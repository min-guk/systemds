/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ContributionKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EdgeContribution;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationEndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ValidationException;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ValidationReason;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Deterministic projection from neutral placement semantics to exact MinST cut facts. */
public final class MinStExactCostFactsProducer {
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;
	private static final double HARD_LEGALITY = 1e15;

	private MinStExactCostFactsProducer() {
		// utility class
	}

	public static MinStExactCostFacts derive(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope) {
		Objects.requireNonNull(analysis, "analysis");
		validateScope(analysis, orderedScope);
		Derivation derivation = deriveUnchecked(analysis, orderedScope);
		return new MinStExactCostFacts(analysis, analysis.analysisFingerprint(), orderedScope,
			derivation.decisions, derivation.edges, derivation.groups,
			derivation.obligations, derivation.fingerprint);
	}

	static void validate(PlacementAnalysis analysis, String analysisFingerprint,
		List<CompiledHopKey> orderedScope, List<DecisionFact> decisions,
		List<DirectedEdgeFact> edges, List<AuxiliaryGroupFact> groups,
		List<ObligationFact> obligations, String derivationFingerprint) {
		Objects.requireNonNull(analysis, "analysis");
		if(!analysis.analysisFingerprint().equals(analysisFingerprint))
			fail(ValidationReason.FOREIGN_OWNER, "Analysis fingerprint is foreign to its owner");
		validateScope(analysis, orderedScope);
		validateCapacitySums(edges);
		Derivation expected = deriveUnchecked(analysis, orderedScope);
		if(!sameDecisions(expected.decisions, decisions))
			fail(ValidationReason.RAW_STATE_RECEIPT_MISMATCH,
				"Decision states differ from the pre-solve legality projection");
		validateGroups(expected.groups, groups);
		if(!sameObligations(expected.obligations, obligations))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Obligation facts differ from the neutral graph");
		if(!sameEdges(expected.edges, edges))
			fail(ValidationReason.CAPACITY_SUM_MISMATCH,
				"Directed edge facts differ from their canonical derivation");
		if(!expected.fingerprint.equals(derivationFingerprint))
			fail(ValidationReason.DERIVATION_FINGERPRINT_MISMATCH,
				"Derivation fingerprint is stale or forged");
	}

	private static Derivation deriveUnchecked(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope) {
		IdentityHashMap<CompiledHopKey, DecisionFact> decisionsByKey = new IdentityHashMap<>();
		List<DecisionFact> decisions = new ArrayList<>(orderedScope.size());
		for(int index = 0; index < orderedScope.size(); index++) {
			CompiledHopKey key = orderedScope.get(index);
			Hop hop = analysis.hop(key).orElseThrow();
			List<PlacementState> states = legalStates(analysis, key);
			DecisionFact decision = new DecisionFact(key, computeNodeId(index),
				placementNodeId(index), states);
			decisions.add(decision);
			decisionsByKey.put(key, decision);
		}

		int workers = workerCount(analysis.graph());
		EdgeAccumulator accumulator = new EdgeAccumulator();
		for(DecisionFact decision : decisions)
			addDecisionEdges(analysis, decision, workers, accumulator);
		List<AuxiliaryGroupFact> groups = deriveGroups(analysis, orderedScope,
			decisionsByKey, workers, accumulator);
		List<ObligationFact> obligations = deriveObligations(analysis, decisionsByKey);
		List<DirectedEdgeFact> edges = accumulator.freeze();
		String fingerprint = fingerprint(analysis, orderedScope, decisions, edges, groups, obligations);
		return new Derivation(List.copyOf(decisions), edges, groups, obligations, fingerprint);
	}

	private static List<PlacementState> legalStates(PlacementAnalysis analysis, CompiledHopKey key) {
		Set<PlacementState> legal = new TreeSet<>();
		NeutralPlacementGraph.Node node = analysis.graph().node(key).orElseThrow();
		for(PlacementState state : node.legalAlternatives())
			if(preSolveLegal(analysis, key, state))
				legal.add(state);
		for(NeutralPlacementGraph.RelocationAction action : analysis.graph().relocationActions())
			if(action.key().sourceValueVersion().equals(node.valueVersion())
				&& preSolveLegal(analysis, key, action.key().targetPlacement()))
				legal.add(action.key().targetPlacement());
		if(legal.isEmpty())
			throw new IllegalArgumentException("Neutral decision has no pre-solve legal state: " + key);
		return List.copyOf(legal);
	}

	private static boolean preSolveLegal(PlacementAnalysis analysis, CompiledHopKey consumerKey,
		PlacementState state) {
		if(state.execType() != ExecType.FED)
			return true;
		Hop consumer = analysis.hop(consumerKey).orElseThrow(() ->
			new IllegalArgumentException("MINST_CONSUMER_HOP_UNPROVEN"));
		for(Hop input : consumer.getInput())
			if(input != null && input.getDataType() != null && input.getDataType().isMatrix()
				&& input instanceof DataGenOp)
				return false;
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.consumer() != consumerKey)
				continue;
			if(analysis.requireExactCompiledInputEdge(edge.producer(), edge.consumer(),
				edge.inputPosition()) != edge)
				throw new IllegalArgumentException("MINST_COMPILED_EDGE_IDENTITY_UNPROVEN");
			if(edge.inputPosition() >= consumer.getInput().size())
				throw new IllegalArgumentException("MINST_INPUT_POSITION_UNPROVEN");
			Hop input = consumer.getInput().get(edge.inputPosition());
			if(input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				throw new IllegalArgumentException("MINST_INPUT_HOP_UNPROVEN");
			NeutralPlacementGraph.Node inputNode = analysis.graph().node(edge.producer()).orElseThrow();
			boolean hasFederatedRepresentation = inputNode.legalAlternatives().stream()
				.anyMatch(candidate -> candidate.output() == FederatedOutput.FOUT)
				|| isPersistentRead(input)
				|| analysis.graph().relocationActions().stream().anyMatch(action ->
					action.key().sourceValueVersion().equals(inputNode.valueVersion()));
			if(!hasFederatedRepresentation)
				return false;
		}
		return true;
	}

	private static void addDecisionEdges(PlacementAnalysis analysis, DecisionFact decision,
		int workers, EdgeAccumulator edges) {
		Hop hop = analysis.hop(decision.key()).orElseThrow();
		boolean cp = hasExec(decision, ExecType.CP);
		boolean fed = hasExec(decision, ExecType.FED);
		boolean lout = hasOutput(decision, FederatedOutput.LOUT);
		boolean fout = hasOutput(decision, FederatedOutput.FOUT);
		boolean fedLout = hasState(decision, ExecType.FED, FederatedOutput.LOUT);
		boolean cpFout = hasState(decision, ExecType.CP, FederatedOutput.FOUT);

		double base = requireCost(FederatedCostModel.computeOpCost(hop), "MINST_CP_COST_UNPROVEN");
		double fedCost = requireCost(FederatedCostModel.computeFederatedComputeCost(
			hop, base, workers, false) + FederatedCostModel.computeFedCoordinationCost(workers),
			"MINST_FED_COST_UNPROVEN");
		edges.add(SOURCE, decision.computeNodeId(), cp ? base : HARD_LEGALITY,
			cp ? ContributionKind.CP_UNARY : ContributionKind.HARD_EXEC,
			decision.key(), null, -1, cp ? "neutral-cp-unary" : "pre-solve-cp-illegal");
		edges.add(decision.computeNodeId(), SINK, fed ? fedCost : HARD_LEGALITY,
			fed ? ContributionKind.FED_UNARY : ContributionKind.HARD_EXEC,
			decision.key(), null, -1, fed ? "neutral-fed-unary" : "pre-solve-fed-illegal");
		if(!lout)
			edges.add(SOURCE, decision.placementNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1, "pre-solve-lout-illegal");
		if(!fout)
			edges.add(decision.placementNodeId(), SINK, HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1, "pre-solve-fout-illegal");

		if(!fedLout)
			edges.add(decision.computeNodeId(), decision.placementNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1,
				"pre-solve-fed-lout-illegal");
		else {
			FType fType = requireUniqueLayoutType(decision);
			double bytes = estimatedBytes(analysis, decision.key(), hop);
			double download = requireCost(FederatedCostModel.computeDownloadNetworkCost(bytes,
				fType, workers), "MINST_DOWNLOAD_COST_UNPROVEN");
			edges.add(decision.computeNodeId(), decision.placementNodeId(), download,
				ContributionKind.DOWNLOAD, decision.key(), null, -1, "native-fed-lout-download");
		}
		if(!cpFout)
			edges.add(decision.placementNodeId(), decision.computeNodeId(), HARD_LEGALITY,
				ContributionKind.HARD_OUTPUT, decision.key(), null, -1,
				"pre-solve-cp-fout-illegal");
		else {
			FType fType = requireUniqueLayoutType(decision);
			double bytes = estimatedBytes(analysis, decision.key(), hop);
			double upload = requireCost(FederatedCostModel.computeUploadNetworkCost(bytes,
				fType, workers), "MINST_UPLOAD_COST_UNPROVEN");
			edges.add(decision.placementNodeId(), decision.computeNodeId(), upload,
				ContributionKind.UPLOAD, decision.key(), null, -1, "native-cp-fout-upload");
		}
	}

	private static List<AuxiliaryGroupFact> deriveGroups(PlacementAnalysis analysis,
		List<CompiledHopKey> orderedScope, IdentityHashMap<CompiledHopKey, DecisionFact> decisions,
		int workers, EdgeAccumulator edges) {
		List<AuxiliaryGroupFact> result = new ArrayList<>();
		IdentityHashMap<CompiledHopKey,List<CompiledInputEdgeFact>> edgesByProducer = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(analysis.requireExactCompiledInputEdge(edge.producer(), edge.consumer(),
				edge.inputPosition()) != edge)
				throw new IllegalArgumentException("MINST_COMPILED_EDGE_IDENTITY_UNPROVEN");
			if(decisions.containsKey(edge.producer()) && decisions.containsKey(edge.consumer()))
				edgesByProducer.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(edge);
		}
		long nextAux = -3L;
		for(CompiledHopKey producerKey : orderedScope) {
			DecisionFact producerDecision = decisions.get(producerKey);
			Hop producer = analysis.hop(producerKey).orElseThrow();
			if(producer.getDataType() == null || !producer.getDataType().isMatrix())
				continue;
			Map<GroupDemandKey,List<Use>> demands = new LinkedHashMap<>();
			for(CompiledInputEdgeFact edge : edgesByProducer.getOrDefault(producerKey, List.of())) {
				CompiledHopKey consumerKey = edge.consumer();
				DecisionFact consumerDecision = decisions.get(consumerKey);
				if(hasExec(consumerDecision, ExecType.FED) && canUpload(producer)) {
					FType type = requiredType(analysis, edge);
					demands.computeIfAbsent(new GroupDemandKey(Direction.UPLOAD, type), ignored ->
						new ArrayList<>()).add(new Use(edge, consumerDecision));
				}
				if(hasExec(consumerDecision, ExecType.CP)
					&& hasOutput(producerDecision, FederatedOutput.FOUT)) {
					FType type = requireUniqueLayoutType(producerDecision);
					demands.computeIfAbsent(new GroupDemandKey(Direction.DOWNLOAD, type), ignored ->
						new ArrayList<>()).add(new Use(edge, consumerDecision));
				}
			}
			for(Map.Entry<GroupDemandKey,List<Use>> entry : demands.entrySet()) {
				List<Use> uses = entry.getValue().stream()
					.sorted(Comparator.comparing((Use use) -> use.edge.consumer().normalizedSignature())
						.thenComparingInt(use -> use.edge.inputPosition()))
					.toList();
				List<EndpointFact> endpoints = new ArrayList<>(uses.size());
				double bytes = estimatedBytes(analysis, producerKey, producer);
				double demand = entry.getKey().direction == Direction.UPLOAD
					? FederatedCostModel.computeUploadNetworkCost(bytes, entry.getKey().type, workers)
					: FederatedCostModel.computeDownloadNetworkCost(bytes, entry.getKey().type, workers);
				demand = requireCost(demand, "MINST_GROUP_DEMAND_COST_UNPROVEN");
				for(Use use : uses)
					endpoints.add(new EndpointFact(use.edge.producer(), use.edge.consumer(), use.edge.inputPosition(),
						use.consumerDecision.computeNodeId(), bits(demand)));
				long aux = nextAux--;
				AuxiliaryGroupFact group = new AuxiliaryGroupFact(aux, entry.getKey().direction,
					producerKey, producerDecision.placementNodeId(), entry.getKey().type,
					bits(demand), endpoints);
				result.add(group);
				addGroupEdges(group, edges);
			}
		}
		return List.copyOf(result);
	}

	private static void addGroupEdges(AuxiliaryGroupFact group, EdgeAccumulator edges) {
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
			if(group.direction() == Direction.UPLOAD)
				edges.add(endpoint.consumerComputeNodeId(), group.auxiliaryNodeId(),
					HARD_LEGALITY, ContributionKind.HARD_UPLOAD_OR,
					endpoint.consumerKey(), group.producerKey(), endpoint.inputPosition(),
					"upload-or-hard");
			else
				edges.add(group.auxiliaryNodeId(), endpoint.consumerComputeNodeId(),
					HARD_LEGALITY, ContributionKind.HARD_DOWNLOAD_OR,
					endpoint.consumerKey(), group.producerKey(), endpoint.inputPosition(),
					"download-or-hard");
		}
		EndpointFact priceOwner = group.endpointsInCanonicalOrder().stream()
			.max(Comparator.comparingLong(EndpointFact::demandCostBits)
				.thenComparing(endpoint -> endpoint.consumerKey().normalizedSignature()))
			.orElseThrow();
		if(group.direction() == Direction.UPLOAD)
			edges.add(group.auxiliaryNodeId(), group.producerPlacementNodeId(),
				Double.longBitsToDouble(group.priceBits()), ContributionKind.PRICE_UPLOAD_OR,
				group.producerKey(), priceOwner.consumerKey(), priceOwner.inputPosition(),
				"upload-or-price-max");
		else
			edges.add(group.producerPlacementNodeId(), group.auxiliaryNodeId(),
				Double.longBitsToDouble(group.priceBits()), ContributionKind.PRICE_DOWNLOAD_OR,
				group.producerKey(), priceOwner.consumerKey(), priceOwner.inputPosition(),
				"download-or-price-max");
	}

	private static List<ObligationFact> deriveObligations(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey, DecisionFact> decisions) {
		List<ObligationFact> result = new ArrayList<>();
		for(NeutralPlacementGraph.RelocationAction action : analysis.graph().relocationActions()) {
			List<ObligationEndpointFact> endpoints = new ArrayList<>();
			for(ObligationKey obligation : action.obligations())
				if(decisions.containsKey(obligation.consumer()))
					endpoints.add(new ObligationEndpointFact(obligation.consumer(),
						obligation.inputPosition(), obligation.requiredPlacement()));
			if(!endpoints.isEmpty())
				result.add(new ObligationFact(action.normalizedSignature(), endpoints));
		}
		return List.copyOf(result);
	}

	private static FType requiredType(PlacementAnalysis analysis, CompiledInputEdgeFact edge) {
		Set<FType> structuralLayouts = new LinkedHashSet<>();
		addPublishedLayouts(analysis.graph().node(edge.producer()).orElseThrow(), structuralLayouts);
		for(CompiledInputEdgeFact sibling : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(sibling.consumer() != edge.consumer() || sibling.inputPosition() == edge.inputPosition())
				continue;
			addPublishedLayouts(analysis.graph().node(sibling.producer()).orElseThrow(), structuralLayouts);
		}
		try {
			PlacementAnalysis.CandidateConsumerProfileFact profile = analysis.candidateConsumerProfileFacts()
				.requireExact(edge.consumer(), edge.inputPosition());
			if(profile.status() != PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE)
				throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|profile-status="
					+ profile.status() + "|failure=" + profile.failureCode());
			List<FType> allowed = profile.allowedTargetTypes().stream()
				.distinct().sorted(Comparator.comparing(Enum::name)).toList();
			if(allowed.isEmpty()) {
				if(structuralLayouts.size() == 1)
					return structuralLayouts.iterator().next();
				throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|unconstrained-profile");
			}
			List<FType> compatibleLayouts = allowed.stream().filter(structuralLayouts::contains).toList();
			if(compatibleLayouts.size() == 1)
				return compatibleLayouts.get(0);
			if(compatibleLayouts.size() > 1)
				throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|ambiguous-sibling-layout");
			throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|no-structural-intersection");
		}
		catch(PlacementAnalysis.CandidateRuleLookupException missingProfile) {
			if(structuralLayouts.size() == 1)
				return structuralLayouts.iterator().next();
			throw new IllegalArgumentException("MINST_CONSUMER_LAYOUT_UNPROVEN|missing-profile", missingProfile);
		}
	}

	private static void addPublishedLayouts(NeutralPlacementGraph.Node node, Set<FType> layouts) {
		node.legalAlternatives().stream()
			.filter(state -> state.output() == FederatedOutput.FOUT && state.fType() != null)
			.map(PlacementState::fType).forEach(layouts::add);
		node.anchors().stream().map(anchor -> anchor.fType()).forEach(layouts::add);
	}

	private static boolean canUpload(Hop producer) {
		return isPersistentRead(producer) || !(producer instanceof DataGenOp);
	}

	private static boolean isPersistentRead(Hop hop) {
		return hop instanceof DataOp && ((DataOp)hop).getOp() == OpOpData.PERSISTENTREAD;
	}

	private static boolean hasExec(DecisionFact decision, ExecType exec) {
		return decision.legalStatesInCanonicalOrder().stream().anyMatch(state -> state.execType() == exec);
	}

	private static boolean hasOutput(DecisionFact decision, FederatedOutput output) {
		return decision.legalStatesInCanonicalOrder().stream().anyMatch(state -> state.output() == output);
	}

	private static boolean hasState(DecisionFact decision, ExecType exec, FederatedOutput output) {
		return decision.legalStatesInCanonicalOrder().stream()
			.anyMatch(state -> state.execType() == exec && state.output() == output);
	}

	private static FType requireUniqueLayoutType(DecisionFact decision) {
		List<FType> types = decision.legalStatesInCanonicalOrder().stream()
			.map(PlacementState::fType).filter(Objects::nonNull).distinct()
			.sorted(Comparator.comparing(Enum::name)).toList();
		if(types.size() != 1)
			throw new IllegalArgumentException("MINST_DECISION_LAYOUT_UNPROVEN|key="
				+ decision.key().normalizedSignature() + "|types=" + types);
		return types.get(0);
	}

	private static long computeNodeId(int scopeIndex) { return 2L * scopeIndex; }
	private static long placementNodeId(int scopeIndex) { return 2L * scopeIndex + 1L; }

	private static int workerCount(NeutralPlacementGraph graph) {
		Set<String> workers = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : graph.nodes())
			for(var anchor : node.anchors())
				for(var partition : anchor.partitions())
					workers.add(partition.workerId());
		if(workers.isEmpty())
			throw new IllegalArgumentException("MINST_WORKER_COUNT_UNPROVEN");
		return workers.size();
	}

	private static double estimatedBytes(PlacementAnalysis analysis, CompiledHopKey key, Hop hop) {
		double estimate = hop.getOutputMemEstimate();
		if(Double.isFinite(estimate) && estimate > 0.0)
			return estimate;
		double derived = analysis.shapeFact(key).filter(shape -> shape.rows() > 0 && shape.cols() > 0)
			.map(shape -> (double)shape.rows() * shape.cols() * 8.0).orElse(Double.NaN);
		if(!Double.isFinite(derived) || derived <= 0.0)
			throw new IllegalArgumentException("MINST_OUTPUT_BYTES_UNPROVEN|key="
				+ key.normalizedSignature());
		return derived;
	}

	private static double requireCost(double value, String reason) {
		if(!Double.isFinite(value) || value < 0.0
			|| Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(requireCost(value, "MINST_COST_BITS_UNPROVEN"));
	}

	private static void validateScope(PlacementAnalysis analysis, List<CompiledHopKey> scope) {
		Objects.requireNonNull(scope, "orderedScope");
		List<CompiledHopKey> expected = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		Set<CompiledHopKey> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CompiledHopKey key : scope)
			if(!seen.add(key))
				fail(ValidationReason.SCOPE_DUPLICATE, "Scope contains a duplicate key identity");
		Set<CompiledHopKey> expectedIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
		expectedIdentities.addAll(expected);
		for(CompiledHopKey key : scope)
			if(!expectedIdentities.contains(key))
				fail(ValidationReason.SCOPE_FOREIGN, "Scope contains a copied or foreign key");
		if(scope.size() != expected.size())
			fail(ValidationReason.SCOPE_REORDERED, "Scope does not exactly cover compiled occurrences");
		for(int index = 0; index < expected.size(); index++)
			if(scope.get(index) != expected.get(index))
				fail(ValidationReason.SCOPE_REORDERED, "Scope order differs at index " + index);
	}

	private static void validateCapacitySums(List<DirectedEdgeFact> edges) {
		Objects.requireNonNull(edges, "edges");
		for(DirectedEdgeFact edge : edges) {
			double sum = 0.0;
			for(EdgeContribution contribution : edge.contributionsInDerivationOrder()) {
				validateBits(contribution.costBits());
				sum += Double.longBitsToDouble(contribution.costBits());
			}
			validateBits(edge.capacityBits());
			if(edge.capacityBits() != Double.doubleToRawLongBits(sum))
				fail(ValidationReason.CAPACITY_SUM_MISMATCH,
					"Edge capacity differs from its ordered contribution sum");
		}
	}

	private static void validateBits(long value) {
		double cost = Double.longBitsToDouble(value);
		if(!Double.isFinite(cost) || cost < 0.0
			|| value == Double.doubleToRawLongBits(-0.0))
			fail(ValidationReason.CAPACITY_SUM_MISMATCH, "Cost bits are not canonical");
	}

	private static boolean sameDecisions(List<DecisionFact> expected, List<DecisionFact> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			DecisionFact left = expected.get(i), right = actual.get(i);
			if(left.key() != right.key() || left.computeNodeId() != right.computeNodeId()
				|| left.placementNodeId() != right.placementNodeId()
				|| !left.legalStatesInCanonicalOrder().equals(right.legalStatesInCanonicalOrder()))
				return false;
		}
		return true;
	}

	private static void validateGroups(List<AuxiliaryGroupFact> expected,
		List<AuxiliaryGroupFact> actual) {
		if(actual == null || expected.size() != actual.size())
			fail(ValidationReason.OR_GROUP_ENDPOINT_MISMATCH, "Auxiliary group count differs");
		for(int i = 0; i < expected.size(); i++) {
			AuxiliaryGroupFact left = expected.get(i), right = actual.get(i);
			if(left.direction() != right.direction())
				fail(ValidationReason.OR_GROUP_DIRECTION_MISMATCH, "Auxiliary group direction differs");
			if(left.priceBits() != right.priceBits())
				fail(ValidationReason.OR_GROUP_PRICE_MISMATCH, "Auxiliary group price differs");
			if(left.auxiliaryNodeId() != right.auxiliaryNodeId()
				|| left.producerKey() != right.producerKey()
				|| left.producerPlacementNodeId() != right.producerPlacementNodeId()
				|| left.conversionType() != right.conversionType()
				|| !sameEndpoints(left.endpointsInCanonicalOrder(), right.endpointsInCanonicalOrder()))
				fail(ValidationReason.OR_GROUP_ENDPOINT_MISMATCH, "Auxiliary group endpoints differ");
		}
	}

	private static boolean sameEndpoints(List<EndpointFact> expected, List<EndpointFact> actual) {
		if(expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			EndpointFact left = expected.get(i), right = actual.get(i);
			if(left.producerKey() != right.producerKey()
				|| left.inputPosition() != right.inputPosition()
				|| left.consumerKey() != right.consumerKey()
				|| left.consumerComputeNodeId() != right.consumerComputeNodeId()
				|| left.demandCostBits() != right.demandCostBits())
				return false;
		}
		return true;
	}

	private static boolean sameEdges(List<DirectedEdgeFact> expected, List<DirectedEdgeFact> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			DirectedEdgeFact left = expected.get(i), right = actual.get(i);
			if(left.fromNodeId() != right.fromNodeId() || left.toNodeId() != right.toNodeId()
				|| left.capacityBits() != right.capacityBits()
				|| !sameContributions(left.contributionsInDerivationOrder(),
					right.contributionsInDerivationOrder()))
				return false;
		}
		return true;
	}

	private static boolean sameContributions(List<EdgeContribution> expected,
		List<EdgeContribution> actual) {
		if(expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			EdgeContribution left = expected.get(i), right = actual.get(i);
			if(left.kind() != right.kind() || left.ownerKey() != right.ownerKey()
				|| left.peerKeyOrNull() != right.peerKeyOrNull()
				|| left.inputPosition() != right.inputPosition()
				|| left.costBits() != right.costBits()
				|| !left.provenance().equals(right.provenance()))
				return false;
		}
		return true;
	}

	private static boolean sameObligations(List<ObligationFact> expected,
		List<ObligationFact> actual) {
		if(actual == null || expected.size() != actual.size()) return false;
		for(int i = 0; i < expected.size(); i++) {
			ObligationFact left = expected.get(i), right = actual.get(i);
			if(!left.actionSignature().equals(right.actionSignature())
				|| left.endpointsInCanonicalOrder().size() != right.endpointsInCanonicalOrder().size())
				return false;
			for(int j = 0; j < left.endpointsInCanonicalOrder().size(); j++) {
				ObligationEndpointFact a = left.endpointsInCanonicalOrder().get(j);
				ObligationEndpointFact b = right.endpointsInCanonicalOrder().get(j);
				if(a.consumerKey() != b.consumerKey() || a.inputPosition() != b.inputPosition()
					|| !a.requiredPlacement().equals(b.requiredPlacement()))
					return false;
			}
		}
		return true;
	}

	private static String fingerprint(PlacementAnalysis analysis, List<CompiledHopKey> scope,
		List<DecisionFact> decisions, List<DirectedEdgeFact> edges,
		List<AuxiliaryGroupFact> groups, List<ObligationFact> obligations) {
		StringBuilder normalized = new StringBuilder(analysis.analysisFingerprint());
		for(CompiledHopKey key : scope) normalized.append("|S:").append(key.normalizedSignature());
		for(DecisionFact decision : decisions) {
			normalized.append("|D:").append(decision.key().normalizedSignature()).append(':')
				.append(decision.computeNodeId()).append(':').append(decision.placementNodeId());
			for(PlacementState state : decision.legalStatesInCanonicalOrder())
				normalized.append(':').append(state.normalizedSignature());
		}
		for(DirectedEdgeFact edge : edges) {
			normalized.append("|E:").append(edge.fromNodeId()).append('>').append(edge.toNodeId())
				.append(':').append(edge.capacityBits());
			for(EdgeContribution contribution : edge.contributionsInDerivationOrder())
				normalized.append(':').append(contribution.kind()).append(':')
					.append(contribution.ownerKey().normalizedSignature()).append(':')
					.append(contribution.peerKeyOrNull() == null ? "-"
						: contribution.peerKeyOrNull().normalizedSignature()).append(':')
					.append(contribution.inputPosition()).append(':').append(contribution.costBits())
					.append(':').append(contribution.provenance());
		}
		for(AuxiliaryGroupFact group : groups) {
			normalized.append("|G:").append(group.auxiliaryNodeId()).append(':')
				.append(group.direction()).append(':').append(group.producerKey().normalizedSignature())
				.append(':').append(group.producerPlacementNodeId()).append(':')
				.append(group.conversionType()).append(':').append(group.priceBits());
			for(EndpointFact endpoint : group.endpointsInCanonicalOrder())
				normalized.append(':').append(endpoint.producerKey().normalizedSignature()).append(':')
					.append(endpoint.inputPosition()).append(':')
					.append(endpoint.consumerKey().normalizedSignature()).append(':')
					.append(endpoint.consumerComputeNodeId()).append(':').append(endpoint.demandCostBits());
		}
		for(ObligationFact obligation : obligations)
			normalized.append("|O:").append(obligation.actionSignature());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(normalized.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for(byte value : digest) hex.append(String.format("%02x", value));
			return hex.toString();
		}
		catch(NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static void fail(ValidationReason reason, String message) {
		throw new ValidationException(reason, message);
	}

	private static final class EdgeAccumulator {
		private final Map<EdgeKey,List<EdgeContribution>> contributions = new LinkedHashMap<>();

		void add(long from, long to, double cost, ContributionKind kind,
			CompiledHopKey owner, CompiledHopKey peer, int inputPosition, String provenance) {
			double canonical = requireCost(cost, "MINST_EDGE_COST_UNPROVEN");
			contributions.computeIfAbsent(new EdgeKey(from, to), ignored -> new ArrayList<>())
				.add(new EdgeContribution(kind, owner, peer, inputPosition, bits(canonical), provenance));
		}

		List<DirectedEdgeFact> freeze() {
			List<DirectedEdgeFact> result = new ArrayList<>(contributions.size());
			for(Map.Entry<EdgeKey,List<EdgeContribution>> entry : contributions.entrySet()) {
				double sum = 0.0;
				for(EdgeContribution contribution : entry.getValue())
					sum += Double.longBitsToDouble(contribution.costBits());
				sum = requireCost(sum, "MINST_EDGE_SUM_UNPROVEN");
				result.add(new DirectedEdgeFact(entry.getKey().from, entry.getKey().to,
					Double.doubleToRawLongBits(sum), entry.getValue()));
			}
			return List.copyOf(result);
		}
	}

	private static final class EdgeKey {
		private final long from;
		private final long to;
		EdgeKey(long from, long to) { this.from = from; this.to = to; }
		@Override public boolean equals(Object other) {
			return other instanceof EdgeKey && from == ((EdgeKey)other).from && to == ((EdgeKey)other).to;
		}
		@Override public int hashCode() { return Long.hashCode(from) * 31 + Long.hashCode(to); }
	}

	private static final class GroupDemandKey {
		private final Direction direction;
		private final FType type;
		GroupDemandKey(Direction direction, FType type) { this.direction = direction; this.type = type; }
		@Override public boolean equals(Object other) {
			return other instanceof GroupDemandKey && direction == ((GroupDemandKey)other).direction
				&& type == ((GroupDemandKey)other).type;
		}
		@Override public int hashCode() { return direction.hashCode() * 31 + type.hashCode(); }
	}

	private static final class Use {
		private final CompiledInputEdgeFact edge;
		private final DecisionFact consumerDecision;
		Use(CompiledInputEdgeFact edge, DecisionFact consumerDecision) {
			this.edge = Objects.requireNonNull(edge, "edge");
			this.consumerDecision = consumerDecision;
		}
	}

	private static final class Derivation {
		private final List<DecisionFact> decisions;
		private final List<DirectedEdgeFact> edges;
		private final List<AuxiliaryGroupFact> groups;
		private final List<ObligationFact> obligations;
		private final String fingerprint;
		Derivation(List<DecisionFact> decisions, List<DirectedEdgeFact> edges,
			List<AuxiliaryGroupFact> groups, List<ObligationFact> obligations, String fingerprint) {
			this.decisions = decisions;
			this.edges = edges;
			this.groups = groups;
			this.obligations = obligations;
			this.fingerprint = fingerprint;
		}
	}
}
