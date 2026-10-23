package dev.gentlebuild.gametest.ack;

import java.util.ArrayList;
import java.util.List;
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
 * Section packets are split per position: only gated positions are
 * withheld, everything else is applied and acked like vanilla does.
 * All hooks run on the client thread; arming is test-thread safe.
 */
public final class AckGate {
	private AckGate() {}

	public enum Mode { OFF, HOLD, DROP }

	private static final Object LOCK = new Object();
	private static Mode mode = Mode.OFF;
	private static Set<BlockPos> gated = Set.of();
	private static final List<HeldUpdate> held = new ArrayList<>();
	private static int heldTotal;
	private static int droppedTotal;

	private record HeldUpdate(BlockPos pos, BlockState state) {}

	/** Hold every block update for the given positions until {@link #release()}. */
	public static void hold(BlockPos... positions) {
		arm(Mode.HOLD, positions);
	}

	/** Discard every block update for the given positions until {@link #off()}. */
	public static void drop(BlockPos... positions) {
		arm(Mode.DROP, positions);
	}

	private static void arm(Mode m, BlockPos... positions) {
		synchronized (LOCK) {
			gated = Set.copyOf(List.of(positions));
			held.clear();
			heldTotal = 0;
			droppedTotal = 0;
			mode = m;
		}
	}

	/** Disarm; still-held updates are discarded. */
	public static void off() {
		synchronized (LOCK) {
			mode = Mode.OFF;
			gated = Set.of();
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
			suppress(pos, state);
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
					suppress(u.pos(), u.state());
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

	/** Caller must hold LOCK. */
	private static void suppress(BlockPos pos, BlockState state) {
		if (mode == Mode.HOLD) {
			held.add(new HeldUpdate(pos, state));
			heldTotal++;
		} else {
			droppedTotal++;
		}
	}

	/** Updates withheld so far (held + dropped). */
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
