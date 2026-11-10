package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.shirokai.commands.permission.LuckPermsPermissionService;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.luckperms.api.*;
import net.luckperms.api.cacheddata.*;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.*;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.Test;

class LuckPermsPermissionTest {
  @SuppressWarnings("unchecked")
  private static <T> T api(Class<T> type, Function<Method, Object> methods) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (object, method, args) -> methods.apply(method));
  }

  private LuckPermsPermissionService service(
      Tristate value, boolean loaded, boolean contextual, AtomicReference<QueryOptions> used) {
    QueryOptions context = api(QueryOptions.class, m -> null),
        global = api(QueryOptions.class, m -> null);
    CachedPermissionData data = api(CachedPermissionData.class, m -> value);
    CachedDataManager cache =
        (CachedDataManager)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {CachedDataManager.class},
                (p, m, args) -> {
                  assertSame(contextual ? context : global, args[0]);
                  used.set((QueryOptions) args[0]);
                  return data;
                });
    User user = api(User.class, m -> cache);
    UserManager users = api(UserManager.class, m -> loaded ? user : null);
    ContextManager contexts =
        api(
            ContextManager.class,
            m ->
                m.getName().equals("getStaticQueryOptions")
                    ? global
                    : Optional.ofNullable(contextual ? context : null));
    LuckPerms lp =
        api(LuckPerms.class, m -> m.getName().equals("getUserManager") ? users : contexts);
    return new LuckPermsPermissionService(lp);
  }

  @Test
  void truePermission() {
    assertTrue(
        service(Tristate.TRUE, true, true, new AtomicReference<>())
            .check(UUID.randomUUID(), "node", false));
  }

  @Test
  void explicitFalseOverridesFallback() {
    assertFalse(
        service(Tristate.FALSE, true, true, new AtomicReference<>())
            .check(UUID.randomUUID(), "node", true));
  }

  @Test
  void undefinedUsesFallback() {
    assertTrue(
        service(Tristate.UNDEFINED, true, true, new AtomicReference<>())
            .check(UUID.randomUUID(), "node", true));
    assertFalse(
        service(Tristate.UNDEFINED, true, true, new AtomicReference<>())
            .check(UUID.randomUUID(), "node", false));
  }

  @Test
  void unloadedUserFailsClosed() {
    assertFalse(
        service(Tristate.TRUE, false, true, new AtomicReference<>())
            .check(UUID.randomUUID(), "node", true));
  }

  @Test
  void contextualCacheIsQueried() {
    var used = new AtomicReference<QueryOptions>();
    assertTrue(service(Tristate.TRUE, true, true, used).check(UUID.randomUUID(), "node", false));
    assertNotNull(used.get());
  }

  @Test
  void staticContextFallback() {
    var used = new AtomicReference<QueryOptions>();
    assertTrue(service(Tristate.TRUE, true, false, used).check(UUID.randomUUID(), "node", false));
    assertNotNull(used.get());
  }
}
