package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

// TEMP STUB for compile-unblock. Replace with real implementation when available.
public class FederatedTypeHandler {
	public boolean isUnsupportedOperation(Hop hop) {
		return false;
	}

	public HandlerResult determineType(Hop hop, FType[] inputTypes) {
		return HandlerResult.unsupported("ftype handler stub");
	}
}
