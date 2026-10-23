package dev.gentlebuild.gametest;

import java.util.EnumMap;
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
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import dev.gentlebuild.GentleBuildClient;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.model.BuildTarget;
import dev.gentlebuild.model.Phase;
import dev.gentlebuild.model.RunState;
import dev.gentlebuild.plan.Planner;

/**
 * Second scale/performance tier (extends the GentleBuildScaleTest evidence
 * upward): a ~1.2k-cell blueprint — a solid 20x15x4 stone podium (1200
 * cells) plus a redstone row (dust/repeaters) and a floor lever on its top
 * surface — is staged, captured through the real Litematica import and
 * built by the production BuildController in a flat arena.
 *
 * Asserts:
 *  - COMPLETE under a generous tick budget (regression bound, not a
 *    microbenchmark — this machine is slow under load);
 *  - THREE emergency pause cycles via the real H hotkey path: each pause
 *    halts the run with zero planner progress while PAUSED, and an
 *    explicit resume (fresh planner rebuild each time) continues the
 *    build to completion.
 *
 * Metrics are logged as [GTBIG] lines: verified count at each pause,
 * each phase's drained tick + wall ms (post-final-resume planner),
 * total wall time, total ticks and heap used before/after — INFO only,
 * never asserted.
 *
 * NOTE: this class must be listed under the "fabric-client-gametest"
 * entrypoints in src/gametest/resources/fabric.mod.json to be picked up
 * by the test harness (registration is intentionally left to the
 * maintainer — the resource file is not edited by this change).
 */
public final class GentleBuildBigBuildTest implements FabricClientGameTest {
	private static final BlockPos FLOOR_ORIGIN = new BlockPos(-8, 63, -8);
	private static final int FLOOR_W = 40, FLOOR_D = 40;
	private static final int STAGE_DY = 136;
	private static final BlockPos ORIGIN = new BlockPos(2, 64, 2); // local (0,0,0)
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	private static final BlockPos BOX_MAX = new BlockPos(19, 4, 14);

	private static final int WALL_W = 20;   // x 0..19
	private static final int WALL_D = 15;   // z 0..14
	private static final int WALL_H = 4;    // y 0..3 -> 1200 cells

	/** Verified counts at which the three H-key pauses are injected. */
	private static final int[] PAUSE_AT = { 80, 400, 750 };
	/** Per-stage budget to reach each pause threshold (generous). */
	private static final int STAGE_TICKS = 40000;
	/** Post-resume completion budget (~75 min at 20 tps — generous). */
	private static final int BUILD_TICKS = 90000;
	/** Ticks observed while PAUSED to prove the halt. */
	private static final int HALT_OBSERVE_TICKS = 60;

	/** Interesting world cells for spot verification. */
	private static final BlockPos WALL_CORNER = ORIGIN.offset(19, 0, 14);
	private static final BlockPos WALL_TOP = ORIGIN.offset(19, 3, 14);
	private static final BlockPos REPEATER_A = ORIGIN.offset(6, 4, 7);
	private static final BlockPos REPEATER_B = ORIGIN.offset(8, 4, 7);
	private static final BlockPos LEVER = ORIGIN.offset(10, 4, 7);

	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		// Solid 20x15x4 podium (1200 cells) — forces relocation legs and
		// stances on the build's own top surface for the interior.
		for (int y = 0; y < WALL_H; y++) {
			for (int x = 0; x < WALL_W; x++) {
				for (int z = 0; z < WALL_D; z++) {
					m.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
				}
			}
		}
		// Redstone row on the podium top, z=7: dust - repeater(delay 2,
		// east) - dust - repeater(delay 4) - dust. No power source: every
		// captured state stays valid.
		m.put(new BlockPos(5, 4, 7), Blocks.REDSTONE_WIRE.defaultBlockState());
		m.put(new BlockPos(6, 4, 7), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DELAY, 2));
		m.put(new BlockPos(7, 4, 7), Blocks.REDSTONE_WIRE.defaultBlockState());
		m.put(new BlockPos(8, 4, 7), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DELAY, 4));
		m.put(new BlockPos(9, 4, 7), Blocks.REDSTONE_WIRE.defaultBlockState());
		// Activation-phase tail: a floor lever (OFF).
		m.put(new BlockPos(10, 4, 7), Blocks.LEVER.defaultBlockState()
				.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.POWERED, false));
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

			Runtime rt = Runtime.getRuntime();
			long memBefore = rt.totalMemory() - rt.freeMemory();
			long wall0 = System.currentTimeMillis();
			waited = 0;
			System.out.println("[GTBIG] start memUsedMB=" + (memBefore >> 20)
					+ " cells=" + desiredBlocks().size());

			BlockPos rel = schematic.getAreaPositions().get("A");
			context.runOnClient(mc -> {
				var mgr = DataManager.getSchematicPlacementManager();
				for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) {
					mgr.removeSchematicPlacement(old);
				}
				SchematicPlacement placement = SchematicPlacement.createFor(
						schematic, ORIGIN.subtract(rel), "gbbig", true, true);
				mgr.addSchematicPlacement(placement, true);
				mgr.setSelectedSchematicPlacement(placement);
				var cfg = BuildController.get().config();
				// Deterministic cadence: no humanized rests inside the
				// measured window; sprint keeps relocation legs short.
				cfg.automaticPauses = false;
				cfg.sprintOnLongWalks = true;
				// The shared test box lags hard under llvmpipe (60-300 ticks
				// behind): give acks real headroom so transactions do not
				// time out into requeues, and converge genuine stalls fast.
				cfg.confirmationTimeoutTicks = 200;
				cfg.stallAuditTicks = 120;
				BuildController.get().start();
				// importSelected() resets the per-build gates synchronously,
				// so they must be opened AFTER start().
				cfg.fluidsEnabled = true;   // no fluid cells, but keep all gates open
				cfg.activationEnabled = true;
			});

			// ---- pause/resume cycles via the real H hotkey -------------
			for (int i = 0; i < PAUSE_AT.length; i++) {
				String label = "cycle" + (i + 1);
				final int threshold = PAUSE_AT[i];
				awaitUntil(context, () -> verified() >= threshold
								|| terminal(), STAGE_TICKS, label);
				if (verified() < threshold) {
					throw new AssertionError("build never reached "
							+ threshold + " verified: state="
							+ BuildController.get().state()
							+ " " + BuildController.get().diagnosticState());
				}
				context.getInput().pressKey(GentleBuildClient.pauseKey);
				waitTicks(context, 15);
				assertState(RunState.PAUSED, label + "-hotkey");
				int verifiedAtPause = verified();
				waitTicks(context, HALT_OBSERVE_TICKS);
				int verifiedAfterHalt = verified();
				System.out.println("[GTBIG] pause " + label
						+ " verifiedAtPause=" + verifiedAtPause
						+ " afterHalt=" + verifiedAfterHalt
						+ " observeTicks=" + HALT_OBSERVE_TICKS);
				if (verifiedAfterHalt != verifiedAtPause) {
					throw new AssertionError("paused build kept working: "
							+ verifiedAtPause + " -> " + verifiedAfterHalt);
				}
				assertState(RunState.PAUSED, label + "-halt-observe");
				context.runOnClient(mc -> {
					var cfg = BuildController.get().config();
					cfg.fluidsEnabled = true;
					cfg.activationEnabled = true;
					BuildController.get().start();
				});
			}

			// ---- final resume -> COMPLETE, per-phase metrics -----------
			EnumMap<Phase, long[]> drained = new EnumMap<>(Phase.class);
			int tick = 0;
			long resume0 = System.currentTimeMillis();
			for (; tick < BUILD_TICKS / 20; tick++) {
				waitTicks(context, 20);
				RunState st = BuildController.get().state();
				Planner pl = BuildController.get().planner();
				if (pl != null) {
					EnumMap<Phase, Integer> rem = remainingByPhase(pl);
					for (Phase ph : Phase.values()) {
						if (rem.get(ph) == 0 && !drained.containsKey(ph)) {
							drained.put(ph, new long[] {
									tick * 20L,
									System.currentTimeMillis() - resume0 });
						}
					}
				}
				if (tick % 10 == 0) {
					System.out.println("[GTBIG] t=" + (tick * 20)
							+ " " + BuildController.get().diagnosticState()
							+ " verified=" + verified() + "/" + total());
				}
				if (st == RunState.COMPLETE) {
					break;
				}
				if (st == RunState.PAUSED) {
					throw new AssertionError("unexpected pause after resume at t="
							+ (tick * 20) + ": "
							+ BuildController.get().statusLines());
				}
			}
			assertState(RunState.COMPLETE, "complete");

			long wallMs = System.currentTimeMillis() - wall0;
			long memAfter = rt.totalMemory() - rt.freeMemory();
			for (Phase ph : Phase.values()) {
				long[] d = drained.get(ph);
				System.out.println("[GTBIG] phase=" + ph
						+ (d != null
								? " drainedTick=" + d[0] + " drainedMs=" + d[1]
								: " drained=never"));
			}
			System.out.println("[GTBIG] complete wallMs=" + wallMs
					+ " ticks~" + waited
					+ " memBeforeMB=" + (memBefore >> 20)
					+ " memAfterMB=" + (memAfter >> 20)
					+ " deltaMB=" + ((memAfter - memBefore) >> 20));

			assertBuilt(sp);
			context.takeScreenshot("gentlebuild-big-build-complete");
		}
	}

	// ------------------------------------------------------------------
	// Assertions / sampling helpers
	// ------------------------------------------------------------------

	private static int verified() {
		Planner pl = BuildController.get().planner();
		return pl == null ? 0 : pl.verifiedCount();
	}

	private static int total() {
		Planner pl = BuildController.get().planner();
		return pl == null ? 0 : pl.totalTargets();
	}

	private static boolean terminal() {
		RunState st = BuildController.get().state();
		return st == RunState.PAUSED || st == RunState.COMPLETE;
	}

	/** Ticks the test has explicitly waited through — the metric reported
	 * as ticks~ alongside wallMs. */
	private int waited;

	private void waitTicks(ClientGameTestContext context, int n) {
		context.waitTicks(n);
		waited += n;
	}

	private static EnumMap<Phase, Integer> remainingByPhase(Planner pl) {
		EnumMap<Phase, Integer> rem = new EnumMap<>(Phase.class);
		for (Phase ph : Phase.values()) {
			rem.put(ph, 0);
		}
		for (BuildTarget t : pl.targets()) {
			if (!pl.isVerified(t)) {
				rem.merge(t.phase(), 1, Integer::sum);
			}
		}
		return rem;
	}

	private void awaitUntil(ClientGameTestContext context,
							java.util.function.BooleanSupplier cond,
							int maxTicks, String label) {
		for (int t = 0; t < maxTicks / 20; t++) {
			waitTicks(context, 20);
			if (t % 10 == 0) {
				System.out.println("[GTBIG-" + label + "] "
						+ BuildController.get().diagnosticState()
						+ " verified=" + verified() + "/" + total());
			}
			if (cond.getAsBoolean()) {
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

	/** Spot-check the world, plus an exact count over the blueprint box. */
	private void assertBuilt(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			assertBlock(level, WALL_CORNER, Blocks.STONE, "wall corner");
			assertBlock(level, WALL_TOP, Blocks.STONE, "wall top");
			assertBlock(level, LEVER, Blocks.LEVER, "lever");
			for (BlockPos p : new BlockPos[] { REPEATER_A, REPEATER_B }) {
				BlockState s = level.getBlockState(p);
				if (s.getBlock() != Blocks.REPEATER
						|| s.getValue(BlockStateProperties.HORIZONTAL_FACING)
								!= Direction.EAST) {
					throw new AssertionError("repeater wrong at "
							+ p.toShortString() + ": " + s);
				}
			}
			if (level.getBlockState(LEVER)
					.getValue(BlockStateProperties.POWERED)) {
				throw new AssertionError("lever left powered");
			}
			// Every desired cell's block must match — full-box sweep.
			for (var e : desiredBlocks().entrySet()) {
				BlockPos world = e.getKey().offset(ORIGIN);
				if (level.getBlockState(world).getBlock()
						!= e.getValue().getBlock()) {
					throw new AssertionError("cell wrong at "
							+ world.toShortString() + ": "
							+ level.getBlockState(world).getBlock()
							+ " != " + e.getValue().getBlock());
				}
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
			player.teleportTo(-2.5, 64, 8.5);
			var inv = player.getInventory();
			inv.clearContent();
			// ~1206 cells: 19 full stacks of stone plus generous scaffold
			// material for reaching the podium top.
			for (int slot = 0; slot < 20; slot++) {
				inv.setItem(slot, new ItemStack(Items.STONE, 64));
			}
			inv.setItem(20, new ItemStack(Items.REDSTONE, 16));
			inv.setItem(21, new ItemStack(Items.REPEATER, 8));
			inv.setItem(22, new ItemStack(Items.LEVER, 4));
			inv.setItem(23, new ItemStack(Items.DIRT, 64));
			inv.setItem(24, new ItemStack(Items.DIRT, 64));
			inv.setItem(25, new ItemStack(Items.DIRT, 64));
			inv.setItem(26, new ItemStack(Items.DIRT, 64));
		});
	}

	/** Capture the desired structure through Litematica's own world import. */
	private LitematicaSchematic captureSchematic(TestSingleplayerContext sp) {
		AtomicReference<LitematicaSchematic> ref = new AtomicReference<>();
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			desiredBlocks().forEach((local, state) ->
					level.setBlock(local.offset(STAGE), state, 3));
			var sel = new AreaSelection();
			sel.setExplicitOrigin(STAGE);
			var box = new Box(STAGE, STAGE.offset(BOX_MAX), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbbig", s -> { });
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
