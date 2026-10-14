/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoLegalityTableTest {
	@Test
	public void indexedTablesMatchOriginalPredicatesExhaustively() throws Exception {
		MemoProgramFacts facts=MemoProgramFacts.analyze(parsed(
			"X=0;for(i in 1:3){if(i==3){X=3;}else{X=1;}}print(X);"));
		for(MemoProgramFacts.LoopRecord loop:facts.loops()) {
			for(MemoProgramFacts.LoopCardinality cardinality:MemoProgramFacts.LoopCardinality.values()) {
				boolean expected=loop.executionCases().stream().anyMatch(value -> value.cardinality()==cardinality);
				Assert.assertEquals(expected,facts.loopCardinalityReachable(loop.id(),cardinality));
				for(MemoProgramFacts.IterationPhase phase:MemoProgramFacts.IterationPhase.values()) {
					boolean phaseExpected=loop.executionCases().stream()
						.filter(value -> value.cardinality()==cardinality)
						.anyMatch(value -> value.bodyPhases().contains(phase));
					Assert.assertEquals(phaseExpected,facts.loopPhaseReachable(loop.id(),cardinality,phase));
				}
			}
		}
		for(MemoProgramFacts.Guard guard:facts.guards())
			Assert.assertEquals(facts.enclosingLoop(guard.parentRegionId()),facts.guardEnclosingLoop(guard.id()));
	}

	@Test
	public void nullAndMalformedInputsUseTheOriginalPredicatePath() throws Exception {
		MemoProgramFacts facts=MemoProgramFacts.analyze(parsed("X=0;for(i in 1:1){X=X+1;}print(X);"));
		int loop=facts.loop(0).id();
		Assert.assertEquals(facts.loopExecutionCases(loop).stream().anyMatch(value -> value.cardinality()==null),
			facts.loopCardinalityReachable(loop,null));
		assertSameFailure(() -> facts.loopExecutionCases(loop).stream()
			.filter(value -> value.cardinality()==MemoProgramFacts.LoopCardinality.ONE)
			.anyMatch(value -> value.bodyPhases().contains(null)),
			() -> facts.loopPhaseReachable(loop,MemoProgramFacts.LoopCardinality.ONE,null));
		assertSameFailure(() -> facts.loopExecutionCases(-1).stream().anyMatch(value -> true),
			() -> facts.loopCardinalityReachable(-1,MemoProgramFacts.LoopCardinality.ONE));
		assertSameFailure(() -> facts.loopExecutionCases(facts.loops().size()).stream().anyMatch(value -> true),
			() -> facts.loopPhaseReachable(facts.loops().size(),MemoProgramFacts.LoopCardinality.ONE,
				MemoProgramFacts.IterationPhase.FIRST));
		assertSameFailure(() -> facts.enclosingLoop(facts.guard(-1).parentRegionId()),
			() -> facts.guardEnclosingLoop(-1));
		assertSameFailure(() -> facts.enclosingLoop(facts.guard(facts.guards().size()).parentRegionId()),
			() -> facts.guardEnclosingLoop(facts.guards().size()));
	}

	@Test public void cardinalityContradictionPreservesDetail() throws Exception {
		assertContradiction("X=0;for(i in 1:1){X=X+1;}print(X);",
			facts -> MemoBindingResolver.EvaluationContext.empty()
				.withLoopCardinality(0,MemoProgramFacts.LoopCardinality.ZERO),
			"loop cardinality contradicts proven iteration count");
	}

	@Test public void phaseContradictionPreservesDetail() throws Exception {
		assertContradiction("X=0;for(i in 1:1){X=X+1;}print(X);",
			facts -> MemoBindingResolver.EvaluationContext.empty()
				.withLoopCardinality(0,MemoProgramFacts.LoopCardinality.ONE)
				.withIterationPhase(0,MemoProgramFacts.IterationPhase.LAST),
			"loop phase is unreachable for cardinality");
	}

	@Test public void guardContradictionAndCompetingCheckPreserveOrder() throws Exception {
		String script="X=0;for(i in 1:3){if(i==3){X=3;}else{X=1;}}print(X);";
		assertContradiction(script,facts -> guardContext(facts,false),
			"explicit guard contradicts proven loop phase");
		assertContradiction(script,facts -> guardContext(facts,true),
			"loop cardinality contradicts proven iteration count");
	}

	private static MemoBindingResolver.EvaluationContext guardContext(MemoProgramFacts facts,
		boolean competingCardinality) {
		MemoProgramFacts.Guard guard=facts.guards().stream()
			.filter(value -> facts.enclosingLoop(value.parentRegionId())==0).findFirst().orElseThrow();
		MemoProgramFacts.IterationPhase phase=Arrays.stream(MemoProgramFacts.IterationPhase.values())
			.filter(value -> facts.guardValue(0,guard.id(),value)!=null).findFirst().orElseThrow();
		boolean wrong=!facts.guardValue(0,guard.id(),phase);
		Map<MemoBindingResolver.LoopCardinalityDecision,MemoProgramFacts.LoopCardinality> cardinalities=
			competingCardinality ? Map.of(new MemoBindingResolver.LoopCardinalityDecision(0,List.of()),
				MemoProgramFacts.LoopCardinality.ONE) : Map.of();
		return new MemoBindingResolver.EvaluationContext(
			Map.of(new MemoBindingResolver.GuardDecision(guard.id(),List.of()),wrong),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(),
			Map.of(new MemoBindingResolver.LoopDecision(0,List.of()),phase),cardinalities);
	}

	private static void assertContradiction(String script,Context context,String detail) throws Exception {
		MemoProgramFacts facts=MemoProgramFacts.analyze(parsed(script));
		int[] callbacks={0};
		MemoBindingResolver.VisitResult result=new MemoBindingResolver(facts).visitJoint(
			List.of(facts.bindings().get(0).id()),context.get(facts),environment -> { callbacks[0]++; return true; });
		Assert.assertEquals(MemoBindingResolver.VisitStatus.COMPLETE,result.status());
		Assert.assertEquals(0,result.environmentCount());
		Assert.assertEquals(detail,result.detail());
		Assert.assertEquals(0,callbacks[0]);
	}

	private static DMLProgram parsed(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static void assertSameFailure(ThrowingRunnable expected,ThrowingRunnable actual) {
		Throwable left=capture(expected),right=capture(actual);
		Assert.assertNotNull(left);
		Assert.assertNotNull(right);
		Assert.assertEquals(left.getClass(),right.getClass());
		// HotSpot may reuse a stackless implicit exception after this shared throw
		// site becomes hot in the full suite. Its omitted detail is not a semantic difference.
		if(!omittedImplicitDetail(left) && !omittedImplicitDetail(right))
			Assert.assertEquals(left.getMessage(),right.getMessage());
	}

	private static boolean omittedImplicitDetail(Throwable error) {
		return (error instanceof ArrayIndexOutOfBoundsException || error instanceof NullPointerException)
			&& error.getMessage()==null && error.getStackTrace().length==0;
	}

	private static Throwable capture(ThrowingRunnable action) {
		try { action.run(); return null; }
		catch(Throwable error) { return error; }
	}

	private interface ThrowingRunnable { void run() throws Exception; }
	private interface Context { MemoBindingResolver.EvaluationContext get(MemoProgramFacts facts); }
}
