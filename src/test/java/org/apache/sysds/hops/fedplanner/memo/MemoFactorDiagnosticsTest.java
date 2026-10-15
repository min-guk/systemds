/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class MemoFactorDiagnosticsTest {
	@Test public void liveConstructionReadsFinalDomainsAtSnapshotTime() {
		int[] domains=new int[2];
		MemoFactorDiagnostics diagnostics=MemoFactorDiagnostics.liveDomains(domains);
		domains[0]=2;
		diagnostics.addNativeArray(0,List.of());
		domains[1]=3;
		diagnostics.check(MemoFactorDiagnostics.Category.INPUT_SUPPLY,1,new int[] {0,1},
			rules -> MemoOptimizer.Truth.YES,null,List.of());
		Assert.assertArrayEquals(new int[] {2},diagnostics.factors().get(0).domains());
		Assert.assertArrayEquals(new int[] {2,3},diagnostics.factors().get(1).domains());
		Assert.assertEquals(6,summary(diagnostics,"NONE").estimatedMaxTableRows());
	}

	@Test public void typedGraphAndLeaveOutMetricsUseOriginalDomains() {
		MemoFactorDiagnostics diagnostics=new MemoFactorDiagnostics(new int[] {2,3,5,7});
		diagnostics.addNativeArray(0,List.of("owner:0"));
		diagnostics.check(MemoFactorDiagnostics.Category.INPUT_SUPPLY,11,new int[] {0,1},
			rules -> MemoOptimizer.Truth.YES,null,List.of("relation:function","binding:CALL_RETURN"));
		diagnostics.cost(MemoFactorDiagnostics.Category.MOVEMENT_RETAINED_SHARED,new int[] {1,2,3},
			rules -> 1,0,null,List.of("relation:branch","shared:true"));

		var factors=diagnostics.factors();
		Assert.assertArrayEquals(new int[] {2,3},factors.get(1).domains());
		int[] leaked=factors.get(1).domains(); leaked[0]=99;
		Assert.assertArrayEquals(new int[] {2,3},diagnostics.factors().get(1).domains());
		var full=summary(diagnostics,"NONE");
		Assert.assertEquals(4,full.nodes()); Assert.assertEquals(4,full.edges());
		var withoutMovement=summary(diagnostics,"CATEGORY:MOVEMENT_RETAINED_SHARED");
		Assert.assertEquals(1,withoutMovement.edges());
		Assert.assertEquals(4,withoutMovement.nodes());
		var withoutFunction=summary(diagnostics,"TAG:relation:function");
		Assert.assertEquals(3,withoutFunction.edges());
		Assert.assertTrue(full.estimatedMaxTableRows()>=7);
	}

	@Test public void timingWrappersReturnUnchangedValues() {
		MemoFactorDiagnostics diagnostics=new MemoFactorDiagnostics(new int[] {2,2});
		MemoOptimizer.Check check=diagnostics.check(MemoFactorDiagnostics.Category.INPUT_SUPPLY,3,
			new int[] {0,1},rules -> rules[0]==rules[1] ? MemoOptimizer.Truth.YES : MemoOptimizer.Truth.NO,
			null,List.of());
		MemoOptimizer.CostTerm cost=diagnostics.cost(MemoFactorDiagnostics.Category.MOVEMENT_UNRETAINED,
			new int[] {0,1},rules -> rules[0]+2*rules[1],0,null,List.of());
		Assert.assertEquals(MemoOptimizer.Truth.YES,check.evaluate(new int[] {1,1}));
		Assert.assertEquals("2",cost.evaluateExact(new int[] {0,1}).toPlainString());
		Assert.assertEquals(1,timing(diagnostics,MemoFactorDiagnostics.Category.INPUT_SUPPLY).evaluations());
		Assert.assertEquals(1,timing(diagnostics,MemoFactorDiagnostics.Category.MOVEMENT_UNRETAINED).evaluations());
		Assert.assertTrue(timing(diagnostics,MemoFactorDiagnostics.Category.INPUT_SUPPLY).evaluationNanos()>=0);
	}

	@Test public void programModelDiagnosticsAreOptInAndDoNotChangeExactPlan() throws Exception {
		String property=MemoFactorDiagnostics.ENABLE_PROPERTY;
		String before=System.getProperty(property);
		try {
			System.clearProperty(property);
			MemoProgramModel plain=model();
			var plainResult=plain.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
			Assert.assertNull(plain.factorDiagnostics());

			System.setProperty(property,"true");
			MemoProgramModel observed=model();
			var observedResult=observed.optimize(MemoOptimizer.Limits.exactDefaults()).optimization();
			Assert.assertNotNull(observed.factorDiagnostics());
			Assert.assertEquals(plainResult.status(),observedResult.status());
			Assert.assertEquals(0,plainResult.cost().compareTo(observedResult.cost()));
			Assert.assertArrayEquals(plainResult.selectedRules(),observedResult.selectedRules());
			List<MemoFactorDiagnostics.Factor> factors=observed.factorDiagnostics().factors();
			Assert.assertTrue(factors.stream().flatMapToInt(f -> java.util.Arrays.stream(f.domains()))
				.allMatch(domain -> domain>0));
			Assert.assertTrue(factors.stream().anyMatch(f -> f.category()==MemoFactorDiagnostics.Category.INPUT_SUPPLY));
			Assert.assertTrue(factors.stream().anyMatch(f -> f.tags().contains("relation:function")));
			Assert.assertTrue(factors.stream().anyMatch(f -> f.tags().contains("relation:branch")));
			Assert.assertTrue(factors.stream().filter(MemoFactorDiagnostics.Factor::evaluatorInstrumented)
				.anyMatch(f -> f.evaluations()>0));
		}
		finally {
			if(before==null) System.clearProperty(property); else System.setProperty(property,before);
		}
	}

	private static MemoFactorDiagnostics.GraphSummary summary(MemoFactorDiagnostics diagnostics,String excluded) {
		return diagnostics.graphSummaries().stream().filter(value -> value.excludedCategory().equals(excluded))
			.findFirst().orElseThrow();
	}
	private static MemoFactorDiagnostics.CategoryTiming timing(MemoFactorDiagnostics diagnostics,
		MemoFactorDiagnostics.Category category) {
		return diagnostics.timings().stream().filter(value -> value.category()==category).findFirst().orElseThrow();
	}

	private static MemoProgramModel model() throws Exception {
		String script="f=function(boolean p,matrix[double] X) return(matrix[double] Y){"
			+"if(p){Y=abs(X);}else{Y=X;}} P=matrix(1,rows=2,cols=2);"
			+"A=f(TRUE,P);B=f(FALSE,P);print(sum(A)+sum(B));";
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null);
		return new MemoProgramModel(facts,sources,MemoRuleBuilder.build(facts,sources,new MemoNativeRules()),
			MemoProgramModel.Profile.defaults());
	}
}
