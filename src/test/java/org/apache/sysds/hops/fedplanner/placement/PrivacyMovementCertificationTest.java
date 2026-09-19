/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/** Independent final-boundary certification for origin-bound payload movement. */
public class PrivacyMovementCertificationTest {
	@After
	public void clearEmissionState() {
		PlacementEmissionTransaction.resetForTesting();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	@Test
	public void localProjectionRejectsProtectedFoutForBothCpOutputModes() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(localMaterializationScript()));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis publicAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult baseline = new FedAllPlacementAdapter().select(publicAnalysis);
		NeutralPlacementGraph.Node source = federatedSource(publicAnalysis, "X");
		PlacementAnalysis.CompiledInputEdgeFact payloadEdge = publicAnalysis
			.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == source.key())
			.filter(edge -> !publicAnalysis.isCoordinatorMetadataOnlyInput(edge))
			.findFirst().orElseThrow();
		PlacementAnalysis protectedAnalysis = withPrivacy(publicAnalysis,
			Map.of(source.key(), Privacy.PRIVATE_AGGREGATE), program);

		for(FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
			Map<CompiledHopKey,PlacementState> selected = new LinkedHashMap<>(baseline.selectedStates());
			selected.put(source.key(), source.legalAlternatives().stream()
				.filter(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT && state.fType() != null)
				.findFirst().orElseThrow());
			NeutralPlacementGraph.Node consumer = publicAnalysis.graph().node(payloadEdge.consumer()).orElseThrow();
			selected.put(consumer.key(), consumer.legalAlternatives().stream()
				.filter(state -> state.execType() == ExecType.CP && state.output() == output)
				.findFirst().orElseThrow());
			var candidates = CandidateSelections.selectNativeCanonical(publicAnalysis,
				publicAnalysis.graph().relocationActions(), selected);
			Map<CompiledHopKey,PlacementEmissionState> emissions = new LinkedHashMap<>();
			selected.forEach((key, state) -> emissions.put(key, new PlacementEmissionState(state, false)));

			Assert.assertThrows("CP/" + output + " must not derive LOCAL for protected payload",
				IllegalStateException.class, () -> LocalMaterializationSelections.derive(
					protectedAnalysis, selected, emissions, candidates.candidates()));
		}
	}

	@Test
	public void activeProtectedRelocationIsUnavailableButDirectSamePoolReceiptRemains() throws Exception {
		FixtureProgram crossPoolProgram = FixtureProgram.adopt(compile(crossPoolScript(false)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(crossPoolProgram, Privacy.PUBLIC);
		PlacementAnalysis crossPublic = new NeutralPlacementGraphBuilder().buildAnalysis(crossPoolProgram);
		NormalizedPlannerResult crossPlan = new FedAllPlacementAdapter().select(crossPublic);
		Assert.assertTrue("fixture must select an active cross-pool relocation",
			!crossPlan.selectedRelocations().isEmpty());
		NeutralPlacementGraph.Node leftSource = federatedSource(crossPublic, "A");
		NeutralPlacementGraph.Node rightSource = federatedSource(crossPublic, "B");
		PlacementAnalysis crossProtected = withPrivacy(crossPublic,
			Map.of(leftSource.key(), Privacy.PRIVATE_AGGREGATE,
				rightSource.key(), Privacy.PRIVATE_AGGREGATE), crossPoolProgram);
		List<NeutralPlacementGraph.RelocationAction> selectedActionUniverse =
			crossProtected.graph().relocationActions();
		CandidateSelections.PartialReachabilityIndex publicReachability =
			CandidateSelections.partialReachabilityIndex(
				crossPublic, crossPublic.graph(), selectedActionUniverse);
		Assert.assertTrue("fixture assignment must be reachable before privacy filtering",
			publicReachability.canStillBeReachable(crossPlan.selectedStates()));
		CandidateSelections.PartialReachabilityIndex crossReachability =
			CandidateSelections.partialReachabilityIndex(
				crossProtected, crossProtected.graph(), selectedActionUniverse);
		Assert.assertFalse("candidate reachability must reject an unsafe-only active relocation",
			crossReachability.canStillBeReachable(crossPlan.selectedStates()));
		Assert.assertThrows("privacy-filtered candidate receipts are outside the protected domain",
			IllegalArgumentException.class,
			() -> RelocationSelections.selectMinimumCost(crossProtected,
				selectedActionUniverse, crossPlan.selectedStates(),
				crossPlan.selectedCandidateSelections(), ignored -> 1.0));
		Assert.assertThrows(IllegalArgumentException.class, () -> RelocationSelections.resolveAndValidate(
			crossProtected, crossPlan.selectedStates(), crossPlan.selectedCandidateSelections(),
			crossPlan.selectedRelocationChoices()));

		FixtureProgram samePoolProgram = FixtureProgram.adopt(compile(crossPoolScript(true)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(samePoolProgram, Privacy.PUBLIC);
		PlacementAnalysis samePublic = new NeutralPlacementGraphBuilder().buildAnalysis(samePoolProgram);
		NormalizedPlannerResult samePlan = new FedAllPlacementAdapter().select(samePublic);
		List<RelocationSelections.ResolvedChoice> direct = RelocationSelections.resolveAndValidate(
			samePublic, samePlan.selectedStates(), samePlan.selectedCandidateSelections(),
			samePlan.selectedRelocationChoices());
		Assert.assertTrue("fixture must retain a non-emitting same-pool receipt",
			direct.stream().anyMatch(choice -> !choice.requiresEmission()));
		NeutralPlacementGraph.Node directSource = relocationSource(samePublic,
			direct.stream().filter(choice -> !choice.requiresEmission()).findFirst().orElseThrow()
				.action().key());
		PlacementAnalysis sameProtected = withPrivacy(samePublic,
			Map.of(directSource.key(), Privacy.PRIVATE_AGGREGATE), samePoolProgram);
		NormalizedPlannerResult sameProtectedPlan = new FedAllPlacementAdapter().select(sameProtected);
		CandidateSelections.PartialReachabilityIndex sameReachability =
			CandidateSelections.partialReachabilityIndex(sameProtected, sameProtected.graph(),
				sameProtected.graph().relocationActions());
		Assert.assertTrue("safe same-pool receipt must remain candidate-reachable",
			sameReachability.canStillBeReachable(sameProtectedPlan.selectedStates()));
		Assert.assertTrue(RelocationSelections.resolveAndValidate(sameProtected,
			sameProtectedPlan.selectedStates(), sameProtectedPlan.selectedCandidateSelections(),
			sameProtectedPlan.selectedRelocationChoices()).stream()
			.anyMatch(choice -> !choice.requiresEmission()));
		RelocationSelections.CandidateProblemIndex directIndex = RelocationSelections.candidateProblemIndex(
			sameProtected, sameProtected.graph(), sameProtected.graph().relocationActions(),
			sameProtectedPlan.selectedStates(), sameProtectedPlan.selectedCandidateSelections(),
			sameProtected.relocationOrder());
		RelocationSelections.ExactEmissionScorer directScorer = directIndex.newExactEmissionScorer();
		sameProtectedPlan.selectedCandidateSelections().forEach(directScorer::selectReceipt);
		Assert.assertNotEquals("one unsafe option must not hide a legal direct option",
			Integer.MAX_VALUE, directScorer.minimumPhysicalEmissionCount());
	}

	@Test
	public void relocationPrivacyUsesEveryCompiledOwnerOfASharedValueVersion() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(crossPoolScript(false)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis original = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NeutralPlacementGraph.RelocationAction action = original.graph().relocationActions().get(0);
		var sourceValue = action.key().sourceValueVersion();
		List<NeutralPlacementGraph.Node> originalOwners = original.graph().nodes().stream()
			.filter(node -> original.isCompiledHopOccurrence(node.key()))
			.filter(node -> node.valueVersion().equals(sourceValue)).toList();
		Assert.assertFalse(originalOwners.isEmpty());
		NeutralPlacementGraph.Node additionalOwner = original.graph().nodes().stream()
			.filter(node -> original.isCompiledHopOccurrence(node.key()))
			.filter(node -> !node.valueVersion().equals(sourceValue))
			.findFirst().orElseThrow();
		List<NeutralPlacementGraph.Node> projectedNodes = original.graph().nodes().stream()
			.map(node -> node.key() == additionalOwner.key()
				? new NeutralPlacementGraph.Node(node.key(), node.kind(), sourceValue,
					node.emittedWork(), node.legalAlternatives(), node.exclusions(), node.anchors())
				: node).toList();
		NeutralPlacementGraph projectedGraph = new NeutralPlacementGraph(projectedNodes,
			original.graph().constraints(), List.of(action), List.of());
		Assert.assertTrue("fixture must expose multiple compiled owners for one action source value",
			projectedGraph.nodes().stream()
				.filter(node -> original.isCompiledHopOccurrence(node.key()))
				.filter(node -> node.valueVersion().equals(sourceValue)).count() > 1);

		PlacementAnalysis publicProjection = withGraphPrivacy(original, projectedGraph, Map.of(), program);
		PlacementAnalysis protectedProjection = withGraphPrivacy(original, projectedGraph,
			Map.of(additionalOwner.key(), Privacy.PRIVATE_AGGREGATE), program);
		RelocationSelections.RelocationPrivacyIndex publicIndex = RelocationSelections.relocationPrivacyIndex(
			publicProjection, projectedGraph, List.of(action));
		RelocationSelections.RelocationPrivacyIndex protectedIndex = RelocationSelections.relocationPrivacyIndex(
			protectedProjection, projectedGraph, List.of(action));
		Assert.assertFalse(publicIndex.requiresOriginResidency(action));
		Assert.assertTrue("one protected possible owner must bind the shared source value",
			protectedIndex.requiresOriginResidency(action));
		Assert.assertTrue("a non-emitting direct receipt remains safe",
			protectedIndex.isPrivacySafe(action, false));
		Assert.assertFalse("an active receipt from the shared protected value is unsafe",
			protectedIndex.isPrivacySafe(action, true));
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void indexedRelocationScoreMatchesCanonicalSelectedRealizations() throws Exception {
		for(boolean samePool : List.of(false, true)) {
			FixtureProgram program = FixtureProgram.adopt(compile(crossPoolScript(samePool)));
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
			List<NeutralPlacementGraph.RelocationAction> actions = analysis.graph().relocationActions();
			RelocationSelections.CanonicalOrderIndex order = analysis.relocationOrderFor(actions);
			RelocationSelections.Selection canonical = RelocationSelections.selectMinimumCost(
				analysis, actions, plan.selectedStates(), plan.selectedCandidateSelections(),
				order, ignored -> 1.0);
			RelocationSelections.CandidateProblemIndex index =
				RelocationSelections.candidateProblemIndex(analysis, analysis.graph(), actions,
					plan.selectedStates(), plan.selectedCandidateSelections(), order);
			RelocationSelections.Selection indexed = index.select(plan.selectedCandidateSelections());
			RelocationSelections.ExactEmissionScorer scorer = index.newExactEmissionScorer();
			plan.selectedCandidateSelections().forEach(scorer::selectReceipt);

			Assert.assertEquals(canonical.emittedActions(), indexed.emittedActions());
			Assert.assertEquals(canonical.emittedActions().size(),
				scorer.minimumPhysicalEmissionCount());
		}
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void indexedRelocationHonorsExactDirectAndRelocationBindings() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(crossPoolScript(true)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		NeutralPlacementGraph.RelocationAction action = analysis.graph().relocationActions().stream()
			.filter(candidate -> !analysis.graph().isRelocationActive(
				candidate, plan.selectedStates(), List.of()))
			.findFirst().orElseThrow();
		var obligation = action.obligations().stream()
			.filter(candidate -> candidate.requiredPlacement().equals(
				plan.selectedStates().get(candidate.consumer())))
			.findFirst().orElseThrow();
		CandidateSelectionReceipt originalConsumer = plan.selectedCandidateSelections().stream()
			.filter(receipt -> receipt.rule().parentOccurrence() == obligation.consumer())
			.findFirst().orElseThrow();
		CompiledHopKey sourceOwner = analysis.graph().nodes().stream()
			.filter(node -> node.valueVersion().equals(action.key().sourceValueVersion()))
			.map(NeutralPlacementGraph.Node::key)
			.filter(plan.selectedStates()::containsKey)
			.findFirst().orElseThrow();
		CandidateSelectionReceipt originalSource = plan.selectedCandidateSelections().stream()
			.filter(receipt -> receipt.rule().parentOccurrence() == sourceOwner)
			.findFirst().orElseThrow();

		DurableAnchorKey directAnchor = action.key().durableAnchor();
		List<AnchorPartition> shiftedPartitions = new ArrayList<>(directAnchor.partitions());
		AnchorPartition first = shiftedPartitions.get(0);
		shiftedPartitions.set(0, new AnchorPartition(first.workerId() + "-other",
			first.begin(), first.end()));
		DurableAnchorKey shiftedAnchor = new DurableAnchorKey("shifted-source",
			directAnchor.fType(), shiftedPartitions);
		CandidateEmissionRealization directSourceRealization = CandidateEmissionRealization.durable(
			originalSource.emission().emissionState(), directAnchor, List.of(), List.of());
		CandidateEmissionRealization proofDistinctDirectSourceRealization =
			CandidateEmissionRealization.durable(originalSource.emission().emissionState(),
				directAnchor, List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
					sourceOwner, "relocation-score-cache-proof-variant")), List.of());
		CandidateEmissionRealization shiftedSourceRealization = CandidateEmissionRealization.durable(
			originalSource.emission().emissionState(), shiftedAnchor, List.of(), List.of());
		CandidateEmissionRealization unanchoredSourceRealization =
			CandidateEmissionRealization.nativeLineage(
				originalSource.emission().emissionState(), "unanchored-native-source",
				List.of(), List.of());
		CandidateSelectionReceipt directSource = receiptWithRealization(
			originalSource, directSourceRealization);
		CandidateSelectionReceipt proofDistinctDirectSource = receiptWithRealization(
			originalSource, proofDistinctDirectSourceRealization);
		CandidateSelectionReceipt shiftedSource = receiptWithRealization(
			originalSource, shiftedSourceRealization);
		CandidateSelectionReceipt unanchoredSource = receiptWithRealization(
			originalSource, unanchoredSourceRealization);
		Assert.assertTrue("an unanchored native realization cannot suppress relocation from coarse state",
			analysis.graph().isRelocationActive(action, plan.selectedStates(),
				List.of(unanchoredSource)));
		PlacementProofKey nativeContinuity = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, sourceOwner, "same-pool-native-source");
		CandidateEmissionRealization witnessedNativeSourceRealization =
			CandidateEmissionRealization.nativeLineage(
				originalSource.emission().emissionState(), "witnessed-native-source", directAnchor,
				List.of(nativeContinuity), List.of());
		CandidateSelectionReceipt witnessedNativeSource = receiptWithRealization(
			originalSource, witnessedNativeSourceRealization);
		Assert.assertFalse("a typed native worker-pool witness authorizes direct same-pool reuse",
			analysis.graph().isRelocationActive(action, plan.selectedStates(),
				List.of(witnessedNativeSource)));
		CandidateRealizationReference directSourceRef = CandidateRealizationReference.of(
			directSource.rule(), directSource.realization());
		CandidateRealizationReference shiftedSourceRef = CandidateRealizationReference.of(
			shiftedSource.rule(), shiftedSource.realization());
		CandidateEmissionRealization directConsumerRealization = new CandidateEmissionRealization(
			originalConsumer.realization().key(), originalConsumer.supportClause().proofDependencies(),
			List.of(CandidateRealizationInputBinding.direct(
				obligation.inputPosition(), directSourceRef)));
		CandidateEmissionRealization relocatedConsumerRealization = new CandidateEmissionRealization(
			originalConsumer.realization().key(), originalConsumer.supportClause().proofDependencies(),
			List.of(CandidateRealizationInputBinding.relocation(
				obligation.inputPosition(), shiftedSourceRef, action.key())));
		CandidateEmissionFact consumerEmission = new CandidateEmissionFact(
			originalConsumer.emission().emissionState(), originalConsumer.emission().executionFType(),
			originalConsumer.emission().derivedFoutAction(),
			List.of(directConsumerRealization, relocatedConsumerRealization));
		CandidateEmissionRealization consumerRealization = consumerEmission.realizations().get(0);
		Assert.assertEquals("same layout must retain direct/relocation as OR support clauses",
			2, consumerRealization.supportClauses().size());
		var directClause = consumerRealization.supportClauses().stream()
			.filter(clause -> clause.inputBindings().stream().anyMatch(binding ->
				binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT))
			.findFirst().orElseThrow();
		var relocatedClause = consumerRealization.supportClauses().stream()
			.filter(clause -> clause.inputBindings().stream().anyMatch(binding ->
				binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION))
			.findFirst().orElseThrow();
		CandidateSelectionReceipt directConsumer = new CandidateSelectionReceipt(
			originalConsumer.rule(), consumerEmission, consumerRealization, directClause, List.of());
		CandidateSelectionReceipt relocatedConsumer = new CandidateSelectionReceipt(
			originalConsumer.rule(), consumerEmission, consumerRealization, relocatedClause, List.of());
		List<CandidateSelectionReceipt> universe = List.of(
			directSource, proofDistinctDirectSource, shiftedSource, directConsumer, relocatedConsumer);
		RelocationSelections.CanonicalOrderIndex order = analysis.relocationOrder();
		RelocationSelections.CandidateProblemIndex index = RelocationSelections.candidateProblemIndex(
			analysis, analysis.graph(), analysis.graph().relocationActions(),
			plan.selectedStates(), universe, order);
		Assert.assertNotEquals("distinct exact realization/clause authority must retain distinct receipt identity",
			directSource.normalizedSignature(), proofDistinctDirectSource.normalizedSignature());

		assertIndexedCanonicalParity(analysis, plan.selectedStates(), order, index,
			List.of(directSource, directConsumer), 0);
		assertIndexedCanonicalParity(analysis, plan.selectedStates(), order, index,
			List.of(proofDistinctDirectSource, directConsumer), 0);
		assertIndexedCanonicalParity(analysis, plan.selectedStates(), order, index,
			List.of(shiftedSource, relocatedConsumer), 1);
	}

	private static CandidateSelectionReceipt receiptWithRealization(
		CandidateSelectionReceipt original, CandidateEmissionRealization realization) {
		CandidateEmissionFact emission = new CandidateEmissionFact(
			original.emission().emissionState(), original.emission().executionFType(),
			original.emission().derivedFoutAction(), List.of(realization));
		return new CandidateSelectionReceipt(original.rule(), emission, realization,
			original.fallbackMaterializations());
	}

	private static void assertIndexedCanonicalParity(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		RelocationSelections.CanonicalOrderIndex order,
		RelocationSelections.CandidateProblemIndex index,
		List<CandidateSelectionReceipt> selected, int expectedEmissions) {
		RelocationSelections.Selection canonical = RelocationSelections.selectCanonicalPrevalidated(
			analysis, analysis.graph(), analysis.graph().relocationActions(), assignment, selected,
			order, (demand, action) -> CandidateSelections.realizationActionAllowed(
				analysis, assignment, selected, demand, action));
		RelocationSelections.Selection indexed = index.select(selected);
		RelocationSelections.ExactEmissionScorer scorer = index.newExactEmissionScorer();
		selected.forEach(scorer::selectReceipt);
		Assert.assertEquals(expectedEmissions, canonical.emittedActions().size());
		Assert.assertEquals(canonical.emittedActions(), indexed.emittedActions());
		Assert.assertEquals(expectedEmissions, scorer.minimumPhysicalEmissionCount());
	}

	@Test
	public void forgedNormalizedResultFailsBeforeAnyRegistryMutation() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(localMaterializationScript()));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis publicAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult publicPlan = new FedAllPlacementAdapter().select(publicAnalysis);
		NeutralPlacementGraph.Node source = publicAnalysis.graph().decisionNodes().stream()
			.filter(node -> publicAnalysis.isCompiledHopOccurrence(node.key()))
			.filter(node -> !publicAnalysis.isDmlFunctionCallBoundary(node.key()))
			.filter(node -> {
				PlacementState state = publicPlan.selectedStates().get(node.key());
				return state != null && (state.execType() != ExecType.FED
					|| state.output() != FederatedOutput.FOUT);
			}).findFirst().orElseThrow();
		PlacementAnalysis protectedAnalysis = withPrivacy(publicAnalysis,
			Map.of(source.key(), Privacy.PRIVATE_AGGREGATE), program);
		program.install(protectedAnalysis);
		NormalizedPlannerResult unsigned = rebind(publicPlan, protectedAnalysis, "pending");
		var beforeRefed = FederatedRefedRegistry.snapshotAll();
		var beforeFout = FederatedFoutMaterializeRegistry.snapshotAll();
		var beforeLocal = FederatedLocalMaterializeRegistry.snapshotAll();

		Assert.assertThrows("a stale cross-analysis receipt cannot be assigned a valid plan hash",
			IllegalArgumentException.class, () -> PlacementEmissionTransaction.canonicalPlanHash(unsigned));
		Assert.assertEquals(beforeRefed, FederatedRefedRegistry.snapshotAll());
		Assert.assertEquals(beforeFout, FederatedFoutMaterializeRegistry.snapshotAll());
		Assert.assertEquals(beforeLocal, FederatedLocalMaterializeRegistry.snapshotAll());
		Assert.assertTrue(PlacementEmissionTransaction.receiptSnapshotForTesting().isEmpty());
	}

	@Test
	public void loopBootstrapDoesNotAuthorizeAnUnrelatedLocalBackedge() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(unsafeLoopBackedgeScript()));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
	}

	@Test
	public void loopBootstrapDoesNotRetainAStaleAnchorAcrossTranspose() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(incompatibleLoopAnchorScript()));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void publicTransposeLoopRetainsExecutablePlacement() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(incompatibleLoopAnchorScript()));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NormalizedPlannerResult selected = new FedAllPlacementAdapter().select(analysis);
		Assert.assertFalse("the public loop must retain executable placement rather than being rejected by opcode",
			selected.selectedStates().isEmpty());
		Assert.assertTrue("the compiled transpose must be covered by the selected plan",
			analysis.graph().decisionNodes().stream().anyMatch(node -> analysis.hop(node.key())
				.map(hop -> "r(r')".equals(hop.getOpString())).orElse(false)
				&& selected.selectedStates().containsKey(node.key())));
	}

	@Test
	public void matchingFTypeDoesNotAlignDifferentWorkerPools() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(crossPoolAggregateScript()));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		var beforeRefed = FederatedRefedRegistry.snapshotAll();
		var beforeFout = FederatedFoutMaterializeRegistry.snapshotAll();
		var beforeLocal = FederatedLocalMaterializeRegistry.snapshotAll();
		// Exact publication now rejects the unsupported protected inputs before a
		// selector can see a coarse same-FType candidate. Aggregate output privacy
		// does not authorize moving those inputs between the two worker endpoints.
		DMLRuntimeException rejected = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(rejected.getMessage(),
			rejected.getMessage().contains("No privacy-safe physical placement"));
		Assert.assertEquals(beforeRefed, FederatedRefedRegistry.snapshotAll());
		Assert.assertEquals(beforeFout, FederatedFoutMaterializeRegistry.snapshotAll());
		Assert.assertEquals(beforeLocal, FederatedLocalMaterializeRegistry.snapshotAll());
		Assert.assertTrue(PlacementEmissionTransaction.receiptSnapshotForTesting().isEmpty());
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void transientJoinRejectsMissingReachingDefinitionCompatibility() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compile(
			"X=federated(addresses=list(\"localhost:20334/X1\",\"localhost:20335/X2\"),"
				+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
				+ "z=X; if(sum(matrix(1,rows=1,cols=1))>0) { z=X+1; } else { z=X+2; }\n"
				+ "print(sum(z));\n"));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Map<String,List<PlacementAnalysis.LogicalTransientInputFact>> byReader = new LinkedHashMap<>();
		for(var fact : analysis.logicalTransientInputsInCanonicalOrder())
			byReader.computeIfAbsent(fact.targetRead().normalizedSignature() + ':' + fact.logicalPosition(),
				ignored -> new ArrayList<>()).add(fact);
		List<PlacementAnalysis.LogicalTransientInputFact> reaching = byReader.values().stream()
			.filter(facts -> facts.size() > 1).findFirst().orElseThrow();
		List<PlacementAnalysis.LogicalTransientInputFact> incomplete = new ArrayList<>(
			analysis.logicalTransientInputsInCanonicalOrder());
		incomplete.remove(reaching.get(0));

		Assert.assertThrows("a reader alternative needs compatible support from every reaching writer",
			IllegalArgumentException.class,
			() -> withLogicalTransientInputs(analysis, incomplete, program));
	}

	private static PlacementAnalysis withPrivacy(PlacementAnalysis source,
		Map<CompiledHopKey,Privacy> replacements, DMLProgram owner) {
		return withGraphPrivacy(source, source.graph(), replacements, owner);
	}

	private static PlacementAnalysis withGraphPrivacy(PlacementAnalysis source,
		NeutralPlacementGraph graph, Map<CompiledHopKey,Privacy> replacements, DMLProgram owner) {
		Map<CompiledHopKey,PlacementAnalysis.NodeShapeFact> shapes = new LinkedHashMap<>();
		LinkedHashSet<CompiledHopKey> keys = new LinkedHashSet<>();
		for(HopOccurrenceProjection occurrence : source.occurrences()) {
			keys.add(occurrence.key());
			shapes.put(occurrence.key(), source.shapeFact(occurrence.key()).orElseThrow());
		}
		PlacementShapeFacts shapeFacts = new PlacementShapeFacts(shapes, keys);
		List<PlacementPrivacyFacts.PrivacyFact> privacy = graph.nodes().stream()
			.map(node -> new PlacementPrivacyFacts.PrivacyFact(node.key(), node.valueVersion(),
				replacements.getOrDefault(node.key(), source.requirePrivacy(node.key())), List.of()))
			.toList();
		return new PlacementAnalysis(graph, source.occurrences(), source.topLevelStatementBlocks(), owner,
			shapeFacts, source.analysisFingerprint(), source.heuristicPolicyFacts(),
			source.candidateRuleDomain().orderedRuleKeys(), rebindRelocationActions(source, graph),
			source.candidateRuleDomain().orderedConsumerKeys(),
			source.candidateConsumerProfileFacts().orderedFacts(),
			source.detachedConsumerProfileFacts().orderedFacts(), source.compiledInputEdgesInCanonicalOrder(),
			source.logicalTransientInputsInCanonicalOrder(),
			new PlacementPrivacyFacts(graph.nodes(), privacy, source.numWorkers()), null);
	}

	private static List<PlacementAnalysis.CandidateRuleFact> rebindRelocationActions(
		PlacementAnalysis source, NeutralPlacementGraph graph) {
		Map<String,RelocationActionKey> actions = graph.relocationActions().stream().collect(
			java.util.stream.Collectors.toMap(action -> action.key().normalizedSignature(),
				NeutralPlacementGraph.RelocationAction::key));
		List<PlacementAnalysis.CandidateRuleFact> rebound = new ArrayList<>();
		for(var fact : source.candidateRuleFacts().orderedFacts()) {
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> realizations = new ArrayList<>();
				for(CandidateEmissionRealization realization : emission.realizations()) {
					List<PlacementAnalysis.CandidateRealizationSupportClause> clauses = new ArrayList<>();
					for(var clause : realization.supportClauses()) {
						List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
						boolean graphOwned = true;
						for(var binding : clause.inputBindings()) {
							if(binding.kind() != PlacementIdentity.CandidateInputBindingKind.RELOCATION) {
								bindings.add(binding);
								continue;
							}
							RelocationActionKey action = actions.get(binding.relocationAction().normalizedSignature());
							if(action == null) {
								graphOwned = false;
								break;
							}
							bindings.add(CandidateRealizationInputBinding.relocation(
								binding.inputPosition(), binding.source(), action));
						}
						if(graphOwned)
							clauses.add(new PlacementAnalysis.CandidateRealizationSupportClause(
								clause.proofDependencies(), bindings, clause.nativeWorkerPoolWitness()));
					}
					if(!clauses.isEmpty())
						realizations.add(new CandidateEmissionRealization(realization.key(), clauses));
				}
				if(!realizations.isEmpty())
					emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
						emission.derivedFoutAction(), realizations));
			}
			if(fact.status() == PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE && emissions.isEmpty())
				throw new IllegalStateException("Replacement graph removed every realization of an AVAILABLE fixture row");
			rebound.add(new PlacementAnalysis.CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
				fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(rebound);
	}

	private static PlacementAnalysis withLogicalTransientInputs(PlacementAnalysis source,
		List<PlacementAnalysis.LogicalTransientInputFact> logicalInputs, DMLProgram owner) {
		Map<CompiledHopKey,PlacementAnalysis.NodeShapeFact> shapes = new LinkedHashMap<>();
		LinkedHashSet<CompiledHopKey> keys = new LinkedHashSet<>();
		for(HopOccurrenceProjection occurrence : source.occurrences()) {
			keys.add(occurrence.key());
			shapes.put(occurrence.key(), source.shapeFact(occurrence.key()).orElseThrow());
		}
		List<PlacementPrivacyFacts.PrivacyFact> privacy = source.graph().nodes().stream()
			.map(node -> new PlacementPrivacyFacts.PrivacyFact(node.key(), node.valueVersion(),
				source.requirePrivacy(node.key()), List.of())).toList();
		return new PlacementAnalysis(source.graph(), source.occurrences(),
			source.topLevelStatementBlocks(), owner, new PlacementShapeFacts(shapes, keys),
			source.analysisFingerprint(), source.heuristicPolicyFacts(),
			source.candidateRuleDomain().orderedRuleKeys(), source.candidateRuleFacts().orderedFacts(),
			source.candidateRuleDomain().orderedConsumerKeys(),
			source.candidateConsumerProfileFacts().orderedFacts(),
			source.detachedConsumerProfileFacts().orderedFacts(),
			source.compiledInputEdgesInCanonicalOrder(), logicalInputs,
			new PlacementPrivacyFacts(source.graph().nodes(), privacy, source.numWorkers()), null);
	}

	private static NormalizedPlannerResult rebind(NormalizedPlannerResult source,
		PlacementAnalysis analysis, String hash) {
		return new NormalizedPlannerResult() {
			@Override public PlacementAnalysis analysis() { return analysis; }
			@Override public String plannerId() { return source.plannerId(); }
			@Override public String analysisFingerprint() { return analysis.analysisFingerprint(); }
			@Override public Map<CompiledHopKey,PlacementState> selectedStates() {
				return source.selectedStates();
			}
			@Override public Map<CompiledHopKey,PlacementEmissionState> selectedEmissionStates() {
				return source.selectedEmissionStates();
			}
			@Override public List<RelocationActionKey> selectedRelocations() {
				return source.selectedRelocations();
			}
			@Override public List<RelocationChoiceReceipt> selectedRelocationChoices() {
				return source.selectedRelocationChoices();
			}
			@Override public List<PlacementIdentity.CandidateSelectionReceipt> selectedCandidateSelections() {
				return source.selectedCandidateSelections();
			}
			@Override public List<LocalMaterializationActionKey> selectedLocalMaterializations() {
				return source.selectedLocalMaterializations();
			}
			@Override public String objectiveCertificate() { return source.objectiveCertificate(); }
			@Override public String normalizedPlanFingerprint() { return hash; }
		};
	}

	private static NeutralPlacementGraph.Node federatedSource(PlacementAnalysis analysis, String name) {
		return analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.filter(DataOp.class::isInstance).map(DataOp.class::cast)
			.map(hop -> name.equals(hop.getName())).orElse(false)).findFirst().orElseThrow();
	}

	private static NeutralPlacementGraph.Node relocationSource(PlacementAnalysis analysis,
		RelocationActionKey action) {
		return analysis.graph().nodes().stream()
			.filter(node -> analysis.isCompiledHopOccurrence(node.key())
				&& node.valueVersion().equals(action.sourceValueVersion()))
			.findFirst().orElseThrow();
	}

	private static String localMaterializationScript() {
		return "X=federated(addresses=list(\"localhost:1234/X1\"),"
			+ "ranges=list(list(0,0),list(4,2)));S=rand(rows=4,cols=2,seed=7);"
			+ "Y=X+S;write(Y,\"/tmp/privacy-movement-y\",format=\"binary\");";
	}

	private static String crossPoolScript(boolean samePool) {
		String right = samePool ? "localhost:1234/B1" : "localhost:2234/B1";
		return "A=federated(addresses=list(\"localhost:1234/A1\"),ranges=list(list(0,0),list(4,2)));"
			+ "B=federated(addresses=list(\"" + right + "\"),ranges=list(list(0,0),list(4,2)));"
			+ "C=A+B;write(C,\"/tmp/privacy-movement-c\",format=\"binary\");";
	}

	private static String crossPoolAggregateScript() {
		return "A=federated(addresses=list(\"localhost:1234/A1\"),ranges=list(list(0,0),list(4,2)));"
			+ "B=federated(addresses=list(\"localhost:2234/B1\"),ranges=list(list(0,0),list(4,2)));"
			+ "C=A+B;print(sum(exp(C)));";
	}

	private static String unsafeLoopBackedgeScript() {
		return "A=federated(addresses=list(\"localhost:1234/A1\"),"
			+ "ranges=list(list(0,0),list(4,2)));B=A+1;i=1;"
			+ "while(i<=2){if(i>0){B=B+1;}else{B=matrix(1,rows=4,cols=2);}i=i+1;}"
			+ "write(B,\"/tmp/privacy-movement-unsafe-loop\",format=\"binary\");";
	}

	private static String incompatibleLoopAnchorScript() {
		return "A=federated(addresses=list(\"localhost:1234/A1\"),"
			+ "ranges=list(list(0,0),list(4,4)));B=A;i=1;"
			+ "while(i<=2){B=t(B);i=i+1;}"
			+ "write(B,\"/tmp/privacy-movement-incompatible-loop\",format=\"binary\");";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static final class FixtureProgram extends DMLProgram {
		private PlacementAnalysis authority;
		private FixtureProgram() { super(DMLProgram.DEFAULT_NAMESPACE); }
		private static FixtureProgram adopt(DMLProgram compiled) {
			FixtureProgram program = new FixtureProgram();
			program.getStatementBlocks().addAll(new ArrayList<>(compiled.getStatementBlocks()));
			return program;
		}
		private void install(PlacementAnalysis analysis) { authority = analysis; }
		@Override public PlacementAnalysis requirePlacementAnalysisAuthority() { return authority; }
		@Override public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
			if(candidate != authority)
				throw new IllegalArgumentException("foreign fixture authority");
		}
	}
}
