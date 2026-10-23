package dev.litebuild;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.Test;
import dev.litebuild.build.PreflightReport;
import dev.litebuild.model.Blueprint;
import static org.junit.jupiter.api.Assertions.*;

class PreflightReportTest extends McTestBase {

	private Blueprint bp(Map<BlockPos, BlockState> blocks) {
		return new Blueprint("t", Map.copyOf(blocks), Set.of(), 0, "fp");
	}

	private Blueprint bp(Map<BlockPos, BlockState> blocks, Set<BlockPos> be,
					   int entities) {
		return new Blueprint("t", Map.copyOf(blocks), be, entities, "fp");
	}

	private PreflightReport analyze(Blueprint bp, ToIntFunction<Item> inv,
									boolean fluids, boolean activation) {
		return PreflightReport.analyze(bp, null, inv, null, fluids, activation);
	}

	private PreflightReport.TaskGroup group(java.util.List<PreflightReport.TaskGroup> groups,
											String key) {
		return groups.stream().filter(g -> g.key().equals(key))
				.findFirst().orElse(null);
	}

	private PreflightReport.Material material(PreflightReport r, String id) {
		return r.materials().stream().filter(m -> m.itemId().equals(id))
				.findFirst().orElse(null);
	}

	@Test
	void missingMaterialsAggregateByItem() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(1, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(2, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(0, 1, 0), Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(p(1, 1, 0), Blocks.OAK_PLANKS.defaultBlockState());

		PreflightReport r = analyze(bp(blocks),
				item -> item == Items.STONE ? 1 : 0, true, true);

		PreflightReport.Material stone = material(r, "minecraft:stone");
		assertNotNull(stone);
		assertEquals(3, stone.need());
		assertEquals(1, stone.have());
		assertEquals(2, stone.missing());
		PreflightReport.Material planks = material(r, "minecraft:oak_planks");
		assertNotNull(planks);
		assertEquals(2, planks.need());
		assertEquals(0, planks.have());
		assertEquals(2, planks.missing());
		assertEquals(2, r.missing().size());
		assertFalse(r.clear());
		// Sorted by registry id, same as the chat materials view.
		assertEquals("minecraft:oak_planks", r.materials().get(0).itemId());
	}

	@Test
	void fullInventoryReportsNoMissing() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		PreflightReport r = analyze(bp(blocks), item -> 64, true, true);
		assertTrue(r.missing().isEmpty());
		assertTrue(r.clear());
		assertEquals("Preflight: ready", r.headline());
	}

	@Test
	void fluidTasksNeedBucketsAndToggle() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.WATER.defaultBlockState());
		blocks.put(p(1, 0, 0), Blocks.LAVA.defaultBlockState());
		blocks.put(p(2, 0, 0), Blocks.OAK_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
				.setValue(BlockStateProperties.WATERLOGGED, true));

		PreflightReport r = analyze(bp(blocks), item -> 0, false, true);

		// 1 source + 1 waterlog task = 2 water buckets, 1 lava bucket,
		// plus the dry oak-slab item.
		assertEquals(2, material(r, "minecraft:water_bucket").need());
		assertEquals(1, material(r, "minecraft:lava_bucket").need());
		assertEquals(1, material(r, "minecraft:oak_slab").need());

		assertEquals(1, r.requiredToggles().size());
		assertEquals("Fluids", r.requiredToggles().get(0).label());
		assertEquals(3, r.requiredToggles().get(0).tasks());
		assertNotNull(group(r.risks(), "fluids_off"));

		PreflightReport open = analyze(bp(blocks), item -> 0, true, true);
		assertTrue(open.requiredToggles().isEmpty());
		assertNull(group(open.risks(), "fluids_off"));
	}

	@Test
	void blockEntityManualClassification() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.OAK_SIGN.defaultBlockState());
		blocks.put(p(1, 0, 0), Blocks.CHEST.defaultBlockState());
		blocks.put(p(2, 0, 0), Blocks.COMMAND_BLOCK.defaultBlockState());
		blocks.put(p(3, 0, 0), Blocks.NOTE_BLOCK.defaultBlockState());
		Set<BlockPos> be = Set.of(p(0, 0, 0), p(1, 0, 0), p(2, 0, 0), p(3, 0, 0));

		PreflightReport r = analyze(bp(blocks, be, 2), item -> 0, true, true);

		assertEquals(1, group(r.manualTasks(), "sign").count());
		assertEquals(1, group(r.manualTasks(), "container").count());
		assertEquals(1, group(r.manualTasks(), "command").count());
		assertEquals(1, group(r.manualTasks(), "blockentity").count());
		assertEquals(2, group(r.manualTasks(), "entities").count());
		// Positions carry through as examples for the detail view.
		assertEquals(p(1, 0, 0), group(r.manualTasks(), "container")
				.examples().get(0));
	}

	@Test
	void commandBlockIsManualEvenWithoutStoredNbt() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.COMMAND_BLOCK.defaultBlockState());
		PreflightReport r = analyze(bp(blocks), item -> 0, true, true);
		assertEquals(1, group(r.manualTasks(), "command").count());
	}

	@Test
	void dynamicPistonsAndItemlessStatesClassified() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.PISTON.defaultBlockState()
				.setValue(BlockStateProperties.EXTENDED, true));
		blocks.put(p(1, 0, 0), Blocks.MOVING_PISTON.defaultBlockState());
		blocks.put(p(2, 0, 0), Blocks.FROGSPAWN.defaultBlockState());
		blocks.put(p(3, 0, 0), Blocks.PISTON.defaultBlockState());

		PreflightReport r = analyze(bp(blocks), item -> 0, true, true);

		PreflightReport.TaskGroup pistons = group(r.risks(), "piston");
		assertNotNull(pistons);
		assertEquals(2, pistons.count());
		// The retracted piston is an ordinary placeable target.
		assertEquals(1, material(r, "minecraft:piston").need());
		// Frogspawn has no item — manual; the moving piston went to risks,
		// not here.
		PreflightReport.TaskGroup noitem = group(r.manualTasks(), "noitem");
		assertNotNull(noitem);
		assertEquals(1, noitem.count());
		assertEquals(p(2, 0, 0), noitem.examples().get(0));
	}

	@Test
	void activationToggleCoversPhaseAndLiveControls() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.LEVER.defaultBlockState()
				.setValue(LeverBlock.FACE, AttachFace.FLOOR));
		blocks.put(p(1, 0, 0), Blocks.OAK_TRAPDOOR.defaultBlockState()
				.setValue(BlockStateProperties.OPEN, true));
		blocks.put(p(2, 0, 0), Blocks.OAK_TRAPDOOR.defaultBlockState());

		PreflightReport r = analyze(bp(blocks), item -> 64, true, false);

		// Lever (ACTIVATION phase) + open trapdoor (interaction) = 2.
		assertEquals(1, r.requiredToggles().size());
		assertEquals("Activation", r.requiredToggles().get(0).label());
		assertEquals(2, r.requiredToggles().get(0).tasks());

		PreflightReport open = analyze(bp(blocks), item -> 64, true, true);
		assertTrue(open.requiredToggles().isEmpty());
	}

	@Test
	void unloadedChunksCountedWhenWorldKnown() {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		blocks.put(p(0, 0, 0), Blocks.STONE.defaultBlockState());
		blocks.put(p(1, 0, 0), Blocks.STONE.defaultBlockState());

		MapWorld world = new MapWorld();
		world.defaultLoaded = false;
		PreflightReport r = PreflightReport.analyze(bp(blocks), null,
				item -> 64, world, true, true);
		assertEquals(2, r.unloadedCells());
		assertEquals(2, group(r.risks(), "unloaded").count());

		PreflightReport unknown = analyze(bp(blocks), item -> 64, true, true);
		assertEquals(-1, unknown.unloadedCells());
		assertNull(group(unknown.risks(), "unloaded"));
	}
}
