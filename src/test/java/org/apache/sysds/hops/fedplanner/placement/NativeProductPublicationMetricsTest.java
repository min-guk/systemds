/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class NativeProductPublicationMetricsTest {
	@Test
	public void publicationBucketsKeepLogicalAndConsumedProofsSeparateAndReset() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		SearchSpaceMetrics.NativePublicationOutcome outcome =
			SearchSpaceMetrics.NativePublicationOutcome.PUBLISHED;
		for(int logicalProofs : List.of(0, 1, 2, 3, 4, Integer.MAX_VALUE))
			metrics.recordNativePublication(outcome, logicalProofs);
		metrics.recordNativePublicationProofConsumed(outcome);
		metrics.recordNativePublicationProofConsumed(outcome);

		List<SearchSpaceMetrics.NativePublicationCount> snapshot =
			metrics.nativePublicationSnapshot();
		Assert.assertEquals(SearchSpaceMetrics.NativePublicationOutcome.values().length,
			snapshot.size());
		Assert.assertEquals(Arrays.asList(SearchSpaceMetrics.NativePublicationOutcome.values()),
			snapshot.stream().map(SearchSpaceMetrics.NativePublicationCount::outcome).toList());
		SearchSpaceMetrics.NativePublicationCount published = snapshot.stream()
			.filter(row -> row.outcome() == outcome).findFirst().orElseThrow();
		Assert.assertEquals(6, published.queries());
		Assert.assertEquals(2_147_483_657L, published.logicalProofs());
		Assert.assertEquals("consumption counts actual iteration, not logical product width",
			2, published.consumedProofs());
		Assert.assertEquals(1, published.emptyResults());
		Assert.assertEquals(1, published.singletonResults());
		Assert.assertEquals(2, published.smallProducts());
		Assert.assertEquals(2, published.largeProducts());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> metrics.recordNativePublication(outcome, -1));
		Assert.assertThrows(UnsupportedOperationException.class, snapshot::clear);

		metrics.recordNativePublication(
			SearchSpaceMetrics.NativePublicationOutcome.NO_PRODUCT, 3);
		Assert.assertEquals("captured snapshots must be detached", 0, snapshot.stream()
			.filter(row -> row.outcome() == SearchSpaceMetrics.NativePublicationOutcome.NO_PRODUCT)
			.findFirst().orElseThrow().queries());
		metrics.reset();
		for(SearchSpaceMetrics.NativePublicationCount row : metrics.nativePublicationSnapshot()) {
			Assert.assertEquals(0, row.queries());
			Assert.assertEquals(0, row.logicalProofs());
			Assert.assertEquals(0, row.consumedProofs());
			Assert.assertEquals(0, row.emptyResults());
			Assert.assertEquals(0, row.singletonResults());
			Assert.assertEquals(0, row.smallProducts());
			Assert.assertEquals(0, row.largeProducts());
		}
	}

	@Test
	public void nativePublicationLiveOutputIsOptIn() {
		String prior = System.getProperty("sysds.fedplanner.liveMetrics");
		PrintStream stderr = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try(PrintStream capture = new PrintStream(bytes)) {
			System.setErr(capture);
			System.clearProperty("sysds.fedplanner.liveMetrics");
			SearchSpaceMetrics disabled = new SearchSpaceMetrics();
			disabled.recordNativePublication(
				SearchSpaceMetrics.NativePublicationOutcome.NO_PRODUCT, 0);
			var off = disabled.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
			disabled.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, off);
			Assert.assertEquals(0, bytes.size());

			System.setProperty("sysds.fedplanner.liveMetrics", "true");
			SearchSpaceMetrics enabled = new SearchSpaceMetrics();
			enabled.recordNativePublication(
				SearchSpaceMetrics.NativePublicationOutcome.PUBLISHED, 4);
			enabled.recordNativePublicationProofConsumed(
				SearchSpaceMetrics.NativePublicationOutcome.PUBLISHED);
			var on = enabled.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
			String output = bytes.toString();
			Assert.assertTrue(output.contains("SEARCH_SPACE_NATIVE_PUBLICATION|seq=1|"));
			Assert.assertTrue(output.contains("outcome=PUBLISHED"));
			Assert.assertTrue(output.contains("logicalProofs=4"));
			Assert.assertTrue(output.contains("consumedProofs=1"));
			enabled.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, on);
		}
		finally {
			System.setErr(stderr);
			if(prior == null)
				System.clearProperty("sysds.fedplanner.liveMetrics");
			else
				System.setProperty("sysds.fedplanner.liveMetrics", prior);
		}
	}

	@Test
	public void protectedActionsPublicationOutcomesPartitionDirectWorkWithoutChangingAnalysis()
		throws Exception {
		PlacementAnalysis plain = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementAnalysis observed = new NeutralPlacementGraphBuilder(null, metrics).buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));

		Assert.assertEquals(plain.analysisFingerprint(), observed.analysisFingerprint());
		Assert.assertEquals(plain.graph().normalizedSignatureWithLegalAssignments(),
			observed.graph().normalizedSignatureWithLegalAssignments());
		Assert.assertEquals(plain.candidateRuleFacts().orderedFacts(),
			observed.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(plain.logicalTransientInputsInCanonicalOrder(),
			observed.logicalTransientInputsInCanonicalOrder());

		List<SearchSpaceMetrics.NativePublicationCount> publication =
			metrics.nativePublicationSnapshot();
		long queries = publication.stream()
			.mapToLong(SearchSpaceMetrics.NativePublicationCount::queries).sum();
		long consumed = publication.stream()
			.mapToLong(SearchSpaceMetrics.NativePublicationCount::consumedProofs).sum();
		var direct = metrics.directBindingSnapshot();
		Assert.assertTrue("protected ACTIONS must exercise publication diagnostics", queries > 0);
		Assert.assertEquals(direct.get(
			SearchSpaceMetrics.DirectWork.SEED_RELATIONS_REQUESTED).longValue(), queries);
		Assert.assertEquals(direct.get(
			SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED).longValue(), consumed);
	}
}
