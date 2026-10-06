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

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.BranchPlacementNormalization;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compiler rewrites, DP selection and explicit movement for independently chosen pools. */
public class JointBoundaryMixedInputTest {

	@Test
	public void independentPublicOperandHasAnExplicitLocalPlanBesideProtectedValueMap() throws Exception {
		PlacementAnalysis analysis = analysis(true);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var optimized = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		var selection = ExactPhysicalSelection.create(model, optimized);
		var projected = ExactPhysicalPlacementProjector.project(selection).normalizedResult();
		var consumer = selection.alternativesInDecisionOrder().stream().filter(alternative ->
			analysis.hop(alternative.decision()).orElseThrow() instanceof BinaryOp binary
				&& "Z".equals(binary.getName())).findFirst().orElseThrow();
		Assert.assertEquals(ExecType.FED, consumer.state().execType());
		Assert.assertEquals(FederatedOutput.FOUT, consumer.state().output());
		Assert.assertEquals(PlacementLayoutKind.VALUE_MAP, consumer.realization().key().layoutKind());
		Assert.assertTrue(consumer.orderedInputs().get(0).present());
		Assert.assertFalse("the public input is explicitly collected for the protected input's current pool",
			consumer.orderedInputs().get(1).present());
		Assert.assertFalse("a feasible local input requires a selected transfer receipt",
			projected.selectedLocalMaterializations().isEmpty());
		for(Object raw : projected.selectedLocalMaterializations()) {
			var action = (LocalMaterializationActionKey) raw;
			String name = analysis.hop(action.sourceOccurrence()).orElseThrow().getName();
			Assert.assertTrue("only public source aliases may be collected: " + name,
				Set.of("A2", "B2", "V").contains(name));
		}
		Assert.assertTrue("selected FED work and transfers have nonzero physical cost",
			optimized.solverResult().objective() > 0);
	}

	@Test
	public void independentProtectedOperandsAreRejectedForInfeasibility() throws Exception {
		PlacementAnalysis analysis = analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> LocalPhysicalOptimizer.optimize(model, surface));
		Assert.assertTrue("resource overflow or internal errors are not privacy rejection: " + failure,
			failure.getMessage().contains("INFEASIBLE"));
	}

	private static PlacementAnalysis analysis(boolean publicSecondInputs) throws Exception {
		StringBuilder script = new StringBuilder();
		for(String name : List.of("A1", "A2", "B1", "B2")) {
			int port = name.startsWith("A") ? 23334 : 23335;
			script.append(name).append("=federated(addresses=list(\"localhost:").append(port)
				.append('/').append(name).append("\"),ranges=list(list(0,0),list(8,3)));\n");
		}
		script.append("flagU=sum(A1)>0;flagV=sum(A1)<0;\n")
			.append("if(flagU){U=A1;}else{U=B1;}\nif(flagV){V=A2;}else{V=B2;}\nZ=U+V;\n")
			.append("print(\"SUM=\"+sum(Z));print(\"NORM=\"+sum(Z*Z));")
			.append("print(\"ROWS=\"+nrow(Z));print(\"COLS=\"+ncol(Z));");
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script.toString(), new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		BranchPlacementNormalization.prepare(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		if(publicSecondInputs) {
			Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			for(var block : program.getStatementBlocks())
				if(block.getHops() != null)
					for(Hop root : block.getHops()) markPublicSecondInputs(root, visited);
		}
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void markPublicSecondInputs(Hop hop, Set<Hop> visited) {
		if(!visited.add(hop)) return;
		if(hop instanceof DataOp data && ("A2".equals(data.getName()) || "B2".equals(data.getName())))
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, Privacy.PUBLIC);
		for(Hop input : hop.getInput()) markPublicSecondInputs(input, visited);
	}
}
