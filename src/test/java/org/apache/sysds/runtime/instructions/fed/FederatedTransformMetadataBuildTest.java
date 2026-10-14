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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedUDF;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.StringObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;
import org.apache.sysds.runtime.transform.encode.ColumnEncoderRecode;
import org.apache.sysds.runtime.transform.encode.EncoderFactory;
import org.apache.sysds.runtime.transform.encode.MultiColumnEncoder;
import org.junit.After;
import org.junit.Test;

public class FederatedTransformMetadataBuildTest {
	private static final String RELEASE_SPEC =
		"{ids:true,recode:[1],dummycode:[1],cofeePublicRecodeMetadata:true}";
	private final String priorRelease = System.getProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);

	@After
	public void restoreReleaseProperty() {
		if(priorRelease == null)
			System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		else
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, priorRelease);
	}

	@Test
	public void buildOnlyMatchesLocalGlobalDomainAndDoesNotApply() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		FrameBlock first = frame(new Object[][] {{"beta", 1L}, {null, 2L}, {"alpha", 3L}});
		FrameBlock second = frame(new Object[][] {{"alpha", 4L}, {"gamma", 5L}, {null, 6L}});
		List<Long> dispatchOrder = new ArrayList<>();
		MockFedData worker1 = new MockFedData(11, first, dispatchOrder);
		MockFedData worker2 = new MockFedData(22, second, dispatchOrder);
		// Deliberately store the ranges out of order. The production helper must issue and merge in logical order.
		FrameObject input = federatedFrame(List.of(
			entry(3, 6, worker2), entry(0, 3, worker1)), 6, 2);

		MultiReturnParameterizedBuiltinFEDInstruction.PublicRecodeMetadata result =
			MultiReturnParameterizedBuiltinFEDInstruction.buildPublicRecodeMetadata(input, RELEASE_SPEC);

		assertEquals(Arrays.asList(11L, 22L), dispatchOrder);
		assertEquals(1, worker1.buildCalls);
		assertEquals(1, worker2.buildCalls);
		assertEquals(0, worker1.applyCalls);
		assertEquals(0, worker2.applyCalls);
		assertNotNull(result.encoder());
		assertNotNull(result.metadata());

		FrameBlock combined = frame(new Object[][] {
			{"beta", 1L}, {null, 2L}, {"alpha", 3L},
			{"alpha", 4L}, {"gamma", 5L}, {null, 6L}});
		Expected expected = localExpected(combined, "{ids:true,recode:[1],dummycode:[1]}");
		assertFrameEquals(expected.metadata, result.metadata());
		assertMatrixEquals(expected.encoder.apply(combined, 1), result.encoder().apply(combined, 1));
	}

	@Test
	public void buildOnlyRejectsUnauthorizedAndNonMetadataSpecsBeforeRpc() {
		FrameBlock block = frame(new Object[][] {{"a", 1L}});
		List<Long> dispatchOrder = new ArrayList<>();
		MockFedData worker = new MockFedData(31, block, dispatchOrder);
		FrameObject input = federatedFrame(List.of(entry(0, 1, worker)), 1, 2);

		System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		expectDML(() ->
			MultiReturnParameterizedBuiltinFEDInstruction.buildPublicRecodeMetadata(input, RELEASE_SPEC));
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		expectDML(() ->
			MultiReturnParameterizedBuiltinFEDInstruction.buildPublicRecodeMetadata(input,
				"{ids:true,recode:[1],omit:[2],cofeePublicRecodeMetadata:true}"));
		expectDML(() ->
			MultiReturnParameterizedBuiltinFEDInstruction.buildPublicRecodeMetadata(input,
				"{ids:true,recode:[1]}"));

		assertTrue(dispatchOrder.isEmpty());
		assertEquals(0, worker.buildCalls);
		assertEquals(0, worker.applyCalls);
	}

	@Test
	public void ordinaryFederatedTransformStillBuildsAppliesAndPublishesMetadata() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		FrameBlock block = frame(new Object[][] {{"b", 1L}, {"a", 2L}, {"b", 3L}});
		MockFedData worker = new MockFedData(41, block, new ArrayList<>());
		FrameObject input = federatedFrame(List.of(entry(0, 3, worker)), 3, 2);
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("in", input);
		ec.setVariable("spec", new StringObject(RELEASE_SPEC));
		ec.setVariable("encoded", new MatrixObject(ValueType.FP64, "encoded",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024, -1))));
		ec.setVariable("metadata", new FrameObject("metadata",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024, -1))));
		String instruction = "FED" + Instruction.OPERAND_DELIM + "transformencode" + Instruction.OPERAND_DELIM
			+ "in" + Instruction.VALUETYPE_PREFIX + "FRAME" + Instruction.VALUETYPE_PREFIX + "STRING"
			+ Instruction.OPERAND_DELIM
			+ "spec" + Instruction.VALUETYPE_PREFIX + "SCALAR" + Instruction.VALUETYPE_PREFIX + "STRING"
			+ Instruction.VALUETYPE_PREFIX + "false" + Instruction.OPERAND_DELIM + "encoded"
			+ Instruction.OPERAND_DELIM + "metadata";

		MultiReturnParameterizedBuiltinFEDInstruction.parseInstruction(instruction).processInstruction(ec);

		assertEquals(1, worker.buildCalls);
		assertEquals(1, worker.applyCalls);
		assertTrue(ec.getMatrixObject("encoded").isFederated());
		assertEquals(3, ec.getMatrixObject("encoded").getNumRows());
		assertEquals(3, ec.getMatrixObject("encoded").getNumColumns());
		assertEquals(2, ec.getFrameObject("metadata").getNumColumns());
	}

	@Test
	public void failedWorkerResponseStillDrainsEveryIssuedBuildFuture() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		DrainFedData failed = new DrainFedData(51, frame(new Object[][] {{"a", 1L}}), true);
		DrainFedData success = new DrainFedData(52, frame(new Object[][] {{"b", 2L}}), false);
		FrameObject input = federatedFrame(List.of(
			Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {1, 2}), failed),
			Pair.of(new FederatedRange(new long[] {1, 0}, new long[] {2, 2}), success)), 2, 2);

		expectDML(() -> MultiReturnParameterizedBuiltinFEDInstruction.buildPublicRecodeMetadata(input, RELEASE_SPEC));

		assertEquals(1, failed.buildCalls);
		assertEquals(1, success.buildCalls);
		assertEquals(1, failed.futureGets);
		assertEquals(1, success.futureGets);
	}

	private static Pair<FederatedRange, FederatedData> entry(long begin, long end, MockFedData worker) {
		return Pair.of(new FederatedRange(new long[] {begin, 0}, new long[] {end, 2}), worker);
	}

	private static FrameObject federatedFrame(List<Pair<FederatedRange, FederatedData>> entries, long rows, long cols) {
		FrameObject input = new FrameObject("federated", new MetaData(new MatrixCharacteristics(rows, cols, 1024, -1)),
			new ValueType[] {ValueType.STRING, ValueType.INT64});
		input.setFedMapping(new FederationMap(99, entries, FType.ROW));
		return input;
	}

	private static FrameBlock frame(Object[][] rows) {
		FrameBlock frame = new FrameBlock(new ValueType[] {ValueType.STRING, ValueType.INT64},
			new String[] {"category", "value"});
		for(Object[] row : rows)
			frame.appendRow(row);
		return frame;
	}

	private static Expected localExpected(FrameBlock input, String spec) {
		MultiColumnEncoder encoder = EncoderFactory.createEncoder(
			spec, input.getColumnNames(), input.getNumColumns(), null, 1, input.getNumColumns() + 1);
		encoder.build(input, 1);
		if(ColumnEncoderRecode.SORT_RECODE_MAP)
			encoder.applyToAll(ColumnEncoderRecode.class, ColumnEncoderRecode::sortCPRecodeMaps);
		FrameBlock metadata = new FrameBlock(input.getNumColumns(), ValueType.STRING);
		metadata.setColumnNames(input.getColumnNames());
		encoder.getMetaData(metadata);
		encoder.initMetaData(metadata);
		return new Expected(encoder, metadata);
	}

	private static void assertFrameEquals(FrameBlock expected, FrameBlock actual) {
		assertEquals(expected.getNumRows(), actual.getNumRows());
		assertEquals(expected.getNumColumns(), actual.getNumColumns());
		assertTrue(Arrays.equals(expected.getColumnNames(), actual.getColumnNames()));
		for(int c = 0; c < expected.getNumColumns(); c++) {
			assertEquals(expected.getColumnMetadata(c).getNumDistinct(), actual.getColumnMetadata(c).getNumDistinct());
			for(int r = 0; r < expected.getNumRows(); r++)
				assertEquals(expected.get(r, c), actual.get(r, c));
		}
	}

	private static void assertMatrixEquals(MatrixBlock expected, MatrixBlock actual) {
		assertEquals(expected.getNumRows(), actual.getNumRows());
		assertEquals(expected.getNumColumns(), actual.getNumColumns());
		for(int r = 0; r < expected.getNumRows(); r++)
			for(int c = 0; c < expected.getNumColumns(); c++)
				assertEquals(expected.get(r, c), actual.get(r, c), 0d);
	}

	private static void expectDML(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected DMLRuntimeException");
		}
		catch(DMLRuntimeException expected) {
			// expected
		}
	}

	private record Expected(MultiColumnEncoder encoder, FrameBlock metadata) { }

	private static final class MockFedData extends FederatedData {
		private final FrameObject local;
		private final List<Long> dispatchOrder;
		private final MockFedData counters;
		private int buildCalls;
		private int applyCalls;

		private MockFedData(long id, FrameBlock block, List<Long> dispatchOrder) {
			this(id, localFrame(block), dispatchOrder, null);
		}

		private MockFedData(long id, FrameObject local, List<Long> dispatchOrder, MockFedData counters) {
			super(DataType.FRAME, new InetSocketAddress("localhost", 21000 + (int) id), "mock", id);
			this.local = local;
			this.dispatchOrder = dispatchOrder;
			this.counters = counters == null ? this : counters;
		}

		@Override
		public FederatedData copyWithNewID(long varID) {
			return new MockFedData(varID, local, dispatchOrder, counters);
		}

		@Override
		public Future<FederatedResponse> executeFederatedOperation(FederatedRequest... requests) {
			assertEquals(1, requests.length);
			assertEquals(FederatedRequest.RequestType.EXEC_UDF, requests[0].getType());
			FederatedUDF udf = (FederatedUDF) requests[0].getParam(0);
			if(udf instanceof MultiReturnParameterizedBuiltinFEDInstruction.CreateFrameEncoder) {
				counters.buildCalls++;
				dispatchOrder.add(getVarID());
			}
			else if(udf instanceof MultiReturnParameterizedBuiltinFEDInstruction.ExecuteFrameEncoder)
				counters.applyCalls++;
			else
				throw new AssertionError("Unexpected UDF " + udf.getClass().getName());
			return CompletableFuture.completedFuture(udf.execute(ExecutionContextFactory.createContext(), local));
		}
	}

	private static final class DrainFedData extends FederatedData {
		private final FrameObject local;
		private final boolean fail;
		private int buildCalls;
		private int futureGets;

		private DrainFedData(long id, FrameBlock block, boolean fail) {
			super(DataType.FRAME, new InetSocketAddress("localhost", 21100 + (int) id), "mock", id);
			local = localFrame(block);
			this.fail = fail;
		}

		@Override
		public Future<FederatedResponse> executeFederatedOperation(FederatedRequest... requests) {
			buildCalls++;
			FederatedResponse response = fail
				? new FederatedResponse(FederatedResponse.ResponseType.ERROR, "injected encoder failure")
				: ((FederatedUDF) requests[0].getParam(0)).execute(ExecutionContextFactory.createContext(), local);
			return new CountingFuture(response, this);
		}
	}

	private static final class CountingFuture implements Future<FederatedResponse> {
		private final CompletableFuture<FederatedResponse> delegate;
		private final DrainFedData owner;

		private CountingFuture(FederatedResponse response, DrainFedData owner) {
			delegate = CompletableFuture.completedFuture(response);
			this.owner = owner;
		}

		@Override public boolean cancel(boolean mayInterruptIfRunning) { return delegate.cancel(mayInterruptIfRunning); }
		@Override public boolean isCancelled() { return delegate.isCancelled(); }
		@Override public boolean isDone() { return delegate.isDone(); }

		@Override
		public FederatedResponse get() throws InterruptedException, ExecutionException {
			owner.futureGets++;
			return delegate.get();
		}

		@Override
		public FederatedResponse get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
			owner.futureGets++;
			return delegate.get(timeout, unit);
		}
	}

	private static FrameObject localFrame(FrameBlock block) {
		FrameObject local = new FrameObject("local",
			new MetaData(new MatrixCharacteristics(block.getNumRows(), block.getNumColumns(), 1024, -1)),
			block.getSchema().clone());
		local.acquireModify(block);
		local.release();
		return local;
	}
}
