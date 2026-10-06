/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.JointTuple;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class JointInputReachingDefinitionsTest {
	@Test
	public void oneBranchDecisionRetainsOnlyAaAndBbTuples() throws Exception {
		PlacementJointInputAnalysis analysis = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=matrix(1,2,2);B=matrix(2,2,2);}"
			+ "else{A=matrix(3,2,2);B=matrix(4,2,2);}C=A+B;print(sum(C));");
		List<JointTuple> tuples = consumerTuples(analysis, "C");

		Assert.assertEquals(tupleSignatures(tuples).toString(), 2, tuples.size());
		for(JointTuple tuple : tuples) {
			String left = tuple.inputs().get(0).source().occurrence().callSitePath();
			String right = tuple.inputs().get(1).source().occurrence().callSitePath();
			Assert.assertEquals("cross-arm tuple escaped projection: " + tuple,
				left.contains("branch-if"), right.contains("branch-if"));
		}
	}

	@Test
	public void independentBranchesRetainAllFourCombinations() throws Exception {
		PlacementJointInputAnalysis analysis = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "q=as.scalar(rand(rows=1,cols=1));if(p>0.5){A=matrix(1,2,2);}else{A=matrix(2,2,2);}"
			+ "if(q>0.5){B=matrix(3,2,2);}else{B=matrix(4,2,2);}C=A+B;print(sum(C));");

		Assert.assertEquals(tupleSignatures(consumerTuples(analysis, "C")).toString(), 4,
			consumerTuples(analysis, "C").size());
	}

	@Test
	public void sameBlockSequentialReadsKeepTheirObservedDefinitionVersions() throws Exception {
		DMLProgram program = new DMLProgram();
		DataOp initialA = transientWrite("A", new LiteralOp(1L), 1);
		DataOp firstRead = transientRead("A", 2);
		DataOp writeX = transientWrite("X", firstRead, 2);
		DataOp laterA = transientWrite("A", new LiteralOp(2L), 3);
		DataOp secondRead = transientRead("A", 4);
		DataOp writeB = transientWrite("B", secondRead, 4);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(initialA, writeX, laterA, writeB)));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		PlacementJointInputAnalysis analysis = PlacementJointInputAnalysis.analyze(program);
		List<Integer> reads = new ArrayList<>();
		for(int ordinal = 0; ordinal < analysis.occurrenceCount(); ordinal++)
			if(PlacementProgramFacts.isTransientRead(analysis.occurrenceHop(ordinal))
				&& "A".equals(analysis.occurrenceHop(ordinal).getName()))
				reads.add(ordinal);
		Assert.assertEquals("fixture must retain both A reads", 2, reads.size());
		List<JointTuple> tuples = analysis.tuplesForReads(reads);
		Assert.assertEquals(tupleSignatures(tuples).toString(), 1, tuples.size());
		Assert.assertNotEquals("the earlier read was rebound to the later definition",
			tuples.get(0).inputs().get(0).source().occurrenceOrdinal(),
			tuples.get(0).inputs().get(1).source().occurrenceOrdinal());
	}

	@Test
	public void unchangedArmAndRepeatedLoopBranchReachFiniteFixedPoint() throws Exception {
		PlacementJointInputAnalysis unchanged = analyze("p=as.scalar(rand(rows=1,cols=1));"
			+ "q=as.scalar(rand(rows=1,cols=1));A=matrix(0,2,2);B=matrix(0,2,2);"
			+ "if(p>0.5){if(q>0.5){A=matrix(1,2,2);B=matrix(1,2,2);}"
			+ "else{A=matrix(2,2,2);B=matrix(2,2,2);}}"
			+ "C=A+B;print(sum(C));");
		Assert.assertEquals(tupleSignatures(consumerTuples(unchanged, "C")).toString(), 3,
			consumerTuples(unchanged, "C").size());

		PlacementJointInputAnalysis loop = analyze("p=as.scalar(rand(rows=1,cols=1));i=1;"
			+ "A=matrix(0,2,2);B=matrix(0,2,2);while(i<3){"
			+ "if(p>0.5){A=matrix(1,2,2);B=matrix(1,2,2);}"
			+ "else{A=matrix(2,2,2);B=matrix(2,2,2);}i=i+1;}C=A+B;print(sum(C));");
		List<JointTuple> tuples = consumerTuples(loop, "C");
		Assert.assertEquals(tupleSignatures(tuples).toString(), 3, tuples.size());
		for(JointTuple tuple : tuples) {
			String left = tuple.inputs().get(0).source().occurrence().callSitePath();
			String right = tuple.inputs().get(1).source().occurrence().callSitePath();
			Assert.assertEquals("loop fixed point introduced a cross-arm tuple: " + tuple, left, right);
		}
	}

	@Test
	public void nonRecursiveFunctionReturnsKeepCallSiteAndExitCorrelation() throws Exception {
		PlacementJointInputAnalysis analysis = analyze("f=function(matrix[double] X,matrix[double] Y)"
			+ "return(matrix[double] A,matrix[double] B){T=X+Y;if(sum(T)>0){A=X;B=Y;}else{A=Y;B=X;}}"
			+ "X=matrix(1,2,2);Y=matrix(2,2,2);[A,B]=f(X,Y);[D,E]=f(X,Y);"
			+ "C=A+B;F=D+E;print(sum(C)+sum(F));");
		List<JointTuple> tuples = consumerTuples(analysis, "C");

		Assert.assertEquals(tupleSignatures(tuples).toString(), 2, tuples.size());
		for(JointTuple tuple : tuples) {
			Assert.assertEquals(PlacementJointInputAnalysis.SourceKind.FUNCTION_RETURN,
				tuple.inputs().get(0).source().kind());
			Assert.assertEquals(tuple.inputs().get(0).source().occurrenceOrdinal(),
				tuple.inputs().get(1).source().occurrenceOrdinal());
			Assert.assertTrue(tuple.inputs().get(0).source().callContext().contains("/call-"));
		}
		List<JointTuple> formalInputs = readTuples(analysis, "X", "Y", "function/");
		Assert.assertEquals("two call sites must retain separate formal bindings: "
			+ tupleSignatures(formalInputs), 2, formalInputs.size());
		for(JointTuple tuple : formalInputs)
			Assert.assertTrue(tuple.inputs().stream().allMatch(input ->
				input.source().kind() == PlacementJointInputAnalysis.SourceKind.FUNCTION_INPUT));
	}

	@Test
	public void formalNamesResolveDistinctCallerVariables() throws Exception {
		PlacementJointInputAnalysis analysis = analyze(
			"f=function(matrix[double] A,matrix[double] B) return(matrix[double] C){"
				+ "i=1;while(i<1){i=i+1;}C=A+B;}"
				+ "X=matrix(1,2,2);Y=matrix(2,2,2);C1=f(X,X);C2=f(Y,Y);"
				+ "print(sum(C1)+sum(C2));");
		List<JointTuple> formalInputs = readTuples(analysis, "A", "B", "function/");
		Assert.assertEquals(tupleSignatures(formalInputs).toString(), 2, formalInputs.size());
		for(JointTuple tuple : formalInputs) {
			Assert.assertEquals(PlacementJointInputAnalysis.SourceKind.FUNCTION_INPUT,
				tuple.inputs().get(0).source().kind());
			Assert.assertEquals(tuple.inputs().get(0).source().occurrenceOrdinal(),
				tuple.inputs().get(1).source().occurrenceOrdinal());
		}
	}

	@Test
	public void unrelatedBranchesDoNotConsumeTheRequestedDependencySlice() throws Exception {
		StringBuilder script = new StringBuilder("p=as.scalar(rand(rows=1,cols=1));");
		for(int index = 0; index < 15; index++)
			script.append("if(p>").append(index).append("){U").append(index)
				.append("=matrix(1,2,2);}else{U").append(index).append("=matrix(2,2,2);}");
		script.append("if(p>0.5){A=matrix(1,2,2);B=matrix(1,2,2);}else{"
			+ "A=matrix(2,2,2);B=matrix(2,2,2);}C=A+B;print(sum(C));");
		PlacementJointInputAnalysis analysis = analyze(script.toString());
		Assert.assertEquals(2, consumerTuples(analysis, "C").size());
	}

	@Test
	public void loopHeaderReadSeesEntryAndBackedgeDefinitions() throws Exception {
		PlacementJointInputAnalysis analysis = analyze("A=matrix(0,2,2);i=1;"
			+ "while(sum(A)<3){A=A+1;i=i+1;}print(sum(A));");
		int headerRead = -1;
		for(int ordinal = 0; ordinal < analysis.occurrenceCount(); ordinal++)
			if(PlacementProgramFacts.isTransientRead(analysis.occurrenceHop(ordinal))
				&& "A".equals(analysis.occurrenceHop(ordinal).getName())
				&& analysis.occurrenceBlock(ordinal) instanceof WhileStatementBlock) {
				headerRead = ordinal;
				break;
			}
		Assert.assertTrue("fixture must retain loop-header A read", headerRead >= 0);
		Assert.assertEquals(2, analysis.tuplesForReads(List.of(headerRead)).size());
	}

	@Test(timeout = 5000)
	public void loopedFunctionBindingHasFiniteStaticIdentity() throws Exception {
		PlacementJointInputAnalysis analysis = analyze(
			"f=function(matrix[double] X,matrix[double] Y) return(matrix[double] A,matrix[double] B){"
				+ "if(sum(X)>0){A=X;B=Y;}else{A=Y;B=X;}}"
				+ "A=matrix(1,2,2);B=matrix(2,2,2);i=1;"
				+ "while(i<3){[A,B]=f(A,B);i=i+1;}C=A+B;print(sum(C));");
		List<JointTuple> tuples = consumerTuples(analysis, "C");
		Assert.assertFalse(tuples.isEmpty());
		Assert.assertTrue("static call/return identities must remain finite: " + tuples.size(),
			tuples.size() <= 5);
	}

	private static PlacementJointInputAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return PlacementJointInputAnalysis.analyze(program);
	}

	private static List<JointTuple> consumerTuples(PlacementJointInputAnalysis analysis, String outputName) {
		return readTuples(analysis, "A", "B", "main");
	}

	private static List<JointTuple> readTuples(PlacementJointInputAnalysis analysis,
		String leftName, String rightName, String pathFragment) {
		int left = -1, right = -1;
		for(int a = 0; a < analysis.occurrenceCount(); a++) {
			if(!PlacementProgramFacts.isTransientRead(analysis.occurrenceHop(a))
				|| !leftName.equals(analysis.occurrenceHop(a).getName())
				|| !analysis.occurrenceKey(a).callSitePath().contains(pathFragment))
				continue;
			for(int b = 0; b < analysis.occurrenceCount(); b++) {
				if(!PlacementProgramFacts.isTransientRead(analysis.occurrenceHop(b))
					|| !rightName.equals(analysis.occurrenceHop(b).getName())
					|| !analysis.occurrenceKey(a).callSitePath().equals(analysis.occurrenceKey(b).callSitePath()))
					continue;
				if(Math.max(a, b) > Math.max(left, right)) { left = a; right = b; }
			}
		}
		if(left < 0)
			throw new AssertionError("No same-block reads for " + leftName + '/' + rightName);
		return analysis.tuplesForReads(List.of(left, right));
	}

	private static List<String> tupleSignatures(List<JointTuple> tuples) {
		return tuples.stream().map(JointTuple::stableKey).toList();
	}

	private static DataOp transientWrite(String name, org.apache.sysds.hops.Hop input, int line) {
		DataOp write = new DataOp(name, input.getDataType(), input.getValueType(), input,
			OpOpData.TRANSIENTWRITE, name);
		write.setBeginLine(line);
		return write;
	}

	private static DataOp transientRead(String name, int line) {
		DataOp read = new DataOp(name, DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, name, 0, 0, -1, -1);
		read.setBeginLine(line);
		return read;
	}
}
