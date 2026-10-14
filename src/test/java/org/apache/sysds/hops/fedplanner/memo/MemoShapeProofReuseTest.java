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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoShapeProofReuseTest {
	@Test
	public void completedBindingGuardQueryIsReusedWithExactContextIdentity() throws Exception {
		DMLProgram program=parse("A=matrix(0,1,1);for(i in 1:3){if(i==1){A=matrix(2,4,2);}"
			+"else{A=abs(A);}print(sum(A));}print(sum(A));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes cached=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),32,0);
		MemoShapes reference=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),0,0);
		MemoProgramFacts.Occurrence consumer=abs(facts);
		int binding=facts.inputBinding(consumer.id(),0);
		EvaluationContext middle=phase(IterationPhase.MIDDLE,LoopCardinality.MANY,
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of());

		MemoShapes.CostGuardSearch expected=reference.firstUnfixedCostGuardForBinding(binding,middle);
		MemoShapes.CostGuardSearch first=cached.firstUnfixedCostGuardForBinding(binding,middle);
		MemoShapes.CostGuardSearch second=cached.firstUnfixedCostGuardForBinding(binding,middle);
		assertSemanticResult(expected,first);
		assertSemanticResult(first,second);
		Assert.assertEquals(2,cached.reuseStatistics().bindingGuardRequests());
		Assert.assertEquals(1,cached.reuseStatistics().bindingGuardCacheHits());

		EvaluationContext last=phase(IterationPhase.LAST,LoopCardinality.MANY,
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of());
		EvaluationContext shallow=phase(IterationPhase.MIDDLE,LoopCardinality.MANY,
			MemoBindingResolver.NO_SELECTION,17,List.of(),Map.of());
		EvaluationContext one=phase(IterationPhase.FIRST,LoopCardinality.ONE,
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of());
		cached.firstUnfixedCostGuardForBinding(binding,last);
		cached.firstUnfixedCostGuardForBinding(binding,shallow);
		cached.firstUnfixedCostGuardForBinding(binding,one);
		Assert.assertEquals("phase/cardinality/maxDepth must not collide",5,
			cached.reuseStatistics().bindingGuardRequests());
		Assert.assertEquals(1,cached.reuseStatistics().bindingGuardCacheHits());
	}

	@Test
	public void repeatHeadProofIsReusedOnlyAfterACompletedProof() throws Exception {
		DMLProgram program=parse("A=matrix(0,1,1);for(i in 1:3){if(i==1){A=matrix(2,4,2);}"
			+"else{A=abs(A);}print(sum(A));}print(sum(A));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes cached=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),0,32);
		MemoShapes reference=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),0,0);
		MemoProgramFacts.Occurrence consumer=abs(facts);
		int binding=facts.inputBinding(consumer.id(),0);
		EvaluationContext middle=phase(IterationPhase.MIDDLE,LoopCardinality.MANY,
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of());

		MemoShapes.CostGuardSearch expected=reference.firstUnfixedCostGuardForBinding(binding,middle);
		MemoShapes.CostGuardSearch first=cached.firstUnfixedCostGuardForBinding(binding,middle);
		MemoShapes.CostGuardSearch second=cached.firstUnfixedCostGuardForBinding(binding,middle);
		assertSemanticResult(expected,first);
		assertSemanticResult(first,second);
		Assert.assertTrue("fixture must exercise the repeat proof",cached.reuseStatistics().repeatProofRequests()>1);
		Assert.assertTrue("only a completed proof may be reused",cached.reuseStatistics().repeatProofCacheHits()>0);
	}

	@Test
	public void mutableHeadBindingIsSnapshottedRatherThanUsingLookupIdentity() throws Exception {
		DMLProgram program=parse("A=matrix(0,1,1);for(i in 1:3){if(i==1){A=matrix(2,4,2);}"
			+"else{A=abs(A);}print(sum(A));}print(sum(A));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),32,0);
		MemoProgramFacts.Occurrence consumer=abs(facts);
		int binding=facts.inputBinding(consumer.id(),0);
		boolean[] bound={false};
		MemoBindingResolver.SelectionLookup lookup=new MemoBindingResolver.SelectionLookup() {
			@Override public boolean headIsBound(int loop,int slot) { return bound[0]; }
		};
		EvaluationContext context=phase(IterationPhase.MIDDLE,LoopCardinality.MANY,
			lookup,4096,List.of(),Map.of());

		shapes.firstUnfixedCostGuardForBinding(binding,context);
		shapes.firstUnfixedCostGuardForBinding(binding,context);
		Assert.assertEquals(1,shapes.reuseStatistics().bindingGuardCacheHits());
		bound[0]=true;
		shapes.firstUnfixedCostGuardForBinding(binding,context);
		Assert.assertEquals("changed head binding requires a distinct semantic key",1,
			shapes.reuseStatistics().bindingGuardCacheHits());
	}

	@Test
	public void canonicalGuardEqualityDoesNotEraseSyntacticFrameFromCacheKey() throws Exception {
		DMLProgram program=parse("A=matrix(1,rows=8,cols=4);B=abs(A);print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),32,0);
		int binding=facts.inputBinding(abs(facts).id(),0);
		GuardDecision first=new GuardDecision(3,List.of(7),91,List.of(2));
		GuardDecision second=new GuardDecision(4,List.of(8),91,List.of(2));
		Assert.assertEquals("fixture decisions share canonical value identity",first,second);

		shapes.firstUnfixedCostGuardForBinding(binding,new EvaluationContext(Map.of(first,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of(),Map.of()));
		shapes.firstUnfixedCostGuardForBinding(binding,new EvaluationContext(Map.of(second,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096,List.of(),Map.of(),Map.of()));
		Assert.assertEquals("syntactic frames remain part of the full proof context",0,
			shapes.reuseStatistics().bindingGuardCacheHits());
	}

	@Test
	public void boundedCacheEvictsLeastRecentlyUsedCompletedQuery() throws Exception {
		DMLProgram program=parse("A=matrix(1,rows=8,cols=4);B=abs(A);print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),1,0);
		int binding=facts.inputBinding(abs(facts).id(),0);
		EvaluationContext deep=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,
			4096,List.of(),Map.of(),Map.of());
		EvaluationContext shallow=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,
			17,List.of(),Map.of(),Map.of());

		shapes.firstUnfixedCostGuardForBinding(binding,deep);
		shapes.firstUnfixedCostGuardForBinding(binding,shallow);
		shapes.firstUnfixedCostGuardForBinding(binding,deep);
		Assert.assertEquals(3,shapes.reuseStatistics().bindingGuardRequests());
		Assert.assertEquals(0,shapes.reuseStatistics().bindingGuardCacheHits());
	}

	@Test
	public void cachedUnboundDependencyRemainsUnknown() throws Exception {
		DMLProgram program=parse("for(i in 1:2){A=matrix(1,rows=8,cols=4);}print(sum(A));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		int unbound=facts.bindings().stream()
			.filter(binding -> binding.kind()==MemoProgramFacts.BindingKind.UNBOUND)
			.findFirst().orElseThrow().id();
		MemoShapes cached=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),32,0);
		MemoShapes reference=MemoShapes.analyze(facts,MemoSources.acquire(facts,null),0,0);

		MemoShapes.CostGuardSearch expected=
			reference.firstUnfixedCostGuardForBinding(unbound,EvaluationContext.empty());
		MemoShapes.CostGuardSearch first=
			cached.firstUnfixedCostGuardForBinding(unbound,EvaluationContext.empty());
		MemoShapes.CostGuardSearch second=
			cached.firstUnfixedCostGuardForBinding(unbound,EvaluationContext.empty());
		Assert.assertTrue(expected.unresolved());
		assertSemanticResult(expected,first);
		assertSemanticResult(first,second);
		Assert.assertEquals(1,cached.reuseStatistics().bindingGuardCacheHits());
	}

	private static void assertSemanticResult(MemoShapes.CostGuardSearch expected,
		MemoShapes.CostGuardSearch actual) {
		Assert.assertEquals(expected.guard(),actual.guard());
		Assert.assertEquals(expected.unresolved(),actual.unresolved());
	}

	private static EvaluationContext phase(IterationPhase phase,LoopCardinality cardinality,
		MemoBindingResolver.SelectionLookup selections,int maxDepth,List<Integer> frame,
		Map<GuardDecision,Boolean> guards) {
		LoopDecision loop=new LoopDecision(0,frame);
		return new EvaluationContext(guards,Map.of(loop,phase==IterationPhase.FIRST
			? LoopPhase.ENTRY : LoopPhase.BACKEDGE),selections,maxDepth,frame,
			Map.of(loop,phase),Map.of(new LoopCardinalityDecision(0,frame),cardinality));
	}

	private static MemoProgramFacts.Occurrence abs(MemoProgramFacts facts) {
		return facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}
