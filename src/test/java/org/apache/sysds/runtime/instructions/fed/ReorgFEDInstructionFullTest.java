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

package org.apache.sysds.runtime.instructions.fed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;

public class ReorgFEDInstructionFullTest {
	@Test
	public void transposeSupportsSingleRangeFullInputAndPreservesFullOutput() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setAutoCreateVars(true);
		MatrixBlock block = new MatrixBlock(2, 3, false);
		block.allocateDenseBlock();
		block.set(0, 1, 7.0);
		MatrixObject input = ExecutionContext.createMatrixObject(block);
		long inputId = FederationUtils.getNextFedDataID();
		input.setFedMapping(new FederationMap(inputId, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {2, 3}),
			new FederatedLocalData(inputId, input))), FType.FULL));
		ec.setVariable("X", input);
		ec.setVariable("Y", ExecutionContext.createMatrixObject(new MatrixBlock()));

		String instruction = InstructionUtils.concatOperands("FED", "r'",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			"1", "FOUT");
		ReorgFEDInstruction.parseInstruction(instruction).processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("Y");
		assertTrue(output.isFederated(FType.FULL));
		assertEquals(1, output.getFedMapping().getSize());
		assertEquals(3, output.getNumRows());
		assertEquals(2, output.getNumColumns());
		assertArrayEquals(new long[] {0, 0}, output.getFedMapping().getFederatedRanges()[0].getBeginDims());
		assertArrayEquals(new long[] {3, 2}, output.getFedMapping().getFederatedRanges()[0].getEndDims());
	}
}
