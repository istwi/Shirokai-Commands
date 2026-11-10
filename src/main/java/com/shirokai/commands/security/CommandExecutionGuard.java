package com.shirokai.commands.security;

import com.shirokai.commands.ShirokaiCommands;
import net.minecraft.server.command.ServerCommandSource;

public final class CommandExecutionGuard {
  private CommandExecutionGuard() {}

  public static boolean allow(ServerCommandSource source, String input) {
    var mod = ShirokaiCommands.get();
    if (source.getPlayer() == null) return true;
    if (mod == null) return true;
    if (!mod.allow(source, input)) return false;
    var parse =
        source
            .getServer()
            .getCommandManager()
            .getDispatcher()
            .parse(com.shirokai.commands.util.CommandPath.clean(input), source);
    if (parse.getContext().getNodes().isEmpty()) {
      source.sendError(net.minecraft.text.Text.literal(mod.config().messages.unknown));
      return false;
    }
    return redirectsAllowed(source, input);
  }

  public static boolean redirectsAllowed(ServerCommandSource source, String input) {
    var mod = ShirokaiCommands.get();
    var context =
        source
            .getServer()
            .getCommandManager()
            .getDispatcher()
            .parse(com.shirokai.commands.util.CommandPath.clean(input), source)
            .getContext()
            .getChild();
    // Inspect redirected execution chains, e.g. execute ... run blocked-command.
    while (context != null) {
      if (!context.getNodes().isEmpty()) {
        int start = context.getNodes().getFirst().getRange().getStart();
        if (!mod.allow(
            source, com.shirokai.commands.util.CommandPath.clean(input).substring(start)))
          return false;
      }
      context = context.getChild();
    }
    return true;
  }
}
