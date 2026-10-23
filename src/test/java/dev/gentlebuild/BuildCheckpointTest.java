package dev.gentlebuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.model.BuildTarget;
import dev.gentlebuild.model.Phase;
import dev.gentlebuild.plan.BuildCheckpoint;
import static org.junit.jupiter.api.Assertions.*;

class BuildCheckpointTest extends McTestBase {

	private static BuildTarget stone(int x, int y) {
		return new BuildTarget(p(x, y, 0), Blocks.STONE.defaultBlockState(), Phase.STRUCTURE);
	}

	private static BuildTarget repeater(int x, int delay) {
		BlockState state = Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.DELAY, delay);
		return new BuildTarget(p(x, 1, 0), state, Phase.MECHANISMS);
	}

	@Test
	void nineStructureCellsNotDue() {
		BuildCheckpoint c = new BuildCheckpoint();
		for (int x = 0; x < 9; x++) {
			c.record(stone(x, 0));
		}
		assertFalse(c.due());
	}

	@Test
	void tenStructureCellsDue() {
		BuildCheckpoint c = new BuildCheckpoint();
		for (int x = 0; x < 10; x++) {
			c.record(stone(x, 0));
		}
		assertTrue(c.due());
		assertEquals(10, c.snapshot().size());
	}

	@Test
	void fourSensitiveCellsNotDue() {
		BuildCheckpoint c = new BuildCheckpoint();
		for (int x = 0; x < 4; x++) {
			c.record(repeater(x, 1));
		}
		assertFalse(c.due());
	}

	@Test
	void fiveSensitiveCellsDue() {
		BuildCheckpoint c = new BuildCheckpoint();
		for (int x = 0; x < 5; x++) {
			c.record(repeater(x, 1));
		}
		assertTrue(c.due());
	}

	@Test
	void mixedBatchBecomesDueAtFive() {
		BuildCheckpoint c = new BuildCheckpoint();
		for (int x = 0; x < 4; x++) {
			c.record(stone(x, 0));
		}
		assertFalse(c.due());
		c.record(repeater(4, 1));
		assertTrue(c.due());
	}

	@Test
	void duplicatePositionUpdatesWithoutCounting() {
		BuildCheckpoint c = new BuildCheckpoint();
		BlockState dry = Blocks.STONE_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.WATERLOGGED, false);
		BlockState wet = dry.setValue(BlockStateProperties.WATERLOGGED, true);
		c.record(new BuildTarget(p(0, 0, 0), dry, Phase.STRUCTURE));
		c.record(new BuildTarget(p(0, 0, 0), wet, Phase.FLUIDS));
		assertEquals(1, c.snapshot().size());
		assertEquals(wet, c.snapshot().get(p(0, 0, 0)));
		assertFalse(c.due());
	}

	@Test
	void phaseChangeFlushesOnlyNonEmptyBatch() {
		BuildCheckpoint c = new BuildCheckpoint();
		assertFalse(c.shouldFlush(Phase.MECHANISMS));
		c.record(stone(0, 0));
		assertFalse(c.shouldFlush(Phase.STRUCTURE));
		assertTrue(c.shouldFlush(Phase.MECHANISMS));
	}

	@Test
	void clearRemovesDueAndPhaseState() {
		BuildCheckpoint c = new BuildCheckpoint();
		c.record(repeater(0, 1));
		c.record(repeater(1, 1));
		c.record(repeater(2, 1));
		c.record(repeater(3, 1));
		c.record(repeater(4, 1));
		assertTrue(c.due());
		c.clear();
		assertTrue(c.isEmpty());
		assertFalse(c.due());
		assertFalse(c.shouldFlush(Phase.STRUCTURE));
		c.record(stone(0, 0));
		assertFalse(c.due(), "sensitivity must not survive clear()");
	}

	@Test
	void customThresholdsApply() {
		// structure=7, sensitive=6: six structure cells are not due yet
		BuildCheckpoint c = new BuildCheckpoint();
		for (int x = 0; x < 6; x++) {
			c.record(stone(x, 0));
		}
		assertFalse(c.due(7, 6));
		c.record(stone(6, 0));
		assertTrue(c.due(7, 6));
		// Defaults unchanged: 7 structure cells are still not due at 10/5.
		assertFalse(c.due());
		BuildCheckpoint c2 = new BuildCheckpoint();
		for (int x = 0; x < 5; x++) {
			c2.record(repeater(x, 1));
		}
		assertFalse(c2.due(7, 6), "5 sensitive cells below custom sensitive limit");
		c2.record(repeater(5, 1));
		assertTrue(c2.due(7, 6));
		// Sensitive limit is capped at the structure limit.
		BuildCheckpoint c3 = new BuildCheckpoint();
		for (int x = 0; x < 5; x++) {
			c3.record(repeater(x, 1));
		}
		assertTrue(c3.due(5, 8));
	}

	@Test
	void snapshotIsImmutable() {
		BuildCheckpoint c = new BuildCheckpoint();
		c.record(stone(0, 0));
		assertThrows(UnsupportedOperationException.class,
				() -> c.snapshot().put(p(9, 9, 9), Blocks.STONE.defaultBlockState()));
	}

	@Test
	void directionalStateStaysLatest() {
		BuildCheckpoint c = new BuildCheckpoint();
		BlockState north = Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		c.record(new BuildTarget(p(0, 1, 0), north, Phase.MECHANISMS));
		BlockState south = north.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
		c.record(new BuildTarget(p(0, 1, 0), south, Phase.MECHANISMS));
		assertEquals(south, c.snapshot().get(p(0, 1, 0)));
	}
}
