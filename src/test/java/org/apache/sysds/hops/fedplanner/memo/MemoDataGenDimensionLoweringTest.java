/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.ParserFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class MemoDataGenDimensionLoweringTest {
	private record Fixture(DataGenOp dataGen,UnaryOp dimension) { }

	@After public void cleanup() { MemoPlanRegistry.clear(); }

	@Test public void knownRandRowsMayEmbedSelectedNrowAsLiteral() throws Exception {
		DMLProgram program=parse("X=matrix(1,2,2);R=matrix(0,2,1);print(sum(X)+sum(R));");
		DataGenOp rand=all(program).stream().filter(DataGenOp.class::isInstance).map(DataGenOp.class::cast)
			.filter(hop -> hop.getDim1()==2 && hop.getDim2()==1).findFirst().orElseThrow();
		Hop x=all(program).stream().filter(DataGenOp.class::isInstance)
			.filter(hop -> hop!=rand && hop.getDim1()==2 && hop.getDim2()==2).findFirst().orElseThrow();
		UnaryOp nrow=new UnaryOp("rows",DataType.SCALAR,ValueType.INT64,OpOp1.NROW,x);
		int rowPosition=rand.getParamIndexMap().get(DataExpression.RAND_ROWS);
		Hop literalRows=rand.getInput().set(rowPosition,nrow);
		literalRows.getParent().remove(rand); nrow.getParent().add(rand);
		// Recompilation can establish the DataGen output shape without rewriting its logical
		// dimension input. This is the representation observed in the GLM failure.
		new FederatedPlanMemo().plan(program);

		x.constructLops(); rand.constructLops();
		Assert.assertNull("known DataGen rows embed a replacement literal instead of lowering nrow",nrow.getLops());
		Assert.assertNotNull(rand.getLops());
		List<Lop> lowered=new ArrayList<>(lopDag(rand.getLops())); lowered.addAll(lopDag(x.getLops()));
		MemoPlanRegistry.verifyLowering(program.getStatementBlocks().get(0),List.of(rand),lowered);
	}

	@Test public void unknownDataGenDimensionIsNotEmbedded() throws Exception {
		Fixture fixture=fixture(OpOp1.NROW,DataExpression.RAND_ROWS);
		fixture.dataGen().constructLops(); fixture.dataGen().setDim1(-1);
		Assert.assertFalse(MemoPlanRegistry.isEmbeddedDataGenDimension(fixture.dimension(),ExecType.CP,
			MemoPartition.LOCAL,Set.of(fixture.dataGen().getLops()),ignored -> true));
	}

	@Test public void wrongMetadataAxisIsNotEmbedded() throws Exception {
		Fixture fixture=fixture(OpOp1.NROW,DataExpression.RAND_COLS);
		fixture.dataGen().constructLops();
		Assert.assertFalse(MemoPlanRegistry.isEmbeddedDataGenDimension(fixture.dimension(),ExecType.CP,
			MemoPartition.LOCAL,Set.of(fixture.dataGen().getLops()),ignored -> true));
	}

	@Test public void mixedExecutableParentIsNotEmbedded() throws Exception {
		Fixture fixture=fixture(OpOp1.NROW,DataExpression.RAND_ROWS);
		fixture.dataGen().constructLops();
		new UnaryOp("shared",DataType.SCALAR,ValueType.INT64,OpOp1.ABS,fixture.dimension());
		Assert.assertFalse(MemoPlanRegistry.isEmbeddedDataGenDimension(fixture.dimension(),ExecType.CP,
			MemoPartition.LOCAL,Set.of(fixture.dataGen().getLops()),ignored -> true));
	}

	@Test public void federatedMetadataSelectionIsNotEmbedded() throws Exception {
		Fixture fixture=fixture(OpOp1.NROW,DataExpression.RAND_ROWS);
		fixture.dataGen().constructLops();
		Assert.assertFalse(MemoPlanRegistry.isEmbeddedDataGenDimension(fixture.dimension(),ExecType.FED,
			MemoPartition.ROW,Set.of(fixture.dataGen().getLops()),ignored -> true));
	}

	@Test public void absentDataGenParentLopIsNotEmbedded() throws Exception {
		Fixture fixture=fixture(OpOp1.NROW,DataExpression.RAND_ROWS);
		fixture.dataGen().constructLops();
		Assert.assertFalse(MemoPlanRegistry.isEmbeddedDataGenDimension(fixture.dimension(),ExecType.CP,
			MemoPartition.LOCAL,Set.of(),ignored -> true));
	}

	private static Fixture fixture(OpOp1 operation,String parameter) throws Exception {
		DMLProgram program=parse("X=matrix(1,2,2);R=matrix(0,2,1);print(sum(X)+sum(R));");
		DataGenOp target=all(program).stream().filter(DataGenOp.class::isInstance).map(DataGenOp.class::cast)
			.filter(hop -> hop.getDim1()==2 && hop.getDim2()==1).findFirst().orElseThrow();
		Hop source=all(program).stream().filter(DataGenOp.class::isInstance)
			.filter(hop -> hop!=target && hop.getDim1()==2 && hop.getDim2()==2).findFirst().orElseThrow();
		UnaryOp dimension=new UnaryOp("dimension",DataType.SCALAR,ValueType.INT64,operation,source);
		int position=target.getParamIndexMap().get(parameter); Hop replaced=target.getInput().set(position,dimension);
		replaced.getParent().remove(target); dimension.getParent().add(target);
		return new Fixture(target,dimension);
	}

	private static List<Lop> lopDag(Lop root) {
		List<Lop> result=new ArrayList<>(); Set<Lop> seen=Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Lop> queue=new ArrayDeque<>(); queue.add(root);
		while(!queue.isEmpty()) { Lop lop=queue.removeFirst(); if(!seen.add(lop)) continue;
			result.add(lop); queue.addAll(lop.getInputs()); }
		return result;
	}

	private static List<Hop> all(DMLProgram program) {
		List<Hop> result=new ArrayList<>(); Set<Hop> seen=Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Hop> queue=new ArrayDeque<>();
		for(var block:program.getStatementBlocks()) if(block.getHops()!=null) queue.addAll(block.getHops());
		while(!queue.isEmpty()) { Hop hop=queue.removeFirst(); if(!seen.add(hop)) continue;
			result.add(hop); queue.addAll(hop.getInput()); }
		return result;
	}

	private static DMLProgram parse(String script) throws Exception {
		DMLProgram program=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script,new HashMap<>()); DMLTranslator translator=new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		return program;
	}
}
