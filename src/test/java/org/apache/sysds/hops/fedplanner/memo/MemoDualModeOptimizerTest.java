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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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

/** Independent Cartesian-product oracle tests for the dual-mode optimizer. */
public class MemoDualModeOptimizerTest {
	private static final BigDecimal EPSILON_FACTOR=new BigDecimal("1.05");

	private record Problem(int[] sizes,boolean[] coverage,double[][] nativeCosts,
		List<Check> checks,List<CostTerm> terms) {
		private Problem {
			sizes=sizes.clone();
			coverage=coverage.clone();
			nativeCosts=Arrays.stream(nativeCosts).map(double[]::clone).toArray(double[][]::new);
			checks=List.copyOf(checks);
			terms=List.copyOf(terms);
		}
	}

	private record Oracle(BigDecimal cost,int[] selected,long feasible) {
		private Oracle { selected=selected==null ? null : selected.clone(); }
	}

	@Test public void globalDirectAndSearchMatchIndependentOracleOnSignedRandomGraphs() {
		for(int seed=0;seed<30;seed++) {
			Problem problem=randomProblem(seed,false);
			Oracle oracle=enumerate(problem);
			Assert.assertNotNull("seed="+seed,oracle.cost());
			for(boolean direct:new boolean[] {true,false}) {
				Result result=solve(problem,Mode.GLOBAL,Options.defaults()
					.withDirectExact(direct).withTrace(true));
				assertExact("seed="+seed+" direct="+direct,problem,oracle,result);
			}
		}
	}

	@Test public void globalCanonicalTieBreakIsLexicographicAcrossRoutes() {
		Problem problem=new Problem(new int[] {3,2,3},covered(3),
			new double[][] {{1,0,0},{0,0},{0,0,0}},
			List.of(check(1,new int[] {0,2},r -> r[0]==0 && r[1]==0 ? Truth.NO : Truth.YES)),
			List.of(new CostTerm(new int[] {0,1,2},r -> r[0]==2 ? 0 : 0,0)));
		Oracle oracle=enumerate(problem);
		Assert.assertArrayEquals(new int[] {1,0,0},oracle.selected());
		for(boolean direct:new boolean[] {true,false})
			assertExact("tie direct="+direct,problem,oracle,
				solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(direct).withTrace(true)));
	}

	@Test public void localCertificatesAndEveryPublishedBoundContainTheTrueOptimum() {
		for(int seed=100;seed<125;seed++) {
			Problem problem=randomProblem(seed,true);
			Oracle oracle=enumerate(problem);
			Result result=solve(problem,Mode.LOCAL,Options.defaults()
				.withDirectExact(false).withTrace(true));
			assertSuccessfulCertificate("seed="+seed,problem,oracle,result);
			Assert.assertTrue(result.upperBound().compareTo(oracle.cost().multiply(EPSILON_FACTOR))<=0);
			if(result.status()==Status.EPSILON_OPTIMAL) {
				Assert.assertTrue(result.lowerBound().signum()>0);
				Assert.assertTrue(result.upperBound().compareTo(result.lowerBound().multiply(EPSILON_FACTOR))<=0);
			}
		}
	}

	@Test public void localUsesTheExactFivePercentBoundaryWithoutFloatingTolerance() {
		Problem boundary=epsilonProblem(5.0);
		Problem outside=epsilonProblem(5.0000001);
		Result atBoundary=solve(boundary,Mode.LOCAL,Options.defaults()
			.withDirectExact(false).withTrace(true));
		assertSuccessfulCertificate("boundary",boundary,enumerate(boundary),atBoundary);
		Assert.assertEquals(Status.EPSILON_OPTIMAL,atBoundary.status());
		Assert.assertEquals(0,new BigDecimal("100").compareTo(atBoundary.lowerBound()));
		Assert.assertEquals(0,new BigDecimal("105").compareTo(atBoundary.upperBound()));

		Result justOutside=solve(outside,Mode.LOCAL,Options.defaults()
			.withDirectExact(false).withTrace(true));
		assertExact("outside",outside,enumerate(outside),justOutside);
	}

	@Test public void negativeZeroAndLargeConstantsRequireExactProof() {
		List<Problem> problems=List.of(
			new Problem(new int[] {2,2},covered(2),new double[][] {{0,0},{0,0}},List.of(),List.of()),
			new Problem(new int[] {2,2},covered(2),new double[][] {{-10,0},{0,-0.25}},List.of(),
				List.of(new CostTerm(new int[] {0,1},r -> r[0]==r[1] ? -1.5 : 2,-1.5))),
			new Problem(new int[] {2,2,2},covered(3),
				new double[][] {{1e12,1e12},{0,0.125},{0.25,0}},List.of(),
				List.of(new CostTerm(new int[0],r -> 1e12,1e12))));
		for(Problem problem:problems) {
			Oracle oracle=enumerate(problem);
			for(Mode mode:Mode.values())
				assertExact(mode.toString(),problem,oracle,solve(problem,mode,
					Options.defaults().withDirectExact(false).withTrace(true)));
		}
	}

	@Test public void multiParentSharedMovementAndCrossingHighArityFactorAreCountedOnce() {
		Problem problem=new Problem(new int[] {3,2,3,2,2,3},covered(6),
			new double[][] {{0.5,0.25,1},{0,0.75},{0.25,0,0.5},{1,0},{0,0.25},{0.5,0.25,0}},
			List.of(
				check(10,new int[] {0,1,2},r -> r[0]==2 && r[1]==0 && r[2]==1 ? Truth.NO : Truth.YES),
				check(11,new int[] {2,3},r -> r[0]==0 && r[1]==1 ? Truth.NO : Truth.YES),
				check(12,new int[] {3,4,0},r -> r[0]==0 && r[1]==1 && r[2]==1 ? Truth.NO : Truth.YES)),
			List.of(
				new CostTerm(new int[] {0,2,3},r -> r[0]==r[1] ? 0.125 : 2.125,0.125),
				new CostTerm(new int[] {0,1,2,4,5},r ->
					(r[0]+r[2]+r[4])%3==r[3] ? 0.0625 : 1.0625,0.0625),
				new CostTerm(new int[] {5,1},r -> r[0]==2*r[1] ? -0.25 : 0.5,-0.25)));
		Oracle oracle=enumerate(problem);
		for(Mode mode:Mode.values())
			assertSuccessfulCertificate(mode.toString(),problem,oracle,solve(problem,mode,
				Options.defaults().withDirectExact(false).withTrace(true)));
	}

	@Test public void unknownOpenAndRejectAllModelsNeverProduceFalseSuccess() {
		Problem open=new Problem(new int[] {2},new boolean[] {false},new double[][] {{0,1}},List.of(),List.of());
		assertUncertified(Status.UNRESOLVED,solve(open,Mode.GLOBAL,Options.defaults()));
		Problem unknown=new Problem(new int[] {2},covered(1),new double[][] {{0,1}},
			List.of(new Check(1,new int[] {0},r -> Truth.UNKNOWN)),List.of());
		assertUncertified(Status.UNRESOLVED,solve(unknown,Mode.LOCAL,
			Options.defaults().withDirectExact(false)));
		Problem rejectAll=new Problem(new int[] {2,2},covered(2),new double[][] {{0,1},{0,1}},
			List.of(check(2,new int[] {0,1},r -> Truth.NO)),List.of());
		Result infeasible=solve(rejectAll,Mode.GLOBAL,Options.defaults().withDirectExact(false));
		Assert.assertEquals(Status.INFEASIBLE,infeasible.status());
		Assert.assertNull(infeasible.selectedRules());
	}

	@Test public void lateUnknownObligationIsPreservedWithoutEnumeratingUnrelatedDomains() {
		int owners=52;
		int[] sizes=new int[owners];
		Arrays.fill(sizes,2);
		double[][] nativeCosts=new double[owners][2];
		nativeCosts[0][0]=1;
		Problem problem=new Problem(sizes,covered(owners),nativeCosts,List.of(
			check(1,new int[] {0,1},r -> r[0]==1 && r[1]==1 ? Truth.UNKNOWN
				: r[0]==1 ? Truth.NO : Truth.YES)),List.of());
		Result result=solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(true)
			.withDirectCells(1).withSeedWorkLimit(0).withDiagnosticNodes(64).withTrace(true));
		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertFalse(result.certified());
		if(result.selectedRules()!=null)
			Assert.assertEquals(0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
		Assert.assertTrue("UNKNOWN on two owners must only visit their binary search tree; nodes="+
			result.statistics().nodes(),
			result.statistics().nodes()<=7);
	}

	@Test public void alreadyFixedUnknownScopeStopsBeforeUnrelatedDomains() {
		int owners=52;
		int[] sizes=new int[owners];
		Arrays.fill(sizes,2);
		sizes[0]=1;
		sizes[1]=1;
		double[][] nativeCosts=new double[owners][];
		for(int owner=0;owner<owners;owner++) nativeCosts[owner]=new double[sizes[owner]];
		Problem problem=new Problem(sizes,covered(owners),nativeCosts,
			List.of(new Check(1,new int[] {0,1},r -> Truth.UNKNOWN)),List.of());
		Result result=solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(true)
			.withDirectCells(1).withSeedWorkLimit(0).withDiagnosticNodes(8));
		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertTrue("fixed UNKNOWN must stop before the unrelated 2^50 product; nodes="+
			result.statistics().nodes(),result.statistics().nodes()<=1);
	}

	@Test public void greedyDeadEndAndSeedWorkCapsDoNotRemoveFeasibleAssignments() {
		Problem problem=new Problem(new int[] {2,2,2,2},covered(4),
			new double[][] {{0,3},{0,3},{0,3},{0,3}},
			List.of(
				check(1,new int[] {0,1},r -> r[0]==r[1] ? Truth.YES : Truth.NO),
				check(2,new int[] {1,2},r -> r[0]==r[1] ? Truth.YES : Truth.NO),
				check(3,new int[] {2,3},r -> r[0]==r[1] ? Truth.YES : Truth.NO),
				check(4,new int[] {0,3},r -> r[0]==0 && r[1]==0 ? Truth.NO : Truth.YES)),
			List.of(new CostTerm(new int[] {0,1,2,3},r -> r[0]==1 ? -11 : 0,-11)));
		Oracle oracle=enumerate(problem);
		Assert.assertArrayEquals(new int[] {1,1,1,1},oracle.selected());
		for(long seedCap:new long[] {0,1,Long.MAX_VALUE}) {
			Options options=Options.defaults().withDirectExact(false)
				.withSeedWorkLimit(seedCap).withTrace(true);
			Result result=solve(problem,Mode.LOCAL,options);
			assertSuccessfulCertificate("seed cap="+seedCap,problem,oracle,result);
			assertExact("global seed cap="+seedCap,problem,oracle,solve(problem,Mode.GLOBAL,options));
		}
	}

	@Test public void directExactRouteDoesNotCreateSeedRelaxationOrPendingSearchWork() {
		Problem problem=randomProblem(77,true);
		Oracle oracle=enumerate(problem);
		Result result=solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(true).withTrace(true));
		assertExact("direct",problem,oracle,result);
		Assert.assertEquals(1,result.statistics().directExactSolves());
		Assert.assertEquals(0,result.statistics().seedAttempts());
		Assert.assertEquals(0,result.statistics().relaxationCalls());
		Assert.assertEquals(0,result.statistics().maxPending());
	}

	@Test public void declinedCyclicKernelPreservesTheCoverAndCompletesConditionedTrees() {
		Problem cycle=new Problem(new int[] {2,2,2},covered(3),
			new double[][] {{1,0},{0,1},{0.5,0}},List.of(),List.of(
				new CostTerm(new int[] {0,1},r -> r[0]==r[1] ? 0 : 3,0),
				new CostTerm(new int[] {1,2},r -> r[0]==r[1] ? 0 : 2,0),
				new CostTerm(new int[] {2,0},r -> r[0]==r[1] ? 0 : 4,0)));
		Oracle oracle=enumerate(cycle);
		Result result=solve(cycle,Mode.GLOBAL,Options.defaults().withDirectExact(true)
			.withDirectCells(0).withSeedWorkLimit(0).withTrace(true));
		assertExact("declined cyclic direct kernel",cycle,oracle,result);
		Assert.assertTrue("root decline must not disable exact kernels in conditioned children",
			result.statistics().directAttempts()>1);
		Assert.assertTrue(result.statistics().directExactSolves()>0);
	}

	@Test public void directKernelRecomputesNeighborsAfterConditionalPropagation() {
		int[] sizes={2,2,2};
		boolean[] coverage=covered(3);
		double[][] nativeCosts={{0,1},{0,1},{0,1}};
		List<Check> checks=List.of(check(1,new int[] {0,1},r -> r[0]==r[1] ? Truth.YES : Truth.NO));
		List<CostTerm> terms=List.of(
			new CostTerm(new int[] {0,2},r -> r[0]==r[1] ? 0 : 5,0),
			new CostTerm(new int[] {1,2},r -> r[0]==r[1] ? 0 : 3,0));
		BitSet[] restrictions={new BitSet(2),new BitSet(2),new BitSet(2)};
		restrictions[0].set(0);
		restrictions[1].set(0,2);
		restrictions[2].set(0,2);
		MemoOptimizer.DirectResult result=new MemoOptimizer(sizes,coverage,nativeCosts,checks,terms,
			MemoOptimizer.Limits.exactDefaults()).optimizeDirect(restrictions,MemoResourcePolicy.defaults());
		Assert.assertTrue("conditioned triangle becomes an exact tree",result.completed());
		Assert.assertEquals(Status.OPTIMAL,result.result().status());
		Assert.assertArrayEquals(new int[] {0,0,0},result.result().selectedRules());
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(result.result().cost()));
	}

	@Test public void certifiedClassesNeverReplaceTheSelectedNativeOrdinal() {
		int[][] classes={{0,0,1,1},{0,1,0,1},{0,0}};
		Problem problem=new Problem(new int[] {4,4,2},covered(3),
			new double[][] {{5,4,0,1},{0,1,2,3},{0,0.25}},List.of(),
			List.of(new CostTerm(new int[] {0,1,2},r ->
				(r[0]>=2 ? 10 : 0)+(r[1]%2),0,classes)));
		Oracle oracle=enumerate(problem);
		Assert.assertEquals(1,oracle.selected()[0]);
		for(boolean direct:new boolean[] {true,false}) {
			Result result=solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(direct));
			assertExact("class direct="+direct,problem,oracle,result);
			Assert.assertEquals(1,result.selectedRules()[0]);
		}
	}

	@Test public void insufficientMemoryAndDiagnosticLimitAreNeverReportedAsCertifiedSuccess() {
		Problem problem=randomProblem(91,true);
		Oracle oracle=enumerate(problem);
		for(Options options:List.of(
			Options.defaults().withDirectExact(false).withMaxResidentBytes(1),
			Options.defaults().withDirectExact(false).withDiagnosticNodes(1))) {
			Result result=solve(problem,Mode.GLOBAL,options.withTrace(true));
			if(result.status()==Status.OPTIMAL)
				assertExact("completed before limit",problem,oracle,result);
			else {
				Assert.assertTrue(result.status()==Status.RESOURCE_EXHAUSTED
					|| result.status()==Status.UNRESOLVED);
				Assert.assertFalse(result.certified());
			}
		}
	}

	@Test public void memoryRejectedDirectTableFallsBackToExactLowMemorySearch() {
		int owners=5;
		int[] sizes=new int[owners];
		Arrays.fill(sizes,4);
		double[][] nativeCosts=new double[owners][4];
		for(int owner=0;owner<owners;owner++)
			for(int rule=0;rule<4;rule++) nativeCosts[owner][rule]=(owner+rule)%5/8.0;
		Problem problem=new Problem(sizes,covered(owners),nativeCosts,List.of(),List.of(
			new CostTerm(new int[] {0,1,2,3,4},r ->
				(r[0]+2*r[1]+3*r[2]+4*r[3]+5*r[4])%7/8.0,0)));
		Oracle oracle=enumerate(problem);
		Result fallback=null;
		for(long bytes:new long[] {8_192,16_384,32_768,65_536,131_072,262_144}) {
			Result candidate=solve(problem,Mode.GLOBAL,Options.defaults().withDirectExact(true)
				.withFactorCells(Integer.MAX_VALUE).withSeedWorkLimit(0)
				.withMaxResidentBytes(bytes).withTrace(true));
			if(candidate.status()==Status.OPTIMAL
				&& "CERTIFIED_SEARCH".equals(candidate.statistics().route())) {
				fallback=candidate;
				break;
			}
		}
		Assert.assertNotNull("expected a byte range where a direct table is rejected but DFS fits",fallback);
		assertExact("memory fallback",problem,oracle,fallback);
		Assert.assertTrue(fallback.statistics().directAttempts()>0);
	}

	@Test public void deepTreeChainPreservesWitnessAndExactCostWithoutRecursiveOverflow() {
		int owners=400;
		int[] sizes=new int[owners];
		Arrays.fill(sizes,2);
		double[][] nativeCosts=new double[owners][2];
		for(double[] costs:nativeCosts) costs[1]=1;
		List<Check> checks=new ArrayList<>();
		for(int owner=0;owner+1<owners;owner++)
			checks.add(check(owner,new int[] {owner,owner+1},r -> r[0]==r[1] ? Truth.YES : Truth.NO));
		Problem problem=new Problem(sizes,covered(owners),nativeCosts,checks,List.of());
		Result result=solve(problem,Mode.GLOBAL,Options.defaults().withTrace(true));
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(result.upperBound()));
		Assert.assertArrayEquals(new int[owners],result.selectedRules());
	}

	private static Result solve(Problem problem,Mode mode,Options options) {
		return new MemoDualModeOptimizer(problem.sizes(),problem.coverage(),problem.nativeCosts(),
			problem.checks(),problem.terms(),mode,options).optimize();
	}

	private static void assertExact(String label,Problem problem,Oracle oracle,Result result) {
		assertSuccessfulCertificate(label,problem,oracle,result);
		Assert.assertEquals(label,Status.OPTIMAL,result.status());
		Assert.assertEquals(label,0,oracle.cost().compareTo(result.upperBound()));
		Assert.assertEquals(label,0,result.lowerBound().compareTo(result.upperBound()));
		Assert.assertArrayEquals(label,oracle.selected(),result.selectedRules());
	}

	private static void assertSuccessfulCertificate(String label,Problem problem,Oracle oracle,Result result) {
		Assert.assertTrue(label+" status="+result.status(),result.certified());
		Assert.assertTrue(label,result.status()==Status.OPTIMAL || result.status()==Status.EPSILON_OPTIMAL);
		Assert.assertNotNull(label,result.selectedRules());
		Assert.assertEquals(label,0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
		Assert.assertTrue(label+" LB<=OPT",result.lowerBound().compareTo(oracle.cost())<=0);
		Assert.assertTrue(label+" OPT<=UB",oracle.cost().compareTo(result.upperBound())<=0);
		BigDecimal previousLower=null,previousUpper=null;
		boolean feasibleUpperSeen=false;
		for(var point:result.progression()) {
			Assert.assertTrue(label+" trace LB<=OPT",point.lowerBound().compareTo(oracle.cost())<=0);
			if(point.upperBound()!=null) {
				feasibleUpperSeen=true;
				Assert.assertTrue(label+" trace OPT<=UB",oracle.cost().compareTo(point.upperBound())<=0);
			}
			else
				Assert.assertFalse(label+" trace lost a previously validated incumbent",feasibleUpperSeen);
			if(previousLower!=null)
				Assert.assertTrue(label+" monotone LB",previousLower.compareTo(point.lowerBound())<=0);
			if(previousUpper!=null && point.upperBound()!=null)
				Assert.assertTrue(label+" monotone UB",previousUpper.compareTo(point.upperBound())>=0);
			previousLower=point.lowerBound();
			if(point.upperBound()!=null) previousUpper=point.upperBound();
		}
	}

	private static void assertUncertified(Status expected,Result result) {
		Assert.assertEquals(expected,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertNull(result.selectedRules());
	}

	private static Oracle enumerate(Problem problem) {
		long combinations=1;
		for(int size:problem.sizes()) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("oracle fixture unexpectedly large: "+combinations,combinations<=1_000_000);
		BigDecimal best=null;
		int[] winner=null;
		long feasible=0;
		int[] selected=new int[problem.sizes().length];
		for(long ordinal=0;ordinal<combinations;ordinal++) {
			boolean legal=true;
			for(Check check:problem.checks()) {
				int[] tuple=project(selected,check.owners());
				Truth truth=check.evaluate(tuple);
				Assert.assertNotEquals("oracle fixture returned UNKNOWN for a complete tuple",Truth.UNKNOWN,truth);
				if(truth==Truth.NO) { legal=false; break; }
			}
			if(legal) {
				feasible++;
				BigDecimal cost=evaluate(problem,selected);
				if(best==null || cost.compareTo(best)<0) {
					best=cost;
					winner=selected.clone();
				}
			}
			increment(selected,problem.sizes());
		}
		return new Oracle(best,winner,feasible);
	}

	private static BigDecimal evaluate(Problem problem,int[] selected) {
		BigDecimal cost=BigDecimal.ZERO;
		for(int owner=0;owner<selected.length;owner++)
			cost=cost.add(BigDecimal.valueOf(problem.nativeCosts()[owner][selected[owner]]));
		for(CostTerm term:problem.terms())
			cost=cost.add(term.evaluateExact(project(selected,term.owners())));
		return cost;
	}

	private static int[] project(int[] selected,int[] owners) {
		int[] tuple=new int[owners.length];
		for(int i=0;i<owners.length;i++) tuple[i]=selected[owners[i]];
		return tuple;
	}

	private static void increment(int[] selected,int[] sizes) {
		for(int i=selected.length-1;i>=0;i--) {
			if(++selected[i]<sizes[i]) return;
			selected[i]=0;
		}
	}

	private static Check check(int site,int[] owners,MemoOptimizer.Predicate completePredicate) {
		return new Check(site,owners,r -> {
			for(int rule:r) if(rule<0) return Truth.UNKNOWN;
			return completePredicate.test(r);
		});
	}

	private static Problem randomProblem(int seed,boolean positive) {
		Random random=new Random(seed);
		int owners=5+seed%2;
		int[] sizes=new int[owners];
		double[][] nativeCosts=new double[owners][];
		for(int owner=0;owner<owners;owner++) {
			sizes[owner]=2+random.nextInt(2);
			nativeCosts[owner]=new double[sizes[owner]];
			for(int rule=0;rule<sizes[owner];rule++)
				nativeCosts[owner][rule]=(random.nextInt(17)+(positive ? 20 : -4))/8.0;
		}
		List<Check> checks=new ArrayList<>();
		for(int owner=0;owner<owners;owner++) {
			int next=(owner+1)%owners,forbidden=random.nextInt(3);
			checks.add(check(100+owner,new int[] {owner,next},r ->
				r[0]+2*r[1]==forbidden && r[0]+r[1]>0 ? Truth.NO : Truth.YES));
		}
		checks.add(check(200,new int[] {0,2,owners-1},r ->
			r[0]>0 && r[1]>0 && r[2]>0 && (r[0]+r[1]+r[2])%2==0 ? Truth.NO : Truth.YES));
		double floor=positive ? 0.125 : -0.25;
		List<CostTerm> terms=List.of(
			new CostTerm(new int[] {0,2},r -> r[0]==r[1] ? floor : floor+0.75,floor),
			new CostTerm(new int[] {0,1,3},r -> (positive ? 0.25 : 0)+(r[0]+2*r[1]+r[2])/16.0,
				positive ? 0.25 : 0),
			new CostTerm(new int[] {2,owners-1},r -> r[0]==0 && r[1]==0 ? 0 : 0.375,0));
		return new Problem(sizes,covered(owners),nativeCosts,checks,terms);
	}

	private static Problem epsilonProblem(double penalty) {
		return new Problem(new int[] {2,2},covered(2),
			new double[][] {{100,100},{0,0}},List.of(),List.of(
				new CostTerm(new int[] {0,1},r -> r[0]==0
					? r[1]==0 ? penalty : 6
					: r[1]==0 ? 10 : 0,0)));
	}

	private static boolean[] covered(int owners) {
		boolean[] result=new boolean[owners];
		Arrays.fill(result,true);
		return result;
	}
}
