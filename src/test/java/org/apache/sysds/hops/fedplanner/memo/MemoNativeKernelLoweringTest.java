/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.lops.TernaryAggregate;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class MemoNativeKernelLoweringTest {
	@Test public void selectedSumProductKeepsItsIndependentlyPricedMultiply() throws Exception {
		DMLProgram program=parse();
		MemoProgramFacts facts=new FederatedPlanMemo().plan(program).facts();
		Hop multiply=facts.occurrences().stream().map(MemoProgramFacts.Occurrence::hop)
			.filter(hop -> hop instanceof BinaryOp binary && binary.getOp()==OpOp2.MULT).findFirst().orElseThrow();
		Assert.assertTrue(MemoPlanRegistry.preservesNativeKernel(multiply));
		constructSelectedLops(program);
		Assert.assertTrue("selected authority must survive statement lowering",MemoPlanRegistry.preservesNativeKernel(multiply));
		Assert.assertNotNull("selected multiply is a native cost/supply owner, not an implicit fusion",multiply.getLops());
		Assert.assertFalse(multiply.getParent().get(0).getLops() instanceof TernaryAggregate);
	}

	@Test public void unselectedSumProductRetainsOrdinaryCompilerFusion() throws Exception {
		DMLProgram program=parse();
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		Hop multiply=facts.occurrences().stream().map(MemoProgramFacts.Occurrence::hop)
			.filter(hop -> hop instanceof BinaryOp binary && binary.getOp()==OpOp2.MULT).findFirst().orElseThrow();
		new DMLTranslator(program).constructLops(program);
		Assert.assertTrue("the change must not globally turn off the normal compiler optimization",
			multiply.getParent().get(0).getLops() instanceof TernaryAggregate);
	}

	@Test public void selectedMatrixChainPreservesInnerMultiplyAndTranspose() throws Exception {
		assertSelectedKernelsSurvive("X=matrix(1,4,2);v=matrix(2,2,1);print(sum(t(X)%*%(X%*%v)));");
	}

	@Test public void selectedOuterAggregatePreservesComparison() throws Exception {
		assertSelectedKernelsSurvive("X=matrix(1,4,1);Y=matrix(2,1,3);print(sum(outer(X,Y,\"<\")));");
	}

	@Test public void selectedReluPatternPreservesItsComparisonAndMultiply() throws Exception {
		assertSelectedKernelsSurvive("X=matrix(1,4,2);Y=matrix(2,4,2);print(sum((X>0)*Y));");
	}

	private static void assertSelectedKernelsSurvive(String script) throws Exception {
		DMLProgram program=parse(script);
		MemoProgramFacts facts=new FederatedPlanMemo().plan(program).facts();
		constructSelectedLops(program);
		for(var occurrence:facts.occurrences()) {
			Hop hop=occurrence.hop();
			if(!(hop instanceof org.apache.sysds.hops.LiteralOp) && !MemoRuleBuilder.transparent(hop))
				Assert.assertNotNull("selected native owner "+hop.getOpString()+" must survive lowering",hop.getLops());
		}
		new DMLTranslator(program).getRuntimeProgram(program,org.apache.sysds.conf.ConfigurationManager.getDMLConfig());
	}

	private static void constructSelectedLops(DMLProgram program) {
		DMLTranslator translator=new DMLTranslator(program);
		try(FederatedPlannerUtils.PlannerRecompileOwnerScope ignored=
			FederatedPlannerUtils.activatePlannerRecompileOwner(program)) {
			for(var block:program.getStatementBlocks()) translator.constructLops(block);
		}
	}

	private static DMLProgram parse() throws Exception {
		return parse("X=matrix(1,4,2);Y=matrix(2,4,2);print(sum(X*Y));");
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);translator.validateParseTree(program);translator.constructHops(program);
		return program;
	}
	@After public void cleanup() { MemoPlanRegistry.clear(); }
}
