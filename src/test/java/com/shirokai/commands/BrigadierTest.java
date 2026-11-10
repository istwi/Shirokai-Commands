package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shirokai.commands.filter.CommandFilter;
import org.junit.jupiter.api.Test;

class BrigadierTest {
  @Test
  void treeOmitsBlockedNodesButKeepsManualExecution() throws Exception {
    var d = new CommandDispatcher<Boolean>();
    d.register(
        LiteralArgumentBuilder.<Boolean>literal("kit")
            .then(LiteralArgumentBuilder.<Boolean>literal("claim").executes(c -> 1))
            .then(LiteralArgumentBuilder.<Boolean>literal("admin").executes(c -> 2)));
    var filtered = CommandFilter.filter(d.getRoot(), path -> !path.equals("kit admin"));
    assertNull(filtered.getChild("kit").getChild("admin"));
    assertNotNull(filtered.getChild("kit").getChild("claim"));
    assertEquals(2, d.execute("kit admin", false));
  }

  @Test
  void originalPermissionRequirementIsEnforced() {
    var d = new CommandDispatcher<Boolean>();
    d.register(
        LiteralArgumentBuilder.<Boolean>literal("balance")
            .requires(Boolean::booleanValue)
            .executes(c -> 1));
    assertThrows(
        com.mojang.brigadier.exceptions.CommandSyntaxException.class,
        () -> d.execute("balance", false));
  }

  @Test
  void redirectToHiddenNodeCannotLeak() {
    var d = new CommandDispatcher<Boolean>();
    var hidden =
        d.register(
            LiteralArgumentBuilder.<Boolean>literal("hidden")
                .then(LiteralArgumentBuilder.<Boolean>literal("secret")));
    d.register(LiteralArgumentBuilder.<Boolean>literal("alternate").redirect(hidden));
    var tree = CommandFilter.filter(d.getRoot(), path -> !path.startsWith("hidden"));
    assertNull(tree.getChild("hidden"));
    assertNull(tree.getChild("alternate").getRedirect());
  }
}
