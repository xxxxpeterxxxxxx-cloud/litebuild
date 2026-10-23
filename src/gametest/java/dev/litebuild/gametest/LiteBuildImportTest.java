package dev.litebuild.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import dev.litebuild.build.BuildController;
import dev.litebuild.model.Blueprint;
import dev.litebuild.model.BuildIssue;
import dev.litebuild.model.RunState;

/**
 * Real file-import coverage. All existing gametests feed the importer with
 * in-memory {@link LitematicaSchematic#createFromWorld} results; this test
 * round-trips through the on-disk formats that {@code /lb load} dispatches
 * on ({@link FileType#fromFile} -> createFromFile / WorldUtils.convert*):
 *
 *   a) .litematic  write -> createFromFile -> placement -> importSelected
 *   b) placement rotation (CW90) and mirror (FRONT_BACK) on the reloaded
 *      schematic, checking transformed cell positions AND block states
 *   c) multi-subregion selection plus a negative-size sub-region
 *   d) vanilla-structure .nbt written via StructureTemplate.save/NbtIo,
 *      read through WorldUtils.convertStructureToLitematicaSchematic
 *   e) two enabled sub-regions moved onto the same cells with disagreeing
 *      content -> BuildIssue CONFLICT, never a silent overwrite
 *
 * Schematica (.schematic) is intentionally uncovered: upstream
 * convertLitematicaSchematicToSchematicaSchematic is a stub returning
 * false, so no writer exists to produce a fixture file.
 */
public final class LiteBuildImportTest implements FabricClientGameTest {
	private static final BlockPos ORIGIN = new BlockPos(2, 64, 2);
	private static final int STAGE_DY = 136;
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	private static final BlockPos STAGE2 = STAGE.offset(32, 0, 0);
	private static final BlockPos STAGE3 = STAGE.offset(64, 0, 0);

	private static final String LITEMATIC_NAME = "gb-import-test";
	private static final String STRUCT_NAME = "gb-import-struct.nbt";

	private static final BlockPos REPEATER_LOCAL = new BlockPos(2, 1, 1);
	private static final BlockPos STAIR_LOCAL = new BlockPos(0, 0, 1);

	// Expected cell counts of the small fixture box (3 x 2 x 2).
	private static final int FIXTURE_CELLS = 12;
	private static final int FIXTURE_NON_AIR = 5;

	private static Map<BlockPos, BlockState> fixtureBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		m.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(REPEATER_LOCAL, Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 4));
		m.put(STAIR_LOCAL, Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
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

			// ----------------------------------------------------------
			// (a) .litematic round-trip through the real file path
			// ----------------------------------------------------------
			LitematicaSchematic captured = captureFixture(sp, STAGE, "A");
			Path litematicFile = schematicDir.resolve(LITEMATIC_NAME + ".litematic");
			Path nbtFile = schematicDir.resolve(STRUCT_NAME);
			try {
				writeLitematic(context, captured, schematicDir, LITEMATIC_NAME);
				LitematicaSchematic reloaded = loadViaFileType(context, schematicDir,
						litematicFile.getFileName().toString());
				if (reloaded == null || reloaded.getSubRegionCount() != 1) {
					throw new AssertionError("createFromFile produced "
							+ (reloaded == null ? "null" : reloaded.getSubRegionCount()
									+ " regions"));
				}
				selectPlacement(context, reloaded, "A", "gbimport",
						Mirror.NONE, Rotation.NONE);
				Blueprint bp = importAndAwait(context, "litematic-roundtrip");
				assertFixtureBlueprint(bp, Mirror.NONE, Rotation.NONE);

				// ------------------------------------------------------
				// (b) placement rotation + mirror on the reloaded file
				// ------------------------------------------------------
				selectPlacement(context, reloaded, "A", "gbrot90",
						Mirror.NONE, Rotation.CLOCKWISE_90);
				Blueprint rot = importAndAwait(context, "rotation-cw90");
				assertFixtureBlueprint(rot, Mirror.NONE, Rotation.CLOCKWISE_90);

				selectPlacement(context, reloaded, "A", "gbmirror",
						Mirror.FRONT_BACK, Rotation.NONE);
				Blueprint mir = importAndAwait(context, "mirror-front-back");
				assertFixtureBlueprint(mir, Mirror.FRONT_BACK, Rotation.NONE);
				System.out.println("[GT-IMPORT] (a)+(b) litematic round-trip + "
						+ "rotation/mirror verified");

				// ------------------------------------------------------
				// (c) multi-subregion + negative-size region
				// ------------------------------------------------------
				LitematicaSchematic multi = captureMulti(sp);
				selectPlacement(context, multi, "A", "gbmulti",
						Mirror.NONE, Rotation.NONE);
				Blueprint bpMulti = importAndAwait(context, "multi-region");
				assertMultiBlueprint(bpMulti);
				System.out.println("[GT-IMPORT] (c) multi-subregion + "
						+ "negative-size region verified");

				// ------------------------------------------------------
				// (d) vanilla-structure .nbt -> convertStructureTo...
				// ------------------------------------------------------
				writeStructureNbt(sp, nbtFile);
				LitematicaSchematic fromNbt = loadViaFileType(context, schematicDir,
						STRUCT_NAME);
				if (fromNbt == null || fromNbt.getSubRegionCount() < 1) {
					throw new AssertionError("convertStructureToLitematicaSchematic "
							+ "produced " + fromNbt);
				}
				String nbtRegion = fromNbt.getAreaPositions().keySet().iterator().next();
				selectPlacement(context, fromNbt, nbtRegion, "gbstruct",
						Mirror.NONE, Rotation.NONE);
				Blueprint bpNbt = importAndAwait(context, "structure-nbt");
				assertFixtureBlueprint(bpNbt, Mirror.NONE, Rotation.NONE);
				System.out.println("[GT-IMPORT] (d) vanilla-structure .nbt "
						+ "verified via region '" + nbtRegion + "'");

				// ------------------------------------------------------
				// (e) overlapping sub-regions must fail with CONFLICT
				// ------------------------------------------------------
				LitematicaSchematic overlap = captureOverlap(sp);
				context.runOnClient(mc -> {
					var mgr = DataManager.getSchematicPlacementManager();
					SchematicPlacement placement = SchematicPlacement.createFor(
							overlap, ORIGIN, "gboverlap", true, true);
					mgr.addSchematicPlacement(placement, true);
					// Pull sub-region B onto region A's cells: the world
					// position maps to a relative pos of (0,0,0) == relA.
					placement.moveSubRegionTo("B", placement.getOrigin(), s -> { });
					mgr.setSelectedSchematicPlacement(placement);
				});
				Blueprint conflict = importAndAwaitFailure(context, "overlap-conflict");
				if (conflict != null) {
					throw new AssertionError("overlapping regions silently "
							+ "produced a blueprint");
				}
				long conflicts = BuildController.get().issues().stream()
						.filter(i -> i.severity() == BuildIssue.Severity.CONFLICT)
						.count();
				if (conflicts < 1) {
					throw new AssertionError("expected CONFLICT issue, got "
							+ BuildController.get().issues());
				}
				System.out.println("[GT-IMPORT] (e) overlap CONFLICT verified "
						+ "(" + conflicts + " issue(s))");

				context.takeScreenshot("litebuild-import-test");
			} finally {
				deleteQuietly(litematicFile);
				deleteQuietly(nbtFile);
				context.runOnClient(mc -> {
					BuildController.get().stop();
					DataManager.getSchematicPlacementManager()
							.setSelectedSchematicPlacement(null);
				});
			}
		}
	}

	// ------------------------------------------------------------------
	// File path helpers (a) / (d)
	// ------------------------------------------------------------------

	private void writeLitematic(ClientGameTestContext context, LitematicaSchematic schem,
								Path dir, String name) {
		context.runOnClient(mc -> {
			try {
				Files.createDirectories(dir);
			} catch (java.io.IOException e) {
				throw new AssertionError("schematic dir not writable: " + dir, e);
			}
			if (!schem.writeToFile(dir, name, true)) {
				throw new AssertionError("writeToFile failed for " + name);
			}
		});
		Path file = dir.resolve(name + ".litematic");
		if (!Files.isRegularFile(file)) {
			throw new AssertionError("expected schematic file missing: " + file);
		}
	}

	/** Dispatch on FileType exactly like LiteBuildCommands.load. */
	private LitematicaSchematic loadViaFileType(ClientGameTestContext context,
												Path dir, String fileName) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		context.runOnClient(mc -> {
			Path file = dir.resolve(fileName);
			FileType type = FileType.fromFile(file);
			LitematicaSchematic schematic = switch (type) {
				case LITEMATICA_SCHEMATIC ->
						LitematicaSchematic.createFromFile(dir, fileName);
				case VANILLA_STRUCTURE ->
						WorldUtils.convertStructureToLitematicaSchematic(dir, fileName);
				default -> null;
			};
			ref.set(schematic);
		});
		return ref.get();
	}

	/** Vanilla StructureTemplate -> .nbt on disk (what a structure block saves). */
	private void writeStructureNbt(TestSingleplayerContext sp, Path nbtFile) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			fixtureBlocks().forEach((local, state) ->
					level.setBlock(local.offset(STAGE), state, 3));
			StructureTemplate template = new StructureTemplate();
			template.fillFromWorld(level, STAGE, new Vec3i(3, 2, 2),
					false, List.of(Blocks.STRUCTURE_VOID));
			CompoundTag tag = template.save(new CompoundTag());
			try {
				Files.createDirectories(nbtFile.getParent());
				NbtIo.writeCompressed(tag, nbtFile);
			} catch (java.io.IOException e) {
				throw new AssertionError("structure .nbt write failed", e);
			} finally {
				fixtureBlocks().keySet().forEach(local ->
						level.setBlock(local.offset(STAGE),
								Blocks.AIR.defaultBlockState(), 3));
			}
		});
	}

	// ------------------------------------------------------------------
	// Placement + import helpers
	// ------------------------------------------------------------------

	/**
	 * Create a placement whose schematic-local origin lands at ORIGIN after
	 * the placement transform, then select it. For a positive-size region
	 * cell c this yields worldPos(c) == ORIGIN + T(c) where T is the
	 * combined placement mirror+rotation (upstream
	 * PositionUtils.getTransformedBlockPos semantics).
	 */
	private void selectPlacement(ClientGameTestContext context, LitematicaSchematic schem,
								 String regionName, String name,
								 Mirror mirror, Rotation rotation) {
		context.runOnClient(mc -> {
			var mgr = DataManager.getSchematicPlacementManager();
			SchematicPlacement previous = mgr.getSelectedSchematicPlacement();
			if (previous != null) {
				mgr.removeSchematicPlacement(previous, true);
			}
			BlockPos rel = schem.getAreaPositions().get(regionName);
			if (rel == null) {
				throw new AssertionError("schematic has no region '" + regionName
						+ "' regions=" + schem.getAreaPositions().keySet());
			}
			BlockPos relT = PositionUtils.getTransformedBlockPos(rel, mirror, rotation);
			SchematicPlacement placement = SchematicPlacement.createFor(
					schem, ORIGIN.subtract(relT), name, true, true);
			mgr.addSchematicPlacement(placement, true);
			mgr.setSelectedSchematicPlacement(placement);
			if (rotation != Rotation.NONE) {
				placement.setRotation(rotation, null);
			}
			if (mirror != Mirror.NONE) {
				placement.setMirror(mirror, null);
			}
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

	/** Like {@link #importAndAwait} but expects the snapshot to be rejected. */
	private Blueprint importAndAwaitFailure(ClientGameTestContext context, String label) {
		context.runOnClient(mc -> BuildController.get().importSelected());
		context.waitFor(mc -> BuildController.get().state() != RunState.PLANNING, 600);
		BuildController c = BuildController.get();
		if (c.state() != RunState.EMPTY) {
			throw new AssertionError(label + ": expected EMPTY after failed "
					+ "import, state=" + c.state() + " status=" + c.statusLines());
		}
		return c.blueprint();
	}

	// ------------------------------------------------------------------
	// Assertions
	// ------------------------------------------------------------------

	/**
	 * Verify the single-region fixture blueprint under the given placement
	 * transform. Expected world pos of cell c: ORIGIN + T(c); expected
	 * state: mirror->mirror->rotate per RegionTransform (no sub-region
	 * transform is applied, so it reduces to placement mirror then the
	 * combined rotation).
	 */
	private void assertFixtureBlueprint(Blueprint bp, Mirror mirror, Rotation rotation) {
		if (bp.blocks().size() != FIXTURE_CELLS) {
			throw new AssertionError("cell count " + bp.blocks().size()
					+ " != " + FIXTURE_CELLS);
		}
		if (bp.nonAirCount() != FIXTURE_NON_AIR) {
			throw new AssertionError("nonAir " + bp.nonAirCount()
					+ " != " + FIXTURE_NON_AIR);
		}
		Map<BlockPos, BlockState> fixture = fixtureBlocks();
		for (int x = 0; x < 3; x++) {
			for (int y = 0; y < 2; y++) {
				for (int z = 0; z < 2; z++) {
					BlockPos local = new BlockPos(x, y, z);
					BlockState expected = fixture.getOrDefault(local,
							Blocks.AIR.defaultBlockState());
					expected = expected.mirror(mirror).rotate(rotation);
					BlockPos world = ORIGIN.offset(
							PositionUtils.getTransformedBlockPos(local, mirror, rotation));
					assertCell(bp, world, expected, "fixture");
				}
			}
		}
		// Explicit rotation/mirror semantics on the repeater, not just map
		// equality: CW90 must turn NORTH -> EAST, FRONT_BACK -> SOUTH.
		BlockPos repeaterWorld = ORIGIN.offset(
				PositionUtils.getTransformedBlockPos(REPEATER_LOCAL, mirror, rotation));
		BlockState repeater = bp.blocks().get(repeaterWorld);
		if (repeater == null || repeater.getBlock() != Blocks.REPEATER) {
			throw new AssertionError("repeater missing at " + repeaterWorld
					+ ": " + repeater);
		}
		Direction wantedFacing = rotation.rotate(mirror.mirror(Direction.NORTH));
		if (repeater.getValue(BlockStateProperties.HORIZONTAL_FACING) != wantedFacing
				|| repeater.getValue(BlockStateProperties.DELAY) != 4) {
			throw new AssertionError("repeater state wrong at " + repeaterWorld
					+ ": " + repeater + " wanted facing " + wantedFacing);
		}
	}

	private void assertMultiBlueprint(Blueprint bp) {
		// Region A: 2 stone at rel (0,0,0); region B: 2 dirt at rel (4,0,0);
		// region C: negative-size (-3,2,-3) at rel (5,0,5), 18 cells.
		if (bp.blocks().size() != 22 || bp.nonAirCount() != 22) {
			throw new AssertionError("multi-region counts: cells="
					+ bp.blocks().size() + " nonAir=" + bp.nonAirCount());
		}
		assertCell(bp, ORIGIN.offset(0, 0, 0), Blocks.STONE.defaultBlockState(), "A0");
		assertCell(bp, ORIGIN.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), "A1");
		assertCell(bp, ORIGIN.offset(4, 0, 0), Blocks.DIRT.defaultBlockState(), "B0");
		assertCell(bp, ORIGIN.offset(5, 0, 0), Blocks.DIRT.defaultBlockState(), "B1");
		// Negative-size region C: container cell (0,0,0) is the min corner
		// (3,0,3); the signed size shifts it (-2,0,-2) from regionPos (5,0,5).
		assertCell(bp, ORIGIN.offset(3, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState(),
				"C-min-corner");
		assertCell(bp, ORIGIN.offset(5, 1, 5), Blocks.IRON_BLOCK.defaultBlockState(),
				"C-max-corner");
		assertCell(bp, ORIGIN.offset(4, 1, 4), Blocks.BRICKS.defaultBlockState(),
				"C-mid");
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
	// Fixtures (server-side staging + Litematica world capture)
	// ------------------------------------------------------------------

	private void prepareWorld(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			server.setDifficulty(Difficulty.PEACEFUL, true);
			for (int x = -16; x <= 80; x++) {
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

	/** Stage the small fixture and capture it through createFromWorld. */
	private LitematicaSchematic captureFixture(TestSingleplayerContext sp,
											 BlockPos stage, String name) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			fixtureBlocks().forEach((local, state) ->
					level.setBlock(local.offset(stage), state, 3));
			var sel = new AreaSelection();
			sel.setExplicitOrigin(stage);
			sel.addSubRegionBox(new Box(stage, stage.offset(2, 1, 1), name), true);
			ref.set(LitematicaSchematic.createFromWorld(level, sel, saveInfo(),
					"gbtest", s -> { }));
			fixtureBlocks().keySet().forEach(local ->
					level.setBlock(local.offset(stage), Blocks.AIR.defaultBlockState(), 3));
		});
		if (ref.get() == null) {
			throw new AssertionError("fixture capture failed");
		}
		return ref.get();
	}

	/**
	 * Three sub-regions in one selection: A = 2 stone (positive), B = 2
	 * dirt (positive), C = 3x2x3 bricks with a negative signed size
	 * (pos1 > pos2 on x/z) exercising the min-corner offset path in
	 * RegionTransform.worldPos.
	 */
	private LitematicaSchematic captureMulti(TestSingleplayerContext sp) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			java.util.List<BlockPos> staged = new java.util.ArrayList<>();
			for (int x = 0; x <= 1; x++) {
				BlockPos p = STAGE2.offset(x, 0, 0);
				level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
				staged.add(p);
			}
			for (int x = 4; x <= 5; x++) {
				BlockPos p = STAGE2.offset(x, 0, 0);
				level.setBlock(p, Blocks.DIRT.defaultBlockState(), 3);
				staged.add(p);
			}
			for (int x = 3; x <= 5; x++) {
				for (int y = 0; y <= 1; y++) {
					for (int z = 3; z <= 5; z++) {
						BlockPos p = STAGE2.offset(x, y, z);
						level.setBlock(p, Blocks.BRICKS.defaultBlockState(), 3);
						staged.add(p);
					}
				}
			}
			level.setBlock(STAGE2.offset(3, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
			level.setBlock(STAGE2.offset(5, 1, 5), Blocks.IRON_BLOCK.defaultBlockState(), 3);

			var sel = new AreaSelection();
			sel.setExplicitOrigin(STAGE2);
			sel.addSubRegionBox(new Box(STAGE2, STAGE2.offset(1, 0, 0), "A"), true);
			sel.addSubRegionBox(new Box(STAGE2.offset(4, 0, 0),
					STAGE2.offset(5, 0, 0), "B"), true);
			// pos1 > pos2 on x/z -> signed size (-3,2,-3).
			sel.addSubRegionBox(new Box(STAGE2.offset(5, 0, 5),
					STAGE2.offset(3, 1, 3), "C"), true);
			ref.set(LitematicaSchematic.createFromWorld(level, sel, saveInfo(),
					"gbmulti", s -> { }));
			staged.forEach(p -> level.setBlock(p, Blocks.AIR.defaultBlockState(), 3));
		});
		LitematicaSchematic schem = ref.get();
		if (schem == null || schem.getSubRegionCount() != 3) {
			throw new AssertionError("multi-region capture failed: " + schem);
		}
		Vec3i signed = schem.getAreaSizeAsVec3i("C");
		if (signed == null || signed.getX() >= 0 || signed.getZ() >= 0) {
			throw new AssertionError("negative-size region not preserved by "
					+ "createFromWorld: size=" + signed);
		}
		return schem;
	}

	/** Two disjoint regions later moved onto the same cells for (e). */
	private LitematicaSchematic captureOverlap(TestSingleplayerContext sp) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			java.util.List<BlockPos> staged = new java.util.ArrayList<>();
			for (int x = 0; x <= 1; x++) {
				BlockPos p = STAGE3.offset(x, 0, 0);
				level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
				staged.add(p);
			}
			for (int x = 4; x <= 5; x++) {
				BlockPos p = STAGE3.offset(x, 0, 0);
				level.setBlock(p, Blocks.DIRT.defaultBlockState(), 3);
				staged.add(p);
			}
			var sel = new AreaSelection();
			sel.setExplicitOrigin(STAGE3);
			sel.addSubRegionBox(new Box(STAGE3, STAGE3.offset(1, 0, 0), "A"), true);
			sel.addSubRegionBox(new Box(STAGE3.offset(4, 0, 0),
					STAGE3.offset(5, 0, 0), "B"), true);
			ref.set(LitematicaSchematic.createFromWorld(level, sel, saveInfo(),
					"gboverlap", s -> { }));
			staged.forEach(p -> level.setBlock(p, Blocks.AIR.defaultBlockState(), 3));
		});
		if (ref.get() == null || ref.get().getSubRegionCount() != 2) {
			throw new AssertionError("overlap capture failed");
		}
		return ref.get();
	}

	private static LitematicaSchematic.SchematicSaveInfo saveInfo() {
		return new LitematicaSchematic.SchematicSaveInfo(false, false, true, false);
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (java.io.IOException ignored) {
		}
	}
}
