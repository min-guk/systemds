/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

public class LocalPhysicalOptimizerIncrementalTraceTest {
	private static final List<String> FIELDS = List.of("phase", "merges", "clusters", "lower", "upper",
		"relativeGap", "elapsedNanos", "dpNanos", "scoringNanos", "validationNanos", "assignments",
		"retainedSlots", "improvements", "resourceRejected", "internalDecisions", "plannerElapsedNanos",
		"separateGlobalCalls", "scope");

	@Test
	public void checkpointTracePreservesSchemaAndRoundTripNumbers() {
		for(double[] values : List.of(
			new double[] {123.45678901234567, 987.6543210987654, 0.05000000000000001},
			new double[] {Double.MIN_VALUE, -0.0, Double.POSITIVE_INFINITY},
			new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN})) {
			var checkpoint = checkpoint(values[0], values[1], values[2]);
			String actual = LocalPhysicalOptimizer.incrementalCheckpointTrace(checkpoint, 91L);
			String oracle = oracle(checkpoint, 91L);
			Map<String,String> actualFields = fields(actual);
			Map<String,String> oracleFields = fields(oracle);

			assertEquals(FIELDS, new ArrayList<>(actualFields.keySet()));
			assertEquals(FIELDS, new ArrayList<>(oracleFields.keySet()));
			for(String name : FIELDS) {
				if(name.equals("lower") || name.equals("upper") || name.equals("relativeGap"))
					assertEquals(name, Double.doubleToRawLongBits(Double.parseDouble(oracleFields.get(name))),
						Double.doubleToRawLongBits(Double.parseDouble(actualFields.get(name))));
				else
					assertEquals(name, oracleFields.get(name), actualFields.get(name));
			}
		}
	}

	@Test
	public void checkpointTraceIsIndependentOfDefaultLocale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.FRANCE);
			String trace = LocalPhysicalOptimizer.incrementalCheckpointTrace(
				checkpoint(1.25e-120, 2.5e120, 0.03125), 101L);
			Map<String,String> parsed = fields(trace);
			assertEquals(Double.doubleToRawLongBits(1.25e-120),
				Double.doubleToRawLongBits(Double.parseDouble(parsed.get("lower"))));
			assertEquals(Double.doubleToRawLongBits(2.5e120),
				Double.doubleToRawLongBits(Double.parseDouble(parsed.get("upper"))));
			assertEquals("101", parsed.get("plannerElapsedNanos"));
		}
		finally {
			Locale.setDefault(previous);
		}
	}

	private static IncrementalRegionalOptimizer.Checkpoint checkpoint(double lower, double upper, double gap) {
		return new IncrementalRegionalOptimizer.Checkpoint("MERGE", 2, 3, lower, upper, gap,
			11L, 12L, 13L, 14L, 15L, 16L, 4, 5, 6);
	}

	private static String oracle(IncrementalRegionalOptimizer.Checkpoint cp, long plannerElapsedNanos) {
		return String.format(Locale.ROOT,
			"phase=%s merges=%d clusters=%d lower=%.17g upper=%.17g relativeGap=%.17g "
				+ "elapsedNanos=%d dpNanos=%d scoringNanos=%d validationNanos=%d assignments=%d "
				+ "retainedSlots=%d improvements=%d resourceRejected=%d internalDecisions=%d "
				+ "plannerElapsedNanos=%d separateGlobalCalls=0 scope=encoded-model",
			cp.phase(), cp.merges(), cp.activeClusters(), cp.lower(), cp.upper(), cp.relativeGap(),
			cp.elapsedNanos(), cp.dpNanos(), cp.scoringNanos(), cp.validationNanos(), cp.assignments(),
			cp.retainedSlots(), cp.improvements(), cp.resourceRejected(), cp.internalDecisions(),
			plannerElapsedNanos);
	}

	private static Map<String,String> fields(String trace) {
		Map<String,String> parsed = new LinkedHashMap<>();
		for(String token : trace.split(" ")) {
			int separator = token.indexOf('=');
			if(separator <= 0 || separator == token.length() - 1)
				throw new AssertionError("invalid trace token: " + token);
			parsed.put(token.substring(0, separator), token.substring(separator + 1));
		}
		return parsed;
	}
}
