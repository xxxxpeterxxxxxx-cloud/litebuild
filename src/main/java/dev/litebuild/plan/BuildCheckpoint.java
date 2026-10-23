package dev.litebuild.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import dev.litebuild.model.BuildTarget;
import dev.litebuild.model.Phase;

/**
 * Completed-stage batch awaiting a construction re-check. A structure-only
 * batch is due at {@link #STRUCTURE_LIMIT} unique cells; a batch containing
 * any later phase is due at {@link #SENSITIVE_LIMIT}. Re-recording a cell
 * updates its latest completed stage without growing the batch.
 */
public final class BuildCheckpoint {
	public static final int STRUCTURE_LIMIT = 10;
	public static final int SENSITIVE_LIMIT = 5;

	private final Map<BlockPos, BlockState> pending = new LinkedHashMap<>();
	private Phase lastPhase;
	private boolean sensitive;

	public void record(BuildTarget target) {
		pending.put(target.pos(), target.state());
		if (target.phase() != Phase.STRUCTURE) {
			sensitive = true;
		}
		lastPhase = target.phase();
	}

	public boolean due() {
		return due(STRUCTURE_LIMIT, SENSITIVE_LIMIT);
	}

	/** Same threshold rule with configurable bounds (sensitive never above structure). */
	public boolean due(int structureLimit, int sensitiveLimit) {
		int structure = Math.max(5, Math.min(10, structureLimit));
		int sensitive = Math.max(5, Math.min(structure, sensitiveLimit));
		return !pending.isEmpty()
				&& pending.size() >= (this.sensitive ? sensitive : structure);
	}

	/** A non-empty batch flushes before the build moves into another phase. */
	public boolean shouldFlush(Phase next) {
		return !pending.isEmpty() && next != lastPhase;
	}

	public boolean isEmpty() {
		return pending.isEmpty();
	}

	public Map<BlockPos, BlockState> snapshot() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(pending));
	}

	public void clear() {
		pending.clear();
		lastPhase = null;
		sensitive = false;
	}
}
