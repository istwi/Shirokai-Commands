package com.shirokai.commands.alias;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.util.CommandPath;
import java.util.*;

/** Compiled named-argument mapping, independent of Minecraft and command execution. */
public final class ArgumentTemplate {
  public record Token(String value, String raw, int start, int end) {}

  public record Part(String literal, String name) {
    public boolean variable() {
      return name != null;
    }
  }

  public record Mapping(int sourceStart, int sourceEnd, int targetStart, int targetEnd) {}

  public record Projection(String command, List<Mapping> mappings) {
    public int[] range(int start, int end) {
      int a = Integer.MAX_VALUE, b = -1;
      for (var m : mappings)
        if (start < m.sourceEnd && end > m.sourceStart) {
          a = Math.min(a, m.targetStart);
          b = Math.max(b, m.targetEnd);
        }
      return b < 0 ? new int[] {0, 0} : new int[] {a, b};
    }
  }

  public record Match(
      ArgumentTemplate template, List<Token> input, Map<String, Token> values, boolean complete) {
    public Projection projection() {
      return template.project(this);
    }
  }

  public record Completion(String command, int argumentStart) {}

  private final String id;
  private final CommandConfig.ArgumentAlias spec;
  private final List<String> prefix;
  private final List<String> names;
  private final List<Part> target;

  public ArgumentTemplate(String id, CommandConfig.ArgumentAlias spec) {
    this.id = id;
    this.spec = spec;
    if (id == null
        || !id.matches("[a-z0-9_-]+")
        || spec == null
        || spec.alias == null
        || spec.target == null
        || spec.suggestions == null)
      throw new IllegalArgumentException("Invalid argumentAliases id or definition: " + id);
    List<Token> alias = parse(spec.alias);
    if (alias == null
        || alias.isEmpty()
        || !spec.alias.equals(spec.alias.strip())
        || hasControl(spec.alias)
        || hasControl(spec.target)) bad("Invalid alias/target pattern");
    var literals = new ArrayList<String>();
    var variables = new ArrayList<String>();
    for (var t : alias) {
      String name = variable(t.raw);
      if (name != null) {
        if (variables.contains(name)) bad("Duplicate input argument: " + name);
        variables.add(name);
      } else {
        if (!variables.isEmpty() || !t.raw.matches("[a-z0-9_:/.-]+"))
          bad("Alias requires literal prefix followed by {named} arguments");
        literals.add(t.value);
      }
    }
    if (literals.isEmpty() || CommandPath.admin(literals.getFirst()))
      bad("Invalid/reserved alias root");
    prefix = List.copyOf(literals);
    names = List.copyOf(variables);
    List<Token> output = parse(spec.target);
    if (output == null || output.isEmpty() || !spec.target.equals(spec.target.strip()))
      bad("Invalid target");
    var parts = new ArrayList<Part>();
    var used = new HashSet<String>();
    for (var t : output) {
      String name = variable(t.raw);
      if (name != null) {
        if (!names.contains(name) || !used.add(name))
          bad("Unknown or repeated target argument: " + name);
        parts.add(new Part(null, name));
      } else {
        if (t.raw.indexOf('{') >= 0 || t.raw.indexOf('}') >= 0)
          bad("Placeholders must occupy a complete token");
        parts.add(new Part(t.raw, null));
      }
    }
    if (parts.getFirst().variable()
        || !parts.getFirst().literal.matches("[a-z0-9_:/.-]+")
        || CommandPath.admin(parts.getFirst().literal))
      bad("Target root must be a fixed, non-administrative command");
    if (!used.equals(new HashSet<>(names)))
      bad("Every input argument must occur exactly once in the target");
    target = List.copyOf(parts);
    if (spec.permission != null && spec.permission.isBlank()) bad("Empty permission");
    if (spec.argumentLabel != null && !spec.argumentLabel.matches("[\\p{L}\\p{N}_-]{1,64}"))
      bad("argumentLabel must contain 1..64 letters, digits, underscores or hyphens");
    for (var e : spec.suggestions.entrySet())
      if (!names.contains(e.getKey())
          || e.getValue() == null
          || e.getValue().stream().anyMatch(s -> s == null || hasControl(s)))
        bad("Invalid static suggestions: " + e.getKey());
  }

  private void bad(String message) {
    throw new IllegalArgumentException("argumentAliases." + id + ": " + message);
  }

  private static boolean hasControl(String s) {
    return s.chars().anyMatch(Character::isISOControl);
  }

  private static String variable(String raw) {
    return raw.matches("\\{[A-Za-z_][A-Za-z0-9_]*\\}") ? raw.substring(1, raw.length() - 1) : null;
  }

  public String id() {
    return id;
  }

  public CommandConfig.ArgumentAlias spec() {
    return spec;
  }

  public List<String> prefix() {
    return prefix;
  }

  public List<String> names() {
    return names;
  }

  public String argumentLabel() {
    return spec.argumentLabel != null
        ? spec.argumentLabel
        : (names.isEmpty() ? "argumentos" : String.join("_", names));
  }

  public String root() {
    return prefix.getFirst();
  }

  public String targetRoot() {
    return target.getFirst().literal;
  }

  public String permission() {
    return spec.permission == null ? "shirokaicommands.alias." + id : spec.permission;
  }

  public String fixedTargetPrefix() {
    var p = new ArrayList<String>();
    for (var part : target) {
      if (part.variable()) break;
      p.add(part.literal);
    }
    return String.join(" ", p);
  }

  public Match match(String nativeInput) {
    var tokens = parse(nativeInput);
    if (tokens == null
        || tokens.size() < prefix.size()
        || tokens.size() > prefix.size() + names.size()) return null;
    for (int i = 0; i < prefix.size(); i++)
      if (!prefix.get(i).equals(tokens.get(i).value)) return null;
    Map<String, Token> values = new LinkedHashMap<>();
    for (int i = prefix.size(); i < tokens.size(); i++)
      values.put(names.get(i - prefix.size()), tokens.get(i));
    return new Match(this, tokens, Map.copyOf(values), values.size() == names.size());
  }

  public Projection project(Match match) {
    var out = new StringBuilder();
    var mappings = new ArrayList<Mapping>();
    for (int i = 0; i < target.size(); i++) {
      if (!out.isEmpty()) out.append(' ');
      int start = out.length();
      var part = target.get(i);
      Token source = part.variable() ? match.values.get(part.name) : null;
      // Empty placeholders are used for display-policy evaluation only, never dispatch.
      out.append(part.variable() ? (source == null ? "\"\"" : source.raw) : part.literal);
      if (source != null) mappings.add(new Mapping(source.start, source.end, start, out.length()));
      if (!part.variable())
        for (int j = 0; j < prefix.size(); j++) {
          if ((j == 0 && i == 0) || (j > 0 && prefix.get(j).equals(part.literal))) {
            Token t = match.input.get(j);
            mappings.add(new Mapping(t.start, t.end, start, out.length()));
          }
        }
    }
    return new Projection(out.toString(), List.copyOf(mappings));
  }

  public String expand(Match match) {
    if (!match.complete) throw new IllegalArgumentException("Usage: /" + spec.alias);
    return project(match).command;
  }

  public Completion completion(Map<String, String> values, String selected, String remaining) {
    var out = new StringBuilder();
    for (var part : target) {
      if (!out.isEmpty()) out.append(' ');
      if (part.variable()) {
        if (part.name.equals(selected)) {
          int start = out.length();
          out.append(remaining);
          return new Completion(out.toString(), start);
        }
        if (!values.containsKey(part.name)) return null;
        out.append(values.get(part.name));
      } else out.append(part.literal);
    }
    return null;
  }

  public record PartialInput(List<Token> completed, int start, String remaining) {}

  /** Locate the argument under the completion cursor, including incomplete quotes. */
  public static PartialInput partial(String input) {
    var completed = new ArrayList<Token>();
    var reader = new StringReader(input);
    while (true) {
      reader.skipWhitespace();
      if (!reader.canRead()) return new PartialInput(List.copyOf(completed), input.length(), "");
      int start = reader.getCursor();
      String value;
      try {
        if (StringReader.isQuotedStringStart(reader.peek())) value = reader.readString();
        else {
          while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
          value = input.substring(start, reader.getCursor());
        }
      } catch (CommandSyntaxException e) {
        return new PartialInput(List.copyOf(completed), start, input.substring(start));
      }
      if (!reader.canRead())
        return new PartialInput(List.copyOf(completed), start, input.substring(start));
      completed.add(
          new Token(value, input.substring(start, reader.getCursor()), start, reader.getCursor()));
    }
  }

  public static List<Token> parse(String input) {
    var result = new ArrayList<Token>();
    var reader = new StringReader(input);
    try {
      while (reader.canRead()) {
        reader.skipWhitespace();
        if (!reader.canRead()) break;
        int start = reader.getCursor();
        String value;
        if (StringReader.isQuotedStringStart(reader.peek())) {
          value = reader.readString();
          if (reader.canRead() && !Character.isWhitespace(reader.peek())) return null;
        } else {
          while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
          value = input.substring(start, reader.getCursor());
        }
        result.add(
            new Token(
                value, input.substring(start, reader.getCursor()), start, reader.getCursor()));
      }
    } catch (CommandSyntaxException e) {
      return null;
    }
    return List.copyOf(result);
  }

  public static List<ArgumentTemplate> compile(
      Map<String, CommandConfig.ArgumentAlias> definitions) {
    if (definitions == null) throw new IllegalArgumentException("argumentAliases cannot be null");
    var templates =
        definitions.entrySet().stream()
            .map(e -> new ArgumentTemplate(e.getKey(), e.getValue()))
            .toList();
    for (int i = 0; i < templates.size(); i++)
      for (int j = i + 1; j < templates.size(); j++) {
        String a = String.join(" ", templates.get(i).prefix),
            b = String.join(" ", templates.get(j).prefix);
        if (CommandPath.prefix(a, b) || CommandPath.prefix(b, a))
          throw new IllegalArgumentException("Overlapping argument aliases: " + a + ", " + b);
      }
    for (var source : templates)
      for (var destination : templates) {
        if (source == destination
            && source.spec.extendExisting
            && source.prefix.size() == 1
            && parse(source.fixedTargetPrefix()).size() > 1) continue;
        if (CommandPath.prefix(source.fixedTargetPrefix(), String.join(" ", destination.prefix())))
          throw new IllegalArgumentException(
              "Argument alias target refers to another argument alias: "
                  + source.id()
                  + " -> "
                  + destination.id());
      }
    return templates;
  }
}
