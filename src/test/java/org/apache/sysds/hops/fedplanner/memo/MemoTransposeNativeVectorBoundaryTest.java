/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.Occurrence;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** Boundary checks for admitting unary TRANS to MemoProgramModel's native vector cache. */
public class MemoTransposeNativeVectorBoundaryTest {
	private static final Method NATIVE_VECTOR=method("nativeCostVector",
		Occurrence.class,EvaluationContext.class,double.class);

	@Test
	public void nestedTransposesAndOneByOneRetainTheUncachedNativeCost() throws Exception {
		Fixture nested=fixture(7,3,21,false,true);
		Assert.assertEquals("both nested operators must remain visible",2,nested.transposes().size());
		for(int index=0;index<nested.transposes().size();index++) {
			Invocation invocation=invokeAgainstUncached(nested,index,1.0d);
			assertBits(invocation.uncached(),invocation.cached());
			Assert.assertEquals(1,invocation.cache().size());
		}

		Fixture singleton=fixture(1,1,1,false,false);
		Invocation oneByOne=invokeAgainstUncached(singleton,0,1.0d);
		assertBits(oneByOne.uncached(),oneByOne.cached());
		Assert.assertEquals("1x1 TRANS is still an exact unary cache request",1,oneByOne.cache().size());
	}

	@Test
	public void requiresCompressionTemplateMarkerMatchesUncached() throws Exception {
		Fixture fixture=fixture(9,4,18,true,false);
		Occurrence occurrence=fixture.transposes().get(0);
		Assert.assertTrue("the analyzed private template must retain the supported pre-freeze marker",
			fixture.cachedUniverse().shapes().view(occurrence.compiledOwnerId()).requiresCompression());
		Invocation invocation=invokeAgainstUncached(fixture,0,0.375d);
		assertBits(invocation.uncached(),invocation.cached());
		Assert.assertEquals(1,invocation.cache().size());
	}

	@Test
	public void fullShapeIncludingNnzSeparatesMinusOneZeroAndDense() throws Exception {
		List<Object> keys=new ArrayList<>();
		for(long nonZeros : new long[] {-1,0,24}) {
			Fixture fixture=fixture(6,4,nonZeros,false,false);
			Invocation invocation=invokeAgainstUncached(fixture,0,1.0d);
			assertBits(invocation.uncached(),invocation.cached());
			keys.add(invocation.cache().keySet().iterator().next());
		}
		Assert.assertEquals("all three equal-dimension NNZ states must remain distinct",3,
			keys.stream().distinct().count());
		Assert.assertNotEquals(keys.get(0),keys.get(1));
		Assert.assertNotEquals(keys.get(1),keys.get(2));
	}

	@Test
	public void exactWeightBitsSeparateRequestsAndHitsReturnDefensiveClones() throws Exception {
		Fixture fixture=fixture(8,5,40,false,false);
		Pair pair=models(fixture);
		double firstWeight=1.0d, secondWeight=Math.nextUp(firstWeight);
		double[] referenceFirst=invoke(pair.uncached(),fixture.uncachedTransposes().get(0),firstWeight);
		double[] referenceSecond=invoke(pair.uncached(),fixture.uncachedTransposes().get(0),secondWeight);
		double[] first=invoke(pair.cached(),fixture.transposes().get(0),firstWeight);
		double[] second=invoke(pair.cached(),fixture.transposes().get(0),secondWeight);
		assertBits(referenceFirst,first);
		assertBits(referenceSecond,second);
		Map<?,?> cache=cache(pair.cached());
		Assert.assertEquals("adjacent binary64 weights require separate entries",2,cache.size());

		double original=first[0];
		first[0]=Double.longBitsToDouble(Double.doubleToRawLongBits(original)^0x100L);
		double[] firstHit=invoke(pair.cached(),fixture.transposes().get(0),firstWeight);
		Assert.assertNotSame(first,firstHit);
		assertBits(referenceFirst,firstHit);
		firstHit[0]=Double.NaN;
		double[] secondHit=invoke(pair.cached(),fixture.transposes().get(0),firstWeight);
		Assert.assertNotSame(firstHit,secondHit);
		assertBits(referenceFirst,secondHit);
		Assert.assertEquals(2,cache.size());
		Assert.assertEquals(2L,longField(pair.cached(),"nativeVectorHits"));
	}

	@Test
	public void unknownShapeIsRejectedAndFailedVectorDoesNotPublish() throws Exception {
		Fixture unknown=fixture(-1,-1,-1,false,false);
		Throwable cachedUnknown=modelFailure(unknown.facts(),unknown.sources(),unknown.cachedUniverse(),4096);
		Throwable uncachedUnknown=modelFailure(unknown.uncachedFacts(),unknown.uncachedSources(),
			unknown.uncachedUniverse(),0);
		Assert.assertTrue(cachedUnknown instanceof MemoCostModel.CostException);
		Assert.assertTrue(uncachedUnknown instanceof MemoCostModel.CostException);
		Assert.assertEquals(MemoCostModel.DiagnosticCode.UNKNOWN_SIZE,
			((MemoCostModel.CostException)cachedUnknown).code());
		Assert.assertEquals(((MemoCostModel.CostException)uncachedUnknown).code(),
			((MemoCostModel.CostException)cachedUnknown).code());

		Fixture fixture=fixture(3,2,6,false,false);
		Pair pair=models(fixture);
		Map<?,?> cache=cache(pair.cached());
		Assert.assertEquals(0,cache.size());
		Throwable cachedFailure=failure(pair.cached(),fixture.transposes().get(0),Double.NaN);
		Throwable uncachedFailure=failure(pair.uncached(),fixture.uncachedTransposes().get(0),Double.NaN);
		Assert.assertNotNull(cachedFailure);
		Assert.assertNotNull(uncachedFailure);
		Assert.assertEquals(uncachedFailure.getClass(),cachedFailure.getClass());
		Assert.assertEquals(MemoCostModel.DiagnosticCode.INVALID_FREQUENCY,
			((MemoCostModel.CostException)cachedFailure).code());
		Assert.assertEquals("a failed build must never publish its allocated vector",0,cache.size());
		Assert.assertEquals(0L,longField(pair.cached(),"nativeVectorBuilds"));
	}

	private static Throwable modelFailure(MemoProgramFacts facts,MemoSources sources,
		MemoRuleBuilder.Result universe,int entries) {
		try {
			new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults(),true,entries);
			return null;
		}
		catch(Throwable error) {
			return error;
		}
	}

	private static Invocation invokeAgainstUncached(Fixture fixture,int transpose,double weight) throws Exception {
		Pair pair=models(fixture);
		double[] uncached=invoke(pair.uncached(),fixture.uncachedTransposes().get(transpose),weight);
		double[] cached=invoke(pair.cached(),fixture.transposes().get(transpose),weight);
		return new Invocation(uncached,cached,cache(pair.cached()));
	}

	private static Pair models(Fixture fixture) throws Exception {
		MemoProgramModel uncached=new MemoProgramModel(fixture.uncachedFacts(),fixture.uncachedSources(),
			fixture.uncachedUniverse(),MemoProgramModel.Profile.defaults(),true,0);
		MemoProgramModel cached=new MemoProgramModel(fixture.facts(),fixture.sources(),
			fixture.cachedUniverse(),MemoProgramModel.Profile.defaults(),true,4096);
		clearNativeState(uncached);
		clearNativeState(cached);
		return new Pair(uncached,cached);
	}

	private static void clearNativeState(MemoProgramModel model) throws Exception {
		cache(model).clear();
		field("nativeVectorRequests").setLong(model,0);
		field("nativeVectorHits").setLong(model,0);
		field("nativeVectorBuilds").setLong(model,0);
	}

	private static double[] invoke(MemoProgramModel model,Occurrence occurrence,double weight) throws Exception {
		try {
			return (double[])NATIVE_VECTOR.invoke(model,occurrence,EvaluationContext.empty(),weight);
		}
		catch(InvocationTargetException error) {
			if(error.getCause() instanceof Exception exception) throw exception;
			if(error.getCause() instanceof Error fatal) throw fatal;
			throw error;
		}
	}

	private static Throwable failure(MemoProgramModel model,Occurrence occurrence,double weight) {
		try {
			invoke(model,occurrence,weight);
			return null;
		}
		catch(Throwable error) {
			return error;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,double[]> cache(MemoProgramModel model) throws Exception {
		return (Map<Object,double[]>)field("nativeVectorCache").get(model);
	}

	private static long longField(MemoProgramModel model,String name) throws Exception {
		return field(name).getLong(model);
	}

	private static Field field(String name) throws Exception {
		Field result=MemoProgramModel.class.getDeclaredField(name);
		result.setAccessible(true);
		return result;
	}

	private static Method method(String name,Class<?>... parameters) {
		try {
			Method result=MemoProgramModel.class.getDeclaredMethod(name,parameters);
			result.setAccessible(true);
			return result;
		}
		catch(ReflectiveOperationException error) {
			throw new ExceptionInInitializerError(error);
		}
	}

	private static void assertBits(double[] expected,double[] actual) {
		Assert.assertEquals(expected.length,actual.length);
		Assert.assertTrue("fixture must expose at least one native rule",expected.length>0);
		for(int index=0;index<expected.length;index++)
			Assert.assertEquals("binary64 mismatch at rule "+index,
				Double.doubleToRawLongBits(expected[index]),Double.doubleToRawLongBits(actual[index]));
	}

	private static Fixture fixture(long rows,long columns,long nonZeros,boolean compressed,boolean nested) {
		Graph cached=graph(rows,columns,nonZeros,compressed,nested);
		Graph uncached=graph(rows,columns,nonZeros,compressed,nested);
		return new Fixture(cached.facts(),cached.sources(),cached.universe(),cached.transposes(),
			uncached.facts(),uncached.sources(),uncached.universe(),uncached.transposes());
	}

	private static Graph graph(long rows,long columns,long nonZeros,boolean compressed,boolean nested) {
		DataOp input=new DataOp("X",DataType.MATRIX,ValueType.FP64,OpOpData.PERSISTENTREAD,
			"X",rows,columns,nonZeros,1000);
		ReorgOp inner=transpose("inner",input,columns,rows,nonZeros,compressed);
		ReorgOp root=nested ? transpose("outer",inner,rows,columns,nonZeros,compressed) : inner;
		StatementBlock block=new StatementBlock();
		block.setHops(new ArrayList<>(List.of(root)));
		DMLProgram program=new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		List<Occurrence> transposes=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp()==ReOrgOp.TRANS)
			.toList();
		return new Graph(facts,sources,universe,transposes);
	}

	private static ReorgOp transpose(String name,Hop input,long rows,long columns,long nonZeros,
		boolean compressed) {
		ReorgOp result=new ReorgOp(name,DataType.MATRIX,ValueType.FP64,ReOrgOp.TRANS,input);
		result.setDim1(rows);
		result.setDim2(columns);
		result.setNnz(nonZeros);
		result.setBlocksize(1000);
		if(compressed) result.setRequiresCompression();
		return result;
	}

	private record Graph(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe,
		List<Occurrence> transposes) { }
	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result cachedUniverse,
		List<Occurrence> transposes,MemoProgramFacts uncachedFacts,MemoSources uncachedSources,
		MemoRuleBuilder.Result uncachedUniverse,List<Occurrence> uncachedTransposes) { }
	private record Pair(MemoProgramModel uncached,MemoProgramModel cached) { }
	private record Invocation(double[] uncached,double[] cached,Map<Object,double[]> cache) { }
}
