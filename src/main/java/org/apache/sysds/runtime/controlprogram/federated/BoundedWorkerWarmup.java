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

import java.lang.management.ManagementFactory;

import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.matrix.data.LibMatrixMult;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

/** Narrow evaluation request: no matrix, cell value, or data-derived checksum leaves the worker. */
final class BoundedWorkerWarmup {
	private static volatile double sink;

	private BoundedWorkerWarmup() { }

	static FederatedResponse prepare(FederatedRequest request, ExecutionContext ec) {
		if(request.getNumParams() != 2 || !(request.getParam(0) instanceof String path) ||
			!(request.getParam(1) instanceof String privacy))
			throw denied();
		MatrixObject source = source(request, ec, path, privacy);
		try {
			MatrixBlock block = source.acquireRead();
			try {
				return receipt(block.getNumRows(), block.getNumColumns(), 0, 0);
			}
			finally { source.release(); }
		}
		catch(Exception ex) { throw denied(); }
	}

	static FederatedResponse run(FederatedRequest request, ExecutionContext ec) {
		if(request.getNumParams() != 4 || !(request.getParam(0) instanceof String path) ||
			!(request.getParam(1) instanceof Integer maxRows) ||
			!(request.getParam(2) instanceof Integer maxCols) ||
			!(request.getParam(3) instanceof Integer repeats) ||
			maxRows < 1 || maxRows > 2048 || maxCols < 1 || maxCols > 128 || repeats != 3)
			throw denied();
		MatrixObject source = source(request, ec, path, "private-aggregate");
		try {
			MatrixBlock block = source.acquireRead();
			try {
				int rows = Math.min(maxRows, block.getNumRows());
				int cols = Math.min(maxCols, block.getNumColumns());
				if(rows < 1 || cols < 1) throw denied();
				// slice creates a distinct block. Never alter the original cached object.
				MatrixBlock sample = block.slice(0, rows - 1, 0, cols - 1, true, null);
				long start = System.nanoTime();
				warm(sample, repeats);
				return receipt(rows, cols, repeats, System.nanoTime() - start);
			}
			finally { source.release(); }
		}
		catch(Exception ex) { throw denied(); }
	}

	private static MatrixObject source(FederatedRequest request, ExecutionContext ec,
		String path, String privacy) {
		if(ec == null || path == null || path.isBlank() || !path.startsWith("/") ||
			!("public".equals(privacy) || "private-aggregate".equals(privacy) || "private".equals(privacy)))
			throw denied();
		Data data = ec.getVariable(String.valueOf(request.getID()));
		if(!(data instanceof MatrixObject matrix) || !path.equals(matrix.getFileName()))
			throw denied();
		return matrix;
	}

	static void warm(MatrixBlock sample, int repeats) {
		int rows = sample.getNumRows(), cols = sample.getNumColumns();
		if(rows < 1 || rows > 2048 || cols < 1 || cols > 128 || repeats != 3)
			throw denied();
		for(int iteration = 0; iteration < repeats; iteration++) {
			MatrixBlock transformed = new MatrixBlock(rows, cols, false);
			transformed.allocateDenseBlock();
			for(int row = 0; row < rows; row++)
				for(int col = 0; col < cols; col++) {
					double absolute = Math.abs(sample.get(row, col));
					transformed.set(row, col, absolute / (1d + absolute));
				}
			transformed.recomputeNonZeros();
			MatrixBlock gram = LibMatrixMult.matrixMult(transformed.transpose(), transformed);
			MatrixBlock sums = transformed.rowSum();
			// Volatile worker-local consumption prevents dead-code elimination.
			sink = gram.get(0, 0) + sums.get(0, 0);
		}
	}

	private static FederatedResponse receipt(int rows, int cols, int repeats, long nanos) {
		String value = ProcessHandle.current().pid() + "|" +
			ManagementFactory.getRuntimeMXBean().getStartTime() + "|" +
			rows + "|" + cols + "|" + repeats + "|" + nanos;
		return new FederatedResponse(FederatedResponse.ResponseType.SUCCESS, value);
	}

	private static FederatedWorkerHandlerException denied() {
		return new FederatedWorkerHandlerException("Bounded worker source operation denied.");
	}
}
