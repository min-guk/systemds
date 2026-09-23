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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject.UpdateType;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.AggregateUnaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.Data.WorkerPrivacyLevel;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
import org.apache.sysds.runtime.instructions.cp.ListObject;
import org.apache.sysds.runtime.instructions.cp.StringObject;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.lineage.LineageCacheConfig;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.ReuseCacheType;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
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
	public void fullSumRemainsProtectedUntilSuccessfulReleaseApplication() {
		ExecutionContext ec = matrixContext("X", WorkerPrivacyLevel.PRIVATE_AGGREGATE);
		ec.setVariable("sum", scalar(0, WorkerPrivacyLevel.PUBLIC));
		String instruction = InstructionUtils.concatOperands("CP", "uak+",
			operand("X", DataType.MATRIX, ValueType.FP64),
			operand("sum", DataType.SCALAR, ValueType.FP64), "1");
		Instruction parsed = InstructionParser.parseSingleInstruction(instruction);
		WorkerPrivacyLevel executionLabel = FederatedWorkerPrivacy.inferInstructionOutput(ec, parsed);
		assertEquals(WorkerPrivacyLevel.PRIVATE_AGGREGATE, executionLabel);
		FederatedWorkerPrivacy.protectInstructionAliases(ec, parsed, executionLabel);
		assertEquals(WorkerPrivacyLevel.PRIVATE_AGGREGATE,
			ec.getVariable("sum").getWorkerPrivacyLevel());
		parsed.processInstruction(ec);
		FederatedWorkerPrivacy.applyInstructionOutput(ec, parsed, executionLabel);
		assertEquals(WorkerPrivacyLevel.PUBLIC, ec.getVariable("sum").getWorkerPrivacyLevel());
	}

	@Test
	public void operationContractsPreserveLabelsUntilAnExplicitRelease() {
		for(WorkerPrivacyLevel level : WorkerPrivacyLevel.values()) {
			assertEquals(level, inferUnaryMatrixLabel("r'", level, true));
			assertEquals(level, inferUnaryMatrixLabel("rev", level, false));
			assertEquals(nonDeclassifiable(level), inferUnaryMatrixLabel("uacmax", level, true));
			assertEquals(nonDeclassifiable(level),
				inferRightIndexLabel(level, WorkerPrivacyLevel.PUBLIC));
		}
	}

	@Test
	public void indexingAccountsForEveryVariableOperandAndBound() {
		for(WorkerPrivacyLevel level : List.of(WorkerPrivacyLevel.PRIVATE_AGGREGATE,
			WorkerPrivacyLevel.PRIVATE, WorkerPrivacyLevel.UNKNOWN)) {
			assertEquals(nonDeclassifiable(level),
				inferRightIndexLabel(WorkerPrivacyLevel.PUBLIC, level));
			assertEquals(nonDeclassifiable(level), inferLeftIndexLabel(WorkerPrivacyLevel.PUBLIC, level,
				WorkerPrivacyLevel.PUBLIC));
			assertEquals(nonDeclassifiable(level), inferLeftIndexLabel(WorkerPrivacyLevel.PUBLIC,
				WorkerPrivacyLevel.PUBLIC, level));
		}
	}

	@Test
	public void nonBijectivePrivateAggregateOperationsCannotComposeIntoFullSumRelease() throws Exception {
		for(String opcode : List.of("rightIndex", "leftIndex", "uacmax")) {
			Path data = writeCsv("private-aggregate");
			long inputId = NEXT_ID.incrementAndGet();
			long intermediateId = NEXT_ID.incrementAndGet();
			long sumId = NEXT_ID.incrementAndGet();
			FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
				new FederatedReadCache(), null);
			assertEquals(true, execute(handler, new FederatedRequest(RequestType.READ_VAR, inputId,
				data.toString(), DataType.MATRIX.name())).isSuccessful());
			assertEquals("non-bijective operation must execute: " + opcode, true,
				execute(handler, new FederatedRequest(RequestType.EXEC_INST, intermediateId,
					localOperation(opcode, inputId, intermediateId))).isSuccessful());

			String sum = InstructionUtils.concatOperands("CP", "uak+",
				operand(Long.toString(intermediateId), DataType.MATRIX, ValueType.FP64),
				operand(Long.toString(sumId), DataType.SCALAR, ValueType.FP64), "1");
			assertEquals(true,
				execute(handler, new FederatedRequest(RequestType.EXEC_INST, sumId, sum)).isSuccessful());
			assertEquals("derived aggregate must remain private: " + opcode, false,
				execute(handler, new FederatedRequest(RequestType.GET_VAR, sumId)).isSuccessful());
		}
	}

	@Test
	public void bijectivePrivateAggregateReorgStillAllowsFullSumRelease() throws Exception {
		for(String opcode : List.of("r'", "rev")) {
			Path data = writeCsv("private-aggregate");
			long inputId = NEXT_ID.incrementAndGet();
			long intermediateId = NEXT_ID.incrementAndGet();
			long sumId = NEXT_ID.incrementAndGet();
			FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
				new FederatedReadCache(), null);
			assertEquals(true, execute(handler, new FederatedRequest(RequestType.READ_VAR, inputId,
				data.toString(), DataType.MATRIX.name())).isSuccessful());
			assertEquals(true, execute(handler, new FederatedRequest(RequestType.EXEC_INST, intermediateId,
				localOperation(opcode, inputId, intermediateId))).isSuccessful());

			String sum = InstructionUtils.concatOperands("CP", "uak+",
				operand(Long.toString(intermediateId), DataType.MATRIX, ValueType.FP64),
				operand(Long.toString(sumId), DataType.SCALAR, ValueType.FP64), "1");
			assertEquals(true,
				execute(handler, new FederatedRequest(RequestType.EXEC_INST, sumId, sum)).isSuccessful());
			FederatedResponse released = execute(handler, new FederatedRequest(RequestType.GET_VAR, sumId));
			assertEquals(true, released.isSuccessful());
			assertEquals(10d, ((DoubleObject) released.getData()[0]).getDoubleValue(), 0d);
		}
	}

	@Test
	public void literalFlagsCannotHideMatrixOperandsButLiteralBoundsRemainPublic() {
		ExecutionContext ec = matrixContext("X", WorkerPrivacyLevel.PRIVATE);
		String forgedTranspose = InstructionUtils.concatOperands("CP", "r'",
			operand("X", DataType.MATRIX, ValueType.FP64, true),
			operand("Y", DataType.MATRIX, ValueType.FP64), "1");
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.inferInstructionOutput(ec,
				InstructionParser.parseSingleInstruction(forgedTranspose)));

		String forgedRightIndex = InstructionUtils.concatOperands("CP", "rightIndex",
			operand("X", DataType.MATRIX, ValueType.FP64, true), literalIndex(1), literalIndex(1),
			literalIndex(1), literalIndex(1), operand("Y", DataType.MATRIX, ValueType.FP64));
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.inferInstructionOutput(ec,
				InstructionParser.parseSingleInstruction(forgedRightIndex)));

		String validRightIndex = InstructionUtils.concatOperands("CP", "rightIndex",
			operand("X", DataType.MATRIX, ValueType.FP64), literalIndex(1), literalIndex(1),
			literalIndex(1), literalIndex(1), operand("Y", DataType.MATRIX, ValueType.FP64));
		assertEquals(WorkerPrivacyLevel.PRIVATE,
			FederatedWorkerPrivacy.inferInstructionOutput(ec,
				InstructionParser.parseSingleInstruction(validRightIndex)));
	}

	@Test
	public void preExecutionProtectionCoversReusedOutputAndInPlaceAliasesOnFailure() {
		ExecutionContext ec = matrixContext("X", WorkerPrivacyLevel.PUBLIC);
		MatrixObject input = ec.getMatrixObject("X");
		input.setUpdateType(UpdateType.INPLACE);
		ec.setVariable("X_ALIAS", input);
		ec.setVariable("R", scalar(9, WorkerPrivacyLevel.PRIVATE));

		MatrixObject reusedOutput = matrix(WorkerPrivacyLevel.PUBLIC);
		ec.setVariable("Y", reusedOutput);
		ec.setVariable("Y_ALIAS", reusedOutput);
		String leftIndex = InstructionUtils.concatOperands("CP", "leftIndex",
			operand("X", DataType.MATRIX, ValueType.FP64),
			operand("R", DataType.SCALAR, ValueType.FP64), literalIndex(3), literalIndex(3),
			literalIndex(1), literalIndex(1), operand("Y", DataType.MATRIX, ValueType.FP64));
		Instruction instruction = InstructionParser.parseSingleInstruction(leftIndex);
		WorkerPrivacyLevel label = FederatedWorkerPrivacy.inferInstructionOutput(ec, instruction);
		FederatedWorkerPrivacy.protectInstructionAliases(ec, instruction, label);

		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("X").getWorkerPrivacyLevel());
		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("X_ALIAS").getWorkerPrivacyLevel());
		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("Y").getWorkerPrivacyLevel());
		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("Y_ALIAS").getWorkerPrivacyLevel());
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateRawRelease(ec.getVariable("X_ALIAS")));
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateRawRelease(ec.getVariable("Y_ALIAS")));
		assertThrows(RuntimeException.class, () -> instruction.processInstruction(ec));
		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("X_ALIAS").getWorkerPrivacyLevel());
		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("Y_ALIAS").getWorkerPrivacyLevel());
	}

	@Test
	public void successfulPartialUpdateCannotDowngradeReusedPrivateOutputAlias() {
		ExecutionContext ec = matrixContext("X", WorkerPrivacyLevel.PUBLIC);
		ec.setVariable("R", scalar(9, WorkerPrivacyLevel.PUBLIC));
		MatrixObject reusedOutput = matrix(WorkerPrivacyLevel.PRIVATE);
		ec.setVariable("Y", reusedOutput);
		ec.setVariable("Y_ALIAS", reusedOutput);
		String leftIndex = InstructionUtils.concatOperands("CP", "leftIndex",
			operand("X", DataType.MATRIX, ValueType.FP64),
			operand("R", DataType.SCALAR, ValueType.FP64), literalIndex(1), literalIndex(1),
			literalIndex(1), literalIndex(1), operand("Y", DataType.MATRIX, ValueType.FP64));
		Instruction instruction = InstructionParser.parseSingleInstruction(leftIndex);
		WorkerPrivacyLevel label = FederatedWorkerPrivacy.inferInstructionOutput(ec, instruction);
		assertEquals(WorkerPrivacyLevel.PUBLIC, label);
		FederatedWorkerPrivacy.protectInstructionAliases(ec, instruction, label);
		instruction.processInstruction(ec);
		FederatedWorkerPrivacy.applyInstructionOutput(ec, instruction, label);

		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("Y").getWorkerPrivacyLevel());
		assertEquals(WorkerPrivacyLevel.PRIVATE, ec.getVariable("Y_ALIAS").getWorkerPrivacyLevel());
		assertThrows(FederatedWorkerHandlerException.class,
			() -> FederatedWorkerPrivacy.validateRawRelease(ec.getVariable("Y_ALIAS")));
	}

	@Test
	public void rmvarCanDeleteProtectedStateWithoutReleasingIt() {
		ExecutionContext ec = matrixContext("X", WorkerPrivacyLevel.PRIVATE);
		Instruction rmvar = InstructionParser.parseSingleInstruction(
			InstructionUtils.concatOperands("CP", "rmvar", "X"));
		assertEquals(WorkerPrivacyLevel.PUBLIC,
			FederatedWorkerPrivacy.inferInstructionOutput(ec, rmvar));
		rmvar.processInstruction(ec);
		assertEquals(false, ec.containsVariable("X"));
	}

	@Test
	public void handlerExecutesAllowedProtectedLocalOperationsWithoutRawRelease() throws Exception {
		for(String opcode : List.of("r'", "rev", "uacmax", "rightIndex", "leftIndex")) {
			Path data = writeCsv("private-aggregate");
			long inputId = NEXT_ID.incrementAndGet();
			long outputId = NEXT_ID.incrementAndGet();
			FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
				new FederatedReadCache(), null);
			assertEquals(true, execute(handler, new FederatedRequest(RequestType.READ_VAR, inputId,
				data.toString(), DataType.MATRIX.name())).isSuccessful());

			String instruction = localOperation(opcode, inputId, outputId);
			assertEquals("allowed operation must execute: " + opcode, true,
				execute(handler, new FederatedRequest(RequestType.EXEC_INST, outputId, instruction)).isSuccessful());
			assertEquals("protected result must not be released: " + opcode, false,
				execute(handler, new FederatedRequest(RequestType.GET_VAR, outputId)).isSuccessful());
		}

		Path data = writeCsv("private");
		long inputId = NEXT_ID.incrementAndGet();
		FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null);
		assertEquals(true, execute(handler, new FederatedRequest(RequestType.READ_VAR, inputId,
			data.toString(), DataType.MATRIX.name())).isSuccessful());
		String rmvar = InstructionUtils.concatOperands("CP", "rmvar", Long.toString(inputId));
		assertEquals(true,
			execute(handler, new FederatedRequest(RequestType.EXEC_INST, inputId, rmvar)).isSuccessful());
		assertEquals(false,
			execute(handler, new FederatedRequest(RequestType.GET_VAR, inputId)).isSuccessful());
	}

	@Test
	public void placeholderPatchedOutputIsRejectedBeforeExistingDestinationMutation() throws Exception {
		Path data = writeCsv("private");
		long inputId = NEXT_ID.incrementAndGet();
		long selectorId = NEXT_ID.incrementAndGet();
		long destinationId = NEXT_ID.incrementAndGet();
		FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null);
		assertEquals(true, execute(handler, new FederatedRequest(RequestType.READ_VAR, inputId,
			data.toString(), DataType.MATRIX.name())).isSuccessful());
		assertEquals(true, execute(handler,
			new FederatedRequest(RequestType.PUT_VAR, selectorId,
				new StringObject(Long.toString(destinationId))),
			new FederatedRequest(RequestType.PUT_VAR, destinationId,
				new MatrixBlock(1, 1, 7d))).isSuccessful());

		String patchedOutput = Lop.VARIABLE_NAME_PLACEHOLDER + selectorId + Lop.VARIABLE_NAME_PLACEHOLDER;
		String transpose = InstructionUtils.concatOperands("CP", "r'",
			operand(Long.toString(inputId), DataType.MATRIX, ValueType.FP64),
			operand(patchedOutput, DataType.MATRIX, ValueType.FP64), "1");
		assertEquals(false, execute(handler,
			new FederatedRequest(RequestType.EXEC_INST, destinationId, transpose)).isSuccessful());

		FederatedResponse destination = execute(handler,
			new FederatedRequest(RequestType.GET_VAR, destinationId));
		assertEquals(true, destination.isSuccessful());
		MatrixBlock unchanged = (MatrixBlock) destination.getData()[0];
		assertEquals(1, unchanged.getNumRows());
		assertEquals(1, unchanged.getNumColumns());
		assertEquals(7d, unchanged.get(0, 0), 0d);
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

	private static WorkerPrivacyLevel inferUnaryMatrixLabel(String opcode, WorkerPrivacyLevel level,
		boolean threads) {
		ExecutionContext ec = matrixContext("X", level);
		String instruction = threads ? InstructionUtils.concatOperands("CP", opcode,
			operand("X", DataType.MATRIX, ValueType.FP64),
			operand("Y", DataType.MATRIX, ValueType.FP64), "1")
			: InstructionUtils.concatOperands("CP", opcode, operand("X", DataType.MATRIX, ValueType.FP64),
				operand("Y", DataType.MATRIX, ValueType.FP64));
		return FederatedWorkerPrivacy.inferInstructionOutput(ec,
			InstructionParser.parseSingleInstruction(instruction));
	}

	private static WorkerPrivacyLevel inferRightIndexLabel(WorkerPrivacyLevel inputLevel,
		WorkerPrivacyLevel boundLevel) {
		ExecutionContext ec = matrixContext("X", inputLevel);
		ec.setVariable("B", scalar(1, boundLevel));
		String instruction = InstructionUtils.concatOperands("CP", "rightIndex",
			operand("X", DataType.MATRIX, ValueType.FP64), operand("B", DataType.SCALAR, ValueType.INT64),
			literalIndex(1), literalIndex(1), literalIndex(1),
			operand("Y", DataType.MATRIX, ValueType.FP64));
		return FederatedWorkerPrivacy.inferInstructionOutput(ec,
			InstructionParser.parseSingleInstruction(instruction));
	}

	private static WorkerPrivacyLevel inferLeftIndexLabel(WorkerPrivacyLevel inputLevel,
		WorkerPrivacyLevel rhsLevel, WorkerPrivacyLevel boundLevel) {
		ExecutionContext ec = matrixContext("X", inputLevel);
		ec.setVariable("R", matrix(rhsLevel));
		ec.setVariable("B", scalar(1, boundLevel));
		String instruction = InstructionUtils.concatOperands("CP", "leftIndex",
			operand("X", DataType.MATRIX, ValueType.FP64), operand("R", DataType.MATRIX, ValueType.FP64),
			operand("B", DataType.SCALAR, ValueType.INT64), literalIndex(1), literalIndex(1), literalIndex(1),
			operand("Y", DataType.MATRIX, ValueType.FP64));
		return FederatedWorkerPrivacy.inferInstructionOutput(ec,
			InstructionParser.parseSingleInstruction(instruction));
	}

	private static ExecutionContext matrixContext(String name, WorkerPrivacyLevel level) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable(name, matrix(level));
		return ec;
	}

	private static MatrixObject matrix(WorkerPrivacyLevel level) {
		MatrixObject matrix = ExecutionContext.createMatrixObject(new MatrixBlock(2, 2, 1));
		matrix.setWorkerPrivacyLevel(level);
		return matrix;
	}

	private static DoubleObject scalar(double value, WorkerPrivacyLevel level) {
		DoubleObject scalar = new DoubleObject(value);
		scalar.setWorkerPrivacyLevel(level);
		return scalar;
	}

	private static String operand(String name, DataType dataType, ValueType valueType) {
		return InstructionUtils.concatOperandParts(name, dataType.name(), valueType.name());
	}

	private static String operand(String name, DataType dataType, ValueType valueType, boolean literal) {
		return InstructionUtils.concatOperandParts(name, dataType.name(), valueType.name(),
			Boolean.toString(literal));
	}

	private static String literalIndex(long value) {
		return operand(Long.toString(value), DataType.SCALAR, ValueType.INT64, true);
	}

	private static String localOperation(String opcode, long inputId, long outputId) {
		String input = operand(Long.toString(inputId), DataType.MATRIX, ValueType.FP64);
		String output = operand(Long.toString(outputId), DataType.MATRIX, ValueType.FP64);
		switch(opcode) {
			case "r'":
			case "uacmax":
				return InstructionUtils.concatOperands("CP", opcode, input, output, "1");
			case "rev":
				return InstructionUtils.concatOperands("CP", opcode, input, output);
			case "rightIndex":
				return InstructionUtils.concatOperands("CP", opcode, input, literalIndex(1), literalIndex(1),
					literalIndex(1), literalIndex(1), output);
			case "leftIndex":
				return InstructionUtils.concatOperands("CP", opcode, input,
					operand("9", DataType.SCALAR, ValueType.FP64, true), literalIndex(1), literalIndex(1),
					literalIndex(1), literalIndex(1), output);
			default:
				throw new IllegalArgumentException("unsupported test opcode " + opcode);
		}
	}

	private static WorkerPrivacyLevel nonDeclassifiable(WorkerPrivacyLevel level) {
		return level == WorkerPrivacyLevel.PRIVATE_AGGREGATE ? WorkerPrivacyLevel.PRIVATE : level;
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
