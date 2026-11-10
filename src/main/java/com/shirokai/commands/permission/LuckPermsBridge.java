package com.shirokai.commands.permission;

import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.filter.CommandRuleEngine.Subject;
import java.util.function.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;

public final class LuckPermsBridge implements AutoCloseable {
  private Integration integration;
  private boolean unavailable;

  public void start(Runnable refresh) {
    if (FabricLoader.getInstance().isModLoaded("luckperms")) {
      try {
        integration = new Integration(refresh);
      } catch (RuntimeException | LinkageError e) {
        unavailable = true;
        com.shirokai.commands.ShirokaiCommands.LOGGER.error(
            "LuckPerms is installed but its API is unavailable; policy permission checks fail"
                + " closed",
            e);
      }
    }
  }

  public Subject subject(ServerCommandSource source, CommandConfig config) {
    if (source.getPlayer() == null) return new Subject(true, true, p -> true);
    boolean op = source.hasPermissionLevel(4);
    Predicate<String> permissions = p -> check(source, p, true, config);
    boolean bypass =
        (config.settings.opBypass && op) || check(source, "shirokaicommands.bypass", false, config);
    return new Subject(false, bypass, permissions);
  }

  public boolean admin(ServerCommandSource source, String permission, CommandConfig config) {
    return source.getPlayer() == null
        || check(source, permission, source.hasPermissionLevel(4), config)
        || (!permission.equals("shirokaicommands.admin")
            && check(source, "shirokaicommands.admin", source.hasPermissionLevel(4), config));
  }

  public boolean check(
      ServerCommandSource source, String permission, boolean fallback, CommandConfig config) {
    if (!config.settings.luckPermsIntegration) return fallback;
    if (integration == null) return unavailable ? false : fallback;
    return integration.check(source.getPlayer().getUuid(), permission, fallback);
  }

  public void close() {
    if (integration != null) integration.close();
    integration = null;
  }

  // Isolated class loading: the mod runs without the optional LuckPerms API.
  private static final class Integration {
    final net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
    final net.luckperms.api.event.EventSubscription<?> subscription;

    Integration(Runnable refresh) {
      subscription =
          api.getEventBus()
              .subscribe(
                  net.luckperms.api.event.user.UserDataRecalculateEvent.class,
                  event -> refresh.run());
    }

    final LuckPermsPermissionService service = new LuckPermsPermissionService(api);

    boolean check(java.util.UUID uuid, String permission, boolean fallback) {
      return service.check(uuid, permission, fallback);
    }

    void close() {
      subscription.close();
    }
  }
}
