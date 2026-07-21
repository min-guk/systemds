/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.List;

/** Literal, production-independent enumeration oracle for the B0 MinST selector contract. */
final class MinStExactTwoDecisionOracle {
	static final long SOURCE_ID = -1L;
	static final long SINK_ID = -2L;
	static final long A_ID = 10L;
	static final long B_ID = 20L;

	static final List<Edge> UNIQUE_EDGES = List.of(
		new Edge(SOURCE_ID, A_ID, bits(8.0)),
		new Edge(A_ID, SINK_ID, bits(1.0)),
		new Edge(SOURCE_ID, B_ID, bits(5.0)),
		new Edge(B_ID, SINK_ID, bits(2.0)),
		new Edge(A_ID, B_ID, bits(4.0)),
		new Edge(B_ID, A_ID, bits(3.0)));

	static final List<Edge> NONUNIQUE_EDGES = List.of(
		new Edge(SOURCE_ID, A_ID, bits(1.0)),
		new Edge(A_ID, SINK_ID, bits(1.0)),
		new Edge(SOURCE_ID, B_ID, bits(1.0)),
		new Edge(B_ID, SINK_ID, bits(1.0)));

	private MinStExactTwoDecisionOracle() {
		// utility class
	}

	static Selection enumerateUniqueFixture() {
		Enumeration enumeration = enumerate(UNIQUE_EDGES);
		if(enumeration.result() != Result.UNIQUE_MINIMUM)
			throw new IllegalStateException("MINST_LITERAL_FIXTURE_NOT_UNIQUE|count="
				+ enumeration.minima().size());
		return enumeration.minima().get(0);
	}

	static Enumeration enumerateNonuniqueFixture() {
		return enumerate(NONUNIQUE_EDGES);
	}

	private static Enumeration enumerate(List<Edge> edges) {
		List<Selection> candidates = new ArrayList<>();
		for(int mask = 0; mask < 4; mask++) {
			List<Long> source = new ArrayList<>();
			if((mask & 1) != 0)
				source.add(A_ID);
			if((mask & 2) != 0)
				source.add(B_ID);
			long totalBits = cutBits(edges, source);
			candidates.add(new Selection(totalBits, List.copyOf(source), mask));
		}
		double minimum = candidates.stream()
			.mapToDouble(value -> Double.longBitsToDouble(value.objectiveBits())).min().orElseThrow();
		List<Selection> minima = candidates.stream()
			.filter(value -> Double.compare(Double.longBitsToDouble(value.objectiveBits()), minimum) == 0)
			.toList();
		return new Enumeration(minima.size() == 1 ? Result.UNIQUE_MINIMUM : Result.TIE_UNSPECIFIED,
			minima);
	}

	static long cutBits(List<Edge> edges, List<Long> sourceNodeIds) {
		double total = 0.0;
		for(Edge edge : edges) {
			double capacity = canonicalCapacity(edge);
			boolean fromSource = edge.from() == SOURCE_ID || sourceNodeIds.contains(edge.from());
			boolean toSource = edge.to() != SINK_ID && sourceNodeIds.contains(edge.to());
			if(fromSource && !toSource) {
				total += capacity;
				if(!Double.isFinite(total) || total < 0.0
					|| Double.doubleToRawLongBits(total) == Double.doubleToRawLongBits(-0.0))
					throw new IllegalArgumentException("MinST cut total must be finite and non-negative");
			}
		}
		return bits(total);
	}

	private static double canonicalCapacity(Edge edge) {
		double capacity = Double.longBitsToDouble(edge.capacityBits());
		if(!Double.isFinite(capacity) || capacity < 0.0
			|| edge.capacityBits() == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException("MinST edge capacity must be finite and non-negative");
		return capacity;
	}

	static void validateCanonicalSourceIds(List<Long> sourceNodeIds) {
		if(sourceNodeIds == null || !sourceNodeIds.equals(sourceNodeIds.stream().sorted().distinct().toList()))
			throw new IllegalArgumentException("MinST source IDs must be sorted and unique");
	}

	static void validateObjective(List<Edge> edges, Selection selection) {
		validateCanonicalSourceIds(selection.sourceNodeIds());
		if(cutBits(edges, selection.sourceNodeIds()) != selection.objectiveBits())
			throw new IllegalArgumentException("MinST objective differs from literal capacities");
	}

	static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	enum Result { UNIQUE_MINIMUM, TIE_UNSPECIFIED }

	record Edge(long from, long to, long capacityBits) { }

	record Enumeration(Result result, List<Selection> minima) {
		Enumeration {
			minima = List.copyOf(minima);
		}
	}

	record Selection(long objectiveBits, List<Long> sourceNodeIds, int mask) {
		Selection {
			sourceNodeIds = List.copyOf(sourceNodeIds);
		}
	}
}
