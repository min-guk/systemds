package org.apache.sysds.hops.fedplanner.ftype.handlers.ophandlers;

import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for LeftIndexingOp operations (left-hand side indexing X[i:j, k:l] = Y)
 */
public class LeftIndexingOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof LeftIndexingOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        if (!hasFederatedFirstInput(inputTypes)) {
            return HandlerResult.unsupported("LeftIndexingOp: Requires federated first input");
        }
        // Handle BROADCAST input
        FType inputType = inputTypes[0];
        if (inputType == FType.BROADCAST) {
            return HandlerResult.supported(FType.BROADCAST,
                "LeftIndexingOp: BROADCAST input maintains BROADCAST");
        }

        return HandlerResult.supported(inputType,
            "LeftIndexingOp: Maintains input structure");
    }
}
