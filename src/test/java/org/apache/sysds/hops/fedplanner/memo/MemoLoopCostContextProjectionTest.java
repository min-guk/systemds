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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class MemoLoopCostContextProjectionTest {
	private Path directory;

	@After public void cleanup() throws Exception {
		if(directory!=null) try(var paths=Files.walk(directory)) {
			for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	@Test public void repeatHeadDescendsToStableGuardedOverwriteNnzContexts() throws Exception {
		directory=Files.createTempDirectory("memo-loop-cost");
		DMLProgram program=parse("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"D=read(\""+input("dense",32)+"\");S=read(\""+input("sparse",2)+"\");A=D;"
			+"for(i in 1:3){B=abs(A);print(sum(B));if(p){A=D;}else{A=S;}}");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		freezeReadNnz(facts);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence consumer=abs(facts);
		EvaluationContext middle=phase(IterationPhase.MIDDLE,Map.of());

		MemoShapes.CostGuardSearch split=shapes.firstUnfixedCostGuard(consumer.id(),middle);
		Assert.assertFalse(split.unresolved());
		Assert.assertNotNull("repeat cost must follow the backedge to its stable guard",split.guard());
		long firstNnz=nnz(shapes,consumer,split.guard(),true);
		long secondNnz=nnz(shapes,consumer,split.guard(),false);
		Assert.assertEquals(32,Math.max(firstNnz,secondNnz));
		Assert.assertEquals(2,Math.min(firstNnz,secondNnz));
		for(boolean value:new boolean[] {false,true}) {
			MemoShapes.CostGuardSearch fixed=shapes.firstUnfixedCostGuard(consumer.id(),
				phase(IterationPhase.MIDDLE,Map.of(split.guard(),value)));
			Assert.assertFalse(fixed.unresolved());
			Assert.assertNull(fixed.guard());
		}
	}

	@Test public void loopModelRetainsFiveExactNnzCostPrimitives() throws Exception {
		directory=Files.createTempDirectory("memo-loop-cost-model");
		DMLProgram program=parse("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"D=read(\""+input("dense",32)+"\");S=read(\""+input("sparse",2)+"\");A=D;"
			+"for(i in 1:3){B=abs(A);print(sum(B));if(p){A=D;}else{A=S;}}");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		freezeReadNnz(facts);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramFacts.Occurrence consumer=abs(facts);
		MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		int owner=consumer.compiledOwnerId();
		MemoProgramModel.CostProjectionStatistics statistics=model.costProjectionStatistics(owner);
		Assert.assertEquals("FIRST has D; MIDDLE and LAST retain both guarded NNZ contexts",
			5,statistics.projectedContexts());

		GuardDecision guard=facts.guardDecision(facts.guards().get(0).id(),List.of());
		BigDecimal expected=new BigDecimal(MemoCostModel.nativeCost(
			universe.shapes().viewAt(consumer.id(),phase(IterationPhase.FIRST,Map.of())),
			universe.memo().rule(owner,0),Math.max(1,sources.workers()),1));
		for(IterationPhase iteration:List.of(IterationPhase.MIDDLE,IterationPhase.LAST))
			for(boolean value:new boolean[] {false,true})
				expected=expected.add(new BigDecimal(MemoCostModel.nativeCost(
					universe.shapes().viewAt(consumer.id(),phase(iteration,Map.of(guard,value))),
					universe.memo().rule(owner,0),Math.max(1,sources.workers()),0.5)));
		Assert.assertEquals("the model preserves each original binary64 primitive before exact summation",0,
			expected.compareTo(nativeOwnerCost(model,owner,0)));
	}

	@Test public void retainedBackedgeUsesTheFrozenCanonicalGuardOutcomeAcrossIterations() throws Exception {
		directory=Files.createTempDirectory("memo-loop-cost-dynamic");
		DMLProgram program=parse("D=read(\""+input("dense",32)+"\");S=read(\""
			+input("sparse",2)+"\");A=D;for(i in 1:3){B=abs(A);"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=S;}}print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		freezeReadNnz(facts);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence consumer=abs(facts);
		MemoShapes.CostGuardSearch split=shapes.firstUnfixedCostGuard(consumer.id(),
			phase(IterationPhase.MIDDLE,Map.of()));
		Assert.assertNotNull(split.guard());
		boolean retainingValue=retainsHead(facts,split.guard());
		MemoShapes.CostGuardSearch retained=shapes.firstUnfixedCostGuard(consumer.id(),
			phase(IterationPhase.MIDDLE,Map.of(split.guard(),retainingValue)));
		Assert.assertFalse("the frozen profile reuses one canonical guard outcome in this call frame",
			retained.unresolved());
		Assert.assertNull(retained.guard());
	}

	@Test public void repeatHeadRetainsEachStableGuardedSizeContext() throws Exception {
		DMLProgram program=parse("p=as.scalar(rand(rows=1,cols=1))>0.5;A=matrix(1,rows=8,cols=4);"
			+"for(i in 1:3){B=abs(A);if(p){A=matrix(1,rows=8,cols=4);}"
			+"else{A=matrix(1,rows=16,cols=4);}}print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence consumer=abs(facts);
		MemoShapes.CostGuardSearch split=shapes.firstUnfixedCostGuard(consumer.id(),
			phase(IterationPhase.MIDDLE,Map.of()));
		Assert.assertNotNull(split.guard());
		long first=rows(shapes,consumer,split.guard(),true);
		long second=rows(shapes,consumer,split.guard(),false);
		Assert.assertEquals(16,Math.max(first,second));
		Assert.assertEquals(8,Math.min(first,second));
	}

	@Test public void repeatCycleMayCloseAtDefinitionAfterPhaseProvenOverwrite() throws Exception {
		DMLProgram program=parse("A=matrix(0,1,1);for(i in 1:3){if(i==1){A=matrix(2,4,2);}"
			+"else{A=abs(A);}print(sum(A));}print(sum(A));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence consumer=abs(facts);

		MemoShapes.CostGuardSearch search=shapes.firstUnfixedCostGuard(consumer.id(),
			phase(IterationPhase.MIDDLE,Map.of()));
		Assert.assertFalse("the definition-to-head recurrence has stable 4x2 cost statistics",
			search.unresolved());
		Assert.assertNull(search.guard());
	}

	@Test public void manyIterationLiveOutProjectsItsBackedgeAtLastPhase() throws Exception {
		DMLProgram program=parse("A=matrix(0,2,2);for(i in 1:3){if(i==3){A=matrix(2,4,2);}"
			+"else{A=abs(A);}}print(sum(A));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		MemoProgramFacts.Occurrence sum=facts.occurrences().stream()
			.filter(value -> "ua(+RC)".equals(value.hop().getOpString())).findFirst().orElseThrow();
		EvaluationContext many=new EvaluationContext(Map.of(),Map.of(),MemoBindingResolver.NO_SELECTION,4096,
			List.of(),Map.of(),Map.of(new LoopCardinalityDecision(0,List.of()),LoopCardinality.MANY));

		MemoShapes.CostGuardSearch search=shapes.firstUnfixedCostGuard(sum.id(),many);
		Assert.assertFalse(search.unresolved());
		Assert.assertNull("the i==3 guard is proven true at the MANY live-out",search.guard());
		Assert.assertEquals(4,shapes.viewAt(sum.id(),many).getInput(0).getDim1());
	}

	@Test public void unboundCostDependencyIsAnExplicitUnknownObligation() throws Exception {
		MemoProgramFacts facts=MemoProgramFacts.analyze(parse(
			"for(i in 1:2){A=matrix(1,rows=8,cols=4);}print(sum(A));"));
		MemoShapes shapes=MemoShapes.analyze(facts,MemoSources.acquire(facts,null));
		int unbound=facts.bindings().stream().filter(binding -> binding.kind()==BindingKind.UNBOUND)
			.findFirst().orElseThrow().id();
		Assert.assertTrue(shapes.firstUnfixedCostGuardForBinding(unbound,EvaluationContext.empty()).unresolved());
	}

	private static boolean retainsHead(MemoProgramFacts facts,GuardDecision guard) {
		MemoProgramFacts.Guard syntactic=facts.guard(guard.guardId());
		// The fixture writes only in the then arm, so canonical false selects retention.
		return facts.canonicalGuardValue(syntactic.id(),List.of(),false);
	}

	private static long nnz(MemoShapes shapes,MemoProgramFacts.Occurrence occurrence,
		GuardDecision guard,boolean value) {
		EvaluationContext context=phase(IterationPhase.MIDDLE,Map.of(guard,value));
		return shapes.viewAt(occurrence.id(),context).getInput(0).getNnz();
	}

	private static long rows(MemoShapes shapes,MemoProgramFacts.Occurrence occurrence,
		GuardDecision guard,boolean value) {
		EvaluationContext context=phase(IterationPhase.MIDDLE,Map.of(guard,value));
		return shapes.viewAt(occurrence.id(),context).getInput(0).getDim1();
	}

	@SuppressWarnings("unchecked")
	private static BigDecimal nativeOwnerCost(MemoProgramModel model,int owner,int rule) throws Exception {
		Field nativeCostsField=MemoProgramModel.class.getDeclaredField("nativeCosts");
		nativeCostsField.setAccessible(true);
		BigDecimal result=new BigDecimal(((double[][])nativeCostsField.get(model))[owner][rule]);
		Field countField=MemoProgramModel.class.getDeclaredField("nativeCostTermCount");
		countField.setAccessible(true);
		Field costsField=MemoProgramModel.class.getDeclaredField("costs");
		costsField.setAccessible(true);
		List<MemoOptimizer.CostTerm> terms=(List<MemoOptimizer.CostTerm>)costsField.get(model);
		for(int index=0;index<(int)countField.get(model);index++) {
			MemoOptimizer.CostTerm term=terms.get(index);
			if(term.owners().length==1 && term.owners()[0]==owner)
				result=result.add(term.evaluateExact(new int[] {rule}));
		}
		return result;
	}

	private static EvaluationContext phase(IterationPhase phase,Map<GuardDecision,Boolean> guards) {
		LoopDecision loop=new LoopDecision(0,List.of());
		return new EvaluationContext(guards,Map.of(loop,phase==IterationPhase.FIRST
			? LoopPhase.ENTRY : LoopPhase.BACKEDGE),MemoBindingResolver.NO_SELECTION,4096,List.of(),
			Map.of(loop,phase),Map.of(new LoopCardinalityDecision(0,List.of()),LoopCardinality.MANY));
	}

	private String input(String name,int nnz) throws Exception {
		Path file=directory.resolve(name+".csv");
		Files.writeString(file,nnz==32 ? "1,1,1,1\n".repeat(8)
			: "1,1,0,0\n"+"0,0,0,0\n".repeat(7));
		Files.writeString(Path.of(file+".mtd"),"{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+"\"format\":\"csv\",\"rows\":8,\"cols\":4,\"nnz\":"+nnz+"}");
		return file.toString();
	}

	private static void freezeReadNnz(MemoProgramFacts facts) {
		for(MemoProgramFacts.Occurrence occurrence:facts.occurrences())
			if(occurrence.hop() instanceof DataOp data && data.getOp()==OpOpData.PERSISTENTREAD)
				data.setNnz("D".equals(data.getName()) ? 32 : 2);
	}

	private static MemoProgramFacts.Occurrence abs(MemoProgramFacts facts) {
		return facts.occurrences().stream().filter(value -> value.hop() instanceof UnaryOp unary
			&& unary.getOp()==OpOp1.ABS).findFirst().orElseThrow();
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,
			new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return program;
	}
}
