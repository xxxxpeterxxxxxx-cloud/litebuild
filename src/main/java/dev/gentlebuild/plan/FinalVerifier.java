package dev.gentlebuild.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.VerificationMismatch;
import dev.gentlebuild.nav.NavWorld;
import dev.gentlebuild.state.StateRules;

/**
 * Incremental full verification: sweeps the cached blueprint cell list in
 * batches (required AIR cells included), records typed mismatches for the
 * whole sweep, and reports COMPLETE only after two consecutive clean passes
 * separated by the configured settle period. Detailed records are bounded at
 * {@link #MAX_MISMATCHES}; the total count stays accurate.
 */
public final class FinalVerifier {
	public static final int BATCH = Planner.VALIDATION_BATCH;
	public static final int MAX_MISMATCHES = 512;
	private static final int MAX_SHOWN = 32;
	private static final int MAX_RUNTIME_NOTES = 16;

	public enum Status { SWEEPING, SETTLING, COMPLETE, MISMATCH, UNLOADED }

	/**
	 * FINAL compares strict final state over the whole blueprint. CONSTRUCTION
	 * is for completed-stage checkpoints: neighbor connection shapes may still
	 * be pending, and runtime notes are not reported.
	 */
	public enum Mode { FINAL, CONSTRUCTION }

	private final List<Map.Entry<BlockPos, BlockState>> cells;
	private final Mode mode;
	private int cursor;
	private int passes;
	private int settleTicks;
	private boolean sweepMismatch;
	private int checkedCells;
	private int totalMismatches;
	private final List<VerificationMismatch> differences = new ArrayList<>();
	private final List<String> runtimeNotes = new ArrayList<>();

	public FinalVerifier(Blueprint blueprint) {
		this(blueprint, Mode.FINAL);
	}

	public FinalVerifier(Blueprint blueprint, Mode mode) {
		this.cells = new ArrayList<>(blueprint.blocks().entrySet());
		this.mode = mode;
	}

	/**
	 * Process up to {@link #BATCH} cells.
	 *
	 * @param supportOwned true when the cell holds a live, exactly-owned
	 *                     temporary support — still a mismatch (TEMP_SUPPORT),
	 *                     never a silent pass.
	 */
	public Status tick(NavWorld world, Predicate<BlockPos> supportOwned,
					   int configuredSettleTicks) {
		// After the first clean sweep, settle for the configured delay BEFORE
		// running the second sweep — not per sweep afterwards.
		if (settleTicks > 0) {
			settleTicks--;
			return Status.SETTLING;
		}
		int budget = BATCH;
		while (budget-- > 0 && cursor < cells.size()) {
			var e = cells.get(cursor++);
			BlockPos pos = e.getKey();
			BlockState expected = e.getValue();
			checkedCells++;
			if (!world.isLoaded(pos)) {
				addMismatch(new VerificationMismatch(pos, expected, null,
						VerificationMismatch.Kind.UNLOADED));
				return Status.UNLOADED;
			}
			BlockState actual = world.getBlockState(pos);
			VerificationMismatch.Kind kind = classify(expected, actual,
					supportOwned.test(pos), mode);
			if (kind != null) {
				addMismatch(new VerificationMismatch(pos, expected, actual, kind));
			} else if (mode == Mode.FINAL && !expected.equals(actual)
					&& !expected.isAir() && !actual.isAir()
					&& expected.getBlock() == actual.getBlock()) {
				// Same block, final-equivalent, differing only in
				// runtime-waived properties — informational only.
				if (runtimeNotes.size() < MAX_RUNTIME_NOTES) {
					runtimeNotes.add(pos.toShortString()
							+ ": Laufzeit-Zustand abweichend (power/age u.ae.)");
				}
			}
		}
		if (cursor < cells.size()) {
			return Status.SWEEPING;
		}
		boolean hadMismatch = sweepMismatch;
		cursor = 0;
		checkedCells = 0;
		sweepMismatch = false;
		if (hadMismatch) {
			return Status.MISMATCH;
		}
		if (passes == 0) {
			passes = 1;
			settleTicks = configuredSettleTicks;
			return Status.SETTLING;
		}
		return Status.COMPLETE;
	}

	private static VerificationMismatch.Kind classify(BlockState expected,
													  BlockState actual,
													  boolean supportOwned,
													  Mode mode) {
		if (expected.isAir()) {
			if (actual.isAir()) {
				return null;
			}
			return supportOwned
					? VerificationMismatch.Kind.TEMP_SUPPORT
					: VerificationMismatch.Kind.EXTRA_BLOCK;
		}
		if (actual.isAir()) {
			return VerificationMismatch.Kind.MISSING;
		}
		if (actual.getBlock() != expected.getBlock()) {
			return VerificationMismatch.Kind.WRONG_BLOCK;
		}
		boolean equivalent = mode == Mode.CONSTRUCTION
				? StateRules.constructionEquivalent(expected, actual)
				: StateRules.finalEquivalent(expected, actual);
		if (!equivalent) {
			return VerificationMismatch.Kind.WRONG_STATE;
		}
		return null;
	}

	private void addMismatch(VerificationMismatch m) {
		sweepMismatch = true;
		totalMismatches++;
		if (differences.size() < MAX_MISMATCHES) {
			differences.add(m);
		}
	}

	/** Restart verification entirely (e.g. after consuming the results). */
	public void reset() {
		cursor = 0;
		passes = 0;
		settleTicks = 0;
		sweepMismatch = false;
		checkedCells = 0;
		totalMismatches = 0;
		differences.clear();
		runtimeNotes.clear();
	}

	/** Typed records of the last sweep, bounded at {@link #MAX_MISMATCHES}. */
	public List<VerificationMismatch> differences() {
		return List.copyOf(differences);
	}

	/** True mismatch total across the whole sweep, beyond the detail cap. */
	public int mismatchCount() {
		return totalMismatches;
	}

	/** Human-readable lines, bounded at 32 plus a truncation note. */
	public List<String> mismatches() {
		List<String> lines = new ArrayList<>();
		for (VerificationMismatch m : differences) {
			if (lines.size() >= MAX_SHOWN) {
				break;
			}
			lines.add(m.pos().toShortString() + " [" + m.kind() + "] erwartet "
					+ m.expected() + ", gefunden "
					+ (m.actual() != null ? m.actual() : "nicht geladen"));
		}
		if (totalMismatches > lines.size()) {
			lines.add("... und " + (totalMismatches - lines.size())
					+ " weitere");
		}
		return lines;
	}

	/** Bounded informational notes about runtime-waived state differences. */
	public List<String> runtimeNotes() {
		return List.copyOf(runtimeNotes);
	}

	public int checkedCells() { return checkedCells; }
	public int totalCells() { return cells.size(); }
	public boolean sweepMismatch() { return sweepMismatch; }
}
