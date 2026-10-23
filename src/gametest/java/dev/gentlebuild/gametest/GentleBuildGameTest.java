package dev.gentlebuild.gametest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.model.RunState;
import dev.gentlebuild.motion.BuildRhythm;
import dev.gentlebuild.motion.InputBroker;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * In-world end-to-end test: builds a real Litematica schematic (captured
 * from a staged structure high in the sky), lets the production
 * BuildController construct it in a flat arena, and verifies the result
 * server-side. Fixture setup happens through the test harness only;
 * all construction goes through the real controller.
 */
public final class GentleBuildGameTest implements FabricClientGameTest {
	// Desired structure, world coordinates (arena around the player).
	private static final BlockPos FLOOR_ORIGIN = new BlockPos(-8, 63, -8);
	private static final int FLOOR_W = 29, FLOOR_D = 21;
	// Stage the template at y+136 so createFromWorld only sees our blocks.
	private static final int STAGE_DY = 136;
	private static final BlockPos ORIGIN = new BlockPos(2, 64, 2); // local (0,0,0)
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);

	// ---- automatic-pause probe (test-only client tick listener) ----
	private static final java.util.concurrent.atomic.AtomicBoolean SHORT_SEEN =
			new java.util.concurrent.atomic.AtomicBoolean();
	private static final java.util.concurrent.atomic.AtomicBoolean AFK_SEEN =
			new java.util.concurrent.atomic.AtomicBoolean();
	private static final java.util.concurrent.atomic.AtomicReference<String> VIOLATION =
			new java.util.concurrent.atomic.AtomicReference<>();
	private static volatile boolean manualPauseDone;
	private static Vec3 restStartPos;
	private static RunState prevState = RunState.EMPTY;

	private static void probeTick(Minecraft mc) {
		var c = BuildController.get();
		RunState st = c.state();
		if (st == RunState.RESTING && prevState != RunState.RESTING) {
			restStartPos = mc.player != null ? mc.player.position() : null;
		}
		prevState = st;
		if (st != RunState.RESTING || mc.player == null) {
			return;
		}
		if (c.automaticPauseKind() == BuildRhythm.PauseKind.SHORT) {
			SHORT_SEEN.set(true);
		}
		if (c.automaticPauseKind() == BuildRhythm.PauseKind.AFK) {
			AFK_SEEN.set(true);
			// One forced early AFK is enough; raise the interval so the
			// rest of the run is not interrupted by repeated AFK rests.
			c.config().afkIntervalMinTicks = 72000;
			c.config().afkIntervalMaxTicks = 72000;
			if (!manualPauseDone) {
				manualPauseDone = true;
				c.pause("test manual"); // user interrupt during AFK
			}
		}
		if (!Input.EMPTY.equals(InputBroker.desired())
				|| mc.player.isUsingItem() || !mc.player.onGround()
				|| (restStartPos != null
						&& mc.player.position().distanceToSqr(restStartPos) > 0.04)) {
			VIOLATION.compareAndSet(null,
					"rest invariants broken: " + c.diagnosticState());
		}
	}

	static {
		ClientTickEvents.END_CLIENT_TICK.register(GentleBuildGameTest::probeTick);
	}

	// local coords relative to ORIGIN
	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		for (int x = 0; x <= 7; x++) {
			m.put(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState()); // line x2..9,z2
		}
		m.put(new BlockPos(8, 0, 0), Blocks.STONE.defaultBlockState());     // support 10,64,2
		m.put(new BlockPos(8, 1, 0), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 4));
		m.put(new BlockPos(9, 0, 0), Blocks.HOPPER.defaultBlockState()
				.setValue(BlockStateProperties.FACING_HOPPER, Direction.DOWN));
		// glass basin: floor 3x3 at local x10..12,z3..5 (world 12..14, 5..7)
		for (int x = 10; x <= 12; x++) {
			for (int z = 3; z <= 5; z++) {
				m.put(new BlockPos(x, 0, z), Blocks.GLASS.defaultBlockState());
			}
		}
		for (int x = 10; x <= 12; x++) {
			for (int z = 3; z <= 5; z++) {
				if (x == 11 && z == 4) {
					continue; // center = water source
				}
				m.put(new BlockPos(x, 1, z), Blocks.GLASS.defaultBlockState());
			}
		}
		m.put(new BlockPos(11, 1, 4), Blocks.WATER.defaultBlockState());    // source 13,65,6
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

			// Placement origin maps schematic-local (0,0,0) to ORIGIN in the
			// world. The stored sub-region position is already relative.
			BlockPos rel = schematic.getAreaPositions().get("A");
			context.runOnClient(mc -> {
				var mgr = DataManager.getSchematicPlacementManager();
				SchematicPlacement placement = SchematicPlacement.createFor(
						schematic, ORIGIN.subtract(rel), "gbtest", true, true);
				mgr.addSchematicPlacement(placement, true);
				mgr.setSelectedSchematicPlacement(placement);
				BuildController.get().config().fluidsEnabled = false;
				BuildController.get().config().activationEnabled = false;
				// Deterministic cadence for this run: pauses fire fast.
				var cfg = BuildController.get().config();
				cfg.naturalMovement = true;
				cfg.automaticPauses = true;
				cfg.shortPauseMinActions = cfg.shortPauseMaxActions = 2;
				cfg.shortPauseMinTicks = cfg.shortPauseMaxTicks = 8;
				// AFK is due on the first active boundary, while the player is
			// still standing in the open spawn spot (deterministic, unlike
			// waiting for a mid-build safe boundary next to placed blocks).
			cfg.afkIntervalMinTicks = cfg.afkIntervalMaxTicks = 1;
				cfg.afkDurationMinTicks = cfg.afkDurationMaxTicks = 16;
				SHORT_SEEN.set(false);
				AFK_SEEN.set(false);
				VIOLATION.set(null);
				manualPauseDone = false;
				BuildController.get().start(); // snapshots, then auto-starts
			});

			// First AFK triggers a manual pause via the probe; PAUSED must
			// hold (no auto-resume), then an explicit start continues.
			awaitDiag(context, AFK_SEEN::get, 2400, "first-afk");
			context.waitTicks(5);
			assertState(context, RunState.PAUSED);
			context.runOnClient(mc -> BuildController.get().start());
			System.out.println("[GT] manual pause during AFK held, resumed");

			// Wait until the dry build is done and pauses at fluid authorization.
			context.waitFor(mc -> BuildController.get().state() == RunState.PAUSED
					|| BuildController.get().state() == RunState.COMPLETE, 12000);
			System.out.println("[GT] state=" + BuildController.get().state()
					+ " status=" + BuildController.get().statusLines()
					+ " issues=" + BuildController.get().issues().stream()
							.map(Object::toString).limit(12).toList()
					+ " blueprint=" + (BuildController.get().blueprint() != null
							? BuildController.get().blueprint().nonAirCount() + " nonAir"
							: "null")
					+ " planner=" + (BuildController.get().planner() != null
							? BuildController.get().planner().verifiedCount() + "/"
									+ BuildController.get().planner().totalTargets()
							: "null"));
			assertState(context, RunState.PAUSED);
			assertDryStructure(sp);
			context.takeScreenshot("gentlebuild-dry-complete");

			// Scenario 3a: remove one basin wall server-side; the pour must
			// be refused and the bucket must survive.
			var wall = new BlockPos(12, 65, 6);
			sp.getServer().runOnServer(server -> {
				server.overworld().setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
				// Drain every spare glass stack: otherwise the controller
				// could legitimately rebuild the wall itself and the refusal
				// assertion would never be reachable.
				var inv = conn.getServerPlayer().getInventory();
				for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
					var stack = inv.getNonEquipmentItems().get(i);
					if (stack.is(Items.GLASS)) {
						inv.getNonEquipmentItems().set(i, ItemStack.EMPTY);
					}
				}
			});
			context.waitTicks(10);
			System.out.println("[GT] wall removed + glass drained, fluids enabled");
			context.runOnClient(mc -> {
				BuildController.get().config().fluidsEnabled = true;
				BuildController.get().start();
			});
			awaitDiag(context, () -> BuildController.get().state() == RunState.PAUSED,
					2400, "refusal-pause");
			assertState(context, RunState.PAUSED);
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				assertBlock(level, new BlockPos(13, 65, 6), Blocks.AIR,
						"water source placed despite missing live wall");
				long buckets = conn.getServerPlayer().getInventory().getNonEquipmentItems()
						.stream().filter(s -> s.is(Items.WATER_BUCKET)).count();
				if (buckets < 1) {
					throw new AssertionError("water bucket consumed despite failed containment");
				}
			});

			// Scenario 3b: repair the wall, resume, expect full completion.
			sp.getServer().runOnServer(server ->
					server.overworld().setBlock(wall, Blocks.GLASS.defaultBlockState(), 3));
			context.waitTicks(10);
			System.out.println("[GT] wall restored, resuming");
			context.runOnClient(mc -> BuildController.get().start());
			awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE
					|| BuildController.get().state() == RunState.PAUSED,
					4800, "complete");
			// An owned support inside a required-AIR blueprint cell is an
			// explicit TEMP_SUPPORT mismatch; beside the repeater the
			// conservative break policy refuses auto-removal, so the user
			// removes it and a fresh audit completes.
			if (BuildController.get().state() == RunState.PAUSED) {
				var bp = BuildController.get().blueprint();
				var toClear = BuildController.get().supportPositions().stream()
						.filter(pos -> bp.blocks().containsKey(pos)
								&& bp.blocks().get(pos).isAir())
						.toList();
				System.out.println("[GT] inside-AIR supports: " + toClear);
				sp.getServer().runOnServer(server -> {
					var level = server.overworld();
					for (BlockPos pos : toClear) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				});
				context.waitTicks(10);
				context.runOnClient(mc -> BuildController.get().verify());
				awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
						1200, "verify-after-support-removal");
			}
			assertState(context, RunState.COMPLETE);
			sp.getServer().runOnServer(server -> assertBlock(
					server.overworld(), new BlockPos(13, 65, 6), Blocks.WATER,
					"water source missing after completion"));
			if (!SHORT_SEEN.get() || !AFK_SEEN.get()) {
				throw new AssertionError("automatic pauses never fired: short="
						+ SHORT_SEEN.get() + " afk=" + AFK_SEEN.get());
			}
			if (VIOLATION.get() != null) {
				throw new AssertionError(VIOLATION.get());
			}
			// Restore default cadence for the next scenario.
			context.runOnClient(mc -> {
				var cfg = BuildController.get().config();
				cfg.afkIntervalMinTicks = 2400; cfg.afkIntervalMaxTicks = 6000;
				cfg.shortPauseMinActions = 4; cfg.shortPauseMaxActions = 10;
				cfg.shortPauseMinTicks = 8; cfg.shortPauseMaxTicks = 24;
				cfg.afkIntervalMinTicks = 2400; cfg.afkIntervalMaxTicks = 6000;
				cfg.afkDurationMinTicks = 160; cfg.afkDurationMaxTicks = 400;
			});
			context.takeScreenshot("gentlebuild-build-result");
		}
	}

	private void awaitDiag(ClientGameTestContext context,
						   java.util.function.BooleanSupplier cond,
						   int maxTicks, String label) {
		try {
			for (int t = 0; t < maxTicks / 20; t++) {
				context.waitTicks(20);
				context.runOnClient(mc ->
						System.out.println("[GT-" + label + "] "
								+ BuildController.get().diagnosticState()));
				if (cond.getAsBoolean()) {
					return;
				}
			}
		} finally {
			try {
				context.takeScreenshot("gentlebuild-" + label + "-end");
			} catch (Throwable ignored) {
			}
		}
		throw new AssertionError("timeout waiting for " + label
				+ " issues=" + BuildController.get().issues().stream()
				.map(Object::toString).limit(8).toList());
	}

	private void assertState(ClientGameTestContext context, RunState wanted) {
		RunState actual = BuildController.get().state();
		if (actual != wanted) {
			throw new AssertionError("state=" + actual + " wanted=" + wanted
					+ " status=" + BuildController.get().statusLines()
					+ " issues=" + BuildController.get().issues().stream()
					.map(Object::toString).limit(8).toList());
		}
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
			// Flat arena floor at y=63, air volume above.
			for (int x = FLOOR_ORIGIN.getX(); x < FLOOR_ORIGIN.getX() + FLOOR_W; x++) {
				for (int z = FLOOR_ORIGIN.getZ(); z < FLOOR_ORIGIN.getZ() + FLOOR_D; z++) {
					level.setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
					for (int y = 64; y <= 80; y++) {
						level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			var player = conn(sp).getServerPlayer();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(0.2, 64, 0.2); // same-cell, off-center start
			var inv = player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.STONE, 64));
			inv.setItem(1, new ItemStack(Items.GLASS, 32));
			inv.setItem(2, new ItemStack(Items.REPEATER, 2));
			inv.setItem(3, new ItemStack(Items.HOPPER, 2));
			inv.setItem(4, new ItemStack(Items.WATER_BUCKET, 1));
			inv.setItem(5, new ItemStack(Items.DIRT, 16));
			// Extra materials in the main inventory to exercise swapping.
			inv.setItem(20, new ItemStack(Items.STONE, 32));
		});
	}

	private net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection conn(
			TestSingleplayerContext sp) {
		return sp.getConnection();
	}

	/** Capture the desired structure through Litematica's own world import. */
	private LitematicaSchematic captureSchematic(TestSingleplayerContext sp) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			var desired = desiredBlocks();
			// Stage a copy high above the arena (all other cells are air).
			desired.forEach((local, state) ->
					level.setBlock(local.offset(STAGE), state, 3));
			var sel = new AreaSelection();
			sel.setExplicitOrigin(STAGE);
			var box = new Box(STAGE,
					STAGE.offset(12, 1, 5), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbtest", s -> { });
			ref.set(schem);
			// Clean up the staging copy.
			for (int x = 0; x <= 12; x++) {
				for (int y = 0; y <= 1; y++) {
					for (int z = 0; z <= 5; z++) {
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

	private void assertDryStructure(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			var desired = desiredBlocks();
			for (var e : desired.entrySet()) {
				BlockPos world = e.getKey().offset(ORIGIN);
				if (e.getValue().getBlock() == Blocks.WATER) {
					continue; // fluid stage not yet authorized
				}
				BlockState actual = level.getBlockState(world);
				if (actual.getBlock() != e.getValue().getBlock()) {
					throw new AssertionError("dry target wrong at " + world.toShortString()
							+ ": " + actual.getBlock() + " != " + e.getValue().getBlock());
				}
			}
			BlockState repeater = level.getBlockState(new BlockPos(10, 65, 2));
			if (repeater.getBlock() != Blocks.REPEATER
					|| repeater.getValue(BlockStateProperties.DELAY) != 4
					|| repeater.getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.NORTH) {
				throw new AssertionError("repeater wrong: " + repeater);
			}
			assertBlock(level, new BlockPos(11, 64, 2), Blocks.HOPPER, "hopper");
		});
	}

	private static void assertBlock(net.minecraft.world.level.Level level,
									BlockPos pos, net.minecraft.world.level.block.Block wanted,
									String what) {
		if (level.getBlockState(pos).getBlock() != wanted) {
			throw new AssertionError(what + " at " + pos.toShortString()
					+ ": " + level.getBlockState(pos).getBlock() + " != " + wanted);
		}
	}
}
