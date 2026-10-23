package dev.gentlebuild.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import dev.gentlebuild.model.PlacementAction;
import dev.gentlebuild.state.StateRules;

/**
 * One performed interaction awaiting server confirmation. Confirms only when
 * an authoritative update for the target arrived AND matches the expected
 * state AND stays consistent for SETTLE_TICKS consecutive ticks. A locally
 * predicted state alone is never accepted: an authoritative AIR/rollback
 * becomes MISMATCH once settled, or TIMEOUT if nothing authoritative arrives.
 */
public class Transaction {
	static final int SETTLE_TICKS = 3;

	public enum Status { PENDING, CONFIRMED, MISMATCHED, TIMEOUT }

	private final PlacementAction action;
	private final int timeoutTicks;
	/** Minimum ticks between the action and any confirmation (fluids settle). */
	private final int minSettleTicks;
	private int ticksWaiting;
	private int settleTicks;
	private boolean acked;
	private BlockState lastAcked;
	private Status status = Status.PENDING;

	public Transaction(PlacementAction action, int timeoutTicks) {
		this(action, timeoutTicks, SETTLE_TICKS);
	}

	public Transaction(PlacementAction action, int timeoutTicks, int minSettleTicks) {
		this.action = action;
		this.timeoutTicks = Math.max(timeoutTicks, minSettleTicks + SETTLE_TICKS);
		this.minSettleTicks = minSettleTicks;
	}

	public PlacementAction action() {
		return action;
	}

	public Status status() {
		return status;
	}

	public BlockPos pos() {
		return action.target();
	}

	/** Last authoritative state acknowledged for the target (or null). */
	public BlockState acknowledgedState() {
		return lastAcked;
	}

	/** Authoritative update from the packet mixin. */
	public void acknowledge(BlockPos pos, BlockState state) {
		if (pos.equals(action.target())) {
			lastAcked = state;
			acked = true;
		}
	}

	/**
	 * @return true once resolved (confirmed, mismatched or timed out)
	 */
	public boolean tick(java.util.function.Function<BlockPos, BlockState> worldState) {
		if (status != Status.PENDING) {
			return true;
		}
		if (++ticksWaiting > timeoutTicks) {
			status = Status.TIMEOUT;
			return true;
		}
		if (!acked) {
			return false;
		}
		if (ticksWaiting < Math.max(SETTLE_TICKS, minSettleTicks)) {
			return false;
		}
		BlockState current = worldState.apply(action.target());
		boolean ackMatches = lastAcked != null
				&& StateRules.placementEquivalent(action.expected(), lastAcked);
		boolean worldMatches = StateRules.placementEquivalent(action.expected(), current);
		if (ackMatches && worldMatches) {
			if (++settleTicks >= SETTLE_TICKS) {
				status = Status.CONFIRMED;
				return true;
			}
		} else {
			settleTicks = 0;
			// An authoritative value exists and disagrees: settle on it being
			// definitively wrong instead of waiting the full timeout.
			// Breaking is different: the pre-break block acks normally while
			// damage progresses — only the authoritative AIR counts.
			if (action.kind() != dev.gentlebuild.model.ActionKind.BREAK_TEMPORARY
					&& action.kind() != dev.gentlebuild.model.ActionKind.BREAK_REPAIR
					&& lastAcked != null
					&& !StateRules.placementEquivalent(action.expected(), lastAcked)
					&& current.equals(lastAcked) && ticksWaiting >= SETTLE_TICKS + 3) {
				status = Status.MISMATCHED;
				return true;
			}
		}
		return false;
	}

	void forceTimeout() {
		if (status == Status.PENDING) {
			status = Status.TIMEOUT;
		}
	}
}
