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

/** Tiny full native universes: no solver propagation, bounds, or DP in the reference enumeration. */
public class MemoExhaustiveParityTest {
	@Test public void sharedProducerUsesOneAssignmentAcrossItsConsumers() throws Exception {
		assertExact("A=abs(P);B=A+1;C=A*2;print(sum(B)+sum(C));");
	}

	@Test public void guardedJoinKeepsBothBranchesFeasible() throws Exception {
		assertExact("p=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=P;}else{A=matrix(2,4,2);}"
			+ "B=abs(A);print(sum(B));");
	}

	@Test public void loopBoundaryIncludesEntryAndDirectBackedge() throws Exception {
		assertExact("A=P;for(i in 1:2){A=abs(A);}print(sum(A));");
	}

	@Test public void sharedCompiledFunctionKeepsCallsiteBindings() throws Exception {
		assertExact("f=function(boolean p,matrix[double] X) return(matrix[double] Y){"
			+ "if(p){Y=abs(X);}else{Y=X;}}A=f(TRUE,P);B=f(FALSE,P);print(sum(A)+sum(B));");
	}

	@Test public void overwrittenTransientDoesNotRebindAnEarlierConsumer() throws Exception {
		assertExact("A=P;B=abs(A);A=matrix(2,4,2);C=abs(A);print(sum(B)+sum(C));");
	}

	private static void assertExact(String script) throws Exception {
		MemoProgramModel model=model("P=federated(addresses=list(\"localhost:19101/P1\",\"localhost:19102/P2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"+script);
		int[] sizes=((int[])field(MemoProgramModel.class,"sizes").get(model)).clone();
		long combinations=1;
		for(int size:sizes) combinations=Math.multiplyExact(combinations,size);
		Assert.assertTrue("fixture must remain exhaustive and small: "+combinations,combinations<=500_000);
		List<MemoOptimizer.Check> checks=model.checks();
		int[][] scopes=checks.stream().map(MemoOptimizer.Check::owners).toArray(int[][]::new);
		MemoOptimizer.Predicate[] predicates=new MemoOptimizer.Predicate[checks.size()];
		Field predicate=field(MemoOptimizer.Check.class,"predicate");
		for(int i=0;i<checks.size();i++) predicates[i]=(MemoOptimizer.Predicate)predicate.get(checks.get(i));
		BigDecimal best=null;
		int[] winner=null,selected=new int[sizes.length];
		long feasible=0;
		for(long n=0;n<combinations;n++) {
			boolean legal=true;
			for(int c=0;c<predicates.length;c++) {
				int[] tuple=new int[scopes[c].length];
				for(int i=0;i<tuple.length;i++) tuple[i]=selected[scopes[c][i]];
				MemoOptimizer.Truth truth=predicates[c].test(tuple);
				Assert.assertNotEquals("complete supported fixture has no unknown binding",MemoOptimizer.Truth.UNKNOWN,truth);
				if(truth==MemoOptimizer.Truth.NO) { legal=false; break; }
			}
			if(legal) {
				feasible++;
				BigDecimal cost=model.evaluateCost(selected).total();
				if(best==null || cost.compareTo(best)<0) { best=cost; winner=selected.clone(); }
			}
			for(int i=selected.length-1;i>=0;i--) {
				if(++selected[i]<sizes[i]) break;
				selected[i]=0;
			}
		}
		Assert.assertTrue("fixture must have legal plans",feasible>0);
		for(int cache:new int[] {0,4096}) {
			var solution=model.optimize(new MemoOptimizer.Limits(Long.MAX_VALUE,256,cache));
			Assert.assertEquals(solution.diagnostics().toString(),MemoOptimizer.Status.OPTIMAL,solution.optimization().status());
			Assert.assertEquals("solver must preserve original exact cost",0,best.compareTo(solution.optimization().cost()));
			Assert.assertArrayEquals("deterministic lexicographic optimum",winner,solution.optimization().selectedRules());
		}
		System.out.println("MEMO_EXHAUSTIVE assignments="+combinations+" feasible="+feasible+" cost="+best);
	}

	private static Field field(Class<?> owner,String name) throws Exception {
		Field result=owner.getDeclaredField(name); result.setAccessible(true); return result;
	}

	private static MemoProgramModel model(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,ignored -> new FederatedSourceMetadata(Privacy.PUBLIC,List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {2,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),"P1")),
			new ImmutablePair<>(new FederatedRange(new long[] {2,0},new long[] {4,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),"P2")))));
		return new MemoProgramModel(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()),
			MemoProgramModel.Profile.defaults());
	}
}
