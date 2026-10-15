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
import java.util.BitSet;
import java.util.List;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

/** Independent exhaustive checks for optional clustered lower bounds. */
public class MemoClusterBoundsTest {
	@Test public void overlappingFactorsRaiseTheBoundWhenTheirSeparateMinimaConflict() {
		Fixture fixture=new Fixture(new int[] {2,2},new double[][] {{0,0},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==r[1] ? 0 : 10,0),
			new CostTerm(new int[] {0,1},r -> r[0]!=r[1] ? 0 : 10,0)));
		BitSet[] domains=domains(fixture.sizes());
		BigDecimal independent=independentBound(fixture,domains);
		MemoClusterBounds clusters=create(fixture,domains,64,new MemoResourcePolicy(Long.MAX_VALUE));
		BigDecimal grouped=clusters.bound(domains,nativeMinima(fixture,domains),termMinima(fixture,domains));

		Assert.assertEquals(0,BigDecimal.ZERO.compareTo(independent));
		Assert.assertEquals(0,new BigDecimal("10").compareTo(grouped));
		Assert.assertEquals(0,relaxedMinimum(fixture,domains).compareTo(grouped));
		Assert.assertTrue(clusters.statistics().groups()>0);
		Assert.assertTrue(clusters.statistics().tableCells()>0);
	}

	@Test public void signedHighOrderCostsChecksAndOriginalOrdinalsMatchExhaustiveRelaxation() {
		Fixture fixture=new Fixture(new int[] {3,2,2},
			new double[][] {{0,-2,1},{0,1},{0,0}},
			List.of(check(7,new int[] {0,1},r -> r[0]==1 && r[1]==0 ? Truth.NO : Truth.YES)),
			List.of(
				new CostTerm(new int[] {0,1,2},r -> r[0]==1 && r[1]==0 && r[2]==0 ? -6 : 4,-6),
				new CostTerm(new int[] {0,2},r -> r[0]==2 && r[1]==0 ? -5 : 3,-5),
				new CostTerm(new int[] {1,2},r -> r[0]==r[1] ? -1 : 2,-1)));
		BitSet[] domains=domains(fixture.sizes());
		MemoClusterBounds clusters=create(fixture,domains,128,new MemoResourcePolicy(Long.MAX_VALUE));
		BigDecimal bound=clusters.bound(domains,nativeMinima(fixture,domains),termMinima(fixture,domains));

		Assert.assertEquals("cluster enumeration must retain checks and original rule ordinals",
			0,relaxedMinimum(fixture,domains).compareTo(bound));
		Assert.assertTrue("joint enumeration should dominate separate signed minima",
			bound.compareTo(independentBound(fixture,domains))>0);
	}

	@Test public void unknownCheckRowsRemainInTheLowerBoundRelaxation() {
		Fixture fixture=new Fixture(new int[] {2,2},new double[][] {{0,0},{0,0}},
			List.of(new Check(9,new int[] {0,1},r -> {
				if(r[0]<0 || r[1]<0) return Truth.UNKNOWN;
				if(r[0]==0 && r[1]==0) return Truth.UNKNOWN;
				return r[0]==0 ? Truth.YES : Truth.NO;
			})),List.of(new CostTerm(new int[] {0,1},r -> r[0]==0 && r[1]==0 ? 0 : 5,0)));
		BitSet[] domains=domains(fixture.sizes());
		MemoClusterBounds clusters=create(fixture,domains,32,new MemoResourcePolicy(Long.MAX_VALUE));
		BigDecimal bound=clusters.bound(domains,nativeMinima(fixture,domains),termMinima(fixture,domains));

		Assert.assertEquals("UNKNOWN must be relaxed rather than rejected",0,BigDecimal.ZERO.compareTo(bound));
		Assert.assertEquals(0,relaxedMinimum(fixture,domains).compareTo(bound));
	}

	@Test public void cacheKeyIncludesTheFullConditionalGroupDomain() {
		Fixture fixture=new Fixture(new int[] {2,2},new double[][] {{0,0},{0,0}},List.of(),List.of(
			new CostTerm(new int[] {0,1},r -> r[0]==0 && r[1]==0 ? 0 : 4,0),
			new CostTerm(new int[] {0,1},r -> r[0]==1 && r[1]==1 ? 0 : 7,0)));
		BitSet[] root=domains(fixture.sizes());
		MemoClusterBounds clusters=create(fixture,root,64,new MemoResourcePolicy(Long.MAX_VALUE));
		BigDecimal rootBound=clusters.bound(root,nativeMinima(fixture,root),termMinima(fixture,root));
		clusters.bound(root,nativeMinima(fixture,root),termMinima(fixture,root));
		BitSet[] fixed=copy(root);
		fixed[0].clear(); fixed[0].set(0);
		BigDecimal fixedBound=clusters.bound(fixed,nativeMinima(fixture,fixed),termMinima(fixture,fixed));
		clusters.bound(fixed,nativeMinima(fixture,fixed),termMinima(fixture,fixed));

		Assert.assertEquals(0,new BigDecimal("4").compareTo(rootBound));
		Assert.assertEquals(0,new BigDecimal("7").compareTo(fixedBound));
		Assert.assertTrue("repeated root and conditional queries should hit distinct cached signatures",
			clusters.statistics().cacheHits()>=2);
	}

	@Test public void rejectedOptionalTablesFallBackToTheIndependentBound() {
		Fixture fixture=new Fixture(new int[] {3,3,3},new double[][] {{0,0,0},{0,0,0},{0,0,0}},
			List.of(),List.of(
				new CostTerm(new int[] {0,1,2},r -> Arrays.equals(r,new int[] {0,0,0}) ? 0 : 20,0),
				new CostTerm(new int[] {0,1,2},r -> Arrays.equals(r,new int[] {2,2,2}) ? 0 : 20,0)));
		BitSet[] domains=domains(fixture.sizes());
		MemoResourcePolicy resources=new MemoResourcePolicy(0);
		MemoClusterBounds clusters=create(fixture,domains,128,resources);
		BigDecimal bound=clusters.bound(domains,nativeMinima(fixture,domains),termMinima(fixture,domains));

		Assert.assertEquals(0,independentBound(fixture,domains).compareTo(bound));
		Assert.assertEquals(0,clusters.statistics().groups());
		Assert.assertEquals(0,clusters.retainedBytes());
		Assert.assertTrue(resources.stats().rejections()>0);
	}

	@Test public void overlappingGroupScopesNeverDoubleCountNativeOrFactorCosts() {
		Fixture fixture=new Fixture(new int[] {3,3,3},
			new double[][] {{0,0,0},{5,5,5},{0,0,0}},List.of(),List.of(
				new CostTerm(new int[] {0,1},r -> 0,0),
				new CostTerm(new int[] {1,2},r -> 0,0)));
		BitSet[] domains=domains(fixture.sizes());
		MemoClusterBounds clusters=create(fixture,domains,18,new MemoResourcePolicy(Long.MAX_VALUE));
		BigDecimal bound=clusters.bound(domains,nativeMinima(fixture,domains),termMinima(fixture,domains));

		Assert.assertEquals("the shared owner belongs to only one numeric group",
			0,new BigDecimal("5").compareTo(bound));
		Assert.assertEquals(2,clusters.statistics().groups());
		Assert.assertEquals(18,clusters.statistics().tableCells());
	}

	private record Fixture(int[] sizes,double[][] nativeCosts,List<Check> checks,List<CostTerm> terms) { }

	private static MemoClusterBounds create(Fixture fixture,BitSet[] root,int maxCells,
		MemoResourcePolicy resources) {
		return new MemoClusterBounds(fixture.sizes(),decimal(fixture.nativeCosts()),fixture.checks(),
			fixture.terms(),root,maxCells,resources);
	}

	private static BigDecimal relaxedMinimum(Fixture fixture,BitSet[] domains) {
		int[] selected=new int[fixture.sizes().length];
		Arrays.fill(selected,-1);
		return enumerate(fixture,domains,selected,0,null);
	}

	private static BigDecimal enumerate(Fixture fixture,BitSet[] domains,int[] selected,int owner,
		BigDecimal best) {
		if(owner==selected.length) {
			for(Check check:fixture.checks())
				if(check.evaluate(project(selected,check.owners()))==Truth.NO) return best;
			BigDecimal value=BigDecimal.ZERO;
			for(int i=0;i<selected.length;i++) value=value.add(exact(fixture.nativeCosts()[i][selected[i]]));
			for(CostTerm term:fixture.terms()) value=value.add(term.evaluateExact(project(selected,term.owners())));
			return best==null || value.compareTo(best)<0 ? value : best;
		}
		for(int rule=domains[owner].nextSetBit(0);rule>=0;rule=domains[owner].nextSetBit(rule+1)) {
			selected[owner]=rule;
			best=enumerate(fixture,domains,selected,owner+1,best);
		}
		selected[owner]=-1;
		return best;
	}

	private static BigDecimal independentBound(Fixture fixture,BitSet[] domains) {
		BigDecimal result=BigDecimal.ZERO;
		for(BigDecimal value:nativeMinima(fixture,domains)) result=result.add(value);
		for(BigDecimal value:termMinima(fixture,domains)) result=result.add(value);
		return result;
	}

	private static BigDecimal[] nativeMinima(Fixture fixture,BitSet[] domains) {
		BigDecimal[] result=new BigDecimal[fixture.sizes().length];
		for(int owner=0;owner<result.length;owner++)
			for(int rule=domains[owner].nextSetBit(0);rule>=0;rule=domains[owner].nextSetBit(rule+1)) {
				BigDecimal value=exact(fixture.nativeCosts()[owner][rule]);
				if(result[owner]==null || value.compareTo(result[owner])<0) result[owner]=value;
			}
		return result;
	}

	private static BigDecimal[] termMinima(Fixture fixture,BitSet[] domains) {
		BigDecimal[] result=new BigDecimal[fixture.terms().size()];
		for(int id=0;id<result.length;id++) {
			CostTerm term=fixture.terms().get(id);
			int[] tuple=new int[term.owners().length];
			result[id]=termMinimum(term,term.owners(),domains,tuple,0,null);
		}
		return result;
	}

	private static BigDecimal termMinimum(CostTerm term,int[] scope,BitSet[] domains,int[] tuple,
		int axis,BigDecimal best) {
		if(axis==scope.length) {
			BigDecimal value=term.evaluateExact(tuple);
			return best==null || value.compareTo(best)<0 ? value : best;
		}
		for(int rule=domains[scope[axis]].nextSetBit(0);rule>=0;
			rule=domains[scope[axis]].nextSetBit(rule+1)) {
			tuple[axis]=rule;
			best=termMinimum(term,scope,domains,tuple,axis+1,best);
		}
		return best;
	}

	private static Check check(int site,int[] owners,MemoOptimizer.Predicate predicate) {
		return new Check(site,owners,r -> {
			for(int rule:r) if(rule<0) return Truth.UNKNOWN;
			return predicate.test(r);
		});
	}

	private static BigDecimal[][] decimal(double[][] source) {
		BigDecimal[][] result=new BigDecimal[source.length][];
		for(int owner=0;owner<source.length;owner++) {
			result[owner]=new BigDecimal[source[owner].length];
			for(int rule=0;rule<source[owner].length;rule++) result[owner][rule]=exact(source[owner][rule]);
		}
		return result;
	}

	private static BigDecimal exact(double value) { return new BigDecimal(value); }

	private static int[] project(int[] selected,int[] owners) {
		int[] result=new int[owners.length];
		for(int i=0;i<owners.length;i++) result[i]=selected[owners[i]];
		return result;
	}

	private static BitSet[] domains(int[] sizes) {
		BitSet[] result=new BitSet[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) {
			result[owner]=new BitSet(sizes[owner]); result[owner].set(0,sizes[owner]);
		}
		return result;
	}

	private static BitSet[] copy(BitSet[] source) {
		return Arrays.stream(source).map(domain -> (BitSet)domain.clone()).toArray(BitSet[]::new);
	}
}
