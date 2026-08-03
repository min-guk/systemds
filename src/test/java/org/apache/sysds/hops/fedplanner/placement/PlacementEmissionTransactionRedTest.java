/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailurePoint;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.Dag;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.ConsumerInputSpec;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.Instruction;
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
	public void refedRegistryWritePreservesExactCompatibleConsumerHopIds() throws Exception {
		PlacementEmissionTransaction.emit(fixture.program(), fixture.plan(), FailureInjector.none());
		RelocationAction upload = selectedRelocation(fixture);
		NeutralPlacementGraph.Node local = selectedRelocationSource(fixture, upload);
		long scope = fixture.analysis().occurrences().stream()
			.filter(o -> o.key().equals(local.key()))
			.findFirst().orElseThrow().scopeId();
		long sourceHopId = fixture.analysis().hop(local.key()).orElseThrow().getHopID();
		List<Long> expectedConsumerHopIds = upload.key().compatibleConsumers().stream()
			.map(key -> fixture.analysis().hop(key).orElseThrow().getHopID())
			.distinct().sorted().toList();

		FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(scope).get(sourceHopId);
		Assert.assertNotNull("G007_REFED_REGISTRY_WRITE_PRESENT", spec);
		Assert.assertEquals("G007_REFED_REGISTRY_PRESERVES_EXACT_COMPATIBLE_CONSUMERS",
			expectedConsumerHopIds, spec.getConsumerHopIds());
		Assert.assertTrue("G007_PLANNER_REFED_REGISTRY_FORBIDS_WILDCARD_INPUT_AUTHORITY",
			spec.getConsumerInputs().stream().noneMatch(ConsumerInputSpec::allInputs));
		Assert.assertEquals("G007_REFED_REGISTRY_PRESERVES_EXACT_MATERIALIZATION_FTYPE",
			upload.key().materializationFType(), spec.getMaterializationFType());
	}

	@Test
	public void compatibleConsumerSpecificAuthoritiesCoalesceWithinOneRegistrySlot() {
		long scope = 7_001L;
		long source = 7_002L;
		long anchor = 7_003L;
		String durableAnchor = "(7,worker-a;worker-b;|0,5;5,10;|ROW";
		FederatedRefedRegistry.registerConsumerInputs(scope, source, anchor, durableAnchor,
			FType.BROADCAST, List.of(new ConsumerInputSpec(7_020L, 1)));
		FederatedRefedRegistry.registerConsumerInputs(scope, source, anchor, durableAnchor,
			FType.BROADCAST, List.of(new ConsumerInputSpec(7_010L, 0)));

		FederatedRefedRegistry.AnchorSpec merged = FederatedRefedRegistry.snapshot(scope).get(source);
		Assert.assertNotNull("G007_REFED_COMPATIBLE_SLOT_MERGE_PRESENT", merged);
		Assert.assertEquals("G007_REFED_COMPATIBLE_SLOT_MERGE_ONE_AUTHORITY",
			1, merged.getAuthorities().size());
		Assert.assertEquals("G007_REFED_COMPATIBLE_SLOT_MERGE_EXACT_SORTED_INPUTS",
			List.of(new ConsumerInputSpec(7_010L, 0), new ConsumerInputSpec(7_020L, 1)),
			merged.getConsumerInputs());
		Assert.assertEquals("G007_REFED_COMPATIBLE_SLOT_MERGE_FTYPE",
			FType.BROADCAST, merged.getMaterializationFType());
	}

	@Test
	public void equalPlacementValuesDoNotRequireSharedObjectIdentityDuringEmission() throws Exception {
		Fixture localFixture = localMaterializationFixture();
		Assert.assertFalse("fixture must exercise exact LOCAL materialization authority",
			localFixture.plan().selectedLocalMaterializations().isEmpty());
		Map<CompiledHopKey,PlacementEmissionState> copied = new LinkedHashMap<>();
		localFixture.plan().selectedEmissionStates().forEach((key, emission) -> {
			PlacementState state = emission.placementState();
			PlacementState equalButDistinct = new PlacementState(state.execType(), state.output(),
				state.fType(), state.shapeDependent());
			Assert.assertEquals(state, equalButDistinct);
			Assert.assertNotSame("fixture must exercise value equality rather than identity",
				state, equalButDistinct);
			copied.put(key, new PlacementEmissionState(equalButDistinct, emission.derivedFedFout()));
		});
		NormalizedPlannerResult equalValuePlan = wrapWithEmissionStates(localFixture.plan(), copied);

		PlacementEmissionReceipt receipt = PlacementEmissionTransaction.emit(
			localFixture.program(), equalValuePlan, FailureInjector.none());

		Assert.assertTrue("semantically equal placement values must emit successfully", receipt.applied());
	}

	@Test
	public void durableAnchorRegistryKeyRoundTripsThroughRuntimeParser() throws Exception {
		PlacementEmissionTransaction.emit(fixture.program(), fixture.plan(), FailureInjector.none());
		RelocationAction relocation = selectedRelocation(fixture);
		NeutralPlacementGraph.Node local = selectedRelocationSource(fixture, relocation);
		long scope = fixture.analysis().occurrences().stream()
			.filter(o -> o.key().equals(local.key()))
			.findFirst().orElseThrow().scopeId();
		long sourceHopId = fixture.analysis().hop(local.key()).orElseThrow().getHopID();
		FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(scope).get(sourceHopId);

		Assert.assertNotNull("G007_RUNTIME_ANCHOR_REGISTRY_WRITE_PRESENT", spec);
		FederationMap rebuilt = FederationUtils.buildAnchorMapFromKey(spec.getAnchorKey());
		Assert.assertNotNull("G007_RUNTIME_ANCHOR_KEY_MUST_BE_PARSEABLE", rebuilt);
		Assert.assertEquals("G007_RUNTIME_ANCHOR_FTYPE_ROUND_TRIP",
			relocation.key().durableAnchor().fType(), rebuilt.getType());
		Assert.assertEquals("G007_RUNTIME_ANCHOR_PARTITION_COUNT_ROUND_TRIP",
			relocation.key().durableAnchor().partitions().size(), rebuilt.getSize());
		FederatedRange[] ranges = rebuilt.getFederatedRanges();
		for(int i = 0; i < ranges.length; i++) {
			PlacementIdentity.AnchorPartition partition = relocation.key().durableAnchor().partitions().get(i);
			Assert.assertEquals("G007_RUNTIME_ANCHOR_ROW_BEGIN_ROUND_TRIP",
				partition.begin().get(0).longValue(), ranges[i].getBeginDims()[0]);
			Assert.assertEquals("G007_RUNTIME_ANCHOR_ROW_END_ROUND_TRIP",
				partition.end().get(0).longValue(), ranges[i].getEndDims()[0]);
		}
	}

	@Test
	public void fullFedInitDurableAnchorMatchesLiveRegisteredAuthority() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileFullRelocationProgram());
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NeutralPlacementGraph.Node anchor = uniqueNode(analysis, "XF");

		Assert.assertEquals("G007_FULL_FEDINIT_REQUIRES_ONE_DURABLE_ANCHOR", 1, anchor.anchors().size());
		Assert.assertEquals("G007_FULL_FEDINIT_REQUIRES_FULL_DURABLE_ANCHOR",
			FType.FULL, anchor.anchors().get(0).fType());
		DataOp source = (DataOp) analysis.hop(anchor.key()).orElseThrow();
		FederatedPlannerUtils.registerFedInitVar("XF", FederatedPlannerUtils.deriveFedInitFType(source),
			FederatedPlannerUtils.deriveFedInitSignature(source));
		String durableKey = ExactPlacementRegistration.runtimeAnchorKey(anchor.anchors().get(0));
		String liveKey = FederatedPlannerUtils.getFedAnchorKey("XF");
		Assert.assertEquals("G007_FULL_FEDINIT_LIVE_AND_DURABLE_AUTHORITY_MUST_MATCH",
			liveKey, durableKey);
		FederationMap runtimeMap = FederationUtils.buildAnchorMapFromKey(durableKey);
		Assert.assertNotNull("G007_FULL_FEDINIT_DURABLE_AUTHORITY_MUST_REBUILD", runtimeMap);
		String runtimeKey = FederatedPlannerUtils.deriveFedMappingSignature(runtimeMap)
			+ '|' + runtimeMap.getType().name();
		Assert.assertEquals("G007_FULL_FEDINIT_COMPILE_AND_RUNTIME_AUTHORITY_MUST_MATCH",
			liveKey, runtimeKey);
	}

	@Test
	public void singleWorkerFullAuthorityIsIdenticalForFedAllAndUnmarkedHeuristic() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileFullRelocationProgram());
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var fedAll = new FedAllPlacementAdapter().select(analysis);
		var heuristic = new HeuristicPlacementAdapter().select(analysis, Set.of());
		NeutralPlacementGraph.Node anchor = uniqueNode(analysis, "XF");

		Assert.assertEquals("G007_SINGLE_WORKER_FEDINIT_HAS_ONE_EXACT_AUTHORITY",
			1, anchor.anchors().size());
		Assert.assertEquals("G007_SINGLE_WORKER_FEDINIT_AUTHORITY_IS_FULL",
			FType.FULL, anchor.anchors().get(0).fType());
		Assert.assertEquals("G007_UNMARKED_HEURISTIC_REUSES_FEDALL_NORMALIZED_ASSIGNMENT",
			fedAll.selectedStates(), heuristic.selectedStates());
		Assert.assertEquals("G007_UNMARKED_HEURISTIC_REUSES_FEDALL_CANDIDATE_AUTHORITY",
			fedAll.selectedCandidateSelections(), heuristic.selectedCandidateSelections());
		Assert.assertEquals("G007_UNMARKED_HEURISTIC_REUSES_FEDALL_RELOCATION_CHOICES",
			fedAll.selectedRelocationChoices(), heuristic.selectedRelocationChoices());
		Assert.assertEquals("G007_UNMARKED_HEURISTIC_REUSES_FEDALL_RELOCATIONS",
			fedAll.selectedRelocations(), heuristic.selectedRelocations());
		Assert.assertTrue("G007_SINGLE_WORKER_FULL_RELOCATION_TYPES_REMAIN_EXACT",
			fedAll.selectedRelocations().stream().allMatch(relocation ->
				relocation.materializationFType() != null
					&& relocation.materializationFType() != FType.PART
					&& relocation.materializationFType() != FType.OTHER));
	}


	@Test
	public void emittedTransactionRegistryRefedLowersThroughDagGetJobsToConcreteInstruction() throws Exception {
		PlacementEmissionTransaction.emit(fixture.program(), fixture.plan(), FailureInjector.none());
		RelocationAction relocation = selectedRelocation(fixture);
		NeutralPlacementGraph.Node local = selectedRelocationSource(fixture, relocation);
		long scope = fixture.analysis().occurrences().stream()
			.filter(o -> o.key().equals(local.key()))
			.findFirst().orElseThrow().scopeId();
		long sourceHopId = fixture.analysis().hop(local.key()).orElseThrow().getHopID();
		FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(scope).get(sourceHopId);
		Assert.assertNotNull("G007_NORMAL_DAG_REQUIRES_REFED_REGISTRY_ENTRY", spec);
		Assert.assertFalse("G007_NORMAL_DAG_REQUIRES_EXACT_REFED_CONSUMERS", spec.getConsumerHopIds().isEmpty());
		Assert.assertEquals("G007_SINGLE_WORKER_REFED_REGISTRY_PRESERVES_FULL",
			FType.FULL, spec.getMaterializationFType());
		Assert.assertTrue("G007_SINGLE_WORKER_REFED_REGISTRY_FORBIDS_WILDCARD_INPUT_AUTHORITY",
			spec.getConsumerInputs().stream().noneMatch(ConsumerInputSpec::allInputs));

		List<String> instructions = instructionStringsFromProgram(fixture.program());
		List<String> refedInstructions = instructions.stream()
			.filter(instruction -> instruction.contains("fed_refed"))
			.toList();

		Assert.assertFalse("G007_TRANSACTION_REGISTRY_DAG_LOWERING_EMITS_REFED: "
			+ instructions, refedInstructions.isEmpty());
		Assert.assertTrue("G007_NORMAL_DAG_REFED_INSTRUCTION_USES_CONCRETE_LIVE_ANCHOR_OR_KEY: "
			+ refedInstructions, refedInstructions.stream().anyMatch(instruction -> instruction.contains("°X·MATRIX")
				|| (spec.getAnchorKey() != null && instruction.contains(spec.getAnchorKey()))));
		Assert.assertTrue("G007_NORMAL_DAG_REFED_INSTRUCTION_PRESERVES_EXACT_MATERIALIZATION_FTYPE: "
			+ refedInstructions, refedInstructions.stream().allMatch(instruction ->
				instruction.endsWith("°" + spec.getMaterializationFType().name())));
		Assert.assertTrue("G007_NORMAL_DAG_REFED_INSTRUCTIONS_MUST_NOT_SERIALIZE_NULL_ANCHOR: "
			+ refedInstructions, refedInstructions.stream().noneMatch(instruction -> instruction.contains("null.UNKNOWN")));
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

	private static RelocationAction selectedRelocation(Fixture fixture) {
		List<RelocationAction> selected = fixture.analysis().graph().relocationActions().stream()
			.filter(action -> fixture.plan().selectedRelocations().contains(action.key())).toList();
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_SELECTED_EXACT_RELOCATION", 1, selected.size());
		return selected.get(0);
	}

	private static NeutralPlacementGraph.Node selectedRelocationSource(Fixture fixture,
		RelocationAction relocation) {
		List<NeutralPlacementGraph.Node> sources = fixture.analysis().graph().nodes().stream()
			.filter(node -> node.emittedWork()
				&& node.valueVersion().equals(relocation.key().sourceValueVersion())).toList();
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_SELECTED_RELOCATION_SOURCE", 1, sources.size());
		return sources.get(0);
	}

	private static Fixture relocationFixture() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileRelocationProgram());
		PlacementAnalysis baseline = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult baselinePlan = new FedAllPlacementAdapter().select(baseline);
		RelocationAction upload = baseline.graph().relocationActions().stream()
			.filter(action -> action.key().materializationFType() == FType.FULL)
			.filter(action -> action.key().targetPlacement().execType() == ExecType.FED)
			.filter(action -> action.key().targetPlacement().output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"P4 fixture requires an exact PRESENT-backed FULL relocation"));
		Map<CompiledHopKey, PlacementState> selected = new LinkedHashMap<>(baselinePlan.selectedStates());
		for(var obligation : upload.obligations())
			selected.put(obligation.consumer(), obligation.requiredPlacement());
		List<NeutralPlacementGraph.Node> sources = baseline.graph().nodes().stream()
			.filter(node -> node.valueVersion().equals(upload.key().sourceValueVersion()))
			.filter(NeutralPlacementGraph.Node::emittedWork).toList();
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_RELOCATION_SOURCE", 1, sources.size());
		NeutralPlacementGraph.Node local = sources.get(0);
		PlacementState localState = local.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"P4 fixture relocation source requires an exact CP/LOUT arm"));
		selected.put(local.key(), localState);
		var candidateSelection = CandidateSelections.selectMaterializationMaximal(
			baseline, baseline.graph().relocationActions(), selected);
		Map<CompiledHopKey, PlacementEmissionState> emission = new LinkedHashMap<>();
		selected.forEach((key, state) -> emission.put(key, new PlacementEmissionState(state, false)));
		NormalizedPlannerResult plan = withCanonicalFingerprint(
			NormalizedPlannerResults.createWithEmissionStatesAndCandidateSelections(
				baseline, "transaction-fixture", emission, candidateSelection.candidates(),
				candidateSelection.relocationChoices(), "exact-present-relocation"), baseline);
		NeutralPlacementGraph.Node anchor = uniqueNode(baseline, "X");
		List<PlacementAnalysis.CompiledInputEdgeFact> localEdges = baseline.compiledInputEdgesInCanonicalOrder()
			.stream().filter(edge -> edge.producer() == local.key()).toList();

		Assert.assertTrue("P4_FIXTURE_REQUIRES_EXACT_LOCAL_INPUT", !localEdges.isEmpty());
		Assert.assertTrue("P4_FIXTURE_REQUIRES_LOCAL_CP_LOUT_SOURCE",
			selected(plan, local.key(), ExecType.CP, FederatedOutput.LOUT));
		Assert.assertTrue("P4_FIXTURE_LOCAL_SOURCE_HAS_NO_DURABLE_ANCHOR", local.anchors().isEmpty());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_EXACT_RELOCATION_OBLIGATION", 1,
			upload.obligations().size());
		Assert.assertEquals("P4_FIXTURE_RELOCATION_ENDPOINTS_ARE_EXACT",
			localEdges.stream().filter(edge -> upload.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == edge.consumer() && obligation.inputPosition() == edge.inputPosition()))
				.map(edge -> endpoint(edge.consumer(), edge.inputPosition())).sorted().toList(),
			upload.obligations().stream().map(obligation ->
				endpoint(obligation.consumer(), obligation.inputPosition())).sorted().toList());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_DURABLE_ANCHOR", 1, anchor.anchors().size());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_FULL_DURABLE_ANCHOR", FType.FULL,
			anchor.anchors().get(0).fType());
		Assert.assertTrue("P4_FIXTURE_REQUIRES_FED_FOUT_ANCHOR_SOURCE",
			selected(plan, anchor.key(), ExecType.FED, FederatedOutput.FOUT));
		Assert.assertEquals("P4_FIXTURE_RELOCATION_USES_EXACT_ANCHOR", anchor.anchors().get(0),
			upload.key().durableAnchor());
		Assert.assertEquals("P4_FIXTURE_RELOCATION_NAMES_EXACT_COMPATIBLE_CONSUMERS",
			upload.obligations().stream().map(PlacementIdentity.ObligationKey::consumer)
				.distinct().sorted().toList(),
			upload.key().compatibleConsumers());
		Assert.assertTrue("P4_FIXTURE_RELOCATION_IS_ANCHOR_TYPED_FED_FOUT",
			upload.key().targetPlacement().execType() == ExecType.FED
				&& upload.key().targetPlacement().output() == FederatedOutput.FOUT
				&& upload.key().targetPlacement().fType() == upload.key().durableAnchor().fType());
		Assert.assertTrue("P4_FIXTURE_RELOCATION_HAS_EXACT_PRESENT_BACKING_ROW",
			upload.obligations().stream().allMatch(obligation -> plan.selectedCandidateSelections().stream()
				.anyMatch(candidate -> candidate.rule().parentOccurrence() == obligation.consumer()
					&& obligation.inputPosition() < candidate.rule().orderedInputs().size()
					&& candidate.rule().orderedInputs().get(obligation.inputPosition()).present()
					&& candidate.rule().orderedInputs().get(obligation.inputPosition()).fType()
						== upload.key().materializationFType())));
		Assert.assertTrue("P4_FIXTURE_RELOCATION_AUTHORITY_IS_EXACT",
			upload.obligations().stream().allMatch(obligation ->
				obligation.sourceValueVersion().equals(local.valueVersion())
					&& obligation.relocationAction().equals(upload.key())
					&& obligation.requiredPlacement().equals(upload.key().targetPlacement())
					&& baseline.graph().node(obligation.consumer()).orElseThrow().legalAlternatives()
						.contains(obligation.requiredPlacement())
					&& obligation.requiredPlacement().equals(
						plan.selectedStates().get(obligation.consumer()))));
		Assert.assertTrue("P4_FIXTURE_REQUIRES_EXACT_FORCED_RELOCATION_SELECTION",
			plan.selectedRelocations().contains(upload.key()));
		Assert.assertFalse("P4_FIXTURE_REQUIRES_DECISIONS", plan.selectedStates().isEmpty());
		program.install(baseline);
		return new Fixture(program, baseline, plan);
	}

	private static Fixture localMaterializationFixture() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileFullRelocationProgram());
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult baseline = new FedAllPlacementAdapter().select(analysis);
		NeutralPlacementGraph.Node source = uniqueNode(analysis, "XF");
		PlacementState sourceState = source.legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == FType.FULL)
			.findFirst().orElseThrow(() -> new AssertionError(
				"equal-value fixture requires FED/FOUT/FULL source"));
		Map<CompiledHopKey,PlacementState> selected = new LinkedHashMap<>(baseline.selectedStates());
		selected.put(source.key(), sourceState);
		List<CompiledHopKey> consumers = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == source.key()).map(PlacementAnalysis.CompiledInputEdgeFact::consumer)
			.distinct().toList();
		Assert.assertFalse("equal-value fixture requires a physical source consumer", consumers.isEmpty());
		for(CompiledHopKey consumer : consumers) {
			PlacementState local = analysis.graph().node(consumer).orElseThrow().legalAlternatives().stream()
				.filter(state -> state.execType() == ExecType.CP
					&& state.output() == FederatedOutput.LOUT)
				.findFirst().orElseThrow(() -> new AssertionError(
					"equal-value fixture consumer requires CP/LOUT"));
			selected.put(consumer, local);
		}
		CandidateSelections.Selection candidates = CandidateSelections.selectNativeCanonical(
			analysis, analysis.graph().relocationActions(), selected);
		Map<CompiledHopKey,PlacementEmissionState> emission = new LinkedHashMap<>();
		selected.forEach((key, state) -> emission.put(key, new PlacementEmissionState(state, false)));
		NormalizedPlannerResult plan = NormalizedPlannerResults.createWithEmissionStatesAndCandidateSelections(
			analysis, "transaction-local-equal-values", emission, candidates.candidates(),
			candidates.relocationChoices(), "exact-local-equal-value-authority");
		Assert.assertFalse("equal-value fixture must derive LOCAL materialization",
			plan.selectedLocalMaterializations().isEmpty());
		program.install(analysis);
		return new Fixture(program, analysis, plan);
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
		if(source.analysis() != analysis
			|| !source.normalizedPlanFingerprint().equals(PlacementEmissionTransaction.canonicalPlanHash(source)))
			throw new IllegalArgumentException("Fixture source is not a canonical normalized result");
		return source;
	}

	private static DMLProgram compileRelocationProgram() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\"),"
			+ "ranges=list(list(0,0),list(4,2)));\n"
			+ "S=rand(rows=4,cols=2,seed=7);\n"
			+ "T=t(S);\n"
			+ "Y1=T%*%X;\nY2=T%*%(X+1);\n"
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

	private static DMLProgram compileFullRelocationProgram() throws Exception {
		String script = "XF=federated(addresses=list(\"worker1:8001/data/P2P2D_features.data\"),"
			+ "ranges=list(list(0,0),list(50000,2100)));\n"
			+ "S=rand(rows=50000,cols=2100,seed=7);\n"
			+ "Y=XF+S;\n"
			+ "write(Y,\"/tmp/g007-full-anchor-y\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}


	private static List<String> instructionStringsFromProgram(DMLProgram program) throws Exception {
		DMLTranslator translator = new DMLTranslator(program);
		List<String> instructions = new ArrayList<>();
		for (StatementBlock sb : program.getStatementBlocks()) {
			translator.constructLops(sb);
			Dag<Lop> dag = new Dag<>();
			for (Lop lop : sb.getLops())
				lop.addToDag(dag);
			for (Instruction inst : dag.getJobs(sb, ConfigurationManager.getDMLConfig()))
				instructions.add(inst.getInstructionString());
		}
		return instructions;
	}

	private static NormalizedPlannerResult wrap(NormalizedPlannerResult source, PlacementAnalysis analysis,
		String analysisFingerprint, Map<CompiledHopKey, PlacementState> states,
		List<RelocationActionKey> relocations, String planHash) {
		return new NormalizedPlannerResult() {
			@Override public PlacementAnalysis analysis() { return analysis; }
			@Override public String plannerId() { return source.plannerId(); }
			@Override public String analysisFingerprint() { return analysisFingerprint; }
			@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return Map.copyOf(states); }
			@Override public Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates() {
				return emissionStatesFor(source, states);
			}
			@Override public List<RelocationActionKey> selectedRelocations() { return List.copyOf(relocations); }
			@Override public List<CandidateSelectionReceipt> selectedCandidateSelections() {
				return source.selectedCandidateSelections();
			}
			@Override public List<RelocationChoiceReceipt> selectedRelocationChoices() {
				return source.selectedRelocationChoices();
			}
			@Override public List<LocalMaterializationActionKey> selectedLocalMaterializations() {
				return source.selectedLocalMaterializations();
			}
			@Override public String objectiveCertificate() { return source.objectiveCertificate(); }
			@Override public String normalizedPlanFingerprint() { return planHash; }
		};
	}

	private static NormalizedPlannerResult wrapWithEmissionStates(NormalizedPlannerResult source,
		Map<CompiledHopKey,PlacementEmissionState> emissionStates) {
		Map<CompiledHopKey,PlacementState> states = new LinkedHashMap<>();
		emissionStates.forEach((key, emission) -> states.put(key, emission.placementState()));
		return new NormalizedPlannerResult() {
			@Override public PlacementAnalysis analysis() { return source.analysis(); }
			@Override public String plannerId() { return source.plannerId(); }
			@Override public String analysisFingerprint() { return source.analysisFingerprint(); }
			@Override public Map<CompiledHopKey,PlacementState> selectedStates() { return Map.copyOf(states); }
			@Override public Map<CompiledHopKey,PlacementEmissionState> selectedEmissionStates() {
				return Map.copyOf(emissionStates);
			}
			@Override public List<RelocationActionKey> selectedRelocations() {
				return source.selectedRelocations();
			}
			@Override public List<CandidateSelectionReceipt> selectedCandidateSelections() {
				return source.selectedCandidateSelections();
			}
			@Override public List<RelocationChoiceReceipt> selectedRelocationChoices() {
				return source.selectedRelocationChoices();
			}
			@Override public List<LocalMaterializationActionKey> selectedLocalMaterializations() {
				return source.selectedLocalMaterializations();
			}
			@Override public String objectiveCertificate() { return source.objectiveCertificate(); }
			@Override public String normalizedPlanFingerprint() {
				return source.normalizedPlanFingerprint();
			}
		};
	}

	private static Map<CompiledHopKey, PlacementEmissionState> emissionStatesFor(
		NormalizedPlannerResult source, Map<CompiledHopKey, PlacementState> states) {
		Map<CompiledHopKey, PlacementEmissionState> result = new LinkedHashMap<>();
		states.forEach((key, state) -> {
			PlacementEmissionState exact = source.selectedEmissionStates().get(key);
			result.put(key, exact != null && exact.placementState().equals(state)
				? exact : new PlacementEmissionState(state, false));
		});
		return Map.copyOf(result);
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
			new LinkedHashMap<>(FederatedPlannerUtils.snapshotPlannerRecompileStates()),
			new java.util.TreeSet<>(FederatedPlannerUtils.snapshotAmbiguousPlannerRecompileSignatures()),
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
				+ "|anchor=" + spec.getAnchorHopId() + "|anchorKey=" + spec.getAnchorKey()
				+ "|type=" + spec.getMaterializationFType()
				+ "|consumers=" + spec.getConsumerHopIds()));
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
		FederatedRefedRegistry.register(SEEDED_SCOPE, 11L, 12L, "seed-anchor-key", List.of(13L));
		FederatedFoutMaterializeRegistry.register(SEEDED_SCOPE, 11L, 12L, "ROW", "seed", "seed-anchor-key");
		FederatedLocalMaterializeRegistry.register(SEEDED_SCOPE, 13L, List.of(14L), "ROW", "seed-local");
	}

	private static void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		FederatedPlannerUtils.clearPlannerRecompileStates();
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
	private record StateSnapshot(Map<Long, HopSnapshot> hops, List<String> registries,
		Map<String, FederatedPlannerUtils.PlannerRecompileStateSnapshot> plannerRecompileStates,
		java.util.Set<String> ambiguousPlannerRecompileSignatures, Map<?, ?> receipts,
		long fallbackCount, long repairCount) { }
	@FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
