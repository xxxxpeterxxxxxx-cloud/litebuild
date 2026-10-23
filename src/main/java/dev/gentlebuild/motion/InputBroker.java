package dev.gentlebuild.motion;

import net.minecraft.world.entity.player.Input;

/**
 * Single owner of all mod-driven input. The KeyboardInput mixin delegates to
 * this broker; on any halt every owned input is released and the hotbar slot
 * restored only if we switched it.
 */
public final class InputBroker {
	private static Input desired = Input.EMPTY;
	private static boolean controlling;
	private static boolean attackHeldByMod;
	private static int ownedHotbarSlot = -1;
	private static int savedHotbarSlot = -1;
	private static Input lastVanilla = Input.EMPTY;
	private static long manualInputTick = Long.MIN_VALUE;

	private InputBroker() {}

	/** Called from the mixin with the real key state, before our override. */
	public static void noteManualInput(Input vanilla) {
		lastVanilla = vanilla;
		if (!Input.EMPTY.equals(vanilla)) {
			manualInputTick = System.nanoTime();
		}
	}

	public static Input lastVanilla() {
		return lastVanilla;
	}

	/** True if the real keyboard produced any non-empty input recently. */
	public static boolean manualInputRecently() {
		return !Input.EMPTY.equals(lastVanilla)
				&& System.nanoTime() - manualInputTick < 250_000_000L;
	}

	public static void setDesired(Input input) {
		desired = input;
		controlling = true;
	}

	public static boolean isControlling() {
		return controlling;
	}

	public static Input desired() {
		return desired;
	}

	/** Called by the mixin AFTER vanilla key polling, before physics. */
	public static Input apply(Input vanilla) {
		return controlling ? desired : vanilla;
	}

	public static void releaseAll() {
		desired = Input.EMPTY;
		controlling = false;
		holdAttack(false);
	}

	/**
	 * Hold or release the vanilla attack key. While held, Minecraft's own
	 * {@code continueAttack} drives the whole break sequence (START ->
	 * progress -> STOP, plus arm swing) exactly like a held left mouse
	 * button; releasing it lets vanilla send the matching ABORT/STOP.
	 */
	public static void holdAttack(boolean held) {
		attackHeldByMod = held;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.options != null && mc.options.keyAttack.isDown() != held) {
			mc.options.keyAttack.setDown(held);
		}
	}

	/** Whether the current keyAttack hold originates from the mod itself —
	 *  lets the manual-input guard distinguish our held LMB from a real
	 *  physical press. */
	public static boolean attackHeldByMod() {
		return attackHeldByMod;
	}

	/** Record ownership of a hotbar switch; first call remembers the slot. */
	public static void ownHotbar(int restoredFrom, int slot) {
		if (ownedHotbarSlot < 0) {
			savedHotbarSlot = restoredFrom;
		}
		ownedHotbarSlot = slot;
	}

	/** Update ownership when we switch again; keeps the original saved slot. */
	public static void updateOwnedHotbar(int slot) {
		ownedHotbarSlot = slot;
	}

	public static int savedHotbarSlot() {
		return savedHotbarSlot;
	}

	public static void clearHotbarOwnership() {
		ownedHotbarSlot = -1;
		savedHotbarSlot = -1;
	}

	public static int ownedHotbarSlot() {
		return ownedHotbarSlot;
	}
}
