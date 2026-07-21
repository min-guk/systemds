/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Immutable, owner-bound input to the exact MinST selector. */
public final class MinStExactCostFacts {
	public enum Direction { UPLOAD, DOWNLOAD }
	public enum ContributionKind {
		CP_UNARY, FED_UNARY, UPLOAD, DOWNLOAD, HARD_EXEC, HARD_OUTPUT,
		HARD_UPLOAD_OR, HARD_DOWNLOAD_OR, PRICE_UPLOAD_OR, PRICE_DOWNLOAD_OR
	}
	public enum ValidationReason {
		FOREIGN_OWNER,
		SCOPE_REORDERED,
		SCOPE_DUPLICATE,
		SCOPE_FOREIGN,
		CAPACITY_SUM_MISMATCH,
		DERIVATION_FINGERPRINT_MISMATCH,
		OR_GROUP_ENDPOINT_MISMATCH,
		OR_GROUP_DIRECTION_MISMATCH,
		OR_GROUP_PRICE_MISMATCH,
		RAW_STATE_RECEIPT_MISMATCH
	}

	public static final class ValidationException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		private final ValidationReason reason;

		ValidationException(ValidationReason reason, String message) {
			super(message);
			this.reason = Objects.requireNonNull(reason, "reason");
		}

		public ValidationReason reason() { return reason; }
	}

	public static final class DecisionFact {
		private final CompiledHopKey key;
		private final long computeNodeId;
		private final long placementNodeId;
		private final List<PlacementState> legalStates;

		DecisionFact(CompiledHopKey key, long computeNodeId, long placementNodeId,
			List<PlacementState> legalStatesInCanonicalOrder) {
			this.key = Objects.requireNonNull(key, "key");
			this.computeNodeId = computeNodeId;
			this.placementNodeId = placementNodeId;
			this.legalStates = List.copyOf(legalStatesInCanonicalOrder);
		}

		public CompiledHopKey key() { return key; }
		public long computeNodeId() { return computeNodeId; }
		public long placementNodeId() { return placementNodeId; }
		public List<PlacementState> legalStatesInCanonicalOrder() { return legalStates; }
	}

	public static final class EdgeContribution {
		private final ContributionKind kind;
		private final CompiledHopKey ownerKey;
		private final CompiledHopKey peerKey;
		private final int inputPosition;
		private final long costBits;
		private final String provenance;

		EdgeContribution(ContributionKind kind, CompiledHopKey ownerKey,
			CompiledHopKey peerKeyOrNull, int inputPosition, long costBits, String provenance) {
			this.kind = Objects.requireNonNull(kind, "kind");
			this.ownerKey = Objects.requireNonNull(ownerKey, "ownerKey");
			this.peerKey = peerKeyOrNull;
			this.inputPosition = inputPosition;
			this.costBits = costBits;
			this.provenance = Objects.requireNonNull(provenance, "provenance");
		}

		public ContributionKind kind() { return kind; }
		public CompiledHopKey ownerKey() { return ownerKey; }
		public CompiledHopKey peerKeyOrNull() { return peerKey; }
		public int inputPosition() { return inputPosition; }
		public long costBits() { return costBits; }
		public String provenance() { return provenance; }
	}

	public static final class DirectedEdgeFact {
		private final long fromNodeId;
		private final long toNodeId;
		private final long capacityBits;
		private final List<EdgeContribution> contributions;

		DirectedEdgeFact(long fromNodeId, long toNodeId, long capacityBits,
			List<EdgeContribution> contributionsInDerivationOrder) {
			this.fromNodeId = fromNodeId;
			this.toNodeId = toNodeId;
			this.capacityBits = capacityBits;
			this.contributions = List.copyOf(contributionsInDerivationOrder);
		}

		public long fromNodeId() { return fromNodeId; }
		public long toNodeId() { return toNodeId; }
		public long capacityBits() { return capacityBits; }
		public List<EdgeContribution> contributionsInDerivationOrder() { return contributions; }
	}

	public static final class EndpointFact {
		private final CompiledHopKey producerKey;
		private final int inputPosition;
		private final CompiledHopKey consumerKey;
		private final long consumerComputeNodeId;
		private final long demandCostBits;

		EndpointFact(CompiledHopKey producerKey, CompiledHopKey consumerKey, int inputPosition,
			long consumerComputeNodeId, long demandCostBits) {
			this.producerKey = Objects.requireNonNull(producerKey, "producerKey");
			this.inputPosition = inputPosition;
			this.consumerKey = Objects.requireNonNull(consumerKey, "consumerKey");
			this.consumerComputeNodeId = consumerComputeNodeId;
			this.demandCostBits = demandCostBits;
		}

		public CompiledHopKey producerKey() { return producerKey; }
		public int inputPosition() { return inputPosition; }
		public CompiledHopKey consumerKey() { return consumerKey; }
		public long consumerComputeNodeId() { return consumerComputeNodeId; }
		public long demandCostBits() { return demandCostBits; }
	}

	public static final class AuxiliaryGroupFact {
		private final long auxiliaryNodeId;
		private final Direction direction;
		private final CompiledHopKey producerKey;
		private final long producerPlacementNodeId;
		private final FType conversionType;
		private final long priceBits;
		private final List<EndpointFact> endpoints;

		AuxiliaryGroupFact(long auxiliaryNodeId, Direction direction, CompiledHopKey producerKey,
			long producerPlacementNodeId, FType conversionType, long priceBits,
			List<EndpointFact> endpointsInCanonicalOrder) {
			this.auxiliaryNodeId = auxiliaryNodeId;
			this.direction = Objects.requireNonNull(direction, "direction");
			this.producerKey = Objects.requireNonNull(producerKey, "producerKey");
			this.producerPlacementNodeId = producerPlacementNodeId;
			this.conversionType = Objects.requireNonNull(conversionType, "conversionType");
			this.priceBits = priceBits;
			this.endpoints = List.copyOf(endpointsInCanonicalOrder);
		}

		public long auxiliaryNodeId() { return auxiliaryNodeId; }
		public Direction direction() { return direction; }
		public CompiledHopKey producerKey() { return producerKey; }
		public long producerPlacementNodeId() { return producerPlacementNodeId; }
		public FType conversionType() { return conversionType; }
		public long priceBits() { return priceBits; }
		public List<EndpointFact> endpointsInCanonicalOrder() { return endpoints; }
	}

	public static final class ObligationEndpointFact {
		private final CompiledHopKey consumerKey;
		private final int inputPosition;
		private final PlacementState requiredPlacement;

		ObligationEndpointFact(CompiledHopKey consumerKey, int inputPosition,
			PlacementState requiredPlacement) {
			this.consumerKey = Objects.requireNonNull(consumerKey, "consumerKey");
			this.inputPosition = inputPosition;
			this.requiredPlacement = Objects.requireNonNull(requiredPlacement, "requiredPlacement");
		}

		public CompiledHopKey consumerKey() { return consumerKey; }
		public int inputPosition() { return inputPosition; }
		public PlacementState requiredPlacement() { return requiredPlacement; }
	}

	public static final class ObligationFact {
		private final String actionSignature;
		private final List<ObligationEndpointFact> endpoints;

		ObligationFact(String actionSignature,
			List<ObligationEndpointFact> endpointsInCanonicalOrder) {
			this.actionSignature = Objects.requireNonNull(actionSignature, "actionSignature");
			this.endpoints = List.copyOf(endpointsInCanonicalOrder);
		}

		public String actionSignature() { return actionSignature; }
		public List<ObligationEndpointFact> endpointsInCanonicalOrder() { return endpoints; }
	}

	private final PlacementAnalysis analysis;
	private final String analysisFingerprint;
	private final List<CompiledHopKey> orderedScope;
	private final List<DecisionFact> decisions;
	private final List<DirectedEdgeFact> edges;
	private final List<AuxiliaryGroupFact> groups;
	private final List<ObligationFact> obligations;
	private final String derivationFingerprint;

	MinStExactCostFacts(PlacementAnalysis analysis, String analysisFingerprint,
		List<CompiledHopKey> orderedScope, List<DecisionFact> decisionFactsInScopeOrder,
		List<DirectedEdgeFact> directedEdgesInDerivationOrder,
		List<AuxiliaryGroupFact> auxiliaryGroupsInCanonicalOrder,
		List<ObligationFact> obligationFactsInCanonicalOrder, String derivationFingerprint) {
		MinStExactCostFactsProducer.validate(analysis, analysisFingerprint, orderedScope,
			decisionFactsInScopeOrder, directedEdgesInDerivationOrder,
			auxiliaryGroupsInCanonicalOrder, obligationFactsInCanonicalOrder,
			derivationFingerprint);
		this.analysis = analysis;
		this.analysisFingerprint = analysisFingerprint;
		this.orderedScope = List.copyOf(orderedScope);
		this.decisions = List.copyOf(decisionFactsInScopeOrder);
		this.edges = List.copyOf(directedEdgesInDerivationOrder);
		this.groups = List.copyOf(auxiliaryGroupsInCanonicalOrder);
		this.obligations = List.copyOf(obligationFactsInCanonicalOrder);
		this.derivationFingerprint = derivationFingerprint;
	}

	public PlacementAnalysis analysis() { return analysis; }
	public String analysisFingerprint() { return analysisFingerprint; }
	public List<CompiledHopKey> orderedScope() { return orderedScope; }
	public long sourceNodeId() { return -1L; }
	public long sinkNodeId() { return -2L; }
	public List<DecisionFact> decisionFactsInScopeOrder() { return decisions; }
	public List<DirectedEdgeFact> directedEdgesInDerivationOrder() { return edges; }
	public List<AuxiliaryGroupFact> auxiliaryGroupsInCanonicalOrder() { return groups; }
	public List<ObligationFact> obligationFactsInCanonicalOrder() { return obligations; }
	public String derivationFingerprint() { return derivationFingerprint; }
}
