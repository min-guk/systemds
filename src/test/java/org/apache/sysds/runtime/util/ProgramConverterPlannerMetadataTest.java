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
package org.apache.sysds.runtime.util;

import static org.junit.Assert.assertEquals;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.junit.Test;

public class ProgramConverterPlannerMetadataTest {
	@Test
	public void parForInstructionClonePreservesPlannerProof() {
		Instruction original = InstructionParser.parseSingleInstruction(InstructionUtils.concatOperands(
			"CP", "-",
			InstructionUtils.concatOperandParts("run_index", DataType.SCALAR.name(), ValueType.INT64.name(), "false"),
			InstructionUtils.concatOperandParts("1", DataType.SCALAR.name(), ValueType.INT64.name(), "true"),
			InstructionUtils.concatOperandParts("out", DataType.SCALAR.name(), ValueType.INT64.name())));
		original.setLocation("kmeans.dml", 155, 155, 6, 39);
		original.setPlannerOriginHopID(461);
		original.setPlannerRecompileSignature("sig-461");
		original.setPlannerLoweringAuxiliaryKind("helper-kind");
		original.setPlannerRewriteReplacementKind("rewrite-kind");
		original.setPlannerAuditKey("audit-key-461");

		Instruction clone = ProgramConverter.cloneInstruction(original, 7, true, false);

		assertEquals(original.getFilename(), clone.getFilename());
		assertEquals(original.getBeginLine(), clone.getBeginLine());
		assertEquals(original.getPlannerOriginHopID(), clone.getPlannerOriginHopID());
		assertEquals(original.getPlannerRecompileSignature(), clone.getPlannerRecompileSignature());
		assertEquals(original.getPlannerLoweringAuxiliaryKind(), clone.getPlannerLoweringAuxiliaryKind());
		assertEquals(original.getPlannerRewriteReplacementKind(), clone.getPlannerRewriteReplacementKind());
		assertEquals(original.getPlannerAuditKey(), clone.getPlannerAuditKey());
	}
}
