package dev.litebuild.blueprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import dev.litebuild.model.Blueprint;
import dev.litebuild.model.BuildIssue;

public final class BlueprintImporter {
	public static final int MAX_REGION_CELLS = 1_000_000;
	public static final int MAX_NON_AIR = 100_000;
	/** Cells processed per tick by the incremental snapshotter. */
	public static final int CELLS_PER_TICK = 4096;

	private BlueprintImporter() {}

	public record RegionWork(String name,
							 SubRegionPlacement placement,
							 LitematicaBlockStateContainer container,
							 Vec3i signedSize,
							 Vec3i containerSize,
							 Map<BlockPos, CompoundData> blockEntities,
							 int entityCount) {}

	/**
	 * Collects and bounds-checks all enabled sub-regions of the currently
	 * selected placement. Returns null (plus a reason in {@code errors}) when
	 * no usable selection exists.
	 */
	public static List<RegionWork> collectRegions(List<BuildIssue> errors) {
		SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
		SchematicPlacement placement = manager == null ? null : manager.getSelectedSchematicPlacement();
		if (placement == null || !placement.isEnabled()) {
			errors.add(new BuildIssue(BuildIssue.Severity.BLOCKED, BlockPos.ZERO,
					"No active Litematica placement selected"));
			return null;
		}
		LitematicaSchematic schematic = placement.getSchematic();
		if (schematic == null) {
			errors.add(new BuildIssue(BuildIssue.Severity.BLOCKED, BlockPos.ZERO,
					"Placement has no loaded schematic"));
			return null;
		}

		Map<String, SubRegionPlacement> enabled = placement.getEnabledRelativeSubRegionPlacements();
		if (enabled.isEmpty()) {
			errors.add(new BuildIssue(BuildIssue.Severity.BLOCKED, BlockPos.ZERO,
					"All subregions of the placement are disabled"));
			return null;
		}

		long totalCells = 0;
		List<RegionWork> regions = new ArrayList<>();
		for (Map.Entry<String, SubRegionPlacement> entry : enabled.entrySet()) {
			String name = entry.getKey();
			SubRegionPlacement sub = entry.getValue();
			LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
			Vec3i signedSize = schematic.getAreaSizeAsVec3i(name);
			if (container == null || signedSize == null) {
				errors.add(new BuildIssue(BuildIssue.Severity.BLOCKED, BlockPos.ZERO,
						"Subregion '" + name + "' has no block data"));
				return null;
			}
			Vec3i containerSize = container.getSize();
			long cells = (long) Math.abs(containerSize.getX())
					* Math.abs(containerSize.getY())
					* Math.abs(containerSize.getZ());
			totalCells = Math.addExact(totalCells, cells);
			if (totalCells > MAX_REGION_CELLS) {
				errors.add(new BuildIssue(BuildIssue.Severity.BLOCKED, BlockPos.ZERO,
						"Selection too large: >" + MAX_REGION_CELLS + " cells"));
				return null;
			}
			Map<BlockPos, CompoundData> blockEntities = schematic.getBlockEntityMapForRegion(name);
			if (blockEntities == null) {
				blockEntities = Map.of();
			}
			List<LitematicaSchematic.EntityInfo> entities = schematic.getEntityListForRegion(name);
			regions.add(new RegionWork(name, sub, container, signedSize, containerSize,
					blockEntities, entities == null ? 0 : entities.size()));
		}
		return regions;
	}

	/**
	 * World transform for one container cell, identical to upstream
	 * SchematicPlacingUtils semantics including signed sub-region sizes.
	 */
	public static BlockPos worldPos(SchematicPlacement placement, SubRegionPlacement sub,
									Vec3i signedSize, int x, int y, int z) {
		return RegionTransform.of(placement, sub, signedSize).worldPos(x, y, z);
	}

	/** State transform equivalent to upstream (main mirror, sub mirror, combined rotation). */
	public static BlockState transformState(SchematicPlacement placement, SubRegionPlacement sub,
											BlockState original) {
		return RegionTransform.of(placement, sub, null).transformState(original);
	}

	/** Stable fingerprint of the placement transform/subregions; pause triggers when it changes. */
	public static String fingerprint(SchematicPlacement placement, List<RegionWork> regions) {
		StringBuilder sb = new StringBuilder();
		sb.append(placement.getName()).append('|')
				.append(placement.getOrigin()).append('|')
				.append(placement.getRotation()).append('|')
				.append(placement.getMirror());
		for (RegionWork region : regions) {
			SubRegionPlacement sub = region.placement();
			sb.append('|').append(region.name()).append('@')
					.append(sub.getPos()).append('/')
					.append(sub.getRotation()).append('/')
					.append(sub.getMirror());
		}
		return Integer.toHexString(sb.toString().hashCode()) + ':' + regions.size()
				+ ':' + placement.getSchematic().hashCode();
	}

	/**
	 * Incremental snapshotter: processes at most {@link #CELLS_PER_TICK} cells per
	 * {@link #tick()} call so importing never stalls the render loop.
	 */
	public static final class SnapshotTask {
		private final SchematicPlacement placement;
		private final List<RegionWork> regions;
		private final Map<BlockPos, BlockState> blocks = new HashMap<>();
		private final Set<BlockPos> blockEntityData = new HashSet<>();
		private final List<BuildIssue> issues = new ArrayList<>();
		private int regionIndex;
		private int cx, cy, cz;
		private int entityTotal;
		private int nonAir;
		private boolean done;
		private boolean failed;

		public SnapshotTask(SchematicPlacement placement, List<RegionWork> regions) {
			this.placement = Objects.requireNonNull(placement);
			this.regions = regions;
		}

		public boolean tick() {
			if (done) {
				return true;
			}
			int budget = CELLS_PER_TICK;
			while (budget > 0 && regionIndex < regions.size()) {
				RegionWork region = regions.get(regionIndex);
				Vec3i size = region.containerSize();
				int sx = Math.abs(size.getX());
				int sy = Math.abs(size.getY());
				int sz = Math.abs(size.getZ());

				while (budget > 0 && cy < sy) {
					BlockState raw = region.container().get(cx, cy, cz);
					if (raw.getBlock() != Blocks.STRUCTURE_VOID) {
						BlockPos world = worldPos(placement, region.placement(), region.signedSize(), cx, cy, cz);
						BlockState state = transformState(placement, region.placement(), raw);
						BlockState prev = blocks.get(world);
						if (prev != null && !prev.equals(state)) {
							// Overlapping sub-region cells with different content: reject
							// instead of nondeterministically overwriting.
							issues.add(new BuildIssue(BuildIssue.Severity.CONFLICT, world,
									"Overlapping subregions with different blocks"));
							failed = true;
							done = true;
							return true;
						}
						if (prev == null) {
							blocks.put(world, state);
							if (!state.isAir()) {
								nonAir++;
								if (nonAir > MAX_NON_AIR) {
									issues.add(new BuildIssue(BuildIssue.Severity.BLOCKED, world,
											"Mehr als " + MAX_NON_AIR + " non-air blocks"));
									failed = true;
									done = true;
									return true;
								}
							}
						}
						BlockPos key = new BlockPos(cx, cy, cz);
						if (region.blockEntities().containsKey(key)) {
							blockEntityData.add(world);
						}
					}
					budget--;
					if (++cx >= sx) {
						cx = 0;
						if (++cz >= sz) {
							cz = 0;
							cy++;
						}
					}
				}
				if (cy >= sy) {
					regionIndex++;
					cx = cy = cz = 0;
					entityTotal += region.entityCount();
				}
			}
			if (regionIndex >= regions.size()) {
				done = true;
			}
			return done;
		}

		public boolean isDone() { return done; }
		public boolean isFailed() { return failed; }
		public List<BuildIssue> issues() { return issues; }

		public Blueprint result() {
			return new Blueprint(placement.getName(), Map.copyOf(blocks), Set.copyOf(blockEntityData),
					entityTotal, fingerprint(placement, regions));
		}
	}
}
