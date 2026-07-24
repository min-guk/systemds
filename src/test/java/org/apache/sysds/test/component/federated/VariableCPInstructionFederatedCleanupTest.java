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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class VariableCPInstructionFederatedCleanupTest {
	private static class CountingFederationMap extends FederationMap {
		private int cleanupCalls = 0;
		private final List<Long> cleanedIds = new ArrayList<>();

		CountingFederationMap(long id) {
			super(id, List.of(Pair.of(
				new FederatedRange(new long[] {0, 0}, new long[] {2, 2}),
				new FederatedData(Types.DataType.MATRIX,
					new InetSocketAddress("localhost", 14000 + (int) id), "matrix-" + id))), FType.ROW);
		}

		@Override
		public void execCleanup(long tid, long... id) {
			cleanupCalls++;
			if(id != null)
				for(long value : id)
					cleanedIds.add(value);
		}
	}

	@Test
	public void selfCopyFederatedMatrixDoesNotCleanupLiveMapping() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		CountingFederationMap map = new CountingFederationMap(11);
		MatrixObject matrix = federatedMatrix("A", map);
		ec.setVariable("A", matrix);

		copy("A", "A").processInstruction(ec);

		assertSame(matrix, ec.getVariable("A"));
		assertTrue(((MatrixObject) ec.getVariable("A")).isFederated());
		assertEquals(11, ((MatrixObject) ec.getVariable("A")).getFedMapping().getID());
		assertEquals("cpvar A A must not release the same live federated mapping", 0, map.cleanupCalls);
		assertTrue(map.cleanedIds.isEmpty());
	}

	@Test
	public void copyOverDistinctFederatedTargetCleansOldTargetMapping() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		CountingFederationMap sourceMap = new CountingFederationMap(21);
		CountingFederationMap targetMap = new CountingFederationMap(22);
		MatrixObject source = federatedMatrix("A", sourceMap);
		MatrixObject target = federatedMatrix("B", targetMap);
		ec.setVariable("A", source);
		ec.setVariable("B", target);

		copy("A", "B").processInstruction(ec);

		assertSame(source, ec.getVariable("B"));
		assertEquals(0, sourceMap.cleanupCalls);
		assertEquals(1, targetMap.cleanupCalls);
		assertEquals(List.of(22L), targetMap.cleanedIds);
	}

	private static Instruction copy(String source, String target) {
		return VariableCPInstruction.prepareCopyInstruction(source, target);
	}

	private static MatrixObject federatedMatrix(String name, CountingFederationMap map) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, name,
			new MetaData(new MatrixCharacteristics(2, 2, 1024)));
		matrix.setFedMapping(map);
		return matrix;
	}
}
