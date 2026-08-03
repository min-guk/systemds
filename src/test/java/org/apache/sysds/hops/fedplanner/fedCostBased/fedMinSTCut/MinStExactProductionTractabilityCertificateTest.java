/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer.RepresentativePreference;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer.SelectedFedAuthority;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ContributionKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Production certificate for the bounded exact candidate-row universe. Transfer
 * authorities are separately required to be complete and unique per placement.
 */
public class MinStExactProductionTractabilityCertificateTest {
	@Test
	public void physicalAlternativeFactorsAreBitExactWithLegacyCutOnSmallFixture() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\"),ranges=list(list(0,0),list(4,2)));",
			"S=rand(rows=4,cols=2,seed=7);", "Y=X+S;",
			"write(Y,\"out\",format=\"csv\");") + "\n"));
		List<CompiledHopKey> scope = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		MinStExactCostFactsProducer.PhysicalCostSurface repeated =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		Assert.assertEquals(analysis.analysisFingerprint(), surface.ownerFingerprint());
		Assert.assertEquals(surface.contributionFingerprint(), repeated.contributionFingerprint());
		Assert.assertEquals(surface.contributions().stream().map(
			MinStExactCostFactsProducer.PhysicalContribution::id).toList(),
			repeated.contributions().stream().map(
				MinStExactCostFactsProducer.PhysicalContribution::id).toList());
		long combinations = 1L;
		for(var variable : model.variables())
			combinations = Math.multiplyExact(combinations, variable.domainSize());
		Assert.assertTrue("fixture must remain exhaustively enumerable: " + combinations,
			combinations <= 100_000L);
		int compared = comparePhysicalAssignments(analysis, scope, model, surface,
			new ArrayList<>(), 0);
		Assert.assertTrue("fixture must expose at least one exact legacy/physical assignment", compared > 0);
		Assert.assertFalse("compiled transfer identities must be published",
			surface.transferKeys().isEmpty());
	}

	private static int comparePhysicalAssignments(PlacementAnalysis analysis,
		List<CompiledHopKey> scope, MinStExactPhysicalModel model,
		MinStExactCostFactsProducer.PhysicalCostSurface surface,
		List<Integer> assignment, int index) {
		if(index < model.domains().size()) {
			int compared = 0;
			for(int value = 0; value < model.domains().get(index).alternatives().size(); value++) {
				assignment.add(value);
				compared += comparePhysicalAssignments(analysis, scope, model, surface,
					assignment, index + 1);
				assignment.remove(assignment.size() - 1);
			}
			return compared;
		}
		double legal = MinStExactCategoricalSolver.evaluate(model.variables(), model.hardFactors(),
			new MinStExactCategoricalSolver.Limits(1_000_000L, 10_000_000L), assignment);
		if(!Double.isFinite(legal))
			return 0;
		long physicalBits = surface.evaluateCanonical(assignment);
		List<RepresentativePreference> preferences = new ArrayList<>();
		for(int i = 0; i < assignment.size(); i++) {
			var alternative = model.domains().get(i).alternatives().get(assignment.get(i));
			if(alternative.captured()
				&& alternative.state().execType() == org.apache.sysds.common.Types.ExecType.FED)
				preferences.add(new RepresentativePreference(alternative.decision(),
					alternative.state().execType(), alternative.state().output(), alternative.orderedInputs(),
					alternative.state(), alternative.candidateRule(), alternative.candidateEmission()));
		}
		MinStExactCostFacts facts;
		try {
			facts = MinStExactCostFactsProducer.derive(analysis, scope, preferences);
		}
		catch(IllegalArgumentException incompatibleLegacyMembership) {
			return 0;
		}
		long legacyBits = legacyCutBitsForFixedPhysicalAssignment(facts, model, assignment);
		Assert.assertEquals("physical factor objective must preserve legacy cut bits",
			legacyBits, physicalBits);
		return 1;
	}

	private static long legacyCutBitsForFixedPhysicalAssignment(MinStExactCostFacts facts,
		MinStExactPhysicalModel model, List<Integer> assignment) {
		Map<CompiledHopKey,PlacementState> states = new java.util.IdentityHashMap<>();
		for(int i = 0; i < model.domains().size(); i++)
			states.put(model.domains().get(i).node().key(),
				model.domains().get(i).alternatives().get(assignment.get(i)).state());
		List<MinStExactCutSolver.Decision> decisions = new ArrayList<>();
		Set<Long> decisionNodes = new LinkedHashSet<>();
		for(var decision : facts.decisionFactsInScopeOrder()) {
			PlacementState state = states.get(decision.key());
			if(state == null)
				continue;
			List<Long> sourceNodes = new ArrayList<>(2);
			if(state.execType() == org.apache.sysds.common.Types.ExecType.FED)
				sourceNodes.add(decision.computeNodeId());
			if(state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT)
				sourceNodes.add(decision.placementNodeId());
			decisions.add(new MinStExactCutSolver.Decision(List.of(
				new MinStExactCutSolver.Choice(sourceNodes))));
			decisionNodes.add(decision.computeNodeId());
			decisionNodes.add(decision.placementNodeId());
		}
		Set<Long> free = new LinkedHashSet<>();
		List<MinStExactCutSolver.Edge> edges = facts.directedEdgesInDerivationOrder().stream()
			.map(edge -> {
				free.add(edge.fromNodeId());
				free.add(edge.toNodeId());
				return new MinStExactCutSolver.Edge(edge.fromNodeId(), edge.toNodeId(), edge.capacityBits());
			}).toList();
		free.remove(facts.sourceNodeId());
		free.remove(facts.sinkNodeId());
		free.removeAll(decisionNodes);
		return MinStExactCutSolver.solve(facts.sourceNodeId(), facts.sinkNodeId(), decisions,
			free.stream().sorted().toList(), edges).objectiveBits();
	}

	@Test
	public void legacyCandidateSelectionReachabilityParityRemainsExplicit() throws Exception {
		// KMeans contains native ABSENT_LOCAL, direct PRESENT, refed PRESENT, and an
		// exact ABSENT->PRESENT post-materialization pair. The old two-binary-op
		// fixture did not contain the last category and therefore could not certify it.
		PlacementAnalysis parityAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(kmeans());
		List<CompiledHopKey> parityScope = parityAnalysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		assertCandidateSelectionReachabilityParity(parityAnalysis, parityScope,
			MinStExactCostFactsProducer.derive(parityAnalysis, parityScope));
	}

	@Test
	public void sevenCampaignWorkloadsPublishExactPhysicalTractabilityCertificate() throws Exception {
		for(Workload workload : List.of(
			new Workload("KMEANS", kmeans()), new Workload("PCA", pca()),
			new Workload("LM", lm()), new Workload("L2SVM", l2svm()),
			new Workload("LOGREG", logreg()), new Workload("ALS", als()),
			new Workload("STEPLM", steplm()))) {
				PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
					.bindAtFinalHopBoundary(workload.program());
			MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
			MinStExactCostFactsProducer.PhysicalCostSurface surface =
				MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
			MinStExactPhysicalOptimizer.Result optimized = MinStExactPhysicalOptimizer.optimize(
				model, surface, MinStExactPhysicalOptimizer.PRODUCTION_LIMITS);
			MinStExactPhysicalSelection selected = MinStExactPhysicalSelection.create(model, optimized);
			var projected = MinStExactPhysicalPlacementProjector.project(selected);
			var statistics = optimized.solverResult().statistics();
			String certificate = workload.name() + "|decisions=" + model.domains().size()
				+ "|domainSizes=" + model.domains().stream()
					.map(domain -> domain.alternatives().size()).toList()
				+ "|stats=" + statistics + "|objective="
				+ Double.longBitsToDouble(optimized.canonicalObjectiveBits());

			Assert.assertEquals(certificate, analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> analysis.graph().node(occurrence.key()).orElseThrow().emittedWork())
				.count(), model.domains().size());
			Assert.assertEquals(certificate, analysis.graph().decisionNodes().size(),
				selected.selectedStates().size());
			Assert.assertEquals(certificate, selected.selectedStates(),
				projected.normalizedResult().selectedStates());
			Assert.assertEquals(certificate, selected.candidateReceipts(),
				projected.normalizedResult().selectedCandidateSelections());
			Assert.assertEquals(certificate, selected.relocationChoices(),
				projected.normalizedResult().selectedRelocationChoices());
			Assert.assertTrue(certificate, statistics.maximumFactorCells()
				<= MinStExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumFactorCells());
				Assert.assertTrue(certificate, statistics.materializedFactorCells()
					<= MinStExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumMaterializedCells());

				// Exercise the actual production root, not only its component chain.  The
				// root must reproduce the exact physical selection and commit that same
				// normalized authority without a repair or fallback layer.
				var receipt = new FederatedPlanMinSTCut().rewriteProgram(
					workload.program(), null, null, analysis);
				Assert.assertEquals(certificate, selected.selectedStates(),
					receipt.normalizedResult().selectedStates());
				Assert.assertEquals(certificate, selected.candidateReceipts(),
					receipt.normalizedResult().selectedCandidateSelections());
				Assert.assertEquals(certificate, selected.relocationChoices(),
					receipt.normalizedResult().selectedRelocationChoices());
				Assert.assertNotNull(certificate, receipt.emissionReceipt());
				Assert.assertTrue(certificate, receipt.emissionReceipt().applied());
				Assert.assertFalse(certificate, receipt.emissionReceipt().noOp());

				// Every runtime-available candidate emission that is itself a legal neutral
			// placement must remain represented.  The exact model may add distinct direct
			// and relocation bindings, but it must never silently close the candidate row.
			for(var rule : analysis.candidateRuleFacts().orderedFacts()) {
				if(rule.status() != org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis
					.CandidateEvaluationStatus.AVAILABLE)
					continue;
				var domain = model.domains().stream()
					.filter(candidate -> candidate.node().key() == rule.key().parentOccurrence())
					.findFirst().orElse(null);
				if(domain == null)
					continue;
				for(var emission : rule.allowedEmissionFacts()) {
					PlacementState state = emission.emissionState().placementState();
					if(domain.node().legalAlternatives().stream().noneMatch(legal -> legal.equals(state)))
						continue;
					boolean outputAuthorityExists = !emission.emissionState().derivedFedFout()
						|| analysis.graph().relocationActions().stream().anyMatch(action ->
							action.key().sourceValueVersion() == domain.node().valueVersion()
								&& action.key().targetPlacement().equals(state));
					if(!outputAuthorityExists)
						continue;
					Assert.assertTrue(certificate + "|missing=" + emission.normalizedSignature()
						+ "|rule=" + rule.key().normalizedSignature()
						+ "|capability=" + rule.capability() + "|profile=" + rule.profile()
						+ "|hop=" + analysis.hop(rule.key().parentOccurrence()).orElseThrow()
						+ "|actions=" + candidateActionDiagnostics(analysis, rule, state),
						domain.alternatives().stream().anyMatch(alternative -> alternative.captured()
							&& alternative.candidateRule() == rule
							&& alternative.candidateEmission() == emission));
				}
			}
		}
	}

	private static List<String> candidateActionDiagnostics(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact rule,
		PlacementState state) {
		List<String> result = new ArrayList<>();
		for(int position = 0; position < rule.key().orderedInputs().size(); position++) {
			var input = rule.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			var edges = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == rule.key().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			long actions = analysis.graph().relocationActions().stream()
				.filter(action -> action.key().materializationFType() == input.fType())
				.filter(action -> action.obligations().stream().anyMatch(obligation ->
					obligation.consumer() == rule.key().parentOccurrence()
						&& obligation.inputPosition() == inputPosition
						&& obligation.requiredPlacement().equals(state)))
				.count();
			result.add(position + ":" + input.fType() + ":compiledSources=" + edges.size()
				+ ":exactActions=" + actions);
		}
		return result;
	}

	private static void assertCandidateSelectionReachabilityParity(PlacementAnalysis analysis,
		List<CompiledHopKey> scope, MinStExactCostFacts baseline) {
		var plan = new FedAllPlacementAdapter().select(analysis);
		List<CandidateSelectionReceipt> reachableRows = new ArrayList<>();
		for(var node : analysis.graph().decisionNodes())
			for(PlacementState state : node.legalAlternatives()) {
				Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>(plan.selectedStates());
				assignment.put(node.key(), state);
				try {
					reachableRows.addAll(CandidateSelections.feasibleVariants(analysis,
						analysis.graph().relocationActions(), assignment).values().stream()
						.flatMap(List::stream).toList());
				}
				catch(IllegalStateException inactiveCombination) {
					// A one-node mutation can invalidate another active consumer. It is not a
					// reachable exact assignment and contributes no parity row.
				}
			}
		int absentNative = 0, presentDirect = 0, presentRelocated = 0, postMaterializedPresent = 0;
		for(CandidateSelectionReceipt receipt : reachableRows.stream().distinct().sorted().toList()) {
			if(receipt.emission().emissionState().placementState().execType()
				!= org.apache.sysds.common.Types.ExecType.FED)
				continue;
			boolean useful = false;
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.consumer() != receipt.rule().parentOccurrence()
					|| edge.inputPosition() >= receipt.rule().orderedInputs().size())
					continue;
				var input = receipt.rule().orderedInputs().get(edge.inputPosition());
				var source = analysis.graph().node(edge.producer()).orElseThrow();
				boolean direct = input.present() && source.legalAlternatives().stream().anyMatch(state ->
					state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
						&& state.fType() == input.fType());
				boolean relocated = input.present() && analysis.graph().relocationActions().stream()
					.anyMatch(action -> action.key().sourceValueVersion() == source.valueVersion()
						&& action.key().targetPlacement()
							.equals(receipt.emission().emissionState().placementState())
						&& action.key().materializationFType() == input.fType()
						&& action.obligations().stream().anyMatch(obligation ->
							obligation.consumer() == edge.consumer()
								&& obligation.inputPosition() == edge.inputPosition()));
				useful |= !input.present() && absentNative == 0
					|| input.present() && direct && presentDirect == 0
					|| relocated && presentRelocated == 0
					|| relocated && postMaterializedPresent == 0
						&& hasAbsentSeedRow(analysis, receipt, edge.inputPosition());
			}
			if(!useful)
				continue;
			RepresentativePreference preference = preferenceForReceipt(analysis, receipt);
			MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(
				analysis, scope, List.of(preference));
			var decision = facts.decisionFactsInScopeOrder().stream()
				.filter(candidate -> candidate.key() == receipt.rule().parentOccurrence()).findFirst().orElseThrow();
			var selectedAuthority = MinStExactCostFactsProducer.selectedFedAuthority(analysis, decision,
				facts.membershipRepresentativesInCanonicalOrder(),
				List.of(preference));
			var representative = selectedAuthority.executionRepresentative();
			Assert.assertSame("MINST_SELECTED_RULE_MUST_MATCH_FEASIBLE_VARIANT", receipt.rule(),
				representative.candidateRuleFactOrNull().key());
			Assert.assertSame("MINST_SELECTED_EMISSION_MUST_MATCH_FEASIBLE_VARIANT", receipt.emission(),
				selectedAuthority.normalizedEmissionOrNull());
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.consumer() != receipt.rule().parentOccurrence()
					|| edge.inputPosition() >= receipt.rule().orderedInputs().size())
					continue;
				var input = receipt.rule().orderedInputs().get(edge.inputPosition());
				boolean upload = facts.auxiliaryGroupsInCanonicalOrder().stream()
					.filter(group -> group.direction() == Direction.UPLOAD)
					.flatMap(group -> group.endpointsInCanonicalOrder().stream())
					.anyMatch(endpoint -> endpoint.consumerKey() == edge.consumer()
						&& endpoint.producerKey() == edge.producer()
						&& endpoint.inputPosition() == edge.inputPosition());
				if(!input.present()) {
					if(absentNative > 0)
						continue;
					absentNative = 1;
					Assert.assertFalse("CANDIDATE_SELECTION_ABSENT_NATIVE_PARITY", upload);
					continue;
				}
				var source = analysis.graph().node(edge.producer()).orElseThrow();
				boolean direct = source.legalAlternatives().stream().anyMatch(state ->
					state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
						&& state.fType() == input.fType());
				boolean relocated = analysis.graph().relocationActions().stream()
					.anyMatch(action -> action.key().sourceValueVersion() == source.valueVersion()
						&& action.key().targetPlacement()
							.equals(receipt.emission().emissionState().placementState())
						&& action.key().materializationFType() == input.fType()
						&& action.obligations().stream().anyMatch(obligation ->
							obligation.consumer() == edge.consumer()
								&& obligation.inputPosition() == edge.inputPosition()));
				if(direct) {
					presentDirect = 1;
				}
				if(relocated) {
					boolean postMaterialized = hasAbsentSeedRow(analysis, receipt, edge.inputPosition());
					presentRelocated = 1;
					Assert.assertTrue("CANDIDATE_SELECTION_PRESENT_RELOCATION_PARITY", upload);
					Assert.assertTrue("MINST_PRESENT_RELOCATION_RECEIPT_PARITY",
						facts.transferAuthoritiesInCanonicalOrder().stream().anyMatch(authority ->
							authority.authorityKind() == TransferAuthorityKind.RELOCATION_OBLIGATION
								&& authority.endpoint().consumerKey() == edge.consumer()
								&& authority.endpoint().producerKey() == edge.producer()
								&& authority.endpoint().inputPosition() == edge.inputPosition()
								&& authority.requiredPlacement()
									.equals(receipt.emission().emissionState().placementState())
								&& authority.group().conversionType() == input.fType()));
					if(postMaterialized)
						postMaterializedPresent = 1;
				}
				Assert.assertTrue("PRESENT_MUST_BE_DIRECT_OR_RELOCATABLE", direct || relocated);
			}
		}
		Assert.assertTrue("PARITY_FIXTURE_MUST_EXERCISE_PRESENT_DIRECT", presentDirect > 0);
		if(!analysis.graph().relocationActions().isEmpty()) {
			Assert.assertTrue("PARITY_FIXTURE_MUST_EXERCISE_PRESENT_RELOCATED", presentRelocated > 0);
			Assert.assertTrue("PARITY_FIXTURE_MUST_EXERCISE_POST_MATERIALIZED_PRESENT",
				postMaterializedPresent > 0);
		}
	}

	private static RepresentativePreference preferenceForReceipt(PlacementAnalysis analysis,
		CandidateSelectionReceipt receipt) {
		var fact = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(candidate -> candidate.key() == receipt.rule()).findFirst().orElseThrow();
		PlacementState state = receipt.emission().emissionState().placementState();
		return new RepresentativePreference(receipt.rule().parentOccurrence(), state.execType(),
			state.output(), receipt.rule().orderedInputs(), state, fact, receipt.emission());
	}

	private static boolean hasAbsentSeedRow(PlacementAnalysis analysis,
		CandidateSelectionReceipt present, int inputPosition) {
		return analysis.candidateRuleFacts().orderedFacts().stream().anyMatch(candidate -> {
			if(candidate.key().parentOccurrence() != present.rule().parentOccurrence()
				|| candidate.key().orderedInputs().size() != present.rule().orderedInputs().size()
				|| candidate.key().orderedInputs().get(inputPosition).present())
				return false;
			for(int position = 0; position < candidate.key().orderedInputs().size(); position++)
				if(position != inputPosition && !candidate.key().orderedInputs().get(position)
					.equals(present.rule().orderedInputs().get(position)))
					return false;
			return true;
		});
	}

	private static void assertExactPresentAndAbsentLocalAuthoritySemantics(PlacementAnalysis analysis,
		MinStExactCostFacts facts) {
		for(var group : facts.auxiliaryGroupsInCanonicalOrder()) {
			if(group.direction() != Direction.UPLOAD)
				continue;
			for(var endpoint : group.endpointsInCanonicalOrder()) {
				var decision = facts.decisionFactsInScopeOrder().stream()
					.filter(candidate -> candidate.key() == endpoint.consumerKey()).findFirst().orElseThrow();
				var execution = MinStExactCostFactsProducer.selectedFedAuthority(analysis, decision,
					facts.membershipRepresentativesInCanonicalOrder(), List.of()).executionRepresentative();
				Assert.assertTrue("UPLOAD_MUST_NAME_EXACT_PRESENT_INPUT",
					execution.orderedInputs().get(endpoint.inputPosition()).present());
				long authorities = facts.transferAuthoritiesInCanonicalOrder().stream()
					.filter(authority -> authority.group() == group && authority.endpoint() == endpoint
						&& authority.authorityKind() == TransferAuthorityKind.RELOCATION_OBLIGATION)
					.count();
				Assert.assertEquals("RELOCATED_PRESENT_MUST_HAVE_ONE_EXACT_RELOCATION_AUTHORITY", 1L,
					authorities);
			}
		}
		for(var decision : facts.decisionFactsInScopeOrder()) {
			if(decision.legalStatesInCanonicalOrder().stream()
				.noneMatch(state -> state.execType() == org.apache.sysds.common.Types.ExecType.FED))
				continue;
			var representative = MinStExactCostFactsProducer.selectedFedAuthority(analysis, decision,
				facts.membershipRepresentativesInCanonicalOrder(), List.of()).executionRepresentative();
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(edge.consumer() != representative.decisionKey()
					|| edge.inputPosition() >= representative.orderedInputs().size()
					|| representative.orderedInputs().get(edge.inputPosition()).present())
					continue;
				Assert.assertTrue("ABSENT_LOCAL_MUST_NOT_CREATE_UPLOAD_GROUP",
					facts.auxiliaryGroupsInCanonicalOrder().stream()
						.filter(group -> group.direction() == Direction.UPLOAD)
						.flatMap(group -> group.endpointsInCanonicalOrder().stream())
						.noneMatch(endpoint -> endpoint.consumerKey() == representative.decisionKey()
							&& endpoint.producerKey() == edge.producer()
							&& endpoint.inputPosition() == edge.inputPosition()));
			}
			for(var authority : representative.inputAuthorityFacts()) {
				var input = representative.orderedInputs().get(authority.inputPosition());
				Assert.assertTrue("MEMBERSHIP_INPUT_AUTHORITY_MUST_NAME_PRESENT", input.present());
				boolean relocationAlternative = facts.auxiliaryGroupsInCanonicalOrder().stream()
						.filter(group -> group.direction() == Direction.UPLOAD)
						.flatMap(group -> group.endpointsInCanonicalOrder().stream())
						.anyMatch(endpoint -> endpoint.consumerKey() == representative.decisionKey()
							&& endpoint.producerKey() == authority.inputEdge().producer()
							&& endpoint.inputPosition() == authority.inputPosition());
				boolean directRequired = facts.directedEdgesInDerivationOrder().stream()
						.flatMap(edge -> edge.contributionsInDerivationOrder().stream())
						.anyMatch(contribution -> contribution.kind() == ContributionKind.HARD_UPLOAD_REUSE
							&& contribution.ownerKey() == representative.decisionKey()
							&& contribution.peerKeyOrNull() == authority.inputEdge().producer()
							&& contribution.inputPosition() == authority.inputPosition()
							&& contribution.provenance().equals(
								"exact-present-input-requires-producer-fout"));
				Assert.assertTrue("PRESENT_MUST_HAVE_DIRECT_OR_RELOCATION_AUTHORITY",
					relocationAlternative || directRequired);
			}
		}
	}

	private static int assertExecutionAndOutputAuthorityIdentity(PlacementAnalysis analysis,
		MinStExactCostFacts facts) {
		int composites = 0;
		for(var decision : facts.decisionFactsInScopeOrder()) {
			if(decision.legalStatesInCanonicalOrder().stream()
				.noneMatch(state -> state.execType() == org.apache.sysds.common.Types.ExecType.FED))
				continue;
			SelectedFedAuthority authority = MinStExactCostFactsProducer.selectedFedAuthority(
				analysis, decision, facts.membershipRepresentativesInCanonicalOrder(), List.of());
			if(!authority.derivedFedFout()
				|| authority.outputRepresentative().authorityKind()
					!= MembershipAuthorityKind.RELOCATION_SOURCE)
				continue;
			composites++;
			var execution = authority.executionRepresentative();
			var output = authority.outputRepresentative();
			Assert.assertEquals(MembershipAuthorityKind.CAPTURED_RULE, execution.authorityKind());
			Assert.assertSame(decision.key(), execution.decisionKey());
			Assert.assertSame(decision.key(), output.decisionKey());
			Assert.assertNotNull(execution.candidateRuleFactOrNull());
			Assert.assertNotNull(execution.candidateEmissionFactOrNull());
			Assert.assertSame(execution.state(),
				execution.candidateEmissionFactOrNull().emissionState().placementState());
			Assert.assertFalse(execution.candidateEmissionFactOrNull().emissionState().derivedFedFout());
			Assert.assertNotNull(execution.candidateEmissionFactOrNull().executionFType());
			Assert.assertNotNull(output.relocationActionOrNull());
			Assert.assertSame(analysis.graph().node(decision.key()).orElseThrow().valueVersion(),
				output.relocationActionOrNull().key().sourceValueVersion());
			Assert.assertSame(output.state(),
				output.relocationActionOrNull().key().targetPlacement());
			Assert.assertSame(output.state(), authority.normalizedEmissionOrNull()
				.emissionState().placementState());
			Assert.assertTrue(authority.normalizedEmissionOrNull()
				.emissionState().derivedFedFout());
			Assert.assertEquals(output.state().fType(),
				output.relocationActionOrNull().key().materializationFType());
		}
		return composites;
	}

	private static BigInteger exactProduct(List<List<RepresentativePreference>> groups) {
		BigInteger product = BigInteger.ONE;
		for(List<?> group : groups)
			product = product.multiply(BigInteger.valueOf(group.size() + 1L));
		return product;
	}

	private static DMLProgram kmeans() throws Exception {
		return compile(String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"write(Y,\"out\",format=\"csv\");") + "\n");
	}

	private static DMLProgram pca() throws Exception {
		return compile(String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));",
			"[Xout,Mout]=pca(X=X,K=10);",
			"write(Mout,\"out\",format=\"csv\");") + "\n");
	}

	private static DMLProgram lm() throws Exception {
		return compile(dataPrelude() + String.join("\n",
			"B=lm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);",
			"write(B,\"out\",format=\"csv\");") + "\n");
	}

	private static DMLProgram l2svm() throws Exception {
		return compile(dataPrelude() + String.join("\n",
			"B=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-22,maxIterations=30);",
			"write(B,\"out\",format=\"csv\");") + "\n");
	}

	private static DMLProgram logreg() throws Exception {
		return compile(dataPrelude() + String.join("\n",
			"Y=(Y<0)+1;",
			"B=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0,"
				+ "numclasses=2,numrows=50000,numcols=2100);",
			"write(B,\"out\",format=\"csv\");") + "\n");
	}

	private static DMLProgram als() throws Exception {
		return compile(featuresPrelude() + String.join("\n",
			"[U,V]=als(X=X,rank=10,regType=\"L2\",reg=0.000001,maxi=2,"
				+ "check=FALSE,thr=0.0001,seed=1389632218,verbose=FALSE);",
			"write(V,\"out\",format=\"csv\");") + "\n");
	}

	private static DMLProgram steplm() throws Exception {
		return compile(dataPrelude() + String.join("\n",
			"[B,S]=steplm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);",
			"write(B,\"out\",format=\"csv\");") + "\n");
	}

	private static String dataPrelude() {
		return featuresPrelude()
			+ "Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));\n";
	}

	private static String featuresPrelude() {
		return "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private record Workload(String name, DMLProgram program) { }
}
