/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;

/** Pre-patch RED contract for typed, immutable DP oracle ownership. */
public class CampaignBDpEnumeratorOracleOwnerContractTest {
 private static final Path ENUM = Path.of("src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java");
 private static final Path MEMO = Path.of("src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java");
 private static String source(Path p) throws Exception { return Files.readString(p); }
 private static void require(String text, String needle, String prefix) { Assert.assertTrue(prefix + " missing: " + needle, text.contains(needle)); }

 @Test public void typedCanonicalOracleFactsBindExactPlacementAnalysisOwner() throws Exception {
  String s = source(ENUM) + source(MEMO);
  require(s, "PlacementAnalysis", "RED_DP_ORACLE_OWNER");
  require(s, "canonical", "RED_DP_ORACLE_TYPED_FACTS");
  require(s, "ordered", "RED_DP_ORACLE_ORDER");
  require(s, "logicalFType", "RED_DP_ORACLE_LOGICAL_FTYPE");
  require(s, "shapeDependence", "RED_DP_ORACLE_SHAPE_DEPENDENCE");
  require(s, "runtimeSupportReason", "RED_DP_ORACLE_RUNTIME_REASON");
  require(s, "assertProgramOwner", "RED_DP_ORACLE_IDENTITY");
 }

 @Test public void foreignCopiedNullAndInternalBuildRejectBeforeMutation() throws Exception {
  String s = source(ENUM) + source(MEMO);
  require(s, "foreign", "RED_DP_ORACLE_FOREIGN");
  require(s, "null", "RED_DP_ORACLE_NULL");
  require(s, "internalAnalysisBuildCount", "RED_DP_ORACLE_INTERNAL_BUILD");
  require(s, "mutation", "RED_DP_ORACLE_MUTATION_FREE");
  require(s, "copy", "RED_DP_ORACLE_COPY_REJECT");
 }

 @Test public void orderedInputFactsAndLoutFixtureHaveNoDefaultOrReordering() throws Exception {
  String s = source(ENUM);
  require(s, "LOUT", "RED_DP_ORACLE_LOUT_LOCAL_INPUT");
  require(s, "inputFTypes", "RED_DP_ORACLE_INPUT_VECTOR");
  require(s, "reordered", "RED_DP_ORACLE_REORDER");
  require(s, "no default", "RED_DP_ORACLE_NO_DEFAULT");
  Assert.assertTrue("RED_DP_ORACLE_OCCURRENCE_COUNT", Pattern.compile("OracleFacade").matcher(s).results().count() >= 32);
 }

 @Test public void equalCostAndOneUlpSnapshotsPreserveTieOrderAndReceipts() throws Exception {
  String s = source(ENUM) + source(MEMO);
  require(s, "Double.doubleToRawLongBits", "RED_DP_ORACLE_COST_BITS");
  require(s, "tie", "RED_DP_ORACLE_TIE_ORDER");
  require(s, "receipts", "RED_DP_ORACLE_RECEIPTS");
  require(s, "oneUlp", "RED_DP_ORACLE_ONE_ULP");
  require(s, "candidate arms", "RED_DP_ORACLE_ARMS");
  require(s, "roots", "RED_DP_ORACLE_ROOTS");
  require(s, "edges", "RED_DP_ORACLE_EDGES");
 }

 @Test public void structuralBoundaryHasNoDirectFacadeOrLegacyFallbackOwner() throws Exception {
  String s = source(ENUM);
  require(s, "OracleFacade", "RED_DP_ORACLE_FACADE_BOUNDARY");
  require(s, "RuleRegistry", "RED_DP_ORACLE_REGISTRY_BOUNDARY");
  require(s, "RulesCore", "RED_DP_ORACLE_RULES_BOUNDARY");
  require(s, "legacy", "RED_DP_ORACLE_LEGACY_OVERLOAD");
  require(s, "fallback", "RED_DP_ORACLE_FALLBACK_BOUNDARY");
  Assert.assertFalse("RED_DP_ORACLE_WRAPPER_HIDING", s.contains("Object oracle"));
 }
}
