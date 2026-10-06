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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.util.Arrays;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.WorkerResponseSummary;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.junit.Assert;
import org.junit.Test;

public class CtableInputPreparationCostTest {
	private static final double BYTES = 16_000_000;
	private static final InputLayout LOCAL = new InputLayout(null, List.of(), false);

	@Test public void remoteSecondaryStillNeedsSlicedPut() {
		Assert.assertTrue("A DIRECT_FOUT secondary is collected then uploaded, not consumed in place",
			preparation(table(false), layout(row("a"), row("a"), LOCAL)) >= put());
	}

	@Test public void localSecondaryStillNeedsSlicedPut() {
		Assert.assertTrue(preparation(table(false), layout(row("a"), LOCAL, LOCAL)) >= put());
	}

	@Test public void reversedInputsHaveTheSamePreparation() {
		Assert.assertEquals(preparation(table(false), layout(row("a"), LOCAL, LOCAL)),
			preparation(table(false), layout(LOCAL, row("a"), LOCAL)), 1e-12);
	}

	@Test public void weightsAreReusedOnlyAtMatchingWorkersAndCompleteRanges() {
		TernaryOp weighted = table(true);
		double aligned = preparation(weighted, layout(row("a"), row("a"), row("a")));
		Assert.assertEquals(preparation(table(false), layout(row("a"), row("a"), LOCAL)), aligned, 0);
		double remote = preparation(weighted, layout(row("a"), row("a"), row("b")));
		Assert.assertEquals("Different worker addresses require one weight PUT and slicing work",
			put() + 2 * FederatedCostModel.computeMemoryAccessCost(BYTES), remote - aligned, 1e-12);
		InputLayout shifted = new InputLayout(FType.ROW, List.of(
			new AnchorPartition("a0", List.of(0L, 0L), List.of(500_000L, 1L)),
			new AnchorPartition("a1", List.of(500_000L, 0L), List.of(2_000_000L, 1L))), true);
		Assert.assertTrue("Equal FType and workers do not prove full alignment",
			preparation(weighted, layout(row("a"), row("a"), shifted)) - aligned >= put());
	}

	@Test public void unknownWeightRangesCannotProveReuse() {
		TernaryOp weighted = table(true);
		double aligned = preparation(weighted, layout(row("a"), row("a"), row("a")));
		Assert.assertTrue(preparation(weighted, layout(row("a"), row("a"),
			new InputLayout(FType.ROW, List.of(), false))) - aligned >= put());
	}

	@Test public void singletonBroadcastDoesNotCopyWeightSlices() {
		for(FType type : List.of(FType.ROW, FType.FULL)) {
			InputLayout single = new InputLayout(type, List.of(
				new AnchorPartition("a0", List.of(0L, 0L), List.of(2_000_000L, 1L))), true);
			FederatedExecutionLayout layout = new FederatedExecutionLayout(type, 1, List.of(single, single, LOCAL));
			double delta = preparation(table(true), layout) - preparation(table(false), layout);
			Assert.assertEquals("A singleton map uploads the original block without copying slices",
				FederatedCostModel.computeInBandUploadPayloadCost(BYTES, type, 1), delta, 1e-12);
		}
	}

	@Test public void secondaryDimensionUsesItsOwnResponseCount() {
		InputLayout four = new InputLayout(FType.ROW, java.util.stream.IntStream.range(0, 4)
			.mapToObj(i -> new AnchorPartition("b" + i, List.of(i * 500_000L, 0L),
				List.of((i + 1) * 500_000L, 1L))).toList(), true);
		TernaryOp table = table(false);
		double delta = preparation(table, layout(row("a"), four, LOCAL))
			- preparation(table, layout(row("a"), row("b"), LOCAL));
		Assert.assertEquals("Secondary dimension discovery executes on its own map",
			dimensionStage(table, 4) - dimensionStage(table, 2), delta, 1e-12);
	}

	private static double dimensionStage(Hop table, int responses) {
		return FederatedCostModel.computeNativeFederatedLoutResultCost(table,
			new WorkerResponseSummary(8D * responses, 8, responses))
			+ FederatedCostModel.computeExecutionCost(null, 2_000_000D / responses, BYTES / responses, 8)
			+ FederatedCostModel.computeExecutionCost(null, responses, 8D * responses, 8);
	}

	private static TernaryOp table(boolean weights) {
		return new TernaryOp("table", DataType.MATRIX, ValueType.FP64, OpOp3.CTABLE,
			matrix("A"), matrix("B"), weights ? matrix("W") : new LiteralOp(1D));
	}

	private static Hop matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, 2_000_000, 1, 2_000_000, 1_000);
	}

	private static InputLayout row(String worker) {
		return new InputLayout(FType.ROW, List.of(
			new AnchorPartition(worker + "0", List.of(0L, 0L), List.of(1_000_000L, 1L)),
			new AnchorPartition(worker + "1", List.of(1_000_000L, 0L), List.of(2_000_000L, 1L))), true);
	}

	private static FederatedExecutionLayout layout(InputLayout... inputs) {
		return new FederatedExecutionLayout(FType.ROW, 2, Arrays.asList(inputs));
	}

	private static double preparation(Hop hop, FederatedExecutionLayout layout) {
		return FederatedCostModel.computeMixedFedLocalCost(hop, hop.getInput(), List.of(BYTES, BYTES, BYTES),
			layout.inputs().stream().map(InputLayout::fType).toList(), layout.executionType(), 0, 32, layout.workers(), layout)
			.getInputPreparationCost();
	}

	private static double put() {
		return FederatedCostModel.computeInBandUploadPayloadCost(BYTES, FType.ROW, 2);
	}
}
