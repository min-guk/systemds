/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.MMChainCPInstruction;
import org.apache.sysds.runtime.instructions.fed.MMChainFEDInstruction;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class MMChainFEDInstructionCompileTest {
	@Test
	public void rowMappingIsSupported() {
		assertNotNull(convert(FType.ROW, 2));
	}

	@Test
	public void singleRangeFullMappingIsSupported() {
		assertNotNull(convert(FType.FULL, 1));
	}

	@Test
	public void multiRangeFullAndColumnMappingsFailClosed() {
		assertNull(convert(FType.FULL, 2));
		assertNull(convert(FType.COL, 2));
	}

	private static MMChainFEDInstruction convert(FType type, int ranges) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("X", federatedMatrix(type, ranges));
		String instruction = InstructionUtils.concatOperands(
			"CP", "mmchain",
			InstructionUtils.concatOperandParts("X", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("v", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("out", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			"XtXv", "1");
		return MMChainFEDInstruction.parseInstruction(
			MMChainCPInstruction.parseInstruction(instruction), ec);
	}

	private static MatrixObject federatedMatrix(FType type, int ranges) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(10, 4, 1024, -1)));
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for (int i = 0; i < ranges; i++) {
			FederatedRange range = new FederatedRange(new long[] {i, 0}, new long[] {i + 1, 4});
			FederatedData data = new FederatedData(Types.DataType.MATRIX,
				new InetSocketAddress("localhost", 15000 + i), "dummy");
			entries.add(Pair.of(range, data));
		}
		matrix.setFedMapping(new FederationMap(91, entries, type));
		return matrix;
	}
}
