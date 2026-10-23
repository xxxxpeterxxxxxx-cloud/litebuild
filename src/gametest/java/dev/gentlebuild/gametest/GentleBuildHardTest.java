package dev.gentlebuild.gametest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.gametest.audit.GentleBuildAudit;
import dev.gentlebuild.model.BuildIssue;
import dev.gentlebuild.model.RunState;

/**
 * Hard real-world build + wire-level anticheat audit. A compact
 * "farm-machine" (~64 cells, 3 layers: stone platform, stair run, slab
 * walkway, framed oak door, dust/repeater/comparator row, lever, wall
 * torch, floor trapdoor, sealed glass water basin, elevated corner caps)
 * is staged, captured through the real Litematica import and built by the
 * production BuildController through staged permissions:
 *   STRUCTURE..MECHANISMS -> PAUSED (fluids) -> water pour
 *   -> PAUSED (activation) -> lever -> COMPLETE.
 * The whole run, plus a fault-injection repair pass, is recorded by
 * GentleBuildAudit; packet- and tick-level invariants are asserted at the
 * end. All construction goes through the real controller.
 */
public final class GentleBuildHardTest implements FabricClientGameTest {
	private static final BlockPos FLOOR_ORIGIN = new BlockPos(-8, 63, -6);
	private static final int FLOOR_W = 22, FLOOR_D = 17;
	private static final int STAGE_DY = 136;
	private static final BlockPos ORIGIN = new BlockPos(2, 64, 2); // local (0,0,0)
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	private static final BlockPos BOX_MAX = new BlockPos(9, 2, 3);

	// World positions of the interesting cells.
	private static final BlockPos STAIR_LOW = new BlockPos(2, 65, 2);
	private static final BlockPos STAIR_HIGH = new BlockPos(3, 66, 2);
	private static final BlockPos DUST_A = new BlockPos(2, 65, 3);
	private static final BlockPos REPEATER = new BlockPos(3, 65, 3);
	private static final BlockPos COMPARATOR = new BlockPos(4, 65, 3);
	private static final BlockPos DUST_B = new BlockPos(5, 65, 3);
	private static final BlockPos LEVER = new BlockPos(6, 65, 3);
	private static final BlockPos WALL_TORCH = new BlockPos(6, 66, 3);
	private static final BlockPos TRAPDOOR = new BlockPos(6, 65, 5);
	private static final BlockPos DOOR_LOWER = new BlockPos(7, 65, 4);
	private static final BlockPos DOOR_UPPER = new BlockPos(7, 66, 4);
	private static final BlockPos WATER = new BlockPos(10, 65, 3);
	private static final BlockPos FAULT_STONE = new BlockPos(2, 64, 4); // platform edge

	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		// Stone base platform x0..5, z0..3.
		for (int x = 0; x <= 5; x++) {
			for (int z = 0; z <= 3; z++) {
				m.put(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
			}
		}
		// Two-step stair run ascending along the z=0 edge.
		m.put(new BlockPos(0, 1, 0), Blocks.STONE_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
				.setValue(BlockStateProperties.HALF, Half.BOTTOM));
		m.put(new BlockPos(1, 1, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(1, 2, 0), Blocks.STONE_STAIRS.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
				.setValue(BlockStateProperties.HALF, Half.BOTTOM));
		// Redstone row z=1: dust - repeater(delay 3, east) - comparator
		// (subtract, east) - dust - lever(OFF, floor).
		m.put(new BlockPos(0, 1, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
		m.put(new BlockPos(1, 1, 1), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DELAY, 3));
		m.put(new BlockPos(2, 1, 1), Blocks.COMPARATOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.MODE_COMPARATOR, ComparatorMode.SUBTRACT));
		m.put(new BlockPos(3, 1, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
		m.put(new BlockPos(4, 1, 1), Blocks.LEVER.defaultBlockState()
				.setValue(net.minecraft.world.level.block
						.FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.POWERED, false));
		// Wall torch hung on the door jamb's west face (support ordering).
		m.put(new BlockPos(4, 2, 1), Blocks.WALL_TORCH.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		// Slab walkway along z=3.
		for (int x = 0; x <= 3; x++) {
			m.put(new BlockPos(x, 1, 3), Blocks.STONE_SLAB.defaultBlockState()
					.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
		}
		// Floor trapdoor lying flat on the platform, next to the walkway.
		m.put(new BlockPos(4, 1, 3), Blocks.OAK_TRAPDOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.HALF, Half.BOTTOM)
				.setValue(BlockStateProperties.OPEN, false));
		// Oak door in a stone frame at the platform's east edge.
		m.put(new BlockPos(5, 1, 1), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(5, 2, 1), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(5, 1, 3), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(5, 2, 3), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(5, 1, 2), Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
				.setValue(BlockStateProperties.DOOR_HINGE, net.minecraft.world.level.block
						.state.properties.DoorHingeSide.RIGHT)
				.setValue(BlockStateProperties.OPEN, false));
		m.put(new BlockPos(5, 2, 2), Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
				.setValue(BlockStateProperties.DOOR_HINGE, net.minecraft.world.level.block
						.state.properties.DoorHingeSide.RIGHT)
				.setValue(BlockStateProperties.OPEN, false));
		// Glass water basin: 3x3 floor, ring walls, one source inside,
		// two raised corner caps only reachable via diagonal sight-lines.
		for (int x = 7; x <= 9; x++) {
			for (int z = 0; z <= 2; z++) {
				m.put(new BlockPos(x, 0, z), Blocks.GLASS.defaultBlockState());
			}
		}
		for (int x = 7; x <= 9; x++) {
			for (int z = 0; z <= 2; z++) {
				if (x == 8 && z == 1) {
					continue;
				}
				m.put(new BlockPos(x, 1, z), Blocks.GLASS.defaultBlockState());
			}
		}
		m.put(new BlockPos(8, 1, 1), Blocks.WATER.defaultBlockState());
		m.put(new BlockPos(7, 2, 0), Blocks.GLASS.defaultBlockState());
		m.put(new BlockPos(9, 2, 2), Blocks.GLASS.defaultBlockState());
		return m;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			var conn = sp.getConnection();
			conn.waitForChunksRender();
			prepareWorld(sp);
			LitematicaSchematic schematic = captureSchematic(sp);

			BlockPos rel = schematic.getAreaPositions().get("A");
			context.runOnClient(mc -> {
				var mgr = DataManager.getSchematicPlacementManager();
				for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) {
					mgr.removeSchematicPlacement(old);
				}
				SchematicPlacement placement = SchematicPlacement.createFor(
						schematic, ORIGIN.subtract(rel), "gbhard", true, true);
				mgr.addSchematicPlacement(placement, true);
				mgr.setSelectedSchematicPlacement(placement);
				var cfg = BuildController.get().config();
				cfg.fluidsEnabled = false;
				cfg.activationEnabled = false;
				cfg.automaticPauses = true;   // humanized cadence stays on
				cfg.automaticRepairs = true;
				cfg.maxRepairRounds = 3;
				GentleBuildAudit.startRecording();
				BuildController.get().start();
			});

			// Stage 1: dry build runs to the fluid-authorization pause.
			awaitState(context, 60000, "dry");
			assertState(RunState.PAUSED, "dry");
			assertUnverifiedExactly(List.of(WATER, LEVER),
					"after dry pause only the fluid+activation work may remain");
			assertDryStructure(sp);

			// Stage 2: authorize fluids -> pour -> activation pause (lever).
			context.runOnClient(mc -> {
				BuildController.get().config().fluidsEnabled = true;
				BuildController.get().start();
			});
			awaitState(context, 30000, "fluids");
			assertState(RunState.PAUSED, "fluids");
			assertUnverifiedExactly(List.of(LEVER),
					"after fluids only the lever may remain");
			sp.getServer().runOnServer(server -> assertBlock(
					server.overworld(), WATER, Blocks.WATER, "water source"));

			// Stage 3: authorize activation -> lever -> audit -> COMPLETE.
			context.runOnClient(mc -> {
				BuildController.get().config().activationEnabled = true;
				BuildController.get().start();
			});
			awaitState(context, 30000, "activation");
			assertState(RunState.COMPLETE, "activation");
			context.takeScreenshot("gentlebuild-hard-complete");

			assertFinalStructure(sp);
			assertNoBlockingIssues("after build");

			// Explicit re-verify: audit must pass cleanly with unchanged
			// repair bookkeeping.
			int auditsBefore = BuildController.get().fullAuditRuns();
			context.runOnClient(mc -> BuildController.get().verify());
			awaitState(context, 12000, "reverify");
			assertState(RunState.COMPLETE, "reverify");
			// verify() starts a fresh session counter — a clean audit must
			// not have run any repair round.
			if (BuildController.get().repairRound() != 0) {
				throw new AssertionError("re-verify triggered repair rounds: "
						+ BuildController.get().repairRound());
			}
			if (BuildController.get().fullAuditRuns() <= auditsBefore) {
				throw new AssertionError("re-verify ran no audit: runs="
						+ BuildController.get().fullAuditRuns());
			}

			// Fault injection (server-side fixture mutation only): a
			// missing platform stone, a reset repeater, a flipped lever
			// and an opened trapdoor must be repaired through the normal
			// interaction path - still under the audit.
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				level.setBlock(FAULT_STONE, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(REPEATER, level.getBlockState(REPEATER)
						.setValue(BlockStateProperties.DELAY, 1), 3);
				level.setBlock(LEVER, level.getBlockState(LEVER)
						.setValue(BlockStateProperties.POWERED, true), 3);
				level.setBlock(TRAPDOOR, level.getBlockState(TRAPDOOR)
						.setValue(BlockStateProperties.OPEN, true), 3);
				System.out.println("[GT-HARD] injected 4 faults");
			});
			context.waitTicks(10);
			context.runOnClient(mc -> BuildController.get().verify());
			awaitState(context, 30000, "repair");
			assertState(RunState.COMPLETE, "repair");
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				assertBlock(level, FAULT_STONE, Blocks.STONE, "repaired stone");
				if (level.getBlockState(REPEATER)
						.getValue(BlockStateProperties.DELAY) != 3) {
					throw new AssertionError("repeater delay not restored: "
							+ level.getBlockState(REPEATER));
				}
				if (level.getBlockState(LEVER)
						.getValue(BlockStateProperties.POWERED)) {
					throw new AssertionError("lever left powered");
				}
				if (level.getBlockState(TRAPDOOR)
						.getValue(BlockStateProperties.OPEN)) {
					throw new AssertionError("trapdoor left open");
				}
			});
			if (BuildController.get().repairRound() < 1) {
				throw new AssertionError("expected a repair round, got "
						+ BuildController.get().repairRound());
			}
			assertNoBlockingIssues("after repair");
			context.takeScreenshot("gentlebuild-hard-repaired");

			// ---- wire audit verdict -----------------------------------
			context.runOnClient(mc -> GentleBuildAudit.stopRecording());
			List<String> violations = GentleBuildAudit.violations();
			System.out.println("[GT-HARD] audit " + GentleBuildAudit.summary());
			System.out.println("[GT-HARD] packet histogram: "
					+ GentleBuildAudit.histogram());
			for (String note : GentleBuildAudit.notes()) {
				System.out.println("[GT-HARD] note: " + note);
			}
			if (!violations.isEmpty()) {
				for (String v : violations) {
					java.util.regex.Matcher mm = java.util.regex.Pattern
							.compile("at (\\d+), (\\d+), (\\d+)").matcher(v);
					if (mm.find()) {
						System.out.println("[GT-HARD] timeline "
								+ GentleBuildAudit.destroyTimeline(new BlockPos(
										Integer.parseInt(mm.group(1)),
										Integer.parseInt(mm.group(2)),
										Integer.parseInt(mm.group(3)))));
					}
				}
			}
			if (!violations.isEmpty()) {
				throw new AssertionError("wire audit violations ("
						+ violations.size() + "): "
						+ violations.stream().limit(50).toList());
			}
		}
	}

	// ------------------------------------------------------------------
	// Assertions
	// ------------------------------------------------------------------

	/** Wait for a terminal state (PAUSED or COMPLETE) with progress logs. */
	private void awaitState(ClientGameTestContext context, int maxTicks, String label) {
		for (int t = 0; t < maxTicks / 20; t++) {
			context.waitTicks(20);
			if (t % 10 == 0) {
				context.runOnClient(mc -> {
					var c = BuildController.get();
					var pl = c.planner();
					System.out.println("[GT-HARD-" + label + "] "
							+ c.diagnosticState()
							+ " verified=" + (pl != null
									? pl.verifiedCount() + "/" + pl.totalTargets()
									: "?")
							+ " pos=" + (mc.player != null
									? mc.player.blockPosition().toShortString()
									: "?"));
				});
			}
			RunState st = BuildController.get().state();
			if (st == RunState.PAUSED || st == RunState.COMPLETE) {
				System.out.println("[GT-HARD-" + label + "-end] state=" + st
						+ " " + BuildController.get().diagnosticState()
						+ " status=" + BuildController.get().statusLines()
						+ " issues=" + BuildController.get().issues().stream()
								.map(Object::toString).limit(10).toList());
				return;
			}
		}
		throw new AssertionError("timeout in stage " + label + ": "
				+ BuildController.get().diagnosticState()
				+ " status=" + BuildController.get().statusLines()
				+ " issues=" + BuildController.get().issues().stream()
				.map(Object::toString).limit(8).toList());
	}

	private void assertState(RunState wanted, String stage) {
		RunState actual = BuildController.get().state();
		if (actual != wanted) {
			throw new AssertionError("stage " + stage + ": state=" + actual
					+ " wanted=" + wanted
					+ " status=" + BuildController.get().statusLines()
					+ " issues=" + BuildController.get().issues().stream()
					.map(Object::toString).limit(8).toList());
		}
	}

	private void assertUnverifiedExactly(List<BlockPos> expected, String msg) {
		var planner = BuildController.get().planner();
		if (planner == null) {
			throw new AssertionError("no planner: " + msg);
		}
		java.util.Set<BlockPos> remaining = new java.util.HashSet<>();
		for (var t : planner.targets()) {
			if (!planner.isVerified(t)) {
				remaining.add(t.pos());
			}
		}
		java.util.Set<BlockPos> wanted = new java.util.HashSet<>(expected);
		if (!remaining.equals(wanted)) {
			throw new AssertionError(msg + ": unverified=" + remaining
					+ " status=" + BuildController.get().statusLines()
					+ " issues=" + BuildController.get().issues().stream()
							.map(Object::toString).limit(10).toList());
		}
	}

	/** CONFLICT/BLOCKED issues or MANUAL marks are failures; advisory
	 * block-entity notes on blueprint-flagged cells are tolerated. */
	private void assertNoBlockingIssues(String when) {
		var c = BuildController.get();
		var bp = c.blueprint();
		java.util.Set<BlockPos> advisoryCells = bp != null
				? bp.blockEntityData() : java.util.Set.of();
		for (BuildIssue issue : c.issues()) {
			boolean tolerated = issue.severity() == BuildIssue.Severity.INFO
					|| (issue.severity() == BuildIssue.Severity.MANUAL
						&& advisoryCells.contains(issue.pos()));
			if (!tolerated) {
				throw new AssertionError("manual/conflict issue " + when
						+ ": " + issue);
			}
		}
	}

	private void assertDryStructure(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			for (var e : desiredBlocks().entrySet()) {
				BlockPos world = e.getKey().offset(ORIGIN);
				var wanted = e.getValue().getBlock();
				if (wanted == Blocks.WATER || wanted == Blocks.LEVER) {
					continue; // gated phases
				}
				if (level.getBlockState(world).getBlock() != wanted) {
					throw new AssertionError("dry cell wrong at "
							+ world.toShortString() + ": "
							+ level.getBlockState(world).getBlock()
							+ " != " + wanted);
				}
			}
			assertBlock(level, WATER, Blocks.AIR, "water placed before authorization");
			assertBlock(level, LEVER, Blocks.AIR, "lever placed before authorization");
		});
	}

	private void assertFinalStructure(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			// Stairs: facing + half.
			for (BlockPos p : List.of(STAIR_LOW, STAIR_HIGH)) {
				BlockState s = level.getBlockState(p);
				if (s.getBlock() != Blocks.STONE_STAIRS
						|| s.getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.WEST
						|| s.getValue(BlockStateProperties.HALF) != Half.BOTTOM) {
					throw new AssertionError("stair wrong at "
							+ p.toShortString() + ": " + s);
				}
			}
			// Door: closed, halves, facing.
			BlockState dl = level.getBlockState(DOOR_LOWER);
			BlockState du = level.getBlockState(DOOR_UPPER);
			if (dl.getBlock() != Blocks.OAK_DOOR
					|| dl.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER
					|| dl.getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.WEST
					|| dl.getValue(BlockStateProperties.OPEN)) {
				throw new AssertionError("door lower wrong: " + dl);
			}
			if (du.getBlock() != Blocks.OAK_DOOR
					|| du.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER
					|| du.getValue(BlockStateProperties.OPEN)) {
				throw new AssertionError("door upper wrong: " + du);
			}
			// Repeater: facing + delay 3.
			BlockState rep = level.getBlockState(REPEATER);
			if (rep.getBlock() != Blocks.REPEATER
					|| rep.getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.EAST
					|| rep.getValue(BlockStateProperties.DELAY) != 3) {
				throw new AssertionError("repeater wrong: " + rep);
			}
			// Comparator: subtract mode.
			BlockState cmp = level.getBlockState(COMPARATOR);
			if (cmp.getBlock() != Blocks.COMPARATOR
					|| cmp.getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.EAST
					|| cmp.getValue(BlockStateProperties.MODE_COMPARATOR)
							!= ComparatorMode.SUBTRACT) {
				throw new AssertionError("comparator wrong: " + cmp);
			}
			// Lever OFF.
			BlockState lev = level.getBlockState(LEVER);
			if (lev.getBlock() != Blocks.LEVER
					|| lev.getValue(BlockStateProperties.POWERED)) {
				throw new AssertionError("lever wrong: " + lev);
			}
			// Trapdoor closed bottom half.
			BlockState td = level.getBlockState(TRAPDOOR);
			if (td.getBlock() != Blocks.OAK_TRAPDOOR
					|| td.getValue(BlockStateProperties.OPEN)
					|| td.getValue(BlockStateProperties.HALF) != Half.BOTTOM) {
				throw new AssertionError("trapdoor wrong: " + td);
			}
			// Wall torch attached, facing away from the jamb.
			BlockState torch = level.getBlockState(WALL_TORCH);
			if (torch.getBlock() != Blocks.WALL_TORCH
					|| torch.getValue(BlockStateProperties.HORIZONTAL_FACING)
							!= Direction.WEST) {
				throw new AssertionError("wall torch wrong: " + torch);
			}
			// Redstone dust present at both ends of the row.
			assertBlock(level, DUST_A, Blocks.REDSTONE_WIRE, "dust A");
			assertBlock(level, DUST_B, Blocks.REDSTONE_WIRE, "dust B");
			// Slab walkway.
			for (int x = 0; x <= 3; x++) {
				BlockState slab = level.getBlockState(
						ORIGIN.offset(x, 1, 3));
				if (slab.getBlock() != Blocks.STONE_SLAB
						|| slab.getValue(BlockStateProperties.SLAB_TYPE)
								!= SlabType.BOTTOM) {
					throw new AssertionError("slab wrong at "
							+ ORIGIN.offset(x, 1, 3).toShortString() + ": " + slab);
				}
			}
			// Basin: walls + source.
			for (int x = 7; x <= 9; x++) {
				for (int z = 0; z <= 2; z++) {
					if (x == 8 && z == 1) {
						continue;
					}
					assertBlock(level, ORIGIN.offset(x, 1, z), Blocks.GLASS,
							"basin wall " + x + "," + z);
				}
			}
			assertBlock(level, WATER, Blocks.WATER, "water source");
			if (!level.getFluidState(WATER).isSource()) {
				throw new AssertionError("water at " + WATER.toShortString()
						+ " is not a source: " + level.getFluidState(WATER));
			}
		});
	}

	// ------------------------------------------------------------------
	// Fixture (test-side server setup only)
	// ------------------------------------------------------------------

	private void prepareWorld(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			server.setDifficulty(Difficulty.PEACEFUL, true);
			for (int x = FLOOR_ORIGIN.getX(); x < FLOOR_ORIGIN.getX() + FLOOR_W; x++) {
				for (int z = FLOOR_ORIGIN.getZ(); z < FLOOR_ORIGIN.getZ() + FLOOR_D; z++) {
					level.setBlock(new BlockPos(x, 63, z),
							Blocks.STONE.defaultBlockState(), 3);
					for (int y = 64; y <= 80; y++) {
						level.setBlock(new BlockPos(x, y, z),
								Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			var player = sp.getConnection().getServerPlayer();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(-1.5, 64, 3.5);
			var inv = player.getInventory();
			inv.clearContent();
			// Rich hotbar + main inventory: exercises slot swapping.
			inv.setItem(0, new ItemStack(Items.STONE, 64));
			inv.setItem(1, new ItemStack(Items.GLASS, 64));
			inv.setItem(2, new ItemStack(Items.STONE_STAIRS, 8));
			inv.setItem(3, new ItemStack(Items.STONE_SLAB, 8));
			inv.setItem(4, new ItemStack(Items.OAK_DOOR, 2));
			inv.setItem(5, new ItemStack(Items.REDSTONE, 16));
			inv.setItem(6, new ItemStack(Items.REPEATER, 4));
			inv.setItem(7, new ItemStack(Items.COMPARATOR, 4));
			inv.setItem(8, new ItemStack(Items.LEVER, 4));
			inv.setItem(9, new ItemStack(Items.OAK_TRAPDOOR, 4));
			inv.setItem(10, new ItemStack(Items.TORCH, 16));
			inv.setItem(11, new ItemStack(Items.DIRT, 32));
			// Water buckets stack to 1 in vanilla: two separate stacks, never one count-2 stack.
			inv.setItem(12, new ItemStack(Items.WATER_BUCKET, 1));
			inv.setItem(13, new ItemStack(Items.WATER_BUCKET, 1));
			inv.setItem(20, new ItemStack(Items.STONE, 64));
			inv.setItem(21, new ItemStack(Items.GLASS, 32));
			for (int i = 0; i < inv.getContainerSize(); i++) {
				var st = inv.getItem(i);
				if (!st.isEmpty() && st.getCount() > st.getMaxStackSize()) {
					throw new AssertionError("impossible fixture stack in slot " + i + ": " + st);
				}
			}
		});
	}

	/** Capture the desired structure through Litematica's own world import. */
	private LitematicaSchematic captureSchematic(TestSingleplayerContext sp) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			var desired = desiredBlocks();
			desired.forEach((local, state) ->
					level.setBlock(local.offset(STAGE), state, 3));
			var sel = new AreaSelection();
			sel.setExplicitOrigin(STAGE);
			var box = new Box(STAGE, STAGE.offset(BOX_MAX), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbhard", s -> { });
			ref.set(schem);
			for (int x = 0; x <= BOX_MAX.getX(); x++) {
				for (int y = 0; y <= BOX_MAX.getY(); y++) {
					for (int z = 0; z <= BOX_MAX.getZ(); z++) {
						level.setBlock(STAGE.offset(x, y, z),
								Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		});
		if (ref.get() == null) {
			throw new AssertionError("schematic capture failed");
		}
		return ref.get();
	}

	private static void assertBlock(net.minecraft.world.level.Level level,
									BlockPos pos,
									net.minecraft.world.level.block.Block wanted,
									String what) {
		if (level.getBlockState(pos).getBlock() != wanted) {
			throw new AssertionError(what + " at " + pos.toShortString()
					+ ": " + level.getBlockState(pos).getBlock() + " != " + wanted);
		}
	}
}
