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

package org.apache.sysds.runtime.controlprogram.caching;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.junit.Test;

public class FrameObjectFederatedMaterializationTest {
	@Test
	public void rowPartitionSchemasAreMergedBeforeCollectingValues() throws Exception {
		FrameBlock first = new FrameBlock(new ValueType[] {ValueType.BOOLEAN, ValueType.INT32});
		first.appendRow(new Object[] {true, 2});
		FrameBlock second = new FrameBlock(new ValueType[] {ValueType.FP64, ValueType.INT64});
		second.appendRow(new Object[] {2.5, 3L});

		FederationMap map = mock(FederationMap.class);
		when(map.requestFederatedData()).thenReturn(List.of(
			response(new FederatedRange(new long[] {0, 0}, new long[] {1, 2}), first),
			response(new FederatedRange(new long[] {1, 0}, new long[] {2, 2}), second)));
		FrameObject object = new FrameObject("federated-frame");
		object.setSchema(new ValueType[] {ValueType.FP64, ValueType.FP64});

		FrameBlock result = object.readBlobFromFederated(map, new long[] {2, 2});

		assertArrayEquals(new ValueType[] {ValueType.FP64, ValueType.INT64}, result.getSchema());
		assertArrayEquals(result.getSchema(), object.getSchema());
		assertEquals(1.0, result.get(0, 0));
		assertEquals(2L, result.get(0, 1));
		assertEquals(2.5, result.get(1, 0));
		assertEquals(3L, result.get(1, 1));
	}

	private static Pair<FederatedRange, java.util.concurrent.Future<FederatedResponse>> response(
		FederatedRange range, FrameBlock block) {
		return Pair.of(range, CompletableFuture.completedFuture(
			new FederatedResponse(ResponseType.SUCCESS, block)));
	}
}
