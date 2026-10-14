package org.apache.sysds.hops.fedplanner.memo;

import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Result;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;

public class MemoOptimizerCheckStateTest {
	@Test
	public void invariantAxisKeepsActualOrdinalLexAndPartialUnknown() {
		int[] sizes={3,2}; boolean[] coverage={true,true};
		double[][] nativeCosts={{0,0,0},{0,0}};
		Check pruneZero=new Check(1,new int[] {0},v -> v[0]<0 ? Truth.UNKNOWN
			: v[0]==0 ? Truth.NO : Truth.YES);
		MemoOptimizer.Predicate relation=v -> v[0]<0 || v[1]<0 ? Truth.UNKNOWN : Truth.YES;
		Check plain=new Check(2,new int[] {0,1},relation);
		Check classified=new Check(2,new int[] {0,1},relation,
			new int[][] {{0,1,1},{0,1}});
		Result reference=solve(sizes,coverage,nativeCosts,List.of(pruneZero,plain),List.of(),Limits.exactDefaults());
		Result candidate=solve(sizes,coverage,nativeCosts,List.of(pruneZero,classified),List.of(),Limits.exactDefaults());
		assertSame(reference,candidate);
		Assert.assertArrayEquals(new int[] {1,0},candidate.selectedRules());
		Assert.assertEquals(1,candidate.statistics().invariantCheckAxes());
		Assert.assertEquals(1,candidate.statistics().binaryChecksReduced());
	}

	@Test
	public void completeUnknownIsNotPromotedByClassProjection() {
		int[] sizes={2,2}; boolean[] coverage={true,true}; double[][] costs={{0,0},{0,0}};
		MemoOptimizer.Predicate relation=v -> v[0]<0 || v[1]<0 ? Truth.UNKNOWN
			: v[1]==0 ? Truth.YES : Truth.UNKNOWN;
		Check plain=new Check(3,new int[] {0,1},relation);
		Check classified=new Check(3,new int[] {0,1},relation,new int[][] {{0,0},{0,1}});
		Result reference=solve(sizes,coverage,costs,List.of(plain),List.of(),Limits.exactDefaults());
		Result candidate=solve(sizes,coverage,costs,List.of(classified),List.of(),Limits.exactDefaults());
		assertSame(reference,candidate);
	}

	@Test
	public void groupedPartitionsRefineWithoutChangingTruth() {
		int[] sizes={4,2}; boolean[] coverage={true,true}; double[][] costs={{0,0,0,0},{0,0}};
		MemoOptimizer.Predicate first=v -> v[0]<0 || v[1]<0 ? Truth.UNKNOWN
			: (v[0]&1)==v[1] ? Truth.YES : Truth.NO;
		MemoOptimizer.Predicate second=v -> v[0]<0 || v[1]<0 ? Truth.UNKNOWN
			: (v[0]<2 || v[1]==1) ? Truth.YES : Truth.NO;
		List<Check> plain=List.of(new Check(4,new int[] {0,1},first),new Check(5,new int[] {0,1},second));
		List<Check> classes=List.of(
			new Check(4,new int[] {0,1},first,new int[][] {{0,1,0,1},{0,1}}),
			new Check(5,new int[] {0,1},second,new int[][] {{0,0,1,1},{0,1}}));
		for(int cache:new int[] {0,1,4096}) {
			Limits limits=new Limits(Long.MAX_VALUE,256,cache);
			assertSame(solve(sizes,coverage,costs,plain,List.of(),limits),
				solve(sizes,coverage,costs,classes,List.of(),limits));
		}
	}

	@Test
	public void finiteBudgetAndExactCostRemainIdentical() {
		int[] sizes={3,3}; boolean[] coverage={true,true}; double[][] costs={{0,0,0},{0,0,0}};
		MemoOptimizer.Predicate relation=v -> v[0]<0 || v[1]<0 ? Truth.UNKNOWN
			: v[0]%2==v[1]%2 ? Truth.YES : Truth.NO;
		CostTerm cost=new CostTerm(new int[] {0,1},v -> 0.25*v[0]+0.125*v[1],0);
		for(long nodes:new long[] {1,64}) {
			Limits limits=new Limits(nodes,2,4096);
			assertSame(solve(sizes,coverage,costs,List.of(new Check(6,new int[] {0,1},relation)),List.of(cost),limits),
				solve(sizes,coverage,costs,List.of(new Check(6,new int[] {0,1},relation,
					new int[][] {{0,1,0},{0,1,0}})),List.of(cost),limits));
		}
	}

	private static Result solve(int[] sizes,boolean[] coverage,double[][] nativeCosts,List<Check> checks,
		List<CostTerm> costs,Limits limits) {
		return new MemoOptimizer(sizes,coverage,nativeCosts,checks,costs,limits).optimize();
	}
	private static void assertSame(Result expected,Result actual) {
		Assert.assertEquals(expected.status(),actual.status());
		if(expected.cost()==null) Assert.assertNull(actual.cost());
		else Assert.assertEquals(0,expected.cost().compareTo(actual.cost()));
		Assert.assertTrue(Arrays.toString(actual.selectedRules()),
			Arrays.equals(expected.selectedRules(),actual.selectedRules()));
	}
}
