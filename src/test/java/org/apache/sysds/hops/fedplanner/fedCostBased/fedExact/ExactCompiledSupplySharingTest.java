/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Production cost-surface checks for derived sharing across direct and staged REFED supplies. */
public class ExactCompiledSupplySharingTest {
	private static final double ITERATIONS = 3d;

	@Test
	public void productionSurfaceSharesInvariantLoutAndFoutButNotUpdatedSources() throws Exception {
		Fixture fixture = fixture();
		ForcedUpload stableLocal = forcedUpload(fixture, "stable", FederatedOutput.LOUT);
		ForcedUpload updatedLocal = forcedUpload(fixture, "updated", FederatedOutput.LOUT);
		ForcedUpload stableFout = forcedUpload(fixture, "stable", FederatedOutput.FOUT);
		ForcedUpload updatedFout = forcedUpload(fixture, "updated", FederatedOutput.FOUT);
		ForcedUpload stableAction = forcedUpload(fixture, "stable", FederatedOutput.LOUT,
			FederatedOutput.LOUT);

		Assert.assertTrue("invariant local source requires one retained runtime supply lifetime",
			fixture.surface().selectedSharedSupplyLifetimes(stableLocal.assignment())
				.contains(stableLocal.physicalIdentity()));
		Assert.assertFalse("loop-updated value versions must not reuse one runtime copy",
			fixture.surface().selectedSharedSupplyLifetimes(updatedLocal.assignment())
				.contains(updatedLocal.physicalIdentity()));
		Assert.assertTrue("invariant FOUT staging is fused into the retained REFED supply",
			fixture.surface().selectedSharedSupplyLifetimes(stableFout.assignment())
				.contains(stableFout.physicalIdentity()));
		Assert.assertFalse("loop-updated FOUT values must stage and upload per produced version",
			fixture.surface().selectedSharedSupplyLifetimes(updatedFout.assignment())
				.contains(updatedFout.physicalIdentity()));
		Assert.assertTrue("FED/LOUT input movement retains its exact ACTION identity",
			stableAction.physicalIdentity().startsWith("ACTION|")
				&& fixture.surface().selectedSharedSupplyLifetimes(stableAction.assignment())
					.contains(stableAction.physicalIdentity()));

		double stableCost = movementCost(fixture.surface(), stableLocal);
		double updatedCost = movementCost(fixture.surface(), updatedLocal);
		double stableFoutCost = movementCost(fixture.surface(), stableFout);
		double updatedFoutCost = movementCost(fixture.surface(), updatedFout);
		double stableFoutCollection = stagedCollectionCost(fixture.surface(), stableFout);
		double updatedFoutCollection = stagedCollectionCost(fixture.surface(), updatedFout);
		Assert.assertTrue("production REFED upload must have a finite positive price",
			Double.isFinite(stableCost) && stableCost > 0d);
		Assert.assertEquals("updated loop values upload once per produced version",
			ITERATIONS * stableCost, updatedCost, Math.max(1e-12, stableCost * 1e-12));
		Assert.assertEquals("invariant FOUT stages and uploads only on the shared supply miss",
			stableCost, stableFoutCost, Math.max(1e-12, stableCost * 1e-12));
		Assert.assertEquals("updated FOUT stages and uploads once per produced version",
			ITERATIONS * stableCost, updatedFoutCost, Math.max(1e-12, stableCost * 1e-12));
		Assert.assertTrue("FOUT staging must explicitly own its source collection",
			stableFoutCollection > 0d);
		Assert.assertEquals("updated FOUT source collection executes once per produced version",
			ITERATIONS * stableFoutCollection, updatedFoutCollection,
			Math.max(1e-12, stableFoutCollection * 1e-12));
	}

	static ForcedUpload forcedUpload(Fixture fixture, String variable,
		FederatedOutput sourceOutput) {
		return forcedUpload(fixture, variable, sourceOutput, FederatedOutput.FOUT);
	}

	static ForcedUpload forcedUpload(Fixture fixture, String variable,
		FederatedOutput sourceOutput, FederatedOutput targetOutput) {
		CompiledHopKey read = fixture.analysis().compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
				&& variable.equals(data.getName()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> fixture.analysis().executionFrequencyFacts()
				.exactExecutionWeight(key) == ITERATIONS)
			.findFirst().orElseThrow();
		var edge = fixture.analysis().compiledInputEdgesInCanonicalOrder().stream()
			.filter(candidate -> candidate.producer() == read
				&& "ba(+*)".equals(fixture.analysis().hop(candidate.consumer()).orElseThrow().getOpString()))
			.findFirst().orElseThrow();
		var source = domain(fixture.model(), read);
		var consumer = domain(fixture.model(), edge.consumer());
		int sourceValue = -1;
		for(int value = 0; value < source.alternatives().size(); value++) {
			var state = source.alternatives().get(value).state();
			if(state.output() == sourceOutput && (sourceOutput != FederatedOutput.LOUT
				|| state.execType() == ExecType.CP)) {
				sourceValue = value;
				break;
			}
		}
		Assert.assertTrue("source alternative missing for " + variable + '/' + sourceOutput,
			sourceValue >= 0);
		int consumerValue = -1;
		String physicalIdentity = null;
		for(int value = 0; value < consumer.alternatives().size(); value++) {
			var authority = consumer.alternatives().get(value).inputAuthorities().stream()
				.filter(input -> input.inputPosition() == edge.inputPosition()
					&& input.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
					&& input.relocationAction().key().targetPlacement().output() == targetOutput
					&& input.relocationAction().key().sourceValueVersion()
						.equals(fixture.analysis().graph().node(read).orElseThrow().valueVersion()))
				.findFirst().orElse(null);
			if(authority != null) {
				consumerValue = value;
				physicalIdentity = RelocationSelections.physicalEmissionIdentity(
					authority.relocationAction().key());
				break;
			}
		}
		Assert.assertTrue("REFED consumer alternative missing for " + variable, consumerValue >= 0);
		List<Integer> assignment = zeroAssignment(fixture.model());
		assignment.set(fixture.model().domains().indexOf(source), sourceValue);
		assignment.set(fixture.model().domains().indexOf(consumer), consumerValue);
		return new ForcedUpload(source, consumer, assignment, physicalIdentity,
			fixture.model().domains().indexOf(source));
	}

	static double movementCost(ExactPhysicalCostModel.PhysicalCostSurface surface,
		ForcedUpload upload) {
		var transfer = surface.transferKeys().stream()
			.filter(key -> key.direction() == ExactPhysicalCostModel.Direction.UPLOAD
				&& key.physicalEmissionIdentity().equals(upload.physicalIdentity())
				&& key.endpoints().stream().anyMatch(endpoint ->
					endpoint.producer() == upload.source().node().key()
						&& endpoint.consumer() == upload.consumer().node().key()))
			.toList();
		Assert.assertEquals("one exact b_e upload descriptor must own the selected supply", 1,
			transfer.size());
		List<Integer> localSource = localSourceAssignment(upload);
		List<?> scope = List.of(upload.source().variable(), upload.consumer().variable());
		var positive = surface.contributions().stream()
			.filter(contribution -> contribution.factor().scope().containsAll(scope))
			.map(contribution -> new java.util.AbstractMap.SimpleImmutableEntry<>(contribution,
				surface.evaluateContributionCanonical(contribution, upload.assignment())))
			.filter(entry -> entry.getValue() > 0d
				&& surface.evaluateContributionCanonical(entry.getKey(), localSource) > 0d).toList();
		int minimumScope = positive.stream().mapToInt(entry -> entry.getKey().factor().scope().size())
			.min().orElseThrow();
		var direct = positive.stream()
			.filter(entry -> entry.getKey().factor().scope().size() == minimumScope).toList();
		Assert.assertEquals("one contribution must own the exact b_e upload",
			1, direct.size());
		return direct.get(0).getValue();
	}

	private static double stagedCollectionCost(ExactPhysicalCostModel.PhysicalCostSurface surface,
		ForcedUpload upload) {
		List<Integer> localSource = localSourceAssignment(upload);
		List<?> scope = List.of(upload.source().variable(), upload.consumer().variable());
		var collection = surface.contributions().stream()
			.filter(contribution -> contribution.factor().scope().containsAll(scope))
			.map(contribution -> new java.util.AbstractMap.SimpleImmutableEntry<>(contribution,
				surface.evaluateContributionCanonical(contribution, upload.assignment())))
			.filter(entry -> entry.getValue() > 0d
				&& surface.evaluateContributionCanonical(entry.getKey(), localSource) == 0d).toList();
		Assert.assertEquals("one source collection must precede the selected staged upload",
			1, collection.size());
		return collection.get(0).getValue();
	}

	private static List<Integer> localSourceAssignment(ForcedUpload upload) {
		List<Integer> local = new ArrayList<>(upload.assignment());
		int localValue = -1;
		for(int value = 0; value < upload.source().alternatives().size(); value++) {
			var state = upload.source().alternatives().get(value).state();
			if(state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT) {
				localValue = value;
				break;
			}
		}
		Assert.assertTrue("fixture must expose the same source as native CP/LOUT", localValue >= 0);
		local.set(upload.sourcePosition(), localValue);
		return local;
	}

	private static List<Integer> zeroAssignment(ExactPhysicalModel model) {
		return new ArrayList<>(java.util.Collections.nCopies(model.domains().size(), 0));
	}

	private static ExactPhysicalModel.DecisionDomain domain(ExactPhysicalModel model,
		CompiledHopKey key) {
		return model.domains().stream().filter(candidate -> candidate.node().key() == key)
			.findFirst().orElseThrow();
	}

	static Fixture fixture() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));",
			"stable=matrix(1,rows=3,cols=1);",
			"updated=stable;",
			"for(i in 1:3) {",
			"  A=X%*%stable;",
			"  B=X%*%updated;",
			"  print(sum(A)+sum(B));",
			"  updated=updated+1;",
			"}") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		return new Fixture(analysis, model,
			ExactPhysicalCostModel.physicalCostSurface(analysis, model));
	}

	record Fixture(PlacementAnalysis analysis, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) { }
	record ForcedUpload(ExactPhysicalModel.DecisionDomain source,
		ExactPhysicalModel.DecisionDomain consumer, List<Integer> assignment,
		String physicalIdentity, int sourcePosition) { }
}
