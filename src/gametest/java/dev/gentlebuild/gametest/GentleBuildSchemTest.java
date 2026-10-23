package dev.gentlebuild.gametest;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.model.RunState;

/**
 * Real .schem (Sponge schematic v2) disk-import coverage. The fixture file is
 * written programmatically in this test against the exact tag layout that
 * Litematica 0.28.8's Sponge reader expects (verified against
 * {@code LitematicaSchematic#isValidSpongeSchematic/readFromSpongeSchematic}):
 *
 *   Version: int 2 (< 3 -> flat v2 layout, no "Schematic" wrapper compound)
 *   DataVersion: current game data version (>= MINECRAFT_DATA_VERSION skips
 *                the vanilla DataFixer path entirely)
 *   Width/Height/Length: SHORT tags (CompoundData.contains(key, 2) check)
 *   Palette: compound of "minecraft:block[prop=val,...]" -> int index
 *   PaletteMax: int (spec field; upstream reads the key count instead)
 *   BlockData: byte array of VarInt palette indices, index = (y*L+z)*W+x
 *   BlockEntities: list of compounds { Pos: int[3], Id: "...", ...tile nbt }
 *   Offset: int[3], Metadata: { Name, Author, Date } - optional, kept for
 *           spec fidelity
 *
 * Load goes through the identical dispatch as /gb load:
 * FileType.fromFile -> SPONGE_SCHEMATIC ->
 * WorldUtils.convertSpongeSchematicToLitematicaSchematic.
 *
 * Block matrix inside the fixture (16 cells, 4x2x2):
 *   (0,0,0) oak door lower half facing east
 *   (0,1,0) oak door upper half (same facing/hinge)
 *   (1,0,0) oak stairs facing south
 *   (2,0,0) stone slab type=top
 *   (3,0,0) oak fence waterlogged=true
 *   (1,0,1) repeater facing north delay=4
 *   (2,0,1) chest facing west + BlockEntities entry (block entity coverage)
 */
public final class GentleBuildSchemTest implements FabricClientGameTest {
	private static final BlockPos ORIGIN = new BlockPos(2, 64, 2);

	private static final String SCHEM_NAME = "gb-schem-test.schem";

	private static final int WIDTH = 4;
	private static final int HEIGHT = 2;
	private static final int LENGTH = 2;
	private static final int CELLS = WIDTH * HEIGHT * LENGTH;
	private static final int NON_AIR = 7;

	private static final BlockPos DOOR_LOWER = new BlockPos(0, 0, 0);
	private static final BlockPos DOOR_UPPER = new BlockPos(0, 1, 0);
	private static final BlockPos STAIR = new BlockPos(1, 0, 0);
	private static final BlockPos SLAB = new BlockPos(2, 0, 0);
	private static final BlockPos WATERLOGGED = new BlockPos(3, 0, 0);
	private static final BlockPos REPEATER = new BlockPos(1, 0, 1);
	private static final BlockPos CHEST = new BlockPos(2, 0, 1);

	private static Map<BlockPos, BlockState> fixtureBlocks() {
		Map<BlockPos, BlockState> m = new LinkedHashMap<>();
		m.put(DOOR_LOWER, Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
				.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT));
		m.put(DOOR_UPPER, Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
				.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT));
		m.put(STAIR, Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
				.setValue(BlockStateProperties.HALF, Half.BOTTOM)
				.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT));
		m.put(SLAB, Blocks.STONE_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
		m.put(WATERLOGGED, Blocks.OAK_FENCE.defaultBlockState()
				.setValue(BlockStateProperties.WATERLOGGED, true));
		m.put(REPEATER, Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 4));
		m.put(CHEST, Blocks.CHEST.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
				.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE));
		return m;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			sp.getConnection().waitForChunksRender();
			prepareWorld(sp);
			Path schematicDir = context.computeOnClient(mc ->
					DataManager.getDefaultBaseSchematicDirectory());
			Path schemFile = schematicDir.resolve(SCHEM_NAME);
			try {
				// --------------------------------------------------
				// Write the real .schem fixture, then take it through
				// the exact /gb load dispatch.
				// --------------------------------------------------
				writeSpongeSchem(context, schematicDir);

				FileType detected = context.computeOnClient(mc ->
						FileType.fromFile(schemFile));
				if (detected != FileType.SPONGE_SCHEMATIC) {
					throw new AssertionError("FileType.fromFile classified "
							+ SCHEM_NAME + " as " + detected
							+ " (expected SPONGE_SCHEMATIC)");
				}
				LitematicaSchematic schem = loadViaFileType(context, schematicDir);
				if (schem == null || schem.getSubRegionCount() != 1) {
					throw new AssertionError("convertSpongeSchematicToLitematica"
							+ "Schematic produced " + (schem == null ? "null"
									: schem.getSubRegionCount() + " regions"));
				}
				String region = schem.getAreaPositions().keySet().iterator().next();
				selectPlacement(context, schem, region, "gbschem");
				Blueprint bp = importAndAwait(context, "sponge-schem");
				assertSchemBlueprint(bp);
				System.out.println("[GT-SCHEM] .schem v2 file-path import verified "
						+ "(region '" + region + "', " + bp.nonAirCount()
						+ " non-air, blockEntityData=" + bp.blockEntityData() + ")");

				context.takeScreenshot("gentlebuild-schem-test");
			} finally {
				deleteQuietly(schemFile);
				context.runOnClient(mc -> {
					BuildController.get().stop();
					DataManager.getSchematicPlacementManager()
							.setSelectedSchematicPlacement(null);
				});
			}
		}
	}

	// ------------------------------------------------------------------
	// .schem fixture writer (Sponge schematic v2, gzip-compressed NBT)
	// ------------------------------------------------------------------

	private void writeSpongeSchem(ClientGameTestContext context, Path dir) {
		context.runOnClient(mc -> {
			try {
				Files.createDirectories(dir);
				NbtIo.writeCompressed(buildSpongeNbt(), dir.resolve(SCHEM_NAME));
			} catch (java.io.IOException e) {
				throw new AssertionError(".schem fixture write failed", e);
			}
		});
		if (!Files.isRegularFile(dir.resolve(SCHEM_NAME))) {
			throw new AssertionError("expected schematic file missing: "
					+ dir.resolve(SCHEM_NAME));
		}
	}

	/**
	 * Assemble the Sponge v2 root tag. Palette entries are derived from the
	 * same fixture map used by the assertions, so the state strings round-trip
	 * byte-for-byte semantically.
	 */
	private static CompoundTag buildSpongeNbt() {
		Map<BlockPos, BlockState> fixture = fixtureBlocks();

		// Palette: air is always index 0, remaining states in encounter order.
		Map<BlockState, Integer> paletteIds = new LinkedHashMap<>();
		paletteIds.put(Blocks.AIR.defaultBlockState(), 0);
		Map<BlockPos, Integer> cellIds = new LinkedHashMap<>();
		fixture.forEach((pos, state) -> cellIds.put(pos,
				paletteIds.computeIfAbsent(state, s -> paletteIds.size())));

		CompoundTag palette = new CompoundTag();
		paletteIds.forEach((state, id) -> palette.putInt(spongeStateName(state), id));

		// BlockData: VarInt palette ids in Sponge order (y*L+z)*W+x.
		ByteArrayOutputStream blockData = new ByteArrayOutputStream();
		for (int y = 0; y < HEIGHT; y++) {
			for (int z = 0; z < LENGTH; z++) {
				for (int x = 0; x < WIDTH; x++) {
					writeVarInt(blockData,
							cellIds.getOrDefault(new BlockPos(x, y, z), 0));
				}
			}
		}

		// BlockEntities: one chest at its container cell. Upstream reads
		// "Pos" (int[3]) as the map key, renames "Id" -> "id", and stores the
		// remaining tag as the tile data for Version < 3.
		ListTag blockEntities = new ListTag();
		CompoundTag chest = new CompoundTag();
		chest.putIntArray("Pos", new int[]{CHEST.getX(), CHEST.getY(), CHEST.getZ()});
		chest.putString("Id", "minecraft:chest");
		chest.putInt("x", CHEST.getX());
		chest.putInt("y", CHEST.getY());
		chest.putInt("z", CHEST.getZ());
		chest.put("Items", new ListTag());
		blockEntities.add(chest);

		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", "gb-schem-test");
		metadata.putString("Author", "gentlebuild-gametest");
		metadata.putLong("Date", System.currentTimeMillis());

		CompoundTag root = new CompoundTag();
		root.putShort("Width", (short) WIDTH);
		root.putShort("Height", (short) HEIGHT);
		root.putShort("Length", (short) LENGTH);
		root.putInt("Version", 2);
		root.putInt("DataVersion",
				SharedConstants.getCurrentVersion().dataVersion().version());
		root.putIntArray("Offset", new int[]{0, 0, 0});
		root.put("Palette", palette);
		root.putInt("PaletteMax", paletteIds.size());
		root.putByteArray("BlockData", blockData.toByteArray());
		root.put("BlockEntities", blockEntities);
		root.put("Metadata", metadata);
		return root;
	}

	/** Minecraft VarInt: 7 payload bits per byte, little-endian groups. */
	private static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	/**
	 * Sponge palette key format:
	 * {@code minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]}.
	 * All properties are emitted so the parsed state equals the fixture state
	 * exactly.
	 */
	private static String spongeStateName(BlockState state) {
		StringBuilder sb = new StringBuilder(
				BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		var props = state.getProperties();
		if (!props.isEmpty()) {
			sb.append('[');
			boolean first = true;
			for (Property<?> prop : props) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(prop.getName()).append('=')
						.append(propertyName(prop, state.getValue(prop)));
			}
			sb.append(']');
		}
		return sb.toString();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String propertyName(Property property, Comparable<?> value) {
		return property.getName(value);
	}

	// ------------------------------------------------------------------
	// Load / placement / import (mirrors GentleBuildCommands.load)
	// ------------------------------------------------------------------

	/** Dispatch on FileType exactly like GentleBuildCommands.load. */
	private LitematicaSchematic loadViaFileType(ClientGameTestContext context, Path dir) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		context.runOnClient(mc -> {
			Path file = dir.resolve(SCHEM_NAME);
			FileType type = FileType.fromFile(file);
			LitematicaSchematic schematic = switch (type) {
				case LITEMATICA_SCHEMATIC ->
						LitematicaSchematic.createFromFile(dir, SCHEM_NAME);
				case SPONGE_SCHEMATIC ->
						WorldUtils.convertSpongeSchematicToLitematicaSchematic(dir, SCHEM_NAME);
				case VANILLA_STRUCTURE ->
						WorldUtils.convertStructureToLitematicaSchematic(dir, SCHEM_NAME);
				default -> null;
			};
			ref.set(schematic);
		});
		return ref.get();
	}

	/**
	 * Same flow as GentleBuildCommands.load: register in SchematicHolder,
	 * create a placement, select it. The placement origin is chosen so
	 * container cell (0,0,0) lands at ORIGIN.
	 */
	private void selectPlacement(ClientGameTestContext context, LitematicaSchematic schem,
								 String regionName, String name) {
		context.runOnClient(mc -> {
			var mgr = DataManager.getSchematicPlacementManager();
			SchematicPlacement previous = mgr.getSelectedSchematicPlacement();
			if (previous != null) {
				mgr.removeSchematicPlacement(previous, true);
			}
			SchematicHolder.getInstance().addSchematic(schem, true);
			BlockPos rel = schem.getAreaPositions().get(regionName);
			if (rel == null) {
				throw new AssertionError("schematic has no region '" + regionName
						+ "' regions=" + schem.getAreaPositions().keySet());
			}
			SchematicPlacement placement = SchematicPlacement.createFor(
					schem, ORIGIN.subtract(rel), name, true, true);
			mgr.addSchematicPlacement(placement, true);
			mgr.setSelectedSchematicPlacement(placement);
		});
	}

	private Blueprint importAndAwait(ClientGameTestContext context, String label) {
		context.runOnClient(mc -> BuildController.get().importSelected());
		context.waitFor(mc -> BuildController.get().state() != RunState.PLANNING, 600);
		BuildController c = BuildController.get();
		if (c.state() != RunState.READY || c.blueprint() == null) {
			throw new AssertionError(label + ": expected READY blueprint, state="
					+ c.state() + " status=" + c.statusLines()
					+ " issues=" + c.issues());
		}
		return c.blueprint();
	}

	// ------------------------------------------------------------------
	// Assertions
	// ------------------------------------------------------------------

	private void assertSchemBlueprint(Blueprint bp) {
		if (bp.blocks().size() != CELLS) {
			throw new AssertionError("cell count " + bp.blocks().size()
					+ " != " + CELLS);
		}
		if (bp.nonAirCount() != NON_AIR) {
			throw new AssertionError("nonAir " + bp.nonAirCount()
					+ " != " + NON_AIR);
		}
		Map<BlockPos, BlockState> fixture = fixtureBlocks();
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				for (int z = 0; z < LENGTH; z++) {
					BlockPos local = new BlockPos(x, y, z);
					BlockState expected = fixture.getOrDefault(local,
							Blocks.AIR.defaultBlockState());
					assertCell(bp, ORIGIN.offset(local), expected, "cell " + local);
				}
			}
		}

		// Explicit property-level checks on the matrix, not just map equality:
		// the point is proving individual state properties survive the file.
		assertProp(bp, DOOR_LOWER, BlockStateProperties.DOUBLE_BLOCK_HALF,
				DoubleBlockHalf.LOWER, "door lower half");
		assertProp(bp, DOOR_LOWER, BlockStateProperties.HORIZONTAL_FACING,
				Direction.EAST, "door lower facing");
		assertProp(bp, DOOR_UPPER, BlockStateProperties.DOUBLE_BLOCK_HALF,
				DoubleBlockHalf.UPPER, "door upper half");
		assertProp(bp, DOOR_UPPER, BlockStateProperties.DOOR_HINGE,
				DoorHingeSide.LEFT, "door upper hinge");
		assertProp(bp, STAIR, BlockStateProperties.HORIZONTAL_FACING,
				Direction.SOUTH, "stair facing");
		assertProp(bp, STAIR, BlockStateProperties.HALF,
				Half.BOTTOM, "stair half");
		assertProp(bp, SLAB, BlockStateProperties.SLAB_TYPE,
				SlabType.TOP, "slab type");
		assertProp(bp, WATERLOGGED, BlockStateProperties.WATERLOGGED,
				true, "waterlogged fence");
		assertProp(bp, REPEATER, BlockStateProperties.HORIZONTAL_FACING,
				Direction.NORTH, "repeater facing");
		assertProp(bp, REPEATER, BlockStateProperties.DELAY,
				4, "repeater delay");
		assertProp(bp, CHEST, BlockStateProperties.HORIZONTAL_FACING,
				Direction.WEST, "chest facing");
		assertProp(bp, CHEST, BlockStateProperties.CHEST_TYPE,
				ChestType.SINGLE, "chest type");

		// The BlockEntities entry must reach the blueprint's block-entity set
		// at the chest's world position.
		BlockPos chestWorld = ORIGIN.offset(CHEST);
		if (!bp.blockEntityData().contains(chestWorld)) {
			throw new AssertionError("block entity data missing for chest at "
					+ chestWorld.toShortString() + ": " + bp.blockEntityData());
		}
		if (bp.entities() != 0) {
			throw new AssertionError("unexpected entity count " + bp.entities());
		}
	}

	private <T extends Comparable<T>> void assertProp(Blueprint bp, BlockPos local,
													  Property<T> prop, T wanted, String what) {
		BlockPos world = ORIGIN.offset(local);
		BlockState actual = bp.blocks().get(world);
		if (actual == null) {
			throw new AssertionError(what + ": no blueprint cell at "
					+ world.toShortString());
		}
		if (!actual.hasProperty(prop) || !actual.getValue(prop).equals(wanted)) {
			throw new AssertionError(what + " at " + world.toShortString()
					+ ": " + actual + " wanted " + prop.getName() + "=" + wanted);
		}
	}

	private void assertCell(Blueprint bp, BlockPos pos, BlockState expected, String what) {
		BlockState actual = bp.blocks().get(pos);
		if (actual == null) {
			throw new AssertionError(what + ": no blueprint cell at "
					+ pos.toShortString());
		}
		if (!actual.equals(expected)) {
			throw new AssertionError(what + " at " + pos.toShortString()
					+ ": " + actual + " != " + expected);
		}
	}

	// ------------------------------------------------------------------
	// World prep (same arena floor as the import test)
	// ------------------------------------------------------------------

	private void prepareWorld(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			server.setDifficulty(Difficulty.PEACEFUL, true);
			for (int x = -16; x <= 32; x++) {
				for (int z = -16; z <= 32; z++) {
					level.setBlock(new BlockPos(x, 63, z),
							Blocks.STONE.defaultBlockState(), 3);
					for (int y = 64; y <= 80; y++) {
						level.setBlock(new BlockPos(x, y, z),
								Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		});
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (java.io.IOException ignored) {
		}
	}
}
