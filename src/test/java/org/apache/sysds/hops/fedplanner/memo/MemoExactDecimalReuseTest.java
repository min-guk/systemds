/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Limits;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.junit.Assert;
import org.junit.Test;

public class MemoExactDecimalReuseTest {
	@Test public void allFiniteBoundariesKeepExactValueAndScale() throws Exception {
		double[] values={0.0,-0.0,Double.MIN_VALUE,-Double.MIN_VALUE,Double.MIN_NORMAL,
			Math.nextDown(Double.MIN_NORMAL),Double.MAX_VALUE,-Double.MAX_VALUE,
			Math.PI,-Math.PI,1.0/3.0,0.1,-0.1,Math.nextUp(1.0),-0.125};
		MemoOptimizer optimizer=empty(64);
		for(double value:values) {
			BigDecimal expected=new BigDecimal(value),actual=convert(optimizer,value);
			Assert.assertEquals(expected,actual);
			Assert.assertEquals(expected.scale(),actual.scale());
			Assert.assertEquals(expected.precision(),actual.precision());
			Assert.assertSame(actual,convert(optimizer,value));
		}
		Assert.assertEquals(values.length,cache(optimizer).size());
		Assert.assertTrue(cache(optimizer).containsKey(Double.doubleToRawLongBits(0.0)));
		Assert.assertTrue(cache(optimizer).containsKey(Double.doubleToRawLongBits(-0.0)));
		MemoOptimizer nativeValues=new MemoOptimizer(new int[] {values.length},new boolean[] {true},
			new double[][] {values},List.of(),List.of(),limits(64));
		BigDecimal[][] actual=(BigDecimal[][])field(nativeValues,"nativeCosts");
		for(int i=0;i<values.length;i++) Assert.assertEquals(new BigDecimal(values[i]),actual[0][i]);
	}

	@Test public void repeatedValueNeverSkipsCallbackCloneOrLaterFailure() throws Exception {
		AtomicInteger calls=new AtomicInteger();
		IllegalStateException marker=new IllegalStateException("later evaluator failure");
		CostTerm term=new CostTerm(new int[] {0},tuple -> {
			int call=calls.incrementAndGet();
			Assert.assertArrayEquals(new int[] {1},tuple);
			tuple[0]=99;
			if(call==3) throw marker;
			return call==4 ? Double.NaN : 0.125;
		},0);
		MemoOptimizer optimizer=withTerm(new int[] {2},term,8);
		Function<int[],BigDecimal> evaluator=evaluator(optimizer);
		int[] caller={1};
		BigDecimal first=evaluator.apply(caller);
		Assert.assertSame(first,evaluator.apply(caller));
		int entries=cache(optimizer).size();
		Assert.assertSame(marker,Assert.assertThrows(IllegalStateException.class,() -> evaluator.apply(caller)));
		IllegalArgumentException invalid=Assert.assertThrows(IllegalArgumentException.class,
			() -> evaluator.apply(caller));
		Assert.assertEquals("Costs must be finite: NaN",invalid.getMessage());
		Assert.assertEquals(4,calls.get());
		Assert.assertArrayEquals(new int[] {1},caller);
		Assert.assertEquals(entries,cache(optimizer).size());
	}

	@Test public void zeroCapacityAndSaturationKeepExactResultsAndBoundedState() throws Exception {
		MemoOptimizer disabled=empty(0);
		Assert.assertEquals(new BigDecimal(0.1),convert(disabled,0.1));
		Assert.assertNotSame(convert(disabled,0.1),convert(disabled,0.1));
		Assert.assertTrue(cache(disabled).isEmpty());
		MemoOptimizer bounded=empty(2);
		BigDecimal first=convert(bounded,1.25),second=convert(bounded,2.5);
		Assert.assertEquals(new BigDecimal(3.75),convert(bounded,3.75));
		Assert.assertEquals(2,cache(bounded).size());
		Assert.assertFalse(cache(bounded).containsKey(Double.doubleToRawLongBits(3.75)));
		Assert.assertSame(first,convert(bounded,1.25));
		Assert.assertSame(second,convert(bounded,2.5));
		Assert.assertNotSame(convert(bounded,3.75),convert(bounded,3.75));
		Assert.assertEquals(4096,field(empty(Integer.MAX_VALUE),"exactValueCacheLimit"));
	}

	@Test public void nonFiniteValuesFailBeforeCachePublication() throws Exception {
		for(int capacity:new int[] {0,2}) {
			MemoOptimizer optimizer=empty(capacity);
			convert(optimizer,0.25);
			int before=cache(optimizer).size();
			for(double value:new double[] {Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
				IllegalArgumentException failure=Assert.assertThrows(IllegalArgumentException.class,
					() -> convert(optimizer,value));
				Assert.assertEquals("Costs must be finite: "+value,failure.getMessage());
				Assert.assertEquals(before,cache(optimizer).size());
			}
		}
	}

	@Test public void sharedTermAndReducedScopeKeepOptimizerStateIndependent() throws Exception {
		AtomicInteger calls=new AtomicInteger();
		CostTerm shared=new CostTerm(new int[] {0,1},tuple -> {
			calls.incrementAndGet();
			Assert.assertArrayEquals(new int[] {0,1},tuple);
			tuple[0]=7; tuple[1]=7;
			return -0.375;
		},-1);
		MemoOptimizer first=withTerm(new int[] {1,2},shared,8);
		MemoOptimizer second=withTerm(new int[] {1,2},shared,8);
		Assert.assertNotSame(cache(first),cache(second));
		int[] caller={1};
		BigDecimal a=evaluator(first).apply(caller),b=evaluator(second).apply(caller);
		Assert.assertEquals(new BigDecimal(-0.375),a);
		Assert.assertEquals(a,b);
		Assert.assertNotSame(a,b);
		Assert.assertSame(a,evaluator(first).apply(caller));
		Assert.assertEquals(3,calls.get());
		Assert.assertArrayEquals(new int[] {1},caller);
	}

	@Test public void constructorValidationPrecedenceIsUnchanged() {
		Check badCheck=new Check(0,new int[] {0},tuple -> Truth.YES,new int[][] {{0}});
		CostTerm badTerm=new CostTerm(new int[] {0},tuple -> 0,0,new int[][] {{0}});
		IllegalArgumentException truth=Assert.assertThrows(IllegalArgumentException.class,
			() -> new MemoOptimizer(new int[] {2},new boolean[] {true},new double[][] {{0,0}},
				List.of(badCheck),List.of(),null));
		Assert.assertEquals("Truth classes must match immutable rule domain",truth.getMessage());
		IllegalArgumentException cost=Assert.assertThrows(IllegalArgumentException.class,
			() -> new MemoOptimizer(new int[] {2},new boolean[] {true},new double[][] {{0,0}},
				List.of(),List.of(badTerm),null));
		Assert.assertEquals("Cost classes must match immutable rule domain",cost.getMessage());
		Assert.assertThrows(NullPointerException.class,
			() -> new MemoOptimizer(new int[] {2},new boolean[] {true},new double[][] {{0}},
				List.of(),List.of(),null));
		IllegalArgumentException invalid=Assert.assertThrows(IllegalArgumentException.class,
			() -> new MemoOptimizer(new int[] {1},new boolean[] {true},new double[][] {{Double.NaN}},
				List.of(),List.of(),limits(8)));
		Assert.assertEquals("Costs must be finite: NaN",invalid.getMessage());
	}

	@Test public void decimalReuseDoesNotSuppressCertifiedBoundFailure() {
		String expected=null;
		for(int capacity:new int[] {0,8}) {
			CostTerm bad=new CostTerm(new int[] {0},tuple -> 0.5,1);
			MemoOptimizer optimizer=withTerm(new int[] {2},bad,capacity);
			IllegalStateException failure=Assert.assertThrows(IllegalStateException.class,optimizer::optimize);
			Assert.assertTrue(failure.getMessage().startsWith("Cost below certified bound"));
			if(expected==null) expected=failure.getMessage();
			else Assert.assertEquals(expected,failure.getMessage());
		}
	}

	private static Limits limits(int capacity) { return new Limits(Long.MAX_VALUE,256,capacity); }
	private static MemoOptimizer empty(int capacity) {
		return new MemoOptimizer(new int[0],new boolean[0],new double[0][],List.of(),List.of(),limits(capacity));
	}
	private static MemoOptimizer withTerm(int[] domains,CostTerm term,int capacity) {
		boolean[] coverage=new boolean[domains.length];
		java.util.Arrays.fill(coverage,true);
		double[][] nativeCosts=new double[domains.length][];
		for(int i=0;i<domains.length;i++) nativeCosts[i]=new double[domains[i]];
		return new MemoOptimizer(domains,coverage,nativeCosts,List.of(),List.of(term),limits(capacity));
	}
	private static Object field(Object target,String name) throws Exception {
		Field field=target.getClass().getDeclaredField(name); field.setAccessible(true);
		return field.get(target);
	}
	@SuppressWarnings("unchecked")
	private static Map<Long,BigDecimal> cache(MemoOptimizer optimizer) throws Exception {
		return (Map<Long,BigDecimal>)field(optimizer,"exactValues");
	}
	@SuppressWarnings("unchecked")
	private static Function<int[],BigDecimal> evaluator(MemoOptimizer optimizer) throws Exception {
		Object factor=((List<?>)field(optimizer,"terms")).get(0);
		return (Function<int[],BigDecimal>)field(factor,"evaluator");
	}
	private static BigDecimal convert(MemoOptimizer optimizer,double value) throws Exception {
		Method method=MemoOptimizer.class.getDeclaredMethod("cachedExact",double.class);
		method.setAccessible(true);
		try { return (BigDecimal)method.invoke(optimizer,value); }
		catch(InvocationTargetException failure) { throw (RuntimeException)failure.getCause(); }
	}
}
