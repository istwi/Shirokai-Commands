package com.shirokaicommands.test;

import static net.minecraft.server.command.CommandManager.*;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shirokai.commands.ShirokaiCommands;
import com.shirokai.commands.security.CommandExecutionGuard;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.*;

/** Runs inside an actual Fabric dedicated server, never in the distributed mod jar. */
public class DedicatedServerTests implements ModInitializer {
  private int count, executions, stage = -1, ticks;
  private RecordingHandler handler;
  private ServerPlayerEntity player;
  private MinecraftServer server;
  private boolean failed;
  private String echoed;
  private final Path result = Path.of("test-result.txt");

  public void onInitialize() {
    CommandRegistrationCallback.EVENT.register(
        (d, a, e) -> {
          d.register(
              literal("sctvisible")
                  .executes(
                      c -> {
                        executions++;
                        return 1;
                      }));
          d.register(
              literal("scthidden")
                  .executes(
                      c -> {
                        executions++;
                        return 1;
                      }));
          var blocked =
              d.register(
                  literal("sctblocked")
                      .executes(
                          c -> {
                            executions++;
                            return 1;
                          }));
          d.register(literal("sctalternate").redirect(blocked).executes(blocked.getCommand()));
          d.register(
              literal("minecraft:sctblocked")
                  .executes(
                      c -> {
                        executions++;
                        return 1;
                      }));
          d.register(
              literal("sctbalance")
                  .executes(
                      c -> {
                        check(c.getSource().getPlayer() == player, "Alias identity preserved");
                        executions++;
                        return 1;
                      })
                  .then(
                      argument("amount", StringArgumentType.word())
                          .suggests((c, b) -> b.suggest("example").buildFuture())
                          .executes(
                              c -> {
                                executions++;
                                return 1;
                              })));
          d.register(
              literal("sctdenied")
                  .requires(s -> s.hasPermissionLevel(4))
                  .executes(
                      c -> {
                        executions++;
                        return 1;
                      }));
          d.register(
              literal("sctecho")
                  .then(
                      argument("text", StringArgumentType.greedyString())
                          .executes(
                              c -> {
                                echoed = StringArgumentType.getString(c, "text");
                                return 1;
                              })));
          d.register(
              literal("sctcontainer").executes(c -> 20).then(literal("allowed").executes(c -> 21)));
          d.register(
              literal("/sctwand")
                  .executes(
                      c -> {
                        throw new AssertionError("Blocked slash literal executed");
                      }));
          d.register(literal("/scthidden").executes(c -> 31));
          d.register(literal("/sctoriginal").executes(c -> 32));
          var pay =
              d.register(
                  literal("sctpay")
                      .then(
                          argument(
                                  "amount",
                                  com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                              .then(
                                  argument("target", StringArgumentType.word())
                                      .suggests((ctx, b) -> playerNames(b))
                                      .then(
                                          argument("currency", StringArgumentType.greedyString())
                                              .suggests(
                                                  (ctx, b) ->
                                                      b.suggest("cash")
                                                          .suggest("impactor:cash")
                                                          .suggest("tokens")
                                                          .suggest("impactor:tokens")
                                                          .suggest("impactor:dollars")
                                                          .buildFuture())
                                              .executes(
                                                  ctx ->
                                                      StringArgumentType.getString(ctx, "currency")
                                                              .equals("impactor:cash")
                                                          ? 43
                                                          : 44)))));
          d.register(
              literal("sctafterpay")
                  .then(
                      argument(
                              "amount",
                              com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                          .then(
                              argument("target", StringArgumentType.word())
                                  .suggests((ctx, b) -> playerNames(b))
                                  .then(
                                      argument("currency", StringArgumentType.word())
                                          .suggests(
                                              (ctx, b) ->
                                                  b.suggest("cash")
                                                      .suggest("tokens")
                                                      .suggest("impactor:cash")
                                                      .buildFuture())
                                          .executes(ctx -> 46)
                                          .then(
                                              argument("extra", StringArgumentType.word())
                                                  .suggests((ctx, b) -> playerNames(b))
                                                  .executes(ctx -> 47))))));
          d.register(literal("sctaliaspay").redirect(pay));
          d.register(literal("economy:sctpay").redirect(pay));
          d.register(literal("scteconomy").then(literal("pay").redirect(pay)));
          d.register(
              literal("sctplayers")
                  .then(
                      argument("target", StringArgumentType.word())
                          .suggests((ctx, b) -> playerNames(b))));
          d.register(
              literal("sctmsg")
                  .then(
                      argument("target", StringArgumentType.word())
                          .suggests((ctx, b) -> playerNames(b))
                          .then(
                              argument("message", StringArgumentType.greedyString())
                                  .executes(ctx -> 45))));
          d.register(literal("sctfirst").executes(c -> 11));
          d.register(literal("sctsecond").executes(c -> 12));
          d.register(
              literal("sctkit")
                  .then(
                      literal("claim")
                          .executes(
                              c -> {
                                executions++;
                                return 1;
                              }))
                  .then(
                      literal("admin")
                          .executes(
                              c -> {
                                executions++;
                                return 1;
                              })));
        });
    ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
    ServerTickEvents.END_SERVER_TICK.register(
        s -> {
          if (failed) return;
          if (stage == -1) run(this::initial);
          else if (stage > 0) run(this::advance);
        });
  }

  private void run(Throwing action) {
    try {
      action.run();
    } catch (Throwable error) {
      failed = true;
      error.printStackTrace();
      try {
        Files.writeString(result, "FAIL after " + count + " assertions: " + error);
      } catch (Exception ignored) {
      }
      server.stop(false);
    }
  }

  private void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    count++;
    System.out.println("SC_TEST_PASS " + name);
  }

  private void initial() throws Exception {
    Files.deleteIfExists(result);
    player =
        new ServerPlayerEntity(
            server,
            server.getOverworld(),
            new GameProfile(UUID.fromString("f9db780f-0eef-4ccd-9522-5c063ddc1751"), "PolicyTest"),
            SyncedClientOptions.createDefault());
    handler = new RecordingHandler(server, player);
    player.networkHandler = handler;
    var mod = ShirokaiCommands.get();
    var source = player.getCommandSource();
    check(!mod.isAdmin(source, "shirokaicommands.admin"), "Player admin denied");
    check(
        mod.isAdmin(server.getCommandSource(), "shirokaicommands.admin"),
        "Console admin permitted");
    var dispatcher = server.getCommandManager().getDispatcher();
    var parse = dispatcher.parse("sc reload", source);
    check(parse.getReader().canRead(), "Brigadier admin permission denied");
    check(
        mod.decision(source, "scthidden").execute() && !mod.decision(source, "scthidden").visible(),
        "HIDE policy");
    invoke("scthidden");
    check(executions == 1, "Hidden command manually executes");
    invoke("sctblocked");
    check(executions == 1, "Network BLOCK prevents original execution");
    invoke("minecraft:sctblocked");
    check(executions == 1, "Namespaced BLOCK prevents execution");
    invoke("sctalternate");
    check(executions == 1, "Redirect BLOCK prevents execution");
    check(
        !CommandExecutionGuard.allow(source.withLevel(2), "execute run sctblocked"),
        "Execute run cannot bypass");
    invoke("sctbalance");
    check(executions == 1, "REPLACE original blocked");
    invoke("sctmoney");
    check(executions == 2, "REPLACE alias executes");
    invoke("sctmoney argument");
    check(executions == 3, "Alias forwards arguments");
    invoke("sctrestricted");
    check(executions == 3, "Alias preserves original vanilla permission");
    var suggestions =
        dispatcher.getCompletionSuggestions(dispatcher.parse("sctmoney ex", source)).join();
    var trailing =
        dispatcher.getCompletionSuggestions(dispatcher.parse("sctmoney ", source)).join();
    check(
        trailing.getList().stream().anyMatch(x -> x.getText().equals("example")),
        "Alias suggests after trailing space");
    check(
        suggestions.getList().stream().anyMatch(x -> x.getText().equals("example")),
        "Alias forwards suggestion provider");
    check(mod.executeAlias(source, "sctgroup first") == 11, "Shared-root first alias executes");
    check(mod.executeAlias(source, "sctgroup second") == 12, "Shared-root second alias executes");
    mod.executeAlias(source, "sctfixed two  spaces");
    check(
        "fixed two  spaces".equals(echoed), "Fixed dynamic prefix and original spacing preserved");
    check(
        !mod.decision(source, "sctkit admin").execute()
            && mod.decision(source, "sctkit claim").execute(),
        "Subcommands independently evaluated");
    server.getCommandManager().sendCommandTree(player);
    check(handler.tree != null, "Real command-tree packet constructed");
    var root =
        handler.tree.getCommandTree(
            net.minecraft.command.CommandRegistryAccess.of(
                server.getRegistryManager(), server.getSaveProperties().getEnabledFeatures()));
    check(
        root.getChild("sctcontainer") != null,
        "Blocked parent retained as container for allowed child");
    check(
        root.getChild("sctcontainer").getCommand() == null,
        "Blocked container not marked executable");
    check(
        root.getChild("sctcontainer").getChild("allowed") != null,
        "Explicit child override visible");
    check(
        root.getChild("/sctwand") == null,
        "WorldEdit-style slash literal absent from command tree");
    check(root.getChild("/scthidden") == null, "HIDE slash literal absent from tree");
    check(
        !CommandExecutionGuard.allow(source, "/sctwand"), "WorldEdit-style native command blocked");
    invoke("/sctwand");
    check(
        server.getCommandManager().getDispatcher().execute("/scthidden", source) == 31,
        "HIDE preserves native slash execution");
    check(
        mod.executeAlias(source, "sctslashalias") == 32,
        "Replacement preserves original slash literal");
    var noPrefix =
        new com.mojang.brigadier.suggestion.SuggestionsBuilder("", 0).suggest("/sctwand").build();
    check(
        mod.filterSuggestions(source, "", noPrefix).getList().isEmpty(),
        "Native slash suggestion without chat prefix filtered");
    var chatReader = new com.mojang.brigadier.StringReader("/sctmoney ex");
    chatReader.skip();
    var chatSuggestions =
        dispatcher.getCompletionSuggestions(dispatcher.parse(chatReader, source)).join();
    check(
        chatSuggestions.getList().stream()
            .anyMatch(x -> x.apply("/sctmoney ex").equals("/sctmoney example")),
        "Alias suggestion offsets preserve chat slash");
    var supplied =
        new com.mojang.brigadier.suggestion.SuggestionsBuilder("/", 1)
            .suggest("/sctwand")
            .suggest("sctvisible")
            .build();
    var filtered = mod.filterSuggestions(source, "/", supplied);
    check(
        filtered.getList().stream().noneMatch(x -> x.getText().equals("/sctwand")),
        "Double-slash server suggestion filtered");
    check(
        filtered.getList().stream().anyMatch(x -> x.getText().equals("sctvisible")),
        "Normal slash-prefix suggestion retained");
    check(
        com.shirokai.commands.util.CommandPath.canonical(
                com.shirokai.commands.util.CommandPath.fromUser("//sctwand"))
            .equals("/sctwand"),
        "Admin notation preserves one native slash");
    check(root.getChild("scthidden") == null, "Hidden node absent from packet");
    check(root.getChild("sctblocked") == null, "Blocked node absent from packet");
    check(root.getChild("sctbalance") == null, "Replaced node absent from packet");
    check(root.getChild("sctmoney") != null, "Alias present in packet");
    check(root.getChild("sctrestricted") == null, "Original permission denial hides alias");
    check(
        root.getChild("sctgroup").getChild("first") != null
            && root.getChild("sctgroup").getChild("second") != null,
        "Shared-root aliases present in packet");
    check(
        root.getChild("sctkit").getChild("admin") == null, "Blocked subcommand absent from packet");
    check(
        mod.decision(server.getCommandSource(), "sctblocked").execute(),
        "Console remains unrestricted");
    String payInput = "/sctpay 10 a ";
    handler.onRequestCommandCompletions(
        new net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket(71, payInput));
    check(
        handler.completion != null,
        "Argument suggestions packet generated through real network handler");
    var currencies =
        handler.completion.getSuggestions().getList().stream()
            .map(com.mojang.brigadier.suggestion.Suggestion::getText)
            .collect(java.util.stream.Collectors.toSet());
    check(
        currencies.equals(Set.of("cash", "tokens")),
        "Only plain currencies survive configured argument filter");
    check(
        handler.completion.start() == payInput.length(),
        "Currency suggestion replacement range preserved");
    check(
        dispatcher.execute("sctpay 10 a impactor:cash", source) == 43,
        "Hidden namespaced currency still manually executable");
    check(
        dispatcher.execute("sctpay 10 a cash", source) == 44,
        "Plain currency still manually executable");
    var actors =
        List.of(player, onlinePlayer("Alice"), onlinePlayer("Bob"), onlinePlayer("Hidden"));
    var connected = server.getPlayerManager().getPlayerList();
    connected.addAll(actors);
    try {
      String playerInput = "/sctpay 10 ";
      var reader = new com.mojang.brigadier.StringReader(playerInput);
      reader.skip();
      var original = dispatcher.getCompletionSuggestions(dispatcher.parse(reader, source)).join();
      var offered =
          original.getList().stream()
              .map(com.mojang.brigadier.suggestion.Suggestion::getText)
              .toList();
      var expected =
          offered.stream()
              .filter(
                  name ->
                      Set.of("alice", "bob", "policytest").contains(name.toLowerCase(Locale.ROOT)))
              .toList();
      var actual = requestSuggestions(playerInput);
      var names =
          actual.getList().stream()
              .map(com.mojang.brigadier.suggestion.Suggestion::getText)
              .toList();
      check(
          names.equals(expected),
          "Online-only response preserves original provider order and casing");
      check(names.contains("PolicyTest"), "Requester is retained when originally suggested");
      check(
          !names.contains("Hidden"), "Connected player omitted by original provider is not added");
      check(
          names.stream()
              .noneMatch(
                  name -> name.startsWith("@") || name.equals("Server") || name.equals("Offline")),
          "Selectors, Server and offline players removed");
      check(
          actual.getRange().equals(original.getRange()),
          "Online-only response keeps Brigadier replacement range");
      check(
          actual.getList().stream()
              .filter(s -> s.getText().equals("ALICE"))
              .findFirst()
              .orElseThrow()
              .getTooltip()
              .getString()
              .equals("Original tooltip"),
          "Online-only response preserves suggestion tooltip");
      check(
          requestSuggestions("/sctplayers ").getList().stream()
              .anyMatch(s -> s.getText().equals("Server")),
          "Unconfigured command suggestions remain unchanged");
      check(
          requestSuggestions("/economy:sctpay 10 ").getList().stream()
              .map(s -> s.getText())
              .toList()
              .equals(expected),
          "Namespaced command online filter works");
      check(
          requestSuggestions("/scteconomy pay 10 ").getList().stream()
              .map(s -> s.getText())
              .toList()
              .equals(expected),
          "Subcommand path online filter works");
      check(
          requestSuggestions("/sctmsg ").getList().stream()
              .map(s -> s.getText())
              .toList()
              .equals(expected),
          "First argument online filter works");
      check(
          requestSuggestions("/sctsend 10 ").getList().stream()
              .map(s -> s.getText())
              .toList()
              .equals(expected),
          "Alias argument offsets expand to original online filter");
      connected.remove(actors.get(2));
      check(
          requestSuggestions(playerInput).getList().stream()
              .noneMatch(s -> s.getText().equals("bob")),
          "Disconnected player removed at next response");
      check(
          requestSuggestions("/sctpay 10 Offline ").getList().stream()
              .map(s -> s.getText())
              .collect(java.util.stream.Collectors.toSet())
              .equals(Set.of("cash", "tokens")),
          "Currency filter works after manually typed offline recipient");
      check(
          dispatcher.execute("sctpay 10 Offline cash", source) == 44,
          "Online filter does not change payment command execution");
      check(
          dispatcher.execute("sctmsg Offline hello", source) == 45,
          "Online filter does not change message command execution");
      check(
          requestSuggestions("/sctafterpay 10 PolicyTest cash ").getList().isEmpty(),
          "No suggestions after currency with hideAll on fourth argument");
      check(
          requestSuggestions("/sctafterpay 10 ").getList().stream()
              .anyMatch(s -> s.getText().equals("ALICE")),
          "hideAll keeps player suggestions before currency");
      check(
          requestSuggestions("/sctafterpay 10 PolicyTest ").getList().stream()
              .map(s -> s.getText())
              .collect(java.util.stream.Collectors.toSet())
              .equals(Set.of("cash", "tokens")),
          "hideAll keeps currency suggestions in third argument");
      check(
          dispatcher.execute("sctafterpay 10 PolicyTest cash Server", source) == 47,
          "hideAll does not block manual extra argument execution");
      var empty =
          new com.mojang.brigadier.suggestion.Suggestions(
              com.mojang.brigadier.context.StringRange.at(playerInput.length()), List.of());
      check(
          mod.filterSuggestions(source, playerInput, empty) == empty,
          "Empty suggestions preserved without adding online names");
    } finally {
      connected.removeAll(actors);
    }
    int beforeInternal = executions;
    server.getCommandManager().executeWithPrefix(source, "sctblocked");
    check(executions == beforeInternal + 1, "Internal player command untouched by default");
    mod.mutate(
        server.getCommandSource(),
        "integration whitelist",
        c -> {
          c.settings.whitelistEnabled = true;
          c.settings.guardInternalPlayerCommands = true;
        });
    stage = 1;
  }

  private void advance() throws Exception {
    if (++ticks > 400)
      throw new AssertionError("Async config operation timed out at stage " + stage);
    var mod = ShirokaiCommands.get();
    var source = player.getCommandSource();
    if (stage == 1 && mod.config().settings.whitelistEnabled) {
      int beforeInternal = executions;
      server.getCommandManager().executeWithPrefix(source, "sctblocked");
      check(executions == beforeInternal, "Configured internal guard blocks player command");
      server.getCommandManager().executeWithPrefix(server.getCommandSource(), "sctblocked");
      check(executions == beforeInternal + 1, "Internal guard never blocks console");
      check(!mod.decision(source, "sctvisible").execute(), "Whitelist blocks unspecified command");
      check(mod.decision(source, "scthidden").execute(), "Whitelist preserves HIDE execution");
      check(
          Files.exists(Path.of("config/shirokai-commands.json.bak")),
          "Administrative backup created");
      var file = Path.of("config/shirokai-commands.json");
      String json = Files.readString(file);
      Files.writeString(
          file, json.replace("\"whitelistEnabled\": true", "\"whitelistEnabled\": false"));
      mod.reload(server.getCommandSource());
      stage = 2;
      ticks = 0;
    } else if (stage == 2 && !mod.config().settings.whitelistEnabled) {
      check(mod.decision(source, "sctvisible").execute(), "Reload applies new config");
      Files.writeString(Path.of("config/shirokai-commands.json"), "{invalid");
      mod.reload(server.getCommandSource());
      stage = 3;
      ticks = 0;
    } else if (stage == 3 && ticks > 20) {
      check(
          mod.decision(source, "sctvisible").execute(), "Invalid JSON preserves last valid config");
      Files.copy(
          Path.of("config/shirokai-commands.json.bak"),
          Path.of("config/shirokai-commands.json"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(result, "PASS " + count + " assertions\n");
      stage = 0;
      server.stop(false);
    }
  }

  private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
      playerNames(com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
    return b.suggest("@a")
        .suggest("@p")
        .suggest("@r")
        .suggest("@s")
        .suggest("@e")
        .suggest("Server")
        .suggest("Offline")
        .suggest("ALICE", net.minecraft.text.Text.literal("Original tooltip"))
        .suggest("bob")
        .suggest("PolicyTest")
        .buildFuture();
  }

  private ServerPlayerEntity onlinePlayer(String name) {
    var p =
        new ServerPlayerEntity(
            server,
            server.getOverworld(),
            new GameProfile(UUID.randomUUID(), name),
            SyncedClientOptions.createDefault());
    p.networkHandler = new RecordingHandler(server, p);
    return p;
  }

  private com.mojang.brigadier.suggestion.Suggestions requestSuggestions(String input) {
    handler.completion = null;
    handler.onRequestCommandCompletions(
        new net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket(72, input));
    if (handler.completion == null)
      throw new AssertionError("No suggestions response for " + input);
    return handler.completion.getSuggestions();
  }

  private void invoke(String command) throws Exception {
    var method = ServerPlayNetworkHandler.class.getDeclaredMethod("executeCommand", String.class);
    method.setAccessible(true);
    method.invoke(handler, command);
  }

  private interface Throwing {
    void run() throws Exception;
  }

  private static class RecordingHandler extends ServerPlayNetworkHandler {
    CommandTreeS2CPacket tree;
    CommandSuggestionsS2CPacket completion;

    RecordingHandler(MinecraftServer s, ServerPlayerEntity p) {
      super(
          s,
          new ClientConnection(NetworkSide.SERVERBOUND),
          p,
          ConnectedClientData.createDefault(p.getGameProfile(), false));
    }

    @Override
    public void sendPacket(Packet<?> packet) {
      if (packet instanceof CommandTreeS2CPacket t) tree = t;
      if (packet instanceof CommandSuggestionsS2CPacket p) completion = p;
    }
  }
}
