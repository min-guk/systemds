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
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Mode;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Options;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Progress;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

/** Independent exhaustive-oracle tests for Local's certificate-first contract. */
public class MemoLocalOptimizerTest {
	private static final BigDecimal EPSILON_FACTOR=new BigDecimal("1.05");

	private record Problem(int[] sizes,boolean[] coverage,double[][] nativeCosts,
		List<Check> checks,List<CostTerm> costs) {
		private Problem {
			sizes=sizes.clone(); coverage=coverage.clone();
			nativeCosts=Arrays.stream(nativeCosts).map(double[]::clone).toArray(double[][]::new);
			checks=List.copyOf(checks); costs=List.copyOf(costs);
		}
		private Problem(int[] sizes,double[][] nativeCosts,List<Check> checks,List<CostTerm> costs) {
			this(sizes,covered(sizes.length),nativeCosts,checks,costs);
		}
	}

	private record Oracle(BigDecimal cost,int[] selected) {
		private Oracle { selected=selected==null ? null : selected.clone(); }
	}

	@Test public void defaultHighMemoryLocalCertifiesTheFirstFivePercentWitnessWithoutDirectExact() {
		Problem problem=epsilonProblem("5");
		Result result=local(problem,Options.defaults().withTrace(true));

		Assert.assertEquals(Status.EPSILON_OPTIMAL,result.status());
		Assert.assertEquals(0,new BigDecimal("100").compareTo(result.lowerBound()));
		Assert.assertEquals(0,new BigDecimal("105").compareTo(result.upperBound()));
		Assert.assertArrayEquals(new int[] {0,0},result.selectedRules());
		Assert.assertEquals("Local must not start with a monolithic exact attempt",0,
			result.statistics().directAttempts());
		Assert.assertEquals(0,result.statistics().directExactSolves());
		Assert.assertEquals("LOCAL_CERTIFICATE_FIRST",result.statistics().route());
		Assert.assertEquals("the initial certificate requires no branch node",0,result.statistics().nodes());
		assertCertificate(problem,enumerate(problem),result);
	}

	@Test public void justOutsideFivePercentIsRefinedInsteadOfFalselyCertified() {
		Problem problem=epsilonProblem("5.0000001");
		Oracle oracle=enumerate(problem);
		Result result=local(problem,Options.defaults().withTrace(true));

		assertCertificate(problem,oracle,result);
		Assert.assertFalse("105.0000001 over LB 100 is not a 5% certificate",
			new BigDecimal("105.0000001").compareTo(EPSILON_FACTOR.multiply(new BigDecimal("100")))<=0);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertEquals(0,oracle.cost().compareTo(result.upperBound()));
		Assert.assertArrayEquals("the first seed must not freeze the proof domain",oracle.selected(),
			result.selectedRules());
		Assert.assertEquals("LOCAL_CERTIFICATE_FIRST",result.statistics().route());
	}

	@Test public void zeroAndNegativeLowerBoundsRequireExactProof() {
		List<Problem> problems=List.of(
			new Problem(new int[] {2,2},new double[][] {{0,0},{0,0}},List.of(),List.of()),
			new Problem(new int[] {2,2},new double[][] {{-4,1},{0,-2}},List.of(),List.of(
				new CostTerm(new int[] {0,1},r -> r[0]==r[1] ? -3 : 2,-3))));
		for(Problem problem:problems) {
			Oracle oracle=enumerate(problem);
			Result result=local(problem,Options.defaults().withTrace(true));
			assertExact(problem,oracle,result);
			Assert.assertNotEquals(Status.EPSILON_OPTIMAL,result.status());
		}
	}

	@Test public void signedRandomModelsMatchAnIndependentCartesianOracle() {
		for(int seed=0;seed<16;seed++) {
			Problem problem=randomProblem(seed,true);
			Oracle oracle=enumerate(problem);
			Result result=local(problem,Options.defaults().withTrace(true));
			assertExact(problem,oracle,result);
		}
	}

	@Test public void positiveRandomModelsPublishOnlyOracleContainingBounds() {
		for(int seed=100;seed<124;seed++) {
			Problem problem=randomProblem(seed,false);
			Oracle oracle=enumerate(problem);
			Result result=local(problem,Options.defaults().withTrace(true));
			assertCertificate(problem,oracle,result);
		}
	}

	@Test public void sharedMultiFactorHighArityConflictIsCountedOnce() {
		Problem problem=new Problem(new int[] {3,2,3,2,2},
			new double[][] {{2,0,1},{0,1},{1,0,2},{0,1},{1,0}},
			List.of(
				check(1,new int[] {0,1,2},r -> r[0]==1 && r[1]==0 && r[2]==1 ? Truth.NO : Truth.YES),
				check(2,new int[] {2,3,4,0},r -> r[0]+r[1]+r[2]+r[3]==6 ? Truth.NO : Truth.YES)),
			List.of(
				new CostTerm(new int[] {0,2,3},r -> r[0]==r[1] ? 0 : 7,0),
				new CostTerm(new int[] {0,1,2,4},r -> (r[0]+2*r[1]+r[2]+r[3])%3==0 ? 0 : 5,0),
				new CostTerm(new int[] {0,2},r -> r[0]==2 && r[1]==0 ? -4 : 1,-4)));
		Oracle oracle=enumerate(problem);
		Result result=local(problem,Options.defaults().withTrace(true));

		assertExact(problem,oracle,result); // signed floor deliberately disables epsilon termination
	}

	@Test public void unknownCheaperRegionRemainsInTheGlobalLowerBound() {
		Problem problem=new Problem(new int[] {2},new double[][] {{90,105}},List.of(
			new Check(7,new int[] {0},r -> r[0]==0 ? Truth.UNKNOWN : Truth.YES)),List.of());
		Result result=local(problem,Options.defaults().withTrace(true));

		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertArrayEquals(new int[] {1},result.selectedRules());
		Assert.assertEquals(0,new BigDecimal("105").compareTo(result.upperBound()));
		Assert.assertTrue("the possible UNKNOWN cost cannot be discarded",
			result.lowerBound().compareTo(new BigDecimal("90"))<=0);
		for(Progress point:result.progression())
			Assert.assertTrue(point.lowerBound().compareTo(new BigDecimal("90"))<=0);
	}

	@Test public void impossibleOriginalProblemIsProvedInfeasible() {
		Problem problem=new Problem(new int[] {2,3},new double[][] {{0,1},{0,1,2}},List.of(
			check(9,new int[] {0,1},r -> Truth.NO)),List.of());
		Result result=local(problem,Options.defaults().withTrace(true));

		Assert.assertEquals(Status.INFEASIBLE,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertNull(result.selectedRules());
		Assert.assertNull(result.upperBound());
	}

	@Test public void incompleteCandidateCoverageCannotProduceASeedCertificate() {
		Problem problem=new Problem(new int[] {2},new boolean[] {false},new double[][] {{100,105}},
			List.of(),List.of());
		Result result=local(problem,Options.defaults().withTrace(true));

		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertNull(result.selectedRules());
	}

	@Test public void globalKeepsItsExactFirstRouteWhileLocalUsesCertificateFirst() {
		Problem problem=epsilonProblem("5");
		Result global=solve(problem,Mode.GLOBAL,Options.defaults().withTrace(true));
		Result local=local(problem,Options.defaults().withTrace(true));

		Assert.assertEquals(Status.OPTIMAL,global.status());
		Assert.assertEquals("DIRECT",global.statistics().route());
		Assert.assertEquals(1,global.statistics().directAttempts());
		Assert.assertEquals(1,global.statistics().directExactSolves());
		Assert.assertEquals(Status.EPSILON_OPTIMAL,local.status());
		Assert.assertEquals("LOCAL_CERTIFICATE_FIRST",local.statistics().route());
		Assert.assertEquals(0,local.statistics().directAttempts());
	}

	@Test public void improvedUpperBoundDoesNotRestrictLaterConflictResolution() {
		Problem problem=new Problem(new int[] {2,2,2},new double[][] {{100,100},{0,0},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==0 && r[1]==0 ? 4 : r[0]==1 && r[1]==1 ? 0 : 8,0),
			new CostTerm(new int[] {1,2},r -> r[0]==0 && r[1]==0 ? 1.0000001
				: r[0]==1 && r[1]==1 ? 0 : 8,0)));
		Oracle oracle=enumerate(problem);
		Assert.assertArrayEquals(new int[] {1,1,1},oracle.selected());
		Result result=local(problem,Options.defaults().withImproveSeed(true).withTrace(true));

		assertExact(problem,oracle,result);
		Assert.assertArrayEquals("a good incumbent is only an upper bound, never a domain restriction",
			new int[] {1,1,1},result.selectedRules());
	}

	@Test public void localStopsAfterOnlyTheMessagesNeededForTheFivePercentCertificate() {
		Problem problem=new Problem(new int[] {1,2,2},new double[][] {{100},{0,0},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {1},r -> r[0]==0 ? 2 : 3,0),
			new CostTerm(new int[] {2},r -> r[0]==0 ? 4 : 5,0)));
		Oracle oracle=enumerate(problem);
		String previous=System.getProperty("sysds.fedplanner.memo.local.improveSeed");
		Result result;
		try {
			System.setProperty("sysds.fedplanner.memo.local.improveSeed","false");
			result=local(problem,Options.defaults().withTrace(true));
		}
		finally { restoreProperty("sysds.fedplanner.memo.local.improveSeed",previous); }

		assertCertificate(problem,oracle,result);
		Assert.assertEquals(Status.EPSILON_OPTIMAL,result.status());
		Assert.assertEquals(0,new BigDecimal("102").compareTo(result.lowerBound()));
		Assert.assertEquals(0,new BigDecimal("106").compareTo(result.upperBound()));
		Assert.assertEquals("only the fixed native bucket and x bucket are needed",2,
			result.statistics().clusterGroups());
		Assert.assertNotNull(result.localStatistics());
		Assert.assertEquals(2,result.localStatistics().completedMessages());
		Assert.assertEquals("the y bucket remains outside the proof work",1,
			result.localStatistics().untouchedBuckets());
		Assert.assertEquals(0,result.statistics().directAttempts());
		Assert.assertEquals(0,result.statistics().directExactSolves());
		Assert.assertEquals("LOCAL_CERTIFICATE_FIRST",result.statistics().route());
	}

	@Test public void disablingMessagesFallsBackToCompleteOriginalDomainSearch() {
		Problem problem=randomProblem(51,true);
		Oracle oracle=enumerate(problem);
		String previous=System.getProperty("sysds.fedplanner.memo.local.messages");
		Result result;
		try {
			System.setProperty("sysds.fedplanner.memo.local.messages","false");
			result=local(problem,Options.defaults().withTrace(true));
		}
		finally { restoreProperty("sysds.fedplanner.memo.local.messages",previous); }

		assertExact(problem,oracle,result);
		Assert.assertEquals(0,result.statistics().clusterGroups());
		Assert.assertEquals(0,result.statistics().directAttempts());
		Assert.assertEquals("LOCAL_CERTIFICATE_FIRST",result.statistics().route());
	}

	@Test public void typedImproveSeedOptionIsUsedWhenTheSystemPropertyIsAbsent() {
		Problem problem=new Problem(new int[] {2,2,2},new double[][] {{100,100},{0,0},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==0 && r[1]==0 ? 4 : r[0]==1 && r[1]==1 ? 0 : 8,0),
			new CostTerm(new int[] {1,2},r -> r[0]==0 && r[1]==0 ? 1.0000001
				: r[0]==1 && r[1]==1 ? 0 : 8,0)));
		Oracle oracle=enumerate(problem);
		String previous=System.getProperty("sysds.fedplanner.memo.local.improveSeed");
		Result disabled,enabled;
		try {
			System.clearProperty("sysds.fedplanner.memo.local.improveSeed");
			disabled=local(problem,Options.defaults().withImproveSeed(false).withTrace(true));
			enabled=local(problem,Options.defaults().withImproveSeed(true).withTrace(true));
		}
		finally { restoreProperty("sysds.fedplanner.memo.local.improveSeed",previous); }

		assertCertificate(problem,oracle,disabled);
		assertCertificate(problem,oracle,enabled);
		Assert.assertEquals("only FIRST_FEASIBLE runs when the typed option is false",1,
			disabled.statistics().seedAttempts());
		Assert.assertTrue("the typed true option enables bounded seed refinement",
			enabled.statistics().seedAttempts()>disabled.statistics().seedAttempts());
	}

	@Test public void initialMemoryAdmissionAccountsForBaseAndProofDomainsTogether() {
		int domain=64;
		Problem problem=new Problem(new int[] {domain,domain},zeros(2,domain),List.of(),List.of());
		// Base=3648 and proof domains=240 bytes under Local's conservative accounting.
		Result result=local(problem,Options.defaults().withSeedWorkLimit(0).withClusterCells(0)
			.withMaxResidentBytes(3_887));

		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertEquals("Cannot retain Local proof domains",result.reason());
		Assert.assertEquals("the rejected peak is the combined resident state",3_888,
			result.statistics().peakEstimatedBytes());
		Assert.assertNull(result.selectedRules());
	}

	@Test public void rejectedAtomicChildReservationRetainsTheParentBoundsAndIncumbent() {
		Problem problem=new Problem(new int[] {2,2},new double[][] {{0,0},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==0 ? 10+r[1] : r[1],0)));
		Oracle oracle=enumerate(problem);
		String previousMessages=System.getProperty("sysds.fedplanner.memo.local.messages");
		String previousImprove=System.getProperty("sysds.fedplanner.memo.local.improveSeed");
		Result result;
		try {
			System.setProperty("sysds.fedplanner.memo.local.messages","false");
			System.setProperty("sysds.fedplanner.memo.local.improveSeed","false");
			result=local(problem,Options.defaults().withMaxResidentBytes(3_800).withTrace(true));
		}
		finally {
			restoreProperty("sysds.fedplanner.memo.local.messages",previousMessages);
			restoreProperty("sysds.fedplanner.memo.local.improveSeed",previousImprove);
		}

		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,result.status());
		Assert.assertFalse(result.certified());
		Assert.assertEquals("Cannot retain both children of proof cover",result.reason());
		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(result.lowerBound()));
		Assert.assertEquals(0,BigDecimal.TEN.compareTo(result.upperBound()));
		Assert.assertTrue(result.lowerBound().compareTo(oracle.cost())<=0);
		Assert.assertTrue(oracle.cost().compareTo(result.upperBound())<=0);
		Assert.assertArrayEquals(new int[] {0,0},result.selectedRules());
		Assert.assertNotNull(result.localStatistics());
		Assert.assertEquals("the parent remains in the proof cover after failed child admission",1,
			result.localStatistics().activeRegions());
		Assert.assertTrue(result.localStatistics().memoryRejections()>0);
		for(Progress point:result.progression()) {
			Assert.assertTrue(point.lowerBound().compareTo(oracle.cost())<=0);
			if(point.upperBound()!=null) Assert.assertTrue(oracle.cost().compareTo(point.upperBound())<=0);
		}
	}

	private static Problem epsilonProblem(String penalty) {
		BigDecimal exactPenalty=new BigDecimal(penalty);
		return new Problem(new int[] {2,2},new double[][] {{100,100},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==0
				? r[1]==0 ? exactPenalty.doubleValue() : 6
				: r[1]==0 ? 10 : 0,0)));
	}

	private static Problem randomProblem(int seed,boolean signed) {
		Random random=new Random(seed);
		int owners=4+seed%2;
		int[] sizes=new int[owners]; double[][] nativeCosts=new double[owners][];
		for(int owner=0;owner<owners;owner++) {
			sizes[owner]=2+random.nextInt(2); nativeCosts[owner]=new double[sizes[owner]];
			for(int rule=0;rule<sizes[owner];rule++)
				nativeCosts[owner][rule]=(random.nextInt(13)+(signed ? -6 : 18))/8.0;
		}
		List<Check> checks=new ArrayList<>();
		for(int owner=0;owner<owners;owner++) {
			int next=(owner+1)%owners,forbidden=random.nextInt(2);
			checks.add(check(owner,new int[] {owner,next},r ->
				r[0]>0 && r[1]>0 && (r[0]+r[1])%2==forbidden ? Truth.NO : Truth.YES));
		}
		double floor=signed ? -1.0 : 0;
		List<CostTerm> costs=List.of(
			new CostTerm(new int[] {0,2},r -> r[0]==r[1] ? floor : floor+2,floor),
			new CostTerm(new int[] {0,1,owners-1},r ->
				(r[0]+2*r[1]+r[2])%3==0 ? floor : floor+1.5,floor));
		return new Problem(sizes,nativeCosts,checks,costs);
	}

	private static Result local(Problem problem,Options options) { return solve(problem,Mode.LOCAL,options); }

	private static Result solve(Problem problem,Mode mode,Options options) {
		return new MemoDualModeOptimizer(problem.sizes(),problem.coverage(),problem.nativeCosts(),
			problem.checks(),problem.costs(),mode,options).optimize();
	}

	private static void assertExact(Problem problem,Oracle oracle,Result result) {
		assertCertificate(problem,oracle,result);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertEquals(0,oracle.cost().compareTo(result.lowerBound()));
		Assert.assertEquals(0,oracle.cost().compareTo(result.upperBound()));
	}

	private static void assertCertificate(Problem problem,Oracle oracle,Result result) {
		Assert.assertTrue("status="+result.status()+" reason="+result.reason(),result.certified());
		Assert.assertNotNull(result.selectedRules());
		Assert.assertEquals(Truth.YES,truth(problem,result.selectedRules()));
		Assert.assertEquals(0,evaluate(problem,result.selectedRules()).compareTo(result.upperBound()));
		Assert.assertTrue("LB <= OPT",result.lowerBound().compareTo(oracle.cost())<=0);
		Assert.assertTrue("OPT <= UB",oracle.cost().compareTo(result.upperBound())<=0);
		if(result.status()==Status.EPSILON_OPTIMAL) {
			Assert.assertTrue(result.lowerBound().signum()>0);
			Assert.assertTrue(result.upperBound().compareTo(EPSILON_FACTOR.multiply(result.lowerBound()))<=0);
		}
		BigDecimal previousLower=null,previousUpper=null;
		for(Progress point:result.progression()) {
			Assert.assertTrue("published LB <= OPT",point.lowerBound().compareTo(oracle.cost())<=0);
			if(point.upperBound()!=null)
				Assert.assertTrue("published OPT <= UB",oracle.cost().compareTo(point.upperBound())<=0);
			if(previousLower!=null) Assert.assertTrue(previousLower.compareTo(point.lowerBound())<=0);
			if(previousUpper!=null && point.upperBound()!=null)
				Assert.assertTrue(previousUpper.compareTo(point.upperBound())>=0);
			previousLower=point.lowerBound();
			if(point.upperBound()!=null) previousUpper=point.upperBound();
		}
	}

	private static Oracle enumerate(Problem problem) {
		long combinations=1;
		for(int size:problem.sizes()) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("oracle fixture too large",combinations<=100_000);
		BigDecimal best=null; int[] winner=null; int[] selected=new int[problem.sizes().length];
		for(long ordinal=0;ordinal<combinations;ordinal++) {
			Truth truth=truth(problem,selected);
			Assert.assertNotEquals("ordinary oracle fixture has UNKNOWN complete row",Truth.UNKNOWN,truth);
			if(truth==Truth.YES) {
				BigDecimal cost=evaluate(problem,selected);
				if(best==null || cost.compareTo(best)<0) { best=cost; winner=selected.clone(); }
			}
			increment(selected,problem.sizes());
		}
		Assert.assertNotNull("ordinary oracle fixture must be feasible",best);
		return new Oracle(best,winner);
	}

	private static Truth truth(Problem problem,int[] selected) {
		Truth result=Truth.YES;
		for(Check check:problem.checks()) {
			Truth current=check.evaluate(project(selected,check.owners()));
			if(current==Truth.NO) return Truth.NO;
			if(current==Truth.UNKNOWN) result=Truth.UNKNOWN;
		}
		return result;
	}

	private static BigDecimal evaluate(Problem problem,int[] selected) {
		BigDecimal result=BigDecimal.ZERO;
		for(int owner=0;owner<selected.length;owner++)
			result=result.add(new BigDecimal(problem.nativeCosts()[owner][selected[owner]]));
		for(CostTerm cost:problem.costs())
			result=result.add(cost.evaluateExact(project(selected,cost.owners())));
		return result;
	}

	private static int[] project(int[] selected,int[] owners) {
		int[] result=new int[owners.length];
		for(int axis=0;axis<owners.length;axis++) result[axis]=selected[owners[axis]];
		return result;
	}

	private static void increment(int[] selected,int[] sizes) {
		for(int owner=selected.length-1;owner>=0;owner--) {
			if(++selected[owner]<sizes[owner]) return;
			selected[owner]=0;
		}
	}

	private static Check check(int site,int[] owners,MemoOptimizer.Predicate predicate) {
		return new Check(site,owners,r -> {
			for(int rule:r) if(rule<0) return Truth.UNKNOWN;
			return predicate.test(r);
		});
	}

	private static boolean[] covered(int owners) {
		boolean[] result=new boolean[owners]; Arrays.fill(result,true); return result;
	}

	private static double[][] zeros(int owners,int size) {
		double[][] result=new double[owners][];
		for(int owner=0;owner<owners;owner++) result[owner]=new double[size];
		return result;
	}

	private static void restoreProperty(String name,String value) {
		if(value==null) System.clearProperty(name);
		else System.setProperty(name,value);
	}
}
