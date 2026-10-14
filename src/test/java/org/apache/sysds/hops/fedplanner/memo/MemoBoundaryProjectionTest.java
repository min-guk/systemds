/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.junit.Assert;
import org.junit.Test;

/** Locks the broad loop-boundary semantics before boundary checks are projected into smaller rows. */
public class MemoBoundaryProjectionTest {
	@Test public void entryAndBackedgeTruthAndCostAreExactForEveryBoundarySelection() throws Exception {
		assertCurrentBoundaryParity("A=P;for(i in 1:2){A=abs(A);}print(sum(A));",
			Map.of("P",new SourceSpec(4,2,Privacy.PUBLIC)),true,true,true);
	}

	@Test public void nestedLoopHeadSourceKeepsTheOuterHeadContract() throws Exception {
		assertCurrentBoundaryParity("A=P;for(i in 1:2){B=A;for(j in 1:2){B=abs(B);}A=B;}print(sum(A));",
			Map.of("P",new SourceSpec(4,2,Privacy.PUBLIC)),true,true,true);
	}

	@Test public void sharedFunctionCallsKeepTheirDistinctBoundaryFrames() throws Exception {
		assertCurrentBoundaryParity("f=function(matrix[double] X) return(matrix[double] Y){A=X;"
			+"for(i in 1:2){A=abs(A);}Y=A;}R=f(P);S=f(Q);print(sum(R)+sum(S));",
			Map.of("P",new SourceSpec(4,2,Privacy.PUBLIC),"Q",new SourceSpec(4,2,Privacy.PUBLIC)),true,true,true);
	}

	@Test public void sharedFunctionShapeMismatchKeepsTheBroadBoundaryTruth() throws Exception {
		assertCurrentBoundaryParity("f=function(matrix[double] X) return(matrix[double] Y){A=X;"
			+"for(i in 1:2){A=A;}Y=A;}R=f(P);S=f(Q);print(\"done\");",
			Map.of("P",new SourceSpec(4,2,Privacy.PUBLIC),"Q",new SourceSpec(6,2,Privacy.PUBLIC)),false,false,false);
	}

	@Test public void conditionalEntryHasMultipleSourcesAndADirectBackedge() throws Exception {
		Fixture fixture=fixture("p=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"if(p){A=P;}else{A=Q;}for(i in 1:2){A=abs(A);}print(sum(A));",
			Map.of("P",new SourceSpec(4,2,Privacy.PUBLIC),"Q",new SourceSpec(4,2,Privacy.PUBLIC)));
		MemoProgramModel broad=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),false);
		Assert.assertTrue("fixture must expose the current multi-owner boundary scope",
			boundaryChecks(broad).stream().anyMatch(check -> check.owners().length>2));
		assertCurrentBoundaryParity(fixture,true,true,true);
	}

	@Test public void guardedPrivateAggregateBoundaryNeverPromotesAnUnknownSelection() throws Exception {
		assertCurrentBoundaryParity("f=function(boolean p,boolean q,matrix[double] X) "
			+"return(matrix[double] Y){if(p){if(q){Y=X;}else{Y=matrix(2,rows=4,cols=2);}}else{Y=X;}}"
			+"p=as.scalar(rand(rows=1,cols=1))>0.5;q=as.scalar(rand(rows=1,cols=1))>0.5;"
			+"A=f(p,q,P);for(i in 1:2){A=abs(A);}print(sum(A));",
			Map.of("P",new SourceSpec(4,2,Privacy.PRIVATE_AGGREGATE_TO_PUBLIC)),false,true,true);
	}

	private static void assertCurrentBoundaryParity(String body,Map<String,SourceSpec> sourceSpecs,
		boolean requireBothCompleteTruths,boolean requireLegal,boolean expectProjection) throws Exception {
		assertCurrentBoundaryParity(fixture(body,sourceSpecs),requireBothCompleteTruths,requireLegal,expectProjection);
	}

	private static void assertCurrentBoundaryParity(Fixture fixture,boolean requireBothCompleteTruths,
		boolean requireLegal,boolean expectProjection) throws Exception {
		MemoProgramModel reference=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),false);
		MemoProgramModel cached=new MemoProgramModel(fixture.facts,fixture.sources,fixture.universe,
			MemoProgramModel.Profile.defaults(),true);
		List<MemoOptimizer.Check> referenceChecks=boundaryChecks(reference);
		List<MemoOptimizer.Check> cachedChecks=boundaryChecks(cached);
		Assert.assertFalse("fixture must contain a loop boundary",referenceChecks.isEmpty());
		assertBoundaryStructure(referenceChecks,cachedChecks,expectProjection);

		Set<Integer> owners=new LinkedHashSet<>();
		for(MemoOptimizer.Check check:referenceChecks) for(int owner:check.owners()) owners.add(owner);
		for(MemoOptimizer.Check check:cachedChecks) for(int owner:check.owners()) owners.add(owner);
		int[] boundaryOwners=owners.stream().mapToInt(Integer::intValue).sorted().toArray();
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(reference)).clone();
		long combinations=1;
		for(int owner:boundaryOwners) combinations=Math.multiplyExact(combinations,sizes[owner]);
		Assert.assertTrue("boundary-only Cartesian product must remain bounded: "+combinations,
			combinations<=4_096);

		int[] partial=new int[sizes.length];
		Arrays.fill(partial,-1);
		Assert.assertEquals(MemoOptimizer.Truth.UNKNOWN,conjunction(referenceChecks,partial));
		Assert.assertEquals("cache path must not promote an unresolved boundary",
			MemoOptimizer.Truth.UNKNOWN,conjunction(cachedChecks,partial));
		for(int owner:boundaryOwners) {
			partial[owner]=0;
			Assert.assertEquals("partial boundary truth at owner "+owner,
				conjunction(referenceChecks,partial),conjunction(cachedChecks,partial));
			partial[owner]=-1;
		}

		int[] selected=new int[sizes.length];
		long yes=0,no=0,unknown=0;
		BigDecimal referenceBest=null,cachedBest=null;
		int[] referenceWinner=null,cachedWinner=null;
		for(long value=0;value<combinations;value++) {
			MemoOptimizer.Truth expected=conjunction(referenceChecks,selected);
			MemoOptimizer.Truth actual=conjunction(cachedChecks,selected);
			Assert.assertEquals("boundary truth at selection "+Arrays.toString(selected),expected,actual);
			if(expected==MemoOptimizer.Truth.YES) yes++;
			else if(expected==MemoOptimizer.Truth.NO) no++;
			else unknown++;
			BigDecimal referenceCost=reference.evaluateCost(selected).total();
			BigDecimal cachedCost=cached.evaluateCost(selected).total();
			Assert.assertEquals("exact cost at selection "+Arrays.toString(selected),referenceCost,cachedCost);
			if(expected==MemoOptimizer.Truth.YES
				&& (referenceBest==null || referenceCost.compareTo(referenceBest)<0)) {
				referenceBest=referenceCost; referenceWinner=selected.clone();
			}
			if(actual==MemoOptimizer.Truth.YES && (cachedBest==null || cachedCost.compareTo(cachedBest)<0)) {
				cachedBest=cachedCost; cachedWinner=selected.clone();
			}
			increment(selected,boundaryOwners,sizes);
		}
		Assert.assertEquals(referenceBest,cachedBest);
		Assert.assertArrayEquals(referenceWinner,cachedWinner);
		if(requireLegal) Assert.assertTrue("fixture must retain at least one legal boundary selection",yes>0);
		if(requireBothCompleteTruths) {
			Assert.assertTrue("fixture must exercise a rejected complete boundary selection",no>0);
			Assert.assertEquals("complete exact boundary fixture must not stay unresolved",0,unknown);
		}
	}

	private static void assertBoundaryStructure(List<MemoOptimizer.Check> reference,
		List<MemoOptimizer.Check> projected,boolean expectProjection) {
		if(!expectProjection) {
			Assert.assertEquals("fallback must retain one original check per head",reference.size(),projected.size());
			for(int i=0;i<reference.size();i++) {
				Assert.assertEquals(reference.get(i).site(),projected.get(i).site());
				Assert.assertArrayEquals("fallback must retain the complete original scope",
					reference.get(i).owners(),projected.get(i).owners());
			}
			return;
		}
		boolean narrowed=false;
		for(MemoOptimizer.Check row:projected) {
			MemoOptimizer.Check broad=reference.stream().filter(check -> check.site()==row.site()).findFirst()
				.orElseThrow(() -> new AssertionError("projected row lost its broad head site "+row.site()));
			Set<Integer> broadOwners=new LinkedHashSet<>();
			for(int owner:broad.owners()) broadOwners.add(owner);
			for(int owner:row.owners())
				Assert.assertTrue("projected row introduced owner "+owner,broadOwners.contains(owner));
			Assert.assertTrue("a proven boundary row must be unary or binary",row.owners().length<=2);
			narrowed|=row.owners().length<broad.owners().length;
		}
		Assert.assertTrue("fixture must replace at least one broad boundary scope",narrowed);
	}

	private static List<MemoOptimizer.Check> boundaryChecks(MemoProgramModel model) {
		List<MemoOptimizer.Check> result=new ArrayList<>();
		for(MemoOptimizer.Check check:model.checks()) if(check.site()<0) result.add(check);
		return result;
	}

	private static MemoOptimizer.Truth conjunction(List<MemoOptimizer.Check> checks,int[] selected)
		throws Exception {
		Field predicateField=field(MemoOptimizer.Check.class,"predicate");
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

	private static void increment(int[] selected,int[] owners,int[] sizes) {
		for(int i=owners.length-1;i>=0;i--) {
			int owner=owners[i];
			if(++selected[owner]<sizes[owner]) return;
			selected[owner]=0;
		}
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field result=owner.getDeclaredField(name);
		result.setAccessible(true);
		return result;
	}

	private static Fixture fixture(String body,Map<String,SourceSpec> specs) throws Exception {
		StringBuilder script=new StringBuilder();
		for(var entry:specs.entrySet()) {
			SourceSpec spec=entry.getValue();
			long split=spec.rows()/2;
			script.append(entry.getKey()).append("=federated(addresses=list(\"localhost:19101/")
				.append(entry.getKey()).append("1\",\"localhost:19102/").append(entry.getKey())
				.append("2\"),ranges=list(list(0,0),list(").append(split).append(',').append(spec.columns())
				.append("),list(").append(split).append(",0),list(").append(spec.rows()).append(',')
				.append(spec.columns()).append(")));\n");
		}
		script.append(body);
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script.toString(),new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,data -> metadata(data,specs.get(data.getName())));
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		return new Fixture(facts,sources,universe);
	}

	private static FederatedSourceMetadata metadata(DataOp data,SourceSpec spec) {
		Assert.assertNotNull("missing source metadata for "+data.getName(),spec);
		long split=spec.rows()/2;
		return new FederatedSourceMetadata(spec.privacy(),List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {split,spec.columns()}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),data.getName()+"1")),
			new ImmutablePair<>(new FederatedRange(new long[] {split,0},new long[] {spec.rows(),spec.columns()}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),data.getName()+"2"))));
	}

	private record SourceSpec(long rows,long columns,Privacy privacy) { }
	private record Fixture(MemoProgramFacts facts,MemoSources sources,MemoRuleBuilder.Result universe) { }
}
