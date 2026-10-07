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
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class PlacementRealizationLookupIndexTest {
	@Test
	public void lookupKeepsOwnedResultsAndRejectsForeignOwnersAndMissingRealizations() throws Exception {
		var program = ProductionShadowFixtureFactory.compile("B-11");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		int checked = 0;
		boolean checkedMissing = false;
		for(var rule : analysis.candidateRuleFacts().orderedFacts()) {
			if(rule.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(var emission : rule.allowedEmissionFacts())
				for(var realization : emission.realizations()) {
					var reference = CandidateRealizationReference.of(rule.key(), realization);
					for(int repeat = 0; repeat < 3; repeat++)
						Assert.assertSame(realization, analysis.requireExactCandidateRealization(reference));
					var key = realization.key();
					var equalKey = new PlacementRealizationKey(key.emissionState(), key.layoutKind(),
						key.durableAnchor(), key.nativeLineage());
					Assert.assertSame(realization, analysis.requireExactCandidateRealization(
						new CandidateRealizationReference(new CandidateRuleKey(rule.key().parentOccurrence(),
							rule.key().orderedInputs()), equalKey)));
					CompiledHopKey owner = rule.key().parentOccurrence();
					CompiledHopKey foreign = new CompiledHopKey(owner.programFingerprint(), owner.functionNamespace(),
						owner.callSitePath(), owner.recompileContext(), owner.controlRegion(),
						owner.emittedHopInstance(), owner.canonicalSourceOrigin());
					Assert.assertEquals(owner, foreign);
					var foreignReference = new CandidateRealizationReference(
						new CandidateRuleKey(foreign, rule.key().orderedInputs()), key);
					Assert.assertThrows(IllegalArgumentException.class,
						() -> analysis.requireExactCandidateRealization(foreignReference));
					if(key.emissionState().placementState().output() == FederatedOutput.FOUT) {
						var missing = new CandidateRealizationReference(rule.key(),
							PlacementRealizationKey.nativeLineage(key.emissionState(), "missing-test-lineage"));
						Assert.assertThrows(IllegalArgumentException.class,
							() -> analysis.requireExactCandidateRealization(missing));
						checkedMissing = true;
					}
					checked++;
				}
		}
		Assert.assertTrue("all published owned realizations were checked", checked > 0);
		Assert.assertTrue("fixture retains protected federated realizations", checkedMissing);
	}
}
