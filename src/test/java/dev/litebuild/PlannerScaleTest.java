package dev.litebuild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import dev.litebuild.blueprint.BlueprintImporter;
import dev.litebuild.model.Blueprint;
import dev.litebuild.model.BuildTarget;
import dev.litebuild.model.Phase;
import dev.litebuild.plan.Planner;
import static org.junit.jupiter.api.Assertions.*;

/**
 * First size/performance evidence for the planner: a ~7.2k-cell blueprint
 * spanning all five phases must drain through the bounded
 * {@link Planner#VALIDATION_BATCH} sweep with persistent per-phase cursors —
 * far cells are reached (the >512 starvation regression class), the O(1)
 * counters stay cheap and candidate ordering over the full pool does not
 * degrade. All bounds are wall-clock regression guards on a map-backed
 * world, deliberately generous (this machine is slow under load).
 *
 * Importer bound coverage: MAX_REGION_CELLS / MAX_NON_AIR / CELLS_PER_TICK
 * are asserted as constants here; the real rejection paths need live
 * Litematica containers (SnapshotTask over a LitematicaBlockStateContainer)
 * and can only be exercised from the client gametest side — see
 * LiteBuildScaleTest notes. Unit-asserting rejection would require
 * fabricating Litematica internals, which is out of scope.
 */
class PlannerScaleTest extends McTestBase {

	/** 60x60 x 2 layers = 7200 STRUCTURE cells. */
	private static final int PLATFORM = 60;
	private static final int TORCHES = 10;
	private static final int REPEATERS = 20;
	/** Expected targets: platform + torches + repeaters + water + lever. */
	private static final int TOTAL = 2 * PLATFORM * PLATFORM + TORCHES + REPEATERS + 2;

	// Generous wall-clock bounds (ms): regression detection, not benchmarks.
	private static final long CONSTRUCT_BUDGET_MS = 10_000;
	private static final long SWEEP_BUDGET_MS = 30_000;
	private static final long COUNTERS_BUDGET_MS = 5_000;
	private static final long SORT_BUDGET_MS = 10_000;

	private Blueprint blueprint(Map<BlockPos, BlockState> blocks) {
		return new Blueprint("scale", Map.copyOf(blocks), Set.of(), 0, "fp");
	}

	/**
	 * ~7.2k cells: two solid 60x60 stone layers (STRUCTURE), a torch row
	 * (ATTACHMENTS), a repeater row (MECHANISMS) and one water + one lever
	 * at the far corner (FLUIDS/ACTIVATION) — the tail of the serpentine
	 * sweep, so cursor persistence is what reaches them.
	 */
	private Map<BlockPos, BlockState> largeBlueprint() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int y = 0; y <= 1; y++) {
			for (int x = 0; x < PLATFORM; x++) {
				for (int z = 0; z < PLATFORM; z++) {
					blocks.put(p(x, y, z), Blocks.STONE.defaultBlockState());
				}
			}
		}
		for (int x = 0; x < TORCHES; x++) {
			blocks.put(p(x, 2, 1), Blocks.TORCH.defaultBlockState());
		}
		for (int x = 0; x < REPEATERS; x++) {
			blocks.put(p(x, 2, 0), Blocks.REPEATER.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		}
		blocks.put(p(PLATFORM - 1, 2, PLATFORM - 1), Blocks.WATER.defaultBlockState());
		blocks.put(p(PLATFORM - 1, 2, PLATFORM - 2), Blocks.LEVER.defaultBlockState()
				.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.POWERED, false));
		return blocks;
	}

	@Test
	void largeBlueprintSweepReachesFarCells() {
		Map<BlockPos, BlockState> blocks = largeBlueprint();
		long c0 = System.nanoTime();
		Planner planner = new Planner(blueprint(blocks));
		long constructMs = (System.nanoTime() - c0) / 1_000_000;
		assertEquals(TOTAL, planner.totalTargets());

		MapWorld world = new MapWorld();
		Vec3 eye = Vec3.atCenterOf(p(0, 1, 0));
		Set<BlockPos> returned = new HashSet<>();
		// Each call serves at most ELIGIBLE_PER_CALL=8, so >= ~904 calls
		// are needed; 3x headroom tolerates partial/empty sweeps.
		int maxIterations = TOTAL * 3;
		int iterations = 0;
		long s0 = System.nanoTime();
		while (!planner.isComplete() && iterations < maxIterations) {
			List<BuildTarget> batch = planner.nextEligible(world, true, true, eye);
			for (BuildTarget t : batch) {
				world.set(t.pos(), t.state());
				planner.markVerified(t);
				returned.add(t.pos());
			}
			// O(1) counters polled every iteration like the HUD does.
			planner.verifiedCount();
			planner.totalTargets();
			iterations++;
		}
		long sweepMs = (System.nanoTime() - s0) / 1_000_000;
		System.out.println("[PSCALE] targets=" + planner.totalTargets()
				+ " constructMs=" + constructMs
				+ " sweepMs=" + sweepMs
				+ " iterations=" + iterations
				+ " verified=" + planner.verifiedCount() + "/" + planner.totalTargets());

		assertTrue(planner.isComplete(), "sweep stalled: verified="
				+ planner.verifiedCount() + "/" + planner.totalTargets()
				+ " iterations=" + iterations
				+ " issues=" + planner.issues().stream().limit(8).toList());
		// Persistent cursors carried the sweep to the far cells instead of
		// restarting at index 0 inside the giant STRUCTURE phase.
		assertTrue(returned.contains(p(PLATFORM - 1, 1, PLATFORM - 1)),
				"far STRUCTURE cell never offered");
		assertTrue(returned.contains(p(REPEATERS - 1, 2, 0)),
				"far MECHANISMS cell never offered");
		assertTrue(returned.contains(p(PLATFORM - 1, 2, PLATFORM - 1)),
				"FLUIDS cell never offered");
		assertTrue(returned.contains(p(PLATFORM - 1, 2, PLATFORM - 2)),
				"ACTIVATION cell never offered");
		assertFalse(planner.scanIncomplete(),
				"scan incomplete after full drain");
		assertTrue(sweepMs < SWEEP_BUDGET_MS,
				"sweep degraded: " + sweepMs + "ms > " + SWEEP_BUDGET_MS + "ms");
		assertTrue(constructMs < CONSTRUCT_BUDGET_MS,
				"construction degraded: " + constructMs + "ms");
	}

	@Test
	void countersAndCandidateOrderDoNotDegrade() {
		Planner planner = new Planner(blueprint(largeBlueprint()));
		int n = planner.totalTargets();

		// verifiedCount()/totalTargets() are O(1) set sizes — the HUD and
		// gametest diagnostics poll them per tick; 200k calls must stay noise.
		long t0 = System.nanoTime();
		long acc = 0;
		for (int i = 0; i < 100_000; i++) {
			acc += planner.verifiedCount() + planner.totalTargets();
		}
		long counterMs = (System.nanoTime() - t0) / 1_000_000;
		assertEquals((long) n * 100_000, acc);

		// Full-pool candidate ordering (used for bounded same-stance probing):
		// three sorts of ~7.2k targets must not degrade.
		Vec3 eye = Vec3.atCenterOf(p(0, 0, 0));
		BuildTarget first = null;
		long s0 = System.nanoTime();
		for (int rep = 0; rep < 3; rep++) {
			List<BuildTarget> pool = new ArrayList<>(planner.targets());
			pool.sort(Planner.candidateOrder(eye, 0.0f, 0.0f, null, null));
			first = pool.get(0);
		}
		long sortMs = (System.nanoTime() - s0) / 1_000_000;
		System.out.println("[PSCALE] counters200kMs=" + counterMs
				+ " sort3x" + n + "Ms=" + sortMs);

		assertTrue(counterMs < COUNTERS_BUDGET_MS,
				"counter polling degraded: " + counterMs + "ms");
		assertTrue(sortMs < SORT_BUDGET_MS,
				"candidate ordering degraded: " + sortMs + "ms");
		// Ordering sanity: earliest phase, lowest layer, nearest to the eye.
		assertNotNull(first);
		assertEquals(Phase.STRUCTURE, first.phase());
		assertEquals(p(0, 0, 0), first.pos());
	}

	@Test
	void importerBoundsAreDocumentedConstants() {
		// Cheap surface assertions: the constants exist and stay coherent.
		// The actual rejection paths (collectRegions cell budget, SnapshotTask
		// non-air budget) iterate live LitematicaBlockStateContainers and are
		// only reachable with a real placement loaded — gametest territory,
		// not reproducible here without fabricating Litematica internals.
		assertEquals(1_000_000, BlueprintImporter.MAX_REGION_CELLS);
		assertEquals(100_000, BlueprintImporter.MAX_NON_AIR);
		assertEquals(4096, BlueprintImporter.CELLS_PER_TICK);
		assertTrue(BlueprintImporter.MAX_NON_AIR <= BlueprintImporter.MAX_REGION_CELLS,
				"non-air cap exceeds the region cap — unreachable bound");
		assertTrue(BlueprintImporter.CELLS_PER_TICK >= 1);
		assertTrue(Planner.VALIDATION_BATCH >= 1);
	}
}
