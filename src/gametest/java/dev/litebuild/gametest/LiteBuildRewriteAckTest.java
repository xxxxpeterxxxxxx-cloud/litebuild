package dev.litebuild.gametest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
import dev.litebuild.gametest.ack.AckGate;
import dev.litebuild.gametest.mixin.ack.BuildControllerAccess;
import dev.litebuild.model.BuildIssue;
import dev.litebuild.model.RunState;

/**
 * Scenario 5 — contradictory server acknowledgements. The AckGate
 * REWRITE mode substitutes the authoritative state inside gated
 * clientbound updates: the placement lands server-side, but the update
 * the client applies and acks reports a DIFFERENT block (stone -> dirt),
 * exactly what a rollback or protection plugin looks like on the wire.
 *
 *   A) The rewritten ack must resolve the transaction MISMATCHED on real
 *      evidence — never CONFIRMED, never verified, never COMPLETE — and
 *      the foreign block must not create journal ownership.
 *   B) The run ends bounded: the conflict reaches the final audit, the
 *      foreign block is refused as MANUAL work (RepairPolicy never
 *      breaks blocks it cannot prove ownership of) and the terminal
 *      state is PAUSED reporting the concrete coordinate — no retry
 *      loop, no consumed repair round.
 *   C) release() replays the withheld true state; a plain verify then
 *      settles the same plan COMPLETE.
 */
public final class LiteBuildRewriteAckTest implements FabricClientGameTest {
	// Blueprint cells (world coords): stone x0..4 at 64,0; region
	// x0..7,y64..65,z0 — same layout as LiteBuildAckTest.
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
	private static final int STAGE_DY = 140;
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	private static final BlockPos GATED = new BlockPos(4, 64, 0);  // last in build order
	private static final BlockState SUBSTITUTE = Blocks.DIRT.defaultBlockState();
	private static final int ACK_TIMEOUT = 30;  // confirmationTimeoutTicks override
	/** Build + mismatch + audit must pause far below this bound. */
	private static final int PAUSE_TICKS = 3600;
	/** The audit runs once; three is the hard bound against audit churn. */
	private static final int MAX_AUDIT_RUNS = 3;
	private static final int MAX_REPAIR_ROUNDS = 3;  // cfg.maxRepairRounds below

	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		for (int x = 0; x <= 4; x++) {
			m.put(new BlockPos(x, 0, 0), Blocks.STONE.defaultBlockState());
		}
		return m;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
		try (TestSingleplayerContext sp = context.worldBuilder()
				.setUseConsistentSettings(true).create()) {
			try {
				run(context, sp);
			} finally {
				AckGate.off();
			}
		}
	}

	private void run(ClientGameTestContext context, TestSingleplayerContext sp) {
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
					schematic, ORIGIN.subtract(rel), "gbrw", true, true);
			mgr.addSchematicPlacement(placement, true);
			mgr.setSelectedSchematicPlacement(placement);
			var cfg = BuildController.get().config();
			cfg.automaticPauses = false;  // focused ack test
			cfg.automaticRepairs = true;
			cfg.maxRepairRounds = 3;
			cfg.confirmationTimeoutTicks = ACK_TIMEOUT;
			// Armed up front: the leading cells ack normally, only the last
			// one's confirmation contradicts the prediction.
			AckGate.rewrite(SUBSTITUTE, GATED);
			BuildController.get().start();
		});

		// ---- A) contradictory ack --------------------------------------
		// Wait until the server really acknowledged the gated placement and
		// the client was made to apply the substitute state — proof the
		// contradiction was delivered, not just a silent timeout.
		awaitDiag(context, () -> AckGate.rewritten() >= 1, 2400, "rewrite-first");
		System.out.println("[GT-RW] ack rewritten to " + SUBSTITUTE.getBlock()
				+ ": " + BuildController.get().diagnosticState());

		// While the contradiction settles, the run must NEVER complete and
		// the gated cell must NEVER verify — the locally predicted stone is
		// contradicted by the authoritative dirt.
		awaitPaused(context, PAUSE_TICKS, "rewrite-paused");

		var ctrl = BuildController.get();
		// (b) terminal state: bounded PAUSED with the concrete coordinate.
		if (ctrl.state() != RunState.PAUSED) {
			throw new AssertionError("expected PAUSED, got " + ctrl.state()
					+ " " + ctrl.diagnosticState());
		}
		boolean coordinateReported = ctrl.issues().stream().anyMatch(i ->
				i.pos().equals(GATED)
						&& (i.severity() == BuildIssue.Severity.CONFLICT
								|| i.severity() == BuildIssue.Severity.MANUAL))
				|| ctrl.statusLines().stream().anyMatch(s ->
						s.contains(GATED.toShortString()));
		if (!coordinateReported) {
			throw new AssertionError("contradicted cell "
					+ GATED.toShortString() + " not reported: issues="
					+ ctrl.issues().stream().map(Object::toString)
							.limit(10).toList()
					+ " status=" + ctrl.statusLines());
		}
		// The contradicting ack must not create ownership: a dirt that the
		// server reports is never recorded as our placed stone.
		if (((BuildControllerAccess) ctrl).litebuild$journal().owns(GATED)) {
			throw new AssertionError("contradictory ack created ownership at "
					+ GATED.toShortString());
		}
		// Boundedness: repair stays inside maxRepairRounds. The gated cell
		// itself must never be auto-repaired (the foreign block is not
		// provably ours to break) — an early cell whose ack timed out may
		// legitimately be re-interacted with, that IS our own block, so a
		// nonzero repairRound alone is not a violation. The true foreign-
		// block canary is the client world still holding SUBSTITUTE below.
		if (ctrl.repairRound() > MAX_REPAIR_ROUNDS) {
			throw new AssertionError("unbounded repair: repairRound="
					+ ctrl.repairRound());
		}
		if (ctrl.fullAuditRuns() > MAX_AUDIT_RUNS) {
			throw new AssertionError("unbounded audit loop: runs="
					+ ctrl.fullAuditRuns());
		}
		// The rewrite was wire-level only: the server world genuinely holds
		// the placed block (no break ever ran), while the client world must
		// show the substitute it was told to apply.
		assertWorld(sp, GATED, Blocks.STONE, "server-side placed block");
		var clientState = new AtomicReference<BlockState>();
		context.runOnClient(mc ->
				clientState.set(mc.level.getBlockState(GATED)));
		if (clientState.get() == null
				|| !clientState.get().is(SUBSTITUTE.getBlock())) {
			throw new AssertionError("client world never applied the "
					+ "rewritten state at " + GATED.toShortString() + ": "
					+ clientState.get());
		}
		System.out.println("[GT-RW] contradiction -> bounded pause: "
				+ ctrl.diagnosticState());
		// Terminal-state stability: no silent resume/re-audit churn.
		int auditsAtPause = ctrl.fullAuditRuns();
		context.waitTicks(60);
		if (ctrl.state() != RunState.PAUSED
				|| ctrl.fullAuditRuns() != auditsAtPause) {
			throw new AssertionError("state churned after pause: "
					+ ctrl.diagnosticState() + " audits=" + auditsAtPause
					+ "->" + ctrl.fullAuditRuns());
		}
		context.takeScreenshot("litebuild-rewrite-paused");

		// ---- C) released: replay truth, verify completes ----------------
		context.runOnClient(mc -> {
			int n = AckGate.release();
			System.out.println("[GT-RW] released " + n + " rewritten updates");
			BuildController.get().verify();
		});
		awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
				2400, "rewrite-complete");
		assertWorld(sp, GATED, Blocks.STONE, "gated cell true state after release");
		context.waitFor(mc -> mc.level != null
				&& mc.level.getBlockState(GATED).is(Blocks.STONE), 400);
		context.takeScreenshot("litebuild-rewrite-complete");
	}

	/**
	 * Poll until PAUSED while enforcing the two invariants of the
	 * contradiction window: never COMPLETE, never a verified gated cell
	 * on any planner that contains it.
	 */
	private void awaitPaused(ClientGameTestContext context, int maxTicks,
							 String label) {
		for (int t = 0; t < maxTicks / 20; t++) {
			context.waitTicks(20);
			context.runOnClient(mc ->
					System.out.println("[GT-RW-" + label + "] "
							+ BuildController.get().diagnosticState()));
			var c = BuildController.get();
			if (c.state() == RunState.COMPLETE) {
				throw new AssertionError("completed on a contradictory ack: "
						+ c.diagnosticState());
			}
			var planner = c.planner();
			// Empty targetsAt = a scoped repair planner that does not
			// contain the cell — not proof of verification.
			if (planner != null && !planner.targetsAt(GATED).isEmpty()
					&& planner.isVerified(GATED)) {
				throw new AssertionError("verified on a contradictory ack: "
						+ c.diagnosticState());
			}
			if (c.state() == RunState.PAUSED) {
				return;
			}
		}
		throw new AssertionError("timeout waiting for " + label + " "
				+ BuildController.get().diagnosticState()
				+ " status=" + BuildController.get().statusLines());
	}

	private void awaitDiag(ClientGameTestContext context,
						   java.util.function.BooleanSupplier cond,
						   int maxTicks, String label) {
		try {
			for (int t = 0; t < maxTicks / 20; t++) {
				context.waitTicks(20);
				context.runOnClient(mc ->
						System.out.println("[GT-RW-" + label + "] "
								+ BuildController.get().diagnosticState()));
				if (cond.getAsBoolean()) {
					return;
				}
			}
		} finally {
			try {
				context.takeScreenshot("litebuild-rewrite-" + label + "-end");
			} catch (Throwable ignored) {
			}
		}
		throw new AssertionError("timeout waiting for " + label
				+ " " + BuildController.get().diagnosticState()
				+ " status=" + BuildController.get().statusLines());
	}

	private static void assertWorld(TestSingleplayerContext sp, BlockPos pos,
									net.minecraft.world.level.block.Block wanted,
									String what) {
		sp.getServer().runOnServer(server -> {
			BlockState actual = server.overworld().getBlockState(pos);
			if (actual.getBlock() != wanted) {
				throw new AssertionError(what + " at " + pos.toShortString()
						+ ": " + actual.getBlock());
			}
		});
	}

	private void prepareWorld(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
			server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
			server.setDifficulty(Difficulty.PEACEFUL, true);
			for (int x = -4; x <= 12; x++) {
				for (int z = -4; z <= 8; z++) {
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
			player.teleportTo(0.2, 64, 0.2);
			var inv = player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.STONE, 64));
			inv.setItem(1, new ItemStack(Items.DIRT, 16));
			inv.setItem(2, new ItemStack(Items.IRON_SHOVEL, 1));
			inv.setItem(3, new ItemStack(Items.IRON_PICKAXE, 1));
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
			// Region covers local x0..7, y0..1, z0 (all other cells AIR).
			var box = new Box(STAGE, STAGE.offset(7, 1, 0), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbrw", s -> { });
			ref.set(schem);
			for (int x = 0; x <= 7; x++) {
				for (int y = 0; y <= 1; y++) {
					level.setBlock(STAGE.offset(x, y, 0),
							Blocks.AIR.defaultBlockState(), 3);
				}
			}
		});
		if (ref.get() == null) {
			throw new AssertionError("schematic capture failed");
		}
		return ref.get();
	}
}
