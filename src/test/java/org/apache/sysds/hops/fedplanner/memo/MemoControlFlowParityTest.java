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
package org.apache.sysds.hops.fedplanner.memo;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.junit.Assert;
import org.junit.Test;

/** End-to-end control-flow fixtures over the immutable Memo model and exact optimizer. */
public class MemoControlFlowParityTest {
	private static final String SHARED_ABS =
		"f=function(boolean flag,matrix[double] X) return(matrix[double] Y){"
			+ "if(flag){Y=abs(X);}else{Y=abs(X);}}";

	@Test
	public void sharedFunctionChoiceServesProtectedFedAndPublicLocalCalls() throws Exception {
		Fixture fixture = model(SHARED_ABS
			+ "P=matrix(1,rows=8,cols=4);"
			+ "Q=matrix(2,rows=8,cols=4);A=f(TRUE,P);B=f(TRUE,Q);print(sum(A)+sum(B));",
			Map.of("P",Privacy.PRIVATE_AGGREGATE));
		MemoProgramModel.Solution solution = optimize(fixture.model());

		Assert.assertEquals(2,fixture.facts().calls().size());
		Assert.assertEquals(fixture.facts().call(0).functionSummaryId(),
			fixture.facts().call(1).functionSummaryId());
		MemoProgramFacts.FunctionSummary summary = fixture.facts().functionSummary(
			fixture.facts().call(0).functionSummaryId());
		List<Integer> absOwners = summary.compiledOwnerIds().stream()
			.filter(owner -> fixture.facts().compiledOwnerHop(owner) instanceof UnaryOp unary
				&& unary.getOp() == OpOp1.ABS).toList();
		Assert.assertFalse("the non-inlined body must own its shared native abs choice",absOwners.isEmpty());
		int[] selected = solution.optimization().selectedRules();
		for(int owner : absOwners) {
			RuleMemo.Rule rule = fixture.model().memo().rule(owner,selected[owner]);
			Assert.assertEquals("protected actual forces the one compiled body choice to FED",ExecType.FED,rule.exec());
			Assert.assertEquals(MemoPartition.ROW,rule.inputs().get(0));
		}
		Assert.assertTrue("the local actual must use the shared FED body through an upload",
			allSupplies(solution).stream().anyMatch(supply -> supply.recipes().stream()
				.anyMatch(recipe -> recipe.sourcePartition() == MemoPartition.LOCAL
					&& recipe.movement() == MemoSupplyRegistry.Movement.UPLOAD)));
	}

	@Test
	public void sharedPhysicalInputUnionsAllOccurrenceRecipes() throws Exception {
		Hop localOne=matrix("local-one",8,4);
		Hop localTwo=matrix("local-two",8,4);
		DataOp federated=new DataOp("fed",DataType.MATRIX,ValueType.FP64,
			OpOpData.FEDERATED,"fed",-1,-1,-1,1000);
		DataOp read=new DataOp("V",DataType.MATRIX,ValueType.FP64,
			OpOpData.TRANSIENTREAD,"V",-1,-1,-1,1000);
		UnaryOp shared=new UnaryOp("shared",DataType.MATRIX,ValueType.FP64,OpOp1.ABS,read);
		StatementBlock block=new StatementBlock();
		block.setHops(new ArrayList<>(List.of(write("V",localOne),write("out-one",shared),
			write("V",localTwo),write("out-two",shared),write("V",federated),write("out-fed",shared))));
		DMLProgram program=new DMLProgram(); program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,
			data -> sourceMetadata(Privacy.PRIVATE_AGGREGATE,data.getName(),8,4));
		MemoRuleBuilder.Result rules=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramModel model=new MemoProgramModel(facts,sources,rules,MemoProgramModel.Profile.defaults());
		MemoProgramModel.Solution solution=optimize(model);

		List<MemoSupplyRegistry.Supply> guarded=allSupplies(solution).stream()
			.filter(supply -> supply.consumerHopId()==shared.getHopID() && supply.inputPosition()==0).toList();
		Assert.assertEquals("one shared physical input must have one supply",1,guarded.size());
		Assert.assertEquals("the supply must retain both direct and moved occurrence layouts",
			Set.of(MemoPartition.LOCAL,MemoPartition.ROW),guarded.get(0).recipes().stream()
				.map(MemoSupplyRegistry.Recipe::sourcePartition).collect(java.util.stream.Collectors.toSet()));
		model.supplySchedule(23,solution);
	}

	@Test
	public void correlatedBranchDefinitionsRemainGuardedSupplies() throws Exception {
		Fixture fixture = model("P=matrix(1,rows=8,cols=4);"
			+ "Q=matrix(2,rows=8,cols=4);p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=P;B=Q;}else{A=Q;B=P;}C=abs(A);D=abs(B);print(sum(C)+sum(D));",
			Map.of("P",Privacy.PRIVATE_AGGREGATE));
		MemoProgramModel.Solution solution = optimize(fixture.model());

		List<MemoSupplyRegistry.Supply> guarded = allSupplies(solution).stream()
			.filter(MemoSupplyRegistry.Supply::guarded).toList();
		Assert.assertEquals("both binary inputs have exactly the two correlated reaching layouts",2,guarded.size());
		for(MemoSupplyRegistry.Supply supply : guarded) {
			Assert.assertEquals(2,supply.recipes().size());
			Assert.assertEquals(Set.of(MemoPartition.LOCAL,MemoPartition.ROW),supply.recipes().stream()
				.map(MemoSupplyRegistry.Recipe::sourcePartition).collect(java.util.stream.Collectors.toSet()));
		}
		MemoPlanRegistry.SupplySchedule schedule=fixture.model().supplySchedule(17,solution);
		Assert.assertFalse("the non-direct guarded alternative has an explicit selected action",
			schedule.edges().isEmpty());
		Assert.assertTrue("top-level guarded values use the broad program-root lifetime",
			schedule.scopes().stream().allMatch(scope -> scope.kind()==MemoPlanRegistry.ScopeKind.PROGRAM_ROOT));
	}

	@Test
	public void loopUploadsEntryKeepsDirectBackedgeAndCollectsExit() throws Exception {
		String collectFunction = "g=function(boolean flag,matrix[double] X) return(matrix[double] Y){"
			+ "if(flag){Y=abs(X);}else{Y=abs(X);}}";
		String localCalls = "Q=matrix(2,rows=64,cols=16);B1=g(TRUE,Q);B2=g(TRUE,Q);B3=g(TRUE,Q);";
		Fixture fixture = model(SHARED_ABS + collectFunction
			+ "P=matrix(1,rows=64,cols=16);"
			+ "R=f(TRUE,P);A=matrix(1,rows=64,cols=16);for(i in 1:4){A=f(TRUE,A);}"
			+ localCalls + "C=g(TRUE,A);print(sum(R)+sum(C)+sum(B1)+sum(B2)+sum(B3));",
			Map.of("P",Privacy.PRIVATE_AGGREGATE));
		MemoProgramModel.Solution solution = optimize(fixture.model());
		Map<org.apache.sysds.parser.StatementBlock,List<MemoLoopRuntime.Contract>> contracts =
			fixture.model().loopContracts(solution.optimization().selectedRules());

		MemoLoopRuntime.Contract carried = contracts.values().stream().flatMap(List::stream)
			.filter(contract -> contract.variable().equals("A")).findFirst().orElseThrow(() ->
				new AssertionError("missing A contract: heads=" + fixture.model().heads() + ", contracts=" + contracts));
		Assert.assertEquals(MemoPartition.ROW,carried.target());
		Assert.assertTrue("entry may upload once into the selected loop head",
			carried.entryRecipes().stream().anyMatch(recipe -> recipe.sourcePartition() == MemoPartition.LOCAL
				&& recipe.movement() == MemoSupplyRegistry.Movement.UPLOAD));
		Assert.assertTrue("the loop live-out may collect for the shared CP function body",
			allSupplies(solution).stream().anyMatch(supply -> supply.recipes().stream()
				.anyMatch(recipe -> recipe.sourcePartition() == MemoPartition.ROW
					&& recipe.movement() == MemoSupplyRegistry.Movement.COLLECT)));
		Assert.assertFalse("backedge transfer candidates do not exist outside the entry-only contract",
			carried.entryRecipes().stream().anyMatch(recipe -> recipe.sourcePartition() == MemoPartition.ROW
				&& recipe.movement() != MemoSupplyRegistry.Movement.DIRECT));
	}

	@Test
	public void functionLoopHeadUsesActualCallFrameToExposeFederatedLayouts() throws Exception {
		Fixture fixture=model("f=function(matrix[double] X) return(matrix[double] Y){A=X;"
			+ "for(i in 1:3){A=abs(A);}Y=A;}P=matrix(1,rows=8,cols=4);R=f(P);print(sum(R));",
			Map.of("P",Privacy.PRIVATE_AGGREGATE_TO_PUBLIC));
		MemoProgramModel.Head carried=fixture.model().heads().stream()
			.filter(head -> "A".equals(head.value().variable())).findFirst().orElseThrow();
		var field=MemoProgramModel.class.getDeclaredField("sizes"); field.setAccessible(true);
		int[] sizes=(int[]) field.get(fixture.model());
		Assert.assertEquals("the formal entry's actual FED source must expose LOCAL/ROW/COL/REP",
			4,sizes[carried.variable()]);
	}

	@Test
	public void missingReturnTerminalEnvironmentReducesNormalContinuationCost() throws Exception {
		String prefix = "p=as.scalar(rand(rows=1,cols=1));X=matrix(-2,rows=64,cols=16);";
		Fixture terminal = model("f=function(boolean p,matrix[double] X) return(matrix[double] Y){"
			+ "if(p){Y=abs(X);}}" + prefix + "A=f(p,X);B=abs(X);print(sum(B));",Map.of());
		Fixture total = model("f=function(boolean p,matrix[double] X) return(matrix[double] Y){"
			+ "if(p){Y=abs(X);}else{Y=X;}}" + prefix + "A=f(p,X);B=abs(X);print(sum(B));",Map.of());
		MemoProgramModel.Solution terminalSolution = optimize(terminal.model());
		MemoProgramModel.Solution totalSolution = optimize(total.model());

		Assert.assertTrue("the throwing environment must not execute or cost the caller continuation",
			terminalSolution.optimization().cost().compareTo(totalSolution.optimization().cost()) < 0);
		Assert.assertTrue("terminal feasibility must not fabricate a supply from an unbound return",
			allSupplies(terminalSolution).stream().noneMatch(supply -> supply.recipes().isEmpty()));
	}

	private static MemoProgramModel.Solution optimize(MemoProgramModel model) {
		MemoProgramModel.Solution solution = model.optimize(MemoOptimizer.Limits.exactDefaults());
		Assert.assertEquals(solution.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,
			solution.optimization().status());
		return solution;
	}

	private static List<MemoSupplyRegistry.Supply> allSupplies(MemoProgramModel.Solution solution) {
		return solution.supplies().values().stream().flatMap(List::stream).toList();
	}

	private static Fixture model(String script,Map<String,Privacy> federatedPrivacy) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		for(String variable : federatedPrivacy.keySet()) replaceDefinitionWithFederated(program,variable);
		MemoProgramFacts facts = MemoProgramFacts.analyze(program);
		long rows = script.contains("rows=64") ? 64 : 8;
		long columns = script.contains("cols=16") ? 16 : 4;
		MemoSources sources = MemoSources.acquire(facts,null,data -> sourceMetadata(
			federatedPrivacy.getOrDefault(data.getName(),Privacy.PUBLIC),data.getName(),rows,columns));
		MemoRuleBuilder.Result rules = MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		return new Fixture(facts,new MemoProgramModel(facts,sources,rules,MemoProgramModel.Profile.defaults()));
	}

	private static void replaceDefinitionWithFederated(DMLProgram program,String variable) {
		for(StatementBlock block : program.getStatementBlocks()) {
			if(block.getHops() == null) continue;
			for(Hop root : block.getHops()) {
				if(!(root instanceof DataOp write) || write.getOp() != OpOpData.TRANSIENTWRITE
					|| !variable.equals(write.getName()) || write.getInput().size() != 1) continue;
				Hop generated = write.getInput(0);
				DataOp source = new DataOp(variable,DataType.MATRIX,ValueType.FP64,
					OpOpData.FEDERATED,variable,-1,-1,-1,1000);
				HopRewriteUtils.replaceChildReference(write,generated,source);
				return;
			}
		}
		throw new AssertionError("Missing parsed definition for federated fixture " + variable);
	}

	private static DataOp write(String name,Hop input) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,input,OpOpData.TRANSIENTWRITE,name);
	}

	private static Hop matrix(String name,int rows,int columns) {
		DataOp dimensions=new DataOp(name,DataType.MATRIX,ValueType.FP64,
			OpOpData.PERSISTENTREAD,"unused-"+name,rows,columns,(long) rows*columns,1000);
		return HopRewriteUtils.createDataGenOp(dimensions,-1);
	}

	private static FederatedSourceMetadata sourceMetadata(Privacy privacy,String variable,long rows,long columns) {
		long split = rows/2;
		return new FederatedSourceMetadata(privacy,List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {split,columns}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),variable+"1")),
			new ImmutablePair<>(new FederatedRange(new long[] {split,0},new long[] {rows,columns}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),variable+"2"))));
	}

	private record Fixture(MemoProgramFacts facts,MemoProgramModel model) { }
}
