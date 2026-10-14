/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoContextualShapeReuseTest {
	@Test
	public void guardTruthVectorsDoNotCollapseShapeKeyHashesByPopulationCount() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,3);B=abs(A);");
		List<MemoBindingResolver.GuardDecision> decisions=java.util.stream.IntStream.range(0,10)
			.mapToObj(guard -> new MemoBindingResolver.GuardDecision(guard,List.of(),100+guard,List.of()))
			.toList();
		HashSet<Integer> hashes=new HashSet<>();
		for(int mask=0;mask<1024;mask++) {
			Map<MemoBindingResolver.GuardDecision,Boolean> values=new LinkedHashMap<>();
			for(int guard=0;guard<decisions.size();guard++)
				values.put(decisions.get(guard),(mask&(1<<guard))!=0);
			Object key=shapeContextKey(fixture.shapes(),new EvaluationContext(values,Map.of(),
				MemoBindingResolver.NO_SELECTION,4096));
			hashes.add(key.hashCode());
			Assert.assertEquals(key,shapeContextKey(fixture.shapes(),new EvaluationContext(values,Map.of(),
				MemoBindingResolver.NO_SELECTION,4096)));
		}
		Assert.assertTrue("1024 exact truth vectors need a well-distributed full-key hash: "+hashes.size(),
			hashes.size()>=900);
	}

	@Test
	public void canonicalGuardAliasRetainsSyntacticShapeIdentity() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){if(q){Y=matrix(1,2,3);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=f(p);}");
		int outer=fixture.facts().guards().stream().filter(guard ->
			fixture.facts().region(guard.parentRegionId()).kind()!=MemoProgramFacts.RegionKind.FUNCTION)
			.findFirst().orElseThrow().id();
		int inner=fixture.facts().guards().stream().filter(guard ->
			fixture.facts().region(guard.parentRegionId()).kind()==MemoProgramFacts.RegionKind.FUNCTION)
			.findFirst().orElseThrow().id();
		int call=fixture.facts().calls().get(0).id();
		var outerDecision=fixture.facts().guardDecision(outer,List.of());
		var innerDecision=fixture.facts().guardDecision(inner,List.of(call));
		Assert.assertEquals("fixture must exercise canonical guard aliasing",outerDecision,innerDecision);
		EvaluationContext outerContext=new EvaluationContext(Map.of(outerDecision,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(call));
		EvaluationContext innerContext=new EvaluationContext(Map.of(innerDecision,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(call));

		Object outerKey=shapeContextKey(fixture.shapes(),outerContext);
		Object innerKey=shapeContextKey(fixture.shapes(),innerContext);
		Assert.assertNotEquals("full shape keys retain syntactic guard/frame provenance",outerKey,innerKey);
		Assert.assertEquals("equal full contexts retain exact equality and the precomputed hash",
			outerKey,shapeContextKey(fixture.shapes(),outerContext));
		Assert.assertEquals(outerKey.hashCode(),shapeContextKey(fixture.shapes(),outerContext).hashCode());
	}

	@Test
	public void indexedGuardRelevanceMatchesExactPrefixReference() throws Exception {
		Fixture fixture=fixture("f=function(boolean q) return(matrix[double] Y){if(q){Y=matrix(1,2,3);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=f(p);}");
		int call=fixture.facts().calls().get(0).id();
		Set<Integer> dependencies=new HashSet<>();
		fixture.facts().guards().forEach(guard -> dependencies.add(guard.id()));
		List<List<Integer>> frames=List.of(List.of(),List.of(call),List.of(call,call));
		for(MemoProgramFacts.Guard guard:fixture.facts().guards())
			for(List<Integer> decisionFrame:frames) {
				var decision=fixture.facts().guardDecision(guard.id(),decisionFrame);
				for(List<Integer> evaluationFrame:frames)
					Assert.assertEquals("indexed OR-of-prefixes must equal the reference scan",
						fixture.shapes().referenceGuardRelevantForTesting(decision,dependencies,evaluationFrame),
						fixture.shapes().indexedGuardRelevantForTesting(decision,dependencies,evaluationFrame));
			}
		var synthetic=new MemoBindingResolver.GuardDecision(999,List.of(call,call),-1,List.of());
		Assert.assertEquals(fixture.shapes().referenceGuardRelevantForTesting(synthetic,dependencies,List.of(call)),
			fixture.shapes().indexedGuardRelevantForTesting(synthetic,dependencies,List.of(call)));
		long hits=fixture.shapes().reuseStatistics().guardRelevanceCacheHits();
		fixture.shapes().indexedGuardRelevantForTesting(
			fixture.facts().guardDecision(fixture.facts().guards().get(0).id(),List.of(call)),dependencies,List.of(call));
		Assert.assertTrue(fixture.shapes().reuseStatistics().guardRelevanceCacheHits()>hits);
	}

	private static Object shapeContextKey(MemoShapes shapes,EvaluationContext context) throws Exception {
		Method method=MemoShapes.class.getDeclaredMethod("shapeContextKey",EvaluationContext.class);
		method.setAccessible(true);
		return method.invoke(shapes,context);
	}

	private static Fixture fixture(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		return new Fixture(facts,shapes);
	}

	private record Fixture(MemoProgramFacts facts,MemoShapes shapes) { }
}
