/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
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
import org.apache.sysds.runtime.util.UtilFunctions;

/**
 * Immutable occurrence-execution facts shared by all placement selectors and cost models.
 *
 * <p>The fact construction follows compiled statement-block identity rather than HOP identity:
 * loops scale their nested regions, branches use the configured expected branch weight, and each
 * DML function invocation receives a distinct context.  Consequently, two projections backed by
 * the same HOP can still have different execution weights, while one function body called from
 * multiple sites retains all call-context profiles.</p>
 */
public final class OccurrenceExecutionFrequencyFacts {
	/** One exact call-context occurrence profile for one control-region path. */
	public record OccurrenceProfileFact(double expectedExecutions,
		List<Pair<Long,Double>> loopContext, long contextOrdinal) {
		public OccurrenceProfileFact {
			expectedExecutions = requirePositiveWeight(expectedExecutions,
				"PLACEMENT_OCCURRENCE_WEIGHT_UNPROVEN");
			loopContext = List.copyOf(Objects.requireNonNull(loopContext, "loopContext"));
			if(contextOrdinal < 0L)
				throw new IllegalArgumentException("PLACEMENT_OCCURRENCE_CONTEXT_INVALID");
		}
	}

	private static final long MAIN_CONTEXT = 0L;
	private final Map<String,List<OccurrenceProfileFact>> profilesByPath;
	private final boolean exactFunctionContextsProven;

	private OccurrenceExecutionFrequencyFacts(
		Map<String,List<OccurrenceProfileFact>> profilesByPath,
		boolean exactFunctionContextsProven) {
		Map<String,List<OccurrenceProfileFact>> frozen = new LinkedHashMap<>();
		profilesByPath.forEach((path, profiles) -> frozen.put(path, List.copyOf(profiles)));
		this.profilesByPath = Collections.unmodifiableMap(frozen);
		this.exactFunctionContextsProven = exactFunctionContextsProven;
	}

	static OccurrenceExecutionFrequencyFacts from(PlacementAnalysis analysis) {
		return new Builder(Objects.requireNonNull(analysis, "analysis")).build();
	}

	/** Immutable path-indexed profiles, exposed for cost-model compatibility checks. */
	public Map<String,List<OccurrenceProfileFact>> profilesByPath() {
		return profilesByPath;
	}

	/** Whether every DML function occurrence was indexed from guarded compiler-owned roots. */
	public boolean exactFunctionContextsProven() {
		return exactFunctionContextsProven;
	}

	/** Sum expected executions across every call context of one exact compiled occurrence. */
	public double executionWeight(CompiledHopKey key) {
		double total = 0.0;
		for(OccurrenceProfileFact profile : requireProfiles(key))
			total += profile.expectedExecutions();
		return requirePositiveWeight(total, "PLACEMENT_EXECUTION_WEIGHT_UNPROVEN");
	}

	/**
	 * Strict execution weight for exact physical costing.  Exact costing requires one compiler-owned
	 * occurrence path rather than the conservative multi-path aggregation used by local ordering.
	 */
	public double exactExecutionWeight(CompiledHopKey key) {
		double total = 0.0;
		for(OccurrenceProfileFact profile : requireExactProfiles(key))
			total += profile.expectedExecutions();
		return requirePositiveWeight(total, "EXACT_EXECUTION_WEIGHT_UNPROVEN");
	}

	/**
	 * Expected executions of a producer-to-consumer movement, respecting reusable values that are
	 * produced in a deeper loop than their consumer.
	 */
	public double forwardingWeight(CompiledHopKey consumer, CompiledHopKey producer) {
		List<OccurrenceProfileFact> consumers = requireProfiles(consumer);
		List<OccurrenceProfileFact> producers = requireProfiles(producer);
		double total = 0.0;
		for(OccurrenceProfileFact consumerProfile : consumers) {
			OccurrenceProfileFact producerProfile = producers.stream()
				.filter(candidate -> candidate.contextOrdinal() == consumerProfile.contextOrdinal())
				.findFirst().orElse(null);
			// Synthetic/cross-boundary projections can lack a shared call-context ordinal.  The
			// consumer frequency is the conservative local-ordering estimate; Exact separately
			// requires complete guarded function contexts before consuming this fact.
			if(producerProfile == null) {
				total += consumerProfile.expectedExecutions();
				continue;
			}
			total += requirePositiveWeight(PlacementCostSemantics.forwardingWeight(
				consumerProfile.expectedExecutions(), consumerProfile.loopContext(),
				producerProfile.loopContext()), "PLACEMENT_FORWARDING_WEIGHT_UNPROVEN");
		}
		return requirePositiveWeight(total, "PLACEMENT_FORWARDING_WEIGHT_UNPROVEN");
	}

	/** Strict producer-to-consumer weight for exact physical costing. */
	public double exactForwardingWeight(CompiledHopKey consumer, CompiledHopKey producer) {
		List<OccurrenceProfileFact> consumers = requireExactProfiles(consumer);
		List<OccurrenceProfileFact> producers = requireExactProfiles(producer);
		double total = 0.0;
		for(OccurrenceProfileFact consumerProfile : consumers) {
			OccurrenceProfileFact producerProfile = producers.stream()
				.filter(candidate -> candidate.contextOrdinal() == consumerProfile.contextOrdinal())
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
					"EXACT_OCCURRENCE_CONTEXT_UNMATCHED|consumer=" + consumer.normalizedSignature()
						+ "|producer=" + producer.normalizedSignature()));
			total += requirePositiveWeight(PlacementCostSemantics.forwardingWeight(
				consumerProfile.expectedExecutions(), consumerProfile.loopContext(),
				producerProfile.loopContext()), "EXACT_FORWARDING_WEIGHT_UNPROVEN");
		}
		return requirePositiveWeight(total, "EXACT_FORWARDING_WEIGHT_UNPROVEN");
	}

	/** Expected executions of one logical DML function-call boundary. */
	public double logicalFunctionCallWeight(LogicalFunctionInputFact fact) {
		List<String> paths = fact.boundary().controlRegion().regionPath();
		String expectedBoundary = "input-" + fact.callInputPosition();
		if(paths.size() != 2 || !expectedBoundary.equals(paths.get(1)))
			throw new IllegalArgumentException("PLACEMENT_LOGICAL_FUNCTION_BOUNDARY_PATH_UNPROVEN|boundary="
				+ fact.boundary().normalizedSignature() + "|paths=" + paths);
		List<OccurrenceProfileFact> calls = profilesByPath.get(paths.get(0));
		if(calls == null || calls.isEmpty())
			throw new IllegalArgumentException("PLACEMENT_LOGICAL_FUNCTION_CALL_PATH_UNPROVEN|path="
				+ paths.get(0));
		double total = 0.0;
		for(OccurrenceProfileFact profile : calls)
			total += profile.expectedExecutions();
		return requirePositiveWeight(total, "PLACEMENT_LOGICAL_FUNCTION_CALL_WEIGHT_UNPROVEN");
	}

	private List<OccurrenceProfileFact> requireProfiles(CompiledHopKey key) {
		Objects.requireNonNull(key, "key");
		List<String> paths = key.controlRegion().regionPath();
		if(paths.isEmpty())
			throw new IllegalArgumentException("PLACEMENT_OCCURRENCE_PATH_UNPROVEN|key="
				+ key.normalizedSignature());
		List<OccurrenceProfileFact> result = new ArrayList<>();
		for(String path : paths) {
			List<OccurrenceProfileFact> profiles = profilesByPath.get(path);
			if(profiles == null || profiles.isEmpty())
				throw new IllegalArgumentException("PLACEMENT_OCCURRENCE_PATH_UNPROVEN|path=" + path);
			result.addAll(profiles);
		}
		return List.copyOf(result);
	}

	private List<OccurrenceProfileFact> requireExactProfiles(CompiledHopKey key) {
		Objects.requireNonNull(key, "key");
		List<String> paths = key.controlRegion().regionPath();
		if(paths.size() != 1)
			throw new IllegalArgumentException("EXACT_OCCURRENCE_PATH_UNPROVEN|key="
				+ key.normalizedSignature() + "|paths=" + paths);
		List<OccurrenceProfileFact> result = profilesByPath.get(paths.get(0));
		if(result == null || result.isEmpty())
			throw new IllegalArgumentException("EXACT_OCCURRENCE_PATH_UNPROVEN|path=" + paths.get(0));
		return result;
	}

	private static final class Builder {
		private final PlacementAnalysis analysis;
		private final Map<String,List<OccurrenceProfileFact>> profiles = new LinkedHashMap<>();
		private final Map<String,List<FunctionCallContext>> functionCalls = new LinkedHashMap<>();
		private final Map<Hop,List<FunctionCallContext>> indexedFunctionCalls = new IdentityHashMap<>();
		private long nextContextOrdinal = MAIN_CONTEXT + 1L;

		private Builder(PlacementAnalysis analysis) {
			this.analysis = analysis;
		}

		private OccurrenceExecutionFrequencyFacts build() {
			analysis.assertProgramStructureUnchanged();
			boolean hasDmlFunction = analysis.compiledHopOccurrences().stream()
				.map(HopOccurrenceProjection::hop)
				.anyMatch(hop -> hop instanceof FunctionOp function
					&& function.getFunctionType() == FunctionOp.FunctionType.DML);
			boolean exactFunctions = !hasDmlFunction || analysis.hasGuardedFunctionRoots();
			indexBlocks(analysis.topLevelStatementBlocks(), "main", 1.0, List.of(), List.of(),
				List.of(), MAIN_CONTEXT);
			if(exactFunctions)
				indexCalledFunctions();
			indexDetachedStraightLineProfiles();
			indexMissingProfilesConservatively();
			analysis.assertProgramStructureUnchanged();
			return new OccurrenceExecutionFrequencyFacts(profiles, exactFunctions);
		}

		private void indexCalledFunctions() {
			Map<String,Integer> processedCalls = new LinkedHashMap<>();
			boolean advanced;
			do {
				advanced = false;
				for(String functionKey : new ArrayList<>(functionCalls.keySet())) {
					List<FunctionCallContext> calls = functionCalls.get(functionKey);
					int processed = processedCalls.getOrDefault(functionKey, 0);
					FunctionStatementBlock function = analysis.namedFunctionStatementBlocks().get(functionKey);
					if(function == null)
						throw new IllegalArgumentException(
							"PLACEMENT_FUNCTION_ROOT_UNPROVEN|function=" + functionKey);
					while(processed < calls.size()) {
						FunctionCallContext call = calls.get(processed++);
						indexBlock(function, "function/" + functionKey, call.networkWeight,
							call.loopContext, call.transTables, Map.of(), call.callStack,
							call.contextOrdinal);
						advanced = true;
					}
					processedCalls.put(functionKey, processed);
				}
			}
			while(advanced);
		}

		private void indexDetachedStraightLineProfiles() {
			if(!analysis.topLevelStatementBlocks().isEmpty()
				|| !analysis.namedFunctionStatementBlocks().isEmpty())
				return;
			for(HopOccurrenceProjection occurrence : analysis.compiledHopOccurrences()) {
				List<String> paths = occurrence.key().controlRegion().regionPath();
				if(paths.size() == 1)
					putProfile(paths.get(0), new OccurrenceProfileFact(1.0, List.of(), MAIN_CONTEXT));
			}
		}

		private void indexMissingProfilesConservatively() {
			for(HopOccurrenceProjection occurrence : analysis.compiledHopOccurrences())
				for(String path : occurrence.key().controlRegion().regionPath())
					if(!profiles.containsKey(path))
						putProfile(path, new OccurrenceProfileFact(1.0, List.of(), MAIN_CONTEXT));
		}

		private Map<String,List<Hop>> indexBlocks(List<StatementBlock> blocks, String path,
			double networkWeight, List<Pair<Long,Double>> loopContext,
			List<Map<String,List<Hop>>> outerTransTables, List<String> callStack,
			long contextOrdinal) {
			Map<String,List<Hop>> former = new LinkedHashMap<>();
			for(int index = 0; blocks != null && index < blocks.size(); index++) {
				Map<String,List<Hop>> writes = indexBlock(blocks.get(index), path + '/' + index,
					networkWeight, loopContext, outerTransTables, former, callStack, contextOrdinal);
				replaceMappings(former, writes);
			}
			return former;
		}

		private Map<String,List<Hop>> indexBlock(StatementBlock block, String path,
			double networkWeight, List<Pair<Long,Double>> loopContext,
			List<Map<String,List<Hop>>> outerTransTables, Map<String,List<Hop>> formerTransTable,
			List<String> callStack, long contextOrdinal) {
			List<Map<String,List<Hop>>> visible = appendTransTable(outerTransTables, formerTransTable);
			Map<String,List<Hop>> headerWrites;
			if(block instanceof ForStatementBlock forBlock) {
				double loopWeight = forLoopWeight(forBlock, visible);
				OccurrenceProfileFact nested = nestedLoopProfile(block, networkWeight, loopContext,
					loopWeight, contextOrdinal);
				headerWrites = scanBlockRoots(blockRoots(block), nested.expectedExecutions(),
					nested.loopContext(), visible, callStack, contextOrdinal);
				putProfile(path, nested);
				ForStatement statement = (ForStatement)block.getStatement(0);
				Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/loop-body",
					nested.expectedExecutions(), nested.loopContext(),
					appendTransTable(visible, headerWrites), callStack, contextOrdinal);
				replaceMappings(headerWrites, bodyWrites);
			}
			else if(block instanceof WhileStatementBlock whileBlock) {
				double loopWeight = requirePositiveWeight(
					RewireConstants.estimateWhileLoopWeight(whileBlock, visible),
					"PLACEMENT_WHILE_OCCURRENCE_WEIGHT_UNPROVEN");
				OccurrenceProfileFact nested = nestedLoopProfile(block, networkWeight, loopContext,
					loopWeight, contextOrdinal);
				headerWrites = scanBlockRoots(blockRoots(block), nested.expectedExecutions(),
					nested.loopContext(), visible, callStack, contextOrdinal);
				putProfile(path, nested);
				WhileStatement statement = (WhileStatement)block.getStatement(0);
				Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/loop-body",
					nested.expectedExecutions(), nested.loopContext(),
					appendTransTable(visible, headerWrites), callStack, contextOrdinal);
				replaceMappings(headerWrites, bodyWrites);
			}
			else if(block instanceof IfStatementBlock) {
				headerWrites = scanBlockRoots(blockRoots(block), networkWeight, loopContext,
					visible, callStack, contextOrdinal);
				putProfile(path, new OccurrenceProfileFact(networkWeight, loopContext, contextOrdinal));
				double branchWeight = requirePositiveWeight(networkWeight
					* RewireConstants.DEFAULT_IF_ELSE_WEIGHT, "PLACEMENT_BRANCH_WEIGHT_UNPROVEN");
				IfStatement statement = (IfStatement)block.getStatement(0);
				List<Map<String,List<Hop>>> branchOuter = appendTransTable(visible, headerWrites);
				Map<String,List<Hop>> ifWrites = indexBlocks(statement.getIfBody(), path + "/branch-if",
					branchWeight, loopContext, branchOuter, callStack, contextOrdinal);
				Map<String,List<Hop>> elseWrites = indexBlocks(statement.getElseBody(), path + "/branch-else",
					branchWeight, loopContext, branchOuter, callStack, contextOrdinal);
				mergeMappings(headerWrites, ifWrites);
				mergeMappings(headerWrites, elseWrites);
			}
			else if(block instanceof FunctionStatementBlock) {
				headerWrites = scanBlockRoots(blockRoots(block), networkWeight, loopContext,
					visible, callStack, contextOrdinal);
				putProfile(path, new OccurrenceProfileFact(networkWeight, loopContext, contextOrdinal));
				FunctionStatement statement = (FunctionStatement)block.getStatement(0);
				Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/body",
					networkWeight, loopContext, appendTransTable(visible, headerWrites), callStack,
					contextOrdinal);
				replaceMappings(headerWrites, bodyWrites);
			}
			else {
				headerWrites = scanBlockRoots(blockRoots(block), networkWeight, loopContext,
					visible, callStack, contextOrdinal);
				putProfile(path, new OccurrenceProfileFact(networkWeight, loopContext, contextOrdinal));
			}
			return headerWrites;
		}

		private OccurrenceProfileFact nestedLoopProfile(StatementBlock block, double networkWeight,
			List<Pair<Long,Double>> loopContext, double loopWeight, long contextOrdinal) {
			List<Pair<Long,Double>> nested = new ArrayList<>(loopContext);
			nested.add(Pair.of(block.getSBID(), loopWeight));
			return new OccurrenceProfileFact(networkWeight * loopWeight, nested, contextOrdinal);
		}

		private double forLoopWeight(ForStatementBlock block,
			List<Map<String,List<Hop>>> transTables) {
			Double from = scalarConstant(block.getFromHops(), transTables);
			Double to = scalarConstant(block.getToHops(), transTables);
			Double increment = block.getIncrementHops() == null ? 1.0
				: scalarConstant(block.getIncrementHops(), transTables);
			if(from == null || to == null || increment == null || increment == 0.0)
				return RewireConstants.DEFAULT_LOOP_WEIGHT;
			double step = from > to && increment == 1.0 ? -1.0 : increment;
			double iterations = UtilFunctions.getSeqLength(from, to, step, false);
			return iterations > 0.0 ? iterations : RewireConstants.DEFAULT_LOOP_WEIGHT;
		}

		private Double scalarConstant(Hop boundRoot, List<Map<String,List<Hop>>> transTables) {
			if(boundRoot == null || boundRoot.getInput() == null || boundRoot.getInput().isEmpty())
				return null;
			return RewireConstants.tryEvaluateScalarConstant(boundRoot.getInput().get(0), transTables);
		}

		private void putProfile(String path, OccurrenceProfileFact profile) {
			List<OccurrenceProfileFact> occurrences = profiles.computeIfAbsent(path,
				ignored -> new ArrayList<>());
			for(OccurrenceProfileFact existing : occurrences)
				if(existing.contextOrdinal() == profile.contextOrdinal()) {
					if(Double.doubleToRawLongBits(existing.expectedExecutions())
						!= Double.doubleToRawLongBits(profile.expectedExecutions())
						|| !existing.loopContext().equals(profile.loopContext()))
						throw new IllegalArgumentException(
							"PLACEMENT_OCCURRENCE_CONTEXT_CONFLICT|path=" + path);
					return;
				}
			occurrences.add(profile);
		}

		private List<Hop> blockRoots(StatementBlock block) {
			List<Hop> roots = new ArrayList<>();
			if(block.getHops() != null)
				roots.addAll(block.getHops());
			if(block instanceof IfStatementBlock)
				roots.add(((IfStatementBlock)block).getPredicateHops());
			else if(block instanceof WhileStatementBlock)
				roots.add(((WhileStatementBlock)block).getPredicateHops());
			else if(block instanceof ForStatementBlock) {
				roots.add(((ForStatementBlock)block).getFromHops());
				roots.add(((ForStatementBlock)block).getToHops());
				roots.add(((ForStatementBlock)block).getIncrementHops());
			}
			roots.removeIf(Objects::isNull);
			return roots;
		}

		private Map<String,List<Hop>> scanBlockRoots(List<Hop> roots, double networkWeight,
			List<Pair<Long,Double>> loopContext, List<Map<String,List<Hop>>> visibleTransTables,
			List<String> callStack, long contextOrdinal) {
			Map<String,List<Hop>> writes = new LinkedHashMap<>();
			for(Hop root : roots) {
				List<Map<String,List<Hop>>> current = appendTransTable(visibleTransTables, writes);
				collectFunctionCalls(List.of(root), networkWeight, loopContext, current,
					callStack, contextOrdinal);
				mergeMappings(writes, transientWrites(List.of(root)));
			}
			return writes;
		}

		private Map<String,List<Hop>> transientWrites(List<Hop> roots) {
			Map<String,List<Hop>> writes = new LinkedHashMap<>();
			Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Hop root : roots)
				collectTransientWrites(root, visited, writes);
			return writes;
		}

		private void collectTransientWrites(Hop hop, Set<Hop> visited,
			Map<String,List<Hop>> writes) {
			if(hop == null || !visited.add(hop))
				return;
			for(Hop input : hop.getInput())
				collectTransientWrites(input, visited, writes);
			if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE) {
				String name = hop.getName();
				if(name != null && !name.isBlank())
					writes.computeIfAbsent(name, ignored -> new ArrayList<>()).add(hop);
			}
		}

		private void collectFunctionCalls(List<Hop> roots, double networkWeight,
			List<Pair<Long,Double>> loopContext, List<Map<String,List<Hop>>> visibleTransTables,
			List<String> callStack, long contextOrdinal) {
			Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Hop root : roots)
				collectFunctionCalls(root, networkWeight, loopContext, visibleTransTables,
					callStack, contextOrdinal, visited);
		}

		private void collectFunctionCalls(Hop hop, double networkWeight,
			List<Pair<Long,Double>> loopContext, List<Map<String,List<Hop>>> visibleTransTables,
			List<String> callStack, long contextOrdinal, Set<Hop> visited) {
			if(hop == null || !visited.add(hop))
				return;
			for(Hop input : hop.getInput())
				collectFunctionCalls(input, networkWeight, loopContext, visibleTransTables,
					callStack, contextOrdinal, visited);
			if(!(hop instanceof FunctionOp function)
				|| function.getFunctionType() != FunctionOp.FunctionType.DML)
				return;
			String functionIdentity = function.getFunctionKey();
			if(functionIdentity == null || functionIdentity.isBlank())
				throw new IllegalArgumentException("PLACEMENT_FUNCTION_IDENTITY_UNPROVEN");
			if(callStack.contains(functionIdentity))
				throw new IllegalArgumentException(
					"PLACEMENT_RECURSIVE_FUNCTION_CONTEXT_UNSUPPORTED|function=" + functionIdentity);
			String functionRootKey = DMLProgram.DEFAULT_NAMESPACE.equals(function.getFunctionNamespace())
				? function.getFunctionName() : functionIdentity;
			Map<String,List<Hop>> inputs = new LinkedHashMap<>();
			String[] names = function.getInputVariableNames();
			int limit = Math.min(names == null ? 0 : names.length, function.getInput().size());
			for(int index = 0; index < limit; index++) {
				String name = Objects.requireNonNull(names[index], "function input name");
				if(name.isBlank())
					throw new IllegalArgumentException("PLACEMENT_FUNCTION_INPUT_NAME_UNPROVEN");
				Hop input = resolveTransientSource(function.getInput(index), visibleTransTables);
				inputs.computeIfAbsent(name, ignored -> new ArrayList<>()).add(input);
			}
			List<Map<String,List<Hop>>> functionTransTables = appendTransTable(visibleTransTables, inputs);
			FunctionCallContext candidate = new FunctionCallContext(networkWeight, loopContext,
				functionTransTables, appendCallStack(callStack, functionIdentity), -1L);
			List<FunctionCallContext> indexed = indexedFunctionCalls.computeIfAbsent(hop,
				ignored -> new ArrayList<>());
			if(indexed.stream().anyMatch(existing -> existing.sameAs(candidate)))
				return;
			FunctionCallContext context = candidate.withContextOrdinal(nextContextOrdinal++);
			indexed.add(context);
			functionCalls.computeIfAbsent(functionRootKey, ignored -> new ArrayList<>()).add(context);
		}

		private List<String> appendCallStack(List<String> callStack, String functionIdentity) {
			List<String> nested = new ArrayList<>(callStack);
			nested.add(functionIdentity);
			return List.copyOf(nested);
		}

		private Hop resolveTransientSource(Hop hop,
			List<Map<String,List<Hop>>> visibleTransTables) {
			if(!(hop instanceof DataOp data) || data.getOp() != OpOpData.TRANSIENTREAD)
				return hop;
			String name = hop.getName();
			for(int index = visibleTransTables.size() - 1; index >= 0; index--) {
				List<Hop> candidates = visibleTransTables.get(index).get(name);
				if(candidates != null && !candidates.isEmpty()) {
					Hop candidate = candidates.get(candidates.size() - 1);
					if(candidate != hop)
						return candidate;
				}
			}
			return hop;
		}

		private List<Map<String,List<Hop>>> appendTransTable(
			List<Map<String,List<Hop>>> tables, Map<String,List<Hop>> table) {
			List<Map<String,List<Hop>>> result = new ArrayList<>();
			if(tables != null)
				for(Map<String,List<Hop>> candidate : tables)
					if(candidate != null && !candidate.isEmpty())
						result.add(candidate);
			if(table != null && !table.isEmpty())
				result.add(table);
			return List.copyOf(result);
		}

		private void replaceMappings(Map<String,List<Hop>> target, Map<String,List<Hop>> source) {
			for(Map.Entry<String,List<Hop>> entry : source.entrySet())
				target.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}

		private void mergeMappings(Map<String,List<Hop>> target, Map<String,List<Hop>> source) {
			for(Map.Entry<String,List<Hop>> entry : source.entrySet())
				target.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
					.addAll(entry.getValue());
		}
	}

	private static final class FunctionCallContext {
		private final double networkWeight;
		private final List<Pair<Long,Double>> loopContext;
		private final List<Map<String,List<Hop>>> transTables;
		private final List<String> callStack;
		private final long contextOrdinal;

		private FunctionCallContext(double networkWeight, List<Pair<Long,Double>> loopContext,
			List<Map<String,List<Hop>>> transTables, List<String> callStack, long contextOrdinal) {
			this.networkWeight = requirePositiveWeight(networkWeight,
				"PLACEMENT_FUNCTION_CALL_WEIGHT_UNPROVEN");
			this.loopContext = List.copyOf(loopContext);
			List<Map<String,List<Hop>>> copied = new ArrayList<>();
			for(Map<String,List<Hop>> table : transTables) {
				Map<String,List<Hop>> copiedTable = new LinkedHashMap<>();
				for(Map.Entry<String,List<Hop>> entry : table.entrySet())
					copiedTable.put(entry.getKey(), List.copyOf(entry.getValue()));
				copied.add(Collections.unmodifiableMap(copiedTable));
			}
			this.transTables = List.copyOf(copied);
			this.callStack = List.copyOf(callStack);
			this.contextOrdinal = contextOrdinal;
		}

		private FunctionCallContext withContextOrdinal(long ordinal) {
			return new FunctionCallContext(networkWeight, loopContext, transTables, callStack, ordinal);
		}

		private boolean sameAs(FunctionCallContext that) {
			if(Double.doubleToRawLongBits(networkWeight)
				!= Double.doubleToRawLongBits(that.networkWeight)
				|| !loopContext.equals(that.loopContext) || !callStack.equals(that.callStack)
				|| transTables.size() != that.transTables.size())
				return false;
			for(int tableIndex = 0; tableIndex < transTables.size(); tableIndex++) {
				Map<String,List<Hop>> left = transTables.get(tableIndex);
				Map<String,List<Hop>> right = that.transTables.get(tableIndex);
				if(!left.keySet().equals(right.keySet()))
					return false;
				for(String name : left.keySet()) {
					List<Hop> leftHops = left.get(name);
					List<Hop> rightHops = right.get(name);
					if(rightHops == null || leftHops.size() != rightHops.size())
						return false;
					for(int hopIndex = 0; hopIndex < leftHops.size(); hopIndex++)
						if(leftHops.get(hopIndex) != rightHops.get(hopIndex))
							return false;
				}
			}
			return true;
		}
	}

	private static double requirePositiveWeight(double value, String reason) {
		if(!Double.isFinite(value) || value <= 0.0)
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}
}
