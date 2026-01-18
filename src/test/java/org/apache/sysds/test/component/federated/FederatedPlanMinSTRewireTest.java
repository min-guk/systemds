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
import org.apache.sysds.common.Types;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTRewire;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.ParserWrapper;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.junit.Assert;
import org.junit.Test;

public class FederatedPlanMinSTRewireTest extends AutomatedTestBase {
	private static final String TEST_DIR = "functions/federated/privacy/";
	private static final String HOME = SCRIPT_DIR + TEST_DIR;
	private static final String TEST_CLASS_DIR = TEST_DIR + FederatedPlanMinSTRewireTest.class.getSimpleName() + "/";

	private static final String[] TEST_SCRIPTS = {
		"FederatedPlanCostEnumeratorTest1.dml",
		"FederatedPlanCostEnumeratorTest2.dml",
		"FederatedPlanCostEnumeratorTest3.dml",
		"FederatedPlanCostEnumeratorTest4.dml",
		"FederatedPlanCostEnumeratorTest5.dml",
		"FederatedPlanCostEnumeratorTest6.dml",
		"FederatedPlanCostEnumeratorTest7.dml",
		"FederatedPlanCostEnumeratorTest8.dml",
		"FederatedPlanCostEnumeratorTest9.dml",
		"FederatedPlanCostEnumeratorTest10.dml",
		"FederatedPlanCostEnumeratorTest11.dml",
		"FederatedPlanCostEnumeratorTest12.dml",
		"FederatedPlanCostEnumeratorTest13.dml",
		"FederatedPlanCostEnumeratorTest14.dml",
		"FederatedPlanCostEnumeratorTest15.dml",
		"FederatedPlanCostEnumeratorTest16.dml",
		"FederatedPlanCostEnumeratorTest17.dml",
		"FederatedPlanCostEnumeratorTest18.dml",
		"FederatedPlanCostEnumeratorTest19.dml"
	};

	@Override
	public void setUp() {
		// no-op
	}

	@Test
	public void testMinSTRewireConsistency() {
		for (String scriptFilename : TEST_SCRIPTS) {
			runRewireChecks(scriptFilename);
		}
	}

	@Test
	public void testMinSTRewireLoopCarryEdges() {
		try {
			String dml = String.join("\n",
					"x = matrix(1, rows=10, cols=10);",
					"for(i in 1:5) {",
					"  x = x + 1;",
					"}",
					"print(sum(x));",
					"");
			DMLProgram prog = parseAndRewriteScript("MinSTRewireLoopCarryEdges", dml);

			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();
			Set<Long> unRefTwriteSet = new HashSet<>();
			Set<Long> unRefSet = new HashSet<>();
			Set<Hop> progRootHopSet = new HashSet<>();
			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);

			FederatedPlanMinSTRewire.rewireProgram(
					prog, rewireTable, graph, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, oracleFacade);

			List<FederatedPlanMinSTGraph.LoopCarryEdge> loopEdges = graph.getLoopCarryEdges();
			Assert.assertFalse("Expected at least one loop-carry edge", loopEdges.isEmpty());

			boolean foundX = false;
			for (FederatedPlanMinSTGraph.LoopCarryEdge edge : loopEdges) {
				Hop end = graph.getHopRef(edge.getEndWriterHopId());
				Hop front = graph.getHopRef(edge.getFrontReaderHopId());
				if (end instanceof DataOp && ((DataOp) end).getOp() == Types.OpOpData.TRANSIENTWRITE
						&& "x".equals(end.getName())
						&& front instanceof DataOp && ((DataOp) front).getOp() == Types.OpOpData.TRANSIENTREAD
						&& "x".equals(front.getName())) {
					foundX = true;
					Assert.assertEquals("Unexpected loop-carry weight for x", 4.0, edge.getWeight(), 1e-9);
					break;
				}
			}
			Assert.assertTrue("Missing loop-carry edge for x", foundX);
		}
		catch (Exception e) {
			e.printStackTrace();
			Assert.fail("MinST loop-carry test failed: " + e.getMessage());
		}
	}

	private void runRewireChecks(String scriptFilename) {
		try {
			DMLProgram prog = parseAndRewrite(scriptFilename);

			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			FederatedPlanMinSTGraph graph =
				new FederatedPlanMinSTGraph();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();
			Set<Long> unRefTwriteSet = new HashSet<>();
			Set<Long> unRefSet = new HashSet<>();
			Set<Hop> progRootHopSet = new HashSet<>();
			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);

			FederatedPlanMinSTRewire.rewireProgram(
				prog, rewireTable, graph, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, oracleFacade);

			assertAcyclicFederatedDag(prog, rewireTable);
			Map<Long, Hop> hopMap = buildHopMap(prog, rewireTable);
			assertTransientReadMappings(rewireTable, hopMap);
		}
		catch (Exception e) {
			e.printStackTrace();
			Assert.fail("MinST rewire check failed for " + scriptFilename + ": " + e.getMessage());
		}
	}

	private DMLProgram parseAndRewrite(String scriptFilename) throws Exception {
		int index = scriptFilename.lastIndexOf(".dml");
		String testName = scriptFilename.substring(0, index > 0 ? index : scriptFilename.length());
		TestConfiguration testConfig = new TestConfiguration(TEST_CLASS_DIR, testName, new String[] {});
		addTestConfiguration(testName, testConfig);
		loadTestConfiguration(testConfig);

		DMLConfig conf = new DMLConfig(getCurConfigFile().getPath());
		ConfigurationManager.setLocalConfig(conf);
		ConfigurationManager.getDMLConfig().setTextValue(
			DMLConfig.FEDERATED_PLANNER, "compile_min_st_cut");

		String dmlScriptString = DMLScript.readDMLScript(true, HOME + scriptFilename);

		ParserWrapper parser = ParserFactory.createParser();
		DMLProgram prog = parser.parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, dmlScriptString, new HashMap<>());
		DMLTranslator dmlt = new DMLTranslator(prog);
		dmlt.liveVariableAnalysis(prog);
		dmlt.validateParseTree(prog);
		dmlt.constructHops(prog);
		dmlt.rewriteHopsDAG(prog);
		return prog;
	}

	private DMLProgram parseAndRewriteScript(String testName, String dmlScriptString) throws Exception {
		TestConfiguration testConfig = new TestConfiguration(TEST_CLASS_DIR, testName, new String[] {});
		addTestConfiguration(testName, testConfig);
		loadTestConfiguration(testConfig);

		DMLConfig conf = new DMLConfig(getCurConfigFile().getPath());
		ConfigurationManager.setLocalConfig(conf);
		ConfigurationManager.getDMLConfig().setTextValue(
				DMLConfig.FEDERATED_PLANNER, "compile_min_st_cut");

		ParserWrapper parser = ParserFactory.createParser();
		DMLProgram prog = parser.parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, dmlScriptString, new HashMap<>());
		DMLTranslator dmlt = new DMLTranslator(prog);
		dmlt.liveVariableAnalysis(prog);
		dmlt.validateParseTree(prog);
		dmlt.constructHops(prog);
		dmlt.rewriteHopsDAG(prog);
		return prog;
	}

	private void assertAcyclicFederatedDag(DMLProgram prog, Map<Long, List<Hop>> rewireTable) {
		List<Hop> roots = new ArrayList<>();
		for (StatementBlock sb : prog.getStatementBlocks())
			collectStatementBlockRoots(sb, roots);

		Map<Long, Integer> visitState = new HashMap<>();
		Deque<Long> path = new ArrayDeque<>();
		for (Hop root : roots)
			dfsCheckDag(root, rewireTable, visitState, path);
	}

	private void dfsCheckDag(Hop hop, Map<Long, List<Hop>> rewireTable,
			Map<Long, Integer> visitState, Deque<Long> path) {
		if (hop == null)
			return;
		long hopId = hop.getHopID();
		Integer state = visitState.get(hopId);
		if (state != null) {
			if (state == 1) {
				Assert.fail("Cycle detected at hop " + hopId + " (" + hop.getOpString() + ") path=" + path);
			}
			return;
		}
		visitState.put(hopId, 1);
		path.push(hopId);
		for (Hop child : getFederatedDagChildren(hop, rewireTable))
			dfsCheckDag(child, rewireTable, visitState, path);
		path.pop();
		visitState.put(hopId, 2);
	}

	private List<Hop> getFederatedDagChildren(Hop hop, Map<Long, List<Hop>> rewireTable) {
		List<Hop> children = new ArrayList<>();
		if (hop.getInput() != null)
			children.addAll(hop.getInput());
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			List<Hop> transChildHops = rewireTable.get(hop.getHopID());
			if (transChildHops != null) {
				for (Hop transChildHop : transChildHops) {
					if (transChildHop instanceof DataOp
							&& ((DataOp) transChildHop).getOp() == Types.OpOpData.TRANSIENTREAD
							&& hop.getName().equals(transChildHop.getName())) {
						continue;
					}
					children.add(transChildHop);
				}
			}
		}
		return children;
	}

	private Map<Long, Hop> buildHopMap(DMLProgram prog, Map<Long, List<Hop>> rewireTable) {
		Map<Long, Hop> hopMap = new HashMap<>();
		List<Hop> roots = new ArrayList<>();
		for (StatementBlock sb : prog.getStatementBlocks())
			collectStatementBlockRoots(sb, roots);

		Deque<Hop> stack = new ArrayDeque<>(roots);
		while (!stack.isEmpty()) {
			Hop hop = stack.pop();
			if (hop == null)
				continue;
			if (hopMap.putIfAbsent(hop.getHopID(), hop) != null)
				continue;
			if (hop.getInput() != null)
				stack.addAll(hop.getInput());
		}

		if (rewireTable != null) {
			for (List<Hop> hops : rewireTable.values()) {
				if (hops == null)
					continue;
				for (Hop hop : hops) {
					if (hop != null)
						hopMap.putIfAbsent(hop.getHopID(), hop);
				}
			}
		}

		return hopMap;
	}

		private void assertTransientReadMappings(Map<Long, List<Hop>> rewireTable, Map<Long, Hop> hopMap) {
			if (rewireTable == null || rewireTable.isEmpty())
				return;
			for (Map.Entry<Long, List<Hop>> entry : rewireTable.entrySet()) {
				Hop hop = hopMap.get(entry.getKey());
				if (!isTransientRead(hop))
					continue;
				List<Hop> childHops = entry.getValue();
				Assert.assertNotNull("Missing TRead mapping for hop " + hop.getHopID(), childHops);
				Assert.assertFalse("Empty TRead mapping for hop " + hop.getHopID(), childHops.isEmpty());
				for (Hop child : childHops) {
					Assert.assertNotNull("Null child in TRead mapping for hop " + hop.getHopID(), child);
					Assert.assertNotEquals("Self-referential child in TRead mapping for hop " + hop.getHopID(),
							hop.getHopID(), child.getHopID());
					Assert.assertFalse("Redundant TRead child in mapping for hop " + hop.getHopID(),
							isTransientRead(child) && hop.getName().equals(child.getName()));
				}
			}
		}

	private boolean isTransientRead(Hop hop) {
		if (!(hop instanceof DataOp))
			return false;
		return ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD;
	}

	private boolean isTransientWrite(Hop hop) {
		if (!(hop instanceof DataOp))
			return false;
		return ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE;
	}

	private void collectStatementBlockRoots(StatementBlock sb, List<Hop> roots) {
		if (sb == null)
			return;
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			roots.add(isb.getPredicateHops());
			for (StatementBlock inner : istmt.getIfBody())
				collectStatementBlockRoots(inner, roots);
			for (StatementBlock inner : istmt.getElseBody())
				collectStatementBlockRoots(inner, roots);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			roots.add(fsb.getFromHops());
			roots.add(fsb.getToHops());
			if (fsb.getIncrementHops() != null)
				roots.add(fsb.getIncrementHops());
			for (StatementBlock inner : fstmt.getBody())
				collectStatementBlockRoots(inner, roots);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			roots.add(wsb.getPredicateHops());
			for (StatementBlock inner : wstmt.getBody())
				collectStatementBlockRoots(inner, roots);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				collectStatementBlockRoots(inner, roots);
		} else {
			if (sb.getHops() != null)
				roots.addAll(sb.getHops());
		}
	}
}
