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

import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDFoutInstruction;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;

public class FEDFoutInstructionBroadcastFallbackTest {
	private static class NoOpFederationMap extends FederationMap {
		public NoOpFederationMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap, FType type) {
			super(id, fedMap, type);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... fr) {
			return (Future<FederatedResponse>[]) new Future<?>[0];
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] frSlices, FederatedRequest... fr) {
			return (Future<FederatedResponse>[]) new Future<?>[0];
		}
	}

	@Test
	public void testUndersizedRowHintIsRejectedInsteadOfChangedToBroadcast() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());

		MatrixObject in = localMatrix("X", 1, 10);
		MatrixObject anchor = federatedAnchor("A", 4);
		MatrixObject out = new MatrixObject(ValueType.FP64, "out",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024)));

		ec.setVariable("X", in);
		ec.setVariable("A", anchor);
		ec.setVariable("Y", out);

		String inst = InstructionUtils.concatOperands(
			"FED", "fed_fout",
			InstructionUtils.concatOperandParts("X", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("A", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			"ROW");
		FEDFoutInstruction parsed = FEDFoutInstruction.parseInstruction(inst);
		assertThrows("The runtime must not replace the planner-selected ROW placement with BROADCAST",
			RuntimeException.class, () -> parsed.processInstruction(ec));
	}

	@Test
	public void testUndersizedColHintIsRejectedInsteadOfChangedToBroadcast() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());

		MatrixObject in = localMatrix("X", 10, 1);
		MatrixObject anchor = federatedAnchor("A", 4);
		MatrixObject out = new MatrixObject(ValueType.FP64, "out",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024)));

		ec.setVariable("X", in);
		ec.setVariable("A", anchor);
		ec.setVariable("Y", out);

		String inst = InstructionUtils.concatOperands(
			"FED", "fed_fout",
			InstructionUtils.concatOperandParts("X", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("A", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			"COL");
		FEDFoutInstruction parsed = FEDFoutInstruction.parseInstruction(inst);
		assertThrows("The runtime must not replace the planner-selected COL placement with BROADCAST",
			RuntimeException.class, () -> parsed.processInstruction(ec));
	}

	@Test
	public void testFederatedInputRepartitionRequiresExplicitPlannerMaterialization() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject in = localMatrix("X", 2, 3);
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {2, 3});
		FederatedData data = new FederatedData(Types.DataType.MATRIX,
			new InetSocketAddress("localhost", 14000), "dummy");
		in.setFedMapping(new NoOpFederationMap(91, List.of(Pair.of(range, data)), FType.ROW));

		ec.setVariable("X", in);
		ec.setVariable("A", federatedAnchor("A", 1));
		ec.setVariable("Y", new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024))));

		String inst = InstructionUtils.concatOperands(
			"FED", "fed_fout",
			InstructionUtils.concatOperandParts("X", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("A", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			"COL");
		assertThrows("FED->LOUT->FOUT must be represented explicitly by the planner instead of being "
			+ "hidden inside fed_fout", RuntimeException.class,
			() -> FEDFoutInstruction.parseInstruction(inst).processInstruction(ec));
	}

	private static MatrixObject localMatrix(String name, int rows, int cols) {
		MatrixBlock mb = new MatrixBlock(rows, cols, false);
		mb.allocateDenseBlock();
		MatrixCharacteristics mc = new MatrixCharacteristics(rows, cols, 1024, 0);
		return new MatrixObject(ValueType.FP64, name, new MetaData(mc), mb);
	}

	private static MatrixObject federatedAnchor(String name, int numWorkers) {
		MatrixObject anchor = new MatrixObject(ValueType.FP64, name);
		anchor.setMetaData(new MetaData(new MatrixCharacteristics(-1, -1, 1024)));

		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for (int i = 0; i < numWorkers; i++) {
			FederatedRange range = new FederatedRange(new long[] {i, 0}, new long[] {i + 1, 1});
			FederatedData data = new FederatedData(Types.DataType.MATRIX,
				new InetSocketAddress("localhost", 14000 + i), "dummy");
			entries.add(Pair.of(range, data));
		}
		anchor.setFedMapping(new NoOpFederationMap(1, entries, FType.ROW));
		return anchor;
	}
}
