package dev.gentlebuild;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.BuildTarget;
import dev.gentlebuild.model.Phase;
import dev.gentlebuild.plan.Planner;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Second scale tier for the planner: ~30.5k targets (4x PlannerScaleTest)
 * with every multi-task cell shape — double slabs, waterlogged solids and
 * door halves with dependent counterparts — inside one blueprint. The same
 * bounded {@link Planner#VALIDATION_BATCH} sweep with per-phase cursors
 * must drain the whole pool, the phase partition must be exact, and
 * construction/sweep/ordering must stay inside generous wall-clock and
 * heap bounds. Bounds are regression guards on a map-backed world, not
 * benchmarks (this machine is slow under load).
 *
 * Sweep cost shape: each call evaluates at most VALIDATION_BATCH unverified
 * candidates and re-scans a phase's list with cheap verified-skips between
 * batches, so draining N targets costs O(N) evaluations plus O(N/8) list
 * passes of skip-work — super-linear only in hash lookups, not in world
 * reads. A quadratic-in-world-access blowup would exceed the budgets here.
 */
class PlannerLargeScaleTest extends McTestBase {

	/** 150x100 x 2 layers = 30,000 STRUCTURE cells. */
	private static final int W = 150;
	private static final int D = 100;
	private static final int TORCHES = 200;
	private static final int REPEATERS = 100;
	/** Door cells = lowers + dependent uppers. */
	private static final int DOORS = 40;
	/** Double-slab cells produce a STRUCTURE (double) + ATTACHMENTS
	 * (half stage) target each. */
	private static final int DOUBLE_SLABS = 30;
	/** Waterlogged-slab cells produce a dry shell + a FLUIDS task each. */
	private static final int WATERLOGGED = 20;
	private static final int WATER = 10;
	private static final int LEVERS = 20;

	private static final int TOTAL = 2 * W * D + TORCHES + REPEATERS
			+ 2 * DOORS + 2 * DOUBLE_SLABS + 2 * WATERLOGGED + WATER + LEVERS;
	// Slab phase rules (StateRules.phaseOf): slabs count as solid under
	// the 26.2 isSolid() semantics, so every slab task — double state,
	// half stage and the dry shell of a waterlogged slab — lands in
	// STRUCTURE; only the fluid task is FLUIDS.
	private static final int EXPECTED_STRUCTURE = 2 * W * D + 2 * DOUBLE_SLABS + WATERLOGGED;
	private static final int EXPECTED_ATTACHMENTS = TORCHES;
	private static final int EXPECTED_MECHANISMS = REPEATERS + 2 * DOORS;
	private static final int EXPECTED_FLUIDS = WATER + WATERLOGGED;
	private static final int EXPECTED_ACTIVATION = LEVERS;
	private static final int EXPECTED_DEPENDENT = DOORS;

	// Generous wall-clock/heap bounds: regression detection, not benchmarks.
	private static final long CONSTRUCT_BUDGET_MS = 20_000;
	private static final long SWEEP_BUDGET_MS = 45_000;
	private static final long SORT_BUDGET_MS = 20_000;
	/** Blueprint + planner + simulated world must stay far under this. */
	private static final long HEAP_BUDGET_MB = 1024;

	private static final BlockState DOOR_LOWER = Blocks.OAK_DOOR.defaultBlockState()
			.setValue(DoorBlock.FACING, Direction.NORTH)
			.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
			.setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
			.setValue(DoorBlock.OPEN, false)
			.setValue(DoorBlock.POWERED, false);
	private static final BlockState DOOR_UPPER = DOOR_LOWER
			.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

	private Blueprint blueprint(Map<BlockPos, BlockState> blocks) {
		return new Blueprint("xl", Map.copyOf(blocks), Set.of(), 0, "fp");
	}

	/**
	 * ~30.5k cells: two solid 150x100 stone layers (STRUCTURE), a row of
	 * double slabs and a row of spaced waterlogged slabs (multi-task
	 * cells), torch and repeater rows (ATTACHMENTS/MECHANISMS), a door row
	 * whose upper halves are dependent targets, and water + levers at the
	 * far corner (FLUIDS/ACTIVATION) — the tail of the serpentine sweep,
	 * so cursor persistence is what reaches them.
	 */
	private Map<BlockPos, BlockState> xlBlueprint() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int y = 0; y <= 1; y++) {
			for (int x = 0; x < W; x++) {
				for (int z = 0; z < D; z++) {
					blocks.put(p(x, y, z), Blocks.STONE.defaultBlockState());
				}
			}
		}
		for (int x = 0; x < TORCHES; x++) {
			blocks.put(p(x % W, 2, 10 + x / W), Blocks.TORCH.defaultBlockState());
		}
		for (int x = 0; x < REPEATERS; x++) {
			blocks.put(p(x, 2, 12), Blocks.REPEATER.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		}
		for (int x = 0; x < DOORS; x++) {
			blocks.put(p(x, 2, 14), DOOR_LOWER);
			blocks.put(p(x, 3, 14), DOOR_UPPER);
		}
		for (int x = 0; x < DOUBLE_SLABS; x++) {
			blocks.put(p(x, 2, 16), Blocks.STONE_SLAB.defaultBlockState()
					.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE));
		}
		// Waterlogged slabs are SPACED (x*7): a FLUIDS task gates on every
		// blueprint neighbor of phase <= ATTACHMENTS having its early-phase
		// tasks live-verified, and a waterlogged bottom slab's blueprint
		// state classifies as STRUCTURE — two adjacent ones would have
		// deadlocked on each other's fluid task before the early-phase-only
		// neighbor check.
		for (int x = 0; x < WATERLOGGED; x++) {
			blocks.put(p(x * 7, 2, 18), Blocks.PRISMARINE_SLAB.defaultBlockState()
					.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
					.setValue(BlockStateProperties.WATERLOGGED, true));
		}
		for (int i = 0; i < WATER; i++) {
			blocks.put(p(W - 1 - i, 2, D - 1), Blocks.WATER.defaultBlockState());
		}
		for (int i = 0; i < LEVERS; i++) {
			blocks.put(p(W - 11 - i, 2, D - 1), Blocks.LEVER.defaultBlockState()
					.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
					.setValue(BlockStateProperties.POWERED, false));
		}
		return blocks;
	}

	/** One O(N) pass recording the iteration each phase fully verified. */
	private void recordDrains(Planner planner,
							  EnumMap<Phase, Integer> expectedByPhase,
							  EnumMap<Phase, Long> drainedAtIter,
							  int iterations) {
		EnumMap<Phase, Integer> counts = new EnumMap<>(Phase.class);
		for (BuildTarget t : planner.targets()) {
			if (planner.isVerified(t)) {
				counts.merge(t.phase(), 1, Integer::sum);
			}
		}
		for (Phase ph : Phase.values()) {
			if (expectedByPhase.getOrDefault(ph, 0) > 0
					&& !drainedAtIter.containsKey(ph)
					&& counts.getOrDefault(ph, 0) >= expectedByPhase.get(ph)) {
				drainedAtIter.put(ph, (long) iterations);
			}
		}
	}

	/**
	 * Simulated placement: a served target writes its own state; a door
	 * placement also produces the dependent upper half, exactly like the
	 * real interaction.
	 */
	private void place(MapWorld world, BuildTarget t) {
		world.set(t.pos(), t.state());
		if (t.state().getBlock() instanceof DoorBlock) {
			world.set(t.pos().above(), DOOR_UPPER);
		}
	}

	@Test
	void xlBlueprintSweepDrainsAllPhases() {
		Runtime rt = Runtime.getRuntime();
		rt.gc();
		long memBefore = rt.totalMemory() - rt.freeMemory();

		Map<BlockPos, BlockState> blocks = xlBlueprint();
		long c0 = System.nanoTime();
		Planner planner = new Planner(blueprint(blocks));
		long constructMs = (System.nanoTime() - c0) / 1_000_000;
		assertEquals(TOTAL, planner.totalTargets());

		// Exact phase partition and dependent-counterpart count.
		EnumMap<Phase, Integer> expectedByPhase = new EnumMap<>(Phase.class);
		int dependent = 0;
		for (BuildTarget t : planner.targets()) {
			expectedByPhase.merge(t.phase(), 1, Integer::sum);
			if (planner.isDependent(t)) {
				dependent++;
			}
		}
		assertEquals(EXPECTED_STRUCTURE, expectedByPhase.getOrDefault(Phase.STRUCTURE, 0));
		assertEquals(EXPECTED_ATTACHMENTS, expectedByPhase.getOrDefault(Phase.ATTACHMENTS, 0));
		assertEquals(EXPECTED_MECHANISMS, expectedByPhase.getOrDefault(Phase.MECHANISMS, 0));
		assertEquals(EXPECTED_FLUIDS, expectedByPhase.getOrDefault(Phase.FLUIDS, 0));
		assertEquals(EXPECTED_ACTIVATION, expectedByPhase.getOrDefault(Phase.ACTIVATION, 0));
		assertEquals(EXPECTED_DEPENDENT, dependent,
				"door upper halves must be dependent targets");

		MapWorld world = new MapWorld();
		Vec3 eye = Vec3.atCenterOf(p(0, 1, 0));
		Set<BlockPos> returned = new HashSet<>();
		EnumMap<Phase, Long> drainedAtIter = new EnumMap<>(Phase.class);
		// Each call serves at most ELIGIBLE_PER_CALL=8, so >= ~3.9k calls
		// are needed; 3x headroom tolerates partial/empty sweeps.
		int maxIterations = TOTAL * 3;
		int iterations = 0;
		long s0 = System.nanoTime();
		while (!planner.isComplete() && iterations < maxIterations) {
			List<BuildTarget> batch = planner.nextEligible(world, true, true, eye);
			for (BuildTarget t : batch) {
				place(world, t);
				planner.markVerified(t);
				returned.add(t.pos());
			}
			planner.verifiedCount();
			planner.totalTargets();
			// Per-phase drain ticks: one full pass over the pool every 64
			// iterations keeps the metric cheap (drain order is only a
			// coarse signal, not a per-iteration gate).
			if (iterations % 64 == 0) {
				recordDrains(planner, expectedByPhase, drainedAtIter, iterations);
			}
			iterations++;
		}
		recordDrains(planner, expectedByPhase, drainedAtIter, iterations);
		long sweepMs = (System.nanoTime() - s0) / 1_000_000;
		rt.gc();
		long memAfter = rt.totalMemory() - rt.freeMemory();
		long deltaMB = (memAfter - memBefore) >> 20;

		StringBuilder drain = new StringBuilder();
		for (Phase ph : Phase.values()) {
			Long it = drainedAtIter.get(ph);
			drain.append(' ').append(ph).append('=')
					.append(it == null ? "never" : it.toString());
		}
		System.out.println("[PXSCALE] targets=" + planner.totalTargets()
				+ " constructMs=" + constructMs
				+ " sweepMs=" + sweepMs
				+ " iterations=" + iterations
				+ " verified=" + planner.verifiedCount() + "/" + planner.totalTargets()
				+ " drainedAtIter" + drain
				+ " heapDeltaMB=" + deltaMB);

		assertTrue(planner.isComplete(), "sweep stalled: verified="
				+ planner.verifiedCount() + "/" + planner.totalTargets()
				+ " iterations=" + iterations
				+ " issues=" + planner.issues().stream().limit(8).toList());
		// Serpentine tail: the far STRUCTURE/FLUIDS/ACTIVATION cells were
		// reached via persistent cursors, and dependent door uppers were
		// verified through world reads only (never offered).
		assertTrue(returned.contains(p(W - 1, 1, D - 1)),
				"far STRUCTURE cell never offered");
		assertTrue(returned.contains(p(REPEATERS - 1, 2, 12)),
				"far MECHANISMS cell never offered");
		assertTrue(returned.contains(p(W - 1, 2, D - 1)),
				"FLUIDS cell never offered");
		assertTrue(returned.contains(p(W - 11, 2, D - 1)),
				"ACTIVATION cell never offered");
		for (int x = 0; x < DOORS; x++) {
			assertFalse(returned.contains(p(x, 3, 14)),
					"dependent door upper was offered for placement");
		}
		// Phase order: everything earlier drained before anything later.
		for (int i = 1; i < Phase.values().length; i++) {
			Phase ph = Phase.values()[i];
			Long prev = drainedAtIter.get(Phase.values()[i - 1]);
			Long cur = drainedAtIter.get(ph);
			if (expectedByPhase.getOrDefault(ph, 0) > 0) {
				assertNotNull(cur, ph + " never drained");
				assertNotNull(prev, Phase.values()[i - 1] + " never drained");
				assertTrue(cur >= prev,
						ph + " drained before " + Phase.values()[i - 1]);
			}
		}
		assertFalse(planner.scanIncomplete(),
				"scan incomplete after full drain");
		assertTrue(sweepMs < SWEEP_BUDGET_MS,
				"sweep degraded: " + sweepMs + "ms > " + SWEEP_BUDGET_MS + "ms");
		assertTrue(constructMs < CONSTRUCT_BUDGET_MS,
				"construction degraded: " + constructMs + "ms");
		assertTrue(deltaMB < HEAP_BUDGET_MB,
				"heap growth degraded: " + deltaMB + "MB");
	}

	@Test
	void candidateOrderDoesNotDegradeAtXl() {
		Planner planner = new Planner(blueprint(xlBlueprint()));
		int n = planner.totalTargets();

		// Full-pool ordering over ~30.5k targets — the same-stance probe
		// sorts this pool; three sorts must not degrade.
		Vec3 eye = Vec3.atCenterOf(p(0, 0, 0));
		BuildTarget first = null;
		long s0 = System.nanoTime();
		for (int rep = 0; rep < 3; rep++) {
			List<BuildTarget> pool = new ArrayList<>(planner.targets());
			pool.sort(Planner.candidateOrder(eye, 0.0f, 0.0f, null, null));
			first = pool.get(0);
		}
		long sortMs = (System.nanoTime() - s0) / 1_000_000;
		System.out.println("[PXSCALE] sort3x" + n + "Ms=" + sortMs);

		assertTrue(sortMs < SORT_BUDGET_MS,
				"candidate ordering degraded: " + sortMs + "ms");
		assertNotNull(first);
		assertEquals(Phase.STRUCTURE, first.phase());
		assertEquals(p(0, 0, 0), first.pos());
	}
}
