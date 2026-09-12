/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class IncrementalRegionalOptimizerTest {
	private static final Limits LIMITS = new Limits(100000,1000000);
	private IncrementalRegionalOptimizer.Result run(List<Variable> variables, List<Factor> factors,
		List<Integer> seed, long assignments, long slots, double target, boolean early) {
		var problem = RegionalSearchProblem.generic(variables,factors);
		return IncrementalRegionalOptimizer.optimize(problem,problem.reducedRoot(LIMITS),seed,
			LIMITS,new IncrementalRegionalOptimizer.Options(target,assignments,slots,0,16,early), cp -> { });
	}
	private void audit(IncrementalRegionalOptimizer.Result result, double optimum) {
		double lastLower = 0, lastUpper = Double.POSITIVE_INFINITY;
		for(var cp : result.checkpoints()) {
			assertTrue(cp.phase(),cp.lower() <= optimum);
			assertTrue(cp.phase(),cp.upper() >= optimum);
			assertTrue(cp.lower() >= lastLower); assertTrue(cp.upper() <= lastUpper);
			lastLower = cp.lower(); lastUpper = cp.upper();
		}
	}
	@Test public void internalDecisionCountTracksCompleteBucketElimination() {
		var x=new Variable("x",2); var y=new Variable("y",2); var z=new Variable("z",2);
		var factors=List.of(Factor.dense(List.of(x,y),1,2,3,4),
			Factor.dense(List.of(y,z),1,3,4,2),Factor.dense(List.of(x,z),1,4,2,3));
		var result=run(List.of(x,y,z),factors,List.of(0,0,0),100000,1000000,0,false);
		// Initial triangle has no private axis. Each complete bucket removes one decision.
		assertEquals(List.of("BOOTSTRAP","INITIAL_BOUND","MERGE","MERGE","MERGE","EXACT"),
			result.checkpoints().stream().map(IncrementalRegionalOptimizer.Checkpoint::phase).toList());
		assertEquals(List.of(0,0,1,2,3,3),
			result.checkpoints().stream().map(IncrementalRegionalOptimizer.Checkpoint::internalDecisions).toList());
	}
	@Test public void internalDecisionCountExcludesSingletonsAndCountsPrivateProjection() {
		var singleton=new Variable("singleton",1); var x=new Variable("x",2); var y=new Variable("y",2);
		var factors=List.of(Factor.dense(List.of(singleton,x),3,1),Factor.dense(List.of(y),4,2));
		var result=run(List.of(singleton,x,y),factors,List.of(0,0,0),100000,1000000,0,false);
		assertEquals(List.of(0,2,2),
			result.checkpoints().stream().map(IncrementalRegionalOptimizer.Checkpoint::internalDecisions).toList());
	}
	@Test public void randomizedTriangleBoundsAndExactClosureMatchGlobal() {
		Random random = new Random(91101);
		for(int trial=0; trial<40; trial++) {
			var x=new Variable("x",2); var y=new Variable("y",2); var z=new Variable("z",3);
			List<Variable> variables=List.of(x,y,z);
			List<Factor> factors=new ArrayList<>();
			for(List<Variable> scope : List.of(List.of(x,y),List.of(y,z),List.of(x,z))) {
				int count=scope.stream().mapToInt(Variable::domainSize).reduce(1,(a,b)->a*b);
				double[] values=new double[count];
				for(int i=0;i<count;i++) values[i]=1+random.nextInt(20);
				factors.add(Factor.dense(scope,values));
			}
			factors.add(Factor.dense(List.of(),3));
			double optimum=ExactCategoricalSolver.solve(variables,factors,LIMITS).objective();
			var result=run(variables,factors,List.of(0,0,0),100000,1000000,0,false);
			audit(result,optimum); assertEquals("EXACT",result.stopReason());
			assertEquals(optimum,result.upper(),0); assertEquals(optimum,result.lower(),0);
			assertEquals(optimum,RegionalSearchProblem.evaluateFactors(variables,factors,result.assignment()),0);
		}
	}
	@Test public void capKeepsFullCoverAndCertifiedIncumbent() {
		var x=new Variable("x",2); var y=new Variable("y",2); var z=new Variable("z",2);
		var vars=List.of(x,y,z);
		var factors=List.of(Factor.dense(List.of(x,y),1,2,3,1),
			Factor.dense(List.of(y,z),1,2,3,1),Factor.dense(List.of(z,x),1,2,3,1));
		var result=run(vars,factors,List.of(0,0,0),4,100000,0,false);
		audit(result,3); assertEquals("RESOURCE",result.stopReason());
		assertEquals(3,result.upper(),0); assertTrue(result.checkpoints().get(result.checkpoints().size()-1).activeClusters()>0);
	}
	@Test public void initialMemoryCapNeverPublishesPartialCoverBound() {
		var x=new Variable("x",2);
		var result=run(List.of(x),List.of(Factor.dense(List.of(x),5,7)),List.of(0),10000,1,.05,true);
		assertEquals("RESOURCE_INITIAL",result.stopReason()); assertEquals(0,result.lower(),0); assertEquals(5,result.upper(),0);
	}
	@Test public void duplicatedFactorsAndDisconnectedComponentsCountExactlyOnceEach() {
		var x=new Variable("x",2); var y=new Variable("y",2);
		Factor repeated=Factor.dense(List.of(x),7,2);
		var factors=List.of(repeated,repeated,Factor.dense(List.of(y),3,1),Factor.dense(List.of(),4));
		var result=run(List.of(x,y),factors,List.of(0,0),10000,100000,0,false);
		audit(result,9); assertEquals("EXACT",result.stopReason()); assertEquals(9,result.upper(),0);
		assertTrue(result.checkpoints().get(result.checkpoints().size()-1).activeClusters()>1);
	}
	@Test public void targetMayStopBeforeExactWithRealConditionalDpImprovement() {
		var x=new Variable("x",2); var y=new Variable("y",2);
		var factors=List.of(Factor.dense(List.of(x),100,105),Factor.dense(List.of(x),6,1),
			Factor.dense(List.of(y),0,2));
		var result=run(List.of(x,y),factors,List.of(0,1),10000,100000,.05,true);
		audit(result,106); assertTrue(result.upper()<=108);
		assertTrue(IncrementalRegionalOptimizer.relativeGap(result.lower(),result.upper())<=.05);
	}
	@Test public void relativeGapDoesNotCertifyPositiveCostWithZeroLower() {
		assertEquals(Double.POSITIVE_INFINITY,IncrementalRegionalOptimizer.relativeGap(0,1),0);
		assertEquals(0,IncrementalRegionalOptimizer.relativeGap(0,0),0);
		assertTrue(IncrementalRegionalOptimizer.relativeGap(100,105)>=.05);
	}
	@Test public void privateProjectionUsesJointTablesAndImprovesPlanBeforePairSearch() {
		var x=new Variable("x",2); var y=new Variable("y",2); var z=new Variable("z",2);
		var factors=List.of(Factor.dense(List.of(x,y),9,8,7,1),Factor.dense(List.of(z),5,2));
		var result=run(List.of(x,y,z),factors,List.of(0,0,0),10000,100000,.05,true);
		audit(result,3); assertEquals(3,result.upper(),0);
		var initial=result.checkpoints().stream().filter(c->c.phase().equals("INITIAL_BOUND")).findFirst().orElseThrow();
		assertTrue(initial.improvements()>0); assertEquals(0,initial.scoringNanos());
		assertEquals(List.of(1,1,1),result.assignment());
	}

	@Test public void optionalDiagnosticsCannotExcludeAnAffordableExactMerge() {
		var x=new Variable("x",2);
		var factors=List.of(Factor.dense(List.of(x),1,4),Factor.dense(List.of(x),4,1));
		// Four immutable leaf cells plus four output slots fit; diagnostic marginals do not.
		var result=run(List.of(x),factors,List.of(0),100,8,0,false);
		audit(result,5); assertEquals("EXACT",result.stopReason()); assertEquals(5,result.upper(),0);
		for(var checkpoint : result.checkpoints()) assertTrue(checkpoint.retainedSlots()<=8);
	}

	@Test public void rejectedWideBucketBecomesAffordableAfterLeafElimination() {
		var x=new Variable("center",2);
		List<Variable> variables=new ArrayList<>(); variables.add(x);
		List<Factor> factors=new ArrayList<>();
		List<Integer> seed=new ArrayList<>(); seed.add(0);
		for(int i=0;i<12;i++) {
			var y=new Variable("leaf"+i,2); variables.add(y); seed.add(0);
			factors.add(Factor.dense(List.of(x,y),4,3,2,1));
			factors.add(Factor.dense(List.of(y),0,2));
		}
		double optimum=ExactCategoricalSolver.solve(variables,factors,LIMITS).objective();
		var result=run(variables,factors,seed,4,1000000,0,false);
		audit(result,optimum); assertEquals("EXACT",result.stopReason());
		assertEquals(optimum,result.upper(),0);
		assertTrue(result.checkpoints().get(result.checkpoints().size()-1).resourceRejected()>0);
	}

	@Test public void singletonStarProjectsWithoutCartesianCoupling() {
		var hub=new Variable("singleton",1);
		List<Variable> variables=new ArrayList<>(); variables.add(hub);
		List<Factor> factors=new ArrayList<>();
		List<Integer> seed=new ArrayList<>(); seed.add(0);
		for(int i=0;i<24;i++) {
			var y=new Variable("v"+i,2); variables.add(y); seed.add(0);
			factors.add(Factor.dense(List.of(hub,y),3,1));
		}
		var result=run(variables,factors,seed,2,1000000,0,false);
		audit(result,24); assertEquals("EXACT",result.stopReason()); assertEquals(24,result.upper(),0);
	}

	@Test public void sparseMultiwayGraphMaintainsEveryPrefixAndReachesGlobal() {
		Random random=new Random(99183);
		for(int trial=0;trial<20;trial++) {
			List<Variable> variables=new ArrayList<>(); List<Integer> seed=new ArrayList<>();
			for(int i=0;i<7;i++) { variables.add(new Variable("v"+i,2)); seed.add(0); }
			List<Factor> factors=new ArrayList<>();
			for(int i=0;i<7;i++) for(int j=i+1;j<7;j++) {
				if(random.nextBoolean()) factors.add(Factor.dense(List.of(variables.get(i),variables.get(j)),
					1+random.nextInt(40),1+random.nextInt(40),1+random.nextInt(40),1+random.nextInt(40)));
			}
			double optimum=ExactCategoricalSolver.solve(variables,factors,LIMITS).objective();
			var result=run(variables,factors,seed,100000,1000000,0,false);
			audit(result,optimum); assertEquals("EXACT",result.stopReason()); assertEquals(optimum,result.upper(),0);
		}
	}

}
