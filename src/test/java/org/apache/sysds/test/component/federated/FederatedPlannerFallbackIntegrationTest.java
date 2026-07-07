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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
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
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.ParserWrapper;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.instructions.fed.QuaternaryFEDInstruction;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.runtime.instructions.FEDInstructionParser;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.jgrapht.graph.DefaultWeightedEdge;
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
	public void testDpOracleCpfoutFallbackForMixedLocalAndFederatedInputs() throws Exception {
		DataOp localLeft = transientRead("LocalLeft", ROWS, 1);
		DataOp fedRight = federatedRead("FedRight", ROWS, 1);
		BinaryOp cbind = new BinaryOp("cbindMixed", DataType.MATRIX, ValueType.FP64, OpOp2.CBIND, localLeft, fedRight);
		cbind.setDim1(ROWS);
		cbind.setDim2(2);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon localLeftCommon = registerHopCommon(hopCommonTable, localLeft);
		HopCommon fedRightCommon = registerHopCommon(hopCommonTable, fedRight);
		HopCommon cbindCommon = registerHopCommon(hopCommonTable, cbind);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan localLeftPlan = addCustomPlan(memoTable, localLeftCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 5.0);
		localLeftPlan.setCpFoutType(FType.ROW);
		addCustomPlan(memoTable, fedRightCommon, FederatedOutput.FOUT, ExecType.FED, FType.FULL, 7.0);
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap = new HashMap<>();
		privacyMap.put(localLeft.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
		privacyMap.put(fedRight.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);
		privacyMap.put(cbind.getHopID(), org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC);

		Map<Long, Set<Long>> uploadHints = new HashMap<>();
		Set<Long> unref = new HashSet<>();
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		invokeEnumerateHop(cbind, memoTable, hopCommonTable, rewireTable, privacyMap, uploadHints, unref, 1, oracle);

		FedPlan plan = memoTable.getFedPlanAfterPrune(cbind.getHopID(), FederatedOutput.FOUT);
		assertNotNull("Expected CP_FOUT plan for mixed local/federated cbind when oracle allows CP/FOUT", plan);
		FedPlanVariants variants = memoTable.getFedPlanVariants(Pair.of(cbind.getHopID(), FederatedOutput.FOUT));
		assertNotNull("Expected FOUT variants for mixed local/federated cbind", variants);
		assertFalse("Expected at least one FOUT variant for mixed local/federated cbind",
			variants.getFedPlanVariants().isEmpty());
	}

	@Test
	public void testPrivateAggregateDecisionKeepsCpfoutOpenForMaterializableMatrixHop() {
		DataOp localLeft = transientRead("LocalLeft", ROWS, 1);
		DataOp fedRight = federatedRead("FedRight", ROWS, 1);
		BinaryOp cbind = new BinaryOp("cbindPrivateAgg", DataType.MATRIX, ValueType.FP64, OpOp2.CBIND, localLeft, fedRight);
		cbind.setDim1(ROWS);
		cbind.setDim2(2);

		OpCaps oracleCaps = new OpCaps.Builder()
			.exec(ExecType.CP)
			.placement(FederatedOutput.LOUT)
			.reason(ReasonCode.NO_FED_INPUT)
			.build();

		ExecPlacementPolicy.Decision decision = ExecPlacementPolicy.decide(
			cbind, Privacy.PRIVATE_AGGREGATE, FType.ROW, oracleCaps);
		assertTrue("PRIVATE_AGGREGATE should keep CP/LOUT open", decision.allowCP_LOUT);
		assertTrue("PRIVATE_AGGREGATE should keep CP/FOUT open whenever the matrix hop has a concrete materializable FType",
			decision.allowCP_FOUT);
	}

	@Test
	public void testPrivateAggregateDecisionRequiresConcreteFTypeForCpfout() {
		DataOp localLeft = transientRead("LocalLeft", ROWS, 1);
		DataOp fedRight = federatedRead("FedRight", ROWS, 1);
		BinaryOp cbind = new BinaryOp("cbindPrivateAggUnknownFType", DataType.MATRIX, ValueType.FP64, OpOp2.CBIND,
			localLeft, fedRight);
		cbind.setDim1(ROWS);
		cbind.setDim2(2);

		OpCaps oracleCaps = new OpCaps.Builder()
			.exec(ExecType.CP)
			.placement(FederatedOutput.LOUT)
			.reason(ReasonCode.NO_FED_INPUT)
			.build();

		ExecPlacementPolicy.Decision decision = ExecPlacementPolicy.decide(
			cbind, Privacy.PRIVATE_AGGREGATE, null, oracleCaps);
		assertTrue("PRIVATE_AGGREGATE should keep CP/LOUT open", decision.allowCP_LOUT);
		assertFalse("PRIVATE_AGGREGATE should not expose CP/FOUT without a concrete materializable FType hint",
			decision.allowCP_FOUT);
	}

	@Test
	public void testMinSTBuildExecPlacementCapsUsesSharedPrivateAggregateCpfoutPolicy() throws Exception {
		DataOp localLeft = transientRead("LocalLeft", ROWS, 1);
		DataOp fedRight = federatedRead("FedRight", ROWS, 1);
		BinaryOp cbind = new BinaryOp("cbindPrivateAggMinST", DataType.MATRIX, ValueType.FP64, OpOp2.CBIND,
			localLeft, fedRight);
		cbind.setDim1(ROWS);
		cbind.setDim2(2);

		OpCaps oracleCaps = new OpCaps.Builder()
			.exec(ExecType.CP)
			.placement(FederatedOutput.LOUT)
			.reason(ReasonCode.NO_FED_INPUT)
			.build();
		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(fedRight.getHopID(), FType.ROW);

		FederatedPlanMinSTGraph.ExecPlacementCaps caps = invokeBuildExecPlacementCaps(
			cbind, Privacy.PRIVATE_AGGREGATE, FType.ROW, oracleCaps, fTypeMap);
		assertTrue("Shared PRIVATE_AGGREGATE policy should keep CP/LOUT open for MinST as well",
			caps.allowCP_LOUT);
		assertTrue("MinST should inherit CP/FOUT from the shared PRIVATE_AGGREGATE policy when materialization is feasible",
			caps.allowCP_FOUT);
	}

	@Test
	public void testDpContextualFTypeMapKeepsLocalCpFoutHintForNonTransientPlan() throws Exception {
		DataOp left = transientRead("B", 1, COLS);
		DataOp right = transientRead("S", 1, COLS);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);
		plus.setDim1(1);
		plus.setDim2(COLS);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon plusCommon = registerHopCommon(hopCommonTable, plus);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, transientRead("Root", 1, 1));

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan plusPlan = addPlanWithChildren(memoTable, plusCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.BROADCAST, 0.0, List.of());
		plusPlan.setCpFoutType(FType.ROW);
		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerAdditionalRootHopIDs(List.of(plus));

		FedPlanVariants rootVariants = new FedPlanVariants(dummyRootCommon, FederatedOutput.LOUT);
		FedPlan rootPlan = new FedPlan(0.0, rootVariants, List.of());
		rootPlan.setExecType(ExecType.CP);
		rootPlan.setFType(FType.BROADCAST);

		Map<Long, FType> contextualFTypeMap = invokeBuildContextuallyFeasibleDecisionFTypeMap(
			memoTable, rootPlan, Map.of(plus.getHopID(), FederatedOutput.LOUT));

		assertEquals("Expected CP-local non-transient plan to keep its downstream cpFoutType hint",
			FType.ROW, contextualFTypeMap.get(plus.getHopID()));
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

		assertEquals("Direct FED input -> FED/FOUT transient-write alias should remain metadata-like"
			+ " during output-decision forwarding reconciliation",
			0.0, forwardingShare, 1e-9);
	}

	@Test
	public void testDpDirectFederatedInputTransientReadForwardingSkipsRefedShare() throws Exception {
		DataOp fedInput = federatedRead("XfedDirect", ROWS, COLS);
		DataOp tRead = transientRead("Xalias", ROWS, COLS);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon inputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tReadCommon = registerHopCommon(hopCommonTable, tRead);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan inputPlan = addCustomPlan(memoTable, inputCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0);
		FedPlan tReadPlan = addCustomPlan(memoTable, tReadCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 20.0);

		double plannerForwardingShare = invokeDpPlannerForwardingShare(
			true, FederatedOutput.FOUT, inputPlan, tReadPlan, 1);
		double enumeratorForwardingShare = invokeDpEnumeratorForwardingShare(
			true, FederatedOutput.FOUT, inputPlan, tReadPlan, 1);

		assertEquals("Direct FEDERATED/FedInit source -> TRANSIENTREAD should stay metadata-like in"
			+ " output-decision forwarding reconciliation",
			0.0, plannerForwardingShare, 1e-9);
		assertEquals("Enumerator parity should match planner helper for direct FED->TRead boundary",
			0.0, enumeratorForwardingShare, 1e-9);
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
	public void testDpChildCostBuffersSkipDirectFederatedInputTransientReadRefedShare() throws Exception {
		DataOp child = federatedRead("XfedDirectChild", ROWS, COLS);
		DataOp parent = transientRead("XaliasChild", ROWS, COLS);
		parent.getInput().add(child);
		child.getParent().add(parent);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0);
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>(List.of(child));
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>();
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertTrue("Direct FED->TRead child should remain FOUT-only in the child-cost buffers",
			fOUTOnlyinputHops.contains(child));
		assertEquals("Expected one FOUT-only FED forwarding entry", 1, fOUTOnlychildForwardingCostToFED.size());
		assertEquals("Direct FEDERATED/FedInit source -> TRANSIENTREAD should not pay base DP"
			+ " FOUT->FED refed share", 0.0, fOUTOnlychildForwardingCostToFED.get(0), 1e-9);
	}

	@Test
	public void testDpChildCostBuffersSkipStableFederatedProducerTransientWriteRefedShare() throws Exception {
		DataOp fedInput = federatedRead("XfedStableProducer", ROWS, COLS);
		UnaryOp producer = new UnaryOp("stableProducer", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, fedInput);
		DataOp parent = transientWrite("XstableAlias", ROWS, COLS);
		parent.getInput().add(producer);
		producer.getParent().add(parent);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon producerCommon = registerHopCommon(hopCommonTable, producer);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0);
		addPlanWithChildren(memoTable, producerCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 20.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>(List.of(producer));
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>();
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertTrue("Stable federated producer should remain FOUT-only in the child-cost buffers",
			fOUTOnlyinputHops.contains(producer));
		assertEquals("Expected one FOUT-only FED forwarding entry", 1, fOUTOnlychildForwardingCostToFED.size());
		assertEquals("Stable federated producer -> TRANSIENTWRITE alias should not pay extra base DP"
			+ " FOUT->FED refed share", 0.0, fOUTOnlychildForwardingCostToFED.get(0), 1e-9);
	}

	@Test
	public void testDpChildCostBuffersAmortizeStableTransientReadLoopLocalMaterialization() throws Exception {
		DataOp fedInput = federatedRead("XfedStableLoop", ROWS, COLS);
		DataOp tWrite = transientWrite("XstableLoop", ROWS, COLS);
		DataOp tRead = transientRead("XstableLoop", ROWS, COLS);
		UnaryOp parent = new UnaryOp("loopConsumer", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, tRead);
		tRead.getParent().add(parent);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tWriteCommon = registerHopCommon(hopCommonTable, tWrite);
		HopCommon tReadCommon = new HopCommon(tRead, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon parentCommon = new HopCommon(parent, 1.0, 1.0, 29.0, 1, List.of());
		hopCommonTable.put(tRead.getHopID(), tReadCommon);
		hopCommonTable.put(parent.getHopID(), parentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0);
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 20.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 30.0,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(tRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals("Expected one FOUT-only CP-materialization entry", 1, fOUTOnlychildForwardingCostToCP.size());
		double baseDownload = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead), FType.ROW, 1);
		assertEquals("Stable federated-input TRANSIENTREAD should amortize loop-local materialization"
			+ " back to one download instead of opWeight*download",
			baseDownload, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
	}

	@Test
	public void testDpChildCostBuffersAmortizeTransientReadLoopLocalMaterializationFromFoutTWrite() throws Exception {
		DataOp tWrite = transientWrite("Yloop", ROWS, COLS);
		DataOp tRead = transientRead("Yloop", ROWS, COLS);
		UnaryOp parent = new UnaryOp("loopConsumerFromTWrite", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, tRead);
		tRead.getParent().add(parent);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon tWriteCommon = registerHopCommon(hopCommonTable, tWrite);
		HopCommon tReadCommon = new HopCommon(tRead, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon parentCommon = new HopCommon(parent, 1.0, 1.0, 29.0, 1, List.of());
		hopCommonTable.put(tRead.getHopID(), tReadCommon);
		hopCommonTable.put(parent.getHopID(), parentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 20.0, List.of());
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 30.0,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(tRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals("Expected one FOUT-only CP-materialization entry", 1, fOUTOnlychildForwardingCostToCP.size());
		double baseDownload = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead), FType.ROW, 1);
		assertEquals("FOUT TRANSIENTWRITE-backed TRANSIENTREAD should amortize loop-local materialization"
			+ " back to one download instead of parentMultiplicity*download",
			baseDownload, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
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
	public void testDpForwardingShareUsesCpFoutTypeForLocalLoutChild() throws Exception {
		DataOp localInput = transientRead("XlocalLout", ROWS, COLS);
		UnaryOp child = new UnaryOp("localLout", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, localInput);
		UnaryOp parent = new UnaryOp("fedParentLout", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, child);

		HopCommon childCommon = new HopCommon(child, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon parentCommon = new HopCommon(parent, 1.0, 1.0, 1.0, 1, List.of());

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan childPlan = addCustomPlan(memoTable, childCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.BROADCAST, 10.0);
		FedPlan parentPlan = addCustomPlan(memoTable, parentCommon,
			FederatedOutput.LOUT, ExecType.FED, FType.FULL, 20.0);

		childPlan.setCpFoutType(FType.ROW);
		double plannerRowShare = invokeDpPlannerForwardingShare(
			true, FederatedOutput.LOUT, childPlan, parentPlan, 4);
		double enumeratorRowShare = invokeDpEnumeratorForwardingShare(
			true, FederatedOutput.LOUT, childPlan, parentPlan, 4);

		childPlan.setCpFoutType(null);
		double plannerBroadcastShare = invokeDpPlannerForwardingShare(
			true, FederatedOutput.LOUT, childPlan, parentPlan, 4);
		double enumeratorBroadcastShare = invokeDpEnumeratorForwardingShare(
			true, FederatedOutput.LOUT, childPlan, parentPlan, 4);

		assertTrue("Planner forwarding share should honor narrower cpFoutType than logical BROADCAST",
			plannerRowShare < plannerBroadcastShare);
		assertTrue("Enumerator forwarding share should honor narrower cpFoutType than logical BROADCAST",
			enumeratorRowShare < enumeratorBroadcastShare);
	}

	@Test
	public void testDpPromoteLocalFedInputHintsUsesCpFoutTypeWhenAnchorExists() throws Exception {
		DataOp fedInput = federatedRead("XfedAnchor", ROWS, COLS);
		UnaryOp localVec = new UnaryOp("localVec", DataType.MATRIX, ValueType.FP64, OpOp1.EXP,
			transientRead("localVecIn", ROWS, 1));

		HopCommon fedInputCommon = new HopCommon(fedInput, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon localVecCommon = new HopCommon(localVec, 1.0, 1.0, 1.0, 1, List.of());

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan fedPlan = addCustomPlan(memoTable, fedInputCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.FULL, 10.0);
		FedPlan localPlan = addCustomPlan(memoTable, localVecCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.BROADCAST, 5.0);
		localPlan.setCpFoutType(FType.ROW);

		List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();
		planChilds.add(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT));
		planChilds.add(Pair.of(localVec.getHopID(), FederatedOutput.LOUT));

		List<Hop> collectedHops = new ArrayList<>(List.of(fedInput, localVec));
		List<FType> collectedFTypes = new ArrayList<>();
		collectedFTypes.add(fedPlan.getFType());
		collectedFTypes.add(null);
		Map<Long, FType> fedInputTypeMap = new HashMap<>();
		fedInputTypeMap.put(fedInput.getHopID(), fedPlan.getFType());

		invokePromoteLocalFedInputHints(memoTable, planChilds, collectedHops, collectedFTypes, fedInputTypeMap);

		assertEquals("Local ROW cpFoutType should become an oracle/planning hint once a federated anchor exists",
			FType.ROW, collectedFTypes.get(1));
		assertEquals("Local ROW cpFoutType should be visible to FED-input feasibility checks",
			FType.ROW, fedInputTypeMap.get(localVec.getHopID()));
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
	public void testDpLoutToFedUploadShareUsesParentDemandWeights() throws Exception {
		DataOp localInput = transientRead("XsharedUploadIn", ROWS, COLS);
		UnaryOp child = new UnaryOp("sharedUploadChild", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, localInput);
		UnaryOp currentFedParent = new UnaryOp("currentFedParent", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, child);
		UnaryOp hotSiblingParent = new UnaryOp("hotSiblingParent", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, child);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = new HopCommon(child, 1.0, 1.0, 1.0, 2, List.of());
		HopCommon currentParentCommon = new HopCommon(currentFedParent, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon hotParentCommon = new HopCommon(hotSiblingParent, 1.0, 1.0, 9.0, 1, List.of());
		hopCommonTable.put(child.getHopID(), childCommon);
		hopCommonTable.put(currentFedParent.getHopID(), currentParentCommon);
		hopCommonTable.put(hotSiblingParent.getHopID(), hotParentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.LOUT, ExecType.CP, FType.FULL, 10.0);
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>(List.of(child));
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>();
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(currentParentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals("Expected child to move to LOUT-only inputs", 1, lOUTOnlyinputHops.size());
		assertEquals(1, lOUTOnlychildForwardingCostToFED.size());
		double uploadCost = FederatedCostModel.computeUploadNetworkCost(
			FederatedCostModel.getEffectiveUploadMemEstimate(child), FType.FULL, 1)
			+ FederatedCostModel.computeLocalToFedForwardingPenalty(FType.FULL, 1);
		assertEquals("LOUT->FED upload should be split by parent demand weights",
			uploadCost / 10.0, lOUTOnlychildForwardingCostToFED.get(0), 1e-9);
	}

	@Test
	public void testDpFoutToCpDownloadShareUsesSameParentDemandWeights() throws Exception {
		DataOp fedInput = federatedRead("XsharedDownload", ROWS, COLS);
		UnaryOp currentCpParent = new UnaryOp("currentCpParent", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, fedInput);
		UnaryOp hotSiblingParent = new UnaryOp("hotCpSiblingParent", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, fedInput);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = new HopCommon(fedInput, 1.0, 1.0, 1.0, 2, List.of());
		HopCommon currentParentCommon = new HopCommon(currentCpParent, 1.0, 1.0, 1.0, 1, List.of());
		HopCommon hotParentCommon = new HopCommon(hotSiblingParent, 1.0, 1.0, 9.0, 1, List.of());
		hopCommonTable.put(fedInput.getHopID(), childCommon);
		hopCommonTable.put(currentCpParent.getHopID(), currentParentCommon);
		hopCommonTable.put(hotSiblingParent.getHopID(), hotParentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.FULL, 10.0);
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(fedInput));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(currentParentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(fedInput), FType.FULL, 1);
		assertEquals("FOUT->CP download should use the same shared boundary formula as upload",
			downloadCost / 10.0, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
	}

	@Test
	public void testDpStableTransientReadFoutToCpDownloadCapsLoopMaterialization() throws Exception {
		DataOp fedInput = federatedRead("XloopMatSource", ROWS, COLS);
		DataOp tRead = transientRead("XloopMat", ROWS, COLS);
		UnaryOp currentCpParent = new UnaryOp("loopCpParent", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, tRead);
		tRead.getInput().add(fedInput);
		fedInput.getParent().add(tRead);

		List<Pair<Long, Double>> loopContext = List.of(Pair.of(99L, 9.0));
		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tReadCommon = new HopCommon(tRead, 0.5, 0.5, 9.0, 1, loopContext);
		HopCommon parentCommon = new HopCommon(currentCpParent, 0.5, 0.5, 9.0, 1, loopContext);
		hopCommonTable.put(tRead.getHopID(), tReadCommon);
		hopCommonTable.put(currentCpParent.getHopID(), parentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(tRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead), FType.ROW, 1);
		assertEquals("Stable federated-input TRANSIENTREAD local materialization should be charged once,"
				+ " not once per loop/clone placement occurrence",
				downloadCost, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
	}

	@Test
	public void testDpRepeatedAggBinaryFoutToCpAddsLocalAccessDemand() throws Exception {
		DataOp fedInput = federatedRead("XloopAggSource", ROWS, COLS);
		DataOp tRead = transientRead("XloopAgg", ROWS, COLS);
		DataOp localLeft = transientRead("PloopAgg", COLS, ROWS);
		tRead.getInput().add(fedInput);
		fedInput.getParent().add(tRead);
		AggBinaryOp repeatedAgg = new AggBinaryOp("loopAgg", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, localLeft, tRead);
		tRead.getParent().add(repeatedAgg);

		double demand = 9.0;
		List<Pair<Long, Double>> loopContext = List.of(Pair.of(99L, demand));
		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tReadCommon = new HopCommon(tRead, 1.0, 1.0, demand, 1, loopContext);
		HopCommon parentCommon = new HopCommon(repeatedAgg, 1.0, 1.0, demand, 1, loopContext);
		hopCommonTable.put(tRead.getHopID(), tReadCommon);
		hopCommonTable.put(repeatedAgg.getHopID(), parentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(tRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead), FType.ROW, 1);
		double localAccessDemand = demand * FederatedCostModel.computeMemoryAccessCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead));
		assertEquals("Repeated CP-local AggBinary over a stable FED/FOUT input should charge"
				+ " one materialization plus per-demand local MatrixObject acquire/scan",
			downloadCost + localAccessDemand, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
	}

	@Test
	public void testDpRepeatedAggUnaryFoutToCpAddsLocalAccessDemand() throws Exception {
		DataOp fedInput = federatedRead("XloopAggUnarySource", ROWS, COLS);
		DataOp tRead = transientRead("XloopAggUnary", ROWS, COLS);
		tRead.getInput().add(fedInput);
		fedInput.getParent().add(tRead);
		AggUnaryOp repeatedAgg = new AggUnaryOp("loopAggUnary", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM_SQ, Direction.Row, tRead);
		tRead.getParent().add(repeatedAgg);

		double demand = 9.0;
		List<Pair<Long, Double>> loopContext = List.of(Pair.of(99L, demand));
		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tReadCommon = new HopCommon(tRead, 1.0, 1.0, demand, 1, loopContext);
		HopCommon parentCommon = new HopCommon(repeatedAgg, 1.0, 1.0, demand, 1, loopContext);
		hopCommonTable.put(tRead.getHopID(), tReadCommon);
		hopCommonTable.put(repeatedAgg.getHopID(), parentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(tRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead), FType.ROW, 1);
		double localAccessDemand = demand * FederatedCostModel.computeMemoryAccessCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead));
		assertEquals("Repeated CP-local AggUnary over a stable FED/FOUT input should charge"
				+ " one materialization plus per-demand local MatrixObject acquire/scan",
			downloadCost + localAccessDemand, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
	}

	@Test
	public void testDpRepeatedBinaryFoutToCpAddsLocalAccessDemand() throws Exception {
		DataOp fedInput = federatedRead("XloopBinarySource", ROWS, COLS);
		DataOp tRead = transientRead("XloopBinary", ROWS, COLS);
		DataOp localRight = transientRead("YloopBinary", ROWS, COLS);
		tRead.getInput().add(fedInput);
		fedInput.getParent().add(tRead);
		UnaryOp computedFout = new UnaryOp("computedFoutForBinary", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, tRead);
		tRead.getParent().add(computedFout);
		BinaryOp repeatedBinary = new BinaryOp("loopBinaryPlus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, computedFout, localRight);
		computedFout.getParent().add(repeatedBinary);

		double demand = 9.0;
		List<Pair<Long, Double>> loopContext = List.of(Pair.of(99L, demand));
		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tReadCommon = new HopCommon(tRead, 1.0, 1.0, demand, 1, loopContext);
		HopCommon computedCommon = new HopCommon(computedFout, 1.0, 1.0, demand, 1, loopContext);
		HopCommon parentCommon = new HopCommon(repeatedBinary, 1.0, 1.0, demand, 1, loopContext);
		hopCommonTable.put(tRead.getHopID(), tReadCommon);
		hopCommonTable.put(computedFout.getHopID(), computedCommon);
		hopCommonTable.put(repeatedBinary.getHopID(), parentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, computedCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(tRead.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(computedFout));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(computedFout), FType.ROW, 1);
		double localAccessDemand = demand * Math.max(
			FederatedCostModel.computeMemoryAccessCost(FederatedCostModel.getEffectiveOutputMemEstimate(computedFout)),
			FederatedCostModel.computeFedCoordinationCost(1));
		assertEquals("Repeated CP-local BinaryOp over a computed FED/FOUT input should charge per-demand"
				+ " boundary transfer plus per-demand local MatrixObject acquire/scan",
			demand * downloadCost + localAccessDemand, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
	}

	@Test
	public void testDpAggBinaryUsesPriorSiblingLocalCacheForStableInputWithAccessFloor() throws Exception {
		DataOp fedInput = federatedRead("XaggNoCacheSource", ROWS, COLS);
		DataOp priorRead = transientRead("XaggNoCache", ROWS, COLS);
		DataOp laterRead = transientRead("XaggNoCache", ROWS, COLS);
		priorRead.getInput().add(fedInput);
		laterRead.getInput().add(fedInput);
		fedInput.getParent().add(priorRead);
		fedInput.getParent().add(laterRead);

		UnaryOp priorCpParent = new UnaryOp("priorCpMaterializerForAgg", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, priorRead);
		priorCpParent.setBeginLine(112);
		DataOp localLeft = transientRead("PaggNoCache", COLS, ROWS);
		AggBinaryOp laterAggParent = new AggBinaryOp("laterAggNoCache", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, localLeft, laterRead);
		laterAggParent.setBeginLine(210);
		priorRead.getParent().add(priorCpParent);
		laterRead.getParent().add(laterAggParent);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon priorReadCommon = registerHopCommon(hopCommonTable, priorRead);
		HopCommon laterReadCommon = registerHopCommon(hopCommonTable, laterRead);
		HopCommon priorParentCommon = registerHopCommon(hopCommonTable, priorCpParent);
		HopCommon laterParentCommon = registerHopCommon(hopCommonTable, laterAggParent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, priorReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, laterReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, priorParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(priorRead.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(laterRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(laterParentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double cachedLocalAccessCost = Math.max(
			FederatedCostModel.computeMemoryAccessCost(
				FederatedCostModel.getEffectiveOutputMemEstimate(laterRead)),
			FederatedCostModel.computeFedCoordinationCost(1));
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(laterRead), FType.ROW, 1);
		assertEquals("A source-ordered CP-local AggBinary over a stable FED/FOUT input should"
				+ " reuse the prior sibling's local MatrixObject for network transfer while still"
				+ " paying the cached local acquire/scan floor",
			cachedLocalAccessCost, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
		assertTrue("Cached aggregate access must remain cheaper than a duplicate worker-to-coordinator download",
			fOUTOnlychildForwardingCostToCP.get(0) < downloadCost);
	}

	@Test
	public void testDpWdivmmUsesPriorSiblingLocalCacheForStableInputWithLoopDemand() throws Exception {
		DataOp fedInput = federatedRead("XwdivmmCacheSource", ROWS, COLS);
		DataOp priorRead = transientRead("XwdivmmCache", ROWS, COLS);
		DataOp laterRead = transientRead("XwdivmmCache", ROWS, COLS);
		priorRead.getInput().add(fedInput);
		laterRead.getInput().add(fedInput);
		fedInput.getParent().add(priorRead);
		fedInput.getParent().add(laterRead);

		UnaryOp priorCpParent = new UnaryOp("priorCpMaterializerForWdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, priorRead);
		priorCpParent.setBeginLine(112);
		DataOp localU = transientRead("UwdivmmCache", ROWS, 2);
		DataOp localV = transientRead("VwdivmmCache", COLS, 2);
		QuaternaryOp laterWdivmmParent = new QuaternaryOp("laterWdivmmCache", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, laterRead, localU, localV, new LiteralOp(-1), 1, false, false);
		laterWdivmmParent.setBeginLine(114);
		priorRead.getParent().add(priorCpParent);
		laterRead.getParent().add(laterWdivmmParent);

		double demand = 9.5;
		List<Pair<Long, Double>> loopContext = List.of(Pair.of(99L, demand));
		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon priorReadCommon = registerHopCommon(hopCommonTable, priorRead);
		HopCommon laterReadCommon = new HopCommon(laterRead, 1.0, 1.0, demand, 1, loopContext);
		HopCommon priorParentCommon = registerHopCommon(hopCommonTable, priorCpParent);
		HopCommon laterParentCommon = new HopCommon(laterWdivmmParent, 1.0, 1.0, demand, 1, loopContext);
		hopCommonTable.put(laterRead.getHopID(), laterReadCommon);
		hopCommonTable.put(laterWdivmmParent.getHopID(), laterParentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, priorReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, laterReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, priorParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(priorRead.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(laterRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(laterParentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 2);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double cachedLocalAccessCost = Math.max(
			FederatedCostModel.computeMemoryAccessCost(
				FederatedCostModel.getEffectiveOutputMemEstimate(laterRead)),
			FederatedCostModel.computeFedCoordinationCost(1));
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(laterRead), FType.ROW, 2);
		assertEquals("A later looped CP WDIVMM over the same stable FED/FOUT input should"
				+ " reuse the prior sibling materialization and pay only per-demand local access",
			cachedLocalAccessCost * demand, fOUTOnlychildForwardingCostToCP.get(0), 1e-9);
		assertTrue("Looped WDIVMM cached access must remain cheaper than repeated federated downloads",
			fOUTOnlychildForwardingCostToCP.get(0) < downloadCost * demand);
	}

	@Test
	public void testDpStableTransientReadPriorSiblingLocalCacheSkipsLaterDownload() throws Exception {
		DataOp fedInput = federatedRead("XpriorLocalCacheSource", ROWS, COLS);
		DataOp priorRead = transientRead("XpriorLocalCache", ROWS, COLS);
		DataOp laterRead = transientRead("XpriorLocalCache", ROWS, COLS);
		priorRead.getInput().add(fedInput);
		laterRead.getInput().add(fedInput);
		fedInput.getParent().add(priorRead);
		fedInput.getParent().add(laterRead);

		UnaryOp priorCpParent = new UnaryOp("priorCpMaterializer", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, priorRead);
		UnaryOp laterCpParent = new UnaryOp("laterCpConsumer", DataType.MATRIX, ValueType.FP64,
			OpOp1.SQRT, laterRead);
		priorCpParent.setBeginLine(112);
		laterCpParent.setBeginLine(114);
		priorRead.getParent().add(priorCpParent);
		laterRead.getParent().add(laterCpParent);

		List<Pair<Long, Double>> loopContext = List.of(Pair.of(99L, 9.0));
		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon priorReadCommon = new HopCommon(priorRead, 0.5, 0.5, 9.0, 1, loopContext);
		HopCommon laterReadCommon = new HopCommon(laterRead, 0.5, 0.5, 9.0, 1, loopContext);
		HopCommon priorParentCommon = new HopCommon(priorCpParent, 0.5, 0.5, 9.0, 1, loopContext);
		HopCommon laterParentCommon = new HopCommon(laterCpParent, 0.5, 0.5, 9.0, 1, loopContext);
		hopCommonTable.put(priorRead.getHopID(), priorReadCommon);
		hopCommonTable.put(laterRead.getHopID(), laterReadCommon);
		hopCommonTable.put(priorCpParent.getHopID(), priorParentCommon);
		hopCommonTable.put(laterCpParent.getHopID(), laterParentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, priorReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, laterReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, priorParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(priorRead.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>();
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>(List.of(laterRead));
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(laterParentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		assertEquals(1, fOUTOnlychildForwardingCostToCP.size());
		double cachedLocalAccessCost = FederatedCostModel.computeMemoryAccessCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(laterRead));
		double downloadCost = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(laterRead), FType.ROW, 1);
		assertTrue("A later source-ordered CP consumer of the same stable federated input should"
			+ " reuse the prior sibling's local MatrixObject for network transfer but still pay"
			+ " a positive local cached MatrixObject access floor",
			fOUTOnlychildForwardingCostToCP.get(0) >= cachedLocalAccessCost);
		assertTrue("Cached local access must remain cheaper than a duplicate worker-to-coordinator download",
			fOUTOnlychildForwardingCostToCP.get(0) < downloadCost);
	}

	@Test
	public void testDpStableTransientReadLoutCpParentPaysLocalAccessFloor() throws Exception {
		DataOp fedInput = federatedRead("XstableLoutAccessSource", ROWS, COLS);
		DataOp tRead = transientRead("XstableLoutAccess", ROWS, COLS);
		tRead.getInput().add(fedInput);
		fedInput.getParent().add(tRead);
		UnaryOp cpParent = new UnaryOp("stableLoutCpConsumer", DataType.MATRIX, ValueType.FP64,
			OpOp1.SQRT, tRead);
		tRead.getParent().add(cpParent);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tReadCommon = registerHopCommon(hopCommonTable, tRead);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, cpParent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 7.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 9.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		List<Hop> bothOutInputs = new ArrayList<>(List.of(tRead));
		double[][] childCumulativeCost = new double[1][2];
		double[] childForwardingCostToCP = new double[1];
		double[] childForwardingCostToFED = new double[1];
		double[] childForwardingCostFOutToFED = new double[1];
		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();
		List<Hop> fOUTOnlyinputHops = new ArrayList<>();
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		FederatedPlannerDpCostEstimator.getChildCosts(parentCommon, memoTable, hopCommonTable, bothOutInputs,
			childCumulativeCost, childForwardingCostToCP, childForwardingCostToFED,
			childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
			lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
			fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED, 1);

		double localAccessCost = FederatedCostModel.computeMemoryAccessCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(tRead));
		assertTrue("Stable federated TRANSIENTREAD selected as LOUT still has a CP local MatrixObject"
			+ " access floor for the consuming parent",
			childCumulativeCost[0][0] >= 7.0 + localAccessCost);
	}

	@Test
	public void testDpNonTransientCpfoutCpParentForwardingSkipsSyntheticDownloadShare() throws Exception {
		DataOp localInput = transientRead("XlocalCpParent", ROWS, COLS);
		UnaryOp child = new UnaryOp("localCpfout", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, localInput);
		UnaryOp parent = new UnaryOp("cpParent", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, child);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan childPlan = addCustomPlan(memoTable, childCommon,
			FederatedOutput.FOUT, ExecType.CP, FType.FULL, 10.0);
		FedPlan parentPlan = addCustomPlan(memoTable, parentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.FULL, 20.0);
		memoTable.registerHopRefs(hopCommonTable);

		double forwardingShare = invokeDpPlannerForwardingShare(
			false, FederatedOutput.FOUT, childPlan, parentPlan, 1);
		double enumeratorForwardingShare = invokeDpEnumeratorForwardingShare(
			false, FederatedOutput.FOUT, childPlan, parentPlan, 1);

		assertEquals("CP parent consuming a CP/FOUT child should not pay an extra synthetic FOUT->CP"
			+ " network download during output-decision reconciliation",
			0.0, forwardingShare, 1e-9);
		assertEquals("Enumerator exact-hop delta must match the base child-cost / planner helper parity"
			+ " for CP parent <- CP/FOUT child",
			0.0, enumeratorForwardingShare, 1e-9);
	}

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
	public void testDpDecisionMapChargesTransientReadLoutMaterializationPerCloneMultiplicity() throws Exception {
		DataOp origTRead = transientRead("XorigMaterialize", ROWS, COLS);
		DataOp cloneTRead = transientRead("XcloneMaterialize", ROWS, COLS);
		UnaryOp origParent = new UnaryOp("u_orig_materialize", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, origTRead);
		UnaryOp cloneParent = new UnaryOp("u_clone_materialize", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, cloneTRead);
		LiteralOp dummyRoot = new LiteralOp(1L);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origTReadCommon = registerHopCommon(hopCommonTable, origTRead);
		HopCommon cloneTReadCommon = new HopCommon(cloneTRead, 1.0, 49.0, 49.0, 1,
			List.of(Pair.of(87L, 49.0)));
		hopCommonTable.put(cloneTRead.getHopID(), cloneTReadCommon);
		HopCommon origParentCommon = registerHopCommon(hopCommonTable, origParent);
		HopCommon cloneParentCommon = new HopCommon(cloneParent, 1.0, 49.0, 49.0, 1,
			List.of(Pair.of(87L, 49.0)));
		hopCommonTable.put(cloneParent.getHopID(), cloneParentCommon);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, origTReadCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0);
		addCustomPlan(memoTable, origTReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addCustomPlan(memoTable, cloneTReadCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0);
		addCustomPlan(memoTable, cloneTReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, origParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(origTRead.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, cloneParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(cloneTRead.getHopID(), FederatedOutput.LOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0, List.of());

		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(
			cloneTRead.getHopID(), origTRead.getHopID(),
			cloneParent.getHopID(), origParent.getHopID()));
		memoTable.registerAdditionalRootHopIDs(List.of(origParent, cloneParent));

		Map<Long, FederatedOutput> outputDecisions =
			new HashMap<>(Map.of(origTRead.getHopID(), FederatedOutput.LOUT));
		double totalCost = invokeComputeDecisionMapTotalCost(memoTable, dummyRootPlan, outputDecisions);
		double unitDownload = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(origTRead), FType.ROW, 1);

		assertEquals("Transient-read LOUT materialization must scale with original + virtual clone multiplicity",
			unitDownload * 50.0, totalCost, 1e-9);
	}

	@Test
	public void testDpDecisionMapChargesComputedFoutToCpLocalEdgeCost() throws Exception {
		DataOp fedInput = federatedRead("XcomputedDecisionMap", ROWS, COLS);
		UnaryOp computedFout = new UnaryOp("computed_decision_map", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, fedInput);
		computedFout.setDim1(ROWS);
		computedFout.setDim2(COLS);
		computedFout.setNnz(ROWS * COLS);
		DataOp localInput = transientRead("YcomputedDecisionMap", ROWS, COLS);
		BinaryOp localConsumer = new BinaryOp("local_consumer_decision_map", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, computedFout, localInput);
		localConsumer.setDim1(ROWS);
		localConsumer.setDim2(COLS);
		localConsumer.setNnz(ROWS * COLS);
		LiteralOp dummyRoot = new LiteralOp(1L);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon computedCommon = registerHopCommon(hopCommonTable, computedFout);
		HopCommon localInputCommon = registerHopCommon(hopCommonTable, localInput);
		HopCommon localConsumerCommon = new HopCommon(localConsumer, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(localConsumer.getHopID(), localConsumerCommon);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, computedCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addCustomPlan(memoTable, localInputCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, localConsumerCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(
				Pair.of(computedFout.getHopID(), FederatedOutput.FOUT),
				Pair.of(localInput.getHopID(), FederatedOutput.LOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0, List.of());

		memoTable.registerHopRefs(hopCommonTable);
		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		outputDecisions.put(computedFout.getHopID(), FederatedOutput.FOUT);
		outputDecisions.put(localConsumer.getHopID(), FederatedOutput.LOUT);

		double totalCost = invokeComputeDecisionMapTotalCost(memoTable, dummyRootPlan, outputDecisions);
		double unitDownload = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(computedFout), FType.ROW, 1);

		assertTrue("Decision-map scoring must include the computed FED/FOUT -> CP-local edge cost; total="
			+ totalCost + " unitDownload=" + unitDownload,
			totalCost > unitDownload);
	}

	@Test
	public void testDpDecisionMapChargesCompatibleChildVariantShift() throws Exception {
		DataOp input = transientRead("XcompatibleVariantShift", ROWS, COLS);
		UnaryOp source = new UnaryOp("compatible_variant_source", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, input);
		source.setDim1(ROWS);
		source.setDim2(COLS);
		source.setNnz(ROWS * COLS);
		UnaryOp target = new UnaryOp("compatible_variant_target", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, source);
		target.setDim1(ROWS);
		target.setDim2(COLS);
		target.setNnz(ROWS * COLS);
		UnaryOp fedParent = new UnaryOp("compatible_variant_parent", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, target);
		fedParent.setDim1(ROWS);
		fedParent.setDim2(COLS);
		fedParent.setNnz(ROWS * COLS);
		LiteralOp dummyRoot = new LiteralOp(1L);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon sourceCommon = registerHopCommon(hopCommonTable, source);
		HopCommon targetCommon = registerHopCommon(hopCommonTable, target);
		HopCommon fedParentCommon = registerHopCommon(hopCommonTable, fedParent);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, sourceCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0);
		addCustomPlan(memoTable, sourceCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);

		FedPlanVariants targetFoutVariants = new FedPlanVariants(targetCommon, FederatedOutput.FOUT);
		FedPlan prunedBestTarget = new FedPlan(10.0, targetFoutVariants,
			List.of(Pair.of(source.getHopID(), FederatedOutput.FOUT)));
		prunedBestTarget.setExecType(ExecType.FED);
		prunedBestTarget.setFType(FType.ROW);
		targetFoutVariants.addFedPlan(prunedBestTarget);
		FedPlan compatibleTarget = new FedPlan(30.0, targetFoutVariants,
			List.of(Pair.of(source.getHopID(), FederatedOutput.LOUT)));
		compatibleTarget.setExecType(ExecType.CP);
		compatibleTarget.setFType(FType.ROW);
		targetFoutVariants.addFedPlan(compatibleTarget);
		memoTable.addFedPlanVariants(target.getHopID(), FederatedOutput.FOUT, targetFoutVariants);

		addPlanWithChildren(memoTable, fedParentCommon, FederatedOutput.LOUT, ExecType.FED, FType.ROW, 100.0,
			List.of(Pair.of(target.getHopID(), FederatedOutput.FOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(fedParent.getHopID(), FederatedOutput.LOUT)));

		memoTable.registerHopRefs(hopCommonTable);
		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		outputDecisions.put(source.getHopID(), FederatedOutput.LOUT);
		outputDecisions.put(target.getHopID(), FederatedOutput.FOUT);
		outputDecisions.put(fedParent.getHopID(), FederatedOutput.LOUT);

		double totalCost = invokeComputeDecisionMapTotalCost(memoTable, dummyRootPlan, outputDecisions);

		assertTrue("Decision-map scoring must charge the compatible child-variant cumulative delta; total="
			+ totalCost,
			totalCost > 119.999);
	}

	@Test
	public void testDpDecisionMapChargesNestedCompatibleChildVariantShift() throws Exception {
		DataOp input = transientRead("XnestedCompatibleVariantShift", ROWS, COLS);
		UnaryOp source = new UnaryOp("nested_compatible_variant_source", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, input);
		source.setDim1(ROWS);
		source.setDim2(COLS);
		source.setNnz(ROWS * COLS);
		UnaryOp target = new UnaryOp("nested_compatible_variant_target", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, source);
		target.setDim1(ROWS);
		target.setDim2(COLS);
		target.setNnz(ROWS * COLS);
		UnaryOp fedParent = new UnaryOp("nested_compatible_variant_parent", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, target);
		fedParent.setDim1(ROWS);
		fedParent.setDim2(COLS);
		fedParent.setNnz(ROWS * COLS);
		UnaryOp stableWrapper = new UnaryOp("nested_compatible_variant_wrapper", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, fedParent);
		stableWrapper.setDim1(ROWS);
		stableWrapper.setDim2(COLS);
		stableWrapper.setNnz(ROWS * COLS);
		LiteralOp dummyRoot = new LiteralOp(1L);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon sourceCommon = registerHopCommon(hopCommonTable, source);
		HopCommon targetCommon = registerHopCommon(hopCommonTable, target);
		HopCommon fedParentCommon = registerHopCommon(hopCommonTable, fedParent);
		HopCommon stableWrapperCommon = registerHopCommon(hopCommonTable, stableWrapper);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, sourceCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0);
		addCustomPlan(memoTable, sourceCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);

		FedPlanVariants targetFoutVariants = new FedPlanVariants(targetCommon, FederatedOutput.FOUT);
		FedPlan prunedBestTarget = new FedPlan(10.0, targetFoutVariants,
			List.of(Pair.of(source.getHopID(), FederatedOutput.FOUT)));
		prunedBestTarget.setExecType(ExecType.FED);
		prunedBestTarget.setFType(FType.ROW);
		targetFoutVariants.addFedPlan(prunedBestTarget);
		FedPlan compatibleTarget = new FedPlan(30.0, targetFoutVariants,
			List.of(Pair.of(source.getHopID(), FederatedOutput.LOUT)));
		compatibleTarget.setExecType(ExecType.CP);
		compatibleTarget.setFType(FType.ROW);
		targetFoutVariants.addFedPlan(compatibleTarget);
		memoTable.addFedPlanVariants(target.getHopID(), FederatedOutput.FOUT, targetFoutVariants);

		addPlanWithChildren(memoTable, fedParentCommon, FederatedOutput.LOUT, ExecType.FED, FType.ROW, 100.0,
			List.of(Pair.of(target.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, stableWrapperCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 150.0,
			List.of(Pair.of(fedParent.getHopID(), FederatedOutput.LOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(stableWrapper.getHopID(), FederatedOutput.LOUT)));

		memoTable.registerHopRefs(hopCommonTable);
		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		outputDecisions.put(source.getHopID(), FederatedOutput.LOUT);
		outputDecisions.put(target.getHopID(), FederatedOutput.FOUT);
		outputDecisions.put(fedParent.getHopID(), FederatedOutput.LOUT);
		outputDecisions.put(stableWrapper.getHopID(), FederatedOutput.LOUT);

		double totalCost = invokeComputeDecisionMapTotalCost(memoTable, dummyRootPlan, outputDecisions);

		assertTrue("Decision-map scoring must recurse through unchanged child variants and charge nested compatible shifts; total="
			+ totalCost,
			totalCost > 169.999);
	}

	@Test
	public void testDpCloneFamilyRewriteDoesNotDoubleCountComputedFoutToCpLocalEdgeCost() throws Exception {
		DataOp origFedInput = federatedRead("XorigCloneFamilyEdge", 10000, 1000);
		UnaryOp origProducer = new UnaryOp("orig_clone_family_producer", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, origFedInput);
		origProducer.setDim1(10000);
		origProducer.setDim2(1000);
		origProducer.setNnz(10000 * 1000);
		UnaryOp origParent = new UnaryOp("orig_clone_family_parent", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, origProducer);
		origParent.setDim1(10000);
		origParent.setDim2(1000);
		origParent.setNnz(10000 * 1000);

		DataOp cloneFedInput = federatedRead("XcloneCloneFamilyEdge", 10000, 1000);
		UnaryOp cloneProducer = new UnaryOp("clone_clone_family_producer", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, cloneFedInput);
		cloneProducer.setDim1(10000);
		cloneProducer.setDim2(1000);
		cloneProducer.setNnz(10000 * 1000);
		UnaryOp cloneParent = new UnaryOp("clone_clone_family_parent", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, cloneProducer);
		cloneParent.setDim1(10000);
		cloneParent.setDim2(1000);
		cloneParent.setNnz(10000 * 1000);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origFedInputCommon = registerHopCommon(hopCommonTable, origFedInput);
		HopCommon origProducerCommon = registerHopCommon(hopCommonTable, origProducer);
		HopCommon origParentCommon = registerHopCommon(hopCommonTable, origParent);
		HopCommon cloneFedInputCommon = new HopCommon(cloneFedInput, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneFedInput.getHopID(), cloneFedInputCommon);
		HopCommon cloneProducerCommon = new HopCommon(cloneProducer, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneProducer.getHopID(), cloneProducerCommon);
		HopCommon cloneParentCommon = new HopCommon(cloneParent, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneParent.getHopID(), cloneParentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, origFedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, origProducerCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(origFedInput.getHopID(), FederatedOutput.FOUT)));
		double unitDownload = FederatedCostModel.computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(origProducer), FType.ROW, 1);
		FedPlan origParentPlan = addPlanWithChildren(memoTable, origParentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0 + unitDownload,
			List.of(Pair.of(origProducer.getHopID(), FederatedOutput.FOUT)));
		addCustomPlan(memoTable, cloneFedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0);
		addPlanWithChildren(memoTable, cloneProducerCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 0.0,
			List.of(Pair.of(cloneFedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, cloneParentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0 + unitDownload,
			List.of(Pair.of(cloneProducer.getHopID(), FederatedOutput.FOUT)));

		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(
			cloneFedInput.getHopID(), origFedInput.getHopID(),
			cloneProducer.getHopID(), origProducer.getHopID(),
			cloneParent.getHopID(), origParent.getHopID()));

		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		outputDecisions.put(origProducer.getHopID(), FederatedOutput.FOUT);
		outputDecisions.put(origParent.getHopID(), FederatedOutput.LOUT);
		double familyCost = invokeComputeCloneFamilyRewriteCost(memoTable,
			new LinkedHashSet<>(List.of(origParent.getHopID(), cloneParent.getHopID())),
			origParentPlan, outputDecisions);

		assertEquals("Clone-family rewrite scoring must use each concrete plan cumulative cost"
			+ " and must not add a second synthetic FED/FOUT -> CP-local edge charge",
			2.0 + (2.0 * unitDownload), familyCost, 1e-9);
	}

	@Test
	public void testDpCloneFamilyRewriteUsesFullMemberCostNotParentShare() throws Exception {
		DataOp origParent = transientRead("XorigCloneFamilyFullCost", ROWS, COLS);
		DataOp cloneParent = transientRead("XcloneCloneFamilyFullCost", ROWS, COLS);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origParentCommon = new HopCommon(origParent, 1.0, 1.0, 1.0, 2, List.of());
		hopCommonTable.put(origParent.getHopID(), origParentCommon);
		HopCommon cloneParentCommon = new HopCommon(cloneParent, 1.0, 50.0, 50.0, 2,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneParent.getHopID(), cloneParentCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FedPlan origParentPlan = addCustomPlan(memoTable, origParentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 100.0);
		addCustomPlan(memoTable, cloneParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 60.0);

		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(cloneParent.getHopID(), origParent.getHopID()));

		double familyCost = invokeComputeCloneFamilyRewriteCost(memoTable,
			new LinkedHashSet<>(List.of(origParent.getHopID(), cloneParent.getHopID())),
			origParentPlan, new HashMap<>());

		assertEquals("Clone-family rewrite compares full original+virtual member execution cost,"
			+ " not parent-edge cost shares", 160.0, familyCost, 1e-9);
	}

	@Test
	public void testDpResolveOneHopConflictAggregatesVirtualCloneCostEdges() throws Exception {
		DataOp origChild = transientRead("XorigChoice", ROWS, COLS);
		DataOp cloneChild = transientRead("XcloneChoice", ROWS, COLS);
		UnaryOp origParent = new UnaryOp("u_orig_choice", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, origChild);
		UnaryOp cloneParent = new UnaryOp("u_clone_choice", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, cloneChild);
		DataOp dummyRoot = transientRead("RootChoice", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origChildCommon = registerHopCommon(hopCommonTable, origChild);
		HopCommon cloneChildCommon = new HopCommon(cloneChild, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneChild.getHopID(), cloneChildCommon);
		HopCommon origParentCommon = registerHopCommon(hopCommonTable, origParent);
		HopCommon cloneParentCommon = new HopCommon(cloneParent, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneParent.getHopID(), cloneParentCommon);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, origChildCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 2.0);
		addCustomPlan(memoTable, origChildCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0);
		addCustomPlan(memoTable, cloneChildCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		addCustomPlan(memoTable, cloneChildCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1000.0);
		addPlanWithChildren(memoTable, origParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 5.0,
			List.of(Pair.of(origChild.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, cloneParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 5.0,
			List.of(Pair.of(cloneChild.getHopID(), FederatedOutput.LOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0, List.of());

		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(
			cloneChild.getHopID(), origChild.getHopID(),
			cloneParent.getHopID(), origParent.getHopID()));
		memoTable.registerAdditionalRootHopIDs(List.of(origParent, cloneParent));

		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, dummyRootPlan, new HashMap<>());
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		Object entry = conflictMap.get(origChild.getHopID());
		assertNotNull("Expected merged conflict entry for executable child + virtual clone", entry);

		FederatedOutput chosen = invokeResolveOneHopConflict(
			memoTable, origChild.getHopID(), entry, new HashMap<>(), 1);

			assertEquals("Generic one-hop resolve should aggregate executable and virtual-clone costs"
				+ " instead of preferring the original executable member", FederatedOutput.LOUT, chosen);
		}

	@Test
	public void testDpDecisionMapScoresVirtualCloneFamilyOutputOverride() throws Exception {
		DataOp origChild = transientRead("XorigClosureClone", ROWS, COLS);
		DataOp cloneChild = transientRead("XcloneClosureClone", ROWS, COLS);
		UnaryOp rootParent = new UnaryOp("root_parent_closure_clone", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, origChild);
		UnaryOp cloneRoot = new UnaryOp("clone_root_closure_clone", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, cloneChild);
		DataOp dummyRoot = transientRead("RootClosureClone", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origChildCommon = registerHopCommon(hopCommonTable, origChild);
		HopCommon cloneChildCommon = new HopCommon(cloneChild, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneChild.getHopID(), cloneChildCommon);
		HopCommon rootParentCommon = registerHopCommon(hopCommonTable, rootParent);
		HopCommon cloneRootCommon = new HopCommon(cloneRoot, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneRoot.getHopID(), cloneRootCommon);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, origChildCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 20.0);
		addCustomPlan(memoTable, origChildCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0);
		addCustomPlan(memoTable, cloneChildCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		addCustomPlan(memoTable, cloneChildCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1000.0);
		addPlanWithChildren(memoTable, rootParentCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0,
			List.of(Pair.of(origChild.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, rootParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 100.0,
			List.of(Pair.of(origChild.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, cloneRootCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(cloneChild.getHopID(), FederatedOutput.LOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(rootParent.getHopID(), FederatedOutput.FOUT)));

		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(cloneChild.getHopID(), origChild.getHopID()));
		memoTable.registerAdditionalRootHopIDs(List.of(cloneRoot));

		Map<Long, FederatedOutput> childLoutDecision = new HashMap<>();
		childLoutDecision.put(origChild.getHopID(), FederatedOutput.LOUT);
		Map<Long, FederatedOutput> childFoutDecision = new HashMap<>();
		childFoutDecision.put(origChild.getHopID(), FederatedOutput.FOUT);

		double loutScore = invokeComputeDecisionMapTotalCost(memoTable, dummyRootPlan, childLoutDecision);
		double foutScore = invokeComputeDecisionMapTotalCost(memoTable, dummyRootPlan, childFoutDecision);

		assertTrue("Decision-map scoring must price the virtual clone member when a closure-style"
			+ " original-hop FOUT decision would later be mirrored to the clone family; lout="
			+ loutScore + " fout=" + foutScore,
			foutScore > loutScore + 500.0);
	}

	@Test
	public void testDpRewriteMirrorsCloneFamilyStateToVirtualMembers() throws Exception {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		DataOp origChild = transientRead("XorigRewriteState", ROWS, COLS);
		DataOp cloneChild = transientRead("XcloneRewriteState", ROWS, COLS);
		UnaryOp origParent = new UnaryOp("u_orig_rewrite_state", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, origChild);
		UnaryOp cloneParent = new UnaryOp("u_clone_rewrite_state", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, cloneChild);
		DataOp dummyRoot = transientRead("RootRewriteState", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origChildCommon = registerHopCommon(hopCommonTable, origChild);
		HopCommon cloneChildCommon = new HopCommon(cloneChild, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneChild.getHopID(), cloneChildCommon);
		HopCommon origParentCommon = registerHopCommon(hopCommonTable, origParent);
		HopCommon cloneParentCommon = new HopCommon(cloneParent, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneParent.getHopID(), cloneParentCommon);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, origChildCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 20.0);
		FedPlan origFoutPlan = addCustomPlan(memoTable, origChildCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 10.0);
		addCustomPlan(memoTable, cloneChildCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1000.0);
		addCustomPlan(memoTable, cloneChildCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 5.0);
		addPlanWithChildren(memoTable, origParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(origChild.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, cloneParentCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(cloneChild.getHopID(), FederatedOutput.FOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0, List.of());

		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(
			cloneChild.getHopID(), origChild.getHopID(),
			cloneParent.getHopID(), origParent.getHopID()));
		memoTable.registerAdditionalRootHopIDs(List.of(origParent, cloneParent));

		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		outputDecisions.put(origChild.getHopID(), FederatedOutput.FOUT);
		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, dummyRootPlan, outputDecisions);

		FederatedPlannerDpFedCostBased planner = new FederatedPlannerDpFedCostBased();
		invokeDpRewriteHopWithConflictMap(planner, origFoutPlan, memoTable, outputDecisions, conflictMap);

		assertEquals("Executable original should keep the selected clone-family FED/FOUT state",
			ExecType.FED, origChild.getForcedExecType());
		assertEquals(FederatedOutput.FOUT, origChild.getFederatedOutput());
		assertEquals("Virtual clone member state must mirror the compatible selected family plan"
			+ " so recompile signature matching cannot restore stale CP/LOUT state",
			ExecType.FED, cloneChild.getForcedExecType());
		assertEquals(FederatedOutput.FOUT, cloneChild.getFederatedOutput());
		FederatedPlannerUtils.PlannerRecompileState cloneState =
			FederatedPlannerUtils.getPlannerRecompileState(cloneChild);
		assertNotNull("Virtual clone member state must be visible to Recompiler signature restore",
			cloneState);
		assertEquals(ExecType.FED, cloneState.getExecType());
		assertEquals(FederatedOutput.FOUT, cloneState.getFederatedOutput());
		FederatedPlannerUtils.clearPlannerRecompileStates();
	}

	@Test
	public void testDpVirtualCloneRewriteDoesNotOverwriteConflictingOriginalState() throws Exception {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		DataOp input = transientRead("XvirtualCloneStateGuard", ROWS, COLS);
		UnaryOp origParent = new UnaryOp("orig_virtual_clone_state_guard", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, input);
		UnaryOp cloneParent = new UnaryOp("clone_virtual_clone_state_guard", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, input);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon origCommon = registerHopCommon(hopCommonTable, origParent);
		HopCommon cloneCommon = new HopCommon(cloneParent, 1.0, 50.0, 50.0, 1,
			List.of(Pair.of(999L, 50.0)));
		hopCommonTable.put(cloneParent.getHopID(), cloneCommon);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, origCommon, FederatedOutput.LOUT, ExecType.FED, FType.ROW, 1.0);
		FedPlan cloneCpPlan = addCustomPlan(memoTable, cloneCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(Map.of(cloneParent.getHopID(), origParent.getHopID()));

		origParent.setForcedExecType(ExecType.FED);
		origParent.setFederatedOutput(FederatedOutput.LOUT);
		FederatedPlannerUtils.registerPlannerRecompileState(
			origParent, ExecType.FED, FederatedOutput.LOUT);

		FederatedPlannerDpFedCostBased planner = new FederatedPlannerDpFedCostBased();
		invokeDpRewriteHopWithConflictMap(
			planner, cloneCpPlan, memoTable, new HashMap<>(), new HashMap<>());

		assertEquals("A virtual clone with conflicting state must not overwrite"
			+ " the executable original hop state selected earlier",
			ExecType.FED, origParent.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, origParent.getFederatedOutput());
		FederatedPlannerUtils.clearPlannerRecompileStates();
	}

	@Test
	public void testPlannerRecompileStateSurvivesFedInitClearUntilPlannerReset() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		DataOp read = transientRead("XplannerStateLifetime", ROWS, COLS);
		FederatedPlannerUtils.registerPlannerRecompileState(
			read, ExecType.FED, FederatedOutput.FOUT);

		assertNotNull("Planner recompile state should be registered before lowering",
			FederatedPlannerUtils.getPlannerRecompileState(read));
		FederatedPlannerUtils.clearFedInitVars();
		assertNotNull("Lop lowering fed-init cleanup must not erase planner recompile state",
			FederatedPlannerUtils.getPlannerRecompileState(read));

		FederatedPlannerUtils.resetFederatedPlannerRunState();
		assertTrue("A new federated planner run must clear stale recompile state",
			FederatedPlannerUtils.getPlannerRecompileState(read) == null);
	}


	@Test
	public void testDpTransientFamilyDecisionMembersExcludeConsumerParents() throws Exception {
		DataOp fedInput = federatedRead("Xin", ROWS, COLS);
		DataOp tWrite = HopRewriteUtils.createTransientWrite("P", fedInput);
		DataOp tRead = transientRead("P", ROWS, COLS);
		LiteralOp rowStart = new LiteralOp(1L);
		LiteralOp rowEnd = new LiteralOp(ROWS);
		LiteralOp colStart = new LiteralOp(1L);
		LiteralOp colEnd = new LiteralOp(1L);
		IndexingOp rightIndex = HopRewriteUtils.createIndexingOp(tRead, rowStart, rowEnd, colStart, colEnd);
		rightIndex.setDim1(ROWS);
		rightIndex.setDim2(1);
		UnaryOp root = HopRewriteUtils.createUnary(rightIndex, OpOp1.EXP);
		root.setDim1(ROWS);
		root.setDim2(1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tWriteCommon = registerHopCommon(hopCommonTable, tWrite);
		HopCommon tReadCommon = registerHopCommon(hopCommonTable, tRead);
		HopCommon rightIndexCommon = registerHopCommon(hopCommonTable, rightIndex);
		HopCommon rootCommon = registerHopCommon(hopCommonTable, root);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addSinglePlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW);
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 2.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 3.0,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 4.0,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, rightIndexCommon, FederatedOutput.LOUT, ExecType.CP, FType.BROADCAST, 5.0,
			List.of(Pair.of(tRead.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, rightIndexCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 6.0,
			List.of(Pair.of(tRead.getHopID(), FederatedOutput.FOUT)));
		FedPlan rootPlan = addPlanWithChildren(memoTable, rootCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 7.0,
			List.of(Pair.of(rightIndex.getHopID(), FederatedOutput.LOUT)));

		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, rootPlan, outputDecisions);
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		Map<Long, LinkedHashSet<Long>> parentGraph = invokeBuildConflictParentGraph(memoTable, conflictMap);
		LinkedHashSet<Long> familyHopIDs = invokeCollectTransientFamilyDecisionHopIDs(
			memoTable, tWrite.getHopID(), conflictMap, parentGraph);

		assertTrue("TWrite should remain in its transient family", familyHopIDs.contains(tWrite.getHopID()));
		assertTrue("Linked TRead should remain in the transient family", familyHopIDs.contains(tRead.getHopID()));
		assertFalse("Consumer parents like rightIndex must not be forced into the transient family decision set",
			familyHopIDs.contains(rightIndex.getHopID()));
	}

	@Test
	public void testDpContextualTransientBundleFeasibilitySkipsRightIndexSliceWithoutConcreteSource()
		throws Exception {
		DataOp localSource = transientRead("P_local", ROWS, COLS);
		DataOp tWrite = HopRewriteUtils.createTransientWrite("P", localSource);
		DataOp tRead = transientRead("P", ROWS, COLS);
		LiteralOp rowStart = new LiteralOp(1L);
		LiteralOp rowEnd = new LiteralOp(ROWS);
		LiteralOp colStart = new LiteralOp(1L);
		LiteralOp colEnd = new LiteralOp(1L);
		IndexingOp rightIndex = HopRewriteUtils.createIndexingOp(tRead, rowStart, rowEnd, colStart, colEnd);
		rightIndex.setDim1(ROWS);
		rightIndex.setDim2(1);
		UnaryOp root = HopRewriteUtils.createUnary(rightIndex, OpOp1.EXP);
		root.setDim1(ROWS);
		root.setDim2(1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon localSourceCommon = registerHopCommon(hopCommonTable, localSource);
		HopCommon tWriteCommon = registerHopCommon(hopCommonTable, tWrite);
		HopCommon tReadCommon = registerHopCommon(hopCommonTable, tRead);
		HopCommon rightIndexCommon = registerHopCommon(hopCommonTable, rightIndex);
		HopCommon rootCommon = registerHopCommon(hopCommonTable, root);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addPlanWithChildren(memoTable, localSourceCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of());
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 2.0,
			List.of(Pair.of(localSource.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 2.2,
			List.of(Pair.of(localSource.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 3.0,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 3.1,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, rightIndexCommon, FederatedOutput.LOUT, ExecType.CP, FType.BROADCAST, 4.0,
			List.of(Pair.of(tRead.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, rightIndexCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 4.05,
			List.of(Pair.of(tRead.getHopID(), FederatedOutput.FOUT)));
		FedPlan rootPlan = addPlanWithChildren(memoTable, rootCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW,
			5.0, List.of(Pair.of(rightIndex.getHopID(), FederatedOutput.LOUT)));

		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, rootPlan, outputDecisions);
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		Map<Long, LinkedHashSet<Long>> parentGraph = invokeBuildConflictParentGraph(memoTable, conflictMap);
		LinkedHashSet<Long> familyHopIDs = invokeCollectTransientFamilyDecisionHopIDs(
			memoTable, tWrite.getHopID(), conflictMap, parentGraph);
		LinkedHashSet<Long> bundleHopIDs = new LinkedHashSet<>(familyHopIDs);
		bundleHopIDs.add(rightIndex.getHopID());

		LinkedHashSet<Long> feasibleBundleHopIDs = invokeCollectContextuallyFeasibleTransientBundleHopIDs(
			memoTable, rootPlan, outputDecisions, conflictMap, familyHopIDs, bundleHopIDs);

		assertTrue("Original transient family should remain present in the feasible bundle set",
			feasibleBundleHopIDs.containsAll(familyHopIDs));
		assertFalse("Contextual bundle feasibility must keep rightIndex slice local when the transient"
			+ " read lacks a concrete federated source",
			feasibleBundleHopIDs.contains(rightIndex.getHopID()));
	}

	@Test
	public void testDpRewriteKeepsTransientChainConsistentWithFedParentEdge() throws Exception {
		DataOp fedInput = federatedRead("Xin", ROWS, COLS);
		DataOp tWrite = HopRewriteUtils.createTransientWrite("P", fedInput);
		DataOp tRead = transientRead("P", ROWS, COLS);
		LiteralOp rowStart = new LiteralOp(1L);
		LiteralOp rowEnd = new LiteralOp(ROWS);
		LiteralOp colStart = new LiteralOp(1L);
		LiteralOp colEnd = new LiteralOp(1L);
		IndexingOp rightIndex = HopRewriteUtils.createIndexingOp(tRead, rowStart, rowEnd, colStart, colEnd);
		rightIndex.setDim1(ROWS);
		rightIndex.setDim2(1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon fedInputCommon = registerHopCommon(hopCommonTable, fedInput);
		HopCommon tWriteCommon = registerHopCommon(hopCommonTable, tWrite);
		HopCommon tReadCommon = registerHopCommon(hopCommonTable, tRead);
		HopCommon rightIndexCommon = registerHopCommon(hopCommonTable, rightIndex);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addSinglePlan(memoTable, fedInputCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW);
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, tWriteCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 2.0,
			List.of(Pair.of(fedInput.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, tReadCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 3.0,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.LOUT)));
		FedPlan tReadFoutPlan = addPlanWithChildren(memoTable, tReadCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 4.0,
			List.of(Pair.of(tWrite.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, rightIndexCommon, FederatedOutput.LOUT, ExecType.CP, FType.BROADCAST, 5.0,
			List.of(Pair.of(tRead.getHopID(), FederatedOutput.LOUT)));
		FedPlan rightIndexFoutPlan = addPlanWithChildren(memoTable, rightIndexCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 6.0,
			List.of(Pair.of(tRead.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		FederatedPlannerDpFedCostBased planner = new FederatedPlannerDpFedCostBased();
		Map<Long, FederatedOutput> conflictingOutputDecisions = new HashMap<>();
		conflictingOutputDecisions.put(tRead.getHopID(), FederatedOutput.LOUT);

		invokeDpRewriteHop(planner, rightIndexFoutPlan, memoTable, conflictingOutputDecisions);

		assertEquals("FED rightIndex parent must keep its selected FOUT chain executable",
			FederatedOutput.FOUT, rightIndex.getFederatedOutput());
		assertEquals("Transient read must follow the selected FED parent edge rather than rewrite to local",
			FederatedOutput.FOUT, tRead.getFederatedOutput());
		assertEquals("Upstream transient write must stay aligned with the selected transient read",
			FederatedOutput.FOUT, tWrite.getFederatedOutput());
		assertEquals("Transient read should remain FED-executable under the selected parent chain",
			ExecType.FED, tRead.getForcedExecType());
		assertEquals("The synthetic FED transient-read plan should still be present",
			FederatedOutput.FOUT, tReadFoutPlan.getFedOutType());
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
	public void testDpResolveOneHopConflictAccountsForSameOutputCompatibleVariantShift() throws Exception {
		DataOp source = transientRead("XsourceCompat", ROWS, COLS);
		UnaryOp target = new UnaryOp("targetCompat", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, source);
		UnaryOp foutParent = new UnaryOp("foutParentCompat", DataType.MATRIX, ValueType.FP64, OpOp1.LOG, target);
		UnaryOp loutParent = new UnaryOp("loutParentCompat", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, target);
		DataOp dummyRoot = transientRead("RootCompat", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon sourceCommon = registerHopCommon(hopCommonTable, source);
		HopCommon targetCommon = registerHopCommon(hopCommonTable, target);
		HopCommon foutParentCommon = registerHopCommon(hopCommonTable, foutParent);
		HopCommon loutParentCommon = registerHopCommon(hopCommonTable, loutParent);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, sourceCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		addCustomPlan(memoTable, sourceCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 2.0);
		addPlanWithChildren(memoTable, targetCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0,
			List.of(Pair.of(source.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, targetCommon, FederatedOutput.FOUT, ExecType.CP, FType.ROW, 1000.0,
			List.of(Pair.of(source.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, targetCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 50.0,
			List.of(Pair.of(source.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, foutParentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(target.getHopID(), FederatedOutput.FOUT)));
		addPlanWithChildren(memoTable, loutParentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(target.getHopID(), FederatedOutput.LOUT)));
		FedPlan rootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0, List.of());
		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerAdditionalRootHopIDs(List.of(foutParent, loutParent));

		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, rootPlan, new HashMap<>());
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		Object entry = conflictMap.get(target.getHopID());
		assertNotNull("Expected conflict entry for the target hop", entry);

		FederatedOutput chosen = invokeResolveOneHopConflict(
			memoTable, target.getHopID(), entry, Map.of(source.getHopID(), FederatedOutput.LOUT), 1);

			assertEquals("Generic one-hop resolve should notice when current child decisions force a much"
				+ " more expensive same-output compatible variant", FederatedOutput.LOUT, chosen);
		}

	@Test
	public void testDpRewriteHonorsStrictCompatibleOutputDecisionBeforeInheritedFallback() throws Exception {
		DataOp child = transientRead("XrewriteDesiredChild", ROWS, COLS);
		UnaryOp target = new UnaryOp("targetRewriteDesired", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, child);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon targetCommon = registerHopCommon(hopCommonTable, target);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0);
		FedPlan inheritedFoutPlan = addPlanWithChildren(memoTable, targetCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0,
			List.of(Pair.of(child.getHopID(), FederatedOutput.FOUT)));
		FedPlan desiredLoutPlan = addPlanWithChildren(memoTable, targetCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 10.0,
			List.of(Pair.of(child.getHopID(), FederatedOutput.LOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, FederatedOutput> outputDecisions = new HashMap<>();
		outputDecisions.put(child.getHopID(), FederatedOutput.LOUT);
		outputDecisions.put(target.getHopID(), FederatedOutput.LOUT);

		FedPlan selected = invokeSelectRewritePlanVariant(memoTable, target.getHopID(),
			FederatedOutput.LOUT, FederatedOutput.FOUT, inheritedFoutPlan, outputDecisions);

		assertEquals("Rewrite must honor the explicit DP output decision when a strict child-compatible"
				+ " variant exists, instead of silently staying on inherited FOUT",
			desiredLoutPlan, selected);
		assertEquals(FederatedOutput.LOUT, selected.getFedOutType());
		assertEquals(ExecType.CP, selected.getExecType());
	}

	@Test
	public void testDpRequiredOutputClosureHonorsStrictCompatibleSameOutputVariant() throws Exception {
		DataOp source = transientRead("XclosureCompatibleSource", ROWS, COLS);
		UnaryOp child = new UnaryOp("childClosureCompatible", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, source);
		UnaryOp parent = new UnaryOp("parentClosureCompatible", DataType.MATRIX, ValueType.FP64,
			OpOp1.LOG, child);
		DataOp dummyRoot = transientRead("RootClosureCompatible", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 10.0);
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0);

		FedPlanVariants parentFoutVariants = new FedPlanVariants(parentCommon, FederatedOutput.FOUT);
		FedPlan cheaperChildFoutVariant = new FedPlan(1.0, parentFoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.FOUT)));
		cheaperChildFoutVariant.setExecType(ExecType.FED);
		cheaperChildFoutVariant.setFType(FType.ROW);
		parentFoutVariants.addFedPlan(cheaperChildFoutVariant);
		FedPlan compatibleChildLoutVariant = new FedPlan(20.0, parentFoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.LOUT)));
		compatibleChildLoutVariant.setExecType(ExecType.FED);
		compatibleChildLoutVariant.setFType(FType.ROW);
		parentFoutVariants.addFedPlan(compatibleChildLoutVariant);
		memoTable.addFedPlanVariants(parent.getHopID(), FederatedOutput.FOUT, parentFoutVariants);

		FedPlan rootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(parent.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, rootPlan, new HashMap<>());
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		assertNotNull("Expected a conflict entry for the child decision", conflictMap.get(child.getHopID()));

		Map<Long, FederatedOutput> decisions = new HashMap<>();
		decisions.put(parent.getHopID(), FederatedOutput.FOUT);
		decisions.put(child.getHopID(), FederatedOutput.LOUT);
		LinkedHashSet<Long> closureHopIDs = new LinkedHashSet<>();

		invokeApplyRequiredOutputDecisionClosure(memoTable, parent.getHopID(), FederatedOutput.FOUT,
			conflictMap, decisions, closureHopIDs);

		assertEquals("Required-output closure must not overwrite an explicit child LOUT decision"
				+ " when a same-output parent FOUT variant can preserve that child state",
			FederatedOutput.LOUT, decisions.get(child.getHopID()));
		assertEquals(FederatedOutput.FOUT, decisions.get(parent.getHopID()));
	}

	@Test
	public void testDpParentVariantSwitchPreservesDownstreamFoutDemandAndChargesCpfoutForwarding()
		throws Exception {
		DataOp child = transientRead("XdownstreamState", 10000, 10000);
		UnaryOp parent = new UnaryOp("parentDownstreamState", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, child);
		parent.setDim1(10000);
		parent.setDim2(10000);
		UnaryOp downstream = new UnaryOp("downstreamFedState", DataType.MATRIX, ValueType.FP64,
			OpOp1.LOG, parent);
		downstream.setDim1(10000);
		downstream.setDim2(10000);
		DataOp dummyRoot = transientRead("RootDownstreamState", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);
		HopCommon downstreamCommon = registerHopCommon(hopCommonTable, downstream);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.CP, FType.ROW, 1.0);
		FedPlanVariants parentFoutVariants = new FedPlanVariants(parentCommon, FederatedOutput.FOUT);
		FedPlan parentFedFout = new FedPlan(100.0, parentFoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.FOUT)));
		parentFedFout.setExecType(ExecType.FED);
		parentFedFout.setFType(FType.ROW);
		parentFoutVariants.addFedPlan(parentFedFout);
		FedPlan parentCpFout = new FedPlan(105.0, parentFoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.LOUT)));
		parentCpFout.setExecType(ExecType.CP);
		parentCpFout.setFType(FType.ROW);
		parentFoutVariants.addFedPlan(parentCpFout);
		memoTable.addFedPlanVariants(parent.getHopID(), FederatedOutput.FOUT, parentFoutVariants);
		addPlanWithChildren(memoTable, parentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0,
			List.of(Pair.of(child.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, downstreamCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 110.0,
			List.of(Pair.of(parent.getHopID(), FederatedOutput.FOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(downstream.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, dummyRootPlan, new HashMap<>());
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		Object entry = conflictMap.get(child.getHopID());
		assertNotNull("Expected conflict entry for child consumed through a downstream FOUT chain", entry);

		double noContextDelta = invokeDpPlannerSwitchEdgeCostDelta(
			memoTable, child.getHopID(), FederatedOutput.FOUT, FederatedOutput.LOUT,
			parentFedFout, true, 1);
		double contextDelta = invokeDpPlannerSwitchEdgeCostDeltaWithConflicts(
			memoTable, child.getHopID(), FederatedOutput.FOUT, FederatedOutput.LOUT,
			parentFedFout, true, 1, conflictMap);
		FederatedOutput chosen = invokeResolveOneHopConflictWithConflictMap(
			memoTable, child.getHopID(), entry, new HashMap<>(), 1, conflictMap);

		assertTrue("Without downstream context the local parent variant is falsely attractive",
			noContextDelta < 0.0);
		assertTrue("Downstream FOUT demand must preserve parent FOUT and charge CP/FOUT->FED forwarding",
			contextDelta > 0.0);
		assertEquals("The child decision must not borrow an incompatible parent LOUT switch when"
				+ " the parent is demanded as FOUT downstream",
			FederatedOutput.FOUT, chosen);
	}

	@Test
	public void testDpParentVariantSwitchDoesNotDoubleChargeAccountedCpfoutForwarding()
		throws Exception {
		DataOp child = transientRead("XaccountedCpfoutState", 10000, 10000);
		UnaryOp parent = new UnaryOp("parentAccountedCpfoutState", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, child);
		parent.setDim1(10000);
		parent.setDim2(10000);
		UnaryOp downstream = new UnaryOp("downstreamAccountedCpfoutState", DataType.MATRIX, ValueType.FP64,
			OpOp1.LOG, parent);
		downstream.setDim1(10000);
		downstream.setDim2(10000);
		DataOp dummyRoot = transientRead("RootAccountedCpfoutState", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);
		HopCommon downstreamCommon = registerHopCommon(hopCommonTable, downstream);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.FED, FType.ROW, 1.0);
		FedPlanVariants parentFoutVariants = new FedPlanVariants(parentCommon, FederatedOutput.FOUT);
		FedPlan parentFedFout = new FedPlan(100.0, parentFoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.FOUT)));
		parentFedFout.setExecType(ExecType.FED);
		parentFedFout.setFType(FType.ROW);
		parentFoutVariants.addFedPlan(parentFedFout);
		FedPlan parentCpFout = new FedPlan(105.0, parentFoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.LOUT)));
		parentCpFout.setExecType(ExecType.CP);
		parentCpFout.setFType(FType.ROW);
		parentCpFout.setFoutMaterializationAccounted(true);
		parentFoutVariants.addFedPlan(parentCpFout);
		memoTable.addFedPlanVariants(parent.getHopID(), FederatedOutput.FOUT, parentFoutVariants);
		FedPlan downstreamPlan = addPlanWithChildren(memoTable, downstreamCommon,
			FederatedOutput.FOUT, ExecType.FED, FType.ROW, 110.0,
			List.of(Pair.of(parent.getHopID(), FederatedOutput.FOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(downstream.getHopID(), FederatedOutput.FOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, dummyRootPlan, new HashMap<>());
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		double forwardingShare = invokeDpPlannerForwardingShare(
			true, FederatedOutput.FOUT, parentCpFout, downstreamPlan, 1);
		double contextDelta = invokeDpPlannerSwitchEdgeCostDeltaWithConflicts(
			memoTable, child.getHopID(), FederatedOutput.FOUT, FederatedOutput.LOUT,
			parentFedFout, true, 1, conflictMap);

		assertEquals("CP/FOUT child plans whose cumulative cost already accounts for materialization"
				+ " must not pay a second downstream FED upload",
			0.0, forwardingShare, 0.0);
		assertEquals("Parent-variant switch should compare the CP/FOUT variant's own cost only;"
				+ " downstream forwarding has already been accounted by the CP/FOUT plan",
			5.0, contextDelta, 1e-9);
	}

	@Test
	public void testDpParentVariantSwitchDoesNotTreatObservedLoutAsHardDemandWhenFoutFeasible()
		throws Exception {
		DataOp child = transientRead("XobservedLoutState", 10000, 10000);
		UnaryOp parent = new UnaryOp("parentObservedLoutState", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, child);
		parent.setDim1(10000);
		parent.setDim2(10000);
		UnaryOp downstream = new UnaryOp("downstreamObservedLoutState", DataType.MATRIX, ValueType.FP64,
			OpOp1.LOG, parent);
		downstream.setDim1(10000);
		downstream.setDim2(10000);
		DataOp dummyRoot = transientRead("RootObservedLoutState", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon childCommon = registerHopCommon(hopCommonTable, child);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);
		HopCommon downstreamCommon = registerHopCommon(hopCommonTable, downstream);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, childCommon, FederatedOutput.LOUT, ExecType.CP, FType.ROW, 1.0);
		addCustomPlan(memoTable, childCommon, FederatedOutput.FOUT, ExecType.CP, FType.ROW, 1.0);

		FedPlanVariants parentLoutVariants = new FedPlanVariants(parentCommon, FederatedOutput.LOUT);
		FedPlan parentCurrentLout = new FedPlan(100.0, parentLoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.LOUT)));
		parentCurrentLout.setExecType(ExecType.CP);
		parentCurrentLout.setFType(FType.ROW);
		parentLoutVariants.addFedPlan(parentCurrentLout);
		FedPlan parentExpensiveLoutWithFoutChild = new FedPlan(600.0, parentLoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.FOUT)));
		parentExpensiveLoutWithFoutChild.setExecType(ExecType.CP);
		parentExpensiveLoutWithFoutChild.setFType(FType.ROW);
		parentLoutVariants.addFedPlan(parentExpensiveLoutWithFoutChild);
		memoTable.addFedPlanVariants(parent.getHopID(), FederatedOutput.LOUT, parentLoutVariants);

		FedPlanVariants parentFoutVariants = new FedPlanVariants(parentCommon, FederatedOutput.FOUT);
		FedPlan parentCheaperFoutWithFoutChild = new FedPlan(80.0, parentFoutVariants,
			List.of(Pair.of(child.getHopID(), FederatedOutput.FOUT)));
		parentCheaperFoutWithFoutChild.setExecType(ExecType.FED);
		parentCheaperFoutWithFoutChild.setFType(FType.ROW);
		parentFoutVariants.addFedPlan(parentCheaperFoutWithFoutChild);
		memoTable.addFedPlanVariants(parent.getHopID(), FederatedOutput.FOUT, parentFoutVariants);

		addPlanWithChildren(memoTable, downstreamCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 120.0,
			List.of(Pair.of(parent.getHopID(), FederatedOutput.LOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(downstream.getHopID(), FederatedOutput.LOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, ?> conflictMap = invokeCollectConflictsSingleBfs(memoTable, dummyRootPlan, new HashMap<>());
		invokeRefreshConflictChoiceFeasibility(conflictMap, memoTable);
		Object entry = conflictMap.get(child.getHopID());
		assertNotNull("Expected conflict entry for child consumed by an observed LOUT parent", entry);

		double contextDelta = invokeDpPlannerSwitchEdgeCostDeltaWithConflicts(
			memoTable, child.getHopID(), FederatedOutput.LOUT, FederatedOutput.FOUT,
			parentCurrentLout, false, 1, conflictMap);
		FederatedOutput chosen = invokeResolveOneHopConflictWithConflictMap(
			memoTable, child.getHopID(), entry, new HashMap<>(), 1, conflictMap);

		assertTrue("Observed downstream LOUT must not hide a cheaper feasible FOUT parent variant",
			contextDelta < 0.0);
		assertEquals("A LOUT observation is not a hard demand when the parent can choose FOUT",
			FederatedOutput.FOUT, chosen);
	}

	@Test
	public void testDpSeenOnlyOutputReevaluatesCheaperAlternativePlan()
		throws Exception {
		DataOp target = transientRead("seenOnlyCheaperAlternative", 1000, 1000);
		UnaryOp parent = new UnaryOp("parentSeenOnlyCheaperAlternative", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, target);
		parent.setDim1(1000);
		parent.setDim2(1000);
		DataOp dummyRoot = transientRead("RootSeenOnlyCheaperAlternative", 1, 1);

		Map<Long, HopCommon> hopCommonTable = new HashMap<>();
		HopCommon targetCommon = registerHopCommon(hopCommonTable, target);
		HopCommon parentCommon = registerHopCommon(hopCommonTable, parent);
		HopCommon dummyRootCommon = registerHopCommon(hopCommonTable, dummyRoot);

		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		addCustomPlan(memoTable, targetCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 100.0);
		addCustomPlan(memoTable, targetCommon,
			FederatedOutput.FOUT, ExecType.CP, FType.ROW, 10.0);
		addPlanWithChildren(memoTable, parentCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 110.0,
			List.of(Pair.of(target.getHopID(), FederatedOutput.LOUT)));
		addPlanWithChildren(memoTable, parentCommon,
			FederatedOutput.FOUT, ExecType.CP, FType.ROW, 20.0,
			List.of(Pair.of(target.getHopID(), FederatedOutput.FOUT)));
		FedPlan dummyRootPlan = addPlanWithChildren(memoTable, dummyRootCommon,
			FederatedOutput.LOUT, ExecType.CP, FType.ROW, 0.0,
			List.of(Pair.of(parent.getHopID(), FederatedOutput.LOUT)));
		memoTable.registerHopRefs(hopCommonTable);

		Map<Long, FederatedOutput> decisions = invokeComputeOutputDecisions(memoTable, dummyRootPlan);

		assertEquals("A seen-only LOUT edge must still be re-evaluated when the memoized FOUT"
				+ " alternative is cheaper under the same DP cost model",
			FederatedOutput.FOUT, decisions.get(target.getHopID()));
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
	public void testDirectFederatedQuaternaryLopsParse() {
		assertDirectFederatedQuaternaryInstruction(
			new QuaternaryOp("wsloss", DataType.SCALAR, ValueType.FP64, OpOp4.WSLOSS,
				federatedRead("Xwsloss", ROWS, COLS), transientRead("Uwsloss", ROWS, 2),
				transientRead("Vwsloss", COLS, 2), transientRead("Wwsloss", ROWS, COLS), false),
			"wsloss", true);
		assertDirectFederatedQuaternaryInstruction(
			new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64, OpOp4.WSIGMOID,
				federatedRead("Xwsigmoid", ROWS, COLS), transientRead("Uwsigmoid", ROWS, 2),
				transientRead("Vwsigmoid", COLS, 2), false, false),
			"wsigmoid", false);
		assertDirectFederatedQuaternaryInstruction(
			new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64, OpOp4.WDIVMM,
				federatedRead("Xwdivmm", ROWS, COLS), transientRead("Uwdivmm", ROWS, 2),
				transientRead("Vwdivmm", COLS, 2), new LiteralOp(-1), 1, false, false),
			"wdivmm", true);
		assertDirectFederatedQuaternaryInstruction(
			new QuaternaryOp("wcemm", DataType.SCALAR, ValueType.FP64, OpOp4.WCEMM,
				federatedRead("Xwcemm", ROWS, COLS), transientRead("Uwcemm", ROWS, 2),
				transientRead("Vwcemm", COLS, 2), new LiteralOp(0.1), 1, false, false),
			"wcemm", true, "0.1");
		assertDirectFederatedQuaternaryInstruction(
			new QuaternaryOp("wumm", DataType.MATRIX, ValueType.FP64, OpOp4.WUMM,
				federatedRead("Xwumm", ROWS, COLS), transientRead("Uwumm", ROWS, 2),
				transientRead("Vwumm", COLS, 2), true, OpOp1.MULT2, null),
			"wumm", false);
	}

	@Test
	public void testPlannerAllowsSupportedFederatedWdivmmHopExecution() {
		DataOp x = federatedRead("XwdivmmPlan", ROWS, COLS);
		DataOp u = transientRead("UwdivmmPlan", ROWS, 2);
		DataOp v = transientRead("VwdivmmPlan", COLS, 2);
		QuaternaryOp wdivmm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 1, false, false);

		Map<Long, FType> fTypes = new HashMap<>();
		fTypes.put(x.getHopID(), FType.ROW);
		fTypes.put(u.getHopID(), FType.FULL);
		fTypes.put(v.getHopID(), FType.FULL);

		assertTrue("WDIVMM has a direct FED lowering path backed by QuaternaryWDivMMFEDInstruction",
			FederatedRefedPolicy.canExecuteFederatedHop(wdivmm));
		assertTrue("Supported WDIVMM inputs should be feasible for planner-enforced FED execution",
			FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(wdivmm, fTypes));
	}

	@Test
	public void testPlannerDoesNotAdvertiseNativeFederatedWdivmmForFullX() {
		DataOp x = federatedRead("XwdivmmFullPlan", ROWS, COLS);
		DataOp u = transientRead("UwdivmmFullPlan", ROWS, 2);
		DataOp v = transientRead("VwdivmmFullPlan", COLS, 2);
		QuaternaryOp wdivmm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 1, false, false);
		OpSig sig = OpSig.of(Opcodes.WDIVMM.toString(), OpCategory.QUATERNARY,
			Map.of("q.type", "WDIVMM", "wdivmm.baseType", "1"),
			InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);

		OpCaps caps = new Rulesets.WeightedDivMMRule().caps(sig,
			Arrays.asList(FType.FULL, FType.ROW, FType.ROW, null), null);
		ExecPlacementPolicy.Decision decision = ExecPlacementPolicy.decide(
			wdivmm, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, caps.foutFType().orElse(null), caps);

		assertEquals("FULL-X WDIVMM should use coordinator execution because QuaternaryWDivMMFEDInstruction "
			+ "supports native FED only for ROW/COL X", ExecType.CP, caps.exec());
		assertEquals("FULL-X WDIVMM may still materialize the local result as FOUT", FederatedOutput.FOUT,
			caps.placement());
		assertTrue("FULL-X WDIVMM should retain CP->FOUT as a cost competitor", decision.allowCP_FOUT);
		assertFalse("FULL-X WDIVMM must not advertise native FED/FOUT", decision.allowFED_FOUT);
		assertFalse("FULL-X WDIVMM must not advertise native FED/LOUT", decision.allowFED_LOUT);
	}

	@Test
	public void testPlannerKeepsLegalWdivmmLocalAggregationFedCandidateOpen() {
		DataOp x = federatedRead("XwdivmmLocalAggPlan", ROWS, COLS);
		DataOp u = transientRead("UwdivmmLocalAggPlan", ROWS, 2);
		DataOp v = transientRead("VwdivmmLocalAggPlan", COLS, 2);
		QuaternaryOp wdivmm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 1, false, false);
		OpSig sig = OpSig.of(Opcodes.WDIVMM.toString(), OpCategory.QUATERNARY,
			Map.of("q.type", "WDIVMM", "wdivmm.baseType", "1"),
			InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);

		OpCaps caps = new Rulesets.WeightedDivMMRule().caps(sig,
			Arrays.asList(FType.ROW, FType.ROW, FType.ROW, null),
			new ShapeHint(5, 2, BLOCKSIZE, java.util.Optional.empty(), ROWS, COLS, ROWS, 2));
		ExecPlacementPolicy.Decision decision = ExecPlacementPolicy.decide(
			wdivmm, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, caps.foutFType().orElse(null), caps);

		assertEquals("ROW-X left WDIVMM local aggregation is legal at runtime and must remain costed",
			ExecType.FED, caps.exec());
		assertEquals("ROW-X left WDIVMM local aggregation produces a local coordinator result",
			FederatedOutput.LOUT,
			caps.placement());
		assertEquals("Local aggregation is an alignment/topology detail, not a candidate-space ban",
			ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, caps.reason());
		assertTrue("WDivMM local aggregation must retain a local CP competitor", decision.allowCP_LOUT);
		assertTrue("WDivMM local aggregation must retain the legal FED/LOUT candidate for cost comparison",
			decision.allowFED_LOUT);
		assertFalse("WDivMM local aggregation has no native FED/FOUT result", decision.allowFED_FOUT);
	}

	@Test
	public void testWdivmmLocalAggregationAddsReplicatedPartialResultCost() {
		DataOp x = federatedRead("XwdivmmAggPlan", ROWS, COLS);
		DataOp u = transientRead("UwdivmmAggPlan", ROWS, 2);
		DataOp v = transientRead("VwdivmmAggPlan", COLS, 2);
		QuaternaryOp leftWdivmm = new QuaternaryOp("wdivmmLeft", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 1, false, false);
		QuaternaryOp rightWdivmm = new QuaternaryOp("wdivmmRight", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 2, false, false);

		assertTrue("Left WDIVMM over ROW-partitioned X uses runtime local aggregation",
			FederatedCostModel.requiresFederatedWdivmmLocalAggregation(leftWdivmm, FType.ROW));
		assertFalse("Left WDIVMM over COL-partitioned X can keep native federated output",
			FederatedCostModel.requiresFederatedWdivmmLocalAggregation(leftWdivmm, FType.COL));
		assertTrue("Right WDIVMM over COL-partitioned X uses runtime local aggregation",
			FederatedCostModel.requiresFederatedWdivmmLocalAggregation(rightWdivmm, FType.COL));
		assertFalse("Right WDIVMM over ROW-partitioned X can keep native federated output",
			FederatedCostModel.requiresFederatedWdivmmLocalAggregation(rightWdivmm, FType.ROW));

		double resultMem = ROWS * 2 * (double) OptimizerUtils.DOUBLE_SIZE;
		double ordinaryDownload = FederatedCostModel.computeDownloadNetworkCost(resultMem, FType.ROW, 4);
		double localAggCost = FederatedCostModel.computeWdivmmLocalAggregationCost(
			leftWdivmm, FType.ROW, resultMem, 4);
		assertTrue("Local aggregation must charge one full partial result per worker plus coordinator aggregation",
			localAggCost > ordinaryDownload);
		assertEquals("ROW-X WDIVMM local aggregation should not apply generic linear FED compute speedup",
			100.0, FederatedCostModel.adjustFederatedComputeCostForWdivmmLocalAggregation(
				leftWdivmm, FType.ROW, 100.0, 25.0), 0.0);
		assertEquals("Non-local-aggregation WDIVMM keeps the ordinary FED compute estimate",
			25.0, FederatedCostModel.adjustFederatedComputeCostForWdivmmLocalAggregation(
				leftWdivmm, FType.COL, 100.0, 25.0), 0.0);
		double rowInputPrepCost = FederatedCostModel.computeWdivmmInputPreparationCost(leftWdivmm,
			leftWdivmm.getInput(), Arrays.asList(FType.ROW, FType.FULL, FType.FULL, null), 4);
		double rowAlignedUInputPrepCost = FederatedCostModel.computeWdivmmInputPreparationCost(leftWdivmm,
			leftWdivmm.getInput(), Arrays.asList(FType.ROW, FType.ROW, FType.FULL, null), 4);
		double colInputPrepCost = FederatedCostModel.computeWdivmmInputPreparationCost(rightWdivmm,
			rightWdivmm.getInput(), Arrays.asList(FType.COL, FType.FULL, FType.FULL, null), 4);
		assertTrue("ROW-X WDIVMM must charge runtime input preparation for sliced U plus full V broadcast",
			rowInputPrepCost > rowAlignedUInputPrepCost);
		assertTrue("ROW-X WDIVMM must still charge full V broadcast even when U is ROW-aligned",
			rowAlignedUInputPrepCost > 0.0);
		assertTrue("COL-X WDIVMM must charge runtime input preparation for full U broadcast and sliced V",
			colInputPrepCost > 0.0);
		assertEquals("Non-local-aggregation WDIVMM should not receive the special local aggregation cost",
			0.0, FederatedCostModel.computeWdivmmLocalAggregationCost(
				leftWdivmm, FType.COL, resultMem, 4), 0.0);
		assertEquals("FULL-X WDIVMM is not a native FED input-preparation path",
			0.0, FederatedCostModel.computeWdivmmInputPreparationCost(leftWdivmm,
				leftWdivmm.getInput(), Arrays.asList(FType.FULL, FType.ROW, FType.FULL, null), 4), 0.0);
	}

	@Test
	public void testAggBinaryLocalAddAggregationAddsReplicatedPartialResultCost() {
		DataOp left = federatedRead("XaggBinaryAddPlan", ROWS, COLS);
		DataOp right = transientRead("YaggBinaryAddPlan", COLS, 2);
		AggBinaryOp ba = new AggBinaryOp("ba", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);

		assertTrue("BROADCAST-left x ROW-right matrix multiply uses runtime aggAdd local aggregation",
			FederatedCostModel.requiresFederatedAggBinaryAddAggregation(ba,
				Arrays.asList(FType.BROADCAST, FType.ROW)));
		assertTrue("COL-left x ROW-right matrix multiply uses runtime aggAdd local aggregation",
			FederatedCostModel.requiresFederatedAggBinaryAddAggregation(ba,
				Arrays.asList(FType.COL, FType.ROW)));
		assertFalse("ROW-left matrix multiply local materialization binds row partitions instead",
			FederatedCostModel.requiresFederatedAggBinaryAddAggregation(ba,
				Arrays.asList(FType.ROW, FType.BROADCAST)));

		double resultMem = ROWS * 2 * (double) OptimizerUtils.DOUBLE_SIZE;
		double ordinaryDownload = FederatedCostModel.computeDownloadNetworkCost(resultMem, FType.ROW, 4);
		double localAggCost = FederatedCostModel.computeAggBinaryAddAggregationCost(ba,
			Arrays.asList(FType.BROADCAST, FType.ROW), resultMem, 4);
		assertTrue("AggBinary aggAdd must charge one full partial result per worker plus coordinator aggregation",
			localAggCost > ordinaryDownload);
		double localLeftPrepCost = FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
			Arrays.asList(left, right), Arrays.asList(null, FType.ROW), 4);
		double fullLeftPrepCost = FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
			Arrays.asList(left, right), Arrays.asList(FType.FULL, FType.ROW), 4);
			double broadcastLeftPrepCost = FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
				Arrays.asList(left, right), Arrays.asList(FType.BROADCAST, FType.ROW), 4);
			double colLeftPrepCost = FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
				Arrays.asList(left, right), Arrays.asList(FType.COL, FType.ROW), 4);
			double colLeftLocalRightPrepCost = FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
				Arrays.asList(left, right), Arrays.asList(FType.COL, null), 4);
			assertTrue("Local-left x ROW-right must charge sliced input broadcast preparation",
				localLeftPrepCost > 0.0);
		assertEquals("FULL-left x ROW-right already has federated full input state, so aggBinary input"
			+ " preparation must not charge another coordinator upload",
			0.0, fullLeftPrepCost, 0.0);
			assertEquals("BROADCAST-left x ROW-right already has federated replicated input state, so aggBinary"
				+ " input preparation must not charge another coordinator upload",
				0.0, broadcastLeftPrepCost, 0.0);
			assertEquals("COL-left x ROW-right uses the aligned COL_T federated branch after planner compatibility"
				+ " checks, so it should not charge sliced input preparation",
				0.0, colLeftPrepCost, 0.0);
			assertTrue("COL-left x local-right must charge sliced right input broadcast preparation",
				colLeftLocalRightPrepCost > 0.0);
		assertEquals("ROW-left bind path should not receive replicated-partial aggAdd cost",
			0.0, FederatedCostModel.computeAggBinaryAddAggregationCost(ba,
				Arrays.asList(FType.ROW, FType.BROADCAST), resultMem, 4), 0.0);
		assertEquals("ROW-left bind path should not receive sliced input broadcast preparation",
			0.0, FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
				Arrays.asList(left, right), Arrays.asList(FType.ROW, FType.BROADCAST), 4), 0.0);
	}

	@Test
	public void testAxisPreservingAggregateUnaryUsesReducedNativeFedCostOnlyWhenRuntimeKeepsFederatedOutput() {
		DataOp rowInput = federatedRead("XrowAggUnaryCost", ROWS, COLS);
		AggUnaryOp rowAggregate = new AggUnaryOp("rowSumsSq", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM_SQ, Direction.Row, rowInput);
		rowAggregate.setDim1(ROWS);
		rowAggregate.setDim2(1);
		rowAggregate.setNnz(-1);

		double baseSelfCost = 100.0;
		assertTrue("ROW input + row aggregate is native FED/FOUT and should not equal CP full-input self cost",
			FederatedCostModel.isNativeFederatedAggregateUnaryOutput(rowAggregate, FType.ROW));
		assertTrue("Replicated FULL aggregate-unary output is also native FED/FOUT",
			FederatedCostModel.isNativeFederatedAggregateUnaryOutput(rowAggregate, FType.FULL));
		assertTrue("Native aggregate-unary FED cost must be reduced below the CP local self cost",
			FederatedCostModel.computeNativeFederatedAggregateUnaryCost(rowAggregate, FType.ROW, baseSelfCost)
				< baseSelfCost);
		double staleFullMatrixMem = ROWS * COLS * (double) OptimizerUtils.DOUBLE_SIZE;
		double rowVectorMem = ROWS * (double) OptimizerUtils.DOUBLE_SIZE;
		double genericFullDownload = FederatedCostModel.computeDownloadNetworkCost(
			staleFullMatrixMem, FType.ROW, 4);
		double nativeRowLoutDownload =
			FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
				rowAggregate, FType.ROW, staleFullMatrixMem, 4, genericFullDownload);
		assertTrue("Native FED/LOUT aggregate-unary must use the reduced row-vector result shape,"
			+ " not a stale full-matrix boundary estimate",
			nativeRowLoutDownload < genericFullDownload);
		assertTrue("ROW aggregate over ROW federation charges result payload only; instruction"
			+ " control is already represented by the FED coordination term",
			nativeRowLoutDownload > 0.0
				&& nativeRowLoutDownload < FederatedCostModel.computeDownloadNetworkCost(rowVectorMem, FType.ROW, 4));

		AggUnaryOp oppositeAxisAggregate = new AggUnaryOp("colSumsSq", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM_SQ, Direction.Col, rowInput);
		oppositeAxisAggregate.setDim1(1);
		oppositeAxisAggregate.setDim2(COLS);
		oppositeAxisAggregate.setNnz(-1);
		assertFalse("ROW input + column aggregate requires global consolidation, not cheap native FOUT",
			FederatedCostModel.isNativeFederatedAggregateUnaryOutput(oppositeAxisAggregate, FType.ROW));
		assertEquals("Opposite-axis aggregate keeps the ordinary FED compute estimate",
			baseSelfCost,
			FederatedCostModel.computeNativeFederatedAggregateUnaryCost(
				oppositeAxisAggregate, FType.ROW, baseSelfCost), 0.0);
		double oppositeAxisLocalAggregationCost =
			FederatedCostModel.computeAggregateUnaryLocalAggregationCost(
				oppositeAxisAggregate, FType.ROW, staleFullMatrixMem, 4);
		assertTrue("Opposite-axis FED/LOUT aggregate-unary still keeps the FED candidate open"
			+ " but charges reduced partial-vector GET_VAR plus coordinator aggregation",
			oppositeAxisLocalAggregationCost > 0.0
				&& oppositeAxisLocalAggregationCost < genericFullDownload);

		AggUnaryOp rowColMatrixAggregate = new AggUnaryOp("sumAllMatrix", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.RowCol, rowInput);
		rowColMatrixAggregate.setDim1(ROWS);
		rowColMatrixAggregate.setDim2(COLS);
		rowColMatrixAggregate.setNnz(-1);
		double rowColLoutDownload =
			FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
				rowColMatrixAggregate, FType.ROW, staleFullMatrixMem, 4, genericFullDownload);
		assertTrue("Full aggregate FED/LOUT returns one scalar partial per worker, not the full input matrix",
			rowColLoutDownload > 0.0 && rowColLoutDownload < nativeRowLoutDownload);

		AggUnaryOp scalarAggregate = new AggUnaryOp("sumAll", DataType.SCALAR, ValueType.FP64,
			AggOp.SUM, Direction.RowCol, rowInput);
		assertFalse("Scalar aggregate outputs cannot be represented as federated variables",
			FederatedCostModel.isNativeFederatedAggregateUnaryOutput(scalarAggregate, FType.FULL));
		assertEquals("Scalar aggregate keeps the ordinary FED compute estimate",
			baseSelfCost,
			FederatedCostModel.computeNativeFederatedAggregateUnaryCost(scalarAggregate, FType.FULL, baseSelfCost),
			0.0);
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
				long pId = (fedInitRead.getHopID() << 2) | 1L;
				double cpUnaryCost = (double) getEdgeWeightOrZero.invoke(graph, leafedSource, cId);
				double localMaterialization = (double) getEdgeWeightOrZero.invoke(graph, cId, pId);
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
	public void testMinSTDerivedFedFoutChargesDownloadAndUpload() throws Exception {
		DataOp input = transientRead("X", ROWS, COLS);
		UnaryOp derived = HopRewriteUtils.createUnary(input, OpOp1.EXP);
		derived.setDim1(ROWS);
		derived.setDim2(COLS);

		FederatedPlanMinSTGraph.ExecPlacementCaps caps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		caps.allowCP_LOUT = true;
		caps.allowCP_FOUT = true;
		caps.allowFED_LOUT = true;
		caps.allowFED_FOUT = true;
		caps.fedFoutMode = FederatedPlanMinSTGraph.ExecPlacementCaps.FedFoutMode.DERIVED_REFED;

		Vertex vertex = new Vertex(derived, Privacy.PUBLIC, FType.ROW, FType.ROW, caps);
		vertex.setMetadata(2.0, 1.0, List.of());
		vertex.setCost(0.0, 10.0, 20.0);

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.addVertex(vertex);
		graph.addExecPlacementResultEdge(vertex);

		Field rootLocalSinkField = FederatedPlanMinSTGraph.class.getDeclaredField("rootLocalSink");
		rootLocalSinkField.setAccessible(true);
		long rootLocalSink = rootLocalSinkField.getLong(null);

		Method getEdgeWeightOrZero = FederatedPlanMinSTGraph.class.getDeclaredMethod(
			"getEdgeWeightOrZero", long.class, long.class);
		getEdgeWeightOrZero.setAccessible(true);
		long cId = derived.getHopID() << 2;
		long pId = (derived.getHopID() << 2) | 1L;

		double fedMaterialization = (double) getEdgeWeightOrZero.invoke(graph, cId, rootLocalSink);
		double foutUpload = (double) getEdgeWeightOrZero.invoke(graph, pId, rootLocalSink);
		double nativeFedLoutEdge = (double) getEdgeWeightOrZero.invoke(graph, cId, pId);
		double nativeCpFoutEdge = (double) getEdgeWeightOrZero.invoke(graph, pId, cId);

		assertEquals("Derived FED/FOUT must charge FED->local materialization as FED unary",
			40.0, fedMaterialization, 1e-9);
		assertEquals("Derived FED/FOUT must still charge CP->FOUT upload as FOUT unary",
			20.0, foutUpload, 1e-9);
		assertEquals("Derived FED/FOUT must not hide materialization behind C->P only",
			0.0, nativeFedLoutEdge, 1e-9);
		assertEquals("Derived FED/FOUT must not use native CP/FOUT edge encoding",
			0.0, nativeCpFoutEdge, 1e-9);
		assertEquals("FED/FOUT source-side cut should pay both download and upload",
			60.0, computeMinSTCutCost(graph, cId, pId), 1e-9);
	}

	@Test
	public void testMinSTConcreteFederatedSourceTransientReadKeepsCpLoutCandidate() throws Exception {
		DataOp federatedSource = new DataOp("Y_fed", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "Y_fed", ROWS, COLS, ROWS * COLS, BLOCKSIZE);
		DataOp tRead = transientRead("Y", ROWS, COLS);
		tRead.addInput(federatedSource);

		FederatedPlanMinSTGraph.ExecPlacementCaps sourceCaps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		sourceCaps.allowCP_LOUT = false;
		sourceCaps.allowCP_FOUT = false;
		sourceCaps.allowFED_LOUT = false;
		sourceCaps.allowFED_FOUT = true;
		sourceCaps.fedFoutMode = FederatedPlanMinSTGraph.ExecPlacementCaps.FedFoutMode.NATIVE;

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		Vertex sourceVertex = new Vertex(federatedSource, Privacy.PUBLIC, FType.FULL, sourceCaps);
		sourceVertex.setMetadata(1.0, 1.0, List.of());
		graph.addVertex(sourceVertex);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(federatedSource.getHopID(), FType.FULL);
		Map<Long, Privacy> privacyMap = new HashMap<>();
		privacyMap.put(federatedSource.getHopID(), Privacy.PUBLIC);
		privacyMap.put(tRead.getHopID(), Privacy.PUBLIC);
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(tRead.getHopID(), List.of(federatedSource));

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		Vertex tReadVertex = invokeRewireHop(tRead, rewireTable, graph, fTypeMap, privacyMap, oracle);

		assertNotNull("Expected concrete FED source TRead vertex", tReadVertex);
		assertTrue("Concrete FED source TRead must keep CP/LOUT open for costed local materialization",
			tReadVertex.getCaps().allowCP_LOUT);
		assertFalse("TRead legality must continue to forbid CP/FOUT", tReadVertex.getCaps().allowCP_FOUT);
		assertFalse("TRead legality must continue to forbid FED/LOUT", tReadVertex.getCaps().allowFED_LOUT);
		assertTrue("Concrete FED source TRead must keep FED/FOUT available",
			tReadVertex.getCaps().allowFED_FOUT);
		assertTrue("Concrete FED source TRead must be marked as stable for shared local materialization",
			tReadVertex.isStableFederatedInputRead());
	}

	@Test
	public void testMinSTConcreteFederatedSourceTransientReadCpCostUsesSharedDownload() throws Exception {
		DataOp federatedSource = new DataOp("Y_fed", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "Y_fed", ROWS, COLS, ROWS * COLS, BLOCKSIZE);
		DataOp tRead = transientRead("Y", ROWS, COLS);
		tRead.addInput(federatedSource);

		FederatedPlanMinSTGraph.ExecPlacementCaps sourceCaps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		sourceCaps.allowCP_LOUT = false;
		sourceCaps.allowCP_FOUT = false;
		sourceCaps.allowFED_LOUT = false;
		sourceCaps.allowFED_FOUT = true;
		sourceCaps.fedFoutMode = FederatedPlanMinSTGraph.ExecPlacementCaps.FedFoutMode.NATIVE;

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		Vertex sourceVertex = new Vertex(federatedSource, Privacy.PUBLIC, FType.FULL, sourceCaps);
		sourceVertex.setMetadata(1.0, 1.0, List.of());
		graph.addVertex(sourceVertex);

		Map<Long, FType> fTypeMap = new HashMap<>();
		fTypeMap.put(federatedSource.getHopID(), FType.FULL);
		Map<Long, Privacy> privacyMap = new HashMap<>();
		privacyMap.put(federatedSource.getHopID(), Privacy.PUBLIC);
		privacyMap.put(tRead.getHopID(), Privacy.PUBLIC);
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(tRead.getHopID(), List.of(federatedSource));

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		Vertex tReadVertex = invokeRewireHop(tRead, rewireTable, graph, fTypeMap, privacyMap, oracle);
		tReadVertex.setMetadata(30.0, 1.0, List.of());
		tReadVertex.setCost(0.0, 0.0, 100.0);
		tReadVertex.setNumParents(2);
		graph.addVertex(tReadVertex);

		Method setVertexCost = FederatedPlanMinSTGraph.class.getDeclaredMethod("setVertexCost", Vertex.class);
		setVertexCost.setAccessible(true);
		setVertexCost.invoke(graph, tReadVertex);

		Method addExecPlacementResultEdge = FederatedPlanMinSTGraph.class.getDeclaredMethod(
			"addExecPlacementResultEdge", Vertex.class);
		addExecPlacementResultEdge.setAccessible(true);
		addExecPlacementResultEdge.invoke(graph, tReadVertex);

		Field leafedSourceField = FederatedPlanMinSTGraph.class.getDeclaredField("leafedSource");
		leafedSourceField.setAccessible(true);
		long leafedSource = leafedSourceField.getLong(null);

		Method getEdgeWeightOrZero = FederatedPlanMinSTGraph.class.getDeclaredMethod(
			"getEdgeWeightOrZero", long.class, long.class);
		getEdgeWeightOrZero.setAccessible(true);
		long cId = tRead.getHopID() << 2;
		long pId = (tRead.getHopID() << 2) | 1L;
		double cpUnaryCost = (double) getEdgeWeightOrZero.invoke(graph, leafedSource, cId);
		double localMaterialization = (double) getEdgeWeightOrZero.invoke(graph, cId, pId);
		double expectedSharedDownload = 100.0 / 30.0 / 2.0;

		assertEquals("Concrete FED source TRead should pay one shared local materialization cost",
			expectedSharedDownload, cpUnaryCost, 1e-9);
		assertEquals("Concrete FED source TRead result edge should use the same amortized local materialization cost",
			expectedSharedDownload, localMaterialization, 1e-9);

		graph.addParentChildNetEdge(sourceVertex, federatedSource.getHopID(), tReadVertex, tRead.getHopID(), false);
		long sourcePId = (federatedSource.getHopID() << 2) | 1L;
		double requiredLocalHardEdge = (double) getEdgeWeightOrZero.invoke(graph, sourcePId, cId);
		assertEquals("Concrete FED source TRead CP materialization must not force the FED source primary placement local",
			0.0, requiredLocalHardEdge, 1e-9);
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
	public void testCpfoutMaterializeUsesKeyOnlyAnchorForNonRuntimeTransientAnchor() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp localInput = transientRead("GradLocal", ROWS, COLS);
			localInput.setForcedExecType(ExecType.CP);
			localInput.setFederatedOutput(FederatedOutput.LOUT);

			DataOp tWrite = HopRewriteUtils.createTransientWrite("Grad", localInput);
			tWrite.setDim1(ROWS);
			tWrite.setDim2(COLS);
			tWrite.setForcedExecType(ExecType.FED);
			tWrite.setFederatedOutput(FederatedOutput.FOUT);

			DataOp staleTransientAnchor = transientRead("Grad", ROWS, COLS);
			staleTransientAnchor.setForcedExecType(ExecType.CP);
			staleTransientAnchor.setFederatedOutput(FederatedOutput.LOUT);

			Map<Long, FType> plannedFTypes = new HashMap<>();
			plannedFTypes.put(tWrite.getHopID(), FType.ROW);
			plannedFTypes.put(staleTransientAnchor.getHopID(), FType.ROW);

			invokeRegisterCpfoutWithSelection(tWrite, plannedFTypes, -1L,
				"fedinit://workers/0,10;10,20|ROW", staleTransientAnchor);

			FederatedFoutMaterializeRegistry.MaterializeSpec spec =
				FederatedFoutMaterializeRegistry.snapshot(-1L).get(tWrite.getHopID());
			assertNotNull("Expected TWrite CP/FOUT materialization to be registered", spec);
			assertEquals("Non-runtime transient anchors must be dropped in favor of key-only materialization",
				-1L, spec.getAnchorHopId());
			assertEquals("Concrete non-VAR anchor key must be preserved for runtime worker-pool lookup",
				"fedinit://workers/0,10;10,20|ROW", spec.getAnchorKey());
			assertEquals("Planner-selected row partition hint must be preserved", "ROW", spec.getFTypeHint());
			assertEquals("The transient write should expose its concrete anchor key to downstream TReads",
				"fedinit://workers/0,10;10,20|ROW", FederatedPlannerUtils.getFedAnchorKey("Grad"));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagFoutMaterializePrefersConcreteKeyOverTransientAnchorLop() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			DataOp localInput = transientRead("V", ROWS, COLS);
			localInput.setForcedExecType(ExecType.CP);
			localInput.setFederatedOutput(FederatedOutput.LOUT);

			DataOp transientAnchor = transientRead("Grad", ROWS, COLS);
			transientAnchor.setForcedExecType(ExecType.FED);
			transientAnchor.setFederatedOutput(FederatedOutput.FOUT);

			BinaryOp fedParent = HopRewriteUtils.createBinary(localInput, transientAnchor, OpOp2.PLUS);
			fedParent.setDim1(ROWS);
			fedParent.setDim2(COLS);
			fedParent.setForcedExecType(ExecType.FED);
			fedParent.setFederatedOutput(FederatedOutput.FOUT);

			FederatedFoutMaterializeRegistry.register(-1L, localInput.getHopID(), transientAnchor.getHopID(),
				"ROW", "Grad", anchorKey);

			Lop parentLop = fedParent.constructLops();
			Dag<Lop> dag = new Dag<>();
			parentLop.addToDag(dag);
			String foutInstruction = null;
			for (Instruction inst : dag.getJobs(null, ConfigurationManager.getDMLConfig())) {
				String istr = inst.getInstructionString();
				if (istr.contains("fed_fout")) {
					foutInstruction = istr;
					break;
				}
			}

			assertNotNull("Expected CP->FOUT materialize instruction for FED parent demand", foutInstruction);
			assertTrue("Concrete anchor key should be emitted as literal worker-pool anchor: " + foutInstruction,
				foutInstruction.contains(anchorKey));
			assertFalse("Transient Grad must not be emitted as runtime anchor when a concrete key is available: "
				+ foutInstruction, foutInstruction.contains(Lop.OPERAND_DELIMITOR + "Grad"));
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
	public void testMinSTRequiredLocalDemandAddsGraphConstraint() {
		DataOp fedChild = federatedRead("Yconstraint", ROWS, COLS);
		BinaryOp parent = new BinaryOp("gtConstraint", DataType.MATRIX, ValueType.FP64,
			OpOp2.GREATER, fedChild, new LiteralOp(0L));

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.addVertex(new Vertex(fedChild, Privacy.PUBLIC, FType.ROW, FType.ROW, allowAllCaps()));
		graph.addVertex(new Vertex(parent, Privacy.PRIVATE_AGGREGATE, FType.ROW, FType.ROW, allowAllCaps()));
		graph.addRequiredLocalInputEdge(parent.getHopID(), fedChild.getHopID());

		long childP = minstPlacementId(fedChild.getHopID());
		long parentC = minstComputeId(parent.getHopID());
		DefaultWeightedEdge constraint = graph.getGraph().getEdge(childP, parentC);
		assertNotNull("Required-local demand must be encoded before the cut", constraint);
		assertTrue("Constraint must make child FOUT + parent CP infeasible",
			graph.getGraph().getEdgeWeight(constraint) > 1e12);
	}

	@Test
	public void testMinSTRequiredLocalConstraintTruthTable() {
		DataOp fedChild = federatedRead("YconstraintTable", ROWS, COLS);
		BinaryOp parent = new BinaryOp("gtConstraintTable", DataType.MATRIX, ValueType.FP64,
			OpOp2.GREATER, fedChild, new LiteralOp(0L));

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.addVertex(new Vertex(fedChild, Privacy.PUBLIC, FType.ROW, FType.ROW, allowAllCaps()));
		graph.addVertex(new Vertex(parent, Privacy.PRIVATE_AGGREGATE, FType.ROW, FType.ROW, allowAllCaps()));
		graph.addRequiredLocalInputEdge(parent.getHopID(), fedChild.getHopID());

		long childP = minstPlacementId(fedChild.getHopID());
		long parentC = minstComputeId(parent.getHopID());
		assertTrue("Child FOUT with CP parent must cut the hard constraint",
			computeMinSTCutCost(graph, childP) > 1e12);
		assertEquals("Child LOUT with CP parent is allowed", 0.0,
			computeMinSTCutCost(graph), 1e-9);
		assertEquals("Child FOUT with FED parent is allowed by this local-demand constraint", 0.0,
			computeMinSTCutCost(graph, childP, parentC), 1e-9);
		assertEquals("Child LOUT with FED parent is allowed by this local-demand constraint", 0.0,
			computeMinSTCutCost(graph, parentC), 1e-9);
	}

	@Test
	public void testMinSTRepairFixpointPromotesRawFedLoutToFedFoutForSelectedFoutChain() throws Exception {
		DataOp fedInput = federatedRead("X", ROWS, COLS);
		DataOp localVec = federatedRead("V", COLS, 1);

		Hop agg = HopRewriteUtils.createMatrixMultiply(fedInput, localVec);
		agg.setDim1(ROWS);
		agg.setDim2(1);
		DataOp localSide = transientRead("P_1K", ROWS, 1);
		localSide.setForcedExecType(ExecType.CP);
		localSide.setFederatedOutput(FederatedOutput.LOUT);
		BinaryOp consumer = new BinaryOp("mul", DataType.MATRIX, ValueType.FP64, OpOp2.MULT, localSide, agg);
		consumer.setDim1(ROWS);
		consumer.setDim2(1);
		DataOp localTail = transientRead("Tail", ROWS, 1);
		localTail.setForcedExecType(ExecType.CP);
		localTail.setFederatedOutput(FederatedOutput.LOUT);
		BinaryOp terminal = new BinaryOp("minus", DataType.MATRIX, ValueType.FP64, OpOp2.MINUS, consumer, localTail);
		terminal.setDim1(ROWS);
		terminal.setDim2(1);

		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		graph.setNumOfWorkers(4);

		FederatedPlanMinSTGraph.ExecPlacementCaps tReadCaps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		tReadCaps.allowCP_LOUT = true;
		tReadCaps.allowFED_FOUT = true;
		FederatedPlanMinSTGraph.ExecPlacementCaps aggCaps = allowAllCaps();
		FederatedPlanMinSTGraph.ExecPlacementCaps consumerCaps = new FederatedPlanMinSTGraph.ExecPlacementCaps();
		consumerCaps.allowCP_LOUT = true;
		consumerCaps.allowCP_FOUT = true;
		consumerCaps.allowFED_FOUT = true;

		graph.addVertex(new Vertex(fedInput, Privacy.PUBLIC, FType.ROW, FType.ROW, tReadCaps));
		graph.addVertex(new Vertex(localVec, Privacy.PUBLIC, FType.ROW, FType.ROW, tReadCaps));
		graph.addVertex(new Vertex(localSide, Privacy.PUBLIC, null, null, allowAllCaps()));
		graph.addVertex(new Vertex(localTail, Privacy.PUBLIC, null, null, allowAllCaps()));

		Vertex aggVertex = new Vertex(agg, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, FType.ROW, FType.ROW, aggCaps);
		aggVertex.setMetadata(300.0, 300.0, List.of());
		aggVertex.setCost(9617.812225, 1345.201360, 1352.181808);
		graph.addVertex(aggVertex);

		Vertex consumerVertex = new Vertex(consumer, Privacy.PRIVATE_AGGREGATE, FType.ROW, FType.ROW, consumerCaps);
		consumerVertex.setMetadata(300.0, 300.0, List.of());
		consumerVertex.setCost(13.738129, 1345.201360, 1352.181808);
		graph.addVertex(consumerVertex);
		Vertex terminalVertex = new Vertex(terminal, Privacy.PRIVATE_AGGREGATE, FType.ROW, FType.ROW, consumerCaps);
		terminalVertex.setMetadata(300.0, 300.0, List.of());
		terminalVertex.setCost(13.738129, 1345.201360, 1352.181808);
		graph.addVertex(terminalVertex);
		graph.addRequiredLocalInputEdge(consumer.getHopID(), agg.getHopID());
		graph.addRequiredLocalInputEdge(terminal.getHopID(), consumer.getHopID());

		Map<Long, ExecType> execSelection = new HashMap<>();
		Map<Long, FederatedOutput> outSelection = new HashMap<>();
		execSelection.put(fedInput.getHopID(), ExecType.FED);
		outSelection.put(fedInput.getHopID(), FederatedOutput.FOUT);
		execSelection.put(localVec.getHopID(), ExecType.FED);
		outSelection.put(localVec.getHopID(), FederatedOutput.FOUT);
		execSelection.put(localSide.getHopID(), ExecType.CP);
		outSelection.put(localSide.getHopID(), FederatedOutput.LOUT);
		execSelection.put(localTail.getHopID(), ExecType.CP);
		outSelection.put(localTail.getHopID(), FederatedOutput.LOUT);
		execSelection.put(agg.getHopID(), ExecType.FED);
		outSelection.put(agg.getHopID(), FederatedOutput.LOUT); // raw MinST selection
		execSelection.put(consumer.getHopID(), ExecType.CP);
		outSelection.put(consumer.getHopID(), FederatedOutput.FOUT); // selected downstream FOUT chain
		execSelection.put(terminal.getHopID(), ExecType.CP);
		outSelection.put(terminal.getHopID(), FederatedOutput.FOUT);

		invokeRepairSelectionFixpoint(graph, execSelection, outSelection);

		assertEquals("Raw FED/LOUT aggregate should stay federated when only output placement needs repair",
			ExecType.FED, execSelection.get(agg.getHopID()));
		assertEquals("Selected downstream FOUT chain should promote raw FED/LOUT to FED/FOUT instead of CP/FOUT",
			FederatedOutput.FOUT, outSelection.get(agg.getHopID()));
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

	private static void assertDirectFederatedQuaternaryInstruction(QuaternaryOp hop, String opcode, boolean hasFourInputSlot) {
		assertDirectFederatedQuaternaryInstruction(hop, opcode, hasFourInputSlot, "W");
	}

	private static void assertDirectFederatedQuaternaryInstruction(QuaternaryOp hop, String opcode,
			boolean hasFourInputSlot, String input4Name) {
		assertTrue("QuaternaryOp should advertise existing FED runtime support", hop.supportsFederatedExecution());
		hop.setForcedExecType(ExecType.FED);
		Lop lop = hop.constructLops();
		assertEquals("Expected direct FED lop lowering", ExecType.FED, lop.getExecType());
		String instruction = hasFourInputSlot
			? lop.getInstructions("X", "U", "V", input4Name, "OUT")
			: lop.getInstructions("X", "U", "V", "OUT");
		assertTrue("Instruction should use FED exec prefix: " + instruction,
			instruction.startsWith(ExecType.FED.name() + Lop.OPERAND_DELIMITOR + opcode));
		assertFalse("Direct FED quaternary lowering should not emit Spark map/reduce opcode: " + instruction,
			instruction.contains("map") || instruction.contains("red"));
		Instruction parsed = FEDInstructionParser.parseSingleInstruction(instruction);
		assertTrue("FED parser should dispatch quaternary instruction: " + instruction,
			parsed instanceof QuaternaryFEDInstruction);
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

	private static double invokeDpEnumeratorForwardingShare(
			boolean parentIsFed, FederatedOutput childOut,
			FedPlan childPlan, FedPlan parentPlan, int numWorkers) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"computeParentChildForwardingCostShare",
			boolean.class, FederatedOutput.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			int.class);
		method.setAccessible(true);
		return (double) method.invoke(null, parentIsFed, childOut, childPlan, parentPlan, numWorkers);
	}

	private static void invokePromoteLocalFedInputHints(FederatedPlannerDpMemoTable memoTable,
			List<Pair<Long, FederatedOutput>> planChilds, List<Hop> collectedHops, List<FType> collectedFTypes,
			Map<Long, FType> fedInputTypeMap) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"promoteLocalFedInputHints",
			FederatedPlannerDpMemoTable.class,
			List.class, List.class, List.class, Map.class);
		method.setAccessible(true);
		method.invoke(null, memoTable, planChilds, collectedHops, collectedFTypes, fedInputTypeMap);
	}

	private static double invokeDpParentVariantSwitchDelta(
			FederatedPlannerDpMemoTable memoTable, FedPlan parentPlan,
			long childHopID, FederatedOutput desiredChildOut) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeParentVariantSwitchDelta",
			FederatedPlannerDpMemoTable.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			long.class,
			FederatedOutput.class);
		method.setAccessible(true);
		return (double) method.invoke(null, memoTable, parentPlan, childHopID, desiredChildOut);
	}

	private static double invokeDpEnumeratorParentVariantSwitchDelta(
			FederatedPlannerDpMemoTable memoTable, FedPlan parentPlan,
			long childHopID, FederatedOutput desiredChildOut) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"computeParentVariantSwitchDelta",
			FederatedPlannerDpMemoTable.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			long.class,
			FederatedOutput.class);
		method.setAccessible(true);
		return (double) method.invoke(null, memoTable, parentPlan, childHopID, desiredChildOut);
	}

	private static double invokeDpPlannerSwitchEdgeCostDelta(
			FederatedPlannerDpMemoTable memoTable, long childHopID,
			FederatedOutput fromOut, FederatedOutput toOut,
			FedPlan parentPlan, boolean parentIsFed, int numWorkers) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeSwitchEdgeCostDelta",
			FederatedPlannerDpMemoTable.class,
			long.class,
			FederatedOutput.class,
			FederatedOutput.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			boolean.class,
			int.class);
		method.setAccessible(true);
		return (double) method.invoke(null, memoTable, childHopID, fromOut, toOut, parentPlan, parentIsFed, numWorkers);
	}

	private static double invokeDpPlannerSwitchEdgeCostDeltaWithConflicts(
			FederatedPlannerDpMemoTable memoTable, long childHopID,
			FederatedOutput fromOut, FederatedOutput toOut,
			FedPlan parentPlan, boolean parentIsFed, int numWorkers,
			Map<Long, ?> conflictCheckMap) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeSwitchEdgeCostDelta",
			FederatedPlannerDpMemoTable.class,
			long.class,
			FederatedOutput.class,
			FederatedOutput.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			boolean.class,
			int.class,
			Map.class);
		method.setAccessible(true);
		return (double) method.invoke(
			null, memoTable, childHopID, fromOut, toOut, parentPlan, parentIsFed, numWorkers, conflictCheckMap);
	}

	private static double invokeDpEnumeratorSwitchEdgeCostDelta(
			FederatedPlannerDpMemoTable memoTable, long childHopID,
			FederatedOutput fromOut, FederatedOutput toOut,
			FedPlan parentPlan, boolean parentIsFed, int numWorkers) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod(
			"computeSwitchEdgeCostDelta",
			FederatedPlannerDpMemoTable.class,
			long.class,
			FederatedOutput.class,
			FederatedOutput.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			boolean.class,
			int.class);
		method.setAccessible(true);
		return (double) method.invoke(null, memoTable, childHopID, fromOut, toOut, parentPlan, parentIsFed, numWorkers);
	}

	@SuppressWarnings("unchecked")
	private static Vertex invokeRewireHop(Hop hop, FederatedPlanMinSTGraph graph, Map<Long, FType> fTypeMap,
			Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap, OracleFacade oracleFacade)
			throws Exception {
		return invokeRewireHop(hop, new HashMap<Long, List<Hop>>(), graph, fTypeMap, privacyMap, oracleFacade);
	}

	@SuppressWarnings("unchecked")
	private static Vertex invokeRewireHop(Hop hop, Map<Long, List<Hop>> rewireTable,
			FederatedPlanMinSTGraph graph, Map<Long, FType> fTypeMap,
			Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyMap, OracleFacade oracleFacade)
			throws Exception {
		Method method = FederatedPlanMinSTRewire.class.getDeclaredMethod(
			"rewireHop", Hop.class, Map.class, List.class, Map.class, Map.class,
			Map.class, FederatedPlanMinSTGraph.class, Map.class, List.class, Set.class,
			Set.class, List.class, OracleFacade.class);
		method.setAccessible(true);
		return (Vertex) method.invoke(null, hop, rewireTable, new ArrayList<>(),
			new HashMap<>(), new HashMap<>(), privacyMap, graph, fTypeMap, new ArrayList<>(),
			new HashSet<>(), new HashSet<>(), new ArrayList<>(), oracleFacade);
	}

	private static FederatedPlanMinSTGraph.ExecPlacementCaps invokeBuildExecPlacementCaps(
			Hop hop, Privacy privacy, FType fType, OpCaps capsOracle, Map<Long, FType> fTypeMap)
			throws Exception {
		Method method = FederatedPlanMinSTRewire.class.getDeclaredMethod(
			"buildExecPlacementCaps", Hop.class, Privacy.class, FType.class, OpCaps.class, Map.class);
		method.setAccessible(true);
		return (FederatedPlanMinSTGraph.ExecPlacementCaps) method.invoke(
			null, hop, privacy, fType, capsOracle, fTypeMap);
	}

	private static void invokeDpRewriteHop(FederatedPlannerDpFedCostBased planner, FedPlan plan,
			FederatedPlannerDpMemoTable memoTable) throws Exception {
		invokeDpRewriteHop(planner, plan, memoTable, new HashMap<Long, FederatedOutput>());
	}

	private static void invokeDpRewriteHop(FederatedPlannerDpFedCostBased planner, FedPlan plan,
			FederatedPlannerDpMemoTable memoTable, Map<Long, FederatedOutput> outputDecisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"rewriteHop", FederatedPlannerDpMemoTable.FedPlan.class, FederatedPlannerDpMemoTable.class,
			Map.class, Set.class, Map.class);
		method.setAccessible(true);
		method.invoke(planner, plan, memoTable, outputDecisions,
			new HashSet<Long>(), new HashMap<Long, FType>());
	}

	private static void invokeDpRewriteHopWithConflictMap(FederatedPlannerDpFedCostBased planner, FedPlan plan,
			FederatedPlannerDpMemoTable memoTable, Map<Long, FederatedOutput> outputDecisions,
			Map<Long, ?> conflictMap) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"rewriteHop", FederatedPlannerDpMemoTable.FedPlan.class, FederatedPlannerDpMemoTable.class,
			Map.class, Set.class, Map.class, Map.class);
		method.setAccessible(true);
		method.invoke(planner, plan, memoTable, outputDecisions,
			new HashSet<Long>(), new HashMap<Long, FType>(), conflictMap);
	}

	private static FedPlan invokeSelectRewritePlanVariant(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			FederatedOutput desiredOut,
			FederatedOutput inheritedOut,
			FedPlan fallbackPlan,
			Map<Long, FederatedOutput> outputDecisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"selectRewritePlanVariant",
			FederatedPlannerDpMemoTable.class,
			long.class,
			FederatedOutput.class,
			FederatedOutput.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			Map.class,
			Map.class,
			boolean.class);
		method.setAccessible(true);
		return (FedPlan) method.invoke(null, memoTable, hopID, desiredOut, inheritedOut,
			fallbackPlan, outputDecisions, new HashMap<Long, Object>(), true);
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

	@SuppressWarnings("unchecked")
	private static Map<Long, FType> invokeBuildContextuallyFeasibleDecisionFTypeMap(
			FederatedPlannerDpMemoTable memoTable,
			FedPlan rootPlan,
			Map<Long, FederatedOutput> outputDecisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"buildContextuallyFeasibleDecisionFTypeMap",
			FederatedPlannerDpMemoTable.class,
			FedPlan.class,
			Map.class);
		method.setAccessible(true);
		return (Map<Long, FType>) method.invoke(null, memoTable, rootPlan, outputDecisions);
	}

	private static FederatedOutput invokeResolveOneHopConflict(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			Object conflictEntry,
			Map<Long, FederatedOutput> tentativeDecisions,
			int numWorkers) throws Exception {
		Class<?> conflictEntryClass = Class.forName(
			FederatedPlannerDpFedCostBased.class.getName() + "$ConflictEntry");
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"resolveOneHopConflict",
			FederatedPlannerDpMemoTable.class,
			long.class,
			conflictEntryClass,
			Map.class,
			int.class);
		method.setAccessible(true);
		return (FederatedOutput) method.invoke(null, memoTable, hopID, conflictEntry, tentativeDecisions, numWorkers);
	}

	private static FederatedOutput invokeResolveOneHopConflictWithConflictMap(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			Object conflictEntry,
			Map<Long, FederatedOutput> tentativeDecisions,
			int numWorkers,
			Map<Long, ?> conflictCheckMap) throws Exception {
		Class<?> conflictEntryClass = Class.forName(
			FederatedPlannerDpFedCostBased.class.getName() + "$ConflictEntry");
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"resolveOneHopConflict",
			FederatedPlannerDpMemoTable.class,
			long.class,
			conflictEntryClass,
			Map.class,
			int.class,
			Map.class);
		method.setAccessible(true);
		return (FederatedOutput) method.invoke(
			null, memoTable, hopID, conflictEntry, tentativeDecisions, numWorkers, conflictCheckMap);
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, FederatedOutput> invokeComputeOutputDecisions(
			FederatedPlannerDpMemoTable memoTable,
			FedPlan rootPlan) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeOutputDecisions",
			FederatedPlannerDpMemoTable.class,
			FederatedPlannerDpMemoTable.FedPlan.class);
		method.setAccessible(true);
		return (Map<Long, FederatedOutput>) method.invoke(null, memoTable, rootPlan);
	}

	private static void invokeApplyRequiredOutputDecisionClosure(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			FederatedOutput desiredOut,
			Map<Long, ?> conflictMap,
			Map<Long, FederatedOutput> decisions,
			LinkedHashSet<Long> closureHopIDs) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"applyRequiredOutputDecisionClosure",
			FederatedPlannerDpMemoTable.class,
			long.class,
			FederatedOutput.class,
			Map.class,
			Map.class,
			LinkedHashSet.class,
			Set.class);
		method.setAccessible(true);
		method.invoke(null, memoTable, hopID, desiredOut, conflictMap, decisions,
			closureHopIDs, new HashSet<String>());
	}

	private static double invokeComputeDecisionMapTotalCost(
			FederatedPlannerDpMemoTable memoTable,
			FedPlan rootPlan,
			Map<Long, FederatedOutput> outputDecisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeDecisionMapScoreBreakdown",
			FederatedPlannerDpMemoTable.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			Map.class);
		method.setAccessible(true);
		Object breakdown = method.invoke(null, memoTable, rootPlan, outputDecisions);
		Field totalCostField = breakdown.getClass().getDeclaredField("totalCost");
		totalCostField.setAccessible(true);
		return totalCostField.getDouble(breakdown);
	}

	private static double invokeComputeCloneFamilyRewriteCost(
			FederatedPlannerDpMemoTable memoTable,
			Set<Long> familyMemberIDs,
			FedPlan referencePlan,
			Map<Long, FederatedOutput> outputDecisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeCloneFamilyRewriteCost",
			FederatedPlannerDpMemoTable.class,
			Set.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			Map.class);
		method.setAccessible(true);
		return (Double) method.invoke(null, memoTable, familyMemberIDs, referencePlan, outputDecisions);
	}

	private static void invokeRefreshConflictChoiceFeasibility(
			Map<Long, ?> conflictCheckMap, FederatedPlannerDpMemoTable memoTable) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"refreshConflictChoiceFeasibility", Map.class, FederatedPlannerDpMemoTable.class);
		method.setAccessible(true);
		method.invoke(null, conflictCheckMap, memoTable);
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, LinkedHashSet<Long>> invokeBuildConflictParentGraph(
			FederatedPlannerDpMemoTable memoTable, Map<Long, ?> conflictCheckMap) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"buildConflictParentGraph", FederatedPlannerDpMemoTable.class, Map.class);
		method.setAccessible(true);
		return (Map<Long, LinkedHashSet<Long>>) method.invoke(null, memoTable, conflictCheckMap);
	}

	@SuppressWarnings("unchecked")
	private static LinkedHashSet<Long> invokeCollectTransientFamilyDecisionHopIDs(
			FederatedPlannerDpMemoTable memoTable, long tWriteHopID, Map<Long, ?> conflictCheckMap,
			Map<Long, LinkedHashSet<Long>> parentGraph) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"collectTransientFamilyDecisionHopIDs",
			FederatedPlannerDpMemoTable.class,
			long.class,
			Map.class,
			Map.class);
		method.setAccessible(true);
		return (LinkedHashSet<Long>) method.invoke(null, memoTable, tWriteHopID, conflictCheckMap, parentGraph);
	}

	@SuppressWarnings("unchecked")
	private static LinkedHashSet<Long> invokeCollectContextuallyFeasibleTransientBundleHopIDs(
			FederatedPlannerDpMemoTable memoTable,
			FedPlan rootPlan,
			Map<Long, FederatedOutput> baseDecisions,
			Map<Long, ?> conflictCheckMap,
			LinkedHashSet<Long> familyHopIDs,
			LinkedHashSet<Long> bundleHopIDs) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"collectContextuallyFeasibleTransientBundleHopIDs",
			FederatedPlannerDpMemoTable.class,
			FederatedPlannerDpMemoTable.FedPlan.class,
			Map.class,
			Map.class,
			LinkedHashSet.class,
			LinkedHashSet.class);
		method.setAccessible(true);
		return (LinkedHashSet<Long>) method.invoke(
			null, memoTable, rootPlan, baseDecisions, conflictCheckMap, familyHopIDs, bundleHopIDs);
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

	private static double computeMinSTCutCost(FederatedPlanMinSTGraph graph, long... sourceNodes) {
		Set<Long> sourceSide = new HashSet<>();
		for (long node : sourceNodes)
			sourceSide.add(node);
		double cost = 0.0;
		for (DefaultWeightedEdge edge : graph.getGraph().edgeSet()) {
			Long src = graph.getGraph().getEdgeSource(edge);
			Long dst = graph.getGraph().getEdgeTarget(edge);
			if (sourceSide.contains(src) && !sourceSide.contains(dst))
				cost += graph.getGraph().getEdgeWeight(edge);
		}
		return cost;
	}

	private static long minstComputeId(long hopId) {
		return hopId << 2;
	}

	private static long minstPlacementId(long hopId) {
		return (hopId << 2) | 1;
	}

	private static boolean invokeDemoteStaleTransientWriteFederatedSelections(List<Hop> all,
			Map<Long, FType> fTypeMap, long sbId, boolean conditionalContext) throws Exception {
		Method method = FederatedRefedPolicy.class.getDeclaredMethod(
			"demoteStaleTransientWriteFederatedSelections", List.class, Map.class, long.class, boolean.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, all, fTypeMap, sbId, conditionalContext);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void invokeRegisterCpfoutWithSelection(Hop hop, Map<Long, FType> fTypeMap, long sbId,
			String anchorKey, Hop anchorHop) throws Exception {
		Class<?> keyTypeClass = Class.forName(FederatedRefedPolicy.class.getName() + "$AnchorKeyType");
		Object fedInitSignature = Enum.valueOf((Class<Enum>) keyTypeClass.asSubclass(Enum.class), "FEDINIT_SIGNATURE");

		Class<?> anchorKeyClass = Class.forName(FederatedRefedPolicy.class.getName() + "$AnchorKey");
		Constructor<?> anchorKeyCtor = anchorKeyClass.getDeclaredConstructor(keyTypeClass, Object.class);
		anchorKeyCtor.setAccessible(true);
		Object key = anchorKeyCtor.newInstance(fedInitSignature, anchorKey);

		Class<?> anchorSelectionClass = Class.forName(FederatedRefedPolicy.class.getName() + "$AnchorSelection");
		Constructor<?> anchorSelectionCtor = anchorSelectionClass.getDeclaredConstructor(anchorKeyClass, Hop.class);
		anchorSelectionCtor.setAccessible(true);
		Object selection = anchorSelectionCtor.newInstance(key, anchorHop);

		Method method = FederatedRefedPolicy.class.getDeclaredMethod(
			"registerCpfoutWithSelection", Hop.class, Map.class, long.class, anchorSelectionClass);
		method.setAccessible(true);
		method.invoke(null, hop, fTypeMap, sbId, selection);
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
