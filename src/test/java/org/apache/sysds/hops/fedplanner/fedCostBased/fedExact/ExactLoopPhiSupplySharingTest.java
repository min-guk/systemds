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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Production cost-surface regression for one loop PHI value reused by an inner loop. */
public class ExactLoopPhiSupplySharingTest {
	private static final int OUTER_ITERATIONS = 3;
	private static final int USES_PER_VERSION = 3;

	@Test
	public void loopPhiChargesOneSupplyPerValueEpochInsteadOfPerInnerUse() throws Exception {
		Fixture fixture = fixture(false);
		ForcedUpload invariant = forcedUpload(fixture, "stable", FederatedOutput.LOUT);
		ForcedUpload carried = forcedUpload(fixture, "epoch", FederatedOutput.LOUT);
		ForcedUpload invariantFout = forcedUpload(fixture, "stable", FederatedOutput.FOUT);
		ForcedUpload carriedFout = forcedUpload(fixture, "epoch", FederatedOutput.FOUT);

		Assert.assertEquals(OUTER_ITERATIONS * USES_PER_VERSION,
			fixture.analysis().executionFrequencyFacts().exactExecutionWeight(carried.source().node().key()), 0d);
		CompiledHopKey carrier = fixture.analysis().logicalTransientInputsInCanonicalOrder().stream()
			.filter(input -> input.sourceValueVersion().versionKind() == VersionKind.LOOP_BACKEDGE)
			.filter(input -> "carried".equals(input.readValueVersion().lexicalVariable()))
			.map(PlacementAnalysis.LogicalTransientInputFact::targetRead).findFirst().orElseThrow();
		Assert.assertTrue("fixture must contain entry and backedge reaching definitions",
			fixture.analysis().logicalTransientInputsInCanonicalOrder().stream()
				.filter(input -> input.targetRead() == carrier).count() > 1);

		Assert.assertTrue("inner uses of one invariant value require a retained supply",
			fixture.surface().selectedSharedSupplyLifetimes(invariant.assignment())
				.contains(invariant.physicalIdentity()));
		Assert.assertTrue("each loop-carried value must be retained across its inner uses",
			fixture.surface().selectedSharedSupplyLifetimes(carried.assignment())
				.contains(carried.physicalIdentity()));
		Assert.assertTrue("each staged FOUT value must retain its REFED copy across inner uses",
			fixture.surface().selectedSharedSupplyLifetimes(carriedFout.assignment())
				.contains(carriedFout.physicalIdentity()));
		assertDirectOwner(fixture, carriedFout);

		double unit = movementCost(fixture.surface(), invariant);
		double phi = movementCost(fixture.surface(), carried);
		Assert.assertTrue(Double.isFinite(unit) && unit > 0d);
		Assert.assertEquals("entry plus consumed backedges form exactly one value epoch per outer iteration",
			OUTER_ITERATIONS * unit, phi, Math.max(1e-12, unit * 1e-12));
		double foutUnit = movementCost(fixture.surface(), invariantFout);
		double foutPhi = movementCost(fixture.surface(), carriedFout);
		Assert.assertEquals("staged FOUT upload executes once per outer value epoch",
			OUTER_ITERATIONS * foutUnit, foutPhi, Math.max(1e-12, foutUnit * 1e-12));
		double collectionUnit = stagedCollectionCost(fixture.surface(), invariantFout);
		double collectionPhi = stagedCollectionCost(fixture.surface(), carriedFout);
		Assert.assertEquals("staged FOUT collection executes once per outer value epoch",
			OUTER_ITERATIONS * collectionUnit, collectionPhi,
			Math.max(1e-12, collectionUnit * 1e-12));
	}

	@Test
	public void semanticUpdateInsideInnerLoopDoesNotReusePhiSnapshot() throws Exception {
		Fixture fixture = fixture(true);
		ForcedUpload invariant = forcedUpload(fixture, "stable", FederatedOutput.LOUT);
		ForcedUpload updated = forcedUpload(fixture, "mutated", FederatedOutput.LOUT);
		ForcedUpload invariantFout = forcedUpload(fixture, "stable", FederatedOutput.FOUT);
		ForcedUpload updatedFout = forcedUpload(fixture, "mutated", FederatedOutput.FOUT);

		Assert.assertFalse("each semantic update must own a distinct REFED copy",
			fixture.surface().selectedSharedSupplyLifetimes(updated.assignment())
				.contains(updated.physicalIdentity()));
		Assert.assertFalse("updated FOUT values must not acquire a retained lifetime",
			fixture.surface().selectedSharedSupplyLifetimes(updatedFout.assignment())
				.contains(updatedFout.physicalIdentity()));
		double unit = movementCost(fixture.surface(), invariant);
		Assert.assertEquals(OUTER_ITERATIONS * USES_PER_VERSION * unit,
			movementCost(fixture.surface(), updated), Math.max(1e-12, unit * 1e-12));
		double foutUnit = movementCost(fixture.surface(), invariantFout);
		Assert.assertEquals("mutated FOUT values must upload once per semantic version",
			OUTER_ITERATIONS * USES_PER_VERSION * foutUnit,
			movementCost(fixture.surface(), updatedFout), Math.max(1e-12, foutUnit * 1e-12));
		double collectionUnit = stagedCollectionCost(fixture.surface(), invariantFout);
		Assert.assertEquals("mutated FOUT values must collect once per semantic version",
			OUTER_ITERATIONS * USES_PER_VERSION * collectionUnit,
			stagedCollectionCost(fixture.surface(), updatedFout),
			Math.max(1e-12, collectionUnit * 1e-12));
	}

	private static void assertDirectOwner(Fixture fixture, ForcedUpload upload) {
		int position = fixture.model().domains().indexOf(upload.source());
		var selected = upload.source().alternatives().get(upload.assignment().get(position));
		Assert.assertNull(selected.relocationAction());
		Assert.assertNull(selected.derivedFoutAction());
		Assert.assertFalse(selected.inputAuthorities().stream().anyMatch(authority ->
			authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION));
	}



	private static ForcedUpload forcedUpload(Fixture fixture, String variable,
		FederatedOutput sourceOutput) {
		CompiledHopKey read = "mutated".equals(variable)
			? fixture.analysis().compiledInputEdgesInCanonicalOrder().stream()
				.filter(candidate -> candidate.inputPosition() == 1
					&& fixture.analysis().hop(candidate.producer()).orElseThrow() instanceof BinaryOp
					&& "ba(+*)".equals(fixture.analysis().hop(candidate.consumer()).orElseThrow().getOpString()))
				.map(PlacementAnalysis.CompiledInputEdgeFact::producer).findFirst().orElseThrow()
			: fixture.analysis().compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD
				&& variable.equals(data.getName()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> fixture.analysis().executionFrequencyFacts().exactExecutionWeight(key)
				== OUTER_ITERATIONS * USES_PER_VERSION)
			.filter(key -> fixture.analysis().compiledInputEdgesInCanonicalOrder().stream()
				.anyMatch(candidate -> candidate.producer() == key
					&& "ba(+*)".equals(fixture.analysis().hop(candidate.consumer()).orElseThrow().getOpString())))
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
		Assert.assertTrue("CP/LOUT source alternative missing for " + variable, sourceValue >= 0);
		int consumerValue = -1;
		String physicalIdentity = null;
		for(int value = 0; value < consumer.alternatives().size(); value++) {
			var authority = consumer.alternatives().get(value).inputAuthorities().stream()
				.filter(input -> input.inputPosition() == edge.inputPosition()
					&& input.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
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
		List<Integer> assignment = new ArrayList<>(Collections.nCopies(fixture.model().domains().size(), 0));
		int sourcePosition = fixture.model().domains().indexOf(source);
		assignment.set(sourcePosition, sourceValue);
		assignment.set(fixture.model().domains().indexOf(consumer), consumerValue);
		return new ForcedUpload(source, consumer, assignment, physicalIdentity);
	}

	private static double movementCost(ExactPhysicalCostModel.PhysicalCostSurface surface,
		ForcedUpload upload) {
		Assert.assertEquals(1, surface.transferKeys().stream()
			.filter(key -> key.direction() == ExactPhysicalCostModel.Direction.UPLOAD
				&& key.physicalEmissionIdentity().equals(upload.physicalIdentity())
				&& key.endpoints().stream().anyMatch(endpoint ->
					endpoint.producer() == upload.source().node().key()
						&& endpoint.consumer() == upload.consumer().node().key()))
			.count());
		List<Integer> local = new ArrayList<>(upload.assignment());
		int inactive = -1;
		for(int value = 0; value < upload.consumer().alternatives().size(); value++)
			if(upload.consumer().alternatives().get(value).inputAuthorities().stream().noneMatch(authority ->
				authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
					&& RelocationSelections.physicalEmissionIdentity(authority.relocationAction().key())
						.equals(upload.physicalIdentity()))) {
				inactive = value;
				break;
			}
		Assert.assertTrue(inactive >= 0);
		local.set(surface.variables().indexOf(upload.consumer().variable()), inactive);
		var positive = surface.contributions().stream()
			.map(contribution -> new java.util.AbstractMap.SimpleImmutableEntry<>(contribution,
				surface.evaluateContributionCanonical(contribution, upload.assignment())))
			.filter(entry -> entry.getKey().factor().scope().contains(upload.source().variable())
				&& entry.getKey().factor().scope().contains(upload.consumer().variable())
				&& entry.getValue() > 0d
				&& surface.evaluateContributionCanonical(entry.getKey(), local) == 0d).toList();
		Assert.assertEquals("one contribution must own the selected b_e upload", 1, positive.size());
		return positive.get(0).getValue();
	}

	private static double stagedCollectionCost(ExactPhysicalCostModel.PhysicalCostSurface surface,
		ForcedUpload upload) {
		List<Integer> local = new ArrayList<>(upload.assignment());
		int localSource = -1;
		for(int value = 0; value < upload.source().alternatives().size(); value++) {
			var state = upload.source().alternatives().get(value).state();
			if(state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT) {
				localSource = value;
				break;
			}
		}
		Assert.assertTrue(localSource >= 0);
		local.set(surface.variables().indexOf(upload.source().variable()), localSource);
		var collection = surface.contributions().stream()
			.map(contribution -> new java.util.AbstractMap.SimpleImmutableEntry<>(contribution,
				surface.evaluateContributionCanonical(contribution, upload.assignment())))
			.filter(entry -> entry.getKey().factor().scope().contains(upload.source().variable())
				&& entry.getKey().factor().scope().contains(upload.consumer().variable())
				&& entry.getValue() > 0d
				&& surface.evaluateContributionCanonical(entry.getKey(), local) == 0d).toList();
		Assert.assertEquals("one source collection must precede staged upload: " + collection.stream()
			.map(entry -> entry.getKey().id() + '=' + entry.getValue()).toList(), 1, collection.size());
		return collection.get(0).getValue();
	}

	private static ExactPhysicalModel.DecisionDomain domain(ExactPhysicalModel model,
		CompiledHopKey key) {
		return model.domains().stream().filter(candidate -> candidate.node().key() == key)
			.findFirst().orElseThrow();
	}

	private static Fixture fixture(boolean innerMutation) throws Exception {
		List<String> lines = new ArrayList<>(List.of(
			"LA=federated(addresses=list(\"localhost:1234/LA\"),ranges=list(list(0,0),list(8,3)));",
			"LB=federated(addresses=list(\"localhost:1235/LB\"),ranges=list(list(0,0),list(8,3)));",
			"stable=matrix(1,rows=3,cols=1);",
			"carried=matrix(1,rows=3,cols=1);",
			"for(i in 1:3) {",
			"  epoch=carried;",
			"  for(j in 1:3) {",
			"    LA=LA+j;",
			"    LB=LB+j;"));
		if(innerMutation)
			lines.addAll(List.of("    epoch=epoch+1;", "    mutated=epoch;"));
		lines.addAll(List.of(
			"    A=LA%*%stable;",
			innerMutation ? "    B=LB%*%mutated;" : "    B=LB%*%epoch;",
			"    print(sum(A)+sum(B));",
			"  }",
			"  carried=carried+1;",
			"}"));
		String script = String.join("\n", lines) + "\n";
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

	private record Fixture(PlacementAnalysis analysis, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) { }
	private record ForcedUpload(ExactPhysicalModel.DecisionDomain source,
		ExactPhysicalModel.DecisionDomain consumer, List<Integer> assignment,
		String physicalIdentity) { }
}
