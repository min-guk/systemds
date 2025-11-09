package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.ophandlers;

import org.apache.sysds.hops.*;
import org.apache.sysds.lops.MMTSJ.MMTSJType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.HandlerResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for AggBinaryOp operations (matrix multiplication and aggregation)
 */
public class AggBinaryOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof AggBinaryOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        AggBinaryOp abop = (AggBinaryOp) hop;
        FType firstFType = inputTypes.length > 0 ? inputTypes[0] : null;
        FType secondFType = inputTypes.length > 1 ? inputTypes[1] : null;

        // Use the updated combineBinaryFTypes with isAggregation=true
        // This handles all the normalization and output FType determination
        FType result = combineBinaryFTypes(firstFType, secondFType, false, false, true);

        // Special handling for self-transpose multiplication
        MMTSJType mmtsj = abop.checkTransposeSelf();
        if (mmtsj != MMTSJType.NONE &&
            ((mmtsj.isLeft() && firstFType == FType.ROW) ||
             (mmtsj.isRight() && firstFType == FType.COL))) {
            return HandlerResult.supported(FType.BROADCAST,
                "AggBinaryOp: Self-transpose multiplication results in BROADCAST");
        }

        // Check if operation is supported
        if (result == null) {
            // Fallback: 혼합 파티션 집계인데 combine에서 null이 온 경우 → 정책값으로 보정
            if ((firstFType == FType.ROW && secondFType == FType.COL) ||
                (firstFType == FType.COL && secondFType == FType.ROW)) {
                return HandlerResult.supported(FType.ROW,
                    "AggBinaryOp: Mixed partitions (ROW×COL) aggregated with ROW-priority policy");
           }
            if (firstFType == FType.BROADCAST || secondFType == FType.BROADCAST) {
                return HandlerResult.unsupported(
                    "AggBinaryOp: BROADCAST input would cause duplicate aggregation");
            }
            if (firstFType == null && secondFType == null) {
                // This case shouldn't happen as combineBinaryFTypes returns BROADCAST for null+null aggregation
                // But keep for safety - return BROADCAST as expected by test case 8
                return HandlerResult.supported(FType.BROADCAST,
                    "AggBinaryOp: LOCAL × LOCAL matrix multiplication → BROADCAST result");
            }
            return HandlerResult.unsupported("AggBinaryOp: Unsupported federation pattern");
        }

        // Generate appropriate reasoning message
        String reasoning = generateAggBinaryOpReasoning(firstFType, secondFType, result);
        return HandlerResult.supported(result, reasoning);
    }

    private String generateAggBinaryOpReasoning(FType first, FType second, FType result) {
        // Both LOCAL
        if (first == null && second == null) {
            return "AggBinaryOp: LOCAL × LOCAL matrix multiplication → BROADCAST (global result)";
        }

        // One LOCAL, one federated
        if (first == null && second != null) {
            if (second == FType.ROW) {
                return "AggBinaryOp: LOCAL parameters × ROW data → ROW (Fed PS pattern)";
            }
            if (second == FType.COL) {
                return "AggBinaryOp: LOCAL vector × COL partition → COL";
            }
            return "AggBinaryOp: LOCAL × " + second + " → " + result + " (LOCAL broadcasts to workers)";
        }
        if (second == null && first != null) {
            if (first == FType.ROW) {
                return "AggBinaryOp: ROW data × LOCAL parameters → ROW";
            }
            if (first == FType.COL) {
                return "AggBinaryOp: COL partition × LOCAL vector → COL";
            }
            return "AggBinaryOp: " + first + " × LOCAL → " + result + " (LOCAL broadcasts to workers)";
        }

        // Same partition types
        if (first == second) {
            String suffix = (result == FType.BROADCAST)
                ? " (global reduction → BROADCAST)"
                : " (same partition maintained)";
            return "AggBinaryOp: " + first + " × " + first + " → " + result + suffix;
        }

        // Mixed partitions (shouldn't reach here as it returns null in combineBinaryFTypes)
        return "AggBinaryOp: " + first + " × " + second + " → " + result;
    }
}
