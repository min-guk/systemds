/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED: MinST derivation must bind exact candidate-rule authority evidence, not only its key. */
public class CampaignBG014MinStCandidateRuleFactAuthorityFingerprintRedTest {
	@Test
	public void mutatedCandidateRuleCapabilityDetailFailsClosedOrChangesMinStDerivationFingerprint()
		throws Exception {
		DMLProgram program = compileHermeticB11();
		PlacementAnalysis original = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		CandidateRuleFact mutatedFact = firstAvailableRuleWithCapability(original);
		PlacementAnalysis mutated = reconstructWithOneMutatedRuleFact(original, program, mutatedFact);

		MinStExactCostFacts originalFacts = MinStExactCostFactsProducer.derive(original, scope(original));
		try {
			MinStExactCostFacts mutatedFacts = MinStExactCostFactsProducer.derive(mutated, scope(mutated));
			Assert.assertNotEquals(
				"MINST_EXACT_DERIVATION_MUST_BIND_CANDIDATE_RULE_CAPABILITY_DETAIL_AUTHORITY",
				originalFacts.derivationFingerprint(), mutatedFacts.derivationFingerprint());
		}
		catch(RuntimeException failClosed) {
			Assert.assertTrue("mutated authority-bearing fact failed closed", true);
		}
	}

	private static PlacementAnalysis reconstructWithOneMutatedRuleFact(PlacementAnalysis source,
		DMLProgram program, CandidateRuleFact target) {
		List<CandidateRuleFact> facts = new ArrayList<>(source.candidateRuleFacts().orderedFacts().size());
		for(CandidateRuleFact fact : source.candidateRuleFacts().orderedFacts())
			facts.add(fact == target ? withMutatedCapabilityDetail(fact) : fact);

		return new PlacementAnalysis(source.graph(), source.occurrences(), source.topLevelStatementBlocks(), program,
			copyShapeFacts(source), source.analysisFingerprint(), source.heuristicPolicyFacts(),
			source.candidateRuleDomain().orderedRuleKeys(), facts,
			source.candidateRuleDomain().orderedConsumerKeys(),
			source.candidateConsumerProfileFacts().orderedFacts(),
			source.detachedConsumerProfileFacts().orderedFacts(),
			source.compiledInputEdgesInCanonicalOrder());
	}

	private static CandidateRuleFact withMutatedCapabilityDetail(CandidateRuleFact fact) {
		CandidateCapabilityFact capability = fact.capability();
		CandidateCapabilityFact mutatedCapability = new CandidateCapabilityFact(capability.category(),
			capability.opcode(), capability.nativeExec(), capability.nativeOutput(), capability.nativeFoutFType(),
			capability.reasonCode(), capability.detail() + "|authority-mutated", capability.notes());
		return new CandidateRuleFact(fact.key(), fact.status(), mutatedCapability, fact.shapeProof(),
			fact.profile(), fact.failureCode());
	}

	private static CandidateRuleFact firstAvailableRuleWithCapability(PlacementAnalysis analysis) {
		return analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.capability() != null)
			.findFirst().orElseThrow(() -> new AssertionError("B-11 must publish an available candidate rule fact"));
	}

	private static PlacementShapeFacts copyShapeFacts(PlacementAnalysis analysis) {
		Map<CompiledHopKey,NodeShapeFact> facts = new LinkedHashMap<>();
		Set<CompiledHopKey> expected = new LinkedHashSet<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			expected.add(occurrence.key());
			facts.put(occurrence.key(), analysis.shapeFact(occurrence.key()).orElseThrow(() ->
				new AssertionError("shape fact missing for " + occurrence.key())));
		}
		return new PlacementShapeFacts(facts, expected);
	}

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream().map(HopOccurrenceProjection::key).toList();
	}

	private static DMLProgram compileHermeticB11() throws Exception {
		String script = localFederatedRow("X") + "Y=X+1;\nprint(sum(Y));\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static String localFederatedRow(String variable) {
		return variable + "_LOCAL=matrix(0,4,2);\n" + variable
			+ "=federated(local_matrix=" + variable + "_LOCAL,"
			+ "addresses=list(\"localhost:1234\",\"localhost:1235\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";
	}
}
