/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
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
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
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
		fixture = relocationFixture();
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
		Fixture foreign = relocationFixture();
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

	@Test
	public void b11AlreadyCompatibleFederatedSourceNeedsNoRelocation() throws Exception {
		assertB11AlreadyCompatibleNegative();
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

	private static Fixture relocationFixture() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileRelocationProgram());
		PlacementAnalysis baseline = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult baselinePlan = new FedAllPlacementAdapter().select(baseline);
		NormalizedPlannerResult plan = withCanonicalFingerprint(baselinePlan, baseline);
		NeutralPlacementGraph.Node local = uniqueNode(baseline, "S");
		NeutralPlacementGraph.Node anchor = uniqueNode(baseline, "X");
		List<PlacementAnalysis.CompiledInputEdgeFact> localEdges = baseline.compiledInputEdgesInCanonicalOrder()
			.stream().filter(edge -> edge.producer() == local.key()).toList();
		List<RelocationAction> uploads = baseline.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion().equals(local.valueVersion())).toList();

		Assert.assertEquals("P4_FIXTURE_REQUIRES_TWO_EXACT_LOCAL_INPUTS", 2, localEdges.size());
		Assert.assertTrue("P4_FIXTURE_REQUIRES_LOCAL_INPUT_POSITION_ONE",
			localEdges.stream().allMatch(edge -> edge.inputPosition() == 1));
		Assert.assertTrue("P4_FIXTURE_REQUIRES_LOCAL_CP_LOUT_SOURCE",
			selected(plan, local.key(), ExecType.CP, FederatedOutput.LOUT));
		Assert.assertTrue("P4_FIXTURE_LOCAL_SOURCE_HAS_NO_DURABLE_ANCHOR", local.anchors().isEmpty());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_SHARED_GRAPH_RELOCATION", 1, uploads.size());
		RelocationAction upload = uploads.get(0);
		Assert.assertEquals("P4_FIXTURE_REQUIRES_TWO_EXACT_RELOCATION_OBLIGATIONS", 2,
			upload.obligations().size());
		Assert.assertEquals("P4_FIXTURE_RELOCATION_ENDPOINTS_ARE_EXACT",
			localEdges.stream().map(edge -> endpoint(edge.consumer(), edge.inputPosition())).sorted().toList(),
			upload.obligations().stream().map(obligation ->
				endpoint(obligation.consumer(), obligation.inputPosition())).sorted().toList());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_DURABLE_ANCHOR", 1, anchor.anchors().size());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ROW_DURABLE_ANCHOR", FType.ROW,
			anchor.anchors().get(0).fType());
		Assert.assertTrue("P4_FIXTURE_REQUIRES_FED_FOUT_ANCHOR_SOURCE",
			selected(plan, anchor.key(), ExecType.FED, FederatedOutput.FOUT));
		Assert.assertEquals("P4_FIXTURE_RELOCATION_USES_EXACT_ANCHOR", anchor.anchors().get(0),
			upload.key().durableAnchor());
		Assert.assertEquals("P4_FIXTURE_RELOCATION_NAMES_EXACT_COMPATIBLE_CONSUMERS",
			localEdges.stream().map(PlacementAnalysis.CompiledInputEdgeFact::consumer).sorted().toList(),
			upload.key().compatibleConsumers());
		Assert.assertTrue("P4_FIXTURE_RELOCATION_IS_ANCHOR_TYPED_FED_FOUT",
			upload.key().targetPlacement().execType() == ExecType.FED
				&& upload.key().targetPlacement().output() == FederatedOutput.FOUT
				&& upload.key().targetPlacement().fType() == upload.key().durableAnchor().fType()
				&& upload.key().targetPlacement().shapeDependent());
		Assert.assertTrue("P4_FIXTURE_RELOCATION_AUTHORITY_IS_EXACT",
			upload.obligations().stream().allMatch(obligation ->
				obligation.sourceValueVersion().equals(local.valueVersion())
					&& obligation.relocationAction().equals(upload.key())
					&& obligation.requiredPlacement().equals(upload.key().targetPlacement())
					&& baseline.graph().node(obligation.consumer()).orElseThrow().legalAlternatives()
						.contains(obligation.requiredPlacement())
					&& obligation.requiredPlacement().equals(
						plan.selectedStates().get(obligation.consumer()))));
		Assert.assertTrue("P4_FIXTURE_REQUIRES_EXACT_FEDALL_RELOCATION_SELECTION",
			plan.selectedRelocations().contains(upload.key()));
		Assert.assertFalse("P4_FIXTURE_REQUIRES_DECISIONS", plan.selectedStates().isEmpty());
		program.install(baseline);
		return new Fixture(program, baseline, plan);
	}

	private static void assertB11AlreadyCompatibleNegative() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-11"));
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		NeutralPlacementGraph.Node source = analysis.graph().nodes().stream()
			.filter(node -> analysis.hop(node.key()).orElseThrow() instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED).findFirst()
			.orElseThrow(() -> new AssertionError("P4_B11_REQUIRES_DURABLE_SOURCE"));
		Assert.assertTrue("P4_B11_SOURCE_IS_ALREADY_FED_FOUT_COMPATIBLE",
			selected(plan, source.key(), ExecType.FED, FederatedOutput.FOUT));
		Assert.assertTrue("P4_B11_ALREADY_COMPATIBLE_SOURCE_NEEDS_NO_RELOCATION",
			plan.selectedRelocations().isEmpty()
				&& analysis.graph().relocationActions().stream()
					.noneMatch(action -> analysis.graph().isRelocationActive(action, plan.selectedStates())));
	}

	private static NeutralPlacementGraph.Node uniqueNode(PlacementAnalysis analysis, String name) {
		List<NeutralPlacementGraph.Node> matches = analysis.graph().nodes().stream()
			.filter(node -> analysis.hop(node.key()).map(hop -> name.equals(hop.getName())).orElse(false)).toList();
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_" + name, 1, matches.size());
		return matches.get(0);
	}

	private static String endpoint(CompiledHopKey consumer, int inputPosition) {
		return consumer.normalizedSignature() + '@' + inputPosition;
	}

	private static NormalizedPlannerResult withCanonicalFingerprint(NormalizedPlannerResult source,
		PlacementAnalysis analysis) {
		return wrap(source, analysis, analysis.analysisFingerprint(), source.selectedStates(),
			source.selectedRelocations(), canonicalPlanHash(source));
	}

	private static String canonicalPlanHash(NormalizedPlannerResult result) {
		StringBuilder canonical = new StringBuilder().append(result.plannerId()).append('\n')
			.append(result.analysisFingerprint()).append('\n');
		result.selectedStates().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> canonical
			.append(entry.getKey().normalizedSignature()).append('=')
			.append(entry.getValue().normalizedSignature()).append('\n'));
		result.selectedRelocations().stream()
			.sorted(Comparator.comparing(RelocationActionKey::normalizedSignature))
			.forEach(relocation -> canonical.append(relocation.normalizedSignature()).append('\n'));
		canonical.append(result.objectiveCertificate());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hash = new StringBuilder(64);
			for(byte value : digest)
				hash.append(String.format("%02x", value));
			return hash.toString();
		}
		catch(Exception failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private static DMLProgram compileRelocationProgram() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "S=rand(rows=4,cols=2,seed=7);\n"
			+ "Y1=X+S;\nY2=X-S;\n"
			+ "write(Y1,\"/tmp/g005-p4-y1\",format=\"binary\");\n"
			+ "write(Y2,\"/tmp/g005-p4-y2\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
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
	private static final class FixtureProgram extends DMLProgram {
		private PlacementAnalysis authority;

		private FixtureProgram() {
			super(DMLProgram.DEFAULT_NAMESPACE);
		}

		private static FixtureProgram adopt(DMLProgram compiled) {
			FixtureProgram result = new FixtureProgram();
			result.getStatementBlocks().addAll(compiled.getStatementBlocks());
			return result;
		}

		private void install(PlacementAnalysis analysis) {
			if(authority != null)
				throw new IllegalStateException("fixture placement authority is already installed");
			authority = analysis;
		}

		@Override
		public PlacementAnalysis requirePlacementAnalysisAuthority() {
			if(authority == null)
				throw new IllegalStateException("fixture program has no placement authority");
			return authority;
		}

		@Override
		public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
			if(candidate == null || candidate != authority)
				throw new IllegalArgumentException("Placement analysis is not the canonical fixture owner");
		}
	}
	private record HopSnapshot(ExecType exec, ExecType forcedExec, FederatedOutput output, boolean derived) { }
	private record StateSnapshot(Map<Long, HopSnapshot> hops, List<String> registries, Map<?, ?> receipts,
		long fallbackCount, long repairCount) { }
	@FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
