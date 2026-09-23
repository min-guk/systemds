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
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject.UpdateType;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedWorkerPrivacyTestFixture.Label;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
import org.apache.sysds.runtime.instructions.cp.ListObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;

/** Tests the historical worker policy only through a test-scoped fixture. */
public class FederatedWorkerPrivacyTest {
	private static final AtomicLong NEXT_ID = new AtomicLong(910_000);

	@Test
	public void fixtureRawReleaseRequiresPublicRecursively() {
		FederatedWorkerPrivacyTestFixture fixture = new FederatedWorkerPrivacyTestFixture();
		DoubleObject publicValue = new DoubleObject(1);
		fixture.label(publicValue, Label.PUBLIC);
		fixture.validateRawRelease(publicValue);

		for(Label label : List.of(Label.PRIVATE_AGGREGATE, Label.PRIVATE, Label.UNKNOWN)) {
			DoubleObject protectedValue = new DoubleObject(1);
			fixture.label(protectedValue, label);
			assertThrows(FederatedWorkerHandlerException.class,
				() -> fixture.validateRawRelease(protectedValue));
		}

		DoubleObject child = new DoubleObject(1);
		ListObject list = new ListObject(List.of(child));
		fixture.label(list, Label.PUBLIC);
		fixture.label(child, Label.PRIVATE);
		assertThrows(FederatedWorkerHandlerException.class, () -> fixture.validateRawRelease(list));
	}

	@Test
	public void fixtureFullSumDeclassifiesOnlyAfterSuccessfulApplication() {
		FederatedWorkerPrivacyTestFixture fixture = new FederatedWorkerPrivacyTestFixture();
		ExecutionContext ec = matrixContext(fixture, "X", Label.PRIVATE_AGGREGATE);
		ec.setVariable("sum", new DoubleObject(0));
		fixture.label(ec.getVariable("sum"), Label.PUBLIC);
		Instruction sum = instruction("CP", "uak+", operand("X", DataType.MATRIX, ValueType.FP64),
			operand("sum", DataType.SCALAR, ValueType.FP64), "1");

		Label executionLabel = fixture.inferInstructionOutput(ec, sum);
		assertEquals(Label.PRIVATE_AGGREGATE, executionLabel);
		fixture.protectInstructionAliases(ec, sum, executionLabel);
		assertEquals(Label.PRIVATE_AGGREGATE, fixture.labelOf(ec.getVariable("sum")));
		sum.processInstruction(ec);
		fixture.applyInstructionOutput(ec, sum, executionLabel);
		assertEquals(Label.PUBLIC, fixture.labelOf(ec.getVariable("sum")));
	}

	@Test
	public void fixtureDocumentsNarrowOperationPropagation() {
		for(Label label : Label.values()) {
			assertEquals(label, inferUnary("r'", label, true));
			assertEquals(label, inferUnary("rev", label, false));
			assertEquals(nonDeclassifiable(label), inferUnary("uacmax", label, true));
			assertEquals(nonDeclassifiable(label), inferUnary("uacmean", label, true));
			assertEquals(nonDeclassifiable(label), inferReplace(label));
		}
	}

	@Test
	public void fixtureIndexingAccountsForDataAndVariableBounds() {
		FederatedWorkerPrivacyTestFixture fixture = new FederatedWorkerPrivacyTestFixture();
		ExecutionContext ec = matrixContext(fixture, "X", Label.PUBLIC);
		ec.setVariable("R", matrix());
		fixture.label(ec.getVariable("R"), Label.PRIVATE_AGGREGATE);
		ec.setVariable("B", new DoubleObject(1));
		fixture.label(ec.getVariable("B"), Label.PRIVATE);
		Instruction leftIndex = instruction("CP", "leftIndex",
			operand("X", DataType.MATRIX, ValueType.FP64), operand("R", DataType.MATRIX, ValueType.FP64),
			operand("B", DataType.SCALAR, ValueType.INT64), literalIndex(1), literalIndex(1), literalIndex(1),
			operand("Y", DataType.MATRIX, ValueType.FP64));
		assertEquals(Label.PRIVATE, fixture.inferInstructionOutput(ec, leftIndex));
	}

	@Test
	public void fixtureRejectsLiteralLaunderingAndUnsupportedAggregateBinary() {
		FederatedWorkerPrivacyTestFixture fixture = new FederatedWorkerPrivacyTestFixture();
		ExecutionContext ec = matrixContext(fixture, "X", Label.PRIVATE);
		Instruction forgedTranspose = instruction("CP", "r'",
			operand("X", DataType.MATRIX, ValueType.FP64, true),
			operand("Y", DataType.MATRIX, ValueType.FP64), "1");
		assertThrows(FederatedWorkerHandlerException.class,
			() -> fixture.inferInstructionOutput(ec, forgedTranspose));

		ec.setVariable("T", matrix());
		fixture.label(ec.getVariable("T"), Label.PRIVATE_AGGREGATE);
		Instruction multiply = instruction("CP", "ba+*",
			operand("T", DataType.MATRIX, ValueType.FP64), operand("X", DataType.MATRIX, ValueType.FP64),
			operand("G", DataType.MATRIX, ValueType.FP64), "1");
		assertThrows(FederatedWorkerHandlerException.class,
			() -> fixture.inferInstructionOutput(ec, multiply));
	}

	@Test
	public void fixtureProtectsReusedAndInPlaceAliasesBeforeFailure() {
		FederatedWorkerPrivacyTestFixture fixture = new FederatedWorkerPrivacyTestFixture();
		ExecutionContext ec = matrixContext(fixture, "X", Label.PUBLIC);
		MatrixObject input = ec.getMatrixObject("X");
		input.setUpdateType(UpdateType.INPLACE);
		ec.setVariable("X_ALIAS", input);
		ec.setVariable("R", new DoubleObject(9));
		fixture.label(ec.getVariable("R"), Label.PRIVATE);
		MatrixObject reusedOutput = matrix();
		ec.setVariable("Y", reusedOutput);
		ec.setVariable("Y_ALIAS", reusedOutput);
		fixture.label(reusedOutput, Label.PUBLIC);
		Instruction leftIndex = instruction("CP", "leftIndex",
			operand("X", DataType.MATRIX, ValueType.FP64), operand("R", DataType.SCALAR, ValueType.FP64),
			literalIndex(3), literalIndex(3), literalIndex(1), literalIndex(1),
			operand("Y", DataType.MATRIX, ValueType.FP64));

		Label label = fixture.inferInstructionOutput(ec, leftIndex);
		fixture.protectInstructionAliases(ec, leftIndex, label);
		assertEquals(Label.PRIVATE, fixture.labelOf(ec.getVariable("X_ALIAS")));
		assertEquals(Label.PRIVATE, fixture.labelOf(ec.getVariable("Y_ALIAS")));
		assertThrows(RuntimeException.class, () -> leftIndex.processInstruction(ec));
	}

	@Test
	public void productionHandlerNoLongerEnforcesTestOnlyRawReleasePolicy() throws Exception {
		Path data = writeCsv("private-aggregate");
		long id = NEXT_ID.incrementAndGet();
		FederatedResponse response = execute(
			new FederatedRequest(RequestType.READ_VAR, id, data.toString(), DataType.MATRIX.name()),
			new FederatedRequest(RequestType.GET_VAR, id));
		assertTrue(response.isSuccessful());
		assertTrue(response.getData()[0] instanceof MatrixBlock);
	}

	@Test
	public void productionHandlerExecutesPcaMeanReplaceWithoutFixturePolicy() throws Exception {
		Path data = writeCsv("private-aggregate");
		long inputId = NEXT_ID.incrementAndGet();
		long meanId = NEXT_ID.incrementAndGet();
		long replacedId = NEXT_ID.incrementAndGet();
		String mean = InstructionUtils.concatOperands("CP", "uacmean",
			operand(Long.toString(inputId), DataType.MATRIX, ValueType.FP64),
			operand(Long.toString(meanId), DataType.MATRIX, ValueType.FP64), "1");
		String replace = InstructionUtils.concatOperands("CP", "replace", "target=" + meanId,
			"pattern=NaN", "replacement=0",
			operand(Long.toString(replacedId), DataType.MATRIX, ValueType.FP64));
		FederatedResponse response = execute(
			new FederatedRequest(RequestType.READ_VAR, inputId, data.toString(), DataType.MATRIX.name()),
			new FederatedRequest(RequestType.EXEC_INST, meanId, mean),
			new FederatedRequest(RequestType.EXEC_INST, replacedId, replace),
			new FederatedRequest(RequestType.GET_VAR, replacedId));
		assertTrue(response.isSuccessful());
	}

	@Test
	public void productionHandlerExecutesLmAggregateBinaryWithoutFixturePolicy() throws Exception {
		long leftId = NEXT_ID.incrementAndGet();
		long rightId = NEXT_ID.incrementAndGet();
		long outputId = NEXT_ID.incrementAndGet();
		String multiply = InstructionUtils.concatOperands("CP", "ba+*", Long.toString(leftId),
			Long.toString(rightId), Long.toString(outputId), "1");
		FederatedResponse response = execute(
			new FederatedRequest(RequestType.PUT_VAR, leftId, new MatrixBlock(2, 2, new double[] {1, 2, 3, 4})),
			new FederatedRequest(RequestType.PUT_VAR, rightId, new MatrixBlock(2, 1, new double[] {5, 6})),
			new FederatedRequest(RequestType.EXEC_INST, outputId, multiply),
			new FederatedRequest(RequestType.GET_VAR, outputId));

		assertTrue(response.isSuccessful());
		MatrixBlock result = (MatrixBlock) response.getData()[0];
		assertEquals(17d, result.get(0, 0), 0d);
		assertEquals(39d, result.get(1, 0), 0d);
	}

	private static Label inferUnary(String opcode, Label label, boolean threads) {
		FederatedWorkerPrivacyTestFixture fixture = new FederatedWorkerPrivacyTestFixture();
		ExecutionContext ec = matrixContext(fixture, "X", label);
		Instruction parsed = threads ? instruction("CP", opcode,
			operand("X", DataType.MATRIX, ValueType.FP64), operand("Y", DataType.MATRIX, ValueType.FP64), "1")
			: instruction("CP", opcode, operand("X", DataType.MATRIX, ValueType.FP64),
				operand("Y", DataType.MATRIX, ValueType.FP64));
		return fixture.inferInstructionOutput(ec, parsed);
	}

	private static Label inferReplace(Label label) {
		FederatedWorkerPrivacyTestFixture fixture = new FederatedWorkerPrivacyTestFixture();
		ExecutionContext ec = matrixContext(fixture, "X", label);
		Instruction replace = instruction("CP", "replace", "target=X", "pattern=NaN", "replacement=0",
			operand("Y", DataType.MATRIX, ValueType.FP64));
		return fixture.inferInstructionOutput(ec, replace);
	}

	private static ExecutionContext matrixContext(FederatedWorkerPrivacyTestFixture fixture, String name,
		Label label) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject matrix = matrix();
		ec.setVariable(name, matrix);
		fixture.label(matrix, label);
		return ec;
	}

	private static MatrixObject matrix() {
		return ExecutionContext.createMatrixObject(new MatrixBlock(2, 2, 1));
	}

	private static Instruction instruction(String... parts) {
		return InstructionParser.parseSingleInstruction(InstructionUtils.concatOperands(parts));
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

	private static Label nonDeclassifiable(Label label) {
		return label == Label.PRIVATE_AGGREGATE ? Label.PRIVATE : label;
	}

	private static Path writeCsv(String privacy) throws Exception {
		Path dir = Files.createTempDirectory("worker-privacy-handler-");
		Path data = dir.resolve("X.csv");
		Files.writeString(data, "1,2\n3,4\n");
		Files.writeString(data.resolveSibling("X.csv.mtd"),
			"{\"data_type\":\"matrix\",\"value_type\":\"double\",\"rows\":2,\"cols\":2,"
				+ "\"nnz\":4,\"format\":\"csv\",\"header\":false,\"sep\":\",\",\"privacy\":\""
				+ privacy + "\"}");
		return data;
	}

	private static FederatedResponse execute(FederatedRequest... requests) {
		FederatedWorkerHandler handler = new FederatedWorkerHandler(new FederatedLookupTable(),
			new FederatedReadCache(), null);
		return handler.createResponse(requests);
	}
}
