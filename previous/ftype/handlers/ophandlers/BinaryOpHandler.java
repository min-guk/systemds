package org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.ophandlers;

import org.apache.sysds.common.Types.*;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.HandlerResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.FederatedTypeHandler;

/**
 * Handler for BinaryOp operations (standard binary operations)
 */
public class BinaryOpHandler extends FederatedTypeHandler {
    @Override
    public boolean canHandle(Hop hop) {
        return hop instanceof BinaryOp;
    }

    @Override
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        /*
         * FIX: Binary operations PLUS and MINUS incorrectly falling to LOUT in broadcast patterns
         *
         * PROBLEM ANALYSIS:
         * - Log analysis revealed inconsistency where b(+) and b(-) operations with input pattern
         *   [null, ROW] (LOCAL scalar + FOUT ROW) incorrectly returned LOUT instead of ROW
         * - Other binary operations (b(1-*), b(>), b(max), b(^), b(*)) correctly handled the
         *   same pattern and maintained ROW federated type
         * - Root cause: aggressive isScalarOutput() check at line 374 blocked element-wise
         *   operations from reaching broadcast logic in combineBinaryFTypes()
         *
         * SOLUTION:
         * - Replace blanket scalar check with operation-aware conditional check
         * - Allow element-wise operations (PLUS, MINUS, etc.) to proceed to broadcast logic
         * - Only apply scalar check to operations that truly produce scalar output
         * - Maintain consistency with other working binary operations
         *
         * EXPECTED RESULT:
         * - b(+) with [null, ROW] → HandlerResult.supported(ROW, "Essential broadcast")
         * - b(-) with [null, ROW] → HandlerResult.supported(ROW, "Essential broadcast")
         * - Consistent behavior across all element-wise binary operations
         */
        BinaryOp bop = (BinaryOp) hop;
        OpOp2 operation = bop.getOp();

        // ---- ① Unsupported opcodes in FED execution ----
        // LOG_NZ is not supported because InstructionType.Builtin is not in FEDInstructionParser
        if (operation == OpOp2.LOG_NZ) {
            return HandlerResult.unsupported("BinaryOp: LOG_NZ opcode not supported in federated execution");
        }

        // QUANTIZE_COMPRESS is not supported in federated execution
        // - FEDInstructionParser.java has no case for QuantizeCompression InstructionType
        // - No QuantizeCompressionFEDInstruction class exists
        // - CPInstructionParser handles it via CompressionCPInstruction.parseQuantizationFusedInstruction()
        if (operation == OpOp2.QUANTIZE_COMPRESS) {
            return HandlerResult.unsupported("BinaryOp: QUANTIZE_COMPRESS not supported in federated execution (no FED instruction implementation)");
        }

        // UNION_DISTINCT is not supported in federated execution
        // - FEDInstructionParser.java has no case for InstructionType.Union (line 58-90)
        // - No UnionFEDInstruction class exists
        // - CPInstructionParser handles it via UnionCPInstruction
        if (operation == OpOp2.UNION_DISTINCT) {
            return HandlerResult.unsupported("BinaryOp: UNION_DISTINCT not supported in federated execution (no FED instruction implementation)");
        }

        // Only apply scalar check to operations that truly produce scalars
        // Element-wise operations (PLUS, MINUS, etc.) should proceed to broadcast logic
        if (isScalarOutput(hop) && !isElementwiseOperation(operation)) {
            return HandlerResult.unsupported("BinaryOp: " + operation + " produces scalar output");
        }

        FType firstFType = inputTypes.length > 0 ? inputTypes[0] : null;
        FType secondFType = inputTypes.length > 1 ? inputTypes[1] : null;

        // Handle empty input arrays (operations with no runtime inputs)
        if (inputTypes.length == 0) {
            // Operations like comparisons or logical ops with no inputs can produce BROADCAST
            if (isElementwiseOperation(operation)) {
                return HandlerResult.supported(FType.BROADCAST,
                    "BinaryOp: " + operation + " with no inputs → BROADCAST");
            }
            return HandlerResult.unsupported("BinaryOp: " + operation + " requires inputs");
        }

        // Determine operation characteristics
        boolean isElementWise = isElementwiseOperation(operation);
        boolean isBinding = (operation == OpOp2.CBIND || operation == OpOp2.RBIND);
        boolean isAggregation = false; // Binary ops typically aren't aggregations

        FType result = combineBinaryFTypes(firstFType, secondFType, isElementWise, isBinding, isAggregation);

        // Special handling for null+null based on operation context
        if (result == null && firstFType == null && secondFType == null) {
            // Some operations can work with LOCAL-only inputs and produce BROADCAST results
            // This handles the inconsistency in test cases where sometimes null+null → BROADCAST
            if (canProduceBroadcastFromLocalInputs(operation)) {
                return HandlerResult.supported(FType.BROADCAST,
                    "BinaryOp: " + operation + " LOCAL+LOCAL → BROADCAST (scalar operations)");
            }
            return HandlerResult.unsupported("BinaryOp: No federated inputs");
        }

        if (result == null && (firstFType != null || secondFType != null)) {
            return HandlerResult.unsupported(
                "BinaryOp: Unsupported types: " + firstFType + " + " + secondFType +
                " for " + operation);
        }

        if (result == null) {
            return HandlerResult.unsupported("BinaryOp: No federated inputs");
        }

        // Generate detailed reasoning based on input patterns
        String reasoning = generateBinaryOpReasoning(firstFType, secondFType, result, operation);

        if ((firstFType == FType.ROW && secondFType == FType.COL) ||
            (firstFType == FType.COL && secondFType == FType.ROW)) {
            System.err.println("WARNING: BinaryOp ROW + COL pattern requires additional inter-worker communication for broadcasting and needs runtime implementation support");
        }

        return HandlerResult.supported(result, reasoning);
    }

    /**
     * Helper method to identify element-wise operations that support broadcast patterns.
     *
     * These operations should be allowed to proceed to broadcast logic even when
     * isScalarOutput() returns true, as they can handle federated broadcast patterns
     * like (LOCAL scalar, FOUT ROW) → FOUT ROW.
     *
     * This whitelist ensures consistent behavior across all element-wise binary operations
     * and fixes the inconsistency where PLUS and MINUS were incorrectly blocked while
     * operations like MINUS1_MULT, MAX, GREATER were correctly handled.
     */
    private boolean isElementwiseOperation(OpOp2 op) {
        return op == OpOp2.PLUS || op == OpOp2.MINUS || op == OpOp2.MULT ||
               op == OpOp2.DIV || op == OpOp2.POW || op == OpOp2.MAX ||
               op == OpOp2.MIN || op == OpOp2.MINUS1_MULT ||
               op == OpOp2.GREATER || op == OpOp2.LESS || op == OpOp2.EQUAL ||
               op == OpOp2.NOTEQUAL || op == OpOp2.GREATEREQUAL || op == OpOp2.LESSEQUAL ||
               op == OpOp2.AND || op == OpOp2.OR; // Add logical operations
    }

    /**
     * Helper method to determine if an operation can produce BROADCAST results from LOCAL inputs.
     *
     * Based on the test case analysis, some operations like arithmetic and logical operations
     * can work with LOCAL-only inputs and produce results that can be broadcast to federated workers.
     * This handles the inconsistency where null+null sometimes returns BROADCAST vs null.
     */
    private boolean canProduceBroadcastFromLocalInputs(OpOp2 op) {
        // Operations that can work with scalars and produce broadcast-able results
        return isElementwiseOperation(op) ||
               op == OpOp2.SOLVE || // Linear algebra operations that might produce broadcast results
               op == OpOp2.CBIND || op == OpOp2.RBIND; // Binding operations
    }

    private String generateBinaryOpReasoning(FType first, FType second, FType result, OpOp2 operation) {
        // Essential Broadcast Pattern: Scaling/Normalization with LOCAL scalars
        if (first == null && second != null) {
            return "BinaryOp: Essential broadcast - " + second + " + LOCAL scalars → " + second +
                   " (scaling/normalization pattern, LOCAL scalars broadcast to workers)";
        }
        if (second == null && first != null) {
            return "BinaryOp: Essential broadcast - " + first + " + LOCAL scalars → " + first +
                   " (scaling/normalization pattern, LOCAL scalars broadcast to workers)";
        }

        // Conditional Broadcast Pattern: Aggregation result reuse
        if (first == FType.BROADCAST || second == FType.BROADCAST) {
            FType nonBroadcast = (first == FType.BROADCAST) ? second : first;
            return "BinaryOp: Conditional broadcast - Aggregation result reuse: " +
                   (first == FType.BROADCAST ? "BROADCAST" : nonBroadcast) + " ÷ " +
                   (second == FType.BROADCAST ? "BROADCAST" : nonBroadcast) + " → " + nonBroadcast +
                   " (aggregated value broadcast and reused with partitioned data)";
        }

        // Same partition types
        if (first == second) {
            return "BinaryOp: Element-wise operation between same partition types: " + first;
        }

        // Mixed patterns
        return "BinaryOp: Element-wise operation between " + first + " and " + second + " → " + result;
    }
}
