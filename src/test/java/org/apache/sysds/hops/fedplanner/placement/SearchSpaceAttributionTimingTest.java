/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

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
		for(SearchSpaceMetrics.Phase phase : SearchSpaceMetrics.Phase.values()) {
			if(phase == SearchSpaceMetrics.Phase.PROOF_GROUNDING) {
				Assert.assertEquals("mandatory input relations make source-grounding work unnecessary", 0,
					timing.phase(phase).calls());
				Assert.assertEquals(0, timing.phase(phase).inclusiveWallNanos());
			}
			else
				Assert.assertTrue("missing phase " + phase, timing.phase(phase).inclusiveWallNanos() > 0);
		}
		SearchSpaceMetrics.PhaseMeasurement topology =
			timing.phase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		Assert.assertEquals("each measured topology call is classified as a build or hit",
			work.topologyExpansionBuilds() + work.topologyExpansionHits(), topology.calls());
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
	public void primitiveHandlePreservesNestedAccountingAndTokenInterop() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		SearchSpaceMetrics.PhaseToken root =
			metrics.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		long topology = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		SearchSpaceMetrics.PhaseToken overlay =
			metrics.startPhase(SearchSpaceMetrics.Phase.PROOF_OVERLAY);

		List<SearchSpaceMetrics.LivePhase> live = metrics.livePhaseSnapshot();
		Assert.assertEquals(1,livePhase(live,SearchSpaceMetrics.Phase.ANALYSIS).activeCount());
		Assert.assertEquals(1,livePhase(live,SearchSpaceMetrics.Phase.PROOF_TOPOLOGY).activeCount());
		Assert.assertEquals(1,livePhase(live,SearchSpaceMetrics.Phase.PROOF_OVERLAY).activeCount());

		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_OVERLAY,overlay);
		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY,topology);
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,root);

		SearchSpaceMetrics.AttributionSnapshot timing = metrics.attributionSnapshot();
		Assert.assertEquals(1,timing.phase(SearchSpaceMetrics.Phase.ANALYSIS).calls());
		Assert.assertEquals(1,timing.phase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY).calls());
		Assert.assertEquals(1,timing.phase(SearchSpaceMetrics.Phase.PROOF_OVERLAY).calls());
		long exclusiveWall = timing.phases().stream()
			.mapToLong(SearchSpaceMetrics.PhaseMeasurement::exclusiveWallNanos).sum();
		Assert.assertEquals(timing.phase(SearchSpaceMetrics.Phase.ANALYSIS).inclusiveWallNanos(),
			exclusiveWall);
		assertUnknownOrAdditive(timing,true);
		assertUnknownOrAdditive(timing,false);
	}

	@Test
	public void primitiveHandleRolloverPreservesActiveParentsAndRejectsStaleHandles() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		long stale = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY,stale);
		long parent = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.ANALYSIS);
		java.lang.reflect.Field sequence = SearchSpaceMetrics.class.getDeclaredField("nextPhaseSequence");
		sequence.setAccessible(true);
		sequence.setLong(metrics,0xffff_ffffL);
		long child = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		Assert.assertNotEquals(parent >>> 32,child >>> 32);
		assertPhaseOrderFailure(() -> metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY,stale));
		assertPhaseOrderFailure(() -> metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,parent));
		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY,child);
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,parent);
		Assert.assertEquals(2,metrics.attributionSnapshot()
			.phase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY).calls());
		Assert.assertEquals(1,metrics.attributionSnapshot()
			.phase(SearchSpaceMetrics.Phase.ANALYSIS).calls());
	}

	@Test
	public void primitiveHandleRejectsOutOfOrderAndStaleCloseWithoutMutatingStack() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		SearchSpaceMetrics foreign = new SearchSpaceMetrics();
		SearchSpaceMetrics.PhaseToken wrapped =
			metrics.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		SearchSpaceMetrics.PhaseToken foreignWrapped =
			foreign.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		assertPhaseOrderFailure(() ->
			foreign.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,wrapped));
		assertPhaseOrderFailure(() ->
			metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,foreignWrapped));
		foreign.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,foreignWrapped);
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,wrapped);
		long root = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.ANALYSIS);
		long foreignRoot = foreign.startPhaseHandle(SearchSpaceMetrics.Phase.ANALYSIS);
		assertPhaseOrderFailure(() ->
			foreign.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,root));
		assertPhaseOrderFailure(() ->
			metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,foreignRoot));
		foreign.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,foreignRoot);
		long child = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		assertPhaseOrderFailure(() ->
			metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,root));
		assertPhaseOrderFailure(() ->
			metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_OVERLAY,child));
		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY,child);
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,root);
		metrics.reset();

		long next = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.ANALYSIS);
		assertPhaseOrderFailure(() ->
			metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,root));
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,next);
		Assert.assertEquals(1,
			metrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.ANALYSIS).calls());
	}

	@Test
	public void primitiveStackGrowsPastInitialDepthWithoutChangingLifoAccounting() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		long[] handles = new long[33];
		SearchSpaceMetrics.Phase[] phases = new SearchSpaceMetrics.Phase[handles.length];
		for(int depth = 0; depth < handles.length; depth++) {
			phases[depth] = depth % 2 == 0 ? SearchSpaceMetrics.Phase.ANALYSIS
				: SearchSpaceMetrics.Phase.PROOF_TOPOLOGY;
			handles[depth] = metrics.startPhaseHandle(phases[depth]);
		}
		Assert.assertEquals(handles.length,metrics.livePhaseSnapshot().stream()
			.mapToLong(SearchSpaceMetrics.LivePhase::activeCount).sum());
		for(int depth = handles.length - 1; depth >= 0; depth--)
			metrics.finishPhase(phases[depth],handles[depth]);
		Assert.assertEquals(handles.length,metrics.attributionSnapshot().phases().stream()
			.mapToLong(SearchSpaceMetrics.PhaseMeasurement::calls).sum());
	}

	@Test
	public void liveSnapshotCombinesCompletedAndActivePrimitiveCallsWithoutClosingThem() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		long root = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.ANALYSIS);
		long completed = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY,completed);
		long active = metrics.startPhaseHandle(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);

		SearchSpaceMetrics.LivePhase first = livePhase(metrics.livePhaseSnapshot(),
			SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		SearchSpaceMetrics.LivePhase second = livePhase(metrics.livePhaseSnapshot(),
			SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		Assert.assertEquals(1,first.completedCalls());
		Assert.assertEquals(1,first.activeCount());
		Assert.assertEquals(first.completedCalls(),second.completedCalls());
		Assert.assertEquals(first.activeCount(),second.activeCount());
		Assert.assertTrue(second.inclusiveWallNanos() >= first.inclusiveWallNanos());

		metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY,active);
		metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS,root);
		SearchSpaceMetrics.AttributionSnapshot timing = metrics.attributionSnapshot();
		Assert.assertEquals(2,timing.phase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY).calls());
		Assert.assertEquals(timing.phase(SearchSpaceMetrics.Phase.ANALYSIS).inclusiveWallNanos(),
			timing.phases().stream().mapToLong(
				SearchSpaceMetrics.PhaseMeasurement::exclusiveWallNanos).sum());
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

	private static SearchSpaceMetrics.LivePhase livePhase(List<SearchSpaceMetrics.LivePhase> phases,
		SearchSpaceMetrics.Phase phase) {
		return phases.stream().filter(value -> value.phase().equals(phase.name()))
			.findFirst().orElseThrow();
	}

	private static void assertPhaseOrderFailure(Runnable close) {
		try {
			close.run();
			Assert.fail("out-of-order finish must fail closed");
		}
		catch(IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().startsWith("SEARCH_SPACE_PHASE_ORDER"));
		}
	}
}
