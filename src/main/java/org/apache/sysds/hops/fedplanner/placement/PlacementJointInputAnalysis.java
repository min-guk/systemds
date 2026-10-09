/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;

/**
 * Finite, path-correlated reaching-definition facts for transient reads.
 *
 * <p>This analysis deliberately models structured CFG alternatives, rather than
 * taking a Cartesian product of the per-variable sets in {@link PlacementProgramFacts.CfgAnalysis}.
 * It is path insensitive with respect to predicate expressions: unless an arm is structurally absent,
 * both arms are retained. Loops converge over the finite set of static definitions. Non-recursive DML
 * calls are expanded per static call occurrence, preserving argument and returned-value provenance.</p>
 */
public final class PlacementJointInputAnalysis {
	private static final int MAX_ENVIRONMENTS = 16_384;
	private static final int MAX_ENVIRONMENT_COMPARISONS = 4_096;
	private static final int MAX_INTERNED_AXES = 4_096;
	private static final int MAX_INTERNED_DEFINITIONS = 65_536;
	public static final class ResourceLimitException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		ResourceLimitException(String message) { super(message); }
	}

	public enum SourceKind { OCCURRENCE, FUNCTION_INPUT, FUNCTION_RETURN }

	/** A static source, retaining the exact compiled occurrence whenever one exists. */
	public static final class Definition implements Comparable<Definition> {
		private final SourceKind kind;
		private final CompiledHopKey occurrence;
		private final int occurrenceOrdinal;
		private final int boundaryPosition;
		private final String callContext;
		private final String provenance;
		private final CompiledHopKey valueOccurrence;
		private final String stableKey;
		private final int hashCode;

		public Definition(SourceKind kind, CompiledHopKey occurrence, int occurrenceOrdinal,
			int boundaryPosition, String callContext, String provenance,
			CompiledHopKey valueOccurrence) {
			this.kind = Objects.requireNonNull(kind, "kind");
			this.occurrence = occurrence;
			this.occurrenceOrdinal = occurrenceOrdinal;
			this.boundaryPosition = boundaryPosition;
			this.callContext = callContext == null ? "main" : callContext;
			this.provenance = provenance == null ? "" : provenance;
			this.valueOccurrence = valueOccurrence;
			stableKey = kind + "|" + occurrenceOrdinal + '|' + boundaryPosition + '|' + this.callContext + '|'
				+ (occurrence == null ? "" : occurrence.normalizedSignature()) + '|'
				+ (valueOccurrence == null ? "" : valueOccurrence.normalizedSignature());
			int definitionHash = kind.hashCode();
			definitionHash = 31 * definitionHash + Objects.hashCode(occurrence);
			definitionHash = 31 * definitionHash + Integer.hashCode(occurrenceOrdinal);
			definitionHash = 31 * definitionHash + Integer.hashCode(boundaryPosition);
			definitionHash = 31 * definitionHash + this.callContext.hashCode();
			definitionHash = 31 * definitionHash + this.provenance.hashCode();
			hashCode = 31 * definitionHash + Objects.hashCode(valueOccurrence);
		}

		public SourceKind kind() { return kind; }
		public CompiledHopKey occurrence() { return occurrence; }
		public int occurrenceOrdinal() { return occurrenceOrdinal; }
		public int boundaryPosition() { return boundaryPosition; }
		public String callContext() { return callContext; }
		public String provenance() { return provenance; }
		public CompiledHopKey valueOccurrence() { return valueOccurrence; }

		@Override public int compareTo(Definition that) {
			return stableKey.compareTo(that.stableKey);
		}

		public String stableKey() { return stableKey; }

		@Override public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof Definition that))
				return false;
			return occurrenceOrdinal == that.occurrenceOrdinal
				&& boundaryPosition == that.boundaryPosition
				&& kind == that.kind
				&& Objects.equals(occurrence, that.occurrence)
				&& callContext.equals(that.callContext)
				&& provenance.equals(that.provenance)
				&& Objects.equals(valueOccurrence, that.valueOccurrence);
		}

		@Override public int hashCode() {
			return hashCode;
		}

		@Override public String toString() {
			return "Definition[kind=" + kind + ", occurrence=" + occurrence
				+ ", occurrenceOrdinal=" + occurrenceOrdinal + ", boundaryPosition=" + boundaryPosition
				+ ", callContext=" + callContext + ", provenance=" + provenance
				+ ", valueOccurrence=" + valueOccurrence + ']';
		}
	}

	public record InputDefinition(int inputPosition, int readOrdinal, Definition source)
		implements Comparable<InputDefinition> {
		public InputDefinition {
			if(inputPosition < 0 || readOrdinal < 0)
				throw new IllegalArgumentException("Input and read ordinals must be non-negative");
			Objects.requireNonNull(source, "source");
		}

		@Override public int compareTo(InputDefinition that) {
			int input = Integer.compare(inputPosition, that.inputPosition);
			return input != 0 ? input : source.compareTo(that.source);
		}
	}

	/** One jointly reachable selection in input-position order. */
	public record JointTuple(List<InputDefinition> inputs) implements Comparable<JointTuple> {
		public JointTuple { inputs = List.copyOf(inputs); }
		@Override public int compareTo(JointTuple that) { return stableKey().compareTo(that.stableKey()); }
		public String stableKey() {
			return inputs.stream().map(input -> input.inputPosition() + "=" + input.source().stableKey())
				.reduce((left, right) -> left + ";" + right).orElse("");
		}
	}

	/** Persistent balanced canonical text for one immutable sorted definition map. */
	private static final class CanonicalDefinitionMap<K extends Comparable<? super K>> {
		private static final class Node<K> {
			private final K key;
			private final PlacementAnalysis.NormalizedText entryText;
			private final Node<K> left;
			private final Node<K> right;
			private final int height;
			private final PlacementAnalysis.NormalizedText text;

			private Node(K key, PlacementAnalysis.NormalizedText entryText, Node<K> left, Node<K> right) {
				this.key = key;
				this.entryText = entryText;
				this.left = left;
				this.right = right;
				height = 1 + Math.max(height(left), height(right));
				PlacementAnalysis.NormalizedTextBuilder builder = new PlacementAnalysis.NormalizedTextBuilder();
				if(left != null)
					builder.append(left.text).append(";");
				builder.append(entryText);
				if(right != null)
					builder.append(";").append(right.text);
				text = builder.build();
			}
		}

		private final Node<K> root;
		private final String mappingSeparator;

		private CanonicalDefinitionMap(Node<K> root, String mappingSeparator) {
			this.root = root;
			this.mappingSeparator = mappingSeparator;
		}

		private static <K extends Comparable<? super K>> CanonicalDefinitionMap<K> from(
			Map<K,Definition> values, String mappingSeparator,
			Function<Definition,String> definitionKey) {
			List<Map.Entry<K,Definition>> entries = new ArrayList<>(values.entrySet());
			return new CanonicalDefinitionMap<>(build(entries, 0, entries.size(), mappingSeparator,
				definitionKey), mappingSeparator);
		}

		private CanonicalDefinitionMap<K> with(K key, Definition definition,
			Function<Definition,String> definitionKey) {
			return new CanonicalDefinitionMap<>(put(root, key,
				entryText(key, definition, mappingSeparator, definitionKey)), mappingSeparator);
		}

		private PlacementAnalysis.NormalizedText text() {
			return root == null ? Environment.EMPTY_TEXT : root.text;
		}

		private static <K extends Comparable<? super K>> Node<K> build(
			List<Map.Entry<K,Definition>> entries, int from, int to, String mappingSeparator,
			Function<Definition,String> definitionKey) {
			if(from == to)
				return null;
			int middle = (from + to) >>> 1;
			Map.Entry<K,Definition> entry = entries.get(middle);
			return new Node<>(entry.getKey(),
				entryText(entry.getKey(), entry.getValue(), mappingSeparator, definitionKey),
				build(entries, from, middle, mappingSeparator, definitionKey),
				build(entries, middle + 1, to, mappingSeparator, definitionKey));
		}

		private static <K extends Comparable<? super K>> Node<K> put(Node<K> node, K key,
			PlacementAnalysis.NormalizedText entryText) {
			if(node == null)
				return new Node<>(key, entryText, null, null);
			int order = key.compareTo(node.key);
			Node<K> updated = order < 0
				? new Node<>(node.key, node.entryText, put(node.left, key, entryText), node.right)
				: order > 0
					? new Node<>(node.key, node.entryText, node.left, put(node.right, key, entryText))
					: new Node<>(key, entryText, node.left, node.right);
			return balance(updated);
		}

		private static <K> Node<K> balance(Node<K> node) {
			int balance = height(node.left) - height(node.right);
			if(balance > 1) {
				Node<K> left = node.left;
				if(height(left.left) < height(left.right))
					left = rotateLeft(left);
				return rotateRight(new Node<>(node.key, node.entryText, left, node.right));
			}
			if(balance < -1) {
				Node<K> right = node.right;
				if(height(right.right) < height(right.left))
					right = rotateRight(right);
				return rotateLeft(new Node<>(node.key, node.entryText, node.left, right));
			}
			return node;
		}

		private static <K> Node<K> rotateLeft(Node<K> node) {
			Node<K> right = node.right;
			Node<K> moved = new Node<>(node.key, node.entryText, node.left, right.left);
			return new Node<>(right.key, right.entryText, moved, right.right);
		}

		private static <K> Node<K> rotateRight(Node<K> node) {
			Node<K> left = node.left;
			Node<K> moved = new Node<>(node.key, node.entryText, left.right, node.right);
			return new Node<>(left.key, left.entryText, left.left, moved);
		}

		private static int height(Node<?> node) {
			return node == null ? 0 : node.height;
		}

		private static <K> PlacementAnalysis.NormalizedText entryText(K key, Definition definition,
			String mappingSeparator, Function<Definition,String> definitionKey) {
			return new PlacementAnalysis.NormalizedTextBuilder().append(key.toString())
				.append(mappingSeparator).append(definitionKey.apply(definition)).build();
		}
	}

	/** Exact analysis-local interning; hashes only route to Map.equals, which proves reuse. */
	private static final class CanonicalAxisPool {
		private final Map<Map<String,Definition>,CanonicalDefinitionMap<String>> values =
			new java.util.HashMap<>();
		private final Map<Map<Integer,Definition>,CanonicalDefinitionMap<Integer>> reads =
			new java.util.HashMap<>();
		private int retainedAxes;
		private int retainedDefinitions;

		private CanonicalDefinitionMap<String> values(Map<String,Definition> key,
			Supplier<CanonicalDefinitionMap<String>> build) {
			CanonicalDefinitionMap<String> retained = values.get(key);
			if(retained != null)
				return retained;
			CanonicalDefinitionMap<String> candidate = build.get();
			if(canRetain(key.size())) {
				values.put(key, candidate);
				retainedAxes++;
				retainedDefinitions += key.size();
			}
			return candidate;
		}

		private CanonicalDefinitionMap<Integer> reads(Map<Integer,Definition> key,
			Supplier<CanonicalDefinitionMap<Integer>> build) {
			CanonicalDefinitionMap<Integer> retained = reads.get(key);
			if(retained != null)
				return retained;
			CanonicalDefinitionMap<Integer> candidate = build.get();
			if(canRetain(key.size())) {
				reads.put(key, candidate);
				retainedAxes++;
				retainedDefinitions += key.size();
			}
			return candidate;
		}

		private boolean canRetain(int definitions) {
			return retainedAxes < MAX_INTERNED_AXES
				&& definitions <= MAX_INTERNED_DEFINITIONS - retainedDefinitions;
		}

		private void clear() {
			values.clear();
			reads.clear();
			retainedAxes = 0;
			retainedDefinitions = 0;
		}
	}

	private static final class Environment implements Comparable<Environment> {
		private static final int ORDERING_PREFIX_LENGTH = 96;
		private static final PlacementAnalysis.NormalizedText EMPTY_TEXT =
			PlacementAnalysis.NormalizedText.literal("");
		private final Map<String,Definition> values;
		private final Map<Integer,Definition> readSources;
		private final Function<Definition,String> definitionKey;
		private final CanonicalAxisPool axisPool;
		private final CanonicalDefinitionMap<String> valuesAxis;
		private final CanonicalDefinitionMap<Integer> readSourcesAxis;
		private final PlacementAnalysis.NormalizedText valuesText;
		private final PlacementAnalysis.NormalizedText readSourcesText;
		private final PlacementAnalysis.NormalizedText orderingText;
		private final String orderingPrefix;
		private final int hashCode;
		private volatile String stableKey;

		Environment(Map<String,Definition> values, Map<Integer,Definition> readSources) {
			this(values, readSources, Definition::stableKey);
		}
		Environment(Map<String,Definition> values, Map<Integer,Definition> readSources,
			Function<Definition,String> definitionKey) {
			this(values, readSources, definitionKey, null, false, null, null);
		}
		Environment(Map<String,Definition> values, Map<Integer,Definition> readSources,
			CanonicalAxisPool axisPool) {
			this(values, readSources, Definition::stableKey, axisPool, false, null, null);
		}
		private Environment(Map<String,Definition> values, Map<Integer,Definition> readSources,
			Function<Definition,String> definitionKey, CanonicalAxisPool axisPool,
			boolean trustedImmutable, CanonicalDefinitionMap<String> retainedValuesAxis,
			CanonicalDefinitionMap<Integer> retainedReadSourcesAxis) {
			this.values = trustedImmutable ? values : immutableSortedCopy(values);
			this.readSources = trustedImmutable ? readSources : immutableSortedCopy(readSources);
			this.definitionKey = definitionKey;
			this.axisPool = axisPool;
			// Supplied axes already describe these exact immutable maps and have been
			// interned by the transition. Never reprobe an unchanged parent axis.
			valuesAxis = retainedValuesAxis != null ? retainedValuesAxis : axisPool == null
				? CanonicalDefinitionMap.from(this.values, "=", definitionKey)
				: axisPool.values(this.values, () -> CanonicalDefinitionMap.from(this.values, "=", definitionKey));
			readSourcesAxis = retainedReadSourcesAxis != null ? retainedReadSourcesAxis : axisPool == null
				? CanonicalDefinitionMap.from(this.readSources, "=>", definitionKey)
				: axisPool.reads(this.readSources, () -> CanonicalDefinitionMap.from(this.readSources, "=>", definitionKey));
			valuesText = valuesAxis.text();
			readSourcesText = readSourcesAxis.text();
			orderingText = new PlacementAnalysis.NormalizedTextBuilder()
				.append(valuesText).append("|reads=").append(readSourcesText).build();
			orderingPrefix = orderingPrefix(this.values, this.readSources, definitionKey);
			hashCode = 31 * this.values.hashCode() + this.readSources.hashCode();
		}
		Map<String,Definition> values() { return values; }
		Map<Integer,Definition> readSources() { return readSources; }
		Environment with(String variable, Definition definition) {
			if(Objects.equals(values.get(variable), definition))
				return this;
			Map<String,Definition> copy = new TreeMap<>(values);
			copy.put(variable, definition);
			Map<String,Definition> updated = Collections.unmodifiableMap(copy);
			CanonicalDefinitionMap<String> axis = axisPool == null
				? valuesAxis.with(variable, definition, definitionKey)
				: axisPool.values(updated, () -> valuesAxis.with(variable, definition, definitionKey));
			return new Environment(updated, readSources, definitionKey, axisPool,
				true, axis, readSourcesAxis);
		}
		Environment observe(int readOrdinal, Definition definition) {
			if(Objects.equals(readSources.get(readOrdinal), definition))
				return this;
			Map<Integer,Definition> copy = new TreeMap<>(readSources);
			copy.put(readOrdinal, definition);
			Map<Integer,Definition> updated = Collections.unmodifiableMap(copy);
			CanonicalDefinitionMap<Integer> axis = axisPool == null
				? readSourcesAxis.with(readOrdinal, definition, definitionKey)
				: axisPool.reads(updated, () -> readSourcesAxis.with(readOrdinal, definition, definitionKey));
			return new Environment(values, updated, definitionKey, axisPool,
				true, valuesAxis, axis);
		}
		Environment nextBlock() {
			if(readSources.isEmpty())
				return this;
			Map<Integer,Definition> empty = Map.of();
			CanonicalDefinitionMap<Integer> axis = axisPool == null
				? CanonicalDefinitionMap.from(empty, "=>", definitionKey)
				: axisPool.reads(empty, () -> CanonicalDefinitionMap.from(empty, "=>", definitionKey));
			return new Environment(values, empty, definitionKey, axisPool, true, valuesAxis, axis);
		}
		@Override public int compareTo(Environment that) {
			if(this == that)
				return 0;
			if(valuesText == that.valuesText)
				return readSourcesText == that.readSourcesText ? 0
					: readSourcesText.compareTo(that.readSourcesText);
			int prefixOrder = orderingPrefix.compareTo(that.orderingPrefix);
			if(prefixOrder != 0)
				return prefixOrder;
			if(orderingText.length() < ORDERING_PREFIX_LENGTH
				&& that.orderingText.length() < ORDERING_PREFIX_LENGTH)
				return 0;
			return orderingText.compareTo(that.orderingText);
		}
		int compareCanonical(Environment that,
			Comparator<PlacementAnalysis.NormalizedText> canonicalOrder) {
			if(this == that)
				return 0;
			if(valuesText == that.valuesText)
				return readSourcesText == that.readSourcesText ? 0
					: canonicalOrder.compare(readSourcesText, that.readSourcesText);
			int prefixOrder = orderingPrefix.compareTo(that.orderingPrefix);
			if(prefixOrder != 0)
				return prefixOrder;
			if(orderingText.length() < ORDERING_PREFIX_LENGTH
				&& that.orderingText.length() < ORDERING_PREFIX_LENGTH)
				return 0;
			return canonicalOrder.compare(orderingText, that.orderingText);
		}
		String stableKey() {
			String key = stableKey;
			if(key == null) {
				key = orderingText.materialize();
				stableKey = key;
			}
			return key;
		}

		@Override public boolean equals(Object other) {
			return this == other || other instanceof Environment that
				&& values.equals(that.values) && readSources.equals(that.readSources);
		}

		@Override public int hashCode() {
			return hashCode;
		}

		private static String orderingPrefix(Map<String,Definition> values,
			Map<Integer,Definition> readSources, Function<Definition,String> definitionKey) {
			StringBuilder prefix = new StringBuilder(ORDERING_PREFIX_LENGTH);
			boolean first = true;
			for(Map.Entry<String,Definition> entry : values.entrySet()) {
				if(!first)
					appendPrefix(prefix, ";");
				appendPrefix(prefix, entry.getKey());
				appendPrefix(prefix, "=");
				if(prefix.length() < ORDERING_PREFIX_LENGTH)
					appendPrefix(prefix, definitionKey.apply(entry.getValue()));
				first = false;
				if(prefix.length() == ORDERING_PREFIX_LENGTH)
					return prefix.toString();
			}
			appendPrefix(prefix, "|reads=");
			boolean firstRead = true;
			for(Map.Entry<Integer,Definition> entry : readSources.entrySet()) {
				if(!firstRead)
					appendPrefix(prefix, ";");
				appendPrefix(prefix, entry.getKey().toString());
				appendPrefix(prefix, "=>");
				if(prefix.length() < ORDERING_PREFIX_LENGTH)
					appendPrefix(prefix, definitionKey.apply(entry.getValue()));
				firstRead = false;
				if(prefix.length() == ORDERING_PREFIX_LENGTH)
					break;
			}
			return prefix.toString();
		}

		private static void appendPrefix(StringBuilder prefix, String value) {
			int remaining = ORDERING_PREFIX_LENGTH - prefix.length();
			if(remaining > 0)
				prefix.append(value, 0, Math.min(remaining, value.length()));
		}

		private static <K,V> Map<K,V> immutableSortedCopy(Map<K,V> source) {
			return Collections.unmodifiableMap(new TreeMap<>(source));
		}
	}

	/** Immutable marker retaining the exact comparator used by every analysis-local environment set. */
	private static final class OrderedEnvironments extends AbstractSet<Environment>
		implements SortedSet<Environment> {
		private final List<Environment> values;
		private final Comparator<Environment> comparator;

		private OrderedEnvironments(List<Environment> ownedValues,
			Comparator<Environment> comparator) {
			values = List.copyOf(ownedValues);
			this.comparator = comparator;
		}

		@Override public Comparator<? super Environment> comparator() { return comparator; }
		@Override public Environment first() {
			if(values.isEmpty())
				throw new java.util.NoSuchElementException();
			return values.get(0);
		}
		@Override public Environment last() {
			if(values.isEmpty())
				throw new java.util.NoSuchElementException();
			return values.get(values.size() - 1);
		}
		@Override public int size() { return values.size(); }
		@Override public boolean contains(Object value) {
			if(!(value instanceof Environment environment))
				return false;
			return Collections.binarySearch(values, environment, comparator) >= 0;
		}
		@Override public Iterator<Environment> iterator() { return values.iterator(); }
		@Override public SortedSet<Environment> subSet(Environment from, Environment to) {
			return immutableTreeView().subSet(from, to);
		}
		@Override public SortedSet<Environment> headSet(Environment to) {
			return immutableTreeView().headSet(to);
		}
		@Override public SortedSet<Environment> tailSet(Environment from) {
			return immutableTreeView().tailSet(from);
		}
		@Override public boolean add(Environment value) {
			throw new UnsupportedOperationException();
		}
		@Override public boolean remove(Object value) {
			throw new UnsupportedOperationException();
		}
		@Override public boolean addAll(java.util.Collection<? extends Environment> values) {
			throw new UnsupportedOperationException();
		}
		@Override public boolean removeAll(java.util.Collection<?> values) {
			throw new UnsupportedOperationException();
		}
		@Override public boolean retainAll(java.util.Collection<?> values) {
			throw new UnsupportedOperationException();
		}
		@Override public boolean removeIf(java.util.function.Predicate<? super Environment> filter) {
			throw new UnsupportedOperationException();
		}
		@Override public void clear() {
			throw new UnsupportedOperationException();
		}

		private SortedSet<Environment> immutableTreeView() {
			TreeSet<Environment> view = new TreeSet<>(comparator);
			view.addAll(values);
			return Collections.unmodifiableSortedSet(view);
		}
	}

	private record Observation(String context, StatementBlock block, Environment environment) { }
	private record InvocationInput(String context, Environment callee) { }
	private record InvocationResult(Set<Environment> callerEnvironments) { }
	private record AnalysisSlice(Set<String> trackedVariables,
		Map<Integer,List<Map<Integer,Definition>>> readSourcesByWitness) { }

	private final DMLProgram program;
	private final List<PlacementGraphFingerprint.HopOccurrence> occurrences;
	private final List<CompiledHopKey> occurrenceKeys;
	private final Map<StatementBlock,List<Integer>> ordinalsByBlock;
	private final Map<Hop,List<Integer>> ordinalsByHop;
	private final Map<CompiledHopKey,Integer> ordinalsByKey;
	private final Set<String> functionBoundaryVariables;
	private final Map<Integer,Set<Observation>> observationsByRead = new TreeMap<>();
	private final Set<String> activeFunctions = new LinkedHashSet<>();
	private final Map<FunctionStatementBlock,Map<InvocationInput,Set<Environment>>> invocationExitMemo =
		new IdentityHashMap<>();
	private final Map<List<Integer>,List<JointTuple>> tupleCache = new java.util.HashMap<>();
	private final Map<String,Definition[]> occurrenceDefinitionCache = new java.util.HashMap<>();
	private final Map<Set<String>,Set<String>> expandedTrackedSlices = new java.util.HashMap<>();
	private final CanonicalAxisPool canonicalAxisPool = new CanonicalAxisPool();
	private final Comparator<PlacementAnalysis.NormalizedText> normalizedTextOrder =
		PlacementAnalysis.normalizedTextComparator();
	private final IdentityHashMap<Environment,IdentityHashMap<Environment,Integer>> environmentComparisonMemo =
		new IdentityHashMap<>();
	private final Comparator<Environment> environmentOrder = this::compareEnvironments;
	private int environmentComparisonMemoEntries;
	private Set<String> trackedVariables = Set.of();
	private AnalysisSlice recentSlice;
	private Map<Set<String>,List<List<Integer>>> consumerReadsBySlice;
	private int analysisPassCount;
	private int invocationAttempts;
	private int functionBodyExecutions;

	private PlacementJointInputAnalysis(DMLProgram program,
		List<PlacementGraphFingerprint.HopOccurrence> retainedOccurrences,
		List<CompiledHopKey> retainedOccurrenceKeys) {
		this.program = Objects.requireNonNull(program, "program");
		occurrences = List.copyOf(retainedOccurrences);
		if(occurrences.size() != retainedOccurrenceKeys.size())
			throw new IllegalArgumentException("Occurrence/key cardinality mismatch");
		Map<StatementBlock,List<Integer>> blocks = new IdentityHashMap<>();
		Map<Hop,List<Integer>> hops = new IdentityHashMap<>();
		Map<CompiledHopKey,Integer> keys = new IdentityHashMap<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			blocks.computeIfAbsent(occurrences.get(ordinal).block(), ignored -> new ArrayList<>()).add(ordinal);
			hops.computeIfAbsent(occurrences.get(ordinal).hop(), ignored -> new ArrayList<>()).add(ordinal);
			if(keys.put(retainedOccurrenceKeys.get(ordinal), ordinal) != null)
				throw new IllegalArgumentException("Duplicate compiled occurrence key identity");
		}
		occurrenceKeys = List.copyOf(retainedOccurrenceKeys);
		ordinalsByBlock = immutableIdentityLists(blocks);
		ordinalsByHop = immutableIdentityLists(hops);
		ordinalsByKey = Collections.unmodifiableMap(keys);
		Set<String> functionVariables = new TreeSet<>();
		for(FunctionStatementBlock function : program.getNamedNSFunctionStatementBlocks().values()) {
			FunctionStatement statement = (FunctionStatement) function.getStatement(0);
			statement.getInputParams().forEach(parameter -> functionVariables.add(parameter.getName()));
			statement.getOutputParams().forEach(parameter -> functionVariables.add(parameter.getName()));
		}
		functionBoundaryVariables = Collections.unmodifiableSet(functionVariables);
	}

	/** Analyze the compiled HOP/statement-block program without mutating it. */
	public static PlacementJointInputAnalysis analyze(DMLProgram program) {
		List<PlacementGraphFingerprint.HopOccurrence> occurrences =
			PlacementGraphFingerprint.orderedOccurrences(program);
		String programId = PlacementProgramFacts.structuralFingerprint(occurrences);
		List<CompiledHopKey> keys = occurrences.stream()
			.map(occurrence -> PlacementProgramFacts.compiledOccurrenceKey(programId, occurrence)).toList();
		return new PlacementJointInputAnalysis(program, occurrences, keys);
	}

	/** Package integration entry point; reuses the exact key instances owned by program facts. */
	static PlacementJointInputAnalysis analyze(DMLProgram program, PlacementProgramFacts facts) {
		Objects.requireNonNull(facts, "facts");
		return new PlacementJointInputAnalysis(program, facts.occurrences(), facts.occurrenceKeys());
	}

	public int occurrenceCount() { return occurrences.size(); }
	public CompiledHopKey occurrenceKey(int ordinal) { return occurrenceKeys.get(ordinal); }
	public Hop occurrenceHop(int ordinal) { return occurrences.get(ordinal).hop(); }
	StatementBlock occurrenceBlock(int ordinal) { return occurrences.get(ordinal).block(); }
	public int occurrenceOrdinal(CompiledHopKey key) {
		Integer ordinal = ordinalsByKey.get(key);
		if(ordinal == null)
			throw new IllegalArgumentException("Compiled occurrence key is not owned by this joint analysis");
		return ordinal;
	}

	/**
	 * Return jointly reachable sources for the requested transient reads.
	 * Reads must belong to the same statement block; this is the consumer-input projection boundary.
	 */
	public synchronized List<JointTuple> tuplesForReads(List<Integer> readOrdinals) {
		if(readOrdinals == null || readOrdinals.isEmpty())
			return List.of();
		List<Integer> reads = List.copyOf(readOrdinals);
		StatementBlock block = null;
		for(int ordinal : reads) {
			checkReadOrdinal(ordinal);
			StatementBlock current = occurrences.get(ordinal).block();
			if(block != null && block != current)
				throw new IllegalArgumentException("Joint reads must share a statement block");
			block = current;
		}
		List<JointTuple> cached = tupleCache.get(reads);
		if(cached != null)
			return cached;
		Set<String> tracked = trackedSlice(reads);
		if(recentSlice == null || !recentSlice.trackedVariables().equals(tracked))
			recentSlice = analyzeFor(tracked);
		List<JointTuple> result = project(reads, recentSlice);
		tupleCache.put(reads, result);
		return result;
	}

	private List<JointTuple> project(List<Integer> reads, AnalysisSlice slice) {
		int witnessOrdinal = reads.stream().max(Integer::compareTo).orElseThrow();
		Set<JointTuple> tuples = new TreeSet<>();
		for(Map<Integer,Definition> readSources :
			slice.readSourcesByWitness().getOrDefault(witnessOrdinal, List.of())) {
			List<InputDefinition> inputs = new ArrayList<>(reads.size());
			boolean complete = true;
			for(int input = 0; input < reads.size(); input++) {
				int readOrdinal = reads.get(input);
				Definition source = readSources.get(readOrdinal);
				if(source == null) { complete = false; break; }
				inputs.add(new InputDefinition(input, readOrdinal, source));
			}
			if(complete)
				tuples.add(new JointTuple(inputs));
		}
		return List.copyOf(tuples);
	}

	/** Identity-exact integration adapter for the placement graph's canonical keys. */
	public List<JointTuple> tuplesForReadKeys(List<CompiledHopKey> readKeys) {
		Objects.requireNonNull(readKeys, "readKeys");
		return tuplesForReads(readKeys.stream().map(this::occurrenceOrdinal).toList());
	}

	/** Convenience lookup for direct transient-read inputs of one compiled consumer occurrence. */
	public synchronized List<JointTuple> tuplesForConsumer(int consumerOrdinal) {
		Hop consumer = occurrenceHop(consumerOrdinal);
		List<Integer> reads = new ArrayList<>();
		for(Hop input : consumer.getInput()) {
			if(!PlacementProgramFacts.isTransientRead(input))
				throw new IllegalArgumentException("Consumer input is not a direct transient read: "
					+ occurrenceKey(consumerOrdinal).normalizedSignature());
			reads.add(uniqueOrdinal(input, occurrences.get(consumerOrdinal).block()));
		}
		cacheConsumerSlice(reads);
		return tuplesForReads(reads);
	}

	public List<JointTuple> tuplesForConsumer(CompiledHopKey consumer) {
		return tuplesForConsumer(occurrenceOrdinal(consumer));
	}

	private Set<Environment> executeSequence(List<StatementBlock> blocks, Set<Environment> incoming,
		String context) {
		Set<Environment> current = ordered(incoming);
		for(StatementBlock block : blocks == null ? List.<StatementBlock>of() : blocks)
			current = executeBlock(block, current, context);
		return current;
	}

	private Set<String> trackedSlice(List<Integer> reads) {
		Set<String> requested = new TreeSet<>();
		for(int read : reads)
			requested.add(variable(read));
		Set<String> key = Collections.unmodifiableSet(requested);
		Set<String> cached = expandedTrackedSlices.get(key);
		if(cached != null)
			return cached;
		Set<String> tracked = new TreeSet<>(requested);
		expandFunctionBoundarySlice(tracked);
		Set<String> result = Collections.unmodifiableSet(tracked);
		expandedTrackedSlices.put(key, result);
		return result;
	}

	private void cacheConsumerSlice(List<Integer> requestedReads) {
		List<Integer> requested = List.copyOf(requestedReads);
		if(tupleCache.containsKey(requested))
			return;
		Set<String> tracked = trackedSlice(requested);
		List<List<Integer>> related = consumerReadsBySlice().get(tracked);
		if(related == null)
			return;
		AnalysisSlice slice = recentSlice != null && recentSlice.trackedVariables().equals(tracked)
			? recentSlice : analyzeFor(tracked);
		recentSlice = slice;
		for(List<Integer> reads : related)
			tupleCache.computeIfAbsent(reads, ignored -> project(reads, slice));
	}

	private Map<Set<String>,List<List<Integer>>> consumerReadsBySlice() {
		if(consumerReadsBySlice != null)
			return consumerReadsBySlice;
		Map<Set<String>,List<List<Integer>>> grouped = new java.util.LinkedHashMap<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			Hop consumer = occurrenceHop(ordinal);
			if(consumer instanceof FunctionOp || consumer.getInput().size() < 2
				|| !consumer.getInput().stream().allMatch(PlacementProgramFacts::isTransientRead))
				continue;
			List<Integer> reads = new ArrayList<>(consumer.getInput().size());
			try {
				for(Hop input : consumer.getInput())
					reads.add(uniqueOrdinal(input, occurrences.get(ordinal).block()));
			}
			catch(IllegalArgumentException ambiguousOccurrence) {
				// This is only an eager reuse index. Preserve the existing on-demand error boundary.
				continue;
			}
			List<Integer> immutableReads = List.copyOf(reads);
			grouped.computeIfAbsent(trackedSlice(immutableReads), ignored -> new ArrayList<>())
				.add(immutableReads);
		}
		Map<Set<String>,List<List<Integer>>> immutable = new java.util.LinkedHashMap<>();
		for(Map.Entry<Set<String>,List<List<Integer>>> entry : grouped.entrySet())
			immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
		consumerReadsBySlice = Collections.unmodifiableMap(immutable);
		return consumerReadsBySlice;
	}

	private AnalysisSlice analyzeFor(Set<String> tracked) {
		clearEnvironmentComparisonMemo();
		canonicalAxisPool.clear();
		observationsByRead.clear();
		activeFunctions.clear();
		invocationExitMemo.clear();
		invocationAttempts = 0;
		functionBodyExecutions = 0;
		trackedVariables = tracked;
		analysisPassCount++;
		try {
			executeSequence(program.getStatementBlocks(),
				Set.of(new Environment(Map.of(), Map.of(), canonicalAxisPool)), "main");
			Map<Integer,List<Map<Integer,Definition>>> compact = new TreeMap<>();
			for(Map.Entry<Integer,Set<Observation>> entry : observationsByRead.entrySet()) {
				Set<Map<Integer,Definition>> snapshots = new LinkedHashSet<>();
				for(Observation observation : entry.getValue())
					snapshots.add(observation.environment().readSources());
				compact.put(entry.getKey(), List.copyOf(snapshots));
			}
			return new AnalysisSlice(tracked, Collections.unmodifiableMap(compact));
		}
		finally {
			clearEnvironmentComparisonMemo();
			canonicalAxisPool.clear();
			observationsByRead.clear();
			activeFunctions.clear();
			invocationExitMemo.clear();
			trackedVariables = Set.of();
		}
	}

	private void expandFunctionBoundarySlice(Set<String> tracked) {
		boolean changed;
		do {
			changed = false;
			for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
				if(!(occurrence.hop() instanceof FunctionOp call))
					continue;
				if(call.getFunctionType() != FunctionOp.FunctionType.DML)
					continue;
				String functionKey = DMLProgram.DEFAULT_NAMESPACE.equals(call.getFunctionNamespace())
					? call.getFunctionName() : DMLProgram.constructFunctionKey(
						call.getFunctionNamespace(), call.getFunctionName());
				FunctionStatementBlock function = program.getNamedNSFunctionStatementBlocks().get(functionKey);
				if(function == null)
					continue;
				FunctionStatement statement = (FunctionStatement) function.getStatement(0);
				List<String> formals = statement.getInputParams().stream()
					.map(parameter -> parameter.getName()).toList();
				for(int position = 0; position < formals.size() && position < call.getInput().size(); position++)
					if(tracked.contains(formals.get(position))) {
						Hop actual = call.getInput(position);
						if(PlacementProgramFacts.isTransientRead(actual))
							changed |= tracked.add(actual.getName());
					}
				String[] outputs = call.getOutputVariableNames();
				List<String> formalOutputs = statement.getOutputParams().stream()
					.map(parameter -> parameter.getName()).toList();
				for(int position = 0; outputs != null && position < outputs.length
					&& position < formalOutputs.size(); position++)
					if(outputs[position] != null && tracked.contains(outputs[position]))
						changed |= tracked.add(formalOutputs.get(position));
			}
		} while(changed);
	}

	private Set<Environment> executeBlock(StatementBlock block, Set<Environment> incoming, String context) {
		if(block instanceof WhileStatementBlock) {
			WhileStatement statement = (WhileStatement) block.getStatement(0);
			return loopFixedPoint(block, statement.getBody(), nextBlock(incoming), context);
		}
		if(block instanceof ForStatementBlock) {
			ForStatement statement = (ForStatement) block.getStatement(0);
			return loopFixedPoint(block, statement.getBody(), nextBlock(incoming), context);
		}
		Set<Environment> afterHeader = executeOccurrences(block, nextBlock(incoming), context);
		if(block instanceof IfStatementBlock) {
			IfStatement statement = (IfStatement) block.getStatement(0);
			Set<Environment> thenStates = executeSequence(statement.getIfBody(), afterHeader, context);
			Set<Environment> elseStates = statement.getElseBody() == null || statement.getElseBody().isEmpty()
				? afterHeader : executeSequence(statement.getElseBody(), afterHeader, context);
			return union(thenStates, elseStates);
		}
		if(block instanceof FunctionStatementBlock) {
			FunctionStatement statement = (FunctionStatement) block.getStatement(0);
			return executeSequence(statement.getBody(), afterHeader, context);
		}
		return nextBlock(afterHeader);
	}

	private Set<Environment> loopFixedPoint(StatementBlock headerBlock,
		List<StatementBlock> body, Set<Environment> entry, String context) {
		Set<Environment> header = ordered(entry);
		boolean changed;
		do {
			Set<Environment> observedHeader = executeOccurrences(
				headerBlock, nextBlock(header), context);
			Set<Environment> bodyExit = executeSequence(body, observedHeader, context);
			Set<Environment> expanded = union(header, bodyExit);
			changed = !expanded.equals(header);
			header = expanded;
		} while(changed);
		return executeOccurrences(headerBlock, nextBlock(header), context);
	}

	private Set<Environment> executeOccurrences(StatementBlock block, Set<Environment> incoming,
		String context) {
		Set<Environment> states = ordered(incoming);
		for(int ordinal : ordinalsByBlock.getOrDefault(block, List.of())) {
			Hop hop = occurrences.get(ordinal).hop();
			if(PlacementProgramFacts.isTransientRead(hop) && tracks(variable(ordinal), context)) {
				List<Environment> observed = new ArrayList<>(states.size());
				for(Environment state : states) {
					Definition source = state.values().get(variable(ordinal));
					observed.add(source == null ? state : state.observe(ordinal, source));
				}
				states = freezeCandidates(observed);
				Set<Observation> observations = observationsByRead.computeIfAbsent(ordinal,
					ignored -> new LinkedHashSet<>());
				for(Environment state : states)
					observations.add(new Observation(context, block, state));
			}
			if(hop instanceof FunctionOp call && call.getFunctionType() == FunctionOp.FunctionType.DML)
				states = invoke(ordinal, call, states, context).callerEnvironments();
			else if(PlacementProgramFacts.isTransientWrite(hop)
				&& tracks(variable(ordinal), context)) {
				List<Environment> updated = new ArrayList<>(states.size());
				for(Environment state : states)
					updated.add(state.with(variable(ordinal), writeDefinition(ordinal, state, context)));
				states = freezeCandidates(updated);
			}
		}
		return states;
	}

	private boolean tracks(String variable, String context) {
		return trackedVariables.contains(variable)
			|| !"main".equals(context) && functionBoundaryVariables.contains(variable);
	}

	private InvocationResult invoke(int callOrdinal, FunctionOp call, Set<Environment> callerStates,
		String parentContext) {
		String functionKey = DMLProgram.DEFAULT_NAMESPACE.equals(call.getFunctionNamespace())
			? call.getFunctionName() : DMLProgram.constructFunctionKey(call.getFunctionNamespace(), call.getFunctionName());
		FunctionStatementBlock function = program.getNamedNSFunctionStatementBlocks().get(functionKey);
		if(function == null)
			throw new UnsupportedOperationException("DML call has no compiled function body: " + functionKey);
		if(!activeFunctions.add(functionKey))
			throw new UnsupportedOperationException("Recursive DML functions are outside joint-input analysis: "
				+ functionKey);
		try {
			FunctionStatement statement = (FunctionStatement) function.getStatement(0);
			List<String> formalInputs = statement.getInputParams().stream().map(parameter -> parameter.getName()).toList();
			List<String> formalOutputs = statement.getOutputParams().stream().map(parameter -> parameter.getName()).toList();
			String context = parentContext + "/call-" + callOrdinal;
			TreeSet<Environment> result = newEnvironmentSet();
			for(Environment caller : callerStates) {
				Environment callee = bindArguments(callOrdinal, call, formalInputs, caller, context);
				invocationAttempts++;
				InvocationInput input = new InvocationInput(context, callee);
				Map<InvocationInput,Set<Environment>> exitsByInput = invocationExitMemo.get(function);
				Set<Environment> exits = exitsByInput == null ? null : exitsByInput.get(input);
				if(exits == null) {
					functionBodyExecutions++;
					exits = executeBlock(function, Set.of(callee), context);
					if(exitsByInput == null) {
						exitsByInput = new java.util.HashMap<>();
						invocationExitMemo.put(function, exitsByInput);
					}
					exitsByInput.put(input, exits);
				}
				for(Environment exit : exits) {
					Environment returned = caller;
					String[] outputNames = call.getOutputVariableNames();
					for(int position = 0; position < formalOutputs.size() && outputNames != null
						&& position < outputNames.length; position++) {
						Definition source = exit.values().get(formalOutputs.get(position));
						if(source != null && outputNames[position] != null
							&& trackedVariables.contains(outputNames[position]))
							returned = returned.with(outputNames[position], new Definition(SourceKind.FUNCTION_RETURN,
								occurrenceKeys.get(callOrdinal), callOrdinal, position, context,
								source.stableKey(),
								source.valueOccurrence()));
					}
					result.add(returned);
				}
			}
			return new InvocationResult(freezeOwned(result));
		}
		finally {
			activeFunctions.remove(functionKey);
		}
	}

	private Environment bindArguments(int callOrdinal, FunctionOp call, List<String> formalInputs,
		Environment caller, String context) {
		Environment callee = new Environment(Map.of(), Map.of(), canonicalAxisPool);
		for(int position = 0; position < formalInputs.size() && position < call.getInput().size(); position++) {
			Hop actual = call.getInput(position);
			Definition source;
			if(PlacementProgramFacts.isTransientRead(actual))
				source = caller.values().get(actual.getName());
			else {
				int actualOrdinal = uniqueOrdinal(actual, occurrences.get(callOrdinal).block());
				source = occurrenceDefinition(actualOrdinal, context);
			}
			if(source != null)
				callee = callee.with(formalInputs.get(position), new Definition(SourceKind.FUNCTION_INPUT,
					occurrenceKeys.get(callOrdinal), callOrdinal, position, context, source.stableKey(),
					source.valueOccurrence()));
		}
		return callee;
	}

	private Definition occurrenceDefinition(int ordinal, String context) {
		Definition[] definitions = occurrenceDefinitionCache.computeIfAbsent(context,
			ignored -> new Definition[occurrences.size()]);
		Definition definition = definitions[ordinal];
		if(definition == null) {
			definition = new Definition(SourceKind.OCCURRENCE, occurrenceKeys.get(ordinal), ordinal, -1,
				context, "", occurrenceKeys.get(ordinal));
			definitions[ordinal] = definition;
		}
		return definition;
	}

	private Definition writeDefinition(int ordinal, Environment state, String context) {
		Definition supplied = aliasInputDefinition(occurrences.get(ordinal).hop(), state);
		CompiledHopKey write = occurrenceKeys.get(ordinal);
		return supplied == null
			? occurrenceDefinition(ordinal, context)
			: new Definition(SourceKind.OCCURRENCE, write, ordinal, -1, context,
				supplied.stableKey(), supplied.valueOccurrence());
	}

	private Definition aliasInputDefinition(Hop write, Environment state) {
		if(!PlacementProgramFacts.isTransientWrite(write) || write.getInput().isEmpty())
			return null;
		Hop input = write.getInput(0);
		if(input instanceof UnaryOp unary && unary.getOp() == OpOp1._PLACEMENT
			&& !input.getInput().isEmpty())
			input = input.getInput(0);
		return PlacementProgramFacts.isTransientRead(input) ? state.values().get(input.getName()) : null;
	}

	private int uniqueOrdinal(Hop hop, StatementBlock preferredBlock) {
		List<Integer> matches = ordinalsByHop.getOrDefault(hop, List.of());
		if(matches.size() == 1)
			return matches.get(0);
		List<Integer> local = matches.stream().filter(ordinal -> occurrences.get(ordinal).block() == preferredBlock)
			.toList();
		if(local.size() == 1)
			return local.get(0);
		throw new IllegalArgumentException("Hop does not have one compiled occurrence in the requested context");
	}

	private String variable(int ordinal) {
		return PlacementProgramFacts.lexicalVariable(occurrences.get(ordinal).hop(), ordinal);
	}

	private void checkReadOrdinal(int ordinal) {
		if(ordinal < 0 || ordinal >= occurrences.size()
			|| !PlacementProgramFacts.isTransientRead(occurrences.get(ordinal).hop()))
			throw new IllegalArgumentException("Not a transient-read occurrence ordinal: " + ordinal);
	}

	private Set<Environment> union(Set<Environment> left, Set<Environment> right) {
		Set<Environment> orderedLeft = ordered(left);
		Set<Environment> orderedRight = ordered(right);
		if(orderedLeft == orderedRight || orderedRight.isEmpty())
			return orderedLeft;
		if(orderedLeft.isEmpty())
			return orderedRight;
		List<Environment> result = new ArrayList<>(orderedLeft.size() + orderedRight.size());
		Iterator<Environment> leftIterator = orderedLeft.iterator();
		Iterator<Environment> rightIterator = orderedRight.iterator();
		Environment leftValue = leftIterator.hasNext() ? leftIterator.next() : null;
		Environment rightValue = rightIterator.hasNext() ? rightIterator.next() : null;
		boolean rightContributed = false;
		while(leftValue != null && rightValue != null) {
			int order = environmentOrder.compare(leftValue, rightValue);
			if(order <= 0) {
				result.add(leftValue);
				leftValue = leftIterator.hasNext() ? leftIterator.next() : null;
				if(order == 0)
					rightValue = rightIterator.hasNext() ? rightIterator.next() : null;
			}
			else {
				result.add(rightValue);
				rightContributed = true;
				rightValue = rightIterator.hasNext() ? rightIterator.next() : null;
			}
		}
		while(leftValue != null) {
			result.add(leftValue);
			leftValue = leftIterator.hasNext() ? leftIterator.next() : null;
		}
		while(rightValue != null) {
			result.add(rightValue);
			rightContributed = true;
			rightValue = rightIterator.hasNext() ? rightIterator.next() : null;
		}
		return rightContributed ? freezeAlreadyOrdered(result) : orderedLeft;
	}

	private int compareEnvironments(Environment left, Environment right) {
		if(left == right)
			return 0;
		IdentityHashMap<Environment,Integer> forward = environmentComparisonMemo.get(left);
		Integer cached = forward == null ? null : forward.get(right);
		if(cached != null)
			return cached;
		IdentityHashMap<Environment,Integer> reverse = environmentComparisonMemo.get(right);
		cached = reverse == null ? null : reverse.get(left);
		if(cached != null)
			return -cached;
		int order = Integer.signum(left.compareCanonical(right, normalizedTextOrder));
		if(environmentComparisonMemoEntries == MAX_ENVIRONMENT_COMPARISONS)
			clearEnvironmentComparisonMemo();
		environmentComparisonMemo.computeIfAbsent(left, ignored -> new IdentityHashMap<>())
			.put(right, order);
		environmentComparisonMemoEntries++;
		return order;
	}

	private void clearEnvironmentComparisonMemo() {
		environmentComparisonMemo.clear();
		environmentComparisonMemoEntries = 0;
	}

	private Set<Environment> ordered(Set<Environment> values) {
		return values instanceof OrderedEnvironments ordered
			&& ordered.comparator() == environmentOrder ? values : bounded(values);
	}

	private Set<Environment> nextBlock(Set<Environment> values) {
		boolean hasObservations = false;
		for(Environment value : values)
			if(!value.readSources().isEmpty()) {
				hasObservations = true;
				break;
			}
		if(!hasObservations)
			return ordered(values);
		List<Environment> result = new ArrayList<>(values.size());
		for(Environment value : values)
			result.add(value.nextBlock());
		return freezeCandidates(result);
	}

	private Set<Environment> bounded(Set<Environment> values) {
		if(values instanceof OrderedEnvironments ordered
			&& ordered.comparator() == environmentOrder)
			return values;
		return freezeCandidates(new ArrayList<>(values));
	}

	private TreeSet<Environment> newEnvironmentSet() {
		return new TreeSet<>(environmentOrder);
	}

	private Set<Environment> freezeCandidates(List<Environment> candidates) {
		List<Environment> ordered = new ArrayList<>(candidates);
		ordered.sort(environmentOrder);
		if(ordered.size() > 1) {
			List<Environment> compacted = new ArrayList<>(ordered.size());
			Environment retained = ordered.get(0);
			compacted.add(retained);
			for(int index = 1; index < ordered.size(); index++) {
				Environment candidate = ordered.get(index);
				if(environmentOrder.compare(retained, candidate) != 0) {
					retained = candidate;
					compacted.add(candidate);
				}
			}
			ordered = compacted;
		}
		return freezeAlreadyOrdered(ordered);
	}

	private Set<Environment> freezeAlreadyOrdered(List<Environment> values) {
		if(values.size() > MAX_ENVIRONMENTS)
			throw new ResourceLimitException("Joint-input CFG exceeded finite environment limit "
				+ MAX_ENVIRONMENTS + "; refusing an inexact Cartesian fallback");
		return new OrderedEnvironments(values, environmentOrder);
	}

	private Set<Environment> freezeOwned(TreeSet<Environment> values) {
		return freezeAlreadyOrdered(new ArrayList<>(values));
	}

	private static <K> Map<K,List<Integer>> immutableIdentityLists(Map<K,List<Integer>> source) {
		Map<K,List<Integer>> result = new IdentityHashMap<>();
		for(Map.Entry<K,List<Integer>> entry : source.entrySet())
			result.put(entry.getKey(), List.copyOf(entry.getValue()));
		return Collections.unmodifiableMap(result);
	}
}
