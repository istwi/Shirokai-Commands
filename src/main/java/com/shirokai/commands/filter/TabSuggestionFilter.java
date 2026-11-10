package com.shirokai.commands.filter;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.util.CommandPath;
import java.util.*;

/** Immutable, cosmetic argument policy. The caller supplies the live online-name snapshot. */
public final class TabSuggestionFilter {
  private record Rule(
      List<String> path, int argumentIndex, List<String> prefixes, boolean onlineOnly) {}

  private record Token(String value, int start, int end) {}

  private final List<Rule> rules;
  private final boolean needsOnlinePlayers;

  public TabSuggestionFilter(List<CommandConfig.TabFilter> filters) {
    rules =
        filters.stream()
            .map(
                f ->
                    new Rule(
                        List.of(f.command.split(" ")),
                        f.argumentIndex,
                        List.copyOf(f.hidePrefixes),
                        f.onlinePlayersOnly))
            .toList();
    needsOnlinePlayers = rules.stream().anyMatch(Rule::onlineOnly);
  }

  public boolean requiresOnlinePlayers() {
    return needsOnlinePlayers;
  }

  /** Convenience for whole-command checks; ranged suggestions use the overload below. */
  public boolean visible(String nativeCommand) {
    return visible(nativeCommand, 0, nativeCommand.length(), Set.of());
  }

  /** start/end refer to the inserted text's range in the expanded native candidate. */
  public boolean visible(
      String nativeCommand, int start, int end, Set<String> onlineNamesLowercase) {
    if (rules.isEmpty()) return true;
    List<Token> tokens = tokens(nativeCommand);
    for (var rule : rules) {
      int argument = rule.path().size() + rule.argumentIndex() - 1;
      if (tokens.size() <= argument || !matchesPath(tokens, rule.path())) continue;
      Token value = tokens.get(argument);
      // Do not filter a previously typed recipient while suggesting a currency/message later.
      if (start >= value.end() || end <= value.start()) continue;
      if (rule.prefixes().stream().anyMatch(value.value()::startsWith)) return false;
      if (rule.onlineOnly()
          && !onlineNamesLowercase.contains(value.value().toLowerCase(Locale.ROOT))) return false;
    }
    return true;
  }

  private static boolean matchesPath(List<Token> tokens, List<String> path) {
    for (int i = 0; i < path.size(); i++) {
      String value = tokens.get(i).value();
      if (i == 0) value = CommandPath.canonical(value);
      if (!value.equals(path.get(i))) return false;
    }
    return true;
  }

  private static List<Token> tokens(String command) {
    var result = new ArrayList<Token>();
    var reader = new StringReader(command);
    try {
      while (reader.canRead()) {
        reader.skipWhitespace();
        if (!reader.canRead()) break;
        int start = reader.getCursor();
        String value;
        if (StringReader.isQuotedStringStart(reader.peek())) value = reader.readString();
        else {
          while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
          value = command.substring(start, reader.getCursor());
        }
        result.add(new Token(value, start, reader.getCursor()));
      }
    } catch (CommandSyntaxException e) {
      return List.of();
    }
    return result;
  }
}
