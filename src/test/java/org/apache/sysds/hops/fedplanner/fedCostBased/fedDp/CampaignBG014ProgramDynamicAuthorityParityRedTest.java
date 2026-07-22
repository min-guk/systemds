/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.PlannerRecompileStateSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AppliedPlanReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpDynamicInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.DpSemanticConstructionException;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.OracleInputState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED for final program/dynamic DP authority and adapter-receipt parity. */
public class CampaignBG014ProgramDynamicAuthorityParityRedTest {
	@Test
	public void cfgReplayClosesExactPhysicalConsumerCandidateFacts() {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(compile("B-21"));
		Assert.assertEquals("B-21 must retain exactly one logical TWrite-to-TRead authority", 1,
			analysis.logicalTransientInputsInCanonicalOrder().size());
		LogicalTransientInputFact logical = analysis.logicalTransientInputsInCanonicalOrder().get(0);
		List<CompiledInputEdgeFact> physicalConsumers = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == logical.targetRead()).toList();
		Assert.assertEquals("replayed TRead must have one exact physical consumer", 1, physicalConsumers.size());
		CompiledInputEdgeFact physical = physicalConsumers.get(0);
		Assert.assertEquals(0, physical.inputPosition());
		Assert.assertTrue("physical consumer must not gain a fabricated logical authority",
			analysis.logicalTransientInputsInCanonicalOrder().stream()
				.noneMatch(fact -> fact.targetRead() == physical.consumer()));
		Assert.assertTrue("TWrite-to-TRead relation must not be a compiled physical edge",
			analysis.compiledInputEdgesInCanonicalOrder().stream().noneMatch(edge ->
				edge.producer() == logical.sourceWrite() && edge.consumer() == logical.targetRead()));

		List<List<CandidateInputState>> vectors = analysis.candidateRuleDomain().orderedRuleKeys().stream()
			.filter(key -> key.parentOccurrence() == physical.consumer())
			.map(key -> key.orderedInputs()).toList();
		List<CandidateInputState> local = List.of(CandidateInputState.absentLocal());
		List<CandidateInputState> row = List.of(CandidateInputState.present(FType.ROW));
		Assert.assertTrue("physical consumer must retain its local candidate", vectors.contains(local));
		Assert.assertTrue("post-CFG closure must publish the physical PRESENT ROW candidate", vectors.contains(row));
		CandidateRuleFact rowFact = analysis.candidateRuleFacts().requireExact(physical.consumer(), row);
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, rowFact.status());
		Assert.assertEquals(ExecType.FED, rowFact.capability().nativeExec());
		Assert.assertEquals(FederatedOutput.FOUT, rowFact.capability().nativeOutput());
		Assert.assertEquals(FType.ROW, rowFact.capability().nativeFoutFType());
	}

	@Test
	public void programAndDynamicEntrypointsRetainExactAdapterReceipts() {
		ProgramInvocation owner = invokeProgram("B-21");
		FunctionStatementBlock function = owner.program().getFunctionStatementBlock(
			DMLProgram.DEFAULT_NAMESPACE, "f");
		assertAppliedPlansAreExactReceipts(owner.receipt());
		assertTransientReadLogicalParity(owner.receipt().semanticConsumption().semanticBlock());
		assertScalarTransientForwardDependency(owner.receipt().semanticConsumption().semanticBlock());
		DpDynamicInvocationReceipt dynamic;
		try {
			dynamic = new FederatedPlannerDpFedCostBased().rewriteFunctionDynamic(
				function, new LocalVariableMap(), owner.receipt().analysis());
		}
		catch(DpSemanticConstructionException failure) {
			PlacementAnalysis analysis = owner.receipt().analysis();
			String nodeKind = analysis.graph().node(failure.parentOccurrence())
				.map(node -> node.kind().name()).orElse("MISSING");
			String hop = analysis.hop(failure.parentOccurrence())
				.map(value -> value.getOpString() + '#' + value.getHopID()).orElse("MISSING");
			throw new AssertionError("G014_RED5_DYNAMIC_STRUCTURAL_OWNER"
				+ "|reason=" + failure.reasonCode()
				+ "|disposition=" + failure.disposition()
				+ "|analysisFingerprint=" + failure.analysisFingerprint()
				+ "|ownerKey=" + failure.parentOccurrence().normalizedSignature()
				+ "|namespace=" + failure.parentOccurrence().functionNamespace()
				+ "|callSite=" + failure.parentOccurrence().callSitePath()
				+ "|recompile=" + failure.parentOccurrence().recompileContext()
				+ "|nodeKind=" + nodeKind + "|hop=" + hop, failure);
		}

		Assert.assertSame(owner.receipt().analysis(), owner.receipt().exactSelection().analysis());
		Assert.assertSame(owner.receipt().analysis(), owner.receipt().semanticConsumption().analysis());
		Assert.assertSame(owner.receipt().analysis(), dynamic.analysis());
		Assert.assertSame(dynamic.analysis(), dynamic.memoTable().analysis());
		Assert.assertSame(dynamic.analysis(), dynamic.enumerationResult().rewireSnapshot().analysis());
		Assert.assertSame(dynamic.analysis(), dynamic.enumerationResult().semanticBlock().context().analysis());
		assertTransientReadLogicalParity(dynamic.enumerationResult().semanticBlock());
		assertScalarTransientForwardDependency(dynamic.enumerationResult().semanticBlock());
		DpPlacementAdapter.ExactSelection dynamicSelection = new DpPlacementAdapter().selectExact(
			dynamic.analysis(), dynamic.memoTable(), dynamic.enumerationResult().optimalPlan());
		Assert.assertSame(dynamic.analysis(), dynamicSelection.analysis());
		Assert.assertSame(dynamic.memoTable(), dynamicSelection.memo());
		Assert.assertSame(dynamic.enumerationResult().optimalPlan(), dynamicSelection.legacyOptimalPlan());
		assertSelectedRootsAreExactMemoReceipts(dynamicSelection, dynamic.memoTable());
		Assert.assertFalse("B-21 must exercise an exact function selection",
			owner.receipt().exactSelection().selectedRootPlans().isEmpty());
		Assert.assertEquals(dynamic.fingerprintBefore(), dynamic.fingerprintAfter());
		Assert.assertEquals(dynamic.analysis().analysisFingerprint(), dynamic.fingerprintAfter());
	}

	private static void assertScalarTransientForwardDependency(PreSelectionSemanticBlock block) {
		List<ScalarTransientDependency> dependencies = new ArrayList<>();
		for(CandidateOccurrenceSnapshot snapshot : block.candidateSnapshots()) {
			List<?> entries = transientForwardDependencies(snapshot);
			for(Object entry : entries)
				dependencies.add(new ScalarTransientDependency(snapshot,
					invokeAccessor(entry, "forwardEdge", RewireTransientForwardEdge.class),
					invokeAccessor(entry, "sourceOccurrence", CompiledHopKey.class),
					invokeAccessor(entry, "collectedPosition", Integer.class),
					invokeAccessor(entry, "selectedSourceState", PlacementState.class)));
		}
		Assert.assertEquals("B-21 must publish exactly one scalar transient-forward dependency receipt",
			1, dependencies.size());
		ScalarTransientDependency dependency = dependencies.get(0);
		CandidateOccurrenceSnapshot snapshot = dependency.snapshot();
		PlacementAnalysis analysis = block.context().analysis();
		RewireTransientForwardEdge forward = dependency.forwardEdge();
		Assert.assertSame(block.context(), snapshot.context());
		Assert.assertSame(forward.writeOccurrence(), dependency.sourceOccurrence());
		Assert.assertSame(forward.readOccurrence(), snapshot.parentOccurrence());
		Assert.assertEquals(0, dependency.collectedPosition());
		Assert.assertEquals(org.apache.sysds.common.Types.DataType.SCALAR,
			analysis.hop(forward.writeOccurrence()).orElseThrow().getDataType());
		Assert.assertEquals(org.apache.sysds.common.Types.DataType.SCALAR,
			analysis.hop(forward.readOccurrence()).orElseThrow().getDataType());
		Assert.assertEquals(1, block.context().rewireSnapshot().transientForwardEdges().stream()
			.filter(edge -> edge == forward).count());
		Assert.assertTrue(analysis.compiledInputEdgesInCanonicalOrder().stream().noneMatch(edge ->
			edge.producer() == forward.writeOccurrence() && edge.consumer() == forward.readOccurrence()));
		Assert.assertTrue(analysis.logicalTransientInputsInCanonicalOrder().stream().noneMatch(fact ->
			fact.sourceWrite() == forward.writeOccurrence() && fact.targetRead() == forward.readOccurrence()));
		Assert.assertTrue(snapshot.rawEntries().isEmpty());
		Assert.assertTrue(snapshot.promotedEntries().isEmpty());
		Assert.assertTrue(snapshot.logicalEntries().isEmpty());
		Assert.assertTrue(snapshot.orderedOracleInputs().isEmpty());
		PlacementState selected = dependency.selectedSourceState();
		Assert.assertEquals(ExecType.CP, selected.execType());
		Assert.assertEquals(FederatedOutput.LOUT, selected.output());
		Assert.assertNull(selected.fType());
		Assert.assertEquals(1, analysis.graph().node(forward.writeOccurrence()).orElseThrow()
			.legalAlternatives().stream().filter(state -> state == selected).count());
		CandidateRuleFact empty = analysis.candidateRuleFacts().requireExact(forward.readOccurrence(), List.of());
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, empty.status());
		Assert.assertEquals(ExecType.CP, empty.capability().nativeExec());
		Assert.assertEquals(FederatedOutput.LOUT, empty.capability().nativeOutput());
		Assert.assertNull(empty.capability().nativeFoutFType());
	}

	private static List<?> transientForwardDependencies(CandidateOccurrenceSnapshot snapshot) {
		return invokeAccessor(snapshot, "transientForwardDependencies", List.class);
	}

	private static <T> T invokeAccessor(Object owner, String name, Class<T> type) {
		try { return type.cast(owner.getClass().getMethod(name).invoke(owner)); }
		catch(ReflectiveOperationException failure) {
			throw new AssertionError("G014_RED6_EXACT_NONCARRIER_TRANSIENT_FORWARD_RECEIPT_MISSING", failure);
		}
	}

	private record ScalarTransientDependency(CandidateOccurrenceSnapshot snapshot,
		RewireTransientForwardEdge forwardEdge, CompiledHopKey sourceOccurrence, int collectedPosition,
		PlacementState selectedSourceState) { }

	private static void assertTransientReadLogicalParity(PreSelectionSemanticBlock block) {
		List<List<OracleInputState>> vectors = block.candidateSnapshots().stream()
			.filter(snapshot -> block.context().analysis().graph().node(snapshot.parentOccurrence())
				.orElseThrow().kind() == NodeKind.TRANSIENT_READ)
			.filter(snapshot -> !snapshot.logicalEntries().isEmpty())
			.map(snapshot -> snapshot.orderedOracleInputs()).distinct().toList();
		Assert.assertEquals("B-21 transient read must consume nonempty local and ROW logical vectors",
			List.of(List.of(OracleInputState.ABSENT_LOCAL), List.of(OracleInputState.ROW)), vectors);
		Assert.assertTrue("logical transient candidate must retain zero physical inputs",
			block.candidateSnapshots().stream().filter(snapshot -> !snapshot.logicalEntries().isEmpty())
				.allMatch(snapshot -> snapshot.rawEntries().isEmpty()
					&& snapshot.promotedEntries().isEmpty() && snapshot.logicalEntries().size() == 1));
	}

	@Test
	public void foreignAndUnboundAuthorityRejectBeforePlannerStatePublication() {
		ProgramInvocation owner = invokeProgram("B-21");
		DMLProgram foreignProgram = compile("B-21");
		PlacementAnalysis foreign = new NeutralPlacementGraphBuilder().buildAnalysis(foreignProgram);
		FunctionStatementBlock function = owner.program().getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, "f");
		FederatedPlannerDpFedCostBased planner = new FederatedPlannerDpFedCostBased();

		assertAuthorityPairRejectsWithoutMutation(planner, owner, function, foreign, "foreign");

		PlacementAnalysis copied = CampaignBPlacementAnalysisFixtureBridge.withProjectionOrder(
			owner.receipt().analysis(), owner.program(),
			CampaignBPlacementAnalysisFixtureBridge.ProjectionOrder.NORMAL);
		Assert.assertNotSame(owner.receipt().analysis(), copied);
		Assert.assertEquals(owner.receipt().analysis().analysisFingerprint(), copied.analysisFingerprint());
		copied.assertProgramOwner(owner.program());
		assertAuthorityPairRejectsWithoutMutation(planner, owner, function, copied, "copied same-value");

		PlacementAnalysis detached = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(owner.program());
		Assert.assertNotSame(owner.receipt().analysis(), detached);
		detached.assertProgramOwner(owner.program());
		assertAuthorityPairRejectsWithoutMutation(planner, owner, function, detached, "detached");

		DMLProgram unbound = compile("B-21");
		FunctionStatementBlock unboundFunction = unbound.getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, "f");
		BoundaryState unboundBefore = snapshot(unbound, null);
		assertRejects(IllegalStateException.class, () -> planner.rewriteProgram(unbound, null, null),
			"unbound program authority");
		assertRejects(IllegalStateException.class,
			() -> planner.rewriteFunctionDynamic(unboundFunction, new LocalVariableMap()),
			"unbound dynamic authority");
		Assert.assertEquals("unbound authority rejection mutated program/global state", unboundBefore,
			snapshot(unbound, null));
		Assert.assertSame(owner.receipt().analysis(), owner.program().requirePlacementAnalysisAuthority());
		Assert.assertEquals(owner.receipt().analysisFingerprintBefore(),
			owner.receipt().analysisFingerprintAfter());
		Assert.assertEquals(0, owner.receipt().counters().repairCount());
		Assert.assertEquals(0, owner.receipt().counters().fallbackCount());
		Assert.assertEquals(0, owner.receipt().counters().reenumerationCount());
	}

	private static void assertAuthorityPairRejectsWithoutMutation(FederatedPlannerDpFedCostBased planner,
		ProgramInvocation owner, FunctionStatementBlock function, PlacementAnalysis rejected, String label) {
		BoundaryState before = snapshot(owner.program(), owner.receipt().analysis());
		assertRejects(() -> planner.rewriteProgram(owner.program(), null, null, rejected),
			label + " program authority");
		Assert.assertEquals(label + " program rejection mutated planner/memo/registry state", before,
			snapshot(owner.program(), owner.receipt().analysis()));
		assertRejects(() -> planner.rewriteFunctionDynamic(function, new LocalVariableMap(), rejected),
			label + " dynamic authority");
		Assert.assertEquals(label + " dynamic rejection mutated planner/memo/registry state", before,
			snapshot(owner.program(), owner.receipt().analysis()));
	}

	private static void assertAppliedPlansAreExactReceipts(DpInvocationReceipt receipt) {
		Assert.assertSame(receipt.memo(), receipt.exactSelection().memo());
		List<FedPlan> roots = receipt.exactSelection().selectedRootPlans();
		Assert.assertFalse("exact adapter selection must publish root receipts", roots.isEmpty());
		for(int i = 0; i < roots.size(); i++) {
			FedPlan selected = roots.get(i);
			AppliedPlanReceipt applied = receipt.appliedPlans().get(i);
			long planningHopId = receipt.exactSelection().aggregateChildEdges().get(i).getLeft();
			long executableHopId = receipt.memo().resolveOriginalHopId(planningHopId);
			Assert.assertEquals(i, applied.ordinal());
			Assert.assertFalse(applied.additionalRoot());
			Assert.assertEquals(planningHopId, applied.planningHopId());
			Assert.assertEquals(receipt.exactSelection().aggregateChildEdges().get(i).getRight(), applied.output());
			Assert.assertSame(selected, applied.plan());
			Assert.assertSame(receipt.exactSelection().selectedRootHops().get(i), applied.planningHop());
			Assert.assertEquals(executableHopId, applied.executableHopId());
			Assert.assertSame(receipt.memo().resolveOriginalHop(planningHopId), applied.executableHop());
		}
	}

	private static void assertSelectedRootsAreExactMemoReceipts(DpPlacementAdapter.ExactSelection selection,
		FederatedPlannerDpMemoTable memo) {
		Assert.assertFalse("exact dynamic selection must publish root receipts",
			selection.selectedRootPlans().isEmpty());
		Assert.assertEquals(selection.selectedRootPlans().size(), selection.aggregateChildEdges().size());
		Assert.assertEquals(selection.selectedRootPlans().size(), selection.selectedRootHops().size());
		for(int i = 0; i < selection.selectedRootPlans().size(); i++) {
			FedPlan selected = selection.selectedRootPlans().get(i);
			long planningHopId = selection.aggregateChildEdges().get(i).getLeft();
			Assert.assertSame(selected, memo.getFedPlanAfterPrune(selection.aggregateChildEdges().get(i)));
			Assert.assertSame(selected.getHopRef(), selection.selectedRootHops().get(i));
			Assert.assertEquals(selected.getExecType(), selected.getHopRef().getForcedExecType());
			Assert.assertEquals(selected.getFedOutType(), selected.getHopRef().getFederatedOutput());
			Assert.assertEquals(selected.getExecType(), memo.resolveOriginalHop(planningHopId).getForcedExecType());
			Assert.assertEquals(selected.getFedOutType(),
				memo.resolveOriginalHop(planningHopId).getFederatedOutput());
		}
	}

	private static BoundaryState snapshot(DMLProgram program, PlacementAnalysis analysis) {
		return new BoundaryState(PlacementGraphFingerprint.capture(program),
			analysis == null ? null : analysis.analysisFingerprint(),
			FederatedPlannerUtils.snapshotFedState(), FederatedPlannerUtils.snapshotFedAnchorKeys(),
			FederatedPlannerUtils.snapshotPlannerRecompileStates(),
			FederatedPlannerUtils.snapshotAmbiguousPlannerRecompileSignatures(),
			registrySnapshot(analysis));
	}

	private static List<String> registrySnapshot(PlacementAnalysis analysis) {
		Set<Long> scopes = new LinkedHashSet<>();
		scopes.add(-1L);
		if(analysis != null)
			analysis.occurrences().forEach(occurrence -> scopes.add(occurrence.scopeId()));
		List<Long> ordered = new ArrayList<>(scopes);
		ordered.sort(Long::compareTo);
		return ordered.stream().map(scope -> scope + "|R=" + FederatedRefedRegistry.snapshot(scope)
			+ "|F=" + FederatedFoutMaterializeRegistry.snapshot(scope)
			+ "|L=" + FederatedLocalMaterializeRegistry.snapshotScopes(scope)).toList();
	}

	private static ProgramInvocation invokeProgram(String fixture) {
		DMLProgram program = compile(fixture);
		String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
		AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
		try {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			new DMLTranslator(program).constructLops(program, receipt::set);
		}
		catch(Exception e) { throw new AssertionError("Unable to invoke program fixture " + fixture, e); }
		finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
		Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
		return new ProgramInvocation(program, (DpInvocationReceipt) receipt.get());
	}

	private static DMLProgram compile(String fixture) {
		try { return "B-21".equals(fixture) ? CampaignBG014HermeticPlannerFixtureFactory.compile(fixture)
			: ProductionShadowFixtureFactory.compile(fixture); }
		catch(Exception e) { throw new AssertionError("Unable to compile authority fixture " + fixture, e); }
	}

	private static void assertRejects(Runnable action, String label) {
		assertRejects(IllegalArgumentException.class, action, label);
	}

	private static void assertRejects(Class<? extends RuntimeException> expectedType,
		Runnable action, String label) {
		try { action.run(); Assert.fail("accepted " + label); }
		catch(RuntimeException expected) {
			Assert.assertTrue("wrong rejection for " + label + ": " + expected,
				expectedType.isInstance(expected));
			Assert.assertNotNull(expected.getMessage());
		}
	}

	private record BoundaryState(String programFingerprint, String analysisFingerprint,
		Map<String, FederatedPlannerUtils.FedVarSnapshot> fedState, Map<String, String> anchorKeys,
		Map<String, PlannerRecompileStateSnapshot> recompileStates, Set<String> ambiguousRecompileSignatures,
		List<String> registryState) { }
	private record ProgramInvocation(DMLProgram program, DpInvocationReceipt receipt) { }
}
