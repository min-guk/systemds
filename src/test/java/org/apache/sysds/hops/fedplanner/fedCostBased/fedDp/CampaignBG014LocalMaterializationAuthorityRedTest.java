/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailurePoint;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.ObservabilitySnapshot;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/** Executable Task33 RED: selected local materialization authority is canonical, exact, and fail-closed. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CampaignBG014LocalMaterializationAuthorityRedTest {
	private static final PlacementState FED_FOUT_ROW = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);
	private static final PlacementState FED_FOUT_COL = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.COL, true);
	private static final PlacementState CP_LOUT = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final long SEED_SCOPE = 77033L;
	private Fixture fixture;

	@Before
	public void setUp() throws Exception {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
		fixture = fixture();
	}

	@After
	public void tearDown() {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
	}

	@Test
	public void a00_selectedLocalAuthorityChangesTheCanonicalPlanHashBeforeTypedApiContract() {
		EmissionAwareResult withoutLocal = fixture.result(List.of());
		EmissionAwareResult withLocal = fixture.result(List.of(fixture.localAction(fixture.scopeText())));

		Assert.assertNotEquals("TASK33_SELECTED_LOCAL_AUTHORITY_MUST_PARTICIPATE_IN_CANONICAL_HASH",
			PlacementEmissionTransaction.canonicalPlanHash(withoutLocal),
			PlacementEmissionTransaction.canonicalPlanHash(withLocal));

		assertFutureTypedLocalMaterializationApi(withLocal);
	}

	@Test
	public void selectedLocalAuthorityEmitsOneExactSortedRegistryEntry() {
		EmissionAwareResult result = fixture.result(List.of(fixture.localAction(fixture.scopeText())));
		FederatedLocalMaterializeRegistry.Snapshot before = FederatedLocalMaterializeRegistry.snapshotAll();
		PlacementEmissionTransaction.emit(fixture.program(), result, FailureInjector.none());

		FederatedLocalMaterializeRegistry.LocalMaterializeSpec expectedSpec =
			new FederatedLocalMaterializeRegistry.LocalMaterializeSpec(fixture.consumerHopIds(),
				fixture.producerFedFout().fType().name(), fixture.durableProvenance());
		Map<Long, Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec>> expectedScopes =
			new LinkedHashMap<>(before.scopes());
		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> expectedScopeEntries =
			new LinkedHashMap<>(expectedScopes.getOrDefault(fixture.scopeId(), Map.of()));
		expectedScopeEntries.put(fixture.producer().hop().getHopID(), expectedSpec);
		expectedScopes.put(fixture.scopeId(), Map.copyOf(expectedScopeEntries));
		Assert.assertEquals("TASK33_LOCAL_SNAPSHOTALL_HAS_ONLY_EXPECTED_SCOPE_AND_SOURCE_DELTA",
			new FederatedLocalMaterializeRegistry.Snapshot(expectedScopes),
			FederatedLocalMaterializeRegistry.snapshotAll());

		FederatedLocalMaterializeRegistry.LocalMaterializeSpec spec =
			FederatedLocalMaterializeRegistry.snapshotAll().scopes().get(fixture.scopeId())
				.get(fixture.producer().hop().getHopID());
		Assert.assertEquals("TASK33_LOCAL_ENTRY_SOURCE_IS_EXACT_PRODUCER", expectedSpec, spec);
		Assert.assertEquals("TASK33_LOCAL_ENTRY_SCOPE_IS_EXACT", fixture.scopeId(), fixture.producer().scopeId());
		Assert.assertEquals("TASK33_LOCAL_ENTRY_CONSUMERS_ARE_EXACT_SORTED",
			fixture.consumerHopIds(), spec.getConsumerHopIds());
		Assert.assertEquals("TASK33_LOCAL_ENTRY_FTYPE_IS_DERIVED_FROM_PRODUCER_PLACEMENT",
			fixture.producerFedFout().fType().name(), spec.getFTypeHint());
		Assert.assertEquals("TASK33_LOCAL_ENTRY_REASON_IS_DURABLE_PROVENANCE",
			fixture.durableProvenance(), spec.getReason());
	}

	@Test
	public void invalidLocalMaterializationAuthorityRejectsBeforeAnyOwnedMutation() {
		TestLocalMaterializationAction valid = fixture.canonicalLocalAction(fixture.scopeText());
		Object selectedValidAction = futureTypedActionOr(valid);
		EmissionAwareResult validResult = fixture.result(List.of(selectedValidAction));
		EmissionAwareResult fedConsumerResult = fixture.result(List.of(selectedValidAction),
			Map.of(fixture.firstConsumer().key(), fixture.firstConsumerFed()));
		assertFedConsumerDiffersOnlyByOneSelectedStateOverride(validResult, fedConsumerResult, selectedValidAction);
		List<NamedResult> invalid = List.of(
			invalidAction("producer-lout", valid, fixture.canonicalLocalAction(fixture.scopeText(), CP_LOUT,
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value(), fixture.durableProvenance())),
			new NamedResult("fed-consumer", fedConsumerResult),
			invalidAction("null-ftype", valid, fixture.canonicalLocalAction(fixture.scopeText(),
				new PlacementState(ExecType.FED, FederatedOutput.FOUT, null, true),
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value(), fixture.durableProvenance())),
			invalidAction("required-placement-mismatch", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(new TestLocalMaterializationObligation(fixture.firstConsumer().key(), 0, fixture.firstConsumerFed()),
					consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value(), fixture.durableProvenance())),
			invalidAction("empty-obligations", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(), fixture.producer().key(), fixture.producer().value(), fixture.durableProvenance())),
			invalidAction("partial-obligations", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(consumerObligation(fixture.firstConsumer(), 0)), fixture.producer().key(), fixture.producer().value(), fixture.durableProvenance())),
			invalidAction("duplicate-obligations", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.firstConsumer(), 0),
					consumerObligation(fixture.secondConsumer(), 0)), fixture.producer().key(), fixture.producer().value(), fixture.durableProvenance())),
			invalidAction("blank-scope", valid, fixture.canonicalLocalAction(" ")),
			invalidAction("mismatched-scope", valid, fixture.canonicalLocalAction((fixture.scopeId() + 1) + ":main")),
			invalidAction("source-value-version-mismatch", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.firstConsumer().value(), fixture.durableProvenance())),
			invalidAction("durable-provenance-mismatch", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value(), "fed-init:Y")),
			invalidAction("blank-durable-provenance", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value(), " ")),
			invalidAction("wrong-input-position", valid, fixture.canonicalLocalAction(fixture.scopeText(), fixture.producerFedFout(),
				List.of(consumerObligation(fixture.firstConsumer(), 1), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value(), fixture.durableProvenance())),
			invalidAction("foreign-occurrence", valid, fixture.foreignOccurrenceAction()),
			invalidAction("same-program-unselected-virtual-clone", valid, fixture.virtualCloneAction())
		);
		for(NamedResult candidate : invalid)
			assertRejectsBeforeMutation(candidate.name(), candidate.result());
	}

	@Test
	public void scopeAndFTypeDistinctLocalActionsRemainDistinct() {
		Fixture row = fixture;
		Fixture col;
		try { col = fixture(FType.COL); }
		catch(Exception failure) { throw new AssertionError("TASK33_FIXTURE_REQUIRES_LEGAL_COL_LOCAL_ACTION", failure); }
		Assert.assertNotEquals("TASK33_ROW_COL_SCOPES_MUST_BE_PROVABLY_DISTINCT", row.scopeId(), col.scopeId());
		TestLocalMaterializationAction rowAction = row.canonicalLocalAction(row.scopeText());
		TestLocalMaterializationAction colAction = col.canonicalLocalAction(col.scopeText());
		Assert.assertEquals("TASK33_ROW_ACTION_KEEPS_MATCHING_FTYPE", FType.ROW, rowAction.fType());
		Assert.assertEquals("TASK33_COL_ACTION_KEEPS_MATCHING_FTYPE", FType.COL, colAction.fType());
		Assert.assertNotEquals("TASK33_SCOPE_AND_FTYPE_IDENTITY_MAKE_ACTIONS_DISTINCT", rowAction, colAction);
		Assert.assertEquals("TASK33_DISTINCT_CANONICAL_ACTIONS_SURVIVE_SET_IDENTITY", 2,
			Set.of(rowAction, colAction).size());

		PlacementEmissionTransaction.emit(row.program(),
			row.result(List.of(futureTypedActionOr(rowAction))), FailureInjector.none());
		Assert.assertEquals("TASK33_SCOPE_DISTINCT_ACTION_SURVIVES", 1,
			FederatedLocalMaterializeRegistry.snapshotAll().scopes().getOrDefault(row.scopeId(), Map.of()).size());
		Assert.assertEquals("TASK33_ROW_FIXTURE_KEEPS_MATCHING_FTYPE", FType.ROW.name(),
			FederatedLocalMaterializeRegistry.snapshotAll().scopes().get(row.scopeId())
				.get(row.producer().hop().getHopID()).getFTypeHint());

		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
		PlacementEmissionTransaction.emit(col.program(),
			col.result(List.of(futureTypedActionOr(colAction))), FailureInjector.none());
		Assert.assertEquals("TASK33_FTYPE_DISTINCT_ACTION_SURVIVES", 1,
			FederatedLocalMaterializeRegistry.snapshotAll().scopes().getOrDefault(col.scopeId(), Map.of()).size());
		Assert.assertEquals("TASK33_COL_FIXTURE_KEEPS_MATCHING_FTYPE", FType.COL.name(),
			FederatedLocalMaterializeRegistry.snapshotAll().scopes().get(col.scopeId())
				.get(col.producer().hop().getHopID()).getFTypeHint());
	}

	@Test
	public void injectedFailureRestoresHopsAllRegistriesReceiptAndObservability() {
		seedRegistries();
		primeHopMirrors(fixture.analysis());
		StateSnapshot beforeHopFailure = snapshot(fixture.analysis());
		assertThrowsAny(() -> PlacementEmissionTransaction.emit(fixture.program(),
			fixture.result(List.of(fixture.localAction(fixture.scopeText()))),
			FailureInjector.failAt(FailurePoint.AFTER_FIRST_HOP_MUTATION)));
		Assert.assertEquals("TASK33_ROLLBACK_AFTER_HOP_MUTATION", beforeHopFailure, snapshot(fixture.analysis()));

		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
		seedRegistries();
		primeHopMirrors(fixture.analysis());
		StateSnapshot beforeRegistryFailure = snapshot(fixture.analysis());
		assertThrowsAny(() -> PlacementEmissionTransaction.emit(fixture.program(),
			fixture.result(List.of(fixture.localAction(fixture.scopeText()))),
			FailureInjector.failAt(FailurePoint.AFTER_FIRST_REGISTRY_WRITE)));
		Assert.assertEquals("TASK33_ROLLBACK_AFTER_LOCAL_REGISTRY_WRITE", beforeRegistryFailure,
			snapshot(fixture.analysis()));
	}

	private static Fixture fixture() throws Exception {
		return fixture(FType.ROW);
	}

	private static Fixture fixture(FType fType) throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileProgram(fType));
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Occurrence producer = occurrence(analysis, hop -> hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
			&& "X".equals(data.getName()), "producer X");
		List<Occurrence> consumers = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == producer.key()).map(edge -> occurrence(analysis, edge.consumer()))
			.filter(o -> isBinaryConsumer(o.hop())).distinct().toList();
		Assert.assertEquals("TASK33_FIXTURE_REQUIRES_TWO_EXACT_CONSUMERS", 2, consumers.size());
		for(Occurrence consumer : consumers)
			Assert.assertTrue("TASK33_FIXTURE_CONSUMER_MUST_ACCEPT_CP_LOUT",
				node(analysis, consumer.key()).legalAlternatives().contains(CP_LOUT));
		PlacementState producerFedFout = node(analysis, producer.key()).legalAlternatives().stream()
			.filter(s -> s.execType() == ExecType.FED && s.output() == FederatedOutput.FOUT && s.fType() == fType)
			.findFirst().orElseThrow(() -> new AssertionError("TASK33_FIXTURE_PRODUCER_MUST_ACCEPT_FED_FOUT_" + fType));
		PlacementState firstConsumerFed = node(analysis, consumers.get(0).key()).legalAlternatives().stream()
			.filter(s -> s.execType() == ExecType.FED).findFirst()
			.orElseThrow(() -> new AssertionError("TASK33_FIXTURE_CONSUMER_MUST_HAVE_LEGAL_FED_ALTERNATIVE"));
		program.install(analysis);
		return new Fixture(program, analysis, producer, consumers.get(0), consumers.get(1), producerFedFout,
			firstConsumerFed, fType);
	}

	private static boolean isBinaryConsumer(Hop hop) {
		return hop.getInput().stream().anyMatch(input -> input instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
			&& "X".equals(data.getName()));
	}

	private static DMLProgram compileProgram(FType fType) throws Exception {
		String ranges = fType == FType.COL ? "list(list(0,0),list(4,2),list(0,2),list(4,4))"
			: "list(list(0,0),list(2,2),list(2,0),list(4,2))";
		int cols = fType == FType.COL ? 4 : 2;
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=" + ranges + ");\n"
			+ "S1=rand(rows=4,cols=" + cols + ",seed=7);\n"
			+ "S2=rand(rows=4,cols=" + cols + ",seed=8);\n"
			+ "Y1=X+S1;\nY2=X-S2;\n"
			+ "write(Y1,\"/tmp/g005-task33-y1\",format=\"binary\");\n"
			+ "write(Y2,\"/tmp/g005-task33-y2\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static Node node(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.graph().node(key).orElseThrow(() -> new AssertionError("missing node"));
	}

	private static Occurrence occurrence(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.occurrences().stream().filter(o -> o.key() == key)
			.map(o -> new Occurrence(o, node(analysis, o.key()).valueVersion())).findFirst().orElseThrow();
	}

	private static Occurrence occurrence(PlacementAnalysis analysis, java.util.function.Predicate<Hop> predicate,
		String label) {
		return analysis.occurrences().stream().filter(o -> predicate.test(o.hop()))
			.map(o -> new Occurrence(o, node(analysis, o.key()).valueVersion())).findFirst()
			.orElseThrow(() -> new AssertionError("missing " + label));
	}

	private static TestLocalMaterializationObligation consumerObligation(Occurrence consumer, int inputPosition) {
		return new TestLocalMaterializationObligation(consumer.key(), inputPosition, CP_LOUT);
	}

	private static void assertRejectsBeforeMutation(String label, EmissionAwareResult result) {
		StateSnapshot before = snapshot(result.analysis());
		assertThrowsAny(() -> PlacementEmissionTransaction.emit(((FixtureProgram) result.programOwner()), result,
			FailureInjector.none()), "TASK33_INVALID_LOCAL_AUTHORITY_MUST_REJECT|" + label);
		Assert.assertEquals("TASK33_INVALID_LOCAL_AUTHORITY_MUTATED|" + label, before, snapshot(result.analysis()));
	}

	private NamedResult invalidAction(String label, TestLocalMaterializationAction valid,
		TestLocalMaterializationAction candidate) {
		assertOneActionDefect(label, valid, candidate);
		return new NamedResult(label, fixture.result(List.of(futureTypedActionOr(candidate))));
	}

	private static void assertFedConsumerDiffersOnlyByOneSelectedStateOverride(EmissionAwareResult valid,
		EmissionAwareResult candidate, Object identicalAction) {
		Assert.assertEquals("TASK33_FED_CONSUMER_NEGATIVE_USES_IDENTICAL_VALID_ACTION",
			List.of(identicalAction), candidate.selectedLocalMaterializations());
		Assert.assertEquals("TASK33_FED_CONSUMER_NEGATIVE_STARTS_FROM_VALID_ACTION",
			valid.selectedLocalMaterializations(), candidate.selectedLocalMaterializations());
		int deltas = 0;
		for(CompiledHopKey key : valid.selectedStates().keySet())
			deltas += Objects.equals(valid.selectedStates().get(key), candidate.selectedStates().get(key)) ? 0 : 1;
		Assert.assertEquals("TASK33_FED_CONSUMER_NEGATIVE_HAS_EXACTLY_ONE_SELECTED_STATE_DEFECT", 1, deltas);
	}

	private static void assertOneActionDefect(String label, TestLocalMaterializationAction expected,
		TestLocalMaterializationAction actual) {
		int deltas = 0;
		deltas += Objects.equals(expected.statementBlockScope(), actual.statementBlockScope()) ? 0 : 1;
		deltas += expected.sourceOccurrence() == actual.sourceOccurrence() ? 0 : 1;
		deltas += Objects.equals(expected.sourceValueVersion(), actual.sourceValueVersion()) ? 0 : 1;
		deltas += Objects.equals(expected.producerPlacement(), actual.producerPlacement()) ? 0 : 1;
		deltas += Objects.equals(expected.obligations(), actual.obligations()) ? 0 : 1;
		deltas += Objects.equals(expected.durableProvenance(), actual.durableProvenance()) ? 0 : 1;
		Assert.assertEquals("TASK33_INVALID_ACTION_MUST_HAVE_ONE_NAMED_DEFECT|" + label, 1, deltas);
	}

	private static void assertThrowsAny(Runnable action) {
		assertThrowsAny(action, "expected failure");
	}

	private static void assertThrowsAny(Runnable action, String message) {
		try {
			action.run();
			Assert.fail(message);
		}
		catch(AssertionError failure) {
			throw failure;
		}
		catch(RuntimeException expected) {
			// exact exception type is deliberately not the RED contract
		}
	}

	private static void primeHopMirrors(PlacementAnalysis analysis) {
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			occurrence.hop().setExecType(ExecType.CP);
			occurrence.hop().clearForcedExecType();
			occurrence.hop().setFederatedOutput(FederatedOutput.LOUT);
			occurrence.hop().setFederatedOutputDerived(true);
		}
	}

	private static StateSnapshot snapshot(PlacementAnalysis analysis) {
		Map<Long, HopSnapshot> hops = new LinkedHashMap<>();
		analysis.occurrences().stream().map(HopOccurrenceProjection::hop)
			.sorted(java.util.Comparator.comparingLong(Hop::getHopID)).forEach(hop -> hops.put(hop.getHopID(),
				new HopSnapshot(hop.getExecType(), hop.getForcedExecType(), hop.getFederatedOutput(),
					hop.isFederatedOutputDerived())));
		return new StateSnapshot(Map.copyOf(hops), FederatedRefedRegistry.snapshotAll(),
			FederatedFoutMaterializeRegistry.snapshotAll(), FederatedLocalMaterializeRegistry.snapshotAll(),
			Map.copyOf(PlacementEmissionTransaction.receiptSnapshotForTesting()),
			PlacementEmissionTransaction.observabilitySnapshot());
	}

	private static void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private static void seedRegistries() {
		FederatedRefedRegistry.register(SEED_SCOPE, 11L, 12L, "seed-anchor");
		FederatedFoutMaterializeRegistry.register(SEED_SCOPE, 13L, 14L, "ROW", "seed", "seed-anchor");
		FederatedLocalMaterializeRegistry.register(SEED_SCOPE, 15L, List.of(16L), "COL", "seed-local");
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(64);
			for(byte b : digest) out.append(String.format("%02x", b));
			return out.toString();
		}
		catch(Exception failure) {
			throw new IllegalStateException("SHA-256 unavailable", failure);
		}
	}

	private static String canonicalHashIncludingLocal(EmissionAwareResult result) {
		String base = PlacementEmissionTransaction.canonicalPlanHash(result);
		List<String> locals = result.selectedLocalMaterializations().stream()
			.map(Object::toString).sorted().toList();
		return sha256(base + "\nLOCAL\n" + String.join("\n", locals));
	}

	private record Fixture(FixtureProgram program, PlacementAnalysis analysis, Occurrence producer,
		Occurrence firstConsumer, Occurrence secondConsumer, PlacementState producerFedFout,
		PlacementState firstConsumerFed, FType fixtureFType) {
		private EmissionAwareResult result(List<Object> actions) {
			return result(actions, Map.of());
		}
		private EmissionAwareResult result(List<Object> actions, Map<CompiledHopKey, PlacementState> selectedOverrides) {
			Map<CompiledHopKey, PlacementState> selected = new LinkedHashMap<>();
			for(Node n : analysis.graph().decisionNodes()) {
				PlacementState state = n.key() == producer.key() ? producerFedFout
					: n.key() == firstConsumer.key() || n.key() == secondConsumer.key() ? CP_LOUT
					: n.legalAlternatives().contains(CP_LOUT) ? CP_LOUT : n.legalAlternatives().get(0);
				if(selectedOverrides.containsKey(n.key()))
					state = selectedOverrides.get(n.key());
				Assert.assertTrue("TASK33_FIXTURE_SELECTION_MUST_BE_LEGAL", n.legalAlternatives().contains(state));
				selected.put(n.key(), state);
			}
			EmissionAwareResult draft = new EmissionAwareResult(program, analysis, "task33-red", analysis.analysisFingerprint(),
				selected, List.of(), "task33-canonical-3-node-local-materialization", "pending", actions);
			return new EmissionAwareResult(program, analysis, draft.plannerId(), draft.analysisFingerprint(), selected,
				draft.selectedRelocations(), draft.objectiveCertificate(), PlacementEmissionTransaction.canonicalPlanHash(draft), actions);
		}
		private String scopeText() { return producer.scopeId() + ":main"; }
		private long scopeId() { return producer.scopeId(); }
		private List<Long> consumerHopIds() {
			return List.of(firstConsumer.hop().getHopID(), secondConsumer.hop().getHopID()).stream().sorted().toList();
		}
		private String durableProvenance() { return "fed-init:X"; }
		private Object localAction(String scopeText) {
			return futureTypedActionOr(canonicalLocalAction(scopeText));
		}
		private TestLocalMaterializationAction canonicalLocalAction(String scopeText) {
			return canonicalLocalAction(scopeText, producerFedFout,
				List.of(consumerObligation(firstConsumer, 0), consumerObligation(secondConsumer, 0)),
				producer.key(), producer.value(), durableProvenance());
		}
		private TestLocalMaterializationAction canonicalLocalAction(String scopeText,
			PlacementState producerState, List<TestLocalMaterializationObligation> obligations,
			CompiledHopKey source, ValueVersionKey value, String durableProvenance) {
			return new TestLocalMaterializationAction(source, value, producerState, obligations, scopeText, durableProvenance);
		}
		private TestLocalMaterializationAction foreignOccurrenceAction() {
			ControlRegionKey foreignRegion = new ControlRegionKey(producer.key().programFingerprint() + "|foreign",
				producer.key().functionNamespace(), producer.key().controlRegion().regionPath(),
				producer.key().callSitePath(), producer.key().recompileContext());
			CompiledHopKey foreign = new CompiledHopKey(foreignRegion.programFingerprint(),
				producer.key().functionNamespace(), producer.key().callSitePath(), producer.key().recompileContext(),
				foreignRegion, producer.key().emittedHopInstance(), producer.key().canonicalSourceOrigin());
			Assert.assertNotEquals("TASK33_FOREIGN_OCCURRENCE_MUST_BE_PROVABLY_UNSELECTED",
				producer.key().normalizedSignature(), foreign.normalizedSignature());
			return canonicalLocalAction(scopeText(), producerFedFout,
				List.of(consumerObligation(firstConsumer, 0), consumerObligation(secondConsumer, 0)),
				foreign, producer.value(), durableProvenance());
		}
		private TestLocalMaterializationAction virtualCloneAction() {
			CompiledHopKey clone = new CompiledHopKey(producer.key().programFingerprint(), producer.key().functionNamespace(),
				producer.key().callSitePath(), producer.key().recompileContext(), producer.key().controlRegion(),
				producer.key().emittedHopInstance() + "|virtual-clone", producer.key().canonicalSourceOrigin());
			return canonicalLocalAction(scopeText(), producerFedFout,
				List.of(consumerObligation(firstConsumer, 0), consumerObligation(secondConsumer, 0)), clone, producer.value(), durableProvenance());
		}
	}

	public record TestLocalMaterializationObligation(CompiledHopKey consumerOccurrence, int inputPosition,
		PlacementState requiredPlacement) {
		public TestLocalMaterializationObligation {
			Objects.requireNonNull(consumerOccurrence, "consumerOccurrence");
			Objects.requireNonNull(requiredPlacement, "requiredPlacement");
		}
	}

	public record TestLocalMaterializationAction(CompiledHopKey sourceOccurrence, ValueVersionKey sourceValueVersion,
		PlacementState producerPlacement, List<TestLocalMaterializationObligation> obligations,
		String statementBlockScope, String durableProvenance) {
		public TestLocalMaterializationAction {
			Objects.requireNonNull(sourceOccurrence, "sourceOccurrence");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(producerPlacement, "producerPlacement");
			obligations = List.copyOf(Objects.requireNonNull(obligations, "obligations"));
		}
		private FType fType() { return producerPlacement.fType(); }
	}

	/** Current NormalizedPlannerResult plus the Task33 raw authority surface intentionally ignored by current production. */
	public static final class EmissionAwareResult implements NormalizedPlannerResult {
		private final DMLProgram programOwner;
		private final PlacementAnalysis analysis;
		private final String plannerId;
		private final String analysisFingerprint;
		private final Map<CompiledHopKey, PlacementState> selectedStates;
		private final List<RelocationActionKey> selectedRelocations;
		private final String objectiveCertificate;
		private final String normalizedPlanFingerprint;
		private final List<Object> selectedLocalMaterializations;

		private EmissionAwareResult(DMLProgram programOwner, PlacementAnalysis analysis, String plannerId,
			String analysisFingerprint, Map<CompiledHopKey, PlacementState> selectedStates,
			List<RelocationActionKey> selectedRelocations, String objectiveCertificate,
			String normalizedPlanFingerprint, List<Object> selectedLocalMaterializations) {
			this.programOwner = Objects.requireNonNull(programOwner, "programOwner");
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.plannerId = plannerId;
			this.analysisFingerprint = analysisFingerprint;
			this.selectedStates = Map.copyOf(selectedStates);
			this.selectedRelocations = List.copyOf(selectedRelocations);
			this.objectiveCertificate = objectiveCertificate;
			this.normalizedPlanFingerprint = normalizedPlanFingerprint;
			this.selectedLocalMaterializations = List.copyOf(selectedLocalMaterializations);
		}
		public DMLProgram programOwner() { return programOwner; }
		@Override public PlacementAnalysis analysis() { return analysis; }
		@Override public String plannerId() { return plannerId; }
		@Override public String analysisFingerprint() { return analysisFingerprint; }
		@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return selectedStates; }
		@Override public List<RelocationActionKey> selectedRelocations() { return selectedRelocations; }
		@Override public String objectiveCertificate() { return objectiveCertificate; }
		@Override public String normalizedPlanFingerprint() { return normalizedPlanFingerprint; }
		public List<Object> selectedLocalMaterializations() { return selectedLocalMaterializations; }
	}

	private static void assertFutureTypedLocalMaterializationApi(EmissionAwareResult result) {
		try {
			java.lang.reflect.Method method = NormalizedPlannerResult.class.getMethod("selectedLocalMaterializations");
			Assert.assertEquals("TASK33_SELECTED_LOCAL_MATERIALIZATIONS_API_RETURNS_LIST",
				List.class, method.getReturnType());
			Assert.assertFalse("TASK33_SELECTED_LOCAL_MATERIALIZATIONS_API_IS_ADDITIVE_AND_NONEMPTY",
				((List<?>) method.invoke(result)).isEmpty());
			Class<?> actionType = Class.forName(
				"org.apache.sysds.hops.fedplanner.placement.PlacementIdentity$LocalMaterializationActionKey");
			Class<?> obligationType = Class.forName(
				"org.apache.sysds.hops.fedplanner.placement.PlacementIdentity$LocalMaterializationObligation");
			assertRecordComponents(obligationType, new ExpectedComponent("consumerOccurrence", CompiledHopKey.class),
				new ExpectedComponent("inputPosition", int.class),
				new ExpectedComponent("requiredPlacement", PlacementState.class));
			assertRecordComponents(actionType, new ExpectedComponent("sourceOccurrence", CompiledHopKey.class),
				new ExpectedComponent("sourceValueVersion", ValueVersionKey.class),
				new ExpectedComponent("producerPlacement", PlacementState.class),
				new ExpectedComponent("obligations", List.class),
				new ExpectedComponent("statementBlockScope", String.class),
				new ExpectedComponent("durableProvenance", String.class));
			for(Object action : result.selectedLocalMaterializations()) {
				Assert.assertTrue("TASK33_LOCAL_ACTION_MUST_BE_REAL_PRODUCTION_TYPED_RECORD",
					actionType.isInstance(action));
				Assert.assertFalse("TASK33_LOCAL_ACTION_MUST_NOT_CARRY_DUPLICATE_STANDALONE_FTYPE",
					List.of(actionType.getRecordComponents()).stream().anyMatch(c -> c.getName().equals("fType")));
				Object obligations = actionType.getMethod("obligations").invoke(action);
				Assert.assertTrue("TASK33_LOCAL_ACTION_OBLIGATIONS_MUST_BE_REAL_PRODUCTION_TYPED_RECORDS",
					((List<?>) obligations).stream().allMatch(obligationType::isInstance));
			}
		}
		catch(ReflectiveOperationException | LinkageError missingFutureContract) {
			throw new AssertionError("TASK33_REQUIRES_TYPED_LOCAL_MATERIALIZATION_API_AFTER_HASH_IS_CANONICAL",
				missingFutureContract);
		}
	}

	private static void assertRecordComponents(Class<?> recordType, ExpectedComponent... expected) {
		Assert.assertTrue("TASK33_LOCAL_AUTHORITY_TYPE_MUST_BE_RECORD|" + recordType.getName(), recordType.isRecord());
		RecordComponent[] actual = recordType.getRecordComponents();
		Assert.assertEquals("TASK33_LOCAL_AUTHORITY_RECORD_COMPONENT_COUNT|" + recordType.getName(),
			expected.length, actual.length);
		for(int i = 0; i < expected.length; i++) {
			Assert.assertEquals("TASK33_LOCAL_AUTHORITY_RECORD_COMPONENT_NAME|" + recordType.getName() + "|" + i,
				expected[i].name(), actual[i].getName());
			Assert.assertEquals("TASK33_LOCAL_AUTHORITY_RECORD_COMPONENT_TYPE|" + recordType.getName() + "|" + i,
				expected[i].type(), actual[i].getType());
		}
	}

	private record ExpectedComponent(String name, Class<?> type) { }

	private static Object futureTypedActionOr(TestLocalMaterializationAction fallback) {
		for(String actionName : List.of(
			"org.apache.sysds.hops.fedplanner.placement.PlacementIdentity$LocalMaterializationActionKey",
			"org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult$LocalMaterializationActionKey",
			"org.apache.sysds.hops.fedplanner.placement.LocalMaterializationActionKey")) {
			for(String obligationName : productionObligationTypeCandidates(actionName)) {
				try {
					Class<?> obligationType = Class.forName(obligationName);
					List<Object> realObligations = new ArrayList<>();
					for(TestLocalMaterializationObligation obligation : fallback.obligations())
						realObligations.add(constructProductionObligation(obligationType, obligation));
					return constructProductionAction(Class.forName(actionName), fallback, realObligations);
				}
				catch(ReflectiveOperationException | LinkageError | IllegalArgumentException ignored) { }
			}
		}
		return fallback;
	}

	private static List<String> productionObligationTypeCandidates(String actionName) {
		if(actionName.endsWith("$LocalMaterializationActionKey"))
			return List.of(actionName.replace("$LocalMaterializationActionKey",
				"$LocalMaterializationObligation"));
		return List.of(actionName.replace(".LocalMaterializationActionKey", ".LocalMaterializationObligation"));
	}

	private static Object constructProductionObligation(Class<?> type, TestLocalMaterializationObligation obligation)
		throws ReflectiveOperationException {
		assertRecordComponents(type, new ExpectedComponent("consumerOccurrence", CompiledHopKey.class),
			new ExpectedComponent("inputPosition", int.class),
			new ExpectedComponent("requiredPlacement", PlacementState.class));
		Constructor<?> ctor = type.getDeclaredConstructor(CompiledHopKey.class, int.class, PlacementState.class);
		ctor.setAccessible(true);
		return ctor.newInstance(obligation.consumerOccurrence(), obligation.inputPosition(),
			obligation.requiredPlacement());
	}

	private static Object constructProductionAction(Class<?> type, TestLocalMaterializationAction fallback,
		List<Object> realObligations) throws ReflectiveOperationException {
		assertRecordComponents(type, new ExpectedComponent("sourceOccurrence", CompiledHopKey.class),
			new ExpectedComponent("sourceValueVersion", ValueVersionKey.class),
			new ExpectedComponent("producerPlacement", PlacementState.class),
			new ExpectedComponent("obligations", List.class),
			new ExpectedComponent("statementBlockScope", String.class),
			new ExpectedComponent("durableProvenance", String.class));
		Constructor<?> ctor = type.getDeclaredConstructor(CompiledHopKey.class, ValueVersionKey.class,
			PlacementState.class, List.class, String.class, String.class);
		ctor.setAccessible(true);
		return ctor.newInstance(fallback.sourceOccurrence(), fallback.sourceValueVersion(),
			fallback.producerPlacement(), List.copyOf(realObligations),
			fallback.statementBlockScope(), fallback.durableProvenance());
	}

	private record Occurrence(HopOccurrenceProjection projection, ValueVersionKey value) {
		private CompiledHopKey key() { return projection.key(); }
		private Hop hop() { return projection.hop(); }
		private long scopeId() { return projection.scopeId(); }
	}

	private record NamedResult(String name, EmissionAwareResult result) { }
	private record HopSnapshot(ExecType exec, ExecType forced, FederatedOutput output, boolean derived) { }
	private record StateSnapshot(Map<Long, HopSnapshot> hops, FederatedRefedRegistry.Snapshot refed,
		FederatedFoutMaterializeRegistry.Snapshot fout, FederatedLocalMaterializeRegistry.Snapshot local,
		Map<DMLProgram, PlacementEmissionTransaction.PlacementEmissionReceipt> receipts,
		ObservabilitySnapshot observability) { }

	private static final class FixtureProgram extends DMLProgram {
		private PlacementAnalysis authority;
		private FixtureProgram() { super(DMLProgram.DEFAULT_NAMESPACE); }
		private static FixtureProgram adopt(DMLProgram compiled) {
			FixtureProgram result = new FixtureProgram();
			result.getStatementBlocks().addAll(compiled.getStatementBlocks());
			return result;
		}
		private void install(PlacementAnalysis analysis) { authority = analysis; }
		@Override public PlacementAnalysis requirePlacementAnalysisAuthority() {
			if(authority == null) throw new IllegalStateException("missing fixture authority");
			return authority;
		}
		@Override public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
			if(candidate != authority) throw new IllegalArgumentException("foreign fixture authority");
		}
	}
}
