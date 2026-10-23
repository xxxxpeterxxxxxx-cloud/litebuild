package dev.litebuild.gametest;

import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
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
import dev.litebuild.model.RunState;

/**
 * Scenario 2: a one-cell ground gap forces a real temporary support; a
 * DOUBLE slab exercises the two-step slab path; afterwards the explicit
 * cleanup breaks the owned support through the normal destroy path.
 * All construction goes through the production BuildController.
 */
public final class LiteBuildScenario2Test implements FabricClientGameTest {
	// Active only while build2 runs: the player must never leave the walkway or cross the gap
	// before the gap block is owned by the controller.
	private static volatile boolean observing;
	private static final AtomicReference<String> FIRST_ERROR = new AtomicReference<>();
	private static volatile double observedMinY = Double.MAX_VALUE;
	private static volatile int observedTicks;

	private static void observeTick(Minecraft mc) {
		if (!observing || mc.player == null) {
			return;
		}
		var pos = mc.player.position();
		observedTicks++;
		observedMinY = Math.min(observedMinY, pos.y);
		if (pos.y < 63.75) {
			FIRST_ERROR.compareAndSet(null, "fell below the walkway at " + pos);
		}
		if (pos.x >= 2.25 && pos.y >= 63.75
				&& !BuildController.get().supportPositions().contains(new BlockPos(2, 63, -4))) {
			FIRST_ERROR.compareAndSet(null, "crossed the gap before it was owned at " + pos);
		}
	}

	static {
		ClientTickEvents.END_CLIENT_TICK.register(LiteBuildScenario2Test::observeTick);
	}

	// local coords relative to ORIGIN (world 9,64,-4)
	private static final BlockPos ORIGIN = new BlockPos(9, 64, -4);
	private static final int STAGE_DY = 136;
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);

	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		m.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(1, 0, 0), Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE));
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
								new net.minecraft.world.phys.Vec3(0.5, 64, -3.5)) < 0.05));
			}
			if (!spawnSynced.get()) {
				throw new AssertionError("client spawn never synchronized to (0.5, 64, -3.5)");
			}
			context.runOnClient(mc -> {
				var mgr = DataManager.getSchematicPlacementManager();
				SchematicPlacement placement = SchematicPlacement.createFor(
						schematic, ORIGIN.subtract(rel), "gbtest2", true, true);
				mgr.addSchematicPlacement(placement, true);
				mgr.setSelectedSchematicPlacement(placement);
				BuildController.get().config().fluidsEnabled = false;
				BuildController.get().config().activationEnabled = false;
				FIRST_ERROR.set(null);
				observedMinY = Double.MAX_VALUE;
				observedTicks = 0;
				observing = true;
				BuildController.get().start();
			});

			try {
				awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE
						|| BuildController.get().state() == RunState.PAUSED,
						3600, "build2");
			} finally {
				observing = false;
			}
			System.out.println("[GT2-OBS] minY=" + observedMinY + " observedTicks=" + observedTicks
					+ " firstError=" + FIRST_ERROR.get());
			if (FIRST_ERROR.get() != null) {
				throw new AssertionError("walk safety violated: " + FIRST_ERROR.get());
			}
			if (BuildController.get().state() != RunState.COMPLETE) {
				throw new AssertionError("not complete: "
						+ BuildController.get().diagnosticState());
			}

			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				assertBlock(level, new BlockPos(9, 64, -4), Blocks.STONE, "stone");
				assertBlock(level, new BlockPos(10, 64, -4),
						Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
								.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
						"double slab");
			});
			int supports = BuildController.get().supportCount();
			java.util.Set<BlockPos> owned =
					java.util.Set.copyOf(BuildController.get().supportPositions());
			System.out.println("[GT2] supports=" + supports + " at " + owned);
			if (supports < 1) {
				throw new AssertionError("expected a temporary support over the gap");
			}
			context.takeScreenshot("litebuild-scenario2-built");

			// Cleanup: move the player so no support is underfoot, then run
			// the real break path and verify the support becomes AIR.
			sp.getServer().runOnServer(server ->
					sp.getConnection().getServerPlayer().teleportTo(4.5, 64, -3.5));
			// Let the client receive the teleport before reach checks run.
			context.waitTicks(10);
			context.runOnClient(mc -> BuildController.get().startCleanup());
			awaitDiag(context, () -> BuildController.get().supportCount() == 0,
					300, "cleanup2");
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				for (BlockPos p : owned) {
					assertBlock(level, p, Blocks.AIR,
							"temporary support not removed");
				}
				assertBlock(level, new BlockPos(3, 63, -4), Blocks.STONE,
						"existing floor damaged by cleanup");
				assertBlock(level, new BlockPos(9, 64, -4), Blocks.STONE, "stone");
				assertBlock(level, new BlockPos(10, 64, -4),
						Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
								.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
						"double slab");
			});
			context.takeScreenshot("litebuild-scenario2-cleaned");
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
				context.takeScreenshot("litebuild-" + label + "-end");
			} catch (Throwable ignored) {
			}
		}
		throw new AssertionError("timeout waiting for " + label
				+ " " + BuildController.get().diagnosticState());
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
			// Pit x=-3..15 z=-8..0: solid floor at y=60, air y=61..63. The only
			// walkway is the strip x=0..11 z=-4 at y=63, missing x=2 — a gap too
			// deep to cross on foot, forcing a real temporary support.
			for (int x = -3; x <= 15; x++) {
				for (int z = -8; z <= 0; z++) {
					level.setBlock(new BlockPos(x, 60, z), Blocks.STONE.defaultBlockState(), 3);
					for (int y = 61; y <= 80; y++) {
						level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			for (int x = 0; x <= 11; x++) {
				if (x != 2) {
					level.setBlock(new BlockPos(x, 63, -4), Blocks.STONE.defaultBlockState(), 3);
				}
			}
			var player = sp.getConnection().getServerPlayer();
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(0.5, 64, -3.5);
			var inv = player.getInventory();
			inv.clearContent();
			inv.setItem(0, new ItemStack(Items.STONE, 64));
			inv.setItem(1, new ItemStack(Items.DIRT, 16));
			// Slabs only in the main inventory -> exercises hotbar swap.
			inv.setItem(20, new ItemStack(Items.SMOOTH_STONE_SLAB, 4));
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
			var box = new Box(STAGE, STAGE.offset(1, 0, 0), "A");
			sel.addSubRegionBox(box, true);
			var schem = LitematicaSchematic.createFromWorld(level, sel,
					new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
					"gbtest2", s -> { });
			ref.set(schem);
			desired.keySet().forEach(local ->
					level.setBlock(local.offset(STAGE),
							Blocks.AIR.defaultBlockState(), 3));
		});
		return ref.get();
	}
}
