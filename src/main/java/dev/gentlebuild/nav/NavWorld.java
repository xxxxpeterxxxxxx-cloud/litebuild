package dev.gentlebuild.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Minimal world seam for the navigator/planner so tests do not need a game. */
public interface NavWorld {
	BlockState getBlockState(BlockPos pos);
	boolean isLoaded(BlockPos pos);
}
