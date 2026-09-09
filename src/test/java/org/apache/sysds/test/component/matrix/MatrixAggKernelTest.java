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

package org.apache.sysds.test.component.matrix;

import static org.junit.Assert.assertEquals;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.matrix.data.LibMatrixAgg;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.operators.AggregateUnaryOperator;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Test;

public class MatrixAggKernelTest {
	private static final int MIN_PAR = 2*(int)LibMatrixAgg.PAR_NUMCELL_THRESHOLD1;
	private static final int MIN_PAR_SQRT = (int)Math.sqrt(MIN_PAR);
	private static final int K = InfrastructureAnalyzer.getLocalParallelism();
	
	@Test
	public void testDenseKahanSum() {
		testMatrixAggregation(Opcodes.UAKP.toString(), Opcodes.UARKP.toString(), Opcodes.UACKP.toString(), 0.95);
	}
	
	@Test
	public void testSparseKahanSum() {
		testMatrixAggregation(Opcodes.UAKP.toString(), Opcodes.UARKP.toString(), Opcodes.UACKP.toString(), 0.1);
	}
	
	@Test
	public void testDenseSum() {
		testMatrixAggregation(Opcodes.UAP.toString(), Opcodes.UARP.toString(), Opcodes.UACP.toString(), 0.95);
	}
	
	@Test
	public void testSparseSum() {
		testMatrixAggregation(Opcodes.UAP.toString(), Opcodes.UARP.toString(), Opcodes.UACP.toString(), 0.1);
	}
	
	@Test
	public void testDenseSqSum() {
		testMatrixAggregation(Opcodes.UASQKP.toString(), Opcodes.UARSQKP.toString(), Opcodes.UACSQKP.toString(), 0.95);
	}
	
	@Test
	public void testSparseSqSum() {
		testMatrixAggregation(Opcodes.UASQKP.toString(), Opcodes.UARSQKP.toString(), Opcodes.UACSQKP.toString(), 0.1);
	}
	
	@Test
	public void testDenseMean() {
		testMatrixAggregation(Opcodes.UAMEAN.toString(), Opcodes.UARMEAN.toString(), Opcodes.UACMEAN.toString(), 0.95);
	}
	
	@Test
	public void testSparseMean() {
		testMatrixAggregation(Opcodes.UAMEAN.toString(), Opcodes.UARMEAN.toString(), Opcodes.UACMEAN.toString(), 0.1);
	}
	
	@Test
	public void testDenseVar() {
		testMatrixAggregation(Opcodes.UAVAR.toString(), Opcodes.UARVAR.toString(), Opcodes.UACVAR.toString(), 0.95);
	}
	
	@Test
	public void testSparseVar() {
		testMatrixAggregation(Opcodes.UAVAR.toString(), Opcodes.UARVAR.toString(), Opcodes.UACVAR.toString(), 0.1);
	}
	
	@Test
	public void testDenseMin() {
		testMatrixAggregation(Opcodes.UAMIN.toString(), Opcodes.UARMIN.toString(), Opcodes.UACMIN.toString(), 0.95);
	}
	
	@Test
	public void testSparseMin() {
		testMatrixAggregation(Opcodes.UAMIN.toString(), Opcodes.UARMIN.toString(), Opcodes.UACMIN.toString(), 0.1);
	}
	
	@Test
	public void testDenseMax() {
		testMatrixAggregation(Opcodes.UAMAX.toString(), Opcodes.UARMAX.toString(), Opcodes.UACMAX.toString(), 0.95);
	}
	
	@Test
	public void testSparseMax() {
		testMatrixAggregation(Opcodes.UAMAX.toString(), Opcodes.UARMAX.toString(), Opcodes.UACMAX.toString(), 0.1);
	}

	@Test
	public void testDenseTwoOutputRowIndexMax() {
		testTwoOutputRowIndexAggregation(Opcodes.UARIMAX.toString());
	}

	@Test
	public void testDenseTwoOutputRowIndexMin() {
		testTwoOutputRowIndexAggregation(Opcodes.UARIMIN.toString());
	}

	private void testMatrixAggregation(String opcode1, String opcode2, String opcode3, double sp) {
		testMatrixAggregation(getOp(opcode1,1), getOp(opcode1,K), MIN_PAR, 1, sp);
		testMatrixAggregation(getOp(opcode2,1), getOp(opcode2,K), 1, MIN_PAR, sp);
		testMatrixAggregation(getOp(opcode3,1), getOp(opcode3,K), MIN_PAR, 1, sp);
		testMatrixAggregation(getOp(opcode1,1), getOp(opcode1,K), MIN_PAR_SQRT, MIN_PAR_SQRT, sp);
		testMatrixAggregation(getOp(opcode2,1), getOp(opcode2,K), MIN_PAR_SQRT, MIN_PAR_SQRT, sp);
		testMatrixAggregation(getOp(opcode3,1), getOp(opcode3,K), MIN_PAR_SQRT, MIN_PAR_SQRT, sp);
	}

	private void testMatrixAggregation(AggregateUnaryOperator uaop1,
		AggregateUnaryOperator uaopk, int n, int m, double sp)
	{
		MatrixBlock mb1 = MatrixBlock.randOperations(n, m, sp, 0, 0.1, "uniform", 7);
		//run single- and multi-threaded kernels and compare
		MatrixBlock ret1 = mb1.aggregateUnaryOperations(uaop1);
		MatrixBlock ret2 = mb1.aggregateUnaryOperations(uaopk);
		TestUtils.compareMatrices(ret1, ret2, 1e-8);
	}

	private void testTwoOutputRowIndexAggregation(String opcode) {
		MatrixBlock input = MatrixBlock.randOperations(MIN_PAR, 4, 1, -1, 1, "uniform", 7);
		AggregateUnaryOperator sequential = InstructionUtils.parseAggregateUnaryRowIndexOperator(opcode, 2, 1);
		AggregateUnaryOperator parallel = InstructionUtils.parseAggregateUnaryRowIndexOperator(opcode, 2, 8);

		MatrixBlock expected = input.aggregateUnaryOperations(sequential);
		MatrixBlock actual = input.aggregateUnaryOperations(parallel);

		assertEquals(2, expected.getNumColumns());
		assertEquals(2, actual.getNumColumns());
		TestUtils.compareMatrices(expected, actual, 0);
		assertRowIndexResult(input, actual, 0, opcode.equalsIgnoreCase(Opcodes.UARIMAX.toString()));
		assertRowIndexResult(input, actual, MIN_PAR / 2, opcode.equalsIgnoreCase(Opcodes.UARIMAX.toString()));
		assertRowIndexResult(input, actual, MIN_PAR - 1, opcode.equalsIgnoreCase(Opcodes.UARIMAX.toString()));
	}

	private void assertRowIndexResult(MatrixBlock input, MatrixBlock result, int row, boolean max) {
		int expectedIndex = 0;
		double expectedValue = input.get(row, 0);
		for(int col = 1; col < input.getNumColumns(); col++) {
			double value = input.get(row, col);
			if(max ? value > expectedValue : value < expectedValue) {
				expectedIndex = col;
				expectedValue = value;
			}
		}
		assertEquals(expectedIndex + 1, result.get(row, 0), 0);
		assertEquals(expectedValue, result.get(row, 1), 0);
	}
	
	private AggregateUnaryOperator getOp(String opcode, int threads) {
		return InstructionUtils.parseBasicAggregateUnaryOperator(opcode, threads);
	}
}
