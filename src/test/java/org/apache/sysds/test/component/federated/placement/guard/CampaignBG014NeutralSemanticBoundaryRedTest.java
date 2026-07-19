/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** RED guard for the policy-neutral Slice-B capture/normalization boundary. */
public class CampaignBG014NeutralSemanticBoundaryRedTest {
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path ADAPTER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java");
	private static final Path ENUMERATOR = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java");
	private static final Path REWIRE = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpRewireTransTable.java");

	@Test
	public void adapterOwnsOnlyTypedFactsAndNoDpLocalPolicyUniverse() throws Exception {
		String source = code(ADAPTER);
		for(String declaration : List.of("enum MapEntryState", "enum OracleInputState",
			"enum ConstructionDisposition", "record NeutralEnumerationContext",
			"record CandidateMapEntry", "record CandidateOccurrenceSnapshot",
			"record PreSelectionSemanticBlock", "record NormalizedCandidateInputs",
			"class DpSemanticConstructionException"))
			Assert.assertTrue("G014_NEUTRAL_TYPED_BOUNDARY_MISSING|" + declaration, source.contains(declaration));

		for(String forbidden : List.of("OracleFacade", "RulesCore", "inferFallbackFType", "workload",
			"FederatedWorker", "System.getenv", "System.getProperty"))
			Assert.assertFalse("G014_NEUTRAL_ADAPTER_POLICY_LEAK|" + forbidden, source.contains(forbidden));
	}

	@Test
	public void canonicalEnumeratorCapturesRawEntriesBeforePureNormalization() throws Exception {
		String source = code(ENUMERATOR);
		int rawCapture = source.indexOf("CandidateOccurrenceSnapshot");
		int normalization = source.indexOf("normalizeCandidateInputs", rawCapture);
		int oracle = source.indexOf("OracleUtils.decideWithOracle", normalization);
		int feasibility = source.indexOf("canSatisfyFederatedInputsFromFTypes", normalization);
		Assert.assertTrue("G014_NEUTRAL_RAW_CAPTURE_ORDER",
			rawCapture >= 0 && normalization > rawCapture && oracle > normalization && feasibility > normalization);

		String downstream = source.substring(normalization);
		Assert.assertTrue("G014_NEUTRAL_EFFECTIVE_FTYPE_LIST_REQUIRED",
			downstream.contains("effectiveCollectedFTypes()"));
		Assert.assertTrue("G014_NEUTRAL_EFFECTIVE_NON_NULL_MAP_REQUIRED",
			downstream.contains("effectiveNonNullFTypeMap()"));
		Assert.assertFalse("G014_NEUTRAL_RAW_FTYPE_LIST_REUSED_AFTER_NORMALIZATION",
			downstream.contains("decideWithOracle(\n\t\t\t\t\t\thop, privacyConstraint, collectedHops, collectedFTypes"));
	}

	@Test
	public void presentNullCannotCollapseToAbsenceOrBePromotedInPlace() throws Exception {
		String source = code(ADAPTER);
		Assert.assertTrue("G014_PRESENT_NULL_STATE_REQUIRED", source.contains("PRESENT_NULL"));
		Assert.assertTrue("G014_RAW_CONTAINS_KEY_REQUIRED", source.contains("containsKey"));
		Assert.assertTrue("G014_PRESENT_NULL_TERMINAL_DISPOSITION_REQUIRED",
			source.contains("ANCHOR_METADATA_INCOMPLETE"));
		Assert.assertFalse("G014_PRESENT_NULL_PUT_IF_ABSENT_FORBIDDEN", source.contains("putIfAbsent"));
	}

	@Test
	public void legacyFallbackAndRepairCannotRemainReachableFromCanonicalPath() throws Exception {
		String source = code(ENUMERATOR);
		Assert.assertFalse("G014_NEUTRAL_BACKFILL_CALL_REMAINS", source.contains("backfillLocalOracleInputHints(hop,"));
		Assert.assertFalse("G014_NEUTRAL_FALLBACK_INFERENCE_REMAINS", source.contains("OracleUtils.inferFallbackFType"));
		Assert.assertFalse("G014_NEUTRAL_CPFOUT_FALLBACK_REMAINS", source.contains("canUseOracleCpfoutFallback("));
		Assert.assertTrue("G014_NEUTRAL_TYPED_TERMINAL_THROW_REQUIRED",
			source.contains("DpSemanticConstructionException"));
	}

	@Test
	public void productionRewireSnapshotComesFromTheRealRewireCarriers() throws Exception {
		String enumerator = code(ENUMERATOR);
		String rewire = code(REWIRE);
		int realRewire = enumerator.indexOf("FederatedPlannerDpRewireTransTable.rewireProgram(");
		int snapshot = enumerator.indexOf("snapshotProductionRewire(", realRewire);
		int memoRegistration = enumerator.indexOf("memoTable.registerHopRefs", realRewire);
		Assert.assertTrue("G014_REWIRE_SNAPSHOT_PRODUCER_ORDER",
			realRewire >= 0 && snapshot > realRewire && memoRegistration > snapshot);
		Assert.assertTrue("G014_REWIRE_CONSUMER_EDGE_REQUIRED", rewire.contains("record RewireConsumerEdge"));
		Assert.assertTrue("G014_REWIRE_OCCURRENCE_SNAPSHOT_REQUIRED",
			rewire.contains("record RewireOccurrenceSnapshot"));
		Assert.assertFalse("G014_REWIRE_INSPECTION_CANNOT_BE_PRODUCER",
			enumerator.substring(realRewire, memoRegistration).contains("inspectExact("));
	}

	private static String code(Path path) throws Exception {
		return JavaSourceBoundaryScanner.codeOnly(Files.readString(path));
	}
}
