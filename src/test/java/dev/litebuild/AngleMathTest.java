package dev.litebuild;

import org.junit.jupiter.api.Test;
import dev.litebuild.motion.AngleMath;
import static org.junit.jupiter.api.Assertions.*;

class AngleMathTest {

	@Test
	void shortestDeltaWrapsAcross180() {
		assertEquals(2.0, AngleMath.shortestDelta(179.0, -179.0), 1e-6);
		assertEquals(-2.0, AngleMath.shortestDelta(-179.0, 179.0), 1e-6);
	}

	@Test
	void wrapBoundaries() {
		assertEquals(-179.0, AngleMath.wrap(181.0), 1e-6);
		assertEquals(179.0, AngleMath.wrap(-181.0), 1e-6);
		assertEquals(0.0, AngleMath.wrap(360.0), 1e-6);
	}

	@Test
	void stepRespectsSpeedAndAccel() {
		double angle = 0;
		double velocity = 0;
		double maxSpeed = 100.0, maxAccel = 300.0, dt = 1.0 / 20.0;
		for (int i = 0; i < 400; i++) {
			double[] r = AngleMath.step(angle, velocity, 90.0, maxSpeed, maxAccel, dt);
			angle = r[0];
			velocity = r[1];
			assertTrue(Math.abs(velocity) <= maxSpeed + 1e-6, "speed exceeded");
		}
		assertEquals(90.0, angle, 0.5);
		assertEquals(0.0, velocity, 1e-6);
	}

	@Test
	void noOvershootOrOscillation() {
		double angle = 170.0, velocity = 0;
		double prevError = Double.MAX_VALUE;
		boolean overshot = false;
		for (int i = 0; i < 400; i++) {
			double[] r = AngleMath.step(angle, velocity, -175.0, 100, 300, 0.05);
			angle = r[0];
			velocity = r[1];
			double err = Math.abs(AngleMath.shortestDelta(angle, -175.0));
			if (err > prevError + 1e-9) {
				overshot = true;
			}
			prevError = err;
		}
		assertFalse(overshot, "angle oscillated/overshot");
		assertEquals(-175.0, angle, 0.5);
	}
}
