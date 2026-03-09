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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTRewire;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.ParserWrapper;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
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

		Map<Long, Set<Long>> uploadHints = new HashMap<>();
		Set<Long> unref = new HashSet<>();
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		invokeEnumerateHop(plus, memoTable, hopCommonTable, rewireTable, privacyMap, uploadHints, unref, 1, oracle);

		FedPlan plan = memoTable.getFedPlanAfterPrune(plus.getHopID(), FederatedOutput.FOUT);
		assertNotNull("Expected CP_FOUT plan for binary plus", plan);
		assertEquals("Expected ROW fallback FType for mismatch inputs", FType.ROW, plan.getFType());
	}

	@Test
	public void testDpDerivedFedFoutWhenOracleOnlyAllowsFedLout() throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp left = federatedRead("FX", ROWS, COLS);
			DataOp right = transientRead("Y");
			BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);
			plus.setDim1(ROWS);
			plus.setDim2(COLS);
			UnaryOp fedParent = HopRewriteUtils.createUnary(plus, OpOp1.EXP);
			fedParent.setDim1(ROWS);
			fedParent.setDim2(COLS);

			Map<Long, FType> plannedFTypes = new HashMap<>();
			plannedFTypes.put(left.getHopID(), FType.ROW);
			assertTrue("Expected refed planner to allow CP->FOUT candidate from planned FTypes",
				FederatedRefedPolicy.canGenerateCpfoutCandidateFromFTypes(plus, plannedFTypes));

			ExecPlacementPolicy.Decision placementDecision = new ExecPlacementPolicy.Decision();
			placementDecision.allowFED_LOUT = true;
			placementDecision.allowFED_FOUT = false;
			OpCaps caps = OpCaps.newBuilder()
				.exec(ExecType.FED)
				.placement(FederatedOutput.LOUT)
				.reason(ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME)
				.build();

			Method shouldEnableMethod = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
				"shouldEnableDerivedFedFout", Hop.class, Privacy.class, Map.class,
				OpCaps.class, ExecPlacementPolicy.Decision.class);
			shouldEnableMethod.setAccessible(true);
			boolean shouldEnableDerivedFedFout = (boolean) shouldEnableMethod.invoke(
				null, plus, Privacy.PUBLIC, plannedFTypes, caps, placementDecision);
			assertTrue("Expected DP to enable derived FED_FOUT under FED+LOUT oracle decision",
				shouldEnableDerivedFedFout);
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDpDerivedFedFoutBoundaryCostIncludesDownloadAndUpload() throws Exception {
		Method boundaryCostMethod = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"derivedFedFoutBoundaryCost", boolean.class, double.class, double.class);
		boundaryCostMethod.setAccessible(true);

		double derivedCost = (double) boundaryCostMethod.invoke(null, true, 7.0, 11.0);
		double nativeCost = (double) boundaryCostMethod.invoke(null, false, 7.0, 11.0);

		assertEquals("Derived FED_FOUT should include both upload and download boundary costs",
			18.0, derivedCost, 1e-9);
		assertEquals("Native FED_FOUT should not add derived boundary costs",
			0.0, nativeCost, 1e-9);
	}

	@Test
	public void testDpTransientFedParentForwardingChargesRefedShare() throws Exception {
		DataOp fedInput = federatedRead("Xfed", ROWS, COLS);
		DataOp tWrite = HopRewriteUtils.createTransientWrite("Xtmp", fedInput);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon inputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tWriteCommon = registerHopCommon(hopCommonTable, tWrite);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan inputPlan = addCustomPlan(memoTable, inputCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0);

		FedPlanVariants tWriteVariants = new FedPlanVariants(tWriteCommon, FederatedOutput.FOUT);
		FedPlan tWritePlan = new FedPlan(0.0, tWriteVariants,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		tWritePlan.setExecType(ExecType.FED);
		tWritePlan.setFType(FType.ROW);
		tWriteVariants.addFedPlan(tWritePlan);
		memoTable.addFedPlanVariants(tWrite.getHopID(), FederatedOutput.FOUT, tWriteVariants);
		memoTable.registerHopRefs(hopCommonTable);

		double forwardingShare = invokeDpPlannerForwardingShare(
			true, FederatedOutput.FOUT, inputPlan, tWritePlan, 1);

		assertTrue("Transient FED parent consuming FOUT child should pay a positive refed/share cost",
			forwardingShare > 0.0);
	}

	@Test
	public void testDpChildCostBuffersChargeTransientFoutToFedShare() throws Exception {
		DataOp child = transientWrite("Xtmp", ROWS, COLS);
		DataOp parent = transientRead("Xtmp", ROWS, COLS);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0);
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(child));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals("Expected child to remain FOUT-only", 1, fOUTOnlyinputHops.size());
		assertEquals("Expected one FOUT-only FED forwarding entry", 1, fOUTOnlychildForwardingCostToFED.size());
		assertTrue("Transient TWrite/TRead boundary should add positive base DP FOUT->FED share",
			fOUTOnlychildForwardingCostToFED.get(0) > 0.0);
	}

	@Test
	public void testDpNonTransientCpfoutFedParentForwardingChargesUploadShare() throws Exception {
		DataOp localInput = transientRead("Xlocal", ROWS, COLS);
		UnaryOp child = new UnaryOp("localFout", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, localInput);
		UnaryOp parent = new UnaryOp("fedParent", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, child);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan childPlan = addCustomPlan(memoTable, childCommon,
			FederatedOutput.FOUT, ExecType.CP, FType.FULL, 10.0);
		FedPlan parentPlan = addCustomPlan(memoTable, parentCommon,
			FederatedOutput.LOUT, ExecType.FED, FType.FULL, 20.0);
		memoTable.registerHopRefs(hopCommonTable);

		double forwardingShare = invokeDpPlannerForwardingShare(
			true, FederatedOutput.FOUT, childPlan, parentPlan, 1);

		assertTrue("Non-transient CP/FOUT child consumed by FED parent should pay an upload/share cost",
			forwardingShare > 0.0);
	}

	@Test
	public void testDpChildCostBuffersChargeNonTransientCpfoutToFedShare() throws Exception {
		DataOp localInput = transientRead("Xlocal", ROWS, COLS);
		UnaryOp child = new UnaryOp("localFout", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, localInput);
		UnaryOp parent = new UnaryOp("fedParent", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, child);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.CP, FType.FULL, 10.0);
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(child));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals("Expected child to remain FOUT-only", 1, fOUTOnlyinputHops.size());
		assertEquals("Expected one FOUT-only FED forwarding entry", 1, fOUTOnlychildForwardingCostToFED.size());
		assertTrue("Non-transient CP/FOUT child consumed by FED parent should add positive base DP FOUT->FED share",
			fOUTOnlychildForwardingCostToFED.get(0) > 0.0);
	}

	@Test
	public void testDpFederatedInputFoutToCpDownloadShareAmortizesAcrossParents() throws Exception {
		DataOp fedInput = federatedRead("XfedShared", ROWS, COLS);
		UnaryOp cpParent = new UnaryOp("cpParent", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, fedInput);

		HopCommon childCommonShared = new HopCommon(fedInput, 1.0, 1.0, 1.0, 4, List.of());
		HopCommon childCommonSingle = new HopCommon(fedInput, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon parentCommon = new HopCommon(cpParent, 1.0, 1.0, 1.0, 1, List.of());

		FederatedPlannerDpMemoTable memoShared = new FederatedPlannerDpMemoTable();
		FedPlan sharedChildPlan = addCustomPlan(memoShared, childCommonShared,
			FederatedOutput.FOUT, ExecType.FED, FType.FULL, 10.0);
		FedPlan parentPlan = addCustomPlan(memoShared, parentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.FULL, 20.0);

		FederatedPlannerDpMemoTable memoSingle = new FederatedPlannerDpMemoTable();
		FedPlan singleChildPlan = addCustomPlan(memoSingle, childCommonSingle,
			FederatedOutput.FOUT, ExecType.FED, FType.FULL, 10.0);
		addCustomPlan(memoSingle, parentCommon, FederatedOutput.LOUT, ExecType.CP, FType.FULL, 20.0);

		double sharedDownload = invokeDpPlannerForwardingShare(
			false, FederatedOutput.FOUT, sharedChildPlan, parentPlan, 1);
		double singleDownload = invokeDpPlannerForwardingShare(
			false, FederatedOutput.FOUT, singleChildPlan, parentPlan, 1);

		assertTrue("Shared federated input download should be cheaper than single-parent charging",
			sharedDownload < singleDownload);
		assertEquals("Loop-invariant federated input download should amortize across parents",
			singleDownload / 4.0, sharedDownload, 1e-9);
	}

	@Test
	public void testDpChildCostBuffersAmortizeFederatedInputDownloadAcrossParents() throws Exception {
		DataOp child = federatedRead("XfedSharedChild", ROWS, COLS);
		UnaryOp parent = new UnaryOp("cpParent", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, child);

		HopCommon childCommonShared = new HopCommon(child, 1.0, 1.0, 1.0, 4, List.of());
		HopCommon childCommonSingle = new HopCommon(child, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon parentCommon = new HopCommon(parent, 1.0, 1.0, 1.0, 1, List.of());

		List<Double> sharedForwardToCp = collectDpFoutOnlyForwardingCostToCP(child, childCommonShared, parentCommon);
		List<Double> singleForwardToCp = collectDpFoutOnlyForwardingCostToCP(child, childCommonSingle, parentCommon);

		assertEquals(1, sharedForwardToCp.size());
		assertEquals(1, singleForwardToCp.size());
		assertTrue("Shared federated input child should pay smaller FOUT->CP download share",
			sharedForwardToCp.get(0) < singleForwardToCp.get(0));
		assertEquals("Child-cost buffer should amortize federated input download across parents",
			singleForwardToCp.get(0) / 4.0, sharedForwardToCp.get(0), 1e-9);
	}

	@Test
	public void testDpConflictCollectionIncludesCloneRootsForCostAggregation() throws Exception {
		DataOp origChild = transientRead("Xorig", ROWS, COLS);
		DataOp cloneChild = transientRead("Xclone", ROWS, COLS);
		UnaryOp origRoot = new UnaryOp("u_orig", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, origChild);
		UnaryOp cloneRoot = new UnaryOp("u_clone", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, cloneChild);
		LiteralOp one = new LiteralOp(1L);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origChildCommon = registerHopCommon(hopCommonTable, origChild);
		HopCommon cloneChildCommon = new HopCommon(cloneChild, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneChild.getHopID(), cloneChildCommon);
		HopCommon origRootCommon = registerHopCommon(hopCommonTable, origRoot);
		HopCommon cloneRootCommon = new HopCommon(cloneRoot, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneRoot.getHopID(), cloneRootCommon);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, one);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addSinglePlan(memoTable, origChildCommon, FederatedOutput.LOUT, ExecType.CP, null);
		addSinglePlan(memoTable, cloneChildCommon, FederatedOutput.LOUT, ExecType.CP, null);
		addPlanWithChildren(memoTable, origRootCommon, FederatedOutput.LOUT, ExecType.CP, null, 10.0,
			List.of(Pair.of(origChild.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, cloneRootCommon, FederatedOutput.LOUT, ExecType.CP, null, 10.0,
			List.of(Pair.of(cloneChild.getHopID(), FederatedOutput.LOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon, FederatedOutput.LOUT,
			ExecType.CP, null, 0.0, List.of());

		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(
			cloneChild.getHopID(), origChild.getHopID(),
			cloneRoot.getHopID(), origRoot.getHopID()));
		memoTable.registerAdditionalRootHopIDs(List.of(origRoot, cloneRoot));

		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, dummyRootPlan, new HashMap<>());
		Object entry = conflictMap.get(origChild.getHopID());
		assertNotNull("Expected conflict entry for original child hop", entry);

		java.lang.reflect.Field parentsField = entry.getClass().getDeclaredField("parents");
		parentsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<FedPlan> parents = (Set<FedPlan>) parentsField.get(entry);
		assertEquals("Original + clone roots should both contribute parent plans", 2, parents.size());

		java.lang.reflect.Field membersField = entry.getClass().getDeclaredField("memberHopIDs");
		membersField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<Long> memberHopIDs = (Set<Long>) membersField.get(entry);
		assertTrue("Conflict entry should include clone child member for cost aggregation",
			memberHopIDs.contains(cloneChild.getHopID()));
	}

	@Test
	public void testDpEnumerateFunctionPlaceholderIncludesMappedFunctionOutputs() throws Exception {
		DataOp fedInput = federatedRead("FX", ROWS, COLS);
		DataOp functionOutput = transientWrite("FY", ROWS, COLS);
		FunctionOp functionHop = new FunctionOp(FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE,
			"test_fun", new String[] {"X"}, List.of(fedInput), new String[] {"Y"}, true);
		functionHop.setDim1(ROWS);
		functionHop.setDim2(COLS);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon inputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon outputCommon = registerHopCommon(hopCommonTable, functionOutput);
		HopCommon functionCommon = registerHopCommon(hopCommonTable, functionHop);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addSinglePlan(memoTable, inputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW);
		addCustomPlan(memoTable, outputCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 100.0);
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(functionHop.getHopID(), List.of(functionOutput));
		Map<Long, Privacy> privacyMap = new HashMap<>();
		privacyMap.put(fedInput.getHopID(), Privacy.PUBLIC);
		privacyMap.put(functionOutput.getHopID(), Privacy.PUBLIC);
		privacyMap.put(functionHop.getHopID(), Privacy.PUBLIC);
		Map<Long, Set<Long>> uploadHints = new HashMap<>();
		Set<Long> unref = new HashSet<>();
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

		invokeEnumerateHop(functionHop, memoTable, hopCommonTable, rewireTable, privacyMap,
			uploadHints, unref, 1, oracle);

		FedPlan functionPlan = memoTable.getFedPlanAfterPrune(functionHop.getHopID(), FederatedOutput.FOUT);
		assertNotNull("Expected FED/FOUT function placeholder plan", functionPlan);
		assertTrue("Function placeholder must reference the mapped function output hop",
			functionPlan.getChildFedPlans().stream().anyMatch(edge -> edge.getKey() == functionOutput.getHopID()));
		assertTrue("Function placeholder cumulative cost should include mapped output cost attribution",
			functionPlan.getCumulativeCost() >= 100.0);
	}

	@Test
	public void testDpPlacementTransferWeightUsesComputeWeightAndMultiplicity() throws Exception {
		HopCommon hopCommon = new HopCommon(transientRead("X"), 2.0, 17.0, 3.5, 1, List.of());
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"placementTransferWeight", HopCommon.class);
		method.setAccessible(true);
		double weight = (double) method.invoke(null, hopCommon);
		assertEquals("Hop-local placement transfer weight must ignore networkWeight",
			7.0, weight, 1e-9);
	}

	@Test
	public void testDpOptionalInputAlwaysAddsFedForwarding() throws Exception {
		DataOp localSource = transientRead("LX");
		UnaryOp target = HopRewriteUtils.createUnary(localSource, OpOp1.EXP);
		target.setDim1(ROWS);
		target.setDim2(COLS);
		UnaryOp optionalParent = HopRewriteUtils.createUnary(target, OpOp1.BROADCAST);
		optionalParent.setDim1(ROWS);
		optionalParent.setDim2(COLS);

		Map<Long, FType> inputFTypes = new HashMap<>();
		Method requiresMethod = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"requiresFederatedInputForParent", Hop.class, Hop.class, int.class, Map.class);
		requiresMethod.setAccessible(true);
		boolean requiresFederatedInput = (boolean) requiresMethod.invoke(
			null, optionalParent, target, 0, inputFTypes);
		assertFalse("Expected BROADCAST input to be OPTIONAL for FED execution", requiresFederatedInput);

		Method shouldAddMethod = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"shouldAddFedForwardingForParentInput", Hop.class, Hop.class, int.class, Map.class, Map.class);
		shouldAddMethod.setAccessible(true);
		Map<Long, Set<Long>> uploadHints = new HashMap<>();
		boolean withoutHint = (boolean) shouldAddMethod.invoke(
			null, optionalParent, target, 0, inputFTypes, uploadHints);
		assertTrue("OPTIONAL input must add LOUT->FED forwarding for FED execution", withoutHint);
	}

	@Test
	public void testDpRewireProducesUploadHintForTransientReadOptionalInput() throws Exception {
		DataOp matrixInput = transientRead("M", ROWS, COLS);
		DataOp treadInput = transientRead("Y", 1, COLS);
		DataOp twriteSource = transientWrite("Y", 1, COLS);
		BinaryOp plusParent = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, matrixInput, treadInput);
		plusParent.setDim1(ROWS);
		plusParent.setDim2(COLS);

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(treadInput.getHopID(), new ArrayList<>(List.of(twriteSource)));
		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		registerHopCommon(hopCommonTable, plusParent);
		registerHopCommon(hopCommonTable, matrixInput);
		registerHopCommon(hopCommonTable, treadInput);
		registerHopCommon(hopCommonTable, twriteSource);

		Map<Long, Set<Long>> uploadHints = new HashMap<>();
		Method populateHintsMethod = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"populateParentChildUploadHintsFromRewire", Map.class, Map.class, Map.class);
		populateHintsMethod.setAccessible(true);
		populateHintsMethod.invoke(null, uploadHints, rewireTable, hopCommonTable);

		assertTrue("Expected producer to create parent-child upload hint from rewire",
			TransTableRewireUtils.hasParentChildUploadHint(
				uploadHints, plusParent.getHopID(), treadInput.getHopID()));

		int treadInputIndex = 1;
		Map<Long, FType> inputFTypes = new HashMap<>();
		Method requiresMethod = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"requiresFederatedInputForParent", Hop.class, Hop.class, int.class, Map.class);
		requiresMethod.setAccessible(true);
		boolean requiresFederatedInput = (boolean) requiresMethod.invoke(
			null, plusParent, treadInput, treadInputIndex, inputFTypes);
		assertFalse("Vector transient-read input should remain OPTIONAL for binary '+' FED exec",
			requiresFederatedInput);

		Method shouldAddMethod = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"shouldAddFedForwardingForParentInput", Hop.class, Hop.class, int.class, Map.class, Map.class);
		shouldAddMethod.setAccessible(true);
		boolean addForwarding = (boolean) shouldAddMethod.invoke(
			null, plusParent, treadInput, treadInputIndex, inputFTypes, uploadHints);
		assertTrue("Producer-generated upload hint must force LOUT->FED forwarding for OPTIONAL input", addForwarding);
	}

	@Test
	public void testUploadHintApiAcceptsZeroHopId() {
		Map<Long, Set<Long>> uploadHints = new HashMap<>();
		TransTableRewireUtils.markParentChildUploadHint(uploadHints, 0L, 7L);
		TransTableRewireUtils.markParentChildUploadHint(uploadHints, 8L, 0L);

		assertTrue("Parent hopId=0 should be treated as valid", TransTableRewireUtils.hasParentChildUploadHint(
			uploadHints, 0L, 7L));
		assertTrue("Child hopId=0 should be treated as valid", TransTableRewireUtils.hasParentChildUploadHint(
			uploadHints, 8L, 0L));
	}

	@Test
	public void testDpRewritePropagatesDerivedFedFoutFlag() throws Exception {
		DataOp child = transientRead("X");
		UnaryOp parent = HopRewriteUtils.createUnary(child, OpOp1.EXP);
		parent.setDim1(ROWS);
		parent.setDim2(COLS);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addSinglePlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW);

		FedPlanVariants parentVariants = new FedPlanVariants(parentCommon, FederatedOutput.FOUT);
		FedPlan parentPlan = new FedPlan(0.0, parentVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.FOUT)));
		parentPlan.setExecType(ExecType.FED);
		parentPlan.setFType(FType.ROW);
		parentPlan.setDerivedFedFout(true);
		parentVariants.addFedPlan(parentPlan);
		memoTable.addFedPlanVariants(parent.getHopID(), FederatedOutput.FOUT, parentVariants);
		memoTable.registerHopRefs(hopCommonTable);

		assertFalse("Precondition: derived marker should start unset", parent.isFederatedOutputDerived());
		invokeDpRewriteHop(new FederatedPlannerDpFedCostBased(), parentPlan, memoTable);
		assertEquals("Expected FED output on rewritten parent", FederatedOutput.FOUT, parent.getFederatedOutput());
		assertEquals("Expected FED exec on rewritten parent", ExecType.FED, parent.getForcedExecType());
		assertTrue("Derived FED/FOUT candidate must propagate to Hop rewrite state",
			parent.isFederatedOutputDerived());
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

	@Test
	public void testMinSTVectorWithoutRefedDoesNotForceBroadcast() throws Exception {
		DataOp left = transientRead("VX", 1, COLS);
		DataOp right = transientRead("VY", 1, COLS);
		BinaryOp plus = new BinaryOp("vplus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);
		plus.setDim1(1);
		plus.setDim2(COLS);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(left.getHopID(), FType.ROW);
		fTypeMap.put(right.getHopID(), FType.COL);
		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap = new HashMap<>();
		privacyMap.put(left.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
		privacyMap.put(right.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
		privacyMap.put(plus.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		Vertex vertex = invokeRewireHop(plus, graph, fTypeMap, privacyMap, oracle);

		assertNotNull("Expected vertex for vector plus", vertex);
		assertTrue("Refed-infeasible vector hop should not be forced to BROADCAST",
			vertex.getDataType() != FType.BROADCAST);
	}

	@Test
	public void testMinSTOptionalInputDoesNotMarkUploadHint() throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp fedAnchor = federatedRead("FX", ROWS, COLS);
			DataOp localSource = transientRead("LX");
			UnaryOp target = HopRewriteUtils.createUnary(localSource, OpOp1.EXP);
			target.setDim1(ROWS);
			target.setDim2(COLS);

			UnaryOp optionalParent = HopRewriteUtils.createUnary(target, OpOp1.BROADCAST);
			optionalParent.setDim1(ROWS);
			optionalParent.setDim2(COLS);

			UnaryOp requiredParent = HopRewriteUtils.createUnary(target, OpOp1.SQRT);
			requiredParent.setDim1(ROWS);
			requiredParent.setDim2(COLS);
			BinaryOp fedConsumer = HopRewriteUtils.createBinary(requiredParent, fedAnchor, OpOp2.PLUS);
			fedConsumer.setDim1(ROWS);
			fedConsumer.setDim2(COLS);
			fedConsumer.setForcedExecType(ExecType.FED);
			fedConsumer.setFederatedOutput(FederatedOutput.FOUT);

			Map<Long, FType> fTypeMap = new HashMap<>();
			fTypeMap.put(fedAnchor.getHopID(), FType.ROW);

			Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap = new HashMap<>();
			privacyMap.put(fedAnchor.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PRIVATE_AGGREGATE);
			privacyMap.put(localSource.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
			privacyMap.put(target.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
			privacyMap.put(optionalParent.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
			privacyMap.put(requiredParent.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
			privacyMap.put(fedConsumer.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PRIVATE_AGGREGATE);

			assertTrue("Target should be refed-feasible via required parent + fed consumer anchor",
				org.apache.sysds.hops.fedplanner.FederatedRefedPolicy.canGenerateCpfoutCandidate(target, fTypeMap));

			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

			Vertex fedAnchorVertex = invokeRewireHop(fedAnchor, graph, fTypeMap, privacyMap, oracle);
			Vertex targetVertex = invokeRewireHop(target, graph, fTypeMap, privacyMap, oracle);
			assertNotNull("Expected fed anchor vertex", fedAnchorVertex);
			assertNotNull("Expected target vertex", targetVertex);
			fedAnchorVertex.setMetadata(1.0, 1.0, List.of());
			targetVertex.setMetadata(1.0, 1.0, List.of());
			graph.addVertex(fedAnchorVertex);
			graph.addVertex(targetVertex);

			Vertex optionalParentVertex = invokeRewireHop(optionalParent, graph, fTypeMap, privacyMap, oracle);
			assertNotNull("Expected optional parent vertex", optionalParentVertex);
			assertFalse("OPTIONAL input should not create parent-child upload hint",
				graph.hasParentChildUploadHint(optionalParent.getHopID(), target.getHopID()));
			assertTrue("OPTIONAL parent should keep local execution candidate",
				optionalParentVertex.getCaps().allowCP_LOUT);
			assertFalse("OPTIONAL input should not force parent FED/FOUT via eager FED hint propagation",
				optionalParentVertex.getCaps().allowFED_FOUT);
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testMinSTRightIndexMainInputKeepsCpCandidateWithoutConcreteFoutPath() throws Exception {
		DataOp labels = transientRead("Y", 100000, 1);
		labels.setForcedExecType(ExecType.CP);
		labels.setFederatedOutput(FederatedOutput.LOUT);

		LinkedHashMap<String, Hop> rexArgs = new LinkedHashMap<>();
		rexArgs.put("target", labels);
		rexArgs.put("max", new LiteralOp(2));
		rexArgs.put("dir", new LiteralOp("rows"));
		rexArgs.put("cast", new LiteralOp(false));
		rexArgs.put("ignore", new LiteralOp(true));
		ParameterizedBuiltinOp rexpand = HopRewriteUtils.createParameterizedBuiltinOp(labels, rexArgs, ParamBuiltinOp.REXPAND);
		rexpand.setDim1(100000);
		rexpand.setDim2(2);
		rexpand.setForcedExecType(ExecType.CP);
		rexpand.setFederatedOutput(FederatedOutput.LOUT);

		IndexingOp rightIndex = HopRewriteUtils.createIndexingOp(rexpand, 1, 100000, 1, 1);
		rightIndex.setDim1(100000);
		rightIndex.setDim2(1);

		Map<Long, FType> fTypeMap = new HashMap<>();
		// Emulate logical type propagation: local-only REXPAND may still carry a ROW logical type.
		fTypeMap.put(rexpand.getHopID(), FType.ROW);
		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap = new HashMap<>();
		privacyMap.put(labels.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PRIVATE_AGGREGATE);
		privacyMap.put(rexpand.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PRIVATE_AGGREGATE);
		privacyMap.put(rightIndex.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PRIVATE_AGGREGATE);

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

		Vertex rexpandVertex = invokeRewireHop(rexpand, graph, fTypeMap, privacyMap, oracle);
		assertNotNull("Expected rexpand vertex", rexpandVertex);
		assertTrue("REXPAND should stay local-capable for this setup", rexpandVertex.getCaps().allowCP_LOUT);
		assertFalse("REXPAND should not expose CP->FOUT in this setup", rexpandVertex.getCaps().allowCP_FOUT);
		assertFalse("REXPAND should not expose FED/FOUT in this setup", rexpandVertex.getCaps().allowFED_FOUT);
		graph.addVertex(rexpandVertex);

		Vertex rightIndexVertex = invokeRewireHop(rightIndex, graph, fTypeMap, privacyMap, oracle);
		assertNotNull("Expected rightIndex vertex", rightIndexVertex);
		assertTrue("RightIndex should keep CP/LOUT when main input lacks concrete FOUT path",
			rightIndexVertex.getCaps().allowCP_LOUT);
	}

	@Test
	public void testMinSTFunctionPlacementRestrictionTargetsOnlyMultiReturnBuiltinHop() throws Exception {
		DataOp input = transientRead("X", 128, 64);
		FunctionOp multiReturn = new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, "builtin", "eigen",
			new String[] {"X"}, List.of(input), new String[] {"D", "V"}, false);
		DataOp functionOutput = new DataOp("out", DataType.MATRIX, ValueType.FP64,
			OpOpData.FUNCTIONOUTPUT, "out", 128, 64, -1, BLOCKSIZE);

		FederatedPlanMinSTGraph.ExecPlacementCaps multiReturnCaps = allowAllCaps();
		FederatedPlanMinSTGraph.ExecPlacementCaps directInputCaps = allowAllCaps();
		FederatedPlanMinSTGraph.ExecPlacementCaps functionOutputCaps = allowAllCaps();

		Method method = FederatedPlanMinSTRewire.class.getDeclaredMethod(
			"applyFunctionPlacementRestrictions", Hop.class, FederatedPlanMinSTGraph.ExecPlacementCaps.class);
		method.setAccessible(true);

		FederatedPlanMinSTGraph.ExecPlacementCaps restrictedMultiReturn =
			(FederatedPlanMinSTGraph.ExecPlacementCaps) method.invoke(null, multiReturn, multiReturnCaps);
		FederatedPlanMinSTGraph.ExecPlacementCaps directInputUnchanged =
			(FederatedPlanMinSTGraph.ExecPlacementCaps) method.invoke(null, input, directInputCaps);
		FederatedPlanMinSTGraph.ExecPlacementCaps functionOutputUnchanged =
			(FederatedPlanMinSTGraph.ExecPlacementCaps) method.invoke(null, functionOutput, functionOutputCaps);

		assertTrue(restrictedMultiReturn.allowCP_LOUT);
		assertFalse(restrictedMultiReturn.allowCP_FOUT);
		assertFalse(restrictedMultiReturn.allowFED_LOUT);
		assertFalse(restrictedMultiReturn.allowFED_FOUT);

		assertTrue("Direct input of MultiReturnBuiltin should keep normal candidate wiring",
			directInputUnchanged.allowFED_FOUT);
		assertTrue("FunctionOutput boundary should keep normal candidate wiring",
			functionOutputUnchanged.allowFED_FOUT);
	}

	@Test
	public void testMinSTFedInitTransientReadKeepsCpLoutCandidate() throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		try {
			FederatedPlannerUtils.registerFedInitVar("X");
			DataOp fedInitRead = transientRead("X", ROWS, COLS);
			FederatedPlanMinSTGraph.ExecPlacementCaps caps = allowAllCaps();

			Method method = FederatedPlanMinSTRewire.class.getDeclaredMethod(
				"applyTransientPlacementRestrictions", Hop.class, FederatedPlanMinSTGraph.ExecPlacementCaps.class);
			method.setAccessible(true);
			FederatedPlanMinSTGraph.ExecPlacementCaps restricted =
				(FederatedPlanMinSTGraph.ExecPlacementCaps) method.invoke(null, fedInitRead, caps);

			assertTrue("Fed-init TRead must keep CP/LOUT open for local materialization parity",
				restricted.allowCP_LOUT);
			assertFalse("TRead legality must continue to forbid CP/FOUT", restricted.allowCP_FOUT);
			assertFalse("TRead legality must continue to forbid FED/LOUT", restricted.allowFED_LOUT);
			assertTrue("Fed-init TRead must keep FED/FOUT available", restricted.allowFED_FOUT);
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testMinSTFedInitTransientReadCpCostUsesSharedDownload() throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		try {
			FederatedPlannerUtils.registerFedInitVar("X");
			DataOp fedInitRead = transientRead("X", ROWS, COLS);
			FederatedPlanMinSTGraph.ExecPlacementCaps caps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
			caps.allowCP_LOUT = true;
			caps.allowCP_FOUT = false;
			caps.allowFED_LOUT = false;
			caps.allowFED_FOUT = true;
			caps.fedFoutMode = FederatedPlanMinSTGraph.ExecPlacementCaps.FedFoutMode.NATIVE;

			Vertex vertex = new Vertex(fedInitRead, Privacy.PUBLIC, FType.FULL, FType.FULL, caps);
			vertex.setMetadata(30.0, 1.0, List.of());
			vertex.setCost(0.0, 0.0, 100.0);
			vertex.setNumParents(2);

			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			graph.addVertex(vertex);

			Method setVertexCost = FederatedPlanMinSTGraph.class.getDeclaredMethod("setVertexCost", Vertex.class);
			setVertexCost.setAccessible(true);
			setVertexCost.invoke(graph, vertex);

			Method addExecPlacementResultEdge = FederatedPlanMinSTGraph.class.getDeclaredMethod(
				"addExecPlacementResultEdge", Vertex.class);
			addExecPlacementResultEdge.setAccessible(true);
			addExecPlacementResultEdge.invoke(graph, vertex);

			Field leafedSourceField = FederatedPlanMinSTGraph.class.getDeclaredField("leafedSource");
			leafedSourceField.setAccessible(true);
			long leafedSource = leafedSourceField.getLong(null);

			Method getEdgeWeightOrZero = FederatedPlanMinSTGraph.class.getDeclaredMethod(
				"getEdgeWeightOrZero", long.class, long.class);
			getEdgeWeightOrZero.setAccessible(true);
			long cId = fedInitRead.getHopID() << 2;
			long lId = (fedInitRead.getHopID() << 2) | 2L;
			double cpUnaryCost = (double) getEdgeWeightOrZero.invoke(graph, leafedSource, cId);
			double localMaterialization = (double) getEdgeWeightOrZero.invoke(graph, cId, lId);
			double expectedSharedDownload = 100.0 / 30.0 / 2.0;

			assertEquals("Stable fed-init TRead should pay one shared local materialization cost",
				expectedSharedDownload, cpUnaryCost, 1e-9);
			assertEquals("Stable fed-init TRead result edge should use the same amortized local materialization cost",
				expectedSharedDownload, localMaterialization, 1e-9);
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testExecPlacementPolicyAllowsMultiReturnPrivacyException() {
		DataOp input = transientRead("X", 128, 64);
		DataOp functionOutput = new DataOp("out", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, "out");

		FunctionOp multiReturn = new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, "builtin", "eigen",
			new String[] {"X"}, List.of(input), new String[] {"D", "V"}, false);

		ExecPlacementPolicy.Decision privateFunctionDecision = ExecPlacementPolicy.decide(
			multiReturn, Privacy.PRIVATE, null, null);
		assertTrue("MULTIRETURN_BUILTIN must keep CP/LOUT under PRIVATE as runtime-safe exception",
			privateFunctionDecision.allowCP_LOUT);
		assertFalse(privateFunctionDecision.allowFED_FOUT);

		ExecPlacementPolicy.Decision privateOutDecision = ExecPlacementPolicy.decide(
			functionOutput, Privacy.PRIVATE, FType.ROW, null);
		assertTrue("FUNCTIONOUTPUT from MULTIRETURN_BUILTIN must stay locally materializable under PRIVATE",
			privateOutDecision.allowCP_LOUT);
		assertFalse(privateOutDecision.allowCP_FOUT);

		ExecPlacementPolicy.Decision publicOutDecision = ExecPlacementPolicy.decide(
			functionOutput, Privacy.PUBLIC, FType.ROW, null);
		assertTrue(publicOutDecision.allowCP_LOUT);
		assertFalse("FUNCTIONOUTPUT from MULTIRETURN_BUILTIN must not keep CP->FOUT even under PUBLIC",
			publicOutDecision.allowCP_FOUT);
	}

	@Test
	public void testMinSTConsistencyAcceptsCpfoutRegisteredOnTransientWriteParent() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp fedInput = federatedRead("X", ROWS, COLS);
			DataOp localVec = transientRead("v", COLS, 1);
			Hop mm = HopRewriteUtils.createMatrixMultiply(fedInput, localVec);
			mm.setDim1(ROWS);
			mm.setDim2(1);
			mm.setForcedExecType(ExecType.CP);
			mm.setFederatedOutput(FederatedOutput.FOUT);

			DataOp tWrite = HopRewriteUtils.createTransientWrite("Xd", mm);
			tWrite.setForcedExecType(ExecType.FED);
			tWrite.setFederatedOutput(FederatedOutput.FOUT);

			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			FederatedPlanMinSTGraph.ExecPlacementCaps caps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
			graph.addVertex(new Vertex(mm, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, FType.ROW, FType.ROW, caps));
			graph.addVertex(new Vertex(tWrite, Privacy.PUBLIC, FType.ROW, FType.ROW, caps));

			Map<Long, Pair<ExecType, FederatedOutput>> plannedExecOut = new HashMap<>();
			plannedExecOut.put(mm.getHopID(), Pair.of(ExecType.CP, FederatedOutput.FOUT));
			plannedExecOut.put(tWrite.getHopID(), Pair.of(ExecType.FED, FederatedOutput.FOUT));

			Map<Long, FType> plannedFTypeMap = new HashMap<>();
			plannedFTypeMap.put(fedInput.getHopID(), FType.ROW);
			plannedFTypeMap.put(mm.getHopID(), FType.ROW);
			plannedFTypeMap.put(tWrite.getHopID(), FType.ROW);

			// CP->FOUT can be registered on the TWrite parent (not necessarily on the producer hop).
			FederatedRefedRegistry.register(-1L, tWrite.getHopID(), fedInput.getHopID());

			invokeValidateMinstPlanConsistency(plannedExecOut, plannedFTypeMap, graph);
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testPruneInvalidCpfoutAnchorsRemovesStaleMaterializeForLocalTransientWrite() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp fedInput = federatedRead("X", ROWS, COLS);
			DataOp tWrite = HopRewriteUtils.createTransientWrite("Components", fedInput);
			tWrite.setDim1(ROWS);
			tWrite.setDim2(COLS);
			tWrite.setForcedExecType(ExecType.CP);
			tWrite.setFederatedOutput(FederatedOutput.FOUT); // stale raw candidate; final selection is local

			Map<Long, FType> plannedFTypes = new HashMap<>();
			plannedFTypes.put(fedInput.getHopID(), FType.ROW);
			plannedFTypes.put(tWrite.getHopID(), FType.ROW);

			FederatedFoutMaterializeRegistry.register(-1L, tWrite.getHopID(), fedInput.getHopID(),
				"ROW", "X", null);
			assertTrue(FederatedFoutMaterializeRegistry.snapshot(-1L).containsKey(tWrite.getHopID()));

			Method pruneMethod = FederatedRefedPolicy.class.getDeclaredMethod(
				"pruneInvalidCpfoutAnchors", List.class, Map.class, long.class);
			pruneMethod.setAccessible(true);
			boolean changed = (boolean) pruneMethod.invoke(null, List.of(tWrite, fedInput), plannedFTypes, -1L);

			assertTrue("Expected stale TWrite materialize candidate to be pruned after local final selection", changed);
			assertFalse("Local-final TWrite must not retain a fed_fout materialize registration",
				FederatedFoutMaterializeRegistry.snapshot(-1L).containsKey(tWrite.getHopID()));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDemoteStaleTransientWriteFederatedSelectionWhenNoFedNeedRemains() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp localInput = transientRead("ComponentsLocal", ROWS, COLS);
			localInput.setForcedExecType(ExecType.CP);
			localInput.setFederatedOutput(FederatedOutput.LOUT);

			DataOp tWrite = HopRewriteUtils.createTransientWrite("Components", localInput);
			tWrite.setDim1(ROWS);
			tWrite.setDim2(COLS);
			tWrite.setForcedExecType(ExecType.FED);
			tWrite.setFederatedOutput(FederatedOutput.FOUT);
			FederatedPlannerUtils.registerFedAnchorKey("Components", "VAR:Components|ROW");
			FederatedFoutMaterializeRegistry.register(-1L, tWrite.getHopID(), localInput.getHopID(),
				"ROW", "Components", "VAR:Components|ROW");

			Map<Long, FType> plannedFTypes = new HashMap<>();
			plannedFTypes.put(localInput.getHopID(), FType.ROW);
			plannedFTypes.put(tWrite.getHopID(), FType.ROW);

			boolean changed = invokeDemoteStaleTransientWriteFederatedSelections(
				List.of(tWrite, localInput), plannedFTypes, -1L, false);

			assertTrue("Expected stale transient-write FED/FOUT marker to be demoted", changed);
			assertEquals("Stale transient write must be repaired to CP", ExecType.CP, tWrite.getForcedExecType());
			assertEquals("Stale transient write must be repaired to local output",
				FederatedOutput.LOUT, tWrite.getFederatedOutput());
			assertFalse("Stale TWrite must not keep a fed_fout materialize registration",
				FederatedFoutMaterializeRegistry.snapshot(-1L).containsKey(tWrite.getHopID()));
			assertTrue("Stale local transient write should clear its anchor key",
				FederatedPlannerUtils.getFedAnchorKey("Components") == null);
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDemoteStaleTransientWriteKeepsLiveFedTransientReadConsumer() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp localInput = transientRead("ComponentsLocal", ROWS, COLS);
			localInput.setForcedExecType(ExecType.CP);
			localInput.setFederatedOutput(FederatedOutput.LOUT);

			DataOp tWrite = HopRewriteUtils.createTransientWrite("Components", localInput);
			tWrite.setDim1(ROWS);
			tWrite.setDim2(COLS);
			tWrite.setBeginLine(10);
			tWrite.setForcedExecType(ExecType.FED);
			tWrite.setFederatedOutput(FederatedOutput.FOUT);

			DataOp tRead = transientRead("Components", ROWS, COLS);
			tRead.setBeginLine(11);
			tRead.setForcedExecType(ExecType.FED);
			tRead.setFederatedOutput(FederatedOutput.FOUT);

			Map<Long, FType> plannedFTypes = new HashMap<>();
			plannedFTypes.put(localInput.getHopID(), FType.ROW);
			plannedFTypes.put(tWrite.getHopID(), FType.ROW);
			plannedFTypes.put(tRead.getHopID(), FType.ROW);

			boolean changed = invokeDemoteStaleTransientWriteFederatedSelections(
				List.of(tRead, tWrite, localInput), plannedFTypes, -1L, false);

			assertFalse("Live federated transient-read consumer should preserve the TWrite marker", changed);
			assertEquals(ExecType.FED, tWrite.getForcedExecType());
			assertEquals(FederatedOutput.FOUT, tWrite.getFederatedOutput());
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testMinSTRepairFixpointPropagatesFedInputDemotionToLinkedTransientRead() throws Exception {
		DataOp localInput = transientRead("Xin", ROWS, COLS);
		localInput.setForcedExecType(ExecType.CP);
		localInput.setFederatedOutput(FederatedOutput.LOUT);

		DataOp tWrite = HopRewriteUtils.createTransientWrite("X", localInput);
		tWrite.setDim1(ROWS);
		tWrite.setDim2(COLS);

		DataOp tRead = transientRead("X", ROWS, COLS);

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();

		FederatedPlanMinSTGraph.ExecPlacementCaps localCaps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		localCaps.allowCP_LOUT = true;
		graph.addVertex(new Vertex(localInput, Privacy.PUBLIC, null, null, localCaps));

		FederatedPlanMinSTGraph.ExecPlacementCaps twCaps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		twCaps.allowCP_LOUT = true;
		twCaps.allowFED_FOUT = true;
		graph.addVertex(new Vertex(tWrite, Privacy.PUBLIC, FType.ROW, FType.ROW, twCaps));

		FederatedPlanMinSTGraph.ExecPlacementCaps trCaps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		trCaps.allowCP_LOUT = true;
		trCaps.allowFED_FOUT = true;
		Vertex trVertex = new Vertex(tRead, Privacy.PUBLIC, FType.ROW, FType.ROW, trCaps);
		trVertex.setTransientWriteHopId(tWrite.getHopID());
		graph.addVertex(trVertex);

		Map<Long, ExecType> execSelection = new HashMap<>();
		Map<Long, FederatedOutput> outSelection = new HashMap<>();
		execSelection.put(localInput.getHopID(), ExecType.CP);
		outSelection.put(localInput.getHopID(), FederatedOutput.LOUT);
		execSelection.put(tWrite.getHopID(), ExecType.FED);
		outSelection.put(tWrite.getHopID(), FederatedOutput.FOUT);
		execSelection.put(tRead.getHopID(), ExecType.FED);
		outSelection.put(tRead.getHopID(), FederatedOutput.FOUT);

		invokeRepairSelectionFixpoint(graph, execSelection, outSelection);

		assertEquals("Unsatisfied local TWrite must demote to CP", ExecType.CP, execSelection.get(tWrite.getHopID()));
		assertEquals(FederatedOutput.LOUT, outSelection.get(tWrite.getHopID()));
		assertEquals("Linked TRead must follow repaired local TWrite", ExecType.CP, execSelection.get(tRead.getHopID()));
		assertEquals(FederatedOutput.LOUT, outSelection.get(tRead.getHopID()));
	}

	@Test
	public void testResolveTransReadChildrenPrefersDominatingTransientWriteOverStaleOuterMapping() {
		DataOp staleFedSource = federatedRead("X", ROWS, COLS);
		DataOp dominatingTWrite = HopRewriteUtils.createTransientWrite("X", transientRead("Xin", ROWS, COLS));
		dominatingTWrite.setBeginLine(10);
		DataOp tRead = transientRead("X", ROWS, COLS);
		tRead.setBeginLine(20);

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(tRead.getHopID(), new ArrayList<>(List.of(staleFedSource)));

		Map<String, List<Hop>> innerTransTable = new HashMap<>();
		innerTransTable.put("X", new ArrayList<>(List.of(dominatingTWrite)));

		List<Hop> resolved = TransTableRewireUtils.resolveTransReadChildren(
			tRead, rewireTable, innerTransTable, null, new ArrayList<>());

		assertNotNull(resolved);
		assertEquals("Dominating local TWrite should replace stale outer mapping", dominatingTWrite, resolved.get(0));
		assertEquals(dominatingTWrite, rewireTable.get(tRead.getHopID()).get(0));
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
		return transientRead(name, ROWS, COLS);
	}

	private static DataOp transientRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, rows, cols, rows * cols, BLOCKSIZE);
	}

	private static DataOp transientWrite(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTWRITE, null, rows, cols, rows * cols, BLOCKSIZE);
	}

	private static DataOp federatedRead(String name, long rows, long cols) {
		FederatedPlannerUtils.registerFedInitVar(name);
		DataOp op = transientRead(name, rows, cols);
		op.setForcedExecType(ExecType.FED);
		op.setFederatedOutput(FederatedOutput.FOUT);
		return op;
	}

	private static HopCommon registerHopCommon(Map<Long, HopCommon> table, Hop hop) {
		HopCommon common = new HopCommon(hop, 1.0, 1.0, 1.0, 1, List.of());
		table.put(hop.getHopID(), common);
		return common;
	}

	private static FedPlan addSinglePlan(FederatedPlannerDpMemoTable memoTable, HopCommon hopCommon,
			FederatedOutput fedOutType, ExecType execType, FType fType) {
		FedPlanVariants variants = new FedPlanVariants(hopCommon, fedOutType);
		FedPlan plan = new FedPlan(0.0, variants, List.of());
		plan.setExecType(execType);
		plan.setFType(fType);
		variants.addFedPlan(plan);
		memoTable.addFedPlanVariants(hopCommon.getHopRef().getHopID(), fedOutType, variants);
		return plan;
	}

	private static FedPlan addCustomPlan(FederatedPlannerDpMemoTable memoTable, HopCommon hopCommon,
			FederatedOutput fedOutType, ExecType execType, FType fType, double cumulativeCost) {
		FedPlanVariants variants = new FedPlanVariants(hopCommon, fedOutType);
		FedPlan plan = new FedPlan(cumulativeCost, variants, List.of());
		plan.setExecType(execType);
		plan.setFType(fType);
		variants.addFedPlan(plan);
		memoTable.addFedPlanVariants(hopCommon.getHopRef().getHopID(), fedOutType, variants);
		return plan;
	}

	private static FedPlan addPlanWithChildren(FederatedPlannerDpMemoTable memoTable, HopCommon hopCommon,
			FederatedOutput fedOutType, ExecType execType, FType fType, double cumulativeCost,
			List<Pair<Long, FederatedOutput>> childFedPlans) {
		FedPlanVariants variants = new FedPlanVariants(hopCommon, fedOutType);
		FedPlan plan = new FedPlan(cumulativeCost, variants, childFedPlans);
		plan.setExecType(execType);
		plan.setFType(fType);
		variants.addFedPlan(plan);
		memoTable.addFedPlanVariants(hopCommon.getHopRef().getHopID(), fedOutType, variants);
		return plan;
	}

	private static List<Double> collectDpFoutOnlyForwardingCostToCP(Hop child, HopCommon childCommon,
			HopCommon parentCommon) {
		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.FULL, 10.0);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(child));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		return fOUTOnlychildForwardingCostToCP;
	}

	private static void invokeEnumerateHop(Hop hop, FederatedPlannerDpMemoTable memoTable,
			Map<Long, HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap,
			Map<Long, Set<Long>> uploadHints, Set<Long> unref,
			int numWorkers, OracleFacade oracleFacade) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"enumerateHop", Hop.class, FederatedPlannerDpMemoTable.class, Map.class, Map.class,
			Map.class, Map.class, Set.class, int.class, OracleFacade.class);
		method.setAccessible(true);
		method.invoke(null, hop, memoTable, hopCommonTable, rewireTable, privacyMap,
			uploadHints, unref, numWorkers, oracleFacade);
	}

	private static double invokeDpPlannerForwardingShare(
			boolean parentIsFed, FederatedOutput childOut,
			FedPlan childPlan, FedPlan parentPlan, int numWorkers) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeParentChildForwardingCostShare",
			boolean.class, FederatedOutput.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			int.class);
		method.setAccessible(true);
		return (double) method.invoke(null, parentIsFed, childOut, childPlan, parentPlan, numWorkers);
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

	private static void invokeDpRewriteHop(FederatedPlannerDpFedCostBased planner, FedPlan plan,
			FederatedPlannerDpMemoTable memoTable) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"rewriteHop", FederatedPlannerDpMemoTable.FedPlan.class, FederatedPlannerDpMemoTable.class,
			Map.class, Set.class, Map.class);
		method.setAccessible(true);
		method.invoke(planner, plan, memoTable, new HashMap<Long, FederatedOutput>(),
			new HashSet<Long>(), new HashMap<Long, FType>());
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, ?> invokeCollectConflictsSingleBfs(FederatedPlannerDpMemoTable memoTable,
			FedPlan rootPlan, Map<Long, FederatedOutput> outputDecisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"collectConflictsSingleBFS",
			FederatedPlannerDpMemoTable.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			Map.class);
		method.setAccessible(true);
		return (Map<Long, ?>) method.invoke(null, memoTable, rootPlan, outputDecisions);
	}

	private static void invokeValidateMinstPlanConsistency(
			Map<Long, Pair<ExecType, FederatedOutput>> plannedExecOut,
			Map<Long, FType> plannedFTypeMap,
			FederatedPlanMinSTGraph graph) throws Exception {
		Method method = org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut.class
			.getDeclaredMethod("validateMinstPlanConsistency", Map.class, Map.class, FederatedPlanMinSTGraph.class);
		method.setAccessible(true);
		method.invoke(null, plannedExecOut, plannedFTypeMap, graph);
	}

	private static void invokeRepairSelectionFixpoint(FederatedPlanMinSTGraph graph,
			Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) throws Exception {
		Method method = FederatedPlanMinSTGraph.class.getDeclaredMethod(
			"repairSelectionFixpoint", Map.class, Map.class);
		method.setAccessible(true);
		method.invoke(graph, execSelection, outSelection);
	}

	private static boolean invokeDemoteStaleTransientWriteFederatedSelections(List<Hop> all,
			Map<Long, FType> fTypeMap, long sbId, boolean conditionalContext) throws Exception {
		Method method = FederatedRefedPolicy.class.getDeclaredMethod(
			"demoteStaleTransientWriteFederatedSelections", List.class, Map.class, long.class, boolean.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, all, fTypeMap, sbId, conditionalContext);
	}

	private static FederatedPlanMinSTGraph.ExecPlacementCaps allowAllCaps() {
		FederatedPlanMinSTGraph.ExecPlacementCaps caps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		caps.allowCP_LOUT = true;
		caps.allowCP_FOUT = true;
		caps.allowFED_LOUT = true;
		caps.allowFED_FOUT = true;
		caps.fedFoutMode = FederatedPlanMinSTGraph.ExecPlacementCaps.FedFoutMode.NATIVE;
		return caps;
	}
}
