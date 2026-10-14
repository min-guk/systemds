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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FederatedSourceMetadata;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.MultiReturnParameterizedBuiltinFEDInstruction.PublicRecodeMetadata;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.apache.sysds.runtime.transform.TransformEncodeMetadataPrivacy;

/**
 * Explicit, uncached preparation of public recode metadata for a numeric federated matrix source. The source is read
 * and cast to a frame at its workers; neither source rows nor the cast frame are collected at the coordinator.
 */
public final class FederatedRecodeMetadataCollector {
	private static final String MATRIX_NAME = "__fed_recode_source";
	private static final String FRAME_NAME = "__fed_recode_frame";

	private FederatedRecodeMetadataCollector() {
		// utility class
	}

	/**
	 * Performs one inline FED init, worker-local {@code as.frame}, and build-only metadata collection. This method does
	 * not cache its result and does not execute the transform apply or any workload runtime body.
	 *
	 * @param source immutable planner source metadata
	 * @param rawSpec authorized public recode/dummycode transform specification
	 * @return collected encoder/metadata and complete preparation latency, including synchronous temporary cleanup
	 */
	public static CollectionResult collect(FederatedSourceMetadata source, String rawSpec) {
		long start = System.nanoTime();
		if(!TransformEncodeMetadataPrivacy.allowsPublicRecodeMetadata(rawSpec))
			throw new DMLRuntimeException("Federated public recode metadata collection is not authorized " +
				"or the transform specification is not metadata-only");
		// Run the throwing validator as well as the predicate before any worker RPC. The normalized spec is consumed by
		// buildPublicRecodeMetadata, which repeats this defense at its own public boundary.
		TransformEncodeMetadataPrivacy.validateAndStripReleaseRequest(rawSpec);
		SourceCopy copied = copyAndValidateSource(source);
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject matrix = new MatrixObject(ValueType.FP64, MATRIX_NAME,
			new MetaData(new MatrixCharacteristics(copied.rows(), copied.columns(), -1, -1)));
		FrameObject frame = new FrameObject(FRAME_NAME,
			new MetaData(new MatrixCharacteristics(copied.rows(), copied.columns(), -1, -1)));
		ec.setVariable(MATRIX_NAME, matrix);
		ec.setVariable(FRAME_NAME, frame);

		PublicRecodeMetadata metadata = null;
		RuntimeException failure = null;
		try {
			InitFEDInstruction.federateMatrix(matrix, copied.partitions());
			CastFEDInstruction.parseInstruction(InstructionUtils.concatOperands("FED", "castdtf",
				InstructionUtils.concatOperandParts(MATRIX_NAME, DataType.MATRIX.name(), ValueType.FP64.name()),
				InstructionUtils.concatOperandParts(FRAME_NAME, DataType.FRAME.name(), ValueType.STRING.name()), "1"))
				.processInstruction(ec);
			metadata = MultiReturnParameterizedBuiltinFEDInstruction.buildPublicRecodeMetadata(frame, rawSpec);
		}
		catch(RuntimeException ex) {
			failure = ex;
			throw ex;
		}
		finally {
			try {
				cleanupTemporary(frame.getFedMapping(), matrix.getFedMapping(), copied, ec.getTID());
			}
			catch(RuntimeException cleanupFailure) {
				if(failure != null)
					failure.addSuppressed(cleanupFailure);
				else
					throw cleanupFailure;
			}
			finally {
				ec.removeVariable(FRAME_NAME);
				ec.removeVariable(MATRIX_NAME);
			}
		}
		return new CollectionResult(metadata, System.nanoTime() - start);
	}

	/** Immutable result for immediate conversion into a planner-owned shape proof. */
	public record CollectionResult(PublicRecodeMetadata metadata, long elapsedNanos) {
		public CollectionResult {
			if(metadata == null || elapsedNanos < 0)
				throw new IllegalArgumentException("Federated recode metadata collection result is incomplete");
		}
	}

	private static SourceCopy copyAndValidateSource(FederatedSourceMetadata source) {
		if(source == null || source.partitions() == null || source.partitions().isEmpty())
			throw new DMLRuntimeException("Federated recode metadata collection requires source partitions");
		List<Pair<FederatedRange, FederatedData>> copied = new ArrayList<>(source.partitions().size());
		long rows = 0;
		long columns = 0;
		for(Pair<FederatedRange, FederatedData> partition : source.partitions()) {
			if(partition == null || partition.getKey() == null || partition.getValue() == null)
				throw new DMLRuntimeException("Federated recode metadata source contains an incomplete partition");
			FederatedRange range = partition.getKey();
			long[] begin = range.getBeginDims();
			long[] end = range.getEndDims();
			FederatedData data = partition.getValue();
			if(begin.length != 2 || end.length != 2 || begin[0] < 0 || begin[1] < 0 || end[0] <= begin[0]
				|| end[1] <= begin[1])
				throw new DMLRuntimeException("Federated recode metadata source requires positive two-dimensional ranges");
			if(data.getDataType() != DataType.MATRIX || data.getAddress() == null || data.getFilepath() == null)
				throw new DMLRuntimeException("Federated recode metadata source must be a numeric MATRIX endpoint");
			rows = Math.max(rows, end[0]);
			columns = Math.max(columns, end[1]);
			FederatedData copiedData = data.copyWithNewID(-1);
			if(copiedData == data || copiedData.getDataType() != DataType.MATRIX)
				throw new DMLRuntimeException("Federated source endpoint could not be defensively copied");
			copied.add(Pair.of(new FederatedRange(begin.clone(), end.clone()), copiedData));
		}
		if(columns > Integer.MAX_VALUE)
			throw new DMLRuntimeException("Federated recode metadata frame exceeds the supported column count");
		return new SourceCopy(List.copyOf(copied), rows, columns);
	}

	private static void cleanupTemporary(FederationMap frame, FederationMap matrix, SourceCopy copied, long tid) {
		RuntimeException failure = null;
		List<FederationMap> cleanupMaps = new ArrayList<>();
		if(frame != null)
			cleanupMaps.add(frame);
		if(matrix != null)
			cleanupMaps.add(matrix);
		else {
			// federateMatrix publishes its map only after every init response succeeds. On a partial failure the
			// defensive endpoint copies still retain the issued ID, so construct a cleanup-only map from those copies.
			List<Pair<FederatedRange, FederatedData>> initialized = copied.partitions().stream()
				.filter(partition -> partition.getValue().isInitialized()).toList();
			if(!initialized.isEmpty())
				cleanupMaps.add(new FederationMap(initialized.get(0).getValue().getVarID(), initialized));
		}
		for(FederationMap map : cleanupMaps) {
			if(map == null || !map.isInitialized())
				continue;
			try {
				map.execute(tid, true, map.cleanup(tid, map.getID()));
			}
			catch(RuntimeException ex) {
				if(failure == null)
					failure = ex;
				else
					failure.addSuppressed(ex);
			}
		}
		if(failure != null)
			throw failure;
	}

	private record SourceCopy(List<Pair<FederatedRange, FederatedData>> partitions, long rows, long columns) { }
}
