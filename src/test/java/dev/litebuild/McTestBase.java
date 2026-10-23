package dev.litebuild;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import dev.litebuild.nav.NavWorld;

public abstract class McTestBase {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** Simple map-backed test world; absent = air, isLoaded controllable. */
	public static class MapWorld implements NavWorld, net.minecraft.world.level.BlockGetter {
		public final Map<BlockPos, BlockState> map = new HashMap<>();
		public boolean defaultLoaded = true;

		public MapWorld set(BlockPos pos, BlockState state) {
			map.put(pos.immutable(), state);
			return this;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return map.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			return defaultLoaded;
		}

		@Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}
		@Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}
		@Override public int getHeight() { return 384; }
		@Override public int getMinY() { return -64; }
	}

	public static BlockPos p(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}
}
