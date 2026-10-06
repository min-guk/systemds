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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
	public record Definition(SourceKind kind, CompiledHopKey occurrence, int occurrenceOrdinal,
		int boundaryPosition, String callContext, String provenance,
		CompiledHopKey valueOccurrence) implements Comparable<Definition> {
		public Definition {
			Objects.requireNonNull(kind, "kind");
			callContext = callContext == null ? "main" : callContext;
			provenance = provenance == null ? "" : provenance;
		}

		@Override public int compareTo(Definition that) {
			return stableKey().compareTo(that.stableKey());
		}

		public String stableKey() {
			return kind + "|" + occurrenceOrdinal + '|' + boundaryPosition + '|' + callContext + '|'
				+ (occurrence == null ? "" : occurrence.normalizedSignature()) + '|'
				+ (valueOccurrence == null ? "" : valueOccurrence.normalizedSignature());
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

	private record Environment(Map<String,Definition> values, Map<Integer,Definition> readSources)
		implements Comparable<Environment> {
		Environment {
			values = Collections.unmodifiableMap(new TreeMap<>(values));
			readSources = Collections.unmodifiableMap(new TreeMap<>(readSources));
		}
		Environment with(String variable, Definition definition) {
			Map<String,Definition> copy = new TreeMap<>(values);
			copy.put(variable, definition);
			return new Environment(copy, readSources);
		}
		Environment observe(int readOrdinal, Definition definition) {
			Map<Integer,Definition> copy = new TreeMap<>(readSources);
			copy.put(readOrdinal, definition);
			return new Environment(values, copy);
		}
		Environment nextBlock() { return readSources.isEmpty() ? this : new Environment(values, Map.of()); }
		@Override public int compareTo(Environment that) { return stableKey().compareTo(that.stableKey()); }
		String stableKey() {
			String definitions = values.entrySet().stream()
				.map(entry -> entry.getKey() + '=' + entry.getValue().stableKey())
				.reduce((left, right) -> left + ";" + right).orElse("");
			String reads = readSources.entrySet().stream()
				.map(entry -> entry.getKey() + "=>" + entry.getValue().stableKey())
				.reduce((left, right) -> left + ";" + right).orElse("");
			return definitions + "|reads=" + reads;
		}
	}

	private record Observation(String context, StatementBlock block, Environment environment) { }
	private record InvocationResult(Set<Environment> callerEnvironments) { }

	private final DMLProgram program;
	private final List<PlacementGraphFingerprint.HopOccurrence> occurrences;
	private final List<CompiledHopKey> occurrenceKeys;
	private final Map<StatementBlock,List<Integer>> ordinalsByBlock;
	private final Map<Hop,List<Integer>> ordinalsByHop;
	private final Map<CompiledHopKey,Integer> ordinalsByKey;
	private final Set<String> functionBoundaryVariables;
	private final Map<Integer,Set<Observation>> observationsByRead = new TreeMap<>();
	private final Set<String> activeFunctions = new LinkedHashSet<>();
	private final Map<List<Integer>,List<JointTuple>> tupleCache = new java.util.HashMap<>();
	private Set<String> trackedVariables = Set.of();

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
		analyzeFor(reads);
		int witnessOrdinal = reads.stream().max(Integer::compareTo).orElseThrow();
		Set<JointTuple> tuples = new TreeSet<>();
		for(Observation observation : observationsByRead.getOrDefault(witnessOrdinal, Set.of())) {
			List<InputDefinition> inputs = new ArrayList<>(reads.size());
			boolean complete = true;
			for(int input = 0; input < reads.size(); input++) {
				int readOrdinal = reads.get(input);
				Definition source = observation.environment().readSources().get(readOrdinal);
				if(source == null) { complete = false; break; }
				inputs.add(new InputDefinition(input, readOrdinal, source));
			}
			if(complete)
				tuples.add(new JointTuple(inputs));
		}
		List<JointTuple> result = List.copyOf(tuples);
		tupleCache.put(reads, result);
		return result;
	}

	/** Identity-exact integration adapter for the placement graph's canonical keys. */
	public List<JointTuple> tuplesForReadKeys(List<CompiledHopKey> readKeys) {
		Objects.requireNonNull(readKeys, "readKeys");
		return tuplesForReads(readKeys.stream().map(this::occurrenceOrdinal).toList());
	}

	/** Convenience lookup for direct transient-read inputs of one compiled consumer occurrence. */
	public List<JointTuple> tuplesForConsumer(int consumerOrdinal) {
		Hop consumer = occurrenceHop(consumerOrdinal);
		List<Integer> reads = new ArrayList<>();
		for(Hop input : consumer.getInput()) {
			if(!PlacementProgramFacts.isTransientRead(input))
				throw new IllegalArgumentException("Consumer input is not a direct transient read: "
					+ occurrenceKey(consumerOrdinal).normalizedSignature());
			reads.add(uniqueOrdinal(input, occurrences.get(consumerOrdinal).block()));
		}
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

	private synchronized void analyzeFor(List<Integer> reads) {
		observationsByRead.clear();
		activeFunctions.clear();
		Set<String> tracked = new TreeSet<>();
		for(int read : reads)
			tracked.add(variable(read));
		expandFunctionBoundarySlice(tracked);
		trackedVariables = Collections.unmodifiableSet(tracked);
		executeSequence(program.getStatementBlocks(), Set.of(new Environment(Map.of(), Map.of())), "main");
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
				Set<Environment> observed = new TreeSet<>();
				for(Environment state : states) {
					Definition source = state.values().get(variable(ordinal));
					observed.add(source == null ? state : state.observe(ordinal, source));
				}
				states = bounded(observed);
				Set<Observation> observations = observationsByRead.computeIfAbsent(ordinal,
					ignored -> new LinkedHashSet<>());
				for(Environment state : states)
					observations.add(new Observation(context, block, state));
			}
			if(hop instanceof FunctionOp call && call.getFunctionType() == FunctionOp.FunctionType.DML)
				states = invoke(ordinal, call, states, context).callerEnvironments();
			else if(PlacementProgramFacts.isTransientWrite(hop)
				&& tracks(variable(ordinal), context)) {
				Set<Environment> updated = new TreeSet<>();
				for(Environment state : states)
					updated.add(state.with(variable(ordinal), writeDefinition(ordinal, state, context)));
				states = bounded(updated);
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
			Set<Environment> result = new TreeSet<>();
			for(Environment caller : callerStates) {
				Environment callee = bindArguments(callOrdinal, call, formalInputs, caller, context);
				Set<Environment> exits = executeBlock(function, Set.of(callee), context);
				for(Environment exit : exits) {
					Environment returned = caller;
					String[] outputNames = call.getOutputVariableNames();
					for(int position = 0; position < formalOutputs.size() && outputNames != null
						&& position < outputNames.length; position++) {
						Definition source = exit.values().get(formalOutputs.get(position));
						if(source != null && outputNames[position] != null
							&& trackedVariables.contains(outputNames[position]))
							returned = returned.with(outputNames[position], new Definition(SourceKind.FUNCTION_RETURN,
								occurrenceKeys.get(callOrdinal), callOrdinal, position, context, source.stableKey(),
								source.valueOccurrence()));
					}
					result.add(returned);
				}
			}
			return new InvocationResult(bounded(result));
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
		return new Definition(SourceKind.OCCURRENCE, occurrenceKeys.get(ordinal), ordinal, -1, context, "",
			occurrenceKeys.get(ordinal));
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
		Set<Environment> result = new TreeSet<>(left);
		result.addAll(right);
		return bounded(result);
	}

	private Set<Environment> ordered(Set<Environment> values) { return bounded(new TreeSet<>(values)); }

	private Set<Environment> nextBlock(Set<Environment> values) {
		Set<Environment> result = new TreeSet<>();
		for(Environment value : values)
			result.add(value.nextBlock());
		return bounded(result);
	}

	private Set<Environment> bounded(Set<Environment> values) {
		if(values.size() > MAX_ENVIRONMENTS)
			throw new ResourceLimitException("Joint-input CFG exceeded finite environment limit "
				+ MAX_ENVIRONMENTS + "; refusing an inexact Cartesian fallback");
		return Collections.unmodifiableSet(values);
	}

	private static <K> Map<K,List<Integer>> immutableIdentityLists(Map<K,List<Integer>> source) {
		Map<K,List<Integer>> result = new IdentityHashMap<>();
		for(Map.Entry<K,List<Integer>> entry : source.entrySet())
			result.put(entry.getKey(), List.copyOf(entry.getValue()));
		return Collections.unmodifiableMap(result);
	}
}
