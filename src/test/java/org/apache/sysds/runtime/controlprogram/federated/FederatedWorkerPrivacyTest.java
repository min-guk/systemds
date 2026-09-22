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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.AggregateUnaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.Data.WorkerPrivacyLevel;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
import org.apache.sysds.runtime.instructions.cp.ListObject;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.lineage.LineageCacheConfig;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.ReuseCacheType;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Test;

public class FederatedWorkerPrivacyTest {
	private static final AtomicLong NEXT_ID = new AtomicLong(910_000);

	@Test
	public void workerReadLabelsComeFromSidecar() throws Exception {
		Path dir = Files.createTempDirectory("worker-privacy-");
		Path data = dir.resolve("X");
		Files.writeString(data.resolveSibling("X.mtd"), metadata("private-aggregate"));
		MatrixObject object = new MatrixObject(ValueType.FP64, data.toString());

		FederatedWorkerPrivacy.attachReadLabel(object, data.toString());

		assertEquals(WorkerPrivacyLevel.PRIVATE_AGGREGATE, object.getWorkerPrivacyLevel());
	}

	@Test
	public void publicSidecarAllowsRawRelease() throws Exception {
		Path dir = Files.createTempDirectory("worker-privacy-");
		Path data = dir.resolve("X");
		Files.writeString(data.resolveSibling("X.mtd"), metadata("public"));
		MatrixObject object = new MatrixObject(ValueType.FP64, data.toString());
		FederatedWorkerPrivacy.attachReadLabel(object, data.toString());

		FederatedWorkerPrivacy.validateRawRelease(object);
	}

	@Test
	public void sidecarWithoutExplicitPrivacyIsUnknownAndCannotBeReleased() throws Exception {
		Path dir = Files.createTempDirectory("worker-privacy-");
		Path data = dir.resolve("X");
		Files.writeString(data.resolveSibling("X.mtd"), metadataWithoutPrivacy());
		MatrixObject object = new MatrixObject(ValueType.FP64, data.toString());

		FederatedWorkerPrivacy.attachReadLabel(object, data.toString());

		assertEquals(WorkerPrivacyLevel.UNKNOWN, object.getWorkerPrivacyLevel());
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateRawRelease(object));
	}

	@Test
	public void protectedAndUnknownRawValuesAreDeniedRecursively() {
		for(WorkerPrivacyLevel level : List.of(WorkerPrivacyLevel.PRIVATE_AGGREGATE,
			WorkerPrivacyLevel.PRIVATE, WorkerPrivacyLevel.UNKNOWN)) {
			DoubleObject value = new DoubleObject(7);
			value.setWorkerPrivacyLevel(level);
			assertThrows(FederatedWorkerHandlerException.class,
				() -> FederatedWorkerPrivacy.validateRawRelease(value));
		}

		DoubleObject child = new DoubleObject(7);
		child.setWorkerPrivacyLevel(WorkerPrivacyLevel.PRIVATE);
		ListObject list = new ListObject(List.of(child));
		list.setWorkerPrivacyLevel(WorkerPrivacyLevel.PUBLIC);
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateRawRelease(list));
	}

	@Test
	public void fullSumReleasesPrivateAggregateButNotPrivate() {
		assertEquals(WorkerPrivacyLevel.PUBLIC, propagateFullSum(WorkerPrivacyLevel.PRIVATE_AGGREGATE));
		assertEquals(WorkerPrivacyLevel.PRIVATE, propagateFullSum(WorkerPrivacyLevel.PRIVATE));
		assertEquals(WorkerPrivacyLevel.UNKNOWN, propagateFullSum(WorkerPrivacyLevel.UNKNOWN));
	}

	@Test
	public void protectedUdfInputsFailClosed() {
		DoubleObject protectedInput = new DoubleObject(7);
		protectedInput.setWorkerPrivacyLevel(WorkerPrivacyLevel.PRIVATE_AGGREGATE);
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateUdfInputs(null,
				new DoubleObject[] {protectedInput}));
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateUdfInputs(null, new DoubleObject[0]));
		DoubleObject publicInput = new DoubleObject(7);
		publicInput.setWorkerPrivacyLevel(WorkerPrivacyLevel.PUBLIC);
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateUdfInputs(null, new DoubleObject[] {publicInput}));
		FederatedWorkerPrivacy.validateUdfInputs(new FederatedData.GetPrivacyConstraints("unused"),
			new DoubleObject[0]);
	}

	@Test
	public void putCannotLaunderExplicitProtectedOrUnknownLabels() {
		for(WorkerPrivacyLevel level : List.of(WorkerPrivacyLevel.PRIVATE_AGGREGATE,
			WorkerPrivacyLevel.PRIVATE, WorkerPrivacyLevel.UNKNOWN)) {
			long id = NEXT_ID.incrementAndGet();
			DoubleObject value = new DoubleObject(7);
			value.setWorkerPrivacyLevel(level);
			FederatedResponse response = execute(new FederatedRequest(RequestType.PUT_VAR, id, value),
				new FederatedRequest(RequestType.GET_VAR, id));
			assertEquals(false, response.isSuccessful());
		}

		long publicId = NEXT_ID.incrementAndGet();
		DoubleObject publicValue = new DoubleObject(7);
		publicValue.setWorkerPrivacyLevel(WorkerPrivacyLevel.PUBLIC);
		FederatedResponse publicResponse = execute(new FederatedRequest(RequestType.PUT_VAR, publicId, publicValue),
			new FederatedRequest(RequestType.GET_VAR, publicId));
		assertEquals(true, publicResponse.isSuccessful());

		DoubleObject nestedPrivate = new DoubleObject(9);
		nestedPrivate.setWorkerPrivacyLevel(WorkerPrivacyLevel.PRIVATE);
		ListObject nested = new ListObject(List.of(nestedPrivate));
		long listId = NEXT_ID.incrementAndGet();
		FederatedResponse nestedResponse = execute(new FederatedRequest(RequestType.PUT_VAR, listId, nested),
			new FederatedRequest(RequestType.GET_VAR, listId));
		assertEquals(false, nestedResponse.isSuccessful());
		assertEquals(WorkerPrivacyLevel.PRIVATE, nestedPrivate.getWorkerPrivacyLevel());
	}

	@Test
	public void serializedListIngressIsRejectedWhenWireLabelsAreLost() throws Exception {
		DoubleObject child = new DoubleObject(9);
		child.setWorkerPrivacyLevel(WorkerPrivacyLevel.PRIVATE);
		ListObject original = new ListObject(List.of(child));
		original.setWorkerPrivacyLevel(WorkerPrivacyLevel.PRIVATE);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try(ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(original);
		}
		ListObject roundTrip;
		try(ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			roundTrip = (ListObject) input.readObject();
		}

		assertEquals(WorkerPrivacyLevel.UNKNOWN, roundTrip.getWorkerPrivacyLevel());
		assertEquals(WorkerPrivacyLevel.UNKNOWN, roundTrip.getData(0).getWorkerPrivacyLevel());
		FederatedResponse response = execute(new FederatedRequest(RequestType.PUT_VAR,
			NEXT_ID.incrementAndGet(), roundTrip));
		assertEquals(false, response.isSuccessful());
	}

	@Test
	public void handlerDeniesRawPrivateAggregateAndPrivateReads() throws Exception {
		for(String privacy : List.of("private-aggregate", "private")) {
			Path data = writeCsv(privacy);
			long id = NEXT_ID.incrementAndGet();
			FederatedResponse response = execute(new FederatedRequest(RequestType.READ_VAR, id,
				data.toString(), DataType.MATRIX.name()), new FederatedRequest(RequestType.GET_VAR, id));
			assertEquals(false, response.isSuccessful());
		}
	}

	@Test
	public void handlerAllowsPublicRawReadAndPrivateAggregateFullSum() throws Exception {
		Path publicData = writeCsv("public");
		long publicId = NEXT_ID.incrementAndGet();
		FederatedResponse publicResponse = execute(new FederatedRequest(RequestType.READ_VAR, publicId,
			publicData.toString(), DataType.MATRIX.name()), new FederatedRequest(RequestType.GET_VAR, publicId));
		assertEquals(true, publicResponse.isSuccessful());

		Path aggregateData = writeCsv("private-aggregate");
		long inputId = NEXT_ID.incrementAndGet();
		long outputId = NEXT_ID.incrementAndGet();
		String sum = InstructionUtils.concatOperands("CP", "uak+",
			InstructionUtils.concatOperandParts(String.valueOf(inputId), DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts(String.valueOf(outputId), DataType.SCALAR.name(), ValueType.FP64.name()), "1");
		FederatedResponse sumResponse = execute(new FederatedRequest(RequestType.READ_VAR, inputId,
			aggregateData.toString(), DataType.MATRIX.name()),
			new FederatedRequest(RequestType.EXEC_INST, outputId, sum),
			new FederatedRequest(RequestType.GET_VAR, outputId));
		assertEquals(true, sumResponse.isSuccessful());
		assertEquals(10d, ((DoubleObject)sumResponse.getData()[0]).getDoubleValue(), 0d);

		Path privateData = writeCsv("private");
		long privateInputId = NEXT_ID.incrementAndGet();
		long privateOutputId = NEXT_ID.incrementAndGet();
		String privateSum = InstructionUtils.concatOperands("CP", "uak+",
			InstructionUtils.concatOperandParts(String.valueOf(privateInputId), DataType.MATRIX.name(),
				ValueType.FP64.name()),
			InstructionUtils.concatOperandParts(String.valueOf(privateOutputId), DataType.SCALAR.name(),
				ValueType.FP64.name()), "1");
		FederatedResponse privateSumResponse = execute(new FederatedRequest(RequestType.READ_VAR, privateInputId,
			privateData.toString(), DataType.MATRIX.name()),
			new FederatedRequest(RequestType.EXEC_INST, privateOutputId, privateSum),
			new FederatedRequest(RequestType.GET_VAR, privateOutputId));
		assertEquals(false, privateSumResponse.isSuccessful());
	}

	@Test
	public void parameterizedBuiltinCannotBypassUndeclaredInputTracking() throws Exception {
		Path aggregateData = writeCsv("private-aggregate");
		long inputId = NEXT_ID.incrementAndGet();
		long outputId = NEXT_ID.incrementAndGet();
		String toString = InstructionUtils.concatOperands("CP", "toString", "target=" + inputId,
			InstructionUtils.concatOperandParts(String.valueOf(outputId), DataType.SCALAR.name(),
				ValueType.STRING.name()));
		FederatedResponse response = execute(new FederatedRequest(RequestType.READ_VAR, inputId,
			aggregateData.toString(), DataType.MATRIX.name()),
			new FederatedRequest(RequestType.EXEC_INST, outputId, toString),
			new FederatedRequest(RequestType.GET_VAR, outputId));
		assertEquals(false, response.isSuccessful());
	}

	@Test
	public void unsupportedInstructionIsDeniedEvenWithOnlyPublicState() {
		long inputId = NEXT_ID.incrementAndGet();
		long outputId = NEXT_ID.incrementAndGet();
		String toString = InstructionUtils.concatOperands("CP", "toString", "target=" + inputId,
			InstructionUtils.concatOperandParts(String.valueOf(outputId), DataType.SCALAR.name(),
				ValueType.STRING.name()));
		FederatedResponse response = execute(new FederatedRequest(RequestType.PUT_VAR, inputId,
			new MatrixBlock(1, 1, 7)), new FederatedRequest(RequestType.EXEC_INST, outputId, toString));

		assertEquals(false, response.isSuccessful());
	}

	@Test
	public void literalFlagCannotLaunderProtectedCopySource() throws Exception {
		Path privateData = writeCsv("private");
		long inputId = NEXT_ID.incrementAndGet();
		long outputId = NEXT_ID.incrementAndGet();
		String copy = InstructionUtils.concatOperands("CP", "cpvar",
			InstructionUtils.concatOperandParts(String.valueOf(inputId), DataType.MATRIX.name(),
				ValueType.FP64.name(), "true"),
			InstructionUtils.concatOperandParts(String.valueOf(outputId), DataType.MATRIX.name(),
				ValueType.FP64.name()));
		FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null);

		FederatedResponse execution = execute(handler, new FederatedRequest(RequestType.READ_VAR, inputId,
			privateData.toString(), DataType.MATRIX.name()), new FederatedRequest(RequestType.EXEC_INST, outputId, copy));
		assertEquals(false, execution.isSuccessful());
		assertEquals(false, execute(handler, new FederatedRequest(RequestType.GET_VAR, inputId)).isSuccessful());
		assertEquals(false, execute(handler, new FederatedRequest(RequestType.GET_VAR, outputId)).isSuccessful());
	}

	@Test
	public void literalFlagCannotLaunderProtectedAggregateSource() throws Exception {
		Path aggregateData = writeCsv("private-aggregate");
		long inputId = NEXT_ID.incrementAndGet();
		long outputId = NEXT_ID.incrementAndGet();
		String sum = InstructionUtils.concatOperands("CP", "uak+",
			InstructionUtils.concatOperandParts(String.valueOf(inputId), DataType.MATRIX.name(),
				ValueType.FP64.name(), "true"),
			InstructionUtils.concatOperandParts(String.valueOf(outputId), DataType.SCALAR.name(),
				ValueType.FP64.name()), "1");

		FederatedResponse response = execute(new FederatedRequest(RequestType.READ_VAR, inputId,
			aggregateData.toString(), DataType.MATRIX.name()),
			new FederatedRequest(RequestType.EXEC_INST, outputId, sum),
			new FederatedRequest(RequestType.GET_VAR, outputId));
		assertEquals(false, response.isSuccessful());
	}

	@Test
	public void multiReturnCannotOverwritePublicAliasFromProtectedInput() throws Exception {
		Path privateData = writeCsv("private");
		long inputId = NEXT_ID.incrementAndGet();
		long qId = NEXT_ID.incrementAndGet();
		long rId = NEXT_ID.incrementAndGet();
		String qr = InstructionUtils.concatOperands("CP", "qr", String.valueOf(inputId), String.valueOf(qId),
			String.valueOf(rId), "1");
		FederatedResponse response = execute(new FederatedRequest(RequestType.PUT_VAR, rId,
			new MatrixBlock(2, 2, 1)), new FederatedRequest(RequestType.READ_VAR, inputId,
			privateData.toString(), DataType.MATRIX.name()), new FederatedRequest(RequestType.EXEC_INST, qId, qr));
		assertEquals(false, response.isSuccessful());
	}

	@Test
	public void failedReadCachePrivacyRevalidationQuarantinesCachedAliases() throws Exception {
		Path publicData = writeCsv("public");
		FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null);
		long firstId = NEXT_ID.incrementAndGet();
		FederatedResponse first = execute(handler, new FederatedRequest(RequestType.READ_VAR, firstId,
			publicData.toString(), DataType.MATRIX.name()), new FederatedRequest(RequestType.GET_VAR, firstId));
		assertEquals(true, first.isSuccessful());

		Files.delete(publicData.resolveSibling("X.csv.mtd"));
		long secondId = NEXT_ID.incrementAndGet();
		FederatedResponse failedRead = execute(handler, new FederatedRequest(RequestType.READ_VAR, secondId,
			publicData.toString(), DataType.MATRIX.name()));
		assertEquals(false, failedRead.isSuccessful());
		FederatedResponse oldAlias = execute(handler, new FederatedRequest(RequestType.GET_VAR, firstId));
		assertEquals(false, oldAlias.isSuccessful());
	}

	@Test
	public void failedReadPrivacyRevalidationQuarantinesEcAliasWithoutCaches() throws Exception {
		DMLConfig config = ConfigurationManager.getDMLConfig();
		String oldReadCache = config.getTextValue(DMLConfig.FEDERATED_READCACHE);
		ReuseCacheType oldReuse = LineageCacheConfig.getCacheType();
		config.setTextValue(DMLConfig.FEDERATED_READCACHE, "false");
		LineageCacheConfig.setConfig(ReuseCacheType.NONE);
		try {
			Path publicData = writeCsv("public");
			FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
				new FederatedReadCache(), null);
			long firstId = NEXT_ID.incrementAndGet();
			FederatedRequest firstRead = new FederatedRequest(RequestType.READ_VAR, firstId,
				publicData.toString(), DataType.MATRIX.name());
			FederatedRequest firstGet = new FederatedRequest(RequestType.GET_VAR, firstId);
			firstRead.setTID(11);
			firstGet.setTID(11);
			FederatedResponse first = execute(handler, firstRead, firstGet);
			assertEquals(true, first.isSuccessful());

			Files.delete(publicData.resolveSibling("X.csv.mtd"));
			long secondId = NEXT_ID.incrementAndGet();
			FederatedRequest secondRead = new FederatedRequest(RequestType.READ_VAR, secondId,
				publicData.toString(), DataType.MATRIX.name());
			secondRead.setTID(12);
			FederatedResponse failedRead = execute(handler, secondRead);
			assertEquals(false, failedRead.isSuccessful());
			FederatedRequest oldAliasGet = new FederatedRequest(RequestType.GET_VAR, firstId);
			oldAliasGet.setTID(11);
			assertEquals(false, execute(handler, oldAliasGet).isSuccessful());
		}
		finally {
			LineageCacheConfig.setConfig(oldReuse);
			config.setTextValue(DMLConfig.FEDERATED_READCACHE, oldReadCache);
		}
	}

	@Test
	public void liveWorkerRawAndAggregateControls() throws Exception {
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1_000);
		try {
			InetSocketAddress address = new InetSocketAddress("localhost", port);
			Path aggregateData = writeCsv("private-aggregate");
			long rawId = NEXT_ID.incrementAndGet();
			FederatedResponse raw = execute(address, new FederatedRequest(RequestType.READ_VAR, rawId,
				aggregateData.toString(), DataType.MATRIX.name()), new FederatedRequest(RequestType.GET_VAR, rawId));
			assertEquals(false, raw.isSuccessful());

			long sumInputId = NEXT_ID.incrementAndGet();
			long sumOutputId = NEXT_ID.incrementAndGet();
			String sum = InstructionUtils.concatOperands("CP", "uak+",
				InstructionUtils.concatOperandParts(String.valueOf(sumInputId), DataType.MATRIX.name(),
					ValueType.FP64.name()),
				InstructionUtils.concatOperandParts(String.valueOf(sumOutputId), DataType.SCALAR.name(),
					ValueType.FP64.name()), "1");
			FederatedResponse aggregate = execute(address, new FederatedRequest(RequestType.READ_VAR, sumInputId,
				aggregateData.toString(), DataType.MATRIX.name()),
				new FederatedRequest(RequestType.EXEC_INST, sumOutputId, sum),
				new FederatedRequest(RequestType.GET_VAR, sumOutputId));
			assertEquals(true, aggregate.isSuccessful());
			assertEquals(10d, ((DoubleObject)aggregate.getData()[0]).getDoubleValue(), 0d);

			Path publicData = writeCsv("public");
			long publicId = NEXT_ID.incrementAndGet();
			FederatedResponse publicRaw = execute(address, new FederatedRequest(RequestType.READ_VAR, publicId,
				publicData.toString(), DataType.MATRIX.name()), new FederatedRequest(RequestType.GET_VAR, publicId));
			assertEquals(true, publicRaw.isSuccessful());
		}
		finally {
			TestUtils.shutdownThread(worker);
		}
	}

	private static WorkerPrivacyLevel propagateFullSum(WorkerPrivacyLevel inputLevel) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = new MatrixObject(ValueType.FP64, "X");
		input.setWorkerPrivacyLevel(inputLevel);
		ec.setVariable("X", input);
		ec.setVariable("sum", new DoubleObject(3));
		String instruction = InstructionUtils.concatOperands("CP", "uak+",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("sum", DataType.SCALAR.name(), ValueType.FP64.name()), "1");
		Instruction parsed = AggregateUnaryCPInstruction.parseInstruction(instruction);
		WorkerPrivacyLevel inferred = FederatedWorkerPrivacy.inferInstructionOutput(ec, parsed);
		FederatedWorkerPrivacy.applyInstructionOutput(ec, parsed, inferred);
		return ec.getVariable("sum").getWorkerPrivacyLevel();
	}

	private static String metadata(String privacy) {
		return "{\"data_type\":\"matrix\",\"value_type\":\"double\",\"rows\":2,\"cols\":2,"
			+ "\"nnz\":4,\"format\":\"csv\",\"header\":false,\"sep\":\",\",\"privacy\":\""
			+ privacy + "\"}";
	}

	private static String metadataWithoutPrivacy() {
		return "{\"data_type\":\"matrix\",\"value_type\":\"double\",\"rows\":2,\"cols\":2,"
			+ "\"nnz\":4,\"format\":\"csv\",\"header\":false,\"sep\":\",\"}";
	}

	private static Path writeCsv(String privacy) throws Exception {
		Path dir = Files.createTempDirectory("worker-privacy-handler-");
		Path data = dir.resolve("X.csv");
		Files.writeString(data, "1,2\n3,4\n");
		Files.writeString(data.resolveSibling("X.csv.mtd"), metadata(privacy));
		return data;
	}

	private static FederatedResponse execute(FederatedRequest... requests) {
		FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null);
		return execute(handler, requests);
	}

	private static FederatedResponse execute(FederatedWorkerHandler handler, FederatedRequest... requests) {
		return handler.createResponse(requests);
	}

	private static FederatedResponse execute(InetSocketAddress address, FederatedRequest... requests) throws Exception {
		return FederatedData.executeFederatedOperation(address, requests).get(10, TimeUnit.SECONDS);
	}
}
