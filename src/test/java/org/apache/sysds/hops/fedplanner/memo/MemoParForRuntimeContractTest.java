/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParForStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.ParForProgramBlock;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.IntObject;
import org.apache.sysds.runtime.instructions.cp.ScalarObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class MemoParForRuntimeContractTest {
	@After public void cleanup() { MemoPlanRegistry.clear(); }

	@Test public void publishedOneIterationProofRejectsRuntimeCardinalityDriftBeforeTasks() throws Exception {
		DMLProgram program=parse("A=matrix(0,1,1);parfor(i in 1:1,check=0){A[i,1]=i;}print(sum(A));");
		ParForStatementBlock block=parFor(program);
		new FederatedPlanMemo().plan(program);
		Assert.assertEquals(Long.valueOf(1),
			MemoPlanRegistry.selectedParForIterations(program,block.getSBID()));

		TestParForProgramBlock runtime=new TestParForProgramBlock(program,1,2,1);
		runtime.setStatementBlock(block);
		DMLRuntimeException error=Assert.assertThrows(DMLRuntimeException.class,
			() -> runtime.execute(new ExecutionContext(new LocalVariableMap())));
		Assert.assertTrue(error.getMessage(),error.getMessage().contains("proven to have exactly 1"));
		Assert.assertEquals("the guard must fail before any exit/task path",0,runtime.exitExecutions);
	}

	@Test public void selectedZeroIterationParforExecutesItsExitInstructionPath() throws Exception {
		DMLProgram program=parse(
			"parfor(i in seq(1,0,1),check=0){print(i);}print(1);");
		ParForStatementBlock block=parFor(program);
		new FederatedPlanMemo().plan(program);
		Assert.assertEquals(Long.valueOf(0),
			MemoPlanRegistry.selectedParForIterations(program,block.getSBID()));

		TestParForProgramBlock runtime=new TestParForProgramBlock(program,1,0,1);
		runtime.setStatementBlock(block);
		runtime.execute(new ExecutionContext(new LocalVariableMap()));
		Assert.assertEquals(1,runtime.exitExecutions);
	}

	@Test public void uncalledFunctionParforIsNotPublishedAsARuntimeContract() throws Exception {
		DMLProgram program=parse("f=function(double n) return(matrix[double] Y){Y=matrix(0,1,1);"
			+ "parfor(i in 1:n,check=0){Y[1,1]=i;}}print(1);");
		MemoProgramFacts facts=new FederatedPlanMemo().plan(program).facts();
		MemoProgramFacts.LoopRecord loop=facts.loop(0);
		Assert.assertTrue(facts.callFramesForRegion(loop.regionId()).isEmpty());
		Assert.assertNull(MemoPlanRegistry.selectedParForIterations(program,loop.statementBlockId()));
	}

	private static final class TestParForProgramBlock extends ParForProgramBlock {
		private final long from;
		private final long to;
		private final long increment;
		private int exitExecutions;

		private TestParForProgramBlock(DMLProgram owner,long from,long to,long increment) {
			super(new Program(owner),"i",parameters(),new ArrayList<>());
			this.from=from;
			this.to=to;
			this.increment=increment;
			ArrayList<Instruction> explicitIncrement=new ArrayList<>();
			explicitIncrement.add(null); // marker only; predicate evaluation is overridden below
			setIncrementInstructions(explicitIncrement);
		}

		@Override protected ScalarObject executePredicateInstructions(int position,
			ArrayList<Instruction> instructions,ExecutionContext context,boolean downCast) {
			return new IntObject(position==1 ? from : position==2 ? to : increment);
		}

		@Override protected void executeExitInstructions(String context,ExecutionContext executionContext) {
			exitExecutions++;
		}
	}

	private static HashMap<String,String> parameters() {
		HashMap<String,String> result=new HashMap<>();
		result.put(ParForStatementBlock.PAR,"1");
		result.put(ParForStatementBlock.TASK_PARTITIONER,"FIXED");
		result.put(ParForStatementBlock.TASK_SIZE,"1");
		result.put(ParForStatementBlock.DATA_PARTITIONER,"NONE");
		result.put(ParForStatementBlock.RESULT_MERGE,"LOCAL_AUTOMATIC");
		result.put(ParForStatementBlock.EXEC_MODE,"LOCAL");
		result.put(ParForStatementBlock.OPT_MODE,"NONE");
		result.put(ParForStatementBlock.OPT_LOG,"WARN");
		return result;
	}

	private static ParForStatementBlock parFor(DMLProgram program) {
		return program.getStatementBlocks().stream().filter(ParForStatementBlock.class::isInstance)
			.map(ParForStatementBlock.class::cast).findFirst().orElseThrow();
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>());
		DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}
