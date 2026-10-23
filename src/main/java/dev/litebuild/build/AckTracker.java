package dev.litebuild.build;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Receives authoritative block updates from ClientPacketListener (after
 * vanilla applied them) and matches them against pending transactions.
 * Resolved transactions are removed, so no stale acks linger.
 */
public final class AckTracker {
	private static final Deque<Transaction> pending = new ArrayDeque<>();

	private AckTracker() {}

	public static void onServerBlock(BlockPos pos, BlockState state) {
		for (Transaction tx : pending) {
			tx.acknowledge(pos, state);
		}
	}

	public static void submit(Transaction tx) {
		pending.addLast(tx);
	}

	public static void remove(Transaction tx) {
		pending.remove(tx);
	}

	public static void cancelAll() {
		Transaction tx;
		while ((tx = pending.pollFirst()) != null) {
			tx.forceTimeout();
		}
	}

	public static int pendingCount() {
		return pending.size();
	}
}
