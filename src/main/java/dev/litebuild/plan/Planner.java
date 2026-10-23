package dev.litebuild.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import dev.litebuild.model.Blueprint;
import dev.litebuild.model.BuildIssue;
import dev.litebuild.model.BuildTarget;
import dev.litebuild.model.Phase;
import dev.litebuild.motion.AngleMath;
import dev.litebuild.nav.NavWorld;
import dev.litebuild.state.StateRules;

/**
 * Orders blueprint cells bottom-up in serpentine rows grouped by phase and
 * evaluates live eligibility against the REAL world only — never an overlay
 * of blueprint cells.
 *
 * Waterlogged solids become two targets at one cell (dry shell + FLUIDS
 * task); double slabs become half-then-double targets at one cell;
 * multi-block counterparts (upper door half, bed head, double-plant top)
 * are dependent targets verified via world reads, never placed separately.
 *
 * The eligibility sweep keeps persistent per-phase cursors and remaining
 * counts so >512-entry phases finish a full pass across ticks instead of
 * restarting at index 0 forever.
 */
public class Planner {
	public static final int VALIDATION_BATCH = 512;
	private static final int ELIGIBLE_PER_CALL = 8;

	private final Blueprint blueprint;
	private final List<BuildTarget> targets = new ArrayList<>();
	private final EnumMap<Phase, List<BuildTarget>> byPhase = new EnumMap<>(Phase.class);
	private final Map<BlockPos, List<BuildTarget>> targetsAt = new HashMap<>();
	private final Set<BuildTarget> dependent = new HashSet<>();
	private final Set<BuildTarget> verified = new HashSet<>();
	private final Set<BlockPos> conflicts = new HashSet<>();
	private final Set<BlockPos> manual = new HashSet<>();
	private final Map<BlockPos, Integer> placeAttempts = new HashMap<>();
	private final Map<BlockPos, Integer> interactAttempts = new HashMap<>();
	private final List<BuildIssue> issues = new ArrayList<>();
	private final EnumMap<Phase, Integer> cursors = new EnumMap<>(Phase.class);
	/** Unfinished tasks per phase (verified/conflicted/manual decremented). */
	private final EnumMap<Phase, Integer> remaining = new EnumMap<>(Phase.class);
	/** True while the eligibility sweep has not covered every unfinished task. */
	private boolean scanIncomplete;
	private Phase disabledBlock;

	public Planner(Blueprint blueprint) {
		this.blueprint = blueprint;
		Map<BlockPos, BlockState> blocks = blueprint.blocks();

		for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
			BlockPos pos = entry.getKey();
			BlockState state = entry.getValue();
			if (state.isAir() || state.getBlock() == Blocks.STRUCTURE_VOID) {
				continue;
			}
			// Flowing fluid cells are expected results, never bucket tasks.
			if (state.getBlock() instanceof LiquidBlock
					&& !state.getFluidState().isSource()) {
				continue;
			}
			boolean waterlogged = state.hasProperty(BlockStateProperties.WATERLOGGED)
					&& state.getValue(BlockStateProperties.WATERLOGGED)
					&& !(state.getBlock() instanceof LiquidBlock);
			boolean doubleSlab = state.getBlock() instanceof SlabBlock
					&& state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
			if (waterlogged) {
				BlockState dry = state.setValue(BlockStateProperties.WATERLOGGED, false);
				targets.add(new BuildTarget(pos, dry, StateRules.phaseOf(dry)));
				targets.add(new BuildTarget(pos, state, Phase.FLUIDS));
			} else if (doubleSlab) {
				// Half slab first, then a real same-cell second placement.
				BlockState bottom = state.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
				targets.add(new BuildTarget(pos, bottom, StateRules.phaseOf(bottom)));
				targets.add(new BuildTarget(pos, state, StateRules.phaseOf(state)));
			} else {
				targets.add(new BuildTarget(pos, state, StateRules.phaseOf(state)));
			}
		}

		// Counterparts produced by the primary placement verify by world
		// reads only — they are never eligible for their own placement.
		for (BuildTarget t : targets) {
			BlockState s = t.state();
			var block = s.getBlock();
			if ((block instanceof DoorBlock || block instanceof DoublePlantBlock)
					&& s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
					&& s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
					&& blocks.containsKey(t.pos().below())) {
				dependent.add(t);
			} else if (block instanceof BedBlock
					&& s.getValue(BedBlock.PART) == BedPart.HEAD) {
				dependent.add(t);
			}
		}

		targets.sort(Comparator
				.comparingInt((BuildTarget t) -> t.phase().ordinal())
				.thenComparingInt(t -> t.pos().getY())
				.thenComparingInt(t -> t.pos().getZ())
				.thenComparingInt(t -> ((t.pos().getZ() & 1) == 0 ? 1 : -1) * t.pos().getX()));
		for (BuildTarget t : targets) {
			byPhase.computeIfAbsent(t.phase(), k -> new ArrayList<>()).add(t);
			targetsAt.computeIfAbsent(t.pos(), k -> new ArrayList<>()).add(t);
		}
		for (Phase phase : Phase.values()) {
			cursors.put(phase, 0);
			remaining.put(phase, byPhase.getOrDefault(phase, List.of()).size());
		}
	}

	public Blueprint blueprint() {
		return blueprint;
	}

	public List<BuildIssue> issues() {
		return issues;
	}

	public List<BuildTarget> targets() {
		return targets;
	}

	public List<BuildTarget> targetsAt(BlockPos pos) {
		return targetsAt.getOrDefault(pos, List.of());
	}

	public boolean isDependent(BuildTarget target) {
		return dependent.contains(target);
	}

	public boolean isVerified(BuildTarget target) {
		return verified.contains(target);
	}

	public void markVerified(BuildTarget target) {
		if (verified.add(target)) {
			remaining.merge(target.phase(), -1, Integer::sum);
		}
	}

	/** Marks every task at the cell verified (tests/convenience). */
	public void markVerified(BlockPos pos) {
		for (BuildTarget t : targetsAt(pos)) {
			markVerified(t);
		}
	}

	/** True when every task at the cell is verified. */
	public boolean isVerified(BlockPos pos) {
		for (BuildTarget t : targetsAt(pos)) {
			if (!verified.contains(t)) {
				return false;
			}
		}
		return true;
	}

	public void markConflict(BlockPos pos, String message) {
		if (conflicts.add(pos)) {
			issues.add(new BuildIssue(BuildIssue.Severity.CONFLICT, pos, message));
			for (BuildTarget t : targetsAt(pos)) {
				// A server-side change invalidates prior completion too —
				// verified state must never outlive its world evidence.
				if (!verified.remove(t)) {
					remaining.merge(t.phase(), -1, Integer::sum);
				}
			}
		}
	}

	public void markManual(BlockPos pos, String message) {
		if (manual.add(pos)) {
			issues.add(new BuildIssue(BuildIssue.Severity.MANUAL, pos, message));
			for (BuildTarget t : targetsAt(pos)) {
				if (!verified.remove(t)) {
					remaining.merge(t.phase(), -1, Integer::sum);
				}
			}
		}
		conflicts.add(pos);
	}

	/** Advisory only — the cell is still built normally. */
	public void noteManualRequirement(BlockPos pos, String message) {
		issues.add(new BuildIssue(BuildIssue.Severity.MANUAL, pos, message));
	}

	public boolean isConflicted(BlockPos pos) {
		return conflicts.contains(pos);
	}

	public boolean isManual(BlockPos pos) {
		return manual.contains(pos);
	}

	public boolean placeAttemptAllowed(BlockPos pos) {
		return placeAttempts.getOrDefault(pos, 0) < 2;
	}

	public void recordPlaceAttempt(BlockPos pos) {
		placeAttempts.merge(pos, 1, Integer::sum);
	}

	public boolean interactAttemptAllowed(BlockPos pos) {
		return interactAttempts.getOrDefault(pos, 0) < 3;
	}

	public void recordInteractAttempt(BlockPos pos) {
		interactAttempts.merge(pos, 1, Integer::sum);
	}

	public boolean isBlueprintCell(BlockPos pos) {
		return blueprint.blocks().containsKey(pos);
	}

	public BlockState expectedAt(BlockPos pos) {
		return blueprint.blocks().get(pos);
	}

	public int totalTargets() {
		return targets.size();
	}

	public int verifiedCount() {
		return verified.size();
	}

	/** Every target (incl. dependent counterparts) verified in the world. */
	public boolean isComplete() {
		return verified.size() >= targets.size();
	}

	public boolean scanIncomplete() {
		return scanIncomplete;
	}


	/**
	 * When every unverified remaining task sits in a phase the config
	 * disables, return that phase's name; otherwise null.
	 */
	public String onlyDisabledWorkRemaining(boolean fluidsEnabled, boolean activationEnabled) {
		for (BuildTarget t : targets) {
			if (verified.contains(t) || conflicts.contains(t.pos()) || manual.contains(t.pos())) {
				continue;
			}
			if (t.phase() == Phase.FLUIDS && !fluidsEnabled) {
				continue;
			}
			if (t.phase() == Phase.ACTIVATION && !activationEnabled) {
				continue;
			}
			return null; // real work remains in an enabled phase
		}
		for (BuildTarget t : targets) {
			if (verified.contains(t) || conflicts.contains(t.pos()) || manual.contains(t.pos())) {
				continue;
			}
			return t.phase() == Phase.FLUIDS ? "Fluids" : "Activation";
		}
		return null;
	}

	/**
	 * Eligible targets, scanning at most {@link #VALIDATION_BATCH} entries
	 * per call with persistent per-phase cursors. Phases with no remaining
	 * work cost nothing; an empty result while {@link #scanIncomplete()}
	 * means the sweep has not finished yet — not a stall. Later phases
	 * require all earlier work verified (conflicts block outright).
	 */
	public List<BuildTarget> nextEligible(NavWorld world, boolean fluidsEnabled,
										  boolean activationEnabled) {
		return nextEligible(world, fluidsEnabled, activationEnabled, null);
	}

	/**
	 * As {@link #nextEligible(NavWorld, boolean, boolean)}. With a non-null
	 * {@code eye} the scan keeps going through the same bounded budget and
	 * retains the {@link #ELIGIBLE_PER_CALL} best candidates by lowest layer
	 * first, then distance from the eye, instead of stopping at the first eight
	 * in build order. Phase gating, cursors and the 512 budget are unchanged.
	 */
	public List<BuildTarget> nextEligible(NavWorld world, boolean fluidsEnabled,
										  boolean activationEnabled, Vec3 eye) {
		List<BuildTarget> found = new ArrayList<>();
		int budget = VALIDATION_BATCH;
		scanIncomplete = false;
		disabledBlock = null;

		for (Phase phase : Phase.values()) {
			List<BuildTarget> list = byPhase.getOrDefault(phase, List.of());
			if (list.isEmpty() || remaining.get(phase) <= 0) {
				continue; // fully settled phase costs no budget
			}
			if (phase == Phase.FLUIDS && !fluidsEnabled) {
				scanIncomplete = true;
				disabledBlock = phase;
				continue;
			}
			if (phase == Phase.ACTIVATION && !activationEnabled) {
				scanIncomplete = true;
				disabledBlock = phase;
				continue;
			}
			if (phase.ordinal() > Phase.STRUCTURE.ordinal()
					&& (!earlierVerified(phase) || conflictBlocks(phase))) {
				scanIncomplete = true; // blocked but not done
				continue;
			}
			int cursor = cursors.get(phase);
			int scanned = 0;
			while (scanned < list.size() && budget > 0) {
				if (cursor >= list.size()) {
					cursor = 0;
				}
				BuildTarget target = list.get(cursor);
				cursor++;
				scanned++;
				// Already-settled entries are skipped WITHOUT consuming the
				// 512-candidate budget — otherwise a phase with more
				// verified entries than the budget would starve every later
				// phase forever (the reported >512 starvation bug).
				if (verified.contains(target) || conflicts.contains(target.pos())
						|| manual.contains(target.pos())) {
					continue;
				}
				budget--;
				if (!world.isLoaded(target.pos())) {
					continue;
				}
				BlockState actual = world.getBlockState(target.pos());
				// Construction progress: intrinsic match suffices while the
				// build is running; the final verifier applies full equality.
				// A cell already at its full desired state (e.g. preexisting
				// waterlog) satisfies every task there at once.
				// A cell already at its full blueprint state (e.g. a
				// preexisting waterlogged block) satisfies every task there.
				if (cellMatchesFinal(target.pos(), actual)) {
					// Only phases the config authorizes are auto-verified —
					// a matching world cell must not silently satisfy gated
					// fluid/activation work.
					for (BuildTarget t : targetsAt(target.pos())) {
						if (phaseAllowed(t.phase(), fluidsEnabled,
								activationEnabled)) {
							markVerified(t);
						}
					}
					continue;
				}
				// Construction progress: intrinsic match suffices while the
				// build is running; the final verifier applies full equality.
				if (StateRules.constructionEquivalent(target.state(), actual)) {
					markVerified(target);
					continue;
				}
				if (dependent.contains(target)) {
					continue; // counterpart only verifies via world reads
				}
				if (!isEligible(target, world)) {
					continue;
				}
				if (eye == null) {
					found.add(target);
					if (found.size() >= ELIGIBLE_PER_CALL) {
						break;
					}
				} else {
					found.add(target);
					found.sort(Comparator
							.comparingInt((BuildTarget t) -> t.pos().getY())
							.thenComparingDouble(t -> eye.distanceToSqr(
									Vec3.atCenterOf(t.pos()))));
					if (found.size() > ELIGIBLE_PER_CALL) {
						found.remove(found.size() - 1);
					}
				}
			}
			cursors.put(phase, cursor);
			if (scanned < list.size()) {
				scanIncomplete = true;
			}
			if (!found.isEmpty()) {
				break; // serve the earliest phase first
			}
			if (budget <= 0) {
				scanIncomplete = true;
				break;
			}
		}
		return found;
	}

	/**
	 * A conflicted cell in an earlier phase blocks later phases outright —
	 * unsafe fluids/activation must not follow a missing/corrupt shell.
	 */
	private boolean conflictBlocks(Phase phase) {
		for (BlockPos pos : conflicts) {
			for (BuildTarget t : targetsAt(pos)) {
				if (t.phase().ordinal() < phase.ordinal()
						&& !verified.contains(t)) {
					return true;
				}
			}
		}
		return false;
	}

	/** O(1)-per-phase gate using remaining counts, not per-task loops. */
	private boolean earlierVerified(Phase phase) {
		for (int i = 0; i < phase.ordinal(); i++) {
			if (remaining.get(Phase.values()[i]) > 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Live eligibility: canSurvive against the real world, gravity support
	 * via FallingBlock.isFree + sturdy face, and (for update-sensitive
	 * phases) blueprint structural neighbors verified.
	 */
	public boolean isEligible(BuildTarget target, NavWorld world) {
		BlockPos pos = target.pos();
		BlockState wanted = target.state();
		LevelReader reader = world instanceof LevelReader r ? r
				: world instanceof net.minecraft.world.level.BlockGetter bg
				? new ReaderAdapter(world, bg) : null;
		if (reader == null) {
			return false;
		}
		if (wanted.getBlock() instanceof FallingBlock) {
			BlockState below = world.getBlockState(pos.below());
			if (FallingBlock.isFree(below)
					|| !below.isFaceSturdy(reader, pos.below(), Direction.UP)) {
				return false;
			}
		}
		if (!wanted.canSurvive(reader, pos)) {
			return false;
		}
		if (target.phase().ordinal() >= Phase.MECHANISMS.ordinal()) {
			for (Direction dir : Direction.values()) {
				BlockPos neighbor = pos.relative(dir);
				BlockState expected = blueprint.blocks().get(neighbor);
				if (expected == null || expected.isAir()) {
					continue;
				}
				if (StateRules.phaseOf(expected).ordinal() <= Phase.ATTACHMENTS.ordinal()
						&& !earlyTasksVerifiedLive(world, neighbor)) {
					return false;
				}
			}
		}
		return true;
	}

	/** Phase authorization: disabled phases stay pending even when the
	 * live cell already matches the blueprint's final state. */
	public static boolean phaseAllowed(Phase phase, boolean fluidsEnabled,
									   boolean activationEnabled) {
		return (phase != Phase.FLUIDS || fluidsEnabled)
				&& (phase != Phase.ACTIVATION || activationEnabled);
	}

	/** Live cell equals the blueprint's full desired state for it. */
	private boolean cellMatchesFinal(BlockPos pos, BlockState actual) {
		BlockState expected = blueprint.blocks().get(pos);
		return expected != null && StateRules.finalEquivalent(expected, actual);
	}

	/** {@code target} is the intermediate half stage of a double-slab cell:
	 * satisfied by any already-placed half of the same slab block. */
	public boolean acceptsHalfSlabStage(BuildTarget target, BlockState actual) {
		boolean doubleSibling = false;
		for (BuildTarget t : targetsAt(target.pos())) {
			if (t != target && StateRules.canStackSlab(t.state(), actual)) {
				doubleSibling = true;
			}
		}
		return doubleSibling
				&& StateRules.slabStageEquivalent(target.state(), actual);
	}

	private boolean earlyTasksVerifiedLive(NavWorld world, BlockPos pos) {
		// Live-world dependency only: the verified set is a cache, never the
		// gate. Only the cell's early-phase tasks (structure/attachments)
		// must be verified — later-phase tasks at the same cell (waterlog,
		// activation) must not block a late-phase neighbor, or adjacent
		// waterloggable cells would deadlock on each other's FLUIDS task.
		// For multi-task cells (dry shell + waterlog, half + double slab)
		// an earlier task is satisfied when the live state matches it OR
		// the cell's full desired state already exists.
		for (BuildTarget t : targetsAt(pos)) {
			if (t.phase().ordinal() > Phase.ATTACHMENTS.ordinal()) {
				continue;
			}
			if (!world.isLoaded(pos)) {
				return false;
			}
			BlockState actual = world.getBlockState(pos);
			if (!StateRules.constructionEquivalent(t.state(), actual)
					&& !acceptsHalfSlabStage(t, actual)
					&& !cellMatchesFinal(pos, actual)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Minimal LevelReader over a plain BlockGetter/NavWorld so survival
	 * checks work in tests and off-world views without fabricating cells.
	 */
	private static final class ReaderAdapter implements LevelReader {
		private final NavWorld nav;
		private final net.minecraft.world.level.BlockGetter getter;

		ReaderAdapter(NavWorld nav, net.minecraft.world.level.BlockGetter getter) {
			this.nav = nav;
			this.getter = getter;
		}

		@Override public BlockState getBlockState(BlockPos pos) { return nav.getBlockState(pos); }
		@Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
			return nav.getBlockState(pos).getFluidState();
		}
		@Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
			return getter.getBlockEntity(pos);
		}
		@Override public int getHeight() { return getter.getHeight(); }
		@Override public int getMinY() { return getter.getMinY(); }
		@Override public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z,
				net.minecraft.world.level.chunk.status.ChunkStatus status, boolean create) { return null; }
		@Override public boolean hasChunk(int x, int z) { return true; }
		@Override public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types t, int x, int z) { return 0; }
		@Override public int getSkyDarken() { return 0; }
		@Override public net.minecraft.world.level.biome.BiomeManager getBiomeManager() { return null; }
		@Override public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) { return null; }
		@Override public boolean isClientSide() { return true; }
		@Override public int getSeaLevel() { return 63; }
		@Override public net.minecraft.world.level.dimension.DimensionType dimensionType() { return null; }
		@Override public net.minecraft.core.RegistryAccess registryAccess() { return null; }
		@Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
			return net.minecraft.world.flag.FeatureFlags.VANILLA_SET;
		}
		@Override public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
			return net.minecraft.world.attribute.EnvironmentAttributeReader.EMPTY;
		}
		@Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() { return null; }
		@Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
			return new net.minecraft.world.level.border.WorldBorder();
		}
		@Override public net.minecraft.world.level.BlockGetter getChunkForCollisions(int x, int z) { return getter; }
		@Override public java.util.List<net.minecraft.world.phys.shapes.VoxelShape> getEntityCollisions(
				net.minecraft.world.entity.Entity e, net.minecraft.world.phys.AABB box) { return java.util.List.of(); }
	}

	/** Waterlogged solid whose remaining task is the fluid part. */
	public static boolean isWaterlogTask(BuildTarget target) {
		return target.phase() == Phase.FLUIDS
				&& !(target.state().getBlock() instanceof LiquidBlock)
				&& target.state().hasProperty(BlockStateProperties.WATERLOGGED)
				&& target.state().getValue(BlockStateProperties.WATERLOGGED);
	}

	/**
	 * Per-candidate cost shared by {@link #chooseNext} and the same-stance
	 * probe ordering: eye distance plus view-turn effort plus a bias toward
	 * the last worked cell so patches finish before hopping. With a previous
	 * work position, a horizontal momentum term rewards continuing the
	 * current sweep direction (~serpentine) instead of ping-ponging; once
	 * nothing remains ahead every candidate pays the same penalty and the
	 * sweep flips naturally. Deterministic — no random draws.
	 */
	public static double scoreCandidate(BuildTarget t,
										net.minecraft.world.phys.Vec3 eye,
										float yaw, float pitch,
										BlockPos lastWorkPos, BlockPos prevWorkPos) {
		net.minecraft.world.phys.Vec3 center =
				net.minecraft.world.phys.Vec3.atCenterOf(t.pos());
		double dx = center.x - eye.x;
		double dy = center.y - eye.y;
		double dz = center.z - eye.z;
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double targetPitch = Math.toDegrees(
				-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		double score = eye.distanceToSqr(center)
				+ 0.15 * Math.abs(AngleMath.shortestDelta(yaw, targetYaw))
				+ 0.05 * Math.abs(pitch - targetPitch);
		if (lastWorkPos != null) {
			score += 0.7 * (Math.abs(t.pos().getX() - lastWorkPos.getX())
					+ Math.abs(t.pos().getZ() - lastWorkPos.getZ()))
					+ 2.0 * Math.abs(t.pos().getY() - lastWorkPos.getY());
		}
		if (lastWorkPos != null && prevWorkPos != null) {
			double ddx = lastWorkPos.getX() - prevWorkPos.getX();
			double ddz = lastWorkPos.getZ() - prevWorkPos.getZ();
			double odx = t.pos().getX() - lastWorkPos.getX();
			double odz = t.pos().getZ() - lastWorkPos.getZ();
			double dl = Math.sqrt(ddx * ddx + ddz * ddz);
			double ol = Math.sqrt(odx * odx + odz * odz);
			if (dl > 1e-6 && ol > 1e-6) {
				double dot = (ddx * odx + ddz * odz) / (dl * ol);
				score += 1.2 * (1.0 - dot);
			}
		}
		return score;
	}

	/**
	 * Ordering equivalent to {@link #chooseNext}'s pick priority: earliest
	 * phase, then lowest layer, then cheapest {@link #scoreCandidate}.
	 * Used to rank the pool for bounded same-stance probing.
	 */
	public static Comparator<BuildTarget> candidateOrder(
			net.minecraft.world.phys.Vec3 eye, float yaw, float pitch,
			BlockPos lastWorkPos, BlockPos prevWorkPos) {
		return candidateOrder(eye, yaw, pitch, lastWorkPos, prevWorkPos, null);
	}

	/** Bounded cost added when a candidate needs a different item than the one held. */
	static final double HELD_ITEM_MISMATCH_COST = 4.0;

	/**
	 * As {@link #candidateOrder} with a held-item preference: after phase and
	 * layer precedence, a candidate whose item differs from {@code held} pays a
	 * bounded extra cost, so same-material work in reach continues first.
	 */
	public static Comparator<BuildTarget> candidateOrder(
			Vec3 eye, float yaw, float pitch,
			BlockPos lastWorkPos, BlockPos prevWorkPos, Item held) {
		return Comparator
				.comparingInt((BuildTarget t) -> t.phase().ordinal())
				.thenComparingInt(t -> t.pos().getY())
				.thenComparingDouble(t -> scoreCandidate(t, eye, yaw, pitch,
						lastWorkPos, prevWorkPos)
						+ (held != null && ItemResolver.itemFor(t.state()) != held
								? HELD_ITEM_MISMATCH_COST : 0.0));
	}

	/**
	 * Stance ranking for ordinary structure placements. Cost is the distance
	 * from the player's feet to the stance plus a coverage penalty of 2 per
	 * pending cell that the stance cannot reach. Pending cells are the current
	 * target plus same-phase, same-layer, same-item candidates (capped at 24).
	 * Coverage is only a heuristic: the solver still picks the first solvable
	 * stance and every block is revalidated.
	 */
	public static Comparator<BlockPos> batchStanceOrder(List<BuildTarget> candidates,
														BuildTarget current,
														Vec3 playerFeet,
														double reach) {
		List<BuildTarget> relevant = new ArrayList<>();
		relevant.add(current);
		for (BuildTarget t : candidates) {
			if (relevant.size() >= 24) {
				break;
			}
			if (t.equals(current) || t.phase() != current.phase()
					|| t.pos().getY() != current.pos().getY()
					|| ItemResolver.itemFor(t.state()) != ItemResolver.itemFor(current.state())) {
				continue;
			}
			relevant.add(t);
		}
		final int size = relevant.size();
		return Comparator.comparingDouble((BlockPos stance) -> {
			Vec3 bottom = Vec3.atBottomCenterOf(stance);
			int count = 0;
			Vec3 eye = bottom.add(0, 1.62, 0);
			for (BuildTarget t : relevant) {
				if (eye.distanceTo(Vec3.atCenterOf(t.pos())) <= reach - 0.4) {
					count++;
				}
			}
			return playerFeet.distanceTo(bottom) + 2.0 * (size - count);
		}).thenComparing(stance -> stance);
	}

	/**
	 * Pick the next target from a candidate pool: earliest phase wins, then
	 * the lowest layer inside that phase (preserves build order), then the
	 * cheapest view/distance score biased toward the last worked cell so
	 * patches finish before hopping. Deterministic — no random draws.
	 */
	public static BuildTarget chooseNext(List<BuildTarget> candidates,
										 net.minecraft.world.phys.Vec3 eye,
										 float yaw, float pitch,
										 BlockPos lastWorkPos) {
		return chooseNext(candidates, eye, yaw, pitch, lastWorkPos, null);
	}

	/** As {@link #chooseNext} but with sweep momentum relative to the
	 * previous confirmed work position. */
	public static BuildTarget chooseNext(List<BuildTarget> candidates,
										 net.minecraft.world.phys.Vec3 eye,
										 float yaw, float pitch,
										 BlockPos lastWorkPos, BlockPos prevWorkPos) {
		BuildTarget best = null;
		double bestScore = Double.MAX_VALUE;
		for (BuildTarget t : candidates) {
			if (best != null
					&& (t.phase().ordinal() < best.phase().ordinal()
							|| (t.phase() == best.phase()
									&& t.pos().getY() < best.pos().getY()))) {
				best = t;
				bestScore = Double.MAX_VALUE;
			} else if (best != null
					&& (t.phase() != best.phase()
							|| t.pos().getY() != best.pos().getY())) {
				continue;
			}
			double score = scoreCandidate(t, eye, yaw, pitch,
					lastWorkPos, prevWorkPos);
			if (score < bestScore) {
				bestScore = score;
				best = t;
			}
		}
		return best;
	}
}
