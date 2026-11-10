package com.shirokai.commands;

import static org.junit.jupiter.api.Assertions.*;

import com.shirokai.commands.alias.ArgumentTemplate;
import com.shirokai.commands.config.*;
import com.shirokai.commands.filter.TabSuggestionFilter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArgumentTemplateTest {
  private CommandConfig.ArgumentAlias spec(String alias, String target) {
    var s = new CommandConfig.ArgumentAlias();
    s.alias = alias;
    s.target = target;
    return s;
  }

  private ArgumentTemplate pay() {
    return new ArgumentTemplate(
        "pay_cash", spec("pay cash {amount} {player}", "pay {amount} {player} cash"));
  }

  @Test
  void namespacesAndSelectorsRetainRawSpelling() {
    var t = new ArgumentTemplate("give", spec("items {item} {player}", "give {player} {item}"));
    assertEquals("give @s minecraft:stone", t.expand(t.match("items minecraft:stone @s")));
  }

  @Test
  void completionLocatesCurrentArgument() {
    var p = ArgumentTemplate.partial("10 Al");
    assertEquals(1, p.completed().size());
    assertEquals(3, p.start());
    assertEquals("Al", p.remaining());
    assertEquals(2, ArgumentTemplate.partial("10 Alice ").completed().size());
  }

  @Test
  void ruleAndTemplateIdsMustBeUnambiguous() {
    var c = new CommandConfig();
    c.commands.put("same", new CommandConfig.Rule("hide"));
    c.argumentAliases.put("same", spec("other {a}", "target {a}"));
    assertThrows(IllegalArgumentException.class, () -> ConfigManager.validate(c));
  }

  @Test
  void defaultLabelUsesConfiguredParameters() {
    assertEquals("amount_player", pay().argumentLabel());
  }

  @Test
  void customLabelOverridesDisplayOnly() {
    var s = spec("pay cash {amount} {player}", "pay {amount} {player} cash");
    s.argumentLabel = "quantidade_jogador";
    var t = new ArgumentTemplate("custom", s);
    assertEquals("quantidade_jogador", t.argumentLabel());
    assertEquals("pay 10 Alice cash", t.expand(t.match("pay cash 10 Alice")));
  }

  @Test
  void invalidLabelsRejected() {
    for (String label : List.of("", " ", "<argumentos>", "x\ny")) {
      var s = spec("foo {a}", "bar {a}");
      s.argumentLabel = label;
      assertThrows(IllegalArgumentException.class, () -> new ArgumentTemplate("x", s));
    }
  }

  @Test
  void reordersAndKeepsLiteralCurrency() {
    var t = pay();
    assertEquals("pay 100 Alice cash", t.expand(t.match("pay cash 100 Alice")));
  }

  @Test
  void worksForUnrelatedModCommands() {
    var t = new ArgumentTemplate("home", spec("homes {name} {world}", "home set {world} {name}"));
    assertEquals("home set overworld base", t.expand(t.match("homes base overworld")));
  }

  @Test
  void preservesQuotedValueAsSingleArgument() {
    var t = new ArgumentTemplate("msg", spec("tell {message} {player}", "msg {player} {message}"));
    assertEquals("msg Alice \"hello there\"", t.expand(t.match("tell \"hello there\" Alice")));
  }

  @Test
  void quoteAndBackslashEscapeIsPreserved() {
    var t = new ArgumentTemplate("quoted", spec("sample {text}", "say {text}"));
    String input = "sample \"hello \\\"world\\\"\"";
    assertEquals("say \"hello \\\"world\\\"\"", t.expand(t.match(input)));
  }

  @Test
  void onlyExactPrefixMatches() {
    assertNull(pay().match("pay cashbox 100 Alice"));
    assertNull(pay().match("pay tokens 100 Alice"));
  }

  @Test
  void partialMatchIsNeverExecutable() {
    var t = pay();
    var m = t.match("pay cash 100");
    assertNotNull(m);
    assertFalse(m.complete());
    assertThrows(IllegalArgumentException.class, () -> t.expand(m));
  }

  @Test
  void extraArgumentsDoNotMatch() {
    assertNull(pay().match("pay cash 100 Alice extra"));
  }

  @Test
  void brokenQuoteDoesNotMatch() {
    assertNull(pay().match("pay cash 100 \"Alice"));
  }

  @Test
  void duplicateInputRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArgumentTemplate("x", spec("foo {a} {a}", "bar {a}")));
  }

  @Test
  void unknownTargetVariableRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArgumentTemplate("x", spec("foo {a}", "bar {b}")));
  }

  @Test
  void droppedInputRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArgumentTemplate("x", spec("foo {a} {b}", "bar {a}")));
  }

  @Test
  void duplicateTargetVariableRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArgumentTemplate("x", spec("foo {a}", "bar {a} {a}")));
  }

  @Test
  void dynamicRootRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArgumentTemplate("x", spec("foo {a}", "{a} foo")));
  }

  @Test
  void lateInputLiteralRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArgumentTemplate("x", spec("foo {a} sub", "bar {a}")));
  }

  @Test
  void reservedAdminRootsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArgumentTemplate("x", spec("sc foo {a}", "bar {a}")));
    assertThrows(
        IllegalArgumentException.class, () -> new ArgumentTemplate("x", spec("foo {a}", "sc {a}")));
  }

  @Test
  void overlappingTemplatesRejected() {
    var a = spec("pay cash {a}", "other {a}");
    var b = spec("pay cash special {b}", "other {b}");
    assertThrows(
        IllegalArgumentException.class, () -> ArgumentTemplate.compile(Map.of("a", a, "b", b)));
  }

  @Test
  void chainedOrCircularTargetsRejected() {
    var a = spec("foo {x}", "bar {x}");
    var b = spec("bar {y}", "foo {y}");
    assertThrows(
        IllegalArgumentException.class, () -> ArgumentTemplate.compile(Map.of("a", a, "b", b)));
  }

  @Test
  void siblingLiteralAliasesCanCoexist() {
    assertEquals(
        2,
        ArgumentTemplate.compile(
                Map.of(
                    "cash",
                    spec("pay cash {x}", "pay {x} cash"),
                    "tokens",
                    spec("pay tokens {x}", "pay {x} tokens")))
            .size());
  }

  @Test
  void staticSuggestionsMustReferenceKnownArgument() {
    var s = spec("foo {x}", "bar {x}");
    s.suggestions.put("missing", List.of("value"));
    assertThrows(IllegalArgumentException.class, () -> new ArgumentTemplate("x", s));
  }

  @Test
  void originalCompletionNeedsEarlierTargetValues() {
    var t =
        new ArgumentTemplate(
            "x", spec("pagar {currency} {amount} {player}", "pay {amount} {player} {currency}"));
    assertNull(t.completion(Map.of(), "currency", "ca"));
    assertEquals(
        "pay 10 Al",
        t.completion(Map.of("amount", "10", "currency", "cash"), "player", "Al").command());
  }

  @Test
  void playerSuggestionRangeMovesToOriginalPlayerSlot() {
    var t = pay();
    String input = "pay cash 100 ALICE";
    var projection = t.match(input).projection();
    int[] range = projection.range(input.indexOf("ALICE"), input.length());
    var f = new CommandConfig.TabFilter();
    f.command = "pay";
    f.argumentIndex = 2;
    f.onlinePlayersOnly = true;
    assertTrue(
        new TabSuggestionFilter(List.of(f))
            .visible(projection.command(), range[0], range[1], Set.of("alice")));
    assertFalse(
        new TabSuggestionFilter(List.of(f))
            .visible(projection.command(), range[0], range[1], Set.of()));
  }

  @Test
  void fixedCurrencyRangeMovesDespiteMissingArguments() {
    var t = pay();
    var p = t.match("pay cash").projection();
    int[] range = p.range(4, 8);
    var f = new CommandConfig.TabFilter();
    f.command = "pay";
    f.argumentIndex = 3;
    f.hidePrefixes = List.of("cash");
    assertFalse(
        new TabSuggestionFilter(List.of(f)).visible(p.command(), range[0], range[1], Set.of()));
  }

  @Test
  void oldJsonDefaultsToNoArgumentAliases(@TempDir Path dir) throws Exception {
    var p = dir.resolve("config.json");
    Files.writeString(p, "{}");
    assertTrue(new ConfigManager(p).read().argumentAliases.isEmpty());
  }

  @Test
  void extensionFlagIsStrictBoolean(@TempDir Path dir) throws Exception {
    var p = dir.resolve("config.json");
    Files.writeString(
        p,
        "{\"argumentAliases\":{\"x\":{\"alias\":\"foo {a}\",\"target\":\"bar"
            + " {a}\",\"extendExisting\":\"true\"}}}");
    assertThrows(java.io.IOException.class, () -> new ConfigManager(p).read());
  }
}
