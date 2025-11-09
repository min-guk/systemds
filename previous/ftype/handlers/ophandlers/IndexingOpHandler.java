package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.ophandlers;

import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.HandlerResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for IndexingOp operations (right indexing X[i:j, k:l])
 */
public class IndexingOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof IndexingOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        if (!hasFederatedFirstInput(inputTypes)) {
            return HandlerResult.unsupported("IndexingOp: Requires federated first input");
        }

        FType inputType = inputTypes[0];

        // Essential Broadcast Pattern: ROW/COL + LOCAL index → ROW/COL
        // Check if we have LOCAL index inputs (indices 1-4 for row_start, row_end, col_start, col_end)
        boolean hasLocalIndices = false;
        for (int i = 1; i < inputTypes.length; i++) {
            if (inputTypes[i] == null) { // null means LOCAL input
                hasLocalIndices = true;
                break;
            }
        }

        if (hasLocalIndices && (inputType == FType.ROW || inputType == FType.COL)) {
            return HandlerResult.supported(inputType,
                "IndexingOp: Essential broadcast - " + inputType + " + LOCAL indices → " + inputType +
                " (LOCAL indices broadcast to workers)");
        }

        // BROADCAST indexing generally maintains BROADCAST
        if (inputType == FType.BROADCAST) {
            return HandlerResult.supported(FType.BROADCAST,
                "IndexingOp: BROADCAST indexing maintains BROADCAST");
        }

        // For partitioned data, indexing generally maintains partition structure
        return HandlerResult.supported(inputType,
            "IndexingOp: Maintains input partition structure");
    }
}
