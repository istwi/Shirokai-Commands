package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.shirokai.commands.config.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitConfigStoreTest {
  @TempDir Path root;

  private Path folder() {
    return root.resolve("shirokai-commands");
  }

  private Path legacy() {
    return root.resolve("shirokai-commands.json");
  }

  private ConfigManager store() {
    return new ConfigManager(folder(), legacy());
  }

  private JsonObject json(String name) throws Exception {
    return JsonParser.parseString(Files.readString(folder().resolve(name))).getAsJsonObject();
  }

  private void write(String name, JsonObject object) throws Exception {
    Files.writeString(folder().resolve(name), object.toString());
  }

  private CommandConfig populated() {
    var c = new CommandConfig();
    c.settings.opBypass = false;
    c.messages.blocked = "custom";
    c.commands.put("help", new CommandConfig.Rule("block"));
    var replace = new CommandConfig.Rule("replace");
    replace.alias = "money";
    c.commands.put("balance", replace);
    var alias = new CommandConfig.ArgumentAlias();
    alias.alias = "pay cash {amount} {player}";
    alias.target = "pay {amount} {player} cash";
    alias.extendExisting = true;
    c.argumentAliases.put("pay_cash", alias);
    var f = new CommandConfig.TabFilter();
    f.command = "pay";
    f.argumentIndex = 2;
    f.onlinePlayersOnly = true;
    c.tabFilters.add(f);
    return c;
  }

  @Test
  void firstStartCreatesFiveManagedFiles() throws Exception {
    store().read();
    for (String name :
        List.of(
            "settings.json", "messages.json", "commands.json", "aliases.json", "tab-filters.json"))
      assertTrue(Files.isRegularFile(folder().resolve(name)));
    assertFalse(Files.exists(legacy()));
  }

  @Test
  void migrationPreservesEveryFeatureAndLegacyBytes() throws Exception {
    new ConfigManager(legacy()).save(populated());
    String before = Files.readString(legacy());
    var c = store().read();
    assertFalse(c.settings.opBypass);
    assertEquals("custom", c.messages.blocked);
    assertEquals("replace", c.commands.get("balance").mode);
    assertTrue(c.argumentAliases.containsKey("pay_cash"));
    assertTrue(c.tabFilters.getFirst().onlinePlayersOnly);
    assertEquals(before, Files.readString(legacy()));
  }

  @Test
  void migrationRoundTripSeparatesAliasAndPolicyRules() throws Exception {
    store().save(populated());
    assertTrue(json("commands.json").getAsJsonObject("commands").has("help"));
    assertFalse(json("commands.json").getAsJsonObject("commands").has("balance"));
    assertTrue(json("aliases.json").getAsJsonObject("prefixAliases").has("balance"));
    assertTrue(store().read().commands.containsKey("balance"));
  }

  @Test
  void legacyFileStopsBeingActiveAfterMigration() throws Exception {
    new ConfigManager(legacy()).save(populated());
    store().read();
    Files.writeString(legacy(), "{invalid");
    assertFalse(store().read().settings.opBypass);
  }

  @Test
  void invalidLegacyIsNotOverwrittenOrMigrated() throws Exception {
    Files.writeString(legacy(), "{invalid");
    assertThrows(java.io.IOException.class, () -> store().read());
    assertEquals("{invalid", Files.readString(legacy()));
    assertFalse(Files.exists(folder()));
  }

  @Test
  void editsToSettingsAndMessagesAreCombined() throws Exception {
    store().read();
    var settings = json("settings.json");
    settings.getAsJsonObject("settings").addProperty("opBypass", false);
    write("settings.json", settings);
    var messages = json("messages.json");
    messages.getAsJsonObject("messages").addProperty("reload", "changed");
    write("messages.json", messages);
    assertFalse(store().read().settings.opBypass);
    assertEquals("changed", store().read().messages.reload);
  }

  @Test
  void missingFileIsRejectedRatherThanSilentlyReset() throws Exception {
    store().read();
    Files.delete(folder().resolve("commands.json"));
    assertThrows(java.io.IOException.class, () -> store().read());
    assertFalse(Files.exists(folder().resolve("commands.json")));
  }

  @Test
  void invalidSinglePartKeepsCallerSnapshotAndFiles() throws Exception {
    store().save(populated());
    var prior = store().read();
    String commands = Files.readString(folder().resolve("commands.json"));
    Files.writeString(folder().resolve("tab-filters.json"), "{invalid");
    assertThrows(java.io.IOException.class, () -> store().read());
    assertTrue(prior.tabFilters.getFirst().onlinePlayersOnly);
    assertEquals(commands, Files.readString(folder().resolve("commands.json")));
  }

  @Test
  void backupsAreCreatedBeforeAdministrativeSave() throws Exception {
    var c = store().read();
    c.settings.opBypass = false;
    store().save(c);
    assertTrue(Files.isRegularFile(folder().resolve("settings.json.bak")));
    assertTrue(
        JsonParser.parseString(Files.readString(folder().resolve("settings.json.bak")))
            .getAsJsonObject()
            .getAsJsonObject("settings")
            .get("opBypass")
            .getAsBoolean());
    assertFalse(store().read().settings.opBypass);
    assertFalse(Files.exists(folder().resolve(".pending-config.json")));
  }

  @Test
  void unknownUserFilesArePreserved() throws Exception {
    Files.createDirectories(folder());
    Files.writeString(folder().resolve("notes.txt"), "keep me");
    var c = store().read();
    store().save(c);
    assertEquals("keep me", Files.readString(folder().resolve("notes.txt")));
  }

  @Test
  void duplicateRuleAcrossFilesRejected() throws Exception {
    store().save(populated());
    var aliases = json("aliases.json");
    var r = new JsonObject();
    r.addProperty("mode", "replace");
    r.addProperty("alias", "assist");
    aliases.getAsJsonObject("prefixAliases").add("help", r);
    write("aliases.json", aliases);
    assertThrows(java.io.IOException.class, () -> store().read());
  }

  @Test
  void replacementInCommandsFileRejectedWithUsefulError() throws Exception {
    store().read();
    var commands = json("commands.json");
    var r = new JsonObject();
    r.addProperty("mode", "replace");
    r.addProperty("alias", "money");
    commands.getAsJsonObject("commands").add("balance", r);
    write("commands.json", commands);
    var error = assertThrows(java.io.IOException.class, () -> store().read());
    assertTrue(error.getMessage().contains("aliases.json"));
  }

  @Test
  void inactiveAliasRuleDoesNotBelongInPrefixAliases() throws Exception {
    store().read();
    var aliases = json("aliases.json");
    var r = new JsonObject();
    r.addProperty("mode", "hide");
    aliases.getAsJsonObject("prefixAliases").add("help", r);
    write("aliases.json", aliases);
    assertThrows(java.io.IOException.class, () -> store().read());
  }

  @Test
  void strictBooleanValidationStillAppliesAcrossFiles() throws Exception {
    store().read();
    var tab = new JsonObject();
    tab.add(
        "tabFilters",
        JsonParser.parseString(
            "[{\"command\":\"pay\",\"argumentIndex\":2,\"onlinePlayersOnly\":\"true\"}]"));
    write("tab-filters.json", tab);
    assertThrows(java.io.IOException.class, () -> store().read());
  }

  @Test
  void interruptedSaveJournalRestoresAllPreviousParts() throws Exception {
    store().save(populated());
    var files = new JsonObject();
    for (String name :
        List.of(
            "settings.json", "messages.json", "commands.json", "aliases.json", "tab-filters.json"))
      files.addProperty(name, Files.readString(folder().resolve(name)));
    var journal = new JsonObject();
    journal.add("files", files);
    write(".pending-config.json", journal);
    Files.writeString(folder().resolve("commands.json"), "{partial");
    Files.writeString(folder().resolve("settings.json"), "{partial");
    var c = store().read();
    assertFalse(c.settings.opBypass);
    assertTrue(c.commands.containsKey("help"));
    assertEquals(
        files.get("settings.json").getAsString(),
        Files.readString(folder().resolve("settings.json")));
    assertFalse(Files.exists(folder().resolve(".pending-config.json")));
  }

  @Test
  void malformedRecoveryCannotChangeUnmanagedFile() throws Exception {
    store().read();
    Path victim = root.resolve("victim.txt");
    Files.writeString(victim, "untouched");
    var journal = new JsonObject();
    var files = new JsonObject();
    files.add("../victim.txt", JsonNull.INSTANCE);
    journal.add("files", files);
    write(".pending-config.json", journal);
    assertThrows(java.io.IOException.class, () -> store().read());
    assertEquals("untouched", Files.readString(victim));
  }

  @Test
  void ioFailureRollsBackAndPreservesUnknownBackupDirectory() throws Exception {
    var c = store().read();
    Files.createDirectories(folder().resolve("settings.json.bak"));
    Files.writeString(folder().resolve("settings.json.bak/user.txt"), "keep");
    c.settings.opBypass = false;
    assertThrows(java.io.IOException.class, () -> store().save(c));
    assertTrue(store().read().settings.opBypass);
    assertEquals("keep", Files.readString(folder().resolve("settings.json.bak/user.txt")));
    assertFalse(Files.exists(folder().resolve(".pending-config.json")));
  }
}
