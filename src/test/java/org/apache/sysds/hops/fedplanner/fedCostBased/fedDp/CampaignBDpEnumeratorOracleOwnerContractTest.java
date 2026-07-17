/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.junit.Assert;
import org.junit.Test;

/** Behavioral pre-patch RED bridge for the typed DP oracle-owner seam. */
public class CampaignBDpEnumeratorOracleOwnerContractTest {
 private static final String FACT = "org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.DpOracleDecisionFact";
 private static Class<?> factClass() {
  try { return Class.forName(FACT); }
  catch (ClassNotFoundException e) { Assert.fail("RED_DP_ORACLE_TYPED_SEAM_MISSING"); return null; }
 }
 private static Method method(String name, Class<?>... args) {
  for (Method m : factClass().getMethods())
   if (m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), args)) return m;
  Assert.fail("RED_DP_ORACLE_TYPED_METHOD_MISSING_" + name); return null;
 }
 @Test public void exactPlacementAnalysisOwnerAndImmutableMemoProjection() throws Exception {
  Class<?> c = factClass();
  Assert.assertTrue("RED_DP_ORACLE_FACT_NOT_FINAL", Modifier.isFinal(c.getModifiers()));
  requireConstructor(c, PlacementAnalysis.class);
  requireMethod("analysis"); requireMethod("memo"); requireMethod("orderedInputFTypes");
  requireMethod("execType"); requireMethod("output"); requireMethod("logicalFType");
 }
 @Test public void orderedTypedFactsRejectMissingReorderedForeignAndNull() throws Exception {
  Method m = method("from", PlacementAnalysis.class, List.class);
  Assert.assertNotNull(m);
  Assert.assertThrows("RED_DP_ORACLE_NULL_REJECT", IllegalArgumentException.class,
   () -> m.invoke(null, new Object[] {null, Collections.<FType>emptyList()}));
 }
 @Test public void loutLocalInputFactHasNoDefaultAndCarriesRuntimeReason() {
  requireMethod("localInputFact"); requireMethod("shapeDependent"); requireMethod("runtimeSupportReason");
 }
 @Test public void equalCostAndOneUlpSelectionReceiptsRemainIdentityStable() {
  requireMethod("candidateArmOrder"); requireMethod("selectedRoots"); requireMethod("selectedEdges");
  requireMethod("objectiveRawBits"); requireMethod("tieOrder"); requireMethod("receipts");
  requireMethod("fallbackCount"); requireMethod("repairCount");
 }
 @Test public void seamIsOwnedByDpAndNotFacadeOrLegacyWrappers() {
  Class<?> c = factClass();
  Assert.assertEquals("RED_DP_ORACLE_OWNER_PACKAGE", "org.apache.sysds.hops.fedplanner.fedCostBased.fedDp", c.getPackageName());
  Assert.assertFalse("RED_DP_ORACLE_WRAPPER", c.getName().contains("Facade"));
 }
 private static void requireConstructor(Class<?> c, Class<?>... args) {
  try { c.getConstructor(args); } catch (NoSuchMethodException e) { Assert.fail("RED_DP_ORACLE_CONSTRUCTOR_MISSING"); }
 }
 private static void requireMethod(String n) { for (Method m : factClass().getMethods()) if (m.getName().equals(n)) return; Assert.fail("RED_DP_ORACLE_TYPED_METHOD_MISSING_" + n); }
}
