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
 * Scale/performance evidence run (run76): a ~239-cell medium blueprint
 * (14x14 stone platform, 6x6 second layer, 6-cell redstone row, one lever)
 * is staged, captured through the real Litematica import and built by the
 * production BuildController in a flat arena.
 *
 * Asserts:
 *  - COMPLETE under a generous tick budget (regression bound, not a
 *    microbenchmark — this machine is slow under load);
 *  - the emergency pause hotkey (H) halts a mid-build run with zero
 *    planner progress while PAUSED, and an explicit resume completes.
 *
 * Metrics are logged as [GTSCALE] lines: wall ms + tick index when each
 * phase's remaining count first drains (post-resume planner), total
 * wall time, and heap used before/after — INFO only, never asserted.
 *
 * NOTE: the importer rejection paths for MAX_REGION_CELLS=1_000_000 and
 * MAX_NON_AIR=100_000 are not exercised here — building a >100k-cell
 * schematic inside a gametest world is impractical; the constants are
 * covered by PlannerScaleTest and the rejection code path is review-only.
 */
public final class GentleBuildScaleTest implements FabricClientGameTest {
	private static final BlockPos FLOOR_ORIGIN = new BlockPos(-4, 63, -4);
	private static final int FLOOR_W = 24, FLOOR_D = 24;
	private static final int STAGE_DY = 136;
	private static final BlockPos ORIGIN = new BlockPos(2, 64, 2); // local (0,0,0)
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	private static final BlockPos BOX_MAX = new BlockPos(13, 1, 13);

	/** Verified count at which the emergency pause is injected. */
	private static final int PAUSE_AT_VERIFIED = 30;
	/** Warm-up budget: ~30 verified cells including snapshot/planning. */
	private static final int WARMUP_TICKS = 20000;
	/** Post-resume completion budget (~30 min at 20 tps — generous). */
	private static final int BUILD_TICKS = 36000;
	/** Ticks observed while PAUSED to prove the halt. */
	private static final int HALT_OBSERVE_TICKS = 80;

	/** Interesting world cells for spot verification. */
	private static final BlockPos PLATFORM_CORNER = ORIGIN.offset(13, 0, 13);
	private static final BlockPos UPPER_CORNER = ORIGIN.offset(9, 1, 9);
	private static final BlockPos REPEATER_A = ORIGIN.offset(3, 1, 1);
	private static final BlockPos REPEATER_B = ORIGIN.offset(5, 1, 1);
	private static final BlockPos LEVER = ORIGIN.offset(9, 1, 1);

	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		// Layer 0: 14x14 stone platform (196 cells).
		for (int x = 0; x <= 13; x++) {
			for (int z = 0; z <= 13; z++) {
				m.put(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
			}
		}
		// Layer 1: 6x6 second storey (36 cells) — forces elevated stances.
		for (int x = 4; x <= 9; x++) {
			for (int z = 4; z <= 9; z++) {
				m.put(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
			}
		}
		// Redstone row along the platform's north edge, z=1, sitting on
		// layer 0: dust - repeater(delay 2, east) - dust - repeater(delay 4)
		// - dust - dust. No power source: every captured state stays valid.
		m.put(new BlockPos(2, 1, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
		m.put(new BlockPos(3, 1, 1), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DELAY, 2));
		m.put(new BlockPos(4, 1, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
		m.put(new BlockPos(5, 1, 1), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
				.setValue(BlockStateProperties.DELAY, 4));
		m.put(new BlockPos(6, 1, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
		m.put(new BlockPos(7, 1, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
		// Activation-phase tail: a floor lever (OFF).
		m.put(new BlockPos(9, 1, 1), Blocks.LEVER.defaultBlockState()
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
			System.out.println("[GTSCALE] start memUsedMB=" + (memBefore >> 20)
					+ " cells=" + desiredBlocks().size());

			BlockPos rel = schematic.getAreaPositions().get("A");
			context.runOnClient(mc -> {
				var mgr = DataManager.getSchematicPlacementManager();
				for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) {
					mgr.removeSchematicPlacement(old);
				}
				SchematicPlacement placement = SchematicPlacement.createFor(
						schematic, ORIGIN.subtract(rel), "gbscale", true, true);
				mgr.addSchematicPlacement(placement, true);
				mgr.setSelectedSchematicPlacement(placement);
				var cfg = BuildController.get().config();
				// Deterministic cadence: no humanized rests inside the
				// measured window; sprint keeps relocation legs short.
				cfg.automaticPauses = false;
				cfg.sprintOnLongWalks = true;
				BuildController.get().start();
				// importSelected() resets the per-build gates synchronously,
				// so they must be opened AFTER start().
				cfg.fluidsEnabled = true;   // no fluid cells, but keep all gates open
				cfg.activationEnabled = true;
			});

			// ---- stage 1: warm up until >=30 cells verified -------------
			awaitUntil(context, () -> verified() >= PAUSE_AT_VERIFIED
							|| terminal(), WARMUP_TICKS, "warmup");
			if (verified() < PAUSE_AT_VERIFIED) {
				throw new AssertionError("build never reached "
						+ PAUSE_AT_VERIFIED + " verified: state="
						+ BuildController.get().state()
						+ " " + BuildController.get().diagnosticState());
			}
			System.out.println("[GTSCALE] warmup done verified=" + verified()
					+ "/" + total());

			// ---- stage 2: emergency pause via the H hotkey --------------
			context.getInput().pressKey(GentleBuildClient.pauseKey);
			context.waitTicks(15);
			assertState(RunState.PAUSED, "hotkey");
			int verifiedAtPause = verified();
			context.waitTicks(HALT_OBSERVE_TICKS);
			int verifiedAfterHalt = verified();
			System.out.println("[GTSCALE] pause verifiedAtPause=" + verifiedAtPause
					+ " afterHalt=" + verifiedAfterHalt
					+ " observeTicks=" + HALT_OBSERVE_TICKS);
			if (verifiedAfterHalt != verifiedAtPause) {
				throw new AssertionError("paused build kept working: "
						+ verifiedAtPause + " -> " + verifiedAfterHalt);
			}
			assertState(RunState.PAUSED, "halt-observe");

			// ---- stage 3: resume -> COMPLETE, per-phase metrics ---------
			context.runOnClient(mc -> {
				var cfg = BuildController.get().config();
				cfg.fluidsEnabled = true;
				cfg.activationEnabled = true;
				BuildController.get().start();
			});
			EnumMap<Phase, long[]> drained = new EnumMap<>(Phase.class);
			int tick = 0;
			long resume0 = System.currentTimeMillis();
			for (; tick < BUILD_TICKS / 20; tick++) {
				context.waitTicks(20);
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
					System.out.println("[GTSCALE] t=" + (tick * 20)
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
				System.out.println("[GTSCALE] phase=" + ph
						+ (d != null
								? " drainedTick=" + d[0] + " drainedMs=" + d[1]
								: " drained=never"));
			}
			System.out.println("[GTSCALE] complete wallMs=" + wallMs
					+ " ticks~" + (tick * 20)
					+ " memBeforeMB=" + (memBefore >> 20)
					+ " memAfterMB=" + (memAfter >> 20)
					+ " deltaMB=" + ((memAfter - memBefore) >> 20));

			assertBuilt(sp);
			context.takeScreenshot("gentlebuild-scale-complete");
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
			context.waitTicks(20);
			if (t % 10 == 0) {
				System.out.println("[GTSCALE-" + label + "] "
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
			assertBlock(level, PLATFORM_CORNER, Blocks.STONE, "platform corner");
			assertBlock(level, UPPER_CORNER, Blocks.STONE, "upper corner");
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
			player.teleportTo(0.5, 64, 8.5);
			var inv = player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.STONE, 64));
			inv.setItem(1, new ItemStack(Items.STONE, 64));
			inv.setItem(2, new ItemStack(Items.STONE, 64));
			inv.setItem(3, new ItemStack(Items.STONE, 64));
			inv.setItem(4, new ItemStack(Items.REDSTONE, 16));
			inv.setItem(5, new ItemStack(Items.REPEATER, 8));
			inv.setItem(6, new ItemStack(Items.LEVER, 4));
			inv.setItem(7, new ItemStack(Items.DIRT, 64)); // scaffold material
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
					"gbscale", s -> { });
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
