/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.Occurrence;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** Focused boundaries for the exact wide native-vector families. */
public class MemoNativeWideVectorBoundaryTest {
	private static final Method NATIVE_VECTOR=method("nativeCostVector",
		Occurrence.class,EvaluationContext.class,double.class);

	@Test public void exactFiveFamiliesMatchUncachedAndHitWithDefensiveClones() throws Exception {
		List<Hop> roots=List.of(
			nary("plus4",OpOpN.PLUS,matrix("p1",8,4,32),matrix("p2",8,4,16),
				matrix("p3",8,4,0),matrix("p4",8,4,-1)),
			nary("mult3",OpOpN.MULT,matrix("m1",8,4,32),matrix("m2",8,4,8),matrix("m3",8,4,1)),
			ternary("ifelse",OpOp3.IFELSE,matrix("condition",8,4,4),
				matrix("then",8,4,32),matrix("else",8,4,0)),
			ternary("plusMult",OpOp3.PLUS_MULT,matrix("base",8,4,20),
				new LiteralOp(2d),matrix("factor",8,4,7)),
			ternary("minusMult",OpOp3.MINUS_MULT,matrix("base2",8,4,19),
				new LiteralOp(-3d),matrix("factor2",8,4,6)));
		for(Hop root:roots) {
			Fixture fixture=fixture(root,copy(root));
			Pair pair=models(fixture);
			double[] expected=invoke(pair.uncached(),fixture.uncachedOccurrence(),0.375d);
			double[] first=invoke(pair.cached(),fixture.cachedOccurrence(),0.375d);
			double[] second=invoke(pair.cached(),fixture.cachedOccurrence(),0.375d);
			assertBits(expected,first); assertBits(expected,second);
			Assert.assertNotSame(first,second);
			Assert.assertEquals(root.getName(),1,cache(pair.cached()).size());
			Assert.assertEquals(root.getName(),2L,longField(pair.cached(),"nativeVectorRequests"));
			Assert.assertEquals(root.getName(),1L,longField(pair.cached(),"nativeVectorHits"));
			Assert.assertEquals(root.getName(),1L,longField(pair.cached(),"nativeVectorBuilds"));
			first[0]=Double.NaN;
			assertBits(expected,invoke(pair.cached(),fixture.cachedOccurrence(),0.375d));
		}
	}

	@Test public void fullOrderedKeySeparatesOrderThirdNnzAndExactWeightBits() throws Exception {
		MemoShapes.Shape dense=shape(8,4,32), sparse=shape(8,4,3), zero=shape(8,4,0);
		Object first=key(17,shape(8,4,32),List.of(dense,sparse,zero),1000,1.0d);
		Object reordered=key(17,shape(8,4,32),List.of(dense,zero,sparse),1000,1.0d);
		Object thirdNnz=key(17,shape(8,4,32),List.of(dense,sparse,sparse),1000,1.0d);
		Object adjacentWeight=key(17,shape(8,4,32),List.of(dense,sparse,zero),1000,Math.nextUp(1.0d));
		Assert.assertNotEquals(first,reordered);
		Assert.assertNotEquals(first,thirdNnz);
		Assert.assertNotEquals(first,adjacentWeight);
	}

	@Test public void repeatedDirectInputFallsBackWithExactNativeCost() throws Exception {
		Hop shared=matrix("shared",8,4,17);
		NaryOp cached=nary("sharedPlus",OpOpN.PLUS,shared,shared,shared);
		Hop uncachedShared=matrix("shared",8,4,17);
		NaryOp uncached=nary("sharedPlus",OpOpN.PLUS,uncachedShared,uncachedShared,uncachedShared);
		MemoShapes.Shape first=shape(8,4,4), middle=shape(16,4,8), last=shape(24,4,12);
		Assert.assertEquals(List.of(last,last,last),
			MemoShapes.effectiveInputShapes(cached,List.of(first,middle,last)));
		Fixture fixture=fixture(cached,uncached);
		Pair pair=models(fixture);
		assertBits(invoke(pair.uncached(),fixture.uncachedOccurrence(),2d),
			invoke(pair.cached(),fixture.cachedOccurrence(),2d));
		Assert.assertEquals(0L,longField(pair.cached(),"nativeVectorRequests"));
		Assert.assertEquals(0,cache(pair.cached()).size());
	}

	@Test public void nestedAliasBetweenRepeatedInputsFallsBackWithExactNativeCost() throws Exception {
		Hop cachedA=matrix("nestedA",8,4,9);
		NaryOp cached=nary("nestedAlias",OpOpN.PLUS,cachedA,unary("derived",cachedA),cachedA);
		Hop uncachedA=matrix("nestedA",8,4,9);
		NaryOp uncached=nary("nestedAlias",OpOpN.PLUS,uncachedA,unary("derived",uncachedA),uncachedA);
		Fixture fixture=fixture(cached,uncached); Pair pair=models(fixture);
		assertBits(invoke(pair.uncached(),fixture.uncachedOccurrence(),1d),
			invoke(pair.cached(),fixture.cachedOccurrence(),1d));
		Assert.assertEquals(0L,longField(pair.cached(),"nativeVectorRequests"));
		Assert.assertEquals(0,cache(pair.cached()).size());
	}

	@Test public void distinctNestedDependencyWithUnknownNnzRemainsEligibleAndExact() throws Exception {
		Hop cachedA=matrix("eligibleA",8,4,-1);
		NaryOp cached=nary("eligibleNested",OpOpN.PLUS,cachedA,unary("derivedUnknown",cachedA),
			matrix("eligibleC",8,4,5));
		Hop uncachedA=matrix("eligibleA",8,4,-1);
		NaryOp uncached=nary("eligibleNested",OpOpN.PLUS,uncachedA,unary("derivedUnknown",uncachedA),
			matrix("eligibleC",8,4,5));
		Fixture fixture=fixture(cached,uncached); Pair pair=models(fixture);
		double[] expected=invoke(pair.uncached(),fixture.uncachedOccurrence(),1d);
		assertBits(expected,invoke(pair.cached(),fixture.cachedOccurrence(),1d));
		assertBits(expected,invoke(pair.cached(),fixture.cachedOccurrence(),1d));
		Assert.assertEquals(2L,longField(pair.cached(),"nativeVectorRequests"));
		Assert.assertEquals(1L,longField(pair.cached(),"nativeVectorHits"));
		Assert.assertEquals(1,cache(pair.cached()).size());
	}

	@Test public void nanAndNegativeFrequencyFailWithoutPublishingAndSignedZeroStaysExact() throws Exception {
		Fixture fixture=fixture(
			nary("plus3",OpOpN.PLUS,matrix("a",4,2,8),matrix("b",4,2,8),matrix("c",4,2,8)),
			nary("plus3",OpOpN.PLUS,matrix("a",4,2,8),matrix("b",4,2,8),matrix("c",4,2,8)));
		Pair pair=models(fixture);
		for(double weight:new double[] {Double.NaN,-1d}) {
			Throwable cached=failure(pair.cached(),fixture.cachedOccurrence(),weight);
			Throwable uncached=failure(pair.uncached(),fixture.uncachedOccurrence(),weight);
			Assert.assertTrue(cached instanceof MemoCostModel.CostException);
			Assert.assertEquals(uncached.getClass(),cached.getClass());
			Assert.assertEquals(MemoCostModel.DiagnosticCode.INVALID_FREQUENCY,
				((MemoCostModel.CostException)cached).code());
			Assert.assertEquals(((MemoCostModel.CostException)uncached).code(),
				((MemoCostModel.CostException)cached).code());
			Assert.assertEquals("failed vector must not publish",0,cache(pair.cached()).size());
		}
		Assert.assertEquals(0L,longField(pair.cached(),"nativeVectorBuilds"));
		double[] positiveZero=invoke(pair.cached(),fixture.cachedOccurrence(),0.0d);
		double[] negativeZero=invoke(pair.cached(),fixture.cachedOccurrence(),-0.0d);
		assertBits(invoke(pair.uncached(),fixture.uncachedOccurrence(),0.0d),positiveZero);
		assertBits(invoke(pair.uncached(),fixture.uncachedOccurrence(),-0.0d),negativeZero);
		Assert.assertEquals("signed-zero frequency bits remain separate",2,cache(pair.cached()).size());
	}

	@Test public void unknownGeometryConstructorFailureParity() throws Exception {
		Graph cached=graph(nary("unknown",OpOpN.PLUS,matrix("u1",-1,-1,-1),
			matrix("u2",-1,-1,-1),matrix("u3",-1,-1,-1)));
		Graph uncached=graph(nary("unknown",OpOpN.PLUS,matrix("u1",-1,-1,-1),
			matrix("u2",-1,-1,-1),matrix("u3",-1,-1,-1)));
		Throwable cachedFailure=modelFailure(cached,4096),uncachedFailure=modelFailure(uncached,0);
		Assert.assertTrue(cachedFailure instanceof MemoCostModel.CostException);
		Assert.assertEquals(MemoCostModel.DiagnosticCode.UNKNOWN_SIZE,
			((MemoCostModel.CostException)cachedFailure).code());
		Assert.assertEquals(((MemoCostModel.CostException)uncachedFailure).code(),
			((MemoCostModel.CostException)cachedFailure).code());
	}

	@Test public void mapAndAppendRemainOutsideTheWideGate() throws Exception {
		List<Hop> excluded=List.of(
			nary("append",OpOpN.CBIND,matrix("a1",8,4,32),matrix("a2",8,4,32),matrix("a3",8,4,32)),
			ternary("map",OpOp3.MAP,matrix("mapX",8,4,32),new LiteralOp(1d),new LiteralOp(2d)));
		for(Hop root:excluded) {
			Fixture fixture=fixture(root,copy(root)); Pair pair=models(fixture);
			Throwable cached=failure(pair.cached(),fixture.cachedOccurrence(),1d);
			Throwable uncached=failure(pair.uncached(),fixture.uncachedOccurrence(),1d);
			if(uncached==null) Assert.assertNull(root.getName(),cached);
			else Assert.assertEquals(root.getName(),uncached.getClass(),cached.getClass());
			Assert.assertEquals(root.getName(),0L,longField(pair.cached(),"nativeVectorRequests"));
			Assert.assertEquals(root.getName(),0,cache(pair.cached()).size());
		}
	}

	private static Pair models(Fixture fixture) throws Exception {
		MemoProgramModel uncached=new MemoProgramModel(fixture.uncached().facts(),fixture.uncached().sources(),
			fixture.uncached().universe(),MemoProgramModel.Profile.defaults(),true,0);
		MemoProgramModel cached=new MemoProgramModel(fixture.cached().facts(),fixture.cached().sources(),
			fixture.cached().universe(),MemoProgramModel.Profile.defaults(),true,4096);
		clear(uncached); clear(cached); return new Pair(uncached,cached);
	}
	private static void clear(MemoProgramModel model) throws Exception {
		cache(model).clear(); field("nativeVectorRequests").setLong(model,0);
		field("nativeVectorHits").setLong(model,0); field("nativeVectorBuilds").setLong(model,0);
	}
	private static Throwable modelFailure(Graph graph,int entries) {
		try { new MemoProgramModel(graph.facts(),graph.sources(),graph.universe(),
			MemoProgramModel.Profile.defaults(),true,entries); return null; }
		catch(Throwable error) { return error; }
	}
	private static Fixture fixture(Hop cached,Hop uncached) {
		Graph left=graph(cached),right=graph(uncached);
		return new Fixture(left,right,left.occurrence(),right.occurrence());
	}
	private static Graph graph(Hop root) {
		StatementBlock block=new StatementBlock(); block.setHops(new ArrayList<>(List.of(root)));
		DMLProgram program=new DMLProgram(); program.setStatementBlocks(new ArrayList<>(List.of(block)));
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		Occurrence occurrence=facts.occurrences().stream()
			.filter(value -> value.hop().getName().equals(root.getName())).findFirst().orElseThrow();
		return new Graph(facts,sources,universe,occurrence);
	}
	private static Hop copy(Hop root) {
		if(root instanceof NaryOp nary) {
			Hop[] inputs=nary.getInput().stream().map(MemoNativeWideVectorBoundaryTest::copyInput).toArray(Hop[]::new);
			return nary(root.getName(),nary.getOp(),inputs);
		}
		TernaryOp ternary=(TernaryOp)root;
		return ternary(root.getName(),ternary.getOp(),copyInput(root.getInput(0)),
			copyInput(root.getInput(1)),copyInput(root.getInput(2)));
	}
	private static Hop copyInput(Hop input) {
		if(input instanceof LiteralOp literal) {
			if(literal.getValueType()==ValueType.BOOLEAN) return new LiteralOp(literal.getBooleanValue());
			return new LiteralOp(literal.getDoubleValue());
		}
		return matrix(input.getName(),input.getDim1(),input.getDim2(),input.getNnz());
	}
	private static DataOp matrix(String name,long rows,long cols,long nnz) {
		return new DataOp(name,DataType.MATRIX,ValueType.FP64,OpOpData.PERSISTENTREAD,
			name,rows,cols,nnz,1000);
	}
	private static NaryOp nary(String name,OpOpN op,Hop... inputs) {
		NaryOp result=new NaryOp(name,DataType.MATRIX,ValueType.FP64,op,inputs);
		long rows=inputs[0].getDim1(),cols=op==OpOpN.CBIND
			? Arrays.stream(inputs).mapToLong(Hop::getDim2).sum() : inputs[0].getDim2();
		result.setDim1(rows); result.setDim2(cols); result.setNnz(rows>0&&cols>0?rows*cols:-1); result.setBlocksize(1000);
		return result;
	}
	private static UnaryOp unary(String name,Hop input) {
		UnaryOp result=new UnaryOp(name,DataType.MATRIX,ValueType.FP64,OpOp1.ABS,input);
		result.setDim1(input.getDim1()); result.setDim2(input.getDim2());
		result.setNnz(input.getNnz()); result.setBlocksize(1000); return result;
	}
	private static TernaryOp ternary(String name,OpOp3 op,Hop first,Hop second,Hop third) {
		TernaryOp result=new TernaryOp(name,DataType.MATRIX,ValueType.FP64,op,first,second,third);
		Hop geometry=first.getDataType()==DataType.MATRIX?first:second.getDataType()==DataType.MATRIX?second:third;
		result.setDim1(geometry.getDim1()); result.setDim2(geometry.getDim2());
		result.setNnz(geometry.getNnz()); result.setBlocksize(1000); return result;
	}
	private static MemoShapes.Shape shape(long rows,long cols,long nnz) {
		return new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,rows,cols,nnz,
			rows>0&&cols>0?MemoShapes.Knowledge.EXACT:MemoShapes.Knowledge.UNKNOWN);
	}
	private static Object key(int occurrence,MemoShapes.Shape output,List<MemoShapes.Shape> inputs,
		int blocksize,double weight) throws Exception {
		Class<?> type=Class.forName(MemoProgramModel.class.getName()+"$NativeVectorKey");
		Constructor<?> constructor=type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
		return constructor.newInstance(occurrence,output,inputs,blocksize,Double.doubleToLongBits(weight));
	}
	private static double[] invoke(MemoProgramModel model,Occurrence occurrence,double weight) throws Exception {
		try { return (double[])NATIVE_VECTOR.invoke(model,occurrence,EvaluationContext.empty(),weight); }
		catch(InvocationTargetException error) {
			if(error.getCause() instanceof Exception exception) throw exception;
			if(error.getCause() instanceof Error fatal) throw fatal;
			throw error;
		}
	}
	private static Throwable failure(MemoProgramModel model,Occurrence occurrence,double weight) {
		try { invoke(model,occurrence,weight); return null; } catch(Throwable error) { return error; }
	}
	@SuppressWarnings("unchecked") private static Map<Object,double[]> cache(MemoProgramModel model) throws Exception {
		return (Map<Object,double[]>)field("nativeVectorCache").get(model);
	}
	private static long longField(MemoProgramModel model,String name) throws Exception { return field(name).getLong(model); }
	private static Field field(String name) throws Exception {
		Field result=MemoProgramModel.class.getDeclaredField(name); result.setAccessible(true); return result;
	}
	private static Method method(String name,Class<?>... parameters) {
		try { Method result=MemoProgramModel.class.getDeclaredMethod(name,parameters); result.setAccessible(true); return result; }
		catch(ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
	}
	private static void assertBits(double[] expected,double[] actual) {
		Assert.assertEquals(expected.length,actual.length); Assert.assertTrue(expected.length>0);
		for(int index=0;index<expected.length;index++) Assert.assertEquals("rule "+index,
			Double.doubleToRawLongBits(expected[index]),Double.doubleToRawLongBits(actual[index]));
	}
	private record Graph(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe,
		Occurrence occurrence) { }
	private record Fixture(Graph cached,Graph uncached,Occurrence cachedOccurrence,
		Occurrence uncachedOccurrence) { }
	private record Pair(MemoProgramModel uncached,MemoProgramModel cached) { }
}
