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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer;
import org.apache.sysds.hops.fedplanner.memo.MemoTreeDpOptimizer;
import org.apache.sysds.hops.fedplanner.memo.MemoDualModeOptimizer;

/** Equal-factor comparison with the legacy Regional optimizer. */
public final class MemoTreeDpRegionalBenchmark {
	private static final ExactCategoricalSolver.Limits LIMITS=
		new ExactCategoricalSolver.Limits(1_000_000,5_000_000);
	private MemoTreeDpRegionalBenchmark() { }

	public static void main(String[] args) {
		int[] sizes={3,2,3,2,2,3}; boolean[] coverage=new boolean[sizes.length];
		Arrays.fill(coverage,true);
		double[][] nativeCosts={{0.5,0.25,1},{0,0.75},{0.25,0,0.5},{1,0},{0,0.25},{0.5,0.25,0}};
		List<MemoOptimizer.Check> checks=List.of(
			new MemoOptimizer.Check(10,new int[] {0,1,2},r -> r[0]==2 && r[1]==0 && r[2]==1
				? MemoOptimizer.Truth.NO : MemoOptimizer.Truth.YES),
			new MemoOptimizer.Check(11,new int[] {2,3},r -> r[0]==0 && r[1]==1
				? MemoOptimizer.Truth.NO : MemoOptimizer.Truth.YES),
			new MemoOptimizer.Check(12,new int[] {3,4,0},r -> r[0]==0 && r[1]==1 && r[2]==1
				? MemoOptimizer.Truth.NO : MemoOptimizer.Truth.YES),
			new MemoOptimizer.Check(13,new int[] {4,5},r -> r[0]==0 && r[1]==2
				? MemoOptimizer.Truth.NO : MemoOptimizer.Truth.YES));
		List<MemoOptimizer.CostTerm> costs=List.of(
			new MemoOptimizer.CostTerm(new int[] {0,2,3},r -> movement(r),0.125),
			new MemoOptimizer.CostTerm(new int[] {0,1,2,4,5},r -> wide(r),0.0625),
			new MemoOptimizer.CostTerm(new int[] {5,1},r -> pair(r),0));
		MemoOptimizer.Result memo=new MemoOptimizer(sizes,coverage,nativeCosts,checks,costs,
			MemoOptimizer.Limits.exactDefaults()).optimize();
		MemoTreeDpOptimizer.Result tree=new MemoTreeDpOptimizer(sizes,coverage,nativeCosts,checks,costs,
			MemoTreeDpOptimizer.Mode.GLOBAL,MemoTreeDpOptimizer.Limits.defaults()).optimize();
		MemoDualModeOptimizer.Result dualGlobal=new MemoDualModeOptimizer(sizes,coverage,nativeCosts,checks,costs,
			MemoDualModeOptimizer.Mode.GLOBAL,MemoDualModeOptimizer.Options.defaults()).optimize();
		MemoDualModeOptimizer.Result dualLocal=new MemoDualModeOptimizer(sizes,coverage,nativeCosts,checks,costs,
			MemoDualModeOptimizer.Mode.LOCAL,MemoDualModeOptimizer.Options.defaults()).optimize();

		List<Variable> variables=new ArrayList<>();
		for(int owner=0;owner<sizes.length;owner++) variables.add(new Variable("owner-"+owner,sizes[owner]));
		List<Factor> hard=List.of(
			factor(variables,sizes,new int[] {0,1,2},r -> r[0]==2 && r[1]==0 && r[2]==1
				? Double.POSITIVE_INFINITY : 0),
			factor(variables,sizes,new int[] {2,3},r -> r[0]==0 && r[1]==1 ? Double.POSITIVE_INFINITY : 0),
			factor(variables,sizes,new int[] {3,4,0},r -> r[0]==0 && r[1]==1 && r[2]==1
				? Double.POSITIVE_INFINITY : 0),
			factor(variables,sizes,new int[] {4,5},r -> r[0]==0 && r[1]==2 ? Double.POSITIVE_INFINITY : 0));
		List<Factor> numeric=new ArrayList<>();
		for(int owner=0;owner<sizes.length;owner++)
			numeric.add(Factor.dense(List.of(variables.get(owner)),nativeCosts[owner]));
		numeric.add(factor(variables,sizes,new int[] {0,2,3},MemoTreeDpRegionalBenchmark::movement));
		numeric.add(factor(variables,sizes,new int[] {0,1,2,4,5},MemoTreeDpRegionalBenchmark::wide));
		numeric.add(factor(variables,sizes,new int[] {5,1},MemoTreeDpRegionalBenchmark::pair));
		List<Factor> all=new ArrayList<>(hard); all.addAll(numeric);
		ExactCategoricalSolver.Result categorical=ExactCategoricalSolver.solve(variables,all,LIMITS);
		long started=System.nanoTime();
		LocalCategoricalOptimizer.Result regional=LocalCategoricalOptimizer.optimize(
			variables,hard,numeric,variables,List.of(variables),ignored -> List.of(),
			(variable,value) -> value,0,true);
		long regionalNanos=System.nanoTime()-started;
		double expected=memo.cost().doubleValue();
		if(memo.status()!=MemoOptimizer.Status.OPTIMAL || tree.status()!=MemoTreeDpOptimizer.Status.OPTIMAL
			|| Double.compare(expected,categorical.objective())!=0
			|| Double.compare(expected,regional.objective())!=0
			|| tree.upperBound().compareTo(memo.cost())!=0
			|| !dualGlobal.certified() || !dualLocal.certified()
			|| dualGlobal.upperBound().compareTo(memo.cost())!=0
			|| dualLocal.upperBound().compareTo(memo.cost())!=0)
			throw new IllegalStateException("equal-factor solver mismatch");
		System.out.println("TREE_DP_REGIONAL_BENCHMARK|case=adversarial-shared-wide"
			+"|memoCost="+memo.cost()+"|treeCost="+tree.upperBound()
			+"|dualGlobalCost="+dualGlobal.upperBound()+"|dualLocalCost="+dualLocal.upperBound()
			+"|categoricalCost="+categorical.objective()+"|regionalCost="+regional.objective()
			+"|regionalNanos="+regionalNanos+"|regionalBlockAssignments="
			+regional.statistics().blockAssignments());
		System.out.println("TREE_DP_REGIONAL_BENCHMARK_OK");
	}

	private static Factor factor(List<Variable> variables,int[] sizes,int[] owners,
		java.util.function.ToDoubleFunction<int[]> evaluator) {
		List<Variable> scope=Arrays.stream(owners).mapToObj(variables::get).toList();
		int cells=1; for(int owner:owners) cells=Math.multiplyExact(cells,sizes[owner]);
		double[] values=new double[cells]; int[] tuple=new int[owners.length];
		for(int cell=0;cell<cells;cell++) {
			values[cell]=evaluator.applyAsDouble(tuple);
			for(int axis=tuple.length-1;axis>=0;axis--) {
				if(++tuple[axis]<sizes[owners[axis]]) break;
				tuple[axis]=0;
			}
		}
		return Factor.dense(scope,values);
	}
	private static double movement(int[] r) { return r[0]==r[1] ? 0.125 : 2.125; }
	private static double wide(int[] r) { return (r[0]+r[2]+r[4])%3==r[3] ? 0.0625 : 1.0625; }
	private static double pair(int[] r) { return r[0]==2*r[1] ? 0 : 0.75; }
}
