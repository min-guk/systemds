/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailurePoint;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** Executable RED for explicit per-occurrence derived FED/FOUT transaction authority. */
public class PlacementEmissionDerivedAuthorityRedTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FED_FOUT =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);

	@Before
	public void setUp() {
		resetOwnedState();
	}

	@After
	public void tearDown() {
		resetOwnedState();
	}

	@Test
	public void canonicalHashDistinguishesOnlyTheDerivedAuthorityBit() {
		Fixture fixture = fixture(false);
		EmissionAwareResult plain = result(fixture, FED_FOUT, false, false);
		EmissionAwareResult derived = result(fixture, FED_FOUT, true, false);

		Assert.assertEquals("P4_DERIVED_FIXTURE_SELECTED_STATES_MUST_BE_IDENTICAL",
			plain.selectedStates(), derived.selectedStates());
		Assert.assertEquals("P4_DERIVED_FIXTURE_OBJECTIVE_MUST_BE_IDENTICAL",
			plain.objectiveCertificate(), derived.objectiveCertificate());
		Assert.assertNotEquals("P4_CANONICAL_HASH_MUST_INCLUDE_DERIVED_AUTHORITY",
			PlacementEmissionTransaction.canonicalPlanHash(plain),
			PlacementEmissionTransaction.canonicalPlanHash(derived));
	}

	@Test
	public void derivedTrueIsAppliedInsideTheSameTransaction() {
		Fixture fixture = fixture(false);
		EmissionAwareResult plan = result(fixture, FED_FOUT, true, true);

		PlacementEmissionTransaction.PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			fixture.program(), plan, FailureInjector.none());

		Assert.assertTrue("P4_DERIVED_TRUE_PLAN_APPLIES", receipt.applied());
		Assert.assertEquals("P4_DERIVED_TRUE_MUTATES_EACH_CONCRETE_HOP", 2, receipt.hopMutations());
		Assert.assertEquals("P4_DERIVED_TRUE_RECEIPT_HASH", plan.normalizedPlanFingerprint(), receipt.planHash());
		Assert.assertTrue("P4_DERIVED_TRUE_FIRST_HOP", fixture.firstHop().isFederatedOutputDerived());
		Assert.assertTrue("P4_DERIVED_TRUE_SECOND_HOP", fixture.secondHop().isFederatedOutputDerived());
	}

	@Test
	public void derivedFalseIsAppliedInsideTheSameTransaction() {
		Fixture fixture = fixture(false);
		fixture.firstHop().setFederatedOutputDerived(true);
		fixture.secondHop().setFederatedOutputDerived(true);
		EmissionAwareResult plan = result(fixture, FED_FOUT, false, false);

		PlacementEmissionTransaction.PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			fixture.program(), plan, FailureInjector.none());

		Assert.assertTrue("P4_DERIVED_FALSE_PLAN_APPLIES", receipt.applied());
		Assert.assertFalse("P4_DERIVED_FALSE_FIRST_HOP", fixture.firstHop().isFederatedOutputDerived());
		Assert.assertFalse("P4_DERIVED_FALSE_SECOND_HOP", fixture.secondHop().isFederatedOutputDerived());
	}

	@Test
	public void derivedTrueOnNonFedFoutRejectsBeforeOwnedMutation() {
		Fixture fixture = fixture(false);
		seedRegistries();
		State before = snapshot(fixture);
		EmissionAwareResult invalid = result(fixture, LOCAL, true, true);
		boolean rejected = false;
		try {
			PlacementEmissionTransaction.emit(fixture.program(), invalid, FailureInjector.none());
		}
		catch(IllegalStateException expected) {
			rejected = true;
		}
		Assert.assertTrue("P4_DERIVED_TRUE_REQUIRES_FED_FOUT", rejected);
		Assert.assertEquals("P4_INVALID_DERIVED_AUTHORITY_REJECTS_BEFORE_MUTATION", before, snapshot(fixture));
	}

	@Test
	public void injectedFailureRestoresDerivedFlagsRegistriesReceiptAndObservability() {
		Fixture fixture = fixture(false);
		seedRegistries();
		primeDistinctMirrors(fixture);
		State before = snapshot(fixture);
		EmissionAwareResult plan = result(fixture, FED_FOUT, true, true);
		boolean rejected = false;
		try {
			PlacementEmissionTransaction.emit(fixture.program(), plan,
				FailureInjector.failAt(FailurePoint.AFTER_FIRST_HOP_MUTATION));
		}
		catch(IllegalStateException expected) {
			rejected = true;
		}
		Assert.assertTrue("P4_DERIVED_FAILURE_INJECTION_MUST_FIRE", rejected);
		Assert.assertEquals("P4_DERIVED_FAILURE_RESTORES_EVERY_OWNED_SURFACE", before, snapshot(fixture));
	}

	@Test
	public void sameConcreteHopWithSameStateAndDerivedBitCoalescesOnce() {
		Fixture fixture = fixture(true);
		EmissionAwareResult plan = result(fixture, FED_FOUT, true, true);

		PlacementEmissionTransaction.PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			fixture.program(), plan, FailureInjector.none());

		Assert.assertEquals("P4_SAME_HOP_DERIVED_AUTHORITY_RETAINS_TWO_OCCURRENCES", 2,
			plan.selectedEmissionStates().size());
		Assert.assertEquals("P4_SAME_HOP_SAME_DERIVED_MUTATES_ONCE", 1, receipt.hopMutations());
		Assert.assertTrue("P4_SAME_HOP_DERIVED_TRUE_APPLIED", fixture.firstHop().isFederatedOutputDerived());
	}

	@Test
	public void sameConcreteHopDerivedDisagreementFailsBeforeOwnedMutation() {
		Fixture fixture = fixture(true);
		seedRegistries();
		State before = snapshot(fixture);
		EmissionAwareResult conflict = result(fixture, FED_FOUT, true, false);
		boolean rejected = false;
		try {
			PlacementEmissionTransaction.emit(fixture.program(), conflict, FailureInjector.none());
		}
		catch(IllegalStateException expected) {
			rejected = true;
		}
		Assert.assertTrue("P4_SAME_HOP_DERIVED_DISAGREEMENT_MUST_FAIL", rejected);
		Assert.assertEquals("P4_SAME_HOP_DERIVED_CONFLICT_PRECEDES_MUTATION", before, snapshot(fixture));
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	public void rawBridgeRetainsImmutableExactKeyStateAndBooleanAuthority() throws Exception {
		Fixture fixture = fixture(false);
		EmissionAwareResult plan = result(fixture, FED_FOUT, true, false);
		Map raw = plan.selectedEmissionStates();

		Assert.assertEquals("P4_DERIVED_AUTHORITY_IS_TOTAL", 2, raw.size());
		Assert.assertTrue("P4_FIRST_KEY_IDENTITY_RETAINED", raw.keySet().stream().anyMatch(key -> key == fixture.first()));
		Assert.assertTrue("P4_SECOND_KEY_IDENTITY_RETAINED", raw.keySet().stream().anyMatch(key -> key == fixture.second()));
		Assert.assertSame("P4_FIRST_EXACT_PLACEMENT_IDENTITY", FED_FOUT, placementState(raw.get(fixture.first())));
		Assert.assertSame("P4_SECOND_EXACT_PLACEMENT_IDENTITY", FED_FOUT, placementState(raw.get(fixture.second())));
		Assert.assertTrue("P4_FIRST_DERIVED_AUTHORITY", derivedFedFout(raw.get(fixture.first())));
		Assert.assertFalse("P4_SECOND_DERIVED_AUTHORITY", derivedFedFout(raw.get(fixture.second())));
		try {
			raw.put(fixture.first(), emissionState(LOCAL, false));
			Assert.fail("P4_DERIVED_AUTHORITY_MAP_MUST_BE_IMMUTABLE");
		}
		catch(UnsupportedOperationException expected) {
			// immutable authority is required
		}
	}

	private static EmissionAwareResult result(Fixture fixture, PlacementState selected,
		boolean firstDerived, boolean secondDerived) {
		Map<CompiledHopKey, PlacementState> states = exactStates(fixture, selected);
		Map<CompiledHopKey, Object> emissionStates = exactEmissionStates(
			fixture, selected, firstDerived, secondDerived);
		EmissionAwareResult draft = new Result(fixture.analysis(), states, emissionStates, "unused");
		return new Result(fixture.analysis(), states, emissionStates,
			PlacementEmissionTransaction.canonicalPlanHash(draft));
	}

	private static Map<CompiledHopKey, PlacementState> exactStates(Fixture fixture, PlacementState state) {
		Map<CompiledHopKey, PlacementState> states = new LinkedHashMap<>();
		states.put(fixture.first(), state);
		states.put(fixture.second(), state);
		return Map.copyOf(states);
	}

	private static Map<CompiledHopKey, Object> exactEmissionStates(Fixture fixture, PlacementState state,
		boolean firstDerived, boolean secondDerived) {
		Map<CompiledHopKey, Object> states = new LinkedHashMap<>();
		states.put(fixture.first(), emissionState(state, firstDerived));
		states.put(fixture.second(), emissionState(state, secondDerived));
		return Map.copyOf(states);
	}

	private static Object emissionState(PlacementState state, boolean derivedFedFout) {
		try {
			Class<?> production = Class.forName(
				"org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState");
			Constructor<?> constructor = production.getDeclaredConstructor(PlacementState.class, boolean.class);
			constructor.setAccessible(true);
			return constructor.newInstance(state, derivedFedFout);
		}
		catch(ClassNotFoundException absentOnRedBase) {
			return new TestEmissionState(state, derivedFedFout);
		}
		catch(ReflectiveOperationException incompatibleProductionContract) {
			throw new AssertionError("Production PlacementEmissionState violates the approved constructor",
				incompatibleProductionContract);
		}
	}

	private static PlacementState placementState(Object value) throws Exception {
		Method accessor;
		try {
			accessor = value.getClass().getMethod("placementState");
		}
		catch(NoSuchMethodException fallbackRecord) {
			accessor = value.getClass().getMethod("state");
		}
		return (PlacementState) accessor.invoke(value);
	}

	private static boolean derivedFedFout(Object value) throws Exception {
		return (boolean) value.getClass().getMethod("derivedFedFout").invoke(value);
	}

	private static Fixture fixture(boolean sharedConcreteHop) {
		String fingerprint = sharedConcreteHop ? "derived-same-hop-red" : "derived-distinct-hop-red";
		ControlRegionKey firstRegion = new ControlRegionKey(fingerprint, "main", List.of("sb-1"),
			"main", "compiled");
		ControlRegionKey secondRegion = new ControlRegionKey(fingerprint, "main", List.of("sb-2"),
			"main", "compiled");
		CompiledHopKey first = new CompiledHopKey(fingerprint, "main", "main", "compiled", firstRegion,
			"derived-hop@sb-1", "derived-hop");
		CompiledHopKey second = new CompiledHopKey(fingerprint, "main", "main", "compiled", secondRegion,
			"derived-hop@sb-2", "derived-hop");
		Node firstNode = node(first, firstRegion, 0);
		Node secondNode = node(second, secondRegion, 1);
		NeutralPlacementGraph graph = new NeutralPlacementGraph(List.of(firstNode, secondNode), List.of(), List.of());
		LiteralOp firstHop = new LiteralOp(7L);
		LiteralOp secondHop = sharedConcreteHop ? firstHop : new LiteralOp(8L);
		List<HopOccurrenceProjection> occurrences = List.of(
			new HopOccurrenceProjection(first, firstHop, 1L, 0, first.normalizedSignature()),
			new HopOccurrenceProjection(second, secondHop, 2L, 1, second.normalizedSignature()));
		Map<CompiledHopKey, NodeShapeFact> shapes = Map.of(first,
			new NodeShapeFact(DataType.SCALAR, -1, -1), second,
			new NodeShapeFact(DataType.SCALAR, -1, -1));
		FixtureProgram program = new FixtureProgram();
		PlacementAnalysis analysis = new PlacementAnalysis(graph, occurrences, program,
			new PlacementShapeFacts(shapes, shapes.keySet()), fingerprint + "-analysis",
			new HeuristicPolicyFacts(List.of()));
		program.install(analysis);
		return new Fixture(program, analysis, firstHop, secondHop, first, second);
	}

	private static Node node(CompiledHopKey key, ControlRegionKey region, int ordinal) {
		ValueVersionKey value = new ValueVersionKey(key.programFingerprint(), "derived", region, ordinal,
			VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true, List.of(LOCAL, FED_FOUT), List.of(), List.of());
	}

	private static void primeDistinctMirrors(Fixture fixture) {
		for(Hop hop : List.of(fixture.firstHop(), fixture.secondHop())) {
			hop.setExecType(ExecType.CP);
			hop.clearForcedExecType();
			hop.setFederatedOutput(FederatedOutput.LOUT);
			hop.setFederatedOutputDerived(true);
		}
	}

	private static State snapshot(Fixture fixture) {
		return new State(hopState(fixture.firstHop()), hopState(fixture.secondHop()),
			FederatedRefedRegistry.snapshotAll(), FederatedFoutMaterializeRegistry.snapshotAll(),
			FederatedLocalMaterializeRegistry.snapshotAll(),
			PlacementEmissionTransaction.receiptSnapshotForTesting(),
			PlacementEmissionTransaction.observabilitySnapshot());
	}

	private static HopState hopState(Hop hop) {
		return new HopState(hop.getExecType(), hop.getForcedExecType(), hop.getFederatedOutput(),
			hop.isFederatedOutputDerived());
	}

	private static void seedRegistries() {
		FederatedRefedRegistry.register(9001L, 11L, 12L, "seed-anchor");
		FederatedFoutMaterializeRegistry.register(9002L, 21L, 22L, "ROW", "seed", "seed-anchor");
		FederatedLocalMaterializeRegistry.register(9003L, 31L, List.of(32L), "ROW", "seed");
	}

	private static void resetOwnedState() {
		PlacementEmissionTransaction.resetForTesting();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private interface EmissionAwareResult extends NormalizedPlannerResult {
		@SuppressWarnings("rawtypes")
		Map selectedEmissionStates();
	}

	private record Result(PlacementAnalysis analysis, Map<CompiledHopKey, PlacementState> selectedStates,
		Map<CompiledHopKey, Object> emissionStates, String normalizedPlanFingerprint)
		implements EmissionAwareResult {
		private Result {
			selectedStates = Map.copyOf(selectedStates);
			emissionStates = Map.copyOf(emissionStates);
		}
		@Override public String plannerId() { return "DERIVED_AUTHORITY_RED"; }
		@Override public String analysisFingerprint() { return analysis.analysisFingerprint(); }
		@Override public List<RelocationActionKey> selectedRelocations() { return List.of(); }
		@Override public String objectiveCertificate() { return "exact-derived-occurrence-authority"; }
		@Override @SuppressWarnings("rawtypes") public Map selectedEmissionStates() { return emissionStates; }
	}

	private record TestEmissionState(PlacementState placementState, boolean derivedFedFout) { }
	private record Fixture(FixtureProgram program, PlacementAnalysis analysis, Hop firstHop, Hop secondHop,
		CompiledHopKey first, CompiledHopKey second) { }
	private record HopState(ExecType execType, ExecType forcedExecType, FederatedOutput output,
		boolean derivedFedFout) { }
	private record State(HopState firstHop, HopState secondHop, FederatedRefedRegistry.Snapshot refed,
		FederatedFoutMaterializeRegistry.Snapshot fout, FederatedLocalMaterializeRegistry.Snapshot local,
		Map<DMLProgram, PlacementEmissionTransaction.PlacementEmissionReceipt> receipts,
		PlacementEmissionTransaction.ObservabilitySnapshot observability) { }

	private static final class FixtureProgram extends DMLProgram {
		private PlacementAnalysis authority;

		private void install(PlacementAnalysis analysis) {
			authority = analysis;
		}

		@Override
		public PlacementAnalysis requirePlacementAnalysisAuthority() {
			return authority;
		}

		@Override
		public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
			if(candidate == null || candidate != authority)
				throw new IllegalArgumentException("Placement analysis is not the canonical fixture owner");
		}
	}
}
