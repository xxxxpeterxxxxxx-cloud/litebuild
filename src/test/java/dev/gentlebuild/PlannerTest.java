package dev.gentlebuild;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.BuildTarget;
import dev.gentlebuild.model.Phase;
import dev.gentlebuild.plan.Planner;
import dev.gentlebuild.state.StateRules;
import static org.junit.jupiter.api.Assertions.*;

class PlannerTest extends McTestBase {

	private Blueprint blueprint(Map<BlockPos, BlockState> blocks) {
		return blueprint(blocks, Set.of());
	}

	private Blueprint blueprint(Map<BlockPos, BlockState> blocks, Set<BlockPos> blockEntities) {
		return new Blueprint("test", Map.copyOf(blocks), blockEntities, 0, "fp");
	}

	@Test
	void torchWaitsForSupport() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 1, 0), Blocks.TORCH.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));

		// Empty world: the stone must be offered before the torch.
		MapWorld world = new MapWorld();
		var first = planner.nextEligible(world, true, true);
		assertFalse(first.isEmpty());
		assertEquals(Phase.STRUCTURE, first.get(0).phase());
		assertEquals(p(0, 0, 0), first.get(0).pos());

		// Stone verified -> torch becomes eligible.
		planner.markVerified(p(0, 0, 0));
		world.set(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		var next = planner.nextEligible(world, true, true);
		assertTrue(next.stream().anyMatch(t -> t.pos().equals(p(0, 1, 0))));
	}

	@Test
	void alreadyCorrectCountsVerified() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		var eligible = planner.nextEligible(world, true, true);
		assertTrue(planner.isVerified(p(0, 0, 0)));
		assertTrue(eligible.stream().noneMatch(t -> t.pos().equals(p(0, 0, 0))));
		assertTrue(planner.isComplete());
	}

	@Test
	void activationGatedUntilEnabled() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.LEVER.defaultBlockState()
				.setValue(net.minecraft.world.level.block.LeverBlock.FACE,
						net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
		blocks.put(p(0, -1, 0), Blocks.STONE.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		world.set(p(0, -1, 0), Blocks.STONE.defaultBlockState());
		planner.markVerified(p(0, -1, 0));

		var gated = planner.nextEligible(world, true, false);
		assertTrue(gated.stream().noneMatch(t -> t.phase() == Phase.ACTIVATION));
		var open = planner.nextEligible(world, true, true);
		assertTrue(open.stream().anyMatch(t -> t.phase() == Phase.ACTIVATION));
	}

	@Test
	void waterloggedSolidSplits() {
		BlockState wet = Blocks.OAK_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.WATERLOGGED, true);
		// The fluid half of a waterlogged solid is the FLUIDS-phase task.
		assertTrue(Planner.isWaterlogTask(new BuildTarget(p(0, 0, 0), wet, Phase.FLUIDS)));
		assertFalse(Planner.isWaterlogTask(new BuildTarget(p(0, 0, 0),
				Blocks.OAK_SLAB.defaultBlockState(), Phase.STRUCTURE)));
	}

	@Test
	void serpentineOrderIsDeterministic() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 2; z++) {
				blocks.put(p(x, 0, z), Blocks.STONE.defaultBlockState());
			}
		}
		Planner a = new Planner(blueprint(blocks));
		Planner b = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		var ea = a.nextEligible(world, true, true);
		var eb = b.nextEligible(world, true, true);
		assertEquals(ea, eb);
	}
	@Test
	void doorCounterpartNotPlacedSeparately() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, -1, 0), Blocks.STONE.defaultBlockState());
		var door = Blocks.OAK_DOOR.defaultBlockState();
		blocks.put(p(0, 0, 0), door.setValue(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
				net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
		blocks.put(p(0, 1, 0), door.setValue(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
				net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
		Planner planner = new Planner(blueprint(blocks));
		// Both cells are targets but only the lower half may be offered.
		assertEquals(3, planner.totalTargets());
		MapWorld world = new MapWorld();
		world.set(p(0, -1, 0), Blocks.STONE.defaultBlockState());
		planner.markVerified(p(0, -1, 0));
		var eligible = planner.nextEligible(world, true, true);
		assertTrue(eligible.stream().noneMatch(t -> t.pos().equals(p(0, 1, 0))),
				"upper door half must never be a placement target");
	}

	@Test
	void hopperNotExcludedByBlockEntity() {
		// NBT data is advisory; the block itself is still built.
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, -1, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 0, 0), Blocks.HOPPER.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks, Set.of(p(0, 0, 0))));
		MapWorld world = new MapWorld();
		world.set(p(0, -1, 0), Blocks.STONE.defaultBlockState());
		planner.markVerified(p(0, -1, 0));
		var eligible = planner.nextEligible(world, true, true);
		assertTrue(eligible.stream().anyMatch(t -> t.pos().equals(p(0, 0, 0))));
	}

	@Test
	void largeStructurePhaseReachesMechanism() {
		// >512 STRUCTURE targets: the persistent sweep must still reach the
		// mechanism phase once structure is drained.
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int i = 0; i < 600; i++) {
			blocks.put(p(i, 0, 0), Blocks.STONE.defaultBlockState());
		}
		blocks.put(p(0, 1, 0), Blocks.REPEATER.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		boolean mechanismSeen = false;
		for (int iter = 0; iter < 700 && !mechanismSeen; iter++) {
			var eligible = planner.nextEligible(world, true, true);
			for (BuildTarget t : eligible) {
				if (t.phase() == Phase.MECHANISMS) {
					mechanismSeen = true;
					break;
				}
				world.set(t.pos(), t.state());
				planner.markVerified(t);
			}
		}
		assertTrue(mechanismSeen, "mechanism phase never reached: verified="
				+ planner.verifiedCount() + "/" + planner.totalTargets()
				+ " issues=" + planner.issues());
	}

	@Test
	void wetSplitIsTwoTasksAndDryDoesNotFinishCell() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		BlockState wet = Blocks.OAK_SLAB.defaultBlockState().setValue(
				BlockStateProperties.WATERLOGGED, true);
		blocks.put(p(0, 0, 0), wet);
		Planner planner = new Planner(blueprint(blocks));
		assertEquals(2, planner.targetsAt(p(0, 0, 0)).size());
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), wet.setValue(BlockStateProperties.WATERLOGGED, false));
		planner.nextEligible(world, true, true);
		// Dry shell verified, wet task still open.
		assertFalse(planner.isVerified(p(0, 0, 0)));
		assertFalse(planner.isComplete());
		// Now the wet state appears: both tasks done.
		world.set(p(0, 0, 0), wet);
		planner.nextEligible(world, true, true);
		assertTrue(planner.isVerified(p(0, 0, 0)));
		assertTrue(planner.isComplete());
	}

	@Test
	void preexistingWetAcceptsDryShell() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		BlockState wet = Blocks.OAK_SLAB.defaultBlockState().setValue(
				BlockStateProperties.WATERLOGGED, true);
		blocks.put(p(0, 0, 0), wet);
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), wet); // already fully waterlogged
		planner.nextEligible(world, true, true);
		assertTrue(planner.isVerified(p(0, 0, 0)));
		assertTrue(planner.isComplete());
	}

	@Test
	void adjacentWaterloggedCellsDoNotDeadlock() {
		// Two neighboring waterloggable cells: each cell's FLUIDS task
		// must not wait on the other's FLUIDS task — the late-phase
		// neighbor check may only require early-phase work.
		BlockState dry = Blocks.OAK_SLAB.defaultBlockState();
		BlockState wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), wet);
		blocks.put(p(1, 0, 0), wet);
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), dry);
		world.set(p(1, 0, 0), dry);
		var eligible = planner.nextEligible(world, true, true);
		assertTrue(eligible.stream().anyMatch(t -> t.phase() == Phase.FLUIDS),
				"waterlog tasks beside a waterloggable neighbor must stay eligible");
	}

	@Test
	void mechanismBesideUnwaterloggedCellIsNotBlocked() {
		// A mechanism on/beside a cell whose waterlog task is still open
		// may only wait on that cell's structure, not on the fluid task.
		BlockState slabTopDry = Blocks.OAK_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE,
						net.minecraft.world.level.block.state.properties.SlabType.TOP);
		BlockState slabTopWet = slabTopDry.setValue(
				BlockStateProperties.WATERLOGGED, true);
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), slabTopWet);
		blocks.put(p(0, 1, 0), Blocks.REPEATER.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), slabTopDry); // structure done, waterlog pending
		var eligible = planner.nextEligible(world, true, true);
		assertTrue(eligible.stream().anyMatch(t -> t.phase() == Phase.MECHANISMS),
				"mechanism must not wait on a neighbor's pending waterlog task");
	}

	@Test
	void conflictBlocksFluidGate() {
		// A conflicted/missing earlier-phase task must block FLUIDS outright.
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 2, 0), Blocks.WATER.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		planner.markConflict(p(0, 0, 0), "wrong block");
		for (int i = 0; i < 4; i++) {
			var eligible = planner.nextEligible(world, true, true);
			assertTrue(eligible.stream().noneMatch(t -> t.phase() == Phase.FLUIDS),
					"fluid task must stay gated behind an unresolved conflict");
		}
	}

	@Test
	void correctedConflictAllowsResume() {
		// Simulates resume: a fresh planner over the same blueprint picks up
		// the previously conflicted cell once the world is corrected.
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		planner.nextEligible(world, true, true);
		planner.markConflict(p(0, 0, 0), "falsch");
		// world corrected -> fresh planner verifies immediately
		world.set(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		Planner resumed = new Planner(blueprint(blocks));
		resumed.nextEligible(world, true, true);
		assertTrue(resumed.isComplete());
	}

	@Test
	void removedSupportReblocksFluidGate() {
		// A STRUCTURE cell that loses its verified state (server updated it
		// away) must block the fluid phase again.
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 2, 0), Blocks.WATER.defaultBlockState());
		Planner planner = new Planner(blueprint(blocks));
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		planner.nextEligible(world, true, true);
		planner.nextEligible(world, true, true);
		// Now the support is removed server-side and the ack invalidates it.
		world.set(p(0, 0, 0), Blocks.AIR.defaultBlockState());
		planner.markConflict(p(0, 0, 0), "removed");
		var eligible = planner.nextEligible(world, true, true);
		assertTrue(eligible.stream().noneMatch(t -> t.phase() == Phase.FLUIDS));
	}

	@Test
	void neighborDependentStairShapeNotConflict() {
		// A stair placed before its neighbors get a recalculated shape:
		// construction accepts it, final verification does not.
		BlockState straight = Blocks.OAK_STAIRS.defaultBlockState();
		BlockState corner = straight.cycle(net.minecraft.world.level.block.StairBlock.SHAPE);
		assertTrue(StateRules.constructionEquivalent(straight, corner)
				|| StateRules.constructionEquivalent(corner, straight));
		assertFalse(StateRules.finalEquivalent(straight, corner));
	}

	@Test
	void repeaterDelayStepsOneToFour() {
		BlockState step1 = Blocks.REPEATER.defaultBlockState();
		BlockState target = step1.cycle(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.DELAY)
				.cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.DELAY)
				.cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.DELAY);
		// delay=1 initial -> interact steps 2,3,4, then done
		BlockState cur = StateRules.nextInteractionStep(target, step1);
		assertNotNull(cur);
		assertEquals(2, cur.getValue(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.DELAY));
		cur = StateRules.nextInteractionStep(target, cur);
		assertEquals(3, cur.getValue(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.DELAY));
		cur = StateRules.nextInteractionStep(target, cur);
		assertEquals(4, cur.getValue(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.DELAY));
		assertNull(StateRules.nextInteractionStep(target, cur));
	}

	private BuildTarget tgt(int x, int y, int z, Phase ph) {
		return new BuildTarget(p(x, y, z), Blocks.STONE.defaultBlockState(), ph);
	}

	@Test
	void chooseNextPrefersFrontFacing() {
		// Equal distance: one in view, one behind — front wins.
		var front = tgt(0, 64, 5, Phase.STRUCTURE);
		var back = tgt(0, 64, -5, Phase.STRUCTURE);
		var eye = new net.minecraft.world.phys.Vec3(0.5, 65.62, 0.5);
		var pick = Planner.chooseNext(java.util.List.of(back, front), eye,
				0.0f, 0.0f, null); // yaw 0 faces +z
		assertEquals(front, pick);
	}

	@Test
	void chooseNextPrefersNearLastWork() {
		var near = tgt(6, 64, 0, Phase.STRUCTURE);
		var far = tgt(-6, 64, 0, Phase.STRUCTURE);
		var eye = new net.minecraft.world.phys.Vec3(0.5, 65.62, 0.5);
		var pick = Planner.chooseNext(java.util.List.of(far, near), eye,
				0.0f, 0.0f, p(5, 64, 0));
		assertEquals(near, pick);
	}

	@Test
	void chooseNextMomentumPrefersContinuation() {
		// Working east: the cell ahead must beat the mirror cell behind
		// even though distance, yaw-error and work-bias are identical.
		var ahead = tgt(3, 64, 0, Phase.STRUCTURE);
		var behind = tgt(-3, 64, 0, Phase.STRUCTURE);
		var eye = new net.minecraft.world.phys.Vec3(0.5, 65.62, 0.5);
		var pick = Planner.chooseNext(java.util.List.of(behind, ahead), eye,
				0.0f, 0.0f, p(0, 64, 0), p(-2, 64, 0));
		assertEquals(ahead, pick);
	}

	@Test
	void chooseNextMomentumFlipsWhenNothingAhead() {
		// Once only cells behind remain, they all share the same momentum
		// penalty — the sweep reverses instead of stalling.
		var behind = tgt(-3, 64, 0, Phase.STRUCTURE);
		var eye = new net.minecraft.world.phys.Vec3(0.5, 65.62, 0.5);
		var pick = Planner.chooseNext(java.util.List.of(behind), eye,
				0.0f, 0.0f, p(0, 64, 0), p(2, 64, 0));
		assertEquals(behind, pick);
	}

	@Test
	void scoreCandidateMomentumPenalty() {
		var eye = new net.minecraft.world.phys.Vec3(0.5, 65.62, 0.5);
		var ahead = tgt(3, 64, 0, Phase.STRUCTURE);
		var behind = tgt(-3, 64, 0, Phase.STRUCTURE);
		double sa = Planner.scoreCandidate(ahead, eye, 0.0f, 0.0f,
				p(0, 64, 0), p(-2, 64, 0));
		double sb = Planner.scoreCandidate(behind, eye, 0.0f, 0.0f,
				p(0, 64, 0), p(-2, 64, 0));
		// Reversal pays the full 2*1.2 swing vs straight continuation.
		assertTrue(sb - sa > 2.0, "reversal penalty too small: " + (sb - sa));
		// Without a previous work position the term is absent entirely:
		// continuation scores identically, reversal loses its penalty.
		double saNoPrev = Planner.scoreCandidate(ahead, eye, 0.0f, 0.0f,
				p(0, 64, 0), null);
		double sbNoPrev = Planner.scoreCandidate(behind, eye, 0.0f, 0.0f,
				p(0, 64, 0), null);
		assertEquals(sa, saNoPrev, 1e-9);
		assertEquals(sb - 2.4, sbNoPrev, 1e-9);
	}

	@Test
	void candidateOrderMatchesChooseNextPriority() {
		// The comparator must order like chooseNext: phase, then layer,
		// then score — its first element is exactly chooseNext's pick.
		var pool = new java.util.ArrayList<BuildTarget>();
		pool.add(tgt(0, 70, 1, Phase.STRUCTURE));
		pool.add(tgt(6, 64, 0, Phase.STRUCTURE));
		pool.add(tgt(-6, 64, 0, Phase.STRUCTURE));
		pool.add(tgt(0, 64, 1, Phase.MECHANISMS));
		var eye = new net.minecraft.world.phys.Vec3(0.5, 65.62, 0.5);
		pool.sort(Planner.candidateOrder(eye, 0.0f, 0.0f, null, null));
		assertEquals(Planner.chooseNext(pool, eye, 0.0f, 0.0f, null),
				pool.get(0));
	}

	@Test
	void chooseNextPhaseAndLayerWin() {
		// A later phase right in front must lose to an earlier phase behind.
		var early = tgt(0, 64, -8, Phase.STRUCTURE);
		var late = tgt(0, 64, 1, Phase.MECHANISMS);
		var eye = new net.minecraft.world.phys.Vec3(0.5, 65.62, 0.5);
		assertEquals(early, Planner.chooseNext(
				java.util.List.of(late, early), eye, 0.0f, 0.0f, null));
		// Within a phase the lower layer wins even across the map.
		var low = tgt(0, 60, -8, Phase.STRUCTURE);
		var high = tgt(0, 70, 1, Phase.STRUCTURE);
		assertEquals(low, Planner.chooseNext(
				java.util.List.of(high, low), eye, 0.0f, 0.0f, null));
	}
}
