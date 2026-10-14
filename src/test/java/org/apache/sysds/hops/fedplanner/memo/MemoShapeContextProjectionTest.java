/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoShapeContextProjectionTest {
	@Test
	public void workloadModelsReuseProjectedShapeContexts() throws Exception {
		for(String workload:new String[] {"logreg","glm"}) {
			MemoProgramModel model=MemoFederatedModelTest.rewrittenWorkloadModel(workload);
			Field field=MemoProgramModel.class.getDeclaredField("shapes");
			field.setAccessible(true);
			MemoShapes.ReuseStatistics statistics=((MemoShapes)field.get(model)).reuseStatistics();
			System.out.println("MEMO_PROJECTED_SHAPE_REUSE workload="+workload+" statistics="+statistics);
			Assert.assertTrue(statistics.phaseShapeRequests()>0);
			Assert.assertTrue("projected contexts must produce real request-local reuse",
				statistics.phaseShapeCacheHits()>0);
		}
	}

	@Test
	public void irrelevantGuardCollapsesButPayloadGuardRemains() throws Exception {
		Fixture fixture=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"q=as.scalar(rand(rows=1,cols=1))>0.5;A=matrix(1,rows=2,cols=3);"
			+"if(p){A=matrix(2,rows=4,cols=5);}if(q){print(1);}B=abs(A);print(sum(B));");
		MemoProgramFacts.Occurrence abs=absOccurrence(fixture.facts());
		EvaluationContext pTrueQTrue=context(fixture.facts(),Map.of(0,true,1,true));
		EvaluationContext pTrueQFalse=context(fixture.facts(),Map.of(0,true,1,false));
		EvaluationContext pFalseQTrue=context(fixture.facts(),Map.of(0,false,1,true));

		Object first=fixture.shapes().projectedShapeKeyForOccurrence(abs.id(),pTrueQTrue);
		Assert.assertEquals(first,fixture.shapes().projectedShapeKeyForOccurrence(abs.id(),pTrueQFalse));
		Assert.assertNotEquals(first,fixture.shapes().projectedShapeKeyForOccurrence(abs.id(),pFalseQTrue));

		fixture.shapes().shapeAt(abs.id(),pTrueQTrue);
		long hits=fixture.shapes().reuseStatistics().occurrenceTransferCacheHits();
		fixture.shapes().shapeAt(abs.id(),pTrueQFalse);
		Assert.assertTrue("the irrelevant guard must reuse the completed pure transfer",
			fixture.shapes().reuseStatistics().occurrenceTransferCacheHits()>hits);
	}

	@Test
	public void completionOnlyAndSecondaryReturnGuardsRemainInTheKey() throws Exception {
		Fixture fixture=fixture("f=function(boolean q,matrix[double] X) "
			+"return(matrix[double] Y,matrix[double] Z){Y=abs(X);if(q){Z=X;}}"
			+"X=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"[A,B]=f(q,X);C=abs(A);print(sum(C));");
		MemoProgramFacts.CallBinding call=fixture.facts().calls().get(0);
		int functionGuard=fixture.facts().guards().stream().filter(guard ->
			fixture.facts().region(guard.parentRegionId()).kind()==MemoProgramFacts.RegionKind.FUNCTION)
			.findFirst().orElseThrow().id();
		var decision=fixture.facts().guardDecision(functionGuard,List.of(call.id()));
		MemoProgramFacts.Occurrence consumer=absOccurrences(fixture.facts()).stream()
			.reduce((left,right) -> right).orElseThrow();
		EvaluationContext whenTrue=new EvaluationContext(Map.of(decision,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096);
		EvaluationContext whenFalse=new EvaluationContext(Map.of(decision,false),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096);
		Assert.assertNotEquals("a missing secondary return changes call completion",
			fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),whenTrue),
			fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),whenFalse));
	}

	@Test
	public void futureUnrelatedCallCompletionDoesNotPolluteAnEarlierOccurrence() throws Exception {
		Fixture fixture=fixture("f=function(boolean p) return(matrix[double] Y){if(p){Y=matrix(1,2,3);}}"
			+"g=function(boolean q) return(matrix[double] Z){if(q){Z=matrix(1,7,9);}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(p);C=abs(A);B=g(q);print(sum(C));");
		MemoProgramFacts.Occurrence consumer=absOccurrence(fixture.facts());
		MemoProgramFacts.Guard gGuard=fixture.facts().guards().stream().filter(guard ->
			fixture.facts().region(guard.parentRegionId()).kind()==MemoProgramFacts.RegionKind.FUNCTION)
			.reduce((left,right) -> right).orElseThrow();
		int gCall=fixture.facts().calls().stream().filter(call ->
			fixture.facts().functionSummary(call.functionSummaryId()).functionKey().contains("g"))
			.findFirst().orElseThrow().id();
		var decision=fixture.facts().guardDecision(gGuard.id(),List.of(gCall));
		EvaluationContext left=new EvaluationContext(Map.of(decision,true),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096);
		EvaluationContext right=new EvaluationContext(Map.of(decision,false),Map.of(),
			MemoBindingResolver.NO_SELECTION,4096);
		Assert.assertEquals(fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),left),
			fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),right));
	}

	@Test
	public void relevantHeadAndCardinalityRemainWhileUnrelatedLoopCollapses() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,3);for(i in 1:2){A=abs(A);}"
			+"B=matrix(1,7,9);for(j in 1:2){B=abs(B);}C=abs(A);print(sum(C));");
		MemoProgramFacts.Occurrence consumer=absOccurrences(fixture.facts()).stream()
			.filter(value -> value.context().contains("main") && value.id()>0)
			.reduce((left,right) -> right).orElseThrow();
		MemoProgramFacts.CarriedValue first=fixture.facts().loop(0).carriedValues().get(0);
		MemoProgramFacts.CarriedValue second=fixture.facts().loop(1).carriedValues().get(0);
		Object base=fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),
			heads(Set.of()));
		Object unrelated=fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),
			heads(Set.of(slot(1,second.slot()))));
		Object relevant=fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),
			heads(Set.of(slot(0,first.slot()))));
		Assert.assertEquals(base,unrelated);
		Assert.assertNotEquals(base,relevant);

		LoopCardinalityDecision loop0=new LoopCardinalityDecision(0,List.of());
		LoopCardinalityDecision loop1=new LoopCardinalityDecision(1,List.of());
		EvaluationContext one=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,4096,
			List.of(),Map.of(),Map.of(loop0,LoopCardinality.ONE,loop1,LoopCardinality.ONE));
		EvaluationContext unrelatedMany=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,4096,
			List.of(),Map.of(),Map.of(loop0,LoopCardinality.ONE,loop1,LoopCardinality.MANY));
		EvaluationContext relevantMany=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,4096,
			List.of(),Map.of(),Map.of(loop0,LoopCardinality.MANY,loop1,LoopCardinality.ONE));
		Assert.assertEquals(fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),one),
			fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),unrelatedMany));
		Assert.assertNotEquals(fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),one),
			fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),relevantMany));
	}

	@Test
	public void callFrameAndDepthAreNeverProjectedAway() throws Exception {
		Fixture fixture=fixture("A=matrix(1,2,3);B=abs(A);print(sum(B));");
		MemoProgramFacts.Occurrence consumer=absOccurrence(fixture.facts());
		EvaluationContext deep=EvaluationContext.empty();
		EvaluationContext shallow=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,1);
		EvaluationContext framed=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,4096,
			List.of(77));
		Object key=fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),deep);
		Assert.assertNotEquals(key,fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),shallow));
		Assert.assertNotEquals(key,fixture.shapes().projectedShapeKeyForOccurrence(consumer.id(),framed));
	}

	@Test
	public void inlinedConcreteReturnKeepsItsUniqueSymbolicDependencies() throws Exception {
		Fixture fixture=fixture("f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;X=matrix(1,rows=8,cols=4);"
			+"if(p){X=matrix(1,rows=7,cols=3);}A=f(X);B=abs(A);print(sum(B));");
		MemoProgramFacts.BindingNode wrapper=fixture.facts().calls().stream()
			.flatMap(call -> call.returnBindingIds().stream())
			.map(fixture.facts()::binding)
			.filter(binding -> binding.kind()==MemoProgramFacts.BindingKind.CALL_RETURN
				&& binding.children().size()>1).findFirst().orElseThrow();
		int concrete=wrapper.children().get(1);
		Field dependenciesField=MemoShapes.class.getDeclaredField("shapeDependenciesByBinding");
		dependenciesField.setAccessible(true);
		List<?> dependencies=(List<?>)dependenciesField.get(fixture.shapes());
		Object symbolicDependencies=dependencies.get(wrapper.id());
		Object concreteDependencies=dependencies.get(concrete);
		boolean nonempty=false;
		for(String component:List.of("loops","guards","heads")) {
			Field componentField=symbolicDependencies.getClass().getDeclaredField(component);
			componentField.setAccessible(true);
			Set<?> symbolic=(Set<?>)componentField.get(symbolicDependencies);
			Set<?> concreteSet=(Set<?>)componentField.get(concreteDependencies);
			nonempty|=!symbolic.isEmpty();
			Assert.assertTrue("resolver-remapped concrete payload must retain symbolic "+component,
				concreteSet.containsAll(symbolic));
		}
		Assert.assertTrue("fixture must give the symbolic return a contextual dependency",nonempty);
	}

	@Test
	public void unresolvedResultIsNeverPublishedAcrossQueryOrder() throws Exception {
		Fixture fixture=fixture("g=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+"f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
			+"if(q){Y=g(X);}else{stop('invalid input');}}"
			+"X=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(q,X);B=t(A);print(sum(B));");
		MemoProgramFacts.Occurrence transpose=fixture.facts().occurrences().stream()
			.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp()==ReOrgOp.TRANS)
			.findFirst().orElseThrow();
		EvaluationContext shallow=new EvaluationContext(Map.of(),Map.of(),
			MemoBindingResolver.NO_SELECTION,1);
		EvaluationContext deep=EvaluationContext.empty();

		Assert.assertFalse(fixture.shapes().inputShape(transpose.id(),0,shallow).exact());
		Assert.assertTrue("a later complete query must not borrow the unresolved result",
			fixture.shapes().inputShape(transpose.id(),0,deep).exact());

		MemoShapes reverse=MemoShapes.analyze(fixture.facts(),MemoSources.acquire(fixture.facts(),null));
		Assert.assertTrue(reverse.inputShape(transpose.id(),0,deep).exact());
		Assert.assertFalse("a complete result must not hide a later depth-limited query",
			reverse.inputShape(transpose.id(),0,shallow).exact());
	}

	private static EvaluationContext context(MemoProgramFacts facts,Map<Integer,Boolean> values) {
		Map<MemoBindingResolver.GuardDecision,Boolean> guards=new HashMap<>();
		for(var entry:values.entrySet()) guards.put(facts.guardDecision(entry.getKey(),List.of()),
			facts.canonicalGuardValue(entry.getKey(),List.of(),entry.getValue()));
		return new EvaluationContext(guards,Map.of(),MemoBindingResolver.NO_SELECTION,4096);
	}

	private static EvaluationContext heads(Set<String> bound) {
		SelectionLookup selections=new SelectionLookup() {
			@Override public boolean headIsBound(int loop,int slot) { return bound.contains(slot(loop,slot)); }
		};
		return new EvaluationContext(Map.of(),Map.of(),selections,4096);
	}

	private static String slot(int loop,int slot) { return loop+":"+slot; }

	private static MemoProgramFacts.Occurrence absOccurrence(MemoProgramFacts facts) {
		return absOccurrences(facts).get(0);
	}

	private static List<MemoProgramFacts.Occurrence> absOccurrences(MemoProgramFacts facts) {
		return facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS).toList();
	}

	private static Fixture fixture(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		return new Fixture(facts,MemoShapes.analyze(facts,MemoSources.acquire(facts,null)));
	}

	private record Fixture(MemoProgramFacts facts,MemoShapes shapes) { }
}
