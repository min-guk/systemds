/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.lops.TernaryAggregate;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class MemoPlanLifecycleTest {
	@After public void cleanup() { MemoPlanRegistry.clear(); }

	@Test public void removeRestoresInitialCompilerStateAndKeepsUnrelatedOwner() throws Exception {
		DMLProgram first=parse("X=matrix(-1,4,2);Y=abs(X);print(sum(Y));");
		Hop abs=all(first).stream().filter(hop -> hop.getOpString().contains("abs")).findFirst().orElseThrow();
		abs.setForcedExecType(ExecType.SPARK); abs.setExecType(ExecType.SPARK);
		abs.setFederatedOutput(FederatedOutput.NONE); abs.activatePrefetch();
		DMLProgram second=parse("A=matrix(1,2,2);for(i in 1:2){A=A+1;}print(sum(A));");

		new FederatedPlanMemo().plan(first);
		new FederatedPlanMemo().plan(second);
		Assert.assertTrue(MemoPlanRegistry.hasPlan(first));
		Assert.assertTrue(MemoPlanRegistry.hasPlan(second));
		Assert.assertTrue(abs.isPlannerPlacementSelected());
		Assert.assertFalse(MemoLoopRuntime.snapshotAll().isEmpty());

		MemoPlanRegistry.remove(first);
		Assert.assertFalse(MemoPlanRegistry.hasPlan(first));
		Assert.assertTrue(MemoPlanRegistry.hasPlan(second));
		Assert.assertEquals(ExecType.SPARK,abs.getForcedExecType());
		Assert.assertEquals(ExecType.SPARK,abs.getExecType());
		Assert.assertEquals(FederatedOutput.NONE,abs.getFederatedOutput());
		Assert.assertTrue(abs.prefetchActivated());
		Assert.assertFalse(abs.isPlannerPlacementSelected());
		Assert.assertTrue("the unrelated loop owner must retain its contracts",
			MemoLoopRuntime.snapshotAll().keySet().stream().allMatch(block -> block.getDMLProg()==second));
	}

	@Test public void ordinaryCompilationRevokesStaleMemoKernelAuthorityAndFusesNormally() throws Exception {
		DMLProgram program=parse("X=matrix(1,4,2);Y=matrix(2,4,2);print(sum(X*Y));");
		MemoProgramFacts facts=new FederatedPlanMemo().plan(program).facts();
		Hop multiply=facts.occurrences().stream().map(MemoProgramFacts.Occurrence::hop)
			.filter(hop -> hop instanceof BinaryOp binary && binary.getOp()==OpOp2.MULT)
			.findFirst().orElseThrow();
		Assert.assertTrue(MemoPlanRegistry.preservesNativeKernel(multiply));

		DMLConfig old=ConfigurationManager.getDMLConfig();
		boolean oldCompilation=OptimizerUtils.FEDERATED_COMPILATION;
		try {
			DMLConfig ordinary=new DMLConfig(); ordinary.setTextValue(DMLConfig.FEDERATED_PLANNER,"NONE");
			ConfigurationManager.setLocalConfig(ordinary); OptimizerUtils.FEDERATED_COMPILATION=false;
			new DMLTranslator(program).constructLops(program);
		}
		finally { OptimizerUtils.FEDERATED_COMPILATION=oldCompilation; ConfigurationManager.setLocalConfig(old); }

		Assert.assertFalse(MemoPlanRegistry.hasPlan(program));
		Assert.assertFalse(multiply.isPlannerPlacementSelected());
		Assert.assertFalse(MemoPlanRegistry.preservesNativeKernel(multiply));
		Assert.assertTrue(multiply.getParent().get(0).getLops() instanceof TernaryAggregate);
	}

	@Test public void replanRetainsTheOriginalPreMemoCompilerState() throws Exception {
		DMLProgram program=parse("X=matrix(-1,4,2);Y=abs(X);print(sum(Y));");
		Hop abs=all(program).stream().filter(hop -> hop.getOpString().contains("abs")).findFirst().orElseThrow();
		abs.setForcedExecType(ExecType.SPARK); abs.setExecType(ExecType.SPARK);
		abs.setFederatedOutput(FederatedOutput.NONE); abs.activatePrefetch();

		new FederatedPlanMemo().plan(program);
		new FederatedPlanMemo().plan(program);
		MemoPlanRegistry.remove(program);

		Assert.assertEquals(ExecType.SPARK,abs.getForcedExecType());
		Assert.assertEquals(ExecType.SPARK,abs.getExecType());
		Assert.assertEquals(FederatedOutput.NONE,abs.getFederatedOutput());
		Assert.assertTrue(abs.prefetchActivated());
		Assert.assertFalse(abs.isPlannerPlacementSelected());
	}

	@Test public void supplyReplacementAndRemovalAreOwnerSpecificAndAtomic() throws Exception {
		DMLProgram first=parse("print(1);");
		DMLProgram second=parse("print(2);");
		long firstBlock=first.getStatementBlocks().get(0).getSBID();
		long secondBlock=second.getStatementBlocks().get(0).getSBID();
		MemoSupplyRegistry.replaceForOwner(first,java.util.Map.of(firstBlock,java.util.List.of()));
		MemoSupplyRegistry.replaceForOwner(second,java.util.Map.of(secondBlock,java.util.List.of()));

		try {
			MemoSupplyRegistry.replaceForOwner(second,java.util.Map.of(firstBlock,java.util.List.of()));
			Assert.fail("one statement-block id cannot be owned by two programs");
		}
		catch(IllegalStateException expected) { /* expected */ }
		Assert.assertTrue(MemoSupplyRegistry.snapshotAll().containsKey(firstBlock));
		Assert.assertTrue("failed replacement must retain the owner's prior entry",
			MemoSupplyRegistry.snapshotAll().containsKey(secondBlock));

		MemoSupplyRegistry.remove(first);
		Assert.assertFalse(MemoSupplyRegistry.snapshotAll().containsKey(firstBlock));
		Assert.assertTrue(MemoSupplyRegistry.snapshotAll().containsKey(secondBlock));
	}

	@Test public void activeRecompileScopePreventsPlanRemovalUntilClosed() throws Exception {
		DMLProgram program=parse("X=matrix(1,2,2);Y=abs(X);print(sum(Y));");
		new FederatedPlanMemo().plan(program);
		long epoch=MemoPlanRegistry.epoch(program);
		var block=program.getStatementBlocks().get(0);
		try(MemoPlanRegistry.RecompileScope scope=
			MemoPlanRegistry.openRecompile(program,block,block.getHops())) {
			Assert.assertThrows(MemoPlanRegistry.StaleMemoPlanException.class,
				() -> new FederatedPlanMemo().plan(program));
			Assert.assertThrows(MemoPlanRegistry.StaleMemoPlanException.class,
				() -> MemoPlanRegistry.remove(program));
			Assert.assertTrue(MemoPlanRegistry.hasPlan(program));
			Assert.assertEquals(epoch,MemoPlanRegistry.epoch(program));
		}
		MemoPlanRegistry.remove(program);
		Assert.assertFalse(MemoPlanRegistry.hasPlan(program));
	}

	private static java.util.List<Hop> all(DMLProgram program) {
		java.util.List<Hop> result=new java.util.ArrayList<>();
		java.util.Set<Hop> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		java.util.ArrayDeque<Hop> queue=new java.util.ArrayDeque<>();
		for(var block:program.getStatementBlocks()) if(block.getHops()!=null) queue.addAll(block.getHops());
		while(!queue.isEmpty()) { Hop hop=queue.removeFirst(); if(!seen.add(hop)) continue;
			result.add(hop); queue.addAll(hop.getInput()); }
		return result;
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return program;
	}
}
