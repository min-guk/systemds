/* Licensed to the Apache Software Foundation (ASF) under one or more
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
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Checks that final action replay cannot republish an unsupported function-call placement. */
public class FunctionCallPublicationProjectionRegressionTest {
	@Test
	public void frozenGnmfFunctionCallRemainsExecutableAndCoordinatorLocal() throws Exception {
		String catalog = System.getProperty("g009.gnmf.catalog");
		String evaluation = System.getProperty("g009.gnmf.evaluation");
		Assume.assumeTrue("frozen external capture inputs are not configured",
			catalog != null || evaluation != null);
		Assert.assertNotNull("set -Dg009.gnmf.catalog", catalog);
		Assert.assertNotNull("set -Dg009.gnmf.evaluation", evaluation);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			PlanningNativeModelCapture.prepareInput(Path.of(catalog), Path.of(evaluation),
				"cell_f25f9235b810daa7339e", true).program());
		Set<RelocationActionKey> publishedActions = analysis.graph().relocationActions().stream()
			.map(action -> action.key()).collect(Collectors.toSet());
		int functionCalls = 0;
		Set<RelocationActionKey> realizedActions = new HashSet<>();
		for(var node : analysis.graph().nodes()) {
			if(node.kind() != NodeKind.FUNCTION_CALL)
				continue;
			functionCalls++;
			Assert.assertEquals("function call has illegal placement: " + node.legalAlternatives(),
				1, node.legalAlternatives().size());
			Assert.assertEquals(ExecType.CP, node.legalAlternatives().get(0).execType());
			Assert.assertEquals(FederatedOutput.LOUT, node.legalAlternatives().get(0).output());
			int executableCallRealizations = 0;
			for(var fact : analysis.candidateRuleFacts().orderedFactsForParent(node.key())) {
				if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
					continue;
				for(var emission : fact.allowedEmissionFacts()) {
					Assert.assertEquals(ExecType.CP, emission.emissionState().placementState().execType());
					Assert.assertEquals(FederatedOutput.LOUT, emission.emissionState().placementState().output());
					executableCallRealizations += emission.realizations().size();
				}
			}
			Assert.assertTrue("function call must have executable candidate authority",
				executableCallRealizations > 0);
		}
		Assert.assertTrue("gnmf fixture must exercise a function call", functionCalls > 0);
		for(var fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			var owner = analysis.graph().node(fact.key().parentOccurrence()).orElseThrow();
			for(var emission : fact.allowedEmissionFacts())
				for(var realization : emission.realizations()) {
					Assert.assertTrue("executable realization has no published physical placement",
						owner.legalAlternatives().contains(emission.emissionState().placementState()));
					for(var clause : realization.supportClauses())
						for(var binding : clause.inputBindings())
							if(binding.kind() == CandidateInputBindingKind.RELOCATION) {
								Assert.assertTrue("realization refers to an unpublished relocation action",
									publishedActions.contains(binding.relocationAction()));
								realizedActions.add(binding.relocationAction());
							}
				}
		}
		for(var action : analysis.graph().relocationActions())
			if(action.directSourcePlacements().isEmpty())
				Assert.assertTrue("published action has no candidate realization support",
					realizedActions.contains(action.key()));
	}
}
