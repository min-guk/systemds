/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

public class FederatedPlannerTraceTimingTest {
	@Test
	public void suppliedStartProducesMonotonicElapsedAndClearRestoresMissingSentinel() {
		FederatedPlannerTrace.clearPlannerTiming();
		try {
			Assert.assertEquals(-1L, FederatedPlannerTrace.plannerElapsedNanos());
			long minimumElapsed = 1_000_000L;
			FederatedPlannerTrace.startPlannerTiming(System.nanoTime() - minimumElapsed);

			long first = FederatedPlannerTrace.plannerElapsedNanos();
			long second = FederatedPlannerTrace.plannerElapsedNanos();

			Assert.assertTrue(first >= minimumElapsed);
			Assert.assertTrue(second >= first);
		}
		finally {
			FederatedPlannerTrace.clearPlannerTiming();
		}
		Assert.assertEquals(-1L, FederatedPlannerTrace.plannerElapsedNanos());
	}

	@Test
	public void invocationBoundariesClearStaleAndCompletedPlannerClocks() {
		FederatedPlannerTrace.clearPlannerTiming();
		try {
			FederatedPlannerTrace.startPlannerTiming(System.nanoTime());
			FederatedPlannerTrace.beginInvocation();
			Assert.assertEquals("a new invocation must not inherit an earlier planner clock",
				-1L, FederatedPlannerTrace.plannerElapsedNanos());

			FederatedPlannerTrace.startPlannerTiming(System.nanoTime());
			FederatedPlannerTrace.completeInvocation();
			Assert.assertEquals("completion must clear timing even when tracing is disabled",
				-1L, FederatedPlannerTrace.plannerElapsedNanos());
		}
		finally {
			FederatedPlannerTrace.clearPlannerTiming();
		}
	}

	@Test
	public void plannerClockIsIsolatedPerThread() throws InterruptedException {
		FederatedPlannerTrace.clearPlannerTiming();
		AtomicLong childInitial = new AtomicLong(Long.MIN_VALUE);
		AtomicLong childElapsed = new AtomicLong(Long.MIN_VALUE);
		AtomicLong childCleared = new AtomicLong(Long.MIN_VALUE);
		AtomicReference<Throwable> childFailure = new AtomicReference<>();
		long parentMinimumElapsed = 1_000_000L;
		try {
			FederatedPlannerTrace.startPlannerTiming(System.nanoTime() - parentMinimumElapsed);
			Thread child = new Thread(() -> {
				try {
					childInitial.set(FederatedPlannerTrace.plannerElapsedNanos());
					long childMinimumElapsed = 2_000_000L;
					FederatedPlannerTrace.startPlannerTiming(System.nanoTime() - childMinimumElapsed);
					childElapsed.set(FederatedPlannerTrace.plannerElapsedNanos());
					FederatedPlannerTrace.clearPlannerTiming();
					childCleared.set(FederatedPlannerTrace.plannerElapsedNanos());
				}
				catch(Throwable failure) {
					childFailure.set(failure);
				}
			}, "fed-planner-trace-timing-test");
			child.start();
			child.join();

			Assert.assertNull(childFailure.get());
			Assert.assertEquals(-1L, childInitial.get());
			Assert.assertTrue(childElapsed.get() >= 2_000_000L);
			Assert.assertEquals(-1L, childCleared.get());
			Assert.assertTrue("the child must not clear the parent thread's clock",
				FederatedPlannerTrace.plannerElapsedNanos() >= parentMinimumElapsed);
		}
		finally {
			FederatedPlannerTrace.clearPlannerTiming();
		}
	}
}
