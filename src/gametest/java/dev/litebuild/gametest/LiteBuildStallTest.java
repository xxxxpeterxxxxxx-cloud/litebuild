package dev.litebuild.gametest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import dev.litebuild.build.BuildController;
import dev.litebuild.model.BuildIssue;
import dev.litebuild.model.RunState;

/**
 * Bounded-failure (stall) scenario: a small blueprint whose last cell is
 * geometrically UNSOLVABLE — a lone floating block with no adjacent face to
 * click and temporary supports disabled — must never loop forever. The mod
 * must bound its retries (placeAttemptAllowed -> markManual), still build
 * every reachable cell, run at most a bounded number of audit/repair rounds
 * and end PAUSED with the cell's concrete coordinate reported as manual
 * work. A per-tick observer additionally proves no single target consumes
 * unbounded ticks and the terminal state does not churn.
 * All construction goes through the production BuildController.
 */
public final class LiteBuildStallTest implements FabricClientGameTest {
	// local coords relative to ORIGIN (world 0,63,-4)
	private static final BlockPos ORIGIN = new BlockPos(0, 63, -4);
	private static final int STAGE_DY = 136;
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	/** Blueprint cell floating alone at y=70 with no neighbor in any
	 *  direction: PlacementSolver finds no face — unsolvable by design. */
	private static final BlockPos FLOATING = new BlockPos(6, 70, -4);
	private static final List<BlockPos> REACHABLE = List.of(
			new BlockPos(0, 63, -4), new BlockPos(1, 63, -4), new BlockPos(2, 63, -4));

	// Timebox bounds. The whole scenario is ~4 placements plus two audits;
	// anything close to these limits means something is looping.
	private static final int AWAIT_TICKS = 2400;
	private static final int TIMEBOX_TICKS = 1800;
	/** Continuous ticks one non-null hud target may occupy before we call
	 *  it a stall (walk+solve+aim+tx for one cell is well under 100). */
	private static final int MAX_SAME_TARGET_TICKS = 300;
	/** Times the floating cell may become the active target: 3 bounded
	 *  place attempts on the main planner + 3 on the repair planner. */
	private static final int MAX_CELL_ACTIVATIONS = 16;
	/** Audits expected: 1 post-build + 1 post-repair; 3 is the hard bound. */
	private static final int MAX_AUDIT_RUNS = 3;

	private static volatile boolean observing;
	private static volatile int observedTicks;
	private static volatile int sameTargetTicks;
	private static volatile int maxSameTargetTicks;
	private static volatile BlockPos lastTarget;
	private static final Map<BlockPos, Integer> ACTIVATIONS = new ConcurrentHashMap<>();

	private static void observeTick(Minecraft mc) {
		if (!observing) {
			return;
		}
		observedTicks++;
		BlockPos t = BuildController.get().hudInfo().target();
		if (t == null) {
			sameTargetTicks = 0;
			return;
		}
		if (t.equals(lastTarget)) {
			if (++sameTargetTicks > maxSameTargetTicks) {
				maxSameTargetTicks = sameTargetTicks;
			}
		} else {
			sameTargetTicks = 0;
			lastTarget = t;
			ACTIVATIONS.merge(t, 1, Integer::sum);
		}
	}

	static {
		ClientTickEvents.END_CLIENT_TICK.register(LiteBuildStallTest::observeTick);
	}

	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		m.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(6, 7, 0), Blocks.STONE.defaultBlockState()); // FLOATING-ORIGIN
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
			java.util.concurrent.atomic.AtomicBoolean spawnSynced =
					new java.util.concurrent.atomic.AtomicBoolean();
			for (int t = 0; t < 40 && !spawnSynced.get(); t++) {
				context.waitTicks(5);
				context.runOnClient(mc -> spawnSynced.set(mc.player != null
						&& mc.player.position().distanceTo(
								new net.minecraft.world.phys.Vec3(0.5, 63, -2.5)) < 0.05));
			}
			if (!spawnSynced.get()) {
				throw new AssertionError("client spawn never synchronized to (0.5, 63, -2.5)");
			}
			context.runOnClient(mc -> {
				var mgr = DataManager.getSchematicPlacementManager();
				for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) {
					mgr.removeSchematicPlacement(old);
				}
				SchematicPlacement placement = SchematicPlacement.createFor(
						schematic, ORIGIN.subtract(rel), "gbstall", true, true);
				mgr.addSchematicPlacement(placement, true);
				mgr.setSelectedSchematicPlacement(placement);
				var cfg = BuildController.get().config();
				cfg.fluidsEnabled = false;
				cfg.activationEnabled = false;
				cfg.allowTemporarySupports = false; // the floating cell stays unsolvable
				cfg.automaticRepairs = true;
				cfg.maxRepairRounds = 3;
				observedTicks = 0;
				sameTargetTicks = 0;
				maxSameTargetTicks = 0;
				lastTarget = null;
				ACTIVATIONS.clear();
				observing = true;
				BuildController.get().start();
			});

			try {
				awaitDiag(context, () -> BuildController.get().state() == RunState.PAUSED
						|| BuildController.get().state() == RunState.COMPLETE,
						AWAIT_TICKS, "stall");
			} finally {
				observing = false;
				context.runOnClient(mc ->
						BuildController.get().config().allowTemporarySupports = true);
			}
			System.out.println("[GT-STALL-OBS] ticks=" + observedTicks
					+ " maxSameTarget=" + maxSameTargetTicks
					+ " activations=" + ACTIVATIONS
					+ " audits=" + BuildController.get().fullAuditRuns()
					+ " repairRound=" + BuildController.get().repairRound());

			// (b) Timebox: bounded total work and bounded per-cell attention.
			if (observedTicks > TIMEBOX_TICKS) {
				throw new AssertionError("timebox exceeded: " + observedTicks
						+ " > " + TIMEBOX_TICKS + " ticks");
			}
			if (maxSameTargetTicks > MAX_SAME_TARGET_TICKS) {
				throw new AssertionError("a single target held the loop for "
						+ maxSameTargetTicks + " ticks");
			}
			int floatingActivations = ACTIVATIONS.getOrDefault(FLOATING, 0);
			if (floatingActivations > MAX_CELL_ACTIVATIONS) {
				throw new AssertionError("unsolvable cell activated "
						+ floatingActivations + " times — retry bound broken");
			}
			if (BuildController.get().fullAuditRuns() > MAX_AUDIT_RUNS) {
				throw new AssertionError("unbounded audit/repair loop: runs="
						+ BuildController.get().fullAuditRuns());
			}
			if (BuildController.get().repairRound()
					> BuildController.get().config().maxRepairRounds) {
				throw new AssertionError("repair rounds exceeded: "
						+ BuildController.get().repairRound());
			}

			// (a) Terminal state: PAUSED (a missing cell can never verify),
			// the floating cell flagged MANUAL/conflict with its coordinate.
			var ctrl = BuildController.get();
			if (ctrl.state() != RunState.PAUSED) {
				throw new AssertionError("expected PAUSED, got " + ctrl.state()
						+ " " + ctrl.diagnosticState());
			}
			boolean coordinateReported = ctrl.issues().stream().anyMatch(i ->
					i.pos().equals(FLOATING)
							&& (i.severity() == BuildIssue.Severity.MANUAL
									|| i.severity() == BuildIssue.Severity.CONFLICT))
					|| ctrl.statusLines().stream().anyMatch(s ->
							s.contains(FLOATING.toShortString()));
			if (!coordinateReported) {
				throw new AssertionError("unsolvable cell " + FLOATING.toShortString()
						+ " not reported as manual work: issues="
						+ ctrl.issues().stream().map(Object::toString).limit(10).toList()
						+ " status=" + ctrl.statusLines());
			}
			var planner = ctrl.planner();
			if (planner == null || !(planner.isManual(FLOATING)
					|| planner.isConflicted(FLOATING))) {
				throw new AssertionError("floating cell not marked manual/conflict"
						+ " on the active planner");
			}

			// Reachable cells must still have been built to completion.
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				for (BlockPos p : REACHABLE) {
					assertBlock(level, p, Blocks.STONE, "reachable cell left unbuilt");
				}
				assertBlock(level, FLOATING, Blocks.AIR,
						"unsolvable cell unexpectedly placed");
			});

			// Terminal-state stability: no silent resume/re-audit churn.
			int auditsAtPause = ctrl.fullAuditRuns();
			context.waitTicks(60);
			if (ctrl.state() != RunState.PAUSED
					|| ctrl.fullAuditRuns() != auditsAtPause) {
				throw new AssertionError("state churned after pause: "
						+ ctrl.diagnosticState() + " audits=" + auditsAtPause
						+ "->" + ctrl.fullAuditRuns());
			}
			context.takeScreenshot("litebuild-stall-paused");
		}
	}

	/** Wait for PAUSED/COMPLETE with per-second diagnostic dumps (timeout
	 *  prints the full state — the stall detector itself). */
	private void awaitDiag(ClientGameTestContext context,
						   java.util.function.BooleanSupplier cond,
						   int maxTicks, String label) {
		try {
			for (int t = 0; t < maxTicks / 20; t++) {
				context.waitTicks(20);
				context.runOnClient(mc -> {
					var c = BuildController.get();
					var pl = c.planner();
					System.out.println("[GT-" + label + "] "
							+ c.diagnosticState()
							+ " verified=" + (pl != null
									? pl.verifiedCount() + "/" + pl.totalTargets() : "?")
							+ " audits=" + c.fullAuditRuns()
							+ " repairs=" + c.repairRound());
				});
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
				+ " " + BuildController.get().diagnosticState()
				+ " status=" + BuildController.get().statusLines()
				+ " issues=" + BuildController.get().issues().stream()
						.map(Object::toString).limit(8).toList());
	}

	private void assertBlock(net.minecraft.world.level.Level level, BlockPos pos,
							 Object expected, String what) {
		if (expected instanceof net.minecraft.world.level.block.Block b) {
			if (level.getBlockState(pos).getBlock() != b) {
				throw new AssertionError(what + " at " + pos.toShortString() + ": "
						+ level.getBlockState(pos).getBlock());
			}
		} else {
			if (!level.getBlockState(pos).equals(expected)) {
				throw new AssertionError(what + " at " + pos.toShortString() + ": "
						+ level.getBlockState(pos));
			}
		}
	}

	private void prepareWorld(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			server.setDifficulty(Difficulty.PEACEFUL, true);
			// Flat stone floor at y=62, clear air y=63..80 (incl. the
			// floating blueprint cell at y=70 — no neighbor blocks at all).
			for (int x = -3; x <= 9; x++) {
				for (int z = -8; z <= 0; z++) {
					level.setBlock(new BlockPos(x, 62, z), Blocks.STONE.defaultBlockState(), 3);
					for (int y = 63; y <= 80; y++) {
						level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			var player = sp.getConnection().getServerPlayer();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(0.5, 63, -2.5);
			var inv = player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.STONE, 64));
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
			var box = new Box(STAGE, STAGE.offset(6, 7, 0), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbstall", s -> { });
			ref.set(schem);
			desired.keySet().forEach(local ->
					level.setBlock(local.offset(STAGE),
							Blocks.AIR.defaultBlockState(), 3));
		});
		return ref.get();
	}
}
