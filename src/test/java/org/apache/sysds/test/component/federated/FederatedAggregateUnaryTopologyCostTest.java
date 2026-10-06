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

package org.apache.sysds.test.component.federated;

import java.util.List;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel.MixedFedLocalCost;
import org.junit.Assert;
import org.junit.Test;

/** Runtime-topology regression for AggregateUnaryFEDInstruction's coordinator phase. */
public class FederatedAggregateUnaryTopologyCostTest {
	private static final int WORKERS = 4;
	private static final long ROWS = 100_000;
	private static final long COLS = 64;

	@Test
	public void alignedRowAggregateBindsDisjointResponses() {
		assertDisjointBind(aggregate("rowSums", Direction.Row, ROWS, 1), FType.ROW);
	}

	@Test
	public void alignedColumnAggregateBindsDisjointResponses() {
		assertDisjointBind(aggregate("colSums", Direction.Col, 1, COLS), FType.COL);
	}

	@Test
	public void oppositeAxisAggregateRetainsArithmeticMergeScaling() {
		AggUnaryOp colSums = aggregate("colSumsAcrossRowPartitions", Direction.Col, 1, COLS);
		MixedFedLocalCost twoWorkers = cost(colSums, FType.ROW, 2);
		MixedFedLocalCost fourWorkers = cost(colSums, FType.ROW, WORKERS);
		double outputMem = outputMem(colSums);

		Assert.assertTrue("Overlapping partial vectors require more coordinator merge work as fan-in grows",
			fourWorkers.getCoordinatorLocalCost() > twoWorkers.getCoordinatorLocalCost());
		Assert.assertTrue("Arithmetic merge must cost more than one output read and write",
			fourWorkers.getCoordinatorLocalCost()
				> 2 * FederatedCostModel.computeMemoryAccessCost(outputMem));
		Assert.assertEquals("Every worker returns one full partial vector, so parallel critical payload"
			+ " remains one full-output equivalent",
			twoWorkers.getPartialResultDownloadCost(),
			fourWorkers.getPartialResultDownloadCost(), 1e-12);
	}

	@Test
	public void broadcastAggregateSelectsOneReplicaWithoutArithmeticReduction() {
		AggUnaryOp rowSums = aggregate("broadcastRowSums", Direction.Row, ROWS, 1);
		MixedFedLocalCost oneWorker = cost(rowSums, FType.BROADCAST, 1);
		MixedFedLocalCost fourWorkers = cost(rowSums, FType.BROADCAST, WORKERS);

		Assert.assertEquals("Replicated results adopt an existing block without a coordinator payload scan",
			0.0, fourWorkers.getCoordinatorLocalCost(), 0.0);
		Assert.assertEquals("All W replicas respond; parallel critical-path payload remains one full result",
			oneWorker.getPartialResultDownloadCost(),
			fourWorkers.getPartialResultDownloadCost(), 1e-12);
		Assert.assertEquals("Replica count must not add coordinator arithmetic",
			oneWorker.getCoordinatorLocalCost(), fourWorkers.getCoordinatorLocalCost(), 1e-12);
	}

	@Test
	public void broadcastScalarAggregateSelectsOneReplica() {
		AggUnaryOp scalar = scalarAggregate("broadcastScalarSum");
		MixedFedLocalCost oneWorker = cost(scalar, FType.BROADCAST, 1);
		MixedFedLocalCost fourWorkers = cost(scalar, FType.BROADCAST, WORKERS);

		Assert.assertEquals("A replicated scalar adopts an existing result without coordinator payload work",
			0.0, fourWorkers.getCoordinatorLocalCost(), 0.0);
		Assert.assertEquals(oneWorker.getPartialResultDownloadCost(),
			fourWorkers.getPartialResultDownloadCost(), 1e-12);
		Assert.assertEquals(oneWorker.getCoordinatorLocalCost(),
			fourWorkers.getCoordinatorLocalCost(), 1e-12);
	}

	private static void assertDisjointBind(AggUnaryOp aggregate, FType type) {
		MixedFedLocalCost oneWorker = cost(aggregate, type, 1);
		MixedFedLocalCost fourWorkers = cost(aggregate, type, WORKERS);
		double bindCost = 2 * FederatedCostModel.computeMemoryAccessCost(outputMem(aggregate));

		Assert.assertEquals("Even a one-response dense bind copies through LibMatrixAppend",
			bindCost, oneWorker.getCoordinatorLocalCost(), 1e-12);
		Assert.assertEquals("Disjoint responses are bound with one full-output read and write",
			bindCost, fourWorkers.getCoordinatorLocalCost(), 1e-12);
		Assert.assertEquals("The aligned aggregate still has one response per worker on the network path",
			oneWorker.getPartialResultDownloadCost() / WORKERS,
			fourWorkers.getPartialResultDownloadCost(), 1e-12);
	}

	@Test
	public void broadcastScalarVarianceRetainsItsSeparateMomentMergePath() {
		Hop input = aggregate("varianceInput", Direction.Row, ROWS, 1).getInput(0);
		AggUnaryOp variance = new AggUnaryOp("broadcastVariance", DataType.SCALAR, ValueType.FP64,
			AggOp.VAR, Direction.RowCol, input);
		variance.setDim1(1);
		variance.setDim2(1);
		MixedFedLocalCost two = cost(variance, FType.BROADCAST, 2);
		MixedFedLocalCost four = cost(variance, FType.BROADCAST, WORKERS);
		Assert.assertTrue("processVar scalar uses the mean/variance merge overload, not select-first",
			two.getCoordinatorLocalCost() > 0.0);
		Assert.assertTrue(four.getCoordinatorLocalCost() > two.getCoordinatorLocalCost());
	}

	private static MixedFedLocalCost cost(AggUnaryOp aggregate, FType type, int workers) {
		return FederatedCostModel.computeMixedFedLocalCost(aggregate, aggregate.getInput(),
			List.of(type), type, 0.0, outputMem(aggregate), workers);
	}

	private static double outputMem(AggUnaryOp aggregate) {
		if(aggregate.getDataType().isScalar())
			return OptimizerUtils.DOUBLE_SIZE;
		return aggregate.getDim1() * (double) aggregate.getDim2() * OptimizerUtils.DOUBLE_SIZE;
	}

	private static AggUnaryOp scalarAggregate(String name) {
		DataOp input = new DataOp("X_" + name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X_" + name, ROWS, COLS, -1, 1024);
		return new AggUnaryOp(name, DataType.SCALAR, ValueType.FP64,
			AggOp.SUM, Direction.RowCol, input);
	}

	private static AggUnaryOp aggregate(String name, Direction direction, long rows, long cols) {
		DataOp input = new DataOp("X_" + name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X_" + name, ROWS, COLS, -1, 1024);
		AggUnaryOp aggregate = new AggUnaryOp(name, DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, direction, input);
		aggregate.setDim1(rows);
		aggregate.setDim2(cols);
		aggregate.setNnz(-1);
		return aggregate;
	}
}
