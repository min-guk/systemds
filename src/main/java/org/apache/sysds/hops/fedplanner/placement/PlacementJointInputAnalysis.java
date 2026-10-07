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

	private static final class Environment implements Comparable<Environment> {
		private static final PlacementAnalysis.NormalizedText EMPTY_TEXT =
			PlacementAnalysis.NormalizedText.literal("");
		private final Map<String,Definition> values;
		private final Map<Integer,Definition> readSources;
		private final Function<Definition,String> definitionKey;
		private final PlacementAnalysis.NormalizedText valuesText;
		private final PlacementAnalysis.NormalizedText readSourcesText;
		private final PlacementAnalysis.NormalizedText orderingText;
		private final int hashCode;
		private volatile String stableKey;

		Environment(Map<String,Definition> values, Map<Integer,Definition> readSources) {
			this(values, readSources, Definition::stableKey);
		}
		Environment(Map<String,Definition> values, Map<Integer,Definition> readSources,
			Function<Definition,String> definitionKey) {
			this(values, readSources, definitionKey, false, null, null);
		}
		private Environment(Map<String,Definition> values, Map<Integer,Definition> readSources,
			Function<Definition,String> definitionKey, boolean trustedImmutable,
			PlacementAnalysis.NormalizedText retainedValuesText,
			PlacementAnalysis.NormalizedText retainedReadSourcesText) {
			this.values = trustedImmutable ? values : immutableSortedCopy(values);
			this.readSources = trustedImmutable ? readSources : immutableSortedCopy(readSources);
			this.definitionKey = definitionKey;
			valuesText = retainedValuesText == null
				? valuesText(this.values, definitionKey) : retainedValuesText;
			readSourcesText = retainedReadSourcesText == null
				? readSourcesText(this.readSources, definitionKey) : retainedReadSourcesText;
			orderingText = new PlacementAnalysis.NormalizedTextBuilder()
				.append(valuesText).append("|reads=").append(readSourcesText).build();
			hashCode = 31 * this.values.hashCode() + this.readSources.hashCode();
		}
		Map<String,Definition> values() { return values; }
		Map<Integer,Definition> readSources() { return readSources; }
		Environment with(String variable, Definition definition) {
			if(Objects.equals(values.get(variable), definition))
				return this;
			Map<String,Definition> copy = new TreeMap<>(values);
			copy.put(variable, definition);
			return new Environment(Collections.unmodifiableMap(copy), readSources, definitionKey,
				true, null, readSourcesText);
		}
		Environment observe(int readOrdinal, Definition definition) {
			if(Objects.equals(readSources.get(readOrdinal), definition))
				return this;
			Map<Integer,Definition> copy = new TreeMap<>(readSources);
			copy.put(readOrdinal, definition);
			return new Environment(values, Collections.unmodifiableMap(copy), definitionKey,
				true, valuesText, null);
		}
		Environment nextBlock() {
			return readSources.isEmpty() ? this
				: new Environment(values, Map.of(), definitionKey, true, valuesText, EMPTY_TEXT);
		}
		@Override public int compareTo(Environment that) {
			return this == that ? 0 : orderingText.compareTo(that.orderingText);
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

		private static PlacementAnalysis.NormalizedText valuesText(Map<String,Definition> values,
			Function<Definition,String> definitionKey) {
			if(values.isEmpty())
				return EMPTY_TEXT;
			PlacementAnalysis.NormalizedTextBuilder key = new PlacementAnalysis.NormalizedTextBuilder();
			boolean first = true;
			for(Map.Entry<String,Definition> entry : values.entrySet()) {
				if(!first)
					key.append(";");
				key.append(entry.getKey()).append("=").append(definitionKey.apply(entry.getValue()));
				first = false;
			}
			return key.build();
		}

		private static PlacementAnalysis.NormalizedText readSourcesText(
			Map<Integer,Definition> readSources, Function<Definition,String> definitionKey) {
			if(readSources.isEmpty())
				return EMPTY_TEXT;
			PlacementAnalysis.NormalizedTextBuilder key = new PlacementAnalysis.NormalizedTextBuilder();
			boolean firstRead = true;
			for(Map.Entry<Integer,Definition> entry : readSources.entrySet()) {
				if(!firstRead)
					key.append(";");
				key.append(entry.getKey().toString()).append("=>")
					.append(definitionKey.apply(entry.getValue()));
				firstRead = false;
			}
			return key.build();
		}

		private static <K,V> Map<K,V> immutableSortedCopy(Map<K,V> source) {
			return Collections.unmodifiableMap(new TreeMap<>(source));
		}
	}

	/** Immutable marker retaining the exact comparator used by every analysis-local environment set. */
	private static final class OrderedEnvironments extends AbstractSet<Environment>
		implements SortedSet<Environment> {
		private final SortedSet<Environment> values;

		private OrderedEnvironments(TreeSet<Environment> ownedValues) {
			values = Collections.unmodifiableSortedSet(ownedValues);
		}

		@Override public Comparator<? super Environment> comparator() { return values.comparator(); }
		@Override public Environment first() { return values.first(); }
		@Override public Environment last() { return values.last(); }
		@Override public int size() { return values.size(); }
		@Override public boolean contains(Object value) { return values.contains(value); }
		@Override public Iterator<Environment> iterator() { return values.iterator(); }
		@Override public SortedSet<Environment> subSet(Environment from, Environment to) {
			return values.subSet(from, to);
		}
		@Override public SortedSet<Environment> headSet(Environment to) { return values.headSet(to); }
		@Override public SortedSet<Environment> tailSet(Environment from) { return values.tailSet(from); }
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
	private final Comparator<PlacementAnalysis.NormalizedText> normalizedTextOrder =
		PlacementAnalysis.normalizedTextComparator();
	private final Comparator<Environment> environmentOrder = (left, right) -> left == right ? 0
		: normalizedTextOrder.compare(left.orderingText, right.orderingText);
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
		observationsByRead.clear();
		activeFunctions.clear();
		invocationExitMemo.clear();
		invocationAttempts = 0;
		functionBodyExecutions = 0;
		trackedVariables = tracked;
		analysisPassCount++;
		try {
			executeSequence(program.getStatementBlocks(),
				Set.of(new Environment(Map.of(), Map.of())), "main");
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
				TreeSet<Environment> observed = newEnvironmentSet();
				for(Environment state : states) {
					Definition source = state.values().get(variable(ordinal));
					observed.add(source == null ? state : state.observe(ordinal, source));
				}
				states = freezeOwned(observed);
				Set<Observation> observations = observationsByRead.computeIfAbsent(ordinal,
					ignored -> new LinkedHashSet<>());
				for(Environment state : states)
					observations.add(new Observation(context, block, state));
			}
			if(hop instanceof FunctionOp call && call.getFunctionType() == FunctionOp.FunctionType.DML)
				states = invoke(ordinal, call, states, context).callerEnvironments();
			else if(PlacementProgramFacts.isTransientWrite(hop)
				&& tracks(variable(ordinal), context)) {
				TreeSet<Environment> updated = newEnvironmentSet();
				for(Environment state : states)
					updated.add(state.with(variable(ordinal), writeDefinition(ordinal, state, context)));
				states = freezeOwned(updated);
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
		Environment callee = new Environment(Map.of(), Map.of());
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
		TreeSet<Environment> result = mutableOrderedCopy(left);
		result.addAll(right);
		return freezeOwned(result);
	}

	private Set<Environment> ordered(Set<Environment> values) {
		return values instanceof OrderedEnvironments ordered
			&& ordered.comparator() == environmentOrder ? values : bounded(values);
	}

	private Set<Environment> nextBlock(Set<Environment> values) {
		TreeSet<Environment> result = newEnvironmentSet();
		for(Environment value : values)
			result.add(value.nextBlock());
		return freezeOwned(result);
	}

	private Set<Environment> bounded(Set<Environment> values) {
		if(values instanceof OrderedEnvironments ordered
			&& ordered.comparator() == environmentOrder)
			return values;
		return freezeOwned(mutableOrderedCopy(values));
	}

	private TreeSet<Environment> newEnvironmentSet() {
		return new TreeSet<>(environmentOrder);
	}

	@SuppressWarnings("unchecked")
	private TreeSet<Environment> mutableOrderedCopy(Set<Environment> values) {
		if(values instanceof SortedSet<?> sorted && sorted.comparator() == environmentOrder)
			return new TreeSet<>((SortedSet<Environment>)sorted);
		TreeSet<Environment> result = newEnvironmentSet();
		result.addAll(values);
		return result;
	}

	private Set<Environment> freezeOwned(TreeSet<Environment> values) {
		if(values.size() > MAX_ENVIRONMENTS)
			throw new ResourceLimitException("Joint-input CFG exceeded finite environment limit "
				+ MAX_ENVIRONMENTS + "; refusing an inexact Cartesian fallback");
		return new OrderedEnvironments(values);
	}

	private static <K> Map<K,List<Integer>> immutableIdentityLists(Map<K,List<Integer>> source) {
		Map<K,List<Integer>> result = new IdentityHashMap<>();
		for(Map.Entry<K,List<Integer>> entry : source.entrySet())
			result.put(entry.getKey(), List.copyOf(entry.getValue()));
		return Collections.unmodifiableMap(result);
	}
}
