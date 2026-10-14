/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoActivationUnionReuseTest {
	@Test public void fullOrderedImmutableInputRetainsExactBitsAndPhaseIdentity() throws Exception {
		for(double probability:new double[] {0.5,0.3,0.125}) {
			MemoProgramModel model=model(probability);
			GuardDecision guard=new GuardDecision(2,List.of(3));
			var yes=new MemoProgramModel.Activation(Map.of(guard,true),probability);
			var no=new MemoProgramModel.Activation(Map.of(guard,false),1-probability);
			var last=new MemoProgramModel.Activation(Map.of(guard,true),
				Map.of(new LoopDecision(0,List.of(3)),IterationPhase.LAST),probability);
			for(List<MemoProgramModel.Activation> events:List.of(List.of(yes,no,yes),List.of(no,yes),
				List.of(yes,last),List.of(yes),List.of(no))) {
				double expected=MemoProgramModel.activationUnion(events,Double.POSITIVE_INFINITY,probability);
				long hits=model.movementReuseStatistics().unionCacheHits();
				Assert.assertEquals(Double.doubleToLongBits(expected),Double.doubleToLongBits(union(model,events)));
				Assert.assertEquals(hits,model.movementReuseStatistics().unionCacheHits());
				Assert.assertEquals(Double.doubleToLongBits(expected),
					Double.doubleToLongBits(union(model,new ArrayList<>(events))));
				Assert.assertEquals(hits+1,model.movementReuseStatistics().unionCacheHits());
			}
			long hits=model.movementReuseStatistics().unionCacheHits();
			union(model,List.of(new MemoProgramModel.Activation(Map.of(guard,true),probability)));
			Assert.assertEquals("equal content in a different object is conservatively a miss",hits,
				model.movementReuseStatistics().unionCacheHits());
		}
	}

	@Test public void evictionAndFailedComputationNeverPublishAnInvalidValue() throws Exception {
		MemoProgramModel model=model(0.3);
		var first=new MemoProgramModel.Activation(Map.of(),1);
		Assert.assertEquals(1,union(model,List.of(first)),0);
		for(int i=0;i<4096;i++) union(model,List.of(new MemoProgramModel.Activation(Map.of(),i+2)));
		long hits=model.movementReuseStatistics().unionCacheHits();
		Assert.assertEquals(1,union(model,List.of(first)),0);
		Assert.assertEquals(hits,model.movementReuseStatistics().unionCacheHits());
		Assert.assertEquals(1,union(model,List.of(first)),0);
		Assert.assertEquals(hits+1,model.movementReuseStatistics().unionCacheHits());
		var inconsistent=List.of(first,new MemoProgramModel.Activation(Map.of(),2));
		for(int attempt=0;attempt<2;attempt++) {
			try { union(model,inconsistent); Assert.fail("inconsistent activation bases must fail"); }
			catch(InvocationTargetException ex) {
				Assert.assertTrue(ex.getCause() instanceof IllegalStateException);
			}
		}
		Assert.assertEquals(hits+1,model.movementReuseStatistics().unionCacheHits());
	}

	private static double union(MemoProgramModel model,List<MemoProgramModel.Activation> events) throws Exception {
		Method method=MemoProgramModel.class.getDeclaredMethod("retainedActivationUnion",List.class);
		method.setAccessible(true); return (double)method.invoke(model,events);
	}
	private static MemoProgramModel model(double probability) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			"A=matrix(1,2,2);print(sum(A));",new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		return new MemoProgramModel(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()),
			new MemoProgramModel.Profile(10,probability));
	}
}
