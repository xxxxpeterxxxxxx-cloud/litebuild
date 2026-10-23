package dev.gentlebuild;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.plan.FluidContainment;
import static org.junit.jupiter.api.Assertions.*;

class FluidContainmentTest extends McTestBase {

	/** 3x3 basin of stone floor+walls at y=0, water expected at (0,1,0). */
	private Map<BlockPos, BlockState> basin() {
		Map<BlockPos, BlockState> bp = new HashMap<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				bp.put(p(x, 0, z), Blocks.STONE.defaultBlockState());
				// walls ring at y=1 except center
				if (x != 0 || z != 0) {
					bp.put(p(x, 1, z), Blocks.STONE.defaultBlockState());
				}
			}
		}
		bp.put(p(0, 1, 0), Blocks.WATER.defaultBlockState());
		return bp;
	}

	@Test
	void closedBasinSeals() {
		Map<BlockPos, BlockState> bp = basin();
		// world mirrors blueprint
		MapWorld w = new MapWorld();
		bp.forEach(w::set);
		FluidContainment.Check c = FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER);
		assertTrue(c.sealed(), () -> "expected sealed: " + c.reason());
	}

	@Test
	void gapInWallLeaks() {
		Map<BlockPos, BlockState> bp = basin();
		bp.put(p(1, 1, 0), Blocks.AIR.defaultBlockState()); // blueprint air hole
		MapWorld w = new MapWorld();
		bp.forEach(w::set);
		FluidContainment.Check c = FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER);
		assertFalse(c.sealed());
		assertEquals(p(1, 1, 0), c.leakAt());
	}

	@Test
	void missingFloorLeaks() {
		Map<BlockPos, BlockState> bp = basin();
		bp.remove(p(0, 0, 0)); // no blueprint cell under the source -> world air
		MapWorld w = new MapWorld();
		bp.forEach(w::set);
		FluidContainment.Check c = FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER);
		assertFalse(c.sealed());
		assertEquals(p(0, 0, 0), c.leakAt());
	}

	@Test
	void blueprintWallPresentButLiveWallAbsentRejected() {
		// Blueprint keeps the wall; the LIVE world lacks it -> must reject.
		Map<BlockPos, BlockState> bp = basin();
		MapWorld w = new MapWorld();
		bp.forEach(w::set);
		w.map.remove(p(1, 1, 0)); // live wall gone, blueprint still has it
		FluidContainment.Check c = FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER);
		assertFalse(c.sealed(), "missing live wall must fail even with blueprint wall");
		assertEquals(p(1, 1, 0), c.leakAt());
	}

	@Test
	void mixedFluidRejected() {
		Map<BlockPos, BlockState> bp = basin();
		bp.put(p(0, 1, 1), Blocks.LAVA.defaultBlockState());
		bp.put(p(0, 1, 2), Blocks.WATER.defaultBlockState());
		MapWorld w = new MapWorld();
		bp.forEach(w::set);
		FluidContainment.Check c = FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER);
		assertFalse(c.sealed());
	}

	@Test
	void unloadedBoundaryRejected() {
		Map<BlockPos, BlockState> bp = basin();
		MapWorld w = new MapWorld() {
			@Override public boolean isLoaded(BlockPos pos) { return !pos.equals(p(1, 1, 0)); }
		};
		bp.forEach(w::set);
		FluidContainment.Check c = FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER);
		assertFalse(c.sealed());
	}

	@Test
	void lavaNearWoodRejected() {
		Map<BlockPos, BlockState> bp = new HashMap<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				bp.put(p(x, 0, z), Blocks.STONE.defaultBlockState());
				if (x != 0 || z != 0) {
					bp.put(p(x, 1, z), Blocks.OAK_PLANKS.defaultBlockState());
				}
			}
		}
		bp.put(p(0, 1, 0), Blocks.LAVA.defaultBlockState());
		MapWorld w = new MapWorld();
		bp.forEach(w::set);
		FluidContainment.Check c = FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.LAVA);
		assertFalse(c.sealed());
		assertTrue(c.reason().contains("flammable"));
	}

	@Test
	void foreignFluidAtSourceRejected() {
		var bp = basin();
		var w = new MapWorld();
		bp.forEach(w::set);
		w.set(p(0, 1, 0), Blocks.LAVA.defaultBlockState());
		assertFalse(FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER).sealed());
	}

	@Test
	void foreignFluidInsideRegionRejected() {
		var bp = basin();
		bp.put(p(1, 1, 0), Blocks.WATER.defaultBlockState());
		bp.put(p(2, 1, 0), Blocks.STONE.defaultBlockState());
		var w = new MapWorld();
		bp.forEach(w::set);
		w.set(p(1, 1, 0), Blocks.LAVA.defaultBlockState());
		assertFalse(FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER).sealed());
	}

	@Test
	void missingWaterloggedSourceShellRejected() {
		var bp = basin();
		bp.put(p(0, 1, 0), Blocks.OAK_SLAB.defaultBlockState().setValue(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,
				true));
		var w = new MapWorld();
		bp.forEach(w::set);
		w.set(p(0, 1, 0), Blocks.AIR.defaultBlockState());
		assertFalse(FluidContainment.verify(w, bp::get, p(0, 1, 0), Fluids.WATER).sealed());
	}
}
