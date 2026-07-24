/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.CandidateNormalizationFixture;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationObserver;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireFunctionOutputEdge;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateMapEntry;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.FunctionOutputDependencyEntry;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.DpSemanticConstructionException;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.MapEntryState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NeutralEnumerationContext;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NormalizedCandidateInputs;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.OracleInputState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.TransientForwardDependencyEntry;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
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

	@Test
	public void scalarTransientForwardReceiptIsExactAndHostileVariantsReject() {
		DpInvocationReceipt invocation = invoke("B-21-SCALAR");
		PreSelectionSemanticBlock block = invocation.semanticConsumption().semanticBlock();
		NeutralEnumerationContext base = block.context();
		List<RewireTransientForwardEdge> scalarForwards = base.rewireSnapshot().transientForwardEdges().stream()
			.filter(edge -> base.analysis().hop(edge.writeOccurrence()).orElseThrow().getDataType() != DataType.MATRIX
				&& base.analysis().hop(edge.readOccurrence()).orElseThrow().getDataType() != DataType.MATRIX).toList();
		Assert.assertEquals("scalar-only fixture must publish both exact transient forwards", 2, scalarForwards.size());
		List<CandidateOccurrenceSnapshot> ownedSnapshots = new ArrayList<>();
		for(RewireTransientForwardEdge scalarForward : scalarForwards) {
			HopOccurrenceProjection scalarSource = base.rewireSnapshot().candidateOccurrences().stream()
				.filter(occurrence -> occurrence.key() == scalarForward.writeOccurrence()).findFirst().orElseThrow();
			HopOccurrenceProjection scalarParent = base.rewireSnapshot().candidateOccurrences().stream()
				.filter(occurrence -> occurrence.key() == scalarForward.readOccurrence()).findFirst().orElseThrow();
			Assert.assertEquals(DataType.SCALAR, scalarSource.hop().getDataType());
			Assert.assertEquals(DataType.SCALAR, scalarParent.hop().getDataType());
			List<CandidateOccurrenceSnapshot> scalarOwned = block.candidateSnapshots().stream()
				.filter(snapshot -> snapshot.transientForwardDependencies().stream()
					.anyMatch(dependency -> dependency.forwardEdge() == scalarForward)).toList();
			Assert.assertEquals("production enumeration must publish one exact scalar receipt", 1,
				scalarOwned.size());
			CandidateOccurrenceSnapshot scalarSnapshot = scalarOwned.get(0);
			Assert.assertSame(scalarParent.key(), scalarSnapshot.parentOccurrence());
			Assert.assertTrue(scalarSnapshot.rawEntries().isEmpty());
			Assert.assertTrue(scalarSnapshot.promotedEntries().isEmpty());
			Assert.assertTrue(scalarSnapshot.logicalEntries().isEmpty());
			Assert.assertTrue(scalarSnapshot.orderedOracleInputs().isEmpty());
			Assert.assertEquals(1, scalarSnapshot.transientForwardDependencies().size());
			TransientForwardDependencyEntry scalarEntry = scalarSnapshot.transientForwardDependencies().get(0);
			Assert.assertSame(scalarForward, scalarEntry.forwardEdge());
			Assert.assertSame(scalarSource.key(), scalarEntry.sourceOccurrence());
			Assert.assertEquals(0, scalarEntry.collectedPosition());
			PlacementState scalarSelected = scalarEntry.selectedSourceState();
			Assert.assertSame(occurrenceState(scalarSource, base), scalarSelected);
			Assert.assertEquals(ExecType.CP, scalarSelected.execType());
			Assert.assertEquals(FederatedOutput.LOUT, scalarSelected.output());
			Assert.assertNull(scalarSelected.fType());
			ownedSnapshots.add(scalarSnapshot);
		}
		RewireTransientForwardEdge forward = scalarForwards.get(0);
		HopOccurrenceProjection source = base.rewireSnapshot().candidateOccurrences().stream()
			.filter(occurrence -> occurrence.key() == forward.writeOccurrence()).findFirst().orElseThrow();
		HopOccurrenceProjection parent = base.rewireSnapshot().candidateOccurrences().stream()
			.filter(occurrence -> occurrence.key() == forward.readOccurrence()).findFirst().orElseThrow();
		CandidateOccurrenceSnapshot snapshot = ownedSnapshots.get(0);
		TransientForwardDependencyEntry entry = snapshot.transientForwardDependencies().get(0);
		PlacementState selected = entry.selectedSourceState();
		List<FType> localType = new ArrayList<>();
		localType.add(null);
		List<Pair<Long, FederatedOutput>> localEdge = List.of(
			Pair.of(source.hop().getHopID(), FederatedOutput.LOUT));
		NormalizedCandidateInputs normalized = org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter
			.normalizeCandidateInputs(base, parent, localEdge, List.of(source.hop()), localType, Map.of(), invocation.memo());
		Assert.assertSame(forward, normalized.snapshot().transientForwardDependencies().get(0).forwardEdge());
		Assert.assertEquals(List.of(source.hop()), normalized.exactCollectedHops());
		Assert.assertEquals(1, normalized.effectiveCollectedFTypes().size());
		Assert.assertNull(normalized.effectiveCollectedFTypes().get(0));
		Assert.assertTrue(normalized.effectiveNonNullFTypeMap().isEmpty());
		assertImmutable(snapshot.transientForwardDependencies());

		assertIllegal(() -> new CandidateOccurrenceSnapshot(base, parent.key(), List.of(), List.of(), List.of(),
			List.of(entry, entry), List.of(), ConstructionDisposition.AVAILABLE, "AVAILABLE"));
		RewireTransientForwardEdge stale = new RewireTransientForwardEdge(source.key(), parent.key());
		assertIllegal(() -> new CandidateOccurrenceSnapshot(base, parent.key(), List.of(), List.of(), List.of(),
			List.of(new TransientForwardDependencyEntry(stale, source.key(), 0, selected)), List.of(),
			ConstructionDisposition.AVAILABLE, "AVAILABLE"));
		RewireTransientForwardEdge reversed = new RewireTransientForwardEdge(parent.key(), source.key());
		NeutralEnumerationContext reversedContext = withForward(base, reversed);
		assertIllegal(() -> new CandidateOccurrenceSnapshot(reversedContext, parent.key(), List.of(), List.of(), List.of(),
			List.of(new TransientForwardDependencyEntry(reversed, parent.key(), 0,
				occurrenceState(parent, base))), List.of(), ConstructionDisposition.AVAILABLE, "AVAILABLE"));
		PlacementState federated = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		assertIllegal(() -> new CandidateOccurrenceSnapshot(base, parent.key(), List.of(), List.of(), List.of(),
			List.of(new TransientForwardDependencyEntry(forward, source.key(), 0, federated)), List.of(),
			ConstructionDisposition.AVAILABLE, "AVAILABLE"));
		List<FType> federatedType = new ArrayList<>();
		federatedType.add(FType.ROW);
		assertSemanticFailure("TRANSIENT_FORWARD_DEPENDENCY_AUTHORITY_DIFFERS", () ->
			org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.normalizeCandidateInputs(
				base, parent, localEdge, List.of(source.hop()), federatedType, Map.of(), invocation.memo()));
		assertSemanticFailure("TRANSIENT_FORWARD_DEPENDENCY_AUTHORITY_DIFFERS", () ->
			org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.normalizeCandidateInputs(
				base, parent, List.of(Pair.of(source.hop().getHopID(), FederatedOutput.FOUT)),
				List.of(source.hop()), localType, Map.of(), invocation.memo()));
		assertSemanticFailure("TRANSIENT_FORWARD_DEPENDENCY_DUPLICATED", () ->
			org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.normalizeCandidateInputs(
				base, parent, List.of(localEdge.get(0), localEdge.get(0)),
				List.of(source.hop(), source.hop()), java.util.Arrays.asList(null, null), Map.of(), invocation.memo()));
		assertSemanticFailure("COLLECTED_DEPENDENCY_UNOWNED", () ->
			org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.normalizeCandidateInputs(
				base, parent, List.of(Pair.of(parent.hop().getHopID(), FederatedOutput.LOUT)),
				List.of(parent.hop()), localType, Map.of(), invocation.memo()));

		HopOccurrenceProjection matrixSource = base.rewireSnapshot().candidateOccurrences().stream()
			.filter(occurrence -> occurrence.hop().getDataType() == DataType.MATRIX)
			.filter(occurrence -> occurrenceState(occurrence, base) != null).findFirst().orElseThrow();
		HopOccurrenceProjection matrixParent = base.rewireSnapshot().candidateOccurrences().stream()
			.filter(occurrence -> occurrence != matrixSource && occurrence.hop().getDataType() == DataType.MATRIX)
			.findFirst().orElseThrow();
		RewireTransientForwardEdge matrix = new RewireTransientForwardEdge(matrixSource.key(), matrixParent.key());
		NeutralEnumerationContext matrixContext = withForward(base, matrix);
		assertIllegal(() -> new CandidateOccurrenceSnapshot(matrixContext, matrixParent.key(), List.of(), List.of(),
			List.of(), List.of(new TransientForwardDependencyEntry(matrix, matrixSource.key(), 0,
				occurrenceState(matrixSource, base))), List.of(), ConstructionDisposition.AVAILABLE, "AVAILABLE"));

		DpInvocationReceipt foreign = invoke("B-21-SCALAR");
		HopOccurrenceProjection foreignSource = foreign.analysis().occurrences().get(source.normalizedOrdinal());
		assertSemanticFailure("UNMAPPABLE_OCCURRENCE", () ->
			org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.normalizeCandidateInputs(
				base, parent, List.of(Pair.of(foreignSource.hop().getHopID(), FederatedOutput.LOUT)),
				List.of(foreignSource.hop()), localType, Map.of(), invocation.memo()));
	}

	@Test
	public void functionOutputDependenciesAreExactReverseDependenciesAndHostileVariantsReject() {
		DpInvocationReceipt invocation = invoke("PCA-MULTIRETURN");
		PreSelectionSemanticBlock block = invocation.semanticConsumption().semanticBlock();
		NeutralEnumerationContext base = block.context();
		FunctionOutputFixture fixture = functionOutputFixture(base, invocation.memo());
		NeutralEnumerationContext context = base.rewireSnapshot().functionOutputEdges().stream()
			.anyMatch(edge -> edge == fixture.edge()) ? base : withFunctionOutputs(base, List.of(fixture.edge()));
		CollectedPlanSelection selection = collectedSelection(fixture.parent(), fixture.output(),
			fixture.selectedOutputState(), invocation.memo());
		NormalizedCandidateInputs normalized = org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter
			.normalizeCandidateInputs(context, fixture.parent(), selection.edges(), selection.hops(), selection.types(),
				selection.fedInputTypeMap(), invocation.memo());
		CandidateOccurrenceSnapshot snapshot = normalized.snapshot();
		Assert.assertEquals(1, snapshot.functionOutputDependencies().size());
		FunctionOutputDependencyEntry dependency = snapshot.functionOutputDependencies().get(0);
		Assert.assertSame(fixture.edge(), dependency.functionOutputEdge());
		Assert.assertSame(snapshot.parentOccurrence(), fixture.edge().functionOccurrence());
		Assert.assertSame(fixture.edge().outputOccurrence(), dependency.outputOccurrence());
		Assert.assertTrue("function-output deps stay out of candidate oracle inputs", snapshot.rawEntries().stream()
			.noneMatch(entry -> entry.occurrence() == dependency.outputOccurrence()));
		Assert.assertTrue("function-output deps stay out of promoted oracle inputs", snapshot.promotedEntries().stream()
			.noneMatch(entry -> entry.occurrence() == dependency.outputOccurrence()));
		Assert.assertEquals(snapshot.promotedEntries().size() + snapshot.logicalEntries().size(),
			snapshot.orderedOracleInputs().size());
		Assert.assertTrue("function-output dependency is retained for child indexing",
			normalized.exactCollectedHops().stream().anyMatch(hop -> hop == fixture.output().hop()));
		FType expectedOutputType = dependency.selectedOutputState().output() == FederatedOutput.FOUT
			? dependency.selectedOutputState().fType() : null;
		int outputPosition = normalized.exactCollectedHops().indexOf(fixture.output().hop());
		Assert.assertSame(expectedOutputType, normalized.effectiveCollectedFTypes().get(outputPosition));
		Assert.assertFalse("function-output dependencies are not parent oracle FType inputs",
			normalized.effectiveNonNullFTypeMap().containsKey(fixture.output().hop().getHopID()));

		assertIllegal(() -> new CandidateOccurrenceSnapshot(context, snapshot.parentOccurrence(), snapshot.rawEntries(),
			snapshot.promotedEntries(), snapshot.logicalEntries(), snapshot.transientForwardDependencies(),
			List.of(dependency, dependency), snapshot.orderedOracleInputs(),
			ConstructionDisposition.AVAILABLE, "AVAILABLE"));
		Assert.assertEquals("Function-output receipts must not be publicly forgeable", 0,
			Arrays.stream(RewireFunctionOutputEdge.class.getConstructors())
				.filter(constructor -> Modifier.isPublic(constructor.getModifiers())).count());
		Assert.assertTrue("Function-output receipt minting must stay private to the rewire table",
			Arrays.stream(RewireFunctionOutputEdge.class.getDeclaredConstructors())
				.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
		assertIllegal(() -> withFunctionOutputs(base, List.of(fixture.edge(), fixture.edge())));
		NeutralEnumerationContext withoutFunctionOutputEdges = withFunctionOutputs(base, List.of());
		assertIllegal(() -> org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter
			.normalizeCandidateInputs(withoutFunctionOutputEdges, fixture.parent(), selection.edges(), selection.hops(),
				selection.types(), selection.fedInputTypeMap(), invocation.memo()));
	}

	private static PlacementState occurrenceState(HopOccurrenceProjection occurrence,
		NeutralEnumerationContext context) {
		return context.analysis().graph().node(occurrence.key()).orElseThrow().legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
				&& state.fType() == null).findFirst().orElse(null);
	}

	private static FunctionOutputFixture functionOutputFixture(NeutralEnumerationContext context,
		FederatedPlannerDpMemoTable memo) {
		boolean sawProjectableStructuralOutput = false;
		for(HopOccurrenceProjection parent : context.rewireSnapshot().candidateOccurrences()) {
			if(!(parent.hop() instanceof FunctionOp))
				continue;
			List<Hop> outputs = ((FunctionOp) parent.hop()).getOutputs();
			for(int position = 0; outputs != null && position < outputs.size(); position++)
				if(outputs.get(position) != null && context.rewireSnapshot().projectExactCarrier(outputs.get(position)) != null)
					sawProjectableStructuralOutput = true;
		}
		Assert.assertTrue("FunctionOp.getOutputs() carriers must be exact projectable occurrences",
			sawProjectableStructuralOutput);
		for(RewireFunctionOutputEdge edge : context.rewireSnapshot().functionOutputEdges()) {
			HopOccurrenceProjection parent = context.rewireSnapshot().candidateOccurrences().stream()
				.filter(occurrence -> occurrence.key() == edge.functionOccurrence()).findFirst().orElseThrow();
			HopOccurrenceProjection output = context.rewireSnapshot().candidateOccurrences().stream()
				.filter(occurrence -> occurrence.key() == edge.outputOccurrence()).findFirst().orElseThrow();
			PlacementState selected = selectedPlanState(output, memo);
			if(selected != null)
				return new FunctionOutputFixture(parent, output, edge, selected);
		}
		throw new AssertionError("fixture must publish an exact, planned rewire-owned FunctionOp output edge");
	}

	private static PlacementState selectedPlanState(HopOccurrenceProjection occurrence, FederatedPlannerDpMemoTable memo) {
		FedPlan local = memo.getFedPlanAfterPrune(occurrence.hop().getHopID(), FederatedOutput.LOUT);
		if(local != null && local.getSelectedPlacementState() != null)
			return local.getSelectedPlacementState();
		FedPlan federated = memo.getFedPlanAfterPrune(occurrence.hop().getHopID(), FederatedOutput.FOUT);
		return federated == null ? null : federated.getSelectedPlacementState();
	}

	private static CollectedPlanSelection collectedSelection(HopOccurrenceProjection parent,
		HopOccurrenceProjection functionOutput, PlacementState outputState, FederatedPlannerDpMemoTable memo) {
		List<Pair<Long, FederatedOutput>> edges = new ArrayList<>();
		List<Hop> hops = new ArrayList<>();
		List<FType> types = new ArrayList<>();
		Map<Long, FType> fedInputTypeMap = new LinkedHashMap<>();
		for(Hop input : parent.hop().getInput()) {
			FederatedOutput output = memo.contains(input.getHopID(), FederatedOutput.LOUT)
				? FederatedOutput.LOUT : FederatedOutput.FOUT;
			FedPlan plan = memo.getFedPlanAfterPrune(input.getHopID(), output);
			Assert.assertNotNull("direct function input must have a selected child plan", plan);
			edges.add(Pair.of(input.getHopID(), output));
			hops.add(input);
			FType type = output == FederatedOutput.FOUT ? plan.getFType() : null;
			types.add(type);
			if(output == FederatedOutput.FOUT)
				fedInputTypeMap.put(input.getHopID(), type);
		}
		edges.add(Pair.of(functionOutput.hop().getHopID(), outputState.output()));
		hops.add(functionOutput.hop());
		types.add(outputState.output() == FederatedOutput.FOUT ? outputState.fType() : null);
		return new CollectedPlanSelection(List.copyOf(edges), List.copyOf(hops),
			java.util.Collections.unmodifiableList(new ArrayList<>(types)),
			java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fedInputTypeMap)));
	}

	private static NeutralEnumerationContext withFunctionOutputs(NeutralEnumerationContext base,
		List<RewireFunctionOutputEdge> functionOutputEdges) {
		RewireOccurrenceSnapshot snapshot = base.rewireSnapshot();
		RewireOccurrenceSnapshot expanded = new RewireOccurrenceSnapshot(snapshot.analysis(), snapshot.program(),
			snapshot.analysisFingerprint(), snapshot.occurrences(), snapshot.candidateOccurrences(),
			snapshot.cloneReceipts(), snapshot.additionalRoots(), snapshot.consumerEdges(), functionOutputEdges,
			snapshot.transientForwardEdges(), snapshot.cloneToOriginal(), snapshot.occurrenceByCarrier(),
			snapshot.activeScopeIds(), snapshot.enumerationScopeKey());
		return new NeutralEnumerationContext(base.analysis(), expanded, base.analysisFingerprint(), base.numWorkers(),
			base.invocationEvidence(), base.privacy());
	}

	private static NeutralEnumerationContext withForward(NeutralEnumerationContext base,
		RewireTransientForwardEdge forward) {
		RewireOccurrenceSnapshot snapshot = base.rewireSnapshot();
		List<RewireTransientForwardEdge> forwards = new ArrayList<>(snapshot.transientForwardEdges());
		forwards.add(forward);
		RewireOccurrenceSnapshot expanded = new RewireOccurrenceSnapshot(snapshot.analysis(), snapshot.program(),
			snapshot.analysisFingerprint(), snapshot.occurrences(), snapshot.candidateOccurrences(),
			snapshot.cloneReceipts(), snapshot.additionalRoots(), snapshot.consumerEdges(), forwards,
			snapshot.cloneToOriginal(), snapshot.occurrenceByCarrier(), snapshot.activeScopeIds(),
			snapshot.enumerationScopeKey());
		return new NeutralEnumerationContext(base.analysis(), expanded, base.analysisFingerprint(), base.numWorkers(),
			base.invocationEvidence(), base.privacy());
	}

	private static void assertIllegal(Runnable action) {
		try { action.run(); Assert.fail("hostile transient-forward authority was accepted"); }
		catch(IllegalArgumentException | DpSemanticConstructionException expected) { }
	}

	private static void assertSemanticFailure(String reason, Runnable action) {
		try { action.run(); Assert.fail("hostile transient-forward request was accepted: " + reason); }
		catch(DpSemanticConstructionException expected) { Assert.assertEquals(reason, expected.reasonCode()); }
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
		assertTrackingMemoEmpty(memo, analysis);
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		assertRegistriesEmpty(fixture.program());
		CandidateNormalizationFixture negative = new CandidateNormalizationFixture(parent, planChilds,
			collectedHops, collectedFTypes, fedInputTypeMap);
		Assert.assertSame(parent, negative.parentOccurrence());
		Assert.assertEquals(planChilds, negative.planChilds());
		assertIdentityList(collectedHops, negative.collectedHops(), "negative collected Hops");
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
		}
		Assert.assertTrue("negative canonical invocation mutated memo: " + memo.writeEvents(),
			memo.writeEvents().isEmpty());
		assertTrackingMemoEmpty(memo, analysis);
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

	private static void assertIdentityList(List<?> expected, List<?> actual, String label) {
		Assert.assertEquals(label, expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++) Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	private static DpInvocationReceipt invoke(String id) {
		return fixture(id).receipt();
	}

	private static Fixture fixture(String id) {
		try {
			DMLProgram program = "B-11".equals(id) || "B-21".equals(id)
				? CampaignBG014HermeticPlannerFixtureFactory.compile(id)
				: "B-21-SCALAR".equals(id) ? compileScalarTransientFixture()
				: "PCA-MULTIRETURN".equals(id) ? compilePcaMultiReturnFixture()
				: ProductionShadowFixtureFactory.compile(id);
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
				new DMLTranslator(program).constructLops(program, receipt::set);
			}
			finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return new Fixture(program, (DpInvocationReceipt) receipt.get());
		}
		catch(Exception e) { throw new AssertionError("Unable to compile G014 candidate fixture " + id, e); }
	}

	private static DMLProgram compilePcaMultiReturnFixture() throws Exception {
		Path data = Files.createTempFile("g014-pca-multireturn-", ".data");
		Path metadata = Path.of(data.toString() + ".mtd");
		Files.writeString(data, "");
		Files.writeString(metadata, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"text\",\"rows\":1000,\"cols\":100,\"nnz\":0,"
			+ "\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		metadata.toFile().deleteOnExit();
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		String script = "X_LOCAL=read(\"" + path + "\");\n"
			+ "X=federated(local_matrix=X_LOCAL,addresses=list(\"localhost:1234\",\"localhost:1235\"),"
			+ "ranges=list(list(0,0),list(500,100),list(500,0),list(1000,100)));\n"
			+ "[PC,V]=pca(X=X,K=4,scale=TRUE,center=TRUE);\nwrite(PC,\"/tmp/g014-pca-out\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static DMLProgram compileScalarTransientFixture() throws Exception {
		Path data = Files.createTempFile("g014-b21-scalar-", ".data");
		Path metadata = Path.of(data.toString() + ".mtd");
		Files.writeString(data, "");
		Files.writeString(metadata, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"text\",\"rows\":4,\"cols\":2,\"nnz\":0,"
			+ "\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		metadata.toFile().deleteOnExit();
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		String script = "A_LOCAL=read(\"" + path + "\");\n"
			+ "A=federated(local_matrix=A_LOCAL,addresses=list(\"localhost:1234\",\"localhost:1235\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "Z=sum(A);\nif(Z>=0){print(Z);}\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static ProgramState snapshotProgram(DMLProgram program, PlacementAnalysis analysis) {
		return new ProgramState(program, analysis, PlacementGraphFingerprint.capture(program),
			analysis.analysisFingerprint(), analysis.occurrences(),
			analysis.occurrences().stream().map(HopOccurrenceProjection::hop).distinct().map(HopState::new).toList());
	}

	private static void assertProgramSame(ProgramState expected, ProgramState actual) {
		Assert.assertSame(expected.program(), actual.program());
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertEquals(expected.programFingerprint(), actual.programFingerprint());
		Assert.assertEquals(expected.analysisFingerprint(), actual.analysisFingerprint());
		assertIdentityList(expected.occurrences(), actual.occurrences(), "negative analysis occurrences");
		Assert.assertEquals(expected.hops().size(), actual.hops().size());
		for(int i = 0; i < expected.hops().size(); i++) expected.hops().get(i).assertSame(actual.hops().get(i));
	}

	private static void assertRegistriesEmpty(DMLProgram program) {
		Assert.assertTrue("refed registry is not globally empty", FederatedRefedRegistry.isEmpty());
		Assert.assertTrue("FOUT registry is not globally empty", FederatedFoutMaterializeRegistry.isEmpty());
		Assert.assertTrue("local registry is not globally empty", FederatedLocalMaterializeRegistry.isEmpty());
		Set<Long> exactScopes = new LinkedHashSet<>();
		exactScopes.add(-1L);
		program.getStatementBlocks().forEach(block -> exactScopes.add(block.getSBID()));
		Assert.assertEquals("B-15 exact default plus top-level statement-block scope universe",
			program.getStatementBlocks().size() + 1, exactScopes.size());
		for(long scope : exactScopes) {
			Assert.assertTrue("refed scope " + scope, FederatedRefedRegistry.snapshot(scope).isEmpty());
			Assert.assertTrue("FOUT scope " + scope, FederatedFoutMaterializeRegistry.snapshot(scope).isEmpty());
			Assert.assertTrue("local scope " + scope,
				FederatedLocalMaterializeRegistry.snapshotScopes(scope).isEmpty());
		}
	}

	private static void assertTrackingMemoEmpty(TrackingMemo memo, PlacementAnalysis analysis) {
		Assert.assertSame(analysis, memo.analysis());
		Assert.assertTrue("negative memo acquired additional roots", memo.getAdditionalRootHopIDs().isEmpty());
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			for(FederatedOutput output : FederatedOutput.values())
				Assert.assertFalse("negative memo acquired coordinate " + occurrence.key() + '/' + output,
					memo.contains(occurrence.hop().getHopID(), output));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values) {
		try { ((List) values).add(null); Assert.fail("mutable candidate snapshots"); }
		catch(UnsupportedOperationException expected) { }
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

	private record Fixture(DMLProgram program, DpInvocationReceipt receipt) { }
	private record ProgramState(DMLProgram program, PlacementAnalysis analysis, String programFingerprint,
		String analysisFingerprint, List<HopOccurrenceProjection> occurrences, List<HopState> hops) { }
	private record ExpectedRawInput(HopOccurrenceProjection child, FederatedOutput output) { }
	private record ExpectedRawCandidate(HopOccurrenceProjection parent, List<ExpectedRawInput> inputs) { }
	private record CollectedPlanSelection(List<Pair<Long, FederatedOutput>> edges, List<Hop> hops,
		List<FType> types, Map<Long, FType> fedInputTypeMap) { }
	private record FunctionOutputFixture(HopOccurrenceProjection parent, HopOccurrenceProjection output,
		RewireFunctionOutputEdge edge, PlacementState selectedOutputState) { }

	private static final class TrackingMemo extends FederatedPlannerDpMemoTable {
		private final List<String> writes = new ArrayList<>();

		private TrackingMemo(PlacementAnalysis analysis) {
			super(analysis);
		}

		@Override
		public void addFedPlanVariants(HopOccurrenceProjection occurrence, FederatedOutput output,
			FedPlanVariants variants) {
			writes.add("addFedPlanVariants.occurrence");
			super.addFedPlanVariants(occurrence, output, variants);
		}

		@Override
		public void addFedPlanVariants(long hopId, FederatedOutput output, FedPlanVariants variants) {
			writes.add("addFedPlanVariants.id");
			super.addFedPlanVariants(hopId, output, variants);
		}

		@Override
		public void registerHopRefs(Map<Long, HopCommon> refs) {
			writes.add("registerHopRefs");
			super.registerHopRefs(refs);
		}

		@Override
		public void registerCloneMapping(Map<Long, Long> clones) {
			writes.add("registerCloneMapping");
			super.registerCloneMapping(clones);
		}

		@Override
		public void registerAdditionalRootHopIDs(List<Hop> roots) {
			writes.add("registerAdditionalRootHopIDs");
			super.registerAdditionalRootHopIDs(roots);
		}

		@Override
		public void registerDeadFunctionOutputHopIDs(Set<Long> ids) {
			writes.add("registerDeadFunctionOutputHopIDs");
			super.registerDeadFunctionOutputHopIDs(ids);
		}

		@Override
		public void setNumWorkers(int workers) {
			writes.add("setNumWorkers");
			super.setNumWorkers(workers);
		}

		private List<String> writeEvents() {
			return List.copyOf(writes);
		}
	}

	private static final class TrackingObserver implements DpEnumerationObserver {
		private DpSemanticConstructionException failure;
		private int results;
		private int oracle;
		private int cost;
		private int placement;
		private int candidates;
		private int repair;
		private int fallback;

		@Override public void constructionFailed(DpSemanticConstructionException value) { failure = value; }
		@Override public void resultPublished(DpEnumerationResult value) { results++; }
		@Override public void oracleEvaluated() { oracle++; }
		@Override public void costEvaluated() { cost++; }
		@Override public void placementDecided() { placement++; }
		@Override public void candidateConstructed() { candidates++; }
		@Override public void repairAttempted() { repair++; }
		@Override public void fallbackAttempted() { fallback++; }

		private DpSemanticConstructionException failure() { return failure; }
		private int resultCount() { return results; }
		private int oracleCount() { return oracle; }
		private int costCount() { return cost; }
		private int placementCount() { return placement; }
		private int candidateCount() { return candidates; }
		private int repairCount() { return repair; }
		private int fallbackCount() { return fallback; }
	}
}
