package com.shirokai.commands.permission;

import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.util.Tristate;

/** Uses LuckPerms' own contextual cache; never expands groups or inheritance itself. */
public final class LuckPermsPermissionService {
  private final LuckPerms api;

  public LuckPermsPermissionService(LuckPerms api) {
    this.api = api;
  }

  public boolean check(UUID uuid, String permission, boolean fallback) {
    var user = api.getUserManager().getUser(uuid);
    if (user == null) return false; // Fail closed while an online user's data is unavailable.
    var options =
        api.getContextManager()
            .getQueryOptions(user)
            .orElse(api.getContextManager().getStaticQueryOptions());
    var value = user.getCachedData().getPermissionData(options).checkPermission(permission);
    return value == Tristate.TRUE || (value == Tristate.UNDEFINED && fallback);
  }
}
