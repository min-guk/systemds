/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
import java.lang.reflect.Method;
import java.util.List;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.*;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.*;
public class GreedyCompletenessProbe {
 public static void main(String[] args) throws Exception {
  Class<?> fixture=PolicyGreedyPlacementSelectorTest.class;
  Method node=fixture.getDeclaredMethod("node",int.class); node.setAccessible(true);
  Method forbid=fixture.getDeclaredMethod("forbid",Node.class,Node.class,PlacementState.class,PlacementState.class); forbid.setAccessible(true);
  Node p=(Node)node.invoke(null,0), l=(Node)node.invoke(null,1), r=(Node)node.invoke(null,2);
  PlacementState local=p.legalAlternatives().stream().filter(s->s.execType()==ExecType.CP).findFirst().orElseThrow();
  PlacementState fed=p.legalAlternatives().stream().filter(s->s.execType()==ExecType.FED).findFirst().orElseThrow();
  var graph=new NeutralPlacementGraph(List.of(p,l,r),List.of(
   new Constraint(ConstraintKind.DOMINATES,p.key(),l.key(),0,"data-input"),
   new Constraint(ConstraintKind.DOMINATES,p.key(),r.key(),0,"data-input"),
   (Constraint)forbid.invoke(null,p,l,fed,fed),(Constraint)forbid.invoke(null,p,r,fed,local),
   (Constraint)forbid.invoke(null,l,r,local,fed),(Constraint)forbid.invoke(null,l,r,fed,local)),List.of());
  var feasible=new PolicyFirstFeasiblePlacementSelector().select(graph);
  if(feasible.assignment().get(p.key()).execType()!=ExecType.CP) throw new AssertionError("Expected CP seed");
  System.out.println("FEASIBLE_WITNESS_P="+feasible.assignment().get(p.key()).normalizedSignature());
  for(var policy:PolicyGreedyPlacementSelector.Policy.values()) {
   try {new PolicyGreedyPlacementSelector(policy).select(graph);throw new AssertionError("Expected known greedy conflict "+policy);}
   catch(PolicyGreedyPlacementSelector.GreedyConflictException expected) {
    if(!expected.getMessage().contains("not global infeasibility") || !expected.getMessage().contains("commits=1")) throw expected;
    System.out.println("POLICY="+policy+" EXPECTED_CONFLICT="+expected.getMessage());
   }
  }
 }
}
