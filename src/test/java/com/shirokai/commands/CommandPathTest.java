package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.config.ConfigManager;
import com.shirokai.commands.filter.CommandRuleEngine;
import com.shirokai.commands.util.CommandPath;
import org.junit.jupiter.api.Test;

class CommandPathTest {
  @Test
  void chatNotationRemovesExactlyOneSlash() {
    assertEquals("help", CommandPath.fromUser("/help"));
    assertEquals("/wand", CommandPath.fromUser("//wand"));
    assertEquals("//wand", CommandPath.fromUser("///wand"));
  }

  @Test
  void nativeNamesKeepTheirSlashes() {
    assertEquals("/wand", CommandPath.canonical("/wand"));
    assertEquals("//wand", CommandPath.clean("//wand"));
    assertEquals("wand", CommandPath.canonical("wand"));
  }

  @Test
  void namespaceKeepsWorldEditSlash() {
    assertEquals("/wand", CommandPath.canonical("worldedit:/wand"));
  }

  @Test
  void slashRuleDoesNotBlockOrdinaryCommand() {
    var config = new CommandConfig();
    config.commands.put("/wand", new CommandConfig.Rule("block"));
    var engine = new CommandRuleEngine(config);
    var user = new CommandRuleEngine.Subject(false, false, p -> true);
    assertFalse(engine.evaluate("/wand", user).execute());
    assertFalse(engine.evaluate("worldedit:/wand", user).visible());
    assertTrue(engine.evaluate("wand", user).execute());
  }

  @Test
  void nativeSlashConfigIsValid() {
    var config = new CommandConfig();
    config.commands.put("/wand", new CommandConfig.Rule("block"));
    config.commands.put("wand", new CommandConfig.Rule("hide"));
    assertDoesNotThrow(() -> ConfigManager.validate(config));
  }

  @Test
  void argumentsRetainSlashesAndSpacing() {
    assertEquals(
        "/replace minecraft:stone \\\"a  / b\\\"",
        CommandPath.fromUser("//replace minecraft:stone \\\"a  / b\\\""));
  }
}
