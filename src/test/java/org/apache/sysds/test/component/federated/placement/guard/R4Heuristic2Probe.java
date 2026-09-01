/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.HexFormat;import java.util.List;import java.util.Map;import java.util.Set;import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;import org.apache.sysds.parser.DMLProgram;import org.junit.Assert;
final class R4Heuristic2Probe {
 record Snapshot(PlacementAnalysis analysis,Object graph,String fingerprint,String graphSignature,List<String>candidates,List<String>identities,List<String>values,List<String>provenance,List<String>anchors,List<String>constraints,List<String>exclusions,List<String>relocations,List<String>obligations){}
 static Snapshot snapshot(DMLProgram p,PlacementAnalysis a){var g=a.graph();return new Snapshot(a,g,a.analysisFingerprint(),g.normalizedSignature(),g.normalizedCandidateUniverse(),g.normalizedIdentities(),g.normalizedValueVersions(),g.normalizedProvenance(),g.normalizedAnchors(),g.normalizedConstraints(),g.normalizedExclusions(),g.normalizedRelocationActions(),g.normalizedObligations());}
 static void unchanged(Snapshot a,Snapshot b){Assert.assertSame(a.analysis(),b.analysis());Assert.assertSame(a.graph(),b.graph());Assert.assertEquals(a,b);}
 static String sha256(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
 static void immutable(List<?>x){try{((List)x).add(null);Assert.fail("mutable list");}catch(UnsupportedOperationException expected){}}
 static void immutable(Map<?,?>x){try{((Map)x).put(null,null);Assert.fail("mutable map");}catch(UnsupportedOperationException expected){}}
 static void immutable(Set<?>x){try{((Set)x).add(null);Assert.fail("mutable set");}catch(UnsupportedOperationException expected){}}
 private R4Heuristic2Probe(){}
}
