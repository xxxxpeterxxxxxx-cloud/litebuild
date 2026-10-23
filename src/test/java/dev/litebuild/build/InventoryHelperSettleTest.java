package dev.litebuild.build;

import org.junit.jupiter.api.Test;
import static dev.litebuild.build.InventoryHelper.EquipResult.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryHelperSettleTest {

	@Test
	void hotbarSwitchWaitsItsMinimumThenReady() {
		int min = InventoryHelper.HOTBAR_SWITCH_TICKS;
		assertEquals(4, min);
		for (int age = 0; age < min; age++) {
			assertEquals(WAITING, InventoryHelper.settleDecision(age, min, true, true), "age " + age);
		}
		assertEquals(READY, InventoryHelper.settleDecision(min, min, true, true));
	}

	@Test
	void mainRefillWaitsItsMinimumThenReady() {
		int min = InventoryHelper.MAIN_REFILL_TICKS;
		assertEquals(8, min);
		assertEquals(WAITING, InventoryHelper.settleDecision(7, min, true, true));
		assertEquals(READY, InventoryHelper.settleDecision(8, min, true, true));
	}

	@Test
	void wrongSelectedSlotBlocksImmediately() {
		assertEquals(BLOCKED, InventoryHelper.settleDecision(0, 4, false, true));
		assertEquals(BLOCKED, InventoryHelper.settleDecision(9, 8, false, false));
	}

	@Test
	void validHeldItemPolledLateIsReadyNotBlocked() {
		// A completed switch observed after the 40-tick window must not be blocked by age alone.
		assertEquals(READY, InventoryHelper.settleDecision(45, 8, true, true));
		assertEquals(READY, InventoryHelper.settleDecision(41, 4, true, true));
	}

	@Test
	void nonDefaultDelayUsesItsOwnWindow() {
		int custom = 7;
		assertEquals(WAITING, InventoryHelper.settleDecision(6, custom, true, true));
		assertEquals(READY, InventoryHelper.settleDecision(7, custom, true, true));
	}

	@Test
	void stillAbsentItemTimesOutOnlyAfterTheWindow() {
		assertEquals(WAITING, InventoryHelper.settleDecision(10, 8, true, false));
		assertEquals(WAITING, InventoryHelper.settleDecision(40, 8, true, false));
		assertEquals(BLOCKED, InventoryHelper.settleDecision(41, 8, true, false));
	}
}
