package dev.litebuild.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/** NavWorld + LevelReader over a real client level. */
public class LevelNavWorld implements NavWorld, LevelReader {
	private final LevelReader level;

	public LevelNavWorld(LevelReader level) {
		this.level = level;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return level.getBlockState(pos);
	}

	@Override
	public boolean isLoaded(BlockPos pos) {
		return level instanceof net.minecraft.world.level.Level l
				? l.isLoaded(pos) : level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}

	@Override public FluidState getFluidState(BlockPos pos) { return level.getFluidState(pos); }
	@Override public BlockEntity getBlockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
	@Override public int getHeight() { return level.getHeight(); }
	@Override public int getMinY() { return level.getMinY(); }
	@Override public ChunkAccess getChunk(int x, int z, ChunkStatus s, boolean create) {
		return level.getChunk(x, z, s, create);
	}
	@Override public boolean hasChunk(int x, int z) { return level.hasChunk(x, z); }
	@Override public int getHeight(Heightmap.Types t, int x, int z) { return level.getHeight(t, x, z); }
	@Override public int getSkyDarken() { return level.getSkyDarken(); }
	@Override public BiomeManager getBiomeManager() { return level.getBiomeManager(); }
	@Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
		return level.getUncachedNoiseBiome(x, y, z);
	}
	@Override public boolean isClientSide() { return true; }
	@Override public int getSeaLevel() { return level.getSeaLevel(); }
	@Override public DimensionType dimensionType() { return level.dimensionType(); }
	@Override public RegistryAccess registryAccess() { return level.registryAccess(); }
	@Override public FeatureFlagSet enabledFeatures() { return level.enabledFeatures(); }
	@Override public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
		return level.environmentAttributes();
	}
	@Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
		return level.getLightEngine();
	}
	@Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
		return level.getWorldBorder();
	}
	@Override public net.minecraft.world.level.BlockGetter getChunkForCollisions(int x, int z) {
		return level.getChunkForCollisions(x, z);
	}
	@Override public java.util.List<net.minecraft.world.phys.shapes.VoxelShape> getEntityCollisions(
			net.minecraft.world.entity.Entity e, net.minecraft.world.phys.AABB box) {
		return level.getEntityCollisions(e, box);
	}
}
