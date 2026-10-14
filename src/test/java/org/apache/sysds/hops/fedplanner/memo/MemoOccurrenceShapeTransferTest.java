/* Licensed to the Apache Software Foundation (ASF) under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoOccurrenceShapeTransferTest {
	@Test public void sharedAliasRetainsEarlierShapeWhenTheLaterShapeIsMissing() {
		Hop shared=new DataOp("A",DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,
			"A",8,4,32,1000);
		BinaryOp template=new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,OpOp2.PLUS,shared,shared);
		MemoShapes.Shape first=new MemoShapes.Shape(DataType.MATRIX,ValueType.FP64,16,4,64,
			MemoShapes.Knowledge.EXACT);
		Assert.assertEquals(List.of(first,first),
			MemoShapes.effectiveInputShapes(template,Arrays.asList(first,null)));
	}

	@Test public void equivalentGuardedInputsReuseOnlyThePureTransfer() throws Exception {
		Fixture fixture=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+ "if(p){A=matrix(1,rows=2,cols=3);}else{A=matrix(2,rows=2,cols=3);}"
			+ "B=abs(A);print(sum(B));");
		MemoProgramFacts.Occurrence abs=abs(fixture.facts());
		GuardDecision guard=fixture.facts().guardDecision(fixture.facts().guards().get(0).id(),List.of());
		EvaluationContext whenTrue=context(guard,true),whenFalse=context(guard,false);
		MemoShapes reference=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,0);
		MemoShapes cached=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,4096);

		Assert.assertEquals(reference.shapeAt(abs.id(),whenTrue),cached.shapeAt(abs.id(),whenTrue));
		Assert.assertEquals(reference.shapeAt(abs.id(),whenFalse),cached.shapeAt(abs.id(),whenFalse));
		Assert.assertTrue("equal transfers reached through distinct guard contexts must avoid a clone",
			cached.reuseStatistics().occurrenceTransferCacheHits()>0);
		Assert.assertEquals(0,reference.reuseStatistics().occurrenceTransferCacheHits());
	}

	@Test public void int64AndFp64SequenceSubstitutionsRemainInTheTransferKey() throws Exception {
		assertContextualRows("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+ "if(p){r=2;}else{r=4;}A=matrix(1,rows=r,cols=2);B=abs(A);print(sum(B));",2,4);
		assertContextualRows("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+ "if(p){n=10.0;}else{n=100.0;}A=seq(1,n,1);B=abs(A);print(sum(B));",10,100);
	}

	@Test public void boundedDepthNeverBorrowsACompletedTransfer() throws Exception {
		Fixture fixture=fixture("g=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"
			+ "f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
			+ "if(q){Y=g(X);}else{stop('invalid input');}}"
			+ "X=matrix(1,rows=8,cols=4);q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+ "A=f(q,X);B=t(A);print(sum(B));");
		MemoProgramFacts.Occurrence transpose=fixture.facts().occurrences().stream()
			.filter(value -> value.hop() instanceof ReorgOp reorg && reorg.getOp()==ReOrgOp.TRANS)
			.findFirst().orElseThrow();
		EvaluationContext shallow=new EvaluationContext(Map.of(),Map.of(),
			MemoBindingResolver.NO_SELECTION,1);
		EvaluationContext deep=EvaluationContext.empty();
		MemoShapes reference=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,0);
		MemoShapes cached=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,4096);
		MemoShapes.Shape shallowReference=reference.shapeAt(transpose.id(),shallow);
		MemoShapes.Shape deepReference=reference.shapeAt(transpose.id(),deep);
		Assert.assertFalse("bounded nested return must remain unresolved",shallowReference.exact());
		Assert.assertTrue("the complete nested return must retain its exact shape",deepReference.exact());
		Assert.assertEquals(shallowReference,cached.shapeAt(transpose.id(),shallow));
		Assert.assertEquals(deepReference,cached.shapeAt(transpose.id(),deep));

		MemoShapes reverse=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,4096);
		Assert.assertEquals(deepReference,reverse.shapeAt(transpose.id(),deep));
		Assert.assertEquals(shallowReference,reverse.shapeAt(transpose.id(),shallow));
	}

	@Test public void liveLoopHeadSelectionsRemainContextualBeforeTransferReuse() throws Exception {
		Fixture fixture=fixture("A=matrix(1,rows=8,cols=4);i=1;"
			+ "while(i<=3){A=abs(A);i=i+1;}B=abs(A);print(sum(B));");
		List<MemoProgramFacts.Occurrence> occurrences=fixture.facts().occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS).toList();
		Assert.assertEquals(2,occurrences.size());
		EvaluationContext unbound=EvaluationContext.empty();
		EvaluationContext bound=new EvaluationContext(Map.of(),Map.of(),new SelectionLookup() {
			@Override public boolean headIsBound(int loopId,int slot) { return true; }
		},4096);
		MemoShapes reference=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,0);
		MemoShapes cached=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,4096);
		for(MemoProgramFacts.Occurrence occurrence:occurrences) {
			Assert.assertEquals(reference.shapeAt(occurrence.id(),unbound),
				cached.shapeAt(occurrence.id(),unbound));
			Assert.assertEquals(reference.shapeAt(occurrence.id(),bound),
				cached.shapeAt(occurrence.id(),bound));
		}
		Assert.assertTrue("head contexts resolving to the same inputs should reuse only the pure transfer",
			cached.reuseStatistics().occurrenceTransferCacheHits()>0);
	}

	@Test public void missingReturnNeverPoisonsANormalReturnTransfer() throws Exception {
		Fixture fixture=fixture("f=function(boolean q,matrix[double] X) return(matrix[double] Y){"
			+ "if(q){Y=X;}}p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+ "A=matrix(1,rows=8,cols=4);R=f(p,A);B=abs(R);print(sum(B));");
		MemoProgramFacts.Occurrence abs=abs(fixture.facts());
		MemoProgramFacts.Guard functionGuard=fixture.facts().guards().stream().filter(guard ->
			fixture.facts().region(guard.parentRegionId()).kind()==MemoProgramFacts.RegionKind.FUNCTION)
			.findFirst().orElseThrow();
		int call=fixture.facts().calls().get(0).id();
		GuardDecision decision=fixture.facts().guardDecision(functionGuard.id(),List.of(call));
		EvaluationContext missing=context(decision,false),normal=context(decision,true);
		MemoShapes reference=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,0);
		MemoShapes.Shape missingReference=reference.shapeAt(abs.id(),missing);
		MemoShapes.Shape normalReference=reference.shapeAt(abs.id(),normal);
		Assert.assertFalse("a missing conditional return has no exact consumer shape",missingReference.exact());
		Assert.assertTrue("the normal return remains exact",normalReference.exact());

		MemoShapes missingFirst=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,4096);
		Assert.assertEquals(missingReference,missingFirst.shapeAt(abs.id(),missing));
		Assert.assertEquals(normalReference,missingFirst.shapeAt(abs.id(),normal));
		MemoShapes normalFirst=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,4096);
		Assert.assertEquals(normalReference,normalFirst.shapeAt(abs.id(),normal));
		Assert.assertEquals(missingReference,normalFirst.shapeAt(abs.id(),missing));
	}

	@Test public void rewrittenWorkloadsPreserveExactRulesCostsAndSelections() throws Exception {
		for(String workload:new String[] {"logreg","glm"}) {
			MemoProgramModel seed=MemoFederatedModelTest.rewrittenWorkloadModel(workload);
			MemoProgramFacts facts=field(seed,"facts",MemoProgramFacts.class);
			MemoSources sources=field(seed,"sources",MemoSources.class);
			MemoShapes referenceShapes=MemoShapes.analyze(facts,sources,4096,4096,0);
			MemoShapes cachedShapes=MemoShapes.analyze(facts,sources,4096,4096,4096);
			MemoRuleBuilder.Result referenceRules=MemoRuleBuilder.build(facts,sources,
				new MemoNativeRules(),referenceShapes);
			MemoRuleBuilder.Result cachedRules=MemoRuleBuilder.build(facts,sources,
				new MemoNativeRules(),cachedShapes);
			Assert.assertEquals(referenceRules.memo().complete(),cachedRules.memo().complete());
			Assert.assertEquals(referenceRules.diagnostics(),cachedRules.diagnostics());
			Assert.assertEquals(referenceRules.inputRoles(),cachedRules.inputRoles());
			Assert.assertEquals(referenceRules.memo().ownerCount(),cachedRules.memo().ownerCount());
			for(int owner=0;owner<referenceRules.memo().ownerCount();owner++) {
				Assert.assertEquals(workload+" owner "+owner,referenceRules.memo().complete(owner),
					cachedRules.memo().complete(owner));
				Assert.assertEquals(workload+" owner "+owner,referenceRules.memo().rules(owner),
					cachedRules.memo().rules(owner));
			}
			MemoProgramModel reference=new MemoProgramModel(facts,sources,referenceRules,
				MemoProgramModel.Profile.defaults());
			MemoProgramModel cached=new MemoProgramModel(facts,sources,cachedRules,
				MemoProgramModel.Profile.defaults());
			MemoOptimizer.Result referenceResult=reference.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
			MemoOptimizer.Result cachedResult=cached.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
			Assert.assertEquals(workload,referenceResult.status(),cachedResult.status());
			Assert.assertEquals(workload,referenceResult.cost(),cachedResult.cost());
			Assert.assertArrayEquals(workload,referenceResult.selectedRules(),cachedResult.selectedRules());
			Assert.assertEquals(workload,reference.evaluateCost(referenceResult.selectedRules()),
				cached.evaluateCost(cachedResult.selectedRules()));
			MemoShapes.ReuseStatistics referenceStats=referenceShapes.reuseStatistics();
			MemoShapes.ReuseStatistics stats=cachedShapes.reuseStatistics();
			System.out.println("MEMO_OCCURRENCE_TRANSFER workload="+workload
				+" reference="+referenceStats+" cached="+stats);
			Assert.assertTrue(workload+" must exercise the transfer cache",stats.occurrenceTransferRequests()>0);
			Assert.assertTrue(workload+" must avoid at least one completed clone",stats.occurrenceTransferCacheHits()>0);
			Assert.assertTrue(workload+" must remove substantive clone/refresh work",
				stats.occurrenceTransferBuilds()*4<referenceStats.occurrenceTransferBuilds());
		}
	}

	private static void assertContextualRows(String script,long trueRows,long falseRows) throws Exception {
		Fixture fixture=fixture(script);
		MemoProgramFacts.Occurrence abs=abs(fixture.facts());
		GuardDecision guard=fixture.facts().guardDecision(fixture.facts().guards().get(0).id(),List.of());
		MemoShapes reference=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,0);
		MemoShapes cached=MemoShapes.analyze(fixture.facts(),fixture.sources(),4096,4096,4096);
		MemoShapes.Shape referenceTrue=reference.shapeAt(abs.id(),context(guard,true));
		MemoShapes.Shape referenceFalse=reference.shapeAt(abs.id(),context(guard,false));
		Assert.assertEquals(trueRows,referenceTrue.rows());
		Assert.assertEquals(falseRows,referenceFalse.rows());
		Assert.assertEquals(referenceTrue,cached.shapeAt(abs.id(),context(guard,true)));
		Assert.assertEquals(referenceFalse,cached.shapeAt(abs.id(),context(guard,false)));
	}

	private static EvaluationContext context(GuardDecision guard,boolean value) {
		return new EvaluationContext(Map.of(guard,value),Map.of(),MemoBindingResolver.NO_SELECTION,4096);
	}

	private static MemoProgramFacts.Occurrence abs(MemoProgramFacts facts) {
		return facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS).findFirst().orElseThrow();
	}

	private static Fixture fixture(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		return new Fixture(facts,MemoSources.acquire(facts,null));
	}

	private static <T> T field(Object owner,String name,Class<T> type) throws Exception {
		Field field=owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return type.cast(field.get(owner));
	}

	private record Fixture(MemoProgramFacts facts,MemoSources sources) { }
}
