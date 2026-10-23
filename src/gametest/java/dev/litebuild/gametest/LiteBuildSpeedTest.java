package dev.litebuild.gametest;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import dev.litebuild.build.BuildController;
import dev.litebuild.model.RunState;
import dev.litebuild.motion.InputBroker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Speed-path proofs (beta.4):
 *  A) a 1x5 stone row in reach of one stance must be built from at most two
 *     distinct player cells — station batching places every reachable block
 *     before relocating (formerly one walk per block).
 *  B) a target beyond an 8-block leg must trigger real vanilla sprinting
 *     during the walk phase.
 * All construction still goes through the production BuildController.
 */
public final class LiteBuildSpeedTest implements FabricClientGameTest {
	private static final int STAGE_DY = 136;

	private static final Set<BlockPos> STANCES = ConcurrentHashMap.newKeySet();
	private static final AtomicBoolean SPRINT_SEEN = new AtomicBoolean();
	private static volatile boolean recordStances;
	private static volatile boolean recordSprint;
	private static volatile int firstAimingTick = -1;
	private static volatile int lastActionTick = -1;
	private static volatile int tickCounter;
	// Row18 evidence: confirmed = planner reports the cell verified, not an attempt.
	private static volatile boolean row18Recording;
	private static final java.util.Set<BlockPos> ROW18_CELLS = new java.util.HashSet<>();
	private static final java.util.Set<BlockPos> ROW18_VERIFIED = ConcurrentHashMap.newKeySet();
	private static final java.util.List<BlockPos> ROW18_CONFIRMED_STANCES =
			java.util.Collections.synchronizedList(new java.util.ArrayList<>());
	private static volatile net.minecraft.world.phys.Vec3 row18FirstConfirmedPos;
	private static final AtomicBoolean ROW18_FORWARD_BEFORE_CONFIRM = new AtomicBoolean();

	private static void probeTick(Minecraft mc) {
		tickCounter++;
		if (mc.player == null) {
			return;
		}
		var c = BuildController.get();
		RunState st = c.state();
		if (row18Recording) {
			var planner = c.planner();
			for (BlockPos pos : ROW18_CELLS) {
				if (planner != null && !ROW18_VERIFIED.contains(pos) && planner.isVerified(pos)) {
					ROW18_VERIFIED.add(pos);
					ROW18_CONFIRMED_STANCES.add(mc.player.blockPosition());
					if (row18FirstConfirmedPos == null) {
						row18FirstConfirmedPos = mc.player.position();
					}
				}
			}
			if (row18FirstConfirmedPos == null && InputBroker.desired().forward()) {
				ROW18_FORWARD_BEFORE_CONFIRM.set(true);
			}
		}
		if (recordStances && (st == RunState.AIMING || st == RunState.WAITING)) {
			STANCES.add(mc.player.blockPosition());
			if (firstAimingTick < 0) {
				firstAimingTick = tickCounter;
			}
			lastActionTick = tickCounter;
		}
		if (recordSprint && st == RunState.WALKING && mc.player.isSprinting()) {
			SPRINT_SEEN.set(true);
		}
	}

	static {
		ClientTickEvents.END_CLIENT_TICK.register(LiteBuildSpeedTest::probeTick);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
		scenarioBatching(context);
		scenarioSprint(context);
		scenarioRow18(context);
	}

	// ------------------------------------------------------------------
	// A) 1x5 row next to the spawn — at most two placement stances
	// ------------------------------------------------------------------

	private void scenarioBatching(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			var conn = sp.getConnection();
			conn.waitForChunksRender();
			// Flat floor; row sits at z=1, x=0..4; spawn just south, centered.
			prepareWorld(sp, new BlockPos(-4, 63, -4), 16, 8,
					new Vec3d(2.5, 64, -1.5));
			Map<BlockPos, BlockState> row = new java.util.LinkedHashMap<>();
			for (int x = 0; x <= 4; x++) {
				row.put(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState());
			}
			BlockPos origin = new BlockPos(0, 64, 1);
			LitematicaSchematic schematic = captureSchematic(sp, row, origin);
			STANCES.clear();
			recordSprint = false;
			recordStances = true;
			firstAimingTick = -1;
			lastActionTick = -1;
			tickCounter = 0;
			context.runOnClient(mc -> {
				selectPlacement(schematic, origin, "gbspeedA");
				var cfg = BuildController.get().config();
				cfg.fluidsEnabled = false;
				cfg.activationEnabled = false;
				// Keep humanization off for a tight deterministic window;
				// batching itself is what this scenario measures.
				cfg.automaticPauses = false;
				BuildController.get().start();
			});
			awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE
					|| BuildController.get().state() == RunState.PAUSED,
					2400, "speedA");
			recordStances = false;
			if (BuildController.get().state() != RunState.COMPLETE) {
				throw new AssertionError("row not complete: "
						+ BuildController.get().statusLines());
			}
			System.out.println("[GT-SPEED] 1x5 row stances=" + STANCES.size()
					+ " " + STANCES + " actionTicks="
					+ (lastActionTick - firstAimingTick));
			if (STANCES.size() > 2) {
				throw new AssertionError("1x5 row used " + STANCES.size()
						+ " stances (<=2 expected): " + STANCES);
			}
			sp.getServer().runOnServer(server -> {
				for (int x = 0; x <= 4; x++) {
					assertBlock(server.overworld(), new BlockPos(x, 64, 1),
							Blocks.STONE, "row cell x=" + x);
				}
			});
			context.takeScreenshot("litebuild-speed-row");
		}
	}

	// ------------------------------------------------------------------
	// B) single target beyond an 8-block leg — sprint must be observed
	// ------------------------------------------------------------------

	// ------------------------------------------------------------------
	// C) 18-cell row from an off-centre spawn in the first block
	// ------------------------------------------------------------------

	private void scenarioRow18(ClientGameTestContext context) {
		final net.minecraft.world.phys.Vec3 spawn = new net.minecraft.world.phys.Vec3(0.23, 64, 0.23);
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			sp.getConnection().waitForChunksRender();
			prepareWorld(sp, new BlockPos(-4, 63, -4), 24, 8,
					new Vec3d(spawn.x, spawn.y, spawn.z));
			Map<BlockPos, BlockState> row = new java.util.LinkedHashMap<>();
			for (int x = 0; x < 18; x++) {
				row.put(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState());
			}
			BlockPos origin = new BlockPos(0, 64, 1);
			LitematicaSchematic schematic = captureSchematic(sp, row, origin);
			// The client must show the authoritative spawn before recording or starting.
			AtomicBoolean spawnSynced = new AtomicBoolean();
			for (int t = 0; t < 40 && !spawnSynced.get(); t++) {
				context.waitTicks(5);
				context.runOnClient(mc -> spawnSynced.set(mc.player != null
						&& mc.player.position().distanceTo(spawn) < 0.05));
			}
			check(spawnSynced.get(), "client spawn never synchronized to the fixture");
			try {
				context.runOnClient(mc -> {
					ROW18_CELLS.clear();
					for (int x = 0; x < 18; x++) {
						ROW18_CELLS.add(new BlockPos(x, 64, 1));
					}
					ROW18_VERIFIED.clear();
					ROW18_CONFIRMED_STANCES.clear();
					row18FirstConfirmedPos = null;
					ROW18_FORWARD_BEFORE_CONFIRM.set(false);
					recordSprint = false;
					recordStances = true;
					row18Recording = true;
					selectPlacement(schematic, origin, "gbspeedC");
					var cfg = BuildController.get().config();
					cfg.fluidsEnabled = false;
					cfg.activationEnabled = false;
					cfg.automaticPauses = false;
					BuildController.get().start();
				});
				awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE
						|| BuildController.get().state() == RunState.PAUSED,
						4800, "speedC");
			} finally {
				row18Recording = false;
				recordStances = false;
			}
			if (BuildController.get().state() != RunState.COMPLETE) {
				throw new AssertionError("row18 not complete: "
						+ BuildController.get().statusLines());
			}
			java.util.List<BlockPos> stances = new java.util.ArrayList<>(ROW18_CONFIRMED_STANCES);
			Set<BlockPos> distinct = new java.util.HashSet<>(stances);
			int longest = 0;
			int run = 0;
			for (int i = 0; i < stances.size(); i++) {
				run = i > 0 && stances.get(i).equals(stances.get(i - 1)) ? run + 1 : 1;
				longest = Math.max(longest, run);
			}
			net.minecraft.world.phys.Vec3 first = row18FirstConfirmedPos;
			double firstDist = first == null ? Double.MAX_VALUE
					: Math.hypot(first.x - spawn.x, first.z - spawn.z);
			System.out.println("[GT-SPEED] row18 confirmed=" + ROW18_VERIFIED.size()
					+ " distinctStances=" + distinct.size() + " longestRun=" + longest
					+ " firstStance=" + (stances.isEmpty() ? "-" : stances.get(0).toShortString())
					+ " firstConfirmedPos=" + first + " horizontalFromSpawn=" + firstDist
					+ " forwardBeforeConfirm=" + ROW18_FORWARD_BEFORE_CONFIRM.get());
			if (ROW18_VERIFIED.size() != 18) {
				throw new AssertionError("row18 confirmed " + ROW18_VERIFIED.size() + " (expected 18)");
			}
			if (ROW18_FORWARD_BEFORE_CONFIRM.get()) {
				throw new AssertionError("forward movement requested before the first confirmation");
			}
			if (first == null || firstDist > 0.05) {
				throw new AssertionError("first confirmed placement moved away from the offset spawn: "
						+ first);
			}
			if (stances.isEmpty() || !stances.get(0).equals(new BlockPos(0, 64, 0))) {
				throw new AssertionError("first batch relocated away from the spawn block: " + stances);
			}
			if (distinct.size() > 6) {
				throw new AssertionError("row18 used " + distinct.size() + " stances (<=6): " + distinct);
			}
			if (longest < 3) {
				throw new AssertionError("no run of 3 consecutive cells from one stance: " + stances);
			}
			sp.getServer().runOnServer(server -> {
				int stones = 0;
				for (int x = 0; x < 18; x++) {
					if (server.overworld().getBlockState(new BlockPos(x, 64, 1)).getBlock() == Blocks.STONE) {
						stones++;
					}
				}
				if (stones != 18) {
					throw new AssertionError("server row18 has " + stones + " stones (expected 18)");
				}
			});
			context.takeScreenshot("litebuild-speed-row18");
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}

	private void scenarioSprint(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			var conn = sp.getConnection();
			conn.waitForChunksRender();
			prepareWorld(sp, new BlockPos(-2, 63, -2), 18, 6,
					new Vec3d(0.5, 64, 0.5));
			Map<BlockPos, BlockState> lone = Map.of(
					new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
			BlockPos origin = new BlockPos(11, 64, 0); // ~11 blocks ahead
			LitematicaSchematic schematic = captureSchematic(sp, lone, origin);
			SPRINT_SEEN.set(false);
			recordStances = false;
			recordSprint = true;
			context.runOnClient(mc -> {
				selectPlacement(schematic, origin, "gbspeedB");
				var cfg = BuildController.get().config();
				cfg.fluidsEnabled = false;
				cfg.activationEnabled = false;
				cfg.automaticPauses = false;
				cfg.sprintOnLongWalks = true;
				BuildController.get().start();
			});
			awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE
					|| BuildController.get().state() == RunState.PAUSED,
					2400, "speedB");
			recordSprint = false;
			if (BuildController.get().state() != RunState.COMPLETE) {
				throw new AssertionError("far target not complete: "
						+ BuildController.get().statusLines());
			}
			if (!SPRINT_SEEN.get()) {
				throw new AssertionError("player never sprinted during the "
						+ ">=8-block relocation leg");
			}
			sp.getServer().runOnServer(server -> assertBlock(server.overworld(),
					new BlockPos(11, 64, 0), Blocks.STONE, "far target"));
			System.out.println("[GT-SPEED] sprint observed on long leg");
			context.takeScreenshot("litebuild-speed-sprint");
		}
	}

	// ------------------------------------------------------------------
	// Fixture helpers (same technique as the other scenarios)
	// ------------------------------------------------------------------

	private record Vec3d(double x, double y, double z) {}

	private void prepareWorld(TestSingleplayerContext sp, BlockPos floorOrigin,
							  int w, int d, Vec3d spawn) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			server.setDifficulty(Difficulty.PEACEFUL, true);
			for (int x = 0; x < w; x++) {
				for (int z = 0; z < d; z++) {
					BlockPos f = floorOrigin.offset(x, 0, z);
					level.setBlock(f, Blocks.STONE.defaultBlockState(), 3);
					for (int y = 1; y <= 16; y++) {
						level.setBlock(f.above(y), Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			var player = sp.getConnection().getServerPlayer();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(spawn.x(), spawn.y(), spawn.z());
			var inv = player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.STONE, 64));
		});
	}

	private void selectPlacement(LitematicaSchematic schematic,
								 BlockPos origin, String name) {
		var mgr = DataManager.getSchematicPlacementManager();
		for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) {
			mgr.removeSchematicPlacement(old);
		}
		BlockPos rel = schematic.getAreaPositions().get("A");
		SchematicPlacement placement = SchematicPlacement.createFor(
				schematic, origin.subtract(rel), name, true, true);
		mgr.addSchematicPlacement(placement, true);
		mgr.setSelectedSchematicPlacement(placement);
	}

	private LitematicaSchematic captureSchematic(TestSingleplayerContext sp,
												 Map<BlockPos, BlockState> desired,
												 BlockPos origin) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		BlockPos stage = origin.above(STAGE_DY);
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			desired.forEach((local, state) ->
					level.setBlock(local.offset(stage), state, 3));
			var sel = new AreaSelection();
			sel.setExplicitOrigin(stage);
			int maxX = 0, maxY = 0, maxZ = 0;
			for (BlockPos local : desired.keySet()) {
				maxX = Math.max(maxX, local.getX());
				maxY = Math.max(maxY, local.getY());
				maxZ = Math.max(maxZ, local.getZ());
			}
			var box = new Box(stage, stage.offset(maxX, maxY, maxZ), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbspeed", s -> { });
			ref.set(schem);
			desired.keySet().forEach(local ->
					level.setBlock(local.offset(stage),
							Blocks.AIR.defaultBlockState(), 3));
		});
		if (ref.get() == null) {
			throw new AssertionError("schematic capture failed");
		}
		return ref.get();
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
				context.takeScreenshot("litebuild-" + label + "-end");
			} catch (Throwable ignored) {
			}
		}
		throw new AssertionError("timeout waiting for " + label
				+ " status=" + BuildController.get().statusLines()
				+ " issues=" + BuildController.get().issues().stream()
				.map(Object::toString).limit(8).toList());
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
