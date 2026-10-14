/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
	@Test public void conditionalSecondaryReturnKeepsExactScopedLegalityAndCost() throws Exception {
		assertExhaustiveParity("f=function(boolean q,matrix[double] X) "
			+ "return(matrix[double] Y,matrix[double] Z){Y=abs(X);if(q){Z=X+1;}}"
			+ "p=as.scalar(rand(rows=1,cols=1))>0.5;[A,B]=f(p,P);C=abs(A);print(sum(C));",false);
	}

	@Test public void loopHeadAndMultiOutputKeepExactScopedLegalityAndCost() throws Exception {
		assertExhaustiveParity("X=matrix(1,rows=4,cols=2);[Q,R]=qr(X);"
			+ "A=P;for(i in 1:2){A=abs(A);}C=abs(R);print(sum(A)+sum(Q)+sum(C));",true);
	}

	@Test public void cachedMovementIsExactForEveryScopedSelection() throws Exception {
		Fixture fixture=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=P;}else{A=matrix(2,rows=4,cols=2);}B=abs(A);C=B+1;print(sum(C));");
		MemoProgramModel cached=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),true);
		MemoProgramModel reference=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),false);
		assertMovementScopeNarrowed(cached,reference);
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(cached)).clone();
		Field predicateField=field(MemoOptimizer.Check.class,"predicate");
		List<MemoOptimizer.Check> cachedChecks=cached.checks(),referenceChecks=reference.checks();

		long combinations=1;
		for(int size:sizes) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("fixture must remain exhaustive",combinations<=100_000);
		int[] selected=new int[sizes.length];
		for(long value=0;value<combinations;value++) {
			Assert.assertEquals("universal input legality at selection "+java.util.Arrays.toString(selected),
				conjunction(referenceChecks,predicateField,selected),conjunction(cachedChecks,predicateField,selected));
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

	private static void assertExhaustiveParity(String body,boolean requireNarrowedMovement) throws Exception {
		Fixture fixture=fixture(body);
		MemoProgramModel cached=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),true);
		MemoProgramModel reference=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),false);
		if(requireNarrowedMovement) assertMovementScopeNarrowed(cached,reference);
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(cached)).clone();
		Field predicateField=field(MemoOptimizer.Check.class,"predicate");
		List<MemoOptimizer.Check> cachedChecks=cached.checks(),referenceChecks=reference.checks();
		long combinations=1;
		for(int size:sizes) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("fixture must remain exhaustive: "+combinations,combinations<=100_000);
		int[] partial=new int[sizes.length]; Arrays.fill(partial,-1);
		Assert.assertEquals(MemoOptimizer.Truth.UNKNOWN,conjunction(referenceChecks,predicateField,partial));
		Assert.assertEquals("splitting rows must not promote an unresolved selection",
			MemoOptimizer.Truth.UNKNOWN,conjunction(cachedChecks,predicateField,partial));
		BigDecimal referenceBest=null,cachedBest=null;
		int[] referenceSelected=null,cachedSelected=null,selected=new int[sizes.length];
		for(long value=0;value<combinations;value++) {
			MemoOptimizer.Truth referenceTruth=conjunction(referenceChecks,predicateField,selected);
			MemoOptimizer.Truth cachedTruth=conjunction(cachedChecks,predicateField,selected);
			Assert.assertEquals("legality at selection "+Arrays.toString(selected),referenceTruth,cachedTruth);
			BigDecimal referenceCost=reference.evaluateCost(selected).total();
			BigDecimal cachedCost=cached.evaluateCost(selected).total();
			Assert.assertEquals("exact cost at selection "+Arrays.toString(selected),referenceCost,cachedCost);
			if(referenceTruth==MemoOptimizer.Truth.YES
				&& (referenceBest==null || referenceCost.compareTo(referenceBest)<0)) {
				referenceBest=referenceCost; referenceSelected=selected.clone();
			}
			if(cachedTruth==MemoOptimizer.Truth.YES && (cachedBest==null || cachedCost.compareTo(cachedBest)<0)) {
				cachedBest=cachedCost; cachedSelected=selected.clone();
			}
			increment(selected,sizes);
		}
		Assert.assertEquals(referenceBest,cachedBest);
		Assert.assertArrayEquals(referenceSelected,cachedSelected);
	}

	@SuppressWarnings("unchecked")
	private static void assertMovementScopeNarrowed(MemoProgramModel cached,MemoProgramModel reference)
		throws Exception {
		List<MemoOptimizer.CostTerm> cachedCosts=(List<MemoOptimizer.CostTerm>)
			field(MemoProgramModel.class,"costs").get(cached);
		List<MemoOptimizer.CostTerm> referenceCosts=(List<MemoOptimizer.CostTerm>)
			field(MemoProgramModel.class,"costs").get(reference);
		Assert.assertEquals(referenceCosts.size(),cachedCosts.size());
		boolean narrowed=false;
		for(int index=0;index<cachedCosts.size();index++) {
			Set<Integer> cachedScope=scope(cachedCosts.get(index).owners());
			Set<Integer> referenceScope=scope(referenceCosts.get(index).owners());
			Assert.assertTrue("cached factor introduced an owner at term "+index,
				referenceScope.containsAll(cachedScope));
			narrowed|=cachedScope.size()<referenceScope.size();
		}
		Assert.assertTrue("fixture must exercise exact retained movement-scope narrowing",narrowed);
	}

	private static Set<Integer> scope(int[] owners) {
		Set<Integer> result=new HashSet<>();
		for(int owner:owners) result.add(owner);
		return result;
	}

	private static void increment(int[] selected,int[] sizes) {
		for(int i=selected.length-1;i>=0;i--) {
			if(++selected[i]<sizes[i]) return;
			selected[i]=0;
		}
	}

	private static MemoOptimizer.Truth conjunction(List<MemoOptimizer.Check> checks,Field predicateField,
		int[] selected) throws Exception {
		MemoOptimizer.Truth result=MemoOptimizer.Truth.YES;
		for(MemoOptimizer.Check check:checks) {
			int[] scope=check.owners(),tuple=new int[scope.length];
			for(int i=0;i<scope.length;i++) tuple[i]=selected[scope[i]];
			MemoOptimizer.Truth value=((MemoOptimizer.Predicate)predicateField.get(check)).test(tuple);
			if(value==MemoOptimizer.Truth.NO) return value;
			if(value==MemoOptimizer.Truth.UNKNOWN) result=value;
		}
		return result;
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
