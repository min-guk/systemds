/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for keeping policy-owned materialization quotients out of the shared plan space. */
public class PolicyQuotientIsolationTest {
	private static final Path MAIN = Path.of("src/main/java/org/apache/sysds/hops/fedplanner");

	@Test
	public void policySelectionOrderCannotShrinkSharedReceiptsOrExactDomains() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(compileProtectedFixture());
		List<CandidateSelectionReceipt> sharedReceipts = canonicalReceipts(analysis);
		List<String> sharedReceiptSignatures = receiptSignatures(sharedReceipts);
		List<List<String>> exactDomains = exactDomainSignatures(ExactPhysicalModel.build(analysis));
		String graphSignature = analysis.graph().normalizedSignature();
		String fingerprint = analysis.analysisFingerprint();

		var fedAll = new FedAllPlacementAdapter().select(analysis);
		var heuristicAfterFedAll = new HeuristicPlacementAdapter().select(analysis, Set.of());
		QuotientWitness witness = quotientWitness(analysis, ExactPhysicalModel.build(analysis),
			fedAll.assignment(), fedAll.selectedCandidateSelections());
		assertPolicyElides("FedAll", fedAll.selectedCandidateSelections(), witness);
		assertPolicyElides("Heuristic-after-FedAll",
			heuristicAfterFedAll.selectedCandidateSelections(), witness);
		assertSourceUniverseUnchanged(analysis, sharedReceipts, sharedReceiptSignatures,
			exactDomains, graphSignature, fingerprint, witness);

		var heuristic = new HeuristicPlacementAdapter().select(analysis, Set.of());
		var fedAllAfterHeuristic = new FedAllPlacementAdapter().select(analysis);
		assertPolicyElides("Heuristic", heuristic.selectedCandidateSelections(), witness);
		assertPolicyElides("FedAll-after-Heuristic",
			fedAllAfterHeuristic.selectedCandidateSelections(), witness);
		assertSourceUniverseUnchanged(analysis, sharedReceipts, sharedReceiptSignatures,
			exactDomains, graphSignature, fingerprint, witness);

		Assert.assertEquals("FedAll selection changed after Heuristic consumed its policy view",
			fedAll.normalizedPlanFingerprint(), fedAllAfterHeuristic.normalizedPlanFingerprint());
		Assert.assertEquals("Heuristic selection changed after FedAll consumed its policy view",
			heuristic.normalizedPlanFingerprint(), heuristicAfterFedAll.normalizedPlanFingerprint());
	}

	@Test
	public void exactAndDpProductionCannotCallPolicyCandidateSelection() throws IOException {
		Path costBased = MAIN.resolve("fedCostBased");
		List<String> forbidden = List.of("PolicyFirstFeasiblePlacementSelector",
			"PolicyCandidateSelectionView", "FedAllPlacementAdapter", "HeuristicPlacementAdapter",
			"selectMaterializationMaximal(",
			"materializationMaximalVariantsForCompleteAssignment(");
		try(Stream<Path> files = Files.walk(costBased)) {
			for(Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String source = Files.readString(file);
				for(String token : forbidden)
					Assert.assertFalse(file + " must not cross the policy-selection boundary via " + token,
						source.contains(token));
			}
		}

		List<Path> unexpected = new ArrayList<>();
		try(Stream<Path> files = Files.walk(MAIN)) {
			for(Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String relative = MAIN.relativize(file).toString().replace('\\', '/');
				if(relative.equals("placement/CandidateSelections.java")
					|| relative.equals("placement/selector/PolicyCandidateSelectionView.java"))
					continue;
				if(Files.readString(file).contains("selectMaterializationMaximal("))
					unexpected.add(file);
			}
		}
		Assert.assertEquals("production policy quotient gained an undeclared caller", List.of(), unexpected);
	}

	private static QuotientWitness quotientWitness(PlacementAnalysis analysis,
		ExactPhysicalModel model, Map<CompiledHopKey,PlacementState> assignment,
		List<CandidateSelectionReceipt> selected) {
		for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
			PlacementState selectedState = assignment.get(domain.node().key());
			if(selectedState == null)
				continue;
			List<CandidateSelectionReceipt> exactRows = domain.alternatives().stream()
				.filter(alternative -> alternative.captured() && alternative.state().equals(selectedState))
				.map(alternative -> canonicalReceipt(analysis, alternative))
				.distinct().toList();
			List<CandidateSelectionReceipt> policyRows = selected.stream()
				.filter(receipt -> receipt.rule().parentOccurrence() == domain.node().key())
				.toList();
			if(exactRows.size() <= policyRows.size())
				continue;
			CandidateSelectionReceipt elided = exactRows.stream()
				.filter(receipt -> policyRows.stream().noneMatch(selectedReceipt -> selectedReceipt == receipt))
				.findFirst().orElseThrow();
			return new QuotientWitness(domain.node().key(), exactRows.size(), policyRows.size(), elided);
		}
		throw new AssertionError("B-11 must expose an Exact candidate-row domain reduced by the policy quotient");
	}

	private static CandidateSelectionReceipt canonicalReceipt(PlacementAnalysis analysis,
		ExactPhysicalModel.Alternative alternative) {
		return analysis.canonicalCandidateReceipt(alternative.candidateRule().key(),
			alternative.candidateEmission(), alternative.realization(), alternative.supportClause());
	}

	private static void assertPolicyElides(String policy,
		List<CandidateSelectionReceipt> selected, QuotientWitness witness) {
		Assert.assertFalse(policy + " must measurably quotient the witnessed candidate row",
			selected.stream().anyMatch(receipt -> receipt == witness.elidedReceipt()));
	}

	private static void assertSourceUniverseUnchanged(PlacementAnalysis analysis,
		List<CandidateSelectionReceipt> expectedReceipts, List<String> expectedReceiptSignatures,
		List<List<String>> expectedExactDomains, String expectedGraphSignature,
		String expectedFingerprint, QuotientWitness witness) {
		List<CandidateSelectionReceipt> actualReceipts = canonicalReceipts(analysis);
		Assert.assertEquals("policy selection mutated the analysis fingerprint",
			expectedFingerprint, analysis.analysisFingerprint());
		Assert.assertEquals("policy selection mutated the shared placement graph",
			expectedGraphSignature, analysis.graph().normalizedSignature());
		Assert.assertEquals("policy selection changed the shared receipt universe",
			expectedReceiptSignatures, receiptSignatures(actualReceipts));
		Assert.assertEquals("policy selection changed an Exact physical domain",
			expectedExactDomains, exactDomainSignatures(ExactPhysicalModel.build(analysis)));
		Assert.assertTrue("fixture must exercise a non-vacuous policy quotient",
			witness.exactRows() > witness.policyRows());
		Assert.assertTrue("policy-elided legal receipt disappeared from the Exact DP domain",
			exactReceipts(analysis, ExactPhysicalModel.build(analysis), witness.decision()).stream()
				.anyMatch(receipt -> receipt == witness.elidedReceipt()));
		Assert.assertEquals("shared receipt universe size changed", expectedReceipts.size(), actualReceipts.size());
		for(int index = 0; index < expectedReceipts.size(); index++)
			Assert.assertSame("policy selection replaced an analysis-owned receipt at index " + index,
				expectedReceipts.get(index), actualReceipts.get(index));
	}

	private static List<CandidateSelectionReceipt> exactReceipts(PlacementAnalysis analysis,
		ExactPhysicalModel model, CompiledHopKey decision) {
		return model.domains().stream().filter(domain -> domain.node().key() == decision)
			.flatMap(domain -> domain.alternatives().stream())
			.filter(ExactPhysicalModel.Alternative::captured)
			.map(alternative -> canonicalReceipt(analysis, alternative)).distinct().toList();
	}

	private static List<CandidateSelectionReceipt> canonicalReceipts(PlacementAnalysis analysis) {
		List<CandidateSelectionReceipt> receipts = new ArrayList<>();
		for(var rule : analysis.candidateRuleFacts().orderedFacts())
			for(var emission : rule.allowedEmissionFacts())
				for(var realization : emission.realizations())
					for(var clause : realization.supportClauses())
						receipts.add(analysis.canonicalCandidateReceipt(
							rule.key(), emission, realization, clause));
		return List.copyOf(receipts);
	}

	private static List<String> receiptSignatures(List<CandidateSelectionReceipt> receipts) {
		return receipts.stream().map(CandidateSelectionReceipt::normalizedSignature).toList();
	}

	private static List<List<String>> exactDomainSignatures(ExactPhysicalModel model) {
		return model.domains().stream().map(domain -> domain.alternatives().stream()
			.map(ExactPhysicalModel.Alternative::signature).toList()).toList();
	}

	private static DMLProgram compileProtectedFixture() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-11");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}

	private record QuotientWitness(CompiledHopKey decision, int exactRows, int policyRows,
		CandidateSelectionReceipt elidedReceipt) { }
}
