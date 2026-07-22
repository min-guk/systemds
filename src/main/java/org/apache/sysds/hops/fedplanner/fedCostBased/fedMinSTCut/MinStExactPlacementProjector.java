/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Graph-free projection from exact MinST facts/selection into the placement carrier. */
public final class MinStExactPlacementProjector {
 private MinStExactPlacementProjector() {}
 public static MinStPlacementInput project(MinStExactCostFacts facts, MinStExactSelection selection) {
  Objects.requireNonNull(facts); Objects.requireNonNull(selection);
  if(!facts.analysisFingerprint().equals(facts.analysis().analysisFingerprint())) throw new IllegalArgumentException("MINST_PROJECTOR_OWNER_FINGERPRINT");
  if(MinStExactSelection.TIE_UNSPECIFIED.equals(selection.tieCertificate())) throw new IllegalArgumentException("MINST_PROJECTOR_TIE_UNSPECIFIED");
  List<CompiledHopKey> scope=facts.orderedScope(); List<PlacementState> states=selection.selectedStatesInScopeOrder();
  if(states.size()!=scope.size()) throw new IllegalArgumentException("MINST_PROJECTOR_SCOPE_CARDINALITY");
  List<MinStPlacementInput.OccurrenceReceipt> out=new ArrayList<>();
  for(int i=0;i<scope.size();i++) {
   Hop h=facts.analysis().hop(scope.get(i)).orElseThrow(); PlacementState s=states.get(i);
   ExecType e=s==null?null:s.execType(); FederatedOutput fo=s==null?FederatedOutput.NONE:s.output();
   out.add(new MinStPlacementInput.OccurrenceReceipt(scope.get(i),h,h.getHopID(),h,h.getHopID(),e,fo));
  }
  MinStPlacementInput.ProducerReceipt p=new MinStPlacementInput.ProducerReceipt(facts.analysisFingerprint(),selection.objectiveBits(),selection.sourcePartitionNodeIds());
  List<MinStPlacementInput.ObligationReceipt> obligations=new ArrayList<>();
  for(MinStExactSelection.ObligationReceipt r:selection.obligationReceiptsInOrder())
   if(r.requiredPlacement().fType()==null) throw new IllegalArgumentException("MINST_PROJECTOR_NONCONCRETE_FTYPE");
   obligations.add(new MinStPlacementInput.ObligationReceipt(r.kind().name(),r.producerKey().hashCode(),r.producerKey().hashCode(),r.actionSignature(),List.of((long)r.consumerKey().hashCode()),r.requiredPlacement().fType(),true,"EXACT_MINST_ACTION","EXACT_MINST"));
  return MinStPlacementInput.create(facts.analysis(),p,out,obligations);
 }
}
