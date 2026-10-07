/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** The explicit STEP-LM CG experiment must not silently dispatch back to DS. */
public class StepLmCgCallGraphTest {
	@Test
	public void stepLmCallsCgWithoutAutomaticDimensionDispatch() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(50000,128)));\n"
			+ "y=federated(addresses=list(\"localhost:1234/y\"),"
			+ "ranges=list(list(0,0),list(50000,1)));\n"
			+ "[B,S]=steplm(X=X,y=y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);\n"
			+ "print(sum(B));\n";
		var program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		var functions = program.getBuiltinFunctionDictionary().getFunctions();
		Assert.assertTrue("CG implementation must be present", functions.containsKey("m_lmCG"));
		Assert.assertFalse("STEP-LM must bypass the lm dimension selector", functions.containsKey("m_lm"));
		Assert.assertFalse("DS must not remain on the STEP-LM call graph", functions.containsKey("m_lmDS"));
	}

	@Test
	public void cgKeepsProtectedTransposeOutsideItsIterationLoop() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(80,8)));\n"
			+ "y=matrix(1,rows=80,cols=1);\n"
			+ "B=lmCG(X=X,y=y,icpt=0,maxi=10,verbose=FALSE);print(sum(B));\n";
		var program = NeutralPlacementFixedPointCompositionTest.compileProtected(script);
		var function = program.getBuiltinFunctionDictionary().getFunctions().get("m_lmCG");
		Assert.assertNotNull(function);
		Set<Hop> outside = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Hop> inside = Collections.newSetFromMap(new IdentityHashMap<>());
		for(StatementBlock block : ((FunctionStatement) function.getStatement(0)).getBody())
			collect(block, false, outside, inside);
		Assert.assertEquals("X transpose must be computed once before the loop", 1,
			outside.stream().filter(StepLmCgCallGraphTest::isXTranspose).count());
		Assert.assertEquals("The CG loop must not transpose X again", 0,
			inside.stream().filter(StepLmCgCallGraphTest::isXTranspose).count());
		Assert.assertTrue("The retained transpose must be written before the loop",
			outside.stream().anyMatch(hop -> isTransposeVariable(hop, OpOpData.TRANSIENTWRITE)));
		Assert.assertTrue("The loop must read the retained transpose",
			inside.stream().anyMatch(hop -> isTransposeVariable(hop, OpOpData.TRANSIENTREAD)));
	}

	private static boolean isXTranspose(Hop hop) {
		return HopRewriteUtils.isTransposeOperation(hop) && "X".equals(hop.getInput().get(0).getName());
	}

	private static boolean isTransposeVariable(Hop hop, OpOpData op) {
		return hop instanceof DataOp data && data.getOp() == op && "Xt".equals(hop.getName());
	}

	private static void collect(StatementBlock block, boolean inLoop, Set<Hop> outside, Set<Hop> inside) {
		if(block instanceof WhileStatementBlock)
			for(StatementBlock child : ((WhileStatement) block.getStatement(0)).getBody())
				collect(child, true, outside, inside);
		else if(block instanceof ForStatementBlock)
			for(StatementBlock child : ((ForStatement) block.getStatement(0)).getBody())
				collect(child, true, outside, inside);
		else if(block instanceof IfStatementBlock) {
			IfStatement conditional = (IfStatement) block.getStatement(0);
			for(StatementBlock child : conditional.getIfBody())
				collect(child, inLoop, outside, inside);
			for(StatementBlock child : conditional.getElseBody())
				collect(child, inLoop, outside, inside);
		}
		else if(block.getHops() != null)
			for(Hop root : block.getHops())
				collect(root, inLoop ? inside : outside);
	}

	private static void collect(Hop hop, Set<Hop> found) {
		if(found.add(hop))
			for(Hop child : hop.getInput())
				collect(child, found);
	}
}
