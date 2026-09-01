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
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailurePoint;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
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
	private static final PlacementState FED_LOUT =
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);

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
	public void graphOnlyDerivedBooleanWithoutExactActionRejectsBeforeMutation() {
		Fixture fixture = fixture(false);
		seedRegistries();
		State before = snapshot(fixture);
		EmissionAwareResult plan = result(fixture, FED_FOUT, true, true);
		Assert.assertThrows(IllegalStateException.class,
			() -> PlacementEmissionTransaction.emit(fixture.program(), plan, FailureInjector.none()));
		Assert.assertEquals("P4_MISSING_DERIVED_ACTION_REJECTS_BEFORE_MUTATION", before, snapshot(fixture));
	}

	@Test
	public void exactGraphOwnedDerivedActionWritesProducerFoutAndRollsBackAtomically() throws Exception {
		ExactFixture exact = exactFixture();
		var selected = exact.plan().selectedCandidateSelections().stream()
			.filter(candidate -> candidate.emission().emissionState().derivedFedFout())
			.filter(candidate -> exact.analysis().isCompiledHopOccurrence(candidate.rule().parentOccurrence()))
			.findFirst().orElseThrow();
		var action = selected.emission().derivedFoutAction();
		var producer = exact.analysis().occurrences().stream()
			.filter(occurrence -> occurrence.key() == action.producer()).findFirst().orElseThrow();
		var anchorOwner = exact.analysis().occurrences().stream()
			.filter(occurrence -> occurrence.key() == action.durableAnchorOwner()).findFirst().orElseThrow();

		PlacementEmissionTransaction.PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			exact.program(), exact.plan(), FailureInjector.none());
		var spec = FederatedFoutMaterializeRegistry.snapshot(producer.scopeId())
			.get(producer.hop().getHopID());
		Assert.assertTrue("P4_EXACT_DERIVED_ACTION_APPLIES", receipt.applied());
		Assert.assertNotNull("P4_DERIVED_ACTION_WRITES_PRODUCER_FOUT_SLOT", spec);
		Assert.assertEquals("P4_DERIVED_ACTION_USES_EXACT_COMPILED_ANCHOR_OWNER",
			anchorOwner.hop().getHopID(), spec.getAnchorHopId());
		Assert.assertEquals(action.materializationFType().name(), spec.getFTypeHint());
		Assert.assertEquals(action.durableAnchor().placementId(), spec.getAnchorLabel());
		Assert.assertEquals(ExactPlacementRegistration.runtimeAnchorKey(action.durableAnchor()),
			spec.getAnchorKey());

		resetOwnedState();
		ExactFixture rollback = exactFixture();
		StateExact before = snapshotExact(rollback);
		Assert.assertThrows(IllegalStateException.class,
			() -> PlacementEmissionTransaction.emit(rollback.program(), rollback.plan(),
				FailureInjector.failAt(FailurePoint.AFTER_FIRST_REGISTRY_WRITE)));
		Assert.assertEquals("P4_DERIVED_FOUT_REGISTRY_AND_HOPS_ROLL_BACK_ATOMICALLY",
			before, snapshotExact(rollback));
	}

	@Test
	public void structurallyEqualForeignDerivedActionRejectsBeforeMutation() throws Exception {
		ExactFixture exact = exactFixture();
		CandidateSelectionReceipt owned = exact.plan().selectedCandidateSelections().get(0);
		DerivedFoutMaterializationActionKey action = owned.emission().derivedFoutAction();
		DerivedFoutMaterializationActionKey foreignAction = new DerivedFoutMaterializationActionKey(
			action.producer(), action.producerValueVersion(), action.candidateRule(), action.sourcePlacement(),
			action.targetPlacement(), action.durableAnchor(), action.durableAnchorOwner(),
			action.durableAnchorOwnerFType(),
			action.materializationFType(),
			action.statementBlockScope());
		CandidateEmissionFact foreignEmission = new CandidateEmissionFact(
			owned.emission().emissionState(), owned.emission().executionFType(), foreignAction);
		CandidateSelectionReceipt foreignCandidate = new CandidateSelectionReceipt(
			owned.rule(), foreignEmission, List.of());
		ExactActionResult draft = new ExactActionResult(exact.analysis(), exact.plan().selectedStates(),
			exact.plan().selectedEmissionStates(), List.of(foreignCandidate), "unused");
		NormalizedPlannerResult foreign = new ExactActionResult(exact.analysis(), exact.plan().selectedStates(),
			exact.plan().selectedEmissionStates(), List.of(foreignCandidate),
			PlacementEmissionTransaction.canonicalPlanHash(draft));
		StateExact before = snapshotExact(exact);
		Assert.assertThrows(IllegalStateException.class,
			() -> PlacementEmissionTransaction.emit(exact.program(), foreign, FailureInjector.none()));
		Assert.assertEquals("foreign derived action must fail before Hop or registry mutation",
			before, snapshotExact(exact));
	}

	@Test
	public void partialCandidateValidationDefersOnlyAnUnassignedExactAnchorOwner() throws Exception {
		ExactFixture exact = exactFixture();
		CandidateSelectionReceipt selected = exact.plan().selectedCandidateSelections().get(0);
		Map<CompiledHopKey,PlacementState> partial = Map.of(
			selected.rule().parentOccurrence(), FED_FOUT);

		Assert.assertEquals("DP local recurrence must retain a derived-FOUT row whose durable owner "
			+ "is outside the currently assigned child closure", List.of(selected),
			CandidateSelections.resolveAndValidatePartial(exact.analysis(), exact.analysis().graph(),
				exact.analysis().graph().relocationActions(), partial, List.of(selected)));
		Assert.assertThrows("Complete-plan validation must still require the exact durable owner state",
			IllegalStateException.class, () -> CandidateSelections.resolveAndValidate(exact.analysis(),
				exact.analysis().graph(), exact.analysis().graph().relocationActions(), partial,
				List.of(selected)));
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
	public void sameConcreteHopDerivedBooleanStillRequiresPerOccurrenceExactActions() {
		Fixture fixture = fixture(true);
		EmissionAwareResult plan = result(fixture, FED_FOUT, true, true);
		Assert.assertThrows(IllegalStateException.class,
			() -> PlacementEmissionTransaction.emit(fixture.program(), plan, FailureInjector.none()));
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
	public void syntheticFunctionBoundaryDerivedStateDoesNotEmitDuplicateRegistryWrite() {
		SyntheticFixture fixture = syntheticFixture();
		PlacementEmissionTransaction.PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			fixture.program(), fixture.plan(), FailureInjector.none());
		Assert.assertTrue(receipt.applied());
		Assert.assertEquals("synthetic boundary owns no independent concrete Hop mutation", 0,
			receipt.hopMutations());
		Assert.assertEquals("synthetic boundary must reuse source authority without a duplicate FOUT write", 0,
			receipt.registryWrites());
		Assert.assertTrue(FederatedFoutMaterializeRegistry.isEmpty());
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

	private static ExactFixture exactFixture() throws Exception {
		String fingerprint = "exact-derived-action-red";
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb-1"),
			"main", "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled", region,
			"derived-producer@sb-1", "derived-producer");
		CompiledHopKey anchorKey = new CompiledHopKey(fingerprint, "main", "main", "compiled", region,
			"anchor-owner@sb-1", "anchor-owner");
		ValueVersionKey value = new ValueVersionKey(fingerprint, "derived", region, 0,
			VersionKind.ORDINARY, List.of());
		ValueVersionKey anchorValue = new ValueVersionKey(fingerprint, "anchor", region, 0,
			VersionKind.ORDINARY, List.of());
		DurableAnchorKey anchor = new DurableAnchorKey("exact-anchor", FType.ROW,
			List.of(new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
		CandidateRuleKey rule = new CandidateRuleKey(key, List.of());
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			key, value, rule, FED_LOUT, FED_FOUT, anchor, anchorKey, FType.ROW, FType.ROW,
			region.normalizedSignature());
		CandidateEmissionFact nativeEmission = new CandidateEmissionFact(
			new PlacementEmissionState(FED_LOUT, false), FType.ROW);
		CandidateEmissionFact emission = new CandidateEmissionFact(
			new PlacementEmissionState(FED_FOUT, true), FType.ROW, action);
		CandidateRuleFact fact = new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(
				org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory.OTHER, "derived-fixture",
				ExecType.FED, FederatedOutput.LOUT, null,
				org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.OK, "exact-derived", List.of()),
			new CandidateShapeProofFact(Map.of("fixture", "exact"), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(nativeEmission, emission), "");
		Node node = new Node(key, NodeKind.OPERATION, value, true,
			List.of(LOCAL, FED_LOUT, FED_FOUT), List.of(), List.of());
		Node anchorNode = new Node(anchorKey, NodeKind.OPERATION, anchorValue, true,
			List.of(FED_FOUT), List.of(), List.of(anchor));
		NeutralPlacementGraph graph = new NeutralPlacementGraph(List.of(anchorNode, node), List.of(), List.of(),
			List.of(new NeutralPlacementGraph.DerivedFoutMaterializationAction(action)));
		LiteralOp hop = new LiteralOp(7L);
		LiteralOp anchorHop = new LiteralOp(8L);
		FixtureProgram program = new FixtureProgram();
		PlacementAnalysis analysis = new PlacementAnalysis(graph,
			List.of(new HopOccurrenceProjection(anchorKey, anchorHop, 1L, 0, anchorKey.normalizedSignature()),
				new HopOccurrenceProjection(key, hop, 1L, 1, key.normalizedSignature())), program,
			new PlacementShapeFacts(Map.of(anchorKey, new NodeShapeFact(DataType.SCALAR, -1, -1),
				key, new NodeShapeFact(DataType.SCALAR, -1, -1)), Set.of(anchorKey, key)),
			fingerprint + "-analysis", new HeuristicPolicyFacts(List.of()),
			List.of(rule), List.of(fact), List.of(), List.of());
		program.install(analysis);
		CandidateSelectionReceipt candidate = new CandidateSelectionReceipt(rule, emission, List.of());
		Map<CompiledHopKey,PlacementState> states = Map.of(anchorKey, FED_FOUT, key, FED_FOUT);
		Map<CompiledHopKey,PlacementEmissionState> emissions = Map.of(
			anchorKey, new PlacementEmissionState(FED_FOUT, false), key, emission.emissionState());
		ExactActionResult draft = new ExactActionResult(analysis, states,
			emissions, List.of(candidate), "unused");
		NormalizedPlannerResult plan = new ExactActionResult(analysis, states,
			emissions, List.of(candidate),
			PlacementEmissionTransaction.canonicalPlanHash(draft));
		return new ExactFixture(program, analysis, plan);
	}

	private static StateExact snapshotExact(ExactFixture fixture) {
		Map<Long, HopState> hops = new LinkedHashMap<>();
		fixture.analysis().occurrences().forEach(occurrence ->
			hops.put(occurrence.hop().getHopID(), hopState(occurrence.hop())));
		return new StateExact(Map.copyOf(hops), FederatedRefedRegistry.snapshotAll(),
			FederatedFoutMaterializeRegistry.snapshotAll(), FederatedLocalMaterializeRegistry.snapshotAll(),
			PlacementEmissionTransaction.receiptSnapshotForTesting(),
			PlacementEmissionTransaction.observabilitySnapshot());
	}

	private static SyntheticFixture syntheticFixture() {
		String fingerprint = "synthetic-derived-boundary-red";
		ControlRegionKey region = new ControlRegionKey(fingerprint, "f",
			List.of("function-boundary:f:output"), "f", "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "f", "main", "compiled", region,
			"synthetic-output", "synthetic-output");
		ValueVersionKey value = new ValueVersionKey(fingerprint, "synthetic", region, 0,
			VersionKind.FUNCTION_OUTPUT, List.of());
		Node node = new Node(key, NodeKind.FUNCTION_OUTPUT, value, true,
			List.of(FED_FOUT), List.of(), List.of());
		NeutralPlacementGraph graph = new NeutralPlacementGraph(List.of(node), List.of(), List.of());
		LiteralOp hop = new LiteralOp(9L);
		FixtureProgram program = new FixtureProgram();
		PlacementAnalysis analysis = new PlacementAnalysis(graph,
			List.of(new HopOccurrenceProjection(key, hop, 1L, 0, key.normalizedSignature())), program,
			new PlacementShapeFacts(Map.of(key, new NodeShapeFact(DataType.SCALAR, -1, -1)), Set.of(key)),
			fingerprint + "-analysis", new HeuristicPolicyFacts(List.of()));
		program.install(analysis);
		ExactActionResult draft = new ExactActionResult(analysis, Map.of(key, FED_FOUT),
			Map.of(key, new PlacementEmissionState(FED_FOUT, true)), List.of(), "unused");
		NormalizedPlannerResult plan = new ExactActionResult(analysis, Map.of(key, FED_FOUT),
			Map.of(key, new PlacementEmissionState(FED_FOUT, true)), List.of(),
			PlacementEmissionTransaction.canonicalPlanHash(draft));
		return new SyntheticFixture(program, plan);
	}

	private static HopState hopState(Hop hop) {
		return new HopState(hop.getExecType(), hop.getForcedExecType(), hop.getFederatedOutput(),
			hop.isFederatedOutputDerived());
	}

	private static void seedRegistries() {
		FederatedRefedRegistry.register(9001L, 11L, 12L, "seed-anchor", java.util.List.of(13L));
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
	private record ExactFixture(DMLProgram program, PlacementAnalysis analysis,
		NormalizedPlannerResult plan) { }
	private record SyntheticFixture(DMLProgram program, NormalizedPlannerResult plan) { }
	private record ExactActionResult(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selectedStates,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<CandidateSelectionReceipt> selectedCandidateSelections,
		String normalizedPlanFingerprint) implements NormalizedPlannerResult {
		@Override public String plannerId() { return "DERIVED_ACTION_EXACT"; }
		@Override public String analysisFingerprint() { return analysis.analysisFingerprint(); }
		@Override public List<RelocationActionKey> selectedRelocations() { return List.of(); }
		@Override public String objectiveCertificate() { return "exact-derived-action"; }
	}
	private record StateExact(Map<Long, HopState> hops, FederatedRefedRegistry.Snapshot refed,
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
