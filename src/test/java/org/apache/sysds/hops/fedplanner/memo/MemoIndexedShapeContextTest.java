/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoIndexedShapeContextTest {
	@Test public void indexedKeyPreservesSyntacticGuardIdentity() throws Exception {
		MemoShapes shapes=fixture(true,65536);
		GuardDecision first=new GuardDecision(1,List.of(4),17,List.of(8));
		GuardDecision alias=new GuardDecision(2,List.of(5),17,List.of(8));
		Assert.assertEquals(first,alias);
		Object firstKey=fullKey(shapes,new EvaluationContext(Map.of(first,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(10)));
		Object aliasKey=fullKey(shapes,new EvaluationContext(Map.of(alias,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(10)));
		Assert.assertNotEquals(firstKey,aliasKey);
	}

	@Test public void nonzeroCapFallsBackAtomicallyAndStably() throws Exception {
		MemoShapes shapes=fixture(true,1);
		EvaluationContext encodable=new EvaluationContext(
			Map.of(new GuardDecision(1,List.of(),3,List.of()),true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096);
		EvaluationContext fallback=new EvaluationContext(
			Map.of(new GuardDecision(2,List.of(),4,List.of()),false),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096);
		Object encodedKey=fullKey(shapes,encodable);
		Object firstFallback=fullKey(shapes,fallback);
		Assert.assertNotEquals(encodedKey,firstFallback);
		Assert.assertEquals(firstFallback,fullKey(shapes,fallback));
		Assert.assertEquals(encodedKey,fullKey(shapes,encodable));
	}

	private static Object fullKey(MemoShapes shapes,EvaluationContext context) throws Exception {
		Method method=MemoShapes.class.getDeclaredMethod("shapeContextKey",EvaluationContext.class);
		method.setAccessible(true);
		return method.invoke(shapes,context);
	}

	private static MemoShapes fixture(boolean indexed,int atomLimit) throws Exception {
		String script="A=matrix(1,rows=2,cols=3);B=abs(A);print(sum(B));";
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		return MemoShapes.analyze(facts,MemoSources.acquire(facts,null),indexed,atomLimit);
	}
}
