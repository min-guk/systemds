/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class PreparedConservativeUnionTest {
	private static final ExactPhysicalCostModel.BranchLiteral A = literal("a", true);
	private static final ExactPhysicalCostModel.BranchLiteral B = literal("b", true);
	private static final ExactPhysicalCostModel.BranchLiteral C = literal("c", true);
	private static final ExactPhysicalCostModel.BranchLiteral D = literal("d", true);

	@Test public void exhaustiveDuplicateSubsetTieAndRawAdditionParity() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.1, A), event(0.1, A), event(0.2, A, B), event(0.2, A, B),
			event(Math.nextDown(0.5), C), event(0.5, C, D), event(0d));
		assertEveryMask(events, 1.3d);
	}

	@Test public void deterministicRandomMasksMatchLegacyRawBits() {
		Random random = new Random(20261007L);
		var literals = List.of(A, B, C, D);
		double[] weights = {0d, 0.1d, 0.2d, 0.3d, Math.nextDown(0.5d), 0.5d, 0.75d};
		for(int trial = 0; trial < 100; trial++) {
			int size = 1 + random.nextInt(8);
			List<ExactMaterializationActivation.Event> events = new ArrayList<>();
			for(int index = 0; index < size; index++) {
				if(index > 0 && random.nextInt(5) == 0) {
					events.add(events.get(random.nextInt(events.size())));
					continue;
				}
				List<ExactPhysicalCostModel.BranchLiteral> conditions = new ArrayList<>();
				for(var literal : literals) if(random.nextBoolean()) conditions.add(literal);
				events.add(new ExactMaterializationActivation.Event(
					weights[random.nextInt(weights.length)], conditions));
			}
			assertEveryMask(events, 2d);
		}
	}

	@Test public void emptyZeroAndOverflowFallbackPreserveRawBits() {
		assertEveryMask(List.of(), Double.MAX_VALUE);
		assertEveryMask(List.of(event(0d), event(0d, A)), Double.MAX_VALUE);
		assertEveryMask(List.of(event(Double.MAX_VALUE, A), event(Double.MAX_VALUE, B),
			event(Double.MAX_VALUE, C)), Double.MAX_VALUE);
	}

	@Test public void validationMatchesLegacyBoundary() {
		Assert.assertThrows(NullPointerException.class,
			() -> ExactMaterializationActivation.prepareConservativeUnion(null, 1d));
		for(double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, -1d, -0d))
			Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactMaterializationActivation.prepareConservativeUnion(List.of(), invalid));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactMaterializationActivation.prepareConservativeUnion(
				List.of(event(1.1d, A)), 1d));
		var prepared = ExactMaterializationActivation.prepareConservativeUnion(
			List.of(event(0.5d, A)), 1d);
		Assert.assertThrows(NullPointerException.class, () -> prepared.evaluate(null));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> prepared.evaluate(new boolean[0]));
	}

	@Test public void concurrentAndReentrantCallsHaveNoSharedMaskState() throws Exception {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.6, A), event(0.2, A, B), event(0.7, C), event(0.7, C));
		var prepared = ExactMaterializationActivation.prepareConservativeUnion(events, 1d);
		var pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Void>> calls = new ArrayList<>();
			for(int round = 0; round < 100; round++) for(int mask = 0; mask < 16; mask++) {
				final int selected = mask;
				calls.add(() -> {
					boolean[] active = mask(events.size(), selected);
					long expected = Double.doubleToRawLongBits(
						ExactMaterializationActivation.conservativeUnion(events, 1d, active));
					Assert.assertEquals(expected, Double.doubleToRawLongBits(prepared.evaluate(active)));
					return null;
				});
			}
			for(var result : pool.invokeAll(calls)) result.get();
		}
		finally { pool.shutdownNow(); }
	}

	@Test public void preparedEvaluationRemovesPerMaskStructureAllocation() {
		var platformBean = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(platformBean instanceof com.sun.management.ThreadMXBean);
		var bean = (com.sun.management.ThreadMXBean)platformBean;
		Assume.assumeTrue(bean.isThreadAllocatedMemorySupported());
		boolean old = bean.isThreadAllocatedMemoryEnabled();
		try {
			if(!old) bean.setThreadAllocatedMemoryEnabled(true);
			List<ExactMaterializationActivation.Event> events = List.of(
				event(0.6, A), event(0.6, A), event(0.2, A, B), event(0.7, C), event(0.4, C, D));
			var prepared = ExactMaterializationActivation.prepareConservativeUnion(events, 1d);
			boolean[] active = {true, true, true, true, true};
			for(int index = 0; index < 10_000; index++) {
				ExactMaterializationActivation.conservativeUnion(events, 1d, active);
				prepared.evaluateOwned(active);
			}
			long thread = Thread.currentThread().getId();
			long startLegacy = bean.getThreadAllocatedBytes(thread);
			for(int index = 0; index < 20_000; index++)
				ExactMaterializationActivation.conservativeUnion(events, 1d, active);
			long legacy = bean.getThreadAllocatedBytes(thread) - startLegacy;
			long startPrepared = bean.getThreadAllocatedBytes(thread);
			for(int index = 0; index < 20_000; index++) prepared.evaluateOwned(active);
			long optimized = bean.getThreadAllocatedBytes(thread) - startPrepared;
			System.out.println("PREPARED_UNION_ALLOCATION|legacy=" + legacy + "|prepared=" + optimized);
			Assert.assertTrue("prepared evaluator must remove structural per-mask allocation: legacy="
				+ legacy + " prepared=" + optimized, optimized * 10L < legacy);
		}
		finally { if(!old) bean.setThreadAllocatedMemoryEnabled(false); }
	}

	private static void assertEveryMask(List<ExactMaterializationActivation.Event> events,
		double scopeWeight) {
		var prepared = ExactMaterializationActivation.prepareConservativeUnion(events, scopeWeight);
		for(int selected = 0; selected < 1 << events.size(); selected++) {
			boolean[] active = mask(events.size(), selected);
			long expected = Double.doubleToRawLongBits(
				ExactMaterializationActivation.conservativeUnion(events, scopeWeight, active));
			Assert.assertEquals("mask=" + selected, expected,
				Double.doubleToRawLongBits(prepared.evaluate(active)));
			Assert.assertEquals("owned mask=" + selected, expected,
				Double.doubleToRawLongBits(prepared.evaluateOwned(active)));
		}
	}

	private static boolean[] mask(int size, int selected) {
		boolean[] result = new boolean[size];
		for(int index = 0; index < size; index++) result[index] = (selected & 1 << index) != 0;
		return result;
	}
	private static ExactMaterializationActivation.Event event(double weight,
		ExactPhysicalCostModel.BranchLiteral... conditions) {
		return new ExactMaterializationActivation.Event(weight, List.of(conditions));
	}
	private static ExactPhysicalCostModel.BranchLiteral literal(String path, boolean arm) {
		return new ExactPhysicalCostModel.BranchLiteral(path, arm);
	}
}
