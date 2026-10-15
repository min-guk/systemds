/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

/** Independent exhaustive reference checks for the Tree-DP optimizer. */
public class MemoTreeDpOptimizerTest {
	private record Problem(int[] sizes,boolean[] coverage,double[][] nativeCosts,
		List<Check> checks,List<CostTerm> terms) {
		private Problem {
			sizes=sizes.clone(); coverage=coverage.clone();
			nativeCosts=Arrays.stream(nativeCosts).map(double[]::clone).toArray(double[][]::new);
			checks=List.copyOf(checks); terms=List.copyOf(terms);
		}
	}
	private record Oracle(BigDecimal cost,int[] selected,long feasible) {
		private Oracle { selected=selected==null ? null : selected.clone(); }
	}

	@Test public void globalMatchesIndependentEnumerationOnRandomConflictGraphs() {
		for(int seed=0;seed<40;seed++) {
			Problem problem=randomProblem(seed);
			Oracle oracle=enumerate(problem);
			Assert.assertNotNull("seed="+seed,oracle.cost());
			MemoTreeDpOptimizer.Result result=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
				MemoTreeDpOptimizer.Limits.defaults());
			assertCertifiedAgainstOracle("seed="+seed,problem,oracle,result,true);
		}
	}

	@Test public void resolvesMultiParentSharedMovementCycleAndWideFactor() {
		int[] sizes={3,2,3,2,2,3};
		double[][] nativeCosts={{0.5,0.25,1},{0,0.75},{0.25,0,0.5},{1,0},{0,0.25},{0.5,0.25,0}};
		List<Check> checks=List.of(
			new Check(10,new int[] {0,1,2},r -> r[0]==2 && r[1]==0 && r[2]==1 ? Truth.NO : Truth.YES),
			new Check(11,new int[] {2,3},r -> r[0]==0 && r[1]==1 ? Truth.NO : Truth.YES),
			new Check(12,new int[] {3,4,0},r -> r[0]==0 && r[1]==1 && r[2]==1 ? Truth.NO : Truth.YES),
			new Check(13,new int[] {4,5},r -> r[0]==0 && r[1]==2 ? Truth.NO : Truth.YES));
		List<CostTerm> terms=List.of(
			// One movement paid once although owners 0 and 2 feed several consumers.
			new CostTerm(new int[] {0,2,3},r -> r[0]==r[1] ? 0.125 : 2.125,0.125),
			new CostTerm(new int[] {0,1,2,4,5},r ->
				(r[0]+r[2]+r[4])%3==r[3] ? 0.0625 : 1.0625,0.0625),
			new CostTerm(new int[] {5,1},r -> r[0]==2*r[1] ? -0.25 : 0.5,-0.25));
		Problem problem=new Problem(sizes,allCovered(sizes.length),nativeCosts,checks,terms);
		assertCertifiedAgainstOracle("adversarial",problem,enumerate(problem),
			solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,MemoTreeDpOptimizer.Limits.defaults()),true);
	}

	@Test public void localCertificatesAreWithinFivePercentOfIndependentOptimum() {
		for(int seed=100;seed<130;seed++) {
			Problem problem=positiveRandomProblem(seed);
			Oracle oracle=enumerate(problem);
			MemoTreeDpOptimizer.Result result=solve(problem,MemoTreeDpOptimizer.Mode.LOCAL,
				MemoTreeDpOptimizer.Limits.defaults());
			assertCertifiedAgainstOracle("local seed="+seed,problem,oracle,result,
				result.status()==MemoTreeDpOptimizer.Status.OPTIMAL);
			Assert.assertTrue(result.status()==MemoTreeDpOptimizer.Status.OPTIMAL
				|| result.status()==MemoTreeDpOptimizer.Status.EPSILON_OPTIMAL);
			if(result.status()==MemoTreeDpOptimizer.Status.EPSILON_OPTIMAL) {
				Assert.assertTrue("epsilon status requires positive LB",result.lowerBound().signum()>0);
				Assert.assertTrue(relativeGap(result.upperBound(),result.lowerBound())
					.compareTo(new BigDecimal("0.05"))<=0);
			}
			Assert.assertTrue("actual approximation seed="+seed,
				result.upperBound().compareTo(oracle.cost().multiply(new BigDecimal("1.05")))<=0);
		}
	}

	@Test public void localStopsAtFivePercentButNotJustOutsideIt() {
		MemoTreeDpOptimizer.Result boundary=solve(epsilonCycle(5),MemoTreeDpOptimizer.Mode.LOCAL,
			MemoTreeDpOptimizer.Limits.defaults());
		Assert.assertEquals(MemoTreeDpOptimizer.Status.EPSILON_OPTIMAL,boundary.status());
		Assert.assertEquals(0,new BigDecimal("100").compareTo(boundary.lowerBound()));
		Assert.assertEquals(0,new BigDecimal("105").compareTo(boundary.upperBound()));
		Assert.assertEquals(0,new BigDecimal("0.05").compareTo(
			relativeGap(boundary.upperBound(),boundary.lowerBound())));

		Problem outside=epsilonCycle(5.0001);
		MemoTreeDpOptimizer.Result resolved=solve(outside,MemoTreeDpOptimizer.Mode.LOCAL,
			MemoTreeDpOptimizer.Limits.defaults());
		assertCertifiedAgainstOracle("outside epsilon boundary",outside,enumerate(outside),resolved,true);
	}

	@Test public void zeroNegativeAndDyadicCostsStayExact() {
		List<Problem> problems=List.of(
			new Problem(new int[] {2,2},allCovered(2),new double[][] {{0,0},{0,0}},List.of(),List.of()),
			new Problem(new int[] {2,2,2},allCovered(3),
				new double[][] {{-0.5,0},{0.125,-0.25},{0.0625,0.5}},
				List.of(new Check(1,new int[] {0,1,2},r -> r[0]+r[1]+r[2]==3 ? Truth.NO : Truth.YES)),
				List.of(new CostTerm(new int[] {0,2},r -> r[0]==r[1] ? -0.125 : 0.25,-0.125))),
			new Problem(new int[] {3,3},allCovered(2),
				new double[][] {{0.03125,0.0625,0.125},{0.5,0.25,0.125}},List.of(),
				List.of(new CostTerm(new int[] {0,1},r -> (r[0]+r[1])*0.015625,0))));
		for(Problem problem:problems) {
			Oracle oracle=enumerate(problem);
			for(MemoTreeDpOptimizer.Mode mode:MemoTreeDpOptimizer.Mode.values())
				assertCertifiedAgainstOracle(mode.toString(),problem,oracle,
					solve(problem,mode,MemoTreeDpOptimizer.Limits.defaults()),true);
		}
	}

	@Test public void reportsOpenUnknownAndInfeasibleModelsWithoutFalseCertificate() {
		Problem open=new Problem(new int[] {2},new boolean[] {false},new double[][] {{0,1}},List.of(),List.of());
		assertUncertified(MemoTreeDpOptimizer.Status.UNRESOLVED,
			solve(open,MemoTreeDpOptimizer.Mode.GLOBAL,MemoTreeDpOptimizer.Limits.defaults()));

		Problem unknown=new Problem(new int[] {2},allCovered(1),new double[][] {{0,1}},
			List.of(new Check(1,new int[] {0},r -> Truth.UNKNOWN)),List.of());
		assertUncertified(MemoTreeDpOptimizer.Status.UNRESOLVED,
			solve(unknown,MemoTreeDpOptimizer.Mode.LOCAL,MemoTreeDpOptimizer.Limits.defaults()));

		Problem infeasible=new Problem(new int[] {2,2},allCovered(2),new double[][] {{0,1},{0,1}},
			List.of(new Check(1,new int[] {0,1},r -> Truth.NO)),List.of());
		MemoTreeDpOptimizer.Result result=solve(infeasible,MemoTreeDpOptimizer.Mode.GLOBAL,
			MemoTreeDpOptimizer.Limits.defaults());
		Assert.assertEquals(MemoTreeDpOptimizer.Status.INFEASIBLE,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertNull(result.selectedRules());
	}

	@Test public void fixedUnknownCannotHideAResolvableLaterConflict() {
		Problem problem=new Problem(new int[] {2,2,2},allCovered(3),
			new double[][] {{0,1},{0,1},{0,1}},
			List.of(
				new Check(1,new int[0],r -> Truth.UNKNOWN),
				new Check(2,new int[] {0,1,2},r ->
					r[0]==0 && r[1]==0 && r[2]==0 ? Truth.NO : Truth.YES)),List.of());
		MemoTreeDpOptimizer.Result result=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			MemoTreeDpOptimizer.Limits.defaults());
		Assert.assertEquals(MemoTreeDpOptimizer.Status.UNRESOLVED,result.status());
		Assert.assertFalse(result.certified());
	}

	@Test public void resourceExhaustionIsExplicitAndNeverCertified() {
		Problem problem=epsilonCycle(5.0001);
		MemoTreeDpOptimizer.Result result=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(1,1,0,1));
		Assert.assertEquals(MemoTreeDpOptimizer.Status.RESOURCE_EXHAUSTED,result.status());
		Assert.assertFalse(result.certified());
		Oracle oracle=enumerate(problem);
		Assert.assertTrue(result.lowerBound().compareTo(oracle.cost())<=0);
		Assert.assertTrue(oracle.cost().compareTo(result.upperBound())<=0);
		Assert.assertEquals(0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
	}

	@Test public void oversizedBinaryFactorIsDeferredWithinCellBudget() {
		double[] wideNative=new double[32];
		for(int rule=0;rule<wideNative.length;rule++) wideNative[rule]=rule/32.0;
		Problem problem=new Problem(new int[] {32,2},allCovered(2),
			new double[][] {wideNative,{0,0.125}},
			List.of(new Check(1,new int[] {0,1},r ->
				r[0]==31 && r[1]==1 ? Truth.NO : Truth.YES)),
			List.of(new CostTerm(new int[] {0,1},r ->
				r[0]==31 && r[1]==1 ? 0 : 10,0)));
		Oracle oracle=enumerate(problem);
		MemoTreeDpOptimizer.Result deferred=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,4,4096,10_000));
		assertCertifiedAgainstOracle("deferred wide binary",problem,oracle,deferred,true);
		Assert.assertEquals(0,deferred.statistics().retainedChecks());
		Assert.assertEquals(0,deferred.statistics().retainedCosts());
		Assert.assertEquals(1,deferred.statistics().deferredChecks());
		Assert.assertEquals(1,deferred.statistics().deferredCosts());

		MemoTreeDpOptimizer.Result retained=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,64,4096,10_000));
		assertCertifiedAgainstOracle("retained wide binary",problem,oracle,retained,true);
		Assert.assertEquals(1,retained.statistics().retainedChecks());
		Assert.assertEquals(1,retained.statistics().retainedCosts());

		MemoTreeDpOptimizer.Result exhausted=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(1,4,0,1));
		Assert.assertEquals(MemoTreeDpOptimizer.Status.RESOURCE_EXHAUSTED,exhausted.status());
		Assert.assertFalse(exhausted.certified());
	}

	@Test public void certifiedCostClassesPreserveExactAndLocalResultsAcrossCacheBudgets() {
		int[][] classes={{0,0,1,1},{0,1,0,1},{0,0}};
		CostTerm classified=new CostTerm(new int[] {0,1,2},r ->
			(r[0]>=2 ? 10 : 0)+(r[1]%2),0,classes);
		Problem problem=new Problem(new int[] {4,4,2},allCovered(3),
			new double[][] {{5,4,0,1},{0,1,2,3},{0,0.25}},List.of(),List.of(classified));
		Oracle oracle=enumerate(problem);
		Assert.assertEquals("conditioned optimum excludes ordinal 0, the first class representative",
			1,oracle.selected()[0]);
		for(MemoTreeDpOptimizer.Mode mode:MemoTreeDpOptimizer.Mode.values())
			for(int cacheEntries:new int[] {0,1,4096}) {
				MemoTreeDpOptimizer.Result result=solve(problem,mode,
					new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,4,cacheEntries,10_000));
				assertCertifiedAgainstOracle(mode+" cache="+cacheEntries,problem,oracle,result,true);
				Assert.assertEquals("cost classes must not replace the selected native ordinal",
					1,result.selectedRules()[0]);
			}
		Problem plain=new Problem(problem.sizes(),problem.coverage(),problem.nativeCosts(),List.of(),
			List.of(new CostTerm(new int[] {0,1,2},r ->
				(r[0]>=2 ? 10 : 0)+(r[1]%2),0)));
		MemoTreeDpOptimizer.Result classifiedEnumeration=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,32,0,10_000));
		MemoTreeDpOptimizer.Result plainEnumeration=solve(plain,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,32,0,10_000));
		Assert.assertArrayEquals(plainEnumeration.selectedRules(),classifiedEnumeration.selectedRules());
		Assert.assertTrue("certified classes should enumerate fewer conditional cells",
			classifiedEnumeration.statistics().conditionalEnumeratedCells()
				< plainEnumeration.statistics().conditionalEnumeratedCells());
	}

	@Test public void infeasibleWitnessValidationDoesNotEvaluateDeferredCosts() {
		AtomicInteger costCalls=new AtomicInteger();
		Problem problem=new Problem(new int[] {2,2,2},allCovered(3),
			new double[][] {{0,1},{0,1},{0,1}},
			List.of(new Check(1,new int[] {0,1,2},r -> Truth.NO)),
			List.of(new CostTerm(new int[] {0,1,2},r -> {
				costCalls.incrementAndGet(); return r[0]+r[1]+r[2];
			},0)));
		MemoTreeDpOptimizer.Result result=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(1,1,4096,10_000));
		Assert.assertEquals(MemoTreeDpOptimizer.Status.RESOURCE_EXHAUSTED,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertEquals("a NO witness has no feasible-plan cost to validate",0,costCalls.get());
	}

	@Test public void conditionalCachesDistinguishCompactAndWideDomainMasks() {
		Problem compact=new Problem(new int[] {4,4,2},allCovered(3),
			new double[][] {{5,4,0,1},{0,1,2,3},{0,0.25}},List.of(),
			List.of(new CostTerm(new int[] {0,1,2},r ->
				(r[0]>=2 ? 10 : 0)+(r[1]%2),0,new int[][] {{0,0,1,1},{0,1,0,1},{0,0}})));
		double[] wideNative=new double[65];
		Arrays.fill(wideNative,100);
		wideNative[0]=0; wideNative[64]=1;
		Problem wide=new Problem(new int[] {65,2,2},allCovered(3),
			new double[][] {wideNative,{0,0.125},{0,0.25}},List.of(),
			List.of(new CostTerm(new int[] {0,1,2},r ->
				r[0]==64 && r[1]==1 ? 0 : 10,0)));
		for(Problem problem:List.of(compact,wide)) {
			Oracle oracle=enumerate(problem);
			MemoTreeDpOptimizer.Result uncached=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
				new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,512,0,10_000));
			MemoTreeDpOptimizer.Result cached=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
				new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,512,4096,10_000));
			assertCertifiedAgainstOracle("uncached domain key",problem,oracle,uncached,true);
			assertCertifiedAgainstOracle("cached domain key",problem,oracle,cached,true);
			Assert.assertEquals(0,uncached.upperBound().compareTo(cached.upperBound()));
			Assert.assertTrue("cached run should reuse exact tuples or conditional domains",
				cached.statistics().exactCostCacheHits()>0
					|| cached.statistics().conditionalCostCacheHits()>0);
		}
	}

	@Test public void resultSnapshotsAreImmutableAndCacheDoesNotChangeAnswer() {
		Problem problem=new Problem(new int[] {2,2,2,2},allCovered(4),
			new double[][] {{10,10},{0,0},{0,0},{0.25,0}},List.of(),List.of(
				new CostTerm(new int[] {0,1},r -> 0,0),
				new CostTerm(new int[] {1,2},r -> 0,0),
				new CostTerm(new int[] {2,0},r -> r[0]==r[1] ? 2 : 0,0)));
		Oracle oracle=enumerate(problem);
		MemoTreeDpOptimizer.Result uncached=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			new MemoTreeDpOptimizer.Limits(Long.MAX_VALUE,Integer.MAX_VALUE,0,Integer.MAX_VALUE));
		MemoTreeDpOptimizer.Result cached=solve(problem,MemoTreeDpOptimizer.Mode.GLOBAL,
			MemoTreeDpOptimizer.Limits.defaults());
		assertCertifiedAgainstOracle("uncached",problem,oracle,uncached,true);
		assertCertifiedAgainstOracle("cached",problem,oracle,cached,true);
		Assert.assertTrue("unaffected component should reuse its DP message",
			cached.statistics().cacheHits()>0);
		int[] selected=cached.selectedRules();
		selected[0]=(selected[0]+1)%problem.sizes()[0];
		Assert.assertFalse(Arrays.equals(selected,cached.selectedRules()));
		Assert.assertThrows(UnsupportedOperationException.class,() -> cached.progression().clear());
	}

	private static MemoTreeDpOptimizer.Result solve(Problem problem,MemoTreeDpOptimizer.Mode mode,
		MemoTreeDpOptimizer.Limits limits) {
		return new MemoTreeDpOptimizer(problem.sizes(),problem.coverage(),problem.nativeCosts(),
			problem.checks(),problem.terms(),mode,limits).optimize();
	}

	private static void assertCertifiedAgainstOracle(String label,Problem problem,Oracle oracle,
		MemoTreeDpOptimizer.Result result,boolean exact) {
		Assert.assertTrue(label+" status="+result.status(),result.certified());
		Assert.assertNotNull(label,result.selectedRules());
		Assert.assertEquals(label,0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
		Assert.assertTrue(label+" LB<=OPT",result.lowerBound().compareTo(oracle.cost())<=0);
		Assert.assertTrue(label+" OPT<=UB",oracle.cost().compareTo(result.upperBound())<=0);
		if(exact) {
			Assert.assertEquals(label,MemoTreeDpOptimizer.Status.OPTIMAL,result.status());
			Assert.assertEquals(label,0,oracle.cost().compareTo(result.upperBound()));
			Assert.assertEquals(label,0,result.lowerBound().compareTo(result.upperBound()));
		}
		BigDecimal previousLower=null,previousUpper=null;
		boolean feasibleUpperSeen=false;
		for(MemoTreeDpOptimizer.BoundPoint point:result.progression()) {
			Assert.assertTrue(label+" trace LB<=OPT",point.lowerBound().compareTo(oracle.cost())<=0);
			if(point.upperBound()==null)
				Assert.assertFalse(label+" trace cannot lose a feasible UB",feasibleUpperSeen);
			else {
				feasibleUpperSeen=true;
				Assert.assertTrue(label+" trace OPT<=UB",oracle.cost().compareTo(point.upperBound())<=0);
			}
			if(previousLower!=null) {
				Assert.assertTrue(label+" monotone LB",previousLower.compareTo(point.lowerBound())<=0);
				if(previousUpper!=null && point.upperBound()!=null)
					Assert.assertTrue(label+" monotone UB",previousUpper.compareTo(point.upperBound())>=0);
			}
			previousLower=point.lowerBound();
			if(point.upperBound()!=null) previousUpper=point.upperBound();
		}
	}

	private static void assertUncertified(MemoTreeDpOptimizer.Status expected,MemoTreeDpOptimizer.Result result) {
		Assert.assertEquals(expected,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertNull(result.selectedRules());
	}

	private static Oracle enumerate(Problem problem) {
		long combinations=1;
		for(int size:problem.sizes()) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("test oracle unexpectedly large: "+combinations,combinations<=1_000_000);
		BigDecimal best=null; int[] winner=null; long feasible=0;
		int[] selected=new int[problem.sizes().length];
		for(long ordinal=0;ordinal<combinations;ordinal++) {
			boolean legal=true;
			for(Check check:problem.checks()) {
				int[] owners=check.owners(),tuple=new int[owners.length];
				for(int i=0;i<owners.length;i++) tuple[i]=selected[owners[i]];
				Truth truth=check.evaluate(tuple);
				Assert.assertNotEquals("supported exhaustive fixture returned UNKNOWN",Truth.UNKNOWN,truth);
				if(truth==Truth.NO) { legal=false; break; }
			}
			if(legal) {
				feasible++;
				BigDecimal cost=evaluate(problem,selected);
				if(best==null || cost.compareTo(best)<0) { best=cost; winner=selected.clone(); }
			}
			increment(selected,problem.sizes());
		}
		return new Oracle(best,winner,feasible);
	}

	private static BigDecimal evaluate(Problem problem,int[] selected) {
		BigDecimal cost=BigDecimal.ZERO;
		for(int owner=0;owner<selected.length;owner++)
			cost=cost.add(BigDecimal.valueOf(problem.nativeCosts()[owner][selected[owner]]));
		for(CostTerm term:problem.terms()) {
			int[] owners=term.owners(),tuple=new int[owners.length];
			for(int i=0;i<owners.length;i++) tuple[i]=selected[owners[i]];
			cost=cost.add(term.evaluateExact(tuple));
		}
		return cost;
	}

	private static void increment(int[] selected,int[] sizes) {
		for(int i=selected.length-1;i>=0;i--) {
			if(++selected[i]<sizes[i]) return;
			selected[i]=0;
		}
	}

	private static Problem randomProblem(int seed) {
		Random random=new Random(seed);
		int owners=5+seed%2;
		int[] sizes=new int[owners];
		double[][] nativeCosts=new double[owners][];
		for(int owner=0;owner<owners;owner++) {
			sizes[owner]=2+random.nextInt(2);
			nativeCosts[owner]=new double[sizes[owner]];
			for(int rule=0;rule<sizes[owner];rule++) nativeCosts[owner][rule]=(random.nextInt(17)-4)/8.0;
		}
		List<Check> checks=new ArrayList<>();
		for(int owner=0;owner<owners;owner++) {
			int next=(owner+1)%owners,forbidden=random.nextInt(3);
			checks.add(new Check(100+owner,new int[] {owner,next},r ->
				r[0]+2*r[1]==forbidden && r[0]+r[1]>0 ? Truth.NO : Truth.YES));
		}
		checks.add(new Check(200,new int[] {0,2,owners-1},r ->
			r[0]>0 && r[1]>0 && r[2]>0 && (r[0]+r[1]+r[2])%2==0 ? Truth.NO : Truth.YES));
		List<CostTerm> terms=List.of(
			new CostTerm(new int[] {0,2},r -> r[0]==r[1] ? -0.25 : 0.5,-0.25),
			new CostTerm(new int[] {0,1,3},r -> (r[0]+2*r[1]+r[2])/16.0,0),
			new CostTerm(new int[] {2,owners-1},r -> r[0]==0 && r[1]==0 ? 0 : 0.375,0));
		return new Problem(sizes,allCovered(owners),nativeCosts,checks,terms);
	}

	private static Problem positiveRandomProblem(int seed) {
		Problem raw=randomProblem(seed);
		double[][] positive=Arrays.stream(raw.nativeCosts()).map(row -> Arrays.stream(row)
			.map(value -> value+4).toArray()).toArray(double[][]::new);
		List<CostTerm> terms=List.of(
			new CostTerm(new int[] {0,2},r -> r[0]==r[1] ? 0.125 : 1.125,0.125),
			new CostTerm(new int[] {0,1,3},r -> 0.25+(r[0]+2*r[1]+r[2])/16.0,0.25),
			new CostTerm(new int[] {2,raw.sizes().length-1},r -> r[0]==0 && r[1]==0 ? 0.5 : 0.875,0.5));
		return new Problem(raw.sizes(),raw.coverage(),positive,raw.checks(),terms);
	}

	private static Problem epsilonCycle(double penalty) {
		return new Problem(new int[] {2,2,2},allCovered(3),
			new double[][] {{100,100},{0,0},{0,0}},List.of(),List.of(
				new CostTerm(new int[] {0,1},r -> 0,0),
				new CostTerm(new int[] {1,2},r -> 0,0),
				new CostTerm(new int[] {2,0},r -> r[0]==0 && r[1]==0 ? penalty : 0,0)));
	}

	private static boolean[] allCovered(int owners) {
		boolean[] result=new boolean[owners]; Arrays.fill(result,true); return result;
	}

	private static BigDecimal relativeGap(BigDecimal upper,BigDecimal lower) {
		return upper.subtract(lower).divide(lower,20,RoundingMode.HALF_EVEN);
	}
}
