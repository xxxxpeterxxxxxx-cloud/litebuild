package dev.litebuild.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.item.Item;
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
import dev.litebuild.build.InventoryHelper;
import dev.litebuild.model.RunState;
import dev.litebuild.motion.InputBroker;

/**
 * Full main-inventory refill (beta.5). The only stone sits in main slots 9 and
 * 35 (6 each) and the 12-stone row needs both refills; all nine hotbar slots
 * hold dirt and the offhand holds stone that must stay untouched. Before the
 * run, equip must refuse (BLOCKED, nothing moved) while a stack is carried and
 * while a foreign container menu is open. During the run, aim, wait and rest
 * states must never request forward movement.
 */
public final class LiteBuildInventoryTest implements FabricClientGameTest {
	private static final int STAGE_DY = 136;
	private static final int ROW = 12;
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 1);

	private static final AtomicBoolean RECORDING = new AtomicBoolean();
	private static final AtomicReference<String> FORWARD_VIOLATION = new AtomicReference<>();
	private static volatile RunState lastState;

	private static void probeTick(Minecraft mc) {
		if (!RECORDING.get()) {
			return;
		}
		RunState st = BuildController.get().state();
		if (st != lastState) {
			lastState = st;
		}
		boolean stationary = st == RunState.AIMING || st == RunState.WAITING
				|| st == RunState.VERIFYING || st == RunState.RESTING;
		if (stationary && InputBroker.desired().forward()) {
			FORWARD_VIOLATION.compareAndSet(null, "forward requested in " + st);
		}
	}

	static {
		ClientTickEvents.END_CLIENT_TICK.register(LiteBuildInventoryTest::probeTick);
	}

	private record Saved(boolean automaticPauses, boolean automaticRepairs, int settleTicks) {}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
		RECORDING.set(false);
		FORWARD_VIOLATION.set(null);
		lastState = null;
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
			});
			try {
				awaitFixture(context, sp);
				guardChecks(context);
				cadenceChecks(context, sp);
				oversizedSwapGuard(context, sp);
				context.runOnClient(mc -> {
					RECORDING.set(true);
					BuildController.get().start();
				});
				awaitUntil(context, sp, () -> BuildController.get().state() == RunState.COMPLETE,
						2400, "inventory-build");
				RECORDING.set(false);
				if (FORWARD_VIOLATION.get() != null) {
					throw new AssertionError("stationary state moved: " + FORWARD_VIOLATION.get());
				}
				finalChecks(context, sp);
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

	/**
	 * Waits (bounded) until the client shows the authoritative fixture, then
	 * selects hotbar slot 4 through the normal client API and logs both sides.
	 * Vanilla syncs the selection to the server with the next interaction.
	 */
	private void awaitFixture(ClientGameTestContext context, TestSingleplayerContext sp) {
		AtomicBoolean ready = new AtomicBoolean();
		for (int t = 0; t < 40 && !ready.get(); t++) {
			context.waitTicks(5);
			context.runOnClient(mc -> {
				var inv = mc.player.getInventory();
				boolean hotbarDirt = true;
				for (int i = 0; i < 9; i++) {
					hotbarDirt &= inv.getItem(i).is(Items.DIRT) && inv.getItem(i).getCount() == 1;
				}
				ready.set(hotbarDirt && inv.getItem(9).is(Items.STONE) && inv.getItem(9).getCount() == 6
						&& inv.getItem(35).is(Items.STONE) && inv.getItem(35).getCount() == 6
						&& inv.getItem(40).is(Items.STONE) && inv.getItem(40).getCount() == 64);
			});
		}
		if (!ready.get()) {
			throw new AssertionError("client inventory fixture never synced");
		}
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(4));
		context.waitTicks(4);
		context.runOnClient(mc -> System.out.println("[GTINV] before-build client selected="
				+ mc.player.getInventory().getSelectedSlot()));
		sp.getServer().runOnServer(server -> System.out.println("[GTINV] before-build server selected="
				+ server.getPlayerList().getPlayers().get(0).getInventory().getSelectedSlot()));
	}

	/** Blocked-equip guards: nothing may move while a stack is carried or a foreign menu is open. */
	private void guardChecks(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			LocalPlayerView view = LocalPlayerView.of(mc);
			InventoryHelper.cancelPending();
			String before = view.snapshot();
			view.player.containerMenu.setCarried(new ItemStack(Items.DIRT));
			// Guard runs before the already-selected shortcut and any pending settle.
			var r1 = InventoryHelper.equip(view.player, Items.STONE);
			var r3 = InventoryHelper.equip(view.player, Items.DIRT);
			view.player.containerMenu.setCarried(ItemStack.EMPTY);
			if (r1 != InventoryHelper.EquipResult.BLOCKED || r3 != InventoryHelper.EquipResult.BLOCKED
					|| !before.equals(view.snapshot())) {
				throw new AssertionError("carried-stack guard failed: " + r1 + "/" + r3
						+ " slot9=" + view.player.getInventory().getItem(9)
						+ " slot35=" + view.player.getInventory().getItem(35));
			}
			AbstractContainerMenu original = view.player.containerMenu;
			view.player.containerMenu = ChestMenu.threeRows(original.containerId + 1,
					view.player.getInventory());
			var r2 = InventoryHelper.equip(view.player, Items.STONE);
			view.player.containerMenu = original;
			if (r2 != InventoryHelper.EquipResult.BLOCKED || !before.equals(view.snapshot())) {
				throw new AssertionError("foreign-menu guard failed: " + r2);
			}
			System.out.println("[GTINV] guards: carried=" + r1 + " foreignMenu=" + r2
					+ " inventoryUnchanged=true");
		});
	}

	/**
	 * Cadence and state checks for the settled switch delays, run on the real client
	 * inventory before the build. The fixture is restored afterwards.
	 */
	private void cadenceChecks(ClientGameTestContext context, TestSingleplayerContext sp) {
		var ready = InventoryHelper.EquipResult.READY;
		var waiting = InventoryHelper.EquipResult.WAITING;
		sp.getServer().runOnServer(server ->
				server.getPlayerList().getPlayers().get(0).getInventory()
						.setItem(2, new ItemStack(Items.STONE, 1)));
		waitClientSlot(context, 2, Items.STONE, 1);
		context.runOnClient(mc -> {
			InventoryHelper.cancelPending();
			check(InventoryHelper.equip(mc.player, Items.DIRT) == ready,
					"already-held dirt must be READY at once");
		});
		context.runOnClient(mc -> {
			check(InventoryHelper.equip(mc.player, Items.STONE) == waiting,
					"hotbar switch must arm as WAITING");
			check(InventoryHelper.equip(mc.player, Items.STONE) == waiting,
					"repeated equip in the same tick must stay WAITING");
		});
		int hot = pollUntilReady(context, Items.STONE);
		check(hot >= 4 && hot <= 6, "hotbar switch READY after 4 ticks, polled at " + hot);

		sp.getServer().runOnServer(server ->
				server.getPlayerList().getPlayers().get(0).getInventory().setItem(2,
						net.minecraft.world.item.ItemStack.EMPTY));
		waitClientSlot(context, 2, Items.STONE, 0);
		context.runOnClient(mc -> {
			InventoryHelper.cancelPending();
			check(InventoryHelper.equip(mc.player, Items.DIRT) == waiting,
					"switch back to dirt must arm as WAITING");
		});
		int back = pollUntilReady(context, Items.DIRT);
		check(back >= 4 && back <= 6, "switch back READY after 4 ticks, polled at " + back);

		context.runOnClient(mc -> {
			check(InventoryHelper.equip(mc.player, Items.STONE) == waiting,
					"main refill must arm as WAITING");
			check(InventoryHelper.equip(mc.player, Items.STONE) == waiting,
					"repeated main refill poll in the same tick must stay WAITING");
		});
		int refill = pollUntilReady(context, Items.STONE);
		check(refill >= 8 && refill <= 10, "main refill READY after 8 ticks, polled at " + refill);
		System.out.println("[GTINV] cadence hotbar=" + hot + " switchBack=" + back
				+ " mainRefill=" + refill);

		context.runOnClient(mc -> {
			check(InventoryHelper.equip(mc.player, Items.DIRT) == waiting, "re-arm dirt");
			int armed = mc.player.getInventory().getSelectedSlot();
			InventoryHelper.cancelPending();
			// The selection already changed; cancel only drops the wait, it never re-clicks.
			check(InventoryHelper.equip(mc.player, Items.DIRT) == ready,
					"after cancel the held dirt is READY with no new switch");
			check(mc.player.getInventory().getSelectedSlot() == armed,
					"cancel must not move the selection");
		});
		context.runOnClient(mc -> check(InventoryHelper.equip(mc.player, Items.STONE) == waiting,
				"late-poll switch must arm"));
		context.waitTicks(45);
		context.runOnClient(mc -> check(InventoryHelper.equip(mc.player, Items.STONE) == ready,
				"a valid switch polled after 45 ticks must be READY, not BLOCKED"));
		System.out.println("[GTINV] late poll after 45 ticks: READY");
		restoreFixture(context, sp);
	}

	/**
	 * Isolated guard test: an impossible count-2 stack in the selected hotbar slot must make a
	 * main-inventory request fail closed, with no click and an unchanged inventory and cursor.
	 * The valid fixture is restored afterwards; this is not part of the successful build.
	 */
	private void oversizedSwapGuard(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).getInventory()
				.setItem(4, new ItemStack(Items.WATER_BUCKET, 2)));
		waitClientSlot(context, 4, Items.WATER_BUCKET, 2);
		AtomicReference<String> before = new AtomicReference<>();
		context.runOnClient(mc -> {
			InventoryHelper.cancelPending();
			LocalPlayerView view = LocalPlayerView.of(mc);
			before.set(view.snapshot());
			check(InventoryHelper.equip(mc.player, Items.STONE) == InventoryHelper.EquipResult.BLOCKED,
					"oversized main swap must be refused");
			check(before.get().equals(view.snapshot()), "refused swap changed the inventory");
			check(mc.player.containerMenu.getCarried().isEmpty(), "cursor must stay empty");
		});
		System.out.println("[GTINV] oversized swap guard: BLOCKED, inventory and cursor unchanged");
		restoreFixture(context, sp);
	}

	private void finalChecks(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			for (int x = 0; x < ROW; x++) {
				assertBlock(level, ORIGIN.offset(x, 0, 0), Blocks.STONE, "row x=" + x);
			}
		});
		context.runOnClient(mc -> {
			LocalPlayerView view = LocalPlayerView.of(mc);
			Inventory inv = view.player.getInventory();
			int stoneMain = 0;
			for (int i = 0; i < 36; i++) {
				if (inv.getItem(i).is(Items.STONE)) {
					stoneMain += inv.getItem(i).getCount();
				}
			}
			int dirt = 0;
			for (int i = 0; i < 36; i++) {
				if (inv.getItem(i).is(Items.DIRT)) {
					dirt += inv.getItem(i).getCount();
				}
			}
			ItemStack offhand = inv.getItem(40);
			if (stoneMain != 0) {
				throw new AssertionError("stone left in 0..35: " + stoneMain);
			}
			if (dirt != 9) {
				throw new AssertionError("dirt not conserved: " + dirt);
			}
			if (!offhand.is(Items.STONE) || offhand.getCount() != 64) {
				throw new AssertionError("offhand changed: " + offhand);
			}
			if (!view.player.containerMenu.getCarried().isEmpty()) {
				throw new AssertionError("cursor not empty");
			}
			System.out.println("[GTINV] client complete: stoneLeft=" + stoneMain + " dirt=" + dirt
					+ " offhand=" + offhand + " cursorEmpty=true slot9=" + inv.getItem(9)
					+ " slot35=" + inv.getItem(35));
		});
		sp.getServer().runOnServer(server -> {
			var player = server.getPlayerList().getPlayers().get(0);
			var inv = player.getInventory();
			int stoneMain = 0;
			int dirt = 0;
			for (int i = 0; i < 36; i++) {
				if (inv.getItem(i).is(Items.STONE)) {
					stoneMain += inv.getItem(i).getCount();
				}
				if (inv.getItem(i).is(Items.DIRT)) {
					dirt += inv.getItem(i).getCount();
				}
			}
			ItemStack offhand = inv.getItem(40);
			if (stoneMain != 0 || dirt != 9 || !offhand.is(Items.STONE) || offhand.getCount() != 64
					|| !player.containerMenu.getCarried().isEmpty()) {
				throw new AssertionError("server inventory not conserved: stone=" + stoneMain
						+ " dirt=" + dirt + " offhand=" + offhand + " carried="
						+ player.containerMenu.getCarried());
			}
			System.out.println("[GTINV] server complete: stoneLeft=" + stoneMain + " dirt=" + dirt
					+ " offhand=" + offhand + " selected=" + inv.getSelectedSlot());
		});
	}

	/** Small helper: the local player and a stable inventory snapshot for before/after checks. */
	private record LocalPlayerView(net.minecraft.client.player.LocalPlayer player) {
		static LocalPlayerView of(Minecraft mc) {
			return new LocalPlayerView(mc.player);
		}

		String snapshot() {
			List<String> parts = new ArrayList<>();
			for (int i = 0; i < 41; i++) {
				parts.add(player.getInventory().getItem(i).toString());
			}
			parts.add(player.getInventory().getSelectedSlot() + "");
			parts.add(player.containerMenu.getCarried().toString());
			return String.join("|", parts);
		}
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
			applyFixture(player);
		});
	}

	/** The fixture inventory: 9 dirt hotbar, 6+6 stone in main slots 9/35, 64 stone offhand. */
	private static void applyFixture(net.minecraft.server.level.ServerPlayer player) {
		var inv = player.getInventory();
		inv.clearContent();
		for (int i = 0; i < 9; i++) {
			inv.setItem(i, new ItemStack(Items.DIRT, 1));
		}
		inv.setItem(9, new ItemStack(Items.STONE, 6));
		inv.setItem(35, new ItemStack(Items.STONE, 6));
		inv.setItem(40, new ItemStack(Items.STONE, 64));
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}

	/** Bounded wait until the client shows {@code count} of {@code item} in {@code slot}. */
	private void waitClientSlot(ClientGameTestContext context, int slot, Item item, int count) {
		AtomicBoolean ok = new AtomicBoolean();
		for (int t = 0; t < 40 && !ok.get(); t++) {
			context.waitTicks(5);
			context.runOnClient(mc -> {
				ItemStack st = mc.player.getInventory().getItem(slot);
				ok.set(count == 0 ? st.isEmpty() : st.is(item) && st.getCount() == count);
			});
		}
		check(ok.get(), "client slot " + slot + " never reached " + count + "x" + item);
	}

	/** Polls equip once per client tick; returns the first poll index that is READY, or -1. */
	private int pollUntilReady(ClientGameTestContext context, Item item) {
		for (int n = 1; n <= 20; n++) {
			context.waitTicks(1);
			AtomicBoolean ready = new AtomicBoolean();
			context.runOnClient(mc ->
					ready.set(InventoryHelper.equip(mc.player, item) == InventoryHelper.EquipResult.READY));
			if (ready.get()) {
				return n;
			}
		}
		return -1;
	}

	/** Restores the normal fixture on the server, waits for the client, reselects slot 4. */
	private void restoreFixture(ClientGameTestContext context, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> applyFixture(server.getPlayerList().getPlayers().get(0)));
		waitClientSlot(context, 9, Items.STONE, 6);
		waitClientSlot(context, 35, Items.STONE, 6);
		context.runOnClient(mc -> {
			InventoryHelper.cancelPending();
			mc.player.getInventory().setSelectedSlot(4);
		});
		context.waitTicks(4);
	}

	private void selectPlacement(LitematicaSchematic schematic) {
		var mgr = DataManager.getSchematicPlacementManager();
		for (SchematicPlacement old : mgr.getAllSchematicsPlacements()) {
			mgr.removeSchematicPlacement(old);
		}
		BlockPos rel = schematic.getAreaPositions().get("A");
		SchematicPlacement placement = SchematicPlacement.createFor(
				schematic, ORIGIN.subtract(rel), "gbinventory", true, true);
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
					"gbinventory", s -> { });
			ref.set(schem);
			desired.keySet().forEach(local ->
					level.setBlock(local.offset(stage), Blocks.AIR.defaultBlockState(), 3));
		});
		if (ref.get() == null) {
			throw new AssertionError("schematic capture failed");
		}
		return ref.get();
	}

	private void awaitUntil(ClientGameTestContext context, TestSingleplayerContext sp,
							java.util.function.BooleanSupplier cond,
							int maxTicks, String label) {
		for (int t = 0; t < maxTicks; t += 5) {
			context.waitTicks(5);
			if (FORWARD_VIOLATION.get() != null) {
				throw new AssertionError("stationary state moved during " + label
						+ ": " + FORWARD_VIOLATION.get());
			}
			if (BuildController.get().state() == RunState.PAUSED) {
				dumpRow(context, sp);
				throw new AssertionError("unexpected PAUSED during " + label + ": "
						+ BuildController.get().statusLines());
			}
			if (cond.getAsBoolean()) {
				return;
			}
		}
		throw new AssertionError("timeout waiting for " + label + " status="
				+ BuildController.get().statusLines());
	}

	private void dumpRow(ClientGameTestContext context, TestSingleplayerContext sp) {
		context.runOnClient(mc -> System.out.println("[GTINV] client held="
				+ mc.player.getMainHandItem() + " selected=" + mc.player.getInventory().getSelectedSlot()
				+ " slot4=" + mc.player.getInventory().getItem(4)
				+ " slot9=" + mc.player.getInventory().getItem(9)
				+ " slot35=" + mc.player.getInventory().getItem(35)));
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			StringBuilder sb = new StringBuilder("[GTINV] server row:");
			for (int x = 0; x < ROW; x++) {
				sb.append(' ').append(level.getBlockState(ORIGIN.offset(x, 0, 0)).getBlock());
			}
			System.out.println(sb);
		});
	}

	private static void assertBlock(net.minecraft.world.level.Level level, BlockPos pos,
									net.minecraft.world.level.block.Block wanted, String what) {
		if (level.getBlockState(pos).getBlock() != wanted) {
			throw new AssertionError(what + " at " + pos.toShortString() + ": "
					+ level.getBlockState(pos).getBlock() + " != " + wanted);
		}
	}
}
