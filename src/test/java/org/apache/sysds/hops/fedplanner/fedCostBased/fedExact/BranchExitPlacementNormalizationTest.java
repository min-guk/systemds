/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.hops.recompile.Recompiler.ResetType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.BasicProgramBlock;
import org.apache.sysds.runtime.controlprogram.FunctionProgramBlock;
import org.apache.sysds.runtime.controlprogram.IfProgramBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.ProgramBlock;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.BooleanObject;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/** Branch-exit carriers expose exact movement choices and lower them on the selected path. */
public class BranchExitPlacementNormalizationTest {
	@After
	public void clearPlannerState() {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	@Test
	public void functionBranchCanDownloadOnlyAtTheExitAndRuntimeLowersTheCarrier() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldConfig);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		try {
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = compile(program());
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
				program, Privacy.PRIVATE_AGGREGATE);
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue(captured.get() instanceof ExactPlacementInput);
			ExactPlacementInput exact = (ExactPlacementInput) captured.get();

			List<CompiledHopKey> carriers = exact.analysis().occurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTWRITE
					&& data.isPlannerBranchNormalization())
				.map(occurrence -> occurrence.key()).toList();
			Assert.assertEquals("one carrier is required on each branch exit", 2, carriers.size());
			for(CompiledHopKey carrier : carriers) {
				PlacementState state = exact.normalizedResult().selectedStates().get(carrier);
				Assert.assertEquals(ExecType.CP, state.execType());
				Assert.assertEquals(FederatedOutput.LOUT, state.output());
				Assert.assertTrue("carrier input must remain an executable movement edge",
					exact.analysis().graph().constraints().stream().anyMatch(constraint ->
						constraint.right() == carrier && constraint.inputPosition() == 0
							&& constraint.kind() == ConstraintKind.DOMINATES
							&& "data-input".equals(constraint.evidence())));
				Assert.assertTrue("the carrier write must still bind exactly into the branch join",
					exact.analysis().graph().constraints().stream().anyMatch(constraint ->
						constraint.left() == carrier
							&& constraint.kind() == ConstraintKind.SAME_PLACEMENT
							&& constraint.evidence().startsWith("cfg-transient-value:")));
			}
			CompiledHopKey elseCarrier = carriers.stream()
				.filter(key -> key.callSitePath().contains("branch-else"))
				.findFirst().orElseThrow();
			Hop elseWrite = exact.analysis().hop(elseCarrier).orElseThrow();
			CompiledHopKey elseRead = exact.analysis().occurrences().stream()
				.filter(occurrence -> occurrence.hop() == elseWrite.getInput(0))
				.map(occurrence -> occurrence.key()).findFirst().orElseThrow();
			PlacementState source = exact.normalizedResult().selectedStates().get(elseRead);
			Assert.assertEquals(ExecType.FED, source.execType());
			Assert.assertEquals(FederatedOutput.FOUT, source.output());
			Assert.assertEquals(FType.COL, source.fType());

			@SuppressWarnings("unchecked")
			List<LocalMaterializationActionKey> locals =
				(List<LocalMaterializationActionKey>) exact.normalizedResult().selectedLocalMaterializations();
			Assert.assertTrue("the false branch must own its exit download",
				locals.stream().flatMap(action -> action.obligations().stream())
					.anyMatch(obligation -> obligation.consumerOccurrence() == elseCarrier));
			Assert.assertTrue("the inverse CP-to-FOUT branch-exit choice must remain available",
				exact.analysis().graph().relocationActions().stream()
					.filter(action -> action.key().targetPlacement().execType() == ExecType.FED)
					.filter(action -> action.key().targetPlacement().output() == FederatedOutput.FOUT)
					.anyMatch(action -> action.key().compatibleConsumers().stream()
						.anyMatch(carriers::contains)));

			Program runtime = translator.getRuntimeProgram(program, config);
			List<LocatedInstruction> instructions = new ArrayList<>();
			runtime.getFunctionProgramBlocks().forEach((name, block) ->
				collect(block, "function/" + name, instructions));
			List<String> falseBranch = instructions.stream()
				.filter(instruction -> instruction.path().contains("branch-else"))
				.map(instruction -> instruction.instruction().getInstructionString()).toList();
			Assert.assertTrue(falseBranch.toString(), falseBranch.stream()
				.anyMatch(value -> value.contains("prefetch") && value.contains("Y")));
			Assert.assertTrue(falseBranch.toString(), falseBranch.stream()
				.anyMatch(value -> value.contains("mvvar") && value.endsWith("°Y")));

			FunctionProgramBlock function = runtime.getFunctionProgramBlock(
				DMLProgram.DEFAULT_NAMESPACE, "f", true);
			LocalVariableMap runtimeInputs = new LocalVariableMap();
			MatrixObject runtimeY = new MatrixObject(org.apache.sysds.common.Types.ValueType.FP64,
				"runtime-Y", new MetaDataFormat(new MatrixCharacteristics(1, 4, 1024),
					org.apache.sysds.common.Types.FileFormat.BINARY));
			runtimeY.setFedMapping(new FederationMap(17, List.of(), FType.COL));
			runtimeInputs.put("Y", runtimeY);
			runtimeInputs.put("flag", new BooleanObject(false));
			Recompiler.recompileProgramBlockHierarchy(function.getChildBlocks(), runtimeInputs,
				0, false, ResetType.RESET);
			instructions.clear();
			collect(function, "function/f", instructions);
			List<Instruction> recompiledFalseBranch = instructions.stream()
				.filter(instruction -> instruction.path().contains("branch-else"))
				.map(LocatedInstruction::instruction).toList();
			Assert.assertTrue("phase two must retain the phase-one carrier download: "
				+ recompiledFalseBranch, recompiledFalseBranch.stream().anyMatch(instruction ->
					instruction.getInstructionString().contains("prefetch")
						&& instruction.getPlannerSyntheticActionKey() != null));
			Assert.assertTrue("phase two must retain the carrier binding: " + recompiledFalseBranch,
				recompiledFalseBranch.stream().anyMatch(instruction ->
					instruction.getInstructionString().contains("mvvar")
						&& instruction.getInstructionString().endsWith("°Y")));
			Assert.assertEquals(0,
				PlacementEmissionTransaction.observabilitySnapshot().runtimeFallbackCount());
			Assert.assertEquals(0,
				PlacementEmissionTransaction.observabilitySnapshot().runtimeRepairCount());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	private static void collect(ProgramBlock block, String path,
		List<LocatedInstruction> instructions) {
		if(block instanceof BasicProgramBlock basic)
			for(Instruction instruction : basic.getInstructions())
				instructions.add(new LocatedInstruction(path, instruction));
		if(block instanceof IfProgramBlock branch) {
			for(ProgramBlock child : branch.getChildBlocksIfBody())
				collect(child, path + "/branch-if", instructions);
			for(ProgramBlock child : branch.getChildBlocksElseBody())
				collect(child, path + "/branch-else", instructions);
		}
		else if(block.getChildBlocks() != null)
			for(ProgramBlock child : block.getChildBlocks())
				collect(child, path + "/child", instructions);
	}

	private record LocatedInstruction(String path, Instruction instruction) { }

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static String program() {
		return """
			f=function(matrix[double] Y, boolean flag) return(matrix[double] R) {
			  if(flag) { Y=Y+1; }
			  k=1; while(k<2) { k=k+1; }
			  R=Y;
			}
			X=federated(addresses=list("localhost:1234/X1","localhost:1235/X2"),
			 ranges=list(list(0,0),list(8,2),list(0,2),list(8,4)));
			Y=colSums(X);
			flag=as.scalar(rand(rows=1,cols=1,seed=7))>0.5;
			R=f(Y,flag);
			print(toString(R));
			""";
	}
}
