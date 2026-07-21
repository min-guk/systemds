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
	private final DMLProgram programOwner;

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		List<StatementBlock> topLevelStatementBlocks, DMLProgram programOwner,
		PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts, List<CandidateRuleKey> candidateRuleDomainKeys,
		List<CandidateRuleFact> candidateRuleFacts,
		List<CandidateConsumerProfileKey> candidateConsumerDomainKeys,
		List<CandidateConsumerProfileFact> candidateConsumerProfileFacts,
		List<DetachedConsumerProfileFact> detachedConsumerProfileFacts,
		List<CompiledInputEdgeFact> compiledInputEdges) {
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
		this.compiledInputEdgesInCanonicalOrder = validateCompiledInputEdges(compiledInputEdges);
		this.inputEdgesByIdentity = indexCompiledInputEdges(this.compiledInputEdgesInCanonicalOrder);
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
			.filter(occurrence -> isCompiledHopOccurrenceKey(occurrence.key())).toList();
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

	private static boolean isCompiledHopOccurrenceKey(CompiledHopKey key) {
		return key.controlRegion().regionPath().stream().noneMatch(part -> part.startsWith("function-boundary:"));
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


	public List<CompiledInputEdgeFact> compiledInputEdgesInCanonicalOrder() {
		return compiledInputEdgesInCanonicalOrder;
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
