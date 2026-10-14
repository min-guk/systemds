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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.After;
import org.junit.Test;

public class FederatedRecodeMetadataCollectorTest {
	private static final String SPEC =
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
	public void collectInitializesCastsBuildsAndLeavesSourceFactsUnchanged() throws Exception {
		Path directory = Files.createTempDirectory("fed-recode-collector");
		Path input = directory.resolve("part.csv");
		Files.writeString(input, "1,10\n2,20\n1,30\n");
		Files.writeString(Path.of(input + ".mtd"), "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"rows\":3,\"cols\":2,\"nnz\":6,\"format\":\"csv\",\"privacy\":\"private-aggregate\"}");
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		try {
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {3, 2});
			FederatedData data = FederatedData.forPlannerMetadata(DataType.MATRIX,
				new InetSocketAddress("localhost", port), input.toString());
			FederatedSourceMetadata source = new FederatedSourceMetadata(
				Privacy.PRIVATE_AGGREGATE, List.of(Pair.of(range, data)));
			System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");

			FederatedRecodeMetadataCollector.CollectionResult result =
				FederatedRecodeMetadataCollector.collect(source, SPEC);

			assertTrue(result.elapsedNanos() > 0);
			assertEquals(-1, data.getVarID());
			assertEquals(DataType.MATRIX, data.getDataType());
			assertEquals(0, range.getBeginDims()[0]);
			assertEquals(3, range.getEndDims()[0]);
			FrameBlock metadata = result.metadata().metadata();
			assertEquals(2, metadata.getNumColumns());
			assertEquals(2, metadata.getColumnMetadata(0).getNumDistinct());
		}
		finally {
			FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(worker);
			try(var paths = Files.walk(directory)) {
				for(Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	@Test
	public void collectRejectsUnauthorizedAndNonMatrixSourcesBeforeNetwork() {
		FederatedData unreachableMatrix = FederatedData.forPlannerMetadata(DataType.MATRIX,
			new InetSocketAddress("localhost", 1), "missing.csv");
		FederatedSourceMetadata matrixSource = source(unreachableMatrix);
		System.clearProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY);
		expectDML(() -> FederatedRecodeMetadataCollector.collect(matrixSource, SPEC));

		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		expectDML(() -> FederatedRecodeMetadataCollector.collect(matrixSource,
			"{ids:true,recode:[1],omit:[2],cofeePublicRecodeMetadata:true}"));
		FederatedData unreachableFrame = FederatedData.forPlannerMetadata(DataType.FRAME,
			new InetSocketAddress("localhost", 1), "missing.csv");
		expectDML(() -> FederatedRecodeMetadataCollector.collect(source(unreachableFrame), SPEC));
		assertEquals(-1, unreachableMatrix.getVarID());
		assertEquals(-1, unreachableFrame.getVarID());
	}

	@Test
	public void partialInitFailureCleansEveryIssuedIdWithoutMutatingSourceFacts() {
		System.setProperty(TransformEncodeMetadataPrivacy.RELEASE_PROPERTY, "true");
		InitState firstState = new InitState(false);
		InitState secondState = new InitState(true);
		PartialInitData first = new PartialInitData(23001, firstState);
		PartialInitData second = new PartialInitData(23002, secondState);
		FederatedSourceMetadata source = new FederatedSourceMetadata(Privacy.PRIVATE_AGGREGATE, List.of(
			Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {1, 1}), first),
			Pair.of(new FederatedRange(new long[] {1, 0}, new long[] {2, 1}), second)));

		expectDML(() -> FederatedRecodeMetadataCollector.collect(source, SPEC));

		assertEquals(-1, first.getVarID());
		assertEquals(-1, second.getVarID());
		assertEquals(1, firstState.initCalls);
		assertEquals(1, secondState.initCalls);
		assertEquals(1, firstState.cleanupCalls);
		assertEquals(1, secondState.cleanupCalls);
		assertTrue(firstState.issuedId >= 0);
		assertEquals(firstState.issuedId, secondState.issuedId);
		assertTrue(firstState.cleanupInstruction.contains(Long.toString(firstState.issuedId)));
		assertTrue(secondState.cleanupInstruction.contains(Long.toString(secondState.issuedId)));
	}

	@Test
	public void castAsFramePublishesFrameTypedDefensiveMap() {
		FederatedData originalData = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", 22001), "mock", 71);
		FederatedRange originalRange = new FederatedRange(new long[] {0, 0}, new long[] {3, 2});
		RecordingMap originalMap = new RecordingMap(71, List.of(Pair.of(originalRange, originalData)));
		MatrixObject input = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(3, 2, 1024, 6)));
		input.setFedMapping(originalMap);
		FrameObject output = new FrameObject("F", new MetaData(new MatrixCharacteristics(-1, -1, 1024, -1)));
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("X", input);
		ec.setVariable("F", output);

		CastFEDInstruction.parseInstruction(InstructionUtils.concatOperands("FED", "castdtf",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("F", DataType.FRAME.name(), ValueType.STRING.name()), "1"))
			.processInstruction(ec);

		FederationMap castMap = output.getFedMapping();
		assertEquals(FType.ROW, castMap.getType());
		assertEquals(DataType.FRAME, castMap.getMap().get(0).getValue().getDataType());
		assertEquals(DataType.MATRIX, originalMap.getMap().get(0).getValue().getDataType());
		assertNotSame(originalRange, castMap.getMap().get(0).getKey());
		assertFalse(castMap.getMap().isEmpty());
	}

	@Test
	public void failedCastCleansItsPreallocatedOutputId() {
		FederatedData data = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", 22002), "mock", 81);
		FailingCastMap map = new FailingCastMap(81, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {1, 1}), data)));
		MatrixObject input = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(1, 1, 1024, 1)));
		input.setFedMapping(map);
		FrameObject output = new FrameObject("F", new MetaData(new MatrixCharacteristics(-1, -1, 1024, -1)));
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("X", input);
		ec.setVariable("F", output);
		CastFEDInstruction cast = CastFEDInstruction.parseInstruction(InstructionUtils.concatOperands(
			"FED", "castdtf",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("F", DataType.FRAME.name(), ValueType.STRING.name()), "1"));

		expectDML(() -> cast.processInstruction(ec));

		assertEquals(1, map.castCalls);
		assertEquals(1, map.cleanupCalls);
		assertTrue(map.cleanupInstruction.contains("rmvar"));
		assertTrue(map.cleanupInstruction.contains(Long.toString(map.allocatedId)));
		assertFalse(output.isFederated());
	}

	private static FederatedSourceMetadata source(FederatedData data) {
		return new FederatedSourceMetadata(Privacy.PRIVATE_AGGREGATE, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {1, 1}), data)));
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

	private static final class RecordingMap extends FederationMap {
		private RecordingMap(long id, List<Pair<FederatedRange, FederatedData>> entries) {
			super(id, entries, FType.ROW);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			return new Future[] {CompletableFuture.completedFuture(
				new FederatedResponse(FederatedResponse.ResponseType.SUCCESS_EMPTY))};
		}
	}

	private static final class FailingCastMap extends FederationMap {
		private int castCalls;
		private int cleanupCalls;
		private long allocatedId = -1;
		private String cleanupInstruction = "";

		private FailingCastMap(long id, List<Pair<FederatedRange, FederatedData>> entries) {
			super(id, entries, FType.ROW);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			if(requests.length == 2) {
				castCalls++;
				allocatedId = requests[0].getID();
				throw new DMLRuntimeException("injected cast failure");
			}
			cleanupCalls++;
			cleanupInstruction = String.valueOf(requests[0].getParam(0));
			return new Future[] {CompletableFuture.completedFuture(
				new FederatedResponse(FederatedResponse.ResponseType.SUCCESS_EMPTY))};
		}
	}

	private static final class InitState {
		private final boolean fail;
		private int initCalls;
		private int cleanupCalls;
		private long issuedId = -1;
		private String cleanupInstruction = "";

		private InitState(boolean fail) {
			this.fail = fail;
		}
	}

	private static final class PartialInitData extends FederatedData {
		private final InitState state;

		private PartialInitData(int port, InitState state) {
			super(DataType.MATRIX, new InetSocketAddress("localhost", port), "mock");
			this.state = state;
		}

		@Override
		public FederatedData copyWithNewID(long varID) {
			PartialInitData copy = new PartialInitData(getAddress().getPort(), state);
			copy.setVarID(varID);
			return copy;
		}

		@Override
		public synchronized Future<FederatedResponse> initFederatedData(long id) {
			setVarID(id);
			state.initCalls++;
			state.issuedId = id;
			if(state.fail) {
				CompletableFuture<FederatedResponse> failed = new CompletableFuture<>();
				failed.completeExceptionally(new DMLRuntimeException("injected init failure"));
				return failed;
			}
			return CompletableFuture.completedFuture(new FederatedResponse(
				FederatedResponse.ResponseType.SUCCESS,
				new Object[] {id, new MatrixCharacteristics(1, 1, 1024, 1)}));
		}

		@Override
		public Future<FederatedResponse> executeFederatedOperation(FederatedRequest... requests) {
			state.cleanupCalls++;
			state.cleanupInstruction = String.valueOf(requests[0].getParam(0));
			return CompletableFuture.completedFuture(
				new FederatedResponse(FederatedResponse.ResponseType.SUCCESS_EMPTY));
		}
	}
}
