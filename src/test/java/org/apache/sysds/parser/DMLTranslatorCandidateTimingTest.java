/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.parser;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.CandidateFormationTiming;
import org.apache.sysds.utils.Statistics;
import org.junit.Assert;
import org.junit.Test;

public class DMLTranslatorCandidateTimingTest {
	@Test
	public void successfulReceiptCallbackPrecedesCompletedTimingRecord() {
		CandidateFormationTiming.clear();
		AtomicBoolean callbackReturned = new AtomicBoolean();
		CandidateFormationTiming.Scope scope = advanceGenericInvocationToRegistration();
		try {
			CandidateFormationTiming.Timing timing = DMLTranslator.completeCandidateReceiptHandoff(
				() -> callbackReturned.set(true), scope);
			Assert.assertTrue(callbackReturned.get());
			Assert.assertNotNull(timing);
			Assert.assertTrue(timing.receiptHandoffNanos() >= 0L);
		}
		finally {
			CandidateFormationTiming.clear(scope);
		}
	}

	@Test
	public void failedReceiptCallbackClearsTimingAndCannotProduceCompletedStatistics() {
		boolean oldStatistics = DMLScript.STATISTICS;
		try {
			DMLScript.STATISTICS = true;
			Statistics.resetCompilePhaseTimes();
			CandidateFormationTiming.clear();
			CandidateFormationTiming.Scope scope = advanceGenericInvocationToRegistration();
			try {
				Assert.assertThrows(IllegalStateException.class,
					() -> DMLTranslator.completeCandidateReceiptHandoff(
						() -> { throw new IllegalStateException("callback failure"); }, scope));
			}
			finally {
				CandidateFormationTiming.clear(scope);
			}
			Assert.assertFalse(Statistics.display(1).contains("CandidateE2EReceipt"));
		}
		finally {
			Statistics.resetCompilePhaseTimes();
			DMLScript.STATISTICS = oldStatistics;
		}
	}

	@Test
	public void nestedCallbackSuccessAndFailurePreserveOuterScope() {
		CandidateFormationTiming.clear();
		CandidateFormationTiming.Scope outer = advanceGenericInvocationToRegistration();
		try {
			CandidateFormationTiming.Timing outerTiming = DMLTranslator.completeCandidateReceiptHandoff(() -> {
				CandidateFormationTiming.Scope nestedSuccess = advanceGenericInvocationToRegistration();
				try {
					Assert.assertNotNull(DMLTranslator.completeCandidateReceiptHandoff(() -> { }, nestedSuccess));
				}
				finally {
					CandidateFormationTiming.clear(nestedSuccess);
				}

				CandidateFormationTiming.Scope nestedFailure = advanceGenericInvocationToRegistration();
				try {
					Assert.assertThrows(IllegalArgumentException.class,
						() -> DMLTranslator.completeCandidateReceiptHandoff(
							() -> { throw new IllegalArgumentException("nested failure"); }, nestedFailure));
				}
				finally {
					CandidateFormationTiming.clear(nestedFailure);
				}
			}, outer);
			Assert.assertNotNull(outerTiming);
		}
		finally {
			CandidateFormationTiming.clear(outer);
		}
	}

	@Test
	public void statisticsResetAndDisplayEmitOneRawFooterPerCompletedInvocation() {
		boolean oldStatistics = DMLScript.STATISTICS;
		try {
			DMLScript.STATISTICS = true;
			Statistics.resetCompilePhaseTimes();
			Assert.assertFalse(Statistics.display(1).contains("CandidateE2EReceipt"));
			Statistics.addCompilePhaseFedPlannerCandidateE2E(new CandidateFormationTiming.Timing(
				true, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 105L));
			String displayed = Statistics.display(1);
			Assert.assertEquals(1, occurrences(displayed,
				"CandidateE2EReceipt schema=candidate-e2e-v1"));
			Assert.assertTrue(displayed.contains("calls=1 exactPhaseCalls=1"));
			Assert.assertTrue(displayed.contains("totalNanos=105"));
			Statistics.resetCompilePhaseTimes();
			Assert.assertFalse(Statistics.display(1).contains("CandidateE2EReceipt"));
		}
		finally {
			Statistics.resetCompilePhaseTimes();
			DMLScript.STATISTICS = oldStatistics;
		}
	}

	private static CandidateFormationTiming.Scope advanceGenericInvocationToRegistration() {
		CandidateFormationTiming.Scope scope = CandidateFormationTiming.begin(System.nanoTime());
		CandidateFormationTiming.commonPreparationComplete();
		CandidateFormationTiming.analysisComplete();
		CandidateFormationTiming.plannerSetupComplete();
		CandidateFormationTiming.planningComplete();
		CandidateFormationTiming.diagnosticsComplete();
		CandidateFormationTiming.conversionComplete();
		CandidateFormationTiming.applicationComplete();
		CandidateFormationTiming.finalVerificationComplete();
		CandidateFormationTiming.registrationComplete();
		return scope;
	}

	private static int occurrences(String value, String needle) {
		int count = 0;
		for(int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length())
			count++;
		return count;
	}
}
