package com.shirokai.commands.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.RootCommandNode;
import com.shirokai.commands.*;
import com.shirokai.commands.filter.*;
import com.shirokai.commands.security.*;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.server.command.*;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandManager.class)
public abstract class CommandManagerMixin {
  @Redirect(
      method = "sendCommandTree",
      at =
          @At(value = "NEW", target = "net/minecraft/network/packet/s2c/play/CommandTreeS2CPacket"))
  private CommandTreeS2CPacket shirokai$tree(
      RootCommandNode<CommandSource> root, ServerPlayerEntity player) {
    var mod = ShirokaiCommands.get();
    return new CommandTreeS2CPacket(
        CommandFilter.filter(
            root,
            path -> mod.visible(player.getCommandSource(), path),
            path -> mod.decision(player.getCommandSource(), path).execute()));
  }

  @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
  private void shirokai$internal(
      ParseResults<ServerCommandSource> parse, String command, CallbackInfo ci) {
    var mod = ShirokaiCommands.get();
    if (mod != null
        && mod.guardInternalPlayerCommands()
        && !CommandExecutionGuard.allow(parse.getContext().getSource(), command)) ci.cancel();
  }
}
