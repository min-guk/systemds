/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeProofSignatureCacheTest {
	@Test
	public void completedScopeReleasesItsBudgetAndRepeatedEndPreservesLaterCache() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(4_096L);
		try {
			PlacementIdentity.rememberSignature(new String("before"), "old-weak-entry");
			PlacementIdentity.beginAnalysisScope(null);
			String full = "x".repeat((int) (4_096L
				- PlacementIdentity.normalizedSignatureCacheRetainedChars()));
			PlacementIdentity.rememberSignature(new String("inside"), full);
			Assert.assertEquals(4_096L, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			PlacementIdentity.endAnalysisScope();
			Assert.assertEquals("released active entries must release their retention budget",
				0L, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			Assert.assertNull(PlacementIdentity.cachedSignature(new String("before")));
			Assert.assertNull(PlacementIdentity.cachedSignature(new String("inside")));

			String signature = new String("outside-signature");
			PlacementIdentity.rememberSignature(new String("outside"), signature);
			Assert.assertSame(signature, PlacementIdentity.cachedSignature(new String("outside")));
			PlacementIdentity.endAnalysisScope();
			Assert.assertSame("an idempotent end must preserve the following planner cache",
				signature, PlacementIdentity.cachedSignature(new String("outside")));
			Assert.assertEquals(signature.length(), PlacementIdentity.normalizedSignatureCacheRetainedChars());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	@Test
	public void equalProofsShareOnlyTextWithoutReplacingBindingAuthority() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(1_000_000L);
		PlacementIdentity.beginAnalysisScope(null);
		try {
			NativeContinuityProof first = proof("seed|é", "output😀", true, "source", 0);
			NativeContinuityProof copy = proof("seed|é", "output😀", true, "source", 0);
			Assert.assertEquals(first, copy);
			Assert.assertNotSame(first.immediateBindings(), copy.immediateBindings());
			CompiledHopKey copyOwner = copy.immediateBindings().get(0).source().rule().parentOccurrence();
			Assert.assertNotSame(first.immediateBindings().get(0).source().rule().parentOccurrence(), copyOwner);
			String text = first.normalizedSignature();
			Assert.assertEquals(legacy(first), text);
			long retained = PlacementIdentity.normalizedSignatureCacheRetainedChars();
			Assert.assertSame("equal proof copies reuse the existing bounded serialization cache",
				text, copy.normalizedSignature());
			Assert.assertEquals(retained, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			Assert.assertSame("only text is shared; exact binding owner authority is untouched",
				copyOwner, copy.immediateBindings().get(0).source().rule().parentOccurrence());
			for(NativeContinuityProof changed : List.of(
				proof("different-seed", "output😀", true, "source", 0),
				proof("seed|é", "different-output", true, "source", 0),
				proof("seed|é", "output😀", false, "source", 0),
				proof("seed|é", "output😀", true, "different-source", 0),
				proof("seed|é", "output😀", true, "source", 1))) {
				Assert.assertNotEquals(text, changed.normalizedSignature());
				Assert.assertEquals(legacy(changed), changed.normalizedSignature());
			}
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	@Test
	public void budgetBypassKeepsExactTextAndObjectLocalCache() {
		for(long budget : new long[] {0, 32}) {
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(budget);
			PlacementIdentity.beginAnalysisScope(null);
			try {
				NativeContinuityProof first = proof("seed", "output", true, "source", 0);
				NativeContinuityProof copy = proof("seed", "output", true, "source", 0);
				String text = first.normalizedSignature();
				Assert.assertEquals(legacy(first), text);
				Assert.assertEquals(text, copy.normalizedSignature());
				Assert.assertNotSame("an oversized serialization is not shared through the cache",
					text, copy.normalizedSignature());
				Assert.assertSame(text, first.normalizedSignature());
				Assert.assertTrue(PlacementIdentity.normalizedSignatureCacheRetainedChars() <= budget);
			}
			finally {
				PlacementIdentity.endAnalysisScope();
				PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
			}
		}
	}

	@Test
	public void equalHashStillRequiresExactProofEqualityAndCountsOnlyMissSerialization() {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(1_000_000L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			NativeContinuityProof first = proof("Aa", "output", true, "source", 0);
			NativeContinuityProof collision = proof("BB", "output", true, "source", 0);
			Assert.assertEquals("fixture forces a structural hash collision", first.hashCode(), collision.hashCode());
			Assert.assertNotEquals(first, collision);
			String text = first.normalizedSignature();
			Assert.assertEquals(legacy(first), text);
			Assert.assertNotEquals(text, collision.normalizedSignature());
			Assert.assertEquals(legacy(collision), collision.normalizedSignature());
			long hits = metrics.snapshot().signatureStructuralCacheHits();
			long serializations = metrics.snapshot().signatureSerializations();
			Assert.assertSame(text, proof("Aa", "output", true, "source", 0).normalizedSignature());
			Assert.assertEquals(hits + 1, metrics.snapshot().signatureStructuralCacheHits());
			Assert.assertEquals(serializations, metrics.snapshot().signatureSerializations());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	@Test
	public void completedAnalysisDoesNotShareTextWithTheNextAnalysis() {
		String prior;
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(1_000_000L);
		PlacementIdentity.beginAnalysisScope(null);
		try {
			prior = proof("seed", "output", true, "source", 0).normalizedSignature();
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
		PlacementIdentity.beginAnalysisScope(new SearchSpaceMetrics());
		try {
			String fresh = proof("seed", "output", true, "source", 0).normalizedSignature();
			Assert.assertEquals(prior, fresh);
			Assert.assertNotSame("completed-scope serialization ownership must be released", prior, fresh);
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	private static NativeContinuityProof proof(String seed, String output,
		boolean exact, String source, int position) {
		ControlRegionKey region = new ControlRegionKey("native-proof-cache", "main",
			List.of("root"), "root", "compiled");
		CompiledHopKey owner = new CompiledHopKey("native-proof-cache", "main", "root",
			"compiled", region, source, source);
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, false), false);
		CandidateRealizationReference reference = CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.local(emission));
		return new NativeContinuityProof(anchor(seed), anchor(output), exact,
			List.of(CandidateRealizationInputBinding.direct(position, reference)));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static String legacy(NativeContinuityProof proof) {
		return proof.externalSeed().normalizedSignature() + "|outputPool="
			+ proof.outputWorkerPoolWitness().normalizedSignature() + "|partitionRanges="
			+ (proof.exactPartitionRanges() ? "exact" : "dynamic") + "|bindings="
			+ proof.immediateBindings().stream()
				.map(CandidateRealizationInputBinding::normalizedSignature).toList();
	}
}
