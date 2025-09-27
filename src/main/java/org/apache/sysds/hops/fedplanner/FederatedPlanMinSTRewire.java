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

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FederatedTypePropagator;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.*;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.InitFEDInstruction;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.apache.sysds.hops.fedplanner.FederatedPlanMinSTGraph.Vertex;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Future;

public class FederatedPlanMinSTRewire {
    
    private static final double DEFAULT_LOOP_WEIGHT = 10.0;
    private static final double DEFAULT_IF_ELSE_WEIGHT = 0.5;

    public static final String FED_MATRIX_IDENTIFIER = "matrix";
    public static final String FED_FRAME_IDENTIFIER = "frame";

    public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
            Map<Long, Vertex> vertexMemoTable, List<Pair<FederatedRange, FederatedData>> fedMap, 
            Set<Long> unRefTwriteSet, Set<Long> unRefSet, Set<Hop> progRootHopSet) {
        // Maps Hop ID and fedOutType pairs to their plan variants
        Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
        Set<Long> visitedHops = new HashSet<>();
        Set<String> fnStack = new HashSet<>();
        List<Pair<Long, Double>> loopStack = new ArrayList<>();

        List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
        Map<String, List<Hop>> outerTransTable = new HashMap<>();
        outerTransTableList.add(outerTransTable);

        for (StatementBlock sb : prog.getStatementBlocks()) {
            Map<String, List<Hop>> innerTransTable = rewireStatementBlock(sb, prog, visitedHops, rewireTable,
                    vertexMemoTable, outerTransTableList, null, privacyConstraintMap, fTypeMap,
                    fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, 1, 1, loopStack);
            outerTransTableList.get(0).putAll(innerTransTable);
        }
    }

    public static void rewireFunctionDynamic(FunctionStatementBlock function, Map<Long, List<Hop>> rewireTable,
            Map<Long, Vertex> vertexMemoTable, List<Pair<FederatedRange, FederatedData>> fedMap, 
            Set<Long> unRefTwriteSet, Set<Long> unRefSet, Set<Hop> progRootHopSet) {
        Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
        Set<Long> visitedHops = new HashSet<>();
        Set<String> fnStack = new HashSet<>();
        List<Pair<Long, Double>> loopStack = new ArrayList<>();
        List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
        Map<String, List<Hop>> outerTransTable = new HashMap<>();
        outerTransTableList.add(outerTransTable);
        // Todo (Future): not tested & not used
        rewireStatementBlock(function, null, visitedHops, rewireTable, vertexMemoTable, outerTransTableList, null,
                privacyConstraintMap, fTypeMap,
                fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, 1, 1, loopStack);
    }

    public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog, Set<Long> visitedHops,
            Map<Long, List<Hop>> rewireTable, Map<Long, Vertex> vertexMemoTable,
            List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
            Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
            List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
            Set<Hop> progRootHopSet, Set<String> fnStack,
            double computeWeight, double networkWeight, List<Pair<Long, Double>> parentLoopStack) {
        List<Map<String, List<Hop>>> newOuterTransTableList = new ArrayList<>();
        if (outerTransTableList != null) {
            for (Map<String, List<Hop>> outerTable : outerTransTableList) {
                if (outerTable != null && !outerTable.isEmpty()) {
                    newOuterTransTableList.add(outerTable);
                }
            }
        }
        if (formerTransTable != null && !formerTransTable.isEmpty()) {
            newOuterTransTableList.add(formerTransTable);
        }

        Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
        Map<String, List<Hop>> innerTransTable = new HashMap<>();

        if (sb instanceof IfStatementBlock) {
            IfStatementBlock isb = (IfStatementBlock) sb;
            IfStatement istmt = (IfStatement) isb.getStatement(0);

            rewireHopDAG(isb.getPredicateHops(), prog, visitedHops, rewireTable, vertexMemoTable, newOuterTransTableList,
                    null, innerTransTable,
                    privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                    networkWeight, parentLoopStack);

            newFormerTransTable.putAll(innerTransTable);
            Map<String, List<Hop>> elseFormerTransTable = new HashMap<>();
            elseFormerTransTable.putAll(innerTransTable);
            computeWeight *= DEFAULT_IF_ELSE_WEIGHT;

            for (StatementBlock innerIsb : istmt.getIfBody())
                newFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
                        vertexMemoTable, newOuterTransTableList, newFormerTransTable,
                        privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                        networkWeight, parentLoopStack));

            for (StatementBlock innerIsb : istmt.getElseBody())
                elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
                        vertexMemoTable, newOuterTransTableList, elseFormerTransTable,
                        privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                        networkWeight, parentLoopStack));

            // If there are common keys: merge elseValue list into ifValue list
            elseFormerTransTable.forEach((key, elseValue) -> {
                newFormerTransTable.merge(key, elseValue, (ifValue, newValue) -> {
                    ifValue.addAll(newValue);
                    return ifValue;
                });
            });
        } else if (sb instanceof ForStatementBlock) { // incl parfor
            ForStatementBlock fsb = (ForStatementBlock) sb;
            ForStatement fstmt = (ForStatement) fsb.getStatement(0);

            // Calculate for-loop iteration count if possible
            double loopWeight = DEFAULT_LOOP_WEIGHT;
            Hop from = fsb.getFromHops().getInput().get(0);
            Hop to = fsb.getToHops().getInput().get(0);
            Hop incr = (fsb.getIncrementHops() != null) ? fsb.getIncrementHops().getInput().get(0) : new LiteralOp(1);

            // Calculate for-loop iteration count (weight) if from, to, and incr are literal
            // ops (constant values)
            if (from instanceof LiteralOp && to instanceof LiteralOp && incr instanceof LiteralOp) {
                double dfrom = HopRewriteUtils.getDoubleValue((LiteralOp) from);
                double dto = HopRewriteUtils.getDoubleValue((LiteralOp) to);
                double dincr = HopRewriteUtils.getDoubleValue((LiteralOp) incr);
                if (dfrom > dto && dincr == 1)
                    dincr = -1;
                loopWeight = UtilFunctions.getSeqLength(dfrom, dto, dincr, false);
            }
            computeWeight *= loopWeight;
            networkWeight *= loopWeight;

            // Create current loop context (copy parent context)
            List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
            currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

            rewireHopDAG(fsb.getFromHops(), prog, visitedHops, rewireTable, vertexMemoTable, newOuterTransTableList,
                    null, innerTransTable,
                    privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                    networkWeight, currentLoopStack);
            rewireHopDAG(fsb.getToHops(), prog, visitedHops, rewireTable, vertexMemoTable, newOuterTransTableList, null,
                    innerTransTable,
                    privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                    networkWeight, currentLoopStack);

            if (fsb.getIncrementHops() != null) {
                rewireHopDAG(fsb.getIncrementHops(), prog, visitedHops, rewireTable, vertexMemoTable,
                        newOuterTransTableList, null, innerTransTable,
                        privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                        networkWeight, currentLoopStack);
            }
            newFormerTransTable.putAll(innerTransTable);

            for (StatementBlock innerFsb : fstmt.getBody())
                newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
                        vertexMemoTable, newOuterTransTableList, newFormerTransTable,
                        privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                        networkWeight, currentLoopStack));

            // Wire UnRefTwrite to liveOutHops
            wireUnRefTwriteToLiveOut(fsb, unRefTwriteSet, vertexMemoTable, newFormerTransTable, fTypeMap);
        } else if (sb instanceof WhileStatementBlock) {
            WhileStatementBlock wsb = (WhileStatementBlock) sb;
            WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

            computeWeight *= DEFAULT_LOOP_WEIGHT;
            networkWeight *= DEFAULT_LOOP_WEIGHT;

            // Create current loop context (copy parent context)
            List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
            currentLoopStack.add(Pair.of(sb.getSBID(), DEFAULT_LOOP_WEIGHT));

            rewireHopDAG(wsb.getPredicateHops(), prog, visitedHops, rewireTable, vertexMemoTable, newOuterTransTableList,
                    null, innerTransTable,
                    privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                    networkWeight, currentLoopStack);
            newFormerTransTable.putAll(innerTransTable);

            for (StatementBlock innerWsb : wstmt.getBody())
                newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
                        vertexMemoTable, newOuterTransTableList, newFormerTransTable,
                        privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                        networkWeight, currentLoopStack));

            // Wire UnRefTwrite to liveOutHops
            wireUnRefTwriteToLiveOut(wsb, unRefTwriteSet, vertexMemoTable, newFormerTransTable, fTypeMap);
        } else if (sb instanceof FunctionStatementBlock) {
            FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
            FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

            for (StatementBlock innerFsb : fstmt.getBody())
                newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
                        vertexMemoTable, newOuterTransTableList, newFormerTransTable,
                        privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
                        networkWeight, parentLoopStack));

            // Wire fcall operation to liveOutHops
            wireUnRefTwriteToLiveOut(fsb, unRefTwriteSet, vertexMemoTable, newFormerTransTable, fTypeMap);
        } else { // generic (last-level)
            if (sb.getHops() != null) {
                for (Hop c : sb.getHops())
                    rewireHopDAG(c, prog, visitedHops, rewireTable, vertexMemoTable, newOuterTransTableList, null,
                            innerTransTable,
                            privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
                            computeWeight, networkWeight, parentLoopStack);
            }

            return innerTransTable;
        }
        return newFormerTransTable;
    }

    private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops, Map<Long, List<Hop>> rewireTable,
            Map<Long, Vertex> vertexMemoTable, List<Map<String, List<Hop>>> outerTransTableList,
            Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
            Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
            List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
            Set<Hop> progRootHopSet,
            Set<String> fnStack, double computeWeight, double networkWeight, List<Pair<Long, Double>> loopStack) {

        if (hop.getInput() != null) {
            for (Hop inputHop : hop.getInput()) {
                long inputHopID = inputHop.getHopID();
                if (!visitedHops.contains(inputHopID)) {
                    visitedHops.add(inputHopID);
                    rewireHopDAG(inputHop, prog, visitedHops, rewireTable, vertexMemoTable, outerTransTableList,
                            formerTransTable, innerTransTable,
                            privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
                            computeWeight, networkWeight, loopStack);
                }
            }
        }

        // Identify hops to connect to the root dummy node
        // Connect TWrite pred and u(print) to the root dummy node
        if ((hop instanceof DataOp && (hop.getName().equals("__pred"))) // TWrite "__pred"
                || (hop instanceof UnaryOp && ((UnaryOp) hop).getOp() == OpOp1.PRINT) // u(print)
                || (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.PERSISTENTWRITE)) { // PWrite
            progRootHopSet.add(hop);
        } else if (!(hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
                && hop.getParent().size() == 0) {
            unRefSet.add(hop.getHopID());
        }

        if (hop instanceof FunctionOp) {
            // maintain counters and investigate functions if not seen so far
            FunctionOp fop = (FunctionOp) hop;
            unRefTwriteSet.add(fop.getHopID());

            if (fop.getFunctionType() == FunctionType.DML) {
                String fkey = fop.getFunctionKey();

                if (!fnStack.contains(fkey)) {
                    fnStack.add(fkey);
                    FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(),
                            fop.getFunctionName());

                    Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
                    if (formerTransTable != null) {
                        newFormerTransTable.putAll(formerTransTable);
                    }
                    newFormerTransTable.putAll(innerTransTable);

                    String[] inputArgs = fop.getInputVariableNames();
                    List<Hop> inputHops = fop.getInput();

                    // Only used outside of functionTransTable.
                    for (int i = 0; i < inputHops.size(); i++) {
                        newFormerTransTable.computeIfAbsent(inputArgs[i], k -> new ArrayList<>()).add(inputHops.get(i));
                    }

                    Map<String, List<Hop>> functionTransTable = rewireStatementBlock(fsb, prog, visitedHops,
                            rewireTable, vertexMemoTable, outerTransTableList, newFormerTransTable,
                            privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
                            computeWeight, networkWeight, loopStack);

                    for (int i = 0; i < fop.getOutputVariableNames().length; i++) {
                        String tWriteName = fop.getOutputVariableNames()[i];
                        List<Hop> outputHops = functionTransTable.get(fsb.getOutputsofSB().get(i).getName());
                        innerTransTable.computeIfAbsent(tWriteName, k -> new ArrayList<>()).addAll(outputHops);
                        for (Hop outputHop : outputHops) {
                            unRefTwriteSet.add(outputHop.getHopID());
                        }
                    }
                }
            }
        }

        Vertex vertex = rewireHop(hop, rewireTable, outerTransTableList, formerTransTable, innerTransTable, privacyConstraintMap,
                vertexMemoTable, fTypeMap, fedMap, unRefTwriteSet);
        vertex.setMetadata(computeWeight, networkWeight, loopStack);
        vertexMemoTable.put(hop.getHopID(), vertex);
    }

    private static Vertex rewireHop(Hop hop, Map<Long, List<Hop>> rewireTable,
            List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
            Map<String, List<Hop>> innerTransTable, Map<Long, Privacy> privacyConstraintMap, Map<Long, Vertex> vertexMemoTable,
            Map<Long, FType> fTypeMap, List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet) {
        Privacy privacy = null;
        FType fType = null;

        if (!(hop instanceof DataOp)) {
            privacy = FederatedTypePropagator.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
            fType = FederatedTypePropagator.getFederatedType(hop, fTypeMap);
            privacyConstraintMap.put(hop.getHopID(), privacy);
            fTypeMap.put(hop.getHopID(), fType);
            return new Vertex(hop, privacy, fType);
        }

        DataOp dataOp = (DataOp) hop;
        Types.OpOpData opType = dataOp.getOp();
        String hopName = dataOp.getName();
        
        if (opType == Types.OpOpData.FEDERATED) {
            privacy = getFedWorkerMetaData(fedMap, dataOp);
            fType = FederatedTypePropagator.deriveFType((DataOp)hop);
            // Debug logging for FEDERATED operation
            FederatedPlannerLogger.logDataOpFTypeDebug(hop, fType, "FEDERATED", "Derived from partition ranges");
        } else if (opType == Types.OpOpData.TRANSIENTWRITE) {
            // Rewire TransWrite
            innerTransTable.computeIfAbsent(hopName, k -> new ArrayList<>()).add(hop);
            unRefTwriteSet.add(hop.getHopID());
            // Propagate Privacy Constraint
            privacy = FederatedTypePropagator.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
            fType = fTypeMap.get(hop.getInput(0).getHopID());
            // Propagate FType (TransWrite has only one input)
            // Debug logging for TRANSIENTWRITE operation
            FederatedPlannerLogger.logDataOpFTypeDebug(hop, fType, "TRANSIENTWRITE", 
                "Propagated from single input (HopID: " + hop.getInput(0).getHopID() + ")");
        } else if (opType == Types.OpOpData.TRANSIENTREAD) {
            // Rewire TransRead
            List<Hop> childHops = rewireTransRead(hopName, innerTransTable, formerTransTable, outerTransTableList);
            // Handle rewire table (TransRead -> TransWrite)
            rewireTable.put(hop.getHopID(), childHops);

            // Todo: Handle exception when TRead has no Child (check why it's missing)
            if (childHops == null || childHops.isEmpty()) {
                FederatedPlannerLogger.logTransReadRewireDebug(hopName, hop.getHopID(), childHops, true, "RewireTransHop");
                return null;
            }

            // Remove childHops that have different hopVarName
            List<Hop> filteredChildHops = new ArrayList<>();
            for (Hop childHop : childHops) {
                String hopVarName = hop.getName();

                if (hopVarName.equals(childHop.getName())) {
                    filteredChildHops.add(childHop);
                }
            }

            // Todo: Handle exception when TRead has no Filtered Child (check why it's missing)
            if (filteredChildHops.isEmpty()) {
                FederatedPlannerLogger.logFilteredChildHopsDebug(hopName, hop.getHopID(), filteredChildHops, true, "RewireTransHop");
                return null;
            }

            fType = null;
            StringBuilder debugInfo = new StringBuilder();
            for (int i = 0; i < filteredChildHops.size(); i++) {
                Hop filteredChildHop = filteredChildHops.get(i);
                long filteredChildHopID = filteredChildHop.getHopID();
                FType childFType = fTypeMap.get(filteredChildHopID);

                // Rewire (TransWrite -> TransRead)
                rewireTable.computeIfAbsent(filteredChildHopID, k -> new ArrayList<>()).add(hop);
                // Remove refTWrite from unRefTwriteSet
                unRefTwriteSet.remove(filteredChildHopID);

                // Check FType consistency of childs(TransWrite)
                if ( i==0 ) {
                    fType = childFType;
                    debugInfo.append("First child HopID: ").append(filteredChildHopID).append(" FType: ").append(childFType);
                } else if (fType != childFType) {
                    // Todo: Handle exception when TRead has different FType
                    FType mismatchedFType = childFType;
                    FederatedPlannerLogger.logFTypeMismatchError(hop, filteredChildHops, fTypeMap, fType, mismatchedFType, i);
                    
                    debugInfo.append(", Child ").append(i).append(" HopID: ").append(filteredChildHopID)
                             .append(" FType: ").append(mismatchedFType).append(" (MISMATCH)");
                    
                    // Prioritize non-null FType when there's a mismatch
                    if (fType == null && mismatchedFType != null) {
                        fType = mismatchedFType;
                    } else if (fType != null && mismatchedFType == null) {
                        // Keep inputFType (already non-null)
                    }
                    // throw new DMLRuntimeException("TransRead input FType mismatch: " + inputFType + " != " + mismatchedFType);
                } else {
                    debugInfo.append(", Child ").append(i).append(" HopID: ").append(filteredChildHopID)
                             .append(" FType: ").append(childFType).append(" (MATCH)");
                }
            }
            // Propagate Privacy Constraint
            privacy = FederatedTypePropagator.getPrivacyConstraint(hop, filteredChildHops, privacyConstraintMap);
            // Debug logging for TRANSIENTREAD operation
            FederatedPlannerLogger.logDataOpFTypeDebug(hop, fType, "TRANSIENTREAD", 
                "Propagated from " + filteredChildHops.size() + " child(s): " + debugInfo.toString());
        } else {
            privacy = FederatedTypePropagator.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
            fType = FederatedTypePropagator.getFederatedType(hop, fTypeMap);
        }

        privacyConstraintMap.put(hop.getHopID(), privacy);
        fTypeMap.put(hop.getHopID(), fType);

        return new Vertex(hop, privacy, fType);
    }

    private static List<Hop> rewireTransRead(String hopName, Map<String, List<Hop>> innerTransTable,
            Map<String, List<Hop>> formerTransTable, List<Map<String, List<Hop>>> outerTransTableList) {
        List<Hop> childHops = new ArrayList<>();

        // Read according to priority: inner -> former -> outer
        if (!innerTransTable.isEmpty()) {
            childHops = innerTransTable.get(hopName);
        }

        if ((childHops == null || childHops.isEmpty()) && formerTransTable != null) {
            childHops = formerTransTable.get(hopName);
        }

        if (childHops == null || childHops.isEmpty()) {
            // Traverse in reverse order from the last inserted outerTransTable
            for (int i = outerTransTableList.size() - 1; i >= 0; i--) {
                Map<String, List<Hop>> outerTransTable = outerTransTableList.get(i);
                childHops = outerTransTable.get(hopName);
                if (childHops != null && !childHops.isEmpty())
                    break;
            }
        }

        return childHops;
    }

    private static Privacy getFedWorkerMetaData(List<Pair<FederatedRange, FederatedData>> fedMap, DataOp initFedOp) {
        // Address
        Hop addressListHop = initFedOp.getInput(initFedOp.getParameterIndex("addresses"));
        List<String> addressList = new ArrayList<>();
        for (Hop addressHop : addressListHop.getInput()) {
            addressList.add(addressHop.getName());
        }

        // Range
        Hop rangeListHop = initFedOp.getInput(initFedOp.getParameterIndex("ranges"));
        List<long[]> rangeList = new ArrayList<>();
        for (Hop rangeHop : rangeListHop.getInput()) {
            long beginRange = (long) Double.parseDouble(rangeHop.getInput(0).getName());
            long endRange = (long) Double.parseDouble(rangeHop.getInput(1).getName());
            rangeList.add(new long[] { beginRange, endRange });
        }

        // Type
        String type = initFedOp.getInput(initFedOp.getParameterIndex("type")).getName();
        DataType fedDataType;

        if (type.equalsIgnoreCase(FED_MATRIX_IDENTIFIER))
            fedDataType = DataType.MATRIX;
        else
            fedDataType = DataType.FRAME;

        // Init Fed Data
        for (int i = 0; i < addressList.size(); i++) {
            String address = addressList.get(i);
            // We split address into url/ip, the port and file path of file to read
            String[] parsedValues = InitFEDInstruction.parseURL(address);
            String host = parsedValues[0];
            int port = Integer.parseInt(parsedValues[1]);
            String filePath = parsedValues[2];

            long[] beginRange = rangeList.get(2 * i);
            long[] endRange = rangeList.get(2 * i + 1);

            try {
                FederatedData federatedData = new FederatedData(fedDataType,
                        new InetSocketAddress(InetAddress.getByName(host), port), filePath);
                fedMap.add(new ImmutablePair<>(new FederatedRange(beginRange, endRange), federatedData));
            } catch (UnknownHostException e) {
                throw new RuntimeException("federated host was unknown: " + host, e);
            }
        }
        Privacy privacyConstraint = null;

        // Request Privacy Constraints
        for (Pair<FederatedRange, FederatedData> fed : fedMap) {
            FederatedData data = fed.getRight();
            if (!data.isInitialized())
                data.initFederatedData(FederationUtils.getNextFedDataID());

            Future<FederatedResponse> future = data.requestPrivacyConstraints();
            try {
                FederatedResponse response = future.get(); // Get actual response from Future

                if (response.isSuccessful()) {
                    Object[] responseData = response.getData();
                    String privacyConstraints = (String) responseData[0]; // Cast privacy constraint as string
                    Privacy tempPrivacy = null;
                    
                    if (privacyConstraints != null) {
                        String pcLower = privacyConstraints.trim().toLowerCase();

                        // Map to appropriate PrivacyConstraint value based on input string
                        if (pcLower.equals("private")
                                || pcLower.equals(Privacy.PRIVATE.toString().toLowerCase())) {
                            tempPrivacy = Privacy.PRIVATE;
                        } else if (pcLower.equals("private-aggregate") || pcLower.equals("private_aggregate") ||
                                pcLower.equals(Privacy.PRIVATE_AGGREGATE.toString().toLowerCase())) {
                            tempPrivacy = Privacy.PRIVATE_AGGREGATE;
                        } else if (pcLower.equals("public")
                                || pcLower.equals(Privacy.PUBLIC.toString().toLowerCase())) {
                            tempPrivacy = Privacy.PUBLIC;
                        } else {
                            throw new DMLRuntimeException("Invalid privacy constraint: " + privacyConstraints +
                                    ". Must be one of 'PRIVATE', 'PRIVATE_AGGREGATE', 'PUBLIC'.");
                        }
                    }

                    if (privacyConstraint == null) {
                        privacyConstraint = tempPrivacy;
                    } else {
                        if (privacyConstraint != tempPrivacy) {
                            throw new DMLRuntimeException("Privacy constraints do not match.");
                        }
                    }
                } else {
                    // Error handling
                    String errorMsg = response.getErrorMessage();
                    System.err.println("Failed to request privacy constraints: " + errorMsg);
                }
            } catch (Exception e) {
                // Exception handling
                e.printStackTrace();
            }
        }
        return privacyConstraint;
    }

    private static void wireUnRefTwriteToLiveOut(StatementBlock sb, Set<Long> unRefTwriteSet,
            Map<Long, Vertex> vertexMemoTable, Map<String, List<Hop>> newFormerTransTable, 
            Map<Long, FType> fTypeMap) {
        if (unRefTwriteSet.isEmpty())
            return;

        VariableSet genHops = sb.getGen();
        VariableSet updatedHops = sb.variablesUpdated();
        VariableSet liveOutHops = sb.liveOut();

//        FederatedPlannerLogger.logWireUnRefTwriteStart(unRefTwriteSet.size());

        Iterator<Long> unRefTwriteIterator = unRefTwriteSet.iterator();
        while (unRefTwriteIterator.hasNext()) {
            Long unRefTwriteHopID = unRefTwriteIterator.next();
            Hop unRefTwriteHop = vertexMemoTable.get(unRefTwriteHopID).getHopRef();
            String unRefTwriteHopName = unRefTwriteHop.getName();

            if (liveOutHops.containsVariable(unRefTwriteHopName)) {
                continue;
            }

            if (unRefTwriteHop instanceof FunctionOp || genHops.containsVariable(unRefTwriteHopName) || updatedHops.containsVariable(unRefTwriteHopName)) {
//                FederatedPlannerLogger.logProcessingUnRefTwriteHop(unRefTwriteHopName, unRefTwriteHopID,
//                                                                 unRefTwriteHop, getHopFType(unRefTwriteHop, fTypeMap));

                String bestLiveOutHopName = null;
                int bestPriority = Integer.MAX_VALUE;
                List<String> candidateInfo = new ArrayList<>();

                Iterator<String> liveOutHopsIterator = liveOutHops.getVariableNames().iterator();
                while (liveOutHopsIterator.hasNext()) {
                    String liveOutHopName = liveOutHopsIterator.next();
                    List<Hop> liveOutHopsList = newFormerTransTable.get(liveOutHopName);

                    if (liveOutHopsList != null && !liveOutHopsList.isEmpty()) {
                        Hop representativeLiveOutHop = liveOutHopsList.get(0);
                        
                        // 새로운 호환성 우선순위 점수 계산
                        CompatibilityResult compatResult = calculateCompatibilityScore(unRefTwriteHop, representativeLiveOutHop, fTypeMap, vertexMemoTable);
                        
                        FType unRefTwriteFType = getHopFType(unRefTwriteHop, fTypeMap);
                        FType liveOutFType = getHopFType(representativeLiveOutHop, fTypeMap);
                        
                        String candidateMsg = FederatedPlannerLogger.createCandidateInfo(liveOutHopName, 
                                                                                      representativeLiveOutHop, liveOutFType,
                                                                                      compatResult.priority, compatResult.score,
                                                                                      compatResult.isCompatible, compatResult.reason);
                        candidateInfo.add(candidateMsg);
                        
                        if (compatResult.isCompatible && compatResult.score < bestPriority) {
                            bestPriority = compatResult.score;
                            bestLiveOutHopName = liveOutHopName;
                        }
                    }
                }

                // 후보 정보 출력
                FederatedPlannerLogger.logCandidateInfo(candidateInfo);

                // 연결 결과 출력
                if (bestLiveOutHopName != null) {
                    FederatedPlannerLogger.logSuccessfulConnection(bestLiveOutHopName, bestPriority);
                    List<Hop> bestLiveOutHopsList = newFormerTransTable.get(bestLiveOutHopName);
                    List<Hop> copyLiveOutHopsList = new ArrayList<>(bestLiveOutHopsList);
                    copyLiveOutHopsList.add(unRefTwriteHop);
                    newFormerTransTable.put(bestLiveOutHopName, copyLiveOutHopsList);
                    unRefTwriteIterator.remove();
                } else {
                    FederatedPlannerLogger.logNoCompatibleConnection();
                    
                    // 원본 알고리즘 실행 (타입 체크 없이)
                    boolean isRewired = false;
                    Iterator<String> fallbackIterator = liveOutHops.getVariableNames().iterator();
                    while (fallbackIterator.hasNext()) {
                        String liveOutHopName = fallbackIterator.next();
                        List<Hop> liveOutHopsList = newFormerTransTable.get(liveOutHopName);

                        if (liveOutHopsList != null && !liveOutHopsList.isEmpty()) {
                            FederatedPlannerLogger.logFallbackConnection(liveOutHopName);
                            List<Hop> copyLiveOutHopsList = new ArrayList<>(liveOutHopsList);
                            copyLiveOutHopsList.add(unRefTwriteHop);
                            newFormerTransTable.put(liveOutHopName, copyLiveOutHopsList);
                            unRefTwriteIterator.remove();
                            isRewired = true;
                            break;
                        }
                    }
                    if (!isRewired) {
                        throw new RuntimeException("No liveOutHops found for " + unRefTwriteHopName);
                    }
                }
            }
        }
    }

    // 호환성 결과를 담는 클래스
    private static class CompatibilityResult {
        boolean isCompatible;
        int priority;
        int score;
        String reason;
        
        CompatibilityResult(boolean isCompatible, int priority, int score, String reason) {
            this.isCompatible = isCompatible;
            this.priority = priority;
            this.score = score;
            this.reason = reason;
        }
    }

    private static CompatibilityResult calculateCompatibilityScore(Hop unRefTwriteHop, Hop liveOutHop, 
                                                                  Map<Long, FType> fTypeMap, Map<Long, Vertex> vertexMemoTable) {
        FType unRefTwriteFType = getHopFType(unRefTwriteHop, fTypeMap);
        FType liveOutFType = getHopFType(liveOutHop, fTypeMap);
        
        boolean sameDataType = unRefTwriteHop.getDataType() == liveOutHop.getDataType() && 
                              unRefTwriteHop.getValueType() == liveOutHop.getValueType();
        boolean sameFType = (unRefTwriteFType == liveOutFType);
        boolean sameDimensions = unRefTwriteHop.getDim1() == liveOutHop.getDim1() && 
                                unRefTwriteHop.getDim2() == liveOutHop.getDim2();
        
        // 1순위: 데이터 타입 동일 & FType 동일
        if (sameDataType && sameFType) {
            return new CompatibilityResult(true, 1, 100, "Perfect match: same data type and FType");
        }
        
        // 2순위: 데이터 타입 동일 & FType 다름
        if (sameDataType) {
            // null이 아니게 동일하면 좋고, null이랑 나머지는 안돼
            if (unRefTwriteFType != null && liveOutFType != null) {
                if (areCompatibleFederationTypes(unRefTwriteFType, liveOutFType)) {
                    return new CompatibilityResult(true, 2, 200, "Same data type, compatible FTypes");
                } else {
                    return new CompatibilityResult(false, 2, Integer.MAX_VALUE, "Same data type, incompatible FTypes");
                }
            } else if (unRefTwriteFType == null || liveOutFType == null) {
                return new CompatibilityResult(false, 2, Integer.MAX_VALUE, "FType mismatch: null vs non-null");
            }
        }
        
        // 3순위: 차원 유사성
        if (sameDataType || sameDimensions) {
            double dimSimilarity = calculateDimensionSimilarity(unRefTwriteHop, liveOutHop);
            int dimScore = 1000 - (int)(dimSimilarity * 100);
            return new CompatibilityResult(true, 3, dimScore, "Dimension similarity: " + dimSimilarity);
        }
        
        // 4순위: 공통 Child 메모리 추정치
        double commonChildMemEstimate = findCommonChildrenMemEstimate(unRefTwriteHop, liveOutHop, vertexMemoTable);
        if (commonChildMemEstimate > 0) {
            int childScore = 10000 - (int)Math.min(commonChildMemEstimate, 9999);
            return new CompatibilityResult(true, 4, childScore, "Common child memory estimate: " + commonChildMemEstimate);
        }
        
        // 5순위: 변수명 매칭 (에러 메시지 출력)
        FederatedPlannerLogger.logNameMatchingFallbackWarning(unRefTwriteHop.getName(), liveOutHop.getName());
        
        int nameScore = getMatchingPriority(unRefTwriteHop.getName(), liveOutHop.getName());
        return new CompatibilityResult(true, 5, 100000 + nameScore, "Name matching fallback");
    }

    private static int getMatchingPriority(String unRefTwriteHopName, String liveOutHopName) {
        if (unRefTwriteHopName.equals(liveOutHopName)) {
            return 1; // 정확한 이름 매칭 (최우선)
        }
        
        if (unRefTwriteHopName.startsWith(liveOutHopName) || 
            liveOutHopName.startsWith(unRefTwriteHopName)) {
            return 2; // 접두사 매칭
        }
        
        if (unRefTwriteHopName.contains(liveOutHopName) || 
            liveOutHopName.contains(unRefTwriteHopName)) {
            return 3; // 부분 문자열 매칭
        }
        
        return 4; // 매칭 없음
    }

    private static FType getHopFType(Hop hop, Map<Long, FType> fTypeMap) {
        return fTypeMap.get(hop.getHopID());
    }

    private static boolean areCompatibleFederationTypes(FType fType1, FType fType2) {
        // ROW와 FULL은 호환 가능
        if ((fType1 == FType.ROW && fType2 == FType.FULL) || 
            (fType1 == FType.FULL && fType2 == FType.ROW)) {
            return true;
        }
        
        // COL과 FULL은 호환 가능
        if ((fType1 == FType.COL && fType2 == FType.FULL) || 
            (fType1 == FType.FULL && fType2 == FType.COL)) {
            return true;
        }
        
        // BROADCAST는 대부분과 호환 가능
        if (fType1 == FType.BROADCAST || fType2 == FType.BROADCAST) {
            return true;
        }
        
        return false;
    }

    // 차원 유사성 계산 (0.0 ~ 1.0, 1.0이 가장 유사)
    private static double calculateDimensionSimilarity(Hop hop1, Hop hop2) {
        long dim1_1 = hop1.getDim1();
        long dim1_2 = hop1.getDim2();
        long dim2_1 = hop2.getDim1();
        long dim2_2 = hop2.getDim2();
        
        // 완전히 같은 차원
        if (dim1_1 == dim2_1 && dim1_2 == dim2_2) {
            return 1.0;
        }
        
        // 한 차원이라도 -1이면 유사성 낮음
        if (dim1_1 == -1 || dim1_2 == -1 || dim2_1 == -1 || dim2_2 == -1) {
            return 0.1;
        }
        
        // 차원 비율 계산
        double ratio1 = (dim1_1 == 0 || dim2_1 == 0) ? 0 : Math.min(dim1_1, dim2_1) / (double)Math.max(dim1_1, dim2_1);
        double ratio2 = (dim1_2 == 0 || dim2_2 == 0) ? 0 : Math.min(dim1_2, dim2_2) / (double)Math.max(dim1_2, dim2_2);
        
        // 평균 유사성
        return (ratio1 + ratio2) / 2.0;
    }

    // 공통 child들의 메모리 추정치 계산 (재귀적 탐색, depth 5 제한)
    private static double findCommonChildrenMemEstimate(Hop hop1, Hop hop2, Map<Long, Vertex> vertexMemoTable) {
        Set<Long> children1 = getAllChildren(hop1, new HashSet<>(), 5);
        Set<Long> children2 = getAllChildren(hop2, new HashSet<>(), 5);
        
        // 교집합 찾기
        Set<Long> commonChildren = new HashSet<>(children1);
        commonChildren.retainAll(children2);
        
        // 공통 child들의 총 메모리 추정치 계산 (HopRef 기반)
        double totalMemEstimate = 0.0;
        for (Long childId : commonChildren) {
            Vertex Vertex = vertexMemoTable.get(childId);
            if (Vertex != null) {
                Hop childHop = Vertex.getHopRef();
                if (childHop != null) {
                    // HopRef의 output memory estimate 사용
                    totalMemEstimate += childHop.getOutputMemEstimate();
                }
            }
        }
        
        return totalMemEstimate;
    }

    // 모든 자식 hop들을 재귀적으로 수집 (depth 제한)
    private static Set<Long> getAllChildren(Hop hop, Set<Long> visited, int maxDepth) {
        Set<Long> children = new HashSet<>();
        
        if (maxDepth <= 0 || visited.contains(hop.getHopID())) {
            return children; // depth 제한 또는 순환 방지
        }
        
        visited.add(hop.getHopID());
        
        if (hop.getInput() != null) {
            for (Hop child : hop.getInput()) {
                children.add(child.getHopID());
                children.addAll(getAllChildren(child, visited, maxDepth - 1));
            }
        }
        
        return children;
    }
}
