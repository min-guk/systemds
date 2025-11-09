package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers;

import java.util.Objects;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

// TEMP STUB for compile-unblock. Replace with real implementation when available.
public final class HandlerResult {
	private final FType fType;
	private final String reason;

	public HandlerResult(FType fType, String reason) {
		this.fType = fType;
		this.reason = (reason == null) ? "" : reason;
	}

	public FType getFType() {
		return fType;
	}

	public String getReason() {
		return reason;
	}

	public static HandlerResult unsupported(String reason) {
		return new HandlerResult(null, Objects.requireNonNullElse(reason, "unsupported operation"));
	}
}
