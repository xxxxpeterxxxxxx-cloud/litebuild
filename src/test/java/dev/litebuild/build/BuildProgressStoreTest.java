package dev.litebuild.build;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import dev.litebuild.McTestBase;
import dev.litebuild.model.Blueprint;
import dev.litebuild.plan.Planner;

class BuildProgressStoreTest extends McTestBase {

	private static Map<BlockPos, BlockState> cells() {
		Map<BlockPos, BlockState> m = new HashMap<>();
		m.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(1, 0, 0), Blocks.FURNACE.defaultBlockState()
				.setValue(FurnaceBlock.FACING, Direction.NORTH));
		m.put(new BlockPos(0, 0, 1), Blocks.DIRT.defaultBlockState());
		return m;
	}

	private static Blueprint blueprint() {
		return new Blueprint("test", cells(), Set.of(), 0, "fp");
	}

	// ---------------- fingerprint ----------------

	@Test
	void fingerprintIsStableAndInsertionOrderIndependent() {
		String a = BuildProgressStore.contentFingerprint(cells());
		assertEquals(a, BuildProgressStore.contentFingerprint(cells()));

		Map<BlockPos, BlockState> reordered = new LinkedHashMap<>();
		reordered.put(new BlockPos(0, 0, 1), Blocks.DIRT.defaultBlockState());
		reordered.put(new BlockPos(1, 0, 0), Blocks.FURNACE.defaultBlockState()
				.setValue(FurnaceBlock.FACING, Direction.NORTH));
		reordered.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		assertEquals(a, BuildProgressStore.contentFingerprint(reordered));
	}

	@Test
	void fingerprintChangesWhenRotated() {
		Map<BlockPos, BlockState> rotated = new HashMap<>();
		for (Map.Entry<BlockPos, BlockState> e : cells().entrySet()) {
			BlockPos p = e.getKey();
			BlockPos rp = new BlockPos(-p.getZ(), p.getY(), p.getX());
			BlockState s = e.getValue();
			if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
				s = s.setValue(BlockStateProperties.HORIZONTAL_FACING,
						s.getValue(BlockStateProperties.HORIZONTAL_FACING)
								.getClockWise());
			}
			rotated.put(rp, s);
		}
		assertNotEquals(BuildProgressStore.contentFingerprint(cells()),
				BuildProgressStore.contentFingerprint(rotated));
	}

	@Test
	void fingerprintChangesOnStateOrPositionDelta() {
		String base = BuildProgressStore.contentFingerprint(cells());

		Map<BlockPos, BlockState> turned = new HashMap<>(cells());
		turned.put(new BlockPos(1, 0, 0), Blocks.FURNACE.defaultBlockState()
				.setValue(FurnaceBlock.FACING, Direction.EAST));
		assertNotEquals(base, BuildProgressStore.contentFingerprint(turned));

		Map<BlockPos, BlockState> moved = new HashMap<>();
		for (Map.Entry<BlockPos, BlockState> e : cells().entrySet()) {
			moved.put(e.getKey().east(10), e.getValue());
		}
		assertNotEquals(base, BuildProgressStore.contentFingerprint(moved));
	}

	// ---------------- record round-trip ----------------

	@Test
	void recordSurvivesStoreReload(@TempDir Path dir) {
		Blueprint bp = blueprint();
		BlockPos origin = new BlockPos(0, 64, 0);
		BuildProgressStore store = new BuildProgressStore(dir);
		assertEquals(0, store.begin("world/test-level", "minecraft:overworld",
				origin, "region-a", bp));
		store.noteVerified(new BlockPos(0, 0, 0));
		store.noteVerified(new BlockPos(1, 0, 0));
		store.flush();
		store.endSession();

		BuildProgressStore reloaded = new BuildProgressStore(dir);
		assertEquals(2, reloaded.begin("world/test-level", "minecraft:overworld",
				origin, "region-a", bp));
		assertEquals(2, reloaded.storedVerifiedCount());
	}

	@Test
	void mismatchedRecordIsDiscarded(@TempDir Path dir) {
		Blueprint bp = blueprint();
		BlockPos origin = new BlockPos(0, 64, 0);
		BuildProgressStore store = new BuildProgressStore(dir);
		assertEquals(0, store.begin("world/test-level", "minecraft:overworld",
				origin, "region-a", bp));
		store.noteVerified(new BlockPos(0, 0, 0));
		store.flush();
		store.endSession();

		// Different world id: a separate file — nothing offered.
		assertEquals(0, new BuildProgressStore(dir).begin("world/other",
				"minecraft:overworld", origin, "region-a", bp));
		// Different dimension with the same schematic: discarded.
		assertEquals(0, new BuildProgressStore(dir).begin("world/test-level",
				"minecraft:the_nether", origin, "region-a", bp));
		// The previous discard deleted the file: even the matching record is gone.
		assertEquals(0, new BuildProgressStore(dir).begin("world/test-level",
				"minecraft:overworld", origin, "region-a", bp));

		// Different schematic content in the same world: discarded.
		Blueprint other = new Blueprint("test",
				Map.of(new BlockPos(9, 9, 9), Blocks.STONE.defaultBlockState()),
				Set.of(), 0, "fp");
		BuildProgressStore s4 = new BuildProgressStore(dir);
		s4.begin("world/test-level", "minecraft:overworld", origin, "region-a", bp);
		s4.noteVerified(new BlockPos(0, 0, 0));
		s4.flush();
		s4.endSession();
		assertEquals(0, new BuildProgressStore(dir).begin("world/test-level",
				"minecraft:overworld", origin, "region-a", other));
	}

	// ---------------- revalidation queueing ----------------

	@Test
	void applyVerifiedMarksOnlyLiveMatches() {
		Blueprint bp = blueprint();
		Planner planner = new Planner(bp);

		Map<BlockPos, BlockState> live = new HashMap<>();
		live.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		live.put(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState()); // wrong block
		live.put(new BlockPos(0, 0, 1), Blocks.DIRT.defaultBlockState());

		int applied = BuildProgressStore.applyVerified(
				Set.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
						new BlockPos(0, 0, 1), new BlockPos(7, 7, 7)),
				planner, bp,
				pos -> live.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
				true, true);

		assertEquals(2, applied);
		assertTrue(planner.isVerified(new BlockPos(0, 0, 0)));
		assertTrue(planner.isVerified(new BlockPos(0, 0, 1)));
		// Mismatched and non-blueprint cells stay unverified → re-queued.
		assertFalse(planner.isVerified(new BlockPos(1, 0, 0)));
		assertFalse(planner.isComplete());
	}

	/** Stored fluid/activation cells must stay pending while their phase
	 * is disabled — restored progress must never bypass phase gating. */
	@Test
	void applyVerifiedRespectsPhaseAuthorization() {
		BlockPos waterPos = new BlockPos(2, 0, 0);
		Map<BlockPos, BlockState> cells = cells();
		cells.put(waterPos, Blocks.WATER.defaultBlockState());
		Blueprint bp = new Blueprint("test", cells, Set.of(), 0, "fp");
		Planner planner = new Planner(bp);

		Map<BlockPos, BlockState> live = new HashMap<>(cells);
		Set<BlockPos> stored = Set.copyOf(cells.keySet());

		int applied = BuildProgressStore.applyVerified(stored, planner, bp,
				pos -> live.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
				false, true);

		assertEquals(3, applied);
		assertTrue(planner.isVerified(new BlockPos(0, 0, 0)));
		assertFalse(planner.isVerified(waterPos));

		// Enabling fluids lets the same stored cell verify on retry.
		BuildProgressStore.applyVerified(stored, planner, bp,
				pos -> live.getOrDefault(pos, Blocks.AIR.defaultBlockState()),
				true, true);
		assertTrue(planner.isVerified(waterPos));
	}
}
