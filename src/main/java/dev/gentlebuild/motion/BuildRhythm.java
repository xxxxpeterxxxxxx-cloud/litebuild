package dev.gentlebuild.motion;

import java.util.random.RandomGenerator;
import dev.gentlebuild.config.BuildConfig;

/**
 * Pure tick-based work/pause timekeeper. No game access — the controller
 * feeds confirmed actions and active work ticks; the rhythm decides when
 * a short pause (8–24 ticks) or an AFK break (at most 5 s every 2–5 active
 * minutes) is due. A due pause never fires while the spot is unsafe and
 * never repeats until fresh work has accrued.
 */
public final class BuildRhythm {

	public enum PauseKind { NONE, SHORT, AFK }

	/** Hard ceiling for one AFK break (5 s). */
	public static final int MAX_AFK_TICKS = 100;

	private final RandomGenerator random;

	private int activeTicks;
	private int actionsSinceShort;
	private int shortThreshold = -1;
	private int afkAt = -1;
	private PauseKind kind = PauseKind.NONE;
	private int remainingTicks;

	public BuildRhythm() {
		this(RandomGenerator.getDefault());
	}

	public BuildRhythm(RandomGenerator random) {
		this.random = random;
	}

	public void reset(BuildConfig config) {
		activeTicks = 0;
		actionsSinceShort = 0;
		shortThreshold = sampleShortThreshold(config);
		afkAt = sampleAfkInterval(config);
		kind = PauseKind.NONE;
		remainingTicks = 0;
	}

	/** One tick of active building (never during a pause or manual stop). */
	public void activeTick(BuildConfig config) {
		if (kind == PauseKind.NONE) {
			activeTicks++;
			if (afkAt < 0) {
				afkAt = sampleAfkInterval(config);
			}
		}
	}

	/** Count one acknowledged non-auxiliary action. */
	public void actionConfirmed(BuildConfig config) {
		if (shortThreshold < 0) {
			shortThreshold = sampleShortThreshold(config);
		}
		actionsSinceShort++;
	}

	/**
	 * Start a scheduled pause if one is due and the spot is safe. A due
	 * pause is kept (not consumed) while unsafe. AFK wins over SHORT.
	 */
	public PauseKind tryStart(boolean safe, BuildConfig config) {
		if (!config.automaticPauses) {
			cancelPause();
			return PauseKind.NONE;
		}
		if (kind != PauseKind.NONE) {
			return kind;
		}
		PauseKind due = PauseKind.NONE;
		if (activeTicks >= afkAt && afkAt >= 0) {
			due = PauseKind.AFK;
		} else if (actionsSinceShort >= shortThreshold && shortThreshold >= 0) {
			due = PauseKind.SHORT;
		}
		if (due == PauseKind.NONE || !safe) {
			return PauseKind.NONE;
		}
		kind = due;
		remainingTicks = due == PauseKind.AFK
				? sampleAfkDuration(config) : sampleShortDuration(config);
		if (due == PauseKind.AFK) {
			activeTicks = 0;
			afkAt = sampleAfkInterval(config);
		}
		actionsSinceShort = 0;
		shortThreshold = sampleShortThreshold(config);
		return kind;
	}

	/** Advance an active pause by one tick; kind returns to NONE at 0. */
	public void tickPause() {
		if (kind != PauseKind.NONE && --remainingTicks <= 0) {
			kind = PauseKind.NONE;
			remainingTicks = 0;
		}
	}

	public void cancelPause() {
		kind = PauseKind.NONE;
		remainingTicks = 0;
	}

	public PauseKind kind() {
		return kind;
	}

	public int remainingTicks() {
		return remainingTicks;
	}

	private int sampleShortThreshold(BuildConfig c) {
		return c.shortPauseMinActions
				+ random.nextInt(Math.max(1,
						c.shortPauseMaxActions - c.shortPauseMinActions + 1));
	}

	private int sampleShortDuration(BuildConfig c) {
		return c.shortPauseMinTicks
				+ random.nextInt(Math.max(1,
						c.shortPauseMaxTicks - c.shortPauseMinTicks + 1));
	}

	private int sampleAfkInterval(BuildConfig c) {
		return c.afkIntervalMinTicks
				+ random.nextInt(Math.max(1,
						c.afkIntervalMaxTicks - c.afkIntervalMinTicks + 1));
	}

	private int sampleAfkDuration(BuildConfig c) {
		int sampled = c.afkDurationMinTicks
				+ random.nextInt(Math.max(1,
						c.afkDurationMaxTicks - c.afkDurationMinTicks + 1));
		return Math.min(MAX_AFK_TICKS, sampled);
	}
}
