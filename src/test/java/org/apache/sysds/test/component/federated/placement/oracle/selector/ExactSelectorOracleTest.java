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

package org.apache.sysds.test.component.federated.placement.oracle.selector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle.Certificate;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle.Policy;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle.Result;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle.Score;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle.TerminationReason;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Choice;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Node;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Output;
import org.junit.Test;

public class ExactSelectorOracleTest {
	@Test
	public void s01IndependentHopsMatchExhaustiveOracle() {
		Result result = assertMatchesIndependentEnumeration(SelectorOracleFixtures.independentHops(), Policy.FED_ALL);
		assertEquals(2, result.getScore().getFedCount());
		assertEquals(2, result.getScore().getFoutCount());
	}

	@Test
	public void s02ParentChildConflictSelectsExactLegalWinner() {
		ExplicitSelectorGraph graph = SelectorOracleFixtures.parentChildFTypeConflict();
		Result result = assertMatchesIndependentEnumeration(graph, Policy.FED_ALL);
		assertTrue(graph.isLegal(result.getAssignment()));
		assertEquals(1, result.getScore().getFedCount());
	}

	@Test
	public void s03SharedChildIsCountedOnce() {
		Result result = assertMatchesIndependentEnumeration(SelectorOracleFixtures.sharedDiamond(), Policy.FED_ALL);
		assertEquals(3, result.getScore().getFedCount());
		assertEquals(3, result.getScore().getFoutCount());
		assertEquals(2, result.getCertificate().getGraphEdgeCount());
	}

	@Test
	public void s04SharedRelocationEnablesBothDownstreamFedNodesOnce() {
		Result result = assertMatchesIndependentEnumeration(SelectorOracleFixtures.sharedRelocation(), Policy.FED_ALL);
		assertEquals(2, result.getScore().getFedCount());
		assertEquals(3, result.getScore().getFoutCount());
		assertEquals(1, result.getScore().getRelocationCount());
		assertEquals("uploaded", result.getAssignment().get("value").getId());
	}

	@Test
	public void s05FedGainDominatesMultipleFoutGains() {
		Result result = assertMatchesIndependentEnumeration(SelectorOracleFixtures.fedBeforeFout(), Policy.FED_ALL);
		assertEquals("fed", result.getAssignment().get("fedGain").getId());
		assertEquals(1, result.getScore().getFedCount());
		assertEquals(0, result.getScore().getFoutCount());
	}

	@Test
	public void s06FewerDistinctRelocationsWinsAfterFedAndFoutTie() {
		Result result = assertMatchesIndependentEnumeration(SelectorOracleFixtures.fewerRelocations(), Policy.FED_ALL);
		assertEquals(2, result.getScore().getFedCount());
		assertEquals(2, result.getScore().getFoutCount());
		assertEquals(1, result.getScore().getRelocationCount());
		assertEquals("shared", result.getAssignment().get("a").getId());
		assertEquals("shared", result.getAssignment().get("b").getId());
	}

	@Test
	public void s07StableSignatureBreaksCompleteTie() {
		Result result = assertMatchesIndependentEnumeration(SelectorOracleFixtures.stableTie(), Policy.FED_ALL);
		assertEquals("alpha", result.getAssignment().get("node").getId());
		assertEquals("node=alpha", result.getScore().getSignature());
	}

	@Test
	public void s08GeneratedBoundedCorpusHasZeroExactnessMismatches() {
		int graphCount = 0;
		for (ExplicitSelectorGraph graph : SelectorOracleFixtures.generatedCorpus()) {
			assertMatchesIndependentEnumeration(graph, Policy.FED_ALL);
			Result heuristic = assertMatchesIndependentEnumeration(graph, Policy.HEURISTIC);
			for (Node node : graph.getNodes())
				assertTrue("heuristic constraint must be applied before scoring for " + node.getId(),
					node.isHeuristicAllowed(heuristic.getAssignment().get(node.getId())));
			graphCount++;
		}
		assertEquals(15, graphCount);
	}

	@Test
	public void exactnessCertificateIsCompleteAndCannotReportFallbackSuccess() {
		ExplicitSelectorGraph graph = SelectorOracleFixtures.sharedRelocation();
		Certificate certificate = ExactSelectorOracle.select(graph, Policy.FED_ALL).getCertificate();
		assertEquals(certificate.getIncumbentScore(), certificate.getFinalUpperBound());
		assertTrue(certificate.getExploredCount() > 0);
		assertTrue(certificate.getPrunedCount() > 0);
		assertEquals(64, certificate.getAssignmentHash().length());
		assertEquals(graph.getNodes().size(), certificate.getGraphNodeCount());
		assertEquals(graph.getEdgeCount(), certificate.getGraphEdgeCount());
		assertEquals(graph.getComponentCount(), certificate.getComponentCount());
		assertFalse(certificate.getBoundDerivation().isBlank());
		assertEquals(graph.getSizeClass(), certificate.getGeneratorSizeClass());
		assertEquals(graph.getSeed(), certificate.getSeed());
		assertEquals(TerminationReason.EXHAUSTED, certificate.getTerminationReason());
	}

	private static Result assertMatchesIndependentEnumeration(ExplicitSelectorGraph graph, Policy policy) {
		Result actual = ExactSelectorOracle.select(graph, policy);
		Score independentBest = enumerateIndependently(graph, policy, 0, new LinkedHashMap<>(), null);
		assertNotNull("fixture must have a legal assignment", independentBest);
		assertEquals(independentBest, actual.getScore());
		assertEquals(actual.getScore(), actual.getCertificate().getFinalUpperBound());
		assertEquals(TerminationReason.EXHAUSTED, actual.getCertificate().getTerminationReason());
		return actual;
	}

	/**
	 * Intentionally separate cartesian reference loop: it does not use the oracle's pruning or
	 * incumbent update path, so generated-corpus comparisons can detect selector mistakes.
	 */
	private static Score enumerateIndependently(ExplicitSelectorGraph graph, Policy policy, int nodeIndex,
		LinkedHashMap<String, Choice> assignment, Score best) {
		if (nodeIndex == graph.getNodes().size()) {
			if (!graph.isLegal(assignment))
				return best;
			Score candidate = independentScore(assignment);
			return best == null || candidate.compareTo(best) > 0 ? candidate : best;
		}
		Node node = graph.getNodes().get(nodeIndex);
		for (Choice choice : node.getChoices()) {
			if (policy == Policy.HEURISTIC && !node.isHeuristicAllowed(choice))
				continue;
			assignment.put(node.getId(), choice);
			best = enumerateIndependently(graph, policy, nodeIndex + 1, assignment, best);
			assignment.remove(node.getId());
		}
		return best;
	}

	private static Score independentScore(LinkedHashMap<String, Choice> assignment) {
		// Score is a public value constructor only through the oracle's pure scoring function.  The
		// reference loop remains independent from selection, bounds, pruning, and termination logic.
		return ExactSelectorOracle.score(assignment);
	}
}
