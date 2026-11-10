package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.shirokai.commands.config.*;
import com.shirokai.commands.filter.*;
import com.shirokai.commands.util.CommandPath;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PolicyTest {
  CommandConfig c;
  CommandRuleEngine.Subject user = new CommandRuleEngine.Subject(false, false, p -> true);

  @BeforeEach
  void setup() {
    c = new CommandConfig();
  }

  void rule(String path, String mode) {
    c.commands.put(path, new CommandConfig.Rule(mode));
  }

  CommandRuleEngine.Decision eval(String path) {
    return new CommandRuleEngine(c).evaluate(path, user);
  }

  @Test
  void aliasKeepsQuotedArguments() {
    rule("balance", "replace");
    c.commands.get("balance").alias = "money";
    assertEquals("balance \"two  spaces\"", eval("money \"two  spaces\"").expanded());
  }

  @Test
  void hideIsNotBlock() {
    rule("calculate", "hide");
    assertTrue(eval("calculate 1 + 2").execute());
    assertFalse(eval("calculate").visible());
  }

  @Test
  void blockBeforeExecution() {
    rule("center", "block");
    assertFalse(eval("center").execute());
    assertFalse(eval("center").visible());
  }

  @Test
  void replacement() {
    rule("balance", "replace");
    c.commands.get("balance").alias = "money";
    assertFalse(eval("balance").execute());
    assertTrue(eval("money 100").execute());
    assertEquals("balance 100", eval("money 100").expanded());
  }

  @Test
  void bypassIgnoresRules() {
    rule("center", "block");
    user = new CommandRuleEngine.Subject(false, true, p -> true);
    assertTrue(eval("center").execute());
    assertTrue(eval("center").visible());
  }

  @Test
  void explicitPermissionsDenyAllowRule() {
    rule("kit", "allow");
    user = new CommandRuleEngine.Subject(false, false, p -> false);
    assertFalse(eval("kit").execute());
  }

  @Test
  void hideStaysExecutableWithDeniedVisibilityPermission() {
    rule("kit", "hide");
    user = new CommandRuleEngine.Subject(false, false, p -> false);
    assertTrue(eval("kit").execute());
  }

  @Test
  void aliasPermissionDoesNotGrantTargetPermission() {
    rule("balance", "replace");
    c.commands.get("balance").alias = "money";
    user =
        new CommandRuleEngine.Subject(
            false, false, p -> !p.equals("shirokaicommands.command.balance"));
    assertFalse(eval("money").execute());
  }

  @Test
  void aliasCanBeIndividuallyDenied() {
    rule("balance", "replace");
    c.commands.get("balance").alias = "money";
    user =
        new CommandRuleEngine.Subject(false, false, p -> !p.startsWith("shirokaicommands.alias."));
    assertFalse(eval("money").execute());
  }

  @Test
  void whitelist() {
    c.settings.whitelistEnabled = true;
    rule("kit", "allow");
    assertTrue(eval("kit").execute());
    assertFalse(eval("help").execute());
    assertFalse(eval("kitten").execute());
  }

  @Test
  void subcommands() {
    rule("kit", "allow");
    rule("kit admin", "block");
    assertTrue(eval("kit claim inicial").execute());
    assertFalse(eval("kit admin foo").execute());
    assertTrue(eval("kit administrator").execute());
  }

  @Test
  void securityHasPriorityOverSpecificAllow() {
    rule("kit", "block");
    c.commands.get("kit").security = true;
    rule("kit claim", "allow");
    assertFalse(eval("kit claim").execute());
  }

  @Test
  void specificAllowOverridesOrdinaryBlock() {
    rule("kit", "block");
    rule("kit claim", "allow");
    assertTrue(eval("kit claim").execute());
  }

  @Test
  void namespaceCannotBypass() {
    rule("give", "block");
    assertFalse(eval("minecraft:give Steve stone").execute());
    assertFalse(eval("other:give Steve stone").execute());
  }

  @Test
  void rootCaseCannotBypass() {
    rule("give", "block");
    assertFalse(eval("GIVE Steve stone").execute());
  }

  @Test
  void consoleUnrestricted() {
    rule("stop", "block");
    c.settings.whitelistEnabled = true;
    user = new CommandRuleEngine.Subject(true, false, p -> false);
    assertTrue(eval("stop").execute());
  }

  @Test
  void adminRoutesRemainRecoverable() {
    c.settings.whitelistEnabled = true;
    assertTrue(eval("sc reload").execute());
    assertTrue(eval("shirokaicommands reload").visible());
  }

  @Test
  void cyclicAliasesRejected() {
    rule("a", "replace");
    c.commands.get("a").alias = "b";
    rule("b", "replace");
    c.commands.get("b").alias = "a";
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void duplicatesRejected() {
    rule("a", "replace");
    c.commands.get("a").alias = "money";
    rule("b", "replace");
    c.commands.get("b").alias = "money";
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void invalidAliasRejected() {
    rule("a", "replace");
    c.commands.get("a").alias = "minecraft:give";
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void reservedRulesRejected() {
    rule("sc", "block");
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void nullSectionsRejected() {
    c.settings = null;
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void hideInWhitelistStaysHide() {
    c.settings.whitelistEnabled = true;
    rule("calculate", "hide");
    assertTrue(eval("calculate").execute());
    assertFalse(eval("calculate").visible());
  }

  @Test
  void auditDoesNotWriteSecrets(@TempDir Path dir) throws Exception {
    try (var log = new com.shirokai.commands.logging.CommandAuditLogger(dir)) {
      log.blocked(
          java.util.UUID.randomUUID(),
          "Player",
          "login password123 token=SECRET",
          "login password123");
    }
    String text = Files.readString(dir.resolve("audit.log"));
    assertFalse(text.contains("SECRET"));
    assertFalse(text.contains("password123"));
    assertTrue(text.contains("BLOCK"));
  }

  @Test
  void sensitiveWhitespaceRedacted() {
    assertEquals("login <arguments redacted>", CommandPath.redacted("login\tsecret"));
  }

  @Test
  void sensitiveArgumentsRedacted() {
    String s = CommandPath.redacted("login password123 token=SECRET 123456");
    assertEquals("login <arguments redacted>", s);
    assertFalse(s.contains("SECRET"));
  }

  @Test
  void persistenceAndBackup(@TempDir Path dir) throws Exception {
    var manager = new ConfigManager(dir.resolve("config.json"));
    manager.save(c);
    rule("kit", "block");
    manager.save(c);
    assertEquals("block", manager.read().commands.get("kit").mode);
    assertTrue(Files.exists(dir.resolve("config.json.bak")));
  }

  @Test
  void invalidJsonKeepsPriorObject(@TempDir Path dir) throws Exception {
    var manager = new ConfigManager(dir.resolve("config.json"));
    manager.save(c);
    var prior = manager.read();
    Files.writeString(dir.resolve("config.json"), "{invalid");
    assertThrows(java.io.IOException.class, manager::read);
    assertTrue(prior.commands.isEmpty());
  }

  @Test
  void reloadNewEngineChangesRules() {
    var old = new CommandRuleEngine(c);
    rule("kit", "block");
    var updated = new CommandRuleEngine(c);
    assertTrue(old.evaluate("kit", user).execute());
    assertFalse(updated.evaluate("kit", user).execute());
  }
}
