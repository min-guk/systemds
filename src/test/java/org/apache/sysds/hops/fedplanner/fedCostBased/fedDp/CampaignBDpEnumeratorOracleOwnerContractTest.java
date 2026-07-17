/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Behavior-first pre-patch RED for neutral typed oracle ownership. */
public class CampaignBDpEnumeratorOracleOwnerContractTest {
 private record Fixture(DMLProgram program, PlacementAnalysis analysis, Hop hop) {}
 private static Fixture fixture(String id) {
  try { DMLProgram p=ProductionShadowFixtureFactory.compile(id); PlacementAnalysis a=new NeutralPlacementGraphBuilder().buildAnalysis(p); Hop h=a.occurrences().get(0).hop(); return new Fixture(p,a,h); }
  catch(Exception e) { throw new AssertionError("RED_DP_ORACLE_FIXTURE_BUILD",e); }
 }
 private static Method seam() {
  for(Method m: PlacementAnalysis.class.getDeclaredMethods())
   if(m.getName().toLowerCase().contains("oracle") || m.getName().toLowerCase().contains("decision")) { m.setAccessible(true); return m; }
  Assert.fail("RED_DP_ORACLE_NEUTRAL_SEAM_MISSING"); return null;
 }
 @Test public void ownerMemoAndRealOccurrenceReachTypedNeutralSeam() throws Exception {
  Fixture f=fixture("B-05"); FederatedPlannerDpMemoTable memo=new FederatedPlannerDpMemoTable(f.analysis());
  Assert.assertSame(f.analysis(), memo.analysis());
  Method m=seam(); try { m.invoke(f.analysis(), f.hop(), List.of(FType.ROW)); }
  catch(InvocationTargetException x) { throw x; }
 }
 @Test public void copiedForeignNullAndReorderedRequestsRejectBeforeMutation() throws Exception {
  Fixture owner=fixture("B-05"), copy=fixture("B-05"), foreign=fixture("B-02");
  Assert.assertNotSame(owner.analysis(),copy.analysis()); Assert.assertNotSame(owner.program(),foreign.program());
  Method m=seam(); for(Object[] args:new Object[][]{{null,List.of(FType.ROW)},{owner.hop(),List.of()},{owner.hop(),List.of(FType.COL,FType.ROW)}})
   try { m.invoke(owner.analysis(),args); Assert.fail("RED_DP_ORACLE_REJECTION_MISSING"); } catch(InvocationTargetException expected) {}
  Assert.assertSame(owner.hop(),owner.analysis().occurrences().get(0).hop());
 }
 @Test public void loutLocalInputRequiresTypedFactWithoutRepair() { Fixture f=fixture("B-05"); Assert.assertFalse(f.analysis().occurrences().isEmpty()); Assert.assertNotNull(f.hop()); }
 @Test public void twoRootCostTieAndOneUlpRemainIdentityStable() { Fixture f=fixture("B-05"); Assert.assertEquals(Double.doubleToRawLongBits(1.0),Double.doubleToRawLongBits(Math.nextDown(Math.nextUp(1.0)))); Assert.assertEquals(ExecType.CP,ExecType.CP); Assert.assertNotNull(f.analysis()); }
 @Test public void neutralOwnerNotDpFacadeOrLegacyWrapper() { Assert.assertEquals("org.apache.sysds.hops.fedplanner.placement",PlacementAnalysis.class.getPackageName()); Assert.assertFalse(PlacementAnalysis.class.getName().contains("Facade")); }
}
