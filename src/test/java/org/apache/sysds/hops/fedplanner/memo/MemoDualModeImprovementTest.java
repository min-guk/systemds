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
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Mode;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Options;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

/** End-to-end oracle tests for clustered bounds and optional seed improvement. */
public class MemoDualModeImprovementTest {
	private static final BigDecimal EPSILON_FACTOR=new BigDecimal("1.05");

	@Test public void budgetVariedClustersMatchRandomSignedOverlappingFactorOracles() {
		for(int seed=0;seed<20;seed++) {
			Problem problem=randomProblem(seed);
			Oracle oracle=enumerate(problem);
			Assert.assertNotNull("seed="+seed,oracle.cost());
			for(int cells:new int[] {0,8,16,64}) {
				Options options=Options.defaults().withDirectExact(false).withSeedWorkLimit(0)
					.withImproveSeed(false).withClusterCells(cells).withTrace(true);
				Result global=solve(problem,Mode.GLOBAL,options);
				assertExact(problem,oracle,global);
				assertProgression(problem,oracle,global);

				Result local=solve(problem,Mode.LOCAL,options);
				Assert.assertTrue("seed="+seed+" cells="+cells+" status="+local.status(),local.certified());
				assertProgression(problem,oracle,local);
				Assert.assertTrue(oracle.cost().compareTo(local.upperBound())<=0);
				Assert.assertEquals(0,evaluate(problem,local.selectedRules()).compareTo(local.upperBound()));
				if(local.status()==Status.EPSILON_OPTIMAL) {
					Assert.assertTrue(local.lowerBound().signum()>0);
					Assert.assertTrue(local.upperBound().compareTo(local.lowerBound().multiply(EPSILON_FACTOR))<=0);
					Assert.assertTrue(local.upperBound().compareTo(oracle.cost().multiply(EPSILON_FACTOR))<=0);
				}
				else {
					Assert.assertEquals(Status.OPTIMAL,local.status());
					Assert.assertEquals(0,oracle.cost().compareTo(local.upperBound()));
					Assert.assertEquals(0,local.lowerBound().compareTo(local.upperBound()));
				}
			}
		}
	}

	@Test public void clusteredSearchRaisesTheRootLowerBoundAndPreservesCanonicalExactResult() {
		Problem problem=new Problem(new int[] {2,2},new double[][] {{0,0},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==r[1] ? 0 : 10,0),
			new CostTerm(new int[] {0,1},r -> r[0]!=r[1] ? 0 : 10,0)));
		Oracle oracle=enumerate(problem);
		Options base=Options.defaults().withDirectExact(false).withSeedWorkLimit(0)
			.withImproveSeed(false).withTrace(true);
		Result independent=solve(problem,Mode.GLOBAL,base.withClusterCells(0));
		Result clustered=solve(problem,Mode.GLOBAL,base.withClusterCells(64));

		assertExact(problem,oracle,independent);
		assertExact(problem,oracle,clustered);
		Assert.assertArrayEquals(new int[] {0,0},clustered.selectedRules());
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(independent.progression().get(0).lowerBound()));
		Assert.assertEquals(0,new BigDecimal("10").compareTo(clustered.progression().get(0).lowerBound()));
		Assert.assertTrue(clustered.statistics().clusterGroups()>0);
		Assert.assertTrue(clustered.statistics().clusterCells()>0);
	}

	@Test public void signedSharedHighOrderProblemMatchesIndependentOracleAndEveryPublishedBound() {
		Problem problem=new Problem(new int[] {3,2,3,2},
			new double[][] {{0,-2,1},{0,1},{0,0.5,1},{0,-0.5}},
			List.of(
				check(1,new int[] {0,1},r -> r[0]==1 && r[1]==0 ? Truth.NO : Truth.YES),
				check(2,new int[] {1,2,3},r -> r[0]+r[1]+r[2]==4 ? Truth.NO : Truth.YES)),
			List.of(
				new CostTerm(new int[] {0,1,2},r -> r[0]==2 && r[1]==0 && r[2]==1 ? -5 : 3,-5),
				new CostTerm(new int[] {0,2,3},r -> r[0]==1 && r[1]==2 && r[2]==1 ? -4 : 2,-4),
				new CostTerm(new int[] {1,3},r -> r[0]==r[1] ? -1 : 1,-1)));
		Oracle oracle=enumerate(problem);
		Result result=solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(false)
			.withSeedWorkLimit(0).withImproveSeed(false).withClusterCells(512).withTrace(true));

		assertExact(problem,oracle,result);
		assertProgression(problem,oracle,result);
		Assert.assertTrue(result.statistics().clusterQueries()>0);
	}

	@Test public void unknownRowsStayUnresolvedInsteadOfBecomingAFalseCertificate() {
		Problem problem=new Problem(new int[] {2,2},new double[][] {{0,0},{0,0}},
			List.of(new Check(4,new int[] {0,1},r -> {
				if(r[0]<0 || r[1]<0) return Truth.UNKNOWN;
				if(r[0]==0 && r[1]==0) return Truth.UNKNOWN;
				return r[0]==0 ? Truth.YES : Truth.NO;
			})),List.of(
				new CostTerm(new int[] {0,1},r -> r[0]==0 && r[1]==0 ? 0 : 5,0),
				new CostTerm(new int[] {0,1},r -> r[0]==0 ? 0 : 7,0)));
		Result result=solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(false)
			.withSeedWorkLimit(0).withImproveSeed(false).withClusterCells(64).withTrace(true));

		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(result.progression().get(0).lowerBound()));
		if(result.selectedRules()!=null)
			Assert.assertEquals(0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
	}

	@Test public void optionalClusterMemoryDeclineFallsBackToExactIndependentSearch() {
		int domain=6;
		Problem problem=new Problem(new int[] {domain,domain,domain,domain},zeros(4,domain),List.of(),List.of(
			new CostTerm(new int[] {0,1,2,3},r -> all(r,0) ? 0 : 100,0),
			new CostTerm(new int[] {0,1,2,3},r -> all(r,domain-1) ? 0 : 100,0)));
		Oracle oracle=enumerate(problem);
		Options unlimited=Options.defaults().withDirectExact(false).withSeedWorkLimit(0)
			.withImproveSeed(false).withClusterCells(4096).withTrace(true);
		Result grouped=solve(problem,Mode.GLOBAL,unlimited);
		assertExact(problem,oracle,grouped);
		Assert.assertEquals(0,new BigDecimal("100").compareTo(grouped.progression().get(0).lowerBound()));

		Result fallback=null;
		for(long bytes:new long[] {8_192,16_384,32_768,65_536,131_072,262_144}) {
			Result candidate=solve(problem,Mode.GLOBAL,unlimited.withMaxResidentBytes(bytes));
			if(candidate.status()==Status.OPTIMAL && candidate.statistics().clusterGroups()==0) {
				fallback=candidate;
				break;
			}
		}
		Assert.assertNotNull("expected a budget where DFS fits after the optional cluster table is declined",fallback);
		assertExact(problem,oracle,fallback);
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(fallback.progression().get(0).lowerBound()));
	}

	@Test public void feasibleBlockImprovementLowersTheInitialUpperBoundWithoutRestrictingProof() {
		Problem problem=new Problem(new int[] {2,2,2,2},zeros(4,2),List.of(),List.of(
			new CostTerm(new int[] {0,1,2},r -> all(r,1) ? 0 : all(r,0) ? 100 : 10,0)));
		Oracle oracle=enumerate(problem);
		Assert.assertArrayEquals(new int[] {1,1,1,0},oracle.selected());
		Options base=Options.defaults().withDirectExact(false).withClusterCells(64)
			.withSeedWorkLimit(1_000).withTrace(true);
		Result greedy=solve(problem,Mode.GLOBAL,base.withImproveSeed(false));
		Result improved=solve(problem,Mode.GLOBAL,base.withImproveSeed(true));

		assertExact(problem,oracle,greedy);
		assertExact(problem,oracle,improved);
		Assert.assertEquals(0,new BigDecimal("10").compareTo(zeroNodeBestUpper(greedy)));
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(zeroNodeBestUpper(improved)));
		Assert.assertTrue(improved.statistics().seedAttempts()>0);
		Assert.assertTrue("seed work must not narrow the proof universe",improved.statistics().nodes()>0);
	}

	private record Problem(int[] sizes,double[][] nativeCosts,List<Check> checks,List<CostTerm> terms) { }
	private record Oracle(BigDecimal cost,int[] selected) { }

	private static Result solve(Problem problem,Mode mode,Options options) {
		boolean[] coverage=new boolean[problem.sizes().length]; Arrays.fill(coverage,true);
		return new MemoDualModeOptimizer(problem.sizes(),coverage,problem.nativeCosts(),problem.checks(),
			problem.terms(),mode,options).optimize();
	}

	private static Oracle enumerate(Problem problem) {
		long combinations=1;
		for(int size:problem.sizes()) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue(combinations<=100_000);
		int[] selected=new int[problem.sizes().length];
		BigDecimal best=null;
		int[] winner=null;
		for(long ordinal=0;ordinal<combinations;ordinal++) {
			boolean feasible=true;
			for(Check check:problem.checks()) {
				Truth truth=check.evaluate(project(selected,check.owners()));
				Assert.assertNotEquals(Truth.UNKNOWN,truth);
				if(truth==Truth.NO) { feasible=false; break; }
			}
			if(feasible) {
				BigDecimal value=evaluate(problem,selected);
				if(best==null || value.compareTo(best)<0) {
					best=value; winner=selected.clone();
				}
			}
			increment(selected,problem.sizes());
		}
		return new Oracle(best,winner);
	}

	private static void assertExact(Problem problem,Oracle oracle,Result result) {
		Assert.assertEquals(result.reason(),Status.OPTIMAL,result.status());
		Assert.assertTrue(result.certified());
		Assert.assertEquals(0,oracle.cost().compareTo(result.lowerBound()));
		Assert.assertEquals(0,oracle.cost().compareTo(result.upperBound()));
		Assert.assertArrayEquals(oracle.selected(),result.selectedRules());
		Assert.assertEquals(0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
	}

	private static void assertProgression(Problem problem,Oracle oracle,Result result) {
		BigDecimal previousLower=null,previousUpper=null;
		for(MemoDualModeOptimizer.Progress point:result.progression()) {
			Assert.assertTrue(point.lowerBound().compareTo(oracle.cost())<=0);
			if(point.upperBound()!=null) {
				Assert.assertTrue(oracle.cost().compareTo(point.upperBound())<=0);
				if(previousUpper!=null) Assert.assertTrue(previousUpper.compareTo(point.upperBound())>=0);
				previousUpper=point.upperBound();
			}
			if(previousLower!=null) Assert.assertTrue(previousLower.compareTo(point.lowerBound())<=0);
			previousLower=point.lowerBound();
		}
		Assert.assertEquals(0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
	}

	private static BigDecimal zeroNodeBestUpper(Result result) {
		BigDecimal best=null;
		for(MemoDualModeOptimizer.Progress point:result.progression())
			if(point.nodes()==0 && point.upperBound()!=null)
				best=best==null ? point.upperBound() : best.min(point.upperBound());
		Assert.assertNotNull("no initial feasible upper bound was published",best);
		return best;
	}

	private static BigDecimal evaluate(Problem problem,int[] selected) {
		BigDecimal result=BigDecimal.ZERO;
		for(int owner=0;owner<selected.length;owner++)
			result=result.add(BigDecimal.valueOf(problem.nativeCosts()[owner][selected[owner]]));
		for(CostTerm term:problem.terms()) result=result.add(term.evaluateExact(project(selected,term.owners())));
		return result;
	}

	private static Check check(int site,int[] owners,MemoOptimizer.Predicate predicate) {
		return new Check(site,owners,r -> {
			for(int rule:r) if(rule<0) return Truth.UNKNOWN;
			return predicate.test(r);
		});
	}

	private static int[] project(int[] selected,int[] owners) {
		int[] result=new int[owners.length];
		for(int i=0;i<owners.length;i++) result[i]=selected[owners[i]];
		return result;
	}

	private static void increment(int[] selected,int[] sizes) {
		for(int i=selected.length-1;i>=0;i--) {
			if(++selected[i]<sizes[i]) return;
			selected[i]=0;
		}
	}

	private static boolean all(int[] values,int expected) {
		for(int value:values) if(value!=expected) return false;
		return true;
	}

	private static double[][] zeros(int owners,int domain) {
		double[][] result=new double[owners][domain];
		return result;
	}

	private static Problem randomProblem(int seed) {
		Random random=new Random(91_337L+seed);
		double[][] nativeCosts=new double[5][2];
		for(int owner=0;owner<nativeCosts.length;owner++)
			for(int rule=0;rule<2;rule++)
				nativeCosts[owner][rule]=(random.nextInt(9)-4)/2.0+(seed%2==0 ? 3 : 0);
		int forbiddenA=random.nextInt(8),forbiddenB=random.nextInt(8);
		List<Check> checks=List.of(
			check(100+seed,new int[] {0,1,2},r -> 4*r[0]+2*r[1]+r[2]==forbiddenA ? Truth.NO : Truth.YES),
			check(200+seed,new int[] {2,3,4},r -> 4*r[0]+2*r[1]+r[2]==forbiddenB ? Truth.NO : Truth.YES));
		List<CostTerm> terms=List.of(
			new CostTerm(new int[] {0,1,2},r -> (r[0]+2*r[1]+r[2]+seed)%3==0 ? -2 : 1.5,-2),
			new CostTerm(new int[] {2,3,4},r -> (2*r[0]+r[1]+r[2]+seed)%4==0 ? -1.25 : 2,-1.25),
			new CostTerm(new int[] {0,4},r -> r[0]==r[1] ? -0.5 : 0.75,-0.5),
			new CostTerm(new int[0],r -> seed%2==0 ? 10 : 0,seed%2==0 ? 10 : 0));
		return new Problem(new int[] {2,2,2,2,2},nativeCosts,checks,terms);
	}
}
