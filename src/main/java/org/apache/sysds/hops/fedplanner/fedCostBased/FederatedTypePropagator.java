package org.apache.sysds.hops.fedplanner.fedCostBased;

import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.*;
import org.apache.sysds.hops.fedplanner.fedCostBased.ftype.handlers.HandlerResult;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DataExpression;

import java.util.Map;
import java.util.List;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.common.Types.ParamBuiltinOp;
/**
 * Main propagator class for determining federated types of operations.
 * Propagates FTypes from input operations to output based on operation-specific rules.
 *
 * This class replaces the monolithic getFederatedTypeDebug function with a
 * modular architecture that separates concerns:
 * - Universal constraints checking
 * - Operation-specific handling
 * - FType propagation rules
 * - Logging and debugging
 */
public class FederatedTypePropagator {

    private final FederatedTypeHandlerFactory handlerFactory;

    public FederatedTypePropagator() {
        this.handlerFactory = new FederatedTypeHandlerFactory();
    }

    /**
     * Debug version of getFederatedType that logs detailed information about the decision process.
     *
     * @param hop The hop operation to analyze
     * @param fTypeMap Map containing FType information for all processed hops
     * @return The FType of the output, or null if the operation doesn't support federated execution
     */
    public FType getFederatedTypeDebug(Hop hop, Map<Long, FType> fTypeMap) {
        // Get appropriate handler for the operation to check constraints
        FederatedTypeHandler handler = handlerFactory.getHandler(hop);

        // Check universal constraints first
        if (handler.isUnsupportedOperation(hop)) {
            String reason = "Operation type " + hop.getClass().getSimpleName() + " not supported for federated execution";
//            FederatedTypeLogger.logGetFederatedTypeDebug(hop, null, reason, null);
            return null;
        }

        // Extract input FTypes
        FType[] inputTypes = extractInputTypes(hop, fTypeMap);

        // Handle operations with no inputs
        if (inputTypes.length == 0) {
            FederatedPlannerLogger.logGetFederatedTypeDebug(hop, null, "No inputs available", inputTypes);
            return null;
        }

        // Log handler selection if debug is enabled
//        if (FederatedTypeLogger.isDebugEnabled()) {
//            FederatedTypeLogger.logHandlerSelection(hop, handler.getClass().getSimpleName());
//        }

        // Determine the output FType
        HandlerResult result = handler.determineType(hop, inputTypes);
        FederatedPlannerLogger.logGetFederatedTypeDebug(hop, result.getFType(), result.getReason(), inputTypes);

        return result.getFType();
    }

    public static Privacy getPrivacyConstraint(Hop hop, List<Hop> inputHops, Map<Long, Privacy> privacyMap) {
        Privacy[] pc = new Privacy[inputHops.size()];
        for (int i = 0; i < inputHops.size(); i++)
            pc[i] = privacyMap.get(inputHops.get(i).getHopID());

        boolean hasPrivateAggreate = false;

        for (Privacy p : pc) {
            if (p == Privacy.PRIVATE) {
                return Privacy.PRIVATE;
            } else if (p == Privacy.PRIVATE_AGGREGATE) {
                hasPrivateAggreate = true;
            }
        }

        if (hasPrivateAggreate) {
            if (hop instanceof AggUnaryOp || hop instanceof AggBinaryOp || hop instanceof QuaternaryOp) {
                return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
            } else if (hop instanceof TernaryOp) {
                switch (((TernaryOp) hop).getOp()) {
                    case MOMENT:
                    case COV:
                    case CTABLE:
                    case INTERQUANTILE:
                    case QUANTILE:
                        return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
                    default:
                        return Privacy.PRIVATE_AGGREGATE;
                }
            } else if (hop instanceof ParameterizedBuiltinOp
                    && ((ParameterizedBuiltinOp) hop).getOp() == ParamBuiltinOp.GROUPEDAGG) {
                return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
            } else {
                return Privacy.PRIVATE_AGGREGATE;
            }
        }

        return Privacy.PUBLIC;
    }

    /**
     * Extract FTypes for all inputs of the given hop
     */
    private FType[] extractInputTypes(Hop hop, Map<Long, FType> fTypeMap) {
        FType[] inputTypes = new FType[hop.getInput().size()];
        for (int i = 0; i < hop.getInput().size(); i++) {
            inputTypes[i] = fTypeMap.get(hop.getInput().get(i).getHopID());
        }
        return inputTypes;
    }

    public static FType deriveFType(DataOp fedInit) {
        Hop ranges = fedInit.getInput(fedInit.getParameterIndex(DataExpression.FED_RANGES));
        boolean rowPartitioned = true;
        boolean colPartitioned = true;
        for( int i=0; i<ranges.getInput().size()/2; i++ ) { // workers
            Hop beg = ranges.getInput(2*i);
            Hop end = ranges.getInput(2*i+1);
            long rl = HopRewriteUtils.getIntValueSafe(beg.getInput(0));
            long ru = HopRewriteUtils.getIntValueSafe(end.getInput(0));
            long cl = HopRewriteUtils.getIntValueSafe(beg.getInput(1));
            long cu = HopRewriteUtils.getIntValueSafe(end.getInput(1));
            rowPartitioned &= (cu-cl == fedInit.getDim2());
            colPartitioned &= (ru-rl == fedInit.getDim1());
        }
        return rowPartitioned && colPartitioned ?
                FType.FULL : rowPartitioned ? FType.ROW :
                colPartitioned ? FType.COL : FType.OTHER;
    }

    /**
     * Static convenience method that mimics the original function signature.
     * This allows for easy migration from the old monolithic function.
     *
     * @param hop The hop operation to analyze
     * @param fTypeMap Map containing FType information for all processed hops
     * @return The FType of the output, or null if not federated
     */
    public static FType getFederatedType(Hop hop, Map<Long, FType> fTypeMap) {
        FederatedTypePropagator propagator = new FederatedTypePropagator();
        return propagator.getFederatedTypeDebug(hop, fTypeMap);
    }
}