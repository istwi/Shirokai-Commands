package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.shirokai.commands.config.*;
import com.shirokai.commands.filter.TabSuggestionFilter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnlinePlayersOnlyTest {
  private CommandConfig.TabFilter rule(String command, int argument) {
    var r = new CommandConfig.TabFilter();
    r.command = command;
    r.argumentIndex = argument;
    r.onlinePlayersOnly = true;
    return r;
  }

  private TabSuggestionFilter filter() {
    return new TabSuggestionFilter(List.of(rule("pay", 2)));
  }

  private boolean visible(String command, String inserted, Set<String> online) {
    int start = command.lastIndexOf(inserted);
    assertTrue(start >= 0);
    return filter().visible(command, start, start + inserted.length(), online);
  }

  @Test
  void onlinePlayersRemain() {
    assertTrue(visible("pay 10 Alice", "Alice", Set.of("alice")));
  }

  @Test
  void offlinePlayersRemoved() {
    assertFalse(visible("pay 10 Offline", "Offline", Set.of("alice")));
  }

  @Test
  void serverAccountRemoved() {
    assertFalse(visible("pay 10 Server", "Server", Set.of("alice")));
  }

  @Test
  void selectorsRemoved() {
    for (String s : List.of("@s", "@r", "@a", "@p", "@e", "@a[name=Alice]"))
      assertFalse(visible("pay 10 " + s, s, Set.of("alice")));
  }

  @Test
  void caseInsensitive() {
    assertTrue(visible("pay 10 ALiCe", "ALiCe", Set.of("alice")));
  }

  @Test
  void selfIsNotExcluded() {
    assertTrue(visible("pay 10 Me", "Me", Set.of("me")));
  }

  @Test
  void unconfiguredCommandUnchanged() {
    assertTrue(visible("trade Offline", "Offline", Set.of()));
  }

  @Test
  void similarRootDoesNotMatch() {
    assertTrue(visible("payroll 10 Offline", "Offline", Set.of()));
  }

  @Test
  void correctArgumentOnly() {
    assertTrue(visible("pay Server Alice", "Server", Set.of()));
  }

  @Test
  void laterCurrencyNotFilteredByOfflineRecipient() {
    assertTrue(visible("pay 10 Offline cash", "cash", Set.of()));
  }

  @Test
  void subcommandPath() {
    String s = "economy pay 10 Offline";
    assertFalse(
        new TabSuggestionFilter(List.of(rule("economy pay", 2)))
            .visible(s, s.indexOf("Offline"), s.length(), Set.of()));
  }

  @Test
  void namespaceOffsetsAreNotLost() {
    String s = "impactor:pay 10 Offline";
    assertFalse(visible(s, "Offline", Set.of()));
    assertTrue(visible("impactor:pay 10 Alice cash", "cash", Set.of()));
  }

  @Test
  void quotedArguments() {
    assertTrue(visible("pay 10 \"ALICE\"", "\"ALICE\"", Set.of("alice")));
  }

  @Test
  void incompleteCommandAndQuotesAreSafe() {
    assertTrue(filter().visible("pay 10 ", 7, 7, Set.of()));
    assertTrue(filter().visible("pay 10 \"", 7, 8, Set.of()));
  }

  @Test
  void combinesOnlineAndPrefixes() {
    var r = rule("pay", 2);
    r.hidePrefixes = List.of("Al");
    String s = "pay 10 Alice";
    assertFalse(new TabSuggestionFilter(List.of(r)).visible(s, 7, s.length(), Set.of("alice")));
  }

  @Test
  void currencyAndPlayerRulesCoexistWhenEditingMiddle() {
    var currency = new CommandConfig.TabFilter();
    currency.command = "pay";
    currency.argumentIndex = 3;
    currency.hidePrefixes = List.of("impactor:");
    String s = "pay 10 Alice impactor:cash";
    assertTrue(
        new TabSuggestionFilter(List.of(rule("pay", 2), currency))
            .visible(s, 7, 12, Set.of("alice")));
  }

  @Test
  void disconnectionChangesNextSnapshot() {
    assertTrue(visible("pay 10 Alice", "Alice", Set.of("alice")));
    assertFalse(visible("pay 10 Alice", "Alice", Set.of()));
  }

  @Test
  void onlineOnlyWithoutPrefixesIsValid() {
    var c = new CommandConfig();
    c.tabFilters.add(rule("pay", 2));
    assertDoesNotThrow(() -> ConfigManager.validate(c));
  }

  @Test
  void inactiveFilterRejected() {
    var c = new CommandConfig();
    var r = rule("pay", 2);
    r.onlinePlayersOnly = false;
    c.tabFilters.add(r);
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void invalidArgumentsRejected() {
    for (int index : List.of(0, 65)) {
      var c = new CommandConfig();
      c.tabFilters.add(rule("pay", index));
      assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
    }
  }

  @Test
  void invalidCommandRejected() {
    var c = new CommandConfig();
    c.tabFilters.add(rule(" ", 1));
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void oldJsonCompatible(@TempDir Path dir) throws Exception {
    Path p = dir.resolve("config.json");
    Files.writeString(
        p,
        "{\"configVersion\":1,\"tabFilters\":[{\"command\":\"pay\",\"argumentIndex\":3,\"hidePrefixes\":[\"impactor:\"]}]}");
    var c = new ConfigManager(p).read();
    assertFalse(c.tabFilters.getFirst().onlinePlayersOnly);
    assertFalse(new TabSuggestionFilter(c.tabFilters).visible("pay 10 Alice impactor:cash"));
  }

  @Test
  void missingOldTabFiltersDefaultEmpty(@TempDir Path dir) throws Exception {
    Path p = dir.resolve("config.json");
    Files.writeString(p, "{}");
    assertTrue(new ConfigManager(p).read().tabFilters.isEmpty());
  }

  @Test
  void jsonOnlineOnlyDefaultsPrefixesEmpty(@TempDir Path dir) throws Exception {
    Path p = dir.resolve("config.json");
    Files.writeString(
        p,
        "{\"tabFilters\":[{\"command\":\"pay\",\"argumentIndex\":2,\"onlinePlayersOnly\":true}]}");
    assertTrue(new ConfigManager(p).read().tabFilters.getFirst().hidePrefixes.isEmpty());
  }

  @Test
  void booleanMustBeJsonBoolean(@TempDir Path dir) throws Exception {
    Path p = dir.resolve("config.json");
    for (String value : List.of("\"true\"", "null", "1")) {
      Files.writeString(
          p,
          "{\"tabFilters\":[{\"command\":\"pay\",\"argumentIndex\":2,\"onlinePlayersOnly\":"
              + value
              + "}]}");
      assertThrows(java.io.IOException.class, () -> new ConfigManager(p).read());
    }
  }
}
