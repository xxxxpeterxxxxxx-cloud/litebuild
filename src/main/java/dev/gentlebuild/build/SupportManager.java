package dev.gentlebuild.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import dev.gentlebuild.nav.NavWorld;

/**
 * Owns temporary support blocks. Ownership is only recorded after the
 * server confirmed placement AND the cell was air beforehand — never
 * inferred, never pre-existing blocks. Cleanup is explicit, happens in
 * reverse placement order with normal reach/LOS breaking.
 */
public class SupportManager {
	private final Deque<BlockPos> owned = new ArrayDeque<>();
	private final Set<BlockPos> ownedSet = new HashSet<>();
	/** Expected block per owned cell — verified live before every break. */
	private final java.util.Map<BlockPos, BlockState> expectedBlock = new java.util.HashMap<>();
	/** Air cells we intend to fill; checked before recording ownership. */
	private final Set<BlockPos> plannedCells = new HashSet<>();

	public boolean owns(BlockPos pos) {
		return ownedSet.contains(pos);
	}

	/** Live state still exactly the block we placed there. */
	public boolean stillExpected(NavWorld world, BlockPos pos) {
		BlockState expected = expectedBlock.get(pos);
		return expected != null && world.getBlockState(pos).equals(expected);
	}

	public int ownedCount() {
		return ownedSet.size();
	}

	public java.util.Set<BlockPos> ownedPositions() {
		return java.util.Collections.unmodifiableSet(ownedSet);
	}

	/** Exact expected block per owned support cell (read-only). */
	public java.util.Map<BlockPos, BlockState> ownedStates() {
		return java.util.Collections.unmodifiableMap(expectedBlock);
	}

	public void planCell(BlockPos pos) {
		plannedCells.add(pos);
	}

	public boolean isPlanned(BlockPos pos) {
		return plannedCells.contains(pos);
	}

	public void unplan(BlockPos pos) {
		plannedCells.remove(pos);
	}

	/** Called when a support placement transaction confirms. */
	public void confirmOwnership(BlockPos pos) {
		confirmOwnership(pos, null);
	}

	public void confirmOwnership(BlockPos pos, BlockState placed) {
		if (plannedCells.remove(pos)) {
			owned.addLast(pos);
			ownedSet.add(pos);
			if (placed != null) {
				expectedBlock.put(pos, placed);
			}
		}
	}

	/** Reverse-order cleanup list; skips cells that now support the player or blueprint. */
	public List<BlockPos> cleanupOrder(NavWorld world,
									 java.util.function.Predicate<BlockPos> isBlueprint,
									 BlockPos playerPos) {
		List<BlockPos> order = new ArrayList<>();
		List<BlockPos> deferred = new ArrayList<>(owned);
		// Reverse dependency order: last placed removed first.
		for (int i = deferred.size() - 1; i >= 0; i--) {
			BlockPos pos = deferred.get(i);
			if (playerPos != null && pos.equals(playerPos.below())) {
				continue; // supporting the player right now
			}
			if (isBlueprint.test(pos)) {
				continue; // became part of final structure
			}
			BlockState state = world.getBlockState(pos);
			if (state.isAir()) {
				ownedSet.remove(pos);
				continue; // already gone
			}
			order.add(pos);
		}
		return order;
	}

	public void removed(BlockPos pos) {
		owned.remove(pos);
		ownedSet.remove(pos);
		plannedCells.remove(pos);
		expectedBlock.remove(pos);
	}

	public void clear() {
		owned.clear();
		ownedSet.clear();
		plannedCells.clear();
		expectedBlock.clear();
	}
}
