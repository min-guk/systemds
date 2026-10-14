/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.DirectRootStatistics;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.SelectionLookup;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.VisitStatus;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoBindingResolverDirectNativeRootTest {
	@Test public void directRootsPreserveDuplicatesLookupOrderDepthAndStopResult() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(1,2,2);B=matrix(2,2,2);print(sum(A+B));");
		List<Integer> available=directRoots(facts);
		Assert.assertTrue(available.size()>=2);
		List<Integer> roots=List.of(available.get(0),available.get(1),available.get(0));
		List<String> calls=new ArrayList<>();
		SelectionLookup selection=new SelectionLookup() {
			@Override public int selectedRule(int owner) { calls.add("rule:"+owner); return owner+10; }
			@Override public MemoPartition selectedLayout(int owner) { calls.add("layout:"+owner); return MemoPartition.ROW; }
		};
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		List<MemoBindingResolver.ResolvedEnvironment> environments=new ArrayList<>();
		var result=resolver.visitJoint(roots,new EvaluationContext(Map.of(),Map.of(),selection,1,List.of(7)),env -> {
			environments.add(env); return false;
		});
		Assert.assertEquals(VisitStatus.STOPPED,result.status());
		Assert.assertEquals(1,result.environmentCount());
		Assert.assertEquals("complete",result.detail());
		Assert.assertEquals(1,environments.size());
		Assert.assertEquals(3,environments.get(0).origins().size());
		Assert.assertEquals(environments.get(0).origins().get(0),environments.get(0).origins().get(2));
		Assert.assertEquals(List.of(7),environments.get(0).origins().get(0).callFrame());
		List<String> expected=new ArrayList<>();
		for(int root:roots) {
			int owner=facts.occurrence(facts.binding(root).referenceId()).compiledOwnerId();
			expected.add("rule:"+owner); expected.add("layout:"+owner);
		}
		Assert.assertEquals(expected,calls);
		Assert.assertEquals(new DirectRootStatistics(1,1,1,3),resolver.directRootStatistics());
	}

	@Test public void lateSelectionFailureHappensBeforeVisitorAndDoesNotPublishFastCall() throws Exception {
		MemoProgramFacts facts=analyze("A=matrix(1,2,2);B=matrix(2,2,2);print(sum(A+B));");
		List<Integer> roots=directRoots(facts).subList(0,2);
		AtomicInteger lookups=new AtomicInteger(),visits=new AtomicInteger();
		SelectionLookup selection=new SelectionLookup() {
			@Override public int selectedRule(int owner) { lookups.incrementAndGet(); return 1; }
			@Override public MemoPartition selectedLayout(int owner) {
				if(lookups.get()==2) throw new IllegalStateException("late lookup");
				return MemoPartition.COL;
			}
		};
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		try {
			resolver.visitJoint(roots,new EvaluationContext(Map.of(),Map.of(),selection,64),env -> {
				visits.incrementAndGet(); return true;
			});
			Assert.fail("expected lookup failure");
		}
		catch(IllegalStateException expected) { Assert.assertEquals("late lookup",expected.getMessage()); }
		Assert.assertEquals(2,lookups.get());
		Assert.assertEquals(0,visits.get());
		Assert.assertEquals(new DirectRootStatistics(1,1,0,0),resolver.directRootStatistics());
	}

	@Test public void contradictionPreflightRunsBeforeFastPathAndSelectionSideEffects() throws Exception {
		MemoProgramFacts facts=analyze("B=matrix(1,2,2);for(i in 1:2){X=i;}print(sum(B));");
		int root=directRoots(facts).get(0);
		int loop=facts.loop(0).id();
		LoopCardinality impossible=List.of(LoopCardinality.ZERO,LoopCardinality.ONE,LoopCardinality.MANY).stream()
			.filter(value -> facts.loopExecutionCases(loop).stream().noneMatch(c -> c.cardinality()==value))
			.findFirst().orElseThrow();
		AtomicInteger sideEffects=new AtomicInteger();
		SelectionLookup selection=new SelectionLookup() {
			@Override public int selectedRule(int owner) { sideEffects.incrementAndGet(); return -1; }
		};
		EvaluationContext context=new EvaluationContext(Map.of(),Map.of(),selection,64)
			.withLoopCardinality(loop,impossible);
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		var result=resolver.visitJoint(List.of(root),context,env -> { sideEffects.incrementAndGet(); return true; });
		Assert.assertEquals(VisitStatus.COMPLETE,result.status());
		Assert.assertEquals(0,result.environmentCount());
		Assert.assertEquals(0,sideEffects.get());
		Assert.assertEquals(new DirectRootStatistics(0,0,0,0),resolver.directRootStatistics());
	}

	@Test public void symbolicReturnConcreteWitnessesNeverUseDirectFastPath() throws Exception {
		MemoProgramFacts uniqueFacts=analyze(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"+
			"P=matrix(1,2,2);A=f(P);print(sum(A));");
		int uniqueReturn=uniqueFacts.call(0).returnBindingIds().get(0);
		Assert.assertEquals(2,uniqueFacts.binding(uniqueReturn).children().size());
		int uniqueConcrete=uniqueFacts.binding(uniqueReturn).children().get(1);
		MemoBindingResolver uniqueResolver=new MemoBindingResolver(uniqueFacts);
		uniqueResolver.visitJoint(List.of(uniqueConcrete),EvaluationContext.empty(),env -> true);
		Assert.assertEquals(0,uniqueResolver.directRootStatistics().fastPathCalls());

		MemoProgramFacts multipleFacts=analyze(
			"f=function(matrix[double] X) return(matrix[double] Y){Y=abs(X);}"+
			"P=matrix(1,2,2);Q=matrix(2,2,2);A=f(P);B=f(Q);print(sum(A+B));");
		MemoBindingResolver multipleResolver=new MemoBindingResolver(multipleFacts);
		for(var call:multipleFacts.calls()) {
			int returned=call.returnBindingIds().get(0);
			Assert.assertEquals(2,multipleFacts.binding(returned).children().size());
			int concrete=multipleFacts.binding(returned).children().get(1);
			multipleResolver.visitJoint(List.of(concrete),EvaluationContext.empty(),env -> true);
		}
		Assert.assertEquals(0,multipleResolver.directRootStatistics().fastPathCalls());
	}

	@Test public void mixedOrEmptyRootsStayOnLegacyPath() throws Exception {
		MemoProgramFacts facts=analyze("B=matrix(1,2,2);for(i in 1:2){X=i;}print(sum(B));");
		int direct=directRoots(facts).get(0);
		int indirect=facts.bindings().stream().filter(binding -> !binding.children().isEmpty())
			.mapToInt(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		resolver.visitJoint(List.of(direct,indirect),EvaluationContext.empty(),env -> true);
		resolver.visitJoint(List.of(),EvaluationContext.empty(),env -> true);
		Assert.assertEquals(0,resolver.directRootStatistics().fastPathCalls());
	}

	@Test public void fastPathRebuildsCollidingDecisionMapsInBaselineOrder() throws Exception {
		StringBuilder script=new StringBuilder("A=matrix(1,2,2);");
		for(int i=0;i<6;i++) script.append("p").append(i)
			.append("=as.scalar(rand(rows=1,cols=1));if(p").append(i).append(">0.5){print(")
			.append(i).append(");}");
		script.append("print(sum(A));");
		MemoProgramFacts facts=analyze(script.toString());
		Map<GuardDecision,Boolean> insertion=new LinkedHashMap<>();
		for(int i=0;i<6;i++) {
			GuardDecision key=new GuardDecision(i,List.of(1008-31*i));
			Assert.assertEquals(2000,key.hashCode()); insertion.put(key,i<4);
		}
		EvaluationContext context=new EvaluationContext(insertion,Map.of(),MemoBindingResolver.NO_SELECTION,64);
		Map<GuardDecision,Boolean> expected=Map.copyOf(new LinkedHashMap<>(context.guards()));
		List<Map<GuardDecision,Boolean>> actual=new ArrayList<>();
		MemoBindingResolver resolver=new MemoBindingResolver(facts);
		resolver.visitJoint(List.of(directRoots(facts).get(0)),context,env -> { actual.add(env.guards()); return true; });
		Assert.assertEquals(1,resolver.directRootStatistics().fastPathCalls());
		Assert.assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(actual.get(0).keySet()));
		Assert.assertEquals(Double.doubleToRawLongBits(probability(expected)),
			Double.doubleToRawLongBits(probability(actual.get(0))));
	}

	private static double probability(Map<GuardDecision,Boolean> guards) {
		double value=1;
		for(boolean branch:guards.values()) value*=branch ? .37 : .63;
		return value;
	}

	private static List<Integer> directRoots(MemoProgramFacts facts) {
		return facts.bindings().stream().filter(binding -> binding.kind()==BindingKind.DEFINITION
			&& binding.children().isEmpty() && !binding.label().endsWith("#for-iterator")
			&& binding.referenceId()>=0 && binding.referenceId()<facts.occurrences().size())
			.map(MemoProgramFacts.BindingNode::id).toList();
	}

	private static MemoProgramFacts analyze(String script) throws Exception {
		return MemoProgramFacts.analyze(parsedProgram(script));
	}

	private static DMLProgram parsedProgram(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}
