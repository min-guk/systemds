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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
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
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.FederatedRefed;
import org.apache.sysds.lops.FunctionCallCP;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.UnaryCP;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.ParserWrapper;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.instructions.fed.QuaternaryFEDInstruction;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.runtime.instructions.FEDInstructionParser;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
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
	public void testFedAllKeepsNativeFederatedChain() throws Exception {
		Map<String, String> args = new HashMap<>();
		args.put("$X1", "localhost:1234/tmp/fedall/X1");
		args.put("$X2", "localhost:1235/tmp/fedall/X2");
		args.put("$r", String.valueOf(ROWS));
		args.put("$c", String.valueOf(COLS));
		args.put("$W", "tmp/fedall/W");

		DMLProgram prog = parseAndRewrite(FEDALL_SCRIPT, args, "compile_fed_all_max_fed_fout_single_pass");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(prog, Privacy.PUBLIC);
		PlannerInvocationReceipt invocation = invokePlannerOnRewrittenProgram(prog, "compile_fed_all_max_fed_fout_single_pass");
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
		assertTrue("The test must exercise the FedAll planner rather than inspect pre-planner HOPs",
			invocation instanceof org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass.FedAllInvocationReceipt);
		assertEquals("FedAll should preserve the native FED/FOUT plus instead of inserting CP->FOUT",
			ExecType.FED, zHop.getForcedExecType());
		assertEquals(FederatedOutput.FOUT, zHop.getFederatedOutput());
		assertEquals("FedAll should keep the downstream multiply federated",
			ExecType.FED, wHop.getForcedExecType());
		assertEquals(FederatedOutput.FOUT, wHop.getFederatedOutput());
	}

	@Test
	public void testPrivateAggregateDecisionRejectsCoordinatorMaterializationDespiteConcreteFType() {
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
		assertFalse("origin-bound data cannot be collected for CP execution", decision.allowCP_LOUT);
		assertFalse("a concrete upload anchor does not authorize a protected coordinator payload",
			decision.allowCP_FOUT);
	}

	@Test
	public void testPrivateAggregateDecisionRejectsCoordinatorMaterializationWithoutFType() {
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
		assertFalse("origin-bound data cannot be collected for CP execution", decision.allowCP_LOUT);
		assertFalse("PRIVATE_AGGREGATE should not expose CP/FOUT without a concrete materializable FType hint",
			decision.allowCP_FOUT);
	}

	@Test
	public void testCostModelPartitionedDownloadUsesParallelPayloadCriticalPath() {
		double logicalMatrixBytes = 8.0 * 1024 * 1024 * 1024;
		int workers = 4;

		double serializedFullDownload = FederatedCostModel.computeDownloadNetworkCost(logicalMatrixBytes);
		double partitionedDownload = FederatedCostModel.computeDownloadNetworkCost(
			logicalMatrixBytes, FType.ROW, workers);
		double singlePartitionPayload = FederatedCostModel.computeDownloadNetworkCost(
			logicalMatrixBytes / workers);

		assertTrue("ROW/PART materialization should not serialize the full logical matrix payload"
				+ " through one worker link; it should use the parallel worker-partition critical path",
			partitionedDownload < serializedFullDownload);
		assertTrue("ROW/PART materialization still pays at least the largest-partition payload term",
			partitionedDownload >= singlePartitionPayload);
	}

	@Test
	public void testPlannerRecompileStateSurvivesFedInitClearUntilPlannerReset() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		DataOp read = transientRead("XplannerStateLifetime", ROWS, COLS);
		read.setBeginLine(2019);
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
	public void testPlannerRecompileStateSkipsSyntheticSourcePosition() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		DataOp synthetic = transientRead("XsyntheticPlannerState", ROWS, COLS);
		FederatedPlannerUtils.registerPlannerRecompileState(
			synthetic, ExecType.FED, FederatedOutput.FOUT);

		assertTrue("Synthetic 0:0 source anchors must not become global recompile"
			+ " signatures because runtime rewrites can create unrelated hops with"
			+ " the same class/op and synthetic position",
			FederatedPlannerUtils.getPlannerRecompileState(synthetic) == null);
		FederatedPlannerUtils.clearPlannerRecompileStates();
	}

	@Test
	public void testDpPlansSteplmWithSameNamedFormalTransientBinding() throws Exception {
		String script = String.join("\n",
			"X_LOCAL = matrix(1, rows=20, cols=5);",
			"Y_LOCAL = matrix(1, rows=20, cols=1);",
			"X = federated(local_matrix=X_LOCAL, addresses=list(\"localhost:8001\"),",
			"              ranges=list(list(0, 0), list(20, 5)));",
			"Y = federated(local_matrix=Y_LOCAL, addresses=list(\"localhost:8001\"),",
			"              ranges=list(list(0, 0), list(20, 1)));",
			"[B, S] = steplm(X=X, y=Y, icpt=0, reg=1e-7, tol=1e-7, maxi=20, verbose=FALSE);",
			"write(B, \"tmp/steplm-dp-repeated-forward.res\", format=\"csv\");",
			"");

		ExactPlacementInput receipt = invokeLocalCostPlannerRewriteScript(script);
		assertNotNull("Production local-cost DP must plan StepLM when a function formal shadows"
			+ " a same-named caller transient", receipt);
		var functionInputs = receipt.analysis().logicalFunctionInputsInCanonicalOrder();
		assertFalse("StepLM must expose shared actual-to-formal input facts", functionInputs.isEmpty());
		for(int i = 0; i < functionInputs.size(); i++)
			for(int j = i + 1; j < functionInputs.size(); j++)
				assertFalse("Shared function-formal ownership must be unique after same-name binding",
					functionInputs.get(i).sourceArgument() == functionInputs.get(j).sourceArgument()
						&& functionInputs.get(i).targetRead() == functionInputs.get(j).targetRead()
						&& functionInputs.get(i).logicalPosition() == functionInputs.get(j).logicalPosition());
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
	public void testPlannerAdvertisesNativeFederatedWdivmmForSingleRangeFullX() {
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

		assertEquals("A single-range FULL FederationMap follows the runtime's explicit row branch",
			ExecType.FED, caps.exec());
		assertEquals("FULL-X WDIVMM retains its single-range remote result", FederatedOutput.FOUT,
			caps.placement());
		assertTrue("FULL-X WDIVMM must retain CP->FOUT as a cost competitor", decision.allowCP_FOUT);
		assertTrue("FULL-X WDIVMM must advertise the runtime-backed FED/FOUT candidate",
			decision.allowFED_FOUT);
		assertTrue("Forced-local FULL-X WDIVMM is also executable", decision.allowFED_LOUT);
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
		assertEquals("Local aggregation is a supported runtime branch, not a candidate-space ban",
			ReasonCode.OK, caps.reason());
		assertTrue("WDivMM local aggregation must retain a local CP competitor", decision.allowCP_LOUT);
		assertTrue("WDivMM local aggregation must retain the legal FED/LOUT candidate for cost comparison",
			decision.allowFED_LOUT);
		assertFalse("WDivMM local aggregation has no native FED/FOUT result", decision.allowFED_FOUT);
	}

	@Test
	public void testPartitionedBinaryFedComputeScalesButBroadcastOnlyDoesNot() {
		DataOp partitioned = federatedRead("XpartitionedBinaryCost", ROWS, COLS);
		DataOp local = transientRead("YpartitionedBinaryCost", ROWS, COLS);
		BinaryOp plus = new BinaryOp("partitionedBinaryCost", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, partitioned, local);

		assertEquals("Partition-preserving binary compute executes independently on worker shards",
			25.0, FederatedCostModel.computeFederatedComputeCost(plus, 100.0, 4, false), 0.0);
		assertEquals("A FED op whose matrix inputs are all replicated must not receive worker scaling",
			100.0, FederatedCostModel.computeFederatedComputeCost(plus, 100.0, 4, true), 0.0);
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
		double oneWorkerLocalAggCost = FederatedCostModel.computeWdivmmLocalAggregationCost(
			leftWdivmm, FType.ROW, resultMem, 1);
			double localAggCost = FederatedCostModel.computeWdivmmLocalAggregationCost(
				leftWdivmm, FType.ROW, resultMem, 4);
			assertTrue("Local aggregation must charge one full partial result per worker plus coordinator aggregation",
				localAggCost > oneWorkerLocalAggCost);
			FederatedCostModel.MixedFedLocalCost tinyLocalAggCost =
				FederatedCostModel.computeMixedFedLocalCost(leftWdivmm, leftWdivmm.getInput(),
					Arrays.asList(FType.ROW, FType.ROW, FType.FULL, null), FType.ROW,
					100.0, OptimizerUtils.DOUBLE_SIZE, 4);
			assertTrue("Local aggregation must charge coordinator-side partial-result aggregation",
				tinyLocalAggCost.getCoordinatorLocalCost() > 0.0);
			assertEquals("ROW-X WDIVMM worker compute remains partitioned; partial-result aggregation is costed separately",
				25.0, FederatedCostModel.adjustFederatedComputeCostForWdivmmLocalAggregation(
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
			FederatedCostModel.MixedFedLocalCost nativeFedWdivmmCost =
				FederatedCostModel.computeMixedFedLocalCost(rightWdivmm, rightWdivmm.getInput(),
					Arrays.asList(FType.ROW, FType.FULL, FType.FULL, null), FType.ROW,
					100.0, resultMem, 4);
			assertEquals("Native FED WDIVMM still pays runtime input preparation",
				"wdivmm-input-preparation", nativeFedWdivmmCost.getLabel());
			assertEquals("Native FED WDIVMM creates a new federation mapping; later placement boundaries own"
					+ " any real result materialization or refederation",
				rowInputPrepCost,
				nativeFedWdivmmCost.getInputPreparationCost(), 1e-9);
			assertEquals("Native FED WDIVMM worker compute is partitioned and needs no unscaled self-cost floor",
				0.0, nativeFedWdivmmCost.getFederatedComputeFloor(), 0.0);
			assertEquals("Native FED WDIVMM does not use the local-aggregation partial GET path",
				0.0, nativeFedWdivmmCost.getCoordinatorPhaseCost(), 0.0);
		assertEquals("FULL-X WDIVMM is not a native FED input-preparation path",
			0.0, FederatedCostModel.computeWdivmmInputPreparationCost(leftWdivmm,
				leftWdivmm.getInput(), Arrays.asList(FType.FULL, FType.ROW, FType.FULL, null), 4), 0.0);
	}

	@Test
	public void testWdivmmCostFloorScalesWithFactorRank() {
		DataOp x = federatedRead("XwdivmmRankPlan", ROWS, COLS);
		QuaternaryOp rankTwoWdivmm = new QuaternaryOp("wdivmmRankTwo", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, transientRead("UwdivmmRankTwo", ROWS, 2),
			transientRead("VwdivmmRankTwo", COLS, 2), new LiteralOp(-1), 1, false, false);
		QuaternaryOp rankEightWdivmm = new QuaternaryOp("wdivmmRankEight", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, transientRead("UwdivmmRankEight", ROWS, 8),
			transientRead("VwdivmmRankEight", COLS, 8), new LiteralOp(-1), 1, false, false);

		double rankTwoCost = FederatedCostModel.computeOpCostWithFallback(rankTwoWdivmm);
		double rankEightCost = FederatedCostModel.computeOpCostWithFallback(rankEightWdivmm);
		assertTrue("WDivMM cost must include the rank-width U/V factor interaction instead of a "
			+ "constant per-cell floor", rankEightCost > rankTwoCost * 2.0);

		FederatedCostModel.MixedFedLocalCost rankEightLocalAggCost =
			FederatedCostModel.computeMixedFedLocalCost(rankEightWdivmm, rankEightWdivmm.getInput(),
				Arrays.asList(FType.ROW, FType.ROW, FType.FULL, null), FType.ROW,
				rankEightCost, ROWS * 8 * (double) OptimizerUtils.DOUBLE_SIZE, 4);
		assertEquals("WDivMM local aggregation must not replace partitioned worker compute with an unscaled floor",
			0.0, rankEightLocalAggCost.getFederatedComputeFloor(), 0.0);
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
		double oneWorkerLocalAggCost = FederatedCostModel.computeAggBinaryAddAggregationCost(ba,
			Arrays.asList(FType.BROADCAST, FType.ROW), resultMem, 1);
			double localAggCost = FederatedCostModel.computeAggBinaryAddAggregationCost(ba,
				Arrays.asList(FType.BROADCAST, FType.ROW), resultMem, 4);
			assertTrue("AggBinary aggAdd must charge one full partial result per worker plus coordinator aggregation",
				localAggCost > oneWorkerLocalAggCost);
			FederatedCostModel.MixedFedLocalCost aggAddStageCost =
				FederatedCostModel.computeMixedFedLocalCost(ba, Arrays.asList(left, right),
					Arrays.asList(FType.BROADCAST, FType.ROW), FType.ROW,
					100.0, OptimizerUtils.DOUBLE_SIZE, 4);
			assertTrue("AggBinary aggAdd must charge coordinator-side partial-result aggregation",
				aggAddStageCost.getCoordinatorLocalCost() > 0.0);
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
	public void testAggBinaryFullLeftUsesRuntimeBindInsteadOfReplicatedPartialAdd() {
		DataOp left = federatedRead("XaggBinaryFullBindPlan", ROWS, COLS);
		DataOp right = transientRead("YaggBinaryFullBindPlan", COLS, 2);
		AggBinaryOp ba = new AggBinaryOp("ba", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);

		assertFalse("Runtime treats FULL as row-capable and routes FULL-left matrix multiply through"
			+ " the left-row bind path, not replicated-partial aggAdd",
			FederatedCostModel.requiresFederatedAggBinaryAddAggregation(ba,
				Arrays.asList(FType.FULL, FType.ROW)));
		assertEquals("FULL-left bind must not receive replicated-partial coordinator aggregation cost",
			0.0, FederatedCostModel.computeAggBinaryAddAggregationCost(ba,
				Arrays.asList(FType.FULL, FType.ROW), ROWS * 2.0 * OptimizerUtils.DOUBLE_SIZE, 1), 0.0);
	}

	@Test
	public void testAggBinaryLocalResultReusesFederatedInstructionRoundTrip() throws Exception {
		DataOp left = federatedRead("XaggBinarySingleControlPlan", ROWS, COLS);
		DataOp right = transientRead("YaggBinarySingleControlPlan", COLS, 2);
		AggBinaryOp ba = new AggBinaryOp("ba", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);
		double resultMem = ROWS * 2.0 * OptimizerUtils.DOUBLE_SIZE;

		FederatedCostModel.MixedFedLocalCost runtimeStages =
			FederatedCostModel.computeMixedFedLocalCost(ba, Arrays.asList(left, right),
				Arrays.asList(FType.BROADCAST, FType.ROW), FType.ROW,
				100.0, resultMem, 1);
		assertTrue("AggregateBinary local-result retrieval must retain the result payload cost",
			runtimeStages.getPartialResultDownloadCost() > 0.0);
		double sharedInBandResultCost =
			FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
				ba, FType.ROW, resultMem, 1, Double.POSITIVE_INFINITY);
		assertEquals("AggregateBinary local aggregation and ordinary FED/LOUT bind share the"
			+ " same one-worker in-band payload contract without a second RTT", sharedInBandResultCost,
			runtimeStages.getPartialResultDownloadCost(), 0.0);
	}

	@Test
	public void testAggBinaryColLeftFederatedRightSkipsRepeatedSlicedBroadcastPreparation() {
		DataOp left = federatedRead("XaggBinaryColLeftPlan", ROWS, COLS);
		DataOp right = transientRead("YaggBinaryFederatedRightPlan", COLS, 2);
		AggBinaryOp ba = new AggBinaryOp("ba", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);

		assertEquals("COL-left x FULL-right already has a remote full representation, so the cost model"
			+ " must not charge the same sliced upload on every repeated execution",
			0.0, FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
				Arrays.asList(left, right), Arrays.asList(FType.COL, FType.FULL), 4), 0.0);
		assertEquals("COL-left x BROADCAST-right already has a remote replicated representation",
			0.0, FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
				Arrays.asList(left, right), Arrays.asList(FType.COL, FType.BROADCAST), 4), 0.0);
		assertTrue("COL-left x local-right still requires sliced input preparation",
			FederatedCostModel.computeAggBinarySlicedInputBroadcastCost(ba,
				Arrays.asList(left, right), Arrays.asList(FType.COL, null), 4) > 0.0);
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
	public void testExecPlacementPolicyUsesPerOutputMultiReturnCapabilitiesAndPrivacy() {
		DataOp frame = new DataOp("Fall", DataType.FRAME, ValueType.STRING,
			OpOpData.TRANSIENTREAD, null, 128, 64, -1, BLOCKSIZE);
		LiteralOp spec = new LiteralOp("{ids:true,dummycode:[1]}");
		DataOp encoded = new DataOp("X0", DataType.MATRIX, ValueType.FP64,
			frame, OpOpData.FUNCTIONOUTPUT, "X0");
		DataOp metadata = new DataOp("M", DataType.FRAME, ValueType.STRING,
			frame, OpOpData.FUNCTIONOUTPUT, "M");
		FunctionOp transform = new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, "_internal", "transformencode",
			null, List.of(frame, spec), new String[] {"X0", "M"},
			new ArrayList<>(List.of(encoded, metadata)));

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		OpCaps callCaps = oracle.decide(transform, Arrays.asList(FType.ROW, null));
		OpCaps encodedCaps = oracle.decide(encoded, List.of(FType.ROW));
		OpCaps metadataCaps = oracle.decide(metadata, List.of(FType.ROW));

		ExecPlacementPolicy.Decision privateCall = ExecPlacementPolicy.decide(
			transform, Privacy.PRIVATE, FType.ROW, callCaps);
		assertFalse("strict privacy must exclude coordinator transformencode", privateCall.allowCP_LOUT);
		assertTrue("runtime-native federated transformencode must remain available", privateCall.allowFED_FOUT);

		ExecPlacementPolicy.Decision aggregateCall = ExecPlacementPolicy.decide(
			transform, Privacy.PRIVATE_AGGREGATE, FType.ROW, callCaps);
		assertFalse("aggregate-only inputs cannot be collected for CP transformencode",
			aggregateCall.allowCP_LOUT);
		assertTrue(aggregateCall.allowFED_FOUT);
		assertFalse("multi-return lowering cannot express CP transform followed by FOUT",
			aggregateCall.allowCP_FOUT);

		ExecPlacementPolicy.Decision privateEncoded = ExecPlacementPolicy.decide(
			encoded, Privacy.PRIVATE, FType.ROW, encodedCaps);
		assertFalse(privateEncoded.allowCP_LOUT);
		assertTrue(privateEncoded.allowFED_FOUT);

		ExecPlacementPolicy.Decision aggregateEncoded = ExecPlacementPolicy.decide(
			encoded, Privacy.PRIVATE_AGGREGATE, FType.ROW, encodedCaps);
		assertFalse("encoded row-level data retains origin residency", aggregateEncoded.allowCP_LOUT);
		assertTrue(aggregateEncoded.allowFED_FOUT);
		assertFalse(aggregateEncoded.allowCP_FOUT);

		ExecPlacementPolicy.Decision aggregateMetadata = ExecPlacementPolicy.decide(
			metadata, Privacy.PRIVATE_AGGREGATE, null, metadataCaps);
		assertFalse("metadata needs output-specific aggregation proof before local release",
			aggregateMetadata.allowCP_LOUT);
		assertFalse(aggregateMetadata.allowFED_FOUT);
		ExecPlacementPolicy.Decision privateMetadata = ExecPlacementPolicy.decide(
			metadata, Privacy.PRIVATE, null, metadataCaps);
		assertFalse("local transform metadata makes strict-private transformencode infeasible",
			privateMetadata.hasAny());

		DataOp input = transientRead("X", 128, 64);
		DataOp eigenValues = new DataOp("D", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, "D");
		DataOp eigenVectors = new DataOp("V", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, "V");
		FunctionOp eigen = new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, "_internal", "eigen",
			new String[] {"X"}, List.of(input), new String[] {"D", "V"},
			new ArrayList<>(List.of(eigenValues, eigenVectors)));
		OpCaps eigenCaps = oracle.decide(eigen, List.of(FType.ROW));
		assertFalse("unsupported multi-return builtins may not collect strict-private data",
			ExecPlacementPolicy.decide(eigen, Privacy.PRIVATE, FType.ROW, eigenCaps).hasAny());
		ExecPlacementPolicy.Decision publicEigen = ExecPlacementPolicy.decide(
			eigen, Privacy.PUBLIC, FType.ROW, eigenCaps);
		assertTrue(publicEigen.allowCP_LOUT);
		assertFalse(publicEigen.allowCP_FOUT);
	}

	@Test
	public void testLocalMaterializeRegistryDefaultSnapshotMergesWithStatementBlockEntries() {
		FederatedLocalMaterializeRegistry.clear();
		try {
			FederatedLocalMaterializeRegistry.register(-1L, 101L,
				List.of(201L), "ROW", "default-local-materialize");
			FederatedLocalMaterializeRegistry.register(7L, 102L,
				List.of(202L), "COL", "statement-local-materialize");
			FederatedLocalMaterializeRegistry.register(7L, 101L,
				List.of(203L), "FULL", "statement-override");

			Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> snapshot =
				FederatedLocalMaterializeRegistry.snapshot(7L);

			assertEquals("Statement-specific entries should override default entries for the same producer",
				List.of(203L), snapshot.get(101L).getConsumerHopIds());
			assertEquals("FULL", snapshot.get(101L).getFTypeHint());
			assertEquals(List.of(202L), snapshot.get(102L).getConsumerHopIds());
			assertEquals("Default entries should remain visible to other statement blocks",
				List.of(201L), FederatedLocalMaterializeRegistry.snapshot(8L).get(101L).getConsumerHopIds());
		}
		finally {
			FederatedLocalMaterializeRegistry.clear();
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
	public void testDagFoutMaterializeUsesDurableKeyInsteadOfUnplannedCycleFallback() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			DataOp localInput = transientRead("CycleLocal", ROWS, COLS);
			localInput.setForcedExecType(ExecType.CP);
			localInput.setFederatedOutput(FederatedOutput.LOUT);

			DataOp fedSeed = federatedRead("SelectedPoolSeed", ROWS, COLS);
			fedSeed.setForcedExecType(ExecType.FED);
			fedSeed.setFederatedOutput(FederatedOutput.FOUT);

			BinaryOp cyclicAnchor = HopRewriteUtils.createBinary(localInput, fedSeed, OpOp2.PLUS);
			cyclicAnchor.setDim1(ROWS);
			cyclicAnchor.setDim2(COLS);
			cyclicAnchor.setForcedExecType(ExecType.FED);
			cyclicAnchor.setFederatedOutput(FederatedOutput.FOUT);

			DataOp unrelatedLeft = federatedRead("UnrelatedPoolLeft", ROWS, COLS);
			DataOp unrelatedRight = federatedRead("UnrelatedPoolRight", ROWS, COLS);
			BinaryOp unrelatedAnchor = HopRewriteUtils.createBinary(unrelatedLeft, unrelatedRight, OpOp2.PLUS);
			unrelatedAnchor.setDim1(ROWS);
			unrelatedAnchor.setDim2(COLS);
			unrelatedAnchor.setForcedExecType(ExecType.FED);
			unrelatedAnchor.setFederatedOutput(FederatedOutput.FOUT);

			BinaryOp root = HopRewriteUtils.createBinary(cyclicAnchor, unrelatedAnchor, OpOp2.PLUS);
			root.setDim1(ROWS);
			root.setDim2(COLS);
			root.setForcedExecType(ExecType.FED);
			root.setFederatedOutput(FederatedOutput.FOUT);

			FederatedFoutMaterializeRegistry.register(-1L, localInput.getHopID(), cyclicAnchor.getHopID(),
				"ROW", "SelectedPoolSeed", anchorKey);

			Lop rootLop = root.constructLops();
			String unrelatedLabel = unrelatedAnchor.getLops().getOutputParameters().getLabel();
			Dag<Lop> dag = new Dag<>();
			rootLop.addToDag(dag);
			String foutInstruction = dag.getJobs(null, ConfigurationManager.getDMLConfig()).stream()
				.map(Instruction::getInstructionString)
				.filter(inst -> inst.contains("fed_fout"))
				.findFirst()
				.orElse(null);

			assertNotNull("Expected the planned CP->FOUT materialization", foutInstruction);
			assertTrue("A cyclic live anchor must fall back only to its own durable placement key: "
				+ foutInstruction, foutInstruction.contains(anchorKey));
			assertFalse("DAG lowering must not substitute an unrelated runtime anchor: " + foutInstruction,
				foutInstruction.contains(Lop.OPERAND_DELIMITOR + unrelatedLabel));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagDoesNotInsertUnplannedRefedWhenRegistryIsEmpty() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp localInput = transientRead("UnplannedLocal", ROWS, COLS);
			localInput.setForcedExecType(ExecType.CP);
			localInput.setFederatedOutput(FederatedOutput.LOUT);

			DataOp federatedInput = transientRead("ExistingFederated", ROWS, COLS);
			federatedInput.setForcedExecType(ExecType.FED);
			federatedInput.setFederatedOutput(FederatedOutput.FOUT);

			BinaryOp fedParent = HopRewriteUtils.createBinary(localInput, federatedInput, OpOp2.PLUS);
			fedParent.setDim1(ROWS);
			fedParent.setDim2(COLS);
			fedParent.setForcedExecType(ExecType.FED);
			fedParent.setFederatedOutput(FederatedOutput.FOUT);

			Lop parentLop = fedParent.constructLops();
			Dag<Lop> dag = new Dag<>();
			parentLop.addToDag(dag);
			List<String> instructions = dag.getJobs(null, ConfigurationManager.getDMLConfig()).stream()
				.map(Instruction::getInstructionString)
				.collect(java.util.stream.Collectors.toList());

			assertFalse("DAG lowering must not invent an unplanned fed_refed relocation: " + instructions,
				instructions.stream().anyMatch(inst -> inst.contains("fed_refed")));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedFailsClosedWhenNoSelectedConsumerIsRecorded() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP cpConsumerAndInvalidAnchor = functionCallConsumerLop(localInput, 962);
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			try {
				FederatedRefedRegistry.register(-1L, localInput.getHopID(),
					cpConsumerAndInvalidAnchor.getHopID(), anchorKey, List.of());
				throw new AssertionError("Expected empty exact consumer registration to fail closed");
			}
			catch (IllegalArgumentException ex) {
				assertTrue("Expected exact-consumer validation failure: " + ex.getMessage(),
					ex.getMessage().contains("exact selected consumer"));
			}
			assertTrue("Rejected empty-consumer registration must not mutate the registry",
				FederatedRefedRegistry.snapshot(-1L).isEmpty());
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedPrevalidationPreservesFullGraphIdentityOnMultiplicityFailure() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP firstConsumer = functionCallConsumerLop(localInput, 961);
			FunctionCallCP invalidSecondConsumer = functionCallConsumerLop(localInput, 962);
			localInput.removeOutput(invalidSecondConsumer);
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), -1L, anchorKey,
				List.of(firstConsumer.getHopID(), invalidSecondConsumer.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, firstConsumer, invalidSecondConsumer));
			List<Lop> originalLops = new ArrayList<>(lops);
			List<List<Lop>> originalInputs = lops.stream()
				.<List<Lop>>map(lop -> new ArrayList<>(lop.getInputs())).toList();
			List<List<Lop>> originalOutputs = lops.stream()
				.<List<Lop>>map(lop -> new ArrayList<>(lop.getOutputs())).toList();

			try {
				invokeInsertRefedLops(lops);
				throw new AssertionError("Expected selected-consumer multiplicity mismatch to fail closed");
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				assertTrue("Expected LopsException for edge multiplicity mismatch but got " + ex.getCause(),
					ex.getCause() instanceof org.apache.sysds.lops.LopsException);
			}
			assertEquals("Fail-closed lowering must preserve exact lop-list identity/order", originalLops, lops);
			for (int i = 0; i < lops.size(); i++) {
				assertEquals("Fail-closed lowering must preserve input edges for lop index " + i,
					originalInputs.get(i), lops.get(i).getInputs());
				assertEquals("Fail-closed lowering must preserve output edges for lop index " + i,
					originalOutputs.get(i), lops.get(i).getOutputs());
			}
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedFailsClosedWhenLiveAndDurableAnchorAuthoritiesDisagree() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedConsumer = functionCallConsumerLop(localInput, 962);
			Data liveAnchor = federatedMatrixAnchorLop("LiveAnchor", 916);
			FederatedPlannerUtils.registerFedAnchorKey(liveAnchor.getOutputParameters().getLabel(),
				"fedinit://live-workers|ROW");
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), liveAnchor.getHopID(),
				"fedinit://stale-workers|ROW", List.of(selectedConsumer.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedConsumer, liveAnchor));
			List<Lop> originalLops = new ArrayList<>(lops);
			List<Lop> originalLocalOutputs = new ArrayList<>(localInput.getOutputs());
			List<Lop> originalConsumerInputs = new ArrayList<>(selectedConsumer.getInputs());
			try {
				invokeInsertRefedLops(lops);
				throw new AssertionError("Expected conflicting live/durable anchor authority to fail closed");
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				assertTrue("Expected LopsException for conflicting anchor authority but got " + ex.getCause(),
					ex.getCause() instanceof org.apache.sysds.lops.LopsException);
			}
			assertEquals("Authority conflict must preserve lop list", originalLops, lops);
			assertEquals("Authority conflict must preserve producer outputs", originalLocalOutputs,
				localInput.getOutputs());
			assertEquals("Authority conflict must preserve consumer inputs", originalConsumerInputs,
				selectedConsumer.getInputs());
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedAcceptsEquivalentPermutedAnchorPartitions() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedConsumer = functionCallConsumerLop(localInput, 962);
			Data liveAnchor = federatedMatrixAnchorLop("LiveAnchor", 916);
			String liveKey = "localhost:10001;localhost:10002;|0,10;10,20;|ROW";
			String durableKey = "localhost:10002;localhost:10001;|10,20;0,10;|ROW";
			FederatedPlannerUtils.registerFedAnchorKey(
				liveAnchor.getOutputParameters().getLabel(), liveKey);
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), liveAnchor.getHopID(),
				durableKey, List.of(selectedConsumer.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedConsumer, liveAnchor));
			boolean changed = invokeInsertRefedLops(lops);

			assertTrue("Partition order must not create a false live/durable authority conflict", changed);
			FederatedRefed refed = lops.stream().filter(lop -> lop instanceof FederatedRefed)
				.map(lop -> (FederatedRefed) lop).findFirst().orElseThrow();
			assertEquals("Equivalent live authority must preserve key-backed deterministic lowering",
				1, refed.getInputs().size());
			assertTrue(refed.getInstructions("LocalLabels", "RefedOut").contains(durableKey));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedFailsClosedForUnresolvedSelectedConsumerId() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP cpConsumerAndInvalidAnchor = functionCallConsumerLop(localInput, 962);
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), cpConsumerAndInvalidAnchor.getHopID(),
				anchorKey, List.of(1234567L));

			List<Lop> lops = new ArrayList<>(List.of(localInput, cpConsumerAndInvalidAnchor));
			try {
				invokeInsertRefedLops(lops);
				throw new AssertionError("Expected unresolved selected consumer ID to fail closed");
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				assertTrue("Expected LopsException for unresolved selected consumer but got " + ex.getCause(),
					ex.getCause() instanceof org.apache.sysds.lops.LopsException);
			}
			assertFalse("Fail-closed unresolved consumer path must not leave orphan fed_refed",
				lops.stream().anyMatch(lop -> lop instanceof FederatedRefed));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedFailsClosedForAmbiguousSelectedConsumerId() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedA = functionCallConsumerLop(localInput, 962);
			FunctionCallCP selectedB = functionCallConsumerLop(localInput, 962);
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), selectedA.getHopID(),
				anchorKey, List.of(selectedA.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedA, selectedB));
			try {
				invokeInsertRefedLops(lops);
				throw new AssertionError("Expected ambiguous selected consumer ID to fail closed");
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				assertTrue("Expected LopsException for ambiguous selected consumer but got " + ex.getCause(),
					ex.getCause() instanceof org.apache.sysds.lops.LopsException);
			}
			assertFalse("Fail-closed ambiguous consumer path must not leave orphan fed_refed",
				lops.stream().anyMatch(lop -> lop instanceof FederatedRefed));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedFailsClosedForInvalidAnchorAndNonConcreteVarKey() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedCpConsumerAndInvalidAnchor = functionCallConsumerLop(localInput, 962);
			FederatedRefedRegistry.register(-1L, localInput.getHopID(),
				selectedCpConsumerAndInvalidAnchor.getHopID(), "VAR:MaybeRemoved|ROW",
				List.of(selectedCpConsumerAndInvalidAnchor.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedCpConsumerAndInvalidAnchor));
			try {
				invokeInsertRefedLops(lops);
				throw new AssertionError("Expected invalid anchor plus non-concrete VAR key to fail closed");
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				assertTrue("Expected LopsException for invalid refed anchor authority but got " + ex.getCause(),
					ex.getCause() instanceof org.apache.sysds.lops.LopsException);
			}
			assertFalse("Fail-closed invalid-anchor path must not leave orphan fed_refed",
				lops.stream().anyMatch(lop -> lop instanceof FederatedRefed));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedRewiresSelectedCpFunctionConsumerWithDurableKeyAnchor() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedCpConsumerAndInvalidAnchor = functionCallConsumerLop(localInput, 962);
			FunctionCallCP unselectedCpConsumer = functionCallConsumerLop(localInput, 963);
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, localInput.getHopID(),
				selectedCpConsumerAndInvalidAnchor.getHopID(), anchorKey,
				List.of(selectedCpConsumerAndInvalidAnchor.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedCpConsumerAndInvalidAnchor, unselectedCpConsumer));
			boolean changed = invokeInsertRefedLops(lops);

			assertTrue("Expected selected CP FunctionCallCP consumer to be rewired through fed_refed", changed);
			List<FederatedRefed> refeds = lops.stream()
				.filter(lop -> lop instanceof FederatedRefed)
				.map(lop -> (FederatedRefed) lop)
				.toList();
			assertEquals("Exactly one planned fed_refed should be inserted", 1, refeds.size());
			FederatedRefed refed = refeds.get(0);
			assertTrue("Selected CP FunctionCallCP must consume the planned fed_refed",
				selectedCpConsumerAndInvalidAnchor.getInputs().contains(refed));
			assertFalse("Selected CP FunctionCallCP must no longer consume the local input directly",
				selectedCpConsumerAndInvalidAnchor.getInputs().contains(localInput));
			assertTrue("Unselected CP FunctionCallCP must not be broadly rewired",
				unselectedCpConsumer.getInputs().contains(localInput));
			assertEquals("Key-backed fed_refed should have only the local input Lop edge", 1, refed.getInputs().size());
			String instruction = refed.getInstructions("LocalLabels", "RefedOut");
			assertTrue("Concrete durable anchor key should be emitted literally: " + instruction,
				instruction.contains(anchorKey));
			assertFalse("Invalid label-null FunctionCallCP anchor must not serialize as null.UNKNOWN: " + instruction,
				instruction.contains("null.UNKNOWN"));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedRematerializesFederatedSourceBeforeCrossAnchorUpload() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data federatedSource = federatedMatrixAnchorLop("FederatedSource", 947);
			FunctionCallCP selectedConsumer = functionCallConsumerLop(federatedSource, 962);
			String targetAnchorKey = "fedinit://target-workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, federatedSource.getHopID(), -1L,
				targetAnchorKey, List.of(selectedConsumer.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(federatedSource, selectedConsumer));
			boolean changed = invokeInsertRefedLops(lops);

			assertTrue("Expected selected FED/FOUT source to lower through an explicit relocation chain", changed);
			FederatedRefed refed = lops.stream()
				.filter(lop -> lop instanceof FederatedRefed)
				.map(lop -> (FederatedRefed) lop)
				.findFirst().orElseThrow();
			UnaryCP localMaterialize = lops.stream()
				.filter(lop -> lop instanceof UnaryCP)
				.map(lop -> (UnaryCP) lop)
				.filter(lop -> OpOp1.PREFETCH.toString().equals(lop.getOpCode()))
				.findFirst().orElseThrow();

			assertEquals("FED->LOUT leg must consume the selected federated source",
				List.of(federatedSource), localMaterialize.getInputs());
			assertEquals("LOUT->FOUT leg must consume only the explicit local materialization",
				List.of(localMaterialize), refed.getInputs());
			assertEquals("The intermediate value must be explicitly local",
				FederatedOutput.LOUT, localMaterialize.getFederatedOutput());
			assertEquals("The relocated value must be federated at the target anchor",
				FederatedOutput.FOUT, refed.getFederatedOutput());
			assertTrue("Selected consumer must consume the relocated value",
				selectedConsumer.getInputs().contains(refed));
			assertFalse("Selected consumer must not retain a stale direct source edge",
				selectedConsumer.getInputs().contains(federatedSource));
			assertTrue("Explicit relocation lops must precede their selected consumer",
				lops.indexOf(federatedSource) < lops.indexOf(localMaterialize)
					&& lops.indexOf(localMaterialize) < lops.indexOf(refed)
					&& lops.indexOf(refed) < lops.indexOf(selectedConsumer));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedRematerializesTransientReadOfFederatedWriteBeforeCrossAnchorUpload()
		throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data transientRead = localMatrixTransientReadLop("Y", 947);
			String sourceAnchorKey = "fedinit://source-workers/0,10;10,20|ROW";
			FederatedPlannerUtils.registerFedAnchorKey("Y", sourceAnchorKey);
			UnaryCP selectedConsumer = new UnaryCP(transientRead, OpOp1.ABS,
				DataType.MATRIX, ValueType.FP64, ExecType.FED);
			selectedConsumer.setHopID(962);
			selectedConsumer.getOutputParameters().setDimensions(ROWS, COLS, BLOCKSIZE, -1);
			selectedConsumer.setFederatedOutput(FederatedOutput.FOUT);

			// The same-name FED/FOUT write is downstream of the selected consumer, so it is not the
			// producer of this transient read. Lowering must use the registered runtime anchor instead
			// of rejecting the read solely because a later TWrite has the same variable name.
			Data laterFederatedWrite = new Data(OpOpData.TRANSIENTWRITE, selectedConsumer, null, "Y", null,
				DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
			laterFederatedWrite.setHopID(963);
			laterFederatedWrite.getOutputParameters().setDimensions(ROWS, COLS, BLOCKSIZE, -1);
			laterFederatedWrite.setExecType(ExecType.FED);
			laterFederatedWrite.setFederatedOutput(FederatedOutput.FOUT);

			String targetAnchorKey = "fedinit://target-workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, transientRead.getHopID(), -1L,
				targetAnchorKey, List.of(selectedConsumer.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(transientRead, selectedConsumer, laterFederatedWrite));
			boolean changed = invokeInsertRefedLops(lops);

			assertTrue("Expected selected transient FED/FOUT value to lower through explicit relocation", changed);
			FederatedRefed refed = lops.stream()
				.filter(lop -> lop instanceof FederatedRefed)
				.map(lop -> (FederatedRefed) lop)
				.findFirst().orElseThrow();
			UnaryCP localMaterialize = lops.stream()
				.filter(lop -> lop instanceof UnaryCP)
				.map(lop -> (UnaryCP) lop)
				.filter(lop -> OpOp1.PREFETCH.toString().equals(lop.getOpCode()))
				.findFirst().orElseThrow();

			assertEquals("FED transient symbol must be explicitly materialized before relocation",
				List.of(transientRead), localMaterialize.getInputs());
			assertEquals("fed_refed must consume only the explicit local materialization",
				List.of(localMaterialize), refed.getInputs());
			assertEquals(FederatedOutput.LOUT, localMaterialize.getFederatedOutput());
			assertEquals(FederatedOutput.FOUT, refed.getFederatedOutput());
			assertTrue(selectedConsumer.getInputs().contains(refed));
			assertFalse(selectedConsumer.getInputs().contains(transientRead));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}


	@Test
	public void testDagRegistryRefedFailsClosedForAmbiguousConcreteAnchorHopIdWithoutDurableKey() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedCpConsumer = functionCallConsumerLop(localInput, 962);
			Data anchorA = federatedMatrixAnchorLop("AnchorA", 916);
			Data anchorB = federatedMatrixAnchorLop("AnchorB", 916);
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), anchorA.getHopID(),
				"VAR:AmbiguousAnchor|ROW", List.of(selectedCpConsumer.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedCpConsumer, anchorA, anchorB));
			try {
				invokeInsertRefedLops(lops);
				throw new AssertionError("Expected duplicate concrete anchor hop ID without durable key to fail closed");
			}
			catch (java.lang.reflect.InvocationTargetException ex) {
				assertTrue("Expected LopsException for ambiguous concrete anchor but got " + ex.getCause(),
					ex.getCause() instanceof org.apache.sysds.lops.LopsException);
			}
			assertFalse("Fail-closed ambiguous-anchor path must not leave orphan fed_refed",
				lops.stream().anyMatch(lop -> lop instanceof FederatedRefed));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedUsesDurableKeyWhenConcreteAnchorHopIdIsAmbiguous() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedCpConsumer = functionCallConsumerLop(localInput, 962);
			Data anchorA = federatedMatrixAnchorLop("AnchorA", 916);
			Data anchorB = federatedMatrixAnchorLop("AnchorB", 916);
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), anchorA.getHopID(),
				anchorKey, List.of(selectedCpConsumer.getHopID()));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedCpConsumer, anchorA, anchorB));
			boolean changed = invokeInsertRefedLops(lops);

			assertTrue("Expected durable key to disambiguate duplicate concrete anchor lops", changed);
			FederatedRefed refed = lops.stream()
				.filter(lop -> lop instanceof FederatedRefed)
				.map(lop -> (FederatedRefed) lop)
				.findFirst().orElseThrow();
			assertEquals("Ambiguous live anchor should force key-backed fed_refed", 1, refed.getInputs().size());
			String instruction = refed.getInstructions("LocalLabels", "RefedOut");
			assertTrue("Concrete durable anchor key should be emitted when live anchor hop is ambiguous: " + instruction,
				instruction.contains(anchorKey));
			assertFalse("Ambiguous live anchor label must not be selected arbitrarily: " + instruction,
				instruction.contains("AnchorA") || instruction.contains("AnchorB"));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testDagRegistryRefedRewiresAllDuplicateSelectedConsumerEdgesConsistently() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedPlannerUtils.clearFedInitVars();
		try {
			Data localInput = localMatrixTransientReadLop("LocalLabels", 947);
			FunctionCallCP selectedCpConsumer = functionCallConsumerLop(List.of(localInput, localInput), 962);
			String anchorKey = "fedinit://workers/0,10;10,20|ROW";
			FederatedRefedRegistry.register(-1L, localInput.getHopID(), selectedCpConsumer.getHopID(),
				anchorKey, List.of(selectedCpConsumer.getHopID()));
			assertEquals("Regression setup requires two local input edges", 2,
				lopOccurrences(selectedCpConsumer.getInputs(), localInput));
			assertEquals("Regression setup requires two local output edges", 2,
				lopOccurrences(localInput.getOutputs(), selectedCpConsumer));

			List<Lop> lops = new ArrayList<>(List.of(localInput, selectedCpConsumer));
			boolean changed = invokeInsertRefedLops(lops);

			assertTrue("Expected duplicate selected consumer edges to be rewired", changed);
			FederatedRefed refed = lops.stream()
				.filter(lop -> lop instanceof FederatedRefed)
				.map(lop -> (FederatedRefed) lop)
				.findFirst().orElseThrow();
			assertEquals("Every duplicate input edge must be rewired to fed_refed", 2,
				lopOccurrences(selectedCpConsumer.getInputs(), refed));
			assertEquals("No stale local input edges may remain on selected consumer", 0,
				lopOccurrences(selectedCpConsumer.getInputs(), localInput));
			assertEquals("No stale local output edges may remain after duplicate rewiring", 0,
				lopOccurrences(localInput.getOutputs(), selectedCpConsumer));
			assertEquals("Refed output edge count must match rewired duplicate input edges", 2,
				lopOccurrences(refed.getOutputs(), selectedCpConsumer));
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	private static Data localMatrixTransientReadLop(String name, long hopId) {
		Data localInput = new Data(OpOpData.TRANSIENTREAD, null, null, name, null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		localInput.setHopID(hopId);
		localInput.getOutputParameters().setDimensions(ROWS, COLS, BLOCKSIZE, -1);
		localInput.setExecType(ExecType.CP);
		localInput.setFederatedOutput(FederatedOutput.LOUT);
		return localInput;
	}

	private static Data federatedMatrixAnchorLop(String name, long hopId) {
		Data anchor = new Data(OpOpData.FEDERATED, null, null, name, null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		anchor.setHopID(hopId);
		anchor.getOutputParameters().setDimensions(ROWS, COLS, BLOCKSIZE, -1);
		anchor.setExecType(ExecType.FED);
		anchor.setFederatedOutput(FederatedOutput.FOUT);
		return anchor;
	}

	private static FunctionCallCP functionCallConsumerLop(Lop input, long hopId) {
		return functionCallConsumerLop(List.of(input), hopId);
	}

	private static FunctionCallCP functionCallConsumerLop(List<Lop> inputs, long hopId) {
		String[] inputNames = new String[inputs.size()];
		for (int i = 0; i < inputNames.length; i++)
			inputNames[i] = "X" + i;
		FunctionCallCP fcall = new FunctionCallCP(new ArrayList<>(inputs),
			DMLProgram.INTERNAL_NAMESPACE, "mock_function", inputNames,
			new String[] {"Out"}, false, ExecType.CP);
		fcall.setHopID(hopId);
		assertTrue("Regression setup requires FunctionCallCP output label to be null",
			fcall.getOutputParameters().getLabel() == null);
		return fcall;
	}

	private static int lopOccurrences(List<Lop> values, Lop target) {
		int count = 0;
		for (Lop value : values)
			if (value == target)
				count++;
		return count;
	}

	private static boolean invokeInsertRefedLops(List<Lop> lops) throws Exception {
		Method insertRefed = Dag.class.getDeclaredMethod("insertRefedLops", List.class, StatementBlock.class);
		insertRefed.setAccessible(true);
		return (boolean) insertRefed.invoke(new Dag<>(), lops, null);
	}

	private static DMLProgram syntheticProgram(Hop... roots) {
		DMLProgram prog = new DMLProgram();
		StatementBlock sb = new StatementBlock();
		sb.setHops(new ArrayList<>(List.of(roots)));
		sb.setDMLProg(prog);
		prog.addStatementBlock(sb);
		return prog;
	}

	private static PlacementAnalysis bindSyntheticPlacementAnalysis(DMLProgram prog) {
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(prog);
	}

	private static ExactPlacementInput invokeLocalCostPlannerRewriteScript(String script) throws Exception {
		ParserWrapper parser = ParserFactory.createParser();
		DMLProgram prog = parser.parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator dmlt = new DMLTranslator(prog);
		dmlt.liveVariableAnalysis(prog);
		dmlt.validateParseTree(prog);
		dmlt.constructHops(prog);
		dmlt.rewriteHopsDAG(prog);
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig plannerConfig = new DMLConfig(oldConfig);
		plannerConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
		try {
			ConfigurationManager.setGlobalConfig(plannerConfig);
			ConfigurationManager.setLocalConfig(plannerConfig);
			new DMLTranslator(prog).constructLops(prog, value -> receipt.compareAndSet(null, value));
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
		assertTrue("Expected production local-cost planner receipt",
			receipt.get() instanceof ExactPlacementInput);
		return (ExactPlacementInput) receipt.get();
	}

	private static BinaryOp findBinaryOp(List<Hop> allHops, OpOp2 op) {
		for(Hop hop : allHops)
			if(hop instanceof BinaryOp && ((BinaryOp) hop).getOp() == op)
				return (BinaryOp) hop;
		return null;
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

	private static PlannerInvocationReceipt invokePlannerOnRewrittenProgram(DMLProgram prog, String planner) {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig newConfig = new DMLConfig(oldConfig);
		newConfig.setTextValue(DMLConfig.FEDERATED_PLANNER, planner);
		ConfigurationManager.setGlobalConfig(newConfig);
		ConfigurationManager.setLocalConfig(newConfig);
		AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
		try {
			new DMLTranslator(prog).constructLops(prog, value -> receipt.compareAndSet(null, value));
			return receipt.get();
		}
		finally {
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

	private static List<Hop> collectAllProgramHops(DMLProgram prog) {
		List<Hop> roots = collectRoots(prog);
		for(org.apache.sysds.parser.FunctionDictionary<org.apache.sysds.parser.FunctionStatementBlock> dict
				: prog.getNamespaces().values())
			for(org.apache.sysds.parser.FunctionStatementBlock fsb : dict.getFunctions().values())
				if(fsb.getHops() != null)
					roots.addAll(fsb.getHops());
		return collectAllHops(roots);
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
		return federatedRead(name, rows, cols, null);
	}

	private static DataOp federatedRead(String name, long rows, long cols, FType fType) {
		FederatedPlannerUtils.registerFedInitVar(name, fType);
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
		FederatedOutput expectedOutput = hop.getDataType() == DataType.SCALAR
			? FederatedOutput.LOUT : FederatedOutput.FOUT;
		hop.setFederatedOutput(expectedOutput);
		Lop lop = hop.constructLops();
		assertEquals("Expected direct FED lop lowering", ExecType.FED, lop.getExecType());
		assertEquals("Quaternary HOP output authority must reach its direct FED LOP",
			expectedOutput, lop.getFederatedOutput());
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
		assertEquals("Direct FED quaternary lowering must preserve planner-selected output authority",
			expectedOutput, ((QuaternaryFEDInstruction) parsed).getFederatedOutput());
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
			"registerCpfoutWithSelection", Hop.class, Map.class, long.class, anchorSelectionClass, List.class);
		method.setAccessible(true);
		// This helper exercises only the TWrite materialize path, which returns before
		// REFED exact-consumer validation; do not recreate the removed consumer-less API.
		method.invoke(null, hop, fTypeMap, sbId, selection, List.of());
	}
}
