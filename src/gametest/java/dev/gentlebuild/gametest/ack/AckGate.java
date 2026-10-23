package dev.gentlebuild.gametest.ack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import dev.gentlebuild.build.AckTracker;

/**
 * Test-only gate on the clientbound block-update feed. HOLD queues the
 * updates for gated positions (a delayed server acknowledgement), DROP
 * discards them (a missing one). Because the withhold happens BEFORE
 * vanilla applies the packet, two honest things follow: the production
 * TAIL hook in ClientPacketListenerMixin never calls
 * {@link AckTracker#onServerBlock}, and the prediction handler keeps its
 * pre-interaction state — so the (ungated) block-changed-ack reverts the
 * optimistic prediction, exactly like a lost packet on a real server.
 *
 * REWRITE instead lets every update through but substitutes the
 * authoritative state: the client world and the AckTracker see a
 * DIFFERENT block than the one placed (a rollback/protection-plugin
 * acknowledgement — "the server landed dirt where stone was expected").
 * The transaction therefore settles MISMATCHED on real evidence, never
 * CONFIRMED. The genuine server state stays queued like a HOLD, so
 * {@link #release()} replays the truth once the contradiction lifts.
 *
 * Section packets are split per position: only gated positions are
 * withheld/rewritten, everything else is applied and acked like vanilla
 * does. All hooks run on the client thread; arming is test-thread safe.
 */
public final class AckGate {
	private AckGate() {}

	public enum Mode { OFF, HOLD, DROP, REWRITE }

	private static final Object LOCK = new Object();
	private static Mode mode = Mode.OFF;
	private static Set<BlockPos> gated = Set.of();
	private static Map<BlockPos, BlockState> rewrites = Map.of();
	private static final List<HeldUpdate> held = new ArrayList<>();
	private static int heldTotal;
	private static int droppedTotal;
	private static int rewrittenTotal;

	private record HeldUpdate(BlockPos pos, BlockState state) {}

	/** Hold every block update for the given positions until {@link #release()}. */
	public static void hold(BlockPos... positions) {
		arm(Mode.HOLD, positions);
	}

	/** Discard every block update for the given positions until {@link #off()}. */
	public static void drop(BlockPos... positions) {
		arm(Mode.DROP, positions);
	}

	/**
	 * Let every block update for the given positions through, but apply and
	 * ack {@code substitute} instead of the real state — a contradictory
	 * server acknowledgement (rollback/plugins). The true states stay
	 * queued for {@link #release()}.
	 */
	public static void rewrite(BlockState substitute, BlockPos... positions) {
		Map<BlockPos, BlockState> map = new LinkedHashMap<>();
		for (BlockPos p : positions) {
			map.put(p.immutable(), substitute);
		}
		rewrite(map);
	}

	/** Per-position substitutes; see {@link #rewrite(BlockState, BlockPos...)}. */
	public static void rewrite(Map<BlockPos, BlockState> substitutes) {
		synchronized (LOCK) {
			gated = Set.copyOf(substitutes.keySet());
			rewrites = Map.copyOf(substitutes);
			held.clear();
			heldTotal = 0;
			droppedTotal = 0;
			rewrittenTotal = 0;
			mode = Mode.REWRITE;
		}
	}

	private static void arm(Mode m, BlockPos... positions) {
		synchronized (LOCK) {
			gated = Set.copyOf(List.of(positions));
			rewrites = Map.of();
			held.clear();
			heldTotal = 0;
			droppedTotal = 0;
			rewrittenTotal = 0;
			mode = m;
		}
	}

	/** Disarm; still-held updates are discarded. */
	public static void off() {
		synchronized (LOCK) {
			mode = Mode.OFF;
			gated = Set.of();
			rewrites = Map.of();
			held.clear();
		}
	}

	/**
	 * Disarm and replay every held update exactly like vanilla +
	 * ClientPacketListenerMixin would: world state first, then the ack feed.
	 * Must run on the client thread. @return replayed update count
	 */
	public static int release() {
		List<HeldUpdate> pending;
		synchronized (LOCK) {
			pending = List.copyOf(held);
			held.clear();
			mode = Mode.OFF;
			gated = Set.of();
		}
		ClientLevel level = Minecraft.getInstance().level;
		for (HeldUpdate u : pending) {
			if (level != null) {
				level.setServerVerifiedBlockState(u.pos(), u.state(), 19);
			}
			AckTracker.onServerBlock(u.pos(), u.state());
		}
		return pending.size();
	}

	/** ClientboundBlockUpdatePacket hook: true = withhold (caller cancels). */
	public static boolean onBlockUpdate(BlockPos pos, BlockState state) {
		synchronized (LOCK) {
			if (mode == Mode.OFF || !gated.contains(pos)) {
				return false;
			}
			if (mode == Mode.REWRITE) {
				rewriteLocked(pos, state);
			} else {
				suppress(pos, state);
			}
			return true;
		}
	}

	/**
	 * ClientboundSectionBlocksUpdatePacket hook: true = handled (caller
	 * cancels). Ungated positions inside the section are passed through
	 * immediately, gated ones are suppressed.
	 */
	public static boolean onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
		List<HeldUpdate> updates = new ArrayList<>();
		packet.runUpdates((pos, state) ->
				updates.add(new HeldUpdate(pos.immutable(), state)));
		synchronized (LOCK) {
			if (mode == Mode.OFF
					|| updates.stream().noneMatch(u -> gated.contains(u.pos()))) {
				return false;
			}
			ClientLevel level = Minecraft.getInstance().level;
			for (HeldUpdate u : updates) {
				if (gated.contains(u.pos())) {
					if (mode == Mode.REWRITE) {
						rewriteLocked(u.pos(), u.state());
					} else {
						suppress(u.pos(), u.state());
					}
				} else {
					if (level != null) {
						level.setServerVerifiedBlockState(u.pos(), u.state(), 19);
					}
					AckTracker.onServerBlock(u.pos(), u.state());
				}
			}
			return true;
		}
	}

	/**
	 * Caller must hold LOCK. Queue the genuine update for release(), then
	 * deliver the substitute exactly like vanilla + the production TAIL
	 * hook would: world state first (server-verified, so the prediction
	 * layer keeps it), then the ack feed. The pending transaction sees an
	 * authoritative state that disagrees with what it placed.
	 */
	private static void rewriteLocked(BlockPos pos, BlockState real) {
		held.add(new HeldUpdate(pos, real));
		heldTotal++;
		rewrittenTotal++;
		BlockState substitute = rewrites.getOrDefault(pos, real);
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			level.setServerVerifiedBlockState(pos, substitute, 19);
		}
		AckTracker.onServerBlock(pos, substitute);
	}

	/** Caller must hold LOCK. */
	private static void suppress(BlockPos pos, BlockState state) {
		if (mode == Mode.HOLD) {
			held.add(new HeldUpdate(pos, state));
			heldTotal++;
		} else {
			droppedTotal++;
		}
	}

	/** Authoritative updates substituted so far (REWRITE only). */
	public static int rewritten() {
		synchronized (LOCK) {
			return rewrittenTotal;
		}
	}

	/** Updates withheld so far (held + dropped + rewritten). */
	public static int suppressed() {
		synchronized (LOCK) {
			return heldTotal + droppedTotal;
		}
	}

	public static boolean armed() {
		synchronized (LOCK) {
			return mode != Mode.OFF;
		}
	}
}
