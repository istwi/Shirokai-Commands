package com.shirokai.commands.filter;

import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.util.CommandPath;
import java.util.*;
import java.util.function.*;

public final class CommandRuleEngine {
  public record Subject(boolean console, boolean bypass, Predicate<String> permission) {}

  public record Decision(
      boolean execute,
      boolean visible,
      String rule,
      String message,
      String expanded,
      boolean alias) {}

  private final CommandConfig config;
  private final Map<String, CommandConfig.Rule> rules;
  private final Map<String, Map.Entry<String, CommandConfig.Rule>> aliases = new HashMap<>();

  public CommandRuleEngine(CommandConfig config) {
    this.config = config;
    rules = Map.copyOf(config.commands);
    for (var e : rules.entrySet())
      if (e.getValue().mode.equals("replace")) aliases.put(e.getValue().alias, e);
  }

  public Decision evaluate(String input, Subject who) {
    String path = CommandPath.canonical(input), expanded = CommandPath.clean(input);
    boolean alias = false;
    String aliasPermission = null, aliasId = null;
    for (var a : matching(path, aliases)) {
      var e = a.getValue();
      alias = true;
      aliasId = e.getKey();
      aliasPermission = "shirokaicommands.alias." + CommandPath.id(e.getKey());
      expanded = e.getKey() + path.substring(e.getValue().alias.length());
      path = expanded;
      break;
    }
    if (who.console() || CommandPath.admin(path))
      return new Decision(true, true, "internal", null, expanded, alias);
    if (who.bypass()) return new Decision(true, true, "bypass", null, expanded, alias);
    for (var e : matching(path, rules))
      if (e.getValue().security && e.getValue().mode.equals("block"))
        return deny(e, expanded, alias);
    if (alias && !who.permission().test(aliasPermission))
      return new Decision(false, false, aliasId, null, expanded, true);
    for (var e : matching(path, rules)) {
      var r = e.getValue();
      boolean granted =
          who.permission()
              .test(
                  r.permission == null
                      ? "shirokaicommands.command." + CommandPath.id(e.getKey())
                      : r.permission);
      if (r.mode.equals("hide"))
        return new Decision(true, false, e.getKey(), r.message, expanded, alias);
      if (!granted || r.mode.equals("block") || (r.mode.equals("replace") && !alias))
        return deny(e, expanded, alias);
      return new Decision(true, true, e.getKey(), r.message, expanded, alias);
    }
    boolean allowed =
        !config.settings.whitelistEnabled && config.settings.defaultMode.equals("allow");
    return new Decision(allowed, allowed, "default", null, expanded, alias);
  }

  private static <T> List<Map.Entry<String, T>> matching(String path, Map<String, T> entries) {
    var found = new ArrayList<Map.Entry<String, T>>();
    String prefix = path;
    while (!prefix.isEmpty()) {
      var value = entries.get(prefix);
      if (value != null) found.add(Map.entry(prefix, value));
      int end = prefix.lastIndexOf(' ');
      if (end < 0) break;
      prefix = prefix.substring(0, end);
    }
    return found;
  }

  private Decision deny(Map.Entry<String, CommandConfig.Rule> e, String expanded, boolean alias) {
    return new Decision(false, false, e.getKey(), e.getValue().message, expanded, alias);
  }
}
