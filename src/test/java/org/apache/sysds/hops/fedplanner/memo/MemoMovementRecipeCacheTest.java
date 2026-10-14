/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.hops.MemoTable;
import org.junit.Assert;
import org.junit.Test;

public class MemoMovementRecipeCacheTest {
	@Test public void logregCacheMatchesReference() throws Exception { assertWorkloadParity("logreg"); }
	@Test public void glmCacheMatchesReference() throws Exception { assertWorkloadParity("glm"); }

	@Test
	public void recipePreservesNullAndUnknownAsDifferentRawInputs() {
		MemoShapes.Shape unknown=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,-1,-1,-1,
			MemoShapes.Knowledge.UNKNOWN);
		MemoShapes.Shape differentNnz=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,-1,-1,0,
			MemoShapes.Knowledge.UNKNOWN);
		MemoShapes.MovementViewRecipe withNull=new MemoShapes.MovementViewRecipe(7,unknown,
			java.util.Arrays.asList(null,unknown));
		MemoShapes.MovementViewRecipe withUnknown=new MemoShapes.MovementViewRecipe(7,unknown,
			List.of(unknown,unknown));
		Assert.assertNull(withNull.inputs().get(0));
		Assert.assertNotEquals(withNull,withUnknown);
		Assert.assertNotEquals(withUnknown,new MemoShapes.MovementViewRecipe(7,unknown,
			List.of(differentNnz,unknown)));
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> withNull.inputs().set(0,unknown));
	}

	@Test
	public void recipeCacheKeySeparatesCategoryWorkersAndRawOrder() throws Exception {
		MemoShapes.Shape left=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,4,2,8,
			MemoShapes.Knowledge.EXACT);
		MemoShapes.Shape right=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,2,4,8,
			MemoShapes.Knowledge.EXACT);
		MemoShapes.MovementViewRecipe forward=new MemoShapes.MovementViewRecipe(3,left,List.of(left,right));
		MemoShapes.MovementViewRecipe reverse=new MemoShapes.MovementViewRecipe(3,left,List.of(right,left));
		Constructor<?> constructor=Class.forName(MemoProgramModel.class.getName()+"$MovementRecipeKey")
			.getDeclaredConstructor(MemoShapes.MovementViewRecipe.class,int.class,boolean.class,int.class);
		constructor.setAccessible(true);
		Object upload=constructor.newInstance(forward,0,true,2);
		Assert.assertEquals(upload,constructor.newInstance(forward,0,true,2));
		Assert.assertNotEquals(upload,constructor.newInstance(forward,0,false,2));
		Assert.assertNotEquals(upload,constructor.newInstance(forward,0,true,3));
		Assert.assertNotEquals(upload,constructor.newInstance(reverse,0,true,2));
	}

	@Test
	public void successfulRawPrefixCanMaterializeAnEquivalentPrivateView() throws Exception {
		Fixture fixture=fixture("B=abs(P);print(sum(B));");
		MemoShapes shapes=fixture.universe.shapes();
		int occurrence=fixture.facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp).findFirst().orElseThrow().id();
		var context=MemoBindingResolver.EvaluationContext.empty();
		MemoShapes.PreparedMovementView first=shapes.prepareMovementView(occurrence,context);
		MemoShapes.PreparedMovementView second=shapes.prepareMovementView(occurrence,context);
		Assert.assertNotNull(first.view());
		Assert.assertNull(second.view());
		Assert.assertEquals(first.recipe(),second.recipe());
		Hop replay=shapes.materializeMovementView(second.recipe());
		assertSameShape(first.view(),replay);
		assertSameShape(first.view().getInput(0),replay.getInput(0));
		var statistics=shapes.movementPrefixStatistics();
		Assert.assertEquals(2,statistics.requests());
		Assert.assertEquals(1,statistics.completeHits());
		Assert.assertTrue(statistics.nodes()>=3);
	}

	@Test
	public void sharedInputAliasAndMemorySurviveRecipeReplay() throws Exception {
		Fixture fixture=fixture("B=P+P;print(sum(B));");
		MemoShapes shapes=fixture.universe.shapes();
		int occurrence=fixture.facts.occurrences().stream()
			.filter(value -> value.hop() instanceof org.apache.sysds.hops.BinaryOp)
			.findFirst().orElseThrow().id();
		MemoShapes.PreparedMovementView prepared=shapes.prepareMovementView(occurrence,
			MemoBindingResolver.EvaluationContext.empty());
		Hop replay=shapes.materializeMovementView(prepared.recipe());
		Assert.assertSame("full clone must preserve the shared input DAG",
			prepared.view().getInput(0),prepared.view().getInput(1));
		Assert.assertSame("recipe replay must preserve the shared input DAG",
			replay.getInput(0),replay.getInput(1));
		assertSameShape(prepared.view().getInput(0),replay.getInput(0));
	}

	@Test
	public void fullPrefixNodeCacheFallsBackWithoutPublishingMoreNodes() throws Exception {
		Fixture fixture=fixture("B=abs(P);print(sum(B));");
		MemoShapes shapes=fixture.universe.shapes();
		Field nodes=field(MemoShapes.class,"movementPrefixNodes"); nodes.setInt(shapes,4096);
		int occurrence=fixture.facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp).findFirst().orElseThrow().id();
		var context=MemoBindingResolver.EvaluationContext.empty();
		Assert.assertNotNull(shapes.prepareMovementView(occurrence,context).view());
		Assert.assertNotNull(shapes.prepareMovementView(occurrence,context).view());
		Assert.assertEquals(4096,shapes.movementPrefixStatistics().nodes());
		Assert.assertEquals(0,shapes.movementPrefixStatistics().completeHits());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void failingFirstDivergentApplyIsNotPublishedAndRetryPreservesFailure() throws Exception {
		Fixture fixture=fixture("B=abs(P);print(sum(B));");
		MemoShapes shapes=fixture.universe.shapes();
		Class<?> nodeClass=Class.forName(MemoShapes.class.getName()+"$MovementPrefixNode");
		Constructor<?> nodeConstructor=nodeClass.getDeclaredConstructor(); nodeConstructor.setAccessible(true);
		Object root=nodeConstructor.newInstance();
		Method apply=MemoShapes.class.getDeclaredMethod("applyMovementPrefix",Hop.class,nodeClass,
			boolean.class,List.class,MemoShapes.Shape.class,int.class,int.class);
		apply.setAccessible(true);
		Field nextField=nodeClass.getDeclaredField("next"); nextField.setAccessible(true);
		MemoShapes.Shape output=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,4,2,8,
			MemoShapes.Knowledge.EXACT);
		MemoShapes.Shape failing=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,13,2,26,
			MemoShapes.Knowledge.EXACT);
		MemoShapes.Shape alternate=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,4,2,8,
			MemoShapes.Knowledge.EXACT);
		Hop first=viewWithFailingInput(),retry=viewWithFailingInput(),success=viewWithFailingInput();
		apply.invoke(shapes,first,root,true,List.of(),output,-1,0);
		Map<MemoShapes.Shape,Object> rootEdges=(Map<MemoShapes.Shape,Object>)nextField.get(root);
		Object outputNode=rootEdges.get(output);
		Assert.assertNotNull(outputNode);
		assertApplyFailure(apply,shapes,first,outputNode,output,failing);
		Assert.assertFalse(((Map<?,?>)nextField.get(outputNode)).containsKey(failing));
		assertApplyFailure(apply,shapes,retry,outputNode,output,failing);
		Assert.assertFalse(((Map<?,?>)nextField.get(outputNode)).containsKey(failing));
		apply.invoke(shapes,success,outputNode,true,List.of(output),alternate,0,0);
		Assert.assertTrue(((Map<?,?>)nextField.get(outputNode)).containsKey(alternate));
		Assert.assertNull("a certified successful edge may skip clone/apply",
			apply.invoke(shapes,null,outputNode,true,List.of(output),alternate,0,0));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void measurementCacheEvictsAtItsConfiguredBound() throws Exception {
		Fixture fixture=fixture("B=abs(P);print(sum(B));");
		MemoProgramModel model=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),true,4096,true,1);
		Map<Object,MemoCostModel.MovementMeasurement> cache=
			(Map<Object,MemoCostModel.MovementMeasurement>)field(MemoProgramModel.class,
				"movementRecipeCache").get(model);
		MemoShapes.Shape shape=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,4,2,8,
			MemoShapes.Knowledge.EXACT);
		MemoShapes.MovementViewRecipe first=new MemoShapes.MovementViewRecipe(1,shape,List.of(shape));
		MemoShapes.MovementViewRecipe second=new MemoShapes.MovementViewRecipe(2,shape,List.of(shape));
		Constructor<?> constructor=Class.forName(MemoProgramModel.class.getName()+"$MovementRecipeKey")
			.getDeclaredConstructor(MemoShapes.MovementViewRecipe.class,int.class,boolean.class,int.class);
		constructor.setAccessible(true);
		cache.put(constructor.newInstance(first,0,true,2),new MemoCostModel.MovementMeasurement(1L,true));
		cache.put(constructor.newInstance(second,0,true,2),new MemoCostModel.MovementMeasurement(2L,true));
		Assert.assertEquals(1,cache.size());
	}

	@Test
	public void failedSizeAndCategoryDoNotPublishAndValidRetryDoes() throws Exception {
		Fixture fixture=fixture("B=abs(P);print(sum(B));");
		MemoProgramModel model=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),true,4096,true,4096);
		MemoShapes shapes=fixture.universe.shapes();
		int occurrence=fixture.facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp).findFirst().orElseThrow().id();
		MemoShapes.PreparedMovementView valid=shapes.prepareMovementView(occurrence,
			MemoBindingResolver.EvaluationContext.empty());
		MemoShapes.Shape unknown=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,-1,-1,-1,
			MemoShapes.Knowledge.UNKNOWN);
		MemoShapes.MovementViewRecipe invalidRecipe=new MemoShapes.MovementViewRecipe(occurrence,
			valid.recipe().output(),List.of(unknown));
		MemoShapes.PreparedMovementView invalid=new MemoShapes.PreparedMovementView(invalidRecipe,
			shapes.materializeMovementView(invalidRecipe));
		Method price=MemoProgramModel.class.getDeclaredMethod("pricePreparedMovement",
			MemoShapes.PreparedMovementView.class,int.class,MemoPartition.class,MemoPartition.class,int.class);
		price.setAccessible(true);
		assertCostFailure(price,model,invalid,MemoPartition.LOCAL,MemoPartition.ROW,
			MemoCostModel.DiagnosticCode.UNKNOWN_SIZE);
		Assert.assertEquals(0,model.movementRecipeStatistics().entries());
		assertCostFailure(price,model,valid,MemoPartition.ROW,MemoPartition.COL,
			MemoCostModel.DiagnosticCode.ILLEGAL_MOVEMENT);
		Assert.assertEquals(0,model.movementRecipeStatistics().entries());
		price.invoke(model,valid,0,MemoPartition.LOCAL,MemoPartition.ROW,fixture.sources.workers());
		Assert.assertEquals(1,model.movementRecipeStatistics().entries());
	}

	private static void assertWorkloadParity(String workload) throws Exception {
		MemoProgramModel fixture=MemoFederatedModelTest.rewrittenWorkloadModel(workload);
		MemoProgramFacts facts=(MemoProgramFacts)field(MemoProgramModel.class,"facts").get(fixture);
		MemoSources sources=(MemoSources)field(MemoProgramModel.class,"sources").get(fixture);
		MemoRuleBuilder.Result referenceUniverse=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoRuleBuilder.Result cachedUniverse=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramModel reference=new MemoProgramModel(facts,sources,referenceUniverse,
			MemoProgramModel.Profile.defaults(),true,4096,true,0);
		MemoProgramModel cached=new MemoProgramModel(facts,sources,cachedUniverse,
			MemoProgramModel.Profile.defaults(),true,4096,true,4096);
		MemoOptimizer.Result expected=reference.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
		MemoOptimizer.Result actual=cached.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
		Assert.assertEquals(workload,MemoOptimizer.Status.OPTIMAL,expected.status());
		Assert.assertEquals(workload,expected.status(),actual.status());
		Assert.assertEquals(workload,expected.cost(),actual.cost());
		Assert.assertArrayEquals(workload,expected.selectedRules(),actual.selectedRules());
		Assert.assertTrue(workload+" must exercise recipe lookup",
			cached.movementRecipeStatistics().requests()>0);
		Assert.assertTrue(workload+" must reuse exact recipe measurements",
			cached.movementRecipeStatistics().hits()>0);
	}

	private static void assertSameShape(Hop expected,Hop actual) {
		Assert.assertEquals(expected.getDataType(),actual.getDataType());
		Assert.assertEquals(expected.getValueType(),actual.getValueType());
		Assert.assertEquals(expected.getDim1(),actual.getDim1());
		Assert.assertEquals(expected.getDim2(),actual.getDim2());
		Assert.assertEquals(expected.getNnz(),actual.getNnz());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.getMemEstimate()),
			Double.doubleToRawLongBits(actual.getMemEstimate()));
	}

	private static void assertCostFailure(Method method,MemoProgramModel model,
		MemoShapes.PreparedMovementView prepared,MemoPartition from,MemoPartition target,
		MemoCostModel.DiagnosticCode code) throws Exception {
		InvocationTargetException thrown=Assert.assertThrows(InvocationTargetException.class,
			() -> method.invoke(model,prepared,0,from,target,2));
		Assert.assertTrue(thrown.getCause() instanceof MemoCostModel.CostException);
		Assert.assertEquals(code,((MemoCostModel.CostException)thrown.getCause()).code());
	}

	private static void assertApplyFailure(Method method,MemoShapes shapes,Hop view,Object node,
		MemoShapes.Shape output,MemoShapes.Shape failing) {
		InvocationTargetException thrown=Assert.assertThrows(InvocationTargetException.class,
			() -> method.invoke(shapes,view,node,true,List.of(output),failing,0,0));
		Assert.assertTrue(thrown.getCause() instanceof IllegalStateException);
		Assert.assertEquals("injected apply failure",thrown.getCause().getMessage());
	}

	private static Hop viewWithFailingInput() {
		DataOp source=new DataOp("X",DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,
			"X",4,2,8,1024);
		FailingUnaryOp input=new FailingUnaryOp(source);
		return new UnaryOp("consumer",DataType.MATRIX,ValueType.FP64,OpOp1.ABS,input);
	}

	private static final class FailingUnaryOp extends UnaryOp {
		private FailingUnaryOp(Hop input) {
			super("failing",DataType.MATRIX,ValueType.FP64,OpOp1.ABS,input);
		}
		@Override public void computeMemEstimate(MemoTable memo) {
			if(getDim1()==13) throw new IllegalStateException("injected apply failure");
			super.computeMemEstimate(memo);
		}
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field field=owner.getDeclaredField(name); field.setAccessible(true); return field;
	}

	private static Fixture fixture(String body) throws Exception {
		String script="P=federated(addresses=list(\"localhost:19101/P1\",\"localhost:19102/P2\"),"
			+"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"+body;
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,ignored -> new FederatedSourceMetadata(
			Privacy.PUBLIC,List.of(
				new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {2,2}),
					new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),"P1")),
				new ImmutablePair<>(new FederatedRange(new long[] {2,0},new long[] {4,2}),
					new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),"P2")))));
		return new Fixture(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()));
	}

	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe) { }
}
