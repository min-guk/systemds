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

package org.apache.sysds.runtime.instructions.fed;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedUDF;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.cp.BooleanObject;
import org.apache.sysds.runtime.instructions.cp.ScalarObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.junit.Assert;
import org.junit.Test;

/** Exercises request construction: a result in the execution response needs no second RTT. */
public class NativeResultBatchContractTest {
	@Test
	public void containsBatchesExecutionAndGetTogether() {
		ExecutionContext ec = ExecutionContextFactory.createContext();
		MatrixObject input = matrix(ec, "X", 1);
		ContainsMap map = new ContainsMap();
		input.setFedMapping(map);
		ParameterizedBuiltinFEDInstruction.parseInstruction(
			"FED°contains°target=X°pattern=3°C·SCALAR·BOOLEAN").processInstruction(ec);
		Assert.assertEquals(1, map.batches);
		Assert.assertTrue(((BooleanObject) ec.getVariable("C")).getBooleanValue());
	}

	@Test
	public void centralMomentReturnsResultInExecutionResponse() {
		ExecutionContext ec = ExecutionContextFactory.createContext();
		UdfData worker = attachWorker(matrix(ec, "X", 1));
		CentralMomentFEDInstruction.parseInstruction(
			"FED°cm°X·MATRIX·FP64°2·SCALAR·INT64·true°C·SCALAR·FP64°1°LOUT")
			.processInstruction(ec);
		Assert.assertEquals(1, worker.batches);
		Assert.assertEquals(0d, ((ScalarObject) ec.getVariable("C")).getDoubleValue(), 0d);
	}

	@Test
	public void mixedCovarianceReturnsResultInExecutionResponse() {
		ExecutionContext ec = ExecutionContextFactory.createContext();
		UdfData worker = attachWorker(matrix(ec, "X", 1));
		matrix(ec, "Y", 2);
		CovarianceFEDInstruction.parseInstruction(
			"FED°cov°X·MATRIX·FP64°Y·MATRIX·FP64°C·SCALAR·FP64°1°LOUT")
			.processInstruction(ec);
		Assert.assertEquals(1, worker.batches);
		Assert.assertEquals(0d, ((ScalarObject) ec.getVariable("C")).getDoubleValue(), 0d);
	}

	private static MatrixObject matrix(ExecutionContext ec, String name, double value) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, name,
			new MetaDataFormat(new MatrixCharacteristics(8, 1, 1000, 8), FileFormat.BINARY));
		ec.setVariable(name, matrix);
		ec.setMatrixOutput(name, new MatrixBlock(8, 1, value));
		return matrix;
	}

	private static UdfData attachWorker(MatrixObject matrix) {
		UdfData worker = new UdfData(matrix);
		matrix.setFedMapping(new UdfMap(worker));
		return worker;
	}

	private static final class ContainsMap extends FederationMap {
		private int batches;

		private ContainsMap() { super(10, List.of(), FType.ROW); }

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, FederatedRequest... requests) {
			batches++;
			Assert.assertEquals(2, requests.length);
			Assert.assertEquals(RequestType.EXEC_INST, requests[0].getType());
			Assert.assertEquals(RequestType.GET_VAR, requests[1].getType());
			return new Future[] { CompletableFuture.completedFuture(new FederatedResponse(
				FederatedResponse.ResponseType.SUCCESS, new BooleanObject(true))) };
		}
	}

	private static final class UdfData extends FederatedData {
		private int batches;
		private final MatrixObject local;

		private UdfData(MatrixObject local) {
			super(DataType.MATRIX, null, "batch-contract", 10);
			this.local = local;
		}

		@Override
		public Future<FederatedResponse> executeFederatedOperation(FederatedRequest... requests) {
			batches++;
			Assert.assertEquals(1, requests.length);
			Assert.assertEquals(RequestType.EXEC_UDF, requests[0].getType());
			return CompletableFuture.completedFuture(((FederatedUDF) requests[0].getParam(0))
				.execute(ExecutionContextFactory.createContext(), local));
		}
	}

	private static final class UdfMap extends FederationMap {
		private final UdfData worker;

		private UdfMap(UdfData worker) {
			super(10, List.of(), FType.ROW);
			this.worker = worker;
		}

		@Override
		public FederationMap mapParallel(long id, BiFunction<FederatedRange, FederatedData, Void> function) {
			function.apply(new FederatedRange(new long[] {0, 0}, new long[] {8, 1}), worker);
			return this;
		}
	}
}
