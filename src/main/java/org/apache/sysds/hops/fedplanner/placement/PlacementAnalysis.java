/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Immutable result of constructing one neutral placement universe for a compiled program. */
public final class PlacementAnalysis {
	public enum InputPresence { ABSENT_LOCAL, PRESENT }

	/** Neutral explicit absence/value state; present-null cannot be represented. */
	public record CandidateInputState(InputPresence presence, FType fType) {
		public CandidateInputState {
			Objects.requireNonNull(presence, "presence");
			if(presence == InputPresence.ABSENT_LOCAL && fType != null
				|| presence == InputPresence.PRESENT && fType == null)
				throw new IllegalArgumentException("Candidate input presence and FType differ");
		}
		public static CandidateInputState absentLocal() {
			return new CandidateInputState(InputPresence.ABSENT_LOCAL, null);
		}
		public static CandidateInputState present(FType fType) {
			return new CandidateInputState(InputPresence.PRESENT, Objects.requireNonNull(fType, "fType"));
		}
		public boolean present() { return presence == InputPresence.PRESENT; }
	}

	/** Exact analysis-owned parent plus edge-position-ordered candidate input states. */
	public record CandidateRuleKey(CompiledHopKey parentOccurrence,
		List<CandidateInputState> orderedInputs) {
		public CandidateRuleKey {
			Objects.requireNonNull(parentOccurrence, "parentOccurrence");
			Objects.requireNonNull(orderedInputs, "orderedInputs");
			for(int i = 0; i < orderedInputs.size(); i++)
				Objects.requireNonNull(orderedInputs.get(i), "orderedInputs[" + i + "]");
			orderedInputs = List.copyOf(orderedInputs);
		}
	}

	public record CandidateConsumerProfileKey(CompiledHopKey consumerOccurrence, int inputPosition) {
		public CandidateConsumerProfileKey {
			Objects.requireNonNull(consumerOccurrence, "consumerOccurrence");
			if(inputPosition < 0)
				throw new IllegalArgumentException("Consumer input position must be non-negative");
		}
	}

	/** Explicit immutable original-occurrence candidate domain, frozen before synthetic boundary expansion. */
	public static final class CandidateRuleDomain {
		private final String analysisFingerprint;
		private final List<CandidateRuleKey> orderedRuleKeys;
		private final List<CandidateConsumerProfileKey> orderedConsumerKeys;
		private final Map<CompiledHopKey,Boolean> parentsByIdentity;

		public CandidateRuleDomain(String analysisFingerprint, List<CandidateRuleKey> ruleKeys,
			List<CandidateConsumerProfileKey> consumerKeys) {
			if(analysisFingerprint == null || analysisFingerprint.isBlank())
				throw new IllegalArgumentException("Candidate domain fingerprint must not be blank");
			this.analysisFingerprint = analysisFingerprint;
			orderedRuleKeys = copyDistinctRuleKeys(ruleKeys);
			orderedConsumerKeys = copyDistinctConsumerKeys(consumerKeys);
			Map<CompiledHopKey,Boolean> parents = new IdentityHashMap<>();
			for(CandidateRuleKey key : orderedRuleKeys)
				parents.put(key.parentOccurrence(), Boolean.TRUE);
			for(CandidateConsumerProfileKey key : orderedConsumerKeys)
				if(!parents.containsKey(key.consumerOccurrence()))
					throw new IllegalArgumentException("Consumer profile owner is outside the candidate domain");
			parentsByIdentity = Collections.unmodifiableMap(parents);
		}

		public String analysisFingerprint() { return analysisFingerprint; }
		public List<CandidateRuleKey> orderedRuleKeys() { return orderedRuleKeys; }
		public List<CandidateConsumerProfileKey> orderedConsumerKeys() { return orderedConsumerKeys; }
		public boolean containsExactParent(CompiledHopKey key) { return parentsByIdentity.containsKey(key); }

		private static List<CandidateRuleKey> copyDistinctRuleKeys(List<CandidateRuleKey> source) {
			Objects.requireNonNull(source, "ruleKeys");
			List<CandidateRuleKey> copied = new java.util.ArrayList<>(source.size());
			Map<CompiledHopKey,List<List<CandidateInputState>>> byParent = new IdentityHashMap<>();
			for(CandidateRuleKey key : source) {
				Objects.requireNonNull(key, "candidate rule domain key");
				List<List<CandidateInputState>> inputs = byParent.computeIfAbsent(key.parentOccurrence(),
					ignored -> new java.util.ArrayList<>());
				if(inputs.contains(key.orderedInputs()))
					throw new IllegalArgumentException("Duplicate candidate rule domain key");
				inputs.add(key.orderedInputs());
				copied.add(key);
			}
			return List.copyOf(copied);
		}

		private static List<CandidateConsumerProfileKey> copyDistinctConsumerKeys(
			List<CandidateConsumerProfileKey> source) {
			Objects.requireNonNull(source, "consumerKeys");
			List<CandidateConsumerProfileKey> copied = new java.util.ArrayList<>(source.size());
			Map<CompiledHopKey,java.util.Set<Integer>> byConsumer = new IdentityHashMap<>();
			for(CandidateConsumerProfileKey key : source) {
				Objects.requireNonNull(key, "candidate consumer domain key");
				if(!byConsumer.computeIfAbsent(key.consumerOccurrence(), ignored -> new java.util.LinkedHashSet<>())
					.add(key.inputPosition()))
					throw new IllegalArgumentException("Duplicate candidate consumer profile domain key");
				copied.add(key);
			}
			return List.copyOf(copied);
		}
	}

	/** Immutable copy of one rule note; no mutable oracle capability object is retained. */
	public record CandidateRuleNote(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode code,
		String message) {
		public CandidateRuleNote {
			Objects.requireNonNull(code, "code");
			message = message == null ? "" : message;
		}
	}

	/** Immutable primitive/enum projection of the exact rule capability result. */
	public record CandidateCapabilityFact(OpCategory category, String opcode, ExecType nativeExec,
		FederatedOutput nativeOutput, FType nativeFoutFType,
		org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode reasonCode, String detail,
		List<CandidateRuleNote> notes) {
		public CandidateCapabilityFact {
			Objects.requireNonNull(category, "category");
			opcode = opcode == null ? "" : opcode;
			Objects.requireNonNull(nativeExec, "nativeExec");
			Objects.requireNonNull(nativeOutput, "nativeOutput");
			Objects.requireNonNull(reasonCode, "reasonCode");
			detail = detail == null ? "" : detail;
			notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
		}
	}

	/** Immutable copy of the shape facts consulted by the exact capability rule. */
	public record CandidateShapeProofFact(Map<String,String> consultedFacts, List<String> requiredFacts,
		List<String> missingRequiredFacts) {
		public CandidateShapeProofFact {
			consultedFacts = Collections.unmodifiableMap(new java.util.TreeMap<>(
				Objects.requireNonNull(consultedFacts, "consultedFacts")));
			requiredFacts = Objects.requireNonNull(requiredFacts, "requiredFacts").stream()
				.map(fact -> Objects.requireNonNull(fact, "required fact")).sorted().toList();
			missingRequiredFacts = Objects.requireNonNull(missingRequiredFacts, "missingRequiredFacts").stream()
				.map(fact -> Objects.requireNonNull(fact, "missing required fact")).sorted().toList();
		}
	}

	/** Immutable producer-profile evidence obtained in the same canonical builder combination. */
	public record CandidateProfileFact(List<FType> producerOutputs, String evaluationFailure) {
		public CandidateProfileFact {
			producerOutputs = List.copyOf(Objects.requireNonNull(producerOutputs, "producerOutputs"));
			evaluationFailure = evaluationFailure == null ? "" : evaluationFailure;
			if(!evaluationFailure.isEmpty() && !producerOutputs.isEmpty())
				throw new IllegalArgumentException("Failed profile evaluation cannot publish outputs");
		}
		public boolean available() { return evaluationFailure.isEmpty(); }
	}

	public enum CandidateEvaluationStatus { AVAILABLE, RULE_ERROR, PROFILE_ERROR }

	public static final class CandidateConsumerProfileFact {
		private final CandidateConsumerProfileKey key;
		private final CandidateEvaluationStatus status;
		private final List<FType> allowedTargetTypes;
		private final String failureCode;
		private final boolean canonicalBuilderFact;

		public CandidateConsumerProfileFact(CandidateConsumerProfileKey key,
			CandidateEvaluationStatus status, List<FType> allowedTargetTypes, String failureCode) {
			this(key, status, allowedTargetTypes, failureCode, false);
		}

		CandidateConsumerProfileFact(CandidateConsumerProfileKey key,
			CandidateEvaluationStatus status, List<FType> allowedTargetTypes, String failureCode,
			boolean canonicalBuilderFact) {
			this.key = Objects.requireNonNull(key, "key");
			this.status = Objects.requireNonNull(status, "status");
			this.allowedTargetTypes = List.copyOf(Objects.requireNonNull(allowedTargetTypes,
				"allowedTargetTypes"));
			this.failureCode = failureCode == null ? "" : failureCode;
			if(status == CandidateEvaluationStatus.RULE_ERROR
				|| status == CandidateEvaluationStatus.AVAILABLE && !this.failureCode.isEmpty()
				|| status == CandidateEvaluationStatus.PROFILE_ERROR
					&& (this.failureCode.isEmpty() || !this.allowedTargetTypes.isEmpty()))
				throw new IllegalArgumentException("Consumer profile status and evidence differ");
			this.canonicalBuilderFact = canonicalBuilderFact;
		}

		public CandidateConsumerProfileKey key() { return key; }
		public CandidateEvaluationStatus status() { return status; }
		public List<FType> allowedTargetTypes() { return allowedTargetTypes; }
		public String failureCode() { return failureCode; }
		boolean canonicalBuilderFact() { return canonicalBuilderFact; }
	}

	/** Primitive producer-scoped profile evidence for a consumer absent from the analysis occurrence graph. */
	public record DetachedConsumerProfileKey(CompiledHopKey producerOccurrence, int parentOrdinal,
		String normalizedConsumerSignature, List<Integer> producerInputPositions) {
		public DetachedConsumerProfileKey {
			Objects.requireNonNull(producerOccurrence, "producerOccurrence");
			if(parentOrdinal < 0)
				throw new IllegalArgumentException("Detached consumer parent ordinal must be non-negative");
			if(normalizedConsumerSignature == null || normalizedConsumerSignature.isBlank())
				throw new IllegalArgumentException("Detached consumer signature must not be blank");
			producerInputPositions = List.copyOf(Objects.requireNonNull(producerInputPositions,
				"producerInputPositions"));
			if(producerInputPositions.isEmpty())
				throw new IllegalArgumentException("Detached consumer must reference its producer");
			int previous = -1;
			for(int position : producerInputPositions) {
				if(position <= previous)
					throw new IllegalArgumentException("Detached consumer input positions must be strictly ordered");
				previous = position;
			}
		}
	}

	public record DetachedConsumerProfileFact(DetachedConsumerProfileKey key,
		CandidateEvaluationStatus status, List<FType> allowedTargetTypes, String failureCode) {
		public DetachedConsumerProfileFact {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(status, "status");
			allowedTargetTypes = List.copyOf(Objects.requireNonNull(allowedTargetTypes, "allowedTargetTypes"));
			failureCode = failureCode == null ? "" : failureCode;
			if(status == CandidateEvaluationStatus.RULE_ERROR
				|| status == CandidateEvaluationStatus.AVAILABLE && !failureCode.isEmpty()
				|| status == CandidateEvaluationStatus.PROFILE_ERROR
					&& (failureCode.isEmpty() || !allowedTargetTypes.isEmpty()))
				throw new IllegalArgumentException("Detached consumer profile status and evidence differ");
		}
	}

	/** One exact immutable rule/profile fact captured by the canonical builder pass. */
	public record CandidateRuleFact(CandidateRuleKey key, CandidateEvaluationStatus status,
		CandidateCapabilityFact capability, CandidateShapeProofFact shapeProof, CandidateProfileFact profile,
		String failureCode) {
		public CandidateRuleFact {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(shapeProof, "shapeProof");
			Objects.requireNonNull(profile, "profile");
			failureCode = failureCode == null ? "" : failureCode;
			if(status == CandidateEvaluationStatus.AVAILABLE
					&& (capability == null || !profile.available() || !failureCode.isEmpty())
				|| status == CandidateEvaluationStatus.RULE_ERROR
					&& (profile.available() || failureCode.isEmpty())
				|| status == CandidateEvaluationStatus.PROFILE_ERROR
					&& (capability == null || profile.available() || failureCode.isEmpty()))
				throw new IllegalArgumentException("Candidate rule status and evidence differ");
		}
	}


	public static final class LoopContextFact {
		private final ControlRegionKey loopRegion;
		private final double weight;

		LoopContextFact(ControlRegionKey loopRegion, double weight) {
			this.loopRegion = Objects.requireNonNull(loopRegion, "loopRegion");
			this.weight = requirePositiveFinite(weight, "weight");
		}

		public ControlRegionKey loopRegion() { return loopRegion; }
		public double weight() { return weight; }
	}

	public static final class ExecutionFrequencyFact {
		private final CompiledHopKey key;
		private final double computeWeight;
		private final double networkWeight;
		private final double multiplicity;
		private final List<LoopContextFact> loopContext;

		ExecutionFrequencyFact(CompiledHopKey key, double computeWeight, double networkWeight,
			double multiplicity, List<LoopContextFact> loopContext) {
			this.key = Objects.requireNonNull(key, "key");
			this.computeWeight = requirePositiveFinite(computeWeight, "computeWeight");
			this.networkWeight = requirePositiveFinite(networkWeight, "networkWeight");
			this.multiplicity = requirePositiveFinite(multiplicity, "multiplicity");
			this.loopContext = copyCanonicalLoopContext(key, loopContext);
		}

		public CompiledHopKey key() { return key; }
		public double computeWeight() { return computeWeight; }
		public double networkWeight() { return networkWeight; }
		public double multiplicity() { return multiplicity; }
		public List<LoopContextFact> loopContext() { return loopContext; }
	}

	public static final class ProducerConsumerDemandKey {
		private final CompiledHopKey producer;
		private final CompiledHopKey consumer;
		private final int inputPosition;

		ProducerConsumerDemandKey(CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) {
			this.producer = Objects.requireNonNull(producer, "producer");
			this.consumer = Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException("inputPosition must be non-negative");
			this.inputPosition = inputPosition;
		}

		public CompiledHopKey producer() { return producer; }
		public CompiledHopKey consumer() { return consumer; }
		public int inputPosition() { return inputPosition; }
	}

	public enum TransferSourceKind {
		DURABLE_ANCHOR,
		PERSISTENT_LOCAL_READ,
		DERIVED_LOCAL_VALUE,
		EXPLICIT_RELOCATION
	}

	public static final class TransferSourceProof {
		private final TransferSourceKind kind;
		private final CompiledHopKey localProducerOrNull;
		private final org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey durableAnchorOrNull;
		private final org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey relocationActionOrNull;

		TransferSourceProof(TransferSourceKind kind, CompiledHopKey localProducerOrNull,
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey durableAnchorOrNull,
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey relocationActionOrNull) {
			this.kind = Objects.requireNonNull(kind, "kind");
			switch(kind) {
				case DURABLE_ANCHOR:
					if(localProducerOrNull != null || durableAnchorOrNull == null || relocationActionOrNull != null)
						throw new IllegalArgumentException("Durable-anchor proof must carry exactly one durable anchor");
					break;
				case PERSISTENT_LOCAL_READ:
				case DERIVED_LOCAL_VALUE:
					if(localProducerOrNull == null || durableAnchorOrNull != null || relocationActionOrNull != null)
						throw new IllegalArgumentException("Local-source proof must carry exactly one local producer");
					break;
				case EXPLICIT_RELOCATION:
					if(localProducerOrNull != null || durableAnchorOrNull != null || relocationActionOrNull == null)
						throw new IllegalArgumentException("Relocation proof must carry exactly one relocation action");
					break;
				default:
					throw new IllegalArgumentException("Unknown transfer source kind");
			}
			this.localProducerOrNull = localProducerOrNull;
			this.durableAnchorOrNull = durableAnchorOrNull;
			this.relocationActionOrNull = relocationActionOrNull;
		}

		public TransferSourceKind kind() { return kind; }
		public CompiledHopKey localProducerOrNull() { return localProducerOrNull; }
		public org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey durableAnchorOrNull() { return durableAnchorOrNull; }
		public org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey relocationActionOrNull() { return relocationActionOrNull; }
	}

	public static final class ProducerConsumerDemandFact {
		private final ProducerConsumerDemandKey key;
		private final double forwardingWeight;
		private final FType requiredTargetType;
		private final CandidateConsumerProfileFact exactConsumerProfile;
		private final TransferSourceProof transferSourceProof;

		ProducerConsumerDemandFact(ProducerConsumerDemandKey key, double forwardingWeight,
			FType requiredTargetType, CandidateConsumerProfileFact exactConsumerProfile,
			TransferSourceProof transferSourceProof) {
			this.key = Objects.requireNonNull(key, "key");
			this.forwardingWeight = requirePositiveFinite(forwardingWeight, "forwardingWeight");
			this.requiredTargetType = Objects.requireNonNull(requiredTargetType, "requiredTargetType");
			this.exactConsumerProfile = Objects.requireNonNull(exactConsumerProfile, "exactConsumerProfile");
			this.transferSourceProof = Objects.requireNonNull(transferSourceProof, "transferSourceProof");
			if(!exactConsumerProfile.canonicalBuilderFact())
				throw new IllegalArgumentException("Demand requires the owner-canonical consumer profile fact");
			if(exactConsumerProfile.status() != CandidateEvaluationStatus.AVAILABLE)
				throw new IllegalArgumentException("Demand requires an available exact consumer profile");
			if(exactConsumerProfile.key().consumerOccurrence() != key.consumer()
				|| exactConsumerProfile.key().inputPosition() != key.inputPosition())
				throw new IllegalArgumentException("Demand key and exact consumer profile differ");
			if(!exactConsumerProfile.allowedTargetTypes().contains(requiredTargetType))
				throw new IllegalArgumentException("Required target type is not allowed by the exact consumer profile");
			if((transferSourceProof.kind() == TransferSourceKind.PERSISTENT_LOCAL_READ
				|| transferSourceProof.kind() == TransferSourceKind.DERIVED_LOCAL_VALUE)
				&& transferSourceProof.localProducerOrNull() != key.producer())
				throw new IllegalArgumentException("Local-source proof producer is not the exact demand producer");
		}

		public ProducerConsumerDemandKey key() { return key; }
		public double forwardingWeight() { return forwardingWeight; }
		public FType requiredTargetType() { return requiredTargetType; }
		public CandidateConsumerProfileFact exactConsumerProfile() { return exactConsumerProfile; }
		public TransferSourceProof transferSourceProof() { return transferSourceProof; }
	}

	private static double requirePositiveFinite(double value, String name) {
		if(!Double.isFinite(value) || value <= 0.0d)
			throw new IllegalArgumentException(name + " must be finite and strictly positive");
		return value;
	}

	private static List<LoopContextFact> copyCanonicalLoopContext(CompiledHopKey key,
		List<LoopContextFact> loopContext) {
		Objects.requireNonNull(loopContext, "loopContext");
		List<LoopContextFact> copied = new java.util.ArrayList<>(loopContext.size());
		Set<ControlRegionKey> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		ControlRegionKey previous = key.controlRegion();
		for(LoopContextFact fact : loopContext) {
			Objects.requireNonNull(fact, "loop context fact");
			if(!seen.add(fact.loopRegion()))
				throw new IllegalArgumentException("Duplicate loop context region identity");
			if(!isStrictRegionDescendant(previous, fact.loopRegion()))
				throw new IllegalArgumentException("Loop context must be canonical outer-to-inner");
			copied.add(fact);
			previous = fact.loopRegion();
		}
		return List.copyOf(copied);
	}

	private static boolean isStrictRegionDescendant(ControlRegionKey parent, ControlRegionKey child) {
		if(!parent.programFingerprint().equals(child.programFingerprint())
			|| !parent.functionNamespace().equals(child.functionNamespace())
			|| !parent.callSitePath().equals(child.callSitePath())
			|| !parent.recompileContext().equals(child.recompileContext()))
			return false;
		List<String> parentPath = parent.regionPath();
		List<String> childPath = child.regionPath();
		return childPath.size() > parentPath.size()
			&& childPath.subList(0, parentPath.size()).equals(parentPath);
	}

	public enum CandidateLookupFailure {
		FOREIGN_PARENT, NON_CANDIDATE_PARENT, MISSING_FACT, REORDERED_INPUTS, PRESENT_NULL
	}

	public static final class CandidateRuleLookupException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		private final CandidateLookupFailure failure;
		public CandidateRuleLookupException(CandidateLookupFailure failure, String message) {
			super(message);
			this.failure = Objects.requireNonNull(failure, "failure");
		}
		public CandidateLookupFailure failure() { return failure; }
	}

	/** Ordered, deeply immutable exact-candidate fact universe. */
	public static final class CandidateRuleFacts {
		private final List<CandidateRuleFact> orderedFacts;
		private final Map<CandidateRuleKey,CandidateRuleFact> factsByKey;
		private final CandidateRuleDomain domain;

		public CandidateRuleFacts(CandidateRuleDomain domain, List<CandidateRuleFact> facts) {
			this.domain = Objects.requireNonNull(domain, "domain");
			Objects.requireNonNull(facts, "facts");
			if(facts.size() != domain.orderedRuleKeys().size())
				throw new IllegalArgumentException("Candidate rule fact/domain multiplicity differs");
			LinkedHashMap<CandidateRuleKey,CandidateRuleFact> indexed = new LinkedHashMap<>();
			for(int i = 0; i < facts.size(); i++) {
				CandidateRuleKey expected = domain.orderedRuleKeys().get(i);
				CandidateRuleFact fact = facts.get(i);
				Objects.requireNonNull(fact, "candidate rule fact");
				if(fact.key().parentOccurrence() != expected.parentOccurrence()
					|| !fact.key().orderedInputs().equals(expected.orderedInputs()))
					throw new IllegalArgumentException("Candidate rule fact order/domain key differs");
				if(indexed.putIfAbsent(fact.key(), fact) != null)
					throw new IllegalArgumentException("Duplicate exact candidate rule fact: " + fact.key());
			}
			orderedFacts = List.copyOf(indexed.values());
			factsByKey = Collections.unmodifiableMap(indexed);
		}

		public List<CandidateRuleFact> orderedFacts() { return orderedFacts; }

		public CandidateRuleFact requireExact(CompiledHopKey parentOccurrence,
			List<CandidateInputState> orderedInputs) {
			if(parentOccurrence == null || !domain.containsExactParent(parentOccurrence))
				throw new CandidateRuleLookupException(CandidateLookupFailure.NON_CANDIDATE_PARENT,
					"Parent is foreign, copied, or outside the canonical candidate domain");
			if(orderedInputs == null)
				throw new CandidateRuleLookupException(CandidateLookupFailure.PRESENT_NULL,
					"Candidate input vector is null");
			for(CandidateInputState input : orderedInputs)
				if(input == null)
					throw new CandidateRuleLookupException(CandidateLookupFailure.PRESENT_NULL,
						"Present-null cannot be a candidate input state");
			CandidateRuleFact fact = factsByKey.get(new CandidateRuleKey(parentOccurrence, orderedInputs));
			if(fact == null) {
				boolean reordered = orderedFacts.stream().filter(candidate ->
					candidate.key().parentOccurrence() == parentOccurrence
						&& candidate.key().orderedInputs().size() == orderedInputs.size())
					.anyMatch(candidate -> sameMultiplicity(candidate.key().orderedInputs(), orderedInputs));
				throw new CandidateRuleLookupException(reordered ? CandidateLookupFailure.REORDERED_INPUTS
					: CandidateLookupFailure.MISSING_FACT, "Exact candidate rule fact is missing");
			}
			if(fact.key().parentOccurrence() != parentOccurrence
				|| !fact.key().orderedInputs().equals(orderedInputs))
				throw new IllegalArgumentException("Candidate rule lookup identity or order differs");
			return fact;
		}

		private static boolean sameMultiplicity(List<CandidateInputState> left, List<CandidateInputState> right) {
			Map<CandidateInputState,Integer> counts = new LinkedHashMap<>();
			for(CandidateInputState value : left) counts.merge(value, 1, Integer::sum);
			for(CandidateInputState value : right) counts.merge(value, -1, Integer::sum);
			return counts.values().stream().allMatch(count -> count == 0);
		}
	}

	public static final class CandidateConsumerProfileFacts {
		private final CandidateRuleDomain domain;
		private final List<CandidateConsumerProfileFact> orderedFacts;
		private final Map<CandidateConsumerProfileKey,CandidateConsumerProfileFact> factsByKey;

		public CandidateConsumerProfileFacts(CandidateRuleDomain domain,
			List<CandidateConsumerProfileFact> facts) {
			this.domain = Objects.requireNonNull(domain, "domain");
			Objects.requireNonNull(facts, "facts");
			if(facts.size() != domain.orderedConsumerKeys().size())
				throw new IllegalArgumentException("Consumer profile fact/domain multiplicity differs");
			LinkedHashMap<CandidateConsumerProfileKey,CandidateConsumerProfileFact> indexed = new LinkedHashMap<>();
			for(int i = 0; i < facts.size(); i++) {
				CandidateConsumerProfileKey expected = domain.orderedConsumerKeys().get(i);
				CandidateConsumerProfileFact fact = Objects.requireNonNull(facts.get(i), "consumer profile fact");
				if(fact.key().consumerOccurrence() != expected.consumerOccurrence()
					|| fact.key().inputPosition() != expected.inputPosition())
					throw new IllegalArgumentException("Consumer profile fact order/domain key differs");
				if(indexed.putIfAbsent(fact.key(), fact) != null)
					throw new IllegalArgumentException("Duplicate consumer profile fact");
			}
			orderedFacts = List.copyOf(indexed.values());
			factsByKey = Collections.unmodifiableMap(indexed);
		}

		public List<CandidateConsumerProfileFact> orderedFacts() { return orderedFacts; }
		public CandidateConsumerProfileFact requireExact(CompiledHopKey consumer, int inputPosition) {
			if(!domain.containsExactParent(consumer))
				throw new CandidateRuleLookupException(CandidateLookupFailure.NON_CANDIDATE_PARENT,
					"Consumer is outside the canonical candidate domain");
			CandidateConsumerProfileFact fact = factsByKey.get(new CandidateConsumerProfileKey(consumer, inputPosition));
			if(fact == null || fact.key().consumerOccurrence() != consumer)
				throw new CandidateRuleLookupException(CandidateLookupFailure.MISSING_FACT,
					"Exact consumer profile fact is missing");
			return fact;
		}
	}

	/** Ordered, deeply immutable detached consumer evidence indexed by exact producer identity. */
	public static final class DetachedConsumerProfileFacts {
		private final List<DetachedConsumerProfileFact> orderedFacts;
		private final Map<CompiledHopKey,List<DetachedConsumerProfileFact>> factsByProducer;

		public DetachedConsumerProfileFacts(List<DetachedConsumerProfileFact> facts,
			Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
			Objects.requireNonNull(facts, "facts");
			Map<CompiledHopKey,List<DetachedConsumerProfileFact>> indexed = new IdentityHashMap<>();
			Set<DetachedConsumerProfileKey> keys = new java.util.HashSet<>();
			List<DetachedConsumerProfileFact> copied = new java.util.ArrayList<>(facts.size());
			for(DetachedConsumerProfileFact fact : facts) {
				Objects.requireNonNull(fact, "detached consumer profile fact");
				if(!analysisKeysByIdentity.containsKey(fact.key().producerOccurrence()))
					throw new IllegalArgumentException("Detached consumer producer is not analysis-owned");
				if(!keys.add(fact.key()))
					throw new IllegalArgumentException("Duplicate detached consumer profile fact");
				indexed.computeIfAbsent(fact.key().producerOccurrence(), ignored -> new java.util.ArrayList<>()).add(fact);
				copied.add(fact);
			}
			orderedFacts = List.copyOf(copied);
			Map<CompiledHopKey,List<DetachedConsumerProfileFact>> immutable = new IdentityHashMap<>();
			indexed.forEach((producer, producerFacts) -> immutable.put(producer, List.copyOf(producerFacts)));
			factsByProducer = Collections.unmodifiableMap(immutable);
		}

		public List<DetachedConsumerProfileFact> orderedFacts() { return orderedFacts; }
		public List<DetachedConsumerProfileFact> requireExactProducer(CompiledHopKey producer) {
			List<DetachedConsumerProfileFact> facts = factsByProducer.get(producer);
			return facts == null ? List.of() : facts;
		}
	}
	/** Exact producer/value pair for one immutable Heuristic demotion fact. */
	public record HeuristicPolicyFact(CompiledHopKey producer, ValueVersionKey valueVersion)
		implements Comparable<HeuristicPolicyFact> {
		public HeuristicPolicyFact {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(valueVersion, "valueVersion");
			if(!producer.programFingerprint().equals(valueVersion.programFingerprint()))
				throw new IllegalArgumentException("Heuristic policy producer and value fingerprints differ");
		}

		@Override
		public int compareTo(HeuristicPolicyFact that) {
			int producerOrder = producer.compareTo(that.producer);
			return producerOrder != 0 ? producerOrder : valueVersion.compareTo(that.valueVersion);
		}
	}

	/** Deterministic, deeply immutable set of producer-scoped Heuristic demotions. */
	public record HeuristicPolicyFacts(List<HeuristicPolicyFact> demotions) {
		public HeuristicPolicyFacts {
			Objects.requireNonNull(demotions, "demotions");
			List<HeuristicPolicyFact> sorted = demotions.stream()
				.map(fact -> Objects.requireNonNull(fact, "demotion fact")).sorted().toList();
			Map<CompiledHopKey, ValueVersionKey> valuesByProducer = new LinkedHashMap<>();
			Map<ValueVersionKey, CompiledHopKey> producersByValue = new LinkedHashMap<>();
			HeuristicPolicyFact previous = null;
			for(HeuristicPolicyFact fact : sorted) {
				if(fact.equals(previous))
					throw new IllegalArgumentException("Duplicate Heuristic policy fact");
				ValueVersionKey priorValue = valuesByProducer.putIfAbsent(fact.producer(), fact.valueVersion());
				if(priorValue != null && !priorValue.equals(fact.valueVersion()))
					throw new IllegalArgumentException("Heuristic policy producer maps to multiple values");
				CompiledHopKey priorProducer = producersByValue.putIfAbsent(fact.valueVersion(), fact.producer());
				if(priorProducer != null && !priorProducer.equals(fact.producer()))
					throw new IllegalArgumentException("Heuristic policy value maps to multiple producers");
				previous = fact;
			}
			demotions = List.copyOf(sorted);
		}
	}

	public record NodeShapeFact(DataType dataType, long rows, long cols) {
		public NodeShapeFact { Objects.requireNonNull(dataType, "dataType"); }
		public boolean knownPositiveMatrix() { return dataType == DataType.MATRIX && rows > 0 && cols > 0; }
	}
	/** Stable association between a neutral graph key and its concrete compiled Hop origin. */
	public record HopOccurrenceProjection(CompiledHopKey key, Hop hop, long scopeId, int normalizedOrdinal,
		String normalizedSignature) {
		public HopOccurrenceProjection {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(hop, "hop");
			if(normalizedOrdinal < 0)
				throw new IllegalArgumentException("normalizedOrdinal must be non-negative");
			if(normalizedSignature == null || normalizedSignature.isBlank())
				throw new IllegalArgumentException("normalizedSignature must not be blank");
		}
	}

	private final NeutralPlacementGraph graph;
	private final List<HopOccurrenceProjection> occurrences;
	private final List<StatementBlock> topLevelStatementBlocks;
	private final Map<CompiledHopKey, Hop> hopsByKey;
	private final PlacementShapeFacts shapeFacts;
	private final String analysisFingerprint;
	private final HeuristicPolicyFacts heuristicPolicyFacts;
	private final CandidateRuleDomain candidateRuleDomain;
	private final CandidateRuleFacts candidateRuleFacts;
	private final CandidateConsumerProfileFacts candidateConsumerProfileFacts;
	private final DetachedConsumerProfileFacts detachedConsumerProfileFacts;
	private final List<ExecutionFrequencyFact> executionFrequencyFactsInScopeOrder;
	private final List<ProducerConsumerDemandFact> producerConsumerDemandFactsInCanonicalOrder;
	private final Map<CompiledHopKey,ExecutionFrequencyFact> frequencyFactsByIdentity;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,ProducerConsumerDemandFact>>> demandFactsByIdentity;
	private final String executionCostFactsFingerprint;
	private final DMLProgram programOwner;

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<ExecutionFrequencyFact> executionFrequencyFacts,
		List<ProducerConsumerDemandFact> producerConsumerDemandFacts) {
		this.graph = Objects.requireNonNull(graph, "graph");
		this.programOwner = programOwner;
		this.occurrences = List.copyOf(occurrences);
		this.topLevelStatementBlocks = List.copyOf(topLevelStatementBlocks);
		Map<CompiledHopKey, Hop> indexed = new LinkedHashMap<>();
		for(HopOccurrenceProjection occurrence : this.occurrences)
			if(indexed.put(occurrence.key(), occurrence.hop()) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop projection key: " + occurrence.key());
		if(indexed.size() != graph.nodes().size())
			throw new IllegalArgumentException("Projection does not cover the neutral placement graph");
		this.shapeFacts = Objects.requireNonNull(shapeFacts, "shapeFacts");
		if(!shapeFacts.keys().equals(indexed.keySet()))
			throw new IllegalArgumentException("Shape facts do not exactly cover indexed placement projections");
		hopsByKey = Map.copyOf(indexed);
		if(analysisFingerprint == null || analysisFingerprint.isBlank())
			throw new IllegalArgumentException("analysisFingerprint must not be blank");
		this.analysisFingerprint = canonicalizeSuppliedAnalysisFingerprint(analysisFingerprint);
		this.heuristicPolicyFacts = Objects.requireNonNull(heuristicPolicyFacts, "heuristicPolicyFacts");
		this.candidateRuleDomain = new CandidateRuleDomain(analysisFingerprint, candidateRuleDomainKeys,
			candidateConsumerDomainKeys);
		Map<CompiledHopKey,Boolean> analysisKeysByIdentity = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : this.occurrences)
			analysisKeysByIdentity.put(occurrence.key(), Boolean.TRUE);
		for(CandidateRuleKey key : this.candidateRuleDomain.orderedRuleKeys())
			if(!analysisKeysByIdentity.containsKey(key.parentOccurrence()))
				throw new IllegalArgumentException("Candidate domain parent is not analysis-owned");
		this.candidateRuleFacts = new CandidateRuleFacts(this.candidateRuleDomain, candidateRuleFacts);
		this.candidateConsumerProfileFacts = new CandidateConsumerProfileFacts(this.candidateRuleDomain,
			candidateConsumerProfileFacts);
		this.detachedConsumerProfileFacts = new DetachedConsumerProfileFacts(detachedConsumerProfileFacts,
			analysisKeysByIdentity);
		this.executionFrequencyFactsInScopeOrder = validateExecutionFrequencyFacts(executionFrequencyFacts);
		this.frequencyFactsByIdentity = indexFrequencyFacts(this.executionFrequencyFactsInScopeOrder);
		this.producerConsumerDemandFactsInCanonicalOrder = validateProducerConsumerDemandFacts(producerConsumerDemandFacts);
		this.demandFactsByIdentity = indexDemandFacts(this.producerConsumerDemandFactsInCanonicalOrder);
		this.executionCostFactsFingerprint = computeExecutionCostFactsFingerprint();
		for(HeuristicPolicyFact fact : heuristicPolicyFacts.demotions()) {
			NeutralPlacementGraph.Node producer = graph.node(fact.producer()).orElseThrow(() ->
				new IllegalArgumentException("Heuristic policy producer is missing from the analysis graph"));
			if(!producer.valueVersion().equals(fact.valueVersion()))
				throw new IllegalArgumentException("Heuristic policy producer/value pair does not match the analysis graph");
		}
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		DMLProgram programOwner, PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts) {
		this(graph, occurrences, List.of(), programOwner, shapeFacts, analysisFingerprint, heuristicPolicyFacts,
			candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys, candidateConsumerProfileFacts,
			List.of(), defaultExecutionFrequencyFacts(graph, occurrences), List.of());
	}

	/** Compatibility surface for fixtures that predate canonical candidate-fact publication. */
	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		DMLProgram programOwner, PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts) {
		this(graph, occurrences, programOwner, shapeFacts, analysisFingerprint, heuristicPolicyFacts,
			List.of(), List.of(), List.of(), List.of());
	}


	private List<ExecutionFrequencyFact> validateExecutionFrequencyFacts(List<ExecutionFrequencyFact> facts) {
		Objects.requireNonNull(facts, "executionFrequencyFacts");
		List<CompiledHopKey> scope = compiledHopOccurrences().stream().map(HopOccurrenceProjection::key).toList();
		if(facts.size() != scope.size())
			throw new IllegalArgumentException("Execution frequency facts do not exactly cover compiled scope");
		List<ExecutionFrequencyFact> copied = new java.util.ArrayList<>(facts.size());
		for(int i = 0; i < facts.size(); i++) {
			ExecutionFrequencyFact fact = Objects.requireNonNull(facts.get(i), "execution frequency fact");
			if(fact.key() != scope.get(i))
				throw new IllegalArgumentException("Execution frequency fact order or owner identity differs");
			copied.add(fact);
		}
		return List.copyOf(copied);
	}

	private Map<CompiledHopKey,ExecutionFrequencyFact> indexFrequencyFacts(List<ExecutionFrequencyFact> facts) {
		Map<CompiledHopKey,ExecutionFrequencyFact> indexed = new IdentityHashMap<>();
		for(ExecutionFrequencyFact fact : facts)
			if(indexed.put(fact.key(), fact) != null)
				throw new IllegalArgumentException("Duplicate execution frequency fact");
		return Collections.unmodifiableMap(indexed);
	}

	private List<ProducerConsumerDemandFact> validateProducerConsumerDemandFacts(
		List<ProducerConsumerDemandFact> facts) {
		Objects.requireNonNull(facts, "producerConsumerDemandFacts");
		Map<CompiledHopKey,Integer> scopeOrder = new IdentityHashMap<>();
		List<CompiledHopKey> scope = compiledHopOccurrences().stream().map(HopOccurrenceProjection::key).toList();
		for(int i = 0; i < scope.size(); i++)
			scopeOrder.put(scope.get(i), i);
		List<ProducerConsumerDemandFact> copied = new java.util.ArrayList<>(facts.size());
		ProducerConsumerDemandFact previous = null;
		for(ProducerConsumerDemandFact fact : facts) {
			Objects.requireNonNull(fact, "producer-consumer demand fact");
			ProducerConsumerDemandKey key = fact.key();
			Integer consumerOrdinal = scopeOrder.get(key.consumer());
			if(consumerOrdinal == null || !scopeOrder.containsKey(key.producer()))
				throw new IllegalArgumentException("Demand key is outside the compiled analysis scope");
			Hop consumerHop = hopsByKey.get(key.consumer());
			Hop producerHop = hopsByKey.get(key.producer());
			if(consumerHop == null || producerHop == null || key.inputPosition() >= consumerHop.getInput().size()
				|| consumerHop.getInput(key.inputPosition()) != producerHop)
				throw new IllegalArgumentException("Demand key is not an exact producer/consumer/input edge");
			CandidateConsumerProfileFact exactProfile = candidateConsumerProfileFacts.requireExact(key.consumer(),
				key.inputPosition());
			if(fact.exactConsumerProfile() != exactProfile)
				throw new IllegalArgumentException("Demand does not bind the owner-canonical consumer profile fact");
			if(!exactProfile.allowedTargetTypes().contains(fact.requiredTargetType()))
				throw new IllegalArgumentException("Demand required type is not owner-canonical");
			validateTransferSourceProof(fact);
			if(previous != null && compareDemandCanonical(previous, fact, scopeOrder) >= 0)
				throw new IllegalArgumentException("Producer/consumer demand facts are not in canonical order");
			copied.add(fact);
			previous = fact;
		}
		return List.copyOf(copied);
	}

	private int compareDemandCanonical(ProducerConsumerDemandFact left, ProducerConsumerDemandFact right,
		Map<CompiledHopKey,Integer> scopeOrder) {
		ProducerConsumerDemandKey a = left.key();
		ProducerConsumerDemandKey b = right.key();
		int c = Integer.compare(scopeOrder.get(a.consumer()), scopeOrder.get(b.consumer()));
		if(c != 0) return c;
		c = Integer.compare(a.inputPosition(), b.inputPosition());
		if(c != 0) return c;
		return Integer.compare(scopeOrder.get(a.producer()), scopeOrder.get(b.producer()));
	}

	private void validateTransferSourceProof(ProducerConsumerDemandFact fact) {
		ProducerConsumerDemandKey key = fact.key();
		TransferSourceProof proof = fact.transferSourceProof();
		NeutralPlacementGraph.Node producer = graph.node(key.producer()).orElseThrow(() ->
			new IllegalArgumentException("Demand producer is missing from the analysis graph"));
		switch(proof.kind()) {
			case PERSISTENT_LOCAL_READ:
			case DERIVED_LOCAL_VALUE:
				if(proof.localProducerOrNull() != key.producer())
					throw new IllegalArgumentException("Demand local proof is not owner-bound");
				break;
			case DURABLE_ANCHOR:
				if(producer.anchors().stream().noneMatch(anchor -> anchor == proof.durableAnchorOrNull()))
					throw new IllegalArgumentException("Demand durable proof is not graph-owned");
				break;
			case EXPLICIT_RELOCATION:
				if(graph.relocationActions().stream().noneMatch(action -> action.key() == proof.relocationActionOrNull()))
					throw new IllegalArgumentException("Demand relocation proof is not graph-owned");
				if(!proof.relocationActionOrNull().sourceValueVersion().equals(producer.valueVersion())
					|| !proof.relocationActionOrNull().compatibleConsumers().contains(key.consumer()))
					throw new IllegalArgumentException("Demand relocation proof does not match source value/consumer");
				break;
			default:
				throw new IllegalArgumentException("Unknown demand proof kind");
		}
	}

	private Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,ProducerConsumerDemandFact>>> indexDemandFacts(
		List<ProducerConsumerDemandFact> facts) {
		Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,ProducerConsumerDemandFact>>> indexed = new IdentityHashMap<>();
		for(ProducerConsumerDemandFact fact : facts) {
			ProducerConsumerDemandKey key = fact.key();
			Map<CompiledHopKey,Map<Integer,ProducerConsumerDemandFact>> byConsumer = indexed.computeIfAbsent(
				key.producer(), ignored -> new IdentityHashMap<>());
			Map<Integer,ProducerConsumerDemandFact> byPosition = byConsumer.computeIfAbsent(key.consumer(),
				ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(key.inputPosition(), fact) != null)
				throw new IllegalArgumentException("Duplicate producer-consumer demand fact");
		}
		return Collections.unmodifiableMap(indexed);
	}

	private String canonicalizeSuppliedAnalysisFingerprint(String supplied) {
		if(!supplied.matches("[0-9a-f]{64}"))
			return supplied;
		String graphSignature = graph.normalizedSignature();
		List<String> projectionSignatures = occurrences.stream()
			.map(occurrence -> stableSignature(occurrence.normalizedSignature())).sorted().toList();
		return sha256(stableSignature(graphSignature) + '\n' + String.join("\n", projectionSignatures));
	}

	private String computeExecutionCostFactsFingerprint() {
		List<String> rows = new java.util.ArrayList<>();
		for(ExecutionFrequencyFact fact : executionFrequencyFactsInScopeOrder) {
			rows.add("F|" + stableSignature(fact.key().normalizedSignature()) + '|' + Double.doubleToRawLongBits(fact.computeWeight())
				+ '|' + Double.doubleToRawLongBits(fact.networkWeight()) + '|'
				+ Double.doubleToRawLongBits(fact.multiplicity()) + '|' + loopSignature(fact.loopContext()));
		}
		for(ProducerConsumerDemandFact fact : producerConsumerDemandFactsInCanonicalOrder) {
			ProducerConsumerDemandKey key = fact.key();
			TransferSourceProof proof = fact.transferSourceProof();
			rows.add("D|" + stableSignature(key.producer().normalizedSignature()) + '|' + stableSignature(key.consumer().normalizedSignature())
				+ '|' + key.inputPosition() + '|' + Double.doubleToRawLongBits(fact.forwardingWeight())
				+ '|' + fact.requiredTargetType().name() + '|' + proofSignature(proof));
		}
		return sha256(String.join("\n", rows));
	}

	private static String loopSignature(List<LoopContextFact> loopContext) {
		return loopContext.stream().map(fact -> stableSignature(fact.loopRegion().normalizedSignature()) + '@'
			+ Double.doubleToRawLongBits(fact.weight())).collect(java.util.stream.Collectors.joining(","));
	}

	private static String proofSignature(TransferSourceProof proof) {
		return proof.kind().name() + '|'
			+ (proof.localProducerOrNull() == null ? "" : stableSignature(proof.localProducerOrNull().normalizedSignature())) + '|'
			+ (proof.durableAnchorOrNull() == null ? "" : stableSignature(proof.durableAnchorOrNull().normalizedSignature())) + '|'
			+ (proof.relocationActionOrNull() == null ? "" : stableSignature(proof.relocationActionOrNull().normalizedSignature()));
	}

	private static String stableSignature(String signature) {
		return signature.replaceAll("[0-9a-f]{64}", "<program>");
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(hash.length * 2);
			for(byte b : hash)
				builder.append(String.format("%02x", b));
			return builder.toString();
		}
		catch(NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static List<ExecutionFrequencyFact> defaultExecutionFrequencyFacts(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> occurrences) {
		List<ExecutionFrequencyFact> facts = new java.util.ArrayList<>();
		for(HopOccurrenceProjection occurrence : occurrences) {
			NodeKind kind = graph.node(occurrence.key()).map(NeutralPlacementGraph.Node::kind).orElse(NodeKind.OPERATION);
			if(kind != NodeKind.FUNCTION_INPUT && kind != NodeKind.FUNCTION_OUTPUT)
				facts.add(new ExecutionFrequencyFact(occurrence.key(), 1.0d, 1.0d, 1.0d, List.of()));
		}
		return List.copyOf(facts);
	}

	public NeutralPlacementGraph graph() {
		return graph;
	}

	public List<HopOccurrenceProjection> occurrences() {
		return occurrences;
	}

	/**
	 * Return projections backed by compiled Hop occurrences. Synthetic function
	 * boundary projections remain part of the semantic graph but are not
	 * independent selected-plan assignments.
	 *
	 * @return immutable compiled occurrence projections in analysis order
	 */
	public List<HopOccurrenceProjection> compiledHopOccurrences() {
		return occurrences.stream().filter(occurrence -> isCompiledHopOccurrence(occurrence.key())).toList();
	}

	public boolean isCompiledHopOccurrence(HopOccurrenceProjection occurrence) {
		Objects.requireNonNull(occurrence, "occurrence");
		if(occurrences.stream().noneMatch(candidate -> candidate == occurrence))
			throw new IllegalArgumentException("Occurrence is not owned by this placement analysis");
		return isCompiledHopOccurrence(occurrence.key());
	}

	public boolean isCompiledHopOccurrence(CompiledHopKey key) {
		NodeKind kind = graph.node(Objects.requireNonNull(key, "key"))
			.orElseThrow(() -> new IllegalArgumentException("Key is not owned by this placement analysis"))
			.kind();
		return kind != NodeKind.FUNCTION_INPUT && kind != NodeKind.FUNCTION_OUTPUT;
	}

	public List<StatementBlock> topLevelStatementBlocks() {
		return topLevelStatementBlocks;
	}

	public Optional<Hop> hop(CompiledHopKey key) {
		return Optional.ofNullable(hopsByKey.get(Objects.requireNonNull(key, "key")));
	}

	public Optional<NodeShapeFact> shapeFact(CompiledHopKey key) {
		return shapeFacts.shapeFact(key);
	}

	public String analysisFingerprint() {
		return analysisFingerprint;
	}

	public HeuristicPolicyFacts heuristicPolicyFacts() {
		return heuristicPolicyFacts;
	}

	public CandidateRuleDomain candidateRuleDomain() { return candidateRuleDomain; }
	public CandidateRuleFacts candidateRuleFacts() { return candidateRuleFacts; }
	public CandidateConsumerProfileFacts candidateConsumerProfileFacts() { return candidateConsumerProfileFacts; }
	public DetachedConsumerProfileFacts detachedConsumerProfileFacts() { return detachedConsumerProfileFacts; }


	public List<ExecutionFrequencyFact> executionFrequencyFactsInScopeOrder() {
		return executionFrequencyFactsInScopeOrder;
	}

	public ExecutionFrequencyFact requireExactExecutionFrequency(CompiledHopKey key) {
		ExecutionFrequencyFact fact = frequencyFactsByIdentity.get(Objects.requireNonNull(key, "key"));
		if(fact == null)
			throw new IllegalArgumentException("Exact execution frequency fact is missing or foreign");
		return fact;
	}

	public List<ProducerConsumerDemandFact> producerConsumerDemandFactsInCanonicalOrder() {
		return producerConsumerDemandFactsInCanonicalOrder;
	}

	public ProducerConsumerDemandFact requireExactProducerConsumerDemand(CompiledHopKey producer,
		CompiledHopKey consumer, int inputPosition) {
		Map<CompiledHopKey,Map<Integer,ProducerConsumerDemandFact>> byConsumer = demandFactsByIdentity.get(
			Objects.requireNonNull(producer, "producer"));
		Map<Integer,ProducerConsumerDemandFact> byPosition = byConsumer == null ? null
			: byConsumer.get(Objects.requireNonNull(consumer, "consumer"));
		ProducerConsumerDemandFact fact = byPosition == null ? null : byPosition.get(inputPosition);
		if(fact == null)
			throw new IllegalArgumentException("Exact producer-consumer demand fact is missing or foreign");
		return fact;
	}

	public String executionCostFactsFingerprint() {
		return executionCostFactsFingerprint;
	}

	public void assertProgramOwner(DMLProgram program) {
		if(program == null || program != programOwner)
			throw new IllegalArgumentException("Placement analysis is foreign to the supplied program");
	}

	public void assertCanonicalProgramAuthority(DMLProgram program) {
		assertProgramOwner(program);
		program.requirePlacementAnalysisAuthority(this);
	}
}
