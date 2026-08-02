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

import org.jgrapht.Graph;
import org.jgrapht.alg.flow.DinicMFImpl;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

/** Polynomial directed s-t min-cut solver over immutable exact MinST edge facts. */
final class MinStPolynomialCutSolver {
	private MinStPolynomialCutSolver() { }

	static MinStExactCutSolver.Result solve(long sourceNodeId, long sinkNodeId,
		List<MinStExactCutSolver.Edge> edges) {
		Graph<Long, DefaultWeightedEdge> forward = graph(sourceNodeId, sinkNodeId, edges, false);
		DinicMFImpl<Long, DefaultWeightedEdge> forwardSolver = new DinicMFImpl<>(forward);
		forwardSolver.calculateMinCut(sourceNodeId, sinkNodeId);
		List<Long> minimumSource = canonicalNonTerminalSource(forwardSolver.getSourcePartition(),
			sourceNodeId, sinkNodeId);

		// A minimum s-t cut S in G is the complement of a minimum t-s cut in the
		// reversed graph. The source-reachable cut is inclusion-minimal, so this
		// complement gives the inclusion-maximal source side among all minima.
		Graph<Long, DefaultWeightedEdge> reverse = graph(sourceNodeId, sinkNodeId, edges, true);
		DinicMFImpl<Long, DefaultWeightedEdge> reverseSolver = new DinicMFImpl<>(reverse);
		reverseSolver.calculateMinCut(sinkNodeId, sourceNodeId);
		Set<Long> maximumSourceSet = new LinkedHashSet<>(forward.vertexSet());
		maximumSourceSet.removeAll(reverseSolver.getSourcePartition());
		List<Long> maximumSource = canonicalNonTerminalSource(maximumSourceSet,
			sourceNodeId, sinkNodeId);

		long minimumBits = MinStExactCutSolver.cutBits(sourceNodeId, sinkNodeId, edges,
			minimumSource);
		long maximumBits = MinStExactCutSolver.cutBits(sourceNodeId, sinkNodeId, edges,
			maximumSource);
		if(minimumBits != maximumBits)
			throw new IllegalArgumentException("MINST_POLYNOMIAL_EXTREMA_OBJECTIVE_MISMATCH|min="
				+ Double.longBitsToDouble(minimumBits) + "|max="
				+ Double.longBitsToDouble(maximumBits));

		List<MinStExactCutSolver.Minimum> extrema = new ArrayList<>(2);
		extrema.add(new MinStExactCutSolver.Minimum(minimumBits, minimumSource));
		if(!maximumSource.equals(minimumSource))
			extrema.add(new MinStExactCutSolver.Minimum(maximumBits, maximumSource));
		return new MinStExactCutSolver.Result(minimumBits, extrema.stream()
			.sorted(MinStPolynomialCutSolver::compareMinima).toList());
	}

	private static Graph<Long, DefaultWeightedEdge> graph(long sourceNodeId, long sinkNodeId,
		List<MinStExactCutSolver.Edge> edges, boolean reverse) {
		Graph<Long, DefaultWeightedEdge> graph = new DefaultDirectedWeightedGraph<>(
			DefaultWeightedEdge.class);
		graph.addVertex(sourceNodeId);
		graph.addVertex(sinkNodeId);
		for(MinStExactCutSolver.Edge edge : edges) {
			double capacity = MinStExactCutSolver.capacity(edge.capacityBits());
			long from = reverse ? edge.toNodeId() : edge.fromNodeId();
			long to = reverse ? edge.fromNodeId() : edge.toNodeId();
			graph.addVertex(from);
			graph.addVertex(to);
			DefaultWeightedEdge added = graph.addEdge(from, to);
			if(added == null)
				throw new IllegalArgumentException("MINST_POLYNOMIAL_DUPLICATE_EDGE|from="
					+ from + "|to=" + to);
			graph.setEdgeWeight(added, capacity);
		}
		return graph;
	}

	private static List<Long> canonicalNonTerminalSource(Set<Long> source,
		long sourceNodeId, long sinkNodeId) {
		return source.stream()
			.filter(node -> node != sourceNodeId && node != sinkNodeId)
			.sorted().toList();
	}

	private static int compareMinima(MinStExactCutSolver.Minimum left,
		MinStExactCutSolver.Minimum right) {
		List<Long> leftNodes = left.sourceNodeIds();
		List<Long> rightNodes = right.sourceNodeIds();
		int limit = Math.min(leftNodes.size(), rightNodes.size());
		for(int index = 0; index < limit; index++) {
			int comparison = Long.compare(leftNodes.get(index), rightNodes.get(index));
			if(comparison != 0)
				return comparison;
		}
		return Integer.compare(leftNodes.size(), rightNodes.size());
	}
}
