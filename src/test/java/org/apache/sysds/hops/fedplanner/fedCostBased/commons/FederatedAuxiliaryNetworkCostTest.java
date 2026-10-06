/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.lang.reflect.Method;
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
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Runtime request-batch and payload ownership for native FED auxiliary stages. */
public class FederatedAuxiliaryNetworkCostTest {
	private static final int WORKERS = 4;
	private static final double SCALAR_BYTES = 8.0;

	@Test
	public void varianceAddsMeanBatchAndPartialPayload() throws Exception {
		DataOp x = matrix("X", 100, 20);
		AggUnaryOp variance = new AggUnaryOp("var", DataType.SCALAR, ValueType.FP64,
			AggOp.VAR, Direction.RowCol, x);
		double expected = rtt() + replicatedPayload(SCALAR_BYTES, WORKERS);

		Assert.assertEquals(expected, auxiliary(variance, List.of(x), List.of(bytes(100, 20)),
			List.of(FType.ROW), SCALAR_BYTES), 0.0);
	}

	@Test
	public void alignedCovarianceAddsOnlyItsUnownedMeanBatch() throws Exception {
		DataOp x = matrix("X", 100, 1);
		DataOp y = matrix("Y", 100, 1);
		BinaryOp covariance = new BinaryOp("cov", DataType.SCALAR, ValueType.FP64,
			OpOp2.COV, x, y);
		double expected = rtt() + 2 * replicatedPayload(SCALAR_BYTES, WORKERS);

		Assert.assertEquals(expected, auxiliary(covariance, List.of(x, y),
			List.of(bytes(100, 1), bytes(100, 1)), List.of(FType.ROW, FType.ROW), SCALAR_BYTES), 0.0);
		Assert.assertEquals("Mixed covariance returns in its sole UDF batch", 0.0,
			auxiliary(covariance, List.of(x, y), List.of(bytes(100, 1), bytes(100, 1)),
				Arrays.asList(FType.ROW, null), SCALAR_BYTES), 0.0);
	}

	@Test
	public void alignedWeightedCovarianceAddsThreeAuxiliaryBatches() throws Exception {
		DataOp x = matrix("X", 100, 1);
		DataOp y = matrix("Y", 100, 1);
		DataOp w = matrix("W", 100, 1);
		TernaryOp covariance = new TernaryOp("cov", DataType.SCALAR, ValueType.FP64,
			OpOp3.COV, x, y, w);
		double expected = 3 * rtt() + 3 * replicatedPayload(SCALAR_BYTES, WORKERS);

		Assert.assertEquals(expected, auxiliary(covariance, List.of(x, y, w),
			List.of(bytes(100, 1), bytes(100, 1), bytes(100, 1)),
			List.of(FType.ROW, FType.ROW, FType.ROW), SCALAR_BYTES), 0.0);
		Assert.assertEquals(0.0, auxiliary(covariance, List.of(x, y, w),
			List.of(bytes(100, 1), bytes(100, 1), bytes(100, 1)),
			Arrays.asList(FType.ROW, null, null), SCALAR_BYTES), 0.0);
	}

	@Test
	public void ordinaryCumulativeOwnsThreeRuntimeBatches() throws Exception {
		long rows = 100, cols = 20;
		DataOp x = matrix("X", rows, cols);
		UnaryOp cumulative = new UnaryOp("C", DataType.MATRIX, ValueType.FP64, OpOp1.CUMSUM, x);
		double rowVectorBytes = bytes(1, cols);
		double correctionBytes = OptimizerUtils.estimateSizeExactSparsity(
			rows, cols, ((double) (WORKERS - 1)) / rows);
		double expected = 2 * rtt() + replicatedPayload(rowVectorBytes, WORKERS)
			+ FederatedCostModel.computeInBandUploadPayloadCost(correctionBytes, FType.ROW, WORKERS);

		Assert.assertEquals(expected, auxiliary(cumulative, List.of(x), List.of(bytes(rows, cols)),
			List.of(FType.ROW), bytes(rows, cols)), 0.0);
		Assert.assertEquals("COL cumulative follows the ordinary one-batch instruction path", 0.0,
			auxiliary(cumulative, List.of(x), List.of(bytes(rows, cols)),
				List.of(FType.COL), bytes(rows, cols)), 0.0);
	}

	@Test
	public void deferredCumulativeUsesOccurrenceShapeForItsRowVectorPayload() throws Exception {
		long rows = 100, cols = 20;
		DataOp x = Mockito.spy(matrix("X", rows, cols));
		Mockito.doReturn(-1L).when(x).getDim1();
		Mockito.doReturn(-1L).when(x).getDim2();
		UnaryOp cumulative = new UnaryOp("C", DataType.MATRIX, ValueType.FP64, OpOp1.CUMSUM, x);
		double inputBytes = bytes(rows, cols);
		double correctionBytes = OptimizerUtils.estimateSizeExactSparsity(
			rows, cols, ((double) (WORKERS - 1)) / rows);
		double expected = 2 * rtt() + replicatedPayload(bytes(1, cols), WORKERS)
			+ FederatedCostModel.computeInBandUploadPayloadCost(correctionBytes, FType.ROW, WORKERS);

		Assert.assertEquals(expected, FederatedCostModel.computeFederatedAuxiliaryNetworkCost(
			cumulative, List.of(x), List.of(inputBytes), List.of(rows), List.of(cols),
			List.of(FType.ROW), inputBytes, WORKERS), 0.0);
	}

	@Test
	public void cumulativeSumProductOwnsFourRuntimeBatches() throws Exception {
		long rows = 100, cols = 2;
		DataOp x = matrix("X", rows, cols);
		UnaryOp cumulative = new UnaryOp("C", DataType.MATRIX, ValueType.FP64,
			OpOp1.CUMSUMPROD, x);
		double inputBytes = bytes(rows, cols);
		double outputBytes = bytes(rows, 1);
		double conditionBytes = bytes(rows, cols);
		double offsetsBytes = OptimizerUtils.estimateSizeExactSparsity(rows, cols,
			((double) (WORKERS - 1)) / (rows * cols));
		double expected = 3 * rtt() + balancedPayload(outputBytes, WORKERS)
			+ balancedPayload(inputBytes, WORKERS)
			+ FederatedCostModel.computeInBandUploadPayloadCost(
				conditionBytes + offsetsBytes, FType.ROW, WORKERS);

		Assert.assertEquals(expected, auxiliary(cumulative, List.of(x), List.of(inputBytes),
			List.of(FType.ROW), outputBytes), 0.0);
	}

	@Test
	public void ctableDiscoversEachFederatedDimensionWithItsOwnBatch() throws Exception {
		DataOp x = matrix("X", 100, 1);
		DataOp y = matrix("Y", 100, 1);
		TernaryOp ctable = new TernaryOp("table", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, x, y, new LiteralOp(1.0));
		double oneDiscovery = rtt() + replicatedPayload(SCALAR_BYTES, WORKERS);

		Assert.assertEquals(2 * oneDiscovery, auxiliary(ctable, ctable.getInput(),
			List.of(bytes(100, 1), bytes(100, 1), SCALAR_BYTES),
			Arrays.asList(FType.ROW, FType.ROW, null), bytes(10, 10)), 0.0);
		Assert.assertEquals(oneDiscovery, auxiliary(ctable, ctable.getInput(),
			List.of(bytes(100, 1), bytes(100, 1), SCALAR_BYTES),
			Arrays.asList(FType.ROW, null, null), bytes(10, 10)), 0.0);
		Assert.assertEquals("Weights do not participate in dimension discovery", 0.0,
			auxiliary(ctable, ctable.getInput(),
				List.of(bytes(100, 1), bytes(100, 1), SCALAR_BYTES),
				Arrays.asList(null, null, FType.ROW), bytes(10, 10)), 0.0);
	}

	@Test
	public void reshapeMetadataPutIsASeparateZeroPayloadBatch() {
		DataOp x = matrix("X", 100, 20);
		ReorgOp reshape = new ReorgOp("R", DataType.MATRIX, ValueType.FP64,
			ReOrgOp.RESHAPE, List.of(x, new LiteralOp(200L), new LiteralOp(10L),
				new LiteralOp(true)));

		Assert.assertEquals(rtt(), auxiliary(reshape, reshape.getInput(),
			List.of(bytes(100, 20), SCALAR_BYTES, SCALAR_BYTES, SCALAR_BYTES),
			Arrays.asList(FType.ROW, null, null, null), bytes(200, 10)), 0.0);
	}

	private static double auxiliary(Hop hop, List<Hop> inputs, List<Double> inputBytes,
		List<FType> inputTypes, double outputBytes) {
		return FederatedCostModel.computeFederatedAuxiliaryNetworkCost(
			hop, inputs, inputBytes, inputTypes, outputBytes, WORKERS);
	}

	private static DataOp matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, rows * cols, 1000);
	}

	private static double bytes(long rows, long cols) {
		return OptimizerUtils.estimateSizeExactSparsity(rows, cols, 1.0);
	}

	private static double rtt() {
		return FederatedCostModel.computeRequestResponseLatency();
	}

	private static double replicatedPayload(double bytesPerWorker, int workers) throws Exception {
		Method method = FederatedCostModel.class.getDeclaredMethod(
			"computeReplicatedWorkerResultDownloadCost", double.class, int.class);
		method.setAccessible(true);
		return (double) method.invoke(null, bytesPerWorker, workers);
	}

	private static double balancedPayload(double totalBytes, int workers) throws Exception {
		Method method = FederatedCostModel.class.getDeclaredMethod(
			"computeCalibratedGetResponsePayloadCost", double.class, int.class);
		method.setAccessible(true);
		return (double) method.invoke(null, totalBytes, workers);
	}
}
