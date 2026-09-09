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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
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
import org.apache.sysds.runtime.functionobjects.IntegerDivide;
import org.apache.sysds.runtime.functionobjects.Modulus;
import org.apache.sysds.runtime.util.UtilFunctions;

/**
 * Immutable occurrence-execution facts shared by all placement selectors and cost models.
 *
 * <p>The fact construction follows compiled statement-block identity rather than HOP identity:
 * loops scale their nested regions, compiler-proven predicates select their reachable arm while
 * unknown branches use the configured expectation, and each DML function invocation receives a
 * distinct context. Consequently, two projections backed by
 * the same HOP can still have different execution weights, while one function body called from
 * multiple sites retains all call-context profiles.</p>
 */
public final class OccurrenceExecutionFrequencyFacts {
	private static final long MAX_EXACT_DOUBLE_INTEGER = 1L << 53;
	/** One branch literal that must hold for an occurrence to activate. */
	public record BranchActivationFact(String decisionKey, boolean ifArm, double probability,
		List<Long> enclosingLoopIds) {
		public BranchActivationFact {
			decisionKey = Objects.requireNonNull(decisionKey, "decisionKey");
			if(decisionKey.isBlank())
				throw new IllegalArgumentException("PLACEMENT_BRANCH_DECISION_KEY_INVALID");
			if(Double.doubleToRawLongBits(probability) != Double.doubleToRawLongBits(0.0)
				&& Double.doubleToRawLongBits(probability) != Double.doubleToRawLongBits(1.0)
				&& Double.doubleToRawLongBits(probability)
					!= Double.doubleToRawLongBits(RewireConstants.DEFAULT_IF_ELSE_WEIGHT))
				throw new IllegalArgumentException(
					"PLACEMENT_BRANCH_PROBABILITY_INVALID|value=" + probability);
			enclosingLoopIds = List.copyOf(Objects.requireNonNull(enclosingLoopIds,
				"enclosingLoopIds"));
		}
	}

	/** One exact call-context occurrence profile for one control-region path. */
	public record OccurrenceProfileFact(double expectedExecutions,
		List<Pair<Long,Double>> loopContext, long contextOrdinal,
		List<BranchActivationFact> activationConditions) {
		public OccurrenceProfileFact(double expectedExecutions,
			List<Pair<Long,Double>> loopContext, long contextOrdinal) {
			this(expectedExecutions, loopContext, contextOrdinal, List.of());
		}

		public OccurrenceProfileFact {
			expectedExecutions = requireNonnegativeWeight(expectedExecutions,
				"PLACEMENT_OCCURRENCE_WEIGHT_UNPROVEN");
			loopContext = List.copyOf(Objects.requireNonNull(loopContext, "loopContext"));
			if(contextOrdinal < 0L)
				throw new IllegalArgumentException("PLACEMENT_OCCURRENCE_CONTEXT_INVALID");
			activationConditions = List.copyOf(Objects.requireNonNull(activationConditions,
				"activationConditions"));
		}
	}

	private static final long MAIN_CONTEXT = 0L;
	private final Map<String,List<OccurrenceProfileFact>> profilesByPath;
	private final Set<String> conservativeFallbackPaths;
	private final boolean exactFunctionContextsProven;

	private OccurrenceExecutionFrequencyFacts(
		Map<String,List<OccurrenceProfileFact>> profilesByPath,
		Set<String> conservativeFallbackPaths,
		boolean exactFunctionContextsProven) {
		Map<String,List<OccurrenceProfileFact>> frozen = new LinkedHashMap<>();
		profilesByPath.forEach((path, profiles) -> frozen.put(path, List.copyOf(profiles)));
		this.profilesByPath = Collections.unmodifiableMap(frozen);
		this.conservativeFallbackPaths = Set.copyOf(conservativeFallbackPaths);
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
		return requireNonnegativeWeight(total, "PLACEMENT_EXECUTION_WEIGHT_UNPROVEN");
	}

	/**
	 * Non-throwing policy-ordering estimate. Detached/synthetic occurrence paths may
	 * intentionally have no exact compiler profile; callers that merely rank two
	 * otherwise legal states must not allocate an exception on every comparison.
	 * Exact cost models continue to use the strict methods below.
	 */
	public double executionWeightOrDefault(CompiledHopKey key, double defaultWeight) {
		requirePositiveWeight(defaultWeight, "PLACEMENT_EXECUTION_DEFAULT_INVALID");
		List<OccurrenceProfileFact> profiles = profilesOrNull(key);
		if(profiles == null)
			return defaultWeight;
		double total = 0.0;
		for(OccurrenceProfileFact profile : profiles)
			total += profile.expectedExecutions();
		return Double.isFinite(total) && total >= 0.0 ? total : defaultWeight;
	}

	/**
	 * Strict execution weight for exact physical costing.  Exact costing requires one compiler-owned
	 * occurrence path rather than the conservative multi-path aggregation used by local ordering.
	 */
	public double exactExecutionWeight(CompiledHopKey key) {
		double total = 0.0;
		for(OccurrenceProfileFact profile : requireExactProfiles(key))
			total += profile.expectedExecutions();
		return requireNonnegativeWeight(total, "EXACT_EXECUTION_WEIGHT_UNPROVEN");
	}

	/** Exact compiler-proven profiles for consumers that must reject conservative fallbacks. */
	public List<OccurrenceProfileFact> exactProfiles(CompiledHopKey key) {
		return requireExactProfiles(key);
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
			if(consumerProfile.expectedExecutions() == 0.0)
				continue;
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
		return requireNonnegativeWeight(total, "PLACEMENT_FORWARDING_WEIGHT_UNPROVEN");
	}

	/** Non-throwing counterpart used only for deterministic local policy ordering. */
	public double forwardingWeightOrDefault(CompiledHopKey consumer, CompiledHopKey producer,
		double defaultWeight) {
		requirePositiveWeight(defaultWeight, "PLACEMENT_FORWARDING_DEFAULT_INVALID");
		List<OccurrenceProfileFact> consumers = profilesOrNull(consumer);
		List<OccurrenceProfileFact> producers = profilesOrNull(producer);
		if(consumers == null || producers == null)
			return defaultWeight;
		double total = 0.0;
		for(OccurrenceProfileFact consumerProfile : consumers) {
			if(consumerProfile.expectedExecutions() == 0.0)
				continue;
			OccurrenceProfileFact producerProfile = producers.stream()
				.filter(candidate -> candidate.contextOrdinal() == consumerProfile.contextOrdinal())
				.findFirst().orElse(null);
			double weight = producerProfile == null ? consumerProfile.expectedExecutions()
				: PlacementCostSemantics.forwardingWeight(consumerProfile.expectedExecutions(),
					consumerProfile.loopContext(), producerProfile.loopContext());
			if(!Double.isFinite(weight) || weight < 0.0)
				return defaultWeight;
			total += weight;
		}
		return Double.isFinite(total) && total >= 0.0 ? total : defaultWeight;
	}

	/** Strict producer-to-consumer weight for exact physical costing. */
	public double exactForwardingWeight(CompiledHopKey consumer, CompiledHopKey producer) {
		List<OccurrenceProfileFact> consumers = requireExactProfiles(consumer);
		List<OccurrenceProfileFact> producers = requireExactProfiles(producer);
		double total = 0.0;
		for(OccurrenceProfileFact consumerProfile : consumers) {
			if(consumerProfile.expectedExecutions() == 0.0)
				continue;
			OccurrenceProfileFact producerProfile = producers.stream()
				.filter(candidate -> candidate.contextOrdinal() == consumerProfile.contextOrdinal())
				.findFirst().orElseThrow(() -> new IllegalArgumentException(
					"EXACT_OCCURRENCE_CONTEXT_UNMATCHED|consumer=" + consumer.normalizedSignature()
						+ "|producer=" + producer.normalizedSignature()));
			total += requirePositiveWeight(PlacementCostSemantics.forwardingWeight(
				consumerProfile.expectedExecutions(), consumerProfile.loopContext(),
				producerProfile.loopContext()), "EXACT_FORWARDING_WEIGHT_UNPROVEN");
		}
		return requireNonnegativeWeight(total, "EXACT_FORWARDING_WEIGHT_UNPROVEN");
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
		return requireNonnegativeWeight(total, "PLACEMENT_LOGICAL_FUNCTION_CALL_WEIGHT_UNPROVEN");
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

	private List<OccurrenceProfileFact> profilesOrNull(CompiledHopKey key) {
		Objects.requireNonNull(key, "key");
		List<String> paths = key.controlRegion().regionPath();
		if(paths.isEmpty())
			return null;
		List<OccurrenceProfileFact> result = new ArrayList<>();
		for(String path : paths) {
			List<OccurrenceProfileFact> profiles = profilesByPath.get(path);
			if(profiles == null || profiles.isEmpty())
				return null;
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
		if(conservativeFallbackPaths.contains(paths.get(0)))
			throw new IllegalArgumentException(
				"EXACT_OCCURRENCE_PROFILE_FALLBACK_UNPROVEN|path=" + paths.get(0));
		return result;
	}

	private static final class Builder {
		private final PlacementAnalysis analysis;
		private final Map<String,List<OccurrenceProfileFact>> profiles = new LinkedHashMap<>();
		private final Set<String> conservativeFallbackPaths = new LinkedHashSet<>();
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
				List.of(), List.of(), MAIN_CONTEXT);
			if(exactFunctions)
				indexCalledFunctions();
			indexDetachedStraightLineProfiles();
			indexMissingProfilesConservatively();
			analysis.assertProgramStructureUnchanged();
			return new OccurrenceExecutionFrequencyFacts(
				profiles, conservativeFallbackPaths, exactFunctions);
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
							call.activationConditions, call.contextOrdinal);
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
					if(!profiles.containsKey(path)) {
						conservativeFallbackPaths.add(path);
						putProfile(path, new OccurrenceProfileFact(1.0, List.of(), MAIN_CONTEXT));
					}
		}

		private Map<String,List<Hop>> indexBlocks(List<StatementBlock> blocks, String path,
			double networkWeight, List<Pair<Long,Double>> loopContext,
			List<Map<String,List<Hop>>> outerTransTables, List<String> callStack,
			List<BranchActivationFact> activationConditions, long contextOrdinal) {
			Map<String,List<Hop>> former = new LinkedHashMap<>();
			for(int index = 0; blocks != null && index < blocks.size(); index++) {
				Map<String,List<Hop>> writes = indexBlock(blocks.get(index), path + '/' + index,
					networkWeight, loopContext, outerTransTables, former, callStack,
					activationConditions, contextOrdinal);
				replaceMappings(former, writes);
			}
			return former;
		}

		private Map<String,List<Hop>> indexBlock(StatementBlock block, String path,
			double networkWeight, List<Pair<Long,Double>> loopContext,
			List<Map<String,List<Hop>>> outerTransTables, Map<String,List<Hop>> formerTransTable,
			List<String> callStack, List<BranchActivationFact> activationConditions,
			long contextOrdinal) {
			List<Map<String,List<Hop>>> visible = appendTransTable(outerTransTables, formerTransTable);
			Map<String,List<Hop>> headerWrites;
			if(block instanceof ForStatementBlock forBlock) {
				double loopWeight = forLoopWeight(forBlock, visible);
				OccurrenceProfileFact nested = nestedLoopProfile(block, networkWeight, loopContext,
					loopWeight, activationConditions, contextOrdinal);
				headerWrites = scanBlockRoots(blockRoots(block), nested.expectedExecutions(),
					nested.loopContext(), visible, callStack, activationConditions, contextOrdinal);
				putProfile(path, nested);
				ForStatement statement = (ForStatement)block.getStatement(0);
				Map<String,List<Hop>> loopUnknowns = loopWrittenUnknowns(statement.getBody());
				loopUnknowns.put(statement.getIterablePredicate().getIterVar().getName(), List.of());
				Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/loop-body",
					nested.expectedExecutions(), nested.loopContext(),
					appendTransTable(appendTransTable(visible, headerWrites), loopUnknowns),
					callStack, activationConditions, contextOrdinal);
				replaceMappings(headerWrites, bodyWrites);
				replaceMappings(headerWrites, loopUnknowns);
			}
			else if(block instanceof WhileStatementBlock whileBlock) {
				List<Map<String,List<Hop>>> boundedVisible = appendExactPredicateScalars(
					visible, whileBlock.getPredicateHops());
				double loopWeight = requirePositiveWeight(
					RewireConstants.estimateWhileLoopWeight(whileBlock, boundedVisible),
					"PLACEMENT_WHILE_OCCURRENCE_WEIGHT_UNPROVEN");
				OccurrenceProfileFact nested = nestedLoopProfile(block, networkWeight, loopContext,
					loopWeight, activationConditions, contextOrdinal);
				headerWrites = scanBlockRoots(blockRoots(block), nested.expectedExecutions(),
					nested.loopContext(), visible, callStack, activationConditions, contextOrdinal);
				putProfile(path, nested);
				WhileStatement statement = (WhileStatement)block.getStatement(0);
				Map<String,List<Hop>> loopUnknowns = loopWrittenUnknowns(statement.getBody());
				Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/loop-body",
					nested.expectedExecutions(), nested.loopContext(),
					appendTransTable(appendTransTable(visible, headerWrites), loopUnknowns),
					callStack, activationConditions, contextOrdinal);
				replaceMappings(headerWrites, bodyWrites);
				replaceMappings(headerWrites, loopUnknowns);
			}
			else if(block instanceof IfStatementBlock ifBlock) {
				Boolean predicate = exactPredicate(ifBlock.getPredicateHops(), visible);
				headerWrites = scanBlockRoots(blockRoots(block), networkWeight, loopContext,
					visible, callStack, activationConditions, contextOrdinal);
				putProfile(path, new OccurrenceProfileFact(networkWeight, loopContext, contextOrdinal,
					activationConditions));
				double ifWeight = predicate == null ? networkWeight == 0.0 ? 0.0
					: requirePositiveWeight(networkWeight * RewireConstants.DEFAULT_IF_ELSE_WEIGHT,
						"PLACEMENT_BRANCH_WEIGHT_UNPROVEN")
					: predicate ? networkWeight : 0.0;
				double elseWeight = predicate == null ? ifWeight : predicate ? 0.0 : networkWeight;
				IfStatement statement = (IfStatement)block.getStatement(0);
				List<Map<String,List<Hop>>> branchOuter = appendTransTable(visible, headerWrites);
				double ifProbability = predicate == null ? RewireConstants.DEFAULT_IF_ELSE_WEIGHT
					: predicate ? 1.0 : 0.0;
				double elseProbability = predicate == null ? RewireConstants.DEFAULT_IF_ELSE_WEIGHT
					: predicate ? 0.0 : 1.0;
				String decisionKey = path + "|context=" + contextOrdinal;
				List<Long> enclosingLoopIds = loopContext.stream().map(Pair::getLeft).toList();
				List<BranchActivationFact> ifConditions = appendActivationCondition(activationConditions,
					new BranchActivationFact(decisionKey, true, ifProbability, enclosingLoopIds));
				List<BranchActivationFact> elseConditions = appendActivationCondition(activationConditions,
					new BranchActivationFact(decisionKey, false, elseProbability, enclosingLoopIds));
				Map<String,List<Hop>> ifWrites = indexBlocks(statement.getIfBody(), path + "/branch-if",
					ifWeight, loopContext, branchOuter, callStack, ifConditions, contextOrdinal);
				Map<String,List<Hop>> elseWrites = indexBlocks(statement.getElseBody(), path + "/branch-else",
					elseWeight, loopContext, branchOuter, callStack, elseConditions, contextOrdinal);
				if(predicate != null)
					replaceMappings(headerWrites, predicate ? ifWrites : elseWrites);
				else
					replaceMappings(headerWrites, joinBranchMappings(branchOuter, ifWrites, elseWrites));
			}
			else if(block instanceof FunctionStatementBlock) {
				headerWrites = scanBlockRoots(blockRoots(block), networkWeight, loopContext,
					visible, callStack, activationConditions, contextOrdinal);
				putProfile(path, new OccurrenceProfileFact(networkWeight, loopContext, contextOrdinal,
					activationConditions));
				FunctionStatement statement = (FunctionStatement)block.getStatement(0);
				Map<String,List<Hop>> bodyWrites = indexBlocks(statement.getBody(), path + "/body",
					networkWeight, loopContext, appendTransTable(visible, headerWrites), callStack,
					activationConditions, contextOrdinal);
				replaceMappings(headerWrites, bodyWrites);
			}
			else {
				headerWrites = scanBlockRoots(blockRoots(block), networkWeight, loopContext,
					visible, callStack, activationConditions, contextOrdinal);
				putProfile(path, new OccurrenceProfileFact(networkWeight, loopContext, contextOrdinal,
					activationConditions));
			}
			return headerWrites;
		}

		private OccurrenceProfileFact nestedLoopProfile(StatementBlock block, double networkWeight,
			List<Pair<Long,Double>> loopContext, double loopWeight,
			List<BranchActivationFact> activationConditions, long contextOrdinal) {
			List<Pair<Long,Double>> nested = new ArrayList<>(loopContext);
			nested.add(Pair.of(block.getSBID(), loopWeight));
			return new OccurrenceProfileFact(networkWeight * loopWeight, nested, contextOrdinal,
				activationConditions);
		}

		private List<BranchActivationFact> appendActivationCondition(
			List<BranchActivationFact> conditions, BranchActivationFact condition) {
			List<BranchActivationFact> nested = new ArrayList<>(conditions);
			nested.add(condition);
			return List.copyOf(nested);
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

		private Boolean exactPredicate(Hop predicateRoot,
			List<Map<String,List<Hop>>> visibleTransTables) {
			Double value = exactScalar(predicateRoot, visibleTransTables,
				Collections.newSetFromMap(new IdentityHashMap<>()), 0);
			return value == null ? null : value != 0.0;
		}

		/**
		 * Exact, occurrence-scoped evaluator for branch predicates. Unlike the
		 * best-effort loop estimator, a transient read is constant only when every
		 * reaching definition in the nearest lexical table proves the same value.
		 */
		private Double exactScalar(Hop hop, List<Map<String,List<Hop>>> transTables,
			Set<Hop> active, int depth) {
			if(hop == null || depth > 32 || !active.add(hop))
				return null;
			try {
				if(hop instanceof LiteralOp literal) {
					if(!literal.getValueType().isNumeric()
						&& literal.getValueType() != org.apache.sysds.common.Types.ValueType.BOOLEAN)
						return null;
					if(literal.getValueType() == org.apache.sysds.common.Types.ValueType.INT64
						&& (literal.getLongValue() > MAX_EXACT_DOUBLE_INTEGER
							|| literal.getLongValue() < -MAX_EXACT_DOUBLE_INTEGER))
						return null;
					return finiteOrNull(HopRewriteUtils.getDoubleValue(literal));
				}
				if(hop instanceof DataOp data) {
					if(data.getOp() == OpOpData.TRANSIENTWRITE)
						return hop.getInput() == null || hop.getInput().size() != 1 ? null
							: exactScalar(hop.getInput().get(0), transTables, active, depth + 1);
					if(data.getOp() == OpOpData.TRANSIENTREAD) {
						List<Hop> candidates = nearestCandidates(data.getName(), transTables);
						if(candidates == null || candidates.isEmpty())
							return null;
						Double result = null;
						for(Hop candidate : candidates) {
							Double value = exactScalar(candidate, transTables, active, depth + 1);
							if(value == null)
								return null;
							if(result != null && Double.doubleToRawLongBits(result)
								!= Double.doubleToRawLongBits(value))
								return null;
							result = value;
						}
						return result;
					}
				}
				if(hop instanceof org.apache.sysds.hops.UnaryOp unary
					&& hop.getDataType() != null && hop.getDataType().isScalar()
					&& hop.getInput() != null && hop.getInput().size() == 1) {
					Double input = exactScalar(hop.getInput().get(0), transTables, active, depth + 1);
					if(input == null)
						return null;
					return switch(unary.getOp()) {
						case CAST_AS_DOUBLE, CAST_AS_SCALAR -> input;
						case CAST_AS_INT -> exactLongAsDouble(UtilFunctions.toLong(input));
						case CAST_AS_BOOLEAN -> input == 0.0 ? 0.0 : 1.0;
						case ABS -> Math.abs(input);
						default -> null;
					};
				}
				if(hop instanceof org.apache.sysds.hops.BinaryOp binary
					&& hop.getDataType() != null && hop.getDataType().isScalar()
					&& hop.getInput() != null && hop.getInput().size() == 2) {
					// Runtime INT64 arithmetic dispatches through long operands. Retaining
					// only a Double here could round above 2^53 and prove the wrong arm.
					// Comparisons remain eligible because their operands are evaluated
					// independently and any unrepresentable INT64 literal is rejected.
					Double left = exactScalar(hop.getInput().get(0), transTables, active, depth + 1);
					Double right = exactScalar(hop.getInput().get(1), transTables, active, depth + 1);
					if(left == null || right == null)
						return null;
					if(binary.getValueType() == org.apache.sysds.common.Types.ValueType.INT64
						&& (binary.getOp() == org.apache.sysds.common.Types.OpOp2.PLUS
							|| binary.getOp() == org.apache.sysds.common.Types.OpOp2.MINUS
							|| binary.getOp() == org.apache.sysds.common.Types.OpOp2.MULT))
						return exactIntegerArithmetic(binary.getOp(), UtilFunctions.toLong(left),
							UtilFunctions.toLong(right));
					double value = switch(binary.getOp()) {
						case PLUS -> left + right;
						case MINUS -> left - right;
						case MULT -> left * right;
						case DIV -> right == 0.0 ? Double.NaN : left / right;
						case MODULUS -> Modulus.getFnObject().execute(left, right);
						case INTDIV -> IntegerDivide.getFnObject().execute(left, right);
						case POW -> Math.pow(left, right);
						case LESS -> left < right ? 1.0 : 0.0;
						case LESSEQUAL -> left <= right ? 1.0 : 0.0;
						case GREATER -> left > right ? 1.0 : 0.0;
						case GREATEREQUAL -> left >= right ? 1.0 : 0.0;
						case EQUAL -> left.doubleValue() == right.doubleValue() ? 1.0 : 0.0;
						case NOTEQUAL -> left.doubleValue() != right.doubleValue() ? 1.0 : 0.0;
						case AND -> left != 0.0 && right != 0.0 ? 1.0 : 0.0;
						case OR -> left != 0.0 || right != 0.0 ? 1.0 : 0.0;
						case XOR -> (left != 0.0) ^ (right != 0.0) ? 1.0 : 0.0;
						default -> Double.NaN;
					};
					if(binary.getValueType() == org.apache.sysds.common.Types.ValueType.INT64
						&& Math.abs(value) > MAX_EXACT_DOUBLE_INTEGER)
						return null;
					return finiteOrNull(value);
				}
				return null;
			}
			finally {
				active.remove(hop);
			}
		}

		private Double finiteOrNull(double value) {
			return Double.isFinite(value) ? value : null;
		}

		private Double exactLongAsDouble(long value) {
			return value >= -MAX_EXACT_DOUBLE_INTEGER && value <= MAX_EXACT_DOUBLE_INTEGER
				? (double)value : null;
		}

		private Double exactIntegerArithmetic(org.apache.sysds.common.Types.OpOp2 operation,
			long left, long right) {
			try {
				long value = switch(operation) {
					case PLUS -> Math.addExact(left, right);
					case MINUS -> Math.subtractExact(left, right);
					case MULT -> Math.multiplyExact(left, right);
					default -> throw new IllegalArgumentException("not integer arithmetic: " + operation);
				};
				return exactLongAsDouble(value);
			}
			catch(ArithmeticException overflow) {
				return null;
			}
		}

		private List<Hop> nearestCandidates(String name,
			List<Map<String,List<Hop>>> transTables) {
			if(name == null || transTables == null)
				return null;
			for(int index = transTables.size() - 1; index >= 0; index--) {
				Map<String,List<Hop>> table = transTables.get(index);
				if(table != null && table.containsKey(name))
					return table.get(name);
			}
			return null;
		}

		private Map<String,List<Hop>> joinBranchMappings(
			List<Map<String,List<Hop>>> before, Map<String,List<Hop>> ifWrites,
			Map<String,List<Hop>> elseWrites) {
			Set<String> names = new LinkedHashSet<>(ifWrites.keySet());
			names.addAll(elseWrites.keySet());
			Map<String,List<Hop>> joined = new LinkedHashMap<>();
			for(String name : names) {
				List<Hop> fromIf = ifWrites.containsKey(name) ? ifWrites.get(name)
					: nearestCandidates(name, before);
				List<Hop> fromElse = elseWrites.containsKey(name) ? elseWrites.get(name)
					: nearestCandidates(name, before);
				if(fromIf == null || fromElse == null || fromIf.isEmpty() || fromElse.isEmpty()) {
					joined.put(name, List.of());
					continue;
				}
				List<Hop> candidates = new ArrayList<>(fromIf);
				candidates.addAll(fromElse);
				joined.put(name, candidates);
			}
			return joined;
		}

		private Map<String,List<Hop>> loopWrittenUnknowns(List<StatementBlock> blocks) {
			Set<String> names = new LinkedHashSet<>();
			collectWrittenNames(blocks, names);
			Map<String,List<Hop>> unknowns = new LinkedHashMap<>();
			for(String name : names)
				unknowns.put(name, List.of());
			return unknowns;
		}

		private void collectWrittenNames(List<StatementBlock> blocks, Set<String> names) {
			if(blocks == null)
				return;
			for(StatementBlock block : blocks) {
				names.addAll(transientWrites(blockRoots(block)).keySet());
				names.addAll(functionOutputUnknowns(blockRoots(block)).keySet());
				if(block instanceof IfStatementBlock) {
					IfStatement statement = (IfStatement)block.getStatement(0);
					collectWrittenNames(statement.getIfBody(), names);
					collectWrittenNames(statement.getElseBody(), names);
				}
				else if(block instanceof ForStatementBlock) {
					ForStatement statement = (ForStatement)block.getStatement(0);
					collectWrittenNames(statement.getBody(), names);
				}
				else if(block instanceof WhileStatementBlock) {
					WhileStatement statement = (WhileStatement)block.getStatement(0);
					collectWrittenNames(statement.getBody(), names);
				}
			}
		}

		/**
		 * Adds occurrence-scoped scalar facts for predicate reads that the lexical
		 * transient table cannot resolve after a branch/function join.  The overlay is
		 * local to this frequency analysis and never mutates the compiler HOP DAG.
		 */
		private List<Map<String,List<Hop>>> appendExactPredicateScalars(
			List<Map<String,List<Hop>>> visible, Hop predicate) {
			if(predicate == null)
				return visible;
			Map<String,Double> resolved = new LinkedHashMap<>();
			Set<String> ambiguous = new java.util.HashSet<>();
			Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			List<Hop> pending = new ArrayList<>();
			pending.add(predicate);
			while(!pending.isEmpty()) {
				Hop hop = pending.remove(pending.size() - 1);
				if(hop == null || !visited.add(hop))
					continue;
				if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD
					&& data.getDataType() != null && data.getDataType().isScalar()) {
					Double value = exactNumericScalar(hop);
					if(value != null && !ambiguous.contains(data.getName())) {
						Double prior = resolved.putIfAbsent(data.getName(), value);
						if(prior != null && Double.doubleToRawLongBits(prior)
							!= Double.doubleToRawLongBits(value)) {
							resolved.remove(data.getName());
							ambiguous.add(data.getName());
						}
					}
				}
				if(hop.getInput() != null)
					pending.addAll(hop.getInput());
			}
			if(resolved.isEmpty())
				return visible;
			Map<String,List<Hop>> overlay = new LinkedHashMap<>();
			resolved.forEach((name, value) -> overlay.put(name, List.of(new LiteralOp(value))));
			return appendTransTable(visible, overlay);
		}

		private Double exactNumericScalar(Hop hop) {
			Double value = null;
			for(HopOccurrenceProjection occurrence : analysis.compiledHopOccurrences()) {
				if(occurrence.hop() != hop)
					continue;
				var fact = analysis.scalarLiteralFact(occurrence.key());
				if(fact.isEmpty())
					continue;
				double candidate;
				try {
					candidate = Double.parseDouble(fact.get().canonicalValue());
				}
				catch(NumberFormatException ignored) {
					return null;
				}
				if(!Double.isFinite(candidate))
					return null;
				if(value != null && Double.doubleToRawLongBits(value)
					!= Double.doubleToRawLongBits(candidate))
					return null;
				value = candidate;
			}
			return value;
		}

		private void putProfile(String path, OccurrenceProfileFact profile) {
			List<OccurrenceProfileFact> occurrences = profiles.computeIfAbsent(path,
				ignored -> new ArrayList<>());
			for(OccurrenceProfileFact existing : occurrences)
				if(existing.contextOrdinal() == profile.contextOrdinal()) {
					if(Double.doubleToRawLongBits(existing.expectedExecutions())
						!= Double.doubleToRawLongBits(profile.expectedExecutions())
						|| !existing.loopContext().equals(profile.loopContext())
						|| !existing.activationConditions().equals(profile.activationConditions()))
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
			List<String> callStack, List<BranchActivationFact> activationConditions,
			long contextOrdinal) {
			Map<String,List<Hop>> writes = new LinkedHashMap<>();
			for(Hop root : roots) {
				List<Map<String,List<Hop>>> current = appendTransTable(visibleTransTables, writes);
				collectFunctionCalls(List.of(root), networkWeight, loopContext, current,
					callStack, activationConditions, contextOrdinal);
				mergeMappings(writes, snapshotScalarWrites(transientWrites(List.of(root)), current));
				replaceMappings(writes, functionOutputUnknowns(List.of(root)));
			}
			return writes;
		}

		/** Freeze scalar reaching definitions in their definition-time lexical context. */
		private Map<String,List<Hop>> snapshotScalarWrites(Map<String,List<Hop>> raw,
			List<Map<String,List<Hop>>> definitionContext) {
			Map<String,List<Hop>> snapshots = new LinkedHashMap<>();
			for(Map.Entry<String,List<Hop>> entry : raw.entrySet()) {
				List<Hop> candidates = entry.getValue();
				boolean scalar = !candidates.isEmpty() && candidates.stream().allMatch(candidate ->
					candidate.getDataType() != null && candidate.getDataType().isScalar());
				if(!scalar) {
					snapshots.put(entry.getKey(), candidates);
					continue;
				}
				if(candidates.size() != 1) {
					snapshots.put(entry.getKey(), List.of());
					continue;
				}
				Hop write = candidates.get(0);
				Double value = exactScalar(write, definitionContext,
					Collections.newSetFromMap(new IdentityHashMap<>()), 0);
				Hop literal = value == null ? null : scalarLiteral(write, value);
				snapshots.put(entry.getKey(), literal == null ? List.of() : List.of(literal));
			}
			return snapshots;
		}

		private Hop scalarLiteral(Hop source, double value) {
			return switch(source.getValueType()) {
				case BOOLEAN -> new LiteralOp(value != 0.0);
				case INT32, INT64 -> Math.abs(value) <= MAX_EXACT_DOUBLE_INTEGER
					? new LiteralOp(UtilFunctions.toLong(value)) : null;
				case FP32, FP64 -> new LiteralOp(value);
				default -> null;
			};
		}

		private Map<String,List<Hop>> transientWrites(List<Hop> roots) {
			Map<String,List<Hop>> writes = new LinkedHashMap<>();
			Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Hop root : roots)
				collectTransientWrites(root, visited, writes);
			return writes;
		}

		private Map<String,List<Hop>> functionOutputUnknowns(List<Hop> roots) {
			Map<String,List<Hop>> outputs = new LinkedHashMap<>();
			Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			List<Hop> pending = new ArrayList<>(roots);
			while(!pending.isEmpty()) {
				Hop hop = pending.remove(pending.size() - 1);
				if(hop == null || !visited.add(hop))
					continue;
				if(hop instanceof FunctionOp function && function.getOutputVariableNames() != null)
					for(String output : function.getOutputVariableNames())
						if(output != null && !output.isBlank())
							outputs.put(output, List.of());
				if(hop.getInput() != null)
					pending.addAll(hop.getInput());
			}
			return outputs;
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
			List<String> callStack, List<BranchActivationFact> activationConditions,
			long contextOrdinal) {
			Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Hop root : roots)
				collectFunctionCalls(root, networkWeight, loopContext, visibleTransTables,
					callStack, activationConditions, contextOrdinal, visited);
		}

		private void collectFunctionCalls(Hop hop, double networkWeight,
			List<Pair<Long,Double>> loopContext, List<Map<String,List<Hop>>> visibleTransTables,
			List<String> callStack, List<BranchActivationFact> activationConditions,
			long contextOrdinal, Set<Hop> visited) {
			if(hop == null || !visited.add(hop))
				return;
			for(Hop input : hop.getInput())
				collectFunctionCalls(input, networkWeight, loopContext, visibleTransTables,
					callStack, activationConditions, contextOrdinal, visited);
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
				inputs.put(name, new ArrayList<>(resolveTransientSources(
					function.getInput(index), visibleTransTables)));
			}
			List<Map<String,List<Hop>>> functionTransTables = appendTransTable(visibleTransTables, inputs);
			FunctionCallContext candidate = new FunctionCallContext(networkWeight, loopContext,
				functionTransTables, appendCallStack(callStack, functionIdentity), activationConditions,
				contextOrdinal, -1L);
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

		private List<Hop> resolveTransientSources(Hop hop,
			List<Map<String,List<Hop>>> visibleTransTables) {
			if(hop != null && hop.getDataType() != null && hop.getDataType().isScalar()) {
				Double value = exactScalar(hop, visibleTransTables,
					Collections.newSetFromMap(new IdentityHashMap<>()), 0);
				Hop literal = value == null ? null : scalarLiteral(hop, value);
				return literal == null ? List.of() : List.of(literal);
			}
			if(!(hop instanceof DataOp data) || data.getOp() != OpOpData.TRANSIENTREAD)
				return List.of(hop);
			List<Hop> candidates = nearestCandidates(hop.getName(), visibleTransTables);
			if(candidates == null)
				return List.of(hop);
			return candidates.stream().filter(candidate -> candidate != hop).toList();
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
		private final List<BranchActivationFact> activationConditions;
		private final long callerContextOrdinal;
		private final long contextOrdinal;

		private FunctionCallContext(double networkWeight, List<Pair<Long,Double>> loopContext,
			List<Map<String,List<Hop>>> transTables, List<String> callStack,
			List<BranchActivationFact> activationConditions, long callerContextOrdinal,
			long contextOrdinal) {
			this.networkWeight = requireNonnegativeWeight(networkWeight,
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
			this.activationConditions = List.copyOf(activationConditions);
			this.callerContextOrdinal = callerContextOrdinal;
			this.contextOrdinal = contextOrdinal;
		}

		private FunctionCallContext withContextOrdinal(long ordinal) {
			return new FunctionCallContext(networkWeight, loopContext, transTables, callStack,
				activationConditions, callerContextOrdinal, ordinal);
		}

		private boolean sameAs(FunctionCallContext that) {
			if(Double.doubleToRawLongBits(networkWeight)
				!= Double.doubleToRawLongBits(that.networkWeight)
				|| callerContextOrdinal != that.callerContextOrdinal
				|| !loopContext.equals(that.loopContext) || !callStack.equals(that.callStack)
				|| !activationConditions.equals(that.activationConditions)
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
						if(!sameSourceHop(leftHops.get(hopIndex), rightHops.get(hopIndex)))
							return false;
				}
			}
			return true;
		}

		private boolean sameSourceHop(Hop left, Hop right) {
			return left == right || left instanceof LiteralOp leftLiteral
				&& right instanceof LiteralOp rightLiteral
				&& leftLiteral.getValueType() == rightLiteral.getValueType()
				&& leftLiteral.getName().equals(rightLiteral.getName());
		}
	}

	private static double requirePositiveWeight(double value, String reason) {
		if(!Double.isFinite(value) || value <= 0.0)
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}

	private static double requireNonnegativeWeight(double value, String reason) {
		if(!Double.isFinite(value) || value < 0.0)
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}
}
