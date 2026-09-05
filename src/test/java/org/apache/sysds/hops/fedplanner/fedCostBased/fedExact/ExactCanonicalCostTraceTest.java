/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.PhysicalContribution;
import org.junit.Assert;
import org.junit.Test;

public class ExactCanonicalCostTraceTest {
	@Test
	public void overlappingScopesAreReportedOnceAndIdsRoundTrip() {
		Variable x = new Variable("x", 2);
		Variable y = new Variable("y", 2);
		AtomicInteger evaluations = new AtomicInteger();
		String id = "00000000|TRANSFER|[x, y] / α";
		List<PhysicalContribution> contributions = List.of(
			new PhysicalContribution(id, Factor.lazy(List.of(x, y), values -> {
				evaluations.incrementAndGet();
				return 0.1 + values[0] + 2.0 * values[1];
			})),
			new PhysicalContribution("00000001|UNARY|[y]", Factor.dense(List.of(y), 1.0, 0.0)));
		List<String> stages = new ArrayList<>();
		List<String> messages = new ArrayList<>();
		ExactPhysicalCostModel.traceCanonicalContributions("DP", List.of(x, y), contributions,
			List.of(0, 1), Double.doubleToRawLongBits(2.1), (stage, message) -> {
				stages.add(stage);
				messages.add(message);
			});
		Assert.assertEquals(1, evaluations.get());
		Assert.assertEquals(List.of("Physical-CostContribution", "Physical-CostContribution",
			"Physical-CostContributionComplete"), stages);
		Assert.assertTrue(messages.get(0).contains("ordinal=0 unit=ms value=2.1 "));
		Assert.assertTrue(messages.get(0).endsWith("scope=0,1"));
		String encodedId = messages.get(0).split("idBase64=")[1].split(" ")[0];
		Assert.assertEquals(id, new String(Base64.getUrlDecoder().decode(encodedId), StandardCharsets.UTF_8));
		Assert.assertTrue(messages.get(1).contains("ordinal=1 unit=ms value=0.0 valueBits=0 "));
		Assert.assertTrue(messages.get(2).contains("contributions=2 unit=ms objective=2.1 "));
	}

	@Test
	public void compensatedSumMatchesCanonicalBitsForAllContributions() {
		List<PhysicalContribution> contributions = List.of(
			new PhysicalContribution("large", Factor.dense(List.of(), 1e16)),
			new PhysicalContribution("small1", Factor.dense(List.of(), 1.0)),
			new PhysicalContribution("small2", Factor.dense(List.of(), 1.0)));
		List<String> messages = new ArrayList<>();
		long expected = Double.doubleToRawLongBits(1e16 + 2.0);
		ExactPhysicalCostModel.traceCanonicalContributions("Exact", List.of(), contributions,
			List.of(), expected, (stage, message) -> messages.add(message));
		Assert.assertEquals(4, messages.size());
		Assert.assertTrue(messages.get(3).endsWith("objectiveBits=" + Long.toUnsignedString(expected)));
	}

	@Test
	public void mismatchNeverEmitsACompletionCertificate() {
		List<String> stages = new ArrayList<>();
		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalCostModel.traceCanonicalContributions("Exact", List.of(),
				List.of(new PhysicalContribution("cost", Factor.dense(List.of(), 1.0))), List.of(),
				Double.doubleToRawLongBits(2.0), (stage, message) -> stages.add(stage)));
		Assert.assertEquals(List.of("Physical-CostContribution"), stages);
	}

	@Test
	public void foreignVariablesAndInvalidAssignmentsFailClosed() {
		Variable x = new Variable("x", 1);
		Variable foreign = new Variable("x", 1);
		List<PhysicalContribution> contributions = List.of(
			new PhysicalContribution("foreign", Factor.dense(List.of(foreign), 0.0)));
		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalCostModel.traceCanonicalContributions("Exact", List.of(x), contributions,
				List.of(0), 0L, (stage, message) -> Assert.fail("must not log unproven cost")));
		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalCostModel.traceCanonicalContributions("Exact", List.of(x), List.of(),
				List.of(1), 0L, (stage, message) -> Assert.fail("must not log unproven cost")));
	}
}
