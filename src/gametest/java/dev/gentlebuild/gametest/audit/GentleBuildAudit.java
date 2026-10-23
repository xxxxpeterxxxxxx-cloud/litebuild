package dev.gentlebuild.gametest.audit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.gentlebuild.build.BuildController;
import dev.gentlebuild.model.Blueprint;
import dev.gentlebuild.motion.AngleMath;
import dev.gentlebuild.motion.InputBroker;

/**
 * Anticheat-style wire audit for GentleBuild traffic. While recording, every
 * serverbound packet (tapped in Connection.send) plus one player-state sample
 * per client tick is stored; {@link #stopRecording()} then evaluates the same
 * invariant classes real anticheats (Grim et al.) check on a vanilla client:
 *
 *   rotation     |dyaw|,|dpitch| <= 45 deg/tick, pitch in [-90,90], no NaN
 *   movement     |dpos| <= 3 blocks/tick in transmitted move packets, no NaN
 *   use-item     hit inside the clicked block's unit cube, reach <=
 *                blockInteractionRange+0.2, target adjacent to blueprint/
 *                support cells, max one use packet per tick, vanilla
 *                ordering (use packet -> arm swing in the same tick)
 *   destroy      ABORT/STOP_DESTROY only after START on the same pos;
 *                no drop/release/swap actions at all
 *   sprint       sprinting only with forward input, never while sneaking,
 *                never stationary for long
 *
 * Everything else (keep-alives, hotbar selects, inputs) is logged in the
 * packet histogram as evidence, not flagged.
 */
public final class GentleBuildAudit {
	private static final double MAX_ROT_PER_TICK_DEG = 45.0;
	private static final double MAX_POS_DELTA = 3.0;
	private static final double MOVE_EPS = 0.01;
	private static final double HIT_EPS = 0.05;
	private static final double REACH_EPS = 0.2;
	private static final int SPRINT_NOFORWARD_GRACE = 3;
	private static final int SPRINT_STATIONARY_GRACE = 10;

	private record Sent(int tick, long seq, Packet<?> packet, Vec3 eye, double reach,
						float yaw, float pitch) {}

	private static final List<Sent> packets = new ArrayList<>();
	private static final List<String> violations = new ArrayList<>();
	private static final List<String> notes = new ArrayList<>();
	private static final Map<String, Integer> histogram = new TreeMap<>();

	private static volatile boolean recording;
	private static boolean evaluated;
	private static long seq;
	private static int tick;
	private static int recordedTicks;

	// consecutive-tick player samples
	private static boolean haveSample;
	private static float prevYaw;
	private static float prevPitch;
	private static Vec3 prevPos;
	private static int sprintNoForward;
	private static int sprintStationary;

	// evidence stats
	private static double maxDeltaYaw;
	private static double maxDeltaPitch;
	private static double maxSampleMove;
	private static double maxPacketMove;
	private static int usePackets;
	private static int useWithSwing;
	private static int useWithoutSwing;
	private static int swingCount;

	static {
		ClientTickEvents.END_CLIENT_TICK.register(GentleBuildAudit::onClientTick);
	}

	private GentleBuildAudit() {}

	public static synchronized void startRecording() {
		packets.clear();
		violations.clear();
		notes.clear();
		histogram.clear();
		seq = 0;
		tick = 0;
		recordedTicks = 0;
		haveSample = false;
		sprintNoForward = 0;
		sprintStationary = 0;
		maxDeltaYaw = maxDeltaPitch = maxSampleMove = maxPacketMove = 0.0;
		usePackets = useWithSwing = useWithoutSwing = swingCount = 0;
		evaluated = false;
		recording = true;
	}

	public static synchronized void stopRecording() {
		recording = false;
		evaluateLocked();
	}

	public static boolean isRecording() {
		return recording;
	}

	/** Debug: destroy-action timeline for a cell (tick, action). */
	public static synchronized String destroyTimeline(BlockPos pos) {
		StringBuilder b = new StringBuilder();
		for (Sent snt : packets) {
			if (snt.packet() instanceof net.minecraft.network.protocol.game
							.ServerboundPlayerActionPacket a
					&& a.getPos().equals(pos)) {
				b.append(" t").append(snt.tick()).append('=').append(a.getAction());
			}
		}
		return b.toString();
	}

	public static synchronized List<String> violations() {
		evaluateLocked();
		return List.copyOf(violations);
	}

	public static synchronized List<String> notes() {
		evaluateLocked();
		return List.copyOf(notes);
	}

	public static synchronized Map<String, Integer> histogram() {
		return Map.copyOf(histogram);
	}

	public static synchronized String summary() {
		evaluateLocked();
		double minutes = recordedTicks / 1200.0;
		double usePerMin = minutes > 0 ? usePackets / minutes : 0.0;
		return String.format(
				"ticks=%d packets=%d | maxDyaw=%.2fdeg/tick maxDpitch=%.2fdeg/tick"
						+ " | maxSampleMove=%.3f maxPacketMove=%.3f"
						+ " | usePackets=%d (swingPaired=%d bare=%d, %.1f/min)"
						+ " | swings=%d | violations=%d",
				recordedTicks, packets.size(), maxDeltaYaw, maxDeltaPitch,
				maxSampleMove, maxPacketMove,
				usePackets, useWithSwing, useWithoutSwing, usePerMin,
				swingCount, violations.size());
	}

	// ------------------------------------------------------------------
	// Capture
	// ------------------------------------------------------------------

	/** Called from the Connection.send mixin on whichever thread emits. */
	public static void onSend(Connection connection, Packet<?> packet) {
		if (!recording || connection.getSending() != PacketFlow.SERVERBOUND) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = mc.player != null ? mc.player.getEyePosition() : null;
		double reach = mc.player != null ? mc.player.blockInteractionRange() : 0.0;
		float yaw = mc.player != null ? mc.player.getYRot() : Float.NaN;
		float pitch = mc.player != null ? mc.player.getXRot() : Float.NaN;
		synchronized (GentleBuildAudit.class) {
			if (!recording) {
				return;
			}
			histogram.merge(packet.getClass().getSimpleName(), 1, Integer::sum);
			packets.add(new Sent(tick, seq++, packet, eye, reach, yaw, pitch));
		}
	}

	/** One player-state sample per client tick while recording. */
	public static void onClientTick(Minecraft mc) {
		if (!recording) {
			return;
		}
		synchronized (GentleBuildAudit.class) {
			if (!recording) {
				return;
			}
			tick++;
			recordedTicks++;
			if (mc.player == null) {
				return;
			}
			float yaw = mc.player.getYRot();
			float pitch = mc.player.getXRot();
			Vec3 pos = mc.player.position();
			if (!Float.isFinite(yaw) || !Float.isFinite(pitch)
					|| !Double.isFinite(pos.x) || !Double.isFinite(pos.y)
					|| !Double.isFinite(pos.z)) {
				violations.add("t" + tick + ": NaN/non-finite player state yaw="
						+ yaw + " pitch=" + pitch + " pos=" + pos);
			}
			if (Math.abs(pitch) > 90.01f) {
				violations.add("t" + tick + ": pitch outside +-90: " + pitch);
			}
			if (haveSample) {
				double dyaw = Math.abs(AngleMath.wrap(yaw - prevYaw));
				double dpitch = Math.abs(pitch - prevPitch);
				maxDeltaYaw = Math.max(maxDeltaYaw, dyaw);
				maxDeltaPitch = Math.max(maxDeltaPitch, dpitch);
				if (dyaw > MAX_ROT_PER_TICK_DEG || dpitch > MAX_ROT_PER_TICK_DEG) {
					violations.add("t" + tick + ": rotation jump dyaw="
							+ fmt(dyaw) + " dpitch=" + fmt(dpitch));
				}
				maxSampleMove = Math.max(maxSampleMove, pos.distanceTo(prevPos));
			}
			if (mc.player.isSprinting()) {
				Input in = InputBroker.isControlling()
						? InputBroker.desired() : InputBroker.lastVanilla();
				if (in.shift()) {
					violations.add("t" + tick + ": sprinting while sneaking");
				}
				if (!in.forward()) {
					if (++sprintNoForward >= SPRINT_NOFORWARD_GRACE) {
						violations.add("t" + tick
								+ ": sprinting without forward input");
					}
				} else {
					sprintNoForward = 0;
				}
				double moveSq = haveSample ? pos.distanceToSqr(prevPos) : 1.0;
				if (moveSq < 0.0001) {
					if (++sprintStationary >= SPRINT_STATIONARY_GRACE) {
						violations.add("t" + tick
								+ ": sprint flag held while stationary");
						sprintStationary = -SPRINT_STATIONARY_GRACE; // don't spam
					}
				} else {
					sprintStationary = 0;
				}
			} else {
				sprintNoForward = 0;
				sprintStationary = 0;
			}
			prevYaw = yaw;
			prevPitch = pitch;
			prevPos = pos;
			haveSample = true;
		}
	}

	// ------------------------------------------------------------------
	// Evaluation (once, at stop/first query)
	// ------------------------------------------------------------------

	private static void evaluateLocked() {
		if (evaluated) {
			return;
		}
		evaluated = true;
		Set<BlockPos> allowed = allowedCells();

		Map<Integer, List<Sent>> byTick = new TreeMap<>();
		for (Sent s : packets) {
			byTick.computeIfAbsent(s.tick, k -> new ArrayList<>()).add(s);
		}

		Vec3 lastMovePos = null;
		float lastMoveYaw = Float.NaN;
		float lastMovePitch = Float.NaN;
		int lastUseTick = Integer.MIN_VALUE;
		final Set<BlockPos> mining = new HashSet<>();

		for (Map.Entry<Integer, List<Sent>> e : byTick.entrySet()) {
			int t = e.getKey();
			List<Sent> list = e.getValue();

			// --- use-type packets: count + vanilla ordering -------------
			List<Sent> uses = new ArrayList<>();
			long firstSwing = Long.MAX_VALUE;
			for (Sent s : list) {
				if (s.packet() instanceof ServerboundUseItemOnPacket
						|| s.packet() instanceof ServerboundUseItemPacket) {
					uses.add(s);
				}
				if (s.packet() instanceof ServerboundSwingPacket) {
					swingCount++;
					firstSwing = Math.min(firstSwing, s.seq());
				}
			}
			if (uses.size() > 1) {
				violations.add("t" + t + ": " + uses.size()
						+ " use-type packets in one tick");
			}
			for (Sent u : uses) {
				usePackets++;
				if (firstSwing != Long.MAX_VALUE) {
					if (firstSwing < u.seq()) {
						// Humans can air-swing then click in one tick; note it
						// but vanilla's use path is always use -> swing.
						notes.add("t" + t + ": swing precedes use packet (legal "
								+ "for humans, unusual for this client)");
					} else {
						useWithSwing++;
					}
				} else {
					useWithoutSwing++;
					notes.add("t" + t + ": use packet without arm swing "
							+ "(matches a client-side-rejected vanilla use)");
				}
			}
			// >=1 tick between consecutive use packets
			if (!uses.isEmpty()) {
				if (t == lastUseTick) {
					violations.add("t" + t + ": duplicate use tick");
				}
				lastUseTick = t;
			}

			for (Sent s : list) {
				Packet<?> p = s.packet();

				// --- movement -------------------------------------------
				if (p instanceof ServerboundMovePlayerPacket m) {
					if (m.hasPosition()) {
						double x = m.getX(0.0), y = m.getY(0.0), z = m.getZ(0.0);
						if (!Double.isFinite(x) || !Double.isFinite(y)
								|| !Double.isFinite(z)) {
							violations.add("t" + t + ": NaN in move packet pos");
						} else {
							Vec3 cur = new Vec3(x, y, z);
							if (lastMovePos != null) {
								double d = cur.distanceTo(lastMovePos);
								maxPacketMove = Math.max(maxPacketMove, d);
								if (d > MAX_POS_DELTA + MOVE_EPS) {
									violations.add("t" + t + ": move packet jump "
											+ fmt(d) + " blocks");
								}
							}
							lastMovePos = cur;
						}
					}
					if (m.hasRotation()) {
						float yRot = m.getYRot(0.0f);
						float xRot = m.getXRot(0.0f);
						lastMoveYaw = yRot;
						lastMovePitch = xRot;
						if (!Float.isFinite(yRot) || !Float.isFinite(xRot)) {
							violations.add("t" + t + ": NaN in move packet rot");
						}
						if (Math.abs(xRot) > 90.01f) {
							violations.add("t" + t + ": move packet pitch "
									+ xRot + " outside +-90");
						}
					}
				}

				// --- block use -------------------------------------------
				if (p instanceof ServerboundUseItemOnPacket u) {
					// UseItemOn has no rotation: the server still uses the last move.
					if (Float.isFinite(lastMoveYaw) && Float.isFinite(lastMovePitch)
							&& Float.isFinite(s.yaw()) && Float.isFinite(s.pitch())
							&& (Math.abs(AngleMath.shortestDelta(lastMoveYaw, s.yaw())) > 1e-4
							|| Math.abs(lastMovePitch - s.pitch()) > 1e-4)) {
						violations.add("t" + t + ": block use precedes view rotation"
								+ " wire=(" + lastMoveYaw + "," + lastMovePitch + ")"
								+ " client=(" + s.yaw() + "," + s.pitch() + ")");
					}
					BlockHitResult hit = u.getHitResult();
					BlockPos bp = hit.getBlockPos();
					Vec3 loc = hit.getLocation();
					double lx = loc.x - bp.getX();
					double ly = loc.y - bp.getY();
					double lz = loc.z - bp.getZ();
					if (lx < -HIT_EPS || lx > 1.0 + HIT_EPS
							|| ly < -HIT_EPS || ly > 1.0 + HIT_EPS
							|| lz < -HIT_EPS || lz > 1.0 + HIT_EPS) {
						violations.add("t" + t + ": use hitVec outside block "
								+ bp.toShortString() + " local=("
								+ fmt(lx) + "," + fmt(ly) + "," + fmt(lz) + ")");
					}
					if (s.eye() != null
							&& s.eye().distanceTo(loc) > s.reach() + REACH_EPS) {
						violations.add("t" + t + ": use hit beyond reach "
								+ fmt(s.eye().distanceTo(loc)) + " > "
								+ fmt(s.reach() + REACH_EPS) + " at "
								+ bp.toShortString());
					}
					if (!allowed.isEmpty() && !allowed.contains(bp)) {
						violations.add("t" + t + ": use target "
								+ bp.toShortString()
								+ " not adjacent to blueprint/support");
					}
				}

				// --- destroy sequence ------------------------------------
				if (p instanceof ServerboundPlayerActionPacket a) {
					BlockPos ap = a.getPos();
					switch (a.getAction()) {
						case START_DESTROY_BLOCK -> {
							if (s.eye() != null && !withinCube(s.eye(), ap,
									s.reach() + REACH_EPS)) {
								violations.add("t" + t + ": START_DESTROY out of "
										+ "reach at " + ap.toShortString());
							}
							mining.add(ap);
						}
						case ABORT_DESTROY_BLOCK -> {
							if (!mining.remove(ap)) {
								violations.add("t" + t + ": ABORT_DESTROY at "
										+ ap.toShortString() + " without START");
							}
						}
						case STOP_DESTROY_BLOCK -> {
							if (!mining.remove(ap)) {
								violations.add("t" + t + ": STOP_DESTROY at "
										+ ap.toShortString() + " without START");
							}
						}
						case DROP_ALL_ITEMS, DROP_ITEM, RELEASE_USE_ITEM,
								SWAP_ITEM_WITH_OFFHAND, STAB ->
							violations.add("t" + t + ": unexpected action "
									+ a.getAction() + " at " + ap.toShortString());
						default -> { }
					}
				}

				// --- sprint/input honesty --------------------------------
				if (p instanceof ServerboundPlayerInputPacket i) {
					Input in = i.input();
					if (in.sprint() && !in.forward()) {
						violations.add("t" + t
								+ ": sprint input without forward input");
					}
					if (in.sprint() && in.shift()) {
						violations.add("t" + t
								+ ": sprint input while sneaking");
					}
				}
			}
		}
		notes.add("recordedTicks=" + recordedTicks + " packets=" + packets.size()
				+ " usePackets=" + usePackets + " paired=" + useWithSwing
				+ " bare=" + useWithoutSwing + " swings=" + swingCount
				+ " maxDyaw=" + fmt(maxDeltaYaw) + " maxDpitch="
				+ fmt(maxDeltaPitch) + " maxPacketMove=" + fmt(maxPacketMove));
	}

	/**
	 * Legal click targets: blueprint cells, their face neighbours (the block
	 * whose face you click to place into an adjacent cell), owned supports
	 * and their neighbours. Empty when the blueprint is unavailable -> the
	 * adjacency check is skipped rather than guessed.
	 */
	private static Set<BlockPos> allowedCells() {
		Set<BlockPos> allowed = new HashSet<>();
		Blueprint blueprint = BuildController.get().blueprint();
		if (blueprint == null) {
			notes.add("blueprint unavailable at evaluation; "
					+ "use-target adjacency check skipped");
			return allowed;
		}
		Set<BlockPos> seeds = new HashSet<>(blueprint.blocks().keySet());
		seeds.addAll(BuildController.get().supportPositions());
		for (BlockPos pos : seeds) {
			allowed.add(pos);
			for (var dir : net.minecraft.core.Direction.values()) {
				allowed.add(pos.relative(dir));
			}
		}
		return allowed;
	}

	private static boolean withinCube(Vec3 eye, BlockPos pos, double reach) {
		double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
		double dx = Math.max(Math.abs(eye.x - cx) - 0.5, 0.0);
		double dy = Math.max(Math.abs(eye.y - cy) - 0.5, 0.0);
		double dz = Math.max(Math.abs(eye.z - cz) - 0.5, 0.0);
		return dx * dx + dy * dy + dz * dz <= reach * reach;
	}

	private static String fmt(double v) {
		return String.format("%.3f", v);
	}
}
