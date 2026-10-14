/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoBindingResolverMapSnapshotTest {
	@Test public void repeatedDirectAndGeneralVisitsReuseExactTransform() throws Exception {
		MemoProgramFacts facts=guardFacts();
		Map<GuardDecision,Boolean> insertion=collidingGuards();
		EvaluationContext context=new EvaluationContext(insertion,Map.of(),MemoBindingResolver.NO_SELECTION,64);
		Map<GuardDecision,Boolean> expected=transform(context.guards());
		int direct=directRoot(facts),general=generalRoot(facts);
		MemoBindingResolver cached=new MemoBindingResolver(facts,4096);
		List<ResolvedEnvironment> environments=new ArrayList<>();
		cached.visitJoint(List.of(direct),context,env -> { environments.add(env); return true; });
		cached.visitJoint(List.of(direct),context,env -> { environments.add(env); return true; });
		cached.visitJoint(List.of(general),context,env -> { environments.add(env); return true; });
		Assert.assertEquals(3,environments.size());
		for(ResolvedEnvironment environment:environments) assertExactMap(expected,environment.guards());
		Assert.assertSame("cached MapN must survive ResolvedEnvironment.Map.copyOf",
			environments.get(0).guards(),environments.get(1).guards());
		Assert.assertTrue(cached.mapSnapshotStatistics().hits()>0);

		MemoBindingResolver reference=new MemoBindingResolver(facts,0);
		List<ResolvedEnvironment> uncached=new ArrayList<>();
		reference.visitJoint(List.of(direct),context,env -> { uncached.add(env); return true; });
		reference.visitJoint(List.of(general),context,env -> { uncached.add(env); return true; });
		for(ResolvedEnvironment environment:uncached) assertExactMap(expected,environment.guards());
		Assert.assertEquals(0,reference.mapSnapshotStatistics().entries());
		Assert.assertEquals(0,reference.mapSnapshotStatistics().hits());
	}

	@Test public void transformedMapAsInputGetsItsOwnSecondTransform() throws Exception {
		MemoProgramFacts facts=guardFacts(); int root=directRoot(facts);
		MemoBindingResolver resolver=new MemoBindingResolver(facts,4096);
		EvaluationContext original=new EvaluationContext(collidingGuards(),Map.of(),
			MemoBindingResolver.NO_SELECTION,64);
		ResolvedEnvironment first=one(resolver,root,original);
		Map<GuardDecision,Boolean> expectedSecond=transform(first.guards());
		EvaluationContext transformed=new EvaluationContext(first.guards(),Map.of(),
			MemoBindingResolver.NO_SELECTION,64);
		ResolvedEnvironment second=one(resolver,root,transformed);
		ResolvedEnvironment repeated=one(resolver,root,transformed);
		assertExactMap(expectedSecond,second.guards());
		Assert.assertSame(second.guards(),repeated.guards());
		Assert.assertTrue(resolver.mapSnapshotStatistics().entries()>=2);
	}

	@Test public void equalDistinctInputsAndCapacityNeverMergeByEquality() throws Exception {
		MemoProgramFacts facts=guardFacts(); int root=directRoot(facts);
		EvaluationContext first=new EvaluationContext(new LinkedHashMap<>(collidingGuards()),Map.of(),
			MemoBindingResolver.NO_SELECTION,64);
		EvaluationContext second=new EvaluationContext(new LinkedHashMap<>(collidingGuards()),Map.of(),
			MemoBindingResolver.NO_SELECTION,64);
		Assert.assertNotSame(first.guards(),second.guards());
		MemoBindingResolver resolver=new MemoBindingResolver(facts,2);
		ResolvedEnvironment firstResult=one(resolver,root,first);
		ResolvedEnvironment secondResult=one(resolver,root,second);
		one(resolver,root,second);
		assertExactMap(transform(first.guards()),firstResult.guards());
		assertExactMap(transform(second.guards()),secondResult.guards());
		Assert.assertEquals(2,resolver.mapSnapshotStatistics().entries());
		Assert.assertEquals(1,resolver.mapSnapshotStatistics().hits());
	}

	@Test public void fullCacheClearsAndRetainsTheNewIdentity() throws Exception {
		MemoProgramFacts facts=guardFacts(); int root=directRoot(facts);
		MemoBindingResolver resolver=new MemoBindingResolver(facts,2);
		EvaluationContext first=new EvaluationContext(new LinkedHashMap<>(collidingGuards()),Map.of(),
			MemoBindingResolver.NO_SELECTION,64);
		EvaluationContext second=new EvaluationContext(new LinkedHashMap<>(collidingGuards()),Map.of(),
			MemoBindingResolver.NO_SELECTION,64);
		EvaluationContext third=new EvaluationContext(new LinkedHashMap<>(collidingGuards()),Map.of(),
			MemoBindingResolver.NO_SELECTION,64);
		one(resolver,root,first); one(resolver,root,second); one(resolver,root,third);
		Assert.assertEquals(1,resolver.mapSnapshotStatistics().entries());
		Assert.assertEquals(1,resolver.mapSnapshotStatistics().clears());
		long hits=resolver.mapSnapshotStatistics().hits();
		one(resolver,root,third);
		Assert.assertEquals(hits+1,resolver.mapSnapshotStatistics().hits());
		one(resolver,root,first);
		Assert.assertEquals("the cleared first identity must miss",hits+1,
			resolver.mapSnapshotStatistics().hits());
	}

	@Test public void branchOwnedGuardMapsAreNeverReusedAcrossValues() throws Exception {
		MemoProgramFacts facts=analyze("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=matrix(1,2,2);}else{A=matrix(2,2,2);}print(sum(A));");
		int select=facts.bindings().stream().filter(binding -> binding.kind()==BindingKind.SELECT)
			.map(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
		MemoBindingResolver resolver=new MemoBindingResolver(facts,4096);
		List<ResolvedEnvironment> environments=new ArrayList<>();
		resolver.visitJoint(List.of(select),EvaluationContext.empty(),env -> { environments.add(env); return true; });
		Assert.assertEquals(2,environments.size());
		GuardDecision decision=facts.guardDecision(facts.guards().get(0).id(),List.of());
		Assert.assertEquals(Boolean.FALSE,environments.get(0).guards().get(decision));
		Assert.assertEquals(Boolean.TRUE,environments.get(1).guards().get(decision));
		Assert.assertNotSame(environments.get(0).guards(),environments.get(1).guards());
		Assert.assertEquals("owned branch maps and empty inherited maps bypass the cache",
			0,resolver.mapSnapshotStatistics().requests());
	}

	private static ResolvedEnvironment one(MemoBindingResolver resolver,int root,EvaluationContext context) {
		List<ResolvedEnvironment> result=new ArrayList<>();
		resolver.visitJoint(List.of(root),context,env -> { result.add(env); return true; });
		Assert.assertEquals(1,result.size()); return result.get(0);
	}
	private static void assertExactMap(Map<GuardDecision,Boolean> expected,Map<GuardDecision,Boolean> actual) {
		Assert.assertEquals(new ArrayList<>(expected.entrySet()),new ArrayList<>(actual.entrySet()));
		Assert.assertEquals(Double.doubleToRawLongBits(probability(expected)),
			Double.doubleToRawLongBits(probability(actual)));
	}
	private static double probability(Map<GuardDecision,Boolean> guards) {
		double value=1; for(boolean branch:guards.values()) value*=branch ? .37 : .63; return value;
	}
	private static <K,V> Map<K,V> transform(Map<K,V> values) {
		return Map.copyOf(Collections.unmodifiableMap(values));
	}
	private static int directRoot(MemoProgramFacts facts) {
		return facts.bindings().stream().filter(binding -> binding.kind()==BindingKind.DEFINITION
			&& binding.children().isEmpty() && !binding.label().endsWith("#for-iterator")
			&& binding.referenceId()>=0 && binding.referenceId()<facts.occurrences().size())
			.map(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
	}
	private static int generalRoot(MemoProgramFacts facts) {
		return facts.bindings().stream().filter(binding -> !binding.children().isEmpty()
			&& binding.kind()!=BindingKind.SELECT).map(MemoProgramFacts.BindingNode::id).findFirst().orElseThrow();
	}
	private static Map<GuardDecision,Boolean> collidingGuards() {
		Map<GuardDecision,Boolean> result=new LinkedHashMap<>();
		for(int i=0;i<6;i++) {
			GuardDecision key=new GuardDecision(i,List.of(1008-31*i));
			Assert.assertEquals(2000,key.hashCode()); result.put(key,i<4);
		}
		return result;
	}
	private static MemoProgramFacts guardFacts() throws Exception {
		StringBuilder script=new StringBuilder("A=matrix(1,2,2);");
		for(int i=0;i<6;i++) script.append("p").append(i)
			.append("=as.scalar(rand(rows=1,cols=1));if(p").append(i).append(">0.5){print(")
			.append(i).append(");}");
		script.append("B=abs(A);print(sum(B));"); return analyze(script.toString());
	}
	private static MemoProgramFacts analyze(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return MemoProgramFacts.analyze(program);
	}
}
