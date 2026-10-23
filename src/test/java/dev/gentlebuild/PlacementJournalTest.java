package dev.gentlebuild;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.build.PlacementJournal;
import static org.junit.jupiter.api.Assertions.*;

class PlacementJournalTest extends McTestBase {

	private static BlockState slab(SlabType type) {
		return Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE, type);
	}

	@Test
	void airToBottomHalfIsRecorded() {
		var j = new PlacementJournal();
		j.recordConfirmed(p(0, 0, 0), Blocks.AIR.defaultBlockState(), slab(SlabType.BOTTOM));
		assertTrue(j.owns(p(0, 0, 0)));
		assertEquals(slab(SlabType.BOTTOM), j.ownedState(p(0, 0, 0)));
	}

	@Test
	void secondSameBlockPlacementRefreshesOwnedStateOnly() {
		var j = new PlacementJournal();
		BlockState bottom = slab(SlabType.BOTTOM);
		BlockState full = slab(SlabType.DOUBLE);
		j.recordConfirmed(p(0, 0, 0), Blocks.AIR.defaultBlockState(), bottom);
		// Second placement: pre-state is the half slab, so recordConfirmed is a no-op...
		j.recordConfirmed(p(0, 0, 0), bottom, full);
		assertEquals(bottom, j.ownedState(p(0, 0, 0)));
		// ...and the owned record is refreshed to the authoritative double slab.
		j.touchOwned(p(0, 0, 0), full);
		assertEquals(full, j.ownedState(p(0, 0, 0)));
	}

	@Test
	void preexistingUnownedHalfNeverBecomesOwnedByTouch() {
		var j = new PlacementJournal();
		j.touchOwned(p(1, 0, 0), slab(SlabType.DOUBLE));
		assertFalse(j.owns(p(1, 0, 0)));
		j.recordConfirmed(p(1, 0, 0), slab(SlabType.BOTTOM), slab(SlabType.DOUBLE));
		assertFalse(j.owns(p(1, 0, 0)), "a non-air pre-state is never claimed");
	}

	@Test
	void changedLiveWorldIsNotStillOurs() {
		var j = new PlacementJournal();
		MapWorld world = new MapWorld();
		world.set(p(2, 0, 0), slab(SlabType.BOTTOM));
		j.recordConfirmed(p(2, 0, 0), Blocks.AIR.defaultBlockState(), slab(SlabType.BOTTOM));
		assertTrue(j.stillOurs(world, p(2, 0, 0)));
		world.set(p(2, 0, 0), Blocks.DIRT.defaultBlockState());
		assertFalse(j.stillOurs(world, p(2, 0, 0)));
	}
}
