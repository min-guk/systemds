/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/**
 * Bounded independent oracle for the exact candidate-receipt assignment space under one
 * complete placement assignment. The oracle starts from immutable compiler facts and checks
 * exact realization/input/action identities directly; it deliberately does not call candidate
 * reachability, materialization-maximal reduction, or candidate search while deciding legality.
 */
public class CandidateReceiptAssignmentCompletenessTest {
	private static final int UNPINNED_COUNT = -1;

	@Test
	public void protectedCrossPoolReceiptSpaceExcludesMovementAndMatchesIndependentOracle() throws Exception {
		assertProtectedNoMovementSemanticClass(assertReceiptSpace(crossPoolScript(), UNPINNED_COUNT,
			UNPINNED_COUNT, 0, 0, false));
	}

	@Test
	public void protectedIncomingSupportExcludesMovementAndMatchesIndependentOracle() throws Exception {
		assertProtectedNoMovementSemanticClass(assertReceiptSpace(incomingSupportScript(), UNPINNED_COUNT,
			UNPINNED_COUNT, 0, 0, false));
	}

	@Test
	public void protectedSamePoolReceiptSpaceExcludesMovementAndMatchesIndependentOracle() throws Exception {
		ReceiptSpace space = assertReceiptSpace(samePoolScript(), UNPINNED_COUNT,
			UNPINNED_COUNT, 0, 0, false);
		assertProtectedNoMovementSemanticClass(space);
	}

	@Test
	public void protectedRepeatedProducerReceiptSpaceMatchesIndependentOracle() throws Exception {
		assertProtectedNoMovementSemanticClass(assertReceiptSpace(repeatedProducerScript(), UNPINNED_COUNT,
			UNPINNED_COUNT, 0, 0, false));
	}

	@Test
	public void completeAssignmentSetIsInvariantToEnumerationAndActionVisitOrder() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(crossPoolScript()));
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw = rawSelectedPlacementDomains(
			analysis, plan.selectedStates());
		Set<CompiledHopKey> oracleConsumers = dependencyClosure(raw);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> boundedRaw = project(raw, oracleConsumers);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> reversedRaw = reversedTraversal(boundedRaw);

		Assert.assertEquals("raw assignment set must not depend on enumeration order",
			enumerateAll(boundedRaw), enumerateAll(reversedRaw));
		Assert.assertEquals("independent legal assignment set must not depend on enumeration order",
			enumerateLegal(analysis, plan.selectedStates(), boundedRaw),
			enumerateLegal(analysis, plan.selectedStates(), reversedRaw));

		List<RelocationAction> actions = new ArrayList<>(analysis.graph().relocationActions());
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> forward = project(
			CandidateSelections.feasibleVariants(analysis, analysis.graph(), actions, plan.selectedStates()),
			oracleConsumers);
		Collections.reverse(actions);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> reverseActions = project(
			CandidateSelections.feasibleVariants(analysis, analysis.graph(), actions, plan.selectedStates()),
			oracleConsumers);
		Assert.assertEquals("production feasible assignment set must not depend on relocation-action visit order",
			enumerateAll(forward), enumerateAll(reverseActions));
	}

	private static ReceiptSpace assertReceiptSpace(String script, int expectedRawAssignments,
		int expectedLegalAssignments, int expectedRawRelocationRows,
		int expectedLegalRelocationRows) throws Exception {
		return assertReceiptSpace(script, expectedRawAssignments, expectedLegalAssignments,
			expectedRawRelocationRows, expectedLegalRelocationRows, true);
	}

	private static ReceiptSpace assertReceiptSpace(String script, int expectedRawAssignments,
		int expectedLegalAssignments, int expectedRawRelocationRows,
		int expectedLegalRelocationRows, boolean requireDirectRow) throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(script));
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Assert.assertTrue("fixture must avoid logical transient coupling; it has a separate exhaustive oracle",
			analysis.logicalTransientInputsInCanonicalOrder().isEmpty());
		Assert.assertTrue("fixture must avoid function-boundary coupling; it has a separate exhaustive oracle",
			analysis.logicalBoundaryRealizations().relations().isEmpty());

		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw = rawSelectedPlacementDomains(
			analysis, plan.selectedStates());
		Set<CompiledHopKey> oracleConsumers = dependencyClosure(raw);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> boundedRaw = project(raw, oracleConsumers);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> production = project(
			CandidateSelections.feasibleVariants(analysis, analysis.graph(),
				analysis.graph().relocationActions(), plan.selectedStates()), oracleConsumers);

		Set<String> rawAssignments = enumerateAll(boundedRaw);
		Set<String> oracle = enumerateLegal(analysis, plan.selectedStates(), boundedRaw);
		Set<String> actual = enumerateAll(production);
		if(expectedRawAssignments != UNPINNED_COUNT)
			Assert.assertEquals("fixture raw product changed", expectedRawAssignments, rawAssignments.size());
		if(expectedLegalAssignments != UNPINNED_COUNT)
			Assert.assertEquals("independent legal product changed", expectedLegalAssignments, oracle.size());
		Assert.assertEquals("production candidate domain must equal the independent legal assignment set",
			oracle, actual);

		if(expectedRawRelocationRows != UNPINNED_COUNT)
			Assert.assertEquals("fixture must retain the intended raw RELOCATION alternatives",
				expectedRawRelocationRows, countRowsWithRelocation(boundedRaw));
		if(expectedLegalRelocationRows != UNPINNED_COUNT)
			Assert.assertEquals("legal assignment domain retained an unexpected RELOCATION row",
				expectedLegalRelocationRows, countRowsWithRelocation(production));
		if(requireDirectRow)
			Assert.assertTrue("fixture must retain at least one exact DIRECT row",
				production.values().stream().flatMap(List::stream)
					.anyMatch(CandidateReceiptAssignmentCompletenessTest::hasDirectBinding));
		return new ReceiptSpace(boundedRaw, production, rawAssignments, oracle);
	}

	private static void assertProtectedNoMovementSemanticClass(ReceiptSpace space) {
		Assert.assertFalse("protected fixture must retain a candidate domain",
			space.production().isEmpty());
		Assert.assertTrue("protected fixture must not expose relocation bindings",
			space.production().values().stream().flatMap(List::stream)
				.noneMatch(CandidateReceiptAssignmentCompletenessTest::hasRelocationBinding));
		Assert.assertEquals("protected raw and independently legal assignment sets must coincide",
			space.rawAssignments(), space.legalAssignments());
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> rawSelectedPlacementDomains(
		PlacementAnalysis analysis, Map<CompiledHopKey,PlacementState> assignment) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw = new IdentityHashMap<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			PlacementState selected = assignment.get(fact.key().parentOccurrence());
			if(selected == null || fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				if(emission.emissionState().placementState().equals(selected))
					raw.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>())
						.addAll(analysis.canonicalCandidateReceipts(fact.key(), emission));
		}
		raw.replaceAll((ignored, rows) -> List.copyOf(rows));
		return raw;
	}

	/** Keep every variable consumer plus the exact source-realization owners it references. */
	private static Set<CompiledHopKey> dependencyClosure(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw) {
		Set<CompiledHopKey> selected = Collections.newSetFromMap(new IdentityHashMap<>());
		raw.forEach((consumer, rows) -> {
			if(rows.stream().anyMatch(receipt ->
				receipt.rule().orderedInputs().size() == 2))
				selected.add(consumer);
		});
		boolean changed;
		do {
			changed = false;
			List<CompiledHopKey> snapshot = List.copyOf(selected);
			for(CompiledHopKey consumer : snapshot)
				for(CandidateSelectionReceipt receipt : raw.getOrDefault(consumer, List.of()))
					for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
						CompiledHopKey source = binding.source().rule().parentOccurrence();
						if(raw.containsKey(source) && selected.add(source))
							changed = true;
					}
		}
		while(changed);
		return selected;
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> project(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> source, Set<CompiledHopKey> consumers) {
		List<CompiledHopKey> ordered = new ArrayList<>(consumers);
		Collections.sort(ordered);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new LinkedHashMap<>();
		for(CompiledHopKey consumer : ordered) {
			List<CandidateSelectionReceipt> rows = source.get(consumer);
			Assert.assertNotNull("candidate domain is missing an oracle dependency owner", rows);
			Assert.assertFalse("candidate domain unexpectedly became bottom", rows.isEmpty());
			result.put(consumer, rows);
		}
		return result;
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> reversedTraversal(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> source) {
		List<CompiledHopKey> consumers = new ArrayList<>(source.keySet());
		Collections.reverse(consumers);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new LinkedHashMap<>();
		for(CompiledHopKey consumer : consumers) {
			List<CandidateSelectionReceipt> rows = new ArrayList<>(source.get(consumer));
			Collections.reverse(rows);
			result.put(consumer, List.copyOf(rows));
		}
		return result;
	}

	private static Set<String> enumerateLegal(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains) {
		List<CompiledHopKey> consumers = List.copyOf(domains.keySet());
		Set<String> legal = new LinkedHashSet<>();
		enumerate(domains, consumers, 0, new IdentityHashMap<>(), selected -> {
			if(selected.values().stream().allMatch(receipt -> independentlyReachable(
				analysis, assignment, selected, receipt)))
				legal.add(signature(consumers, selected));
		});
		return legal;
	}

	private static Set<String> enumerateAll(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains) {
		List<CompiledHopKey> consumers = List.copyOf(domains.keySet());
		Set<String> all = new LinkedHashSet<>();
		enumerate(domains, consumers, 0, new IdentityHashMap<>(),
			selected -> all.add(signature(consumers, selected)));
		return all;
	}

	private static boolean independentlyReachable(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		CandidateSelectionReceipt receipt) {
		if(!independentlyReachableDerivedFout(analysis, assignment, receipt))
			return false;
		for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
			CandidateSelectionReceipt source = selected.get(binding.source().rule().parentOccurrence());
			if(source == null || !matches(binding.source(), source))
				return false;
			if(binding.kind() == CandidateInputBindingKind.LOGICAL_TRANSIENT)
				return false; // This fixture family intentionally has no logical transient edge.
			if(binding.kind() == CandidateInputBindingKind.DIRECT) {
				if(source.provenWorkerPool() == null || receipt.provenWorkerPool() == null
					|| !PlacementIdentity.samePhysicalWorkerPool(
						source.provenWorkerPool(), receipt.provenWorkerPool()))
					return false;
				continue;
			}
			RelocationAction action = analysis.graph().relocationActions().stream()
				.filter(candidate -> candidate.key().equals(binding.relocationAction()))
				.findFirst().orElse(null);
			if(action == null || receipt.provenWorkerPool() == null
				|| !action.key().targetPlacement().equals(
					receipt.realization().key().emissionState().placementState())
				|| !PlacementIdentity.samePhysicalWorkerPool(
					action.key().durableAnchor(), receipt.provenWorkerPool())
				|| analysis.graph().node(source.rule().parentOccurrence()).stream()
					.noneMatch(node -> node.valueVersion().equals(action.key().sourceValueVersion()))
				|| action.obligations().stream().noneMatch(obligation ->
					obligation.consumer() == receipt.rule().parentOccurrence()
						&& obligation.inputPosition() == binding.inputPosition()
						&& obligation.requiredPlacement().equals(
							assignment.get(receipt.rule().parentOccurrence()))))
				return false;
		}
		return true;
	}

	private static boolean independentlyReachableDerivedFout(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment, CandidateSelectionReceipt receipt) {
		PlacementState selected = receipt.emission().emissionState().placementState();
		boolean cpFout = selected.execType() == ExecType.CP
			&& selected.output() == FederatedOutput.FOUT;
		boolean requiresAction = receipt.emission().emissionState().derivedFedFout() || cpFout;
		var action = receipt.emission().derivedFoutAction();
		if(!requiresAction)
			return action == null;
		if(action == null || !action.candidateRule().equals(receipt.rule())
			|| !action.producer().equals(receipt.rule().parentOccurrence())
			|| !action.targetPlacement().equals(selected))
			return false;
		CandidateRuleFact exactRule = analysis.candidateRuleFacts().orderedFactsForParent(
			receipt.rule().parentOccurrence()).stream()
			.filter(candidate -> candidate.key().equals(receipt.rule())).findFirst().orElse(null);
		if(exactRule == null || exactRule.allowedEmissionFacts().stream().noneMatch(source ->
			source.derivedFoutAction() == null
				&& source.emissionState().placementState().equals(action.sourcePlacement())))
			return false;
		long ownedActions = analysis.graph().derivedFoutMaterializationActions().stream()
			.filter(candidate -> candidate.key().equals(action)).count();
		if(ownedActions != 1)
			return false;
		PlacementState anchorOwner = assignment.get(action.durableAnchorOwner());
		return anchorOwner != null && anchorOwner.output() == FederatedOutput.FOUT
			&& anchorOwner.fType() == action.durableAnchorOwnerFType();
	}

	private static boolean matches(CandidateRealizationReference reference,
		CandidateSelectionReceipt receipt) {
		return reference.equals(CandidateRealizationReference.of(receipt.rule(), receipt.realization()));
	}

	private static void enumerate(Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains,
		List<CompiledHopKey> consumers, int position,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		java.util.function.Consumer<Map<CompiledHopKey,CandidateSelectionReceipt>> leaf) {
		if(position == consumers.size()) {
			leaf.accept(selected);
			return;
		}
		CompiledHopKey consumer = consumers.get(position);
		for(CandidateSelectionReceipt receipt : domains.get(consumer)) {
			selected.put(consumer, receipt);
			enumerate(domains, consumers, position + 1, selected, leaf);
			selected.remove(consumer);
		}
	}

	private static String signature(List<CompiledHopKey> consumers,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected) {
		return consumers.stream().sorted().map(consumer -> consumer.normalizedSignature() + "=>"
			+ selected.get(consumer).normalizedSignature()).reduce((left, right) -> left + "\n" + right)
			.orElse("");
	}

	private static int countRowsWithRelocation(
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> domains) {
		return (int)domains.values().stream().flatMap(List::stream)
			.filter(CandidateReceiptAssignmentCompletenessTest::hasRelocationBinding).count();
	}

	private static boolean hasRelocationBinding(CandidateSelectionReceipt receipt) {
		return receipt.supportClause().inputBindings().stream()
			.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.RELOCATION);
	}

	private static boolean hasDirectBinding(CandidateSelectionReceipt receipt) {
		return receipt.supportClause().inputBindings().stream()
			.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.DIRECT);
	}

	private static String crossPoolScript() {
		return "A=federated(addresses=list(\"localhost:1234/A1\"),ranges=list(list(0,0),list(4,2)));"
			+ "B=federated(addresses=list(\"localhost:2234/B1\"),ranges=list(list(0,0),list(4,2)));"
			+ "C=A+B;print(sum(C));";
	}

	private static String incomingSupportScript() {
		return "Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(4,1),list(4,0),list(8,1)));"
			+ "Z=(Y<0)+1;print(sum(Z));";
	}

	private static String samePoolScript() {
		return "A=federated(addresses=list(\"localhost:3234/A1\",\"localhost:3235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "B=federated(addresses=list(\"localhost:3234/B1\",\"localhost:3235/B2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "C=A+B;print(sum(C));";
	}

	private static String repeatedProducerScript() {
		return "A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "C=A+A;print(sum(C));";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}

	private record ReceiptSpace(Map<CompiledHopKey,List<CandidateSelectionReceipt>> raw,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> production,
		Set<String> rawAssignments, Set<String> legalAssignments) { }
}
