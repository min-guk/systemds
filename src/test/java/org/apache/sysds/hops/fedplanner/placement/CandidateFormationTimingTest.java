/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

public class CandidateFormationTimingTest {
	@Test
	public void exactMarksPartitionFixedBoundaryWithoutDoubleCounting() {
		CandidateFormationTiming.clear();
		try {
			CandidateFormationTiming.begin(100L);
			CandidateFormationTiming.commonPreparationComplete(110L);
			CandidateFormationTiming.analysisComplete(130L);
			CandidateFormationTiming.plannerSetupComplete(135L);
			CandidateFormationTiming.modelComplete(145L);
			CandidateFormationTiming.costSurfaceComplete(160L);
			CandidateFormationTiming.optimizerComplete(180L);
			CandidateFormationTiming.selectionComplete(205L);
			CandidateFormationTiming.planningComplete(210L);
			CandidateFormationTiming.diagnosticsComplete(215L);
			CandidateFormationTiming.conversionComplete(225L);
			CandidateFormationTiming.applicationComplete(240L);
			CandidateFormationTiming.finalVerificationComplete(250L);
			CandidateFormationTiming.registrationComplete(260L);
			CandidateFormationTiming.Timing timing = CandidateFormationTiming.finish(270L);

			Assert.assertTrue(timing.exactPhaseAttribution());
			Assert.assertEquals(10L, timing.commonPreparationNanos());
			Assert.assertEquals(20L, timing.analysisNanos());
			Assert.assertEquals(5L, timing.plannerSetupNanos());
			Assert.assertEquals(10L, timing.modelNanos());
			Assert.assertEquals(15L, timing.costSurfaceNanos());
			Assert.assertEquals(20L, timing.optimizerNanos());
			Assert.assertEquals(25L, timing.selectionNanos());
			Assert.assertEquals(5L, timing.otherPlanningNanos());
			Assert.assertEquals(5L, timing.diagnosticsNanos());
			Assert.assertEquals(10L, timing.conversionNanos());
			Assert.assertEquals(15L, timing.applicationNanos());
			Assert.assertEquals(10L, timing.finalVerificationNanos());
			Assert.assertEquals(10L, timing.registrationNanos());
			Assert.assertEquals(10L, timing.receiptHandoffNanos());
			Assert.assertEquals(170L, timing.totalNanos());
		}
		finally {
			CandidateFormationTiming.clear();
		}
	}

	@Test
	public void genericPlannerIsExplicitlyUnattributedRatherThanPretendingExactPhases() {
		CandidateFormationTiming.clear();
		try {
			CandidateFormationTiming.begin(0L);
			CandidateFormationTiming.commonPreparationComplete(1L);
			CandidateFormationTiming.analysisComplete(2L);
			CandidateFormationTiming.plannerSetupComplete(3L);
			CandidateFormationTiming.planningComplete(13L);
			CandidateFormationTiming.diagnosticsComplete(14L);
			CandidateFormationTiming.conversionComplete(15L);
			CandidateFormationTiming.applicationComplete(16L);
			CandidateFormationTiming.finalVerificationComplete(17L);
			CandidateFormationTiming.registrationComplete(18L);
			CandidateFormationTiming.Timing timing = CandidateFormationTiming.finish(19L);
			Assert.assertFalse(timing.exactPhaseAttribution());
			Assert.assertEquals(10L, timing.otherPlanningNanos());
			Assert.assertEquals(0L, timing.modelNanos());
			Assert.assertEquals(19L, timing.totalNanos());
		}
		finally {
			CandidateFormationTiming.clear();
		}
	}

	@Test
	public void disabledMarksAreNoOpsAndNestedScopesRequireLifoClose() {
		CandidateFormationTiming.clear();
		CandidateFormationTiming.modelComplete(1L);
		Assert.assertNull(CandidateFormationTiming.finish(2L));
		try {
			CandidateFormationTiming.Scope outer = CandidateFormationTiming.begin(10L);
			CandidateFormationTiming.Scope inner = CandidateFormationTiming.begin(11L);
			Assert.assertThrows(IllegalStateException.class,
				() -> CandidateFormationTiming.clear(outer));
			CandidateFormationTiming.clear(inner);
			Assert.assertThrows(IllegalStateException.class,
				() -> CandidateFormationTiming.analysisComplete(12L));
		}
		finally {
			CandidateFormationTiming.clear();
		}
	}

	@Test
	public void nonMonotonicTimestampAndInvalidRecordFail() {
		CandidateFormationTiming.clear();
		try {
			CandidateFormationTiming.begin(10L);
			Assert.assertThrows(IllegalStateException.class,
				() -> CandidateFormationTiming.commonPreparationComplete(9L));
		}
		finally {
			CandidateFormationTiming.clear();
		}
		Assert.assertThrows(IllegalArgumentException.class, () -> new CandidateFormationTiming.Timing(
			true, -1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
		Assert.assertThrows(IllegalArgumentException.class, () -> new CandidateFormationTiming.Timing(
			true, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 2L));
	}

	@Test
	public void stateIsThreadLocal() throws Exception {
		CandidateFormationTiming.clear();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			CandidateFormationTiming.Scope parent = CandidateFormationTiming.begin(0L);
			Thread child = new Thread(() -> {
				try {
					Assert.assertNull(CandidateFormationTiming.finish(0L));
					CandidateFormationTiming.begin(100L);
					CandidateFormationTiming.commonPreparationComplete(101L);
					CandidateFormationTiming.analysisComplete(102L);
					CandidateFormationTiming.plannerSetupComplete(103L);
					CandidateFormationTiming.planningComplete(104L);
					CandidateFormationTiming.diagnosticsComplete(105L);
					CandidateFormationTiming.conversionComplete(106L);
					CandidateFormationTiming.applicationComplete(107L);
					CandidateFormationTiming.finalVerificationComplete(108L);
					CandidateFormationTiming.registrationComplete(109L);
					Assert.assertEquals(10L, CandidateFormationTiming.finish(110L).totalNanos());
				}
				catch(Throwable ex) { failure.set(ex); }
				finally { CandidateFormationTiming.clear(); }
			});
			child.start();
			child.join();
			Assert.assertNull(failure.get());
			CandidateFormationTiming.Scope nested = CandidateFormationTiming.begin(1L);
			Assert.assertThrows(IllegalStateException.class,
				() -> CandidateFormationTiming.clear(parent));
			CandidateFormationTiming.clear(nested);
		}
		finally {
			CandidateFormationTiming.clear();
		}
	}
}
