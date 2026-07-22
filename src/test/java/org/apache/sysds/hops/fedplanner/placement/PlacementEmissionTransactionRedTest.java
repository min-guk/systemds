/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailurePoint;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** Executable P4 RED: selected placement emission is one exact, fail-closed transaction. */
public class PlacementEmissionTransactionRedTest {
	private static final long SEEDED_SCOPE = 991L;
	private Fixture fixture;

	@Before
	public void setUp() throws Exception {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
		fixture = fixture("B-11");
	}

	@After
	public void tearDown() {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
	}

	@Test
	public void fullPlanIsPrevalidatedBeforeAnyHopOrRegistryMutation() throws Exception {
		seedRegistries();
		StateSnapshot before = snapshot(fixture.analysis());
		Map<CompiledHopKey, PlacementState> incomplete = new LinkedHashMap<>(fixture.plan().selectedStates());
		incomplete.remove(incomplete.keySet().iterator().next());

		expectFailure(() -> PlacementEmissionTransaction.emit(fixture.program(),
			wrap(fixture.plan(), fixture.analysis(), fixture.analysis().analysisFingerprint(), incomplete,
				fixture.plan().selectedRelocations(), "incomplete-plan"), FailureInjector.none()));

		Assert.assertEquals("P4_PREVALIDATION_MUST_PRECEDE_ALL_MUTATION", before, snapshot(fixture.analysis()));
	}

	@Test
	public void samePlanHashIsANoOpButDifferentStaleAndForeignPlansFailClosed() throws Exception {
		PlacementEmissionReceipt first = PlacementEmissionTransaction.emit(
			fixture.program(), fixture.plan(), FailureInjector.none());
		StateSnapshot committed = snapshot(fixture.analysis());
		PlacementEmissionReceipt repeat = PlacementEmissionTransaction.emit(
			fixture.program(), fixture.plan(), FailureInjector.none());

		Assert.assertTrue("P4_FIRST_EMISSION_APPLIED", first.applied());
		Assert.assertFalse("P4_FIRST_EMISSION_NOT_NOOP", first.noOp());
		Assert.assertEquals("P4_RECEIPT_HASH_IS_CANONICAL_PLAN_HASH",
			fixture.plan().normalizedPlanFingerprint(), first.planHash());
		Assert.assertFalse("P4_REPEAT_NOT_REAPPLIED", repeat.applied());
		Assert.assertTrue("P4_REPEAT_SAME_HASH_NOOP", repeat.noOp());
		Assert.assertEquals("P4_REPEAT_CHANGES_NOTHING", committed, snapshot(fixture.analysis()));

		expectFailure(() -> PlacementEmissionTransaction.emit(fixture.program(),
			wrap(fixture.plan(), fixture.analysis(), fixture.analysis().analysisFingerprint(),
				fixture.plan().selectedStates(), fixture.plan().selectedRelocations(), "different-plan-hash"),
			FailureInjector.none()));
		expectFailure(() -> PlacementEmissionTransaction.emit(fixture.program(),
			wrap(fixture.plan(), fixture.analysis(), "stale-analysis-fingerprint",
				fixture.plan().selectedStates(), fixture.plan().selectedRelocations(), "stale-plan"),
			FailureInjector.none()));
		Fixture foreign = fixture("B-11");
		expectFailure(() -> PlacementEmissionTransaction.emit(fixture.program(),
			wrap(fixture.plan(), foreign.analysis(), foreign.analysis().analysisFingerprint(),
				foreign.plan().selectedStates(), foreign.plan().selectedRelocations(),
				foreign.plan().normalizedPlanFingerprint()), FailureInjector.none()));
		Assert.assertEquals("P4_FOREIGN_OR_STALE_FAILURE_IS_NON_MUTATING", committed, snapshot(fixture.analysis()));
	}

	@Test
	public void failureAfterFirstHopMutationRestoresEveryOwnedSurfaceExactly() throws Exception {
		assertExactRollback(FailurePoint.AFTER_FIRST_HOP_MUTATION);
	}

	@Test
	public void failureAfterFirstRegistryWriteRestoresEveryOwnedSurfaceExactly() throws Exception {
		assertExactRollback(FailurePoint.AFTER_FIRST_REGISTRY_WRITE);
	}

	@Test
	public void durableAnchorsRemainExplicitAndRuntimeFallbackRepairCountersArePresentAndZero() throws Exception {
		PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			fixture.program(), fixture.plan(), FailureInjector.none());
		Assert.assertEquals("P4_RUNTIME_FALLBACK_FORBIDDEN", 0L,
			PlacementEmissionTransaction.observabilitySnapshot().runtimeFallbackCount());
		Assert.assertEquals("P4_RUNTIME_REPAIR_FORBIDDEN", 0L,
			PlacementEmissionTransaction.observabilitySnapshot().runtimeRepairCount());
		Assert.assertTrue("P4_EMISSION_REPORTS_HOP_MUTATIONS", receipt.hopMutations() > 0);
		Assert.assertTrue("P4_EMISSION_REPORTS_REGISTRY_WRITES", receipt.registryWrites() > 0);
		Assert.assertTrue("P4_DURABLE_ANCHOR_KEY_SURVIVES_EMISSION",
			registrySnapshot(fixture.analysis()).stream().anyMatch(row -> row.contains("anchorKey=")
				&& !row.endsWith("anchorKey=null")));
		Assert.assertFalse("P4_RECOMPILE_REGION_CANNOT_EMIT_CP_FOUT",
			fixture.analysis().occurrences().stream().anyMatch(o -> "recompile".equals(o.key().recompileContext())
				&& selected(fixture.plan(), o.key(), ExecType.CP, FederatedOutput.FOUT)));
	}

	private void assertExactRollback(FailurePoint failurePoint) throws Exception {
		seedRegistries();
		primeDistinctHopMirrors(fixture.analysis());
		StateSnapshot before = snapshot(fixture.analysis());
		expectFailure(() -> PlacementEmissionTransaction.emit(fixture.program(), fixture.plan(),
			FailureInjector.failAt(failurePoint)));
		Assert.assertEquals("P4_EXACT_ROLLBACK|" + failurePoint, before, snapshot(fixture.analysis()));
	}

	private static boolean selected(NormalizedPlannerResult result, CompiledHopKey key,
		ExecType exec, FederatedOutput output) {
		PlacementState state = result.selectedStates().get(key);
		return state != null && state.execType() == exec && state.output() == output;
	}

	private static Fixture fixture(String id) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(id);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Assert.assertFalse("P4_FIXTURE_REQUIRES_DECISIONS", plan.selectedStates().isEmpty());
		Assert.assertFalse("P4_FIXTURE_REQUIRES_REGISTRY_OBLIGATIONS", plan.selectedRelocations().isEmpty());
		return new Fixture(program, analysis, plan);
	}

	private static NormalizedPlannerResult wrap(NormalizedPlannerResult source, PlacementAnalysis analysis,
		String analysisFingerprint, Map<CompiledHopKey, PlacementState> states,
		List<RelocationActionKey> relocations, String planHash) {
		return new NormalizedPlannerResult() {
			@Override public PlacementAnalysis analysis() { return analysis; }
			@Override public String plannerId() { return source.plannerId(); }
			@Override public String analysisFingerprint() { return analysisFingerprint; }
			@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return Map.copyOf(states); }
			@Override public List<RelocationActionKey> selectedRelocations() { return List.copyOf(relocations); }
			@Override public String objectiveCertificate() { return source.objectiveCertificate(); }
			@Override public String normalizedPlanFingerprint() { return planHash; }
		};
	}

	private static void primeDistinctHopMirrors(PlacementAnalysis analysis) {
		for(var occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			hop.setExecType(ExecType.CP);
			hop.clearForcedExecType();
			hop.setFederatedOutput(FederatedOutput.LOUT);
			hop.setFederatedOutputDerived(true);
		}
	}

	private static StateSnapshot snapshot(PlacementAnalysis analysis) {
		Map<Long, HopSnapshot> hops = new LinkedHashMap<>();
		analysis.occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.sorted(java.util.Comparator.comparingLong(Hop::getHopID)).forEach(hop -> hops.put(hop.getHopID(),
				new HopSnapshot(hop.getExecType(), hop.getForcedExecType(), hop.getFederatedOutput(),
					hop.isFederatedOutputDerived())));
		return new StateSnapshot(Map.copyOf(hops), registrySnapshot(analysis),
			Map.copyOf(PlacementEmissionTransaction.receiptSnapshotForTesting()),
			PlacementEmissionTransaction.observabilitySnapshot().runtimeFallbackCount(),
			PlacementEmissionTransaction.observabilitySnapshot().runtimeRepairCount());
	}

	private static List<String> registrySnapshot(PlacementAnalysis analysis) {
		List<String> rows = new ArrayList<>();
		java.util.Set<Long> scopes = new java.util.TreeSet<>();
		scopes.add(SEEDED_SCOPE);
		analysis.occurrences().forEach(o -> scopes.add(o.scopeId()));
		for(long scope : scopes) {
			FederatedRefedRegistry.snapshot(scope).forEach((hop, spec) -> rows.add("R|" + scope + '|' + hop
				+ "|anchor=" + spec.getAnchorHopId() + "|anchorKey=" + spec.getAnchorKey()));
			FederatedFoutMaterializeRegistry.snapshot(scope).forEach((hop, spec) -> rows.add("F|" + scope + '|'
				+ hop + "|anchor=" + spec.getAnchorHopId() + "|type=" + spec.getFTypeHint() + "|label="
				+ spec.getAnchorLabel() + "|anchorKey=" + spec.getAnchorKey()));
			FederatedLocalMaterializeRegistry.snapshotScopes(scope).forEach((actualScope, entries) -> entries.forEach(
				(hop, spec) -> rows.add("L|" + actualScope + '|' + hop + "|consumers=" + spec.getConsumerHopIds()
					+ "|type=" + spec.getFTypeHint() + "|reason=" + spec.getReason())));
		}
		return rows.stream().distinct().sorted().toList();
	}

	private static void seedRegistries() {
		FederatedRefedRegistry.register(SEEDED_SCOPE, 11L, 12L, "seed-anchor-key");
		FederatedFoutMaterializeRegistry.register(SEEDED_SCOPE, 11L, 12L, "ROW", "seed", "seed-anchor-key");
		FederatedLocalMaterializeRegistry.register(SEEDED_SCOPE, 13L, List.of(14L), "ROW", "seed-local");
	}

	private static void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private static void expectFailure(ThrowingRunnable action) throws Exception {
		try {
			action.run();
			Assert.fail("P4_FAIL_CLOSED_EXPECTED");
		}
		catch(AssertionError failure) {
			throw failure;
		}
		catch(RuntimeException expected) {
			// exact exception type is deliberately not part of the transaction contract
		}
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis, NormalizedPlannerResult plan) { }
	private record HopSnapshot(ExecType exec, ExecType forcedExec, FederatedOutput output, boolean derived) { }
	private record StateSnapshot(Map<Long, HopSnapshot> hops, List<String> registries, Map<?, ?> receipts,
		long fallbackCount, long repairCount) { }
	@FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
