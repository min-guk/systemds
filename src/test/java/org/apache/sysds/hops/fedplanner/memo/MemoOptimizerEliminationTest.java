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

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Status;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

/** Exact-solver behavior locks for cyclic factor elimination. */
public class MemoOptimizerEliminationTest {
	@Test public void seededCyclicFactorsMatchBruteForceWithUnsortedScopes() {
		Random random=new Random(0x5EEDBEEFL);
		for(int fixture=0;fixture<24;fixture++) {
			double[][] nativeCosts=new double[5][3];
			for(double[] row:nativeCosts) for(int ordinal=0;ordinal<row.length;ordinal++)
				row[ordinal]=(random.nextInt(17)-5)*0.1;
			boolean[][] edge01=randomRelation(random,3);
			boolean[][] edge12=randomRelation(random,3);
			boolean[][] edge20=randomRelation(random,3);
			boolean[][][] ternary=randomRelation3(random,3);
			List<Check> checks=List.of(
				relation(10,new int[] {1,0},edge01),
				relation(11,new int[] {2,1},edge12),
				relation(12,new int[] {0,2},edge20),
				relation3(13,new int[] {4,1,3},ternary));
			List<CostTerm> terms=List.of(
				new CostTerm(new int[] {2,0},r -> (r[0]-r[1])*0.2,-0.4),
				new CostTerm(new int[] {4,1,3},r -> (2*r[0]+r[1]-r[2])*0.05,-0.1));
			Result expected=bruteForce(nativeCosts,checks,terms);
			for(int cache:new int[] {0,4096}) {
				Result actual=solve(nativeCosts,checks,terms,Long.MAX_VALUE,cache);
				assertSame("fixture="+fixture+", cache="+cache,expected,actual);
			}
		}
	}

	@Test public void classCompressedMessagePreservesNativeCostsAndEarlierDescendantTie() {
		double[][] nativeCosts={{0,0},{0,0},{4,3,0},{0,0},{0,0}};
		Check inverseLeaf=new Check(50,new int[] {0,1},r -> partial(r) ? Truth.UNKNOWN
			: r[0]!=r[1] ? Truth.YES : Truth.NO);
		List<Check> compressedChecks=List.of(inverseLeaf,
			new Check(51,new int[] {1,2},r -> Truth.YES,new int[][] {{0,0},{0,0,0}}),
			new Check(52,new int[] {2,3},r -> Truth.YES,new int[][] {{0,0,0},{0,0}}),
			new Check(53,new int[] {3,1},r -> Truth.YES,new int[][] {{0,0},{0,0}}),
			new Check(54,new int[] {2,4},r -> Truth.YES,new int[][] {{0,0,0},{0,0}}));
		List<Check> plainChecks=List.of(inverseLeaf,edge(1,2),edge(2,3),edge(3,1),edge(2,4));
		CostTerm compressed=new CostTerm(new int[] {4,2,3},r -> r[0]+(r[1]==0 ? 0.5 : 0)+r[2],0,
			new int[][] {{0,1},{0,1,1},{0,1}});
		CostTerm plain=new CostTerm(new int[] {4,2,3},r -> r[0]+(r[1]==0 ? 0.5 : 0)+r[2],0);
		Result actual=solve(nativeCosts,compressedChecks,List.of(compressed),Long.MAX_VALUE,4096);
		assertSame("class-compressed message",
			bruteForce(nativeCosts,compressedChecks,List.of(compressed)),actual);
		Result uncompressed=solve(nativeCosts,plainChecks,List.of(plain),Long.MAX_VALUE,4096);
		assertSame("compressed and ordinal messages",uncompressed,actual);
		Assert.assertEquals("native cost must distinguish ordinals sharing a factor class",2,
			actual.selectedRules()[2]);
		Assert.assertEquals("earlier descendant controls the global equal-cost tie",0,
			actual.selectedRules()[0]);
		Assert.assertTrue(actual.statistics().eliminationVariables()>0);
		Assert.assertTrue("certified classes must reduce exact multilevel message work",
			actual.statistics().eliminationCells()<uncompressed.statistics().eliminationCells());
	}

	@Test public void unannotatedCyclicFactorsUseExactOrdinalElimination() {
		double[][] nativeCosts={{2,0},{0,1},{1,0}};
		List<Check> checks=List.of(edge(0,1),edge(1,2),edge(2,0));
		CostTerm term=new CostTerm(new int[] {2,0,1},r -> r[0]==r[1] ? 0.125 : 2,0.125);
		Result actual=solve(nativeCosts,checks,List.of(term),Long.MAX_VALUE,4096);
		assertSame("unannotated factors",bruteForce(nativeCosts,checks,List.of(term)),actual);
		Assert.assertTrue(actual.statistics().eliminationVariables()>0);
		Assert.assertTrue(actual.statistics().eliminationCells()>0);
		Assert.assertTrue(actual.statistics().eliminationRows()>0);
	}

	@Test public void unknownEncounteredDuringEliminationNeverPublishesAFalseProof() {
		Check unknownTuple=new Check(60,new int[] {3,4,5},r -> partial(r) ? Truth.UNKNOWN
			: r[0]==1 && r[1]==1 && r[2]==1 ? Truth.UNKNOWN : Truth.YES);
		double[][] nativeCosts=new double[6][2];
		for(int owner=3;owner<6;owner++) nativeCosts[owner][1]=-1;
		CostTerm firstComponent=new CostTerm(new int[] {0,1,2},r -> r[0]+r[1]+r[2],0);
		CostTerm secondComponent=new CostTerm(new int[] {3,4,5},r -> 0,0);
		Result result=solve(nativeCosts,List.of(unknownTuple),
			List.of(firstComponent,secondComponent),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.UNRESOLVED,result.status());
		Assert.assertTrue("UNKNOWN is reached only after an independent bucket chain completed",
			result.statistics().eliminationVariables()>=3);
	}

	@Test public void excessiveEliminationWidthFallsBackToTheExactLegacySolver() {
		int width=80;
		double[][] nativeCosts=new double[3][width];
		for(double[] row:nativeCosts) Arrays.fill(row,1);
		for(double[] row:nativeCosts) row[0]=0;
		Check wide=new Check(70,new int[] {0,1,2},r -> partial(r) ? Truth.UNKNOWN : Truth.YES);
		Result result=solve(nativeCosts,List.of(wide),List.of(),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertArrayEquals(new int[3],result.selectedRules());
		Assert.assertEquals("rejected preflight must not partially run elimination",0,
			result.statistics().eliminationVariables());
	}

	@Test public void sparseActualOrdinalAboveSixtyFourSurvivesElimination() {
		double[][] nativeCosts={new double[70],new double[2],new double[2]};
		Arrays.fill(nativeCosts[0],100);
		nativeCosts[0][68]=0;
		List<Check> cycle=List.of(edge(0,1),edge(1,2),edge(2,0));
		CostTerm retainWideOwner=new CostTerm(new int[] {2,0},r -> r[0]+0.01*r[1],0);
		Result result=solve(nativeCosts,cycle,List.of(retainWideOwner),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertEquals(68,result.selectedRules()[0]);
		Assert.assertTrue(result.statistics().eliminationVariables()>0);
	}

	@Test public void disconnectedCyclesAndEarlierLeafKeepGlobalLexicographicTie() {
		List<Check> checks=List.of(
			edge(1,3),edge(3,5),edge(5,1),edge(0,1),
			edge(2,4),edge(4,6),edge(6,2),edge(6,7));
		Result result=solve(new double[8][2],checks,List.of(),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,result.status());
		Assert.assertEquals(BigDecimal.ZERO,result.cost());
		Assert.assertArrayEquals(new int[8],result.selectedRules());
	}

	@Test public void sharedMultiOwnerTermIsPaidOnceWithExactBinary64Arithmetic() {
		double[][] nativeCosts={{0,0.1},{0,-0.2},{0,0.3},{0,0}};
		List<Check> cycle=List.of(edge(0,1),edge(1,2),edge(2,0),edge(2,3));
		CostTerm shared=new CostTerm(new int[] {2,0,1},r ->
			r[1]==1 && (r[0]==1 || r[2]==1) ? -0.7 : 0,-0.7);
		Result expected=bruteForce(nativeCosts,cycle,List.of(shared));
		Result actual=solve(nativeCosts,cycle,List.of(shared),Long.MAX_VALUE,4096);
		assertSame("shared factor",expected,actual);
		Assert.assertTrue(actual.cost().compareTo(BigDecimal.ZERO)<0);
	}

	@Test public void propagatedNonzeroSingletonPreservesOriginalOrdinalsAndCost() {
		double[][] nativeCosts={{9,8,0.1},{0,0.2},{0,0.3},{0,0.4}};
		Check fixTwo=new Check(20,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==2 ? Truth.YES : Truth.NO);
		Check ordered=new Check(21,new int[] {2,0,1},r -> partial(r) ? Truth.UNKNOWN
			: r[1]==2 && r[0]==r[2] ? Truth.YES : Truth.NO);
		List<Check> checks=List.of(fixTwo,ordered,edge(1,2),edge(2,3),edge(3,1));
		CostTerm term=new CostTerm(new int[] {3,0,1},r -> -0.25+0.125*r[0]+r[1]+0.5*r[2],-0.25);
		assertSame("propagated singleton",bruteForce(nativeCosts,checks,List.of(term)),
			solve(nativeCosts,checks,List.of(term),Long.MAX_VALUE,4096));
	}

	@Test public void unknownCandidateBlocksProofOnlyWhenItCanStillWin() {
		Check maybe=new Check(30,new int[] {0},r -> r[0]<0 ? Truth.UNKNOWN
			: r[0]==0 ? Truth.YES : Truth.UNKNOWN);
		Assert.assertEquals(Status.UNRESOLVED,solve(new double[][] {{1,0}},List.of(maybe),
			List.of(),Long.MAX_VALUE,4096).status());
		Result dominated=solve(new double[][] {{0,10}},List.of(maybe),List.of(),Long.MAX_VALUE,4096);
		Assert.assertEquals(Status.OPTIMAL,dominated.status());
		Assert.assertArrayEquals(new int[] {0},dominated.selectedRules());
		Assert.assertEquals(BigDecimal.ZERO,dominated.cost());
	}

	@Test public void finiteBudgetRetainsExistingResourceExhaustionContract() {
		List<Check> checks=new ArrayList<>();
		for(int owner=0;owner<8;owner++) checks.add(edge(owner,(owner+1)%8));
		List<CostTerm> terms=new ArrayList<>();
		for(int owner=0;owner<8;owner++) {
			int next=(owner+1)%8;
			terms.add(new CostTerm(new int[] {owner,next},r -> r[0]==r[1] ? 0 : 1,0));
		}
		Result exact=solve(new double[8][2],checks,terms,Long.MAX_VALUE,4096);
		Result legacy=solve(new double[8][2],checks,terms,Long.MAX_VALUE-1,4096);
		assertSame("default and legacy exact paths",exact,legacy);
		Assert.assertTrue(legacy.statistics().nodes()>1);
		assertSame("legacy observed node budget",legacy,
			solve(new double[8][2],checks,terms,legacy.statistics().nodes(),4096));
		Assert.assertEquals(Status.RESOURCE_EXHAUSTED,solve(new double[8][2],checks,terms,
			legacy.statistics().nodes()-1,4096).status());
	}

	@Test public void certifiedClassesDoNotMergeDifferentNativeOrLegalityChoices() {
		double[][] nativeCosts={{4,0,2},{0,1},{0,0}};
		Check legal=new Check(40,new int[] {0,1},r -> partial(r) ? Truth.UNKNOWN
			: r[0]!=0 && r[1]==0 ? Truth.NO : Truth.YES,
			new int[][] {{0,1,1},{0,1}});
		CostTerm classified=new CostTerm(new int[] {2,0,1},r ->
			0.1*r[0]+(r[1]==0 ? 0.2 : 0.4)+0.3*r[2],0,
			new int[][] {{0,1},{0,1,1},{0,1}});
		CostTerm plain=new CostTerm(new int[] {2,0,1},r ->
			0.1*r[0]+(r[1]==0 ? 0.2 : 0.4)+0.3*r[2],0);
		List<Check> cycle=List.of(legal,edge(0,1),edge(1,2),edge(2,0));
		Result expected=solve(nativeCosts,cycle,List.of(plain),Long.MAX_VALUE,4096);
		Result actual=solve(nativeCosts,cycle,List.of(classified),Long.MAX_VALUE,4096);
		assertSame("certified classes",expected,actual);
		Assert.assertArrayEquals(new int[] {1,1,0},actual.selectedRules());
	}

	private static Check relation(int site,int[] owners,boolean[][] allowed) {
		return new Check(site,owners,r -> partial(r) ? Truth.UNKNOWN
			: allowed[r[0]][r[1]] ? Truth.YES : Truth.NO);
	}

	private static Check relation3(int site,int[] owners,boolean[][][] allowed) {
		return new Check(site,owners,r -> partial(r) ? Truth.UNKNOWN
			: allowed[r[0]][r[1]][r[2]] ? Truth.YES : Truth.NO);
	}

	private static Check edge(int left,int right) {
		return new Check(left,new int[] {left,right},r -> Truth.YES);
	}

	private static boolean partial(int[] values) {
		return Arrays.stream(values).anyMatch(value -> value<0);
	}

	private static boolean[][] randomRelation(Random random,int size) {
		boolean[][] result=new boolean[size][size];
		for(boolean[] row:result) for(int right=0;right<size;right++)
			row[right]=random.nextInt(4)!=0;
		return result;
	}

	private static boolean[][][] randomRelation3(Random random,int size) {
		boolean[][][] result=new boolean[size][size][size];
		for(boolean[][] plane:result) for(boolean[] row:plane) for(int third=0;third<size;third++)
			row[third]=random.nextInt(5)!=0;
		return result;
	}

	private static Result solve(double[][] nativeCosts,List<Check> checks,List<CostTerm> terms,
		long nodes,int cache) {
		int[] sizes=Arrays.stream(nativeCosts).mapToInt(row -> row.length).toArray();
		boolean[] coverage=new boolean[sizes.length]; Arrays.fill(coverage,true);
		return new MemoOptimizer(sizes,coverage,nativeCosts,checks,terms,
			new Limits(nodes,256,cache)).optimize();
	}

	private static Result bruteForce(double[][] nativeCosts,List<Check> checks,List<CostTerm> terms) {
		int[] selected=new int[nativeCosts.length];
		Best best=new Best();
		enumerate(0,selected,nativeCosts,checks,terms,best);
		return new Result(best.rules==null ? Status.INFEASIBLE : Status.OPTIMAL,
			best.cost,best.rules,null);
	}

	private static void enumerate(int owner,int[] selected,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> terms,Best best) {
		if(owner<selected.length) {
			for(int rule=0;rule<nativeCosts[owner].length;rule++) {
				selected[owner]=rule;
				enumerate(owner+1,selected,nativeCosts,checks,terms,best);
			}
			return;
		}
		for(Check check:checks) {
			int[] tuple=Arrays.stream(check.owners()).map(index -> selected[index]).toArray();
			if(predicate(check).test(tuple)!=Truth.YES) return;
		}
		BigDecimal cost=BigDecimal.ZERO;
		for(int index=0;index<selected.length;index++)
			cost=cost.add(new BigDecimal(nativeCosts[index][selected[index]]));
		for(CostTerm term:terms) {
			int[] tuple=Arrays.stream(term.owners()).map(index -> selected[index]).toArray();
			cost=cost.add(term.evaluateExact(tuple));
		}
		if(best.cost==null || cost.compareTo(best.cost)<0
			|| cost.compareTo(best.cost)==0 && lexLess(selected,best.rules)) {
			best.cost=cost; best.rules=selected.clone();
		}
	}

	private static MemoOptimizer.Predicate predicate(Check check) {
		try {
			java.lang.reflect.Field field=Check.class.getDeclaredField("predicate");
			field.setAccessible(true);
			return (MemoOptimizer.Predicate)field.get(check);
		}
		catch(ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static boolean lexLess(int[] left,int[] right) {
		if(right==null) return true;
		for(int index=0;index<left.length;index++)
			if(left[index]!=right[index]) return left[index]<right[index];
		return false;
	}

	private static void assertSame(String message,Result expected,Result actual) {
		Assert.assertEquals(message,expected.status(),actual.status());
		if(expected.cost()==null || actual.cost()==null)
			Assert.assertEquals(message,expected.cost(),actual.cost());
		else
			Assert.assertEquals(message,0,expected.cost().compareTo(actual.cost()));
		Assert.assertArrayEquals(message,expected.selectedRules(),actual.selectedRules());
	}

	private static final class Best {
		private BigDecimal cost;
		private int[] rules;
	}
}
