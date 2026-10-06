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
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact a_v/b_e projection and relation-witness coverage. */
public class ExactPhysicalNativeSupplyRepresentationTest {
	@Test
	public void everyLegacyAuthorityRowRoundTripsThroughExactNativeSupplyRelation()
		throws Exception {
		ExactPhysicalModel model = model();
		ExactPhysicalNativeSupplyRepresentation representation = model.nativeSupplyRepresentation();
		int originalRows = 0;
		for(var domain : representation.domains()) {
			originalRows += domain.authority().alternatives().size();
			Assert.assertEquals(domain.authority().alternatives().size(), domain.relation().size());
			for(var witness : domain.relation()) {
				List<Integer> fiber = domain.reconstruct(witness.nativeOrdinal(),
					witness.supplyOrdinals());
				Assert.assertTrue("Every graph-owned legal row needs an exact a_v/b_e witness",
					fiber.contains(witness.originalOrdinal()));

				List<Integer> illegal = new ArrayList<>(witness.supplyOrdinals());
				illegal.add(Integer.MAX_VALUE);
				Assert.assertTrue("Uncertified native/supply cross-products must remain illegal",
					domain.reconstruct(witness.nativeOrdinal(), illegal).isEmpty());
			}
		}
		Assert.assertEquals(originalRows, representation.statistics().originalRows());
		Assert.assertEquals(originalRows, representation.statistics().relationWitnesses());
		Assert.assertTrue(representation.statistics().nativeCandidates() > 0);
		Assert.assertTrue(representation.statistics().supplyCandidates() > 0);
	}

	@Test
	public void postOperationOutputMovementChangesSupplyButNotNativeExecution()
		throws Exception {
		ExactPhysicalModel model = model();
		ExactPhysicalNativeSupplyRepresentation representation = model.nativeSupplyRepresentation();
		boolean compared = false;
		for(var domain : representation.domains())
			for(var moved : domain.relation()) {
				var movedAlternative = domain.authority().alternatives().get(moved.originalOrdinal());
				if(movedAlternative.derivedFoutAction() == null)
					continue;
				var nativeCandidate = domain.nativeCandidates().get(moved.nativeOrdinal());
				var emission = movedAlternative.captured()
					? movedAlternative.candidateEmission() : movedAlternative.executionEmission();
				Assert.assertNotNull(emission);
				Assert.assertEquals("a_v records the selected execution, not every execution type "
					+ "permitted by the candidate capability",
					emission.emissionState().placementState().execType(),
					nativeCandidate.executionType());
				Assert.assertEquals(movedAlternative.derivedFoutAction().key().sourcePlacement(),
					nativeCandidate.nativeOutputState());
				var outputSupply = domain.supplies(moved.originalOrdinal()).stream()
					.filter(supply -> supply.direction()
						== ExactPhysicalNativeSupplyRepresentation.SupplyDirection.OUTPUT)
					.findFirst().orElseThrow();
				Assert.assertEquals(
					ExactPhysicalNativeSupplyRepresentation.SupplyActionKind.OUTPUT_MATERIALIZATION,
					outputSupply.actionKind());
				Assert.assertSame(movedAlternative.derivedFoutAction(), outputSupply.action());
				Assert.assertEquals(movedAlternative.state(), outputSupply.targetState());

				for(var peer : domain.relation()) {
					if(peer.originalOrdinal() == moved.originalOrdinal()
						|| peer.nativeOrdinal() != moved.nativeOrdinal())
						continue;
					Assert.assertFalse("Different post-operation supply choices remain b_e choices",
						moved.supplyOrdinals().equals(peer.supplyOrdinals()));
					compared = true;
					break;
				}
			}
		Assert.assertTrue("Fixture must contain one native execution with moved and unmoved output",
			compared);
	}

	@Test
	public void sourceVersionAndActionRemainPartOfSupplyIdentity() throws Exception {
		ExactPhysicalNativeSupplyRepresentation representation =
			model().nativeSupplyRepresentation();
		for(var domain : representation.domains())
			for(var supply : domain.supplyCandidates()) {
				Assert.assertFalse("retention must not be represented as an action/search choice",
					supply.actionKind().name().contains("RETAIN"));
				if(supply.source() != null && supply.source().sourceDecision() != null)
					Assert.assertNotNull("Source decisions carry exact value versions",
						supply.source().valueVersion());
			}
	}

	@Test
	public void dynamicNativeResidencyWitnessDoesNotBecomeDurableLayout() throws Exception {
		String script = "A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "R=rev(A);U=exp(R);print(sum(U));";
		ExactPhysicalNativeSupplyRepresentation representation =
			model(script, Privacy.PRIVATE_AGGREGATE).nativeSupplyRepresentation();
		int dynamicRows = 0;
		for(var domain : representation.domains())
			for(var witness : domain.relation()) {
				var alternative = domain.authority().alternatives().get(witness.originalOrdinal());
				var realization = alternative.realization();
				var clause = alternative.supportClause();
				if(realization == null || clause == null
					|| realization.key().layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
					|| clause.nativeWorkerPoolWitness() == null
					|| clause.nativeWorkerPoolLayoutExact())
					continue;
				var nativeLayout = domain.nativeCandidate(witness.originalOrdinal()).nativeOutputLayout();
				Assert.assertEquals(PlacementLayoutKind.NATIVE_LINEAGE, nativeLayout.kind());
				Assert.assertEquals(realization.key().nativeLineage(), nativeLayout.lineage());
				Assert.assertEquals("Endpoint residency remains evidence, not a durable exact map",
					clause.nativeWorkerPoolWitness(), nativeLayout.anchor());
				Assert.assertFalse(nativeLayout.exactWorkerPool());
				dynamicRows++;
			}
		Assert.assertTrue("Fixture must contain dynamic native-lineage authority", dynamicRows > 0);
	}

	private static ExactPhysicalModel model() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));"
			+ "p=matrix(1,rows=3,cols=1);pred=X%*%p;print(sum(pred));";
		return model(script, Privacy.PUBLIC);
	}

	private static ExactPhysicalModel model(String script, Privacy privacy) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		return ExactPhysicalModel.build(analysis);
	}
}
