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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTRewire;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.ParserWrapper;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.common.Types.ReOrgOp;
import org.junit.Test;

public class FederatedPlannerFallbackIntegrationTest {
	private static final int ROWS = 10;
	private static final int COLS = 10;
	private static final int BLOCKSIZE = 1000;

	private static final String FEDALL_SCRIPT = String.join("\n",
		"X = federated(addresses=list($X1, $X2),",
		"              ranges=list(list(0, 0), list($r / 2, $c), list($r / 2, 0), list($r, $c)));",
		"Y = t(X);",
		"Z = X + Y;",
		"W = Z * X;",
		"write(W, $W);",
		"");

	@Test
	public void testFedAllCpfoutChain() throws Exception {
		Map<String, String> args = new HashMap<>();
		args.put("$X1", "localhost:1234/tmp/fedall/X1");
		args.put("$X2", "localhost:1235/tmp/fedall/X2");
		args.put("$r", String.valueOf(ROWS));
		args.put("$c", String.valueOf(COLS));
		args.put("$W", "tmp/fedall/W");

		DMLProgram prog = parseAndRewrite(FEDALL_SCRIPT, args, "compile_fed_all");
		List<Hop> roots = collectRoots(prog);
		List<Hop> allHops = collectAllHops(roots);

		DataOp xHop = findFederatedInput(allHops, "X");
		ReorgOp yHop = findTransposeOf(allHops, xHop);
		BinaryOp zHop = findBinaryPlusWithInputs(allHops, xHop, yHop);
		BinaryOp wHop = findBinaryOpWithInputs(allHops, zHop, xHop, OpOp2.MULT);

		assertNotNull("Expected federated X input", xHop);
		assertNotNull("Expected Y=t(X) hop", yHop);
		assertNotNull("Expected Z=X+Y hop", zHop);
		assertNotNull("Expected W=Z*X hop", wHop);
		assertEquals("Expected CP->FOUT on Z", FederatedOutput.FOUT, zHop.getFederatedOutput());
		assertEquals("Expected FED output on W", FederatedOutput.FOUT, wHop.getFederatedOutput());
	}

	@Test
	public void testDpFallbackFTypeForCpfout() throws Exception {
		DataOp left = transientRead("X");
		DataOp right = transientRead("Y");
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon leftCommon = registerHopCommon(hopCommonTable, left);
		HopCommon rightCommon = registerHopCommon(hopCommonTable, right);
		HopCommon plusCommon = registerHopCommon(hopCommonTable, plus);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addSinglePlan(memoTable, leftCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW);
		addSinglePlan(memoTable, rightCommon, FederatedOutput.FOUT, ExecType.FED, FType.COL);
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap = new HashMap<>();
		privacyMap.put(left.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
		privacyMap.put(right.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
		privacyMap.put(plus.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);

		Set<Long> unref = new HashSet<>();
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		invokeEnumerateHop(plus, memoTable, hopCommonTable, rewireTable, privacyMap, unref, 1, oracle);

		FedPlan plan = memoTable.getFedPlanAfterPrune(plus.getHopID(), FederatedOutput.FOUT);
		assertNotNull("Expected CP_FOUT plan for binary plus", plan);
		assertEquals("Expected ROW fallback FType for mismatch inputs", FType.ROW, plan.getFType());
	}

	@Test
	public void testMinSTFallbackFTypeForVertex() throws Exception {
		DataOp left = transientRead("X");
		DataOp right = transientRead("Y");
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(left.getHopID(), FType.ROW);
		fTypeMap.put(right.getHopID(), FType.COL);

		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap = new HashMap<>();
		privacyMap.put(left.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
		privacyMap.put(right.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		Vertex vertex = invokeRewireHop(plus, graph, fTypeMap, privacyMap, oracle);

		assertNotNull("Expected vertex for binary plus", vertex);
		assertEquals("Expected ROW fallback FType for mismatch inputs", FType.ROW, vertex.getDataType());
	}

	private static DMLProgram parseAndRewrite(String script, Map<String, String> args, String planner) throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig newConfig = new DMLConfig(oldConfig);
		newConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, planner);
		ConfigurationManager.setGlobalConfig(newConfig);
		ConfigurationManager.setLocalConfig(newConfig);

		try {
			ParserWrapper parser = ParserFactory.createParser();
			DMLProgram prog = parser.parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, args);
			DMLTranslator dmlt = new DMLTranslator(prog);
			dmlt.liveVariableAnalysis(prog);
			dmlt.validateParseTree(prog);
			dmlt.constructHops(prog);
			dmlt.rewriteHopsDAG(prog);
			return prog;
		} finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	private static List<Hop> collectRoots(DMLProgram prog) {
		List<Hop> roots = new ArrayList<>();
		if (prog == null)
			return roots;
		for (StatementBlock sb : prog.getStatementBlocks()) {
			if (sb.getHops() != null)
				roots.addAll(sb.getHops());
		}
		return roots;
	}

	private static List<Hop> collectAllHops(List<Hop> roots) {
		List<Hop> all = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		Deque<Hop> queue = new ArrayDeque<>(roots);
		while (!queue.isEmpty()) {
			Hop hop = queue.poll();
			if (hop == null || !visited.add(hop.getHopID()))
				continue;
			all.add(hop);
			if (hop.getInput() != null)
				queue.addAll(hop.getInput());
		}
		return all;
	}

	private static DataOp findFederatedInput(List<Hop> allHops, String name) {
		for (Hop hop : allHops) {
			if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED
					&& name.equals(hop.getName())) {
				return (DataOp) hop;
			}
		}
		return null;
	}

	private static ReorgOp findTransposeOf(List<Hop> allHops, Hop input) {
		if (input == null)
			return null;
		for (Hop hop : allHops) {
			if (hop instanceof ReorgOp && ((ReorgOp) hop).getOp() == ReOrgOp.TRANS) {
				List<Hop> inputs = hop.getInput();
				if (inputs != null && !inputs.isEmpty() && inputs.get(0).getHopID() == input.getHopID())
					return (ReorgOp) hop;
			}
		}
		return null;
	}

	private static BinaryOp findBinaryPlusWithInputs(List<Hop> allHops, Hop left, Hop right) {
		return findBinaryOpWithInputs(allHops, left, right, OpOp2.PLUS);
	}

	private static BinaryOp findBinaryOpWithInputs(List<Hop> allHops, Hop left, Hop right, OpOp2 op) {
		if (left == null || right == null)
			return null;
		long leftId = left.getHopID();
		long rightId = right.getHopID();
		for (Hop hop : allHops) {
			if (hop instanceof BinaryOp && ((BinaryOp) hop).getOp() == op) {
				List<Hop> inputs = hop.getInput();
				if (inputs == null || inputs.size() < 2)
					continue;
				long in0 = inputs.get(0).getHopID();
				long in1 = inputs.get(1).getHopID();
				if ((in0 == leftId && in1 == rightId) || (in0 == rightId && in1 == leftId))
					return (BinaryOp) hop;
			}
		}
		return null;
	}

	private static DataOp transientRead(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, ROWS, COLS, ROWS * COLS, BLOCKSIZE);
	}

	private static HopCommon registerHopCommon(Map<Long, HopCommon> table, Hop hop) {
		HopCommon common = new HopCommon(hop, 1.0, 1.0, 1.0, 1, List.of());
		table.put(hop.getHopID(), common);
		return common;
	}

	private static void addSinglePlan(FederatedPlannerDpMemoTable memoTable, HopCommon hopCommon,
			FederatedOutput fedOutType, ExecType execType, FType fType) {
		FedPlanVariants variants = new FedPlanVariants(hopCommon, fedOutType);
		FedPlan plan = new FedPlan(0.0, variants, List.of());
		plan.setExecType(execType);
		plan.setFType(fType);
		variants.addFedPlan(plan);
		memoTable.addFedPlanVariants(hopCommon.getHopRef().getHopID(), fedOutType, variants);
	}

	private static void invokeEnumerateHop(Hop hop, FederatedPlannerDpMemoTable memoTable,
			Map<Long, HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap, Set<Long> unref,
			int numWorkers, OracleFacade oracleFacade) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"enumerateHop", Hop.class, FederatedPlannerDpMemoTable.class, Map.class, Map.class,
			Map.class, Set.class, int.class, OracleFacade.class);
		method.setAccessible(true);
		method.invoke(null, hop, memoTable, hopCommonTable, rewireTable, privacyMap, unref, numWorkers, oracleFacade);
	}

	@SuppressWarnings("unchecked")
	private static Vertex invokeRewireHop(Hop hop, FederatedPlanMinSTGraph graph, Map<Long, FType> fTypeMap,
			Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap, OracleFacade oracleFacade)
			throws Exception {
		Method method = FederatedPlanMinSTRewire.class.getDeclaredMethod(
			"rewireHop", Hop.class, Map.class, List.class, Map.class, Map.class,
			Map.class, FederatedPlanMinSTGraph.class, Map.class, List.class, Set.class,
			Set.class, List.class, OracleFacade.class);
		method.setAccessible(true);
		return (Vertex) method.invoke(null, hop, new HashMap<Long, List<Hop>>(), new ArrayList<>(),
			new HashMap<>(), new HashMap<>(), privacyMap, graph, fTypeMap, new ArrayList<>(),
			new HashSet<>(), new HashSet<>(), new ArrayList<>(), oracleFacade);
	}
}
