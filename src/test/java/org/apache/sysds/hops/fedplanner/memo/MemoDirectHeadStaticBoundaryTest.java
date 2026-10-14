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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.DirectLoopHead;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact boundaries for certified-empty direct loop-head results. */
public class MemoDirectHeadStaticBoundaryTest {
	@Test public void everyCertifiedBindingIsExactEmptyAcrossAllOmittedContextFields() throws Exception {
		MemoProgramFacts facts=combinedFacts(); MemoBindingResolver resolver=new MemoBindingResolver(facts);
		Set<Integer> eligible=eligibleBindings(facts); Assert.assertFalse(eligible.isEmpty());
		MemoProgramFacts.LoopRecord loop=facts.loop(0); int guard=facts.guards().get(0).id();
		GuardDecision decision=facts.guardDecision(guard,List.of()); ThrowingSelection selection=new ThrowingSelection();
		EvaluationContext hostile=new EvaluationContext(Map.of(decision,true),
			Map.of(new LoopDecision(loop.id(),List.of()),LoopPhase.BACKEDGE),selection,1,List.of(999999),
			Map.of(new LoopDecision(loop.id(),List.of()),IterationPhase.LAST),
			Map.of(new LoopCardinalityDecision(loop.id(),List.of()),LoopCardinality.MANY));
		Set<DirectLoopHead> shared=null;
		for(int binding:eligible) {
			Set<DirectLoopHead> reference=resolver.directLoopHeadIdentities(List.of(binding),hostile);
			Set<DirectLoopHead> actual=resolver.directLoopHeadIdentities(binding,hostile);
			Assert.assertTrue(binding+" list reference",reference.isEmpty());
			Assert.assertTrue(binding+" certified result",actual.isEmpty());
			if(shared==null) shared=actual; else Assert.assertSame(shared,actual);
			Assert.assertSame(actual,resolver.directLoopHeadIdentities(binding,EvaluationContext.empty()));
		}
		Assert.assertEquals("certified path must not inspect selections",0,selection.calls);
	}

	@Test public void sharedEmptySetRetainsOrderedUnmodifiableLinkedSetBehavior() throws Exception {
		MemoProgramFacts facts=combinedFacts(); int binding=eligibleBindings(facts).iterator().next();
		Set<DirectLoopHead> empty=new MemoBindingResolver(facts)
			.directLoopHeadIdentities(binding,EvaluationContext.empty());
		Assert.assertFalse(empty.contains(null));
		Assert.assertThrows(UnsupportedOperationException.class,empty::clear);
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> empty.remove(new DirectLoopHead(0,0,List.of())));
		Spliterator<DirectLoopHead> spliterator=empty.spliterator();
		Assert.assertTrue(spliterator.hasCharacteristics(Spliterator.ORDERED));
		Assert.assertTrue(spliterator.hasCharacteristics(Spliterator.DISTINCT));
		Assert.assertTrue(spliterator.hasCharacteristics(Spliterator.SIZED));
		Assert.assertEquals(0,spliterator.estimateSize());
		Assert.assertFalse(spliterator.tryAdvance(ignored -> Assert.fail("empty spliterator advanced")));
	}

	@Test public void everyFallbackBindingPreservesOrderedUnknownAndCyclicResults() throws Exception {
		MemoProgramFacts facts=combinedFacts(); Set<Integer> eligible=eligibleBindings(facts);
		MemoBindingResolver resolver=new MemoBindingResolver(facts); EvaluationContext context=EvaluationContext.empty();
		for(var binding:facts.bindings()) if(!eligible.contains(binding.id())) assertParity(resolver,binding.id(),context);
		int select=facts.bindings().stream().filter(node -> node.kind()==BindingKind.SELECT
			&& "X".equals(node.label())).mapToInt(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
		Set<DirectLoopHead> unknown=assertParity(resolver,select,context);
		Assert.assertTrue("unknown SELECT preserves both ordered loop-head alternatives",unknown.size()>1);
		for(var carried:facts.loop(0).carriedValues()) {
			assertParity(resolver,carried.headBindingId(),context);
			assertParity(resolver,carried.backedgeBindingId(),context);
		}
	}

	@Test public void formalAndCallReturnFramesAndStructuredPhasesPreserveReference() throws Exception {
		MemoProgramFacts facts=combinedFacts(); MemoProgramFacts.CallBinding call=facts.call(0);
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		int formal=facts.functionSummary(call.functionSummaryId()).formalBindingIds().get(0);
		Set<DirectLoopHead> formalHeads=assertParity(resolver,formal,
			EvaluationContext.empty().withCallFrame(List.of(call.id())));
		Assert.assertFalse(formalHeads.isEmpty());
		assertParity(resolver,call.returnBindingIds().get(0),EvaluationContext.empty());

		MemoProgramFacts phaseFacts=phaseFacts(); MemoBindingResolver phaseResolver=new MemoBindingResolver(phaseFacts);
		int select=phaseFacts.bindings().stream().filter(node -> node.kind()==BindingKind.SELECT
			&& "X".equals(node.label())).mapToInt(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
		int loop=phaseFacts.loop(0).id();
		Set<DirectLoopHead> first=assertParity(phaseResolver,select,
			EvaluationContext.empty().withIterationPhase(loop,IterationPhase.FIRST));
		Set<DirectLoopHead> last=assertParity(phaseResolver,select,
			EvaluationContext.empty().withIterationPhase(loop,IterationPhase.LAST));
		Assert.assertNotEquals(first,last);

		EvaluationContext invalidFrame=EvaluationContext.empty().withCallFrame(List.of(999999));
		assertSameFailure(failure(() -> resolver.directLoopHeadIdentities(List.of(formal),invalidFrame)),
			failure(() -> resolver.directLoopHeadIdentities(formal,invalidFrame)));
	}

	@Test public void validationAndConstructorPrecedenceRemainUnchanged() throws Exception {
		MemoProgramFacts facts=combinedFacts(); MemoBindingResolver resolver=new MemoBindingResolver(facts);
		int valid=eligibleBindings(facts).iterator().next();
		assertSameFailure(failure(() -> resolver.directLoopHeadIdentities(List.of(-1),EvaluationContext.empty())),
			failure(() -> resolver.directLoopHeadIdentities(-1,EvaluationContext.empty())));
		assertSameFailure(failure(() -> resolver.directLoopHeadIdentities(List.of(valid),null)),
			failure(() -> resolver.directLoopHeadIdentities(valid,null)));
		assertSameFailure(failure(() -> resolver.directLoopHeadIdentities(List.of(-1),null)),
			failure(() -> resolver.directLoopHeadIdentities(-1,null)));
		Throwable negativeWithFacts=failure(() -> new MemoBindingResolver(facts,-1));
		Throwable negativeWithNull=failure(() -> new MemoBindingResolver(null,-1));
		Assert.assertEquals(IllegalArgumentException.class,negativeWithFacts.getClass());
		assertSameFailure(negativeWithFacts,negativeWithNull);
		Assert.assertThrows(NullPointerException.class,() -> new MemoBindingResolver(null,0));
	}

	private static Set<DirectLoopHead> assertParity(MemoBindingResolver resolver,int binding,
		EvaluationContext context) {
		Set<DirectLoopHead> expected=resolver.directLoopHeadIdentities(List.of(binding),context);
		Set<DirectLoopHead> actual=resolver.directLoopHeadIdentities(binding,context);
		Assert.assertEquals(new ArrayList<>(expected),new ArrayList<>(actual)); return actual;
	}
	private static Set<Integer> eligibleBindings(MemoProgramFacts facts) {
		Set<Integer> concreteReturns=new HashSet<>();
		for(var call:facts.calls()) for(int returned:call.returnBindingIds()) {
			var node=facts.binding(returned);
			if(node.kind()==BindingKind.CALL_RETURN && node.children().size()>1)
				concreteReturns.add(node.children().get(1));
		}
		Set<Integer> result=new HashSet<>();
		for(var binding:facts.bindings()) if(binding.kind()==BindingKind.DEFINITION
			&& binding.children().isEmpty() && !binding.label().endsWith("#for-iterator")
			&& binding.referenceId()>=0 && binding.referenceId()<facts.occurrences().size()
			&& !concreteReturns.contains(binding.id())) result.add(binding.id());
		return result;
	}
	private static MemoProgramFacts combinedFacts() throws Exception {
		return analyze("f=function(matrix[double] Q) return(matrix[double] Y){Y=abs(Q);}"+
			"A=matrix(1,2,2);B=matrix(2,2,2);p=as.scalar(rand(rows=1,cols=1))>0.5;i=1;"+
			"while(i<3){if(p){X=A;}else{X=B;}R=f(A);A=B+R;B=A+1;i=i+1;}"+
			"print(sum(X)+sum(A)+sum(B));");
	}
	private static MemoProgramFacts phaseFacts() throws Exception {
		return analyze("A=matrix(1,2,2);B=matrix(2,2,2);X=matrix(0,2,2);"+
			"for(i in 1:3){if(i==3){X=A;}else{X=B;}A=A+1;B=B+1;}print(sum(X));");
	}
	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,
			new HashMap<>()); DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
	private static Throwable failure(ThrowingRunnable action) {
		try { action.run(); return null; } catch(Throwable error) { return error; }
	}
	private static void assertSameFailure(Throwable expected,Throwable actual) {
		Assert.assertNotNull(expected); Assert.assertNotNull(actual);
		Assert.assertEquals(expected.getClass(),actual.getClass()); Assert.assertEquals(expected.getMessage(),actual.getMessage());
	}
	@FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
	private static final class ThrowingSelection implements SelectionLookup {
		private int calls;
		@Override public int selectedRule(int owner) { calls++; throw new AssertionError("selection read"); }
		@Override public MemoPartition selectedLayout(int owner) { calls++; throw new AssertionError("selection read"); }
		@Override public boolean headIsBound(int loop,int slot) { calls++; throw new AssertionError("selection read"); }
		@Override public MemoPartition selectedHeadLayout(int loop,int slot) {
			calls++; throw new AssertionError("selection read");
		}
	}
}
