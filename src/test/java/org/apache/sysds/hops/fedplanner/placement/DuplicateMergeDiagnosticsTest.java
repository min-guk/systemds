/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DuplicateMergeDiagnosticsTest {
	private static final CompiledHopKey OWNER = owner();
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void optInDiagnosticsPartitionProvenAndUnresolvedDuplicatesWithoutChangingLegacyCount() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(8);
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateRealizationSupportClause a = clause("a");
			CandidateRealizationSupportClause b = clause("b");
			CandidateEmissionRealization single = realization(List.of(a));
			new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(single, single, single));

			CandidateEmissionRealization equalCopy = realization(List.of(copy(a)));
			new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(single, equalCopy));

			CandidateEmissionRealization extended = realization(List.of(a, b));
			new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(single, extended));
			CandidateEmissionRealization bOnly = realization(List.of(b));
			CandidateEmissionRealization bothCopies = realization(List.of(copy(a), copy(b)));
			new CandidateEmissionFact(EMISSION, FType.ROW, null,
				List.of(single, bOnly, bothCopies));

			SearchSpaceMetrics.DuplicateMergeDiagnosticsSnapshot diagnostics =
				metrics.duplicateMergeDiagnosticsSnapshot();
			Assert.assertTrue(diagnostics.enabled());
			Assert.assertEquals(metrics.snapshot().realizationMergeDuplicateClauses(),
				diagnostics.observedDuplicateClauses());
			Assert.assertEquals(6, diagnostics.observedDuplicateClauses());
			Assert.assertEquals(2, diagnostics.immutableListReplayClauses());
			Assert.assertEquals(1, diagnostics.sharedClauseReplayClauses());
			Assert.assertEquals(3, diagnostics.equalDistinctClauseClauses());
			Assert.assertEquals(3, diagnostics.provenanceUnresolvedClauses());
			Assert.assertEquals(4, diagnostics.traces().size());
			Assert.assertTrue(diagnostics.traces().stream().allMatch(trace ->
				trace.callSite().contains("DuplicateMergeDiagnosticsTest")));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void diagnosticsBoundOnlyLimitsTraceRetention() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(1);
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization realization = realization(List.of(clause("bounded")));
			new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(realization, realization));
			new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(realization, realization));
			SearchSpaceMetrics.DuplicateMergeDiagnosticsSnapshot diagnostics =
				metrics.duplicateMergeDiagnosticsSnapshot();
			Assert.assertEquals(2, diagnostics.observedDuplicateClauses());
			Assert.assertEquals(1, diagnostics.traces().size());
			Assert.assertEquals(1, diagnostics.droppedTraceEvents());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void diagnosticsAreDisabledByDefault() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertFalse(metrics.duplicateMergeDiagnosticsSnapshot().enabled());
	}

	@Test
	public void explicitOriginsSeparateGenerationRoutesAndRevisionReplay() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(8);
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization sameBatchLeft;
			CandidateEmissionRealization sameBatchRight;
			try(SearchSpaceMetrics.DuplicateMergeOriginScope ignored =
				metrics.beginDuplicateMergeBatchOrigin(4, "relocation", "product-a")) {
				sameBatchLeft = realization(List.of(clause("same-batch")));
				sameBatchRight = realization(List.of(clause("same-batch")));
			}
			merge(sameBatchLeft, sameBatchRight);

			CandidateEmissionRealization routeA;
			CandidateEmissionRealization routeB;
			try(SearchSpaceMetrics.DuplicateMergeOriginScope ignored =
				metrics.beginDuplicateMergeOrigin(5, "relocation", "native")) {
				routeA = realization(List.of(clause("cross-route")));
			}
			try(SearchSpaceMetrics.DuplicateMergeOriginScope ignored =
				metrics.beginDuplicateMergeOrigin(5, "relocation", "upload")) {
				routeB = realization(List.of(clause("cross-route")));
			}
			merge(routeA, routeB);

			CandidateEmissionRealization revision4;
			CandidateEmissionRealization revision5;
			try(SearchSpaceMetrics.DuplicateMergeOriginScope ignored =
				metrics.beginDuplicateMergeOrigin(6, "direct", "rebuild")) {
				revision4 = realization(List.of(clause("revision-replay")));
			}
			try(SearchSpaceMetrics.DuplicateMergeOriginScope ignored =
				metrics.beginDuplicateMergeOrigin(7, "direct", "rebuild")) {
				revision5 = realization(List.of(clause("revision-replay")));
			}
			merge(revision4, revision5);

			SearchSpaceMetrics.DuplicateMergeDiagnosticsSnapshot diagnostics =
				metrics.duplicateMergeDiagnosticsSnapshot();
			Assert.assertEquals(3, diagnostics.observedDuplicateClauses());
			Assert.assertEquals(1, diagnostics.sameBatchClauses());
			Assert.assertEquals(1, diagnostics.crossRouteClauses());
			Assert.assertEquals(1, diagnostics.unchangedRevisionReplayClauses());
			Assert.assertEquals(0, diagnostics.provenanceUnresolvedClauses());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	private static CandidateEmissionRealization realization(
		List<CandidateRealizationSupportClause> clauses) {
		return new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.sourceLineage(EMISSION, "diagnostic"), clauses);
	}

	private static void merge(CandidateEmissionRealization left,
		CandidateEmissionRealization right) {
		new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(left, right));
	}

	private static CandidateRealizationSupportClause clause(String id) {
		return new CandidateRealizationSupportClause(List.of(
			new PlacementProofKey(PlacementProofKind.SHAPE, OWNER, id)), List.of());
	}

	private static CandidateRealizationSupportClause copy(CandidateRealizationSupportClause clause) {
		return new CandidateRealizationSupportClause(clause.proofDependencies(), clause.inputBindings(),
			clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact());
	}

	private static CompiledHopKey owner() {
		ControlRegionKey region = new ControlRegionKey(
			"duplicate-diagnostics", "main", List.of("root"), "owner", "compiled");
		return new CompiledHopKey(
			"duplicate-diagnostics", "main", "owner", "compiled", region, "owner", "owner");
	}
}
