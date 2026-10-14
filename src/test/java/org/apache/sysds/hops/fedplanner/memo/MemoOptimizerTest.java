/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoOptimizerTest {
	@Test public void constantScopeReductionKeepsCostsAndConstraintsExactly() {
		int[] sizes = {2,1,2}; boolean[] coverage = {true,true,true};
		Check equality = new Check(0,new int[] {0,1,2}, r -> r[0] < 0 || r[2] < 0
			? Truth.UNKNOWN : r[1] == 0 && r[0] == r[2] ? Truth.YES : Truth.NO);
		CostTerm constant = new CostTerm(new int[] {1},r -> r[0] == 0 ? 7 : 100,7);
		CostTerm edge = new CostTerm(new int[] {2,1,0},r -> r[0] == 1 && r[2] == 1 ? 1 : 8,0);
		var result = new MemoOptimizer(sizes,coverage,new double[][] {{1,2},{3},{4,1}},
			List.of(equality),List.of(constant,edge),Limits.exactDefaults()).optimize();
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {1,0,1},result.selectedRules());
		Assert.assertEquals(0,result.cost().compareTo(BigDecimal.valueOf(14)));
		Check impossible = new Check(1,new int[] {1},r -> Truth.NO);
		Assert.assertEquals(Status.INFEASIBLE,new MemoOptimizer(sizes,coverage,
			new double[][] {{1,2},{3},{4,1}},List.of(impossible),List.of(constant),Limits.exactDefaults())
			.optimize().status());
	}

	@Test public void sharedProducerAndMovementHaveOneCostOwner() {
		// A's locally cheaper execution loses globally: both consumers share one upload.
		RuleMemo memo = universe(3, 2, true);
		double[][] nativeCosts = {{0,4}, {3,0}, {3,0}};
		CostTerm sharedUpload = new CostTerm(new int[] {0,1,2}, r ->
			r[0] == 0 && (r[1] == 1 || r[2] == 1) ? 5 : 0, 0);
		var result = solve(memo, nativeCosts, List.of(), List.of(sharedUpload), 4096, Long.MAX_VALUE);
		Assert.assertEquals(Status.OPTIMAL, result.status());
		Assert.assertArrayEquals(new int[] {1,1,1}, result.selectedRules());
		Assert.assertEquals(0, result.cost().compareTo(BigDecimal.valueOf(4)));
		// If A's FED execution costs 6, sharing the upload once beats computing A as FED.
		nativeCosts[0][1] = 6;
		result = solve(memo, nativeCosts, List.of(), List.of(sharedUpload), 0, Long.MAX_VALUE);
		Assert.assertArrayEquals(new int[] {0,1,1}, result.selectedRules());
		Assert.assertEquals(0, result.cost().compareTo(BigDecimal.valueOf(5)));
	}

	@Test public void arcConsistentOddCycleIsGloballyInfeasible() {
		List<Check> checks = List.of(different(0,1), different(1,2), different(2,0));
		var result = solve(universe(3,2,true), new double[3][2], checks, List.of(),4096,Long.MAX_VALUE);
		Assert.assertEquals(Status.INFEASIBLE, result.status());
		Assert.assertNull(result.selectedRules());
		Assert.assertTrue(result.statistics().nodes() > 1);
	}

	@Test public void unrelatedDomainsDoNotAppearInJointCheckScope() {
		Check correlated = new Check(8, new int[] {1,3}, r ->
			r[0] < 0 || r[1] < 0 ? Truth.UNKNOWN : r[0] == r[1] ? Truth.YES : Truth.NO);
		var result = solve(universe(5,2,true), new double[5][2], List.of(correlated),
			List.of(),4096,Long.MAX_VALUE);
		Assert.assertEquals(Status.OPTIMAL, result.status());
		Assert.assertTrue("only 3x3 projected partial/complete tuples exist",
			result.statistics().checkEvaluations() <= 9);
	}

	@Test public void unresolvedCoverageAndUnknownNeverBecomeInfeasibleOrOptimal() {
		var open=solve(universe(1,2,false),new double[][] {{0,1}},
			List.of(),List.of(),0,Long.MAX_VALUE);
		Assert.assertEquals(Status.UNRESOLVED,open.status());
		Assert.assertEquals("incomplete native coverage is not repaired by assignment enumeration",0,open.statistics().nodes());
		Check unknown = new Check(0,new int[] {0}, r -> Truth.UNKNOWN);
		var result = solve(universe(1,2,true),new double[][] {{0,1}},List.of(unknown),List.of(),0,Long.MAX_VALUE);
		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertNull(result.selectedRules());
	}

	@Test public void resourceLimitDoesNotCertifyAnIncumbent() {
		var result = solve(universe(3,2,true),new double[3][2],List.of(),List.of(),0,3);
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,result.status());
	}

	@Test public void exactBinary64SumsDistinguishPlansBeyondRoundedDoubleTotal() {
		var result = solve(universe(2,2,true),new double[][] {{1e16,1e16},{1,0}},
			List.of(),List.of(),4096,Long.MAX_VALUE);
		Assert.assertArrayEquals(new int[] {0,1},result.selectedRules());
	}

	@Test public void negativeOwnedCorrectionUsesCertifiedLowerBound() {
		CostTerm correction = new CostTerm(new int[] {0},r -> r[0] == 1 ? -20 : 0,-20);
		var result = solve(universe(1,2,true),new double[][] {{0,10}},List.of(),List.of(correction),4096,Long.MAX_VALUE);
		Assert.assertArrayEquals(new int[] {1},result.selectedRules());
		Assert.assertEquals(0,result.cost().compareTo(BigDecimal.valueOf(-10)));
	}

	@Test public void exhaustiveSmallFixturesPreserveFeasibilityCostAndTieBreakWithOrWithoutCache() {
		Random random = new Random(391);
		for(int fixture = 0; fixture < 80; fixture++) {
			int n = 4, k = 3;
			double[][] costs = new double[n][k];
			for(double[] row : costs) for(int r = 0; r < k; r++) row[r] = random.nextInt(9);
			boolean[][][] allowed = new boolean[4][k][k];
			for(boolean[][] table : allowed) for(boolean[] row : table)
				for(int j = 0; j < k; j++) row[j] = random.nextInt(4) != 0;
			int[][] scopes = {{0,1},{0,2},{1,3},{2,3}};
			List<Check> checks = new ArrayList<>();
			for(int i = 0; i < scopes.length; i++) {
				final int table = i;
				checks.add(new Check(i,scopes[i],r -> r[0] < 0 || r[1] < 0 ? Truth.UNKNOWN
					: allowed[table][r[0]][r[1]] ? Truth.YES : Truth.NO));
			}
			CostTerm shared = new CostTerm(new int[] {0,1,2},r ->
				r[0] != 0 && (r[1] == 0 || r[2] == 0) ? 7 : 0,0);
			BigDecimal best = null;
			int[] winner = null;
			for(int code = 0; code < 81; code++) {
				int[] a = {code/27, code/9%3, code/3%3, code%3};
				boolean legal = true;
				for(int i = 0; i < scopes.length; i++) legal &= allowed[i][a[scopes[i][0]]][a[scopes[i][1]]];
				if(!legal) continue;
				BigDecimal cost = BigDecimal.ZERO;
				for(int i = 0; i < n; i++) cost = cost.add(new BigDecimal(costs[i][a[i]]));
				if(a[0] != 0 && (a[1] == 0 || a[2] == 0)) cost = cost.add(BigDecimal.valueOf(7));
				if(best == null || cost.compareTo(best) < 0) { best = cost; winner = a; }
			}
			for(int cache : new int[] {0,4096}) {
				var result = solve(universe(n,k,true),costs,checks,List.of(shared),cache,Long.MAX_VALUE);
				Assert.assertEquals("fixture=" + fixture, best == null ? Status.INFEASIBLE : Status.OPTIMAL,result.status());
				Assert.assertArrayEquals("fixture=" + fixture,winner,result.selectedRules());
				if(best != null) Assert.assertEquals(0,best.compareTo(result.cost()));
			}
		}
	}

	@Test public void cyclicResidualWithTreeBranchMatchesExhaustiveOracleWithoutCache() {
		double[][] costs = {{3,0},{1,4},{2,0},{5,1},{0,2}};
		List<Check> checks = List.of(
			different(0,1), different(1,2),
			new Check(20,new int[] {2,0},r -> r[0] < 0 || r[1] < 0 ? Truth.UNKNOWN
				: r[0] == r[1] ? Truth.YES : Truth.NO),
			new Check(12,new int[] {0,1,2},r -> r[0] < 0 || r[1] < 0 || r[2] < 0 ? Truth.UNKNOWN
				: r[0]+r[1]+r[2] >= 1 ? Truth.YES : Truth.NO),
			new Check(23,new int[] {2,3},r -> r[0] < 0 || r[1] < 0 ? Truth.UNKNOWN
				: r[0] == r[1] ? Truth.YES : Truth.NO),
			different(3,4));
		List<CostTerm> terms = List.of(
			new CostTerm(new int[] {0,1,2},r -> r[0] == 1 ? 2 : 0,0),
			new CostTerm(new int[] {2,3},r -> r[0] == 1 && r[1] == 1 ? -2 : 3,-2),
			new CostTerm(new int[] {3,4},r -> r[0] == 0 ? 4 : 1,1));
		BigDecimal expected = null; int[] winner = null;
		for(int code=0; code<32; code++) {
			int[] a = {code>>4,code>>3&1,code>>2&1,code>>1&1,code&1};
			if(a[0]==a[1] || a[1]==a[2] || a[2]!=a[0] || a[0]+a[1]+a[2]<1
				|| a[2]!=a[3] || a[3]==a[4]) continue;
			BigDecimal value=BigDecimal.ZERO;
			for(int owner=0; owner<a.length; owner++) value=value.add(new BigDecimal(costs[owner][a[owner]]));
			value=value.add(BigDecimal.valueOf(a[0]==1 ? 2 : 0));
			value=value.add(BigDecimal.valueOf(a[2]==1 && a[3]==1 ? -2 : 3));
			value=value.add(BigDecimal.valueOf(a[3]==0 ? 4 : 1));
			if(expected==null || value.compareTo(expected)<0) { expected=value; winner=a; }
		}
		var result=solve(universe(5,2,true),costs,checks,terms,0,Long.MAX_VALUE);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(winner,result.selectedRules());
		Assert.assertEquals(0,expected.compareTo(result.cost()));

		List<Check> unknownBranch = new ArrayList<>(checks.subList(0,5));
		unknownBranch.add(new Check(34,new int[] {3,4},r -> Truth.UNKNOWN));
		var unresolved=solve(universe(5,2,true),costs,unknownBranch,terms,0,Long.MAX_VALUE);
		Assert.assertEquals(Status.UNRESOLVED,unresolved.status());
		Assert.assertNull(unresolved.selectedRules());
	}

	@Test public void residualCycleUsesIncrementalPropagationAndBoundsAfterTreeDP() {
		int core=24, n=core+2;
		double[][] costs=new double[n][2];
		for(double[] row : costs) row[1]=1;
		List<Check> checks=new ArrayList<>();
		for(int owner=0; owner<core; owner++) checks.add(new Check(owner,
			new int[] {owner,(owner+1)%core},rules -> rules[0]<0 || rules[1]<0 ? Truth.UNKNOWN
				: rules[0]==rules[1] ? Truth.YES : Truth.NO));
		checks.add(new Check(core,new int[] {0,core},rules -> rules[0]<0 || rules[1]<0 ? Truth.UNKNOWN
			: rules[0]==rules[1] ? Truth.YES : Truth.NO));
		checks.add(new Check(core+1,new int[] {core,core+1},rules -> rules[0]<0 || rules[1]<0 ? Truth.UNKNOWN
			: rules[0]==rules[1] ? Truth.YES : Truth.NO));
		var result=solve(universe(n,2,true),costs,checks,List.of(),4096,128);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[n],result.selectedRules());
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(result.cost()));
		Assert.assertTrue("core assignments must propagate, not enumerate 2^24 tuples",result.statistics().nodes()<128);
		Assert.assertTrue("the more expensive core is cut by its certified bound",result.statistics().boundPrunes()>0);
	}

	@Test public void lazyConditionalBoundsPreserveOptimumAcrossBudgetsAndBacktracking() {
		RuleMemo memo=universe(4,3,true);
		double[][] nativeCosts={{3,2,0},{1,4,2},{5,0,2},{1,3,0}};
		List<CostTerm> terms=List.of(
			new CostTerm(new int[] {0,1,2},r -> 10+7*Math.abs(r[0]-r[1])+3*Math.abs(r[1]-r[2]),0),
			new CostTerm(new int[] {1,2,3},r -> 5+4*Math.abs(r[0]-r[2])+r[1],0));
		BigDecimal best=null; int[] expected=null;
		for(int code=0; code<81; code++) {
			int[] a={code/27,code/9%3,code/3%3,code%3};
			double cost=10+7*Math.abs(a[0]-a[1])+3*Math.abs(a[1]-a[2])
				+5+4*Math.abs(a[1]-a[3])+a[2];
			for(int i=0;i<4;i++) cost+=nativeCosts[i][a[i]];
			BigDecimal exact=BigDecimal.valueOf(cost);
			if(best==null || exact.compareTo(best)<0) { best=exact; expected=a; }
		}
		for(int budget : new int[] {1,256}) for(int cache : new int[] {0,64}) {
			var result=new MemoOptimizer(memo,nativeCosts,List.of(),terms,new Limits(1000,budget,cache)).optimize();
			Assert.assertEquals(Status.OPTIMAL,result.status());
			Assert.assertArrayEquals(expected,result.selectedRules());
			Assert.assertEquals(0,best.compareTo(result.cost()));
		}
	}

	@Test public void incrementalGlobalBoundsAndLexicographicTiePruningAvoidFullRescans() {
		int owners=22;
		double[][] nativeCosts=new double[owners][2];
		List<CostTerm> terms=new ArrayList<>();
		for(int owner=0; owner<owners; owner++) {
			final int id=owner;
			terms.add(new CostTerm(new int[] {id},rules -> rules[0],0));
		}
		var result=solve(universe(owners,2,true),nativeCosts,List.of(),terms,0,64);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[owners],result.selectedRules());
		Assert.assertTrue("equal-cost suffixes are cut by the exact lexicographic floor",
			result.statistics().nodes()<64);
		Assert.assertTrue("only dirty cost terms are recomputed after remove/undo",
			result.statistics().conditionalBoundQueries()<owners*4L);
	}

	@Test public void reducedCoreBoundsReuseOnlyTermsIncidentToChangedDomains() {
		int core=10,owners=core+1;
		double[][] nativeCosts=new double[owners][2];
		List<CostTerm> terms=new ArrayList<>();
		for(int owner=0; owner<core; owner++) {
			int next=(owner+1)%core;
			terms.add(new CostTerm(new int[] {owner,next},rules -> 0,0));
		}
		// This leaf is eliminated into an exact boundary table; the ring remains the reduced core.
		terms.add(new CostTerm(new int[] {0,core},rules -> 0,0));
		var result=solve(universe(owners,2,true),nativeCosts,List.of(),terms,0,64);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[owners],result.selectedRules());
		Assert.assertTrue("reduced tie pruning must finish below the exhaustive 2^10 core",result.statistics().nodes()<64);
		Assert.assertTrue("reduced search refreshes incident ring terms instead of rescanning the ring",
			result.statistics().conditionalBoundQueries()<80);
	}

	@Test public void initialPropagationFoldsNonzeroSingletonOrdinalsWithoutChangingPredicatesOrCosts() {
		int[] sizes={3,2,2}; boolean[] coverage={true,true,true};
		Check forceNonzero=new Check(1,new int[] {0},rules -> rules[0]<0 ? Truth.UNKNOWN
			: rules[0]==2 ? Truth.YES : Truth.NO);
		Check equality=new Check(2,new int[] {0,1,2},rules -> rules[0]<0 || rules[1]<0 || rules[2]<0
			? Truth.UNKNOWN : rules[0]==2 && rules[1]==rules[2] ? Truth.YES : Truth.NO);
		CostTerm term=new CostTerm(new int[] {0,1,2},rules -> 10*rules[0]+rules[1]+rules[2],0);
		var result=new MemoOptimizer(sizes,coverage,new double[][] {{5,4,0},{0,3},{0,2}},
			List.of(forceNonzero,equality),List.of(term),Limits.exactDefaults()).optimize();
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[] {2,0,0},result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(20).compareTo(result.cost()));

		Check unresolvedFixed=new Check(3,new int[] {0},rules -> rules[0]<0 ? Truth.UNKNOWN : Truth.UNKNOWN);
		var unresolved=new MemoOptimizer(sizes,coverage,new double[][] {{5,4,0},{0,3},{0,2}},
			List.of(forceNonzero,unresolvedFixed),List.of(term),Limits.exactDefaults()).optimize();
		Assert.assertEquals("a fully fixed UNKNOWN check must remain unresolved after scope folding",
			Status.UNRESOLVED,unresolved.status());
	}

	@Test public void reducedSearchRetainsBestIncumbentWhenNodeBudgetExpires() {
		int core=8,owners=core+1;
		List<CostTerm> terms=new ArrayList<>();
		for(int owner=0; owner<core; owner++)
			terms.add(new CostTerm(new int[] {owner,(owner+1)%core},rules -> rules[0]==rules[1] ? 0 : 1,0));
		terms.add(new CostTerm(new int[] {0,core},rules -> 0,0));
		var result=solve(universe(owners,2,true),new double[owners][2],List.of(),terms,0,12);
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,result.status());
		Assert.assertNotNull("the exact best plan visited before exhaustion remains diagnostic evidence",result.selectedRules());
		Assert.assertNotNull(result.cost());
	}

	@Test public void sameScopeCheckConjunctionRejectsDisjointSupportWitnesses() {
		Check equal=new Check(1,new int[] {0,1},rules -> rules[0]<0 || rules[1]<0 ? Truth.UNKNOWN
			: rules[0]==rules[1] ? Truth.YES : Truth.NO);
		Check different=new Check(2,new int[] {0,1},rules -> rules[0]<0 || rules[1]<0 ? Truth.UNKNOWN
			: rules[0]!=rules[1] ? Truth.YES : Truth.NO);
		var result=solve(universe(2,2,true),new double[2][2],List.of(equal,different),List.of(),0,64);
		Assert.assertEquals(Status.INFEASIBLE,result.status());
		Assert.assertNull(result.selectedRules());
	}

	@Test public void constantAndConflictingUnaryCostsFoldExactlyIntoNativeVectors() {
		List<CostTerm> costs=List.of(
			new CostTerm(new int[] {0},rules -> rules[0]==0 ? 0 : 100,0),
			new CostTerm(new int[] {0},rules -> rules[0]==0 ? 100 : 0,0),
			new CostTerm(new int[0],rules -> 3,3),
			new CostTerm(new int[0],rules -> -2,-2));
		var result=solve(universe(1,2,true),new double[1][2],List.of(),costs,0,64);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals("equal folded costs retain deterministic lexicographic selection",new int[] {0},
			result.selectedRules());
		Assert.assertEquals(0,BigDecimal.valueOf(101).compareTo(result.cost()));
	}

	@Test public void groupedCostsPreserveEachBinary64TermBeforeBigDecimalSummation() {
		List<CostTerm> costs=List.of(
			new CostTerm(new int[0],rules -> 1e16,1e16),
			new CostTerm(new int[0],rules -> 1,1),
			new CostTerm(new int[0],rules -> -1e16,-1e16));
		var result=solve(universe(1,1,true),new double[1][1],List.of(),costs,0,64);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertEquals("summing doubles first would incorrectly erase the unit term",0,
			BigDecimal.ONE.compareTo(result.cost()));
	}

	@Test public void branchingConnectsOverlappingCostsBeforeIndependentSmallDomains() {
		int spokes=12;
		int[] sizes=new int[spokes+1]; sizes[0]=3; Arrays.fill(sizes,1,sizes.length,2);
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		double[][] nativeCosts=new double[sizes.length][];
		for(int owner=0; owner<sizes.length; owner++) nativeCosts[owner]=new double[sizes[owner]];
		nativeCosts[0][1]=1; nativeCosts[0][2]=2;
		List<CostTerm> terms=new ArrayList<>();
		for(int spoke=1; spoke<=spokes; spoke++) {
			int next=spoke==spokes ? 1 : spoke+1;
			terms.add(new CostTerm(new int[] {0,spoke,next},rules -> rules[0]==0 ? 100 : 0,0));
		}
		for(int cache : new int[] {0,4096}) {
			var result=new MemoOptimizer(sizes,coverage,nativeCosts,List.of(),terms,
				new Limits(64,256,cache)).optimize();
			Assert.assertEquals(Status.OPTIMAL,result.status());
			Assert.assertEquals(0,BigDecimal.ONE.compareTo(result.cost()));
			Assert.assertEquals(1,result.selectedRules()[0]);
			Assert.assertTrue("the shared high-connectivity owner exposes every movement bound before the budget",
				result.statistics().nodes()<64);
		}
	}
	@Test public void fixedUnknownCannotBeResolvedBySearchingUnrelatedChoices() {
		Check fixedUnknown=new Check(0,new int[0],rules -> Truth.UNKNOWN);
		var result=solve(universe(20,2,true),new double[20][2],List.of(fixedUnknown),List.of(),4096,64);
		Assert.assertEquals("an immutable empty-scope obligation cannot be resolved by any assignment",
			Status.UNRESOLVED,result.status());
		Assert.assertEquals("do not enumerate unrelated assignments for a fixed analysis gap",0,
			result.statistics().nodes());
		Assert.assertNull(result.selectedRules());
	}

	@Test public void wideLazyCostRetainsTheExactOptimum() {
		// Width > 8 is not an unsupported model: the wide conditional bound
		// may be relaxed, but complete assignments still pay the exact cost.
		int owners=10;
		double[][] costs=new double[owners][2];
		for(double[] row:costs) row[1]=1;
		int[] scope=java.util.stream.IntStream.range(0,owners).toArray();
		CostTerm wide=new CostTerm(scope,rules -> Arrays.stream(rules).sum()==0 ? 100 : 0,0);
		int[] optimum=new int[owners]; optimum[owners-1]=1;
		for(int cache:new int[] {0,4096}) {
			var result=solve(universe(owners,2,true),costs,List.of(),List.of(wide),cache,10000);
			Assert.assertEquals(Status.OPTIMAL,result.status());
			Assert.assertEquals(0,BigDecimal.ONE.compareTo(result.cost()));
			Assert.assertArrayEquals(optimum,result.selectedRules());
			Assert.assertTrue(result.statistics().wideBoundRelaxations()>0);
		}
	}

	private static Check different(int a,int b) {
		return new Check(a*10+b,new int[] {a,b},r -> r[0] < 0 || r[1] < 0 ? Truth.UNKNOWN
			: r[0] != r[1] ? Truth.YES : Truth.NO);
	}
	private static MemoOptimizer.Result solve(RuleMemo memo,double[][] costs,List<Check> checks,
		List<CostTerm> terms,int cache,long nodes) {
		return new MemoOptimizer(memo,costs,checks,terms,new Limits(nodes,256,cache)).optimize();
	}
	private static RuleMemo universe(int owners,int choices,boolean complete) {
		List<List<RuleMemo.Rule>> domains = new ArrayList<>();
		for(int owner = 0; owner < owners; owner++) {
			List<RuleMemo.Rule> rules = new ArrayList<>();
			for(int r = 0; r < choices; r++) rules.add(new RuleMemo.Rule(ExecType.CP,List.of(),
				List.of(MemoPartition.LOCAL),"rule-" + r));
			domains.add(rules);
		}
		boolean[] coverage = new boolean[owners]; Arrays.fill(coverage,complete);
		return new RuleMemo(domains,coverage);
	}
}
