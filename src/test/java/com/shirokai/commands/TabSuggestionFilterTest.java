package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.shirokai.commands.config.*;
import com.shirokai.commands.filter.TabSuggestionFilter;
import java.util.List;
import org.junit.jupiter.api.Test;

class TabSuggestionFilterTest {
  private CommandConfig.TabFilter rule() {
    var r = new CommandConfig.TabFilter();
    r.command = "pay";
    r.argumentIndex = 3;
    r.hidePrefixes = List.of("impactor:");
    return r;
  }

  private TabSuggestionFilter filter() {
    return new TabSuggestionFilter(List.of(rule()));
  }

  @Test
  void hidesNamespacedCurrencies() {
    assertFalse(filter().visible("pay 10 Alice impactor:cash"));
    assertFalse(filter().visible("pay 10 Alice impactor:tokens"));
  }

  @Test
  void keepsPlainCurrencies() {
    assertTrue(filter().visible("pay 10 Alice cash"));
    assertTrue(filter().visible("pay 10 Alice tokens"));
  }

  @Test
  void keepsOtherCommands() {
    assertTrue(filter().visible("give Alice impactor:cash"));
    assertTrue(filter().visible("payroll 10 Alice impactor:cash"));
  }

  @Test
  void onlyFiltersConfiguredArgument() {
    assertTrue(filter().visible("pay 10 impactor:Alice cash"));
  }

  @Test
  void normalizesCommandNamespace() {
    assertFalse(filter().visible("economy:pay 10 Alice impactor:cash"));
  }

  @Test
  void understandsQuotedArguments() {
    assertFalse(filter().visible("pay 10 \"Alice Smith\" impactor:cash"));
  }

  @Test
  void whitespaceDoesNotShiftArguments() {
    assertFalse(filter().visible("pay  10\tAlice  impactor:cash"));
  }

  @Test
  void incompleteInputRemainsVisible() {
    assertTrue(filter().visible("pay 10 Alice"));
  }

  @Test
  void malformedQuoteDoesNotCrash() {
    assertTrue(filter().visible("pay 10 \"Alice impactor:cash"));
  }

  @Test
  void otherNamespacesUnchanged() {
    assertTrue(filter().visible("pay 10 Alice custom:cash"));
  }

  @Test
  void noFiltersIsOptIn() {
    assertTrue(new TabSuggestionFilter(List.of()).visible("pay 10 Alice impactor:cash"));
  }

  @Test
  void supportsSubcommandPaths() {
    var r = rule();
    r.command = "economy pay";
    assertFalse(new TabSuggestionFilter(List.of(r)).visible("economy pay 10 Alice impactor:cash"));
  }

  @Test
  void rejectsZeroArgumentIndex() {
    var c = new CommandConfig();
    var r = rule();
    r.argumentIndex = 0;
    c.tabFilters.add(r);
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void rejectsEmptyPrefix() {
    var c = new CommandConfig();
    var r = rule();
    r.hidePrefixes = List.of("");
    c.tabFilters.add(r);
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }
}
