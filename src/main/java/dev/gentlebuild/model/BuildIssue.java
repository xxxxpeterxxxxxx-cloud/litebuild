package dev.gentlebuild.model;

import net.minecraft.core.BlockPos;

public record BuildIssue(Severity severity, BlockPos pos, String message) {
	public enum Severity {
		INFO,
		MANUAL,
		CONFLICT,
		BLOCKED
	}
}
