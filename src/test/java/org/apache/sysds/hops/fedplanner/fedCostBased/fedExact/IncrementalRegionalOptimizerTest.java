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
		return run(variables,factors,seed,assignments,slots,target,early,16);
	}
	private IncrementalRegionalOptimizer.Result run(List<Variable> variables, List<Factor> factors,
		List<Integer> seed, long assignments, long slots, double target, boolean early, int scored) {
		var problem = RegionalSearchProblem.generic(variables,factors);
		return IncrementalRegionalOptimizer.optimize(problem,problem.reducedRoot(LIMITS),seed,
			LIMITS,new IncrementalRegionalOptimizer.Options(target,assignments,slots,0,scored,early), cp -> { });
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
	@Test public void conditionalNeighborhoodsShareRootPreparation() {
		List<Variable> variables = List.of(new Variable("shared-a",3),
			new Variable("shared-b",3),new Variable("shared-c",3));
		List<Factor> factors = new ArrayList<>();
		for(Variable variable : variables) {
			factors.add(Factor.dense(List.of(variable),0,9,2));
			factors.add(Factor.dense(List.of(variable),9,0,2));
		}
		var problem = RegionalSearchProblem.generic(variables,factors);
		var result = IncrementalRegionalOptimizer.optimize(problem,problem.reducedRoot(LIMITS),
			List.of(0,0,0),LIMITS,
			new IncrementalRegionalOptimizer.Options(0,100000,1000000,0,16,false),ignored -> { });
		assertEquals(List.of(2,2,2),result.assignment());
		assertEquals(12,result.upper(),0);
		assertTrue(result.checkpoints().stream().anyMatch(cp -> cp.conditionalAttempts() >= 3));
		assertEquals("one caller root and one shared conditional preparation",2,
			problem.reducedRootRequests());
		audit(result,12);
	}
	@Test public void productionPathHasNoFormerMillionAssignmentOrElapsedStop() {
		var x = new Variable("unbounded-production-x",1025);
		var y = new Variable("unbounded-production-y",1025);
		double[] costs = new double[1025*1025];
		Random random = new Random(81723);
		for(int cell=0; cell<costs.length; cell++)
			costs[cell] = 1+random.nextInt(1000);
		costs[costs.length-1] = 0;
		List<Variable> variables = List.of(x,y);
		List<Factor> factors = List.of(Factor.dense(variables,costs));
		Limits wide = new Limits(2_000_000,10_000_000);
		var problem = RegionalSearchProblem.generic(variables,factors);
		var result = IncrementalRegionalOptimizer.optimize(problem,problem.reducedRoot(wide),
			List.of(0,0),wide,IncrementalRegionalOptimizer.Options.configured(),ignored -> { });

		assertNotEquals("TIME",result.stopReason());
		assertEquals("EXACT",result.stopReason());
		assertEquals(0,result.upper(),0);
		assertTrue(result.checkpoints().stream().mapToLong(
			IncrementalRegionalOptimizer.Checkpoint::assignments).max().orElseThrow() > 1_000_000L);
	}
	@Test public void internalDecisionCountTracksCompleteBucketElimination() {
		var x=new Variable("x",2); var y=new Variable("y",2); var z=new Variable("z",2);
		var factors=List.of(Factor.dense(List.of(x,y),1,2,3,4),
			Factor.dense(List.of(y,z),1,3,4,2),Factor.dense(List.of(x,z),1,4,2,3));
		var result=run(List.of(x,y,z),factors,List.of(0,0,0),100000,1000000,0,false);
		// Initial triangle has no private axis. Each complete bucket removes one decision.
		assertEquals(List.of("BOOTSTRAP","INITIAL_BOUND","SEED_BOUNDARY","MERGE","MERGE","MERGE","EXACT"),
			result.checkpoints().stream().map(IncrementalRegionalOptimizer.Checkpoint::phase).toList());
		assertEquals(List.of(0,0,0,1,2,3,3),
			result.checkpoints().stream().map(IncrementalRegionalOptimizer.Checkpoint::internalDecisions).toList());
	}
	@Test public void exactLocalTiePreservesCanonicallyCheaperIncumbent() {
		var x = new Variable("stable-tie-x",2);
		double wide = 0x1p53;
		double tiny = 0x1p-53;
		List<Factor> factors = List.of(
			Factor.dense(List.of(x),wide,wide),
			Factor.dense(List.of(),tiny),
			Factor.dense(List.of(x),0,1),
			Factor.dense(List.of(),tiny),
			Factor.dense(List.of(x),1,0));
		var result = run(List.of(x),factors,List.of(1),100000,1000000,0,false);

		assertEquals("EXACT",result.stopReason());
		assertEquals(List.of(1),result.assignment());
		assertEquals(RegionalSearchProblem.evaluateFactors(List.of(x),factors,List.of(1)),
			result.upper(),0);
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
		assertEquals(3,result.upper(),0); assertTrue(result.checkpoints().stream()
			.anyMatch(checkpoint -> checkpoint.phase().equals("RESOURCE_COVER") && checkpoint.activeClusters()>0));
	}
	@Test public void tinyBudgetStillPublishesCompleteBorrowedCoverBound() {
		var x=new Variable("x",2);
		var result=run(List.of(x),List.of(Factor.dense(List.of(x),5,7)),List.of(0),10000,1,.05,true);
		assertEquals("TARGET_REACHED",result.stopReason());
		assertEquals(5,result.lower(),0); assertEquals(5,result.upper(),0);
		assertTrue(result.checkpoints().stream().allMatch(checkpoint -> checkpoint.retainedSlots()==0));
	}
	@Test public void borrowedDenseLeavesDoNotConsumeRegionalMessageBudget() {
		var x=new Variable("x",4);
		var factors=List.of(Factor.dense(List.of(x),100,0,50,50),
			Factor.dense(List.of(x),0,1,50,50));
		// The eight source-factor cells already belong to the compact model. The
		// regional budget of four slots is sufficient for the exact scalar merge.
		var result=run(List.of(x),factors,List.of(0),10000,4,0,false);
		audit(result,1);
		assertEquals("EXACT",result.stopReason());
		assertEquals(1,result.upper(),0);
		assertEquals(List.of(1),result.assignment());
		assertTrue(result.checkpoints().stream().allMatch(checkpoint -> checkpoint.retainedSlots()<=4));
	}
	@Test public void rejectedBoundaryUsesBoundedConditionalNeighborhoodForIncumbent() {
		var x=new Variable("conditional-x",2); var y=new Variable("conditional-y",2);
		var factors=List.of(Factor.dense(List.of(x,y),50,50,50,0),
			Factor.dense(List.of(x,y),50,50,50,0));
		// The persistent x->y or y->x boundary needs eight slots and cannot fit.
		// The complete two-variable neighborhood remains a bounded conditional solve.
		var result=run(List.of(x,y),factors,List.of(0,0),100,4,0,false);
		audit(result,0);
		assertEquals("RESOURCE",result.stopReason());
		assertEquals(0,result.upper(),0);
		assertEquals(List.of(1,1),result.assignment());
		assertTrue(result.checkpoints().stream().allMatch(checkpoint -> checkpoint.retainedSlots()<=4));
	}
	@Test public void conditionalNeighborhoodRanksOwnerCostPotentialBeforeTableSize() {
		var x=new Variable("low-x",2); var y=new Variable("low-y",2);
		var a=new Variable("high-a",3); var b=new Variable("high-b",3);
		var low=Factor.dense(List.of(x,y),1,1,1,0);
		var high=Factor.dense(List.of(a,b),100,100,100,100,100,100,100,100,0);
		var result=run(List.of(x,y,a,b),List.of(low,low,high,high),List.of(0,0,0,0),
			100,4,0,false,1);
		assertEquals("RESOURCE",result.stopReason());
		assertEquals("the sole conditional slot must target the larger feasible reduction",
			2,result.checkpoints().stream().filter(checkpoint -> checkpoint.phase().equals("SEED_BOUNDARY"))
				.findFirst().orElseThrow().upper(),0);
		// After seed improvement, resource fallback recollects the remaining low-cost region.
		assertEquals(0,result.upper(),0);
		assertEquals(List.of(1,1,2,2),result.assignment());
		assertEquals(1,result.checkpoints().stream()
			.filter(checkpoint -> checkpoint.phase().equals("CONDITIONAL")).count());
	}
	@Test public void unaryOwnerExpandsTwoOriginalInteractionRingsAndStops() {
		var x=new Variable("owner-x",2); var y=new Variable("boundary-y",2);
		var z=new Variable("second-ring-z",2); var q=new Variable("outside-q",2);
		var auxiliary=new Variable("encoded-aux",2);
		var factors=List.of(Factor.dense(List.of(x),5,0),
			Factor.dense(List.of(x,auxiliary),0,Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,0),
			Factor.dense(List.of(auxiliary,y),0,Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,0),
			Factor.dense(List.of(y,z),0,Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,0),
			Factor.dense(List.of(z,q),0,Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,0));
		assertEquals(List.of(0,1,2),IncrementalRegionalOptimizer.ownerOriginalClosureForTest(
			List.of(x,y,z,q,auxiliary),factors,4,0));
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
		// Dense leaves borrow the compact-model tables. The four merge-owned output
		// slots fit; optional diagnostic marginals must not reject that exact merge.
		var result=run(List.of(x),factors,List.of(0),100,4,0,false);
		audit(result,5); assertEquals("EXACT",result.stopReason()); assertEquals(5,result.upper(),0);
		for(var checkpoint : result.checkpoints()) assertTrue(checkpoint.retainedSlots()<=4);
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
