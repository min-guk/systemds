/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.Comparator;
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

	private MinStExactTwoDecisionOracle() {
		// utility class
	}

	static Selection enumerateUniqueFixture() {
		List<Selection> candidates = new ArrayList<>();
		for(int mask = 0; mask < 4; mask++) {
			List<Long> source = new ArrayList<>();
			if((mask & 1) != 0)
				source.add(A_ID);
			if((mask & 2) != 0)
				source.add(B_ID);
			long totalBits = cutBits(UNIQUE_EDGES, source);
			candidates.add(new Selection(totalBits, List.copyOf(source), mask));
		}
		return candidates.stream().min(Comparator
			.comparingDouble((Selection value) -> Double.longBitsToDouble(value.objectiveBits()))
			.thenComparingInt(Selection::mask)).orElseThrow();
	}

	static long cutBits(List<Edge> edges, List<Long> sourceNodeIds) {
		double total = 0.0;
		for(Edge edge : edges) {
			boolean fromSource = edge.from() == SOURCE_ID || sourceNodeIds.contains(edge.from());
			boolean toSource = edge.to() != SINK_ID && sourceNodeIds.contains(edge.to());
			if(fromSource && !toSource)
				total += Double.longBitsToDouble(edge.capacityBits());
		}
		return bits(total);
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

	record Edge(long from, long to, long capacityBits) { }

	record Selection(long objectiveBits, List<Long> sourceNodeIds, int mask) {
		Selection {
			sourceNodeIds = List.copyOf(sourceNodeIds);
		}
	}
}
