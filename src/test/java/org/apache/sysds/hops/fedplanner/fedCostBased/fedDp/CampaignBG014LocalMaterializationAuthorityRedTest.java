/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
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
import org.junit.Test;

/** Executable Task33 RED: selected local materialization authority is canonical, exact, and fail-closed. */
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
	public void selectedLocalAuthorityChangesTheCanonicalPlanHash() {
		EmissionAwareResult withoutLocal = fixture.result(List.of());
		EmissionAwareResult withLocal = fixture.result(List.of(fixture.localAction(FType.ROW, fixture.scopeText())));

		Assert.assertNotEquals("TASK33_SELECTED_LOCAL_AUTHORITY_MUST_PARTICIPATE_IN_CANONICAL_HASH",
			PlacementEmissionTransaction.canonicalPlanHash(withoutLocal),
			PlacementEmissionTransaction.canonicalPlanHash(withLocal));
	}

	@Test
	public void selectedLocalAuthorityEmitsOneExactSortedRegistryEntry() {
		EmissionAwareResult result = fixture.result(List.of(fixture.localAction(FType.ROW, fixture.scopeText())));
		PlacementEmissionTransaction.emit(fixture.program(), result, FailureInjector.none());

		Map<Long, FederatedLocalMaterializeRegistry.LocalMaterializeSpec> local =
			FederatedLocalMaterializeRegistry.snapshot(fixture.scopeId());
		Assert.assertEquals("TASK33_EMIT_MUST_WRITE_EXACTLY_ONE_LOCAL_MATERIALIZATION", 1, local.size());
		FederatedLocalMaterializeRegistry.LocalMaterializeSpec spec = local.get(fixture.producer().hop().getHopID());
		Assert.assertNotNull("TASK33_LOCAL_ENTRY_SOURCE_IS_EXACT_PRODUCER", spec);
		Assert.assertEquals("TASK33_LOCAL_ENTRY_SCOPE_IS_EXACT", fixture.scopeId(), fixture.producer().scopeId());
		Assert.assertEquals("TASK33_LOCAL_ENTRY_CONSUMERS_ARE_EXACT_SORTED",
			fixture.consumerHopIds(), spec.getConsumerHopIds());
		Assert.assertEquals("TASK33_LOCAL_ENTRY_FTYPE_IS_EXACT", FType.ROW.name(), spec.getFTypeHint());
		Assert.assertEquals("TASK33_LOCAL_ENTRY_REASON_IS_EXACT",
			"placement-transaction:" + fixture.scopeText(), spec.getReason());
	}

	@Test
	public void invalidLocalMaterializationAuthorityRejectsBeforeAnyOwnedMutation() {
		List<NamedAction> invalid = List.of(
			new NamedAction("producer-lout", fixture.localAction(FType.ROW, fixture.scopeText(), CP_LOUT,
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value())),
			new NamedAction("fed-consumer", fixture.localAction(FType.ROW, fixture.scopeText(), FED_FOUT_ROW,
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value(), Map.of(fixture.firstConsumer().key(), FED_FOUT_ROW))),
			new NamedAction("null-ftype", fixture.localAction(null, fixture.scopeText())),
			new NamedAction("empty-obligations", fixture.localAction(FType.ROW, fixture.scopeText(), FED_FOUT_ROW,
				List.of(), fixture.producer().key(), fixture.producer().value())),
			new NamedAction("partial-obligations", fixture.localAction(FType.ROW, fixture.scopeText(), FED_FOUT_ROW,
				List.of(consumerObligation(fixture.firstConsumer(), 0)), fixture.producer().key(), fixture.producer().value())),
			new NamedAction("duplicate-obligations", fixture.localAction(FType.ROW, fixture.scopeText(), FED_FOUT_ROW,
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.firstConsumer(), 0),
					consumerObligation(fixture.secondConsumer(), 0)), fixture.producer().key(), fixture.producer().value())),
			new NamedAction("blank-scope", fixture.localAction(FType.ROW, " ")),
			new NamedAction("mismatched-scope", fixture.localAction(FType.ROW, (fixture.scopeId() + 1) + ":main")),
			new NamedAction("mismatched-provenance", fixture.localAction(FType.ROW, fixture.scopeText(), FED_FOUT_ROW,
				List.of(consumerObligation(fixture.firstConsumer(), 0), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.firstConsumer().value())),
			new NamedAction("wrong-input-position", fixture.localAction(FType.ROW, fixture.scopeText(), FED_FOUT_ROW,
				List.of(consumerObligation(fixture.firstConsumer(), 1), consumerObligation(fixture.secondConsumer(), 0)),
				fixture.producer().key(), fixture.producer().value())),
			new NamedAction("foreign-occurrence", fixture.foreignOccurrenceAction()),
			new NamedAction("same-program-unselected-virtual-clone", fixture.virtualCloneAction())
		);
		for(NamedAction candidate : invalid)
			assertRejectsBeforeMutation(candidate.name(), fixture.result(List.of(candidate.action())));
	}

	@Test
	public void scopeAndFTypeDistinctLocalActionsRemainDistinct() {
		EmissionAwareResult result = fixture.result(List.of(
			fixture.localAction(FType.ROW, fixture.scopeText()),
			fixture.localAction(FType.COL, (fixture.scopeId() + 100) + ":main")));
		PlacementEmissionTransaction.emit(fixture.program(), result, FailureInjector.none());

		Assert.assertEquals("TASK33_SCOPE_DISTINCT_ACTION_SURVIVES", 1,
			FederatedLocalMaterializeRegistry.snapshot(fixture.scopeId()).size());
		Assert.assertEquals("TASK33_FTYPE_DISTINCT_ACTION_SURVIVES", 1,
			FederatedLocalMaterializeRegistry.snapshot(fixture.scopeId() + 100).size());
	}

	@Test
	public void injectedFailureRestoresHopsAllRegistriesReceiptAndObservability() {
		seedRegistries();
		primeHopMirrors(fixture.analysis());
		StateSnapshot beforeHopFailure = snapshot(fixture.analysis());
		assertThrowsAny(() -> PlacementEmissionTransaction.emit(fixture.program(),
			fixture.result(List.of(fixture.localAction(FType.ROW, fixture.scopeText()))),
			FailureInjector.failAt(FailurePoint.AFTER_FIRST_HOP_MUTATION)));
		Assert.assertEquals("TASK33_ROLLBACK_AFTER_HOP_MUTATION", beforeHopFailure, snapshot(fixture.analysis()));

		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
		seedRegistries();
		primeHopMirrors(fixture.analysis());
		StateSnapshot beforeRegistryFailure = snapshot(fixture.analysis());
		assertThrowsAny(() -> PlacementEmissionTransaction.emit(fixture.program(),
			fixture.result(List.of(fixture.localAction(FType.ROW, fixture.scopeText()))),
			FailureInjector.failAt(FailurePoint.AFTER_FIRST_REGISTRY_WRITE)));
		Assert.assertEquals("TASK33_ROLLBACK_AFTER_LOCAL_REGISTRY_WRITE", beforeRegistryFailure,
			snapshot(fixture.analysis()));
	}

	private static Fixture fixture() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileProgram());
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
		PlacementState producerFedFoutRow = node(analysis, producer.key()).legalAlternatives().stream()
			.filter(s -> s.execType() == ExecType.FED && s.output() == FederatedOutput.FOUT && s.fType() == FType.ROW)
			.findFirst().orElseThrow(() -> new AssertionError("TASK33_FIXTURE_PRODUCER_MUST_ACCEPT_FED_FOUT_ROW"));
		program.install(analysis);
		return new Fixture(program, analysis, producer, consumers.get(0), consumers.get(1), producerFedFoutRow);
	}

	private static boolean isBinaryConsumer(Hop hop) {
		return hop.getInput().stream().anyMatch(input -> input instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
			&& "X".equals(data.getName()));
	}

	private static DMLProgram compileProgram() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "S1=rand(rows=4,cols=2,seed=7);\n"
			+ "S2=rand(rows=4,cols=2,seed=8);\n"
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
		return new TestLocalMaterializationObligation(consumer.key(), inputPosition);
	}

	private static void assertRejectsBeforeMutation(String label, EmissionAwareResult result) {
		StateSnapshot before = snapshot(result.analysis());
		assertThrowsAny(() -> PlacementEmissionTransaction.emit(((FixtureProgram) result.programOwner()), result,
			FailureInjector.none()), "TASK33_INVALID_LOCAL_AUTHORITY_MUST_REJECT|" + label);
		Assert.assertEquals("TASK33_INVALID_LOCAL_AUTHORITY_MUTATED|" + label, before, snapshot(result.analysis()));
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
		Occurrence firstConsumer, Occurrence secondConsumer, PlacementState producerFedFoutRow) {
		private EmissionAwareResult result(List<Object> actions) {
			Map<CompiledHopKey, PlacementState> selected = new LinkedHashMap<>();
			for(Node n : analysis.graph().decisionNodes()) {
				PlacementState state = n.key() == producer.key() ? producerFedFoutRow
					: n.key() == firstConsumer.key() || n.key() == secondConsumer.key() ? CP_LOUT
					: n.legalAlternatives().contains(CP_LOUT) ? CP_LOUT : n.legalAlternatives().get(0);
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
		private Object localAction(FType fType, String scopeText) {
			return localAction(fType, scopeText, producerFedFoutRow,
				List.of(consumerObligation(firstConsumer, 0), consumerObligation(secondConsumer, 0)),
				producer.key(), producer.value());
		}
		private Object localAction(FType fType, String scopeText, PlacementState producerState,
			List<TestLocalMaterializationObligation> obligations, CompiledHopKey source, ValueVersionKey value) {
			return localAction(fType, scopeText, producerState, obligations, source, value, Map.of());
		}
		private Object localAction(FType fType, String scopeText, PlacementState producerState,
			List<TestLocalMaterializationObligation> obligations, CompiledHopKey source, ValueVersionKey value,
			Map<CompiledHopKey, PlacementState> selectedOverrides) {
			TestLocalMaterializationAction fallback = new TestLocalMaterializationAction(scopeText, source, value,
				producerState, fType, obligations, selectedOverrides);
			return futureTypedActionOr(fallback);
		}
		private Object foreignOccurrenceAction() {
			Fixture foreign;
			try { foreign = fixture(); }
			catch(Exception e) { throw new IllegalStateException(e); }
			return localAction(FType.ROW, scopeText(), FED_FOUT_ROW,
				List.of(consumerObligation(firstConsumer, 0), consumerObligation(secondConsumer, 0)),
				foreign.producer().key(), producer.value());
		}
		private Object virtualCloneAction() {
			CompiledHopKey clone = new CompiledHopKey(producer.key().programFingerprint(), producer.key().functionNamespace(),
				producer.key().callSitePath(), producer.key().recompileContext(), producer.key().controlRegion(),
				producer.key().emittedHopInstance() + "|virtual-clone", producer.key().canonicalSourceOrigin());
			return localAction(FType.ROW, scopeText(), FED_FOUT_ROW,
				List.of(consumerObligation(firstConsumer, 0), consumerObligation(secondConsumer, 0)), clone, producer.value());
		}
	}

	public record TestLocalMaterializationObligation(CompiledHopKey consumer, int inputPosition) {
		public TestLocalMaterializationObligation {
			Objects.requireNonNull(consumer, "consumer");
		}
	}

	public record TestLocalMaterializationAction(String statementBlockScope, CompiledHopKey source,
		ValueVersionKey sourceValueVersion, PlacementState sourcePlacement, FType fType,
		List<TestLocalMaterializationObligation> obligations, Map<CompiledHopKey, PlacementState> selectedOverrides) {
		public TestLocalMaterializationAction {
			obligations = List.copyOf(Objects.requireNonNull(obligations, "obligations"));
			selectedOverrides = Map.copyOf(Objects.requireNonNull(selectedOverrides, "selectedOverrides"));
		}
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

	private static Object futureTypedActionOr(TestLocalMaterializationAction fallback) {
		for(String name : List.of(
			"org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult$SelectedLocalMaterializationAction",
			"org.apache.sysds.hops.fedplanner.placement.SelectedLocalMaterializationAction",
			"org.apache.sysds.hops.fedplanner.placement.LocalMaterializationAction")) {
			try {
				Class<?> type = Class.forName(name);
				for(Constructor<?> ctor : type.getDeclaredConstructors()) {
					if(ctor.getParameterCount() == 7) {
						ctor.setAccessible(true);
						return ctor.newInstance(fallback.statementBlockScope(), fallback.source(),
							fallback.sourceValueVersion(), fallback.sourcePlacement(), fallback.fType(),
							fallback.obligations(), fallback.selectedOverrides());
					}
				}
			}
			catch(ReflectiveOperationException | LinkageError ignored) { }
		}
		return fallback;
	}

	private record Occurrence(HopOccurrenceProjection projection, ValueVersionKey value) {
		private CompiledHopKey key() { return projection.key(); }
		private Hop hop() { return projection.hop(); }
		private long scopeId() { return projection.scopeId(); }
	}

	private record NamedAction(String name, Object action) { }
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
