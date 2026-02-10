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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class OracleFallbackFTypeTest {
	private static final long ROWS = 10;
	private static final long COLS = 10;
	private static final int BLOCKSIZE = 1000;

	@Test
	public void testFallbackConflictingConsumersReturnsNull() {
		DataOp left = matrixRead("left", ROWS, COLS);
		DataOp right = matrixRead("right", ROWS, COLS);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);

		AggUnaryOp rowAgg = new AggUnaryOp("rowAgg", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, plus);
		AggUnaryOp colAgg = new AggUnaryOp("colAgg", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Col, plus);
		assertEquals(2, plus.getParent().size());
		assertNotNull(rowAgg);
		assertNotNull(colAgg);

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		FType inferred = OracleUtils.inferFallbackFType(plus, Collections.emptyList(), oracle, null);
		assertNull("Expected no fallback FType for conflicting consumer constraints", inferred);
	}

	@Test
	public void testFallbackPrefersColForSingleRowShape() {
		DataOp left = matrixRead("left", 1, 10);
		DataOp right = matrixRead("right", 1, 10);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);

		List<FType> inputFTypes = Arrays.asList(FType.ROW, FType.COL);
		FType inferred = OracleUtils.inferFallbackFType(plus, inputFTypes, null, null);
		assertEquals("Expected COL for single-row shape with ROW/COL candidates", FType.COL, inferred);
	}

	@Test
	public void testTransientRewireSkipsReadInWriteInputDag() throws Exception {
		DataOp trInput = transientRead("X");
		LiteralOp one = new LiteralOp(1.0);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, trInput, one);
		DataOp tw = transientWrite("X", plus);

		DataOp trConsumer = transientRead("X");
		AggUnaryOp consumer = new AggUnaryOp("sum", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, trConsumer);

		Map<Long, List<Hop>> rewire = buildTransientRewireTable(Arrays.asList(tw, consumer));
		assertNotNull(rewire);
		List<Hop> twLinks = rewire.get(tw.getHopID());
		assertNotNull(twLinks);
		assertEquals(1, twLinks.size());
		assertEquals(trConsumer.getHopID(), twLinks.get(0).getHopID());

		List<Hop> trLinks = rewire.get(trConsumer.getHopID());
		assertNotNull(trLinks);
		assertEquals(1, trLinks.size());
		assertEquals(tw.getHopID(), trLinks.get(0).getHopID());

		assertFalse("Input TREAD should not be rewired as a consumer",
			rewire.containsKey(trInput.getHopID()));
	}

	@Test
	public void testTransientRewireSkipsMultipleWrites() throws Exception {
		DataOp tr1 = transientRead("X");
		DataOp tw1 = transientWrite("X", tr1);
		DataOp tr2 = transientRead("X");
		DataOp tw2 = transientWrite("X", tr2);

		Map<Long, List<Hop>> rewire = buildTransientRewireTable(Arrays.asList(tw1, tw2));
		assertTrue("Expected no rewire entries when multiple TWRITEs exist",
			rewire == null || rewire.isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testFedAllOracleInputUsesCpfoutHintWhenAvailable() throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		try {
			DataOp fedX = transientRead("Xfed");
			FederatedPlannerUtils.registerFedInitVar("Xfed");
			fedX.setForcedExecType(ExecType.FED);
			fedX.setFederatedOutput(FederatedOutput.FOUT);

			DataOp localA = transientRead("A");
			DataOp localB = transientRead("B");
			BinaryOp localCandidate = new BinaryOp("localCandidate", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, localA, localB);
			localCandidate.setDim1(ROWS);
			localCandidate.setDim2(COLS);

			BinaryOp parent = new BinaryOp("parent", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, fedX, localCandidate);
			parent.setDim1(ROWS);
			parent.setDim2(COLS);

			Map<Long, FType> memo = new java.util.HashMap<>();
			memo.put(fedX.getHopID(), FType.ROW);

			FederatedPlannerFedAll planner = new FederatedPlannerFedAll();
			Method method = FederatedPlannerFedAll.class.getDeclaredMethod(
				"collectInputFTypes", Hop.class, Map.class, Map.class, boolean.class);
			method.setAccessible(true);

			List<FType> base = (List<FType>) method.invoke(planner, parent, memo, null, false);
			List<FType> hinted = (List<FType>) method.invoke(planner, parent, memo, null, true);

			assertEquals("Expected base input FType to preserve existing FED input", FType.ROW, base.get(0));
			assertNull("Expected local input to be null without CP->FOUT hint injection", base.get(1));
			assertNotNull("Expected CP->FOUT-capable local input to receive inferred FType hint", hinted.get(1));
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	@Test
	public void testFunctionOutputFromMultiReturnBuiltinIsCpOnlyButKeepsLogicalFType() {
		DataOp input = matrixRead("X", ROWS, COLS);
		DataOp eigenValues = new DataOp("eigen_values", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		DataOp eigenVectors = new DataOp("eigen_vectors", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		new FunctionOp(FunctionType.MULTIRETURN_BUILTIN, ".builtinNS", "eigen",
			new String[] {"X"}, List.of(input), new String[] {"eigen_values", "eigen_vectors"},
			new java.util.ArrayList<>(List.of(eigenValues, eigenVectors)));

		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		OpCaps caps = oracle.decide(eigenValues, List.of(FType.ROW));
		assertNotNull(caps);
		assertEquals(ExecType.CP, caps.exec());
		assertEquals(ReasonCode.MISSING_FED_INSTRUCTION, caps.reason());

		OracleUtils.OracleDecision decision = OracleUtils.decideWithOracle(
			eigenValues, Privacy.PUBLIC, List.of(input), List.of(FType.ROW), oracle, null, null);
		assertNotNull(decision);
		assertEquals("FunOut should keep logical FType for downstream candidate reasoning",
			FType.ROW, decision.logicalFType());
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, List<Hop>> buildTransientRewireTable(List<Hop> roots) throws Exception {
		Method method = FederatedPlannerFedAll.class.getDeclaredMethod("buildTransientRewireTable", List.class);
		method.setAccessible(true);
		return (Map<Long, List<Hop>>) method.invoke(null, roots);
	}

	private static DataOp matrixRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, rows, cols, rows * cols, BLOCKSIZE);
	}

	private static DataOp transientRead(String name) {
		return matrixRead(name, ROWS, COLS);
	}

	private static DataOp transientWrite(String name, Hop input) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, input, OpOpData.TRANSIENTWRITE, null);
	}
}
