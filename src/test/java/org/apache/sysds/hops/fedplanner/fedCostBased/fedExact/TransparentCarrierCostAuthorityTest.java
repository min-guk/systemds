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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.rewrite.FederatedBranchExitNormalizer;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class TransparentCarrierCostAuthorityTest {
	@Test
	public void sourceReachabilityArmsFormOneExclusiveCacheEvent() {
		var consumer = profile(1.0, List.of(), List.of());
		var ifEvent = ExactPhysicalCostModel.materializationActivation(
			profile(0.5, List.of(), List.of(condition("branch", true, List.of()))), consumer);
		var elseEvent = ExactPhysicalCostModel.materializationActivation(
			profile(0.5, List.of(), List.of(condition("branch", false, List.of()))), consumer);
		Assert.assertEquals(1.0, ExactPhysicalCostModel.reusableActivationUnion(
			List.of(ifEvent.conditions(), elseEvent.conditions()),
			List.of(ifEvent.weight(), elseEvent.weight()), 1.0), 0.0);
	}

	@Test
	public void branchInsideCreationLifetimeDoesNotClaimExclusiveArms() {
		var consumer = profile(4.0, List.of(Pair.of(7L, 4.0)), List.of());
		var ifEvent = ExactPhysicalCostModel.materializationActivation(
			profile(1.0, List.of(), List.of(condition("branch", true, List.of(7L)))), consumer);
		var elseEvent = ExactPhysicalCostModel.materializationActivation(
			profile(1.0, List.of(), List.of(condition("branch", false, List.of(7L)))), consumer);
		Assert.assertNotEquals(ifEvent.conditions(), elseEvent.conditions());
		Assert.assertEquals(1.0, ExactPhysicalCostModel.reusableActivationUnion(
			List.of(ifEvent.conditions(), elseEvent.conditions()),
			List.of(ifEvent.weight(), elseEvent.weight()), 1.0), 0.0);
	}

	@Test
	public void oppositeArmsInsideOneCreationLifetimeRemainPossible() {
		var source = profile(1.0, List.of(),
			List.of(condition("branch", true, List.of(7L))));
		var consumer = profile(2.0, List.of(Pair.of(7L, 4.0)),
			List.of(condition("branch", false, List.of(7L))));
		var event = ExactPhysicalCostModel.materializationActivation(source, consumer);
		Assert.assertEquals(1.0, event.weight(), 0.0);
		Assert.assertEquals(2, event.conditions().size());
	}

	@Test
	public void emptyElseJoinRetainsOneReusableGetLifetime() throws Exception {
		assertSameGetLifetime(
			"if(sum(rand(rows=1,cols=1,seed=7))>0.5){S=S+1;}\n");
	}

	@Test
	public void twoPassThroughArmsRetainBothReachabilityGuards() throws Exception {
		assertSameGetLifetime("if(sum(rand(rows=1,cols=1,seed=7))>0.5){S=S;}else{S=S;}\n");
	}

	@Test
	public void twoFreshArmsHaveOneBranchSelectedGetLifetime() throws Exception {
		assertSameGetLifetime(
			"if(sum(rand(rows=1,cols=1,seed=7))>0.5){S=S+1;}else{S=S+2;}\n");
	}

	@Test
	public void nestedBranchGuardsPreserveTheirJointProbability() throws Exception {
		assertSameGetLifetime("if(sum(rand(rows=1,cols=1,seed=7))>0.5){"
			+ "if(sum(rand(rows=1,cols=1,seed=8))>0.5){S=S+1;}}\n");
	}

	@Test
	public void sequentialBranchGuardsMultiplyIndependentProbabilities() throws Exception {
		assertSameGetLifetime("if(sum(rand(rows=1,cols=1,seed=7))>0.5){S=S+1;}\n"
			+ "if(sum(rand(rows=1,cols=1,seed=8))>0.5){S=S+1;}\n");
	}

	@Test
	public void sequentialDerivedOutputAuthorityRejectsAlteredWorkerRange() throws Exception {
		PlacementAnalysis analysis = branchAnalysis(
			"if(sum(rand(rows=1,cols=1,seed=7))>0.5){S=S+1;}\n"
				+ "if(sum(rand(rows=1,cols=1,seed=8))>0.5){S=S+1;}\n");
		var actionsInGraph = analysis.graph().derivedFoutMaterializationActions();
		var ownerAction = actionsInGraph.stream().filter(action ->
			action.exactOutputAuthorities().stream().anyMatch(authority -> actionsInGraph.stream()
				.anyMatch(dependent -> dependent.key().durableAnchorOwner() == authority.owner()
					&& PlacementIdentity.samePhysicalWorkerPool(
						authority.anchor(),dependent.key().durableAnchor())))).findFirst().orElseThrow();
		var authority = ownerAction.exactOutputAuthorities().stream().filter(candidate ->
			actionsInGraph.stream().anyMatch(dependent ->
				dependent.key().durableAnchorOwner() == candidate.owner()
					&& PlacementIdentity.samePhysicalWorkerPool(
						candidate.anchor(),dependent.key().durableAnchor()))).findFirst().orElseThrow();
		DurableAnchorKey anchor = authority.anchor();
		List<AnchorPartition> changed = new ArrayList<>(anchor.partitions());
		AnchorPartition first = changed.get(0);
		List<Long> end = new ArrayList<>(first.end());
		end.set(end.size()-1,end.get(end.size()-1)+1);
		changed.set(0,new AnchorPartition(first.workerId(),first.begin(),end));
		var invalidAuthority = new NeutralPlacementGraph.DerivedFoutOutputAuthority(
			authority.owner(),new DurableAnchorKey(anchor.placementId(),anchor.fType(),changed));
		List<NeutralPlacementGraph.DerivedFoutOutputAuthority> altered =
			new ArrayList<>(ownerAction.exactOutputAuthorities());
		altered.set(altered.indexOf(authority),invalidAuthority);
		List<NeutralPlacementGraph.DerivedFoutMaterializationAction> actions =
			new ArrayList<>(actionsInGraph);
		actions.set(actions.indexOf(ownerAction),new NeutralPlacementGraph.DerivedFoutMaterializationAction(
			ownerAction.key(),altered));

		Assert.assertThrows("a forged output range must not ground the following branch upload",
			IllegalArgumentException.class, () -> new NeutralPlacementGraph(analysis.graph().nodes(),
				analysis.graph().constraints(),analysis.graph().relocationActions(),actions));
	}

	private static void assertSameGetLifetime(String branch) throws Exception {
		CostProbe stable = repeatedReadGetCost("");
		CostProbe joined = repeatedReadGetCost(branch);
		Assert.assertTrue("fixture must expose a charged federated materialization", stable.cost() > 0.0);
		Assert.assertTrue("fixture must contain compiler branch carriers", joined.carriers() > 0);
		Assert.assertEquals("transparent branch carriers must not restart the GET cache in the loop",
			stable.cost(), joined.cost(), 1e-9);
	}

	private static CostProbe repeatedReadGetCost(String branch) throws Exception {
		PlacementAnalysis analysis = branchAnalysis(branch);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		long carriers = analysis.occurrences().stream().filter(occurrence ->
			occurrence.hop() instanceof DataOp data && data.isPlannerBranchNormalization()).count();
		var read = model.domains().stream().filter(domain ->
			analysis.hop(domain.node().key()).orElse(null) instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && data.getName().equals("S")
				&& analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(edge ->
					edge.producer() == domain.node().key()
						&& analysis.hop(edge.consumer()).orElse(null) instanceof AggUnaryOp))
			.findFirst().orElseThrow();
		List<Integer> assignment = new ArrayList<>();
		for(var domain : model.domains()) {
			int selected = 0;
			if(domain == read) {
				selected = -1;
				for(int value = 0; value < domain.alternatives().size(); value++)
					if(domain.alternatives().get(value).state().output() == FederatedOutput.FOUT) {
						selected = value;
						break;
					}
				Assert.assertTrue("loop read must expose an FOUT alternative", selected >= 0);
			}
			assignment.add(selected);
		}
		double cost = surface.contributions().stream()
			.filter(contribution -> contribution.factor().scope().size() > 1
				&& contribution.factor().scope().contains(read.variable()))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(
				contribution, assignment)).sum();
		return new CostProbe(cost, carriers);
	}

	private static PlacementAnalysis branchAnalysis(String branch) throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));S=rowSums(X);"
			+ branch + "for(i in 1:4){print(sum(S));}";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		FederatedBranchExitNormalizer.normalize(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
	}

	private record CostProbe(double cost, long carriers) { }

	private static OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact profile(double weight,
		List<Pair<Long,Double>> loops,
		List<OccurrenceExecutionFrequencyFacts.BranchActivationFact> conditions) {
		return new OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact(
			weight, loops, 0L, conditions);
	}

	private static OccurrenceExecutionFrequencyFacts.BranchActivationFact condition(
		String key, boolean ifArm, List<Long> loops) {
		return new OccurrenceExecutionFrequencyFacts.BranchActivationFact(
			key, ifArm, 0.5, loops);
	}
}
