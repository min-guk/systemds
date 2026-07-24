/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.List;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedInvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Immutable, owner-bound input to the exact MinST selector. */
public final class MinStExactCostFacts {
	public enum Direction { UPLOAD, DOWNLOAD }
	public enum MembershipAuthorityKind {
		LEGAL_SINGLETON, DURABLE_ANCHOR, CAPTURED_RULE, RELOCATION_SOURCE
	}
	public enum TransferAuthorityKind {
		RELOCATION_OBLIGATION, INDEPENDENT_ANCHOR, DURABLE_SOURCE,
		SELECTED_SOURCE_LOCAL_MATERIALIZATION
	}
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

	/** Exact input-level authority binding a retained rule input to its already-derived producer proof. */
	public static final class MembershipInputAuthorityFact {
		private final CompiledInputEdgeFact inputEdge;
		private final int inputPosition;
		private final MembershipRepresentative producerRepresentative;
		private final String authoritySignature;

		MembershipInputAuthorityFact(CompiledInputEdgeFact inputEdge, int inputPosition,
			MembershipRepresentative producerRepresentative, String authoritySignature) {
			this.inputEdge = Objects.requireNonNull(inputEdge, "inputEdge");
			this.inputPosition = inputPosition;
			this.producerRepresentative = Objects.requireNonNull(producerRepresentative,
				"producerRepresentative");
			this.authoritySignature = Objects.requireNonNull(authoritySignature, "authoritySignature");
			if(inputPosition != inputEdge.inputPosition()
				|| producerRepresentative.decisionKey() != inputEdge.producer()
				|| producerRepresentative.execType() != ExecType.FED
				|| producerRepresentative.output() != FederatedOutput.FOUT
				|| producerRepresentative.state().fType() == null
				|| producerRepresentative.state().fType() == FType.OTHER
				|| producerRepresentative.state().fType() == FType.PART
				|| !(producerRepresentative.authorityKind() == MembershipAuthorityKind.CAPTURED_RULE
					|| producerRepresentative.authorityKind() == MembershipAuthorityKind.RELOCATION_SOURCE
					|| producerRepresentative.authorityKind() == MembershipAuthorityKind.DURABLE_ANCHOR)
				|| authoritySignature.isBlank())
				throw new IllegalArgumentException(
					"MINST_EXACT_MEMBERSHIP_INPUT_AUTHORITY_MISMATCH");
		}

		public CompiledInputEdgeFact inputEdge() { return inputEdge; }
		public int inputPosition() { return inputPosition; }
		public MembershipRepresentative producerRepresentative() { return producerRepresentative; }
		public String authoritySignature() { return authoritySignature; }
	}

	/** Exact pre-solve authority for one cut membership and its retained concrete state. */
	public static final class MembershipRepresentative {
		private final CompiledHopKey decisionKey;
		private final ExecType execType;
		private final FederatedOutput output;
		private final PlacementState state;
		private final MembershipAuthorityKind authorityKind;
		private final DurableAnchorKey durableAnchor;
		private final CandidateRuleFact candidateRuleFact;
		private final List<CandidateInputState> orderedInputs;
		private final List<MembershipInputAuthorityFact> inputAuthorityFacts;
		private final CapturedInvocationEvidence invocationEvidence;
		private final RelocationAction relocationAction;
		private final String authoritySignature;

		MembershipRepresentative(CompiledHopKey decisionKey, ExecType execType,
			FederatedOutput output, PlacementState state, MembershipAuthorityKind authorityKind,
			DurableAnchorKey durableAnchorOrNull, CandidateRuleFact candidateRuleFactOrNull,
			List<CandidateInputState> orderedInputs, List<MembershipInputAuthorityFact> inputAuthorityFacts,
			CapturedInvocationEvidence invocationEvidenceOrNull,
			RelocationAction relocationActionOrNull, String authoritySignatureOrNull) {
			this.decisionKey = Objects.requireNonNull(decisionKey, "decisionKey");
			this.execType = Objects.requireNonNull(execType, "execType");
			this.output = Objects.requireNonNull(output, "output");
			this.state = Objects.requireNonNull(state, "state");
			this.authorityKind = Objects.requireNonNull(authorityKind, "authorityKind");
			this.durableAnchor = durableAnchorOrNull;
			this.candidateRuleFact = candidateRuleFactOrNull;
			this.orderedInputs = List.copyOf(Objects.requireNonNull(orderedInputs, "orderedInputs"));
			this.inputAuthorityFacts = List.copyOf(Objects.requireNonNull(inputAuthorityFacts, "inputAuthorityFacts"));
			this.invocationEvidence = invocationEvidenceOrNull;
			this.relocationAction = relocationActionOrNull;
			this.authoritySignature = authoritySignatureOrNull;
			if(state.execType() != execType || state.output() != output)
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_STATE_MISMATCH");
			for(int i = 0; i < this.inputAuthorityFacts.size(); i++)
				for(int j = i + 1; j < this.inputAuthorityFacts.size(); j++)
					if(this.inputAuthorityFacts.get(i).inputEdge() == this.inputAuthorityFacts.get(j).inputEdge()
						|| this.inputAuthorityFacts.get(i).inputPosition()
							== this.inputAuthorityFacts.get(j).inputPosition())
						throw new IllegalArgumentException(
							"MINST_EXACT_MEMBERSHIP_INPUT_AUTHORITY_DUPLICATE");
			if(output == FederatedOutput.FOUT && (state.fType() == null
				|| state.fType() == FType.OTHER || state.fType() == FType.PART))
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_FTYPE_NONCONCRETE");
			if(authorityKind == MembershipAuthorityKind.LEGAL_SINGLETON) {
				if(durableAnchor != null || candidateRuleFact != null || invocationEvidence != null
					|| relocationAction != null || authoritySignature != null
					|| !this.orderedInputs.isEmpty() || !this.inputAuthorityFacts.isEmpty())
					throw new IllegalArgumentException("MINST_EXACT_SINGLETON_AUTHORITY_MIXED");
			}
			else if(authorityKind == MembershipAuthorityKind.DURABLE_ANCHOR) {
				if(durableAnchor == null || candidateRuleFact != null || invocationEvidence != null
					|| relocationAction != null || authoritySignature != null
					|| !this.orderedInputs.isEmpty() || !this.inputAuthorityFacts.isEmpty()
					|| state.fType() != durableAnchor.fType())
					throw new IllegalArgumentException("MINST_EXACT_ANCHOR_AUTHORITY_MISMATCH");
			}
			else if(authorityKind == MembershipAuthorityKind.CAPTURED_RULE) {
				if(candidateRuleFact == null || invocationEvidence == null || durableAnchor != null
					|| relocationAction != null || authoritySignature != null
					|| candidateRuleFact.key().parentOccurrence() != decisionKey
					|| !candidateRuleFact.key().orderedInputs().equals(this.orderedInputs)
					|| this.inputAuthorityFacts.stream().anyMatch(fact -> fact.inputEdge().consumer() != decisionKey))
					throw new IllegalArgumentException("MINST_EXACT_RULE_AUTHORITY_MISMATCH");
			}
			else if(authorityKind == MembershipAuthorityKind.RELOCATION_SOURCE) {
				if(candidateRuleFact != null || invocationEvidence != null || durableAnchor == null
					|| relocationAction == null || authoritySignature != null || !this.orderedInputs.isEmpty()
					|| !this.inputAuthorityFacts.isEmpty()
					|| relocationAction.key().durableAnchor() != durableAnchor
					|| !relocationAction.key().targetPlacement().equals(state)
					|| state.fType() != durableAnchor.fType())
					throw new IllegalArgumentException("MINST_EXACT_RELOCATION_SOURCE_AUTHORITY_MISMATCH");
			}
			else
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_AUTHORITY_UNKNOWN");
		}

		public CompiledHopKey decisionKey() { return decisionKey; }
		public ExecType execType() { return execType; }
		public FederatedOutput output() { return output; }
		public PlacementState state() { return state; }
		public MembershipAuthorityKind authorityKind() { return authorityKind; }
		public DurableAnchorKey durableAnchorOrNull() { return durableAnchor; }
		public CandidateRuleFact candidateRuleFactOrNull() { return candidateRuleFact; }
		public List<CandidateInputState> orderedInputs() { return orderedInputs; }
		public List<MembershipInputAuthorityFact> inputAuthorityFacts() { return inputAuthorityFacts; }
		public CapturedInvocationEvidence invocationEvidenceOrNull() { return invocationEvidence; }
		public RelocationAction relocationActionOrNull() { return relocationAction; }
		public String authoritySignatureOrNull() { return authoritySignature; }
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

	/** Exact MinST-local authority for one auxiliary transfer endpoint. */
	public static final class TransferAuthorityFact {
		private final AuxiliaryGroupFact group;
		private final EndpointFact endpoint;
		private final CompiledInputEdgeFact inputEdge;
		private final ValueVersionKey sourceValueVersion;
		private final TransferAuthorityKind authorityKind;
		private final PlacementState requiredPlacement;
		private final String authoritySignature;
		private final RelocationAction action;
		private final ObligationKey obligation;
		private final CompiledInputEdgeFact anchorInputEdge;
		private final DurableAnchorKey independentAnchor;
		private final CandidateConsumerProfileFact consumerProfile;
		private final String producerMembershipProof;

		private TransferAuthorityFact(AuxiliaryGroupFact group, EndpointFact endpoint,
			CompiledInputEdgeFact inputEdge, ValueVersionKey sourceValueVersion,
			TransferAuthorityKind authorityKind,
			PlacementState requiredPlacement, String authoritySignature,
			RelocationAction actionOrNull, ObligationKey obligationOrNull,
			CompiledInputEdgeFact anchorInputEdgeOrNull, DurableAnchorKey independentAnchorOrNull,
			CandidateConsumerProfileFact consumerProfileOrNull, String producerMembershipProofOrNull) {
			this.group = Objects.requireNonNull(group, "group");
			this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
			this.inputEdge = Objects.requireNonNull(inputEdge, "inputEdge");
			this.sourceValueVersion = Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			this.authorityKind = Objects.requireNonNull(authorityKind, "authorityKind");
			this.requiredPlacement = Objects.requireNonNull(requiredPlacement, "requiredPlacement");
			this.authoritySignature = Objects.requireNonNull(authoritySignature, "authoritySignature");
			this.action = actionOrNull;
			this.obligation = obligationOrNull;
			this.anchorInputEdge = anchorInputEdgeOrNull;
			this.independentAnchor = independentAnchorOrNull;
			this.consumerProfile = consumerProfileOrNull;
			this.producerMembershipProof = producerMembershipProofOrNull;
			if(group.endpointsInCanonicalOrder().stream().noneMatch(candidate -> candidate == endpoint)
				|| endpoint.producerKey() != group.producerKey()
				|| inputEdge.producer() != endpoint.producerKey()
				|| inputEdge.consumer() != endpoint.consumerKey()
				|| inputEdge.inputPosition() != endpoint.inputPosition()
				|| requiredPlacement.fType() != group.conversionType())
				throw new IllegalArgumentException("MINST_EXACT_TRANSFER_AUTHORITY_MISMATCH");
			if(authorityKind == TransferAuthorityKind.RELOCATION_OBLIGATION) {
				if(action == null || obligation == null || anchorInputEdge != null
					|| independentAnchor != null || consumerProfile != null || producerMembershipProof != null
					|| action.obligations().stream().noneMatch(candidate -> candidate == obligation)
					|| obligation.consumer() != endpoint.consumerKey()
					|| obligation.inputPosition() != endpoint.inputPosition()
					|| obligation.relocationAction() != action.key()
					|| obligation.sourceValueVersion() != sourceValueVersion
					|| action.key().sourceValueVersion() != sourceValueVersion
					|| obligation.requiredPlacement() != action.key().targetPlacement()
					|| requiredPlacement != obligation.requiredPlacement()
					|| !authoritySignature.equals(action.normalizedSignature()))
					throw new IllegalArgumentException("MINST_EXACT_RELOCATION_AUTHORITY_MISMATCH");
			}
			else if(authorityKind == TransferAuthorityKind.INDEPENDENT_ANCHOR) {
				if(group.direction() != Direction.UPLOAD || action != null || obligation != null
					|| anchorInputEdge == null || independentAnchor == null || consumerProfile == null
					|| producerMembershipProof != null
					|| anchorInputEdge.consumer() != endpoint.consumerKey()
					|| anchorInputEdge.inputPosition() == endpoint.inputPosition()
					|| independentAnchor.fType() != group.conversionType()
					|| consumerProfile.key().consumerOccurrence() != endpoint.consumerKey()
					|| consumerProfile.key().inputPosition() != endpoint.inputPosition()
					|| consumerProfile.status() != PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE
					|| !consumerProfile.allowedTargetTypes().isEmpty()
						&& !consumerProfile.allowedTargetTypes().contains(group.conversionType()))
					throw new IllegalArgumentException("MINST_EXACT_INDEPENDENT_ANCHOR_AUTHORITY_MISMATCH");
			}
			else if(authorityKind == TransferAuthorityKind.DURABLE_SOURCE) {
				if(group.direction() != Direction.DOWNLOAD || action != null || obligation != null
					|| anchorInputEdge != null || independentAnchor == null || consumerProfile != null
					|| producerMembershipProof != null
					|| independentAnchor.fType() != group.conversionType())
					throw new IllegalArgumentException("MINST_EXACT_DURABLE_SOURCE_AUTHORITY_MISMATCH");
			}
			else if(group.direction() != Direction.DOWNLOAD || action != null || obligation != null
				|| anchorInputEdge != null || independentAnchor != null || consumerProfile != null
				|| producerMembershipProof == null || producerMembershipProof.isBlank()
				|| requiredPlacement.execType() != ExecType.FED
				|| requiredPlacement.output() != FederatedOutput.FOUT)
				throw new IllegalArgumentException(
					"MINST_EXACT_SELECTED_LOCAL_MATERIALIZATION_AUTHORITY_MISMATCH");
		}

		static TransferAuthorityFact relocation(AuxiliaryGroupFact group, EndpointFact endpoint,
			CompiledInputEdgeFact inputEdge, ValueVersionKey sourceValueVersion,
			RelocationAction action, ObligationKey obligation) {
			return new TransferAuthorityFact(group, endpoint, inputEdge, sourceValueVersion,
				TransferAuthorityKind.RELOCATION_OBLIGATION, obligation.requiredPlacement(),
				action.normalizedSignature(), action, obligation, null, null, null, null);
		}

		static TransferAuthorityFact independentAnchor(AuxiliaryGroupFact group, EndpointFact endpoint,
			CompiledInputEdgeFact inputEdge, ValueVersionKey sourceValueVersion,
			CompiledInputEdgeFact anchorInputEdge,
			DurableAnchorKey anchor, CandidateConsumerProfileFact profile,
			PlacementState requiredPlacement, String authoritySignature) {
			return new TransferAuthorityFact(group, endpoint, inputEdge, sourceValueVersion,
				TransferAuthorityKind.INDEPENDENT_ANCHOR, requiredPlacement, authoritySignature,
				null, null, anchorInputEdge, anchor, profile, null);
		}

		static TransferAuthorityFact durableSource(AuxiliaryGroupFact group, EndpointFact endpoint,
			CompiledInputEdgeFact inputEdge, ValueVersionKey sourceValueVersion,
			DurableAnchorKey anchor, PlacementState requiredPlacement, String authoritySignature) {
			return new TransferAuthorityFact(group, endpoint, inputEdge, sourceValueVersion,
				TransferAuthorityKind.DURABLE_SOURCE, requiredPlacement, authoritySignature,
				null, null, null, anchor, null, null);
		}

		static TransferAuthorityFact selectedSourceLocalMaterialization(AuxiliaryGroupFact group,
			EndpointFact endpoint, CompiledInputEdgeFact inputEdge, ValueVersionKey sourceValueVersion,
			PlacementState requiredPlacement, String authoritySignature, String producerMembershipProof) {
			return new TransferAuthorityFact(group, endpoint, inputEdge, sourceValueVersion,
				TransferAuthorityKind.SELECTED_SOURCE_LOCAL_MATERIALIZATION, requiredPlacement,
				authoritySignature, null, null, null, null, null, producerMembershipProof);
		}

		public AuxiliaryGroupFact group() { return group; }
		public EndpointFact endpoint() { return endpoint; }
		public CompiledInputEdgeFact inputEdge() { return inputEdge; }
		public ValueVersionKey sourceValueVersion() { return sourceValueVersion; }
		public TransferAuthorityKind authorityKind() { return authorityKind; }
		public RelocationAction actionOrNull() { return action; }
		public ObligationKey obligationOrNull() { return obligation; }
		public CompiledInputEdgeFact anchorInputEdgeOrNull() { return anchorInputEdge; }
		public DurableAnchorKey independentAnchorOrNull() { return independentAnchor; }
		public CandidateConsumerProfileFact consumerProfileOrNull() { return consumerProfile; }
		public String producerMembershipProofOrNull() { return producerMembershipProof; }
		public Direction direction() { return group.direction(); }
		public PlacementState requiredPlacement() { return requiredPlacement; }
		public String authoritySignature() { return authoritySignature; }
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
	private final List<MembershipRepresentative> membershipRepresentatives;
	private final List<DirectedEdgeFact> edges;
	private final List<AuxiliaryGroupFact> groups;
	private final List<TransferAuthorityFact> transferAuthorities;
	private final List<ObligationFact> obligations;
	private final String derivationFingerprint;

	MinStExactCostFacts(PlacementAnalysis analysis, String analysisFingerprint,
		List<CompiledHopKey> orderedScope, List<DecisionFact> decisionFactsInScopeOrder,
		List<DirectedEdgeFact> directedEdgesInDerivationOrder,
		List<AuxiliaryGroupFact> auxiliaryGroupsInCanonicalOrder,
		List<TransferAuthorityFact> transferAuthoritiesInCanonicalOrder,
		List<ObligationFact> obligationFactsInCanonicalOrder, String derivationFingerprint) {
		MinStExactCostFactsProducer.validate(analysis, analysisFingerprint, orderedScope,
			decisionFactsInScopeOrder, directedEdgesInDerivationOrder,
			auxiliaryGroupsInCanonicalOrder, transferAuthoritiesInCanonicalOrder,
			obligationFactsInCanonicalOrder,
			derivationFingerprint);
		this.analysis = analysis;
		this.analysisFingerprint = analysisFingerprint;
		this.orderedScope = List.copyOf(orderedScope);
		this.decisions = List.copyOf(decisionFactsInScopeOrder);
		this.membershipRepresentatives = MinStExactCostFactsProducer.membershipRepresentatives(
			analysis, this.decisions);
		this.edges = List.copyOf(directedEdgesInDerivationOrder);
		this.groups = List.copyOf(auxiliaryGroupsInCanonicalOrder);
		this.transferAuthorities = List.copyOf(transferAuthoritiesInCanonicalOrder);
		this.obligations = List.copyOf(obligationFactsInCanonicalOrder);
		this.derivationFingerprint = derivationFingerprint;
	}

	public PlacementAnalysis analysis() { return analysis; }
	public String analysisFingerprint() { return analysisFingerprint; }
	public List<CompiledHopKey> orderedScope() { return orderedScope; }
	public long sourceNodeId() { return -1L; }
	public long sinkNodeId() { return -2L; }
	public List<DecisionFact> decisionFactsInScopeOrder() { return decisions; }
	public List<MembershipRepresentative> membershipRepresentativesInCanonicalOrder() {
		return Objects.requireNonNull(membershipRepresentatives,
			"MINST_EXACT_MEMBERSHIP_AUTHORITY_NOT_PUBLISHED");
	}
	public List<DirectedEdgeFact> directedEdgesInDerivationOrder() { return edges; }
	public List<AuxiliaryGroupFact> auxiliaryGroupsInCanonicalOrder() { return groups; }
	public List<TransferAuthorityFact> transferAuthoritiesInCanonicalOrder() {
		return transferAuthorities;
	}
	public List<ObligationFact> obligationFactsInCanonicalOrder() { return obligations; }
	public String derivationFingerprint() { return derivationFingerprint; }
}
