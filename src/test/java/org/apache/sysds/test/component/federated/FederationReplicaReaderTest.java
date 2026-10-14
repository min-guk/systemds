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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.frame.data.columns.ColumnMetadata;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Test;

public class FederationReplicaReaderTest {
	@Test
	public void twoWorkersCollectMatrixAndFrameReplicasOverTransport() throws Exception {
		int port1 = AutomatedTestBase.getRandomAvailablePort();
		int port2 = AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1)
			port2 = AutomatedTestBase.getRandomAvailablePort();
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(port1, 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(port2, 1000);
			InetSocketAddress address1 = new InetSocketAddress("localhost", port1);
			InetSocketAddress address2 = new InetSocketAddress("localhost", port2);

			MatrixBlock matrix = matrix(new double[][] {{1, 0}, {0, 2}}, true);
			long matrixId1 = putBlock(address1, matrix);
			long matrixId2 = putBlock(address2, matrix);
			MatrixBlock matrixResult = new ExposedMatrixObject().read(
				actualBroadcastMap(DataType.MATRIX, new long[] {2, 2}, address1, matrixId1, address2, matrixId2),
				new long[] {2, 2});
			assertEquals(2, matrixResult.getNonZeros());
			assertEquals(1, matrixResult.get(0, 0), 0);
			assertEquals(2, matrixResult.get(1, 1), 0);

			FrameBlock frame = frame();
			long frameId1 = putBlock(address1, frame);
			long frameId2 = putBlock(address2, frame);
			FrameBlock frameResult = new ExposedFrameObject().read(
				actualBroadcastMap(DataType.FRAME, new long[] {2, 2}, address1, frameId1, address2, frameId2),
				new long[] {2, 2});
			assertEquals("words", frameResult.getColumnName(0));
			assertEquals("missing", frameResult.getColumnMetadata(0).getMvValue());
			assertEquals("beta", frameResult.get(1, 0));
		}
		finally {
			FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	@Test
	public void matrixBroadcastSelectsFirstReplicaWithoutSummingAndOwnsDenseData() throws Exception {
		MatrixBlock first = matrix(new double[][] {{1, 0}, {0, 2}}, false);
		MatrixBlock second = matrix(new double[][] {{1, 0}, {0, 2}}, false);
		TrackingFuture firstFuture = success(first);
		TrackingFuture secondFuture = success(second);

		MatrixBlock result = new ExposedMatrixObject().read(
			broadcastMap(responses(new long[] {2, 2}, firstFuture, secondFuture)), new long[] {2, 2});

		assertEquals(1, firstFuture.getCount());
		assertEquals(1, secondFuture.getCount());
		assertEquals(2, result.getNonZeros());
		assertEquals(1, result.get(0, 0), 0);
		assertEquals(2, result.get(1, 1), 0);
		assertNotSame(first, result);
		result.set(0, 0, 9);
		assertEquals(1, first.get(0, 0), 0);
	}

	@Test
	public void matrixBroadcastPreservesSparseValuesAndNnz() throws Exception {
		MatrixBlock first = matrix(new double[][] {{0, 0, 0}, {0, 7, 0}, {0, 0, 0}}, true);
		MatrixBlock second = new MatrixBlock(first);

		MatrixBlock result = new ExposedMatrixObject().read(broadcastMap(responses(new long[] {3, 3},
			success(first), success(second))), new long[] {3, 3});

		assertTrue(result.isInSparseFormat());
		assertEquals(1, result.getNonZeros());
		assertEquals(7, result.get(1, 1), 0);
	}

	@Test
	public void matrixBroadcastAwaitsAndRejectsFailedNonSelectedReplica() throws Exception {
		TrackingFuture first = success(matrix(new double[][] {{1}}, false));
		TrackingFuture failed = new TrackingFuture(new FederatedResponse(ResponseType.ERROR, "worker failed"));

		try {
			new ExposedMatrixObject().read(broadcastMap(responses(new long[] {1, 1}, first, failed)),
				new long[] {1, 1});
			fail("Expected every replica failure to fail the collect");
		}
		catch(DMLRuntimeException ex) {
			assertTrue(ex.getMessage().contains("Federated matrix read failed"));
		}
		assertEquals(1, first.getCount());
		assertEquals(1, failed.getCount());
	}

	@Test
	public void matrixBroadcastRejectsInvalidFullRangeAndPayloadShape() throws Exception {
		TrackingFuture wrongRange = success(matrix(new double[][] {{1, 2}}, false));
		TrackingFuture wrongShape = success(matrix(new double[][] {{1}}, false));
		List<Pair<FederatedRange, Future<FederatedResponse>>> responses = List.of(
			Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {1, 1}), wrongRange),
			Pair.of(fullRange(new long[] {1, 2}), wrongShape));

		try {
			new ExposedMatrixObject().read(broadcastMap(responses), new long[] {1, 2});
			fail("Expected invalid replica geometry to fail the collect");
		}
		catch(DMLRuntimeException ex) {
			assertTrue(ex.getMessage().contains("Federated matrix read failed"));
		}
		assertEquals(1, wrongRange.getCount());
		assertEquals(1, wrongShape.getCount());
	}

	@Test
	public void frameBroadcastPreservesAndOwnsPayloadAndMetadata() throws Exception {
		FrameBlock first = frame();
		FrameBlock second = frame();
		TrackingFuture firstFuture = success(first);
		TrackingFuture secondFuture = success(second);

		FrameBlock result = new ExposedFrameObject().read(
			broadcastMap(responses(new long[] {2, 2}, firstFuture, secondFuture)), new long[] {2, 2});

		assertEquals(1, firstFuture.getCount());
		assertEquals(1, secondFuture.getCount());
		assertEquals(ValueType.STRING, result.getSchema()[0]);
		assertEquals(ValueType.INT64, result.getSchema()[1]);
		assertEquals("words", result.getColumnName(0));
		assertEquals("ids", result.getColumnName(1));
		assertEquals("missing", result.getColumnMetadata(0).getMvValue());
		assertEquals(2, result.getColumnMetadata(0).getNumDistinct());
		assertEquals("alpha", result.get(0, 0));
		assertEquals(Long.valueOf(2), result.get(1, 1));

		result.set(0, 0, "changed");
		result.setColumnName(0, "changed-name");
		result.getColumnMetadata(0).setMvValue("changed-metadata");
		assertEquals("alpha", first.get(0, 0));
		assertEquals("words", first.getColumnName(0));
		assertEquals("missing", first.getColumnMetadata(0).getMvValue());
	}

	private static MatrixBlock matrix(double[][] values, boolean sparse) {
		MatrixBlock ret = new MatrixBlock(values.length, values[0].length, sparse);
		for(int r = 0; r < values.length; r++)
			for(int c = 0; c < values[r].length; c++)
				if(values[r][c] != 0)
					ret.set(r, c, values[r][c]);
		ret.recomputeNonZeros();
		return ret;
	}

	private static FrameBlock frame() {
		FrameBlock ret = new FrameBlock(new ValueType[] {ValueType.STRING, ValueType.INT64},
			new String[] {"words", "ids"});
		ret.appendRow(new Object[] {"alpha", 1L});
		ret.appendRow(new Object[] {"beta", 2L});
		ret.setColumnMetadata(0, new ColumnMetadata(2, "missing"));
		ret.setColumnMetadata(1, new ColumnMetadata(2));
		return ret;
	}

	private static TrackingFuture success(Object payload) {
		return new TrackingFuture(new FederatedResponse(ResponseType.SUCCESS, payload));
	}

	private static List<Pair<FederatedRange, Future<FederatedResponse>>> responses(
		long[] dims, Future<FederatedResponse> first, Future<FederatedResponse> second) {
		return List.of(Pair.of(fullRange(dims), first), Pair.of(fullRange(dims), second));
	}

	private static FederatedRange fullRange(long[] dims) {
		return new FederatedRange(new long[] {0, 0}, dims.clone());
	}

	private static FederationMap broadcastMap(
		List<Pair<FederatedRange, Future<FederatedResponse>>> responses) {
		return new ResponseFederationMap(responses);
	}

	private static long putBlock(InetSocketAddress address, Object block) throws Exception {
		long id = FederationUtils.getNextFedDataID();
		FederatedResponse response = FederatedData.executeFederatedOperation(address,
			new FederatedRequest(RequestType.PUT_VAR, null, id, block)).get(5, TimeUnit.SECONDS);
		if(!response.isSuccessful())
			response.throwExceptionFromResponse();
		return id;
	}

	private static FederationMap actualBroadcastMap(DataType dataType, long[] dims,
		InetSocketAddress address1, long id1, InetSocketAddress address2, long id2) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		entries.add(Pair.of(fullRange(dims), initializedData(dataType, address1, id1)));
		entries.add(Pair.of(fullRange(dims), initializedData(dataType, address2, id2)));
		return new FederationMap(FederationUtils.getNextFedDataID(), entries, FType.BROADCAST);
	}

	private static FederatedData initializedData(DataType dataType, InetSocketAddress address, long id) {
		FederatedData data = new FederatedData(dataType, address, "replica");
		data.setVarID(id);
		return data;
	}

	private static class ResponseFederationMap extends FederationMap {
		private final List<Pair<FederatedRange, Future<FederatedResponse>>> _responses;

		ResponseFederationMap(List<Pair<FederatedRange, Future<FederatedResponse>>> responses) {
			super(1, Collections.emptyList(), FType.BROADCAST);
			_responses = responses;
		}

		@Override
		public List<Pair<FederatedRange, Future<FederatedResponse>>> requestFederatedData() {
			return _responses;
		}
	}

	private static class ExposedMatrixObject extends MatrixObject {
		ExposedMatrixObject() {
			super(ValueType.FP64, "");
		}

		MatrixBlock read(FederationMap map, long[] dims) throws IOException {
			return readBlobFromFederated(map, dims);
		}
	}

	private static class ExposedFrameObject extends FrameObject {
		ExposedFrameObject() {
			super("");
		}

		FrameBlock read(FederationMap map, long[] dims) throws IOException {
			return readBlobFromFederated(map, dims);
		}
	}

	private static class TrackingFuture implements Future<FederatedResponse> {
		private final CompletableFuture<FederatedResponse> _delegate;
		private int _getCount;

		TrackingFuture(FederatedResponse response) {
			_delegate = CompletableFuture.completedFuture(response);
		}

		int getCount() {
			return _getCount;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return _delegate.cancel(mayInterruptIfRunning);
		}

		@Override
		public boolean isCancelled() {
			return _delegate.isCancelled();
		}

		@Override
		public boolean isDone() {
			return _delegate.isDone();
		}

		@Override
		public FederatedResponse get() throws InterruptedException, ExecutionException {
			_getCount++;
			return _delegate.get();
		}

		@Override
		public FederatedResponse get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
			_getCount++;
			return _delegate.get(timeout, unit);
		}
	}
}
