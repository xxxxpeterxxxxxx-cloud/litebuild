package dev.gentlebuild.motion;

/**
 * Pure angular math used by the motion controller. Degrees everywhere.
 */
public final class AngleMath {

	private AngleMath() {}

	/** Wraps an angle to [-180, 180). */
	public static double wrap(double angle) {
		double wrapped = angle % 360.0;
		if (wrapped >= 180.0) {
			wrapped -= 360.0;
		} else if (wrapped < -180.0) {
			wrapped += 360.0;
		}
		return wrapped;
	}

	/** Shortest signed angular distance from {@code from} to {@code to}. */
	public static double shortestDelta(double from, double to) {
		return wrap(to - from);
	}

	/**
	 * Advances {@code current} towards {@code target} with a rate limit of
	 * {@code maxSpeed} deg/s and an angular acceleration limit of
	 * {@code maxAccel} deg/s^2 applied to {@code velocity} (deg/s).
	 * {@code dt} is the step duration in seconds.
	 * Returns the new velocity; the new angle can be read from
	 * {@link #advance(double, double, double)}.
	 */
	public static double advance(double current, double velocity, double dt) {
		return wrap(current + velocity * dt);
	}

	/**
	 * One integration step of an acceleration-limited, braking-aware tracker.
	 *
	 * @return double[]{newAngle, newVelocity}
	 */
	public static double[] step(double current, double velocity, double target,
								double maxSpeed, double maxAccel, double dt) {
		double error = shortestDelta(current, target);

		// Final snap: close enough and slow enough -> arrive exactly.
		if (Math.abs(error) < 0.1 && Math.abs(velocity) < maxSpeed * dt) {
			return new double[]{target, 0.0};
		}

		// Velocity needed to arrive in one tick if unconstrained.
		double desired = error / dt;
		desired = clamp(desired, -maxSpeed, maxSpeed);

		// Braking distance: v^2 / (2*a). If we would overshoot, brake.
		double brakeDist = (velocity * velocity) / (2.0 * maxAccel);
		double direction = Math.signum(error);
		if (direction != 0.0 && Math.signum(velocity) == direction
				&& brakeDist >= Math.abs(error)) {
			desired = velocity - direction * maxAccel * dt;
		}

		double dv = desired - velocity;
		dv = clamp(dv, -maxAccel * dt, maxAccel * dt);
		double newVelocity = velocity + dv;
		if (Math.abs(newVelocity) > maxSpeed) {
			newVelocity = Math.signum(newVelocity) * maxSpeed;
		}

		double newAngle = current + newVelocity * dt;
		// Prevent oscillation: landing only when moving TOWARD the target and
		// the step would cross it.
		if (Math.signum(newVelocity) == Math.signum(error)
				&& Math.abs(newVelocity * dt) >= Math.abs(error)) {
			newAngle = target;
			newVelocity = 0.0;
		}
		return new double[]{wrap(newAngle), newVelocity};
	}

	public static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
