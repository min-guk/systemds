/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.PlannerRecompileStateSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy.CapturedPlacementRequest;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AdditionalRootInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AppliedPlanReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateMapEntry;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ExactSelection;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.OracleInputState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry.MaterializeSpec;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.LocalMaterializeSpec;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.AnchorSpec;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Executable behavioral RED for immutable, exact DP captured-feasibility ownership. */
public class CampaignBG014CapturedFeasibilityAuthorityRedTest {
	@Test
	public void exactAnchorDecisionRetainsCompleteCapturedAuthority() {
		DpInvocationReceipt invocation = invoke("B-11");
		PlannerState before = plannerState(invocation);
		CandidateDecisionReceipt captured = derivedFoutDecision(invocation);

		FType projected = assertExactlyRetained(invocation, captured);
		Assert.assertSame("B-11 exact anchor must project ROW authority", FType.ROW, projected);
		Assert.assertTrue("the exact B-11 live receipt must retain CP/LOUT", captured.allowCPLOUT());
		Assert.assertTrue("the exact B-11 live receipt must retain CP/FOUT", captured.allowCPFOUT());
		Assert.assertTrue("the exact B-11 live receipt must retain FED/LOUT", captured.allowFEDLOUT());
		Assert.assertTrue("the exact B-11 live receipt must enable the captured FOUT arm", captured.allowFEDFOUT());
		assertLegacyValueOnlyRequestRejectedBeforeMutation(invocation, captured,
			"B-11 value-only captured requests are unbound copies, not exact authority");
		Assert.assertEquals("reading exact B-11 authority mutated planner/global state", before,
			plannerState(invocation));
	}

	@Test
	public void missingAnchorCannotBePromotedByInjectedInputTypes() {
		DpInvocationReceipt invocation = invoke("B-12");
		CandidateDecisionReceipt captured = invocation.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts().stream().filter(value -> value.allowFEDLOUT() && !value.allowFEDFOUT())
			.findFirst().orElseThrow(() -> new AssertionError("B-12 must expose a missing-anchor FED/LOUT arm"));
		PlannerState before = plannerState(invocation);

		FType projected = assertExactlyRetained(invocation, captured);
		Assert.assertTrue("missing-anchor B-12 live receipt must retain CP/LOUT", captured.allowCPLOUT());
		Assert.assertEquals("missing-anchor B-12 CP/FOUT must agree with exact projected authority",
			projected != null && projected != FType.PART && projected != FType.OTHER, captured.allowCPFOUT());
		Assert.assertTrue("missing-anchor B-12 live receipt must retain the FED/LOUT arm", captured.allowFEDLOUT());
		Assert.assertFalse("injected broad input types cannot synthesize exact missing anchor authority",
			captured.allowFEDFOUT());
		Assert.assertFalse("B-12 exact missing-anchor input domain must not borrow the graph-union FED state",
			exactFact(invocation, captured).allowedEmissionStates().stream()
				.anyMatch(emission -> emission.placementState().execType() == ExecType.FED));
		Assert.assertFalse("B-12 decision catalog must remain empty of borrowed FED states",
			captured.allowedEmissionStates().stream()
				.anyMatch(emission -> emission.placementState().execType() == ExecType.FED));
		assertLegacyValueOnlyRequestRejectedBeforeMutation(invocation, captured,
			"B-12 value-only captured requests must not promote missing exact anchor evidence");
		Assert.assertEquals("reading exact B-12 authority mutated planner/global state", before,
			plannerState(invocation));
	}

	@Test
	public void scalarResultCannotPublishMatrixFoutMaterialization() {
		DpInvocationReceipt invocation = invoke("B-11");
		List<CandidateRuleFact> scalarFederatedFacts = invocation.analysis().candidateRuleFacts().orderedFacts()
			.stream().filter(fact -> invocation.analysis().shapeFact(fact.key().parentOccurrence())
				.map(shape -> shape.dataType() == DataType.SCALAR).orElse(false))
			.filter(fact -> fact.capability() != null && fact.capability().nativeExec() == ExecType.FED)
			.toList();

		Assert.assertFalse("B-11 must exercise a scalar result derived from a federated input",
			scalarFederatedFacts.isEmpty());
		for(CandidateRuleFact fact : scalarFederatedFacts) {
			Assert.assertFalse("scalar candidates cannot publish a matrix FOUT materialization",
				fact.allowedEmissionStates().stream()
					.anyMatch(emission -> emission.placementState().output() == FederatedOutput.FOUT));
			Assert.assertFalse("scalar graph nodes cannot retain a matrix FOUT union state",
				invocation.analysis().graph().node(fact.key().parentOccurrence()).orElseThrow()
					.legalAlternatives().stream().anyMatch(state -> state.output() == FederatedOutput.FOUT));
		}
	}

	@Test
	public void ambiguousCallSiteAuthorityRejectsMissingAndIncompatibleFactsWithoutPublication() {
		DpInvocationReceipt invocation = invoke("B-17");
		Constraint distinctCallSites = invocation.analysis().graph().constraints().stream()
			.filter(value -> value.kind() == ConstraintKind.DISTINCT_CONTEXT)
			.filter(value -> invocation.analysis().graph().node(value.left())
				.map(node -> node.kind() == NodeKind.FUNCTION_CALL).orElse(false))
			.filter(value -> invocation.analysis().graph().node(value.right())
				.map(node -> node.kind() == NodeKind.FUNCTION_CALL).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError("B-17 must expose exact distinct call-site authority"));
		CandidateDecisionReceipt captured = invocation.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts().stream()
			.filter(value -> value.candidateSnapshot().parentOccurrence() == distinctCallSites.left()
				|| value.candidateSnapshot().parentOccurrence() == distinctCallSites.right())
			.filter(value -> !value.allowFEDLOUT() && !value.allowFEDFOUT())
			.findFirst().orElseThrow(() -> new AssertionError("B-17 must expose a terminal conflicting call-site decision"));
		PlannerState before = plannerState(invocation);

		assertExactlyRetained(invocation, captured);
		Assert.assertSame("B-17 conflicting authority must terminate as an available captured fact",
			ConstructionDisposition.AVAILABLE, captured.disposition());
		Assert.assertSame("B-17 reason must be the exact captured capability reason",
			captured.capabilityFact().reasonCode(), captured.reasonCode());
		Assert.assertTrue("B-17 conflicting call-site decision must retain CP/LOUT", captured.allowCPLOUT());
		Assert.assertFalse("B-17 conflicting call-site decision must reject CP/FOUT", captured.allowCPFOUT());
		Assert.assertFalse("B-17 conflicting call-site decision must reject FED/LOUT", captured.allowFEDLOUT());
		Assert.assertFalse("ambiguous live receipt cannot publish FED/FOUT", captured.allowFEDFOUT());
		assertLegacyValueOnlyRequestRejectedBeforeMutation(invocation, captured,
			"B-17 value-only captured requests must not guess from ambiguous exact authority");
		Assert.assertEquals("ambiguous rejection mutated planner/global state", before, plannerState(invocation));
	}

	@Test
	public void foreignAndMismatchedOrdinalAuthorityRejectBeforeMutation() {
		DpInvocationReceipt invocation = invoke("B-11");
		DpInvocationReceipt foreignInvocation = invoke("B-12");
		PreSelectionSemanticBlock block = invocation.semanticConsumption().semanticBlock();
		CandidateDecisionReceipt captured = derivedFoutDecision(invocation);
		CandidateDecisionReceipt foreign = foreignInvocation.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts().stream().findFirst()
			.orElseThrow(() -> new AssertionError("foreign fixture must expose a decision receipt"));
		PlannerState before = plannerState(invocation);

		Assert.assertThrows("foreign decision receipts must be rejected before publication",
			IllegalArgumentException.class, () -> new PreSelectionSemanticBlock(block.context(),
				List.of(block.candidateSnapshots().get(0)), List.of(foreign.variantOrdinal()), List.of(foreign),
				1, 1, true));
		Assert.assertThrows("mismatched variant ordinals must not be accepted as retained authority",
			IllegalArgumentException.class, () -> new PreSelectionSemanticBlock(block.context(),
				block.candidateSnapshots(), replaceOrdinal(block, captured, captured.variantOrdinal() + 1),
				block.candidateDecisionReceipts(), block.rawCandidateCount(), block.capturedCandidateCount(),
				block.zeroDifference()));
		Assert.assertEquals("rejected captured-authority attempts mutated planner/global state", before,
			plannerState(invocation));
	}

	private static CandidateDecisionReceipt derivedFoutDecision(DpInvocationReceipt invocation) {
		return invocation.semanticConsumption().semanticBlock().candidateDecisionReceipts().stream()
			.filter(value -> value.allowFEDLOUT() && value.allowFEDFOUT())
			.findFirst().orElseThrow(() -> new AssertionError("B-11 must exercise derived FED/FOUT feasibility"));
	}

	private static FType assertExactlyRetained(DpInvocationReceipt invocation, CandidateDecisionReceipt captured) {
		PreSelectionSemanticBlock block = invocation.semanticConsumption().semanticBlock();
		int index = identityIndex(block.candidateDecisionReceipts(), captured);
		Assert.assertTrue("decision receipt must be retained by exact identity", index >= 0);
		Assert.assertSame(block.context(), captured.context());
		Assert.assertSame(block.candidateSnapshots().get(index), captured.candidateSnapshot());
		Assert.assertEquals(block.candidateVariantOrdinals().get(index).longValue(), captured.variantOrdinal());
		Assert.assertEquals(captured.candidateSnapshot().orderedOracleInputs(), captured.orderedOracleInputs());
		Assert.assertSame(block.context().invocationEvidence().get(captured.candidateSnapshot().parentOccurrence()),
			captured.invocationEvidence());
		Assert.assertSame(invocation.analysis(), captured.context().analysis());
		Assert.assertSame(invocation.semanticConsumption().rewireSnapshot(), captured.context().rewireSnapshot());
		List<CandidateInputState> exactInputs = captured.orderedOracleInputs().stream()
			.map(input -> input == OracleInputState.ABSENT_LOCAL ? CandidateInputState.absentLocal()
				: CandidateInputState.present(FType.valueOf(input.name())))
			.toList();
		CandidateRuleFact exactFact = invocation.analysis().candidateRuleFacts()
			.requireExact(captured.candidateSnapshot().parentOccurrence(), exactInputs);
		Assert.assertSame("decision capability must be the exact retained analysis fact",
			exactFact.capability(), captured.capabilityFact());
		Assert.assertTrue("decision catalog must be a policy-filtered subset of exact fact emission entries",
			new LinkedHashSet<>(exactFact.allowedEmissionStates()).containsAll(captured.allowedEmissionStates()));
		for(PlacementEmissionState emission : captured.allowedEmissionStates())
			Assert.assertTrue("candidate emission state must be exact graph-owned legal identity",
				invocation.analysis().graph().node(captured.candidateSnapshot().parentOccurrence()).orElseThrow()
					.legalAlternatives().stream().anyMatch(state -> state == emission.placementState()));
		for(int i = 0; i < captured.candidateSnapshot().promotedEntries().size(); i++) {
			CandidateMapEntry raw = captured.candidateSnapshot().rawEntries().get(i);
			CandidateMapEntry promoted = captured.candidateSnapshot().promotedEntries().get(i);
			Assert.assertSame("raw/promoted occurrence identity differs", raw.occurrence(), promoted.occurrence());
			Assert.assertEquals("raw/promoted edge position differs", raw.edgePosition(), promoted.edgePosition());
		}
		return PlacementCandidateRuleResolver.projectConsumerSafeType(captured.logicalFType(),
			captured.invocationEvidence().projection());
	}

	private static CandidateRuleFact exactFact(DpInvocationReceipt invocation, CandidateDecisionReceipt captured) {
		List<CandidateInputState> exactInputs = captured.orderedOracleInputs().stream()
			.map(input -> input == OracleInputState.ABSENT_LOCAL ? CandidateInputState.absentLocal()
				: CandidateInputState.present(FType.valueOf(input.name())))
			.toList();
		return invocation.analysis().candidateRuleFacts()
			.requireExact(captured.candidateSnapshot().parentOccurrence(), exactInputs);
	}

	private static void assertLegacyValueOnlyRequestRejectedBeforeMutation(DpInvocationReceipt invocation,
		CandidateDecisionReceipt captured, String message) {
		PlannerState before = plannerState(invocation);
		CapturedPlacementRequest unboundValueCopy = capturedRequest(invocation, captured);
		assertRequestIsOnlyAValueCopy(invocation, unboundValueCopy, captured);
		Assert.assertThrows(message, IllegalArgumentException.class,
			() -> ExecPlacementPolicy.decideCaptured(unboundValueCopy));
		Assert.assertEquals(message + " mutated planner/global state", before, plannerState(invocation));
	}

	private static void assertRequestIsOnlyAValueCopy(DpInvocationReceipt invocation,
		CapturedPlacementRequest request, CandidateDecisionReceipt captured) {
		Hop parent = invocation.analysis().hop(captured.candidateSnapshot().parentOccurrence())
			.orElseThrow(() -> new AssertionError("captured parent occurrence is missing"));
		Assert.assertSame("legacy request carries only the physical parent Hop value", parent, request.hop());
		Assert.assertSame("legacy request copies privacy from the live receipt", captured.privacy(), request.privacy());
		Assert.assertSame("legacy request copies logical FType from the live receipt", captured.logicalFType(),
			request.logicalFType());
		Assert.assertSame("legacy request copies the capability fact value", captured.capabilityFact(),
			request.capabilityFact());
		Assert.assertEquals("legacy request copies effective FType values without retaining receipt identity",
			effectiveFTypes(invocation, captured.candidateSnapshot()), request.effectiveFTypes());
	}

	private static CapturedPlacementRequest capturedRequest(DpInvocationReceipt invocation,
		CandidateDecisionReceipt captured) {
		return new CapturedPlacementRequest(invocation.analysis().hop(captured.candidateSnapshot().parentOccurrence())
			.orElseThrow(() -> new AssertionError("captured parent occurrence is missing")),
			captured.privacy(), captured.logicalFType(), captured.capabilityFact(),
			effectiveFTypes(invocation, captured.candidateSnapshot()));
	}

	private static Map<Long, org.apache.sysds.hops.fedplanner.FTypes.FType> effectiveFTypes(
		DpInvocationReceipt invocation, CandidateOccurrenceSnapshot snapshot) {
		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.FType> effective = new LinkedHashMap<>();
		for(CandidateMapEntry entry : snapshot.promotedEntries()) {
			if(entry.rawFType() == null)
				continue;
			Hop hop = invocation.analysis().hop(entry.occurrence())
				.orElseThrow(() -> new AssertionError("captured input occurrence is missing"));
			effective.put(hop.getHopID(), entry.rawFType());
		}
		return effective;
	}

	private static List<Long> replaceOrdinal(PreSelectionSemanticBlock block,
		CandidateDecisionReceipt target, long replacement) {
		return block.candidateDecisionReceipts().stream()
			.map(value -> value == target ? replacement : value.variantOrdinal()).toList();
	}

	private static int identityIndex(List<CandidateDecisionReceipt> receipts, CandidateDecisionReceipt target) {
		for(int i = 0; i < receipts.size(); i++)
			if(receipts.get(i) == target) return i;
		return -1;
	}

	private static DpInvocationReceipt invoke(String fixture) {
		try {
			DMLProgram program = "B-11".equals(fixture)
				? CampaignBG014HermeticPlannerFixtureFactory.compile(fixture)
				: ProductionShadowFixtureFactory.compile(fixture);
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER,
					"compile_cost_based");
				new DMLTranslator(program).constructLops(program,
					value -> Assert.assertTrue("planner receipt must be published once", receipt.compareAndSet(null, value)));
			}
			finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			Assert.assertSame(program.requirePlacementAnalysisAuthority(), receipt.get().analysis());
			return (DpInvocationReceipt) receipt.get();
		}
		catch(Exception e) { throw new AssertionError("Unable to compile captured-feasibility fixture " + fixture, e); }
	}

	private static PlannerState plannerState(DpInvocationReceipt invocation) {
		return new PlannerState(invocation.analysis().analysisFingerprint(),
			FederatedPlannerUtils.snapshotFedState(), FederatedPlannerUtils.snapshotFedAnchorKeys(),
			FederatedPlannerUtils.snapshotPlannerRecompileStates(),
			FederatedPlannerUtils.snapshotAmbiguousPlannerRecompileSignatures(),
			memoState(invocation), registryState(invocation), publicationState(invocation),
			invocation.counters().repairCount(), invocation.counters().fallbackCount(),
			invocation.counters().reenumerationCount());
	}

	private static MemoState memoState(DpInvocationReceipt invocation) {
		List<CompiledHopKey> exactKeys = new ArrayList<>();
		for(CandidateDecisionReceipt decision : invocation.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts()) {
			addExactKey(exactKeys, decision.candidateSnapshot().parentOccurrence());
			for(CandidateMapEntry entry : decision.candidateSnapshot().promotedEntries())
				addExactKey(exactKeys, entry.occurrence());
		}
		List<MemoPlanState> plans = new ArrayList<>();
		for(CompiledHopKey key : exactKeys) {
			HopOccurrenceProjection occurrence = invocation.analysis().occurrences().stream()
				.filter(value -> value.key() == key).findFirst().orElseThrow();
			plans.add(memoPlanState(invocation, occurrence, FederatedOutput.LOUT));
			plans.add(memoPlanState(invocation, occurrence, FederatedOutput.FOUT));
		}
		return new MemoState(invocation.memo().analysis().analysisFingerprint(), invocation.memo().getNumWorkers(),
			List.copyOf(invocation.memo().getAdditionalRootHopIDs()), List.copyOf(plans));
	}

	private static void addExactKey(List<CompiledHopKey> keys, CompiledHopKey key) {
		if(keys.stream().noneMatch(value -> value == key)) keys.add(key);
	}

	private static MemoPlanState memoPlanState(DpInvocationReceipt invocation,
		HopOccurrenceProjection occurrence, FederatedOutput output) {
		FedPlan plan = invocation.memo().getFedPlanAfterPrune(occurrence, output);
		if(plan == null)
			return new MemoPlanState(occurrence.key(), output, false, null, null, null, false, false,
				0L, 0L, 0L, List.of());
		return new MemoPlanState(occurrence.key(), output, true, plan.getExecType(), plan.getFType(),
			plan.getCpFoutType(), plan.isDerivedFedFout(), plan.isFoutMaterializationAccounted(),
			Double.doubleToRawLongBits(plan.getCumulativeCost()), Double.doubleToRawLongBits(plan.getSelfCost()),
			Double.doubleToRawLongBits(plan.getForwardingCost()), List.copyOf(plan.getChildFedPlans()));
	}

	private static RegistryState registryState(DpInvocationReceipt invocation) {
		Set<Long> scopes = new LinkedHashSet<>();
		scopes.add(-1L);
		for(HopOccurrenceProjection occurrence : invocation.analysis().occurrences()) scopes.add(occurrence.scopeId());
		List<Long> orderedScopes = new ArrayList<>(scopes);
		orderedScopes.sort(Long::compareTo);
		List<RegistryScopeState> snapshots = new ArrayList<>();
		for(long scope : orderedScopes)
			snapshots.add(new RegistryScopeState(scope, FederatedRefedRegistry.snapshot(scope),
				FederatedFoutMaterializeRegistry.snapshot(scope),
				FederatedLocalMaterializeRegistry.snapshot(scope)));
		return new RegistryState(List.copyOf(snapshots));
	}

	private static PublicationState publicationState(DpInvocationReceipt invocation) {
		return new PublicationState(invocation.semanticConsumption().semanticBlock(), invocation.exactSelection(),
			invocation.appliedPlans(), invocation.additionalRootInvocations());
	}

	private record PlannerState(String analysisFingerprint,
		Map<String, FederatedPlannerUtils.FedVarSnapshot> fedState, Map<String, String> anchorKeys,
		Map<String, PlannerRecompileStateSnapshot> recompileStates, Set<String> ambiguousRecompileSignatures,
		MemoState memo, RegistryState registries, PublicationState publication,
		int repairs, int fallbacks, int reenumerations) { }

	private record MemoState(String analysisFingerprint, int numWorkers, List<Long> additionalRootHopIds,
		List<MemoPlanState> plans) { }

	private record MemoPlanState(CompiledHopKey occurrence, FederatedOutput output, boolean present,
		ExecType execType, FType fType, FType cpFoutType, boolean derivedFedFout,
		boolean foutMaterializationAccounted, long cumulativeCostBits, long selfCostBits,
		long forwardingCostBits, List<Pair<Long, FederatedOutput>> childEdges) { }

	private record RegistryState(List<RegistryScopeState> scopes) { }

	private record RegistryScopeState(long scope, Map<Long, AnchorSpec> refed,
		Map<Long, MaterializeSpec> foutMaterialize, Map<Long, LocalMaterializeSpec> localMaterialize) { }

	private record PublicationState(PreSelectionSemanticBlock semanticBlock, ExactSelection exactSelection,
		List<AppliedPlanReceipt> appliedPlans, List<AdditionalRootInvocationReceipt> additionalRootInvocations) { }
}
