/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;
import java.lang.reflect.*;
import java.util.Set;
import org.apache.sysds.hops.fedplanner.placement.*;
import org.apache.sysds.hops.fedplanner.placement.adapter.*;
public class CanonicalJointLegalityProbe {
 public static void main(String[] args) throws Exception {
  Class<?> fixture=JointBoundaryPhysicalModelProofTest.class;
  Field f=fixture.getDeclaredField("SOURCES");f.setAccessible(true);
  Method build=fixture.getDeclaredMethod("analysis",String.class);build.setAccessible(true);
  String suffix=args[0].equals("correlated")?"if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}C=A+B;print(sum(C));":"if(p>0.5){A=X;}else{A=Y;}if(q>0.5){B=X;}else{B=Y;}C=A+B;print(sum(C));";
  PlacementAnalysis detached=(PlacementAnalysis)build.invoke(null,(String)f.get(null)+suffix);
  Field programField=PlacementAnalysis.class.getDeclaredField("programOwner"); programField.setAccessible(true);
  var program=(org.apache.sysds.parser.DMLProgram)programField.get(detached);
  org.apache.sysds.hops.OptimizerUtils.FEDERATED_COMPILATION=true;
  new org.apache.sysds.parser.DMLTranslator(program).prepareSearchSpaceOnly(program,false);
  PlacementAnalysis analysis=program.requirePlacementAnalysisAuthority();
  analysis.assertCanonicalProgramAuthority(program);
  System.out.println("CASE="+args[0]+" ANALYSIS_OK decisions="+analysis.graph().decisionNodes().size());
  try { ExactPhysicalModel.build(analysis).solveLegalityOnly(ExactPhysicalOptimizer.PRODUCTION_LIMITS);System.out.println("EXACT=FEASIBLE"); }
  catch(IllegalArgumentException e) {System.out.println("EXACT=REJECTED "+e.getMessage());}
  try { var selected=new HeuristicPlacementAdapter().select(analysis,Set.of());
   var normalized=PlacementPlannerAdapter.normalize(analysis,selected);
   System.out.println("HEURISTIC=NORMALIZED_SUCCESS candidates="+selected.selectedCandidateSelections().size()+" relocations="+selected.selectedRelocations().size());
   Method consumer=fixture.getDeclaredMethod("binaryConsumer",PlacementAnalysis.class); consumer.setAccessible(true);
   var consumerKey=(PlacementIdentity.CompiledHopKey)consumer.invoke(null,analysis);
   var receipts=new java.util.IdentityHashMap<PlacementIdentity.CompiledHopKey,PlacementIdentity.CandidateSelectionReceipt>();
   for(var c:selected.selectedCandidateSelections())receipts.put(c.rule().parentOccurrence(),c);
   var c=receipts.get(consumerKey);
   System.out.println("CONSUMER="+c.emission().emissionState()+" inputs="+c.rule().orderedInputs()+" layout="+c.realization().key().layoutKind()+" bindings="+c.supportClause().inputBindings().stream().map(b->b.kind().name()).toList());
   for(var relation:JointValueMapRelations.from(analysis)) if(relation.consumer()==consumerKey) {
    var rows=new JointValueMapRelations.Grounding(analysis,relation).rows(receipts,-1,Set.of(0,1));
    int bad=0;
    for(var row:rows) {
     var first=row.inputs().get(0).pool();
     if(row.inputs().stream().anyMatch(i->!PlacementIdentity.samePhysicalWorkerPool(first,i.pool())))bad++;
    }
    System.out.println("JOINT_ROWS="+rows.size()+" EXPECTED_ROWS="+relation.rows().size()+" INCOMPATIBLE_POOL_ROWS="+bad);
   }

   Method prevalidate=PlacementEmissionTransaction.class.getDeclaredMethod("prevalidate",org.apache.sysds.parser.DMLProgram.class,NormalizedPlannerResult.class);prevalidate.setAccessible(true);
   try {prevalidate.invoke(null,program,normalized);System.out.println("EMISSION_PREVALIDATE=ACCEPTED");}
   catch(InvocationTargetException e){System.out.println("EMISSION_PREVALIDATE=REJECTED "+e.getCause());}

  } catch(RuntimeException e) {System.out.println("HEURISTIC=REJECTED "+e.getClass().getSimpleName()+" "+e.getMessage());}
 }
}
