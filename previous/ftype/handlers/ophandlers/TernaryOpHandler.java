package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.ophandlers;

import java.util.Arrays;
import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.HandlerResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for TernaryOp operations (three-input operations)
 */
public class TernaryOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof TernaryOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        // Check for scalar output first
        if (isScalarOutput(hop)) {
            return HandlerResult.unsupported("TernaryOp: Scalar values don't have FType");
        }

        TernaryOp top = (TernaryOp) hop;
        OpOp3 op = top.getOp();

        // Operations that produce scalar output or are unsupported
        if (op == OpOp3.MOMENT || op == OpOp3.COV || op == OpOp3.MAP) {
            String reason = (op == OpOp3.MOMENT || op == OpOp3.COV) ?
                "TernaryOp: " + op + " produces scalar output" :
                "TernaryOp: " + op + " has no federated implementation";
            return HandlerResult.unsupported(reason);
        }

        // Handle IFELSE (conditional selection) - runtime supports mixed patterns including BROADCAST
        if (op == OpOp3.IFELSE) {
            return handleIfelseOp(inputTypes);
        }

        // Check for federated inputs - but some operations can produce BROADCAST from null inputs
        boolean hasAnyFederated = hasAnyFederatedInput(inputTypes);

        // Handle special case: all null inputs can produce BROADCAST for some operations
        if (!hasAnyFederated && allInputsNull(inputTypes)) {
            if (op == OpOp3.PLUS_MULT || op == OpOp3.CTABLE) {
                return HandlerResult.supported(FType.BROADCAST,
                    "TernaryOp: " + op + " with all null inputs → BROADCAST");
            }
        }

        if (!hasAnyFederated) {
            String reason = !hasAnyFederated ?
                "TernaryOp: No federated input" :
                "TernaryOp: CTABLE requires ROW partition";
            return HandlerResult.unsupported(reason);
        }

        // TernaryOp handling depends on operation type
        if (op == OpOp3.CTABLE) {
            // CTABLE maintains first input's partitioning but needs careful handling
            return handleCTableOp(inputTypes);
        } else {
            // Other ternary ops (generally element-wise or selection-based)
            return handleGeneralTernaryOp(inputTypes, op);
        }
    }

    private HandlerResult handleCTableOp(FType[] inputTypes) {
        // 지배 파티션(첫 비-BROADCAST FED 입력) 선택
        for (FType ft : inputTypes) {
            if (ft == FType.ROW || ft == FType.COL) {
                return HandlerResult.supported(ft, "CTABLE: dominant federated partition " + ft);
            }
        }
        // all LOCAL -> BROADCAST 브릿지
        if (allInputsNull(inputTypes)) {
            return HandlerResult.supported(FType.BROADCAST, "CTABLE: all LOCAL → BROADCAST bridge");
        }
        // 그 외(BROADCAST만 있는 경우 등) -> BROADCAST 유지
        boolean hasBroadcast = Arrays.stream(inputTypes).anyMatch(ft -> ft == FType.BROADCAST);
        if (hasBroadcast) {
            return HandlerResult.supported(FType.BROADCAST, "CTABLE: BROADCAST inputs");
        }
        return HandlerResult.unsupported("CTABLE: no valid inputs");
    }


    private HandlerResult handleIfelseOp(FType[] inputTypes) {
        // IFELSE: ifelse(condition, true_val, false_val) - element-wise conditional
        // Use combineBinaryFTypes logic for consistency

        // Find first non-BROADCAST federated type as dominant
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

        // Partitioned type found → use as result
        if (dominantType != null) {
            return HandlerResult.supported(dominantType,
                "TernaryOp: IFELSE dominant type: " + dominantType);
        }

        // All BROADCAST
        if (hasBroadcast && !hasLocal) {
            return HandlerResult.supported(FType.BROADCAST,
                "TernaryOp: IFELSE all BROADCAST");
        }

        // Mix of BROADCAST and LOCAL → BROADCAST
        if (hasBroadcast) {
            return HandlerResult.supported(FType.BROADCAST,
                "TernaryOp: IFELSE BROADCAST with LOCAL");
        }

        // Should not reach here (already checked hasAnyFederatedInput)
        return HandlerResult.unsupported("TernaryOp: IFELSE no federated inputs");
    }

    private HandlerResult handleGeneralTernaryOp(FType[] inputTypes, OpOp3 op) {
        // For general ternary operations, find the dominant partitioning
        FType dominantType = null;
        boolean hasBroadcast = false;
        boolean hasMixedPartitions = false;

        // Count different partition types to detect mixed partitioning scenarios
        for (FType ft : inputTypes) {
            if (ft == null) continue;
            if (ft == FType.BROADCAST) {
                hasBroadcast = true;
            } else if (dominantType == null) {
                dominantType = ft;
            } else if (dominantType != ft) {
                // Mixed partition types detected (e.g., ROW + COL)
                hasMixedPartitions = true;
                break;
            }
        }

        // All BROADCAST inputs
        if (dominantType == null && hasBroadcast) {
            return HandlerResult.supported(FType.BROADCAST,
                "TernaryOp: " + op + " all BROADCAST inputs");
        }

        // Handle mixed partition types through broadcast-based federation
        // This was previously unsupported but runtime can handle it via broadcastSliced()
        if (hasMixedPartitions) {
            // IMPORTANT: Mixed partition types (e.g., ROW + COL inputs) are actually supported
            // by the runtime through broadcast slicing mechanism in TernaryFEDInstruction.
            //
            // How it works:
            // 1. Runtime selects the first federated input as the "dominant" partition type
            // 2. Other inputs with different partition types are broadcast-sliced to align
            //    with the dominant partition using FederationMap.broadcastSliced()
            // 3. Output inherits the dominant partition type via copyWithNewID()
            //
            // Example: t(+*) with inputs (LOCAL, ROW, COL)
            // - ROW becomes dominant (first federated input)
            // - COL input gets broadcast-sliced to ROW workers (each worker gets relevant rows)
            // - Output becomes ROW partitioned
            //
            // This enables operations like gradient computations in ML algorithms where
            // parameters (COL) need to be combined with data features (ROW).
            return HandlerResult.supported(dominantType,
                "TernaryOp: " + op + " mixed partition types supported via broadcast slicing, " +
                "output follows dominant type " + dominantType);
        }

        // Single partition type (all same)
        if (dominantType != null) {
            return HandlerResult.supported(dominantType,
                "TernaryOp: " + op + " uniform partition operation");
        }

        return HandlerResult.unsupported("TernaryOp: " + op + " no valid inputs");
    }

    /**
     * Helper method to check if all inputs are null
     */
    private boolean allInputsNull(FType[] inputTypes) {
        for (FType ft : inputTypes) {
            if (ft != null) {
                return false;
            }
        }
        return true;
    }
}
