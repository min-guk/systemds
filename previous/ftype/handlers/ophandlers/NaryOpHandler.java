package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.ophandlers;

import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.HandlerResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for NaryOp operations (N-ary operations like CBIND, RBIND, PLUS, MULT)
 */
public class NaryOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof NaryOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        // Check for scalar output first
        if (isScalarOutput(hop)) {
            return HandlerResult.unsupported("NaryOp: Scalar values don't have FType");
        }

        NaryOp nop = (NaryOp) hop;
        OpOpN op = nop.getOp();

        // Unsupported operations
        if (op == OpOpN.PRINTF || op == OpOpN.EVAL || op == OpOpN.LIST ||
            ((op == OpOpN.CBIND || op == OpOpN.RBIND) &&
             (hop.getInput().get(0).getDataType().isList() || inputTypes.length > 2)) ||
            (op.isCellOp() &&
             hop.getInput().stream().allMatch(h -> h.getDataType().isScalar()))) {

            String reason = op == OpOpN.PRINTF || op == OpOpN.EVAL ?
                "NaryOp: " + op + " executes on coordinator only" :
                op == OpOpN.LIST ? "NaryOp: LIST operations not federated" :
                (op == OpOpN.CBIND || op == OpOpN.RBIND) && inputTypes.length > 2 ?
                "NaryOp: " + op + " with " + inputTypes.length + " inputs not supported (BuiltinNary has no FED implementation)" :
                "NaryOp: Operation not supported for federated execution";
            return HandlerResult.unsupported(reason);
        }

        // Supported matrix operations
        if (op == OpOpN.CBIND || op == OpOpN.RBIND ||
            op == OpOpN.PLUS || op == OpOpN.MULT ||
            op == OpOpN.MIN || op == OpOpN.MAX) {

            // For element-wise operations (PLUS, MULT, MIN, MAX)
            if (op == OpOpN.PLUS || op == OpOpN.MULT || op == OpOpN.MIN || op == OpOpN.MAX) {
                return handleElementwiseNaryOp(inputTypes, op);
            }

            // For binding operations (CBIND, RBIND) - check all N inputs for consistency
            return handleBindingNaryOp(inputTypes, op);
        }

        return HandlerResult.unsupported("NaryOp: " + op + " not supported");
    }

    private HandlerResult handleBindingNaryOp(FType[] inputTypes, OpOpN op) {
        // CBIND/RBIND: Runtime uses AppendFEDInstruction
        // Requires all inputs have same partition structure
        FType dominantType = null;
        boolean hasLocal = false;
        boolean hasBroadcast = false;

        for (FType ft : inputTypes) {
            if (ft == null) {
                hasLocal = true;
            } else if (ft == FType.BROADCAST) {
                hasBroadcast = true;
            } else {
                if (dominantType == null) {
                    dominantType = ft;
                } else if (dominantType != ft) {
                    // Cannot bind ROW with COL (incompatible structures)
                    return HandlerResult.unsupported("NaryOp: " + op +
                        " cannot bind mixed partitions (" + dominantType + " + " + ft + ")");
                }
            }
        }

        // LOCAL + federated: alignment undefined
        if (hasLocal && dominantType != null) {
            return HandlerResult.unsupported("NaryOp: " + op +
                " cannot bind LOCAL with federated");
        }

        // BROADCAST: ambiguous which replica to use
        if (hasBroadcast) {
            return HandlerResult.unsupported("NaryOp: " + op +
                " cannot bind BROADCAST");
        }

        if (dominantType == null) {
            return HandlerResult.unsupported("NaryOp: " + op + " no federated inputs");
        }

        // Same partition type maintains structure
        return HandlerResult.supported(dominantType,
            "NaryOp: " + op + " maintains " + dominantType);
    }

    private HandlerResult handleElementwiseNaryOp(FType[] inputTypes, OpOpN op) {
        /*
         * Element-wise NaryOp: Converted to binary chain at runtime
         * n+(A,B,C) → ((A+B)+C) using BinaryFEDInstruction
         *
         * First federated input determines dominant type
         */

        // Find first non-BROADCAST federated type (dominant)
        FType dominantType = null;
        boolean hasBroadcast = false;
        boolean hasLocal = false;

        for (FType ft : inputTypes) {
            if (ft == null) {
                hasLocal = true;
            } else if (ft == FType.BROADCAST) {
                hasBroadcast = true;
            } else if (dominantType == null) {
                dominantType = ft;  // First partitioned type wins
            }
        }

        // Partitioned type found → use as dominant
        if (dominantType != null) {
            // LOCAL broadcasts, BROADCAST adapts, mixed partitions use first
            return HandlerResult.supported(dominantType,
                "NaryOp: " + op + " binary chain, dominant: " + dominantType);
        }

        // All BROADCAST
        if (hasBroadcast && !hasLocal) {
            return HandlerResult.supported(FType.BROADCAST,
                "NaryOp: " + op + " all BROADCAST");
        }

        // Mix of BROADCAST and LOCAL → BROADCAST
        if (hasBroadcast) {
            return HandlerResult.supported(FType.BROADCAST,
                "NaryOp: " + op + " BROADCAST with LOCAL");
        }

        // All LOCAL
        return HandlerResult.unsupported("NaryOp: " + op + " no federated inputs");
    }

    /**
     * Helper method to check if all inputs are null
     */
    private boolean hasAllNullInputs(FType[] inputTypes) {
        for (FType ft : inputTypes) {
            if (ft != null) {
                return false;
            }
        }
        return true;
    }
}
