package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.shirokai.commands.config.*;
import com.shirokai.commands.filter.TabSuggestionFilter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HideAllSuggestionsTest {
  private CommandConfig.TabFilter rule() {
    var r = new CommandConfig.TabFilter();
    r.command = "pay";
    r.argumentIndex = 4;
    r.hideAll = true;
    return r;
  }

  private TabSuggestionFilter filter() {
    return new TabSuggestionFilter(List.of(rule()));
  }

  @Test
  void hidesAllFourthArgumentValues() {
    for (String value : List.of("Alice", "Server", "@r", "anything")) {
      String c = "pay 10 Alice cash " + value;
      assertFalse(filter().visible(c, c.lastIndexOf(value), c.length(), Set.of("alice")));
    }
  }

  @Test
  void keepsEarlierArguments() {
    String c = "pay 10 Alice cash";
    assertTrue(filter().visible(c, c.indexOf("Alice"), c.indexOf("Alice") + 5, Set.of("alice")));
    assertTrue(filter().visible(c, c.indexOf("cash"), c.length(), Set.of()));
  }

  @Test
  void correctRangeWhenEditingMiddle() {
    String c = "pay 10 Alice cash Bob";
    assertTrue(filter().visible(c, c.indexOf("cash"), c.indexOf("cash") + 4, Set.of()));
  }

  @Test
  void otherCommandsUnchanged() {
    assertTrue(filter().visible("payroll 10 Alice cash Bob"));
  }

  @Test
  void namespaceSupported() {
    String c = "impactor:pay 10 Alice cash Alice";
    assertFalse(filter().visible(c, c.lastIndexOf("Alice"), c.length(), Set.of("alice")));
  }

  @Test
  void hideAllAloneIsValid() {
    var c = new CommandConfig();
    c.tabFilters.add(rule());
    assertDoesNotThrow(() -> ConfigManager.validate(c));
  }

  @Test
  void oldSettingsDefaultFalse() {
    assertFalse(new CommandConfig.TabFilter().hideAll);
  }

  @Test
  void flagMustBeBoolean(@TempDir Path dir) throws Exception {
    var p = dir.resolve("c.json");
    Files.writeString(
        p, "{\"tabFilters\":[{\"command\":\"pay\",\"argumentIndex\":4,\"hideAll\":\"true\"}]}");
    assertThrows(java.io.IOException.class, () -> new ConfigManager(p).read());
  }
}
