/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipRepresentative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer.PlannedSelection;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer.RepresentativePreference;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Certificate for the encoded MinST objective: complete exact candidate-row variants,
 * globally unique transfer authorities, and a reduced all-cut-bit exhaustive oracle.
 * This does not claim wall-clock optimality outside the encoded cost model.
 */
public class MinStExactPhysicalPlanSpaceOracleTest {
	@Test
	public void multiDefinitionTransientConstraintsRequireExactSamePlacement() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-03"));
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		var constraints = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.SAME_PLACEMENT)
			.filter(constraint -> analysis.graph().node(constraint.left()).orElseThrow().kind()
				== NodeKind.TRANSIENT_WRITE)
			.filter(constraint -> analysis.graph().node(constraint.right()).orElseThrow().kind()
				== NodeKind.BRANCH_JOIN)
			.toList();
		Assert.assertTrue("fixture must contain every reaching definition for one transient read",
			constraints.stream().collect(java.util.stream.Collectors.groupingBy(
				constraint -> constraint.right(), java.util.IdentityHashMap::new,
				java.util.stream.Collectors.counting())).values().stream().anyMatch(count -> count > 1));
		for(var constraint : constraints) {
			var write = model.domains().stream()
				.filter(domain -> domain.node().key() == constraint.left()).findFirst().orElseThrow();
			var join = model.domains().stream()
				.filter(domain -> domain.node().key() == constraint.right()).findFirst().orElseThrow();
			for(var left : write.alternatives())
				for(var right : join.alternatives()) {
					boolean expected = left.state().equals(right.state());
					Assert.assertEquals("every reaching definition must require the exact same TWrite/TRead placement",
						expected, MinStExactPhysicalModel.constraintSatisfied(
							constraint, left.state(), right.state()));
				}
		}
	}

	@Test
	public void derivedHardCapacityStrictlyDominatesEveryFiniteContribution() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-11"));
		List<CompiledHopKey> scope = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope);
		BigDecimal finiteTotal = BigDecimal.ZERO;
		Double hard = null;
		for(var edge : facts.directedEdgesInDerivationOrder()) {
			boolean hardEdge = edge.contributionsInDerivationOrder().stream()
				.anyMatch(contribution -> contribution.kind().name().startsWith("HARD_"));
			double capacity = Double.longBitsToDouble(edge.capacityBits());
			Assert.assertTrue("all cut capacities must remain finite", Double.isFinite(capacity));
			if(hardEdge) {
				if(hard == null)
					hard = capacity;
				else
					Assert.assertEquals("one instance must use one certified hard capacity",
						hard.doubleValue(), capacity, 0.0);
			}
			else
				finiteTotal = finiteTotal.add(BigDecimal.valueOf(capacity));
		}
		Assert.assertNotNull("fixture must exercise hard legality", hard);
		Assert.assertTrue("hard capacity must exceed the complete finite-edge upper bound: H="
			+ hard + ", finite=" + finiteTotal,
			BigDecimal.valueOf(hard).compareTo(finiteTotal) > 0);
	}

	private static void assertHardDirectedEdge(MinStExactCostFacts facts, long from, long to,
		String provenance) {
		Assert.assertTrue("missing hard equality edge " + provenance + " " + from + "->" + to,
			facts.directedEdgesInDerivationOrder().stream().anyMatch(edge -> edge.fromNodeId() == from
				&& edge.toNodeId() == to && edge.contributionsInDerivationOrder().stream()
					.anyMatch(contribution -> contribution.kind().name().startsWith("HARD_")
						&& provenance.equals(contribution.provenance()))));
	}

	@Test
	public void logicalFunctionInputRetainsSelectedLegalBoundaryAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(ProductionShadowFixtureFactory.compile("B-21"));
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		MinStExactPhysicalSelection selected = MinStExactPhysicalSelection.create(model,
			MinStExactPhysicalOptimizer.optimize(model, surface,
				MinStExactPhysicalOptimizer.PRODUCTION_LIMITS));
		var projected = MinStExactPhysicalPlacementProjector.project(selected);
		var boundaries = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.CONJUNCTIVE)
			.filter(constraint -> analysis.graph().node(constraint.right()).orElseThrow().kind()
				== NodeKind.FUNCTION_INPUT)
			.toList();
		Assert.assertFalse("fixture must publish exact function-input boundary authority",
			boundaries.isEmpty());
		for(var boundary : boundaries) {
			PlacementState source = selected.selectedStates().get(boundary.left());
			PlacementState target = selected.selectedStates().get(boundary.right());
			Assert.assertTrue("synthetic boundary must retain a legal exact value-transfer authority",
				NeutralPlacementGraph.constraintSatisfied(boundary, source, target));
			if(target.output() == FederatedOutput.FOUT) {
				Assert.assertEquals("a FOUT function input must forward an existing FOUT value",
					FederatedOutput.FOUT, source.output());
				Assert.assertSame("a FOUT function input must preserve exact layout identity",
					source.fType(), target.fType());
			}
			Assert.assertSame("projection must retain synthetic boundary authority",
				target,
				projected.normalizedResult().selectedStates().get(boundary.right()));
		}
	}

	@Test
	public void outerRowsComposeWithIndependentAllBitAssignmentObjectiveOracle() throws Exception {
		PlacementAnalysis analysis = boundedAnalysis();
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		long combinations = model.domains().stream().mapToLong(domain -> domain.alternatives().size())
			.reduce(1L, Math::multiplyExact);
		Assert.assertTrue("bounded physical fixture must remain independently enumerable: " + combinations,
			combinations <= 1_000_000L);
		long[] legalAssignments = new long[1];
		double independentBest = independentlyEnumeratedPhysicalObjective(model, surface,
			0, new ArrayList<>(), legalAssignments);
		Assert.assertTrue("independent physical universe must contain a legal plan",
			legalAssignments[0] > 0 && Double.isFinite(independentBest));

		MinStExactPhysicalOptimizer.Result production = MinStExactPhysicalOptimizer.optimize(
			model, surface, MinStExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals("categorical variable elimination must equal exhaustive physical assignment oracle",
			independentBest, Double.longBitsToDouble(production.canonicalObjectiveBits()), 0.0);
	}

	@Test
	public void everyHardLegalBoundedAssignmentProjectsThroughRuntimeAuthorityValidators() throws Exception {
		PlacementAnalysis analysis = boundedAnalysis();
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		long combinations = model.domains().stream().mapToLong(domain -> domain.alternatives().size())
			.reduce(1L, Math::multiplyExact);
		Assert.assertTrue("bounded physical-authority fixture must remain enumerable: " + combinations,
			combinations <= 1_000_000L);
		long[] legalAssignments = new long[1];
		assertEveryHardLegalAssignmentProjects(model, surface, model.analyze(
			MinStExactPhysicalOptimizer.PRODUCTION_LIMITS), 0, new ArrayList<>(), legalAssignments);
		Assert.assertTrue("fixture must expose at least one hard-legal assignment", legalAssignments[0] > 0);
	}

	private static void assertEveryHardLegalAssignmentProjects(MinStExactPhysicalModel model,
		MinStExactCostFactsProducer.PhysicalCostSurface surface,
		MinStExactCategoricalSolver.Statistics statistics, int index,
		List<Integer> assignment, long[] legalAssignments) {
		if(index < model.domains().size()) {
			for(int value = 0; value < model.domains().get(index).alternatives().size(); value++) {
				assignment.add(value);
				assertEveryHardLegalAssignmentProjects(model, surface, statistics,
					index + 1, assignment, legalAssignments);
				assignment.remove(assignment.size() - 1);
			}
			return;
		}
		for(var factor : model.hardFactors()) {
			int[] local = new int[factor.scope().size()];
			for(int position = 0; position < local.length; position++)
				local[position] = assignment.get(model.variables().indexOf(factor.scope().get(position)));
			if(factor.cost(local) == Double.POSITIVE_INFINITY)
				return;
		}
		legalAssignments[0]++;
		List<Integer> exactAssignment = List.copyOf(assignment);
		long objectiveBits = surface.evaluateCanonical(exactAssignment);
		var solver = new MinStExactCategoricalSolver.Result(
			Double.longBitsToDouble(objectiveBits), exactAssignment, statistics);
		var optimized = new MinStExactPhysicalOptimizer.Result(solver, objectiveBits,
			surface.contributionFingerprint());
		try {
			MinStExactPhysicalPlacementProjector.project(
				MinStExactPhysicalSelection.create(model, optimized));
		}
		catch(IllegalArgumentException | IllegalStateException invalid) {
			Assert.fail("hard factors accepted an assignment rejected by runtime authority validators: assignment="
				+ exactAssignment + " reason=" + invalid.getMessage());
		}
	}

	private static double independentlyEnumeratedPhysicalObjective(MinStExactPhysicalModel model,
		MinStExactCostFactsProducer.PhysicalCostSurface surface, int index,
		List<Integer> assignment, long[] legalAssignments) {
		if(index < model.domains().size()) {
			double best = Double.POSITIVE_INFINITY;
			for(int value = 0; value < model.domains().get(index).alternatives().size(); value++) {
				assignment.add(value);
				best = Math.min(best, independentlyEnumeratedPhysicalObjective(
					model, surface, index + 1, assignment, legalAssignments));
				assignment.remove(assignment.size() - 1);
			}
			return best;
		}
		for(var factor : model.hardFactors()) {
			int[] local = new int[factor.scope().size()];
			for(int position = 0; position < local.length; position++)
				local[position] = assignment.get(model.variables().indexOf(factor.scope().get(position)));
			if(factor.cost(local) == Double.POSITIVE_INFINITY)
				return Double.POSITIVE_INFINITY;
		}
		legalAssignments[0]++;
		return Double.longBitsToDouble(surface.evaluateCanonical(assignment));
	}

	private static boolean isExpectedRejectedCandidateRow(String message) {
		return message != null && (message.startsWith("MINST_EXACT_REPRESENTATIVE_PREFERENCE_")
			|| message.startsWith("MINST_EXACT_DECISION_AUTHORITY_EMPTY")
			|| message.startsWith("MINST_EXACT_MEMBERSHIP_AUTHORITY_UNPROVEN")
			|| message.startsWith("MINST_EXACT_OBLIGATION_AUTHORITY_MISSING")
			|| message.startsWith("MINST_CONSUMER_LAYOUT_UNPROVEN"));
	}

	private static void enumeratePreferenceCombinations(
		List<List<RepresentativePreference>> groups, int index,
		List<RepresentativePreference> selected,
		List<List<RepresentativePreference>> combinations) {
		if(index == groups.size()) {
			combinations.add(List.copyOf(selected));
			return;
		}
		enumeratePreferenceCombinations(groups, index + 1, selected, combinations);
		for(RepresentativePreference preference : groups.get(index)) {
			selected.add(preference);
			enumeratePreferenceCombinations(groups, index + 1, selected, combinations);
			selected.remove(selected.size() - 1);
		}
	}

	private static double independentlyEnumeratedCutObjective(MinStExactCostFacts facts) {
		Set<Long> nodeSet = new java.util.TreeSet<>();
		for(var edge : facts.directedEdgesInDerivationOrder()) {
			if(edge.fromNodeId() != facts.sourceNodeId() && edge.fromNodeId() != facts.sinkNodeId())
				nodeSet.add(edge.fromNodeId());
			if(edge.toNodeId() != facts.sourceNodeId() && edge.toNodeId() != facts.sinkNodeId())
				nodeSet.add(edge.toNodeId());
		}
		List<Long> nodes = List.copyOf(nodeSet);
		Assert.assertTrue("independent oracle fixture has too many cut bits: " + nodes.size(),
			nodes.size() <= 20);
		double best = Double.POSITIVE_INFINITY;
		long assignments = 1L << nodes.size();
		for(long mask = 0; mask < assignments; mask++) {
			Set<Long> source = new LinkedHashSet<>();
			source.add(facts.sourceNodeId());
			for(int bit = 0; bit < nodes.size(); bit++)
				if((mask & 1L << bit) != 0)
					source.add(nodes.get(bit));
			List<Double> crossing = new ArrayList<>();
			for(var edge : facts.directedEdgesInDerivationOrder())
				if(source.contains(edge.fromNodeId()) && !source.contains(edge.toNodeId()))
					crossing.add(Double.longBitsToDouble(edge.capacityBits()));
			crossing.sort(Double::compare);
			double objective = 0.0;
			for(double capacity : crossing)
				objective += capacity;
			best = Math.min(best, objective);
		}
		return best;
	}

	@Test
	public void cpRowsWithDifferentRuleIdentityAreNotQuotientedByPlacementState() throws Exception {
		boolean exercised = false;
		for(String fixture : List.of("B-01", "B-07", "B-09", "B-11", "B-16", "B-21", "B-22")) {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
			List<CandidateRuleFact> available = analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE).toList();
			for(int leftIndex = 0; leftIndex < available.size() && !exercised; leftIndex++)
				for(int rightIndex = leftIndex + 1; rightIndex < available.size() && !exercised; rightIndex++) {
					CandidateRuleFact left = available.get(leftIndex), right = available.get(rightIndex);
					if(left.key().parentOccurrence() != right.key().parentOccurrence()
						|| left.key().orderedInputs().equals(right.key().orderedInputs()))
						continue;
					for(CandidateEmissionFact leftEmission : left.allowedEmissionFacts())
						for(CandidateEmissionFact rightEmission : right.allowedEmissionFacts()) {
							PlacementState state = leftEmission.emissionState().placementState();
							if(state.execType() != ExecType.CP
								|| rightEmission.emissionState().placementState() != state)
								continue;
							RepresentativePreference leftPreference = new RepresentativePreference(
								left.key().parentOccurrence(), state.execType(), state.output(),
								left.key().orderedInputs(), state, left, leftEmission);
							RepresentativePreference rightPreference = new RepresentativePreference(
								right.key().parentOccurrence(), state.execType(), state.output(),
								right.key().orderedInputs(), state, right, rightEmission);
							Assert.assertNotEquals("CP physical rows require exact rule/input identity",
								MinStExactCostFactsProducer.physicalRepresentativeSignature(leftPreference),
								MinStExactCostFactsProducer.physicalRepresentativeSignature(rightPreference));
							exercised = true;
						}
				}
		}
		Assert.assertTrue("fixtures must exercise same-state CP rows with distinct rule inputs", exercised);
	}

	@Test
	public void duplicatePhysicalTransferAuthorityFailsClosedBeforeSelection() throws Exception {
		MinStExactPhysicalSelection selected = null;
		List<PlacementAnalysis> candidates = new ArrayList<>();
		candidates.add(new NeutralPlacementGraphBuilder().buildAnalysis(kmeansPhysicalFixture()));
		for(String fixture : List.of("B-22", "B-11", "B-21"))
			candidates.add(new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
				ProductionShadowFixtureFactory.compile(fixture)));
		for(PlacementAnalysis candidate : candidates) {
			MinStExactPhysicalModel model = MinStExactPhysicalModel.build(candidate);
			MinStExactCostFactsProducer.PhysicalCostSurface surface =
				MinStExactCostFactsProducer.physicalCostSurface(candidate, model);
			for(var domain : model.domains()) {
				for(int required = 0; required < domain.alternatives().size(); required++) {
					if(domain.alternatives().get(required).inputAuthorities().stream().noneMatch(authority ->
						authority.kind() == MinStExactPhysicalModel.InputAuthorityKind.RELOCATION))
						continue;
					int requiredValue = required;
					List<MinStExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
					factors.addAll(surface.factors());
					factors.add(MinStExactCategoricalSolver.Factor.lazy(List.of(domain.variable()),
						values -> values[0] == requiredValue ? 0.0 : Double.POSITIVE_INFINITY));
					try {
						var solved = MinStExactCategoricalSolver.solve(model.variables(), factors,
							MinStExactPhysicalOptimizer.PRODUCTION_LIMITS);
						long objective = surface.evaluateCanonical(solved.assignmentInVariableOrder());
						var attempt = MinStExactPhysicalSelection.create(model,
							new MinStExactPhysicalOptimizer.Result(solved, objective,
								surface.contributionFingerprint()));
						if(!attempt.relocationChoices().isEmpty()) {
							selected = attempt;
							break;
						}
					}
					catch(IllegalArgumentException infeasibleForcedAlternative) {
						if(!infeasibleForcedAlternative.getMessage().startsWith("MINST_VE_NO_FEASIBLE_ASSIGNMENT"))
							throw infeasibleForcedAlternative;
					}
				}
				if(selected != null)
					break;
			}
			if(selected != null)
				break;
		}
		Assert.assertNotNull("fixture must publish an active exact relocation demand", selected);
		PlacementAnalysis analysis = selected.analysis();
		var duplicate = new ArrayList<>(selected.relocationChoices());
		duplicate.add(selected.relocationChoices().get(0));
		MinStExactPhysicalSelection exact = selected;
		IllegalArgumentException ambiguous = Assert.assertThrows(IllegalArgumentException.class,
			() -> RelocationSelections.resolveAndValidate(analysis,
				analysis.graph().relocationActions(), exact.selectedStates(),
				exact.candidateReceipts(), duplicate));
		Assert.assertTrue(ambiguous.getMessage(), ambiguous.getMessage().startsWith(
			"Relocation demand has multiple selected alternatives"));

		var missing = selected.relocationChoices().subList(1, selected.relocationChoices().size());
		IllegalArgumentException incomplete = Assert.assertThrows(IllegalArgumentException.class,
			() -> RelocationSelections.resolveAndValidate(analysis,
				analysis.graph().relocationActions(), exact.selectedStates(),
				exact.candidateReceipts(), missing));
		Assert.assertTrue(incomplete.getMessage(), incomplete.getMessage().startsWith(
			"Relocation choices do not cover every active exact demand"));
	}

	@Test
	public void nativeAndDerivedEmissionsSharingOnePlacementStateRemainDistinct() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(
			"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
				+ "P=A/2;S=colSums(P);write(S,\"out\",format=\"binary\");"));
		CandidateRuleFact rule = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.allowedEmissionFacts().stream()
				.anyMatch(emission -> emission.emissionState().derivedFedFout()))
			.findFirst().orElseThrow();
		CandidateEmissionFact derivedEmission = rule.allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().derivedFedFout())
			.findFirst().orElseThrow();
		PlacementState state = derivedEmission.emissionState().placementState();
		CandidateEmissionFact nativeEmission = new CandidateEmissionFact(
			new PlacementEmissionState(state, false), derivedEmission.executionFType());
		CandidateRuleFact synthetic = new CandidateRuleFact(rule.key(), rule.status(),
			rule.capability(), rule.shapeProof(), rule.profile(),
			List.of(nativeEmission, derivedEmission), rule.failureCode());
		RepresentativePreference nativePreference = new RepresentativePreference(
			synthetic.key().parentOccurrence(), state.execType(), state.output(),
			synthetic.key().orderedInputs(), state, synthetic, nativeEmission);
		RepresentativePreference derivedPreference = new RepresentativePreference(
			synthetic.key().parentOccurrence(), state.execType(), state.output(),
			synthetic.key().orderedInputs(), state, synthetic, derivedEmission);
		Assert.assertNotEquals(MinStExactCostFactsProducer.preferenceSignature(nativePreference),
			MinStExactCostFactsProducer.preferenceSignature(derivedPreference));
		java.util.concurrent.atomic.AtomicInteger evaluations = new java.util.concurrent.atomic.AtomicInteger();
		String selected = MinStExactVariantSearch.select(
			List.of(List.of(nativePreference, derivedPreference)), 3,
			preferences -> {
				evaluations.incrementAndGet();
				return Optional.of(preferences.isEmpty() ? "baseline"
					: MinStExactCostFactsProducer.preferenceSignature(preferences.get(0)));
			}, String::compareTo);
		Assert.assertEquals(3, evaluations.get());
		Assert.assertNotNull(selected);
	}

	@Test
	public void outerCandidateRowsAreCoveredAndProductionVariantWinnerIsStable()
		throws Exception {
		int mixedDecisions = 0;
		int nonCanonicalRows = 0;
		for(String fixture : List.of("B-01", "B-07", "B-09", "B-11", "B-16", "B-21", "B-22")) {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
			List<CompiledHopKey> scope = analysis.compiledHopOccurrences().stream()
				.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
			MinStExactCostFacts baseline = MinStExactCostFactsProducer.derive(analysis, scope);
			List<List<RepresentativePreference>> actualGroups =
				MinStExactCostFactsProducer.exactCandidateRowPreferenceGroups(analysis, scope, baseline);
			Map<CompiledHopKey,List<RepresentativePreference>> expected = independentlyEnumeratedRows(
				analysis, scope, baseline);
			assertExactCoverage(fixture, expected, actualGroups);
			nonCanonicalRows += expected.values().stream().mapToInt(List::size).sum();
			for(DecisionFact decision : baseline.decisionFactsInScopeOrder()) {
				boolean mixed = decision.legalStatesInCanonicalOrder().stream().anyMatch(state ->
					state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT)
					&& decision.legalStatesInCanonicalOrder().stream().anyMatch(state ->
						state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT);
				if(mixed)
					mixedDecisions++;
			}

			if(combinationCount(actualGroups)
				<= MinStExactCostFactsProducer.MAX_EXACT_ROW_VARIANT_COMBINATIONS) {
				PlannedSelection independent = independentlySelect(analysis, scope,
					groupsInDecisionOrder(expected));
				PlannedSelection production = MinStExactCostFactsProducer.deriveAndSelectBest(analysis, scope);
				assertSamePlanCertificate(fixture, independent, production);
				assertPreferenceHardConstraints(fixture, production);
			}
		}
		Assert.assertTrue("fixtures must contain non-canonical legal emitted physical rows",
			nonCanonicalRows > 0);
		Assert.assertTrue("mixed FED/LOUT+FED/FOUT decisions must not be skipped", mixedDecisions > 0);
	}

	private static Map<CompiledHopKey,List<RepresentativePreference>> independentlyEnumeratedRows(
		PlacementAnalysis analysis, List<CompiledHopKey> scope, MinStExactCostFacts baseline) {
		Map<CompiledHopKey,List<RepresentativePreference>> result = new LinkedHashMap<>();
		for(DecisionFact decision : baseline.decisionFactsInScopeOrder()) {
			List<MembershipRepresentative> canonical = baseline.membershipRepresentativesInCanonicalOrder().stream()
				.filter(representative -> representative.decisionKey() == decision.key()).toList();
			Map<String,RepresentativePreference> rows = new LinkedHashMap<>();
			for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
				if(fact.key().parentOccurrence() != decision.key()
					|| fact.status() != CandidateEvaluationStatus.AVAILABLE)
					continue;
				for(var emission : fact.allowedEmissionFacts()) {
					PlacementState state = emission.emissionState().placementState();
					if(decision.legalStatesInCanonicalOrder().stream().noneMatch(legal -> legal == state))
						continue;
					RepresentativePreference preference = new RepresentativePreference(decision.key(),
						state.execType(), state.output(), fact.key().orderedInputs(), state, fact, emission);
					boolean isCanonical = canonical.stream().anyMatch(representative ->
						independentPhysicalSignature(representative).equals(
							independentPhysicalSignature(preference)));
					if(!isCanonical)
						rows.merge(independentPhysicalSignature(preference), preference,
							(left, right) -> MinStExactCostFactsProducer.preferenceSignature(left)
								.compareTo(MinStExactCostFactsProducer.preferenceSignature(right)) <= 0
									? left : right);
				}
			}
			if(!rows.isEmpty())
				result.put(decision.key(), List.copyOf(rows.values()));
		}
		return result;
	}

	private static String independentPhysicalSignature(RepresentativePreference preference) {
		return preference.state().normalizedSignature() + "|emission="
			+ preference.candidateEmissionFact().normalizedSignature()
			+ "|inputs=" + preference.orderedInputs();
	}

	private static String independentPhysicalSignature(MembershipRepresentative representative) {
		return representative.state().normalizedSignature() + "|emission="
			+ (representative.candidateEmissionFactOrNull() == null
				? representative.state().normalizedSignature()
				: representative.candidateEmissionFactOrNull().normalizedSignature())
			+ "|inputs=" + representative.orderedInputs();
	}

	private static void assertExactCoverage(String fixture,
		Map<CompiledHopKey,List<RepresentativePreference>> expected,
		List<List<RepresentativePreference>> actualGroups) {
		Map<CompiledHopKey,Set<String>> actual = new LinkedHashMap<>();
		for(List<RepresentativePreference> group : actualGroups) {
			Assert.assertFalse(fixture + " empty categorical group", group.isEmpty());
			CompiledHopKey key = group.get(0).decisionKey();
			Assert.assertTrue(fixture + " group crosses decisions",
				group.stream().allMatch(preference -> preference.decisionKey() == key));
			Assert.assertNull(fixture + " duplicate decision group", actual.put(key,
				group.stream().map(MinStExactCostFactsProducer::preferenceSignature)
					.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))));
		}
		Map<CompiledHopKey,Set<String>> expectedSignatures = new LinkedHashMap<>();
		expected.forEach((key, rows) -> expectedSignatures.put(key, rows.stream()
			.map(MinStExactCostFactsProducer::preferenceSignature)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))));
		Assert.assertEquals(fixture + " physical representative coverage", expectedSignatures, actual);
	}

	private static List<List<RepresentativePreference>> groupsInDecisionOrder(
		Map<CompiledHopKey,List<RepresentativePreference>> expected) {
		return expected.values().stream().map(List::copyOf).toList();
	}

	private static PlannedSelection independentlySelect(PlacementAnalysis analysis,
		List<CompiledHopKey> scope, List<List<RepresentativePreference>> groups) {
		List<PlannedSelection> best = new ArrayList<>(1);
		enumerate(analysis, scope, groups, 0, new ArrayList<>(), best);
		if(best.isEmpty())
			throw new AssertionError("independent exact physical plan universe is empty");
		return best.get(0);
	}

	private static void enumerate(PlacementAnalysis analysis, List<CompiledHopKey> scope,
		List<List<RepresentativePreference>> groups, int index,
		List<RepresentativePreference> selected, List<PlannedSelection> best) {
		if(index == groups.size()) {
			Optional<PlannedSelection> evaluated = MinStExactCostFactsProducer
				.evaluatePreferenceVariant(analysis, scope, List.copyOf(selected));
			if(evaluated.isPresent() && (best.isEmpty() || compare(evaluated.get(), best.get(0)) < 0)) {
				best.clear();
				best.add(evaluated.get());
			}
			return;
		}
		enumerate(analysis, scope, groups, index + 1, selected, best);
		for(RepresentativePreference preference : groups.get(index)) {
			selected.add(preference);
			enumerate(analysis, scope, groups, index + 1, selected, best);
			selected.remove(selected.size() - 1);
		}
	}

	private static int compare(PlannedSelection left, PlannedSelection right) {
		int objective = Double.compare(Double.longBitsToDouble(left.selection().objectiveBits()),
			Double.longBitsToDouble(right.selection().objectiveBits()));
		return objective != 0 ? objective : Integer.compare(
			left.selection().obligationReceiptsInOrder().size(),
			right.selection().obligationReceiptsInOrder().size());
	}

	private static void assertSamePlanCertificate(String fixture, PlannedSelection expected,
		PlannedSelection actual) {
		Assert.assertEquals(fixture + " objective", expected.selection().objectiveBits(),
			actual.selection().objectiveBits());
		Assert.assertEquals(fixture + " states", expected.selection().selectedStatesInScopeOrder().stream()
			.map(PlacementState::normalizedSignature).toList(),
			actual.selection().selectedStatesInScopeOrder().stream()
				.map(PlacementState::normalizedSignature).toList());
		Assert.assertEquals(fixture + " physical representative choices",
			expected.facts().representativePreferences().stream()
				.map(MinStExactCostFactsProducer::preferenceSignature).toList(),
			actual.facts().representativePreferences().stream()
				.map(MinStExactCostFactsProducer::preferenceSignature).toList());
		Assert.assertEquals(fixture + " relocation receipts",
			expected.selection().obligationReceiptsInOrder().stream()
				.map(receipt -> receipt.actionSignature() + '|' + receipt.requiredPlacement().normalizedSignature())
				.toList(),
			actual.selection().obligationReceiptsInOrder().stream()
				.map(receipt -> receipt.actionSignature() + '|' + receipt.requiredPlacement().normalizedSignature())
				.toList());
		Assert.assertEquals(fixture + " selected emission signatures",
			selectedEmissionSignatures(expected), selectedEmissionSignatures(actual));
	}

	private static List<String> selectedEmissionSignatures(PlannedSelection plan) {
		Map<CompiledHopKey,PlacementState> selected = new java.util.IdentityHashMap<>();
		for(int i = 0; i < plan.facts().decisionFactsInScopeOrder().size(); i++)
			selected.put(plan.facts().decisionFactsInScopeOrder().get(i).key(),
				plan.selection().selectedStatesInScopeOrder().get(i));
		return plan.facts().membershipRepresentativesInCanonicalOrder().stream()
			.filter(representative -> selected.get(representative.decisionKey()) == representative.state())
			.map(representative -> representative.decisionKey().normalizedSignature() + '|'
				+ representative.state().normalizedSignature() + '|'
				+ (representative.candidateRuleFactOrNull() == null ? "-"
					: representative.candidateRuleFactOrNull().key().normalizedSignature()) + '|'
				+ (representative.candidateEmissionFactOrNull() == null ? "-"
					: representative.candidateEmissionFactOrNull().normalizedSignature()))
			.toList();
	}

	private static void assertPreferenceHardConstraints(String fixture, PlannedSelection plan) {
		for(RepresentativePreference preference : plan.facts().representativePreferences()) {
			List<String> reasons = plan.facts().directedEdgesInDerivationOrder().stream()
				.flatMap(edge -> edge.contributionsInDerivationOrder().stream())
				.filter(contribution -> contribution.ownerKey() == preference.decisionKey())
				.map(contribution -> contribution.provenance()).toList();
			Assert.assertTrue(fixture + " preferred exec not forced", reasons.contains(
				preference.execType() == ExecType.FED ? "exact-row-requires-fed-consumer"
					: "exact-row-requires-cp-consumer"));
			Assert.assertTrue(fixture + " preferred output not forced", reasons.contains(
				preference.output() == FederatedOutput.FOUT ? "exact-row-requires-fout-consumer"
					: "exact-row-requires-lout-consumer"));
		}
		Assert.assertFalse(fixture + " PRESENT must not hard-force producer FOUT",
			plan.facts().directedEdgesInDerivationOrder().stream()
				.flatMap(edge -> edge.contributionsInDerivationOrder().stream()).anyMatch(contribution ->
					"exact-row-present-input-requires-fout".equals(contribution.provenance())));
	}

	private static long combinationCount(List<List<RepresentativePreference>> groups) {
		long count = 1L;
		for(List<RepresentativePreference> group : groups)
			count = Math.multiplyExact(count, group.size() + 1L);
		return count;
	}

	private static DMLProgram kmeansPhysicalFixture() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"write(Y,\"out\",format=\"csv\");") + "\n";
		return compile(script);
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}
	private static PlacementAnalysis boundedAnalysis() throws Exception {
		PlacementAnalysis full = CampaignBPlacementAnalysisFixtureBridge.build(
			ProductionShadowFixtureFactory.compile("B-01"));
		return CampaignBPlacementAnalysisFixtureBridge.prefix(full, 4);
	}

}
