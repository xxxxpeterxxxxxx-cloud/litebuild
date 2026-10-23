package dev.litebuild.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record BuildTarget(BlockPos pos, BlockState state, Phase phase) {}
