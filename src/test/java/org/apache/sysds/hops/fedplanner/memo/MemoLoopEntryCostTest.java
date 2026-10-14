/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.lang.reflect.Method;
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

public class MemoLoopEntryCostTest {
	@Test public void nestedHeadEntryCollectIsChargedOncePerOuterIteration() throws Exception {
		double once=entryCost(1,false);
		double three=entryCost(3,false);
		Assert.assertTrue("outer LOOP_HEAD_CONTRACT entry movement must not be free",once>0);
		Assert.assertEquals("inner entry movement occurs once per dynamic outer invocation",3*once,three,0);
	}
	@Test public void phaseProvenLastIterationGuardChargesOneInnerEntry() throws Exception {
		double once=entryCost(1,false);
		double guardedLast=entryCost(3,true);
		Assert.assertEquals("i==3 activates the inner loop only in the outer LAST phase",once,guardedLast,0);
	}

	private static double entryCost(int outerIterations,boolean guardLast) throws Exception {
		String innerScript="for(j in 1:2){X=abs(X);}";
		String body=guardLast ? "if(i=="+outerIterations+"){"+innerScript+"}" : innerScript;
		return entryCostFromScript("X=federated(addresses=list(\"localhost:19101/X1\",\"localhost:19102/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
				+ "for(i in 1:"+outerIterations+"){"+body+"}print(sum(X));");
	}

	private static double entryCostFromScript(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		MemoProgramFacts facts=MemoProgramFacts.analyze(program);
		MemoSources sources=MemoSources.acquire(facts,null,ignored -> sourceMetadata());
		MemoRuleBuilder.Result universe=MemoRuleBuilder.build(facts,sources,new MemoNativeRules());
		MemoProgramModel model=new MemoProgramModel(facts,sources,universe,MemoProgramModel.Profile.defaults());
		Assert.assertEquals(2,model.heads().size());
		MemoProgramModel.Head outer=model.heads().stream().filter(head -> head.slot().loopId()==0)
			.findFirst().orElseThrow();
		MemoProgramModel.Head inner=model.heads().stream().filter(head -> head.slot().loopId()==1)
			.findFirst().orElseThrow();
		int[] selected=model.optimize(MemoOptimizer.Limits.exactDefaults()).optimization().selectedRules().clone();
		selected[outer.variable()]=MemoPartition.ROW.ordinal();
		selected[inner.variable()]=MemoPartition.LOCAL.ordinal();
		Method entry=MemoProgramModel.class.getDeclaredMethod("entryCost",MemoProgramModel.Head.class,int[].class);
		entry.setAccessible(true);
		return (double)entry.invoke(model,inner,selected);
	}

	private static FederatedSourceMetadata sourceMetadata() {
		return new FederatedSourceMetadata(Privacy.PUBLIC,List.of(
			new ImmutablePair<>(new FederatedRange(new long[] {0,0},new long[] {2,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19101),"X1")),
			new ImmutablePair<>(new FederatedRange(new long[] {2,0},new long[] {4,2}),
				new FederatedData(DataType.MATRIX,new InetSocketAddress("localhost",19102),"X2"))));
	}
}
