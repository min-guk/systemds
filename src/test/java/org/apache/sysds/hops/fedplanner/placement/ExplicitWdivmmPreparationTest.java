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

package org.apache.sysds.hops.fedplanner.placement;

import java.util.Arrays;
import java.util.List;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.junit.Assert;
import org.junit.Test;

public class ExplicitWdivmmPreparationTest {
	@Test public void ftypeAloneMustNotAuthorizeFreeFactorReuse() {
		QuaternaryOp op = operation(1, new LiteralOp(0));
		double knownLocal = FederatedCostModel.computeWdivmmInputPreparationCost(op, op.getInput(),
			Arrays.asList(FType.ROW, null, null, null), 2);
		double typeOnly = FederatedCostModel.computeWdivmmInputPreparationCost(op, op.getInput(),
			Arrays.asList(FType.ROW, FType.ROW, null, null), 2);
		Assert.assertEquals("Without actual ranges/addresses U cannot be assumed aligned", knownLocal, typeOnly, 0.0);
	}
	@Test public void matrixEpsMustNotBeUploadedAsAMatrix() {
		QuaternaryOp scalar = operation(3, new LiteralOp(0.01));
		QuaternaryOp matrix = operation(3, matrix("eps", 1000, 100));
		List<FType> local = Arrays.asList(FType.ROW, null, null, null);
		Assert.assertEquals("Both EPS forms send one scalar, never the full matrix",
			FederatedCostModel.computeWdivmmInputPreparationCost(scalar, scalar.getInput(), local, 2),
			FederatedCostModel.computeWdivmmInputPreparationCost(matrix, matrix.getInput(), local, 2), 0.0);
	}
	@Test public void rowAndFullGeometryControlBothCollectionAndPreparation() {
		var op = operation(1, new LiteralOp(0));
		for(FType type : List.of(FType.ROW, FType.FULL)) {
			var w = layout(type, "a", 1000, 100);
			var aligned = new FederatedExecutionLayout(type, 1,
				List.of(w, layout(type, "a", 1000, 2), layout(FType.FULL, "a", 100, 2)));
			var remote = new FederatedExecutionLayout(type, 1,
				List.of(w, layout(type, "b", 1000, 2), layout(FType.FULL, "a", 100, 2)));
			Assert.assertFalse(PlacementCostSemantics.wdivmmInputNeedsCollection(op, aligned, 1));
			Assert.assertTrue(PlacementCostSemantics.wdivmmInputNeedsCollection(op, aligned, 2));
			Assert.assertTrue(PlacementCostSemantics.wdivmmInputNeedsCollection(op, remote, 1));
			Assert.assertEquals(FederatedCostModel.computeInBandUploadPayloadCost(
				FederatedCostModel.getEffectiveOutputMemEstimate(op.getInput(1)), type, 1),
				preparation(op, remote) - preparation(op, aligned), 1e-10);
		}
	}
	@Test public void fourthMatrixIsMxOnlyForBaseLeftAndRight() {
		var mx = matrix("MX", 1000, 100);
		var w = layout(FType.ROW, "a", 1000, 100);
		var inputs = new FederatedExecutionLayout(FType.ROW, 1,
			List.of(w, layout(FType.ROW, "a", 1000, 2), layout(FType.FULL, "a", 100, 2), w));
		for(int type : new int[] {1, 2}) {
			var op = operation(type, mx);
			Assert.assertTrue(PlacementCostSemantics.isWdivmmMatrixOperand(op, 3));
			Assert.assertFalse(PlacementCostSemantics.wdivmmInputNeedsCollection(op, inputs, 3));
		}
		for(int type : new int[] {3, 4}) {
			var op = operation(type, mx);
			Assert.assertFalse(PlacementCostSemantics.isWdivmmMatrixOperand(op, 3));
			Assert.assertTrue(PlacementCostSemantics.wdivmmInputNeedsCollection(op, inputs, 3));
			Assert.assertFalse(PlacementCostSemantics.wdivmmInputNeedsCollection(
				operation(type, new LiteralOp(0.01)), inputs, 3));
		}
	}
	@Test public void mxRequiresEveryFullRangeToMapToTheSameAddress() {
		var w = layout(FType.FULL, "a", 1000, 100);
		var replicas = new InputLayout(FType.BROADCAST, List.of(w.ranges().get(0),
			layout(FType.FULL, "b", 1000, 100).ranges().get(0)), true);
		Assert.assertTrue(PlacementCostSemantics.reusesWdivmmFactor(w, w, 3, false));
		Assert.assertFalse(PlacementCostSemantics.reusesWdivmmFactor(w, replicas, 3, false));
		Assert.assertFalse(PlacementCostSemantics.reusesWdivmmFactor(w,
			layout(FType.FULL, "a", 1000, 99), 3, false));
	}
	@Test public void matrixEpsDoesNotIncreaseWorkerKernelReadTraffic() {
		Assert.assertEquals(workerCost(operation(3, new LiteralOp(0.01))),
			workerCost(operation(3, matrix("eps", 1000, 100))), 0d);
	}
	@Test public void remoteFactorWorkersCannotGenerateWdivmmResponses() {
		var op = operation(1, new LiteralOp(0)); op.setDim1(100); op.setDim2(2);
		var block = new org.apache.sysds.parser.StatementBlock();
		block.setHops(new java.util.ArrayList<>(List.of(op)));
		var program = new org.apache.sysds.parser.DMLProgram(); program.addStatementBlock(block);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var key = analysis.graph().nodes().stream().map(n -> n.key())
			.filter(k -> analysis.hop(k).orElse(null) == op).findFirst().orElseThrow();
		var prepared = PlacementCostSemantics.prepareExecutionCost(analysis, key);
		var observed = new FederatedExecutionLayout(FType.ROW, 1,
			List.of(layout(FType.ROW, "W-worker", 1000, 100), layout(FType.ROW, "U-worker", 1000, 2)));
		var responses = prepared.outputResponses(observed, 1600d);
		Assert.assertEquals(1, responses.responses());
		Assert.assertEquals(1600d, responses.totalBytes(), 0d);
		Assert.assertEquals(1600d, responses.largestBytes(), 0d);
	}

	private static double workerCost(QuaternaryOp op) {
		op.setDim1(100); op.setDim2(2);
		var block = new org.apache.sysds.parser.StatementBlock();
		block.setHops(new java.util.ArrayList<>(List.of(op)));
		var program = new org.apache.sysds.parser.DMLProgram(); program.addStatementBlock(block);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var key = analysis.graph().nodes().stream().map(n -> n.key())
			.filter(k -> analysis.hop(k).orElse(null) == op).findFirst().orElseThrow();
		return PlacementCostSemantics.prepareExecutionCost(analysis, key).federatedCost(
			new FederatedExecutionLayout(FType.ROW, 1, List.of(layout(FType.ROW, "a", 1000, 100))));
	}
	private static double preparation(QuaternaryOp op, FederatedExecutionLayout layout) {
		return FederatedCostModel.computeWdivmmInputPreparationCost(op, op.getInput(),
			layout.inputs().stream().map(InputLayout::fType).toList(), layout.workers(), layout);
	}
	private static InputLayout layout(FType type, String worker, long rows, long cols) {
		return new InputLayout(type, List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(rows, cols))), true);
	}

	static QuaternaryOp operation(int type, Hop fourth) {
		return new QuaternaryOp("wd", DataType.MATRIX, ValueType.FP64, OpOp4.WDIVMM,
			matrix("W", 1000, 100), matrix("U", 1000, 2), matrix("V", 100, 2), fourth, type, false, false);
	}
	static Hop matrix(String name, int rows, int cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, -1, 1000);
	}
}
