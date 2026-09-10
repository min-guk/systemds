/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.fedCostBased;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Unified utility class for logging federated planner information.
 * Provides methods to log hop details including privacy constraints and FType information,
 */
public class FederatedPlannerLogger {
    private static final boolean ENABLE_REWIRE_HIERARCHY_LOG = false;
    private static final Log LOG = LogFactory.getLog(FederatedPlannerLogger.class.getName());
	private static final boolean ENABLE_STDOUT_LOGS = FederatedPlannerTrace.isEnabled();
	private static final boolean ENABLE_UNGATED_STDOUT_LOGS = Boolean.getBoolean("sysds.fedplanner.stdout");

	private static boolean allowUngatedStdout() {
		return ENABLE_STDOUT_LOGS || ENABLE_UNGATED_STDOUT_LOGS;
	}

	private static boolean shouldStdout(Hop hop) {
		return FederatedPlannerTrace.shouldTrace(hop);
	}

    // ===================================================================================
    // Generic Logging Helpers
    // ===================================================================================

    public static void logInfoMessage(String message) {
        if (LOG.isDebugEnabled())
            LOG.debug(message);
    }

    public static void logWarnMessage(String message) {
        LOG.warn(message);
    }

    public static void logErrorMessage(String message) {
        LOG.error(message);
    }

    public static void logException(String message, Exception ex) {
        logErrorMessage(message);
        if (ex == null) {
            return;
        }
        LOG.error("Exception: " + ex.getClass().getSimpleName()
            + (ex.getMessage() != null ? " - " + ex.getMessage() : ""));
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        LOG.error(sw.toString());
    }
    
    /**
     * Logs hop information including name, hop ID, child hop IDs, privacy constraint, and ftype
     * @param hop The hop to log information for
     * @param privacyConstraintMap Map containing privacy constraints for hops
     * @param fTypeMap Map containing FType information for hops
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logHopInfo(Hop hop, Map<Long, Privacy> privacyConstraintMap, 
                                  Map<Long, FType> fTypeMap, String logPrefix) {
        StringBuilder childIds = new StringBuilder();
        if (hop.getInput() != null && !hop.getInput().isEmpty()) {
            for (int i = 0; i < hop.getInput().size(); i++) {
                if (i > 0) childIds.append(",");
                childIds.append(hop.getInput().get(i).getHopID());
            }
        } else {
            childIds.append("none");
        }
        
        Privacy privacyConstraint = privacyConstraintMap.get(hop.getHopID());
        FType ftype = fTypeMap.get(hop.getHopID());
        
        // Get hop type and opcode information
        String hopType = hop.getClass().getSimpleName();
        String opCode = hop.getOpString();
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("[" + logPrefix + "] (ID:" + hop.getHopID() + " Name:" + hop.getName()
                + ") Type:" + hopType + " OpCode:" + opCode + " ChildIDs:(" + childIds.toString()
                + ") Privacy:" + (privacyConstraint != null ? privacyConstraint : "null")
                + " FType:" + (ftype != null ? ftype : "null"));
        }
    }
    
    /**
     * Logs basic hop information without privacy and FType details
     * @param hop The hop to log information for
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logBasicHopInfo(Hop hop, String logPrefix) {
        StringBuilder childIds = new StringBuilder();
        if (hop.getInput() != null && !hop.getInput().isEmpty()) {
            for (int i = 0; i < hop.getInput().size(); i++) {
                if (i > 0) childIds.append(",");
                childIds.append(hop.getInput().get(i).getHopID());
            }
        } else {
            childIds.append("none");
        }
        
        String hopType = hop.getClass().getSimpleName();
        String opCode = hop.getOpString();
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("[" + logPrefix + "] (ID:" + hop.getHopID() + " Name:" + hop.getName()
                + ") Type:" + hopType + " OpCode:" + opCode + " ChildIDs:(" + childIds.toString() + ")");
        }
    }
    
    /**
     * Logs detailed hop information with dimension and data type
     * @param hop The hop to log information for
     * @param privacyConstraintMap Map containing privacy constraints for hops
     * @param fTypeMap Map containing FType information for hops
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logDetailedHopInfo(Hop hop, Map<Long, Privacy> privacyConstraintMap, 
                                         Map<Long, FType> fTypeMap, String logPrefix) {
        StringBuilder childIds = new StringBuilder();
        if (hop.getInput() != null && !hop.getInput().isEmpty()) {
            for (int i = 0; i < hop.getInput().size(); i++) {
                if (i > 0) childIds.append(",");
                childIds.append(hop.getInput().get(i).getHopID());
            }
        } else {
            childIds.append("none");
        }
        
        Privacy privacyConstraint = privacyConstraintMap.get(hop.getHopID());
        FType ftype = fTypeMap.get(hop.getHopID());
        
        String hopType = hop.getClass().getSimpleName();
        String opCode = hop.getOpString();
        String dataType = hop.getDataType().toString();
        String dimensions = "[" + hop.getDim1() + "x" + hop.getDim2() + "]";
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("[" + logPrefix + "] (ID:" + hop.getHopID() + " Name:" + hop.getName()
                + ") Type:" + hopType + " OpCode:" + opCode + " DataType:" + dataType + " Dims:" + dimensions
                + " ChildIDs:(" + childIds.toString() + ") Privacy:"
                + (privacyConstraint != null ? privacyConstraint : "null") + " FType:"
                + (ftype != null ? ftype : "null"));
        }
    }

    /**
     * Logs a summary of the Oracle's decision for a hop, including privacy, exec mode, placement and notes.
     * Additionally prints the hop's parents/children (including rewire relationships when applicable).
     * @param hop the hop that was evaluated
     * @param privacyConstraint privacy constraint applied to the hop
     * @param inputFTypes oracle input FTypes
     * @param caps result produced by the oracle
     * @param rewireTable table describing rewire relationships between hops
     */
    public static void logOracleDecision(Hop hop, Privacy privacyConstraint,
            List<FType> inputFTypes, OpCaps caps, Map<Long, List<Hop>> rewireTable) {
        if (!shouldStdout(hop))
            return;
        if (hop == null || caps == null)
            return;

        List<Hop> rewireChildren = getRewireConnections(hop, rewireTable, true);
        List<Hop> rewireParents = getRewireConnections(hop, rewireTable, false);
        List<Hop> allChildren = mergeHopLists(hop.getInput(), rewireChildren);
        List<Hop> allParents = mergeHopLists(hop.getParent(), rewireParents);

        String hopInfo = hop.getHopID() + " (" + hop.getOpString() + ")";
        String opcode = canonicalOpcode(hop);
        String hopType = hop.getClass().getSimpleName();
        String privacyInfo = (privacyConstraint == null) ? "null" : privacyConstraint.toString();
        String inputs = (inputFTypes == null || inputFTypes.isEmpty())
            ? "[]"
            : inputFTypes.toString();
        String foutType = caps.foutFType().map(Enum::name).orElse("none");
        String detail = caps.detail().orElse("");
        String childInfo = formatHopIdList(allChildren);
        String parentInfo = formatHopIdList(allParents);
        String rewireChildInfo = formatHopIdList(rewireChildren);
        String rewireParentInfo = formatHopIdList(rewireParents);

        StringBuilder notesBuilder = new StringBuilder();
        List<OpCaps.DecisionNote> notes = caps.notes();
        if (notes == null || notes.isEmpty()) {
            notesBuilder.append("[]");
        }
        else {
            notesBuilder.append("[");
            for (int i = 0; i < notes.size(); i++) {
                OpCaps.DecisionNote note = notes.get(i);
                if (i > 0)
                    notesBuilder.append("; ");
                notesBuilder.append(note.code());
                if (note.message() != null && !note.message().isEmpty()) {
                    notesBuilder.append(": ").append(note.message());
                }
            }
            notesBuilder.append("]");
        }

        System.out.println("[Oracle] hop=" + hopInfo
            + ", opcode=" + opcode
            + ", hopType=" + hopType
            + ", exec=" + caps.exec()
            + ", placement=" + caps.placement()
            + ", foutType=" + foutType
            + ", reason=" + caps.reason()
            + (detail.isEmpty() ? "" : ", detail=" + detail)
            + ", childIDs=" + childInfo
            + ", parentIDs=" + parentInfo
            + ", rewireChildIDs=" + rewireChildInfo
            + ", rewireParentIDs=" + rewireParentInfo
            + ", privacy=" + privacyInfo
            + ", inputs=" + inputs
            + ", notes=" + notesBuilder);
    }

    private static String canonicalOpcode(Hop hop) {
        if (hop == null)
            return "";
        if (hop instanceof AggBinaryOp && ((AggBinaryOp) hop).isMatrixMultiply())
            return Opcodes.MMULT.toString();
        if (hop instanceof LeftIndexingOp)
            return Opcodes.LEFT_INDEX.toString();
        if (hop instanceof IndexingOp)
            return Opcodes.RIGHT_INDEX.toString();
        if (hop instanceof ReorgOp)
            return ((ReorgOp) hop).getOp().toString();
        if (hop instanceof AggUnaryOp)
            return hop.getOpString().toLowerCase(Locale.ROOT);
        if (hop instanceof UnaryOp)
            return ((UnaryOp) hop).getOp().toString();
        if (hop instanceof BinaryOp)
            return ((BinaryOp) hop).getOp().toString();
        if (hop instanceof NaryOp)
            return ((NaryOp) hop).getOp().toString();
        if (hop instanceof TernaryOp)
            return ((TernaryOp) hop).getOp().toString();
        if (hop instanceof QuaternaryOp)
            return ((QuaternaryOp) hop).getOp().toString();
        if (hop instanceof FunctionOp)
            return FunctionOp.OPCODE;
        if (hop instanceof ParameterizedBuiltinOp) {
            ParamBuiltinOp op = ((ParameterizedBuiltinOp) hop).getOp();
            return (op != null) ? op.toString() : fallbackOpcode(hop);
        }
        if (hop instanceof DataOp)
            return ((DataOp) hop).getOp().toString();
        return fallbackOpcode(hop);
    }

    private static String fallbackOpcode(Hop hop) {
        String raw = hop.getOpString();
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
    
    /**
     * Logs error information for null fed plan scenarios
     * @param hopID The hop ID that caused the error
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logNullFedPlanError(long hopID, String logPrefix) {
		if (!ENABLE_STDOUT_LOGS)
			return;
        System.err.println("[" + logPrefix + "] childFedPlan is null for hopID: " + hopID);
    }
    
    /**
     * Logs detailed error information for conflict resolution scenarios
     * @param hopID The hop ID that caused the error
     * @param fedPlan The federated plan with error details
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logConflictResolutionError(long hopID, Object fedPlan, String logPrefix) {
		if (!ENABLE_STDOUT_LOGS)
			return;
        System.err.println("[" + logPrefix + "] confilctLOutFedPlan or confilctFOutFedPlan is null for hopID: " + hopID);
        System.err.println("  Child Hop Details:");
        if (fedPlan != null) {
            // Note: This assumes fedPlan has a getHopRef() method
            // In actual implementation, you might need to cast or handle differently
            System.err.println("    - Class: N/A");
            System.err.println("    - Name: N/A");
            System.err.println("    - OpString: N/A");
            System.err.println("    - HopID: " + hopID);
        }
    }
    
    /**
     * Logs debug information for getFederatedType function
     * @param hop The hop being analyzed
     * @param returnFType The FType that will be returned
     * @param reason The reason for the FType decision
     * @param inputFTypes Array of input FTypes (ft array from getFederatedTypeDebug)
     */
    public static void logGetFederatedTypeDebug(Hop hop, FType returnFType, String reason, FType[] inputFTypes) {
        String hopName = hop.getName() != null ? hop.getName() : "null";
        long hopID = hop.getHopID();
        String operationType = hop.getClass().getSimpleName();
        String opCode = hop.getOpString();
        
        // Build input FTypes string
        StringBuilder inputFTypesStr = new StringBuilder();
        if (inputFTypes != null && inputFTypes.length > 0) {
            inputFTypesStr.append("[");
            for (int i = 0; i < inputFTypes.length; i++) {
                if (i > 0) inputFTypesStr.append(",");
                inputFTypesStr.append(inputFTypes[i] != null ? inputFTypes[i].toString() : "null");
            }
            inputFTypesStr.append("]");
        } else {
            inputFTypesStr.append("[]");
        }
        
//        System.out.println("[GetFederatedType] HopName: " + hopName + " | HopID: " + hopID +
//                          " | OperationType: " + operationType + " | OpCode: " + opCode +
//                          " | InputFTypes: " + inputFTypesStr.toString() +
//                          " | ReturnFType: " + (returnFType != null ? returnFType : "null") +
//                          " | Reason: " + reason);
    }
    
    /**
     * Logs detailed hop error information with complete hop details
     * @param hop The hop that caused the error
     * @param logPrefix Prefix string to identify the log source
     * @param additionalMessage Additional error message
     */
    public static void logHopErrorDetails(Hop hop, String logPrefix, String additionalMessage) {
		if (!shouldStdout(hop))
			return;
        System.err.println("[" + logPrefix + "] " + additionalMessage);
        System.err.println("  Child Hop Details:");
        System.err.println("    - Class: " + hop.getClass().getSimpleName());
        System.err.println("    - Name: " + (hop.getName() != null ? hop.getName() : "null"));
        System.err.println("    - OpString: " + hop.getOpString());
        System.err.println("    - HopID: " + hop.getHopID());
    }
    
    /**
     * Logs detailed null child plan debugging information
     * @param childFedPlanPair The child federated plan pair that is null
     * @param optimalPlan The current optimal plan (parent)
     * @param memoTable The memo table for lookups
     */

    /**
     * Logs debugging information for TransRead hop rewiring process
     * @param hopName The name of the TransRead hop
     * @param hopID The ID of the TransRead hop  
     * @param childHops List of child hops found during rewiring
     * @param isEmptyChildHops Whether the child hops list is empty
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logTransReadRewireDebug(String hopName, long hopID, List<Hop> childHops, 
                                              boolean isEmptyChildHops, String logPrefix) {
		if (!ENABLE_STDOUT_LOGS)
			return;
        if (isEmptyChildHops) {
            System.err.println("[" + logPrefix + "] (hopName: " + hopName + ", hopID: " + hopID + ") child hops is empty");
        }
    }
    
    /**
     * Logs debugging information for filtered child hops during TransRead rewiring
     * @param hopName The name of the TransRead hop
     * @param hopID The ID of the TransRead hop
     * @param filteredChildHops List of filtered child hops
     * @param isEmptyFilteredChildHops Whether the filtered child hops list is empty
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logFilteredChildHopsDebug(String hopName, long hopID, List<Hop> filteredChildHops, 
                                                boolean isEmptyFilteredChildHops, String logPrefix) {
		if (!ENABLE_STDOUT_LOGS)
			return;
        if (isEmptyFilteredChildHops) {
            System.err.println("[" + logPrefix + "] (hopName: " + hopName + ", hopID: " + hopID + ") filtered child hops is empty");
        }
        else {
            System.out.println("[" + logPrefix + "] (hopName: " + hopName + ", hopID: " + hopID
                + ") filtered child hops: " + summarizeHopList(filteredChildHops));
        }
    }
    
    /**
     * Logs both parent and child hop relationships for a rewired TransRead hop, including filtered mappings.
     * @param transReadHop The TransRead hop being rewired
     * @param candidateChildHops All candidate child hops before filtering
     * @param filteredChildHops Child hops that matched the filtered criteria
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logRewireHierarchy(Hop transReadHop, List<Hop> candidateChildHops,
            List<Hop> filteredChildHops, String logPrefix) {
        if (!ENABLE_STDOUT_LOGS)
            return;
        if (transReadHop == null || !ENABLE_REWIRE_HIERARCHY_LOG)
            return;

        String parentSummary = summarizeHopList(transReadHop.getParent());
        String candidateSummary = summarizeHopList(candidateChildHops);
        String filteredSummary = summarizeHopList(filteredChildHops);

        System.out.println("[" + logPrefix + "] Rewire graph for "
            + summarizeHop(transReadHop) + " Parents=(" + parentSummary + ")"
            + " Candidates=(" + candidateSummary + ")"
            + " Filtered=(" + filteredSummary + ")");

        if (filteredChildHops != null && !filteredChildHops.isEmpty()) {
            for (Hop child : filteredChildHops) {
                String childParents = summarizeHopList(child.getParent());
                System.out.println("[" + logPrefix + "]   FilteredChild "
                    + summarizeHop(child) + " Parents=(" + childParents + ")");
            }
        }
    }

    private static String summarizeHop(Hop hop) {
        if (hop == null)
            return "null";
        String name = hop.getName() != null ? hop.getName() : "null";
        String opcode = hop.getOpString() != null ? hop.getOpString() : "null";
        return name + "(ID:" + hop.getHopID() + ",Op:" + opcode + ")";
    }

    private static String summarizeHopList(List<Hop> hops) {
        if (hops == null || hops.isEmpty())
            return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hops.size(); i++) {
            if (i > 0)
                sb.append("; ");
            sb.append(summarizeHop(hops.get(i)));
        }
        return sb.toString();
    }

    private static List<Hop> mergeHopLists(List<Hop> direct, List<Hop> additional) {
        List<Hop> merged = new ArrayList<>();
        if (direct != null && !direct.isEmpty())
            merged.addAll(direct);
        if (additional != null && !additional.isEmpty())
            merged.addAll(additional);
        return merged;
    }

    private static List<Hop> getRewireConnections(Hop hop, Map<Long, List<Hop>> rewireTable, boolean childConnections) {
        if (!(hop instanceof DataOp) || rewireTable == null)
            return Collections.emptyList();
        List<Hop> rewired = rewireTable.get(hop.getHopID());
        if (rewired == null || rewired.isEmpty())
            return Collections.emptyList();
        Types.OpOpData opType = ((DataOp) hop).getOp();
        if (childConnections && opType == Types.OpOpData.TRANSIENTREAD)
            return rewired;
        if (!childConnections && opType == Types.OpOpData.TRANSIENTWRITE)
            return rewired;
        return Collections.emptyList();
    }

    private static String formatHopIdList(List<Hop> hops) {
        if (hops == null || hops.isEmpty())
            return "[]";
        LinkedHashSet<Long> hopIds = new LinkedHashSet<>();
        for (Hop hop : hops) {
            if (hop != null)
                hopIds.add(hop.getHopID());
        }
        if (hopIds.isEmpty())
            return "[]";
        StringBuilder sb = new StringBuilder("[");
        Iterator<Long> idIter = hopIds.iterator();
        while (idIter.hasNext()) {
            sb.append(idIter.next());
            if (idIter.hasNext())
                sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * Logs detailed FType mismatch error information for TransRead hop
     * @param hop The TransRead hop with FType mismatch
     * @param filteredChildHops List of filtered child hops
     * @param fTypeMap Map containing FType information for hops
     * @param expectedFType The expected FType
     * @param mismatchedFType The mismatched FType
     * @param mismatchIndex The index where mismatch occurred
     */
    public static void logFTypeMismatchError(Hop hop, List<Hop> filteredChildHops, Map<Long, FType> fTypeMap,
                                           FType expectedFType, FType mismatchedFType, int mismatchIndex) {
        if (!shouldStdout(hop))
            return;
        String hopName = hop.getName();
        long hopID = hop.getHopID();
        
        System.err.println("[Error] FType MISMATCH DETECTED for TransRead (hopName: " + hopName + ", hopID: " + hopID + ")");
        System.err.println("[Error] TRANSREAD HOP DETAILS - Type: " + hop.getClass().getSimpleName() + 
            ", OpType: " + (hop instanceof org.apache.sysds.hops.DataOp ? 
                ((org.apache.sysds.hops.DataOp)hop).getOp() : "N/A") + 
            ", DataType: " + hop.getDataType() + 
            ", Dims: [" + hop.getDim1() + "x" + hop.getDim2() + "]");
        System.err.println("[Error] FILTERED CHILD HOPS FTYPE ANALYSIS:");
        
        for (int j = 0; j < filteredChildHops.size(); j++) {
            Hop childHop = filteredChildHops.get(j);
            FType childFType = fTypeMap.get(childHop.getHopID());
            System.err.println("[Error]   FilteredChild[" + j + "] - Name: " + childHop.getName() + 
                ", ID: " + childHop.getHopID() + 
                ", FType: " + childFType + 
                ", Type: " + childHop.getClass().getSimpleName() + 
                ", OpType: " + (childHop instanceof org.apache.sysds.hops.DataOp ? 
                    ((org.apache.sysds.hops.DataOp)childHop).getOp().toString() : "N/A") +
                ", Dims: [" + childHop.getDim1() + "x" + childHop.getDim2() + "]");
        }
        
        System.err.println("[Error] Expected FType: " + expectedFType + 
                          ", Mismatched FType: " + mismatchedFType + 
                          " at child index: " + mismatchIndex);
    }
    
    /**
     * Logs FType debug information for DataOp operations (FEDERATED, TRANSIENTWRITE, TRANSIENTREAD)
     * @param hop The DataOp hop being analyzed
     * @param fType The FType that was determined for this operation
     * @param opType The operation type (FEDERATED, TRANSIENTWRITE, TRANSIENTREAD)
     * @param reason The reason for the FType decision
     */
    public static void logDataOpFTypeDebug(Hop hop, FType fType, String opType, String reason) {
        if (!shouldStdout(hop))
            return;
        String hopName = hop.getName() != null ? hop.getName() : "null";
        long hopID = hop.getHopID();
        String hopClass = hop.getClass().getSimpleName();
        String dimensions = "[" + hop.getDim1() + "x" + hop.getDim2() + "]";
        
        System.out.println("[GetFederatedType] HopName: " + hopName +
                          " | HopID: " + hopID + 
                          " | HopClass: " + hopClass + 
                          " | OpType: " + opType + 
                          " | Dims: " + dimensions + 
                          " | FType: " + (fType != null ? fType : "null") + 
                          " | Reason: " + reason);
    }
    
    // Wire UnRefTwrite to LiveOut Logging Methods
    // ===================================================================================

    /**
     * Logs the start of wireUnRefTwriteToLiveOut processing
     * @param unRefTwriteSetSize Number of unRefTwrite hops to process
     */
    public static void logWireUnRefTwriteStart(int unRefTwriteSetSize) {
		if (!allowUngatedStdout())
			return;
        System.out.println("\n[INFO] wireUnRefTwriteToLiveOut - Processing " + unRefTwriteSetSize + " unRefTwrite hops");
    }

    /**
     * Logs the processing of a specific unRefTwrite hop
     * @param hopName Name of the hop
     * @param hopID ID of the hop
     * @param hop The hop being processed
     * @param fType FType of the hop
     */
    public static void logProcessingUnRefTwriteHop(String hopName, long hopID, Hop hop, FType fType) {
		if (!allowUngatedStdout())
			return;
        System.out.println("[INFO] Processing unRefTwrite hop: " + hopName + " (ID: " + hopID + ")");
        System.out.println("  - Type: " + hop.getClass().getSimpleName());
        System.out.println("  - DataType: " + hop.getDataType());
        System.out.println("  - Dimensions: " + hop.getDim1() + "x" + hop.getDim2());
        System.out.println("  - FType: " + fType);
    }

    /**
     * Logs candidate information for wireUnRefTwriteToLiveOut
     * @param candidateInfo List of candidate information strings
     */
    public static void logCandidateInfo(List<String> candidateInfo) {
		if (!allowUngatedStdout())
			return;
        for (String info : candidateInfo) {
            System.out.println(info);
        }
    }

    /**
     * Logs successful connection in wireUnRefTwriteToLiveOut
     * @param bestLiveOutHopName Name of the connected hop
     * @param bestScore Score of the connection
     */
    public static void logSuccessfulConnection(String bestLiveOutHopName, int bestScore) {
		if (!allowUngatedStdout())
			return;
        System.out.println("  ✓ CONNECTED to: " + bestLiveOutHopName + " (Score: " + bestScore + ")");
    }

    /**
     * Logs no compatible connection found
     */
    public static void logNoCompatibleConnection() {
		if (!allowUngatedStdout())
			return;
        System.out.println("  ✗ NO COMPATIBLE CONNECTION FOUND");
        System.out.println("  - Falling back to original algorithm...");
    }

    /**
     * Logs fallback connection in wireUnRefTwriteToLiveOut
     * @param liveOutHopName Name of the fallback connection
     */
    public static void logFallbackConnection(String liveOutHopName) {
		if (!allowUngatedStdout())
			return;
        System.out.println("  ✓ FALLBACK CONNECTION to: " + liveOutHopName + " (No compatibility check)");
    }

    /**
     * Logs warning for name matching fallback
     * @param unRefTwriteHopName Name of the unRefTwrite hop
     * @param liveOutHopName Name of the liveOut hop
     */
    public static void logNameMatchingFallbackWarning(String unRefTwriteHopName, String liveOutHopName) {
		if (!allowUngatedStdout())
			return;
        System.err.println("WARNING: No exact match found, using partial name matching for " + 
                          unRefTwriteHopName + " -> " + liveOutHopName + 
                          " - algorithm needs improvement");
    }

    /**
     * Creates candidate information string for wireUnRefTwriteToLiveOut
     * @param liveOutHopName Name of the candidate hop
     * @param representativeHop Representative hop for the candidate
     * @param liveOutFType FType of the candidate
     * @param priority Priority of the candidate
     * @param score Score of the candidate
     * @param isCompatible Whether the candidate is compatible
     * @param reason Reason for the compatibility result
     * @return Formatted candidate information string
     */
    public static String createCandidateInfo(String liveOutHopName, Hop representativeHop, FType liveOutFType,
                                            int priority, int score, boolean isCompatible, String reason) {
        return "  - Candidate: " + liveOutHopName +
               " (Type: " + representativeHop.getClass().getSimpleName() +
               ", DataType: " + representativeHop.getDataType() +
               ", Dims: " + representativeHop.getDim1() + "x" + representativeHop.getDim2() +
               ", FType: " + liveOutFType +
               ", Priority: " + priority +
               ", Score: " + score +
               ", Compatible: " + isCompatible +
               ", Reason: " + reason + ")";
    }

    /**
     * Logs exec type conflicts when placement is identical but execution modes differ.
     * @param currentHop The hop that has the conflict
     * @param previousExecType The previously selected ExecType
     * @param incomingExecType The newly observed ExecType
     * @param resolvedExecType The ExecType chosen to resolve the conflict
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logExecTypeConflict(Hop currentHop, ExecType previousExecType,
                                          ExecType incomingExecType, ExecType resolvedExecType,
                                          String logPrefix) {
		if (!shouldStdout(currentHop))
			return;
        System.out.println("[" + logPrefix + "] EXEC TYPE CONFLICT DETECTED:");
        System.out.println("  Hop - ID:" + currentHop.getHopID() +
                          " Name:" + (currentHop.getName() != null ? currentHop.getName() : "null") +
                          " Type:" + currentHop.getClass().getSimpleName() +
                          " OpCode:" + currentHop.getOpString());
        System.out.println("  ExecType (prev -> incoming -> resolved): "
            + previousExecType + " -> " + incomingExecType + " -> " + resolvedExecType);
    }

    /**
     * Logs placement conflict information when hasPlacement is true
     * @param currentHop The hop that has placement conflict
     * @param parentHop The parent hop causing the conflict (if available)
     * @param currentFedOutType The current federated output type
     * @param parentFedOutType The parent federated output type
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logPlacementConflict(Hop currentHop, Hop parentHop,
                                           FEDInstruction.FederatedOutput currentFedOutType,
                                           FEDInstruction.FederatedOutput parentFedOutType,
                                           String logPrefix) {
		if (!shouldStdout(currentHop))
			return;
        System.out.println("[" + logPrefix + "] PLACEMENT CONFLICT DETECTED:");

        // Current hop information
        System.out.println("  Current Hop - ID:" + currentHop.getHopID() +
                          " Name:" + (currentHop.getName() != null ? currentHop.getName() : "null") +
                          " Type:" + currentHop.getClass().getSimpleName() +
                          " OpCode:" + currentHop.getOpString() +
                          " CurrentFedOutType:" + currentFedOutType);

        // Parent hop information (if available)
        if (parentHop != null) {
            System.out.println("  Parent Hop - ID:" + parentHop.getHopID() +
                              " Name:" + (parentHop.getName() != null ? parentHop.getName() : "null") +
                              " Type:" + parentHop.getClass().getSimpleName() +
                              " OpCode:" + parentHop.getOpString() +
                              " RequiredFedOutType:" + parentFedOutType);
        } else {
            System.out.println("  Parent Hop - RequiredFedOutType:" + parentFedOutType + " (Parent hop details not available)");
        }
    }

	/**
	 * Converts Privacy enum names to 6-character abbreviations.
	 *
	 * @param privacyName The full Privacy enum name
	 * @return 6-character abbreviation
	 */
	private static String getPrivacyAbbreviation(String privacyName) {
		switch (privacyName) {
			case "PUBLIC":
				return "PUBLIC";
			case "PRIVATE":
				return "PRIVTE";
			case "PRIVATE_AGGREGATE":
				return "PRIVAGG";
			case "PRIVATE_AGGREGATE_TO_PUBLIC":
				return "PRVAGP";
			default:
				return privacyName.length() > 6 ? privacyName.substring(0, 6) : privacyName;
		}
	}

}
