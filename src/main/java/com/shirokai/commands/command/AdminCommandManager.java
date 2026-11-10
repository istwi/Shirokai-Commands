package com.shirokai.commands.command;

import static net.minecraft.server.command.CommandManager.*;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shirokai.commands.*;
import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.util.CommandPath;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class AdminCommandManager {
  private final ShirokaiCommands mod;

  public AdminCommandManager(ShirokaiCommands mod) {
    this.mod = mod;
  }

  public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
    var root =
        literal("shirokaicommands")
            .requires(
                s ->
                    mod.isAdmin(s, "shirokaicommands.admin")
                        || mod.isAdmin(s, "shirokaicommands.reload"));
    root.then(
        literal("help")
            .executes(
                c ->
                    message(
                        c.getSource(),
                        "/sc hide|block|allow \"comando\"; replace \"original\" \"alias\"; remove"
                            + " \"id\"; whitelist on|off; list; inspect \"comando\"; test <jogador>"
                            + " \"comando\"; reload")));
    for (String mode : new String[] {"hide", "block", "allow"})
      root.then(
          literal(mode)
              .requires(s -> mod.isAdmin(s, "shirokaicommands.admin"))
              .then(
                  argument("command", StringArgumentType.string())
                      .executes(
                          c ->
                              mod.mutate(
                                  c.getSource(),
                                  mode,
                                  next -> {
                                    String key =
                                        CommandPath.canonical(
                                            StringArgumentType.getString(c, "command"));
                                    if (next.commands.containsKey(key))
                                      throw new IllegalArgumentException(
                                          "Regra já existe; use remove primeiro: " + key);
                                    next.commands.put(key, new CommandConfig.Rule(mode));
                                  }))));
    root.then(
        literal("replace")
            .requires(s -> mod.isAdmin(s, "shirokaicommands.admin"))
            .then(
                argument("original", StringArgumentType.string())
                    .then(
                        argument("alias", StringArgumentType.string())
                            .executes(
                                c ->
                                    mod.mutate(
                                        c.getSource(),
                                        "replace",
                                        next -> {
                                          String key =
                                              CommandPath.canonical(
                                                  StringArgumentType.getString(c, "original"));
                                          if (next.commands.containsKey(key))
                                            throw new IllegalArgumentException(
                                                "Regra já existe: " + key);
                                          var rule = new CommandConfig.Rule("replace");
                                          rule.alias =
                                              CommandPath.clean(
                                                  StringArgumentType.getString(c, "alias"));
                                          next.commands.put(key, rule);
                                        })))));
    root.then(
        literal("remove")
            .requires(s -> mod.isAdmin(s, "shirokaicommands.admin"))
            .then(
                argument("id", StringArgumentType.string())
                    .executes(
                        c ->
                            mod.mutate(
                                c.getSource(),
                                "remove",
                                next -> {
                                  String id = StringArgumentType.getString(c, "id");
                                  String key =
                                      next.commands.keySet().stream()
                                          .filter(k -> k.equals(id) || CommandPath.id(k).equals(id))
                                          .findFirst()
                                          .orElseThrow(
                                              () ->
                                                  new IllegalArgumentException(
                                                      "Regra não encontrada: " + id));
                                  next.commands.remove(key);
                                }))));
    var whitelist = literal("whitelist").requires(s -> mod.isAdmin(s, "shirokaicommands.admin"));
    for (String toggle : new String[] {"on", "off"})
      whitelist.then(
          literal(toggle)
              .executes(
                  c ->
                      mod.mutate(
                          c.getSource(),
                          "whitelist " + toggle,
                          next -> next.settings.whitelistEnabled = toggle.equals("on"))));
    root.then(whitelist);
    root.then(
        literal("list")
            .requires(s -> mod.isAdmin(s, "shirokaicommands.admin"))
            .executes(
                c -> {
                  message(c.getSource(), "Whitelist: " + mod.config().settings.whitelistEnabled);
                  mod.config()
                      .commands
                      .forEach(
                          (key, rule) ->
                              message(
                                  c.getSource(),
                                  CommandPath.id(key)
                                      + ": /"
                                      + key
                                      + " -> "
                                      + rule.mode
                                      + (rule.alias == null ? "" : " /" + rule.alias)));
                  return 1;
                }));
    root.then(
        literal("inspect")
            .requires(s -> mod.isAdmin(s, "shirokaicommands.admin"))
            .then(
                argument("command", StringArgumentType.string())
                    .executes(
                        c ->
                            inspect(
                                c.getSource(),
                                c.getSource(),
                                StringArgumentType.getString(c, "command")))));
    root.then(
        literal("test")
            .requires(s -> mod.isAdmin(s, "shirokaicommands.admin"))
            .then(
                argument("player", EntityArgumentType.player())
                    .then(
                        argument("command", StringArgumentType.string())
                            .executes(
                                c ->
                                    inspect(
                                        c.getSource(),
                                        EntityArgumentType.getPlayer(c, "player")
                                            .getCommandSource(),
                                        StringArgumentType.getString(c, "command"))))));
    root.then(
        literal("reload")
            .requires(s -> mod.isAdmin(s, "shirokaicommands.reload"))
            .executes(c -> mod.reload(c.getSource())));
    var node = dispatcher.register(root);
    dispatcher.register(literal("sc").requires(node.getRequirement()).redirect(node));
  }

  private int inspect(ServerCommandSource output, ServerCommandSource subject, String input) {
    var d = mod.decision(subject, input);
    var parse =
        subject.getServer().getCommandManager().getDispatcher().parse(d.expanded(), subject);
    return message(
        output,
        "rule="
            + d.rule()
            + " execute="
            + d.execute()
            + " visible="
            + d.visible()
            + " alias="
            + d.alias()
            + " originalParseValid="
            + (!parse.getReader().canRead() && parse.getExceptions().isEmpty())
            + " (simulação; nenhuma execução)");
  }

  private int message(ServerCommandSource source, String message) {
    source.sendFeedback(() -> Text.literal(message), false);
    return 1;
  }
}
