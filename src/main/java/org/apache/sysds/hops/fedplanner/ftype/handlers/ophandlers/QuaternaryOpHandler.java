package org.apache.sysds.hops.fedplanner.ftype.handlers.ophandlers;

import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for QuaternaryOp operations (four-input weighted operations)
 */
public class QuaternaryOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof QuaternaryOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        if (!hasFederatedFirstInput(inputTypes)) {
            return HandlerResult.unsupported("QuaternaryOp: Requires federated first input");
        }

        QuaternaryOp qop = (QuaternaryOp) hop;
        OpOp4 op = qop.getOp();

        // Scalar output operations
        if (op == OpOp4.WSLOSS || op == OpOp4.WCEMM) {
            return HandlerResult.unsupported(
                "QuaternaryOp: " + op + " returns scalar loss value");
        }

        // Operations maintaining first input's structure
        if (op == OpOp4.WSIGMOID || op == OpOp4.WUMM) {
            return HandlerResult.supported(inputTypes[0],
                "QuaternaryOp: " + op + " maintains first input's structure");
        }

        // Most quaternary operations maintain the structure of the first input
        // But BROADCAST inputs need special handling
        FType firstType = inputTypes[0];

        // If first input is BROADCAST, look for a dominant partitioned type
        if (firstType == FType.BROADCAST) {
            for (FType ft : inputTypes) {
                if (ft != null && ft != FType.BROADCAST) {
                    return HandlerResult.supported(ft,
                        "QuaternaryOp: " + op + " uses dominant partition type " + ft);
                }
            }
            // All inputs are BROADCAST
            return HandlerResult.supported(FType.BROADCAST,
                "QuaternaryOp: " + op + " all BROADCAST inputs");
        }

        // Normal case: maintain first input's structure
        return HandlerResult.supported(firstType,
            "QuaternaryOp: " + op + " maintains first input structure");
    }
}
