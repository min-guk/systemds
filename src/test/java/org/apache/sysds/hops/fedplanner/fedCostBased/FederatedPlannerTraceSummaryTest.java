/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.sysds.hops.LiteralOp;
import org.junit.Assert;
import org.junit.Test;

public class FederatedPlannerTraceSummaryTest {
	private static final Pattern TIMING = Pattern.compile(
		"elapsedNanos=(\\d+) traceOutputNanos=(\\d+) remainderNanos=(\\d+) plannerElapsedNanos=(\\d+)");

	@Test
	public void summaryModeSuppressesOptionalSuppliersButPreservesRequiredEmissionAudit()
		throws Exception {
		String output = runProbe(Boolean.FALSE);
		Assert.assertTrue(output, output.contains("DETAIL=false"));
		Assert.assertTrue(output, output.contains("SHOULD=false"));
		Assert.assertTrue(output, output.contains("OPTIONAL_SUPPLIER_CALLS=0"));
		Assert.assertTrue(output, output.contains("REQUIRED_SUPPLIER_CALLS=1"));
		Assert.assertFalse(output, output.contains("optional-detail"));
		Assert.assertFalse(output, output.contains("Physical-CostContribution] hidden"));
		Assert.assertTrue(output, output.contains("[PlannerTrace][Emission-Select]"));
		Assert.assertTrue(output, output.contains("[PlannerTrace][Emission-RegistryWrite] required"));
		Assert.assertTrue(output, output.contains("[PlannerTrace][Emission-Summary] receipt"));
		Assert.assertTrue(output, output.contains("[PlannerTrace][Planner-PhaseTiming] stage=ProbePhase "));
		assertNonNegativeTiming(output);
		assertOutputCounterRetained(output);
	}

	@Test
	public void omittedDetailsPropertyPreservesExistingDetailTrace() throws Exception {
		String output = runProbe(null);
		Assert.assertTrue(output, output.contains("DETAIL=true"));
		Assert.assertTrue(output, output.contains("SHOULD=true"));
		Assert.assertTrue(output, output.contains("OPTIONAL_SUPPLIER_CALLS=1"));
		Assert.assertTrue(output, output.contains("optional-detail"));
		Assert.assertTrue(output, output.contains("Physical-CostContribution] hidden"));
	}

	private static String runProbe(Boolean details) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		List<String> command = new ArrayList<>();
		command.add(java);
		command.add("-Dsysds.fedplanner.trace=true");
		if(details != null)
			command.add("-Dsysds.fedplanner.trace.details=" + details);
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(FederatedPlannerTraceSummaryTest.class.getName());
		command.add("probe");
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		Process process = builder.start();
		String output;
		try(BufferedReader reader = new BufferedReader(new InputStreamReader(
			process.getInputStream(), StandardCharsets.UTF_8))) {
			output = reader.lines().collect(Collectors.joining("\n"));
		}
		Assert.assertEquals(output, 0, process.waitFor());
		return output;
	}

	private static void assertNonNegativeTiming(String output) {
		Matcher matcher = TIMING.matcher(output);
		Assert.assertTrue(output, matcher.find());
		for(int group = 1; group <= 4; group++)
			Assert.assertTrue(Long.parseLong(matcher.group(group)) >= 0L);
		long elapsed = Long.parseLong(matcher.group(1));
		long traceOutput = Long.parseLong(matcher.group(2));
		long remainder = Long.parseLong(matcher.group(3));
		Assert.assertEquals(elapsed, traceOutput + remainder);
	}

	private static void assertOutputCounterRetained(String output) {
		long before = probeValue(output, "OUTPUT_BEFORE_COMPLETE");
		long after = probeValue(output, "OUTPUT_AFTER_COMPLETE");
		Assert.assertTrue(before >= 0L);
		Assert.assertTrue(after >= before);
	}

	private static long probeValue(String output, String key) {
		Matcher matcher = Pattern.compile(key + "=(\\d+)").matcher(output);
		Assert.assertTrue(output, matcher.find());
		return Long.parseLong(matcher.group(1));
	}

	public static void main(String[] args) {
		if(args.length != 1 || !"probe".equals(args[0]))
			throw new IllegalArgumentException("probe argument required");
		LiteralOp hop = new LiteralOp(7L);
		AtomicInteger optionalCalls = new AtomicInteger();
		AtomicInteger requiredCalls = new AtomicInteger();
		FederatedPlannerTrace.beginInvocation();
		FederatedPlannerTrace.startPlannerTiming(System.nanoTime());
		System.out.println("DETAIL=" + FederatedPlannerTrace.isDetailEnabled());
		System.out.println("SHOULD=" + FederatedPlannerTrace.shouldTrace(hop));
		FederatedPlannerTrace.logLazy(hop, "Optional-Hop-Detail", () -> {
			optionalCalls.incrementAndGet();
			return "optional-detail";
		});
		FederatedPlannerTrace.logLazy(hop, "Emission-Select", () -> {
			requiredCalls.incrementAndGet();
			return "required-selection";
		});
		FederatedPlannerTrace.logGlobal("Physical-CostContribution", "hidden");
		FederatedPlannerTrace.logGlobal("Emission-RegistryWrite", "required");
		FederatedPlannerTrace.logGlobal("Emission-Summary", "receipt");
		long phaseStarted = System.nanoTime();
		long phaseOutputStarted = FederatedPlannerTrace.traceOutputNanos();
		FederatedPlannerTrace.logGlobal("ProbePhase-Work", "measured-output");
		FederatedPlannerTrace.logPhaseTiming("ProbePhase", phaseStarted,
			phaseOutputStarted);
		System.out.println("OPTIONAL_SUPPLIER_CALLS=" + optionalCalls.get());
		System.out.println("REQUIRED_SUPPLIER_CALLS=" + requiredCalls.get());
		System.out.println("OUTPUT_BEFORE_COMPLETE=" + FederatedPlannerTrace.traceOutputNanos());
		FederatedPlannerTrace.completeInvocation();
		System.out.println("OUTPUT_AFTER_COMPLETE=" + FederatedPlannerTrace.traceOutputNanos());
	}
}
