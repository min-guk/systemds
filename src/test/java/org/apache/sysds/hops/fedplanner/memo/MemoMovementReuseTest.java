/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Assert;
import org.junit.Test;

public class MemoMovementReuseTest {
	@Test public void cachedMovementIsExactForEveryScopedSelection() throws Exception {
		Fixture fixture=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=P;}else{A=matrix(2,rows=4,cols=2);}B=abs(A);C=B+1;print(sum(C));");
		MemoProgramModel cached=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),true);
		MemoProgramModel reference=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),false);
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(cached)).clone();
		Field predicateField=field(MemoOptimizer.Check.class,"predicate");
		List<MemoOptimizer.Check> cachedChecks=cached.checks(),referenceChecks=reference.checks();
		Assert.assertEquals(referenceChecks.size(),cachedChecks.size());
		long combinations=1;
		for(int size:sizes) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("fixture must remain exhaustive",combinations<=100_000);
		int[] selected=new int[sizes.length];
		for(long value=0;value<combinations;value++) {
			for(int check=0;check<cachedChecks.size();check++) {
				int[] scope=cachedChecks.get(check).owners();
				int[] tuple=new int[scope.length];
				for(int i=0;i<scope.length;i++) tuple[i]=selected[scope[i]];
				MemoOptimizer.Predicate cachedPredicate=(MemoOptimizer.Predicate)predicateField.get(cachedChecks.get(check));
				MemoOptimizer.Predicate referencePredicate=(MemoOptimizer.Predicate)predicateField.get(referenceChecks.get(check));
				Assert.assertEquals("legality at selection "+java.util.Arrays.toString(selected),
					referencePredicate.test(tuple),cachedPredicate.test(tuple));
			}
			Assert.assertEquals("exact dyadic cost at selection "+java.util.Arrays.toString(selected),
				0,reference.evaluateCost(selected).total().compareTo(cached.evaluateCost(selected).total()));
			for(int i=selected.length-1;i>=0;i--) {
				if(++selected[i]<sizes[i]) break;
				selected[i]=0;
			}
		}
		var before=cached.movementReuseStatistics();
		selected=new int[sizes.length];
		cached.evaluateCost(selected);
		var after=cached.movementReuseStatistics();
		Assert.assertTrue("a repeated exact movement projection must hit",after.postGateHits()>before.postGateHits());
		Assert.assertEquals("cached primitives must avoid rebuilding contextual HOP views",
			before.contextualViewBuilds(),after.contextualViewBuilds());
	}

	@Test public void directAndImpossibleLayoutsStayBehindThePostGateCache() throws Exception {
		Fixture fixture=localFixture("A=matrix(1,rows=4,cols=2);B=abs(A);print(sum(B));");
		MemoProgramModel model=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),true);
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(model)).clone();
		int[] selected=new int[sizes.length];
		for(int owner=0;owner<sizes.length;owner++) selected[owner]=0;
		model.evaluateCost(selected);
		var first=model.movementReuseStatistics();
		Assert.assertTrue("the fixture must contain projected movement edges",first.activationRows()>0);
		Assert.assertEquals("DIRECT edges must not build cost primitives",0,first.postGateRequests());
		Assert.assertEquals(0,first.postGateBuilds());
		Assert.assertEquals(0,first.contextualViewBuilds());
		model.evaluateCost(selected);
		var second=model.movementReuseStatistics();
		Assert.assertTrue(second.activationReplays()>first.activationReplays());
		Assert.assertEquals(0,second.postGateRequests());
		Assert.assertEquals(0,second.postGateBuilds());
		Assert.assertEquals(0,second.contextualViewBuilds());
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field result=owner.getDeclaredField(name); result.setAccessible(true); return result;
	}

	private static Fixture fixture(String body) throws Exception {
		String script="P=federated(addresses=list(\"localhost:19101/P1\",\"localhost:19102/P2\"),"
			+"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"+body;
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,ignored -> new FederatedSourceMetadata(Privacy.PUBLIC,List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {2,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),"P1")),
			new ImmutablePair<>(new FederatedRange(new long[] {2,0},new long[] {4,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),"P2")))));
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		return new Fixture(facts,sources,universe);
	}
	private static Fixture localFixture(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		return new Fixture(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()));
	}

	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe) { }
}
