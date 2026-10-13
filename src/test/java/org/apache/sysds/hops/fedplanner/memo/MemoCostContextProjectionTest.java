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
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Assert;
import org.junit.Test;

public class MemoCostContextProjectionTest {
	@Test public void inactiveNestedGuardDoesNotCreateCostContext() throws Exception {
		Fixture fixture=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=matrix(1,rows=2,cols=2);}else{"
			+"if(q){A=matrix(1,rows=4,cols=2);}else{A=matrix(1,rows=8,cols=2);}}"
			+"B=abs(A);print(sum(B));");
		MemoProgramModel.CostProjectionStatistics statistics=
			fixture.model().costProjectionStatistics(fixture.occurrence().compiledOwnerId());
		Assert.assertEquals("p=true plus the two reachable p=false/q arms",3,statistics.projectedContexts());
		Assert.assertEquals(3,statistics.primitiveVectors());
		Assert.assertTrue("the inactive q arm must be skipped before a context is allocated",
			statistics.noRegionsSkipped()>0);
		List<MemoBindingResolver.GuardDecision> guards=fixture.facts().guards().stream()
			.map(guard -> fixture.facts().guardDecision(guard.id(),List.of())).toList();
		Assert.assertEquals(2,guards.size());
		BigDecimal expected=BigDecimal.ZERO;
		for(Map<MemoBindingResolver.GuardDecision,Boolean> cube:List.of(
			Map.of(guards.get(0),true),
			Map.of(guards.get(0),false,guards.get(1),true),
			Map.of(guards.get(0),false,guards.get(1),false))) {
			MemoBindingResolver.EvaluationContext context=new MemoBindingResolver.EvaluationContext(cube,Map.of(),
				MemoBindingResolver.NO_SELECTION,4096);
			double weight=cube.size()==1 ? 0.5 : 0.25;
			expected=expected.add(new BigDecimal(MemoCostModel.nativeCost(
				fixture.universe().shapes().viewAt(fixture.occurrence().id(),context),
				fixture.universe().memo().rule(fixture.occurrence().compiledOwnerId(),0),1,weight)));
		}
		Assert.assertEquals("projected contexts preserve the exact original binary64 primitive sum",0,
			expected.compareTo(nativeOwnerCost(fixture.model(),fixture.occurrence().compiledOwnerId(),0)));
		Assert.assertEquals(statistics.primitiveValues()*Double.BYTES,statistics.primitiveValueBytes());
	}

	@Test public void sourceOnlyDefinitionScalingDoesNotExpandNativeCostContexts() throws Exception {
		long projectedStops=-1;
		for(int definitions:new int[] {1,10,100,1000}) {
			StringBuilder script=new StringBuilder("A=matrix(1,2,2);");
			for(int index=0;index<definitions;index++)
				script.append("p").append(index).append("=as.scalar(rand(rows=1,cols=1))>0.5;")
					.append("if(p").append(index).append("){A=matrix(2,2,2);}");
			script.append("B=abs(A);print(sum(B));");
			Fixture fixture=fixture(script.toString());
			int input=fixture.facts().inputBinding(fixture.occurrence().id(),0);
			Assert.assertEquals("fixture must retain every pass-through reaching definition",
				definitions+1,new MemoBindingResolver(fixture.facts()).directSupplyOwners(List.of(input),List.of()).size());
			MemoProgramModel.CostProjectionStatistics statistics=
				fixture.model().costProjectionStatistics(fixture.occurrence().compiledOwnerId());
			Assert.assertEquals("source count must not create native cost contexts at scale "+definitions,
				1,statistics.projectedContexts());
			Assert.assertTrue("the source-free DAG projection path must be measured",statistics.invariantProjectionStops()>0);
			if(projectedStops<0) projectedStops=statistics.invariantProjectionStops();
			else Assert.assertEquals("source-only scaling must not add projection leaf visits at scale "+definitions,
				projectedStops,statistics.invariantProjectionStops());
		}
	}

	@Test public void everyNativeVariantRetainsProjectedPrimitivesAndSelectedCostAudit() throws Exception {
		DMLProgram program=parse("P=federated(addresses=list(\"localhost:19101/P1\",\"localhost:19102/P2\"),"
			+"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=P;}else{if(q){A=matrix(1,8,2);}else{A=matrix(1,2,2);}}"
			+"B=abs(A);print(sum(B));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,ignored -> new FederatedSourceMetadata(Privacy.PUBLIC,List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {2,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),"P1")),
			new ImmutablePair<>(new FederatedRange(new long[] {2,0},new long[] {4,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),"P2")))));
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramFacts.Occurrence abs=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();
		int owner=abs.compiledOwnerId();
		Assert.assertTrue("exercise several concrete native choices",universe.memo().rules(owner).size()>1);
		Assert.assertTrue(universe.diagnostics().toString(),universe.memo().complete(owner));
		MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		List<MemoBindingResolver.GuardDecision> guards=facts.guards().stream()
			.map(guard -> facts.guardDecision(guard.id(),List.of())).toList();
		Assert.assertEquals(2,guards.size());
		for(int rule=0;rule<universe.memo().rules(owner).size();rule++) {
			BigDecimal expected=BigDecimal.ZERO;
			for(Map<MemoBindingResolver.GuardDecision,Boolean> cube:List.of(
				Map.of(guards.get(0),true),Map.of(guards.get(0),false,guards.get(1),true),
				Map.of(guards.get(0),false,guards.get(1),false))) {
				var context=new MemoBindingResolver.EvaluationContext(cube,Map.of(),
					MemoBindingResolver.NO_SELECTION,4096);
				double primitive=MemoCostModel.nativeCost(universe.shapes().viewAt(abs.id(),context),
					universe.memo().rule(owner,rule),sources.workers(),cube.size()==1 ? 0.5 : 0.25);
				expected=expected.add(new BigDecimal(primitive));
			}
			Assert.assertEquals("exact original primitive sum for "+universe.memo().rule(owner,rule),0,
				expected.compareTo(nativeOwnerCost(model,owner,rule)));
		}
		var solution=model.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
		Assert.assertEquals(MemoOptimizer.Status.OPTIMAL,solution.status());
		Assert.assertEquals(0,model.evaluateCost(solution.selectedRules()).total().compareTo(solution.cost()));
	}

	@Test public void sharedGuardAcrossTwoInputsCreatesOnlyReachablePairs() throws Exception {
		DMLProgram program=parse("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=matrix(1,rows=2,cols=2);B=matrix(2,rows=2,cols=2);}"
			+"else{A=matrix(3,rows=4,cols=2);B=matrix(4,rows=4,cols=2);}C=A+B;print(sum(C));");
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramFacts.Occurrence plus=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof BinaryOp binary && binary.getOp()==OpOp2.PLUS)
			.findFirst().orElseThrow();
		MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		MemoProgramModel.CostProjectionStatistics statistics=
			model.costProjectionStatistics(plus.compiledOwnerId());
		Assert.assertEquals("the shared guard admits only (small,small) and (large,large)",
			2,statistics.projectedContexts());
		Assert.assertEquals("the canonical shared guard is split once per projection tree",
			1,statistics.relevantGuardDecisions());
	}

	@Test public void twoInputSourceDagsAreProjectedBeforeTheirJointSourceSpace() throws Exception {
		for(int definitions:new int[] {1,10,100,1000}) {
			StringBuilder script=new StringBuilder("A=matrix(1,2,2);B=matrix(2,2,2);");
			for(int index=0;index<definitions;index++) {
				script.append("p").append(index).append("=as.scalar(rand(rows=1,cols=1))>0.5;")
					.append("if(p").append(index).append("){A=matrix(3,2,2);}");
				script.append("q").append(index).append("=as.scalar(rand(rows=1,cols=1))>0.5;")
					.append("if(q").append(index).append("){B=matrix(4,2,2);}");
			}
			script.append("C=A+B;print(sum(C));");
			DMLProgram program=parse(script.toString());
			MemoProgramFacts facts=MemoProgramFacts.analyze(program);
			MemoSources sources=MemoSources.acquire(facts,null);
			MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
			MemoProgramFacts.Occurrence plus=facts.occurrences().stream()
				.filter(value -> value.hop() instanceof BinaryOp binary && binary.getOp()==OpOp2.PLUS)
				.findFirst().orElseThrow();
			MemoBindingResolver resolver=new MemoBindingResolver(facts);
			List<Integer> inputs=List.of(facts.inputBinding(plus.id(),0),facts.inputBinding(plus.id(),1));
			for(int input:inputs)
				Assert.assertEquals(definitions+1,resolver.directSupplyOwners(List.of(input),List.of()).size());
			if(definitions==10) {
				long[] environments={0};
				resolver.visitJoint(inputs,MemoBindingResolver.EvaluationContext.empty(),environment -> {
					environments[0]++; return true;
				});
				Assert.assertEquals("the tiny reference has an independent 11x11 source space",121,environments[0]);
			}
			MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
			MemoProgramModel.CostProjectionStatistics statistics=model.costProjectionStatistics(plus.compiledOwnerId());
			Assert.assertEquals((definitions+1)+"x"+(definitions+1)
				+" independent source owners remain one native cost context",1,statistics.projectedContexts());
			Assert.assertEquals(0,statistics.relevantGuardDecisions());
			System.out.println("MEMO_SOURCE_PROJECTION ownersPerInput="+(definitions+1)
				+" contexts="+statistics.projectedContexts()+" primitives="+statistics.primitiveValues()
				+" bindingVisits="+statistics.bindingDagVisits());
		}
	}

	@Test public void manySourcesInTwoStatisticRegionsCreateTwoCostPrimitives() throws Exception {
		StringBuilder script=new StringBuilder("p=as.scalar(rand(rows=1,cols=1))>0.5;if(p){A=matrix(1,8,2);");
		for(int region=0;region<2;region++) {
			if(region==1) script.append("}else{A=matrix(1,4,2);");
			for(int index=0;index<50;index++)
				script.append("q").append(region).append('_').append(index)
					.append("=as.scalar(rand(rows=1,cols=1))>0.5;if(q")
					.append(region).append('_').append(index).append("){A=matrix(2,")
					.append(region==0 ? 8 : 4).append(",2);}");
		}
		Fixture fixture=fixture(script.append("}B=abs(A);print(sum(B));").toString());
		int binding=fixture.facts().inputBinding(fixture.occurrence().id(),0);
		Assert.assertEquals(102,new MemoBindingResolver(fixture.facts())
			.directSupplyOwners(List.of(binding),List.of()).size());
		var statistics=fixture.model().costProjectionStatistics(fixture.occurrence().compiledOwnerId());
		Assert.assertEquals("only p changes the requested native cost statistics",1,statistics.relevantGuardDecisions());
		Assert.assertEquals(2,statistics.projectedContexts());
		Assert.assertEquals(2,statistics.primitiveValues());
		var p=fixture.facts().guardDecision(fixture.facts().guards().get(0).id(),List.of());
		BigDecimal expected=BigDecimal.ZERO;
		for(boolean branch:List.of(false,true)) {
			var context=new MemoBindingResolver.EvaluationContext(Map.of(p,branch),Map.of(),
				MemoBindingResolver.NO_SELECTION,4096);
			expected=expected.add(new BigDecimal(MemoCostModel.nativeCost(
				fixture.universe().shapes().viewAt(fixture.occurrence().id(),context),
				fixture.universe().memo().rule(fixture.occurrence().compiledOwnerId(),0),1,0.5)));
		}
		Assert.assertEquals(0,expected.compareTo(nativeOwnerCost(fixture.model(),fixture.occurrence().compiledOwnerId(),0)));
	}

	private record Fixture(MemoProgramFacts facts,MemoRuleBuilder.Result universe,
		MemoProgramModel model,MemoProgramFacts.Occurrence occurrence) { }
	private static Fixture fixture(String script) throws Exception {
		DMLProgram program=parse(script);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramFacts.Occurrence occurrence=facts.occurrences().stream()
			.filter(value -> value.hop() instanceof UnaryOp unary && unary.getOp()==OpOp1.ABS)
			.findFirst().orElseThrow();
		return new Fixture(facts,universe,
			new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults()),occurrence);
	}
	@SuppressWarnings("unchecked")
	private static BigDecimal nativeOwnerCost(MemoProgramModel model,int owner,int rule) throws Exception {
		Field nativeCostsField=MemoProgramModel.class.getDeclaredField("nativeCosts");
		nativeCostsField.setAccessible(true);
		BigDecimal result=new BigDecimal(((double[][])nativeCostsField.get(model))[owner][rule]);
		Field countField=MemoProgramModel.class.getDeclaredField("nativeCostTermCount"); countField.setAccessible(true);
		Field costsField=MemoProgramModel.class.getDeclaredField("costs"); costsField.setAccessible(true);
		List<MemoOptimizer.CostTerm> terms=(List<MemoOptimizer.CostTerm>)costsField.get(model);
		for(int index=0;index<(int)countField.get(model);index++) {
			MemoOptimizer.CostTerm term=terms.get(index);
			if(term.owners().length==1 && term.owners()[0]==owner)
				result=result.add(term.evaluateExact(new int[] {rule}));
		}
		return result;
	}
	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,
			new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return program;
	}
}
