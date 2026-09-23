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
import java.util.ArrayList;
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
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Immutable result of constructing one neutral placement universe for a compiled program. */
public final class PlacementAnalysis {
	private static final class SharedCanonicalList<T> extends java.util.AbstractList<T>
		implements java.util.RandomAccess {
		private final List<T> values;
		private SharedCanonicalList(List<T> values) { this.values = values; }
		@Override public T get(int index) { return values.get(index); }
		@Override public int size() { return values.size(); }
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<? super T>> List<T> canonicalComparableList(
		java.util.Collection<T> values, String label) {
		Objects.requireNonNull(values, label + "s");
		if(values instanceof SharedCanonicalList<?>)
			return (List<T>) values;
		if(values.isEmpty())
			return List.of();
		if(values.size() == 1)
			return List.of(Objects.requireNonNull(values.iterator().next(), label));
		SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
		if(metrics != null)
			metrics.recordCanonicalSort(values.size());
		List<CanonicalEntry<T>> decorated = new ArrayList<>(values.size());
		CanonicalTextContext textContext = new CanonicalTextContext();
		for(T value : values) {
			if(metrics != null)
				metrics.recordCanonicalOrderingKey();
			decorated.add(new CanonicalEntry<>(Objects.requireNonNull(value, label),
				canonicalOrderingKey(value, textContext)));
		}
		// Do not ask Comparable for the same recursively serialized signature O(log n)
		// times.  The legacy lexical bytes remain the ordering contract, but each
		// value materializes them at most once for this canonicalization boundary.
		decorated.sort((left, right) -> {
			if(metrics != null)
				metrics.recordCanonicalComparison();
			return left.orderingKey().compareTo(right.orderingKey());
		});
		List<T> canonical = decorated.stream().map(CanonicalEntry<T>::value)
			.collect(java.util.stream.Collectors.toCollection(
				() -> new ArrayList<>(decorated.size())));
		for(int i = 1; i < canonical.size(); i++)
			if(canonical.get(i - 1).equals(canonical.get(i)))
				throw new IllegalArgumentException("Duplicate " + label);
		return List.copyOf(canonical);
	}

	private record CanonicalEntry<T>(T value, CanonicalText orderingKey) { }

	/**
	 * Immutable segmented UTF-16 text used only for canonical comparison. It keeps
	 * shared child signatures as segments and therefore does not allocate the full
	 * recursively concatenated clause/realization string on an intermediate sort.
	 */
	private static final class CanonicalText implements Comparable<CanonicalText> {
		private final List<Object> pieces;
		private final int length;

		private CanonicalText(List<Object> pieces) {
			this.pieces = List.copyOf(pieces);
			long total = 0;
			for(Object piece : pieces)
				total += piece instanceof String text ? text.length() : ((CanonicalText) piece).length;
			if(total > Integer.MAX_VALUE)
				throw new IllegalArgumentException("Canonical ordering text exceeds JVM string length");
			length = (int) total;
		}

		private static CanonicalText literal(String value) {
			return new CanonicalText(value.isEmpty() ? List.of() : List.of(value));
		}

		@Override public int compareTo(CanonicalText that) {
			if(this == that)
				return 0;
			CanonicalTextCursor leftCursor = new CanonicalTextCursor(this);
			CanonicalTextCursor rightCursor = new CanonicalTextCursor(that);
			int compared = 0;
			while(compared < length && compared < that.length) {
				int shared = leftCursor.skipSharedSubtree(rightCursor);
				if(shared != 0) {
					compared += shared;
					continue;
				}
				String leftText = leftCursor.text();
				String rightText = rightCursor.text();
				// Structural caches deliberately share immutable child signatures. When
				// two texts reach the same whole segment, its complete UTF-16 contents
				// are equal by identity and need not be rescanned character by character.
				if(leftCursor.offset() == 0 && rightCursor.offset() == 0 && leftText == rightText) {
					compared += leftText.length();
					leftCursor.skipText();
					rightCursor.skipText();
					continue;
				}
				char left = leftCursor.nextCharacter();
				char right = rightCursor.nextCharacter();
				if(left != right)
					return Character.compare(left, right);
				compared++;
			}
			return Integer.compare(length, that.length);
		}
	}

	/** Depth-first cursor over a shared CanonicalText rope; no flattened segment list is created. */
	private static final class CanonicalTextCursor {
		private final java.util.ArrayDeque<CanonicalTextFrame> stack = new java.util.ArrayDeque<>();
		private String text;
		private int offset;

		private CanonicalTextCursor(CanonicalText root) {
			stack.addLast(new CanonicalTextFrame(root));
			advanceText();
		}

		private String text() { return text; }
		private int offset() { return offset; }

		private char nextCharacter() {
			char value = text.charAt(offset++);
			if(offset == text.length())
				advanceText();
			return value;
		}

		private void skipText() { advanceText(); }

		/** Skip a shared immutable rope suffix only when both cursors are at the same position in it. */
		private int skipSharedSubtree(CanonicalTextCursor that) {
			if(text == null || text != that.text || offset != that.offset)
				return 0;
			var left = stack.descendingIterator();
			var right = that.stack.descendingIterator();
			int frames = 0;
			int remaining = text.length() - offset;
			while(left.hasNext() && right.hasNext()) {
				CanonicalTextFrame leftFrame = left.next();
				CanonicalTextFrame rightFrame = right.next();
				if(leftFrame.value != rightFrame.value || leftFrame.index != rightFrame.index)
					break;
				for(int index = leftFrame.index; index < leftFrame.value.pieces.size(); index++) {
					Object piece = leftFrame.value.pieces.get(index);
					remaining += piece instanceof String literal ? literal.length() : ((CanonicalText) piece).length;
				}
				frames++;
			}
			if(frames == 0)
				return 0;
			for(int index = 0; index < frames; index++) {
				stack.removeLast();
				that.stack.removeLast();
			}
			advanceText();
			that.advanceText();
			return remaining;
		}

		private void advanceText() {
			text = null;
			offset = 0;
			while(!stack.isEmpty()) {
				CanonicalTextFrame frame = stack.peekLast();
				if(frame.index == frame.value.pieces.size()) {
					stack.removeLast();
					continue;
				}
				Object piece = frame.value.pieces.get(frame.index++);
				if(piece instanceof String literal) {
					text = literal;
					return;
				}
				stack.addLast(new CanonicalTextFrame((CanonicalText) piece));
			}
		}
	}

	private static final class CanonicalTextFrame {
		private final CanonicalText value;
		private int index;
		private CanonicalTextFrame(CanonicalText value) { this.value = value; }
	}

	private static final class CanonicalTextBuilder {
		private final List<Object> pieces = new ArrayList<>();

		private CanonicalTextBuilder append(String value) {
			if(!value.isEmpty())
				pieces.add(value);
			return this;
		}

		private CanonicalTextBuilder append(CanonicalText value) {
			if(value.length != 0)
				pieces.add(value);
			return this;
		}

		private CanonicalTextBuilder appendFields(CanonicalText... values) {
			for(int index = 0; index < values.length; index++) {
				if(index > 0)
					append("|");
				CanonicalText value = values[index];
				append(Integer.toString(value.length)).append(":").append(value);
			}
			return this;
		}

		private CanonicalText build() { return new CanonicalText(pieces); }
	}

	private static final class CanonicalTextContext {
		private final IdentityHashMap<Object,CanonicalText> values = new IdentityHashMap<>();
		private CanonicalText get(Object key) { return values.get(key); }
		private CanonicalText put(Object key, CanonicalText value) {
			values.put(key, value);
			return value;
		}
	}

	/** Compares the exact UTF-16 lexical bytes produced by PlacementIdentity.fields without concatenation. */
	static int compareLengthPrefixedFieldSequences(List<String> left, List<String> right) {
		int common = Math.min(left.size(), right.size());
		for(int index = 0; index < common; index++) {
			String leftValue = Objects.requireNonNull(left.get(index), "left field");
			String rightValue = Objects.requireNonNull(right.get(index), "right field");
			int order = (Integer.toString(leftValue.length()) + ':').compareTo(
				Integer.toString(rightValue.length()) + ':');
			if(order != 0)
				return order;
			order = leftValue.compareTo(rightValue);
			if(order != 0)
				return order;
		}
		return Integer.compare(left.size(), right.size());
	}

	private static CanonicalText canonicalOrderingKey(Object value, CanonicalTextContext context) {
		CanonicalText cached = context.get(value);
		if(cached != null)
			return cached;
		CanonicalText computed;
		if(value instanceof PlacementProofKey proof)
			computed = canonicalProofOrderingText(proof, context);
		else if(value instanceof CandidateRealizationReference reference)
			computed = canonicalReferenceOrderingText(reference, context);
		else if(value instanceof CandidateRealizationInputBinding binding)
			computed = canonicalBindingOrderingText(binding, context);
		else if(value instanceof PlacementRealizationKey realizationKey)
			computed = canonicalRealizationKeyOrderingText(realizationKey, context);
		else if(value instanceof CandidateRealizationSupportClause clause)
			computed = canonicalClauseOrderingText(clause, context);
		else if(value instanceof CandidateEmissionRealization realization)
			computed = canonicalRealizationOrderingText(realization, context);
		else if(value instanceof TransientCompatibilityProof proof)
			computed = CanonicalText.literal(proof.normalizedSignature());
		else if(value instanceof TransientPlacementCompatibility compatibility)
			computed = CanonicalText.literal(compatibility.normalizedSignature());
		else
			throw new IllegalArgumentException("Unsupported canonical comparable type "
				+ value.getClass().getName());
		return context.put(value, computed);
	}

	/** Package-visible differential-test seam for the exact segmented sort contract. */
	static int compareCanonicalOrdering(Object left, Object right) {
		CanonicalTextContext context = new CanonicalTextContext();
		return canonicalOrderingKey(Objects.requireNonNull(left, "left canonical value"), context)
			.compareTo(canonicalOrderingKey(Objects.requireNonNull(right, "right canonical value"), context));
	}

	/** One sort-scoped rope cache; avoids rebuilding structural text on every comparator call. */
	static <T> java.util.Comparator<T> canonicalComparator() {
		CanonicalTextContext context = new CanonicalTextContext();
		return (left, right) -> canonicalOrderingKey(
			Objects.requireNonNull(left, "left canonical value"), context).compareTo(canonicalOrderingKey(
				Objects.requireNonNull(right, "right canonical value"), context));
	}

	private static int canonicalOrderingLength(Object value) {
		return canonicalOrderingKey(Objects.requireNonNull(value, "canonical value"),
			new CanonicalTextContext()).length;
	}

	private static CanonicalText canonicalClauseOrderingText(
		CandidateRealizationSupportClause clause, CanonicalTextContext context) {
		CanonicalTextBuilder text = new CanonicalTextBuilder().append("proofs=[");
		for(int index = 0; index < clause.proofDependencies().size(); index++) {
			if(index > 0) text.append(", ");
			text.append(canonicalOrderingKey(clause.proofDependencies().get(index), context));
		}
		text.append("]|inputs=[");
		for(int index = 0; index < clause.inputBindings().size(); index++) {
			if(index > 0) text.append(", ");
			text.append(canonicalOrderingKey(clause.inputBindings().get(index), context));
		}
		text.append("]|nativePool=");
		if(clause.nativeWorkerPoolWitness() == null)
			text.append("-");
		else {
			text.append(clause.nativeWorkerPoolWitness().normalizedSignature());
			if(!clause.nativeWorkerPoolLayoutExact())
				text.append("|nativePoolLayout=dynamic");
		}
		return text.build();
	}

	private static CanonicalText canonicalRealizationOrderingText(
		CandidateEmissionRealization realization, CanonicalTextContext context) {
		CanonicalTextBuilder text = new CanonicalTextBuilder()
			.append(canonicalOrderingKey(realization.key(), context)).append("|support=[");
		for(int index = 0; index < realization.supportClauses().size(); index++) {
			if(index > 0) text.append(", ");
			text.append(canonicalOrderingKey(realization.supportClauses().get(index), context));
		}
		return text.append("]").build();
	}

	private static CanonicalText canonicalProofOrderingText(PlacementProofKey proof,
		CanonicalTextContext context) {
		return new CanonicalTextBuilder().appendFields(
			CanonicalText.literal(proof.kind().name()),
			CanonicalText.literal(proof.owner() == null ? "-" : proof.owner().normalizedSignature()),
			CanonicalText.literal(proof.authoritySignature())).build();
	}

	private static CanonicalText canonicalReferenceOrderingText(
		CandidateRealizationReference reference, CanonicalTextContext context) {
		return new CanonicalTextBuilder().appendFields(
			canonicalRuleOrderingText(reference.rule(), context),
			canonicalOrderingKey(reference.realization(), context)).build();
	}

	private static CanonicalText canonicalRuleOrderingText(CandidateRuleKey rule,
		CanonicalTextContext context) {
		CanonicalText cached = context.get(rule);
		if(cached != null)
			return cached;
		CanonicalTextBuilder text = new CanonicalTextBuilder()
			.append(rule.parentOccurrence().normalizedSignature()).append("|inputs=[");
		for(int index = 0; index < rule.orderedInputs().size(); index++) {
			if(index > 0) text.append(", ");
			text.append(rule.orderedInputs().get(index).normalizedSignature());
		}
		return context.put(rule, text.append("]").build());
	}

	private static CanonicalText canonicalRealizationKeyOrderingText(PlacementRealizationKey key,
		CanonicalTextContext context) {
		return new CanonicalTextBuilder().appendFields(
			canonicalEmissionOrderingText(key.emissionState(), context),
			CanonicalText.literal(key.layoutKind().name()),
			CanonicalText.literal(key.durableAnchor() == null ? "-"
				: key.durableAnchor().normalizedSignature()),
			CanonicalText.literal(key.nativeLineage() == null ? "-" : key.nativeLineage())).build();
	}

	private static CanonicalText canonicalEmissionOrderingText(PlacementEmissionState emission,
		CanonicalTextContext context) {
		CanonicalText cached = context.get(emission);
		if(cached != null)
			return cached;
		return context.put(emission, new CanonicalTextBuilder()
			.append(emission.placementState().normalizedSignature())
			.append("|derivedFedFout=").append(Boolean.toString(emission.derivedFedFout())).build());
	}

	private static CanonicalText canonicalBindingOrderingText(
		CandidateRealizationInputBinding binding, CanonicalTextContext context) {
		CanonicalText action = binding.relocationAction() == null ? CanonicalText.literal("-")
			: canonicalRelocationActionOrderingText(binding.relocationAction(), context);
		return new CanonicalTextBuilder().appendFields(
			CanonicalText.literal(Integer.toString(binding.inputPosition())),
			canonicalOrderingKey(binding.source(), context),
			CanonicalText.literal(binding.kind().name()), action).build();
	}

	private static CanonicalText canonicalRelocationActionOrderingText(RelocationActionKey action,
		CanonicalTextContext context) {
		CanonicalText cached = context.get(action);
		if(cached != null)
			return cached;
		CanonicalTextBuilder consumers = new CanonicalTextBuilder();
		for(int index = 0; index < action.compatibleConsumers().size(); index++) {
			if(index > 0) consumers.append(",");
			CanonicalText consumer = CanonicalText.literal(
				action.compatibleConsumers().get(index).normalizedSignature());
			consumers.append(Integer.toString(consumer.length)).append(":").append(consumer);
		}
		return context.put(action, new CanonicalTextBuilder().appendFields(
			CanonicalText.literal(action.sourceValueVersion().normalizedSignature()),
			CanonicalText.literal(action.targetPlacement().normalizedSignature()),
			CanonicalText.literal(action.materializationFType().name()),
			CanonicalText.literal(action.durableAnchor().normalizedSignature()),
			CanonicalText.literal(action.statementBlockScope()), consumers.build()).build());
	}

	static <T extends Comparable<? super T>> List<T> sharedCanonicalComparableList(
		java.util.Collection<T> values, String label) {
		return new SharedCanonicalList<>(canonicalComparableList(values, label));
	}

	static <T extends Comparable<? super T>> List<T> sharedAlreadyCanonicalComparableList(
		List<T> values, String label) {
		Objects.requireNonNull(values, label + "s");
		List<T> copy = new ArrayList<>(values.size());
		for(T value : values)
			copy.add(Objects.requireNonNull(value, label));
		for(int index = 1; index < copy.size(); index++)
			if(copy.get(index - 1).equals(copy.get(index)))
				throw new IllegalArgumentException("Duplicate " + label);
		return new SharedCanonicalList<>(List.copyOf(copy));
	}

	public enum InputPresence { ABSENT_LOCAL, PRESENT }
	public enum CoordinatorInputAccess { PAYLOAD, FEDERATION_MAP_METADATA }

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
		public String normalizedSignature() {
			return presence.name() + ':' + (fType == null ? "-" : fType.name());
		}
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
		public String normalizedSignature() {
			String cached = PlacementIdentity.cachedSignature(this);
			return cached != null ? cached : PlacementIdentity.rememberSignature(this,
				parentOccurrence.normalizedSignature() + "|inputs=" + orderedInputs.stream()
					.map(CandidateInputState::normalizedSignature).toList());
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

	public enum CandidateEvaluationStatus { AVAILABLE, PRIVACY_EXCLUDED, RULE_ERROR, PROFILE_ERROR }

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

	/** One conjunctive proof/input-support route for an exact physical realization. */
	public record CandidateRealizationSupportClause(List<PlacementProofKey> proofDependencies,
		List<CandidateRealizationInputBinding> inputBindings,
		DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact)
		implements Comparable<CandidateRealizationSupportClause> {
		public CandidateRealizationSupportClause(List<PlacementProofKey> proofDependencies,
			List<CandidateRealizationInputBinding> inputBindings) {
			this(proofDependencies, inputBindings, null, true);
		}
		public CandidateRealizationSupportClause(List<PlacementProofKey> proofDependencies,
			List<CandidateRealizationInputBinding> inputBindings,
			DurableAnchorKey nativeWorkerPoolWitness) {
			this(proofDependencies, inputBindings, nativeWorkerPoolWitness, true);
		}
		public CandidateRealizationSupportClause {
			proofDependencies = canonicalComparableList(proofDependencies, "realization proof dependency");
			inputBindings = canonicalComparableList(inputBindings, "realization input binding");
			if(nativeWorkerPoolWitness == null && !nativeWorkerPoolLayoutExact)
				throw new IllegalArgumentException("Dynamic native layout requires a worker-pool witness");
			if(nativeWorkerPoolWitness != null && proofDependencies.stream().noneMatch(proof ->
				proof.kind() == PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY
					&& proof.owner() != null))
				throw new IllegalArgumentException(
					"Native worker-pool witness requires owned native-continuity proof");
		}
		public List<CandidateRealizationReference> requiredInputSupport() {
			return inputBindings.stream().map(CandidateRealizationInputBinding::source)
				.distinct().sorted().toList();
		}
		public String normalizedSignature() {
			String cached = PlacementIdentity.cachedSignature(this);
			return cached != null ? cached : PlacementIdentity.rememberSignature(this,
				"proofs=" + proofDependencies.stream().map(PlacementProofKey::normalizedSignature).toList()
				+ "|inputs=" + inputBindings.stream()
					.map(CandidateRealizationInputBinding::normalizedSignature).toList()
				+ "|nativePool=" + (nativeWorkerPoolWitness == null ? "-"
					: nativeWorkerPoolWitness.normalizedSignature())
				+ (nativeWorkerPoolWitness != null && !nativeWorkerPoolLayoutExact
					? "|nativePoolLayout=dynamic" : ""));
		}
		@Override public int compareTo(CandidateRealizationSupportClause that) {
			return compareCanonicalOrdering(this, that);
		}
	}

	/** One exact physical layout with alternative executable support clauses. */
	public record CandidateEmissionRealization(PlacementRealizationKey key,
		List<CandidateRealizationSupportClause> supportClauses)
		implements Comparable<CandidateEmissionRealization> {
		public CandidateEmissionRealization(PlacementRealizationKey key,
			List<PlacementProofKey> proofs, List<CandidateRealizationInputBinding> inputBindings) {
			this(key, List.of(new CandidateRealizationSupportClause(proofs, inputBindings)));
		}
		public CandidateEmissionRealization {
			Objects.requireNonNull(key, "key");
			supportClauses = canonicalComparableList(supportClauses, "realization support clause");
			if(supportClauses.isEmpty())
				throw new IllegalArgumentException("Candidate realization requires support authority");
			for(CandidateRealizationSupportClause clause : supportClauses)
				if(clause.nativeWorkerPoolWitness() != null
					&& (key.layoutKind() != PlacementLayoutKind.NATIVE_LINEAGE
						|| key.emissionState().placementState().fType()
							!= clause.nativeWorkerPoolWitness().fType()))
					throw new IllegalArgumentException(
						"Native worker-pool witness and realization layout differ");
			DurableAnchorKey nativeWitness = supportClauses.get(0).nativeWorkerPoolWitness();
			boolean nativeLayoutExact = supportClauses.get(0).nativeWorkerPoolLayoutExact();
			for(CandidateRealizationSupportClause clause : supportClauses) {
				DurableAnchorKey candidate = clause.nativeWorkerPoolWitness();
				if((nativeWitness == null) != (candidate == null)
					|| nativeWitness != null && (nativeLayoutExact != clause.nativeWorkerPoolLayoutExact()
						|| !(nativeLayoutExact
							? PlacementIdentity.samePhysicalLayout(nativeWitness, candidate)
							: PlacementIdentity.samePhysicalWorkerEndpoints(nativeWitness, candidate))))
					throw new IllegalArgumentException(
						"One realization cannot mix unproven or physically distinct native worker pools");
			}
		}

		/**
		 * Reuses a stable-order subset or equality-preserving map of an already
		 * canonical support list. Callers must not use this for newly unordered rows.
		 */
		static CandidateEmissionRealization fromAlreadyCanonicalSupportClauses(
			PlacementRealizationKey key, List<CandidateRealizationSupportClause> supportClauses) {
			return new CandidateEmissionRealization(key, sharedAlreadyCanonicalComparableList(
				supportClauses, "realization support clause"));
		}

		public static CandidateEmissionRealization local(PlacementEmissionState emission) {
			return new CandidateEmissionRealization(PlacementRealizationKey.local(emission), List.of(), List.of());
		}
		public static CandidateEmissionRealization local(PlacementEmissionState emission,
			List<PlacementProofKey> proofs, List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.local(emission), proofs, inputBindings);
		}

		public static CandidateEmissionRealization durable(PlacementEmissionState emission,
			DurableAnchorKey anchor, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.durable(emission, anchor),
				proofs, inputBindings);
		}

		public static CandidateEmissionRealization sourceLineage(PlacementEmissionState emission,
			String sourceIdentity) {
			return new CandidateEmissionRealization(
				PlacementRealizationKey.sourceLineage(emission, sourceIdentity), List.of(), List.of());
		}
		public static CandidateEmissionRealization sourceLineage(PlacementEmissionState emission,
			String sourceIdentity, List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(
				PlacementRealizationKey.sourceLineage(emission, sourceIdentity), List.of(), inputBindings);
		}

		public static CandidateEmissionRealization nativeLineage(PlacementEmissionState emission,
			String lineage, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.nativeLineage(emission, lineage),
				proofs, inputBindings);
		}
		public static CandidateEmissionRealization nativeLineage(PlacementEmissionState emission,
			String lineage, DurableAnchorKey nativeWorkerPoolWitness, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.nativeLineage(emission, lineage),
				List.of(new CandidateRealizationSupportClause(
					proofs, inputBindings, nativeWorkerPoolWitness)));
		}
		public static CandidateEmissionRealization nativeLineageDynamicLayout(PlacementEmissionState emission,
			String lineage, DurableAnchorKey nativeWorkerPoolWitness, List<PlacementProofKey> proofs,
			List<CandidateRealizationInputBinding> inputBindings) {
			return new CandidateEmissionRealization(PlacementRealizationKey.nativeLineage(emission, lineage),
				List.of(new CandidateRealizationSupportClause(
					proofs, inputBindings, nativeWorkerPoolWitness, false)));
		}

		public CandidateRealizationSupportClause requireSingletonSupportClause() {
			if(supportClauses.size() != 1)
				throw new IllegalArgumentException("Candidate realization support clause is ambiguous");
			return supportClauses.get(0);
		}
		/** Compatibility fast path; callers handling alternatives must iterate supportClauses(). */
		public List<PlacementProofKey> proofDependencies() {
			return requireSingletonSupportClause().proofDependencies();
		}
		/** Compatibility fast path; callers handling alternatives must iterate supportClauses(). */
		public List<CandidateRealizationInputBinding> inputBindings() {
			return requireSingletonSupportClause().inputBindings();
		}
		/** Compatibility fast path retained only for singleton support authority. */
		public List<CandidateRealizationReference> requiredInputSupport() {
			return requireSingletonSupportClause().requiredInputSupport();
		}

		public PlacementState placementState() { return key.emissionState().placementState(); }
		public DurableAnchorKey anchor() { return key.durableAnchor(); }
		/** Exact runtime worker-pool layout, including ROW/COL partition-axis ranges. */
		public DurableAnchorKey provenWorkerPool(CandidateRealizationSupportClause clause) {
			if(supportClauses.stream().noneMatch(candidate -> candidate == clause))
				throw new IllegalArgumentException("Support clause is not owned by realization");
			return key.durableAnchor() != null ? key.durableAnchor()
				: clause.nativeWorkerPoolLayoutExact() ? clause.nativeWorkerPoolWitness() : null;
		}
		/** Native worker residency proof. Dynamic-layout witnesses prove endpoints/FType only. */
		public DurableAnchorKey nativeWorkerPoolResidencyWitness(CandidateRealizationSupportClause clause) {
			if(supportClauses.stream().noneMatch(candidate -> candidate == clause))
				throw new IllegalArgumentException("Support clause is not owned by realization");
			return key.durableAnchor() != null ? key.durableAnchor() : clause.nativeWorkerPoolWitness();
		}
		public boolean nativeWorkerPoolLayoutExact(CandidateRealizationSupportClause clause) {
			if(supportClauses.stream().noneMatch(candidate -> candidate == clause))
				throw new IllegalArgumentException("Support clause is not owned by realization");
			return key.durableAnchor() != null || clause.nativeWorkerPoolLayoutExact();
		}
		/** Linear bulk query used instead of repeating the public identity guard for every owned clause. */
		boolean allOwnedSupportClausesHaveExactNativeLayout() {
			return key.durableAnchor() != null
				|| supportClauses.stream().allMatch(CandidateRealizationSupportClause::nativeWorkerPoolLayoutExact);
		}
		/** Package-internal fast path; caller must obtain {@code clause} by iterating {@link #supportClauses()}. */
		DurableAnchorKey provenWorkerPoolForOwnedClause(CandidateRealizationSupportClause clause) {
			return key.durableAnchor() != null ? key.durableAnchor()
				: clause.nativeWorkerPoolLayoutExact() ? clause.nativeWorkerPoolWitness() : null;
		}
		/** Package-internal fast path; caller must obtain {@code clause} by iterating {@link #supportClauses()}. */
		DurableAnchorKey nativeWorkerPoolResidencyForOwnedClause(CandidateRealizationSupportClause clause) {
			return key.durableAnchor() != null ? key.durableAnchor() : clause.nativeWorkerPoolWitness();
		}
		/** Package-internal fast path; caller must obtain {@code clause} by iterating {@link #supportClauses()}. */
		boolean nativeWorkerPoolLayoutExactForOwnedClause(CandidateRealizationSupportClause clause) {
			return key.durableAnchor() != null || clause.nativeWorkerPoolLayoutExact();
		}
		public String normalizedSignature() {
			String cached = PlacementIdentity.cachedSignature(this);
			return cached != null ? cached : PlacementIdentity.rememberSignature(this,
				key.normalizedSignature() + "|support=" + supportClauses.stream()
					.map(CandidateRealizationSupportClause::normalizedSignature).toList());
		}
		@Override public int compareTo(CandidateEmissionRealization that) {
			return compareCanonicalOrdering(this, that);
		}
	}

	/** One exact immutable rule/profile emission with its executable physical realizations. */
	public record CandidateEmissionFact(PlacementEmissionState emissionState, FType executionFType,
		DerivedFoutMaterializationActionKey derivedFoutAction,
		List<CandidateEmissionRealization> realizations) {
		public CandidateEmissionFact(PlacementEmissionState emissionState, FType executionFType) {
			this(emissionState, executionFType, null, defaultRealizations(emissionState));
		}
		public CandidateEmissionFact(PlacementEmissionState emissionState, FType executionFType,
			DerivedFoutMaterializationActionKey derivedFoutAction) {
			this(emissionState, executionFType, derivedFoutAction, defaultRealizations(emissionState));
		}
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
			boolean cpFout = state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT;
			if(emissionState.derivedFedFout() && derivedFoutAction == null)
				throw new IllegalArgumentException("Derived FED/FOUT emission requires one exact materialization action");
			if(derivedFoutAction != null && !emissionState.derivedFedFout() && !cpFout)
				throw new IllegalArgumentException(
					"Only CP/FOUT or derived FED/FOUT emissions may carry an output materialization action");
			if(derivedFoutAction != null && (derivedFoutAction.targetPlacement() != state
				|| derivedFoutAction.materializationFType() != state.fType()))
				throw new IllegalArgumentException("FOUT materialization action and emission identities differ");
			realizations = mergeRealizations(realizations);
			if(realizations.isEmpty())
				throw new IllegalArgumentException("Candidate emission requires at least one physical realization");
			for(CandidateEmissionRealization realization : realizations)
				if(!realization.key().emissionState().equals(emissionState))
					throw new IllegalArgumentException("Candidate realization belongs to a different emission");
		}

		private static List<CandidateEmissionRealization> mergeRealizations(
			java.util.Collection<CandidateEmissionRealization> alternatives) {
			Objects.requireNonNull(alternatives, "candidate emission realizations");
			// A realization already owns a canonical clause list. Keep that complete
			// authority (including every OR clause) without rebuilding sorted sets.
			if(alternatives.size() == 1)
				return List.of(Objects.requireNonNull(alternatives.iterator().next(),
					"candidate emission realization"));
			SearchSpaceMetrics metrics = PlacementIdentity.activeMetrics();
			Map<PlacementRealizationKey,Map<Object,CandidateRealizationSupportClause>> clausesByKey =
				new java.util.LinkedHashMap<>();
			Map<PlacementRealizationKey,CandidateEmissionRealization> firstByKey =
				new java.util.LinkedHashMap<>();
			Set<PlacementRealizationKey> repeated = new java.util.HashSet<>();
			for(CandidateEmissionRealization realization : alternatives) {
				Objects.requireNonNull(realization, "candidate emission realization");
				if(metrics != null)
					metrics.recordRealizationMergeInput();
				if(firstByKey.putIfAbsent(realization.key(), realization) != null)
					repeated.add(realization.key());
				Map<Object,CandidateRealizationSupportClause> clauses = clausesByKey.computeIfAbsent(
					realization.key(), ignored -> new java.util.LinkedHashMap<>());
				for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
					Integer structuralHandle = PlacementIdentity.structuralHandle(clause);
					Object key = structuralHandle == null ? clause : structuralHandle;
					boolean unique = clauses.putIfAbsent(key, clause) == null;
					if(metrics != null)
						metrics.recordRealizationMergeClause(unique);
				}
			}
			List<CandidateEmissionRealization> merged = new ArrayList<>(clausesByKey.size());
			for(Map.Entry<PlacementRealizationKey,Map<Object,CandidateRealizationSupportClause>> entry :
				clausesByKey.entrySet())
				if(!repeated.contains(entry.getKey())
					|| entry.getValue().size() == firstByKey.get(entry.getKey()).supportClauses().size()) {
					merged.add(firstByKey.get(entry.getKey()));
					if(metrics != null)
						metrics.recordRealizationMergeReuse();
				}
				else
					merged.add(new CandidateEmissionRealization(entry.getKey(),
						List.copyOf(entry.getValue().values())));
			return canonicalComparableList(merged, "candidate emission realization");
		}

		private static List<CandidateEmissionRealization> defaultRealizations(PlacementEmissionState emission) {
			Objects.requireNonNull(emission, "emissionState");
			return emission.placementState().output() == FederatedOutput.LOUT
				? List.of(CandidateEmissionRealization.local(emission))
				: List.of(CandidateEmissionRealization.nativeLineage(emission,
					"candidate-emission:" + emission.normalizedSignature(), List.of(), List.of()));
		}

		public String normalizedSignature() {
			return selectionSignature()
				+ "|realizations=" + realizations.stream().map(CandidateEmissionRealization::normalizedSignature)
					.toList();
		}

		/** Shallow emission identity used by one selected support-clause receipt. */
		public String selectionSignature() {
			return emissionState.normalizedSignature() + "|executionFType="
				+ (executionFType == null ? "-" : executionFType.name()) + "|derivedAction="
				+ (derivedFoutAction == null ? "-" : derivedFoutAction.normalizedSignature());
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
				|| status == CandidateEvaluationStatus.PRIVACY_EXCLUDED
					&& (capability == null || !profile.available() || failureCode.isEmpty()
						|| !allowedEmissionFacts.isEmpty())
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
		private final Map<CompiledHopKey,List<CandidateRuleFact>> factsByParent;
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
			Map<CompiledHopKey,List<CandidateRuleFact>> parentIndex = new IdentityHashMap<>();
			for(CandidateRuleFact fact : orderedFacts)
				parentIndex.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
			parentIndex.replaceAll((ignored, parentFacts) -> List.copyOf(parentFacts));
			factsByParent = Collections.unmodifiableMap(parentIndex);
		}

		public List<CandidateRuleFact> orderedFacts() { return orderedFacts; }

		/** Exact candidate rows for one analysis-owned parent in canonical domain order. */
		public List<CandidateRuleFact> orderedFactsForParent(CompiledHopKey parentOccurrence) {
			if(parentOccurrence == null || !domain.containsExactParent(parentOccurrence))
				return List.of();
			return factsByParent.getOrDefault(parentOccurrence, List.of());
		}

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
					: CandidateLookupFailure.MISSING_FACT, "Exact candidate rule fact is missing: parent="
						+ parentOccurrence.normalizedSignature() + ", requested=" + orderedInputs
						+ ", available=" + orderedFacts.stream()
							.filter(candidate -> candidate.key().parentOccurrence() == parentOccurrence)
							.map(candidate -> candidate.key().orderedInputs().toString()).toList());
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

	/**
	 * Analysis-owned canonical candidate receipts and their exact structural order.
	 * Candidate rule/emission identities are immutable, so selectors must reuse this
	 * domain instead of rebuilding receipts and their nested textual signatures in
	 * every assignment, closure pass, and validation call.
	 */
	private static final class CandidateReceiptDomain {
		private record ReceiptGroupOrderKey(String rule, String emission, String realization)
			implements Comparable<ReceiptGroupOrderKey> {
			@Override public int compareTo(ReceiptGroupOrderKey that) {
				return compareLengthPrefixedFieldSequences(
					List.of(rule, emission, realization),
					List.of(that.rule, that.emission, that.realization));
			}

			private long retainedCharacters() {
				return (long) rule.length() + emission.length() + realization.length();
			}
		}

		private static final class ReceiptGroup {
			private final CandidateRuleKey rule;
			private final CandidateEmissionFact emission;
			private final CandidateEmissionRealization realization;
			private final List<CandidateRealizationSupportClause> clauses;
			private final SearchSpaceMetrics metrics;
			private Map<CandidateRealizationSupportClause,CandidateSelectionReceipt> receipts;
			private int rankBase = -1;
			private int[] clauseRanks;

			private ReceiptGroup(CandidateRuleKey rule, CandidateEmissionFact emission,
				CandidateEmissionRealization realization, SearchSpaceMetrics metrics) {
				this.rule = rule;
				this.emission = emission;
				this.realization = realization;
				this.clauses = realization.supportClauses();
				this.metrics = metrics;
			}

			private int ownedClauseIndex(CandidateRealizationSupportClause clause) {
				for(int index = 0; index < clauses.size(); index++)
					if(clauses.get(index) == clause)
						return index;
				throw new IllegalArgumentException(
					"Candidate support clause is outside the analysis-owned receipt domain");
			}

			private synchronized CandidateSelectionReceipt receipt(
				CandidateRealizationSupportClause clause) {
				ownedClauseIndex(clause);
				if(receipts == null)
					receipts = new IdentityHashMap<>();
				CandidateSelectionReceipt current = receipts.get(clause);
				if(current == null) {
					current = new CandidateSelectionReceipt(
						rule, emission, realization, clause, List.of());
					receipts.put(clause, current);
					if(metrics != null)
						metrics.recordCandidateReceiptCreated();
				}
				return current;
			}

			private int rank(CandidateRealizationSupportClause clause) {
				int index = ownedClauseIndex(clause);
				if(clauseRanks != null)
					return clauseRanks[index];
				if(rankBase < 0)
					throw new IllegalStateException("Canonical candidate receipt group has no structural rank");
				return Math.addExact(rankBase, index);
			}

			private int assignCanonicalRanks(int firstRank) {
				int size = clauses.size();
				if(size == 1) {
					rankBase = firstRank;
					return Math.incrementExact(firstRank);
				}
				int[] order = new int[size];
				int[] work = new int[size];
				int[] lengths = new int[size];
				for(int index = 0; index < size; index++) {
					order[index] = index;
					lengths[index] = canonicalOrderingLength(clauses.get(index));
				}
				stableSortByLengthPrefix(order, work, lengths, 0, size);
				boolean alreadyCanonical = true;
				for(int index = 0; index < size; index++)
					alreadyCanonical &= order[index] == index;
				if(alreadyCanonical)
					rankBase = firstRank;
				else {
					clauseRanks = new int[size];
					for(int index = 0; index < size; index++)
						clauseRanks[order[index]] = Math.addExact(firstRank, index);
				}
				return Math.addExact(firstRank, size);
			}
		}

		private record RankedReceiptGroup(ReceiptGroup group, ReceiptGroupOrderKey orderKey) { }
		private record RankedClause(ReceiptGroup group, CandidateRealizationSupportClause clause,
			int clauseIndex) { }

		private final Map<CandidateRuleKey,
			Map<CandidateEmissionFact,Map<CandidateEmissionRealization,ReceiptGroup>>> groupsByIdentity;
		private final List<ReceiptGroup> groups;
		private final SearchSpaceMetrics metrics;
		private volatile boolean ranksInitialized;

		private CandidateReceiptDomain(CandidateRuleFacts facts) {
			Map<CandidateRuleKey,Map<CandidateEmissionFact,
				Map<CandidateEmissionRealization,ReceiptGroup>>> indexed = new IdentityHashMap<>();
			List<ReceiptGroup> allGroups = new ArrayList<>();
			metrics = PlacementIdentity.activeMetrics();
			for(CandidateRuleFact fact : facts.orderedFacts()) {
				Map<CandidateEmissionFact,Map<CandidateEmissionRealization,ReceiptGroup>> byEmission =
					new IdentityHashMap<>();
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
					Map<CandidateEmissionRealization,ReceiptGroup> byRealization = new IdentityHashMap<>();
					for(CandidateEmissionRealization realization : emission.realizations()) {
						ReceiptGroup group = new ReceiptGroup(fact.key(), emission, realization, metrics);
						byRealization.put(realization, group);
						allGroups.add(group);
						if(metrics != null)
							metrics.recordReceiptRelationSlots(realization.supportClauses().size());
					}
					byEmission.put(emission, Collections.unmodifiableMap(byRealization));
				}
				indexed.put(fact.key(), Collections.unmodifiableMap(byEmission));
			}
			groups = List.copyOf(allGroups);
			groupsByIdentity = Collections.unmodifiableMap(indexed);
		}

		private synchronized void ensureRanks() {
			if(ranksInitialized)
				return;
			List<RankedReceiptGroup> ranked = new ArrayList<>(groups.size());
			for(ReceiptGroup group : groups) {
				ReceiptGroupOrderKey orderKey = new ReceiptGroupOrderKey(
					group.rule.normalizedSignature(), group.emission.selectionSignature(),
					group.realization.key().normalizedSignature());
				ranked.add(new RankedReceiptGroup(group, orderKey));
				if(metrics != null)
					metrics.recordReceiptRankKeyCharacters(orderKey.retainedCharacters());
			}
			ranked.sort(java.util.Comparator.comparing(RankedReceiptGroup::orderKey));
			int rank = 0;
			for(int start = 0; start < ranked.size();) {
				int end = start + 1;
				while(end < ranked.size() && ranked.get(start).orderKey().equals(ranked.get(end).orderKey()))
					end++;
				if(end == start + 1) {
					ReceiptGroup group = ranked.get(start).group();
					rank = group.assignCanonicalRanks(rank);
				}
				else {
					List<RankedClause> clauses = new ArrayList<>();
					java.util.Comparator<CandidateRealizationSupportClause> clauseComparator =
						receiptClauseComparator();
					for(int groupIndex = start; groupIndex < end; groupIndex++) {
						ReceiptGroup group = ranked.get(groupIndex).group();
						group.clauseRanks = new int[group.clauses.size()];
						for(int clauseIndex = 0; clauseIndex < group.clauses.size(); clauseIndex++)
							clauses.add(new RankedClause(group, group.clauses.get(clauseIndex), clauseIndex));
					}
					clauses.sort(java.util.Comparator.comparing(RankedClause::clause, clauseComparator));
					for(RankedClause clause : clauses) {
						clause.group().clauseRanks[clause.clauseIndex()] = rank;
						rank = Math.incrementExact(rank);
					}
				}
				start = end;
			}
			ranksInitialized = true;
		}

		private static java.util.Comparator<CandidateRealizationSupportClause> receiptClauseComparator() {
			CanonicalTextContext context = new CanonicalTextContext();
			return (left, right) -> {
				CanonicalText leftText = canonicalOrderingKey(left, context);
				CanonicalText rightText = canonicalOrderingKey(right, context);
				int order = compareLengthPrefixes(leftText.length, rightText.length);
				return order != 0 ? order : leftText.compareTo(rightText);
			};
		}

		private static void stableSortByLengthPrefix(int[] order, int[] work, int[] lengths,
			int start, int end) {
			if(end - start < 2)
				return;
			int middle = (start + end) >>> 1;
			stableSortByLengthPrefix(order, work, lengths, start, middle);
			stableSortByLengthPrefix(order, work, lengths, middle, end);
			int left = start;
			int right = middle;
			int output = start;
			while(left < middle && right < end) {
				int comparison = compareLengthPrefixes(lengths[order[left]], lengths[order[right]]);
				work[output++] = comparison <= 0 ? order[left++] : order[right++];
			}
			while(left < middle)
				work[output++] = order[left++];
			while(right < end)
				work[output++] = order[right++];
			System.arraycopy(work, start, order, start, end - start);
		}

		private static int compareLengthPrefixes(int left, int right) {
			int leftDigits = decimalDigits(left);
			int rightDigits = decimalDigits(right);
			int positions = Math.max(leftDigits, rightDigits) + 1;
			for(int position = 0; position < positions; position++) {
				char leftCharacter = position < leftDigits
					? decimalDigit(left, leftDigits, position) : ':';
				char rightCharacter = position < rightDigits
					? decimalDigit(right, rightDigits, position) : ':';
				if(leftCharacter != rightCharacter)
					return Character.compare(leftCharacter, rightCharacter);
			}
			return 0;
		}

		private static int decimalDigits(int value) {
			if(value < 0)
				throw new IllegalArgumentException("Canonical text length must be non-negative");
			int digits = 1;
			for(int remaining = value; remaining >= 10; remaining /= 10)
				digits++;
			return digits;
		}

		private static char decimalDigit(int value, int digits, int position) {
			int divisor = 1;
			for(int index = position + 1; index < digits; index++)
				divisor *= 10;
			return (char) ('0' + value / divisor % 10);
		}

		private ReceiptGroup requireGroup(CandidateRuleKey rule,
			CandidateEmissionFact emission, CandidateEmissionRealization realization) {
			Map<CandidateEmissionFact,Map<CandidateEmissionRealization,ReceiptGroup>> byEmission =
				groupsByIdentity.get(rule);
			Map<CandidateEmissionRealization,ReceiptGroup> byRealization =
				byEmission == null ? null : byEmission.get(emission);
			ReceiptGroup group = byRealization == null ? null : byRealization.get(realization);
			if(group == null)
				throw new IllegalArgumentException(
					"Candidate rule/emission/realization is outside the analysis-owned receipt domain");
			return group;
		}

		private CandidateSelectionReceipt require(CandidateRuleKey rule,
			CandidateEmissionFact emission, CandidateEmissionRealization realization,
			CandidateRealizationSupportClause clause) {
			return requireGroup(rule, emission, realization).receipt(clause);
		}

		private CandidateSelectionReceipt requireSingleton(CandidateRuleKey rule,
			CandidateEmissionFact emission) {
			if(emission.realizations().size() != 1)
				throw new IllegalArgumentException("Candidate emission realization is ambiguous");
			CandidateEmissionRealization realization = emission.realizations().get(0);
			return require(rule, emission, realization, realization.requireSingletonSupportClause());
		}

		private CandidateSelectionReceipt require(CandidateRuleKey rule,
			CandidateEmissionFact emission, CandidateEmissionRealization realization) {
			return require(rule, emission, realization, realization.requireSingletonSupportClause());
		}

		private List<CandidateSelectionReceipt> requireAll(CandidateRuleKey rule,
			CandidateEmissionFact emission) {
			return emission.realizations().stream().flatMap(realization -> realization.supportClauses().stream()
				.map(clause -> require(rule, emission, realization, clause))).toList();
		}

		private int rank(CandidateSelectionReceipt receipt) {
			ensureRanks();
			return requireGroup(receipt.rule(), receipt.emission(), receipt.realization())
				.rank(receipt.supportClause());
		}

		private List<CandidateSelectionReceipt> canonicalize(
			java.util.Collection<CandidateSelectionReceipt> receipts) {
			List<CandidateSelectionReceipt> canonical = new ArrayList<>(receipts.size());
			Map<CandidateSelectionReceipt,Boolean> seen = new IdentityHashMap<>();
			for(CandidateSelectionReceipt receipt : receipts) {
				Objects.requireNonNull(receipt, "candidate receipt");
				CandidateSelectionReceipt exact = require(receipt.rule(), receipt.emission(),
					receipt.realization(), receipt.supportClause());
				if(seen.put(exact, Boolean.TRUE) == null)
					canonical.add(exact);
			}
			if(canonical.size() > 1) {
				ensureRanks();
				canonical.sort(java.util.Comparator.comparingInt(this::rank));
			}
			return List.copyOf(canonical);
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

	/**
	 * Exact common-analysis proof that one coordinator-local input can remain local while the
	 * runtime executes its consumer natively over one exactly resident federated sibling. Unlike a
	 * pathwise re-entry, this fact owns no reusable relocation action: the exact runtime candidate
	 * consumes the local input as {@link InputPresence#ABSENT_LOCAL}. The sibling may itself be a
	 * derived FOUT, so this proof deliberately depends on its exact placement state rather than on
	 * a source-data durable anchor. The consumer may retain FOUT, or emit an exact legal LOUT
	 * result when a protected sibling prevents a nested reduction from executing in CP.
	 */
	public record HeuristicNativeContinuationFact(CompiledHopKey localProducer,
		ValueVersionKey localValueVersion, CompiledHopKey consumer, int localInputPosition,
		CompiledHopKey siblingProducer, ValueVersionKey siblingValueVersion, int siblingInputPosition,
		PlacementState siblingFoutState, PlacementState consumerState,
		CandidateRuleFact runtimeCandidate)
		implements Comparable<HeuristicNativeContinuationFact> {
		public HeuristicNativeContinuationFact {
			Objects.requireNonNull(localProducer, "localProducer");
			Objects.requireNonNull(localValueVersion, "localValueVersion");
			Objects.requireNonNull(consumer, "consumer");
			if(localInputPosition < 0 || siblingInputPosition < 0
				|| localInputPosition == siblingInputPosition)
				throw new IllegalArgumentException(
					"Heuristic native-continuation input positions differ and are non-negative");
			Objects.requireNonNull(siblingProducer, "siblingProducer");
			Objects.requireNonNull(siblingValueVersion, "siblingValueVersion");
			Objects.requireNonNull(siblingFoutState, "siblingFoutState");
			Objects.requireNonNull(consumerState, "consumerState");
			Objects.requireNonNull(runtimeCandidate, "runtimeCandidate");
		}

		@Override public int compareTo(HeuristicNativeContinuationFact that) {
			int consumerOrder = consumer.compareTo(that.consumer);
			if(consumerOrder != 0)
				return consumerOrder;
			int localPositionOrder = Integer.compare(localInputPosition, that.localInputPosition);
			if(localPositionOrder != 0)
				return localPositionOrder;
			return Integer.compare(siblingInputPosition, that.siblingInputPosition);
		}
	}

	/** One exact marker-local path projection; no dominance or descendant closure is implied. */
	public record HeuristicPathFact(HeuristicPolicyFact demotion, List<CompiledHopKey> localPrefix,
		List<HeuristicPathEdgeFact> edges, List<HeuristicPathwiseReentryFact> reentries,
		List<HeuristicNativeContinuationFact> nativeContinuations)
		implements Comparable<HeuristicPathFact> {
		public HeuristicPathFact(HeuristicPolicyFact demotion, List<CompiledHopKey> localPrefix,
			List<HeuristicPathEdgeFact> edges, List<HeuristicPathwiseReentryFact> reentries) {
			this(demotion, localPrefix, edges, reentries, List.of());
		}

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
			nativeContinuations = Objects.requireNonNull(nativeContinuations, "nativeContinuations").stream()
				.map(fact -> Objects.requireNonNull(fact, "native continuation fact"))
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

	/** Finite dimension lattice used by the occurrence-scoped common analysis. */
	public enum DimensionKnowledge { BOTTOM, EXACT, UNKNOWN }

	public record DimensionFact(DimensionKnowledge knowledge, long value) {
		public DimensionFact {
			Objects.requireNonNull(knowledge, "knowledge");
			if(knowledge == DimensionKnowledge.EXACT && value < 0)
				throw new IllegalArgumentException("An exact dimension must be non-negative");
			if(knowledge != DimensionKnowledge.EXACT && value != -1)
				throw new IllegalArgumentException("A non-exact dimension must use value -1");
		}

		public static DimensionFact bottom() {
			return new DimensionFact(DimensionKnowledge.BOTTOM, -1);
		}

		public static DimensionFact unknown() {
			return new DimensionFact(DimensionKnowledge.UNKNOWN, -1);
		}

		public static DimensionFact exact(long value) {
			return new DimensionFact(DimensionKnowledge.EXACT, value);
		}

		public boolean isExact(long expected) {
			return knowledge == DimensionKnowledge.EXACT && value == expected;
		}

		public boolean isExact() {
			return knowledge == DimensionKnowledge.EXACT;
		}

		public DimensionFact join(DimensionFact that) {
			Objects.requireNonNull(that, "that");
			if(knowledge == DimensionKnowledge.BOTTOM)
				return that;
			if(that.knowledge == DimensionKnowledge.BOTTOM)
				return this;
			if(knowledge == DimensionKnowledge.UNKNOWN || that.knowledge == DimensionKnowledge.UNKNOWN)
				return unknown();
			return value == that.value ? this : unknown();
		}

		public String normalizedSignature() {
			return knowledge.name() + (isExact() ? ":" + value : "");
		}
	}

	public enum MatrixOrientation { UNKNOWN, MATRIX, ROW_VECTOR, COLUMN_VECTOR, SCALAR_MATRIX }

	/**
	 * Abstract matrix shape that remains sound when concrete HOP dimensions are unknown.
	 * In particular, one exact dimension is sufficient to prove vector orientation.
	 */
	public record AbstractShapeFact(DataType dataType, DimensionFact rows, DimensionFact cols) {
		public AbstractShapeFact {
			Objects.requireNonNull(dataType, "dataType");
			Objects.requireNonNull(rows, "rows");
			Objects.requireNonNull(cols, "cols");
		}

		public static AbstractShapeFact bottom(DataType dataType) {
			return new AbstractShapeFact(dataType, DimensionFact.bottom(), DimensionFact.bottom());
		}

		public static AbstractShapeFact fromConcrete(NodeShapeFact shape) {
			return new AbstractShapeFact(shape.dataType(),
				shape.rows() >= 0 ? DimensionFact.exact(shape.rows()) : DimensionFact.bottom(),
				shape.cols() >= 0 ? DimensionFact.exact(shape.cols()) : DimensionFact.bottom());
		}

		public AbstractShapeFact join(AbstractShapeFact that) {
			Objects.requireNonNull(that, "that");
			DataType joinedType = dataType == DataType.UNKNOWN ? that.dataType
				: that.dataType == DataType.UNKNOWN || dataType == that.dataType ? dataType : DataType.UNKNOWN;
			return new AbstractShapeFact(joinedType, rows.join(that.rows), cols.join(that.cols));
		}

		public boolean isMatrix() {
			return dataType == DataType.MATRIX;
		}

		public boolean provablyRowVector() {
			return isMatrix() && rows.isExact(1);
		}

		public boolean provablyColumnVector() {
			return isMatrix() && cols.isExact(1);
		}

		public boolean provablyVector() {
			return provablyRowVector() || provablyColumnVector();
		}

		public MatrixOrientation orientation() {
			if(!isMatrix())
				return MatrixOrientation.UNKNOWN;
			if(rows.isExact(1) && cols.isExact(1))
				return MatrixOrientation.SCALAR_MATRIX;
			if(rows.isExact(1))
				return MatrixOrientation.ROW_VECTOR;
			if(cols.isExact(1))
				return MatrixOrientation.COLUMN_VECTOR;
			return rows.isExact() && cols.isExact() ? MatrixOrientation.MATRIX : MatrixOrientation.UNKNOWN;
		}

		public String normalizedSignature() {
			return dataType.name() + '|' + rows.normalizedSignature() + '|' + cols.normalizedSignature();
		}
	}

	/** Exact occurrence-scoped scalar constant, after safe CFG/function joins. */
	public record ScalarLiteralFact(ValueType valueType, String canonicalValue) {
		public ScalarLiteralFact {
			Objects.requireNonNull(valueType, "valueType");
			if(canonicalValue == null)
				throw new IllegalArgumentException("Scalar literal value must not be null");
		}

		public String normalizedSignature() {
			return valueType.name() + ':' + canonicalValue;
		}
	}
	/** Exact structural matrix/frame input edge between two compiled Hop owners. */
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

	/** Common identity surface for non-physical candidate inputs. */
	public interface LogicalCandidateInputFact {
		CompiledHopKey sourceOccurrence();
		CompiledHopKey targetRead();
		int logicalPosition();
	}

	/** Exact typed evidence for one compatible writer-reader physical realization pair. */
	public record TransientCompatibilityProof(DurableAnchorKey sourceAnchor,
		DurableAnchorKey readerAnchor, DurableAnchorKey nativeWorkerPoolWitness,
		boolean nativeWorkerPoolLayoutExact, List<PlacementProofKey> dependencies)
		implements Comparable<TransientCompatibilityProof> {
		public TransientCompatibilityProof(DurableAnchorKey sourceAnchor,
			DurableAnchorKey readerAnchor, List<PlacementProofKey> dependencies) {
			this(sourceAnchor, readerAnchor, null, true, dependencies);
		}
		public TransientCompatibilityProof {
			dependencies = canonicalComparableList(dependencies, "transient compatibility proof dependency");
			if((sourceAnchor == null) != (readerAnchor == null))
				throw new IllegalArgumentException("Transient compatibility anchor proof must name both layouts");
			if(sourceAnchor != null && !PlacementIdentity.samePhysicalLayout(sourceAnchor, readerAnchor))
				throw new IllegalArgumentException("Transient compatibility anchors have different physical layouts");
			if(sourceAnchor != null && nativeWorkerPoolWitness != null)
				throw new IllegalArgumentException("Transient compatibility cannot mix durable and native authority");
			if(nativeWorkerPoolWitness == null && !nativeWorkerPoolLayoutExact)
				throw new IllegalArgumentException("Dynamic transient compatibility requires a worker-pool witness");
		}
		public boolean provesNativeContinuity(CompiledHopKey source, CompiledHopKey reader) {
			return dependencies.stream().anyMatch(proof -> proof.kind()
				== PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY
				&& (proof.owner() == source || proof.owner() == reader));
		}
		public String normalizedSignature() {
			return (sourceAnchor == null ? "-" : sourceAnchor.normalizedSignature()) + "|reader="
				+ (readerAnchor == null ? "-" : readerAnchor.normalizedSignature()) + "|nativePool="
				+ (nativeWorkerPoolWitness == null ? "-" : nativeWorkerPoolWitness.normalizedSignature())
				+ (nativeWorkerPoolWitness != null && !nativeWorkerPoolLayoutExact
					? "|nativePoolLayout=dynamic" : "") + "|proofs="
				+ dependencies.stream().map(PlacementProofKey::normalizedSignature).toList();
		}
		@Override public int compareTo(TransientCompatibilityProof that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	public static PlacementProofKey transientValueIdentityProof(CompiledHopKey sourceWrite,
		ValueVersionKey sourceVersion, ValueVersionKey readVersion) {
		Objects.requireNonNull(sourceVersion, "sourceVersion");
		Objects.requireNonNull(readVersion, "readVersion");
		return new PlacementProofKey(PlacementIdentity.PlacementProofKind.VALUE_IDENTITY,
			Objects.requireNonNull(sourceWrite, "sourceWrite"),
			sourceVersion.normalizedSignature() + "->" + readVersion.normalizedSignature());
	}

	/** One executable source-realization to reader-realization compatibility edge. */
	public record TransientPlacementCompatibility(CandidateRealizationReference sourceRealization,
		CandidateRealizationReference readerRealization, CandidateInputState sourceInput,
		CandidateInputState readerInput, TransientCompatibilityProof proof)
		implements Comparable<TransientPlacementCompatibility> {
		public TransientPlacementCompatibility {
			Objects.requireNonNull(sourceRealization, "sourceRealization");
			Objects.requireNonNull(readerRealization, "readerRealization");
			Objects.requireNonNull(sourceInput, "sourceInput");
			Objects.requireNonNull(readerInput, "readerInput");
			Objects.requireNonNull(proof, "proof");
			PlacementRealizationKey source = sourceRealization.realization();
			PlacementRealizationKey reader = readerRealization.realization();
			if(source.layoutKind() == PlacementLayoutKind.LOCAL
				|| reader.layoutKind() == PlacementLayoutKind.LOCAL) {
				if(source.layoutKind() != PlacementLayoutKind.LOCAL
					|| reader.layoutKind() != PlacementLayoutKind.LOCAL
					|| sourceInput.present() || readerInput.present()
					|| proof.sourceAnchor() != null)
					throw new IllegalArgumentException("LOCAL transient compatibility semantics differ");
			}
			else {
				FType sourceType = source.emissionState().placementState().fType();
				FType readerType = reader.emissionState().placementState().fType();
				if(sourceType == null || sourceType != readerType
					|| !sourceInput.equals(CandidateInputState.present(sourceType))
					|| !readerInput.equals(CandidateInputState.present(readerType)))
					throw new IllegalArgumentException("Federated transient compatibility projection differs");
			}
		}
		public String normalizedSignature() {
			return sourceRealization.normalizedSignature() + "|reader=" + readerRealization.normalizedSignature()
				+ "|sourceInput=" + sourceInput.normalizedSignature() + "|readerInput="
				+ readerInput.normalizedSignature() + "|proof=" + proof.normalizedSignature();
		}
		@Override public int compareTo(TransientPlacementCompatibility that) {
			return normalizedSignature().compareTo(that.normalizedSignature());
		}
	}

	/** One analysis-owned logical compatibility relation across an exact CFG transient forward. */
	public record LogicalTransientInputFact(CompiledHopKey sourceWrite, CompiledHopKey targetRead,
		int logicalPosition, ValueVersionKey sourceValueVersion, ValueVersionKey readValueVersion,
		List<TransientPlacementCompatibility> compatibility)
		implements LogicalCandidateInputFact, Comparable<LogicalTransientInputFact> {
		public LogicalTransientInputFact {
			Objects.requireNonNull(sourceWrite, "sourceWrite");
			Objects.requireNonNull(targetRead, "targetRead");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(readValueVersion, "readValueVersion");
			if(logicalPosition != 0)
				throw new IllegalArgumentException("Transient logical input position must be zero");
			compatibility = canonicalComparableList(compatibility, "transient placement compatibility");
			if(compatibility.isEmpty())
				throw new IllegalArgumentException("Logical transient input requires compatible realizations");
		}

		public List<CandidateInputState> supportedReaderInputs() {
			return compatibility.stream().map(TransientPlacementCompatibility::readerInput).distinct().sorted(
				java.util.Comparator.comparing(CandidateInputState::normalizedSignature)).toList();
		}

		public List<TransientPlacementCompatibility> compatibilityForReader(
			CandidateRealizationReference reader) {
			return compatibility.stream().filter(edge -> edge.readerRealization().equals(reader)).toList();
		}

		/** Legacy singleton views; generalized callers must consume {@link #compatibility()}. */
		public PlacementState federatedSourceState() {
			return requireUniqueLegacy(compatibility.stream().map(TransientPlacementCompatibility::sourceRealization)
				.map(CandidateRealizationReference::realization).map(PlacementRealizationKey::emissionState)
				.map(PlacementEmissionState::placementState).filter(state -> state.output() == FederatedOutput.FOUT)
				.distinct().toList(), "federated source state");
		}
		public PlacementState localSourceState() {
			List<PlacementState> values = compatibility.stream().map(TransientPlacementCompatibility::sourceRealization)
				.map(CandidateRealizationReference::realization).map(PlacementRealizationKey::emissionState)
				.map(PlacementEmissionState::placementState).filter(state -> state.output() == FederatedOutput.LOUT)
				.distinct().toList();
			return values.isEmpty() ? null : requireUniqueLegacy(values, "local source state");
		}
		public DurableAnchorKey anchor() {
			List<DurableAnchorKey> values = compatibility.stream().flatMap(edge -> java.util.stream.Stream.of(
				edge.proof().readerAnchor(), edge.readerRealization().realization().durableAnchor()))
				.filter(Objects::nonNull).distinct().toList();
			return values.isEmpty() ? null : requireUniqueLegacy(values, "transient anchor");
		}
		public FType federatedFType() { return federatedSourceState().fType(); }
		public CandidateInputState localInput() {
			List<CandidateInputState> values = compatibility.stream().map(TransientPlacementCompatibility::readerInput)
				.filter(input -> !input.present()).distinct().toList();
			return values.isEmpty() ? null : requireUniqueLegacy(values, "local input");
		}
		public CandidateInputState federatedInput() {
			return requireUniqueLegacy(compatibility.stream().map(TransientPlacementCompatibility::readerInput)
				.filter(CandidateInputState::present).distinct().toList(), "federated input");
		}
		private static <T> T requireUniqueLegacy(List<T> values, String label) {
			if(values.size() != 1)
				throw new IllegalStateException("Legacy " + label + " view is missing or ambiguous");
			return values.get(0);
		}

		@Override
		public int compareTo(LogicalTransientInputFact that) {
			int readOrder = targetRead.compareTo(that.targetRead);
			if(readOrder != 0) return readOrder;
			int positionOrder = Integer.compare(logicalPosition, that.logicalPosition);
			return positionOrder != 0 ? positionOrder : sourceWrite.compareTo(that.sourceWrite);
		}

		@Override public CompiledHopKey sourceOccurrence() { return sourceWrite; }
	}

	/**
	 * One exact caller argument -> synthetic boundary -> compiled formal-read path.
	 * This is a logical input edge, not a fabricated physical Hop input.
	 */
	public record LogicalFunctionInputFact(CompiledHopKey sourceArgument, CompiledHopKey boundary,
		CompiledHopKey targetRead, int callInputPosition, int logicalPosition,
		ValueVersionKey sourceValueVersion, ValueVersionKey boundaryValueVersion,
		ValueVersionKey readValueVersion)
		implements LogicalCandidateInputFact, Comparable<LogicalFunctionInputFact> {
		public LogicalFunctionInputFact {
			Objects.requireNonNull(sourceArgument, "sourceArgument");
			Objects.requireNonNull(boundary, "boundary");
			Objects.requireNonNull(targetRead, "targetRead");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(boundaryValueVersion, "boundaryValueVersion");
			Objects.requireNonNull(readValueVersion, "readValueVersion");
			if(callInputPosition < 0 || logicalPosition != 0)
				throw new IllegalArgumentException("Function logical input positions differ");
		}

		@Override
		public int compareTo(LogicalFunctionInputFact that) {
			int readOrder = targetRead.compareTo(that.targetRead);
			if(readOrder != 0) return readOrder;
			int callOrder = Integer.compare(callInputPosition, that.callInputPosition);
			if(callOrder != 0) return callOrder;
			int sourceOrder = sourceArgument.compareTo(that.sourceArgument);
			return sourceOrder != 0 ? sourceOrder : boundary.compareTo(that.boundary);
		}

		@Override public CompiledHopKey sourceOccurrence() { return sourceArgument; }
	}

	/** Exact compiler-owned argument -> trace-only boundary relation for one AST-inlined call. */
	public record LogicalInlinedFunctionInputFact(Optional<CompiledHopKey> sourceArgument, CompiledHopKey boundary,
		int callInputPosition, Optional<ValueVersionKey> sourceValueVersion, ValueVersionKey boundaryValueVersion)
		implements Comparable<LogicalInlinedFunctionInputFact> {
		public LogicalInlinedFunctionInputFact {
			Objects.requireNonNull(sourceArgument, "sourceArgument");
			Objects.requireNonNull(boundary, "boundary");
			Objects.requireNonNull(sourceValueVersion, "sourceValueVersion");
			Objects.requireNonNull(boundaryValueVersion, "boundaryValueVersion");
			if(callInputPosition < 0)
				throw new IllegalArgumentException("Inlined function input position must be non-negative");
			if(sourceArgument.isPresent() != sourceValueVersion.isPresent())
				throw new IllegalArgumentException("Inlined function input source key/value presence differs");
		}

		@Override public int compareTo(LogicalInlinedFunctionInputFact that) {
			int boundaryOrder = boundary.compareTo(that.boundary);
			if(boundaryOrder != 0) return boundaryOrder;
			int positionOrder = Integer.compare(callInputPosition, that.callInputPosition);
			if(positionOrder != 0) return positionOrder;
			if(sourceArgument.isEmpty()) return that.sourceArgument.isEmpty() ? 0 : -1;
			return that.sourceArgument.isEmpty() ? 1
				: sourceArgument.orElseThrow().compareTo(that.sourceArgument.orElseThrow());
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
	private final List<HopOccurrenceProjection> compiledHopOccurrences;
	private final Map<HopOccurrenceProjection,Boolean> occurrenceOwnershipByIdentity;
	private final Map<CompiledHopKey,Boolean> occurrenceKeysByIdentity;
	private final Map<CompiledHopKey,Boolean> compiledOccurrenceKeysByIdentity;
	private final Map<CompiledHopKey,String> occurrenceSignaturesByIdentity;
	private final List<StatementBlock> topLevelStatementBlocks;
	private final Map<CompiledHopKey, Hop> hopsByKey;
	private final PlacementShapeFacts shapeFacts;
	private final PlacementPrivacyFacts privacyFacts;
	private final CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence;
	private final OccurrenceExecutionFrequencyFacts executionFrequencyFacts;
	private final String analysisFingerprint;
	private final HeuristicPolicyFacts heuristicPolicyFacts;
	private final CandidateRuleDomain candidateRuleDomain;
	private final CandidateRuleFacts candidateRuleFacts;
	private final CandidateReceiptDomain candidateReceiptDomain;
	private final RelocationSelections.CanonicalOrderIndex relocationOrder;
	private volatile RelocationSelections.RelocationPrivacyIndex relocationPrivacy;
	private final Map<NeutralPlacementGraph.RelocationAction,Boolean> relocationActionsByIdentity;
	private final CandidateConsumerProfileFacts candidateConsumerProfileFacts;
	private final DetachedConsumerProfileFacts detachedConsumerProfileFacts;
	private final List<CompiledInputEdgeFact> compiledInputEdgesInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> inputEdgesByIdentity;
	private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> inputEdgesByConsumerIdentity;
	private final Map<CompiledInputEdgeFact,CoordinatorInputAccess> coordinatorInputAccessByIdentity;
	private final Map<CompiledHopKey,List<CompiledHopKey>> cfgDefinitionSourcesByIdentity;
	private final LogicalBoundaryRealizations logicalBoundaryRealizations;
	private final List<LogicalTransientInputFact> logicalTransientInputsInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,LogicalTransientInputFact>>> logicalInputsByIdentity;
	private final Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> logicalInputsByReaderSlot;
	private final List<LogicalFunctionInputFact> logicalFunctionInputsInCanonicalOrder;
	private final Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>>>
		logicalFunctionInputsByIdentity;
	private final List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputsInCanonicalOrder;
	private final DMLProgram programOwner;
	private final Map<String,FunctionStatementBlock> namedFunctionStatementBlocks;
	private final Runnable programMutationGuard;
	private final boolean guardedFunctionRoots;

	/**
	 * Opaque construction-boundary authority. PlacementAnalysis can validate or advance a
	 * transaction-owned snapshot, but it cannot traverse the program or derive a second structural
	 * universe. The canonical construction boundary owns the concrete fingerprint implementation.
	 */
	interface ProgramStructureAuthority extends Runnable {
		void authorizeCommittedEmission();
	}

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
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, defaultPrivacyFacts(graph), programMutationGuard);
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
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, privacyFacts,
			new CandidatePrivacyClosureEvidence(List.of()), programMutationGuard);
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
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		Runnable programMutationGuard) {
		this(graph, occurrences, topLevelStatementBlocks, programOwner, shapeFacts, analysisFingerprint,
			heuristicPolicyFacts, candidateRuleDomainKeys, candidateRuleFacts,
			candidateConsumerDomainKeys, candidateConsumerProfileFacts, detachedConsumerProfileFacts,
			compiledInputEdges, logicalTransientInputs, privacyFacts, candidatePrivacyClosureEvidence,
			null, programMutationGuard);
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
		List<LogicalTransientInputFact> logicalTransientInputs, PlacementPrivacyFacts privacyFacts,
		CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence,
		List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputs,
		Runnable programMutationGuard) {
		this.graph = Objects.requireNonNull(graph, "graph");
		this.privacyFacts = Objects.requireNonNull(privacyFacts, "privacyFacts");
		this.candidatePrivacyClosureEvidence = Objects.requireNonNull(
			candidatePrivacyClosureEvidence, "candidatePrivacyClosureEvidence");
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
		Map<HopOccurrenceProjection,Boolean> ownedOccurrences = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> ownedKeys = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> compiledKeys = new IdentityHashMap<>();
		Map<CompiledHopKey,String> occurrenceSignatures = new IdentityHashMap<>();
		List<HopOccurrenceProjection> compiledOccurrences = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : this.occurrences) {
			if(indexed.put(occurrence.key(), occurrence.hop()) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop projection key: " + occurrence.key());
			ownedOccurrences.put(occurrence, Boolean.TRUE);
			ownedKeys.put(occurrence.key(), Boolean.TRUE);
			occurrenceSignatures.put(occurrence.key(), occurrence.normalizedSignature());
			NodeKind kind = graph.node(occurrence.key()).orElseThrow(() ->
				new IllegalArgumentException("Occurrence has a foreign graph key")).kind();
			if(isCompiledHopOccurrenceKey(occurrence.key(), kind)) {
				compiledKeys.put(occurrence.key(), Boolean.TRUE);
				compiledOccurrences.add(occurrence);
			}
		}
		if(indexed.size() != graph.nodes().size())
			throw new IllegalArgumentException("Projection does not cover the neutral placement graph");
		this.compiledHopOccurrences = List.copyOf(compiledOccurrences);
		this.occurrenceOwnershipByIdentity = Collections.unmodifiableMap(ownedOccurrences);
		this.occurrenceKeysByIdentity = Collections.unmodifiableMap(ownedKeys);
		this.compiledOccurrenceKeysByIdentity = Collections.unmodifiableMap(compiledKeys);
		this.occurrenceSignaturesByIdentity = Collections.unmodifiableMap(occurrenceSignatures);
		this.shapeFacts = Objects.requireNonNull(shapeFacts, "shapeFacts");
		if(!shapeFacts.keys().equals(indexed.keySet()))
			throw new IllegalArgumentException("Shape facts do not exactly cover indexed placement projections");
		hopsByKey = Map.copyOf(indexed);
		if(analysisFingerprint == null || analysisFingerprint.isBlank())
			throw new IllegalArgumentException("analysisFingerprint must not be blank");
		this.analysisFingerprint = canonicalizeSuppliedAnalysisFingerprint(analysisFingerprint);
		this.heuristicPolicyFacts = Objects.requireNonNull(heuristicPolicyFacts, "heuristicPolicyFacts");
		this.candidateRuleDomain = new CandidateRuleDomain(this.analysisFingerprint, candidateRuleDomainKeys,
			candidateConsumerDomainKeys);
		Map<CompiledHopKey,Boolean> analysisKeysByIdentity = this.occurrenceKeysByIdentity;
		for(CandidateRuleKey key : this.candidateRuleDomain.orderedRuleKeys())
			if(!analysisKeysByIdentity.containsKey(key.parentOccurrence()))
				throw new IllegalArgumentException("Candidate domain parent is not analysis-owned");
		this.candidateRuleFacts = new CandidateRuleFacts(this.candidateRuleDomain, candidateRuleFacts);
		this.candidateReceiptDomain = new CandidateReceiptDomain(this.candidateRuleFacts);
		Map<NeutralPlacementGraph.RelocationAction,Boolean> ownedRelocationActions = new IdentityHashMap<>();
		for(NeutralPlacementGraph.RelocationAction action : graph.relocationActions())
			ownedRelocationActions.put(action, Boolean.TRUE);
		this.relocationActionsByIdentity = Collections.unmodifiableMap(ownedRelocationActions);
		this.relocationOrder = RelocationSelections.canonicalOrderIndex(graph.relocationActions());
		this.candidateConsumerProfileFacts = new CandidateConsumerProfileFacts(this.candidateRuleDomain,
			candidateConsumerProfileFacts);
		this.detachedConsumerProfileFacts = new DetachedConsumerProfileFacts(detachedConsumerProfileFacts,
			analysisKeysByIdentity);
		this.compiledInputEdgesInCanonicalOrder = validateCompiledInputEdges(compiledInputEdges);
		this.inputEdgesByIdentity = indexCompiledInputEdges(this.compiledInputEdgesInCanonicalOrder);
		this.inputEdgesByConsumerIdentity = indexCompiledInputEdgesByConsumer(
			this.compiledInputEdgesInCanonicalOrder);
		this.logicalBoundaryRealizations = new LogicalBoundaryRealizations(graph.nodes(), graph.constraints(),
			hopsByKey, this.candidateRuleFacts.orderedFacts());
		this.logicalBoundaryRealizations.validate(this.candidateRuleFacts.orderedFacts());
		validateCandidateRealizationSupport();
		this.coordinatorInputAccessByIdentity = deriveCoordinatorInputAccess(
			this.compiledInputEdgesInCanonicalOrder);
		this.cfgDefinitionSourcesByIdentity = indexCfgDefinitionSources(graph, hopsByKey);
		this.logicalTransientInputsInCanonicalOrder = validateLogicalTransientInputs(logicalTransientInputs,
			analysisKeysByIdentity);
		this.logicalInputsByIdentity = indexLogicalTransientInputs(this.logicalTransientInputsInCanonicalOrder);
		this.logicalInputsByReaderSlot = indexLogicalTransientInputsByReader(
			this.logicalTransientInputsInCanonicalOrder);
		this.logicalFunctionInputsInCanonicalOrder = validateLogicalFunctionInputs(
			deriveLogicalFunctionInputs(), analysisKeysByIdentity);
		this.logicalFunctionInputsByIdentity = indexLogicalFunctionInputs(
			this.logicalFunctionInputsInCanonicalOrder);
		this.logicalInlinedFunctionInputsInCanonicalOrder = logicalInlinedFunctionInputs == null
			? deriveLogicalInlinedFunctionInputs()
			: validateLogicalInlinedFunctionInputs(logicalInlinedFunctionInputs, analysisKeysByIdentity);
		for(HeuristicPolicyFact fact : heuristicPolicyFacts.demotions()) {
			NeutralPlacementGraph.Node producer = graph.node(fact.producer()).orElseThrow(() ->
				new IllegalArgumentException("Heuristic policy producer is missing from the analysis graph"));
			if(!producer.valueVersion().equals(fact.valueVersion()))
				throw new IllegalArgumentException("Heuristic policy producer/value pair does not match the analysis graph");
		}
		validateHeuristicPaths(analysisKeysByIdentity);
		this.executionFrequencyFacts = OccurrenceExecutionFrequencyFacts.from(this);
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
				else if(!isCompiledAcyclicTransientForwardAccess(
					hopsByKey.get(edge.producer()), producer, OpOpData.TRANSIENTWRITE)
					|| !isCompiledAcyclicTransientForwardAccess(
						hopsByKey.get(edge.consumer()), consumer, OpOpData.TRANSIENTREAD)
					|| edge.inputPosition() != 0)
					throw new IllegalArgumentException("Heuristic CFG edge is not an exact transient forward: "
						+ edge + ", producerKind=" + producer.kind() + ", consumerKind=" + consumer.kind());
			}
			for(HeuristicPathwiseReentryFact fact : path.reentries())
				validateHeuristicReentry(path, fact, analysisKeysByIdentity);
			for(HeuristicNativeContinuationFact fact : path.nativeContinuations())
				validateHeuristicNativeContinuation(path, fact, analysisKeysByIdentity);
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
		if(List.of(local, sibling, consumer).stream().anyMatch(node -> !node.emittedWork()
			|| node.valueVersion().versionKind() == PlacementIdentity.VersionKind.CLONE_RECOMPILE
			|| node.kind() == NodeKind.CLONE || node.kind() == NodeKind.FUNCTION_CALL
			|| node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT
			|| node.kind() == NodeKind.FUNCTION_BODY_NON_EMITTED))
			throw new IllegalArgumentException(
				"Heuristic re-entry must bind exact emitted non-boundary occurrences");
	}

	private void validateHeuristicNativeContinuation(HeuristicPathFact path,
		HeuristicNativeContinuationFact fact,
		Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		if(!analysisKeysByIdentity.containsKey(fact.localProducer())
			|| !analysisKeysByIdentity.containsKey(fact.consumer())
			|| !analysisKeysByIdentity.containsKey(fact.siblingProducer()))
			throw new IllegalArgumentException("Heuristic native continuation contains a foreign occurrence");
		if(!path.localPrefix().contains(fact.localProducer()))
			throw new IllegalArgumentException(
				"Heuristic native-continuation source is outside its exact local prefix");
		NeutralPlacementGraph.Node local = graph.node(fact.localProducer()).orElseThrow();
		NeutralPlacementGraph.Node sibling = graph.node(fact.siblingProducer()).orElseThrow();
		NeutralPlacementGraph.Node consumer = graph.node(fact.consumer()).orElseThrow();
		if(local.valueVersion() != fact.localValueVersion()
			|| sibling.valueVersion() != fact.siblingValueVersion())
			throw new IllegalArgumentException("Heuristic native-continuation value identity differs");
		requireExactCompiledInputEdge(fact.localProducer(), fact.consumer(), fact.localInputPosition());
		requireExactCompiledInputEdge(fact.siblingProducer(), fact.consumer(), fact.siblingInputPosition());
		if(!sibling.legalAlternatives().contains(fact.siblingFoutState())
			|| fact.siblingFoutState().execType() != ExecType.FED
			|| fact.siblingFoutState().output() != FederatedOutput.FOUT
			|| fact.siblingFoutState().fType() == null)
			throw new IllegalArgumentException(
				"Heuristic native-continuation sibling FOUT authority differs");
		if(!consumer.legalAlternatives().contains(fact.consumerState())
			|| fact.consumerState().execType() != ExecType.FED
			|| (fact.consumerState().output() != FederatedOutput.FOUT
				&& fact.consumerState().output() != FederatedOutput.LOUT)
			|| fact.consumerState().fType() != fact.siblingFoutState().fType())
			throw new IllegalArgumentException("Heuristic native-continuation consumer state differs");
		CandidateRuleFact exactCandidate = candidateRuleFacts.requireExact(fact.consumer(),
			fact.runtimeCandidate().key().orderedInputs());
		List<CandidateInputState> inputs = exactCandidate.key().orderedInputs();
		if(exactCandidate != fact.runtimeCandidate()
			|| exactCandidate.status() != CandidateEvaluationStatus.AVAILABLE
			|| exactCandidate.capability() == null
			|| exactCandidate.capability().nativeExec() != fact.consumerState().execType()
			|| !(fact.consumerState().output() == FederatedOutput.FOUT
				&& exactCandidate.capability().nativeOutput() == FederatedOutput.FOUT
				&& exactCandidate.capability().nativeFoutFType() == fact.consumerState().fType()
				|| fact.consumerState().output() == FederatedOutput.LOUT)
			|| fact.localInputPosition() >= inputs.size()
			|| !inputs.get(fact.localInputPosition()).equals(CandidateInputState.absentLocal())
			|| fact.siblingInputPosition() >= inputs.size()
			|| !inputs.get(fact.siblingInputPosition()).equals(
				CandidateInputState.present(fact.siblingFoutState().fType()))
			|| inputs.stream().filter(CandidateInputState::present).count() != 1
			|| exactCandidate.allowedEmissionFacts().stream().noneMatch(emission ->
				emission.emissionState().placementState().equals(fact.consumerState())
					&& emission.executionFType() == fact.siblingFoutState().fType()))
			throw new IllegalArgumentException("Heuristic native-continuation runtime candidate differs");
		if(List.of(local, sibling, consumer).stream().anyMatch(node -> !node.emittedWork()
			|| node.valueVersion().versionKind() == PlacementIdentity.VersionKind.CLONE_RECOMPILE
			|| node.kind() == NodeKind.CLONE || node.kind() == NodeKind.FUNCTION_CALL
			|| node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT
			|| node.kind() == NodeKind.FUNCTION_BODY_NON_EMITTED))
			throw new IllegalArgumentException(
				"Heuristic native continuation must bind exact emitted non-boundary occurrences");
	}

	private List<LogicalTransientInputFact> validateLogicalTransientInputs(
		List<LogicalTransientInputFact> supplied, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		Objects.requireNonNull(supplied, "logicalTransientInputs");
		List<LogicalTransientInputFact> sorted = supplied.stream()
			.map(fact -> Objects.requireNonNull(fact, "logical transient input fact")).sorted().toList();
		if(!sorted.equals(supplied))
			throw new IllegalArgumentException("Logical transient input facts are not in canonical order");
		Map<CompiledHopKey,Map<CompiledHopKey,Set<Integer>>> slots = new IdentityHashMap<>();
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> byReaderSlot = new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : sorted) {
			if(!analysisKeysByIdentity.containsKey(fact.sourceWrite())
				|| !analysisKeysByIdentity.containsKey(fact.targetRead()))
				throw new IllegalArgumentException("Logical transient input has a foreign occurrence");
			NeutralPlacementGraph.Node source = graph.node(fact.sourceWrite()).orElseThrow();
			NeutralPlacementGraph.Node read = graph.node(fact.targetRead()).orElseThrow();
			if(!isCompiledTransientAccess(hopsByKey.get(source.key()), source, OpOpData.TRANSIENTWRITE)
				|| !isCompiledTransientAccess(hopsByKey.get(read.key()), read, OpOpData.TRANSIENTREAD))
				throw new IllegalArgumentException("Logical transient input endpoints have wrong node kinds");
			if(source.valueVersion() != fact.sourceValueVersion() || read.valueVersion() != fact.readValueVersion())
				throw new IllegalArgumentException("Logical transient input value identity differs");
			if(!hopsByKey.get(fact.targetRead()).getInput().isEmpty())
				throw new IllegalArgumentException("Logical transient read has physical inputs");
			if(compiledInputEdgesInCanonicalOrder.stream().anyMatch(edge -> edge.producer() == fact.sourceWrite()
				&& edge.consumer() == fact.targetRead() && edge.inputPosition() == fact.logicalPosition()))
				throw new IllegalArgumentException("Logical transient input fabricated a physical edge");

			Set<CandidateRealizationReference> supportedReaderRealizations = new java.util.HashSet<>();
			for(TransientPlacementCompatibility edge : fact.compatibility()) {
				if(edge.sourceRealization().rule().parentOccurrence() != fact.sourceWrite()
					|| edge.readerRealization().rule().parentOccurrence() != fact.targetRead())
					throw new IllegalArgumentException("Transient compatibility realization owner differs");
				CandidateEmissionRealization sourceRealization = requireReferencedRealization(edge.sourceRealization());
				CandidateEmissionRealization readerRealization = requireReferencedRealization(edge.readerRealization());
				validateTransientRealizationTuple(source, sourceRealization, "source");
				validateTransientRealizationTuple(read, readerRealization, "reader");
		List<CandidateInputState> readerInputs = edge.readerRealization().rule().orderedInputs();
				if(readerInputs.size() != 1 || !readerInputs.get(0).equals(edge.readerInput()))
					throw new IllegalArgumentException("Transient reader realization candidate input differs");
				validateTransientCompatibilityProof(fact, edge, sourceRealization, readerRealization,
					analysisKeysByIdentity);
				supportedReaderRealizations.add(edge.readerRealization());
			}
			Set<CandidateRealizationReference> actualReaderRealizations = candidateRuleFacts.orderedFacts().stream()
				.filter(candidate -> candidate.key().parentOccurrence() == fact.targetRead()
					&& candidate.status() == CandidateEvaluationStatus.AVAILABLE)
				.flatMap(candidate -> candidate.allowedEmissionFacts().stream()
					.flatMap(emission -> emission.realizations().stream()
						.map(realization -> CandidateRealizationReference.of(candidate.key(), realization))))
				.collect(java.util.stream.Collectors.toSet());
			if(!actualReaderRealizations.equals(supportedReaderRealizations)) {
				Set<CandidateRealizationReference> missing = new java.util.HashSet<>(actualReaderRealizations);
				missing.removeAll(supportedReaderRealizations);
				Set<CandidateRealizationReference> extra = new java.util.HashSet<>(supportedReaderRealizations);
				extra.removeAll(actualReaderRealizations);
				throw new IllegalArgumentException(
					"Logical transient candidate domain differs from realizable relation: reader="
						+ fact.targetRead().normalizedSignature() + ", source="
						+ fact.sourceWrite().normalizedSignature() + ", missing=" + missing.stream()
							.map(CandidateRealizationReference::normalizedSignature).sorted().toList()
						+ ", extra=" + extra.stream().map(CandidateRealizationReference::normalizedSignature)
							.sorted().toList());
			}
			if(!slots.computeIfAbsent(fact.sourceWrite(), ignored -> new IdentityHashMap<>())
				.computeIfAbsent(fact.targetRead(), ignored -> new java.util.HashSet<>())
				.add(fact.logicalPosition()))
				throw new IllegalArgumentException("Duplicate logical transient input slot");
			byReaderSlot.computeIfAbsent(fact.targetRead(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(fact.logicalPosition(), ignored -> new ArrayList<>()).add(fact);
		}
		validateReachingDefinitionSupport(byReaderSlot);
		return List.copyOf(sorted);
	}

	private CandidateEmissionRealization requireReferencedRealization(CandidateRealizationReference reference) {
		CandidateRuleFact rule = candidateRuleFacts.requireExact(reference.rule().parentOccurrence(),
			reference.rule().orderedInputs());
		if(rule.status() != CandidateEvaluationStatus.AVAILABLE)
			throw new IllegalArgumentException("Transient compatibility references an unavailable candidate row");
		List<CandidateEmissionRealization> matching = rule.allowedEmissionFacts().stream()
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().equals(reference.realization())).toList();
		if(matching.size() != 1)
			throw new IllegalArgumentException(
				"Transient compatibility realization is missing or ambiguous: reference="
					+ reference.normalizedSignature() + ", matching=" + matching.size()
					+ ", available=" + rule.allowedEmissionFacts().stream()
						.flatMap(emission -> emission.realizations().stream())
						.map(CandidateEmissionRealization::normalizedSignature).toList());
		return matching.get(0);
	}

	private void validateCandidateRealizationSupport() {
		for(CandidateRuleFact fact : candidateRuleFacts.orderedFacts())
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations()) {
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						if(realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE
							&& clause.nativeWorkerPoolWitness() == null)
							throw new IllegalArgumentException(
								"Published native lineage lacks exact worker-pool authority");
						for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
							CandidateEmissionRealization source = requireReferencedRealization(binding.source());
							if(binding.inputPosition() >= fact.key().orderedInputs().size())
								throw new IllegalArgumentException("Candidate realization input binding position differs");
							CandidateInputState rowInput = fact.key().orderedInputs().get(binding.inputPosition());
							FType effectiveType = binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION
								? binding.relocationAction().materializationFType()
								: source.placementState().output() == FederatedOutput.FOUT
									? source.placementState().fType() : null;
							CandidateInputState expectedInput = effectiveType == null
								? CandidateInputState.absentLocal() : CandidateInputState.present(effectiveType);
							if(!rowInput.equals(expectedInput))
								throw new IllegalArgumentException("Candidate realization binding and oracle input row differs: rule="
									+ fact.key().normalizedSignature() + ", position=" + binding.inputPosition()
									+ ", expected=" + expectedInput + ", binding=" + binding);
							if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT) {
								CompiledInputEdgeFact physical = inputEdgesByConsumerIdentity
									.getOrDefault(fact.key().parentOccurrence(), Map.of()).get(binding.inputPosition());
								if(physical == null || physical.producer() != binding.source().rule().parentOccurrence())
									throw new IllegalArgumentException("DIRECT realization binding lacks its exact physical edge");
							}
							if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION) {
								RelocationActionKey action = binding.relocationAction();
								boolean graphOwned = graph.relocationActions().stream()
									.anyMatch(candidate -> candidate.key() == action);
								boolean consumerCompatible = action.compatibleConsumers()
									.contains(fact.key().parentOccurrence());
								ValueVersionKey actualSource = graph.node(
									binding.source().rule().parentOccurrence()).orElseThrow().valueVersion();
								boolean sourceCompatible = action.sourceValueVersion().equals(actualSource);
								if(!graphOwned || !consumerCompatible || !sourceCompatible)
									throw new IllegalArgumentException(
										"Candidate realization relocation binding lacks exact graph authority: graphOwned="
											+ graphOwned + ", consumerCompatible=" + consumerCompatible
											+ ", sourceCompatible=" + sourceCompatible + ", action="
											+ action.normalizedSignature() + ", consumer="
											+ fact.key().parentOccurrence().normalizedSignature() + ", source="
											+ binding.source().normalizedSignature() + ", actualSourceValue="
											+ actualSource.normalizedSignature());
							}
						}
					}
				}
	}

	private static void validateTransientRealizationTuple(NeutralPlacementGraph.Node owner,
		CandidateEmissionRealization realization, String endpoint) {
		PlacementState state = realization.placementState();
		boolean local = state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
			&& state.fType() == null && !state.shapeDependent()
			&& realization.key().layoutKind() == PlacementLayoutKind.LOCAL;
		boolean federated = state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
			&& state.fType() != null && state.fType() != FType.PART && state.fType() != FType.OTHER
			&& realization.key().layoutKind() != PlacementLayoutKind.LOCAL;
		if(!local && !federated)
			throw new IllegalArgumentException("Illegal transient " + endpoint + " realization tuple");
		if(owner.legalAlternatives().stream().noneMatch(candidate -> candidate == state))
			throw new IllegalArgumentException("Transient " + endpoint
				+ " realization state is not analysis-owned: owner=" + owner.key().normalizedSignature()
				+ ", realization=" + realization.key().normalizedSignature() + ", state="
				+ state.normalizedSignature() + '@' + System.identityHashCode(state) + ", legal="
				+ owner.legalAlternatives().stream().map(candidate -> candidate.normalizedSignature()
					+ '@' + System.identityHashCode(candidate)).toList());
	}

	private static void validateTransientCompatibilityProof(LogicalTransientInputFact fact,
		TransientPlacementCompatibility edge, CandidateEmissionRealization source,
		CandidateEmissionRealization reader, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		for(PlacementProofKey proof : edge.proof().dependencies())
			if(proof.owner() != null && !analysisKeysByIdentity.containsKey(proof.owner()))
				throw new IllegalArgumentException("Transient compatibility proof has a foreign owner");
		boolean valueProof = edge.proof().dependencies().stream().anyMatch(proof ->
			proof.kind() == PlacementIdentity.PlacementProofKind.VALUE_IDENTITY
				&& proof.owner() == fact.sourceWrite()
				&& proof.authoritySignature().equals(fact.sourceValueVersion().normalizedSignature()
					+ "->" + fact.readValueVersion().normalizedSignature()));
		if(!valueProof)
			throw new IllegalArgumentException("Transient compatibility lacks exact value-identity proof");
		PlacementLayoutKind sourceKind = source.key().layoutKind();
		PlacementLayoutKind readerKind = reader.key().layoutKind();
		if(sourceKind == PlacementLayoutKind.LOCAL)
			return;
		if(sourceKind == PlacementLayoutKind.DURABLE_MAP && readerKind == PlacementLayoutKind.DURABLE_MAP) {
			if(edge.proof().sourceAnchor() == null
				|| !PlacementIdentity.samePhysicalLayout(source.key().durableAnchor(), edge.proof().sourceAnchor())
				|| !PlacementIdentity.samePhysicalLayout(reader.key().durableAnchor(), edge.proof().readerAnchor()))
				throw new IllegalArgumentException("Transient compatibility durable-map proof differs");
		}
		else {
			if(!edge.proof().provesNativeContinuity(fact.sourceWrite(), fact.targetRead()))
				throw new IllegalArgumentException(
					"Transient native-lineage compatibility lacks owned continuity proof");
			DurableAnchorKey proofWitness = edge.proof().nativeWorkerPoolWitness();
			if(proofWitness == null || readerKind != PlacementLayoutKind.NATIVE_LINEAGE)
				throw new IllegalArgumentException(
					"Transient native-lineage compatibility lacks typed reader worker-pool authority");
			CandidateRealizationSupportClause readerClause = reader.supportClauses().get(0);
			DurableAnchorKey readerWitness = reader.nativeWorkerPoolResidencyWitness(readerClause);
			boolean readerExact = reader.nativeWorkerPoolLayoutExact(readerClause);
			if(readerWitness == null)
				throw new IllegalArgumentException(
					"Transient native-lineage reader lacks a worker-pool witness");
			if(!edge.proof().nativeWorkerPoolLayoutExact() && readerExact)
				throw new IllegalArgumentException(
					"Dynamic transient compatibility cannot publish exact reader geometry");
			boolean sameReaderPool = readerExact && edge.proof().nativeWorkerPoolLayoutExact()
				? PlacementIdentity.samePhysicalLayout(readerWitness, proofWitness)
				: PlacementIdentity.samePhysicalWorkerEndpoints(readerWitness, proofWitness);
			if(!sameReaderPool)
				throw new IllegalArgumentException(
					"Transient native-lineage reader worker-pool authority differs");
		}
	}

	private void validateReachingDefinitionSupport(
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> byReaderSlot) {
		for(Map.Entry<CompiledHopKey,List<CompiledHopKey>> entry : cfgDefinitionSourcesByIdentity.entrySet()) {
			if(entry.getValue().isEmpty())
				continue;
			NeutralPlacementGraph.Node reader = graph.node(entry.getKey()).orElseThrow();
			Hop readerHop = hopsByKey.get(reader.key());
			if(reader.kind() == NodeKind.FUNCTION_INPUT
				|| readerHop == null || !(readerHop.getDataType().isMatrix() || readerHop.getDataType().isFrame())
				|| !isCompiledTransientAccess(readerHop, reader, OpOpData.TRANSIENTREAD))
				continue;
			if(logicalBoundaryRealizations.hasCompleteBoundary(reader.key())) {
				// Mixed formal/result reads use the compiler-declared boundary relation,
				// including every ordinary writer. Exact selection checks its pool per source.
				if(!logicalBoundaryRealizations.sources(reader.key()).containsAll(entry.getValue()))
					throw new IllegalArgumentException("Function boundary omits an ordinary reaching writer");
				continue;
			}
			boolean executable = candidateRuleFacts.orderedFacts().stream().anyMatch(candidate ->
				candidate.key().parentOccurrence() == reader.key()
					&& candidate.status() == CandidateEvaluationStatus.AVAILABLE
					&& !candidate.allowedEmissionFacts().isEmpty());
			if(executable && (byReaderSlot.get(reader.key()) == null
				|| byReaderSlot.get(reader.key()).getOrDefault(0, List.of()).isEmpty()))
				throw new IllegalArgumentException(
					"Executable transient reader lacks reaching-definition compatibility relations: reader="
						+ reader.key().normalizedSignature() + ", sources=" + entry.getValue().stream()
							.map(CompiledHopKey::normalizedSignature).toList());
		}
		for(Map.Entry<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> readEntry : byReaderSlot.entrySet())
			for(List<LogicalTransientInputFact> facts : readEntry.getValue().values())
				validateReachingDefinitionSupportForSlot(
					cfgDefinitionSourcesByIdentity.getOrDefault(readEntry.getKey(), List.of()), facts);
	}

	/** Package-private pure validator used by focused authority tests. */
	static void validateReachingDefinitionSupportForSlot(List<CompiledHopKey> reaching,
		List<LogicalTransientInputFact> facts) {
		Objects.requireNonNull(reaching, "reaching");
		Objects.requireNonNull(facts, "facts");
		Set<CompiledHopKey> suppliedSources = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		facts.forEach(fact -> suppliedSources.add(fact.sourceWrite()));
		if(suppliedSources.size() != reaching.size()
			|| reaching.stream().anyMatch(source -> !suppliedSources.contains(source)))
			throw new IllegalArgumentException("Logical transient relation does not cover every reaching definition"
				+ "|reaching=" + reaching.stream().map(CompiledHopKey::normalizedSignature).toList()
				+ "|supplied=" + suppliedSources.stream().map(CompiledHopKey::normalizedSignature).toList()
				+ "|readers=" + facts.stream().map(fact -> fact.targetRead().normalizedSignature()).distinct().toList());
		Set<CandidateRealizationReference> commonReaders = null;
		for(LogicalTransientInputFact fact : facts) {
			Set<CandidateRealizationReference> readers = fact.compatibility().stream()
				.map(TransientPlacementCompatibility::readerRealization)
				.collect(java.util.stream.Collectors.toSet());
			if(commonReaders == null) commonReaders = new java.util.HashSet<>(readers);
			else commonReaders.retainAll(readers);
		}
		if(commonReaders == null || commonReaders.isEmpty())
			throw new IllegalArgumentException("No reader realization is supported by every reaching definition");
		for(LogicalTransientInputFact fact : facts)
			for(TransientPlacementCompatibility edge : fact.compatibility())
				if(!commonReaders.contains(edge.readerRealization()))
					throw new IllegalArgumentException(
						"Logical transient relation retains a partially supported join realization");
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

	private static Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>>
		indexLogicalTransientInputsByReader(List<LogicalTransientInputFact> facts) {
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> mutable = new IdentityHashMap<>();
		for(LogicalTransientInputFact fact : facts)
			mutable.computeIfAbsent(fact.targetRead(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(fact.logicalPosition(), ignored -> new ArrayList<>()).add(fact);
		Map<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> result = new IdentityHashMap<>();
		for(Map.Entry<CompiledHopKey,Map<Integer,List<LogicalTransientInputFact>>> read : mutable.entrySet()) {
			Map<Integer,List<LogicalTransientInputFact>> slots = new LinkedHashMap<>();
			for(Map.Entry<Integer,List<LogicalTransientInputFact>> slot : read.getValue().entrySet())
				slots.put(slot.getKey(), List.copyOf(slot.getValue()));
			result.put(read.getKey(), Collections.unmodifiableMap(slots));
		}
		return Collections.unmodifiableMap(result);
	}

	private List<LogicalFunctionInputFact> deriveLogicalFunctionInputs() {
		Map<CompiledHopKey,List<Constraint>> incomingArguments = new IdentityHashMap<>();
		for(Constraint constraint : graph.constraints())
			if(constraint.kind() == ConstraintKind.CONJUNCTIVE
				&& (constraint.evidence().startsWith("function-argument:")
					|| constraint.evidence().startsWith("inlined-function-argument:")))
				incomingArguments.computeIfAbsent(constraint.right(), ignored -> new java.util.ArrayList<>())
					.add(constraint);
		List<LogicalFunctionInputFact> result = new java.util.ArrayList<>();
		for(Constraint formal : graph.constraints()) {
			if(formal.kind() != ConstraintKind.SAME_PLACEMENT
				|| !"function-formal-input".equals(formal.evidence()))
				continue;
			List<Constraint> arguments = incomingArguments.getOrDefault(formal.left(), List.of());
			if(arguments.size() != 1)
				throw new IllegalArgumentException("Function input boundary has no unique caller argument");
			Constraint argument = arguments.get(0);
			NeutralPlacementGraph.Node source = graph.node(argument.left()).orElseThrow();
			NeutralPlacementGraph.Node boundary = graph.node(formal.left()).orElseThrow();
			NeutralPlacementGraph.Node read = graph.node(formal.right()).orElseThrow();
			result.add(new LogicalFunctionInputFact(source.key(), boundary.key(), read.key(),
				argument.inputPosition(), 0, source.valueVersion(), boundary.valueVersion(), read.valueVersion()));
		}
		return result.stream().sorted().toList();
	}

	private List<LogicalInlinedFunctionInputFact> deriveLogicalInlinedFunctionInputs() {
		List<LogicalInlinedFunctionInputFact> result = new ArrayList<>();
		Map<CompiledHopKey,Set<Integer>> slots = new LinkedHashMap<>();
		for(Constraint constraint : graph.constraints()) {
			if(constraint.kind() != ConstraintKind.CONJUNCTIVE
				|| !constraint.evidence().startsWith("inlined-function-argument:"))
				continue;
			NeutralPlacementGraph.Node source = graph.node(constraint.left()).orElseThrow();
			NeutralPlacementGraph.Node boundary = graph.node(constraint.right()).orElseThrow();
			if(!source.emittedWork() || boundary.kind() != NodeKind.FUNCTION_INPUT
				|| boundary.emittedWork() || constraint.inputPosition() < 0)
				throw new IllegalArgumentException("Inlined function input constraint endpoints differ");
			if(!slots.computeIfAbsent(boundary.key(), ignored -> new java.util.LinkedHashSet<>())
				.add(constraint.inputPosition()))
				throw new IllegalArgumentException("Inlined function input has ambiguous compiler authority");
			result.add(new LogicalInlinedFunctionInputFact(Optional.of(source.key()), boundary.key(),
				constraint.inputPosition(), Optional.of(source.valueVersion()), boundary.valueVersion()));
		}
		return result.stream().sorted().toList();
	}

	private List<LogicalInlinedFunctionInputFact> validateLogicalInlinedFunctionInputs(
		List<LogicalInlinedFunctionInputFact> supplied, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		Objects.requireNonNull(supplied, "logicalInlinedFunctionInputs");
		Map<CompiledHopKey,Set<Integer>> slots = new LinkedHashMap<>();
		for(LogicalInlinedFunctionInputFact fact : supplied) {
			if(!analysisKeysByIdentity.containsKey(fact.boundary())
				|| fact.sourceArgument().isPresent()
					&& !analysisKeysByIdentity.containsKey(fact.sourceArgument().orElseThrow()))
				throw new IllegalArgumentException("Inlined function input fact references a foreign occurrence");
			NeutralPlacementGraph.Node boundary = graph.node(fact.boundary()).orElseThrow();
			if(boundary.kind() != NodeKind.FUNCTION_INPUT || boundary.emittedWork()
				|| !boundary.valueVersion().equals(fact.boundaryValueVersion()))
				throw new IllegalArgumentException("Inlined function input boundary differs from graph authority");
			if(fact.sourceArgument().isPresent()) {
				NeutralPlacementGraph.Node source = graph.node(fact.sourceArgument().orElseThrow()).orElseThrow();
				if(!source.emittedWork() || !source.valueVersion().equals(fact.sourceValueVersion().orElseThrow()))
					throw new IllegalArgumentException("Inlined function input source differs from graph authority");
				boolean constraint = graph.constraints().stream().anyMatch(edge ->
					edge.kind() == ConstraintKind.CONJUNCTIVE
						&& edge.left().equals(source.key()) && edge.right().equals(boundary.key())
						&& edge.inputPosition() == fact.callInputPosition()
						&& edge.evidence().startsWith("inlined-function-argument:"));
				if(!constraint)
					throw new IllegalArgumentException("Inlined function input source lacks compiler constraint");
			}
			else if(graph.constraints().stream().anyMatch(edge -> edge.right().equals(boundary.key())
				&& edge.evidence().startsWith("inlined-function-argument:")))
				throw new IllegalArgumentException("Eliminated inlined input retains a physical source constraint");
			if(!slots.computeIfAbsent(fact.boundary(), ignored -> new java.util.LinkedHashSet<>())
				.add(fact.callInputPosition()))
				throw new IllegalArgumentException("Inlined function input has ambiguous compiler outcome");
		}
		return supplied.stream().sorted().toList();
	}

	private List<LogicalFunctionInputFact> validateLogicalFunctionInputs(
		List<LogicalFunctionInputFact> supplied, Map<CompiledHopKey,Boolean> analysisKeysByIdentity) {
		Objects.requireNonNull(supplied, "logicalFunctionInputs");
		List<LogicalFunctionInputFact> sorted = supplied.stream()
			.map(fact -> Objects.requireNonNull(fact, "logical function input fact")).sorted().toList();
		if(!sorted.equals(supplied))
			throw new IllegalArgumentException("Logical function input facts are not in canonical order");
		Set<String> identities = new java.util.LinkedHashSet<>();
		for(LogicalFunctionInputFact fact : sorted) {
			if(!analysisKeysByIdentity.containsKey(fact.sourceArgument())
				|| !analysisKeysByIdentity.containsKey(fact.boundary())
				|| !analysisKeysByIdentity.containsKey(fact.targetRead()))
				throw new IllegalArgumentException("Logical function input has a foreign occurrence");
			NeutralPlacementGraph.Node source = graph.node(fact.sourceArgument()).orElseThrow();
			NeutralPlacementGraph.Node boundary = graph.node(fact.boundary()).orElseThrow();
			NeutralPlacementGraph.Node read = graph.node(fact.targetRead()).orElseThrow();
			if(boundary.kind() != NodeKind.FUNCTION_INPUT || !isLogicalFunctionRead(read))
				throw new IllegalArgumentException("Logical function input endpoints have wrong node kinds");
			if(source.valueVersion() != fact.sourceValueVersion()
				|| boundary.valueVersion() != fact.boundaryValueVersion()
				|| read.valueVersion() != fact.readValueVersion())
				throw new IllegalArgumentException("Logical function input value identity differs");
			if(!hopsByKey.get(fact.targetRead()).getInput().isEmpty())
				throw new IllegalArgumentException("Logical function read has physical inputs");
			long argumentEdges = graph.constraints().stream().filter(constraint ->
				constraint.kind() == ConstraintKind.CONJUNCTIVE
					&& constraint.left() == fact.sourceArgument() && constraint.right() == fact.boundary()
					&& constraint.inputPosition() == fact.callInputPosition()).count();
			long formalEdges = graph.constraints().stream().filter(constraint ->
				constraint.kind() == ConstraintKind.SAME_PLACEMENT
					&& constraint.left() == fact.boundary() && constraint.right() == fact.targetRead()
					&& "function-formal-input".equals(constraint.evidence())).count();
			if(argumentEdges != 1 || formalEdges != 1)
				throw new IllegalArgumentException("Logical function input constraint authority differs");
			// The function-input replay owns the exact caller-input rows. A later
			// fixed-point pass may add another caller output (for example BROADCAST)
			// without adding it to this formal read's closed input domain. This constructor
			// therefore validates boundary authority and row shape only; candidate legality,
			// physical input compatibility, and selectors validate the stored rows themselves.
			List<CandidateInputState> inputs = candidateRuleFacts.orderedFacts().stream()
				.filter(candidate -> candidate.key().parentOccurrence() == fact.targetRead()
					&& candidate.status() == CandidateEvaluationStatus.AVAILABLE)
				.map(candidate -> {
					if(candidate.key().orderedInputs().size() != 1)
						throw new IllegalArgumentException(
							"Logical function read candidate must have one caller input");
					return candidate.key().orderedInputs().get(0);
				}).distinct().toList();
			if(inputs.isEmpty())
				throw new IllegalArgumentException("Logical function read has no available caller-input row");
			String identity = System.identityHashCode(fact.sourceArgument()) + ":"
				+ System.identityHashCode(fact.boundary()) + ":" + System.identityHashCode(fact.targetRead())
				+ ':' + fact.callInputPosition();
			if(!identities.add(identity))
				throw new IllegalArgumentException("Duplicate logical function input fact");
		}
		return List.copyOf(sorted);
	}

	static boolean isCompiledTransientAccess(Hop hop, NeutralPlacementGraph.Node node, OpOpData operation) {
		if(operation != OpOpData.TRANSIENTREAD && operation != OpOpData.TRANSIENTWRITE
			|| !(hop instanceof DataOp data) || data.getOp() != operation
			|| !isCompiledHopOccurrenceKey(node.key(), node.kind()))
			return false;
		NodeKind physicalKind = operation == OpOpData.TRANSIENTREAD
			? NodeKind.TRANSIENT_READ : NodeKind.TRANSIENT_WRITE;
		// Phi is the value/control classification of a real compiled read or write,
		// not a replacement for its runtime operation. Clones/synthetic boundaries
		// remain outside this exact CFG forwarding authority.
		return node.kind() == physicalKind || node.kind() == NodeKind.LOOP_PHI
			|| node.kind() == NodeKind.BRANCH_JOIN;
	}

	static boolean isCompiledAcyclicTransientForwardAccess(Hop hop,
		NeutralPlacementGraph.Node node, OpOpData operation) {
		// A branch join is an acyclic merge whose complete reaching-definition set can
		// prove one local value. A LOOP_PHI is cyclic: admitting its back edge can make
		// a later demotion marker appear downstream of itself and replace its native
		// FED/LOUT execution with CP/LOUT. Loop-carried locality remains governed by the
		// existing concrete TRead/TWrite paths until an iteration-aware marker contract
		// can distinguish entry and back-edge states.
		return node.kind() != NodeKind.LOOP_PHI
			&& isCompiledTransientAccess(hop, node, operation);
	}

	private static boolean isLogicalFunctionRead(NeutralPlacementGraph.Node read) {
		return (read.kind() == NodeKind.TRANSIENT_READ || read.kind() == NodeKind.BRANCH_JOIN
			|| read.kind() == NodeKind.LOOP_PHI)
			&& (read.valueVersion().versionKind() == PlacementIdentity.VersionKind.FUNCTION_INPUT
				|| read.valueVersion().predecessorVersions().stream()
					.anyMatch(value -> value.startsWith("cfg-function-input:")));
	}

	private static Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>>>
		indexLogicalFunctionInputs(List<LogicalFunctionInputFact> facts) {
		Map<CompiledHopKey,Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>>> indexed =
			new IdentityHashMap<>();
		for(LogicalFunctionInputFact fact : facts) {
			Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>> byRead = indexed.computeIfAbsent(
				fact.sourceArgument(), ignored -> new IdentityHashMap<>());
			Map<Integer,List<LogicalFunctionInputFact>> byPosition = byRead.computeIfAbsent(fact.targetRead(),
				ignored -> new LinkedHashMap<>());
			byPosition.computeIfAbsent(fact.logicalPosition(), ignored -> new java.util.ArrayList<>()).add(fact);
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
			List.of(), deriveCompiledInputEdges(graph, occurrences, shapeFacts));
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
			+ String.join("\n", projectionSignatures) + '\n'
			+ stableSignature(privacyFacts.normalizedSignature()));
	}

	private static PlacementPrivacyFacts defaultPrivacyFacts(NeutralPlacementGraph graph) {
		Objects.requireNonNull(graph, "graph");
		List<PlacementPrivacyFacts.PrivacyFact> facts = graph.nodes().stream()
			.map(node -> new PlacementPrivacyFacts.PrivacyFact(node.key(), node.valueVersion(),
				Privacy.PUBLIC, List.of())).toList();
		return new PlacementPrivacyFacts(graph.nodes(), facts, 0);
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
		List<CompiledInputEdgeFact> expected = deriveCompiledInputEdges(
			graph, compiledHopOccurrences(), shapeFacts);
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

	private static Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> indexCompiledInputEdgesByConsumer(
		List<CompiledInputEdgeFact> facts) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> indexed = new IdentityHashMap<>();
		for(CompiledInputEdgeFact fact : facts) {
			Map<Integer,CompiledInputEdgeFact> byPosition = indexed.computeIfAbsent(
				fact.consumer(), ignored -> new LinkedHashMap<>());
			if(byPosition.putIfAbsent(fact.inputPosition(), fact) != null)
				throw new IllegalArgumentException("Duplicate compiled consumer input edge fact");
		}
		return Collections.unmodifiableMap(indexed);
	}

	private static List<CompiledInputEdgeFact> deriveCompiledInputEdges(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> occurrences, PlacementShapeFacts shapeFacts) {
		Objects.requireNonNull(graph, "graph");
		Objects.requireNonNull(occurrences, "occurrences");
		Objects.requireNonNull(shapeFacts, "shapeFacts");
		List<HopOccurrenceProjection> compiled = occurrences.stream()
			.filter(occurrence -> isCompiledHopOccurrenceKey(occurrence.key(), graph.node(occurrence.key())
				.orElseThrow(() -> new IllegalArgumentException("Occurrence has a foreign graph key")).kind())).toList();
		Map<CompiledHopKey,HopOccurrenceProjection> occurrencesByIdentity = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : compiled)
			if(occurrencesByIdentity.put(occurrence.key(), occurrence) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop occurrence identity");
		Map<CompiledHopKey,Map<Integer,Constraint>> inputsByConsumer = new IdentityHashMap<>();
		for(Constraint constraint : graph.constraints()) {
			if(!isCompiledInputConstraint(constraint))
				continue;
			HopOccurrenceProjection producer = occurrencesByIdentity.get(constraint.left());
			HopOccurrenceProjection consumer = occurrencesByIdentity.get(constraint.right());
			if(producer == null || consumer == null)
				throw new IllegalArgumentException("Compiled-input constraint has a foreign compiled owner");
			if(constraint.inputPosition() < 0)
				throw new IllegalArgumentException("Compiled-input constraint has no exact input position");
			NodeShapeFact producerShape = shapeFacts.shapeFact(producer.key()).orElseThrow(() ->
				new IllegalArgumentException("Compiled-input producer has no builder-owned shape fact"));
			if(!isPlacementDataType(producerShape.dataType()))
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

	private static boolean isCompiledInputConstraint(Constraint constraint) {
		return constraint.kind() == ConstraintKind.DOMINATES && "data-input".equals(constraint.evidence())
			|| constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& "function-input-binding".equals(constraint.evidence());
	}

	private static boolean isPlacementDataType(DataType dataType) {
		return dataType == DataType.MATRIX || dataType == DataType.FRAME;
	}

	private record EdgeIdentity(CompiledHopKey producer, CompiledHopKey consumer, int inputPosition) { }

	static boolean isCompiledHopOccurrenceKey(CompiledHopKey key, NodeKind kind) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(kind, "kind");
		if(kind == NodeKind.FUNCTION_INPUT || kind == NodeKind.FUNCTION_OUTPUT)
			return false;
		for(String part : key.controlRegion().regionPath())
			if(part.startsWith("function-boundary:"))
				return false;
		return true;
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
		return compiledHopOccurrences;
	}

	public boolean isCompiledHopOccurrence(HopOccurrenceProjection occurrence) {
		Objects.requireNonNull(occurrence, "occurrence");
		if(!occurrenceOwnershipByIdentity.containsKey(occurrence))
			throw new IllegalArgumentException("Occurrence is not owned by this placement analysis");
		return compiledOccurrenceKeysByIdentity.containsKey(occurrence.key());
	}

	public boolean isCompiledHopOccurrence(CompiledHopKey key) {
		Objects.requireNonNull(key, "key");
		if(!occurrenceKeysByIdentity.containsKey(key))
			throw new IllegalArgumentException("Key is not owned by this placement analysis");
		return compiledOccurrenceKeysByIdentity.containsKey(key);
	}

	/** Stable constructor-captured signature of an exact analysis-owned occurrence. */
	public String normalizedOccurrenceSignature(CompiledHopKey key) {
		String signature = occurrenceSignaturesByIdentity.get(Objects.requireNonNull(key, "key"));
		if(signature == null)
			throw new IllegalArgumentException("Key is not owned by this placement analysis");
		return signature;
	}

	public List<StatementBlock> topLevelStatementBlocks() {
		return topLevelStatementBlocks;
	}

	/** Shared immutable control-flow frequency authority captured before selector execution. */
	public OccurrenceExecutionFrequencyFacts executionFrequencyFacts() {
		return executionFrequencyFacts;
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

	/** Transaction-internal authorization of one completely committed exact planner emission. */
	void authorizeCommittedProgramStructure() {
		if(programMutationGuard instanceof ProgramStructureAuthority authority)
			authority.authorizeCommittedEmission();
		else
			programMutationGuard.run();
	}

	public Optional<Hop> hop(CompiledHopKey key) {
		return Optional.ofNullable(hopsByKey.get(Objects.requireNonNull(key, "key")));
	}

	public Optional<NodeShapeFact> shapeFact(CompiledHopKey key) {
		return shapeFacts.shapeFact(key);
	}

	/** Source-compiled dimensions captured before conservative CFG shape closure. */
	public Optional<NodeShapeFact> sourceCompiledShapeFact(CompiledHopKey key) {
		return shapeFacts.sourceCompiledShapeFact(key);
	}

	public Optional<AbstractShapeFact> abstractShapeFact(CompiledHopKey key) {
		return shapeFacts.abstractShapeFact(key);
	}

	public Optional<ScalarLiteralFact> scalarLiteralFact(CompiledHopKey key) {
		return shapeFacts.scalarLiteralFact(key);
	}

	public PlacementPrivacyFacts privacyFactAuthority() {
		return privacyFacts;
	}

	public CandidatePrivacyClosureEvidence candidatePrivacyClosureEvidence() {
		return candidatePrivacyClosureEvidence;
	}

	public Map<CompiledHopKey,Privacy> privacyFacts() {
		return privacyFacts.asMap();
	}

	public Privacy requirePrivacy(CompiledHopKey key) {
		return privacyFacts.requirePrivacy(key);
	}

	public int numWorkers() {
		return privacyFacts.numWorkers();
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

	/** Exact immutable candidate receipt owned by this analysis. */
	public CandidateSelectionReceipt canonicalCandidateReceipt(CandidateRuleKey rule,
		CandidateEmissionFact emission) {
		return candidateReceiptDomain.requireSingleton(rule, emission);
	}

	/** Exact immutable receipt for one physical realization of an emission. */
	public CandidateSelectionReceipt canonicalCandidateReceipt(CandidateRuleKey rule,
		CandidateEmissionFact emission, CandidateEmissionRealization realization) {
		return candidateReceiptDomain.require(rule, emission, realization);
	}

	/** Exact immutable receipt for one conjunctive support clause of a realization. */
	public CandidateSelectionReceipt canonicalCandidateReceipt(CandidateRuleKey rule,
		CandidateEmissionFact emission, CandidateEmissionRealization realization,
		CandidateRealizationSupportClause supportClause) {
		return candidateReceiptDomain.require(rule, emission, realization, supportClause);
	}

	/** All canonical receipts for one emission, in realization order. */
	public List<CandidateSelectionReceipt> canonicalCandidateReceipts(CandidateRuleKey rule,
		CandidateEmissionFact emission) {
		return candidateReceiptDomain.requireAll(rule, emission);
	}

	/** Canonicalizes without reconstructing nested candidate identity strings. */
	public List<CandidateSelectionReceipt> canonicalCandidateReceipts(
		java.util.Collection<CandidateSelectionReceipt> receipts) {
		return candidateReceiptDomain.canonicalize(Objects.requireNonNull(receipts, "receipts"));
	}

	/** Exact structural rank corresponding to CandidateSelectionReceipt.compareTo. */
	public int candidateReceiptRank(CandidateSelectionReceipt receipt) {
		return candidateReceiptDomain.rank(Objects.requireNonNull(receipt, "receipt"));
	}

	/** Shared canonical order for the immutable relocation-action universe. */
	public RelocationSelections.CanonicalOrderIndex relocationOrder() {
		return relocationOrder;
	}

	/**
	 * Reuses the common index only for the exact analysis-owned action objects.
	 * Policy projections may clone/filter actions and therefore need one selector-
	 * local index whose embedded obligation objects belong to that projection.
	 */
	public RelocationSelections.CanonicalOrderIndex relocationOrderFor(
		java.util.Collection<NeutralPlacementGraph.RelocationAction> actions) {
		Objects.requireNonNull(actions, "actions");
		if(actions.size() == relocationActionsByIdentity.size()
			&& actions.stream().allMatch(relocationActionsByIdentity::containsKey))
			return relocationOrder;
		return RelocationSelections.canonicalOrderIndex(actions);
	}

	/**
	 * Reuses the immutable full-analysis source-privacy authority for the exact graph,
	 * including copied or filtered action collections. A projected authority graph
	 * owns a different source-occurrence scope and must rebuild its own index.
	 */
	RelocationSelections.RelocationPrivacyIndex relocationPrivacyFor(
		NeutralPlacementGraph authorityGraph,
		java.util.Collection<NeutralPlacementGraph.RelocationAction> actions) {
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actions, "actions");
		if(authorityGraph == graph) {
			RelocationSelections.RelocationPrivacyIndex common = relocationPrivacy;
			if(common == null) {
				synchronized(this) {
					common = relocationPrivacy;
					if(common == null) {
						common = RelocationSelections.buildRelocationPrivacyIndex(
							this, graph, graph.relocationActions());
						relocationPrivacy = common;
					}
				}
			}
			if(actions == graph.relocationActions() || common.containsAllSources(actions))
				return common;
		}
		return RelocationSelections.buildRelocationPrivacyIndex(this, authorityGraph, actions);
	}


	public List<CompiledInputEdgeFact> compiledInputEdgesInCanonicalOrder() {
		return compiledInputEdgesInCanonicalOrder;
	}

	/** O(1) exact compiled matrix/frame input lookup by consumer occurrence and position. */
	public Optional<CompiledInputEdgeFact> compiledInputEdge(CompiledHopKey consumer,
		int inputPosition) {
		if(inputPosition < 0)
			throw new IllegalArgumentException("inputPosition must be non-negative");
		NeutralPlacementGraph.Node target = graph.node(Objects.requireNonNull(consumer, "consumer"))
			.orElseThrow(() -> new IllegalArgumentException("Compiled input consumer is outside the analysis"));
		Map<Integer,CompiledInputEdgeFact> byPosition = inputEdgesByConsumerIdentity.get(target.key());
		return Optional.ofNullable(byPosition == null ? null : byPosition.get(inputPosition));
	}

	/**
	 * Runtime-native coordinator access required by one exact compiled data edge.
	 * Matrix/frame {@code nrow}, {@code ncol}, and {@code length} read dimensions
	 * directly from {@code FederationMap} ranges in
	 * {@code AggregateUnaryCPInstruction}; they do not acquire or download the
	 * partition payload. The fact is captured once by the shared analysis so every
	 * selector, cost model, and lowering projection consumes the same boundary
	 * semantics.
	 */
	public CoordinatorInputAccess coordinatorInputAccess(CompiledInputEdgeFact edge) {
		CoordinatorInputAccess access = coordinatorInputAccessByIdentity.get(
			Objects.requireNonNull(edge, "compiled input edge"));
		if(access == null)
			throw new IllegalArgumentException(
				"Coordinator input access requires an exact analysis-owned compiled edge");
		return access;
	}

	public boolean isCoordinatorMetadataOnlyInput(CompiledInputEdgeFact edge) {
		return coordinatorInputAccess(edge) == CoordinatorInputAccess.FEDERATION_MAP_METADATA;
	}

	/** O(1) exact producer/consumer boundary query used by DP reconciliation. */
	public boolean isCoordinatorMetadataOnlyInputBoundary(CompiledHopKey producer,
		CompiledHopKey consumer) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> byConsumer =
			inputEdgesByIdentity.get(Objects.requireNonNull(producer, "producer"));
		Map<Integer,CompiledInputEdgeFact> byPosition = byConsumer == null ? null
			: byConsumer.get(Objects.requireNonNull(consumer, "consumer"));
		return byPosition != null && byPosition.size() == 1
			&& isCoordinatorMetadataOnlyInput(byPosition.values().iterator().next());
	}

	private Map<CompiledInputEdgeFact,CoordinatorInputAccess> deriveCoordinatorInputAccess(
		List<CompiledInputEdgeFact> edges) {
		Map<CompiledInputEdgeFact,CoordinatorInputAccess> result = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : edges) {
			Hop producer = hopsByKey.get(edge.producer());
			Hop consumer = hopsByKey.get(edge.consumer());
			result.put(edge, coordinatorInputAccess(producer, consumer, edge.inputPosition()));
		}
		return Collections.unmodifiableMap(result);
	}

	/** Construction-time kernel shared with the pre-selector privacy closure. */
	static CoordinatorInputAccess coordinatorInputAccess(Hop producer, Hop consumer, int inputPosition) {
		boolean federationMapMetadata = inputPosition == 0
			&& producer != null && producer.getDataType() != null
			&& (producer.getDataType().isMatrix() || producer.getDataType().isFrame())
			&& consumer instanceof UnaryOp unary
			&& (unary.getOp() == OpOp1.NROW || unary.getOp() == OpOp1.NCOL
				|| unary.getOp() == OpOp1.LENGTH);
		return federationMapMetadata ? CoordinatorInputAccess.FEDERATION_MAP_METADATA
			: CoordinatorInputAccess.PAYLOAD;
	}

	/**
	 * Exact raw CFG reaching definitions for one occurrence.  This relation is
	 * intentionally broader than {@link #logicalTransientInputsInCanonicalOrder()}:
	 * the latter exists only when the writer/read candidate tuples can be replayed
	 * as one physical planner decision, while shape and cost proofs may still need
	 * the immutable value-flow source of a non-replayable read.
	 */
	public List<CompiledHopKey> cfgDefinitionSourcesInCanonicalOrder(CompiledHopKey key) {
		NeutralPlacementGraph.Node target = graph.node(Objects.requireNonNull(key, "key"))
			.orElseThrow(() -> new IllegalArgumentException("CFG definition target is outside the analysis"));
		return cfgDefinitionSourcesByIdentity.getOrDefault(target.key(), List.of());
	}

	private static Map<CompiledHopKey,List<CompiledHopKey>> indexCfgDefinitionSources(
		NeutralPlacementGraph graph, Map<CompiledHopKey,Hop> hopsByKey) {
		Map<String,List<CompiledHopKey>> sourcesByReference = new java.util.HashMap<>();
		for(NeutralPlacementGraph.Node node : graph.nodes()) {
			Hop owner = hopsByKey.get(node.key());
			// cfg-definition references are minted only by real definition operations.
			// A TRead can legitimately share the same ValueVersionKey/reference with a
			// later TWrite (for example old_norm_R2 = norm_R2), but it is not another
			// reaching writer and must not inflate the compatibility relation's source set.
			if(!isCompiledHopOccurrenceKey(node.key(), node.kind()) || !(owner instanceof DataOp data)
				|| data.getOp() != OpOpData.TRANSIENTWRITE && data.getOp() != OpOpData.FUNCTIONOUTPUT)
				continue;
			sourcesByReference.computeIfAbsent(node.valueVersion().cfgReferenceSignature(),
				ignored -> new java.util.ArrayList<>()).add(node.key());
		}
		for(List<CompiledHopKey> sources : sourcesByReference.values())
			sources.sort(null);

		Map<CompiledHopKey,List<CompiledHopKey>> indexed = new IdentityHashMap<>();
		for(NeutralPlacementGraph.Node target : graph.nodes()) {
			Set<String> references = target.valueVersion().predecessorVersions().stream()
				.filter(value -> value.startsWith("cfg-definition:"))
				.map(value -> value.substring("cfg-definition:".length()))
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			if(references.isEmpty())
				continue;
			List<CompiledHopKey> sources = new java.util.ArrayList<>();
			for(String reference : references) {
				List<CompiledHopKey> owners = sourcesByReference.get(reference);
				if(owners == null || owners.isEmpty())
					throw new IllegalStateException("CFG definition reference has no exact placement owner");
				sources.addAll(owners);
			}
			sources.sort(null);
			indexed.put(target.key(), List.copyOf(sources));
		}
		return Collections.unmodifiableMap(indexed);
	}

	/**
	 * A DML {@link FunctionOp} is a coordinator-side call placeholder. Its matrix arguments are
	 * forwarded through the explicit logical actual/formal boundary and are not consumed locally by
	 * the call Hop itself. Consequently, a FED/FOUT argument followed by a CP/LOUT call placeholder
	 * does not imply a FED-to-local matrix materialization.
	 */
	public boolean isDmlFunctionCallBoundary(CompiledHopKey key) {
		Hop owner = hop(Objects.requireNonNull(key, "function call key")).orElseThrow(
			() -> new IllegalArgumentException("Function call boundary key is outside the analysis"));
		return isDmlFunctionCallBoundary(graph.node(key).orElseThrow(), owner);
	}

	static boolean isDmlFunctionCallBoundary(NeutralPlacementGraph.Node node, Hop owner) {
		// Synthetic formals may share a FunctionOp origin but carry actual payloads.
		return node.kind() == NodeKind.FUNCTION_CALL && owner instanceof FunctionOp function
			&& function.getFunctionType() == FunctionType.DML;
	}

	public List<LogicalTransientInputFact> logicalTransientInputsInCanonicalOrder() {
		return logicalTransientInputsInCanonicalOrder;
	}

	/** All reaching-writer relations for one exact reader slot. */
	public List<LogicalTransientInputFact> logicalTransientInputsForReader(CompiledHopKey targetRead,
		int logicalPosition) {
		Map<Integer,List<LogicalTransientInputFact>> bySlot = logicalInputsByReaderSlot.get(
			Objects.requireNonNull(targetRead, "targetRead"));
		return bySlot == null ? List.of() : bySlot.getOrDefault(logicalPosition, List.of());
	}

	/** Resolves a stable reference back to the one analysis-owned realization it names. */
	public CandidateEmissionRealization requireExactCandidateRealization(
		CandidateRealizationReference reference) {
		return requireReferencedRealization(Objects.requireNonNull(reference, "reference"));
	}

	/** Exact compatibility edges for one chosen reader realization across all reaching writers. */
	public List<TransientPlacementCompatibility> transientCompatibilityForReader(
		CandidateRealizationReference reader) {
		Objects.requireNonNull(reader, "reader");
		return logicalTransientInputsForReader(reader.rule().parentOccurrence(), 0).stream()
			.flatMap(fact -> fact.compatibilityForReader(reader).stream()).sorted().toList();
	}

	/** Exact relation query used after a planner has selected both endpoint receipts. */
	public boolean isTransientPlacementCompatible(LogicalTransientInputFact fact,
		CandidateSelectionReceipt source, CandidateSelectionReceipt reader) {
		Objects.requireNonNull(fact, "fact");
		CandidateSelectionReceipt exactSource = candidateReceiptDomain.require(source.rule(), source.emission(),
			source.realization());
		CandidateSelectionReceipt exactReader = candidateReceiptDomain.require(reader.rule(), reader.emission(),
			reader.realization());
		CandidateRealizationReference sourceRef = CandidateRealizationReference.of(
			exactSource.rule(), exactSource.realization());
		CandidateRealizationReference readerRef = CandidateRealizationReference.of(
			exactReader.rule(), exactReader.realization());
		return fact.compatibility().stream().anyMatch(edge -> edge.sourceRealization().equals(sourceRef)
			&& edge.readerRealization().equals(readerRef));
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

	public LogicalBoundaryRealizations logicalBoundaryRealizations() {
		return logicalBoundaryRealizations;
	}

	public List<LogicalFunctionInputFact> logicalFunctionInputsInCanonicalOrder() {
		return logicalFunctionInputsInCanonicalOrder;
	}

	public List<LogicalInlinedFunctionInputFact> logicalInlinedFunctionInputsInCanonicalOrder() {
		return logicalInlinedFunctionInputsInCanonicalOrder;
	}

	public LogicalInlinedFunctionInputFact requireExactLogicalInlinedFunctionInput(
		CompiledHopKey boundary, int callInputPosition) {
		Objects.requireNonNull(boundary, "boundary");
		List<LogicalInlinedFunctionInputFact> matches = logicalInlinedFunctionInputsInCanonicalOrder.stream()
			.filter(fact -> fact.boundary().equals(boundary)
				&& fact.callInputPosition() == callInputPosition)
			.toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("Inlined function input outcome is not unique: boundary="
				+ boundary.normalizedSignature() + " position=" + callInputPosition
				+ " matches=" + matches.size());
		return matches.get(0);
	}

	public LogicalFunctionInputFact requireExactLogicalFunctionInput(CompiledHopKey sourceArgument,
		CompiledHopKey targetRead, int logicalPosition) {
		Map<CompiledHopKey,Map<Integer,List<LogicalFunctionInputFact>>> byRead =
			logicalFunctionInputsByIdentity.get(Objects.requireNonNull(sourceArgument, "sourceArgument"));
		Map<Integer,List<LogicalFunctionInputFact>> byPosition = byRead == null ? null
			: byRead.get(Objects.requireNonNull(targetRead, "targetRead"));
		List<LogicalFunctionInputFact> facts = byPosition == null ? null : byPosition.get(logicalPosition);
		if(facts == null || facts.size() != 1)
			throw new IllegalArgumentException("Exact logical function input fact is missing or ambiguous");
		return facts.get(0);
	}

	/**
	 * Resolves the physical DML {@link FunctionOp} input that carries one exact logical
	 * caller-argument/formal binding. Matrix and frame arguments additionally require the frozen
	 * compiled-input fact used by placement transfers. Scalar/control arguments are
	 * validated against the concrete FunctionOp input itself because the compiled-input
	 * index intentionally excludes non-data operands and no placement transfer exists for them.
	 */
	public CompiledHopKey requireExactPhysicalFunctionInputConsumer(LogicalFunctionInputFact supplied) {
		Objects.requireNonNull(supplied, "logical function input fact");
		LogicalFunctionInputFact fact = requireExactLogicalFunctionInput(
			supplied.sourceArgument(), supplied.targetRead(), supplied.logicalPosition());
		if(fact != supplied)
			throw new IllegalArgumentException("Logical function input fact is not analysis-owned");
		List<Constraint> owners = graph.constraints().stream().filter(constraint ->
			constraint.kind() == ConstraintKind.DOMINATES
				&& constraint.right() == fact.boundary()
				&& constraint.inputPosition() == fact.callInputPosition()
				&& "function-callsite-control".equals(constraint.evidence())).toList();
		if(owners.size() != 1)
			throw new IllegalArgumentException(
				"Logical function input has no unique physical call-site authority");
		CompiledHopKey consumer = owners.get(0).left();
		if(!isDmlFunctionCallBoundary(consumer))
			throw new IllegalArgumentException(
				"Logical function input consumer is not a compiled DML FunctionOp");
		Hop sourceHop = hopsByKey.get(fact.sourceArgument());
		Hop consumerHop = hopsByKey.get(consumer);
		if(sourceHop == null || !(consumerHop instanceof org.apache.sysds.hops.FunctionOp)
			|| fact.callInputPosition() < 0 || fact.callInputPosition() >= consumerHop.getInput().size()
			|| consumerHop.getInput(fact.callInputPosition()) != sourceHop)
			throw new IllegalArgumentException(
				"Logical function input does not match the exact physical FunctionOp operand");
		NodeShapeFact sourceShape = shapeFacts.shapeFact(fact.sourceArgument()).orElseThrow(() ->
			new IllegalArgumentException("Logical function input has no builder-owned shape fact"));
		if(isPlacementDataType(sourceShape.dataType()))
			requireExactCompiledInputEdge(fact.sourceArgument(), consumer, fact.callInputPosition());
		return consumer;
	}

	public CompiledInputEdgeFact requireExactCompiledInputEdge(CompiledHopKey producer,
		CompiledHopKey consumer, int inputPosition) {
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> byConsumer = inputEdgesByIdentity.get(
			Objects.requireNonNull(producer, "producer"));
		Map<Integer,CompiledInputEdgeFact> byPosition = byConsumer == null ? null
			: byConsumer.get(Objects.requireNonNull(consumer, "consumer"));
		CompiledInputEdgeFact fact = byPosition == null ? null : byPosition.get(inputPosition);
		if(fact == null) {
			boolean producerOwned = inputEdgesByIdentity.keySet().stream().anyMatch(key -> key == producer);
			boolean producerValueMatch = inputEdgesByIdentity.keySet().stream().anyMatch(key -> key.equals(producer));
			boolean consumerOwned = byConsumer != null
				&& byConsumer.keySet().stream().anyMatch(key -> key == consumer);
			boolean consumerValueMatch = byConsumer != null
				&& byConsumer.keySet().stream().anyMatch(key -> key.equals(consumer));
			throw new IllegalArgumentException(
				"Exact compiled input edge fact is missing or foreign: producerOwned=" + producerOwned
					+ " producerValueMatch=" + producerValueMatch
					+ " consumerOwned=" + consumerOwned
					+ " consumerValueMatch=" + consumerValueMatch
					+ " inputPosition=" + inputPosition
					+ " producer=" + producer.normalizedSignature()
					+ " consumer=" + consumer.normalizedSignature());
		}
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
