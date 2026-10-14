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

package org.apache.sysds.runtime.controlprogram.federated;

import java.util.List;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.frame.data.columns.ColumnMetadata;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/**
 * Collects a persistent replicated federated value. All replicas are awaited and
 * validated, while the first entry in {@link FederationMap}'s stable map order is
 * selected deterministically. Replica payloads are never aggregated.
 */
public final class FederationReplicaReader {
	private FederationReplicaReader() {
		// utility class
	}

	public static MatrixBlock readMatrix(
		List<Pair<FederatedRange, Future<FederatedResponse>>> readResponses, long[] dims) throws Exception {
		MatrixBlock selected = (MatrixBlock) awaitAndValidate(readResponses, dims, MatrixBlock.class);
		return new MatrixBlock(selected, selected.isInSparseFormat());
	}

	public static FrameBlock readFrame(
		List<Pair<FederatedRange, Future<FederatedResponse>>> readResponses, long[] dims) throws Exception {
		FrameBlock selected = (FrameBlock) awaitAndValidate(readResponses, dims, FrameBlock.class);
		FrameBlock ret = new FrameBlock(selected);
		ColumnMetadata[] metadata = selected.getColumnMetadata();
		if(metadata != null) {
			ColumnMetadata[] metadataCopy = new ColumnMetadata[metadata.length];
			for(int i = 0; i < metadata.length; i++)
				metadataCopy[i] = metadata[i] == null ? null : new ColumnMetadata(metadata[i]);
			ret.setColumnMetadata(metadataCopy);
		}
		return ret;
	}

	private static Object awaitAndValidate(
		List<Pair<FederatedRange, Future<FederatedResponse>>> readResponses, long[] dims,
		Class<?> payloadType) throws Exception {
		validateDimensions(dims);
		if(readResponses == null || readResponses.isEmpty())
			throw new DMLRuntimeException("Replicated federated read requires at least one worker response.");

		Object selected = null;
		Exception firstFailure = null;
		for(int i = 0; i < readResponses.size(); i++) {
			Pair<FederatedRange, Future<FederatedResponse>> readResponse = readResponses.get(i);
			try {
				if(readResponse == null || readResponse.getLeft() == null || readResponse.getRight() == null)
					throw new DMLRuntimeException("Missing replicated federated response at worker index " + i + ".");
				FederatedResponse response = readResponse.getRight().get();
				validateFullRange(readResponse.getLeft(), dims, i);
				if(response == null || !response.isSuccessful()) {
					if(response != null)
						response.throwExceptionFromResponse();
					throw new DMLRuntimeException("Missing replicated federated response at worker index " + i + ".");
				}
				Object[] data = response.getData();
				if(data == null || data.length != 1 || !payloadType.isInstance(data[0]))
					throw new DMLRuntimeException("Invalid replicated federated " + payloadType.getSimpleName()
						+ " payload at worker index " + i + ".");
				validatePayloadShape(data[0], dims, i);
				if(i == 0)
					selected = data[0];
			}
			catch(Exception ex) {
				if(firstFailure == null)
					firstFailure = ex;
			}
		}
		if(firstFailure instanceof InterruptedException)
			Thread.currentThread().interrupt();
		if(firstFailure != null)
			throw firstFailure;
		return selected;
	}

	private static void validateDimensions(long[] dims) {
		if(dims == null || dims.length != 2 || dims[0] < 0 || dims[1] < 0
			|| dims[0] > Integer.MAX_VALUE || dims[1] > Integer.MAX_VALUE)
			throw new DMLRuntimeException("Invalid dimensions for replicated federated read.");
	}

	private static void validateFullRange(FederatedRange range, long[] dims, int workerIndex) {
		long[] begin = range.getBeginDims();
		long[] end = range.getEndDims();
		if(begin.length != 2 || end.length != 2 || begin[0] != 0 || begin[1] != 0
			|| end[0] != dims[0] || end[1] != dims[1])
			throw new DMLRuntimeException("Invalid replicated federated range at worker index " + workerIndex + ".");
	}

	private static void validatePayloadShape(Object payload, long[] dims, int workerIndex) {
		int rows;
		int cols;
		if(payload instanceof MatrixBlock) {
			rows = ((MatrixBlock) payload).getNumRows();
			cols = ((MatrixBlock) payload).getNumColumns();
		}
		else {
			rows = ((FrameBlock) payload).getNumRows();
			cols = ((FrameBlock) payload).getNumColumns();
		}
		if(rows != dims[0] || cols != dims[1])
			throw new DMLRuntimeException("Invalid replicated federated payload shape at worker index "
				+ workerIndex + ": expected " + dims[0] + "x" + dims[1] + " but received " + rows + "x" + cols + ".");
	}
}
