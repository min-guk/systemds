/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class MemoCompilerIntegrationTest {
	private record HopState(ExecType forced, ExecType execution, FederatedOutput output,
		boolean derived, boolean prefetch, boolean selected, Lop lops) {
		private HopState(Hop hop) {
			this(hop.getForcedExecType(), hop.getExecType(), hop.getFederatedOutput(),
				hop.isFederatedOutputDerived(), hop.prefetchActivated(),
				hop.isPlannerPlacementSelected(), hop.getLops());
		}
	}

	private static final Path IPA = Path.of(
		"src/main/java/org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java");
	private static final Path TRANSLATOR = Path.of(
		"src/main/java/org/apache/sysds/parser/DMLTranslator.java");

	@Test
	public void memoReceiptDoesNotPretendToOwnLegacyAnalysis() {
		PlannerInvocationReceipt receipt = new PlannerInvocationReceipt() { };
		UnsupportedOperationException failure = Assert.assertThrows(
			UnsupportedOperationException.class, receipt::analysis);
		Assert.assertTrue(failure.getMessage().contains("does not use legacy placement analysis"));
	}

	@Test
	public void memoRoutePrecedesLegacyAnalysisAndInvokesTypedPlan() throws Exception {
		String source = Files.readString(IPA);
		int route = source.indexOf("planner == FederatedPlanner.COMPILE_MEMO");
		int legacyAnalysis = source.indexOf("new NeutralPlacementGraphBuilder()");
		Assert.assertTrue("memo route must exist", route >= 0);
		Assert.assertTrue("memo route must run before legacy analysis", route < legacyAnalysis);
		String memoMethod = source.substring(source.indexOf("private void generateMemoPlan"));
		Assert.assertTrue("memo route must call the typed memo planner", memoMethod.contains(".plan(prog)"));
		Assert.assertFalse("memo route must not construct legacy placement analysis",
			memoMethod.contains("NeutralPlacementGraphBuilder"));
		Assert.assertFalse("memo route must not observe the legacy placement shadow",
			memoMethod.contains("PlacementShadowCoordinator"));
	}

	@Test
	public void finalHopBoundaryRoutesMemoBeforeLegacySearchSpace() throws Exception {
		String source = Files.readString(TRANSLATOR);
		int route = source.indexOf("configuredPlanner == org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner.COMPILE_MEMO");
		int legacy = source.indexOf("prepareCommonSearchSpace(dmlp");
		Assert.assertTrue("final-Hop memo route must precede legacy search-space construction",
			route >= 0 && legacy >= 0 && route < legacy);
		String method = source.substring(source.indexOf("private static void runMemoPlannerAtFinalHopBoundary"),
			source.indexOf("private static AFederatedPlanner createFederatedPlanner"));
		Assert.assertTrue(method.contains(".plan(program)"));
		Assert.assertFalse(method.contains("PlacementAnalysis"));
		Assert.assertFalse(method.contains("NeutralPlacementGraphBuilder"));
	}

	@Test
	public void exactOwnerRecompileRequiresAndReusesCommittedMemoPlan() throws Exception {
		DMLConfig old = ConfigurationManager.getDMLConfig();
		DMLConfig memo = new DMLConfig();
		memo.setTextValue(DMLConfig.FEDERATED_PLANNER, "COMPILE_MEMO");
		try {
			ConfigurationManager.setLocalConfig(memo);
			DMLProgram program = parse("A=matrix(2,2,2);B=abs(A);print(sum(B));");
			new FederatedPlanMemo().plan(program);
			StatementBlock block = program.getStatementBlocks().get(0);
			Assert.assertFalse(Recompiler.recompileHopsDagInstructions(block, block.getHops()).isEmpty());
			MemoPlanRegistry.clear();
			Assert.assertThrows(MemoPlanRegistry.StaleMemoPlanException.class,
				() -> Recompiler.recompileHopsDagInstructions(block, block.getHops()));
		}
		finally {
			ConfigurationManager.setLocalConfig(old);
		}
	}

	@Test
	public void clearingAnEpochAlsoRevokesItsLoopContracts() throws Exception {
		DMLProgram program = parse("A=matrix(1,2,2);for(i in 1:3){A=A+1;}print(sum(A));");
		new FederatedPlanMemo().plan(program);
		Assert.assertTrue(MemoPlanRegistry.hasPlan(program));
		Assert.assertFalse(org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime
			.snapshotAll().isEmpty());
		MemoPlanRegistry.clear();
		Assert.assertFalse(MemoPlanRegistry.hasPlan(program));
		Assert.assertTrue(org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime
			.snapshotAll().isEmpty());
		Assert.assertTrue(org.apache.sysds.lops.compile.MemoSupplyRegistry.snapshotAll().isEmpty());
	}

	@Test
	public void occurrenceScopedShapesRejectAnUnplannedThirdShapeOnRecompile() throws Exception {
		DMLConfig old = ConfigurationManager.getDMLConfig();
		DMLConfig memo = new DMLConfig();
		memo.setTextValue(DMLConfig.FEDERATED_PLANNER, "COMPILE_MEMO");
		try {
			ConfigurationManager.setLocalConfig(memo);
			DMLProgram program = parse("p=as.scalar(rand(rows=1,cols=1));A=matrix(1,rows=8,cols=4);"
				+ "if(p>0.5){B=abs(A);}else{B=A;}"
				+ "A=matrix(1,rows=7,cols=4);for(j in 1:2){print(j);}"
				+ "C=exp(A);print(sum(B)+sum(C));");
			MemoProgramFacts initial = MemoProgramFacts.analyze(program);
			UnaryOp abs = unary(initial, OpOp1.ABS);
			UnaryOp exp = unary(initial, OpOp1.EXP);
			Hop sharedRead = abs.getInput().get(0);
			HopRewriteUtils.replaceChildReference(exp, exp.getInput().get(0), sharedRead);

			FederatedPlanMemo.Receipt receipt = new FederatedPlanMemo().plan(program);
			long expBlockId = receipt.facts().occurrences().stream().filter(value -> value.hop() == exp)
				.findFirst().orElseThrow().statementBlockId();
			StatementBlock expBlock = program.getStatementBlocks().stream()
				.filter(block -> block.getSBID() == expBlockId).findFirst().orElseThrow();
			sharedRead.setDim1(9);
			sharedRead.setDim2(4);
			MemoPlanRegistry.StaleMemoPlanException failure = Assert.assertThrows(
				MemoPlanRegistry.StaleMemoPlanException.class,
				() -> Recompiler.recompileHopsDagInstructions(expBlock, expBlock.getHops()));
			Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("occurrence shapes"));
		}
		finally {
			ConfigurationManager.setLocalConfig(old);
		}
	}

	@Test
	public void publicationFailureRollsBackEveryProjectedHopAndRegistry() throws Exception {
		DMLProgram seed = parse("A=matrix(1,2,2);for(i in 1:3){A=A+1;}print(sum(A));");
		new FederatedPlanMemo().plan(seed);
		Map<StatementBlock, List<org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime.Contract>> loopsBefore =
			org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime.snapshotAll();
		org.apache.sysds.lops.compile.MemoSupplyRegistry.Supply sentinel =
			new org.apache.sysds.lops.compile.MemoSupplyRegistry.Supply(901, 902, 0,
				MemoPartition.LOCAL, List.of(new org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe(
					MemoPartition.LOCAL, org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement.DIRECT, null)));
		org.apache.sysds.lops.compile.MemoSupplyRegistry.replaceAll(Map.of(900L, List.of(sentinel)));
		Map<Long, List<org.apache.sysds.lops.compile.MemoSupplyRegistry.Supply>> suppliesBefore =
			org.apache.sysds.lops.compile.MemoSupplyRegistry.snapshotAll();

		DMLProgram target = parse("A=matrix(2,2,2);B=abs(A);print(sum(B));");
		MemoProgramFacts facts = MemoProgramFacts.analyze(target);
		IdentityHashMap<Hop, HopState> hopsBefore = new IdentityHashMap<>();
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			Hop hop = facts.compiledOwnerHop(owner);
			hopsBefore.put(hop, new HopState(hop));
		}
		long seedEpoch = MemoPlanRegistry.epoch(seed);
		RuntimeException injected = new RuntimeException("injected memo publication failure");
		try(var registry = Mockito.mockStatic(MemoPlanRegistry.class, Mockito.CALLS_REAL_METHODS)) {
			registry.when(() -> MemoPlanRegistry.publish(Mockito.same(target),
				Mockito.any(MemoProgramFacts.class), Mockito.any(RuleMemo.class), Mockito.any(int[].class),
				Mockito.any(MemoShapes.class), Mockito.anyMap(), Mockito.any(), Mockito.anyMap())).thenThrow(injected);
			Assert.assertSame(injected, Assert.assertThrows(RuntimeException.class,
				() -> new FederatedPlanMemo().plan(target)));
		}

		for(Map.Entry<Hop, HopState> entry : hopsBefore.entrySet())
			Assert.assertEquals("failed publication must restore Hop " + entry.getKey().getHopID(),
				entry.getValue(), new HopState(entry.getKey()));
		Assert.assertEquals(suppliesBefore,
			org.apache.sysds.lops.compile.MemoSupplyRegistry.snapshotAll());
		Assert.assertEquals(loopsBefore,
			org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime.snapshotAll());
		Assert.assertFalse(MemoPlanRegistry.hasPlan(target));
		Assert.assertTrue(MemoPlanRegistry.hasPlan(seed));
		Assert.assertEquals(seedEpoch, MemoPlanRegistry.epoch(seed));
	}

	private static UnaryOp unary(MemoProgramFacts facts, OpOp1 operation) {
		return (UnaryOp) facts.occurrences().stream().map(MemoProgramFacts.Occurrence::hop)
			.filter(hop -> hop instanceof UnaryOp && ((UnaryOp) hop).getOp() == operation)
			.findFirst().orElseThrow();
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	@After
	public void clearMemoPlan() {
		MemoPlanRegistry.clear();
	}
}
