package com.shirokai.commands.mixin;

import com.mojang.brigadier.tree.*;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CommandNode.class, remap = false)
public interface CommandNodeAccessor<S> {
  @Accessor("children")
  Map<String, CommandNode<S>> shirokai$children();

  @Accessor("literals")
  Map<String, LiteralCommandNode<S>> shirokai$literals();

  @Accessor("arguments")
  Map<String, ArgumentCommandNode<S, ?>> shirokai$arguments();
}
