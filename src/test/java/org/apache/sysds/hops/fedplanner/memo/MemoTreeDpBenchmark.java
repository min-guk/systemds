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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;

/** Standalone, same-model planning benchmark; it does not execute a workload. */
public final class MemoTreeDpBenchmark {
	private static final int[] SIZES={3,2,3,2,2,3};
	private static final boolean[] COVERAGE={true,true,true,true,true,true};
	private static final double[][] NATIVE={{0.5,0.25,1},{0,0.75},{0.25,0,0.5},
		{1,0},{0,0.25},{0.5,0.25,0}};
	private static final List<Check> CHECKS=List.of(
		new Check(10,new int[] {0,1,2},r -> r[0]==2 && r[1]==0 && r[2]==1 ? Truth.NO : Truth.YES),
		new Check(11,new int[] {2,3},r -> r[0]==0 && r[1]==1 ? Truth.NO : Truth.YES),
		new Check(12,new int[] {3,4,0},r -> r[0]==0 && r[1]==1 && r[2]==1 ? Truth.NO : Truth.YES),
		new Check(13,new int[] {4,5},r -> r[0]==0 && r[1]==2 ? Truth.NO : Truth.YES));
	private static final List<CostTerm> COSTS=List.of(
		new CostTerm(new int[] {0,2,3},r -> r[0]==r[1] ? 0.125 : 2.125,0.125),
		new CostTerm(new int[] {0,1,2,4,5},r ->
			(r[0]+r[2]+r[4])%3==r[3] ? 0.0625 : 1.0625,0.0625),
		new CostTerm(new int[] {5,1},r -> r[0]==2*r[1] ? -0.25 : 0.5,-0.25));
	private record Problem(String name,int[] sizes,boolean[] coverage,double[][] nativeCosts,
		List<Check> checks,List<CostTerm> costs,boolean exhaustive) { }

	private MemoTreeDpBenchmark() { }

	public static void main(String[] args) {
		int repetitions=args.length==0 ? 7 : Integer.parseInt(args[0]);
		if(repetitions<1) throw new IllegalArgumentException("repetitions must be positive");
		for(Problem problem:problems()) benchmark(problem,repetitions);
		System.out.println("TREE_DP_BENCHMARK_OK");
	}

	private static void benchmark(Problem problem,int repetitions) {
		MemoOptimizer.Result exact=exact(problem);
		MemoTreeDpOptimizer.Result global=tree(problem,MemoTreeDpOptimizer.Mode.GLOBAL);
		MemoTreeDpOptimizer.Result local=tree(problem,MemoTreeDpOptimizer.Mode.LOCAL);
		BigDecimal oracle=problem.exhaustive() ? enumerate(problem) : exact.cost();
		if(exact.status()!=MemoOptimizer.Status.OPTIMAL || !global.certified() || !local.certified()
			|| exact.cost().compareTo(oracle)!=0 || exact.cost().compareTo(global.upperBound())!=0
			|| global.lowerBound().compareTo(global.upperBound())!=0
			|| local.lowerBound().compareTo(oracle)>0 || local.upperBound().compareTo(oracle)<0
			|| local.status()==MemoTreeDpOptimizer.Status.EPSILON_OPTIMAL
				&& (local.lowerBound().signum()<=0 || local.upperBound().compareTo(
					local.lowerBound().multiply(new BigDecimal("1.05")))>0))
			throw new IllegalStateException("same-model certificate mismatch: "+problem.name());
		List<String> algorithms=new ArrayList<>(List.of("EXACT","TREE_DP_GLOBAL","TREE_DP_LOCAL","MEMO_GLOBAL","MEMO_LOCAL"));
		if(problem.sizes().length<=8) algorithms.addAll(List.of("MEMO_GLOBAL_SEARCH","MEMO_LOCAL_SEARCH","MEMO_LOCAL_NO_SEED"));
		for(String algorithm:algorithms) {
			long nanos=0,peak=0,nodes=0,conflicts=0,dpNanos=0,conflictNanos=0;
			BigDecimal lower=null,upper=null; String status=null;
			for(int repetition=-1;repetition<repetitions;repetition++) {
				resetPeaks(); long started=System.nanoTime();
				if("EXACT".equals(algorithm)) {
					MemoOptimizer.Result result=exact(problem);
					nanos+=repetition<0 ? 0 : System.nanoTime()-started;
					if(repetition>=0) { peak=Math.max(peak,peakBytes()); nodes+=result.statistics().nodes(); }
					lower=result.cost(); upper=result.cost(); status=result.status().name();
				}
				else if(algorithm.startsWith("MEMO_")) {
					MemoDualModeOptimizer.Options options=MemoDualModeOptimizer.Options.defaults()
						.withDirectExact(!algorithm.endsWith("SEARCH") && !algorithm.endsWith("NO_SEED"));
					if(algorithm.endsWith("NO_SEED")) options=options.withSeedWorkLimit(0);
					MemoDualModeOptimizer.Result result=new MemoDualModeOptimizer(problem.sizes(),problem.coverage(),
						problem.nativeCosts(),problem.checks(),problem.costs(),algorithm.startsWith("MEMO_GLOBAL")
							? MemoDualModeOptimizer.Mode.GLOBAL : MemoDualModeOptimizer.Mode.LOCAL,options).optimize();
					nanos+=repetition<0 ? 0 : System.nanoTime()-started;
					if(!result.certified() || result.lowerBound().compareTo(oracle)>0 || result.upperBound().compareTo(oracle)<0
						|| algorithm.startsWith("MEMO_GLOBAL") && result.upperBound().compareTo(oracle)!=0
						|| result.status()==MemoOptimizer.Status.EPSILON_OPTIMAL && (result.lowerBound().signum()<=0
							|| result.upperBound().compareTo(result.lowerBound().multiply(new BigDecimal("1.05")))>0))
						throw new IllegalStateException("Dual-mode benchmark certificate mismatch: "+algorithm+" "+problem.name());
					if(repetition>=0) { peak=Math.max(peak,peakBytes()); nodes+=result.statistics().nodes();
						conflicts+=result.statistics().repairCalls(); dpNanos+=result.statistics().kernelNanos();
						conflictNanos+=result.statistics().searchNanos(); }
					lower=result.lowerBound(); upper=result.upperBound(); status=result.status().name();
				}
				else {
					MemoTreeDpOptimizer.Result result=tree(problem,"TREE_DP_GLOBAL".equals(algorithm)
						? MemoTreeDpOptimizer.Mode.GLOBAL : MemoTreeDpOptimizer.Mode.LOCAL);
					nanos+=repetition<0 ? 0 : System.nanoTime()-started;
					if(repetition>=0) { peak=Math.max(peak,peakBytes()); nodes+=result.statistics().nodes();
						conflicts+=result.statistics().conflicts(); dpNanos+=result.statistics().dpNanos();
						conflictNanos+=result.statistics().conflictNanos(); }
					lower=result.lowerBound(); upper=result.upperBound(); status=result.status().name();
				}
			}
			System.out.println("TREE_DP_BENCHMARK|case="+problem.name()+"|owners="+problem.sizes().length
				+"|oracle="+(problem.exhaustive() ? "enumeration" : "memo-exact")
				+"|algorithm="+algorithm+"|status="+status
				+"|repetitions="+repetitions+"|meanNanos="+(nanos/repetitions)
				+"|peakPoolBytes="+peak+"|meanNodes="+(nodes/repetitions)
				+"|meanConflicts="+(conflicts/repetitions)+"|meanDpNanos="+(dpNanos/repetitions)
				+"|meanConflictNanos="+(conflictNanos/repetitions)
				+"|lowerBound="+lower+"|upperBound="+upper);
		}
	}

	private static MemoOptimizer.Result exact(Problem problem) {
		return new MemoOptimizer(problem.sizes(),problem.coverage(),problem.nativeCosts(),
			problem.checks(),problem.costs(),
			MemoOptimizer.Limits.exactDefaults()).optimize();
	}
	private static MemoTreeDpOptimizer.Result tree(Problem problem,MemoTreeDpOptimizer.Mode mode) {
		return new MemoTreeDpOptimizer(problem.sizes(),problem.coverage(),problem.nativeCosts(),
			problem.checks(),problem.costs(),mode,
			MemoTreeDpOptimizer.Limits.defaults()).optimize();
	}
	private static List<Problem> problems() {
		return List.of(new Problem("adversarial-shared-wide",SIZES,COVERAGE,NATIVE,CHECKS,COSTS,true),
			graph("tree-32",32,false,false),graph("cycle-16",16,true,false),
			graph("diamond-8",8,false,true),wideProblem(),epsilonBoundary());
	}
	private static Problem graph(String name,int owners,boolean cycle,boolean diamond) {
		int[] sizes=new int[owners]; Arrays.fill(sizes,2);
		boolean[] coverage=new boolean[owners]; Arrays.fill(coverage,true);
		double[][] nativeCosts=new double[owners][2];
		for(int owner=0;owner<owners;owner++) {
			nativeCosts[owner][0]=(owner%3+1)*0.125;
			nativeCosts[owner][1]=(owner%5+1)*0.0625;
		}
		List<Check> checks=new ArrayList<>(); List<CostTerm> costs=new ArrayList<>();
		for(int owner=0;owner<owners-1;owner++) {
			int left=owner,right=owner+1;
			checks.add(new Check(100+owner,new int[] {left,right},r ->
				left%4==0 && r[0]==1 && r[1]==1 ? Truth.NO : Truth.YES));
			costs.add(new CostTerm(new int[] {left,right},r -> r[0]==r[1] ? 0.03125 : 0.15625,0.03125));
		}
		if(cycle) costs.add(new CostTerm(new int[] {owners-1,0},r -> r[0]==r[1] ? 0 : 0.25,0));
		if(diamond) {
			costs.add(new CostTerm(new int[] {0,2},r -> r[0]==r[1] ? 0 : 0.125,0));
			costs.add(new CostTerm(new int[] {1,3},r -> r[0]==r[1] ? 0 : 0.125,0));
		}
		return new Problem(name,sizes,coverage,nativeCosts,List.copyOf(checks),List.copyOf(costs),owners<=16);
	}
	private static Problem wideProblem() {
		Problem base=graph("shared-wide-8",8,false,false);
		List<Check> checks=new ArrayList<>(base.checks());
		checks.add(new Check(900,new int[] {0,2,4,6},r ->
			r[0]+r[1]+r[2]+r[3]==4 ? Truth.NO : Truth.YES));
		List<CostTerm> costs=new ArrayList<>(base.costs());
		costs.add(new CostTerm(new int[] {0,2,3,5,7},r ->
			(r[0]+2*r[1]+r[2]+2*r[3]+r[4])%3==0 ? 0.0625 : 0.5625,0.0625));
		return new Problem(base.name(),base.sizes(),base.coverage(),base.nativeCosts(),checks,costs,true);
	}
	private static Problem epsilonBoundary() {
		return new Problem("epsilon-boundary-3",new int[] {2,2,2},new boolean[] {true,true,true},
			new double[][] {{100,100},{0,0},{0,0}},List.of(),List.of(
				new CostTerm(new int[] {0,1},r -> 0,0),
				new CostTerm(new int[] {1,2},r -> 0,0),
				new CostTerm(new int[] {2,0},r -> r[0]==0 && r[1]==0 ? 5 : 0,0)),true);
	}
	private static BigDecimal enumerate(Problem problem) {
		long combinations=1; for(int size:problem.sizes()) combinations=Math.multiplyExact(combinations,size);
		if(combinations>1_000_000) throw new IllegalArgumentException("enumeration case too large");
		BigDecimal best=null; int[] selected=new int[problem.sizes().length];
		for(long ordinal=0;ordinal<combinations;ordinal++) {
			boolean legal=true;
			for(Check check:problem.checks()) {
				int[] owners=check.owners(),tuple=new int[owners.length];
				for(int i=0;i<owners.length;i++) tuple[i]=selected[owners[i]];
				if(check.evaluate(tuple)!=Truth.YES) { legal=false; break; }
			}
			if(legal) {
				BigDecimal cost=BigDecimal.ZERO;
				for(int owner=0;owner<selected.length;owner++)
					cost=cost.add(BigDecimal.valueOf(problem.nativeCosts()[owner][selected[owner]]));
				for(CostTerm term:problem.costs()) {
					int[] owners=term.owners(),tuple=new int[owners.length];
					for(int i=0;i<owners.length;i++) tuple[i]=selected[owners[i]];
					cost=cost.add(term.evaluateExact(tuple));
				}
				if(best==null || cost.compareTo(best)<0) best=cost;
			}
			for(int owner=selected.length-1;owner>=0;owner--)
				if(++selected[owner]<problem.sizes()[owner]) break; else selected[owner]=0;
		}
		return best;
	}
	private static void resetPeaks() {
		for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())
			if(pool.isValid()) try { pool.resetPeakUsage(); } catch(UnsupportedOperationException ignored) { }
	}
	private static long peakBytes() {
		return ManagementFactory.getMemoryPoolMXBeans().stream().filter(MemoryPoolMXBean::isValid)
			.map(MemoryPoolMXBean::getPeakUsage).filter(usage -> usage!=null)
			.mapToLong(usage -> Math.max(0,usage.getUsed())).sum();
	}
}
