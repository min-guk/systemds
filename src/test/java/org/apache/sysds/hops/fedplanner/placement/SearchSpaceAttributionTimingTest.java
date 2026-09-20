/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for opt-in, aggregate-only G009 phase attribution. */
public class SearchSpaceAttributionTimingTest {
	private record SyntheticSafetyContext(String query, int revision, String product,
		int forcedHash) {
		@Override public int hashCode() { return forcedHash; }
	}

	@Test
	public void instrumentationIsSemanticallyInertAndReportsBoundedObserverCost() throws Exception {
		PlacementAnalysis plain = new NeutralPlacementGraphBuilder()
			.buildAnalysis(NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementAnalysis observed = new NeutralPlacementGraphBuilder(null, metrics)
			.buildAnalysis(NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));

		Assert.assertEquals(plain.analysisFingerprint(), observed.analysisFingerprint());
		Assert.assertEquals(plain.graph().normalizedSignatureWithLegalAssignments(),
			observed.graph().normalizedSignatureWithLegalAssignments());
		Assert.assertEquals(plain.candidateRuleFacts().orderedFacts(),
			observed.candidateRuleFacts().orderedFacts());
		SearchSpaceMetrics.Snapshot work = metrics.snapshot();
		SearchSpaceMetrics.AttributionSnapshot timing = metrics.attributionSnapshot();
		Assert.assertEquals(work.proofQueries(), work.exactContextUniqueQueries()
			+ work.exactContextRepeatedQueries() + work.exactContextOverflowQueries());
		for(SearchSpaceMetrics.Phase phase : SearchSpaceMetrics.Phase.values())
			if(phase != SearchSpaceMetrics.Phase.PUBLIC_PROOF_MATERIALIZATION)
				Assert.assertTrue("missing phase " + phase,
					timing.phase(phase).inclusiveWallNanos() > 0);
		Assert.assertEquals("analysis construction uses factorized proof products, not the opt-in "
			+ "legacy public-proof export boundary", 0,
			timing.phase(SearchSpaceMetrics.Phase.PUBLIC_PROOF_MATERIALIZATION).calls());
		SearchSpaceMetrics.PhaseMeasurement topology =
			timing.phase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		Assert.assertEquals("opt-in timing measures topology expansions, not cache-hit overhead",
			work.topologyExpansionBuilds(), topology.calls());
		Assert.assertTrue("cache hits remain available as aggregate diagnostics",
			work.topologyExpansionHits() > 0);
		SearchSpaceMetrics.PhaseMeasurement overlay =
			timing.phase(SearchSpaceMetrics.Phase.PROOF_OVERLAY);
		Assert.assertTrue("recursive topology work must be removed from overlay exclusive time",
			overlay.exclusiveWallNanos() < overlay.inclusiveWallNanos());
		long exclusiveWall = timing.phases().stream()
			.mapToLong(SearchSpaceMetrics.PhaseMeasurement::exclusiveWallNanos).sum();
		Assert.assertEquals(timing.phase(SearchSpaceMetrics.Phase.ANALYSIS).inclusiveWallNanos(),
			exclusiveWall);
		assertUnknownOrAdditive(timing, true);
		assertUnknownOrAdditive(timing, false);
	}

	@Test
	public void publicProofExportIsTimedOnceAndMemoHitsDoNotReenterThePhase() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		PlacementAnalysis.CandidateRuleFact fact = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(candidate -> !candidate.allowedEmissionFacts().isEmpty())
			.findFirst().orElseThrow();
		CandidateRealizationReference reference = CandidateRealizationReference.of(fact.key(),
			fact.allowedEmissionFacts().get(0).realizations().get(0));
		DurableAnchorKey unavailableSeed = new DurableAnchorKey("public-proof-timing-seed", FType.FULL,
			List.of(new AnchorPartition("worker1:8001", List.of(0L, 0L), List.of(1L, 1L))));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(Map.of(), Map.of(),
			List.of(), List.of(), Map.of(), metrics);

		Assert.assertTrue("the isolated resolver deliberately has no proof authority",
			continuity.proveCandidateAlternatives(reference, unavailableSeed).isEmpty());
		SearchSpaceMetrics.PhaseMeasurement measured = metrics.attributionSnapshot()
			.phase(SearchSpaceMetrics.Phase.PUBLIC_PROOF_MATERIALIZATION);
		Assert.assertEquals(1, measured.calls());
		Assert.assertTrue("the public legacy export boundary must report elapsed wall time",
			measured.inclusiveWallNanos() > 0);

		Assert.assertTrue(continuity.proveCandidateAlternatives(reference, unavailableSeed).isEmpty());
		Assert.assertEquals("memo hits must not be charged as proof materialization", 1,
			metrics.attributionSnapshot()
				.phase(SearchSpaceMetrics.Phase.PUBLIC_PROOF_MATERIALIZATION).calls());
	}

	@Test
	public void resetClearsCountersAndTiming() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		SearchSpaceMetrics.PhaseToken started =
			metrics.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, started);
		metrics.recordContextObserver(2);
		metrics.reset();
		Assert.assertEquals(0, metrics.snapshot().contextObserverHashCollisions());
		Assert.assertEquals(0,
			metrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.ANALYSIS).calls());
	}

	@Test
	public void phaseStackRejectsOutOfOrderCloseAndResetClearsIt() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		SearchSpaceMetrics.PhaseToken root =
			metrics.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		SearchSpaceMetrics.PhaseToken child =
			metrics.startPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		try {
			metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, root);
			Assert.fail("out-of-order finish must fail closed");
		}
		catch(IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().startsWith("SEARCH_SPACE_PHASE_ORDER"));
		}
		try {
			metrics.reset();
			Assert.fail("reset while phases are active must fail closed");
		}
		catch(IllegalStateException expected) {
			Assert.assertEquals("SEARCH_SPACE_PHASE_RESET_WHILE_ACTIVE", expected.getMessage());
		}
		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY, child);
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, root);
		metrics.recordContextObserver(2);
		metrics.reset();
		Assert.assertEquals(0, metrics.snapshot().contextObserverHashCollisions());
		Assert.assertEquals(0,
			metrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.ANALYSIS).calls());
	}

	@Test
	public void boundedObserverVerifiesCollisionsAndFullSafetyContextBeforeOverflow() {
		SearchSpaceMetrics.BoundedContextObserver<SyntheticSafetyContext> observer =
			new SearchSpaceMetrics.BoundedContextObserver<>(2);
		SyntheticSafetyContext revisionOne = new SyntheticSafetyContext("q", 1, "p=a*b", 17);
		SyntheticSafetyContext revisionTwo = new SyntheticSafetyContext("q", 2, "p=a*b", 17);
		SyntheticSafetyContext otherProduct = new SyntheticSafetyContext("q", 2, "p=a+c", 17);

		Assert.assertEquals(SearchSpaceMetrics.ContextObservationResult.FIRST,
			observer.observe(revisionOne).result());
		SearchSpaceMetrics.ContextObservation second = observer.observe(revisionTwo);
		Assert.assertEquals(SearchSpaceMetrics.ContextObservationResult.FIRST, second.result());
		Assert.assertEquals(1, second.verifiedHashCollisions());
		Assert.assertEquals(SearchSpaceMetrics.ContextObservationResult.REPEATED,
			observer.observe(revisionOne).result());
		SearchSpaceMetrics.ContextObservation overflow = observer.observe(otherProduct);
		Assert.assertEquals(SearchSpaceMetrics.ContextObservationResult.OVERFLOW, overflow.result());
		Assert.assertEquals("all equal-hash retained keys are checked before overflow",
			2, overflow.verifiedHashCollisions());
	}

	@Test
	public void instrumentedFailurePreservesCauseAndMetricsRemainReusable() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder failing = new NeutralPlacementGraphBuilder(pass -> {
			throw new IllegalStateException("EXPECTED_INSTRUMENTED_FAILURE");
		}, metrics);
		try {
			failing.buildAnalysis(NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
			Assert.fail("fixture observer must fail");
		}
		catch(IllegalStateException expected) {
			Assert.assertEquals("EXPECTED_INSTRUMENTED_FAILURE", expected.getMessage());
		}
		Assert.assertEquals(1,
			metrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.ANALYSIS).calls());

		PlacementAnalysis reused = new NeutralPlacementGraphBuilder(null, metrics)
			.buildAnalysis(NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		Assert.assertNotNull(reused.analysisFingerprint());
		Assert.assertEquals(1,
			metrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.ANALYSIS).calls());
	}

	private static void assertUnknownOrAdditive(SearchSpaceMetrics.AttributionSnapshot timing,
		boolean cpu) {
		long root = cpu ? timing.phase(SearchSpaceMetrics.Phase.ANALYSIS).inclusiveCpuNanos()
			: timing.phase(SearchSpaceMetrics.Phase.ANALYSIS).inclusiveAllocatedBytes();
		long[] values = timing.phases().stream().mapToLong(phase -> cpu
			? phase.exclusiveCpuNanos() : phase.exclusiveAllocatedBytes()).toArray();
		if(root < 0) {
			Assert.assertTrue("unknown support must remain explicit",
				java.util.Arrays.stream(values).allMatch(value -> value == -1 || value == 0));
		}
		else {
			Assert.assertTrue(java.util.Arrays.stream(values).allMatch(value -> value >= 0));
			Assert.assertEquals(root, java.util.Arrays.stream(values).sum());
		}
	}
}
