/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Live lookup and wrapper-depth contract for phase-local loop heads. */
public class MemoShapeHeadLookupTest {
	@Test
	public void sameEvaluationRebindIsIdempotentAndLocalHeadsStayLive() throws Exception {
		Fixture fixture=fixture();
		MutableSelection delegate=new MutableSelection();
		EvaluationContext base=context(delegate);
		Object evaluation=phaseEvaluation(fixture.shapes());

		EvaluationContext first=bind(evaluation,fixture.binding(),base);
		EvaluationContext rebound=bind(evaluation,fixture.binding(),first);
		Assert.assertSame("the same evaluation must not add another selection wrapper",first,rebound);

		Map<Object,MemoShapes.Shape> heads=heads(evaluation);
		Object frameA=headKey(new LoopDecision(7,List.of(1)),3);
		Object frameB=headKey(new LoopDecision(7,List.of(2)),3);
		heads.put(frameA,fixture.shape());
		Assert.assertTrue(first.selections().headIsBound(7,3));
		heads.put(frameB,fixture.shape());
		heads.remove(frameA);
		Assert.assertTrue("another call frame with the same loop/slot remains bound",
			first.selections().headIsBound(7,3));
		heads.remove(frameB);
		Assert.assertFalse(first.selections().headIsBound(7,3));
	}

	@Test
	public void delegateRemainsLiveAndDifferentEvaluationsRemainLayered() throws Exception {
		Fixture fixture=fixture();
		MutableSelection delegate=new MutableSelection();
		Object outer=phaseEvaluation(fixture.shapes());
		EvaluationContext outerContext=bind(outer,fixture.binding(),context(delegate));
		Object inner=phaseEvaluation(fixture.shapes());
		EvaluationContext innerContext=bind(inner,fixture.binding(),outerContext);

		delegate.rule=4;
		delegate.layout=MemoPartition.COL;
		delegate.headLayout=MemoPartition.ROW;
		delegate.bound=true;
		Assert.assertEquals(4,innerContext.selections().selectedRule(11));
		Assert.assertEquals(MemoPartition.COL,innerContext.selections().selectedLayout(11));
		Assert.assertEquals(MemoPartition.ROW,innerContext.selections().selectedHeadLayout(9,2));
		Assert.assertTrue(innerContext.selections().headIsBound(9,2));

		delegate.bound=false;
		Object outerHead=headKey(new LoopDecision(5,List.of(1)),1);
		heads(outer).put(outerHead,fixture.shape());
		Assert.assertTrue("an inner evaluation must delegate to a different outer evaluation",
			innerContext.selections().headIsBound(5,1));
		heads(outer).remove(outerHead);
		Assert.assertFalse(innerContext.selections().headIsBound(5,1));

		Object innerHead=headKey(new LoopDecision(6,List.of(2)),2);
		heads(inner).put(innerHead,fixture.shape());
		Assert.assertTrue(innerContext.selections().headIsBound(6,2));
		Assert.assertFalse("inner local heads do not leak into the outer evaluation",
			outerContext.selections().headIsBound(6,2));
	}

	private static Fixture fixture() throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			"A=matrix(1,rows=2,cols=2);B=abs(A);print(sum(B));",new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		return new Fixture(shapes,0,shapes.shape(0));
	}

	private static EvaluationContext context(SelectionLookup selections) {
		return new EvaluationContext(Map.of(),Map.of(),selections,4096,List.of(),Map.of(),Map.of());
	}

	private static Object phaseEvaluation(MemoShapes shapes) throws Exception {
		Class<?> type=Class.forName(MemoShapes.class.getName()+"$PhaseEvaluation");
		Constructor<?> constructor=type.getDeclaredConstructor(MemoShapes.class);
		constructor.setAccessible(true);
		return constructor.newInstance(shapes);
	}

	private static EvaluationContext bind(Object evaluation,int binding,EvaluationContext context) throws Exception {
		Method method=evaluation.getClass().getDeclaredMethod("bindDirectHeads",int.class,EvaluationContext.class);
		method.setAccessible(true);
		return (EvaluationContext) method.invoke(evaluation,binding,context);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,MemoShapes.Shape> heads(Object evaluation) throws Exception {
		Field field=evaluation.getClass().getDeclaredField("heads");
		field.setAccessible(true);
		return (Map<Object,MemoShapes.Shape>) field.get(evaluation);
	}

	private static Object headKey(LoopDecision loop,int slot) throws Exception {
		Class<?> type=Class.forName(MemoShapes.class.getName()+"$LoopSlotKey");
		Constructor<?> constructor=type.getDeclaredConstructor(LoopDecision.class,int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(loop,slot);
	}

	private record Fixture(MemoShapes shapes,int binding,MemoShapes.Shape shape) { }

	private static final class MutableSelection implements SelectionLookup {
		private int rule=-1;
		private MemoPartition layout;
		private MemoPartition headLayout;
		private boolean bound;
		@Override public int selectedRule(int owner) { return rule; }
		@Override public MemoPartition selectedLayout(int owner) { return layout; }
		@Override public MemoPartition selectedHeadLayout(int loop,int slot) { return headLayout; }
		@Override public boolean headIsBound(int loop,int slot) { return bound; }
	}
}
