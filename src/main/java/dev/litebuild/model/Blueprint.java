package dev.litebuild.model;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Immutable snapshot of the currently selected Litematica placement.
 * Contains AIR entries for all covered cells so collision, cleanup and
 * fluid decisions can distinguish "reserved empty" from "unknown".
 */
public record Blueprint(String name,
						Map<BlockPos, BlockState> blocks,
						Set<BlockPos> blockEntityData,
						int entities,
						String placementFingerprint) {

	public int nonAirCount() {
		int count = 0;
		for (BlockState state : this.blocks.values()) {
			if (!state.isAir()) {
				count++;
			}
		}
		return count;
	}
}
