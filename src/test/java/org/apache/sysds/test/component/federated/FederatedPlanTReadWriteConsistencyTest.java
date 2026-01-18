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

package org.apache.sysds.test.component.federated;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCostEstimator;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanTReadWriteConsistencyTest {
	private static final double HARD_CONSTRAINT = 1e15;

	@Test
	public void testDpTReadRejectsMixedTWrites() throws Exception {
		DpScenario scenario = buildDpScenario(true);
		OracleFacade oracleFacade = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		Set<Long> unref = new HashSet<>();

		invokeEnumerateHop(scenario.tw1, scenario.memoTable, scenario.hopCommonTable,
			scenario.rewireTable, scenario.privacyMap, unref, 1, oracleFacade);
		invokeEnumerateHop(scenario.tw2, scenario.memoTable, scenario.hopCommonTable,
			scenario.rewireTable, scenario.privacyMap, unref, 1, oracleFacade);

		try {
			invokeEnumerateHop(scenario.tr, scenario.memoTable, scenario.hopCommonTable,
				scenario.rewireTable, scenario.privacyMap, unref, 1, oracleFacade);
			Assert.fail("Expected DMLRuntimeException for mixed TWRITE exec/out");
		}
		catch (InvocationTargetException ex) {
			Throwable cause = ex.getCause();
			if (!(cause instanceof DMLRuntimeException)) {
				Assert.fail("Unexpected exception: " + cause);
			}
		}
	}

	@Test
	public void testDpTReadAllowsUniformTWrites() throws Exception {
		DpScenario scenario = buildDpScenario(false);
		OracleFacade oracleFacade = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		Set<Long> unref = new HashSet<>();

		invokeEnumerateHop(scenario.tw1, scenario.memoTable, scenario.hopCommonTable,
			scenario.rewireTable, scenario.privacyMap, unref, 1, oracleFacade);
		invokeEnumerateHop(scenario.tw2, scenario.memoTable, scenario.hopCommonTable,
			scenario.rewireTable, scenario.privacyMap, unref, 1, oracleFacade);
		invokeEnumerateHop(scenario.tr, scenario.memoTable, scenario.hopCommonTable,
			scenario.rewireTable, scenario.privacyMap, unref, 1, oracleFacade);

		FedPlan trFout = scenario.memoTable.getFedPlanAfterPrune(
			scenario.tr.getHopID(), FederatedOutput.FOUT);
		Assert.assertNotNull("Expected FOUT plan for TREAD", trFout);
		Assert.assertEquals("Expected FED exec for TREAD", ExecType.FED, trFout.getExecType());
		Assert.assertNull("Unexpected LOUT plan for TREAD",
			scenario.memoTable.getFedPlanAfterPrune(scenario.tr.getHopID(), FederatedOutput.LOUT));
	}

	@Test
	public void testMinSTAddsHardConstraintEdgesForMultipleTWrites() throws Exception {
		DataOp op1 = createTransientRead("op1");
		DataOp op2 = createTransientRead("op2");
		DataOp tw1 = createTransientWrite("X", op1);
		DataOp tw2 = createTransientWrite("X", op2);
		DataOp tr = createTransientRead("X");

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		addVertex(graph, tw1);
		addVertex(graph, tw2);
		addVertex(graph, tr);

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(tr.getHopID(), Arrays.asList(tw1, tw2));

		invokeEstimateHop(tr, graph, rewireTable);

		Graph<Long, DefaultWeightedEdge> g = graph.getGraph();
		assertConstraintEdge(g, computeId(tw1.getHopID()), computeId(tr.getHopID()));
		assertConstraintEdge(g, computeId(tr.getHopID()), computeId(tw1.getHopID()));
		assertConstraintEdge(g, computeId(tw2.getHopID()), computeId(tr.getHopID()));
		assertConstraintEdge(g, computeId(tr.getHopID()), computeId(tw2.getHopID()));
		assertConstraintEdge(g, placementId(tw1.getHopID()), placementId(tr.getHopID()));
		assertConstraintEdge(g, placementId(tr.getHopID()), placementId(tw1.getHopID()));
		assertConstraintEdge(g, placementId(tw2.getHopID()), placementId(tr.getHopID()));
		assertConstraintEdge(g, placementId(tr.getHopID()), placementId(tw2.getHopID()));
	}

	private static void assertConstraintEdge(Graph<Long, DefaultWeightedEdge> graph, long from, long to) {
		DefaultWeightedEdge edge = graph.getEdge(from, to);
		Assert.assertNotNull("Missing constraint edge " + from + " -> " + to, edge);
		Assert.assertEquals("Unexpected constraint weight for edge " + from + " -> " + to,
			HARD_CONSTRAINT, graph.getEdgeWeight(edge), 0.0);
	}

	private static void invokeEnumerateHop(Hop hop, FederatedPlannerDpMemoTable memoTable,
			Map<Long, HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, Privacy> privacyMap, Set<Long> unref, int numWorkers,
			OracleFacade oracleFacade) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"enumerateHop", Hop.class, FederatedPlannerDpMemoTable.class, Map.class, Map.class,
			Map.class, Set.class, int.class, OracleFacade.class);
		method.setAccessible(true);
		method.invoke(null, hop, memoTable, hopCommonTable, rewireTable, privacyMap, unref, numWorkers, oracleFacade);
	}

	private static void invokeEstimateHop(Hop hop, FederatedPlanMinSTGraph graph,
			Map<Long, List<Hop>> rewireTable) throws Exception {
		Method method = FederatedPlanMinSTCostEstimator.class.getDeclaredMethod(
			"estimateHop", Hop.class, FederatedPlanMinSTGraph.class, Map.class);
		method.setAccessible(true);
		method.invoke(null, hop, graph, rewireTable);
	}

	private static DpScenario buildDpScenario(boolean mixed) {
		DataOp op1 = createTransientRead("op1");
		DataOp op2 = createTransientRead("op2");
		DataOp tw1 = createTransientWrite("X", op1);
		DataOp tw2 = createTransientWrite("X", op2);
		DataOp tr = createTransientRead("X");

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		registerHopCommon(hopCommonTable, op1);
		registerHopCommon(hopCommonTable, op2);
		registerHopCommon(hopCommonTable, tw1);
		registerHopCommon(hopCommonTable, tw2);
		registerHopCommon(hopCommonTable, tr);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addSinglePlan(memoTable, hopCommonTable.get(op1.getHopID()),
			FederatedOutput.FOUT, ExecType.FED, FType.ROW);
		if (mixed) {
			addSinglePlan(memoTable, hopCommonTable.get(op2.getHopID()),
				FederatedOutput.LOUT, ExecType.CP, null);
		} else {
			addSinglePlan(memoTable, hopCommonTable.get(op2.getHopID()),
				FederatedOutput.FOUT, ExecType.FED, FType.ROW);
		}

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(tr.getHopID(), Arrays.asList(tw1, tw2));
		rewireTable.put(tw1.getHopID(), Collections.singletonList(tr));
		rewireTable.put(tw2.getHopID(), Collections.singletonList(tr));

		Map<Long, Privacy> privacyMap = new HashMap<>();
		privacyMap.put(tw1.getHopID(), Privacy.PRIVATE);
		privacyMap.put(tw2.getHopID(), mixed ? Privacy.PUBLIC : Privacy.PRIVATE);
		privacyMap.put(tr.getHopID(), Privacy.PUBLIC);

		return new DpScenario(tw1, tw2, tr, memoTable, hopCommonTable, rewireTable, privacyMap);
	}

	private static void addSinglePlan(FederatedPlannerDpMemoTable memoTable, HopCommon hopCommon,
			FederatedOutput fedOutType, ExecType execType, FType fType) {
		FedPlanVariants variants = new FedPlanVariants(hopCommon, fedOutType);
		FedPlan plan = new FedPlan(0.0, variants, Collections.emptyList());
		plan.setExecType(execType);
		plan.setFType(fType);
		variants.addFedPlan(plan);
		memoTable.addFedPlanVariants(hopCommon.getHopRef().getHopID(), fedOutType, variants);
	}

	private static HopCommon registerHopCommon(Map<Long, HopCommon> table, Hop hop) {
		HopCommon common = new HopCommon(hop, 1.0, 1.0, 1.0, 1, Collections.emptyList());
		table.put(hop.getHopID(), common);
		return common;
	}

	private static DataOp createTransientRead(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 10, 10, 100, 1000);
	}

	private static DataOp createTransientWrite(String name, Hop input) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, input, OpOpData.TRANSIENTWRITE, null);
	}

	private static void addVertex(FederatedPlanMinSTGraph graph, Hop hop) {
		ExecPlacementCaps caps = new ExecPlacementCaps();
		Vertex vertex = new Vertex(hop, Privacy.PUBLIC, null, caps);
		vertex.setMetadata(1.0, 1.0, new ArrayList<>());
		graph.addVertex(vertex);
	}

	private static long computeId(long hopId) {
		return hopId << 1;
	}

	private static long placementId(long hopId) {
		return (hopId << 1) | 1;
	}

	private static final class DpScenario {
		private final DataOp tw1;
		private final DataOp tw2;
		private final DataOp tr;
		private final FederatedPlannerDpMemoTable memoTable;
		private final Map<Long, HopCommon> hopCommonTable;
		private final Map<Long, List<Hop>> rewireTable;
		private final Map<Long, Privacy> privacyMap;

		private DpScenario(DataOp tw1, DataOp tw2, DataOp tr, FederatedPlannerDpMemoTable memoTable,
				Map<Long, HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
				Map<Long, Privacy> privacyMap) {
			this.tw1 = tw1;
			this.tw2 = tw2;
			this.tr = tr;
			this.memoTable = memoTable;
			this.hopCommonTable = hopCommonTable;
			this.rewireTable = rewireTable;
			this.privacyMap = privacyMap;
		}
	}
}
