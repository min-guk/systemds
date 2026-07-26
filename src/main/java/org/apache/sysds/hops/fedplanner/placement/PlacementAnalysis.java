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
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
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

		public CandidateConsumerProfileFact(CandidateConsumerProfileKey key,
			CandidateEvaluationStatus status, List<FType> allowedTargetTypes, String failureCode) {
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
		}

		public CandidateConsumerProfileKey key() { return key; }
		public CandidateEvaluationStatus status() { return status; }
		public List<FType> allowedTargetTypes() { return allowedTargetTypes; }
		public String failureCode() { return failureCode; }
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
	public record CandidateEmissionFact(PlacementEmissionState emissionState, FType executionFType) {
		public CandidateEmissionFact {
			Objects.requireNonNull(emissionState, "emissionState");
			PlacementState state = emissionState.placementState();
			if(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& executionFType == null)
				throw new IllegalArgumentException("Federated FOUT candidate requires an exact execution FType");
			if(state.execType() != ExecType.FED && executionFType != null)
				throw new IllegalArgumentException("Local candidate emission cannot publish a federated execution FType");
			if(emissionState.derivedFedFout()
				&& (state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT))
				throw new IllegalArgumentException("Derived FOUT authority requires a FED/FOUT emission state");
		}

		public String normalizedSignature() {
			return emissionState.normalizedSignature() + "|executionFType="
				+ (executionFType == null ? "-" : executionFType.name());
		}
	}

	/** One exact immutable rule/profile fact captured by the canonical builder pass. */
	public record CandidateRuleFact(CandidateRuleKey key, CandidateEvaluationStatus status,
		CandidateCapabilityFact capability, CandidateShapeProofFact shapeProof, CandidateProfileFact profile,
		List<CandidateEmissionFact> allowedEmissionFacts, String failureCode) {
		public CandidateRuleFact {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(shapeProof, "shapeProof");
			Objects.requireNonNull(profile, "profile");
			allowedEmissionFacts = List.copyOf(Objects.requireNonNull(allowedEmissionFacts,
				"allowedEmissionFacts"));
			failureCode = failureCode == null ? "" : failureCode;
			if(status == CandidateEvaluationStatus.AVAILABLE
					&& (capability == null || !profile.available() || !failureCode.isEmpty()
						|| allowedEmissionFacts.isEmpty())
				|| status == CandidateEvaluationStatus.RULE_ERROR
					&& (profile.available() || failureCode.isEmpty() || !allowedEmissionFacts.isEmpty())
				|| status == CandidateEvaluationStatus.PROFILE_ERROR
					&& (capability == null || profile.available() || failureCode.isEmpty()
						|| !allowedEmissionFacts.isEmpty()))
				throw new IllegalArgumentException("Candidate rule status and evidence differ");
			Set<PlacementEmissionState> identities = new java.util.HashSet<>();
			for(CandidateEmissionFact fact : allowedEmissionFacts) {
				Objects.requireNonNull(fact, "allowed emission fact");
				if(!identities.add(fact.emissionState()))
					throw new IllegalArgumentException("Duplicate exact candidate emission state");
			}
		}

		public List<PlacementEmissionState> allowedEmissionStates() {
			return allowedEmissionFacts.stream().map(CandidateEmissionFact::emissionState).toList();
		}
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
				throw new IllegalArgumentException("Candidate rule fact/domain count differs");
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
				throw new IllegalArgumentException("Consumer profile fact/domain count differs");
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

	public enum HeuristicPathEdgeKind { COMPILED_INPUT, CFG_TRANSIENT_FORWARD }

	/** Exact occurrence-to-occurrence edge used to prove one local Heuristic prefix. */
	public record HeuristicPathEdgeFact(CompiledHopKey producer, CompiledHopKey consumer,
		int inputPosition, ValueVersionKey sourceValueVersion, ValueVersionKey consumerValueVersion,
		HeuristicPathEdgeKind kind) implements Comparable<HeuristicPathEdgeFact> {
		public HeuristicPathEdgeFact {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0)
				throw new IllegalArgumentException("Heuristic path input position must be non-negative");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(consumerValueVersion, "consumerValueVersion");
			Objects.requireNonNull(kind, "kind");
		}

		@Override public int compareTo(HeuristicPathEdgeFact that) {
			int producerOrder = producer.compareTo(that.producer);
			if(producerOrder != 0) return producerOrder;
			int consumerOrder = consumer.compareTo(that.consumer);
			if(consumerOrder != 0) return consumerOrder;
			int positionOrder = Integer.compare(inputPosition, that.inputPosition);
			return positionOrder != 0 ? positionOrder : kind.compareTo(that.kind);
		}
	}

	/**
	 * Exact common-analysis proof for one path-local LOUT-to-FOUT re-entry. The cost is the
	 * neutral selector's modeled distinct-relocation unit, not an unmodeled runtime estimate.
	 */
	public record HeuristicPathwiseReentryFact(CompiledHopKey localProducer,
		ValueVersionKey sourceValueVersion, CompiledHopKey consumer, int inputPosition,
		CompiledHopKey siblingProducer, ValueVersionKey siblingValueVersion, int siblingInputPosition,
		PlacementState siblingFoutState, DurableAnchorKey durableAnchor,
		PlacementState consumerFoutState, CandidateRuleFact runtimeCandidate,
		RelocationActionKey relocationAction, ObligationKey obligation,
		int modeledDistinctRelocationCost) implements Comparable<HeuristicPathwiseReentryFact> {
		public HeuristicPathwiseReentryFact {
			Objects.requireNonNull(localProducer, "localProducer");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(consumer, "consumer");
			if(inputPosition < 0 || siblingInputPosition < 0 || inputPosition == siblingInputPosition)
				throw new IllegalArgumentException("Heuristic re-entry input positions differ and are non-negative");
			Objects.requireNonNull(siblingProducer, "siblingProducer");
			Objects.requireNonNull(siblingValueVersion, "siblingValueVersion");
			Objects.requireNonNull(siblingFoutState, "siblingFoutState");
			Objects.requireNonNull(durableAnchor, "durableAnchor");
			Objects.requireNonNull(consumerFoutState, "consumerFoutState");
			Objects.requireNonNull(runtimeCandidate, "runtimeCandidate");
			Objects.requireNonNull(relocationAction, "relocationAction");
			Objects.requireNonNull(obligation, "obligation");
			if(modeledDistinctRelocationCost != 1)
				throw new IllegalArgumentException("One re-entry fact models one distinct relocation unit");
		}

		@Override public int compareTo(HeuristicPathwiseReentryFact that) {
			int consumerOrder = consumer.compareTo(that.consumer);
			if(consumerOrder != 0) return consumerOrder;
			int positionOrder = Integer.compare(inputPosition, that.inputPosition);
			return positionOrder != 0 ? positionOrder : relocationAction.compareTo(that.relocationAction);
		}
	}

	/** One exact marker-local path projection; no dominance or descendant closure is implied. */
	public record HeuristicPathFact(HeuristicPolicyFact demotion, List<CompiledHopKey> localPrefix,
		List<HeuristicPathEdgeFact> edges, List<HeuristicPathwiseReentryFact> reentries)
		implements Comparable<HeuristicPathFact> {
		public HeuristicPathFact {
			Objects.requireNonNull(demotion, "demotion");
			localPrefix = Objects.requireNonNull(localPrefix, "localPrefix").stream()
				.map(key -> Objects.requireNonNull(key, "local prefix key"))
				.distinct().sorted().toList();
			if(!localPrefix.contains(demotion.producer()))
				throw new IllegalArgumentException("Heuristic local prefix omits its demotion producer");
			edges = Objects.requireNonNull(edges, "edges").stream()
				.map(edge -> Objects.requireNonNull(edge, "path edge")).distinct().sorted().toList();
			reentries = Objects.requireNonNull(reentries, "reentries").stream()
				.map(fact -> Objects.requireNonNull(fact, "re-entry fact"))
				.distinct().sorted().toList();
		}

		@Override public int compareTo(HeuristicPathFact that) {
			return demotion.compareTo(that.demotion);
		}
	}

	/** Deterministic, deeply immutable set of producer-scoped Heuristic demotions. */
	public record HeuristicPolicyFacts(List<HeuristicPolicyFact> demotions, List<HeuristicPathFact> paths) {
		public HeuristicPolicyFacts(List<HeuristicPolicyFact> demotions) {
			this(demotions, List.of());
		}

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
			paths = Objects.requireNonNull(paths, "paths").stream()
				.map(path -> Objects.requireNonNull(path, "Heuristic path fact")).sorted().toList();
			Set<HeuristicPolicyFact> pathDemotions = new java.util.HashSet<>();
			for(HeuristicPathFact path : paths) {
				if(!demotions.contains(path.demotion()))
					throw new IllegalArgumentException("Heuristic path has an unknown demotion");
				if(!pathDemotions.add(path.demotion()))
					throw new IllegalArgumentException("Duplicate Heuristic path demotion");
			}
		}
	}

	public record NodeShapeFact(DataType dataType, long rows, long cols) {
		public NodeShapeFact { Objects.requireNonNull(dataType, "dataType"); }
		public boolean knownPositiveMatrix() { return dataType == DataType.MATRIX && rows > 0 && cols > 0; }
	}
	/** Exact structural matrix input edge between two compiled Hop owners. */
	public static final class CompiledInputEdgeFact {
		private final CompiledHopKey producer;
		private final CompiledHopKey consumer;
		private final int inputPosition;

		CompiledInputEdgeFact(CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) {
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

	/** One analysis-owned logical input carried across an exact CFG transient forward. */
	public record LogicalTransientInputFact(CompiledHopKey sourceWrite, CompiledHopKey targetRead,
		int logicalPosition, ValueVersionKey sourceValueVersion, ValueVersionKey readValueVersion,
		DurableAnchorKey anchor, PlacementState localSourceState, PlacementState federatedSourceState,
		CandidateInputState localInput, CandidateInputState federatedInput) implements Comparable<LogicalTransientInputFact> {
		public LogicalTransientInputFact {
			Objects.requireNonNull(sourceWrite, "sourceWrite");
			Objects.requireNonNull(targetRead, "targetRead");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(readValueVersion, "readValueVersion");
			Objects.requireNonNull(anchor, "anchor");
			Objects.requireNonNull(localSourceState, "localSourceState");
			Objects.requireNonNull(federatedSourceState, "federatedSourceState");
			Objects.requireNonNull(localInput, "localInput");
			Objects.requireNonNull(federatedInput, "federatedInput");
			if(logicalPosition != 0)
				throw new IllegalArgumentException("Transient logical input position must be zero");
		}

		@Override
		public int compareTo(LogicalTransientInputFact that) {
			int readOrder = targetRead.compareTo(that.targetRead);
			if(readOrder != 0) return readOrder;
			int positionOrder = Integer.compare(logicalPosition, that.logicalPosition);
			return positionOrder != 0 ? positionOrder : sourceWrite.compareTo(that.sourceWrite);
		}
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
	private final List<CompiledInputEdgeFact> compiledInputEdgesInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> inputEdgesByIdentity;
	private final List<LogicalTransientInputFact> logicalTransientInputsInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>>> logicalInputsByIdentity;
	private final DMLProgram programOwner;
	private final Map<String,FunctionStatementBlock> namedFunctionStatementBlocks;
	private final Runnable programMutationGuard;
	private final boolean guardedFunctionRoots;

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, List.of(), null);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges, Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, List.of(), programMutationGuard);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges,
		List<LogicalTransientInputFact> logicalTransientInputs, Runnable programMutationGuard) {
		this.graph = Objects.requireNonNull(graph, "graph");
		this.programOwner = programOwner;
		this.programMutationGuard = programMutationGuard == null ? () -> { } : programMutationGuard;
		this.guardedFunctionRoots = programOwner != null && programMutationGuard != null;
		Map<String,FunctionStatementBlock> functions = new java.util.TreeMap<>();
		if(guardedFunctionRoots)
			functions.putAll(programOwner.getNamedNSFunctionStatementBlocks());
		this.namedFunctionStatementBlocks = Collections.unmodifiableMap(functions);
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
		this.compiledInputEdgesInCanonicalOrder = validateCompiledInputEdges(compiledInputEdges);
		this.inputEdgesByIdentity = indexCompiledInputEdges(this.compiledInputEdgesInCanonicalOrder);
		this.logicalTransientInputsInCanonicalOrder = validateLogicalTransientInputs(logicalTransientInputs,
			analysisKeysByIdentity);
		this.logicalInputsByIdentity = indexLogicalTransientInputs(this.logicalTransientInputsInCanonicalOrder);
		for(HeuristicPolicyFact fact : heuristicPolicyFacts.demotions()) {
			NeutralPlacementGraph.Node producer = graph.node(fact.producer()).orElseThrow(() ->
				new IllegalArgumentException("Heuristic policy producer is missing from the analysis graph"));
			if(!producer.valueVersion().equals(fact.valueVersion()))
				throw new IllegalArgumentException("Heuristic policy producer/value pair does not match the analysis graph");
		}
		validateHeuristicPaths(analysisKeysByIdentity);
	}

	private void validateHeuristicPaths(Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		for(HeuristicPathFact path : heuristicPolicyFacts.paths()) {
			for(CompiledHopKey key : path.localPrefix())
				if(!analysisKeysByIdentity.containsKey(key))
					throw new IllegalArgumentException("Heuristic path contains a foreign local-prefix occurrence");
			for(HeuristicPathEdgeFact edge : path.edges()) {
				if(!analysisKeysByIdentity.containsKey(edge.producer())
					|| !analysisKeysByIdentity.containsKey(edge.consumer()))
					throw new IllegalArgumentException("Heuristic path edge contains a foreign occurrence");
				NeutralPlacementGraph.Node producer = graph.node(edge.producer()).orElseThrow();
				NeutralPlacementGraph.Node consumer = graph.node(edge.consumer()).orElseThrow();
				if(producer.valueVersion() != edge.sourceValueVersion()
					|| consumer.valueVersion() != edge.consumerValueVersion())
					throw new IllegalArgumentException("Heuristic path edge value identity differs");
				if(edge.kind() == HeuristicPathEdgeKind.COMPILED_INPUT)
					requireExactCompiledInputEdge(edge.producer(), edge.consumer(), edge.inputPosition());
				else if(producer.kind() != NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE
					|| consumer.kind() != NeutralPlacementGraph.NodeKind.TRANSIENT_READ
					|| edge.inputPosition() != 0)
					throw new IllegalArgumentException("Heuristic CFG edge is not an exact transient forward");
			}
			for(HeuristicPathwiseReentryFact fact : path.reentries())
				validateHeuristicReentry(path, fact, analysisKeysByIdentity);
		}
	}

	private void validateHeuristicReentry(HeuristicPathFact path, HeuristicPathwiseReentryFact fact,
		Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		if(!analysisKeysByIdentity.containsKey(fact.localProducer())
			|| !analysisKeysByIdentity.containsKey(fact.consumer())
			|| !analysisKeysByIdentity.containsKey(fact.siblingProducer()))
			throw new IllegalArgumentException("Heuristic re-entry contains a foreign occurrence");
		if(!path.localPrefix().contains(fact.localProducer()))
			throw new IllegalArgumentException("Heuristic re-entry source is outside its exact local prefix");
		NeutralPlacementGraph.Node local = graph.node(fact.localProducer()).orElseThrow();
		NeutralPlacementGraph.Node sibling = graph.node(fact.siblingProducer()).orElseThrow();
		NeutralPlacementGraph.Node consumer = graph.node(fact.consumer()).orElseThrow();
		if(local.valueVersion() != fact.sourceValueVersion()
			|| sibling.valueVersion() != fact.siblingValueVersion())
			throw new IllegalArgumentException("Heuristic re-entry value identity differs");
		requireExactCompiledInputEdge(fact.localProducer(), fact.consumer(), fact.inputPosition());
		requireExactCompiledInputEdge(fact.siblingProducer(), fact.consumer(), fact.siblingInputPosition());
		if(!sibling.anchors().contains(fact.durableAnchor())
			|| !sibling.legalAlternatives().contains(fact.siblingFoutState())
			|| fact.siblingFoutState().execType() != ExecType.FED
			|| fact.siblingFoutState().output() != FederatedOutput.FOUT
			|| fact.siblingFoutState().fType() != fact.durableAnchor().fType())
			throw new IllegalArgumentException("Heuristic re-entry sibling FOUT authority differs");
		if(!consumer.legalAlternatives().contains(fact.consumerFoutState())
			|| fact.consumerFoutState().execType() != ExecType.FED
			|| fact.consumerFoutState().output() != FederatedOutput.FOUT
			|| fact.consumerFoutState().fType() != fact.durableAnchor().fType())
			throw new IllegalArgumentException("Heuristic re-entry consumer FOUT state differs");
		CandidateRuleFact exactCandidate = candidateRuleFacts.requireExact(fact.consumer(),
			fact.runtimeCandidate().key().orderedInputs());
		if(exactCandidate != fact.runtimeCandidate() || exactCandidate.status() != CandidateEvaluationStatus.AVAILABLE
			|| exactCandidate.capability() == null
			|| exactCandidate.capability().nativeExec() != fact.consumerFoutState().execType()
			|| exactCandidate.capability().nativeOutput() != fact.consumerFoutState().output()
			|| exactCandidate.capability().nativeFoutFType() != fact.consumerFoutState().fType())
			throw new IllegalArgumentException("Heuristic re-entry runtime candidate differs");
		if(!fact.relocationAction().sourceValueVersion().equals(fact.sourceValueVersion())
			|| !fact.relocationAction().targetPlacement().equals(fact.consumerFoutState())
			|| fact.relocationAction().durableAnchor() != fact.durableAnchor()
			|| fact.obligation().consumer() != fact.consumer()
			|| fact.obligation().inputPosition() != fact.inputPosition()
			|| fact.obligation().relocationAction() != fact.relocationAction())
			throw new IllegalArgumentException("Heuristic re-entry relocation obligation differs");
		boolean exactAction = graph.relocationActions().stream().anyMatch(action -> action.key() == fact.relocationAction()
			&& action.obligations().stream().anyMatch(obligation -> obligation == fact.obligation()));
		if(!exactAction)
			throw new IllegalArgumentException("Heuristic re-entry relocation is not analysis-owned");
		if("recompile".equals(fact.localProducer().recompileContext())
			|| "recompile".equals(fact.consumer().recompileContext())
			|| "recompile".equals(fact.siblingProducer().recompileContext()))
			throw new IllegalArgumentException("Heuristic re-entry cannot cross a recompile occurrence");
	}

	private List<LogicalTransientInputFact> validateLogicalTransientInputs(
		List<LogicalTransientInputFact> supplied, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		Objects.requireNonNull(supplied, "logicalTransientInputs");
		List<LogicalTransientInputFact> sorted = supplied.stream()
			.map(fact -> Objects.requireNonNull(fact, "logical transient input fact")).sorted().toList();
		if(!sorted.equals(supplied))
			throw new IllegalArgumentException("Logical transient input facts are not in canonical order");
		Map<CompiledHopKey,Set<Integer>> slots = new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : sorted) {
			if(!analysisKeysByIdentity.containsKey(fact.sourceWrite())
				|| !analysisKeysByIdentity.containsKey(fact.targetRead()))
				throw new IllegalArgumentException("Logical transient input has a foreign occurrence");
			NeutralPlacementGraph.Node source = graph.node(fact.sourceWrite()).orElseThrow();
			NeutralPlacementGraph.Node read = graph.node(fact.targetRead()).orElseThrow();
			if(source.kind() != NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE
				|| read.kind() != NeutralPlacementGraph.NodeKind.TRANSIENT_READ)
				throw new IllegalArgumentException("Logical transient input endpoints have wrong node kinds");
			if(source.valueVersion() != fact.sourceValueVersion() || read.valueVersion() != fact.readValueVersion())
				throw new IllegalArgumentException("Logical transient input value identity differs");
			if(source.anchors().size() != 1 || read.anchors().size() != 1
				|| source.anchors().get(0) != fact.anchor() || !read.anchors().get(0).equals(fact.anchor()))
				throw new IllegalArgumentException("Logical transient input anchor differs");
			if(!hopsByKey.get(fact.targetRead()).getInput().isEmpty())
				throw new IllegalArgumentException("Logical transient read has physical inputs");
			if(source.legalAlternatives().stream().noneMatch(state -> state == fact.localSourceState())
				|| source.legalAlternatives().stream().noneMatch(state -> state == fact.federatedSourceState()))
				throw new IllegalArgumentException("Logical transient input source state is not analysis-owned");
			if(fact.localSourceState().execType() != ExecType.CP
				|| fact.localSourceState().output() != FederatedOutput.LOUT
				|| fact.localSourceState().fType() != null || fact.localSourceState().shapeDependent()
				|| fact.federatedSourceState().execType() != ExecType.FED
				|| fact.federatedSourceState().output() != FederatedOutput.FOUT
				|| fact.federatedSourceState().fType() != fact.anchor().fType()
				|| !fact.localInput().equals(CandidateInputState.absentLocal())
				|| !fact.federatedInput().equals(CandidateInputState.present(fact.anchor().fType())))
				throw new IllegalArgumentException("Logical transient input state semantics differ");
			List<List<CandidateInputState>> expected = List.of(List.of(fact.localInput()), List.of(fact.federatedInput()));
			List<List<CandidateInputState>> actual = candidateRuleDomain.orderedRuleKeys().stream()
				.filter(key -> key.parentOccurrence() == fact.targetRead()).map(CandidateRuleKey::orderedInputs).toList();
			if(!actual.equals(expected))
				throw new IllegalArgumentException("Logical transient candidate domain differs");
			CandidateRuleFact localFact = candidateRuleFacts.requireExact(fact.targetRead(), expected.get(0));
			CandidateRuleFact federatedFact = candidateRuleFacts.requireExact(fact.targetRead(), expected.get(1));
			if(localFact.status() != CandidateEvaluationStatus.AVAILABLE
				|| localFact.capability().nativeExec() != ExecType.CP
				|| localFact.capability().nativeOutput() != FederatedOutput.LOUT
				|| localFact.capability().nativeFoutFType() != null
				|| federatedFact.status() != CandidateEvaluationStatus.AVAILABLE
				|| federatedFact.capability().nativeExec() != ExecType.FED
				|| federatedFact.capability().nativeOutput() != FederatedOutput.FOUT
				|| federatedFact.capability().nativeFoutFType() != fact.anchor().fType())
				throw new IllegalArgumentException("Logical transient candidate capability differs");
			if(read.legalAlternatives().stream().noneMatch(state -> state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.LOUT && state.fType() == null)
				|| read.legalAlternatives().stream().noneMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT && state.fType() == fact.anchor().fType()))
				throw new IllegalArgumentException("Logical transient read legal tuples differ");
			if(compiledInputEdgesInCanonicalOrder.stream().anyMatch(edge -> edge.producer() == fact.sourceWrite()
				&& edge.consumer() == fact.targetRead() && edge.inputPosition() == fact.logicalPosition()))
				throw new IllegalArgumentException("Logical transient input fabricated a physical edge");
			if(!slots.computeIfAbsent(fact.targetRead(), ignored -> new java.util.HashSet<>())
				.add(fact.logicalPosition()))
				throw new IllegalArgumentException("Duplicate logical transient input slot");
		}
		return List.copyOf(sorted);
	}

	private static Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>>>
		indexLogicalTransientInputs(List<LogicalTransientInputFact> facts) {
		Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>>> indexed = new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : facts) {
			Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>> byRead = indexed.computeIfAbsent(
				fact.sourceWrite(), ignored -> new IdentityHashMap<>());
			Map<Integer,LogicalTransientInputFact> byPosition = byRead.computeIfAbsent(fact.targetRead(),
				ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(fact.logicalPosition(), fact) != null)
				throw new IllegalArgumentException("Duplicate logical transient input fact");
		}
		return Collections.unmodifiableMap(indexed);
	}

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		DMLProgram programOwner, PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts) {
		this(graph, occurrences, List.of(), programOwner, shapeFacts, analysisFingerprint, heuristicPolicyFacts,
			candidateRuleDomainKeys, candidateRuleFacts, candidateConsumerDomainKeys, candidateConsumerProfileFacts,
			List.of(), deriveCompiledInputEdges(graph, occurrences));
	}

	/** Compatibility surface for fixtures that predate canonical candidate-fact publication. */
	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		DMLProgram programOwner, PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts) {
		this(graph, occurrences, programOwner, shapeFacts, analysisFingerprint, heuristicPolicyFacts,
			List.of(), List.of(), List.of(), List.of());
	}


	private String canonicalizeSuppliedAnalysisFingerprint(String supplied) {
		if(!supplied.matches("[0-9a-f]{64}"))
			return supplied;
		String graphSignature = graph.normalizedSignature();
		List<String> projectionSignatures = occurrences.stream()
			.map(occurrence -> stableSignature(occurrence.normalizedSignature())).sorted().toList();
		return sha256(stableSignature(graphSignature) + '\n'
			+ String.join("\n", projectionSignatures));
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

	private List<CompiledInputEdgeFact> validateCompiledInputEdges(List<CompiledInputEdgeFact> facts) {
		Objects.requireNonNull(facts, "compiledInputEdges");
		List<CompiledInputEdgeFact> expected = deriveCompiledInputEdges(graph, compiledHopOccurrences());
		if(facts.size() != expected.size())
			throw new IllegalArgumentException("Compiled input edge facts do not exactly cover compiled matrix inputs");
		List<CompiledInputEdgeFact> copied = new java.util.ArrayList<>(facts.size());
		Set<EdgeIdentity> seen = new java.util.HashSet<>();
		for(int i = 0; i < facts.size(); i++) {
			CompiledInputEdgeFact fact = Objects.requireNonNull(facts.get(i), "compiled input edge fact");
			CompiledInputEdgeFact exact = expected.get(i);
			if(fact.producer() != exact.producer() || fact.consumer() != exact.consumer()
				|| fact.inputPosition() != exact.inputPosition())
				throw new IllegalArgumentException("Compiled input edge fact order, identity, or topology differs");
			if(!seen.add(new EdgeIdentity(fact.producer(), fact.consumer(), fact.inputPosition())))
				throw new IllegalArgumentException("Duplicate compiled input edge fact");
			copied.add(fact);
		}
		return List.copyOf(copied);
	}

	private Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> indexCompiledInputEdges(
		List<CompiledInputEdgeFact> facts) {
		Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> indexed = new IdentityHashMap<>();
		for(CompiledInputEdgeFact fact : facts) {
			Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> byConsumer = indexed.computeIfAbsent(
				fact.producer(), ignored -> new IdentityHashMap<>());
			Map<Integer,CompiledInputEdgeFact> byPosition = byConsumer.computeIfAbsent(fact.consumer(),
				ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(fact.inputPosition(), fact) != null)
				throw new IllegalArgumentException("Duplicate compiled input edge fact");
		}
		return Collections.unmodifiableMap(indexed);
	}

	private static List<CompiledInputEdgeFact> deriveCompiledInputEdges(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> occurrences) {
		Objects.requireNonNull(graph, "graph");
		Objects.requireNonNull(occurrences, "occurrences");
		List<HopOccurrenceProjection> compiled = occurrences.stream()
			.filter(occurrence -> isCompiledHopOccurrenceKey(occurrence.key(), graph.node(occurrence.key())
				.orElseThrow(() -> new IllegalArgumentException("Occurrence has a foreign graph key")).kind())).toList();
		Map<CompiledHopKey,HopOccurrenceProjection> occurrencesByIdentity = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : compiled)
			if(occurrencesByIdentity.put(occurrence.key(), occurrence) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop occurrence identity");
		Map<CompiledHopKey,Map<Integer,Constraint>> inputsByConsumer = new IdentityHashMap<>();
		for(Constraint constraint : graph.constraints()) {
			if(constraint.kind() != ConstraintKind.DOMINATES || !"data-input".equals(constraint.evidence()))
				continue;
			HopOccurrenceProjection producer = occurrencesByIdentity.get(constraint.left());
			HopOccurrenceProjection consumer = occurrencesByIdentity.get(constraint.right());
			if(producer == null || consumer == null)
				throw new IllegalArgumentException("Data-input constraint has a foreign compiled owner");
			if(constraint.inputPosition() < 0)
				throw new IllegalArgumentException("Data-input constraint has no exact input position");
			if(producer.hop().getDataType() == null || !producer.hop().getDataType().isMatrix())
				continue;
			Map<Integer,Constraint> byPosition = inputsByConsumer.computeIfAbsent(consumer.key(),
				ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(constraint.inputPosition(), constraint) != null)
				throw new IllegalArgumentException("Compiled consumer input position has multiple producers");
		}
		List<CompiledInputEdgeFact> edges = new java.util.ArrayList<>();
		for(HopOccurrenceProjection consumer : compiled) {
			Map<Integer,Constraint> byPosition = inputsByConsumer.get(consumer.key());
			if(byPosition == null)
				continue;
			byPosition.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
				Constraint constraint = entry.getValue();
				edges.add(new CompiledInputEdgeFact(constraint.left(), constraint.right(), entry.getKey()));
			});
		}
		return List.copyOf(edges);
	}

	private record EdgeIdentity(CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) { }

	static boolean isCompiledHopOccurrenceKey(CompiledHopKey key, NodeKind kind) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(kind, "kind");
		return kind != NodeKind.FUNCTION_INPUT && kind != NodeKind.FUNCTION_OUTPUT
			&& key.controlRegion().regionPath().stream()
				.noneMatch(part -> part.startsWith("function-boundary:"));
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
		return isCompiledHopOccurrenceKey(key, kind);
	}

	public List<StatementBlock> topLevelStatementBlocks() {
		return topLevelStatementBlocks;
	}

	/**
	 * Immutable namespace index over the compiled program's named function roots.
	 * The indexed blocks remain compiler-owned and are protected by the program
	 * structure fingerprint rather than copied into a second semantic universe.
	 */
	public Map<String,FunctionStatementBlock> namedFunctionStatementBlocks() {
		assertProgramStructureUnchanged();
		return namedFunctionStatementBlocks;
	}

	/** Whether named compiler-owned function roots are protected by the analysis guard. */
	public boolean hasGuardedFunctionRoots() {
		return guardedFunctionRoots;
	}

	/** Fail closed if compiler-owned program structure changed after analysis. */
	public void assertProgramStructureUnchanged() {
		programMutationGuard.run();
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


	public List<CompiledInputEdgeFact> compiledInputEdgesInCanonicalOrder() {
		return compiledInputEdgesInCanonicalOrder;
	}

	public List<LogicalTransientInputFact> logicalTransientInputsInCanonicalOrder() {
		return logicalTransientInputsInCanonicalOrder;
	}

	public LogicalTransientInputFact requireExactLogicalTransientInput(CompiledHopKey sourceWrite,
		CompiledHopKey targetRead, int logicalPosition) {
		Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>> byRead = logicalInputsByIdentity.get(
			Objects.requireNonNull(sourceWrite, "sourceWrite"));
		Map<Integer,LogicalTransientInputFact> byPosition = byRead == null ? null
			: byRead.get(Objects.requireNonNull(targetRead, "targetRead"));
		LogicalTransientInputFact fact = byPosition == null ? null : byPosition.get(logicalPosition);
		if(fact == null)
			throw new IllegalArgumentException("Exact logical transient input fact is missing");
		return fact;
	}

	public CompiledInputEdgeFact requireExactCompiledInputEdge(CompiledHopKey producer,
		CompiledHopKey consumer, int inputPosition) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> byConsumer = inputEdgesByIdentity.get(
			Objects.requireNonNull(producer, "producer"));
		Map<Integer,CompiledInputEdgeFact> byPosition = byConsumer == null ? null
			: byConsumer.get(Objects.requireNonNull(consumer, "consumer"));
		CompiledInputEdgeFact fact = byPosition == null ? null : byPosition.get(inputPosition);
		if(fact == null)
			throw new IllegalArgumentException("Exact compiled input edge fact is missing or foreign");
		return fact;
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
