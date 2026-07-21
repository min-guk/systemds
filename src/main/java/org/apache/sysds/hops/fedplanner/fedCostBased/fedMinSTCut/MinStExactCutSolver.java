/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Package-private exhaustive directed s-t cut solver for immutable MinST facts. */
final class MinStExactCutSolver {
	private MinStExactCutSolver() { }

	static Result solve(long sourceNodeId, long sinkNodeId, List<Decision> decisions,
		List<Long> freeNonDecisionNodeIds, List<Edge> edges) {
		validateEdges(edges);
		List<Long> free = canonicalNodeIds(freeNonDecisionNodeIds);
		List<Minimum> minima = new ArrayList<>();
		long[] minimumBits = new long[] { Double.doubleToRawLongBits(Double.POSITIVE_INFINITY) };
		enumerateDecisions(sourceNodeId, sinkNodeId, decisions, 0, new ArrayList<>(), free,
			edges, minimumBits, minima);
		if(minima.isEmpty())
			throw new IllegalArgumentException("MINST_EXACT_NO_LEGAL_PARTITION");
		return new Result(minimumBits[0], minima.stream()
			.sorted(MinStExactCutSolver::compareMinima).toList());
	}

	private static void enumerateDecisions(long sourceNodeId, long sinkNodeId,
		List<Decision> decisions, int index, List<Long> selected, List<Long> free,
		List<Edge> edges, long[] minimumBits, List<Minimum> minima) {
		if(index < decisions.size()) {
			Decision decision = decisions.get(index);
			for(Choice choice : decision.legalChoicesInCanonicalOrder()) {
				int size = selected.size();
				selected.addAll(choice.sourceNodeIds());
				enumerateDecisions(sourceNodeId, sinkNodeId, decisions, index + 1,
					selected, free, edges, minimumBits, minima);
				selected.subList(size, selected.size()).clear();
			}
			return;
		}
		enumerateFree(sourceNodeId, sinkNodeId, free, 0, selected, edges, minimumBits, minima);
	}

	private static void enumerateFree(long sourceNodeId, long sinkNodeId,
		List<Long> free, int index, List<Long> selected, List<Edge> edges,
		long[] minimumBits, List<Minimum> minima) {
		if(index < free.size()) {
			enumerateFree(sourceNodeId, sinkNodeId, free, index + 1, selected,
				edges, minimumBits, minima);
			selected.add(free.get(index));
			enumerateFree(sourceNodeId, sinkNodeId, free, index + 1, selected,
				edges, minimumBits, minima);
			selected.remove(selected.size() - 1);
			return;
		}
		List<Long> source = canonicalNodeIds(selected);
		long objectiveBits = cutBits(sourceNodeId, sinkNodeId, edges, source);
		double objective = Double.longBitsToDouble(objectiveBits);
		double minimum = Double.longBitsToDouble(minimumBits[0]);
		int comparison = Double.compare(objective, minimum);
		if(comparison < 0) {
			minimumBits[0] = objectiveBits;
			minima.clear();
		}
		if(comparison <= 0 && objectiveBits == minimumBits[0])
			minima.add(new Minimum(objectiveBits, source));
	}

	private static long cutBits(long sourceNodeId, long sinkNodeId, List<Edge> edges,
		List<Long> sourceNodeIds) {
		Set<Long> source = new LinkedHashSet<>(sourceNodeIds);
		double total = 0.0;
		for(Edge edge : edges) {
			boolean fromSource = edge.fromNodeId() == sourceNodeId || source.contains(edge.fromNodeId());
			boolean toSource = edge.toNodeId() != sinkNodeId && source.contains(edge.toNodeId());
			if(fromSource && !toSource) {
				total += capacity(edge.capacityBits());
				validateCost(total, "MINST_EXACT_CUT_TOTAL_NOT_CANONICAL");
			}
		}
		return Double.doubleToRawLongBits(total);
	}

	private static void validateEdges(List<Edge> edges) {
		for(Edge edge : edges)
			capacity(edge.capacityBits());
	}

	private static double capacity(long capacityBits) {
		double capacity = Double.longBitsToDouble(capacityBits);
		validateCostBits(capacityBits, capacity, "MINST_EXACT_EDGE_CAPACITY_NOT_CANONICAL");
		return capacity;
	}

	private static void validateCost(double cost, String reason) {
		validateCostBits(Double.doubleToRawLongBits(cost), cost, reason);
	}

	private static void validateCostBits(long bits, double cost, String reason) {
		if(!Double.isFinite(cost) || cost < 0.0 || bits == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException(reason + "|value=" + cost);
	}

	private static List<Long> canonicalNodeIds(List<Long> nodes) {
		List<Long> sorted = nodes.stream().sorted().toList();
		for(int i = 1; i < sorted.size(); i++)
			if(sorted.get(i - 1).equals(sorted.get(i)))
				throw new IllegalArgumentException("MINST_EXACT_DUPLICATE_SOURCE_NODE|node=" + sorted.get(i));
		return List.copyOf(sorted);
	}

	private static int compareMinima(Minimum left, Minimum right) {
		return compareNodeIds(left.sourceNodeIds(), right.sourceNodeIds());
	}

	private static int compareNodeIds(List<Long> left, List<Long> right) {
		int limit = Math.min(left.size(), right.size());
		for(int index = 0; index < limit; index++) {
			int comparison = Long.compare(left.get(index), right.get(index));
			if(comparison != 0)
				return comparison;
		}
		return Integer.compare(left.size(), right.size());
	}

	record Choice(List<Long> sourceNodeIds) {
		Choice { sourceNodeIds = canonicalNodeIds(sourceNodeIds); }
	}

	record Decision(List<Choice> legalChoicesInCanonicalOrder) {
		Decision {
			if(legalChoicesInCanonicalOrder.isEmpty())
				throw new IllegalArgumentException("MINST_EXACT_DECISION_WITHOUT_LEGAL_CHOICE");
			legalChoicesInCanonicalOrder = List.copyOf(legalChoicesInCanonicalOrder);
		}
	}

	record Edge(long fromNodeId, long toNodeId, long capacityBits) { }
	record Minimum(long objectiveBits, List<Long> sourceNodeIds) {
		Minimum { sourceNodeIds = List.copyOf(sourceNodeIds); }
	}
	record Result(long objectiveBits, List<Minimum> minima) {
		Result { minima = List.copyOf(minima); }
		boolean unique() { return minima.size() == 1; }
	}
}
