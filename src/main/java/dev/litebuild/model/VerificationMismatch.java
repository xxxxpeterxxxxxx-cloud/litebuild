package dev.litebuild.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One typed cell difference found by the final audit. {@code actual} may be
 * null only for {@link Kind#UNLOADED} — an unloaded cell is never read as air.
 */
public record VerificationMismatch(BlockPos pos, BlockState expected,
								   BlockState actual, Kind kind) {
	public enum Kind { MISSING, WRONG_BLOCK, WRONG_STATE, EXTRA_BLOCK,
		TEMP_SUPPORT, UNLOADED }
}
