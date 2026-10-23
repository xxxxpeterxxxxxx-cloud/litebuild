package dev.litebuild;

import org.junit.jupiter.api.Test;
import dev.litebuild.config.BuildConfig;
import dev.litebuild.motion.BuildRhythm;
import dev.litebuild.motion.LookProfile;
import static org.junit.jupiter.api.Assertions.*;

/** Pure seeded checks for the turn plan, walk pitch and the build rhythm. */
class MotionBehaviorTest {

	private static BuildConfig cfg() {
		return new BuildConfig();
	}

	// ---- LookProfile ------------------------------------------------

	@Test
	void planIsBoundedAndDeterministic() {
		var a = new LookProfile(new java.util.Random(42)).plan(45.0, true, 1.0);
		var b = new LookProfile(new java.util.Random(42)).plan(45.0, true, 1.0);
		assertEquals(a, b);
		assertTrue(a.reactionTicks() >= 0 && a.reactionTicks() <= 3);
		assertTrue(a.peakDegPerTick() > 0 && Double.isFinite(a.peakDegPerTick()));
	}

	@Test
	void reactionOnlyForLargeDeliberateTurns() {
		var p = new LookProfile(new java.util.Random(7));
		for (int i = 0; i < 200; i++) {
			assertEquals(0, p.plan(5.0, true, 1.0).reactionTicks(), "small precise");
			assertEquals(0, p.plan(40.0, false, 1.0).reactionTicks(), "walk steering");
		}
		int delayed = 0;
		for (int i = 0; i < 200; i++) {
			int r = p.plan(40.0, true, 1.0).reactionTicks();
			assertTrue(r >= 0 && r <= 3);
			if (r > 0) {
				delayed++;
			}
		}
		assertTrue(delayed > 0, "large precise turns must sometimes wait");
	}

	@Test
	void instantWhenStrengthZeroOrTiny() {
		var p = new LookProfile(new java.util.Random(42));
		assertSame(LookProfile.Plan.INSTANT, p.plan(120.0, true, 0.0));
		assertSame(LookProfile.Plan.INSTANT, p.plan(0.1, true, 1.0));
	}

	@Test
	void peakSpeedScalesWithAmplitude() {
		var p = new LookProfile(new java.util.Random(42));
		double small = p.plan(5.0, true, 1.0).peakDegPerTick();
		double large = p.plan(90.0, true, 1.0).peakDegPerTick();
		assertTrue(large > small * 1.5, small + " vs " + large);
		assertTrue(large * 20.0 <= 420.0 * 1.10 + 1e-9, "speed stays bounded");
	}

	@Test
	void walkPitchIsDeterministicAndClamped() {
		var p = new LookProfile(new java.util.Random(42));
		assertEquals(25.0, p.walkPitch(50.0, 10.0), 1e-9);
		assertEquals(-10.0, p.walkPitch(-60.0, 10.0), 1e-9);
		assertEquals(5.0, p.walkPitch(5.0, 10.0), 1e-9, "comfortable pitch kept");
		assertEquals(50.0, p.walkPitch(50.0, 1.0), 1e-9, "short leg keeps working pitch");
		assertEquals(60.0, p.walkPitch(70.0, 1.0), 1e-9);
		assertEquals(-15.0, p.walkPitch(-20.0, 1.0), 1e-9);
	}

	// ---- BuildRhythm ------------------------------------------------

	@Test
	void shortPauseAfterConfiguredActions() {
		var r = new BuildRhythm(new java.util.Random(42));
		var c = cfg();
		c.shortPauseMinActions = c.shortPauseMaxActions = 2;
		c.shortPauseMinTicks = c.shortPauseMaxTicks = 8;
		c.afkIntervalMinTicks = c.afkIntervalMaxTicks = 72000;
		r.reset(c);
		r.actionConfirmed(c);
		r.actionConfirmed(c);
		assertEquals(BuildRhythm.PauseKind.NONE, r.tryStart(false, c),
				"unsafe spot must defer");
		assertEquals(BuildRhythm.PauseKind.SHORT, r.tryStart(true, c));
		assertEquals(8, r.remainingTicks());
		for (int i = 0; i < 8; i++) {
			r.tickPause();
		}
		assertEquals(BuildRhythm.PauseKind.NONE, r.kind());
		// Due was consumed: no immediate second pause.
		assertEquals(BuildRhythm.PauseKind.NONE, r.tryStart(true, c));
	}

	@Test
	void afkWinsAndExpiresOnce() {
		var r = new BuildRhythm(new java.util.Random(42));
		var c = cfg();
		c.shortPauseMinActions = c.shortPauseMaxActions = 1;
		c.afkIntervalMinTicks = c.afkIntervalMaxTicks = 80;
		c.afkDurationMinTicks = c.afkDurationMaxTicks = 16;
		r.reset(c);
		for (int i = 0; i < 80; i++) {
			r.activeTick(c);
		}
		r.actionConfirmed(c); // both dues armed -> AFK priority
		assertEquals(BuildRhythm.PauseKind.AFK, r.tryStart(true, c));
		assertEquals(16, r.remainingTicks());
		for (int i = 0; i < 16; i++) {
			r.tickPause();
		}
		assertEquals(BuildRhythm.PauseKind.NONE, r.kind());
		assertEquals(BuildRhythm.PauseKind.NONE, r.tryStart(true, c));
	}

	@Test
	void afkNeverExceedsFiveSecondsEvenWithoutClamp() {
		var r = new BuildRhythm(new java.util.Random(42));
		var c = cfg();
		c.afkIntervalMinTicks = c.afkIntervalMaxTicks = 20;
		c.afkDurationMinTicks = c.afkDurationMaxTicks = 400; // programmatic, unclamped
		r.reset(c);
		for (int i = 0; i < 20; i++) {
			r.activeTick(c);
		}
		assertEquals(BuildRhythm.PauseKind.AFK, r.tryStart(true, c));
		assertTrue(r.remainingTicks() <= 100, "AFK capped at 5 s: " + r.remainingTicks());
	}

	@Test
	void disabledAndManualCancel() {
		var r = new BuildRhythm(new java.util.Random(42));
		var c = cfg();
		c.shortPauseMinActions = c.shortPauseMaxActions = 1;
		r.reset(c);
		r.actionConfirmed(c);
		c.automaticPauses = false;
		assertEquals(BuildRhythm.PauseKind.NONE, r.tryStart(true, c));
		c.automaticPauses = true;
		assertEquals(BuildRhythm.PauseKind.SHORT, r.tryStart(true, c));
		r.cancelPause();
		assertEquals(BuildRhythm.PauseKind.NONE, r.kind());
	}

	@Test
	void invalidRangesAreSanitized() {
		var c = cfg();
		c.shortPauseMinActions = 50;
		c.shortPauseMaxActions = 2;   // max < min
		c.afkIntervalMinTicks = 5;    // below bound
		c.afkDurationMinTicks = 160;  // old beta.4 default, above the 5 s ceiling
		c.afkDurationMaxTicks = 400;
		c.microCorrectionStrength = 4.0;
		c.clamp();
		assertTrue(c.shortPauseMaxActions >= c.shortPauseMinActions);
		assertTrue(c.afkIntervalMinTicks >= 20);
		assertTrue(c.afkDurationMinTicks >= 1 && c.afkDurationMaxTicks <= 100);
		assertTrue(c.microCorrectionStrength >= 0 && c.microCorrectionStrength <= 1);
	}
}
