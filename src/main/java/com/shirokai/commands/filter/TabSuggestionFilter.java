package com.shirokai.commands.filter;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.util.CommandPath;
import java.util.*;

/**
 * Removes configured argument values from suggestions only; never rewrites commands or execution.
 */
public final class TabSuggestionFilter {
  private record Rule(List<String> path, int argumentIndex, List<String> prefixes) {}

  private final List<Rule> rules;

  public TabSuggestionFilter(List<CommandConfig.TabFilter> filters) {
    rules =
        filters.stream()
            .map(
                f ->
                    new Rule(
                        List.of(f.command.split(" ")),
                        f.argumentIndex,
                        List.copyOf(f.hidePrefixes)))
            .toList();
  }

  public boolean visible(String nativeCommand) {
    if (rules.isEmpty()) return true;
    List<String> tokens = tokens(CommandPath.canonical(nativeCommand));
    for (var rule : rules) {
      int argument = rule.path().size() + rule.argumentIndex() - 1;
      if (tokens.size() <= argument || !tokens.subList(0, rule.path().size()).equals(rule.path()))
        continue;
      String value = tokens.get(argument);
      if (rule.prefixes().stream().anyMatch(value::startsWith)) return false;
    }
    return true;
  }

  private static List<String> tokens(String command) {
    var result = new ArrayList<String>();
    var reader = new StringReader(command);
    try {
      while (reader.canRead()) {
        reader.skipWhitespace();
        if (!reader.canRead()) break;
        if (StringReader.isQuotedStringStart(reader.peek())) result.add(reader.readString());
        else {
          int start = reader.getCursor();
          while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
          result.add(command.substring(start, reader.getCursor()));
        }
      }
    } catch (CommandSyntaxException e) {
      return List.of();
    }
    return result;
  }
}
