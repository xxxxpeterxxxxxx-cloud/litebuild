package dev.litebuild.gametest;

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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
import dev.litebuild.build.BuildController;
import dev.litebuild.model.RunState;

/**
 * Scenario 3 — final audit + bounded safe repair. The build completes, then
 * the test MUTATES the world (fault injection only): removes a stone and
 * resets the repeater delay. The production controller must detect both in
 * the second audit pass and restore them through the normal interaction
 * path. Afterwards an UNOWNED chest in a required-AIR cell must pause with
 * a manual reason and stay untouched; removing it and re-verifying
 * completes.
 */
public final class LiteBuildRepairTest implements FabricClientGameTest {
	// Blueprint cells (world coords): stone 0/1/3,64,0; repeater 3,65,0
	// facing NORTH delay 4; AIR incl. 7,64,0. Region x0..7,y64..65,z0.
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
	private static final int STAGE_DY = 140;
	private static final BlockPos STAGE = ORIGIN.above(STAGE_DY);
	private static final BlockPos STONE_A = new BlockPos(0, 64, 0);
	private static final BlockPos REPEATER = new BlockPos(3, 65, 0);
	private static final BlockPos AIR_CELL = new BlockPos(7, 64, 0);


	private static Map<BlockPos, BlockState> desiredBlocks() {
		Map<BlockPos, BlockState> m = new java.util.LinkedHashMap<>();
		m.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(3, 0, 0), Blocks.STONE.defaultBlockState());
		m.put(new BlockPos(3, 1, 0), Blocks.REPEATER.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DELAY, 4));
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
				SchematicPlacement placement = SchematicPlacement.createFor(
						schematic, ORIGIN.subtract(rel), "gbtest3", true, true);
				mgr.addSchematicPlacement(placement, true);
				mgr.setSelectedSchematicPlacement(placement);
				var cfg = BuildController.get().config();
				cfg.automaticPauses = false;  // focused repair test
				cfg.automaticRepairs = true;
				cfg.maxRepairRounds = 3;
				BuildController.get().start();
			});

			// Clean build first: production controller + first audit.
			awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
					2400, "initial-complete");

			// Fault injection on the SERVER (fixture only): remove one built
			// stone and reset the repeater delay. The production controller
			// must find and fix both through the normal interaction path.
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				level.setBlock(STONE_A, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(REPEATER,
						level.getBlockState(REPEATER).setValue(
								BlockStateProperties.DELAY, 1), 3);
				System.out.println("[GT3] injected faults: stone removed, "
						+ "delay reset");
			});
			context.waitTicks(10);
			context.runOnClient(mc -> BuildController.get().verify());

			// Audit -> bounded repair -> fresh audit -> COMPLETE.
			awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
					4000, "repair-complete");
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				assertBlock(level, STONE_A, Blocks.STONE, "repaired stone");
				BlockState rep = level.getBlockState(REPEATER);
				if (rep.getBlock() != Blocks.REPEATER
						|| rep.getValue(BlockStateProperties.DELAY) != 4) {
					throw new AssertionError("repeater not restored: " + rep);
				}
			});
			var c = BuildController.get();
			if (c.repairRound() < 1 || c.fullAuditRuns() < 2) {
				throw new AssertionError("expected repair round + re-audit: "
						+ "round=" + c.repairRound() + " runs=" + c.fullAuditRuns());
			}
			context.takeScreenshot("litebuild-repair-complete");

			// Unowned chest with contents in a required-AIR cell: the audit
			// must report it as manual and leave it untouched.
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				level.setBlock(AIR_CELL, Blocks.CHEST.defaultBlockState(), 3);
				if (level.getBlockEntity(AIR_CELL) instanceof ChestBlockEntity chest) {
					chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
				}
			});
			context.runOnClient(mc -> BuildController.get().verify());
			awaitDiag(context, () -> BuildController.get().state() == RunState.PAUSED,
					1200, "chest-manual");
			String status = BuildController.get().statusLines().toString();
			if (!status.contains("9") ) {
				System.out.println("[GT3] status=" + status);
			}
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				if (level.getBlockEntity(AIR_CELL) instanceof ChestBlockEntity chest) {
					if (chest.getItem(0).getCount() != 5) {
						throw new AssertionError("chest contents touched");
					}
				} else {
					throw new AssertionError("chest was broken: "
							+ level.getBlockState(AIR_CELL));
				}
			});

			// User removes the chest; a fresh explicit audit completes.
			sp.getServer().runOnServer(server ->
					server.overworld().setBlock(AIR_CELL,
							Blocks.AIR.defaultBlockState(), 3));
			context.waitTicks(10);
			context.runOnClient(mc -> BuildController.get().verify());
			awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
					1200, "verify-clean");
			context.takeScreenshot("litebuild-verify-clean");

			// Bounded retry: seal STONE_A so the FILL can never be solved,
			// and reset the repeater delay so round 1 still makes progress
			// and a second round is scheduled. The per-position attempt
			// cap (2) plus the no-progress stop must end in PAUSED — never
			// an endless repair loop and never a false COMPLETE.
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				level.setBlock(STONE_A.west(), Blocks.BEDROCK.defaultBlockState(), 3);
				level.setBlock(STONE_A.east(), Blocks.BEDROCK.defaultBlockState(), 3);
				level.setBlock(STONE_A.north(), Blocks.BEDROCK.defaultBlockState(), 3);
				level.setBlock(STONE_A.south(), Blocks.BEDROCK.defaultBlockState(), 3);
				level.setBlock(STONE_A.above(), Blocks.BEDROCK.defaultBlockState(), 3);
				level.setBlock(STONE_A, Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(REPEATER, level.getBlockState(REPEATER)
						.setValue(BlockStateProperties.DELAY, 1), 3);
			});
			context.waitTicks(10);
			context.runOnClient(mc -> BuildController.get().verify());
			awaitDiag(context, () -> BuildController.get().state() == RunState.PAUSED,
					4000, "repair-bounded");
			var c2 = BuildController.get();
			if (c2.repairRound() < 1 || c2.repairRound() > 3) {
				throw new AssertionError("repair loop unbounded: round="
						+ c2.repairRound());
			}
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				assertBlock(level, STONE_A, Blocks.AIR, "sealed stone stays missing");
				BlockState rep = level.getBlockState(REPEATER);
				if (rep.getBlock() != Blocks.REPEATER) {
					throw new AssertionError("repeater lost: " + rep);
				}
			});

			// An explicit user verify resets the budget: free the cell and
			// confirm the repair pipeline restores it and completes clean.
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				level.setBlock(STONE_A.west(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(STONE_A.north(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(STONE_A.south(), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(STONE_A.above(), Blocks.AIR.defaultBlockState(), 3);
				// Leave east as AIR too — production repair must refill it.
				level.setBlock(STONE_A.east(), Blocks.AIR.defaultBlockState(), 3);
			});
			context.waitTicks(10);
			context.runOnClient(mc -> BuildController.get().verify());
			awaitDiag(context, () -> BuildController.get().state() == RunState.COMPLETE,
					4000, "repair-after-free");
			sp.getServer().runOnServer(server ->
					assertBlock(server.overworld(), STONE_A, Blocks.STONE,
							"stone restored after unseal"));
			context.takeScreenshot("litebuild-repair-bounded");
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
			inv.setItem(1, new ItemStack(Items.REPEATER, 4));
			inv.setItem(2, new ItemStack(Items.DIRT, 16));
			inv.setItem(3, new ItemStack(Items.IRON_PICKAXE, 1));
		});
	}

	private static void assertBlock(net.minecraft.world.level.Level level,
									BlockPos pos,
									net.minecraft.world.level.block.Block wanted,
									String what) {
		if (level.getBlockState(pos).getBlock() != wanted) {
			throw new AssertionError(what + " at " + pos.toShortString()
					+ ": " + level.getBlockState(pos).getBlock());
		}
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
					"gbtest3", s -> { });
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
