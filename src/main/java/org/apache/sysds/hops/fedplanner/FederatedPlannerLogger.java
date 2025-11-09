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

package org.apache.sysds.hops.fedplanner;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedMemoTable.FedPlan;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Unified utility class for logging federated planner information.
 * Provides methods to log hop details including privacy constraints and FType information,
 * as well as methods to print detailed FederatedMemoTable tree structures and cost analysis.
 * This class integrates the functionality of the former FederatedMemoTablePrinter.
 */
public class FederatedPlannerLogger {
    
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
        
        System.out.println("[" + logPrefix + "] (ID:" + hop.getHopID() + " Name:" + hop.getName() + 
                          ") Type:" + hopType + " OpCode:" + opCode + 
                          " ChildIDs:(" + childIds.toString() + ") Privacy:" + 
                          (privacyConstraint != null ? privacyConstraint : "null") + 
                          " FType:" + (ftype != null ? ftype : "null"));
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
        
        System.out.println("[" + logPrefix + "] (ID:" + hop.getHopID() + " Name:" + hop.getName() + 
                          ") Type:" + hopType + " OpCode:" + opCode + 
                          " ChildIDs:(" + childIds.toString() + ")");
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
        
        System.out.println("[" + logPrefix + "] (ID:" + hop.getHopID() + " Name:" + hop.getName() + 
                          ") Type:" + hopType + " OpCode:" + opCode + " DataType:" + dataType + 
                          " Dims:" + dimensions + " ChildIDs:(" + childIds.toString() + ") Privacy:" + 
                          (privacyConstraint != null ? privacyConstraint : "null") + 
                          " FType:" + (ftype != null ? ftype : "null"));
    }
    
    /**
     * Logs error information for null fed plan scenarios
     * @param hopID The hop ID that caused the error
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logNullFedPlanError(long hopID, String logPrefix) {
        System.err.println("[" + logPrefix + "] childFedPlan is null for hopID: " + hopID);
    }
    
    /**
     * Logs detailed error information for conflict resolution scenarios
     * @param hopID The hop ID that caused the error
     * @param fedPlan The federated plan with error details
     * @param logPrefix Prefix string to identify the log source
     */
    public static void logConflictResolutionError(long hopID, Object fedPlan, String logPrefix) {
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
    public static void logNullChildPlanDebug(Pair<Long, FederatedOutput> childFedPlanPair, 
                                           FedPlan optimalPlan, 
                                           org.apache.sysds.hops.fedplanner.FederatedMemoTable memoTable) {
        FederatedOutput alternativeFedType = (childFedPlanPair.getRight() == FederatedOutput.LOUT) ? 
                                           FederatedOutput.FOUT : FederatedOutput.LOUT;
        FedPlan alternativeChildPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair.getLeft(), alternativeFedType);
        
        // Get child hop info
        Hop childHop = null;
        String childInfo = "UNKNOWN";
        if (alternativeChildPlan != null) {
            childHop = alternativeChildPlan.getHopRef();
            // Check if required fed type plan exists
            String requiredExists = memoTable.getFedPlanAfterPrune(childFedPlanPair.getLeft(), childFedPlanPair.getRight()) != null ? "O" : "X";
            // Check if alternative fed type plan exists  
            String altExists = alternativeChildPlan != null ? "O" : "X";
            
            childInfo = String.format("ID:%d|Name:%s|Op:%s|RequiredFedType:%s(%s)|AltFedType:%s(%s)", 
                childHop.getHopID(),
                childHop.getName() != null ? childHop.getName() : "null",
                childHop.getOpString(),
                childFedPlanPair.getRight(),
                requiredExists,
                alternativeFedType,
                altExists);
        }
        
        // Current parent hop info
        String currentParentInfo = String.format("ID:%d|Name:%s|Op:%s|FedType:%s|RequiredChild:%s", 
            optimalPlan.getHopID(),
            optimalPlan.getHopRef().getName() != null ? optimalPlan.getHopRef().getName() : "null",
            optimalPlan.getHopRef().getOpString(),
            optimalPlan.getFedOutType(),
            childFedPlanPair.getRight());
        
        // Alternative parent info (if child has other parents)
        String alternativeParentInfo = "NONE";
        if (childHop != null) {
            List<Hop> parents = childHop.getParent();
            for (Hop parent : parents) {
                if (parent.getHopID() != optimalPlan.getHopID()) {
                    // Try to find alt parent's fed plan info
                    String altParentFedType = "UNKNOWN";
                    String altParentRequiredChild = "UNKNOWN";
                    
                    // Check both LOUT and FOUT plans for alt parent
                    FedPlan altParentPlanLOUT = memoTable.getFedPlanAfterPrune(parent.getHopID(), FederatedOutput.LOUT);
                    FedPlan altParentPlanFOUT = memoTable.getFedPlanAfterPrune(parent.getHopID(), FederatedOutput.FOUT);
                    
                    if (altParentPlanLOUT != null) {
                        altParentFedType = "LOUT";
                        // Find what this alt parent expects from child
                        for (Pair<Long, FederatedOutput> altChildPair : altParentPlanLOUT.getChildFedPlans()) {
                            if (altChildPair.getLeft() == childHop.getHopID()) {
                                altParentRequiredChild = altChildPair.getRight().toString();
                                break;
                            }
                        }
                    } else if (altParentPlanFOUT != null) {
                        altParentFedType = "FOUT";
                        // Find what this alt parent expects from child
                        for (Pair<Long, FederatedOutput> altChildPair : altParentPlanFOUT.getChildFedPlans()) {
                            if (altChildPair.getLeft() == childHop.getHopID()) {
                                altParentRequiredChild = altChildPair.getRight().toString();
                                break;
                            }
                        }
                    }
                    
                    alternativeParentInfo = String.format("ID:%d|Name:%s|Op:%s|FedType:%s|RequiredChild:%s", 
                        parent.getHopID(),
                        parent.getName() != null ? parent.getName() : "null",
                        parent.getOpString(),
                        altParentFedType,
                        altParentRequiredChild);
                    break;
                }
            }
        }
        
        System.err.println("[DEBUG] NULL CHILD PLAN DETECTED:");
        System.err.println("  Child:           " + childInfo);
        System.err.println("  Current Parent:  " + currentParentInfo);
        System.err.println("  Alt Parent:      " + alternativeParentInfo);
        System.err.println("  Alt Plan Exists: " + (alternativeChildPlan != null));
    }
    
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
        if (isEmptyFilteredChildHops) {
            System.err.println("[" + logPrefix + "] (hopName: " + hopName + ", hopID: " + hopID + ") filtered child hops is empty");
        }
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
    
    // ========== FederatedMemoTable Printing Methods ==========
    
    /**
     * Recursively prints a tree representation of the DAG starting from the given root FedPlan.
     * Includes information about hopID, fedOutType, TotalCost, SelfCost, and NetCost for each node.
     * Additionally, prints the additional total cost once at the beginning.
     *
     * @param rootFedPlan The starting point FedPlan to print
     * @param rootHopStatSet Set of root hop statistics
     * @param memoTable The memoization table containing FedPlan variants
     * @param additionalTotalCost The additional cost to be printed once
     */
    public static void printFedPlanTree(FederatedMemoTable.FedPlan rootFedPlan, Set<Long> rootHopStatSet,
                                        FederatedMemoTable memoTable, double additionalTotalCost) {
        printFedPlanTree(rootFedPlan, rootHopStatSet, memoTable, additionalTotalCost, false);
    }

    public static void printFedPlanTree(FederatedMemoTable.FedPlan rootFedPlan, Set<Long> rootHopStatSet,
                                        FederatedMemoTable memoTable, double additionalTotalCost, boolean onlyEdge) {
        System.out.println("Additional Cost: " + additionalTotalCost);
        Set<Long> visited = new HashSet<>();
        printFedPlanTreeRecursive(rootFedPlan, memoTable, visited, 0, onlyEdge);

        for (Long hopID : rootHopStatSet) {
            FedPlan plan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
            if (plan == null){
                plan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);
            }
            printNotReferencedFedPlanRecursive(plan, memoTable, visited, 1, onlyEdge);
        }
    }

    /**
     * Helper method to recursively print the FedPlan tree for not referenced plans.
     *
     * @param plan  The current FedPlan to print
     * @param memoTable The memoization table containing FedPlan variants
     * @param visited Set to keep track of visited FedPlans (prevents cycles)
     * @param depth   The current depth level for indentation
     */
    private static void printNotReferencedFedPlanRecursive(FederatedMemoTable.FedPlan plan, FederatedMemoTable memoTable,
                                           Set<Long> visited, int depth, boolean onlyEdge) {
        long hopID = plan.getHopRef().getHopID();

        if (visited.contains(hopID)) {
            return;
        }

        visited.add(hopID);
        printFedPlan(plan, memoTable, depth, true, onlyEdge);

        // Process child nodes
        List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = plan.getChildFedPlans();
        for (int i = 0; i < childFedPlanPairs.size(); i++) {
            Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair = childFedPlanPairs.get(i);
            FederatedMemoTable.FedPlanVariants childVariants = memoTable.getFedPlanVariants(childFedPlanPair);
            if (childVariants == null || childVariants.isEmpty())
                continue;

            for (FederatedMemoTable.FedPlan childPlan : childVariants.getFedPlanVariants()) {
                printNotReferencedFedPlanRecursive(childPlan, memoTable, visited, depth + 1, onlyEdge);
            }
        }
    }

    /**
     * Helper method to recursively print the FedPlan tree.
     *
     * @param plan  The current FedPlan to print
     * @param memoTable The memoization table containing FedPlan variants
     * @param visited Set to keep track of visited FedPlans (prevents cycles)
     * @param depth   The current depth level for indentation
     */
    private static void printFedPlanTreeRecursive(FederatedMemoTable.FedPlan plan, FederatedMemoTable memoTable,
                                           Set<Long> visited, int depth, boolean onlyEdge) {
        long hopID = 0;

        if (depth == 0) {
            hopID = -1;
        } else {
            hopID = plan.getHopRef().getHopID();
        }

        if (visited.contains(hopID)) {
            return;
        }

        visited.add(hopID);
        printFedPlan(plan, memoTable, depth, false, onlyEdge);
        
        // Process child nodes
        List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = plan.getChildFedPlans();
        for (int i = 0; i < childFedPlanPairs.size(); i++) {
            Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair = childFedPlanPairs.get(i);
            FederatedMemoTable.FedPlanVariants childVariants = memoTable.getFedPlanVariants(childFedPlanPair);
            if (childVariants == null || childVariants.isEmpty())
                continue;

            for (FederatedMemoTable.FedPlan childPlan : childVariants.getFedPlanVariants()) {
                printFedPlanTreeRecursive(childPlan, memoTable, visited, depth + 1, onlyEdge);
            }
        }
    }

    /**
     * Prints detailed information about a FedPlan including costs, dimensions, and memory estimates.
     *
     * @param plan The FedPlan to print
     * @param memoTable The memoization table containing FedPlan variants
     * @param depth The current depth level for indentation
     * @param isNotReferenced Whether this plan is not referenced
     */
    private static void printFedPlan(FederatedMemoTable.FedPlan plan, FederatedMemoTable memoTable, int depth, boolean isNotReferenced, boolean onlyEdge) {
        StringBuilder sb = new StringBuilder();
        Hop hop = null;

        if (depth == 0){
            sb.append("[HopID]: ROOT, [Name]: ROOT, [FOutType]: Root");
        } else {
            hop = plan.getHopRef();
            // Add FedPlan information with explicit labels
            sb.append("[HopID]: ").append(hop.getHopID())
                    .append(", [Name]: ").append(hop.getOpString())
                    .append(", [FOutType]: ");

            if (isNotReferenced) {
                if (depth == 1) {
                    sb.append("NRef(TOP)");
                } else {
                    sb.append("NRef");
                }
            } else{
                sb.append(plan.getFedOutType());
            }
        }

        // Add child hop IDs with explicit label
        StringBuilder childHopIDs = new StringBuilder();
        childHopIDs.append(", [ChildHopIDs]: (");

        boolean childAdded = false;
        for (Pair<Long, FederatedOutput> childPair : plan.getChildFedPlans()){
            childHopIDs.append(childAdded ? ", " : "");
            childHopIDs.append(childPair.getLeft());
            childAdded = true;
        }
        
        childHopIDs.append(")");

        if( childAdded )
            sb.append(childHopIDs.toString());
        else
            sb.append(", [ChildHopIDs]: ()");

        // Add parent hop IDs with explicit label
        if (depth > 0) {
            List<Hop> parentHops = hop.getParent();
            StringBuilder parentHopIDs = new StringBuilder();
            parentHopIDs.append(", [ParentHopIDs]: (");
            
            boolean parentAdded = false;
            if (parentHops != null && !parentHops.isEmpty()) {
                for (Hop parentHop : parentHops) {
                    parentHopIDs.append(parentAdded ? ", " : "");
                    parentHopIDs.append(parentHop.getHopID());
                    parentAdded = true;
                }
            }
            
            parentHopIDs.append(")");
            sb.append(parentHopIDs.toString());
        }

        // If onlyEdge is true, print only basic information and return
        if (onlyEdge) {
            System.out.println(sb);
            return;
        }

        if (depth == 0){
            sb.append(", [CostInfo]: {TotalCost: ").append(String.format("%.1f", plan.getCumulativeCost())).append("}");
            System.out.println(sb);
            return;
        }

        // Add cost information with explicit labels
        sb.append(", [CostInfo]: {TotalCost: ").append(String.format("%.1f", plan.getCumulativeCost()))
                .append(", SelfCost: ").append(String.format("%.1f", plan.getSelfCost()))
                .append(", NetworkCost: ").append(String.format("%.1f", plan.getForwardingCost()))
                .append(", ComputeWeight: ").append(String.format("%.1f", plan.getComputeWeight())).append("}");

        // // Add matrix characteristics with explicit labels
        // sb.append(", [MatrixInfo]: {Dimensions: (").append(hop.getDim1()).append("x").append(hop.getDim2())
        //         .append("), Blocksize: ").append(hop.getBlocksize())
        //         .append(", NNZ: ").append(hop.getNnz());

        // if (hop.getUpdateType().isInPlace()) {
        //     sb.append(", UpdateType: ").append(hop.getUpdateType().toString().toLowerCase());
        // }
        // sb.append("}");

        // // Add memory estimates with explicit labels
        // sb.append(", [MemoryInfo]: {InputMem: ").append(OptimizerUtils.toMB(hop.getInputMemEstimate())).append("MB")
        //         .append(", IntermediateMem: ").append(OptimizerUtils.toMB(hop.getIntermediateMemEstimate())).append("MB")
        //         .append(", OutputMem: ").append(OptimizerUtils.toMB(hop.getOutputMemEstimate())).append("MB")
        //         .append(", TotalMem: ").append(OptimizerUtils.toMB(hop.getMemEstimate())).append("MB}");

        // // Add execution requirements with explicit labels
        // StringBuilder execInfo = new StringBuilder();
        // execInfo.append(", [ExecutionInfo]: {");
        
        // if (hop.requiresReblock() && hop.requiresCheckpoint()) {
        //     execInfo.append("RequiresReblock: true, RequiresCheckpoint: true");
        // } else if (hop.requiresReblock()) {
        //     execInfo.append("RequiresReblock: true, RequiresCheckpoint: false");
        // } else if (hop.requiresCheckpoint()) {
        //     execInfo.append("RequiresReblock: false, RequiresCheckpoint: true");
        // } else {
        //     execInfo.append("RequiresReblock: false, RequiresCheckpoint: false");
        // }

        // // Add execution type
        // if (hop.getExecType() != null) {
        //     execInfo.append(", ExecType: ").append(hop.getExecType());
        // }
        
        // execInfo.append("}");
        // sb.append(execInfo.toString());
        
        // if (childAdded){
        //     sb.append(", [EdgeInfo]: {");
        //     boolean firstEdge = true;
        //     for (Pair<Long, FederatedOutput> childPair : plan.getChildFedPlans()){
        //         if (!firstEdge) sb.append(", ");
        //         firstEdge = false;
                
        //         // Add forwarding weight for each edge
        //         FedPlan childPlan = memoTable.getFedPlanAfterPrune(childPair.getLeft(), childPair.getRight());
                
        //         if (childPlan == null) {
        //             sb.append(String.format("Edge(ID:%d, NULL)", childPair.getLeft()));
        //         } else {
        //             String isForwardingCostOccured = "";
        //             double totalForwarding = 0.0;
        //             if (childPair.getRight() == plan.getFedOutType()){
        //                 isForwardingCostOccured = "X";
        //                 totalForwarding = 0.0;
        //             } else {
        //                 isForwardingCostOccured = "O";
        //                 totalForwarding = plan.getChildForwardingWeight(childPlan.getLoopContext()) * childPlan.getForwardingCostPerParents();
        //             }
        //             sb.append(String.format("Edge(ID:%d, ForwardingCost:%s, CumulativeCost:%.1f, ForwardingWeight:%.1f, TotalForwarding:%.1f)", 
        //                         childPair.getLeft(), isForwardingCostOccured, 
        //                         childPlan.getCumulativeCostPerParents(), 
        //                         plan.getChildForwardingWeight(childPlan.getLoopContext()),
        //                         totalForwarding));
        //         }
        //     }
        //     sb.append("}");
        // }

        System.out.println(sb);
    }

    // ===================================================================================
    // Wire UnRefTwrite to LiveOut Logging Methods
    // ===================================================================================

    /**
     * Logs the start of wireUnRefTwriteToLiveOut processing
     * @param unRefTwriteSetSize Number of unRefTwrite hops to process
     */
    public static void logWireUnRefTwriteStart(int unRefTwriteSetSize) {
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
        System.out.println("  ✓ CONNECTED to: " + bestLiveOutHopName + " (Score: " + bestScore + ")");
    }

    /**
     * Logs no compatible connection found
     */
    public static void logNoCompatibleConnection() {
        System.out.println("  ✗ NO COMPATIBLE CONNECTION FOUND");
        System.out.println("  - Falling back to original algorithm...");
    }

    /**
     * Logs fallback connection in wireUnRefTwriteToLiveOut
     * @param liveOutHopName Name of the fallback connection
     */
    public static void logFallbackConnection(String liveOutHopName) {
        System.out.println("  ✓ FALLBACK CONNECTION to: " + liveOutHopName + " (No compatibility check)");
    }

    /**
     * Logs warning for name matching fallback
     * @param unRefTwriteHopName Name of the unRefTwrite hop
     * @param liveOutHopName Name of the liveOut hop
     */
    public static void logNameMatchingFallbackWarning(String unRefTwriteHopName, String liveOutHopName) {
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

	/**
	 * Logs the optimal plan determined by the FederatedPlanMinSTGraph.
	 * This includes Hop ID, ExecType, FedOutputType, Privacy, FType, OpCost, and Network Costs.
	 *
	 * @param planGraph The FederatedPlanMinSTGraph after getOptimalPlan has been executed.
	 */
	public static void logOptimalPlan(FederatedPlanMinSTGraph planGraph) {
		logOptimalPlan(planGraph, false);
	}

	/**
	 * Logs the optimal plan with optional debug information.
	 *
	 * @param planGraph The FederatedPlanMinSTGraph after getOptimalPlan has been executed.
	 * @param debug If true, prints additional debug information including all graph edges.
	 */
	public static void logOptimalPlan(FederatedPlanMinSTGraph planGraph, boolean debug) {
		System.out.println("\n[Optimal Federated Plan]");
		System.out.println("--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");
		System.out.printf("%-7s | %-12s | %-20s | %-10s | %-13s | %-8s | %-9s | %-15s | %-15s | %-10s | %s%n",
			"Hop ID", "Type", "OpCode", "ExecType", "FedOutputType", "Privacy", "FType", "ChildIDs", "ParentIDs", "OpCost", "Network Costs (Child -> Cost)");
		System.out.println("--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");

		Graph<Long, DefaultWeightedEdge> graph = planGraph.getGraph();
		Map<Long, FederatedPlanMinSTGraph.Vertex> memoTable = planGraph.getMemoTable();
		long sourceId = -1L; // planGraph.leafedSource
		long sinkId = -2L;   // planGraph.rootLocalSink

		// Sort hop IDs for consistent output
		List<Long> hopIds = new ArrayList<>(memoTable.keySet());
		Collections.sort(hopIds);

		for (Long hopID : hopIds) {
			FederatedPlanMinSTGraph.Vertex vertex = memoTable.get(hopID);
			if (vertex == null) continue;

			Hop hop = vertex.getHopRef();
			String hopType = hop.getClass().getSimpleName();
			// Remove "Op" suffix from type
			if (hopType.endsWith("Op")) {
				hopType = hopType.substring(0, hopType.length() - 2);
			}
			if (hopType.length() > 12) {
				hopType = hopType.substring(0, 12);
			}

			String opCode = hop.getOpString();
			// Remove type name prefix from opCode if it exists
			String typePrefix = hopType.toLowerCase();
			if (opCode.toLowerCase().startsWith(typePrefix)) {
				opCode = opCode.substring(typePrefix.length()).trim();
			}
			if (opCode.length() > 20) {
				opCode = opCode.substring(0, 20);
			}

			// Get child IDs
			StringBuilder childIDs = new StringBuilder();
			if (hop.getInput() != null && !hop.getInput().isEmpty()) {
				for (int i = 0; i < hop.getInput().size(); i++) {
					if (i > 0) childIDs.append(",");
					childIDs.append(hop.getInput().get(i).getHopID());
				}
			}
			String childIDsStr = childIDs.length() > 0 ? childIDs.toString() : "-";
			if (childIDsStr.length() > 15) {
				childIDsStr = childIDsStr.substring(0, 15);
			}

			// Get parent IDs
			StringBuilder parentIDs = new StringBuilder();
			if (hop.getParent() != null && !hop.getParent().isEmpty()) {
				for (int i = 0; i < hop.getParent().size(); i++) {
					if (i > 0) parentIDs.append(",");
					long parentID = hop.getParent().get(i).getHopID();
					parentIDs.append(parentID);
					// TODO: VERIFY - Debug missing parent hops
					if (!memoTable.containsKey(parentID)) {
						System.err.println("[WARN] Hop " + hopID + " has parent " + parentID + " which is NOT in memoTable!");
					}
				}
			}
			String parentIDsStr = parentIDs.length() > 0 ? parentIDs.toString() : "-";
			if (parentIDsStr.length() > 15) {
				parentIDsStr = parentIDsStr.substring(0, 15);
			}

			String execType = hop.getForcedExecType() != null ? hop.getForcedExecType().name() : "N/A";
			String fedOut = hop.getFederatedOutput() != null ? hop.getFederatedOutput().name() : "N/A";
			String privacy = vertex.getPrivacy() != null ? getPrivacyAbbreviation(vertex.getPrivacy().name()) : "N/A";
			String fType = vertex.getDataType() != null ? vertex.getDataType().name() : "N/A";

			// Determine OpCost based on ExecType
			double opCost = 0;
			if (hop.getForcedExecType() == ExecType.FED) {
				DefaultWeightedEdge edge = graph.getEdge(hopID, sinkId);
				if (edge != null) {
					opCost = graph.getEdgeWeight(edge);
				}
			} else if (hop.getForcedExecType() == ExecType.CP) {
				DefaultWeightedEdge edge = graph.getEdge(sourceId, hopID);
				if (edge != null) {
					opCost = graph.getEdgeWeight(edge);
				}
			}

			// Get Network Costs with child hops
			// Network cost occurs when there's an execution type mismatch between child and parent
			StringBuilder networkCostsStr = new StringBuilder();
			if (hop.getInput() != null && !hop.getInput().isEmpty()) {
				for (Hop child : hop.getInput()) {
					long childID = child.getHopID();
					if (!memoTable.containsKey(childID)) continue;

					ExecType childExecType = child.getForcedExecType();
					ExecType parentExecType = hop.getForcedExecType();

					double networkCost = 0;

					// Network cost only occurs when there's a mismatch
					// Case 1: Child=FED, Parent=CP -> edge from child to parent (child -> parent)
					// Case 2: Child=CP, Parent=FED -> edge from parent to child (parent -> child)
					if (childExecType == ExecType.FED && parentExecType == ExecType.CP) {
						DefaultWeightedEdge edge = graph.getEdge(childID, hopID);
						if (edge != null) {
							networkCost = graph.getEdgeWeight(edge);
						}
					} else if (childExecType == ExecType.CP && parentExecType == ExecType.FED) {
						DefaultWeightedEdge edge = graph.getEdge(hopID, childID);
						if (edge != null) {
							networkCost = graph.getEdgeWeight(edge);
						}
					}

					if (networkCost > 0) {
						if (networkCostsStr.length() > 0) networkCostsStr.append(", ");
						networkCostsStr.append(String.format("%d -> %.1f", childID, networkCost));
					}
				}
			}

			System.out.printf("%-7d | %-12s | %-20s | %-10s | %-13s | %-8s | %-9s | %-15s | %-15s | %-10.1f | %s%n",
				hopID, hopType, opCode, execType, fedOut, privacy, fType, childIDsStr, parentIDsStr, opCost, networkCostsStr.toString());
		}
		System.out.println("--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");
	}
}