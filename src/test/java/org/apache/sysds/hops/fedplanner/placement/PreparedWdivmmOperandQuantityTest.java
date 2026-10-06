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

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** QuaternaryWDivMMFEDInstruction slices U on ROW and V on COL maps. */
public class PreparedWdivmmOperandQuantityTest {
	@Test public void rowWeightsSliceUAndReplicateV() { assertKernel(FType.ROW, 2); }
	@Test public void colWeightsReplicateUAndSliceV() { assertKernel(FType.COL, 1); }
	@Test public void rowLeftWritesFullPartialResult() { assertKernel(FType.ROW, 1); }
	@Test public void colRightWritesFullPartialResult() { assertKernel(FType.COL, 2); }

	@Test public void nonliteralSliceKeepsOutputBasedKernelCost() {
		Hop source = matrix("X", 1000, 100, 100000);
		Hop dynamicUpper = new DataOp("k", DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, "k", 0, 0, -1, 1000);
		IndexingOp slice = new IndexingOp("slice", DataType.MATRIX, ValueType.FP64,
			source, new LiteralOp(1), dynamicUpper, new LiteralOp(1), new LiteralOp(20), false, false);
		slice.setDim1(10);
		slice.setDim2(20);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(slice)));
		DMLProgram program = new DMLProgram();
		program.addStatementBlock(block);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var key = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(candidate -> analysis.hop(candidate).orElse(null) == slice).findFirst().orElseThrow();
		var prepared = PlacementCostSemantics.prepareExecutionCost(analysis, key);
		var layout = new FederatedExecutionLayout(FType.ROW, 1,
			List.of(new InputLayout(FType.ROW, List.of(), false)));
		Assert.assertEquals("Unknown coordinates do not turn indexing into a full source scan",
			prepared.localCost(), prepared.federatedCost(layout), 1e-12);
	}

	@Test public void repeatedOperandsEachContributeTheirKernelRead() {
		Hop x = matrix("X", 1000, 1000, 1000000);
		Hop sum = HopRewriteUtils.createBinary(x, x, OpOp2.PLUS);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(sum)));
		DMLProgram program = new DMLProgram();
		program.addStatementBlock(block);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var key = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(candidate -> analysis.hop(candidate).orElse(null) == sum).findFirst().orElseThrow();
		var prepared = PlacementCostSemantics.prepareExecutionCost(analysis, key);
		double expected = FederatedCostModel.computeExecutionCost(sum, ComputeCost.getHOPComputeCost(sum),
			2 * FederatedCostModel.getEffectiveOutputMemEstimate(x),
			FederatedCostModel.getEffectiveOutputMemEstimate(sum));
		Assert.assertEquals("One allocation does not erase the second input's read traffic", expected, prepared.localCost(), 1e-12);
	}

	private static void assertKernel(FType type, int baseType) {
		Hop weights = matrix("W", 90, 60, 7);
		Hop u = matrix("U", 90, 2, 180);
		Hop v = matrix("V", 60, 2, 120);
		QuaternaryOp operation = new QuaternaryOp("wd", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, weights, u, v, null, baseType, false, false);
		operation.setDim1(baseType == 1 ? 60 : 90);
		operation.setDim2(2);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(operation)));
		DMLProgram program = new DMLProgram();
		program.addStatementBlock(block);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var key = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(candidate -> analysis.hop(candidate).orElse(null) == operation)
			.findFirst().orElseThrow();
		var prepared = PlacementCostSemantics.prepareExecutionCost(analysis, key);
		for(int workers : new int[] {1, 3}) {
			List<AnchorPartition> ranges = new ArrayList<>();
			for(int i = 0; i < workers; i++)
				ranges.add(new AnchorPartition("w" + i,
					type == FType.ROW ? List.of(90L * i / workers, 0L) : List.of(0L, 60L * i / workers),
					type == FType.ROW ? List.of(90L * (i + 1) / workers, 60L)
						: List.of(90L, 60L * (i + 1) / workers)));
			double share = 1.0 / workers;
			double read = FederatedCostModel.getEffectiveOutputMemEstimate(weights) * share
				+ FederatedCostModel.getEffectiveOutputMemEstimate(u) * (type == FType.ROW ? share : 1.0)
				+ FederatedCostModel.getEffectiveOutputMemEstimate(v) * (type == FType.COL ? share : 1.0);
			boolean partial = baseType == 1 && type == FType.ROW || baseType == 2 && type == FType.COL;
			double expected = FederatedCostModel.computeExecutionCost(null,
				ComputeCost.getWdivmmComputeCost(90, 60, 7, 2) * share, read,
				FederatedCostModel.getEffectiveOutputMemEstimate(operation) * (partial ? 1.0 : share));
			var layout = new FederatedExecutionLayout(type, workers, List.of(
				new InputLayout(type, ranges, true), new InputLayout(null, List.of(), false),
				new InputLayout(null, List.of(), false)));
			Assert.assertEquals(type + " / " + baseType + " / " + workers,
				expected, prepared.federatedCost(layout), 1e-12);
			if(workers == 1)
				Assert.assertEquals(prepared.localCost(), expected, 1e-12);
		}
	}

	private static Hop matrix(String name, long rows, long cols, long nnz) {
		DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD, name, rows, cols, nnz, 1000);
		hop.setDim1(rows);
		hop.setDim2(cols);
		hop.setNnz(nnz);
		return hop;
	}
}
