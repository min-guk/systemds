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

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDP.FederatedPlannerFedCostBased;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
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
import org.apache.sysds.runtime.util.UtilFunctions;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;

public class FederatedPlanCostEnumeratorTest extends AutomatedTestBase
{
	private static final String TEST_DIR = "functions/federated/privacy/";
	private static final String HOME = SCRIPT_DIR + TEST_DIR;
	private static final String TEST_CLASS_DIR = TEST_DIR + FederatedPlanCostEnumeratorTest.class.getSimpleName() + "/";
	
	@Override
	public void setUp() {}

	@Test
	public void testFederatedPlanCostEnumerator1() { runTest("FederatedPlanCostEnumeratorTest1.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator2() { runTest("FederatedPlanCostEnumeratorTest2.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator3() { runTest("FederatedPlanCostEnumeratorTest3.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator4() { runTest("FederatedPlanCostEnumeratorTest4.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator5() { runTest("FederatedPlanCostEnumeratorTest5.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator6() { runTest("FederatedPlanCostEnumeratorTest6.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator7() { runTest("FederatedPlanCostEnumeratorTest7.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator8() { runTest("FederatedPlanCostEnumeratorTest8.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator9() { runTest("FederatedPlanCostEnumeratorTest9.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator10() { runTest("FederatedPlanCostEnumeratorTest10.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator11() { runTest("FederatedPlanCostEnumeratorTest11.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator12() { runTest("FederatedPlanCostEnumeratorTest12.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator13() { runTest("FederatedPlanCostEnumeratorTest13.dml"); }

	@Test
	public void testFederatedPlanCostEnumerator14() {
		runTestWithUnrollCheck("FederatedPlanCostEnumeratorTest14.dml", true, false);
	}

	@Test
	public void testFederatedPlanCostEnumerator15() {
		runTestWithUnrollCheck("FederatedPlanCostEnumeratorTest15.dml", true, false);
	}

	@Test
	public void testFederatedPlanCostEnumerator16() {
		runTestWithUnrollCheck("FederatedPlanCostEnumeratorTest16.dml", false, false);
	}

	@Test
	public void testFederatedPlanCostEnumerator17() {
		runTestWithUnrollCheck("FederatedPlanCostEnumeratorTest17.dml", true, true);
	}

	@Test
	public void testFederatedPlanCostEnumerator18() {
		runTestWithUnrollCheck("FederatedPlanCostEnumeratorTest18.dml", true, true, true, false);
	}

	@Test
	public void testFederatedPlanCostEnumerator19() {
		runTestWithUnrollCheck("FederatedPlanCostEnumeratorTest19.dml", true, true, true, true);
	}

	private void runTest(String scriptFilename) {
		try {
			parseAndRewrite(scriptFilename);
		}
		catch (Exception e) {
			e.printStackTrace();
			Assert.fail(e.getMessage());
		}
	}

	private void runTestWithUnrollCheck(String scriptFilename, boolean expectUnroll) {
		runTestWithUnrollCheck(scriptFilename, expectUnroll, false);
	}

	private void runTestWithUnrollCheck(String scriptFilename, boolean expectUnroll, boolean requireAmortization) {
		runTestWithUnrollCheck(scriptFilename, expectUnroll, requireAmortization, false, false);
	}

	private void runTestWithUnrollCheck(String scriptFilename, boolean expectUnroll, boolean requireAmortization,
			boolean expectOuterXUpdate, boolean requireIfElse) {
		try {
			DMLProgram prog = parseAndRewrite(scriptFilename);

			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable = new HashMap<>();
			Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();
			Set<Long> unRefTwriteSet = new HashSet<>();
			Set<Long> unRefSet = new HashSet<>();
			Set<Hop> progRootHopSet = new HashSet<>();
			FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.UnrollContext unrollCtx =
				new FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.UnrollContext();

			FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.rewireProgram(prog, rewireTable,
				hopCommonTable, privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, unrollCtx);

			boolean hasUnroll = !unrollCtx.getCloneToOrig().isEmpty() || !unrollCtx.getIter1Roots().isEmpty();
			Assert.assertEquals("Unexpected unroll decision for " + scriptFilename, expectUnroll, hasUnroll);
			assertAcyclicFederatedDag(prog, rewireTable, unrollCtx);

			if (expectUnroll) {
				assertForwardingWeightScaled(prog, rewireTable, hopCommonTable, unrollCtx, requireAmortization);
				if (requireAmortization) {
					if (expectOuterXUpdate)
						assertNestedLoopWeightsWithOuterXUpdate(prog, hopCommonTable, rewireTable, unrollCtx);
					else
						assertNestedLoopWeights(prog, hopCommonTable, rewireTable, unrollCtx);
				}
				if (requireIfElse)
					assertInnerIfElseWeights(prog, hopCommonTable);
				boolean hasIter1Multiplicity = false;
				for (Long cloneId : unrollCtx.getCloneToOrig().keySet()) {
					FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon hc = hopCommonTable.get(cloneId);
					if (hc != null && hc.getMultiplicity() > 1.0) {
						hasIter1Multiplicity = true;
						break;
					}
				}
				Assert.assertTrue("Expected iter1 multiplicity for " + scriptFilename, hasIter1Multiplicity);
			}
		}
		catch (Exception e) {
			e.printStackTrace();
			Assert.fail(e.getMessage());
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

		// Set FEDERATED_PLANNER configuration to COMPILE_COST_BASED
		ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");

		//read script
		String dmlScriptString = DMLScript.readDMLScript(true, HOME + scriptFilename);

		//parsing and dependency analysis
		ParserWrapper parser = ParserFactory.createParser();
		DMLProgram prog = parser.parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, dmlScriptString, new HashMap<>());
		DMLTranslator dmlt = new DMLTranslator(prog);
		dmlt.liveVariableAnalysis(prog);
		dmlt.validateParseTree(prog);
		dmlt.constructHops(prog);
		dmlt.rewriteHopsDAG(prog);
		return prog;
	}

	private void assertAcyclicFederatedDag(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.UnrollContext unrollCtx) {
		List<Hop> roots = new ArrayList<>();
		for (StatementBlock sb : prog.getStatementBlocks())
			collectStatementBlockRoots(sb, roots);
		if (unrollCtx != null && unrollCtx.getIter1Roots() != null)
			roots.addAll(unrollCtx.getIter1Roots());

		Map<Long, Integer> visitState = new HashMap<>();
		Deque<Long> path = new ArrayDeque<>();
		for (Hop root : roots)
			dfsCheckDag(root, rewireTable, visitState, path);
	}

	private void assertForwardingWeightScaled(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.UnrollContext unrollCtx,
			boolean requireAmortization) {
		List<Hop> roots = new ArrayList<>();
		for (StatementBlock sb : prog.getStatementBlocks())
			collectStatementBlockRoots(sb, roots);
		if (unrollCtx != null && unrollCtx.getIter1Roots() != null)
			roots.addAll(unrollCtx.getIter1Roots());

		Set<Long> visited = new HashSet<>();
		Deque<Hop> stack = new ArrayDeque<>(roots);
		boolean checkedMultiplicity = false;
		boolean checkedAmortization = false;
		final double eps = 1e-9;
		while (!stack.isEmpty()) {
			Hop hop = stack.pop();
			if (hop == null)
				continue;
			long hopId = hop.getHopID();
			if (!visited.add(hopId))
				continue;

			List<Hop> children = getFederatedDagChildren(hop, rewireTable);
			for (Hop child : children) {
				if (child == null)
					continue;
				stack.push(child);
				FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon parentCommon = hopCommonTable.get(hopId);
				FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon childCommon =
					hopCommonTable.get(child.getHopID());
				if (parentCommon == null || childCommon == null)
					continue;

				double childMultiplicity = childCommon.getMultiplicity();
				double base = (parentCommon.getNetworkWeight() != 0.0) ? parentCommon.getNetworkWeight() : 1.0;
				double missingLoopsProduct = computeMissingLoopProduct(parentCommon.getLoopContext(),
					childCommon.getLoopContext());
				double expected = base * Math.max(childMultiplicity, 0.0) / missingLoopsProduct;
				double actual = parentCommon.computeForwardingWeightOfChild(
					childCommon.getLoopContext(), childMultiplicity);
				Assert.assertEquals("Forwarding weight mismatch for parent " + hopId + " child " + child.getHopID(),
					expected, actual, eps);

				if (childMultiplicity > 1.0 && missingLoopsProduct <= 1.0 + eps) {
					Assert.assertTrue("Expected multiplicity scaling for parent " + hopId + " child "
						+ child.getHopID(), Math.abs(expected - base) > eps);
					checkedMultiplicity = true;
				}
				if (missingLoopsProduct > 1.0 + eps) {
					Assert.assertTrue("Expected amortized forwarding weight for parent " + hopId + " child "
						+ child.getHopID(), expected < base * Math.max(childMultiplicity, 0.0) - eps);
					checkedAmortization = true;
				}
			}
		}

		Assert.assertTrue("No forwarding-weight multiplicity scaling checked for unrolled graph",
			checkedMultiplicity);
		if (requireAmortization)
			Assert.assertTrue("No forwarding-weight amortization checked for nested loops", checkedAmortization);
	}

	private double computeMissingLoopProduct(List<Pair<Long, Double>> parentLoopContext,
			List<Pair<Long, Double>> childLoopContext) {
		if (parentLoopContext == null || parentLoopContext.isEmpty())
			return 1.0;
		Map<Long, Double> childMap = new HashMap<>();
		if (childLoopContext != null) {
			for (Pair<Long, Double> p : childLoopContext)
				childMap.put(p.getLeft(), p.getRight());
		}
		double product = 1.0;
		for (Pair<Long, Double> p : parentLoopContext) {
			long loopId = p.getLeft();
			double iters = p.getRight();
			if (!childMap.containsKey(loopId) && iters > 0.0)
				product *= iters;
		}
		return product;
	}

	private void assertNestedLoopWeights(DMLProgram prog,
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable,
			FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.UnrollContext unrollCtx) {
		Pair<ForStatementBlock, ForStatementBlock> loops = findNestedForLoops(prog);
		Assert.assertNotNull("Expected nested for loops in program", loops);
		ForStatementBlock outer = loops.getLeft();
		ForStatementBlock inner = loops.getRight();
		double outerWeight = computeForLoopWeight(outer);
		double innerWeight = computeForLoopWeight(inner);
		double outerIter1 = Math.max(outerWeight - 1.0, 0.0);
		Assert.assertTrue("Expected outer loop to have iter1 multiplicity", outerIter1 > 0.0);
		long innerLoopId = inner.getSBID();

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon innerPlus =
			findBinaryPlusHop(hopCommonTable, "W", "X", innerLoopId, outerIter1, innerWeight);
		Assert.assertNotNull("Expected inner W+X hop for nested loop", innerPlus);
		Assert.assertEquals("Inner plus computeWeight mismatch", innerWeight, innerPlus.getComputeWeight(), 1e-9);
		Assert.assertEquals("Inner plus multiplicity mismatch", outerIter1, innerPlus.getMultiplicity(), 1e-9);
		Assert.assertEquals("Inner plus networkWeight mismatch", innerWeight, innerPlus.getNetworkWeight(), 1e-9);
		double totalInnerWeight = computeTotalEffectiveWeight(unrollCtx, hopCommonTable,
			innerPlus.getHopRef().getHopID());
		Assert.assertEquals("Nested loop total compute weight mismatch", outerWeight * innerWeight,
			totalInnerWeight, 1e-9);

		Hop innerPlusHop = innerPlus.getHopRef();
		Hop innerPlusLeft = innerPlusHop.getInput().get(0);
		Hop innerPlusRight = innerPlusHop.getInput().get(1);
		Hop treadXHop = isTRead(innerPlusLeft, "X") ? innerPlusLeft
			: (isTRead(innerPlusRight, "X") ? innerPlusRight : null);
		Assert.assertNotNull("Expected TRead X input on inner plus hop", treadXHop);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon sumHop =
			findSumHopOutsideInner(hopCommonTable, "W", innerLoopId, outerIter1);
		Assert.assertNotNull("Expected sum(W) hop outside inner loop", sumHop);
		Assert.assertEquals("Sum hop computeWeight mismatch", 1.0, sumHop.getComputeWeight(), 1e-9);
		Assert.assertEquals("Sum hop multiplicity mismatch", outerIter1, sumHop.getMultiplicity(), 1e-9);
		Assert.assertEquals("Sum hop networkWeight mismatch", 1.0, sumHop.getNetworkWeight(), 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon treadXInside =
			hopCommonTable.get(treadXHop.getHopID());
		Assert.assertNotNull("Expected TRead X inside inner loop", treadXInside);
		Assert.assertEquals("TRead X networkWeight mismatch", innerWeight, treadXInside.getNetworkWeight(), 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon twriteXOutside =
			findConnectedTWrite(treadXInside, hopCommonTable, rewireTable, "X", innerLoopId, false, 1.0);
		Assert.assertNotNull("Expected TWrite X outside inner loop via rewire edge", twriteXOutside);
		Assert.assertEquals("TWrite X multiplicity mismatch", 1.0, twriteXOutside.getMultiplicity(), 1e-9);

		double actualAmort = treadXInside.computeForwardingWeightOfChild(
			twriteXOutside.getLoopContext(), twriteXOutside.getMultiplicity());
		double expectedAmort = treadXInside.getNetworkWeight() * twriteXOutside.getMultiplicity()
			/ computeMissingLoopProduct(treadXInside.getLoopContext(), twriteXOutside.getLoopContext());
		Assert.assertEquals("Amortized forwarding weight mismatch for X edge",
			expectedAmort, actualAmort, 1e-9);

		Hop sumHopInput = sumHop.getHopRef().getInput().get(0);
		Assert.assertTrue("Expected TRead W input on sum(W) hop", isTRead(sumHopInput, "W"));
		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon treadWOutside =
			hopCommonTable.get(sumHopInput.getHopID());
		Assert.assertNotNull("Expected TRead W outside inner loop", treadWOutside);
		Assert.assertEquals("TRead W multiplicity mismatch", outerIter1, treadWOutside.getMultiplicity(), 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon twriteWInside =
			findConnectedTWrite(treadWOutside, hopCommonTable, rewireTable, "W", innerLoopId, true, outerIter1);
		Assert.assertNotNull("Expected TWrite W inside inner loop via rewire edge", twriteWInside);
		Assert.assertEquals("TWrite W multiplicity mismatch", outerIter1, twriteWInside.getMultiplicity(), 1e-9);

		double actualMult = treadWOutside.computeForwardingWeightOfChild(
			twriteWInside.getLoopContext(), twriteWInside.getMultiplicity());
		double expectedMult = treadWOutside.getNetworkWeight() * twriteWInside.getMultiplicity()
			/ computeMissingLoopProduct(treadWOutside.getLoopContext(), twriteWInside.getLoopContext());
		Assert.assertEquals("Forwarding weight mismatch for W edge",
			expectedMult, actualMult, 1e-9);

	}

	private void assertNestedLoopWeightsWithOuterXUpdate(DMLProgram prog,
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable,
			FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.UnrollContext unrollCtx) {
		Pair<ForStatementBlock, ForStatementBlock> loops = findNestedForLoops(prog);
		Assert.assertNotNull("Expected nested for loops in program", loops);
		ForStatementBlock outer = loops.getLeft();
		ForStatementBlock inner = loops.getRight();
		double outerWeight = computeForLoopWeight(outer);
		double innerWeight = computeForLoopWeight(inner);
		double outerIter1 = Math.max(outerWeight - 1.0, 0.0);
		Assert.assertTrue("Expected outer loop to have iter1 multiplicity", outerIter1 > 0.0);
		long outerLoopId = outer.getSBID();
		long innerLoopId = inner.getSBID();

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon innerPlus =
			findBinaryPlusHop(hopCommonTable, "W", "X", innerLoopId, outerIter1, innerWeight);
		Assert.assertNotNull("Expected inner W+X hop for nested loop", innerPlus);
		Assert.assertEquals("Inner plus computeWeight mismatch", innerWeight, innerPlus.getComputeWeight(), 1e-9);
		Assert.assertEquals("Inner plus multiplicity mismatch", outerIter1, innerPlus.getMultiplicity(), 1e-9);
		Assert.assertEquals("Inner plus networkWeight mismatch", innerWeight, innerPlus.getNetworkWeight(), 1e-9);
		double totalInnerWeight = computeTotalEffectiveWeight(unrollCtx, hopCommonTable,
			innerPlus.getHopRef().getHopID());
		Assert.assertEquals("Nested loop total compute weight mismatch", outerWeight * innerWeight,
			totalInnerWeight, 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon outerPlus =
			findBinaryPlusHop(hopCommonTable, "W", "X", outerLoopId, outerIter1, 1.0);
		Assert.assertNotNull("Expected outer X=W+X hop", outerPlus);
		Assert.assertFalse("Expected outer X=W+X hop outside inner loop",
			containsLoopId(outerPlus.getLoopContext(), innerLoopId));
		Assert.assertEquals("Outer plus computeWeight mismatch", 1.0, outerPlus.getComputeWeight(), 1e-9);
		Assert.assertEquals("Outer plus multiplicity mismatch", outerIter1, outerPlus.getMultiplicity(), 1e-9);
		Assert.assertEquals("Outer plus networkWeight mismatch", 1.0, outerPlus.getNetworkWeight(), 1e-9);

		Hop innerPlusHop = innerPlus.getHopRef();
		Hop innerPlusLeft = innerPlusHop.getInput().get(0);
		Hop innerPlusRight = innerPlusHop.getInput().get(1);
		Hop treadXHop = isTRead(innerPlusLeft, "X") ? innerPlusLeft
			: (isTRead(innerPlusRight, "X") ? innerPlusRight : null);
		Assert.assertNotNull("Expected TRead X input on inner plus hop", treadXHop);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon sumHop =
			findSumHopOutsideInner(hopCommonTable, "W", innerLoopId, outerIter1);
		Assert.assertNotNull("Expected sum(W) hop outside inner loop", sumHop);
		Assert.assertEquals("Sum hop computeWeight mismatch", 1.0, sumHop.getComputeWeight(), 1e-9);
		Assert.assertEquals("Sum hop multiplicity mismatch", outerIter1, sumHop.getMultiplicity(), 1e-9);
		Assert.assertEquals("Sum hop networkWeight mismatch", 1.0, sumHop.getNetworkWeight(), 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon treadXInside =
			hopCommonTable.get(treadXHop.getHopID());
		Assert.assertNotNull("Expected TRead X inside inner loop", treadXInside);
		Assert.assertEquals("TRead X networkWeight mismatch", innerWeight, treadXInside.getNetworkWeight(), 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon twriteXOuter =
			findConnectedTWriteWithLoopContext(treadXInside, hopCommonTable, rewireTable, "X", outerLoopId,
				innerLoopId);
		Assert.assertNotNull("Expected TWrite X in outer loop via rewire edge", twriteXOuter);
		Assert.assertEquals("TWrite X multiplicity mismatch", outerIter1, twriteXOuter.getMultiplicity(), 1e-9);

		double actualAmort = treadXInside.computeForwardingWeightOfChild(
			twriteXOuter.getLoopContext(), twriteXOuter.getMultiplicity());
		double expectedAmort = treadXInside.getNetworkWeight() * twriteXOuter.getMultiplicity()
			/ computeMissingLoopProduct(treadXInside.getLoopContext(), twriteXOuter.getLoopContext());
		Assert.assertEquals("Amortized forwarding weight mismatch for X edge",
			expectedAmort, actualAmort, 1e-9);
	}

	private void assertInnerIfElseWeights(DMLProgram prog,
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable) {
		Pair<ForStatementBlock, ForStatementBlock> loops = findNestedForLoops(prog);
		Assert.assertNotNull("Expected nested for loops in program", loops);
		ForStatementBlock outer = loops.getLeft();
		ForStatementBlock inner = loops.getRight();
		double outerWeight = computeForLoopWeight(outer);
		double innerWeight = computeForLoopWeight(inner);
		double outerIter1 = Math.max(outerWeight - 1.0, 0.0);
		long innerLoopId = inner.getSBID();
		double halfWeight = innerWeight * 0.5;

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon innerMult =
			findBinaryOpHop(hopCommonTable, Types.OpOp2.MULT, "W", "X", innerLoopId, outerIter1, halfWeight);
		Assert.assertNotNull("Expected inner W*X hop inside if-else", innerMult);
		Assert.assertEquals("Inner mult computeWeight mismatch", halfWeight, innerMult.getComputeWeight(), 1e-9);
		Assert.assertEquals("Inner mult multiplicity mismatch", outerIter1, innerMult.getMultiplicity(), 1e-9);
		Assert.assertEquals("Inner mult networkWeight mismatch", innerWeight, innerMult.getNetworkWeight(), 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon innerPlusHalf =
			findBinaryOpHop(hopCommonTable, Types.OpOp2.PLUS, "W", "X", innerLoopId, outerIter1, halfWeight);
		Assert.assertNotNull("Expected inner W+X hop inside if-else", innerPlusHalf);
		Assert.assertEquals("Inner plus(if) computeWeight mismatch", halfWeight, innerPlusHalf.getComputeWeight(), 1e-9);

		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon innerPlusFull =
			findBinaryOpHop(hopCommonTable, Types.OpOp2.PLUS, "W", "X", innerLoopId, outerIter1, innerWeight);
		Assert.assertNotNull("Expected inner W+X hop after if-else", innerPlusFull);
		Assert.assertEquals("Inner plus(full) computeWeight mismatch", innerWeight,
			innerPlusFull.getComputeWeight(), 1e-9);
	}

	private Pair<ForStatementBlock, ForStatementBlock> findNestedForLoops(DMLProgram prog) {
		if (prog == null)
			return null;
		for (StatementBlock sb : prog.getStatementBlocks()) {
			Pair<ForStatementBlock, ForStatementBlock> pair = findNestedForLoops(sb);
			if (pair != null)
				return pair;
		}
		return null;
	}

	private Pair<ForStatementBlock, ForStatementBlock> findNestedForLoops(StatementBlock sb) {
		if (sb instanceof ForStatementBlock) {
			ForStatementBlock outer = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) outer.getStatement(0);
			for (StatementBlock inner : fstmt.getBody()) {
				Pair<ForStatementBlock, ForStatementBlock> pair = findNestedForLoopsInBody(outer, inner);
				if (pair != null)
					return pair;
			}
		} else if (sb instanceof IfStatementBlock) {
			IfStatement istmt = (IfStatement) ((IfStatementBlock) sb).getStatement(0);
			for (StatementBlock inner : istmt.getIfBody()) {
				Pair<ForStatementBlock, ForStatementBlock> pair = findNestedForLoops(inner);
				if (pair != null)
					return pair;
			}
			for (StatementBlock inner : istmt.getElseBody()) {
				Pair<ForStatementBlock, ForStatementBlock> pair = findNestedForLoops(inner);
				if (pair != null)
					return pair;
			}
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatement wstmt = (WhileStatement) ((WhileStatementBlock) sb).getStatement(0);
			for (StatementBlock inner : wstmt.getBody()) {
				Pair<ForStatementBlock, ForStatementBlock> pair = findNestedForLoops(inner);
				if (pair != null)
					return pair;
			}
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatement fstmt = (FunctionStatement) ((FunctionStatementBlock) sb).getStatement(0);
			for (StatementBlock inner : fstmt.getBody()) {
				Pair<ForStatementBlock, ForStatementBlock> pair = findNestedForLoops(inner);
				if (pair != null)
					return pair;
			}
		}
		return null;
	}

	private Pair<ForStatementBlock, ForStatementBlock> findNestedForLoopsInBody(ForStatementBlock outer,
			StatementBlock sb) {
		if (sb instanceof ForStatementBlock) {
			return Pair.of(outer, (ForStatementBlock) sb);
		}
		return findNestedForLoops(sb);
	}

	private double computeForLoopWeight(ForStatementBlock fsb) {
		double loopWeight = 1.0;
		Hop from = fsb.getFromHops().getInput().get(0);
		Hop to = fsb.getToHops().getInput().get(0);
		Hop incr = (fsb.getIncrementHops() != null) ? fsb.getIncrementHops().getInput().get(0)
				: new LiteralOp(1);

		if (from instanceof LiteralOp && to instanceof LiteralOp && incr instanceof LiteralOp) {
			double dfrom = HopRewriteUtils.getDoubleValue((LiteralOp) from);
			double dto = HopRewriteUtils.getDoubleValue((LiteralOp) to);
			double dincr = HopRewriteUtils.getDoubleValue((LiteralOp) incr);
			if (dfrom > dto && dincr == 1)
				dincr = -1;
			loopWeight = UtilFunctions.getSeqLength(dfrom, dto, dincr, false);
		}
		return loopWeight;
	}

	private FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon findBinaryPlusHop(
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			String left, String right, long loopId, double multiplicity) {
		return findBinaryPlusHop(hopCommonTable, left, right, loopId, multiplicity, Double.NaN);
	}

	private FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon findBinaryPlusHop(
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			String left, String right, long loopId, double multiplicity, double expectedComputeWeight) {
		return findBinaryOpHop(hopCommonTable, Types.OpOp2.PLUS, left, right, loopId,
			multiplicity, expectedComputeWeight);
	}

	private FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon findBinaryOpHop(
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			Types.OpOp2 op, String left, String right, long loopId, double multiplicity,
			double expectedComputeWeight) {
		for (FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon hc : hopCommonTable.values()) {
			Hop hop = hc.getHopRef();
			if (!(hop instanceof org.apache.sysds.hops.BinaryOp))
				continue;
			org.apache.sysds.hops.BinaryOp bop = (org.apache.sysds.hops.BinaryOp) hop;
			if (bop.getOp() != op)
				continue;
			if (!hasTReadInput(bop, left, right))
				continue;
			if (!containsLoopId(hc.getLoopContext(), loopId))
				continue;
			if (Math.abs(hc.getMultiplicity() - multiplicity) > 1e-9)
				continue;
			if (!Double.isNaN(expectedComputeWeight)
				&& Math.abs(hc.getComputeWeight() - expectedComputeWeight) > 1e-9)
				continue;
			return hc;
		}
		return null;
	}

	private FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon findSumHopOutsideInner(
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			String varName, long innerLoopId, double multiplicity) {
		for (FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon hc : hopCommonTable.values()) {
			Hop hop = hc.getHopRef();
			if (!(hop instanceof org.apache.sysds.hops.AggUnaryOp))
				continue;
			org.apache.sysds.hops.AggUnaryOp aop = (org.apache.sysds.hops.AggUnaryOp) hop;
			if (aop.getOp() != Types.AggOp.SUM)
				continue;
			if (!hasTReadSingleInput(aop, varName))
				continue;
			if (containsLoopId(hc.getLoopContext(), innerLoopId))
				continue;
			if (Math.abs(hc.getMultiplicity() - multiplicity) > 1e-9)
				continue;
			return hc;
		}
		return null;
	}

	private FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon findConnectedTWrite(
			FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon parentRead,
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable, String varName, long loopId, boolean insideLoop,
			double expectedMultiplicity) {
		if (parentRead == null)
			return null;
		List<Hop> children = rewireTable.get(parentRead.getHopRef().getHopID());
		if (children == null || children.isEmpty())
			return null;
		FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon fallback = null;
		for (Hop child : children) {
			if (child == null)
				continue;
			FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon hc = hopCommonTable.get(child.getHopID());
			if (hc == null)
				continue;
			Hop hop = hc.getHopRef();
			if (!(hop instanceof DataOp))
				continue;
			DataOp dop = (DataOp) hop;
			if (dop.getOp() != Types.OpOpData.TRANSIENTWRITE)
				continue;
			if (!varName.equals(dop.getName()))
				continue;
			if (insideLoop != containsLoopId(hc.getLoopContext(), loopId))
				continue;
			if (Math.abs(hc.getMultiplicity() - expectedMultiplicity) <= 1e-9)
				return hc;
			if (fallback == null)
				fallback = hc;
		}
		return fallback;
	}

	private FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon findConnectedTWriteWithLoopContext(
			FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon parentRead,
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable, String varName, long requiredLoopId, long forbiddenLoopId) {
		if (parentRead == null)
			return null;
		List<Hop> children = rewireTable.get(parentRead.getHopRef().getHopID());
		if (children == null || children.isEmpty())
			return null;
		for (Hop child : children) {
			if (child == null)
				continue;
			FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon hc = hopCommonTable.get(child.getHopID());
			if (hc == null)
				continue;
			Hop hop = hc.getHopRef();
			if (!(hop instanceof DataOp))
				continue;
			DataOp dop = (DataOp) hop;
			if (dop.getOp() != Types.OpOpData.TRANSIENTWRITE)
				continue;
			if (!varName.equals(dop.getName()))
				continue;
			if (requiredLoopId > 0 && !containsLoopId(hc.getLoopContext(), requiredLoopId))
				continue;
			if (forbiddenLoopId > 0 && containsLoopId(hc.getLoopContext(), forbiddenLoopId))
				continue;
			return hc;
		}
		return null;
	}

	private double computeTotalEffectiveWeight(
			FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.UnrollContext unrollCtx,
			Map<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> hopCommonTable,
			long hopId) {
		if (hopCommonTable == null || hopCommonTable.isEmpty())
			return 0.0;
		Map<Long, Long> cloneToOrig = (unrollCtx != null) ? unrollCtx.getCloneToOrig() : null;
		long targetOrig = (cloneToOrig != null && cloneToOrig.containsKey(hopId))
			? cloneToOrig.get(hopId)
			: hopId;
		double sum = 0.0;
		for (Map.Entry<Long, FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon> entry
				: hopCommonTable.entrySet()) {
			long candidateId = entry.getKey();
			long candidateOrig = (cloneToOrig != null && cloneToOrig.containsKey(candidateId))
				? cloneToOrig.get(candidateId)
				: candidateId;
			if (candidateOrig == targetOrig) {
				FederatedPlannerFedCostBased.FederatedMemoTable.HopCommon hc = entry.getValue();
				sum += hc.getComputeWeight() * hc.getMultiplicity();
			}
		}
		return sum;
	}

	private boolean hasTReadInput(org.apache.sysds.hops.BinaryOp bop, String left, String right) {
		if (bop.getInput() == null || bop.getInput().size() < 2)
			return false;
		Hop in1 = bop.getInput().get(0);
		Hop in2 = bop.getInput().get(1);
		return isTRead(in1, left) && isTRead(in2, right)
			|| isTRead(in1, right) && isTRead(in2, left);
	}

	private boolean hasTReadSingleInput(org.apache.sysds.hops.AggUnaryOp aop, String varName) {
		if (aop.getInput() == null || aop.getInput().isEmpty())
			return false;
		return isTRead(aop.getInput().get(0), varName);
	}

	private boolean isTRead(Hop hop, String varName) {
		if (!(hop instanceof DataOp))
			return false;
		DataOp dop = (DataOp) hop;
		return dop.getOp() == Types.OpOpData.TRANSIENTREAD && varName.equals(dop.getName());
	}

	private boolean containsLoopId(List<Pair<Long, Double>> loopContext, long loopId) {
		if (loopContext == null || loopContext.isEmpty())
			return false;
		for (Pair<Long, Double> p : loopContext) {
			if (p.getLeft() != null && p.getLeft() == loopId)
				return true;
		}
		return false;
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
