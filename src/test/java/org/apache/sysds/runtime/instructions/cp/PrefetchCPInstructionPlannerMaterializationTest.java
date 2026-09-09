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
package org.apache.sysds.runtime.instructions.cp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;

public class PrefetchCPInstructionPlannerMaterializationTest {
	@Test
	public void plannerSelectedPrefetchPublishesADistinctLocalValue() {
		ExecutionContext ec = contextWithFederatedCachedInput();
		PrefetchCPInstruction instruction = instruction();
		instruction.setPlannerSyntheticActionKey("planner-local-action");

		instruction.processInstruction(ec);

		MatrixObject input = ec.getMatrixObject("X");
		MatrixObject output = ec.getMatrixObject("Y");
		assertNotSame("exact LOCAL action must not alias its federated source", input, output);
		assertTrue(input.isFederated(FType.FULL));
		assertFalse("exact LOCAL output must not retain a FederationMap", output.isFederated());
		MatrixBlock block = output.acquireReadAndRelease();
		assertEquals(2, block.getNumRows());
		assertEquals(3, block.getNumColumns());
		assertEquals(7.0, block.get(1, 2), 0.0);
	}

	@Test
	public void ordinaryPrefetchRetainsHistoricalAsynchronousAlias() {
		ExecutionContext ec = contextWithFederatedCachedInput();

		instruction().processInstruction(ec);

		assertSame(ec.getMatrixObject("X"), ec.getMatrixObject("Y"));
		assertTrue(ec.getMatrixObject("Y").isFederated(FType.FULL));
	}

	@Test
	public void plannerSelectedPrefetchMaterializesFederatedFrameLocally() {
		ExecutionContext ec = contextWithFederatedCachedFrameInput();
		PrefetchCPInstruction instruction = instruction(DataType.FRAME, ValueType.STRING);
		instruction.setPlannerSyntheticActionKey("planner-frame-local-action");

		instruction.processInstruction(ec);

		FrameObject input = ec.getFrameObject("X");
		FrameObject output = ec.getFrameObject("Y");
		assertNotSame("exact LOCAL frame action must not alias its federated source", input, output);
		assertTrue(input.isFederated(FType.FULL));
		assertFalse("exact LOCAL frame output must not retain a FederationMap", output.isFederated());
		FrameBlock block = output.acquireReadAndRelease();
		assertEquals(1, block.getNumRows());
		assertEquals("value", block.get(0, 0));
	}

	private static ExecutionContext contextWithFederatedCachedInput() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setAutoCreateVars(true);
		MatrixBlock block = new MatrixBlock(2, 3, false);
		block.allocateDenseBlock();
		block.set(1, 2, 7.0);
		MatrixObject input = ExecutionContext.createMatrixObject(block);
		input.setFedMapping(new FederationMap(17, List.of(), FType.FULL));
		ec.setVariable("X", input);
		ec.setVariable("Y", ExecutionContext.createMatrixObject(new MatrixBlock()));
		return ec;
	}

	private static ExecutionContext contextWithFederatedCachedFrameInput() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setAutoCreateVars(true);
		FrameBlock block = new FrameBlock(new ValueType[] {ValueType.STRING});
		block.appendRow(new Object[] {"value"});
		FrameObject input = ExecutionContext.createFrameObject(block);
		input.setFedMapping(new FederationMap(19, List.of(), FType.FULL));
		ec.setVariable("X", input);
		ec.setVariable("Y", ExecutionContext.createFrameObject(new FrameBlock(
			new ValueType[] {ValueType.STRING})));
		return ec;
	}

	private static PrefetchCPInstruction instruction() {
		return instruction(DataType.MATRIX, ValueType.FP64);
	}

	private static PrefetchCPInstruction instruction(DataType dataType, ValueType valueType) {
		String instruction = InstructionUtils.concatOperands("CP", "prefetch",
			InstructionUtils.concatOperandParts("X", dataType.name(), valueType.name(), "false"),
			InstructionUtils.concatOperandParts("Y", dataType.name(), valueType.name()), "1");
		return PrefetchCPInstruction.parseInstruction(instruction);
	}
}
