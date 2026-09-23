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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class BoundedWorkerWarmupTest {
	private static final long ID = 9123L;
	private static final String PATH = "/tmp/w1357-protected-x";

	@Test
	public void protectedSourceIsNotModifiedAndOnlyMetadataLeavesWorker() throws Exception {
		ExecutionContext ec = context();
		FederatedResponse read = BoundedWorkerWarmup.prepare(new FederatedRequest(
			FederatedRequest.RequestType.SOURCE_PREPARE, ID, PATH, "private-aggregate"), ec);
		FederatedResponse warm = BoundedWorkerWarmup.run(new FederatedRequest(
			FederatedRequest.RequestType.BOUNDED_WARMUP, ID, PATH, 2048, 128, 3), ec);
		assertTrue(read.isSuccessful());
		assertTrue(warm.isSuccessful());
		assertEquals(1, read.getData().length);
		assertEquals(1, warm.getData().length);
		assertTrue(read.getData()[0] instanceof String);
		String[] fields = ((String) warm.getData()[0]).split("\\|");
		assertEquals(6, fields.length);
		assertEquals("2", fields[2]);
		assertEquals("2", fields[3]);
		assertEquals("3", fields[4]);
		MatrixObject source = (MatrixObject) ec.getVariable(String.valueOf(ID));
		MatrixBlock original = source.acquireRead();
		try {
			assertEquals(2d, original.get(0, 0), 0d);
			assertEquals(-3d, original.get(1, 1), 0d);
		}
		finally { source.release(); }
	}

	@Test
	public void sourceIdentityAndBoundsFailClosed() {
		ExecutionContext ec = context();
		assertThrows(FederatedWorkerHandlerException.class, () -> BoundedWorkerWarmup.prepare(
			new FederatedRequest(FederatedRequest.RequestType.SOURCE_PREPARE,
				ID, PATH, "invalid"), ec));
		assertThrows(FederatedWorkerHandlerException.class, () -> BoundedWorkerWarmup.run(
			new FederatedRequest(FederatedRequest.RequestType.BOUNDED_WARMUP,
				ID, PATH, 2049, 128, 3), ec));
		assertThrows(FederatedWorkerHandlerException.class, () -> BoundedWorkerWarmup.run(
			new FederatedRequest(FederatedRequest.RequestType.BOUNDED_WARMUP,
				ID, "/tmp/other", 2048, 128, 3), ec));
	}

	private static ExecutionContext context() {
		MatrixBlock values = new MatrixBlock(2, 2, false);
		values.allocateDenseBlock();
		values.set(0, 0, 2d);
		values.set(1, 1, -3d);
		values.recomputeNonZeros();
		MatrixObject source = new MatrixObject(ValueType.FP64, PATH,
			new MetaData(new MatrixCharacteristics(2, 2, 1024, 2)), values);
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable(String.valueOf(ID), source);
		return ec;
	}
}
