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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** B13 locks exact OTHER matrix-scalar candidate capture without durable anchor promotion. */
public class CampaignBG014B13OtherMatrixScalarRuleFactRedTest {
	@Test
	public void b13OtherMatrixScalarPublishesFedFoutOtherRuleFactAndLegalState() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-13");
		String before = PlacementGraphFingerprint.capture(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		HopOccurrenceProjection y = soleBinaryPlusOccurrence(analysis);
		Node yNode = analysis.graph().node(y.key()).orElseThrow(AssertionError::new);

		PlacementState expected = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.OTHER, true);
		Assert.assertTrue("B13 Y must expose runtime-supported OTHER matrix-scalar FOUT candidate: "
			+ yNode.legalAlternatives(), yNode.legalAlternatives().contains(expected));

		CandidateRuleFact fact = analysis.candidateRuleFacts().requireExact(y.key(),
			List.of(CandidateInputState.present(FType.OTHER), CandidateInputState.absentLocal()));
		Assert.assertEquals("B13 OTHER+scalar candidate must be available",
			PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE, fact.status());
		Assert.assertEquals(ExecType.FED, fact.capability().nativeExec());
		Assert.assertEquals(FederatedOutput.FOUT, fact.capability().nativeOutput());
		Assert.assertEquals(FType.OTHER, fact.capability().nativeFoutFType());
		Assert.assertEquals(ReasonCode.OK, fact.capability().reasonCode());
		Assert.assertEquals(List.of(FType.OTHER), fact.profile().producerOutputs());
		Assert.assertTrue("B13 Y OTHER repair must not create durable relocations",
			analysis.graph().relocationActions().stream().noneMatch(action ->
				action.key().sourceValueVersion().equals(yNode.valueVersion())
					|| action.obligations().stream().anyMatch(obligation ->
						obligation.consumer().equals(y.key()))));
		Assert.assertEquals("B13 inspection mutated the compiled graph", before,
			PlacementGraphFingerprint.capture(program));
	}

	private static HopOccurrenceProjection soleBinaryPlusOccurrence(PlacementAnalysis analysis) {
		List<HopOccurrenceProjection> matches = analysis.occurrences().stream()
			.filter(occurrence -> !occurrence.key().canonicalSourceOrigin().startsWith("function-boundary:"))
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp)
			.filter(occurrence -> ((BinaryOp) occurrence.hop()).getOp() == OpOp2.PLUS)
			.toList();
		Assert.assertEquals("expected one B13 binary plus occurrence", 1, matches.size());
		return matches.get(0);
	}
}
