/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/**
 * Same-binary OLD/NEW microbenchmark for the production FunctionalMap path.
 * This is synthetic evidence about representation work, not an end-to-end planner claim.
 */
public final class FunctionalMapSparseBenchmark {
	private record Sample(long nanos, long allocatedBytes, long retainedRows,
		long supportRows, long materializedRows, long maximumRows,
		long objectiveBits, int sourceChoice, int targetChoice) { }

	private FunctionalMapSparseBenchmark() { }

	public static void main(String[] arguments) {
		if(arguments.length != 4)
			throw new IllegalArgumentException(
				"usage: <engine-label> <domain> <warmups> <repeats>");
		String engine = arguments[0];
		int domain = Integer.parseInt(arguments[1]);
		int warmups = Integer.parseInt(arguments[2]);
		int repeats = Integer.parseInt(arguments[3]);
		if(domain < 2 || warmups < 0 || repeats < 1)
			throw new IllegalArgumentException("invalid benchmark arguments");

		Variable source = new Variable("benchmark-source", domain);
		Variable target = new Variable("benchmark-target", domain);
		int[] mapping = new int[domain];
		Arrays.fill(mapping, -1);
		int first = 7 % domain;
		int middle = domain / 2;
		int last = domain - 1;
		mapping[first] = 11 % domain;
		mapping[middle] = (middle + 1) % domain;
		mapping[last] = last;
		Factor relation = Factor.functionalMap(source, target, mapping);
		List<Variable> variables = List.of(source, target);
		List<Factor> factors = List.of(relation);
		long logicalRows = Math.multiplyExact((long)domain, domain);
		Limits limits = new Limits(logicalRows + domain + 1L,
			2L * logicalRows + domain + 1L);

		for(int warmup = 0; warmup < warmups; warmup++)
			runOnce(variables, factors, limits);
		Sample[] samples = new Sample[repeats];
		for(int repeat = 0; repeat < repeats; repeat++)
			samples[repeat] = runOnce(variables, factors, limits);
		assertEquivalent(samples);

		long[] wall = Arrays.stream(samples).mapToLong(Sample::nanos).sorted().toArray();
		long[] allocated = Arrays.stream(samples).mapToLong(Sample::allocatedBytes).sorted().toArray();
		Sample representative = samples[0];
		System.out.printf(
			"RESULT engine=%s domain=%d logicalRows=%d legalRows=3 reportedRetainedRows=%d "
				+ "supportRows=%d reportedMaterializedRows=%d reportedMaximumRows=%d medianWallMs=%.3f "
				+ "medianAllocatedBytes=%d objectiveBits=%d assignment=%d,%d warmups=%d repeats=%d%n",
			engine, domain, logicalRows, representative.retainedRows(),
			representative.supportRows(), representative.materializedRows(),
			representative.maximumRows(), wall[wall.length / 2] / 1_000_000d,
			allocated[allocated.length / 2], representative.objectiveBits(),
			representative.sourceChoice(), representative.targetChoice(), warmups, repeats);
	}

	private static Sample runOnce(List<Variable> variables, List<Factor> factors, Limits limits) {
		com.sun.management.ThreadMXBean allocations = allocationBean();
		long thread = Thread.currentThread().getId();
		long allocatedBefore = allocations == null ? -1L : allocations.getThreadAllocatedBytes(thread);
		long started = System.nanoTime();
		BoundaryMessage leaf = ExactCategoricalSolver.boundaryLeaves(variables, factors, limits).get(0);
		Result result = ExactCategoricalSolver.solve(variables, factors, limits);
		long nanos = System.nanoTime() - started;
		long allocatedAfter = allocations == null ? -1L : allocations.getThreadAllocatedBytes(thread);
		long supportRows = leaf.hardSupport() == null ? -1L : leaf.hardSupport().size();
		return new Sample(nanos,
			allocatedBefore < 0L || allocatedAfter < 0L ? -1L : allocatedAfter - allocatedBefore,
			leaf.retainedCells(), supportRows, result.statistics().materializedFactorCells(),
			result.statistics().maximumFactorCells(), Double.doubleToRawLongBits(result.objective()),
			result.assignmentInVariableOrder().get(0), result.assignmentInVariableOrder().get(1));
	}

	private static com.sun.management.ThreadMXBean allocationBean() {
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		if(!(bean instanceof com.sun.management.ThreadMXBean extended)
			|| !extended.isThreadAllocatedMemorySupported())
			return null;
		if(!extended.isThreadAllocatedMemoryEnabled())
			extended.setThreadAllocatedMemoryEnabled(true);
		return extended;
	}

	private static void assertEquivalent(Sample[] samples) {
		Sample expected = samples[0];
		for(Sample actual : samples)
			if(actual.objectiveBits() != expected.objectiveBits()
				|| actual.sourceChoice() != expected.sourceChoice()
				|| actual.targetChoice() != expected.targetChoice()
				|| actual.supportRows() != expected.supportRows()
				|| actual.retainedRows() != expected.retainedRows()
				|| actual.materializedRows() != expected.materializedRows())
				throw new IllegalStateException("benchmark result changed between repetitions");
	}
}
