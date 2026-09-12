/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

public class PlannerPipelineTimingTest {
	@Test
	public void deterministicMarksPartitionTheWholeInvocationExactly() {
		PlannerPipelineTiming.clear();
		try {
			PlannerPipelineTiming.begin(100L);
			PlannerPipelineTiming.planningComplete(130L);
			PlannerPipelineTiming.diagnosticsComplete(145L);
			PlannerPipelineTiming.conversionComplete(180L);
			PlannerPipelineTiming.applicationComplete(220L);
			PlannerPipelineTiming.Timing timing = PlannerPipelineTiming.finish(250L);

			Assert.assertEquals(30L, timing.planningNanos());
			Assert.assertEquals(15L, timing.diagnosticsNanos());
			Assert.assertEquals(35L, timing.conversionNanos());
			Assert.assertEquals(40L, timing.applicationNanos());
			Assert.assertEquals(30L, timing.finalizationNanos());
			Assert.assertEquals(150L, timing.totalNanos());
			Assert.assertEquals("schema=decision-application-v1 planningNanos=30 diagnosticsNanos=15 "
				+ "conversionNanos=35 applicationNanos=40 finalizationNanos=30 totalNanos=150",
				timing.traceFields());
			Assert.assertThrows(IllegalStateException.class, () -> PlannerPipelineTiming.finish(251L));
		}
		finally {
			PlannerPipelineTiming.clear();
		}
	}

	@Test
	public void standaloneMarksAndFinishAreNoOpsWithoutBegin() {
		PlannerPipelineTiming.clear();
		PlannerPipelineTiming.planningComplete(1L);
		PlannerPipelineTiming.diagnosticsComplete(2L);
		PlannerPipelineTiming.conversionComplete(3L);
		PlannerPipelineTiming.applicationComplete(4L);
		Assert.assertNull(PlannerPipelineTiming.finish(5L));
	}

	@Test
	public void missingDuplicateAndOutOfOrderMarksFailClosed() {
		PlannerPipelineTiming.clear();
		try {
			PlannerPipelineTiming.begin(10L);
			Assert.assertThrows(IllegalStateException.class,
				() -> PlannerPipelineTiming.diagnosticsComplete(20L));
			Assert.assertThrows(IllegalStateException.class,
				() -> PlannerPipelineTiming.planningComplete(20L));

			PlannerPipelineTiming.begin(10L);
			PlannerPipelineTiming.planningComplete(20L);
			Assert.assertThrows(IllegalStateException.class,
				() -> PlannerPipelineTiming.planningComplete(21L));

			PlannerPipelineTiming.begin(10L);
			PlannerPipelineTiming.planningComplete(20L);
			Assert.assertThrows(IllegalStateException.class,
				() -> PlannerPipelineTiming.finish(30L));
		}
		finally {
			PlannerPipelineTiming.clear();
		}
	}

	@Test
	public void nonMonotonicTimestampFailsAndBeginResetsFailure() {
		PlannerPipelineTiming.clear();
		try {
			PlannerPipelineTiming.begin(100L);
			Assert.assertThrows(IllegalStateException.class,
				() -> PlannerPipelineTiming.planningComplete(99L));

			PlannerPipelineTiming.begin(200L);
			PlannerPipelineTiming.planningComplete(200L);
			PlannerPipelineTiming.diagnosticsComplete(200L);
			PlannerPipelineTiming.conversionComplete(200L);
			PlannerPipelineTiming.applicationComplete(200L);
			PlannerPipelineTiming.Timing timing = PlannerPipelineTiming.finish(200L);
			Assert.assertEquals(0L, timing.totalNanos());
		}
		finally {
			PlannerPipelineTiming.clear();
		}
	}

	@Test
	public void stateIsIsolatedPerThreadAndClearRemovesOnlyCurrentThread() throws Exception {
		PlannerPipelineTiming.clear();
		AtomicReference<PlannerPipelineTiming.Timing> childTiming = new AtomicReference<>();
		AtomicReference<Throwable> childFailure = new AtomicReference<>();
		try {
			PlannerPipelineTiming.begin(10L);
			Thread child = new Thread(() -> {
				try {
					Assert.assertNull(PlannerPipelineTiming.finish(1L));
					PlannerPipelineTiming.begin(100L);
					PlannerPipelineTiming.planningComplete(110L);
					PlannerPipelineTiming.diagnosticsComplete(120L);
					PlannerPipelineTiming.conversionComplete(130L);
					PlannerPipelineTiming.applicationComplete(140L);
					childTiming.set(PlannerPipelineTiming.finish(150L));
					PlannerPipelineTiming.clear();
					Assert.assertNull(PlannerPipelineTiming.finish(151L));
				}
				catch(Throwable failure) {
					childFailure.set(failure);
				}
			}, "planner-pipeline-timing-test");
			child.start();
			child.join();

			Assert.assertNull(childFailure.get());
			Assert.assertEquals(50L, childTiming.get().totalNanos());
			PlannerPipelineTiming.planningComplete(20L);
			PlannerPipelineTiming.diagnosticsComplete(30L);
			PlannerPipelineTiming.conversionComplete(40L);
			PlannerPipelineTiming.applicationComplete(50L);
			Assert.assertEquals(50L, PlannerPipelineTiming.finish(60L).totalNanos());
		}
		finally {
			PlannerPipelineTiming.clear();
		}
	}

	@Test
	public void timingRecordRejectsNegativeOrInexactPartitions() {
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new PlannerPipelineTiming.Timing(-1L, 0L, 0L, 0L, 0L, 0L));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new PlannerPipelineTiming.Timing(1L, 2L, 3L, 4L, 5L, 16L));
	}
}
