/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.EnumSet;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalModelOwnerLookupTest {
	@Test
	public void ownerIndexPreservesLegacyAlternativeOrdinalsAndAuthoritySources() throws Exception {
		EnumSet<AuthorityKind> observedAuthorities = EnumSet.noneOf(AuthorityKind.class);
		for(String fixture : List.of("B-11", "B-21", "B-22")) {
			PlacementAnalysis analysis = analysis(fixture);
			ExactPhysicalModel indexed = ExactPhysicalModel.build(analysis);
			ExactPhysicalModel legacy = ExactPhysicalModel.buildWithLegacyCandidateRuleScanForTest(analysis);

			Assert.assertEquals(fixture, legacy.domains().size(), indexed.domains().size());
			for(int domainOrdinal = 0; domainOrdinal < indexed.domains().size(); domainOrdinal++) {
				var indexedDomain = indexed.domains().get(domainOrdinal);
				var legacyDomain = legacy.domains().get(domainOrdinal);
				Assert.assertSame(fixture + "|domain=" + domainOrdinal,
					legacyDomain.node(), indexedDomain.node());
				Assert.assertEquals(fixture + "|domain=" + domainOrdinal,
					legacyDomain.alternatives().size(), indexedDomain.alternatives().size());
				for(int alternativeOrdinal = 0;
					alternativeOrdinal < indexedDomain.alternatives().size(); alternativeOrdinal++) {
					Alternative expected = legacyDomain.alternatives().get(alternativeOrdinal);
					Alternative actual = indexedDomain.alternatives().get(alternativeOrdinal);
					String ordinal = fixture + "|domain=" + domainOrdinal
						+ "|alternative=" + alternativeOrdinal;
					Assert.assertEquals(ordinal, expected.signature(), actual.signature());
					Assert.assertSame(ordinal, expected.authorityKind(), actual.authorityKind());
					Assert.assertSame(ordinal, expected.state(), actual.state());
					Assert.assertSame(ordinal, expected.candidateRule(), actual.candidateRule());
					Assert.assertSame(ordinal, expected.candidateEmission(), actual.candidateEmission());
					Assert.assertSame(ordinal, expected.executionRule(), actual.executionRule());
					Assert.assertSame(ordinal, expected.executionEmission(), actual.executionEmission());
					Assert.assertSame(ordinal, expected.durableAnchor(), actual.durableAnchor());
					Assert.assertSame(ordinal, expected.relocationAction(), actual.relocationAction());
					Assert.assertSame(ordinal, expected.derivedFoutAction(), actual.derivedFoutAction());
					Assert.assertEquals(ordinal, expected.orderedInputs(), actual.orderedInputs());
					Assert.assertEquals(ordinal, expected.inputAuthorities(), actual.inputAuthorities());
					Assert.assertSame(ordinal, expected.realization(), actual.realization());
					Assert.assertSame(ordinal, expected.supportClause(), actual.supportClause());
					observedAuthorities.add(actual.authorityKind());
				}
			}
		}
		Assert.assertTrue("fixture must cover captured candidate ownership: " + observedAuthorities,
			observedAuthorities.contains(AuthorityKind.CAPTURED_RULE));
		Assert.assertTrue("fixture must cover synthetic function-boundary ownership: " + observedAuthorities,
			observedAuthorities.contains(AuthorityKind.SYNTHETIC_BOUNDARY));
	}

	@Test
	public void ownerIndexExaminesOnlyFactsOwnedByEachDecision() throws Exception {
		PlacementAnalysis analysis = analysis("B-22");
		ExactPhysicalModel indexed = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel legacy = ExactPhysicalModel.buildWithLegacyCandidateRuleScanForTest(analysis);
		long nonSyntheticDecisions = analysis.graph().decisionNodes().stream()
			.filter(node -> node.kind() != NodeKind.FUNCTION_INPUT
				&& node.kind() != NodeKind.FUNCTION_OUTPUT).count();
		long ownedFacts = analysis.graph().decisionNodes().stream()
			.filter(node -> node.kind() != NodeKind.FUNCTION_INPUT
				&& node.kind() != NodeKind.FUNCTION_OUTPUT)
			.mapToLong(node -> analysis.candidateRuleFacts()
				.orderedFactsForParent(node.key()).size()).sum();
		long legacyFacts = Math.multiplyExact(nonSyntheticDecisions,
			analysis.candidateRuleFacts().orderedFacts().size());

		Assert.assertEquals(nonSyntheticDecisions,
			indexed.candidateRuleLookupStatistics().ownerLookups());
		Assert.assertEquals(ownedFacts,
			indexed.candidateRuleLookupStatistics().candidateFactsExamined());
		Assert.assertEquals(legacyFacts,
			legacy.candidateRuleLookupStatistics().candidateFactsExamined());
		Assert.assertTrue("fixture must demonstrate removal of the per-decision whole-facts scan",
			indexed.candidateRuleLookupStatistics().candidateFactsExamined()
				< legacy.candidateRuleLookupStatistics().candidateFactsExamined());
	}

	private static PlacementAnalysis analysis(String fixture) throws Exception {
		return new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile(fixture));
	}
}
