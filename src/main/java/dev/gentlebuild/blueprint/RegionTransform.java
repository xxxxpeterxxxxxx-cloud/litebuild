package dev.gentlebuild.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.PositionUtils;

/**
 * Pure transform spec for one sub-region, equivalent to upstream
 * SchematicPlacingUtils semantics (signed sizes, sub-mirror axis swap on
 * 90-degree placement rotation, state mirror->mirror->rotate order).
 * Separated from the Litematica objects so the exact code path is unit-testable.
 */
public record RegionTransform(BlockPos regionPos, Vec3i signedSize, BlockPos origin,
							  Mirror placementMirror, Rotation placementRotation,
							  Mirror subMirror, Rotation subRotation) {

	public static RegionTransform of(SchematicPlacement placement, SubRegionPlacement sub,
									 Vec3i signedSize) {
		return new RegionTransform(sub.getPos(), signedSize, placement.getOrigin(),
				placement.getMirror(), placement.getRotation(),
				sub.getMirror(), sub.getRotation());
	}

	/** World position of container cell (x, y, z). */
	public BlockPos worldPos(int x, int y, int z) {
		BlockPos end = new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(signedSize))
				.offset(regionPos);
		BlockPos min = PositionUtils.getMinCorner(regionPos, end);
		BlockPos local = new BlockPos(x, y, z).offset(min.subtract(regionPos));
		BlockPos pos = local;
		pos = PositionUtils.getTransformedBlockPos(pos, placementMirror, placementRotation);
		pos = PositionUtils.getTransformedBlockPos(pos, subMirror, subRotation);
		return pos
				.offset(PositionUtils.getTransformedBlockPos(regionPos, placementMirror, placementRotation))
				.offset(origin);
	}

	/** Block state transform: placement mirror, sub mirror (axis-swapped on 90 rot), rotation. */
	public BlockState transformState(BlockState original) {
		Rotation rotation = placementRotation.getRotated(subRotation);
		Mirror mirror = subMirror;
		if (mirror != Mirror.NONE
				&& (placementRotation == Rotation.CLOCKWISE_90
				|| placementRotation == Rotation.COUNTERCLOCKWISE_90)) {
			mirror = mirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
		}
		BlockState state = original;
		if (placementMirror != Mirror.NONE) {
			state = state.mirror(placementMirror);
		}
		if (mirror != Mirror.NONE) {
			state = state.mirror(mirror);
		}
		if (rotation != Rotation.NONE) {
			state = state.rotate(rotation);
		}
		return state;
	}
}
