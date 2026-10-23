package dev.gentlebuild.gametest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

/**
 * Incremental construction checkpoints (beta.4). A 12-stone row is built
 * through the production controller. The first checkpoint must cover exactly
 * the first 10 completed cells; a server-side removal of one verified cell is
 * injected while that checkpoint's settle window is open. The checkpoint must
 * repair it through the normal action path, the tail batch must follow, and
 * the global final audit must still produce COMPLETE. Full audits may only
 * run after the tail checkpoint.
 */
public final class GentleBuildCheckpointTest implements FabricClientGameTest {
	private static final int STAGE_DY = 136;
	private static final int ROW = 12;
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 1);
	private static final int SETTLE_TICKS = 20;
	private static final int FIRST_CHECKPOINT_CELLS = 10;

	private static final AtomicBoolean RECORDING = new AtomicBoolean();
	private static final AtomicBoolean REPAIR_SEEN = new AtomicBoolean();
	private static final AtomicBoolean PAUSE_SEEN = new AtomicBoolean();
	private static volatile String pauseStatus = "";
	private static volatile RunState lastState;
	private static volatile int lastRuns = -1;

	private static void probeTick(Minecraft mc) {
		var c = BuildController.get();
		RunState st = c.state();
		if (!RECORDING.get()) {
			return;
		}
		if (st == RunState.PAUSED) {
			PAUSE_SEEN.set(true);
			pauseStatus = c.statusLines().toString();
		}
		if (c.checkpointRuns() == 1 && st == RunState.VERIFYING
				&& c.statusLines().toString().contains("Repair 1/")) {
			REPAIR_SEEN.set(true);
		}
		if (st != lastState || c.checkpointRuns() != lastRuns) {
			lastState = st;
			lastRuns = c.checkpointRuns();
			System.out.println("[GTCP] state=" + st + " checkpointRuns=" + lastRuns
					+ " fullAuditRuns=" + c.fullAuditRuns()
					+ " repairRound=" + c.repairRound()
					+ " status=" + c.statusLines());
		}
	}

	static {
		ClientTickEvents.END_CLIENT_TICK.register(GentleBuildCheckpointTest::probeTick);
	}

	private record Saved(boolean automaticPauses, boolean automaticRepairs, int settleTicks) {}

	private static void resetProbeState() {
		RECORDING.set(false);
		REPAIR_SEEN.set(false);
		PAUSE_SEEN.set(false);
		pauseStatus = "";
		lastState = null;
		lastRuns = -1;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
		resetProbeState();
		AtomicReference<Saved> saved = new AtomicReference<>();
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			sp.getConnection().waitForChunksRender();
			prepareWorld(sp);
			LitematicaSchematic schematic = captureSchematic(sp);
			context.runOnClient(mc -> {
				var cfg = BuildController.get().config();
				saved.set(new Saved(cfg.automaticPauses, cfg.automaticRepairs, cfg.settleTicks));
				selectPlacement(schematic);
				cfg.fluidsEnabled = false;
				cfg.activationEnabled = false;
				cfg.automaticPauses = false;
				cfg.automaticRepairs = true;
				cfg.settleTicks = SETTLE_TICKS;
				RECORDING.set(true);
				BuildController.get().start();
			});
			try {
				runScenario(context, sp);
			} finally {
				RECORDING.set(false);
				context.runOnClient(mc -> {
					var cfg = BuildController.get().config();
					Saved s = saved.get();
					cfg.automaticPauses = s.automaticPauses();
					cfg.automaticRepairs = s.automaticRepairs();
					cfg.settleTicks = s.settleTicks();
				});
			}
		}
	}

	private void runScenario(ClientGameTestContext context, TestSingleplayerContext sp) {
		// 1) The first checkpoint must cover exactly the first 10 completed cells.
		awaitUntil(context, () -> BuildController.get().checkpointRuns() >= 1
				&& BuildController.get().state() == RunState.VERIFYING,
				2400, 1, "first-checkpoint");
		if (BuildController.get().fullAuditRuns() != 0) {
			throw new AssertionError("full audit ran before the first checkpoint finished: "
					+ BuildController.get().fullAuditRuns());
		}
		AtomicReference<String> status = new AtomicReference<>();
		AtomicInteger victimX = new AtomicInteger(-1);
		AtomicInteger runsBeforeResume = new AtomicInteger();
		AtomicReference<RunState> stateBeforeResume = new AtomicReference<>();
		context.runOnClient(mc -> {
			var c = BuildController.get();
			status.set(c.statusLines().toString());
			for (int x = 0; x < ROW && victimX.get() < 0; x++) {
				if (c.planner().isVerified(ORIGIN.offset(x, 0, 0))) {
					victimX.set(x);
				}
			}
			// Resume during a checkpoint is a no-op: the audit keeps running.
			runsBeforeResume.set(c.checkpointRuns());
			stateBeforeResume.set(c.state());
			c.resume();
			if (c.state() != RunState.VERIFYING
					|| c.checkpointRuns() != runsBeforeResume.get()) {
				throw new AssertionError("resume during checkpoint changed state: "
						+ c.state() + " runs=" + c.checkpointRuns());
			}
		});
		System.out.println("[GTCP] resume during checkpoint: state "
				+ stateBeforeResume.get() + " -> " + BuildController.get().state()
				+ " (no-op)");
		String first = status.get();
		if (!first.contains("Checkpoint: " + FIRST_CHECKPOINT_CELLS + " blocks")) {
			throw new AssertionError("first checkpoint not 10 cells: " + first);
		}
		if (victimX.get() < 0) {
			throw new AssertionError("no verified cell in the first checkpoint");
		}
		BlockPos victim = ORIGIN.offset(victimX.get(), 0, 0);
		System.out.println("[GTCP] first checkpoint cells=" + FIRST_CHECKPOINT_CELLS
				+ " victim=" + victim.toShortString() + " status=" + first);

		// 2) Server-side removal of that verified cell, inside the settle window.
		sp.getServer().runOnServer(server ->
				server.overworld().setBlock(victim, Blocks.AIR.defaultBlockState(), 3));
		System.out.println("[GTCP] injected removal at " + victim.toShortString()
				+ " while checkpoint in progress");

		// 3) The checkpoint repairs it; the tail batch follows; then the global audit.
		awaitUntil(context, () -> BuildController.get().checkpointRuns() >= 2
				&& BuildController.get().state() == RunState.VERIFYING,
				4800, 2, "tail-checkpoint");
		if (BuildController.get().fullAuditRuns() != 0) {
			throw new AssertionError("global audit ran before the tail checkpoint: "
					+ BuildController.get().fullAuditRuns());
		}
		awaitUntil(context, () -> BuildController.get().state() == RunState.COMPLETE,
				4800, 5, "global-complete");
		context.takeScreenshot("gentlebuild-checkpoint-complete");

		var c = BuildController.get();
		if (!REPAIR_SEEN.get()) {
			throw new AssertionError("checkpoint never repaired the injected removal: "
					+ c.statusLines());
		}
		if (c.repairRound() != 0) {
			throw new AssertionError("scoped repair consumed the global budget: " + c.repairRound());
		}
		if (c.checkpointRuns() < 2) {
			throw new AssertionError("expected 10 + tail checkpoints, got " + c.checkpointRuns());
		}
		if (c.fullAuditRuns() < 1) {
			throw new AssertionError("global final audit never ran");
		}
		if (PAUSE_SEEN.get()) {
			throw new AssertionError("unexpected pause: " + pauseStatus);
		}
		System.out.println("[GTCP] done checkpointRuns=" + c.checkpointRuns()
				+ " fullAuditRuns=" + c.fullAuditRuns() + " repairRound=" + c.repairRound()
				+ " repairedInCheckpoint=" + REPAIR_SEEN.get());
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			for (int x = 0; x < ROW; x++) {
				assertBlock(level, ORIGIN.offset(x, 0, 0), Blocks.STONE, "row x=" + x);
			}
		});
	}

	private void awaitUntil(ClientGameTestContext context,
							java.util.function.BooleanSupplier cond,
							int maxTicks, int step, String label) {
		for (int t = 0; t < maxTicks; t += step) {
			context.waitTicks(step);
			if (PAUSE_SEEN.get()) {
				throw new AssertionError("unexpected PAUSED during " + label + ": " + pauseStatus);
			}
			if (cond.getAsBoolean()) {
				return;
			}
		}
		throw new AssertionError("timeout waiting for " + label + " status="
				+ BuildController.get().statusLines());
	}

	private void prepareWorld(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			server.setDifficulty(Difficulty.PEACEFUL, true);
			BlockPos floorOrigin = new BlockPos(-4, 63, -4);
			for (int x = 0; x < 20; x++) {
				for (int z = 0; z < 12; z++) {
					BlockPos f = floorOrigin.offset(x, 0, z);
					level.setBlock(f, Blocks.STONE.defaultBlockState(), 3);
					for (int y = 1; y <= 16; y++) {
						level.setBlock(f.above(y), Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			var player = sp.getConnection().getServerPlayer();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(2.5, 64, -1.5);
			var inv = player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.STONE, 64));
		});
	}

	private void selectPlacement(LitematicaSchematic schematic) {
		var mgr = DataManager.getSchematicPlacementManager();
		for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) {
			mgr.removeSchematicPlacement(old);
		}
		BlockPos rel = schematic.getAreaPositions().get("A");
		SchematicPlacement placement = SchematicPlacement.createFor(
				schematic, ORIGIN.subtract(rel), "gbcheckpoint", true, true);
		mgr.addSchematicPlacement(placement, true);
		mgr.setSelectedSchematicPlacement(placement);
	}

	private LitematicaSchematic captureSchematic(TestSingleplayerContext sp) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		Map<BlockPos, BlockState> desired = new java.util.LinkedHashMap<>();
		for (int x = 0; x < ROW; x++) {
			desired.put(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState());
		}
		BlockPos stage = ORIGIN.above(STAGE_DY);
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			desired.forEach((local, state) ->
					level.setBlock(local.offset(stage), state, 3));
			var sel = new AreaSelection();
			sel.setExplicitOrigin(stage);
			var box = new Box(stage, stage.offset(ROW - 1, 0, 0), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbcheckpoint", s -> { });
			ref.set(schem);
			desired.keySet().forEach(local ->
					level.setBlock(local.offset(stage), Blocks.AIR.defaultBlockState(), 3));
		});
		if (ref.get() == null) {
			throw new AssertionError("schematic capture failed");
		}
		return ref.get();
	}

	private static void assertBlock(net.minecraft.world.level.Level level, BlockPos pos,
									net.minecraft.world.level.block.Block wanted, String what) {
		if (level.getBlockState(pos).getBlock() != wanted) {
			throw new AssertionError(what + " at " + pos.toShortString() + ": "
					+ level.getBlockState(pos).getBlock() + " != " + wanted);
		}
	}
}
