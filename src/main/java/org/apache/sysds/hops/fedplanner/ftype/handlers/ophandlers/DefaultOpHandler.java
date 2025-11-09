package org.apache.sysds.hops.fedplanner.ftype.handlers.ophandlers;

import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.ftype.handlers.FederatedTypeHandler;

/**
 * Default handler for unknown operation types
 */
public class DefaultOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return true; // Always handles as fallback
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        return HandlerResult.unsupported(
            "Unknown operation type or unhandled case: " + hop.getClass().getSimpleName());
    }
}
