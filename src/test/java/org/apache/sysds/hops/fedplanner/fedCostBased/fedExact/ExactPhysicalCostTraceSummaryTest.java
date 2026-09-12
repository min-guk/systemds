/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.PhysicalContribution;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalCostTraceSummaryTest {
	@Test
	public void summaryModeReevaluatesAndValidatesCostsWithoutFormattingItems() {
		Variable variable = new Variable("x", 2);
		AtomicInteger evaluations = new AtomicInteger();
		List<PhysicalContribution> contributions = List.of(new PhysicalContribution(
			"identifier whose encoding is detail only", Factor.lazy(List.of(variable), values -> {
				evaluations.incrementAndGet();
				return values[0] == 1 ? 3.5 : 7.0;
			})));
		List<String> stages = new ArrayList<>();
		List<String> messages = new ArrayList<>();

		ExactPhysicalCostModel.traceCanonicalContributions("Exact", List.of(variable),
			contributions, List.of(1), Double.doubleToRawLongBits(3.5), false,
			(stage, message) -> {
				stages.add(stage);
				messages.add(message);
			});

		Assert.assertEquals(1, evaluations.get());
		Assert.assertEquals(List.of("Physical-CostContributionAuditTiming",
			"Physical-CostContributionComplete"), stages);
		Assert.assertFalse(messages.toString().contains("idBase64="));
		Assert.assertTrue(messages.get(1).contains("objectiveBits="
			+ Long.toUnsignedString(Double.doubleToRawLongBits(3.5))));
		assertNonNegativeCounter(messages.get(0), "evaluationValidationNanos");
		Assert.assertEquals(0L, counter(messages.get(0), "detailFormattingOutputNanos"));
	}

	@Test
	public void summaryModeStillThrowsOnObjectiveMismatch() {
		AtomicInteger evaluations = new AtomicInteger();
		List<String> stages = new ArrayList<>();
		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalCostModel.traceCanonicalContributions("DP", List.of(),
				List.of(new PhysicalContribution("cost", Factor.lazy(List.of(), values -> {
					evaluations.incrementAndGet();
					return 1.0;
				}))), List.of(), Double.doubleToRawLongBits(2.0), false,
				(stage, message) -> stages.add(stage)));
		Assert.assertEquals(1, evaluations.get());
		Assert.assertTrue(stages.isEmpty());
	}

	@Test
	public void detailModeAccountsForFormattingAndPreservesCompletion() {
		List<String> stages = new ArrayList<>();
		List<String> messages = new ArrayList<>();
		ExactPhysicalCostModel.traceCanonicalContributions("DP", List.of(),
			List.of(new PhysicalContribution("detail", Factor.dense(List.of(), 1.0))),
			List.of(), Double.doubleToRawLongBits(1.0), true, (stage, message) -> {
				stages.add(stage);
				messages.add(message);
			});
		Assert.assertEquals(List.of("Physical-CostContribution",
			"Physical-CostContributionAuditTiming", "Physical-CostContributionComplete"), stages);
		Assert.assertTrue(messages.get(0).contains("idBase64="));
		assertNonNegativeCounter(messages.get(1), "evaluationValidationNanos");
		assertNonNegativeCounter(messages.get(1), "detailFormattingOutputNanos");
	}

	private static void assertNonNegativeCounter(String message, String name) {
		Assert.assertTrue(message, counter(message, name) >= 0L);
	}

	private static long counter(String message, String name) {
		Matcher matcher = Pattern.compile(name + "=(\\d+)").matcher(message);
		Assert.assertTrue(message, matcher.find());
		return Long.parseLong(matcher.group(1));
	}
}
