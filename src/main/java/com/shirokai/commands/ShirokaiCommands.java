package com.shirokai.commands;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.*;
import com.mojang.brigadier.tree.*;
import com.shirokai.commands.alias.*;
import com.shirokai.commands.command.*;
import com.shirokai.commands.config.*;
import com.shirokai.commands.filter.*;
import com.shirokai.commands.logging.*;
import com.shirokai.commands.permission.*;
import com.shirokai.commands.util.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.*;

public final class ShirokaiCommands implements ModInitializer {
  public static final Logger LOGGER = LoggerFactory.getLogger("shirokai_commands");
  private static ShirokaiCommands instance;
  private final ConfigManager configs =
      new ConfigManager(
          FabricLoader.getInstance().getConfigDir().resolve("shirokai-commands.json"));
  private volatile CommandConfig config = new CommandConfig();
  private volatile CommandRuleEngine engine = new CommandRuleEngine(config);
  private final LuckPermsBridge permissions = new LuckPermsBridge();
  private final AliasManager aliases = new AliasManager();
  private final TabRefreshManager tabs = new TabRefreshManager();
  private final CommandAuditLogger audit =
      new CommandAuditLogger(
          FabricLoader.getInstance().getGameDir().resolve("logs/shirokai-commands"));
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "shirokai-config"));
  private MinecraftServer server;
  private boolean changing;
  private final Map<CommandNode<ServerCommandSource>, String> equivalent = new IdentityHashMap<>();

  public static ShirokaiCommands get() {
    return instance;
  }

  public boolean guardInternalPlayerCommands() {
    return config.settings.guardInternalPlayerCommands;
  }

  public CommandConfig config() {
    return configs.copy(config);
  }

  public boolean isAdmin(ServerCommandSource source, String permission) {
    return permissions.admin(source, permission, config);
  }

  public void onInitialize() {
    instance = this;
    try {
      config = configs.read();
      engine = new CommandRuleEngine(config);
    } catch (Exception e) {
      config.settings.defaultMode = "block";
      engine = new CommandRuleEngine(config);
      LOGGER.error(
          "Invalid initial config; fail-closed policy active. Console /sc reload remains"
              + " available.",
          e);
    }
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, access, environment) -> new AdminCommandManager(this).register(dispatcher));
    ServerLifecycleEvents.SERVER_STARTED.register(
        s -> {
          server = s;
          try {
            aliases.validate(config, s.getCommandManager().getDispatcher());
            apply(config);
          } catch (Exception e) {
            config = new CommandConfig();
            config.settings.defaultMode = "block";
            engine = new CommandRuleEngine(config);
            LOGGER.error(
                "Cannot activate config/aliases; fail-closed startup policy active. Fix config then"
                    + " console /sc reload.",
                e);
          }
          permissions.start(
              () -> {
                if (config.settings.autoRefreshTab) tabs.refresh(server);
              });
        });
    ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
        (s, resources, success) -> {
          if (success) {
            try {
              aliases.validate(config, s.getCommandManager().getDispatcher());
              apply(config);
            } catch (Exception e) {
              LOGGER.error("Alias activation after data reload failed", e);
            }
          }
        });
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, s) -> audit.forget(handler.player.getUuid()));
    ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
        (player, origin, destination) -> {
          if (config.settings.autoRefreshTab) tabs.refresh(server);
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(
        s -> {
          permissions.close();
          io.shutdown();
          try {
            io.awaitTermination(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          audit.close();
        });
  }

  private void indexRoutes() {
    equivalent.clear();
    LOGGER.warn(
        "Command policies inspect Brigadier paths/redirects. Independently implemented wrapper"
            + " aliases and direct dispatcher calls by mods cannot be inferred; audit alternate"
            + " routes before production use.");
    var root = server.getCommandManager().getDispatcher().getRoot();
    for (String key : config.commands.keySet()) {
      CommandNode<ServerCommandSource> node = root;
      for (String token : key.split(" ")) {
        node = node.getChild(token);
        if (node == null) break;
      }
      if (node != null) equivalent.put(node, key);
      else
        LOGGER.warn(
            "Rule '{}' includes dynamic or missing nodes; textual matching applies. Review"
                + " alternate mod routes with /sc inspect.",
            key);
    }
    var executors =
        new IdentityHashMap<com.mojang.brigadier.Command<ServerCommandSource>, String>();
    for (var entry : equivalent.entrySet())
      if (entry.getKey().getCommand() != null)
        executors.putIfAbsent(entry.getKey().getCommand(), entry.getValue());
    var queue = new ArrayDeque<CommandNode<ServerCommandSource>>();
    queue.add(root);
    while (!queue.isEmpty()) {
      var node = queue.removeFirst();
      String key = executors.get(node.getCommand());
      if (key != null) equivalent.putIfAbsent(node, key);
      queue.addAll(node.getChildren());
    }
  }

  private String route(ServerCommandSource source, String input) {
    if (server == null) return input;
    var parsed = server.getCommandManager().getDispatcher().parse(CommandPath.clean(input), source);
    var nodes = parsed.getContext().getNodes();
    String result = input;
    for (var parsedNode : nodes) {
      var node = parsedNode.getNode();
      String key = equivalent.get(node);
      Set<CommandNode<ServerCommandSource>> visited =
          Collections.newSetFromMap(new IdentityHashMap<>());
      while (key == null && node.getRedirect() != null && visited.add(node)) {
        node = node.getRedirect();
        key = equivalent.get(node);
      }
      if (key != null)
        result = key + CommandPath.clean(input).substring(parsedNode.getRange().getEnd());
    }
    return result;
  }

  public CommandRuleEngine.Decision decision(ServerCommandSource source, String input) {
    var subject = permissions.subject(source, config);
    var direct = engine.evaluate(input, subject);
    // A real alias is evaluated before route canonicalization; the original permissions are checked
    // by Brigadier.
    if (direct.alias()) return direct;
    return engine.evaluate(route(source, input), subject);
  }

  public boolean visible(ServerCommandSource source, String path) {
    String root = CommandPath.clean(path).split(" ")[0];
    if (aliases.installed(root) && !activeAlias(path)) return false;
    var d = decision(source, path);
    if (d.alias()) {
      String alias = null;
      for (var r : config.commands.values())
        if (r.mode.equals("replace") && CommandPath.prefix(CommandPath.canonical(path), r.alias))
          alias = r.alias;
      return d.visible() && alias != null && aliasAllowed(source, alias);
    }
    if (d.visible()) return true;
    if (aliasPrefixAllowed(source, path)) return true;
    // Non-executable containers may still lead to a more specific permitted child.
    for (var e : config.commands.entrySet())
      if (CommandPath.prefix(e.getKey(), CommandPath.canonical(path))
          && decision(source, e.getKey()).visible()) return true;
    return false;
  }

  private boolean activeAlias(String path) {
    for (var r : config.commands.values())
      if (r.mode.equals("replace")
          && (CommandPath.prefix(path, r.alias) || CommandPath.prefix(r.alias, path))) return true;
    return false;
  }

  public boolean aliasPrefixAllowed(ServerCommandSource source, String prefix) {
    for (var r : config.commands.values())
      if (r.mode.equals("replace")
          && CommandPath.prefix(r.alias, prefix)
          && aliasAllowed(source, r.alias)) return true;
    return false;
  }

  public boolean aliasAllowed(ServerCommandSource source, String alias) {
    if (!activeAlias(alias)) return false;
    var decision = decision(source, alias);
    if (!decision.execute() || !decision.alias()) return false;
    var parse =
        source.getServer().getCommandManager().getDispatcher().parse(decision.expanded(), source);
    return !parse.getReader().canRead() && parse.getExceptions().isEmpty();
  }

  public boolean allow(ServerCommandSource source, String input) {
    var d = decision(source, input);
    if (d.execute()) return true;
    if (!config.settings.silentBlockedCommands)
      source.sendError(Text.literal(d.message() == null ? config.messages.blocked : d.message()));
    var player = source.getPlayer();
    if (player != null && config.settings.logBlockedCommands)
      audit.blocked(player.getUuid(), player.getGameProfile().getName(), input, d.rule());
    return false;
  }

  public int executeAlias(ServerCommandSource source, String input) throws CommandSyntaxException {
    if (!allow(source, input)) return 0;
    var d = decision(source, input);
    if (!d.alias()) return 0;
    // Dispatcher parses with the exact same source. No console elevation, op flags or permission
    // substitution.
    if (!com.shirokai.commands.security.CommandExecutionGuard.redirectsAllowed(
        source, d.expanded())) return 0;
    return source.getServer().getCommandManager().getDispatcher().execute(d.expanded(), source);
  }

  public Suggestions filterSuggestions(
      ServerCommandSource source, String input, Suggestions suggestions) {
    var kept = new ArrayList<Suggestion>();
    for (var s : suggestions.getList()) {
      String candidate = s.apply(input);
      if (input.startsWith("/")) candidate = CommandPath.fromUser(candidate);
      if (visible(source, candidate)) kept.add(s);
    }
    return new Suggestions(suggestions.getRange(), kept);
  }

  private void apply(CommandConfig next) {
    aliases.install(next, server.getCommandManager().getDispatcher());
    config = next;
    engine = new CommandRuleEngine(next);
    indexRoutes();
    tabs.refresh(server);
  }

  public int mutate(ServerCommandSource source, String operation, Consumer<CommandConfig> update) {
    if (changing) {
      source.sendError(Text.literal("Outra alteração está em andamento."));
      return 0;
    }
    CommandConfig next = configs.copy(config);
    try {
      update.accept(next);
      ConfigManager.validate(next);
      aliases.validate(next, server.getCommandManager().getDispatcher());
    } catch (Exception e) {
      source.sendError(Text.literal(e.getMessage()));
      return 0;
    }
    changing = true;
    io.execute(
        () -> {
          try {
            configs.save(next);
            server.execute(
                () -> {
                  apply(next);
                  changing = false;
                  if (next.settings.logBlockedCommands) audit.admin(source.getName(), operation);
                  source.sendFeedback(
                      () -> Text.literal("§aConfiguração salva e TAB atualizado."), false);
                });
          } catch (Exception e) {
            server.execute(
                () -> {
                  changing = false;
                  source.sendError(Text.literal("Falha ao salvar: " + e.getMessage()));
                });
          }
        });
    return 1;
  }

  public int reload(ServerCommandSource source) {
    if (changing) return 0;
    changing = true;
    io.execute(
        () -> {
          try {
            var next = configs.read();
            server.execute(
                () -> {
                  try {
                    aliases.validate(next, server.getCommandManager().getDispatcher());
                    apply(next);
                    source.sendFeedback(() -> Text.literal(next.messages.reload), false);
                    if (next.settings.logBlockedCommands) audit.admin(source.getName(), "reload");
                  } catch (Exception e) {
                    source.sendError(Text.literal(e.getMessage()));
                  } finally {
                    changing = false;
                  }
                });
          } catch (Exception e) {
            server.execute(
                () -> {
                  changing = false;
                  source.sendError(
                      Text.literal(
                          "JSON rejeitado; configuração anterior preservada: " + e.getMessage()));
                });
          }
        });
    return 1;
  }
}
