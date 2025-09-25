package org.apache.sysds.hops.fedplanner.ftype.handlers;

import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

/**
 * Base class providing common functionality for federated type handlers.
 */
public class FederatedTypeHandler {

    /**
     * Determines the federated type for the given hop operation.
     * Override this method in subclasses.
     */
    public HandlerResult determineType(Hop hop, FType[] inputTypes) {
        return HandlerResult.unsupported("Base handler should not be used directly");
    }

    /**
     * Checks if this handler can process the given hop operation.
     * Override this method in subclasses.
     */
    public boolean canHandle(Hop hop) {
        return false;
    }

    /**
     * Helper method to check if first input is federated
     */
    protected boolean hasFederatedFirstInput(FType[] inputTypes) {
        return inputTypes.length > 0 && inputTypes[0] != null;
    }

    /**
     * Helper method to check if second input is federated
     */
    protected boolean hasFederatedSecondInput(FType[] inputTypes) {
        return inputTypes.length > 1 && inputTypes[1] != null;
    }

    /**
     * Helper method to check if any input is federated
     */
    protected boolean hasAnyFederatedInput(FType[] inputTypes) {
        for (FType ft : inputTypes) {
            if (ft != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to get first non-null FType from inputs
     */
    protected FType getFirstNonNullFType(FType[] inputTypes) {
        for (FType ft : inputTypes) {
            if (ft != null) {
                return ft;
            }
        }
        return null;
    }

    /**
     * Helper method to check if inputs have same FType
     */
    protected boolean haveSameFType(FType first, FType second) {
        if (first == null || second == null) {
            return false;
        }
        return first == second;
    }

    /**
     * Helper method to propagate FType from inputs
     * Returns first non-null FType, commonly used pattern
     */
    protected FType propagateFirstNonNull(FType[] inputTypes) {
        return getFirstNonNullFType(inputTypes);
    }

    /**
     * Determines result FType when combining two types in binary operations.
     * null = LOCAL (coordinator only), BROADCAST = replicated on all workers
     *
     * Updated implementation based on agreed ba(+*) propagation rules:
     * - Deterministic and symmetric handling
     * - Clear separation between executability and output FType
     * - Consistent with verified test cases
     */
    protected FType combineBinaryFTypes(FType first, FType second,
                                       boolean isElementWise, boolean isBinding, boolean isAggregation) {
        // Step 1: Input Normalization
        // For consistency, normalize LOCAL to left side when paired with partition
        if (second == null && first != null && first != FType.BROADCAST) {
            // Swap (PARTITION, null) → (null, PARTITION)
            FType temp = first;
            first = second;
            second = temp;
        }

        // Step 2: Executability Check (determines if operation can proceed)

        // Both LOCAL → execute on coordinator
        if (first == null && second == null) {
            if (isAggregation) {
                return FType.BROADCAST;
            }
            return null;
        }

        // One LOCAL, one Federated
        if (first == null || second == null) {
            FType federatedType = (first != null) ? first : second;

            // Binding: Cannot mix LOCAL with federated (alignment undefined)
            if (isBinding) {
                return null;
            }

            // Aggregation: LOCAL broadcasts to federated workers
            // This maintains the federated partition structure
            if (isAggregation) {
                return federatedType;
            }

            // Element-wise & others: LOCAL broadcasts to federated workers
            return federatedType;
        }

        // BROADCAST handling
        if (first == FType.BROADCAST || second == FType.BROADCAST) {
            FType nonBroadcastType = (first == FType.BROADCAST) ? second : first;

            // Aggregation: BROADCAST causes duplicate counting (N×value)
            if (isAggregation) {
                return null;
            }

            // Binding: Ambiguous which replica to use
            if (isBinding) {
                return null;
            }

            // BROADCAST adapts to partitioned type
            if (nonBroadcastType != null && nonBroadcastType != FType.BROADCAST) {
                return nonBroadcastType;
            }

            // Both BROADCAST → stays BROADCAST
            return FType.BROADCAST;
        }

        // Same partition types → direct operation
        if (first == second) {
            if (isAggregation)
                return FType.BROADCAST;  // ROW×ROW, COL×COL 모두 동일 규칙
            return first;
        }

        // Mixed partitions (ROW + COL)
        if ((first == FType.ROW && second == FType.COL) ||
            (first == FType.COL && second == FType.ROW)) {

            // Binding: Incompatible structures
            if (isBinding) {
                return null;
            }

            // Aggregation (ba(+*)): policy = ROW priority (matches verified tests)
            if (isAggregation) {
                return FType.ROW;
            }

            // Non-aggregation: keep existing policy (ROW wins)
            return FType.ROW;
        }

        // Unhandled combinations
        return null;
    }

    /**
     * Original combineBinaryFTypes for backward compatibility (defaults to element-wise)
     */
    protected FType combineBinaryFTypes(FType first, FType second) {
        return combineBinaryFTypes(first, second, true, false, false);
    }

    /**
     * Determines the effect of transpose on partition type.
     */
    protected FType transposePartition(FType fType) {
        if (fType == FType.ROW) return FType.COL;
        else if (fType == FType.COL) return FType.ROW;
        return fType;
    }

    /**
     * Checks if the given hop operation is universally unsupported for federated execution.
     */
    public boolean isUnsupportedOperation(Hop hop) {
        return hop instanceof DataGenOp || hop instanceof DnnOp ||
               hop instanceof FunctionOp || hop instanceof LiteralOp || hop instanceof DataOp;
    }

    /**
     * Checks if the operation produces scalar output that doesn't have FType.
     * Scalar values don't have FType as there's no partitioning concept for scalars.
     */
    protected boolean isScalarOutput(Hop hop) {
        return hop.isScalar();
    }
}