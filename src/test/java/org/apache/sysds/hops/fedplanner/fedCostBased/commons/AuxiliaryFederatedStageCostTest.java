/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.util.Arrays;
import java.util.List;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.junit.Assert;
import org.junit.Test;

/** Batch counts are independently locked by Aggregate/AuxiliaryStageBatchContractTest. */
public class AuxiliaryFederatedStageCostTest {
	@Test public void varianceOwnsOneAdditionalMeanBatch() {
		Hop input = matrix("X");
		Hop variance = new AggUnaryOp("var", DataType.SCALAR, ValueType.FP64,
			AggOp.VAR, Direction.RowCol, input);
		assertAdditionalBatches(variance, List.of(FType.ROW), 1);
	}

	@Test public void alignedCovarianceOwnsTwoMeanBatches() {
		Hop covariance = new BinaryOp("cov", DataType.SCALAR, ValueType.FP64,
			OpOp2.COV, matrix("X"), matrix("Y"));
		assertAdditionalBatches(covariance, List.of(FType.ROW, FType.ROW), 2);
		Assert.assertEquals(0, auxiliary(covariance, Arrays.asList(FType.ROW, null)), 0);
	}

	@Test public void alignedWeightedCovarianceOwnsThreeAuxiliaryBatches() {
		Hop covariance = new TernaryOp("cov", DataType.SCALAR, ValueType.FP64,
			OpOp3.COV, matrix("X"), matrix("Y"), matrix("W"));
		assertAdditionalBatches(covariance, List.of(FType.ROW, FType.ROW, FType.ROW), 3);
		assertAdditionalBatches(covariance, Arrays.asList(FType.ROW, FType.ROW, null), 3);
	}

	@Test public void rowCumulativeOwnsTwoAuxiliaryBatchesOnlyForRowPartitions() {
		for(OpOp1 op : List.of(OpOp1.CUMSUM, OpOp1.CUMPROD, OpOp1.CUMMIN, OpOp1.CUMMAX)) {
			Hop cumulative = new UnaryOp("cumulative", DataType.MATRIX, ValueType.FP64, op, matrix("X"));
			assertAdditionalBatches(cumulative, List.of(FType.ROW), 2);
			for(FType type : List.of(FType.COL, FType.FULL, FType.BROADCAST))
				Assert.assertEquals(type.toString(), 0, auxiliary(cumulative, List.of(type)), 0);
		}
	}

	@Test public void rowCumulativeSumProductOwnsThreeAuxiliaryBatches() {
		Hop cumulative = new UnaryOp("cumulative", DataType.MATRIX, ValueType.FP64,
			OpOp1.CUMSUMPROD, matrix("X"));
		assertAdditionalBatches(cumulative, List.of(FType.ROW), 3);
	}

	@Test public void reshapeSchemaPutOwnsOneBatchWithoutMatrixUpload() {
		Hop reshape = new ReorgOp("reshape", DataType.MATRIX, ValueType.FP64, ReOrgOp.RESHAPE,
			List.of(matrix("X"), new LiteralOp(5L), new LiteralOp(4L), new LiteralOp(true)));
		Assert.assertEquals(FederatedCostModel.computeRequestResponseLatency(),
			auxiliary(reshape, Arrays.asList(FType.ROW, null, null, null)), 1e-12);
	}

	@Test public void ctableDiscoversEachFederatedDimension() {
		Hop table = new TernaryOp("table", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, matrix("X"), matrix("Y"), new LiteralOp(1D));
		assertAdditionalBatches(table, Arrays.asList(FType.ROW, null, null), 1);
		assertAdditionalBatches(table, Arrays.asList(FType.ROW, FType.ROW, null), 2);
	}

	@Test public void ctableLocalDimensionScanHasWorkButNoRemoteBatch() {
		Hop table = new TernaryOp("table", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, matrix("X"), matrix("Y"), new LiteralOp(1D));
		double local = auxiliary(table, Arrays.asList(null, null, null));
		Assert.assertTrue("Local slices and max scans still perform work", local > 0);
		Assert.assertTrue("Local dimension discovery creates no request batch",
			local < FederatedCostModel.computeRequestResponseLatency());
	}

	private static void assertAdditionalBatches(Hop hop, List<FType> types, int batches) {
		double latency = FederatedCostModel.computeRequestResponseLatency();
		Assert.assertTrue("Missing " + batches + " auxiliary request batches for " + hop.getOpString(),
			auxiliary(hop, types) >= batches * latency);
		// Tiny matrices isolate the stage intercept: a worker fanout is one batch, not W batches.
		Assert.assertTrue("Worker fanout must not multiply stage latency",
			auxiliary(hop, types) < (batches + 1) * latency);
	}

	private static double auxiliary(Hop hop, List<FType> types) {
		return FederatedCostModel.computeMixedFedLocalCost(hop, hop.getInput(), types,
			types.get(0), FederatedCostModel.computeOpCost(hop), 8, 3).getInputPreparationCost();
	}

	private static DataOp matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, 10, 2, 20, 1_000);
	}
}
