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

import java.util.List;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.junit.Assert;
import org.junit.Test;

/** Quantity checks complement the request-count contracts in the runtime instruction tests. */
public class AuxiliaryFederatedQuantityCostTest {
	@Test
	public void varianceMeanPayloadTracksReducedVectorWidth() {
		DataOp narrowInput = matrix("narrow", 1_000, 1, 1_000);
		DataOp wideInput = matrix("wide", 10, 100, 1_000);
		AggUnaryOp narrow = variance(narrowInput);
		AggUnaryOp wide = variance(wideInput);

		double narrowCost = auxiliary(narrow, List.of(8_000D), List.of(FType.ROW), null, 4);
		double wideCost = auxiliary(wide, List.of(8_000D), List.of(FType.ROW), null, 4);

		Assert.assertTrue("The auxiliary mean GET and worker output must carry one value per column",
			wideCost > narrowCost);
	}

	@Test
	public void cumulativeAuxiliaryStagesExistOnlyForRowPartitions() {
		DataOp input = matrix("X", 1_000, 20, 20_000);
		UnaryOp cumulative = cumulative(OpOp1.CUMSUM, input);

		Assert.assertTrue(auxiliary(cumulative, List.of(160_000D), List.of(FType.ROW), null, 4) > 0);
		for(FType type : List.of(FType.COL, FType.FULL, FType.BROADCAST))
			Assert.assertEquals(type.toString(), 0,
				auxiliary(cumulative, List.of(160_000D), List.of(type), null, 4), 0);
	}

	@Test
	public void nonzeroIdentityCorrectionRemainsDenseForSparseInput() {
		DataOp sparse = matrix("sparse", 100_000, 20, 1);
		UnaryOp sum = cumulative(OpOp1.CUMSUM, sparse);
		UnaryOp product = cumulative(OpOp1.CUMPROD, sparse);

		double sumCost = auxiliary(sum, List.of(1_024D), List.of(FType.ROW), null, 4);
		double productCost = auxiliary(product, List.of(1_024D), List.of(FType.ROW), null, 4);

		Assert.assertTrue("CUMPROD materializes a dense all-ones correction despite a sparse input",
			productCost > sumCost * 10);
	}

	@Test
	public void skewedRowLayoutRaisesCriticalWorkerCost() {
		DataOp input = matrix("X", 100_000, 20, 2_000_000);
		UnaryOp cumulative = cumulative(OpOp1.CUMSUM, input);
		FederatedExecutionLayout balanced = layout(input, 50_000);
		FederatedExecutionLayout skewed = layout(input, 99_000);

		double balancedCost = auxiliary(cumulative, List.of(16_000_000D), List.of(FType.ROW), balanced, 2);
		double skewedCost = auxiliary(cumulative, List.of(16_000_000D), List.of(FType.ROW), skewed, 2);

		Assert.assertTrue("The largest worker partition controls auxiliary worker execution",
			skewedCost > balancedCost);
	}

	@Test
	public void occurrenceBytesDriveUnknownShapeAuxiliaryWork() {
		DataOp unknown = matrix("X", -1, -1, -1);
		UnaryOp cumulative = cumulative(OpOp1.CUMSUM, unknown);

		double small = auxiliary(cumulative, List.of(8_000D), List.of(FType.ROW), null, 4);
		double large = auxiliary(cumulative, List.of(8_000_000D), List.of(FType.ROW), null, 4);

		Assert.assertTrue("Occurrence-scoped bytes must replace unknown-shape HOP sentinels",
			large > small);
	}

	@Test
	public void unknownSumProductUsesRequiredTwoColumnGeometry() {
		UnaryOp known = cumulative(OpOp1.CUMSUMPROD, matrix("known", 1_000, 2, 2_000));
		UnaryOp unknown = cumulative(OpOp1.CUMSUMPROD, matrix("unknown", -1, -1, -1));

		double knownCost = auxiliary(known, List.of(16_000D), List.of(FType.ROW), null, 4);
		double unknownCost = auxiliary(unknown, List.of(16_000D), List.of(FType.ROW), null, 4);

		Assert.assertEquals("CUMSUMPROD always consumes two columns, even before shape propagation",
			knownCost, unknownCost, 1e-12);
	}

	@Test
	public void alignedCovariancePaysForTwoMeanScans() {
		DataOp x = matrix("X", 100_000, 1, 100_000);
		DataOp y = matrix("Y", 100_000, 1, 100_000);
		BinaryOp covariance = new BinaryOp("cov", DataType.SCALAR, ValueType.FP64, OpOp2.COV, x, y);

		double aligned = auxiliary(covariance, List.of(800_000D, 800_000D),
			List.of(FType.ROW, FType.ROW), null, 4);
		double mixed = auxiliary(covariance, List.of(800_000D, 800_000D),
			java.util.Arrays.asList(FType.ROW, null), null, 4);

		Assert.assertTrue(aligned > mixed);
		Assert.assertEquals(0, mixed, 0);
	}

	private static AggUnaryOp variance(Hop input) {
		return new AggUnaryOp("var", DataType.MATRIX, ValueType.FP64,
			AggOp.VAR, Direction.Col, input);
	}

	private static UnaryOp cumulative(OpOp1 op, Hop input) {
		return new UnaryOp("cumulative", DataType.MATRIX, ValueType.FP64, op, input);
	}

	private static DataOp matrix(String name, long rows, long cols, long nnz) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, nnz, 1_000);
	}

	private static FederatedExecutionLayout layout(Hop input, long split) {
		long rows = input.getDim1();
		long cols = input.getDim2();
		InputLayout ranges = new InputLayout(FType.ROW, List.of(
			new AnchorPartition("w0", List.of(0L, 0L), List.of(split, cols)),
			new AnchorPartition("w1", List.of(split, 0L), List.of(rows, cols))), true);
		return new FederatedExecutionLayout(FType.ROW, 2, List.of(ranges));
	}

	private static double auxiliary(Hop hop, List<Double> inputBytes, List<FType> types,
		FederatedExecutionLayout layout, int workers) {
		return FederatedCostModel.computeMixedFedLocalCost(hop, hop.getInput(), inputBytes,
			types, types.get(0), 0, Math.max(8, hop.getOutputMemEstimate()), workers, layout)
			.getInputPreparationCost();
	}
}
