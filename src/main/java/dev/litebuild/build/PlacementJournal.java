package dev.litebuild.build;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Exact ownership journal for blocks this session placed itself. A position
 * is owned only when the pre-click cell was AIR and the authoritative server
 * acknowledgement produced a non-air block. Records are kept to the exact
 * state so a block that was later changed externally is never treated as
 * ours — the live-world check happens again at every use anyway.
 */
public final class PlacementJournal {
	private final Map<BlockPos, BlockState> owned = new HashMap<>();
	private final Map<BlockPos, BlockState> before = new HashMap<>();

	/** Normal PLACE confirmed: pre-state was air, ack produced non-air. */
	public void recordConfirmed(BlockPos pos, BlockState beforeState,
								BlockState authoritativeAfter) {
		if (pos == null || beforeState == null || authoritativeAfter == null) {
			return;
		}
		if (!beforeState.isAir() || authoritativeAfter.isAir()) {
			return; // never claim pre-existing blocks or failed placements
		}
		owned.put(pos.immutable(), authoritativeAfter);
		before.put(pos.immutable(), beforeState);
	}

	/** Keep a record in sync after our own confirmed ADJUST/stack step. */
	public void touchOwned(BlockPos pos, BlockState newAuthoritative) {
		BlockState cur = owned.get(pos);
		if (cur != null && newAuthoritative != null
				&& cur.getBlock() == newAuthoritative.getBlock()) {
			owned.put(pos, newAuthoritative);
		}
	}

	public boolean owns(BlockPos pos) {
		return owned.containsKey(pos);
	}

	public BlockState ownedState(BlockPos pos) {
		return owned.get(pos);
	}

	/** Live world still shows exactly the state we confirmed. */
	public boolean stillOurs(dev.litebuild.nav.NavWorld world, BlockPos pos) {
		BlockState rec = owned.get(pos);
		return rec != null && world.getBlockState(pos).equals(rec);
	}

	public void remove(BlockPos pos) {
		owned.remove(pos);
		before.remove(pos);
	}

	public Map<BlockPos, BlockState> owned() {
		return Collections.unmodifiableMap(owned);
	}

	public void clear() {
		owned.clear();
		before.clear();
	}
}
