package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers;

import org.apache.sysds.hops.Hop;

// TEMP STUB for compile-unblock. Replace with real implementation when available.
public final class FederatedTypeHandlerFactory {
	private static final FederatedTypeHandler DEFAULT_HANDLER = new FederatedTypeHandler();

	public FederatedTypeHandlerFactory() {
		// stub keeps constructor public for the existing call sites
	}

	public FederatedTypeHandler getHandler(Hop hop) {
		return DEFAULT_HANDLER;
	}
}
