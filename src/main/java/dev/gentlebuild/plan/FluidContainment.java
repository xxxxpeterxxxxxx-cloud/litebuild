package dev.gentlebuild.plan;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.LavaFluid;
import dev.gentlebuild.nav.NavWorld;

/**
 * Conservative fluid containment. The blueprint only defines the ALLOWED
 * fluid volume; every boundary face is validated against the live world —
 * a blueprinted wall that is not actually built does not seal.
 */
public final class FluidContainment {
	private static final int MAX_REGION = 4096;
	private static final int LAVA_FLAMMABLE_MARGIN = 3;

	public record Check(boolean sealed, BlockPos leakAt, String reason) {
		public static Check ok() {
			return new Check(true, null, null);
		}
		public static Check leak(BlockPos pos, String reason) {
			return new Check(false, pos, reason);
		}
	}

	/**
	 * @param world    live world view (load state + actual blocks)
	 * @param expected blueprint lookup: expected state or null when outside
	 * @param source   the source cell about to be filled
	 * @param fluid    the fluid the player will place (water/lava)
	 */
	public static Check verify(NavWorld world,
							   java.util.function.Function<BlockPos, BlockState> expected,
							   BlockPos source, Fluid fluid) {
		if (!world.isLoaded(source)) {
			return Check.leak(source, "Quellzelle nicht geladen");
		}
		// Flood fill the connected expected-fluid region of the same type.
		Set<BlockPos> region = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(source);
		region.add(source);
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			for (Direction dir : Direction.values()) {
				BlockPos next = pos.relative(dir);
				if (region.contains(next)) {
					continue;
				}
				BlockState exp = expected.apply(next);
				if (exp != null && isSameFluidExpected(exp, fluid)) {
					region.add(next);
					queue.add(next);
					if (region.size() > MAX_REGION) {
						return Check.leak(next, "Fluidregion zu gross (>4096 Zellen)");
					}
				}
			}
		}

		// Boundary: every DOWN/N/E/S/W neighbor of every region cell.
		for (BlockPos pos : region) {
			Check cellCheck = validateRegionCell(world, expected.apply(pos), pos, fluid);
			if (!cellCheck.sealed()) {
				return cellCheck;
			}
			for (Direction dir : Direction.values()) {
				if (dir == Direction.UP) {
					continue; // fluids never flow upward
				}
				BlockPos next = pos.relative(dir);
				if (!world.isLoaded(next)) {
					return Check.leak(next, "Ungeladener Chunk am Fluidrand");
				}
				if (region.contains(next)) {
					continue;
				}
				BlockState actual = world.getBlockState(next);
				if (!sealsFace(actual, world, next, dir.getOpposite())) {
					return Check.leak(next,
							"Rand blockiert Fluss nicht: " + actual.getBlock());
				}
				if (fluid instanceof LavaFluid && isFlammable(actual)) {
					return Check.leak(next, "Lava direkt neben brennbarem Block");
				}
			}
		}

		// Lava margin: no flammable within 3 blocks of any region cell.
		if (fluid instanceof LavaFluid) {
			for (BlockPos pos : region) {
				for (int dx = -LAVA_FLAMMABLE_MARGIN; dx <= LAVA_FLAMMABLE_MARGIN; dx++) {
					for (int dy = -1; dy <= LAVA_FLAMMABLE_MARGIN; dy++) {
						for (int dz = -LAVA_FLAMMABLE_MARGIN; dz <= LAVA_FLAMMABLE_MARGIN; dz++) {
							BlockPos n = pos.offset(dx, dy, dz);
							if (region.contains(n)) {
								continue;
							}
							if (!world.isLoaded(n)) {
								return Check.leak(n, "Ungeladen am Lava-Rand");
							}
							if (isFlammable(world.getBlockState(n))) {
								return Check.leak(n, "Brennbarer Block nahe Lava");
							}
						}
					}
				}
			}
		}
		return Check.ok();
	}

	private static boolean isSameFluidExpected(BlockState expected, Fluid fluid) {
		if (expected.getBlock() instanceof LiquidBlock) {
			return expected.getFluidState().getType().isSame(fluid);
		}
		if (expected.hasProperty(BlockStateProperties.WATERLOGGED)
				&& expected.getValue(BlockStateProperties.WATERLOGGED)) {
			return fluid == Fluids.WATER;
		}
		return false;
	}

	private static boolean sealsFace(BlockState state, NavWorld world,
									 BlockPos pos, Direction face) {
		if (state.isAir()) {
			return false;
		}
		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty() || state.getBlock() instanceof LiquidBlock) {
			return false; // fluids do not seal
		}
		if (world instanceof net.minecraft.world.level.BlockGetter g) {
			return state.isFaceSturdy(g, pos, face);
		}
		return state.canOcclude(); // conservative fallback
	}

	private static Check validateRegionCell(NavWorld world, BlockState expected,
											BlockPos pos, Fluid fluid) {
		if (!world.isLoaded(pos)) {
			return Check.leak(pos, "Fluidzelle nicht geladen");
		}
		BlockState live = world.getBlockState(pos);
		if (isWaterloggedSolid(expected)) {
			return isDryShellMatch(expected, live) ? Check.ok()
					: Check.leak(pos, "Trockene Huelle fehlt oder stimmt nicht");
		}
		if (!live.getFluidState().isEmpty()) {
			return live.getBlock() instanceof LiquidBlock
					&& live.getFluidState().getType().isSame(fluid)
					? Check.ok() : Check.leak(pos, "Fremdes Fluid in Fluidzelle");
		}
		return live.isAir() || live.canBeReplaced() ? Check.ok()
				: Check.leak(pos, "Fluidzelle durch Block belegt");
	}

	private static boolean isFlammable(BlockState state) {
		return state.ignitedByLava();
	}

	private static boolean isWaterloggedSolid(BlockState expected) {
		return expected != null && !(expected.getBlock() instanceof LiquidBlock)
				&& expected.hasProperty(BlockStateProperties.WATERLOGGED)
				&& expected.getValue(BlockStateProperties.WATERLOGGED);
	}

	/** Live cell is the dry shell of a blueprint waterlogged block. */
	private static boolean isDryShellMatch(BlockState expected, BlockState live) {
		if (expected == null || expected.getBlock() instanceof LiquidBlock
				|| !expected.hasProperty(BlockStateProperties.WATERLOGGED)
				|| !expected.getValue(BlockStateProperties.WATERLOGGED)
				|| live.getBlock() != expected.getBlock()) {
			return false;
		}
		BlockState dry = expected.setValue(BlockStateProperties.WATERLOGGED, false);
		return dev.gentlebuild.state.StateRules.constructionEquivalent(dry, live)
				|| dev.gentlebuild.state.StateRules.constructionEquivalent(expected, live);
	}
}
