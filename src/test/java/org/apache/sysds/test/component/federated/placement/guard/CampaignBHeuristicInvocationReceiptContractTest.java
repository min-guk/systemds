/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge.ProjectionOrder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.ipa.FederatedPlannerFactory;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for the real Heuristic supplied-analysis root and immutable typed provenance receipt. */
public class CampaignBHeuristicInvocationReceiptContractTest {
	private static final AtomicLong NEXT_SENTINEL_SCOPE = new AtomicLong(8_105_000_000L);
	private static final String RECEIPT_TYPE = FederatedPlannerFedHeuristic.class.getName()
		+ "$HeuristicInvocationReceipt";
	private static final String COUNTERS_TYPE = FederatedPlannerFedHeuristic.class.getName()
		+ "$InvocationCounters";

	@Test
	public void realFourArgumentRootUsesExactTypedFactsAndSelectsExactlyOnce() throws Exception {
		Fixture fixture = vectorFixture();
		TrackingHeuristic planner = new TrackingHeuristic(fixture.program());

		AFederatedPlanner.PlannerInvocationReceipt receipt = isolatedEmission(List.of(fixture.program()),
			() -> planner.rewriteProgram(fixture.program(), null, null, fixture.analysis()));

		Assert.assertEquals("HEURISTIC_ROOT_SELECTION_COUNT", 1, planner.selectionCount);
		Assert.assertNotNull("HEURISTIC_ROOT_SELECTED_RESULT", planner.selected);
		Assert.assertSame("HEURISTIC_ROOT_MARKER_SET_REUSED_BY_RECEIPT", planner.selectedMarkers,
			invoke(receipt, "markers"));
		assertTypedReceipt(receipt, fixture, planner.selected);
	}

	@Test
	public void factorySuppliedAnalysisRouteIsTypedDeterministicAndMutationFree() throws Exception {
		Invocation first = invokeFactory(vectorFixture());
		Invocation repeat = invokeFactory(vectorFixture());
		Fixture reverseFixture = vectorFixture();
		PlacementAnalysis reversed = CampaignBPlacementAnalysisFixtureBridge.withProjectionOrder(
			reverseFixture.analysis(), reverseFixture.program(), ProjectionOrder.REVERSED);
		// Reordered analysis copies are not rebound as the program's canonical full-rewrite
		// authority; verify order invariance at the selection seam while fresh canonical
		// factory rewrites above preserve first-emission applied/not-noop semantics.
		TrackingHeuristic reversePlanner = new TrackingHeuristic(reverseFixture.program());
		Invocation reverse = new Invocation(reversePlanner.select(reversed,
			markers(reversed.heuristicPolicyFacts())));
		Assert.assertEquals("HEURISTIC_REVERSE_SELECTION_COUNT", 1, reversePlanner.selectionCount);

		Assert.assertEquals("HEURISTIC_FACTORY_ASSIGNMENT", first.result().assignment(),
			repeat.result().assignment());
		Assert.assertEquals("HEURISTIC_FACTORY_PLAN_FINGERPRINT",
			first.result().normalizedPlanFingerprint(), repeat.result().normalizedPlanFingerprint());
		Assert.assertEquals("HEURISTIC_FACTORY_PROVENANCE_FINGERPRINT",
			first.result().certificate().policyViewFingerprint(),
			repeat.result().certificate().policyViewFingerprint());
		Assert.assertEquals("HEURISTIC_FACTORY_EXCLUSIONS", first.result().policyExclusions(),
			repeat.result().policyExclusions());
		Assert.assertEquals("HEURISTIC_REVERSE_PLAN_FINGERPRINT",
			first.result().normalizedPlanFingerprint(), reverse.result().normalizedPlanFingerprint());
		Assert.assertEquals("HEURISTIC_REVERSE_PROVENANCE_FINGERPRINT",
			first.result().certificate().policyViewFingerprint(),
			reverse.result().certificate().policyViewFingerprint());
	}

	@Test
	public void emptyFactsRemainValidWhileForeignAnalysisAndLegacyRoutesFailClosed() throws Exception {
		Fixture fixture = vectorFixture();
		TrackingHeuristic planner = new TrackingHeuristic(fixture.program());
		var before = R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis());

		Assert.assertThrows("HEURISTIC_ROOT_NULL_ANALYSIS", NullPointerException.class,
			() -> mutationFree(List.of(fixture.program()),
				() -> planner.rewriteProgram(fixture.program(), null, null, null)));
		Fixture foreignFixture = vectorFixture();
		IllegalArgumentException foreign = Assert.assertThrows("HEURISTIC_ROOT_FOREIGN_ANALYSIS",
			IllegalArgumentException.class, () -> mutationFree(
				List.of(fixture.program(), foreignFixture.program()),
				() -> planner.rewriteProgram(foreignFixture.program(), null, null, fixture.analysis())));
		Assert.assertTrue("HEURISTIC_ROOT_FOREIGN_OWNER_MESSAGE", foreign.getMessage().contains("foreign"));

		Assert.assertEquals("HEURISTIC_REJECTION_BEFORE_SELECTION", 0, planner.selectionCount);
		Fixture empty = fixture("B-01");
		Assert.assertTrue("HEURISTIC_EMPTY_POLICY_VIEW_PRECONDITION", empty.facts().demotions().isEmpty());
		TrackingHeuristic emptyPlanner = new TrackingHeuristic(empty.program());
		AFederatedPlanner.PlannerInvocationReceipt emptyReceipt = isolatedEmission(List.of(empty.program()),
			() -> emptyPlanner.rewriteProgram(empty.program(), null, null, empty.analysis()));
		Assert.assertEquals("HEURISTIC_EMPTY_POLICY_SELECTION_COUNT", 1, emptyPlanner.selectionCount);
		assertTypedReceipt(emptyReceipt, empty, emptyPlanner.selected);
		Assert.assertThrows("HEURISTIC_LEGACY_ROUTE_MUST_FAIL_CLOSED", UnsupportedOperationException.class,
			() -> mutationFree(List.of(fixture.program()),
				() -> {
					planner.rewriteProgram(fixture.program(), null, null);
					return null;
				}));
		Assert.assertThrows("HEURISTIC_DYNAMIC_ROUTE_MUST_FAIL_CLOSED", UnsupportedOperationException.class,
			() -> mutationFree(List.of(fixture.program()),
				() -> {
					planner.rewriteFunctionDynamic(null, null);
					return null;
				}));
		R4Heuristic2Probe.unchanged(before,
			R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis()));
	}

	private static Invocation invokeFactory(Fixture fixture) throws Exception {
		AFederatedPlanner planner = FederatedPlannerFactory.create(FederatedPlanner.COMPILE_FED_HEURISTIC);
		AFederatedPlanner.PlannerInvocationReceipt receipt = isolatedEmission(List.of(fixture.program()),
			() -> planner.rewriteProgram(fixture.program(), null, null, fixture.analysis()));
		HeuristicPlacementAdapter.Result result = result(receipt);
		assertTypedReceipt(receipt, fixture, result);
		return new Invocation(result);
	}

	private static void assertTypedReceipt(AFederatedPlanner.PlannerInvocationReceipt receipt,
		Fixture fixture, HeuristicPlacementAdapter.Result exactResult) throws Exception {
		Class<?> receiptType = Class.forName(RECEIPT_TYPE);
		Assert.assertEquals("HEURISTIC_RECEIPT_EXACT_TYPE", receiptType, receipt.getClass());
		Assert.assertTrue("HEURISTIC_RECEIPT_MUST_BE_RECORD", receiptType.isRecord());
		Assert.assertTrue("HEURISTIC_RECEIPT_MUST_BE_FINAL", Modifier.isFinal(receiptType.getModifiers()));
		Assert.assertTrue("HEURISTIC_RECEIPT_BOUNDARY",
			AFederatedPlanner.PlannerInvocationReceipt.class.isAssignableFrom(receiptType));
		Assert.assertSame("HEURISTIC_RECEIPT_ANALYSIS_IDENTITY", fixture.analysis(), receipt.analysis());
		Assert.assertSame("HEURISTIC_RECEIPT_POLICY_FACTS_IDENTITY", fixture.facts(),
			invoke(receipt, "policyFacts"));
		Assert.assertSame("HEURISTIC_RECEIPT_RESULT_IDENTITY", exactResult, result(receipt));
		Assert.assertSame("HEURISTIC_RESULT_ANALYSIS_IDENTITY", fixture.analysis(), exactResult.analysis());
		Assert.assertEquals("HEURISTIC_RECEIPT_MARKERS", fixture.markers(), invoke(receipt, "markers"));
		Assert.assertEquals("HEURISTIC_RECEIPT_MARKER_ORDER",
			fixture.facts().demotions().stream().map(fact -> fact.valueVersion()).toList(),
			List.copyOf((Set<?>) invoke(receipt, "markers")));
		for(var fact : fixture.facts().demotions())
			Assert.assertEquals("HEURISTIC_FACT_PRODUCER_VALUE_BINDING", fact.valueVersion(),
				fixture.analysis().graph().node(fact.producer()).orElseThrow().valueVersion());
		Assert.assertEquals("HEURISTIC_RECEIPT_FINGERPRINT_BEFORE", fixture.analysis().analysisFingerprint(),
			invoke(receipt, "analysisFingerprintBefore"));
		Assert.assertEquals("HEURISTIC_RECEIPT_FINGERPRINT_AFTER", fixture.analysis().analysisFingerprint(),
			invoke(receipt, "analysisFingerprintAfter"));
		Assert.assertEquals("HEURISTIC_RESULT_ANALYSIS_FINGERPRINT", fixture.analysis().analysisFingerprint(),
			exactResult.analysisFingerprint());
		Assert.assertFalse("HEURISTIC_PLAN_FINGERPRINT", exactResult.normalizedPlanFingerprint().isBlank());
		Assert.assertFalse("HEURISTIC_PROVENANCE_FINGERPRINT",
			exactResult.certificate().policyViewFingerprint().isBlank());
		Assert.assertFalse("HEURISTIC_FALLBACK", exactResult.certificate().fallbackUsed());
		Assert.assertEquals("HEURISTIC_COMPLETE_UNIVERSE", exactResult.certificate().legalUniverseSize(),
			exactResult.certificate().exploredCount() + exactResult.certificate().prunedCount());
		assertSingleCanonicalEmission(receipt, fixture.analysis());

		R4Heuristic2Probe.immutable((Set<?>) invoke(receipt, "markers"));
		R4Heuristic2Probe.immutable(fixture.facts().demotions());
		R4Heuristic2Probe.immutable(exactResult.assignment());
		R4Heuristic2Probe.immutable(exactResult.filteredCandidateUniverse());
		R4Heuristic2Probe.immutable(exactResult.policyExclusions());
		R4Heuristic2Probe.immutable(exactResult.selectedRelocations());
		R4Heuristic2Probe.immutable(exactResult.selectedObligations());
		R4Heuristic2Probe.immutable(exactResult.durableAnchors());
		R4Heuristic2Probe.immutable(exactResult.plannerFacts());
		R4Heuristic2Probe.immutable(exactResult.certificate().bounds());
		R4Heuristic2Probe.immutable(exactResult.certificate().boundComponents());

		Object counters = invoke(receipt, "counters");
		Class<?> countersType = Class.forName(COUNTERS_TYPE);
		Assert.assertEquals("HEURISTIC_COUNTERS_EXACT_TYPE", countersType, counters.getClass());
		Assert.assertTrue("HEURISTIC_COUNTERS_MUST_BE_RECORD", countersType.isRecord());
		Assert.assertTrue("HEURISTIC_COUNTERS_MUST_BE_FINAL", Modifier.isFinal(countersType.getModifiers()));
		assertCounter(counters, "selectionCount", 1);
		assertCounter(counters, "internalAnalysisBuildCount", 0);
		assertCounter(counters, "legacyRouteCount", 0);
		assertCounter(counters, "repairCount", 0);
		assertCounter(counters, "fallbackCount", 0);
		assertCounter(counters, "mutationCount", 0);
		assertCounter(counters, "applicationCount", 1);
		assertCounter(counters, "doubleApplicationCount", 0);
	}

	private static void assertSingleCanonicalEmission(Object receipt, PlacementAnalysis analysis) throws Exception {
		Object normalized = invoke(receipt, "normalizedResult");
		Assert.assertTrue("HEURISTIC_NORMALIZED_RESULT_TYPE", normalized instanceof NormalizedPlannerResult);
		Assert.assertSame("HEURISTIC_NORMALIZED_ANALYSIS_IDENTITY", analysis,
			((NormalizedPlannerResult) normalized).analysis());
		String canonical = PlacementEmissionTransaction.canonicalPlanHash((NormalizedPlannerResult) normalized);
		Assert.assertEquals("HEURISTIC_PUBLIC_CANONICAL_HASH_AUTHORITY", canonical,
			((NormalizedPlannerResult) normalized).normalizedPlanFingerprint());
		Object emission = invoke(receipt, "emissionReceipt");
		Assert.assertEquals("HEURISTIC_EXACTLY_ONE_EMISSION_HASH", canonical, invoke(emission, "planHash"));
		Assert.assertEquals("HEURISTIC_EMISSION_APPLIED", true, invoke(emission, "applied"));
		Assert.assertEquals("HEURISTIC_EMISSION_NOT_NOOP", false, invoke(emission, "noOp"));
		assertExactAppliedEmission(receipt, analysis);
	}

	private static void assertExactAppliedEmission(Object receipt, PlacementAnalysis analysis) throws Exception {
		Object normalized = invoke(receipt, "normalizedResult");
		Assert.assertTrue("HEURISTIC_APPLIED_NORMALIZED_RESULT_TYPE", normalized instanceof NormalizedPlannerResult);
		Map<CompiledHopKey, PlacementEmissionState> selected =
			((NormalizedPlannerResult) normalized).selectedEmissionStates();
		List<Node> decisionNodes = analysis.graph().decisionNodes();
		Assert.assertEquals("HEURISTIC_APPLIED_DECISION_COVERAGE", decisionNodes.size(), selected.size());
		Map<Hop, Boolean> concreteWrites = new IdentityHashMap<>();
		for(Node node : decisionNodes) {
			PlacementEmissionState emissionState = exactEmissionState(selected, node.key());
			Assert.assertNotNull("HEURISTIC_APPLIED_SELECTED_STATE|"
				+ node.key().normalizedSignature(), emissionState);
			Assert.assertTrue("HEURISTIC_APPLIED_LEGAL_STATE|" + node.key().normalizedSignature(),
				node.legalAlternatives().contains(emissionState.placementState()));
			if(!analysis.isCompiledHopOccurrence(node.key()))
				continue;
			Hop hop = analysis.hop(node.key()).orElseThrow(AssertionError::new);
			concreteWrites.put(hop, Boolean.TRUE);
			Assert.assertEquals("HEURISTIC_APPLIED_HOP_EXEC|" + node.key().normalizedSignature(),
				emissionState.placementState().execType(), hop.getExecType());
			Assert.assertEquals("HEURISTIC_APPLIED_HOP_FORCED_EXEC|" + node.key().normalizedSignature(),
				emissionState.placementState().execType(), hop.getForcedExecType());
			Assert.assertEquals("HEURISTIC_APPLIED_HOP_OUTPUT|" + node.key().normalizedSignature(),
				emissionState.placementState().output(), hop.getFederatedOutput());
			Assert.assertEquals("HEURISTIC_APPLIED_HOP_DERIVED|" + node.key().normalizedSignature(),
				emissionState.derivedFedFout(), hop.isFederatedOutputDerived());
		}
		Object emission = invoke(receipt, "emissionReceipt");
		Assert.assertEquals("HEURISTIC_APPLIED_HOP_MUTATION_COUNT", concreteWrites.size(),
			invoke(emission, "hopMutations"));
		Assert.assertEquals("HEURISTIC_APPLIED_REGISTRY_WRITE_COUNT",
			((NormalizedPlannerResult) normalized).selectedRelocations().size()
				+ ((NormalizedPlannerResult) normalized).selectedLocalMaterializations().size(),
			invoke(emission, "registryWrites"));
	}

	private static PlacementEmissionState exactEmissionState(
		Map<CompiledHopKey, PlacementEmissionState> selected, CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return entry.getValue();
		return null;
	}

	private static HeuristicPlacementAdapter.Result result(Object receipt) throws Exception {
		Method accessor = receipt.getClass().getMethod("result");
		Assert.assertEquals("HEURISTIC_RECEIPT_RESULT_TYPE", HeuristicPlacementAdapter.Result.class,
			accessor.getReturnType());
		return (HeuristicPlacementAdapter.Result) accessor.invoke(receipt);
	}

	private static Object invoke(Object target, String method) throws Exception {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static void assertCounter(Object counters, String accessor, int expected) throws Exception {
		Method method = counters.getClass().getMethod(accessor);
		Assert.assertEquals("HEURISTIC_COUNTER_TYPE_" + accessor, int.class, method.getReturnType());
		Assert.assertEquals("HEURISTIC_COUNTER_" + accessor, expected, method.invoke(counters));
	}

	private static Fixture fixture(String id) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(id);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		HeuristicPolicyFacts facts = analysis.heuristicPolicyFacts();
		return new Fixture(program, analysis, facts, markers(facts));
	}

	private static Fixture vectorFixture() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "w=z+1;", "print(sum(w));") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		HeuristicPolicyFacts facts = analysis.heuristicPolicyFacts();
		Assert.assertEquals("HEURISTIC_VECTOR_FIXTURE_TYPED_FACT_COUNT", 1, facts.demotions().size());
		return new Fixture(program, analysis, facts, markers(facts));
	}

	private static Set<ValueVersionKey> markers(HeuristicPolicyFacts facts) {
		return facts.demotions().stream().map(fact -> fact.valueVersion())
			.collect(Collectors.toUnmodifiableSet());
	}

	private static <T> T mutationFree(List<DMLProgram> programs, CheckedSupplier<T> action) throws Exception {
		synchronized(CampaignBHeuristicInvocationReceiptContractTest.class) {
			RegistryGuard registries = RegistryGuard.seeded(programs);
			try {
				String before = hopFingerprint(programs);
				try {
					return action.get();
				}
				finally {
					Assert.assertEquals("HEURISTIC_ROOT_MUTATED_CONCRETE_HOP_STATE", before,
						hopFingerprint(programs));
					registries.assertUnchanged();
				}
			}
			finally {
				registries.restore();
			}
		}
	}

	private static <T> T isolatedEmission(List<DMLProgram> programs, CheckedSupplier<T> action) throws Exception {
		synchronized(CampaignBHeuristicInvocationReceiptContractTest.class) {
			RegistryGuard registries = RegistryGuard.seeded(programs);
			try {
				PlacementEmissionTransaction.resetForTesting();
				return action.get();
			}
			finally {
				PlacementEmissionTransaction.resetForTesting();
				registries.restore();
			}
		}
	}

	private static String hopFingerprint(List<DMLProgram> programs) {
		List<String> rows = new ArrayList<>();
		for(int programIndex = 0; programIndex < programs.size(); programIndex++) {
			for(Hop hop : allHops(programs.get(programIndex))) {
				List<Long> inputs = hop.getInput().stream().map(Hop::getHopID).sorted().toList();
				List<Long> parents = hop.getParent().stream().map(Hop::getHopID).sorted().toList();
				rows.add(programIndex + "|" + System.identityHashCode(hop) + "|" + hop.getHopID() + "|"
					+ hop.getClass().getName() + "|" + hop.getOpString() + "|" + hop.getExecType() + "|"
					+ hop.getForcedExecType() + "|" + hop.getFederatedOutput() + "|"
					+ hop.isFederatedOutputDerived() + "|" + hop.requiresRecompile() + "|" + hop.isVisited()
					+ "|in=" + inputs + "|parents=" + parents);
			}
		}
		Collections.sort(rows);
		return String.join("\n", rows);
	}

	private static List<Hop> allHops(DMLProgram program) {
		List<Hop> result = new ArrayList<>();
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		walkBlocks(program.getStatementBlocks(), result, seen);
		program.getNamedNSFunctionStatementBlocks().entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(entry -> walkBlock(entry.getValue(), result, seen));
		return result;
	}

	private static void walkBlocks(List<StatementBlock> blocks, List<Hop> result, Set<Hop> seen) {
		if(blocks != null)
			for(StatementBlock block : blocks)
				walkBlock(block, result, seen);
	}

	private static void walkBlock(StatementBlock block, List<Hop> result, Set<Hop> seen) {
		List<Hop> roots = new ArrayList<>();
		if(block.getHops() != null)
			roots.addAll(block.getHops());
		if(block instanceof IfStatementBlock)
			roots.add(((IfStatementBlock) block).getPredicateHops());
		if(block instanceof WhileStatementBlock)
			roots.add(((WhileStatementBlock) block).getPredicateHops());
		if(block instanceof ForStatementBlock) {
			roots.add(((ForStatementBlock) block).getFromHops());
			roots.add(((ForStatementBlock) block).getToHops());
			roots.add(((ForStatementBlock) block).getIncrementHops());
		}
		for(Hop root : roots)
			walkHop(root, result, seen);
		if(block instanceof FunctionStatementBlock)
			walkBlocks(((FunctionStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof WhileStatementBlock)
			walkBlocks(((WhileStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof ForStatementBlock)
			walkBlocks(((ForStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof IfStatementBlock) {
			IfStatement statement = (IfStatement) block.getStatement(0);
			walkBlocks(statement.getIfBody(), result, seen);
			walkBlocks(statement.getElseBody(), result, seen);
		}
	}

	private static void walkHop(Hop hop, List<Hop> result, Set<Hop> seen) {
		if(hop == null || !seen.add(hop))
			return;
		result.add(hop);
		for(Hop input : hop.getInput())
			walkHop(input, result, seen);
	}

	private static final class RegistryGuard {
		private final Set<Long> scopes;
		private final RegistryState original;
		private final String seededFingerprint;

		private RegistryGuard(Set<Long> scopes, RegistryState original, String seededFingerprint) {
			this.scopes = scopes;
			this.original = original;
			this.seededFingerprint = seededFingerprint;
		}

		private static RegistryGuard seeded(List<DMLProgram> programs) throws Exception {
			long scope = unusedPositiveScope();
			Set<Long> scopes = new java.util.HashSet<>(statementBlockScopes(programs));
			scopes.add(-1L);
			scopes.add(scope);
			RegistryState original = RegistryState.capture(scopes);
			try {
				FederatedRefedRegistry.register(scope, 11L, 101L, "anchor:row", java.util.List.of(12L));
				FederatedFoutMaterializeRegistry.register(scope, 12L, 102L, "ROW",
					"row-anchor", "anchor:row");
				FederatedLocalMaterializeRegistry.register(scope, 13L, List.of(14L, 15L),
					"ROW", "heuristic-root-sentinel");
				return new RegistryGuard(Set.copyOf(scopes), original, registryFingerprint(scopes));
			}
			catch(Exception | Error failure) {
				removeSentinels(scope);
				throw failure;
			}
		}

		private void assertUnchanged() throws Exception {
			Assert.assertEquals("HEURISTIC_ROOT_MUTATED_FEDERATION_REGISTRIES", seededFingerprint,
				registryFingerprint(scopes));
		}

		private void restore() {
			original.restore(scopes);
		}
	}

	private static Set<Long> statementBlockScopes(List<DMLProgram> programs) {
		Set<Long> scopes = new java.util.HashSet<>();
		for(DMLProgram program : programs) {
			collectBlockScopes(program.getStatementBlocks(), scopes);
			program.getNamedNSFunctionStatementBlocks().values().forEach(block -> collectBlockScopes(List.of(block), scopes));
		}
		return scopes;
	}

	private static void collectBlockScopes(List<StatementBlock> blocks, Set<Long> scopes) {
		if(blocks == null)
			return;
		for(StatementBlock block : blocks) {
			scopes.add(block.getSBID());
			if(block instanceof FunctionStatementBlock)
				collectBlockScopes(((FunctionStatement) block.getStatement(0)).getBody(), scopes);
			else if(block instanceof WhileStatementBlock)
				collectBlockScopes(((WhileStatement) block.getStatement(0)).getBody(), scopes);
			else if(block instanceof ForStatementBlock)
				collectBlockScopes(((ForStatement) block.getStatement(0)).getBody(), scopes);
			else if(block instanceof IfStatementBlock) {
				IfStatement statement = (IfStatement) block.getStatement(0);
				collectBlockScopes(statement.getIfBody(), scopes);
				collectBlockScopes(statement.getElseBody(), scopes);
			}
		}
	}

	private static long unusedPositiveScope() {
		while(true) {
			long scope = NEXT_SENTINEL_SCOPE.getAndIncrement();
			boolean localScopePresent = FederatedLocalMaterializeRegistry.snapshotScopes(scope)
				.containsKey(scope);
			if(FederatedRefedRegistry.snapshot(scope).isEmpty()
				&& FederatedFoutMaterializeRegistry.snapshot(scope).isEmpty() && !localScopePresent)
				return scope;
		}
	}

	private static String registryFingerprint(Set<Long> scopes) {
		List<String> rows = new ArrayList<>();
		for(long scope : scopes) {
			FederatedRefedRegistry.snapshot(scope).forEach((hopId, spec) -> rows.add("REFED|" + scope + "|"
				+ hopId + "|" + spec.getAnchorHopId() + "|" + spec.getAnchorKey()));
			FederatedFoutMaterializeRegistry.snapshot(scope).forEach((hopId, spec) -> rows.add("FOUT|" + scope
				+ "|" + hopId + "|" + spec.getAnchorHopId() + "|" + spec.getFTypeHint() + "|"
				+ spec.getAnchorLabel() + "|" + spec.getAnchorKey()));
			FederatedLocalMaterializeRegistry.snapshotScopes(scope).forEach((registryScope, entries) ->
				entries.forEach((hopId, spec) -> rows.add("LOCAL|" + registryScope + "|" + hopId + "|"
					+ spec.getConsumerHopIds() + "|" + spec.getFTypeHint() + "|" + spec.getReason())));
		}
		Collections.sort(rows);
		return String.join("\n", rows);
	}

	private record RegistryState(Map<Long, Map<Long, FederatedRefedRegistry.AnchorSpec>> refed,
		Map<Long, Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec>> fout,
		Map<Long, Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec>> local) {
		private static RegistryState capture(Set<Long> scopes) {
			Map<Long, Map<Long, FederatedRefedRegistry.AnchorSpec>> refed = new HashMap<>();
			Map<Long, Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec>> fout = new HashMap<>();
			Map<Long, Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec>> local = new HashMap<>();
			for(long scope : scopes) {
				refed.put(scope, new HashMap<>(FederatedRefedRegistry.snapshot(scope)));
				fout.put(scope, new HashMap<>(FederatedFoutMaterializeRegistry.snapshot(scope)));
				FederatedLocalMaterializeRegistry.snapshotScopes(scope).forEach((s, entries) ->
					local.put(s, new HashMap<>(entries)));
			}
			return new RegistryState(refed, fout, local);
		}

		private void restore(Set<Long> scopes) {
			for(long scope : scopes) {
				FederatedRefedRegistry.snapshot(scope).keySet().forEach(hop -> FederatedRefedRegistry.remove(scope, hop));
				refed.getOrDefault(scope, Map.of()).forEach((hop, spec) ->
					FederatedRefedRegistry.register(scope, hop, spec.getAnchorHopId(), spec.getAnchorKey(),
						spec.getConsumerHopIds()));
				FederatedFoutMaterializeRegistry.snapshot(scope).keySet()
					.forEach(hop -> FederatedFoutMaterializeRegistry.remove(scope, hop));
				fout.getOrDefault(scope, Map.of()).forEach((hop, spec) ->
					FederatedFoutMaterializeRegistry.register(scope, hop, spec.getAnchorHopId(), spec.getFTypeHint(),
						spec.getAnchorLabel(), spec.getAnchorKey()));
			}
			Set<Long> localScopes = new java.util.HashSet<>(scopes);
			localScopes.addAll(local.keySet());
			localScopes.add(-1L);
			for(long scope : localScopes) {
				Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> current =
					FederatedLocalMaterializeRegistry.snapshotScopes(scope).getOrDefault(scope, Map.of());
				current.keySet().forEach(hop -> FederatedLocalMaterializeRegistry.remove(scope, hop));
				local.getOrDefault(scope, Map.of()).forEach((hop, spec) -> FederatedLocalMaterializeRegistry.register(scope,
					hop, spec.getConsumerHopIds(), spec.getFTypeHint(), spec.getReason()));
			}
		}
	}

	private static void removeSentinels(long scope) {
		FederatedRefedRegistry.remove(scope, 11L);
		FederatedFoutMaterializeRegistry.remove(scope, 12L);
		FederatedLocalMaterializeRegistry.remove(scope, 13L);
	}

	@FunctionalInterface
	private interface CheckedSupplier<T> {
		T get() throws Exception;
	}

	private static final class TrackingHeuristic extends FederatedPlannerFedHeuristic {
		private final List<DMLProgram> programs;
		private int selectionCount;
		private Set<ValueVersionKey> selectedMarkers;
		private HeuristicPlacementAdapter.Result selected;

		private TrackingHeuristic(DMLProgram... programs) {
			this.programs = List.of(programs);
		}

		@Override
		public HeuristicPlacementAdapter.Result select(PlacementAnalysis analysis, Set<ValueVersionKey> markers) {
			String fingerprintBefore = analysis.analysisFingerprint();
			R4Heuristic2Probe.Snapshot snapshotBefore = snapshotForTrackedProgram(analysis);
			try {
				return mutationFree(programs, () -> {
					selectionCount++;
					selectedMarkers = markers;
					try {
						selected = super.select(analysis, markers);
						return selected;
					}
					finally {
						Assert.assertEquals("HEURISTIC_SELECT_ANALYSIS_FINGERPRINT_MUTATION",
							fingerprintBefore, analysis.analysisFingerprint());
						R4Heuristic2Probe.unchanged(snapshotBefore, snapshotForTrackedProgram(analysis));
					}
				});
			}
			catch(RuntimeException | Error failure) {
				throw failure;
			}
			catch(Exception failure) {
				throw new AssertionError("HEURISTIC_SELECT_MUTATION_PROOF_FAILED", failure);
			}
		}

		private R4Heuristic2Probe.Snapshot snapshotForTrackedProgram(PlacementAnalysis analysis) {
			Assert.assertEquals("HEURISTIC_SELECT_TRACKED_PROGRAM_COUNT", 1, programs.size());
			return R4Heuristic2Probe.snapshot(programs.get(0), analysis);
		}
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		HeuristicPolicyFacts facts, Set<ValueVersionKey> markers) { }
	private record Invocation(HeuristicPlacementAdapter.Result result) { }
}
