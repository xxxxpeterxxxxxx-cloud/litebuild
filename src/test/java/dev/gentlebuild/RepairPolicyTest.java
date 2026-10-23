package dev.gentlebuild;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.build.PlacementJournal;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.VerificationMismatch;
import dev.gentlebuild.plan.RepairPolicy;
import static dev.gentlebuild.plan.RepairPolicy.Kind;
import static org.junit.jupiter.api.Assertions.*;

class RepairPolicyTest extends McTestBase {

	private Blueprint bp(Map<BlockPos, BlockState> blocks) {
		return new Blueprint("t", Map.copyOf(blocks), java.util.Set.of(), 0, "fp");
	}

	private static VerificationMismatch mm(BlockPos pos, BlockState expected,
										   BlockState actual,
										   VerificationMismatch.Kind kind) {
		return new VerificationMismatch(pos, expected, actual, kind);
	}

	private RepairPolicy.Decision classify(VerificationMismatch m, Blueprint bp,
										   MapWorld w, Map<BlockPos, BlockState> owned,
										   BlockPos feet) {
		return RepairPolicy.classify(m, bp, w, owned, feet, true, true);
	}

	@Test
	void missingStoneFills() {
		var map = new HashMap<BlockPos, BlockState>();
		map.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		var w = new MapWorld();
		var d = classify(mm(p(0, 0, 0), Blocks.STONE.defaultBlockState(),
				Blocks.AIR.defaultBlockState(), VerificationMismatch.Kind.MISSING),
				bp(map), w, Map.of(), p(5, 0, 5));
		assertEquals(Kind.FILL, d.kind());
	}

	@Test
	void repeaterDelayAdjusts() {
		var expected = Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 4);
		var actual = expected.setValue(BlockStateProperties.DELAY, 1);
		var map = Map.of(p(0, 0, 0), expected);
		var w = new MapWorld().set(p(0, 0, 0), actual);
		var d = classify(mm(p(0, 0, 0), expected, actual,
				VerificationMismatch.Kind.WRONG_STATE), bp(map), w, Map.of(),
				p(5, 0, 5));
		assertEquals(Kind.ADJUST, d.kind());
	}

	@Test
	void ownedWrongStairReplacesUnownedIsManual() {
		var expected = Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		var actual = expected.setValue(BlockStateProperties.HORIZONTAL_FACING,
				Direction.SOUTH);
		var map = Map.of(p(0, 0, 0), expected);
		var w = new MapWorld().set(p(0, 0, 0), actual);
		var owned = Map.of(p(0, 0, 0), actual);
		var d = classify(mm(p(0, 0, 0), expected, actual,
				VerificationMismatch.Kind.WRONG_STATE), bp(map), w, owned,
				p(5, 0, 5));
		assertEquals(Kind.REPLACE, d.kind());
		var d2 = classify(mm(p(0, 0, 0), expected, actual,
				VerificationMismatch.Kind.WRONG_STATE), bp(map), w, Map.of(),
				p(5, 0, 5));
		assertEquals(Kind.MANUAL, d2.kind());
	}

	@Test
	void ownedExtraBlockRemovesChangedStateIsManual() {
		var map = Map.of(p(0, 0, 0), Blocks.AIR.defaultBlockState());
		var w = new MapWorld().set(p(0, 0, 0), Blocks.DIRT.defaultBlockState());
		var owned = Map.of(p(0, 0, 0), Blocks.DIRT.defaultBlockState());
		var d = classify(mm(p(0, 0, 0), Blocks.AIR.defaultBlockState(),
				Blocks.DIRT.defaultBlockState(), VerificationMismatch.Kind.EXTRA_BLOCK),
				bp(map), w, owned, p(5, 0, 5));
		assertEquals(Kind.REMOVE, d.kind());
		// Recorded DIRT but live STONE -> externally changed, never break.
		var w2 = new MapWorld().set(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		var d2 = RepairPolicy.classify(mm(p(0, 0, 0),
				Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
				VerificationMismatch.Kind.EXTRA_BLOCK), bp(map), w2, owned,
				p(5, 0, 5), true, true);
		assertEquals(Kind.MANUAL, d2.kind());
	}

	@Test
	void hazardsAreManual() {
		for (var bad : new BlockState[]{
				Blocks.CHEST.defaultBlockState(),      // block entity
				Blocks.TNT.defaultBlockState(),
				Blocks.PISTON.defaultBlockState(),
				}) {
			var map = Map.of(p(0, 0, 0), Blocks.AIR.defaultBlockState());
			var w = new MapWorld().set(p(0, 0, 0), bad);
			var owned = Map.of(p(0, 0, 0), bad);
			var d = classify(mm(p(0, 0, 0), Blocks.AIR.defaultBlockState(), bad,
					VerificationMismatch.Kind.EXTRA_BLOCK), bp(map), w, owned,
					p(5, 0, 5));
			assertEquals(Kind.MANUAL, d.kind(), bad.getBlock().toString());
		}
		// waterlogged shell
		var wet = Blocks.OAK_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.WATERLOGGED, true);
		var map = Map.of(p(0, 0, 0), Blocks.AIR.defaultBlockState());
		var w = new MapWorld().set(p(0, 0, 0), wet);
		var owned = Map.of(p(0, 0, 0), wet);
		var d = classify(mm(p(0, 0, 0), Blocks.AIR.defaultBlockState(), wet,
				VerificationMismatch.Kind.EXTRA_BLOCK), bp(map), w, owned,
				p(5, 0, 5));
		assertEquals(Kind.MANUAL, d.kind());
	}

	@Test
	void neighborGuardsAreManual() {
		var map = Map.of(p(0, 0, 0), Blocks.AIR.defaultBlockState());
		var dirt = Blocks.DIRT.defaultBlockState();
		var owned = Map.of(p(0, 0, 0), dirt);
		var base = mm(p(0, 0, 0), Blocks.AIR.defaultBlockState(), dirt,
				VerificationMismatch.Kind.EXTRA_BLOCK);

		// fluid in Chebyshev-1
		var w1 = new MapWorld().set(p(0, 0, 0), dirt)
				.set(p(1, 0, 0), Blocks.WATER.defaultBlockState());
		assertEquals(Kind.MANUAL, classify(base, bp(map), w1, owned,
				p(5, 0, 5)).kind());
		// redstone wire at Chebyshev distance 2: no update reaches it when
		// the cell is broken — distance alone is not a dependency.
		var w2 = new MapWorld().set(p(0, 0, 0), dirt)
				.set(p(2, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState());
		assertEquals(Kind.REMOVE, classify(base, bp(map), w2, owned,
				p(5, 0, 5)).kind());
		// non-inert neighbor in Chebyshev-1 (no LevelReader -> conservative)
		var w2b = new MapWorld().set(p(0, 0, 0), dirt)
				.set(p(1, 0, 0), Blocks.OAK_DOOR.defaultBlockState());
		assertEquals(Kind.MANUAL, classify(base, bp(map), w2b, owned,
				p(5, 0, 5)).kind());
		// block above
		var w3 = new MapWorld().set(p(0, 0, 0), dirt)
				.set(p(0, 1, 0), Blocks.STONE.defaultBlockState());
		assertEquals(Kind.MANUAL, classify(base, bp(map), w3, owned,
				p(5, 0, 5)).kind());
		// player standing on it
		var w4 = new MapWorld().set(p(0, 0, 0), dirt);
		assertEquals(Kind.MANUAL, classify(base, bp(map), w4, owned,
				p(0, 1, 0)).kind());
	}

	@Test
	void activationGateForLiveControls() {
		var expected = Blocks.LEVER.defaultBlockState()
				.setValue(BlockStateProperties.POWERED, true);
		var actual = Blocks.LEVER.defaultBlockState()
				.setValue(BlockStateProperties.POWERED, false);
		var map = Map.of(p(0, 0, 0), expected);
		var w = new MapWorld().set(p(0, 0, 0), actual);
		var m = mm(p(0, 0, 0), expected, actual,
				VerificationMismatch.Kind.WRONG_STATE);
		assertEquals(Kind.MANUAL,
				RepairPolicy.classify(m, bp(map), w, Map.of(), p(5, 0, 5),
						true, false).kind());
		assertEquals(Kind.ADJUST,
				RepairPolicy.classify(m, bp(map), w, Map.of(), p(5, 0, 5),
						true, true).kind());
	}

	@Test
	void fluidCellsNeedPermissionAndFlowIsManual() {
		var src = Blocks.WATER.defaultBlockState();
		var flow = src.setValue(BlockStateProperties.LEVEL, 1);
		var map = Map.of(p(0, 0, 0), src);
		var w = new MapWorld();
		var m = mm(p(0, 0, 0), src, Blocks.AIR.defaultBlockState(),
				VerificationMismatch.Kind.MISSING);
		assertEquals(Kind.MANUAL,
				RepairPolicy.classify(m, bp(map), w, Map.of(), p(5, 0, 5),
						false, true).kind());
		assertEquals(Kind.FILL,
				RepairPolicy.classify(m, bp(map), w, Map.of(), p(5, 0, 5),
						true, true).kind());
		var mf = mm(p(1, 0, 0), flow, Blocks.AIR.defaultBlockState(),
				VerificationMismatch.Kind.MISSING);
		var mapF = Map.of(p(1, 0, 0), flow);
		assertEquals(Kind.MANUAL,
				RepairPolicy.classify(mf, bp(mapF), w, Map.of(), p(5, 0, 5),
						true, true).kind());
	}

	@Test
	void staleSnapshotRescans() {
		var map = Map.of(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		var w = new MapWorld().set(p(0, 0, 0),
				Blocks.GRANITE.defaultBlockState()); // live != audited actual
		var d = classify(mm(p(0, 0, 0), Blocks.STONE.defaultBlockState(),
				Blocks.AIR.defaultBlockState(), VerificationMismatch.Kind.MISSING),
				bp(map), w, Map.of(), p(5, 0, 5));
		assertEquals(Kind.MANUAL, d.kind());
		assertTrue(d.reason().contains("veraendert"));
	}

	// ---- PlacementJournal ----

	@Test
	void journalRecordsOnlyOwnAirPlacements() {
		var j = new PlacementJournal();
		j.recordConfirmed(p(0, 0, 0), Blocks.DIRT.defaultBlockState(),
				Blocks.STONE.defaultBlockState()); // pre-existing -> no claim
		assertFalse(j.owns(p(0, 0, 0)));
		j.recordConfirmed(p(0, 0, 0), Blocks.AIR.defaultBlockState(),
				Blocks.STONE.defaultBlockState());
		assertTrue(j.owns(p(0, 0, 0)));
		// wrong-orientation ack of the same block is still ours
		j.recordConfirmed(p(1, 0, 0), Blocks.AIR.defaultBlockState(),
				Blocks.OAK_STAIRS.defaultBlockState()
						.setValue(BlockStateProperties.HORIZONTAL_FACING,
								Direction.SOUTH));
		assertTrue(j.owns(p(1, 0, 0)));
		// an unrelated block ack is not claimed
		j.recordConfirmed(p(2, 0, 0), Blocks.AIR.defaultBlockState(),
				Blocks.AIR.defaultBlockState());
		assertFalse(j.owns(p(2, 0, 0)));
		// touch keeps ours in sync only within the same block
		j.touchOwned(p(1, 0, 0), Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
		assertEquals(Direction.EAST, j.ownedState(p(1, 0, 0))
				.getValue(BlockStateProperties.HORIZONTAL_FACING));
		j.touchOwned(p(1, 0, 0), Blocks.DIRT.defaultBlockState());
		assertNotEquals(Blocks.DIRT, j.ownedState(p(1, 0, 0)).getBlock());
		j.remove(p(0, 0, 0));
		assertFalse(j.owns(p(0, 0, 0)));
		j.clear();
		assertFalse(j.owns(p(1, 0, 0)));
	}
}
