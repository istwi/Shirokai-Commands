package com.shirokai.commands.config;

import com.google.gson.*;
import com.shirokai.commands.util.CommandPath;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ConfigManager {
  private final Path path;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  public ConfigManager(Path path) {
    this.path = path;
  }

  public CommandConfig read() throws IOException {
    if (!Files.exists(path)) {
      var config = new CommandConfig();
      save(config);
      return config;
    }
    try (var reader = Files.newBufferedReader(path)) {
      JsonElement json = JsonParser.parseReader(reader);
      validateJsonTypes(json);
      CommandConfig config = gson.fromJson(json, CommandConfig.class);
      validate(config);
      return config;
    } catch (RuntimeException e) {
      throw new IOException("Invalid config: " + e.getMessage(), e);
    }
  }

  private static void validateJsonTypes(JsonElement json) {
    if (!json.isJsonObject()) return;
    JsonElement filters = json.getAsJsonObject().get("tabFilters");
    if (filters == null || !filters.isJsonArray()) return;
    for (JsonElement entry : filters.getAsJsonArray()) {
      if (!entry.isJsonObject()) continue;
      for (String key : List.of("onlinePlayersOnly", "hideAll")) {
        JsonElement value = entry.getAsJsonObject().get(key);
        if (value != null && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()))
          throw new IllegalArgumentException(
              "tabFilters." + key + " must be a JSON boolean (true or false)");
      }
    }
  }

  public CommandConfig copy(CommandConfig c) {
    return gson.fromJson(gson.toJson(c), CommandConfig.class);
  }

  public static void validate(CommandConfig c) {
    if (c == null
        || c.configVersion != 1
        || c.settings == null
        || c.messages == null
        || c.commands == null
        || c.tabFilters == null)
      throw new IllegalArgumentException(
          "Expected configVersion 1, settings, messages and commands");
    if (!Set.of("allow", "block").contains(c.settings.defaultMode))
      throw new IllegalArgumentException("defaultMode must be allow or block");
    if (c.messages.blocked == null
        || c.messages.unknown == null
        || c.messages.reload == null
        || c.messages.aliasConflict == null)
      throw new IllegalArgumentException("Messages cannot be null");
    for (var filter : c.tabFilters) {
      if (filter == null
          || filter.command == null
          || filter.command.isBlank()
          || !filter.command.equals(filter.command.strip())
          || !filter.command.equals(CommandPath.canonical(filter.command))
          || filter.argumentIndex < 1
          || filter.argumentIndex > 64
          || filter.hidePrefixes == null
          || (!filter.onlinePlayersOnly && !filter.hideAll && filter.hidePrefixes.isEmpty())
          || filter.hidePrefixes.stream().anyMatch(prefix -> prefix == null || prefix.isEmpty()))
        throw new IllegalArgumentException(
            "Invalid tabFilter: specify native command, argumentIndex (1..64), and "
                + "onlinePlayersOnly: true, hideAll: true, or nonempty hidePrefixes");
    }
    Set<String> aliases = new HashSet<>();
    Set<String> ids = new HashSet<>();
    for (var e : c.commands.entrySet()) {
      String key = e.getKey();
      var r = e.getValue();
      if (key.isBlank()
          || !key.equals(key.strip())
          || !key.equals(CommandPath.canonical(key))
          || CommandPath.admin(key))
        throw new IllegalArgumentException("Invalid or reserved command path: " + key);
      if (!ids.add(CommandPath.id(key)))
        throw new IllegalArgumentException("Duplicate permission id: " + key);
      if (r == null
          || r.mode == null
          || !Set.of("allow", "hide", "block", "replace").contains(r.mode))
        throw new IllegalArgumentException("Invalid mode: " + key);
      if (r.security && !r.mode.equals("block"))
        throw new IllegalArgumentException("security requires block: " + key);
      if (r.permission != null && r.permission.isBlank())
        throw new IllegalArgumentException("Empty permission: " + key);
      if (r.mode.equals("replace")) {
        if (r.alias == null
            || !r.alias.matches("[a-z0-9_-]+(?: [a-z0-9_-]+)*")
            || CommandPath.admin(r.alias))
          throw new IllegalArgumentException("Invalid alias: " + key);
        if (!aliases.add(r.alias))
          throw new IllegalArgumentException("Duplicate alias: " + r.alias);
      }
    }
    // No alias may overlap another alias or any managed original. This also rejects cycles.
    for (String a : aliases) {
      for (String b : aliases)
        if (!a.equals(b) && (CommandPath.prefix(a, b) || CommandPath.prefix(b, a)))
          throw new IllegalArgumentException("Overlapping aliases: " + a + ", " + b);
      for (String key : c.commands.keySet())
        if (CommandPath.prefix(a, key) || CommandPath.prefix(key, a))
          throw new IllegalArgumentException("Alias overlaps original (or cycle): " + a);
    }
  }

  public void save(CommandConfig config) throws IOException {
    validate(config);
    Files.createDirectories(path.getParent());
    Path tmp = Files.createTempFile(path.getParent(), "shirokai-commands-", ".tmp");
    try {
      Files.writeString(tmp, gson.toJson(config) + "\n", StandardCharsets.UTF_8);
      try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      if (Files.exists(path))
        Files.copy(
            path,
            path.resolveSibling(path.getFileName() + ".bak"),
            StandardCopyOption.REPLACE_EXISTING);
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
