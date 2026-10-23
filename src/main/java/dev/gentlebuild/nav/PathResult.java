package dev.gentlebuild.nav;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

public record PathResult(List<BlockPos> waypoints,
						 Map<BlockPos, List<BlockPos>> scaffoldPlan,
						 Status status) {
	public enum Status {
		FOUND,
		UNREACHABLE,
		NODE_CAP,
		OUT_OF_BOUNDS
	}

	public boolean found() {
		return status == Status.FOUND;
	}
}
