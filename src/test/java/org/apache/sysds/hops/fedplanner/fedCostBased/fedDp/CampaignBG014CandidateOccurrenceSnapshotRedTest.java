/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.InvocationCounters;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.CandidateNormalizationFixture;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationObserver;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateMapEntry;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.DpSemanticConstructionException;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.MapEntryState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.OracleInputState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
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

/** Authoritative compile-time RED for neutral raw/promoted DP candidate facts. */
public class CampaignBG014CandidateOccurrenceSnapshotRedTest {
	@Test
	public void semanticDomainsHaveTheExactStableOrderAndDoNotCollapsePresentNull() {
		Assert.assertEquals(List.of("ABSENT_LOCAL", "PRESENT_NULL", "PRESENT_ROW", "PRESENT_COL",
			"PRESENT_FULL", "PRESENT_BROADCAST", "PRESENT_PART", "PRESENT_OTHER"),
			Arrays.stream(MapEntryState.values()).map(Enum::name).toList());
		Assert.assertEquals(List.of("ABSENT_LOCAL", "ROW", "COL", "FULL", "BROADCAST", "PART", "OTHER"),
			Arrays.stream(OracleInputState.values()).map(Enum::name).toList());
		Assert.assertEquals(List.of("AVAILABLE", "ANCHOR_METADATA_INCOMPLETE", "UNSUPPORTED_ANCHOR_METADATA",
			"FOREIGN_CONTEXT", "STALE_CONTEXT", "DUPLICATE_OCCURRENCE", "REORDERED_EDGE",
			"UNMAPPABLE_OCCURRENCE"),
			Arrays.stream(ConstructionDisposition.values()).map(Enum::name).toList());

		DpInvocationReceipt invocation = invoke("B-11");
		HopOccurrenceProjection occurrence = invocation.analysis().occurrences().get(0);
		CandidateMapEntry presentNull = new CandidateMapEntry(occurrence.key(), 0, true, null,
			MapEntryState.PRESENT_NULL, null);
		Assert.assertTrue(presentNull.mapContainsKey());
		Assert.assertNull(presentNull.rawFType());
		Assert.assertSame(MapEntryState.PRESENT_NULL, presentNull.mapEntryState());
		Assert.assertNull("present-null has no oracle state", presentNull.oracleInputState());
	}

	@Test
	public void realInvocationCapturesEveryRawCandidateBeforeSelection() {
		DpInvocationReceipt invocation = invoke("B-11");
		PreSelectionSemanticBlock block = invocation.semanticConsumption().semanticBlock();
		Assert.assertSame(invocation.analysis(), block.context().analysis());
		Assert.assertSame(invocation.semanticConsumption().rewireSnapshot(), block.context().rewireSnapshot());
		Assert.assertEquals(block.rawCandidateCount(), block.capturedCandidateCount());
		Assert.assertTrue("successful canonical enumeration is zero-difference", block.zeroDifference());
		Assert.assertFalse("fixture must exercise candidate capture", block.candidateSnapshots().isEmpty());
		assertRawCandidateOrder(invocation, block.candidateSnapshots());
		for(CandidateOccurrenceSnapshot snapshot : block.candidateSnapshots()) {
			Assert.assertSame(block.context(), snapshot.context());
			Assert.assertSame(ConstructionDisposition.AVAILABLE, snapshot.disposition());
			Assert.assertEquals("AVAILABLE", snapshot.reasonCode());
			Assert.assertEquals(snapshot.rawEntries().size(), snapshot.promotedEntries().size());
			Assert.assertEquals(snapshot.promotedEntries().size(), snapshot.orderedOracleInputs().size());
			for(int i = 0; i < snapshot.rawEntries().size(); i++) {
				CandidateMapEntry raw = snapshot.rawEntries().get(i);
				CandidateMapEntry promoted = snapshot.promotedEntries().get(i);
				Assert.assertEquals(i, raw.edgePosition());
				Assert.assertEquals(i, promoted.edgePosition());
				Assert.assertEquals(raw.occurrence(), promoted.occurrence());
				if(!raw.mapContainsKey()) Assert.assertSame(MapEntryState.ABSENT_LOCAL, raw.mapEntryState());
				if(raw.mapContainsKey() && raw.rawFType() == null)
					Assert.assertSame(MapEntryState.PRESENT_NULL, raw.mapEntryState());
				if(raw.rawFType() != null) assertFTypeProjection(raw.rawFType(), raw);
			}
			assertImmutable(snapshot.rawEntries());
			assertImmutable(snapshot.promotedEntries());
			assertImmutable(snapshot.orderedOracleInputs());
		}
		assertImmutable(block.candidateSnapshots());
	}

	private static void assertRawCandidateOrder(DpInvocationReceipt invocation,
		List<CandidateOccurrenceSnapshot> snapshots) {
		List<ExpectedRawCandidate> expected = independentlyDeriveRawCandidates(invocation);
		Assert.assertEquals("complete raw candidate sequence", expected.size(), snapshots.size());
		for(int rawOrdinal = 0; rawOrdinal < expected.size(); rawOrdinal++) {
			ExpectedRawCandidate want = expected.get(rawOrdinal);
			CandidateOccurrenceSnapshot actual = snapshots.get(rawOrdinal);
			Assert.assertSame("raw parent identity " + rawOrdinal, want.parent().key(), actual.parentOccurrence());
			Assert.assertEquals("raw input multiplicity " + rawOrdinal, want.inputs().size(), actual.rawEntries().size());
			for(int edge = 0; edge < want.inputs().size(); edge++) {
				ExpectedRawInput input = want.inputs().get(edge);
				CandidateMapEntry entry = actual.rawEntries().get(edge);
				Assert.assertSame("raw child identity " + rawOrdinal + ':' + edge,
					input.child().key(), entry.occurrence());
				Assert.assertEquals(edge, entry.edgePosition());
				Assert.assertEquals("raw output arm " + rawOrdinal + ':' + edge,
					input.output() == FederatedOutput.FOUT, entry.mapContainsKey());
			}
		}
	}

	private static List<ExpectedRawCandidate> independentlyDeriveRawCandidates(DpInvocationReceipt invocation) {
		Map<Hop, HopOccurrenceProjection> exact = new java.util.IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : invocation.analysis().occurrences())
			Assert.assertNull("one occurrence per concrete candidate Hop", exact.put(occurrence.hop(), occurrence));
		List<ExpectedRawCandidate> expected = new ArrayList<>();
		for(HopOccurrenceProjection parent : invocation.analysis().occurrences()) {
			List<HopOccurrenceProjection> both = new ArrayList<>();
			List<HopOccurrenceProjection> loutOnly = new ArrayList<>();
			List<HopOccurrenceProjection> foutOnly = new ArrayList<>();
			for(Hop inputHop : parent.hop().getInput()) {
				HopOccurrenceProjection child = exact.get(inputHop);
				Assert.assertNotNull("raw input must resolve by exact object identity", child);
				boolean hasLout = invocation.memo().contains(inputHop.getHopID(), FederatedOutput.LOUT);
				boolean hasFout = invocation.memo().contains(inputHop.getHopID(), FederatedOutput.FOUT);
				Assert.assertTrue("raw input has no output arm", hasLout || hasFout);
				if(hasLout && hasFout) both.add(child);
				else if(hasLout) loutOnly.add(child);
				else foutOnly.add(child);
			}
			long variants = 1L << both.size();
			for(long variant = 0; variant < variants; variant++) {
				List<ExpectedRawInput> inputs = new ArrayList<>();
				for(int bit = 0; bit < both.size(); bit++)
					inputs.add(new ExpectedRawInput(both.get(bit),
						(variant & (1L << bit)) == 0 ? FederatedOutput.LOUT : FederatedOutput.FOUT));
				loutOnly.forEach(value -> inputs.add(new ExpectedRawInput(value, FederatedOutput.LOUT)));
				foutOnly.forEach(value -> inputs.add(new ExpectedRawInput(value, FederatedOutput.FOUT)));
				expected.add(new ExpectedRawCandidate(parent, List.copyOf(inputs)));
			}
		}
		return List.copyOf(expected);
	}

	@Test
	public void presentNullAndReorderedEdgesAbortTheCanonicalInvocationBeforeAnyPublication() {
		Fixture fixture = fixture("B-15");
		DpInvocationReceipt invocation = fixture.receipt();
		HopOccurrenceProjection parent = invocation.analysis().occurrences().stream()
			.filter(value -> value.hop().getInput().size() >= 2).findFirst().orElseThrow();
		List<Hop> exactInputs = List.copyOf(parent.hop().getInput());
		List<Pair<Long, FederatedOutput>> exactEdges = exactInputs.stream()
			.map(value -> Pair.of(value.getHopID(), FederatedOutput.LOUT)).toList();
		List<FType> localTypes = new ArrayList<>(java.util.Collections.nCopies(exactInputs.size(), null));

		Map<Long, FType> presentNull = new LinkedHashMap<>();
		presentNull.put(exactInputs.get(0).getHopID(), null);
		assertCanonicalTypedAbort(fixture, parent, exactEdges, exactInputs, localTypes, presentNull,
			ConstructionDisposition.ANCHOR_METADATA_INCOMPLETE, "PRESENT_NULL");

		List<Hop> reorderedInputs = new ArrayList<>(exactInputs);
		java.util.Collections.swap(reorderedInputs, 0, 1);
		assertCanonicalTypedAbort(fixture, parent, exactEdges, reorderedInputs, localTypes, Map.of(),
			ConstructionDisposition.REORDERED_EDGE, "REORDERED_EDGE");
	}

	private static void assertCanonicalTypedAbort(Fixture fixture,
		HopOccurrenceProjection parent, List<Pair<Long, FederatedOutput>> planChilds, List<Hop> collectedHops,
		List<FType> collectedFTypes, Map<Long, FType> fedInputTypeMap, ConstructionDisposition disposition,
		String reasonCode) {
		DpInvocationReceipt setup = fixture.receipt();
		PlacementAnalysis analysis = setup.analysis();
		ProgramState before = snapshotProgram(fixture.program(), analysis);
		TrackingMemo memo = new TrackingMemo(analysis);
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		assertRegistriesEmpty(fixture.program());
		CandidateNormalizationFixture negative = new CandidateNormalizationFixture(parent, planChilds,
			collectedHops, collectedFTypes, fedInputTypeMap);
		TrackingObserver observer = new TrackingObserver();
		try {
			DpInvocationReceipt published = new FederatedPlannerDpFedCostBased().rewriteProgram(fixture.program(),
				new FunctionCallGraph(fixture.program()), null, analysis, memo, negative, observer);
			Assert.fail("canonical negative invocation published terminal receipt " + published);
		}
		catch(DpSemanticConstructionException expected) {
			Assert.assertSame("typed failure propagation identity", observer.failure(), expected);
			Assert.assertSame(disposition, expected.disposition());
			Assert.assertEquals(reasonCode, expected.reasonCode());
			Assert.assertEquals(analysis.analysisFingerprint(), expected.analysisFingerprint());
			Assert.assertSame(parent.key(), expected.parentOccurrence());
			Assert.assertEquals(planChilds.size(), expected.edgeCount());
		}
		Assert.assertTrue("negative canonical invocation mutated memo: " + memo.writeEvents(),
			memo.writeEvents().isEmpty());
		Assert.assertEquals(0, observer.resultCount());
		Assert.assertEquals(0, observer.oracleCount());
		Assert.assertEquals(0, observer.costCount());
		Assert.assertEquals(0, observer.placementCount());
		Assert.assertEquals(0, observer.candidateCount());
		Assert.assertEquals(0, observer.repairCount());
		Assert.assertEquals(0, observer.fallbackCount());
		assertRegistriesEmpty(fixture.program());
		assertProgramSame(before, snapshotProgram(fixture.program(), analysis));
		Assert.assertSame("setup counters changed", setup.counters(), fixture.receipt().counters());
	}

	private static void assertFTypeProjection(FType type, CandidateMapEntry entry) {
		Assert.assertEquals("PRESENT_" + type.name(), entry.mapEntryState().name());
		Assert.assertEquals(type.name(), entry.oracleInputState().name());
	}

	private static PlannerState snapshot(DpInvocationReceipt invocation) {
		PlacementAnalysis analysis = invocation.analysis();
		Set<Long> ids = new LinkedHashSet<>();
		analysis.occurrences().forEach(value -> ids.add(value.hop().getHopID()));
		ids.addAll(invocation.memo().getAdditionalRootHopIDs());
		invocation.exactSelection().aggregateChildEdges().forEach(value -> ids.add(value.getLeft()));
		Set<FedPlan> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		ArrayDeque<FedPlan> queue = new ArrayDeque<>(invocation.exactSelection().selectedRootPlans());
		while(!queue.isEmpty()) {
			FedPlan plan = queue.removeFirst();
			if(!seen.add(plan)) continue;
			ids.add(plan.getHopID());
			for(Pair<Long, FederatedOutput> edge : plan.getChildFedPlans()) {
				ids.add(edge.getLeft());
				FedPlan child = invocation.memo().getFedPlanAfterPrune(edge);
				if(child != null) queue.addLast(child);
			}
		}
		List<MemoState> memo = new ArrayList<>();
		for(long id : ids)
			for(FederatedOutput output : FederatedOutput.values()) {
				boolean present = invocation.memo().contains(id, output);
				FedPlanVariants variants = present
					? invocation.memo().getFedPlanVariants(Pair.of(id, output)) : null;
				memo.add(new MemoState(id, output, present, variants,
					variants == null ? List.of() : List.copyOf(variants.getFedPlanVariants())));
			}
		List<HopState> hops = analysis.occurrences().stream().map(HopOccurrenceProjection::hop).distinct()
			.map(HopState::new).toList();
		Map<Long, Map<Long, AnchorSpec>> refed = new LinkedHashMap<>();
		Map<Long, Map<Long, MaterializeSpec>> fout = new LinkedHashMap<>();
		Map<Long, Map<Long, Map<Long, LocalMaterializeSpec>>> local = new LinkedHashMap<>();
		for(long scope : ids) {
			refed.put(scope, FederatedRefedRegistry.snapshot(scope));
			fout.put(scope, FederatedFoutMaterializeRegistry.snapshot(scope));
			local.put(scope, FederatedLocalMaterializeRegistry.snapshotScopes(scope));
		}
		return new PlannerState(analysis, analysis.analysisFingerprint(), analysis.occurrences(), hops,
			List.copyOf(memo), invocation.counters(), refed, fout, local);
	}

	private static void assertStateSame(PlannerState expected, PlannerState actual) {
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertEquals(expected.analysisFingerprint(), actual.analysisFingerprint());
		assertIdentityList(expected.occurrences(), actual.occurrences(), "analysis occurrences");
		Assert.assertSame(expected.counters(), actual.counters());
		Assert.assertEquals(expected.hops().size(), actual.hops().size());
		for(int i = 0; i < expected.hops().size(); i++) expected.hops().get(i).assertSame(actual.hops().get(i));
		Assert.assertEquals(expected.memo().size(), actual.memo().size());
		for(int i = 0; i < expected.memo().size(); i++) expected.memo().get(i).assertSame(actual.memo().get(i));
		assertNestedRegistrySame(expected.refed(), actual.refed(), "refed");
		assertNestedRegistrySame(expected.fout(), actual.fout(), "fout");
		Assert.assertEquals(expected.local().keySet(), actual.local().keySet());
		for(long scope : expected.local().keySet())
			assertNestedRegistrySame(expected.local().get(scope), actual.local().get(scope), "local " + scope);
	}

	private static void assertIdentityList(List<?> expected, List<?> actual, String label) {
		Assert.assertEquals(label, expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++) Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	private static void assertRegistrySame(Map<?, ?> expected, Map<?, ?> actual, String label) {
		Assert.assertEquals(label, expected.keySet(), actual.keySet());
		for(Object key : expected.keySet()) Assert.assertSame(label + ' ' + key, expected.get(key), actual.get(key));
	}

	private static void assertNestedRegistrySame(Map<Long, ? extends Map<?, ?>> expected,
		Map<Long, ? extends Map<?, ?>> actual, String label) {
		Assert.assertEquals(label + " scopes", expected.keySet(), actual.keySet());
		for(long scope : expected.keySet())
			assertRegistrySame(expected.get(scope), actual.get(scope), label + ' ' + scope);
	}

	private static DpInvocationReceipt invoke(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
				new DMLTranslator(program).constructLops(program, receipt::set);
			}
			finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return (DpInvocationReceipt) receipt.get();
		}
		catch(Exception e) { throw new AssertionError("Unable to compile G014 candidate fixture " + id, e); }
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values) {
		try { ((List) values).add(null); Assert.fail("mutable candidate snapshots"); }
		catch(UnsupportedOperationException expected) { }
	}

	private record MemoState(long hopId, FederatedOutput output, boolean present, FedPlanVariants variants,
		List<FederatedPlannerDpMemoTable.FedPlan> variantOrder) {
		private void assertSame(MemoState actual) {
			Assert.assertEquals(hopId, actual.hopId);
			Assert.assertSame(output, actual.output);
			Assert.assertEquals(present, actual.present);
			Assert.assertSame(variants, actual.variants);
			assertIdentityList(variantOrder, actual.variantOrder, "memo variants");
		}
	}

	private record HopState(Hop hop, org.apache.sysds.common.Types.ExecType execType,
		FederatedOutput output, boolean recompile, List<Hop> inputs, List<Hop> parents) {
		private HopState(Hop hop) {
			this(hop, hop.getForcedExecType(), hop.getFederatedOutput(), hop.requiresRecompile(),
				List.copyOf(hop.getInput()), List.copyOf(hop.getParent()));
		}
		private void assertSame(HopState actual) {
			Assert.assertSame(hop, actual.hop);
			Assert.assertSame(execType, actual.execType);
			Assert.assertSame(output, actual.output);
			Assert.assertEquals(recompile, actual.recompile);
			assertIdentityList(inputs, actual.inputs, "Hop inputs");
			assertIdentityList(parents, actual.parents, "Hop parents");
		}
	}

	private record PlannerState(PlacementAnalysis analysis, String analysisFingerprint,
		List<HopOccurrenceProjection> occurrences, List<HopState> hops, List<MemoState> memo,
		InvocationCounters counters, Map<Long, Map<Long, AnchorSpec>> refed,
		Map<Long, Map<Long, MaterializeSpec>> fout,
		Map<Long, Map<Long, Map<Long, LocalMaterializeSpec>>> local) { }
}
