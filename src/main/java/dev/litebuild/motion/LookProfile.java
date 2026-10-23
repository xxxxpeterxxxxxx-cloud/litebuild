package dev.litebuild.motion;

import java.util.random.RandomGenerator;

/**
 * Per-goal turn plan, sampled ONCE when a new gaze goal is adopted. A plan
 * only carries a reaction latency and a peak turn speed; the turn itself is a
 * single deliberate combined yaw/pitch motion executed by
 * {@link MotionController}. Nothing is sampled per tick.
 */
public final class LookProfile {

	/** One planned turn: wait {@code reactionTicks}, then turn at up to {@code peakDegPerTick}. */
	public record Plan(int reactionTicks, double peakDegPerTick) {
		/** Humanization off: no latency, no speed cap beyond the config limits. */
		public static final Plan INSTANT = new Plan(0, 0.0);
	}

	private final RandomGenerator random;

	public LookProfile() {
		this(RandomGenerator.getDefault());
	}

	public LookProfile(RandomGenerator random) {
		this.random = random;
	}

	/**
	 * @param amplitude total angular distance to the goal, degrees
	 * @param precise   work aim (true) vs. walk steering (false)
	 * @param strength  0..1 — 0 disables variation entirely
	 */
	public Plan plan(double amplitude, boolean precise, double strength) {
		if (strength <= 0.0 || amplitude < 0.25) {
			return Plan.INSTANT;
		}
		int reaction = precise && amplitude >= 12.0 ? 1 + random.nextInt(3) : 0;
		double base = Math.min(140.0 + 3.0 * amplitude, 420.0);
		double jitter = (random.nextDouble() * 2.0 - 1.0) * 0.10 * strength;
		return new Plan(reaction, base * (1.0 + jitter) / 20.0);
	}

	/**
	 * Walking gaze pitch: a long leg (&gt;3 blocks) looks slightly down at the
	 * footing, a short reposition keeps the working pitch. Deterministic — the
	 * current pitch is kept whenever it already sits inside the range.
	 */
	public double walkPitch(double currentPitch, double remainingDistance) {
		if (remainingDistance > 3.0) {
			return AngleMath.clamp(currentPitch, -10.0, 25.0);
		}
		return AngleMath.clamp(currentPitch, -15.0, 60.0);
	}
}
