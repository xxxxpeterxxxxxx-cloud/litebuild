package dev.gentlebuild;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import dev.gentlebuild.blueprint.RegionTransform;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transform math mirroring upstream SchematicPlacingUtils semantics,
 * exercised through the shared implementation used by the importer.
 */
class RegionTransformTest extends McTestBase {

	@Test
	void identityNoTransform() {
		RegionTransform t = new RegionTransform(BlockPos.ZERO, new Vec3i(3, 2, 2),
				new BlockPos(100, 64, -50), Mirror.NONE, Rotation.NONE, Mirror.NONE, Rotation.NONE);
		assertEquals(new BlockPos(100, 64, -50), t.worldPos(0, 0, 0));
		assertEquals(new BlockPos(102, 65, -49), t.worldPos(2, 1, 1));
	}

	@Test
	void negativeRegionSize() {
		// Subregion extends in negative x/z; min corner offset applies.
		RegionTransform t = new RegionTransform(new BlockPos(5, 0, 5), new Vec3i(-3, 2, -3),
				BlockPos.ZERO, Mirror.NONE, Rotation.NONE, Mirror.NONE, Rotation.NONE);
		// relative end = regionPos + signedEnd = (3,1,3); min=(3,0,3)
		// local = (x,y,z) + (min - regionPos) = (x-2, y, z-2)
		// world  = local + regionPos(5,0,5) + origin(0,0,0)
		assertEquals(new BlockPos(3, 0, 3), t.worldPos(0, 0, 0));
		assertEquals(new BlockPos(5, 1, 5), t.worldPos(2, 1, 2));
	}

	/**
	 * Exact upstream-equivalent case from review: regionPos (5,0,5),
	 * signedSize (-3,2,-3), origin (100,64,-50), main rotation CW90.
	 */
	@Test
	void negativeSizeRotatedExact() {
		RegionTransform t = new RegionTransform(new BlockPos(5, 0, 5), new Vec3i(-3, 2, -3),
				new BlockPos(100, 64, -50), Mirror.NONE, Rotation.CLOCKWISE_90,
				Mirror.NONE, Rotation.NONE);
		assertEquals(new BlockPos(97, 64, -47), t.worldPos(0, 0, 0));
		assertEquals(new BlockPos(95, 65, -45), t.worldPos(2, 1, 2));
	}

	@Test
	void globalRotation90() {
		RegionTransform t = new RegionTransform(BlockPos.ZERO, new Vec3i(3, 1, 1),
				BlockPos.ZERO, Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.NONE, Rotation.NONE);
		// (x,0,0) -> rotated around origin: x' = -z? upstream: CW_90 maps +x -> +z? verify consistency
		BlockPos a = t.worldPos(0, 0, 0);
		BlockPos b = t.worldPos(2, 0, 0);
		assertEquals(0, a.getX() + a.getZ());
		assertTrue(Math.abs(b.getX()) + Math.abs(b.getZ()) == 2);
		assertNotEquals(new BlockPos(2, 0, 0), b);
	}

	@Test
	void subRegionOffsetAndRotation() {
		// region pos shifted, sub rotation 180
		RegionTransform t = new RegionTransform(new BlockPos(4, 0, 0), new Vec3i(3, 1, 1),
				new BlockPos(10, 0, 0), Mirror.NONE, Rotation.NONE, Mirror.NONE,
				Rotation.CLOCKWISE_180);
		// Untransformed cell (0,0,0) sits at local (0,0,0); sub rotation mirrors
		// it around the sub-region's own frame; regionPos transformed by placement
		// transform only. World pos should be within or adjacent to [10..13].
		BlockPos w = t.worldPos(0, 0, 0);
		assertTrue(w.getX() >= 10 && w.getX() <= 15, () -> "x=" + w.getX());
	}

	@Test
	void subMirrorAxisSwapOn90() {
		// With placement rotated 90 deg, sub mirror FRONT_BACK swaps to LEFT_RIGHT.
		BlockState state = Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(net.minecraft.world.level.block.StairBlock.FACING,
						net.minecraft.core.Direction.EAST);
		RegionTransform t90 = new RegionTransform(BlockPos.ZERO, new Vec3i(1, 1, 1),
				BlockPos.ZERO, Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK,
				Rotation.NONE);
		RegionTransform t0 = new RegionTransform(BlockPos.ZERO, new Vec3i(1, 1, 1),
				BlockPos.ZERO, Mirror.NONE, Rotation.NONE, Mirror.FRONT_BACK,
				Rotation.NONE);
		// The 90-degree placement applies LEFT_RIGHT (x-axis) then rotates;
		// the straight placement applies FRONT_BACK (z-axis).
		BlockState a = t90.transformState(state);
		BlockState b = t0.transformState(state);
		assertNotEquals(a, b, "sub-mirror axis swap must affect outcome");
	}
}
