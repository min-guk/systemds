/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.parser;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.junit.Assert;
import org.junit.Test;

public class FederatedLocalZeroCallsiteSpecializerTest {
	private static final String SCRIPT =
		"inner=function(matrix[double] X) return(matrix[double] Y) {\n"
		+ " if(sum(X)>0) {Y=X+1;} else {Y=X+2;}\n"
		+ "}\n"
		+ "outer=function(matrix[double] X) return(matrix[double] Y) {\n"
		+ " Y=inner(X); Y=Y+X;\n"
		+ "}\n"
		+ "Z=matrix(0,rows=4,cols=1); A=rand(rows=4,cols=1);\n"
		+ "L=outer(Z); R=outer(A); print(sum(L+R));\n";
	private static final String NAMED_SCRIPT =
		"outer=function(matrix[double] X, matrix[double] K) return(matrix[double] Y) {\n"
		+ " if(sum(X)>0) {Y=X+K;} else {Y=X-K;}\n"
		+ "}\n"
		+ "Z=matrix(0,rows=4,cols=1); A=rand(rows=4,cols=1); Q=matrix(1,rows=4,cols=1);\n"
		+ "L=outer(X=Z,K=Q); R=outer(K=Q,X=A); print(sum(L+R));\n";

	@Test
	public void localZeroCallerGetsIsolatedNestedDmlFunctionsWithoutChangingOtherCaller() throws Exception {
		DMLProgram program = compile(SCRIPT);
		String outerKey = DMLProgram.constructFunctionKey(DMLProgram.DEFAULT_NAMESPACE, "outer");
		List<FunctionOp> before = new FunctionCallGraph(program).getFunctionCalls(outerKey);
		Assert.assertEquals(2, before.size());
		FunctionStatementBlock original = program.getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, "outer");
		original.setRecompileOnce(true);
		original.setNondeterministic(true);
		FunctionStatementBlock originalInner = program.getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, "inner");
		originalInner.setRecompileOnce(true);
		originalInner.setNondeterministic(true);

		Assert.assertEquals(1, FederatedLocalZeroCallsiteSpecializer.specialize(program));
		Assert.assertEquals(0, FederatedLocalZeroCallsiteSpecializer.specialize(program));
		Assert.assertTrue(before.stream().filter(call -> !call.getFunctionName().equals("outer"))
			.allMatch(call -> call.getInput().get(0) instanceof DataGenOp));
		String cloneName = before.stream().map(FunctionOp::getFunctionName)
			.filter(name -> !name.equals("outer")).findFirst().orElseThrow();
		FunctionStatementBlock clone = program.getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, cloneName);
		Assert.assertNotSame(original, clone);
		Assert.assertTrue(clone.isRecompileOnce());
		Assert.assertTrue(clone.isNondeterministic());
		Assert.assertNotSame(((FunctionStatement) original.getStatement(0)).getBody().get(0),
			((FunctionStatement) clone.getStatement(0)).getBody().get(0));
		for(StatementBlock originalBlock : ((FunctionStatement) original.getStatement(0)).getBody())
			for(org.apache.sysds.hops.Hop root : originalBlock.getHops())
				Assert.assertEquals(root.getHopID(), root.getPlannerOriginHopID());
		for(StatementBlock clonedBlock : ((FunctionStatement) clone.getStatement(0)).getBody())
			for(org.apache.sysds.hops.Hop root : clonedBlock.getHops())
				Assert.assertEquals(root.getHopID(), root.getPlannerOriginHopID());
		FunctionCallGraph graph = new FunctionCallGraph(program);
		String clonedOuterKey = DMLProgram.constructFunctionKey(DMLProgram.DEFAULT_NAMESPACE, cloneName);
		String nestedKey = graph.getCalledFunctions(clonedOuterKey).iterator().next();
		Assert.assertFalse(nestedKey.endsWith("::inner"));
		FunctionStatementBlock nestedClone = program.getFunctionStatementBlock(nestedKey);
		Assert.assertNotNull(nestedClone);
		Assert.assertNotSame(originalInner, nestedClone);
		Assert.assertTrue(nestedClone.isRecompileOnce());
		Assert.assertTrue(nestedClone.isNondeterministic());
		FunctionOp nestedCall = graph.getFunctionCalls(nestedKey).get(0);
		Assert.assertTrue("optimized nested call must not silently route to original dictionary",
			nestedCall.isCallOptimized());
		Assert.assertEquals(nestedCall.getFunctionKey(), nestedCall.getName());
		Assert.assertTrue(graph.getCalledFunctions(outerKey).stream().anyMatch(key -> key.endsWith("::inner")));
	}

	@Test
	public void swappedNamedArgumentsCompareSameFormalParameter() throws Exception {
		DMLProgram program = compile(NAMED_SCRIPT);
		Assert.assertEquals(2, new FunctionCallGraph(program).getFunctionCalls(
			DMLProgram.constructFunctionKey(DMLProgram.DEFAULT_NAMESPACE, "outer")).size());
		Assert.assertEquals(1, FederatedLocalZeroCallsiteSpecializer.specialize(program));
		Assert.assertEquals(0, FederatedLocalZeroCallsiteSpecializer.specialize(program));
	}

	@Test
	public void equalZeroFormalWithSwappedNamedArgumentsDoesNotClone() throws Exception {
		DMLProgram program = compile(NAMED_SCRIPT.replace("K=Q,X=A", "K=A,X=Z"));
		Assert.assertEquals(2, new FunctionCallGraph(program).getFunctionCalls(
			DMLProgram.constructFunctionKey(DMLProgram.DEFAULT_NAMESPACE, "outer")).size());
		Assert.assertEquals(0, FederatedLocalZeroCallsiteSpecializer.specialize(program));
	}

	@Test
	public void defaultedArgumentsRetainFormalIdentity() throws Exception {
		String script = NAMED_SCRIPT.replace("matrix[double] K", "int K=1")
			.replace("Q=matrix(1,rows=4,cols=1);", "")
			.replace("outer(X=Z,K=Q)", "outer(X=Z)")
			.replace("outer(K=Q,X=A)", "outer(K=2,X=A)");
		DMLProgram program = compile(script);
		Assert.assertEquals(1, FederatedLocalZeroCallsiteSpecializer.specialize(program));
	}

	@Test
	public void diamondNestedGraphClonesSharedCalleeOnce() throws Exception {
		String script = "leaf=function(matrix[double] X) return(matrix[double] Y) {"
			+ " if(sum(X)>0) {Y=X+1;} else {Y=X+2;} }\n"
			+ "left=function(matrix[double] X) return(matrix[double] Y) {Y=leaf(X); Y=Y+X;}\n"
			+ "right=function(matrix[double] X) return(matrix[double] Y) {Y=leaf(X); Y=Y-X;}\n"
			+ "outer=function(matrix[double] X) return(matrix[double] Y) {"
			+ " A=left(X); B=right(X); Y=A+B;}\n"
			+ "Z=matrix(0,rows=4,cols=1); A=rand(rows=4,cols=1);"
			+ "L=outer(Z); R=outer(A); print(sum(L+R));\n";
		DMLProgram program = compile(script);
		Assert.assertEquals(1, FederatedLocalZeroCallsiteSpecializer.specialize(program));
		Assert.assertEquals(1, program.getFunctionStatementBlocks(DMLProgram.DEFAULT_NAMESPACE).keySet()
			.stream().filter(name -> name.startsWith("leaf__g009_local_zero_")).count());
	}

	@Test
	public void nestedMultiReturnBuiltinGetsDetachedOutputDescriptors() throws Exception {
		String script = "outer=function(matrix[double] X) return(matrix[double] Y) {"
			+ " [U,S,V]=svd(X); Y=U; }\n"
			+ "Z=matrix(0,rows=4,cols=4); A=rand(rows=4,cols=4);"
			+ "L=outer(Z); R=outer(A); print(sum(L+R));\n";
		DMLProgram program = compile(script);
		Assert.assertEquals(1, FederatedLocalZeroCallsiteSpecializer.specialize(program));
		FunctionStatementBlock original = program.getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, "outer");
		FunctionStatementBlock clone = program.getFunctionStatementBlocks(DMLProgram.DEFAULT_NAMESPACE).entrySet()
			.stream().filter(entry -> entry.getKey().startsWith("outer__g009_local_zero_"))
			.map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
		FunctionOp originalBuiltin = (FunctionOp) ((FunctionStatement) original.getStatement(0)).getBody()
			.get(0).getHops().get(0);
		FunctionOp clonedBuiltin = (FunctionOp) ((FunctionStatement) clone.getStatement(0)).getBody()
			.get(0).getHops().get(0);
		Assert.assertNotSame(originalBuiltin.getOutputs().get(0), clonedBuiltin.getOutputs().get(0));
		Assert.assertSame(clonedBuiltin.getInput().get(0), clonedBuiltin.getOutputs().get(0).getInput().get(0));
		Assert.assertEquals(clonedBuiltin.getOutputs().get(0).getHopID(),
			clonedBuiltin.getOutputs().get(0).getPlannerOriginHopID());
	}

	@Test
	public void equalZeroCallersDoNotCreateSpuriousClones() throws Exception {
		DMLProgram program = compile(SCRIPT.replace("R=outer(A)", "R=outer(Z)"));
		Assert.assertEquals(0, FederatedLocalZeroCallsiteSpecializer.specialize(program));
		Assert.assertTrue(program.getFunctionStatementBlocks(DMLProgram.DEFAULT_NAMESPACE).keySet().stream()
			.noneMatch(name -> name.contains("__g009_local_zero_")));
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
