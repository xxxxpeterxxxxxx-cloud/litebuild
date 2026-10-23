package dev.gentlebuild.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.gentlebuild.motion.InputBroker;

/**
 * Finds and equips required items using only normal inventory mechanics:
 * hotbar selection and swap clicks in the player's own inventory menu. Never
 * drops, never spawns items, never mutates stacks directly, never touches
 * armor or the offhand. A switch is allowed only when no foreign container
 * menu is open and the cursor is empty. After our own action the live client
 * state must show the requested item (after a short minimum delay) before the
 * operation counts as READY. This is a client-side settling check, not a
 * server acknowledgement; the placement path still revalidates the held item.
 */
public final class InventoryHelper {

	public enum EquipResult { READY, WAITING, MISSING, BLOCKED }

	/** Minimum settle after a hotbar selection change (200 ms at 20 TPS). */
	static final int HOTBAR_SWITCH_TICKS = 4;
	/** Minimum settle after a main-inventory swap (400 ms at 20 TPS). */
	static final int MAIN_REFILL_TICKS = 8;
	/** A still-unsettled switch is abandoned after this many ticks. */
	static final int SETTLE_TIMEOUT_TICKS = 40;

	private record Pending(LocalPlayer player, Item item, int slot, int startTick, int minDelay) {}

	private static Pending pending;

	private InventoryHelper() {}

	/**
	 * Pure settle decision for one pending switch, ordered so that a valid,
	 * already-arrived item is never blocked by age alone.
	 *
	 * @param age          ticks since our action
	 * @param minDelay     minimum ticks before READY
	 * @param slotMatches  the selected slot is still the one we switched to
	 * @param itemMatches  the selected stack is the requested item
	 */
	static EquipResult settleDecision(int age, int minDelay, boolean slotMatches,
									  boolean itemMatches) {
		if (!slotMatches) {
			return EquipResult.BLOCKED;
		}
		if (age >= minDelay && itemMatches) {
			return EquipResult.READY;
		}
		if (age > SETTLE_TIMEOUT_TICKS) {
			return EquipResult.BLOCKED;
		}
		return EquipResult.WAITING;
	}

	/**
	 * Inventory index of the first main-inventory/hotbar slot holding
	 * {@code item} (indices 0-35; armor and offhand are never touched),
	 * -1 if none.
	 */
	public static int findItem(LocalPlayer player, Item item) {
		var inv = player.getInventory();
		for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.getItem() == item) {
				return i;
			}
		}
		return -1;
	}

	/** Count of the item across the player inventory (hotbar + main). */
	public static int countItem(LocalPlayer player, Item item) {
		var inv = player.getInventory();
		int count = 0;
		for (int i = 0; i < 36 && i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.getItem() == item) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/**
	 * Ensures {@code item} is in the selected main-hand slot. Preference:
	 * already selected, then a matching hotbar slot (selection only, minimum
	 * {@link #HOTBAR_SWITCH_TICKS}), then a main-inventory slot (9-35) swapped
	 * into an empty hotbar slot or the selected one (minimum
	 * {@link #MAIN_REFILL_TICKS}).
	 */
	public static EquipResult equip(LocalPlayer player, Item item) {
		return equip(player, item, HOTBAR_SWITCH_TICKS, MAIN_REFILL_TICKS);
	}

	/** Same as {@link #equip(LocalPlayer, Item)} with configurable settle delays. */
	public static EquipResult equip(LocalPlayer player, Item item,
									dev.gentlebuild.config.BuildConfig config) {
		return equip(player, item,
				Math.max(1, Math.min(20, config.hotbarSwitchTicks)),
				Math.max(1, Math.min(20, config.mainRefillTicks)));
	}

	private static EquipResult equip(LocalPlayer player, Item item,
									 int hotbarDelay, int refillDelay) {
		Minecraft mc = Minecraft.getInstance();
		if (player.containerMenu != player.inventoryMenu
				|| !player.containerMenu.getCarried().isEmpty()
				|| mc.gameMode == null) {
			pending = null;
			return EquipResult.BLOCKED;
		}
		if (pending != null) {
			if (pending.player() != player) {
				pending = null;
			} else {
				boolean same = pending.item() == item;
				EquipResult settled = settle(player);
				if (settled == EquipResult.WAITING || settled == EquipResult.BLOCKED || same) {
					return settled;
				}
				// The earlier operation settled; this request is fulfilled on a later tick.
				return EquipResult.WAITING;
			}
		}
		var inv = player.getInventory();
		ItemStack selected = inv.getSelectedItem();
		if (!selected.isEmpty() && selected.getItem() == item) {
			return EquipResult.READY;
		}
		int hotbarMatch = findHotbar(player, item);
		int mainMatch = hotbarMatch >= 0 ? -1 : findMain(player, item);
		if (hotbarMatch < 0 && mainMatch < 0) {
			return EquipResult.MISSING;
		}
		int selectedSlot = inv.getSelectedSlot();
		if (hotbarMatch >= 0) {
			InputBroker.ownHotbar(selectedSlot, hotbarMatch);
			inv.setSelectedSlot(hotbarMatch);
			return arm(player, item, hotbarMatch, hotbarDelay);
		}
		int dest = inv.getItem(selectedSlot).isEmpty() ? selectedSlot : firstEmptyHotbar(player);
		if (dest < 0) {
			dest = selectedSlot;
		}
		// Malformed oversized stacks would be split by the swap: refuse before any click.
		ItemStack source = inv.getItem(mainMatch);
		ItemStack target = inv.getItem(dest);
		if (source.getCount() > source.getMaxStackSize()
				|| target.getCount() > target.getMaxStackSize()) {
			return EquipResult.BLOCKED;
		}
		InputBroker.ownHotbar(selectedSlot, dest);
		mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, mainMatch,
				dest, ContainerInput.SWAP, player);
		if (dest != selectedSlot) {
			inv.setSelectedSlot(dest);
		}
		return arm(player, item, dest, refillDelay);
	}

	private static EquipResult arm(LocalPlayer player, Item item, int slot, int minDelay) {
		pending = new Pending(player, item, slot, player.tickCount, minDelay);
		return EquipResult.WAITING;
	}

	/** Settling check after our own action; never clicks again. */
	private static EquipResult settle(LocalPlayer player) {
		Pending p = pending;
		var inv = player.getInventory();
		ItemStack selected = inv.getSelectedItem();
		boolean itemMatches = !selected.isEmpty() && selected.getItem() == p.item();
		EquipResult result = settleDecision(player.tickCount - p.startTick(), p.minDelay(),
				inv.getSelectedSlot() == p.slot(), itemMatches);
		if (result != EquipResult.WAITING) {
			pending = null;
		}
		return result;
	}

	/** Drops any pending settle check (pause, stop, world switch, release). */
	public static void cancelPending() {
		pending = null;
	}

	private static int findHotbar(LocalPlayer player, Item item) {
		var inv = player.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.getItem() == item) {
				return i;
			}
		}
		return -1;
	}

	private static int findMain(LocalPlayer player, Item item) {
		var inv = player.getInventory();
		for (int i = Inventory.getSelectionSize(); i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.getItem() == item) {
				return i;
			}
		}
		return -1;
	}

	private static int firstEmptyHotbar(LocalPlayer player) {
		var inv = player.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			if (inv.getItem(i).isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Restores the saved hotbar slot ONLY if the player still has our owned
	 * slot selected — never overrides a manual hotbar change.
	 */
	public static void restoreHotbar(LocalPlayer player) {
		int saved = InputBroker.savedHotbarSlot();
		int owned = InputBroker.ownedHotbarSlot();
		if (saved >= 0 && owned >= 0
				&& player.getInventory().getSelectedSlot() == owned) {
			player.getInventory().setSelectedSlot(saved);
		}
		InputBroker.clearHotbarOwnership();
	}
}
