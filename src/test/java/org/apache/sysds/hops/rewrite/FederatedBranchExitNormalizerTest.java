/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.rewrite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class FederatedBranchExitNormalizerTest {
	@Test
	public void addsRealCarriersToBothFunctionBranchesAndIsIdempotent() throws Exception {
		DMLProgram program = compile("""
			f=function(matrix[double] X, boolean flag) return(matrix[double] R) {
			  Y=colSums(X);
			  if(flag) { Y=Y+1; }
			  k=1; while(k<2) { k=k+1; }
			  R=Y;
			}
			X=matrix(1,rows=8,cols=4);
			R=f(X,1==1);
			print(sum(R));
			""");
		IfStatement branch = onlyFunctionBranch(program);
		Assert.assertEquals(0, branch.getElseBody().size());

		FederatedBranchExitNormalizer.normalize(program);
		assertCarrier(branch.getIfBody(), "Y", 1, -1);
		assertCarrier(branch.getElseBody(), "Y", 1, -1);
		DataOp thenRead = carrierRead(branch.getIfBody());
		DataOp elseRead = carrierRead(branch.getElseBody());
		Assert.assertEquals(thenRead.getBeginLine(), elseRead.getBeginLine());
		Assert.assertEquals(thenRead.getBeginColumn(), elseRead.getBeginColumn());
		Assert.assertEquals(thenRead.getEndLine(), elseRead.getEndLine());
		Assert.assertEquals(thenRead.getEndColumn(), elseRead.getEndColumn());
		Assert.assertFalse(thenRead.getPlannerBranchNormalizationKey().equals(
			elseRead.getPlannerBranchNormalizationKey()));
		Assert.assertFalse(FederatedPlannerUtils.plannerRecompileSignature(thenRead).equals(
			FederatedPlannerUtils.plannerRecompileSignature(elseRead)));
		int thenSize = branch.getIfBody().size();
		int elseSize = branch.getElseBody().size();

		FederatedBranchExitNormalizer.normalize(program);
		Assert.assertEquals(thenSize, branch.getIfBody().size());
		Assert.assertEquals(elseSize, branch.getElseBody().size());
	}

	@Test
	public void carriesOnlyLiveOutMatricesAndRecursesThroughLoops() throws Exception {
		DMLProgram program = compile("""
			X=matrix(1,rows=5,cols=3);
			Y=colSums(X);
			i=1;
			while(i<2) {
			  if(i==1) { Y=Y+1; dead=Y+2; s=7; }
			  else { Y=Y+3; dead=Y+4; s=9; }
			  i=i+1;
			}
			print(sum(Y));
			""");
		FederatedBranchExitNormalizer.normalize(program);
		IfStatement branch = nestedMainBranch(program);

		assertCarrier(branch.getIfBody(), "Y", 1, 3);
		assertCarrier(branch.getElseBody(), "Y", 1, 3);
		for(ArrayList<StatementBlock> body : List.of(branch.getIfBody(), branch.getElseBody())) {
			List<Hop> roots = body.get(body.size() - 1).getHops();
			Assert.assertEquals(1, roots.size());
			Assert.assertFalse(roots.stream().anyMatch(hop -> "dead".equals(hop.getName())));
			Assert.assertFalse(roots.stream().anyMatch(hop -> "s".equals(hop.getName())));
		}
	}

	@Test
	public void markerSurvivesHopClone() throws Exception {
		DataOp read = new DataOp("Y", org.apache.sysds.common.Types.DataType.MATRIX,
			org.apache.sysds.common.Types.ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 2, 3, -1, 1024);
		DataOp write = HopRewriteUtils.createTransientWrite("Y", read);
		read.setPlannerBranchNormalization("function/f/0/branch-if/Y");
		write.setPlannerBranchNormalization("function/f/0/branch-if/Y");

		DataOp clone = (DataOp) write.clone();
		Assert.assertTrue(clone.isPlannerBranchNormalization());
		Assert.assertEquals(write.getPlannerBranchNormalizationKey(),
			clone.getPlannerBranchNormalizationKey());
	}

	@Test
	public void doesNotCreateCarrierForUndefinedBranchValue() throws Exception {
		DMLProgram program = compile("""
			f=function(matrix[double] X, boolean flag) return(matrix[double] R) {
			  if(flag) { early=TRUE; }
			  else { R=matrix(1,rows=nrow(X),cols=ncol(X)); }
			}
			X=matrix(1,rows=4,cols=128);
			R=f(X,FALSE);
			print(sum(R));
			""");
		IfStatement branch = onlyFunctionBranch(program);

		FederatedBranchExitNormalizer.normalize(program);

		Assert.assertFalse("a carrier must not invent a read of an undefined branch value",
			hasCarrier(branch.getIfBody(), "R"));
		Assert.assertTrue("the branch that defines the value still needs its placement carrier",
			hasCarrier(branch.getElseBody(), "R"));
	}

	private static void assertCarrier(ArrayList<StatementBlock> body, String name,
		long rows, long columns) {
		StatementBlock carrier = body.get(body.size() - 1);
		Assert.assertTrue(carrier.variablesRead().containsVariable(name));
		Assert.assertTrue(carrier.variablesUpdated().containsVariable(name));
		Assert.assertEquals(1, carrier.getHops().size());
		Hop root = carrier.getHops().get(0);
		Assert.assertTrue(root instanceof DataOp);
		DataOp write = (DataOp) root;
		Assert.assertEquals(OpOpData.TRANSIENTWRITE, write.getOp());
		Assert.assertTrue(write.isPlannerBranchNormalization());
		Assert.assertEquals(name, write.getName());
		Assert.assertEquals(rows, write.getDim1());
		Assert.assertEquals(columns, write.getDim2());
		Assert.assertEquals(1, write.getInput().size());
		Assert.assertTrue(write.getInput(0) instanceof DataOp);
		DataOp read = (DataOp) write.getInput(0);
		Assert.assertEquals(OpOpData.TRANSIENTREAD, read.getOp());
		Assert.assertTrue(read.isPlannerBranchNormalization());
		Assert.assertEquals(write.getPlannerBranchNormalizationKey(),
			read.getPlannerBranchNormalizationKey());
		Assert.assertEquals(name, read.getName());
	}

	private static DataOp carrierRead(ArrayList<StatementBlock> body) {
		DataOp write = (DataOp) body.get(body.size() - 1).getHops().get(0);
		return (DataOp) write.getInput(0);
	}

	private static boolean hasCarrier(ArrayList<StatementBlock> body, String name) {
		if(body.isEmpty())
			return false;
		List<Hop> roots = body.get(body.size() - 1).getHops();
		return roots != null && roots.stream().anyMatch(hop -> hop instanceof DataOp data
			&& data.getOp() == OpOpData.TRANSIENTWRITE && data.isPlannerBranchNormalization()
			&& name.equals(data.getName()));
	}

	private static IfStatement onlyFunctionBranch(DMLProgram program) {
		FunctionStatement function = (FunctionStatement) program.getNamedNSFunctionStatementBlocks()
			.values().iterator().next().getStatement(0);
		return function.getBody().stream().filter(IfStatementBlock.class::isInstance)
			.map(block -> (IfStatement) block.getStatement(0)).findFirst().orElseThrow();
	}

	private static IfStatement nestedMainBranch(DMLProgram program) {
		return program.getStatementBlocks().stream()
			.filter(org.apache.sysds.parser.WhileStatementBlock.class::isInstance)
			.map(block -> (org.apache.sysds.parser.WhileStatement) block.getStatement(0))
			.flatMap(loop -> loop.getBody().stream())
			.filter(IfStatementBlock.class::isInstance)
			.map(block -> (IfStatement) block.getStatement(0)).findFirst().orElseThrow();
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}
