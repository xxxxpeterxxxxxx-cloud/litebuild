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
import dev.litebuild.model.RunState;

/**
 * Scenario 4 — delayed and missing server acknowledgements. A test-only
 * gate (ClientboundAckGateMixin + AckGate) withholds the clientbound block
 * updates that drive the AckTracker for chosen positions. Withheld updates
 * also revert the optimistic prediction via the normal block-changed-ack,
 * so the controller sees exactly what a laggy/silent server looks like:
 * the optimistic block disappears and no ack ever lands.
 *
 *   A) HOLD one cell beyond confirmationTimeoutTicks: never verified,
 *      never COMPLETE; the bounded re-queue/pause path runs and after
 *      release the build finishes cleanly.
 *   B) DROP all cells: the run must end in bounded PAUSED — never
 *      COMPLETE, never an endless placement loop; release + verify
 *      recovers to COMPLETE.
 *   C) HOLD the ack for an owned remove (BREAK_REPAIR): observed air may
 *      count as done but the run must still reach a bounded terminal
 *      state; after release the audit settles COMPLETE.
 */
public final class LiteBuildAckTest implements FabricClientGameTest {
	// Blueprint cells (world coords): stone x0..4 at 64,0; region
	// x0..7,y64..65,z0 — the AIR cell 7,64,0 hosts the break fixture.
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
	private static final int STAGE_DY = 140;
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	private static final BlockPos GATED = new BlockPos(4, 64, 0);  // last in build order
	private static final BlockPos AIR_CELL = new BlockPos(7, 64, 0);
	private static final int ACK_TIMEOUT = 30;  // confirmationTimeoutTicks override

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
					schematic, ORIGIN.subtract(rel), "gback", true, true);
			mgr.addSchematicPlacement(placement, true);
			mgr.setSelectedSchematicPlacement(placement);
			var cfg = BuildController.get().config();
			cfg.automaticPauses = false;  // focused ack test
			cfg.automaticRepairs = true;
			cfg.maxRepairRounds = 3;
			cfg.confirmationTimeoutTicks = ACK_TIMEOUT;
			// Phase A armed up front: the four leading cells ack normally,
			// only the last one's confirmation is withheld.
			AckGate.hold(GATED);
			BuildController.get().start();
		});

		// ---- A) delayed ack --------------------------------------------
		// Wait until at least one server confirmation for the gated cell
		// was actually received and withheld — proof the server accepted
		// the placement while the client must not trust it.
		awaitDiag(context, () -> AckGate.suppressed() >= 1, 2400, "ack-hold-first");

		// Let the transaction run well past confirmationTimeoutTicks. The
		// gated cell must stay unverified and the run must never complete:
		// re-queue while other acks flowed, else the silent-server pause.
		// The bounded branch ("Late acknowledgement"/"Server
		// acknowledgement missing") only fires once NO acknowledgement
		// has been confirmed for a long silence window (400 ticks) —
		// retries of the withheld ack keep retrying until then, so the
		// budget must cover timeout + silence, not just 3 timeouts.
		boolean terminal = false;
		for (int t = 0; t < 1200 && !terminal; t += 10) {
			context.waitTicks(10);
			var c = BuildController.get();
			if (c.state() == RunState.COMPLETE) {
				throw new AssertionError("completed without the gated ack: "
						+ c.diagnosticState());
			}
			var planner = c.planner();
			// Empty targetsAt = a scoped repair planner that does not
			// contain the cell — not proof of verification.
			if (planner != null && !planner.targetsAt(GATED).isEmpty()
					&& planner.isVerified(GATED)) {
				throw new AssertionError("verified without the gated ack: "
						+ c.diagnosticState());
			}
			terminal = c.statusLines().toString().contains("acknowledgement");
		}
		String lines = BuildController.get().statusLines().toString();
		if (!terminal) {
			throw new AssertionError("no ack-timeout branch observed: " + lines);
		}
		System.out.println("[GT-ACK] delayed-ack behaviour: "
				+ BuildController.get().diagnosticState());
		context.takeScreenshot("litebuild-ack-withheld");

		// Release the withheld acknowledgement: the build must now settle
		// cleanly — either the pending transaction confirms or the re-pick
		// verifies the already-visible cell.
		context.runOnClient(mc -> {
			int n = AckGate.release();
			System.out.println("[GT-ACK] released " + n + " held updates");
			if (BuildController.get().state() == RunState.PAUSED) {
				BuildController.get().start();
			}
		});
		awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
				2400, "ack-delayed-complete");
		assertWorld(sp, GATED, Blocks.STONE, "gated cell placed after release");

		// ---- B) missing acks -------------------------------------------
		// Server-side remove every stone (client sees the removal — gate
		// still off), then drop ALL acknowledgements and re-verify. The
		// repair must reach a bounded PAUSED: soft retries expire once no
		// confirmation has flowed for a while. Never COMPLETE.
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			for (int x = 0; x <= 4; x++) {
				level.setBlock(new BlockPos(x, 64, 0),
						Blocks.AIR.defaultBlockState(), 3);
			}
		});
		// The client must SEE the removals before the gate arms — otherwise
		// a withheld packet would hide them and the audit reads a stale world.
		context.waitFor(mc -> mc.level != null
				&& mc.level.getBlockState(GATED).isAir(), 400);
		context.runOnClient(mc -> {
			AckGate.drop(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
					new BlockPos(2, 64, 0), new BlockPos(3, 64, 0), GATED);
			BuildController.get().verify();
		});
		awaitDiag(context, () -> BuildController.get().state() == RunState.PAUSED,
				3000, "ack-drop-pause");
		if (AckGate.suppressed() < 1) {
			throw new AssertionError("no acknowledgement was actually dropped");
		}
		System.out.println("[GT-ACK] drop -> bounded pause: "
				+ BuildController.get().diagnosticState());
		context.takeScreenshot("litebuild-ack-drop-paused");

		// Release the silence: an explicit verify rebuilds everything.
		context.runOnClient(mc -> {
			AckGate.off();
			BuildController.get().verify();
		});
		awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
				3000, "ack-drop-recovered");
		assertWorld(sp, GATED, Blocks.STONE, "stone restored after drop window");

		// ---- C) break ack missing --------------------------------------
		// Fabricate an OWNED stray block in a required-AIR cell (journal
		// access is test-only): the audit must approve a REMOVE through the
		// real BREAK_REPAIR path. Withholding the air ack must still end
		// bounded — observed air counts as done at the break timeout and
		// the follow-up audit decides, never an endless attack loop.
		sp.getServer().runOnServer(server ->
				server.overworld().setBlock(AIR_CELL,
						Blocks.DIRT.defaultBlockState(), 3));
		// Client must see the stray block before the gate arms.
		context.waitFor(mc -> mc.level != null
				&& mc.level.getBlockState(AIR_CELL).is(Blocks.DIRT), 400);
		context.runOnClient(mc -> {
			((BuildControllerAccess) BuildController.get()).litebuild$journal()
					.recordConfirmed(AIR_CELL, Blocks.AIR.defaultBlockState(),
							Blocks.DIRT.defaultBlockState());
			AckGate.hold(AIR_CELL);
			BuildController.get().verify();
		});
		// The break transaction's own timeout is max(conf, 200); observed
		// air counts as done there. Whether the follow-up audit still sees
		// predicted air (clean -> COMPLETE) or reverted dirt (manual ->
		// PAUSED), the run must settle — never an endless attack loop.
		awaitDiag(context, () -> {
			RunState st = BuildController.get().state();
			return st == RunState.PAUSED || st == RunState.COMPLETE;
		}, 3000, "ack-break-bounded");
		if (AckGate.suppressed() < 1) {
			throw new AssertionError("break ran without any withheld ack");
		}
		System.out.println("[GT-ACK] break ack withheld -> bounded "
				+ BuildController.get().state() + ": "
				+ BuildController.get().diagnosticState());
		context.takeScreenshot("litebuild-ack-break-bounded");

		// Release: the server broke the block long ago — after the held
		// air update lands the audit settles COMPLETE.
		context.runOnClient(mc -> {
			int n = AckGate.release();
			System.out.println("[GT-ACK] released " + n + " held break updates");
			BuildController.get().verify();
		});
		awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
				1200, "ack-break-complete");
		assertWorld(sp, AIR_CELL, Blocks.AIR, "stray block removed");
		context.takeScreenshot("litebuild-ack-complete");
	}

	private void awaitDiag(ClientGameTestContext context,
						   java.util.function.BooleanSupplier cond,
						   int maxTicks, String label) {
		try {
			for (int t = 0; t < maxTicks / 20; t++) {
				context.waitTicks(20);
				context.runOnClient(mc ->
						System.out.println("[GT-ACK-" + label + "] "
								+ BuildController.get().diagnosticState()));
				if (cond.getAsBoolean()) {
					return;
				}
			}
		} finally {
			try {
				context.takeScreenshot("litebuild-ack-" + label + "-end");
			} catch (Throwable ignored) {
			}
		}
		throw new AssertionError("timeout waiting for " + label
				+ " " + BuildController.get().diagnosticState());
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
					"gback", s -> { });
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
