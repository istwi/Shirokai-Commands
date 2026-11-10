package com.shirokai.commands.alias;

import static net.minecraft.server.command.CommandManager.*;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.*;
import com.mojang.brigadier.tree.*;
import com.shirokai.commands.*;
import com.shirokai.commands.config.CommandConfig;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.command.*;

public final class AliasManager {
  private final Map<String, CommandNode<ServerCommandSource>> installed = new HashMap<>();

  public void validate(CommandConfig config, CommandDispatcher<ServerCommandSource> dispatcher) {
    for (var e : config.commands.entrySet())
      if (e.getValue().mode.equals("replace")) {
        String root = e.getValue().alias.split(" ")[0];
        if (dispatcher.getRoot().getChild(root) != null
            && dispatcher.getRoot().getChild(root) != installed.get(root))
          throw new IllegalArgumentException(config.messages.aliasConflict + " (" + root + ")");
        String target = e.getKey().split(" ")[0];
        if (dispatcher.getRoot().getChild(target) == null
            || dispatcher.getRoot().getChild(target) == installed.get(target))
          throw new IllegalArgumentException("Alias target does not exist: " + e.getKey());
      }
  }

  public void install(CommandConfig config, CommandDispatcher<ServerCommandSource> dispatcher) {
    // Remove only the root nodes owned by this manager from all Brigadier lookup maps.
    var access =
        (com.shirokai.commands.mixin.CommandNodeAccessor<ServerCommandSource>)
            (Object) dispatcher.getRoot();
    for (String name : installed.keySet()) {
      if (dispatcher.getRoot().getChild(name) != installed.get(name)) continue;
      access.shirokai$children().remove(name);
      access.shirokai$literals().remove(name);
      access.shirokai$arguments().remove(name);
    }
    installed.clear();
    for (var e : config.commands.entrySet())
      if (e.getValue().mode.equals("replace")) {
        String[] parts = e.getValue().alias.split(" ");
        String alias = e.getValue().alias;
        LiteralArgumentBuilder<ServerCommandSource> leaf = literal(parts[parts.length - 1]);
        leaf.requires(source -> ShirokaiCommands.get().aliasAllowed(source, alias));
        leaf.executes(ctx -> ShirokaiCommands.get().executeAlias(ctx.getSource(), alias));
        leaf.then(
            argument("arguments", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> suggest(ctx.getSource(), alias, builder))
                .executes(
                    ctx ->
                        ShirokaiCommands.get()
                            .executeAlias(
                                ctx.getSource(),
                                alias + " " + StringArgumentType.getString(ctx, "arguments"))));
        for (int i = parts.length - 2; i >= 0; i--) {
          String prefix = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, i + 1));
          leaf =
              literal(parts[i])
                  .requires(source -> ShirokaiCommands.get().aliasPrefixAllowed(source, prefix))
                  .then(leaf);
        }
        dispatcher.register(leaf);
        installed.put(parts[0], dispatcher.getRoot().getChild(parts[0]));
      }
  }

  public boolean installed(String root) {
    return installed.containsKey(root);
  }

  private CompletableFuture<Suggestions> suggest(
      ServerCommandSource source, String alias, SuggestionsBuilder builder) {
    var mod = ShirokaiCommands.get();
    String typed = builder.getInput();
    String nativeInput = com.shirokai.commands.util.CommandPath.fromUser(typed);
    var d = mod.decision(source, nativeInput);
    if (!d.execute() || !d.alias()) return Suggestions.empty();
    String expanded = d.expanded();
    int delta = typed.length() - expanded.length();
    var dispatcher = source.getServer().getCommandManager().getDispatcher();
    return dispatcher
        .getCompletionSuggestions(dispatcher.parse(expanded, source))
        .thenCompose(
            suggestions ->
                source
                    .getServer()
                    .submit(
                        () -> {
                          var out =
                              new SuggestionsBuilder(
                                  typed, Math.max(0, suggestions.getRange().getStart() + delta));
                          for (var s : suggestions.getList()) {
                            String next = s.apply(expanded);
                            // Evaluate concrete suggestion paths, including dynamic argument
                            // values.
                            String original =
                                alias
                                    + next.substring(
                                        expanded.length()
                                            - (nativeInput.length() - alias.length()));
                            if (mod.decision(source, original).visible())
                              out.suggest(s.getText(), s.getTooltip());
                          }
                          return out.build();
                        }));
  }
}
