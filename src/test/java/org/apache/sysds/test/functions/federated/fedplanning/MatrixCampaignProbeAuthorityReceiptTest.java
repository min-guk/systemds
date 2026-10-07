/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.functions.federated.fedplanning;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit.AuthorityGeneration;
import org.junit.Assert;
import org.junit.Test;

public class MatrixCampaignProbeAuthorityReceiptTest {
	@Test
	public void heapWitnessObservesAnOverrideOfTheXmxSpelling() throws Exception {
		Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-Xmx96m", "-XX:MaxHeapSize=64m", "-cp", System.getProperty("java.class.path"),
			HeapWitness.class.getName()).redirectErrorStream(true).start();
		String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		Assert.assertEquals(output, 0, child.waitFor());
		Assert.assertEquals(Long.toString(64L * 1024 * 1024), output);
	}

	public static final class HeapWitness {
		public static void main(String[] args) {
			System.out.println(MatrixCampaignProbe.effectiveMaximumHeapSizeBytes());
		}
	}

	@Test
	public void initialAndRecompiledAuthoritiesRemainDistinct() {
		var receipt = new LinkedHashMap<String,Object>();
		var generations = List.of(generation(0, "a"), generation(1, "b"), generation(2, "b"));
		MatrixCampaignProbe.appendAuthorityReceipt(receipt, audit("b", 2), generations);
		Assert.assertEquals("a".repeat(64), receipt.get("initialSelectionFingerprint"));
		Assert.assertEquals("b".repeat(64), receipt.get("finalSelectionFingerprint"));
		Assert.assertEquals(List.of(
			Map.of("sequence", 0, "planFingerprint", "a".repeat(64), "analysisFingerprint", "c".repeat(64)),
			Map.of("sequence", 1, "planFingerprint", "b".repeat(64), "analysisFingerprint", "c".repeat(64)),
			Map.of("sequence", 2, "planFingerprint", "b".repeat(64), "analysisFingerprint", "c".repeat(64))),
			receipt.get("plannerAuthorityGenerations"));
	}

	@Test
	public void missingCommittedAuthorityCannotProduceSuccessReceipt() {
		Assert.assertThrows(IllegalStateException.class, () -> MatrixCampaignProbe.appendAuthorityReceipt(
			new LinkedHashMap<>(), audit("a", 0), List.of()));
	}

	@Test
	public void auditMustNameTheLastCommittedAuthority() {
		Assert.assertThrows(IllegalStateException.class, () -> MatrixCampaignProbe.appendAuthorityReceipt(
			new LinkedHashMap<>(), audit("a", 2), List.of(generation(0, "a"), generation(1, "b"))));
	}

	@Test
	public void auditCountsDistinctPlansWhileReceiptRetainsEveryCommit() {
		Assert.assertThrows(IllegalStateException.class, () -> MatrixCampaignProbe.appendAuthorityReceipt(
			new LinkedHashMap<>(), audit("b", 3),
			List.of(generation(0, "a"), generation(1, "b"), generation(2, "b"))));
	}

	@Test
	public void generationSequenceCannotOmitInitialOrIntermediateCommit() {
		for(var generations : List.of(List.of(generation(1, "a")),
			List.of(generation(0, "a"), generation(2, "b")))) {
			String finalPlan = generations.get(generations.size() - 1).planFingerprint();
			Assert.assertThrows(IllegalStateException.class, () -> MatrixCampaignProbe.appendAuthorityReceipt(
				new LinkedHashMap<>(), Map.of("plan", finalPlan, "authorityGenerations", generations.size()), generations));
		}
	}

	private static AuthorityGeneration generation(int sequence, String plan) {
		return new AuthorityGeneration(sequence, plan.repeat(64), "c".repeat(64));
	}

	private static Map<String,Object> audit(String plan, int unique) {
		return Map.of("plan", plan.repeat(64), "authorityGenerations", unique);
	}
}
