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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Ordinary consumers retain GET accounting when function inputs are alias-only. */
public class ExactFunctionGetSharingTest {
	@Test public void ordinaryDemandStillCreatesOneGetBesideAliasedFunctionFormals() throws Exception {
		var analysis = analyze(false);
		var inputs = analysis.logicalFunctionInputsInCanonicalOrder();
		Assert.assertEquals(2, inputs.size());
		Assert.assertSame(inputs.get(0).sourceArgument(), inputs.get(1).sourceArgument());
		var model = ExactPhysicalModel.build(analysis);
		var source = domain(model, inputs.get(0).sourceArgument());
		var functionTargets = new ArrayList<ExactPhysicalModel.DecisionDomain>();
		for(var input : inputs) functionTargets.add(domain(model, input.targetRead()));
		var ordinary = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> analysis.hop(edge.producer()).orElseThrow().getName().equals("Y")
				&& analysis.hop(edge.consumer()).orElse(null) instanceof AggUnaryOp)
			.findFirst().orElseThrow();
		var ordinaryTarget = domain(model, ordinary.consumer());
		var targets = new ArrayList<>(functionTargets);
		targets.add(ordinaryTarget);
		var ordinarySource = domain(model, ordinary.producer());
		var sources = List.of(source, ordinarySource);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		double unit = FederatedCostModel.computeReusableMaterializationDownloadCost(
			PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, ordinary.producer()),
			ordinarySource.alternatives().get(fed(ordinarySource)).state().fType(), 2);
		for(boolean localOrdinary : List.of(false, true)) {
			List<Integer> values = assignments(model);
			for(var inputSource : sources) values.set(model.domains().indexOf(inputSource), fed(inputSource));
			for(var target : functionTargets)
				values.set(model.domains().indexOf(target), fed(target, false));
			values.set(model.domains().indexOf(ordinaryTarget),
				localOrdinary ? local(ordinaryTarget) : fed(ordinaryTarget, false));
			Assert.assertEquals("Only the ordinary local consumer may activate the retained GET",
				localOrdinary ? unit : 0.0, getCost(surface, sources, targets, values), 1e-9);
		}
	}

	@Test public void distinctFoutActualsDoNotCreateFunctionBoundaryGets() throws Exception {
		var analysis = analyze(true);
		var inputs = analysis.logicalFunctionInputsInCanonicalOrder();
		Assert.assertEquals(2, inputs.size());
		Assert.assertNotSame(inputs.get(0).sourceArgument(), inputs.get(1).sourceArgument());
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var sources = inputs.stream().map(input -> domain(model, input.sourceArgument())).toList();
		var targets = inputs.stream().map(input -> domain(model, input.targetRead())).toList();
		List<Integer> values = assignments(model);
		for(var source : sources) values.set(model.domains().indexOf(source), fed(source));
		for(var target : targets) values.set(model.domains().indexOf(target), fed(target, false));
		Assert.assertEquals("Alias-compatible function inputs must not create retained GETs",
			0.0, getCost(surface, sources, targets, values), 1e-9);
	}

	private static double getCost(ExactPhysicalCostModel.PhysicalCostSurface surface,
		List<ExactPhysicalModel.DecisionDomain> sources,
		List<ExactPhysicalModel.DecisionDomain> targets, List<Integer> values) {
		return surface.contributions().stream().filter(contribution ->
			sources.stream().anyMatch(source -> contribution.factor().scope().contains(source.variable()))
			&& targets.stream().anyMatch(target -> contribution.factor().scope().contains(target.variable())))
			.mapToDouble(contribution -> surface.evaluateContributionCanonical(contribution, values)).sum();
	}

	private static ExactPhysicalModel.DecisionDomain domain(ExactPhysicalModel model,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key) {
		return model.domains().stream().filter(domain -> domain.node().key() == key).findFirst().orElseThrow();
	}

	private static List<Integer> assignments(ExactPhysicalModel model) {
		var values = new ArrayList<Integer>();
		for(var domain : model.domains()) values.add(0);
		return values;
	}

	private static int local(ExactPhysicalModel.DecisionDomain domain) {
		for(int index = 0; index < domain.alternatives().size(); index++)
			if(domain.alternatives().get(index).state().output() == FederatedOutput.LOUT) return index;
		throw new AssertionError("No local alternative: " + domain.node().key());
	}

	private static int fed(ExactPhysicalModel.DecisionDomain domain) { return fed(domain, true); }

	private static int fed(ExactPhysicalModel.DecisionDomain domain, boolean requireFout) {
		for(int index = 0; index < domain.alternatives().size(); index++) {
			var alternative = domain.alternatives().get(index);
			if(alternative.state().execType() == ExecType.FED
				&& (!requireFout || alternative.state().output() == FederatedOutput.FOUT)
				&& alternative.inputAuthorities().stream().noneMatch(authority ->
					authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION)) return index;
		}
		throw new AssertionError("No direct FED alternative: " + domain.node().key());
	}

	private static PlacementAnalysis analyze(boolean distinct) throws Exception {
		String script = """
			consume=function(matrix[double] A, matrix[double] C) return(matrix[double] B) {
			  B=A+C; j=1; while(j<2) { B=B+0; j=j+1; }
			}
			X_LOCAL=rand(rows=6,cols=3,seed=7);
			X=federated(local_matrix=X_LOCAL, addresses=list("localhost:1234","localhost:1235"),
			 ranges=list(list(0,0),list(3,3),list(3,0),list(6,3)));
			Y_LOCAL=rand(rows=6,cols=3,seed=8);
			Y=federated(local_matrix=Y_LOCAL, addresses=list("localhost:2234","localhost:2235"),
			 ranges=list(list(0,0),list(3,3),list(3,0),list(6,3)));
			""" + (distinct ? "W=X+1; Z=consume(X,W);" : "Z=consume(X,X); print(sum(Y));")
			+ "print(sum(Z));";
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program);
		translator.constructHops(program); translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		if(!distinct)
			setFederatedSourcePrivacy(program, "Y", Privacy.PUBLIC);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}

	private static void setFederatedSourcePrivacy(DMLProgram program, String sourceName, Privacy privacy) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.forEach(block -> pending.addAll(block.getHops()));
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		int matches = 0;
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop)) continue;
			if(hop instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.FEDERATED
				&& sourceName.equals(data.getName())) {
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(data, privacy);
				matches++;
			}
			pending.addAll(hop.getInput());
		}
		Assert.assertEquals(1, matches);
	}
}
