package com.shirokai.commands.filter;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

public final class TabRefreshManager {
  private final AtomicBoolean queued = new AtomicBoolean();

  public void refresh(MinecraftServer server) {
    if (server == null || !queued.compareAndSet(false, true)) return;
    server.execute(
        () -> {
          queued.set(false);
          if (server.isRunning())
            for (var p : server.getPlayerManager().getPlayerList())
              server.getCommandManager().sendCommandTree(p);
        });
  }
}
