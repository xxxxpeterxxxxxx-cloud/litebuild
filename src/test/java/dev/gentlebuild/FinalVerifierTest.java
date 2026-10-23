package dev.gentlebuild;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.VerificationMismatch;
import dev.gentlebuild.plan.FinalVerifier;
import static org.junit.jupiter.api.Assertions.*;

class FinalVerifierTest extends McTestBase {

	private Blueprint blueprint(Map<BlockPos, BlockState> blocks) {
		return new Blueprint("test", Map.copyOf(blocks), java.util.Set.of(), 0, "fp");
	}

	@Test
	void firstBatchMismatchPreventsComplete() {
		// >BATCH cells: a mismatch in the FIRST batch must surface even though
		// later batches match.
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int i = 0; i < FinalVerifier.BATCH + 10; i++) {
			blocks.put(p(i, 0, 0), Blocks.STONE.defaultBlockState());
		}
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		world.set(p(3, 0, 0), Blocks.DIRT.defaultBlockState()); // mismatch early
		FinalVerifier v = new FinalVerifier(bp);
		var status = v.tick(world, pos -> false, 0);
		if (status == FinalVerifier.Status.SWEEPING) {
			status = v.tick(world, pos -> false, 0);
		}
		assertEquals(FinalVerifier.Status.MISMATCH, status);
		assertFalse(v.differences().isEmpty());
		assertEquals(1, v.mismatchCount());
		assertEquals(VerificationMismatch.Kind.WRONG_BLOCK,
				v.differences().get(0).kind());
	}

	@Test
	void cleanWorldCompletesAfterTwoPasses() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 1, 0), Blocks.AIR.defaultBlockState()); // required air
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		FinalVerifier v = new FinalVerifier(bp);
		FinalVerifier.Status status = null;
		for (int i = 0; i < 40; i++) {
			status = v.tick(world, pos -> false, 2);
			if (status == FinalVerifier.Status.MISMATCH
					|| status == FinalVerifier.Status.UNLOADED) {
				break;
			}
		}
		assertEquals(FinalVerifier.Status.COMPLETE, status);
		assertEquals(0, v.mismatchCount());
	}

	@Test
	void requiredAirMismatchFails() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 1, 0), Blocks.AIR.defaultBlockState());
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		world.set(p(0, 1, 0), Blocks.DIRT.defaultBlockState());
		FinalVerifier v = new FinalVerifier(bp);
		assertEquals(FinalVerifier.Status.MISMATCH, v.tick(world, pos -> false, 0));
		assertEquals(VerificationMismatch.Kind.EXTRA_BLOCK,
				v.differences().get(0).kind());
	}

	@Test
	void ownedSupportIsExplicitTempSupportMismatch() {
		// A live owned support inside a required-AIR cell is reported as
		// TEMP_SUPPORT — it must be removed or the audit cannot complete.
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 1, 0), Blocks.AIR.defaultBlockState());
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		world.set(p(0, 1, 0), Blocks.DIRT.defaultBlockState()); // support block
		FinalVerifier v = new FinalVerifier(bp);
		FinalVerifier.Status status = v.tick(world, p(0, 1, 0)::equals, 0);
		assertEquals(FinalVerifier.Status.MISMATCH, status);
		assertEquals(VerificationMismatch.Kind.TEMP_SUPPORT,
				v.differences().get(0).kind());
	}

	@Test
	void wrongFacingIsTypedAndIncludesStates() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 4));
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 1));
		FinalVerifier v = new FinalVerifier(bp);
		assertEquals(FinalVerifier.Status.MISMATCH, v.tick(world, pos -> false, 0));
		var m = v.differences().get(0);
		assertEquals(VerificationMismatch.Kind.WRONG_STATE, m.kind());
		assertEquals(4, m.expected().getValue(BlockStateProperties.DELAY));
		assertEquals(1, m.actual().getValue(BlockStateProperties.DELAY));
		assertTrue(v.mismatches().get(0).contains("delay"));
	}

	@Test
	void secondPassMutationStillCaught() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(1, 0, 0), Blocks.STONE.defaultBlockState());
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		FinalVerifier v = new FinalVerifier(bp);
		assertEquals(FinalVerifier.Status.SETTLING,
				v.tick(world, pos -> false, 1)); // pass 1 clean, settle 1
		world.set(p(1, 0, 0), Blocks.DIRT.defaultBlockState()); // mutate
		FinalVerifier.Status status = null;
		for (int i = 0; i < 10; i++) {
			status = v.tick(world, pos -> false, 1);
			if (status != FinalVerifier.Status.SETTLING
					&& status != FinalVerifier.Status.SWEEPING) {
				break;
			}
		}
		assertEquals(FinalVerifier.Status.MISMATCH, status);
	}

	@Test
	void unloadedCellNeverCompletes() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		world.defaultLoaded = false;
		FinalVerifier v = new FinalVerifier(bp);
		assertEquals(FinalVerifier.Status.UNLOADED, v.tick(world, pos -> false, 0));
		assertNull(v.differences().get(0).actual());
		assertNotEquals(FinalVerifier.Status.COMPLETE, v.tick(world, pos -> false, 0));
	}

	@Test
	void detailCapButAccurateTotal() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		for (int i = 0; i < FinalVerifier.MAX_MISMATCHES + 50; i++) {
			blocks.put(p(i, 0, 0), Blocks.STONE.defaultBlockState());
		}
		Blueprint bp = blueprint(blocks);
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		for (int i = 0; i < FinalVerifier.MAX_MISMATCHES + 50; i++) {
			world.set(p(i, 0, 0), Blocks.DIRT.defaultBlockState());
		}
		FinalVerifier v = new FinalVerifier(bp);
		FinalVerifier.Status status = null;
		for (int i = 0; i < 8; i++) {
			status = v.tick(world, pos -> false, 0);
			if (status == FinalVerifier.Status.MISMATCH) {
				break;
			}
		}
		assertEquals(FinalVerifier.Status.MISMATCH, status);
		assertEquals(FinalVerifier.MAX_MISMATCHES, v.differences().size());
		assertEquals(FinalVerifier.MAX_MISMATCHES + 50, v.mismatchCount());
		assertTrue(v.mismatches().get(v.mismatches().size() - 1)
				.contains("more"));
	}

	// ---- construction checkpoints ----

	private static FinalVerifier.Status sweep(FinalVerifier v, MapWorld world) {
		FinalVerifier.Status status = null;
		for (int i = 0; i < 40; i++) {
			status = v.tick(world, pos -> false, 2);
			if (status == FinalVerifier.Status.MISMATCH
					|| status == FinalVerifier.Status.UNLOADED
					|| status == FinalVerifier.Status.COMPLETE) {
				break;
			}
		}
		return status;
	}

	private static BlockState stair(StairsShape shape) {
		return Blocks.STONE_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.STAIRS_SHAPE, shape);
	}

	@Test
	void constructionIgnoresUnbuiltFutureCells() {
		Map<BlockPos, BlockState> scope = new HashMap<>();
		scope.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		world.set(p(1, 0, 0), Blocks.DIRT.defaultBlockState()); // future cell, outside scope
		FinalVerifier v = new FinalVerifier(blueprint(scope),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.COMPLETE, sweep(v, world));
		assertEquals(0, v.mismatchCount());
		assertEquals(1, v.totalCells());
	}

	@Test
	void correctDryWaterloggedStageDoesNotDemandWetFinal() {
		BlockState dry = Blocks.STONE_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
				.setValue(BlockStateProperties.WATERLOGGED, false);
		BlockState wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), dry);
		Map<BlockPos, BlockState> dryScope = new HashMap<>();
		dryScope.put(p(0, 0, 0), dry);
		FinalVerifier construction = new FinalVerifier(blueprint(dryScope),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.COMPLETE, sweep(construction, world));

		Map<BlockPos, BlockState> wetFinal = new HashMap<>();
		wetFinal.put(p(0, 0, 0), wet);
		FinalVerifier strict = new FinalVerifier(blueprint(wetFinal));
		assertEquals(FinalVerifier.Status.MISMATCH, sweep(strict, world));
	}

	@Test
	void pendingStairConnectionWaivedOnlyInConstruction() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), stair(StairsShape.STRAIGHT));
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), stair(StairsShape.INNER_LEFT)); // neighbor not yet built

		FinalVerifier construction = new FinalVerifier(blueprint(blocks),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.COMPLETE, sweep(construction, world));

		FinalVerifier strict = new FinalVerifier(blueprint(blocks));
		assertEquals(FinalVerifier.Status.MISMATCH, sweep(strict, world));
		assertEquals(VerificationMismatch.Kind.WRONG_STATE,
				strict.differences().get(0).kind());
	}

	@Test
	void constructionCatchesWrongIntrinsicStateAndMissingCell() {
		Map<BlockPos, BlockState> delay = new HashMap<>();
		delay.put(p(0, 0, 0), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 4));
		MapWorld world = new MapWorld();
		world.set(p(0, 0, 0), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 1));
		FinalVerifier v = new FinalVerifier(blueprint(delay),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.MISMATCH, sweep(v, world));
		assertEquals(VerificationMismatch.Kind.WRONG_STATE,
				v.differences().get(0).kind());
		assertTrue(v.mismatches().get(0).contains("delay=4")
				&& v.mismatches().get(0).contains("delay=1"));

		Map<BlockPos, BlockState> stone = new HashMap<>();
		stone.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		FinalVerifier missing = new FinalVerifier(blueprint(stone),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.MISMATCH, sweep(missing, new MapWorld()));
		assertEquals(VerificationMismatch.Kind.MISSING,
				missing.differences().get(0).kind());
	}

	@Test
	void constructionUnloadedNeverReadAsAir() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		MapWorld world = new MapWorld();
		world.defaultLoaded = false;
		FinalVerifier v = new FinalVerifier(blueprint(blocks),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.UNLOADED, v.tick(world, pos -> false, 0));
		assertEquals(VerificationMismatch.Kind.UNLOADED,
				v.differences().get(0).kind());
		assertNull(v.differences().get(0).actual());
	}

	@Test
	void constructionNeedsTwoCleanPassesAndSecondPassMutationCaught() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(1, 0, 0), Blocks.STONE.defaultBlockState());
		MapWorld world = new MapWorld();
		blocks.forEach(world::set);
		FinalVerifier clean = new FinalVerifier(blueprint(blocks),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.COMPLETE, sweep(clean, world));

		FinalVerifier mutated = new FinalVerifier(blueprint(blocks),
				FinalVerifier.Mode.CONSTRUCTION);
		assertEquals(FinalVerifier.Status.SETTLING,
				mutated.tick(world, pos -> false, 1));
		world.set(p(1, 0, 0), Blocks.DIRT.defaultBlockState());
		FinalVerifier.Status status = null;
		for (int i = 0; i < 10; i++) {
			status = mutated.tick(world, pos -> false, 1);
			if (status != FinalVerifier.Status.SETTLING
					&& status != FinalVerifier.Status.SWEEPING) {
				break;
			}
		}
		assertEquals(FinalVerifier.Status.MISMATCH, status);
	}
}
