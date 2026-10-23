package dev.litebuild;

import org.junit.jupiter.api.Test;
import dev.litebuild.config.BuildConfig;
import dev.litebuild.motion.AngleMath;
import dev.litebuild.motion.LookProfile;
import dev.litebuild.motion.MotionController;
import dev.litebuild.motion.MotionController.Rotation;
import static org.junit.jupiter.api.Assertions.*;

/** Pure checks of the tick algorithm: no player, no game state. */
class MotionControllerTest {

	private static MotionController seeded() {
		return new MotionController(new LookProfile(new java.util.Random(42)));
	}

	private static BuildConfig plainCfg() {
		BuildConfig c = new BuildConfig();
		c.naturalMovement = false;
		return c;
	}

	/** Runs {@code ticks} steps from the given angles; returns the last rotation. */
	private static Rotation run(MotionController m, double yaw, double pitch,
								BuildConfig c, int ticks) {
		Rotation r = new Rotation(yaw, pitch);
		for (int i = 0; i < ticks; i++) {
			r = m.step(r.yaw(), r.pitch(), c);
		}
		return r;
	}

	private static double dist(Rotation a, Rotation b) {
		return Math.hypot(AngleMath.shortestDelta(a.yaw(), b.yaw()), a.pitch() - b.pitch());
	}

	@Test
	void inactiveReturnsIncomingAngles() {
		Rotation r = seeded().step(10.0, 20.0, plainCfg());
		assertEquals(10.0, r.yaw(), 0.0);
		assertEquals(20.0, r.pitch(), 0.0);
	}

	@Test
	void sameGoalRequestsDoNotChangeTheTurn() {
		BuildConfig c = new BuildConfig();
		MotionController once = seeded();
		MotionController repeated = seeded();
		once.setTarget(30.0, 10.0);
		repeated.setTarget(30.0, 10.0);
		Rotation a = new Rotation(0.0, 0.0);
		Rotation b = new Rotation(0.0, 0.0);
		for (int i = 0; i < 50; i++) {
			a = once.step(a.yaw(), a.pitch(), c);
			repeated.setTarget(30.0, 10.0); // identical request every tick
			b = repeated.step(b.yaw(), b.pitch(), c);
			assertEquals(a, b, "tick " + i);
		}
		assertEquals(30.0, a.yaw(), 0.25 + 1e-9);
		assertEquals(10.0, a.pitch(), 0.25 + 1e-9);
	}

	@Test
	void shortestPathAcrossTheSeam() {
		MotionController m = seeded();
		m.setTarget(-179.0, 0.0);
		BuildConfig c = plainCfg();
		Rotation r = new Rotation(179.0, 0.0);
		for (int i = 0; i < 200; i++) {
			r = m.step(r.yaw(), r.pitch(), c);
			double travelled = AngleMath.shortestDelta(179.0, r.yaw());
			assertTrue(travelled >= -1e-9 && travelled <= 2.0 + 1e-9,
					"spin detected: " + travelled);
		}
		assertTrue(m.isOnTarget(r.yaw(), r.pitch()));
	}

	@Test
	void smallRefinementsProgressAndConverge() {
		BuildConfig c = new BuildConfig();
		MotionController m = seeded();
		m.setTarget(40.0, 0.0);
		Rotation r = run(m, 0.0, 0.0, c, 5);
		for (int k = 1; k <= 20; k++) {
			m.setTarget(40.0 + 0.15 * k, 0.0); // 0.15 deg per tick refinements
			r = m.step(r.yaw(), r.pitch(), c);
		}
		assertTrue(r.yaw() > 3.0, "refinements must not starve the turn: " + r.yaw());
		r = run(m, r.yaw(), r.pitch(), c, 300);
		assertEquals(43.0, r.yaw(), 0.25 + 1e-9);
		Rotation settled = m.step(r.yaw(), r.pitch(), c);
		assertEquals(r, settled, "exactly still once converged");
	}

	@Test
	void combinedYawPitchMoveAsOneArc() {
		BuildConfig c = plainCfg();
		MotionController m = seeded();
		m.setTarget(60.0, 30.0);
		Rotation r = new Rotation(0.0, 0.0);
		for (int i = 0; i < 40; i++) {
			r = m.step(r.yaw(), r.pitch(), c);
			assertEquals(r.yaw() / 60.0, r.pitch() / 30.0, 1e-9, "tick " + i);
		}
		assertTrue(m.isOnTarget(r.yaw(), r.pitch()));
	}

	@Test
	void perAxisSpeedLimitsHold() {
		BuildConfig c = plainCfg();
		c.maxYawSpeed = 40.0;
		c.maxPitchSpeed = 10.0;
		MotionController m = seeded();
		m.setTarget(90.0, 5.0);
		Rotation r = new Rotation(0.0, 0.0);
		for (int i = 0; i < 200; i++) {
			Rotation next = m.step(r.yaw(), r.pitch(), c);
			assertTrue(Math.abs(AngleMath.shortestDelta(r.yaw(), next.yaw())) <= 40.0 / 20.0 + 1e-9);
			assertTrue(Math.abs(next.pitch() - r.pitch()) <= 10.0 / 20.0 + 1e-9);
			r = next;
		}
		assertEquals(90.0, r.yaw(), 0.25 + 1e-9);
	}

	@Test
	void accelerationLimitsTheFirstStep() {
		BuildConfig c = plainCfg();
		c.maxAngularAccel = 50.0;
		MotionController m = seeded();
		m.setTarget(100.0, 0.0);
		Rotation first = m.step(0.0, 0.0, c);
		assertTrue(dist(new Rotation(0.0, 0.0), first) <= 0.125 + 1e-9,
				"first step " + first);
		assertTrue(first.yaw() > 0.0);
	}

	@Test
	void reversedGoalMovesTowardNewTargetNotResidualSpeed() {
		BuildConfig c = plainCfg();
		c.maxAngularAccel = 50.0;
		MotionController m = seeded();
		m.setTarget(90.0, 0.0);
		Rotation r = run(m, 0.0, 0.0, c, 30);
		assertTrue(r.yaw() > 10.0);
		m.setTarget(-90.0, 0.0);
		Rotation next = m.step(r.yaw(), r.pitch(), c);
		double delta = AngleMath.shortestDelta(r.yaw(), next.yaw());
		assertTrue(delta < 0.0, "must head toward the new goal: " + delta);
		assertTrue(Math.abs(delta) <= 0.125 + 1e-9, "speed restarts from rest: " + delta);
	}

	@Test
	void naturalMovementOffHasNoRandomDelay() {
		MotionController m = seeded();
		m.setTarget(20.0, 0.0);
		Rotation first = m.step(0.0, 0.0, plainCfg());
		assertTrue(first.yaw() > 0.0, "no reaction latency without naturalMovement");
	}

	@Test
	void walkJitterUnderDeadzoneLeavesHeadingStill() {
		BuildConfig c = new BuildConfig();
		MotionController m = seeded();
		m.walkGoal(30.0, 5.0, 10.0);
		Rotation r = run(m, 0.0, 5.0, c, 300);
		assertEquals(30.0, r.yaw(), 0.25 + 1e-9);
		m.walkGoal(31.5, 5.0, 10.0); // 1.5 deg change: inside the 3.5 deg walk deadzone
		for (int i = 0; i < 10; i++) {
			Rotation next = m.step(r.yaw(), r.pitch(), c);
			assertEquals(r, next, "tick " + i);
		}
	}

	@Test
	void walkPitchIsHeldAcrossTheLeg() {
		BuildConfig c = plainCfg();
		MotionController m = seeded();
		m.walkGoal(30.0, 5.0, 10.0);
		m.walkGoal(30.0, 40.0, 1.0); // later pitch readings must not move the held pitch
		Rotation r = run(m, 30.0, 5.0, c, 300);
		assertEquals(5.0, r.pitch(), 0.25 + 1e-9);
	}

	@Test
	void walkHeadingIsPure() {
		var m = seeded();
		Rotation a = m.walkHeading(200.0, 4.0, 10.0);
		assertEquals(-160.0, a.yaw(), 1e-9);
		assertEquals(4.0, a.pitch(), 1e-9);
	}

	@Test
	void pitchIsClampedToHumanRange() {
		BuildConfig c = plainCfg();
		MotionController m = seeded();
		m.setTarget(0.0, 120.0);
		Rotation r = run(m, 0.0, 0.0, c, 300);
		assertEquals(89.0, r.pitch(), 1e-9);
	}

	@Test
	void nonFiniteInputIsRejected() {
		MotionController m = seeded();
		assertThrows(IllegalArgumentException.class, () -> m.setTarget(Double.NaN, 0.0));
		assertThrows(IllegalArgumentException.class, () -> m.setTarget(0.0, Double.POSITIVE_INFINITY));
		assertThrows(IllegalArgumentException.class, () -> m.walkGoal(0.0, Double.NaN, 1.0));
		assertThrows(IllegalArgumentException.class, () -> m.walkHeading(Double.NaN, 0.0, 1.0));
	}
}
