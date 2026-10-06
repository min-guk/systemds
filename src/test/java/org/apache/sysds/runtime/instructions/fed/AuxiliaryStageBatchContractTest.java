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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.AlignType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.CacheBlock;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.BooleanObject;
import org.apache.sysds.runtime.instructions.cp.IntObject;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.junit.Test;

/** Locks auxiliary transport stages that are separate from the main FED instruction batch. */
public class AuxiliaryStageBatchContractTest {
	@Test
	public void ctableDiscoversFederatedDimensionsBeforeMainBatch() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingMap map = recordingMap(71, FType.ROW, 4, 1);
		MatrixObject a = matrix("A", 4, 1, null);
		a.setFedMapping(map);
		MatrixBlock bins = new MatrixBlock(4, 1, false);
		bins.set(0, 0, 1);
		bins.set(1, 0, 1);
		bins.set(2, 0, 2);
		bins.set(3, 0, 2);
		MatrixObject b = matrix("B", 4, 1, bins);
		ec.setVariable("A", a);
		ec.setVariable("B", b);
		ec.setVariable("C", matrix("C", 2, 2, null));

		CtableFEDInstruction.parseInstruction(ctableInstruction()).processInstruction(ec);

		assertEquals(2, map.batches.size());
		assertTypes(map.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTrue(String.valueOf(map.batches.get(0).get(0).getParam(0)).contains("uamax"));
		assertTypes(map.batches.get(1), RequestType.PUT_VAR, RequestType.EXEC_INST,
			RequestType.GET_VAR, RequestType.EXEC_INST);
		assertEquals(2, ec.getMatrixObject("C").getNumRows());
		assertEquals(2, ec.getMatrixObject("C").getNumColumns());
	}

	@Test
	public void ctableFoutSlicesRemoteResultInAThirdStage() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData worker = new RecordingData(74, new MatrixBlock(4, 1, 1));
		FederationMap map = new FederationMap(73, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {4, 1}), worker)), FType.ROW);
		MatrixObject a = matrix("A", 4, 1, null);
		a.setFedMapping(map);
		MatrixBlock bins = new MatrixBlock(4, 1, false);
		bins.set(0, 0, 1);
		bins.set(1, 0, 1);
		bins.set(2, 0, 2);
		bins.set(3, 0, 2);
		ec.setVariable("A", a);
		ec.setVariable("B", matrix("B", 4, 1, bins));
		ec.setVariable("C", matrix("C", 2, 2, null));

		CtableFEDInstruction.parseInstruction(ctableInstruction(FederatedOutput.FOUT)).processInstruction(ec);

		assertEquals(3, worker.batches.size());
		assertTypes(worker.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTypes(worker.batches.get(1), RequestType.PUT_VAR, RequestType.EXEC_INST);
		assertTypes(worker.batches.get(2), RequestType.EXEC_UDF);
		assertTrue(ec.getMatrixObject("C").isFederated());
	}

	@Test
	public void ctableCollectsSecondFederatedInputBeforeRebroadcast() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData first = new RecordingData(76, new MatrixBlock(4, 1, 1));
		RecordingData second = new RecordingData(78, new MatrixBlock(4, 1, 1));
		FederationMap firstMap = new FederationMap(75, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {4, 1}), first)), FType.ROW);
		FederationMap secondMap = new FederationMap(77, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {4, 1}), second)), FType.ROW);
		MatrixObject a = matrix("A", 4, 1, null);
		a.setFedMapping(firstMap);
		MatrixObject b = matrix("B", 4, 1, null);
		b.setFedMapping(secondMap);
		ec.setVariable("A", a);
		ec.setVariable("B", b);
		ec.setVariable("C", matrix("C", 2, 2, null));

		CtableFEDInstruction.parseInstruction(ctableInstruction()).processInstruction(ec);

		assertEquals(2, first.batches.size());
		assertTypes(first.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTypes(first.batches.get(1), RequestType.PUT_VAR, RequestType.EXEC_INST,
			RequestType.GET_VAR, RequestType.EXEC_INST);
		assertEquals(2, second.batches.size());
		assertTypes(second.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTypes(second.batches.get(1), RequestType.GET_VAR);
	}

	@Test
	public void ctableSecondaryCollectionIsCachedByMatrixObjectIdentity() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData primaryWorker = new RecordingData(86, new MatrixBlock(4, 1, 1));
		RecordingData secondaryWorker = new RecordingData(88, new MatrixBlock(4, 1, 2));
		FederationMap primaryMap = oneWorkerMap(85, primaryWorker, FType.ROW, 4, 1);
		FederationMap secondaryMap = oneWorkerMap(87, secondaryWorker, FType.ROW, 4, 1);
		MatrixObject primary = matrix("A", 4, 1, null);
		primary.setFedMapping(primaryMap);
		MatrixObject secondary = matrix("B", 4, 1, null);
		secondary.setFedMapping(secondaryMap);
		ec.setVariable("A", primary);
		ec.setVariable("B", secondary);
		setOutputs(ec, "C1", "C2", "C3", "C4");

		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B", "1", false, "C1"))
			.processInstruction(ec);

		assertTrue(secondary.isCached(true));
		assertEquals(2, secondaryWorker.batches.size());
		assertTypes(secondaryWorker.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR,
			RequestType.EXEC_INST);
		assertTypes(secondaryWorker.batches.get(1), RequestType.GET_VAR);
		MatrixBlock firstBroadcast = putBlock(primaryWorker.batches.get(1), 0);
		assertEquals(4, firstBroadcast.getNumRows());
		assertEquals(1, firstBroadcast.getNumColumns());
		assertEquals(512 + firstBroadcast.getExactSerializedSize(),
			putRequest(primaryWorker.batches.get(1), 0).estimateSerializationBufferSize());
		MatrixBlock cached = secondary.acquireReadAndRelease();
		assertEquals(2, secondaryWorker.batches.size());
		assertSame("a singleton-map rebroadcast reuses the collected coordinator block",
			cached, firstBroadcast);
		assertSame(cached, secondary.acquireReadAndRelease());

		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B", "1", false, "C2"))
			.processInstruction(ec);
		assertEquals("warm reuse repeats dimension discovery but not collection", 3,
			secondaryWorker.batches.size());
		assertTypes(secondaryWorker.batches.get(2), RequestType.EXEC_INST, RequestType.GET_VAR,
			RequestType.EXEC_INST);

		ec.setVariable("B_alias", secondary);
		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B_alias", "1", false, "C3"))
			.processInstruction(ec);
		assertEquals("an ExecutionContext alias shares the same coordinator cache", 4,
			secondaryWorker.batches.size());

		MatrixObject separateObject = matrix("B_copy", 4, 1, null);
		separateObject.setFedMapping(secondaryMap);
		ec.setVariable("B_copy", separateObject);
		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B_copy", "1", false, "C4"))
			.processInstruction(ec);
		assertTrue(separateObject.isCached(true));
		assertEquals("an equal mapping on a distinct MatrixObject owns a separate cold collection", 6,
			secondaryWorker.batches.size());
		assertTypes(secondaryWorker.batches.get(4), RequestType.EXEC_INST, RequestType.GET_VAR,
			RequestType.EXEC_INST);
		assertTypes(secondaryWorker.batches.get(5), RequestType.GET_VAR);
		assertEquals(8, primaryWorker.batches.size());
		for(int mainBatch : List.of(1, 3, 5, 7))
			assertEquals(1, putCount(primaryWorker.batches.get(mainBatch)));
	}

	@Test
	public void alignedFederatedWeightsStayNativeWithoutCollectionOrBroadcast() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData primaryWorker = new RecordingData(90, new MatrixBlock(4, 1, 1));
		RecordingData weightsWorker = new RecordingData(92, new MatrixBlock(4, 1, 1));
		MatrixObject primary = matrix("A", 4, 1, null);
		primary.setFedMapping(oneWorkerMap(89, primaryWorker, FType.ROW, 4, 1));
		MatrixObject weights = matrix("W", 4, 1, null);
		weights.setFedMapping(oneWorkerMap(91, weightsWorker, FType.ROW, 4, 1));
		ec.setVariable("A", primary);
		ec.setVariable("B", localBins());
		ec.setVariable("W", weights);
		setOutputs(ec, "C");

		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B", "W", true, "C"))
			.processInstruction(ec);

		assertTrue(primary.getFedMapping().isAligned(weights.getFedMapping(), AlignType.FULL));
		assertFalse("aligned weights remain uncached on the coordinator", weights.isCached(true));
		assertTrue("aligned weights use their native worker id", weightsWorker.batches.isEmpty());
		assertEquals(2, primaryWorker.batches.size());
		assertTypes(primaryWorker.batches.get(1), RequestType.PUT_VAR, RequestType.EXEC_INST,
			RequestType.GET_VAR, RequestType.EXEC_INST);
		assertEquals(1, putCount(primaryWorker.batches.get(1)));
	}

	@Test
	public void nonAlignedFederatedWeightsCollectOnceAndBroadcastEveryUse() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData primaryWorker = new RecordingData(94, new MatrixBlock(4, 1, 1), 21000);
		RecordingData weightsWorker = new RecordingData(96, new MatrixBlock(4, 1, 3), 22000);
		MatrixObject primary = matrix("A", 4, 1, null);
		primary.setFedMapping(oneWorkerMap(93, primaryWorker, FType.ROW, 4, 1));
		MatrixObject weights = matrix("W", 4, 1, null);
		weights.setFedMapping(oneWorkerMap(95, weightsWorker, FType.ROW, 4, 1));
		ec.setVariable("A", primary);
		ec.setVariable("B", localBins());
		ec.setVariable("W", weights);
		setOutputs(ec, "C1", "C2");

		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B", "W", true, "C1"))
			.processInstruction(ec);
		assertFalse(primary.getFedMapping().isAligned(weights.getFedMapping(), AlignType.FULL));
		assertTrue(weights.isCached(true));
		assertEquals(1, weightsWorker.batches.size());
		assertTypes(weightsWorker.batches.get(0), RequestType.GET_VAR);
		assertEquals(2, putCount(primaryWorker.batches.get(1)));
		MatrixBlock weightBroadcast = putBlock(primaryWorker.batches.get(1), 1);
		assertEquals(4, weightBroadcast.getNumRows());
		assertEquals(512 + weightBroadcast.getExactSerializedSize(),
			putRequest(primaryWorker.batches.get(1), 1).estimateSerializationBufferSize());

		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B", "W", true, "C2"))
			.processInstruction(ec);
		assertEquals("warm weights skip collection", 1, weightsWorker.batches.size());
		assertEquals("weights are still uploaded for each CTABLE execution", 2,
			putCount(primaryWorker.batches.get(3)));
	}

	@Test
	public void localSecondInputAndWeightsOnlyBroadcastTheirSlices() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData primaryWorker = new RecordingData(98, new MatrixBlock(4, 1, 1));
		MatrixObject primary = matrix("A", 4, 1, null);
		primary.setFedMapping(oneWorkerMap(97, primaryWorker, FType.ROW, 4, 1));
		ec.setVariable("A", primary);
		ec.setVariable("B", localBins());
		ec.setVariable("W", matrix("W", 4, 1, new MatrixBlock(4, 1, 2)));
		setOutputs(ec, "C");

		CtableFEDInstruction.parseInstruction(ctableInstruction("A", "B", "W", true, "C"))
			.processInstruction(ec);

		assertEquals(2, primaryWorker.batches.size());
		assertEquals(2, putCount(primaryWorker.batches.get(1)));
		assertEquals(4, putBlock(primaryWorker.batches.get(1), 0).getNumRows());
		assertEquals(4, putBlock(primaryWorker.batches.get(1), 1).getNumRows());
	}

	@Test
	public void reshapeSendsSchemaPutInASeparateBatch() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingMap map = recordingMap(72, FType.FULL, 2, 3);
		MatrixObject input = matrix("X", 2, 3, new MatrixBlock(2, 3, 1));
		input.setFedMapping(map);
		ec.setVariable("X", input);
		ec.setVariable("rows", new IntObject(3));
		ec.setVariable("cols", new IntObject(2));
		ec.setVariable("dims", new IntObject(0));
		ec.setVariable("byrow", new BooleanObject(true));
		ec.setVariable("Y", matrix("Y", 3, 2, null));

		ReshapeFEDInstruction.parseInstruction(reshapeInstruction()).processInstruction(ec);

		assertEquals(2, map.batches.size());
		assertTypes(map.batches.get(0), RequestType.PUT_VAR);
		FederatedRequest schemaPut = map.batches.get(0).get(0);
		assertFalse(Arrays.stream(new Object[] {schemaPut.getParam(0), schemaPut.getParam(1)})
			.anyMatch(CacheBlock.class::isInstance));
		assertEquals(512, schemaPut.estimateSerializationBufferSize());
		assertTypes(map.batches.get(1), RequestType.EXEC_INST);
		assertEquals(FType.ROW, ec.getMatrixObject("Y").getFedMapping().getType());
	}

	@Test
	public void forcedFoutTsmmCollectsColdInputThenBroadcastsFullMatrix() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData first = new RecordingData(81, new MatrixBlock(2, 2, 1));
		RecordingData second = new RecordingData(82, new MatrixBlock(2, 2, 2));
		FederationMap map = new FederationMap(80, List.of(
			Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {2, 2}), first),
			Pair.of(new FederatedRange(new long[] {2, 0}, new long[] {4, 2}), second)), FType.ROW);
		MatrixObject input = matrix("X", 4, 2, null);
		input.setFedMapping(map);
		ec.setVariable("X", input);
		ec.setVariable("Y", matrix("Y", 2, 2, null));

		TsmmFEDInstruction.parseInstruction(tsmmInstruction()).processInstruction(ec);

		for(RecordingData worker : List.of(first, second)) {
			assertEquals(2, worker.batches.size());
			assertTypes(worker.batches.get(0), RequestType.GET_VAR);
			assertTypes(worker.batches.get(1), RequestType.PUT_VAR, RequestType.EXEC_INST);
			MatrixBlock broadcast = (MatrixBlock) worker.batches.get(1).get(0).getParam(0);
			assertEquals(4, broadcast.getNumRows());
			assertEquals(2, broadcast.getNumColumns());
			assertEquals(512 + broadcast.getExactSerializedSize(),
				worker.batches.get(1).get(0).estimateSerializationBufferSize());
		}
		assertEquals(FType.BROADCAST, ec.getMatrixObject("Y").getFedMapping().getType());
	}

	@Test
	public void forcedFoutTsmmSkipsCollectionWhenInputIsCoordinatorCached() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		RecordingData worker = new RecordingData(84, new MatrixBlock(4, 2, 1));
		FederationMap map = new FederationMap(83, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {4, 2}), worker)), FType.ROW);
		MatrixObject input = matrix("X", 4, 2, new MatrixBlock(4, 2, 1));
		input.setFedMapping(map);
		ec.setVariable("X", input);
		ec.setVariable("Y", matrix("Y", 2, 2, null));

		TsmmFEDInstruction.parseInstruction(tsmmInstruction()).processInstruction(ec);

		assertEquals(1, worker.batches.size());
		assertTypes(worker.batches.get(0), RequestType.PUT_VAR, RequestType.EXEC_INST);
	}

	private static String ctableInstruction() {
		return ctableInstruction(FederatedOutput.LOUT);
	}

	private static String ctableInstruction(FederatedOutput output) {
		return ctableInstruction("A", "B", "1", false, "C", output);
	}

	private static String ctableInstruction(String first, String second, String weight,
		boolean matrixWeight, String output) {
		return ctableInstruction(first, second, weight, matrixWeight, output, FederatedOutput.LOUT);
	}

	private static String ctableInstruction(String first, String second, String weight,
		boolean matrixWeight, String output, FederatedOutput federatedOutput) {
		return InstructionUtils.concatOperands("FED", "ctable",
			InstructionUtils.concatOperandParts(first, DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts(second, DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts(weight,
				matrixWeight ? DataType.MATRIX.name() : DataType.SCALAR.name(),
				matrixWeight ? ValueType.FP64.name() : ValueType.INT64.name(),
				Boolean.toString(!matrixWeight)),
			"rows" + Instruction.LITERAL_PREFIX + "false",
			"cols" + Instruction.LITERAL_PREFIX + "false",
			InstructionUtils.concatOperandParts(output, DataType.MATRIX.name(), ValueType.FP64.name()),
			"false", "8", federatedOutput.name());
	}

	private static String reshapeInstruction() {
		return InstructionUtils.concatOperands("FED", "rshape",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("rows", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("cols", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("dims", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("byrow", DataType.SCALAR.name(), ValueType.BOOLEAN.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			FederatedOutput.FOUT.name());
	}

	private static String tsmmInstruction() {
		return InstructionUtils.concatOperands("FED", "tsmm",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			"LEFT", "1", FederatedOutput.FOUT.name());
	}

	private static MatrixObject matrix(String name, long rows, long cols, MatrixBlock block) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, name,
			new MetaDataFormat(new MatrixCharacteristics(rows, cols, 1024,
				block == null ? -1 : block.getNonZeros()), FileFormat.BINARY));
		if(block != null) {
			matrix.acquireModify(block);
			matrix.release();
		}
		return matrix;
	}

	private static MatrixObject localBins() {
		MatrixBlock bins = new MatrixBlock(4, 1, false);
		bins.set(0, 0, 1);
		bins.set(1, 0, 1);
		bins.set(2, 0, 2);
		bins.set(3, 0, 2);
		return matrix("B", 4, 1, bins);
	}

	private static void setOutputs(ExecutionContext ec, String... names) {
		for(String name : names)
			ec.setVariable(name, matrix(name, 2, 2, null));
	}

	private static FederationMap oneWorkerMap(long id, RecordingData data, FType type,
		long rows, long cols) {
		return new FederationMap(id, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {rows, cols}), data)), type);
	}

	private static int putCount(List<FederatedRequest> requests) {
		return (int)requests.stream().filter(request -> request.getType() == RequestType.PUT_VAR).count();
	}

	private static MatrixBlock putBlock(List<FederatedRequest> requests, int index) {
		return (MatrixBlock)putRequest(requests, index).getParam(0);
	}

	private static FederatedRequest putRequest(List<FederatedRequest> requests, int index) {
		return requests.stream().filter(request -> request.getType() == RequestType.PUT_VAR)
			.skip(index).findFirst().orElseThrow();
	}

	private static RecordingMap recordingMap(long id, FType type, long rows, long cols) {
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {rows, cols});
		FederatedData data = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", (int)(19000 + id)), "dummy", id);
		return new RecordingMap(id, List.of(Pair.of(range, data)), type);
	}

	private static void assertTypes(List<FederatedRequest> requests, RequestType... expected) {
		assertEquals(Arrays.asList(expected), requests.stream().map(FederatedRequest::getType).toList());
	}

	private static final class RecordingMap extends FederationMap {
		private final List<List<FederatedRequest>> batches = new ArrayList<>();

		private RecordingMap(long id, List<Pair<FederatedRange, FederatedData>> entries, FType type) {
			super(id, entries, type);
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			return record(requests);
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] slices,
			FederatedRequest... requests) {
			FederatedRequest[] combined = new FederatedRequest[(slices == null ? 0 : slices.length) + requests.length];
			int position = 0;
			if(slices != null)
				for(FederatedRequest request : slices)
					combined[position++] = request;
			for(FederatedRequest request : requests)
				combined[position++] = request;
			return record(combined);
		}

		@SuppressWarnings("unchecked")
		private Future<FederatedResponse>[] record(FederatedRequest... requests) {
			batches.add(List.copyOf(Arrays.asList(requests)));
			boolean maximum = Arrays.stream(requests)
				.filter(request -> request.getType() == RequestType.EXEC_INST)
				.map(request -> String.valueOf(request.getParam(0)))
				.anyMatch(instruction -> instruction.contains("uamax"));
			Object response = maximum ? new IntObject(2)
				: Arrays.stream(requests).anyMatch(request -> request.getType() == RequestType.GET_VAR)
					? new MatrixBlock(2, 2, 0) : Long.valueOf(0);
			return new Future[] {CompletableFuture.completedFuture(new FederatedResponse(
				FederatedResponse.ResponseType.SUCCESS, response))};
		}
	}

	private static final class RecordingData extends FederatedData {
		private final MatrixBlock partition;
		private final List<List<FederatedRequest>> batches;

		private RecordingData(long id, MatrixBlock partition) {
			this(id, partition, 21000);
		}

		private RecordingData(long id, MatrixBlock partition, int port) {
			this(id, partition, new ArrayList<>(), port);
		}

		private RecordingData(long id, MatrixBlock partition, List<List<FederatedRequest>> batches, int port) {
			super(DataType.MATRIX, new InetSocketAddress("localhost", port), "dummy", id);
			this.partition = partition;
			this.batches = batches;
		}

		@Override
		public FederatedData copyWithNewID(long varID) {
			return new RecordingData(varID, partition, batches, getAddress().getPort());
		}

		@Override
		public Future<FederatedResponse> executeFederatedOperation(FederatedRequest... requests) {
			batches.add(List.copyOf(Arrays.asList(requests)));
			boolean maximum = Arrays.stream(requests)
				.filter(request -> request.getType() == RequestType.EXEC_INST)
				.map(request -> String.valueOf(request.getParam(0)))
				.anyMatch(instruction -> instruction.contains("uamax"));
			boolean get = Arrays.stream(requests).anyMatch(request -> request.getType() == RequestType.GET_VAR);
			Object response = maximum ? new IntObject(2)
				: requests.length == 1 && get ? partition
					: get ? new MatrixBlock(2, 2, 0) : Long.valueOf(0);
			return CompletableFuture.completedFuture(new FederatedResponse(
				FederatedResponse.ResponseType.SUCCESS, response));
		}
	}
}
