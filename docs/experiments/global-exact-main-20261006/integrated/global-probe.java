/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;

/** Global-only compile witness, invoked through the existing Docker comparison entrypoint. */
public final class BoundaryComparisonProbe {
 public static void main(String[] args) throws Exception {
  Map<String,Object> out = new LinkedHashMap<>();
  long started = System.nanoTime();
  try {
   boolean ok = DMLScript.executeScript(new String[]{"-f",args[0],"-config",args[1],
    "-exec","singlenode","-seed","1011081480","-stats","-explain","runtime","-noFedRuntimeConversion"});
   out.put("executeScriptReturned",ok);
   var programs = PlacementEmissionTransaction.receiptSnapshotForTesting();
   if(!ok || programs.size()!=1)
    throw new IllegalStateException("Expected one completed Global plan, committed="+programs.size());
   var selected = PlacementEmissionTransaction.currentNormalizedResult(programs.keySet().iterator().next());
   if(!"Exact".equals(selected.plannerId()))
    throw new IllegalStateException("Global probe rejects planner="+selected.plannerId());
   var a = selected.analysis();
   out.put("planner",selected.plannerId());
   out.put("objectiveCertificate",selected.objectiveCertificate());
   out.put("graphNodes",a.graph().nodes().size());
   out.put("decisionNodes",a.graph().decisionNodes().size());
   out.put("coarseAlternatives",a.graph().decisionNodes().stream().mapToInt(n->n.legalAlternatives().size()).sum());
   out.put("selectedStateCount",selected.selectedStates().size());
   out.put("selectedLocalMaterializations",selected.selectedLocalMaterializations().size());
   out.put("selectedRelocations",selected.selectedRelocations().size());
   out.put("planningSucceeded",true);
   out.put("status","passed");
  }
  catch(Throwable failure) {
   out.put("status","failed");
   out.put("planningSucceeded",false);
   out.put("error",failure.toString());
   var causes = new ArrayList<String>();
   for(Throwable t=failure;t!=null && causes.size()<16;t=t.getCause()) causes.add(t.toString());
   out.put("causes",causes);
   out.put("committedProgramsAfterFailure",PlacementEmissionTransaction.receiptSnapshotForTesting().size());
   failure.printStackTrace();
  }
  out.put("wallSeconds",(System.nanoTime()-started)/1e9);
  out.put("runtimeExecuted",false);
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),out);
  if(!"passed".equals(out.get("status"))) System.exit(1);
 }
}
