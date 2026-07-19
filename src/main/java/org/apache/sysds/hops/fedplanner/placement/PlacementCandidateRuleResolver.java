/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleLookupException;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.DetachedConsumerProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Pure projection over explicit legacy evidence or exact analysis-owned captured facts. */
public final class PlacementCandidateRuleResolver {
	private static final List<FType> MATRIX_FTYPE_CANDIDATES = List.of(
		FType.ROW, FType.COL, FType.FULL, FType.PART, FType.BROADCAST);

	public record ProfileEvidence(Set<FType> producerCandidates, Set<FType> consumerCandidates,
		boolean consumerConstrained) {
		public ProfileEvidence {
			producerCandidates = immutableSet(producerCandidates, "producerCandidates");
			consumerCandidates = immutableSet(consumerCandidates, "consumerCandidates");
		}
	}

	public record InvocationEvidence(boolean multiReturnBuiltin, boolean matrixOutput, boolean scalarLikeMatrix,
		boolean vectorShape, long rows, long cols, FType fedInitType, boolean transientRead,
		boolean vectorAxisMismatch, boolean rowAxisLengthMismatch, boolean colAxisLengthMismatch,
		FType aggregateSharedAxis, int numWorkers) {
		public InvocationEvidence {
			if(numWorkers < 0)
				throw new IllegalArgumentException("numWorkers must be non-negative");
		}
	}

	/** Explicitly labelled characterization-only request; it cannot impersonate a captured fact. */
	public record LegacyCharacterizationRequest(List<CandidateInputState> orderedInputs,
		CandidateCapabilityFact capability, ProfileEvidence profiles, InvocationEvidence invocation) {
		public LegacyCharacterizationRequest {
			orderedInputs = List.copyOf(Objects.requireNonNull(orderedInputs, "orderedInputs"));
			Objects.requireNonNull(profiles, "profiles");
			Objects.requireNonNull(invocation, "invocation");
		}
	}

	public enum ConsumerNodeKind { NORMAL, TRANSIENT_WRITE, TRANSIENT_READ, TERMINAL_TRANSIENT_WRITE }

	/** Ordered exact edge: consumer reads producer at inputPosition. */
	public record ConsumerEdgeEvidence(int ordinal, CompiledHopKey consumerOccurrence,
		CompiledHopKey producerOccurrence, int inputPosition, ConsumerNodeKind consumerKind) {
		public ConsumerEdgeEvidence {
			if(ordinal < 0 || inputPosition < 0)
				throw new IllegalArgumentException("Consumer edge ordinal and position must be non-negative");
			Objects.requireNonNull(consumerOccurrence, "consumerOccurrence");
			Objects.requireNonNull(producerOccurrence, "producerOccurrence");
			Objects.requireNonNull(consumerKind, "consumerKind");
		}
	}

	public record TransientForwardEvidence(int ordinal, CompiledHopKey writeOccurrence,
		CompiledHopKey readOccurrence) {
		public TransientForwardEvidence {
			if(ordinal < 0)
				throw new IllegalArgumentException("Transient-forward ordinal must be non-negative");
			Objects.requireNonNull(writeOccurrence, "writeOccurrence");
			Objects.requireNonNull(readOccurrence, "readOccurrence");
		}
	}

	public record CapturedInvocationEvidence(InvocationEvidence projection,
		List<ConsumerEdgeEvidence> consumerEdges, List<TransientForwardEvidence> transientForwards) {
		public CapturedInvocationEvidence {
			Objects.requireNonNull(projection, "projection");
			consumerEdges = List.copyOf(Objects.requireNonNull(consumerEdges, "consumerEdges"));
			transientForwards = List.copyOf(Objects.requireNonNull(transientForwards, "transientForwards"));
			for(int i = 0; i < consumerEdges.size(); i++)
				if(consumerEdges.get(i).ordinal() != i)
					throw new CapturedResolutionException(CapturedResolutionFailure.REORDERED_CONSUMER_EDGE,
						"Consumer edge order differs at " + i);
			for(int i = 0; i < transientForwards.size(); i++)
				if(transientForwards.get(i).ordinal() != i)
					throw new CapturedResolutionException(CapturedResolutionFailure.REORDERED_CONSUMER_EDGE,
						"Transient-forward order differs at " + i);
		}
	}

	public record CapturedResolutionRequest(PlacementAnalysis analysis, String analysisFingerprint,
		CompiledHopKey parentOccurrence, List<CandidateInputState> orderedInputs,
		CapturedInvocationEvidence invocation) {
		public CapturedResolutionRequest {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			Objects.requireNonNull(parentOccurrence, "parentOccurrence");
			orderedInputs = List.copyOf(Objects.requireNonNull(orderedInputs, "orderedInputs"));
			Objects.requireNonNull(invocation, "invocation");
		}
	}

	public record CapturedResolution(CandidateRuleFact fact, FType logicalFType,
		List<CandidateConsumerProfileFact> retainedConsumerFacts,
		List<DetachedConsumerProfileFact> retainedDetachedConsumerFacts) {
		public CapturedResolution {
			Objects.requireNonNull(fact, "fact");
			retainedConsumerFacts = List.copyOf(retainedConsumerFacts);
			retainedDetachedConsumerFacts = List.copyOf(retainedDetachedConsumerFacts);
		}
	}

	public enum CapturedResolutionFailure {
		FOREIGN_CONTEXT, STALE_FACT, PARENT_OUTSIDE_CANDIDATE_DOMAIN, MISSING_FACT,
		RULE_EVALUATION_FAILED, PRODUCER_PROFILE_EVALUATION_FAILED, MISSING_CONSUMER_PROFILE,
		CONSUMER_PROFILE_EVALUATION_FAILED, AMBIGUOUS_TRANSIENT_FORWARD, REORDERED_CONSUMER_EDGE
	}

	public static final class CapturedResolutionException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		private final CapturedResolutionFailure failure;
		private CapturedResolutionException(CapturedResolutionFailure failure, String message) {
			super(message);
			this.failure = Objects.requireNonNull(failure, "failure");
		}
		public CapturedResolutionFailure failure() { return failure; }
	}

	private record RetainedConsumer(CompiledHopKey occurrence, int inputPosition) { }

	private PlacementCandidateRuleResolver() { }

	public static List<FType> matrixFTypeCandidates() { return MATRIX_FTYPE_CANDIDATES; }

	public static FType projectLegacyCharacterization(LegacyCharacterizationRequest request) {
		Objects.requireNonNull(request, "request");
		return project(request.orderedInputs(), request.capability(), request.profiles(), request.invocation());
	}

	public static CapturedResolution resolveCaptured(CapturedResolutionRequest request) {
		Objects.requireNonNull(request, "request");
		PlacementAnalysis analysis = request.analysis();
		if(!analysis.analysisFingerprint().equals(request.analysisFingerprint()))
			throw failure(CapturedResolutionFailure.FOREIGN_CONTEXT, request, "Analysis fingerprint differs");
		if(!analysis.candidateRuleDomain().containsExactParent(request.parentOccurrence()))
			throw failure(CapturedResolutionFailure.PARENT_OUTSIDE_CANDIDATE_DOMAIN, request,
				"Parent is foreign, copied, or synthetic");
		CandidateRuleFact fact;
		try {
			fact = analysis.candidateRuleFacts().requireExact(request.parentOccurrence(), request.orderedInputs());
		}
		catch(CandidateRuleLookupException ex) {
			throw failure(ex.failure() == PlacementAnalysis.CandidateLookupFailure.MISSING_FACT
				? CapturedResolutionFailure.MISSING_FACT : CapturedResolutionFailure.STALE_FACT,
				request, ex.getMessage());
		}
		if(fact.status() == CandidateEvaluationStatus.RULE_ERROR || fact.capability() == null)
			throw failure(CapturedResolutionFailure.RULE_EVALUATION_FAILED, request, fact.failureCode());
		if(fact.status() == CandidateEvaluationStatus.PROFILE_ERROR || !fact.profile().available())
			throw failure(CapturedResolutionFailure.PRODUCER_PROFILE_EVALUATION_FAILED, request,
				fact.failureCode());

		List<RetainedConsumer> retained = retainedConsumers(request);
		List<CandidateConsumerProfileFact> consumerFacts = new java.util.ArrayList<>(retained.size());
		Set<FType> intersection = new LinkedHashSet<>(MATRIX_FTYPE_CANDIDATES);
		boolean constrained = false;
		for(RetainedConsumer consumer : retained) {
			CandidateConsumerProfileFact consumerFact;
			try {
				consumerFact = analysis.candidateConsumerProfileFacts().requireExact(
					consumer.occurrence(), consumer.inputPosition());
			}
			catch(CandidateRuleLookupException ex) {
				throw failure(CapturedResolutionFailure.MISSING_CONSUMER_PROFILE, request, ex.getMessage());
			}
			if(consumerFact.status() != CandidateEvaluationStatus.AVAILABLE)
				throw failure(CapturedResolutionFailure.CONSUMER_PROFILE_EVALUATION_FAILED, request,
					consumerFact.failureCode());
			consumerFacts.add(consumerFact);
			if(!consumerFact.allowedTargetTypes().isEmpty()) {
				constrained = true;
				intersection.retainAll(consumerFact.allowedTargetTypes());
			}
		}
		List<DetachedConsumerProfileFact> detachedConsumerFacts =
			analysis.detachedConsumerProfileFacts().requireExactProducer(request.parentOccurrence());
		for(DetachedConsumerProfileFact consumerFact : detachedConsumerFacts) {
			if(consumerFact.status() != CandidateEvaluationStatus.AVAILABLE)
				throw failure(CapturedResolutionFailure.CONSUMER_PROFILE_EVALUATION_FAILED, request,
					consumerFact.failureCode());
			if(!consumerFact.allowedTargetTypes().isEmpty()) {
				constrained = true;
				intersection.retainAll(consumerFact.allowedTargetTypes());
			}
		}
		Set<FType> consumers = constrained ? intersection : Set.of();
		ProfileEvidence profiles = new ProfileEvidence(new LinkedHashSet<>(fact.profile().producerOutputs()),
			consumers, constrained);
		FType logical = project(fact.key().orderedInputs(), fact.capability(), profiles,
			request.invocation().projection());
		return new CapturedResolution(fact, logical, consumerFacts, detachedConsumerFacts);
	}

	public static FType projectConsumerSafeType(FType logicalType, InvocationEvidence invocation) {
		Objects.requireNonNull(invocation, "invocation");
		if(logicalType == null) return null;
		if(invocation.aggregateSharedAxis() != null) return invocation.aggregateSharedAxis();
		if(invocation.scalarLikeMatrix()) return FType.BROADCAST;
		if(logicalType == FType.BROADCAST) return logicalType;
		if(invocation.vectorAxisMismatch() || logicalType == FType.ROW && invocation.rowAxisLengthMismatch()
			|| logicalType == FType.COL && invocation.colAxisLengthMismatch()) return FType.BROADCAST;
		if(invocation.numWorkers() > 1) {
			if(logicalType == FType.ROW && invocation.rows() > 0 && invocation.rows() < invocation.numWorkers())
				return FType.BROADCAST;
			if(logicalType == FType.COL && invocation.cols() > 0 && invocation.cols() < invocation.numWorkers())
				return FType.BROADCAST;
		}
		return logicalType;
	}

	private static List<RetainedConsumer> retainedConsumers(CapturedResolutionRequest request) {
		List<RetainedConsumer> retained = new java.util.ArrayList<>();
		Set<CompiledHopKey> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		collectConsumers(request.parentOccurrence(), request, retained, visited);
		return List.copyOf(retained);
	}

	private static void collectConsumers(CompiledHopKey producer, CapturedResolutionRequest request,
		List<RetainedConsumer> retained, Set<CompiledHopKey> visited) {
		if(!visited.add(producer))
			return;
		for(ConsumerEdgeEvidence edge : request.invocation().consumerEdges()) {
			if(edge.producerOccurrence() != producer) continue;
			if(edge.consumerKind() == ConsumerNodeKind.TERMINAL_TRANSIENT_WRITE) continue;
			if(edge.consumerKind() == ConsumerNodeKind.NORMAL) {
				if(retained.stream().noneMatch(value -> value.occurrence() == edge.consumerOccurrence()
					&& value.inputPosition() == edge.inputPosition()))
					retained.add(new RetainedConsumer(edge.consumerOccurrence(), edge.inputPosition()));
			}
			else if(edge.consumerKind() == ConsumerNodeKind.TRANSIENT_READ)
				collectConsumers(edge.consumerOccurrence(), request, retained, visited);
			else {
				List<TransientForwardEvidence> matches = request.invocation().transientForwards().stream()
					.filter(forward -> forward.writeOccurrence() == edge.consumerOccurrence()).toList();
				if(matches.isEmpty())
					throw failure(CapturedResolutionFailure.AMBIGUOUS_TRANSIENT_FORWARD, request,
						"Transient write requires at least one exact read forward");
				for(TransientForwardEvidence forward : matches)
					collectConsumers(forward.readOccurrence(), request, retained, visited);
			}
		}
	}

	private static FType project(List<CandidateInputState> orderedInputs, CandidateCapabilityFact capability,
		ProfileEvidence profiles, InvocationEvidence invocation) {
		FType logicalType = capability == null ? null : capability.nativeFoutFType();
		if(logicalType == null)
			logicalType = projectProfileType(orderedInputs, profiles, invocation);
		if(invocation.scalarLikeMatrix()) logicalType = FType.BROADCAST;
		if(invocation.vectorShape() && !hasFederatedInput(orderedInputs)) logicalType = FType.BROADCAST;
		logicalType = preferConcreteTransientReadSource(orderedInputs, invocation.transientRead(), logicalType);
		if(logicalType == FType.ROW && invocation.rowAxisLengthMismatch()
			|| logicalType == FType.COL && invocation.colAxisLengthMismatch()) logicalType = FType.BROADCAST;
		return logicalType;
	}

	private static FType projectProfileType(List<CandidateInputState> orderedInputs, ProfileEvidence profiles,
		InvocationEvidence invocation) {
		if(invocation.multiReturnBuiltin() || !invocation.matrixOutput()) return null;
		boolean preferBroadcast = invocation.vectorShape() && hasFederatedInput(orderedInputs)
			&& invocation.fedInitType() == null;
		Set<FType> merged = mergeCandidates(profiles);
		if(!merged.isEmpty()) {
			if(preferBroadcast && merged.contains(FType.BROADCAST)) return FType.BROADCAST;
			return pickPreferredAxis(merged, invocation.rows(), invocation.cols());
		}
		if(invocation.fedInitType() != null) return invocation.fedInitType();
		Set<FType> inputCandidates = new LinkedHashSet<>();
		for(CandidateInputState input : orderedInputs)
			if(input.present()) inputCandidates.add(input.fType());
		if(preferBroadcast && inputCandidates.contains(FType.BROADCAST)) return FType.BROADCAST;
		return pickPreferredAxis(inputCandidates, invocation.rows(), invocation.cols());
	}

	private static Set<FType> mergeCandidates(ProfileEvidence profiles) {
		if(profiles.consumerConstrained()) {
			if(profiles.consumerCandidates().isEmpty()) return Set.of();
			if(profiles.producerCandidates().isEmpty()) return new LinkedHashSet<>(profiles.consumerCandidates());
			Set<FType> merged = new LinkedHashSet<>(profiles.producerCandidates());
			merged.retainAll(profiles.consumerCandidates());
			return merged;
		}
		return new LinkedHashSet<>(profiles.producerCandidates());
	}

	private static FType preferConcreteTransientReadSource(List<CandidateInputState> inputs,
		boolean transientRead, FType logicalType) {
		if(logicalType != FType.BROADCAST || !transientRead || inputs.isEmpty()) return logicalType;
		FType concrete = null;
		for(CandidateInputState input : inputs) {
			if(!input.present()) continue;
			if(input.fType() == FType.BROADCAST) return logicalType;
			if(concrete == null) concrete = input.fType();
			else if(concrete != input.fType()) return logicalType;
		}
		return concrete == null ? logicalType : concrete;
	}

	private static boolean hasFederatedInput(List<CandidateInputState> inputs) {
		for(CandidateInputState input : inputs)
			if(input.present() && (input.fType() == FType.ROW || input.fType() == FType.COL
				|| input.fType() == FType.PART || input.fType() == FType.FULL
				|| input.fType() == FType.BROADCAST)) return true;
		return false;
	}

	private static FType pickPreferredAxis(Set<FType> candidates, long rows, long cols) {
		if(candidates.isEmpty()) return null;
		boolean hasRow = candidates.contains(FType.ROW), hasCol = candidates.contains(FType.COL);
		if(hasRow || hasCol) {
			if(rows == 1 && hasCol) return FType.COL;
			if(cols == 1 && hasRow) return FType.ROW;
			return hasRow ? FType.ROW : FType.COL;
		}
		if(candidates.contains(FType.FULL)) return FType.FULL;
		if(candidates.contains(FType.PART)) return FType.PART;
		return candidates.contains(FType.BROADCAST) ? FType.BROADCAST : null;
	}

	private static Set<FType> immutableSet(Set<FType> source, String name) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(source, name)));
	}

	private static CapturedResolutionException failure(CapturedResolutionFailure failure,
		CapturedResolutionRequest request, String detail) {
		return new CapturedResolutionException(failure, failure + "|" + request.analysisFingerprint() + "|"
			+ request.parentOccurrence() + "|" + (detail == null ? "" : detail));
	}
}
