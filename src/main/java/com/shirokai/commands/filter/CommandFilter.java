package com.shirokai.commands.filter;

import com.mojang.brigadier.tree.*;
import java.util.*;
import java.util.function.Predicate;

public final class CommandFilter {
  private CommandFilter() {}

  public static <S> RootCommandNode<S> filter(RootCommandNode<S> root, Predicate<String> visible) {
    return filter(root, visible, path -> true);
  }

  public static <S> RootCommandNode<S> filter(
      RootCommandNode<S> root, Predicate<String> visible, Predicate<String> canExecute) {
    Map<CommandNode<S>, CommandNode<S>> copies = new IdentityHashMap<>();
    Map<CommandNode<S>, Boolean> executable = new IdentityHashMap<>();
    Set<CommandNode<S>> retained = Collections.newSetFromMap(new IdentityHashMap<>());
    collect(root, "", visible, canExecute, retained, executable);
    var copy = new RootCommandNode<S>();
    copies.put(root, copy);
    for (var child : root.getChildren()) {
      if (retained.contains(child)) {
        copy.addChild(clone(child, retained, executable, copies, new HashSet<>()));
      }
    }
    return copy;
  }

  private static <S> void collect(
      CommandNode<S> node,
      String path,
      Predicate<String> visible,
      Predicate<String> canExecute,
      Set<CommandNode<S>> retained,
      Map<CommandNode<S>, Boolean> executable) {
    retained.add(node);
    executable.merge(node, canExecute.test(path), (a, b) -> a && b);
    for (var child : node.getChildren()) {
      String next = path.isEmpty() ? child.getName() : path + " " + child.getName();
      if (visible.test(next)) collect(child, next, visible, canExecute, retained, executable);
    }
  }

  private static <S> CommandNode<S> clone(
      CommandNode<S> node,
      Set<CommandNode<S>> retained,
      Map<CommandNode<S>, Boolean> executable,
      Map<CommandNode<S>, CommandNode<S>> copies,
      Set<CommandNode<S>> visiting) {
    if (copies.containsKey(node)) return copies.get(node);
    var builder = node.createBuilder();
    if (!executable.getOrDefault(node, false)) builder.executes(null);
    // A redirect must not put a hidden node back into the client packet.
    if (node.getRedirect() != null) {
      var target = node.getRedirect();
      if (!retained.contains(target) || !visiting.add(node)) builder.forward(null, null, false);
      else {
        builder.forward(
            clone(target, retained, executable, copies, visiting),
            node.getRedirectModifier(),
            node.isFork());
        visiting.remove(node);
      }
    }
    var result = builder.build();
    copies.put(node, result);
    for (var child : node.getChildren()) {
      if (retained.contains(child))
        result.addChild(clone(child, retained, executable, copies, visiting));
    }
    return result;
  }
}
