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
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;

/** Optional exact small-region tables over one immutable invocation. Numeric
 * primitives have disjoint ownership, although group scopes may overlap.
 * Contained checks only strengthen a relaxation: UNKNOWN remains admissible.
 * Incomplete tables are never published. Eviction restores independent floors. */
final class MemoClusterBounds {
	public record Statistics(long groups,long tableCells,long queries,long cacheHits,long bytes,
		long buildNanos,long queryNanos) { }
	private final List<Group> groups=new ArrayList<>();
	private long tableCells,queries,cacheHits,bytes,buildNanos,queryNanos;

	MemoClusterBounds(int[] sizes,BigDecimal[][] nativeCosts,List<Check> checks,List<CostTerm> costs,
		BitSet[] rootDomains,int maxCells,MemoResourcePolicy resources) {
		if(maxCells<0) throw new IllegalArgumentException("Negative cluster cell budget");
		long started=System.nanoTime();
		if(maxCells==0) return;
		long preparation=128L+8L*sizes.length+32L*(costs.size()+checks.size());
		for(CostTerm cost:costs) preparation=MemoResourcePolicy.checkedAdd(preparation,
			64L+4L*cost.owners().length+8L*((sizes.length+63L)/64L));
		for(Check check:checks) preparation=MemoResourcePolicy.checkedAdd(preparation,
			64L+4L*check.owners().length+8L*((sizes.length+63L)/64L));
		if(!resources.admit(preparation)) return;
		MemoResourcePolicy tableResources=resources.withResidentBytes(preparation);
		int[] selected=new int[sizes.length]; Arrays.fill(selected,-1);
		int[][] costScopes=costs.stream().map(CostTerm::owners).toArray(int[][]::new);
		int[][] checkScopes=checks.stream().map(Check::owners).toArray(int[][]::new);
		List<BitSet> seeds=new ArrayList<>();
		for(int[] scope:costScopes) seeds.add(bits(scope));
		for(int[] scope:checkScopes) seeds.add(bits(scope));
		seeds.sort(Comparator.comparingInt(BitSet::cardinality).reversed());
		boolean[] ownedTerms=new boolean[costs.size()],ownedNative=new boolean[sizes.length];
		for(BitSet seed:seeds) {
			int available=maxCells-(int)tableCells;
			if(available<=0) break;
			BitSet region=(BitSet)seed.clone();
			if(region.isEmpty() || cells(region,rootDomains,available)>available) continue;
			// Grow only through still-unowned numeric terms; no whole-model search.
			for(int id=0;id<costScopes.length;id++) if(!ownedTerms[id] && intersectsLive(region,costScopes[id],rootDomains)) {
				BitSet union=(BitSet)region.clone(); for(int owner:costScopes[id]) union.set(owner);
				if(cells(union,rootDomains,available)<=available) region=union;
			}
			int[] scope=region.stream().toArray();
			List<Integer> nativeIds=new ArrayList<>(),termIds=new ArrayList<>(),checkIds=new ArrayList<>();
			for(int owner:scope) if(!ownedNative[owner]) nativeIds.add(owner);
			for(int id=0;id<costScopes.length;id++) if(!ownedTerms[id] && contains(region,costScopes[id])) termIds.add(id);
			if(nativeIds.isEmpty() && termIds.isEmpty()) continue;
			for(int id=0;id<checkScopes.length;id++) if(contains(region,checkScopes[id])) checkIds.add(id);
			int rows=(int)cells(region,rootDomains,available);
			long estimate=512L+128L*rows+64L*scope.length+8L*(nativeIds.size()+termIds.size()+checkIds.size());
			for(int owner:scope) estimate=MemoResourcePolicy.checkedAdd(estimate,
				48L+4L*rootDomains[owner].cardinality()+16L*((sizes[owner]+63L)/64L));
			if(!tableResources.admit(bytes,estimate)) continue;
			Group group=new Group(scope,nativeIds.stream().mapToInt(Integer::intValue).toArray(),
				termIds.stream().mapToInt(Integer::intValue).toArray(),rootDomains,rows);
			for(int row=0;row<rows;row++) {
				group.select(row,selected);
				boolean allowed=true;
				for(int id:checkIds) if(checks.get(id).evaluate(project(checkScopes[id],selected))==Truth.NO) { allowed=false; break; }
				if(!allowed) continue;
				BigDecimal value=BigDecimal.ZERO;
				for(int owner:group.nativeIds) value=value.add(nativeCosts[owner][selected[owner]]);
				for(int id:group.termIds) {
					BigDecimal term=costs.get(id).evaluateExact(project(costScopes[id],selected));
					if(term.compareTo(costs.get(id).certifiedMinimum())<0)
						throw new IllegalStateException("Cluster cost below certified floor");
					value=value.add(term);
				}
				group.values[row]=value;
			}
			// Only fully evaluated messages acquire numeric ownership.
			groups.add(group); bytes+=estimate; tableCells+=rows;
			for(int owner:group.nativeIds) ownedNative[owner]=true;
			for(int id:group.termIds) ownedTerms[id]=true;
		}
		buildNanos=System.nanoTime()-started;
	}

	BigDecimal bound(BitSet[] domains,BigDecimal[] nativeMinima,BigDecimal[] termMinima) {
		long started=System.nanoTime(); queries++;
		try {
			BigDecimal total=BigDecimal.ZERO;
			for(BigDecimal value:nativeMinima) total=total.add(value);
			for(BigDecimal value:termMinima) total=total.add(value);
			for(Group group:groups) {
				BigDecimal independent=BigDecimal.ZERO;
				for(int id:group.nativeIds) independent=independent.add(nativeMinima[id]);
				for(int id:group.termIds) independent=independent.add(termMinima[id]);
				long[][] key=new long[group.scope.length][];
				for(int axis=0;axis<group.scope.length;axis++) {
					BitSet domain=domains[group.scope[axis]];
					BitSet outside=(BitSet)domain.clone(); outside.andNot(group.rootDomains[axis]);
					if(!outside.isEmpty()) throw new IllegalArgumentException("Cluster query widens root domain");
					key[axis]=domain.toLongArray();
				}
				BigDecimal best;
				if(Arrays.deepEquals(key,group.lastDomains)) { cacheHits++; best=group.lastBound; }
				else {
					best=null;
					for(int row=0;row<group.values.length;row++) {
						BigDecimal value=group.values[row]; if(value==null) continue;
						int rest=row; boolean live=true;
						for(int axis=group.scope.length-1;axis>=0;axis--) {
							int[] ordinals=group.live[axis];
							if(!domains[group.scope[axis]].get(ordinals[rest%ordinals.length])) { live=false; break; }
							rest/=ordinals.length;
						}
						if(live && (best==null || value.compareTo(best)<0)) best=value;
					}
					group.lastDomains=key; group.lastBound=best;
				}
				// An empty local relaxation could prove infeasibility. Keep the old
				// finite bound instead and let the original checks close that region.
				if(best!=null) total=total.add(best.max(independent).subtract(independent));
			}
			return total;
		}
		finally { queryNanos+=System.nanoTime()-started; }
	}
	List<int[]> scopes() { return groups.stream().map(g -> g.scope.clone()).toList(); }
	Statistics statistics() { return new Statistics(groups.size(),tableCells,queries,cacheHits,bytes,buildNanos,queryNanos); }
	long retainedBytes() { return bytes; }
	void clear() { groups.clear(); bytes=0; }

	private static long cells(BitSet scope,BitSet[] domains,long cap) {
		long product=1;
		for(int owner=scope.nextSetBit(0);owner>=0;owner=scope.nextSetBit(owner+1)) {
			int count=domains[owner].cardinality();
			if(count==0) return cap+1;
			if(product>cap/count) return cap+1;
			product*=count;
		}
		return product;
	}
	private static BitSet bits(int[] scope) { BitSet result=new BitSet(); for(int owner:scope) result.set(owner); return result; }
	private static boolean contains(BitSet scope,int[] values) { for(int owner:values) if(!scope.get(owner)) return false; return true; }
	private static boolean intersectsLive(BitSet scope,int[] values,BitSet[] domains) {
		for(int owner:values) if(scope.get(owner) && domains[owner].cardinality()>1) return true; return false;
	}
	private static int[] project(int[] scope,int[] selected) {
		int[] values=new int[scope.length]; for(int axis=0;axis<scope.length;axis++) values[axis]=selected[scope[axis]]; return values;
	}
	private static final class Group {
		private final int[] scope,nativeIds,termIds;
		private final int[][] live;
		private final BitSet[] rootDomains;
		private final BigDecimal[] values;
		private long[][] lastDomains;
		private BigDecimal lastBound;
		private Group(int[] scope,int[] nativeIds,int[] termIds,BitSet[] domains,int rows) {
			this.scope=scope; this.nativeIds=nativeIds; this.termIds=termIds;
			live=new int[scope.length][]; rootDomains=new BitSet[scope.length];
			for(int axis=0;axis<scope.length;axis++) {
				rootDomains[axis]=(BitSet)domains[scope[axis]].clone(); live[axis]=rootDomains[axis].stream().toArray();
			}
			values=new BigDecimal[rows];
		}
		private void select(int row,int[] selected) {
			for(int axis=scope.length-1;axis>=0;axis--) { selected[scope[axis]]=live[axis][row%live[axis].length]; row/=live[axis].length; }
		}
	}
}
