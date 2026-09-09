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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DataIdentifier;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class FederatedPlannerDpRewireTransTableTest {
	private static final int ROWS = 10;
	private static final int COLS = 10;
	private static final int BLOCKSIZE = 1000;

	@Test
	public void testFunctionInputBindingsShadowCallerScopeAndPreserveDistinctProducers() {
		DataOp callerS = HopRewriteUtils.createTransientWrite("S", transientRead("callerS", ROWS, COLS));
		DataOp branchS1 = HopRewriteUtils.createTransientWrite("S", transientRead("branchS1", ROWS, COLS));
		DataOp branchS2 = HopRewriteUtils.createTransientWrite("S", transientRead("branchS2", ROWS, COLS));
		DataOp actualS = transientRead("S", ROWS, COLS);

		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		rewireTable.put(actualS.getHopID(), new ArrayList<>(List.of(branchS1, branchS2, branchS1)));
		Map<String, List<Hop>> formerTransTable = new HashMap<>();
		formerTransTable.put("S", new ArrayList<>(List.of(callerS)));

		TransTableRewireUtils.mapFunctionInputsToFormerTransTable(
			new String[] {"S"}, List.of(actualS), rewireTable, formerTransTable);

		List<Hop> formalBinding = formerTransTable.get("S");
		assertEquals("A formal parameter must replace the same-named caller binding", 2, formalBinding.size());
		assertSame("Distinct branch producers must retain their original order", branchS1, formalBinding.get(0));
		assertSame("Distinct branch producers must remain available to the function body", branchS2,
			formalBinding.get(1));

		DataOp formalRead = transientRead("S", ROWS, COLS);
		TransTableRewireUtils.registerTransReadMapping(formalRead.getHopID(), formalBinding, rewireTable);
		TransTableRewireUtils.registerTransWriteLinks(formalRead, formalBinding, rewireTable, new HashSet<>());
		assertEquals("The formal read must retain one forward edge per distinct producer", 2,
			rewireTable.get(formalRead.getHopID()).size());
		assertEquals("The first producer must retain one reverse edge", 1,
			rewireTable.get(branchS1.getHopID()).size());
		assertSame(formalRead, rewireTable.get(branchS1.getHopID()).get(0));
		assertEquals("The second producer must retain one reverse edge", 1,
			rewireTable.get(branchS2.getHopID()).size());
		assertSame(formalRead, rewireTable.get(branchS2.getHopID()).get(0));
	}

	@Test
	public void testDpRewireTransHopPrefersDominatingTransientWriteOverStaleOuterMapping() throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp staleFedSource = federatedRead("X", ROWS, COLS);
			DataOp dominatingTWrite = HopRewriteUtils.createTransientWrite("X", transientRead("Xin", ROWS, COLS));
			dominatingTWrite.setBeginLine(10);
			DataOp tRead = transientRead("X", ROWS, COLS);
			tRead.setBeginLine(20);

			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			rewireTable.put(tRead.getHopID(), new ArrayList<>(List.of(staleFedSource)));

			Map<String, List<Hop>> innerTransTable = new HashMap<>();
			innerTransTable.put("X", new ArrayList<>(List.of(dominatingTWrite)));

			Class<?> loopCtxClass = Class.forName(
				FederatedPlannerDpRewireTransTable.class.getName() + "$LoopAnalysisContext");
			Method method = FederatedPlannerDpRewireTransTable.class.getDeclaredMethod("rewireTransHop",
				Hop.class, Map.class, List.class, Map.class, Map.class, Set.class, Set.class, loopCtxClass);
			method.setAccessible(true);
			method.invoke(null, tRead, rewireTable, new ArrayList<Map<String, List<Hop>>>(), null, innerTransTable,
				new HashSet<Long>(), new HashSet<Long>(), null);

			List<Hop> resolved = rewireTable.get(tRead.getHopID());
			assertNotNull("DP rewire should keep a resolved transient-read mapping", resolved);
			assertEquals("DP transient-read rewiring should prefer the dominating local TWrite",
				dominatingTWrite, resolved.get(0));
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testCollectCallerUnconsumedFunctionOutputHopsUsesOnlyTrailingBodyOutputs() throws Exception {
		FunctionOp fop = new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, ".builtinNS", "m_test",
			new String[] {"X"}, List.of(transientRead("Xin", ROWS, COLS)),
			new String[] {"CallerA", "CallerB"},
			new ArrayList<>(List.of(transientRead("CallerA", ROWS, COLS), transientRead("CallerB", ROWS, COLS))));
		FunctionStatement functionStatement = new FunctionStatement();
		functionStatement.setOutputParams(new ArrayList<>(List.of(
			new DataIdentifier("BodyA"),
			new DataIdentifier("BodyB"),
			new DataIdentifier("BodyC"),
			new DataIdentifier("BodyD"))));
		FunctionStatementBlock fsb = new FunctionStatementBlock();
		fsb.addStatement(functionStatement);

		DataOp bodyA = HopRewriteUtils.createTransientWrite("BodyA", transientRead("Ain", ROWS, COLS));
		DataOp bodyB = HopRewriteUtils.createTransientWrite("BodyB", transientRead("Bin", ROWS, COLS));
		DataOp bodyC = HopRewriteUtils.createTransientWrite("BodyC", transientRead("Cin", ROWS, COLS));
		DataOp bodyD = HopRewriteUtils.createTransientWrite("BodyD", transientRead("Din", ROWS, COLS));
		Map<String, List<Hop>> functionTransTable = new HashMap<>();
		functionTransTable.put("BodyA", List.of(bodyA));
		functionTransTable.put("BodyB", List.of(bodyB));
		functionTransTable.put("BodyC", List.of(bodyC));
		functionTransTable.put("BodyD", List.of(bodyD));

		Method helper = FederatedPlannerDpRewireTransTable.class.getDeclaredMethod(
			"collectCallerUnconsumedFunctionOutputHops", FunctionOp.class, FunctionStatementBlock.class, Map.class);
		helper.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<Hop> trailingOutputs = (List<Hop>) helper.invoke(null, fop, fsb, functionTransTable);

		assertEquals("Only caller-unconsumed trailing outputs should be collected", 2, trailingOutputs.size());
		assertEquals(bodyC, trailingOutputs.get(0));
		assertEquals(bodyD, trailingOutputs.get(1));
	}

	@Test
	public void testWhileLoopWeightAccountsForFloorDividedInductionBound() {
		Map<String, List<Hop>> transTable = new HashMap<>();
		transTable.put("it", List.of(transientScalarWrite("it", new LiteralOp(0L))));
		transTable.put("max_iter", List.of(transientScalarWrite("max_iter", new LiteralOp(10L))));

		DataOp predIt = transientScalarRead("it");
		BinaryOp divided = new BinaryOp("it_div_2", DataType.SCALAR, ValueType.FP64,
			OpOp2.DIV, predIt, new LiteralOp(2L));
		UnaryOp castDivided = new UnaryOp("as_int_it_div_2", DataType.SCALAR,
			ValueType.INT64, OpOp1.CAST_AS_INT, divided);
		BinaryOp predicate = new BinaryOp("while_pred", DataType.SCALAR,
			ValueType.BOOLEAN, OpOp2.LESS, castDivided, transientScalarRead("max_iter"));

		DataOp bodyIt = transientScalarRead("it");
		BinaryOp increment = new BinaryOp("it_plus_1", DataType.SCALAR, ValueType.INT64,
			OpOp2.PLUS, bodyIt, new LiteralOp(1L));
		StatementBlock body = new StatementBlock();
		body.setHops(new ArrayList<>(List.of(transientScalarWrite("it", increment))));

		WhileStatement whileStatement = new WhileStatement();
		whileStatement.addStatementBlock(body);
		WhileStatementBlock whileBlock = new WhileStatementBlock();
		whileBlock.setPredicateHops(predicate);
		whileBlock.addStatement(whileStatement);

		assertEquals("floor-divided induction predicates should price all updates",
			20.0, RewireConstants.estimateWhileLoopWeight(whileBlock, List.of(transTable)), 0.0);
	}

	@Test
	public void testWhileLoopWeightTreatsInductionBoundAsCapWhenConvergenceCanExitEarly() {
		Map<String, List<Hop>> transTable = new HashMap<>();
		transTable.put("it", List.of(transientScalarWrite("it", new LiteralOp(0L))));
		transTable.put("max_iter", List.of(transientScalarWrite("max_iter", new LiteralOp(2100L))));

		BinaryOp inductionGuard = new BinaryOp("iteration_cap", DataType.SCALAR,
			ValueType.BOOLEAN, OpOp2.LESS, transientScalarRead("it"), transientScalarRead("max_iter"));
		DataOp convergenceGuard = new DataOp("continue_training", DataType.SCALAR,
			ValueType.BOOLEAN, OpOpData.TRANSIENTREAD, null, 0, 0, 0, BLOCKSIZE);
		BinaryOp predicate = new BinaryOp("while_pred", DataType.SCALAR,
			ValueType.BOOLEAN, OpOp2.AND, inductionGuard, convergenceGuard);

		BinaryOp increment = new BinaryOp("it_plus_1", DataType.SCALAR, ValueType.INT64,
			OpOp2.PLUS, transientScalarRead("it"), new LiteralOp(1L));
		StatementBlock body = new StatementBlock();
		body.setHops(new ArrayList<>(List.of(transientScalarWrite("it", increment))));

		WhileStatement whileStatement = new WhileStatement();
		whileStatement.addStatementBlock(body);
		WhileStatementBlock whileBlock = new WhileStatementBlock();
		whileBlock.setPredicateHops(new DataOp("__pred", DataType.SCALAR,
			ValueType.BOOLEAN, predicate, OpOpData.TRANSIENTWRITE, null));
		whileBlock.addStatement(whileStatement);

		assertEquals("a wrapped data-dependent predicate must retain a sublinear estimate of its hard cap",
			Math.sqrt(2100.0),
			RewireConstants.estimateWhileLoopWeight(whileBlock, List.of(transTable)), 0.0);
	}

	@Test
	public void testWhileLoopWeightUsesTightestConjoinedInductionBound() {
		Map<String, List<Hop>> transTable = new HashMap<>();
		transTable.put("i", List.of(transientScalarWrite("i", new LiteralOp(0L))));
		transTable.put("j", List.of(transientScalarWrite("j", new LiteralOp(0L))));
		transTable.put("i_cap", List.of(transientScalarWrite("i_cap", new LiteralOp(20L))));
		transTable.put("j_cap", List.of(transientScalarWrite("j_cap", new LiteralOp(7L))));

		BinaryOp iGuard = new BinaryOp("i_guard", DataType.SCALAR, ValueType.BOOLEAN,
			OpOp2.LESS, transientScalarRead("i"), transientScalarRead("i_cap"));
		BinaryOp jGuard = new BinaryOp("j_guard", DataType.SCALAR, ValueType.BOOLEAN,
			OpOp2.LESS, transientScalarRead("j"), transientScalarRead("j_cap"));
		BinaryOp predicate = new BinaryOp("while_pred", DataType.SCALAR,
			ValueType.BOOLEAN, OpOp2.AND, iGuard, jGuard);

		BinaryOp incrementI = new BinaryOp("i_plus_1", DataType.SCALAR, ValueType.INT64,
			OpOp2.PLUS, transientScalarRead("i"), new LiteralOp(1L));
		BinaryOp incrementJ = new BinaryOp("j_plus_1", DataType.SCALAR, ValueType.INT64,
			OpOp2.PLUS, transientScalarRead("j"), new LiteralOp(1L));
		StatementBlock body = new StatementBlock();
		body.setHops(new ArrayList<>(List.of(
			transientScalarWrite("i", incrementI), transientScalarWrite("j", incrementJ))));

		WhileStatement whileStatement = new WhileStatement();
		whileStatement.addStatementBlock(body);
		WhileStatementBlock whileBlock = new WhileStatementBlock();
		whileBlock.setPredicateHops(predicate);
		whileBlock.addStatement(whileStatement);

		assertEquals("a conjunction of exact counters exits at its tightest bound",
			7.0, RewireConstants.estimateWhileLoopWeight(whileBlock, List.of(transTable)), 0.0);
	}

	private static DataOp transientRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, rows, cols, rows * cols, BLOCKSIZE);
	}

	private static DataOp transientScalarRead(String name) {
		return new DataOp(name, DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, null, 0, 0, 0, BLOCKSIZE);
	}

	private static DataOp transientScalarWrite(String name, Hop input) {
		return new DataOp(name, DataType.SCALAR, ValueType.INT64, input,
			OpOpData.TRANSIENTWRITE, null);
	}

	private static DataOp federatedRead(String name, long rows, long cols) {
		FederatedPlannerUtils.registerFedInitVar(name);
		DataOp op = transientRead(name, rows, cols);
		op.setForcedExecType(ExecType.FED);
		op.setFederatedOutput(FederatedOutput.FOUT);
		return op;
	}
}
