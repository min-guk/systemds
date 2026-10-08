/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.junit.Assert;
import org.junit.Test;

public class SearchSpaceFineGrainedMetricsTest {
	@Test
	public void generationCoverageSeparatesChecksFromCutsAndResets() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		metrics.recordCandidateOracleCalls(3);
		metrics.recordCandidateEarlyFeasibilityApplication();
		metrics.recordCandidateEarlyFeasibilityCheck(false);
		metrics.recordCandidateEarlyFeasibilityCheck(true);
		metrics.recordGenerationPrivacyLookup(false, false);
		metrics.recordGenerationPrivacyLookup(true, true);
		metrics.recordPrivacyInputMaskCheck();
		metrics.recordSupportMrvProduct();
		metrics.recordSupportSourceCheck(false);
		metrics.recordSupportSourceCheck(true);
		var snapshot = metrics.generationPruningCoverage();
		Assert.assertEquals(new SearchSpaceMetrics.GenerationPruningCoverage(1, 2, 1, 2, 1, 1, 1, 1, 2, 1),
			snapshot);
		Assert.assertEquals(3, metrics.privacyPruningSnapshot().oracleCalls());
		Assert.assertThrows(IllegalArgumentException.class, () -> metrics.recordCandidateOracleCalls(-1));
		metrics.reset();
		Assert.assertEquals(new SearchSpaceMetrics.GenerationPruningCoverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
			metrics.generationPruningCoverage());
		Assert.assertEquals("captured counters remain immutable", 2, snapshot.earlyFeasibilityChecks());
	}

	@Test
	public void routeRetentionIsBoundedAndPreviouslySeenOpcodesStillCount() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		for(int i = 0; i < 1000; i++)
			metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.CARTESIAN, "op-" + i);
		Assert.assertEquals(64, metrics.candidateRouteSnapshot().size());
		Assert.assertEquals(936, metrics.candidateRouteOverflow());
		metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.CARTESIAN, "op-0");
		metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.MRV, "op-0");
		Assert.assertEquals(936, metrics.candidateRouteOverflow());
		Assert.assertEquals(2, metrics.candidateRouteSnapshot().stream()
			.filter(row -> row.opcode().equals("op-0")
				&& row.route() == SearchSpaceMetrics.CandidateRoute.CARTESIAN)
			.findFirst().orElseThrow().calls());
		Assert.assertEquals(66, metrics.candidateRouteSnapshot().stream()
			.mapToLong(SearchSpaceMetrics.CandidateRouteCount::calls).sum());
		Assert.assertEquals(Long.valueOf(1001),
			metrics.candidateRouteTotalsSnapshot().get(SearchSpaceMetrics.CandidateRoute.CARTESIAN));
		Assert.assertEquals(Long.valueOf(1),
			metrics.candidateRouteTotalsSnapshot().get(SearchSpaceMetrics.CandidateRoute.MRV));
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> metrics.candidateRouteSnapshot().clear());
	}

	@Test
	public void namesAndLiteralValuesDoNotConsumeDistinctOpcodeSlots() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		for(int i = 0; i < 1000; i++) {
			metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.CARTESIAN, "TWrite v" + i);
			metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.CARTESIAN, "LiteralOp " + i);
		}
		Assert.assertEquals(2, metrics.candidateRouteSnapshot().size());
		Assert.assertEquals(0, metrics.candidateRouteOverflow());
		Assert.assertEquals("TWrite", metrics.candidateRouteSnapshot().get(0).opcode());
		Assert.assertEquals(1000, metrics.candidateRouteSnapshot().get(0).calls());
		Assert.assertEquals(Long.valueOf(2000),
			metrics.candidateRouteTotalsSnapshot().get(SearchSpaceMetrics.CandidateRoute.CARTESIAN));
	}

	@Test
	public void directSnapshotsAreDetachedAndResetClearsAllNewCounters() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED, 17);
		var before = metrics.directBindingSnapshot();
		metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED);
		Assert.assertEquals(Long.valueOf(17), before.get(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED));
		Assert.assertThrows(UnsupportedOperationException.class, before::clear);
		metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.CP_FAMILY, "wsloss");
		metrics.recordExactRule(new OracleFacade.ExactRuleDiagnostics(11, 7, 3, 2));
		metrics.recordCandidateHeader(false);
		metrics.recordCandidateHeader(true);
		metrics.recordCandidateProfile(false);
		metrics.recordCandidateProfile(true);
		metrics.recordFactorizedRelocationProduct(12, 16, 2);
		metrics.recordRelocationLeaf();
		metrics.reset();
		Assert.assertTrue(metrics.candidateRouteSnapshot().isEmpty());
		Assert.assertEquals(0, metrics.candidateRouteTotalsSnapshot().values().stream()
			.mapToLong(Long::longValue).sum());
		Assert.assertEquals(0, metrics.candidateRouteOverflow());
		Assert.assertEquals(0, metrics.directBindingSnapshot().values().stream().mapToLong(Long::longValue).sum());
		Assert.assertEquals(new SearchSpaceMetrics.CandidateConstructionSnapshot(0, 0, 0, 0, 0, 0, 0),
			metrics.candidateConstructionSnapshot());
		Assert.assertEquals(new SearchSpaceMetrics.RelocationStorageWork(0, 0, 0),
			metrics.relocationStorageSnapshot());
	}

	@Test
	public void candidateConstructionSnapshotCountsEventsAndIsDetached() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		metrics.recordExactRule(new OracleFacade.ExactRuleDiagnostics(17, 9, 4, 3));
		metrics.recordCandidateHeader(false);
		metrics.recordCandidateHeader(true);
		metrics.recordCandidateHeader(true);
		metrics.recordCandidateProfile(false);
		metrics.recordCandidateProfile(true);
		var before = metrics.candidateConstructionSnapshot();
		Assert.assertEquals(new SearchSpaceMetrics.CandidateConstructionSnapshot(17, 9, 3, 3, 2, 2, 1),
			before);

		metrics.recordExactRule(new OracleFacade.ExactRuleDiagnostics(5, 2, 1, 1));
		metrics.recordCandidateHeader(false);
		metrics.recordCandidateProfile(false);
		Assert.assertEquals("immutable snapshot must remain detached from later events",
			new SearchSpaceMetrics.CandidateConstructionSnapshot(17, 9, 3, 3, 2, 2, 1), before);
		Assert.assertEquals(new SearchSpaceMetrics.CandidateConstructionSnapshot(22, 11, 4, 4, 2, 3, 1),
			metrics.candidateConstructionSnapshot());
	}

	@Test
	public void logicalFactorizedWorkIsNotReportedAsExplicitEnumeration() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		metrics.recordFactorizedRelocationProduct(12, 16, 2);
		metrics.recordRelocationLeaf();
		metrics.recordRelocationLeaf();
		Assert.assertEquals(new SearchSpaceMetrics.RelocationStorageWork(1, 12, 2),
			metrics.relocationStorageSnapshot());
		Assert.assertEquals("historical combined counter remains unchanged", 14,
			metrics.snapshot().relocationLeaves());
	}

	@Test
	public void directBindingInstrumentationPreservesProtectedAnalysisAndCountsActualCalls() throws Exception {
		PlacementAnalysis plain = new NeutralPlacementGraphBuilder().buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementAnalysis observed = new NeutralPlacementGraphBuilder(null, metrics).buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		Assert.assertEquals(plain.analysisFingerprint(), observed.analysisFingerprint());
		Assert.assertEquals(plain.candidateRuleFacts().orderedFacts(), observed.candidateRuleFacts().orderedFacts());
		var work = metrics.directBindingSnapshot();
		var timing = metrics.attributionSnapshot();
		Assert.assertTrue(work.get(SearchSpaceMetrics.DirectWork.FACT_VISITS) > 0);
		Assert.assertEquals(work.get(SearchSpaceMetrics.DirectWork.EMISSION_VISITS).longValue(),
			work.get(SearchSpaceMetrics.DirectWork.EMISSIONS_REUSED)
				+ work.get(SearchSpaceMetrics.DirectWork.EMISSIONS_REBUILT));
		long requests = work.get(SearchSpaceMetrics.DirectWork.SEED_RELATIONS_REQUESTED);
		Assert.assertTrue(requests > 0);
		Assert.assertEquals(requests, timing.phase(SearchSpaceMetrics.Phase.DIRECT_PROOF_CALL).calls());
		Assert.assertEquals(requests, timing.phase(SearchSpaceMetrics.Phase.DIRECT_PROOF_CONSUMPTION).calls());
		Assert.assertTrue(timing.phase(SearchSpaceMetrics.Phase.DIRECT_EMISSION_CANONICALIZATION).calls() > 0);
		long publications = work.get(SearchSpaceMetrics.DirectWork.MEMOIZED_NATIVE_PUBLICATION_REQUESTS);
		Assert.assertTrue(publications > 0);
		Assert.assertTrue(work.get(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED) >= publications);
		Assert.assertTrue(work.get(SearchSpaceMetrics.DirectWork.MEMOIZED_NATIVE_PUBLICATION_CACHE_HITS)
			<= publications);
	}

	@Test
	public void liveOutputPublishesMechanismCountsBeforeAnalysisCompletion() throws Exception {
		String old = System.getProperty("sysds.fedplanner.liveMetrics");
		PrintStream stderr = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try(PrintStream capture = new PrintStream(bytes)) {
			System.setProperty("sysds.fedplanner.liveMetrics", "true");
			System.setErr(capture);
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.EXECUTION_RELATION, "+");
			metrics.recordCandidateRoute(SearchSpaceMetrics.CandidateRoute.EXACT_RULE_RESIDUAL, "rix");
			metrics.recordExactRule(new OracleFacade.ExactRuleDiagnostics(13, 8, 4, 1));
			metrics.recordCandidateHeader(false);
			metrics.recordCandidateHeader(true);
			metrics.recordCandidateProfile(false);
			metrics.recordCandidateProfile(true);
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.BINDING_CANDIDATES_EXAMINED, 7);
			var token = metrics.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
			String output = bytes.toString();
			Assert.assertTrue(output.contains("SEARCH_SPACE_COUNTERS|seq=1|Snapshot["));
			Assert.assertTrue(output.contains("SEARCH_SPACE_EXECUTION_RELATIONS|seq=1|"));
			Assert.assertTrue(output.contains("SEARCH_SPACE_CANDIDATE_CONSTRUCTION|seq=1|"
				+ "CandidateConstructionSnapshot[exactRuleCalls=13, evidenceReuses=8, evidenceOverflow=1,"
				+ " headerRequests=2, headerReuses=1, profileRequests=2, profileReuses=1]"));
			Assert.assertTrue(output.contains("BINDING_CANDIDATES_EXAMINED=7"));
			Assert.assertTrue(output.contains("route=EXECUTION_RELATION, calls=1"));
			Assert.assertTrue(output.contains("route=EXACT_RULE_RESIDUAL, calls=1"));
			Assert.assertTrue(output.contains("SEARCH_SPACE_RELOCATION_STORAGE|seq=1|"));
			Assert.assertFalse(output.contains("terminal=true"));
			metrics.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, token);
		}
		finally {
			System.setErr(stderr);
			if(old == null)
				System.clearProperty("sysds.fedplanner.liveMetrics");
			else
				System.setProperty("sysds.fedplanner.liveMetrics", old);
		}
	}
}
