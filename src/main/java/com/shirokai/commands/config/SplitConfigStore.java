package com.shirokai.commands.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Five editable files, validated as one model; a rollback journal protects multi-file saves. */
final class SplitConfigStore {
  static final List<String> FILES =
      List.of(
          "settings.json", "messages.json", "commands.json", "aliases.json", "tab-filters.json");
  private static final String JOURNAL = ".pending-config.json";
  private final Path directory, legacyPath;
  private final ConfigManager legacy;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  SplitConfigStore(Path directory, Path legacyPath) {
    this.directory = directory;
    this.legacyPath = legacyPath;
    legacy = new ConfigManager(legacyPath);
  }

  CommandConfig read() throws IOException {
    recover();
    if (FILES.stream().noneMatch(name -> Files.exists(directory.resolve(name)))) {
      boolean migrate = Files.exists(legacyPath);
      CommandConfig config = migrate ? legacy.read() : new CommandConfig();
      ConfigManager.validate(config);
      save(config);
      if (migrate)
        LoggerFactory.getLogger("shirokai_commands")
            .info(
                "Migrated legacy configuration to {}. Original file {} was preserved and is no"
                    + " longer active.",
                directory,
                legacyPath);
      return config;
    }
    try {
      var settings = part("settings.json", Set.of("configVersion", "settings"));
      var messages = part("messages.json", Set.of("messages"));
      var commands =
          required(part("commands.json", Set.of("commands")), "commands")
              .getAsJsonObject()
              .deepCopy();
      var aliases = part("aliases.json", Set.of("prefixAliases", "argumentAliases"));
      var prefix = required(aliases, "prefixAliases").getAsJsonObject();
      for (var entry : commands.entrySet()) {
        if (entry.getValue().isJsonObject()
            && "replace"
                .equals(
                    entry.getValue().getAsJsonObject().has("mode")
                        ? entry.getValue().getAsJsonObject().get("mode").getAsString()
                        : ""))
          throw new IllegalArgumentException(
              "Move replacement rule '" + entry.getKey() + "' to aliases.json / prefixAliases");
      }
      for (var entry : prefix.entrySet()) {
        if (commands.has(entry.getKey()))
          throw new IllegalArgumentException(
              "Duplicate command rule across commands.json and aliases.json: " + entry.getKey());
        var rule = entry.getValue().getAsJsonObject();
        if (!rule.has("mode") || !"replace".equals(rule.get("mode").getAsString()))
          throw new IllegalArgumentException(
              "prefixAliases entries require mode replace: " + entry.getKey());
        commands.add(entry.getKey(), entry.getValue());
      }
      var combined = new JsonObject();
      combined.add("configVersion", required(settings, "configVersion"));
      combined.add("settings", required(settings, "settings"));
      combined.add("messages", required(messages, "messages"));
      combined.add("commands", commands);
      combined.add("argumentAliases", required(aliases, "argumentAliases"));
      combined.add(
          "tabFilters", required(part("tab-filters.json", Set.of("tabFilters")), "tabFilters"));
      return legacy.decode(combined);
    } catch (RuntimeException e) {
      throw new IOException("Invalid configuration in " + directory + ": " + e.getMessage(), e);
    }
  }

  private JsonObject part(String filename, Set<String> keys) throws IOException {
    Path path = directory.resolve(filename);
    if (!Files.isRegularFile(path))
      throw new IOException("Missing required configuration file: " + path);
    JsonObject value = readObject(path);
    for (String key : value.keySet())
      if (!keys.contains(key))
        throw new IOException("Unknown section '" + key + "' in " + filename);
    return value;
  }

  private static JsonElement required(JsonObject object, String key) {
    if (!object.has(key) || object.get(key).isJsonNull())
      throw new IllegalArgumentException("Missing configuration section: " + key);
    return object.get(key);
  }

  private static JsonObject readObject(Path path) throws IOException {
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (RuntimeException e) {
      throw new IOException("Invalid JSON in " + path.getFileName() + ": " + e.getMessage(), e);
    }
  }

  void save(CommandConfig config) throws IOException {
    ConfigManager.validate(config);
    Files.createDirectories(directory);
    recover();
    var tree = gson.toJsonTree(config).getAsJsonObject();
    var next = new LinkedHashMap<String, JsonObject>();
    var settings = new JsonObject();
    settings.add("configVersion", tree.get("configVersion"));
    settings.add("settings", tree.get("settings"));
    next.put("settings.json", settings);
    var messages = new JsonObject();
    messages.add("messages", tree.get("messages"));
    next.put("messages.json", messages);
    var rules = new JsonObject();
    var prefix = new JsonObject();
    for (var e : config.commands.entrySet())
      ("replace".equals(e.getValue().mode) ? prefix : rules)
          .add(e.getKey(), gson.toJsonTree(e.getValue()));
    var commandFile = new JsonObject();
    commandFile.add("commands", rules);
    next.put("commands.json", commandFile);
    var aliasFile = new JsonObject();
    aliasFile.add("prefixAliases", prefix);
    aliasFile.add("argumentAliases", tree.get("argumentAliases"));
    next.put("aliases.json", aliasFile);
    var tab = new JsonObject();
    tab.add("tabFilters", tree.get("tabFilters"));
    next.put("tab-filters.json", tab);
    var previous = new JsonObject();
    for (String name : FILES) {
      Path file = directory.resolve(name);
      if (Files.exists(file))
        previous.addProperty(name, Files.readString(file, StandardCharsets.UTF_8));
      else previous.add(name, JsonNull.INSTANCE);
    }
    var journal = new JsonObject();
    journal.add("files", previous);
    atomicWrite(directory.resolve(JOURNAL), gson.toJson(journal));
    try {
      for (String name : FILES)
        if (!previous.get(name).isJsonNull())
          atomicWrite(directory.resolve(name + ".bak"), previous.get(name).getAsString());
      for (var entry : next.entrySet())
        atomicWrite(directory.resolve(entry.getKey()), gson.toJson(entry.getValue()) + "\n");
      Files.delete(directory.resolve(JOURNAL));
    } catch (IOException | RuntimeException error) {
      try {
        recover();
      } catch (IOException recovery) {
        error.addSuppressed(recovery);
      }
      if (error instanceof IOException io) throw io;
      throw new IOException("Could not save split configuration", error);
    }
  }

  private void recover() throws IOException {
    Path pending = directory.resolve(JOURNAL);
    if (!Files.exists(pending)) return;
    JsonObject journal = readObject(pending);
    JsonObject files;
    try {
      files = required(journal, "files").getAsJsonObject();
      if (!files.keySet().equals(new HashSet<>(FILES)))
        throw new IllegalArgumentException("Invalid managed-file snapshot");
      for (var value : files.entrySet())
        if (!value.getValue().isJsonNull()
            && (!value.getValue().isJsonPrimitive()
                || !value.getValue().getAsJsonPrimitive().isString()))
          throw new IllegalArgumentException("Invalid snapshot value");
    } catch (RuntimeException e) {
      throw new IOException("Invalid recovery journal; configuration was not overwritten", e);
    }
    for (String name : FILES) {
      JsonElement value = files.get(name);
      Path target = directory.resolve(name);
      if (value.isJsonNull()) Files.deleteIfExists(target);
      else atomicWrite(target, value.getAsString());
    }
    Files.delete(pending);
  }

  private static void atomicWrite(Path path, String content) throws IOException {
    Path tmp = Files.createTempFile(path.getParent(), ".shirokai-", ".tmp");
    try {
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
