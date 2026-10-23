package dev.gentlebuild.motion;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import dev.gentlebuild.config.BuildConfig;

/**
 * Turns the real player view toward an adopted goal. Each adopted goal gets
 * one {@link LookProfile.Plan} (reaction latency and peak speed). The turn is
 * a single combined yaw/pitch motion that accelerates and decelerates within
 * the configured limits, lands exactly on the goal and then holds still.
 * Small goal refinements update the target without restarting the plan.
 * Writes only view angles, never position.
 */
public class MotionController {

	public enum Mode { WALK, PRECISE }

	/** Yaw and pitch in degrees. */
	public record Rotation(double yaw, double pitch) {}

	private static final double ADOPT_PRECISE = 0.05;
	private static final double ADOPT_WALK = 3.5;
	private static final double NEW_GOAL_DEG = 6.0;
	private static final double ON_TARGET_DEG = 0.25;
	private static final double DISTANCE_EPS = 1e-4;

	private final LookProfile profile;
	private Mode mode = Mode.PRECISE;
	private boolean active;
	private boolean hasGoal;
	private double targetYaw;
	private double targetPitch;
	private boolean planPending;
	private LookProfile.Plan plan = LookProfile.Plan.INSTANT;
	private int waitTicks;
	private double angularSpeed;
	private double heldWalkPitch;

	public MotionController() {
		this(new LookProfile());
	}

	public MotionController(LookProfile profile) {
		this.profile = profile;
	}

	/** Precise aim target (final placement, cleanup, interact). */
	public void setTarget(double yaw, double pitch) {
		setGoal(Mode.PRECISE, yaw, pitch);
	}

	public void aimAt(LocalPlayer player, Vec3 point) {
		Vec3 eye = player.getEyePosition();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		setGoal(Mode.PRECISE, yaw, pitch);
	}

	/**
	 * Walking gaze toward a lookahead point. The walking pitch is captured
	 * when the walk starts and then held for the leg.
	 */
	public void walkToward(LocalPlayer player, Vec3 gazePoint,
						   double remainingWalkDistance) {
		Vec3 eye = player.getEyePosition();
		double dx = gazePoint.x - eye.x;
		double dz = gazePoint.z - eye.z;
		double yaw;
		if (dx * dx + dz * dz < 0.15 * 0.15 && hasGoal) {
			yaw = targetYaw;
		} else {
			yaw = Math.toDegrees(Math.atan2(-dx, dz));
		}
		boolean entering = mode != Mode.WALK || !hasGoal;
		double currentPitch = entering ? player.getXRot() : heldWalkPitch;
		walkGoal(yaw, currentPitch, remainingWalkDistance);
	}

	/** Pure walking heading: wrapped yaw plus the walking pitch for this leg. */
	public Rotation walkHeading(double yaw, double currentPitch,
								double remainingWalkDistance) {
		checkFinite(yaw, currentPitch);
		return new Rotation(AngleMath.wrap(yaw),
				profile.walkPitch(currentPitch, remainingWalkDistance));
	}

	/** Adopt a walking goal; the pitch is captured on entry and held afterwards. */
	public void walkGoal(double yaw, double currentPitch, double remainingWalkDistance) {
		boolean entering = mode != Mode.WALK || !hasGoal;
		Rotation goal = walkHeading(yaw, currentPitch, remainingWalkDistance);
		if (entering) {
			heldWalkPitch = goal.pitch();
		}
		setGoal(Mode.WALK, goal.yaw(), heldWalkPitch);
	}

	private static void checkFinite(double a, double b) {
		if (!Double.isFinite(a) || !Double.isFinite(b)) {
			throw new IllegalArgumentException("non-finite gaze input: " + a + ", " + b);
		}
	}

	private void setGoal(Mode newMode, double yaw, double pitch) {
		checkFinite(yaw, pitch);
		double wyaw = AngleMath.wrap(yaw);
		double wpitch = AngleMath.clamp(pitch, -89.0, 89.0);
		if (!hasGoal || newMode != mode) {
			adoptNewPlan(newMode, wyaw, wpitch);
		} else {
			double change = Math.max(Math.abs(AngleMath.shortestDelta(targetYaw, wyaw)),
					Math.abs(wpitch - targetPitch));
			double adopt = newMode == Mode.WALK ? ADOPT_WALK : ADOPT_PRECISE;
			if (change > NEW_GOAL_DEG) {
				adoptNewPlan(newMode, wyaw, wpitch);
			} else if (change > adopt) {
				// Refinement: move the goal only; wait, plan and speed stay.
				targetYaw = wyaw;
				targetPitch = wpitch;
			}
		}
		active = true;
	}

	private void adoptNewPlan(Mode newMode, double wyaw, double wpitch) {
		mode = newMode;
		targetYaw = wyaw;
		targetPitch = wpitch;
		hasGoal = true;
		planPending = true;
		angularSpeed = 0.0;
	}

	/** One tick of rotation update; call once per client tick. */
	public void tick(LocalPlayer player, BuildConfig config) {
		if (!active || player == null) {
			return;
		}
		Rotation next = step(player.getYRot(), player.getXRot(), config);
		player.setYRot((float) next.yaw());
		player.setXRot((float) next.pitch());
		// yRotO/xRotO deliberately untouched: LocalPlayerViewMixin lerps the
		// rendered view between them while isAimingView() is active.
	}

	/**
	 * Pure tick algorithm: the angles after one client tick of turning toward
	 * the adopted goal. Returns the incoming angles when inactive.
	 */
	public Rotation step(double yaw, double pitch, BuildConfig config) {
		if (!active || !hasGoal) {
			return new Rotation(yaw, pitch);
		}
		if (planPending) {
			double amplitude = Math.max(
					Math.abs(AngleMath.shortestDelta(yaw, targetYaw)),
					Math.abs(targetPitch - pitch));
			double strength = config.naturalMovement ? config.microCorrectionStrength : 0.0;
			plan = profile.plan(amplitude, mode == Mode.PRECISE, strength);
			waitTicks = plan.reactionTicks();
			planPending = false;
		}
		if (waitTicks > 0) {
			waitTicks--;
			angularSpeed = 0.0;
			return new Rotation(yaw, pitch);
		}
		double yawErr = AngleMath.shortestDelta(yaw, targetYaw);
		double pitchErr = targetPitch - pitch;
		double distance = Math.hypot(yawErr, pitchErr);
		if (distance < DISTANCE_EPS) {
			angularSpeed = 0.0;
			return new Rotation(targetYaw, targetPitch);
		}
		double cap = Double.POSITIVE_INFINITY;
		if (plan != LookProfile.Plan.INSTANT) {
			cap = Math.min(cap, plan.peakDegPerTick() * 20.0);
		}
		if (Math.abs(yawErr) > 1e-9) {
			cap = Math.min(cap, config.maxYawSpeed * distance / Math.abs(yawErr));
		}
		if (Math.abs(pitchErr) > 1e-9) {
			cap = Math.min(cap, config.maxPitchSpeed * distance / Math.abs(pitchErr));
		}
		double accel = config.maxAngularAccel;
		double desired = Math.min(cap, Math.sqrt(2.0 * accel * distance));
		double dv = Math.max(-accel / 20.0, Math.min(accel / 20.0, desired - angularSpeed));
		angularSpeed = Math.max(0.0, Math.min(cap, angularSpeed + dv));
		double stepDistance = angularSpeed / 20.0;
		if (stepDistance >= distance) {
			angularSpeed = 0.0;
			return new Rotation(targetYaw, targetPitch);
		}
		double fraction = stepDistance / distance;
		return new Rotation(
				AngleMath.wrap(yaw + yawErr * fraction),
				AngleMath.clamp(pitch + pitchErr * fraction, -89.0, 89.0));
	}

	/** True when the real angles are within the on-target tolerance of the adopted goal. */
	public boolean isOnTarget(double yaw, double pitch) {
		return active && hasGoal
				&& Math.abs(AngleMath.shortestDelta(yaw, targetYaw)) < ON_TARGET_DEG
				&& Math.abs(pitch - targetPitch) < ON_TARGET_DEG;
	}

	public boolean isOnTarget(LocalPlayer player) {
		return player != null && isOnTarget(player.getYRot(), player.getXRot());
	}

	public void reset() {
		active = false;
		hasGoal = false;
		planPending = false;
		plan = LookProfile.Plan.INSTANT;
		waitTicks = 0;
		angularSpeed = 0.0;
	}

	public boolean isActive() {
		return active;
	}

	public Mode mode() {
		return mode;
	}
}
