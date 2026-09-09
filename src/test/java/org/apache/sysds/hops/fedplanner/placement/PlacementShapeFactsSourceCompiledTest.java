/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class PlacementShapeFactsSourceCompiledTest {
 @Test public void sourceCompiledShapeIsRequiredAndFingerprintVisible() {
  CompiledHopKey key=key(); NodeShapeFact conservative=new NodeShapeFact(DataType.MATRIX,-1,-1);
  PlacementShapeFacts a=new PlacementShapeFacts(Map.of(key,conservative),Map.of(key,new NodeShapeFact(DataType.MATRIX,10,2100)),Map.of(key,AbstractShapeFact.fromConcrete(conservative)),Map.of(),Set.of(key));
  PlacementShapeFacts b=new PlacementShapeFacts(Map.of(key,conservative),Map.of(key,new NodeShapeFact(DataType.MATRIX,10,10)),Map.of(key,AbstractShapeFact.fromConcrete(conservative)),Map.of(),Set.of(key));
  Assert.assertEquals(2100,a.sourceCompiledShapeFact(key).orElseThrow().cols());
  Assert.assertNotEquals(a.normalizedSignature(),b.normalizedSignature());
  try { new PlacementShapeFacts(Map.of(key,conservative),Map.of(),Map.of(key,AbstractShapeFact.fromConcrete(conservative)),Map.of(),Set.of(key)); Assert.fail("missing source key accepted"); }
  catch(IllegalArgumentException expected) { Assert.assertTrue(expected.getMessage().contains("Source-compiled")); }
 }
 @Test public void latentShapeProofRequiresConcreteAndConservativeAgreement() {
  NodeShapeFact unknown=new NodeShapeFact(DataType.MATRIX,-1,-1);
  NodeShapeFact source=new NodeShapeFact(DataType.MATRIX,10,2100);
  Assert.assertSame(source,PlacementCostSemantics.provenLatentShape(unknown,source));
  Assert.assertNull(PlacementCostSemantics.provenLatentShape(
   new NodeShapeFact(DataType.MATRIX,11,-1),source));
  Assert.assertNotNull(PlacementCostSemantics.provenLatentShape(unknown,
   new NodeShapeFact(DataType.MATRIX,-1,2100)));
  Assert.assertNull(PlacementCostSemantics.provenLatentShape(unknown,
   new NodeShapeFact(DataType.SCALAR,-1,-1)));
 }

 private static CompiledHopKey key() {
  return new CompiledHopKey("p","main","root","compiled",new ControlRegionKey("p","main",List.of("main"),"root","compiled"),"root-0","test:1");
 }
}
