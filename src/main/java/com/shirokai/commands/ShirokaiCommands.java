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
  private volatile TabSuggestionFilter argumentSuggestions =
      new TabSuggestionFilter(config.tabFilters);
  private final LuckPermsBridge permissions = new LuckPermsBridge();
  private final AliasManager aliases = new AliasManager();
  private final ArgumentAliasManager argumentAliases = new ArgumentAliasManager();
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
            validateAliases(config, s.getCommandManager().getDispatcher());
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
              validateAliases(config, s.getCommandManager().getDispatcher());
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

  private void validateAliases(
      CommandConfig next, com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
    aliases.validate(next, dispatcher);
    argumentAliases.validate(next, dispatcher, aliases);
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
    var named = argumentAliases.match(input);
    if (named != null) {
      String expanded = named.projection().command();
      var target = engine.evaluate(expanded, subject);
      boolean allowed = argumentAliasAllowed(source, named.template());
      var routePolicy = engine.evaluate(input, subject);
      boolean explicit = !routePolicy.rule().equals("default");
      return new CommandRuleEngine.Decision(
          allowed && target.execute() && (!explicit || routePolicy.execute()),
          allowed && target.visible() && (!explicit || routePolicy.visible()),
          explicit && !routePolicy.execute()
              ? routePolicy.rule()
              : "argumentAliases." + named.template().id(),
          explicit && routePolicy.message() != null ? routePolicy.message() : target.message(),
          expanded,
          true);
    }
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
    if (argumentAliases.match(path) != null) return d.visible();
    if (d.alias()) {
      String alias = null;
      for (var r : config.commands.values())
        if (r.mode.equals("replace") && CommandPath.prefix(CommandPath.canonical(path), r.alias))
          alias = r.alias;
      return d.visible() && alias != null && aliasAllowed(source, alias);
    }
    if (d.visible()) return true;
    if (aliasPrefixAllowed(source, path) || argumentAliasPrefixVisible(source, path)) return true;
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

  public boolean argumentAliasAllowed(ServerCommandSource source, ArgumentTemplate template) {
    var subject = permissions.subject(source, config);
    boolean permission =
        subject.console() || subject.bypass() || subject.permission().test(template.permission());
    var match = template.match(String.join(" ", template.prefix()));
    var routePolicy = engine.evaluate(String.join(" ", template.prefix()), subject);
    return permission
        && (routePolicy.rule().equals("default") || routePolicy.execute())
        && engine.evaluate(match.projection().command(), subject).execute()
        && argumentAliases.originalAllows(template, source);
  }

  public boolean argumentAliasPrefixAllowed(ServerCommandSource source, String prefix) {
    for (var t : argumentAliases.templates())
      if (CommandPath.prefix(String.join(" ", t.prefix()), prefix)
          && argumentAliasAllowed(source, t)) return true;
    return false;
  }

  private boolean argumentAliasPrefixVisible(ServerCommandSource source, String prefix) {
    var subject = permissions.subject(source, config);
    for (var template : argumentAliases.templates()) {
      String path = String.join(" ", template.prefix());
      var route = engine.evaluate(path, subject);
      if (CommandPath.prefix(path, prefix)
          && argumentAliasAllowed(source, template)
          && engine.evaluate(template.match(path).projection().command(), subject).visible()
          && (route.rule().equals("default") || route.visible())) return true;
    }
    return false;
  }

  public int executeArgumentAlias(String id, ServerCommandSource source, String input)
      throws CommandSyntaxException {
    var match = argumentAliases.match(input);
    if (match == null || !match.template().id().equals(id) || !match.complete()) {
      var template =
          argumentAliases.templates().stream().filter(t -> t.id().equals(id)).findFirst();
      source.sendError(
          Text.literal(
              template
                  .map(t -> "Uso: /" + t.spec().alias)
                  .orElse("Alias recarregado ou removido.")));
      return 0;
    }
    if (!allow(source, input)) return 0;
    String target = match.template().expand(match);
    if (!com.shirokai.commands.security.CommandExecutionGuard.redirectsAllowed(source, target))
      return 0;
    return argumentAliases.executeOriginal(target, source);
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
    if (suggestions.getList().isEmpty()) return suggestions;
    // The existing completion interceptor schedules this method on the server thread.
    Set<String> onlineNames =
        argumentSuggestions.requiresOnlinePlayers()
            ? source.getServer().getPlayerManager().getPlayerList().stream()
                .map(p -> p.getGameProfile().getName().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
            : Set.of();
    var kept = new ArrayList<Suggestion>();
    for (var suggestion : suggestions.getList()) {
      String candidate = suggestion.apply(input);
      int prefix = input.startsWith("/") ? 1 : 0;
      if (prefix == 1) candidate = CommandPath.fromUser(candidate);
      if (!visible(source, candidate)) continue;
      String expanded = decision(source, candidate).expanded();
      int start, end;
      var named = argumentAliases.match(candidate);
      if (named != null) {
        var projection = named.projection();
        expanded = projection.command();
        int[] range =
            projection.range(
                suggestion.getRange().getStart() - prefix,
                suggestion.getRange().getStart() - prefix + suggestion.getText().length());
        start = range[0];
        end = range[1];
      } else {
        int offset = expanded.length() - candidate.length() - prefix;
        start = Math.max(0, suggestion.getRange().getStart() + offset);
        end =
            Math.max(
                start, suggestion.getRange().getStart() + suggestion.getText().length() + offset);
      }
      if (argumentSuggestions.visible(expanded, start, end, onlineNames)) kept.add(suggestion);
    }
    return new Suggestions(suggestions.getRange(), kept);
  }

  private void apply(CommandConfig next) {
    argumentAliases.restore(server.getCommandManager().getDispatcher());
    aliases.install(next, server.getCommandManager().getDispatcher());
    argumentAliases.install(next, server.getCommandManager().getDispatcher());
    config = next;
    engine = new CommandRuleEngine(next);
    argumentSuggestions = new TabSuggestionFilter(next.tabFilters);
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
      validateAliases(next, server.getCommandManager().getDispatcher());
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
                    validateAliases(next, server.getCommandManager().getDispatcher());
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
