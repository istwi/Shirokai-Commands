package com.shirokai.commands.alias;

import static net.minecraft.server.command.CommandManager.*;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.*;
import com.mojang.brigadier.tree.*;
import com.shirokai.commands.ShirokaiCommands;
import com.shirokai.commands.config.CommandConfig;
import com.shirokai.commands.mixin.CommandNodeAccessor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.command.ServerCommandSource;

/** Additive command templates. Original nodes are retained; targets execute on a pristine graph. */
public final class ArgumentAliasManager {
  private record Owned(
      CommandNode<ServerCommandSource> original, CommandNode<ServerCommandSource> wrapper) {}

  private final Map<String, Owned> owned = new LinkedHashMap<>();
  private List<ArgumentTemplate> templates = List.of();
  private CommandDispatcher<ServerCommandSource> originals;

  private CommandNode<ServerCommandSource> baseline(
      CommandDispatcher<ServerCommandSource> d, String name) {
    var current = d.getRoot().getChild(name);
    var prior = owned.get(name);
    return prior != null && current == prior.wrapper ? prior.original : current;
  }

  public void validate(
      CommandConfig config, CommandDispatcher<ServerCommandSource> d, AliasManager legacy) {
    var next = ArgumentTemplate.compile(config.argumentAliases);
    if (next.isEmpty()) return;
    for (var t : next) {
      var root = legacy.installed(t.root()) ? null : baseline(d, t.root());
      if (root != null) {
        if (!(root instanceof LiteralCommandNode)
            || !t.spec().extendExisting
            || root.getRedirect() != null)
          throw new IllegalArgumentException(
              "argumentAliases."
                  + t.id()
                  + ": existing root requires extendExisting and an unambiguous new route: "
                  + t.root());
        if (t.prefix().size() == 1) {
          if (t.names().isEmpty()
              || root.getChildren().stream().anyMatch(n -> n instanceof ArgumentCommandNode)
              || root.getChild(t.argumentLabel()) != null)
            throw new IllegalArgumentException(
                "argumentAliases."
                    + t.id()
                    + ": existing root already has a conflicting argument or label");
          if (t.targetRoot().equals(t.root())) {
            var fixed = ArgumentTemplate.parse(t.fixedTargetPrefix());
            if (fixed.size() < 2
                || !(root.getChild(fixed.get(1).value()) instanceof LiteralCommandNode))
              throw new IllegalArgumentException(
                  "argumentAliases."
                      + t.id()
                      + ": default route must target an original literal subcommand");
          }
        } else if (root.getChild(t.prefix().get(1)) != null)
          throw new IllegalArgumentException(
              "argumentAliases."
                  + t.id()
                  + ": existing command path conflict: "
                  + String.join(" ", t.prefix()));
      }
      var target = baseline(d, t.targetRoot());
      if (target == null || legacy.installed(t.targetRoot()))
        throw new IllegalArgumentException(
            "argumentAliases." + t.id() + ": original target does not exist: " + t.targetRoot());
    }
    // Detect unsupported redirect cycles before any configuration is written or nodes replaced.
    snapshot(d, true);
  }

  @SuppressWarnings("unchecked")
  private static void remove(RootCommandNode<ServerCommandSource> root, String name) {
    var access = (CommandNodeAccessor<ServerCommandSource>) (Object) root;
    access.shirokai$children().remove(name);
    access.shirokai$literals().remove(name);
    access.shirokai$arguments().remove(name);
  }

  public void restore(CommandDispatcher<ServerCommandSource> d) {
    for (var e : owned.entrySet())
      if (d.getRoot().getChild(e.getKey()) == e.getValue().wrapper) {
        remove(d.getRoot(), e.getKey());
        if (e.getValue().original != null) d.getRoot().addChild(e.getValue().original);
      }
    owned.clear();
  }

  public void install(CommandConfig config, CommandDispatcher<ServerCommandSource> d) {
    templates = ArgumentTemplate.compile(config.argumentAliases);
    if (templates.isEmpty()) {
      originals = null;
      return;
    }
    originals = snapshot(d, false);
    var groups = new LinkedHashMap<String, List<ArgumentTemplate>>();
    for (var t : templates) groups.computeIfAbsent(t.root(), k -> new ArrayList<>()).add(t);
    for (var e : groups.entrySet()) {
      var original = d.getRoot().getChild(e.getKey());
      LiteralArgumentBuilder<ServerCommandSource> builder =
          original == null
              ? literal(e.getKey())
                  .requires(s -> ShirokaiCommands.get().argumentAliasPrefixAllowed(s, e.getKey()))
              : (LiteralArgumentBuilder<ServerCommandSource>) original.createBuilder();
      if (original == null)
        for (var t : e.getValue())
          if (t.prefix().size() == 1 && t.names().isEmpty()) builder.executes(c -> execute(t, c));
      var wrapper = builder.build();
      if (original != null) for (var child : original.getChildren()) wrapper.addChild(child);
      for (var t : e.getValue()) add(wrapper, t);
      remove(d.getRoot(), e.getKey());
      d.getRoot().addChild(wrapper);
      owned.put(e.getKey(), new Owned(original, wrapper));
    }
  }

  private void add(CommandNode<ServerCommandSource> root, ArgumentTemplate t) {
    ArgumentBuilder<ServerCommandSource, ?> branch = null;
    if (!t.names().isEmpty())
      branch =
          argument(t.argumentLabel(), StringArgumentType.greedyString())
              .requires(source -> ShirokaiCommands.get().argumentAliasAllowed(source, t))
              .suggests((ctx, b) -> suggest(t, ctx, b))
              .executes(c -> execute(t, c));
    for (int i = t.prefix().size() - 1; i >= 1; i--) {
      String path = String.join(" ", t.prefix().subList(0, i + 1));
      var node =
          literal(t.prefix().get(i))
              .requires(s -> ShirokaiCommands.get().argumentAliasPrefixAllowed(s, path));
      if (branch == null) node.executes(c -> execute(t, c));
      else node.then(branch);
      branch = node;
    }
    if (branch != null) root.addChild(branch.build());
  }

  private int execute(ArgumentTemplate t, CommandContext<ServerCommandSource> context)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    int start = context.getNodes().getFirst().getRange().getStart();
    return ShirokaiCommands.get()
        .executeArgumentAlias(t.id(), context.getSource(), context.getInput().substring(start));
  }

  public ArgumentTemplate.Match match(String input) {
    for (var t : templates) {
      var owner = owned.get(t.root());
      if (t.prefix().size() == 1 && owner != null && owner.original != null) {
        var tokens = ArgumentTemplate.parse(input);
        // Original root execution and literal subcommands always win over a default alias.
        if (tokens != null
            && (tokens.size() <= 1
                || owner.original.getChild(tokens.get(1).value()) instanceof LiteralCommandNode))
          continue;
      }
      var match = t.match(input);
      if (match != null) return match;
    }
    return null;
  }

  public List<ArgumentTemplate> templates() {
    return templates;
  }

  public boolean originalAllows(ArgumentTemplate t, ServerCommandSource source) {
    if (originals == null) return false;
    var parsed = originals.parse(t.fixedTargetPrefix(), source);
    return !parsed.getReader().canRead()
        && parsed.getExceptions().isEmpty()
        && !parsed.getContext().getNodes().isEmpty();
  }

  public int executeOriginal(String command, ServerCommandSource source)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    return originals.execute(command, source);
  }

  private CompletableFuture<Suggestions> suggest(
      ArgumentTemplate t, CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
    var partial = ArgumentTemplate.partial(builder.getRemaining());
    if (partial.completed().size() >= t.names().size()) return Suggestions.empty();
    String selected = t.names().get(partial.completed().size());
    var values = new LinkedHashMap<String, String>();
    for (int i = 0; i < partial.completed().size(); i++)
      values.put(t.names().get(i), partial.completed().get(i).raw());
    int start = builder.getStart() + partial.start();
    var staticBuilder = builder.createOffset(start);
    String remaining = partial.remaining().toLowerCase(Locale.ROOT);
    for (String value : t.spec().suggestions.getOrDefault(selected, List.of()))
      if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
        String rendered =
            value.chars().anyMatch(Character::isWhitespace) || value.startsWith("\"")
                ? StringArgumentType.escapeIfRequired(value)
                : value;
        staticBuilder.suggest(rendered);
      }
    var fixed = staticBuilder.build();
    var request = t.completion(values, selected, partial.remaining());
    if (request == null) return CompletableFuture.completedFuture(fixed);
    return originals
        .getCompletionSuggestions(originals.parse(request.command(), ctx.getSource()))
        .thenApply(
            offered -> {
              var mapped = builder.createOffset(start);
              String prefix = request.command().substring(0, request.argumentStart());
              for (var suggestion : offered.getList()) {
                String candidate = suggestion.apply(request.command());
                if (candidate.startsWith(prefix))
                  mapped.suggest(candidate.substring(prefix.length()), suggestion.getTooltip());
              }
              return Suggestions.merge(builder.getInput(), List.of(fixed, mapped.build()));
            });
  }

  private CommandDispatcher<ServerCommandSource> snapshot(
      CommandDispatcher<ServerCommandSource> d, boolean unwrap) {
    var root = new RootCommandNode<ServerCommandSource>();
    Map<CommandNode<ServerCommandSource>, CommandNode<ServerCommandSource>> copies =
        new IdentityHashMap<>();
    copies.put(d.getRoot(), root);
    for (var child : d.getRoot().getChildren()) {
      var base = unwrap ? baseline(d, child.getName()) : child;
      if (base != null)
        root.addChild(copy(base, copies, Collections.newSetFromMap(new IdentityHashMap<>())));
    }
    return new CommandDispatcher<>(root);
  }

  private static CommandNode<ServerCommandSource> copy(
      CommandNode<ServerCommandSource> node,
      Map<CommandNode<ServerCommandSource>, CommandNode<ServerCommandSource>> copies,
      Set<CommandNode<ServerCommandSource>> visiting) {
    if (copies.containsKey(node)) return copies.get(node);
    if (node instanceof RootCommandNode) {
      var copy = new RootCommandNode<ServerCommandSource>();
      copies.put(node, copy);
      for (var child : node.getChildren()) copy.addChild(copy(child, copies, visiting));
      return copy;
    }
    if (!visiting.add(node))
      throw new IllegalArgumentException(
          "Unsupported circular command redirects while preparing argument aliases");
    var builder = node.createBuilder();
    if (node.getRedirect() != null)
      builder.forward(
          copy(node.getRedirect(), copies, visiting), node.getRedirectModifier(), node.isFork());
    var result = builder.build();
    copies.put(node, result);
    visiting.remove(node);
    for (var child : node.getChildren()) result.addChild(copy(child, copies, visiting));
    return result;
  }
}
