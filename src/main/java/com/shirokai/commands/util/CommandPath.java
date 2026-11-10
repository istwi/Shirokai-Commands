package com.shirokai.commands.util;

import java.util.*;

public final class CommandPath {
  private CommandPath() {}

  public static String clean(String s) {
    s = s.stripLeading();
    while (s.startsWith("/")) s = s.substring(1);
    return s;
  }

  public static String canonical(String s) {
    s = clean(s);
    if (s.isEmpty()) return s;
    int end = s.indexOf(' ');
    String head = end < 0 ? s : s.substring(0, end);
    int colon = head.indexOf(':');
    if (colon >= 0) head = head.substring(colon + 1);
    return head.toLowerCase(Locale.ROOT) + (end < 0 ? "" : s.substring(end));
  }

  public static boolean prefix(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + " ");
  }

  public static String id(String path) {
    return canonical(path).replaceAll("[^a-zA-Z0-9_-]", ".").toLowerCase(Locale.ROOT);
  }

  public static boolean admin(String path) {
    String s = canonical(path);
    return prefix(s, "sc") || prefix(s, "shirokaicommands");
  }

  public static String redacted(String input) {
    String s = clean(input);
    int i = 0;
    while (i < s.length()
        && !Character.isWhitespace(s.charAt(i))
        && !Character.isISOControl(s.charAt(i))) i++;
    return i == s.length() ? s : s.substring(0, i) + " <arguments redacted>";
  }
}
