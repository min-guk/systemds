/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class CandidateRuleFactsRealizationIndexTest {
	@Test
	public void exactReferenceReturnsTheAnalysisOwnedRealization() {
		Fixture fixture = fixture();
		Assert.assertSame(fixture.realization,
			fixture.facts.requireExactRealization(
				CandidateRealizationReference.of(fixture.rule, fixture.realization)));
	}

	@Test
	public void foreignUnavailableAndMissingRealizationKeepDistinctDiagnostics() {
		Fixture fixture = fixture();
		CandidateEmissionRealization missing = CandidateEmissionRealization.local(
			new PlacementEmissionState(
				new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, true), false));
		IllegalArgumentException missingFailure = Assert.assertThrows(IllegalArgumentException.class,
			() -> fixture.facts.requireExactRealization(
				CandidateRealizationReference.of(fixture.rule, missing)));
		Assert.assertTrue(missingFailure.getMessage().startsWith(
			"Transient compatibility realization is missing or ambiguous:"));

		CandidateRuleKey foreign = new CandidateRuleKey(key("foreign"), List.of());
		Assert.assertThrows(PlacementAnalysis.CandidateRuleLookupException.class,
			() -> fixture.facts.requireExactRealization(
				CandidateRealizationReference.of(foreign, fixture.realization)));

		CandidateRuleFact unavailable = new CandidateRuleFact(fixture.rule,
			CandidateEvaluationStatus.PRIVACY_EXCLUDED, fixture.facts.orderedFacts().get(0).capability(),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(), "PRIVATE");
		CandidateRuleFacts unavailableFacts = new CandidateRuleFacts(
			new CandidateRuleDomain("unavailable-index", List.of(fixture.rule), List.of()),
			List.of(unavailable));
		IllegalArgumentException unavailableFailure = Assert.assertThrows(IllegalArgumentException.class,
			() -> unavailableFacts.requireExactRealization(
				CandidateRealizationReference.of(fixture.rule, fixture.realization)));
		Assert.assertEquals("Transient compatibility references an unavailable candidate row",
			unavailableFailure.getMessage());
	}

	@Test
	public void duplicateRealizationKeyKeepsItsExactAmbiguousMatchCount() {
		CompiledHopKey owner = key("ambiguous-owner");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.ROW)));
		PlacementEmissionState emissionState = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		DurableAnchorKey anchor = new DurableAnchorKey("ambiguous-map", FType.ROW,
			List.of(new AnchorPartition("localhost:13000", List.of(0L, 0L), List.of(4L, 2L))));
		CandidateEmissionRealization realization = CandidateEmissionRealization.durable(emissionState, anchor,
			List.of(new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, owner, "ambiguous-map")), List.of());
		CandidateEmissionFact row = new CandidateEmissionFact(
			emissionState, FType.ROW, null, List.of(realization));
		CandidateEmissionFact col = new CandidateEmissionFact(
			emissionState, FType.COL, null, List.of(realization));
		CandidateRuleFact fact = new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(row, col), "");
		CandidateRuleFacts facts = new CandidateRuleFacts(
			new CandidateRuleDomain("ambiguous-index", List.of(rule), List.of()), List.of(fact));

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> facts.requireExactRealization(CandidateRealizationReference.of(rule, realization)));
		Assert.assertTrue(failure.getMessage().contains("matching=2"));
	}

	private static Fixture fixture() {
		CompiledHopKey owner = key("owner");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(CandidateInputState.absentLocal()));
		PlacementState state = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(
			new PlacementEmissionState(state, false));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			new PlacementEmissionState(state, false), null, null, List.of(realization));
		CandidateRuleFact fact = new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
		CandidateRuleDomain domain = new CandidateRuleDomain("realization-index", List.of(rule), List.of());
		return new Fixture(rule, realization, new CandidateRuleFacts(domain, List.of(fact)));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"realization-index", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("realization-index", "main", name, "compiled", region,
			name, name);
	}

	private record Fixture(CandidateRuleKey rule, CandidateEmissionRealization realization,
		CandidateRuleFacts facts) { }
}
