package com.shirokai.commands.mixin;

import com.mojang.brigadier.*;
import com.mojang.brigadier.suggestion.Suggestions;
import com.shirokai.commands.ShirokaiCommands;
import com.shirokai.commands.security.CommandExecutionGuard;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.message.LastSeenMessageList;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
  @Shadow public ServerPlayerEntity player;

  @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true)
  private void shirokai$unsigned(String command, CallbackInfo ci) {
    if (!CommandExecutionGuard.allow(player.getCommandSource(), command)) ci.cancel();
  }

  @Inject(method = "handleCommandExecution", at = @At("HEAD"), cancellable = true)
  private void shirokai$signed(
      ChatCommandSignedC2SPacket packet, LastSeenMessageList seen, CallbackInfo ci) {
    if (!CommandExecutionGuard.allow(player.getCommandSource(), packet.command())) ci.cancel();
  }

  @Redirect(
      method = "onRequestCommandCompletions",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;)Ljava/util/concurrent/CompletableFuture;",
              remap = false))
  private CompletableFuture<Suggestions> shirokai$suggestions(
      CommandDispatcher<ServerCommandSource> dispatcher,
      ParseResults<ServerCommandSource> parse,
      RequestCommandCompletionsC2SPacket packet) {
    var source = player.getCommandSource();
    var mod = ShirokaiCommands.get();
    String input = packet.getPartialCommand();
    return dispatcher
        .getCompletionSuggestions(parse)
        .thenCompose(s -> source.getServer().submit(() -> mod.filterSuggestions(source, input, s)));
  }
}
