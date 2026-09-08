/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Algorithm;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.State;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.StopReason;

/**
 * Certified best-bound search shared by TARGET_GAP and REUSE.
 *
 * <p>Probe partitions are side data until every value of one selected variable
 * is represented. The frontier is changed only by an atomic parent-to-children
 * replacement, so every published frontier continues to cover the original
 * problem. Nodes whose lower bound already suffices for the requested target
 * remain in the frontier and therefore remain part of the global certificate.</p>
 */
final class BranchingRegionalOptimizer {
	private static final class Node {
		final long id;
		final int[] fixed;
		final int depth;
		double lower;
		int width;
		List<MiniBucketLowerBound.Conflict> conflicts;
		List<Integer> feasible;
		final Set<Integer> region;
		final boolean probeBoundAvailable;

		Node(long id, int[] fixed, int depth, double lower, int width,
			List<MiniBucketLowerBound.Conflict> conflicts, List<Integer> feasible, Set<Integer> region,
			boolean probeBoundAvailable) {
			this.id = id;
			this.fixed = fixed.clone();
			this.depth = depth;
			this.lower = lower;
			this.width = width;
			this.conflicts = List.copyOf(conflicts);
			this.feasible = feasible == null ? null : List.copyOf(feasible);
			this.region = new LinkedHashSet<>(region);
			this.probeBoundAvailable = probeBoundAvailable;
		}
	}

	private record Partition(int variable, List<Node> children, double lower,
		long successfulBounds, long fallbackBounds) {
		Partition { children = List.copyOf(children); }
	}

	private BranchingRegionalOptimizer() { }

	static Result run(State state) {
		boolean targetGap = state.options.algorithm() == Algorithm.TARGET_GAP;
		if(!targetGap && state.options.algorithm() != Algorithm.REUSE)
			throw new IllegalArgumentException("BRANCHING_REGIONAL_ALGORITHM_INVALID");

		Map<Long,Node> frontier = new LinkedHashMap<>();
		long nextId = 1L;
		Node root = new Node(0L, state.problem.unconstrained(), 0, state.lower,
			state.options.common().initialWidth(), state.initialBound.conflicts(), state.assignment, Set.of(), false);
		frontier.put(root.id, root);
		state.stats.set("generatedNodes", 1);
		state.stats.set("probeBoundSuccess", 0);
		state.stats.set("probeBoundFallback", 0);
		publishFrontier(state, frontier, "FRONTIER_INITIALIZED", "root=0 condition=root");

		while(true) {
			prune(state, frontier);
			if(frontier.isEmpty()) {
				state.raiseLower(state.upper);
				return state.finish(StopReason.GLOBAL_EXACT);
			}
			publishFrontier(state, frontier, "FRONTIER", nodeDetails(selectActive(state, frontier)));
			if(state.reached())
				return state.finish(StopReason.TARGET_REACHED);
			if(state.expired())
				return state.finish(StopReason.TIME_BUDGET);
			if(state.stats.get("steps") >= state.options.maxSteps())
				return state.finish(StopReason.STEP_LIMIT);

			Node node = selectActive(state, frontier);
			if(node == null)
				throw new IllegalStateException("REGIONAL_SEARCH_DEFERRED_FRONTIER_BELOW_TARGET");
			state.stats.add("steps", 1);

			List<Integer> ranked = state.problem.rankedDecisions(node.fixed, node.conflicts);
			boolean terminal = ranked.isEmpty();
			if(terminal || state.canSolveWhole(node.fixed)) {
				try {
					RegionalSearchProblem.Solution exact = state.whole(node.fixed);
					if(exact.feasible()) {
						checkNodeFeasible(node, exact.objective());
						node.feasible = exact.assignment();
						state.accept(exact);
					}
					frontier.remove(node.id);
					state.stats.add("closedNodes", 1);
					prune(state, frontier);
					publishFrontier(state, frontier, terminal ? "TERMINAL_EXACT" : "NODE_EXACT",
						"node=" + node.id + " condition=" + condition(node.fixed));
					continue;
				}
				catch(IllegalArgumentException failure) {
					if(terminal || !RegionalSearchProblem.isResourceLimit(failure))
						throw failure;
					state.stats.add("resourceFailures", 1);
					state.publish("NODE_EXACT_LIMIT", "node=" + node.id
						+ " condition=" + condition(node.fixed) + " fallback=branch");
				}
			}

			if(targetGap && state.options.common().refineBound()
				&& node.width < state.options.common().maximumWidth()) {
				int strengthenedWidth = node.width + 1;
				MiniBucketLowerBound.Result strengthened;
				try {
					strengthened = state.bound(node.fixed, strengthenedWidth);
				}
				catch(RuntimeException failure) {
					if(!isBoundResourceLimit(failure))
						throw failure;
					state.stats.add("resourceFailures", 1);
					state.publish("NODE_STRENGTHEN_LIMIT", "node=" + node.id
						+ " condition=" + condition(node.fixed) + " fallback=probe");
					strengthened = null;
				}
				if(strengthened == null) {
					ranked = state.problem.rankedDecisions(node.fixed, node.conflicts);
				}
				else {
					double before = node.lower;
					node.lower = Math.max(node.lower, strengthened.lowerBound());
					node.width = strengthenedWidth;
					node.conflicts = strengthened.conflicts();
					checkNodeSeed(state, node);
					state.stats.add("widthStrengthenings", 1);
					state.stats.add("boundActions", 1);
					if(node.lower == before)
						state.stats.add("zeroGainActions", 1);
					prune(state, frontier);
					publishFrontier(state, frontier, "NODE_STRENGTHENED", "node=" + node.id
						+ " condition=" + condition(node.fixed) + " width=" + node.width
						+ " nodeLower=" + node.lower + " nodeGain=" + (node.lower - before));
					if(frontier.isEmpty())
						return state.finish(StopReason.GLOBAL_EXACT);
					if(state.reached())
						return state.finish(StopReason.TARGET_REACHED);
					if(!frontier.containsKey(node.id))
						continue;
					if(state.nodeSufficient(node.lower))
						continue;
					ranked = state.problem.rankedDecisions(node.fixed, node.conflicts);
				}
			}

			int candidateCount = targetGap ? Math.min(2, state.options.probeCandidates()) : 1;
			candidateCount = Math.min(candidateCount, ranked.size());
			if(candidateCount == 0)
				throw new IllegalStateException("REGIONAL_SEARCH_NONTERMINAL_WITHOUT_BRANCH");
			List<Partition> partitions = new ArrayList<>(candidateCount);
			for(int candidate = 0; candidate < candidateCount; candidate++) {
				Partition partition = probe(state, node, ranked.get(candidate), nextId);
				nextId += partition.children().size();
				partitions.add(partition);
			}
			state.stats.add("probeVariables", candidateCount);

			double parentBefore = node.lower;
			node.lower = combinePartitionLower(node.lower,
				partitions.stream().map(Partition::lower).toList());
			checkNodeSeed(state, node);
			for(Partition partition : partitions)
				for(Node child : partition.children())
					child.lower = Math.max(child.lower, node.lower);
			state.stats.add("parentStrengthenings", node.lower > parentBefore ? 1 : 0);
			state.stats.add("boundActions", 1);
			if(node.lower == parentBefore)
				state.stats.add("zeroGainActions", 1);
			prune(state, frontier);
			publishFrontier(state, frontier, "PROBE_COMPLETE", "node=" + node.id
				+ " condition=" + condition(node.fixed) + " candidates=" + candidateDetails(partitions)
				+ " nodeLower=" + node.lower + " nodeGain=" + (node.lower - parentBefore));
			if(frontier.isEmpty())
				return state.finish(StopReason.GLOBAL_EXACT);
			if(state.reached())
				return state.finish(StopReason.TARGET_REACHED);
			if(!frontier.containsKey(node.id))
				continue;
			if(state.nodeSufficient(node.lower))
				continue;

			Partition selected = partitions.stream().max(Comparator
				.comparingDouble(Partition::lower).thenComparingInt(partition -> -partition.variable())).orElseThrow();
			Node promising = selected.children().stream().filter(child -> !state.nodeSufficient(child.lower))
				.min(Comparator.comparingDouble((Node child) -> child.lower).thenComparingLong(child -> child.id))
				.orElseGet(() -> selected.children().stream().min(Comparator.comparingLong(child -> child.id)).orElseThrow());
			String regionDetails = conditionalRegional(state, node, promising);
			prune(state, frontier);
			publishFrontier(state, frontier, "CONDITIONAL_REGION", regionDetails);
			if(frontier.isEmpty())
				return state.finish(StopReason.GLOBAL_EXACT);
			if(state.reached())
				return state.finish(StopReason.TARGET_REACHED);
			if(!frontier.containsKey(node.id))
				continue;
			if(state.nodeSufficient(node.lower))
				continue;

			if(frontier.size() - 1L + selected.children().size() > state.options.maximumFrontier())
				return state.finish(StopReason.FRONTIER_LIMIT);
			if(frontier.remove(node.id) == null)
				throw new IllegalStateException("REGIONAL_SEARCH_FRONTIER_PARENT_MISSING");
			for(Node child : selected.children()) {
				if(frontier.put(child.id, child) != null)
					throw new IllegalStateException("REGIONAL_SEARCH_FRONTIER_NODE_DUPLICATE");
			}
			state.stats.add("branchedNodes", 1);
			state.stats.add("generatedNodes", selected.children().size());
			state.stats.add("cacheHits", reusableProbeBounds(selected.children().stream()
				.map(child -> child.probeBoundAvailable).toList()));
			state.stats.set("selectedSplitVariable", selected.variable() + 1L);
			prune(state, frontier);
			publishFrontier(state, frontier, "BRANCH_COMMIT", "parent=" + node.id
				+ " condition=" + condition(node.fixed) + " variable=" + selected.variable()
				+ " candidateLower=" + selected.lower() + " children=" + selected.children().size());
		}
	}

	private static Partition probe(State state, Node parent, int variable, long firstId) {
		List<Node> children = new ArrayList<>();
		double partitionLower = Double.POSITIVE_INFINITY;
		long successfulBounds = 0L;
		long fallbackBounds = 0L;
		for(int value = 0; value < state.problem.domainSize(variable); value++) {
			int[] fixed = parent.fixed.clone();
			fixed[variable] = value;
			MiniBucketLowerBound.Result result = null;
			try {
				result = state.bound(fixed, parent.width);
			}
			catch(RuntimeException failure) {
				if(!isBoundResourceLimit(failure))
					throw failure;
				state.stats.add("resourceFailures", 1);
			}
			double childLower = result == null ? parent.lower : Math.max(parent.lower, result.lowerBound());
			if(result == null) {
				fallbackBounds++;
				state.stats.add("probeBoundFallback", 1);
			}
			else {
				successfulBounds++;
				state.stats.add("probeBoundSuccess", 1);
			}
			List<Integer> localSeed = parent.feasible != null && state.problem.matches(fixed, parent.feasible)
				? parent.feasible : null;
			Node child = new Node(firstId + value, fixed, parent.depth + 1, childLower,
				parent.width, result == null ? parent.conflicts : result.conflicts(), localSeed, parent.region,
				result != null);
			checkNodeSeed(state, child);
			children.add(child);
			partitionLower = Math.min(partitionLower, childLower);
			state.stats.add("probes", 1);
		}
		if(children.isEmpty())
			throw new IllegalStateException("REGIONAL_SEARCH_EMPTY_BRANCH_DOMAIN");
		return new Partition(variable, children, partitionLower, successfulBounds, fallbackBounds);
	}

	private static String conditionalRegional(State state, Node parent, Node child) {
		int inheritedRegion = parent.region.size();
		state.stats.add("inheritedRegionVariables", inheritedRegion);
		Set<Integer> region = new LinkedHashSet<>(parent.region);
		for(int i = 0; i < child.fixed.length; i++)
			if(child.fixed[i] >= 0)
				region.add(i);
		if(region.size() > state.options.common().maximumRegionVariables()) {
			state.stats.add("zeroGainActions", 1);
			return "node=" + child.id + " condition=" + condition(child.fixed)
				+ " region=skipped-cap regionSize=" + region.size() + " inheritedRegion=" + inheritedRegion;
		}
		int target = Math.min(state.options.common().maximumRegionVariables(),
			Math.max(region.size(), region.size() + state.options.common().regionGrowth()));
		state.problem.growRegion(region, target, child.conflicts);
		List<Integer> reference = child.feasible != null ? child.feasible
			: parent.feasible != null ? parent.feasible : state.assignment;
		RegionalSearchProblem.Solution candidate;
		try {
			candidate = state.region(child.fixed, region, reference);
		}
		catch(IllegalArgumentException failure) {
			if(!RegionalSearchProblem.isResourceLimit(failure))
				throw failure;
			state.stats.add("resourceFailures", 1);
			return "node=" + child.id + " condition=" + condition(child.fixed)
				+ " region=" + indexes(region) + " inheritedRegion=" + inheritedRegion
				+ " resource=limited fallback=branch";
		}
		state.stats.add("regionActions", 1);
		child.region.clear();
		child.region.addAll(region);
		if(!candidate.feasible()) {
			state.stats.add("zeroGainActions", 1);
			return "node=" + child.id + " condition=" + condition(child.fixed)
				+ " region=" + indexes(region) + " inheritedRegion=" + inheritedRegion + " feasible=false";
		}
		if(!state.problem.matches(child.fixed, candidate.assignment()))
			throw new IllegalStateException("REGIONAL_SEARCH_REGION_CONDITION_VIOLATED");
		checkNodeFeasible(child, candidate.objective());
		child.feasible = candidate.assignment();
		if(!state.accept(candidate))
			state.stats.add("zeroGainActions", 1);
		return "node=" + child.id + " condition=" + condition(child.fixed)
			+ " region=" + indexes(region) + " inheritedRegion=" + inheritedRegion
			+ " feasible=true candidate=" + candidate.objective();
	}

	private static boolean isBoundResourceLimit(RuntimeException failure) {
		return failure instanceof MiniBucketLowerBound.ResourceLimitException
			|| failure instanceof IllegalArgumentException argument
				&& RegionalSearchProblem.isResourceLimit(argument);
	}

	private static void prune(State state, Map<Long,Node> frontier) {
		Iterator<Node> iterator = frontier.values().iterator();
		while(iterator.hasNext()) {
			Node node = iterator.next();
			if(node.lower >= state.upper) {
				iterator.remove();
				state.stats.add("prunedNodes", 1);
			}
		}
	}

	private static Node selectActive(State state, Map<Long,Node> frontier) {
		return frontier.values().stream().filter(node -> !state.nodeSufficient(node.lower))
			.min(Comparator.comparingDouble((Node node) -> node.lower).thenComparingLong(node -> node.id)).orElse(null);
	}

	private static void publishFrontier(State state, Map<Long,Node> frontier, String phase, String details) {
		List<Double> nodeBounds = new ArrayList<>(frontier.size());
		long deferred = 0;
		for(Node node : frontier.values()) {
			nodeBounds.add(node.lower);
			if(state.nodeSufficient(node.lower))
				deferred++;
		}
		double aggregate = aggregateFrontierLower(state.upper, nodeBounds);
		state.raiseLower(aggregate);
		state.stats.set("frontier", frontier.size());
		state.stats.max("maxFrontier", frontier.size());
		state.stats.set("deferredNodes", deferred);
		state.publish(phase, details == null ? "" : details);
	}

	static double combinePartitionLower(double parentLower, List<Double> partitionLowers) {
		double result = parentLower;
		for(double candidate : partitionLowers) {
			if(Double.isNaN(candidate) || candidate < 0)
				throw new IllegalArgumentException("REGIONAL_SEARCH_PARTITION_BOUND_INVALID");
			result = Math.max(result, candidate);
		}
		return result;
	}

	static double aggregateFrontierLower(double upper, List<Double> nodeLowers) {
		double result = upper;
		for(double candidate : nodeLowers) {
			if(!Double.isFinite(candidate) || candidate < 0 || candidate > upper)
				throw new IllegalStateException("REGIONAL_SEARCH_FRONTIER_BOUND_INVALID");
			result = Math.min(result, candidate);
		}
		return result;
	}

	static long reusableProbeBounds(List<Boolean> successfulBounds) {
		return successfulBounds.stream().filter(Boolean.TRUE::equals).count();
	}

	private static String nodeDetails(Node node) {
		return node == null ? "active=none" : "node=" + node.id + " condition=" + condition(node.fixed)
			+ " nodeLower=" + node.lower + " depth=" + node.depth;
	}

	private static String candidateDetails(List<Partition> partitions) {
		StringBuilder text = new StringBuilder();
		for(Partition partition : partitions) {
			if(text.length() > 0)
				text.append(',');
			text.append(partition.variable()).append(':').append(partition.lower())
				.append(":success=").append(partition.successfulBounds())
				.append(":fallback=").append(partition.fallbackBounds());
		}
		return text.toString();
	}

	private static String condition(int[] fixed) {
		StringBuilder text = new StringBuilder();
		for(int i = 0; i < fixed.length; i++)
			if(fixed[i] >= 0) {
				if(text.length() > 0)
					text.append(',');
				text.append(i).append(':').append(fixed[i]);
			}
		return text.length() == 0 ? "root" : text.toString();
	}

	private static String indexes(Set<Integer> values) {
		StringBuilder text = new StringBuilder();
		for(int value : values) {
			if(text.length() > 0)
				text.append(',');
			text.append(value);
		}
		return text.toString();
	}

	private static void checkNodeSeed(State state, Node node) {
		if(node.feasible == null)
			return;
		if(!state.problem.matches(node.fixed, node.feasible))
			throw new IllegalStateException("REGIONAL_SEARCH_NODE_SEED_CONDITION_INVALID");
		double cost = state.problem.evaluate(node.feasible);
		checkNodeFeasible(node, cost);
	}

	private static void checkNodeFeasible(Node node, double cost) {
		validateNodeFeasible(node.id, node.lower, cost);
	}

	static void validateNodeFeasible(long nodeId, double nodeLower, double cost) {
		if(!Double.isFinite(cost) || cost < 0)
			throw new IllegalArgumentException("REGIONAL_SEARCH_NODE_FEASIBLE_INVALID");
		if(nodeLower > cost)
			throw new IllegalStateException("REGIONAL_SEARCH_NODE_BOUND_EXCEEDS_FEASIBLE"
				+ "|node=" + nodeId + "|lower=" + nodeLower + "|feasible=" + cost);
	}
}
