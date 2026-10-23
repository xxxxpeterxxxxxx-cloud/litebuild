package dev.gentlebuild.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public record PlacementAction(BlockPos target,
							  BlockState expected,
							  BlockHitResult hit,
							  Item item,
							  boolean sneak,
							  ActionKind kind) {}
