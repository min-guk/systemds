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

import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.PreparedExecutionCost;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Physical work-quantity regressions for the shared CP/FED execution cost. */
public class PreparedExecutionCostQuantityTest {
	@Test
	public void oneWorkerPartitionMatchesLocalKernel() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=10,seed=7); Y=exp(X); print(sum(Y));");
		CompiledHopKey key = find(analysis, hop -> hop instanceof UnaryOp);
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis, key);
		double fed = prepared.federatedCost(layout(FType.ROW, 1,
			rowInput(100, 10, new long[] {0, 100})));
		Assert.assertEquals(prepared.localCost(), fed, 1e-12);
	}

	@Test
	public void aggregateWorkerScanGrowsWithInputWhileOutputShapeStaysFixed() throws Exception {
		PreparedExecutionCost narrow = aggregate("X=rand(rows=100,cols=10,seed=7); Y=rowSums(X); print(sum(Y));");
		PreparedExecutionCost wide = aggregate("X=rand(rows=100,cols=100,seed=7); Y=rowSums(X); print(sum(Y));");
		double narrowCost = narrow.federatedCost(layout(FType.ROW, 2,
			rowInput(100, 10, new long[] {0, 50, 100})));
		double wideCost = wide.federatedCost(layout(FType.ROW, 2,
			rowInput(100, 100, new long[] {0, 50, 100})));
		Assert.assertTrue("worker input reduction must not be capped by equal 100x1 output", wideCost > narrowCost);
	}

	@Test
	public void replicatedInputDoesFullWorkButPartitionedInputUsesLargestShard() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=20,seed=7); Y=exp(X); print(sum(Y));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof UnaryOp));
		double partitioned = prepared.federatedCost(layout(FType.ROW, 2,
			rowInput(100, 20, new long[] {0, 50, 100})));
		double replicated = prepared.federatedCost(layout(FType.BROADCAST, 2,
			new InputLayout(FType.BROADCAST, List.of(), false)));
		Assert.assertTrue(partitioned < replicated);
		Assert.assertEquals(prepared.localCost(), replicated, 1e-12);
	}

	@Test
	public void unknownPartLayoutDoesNotAssumeDisjointWorkerWork() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=20,seed=7); Y=exp(X); print(sum(Y));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof UnaryOp));
		double unknownPart = prepared.federatedCost(layout(FType.PART, 3,
			new InputLayout(FType.PART, List.of(), false)));
		Assert.assertEquals("overlapping PART work needs ranges before any division",
			prepared.localCost(), unknownPart, 1e-12);
	}

	@Test
	public void transposeUsesShardWorkAndSkewUsesLargestWorker() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=20,seed=7); Y=t(X); print(sum(Y));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof ReorgOp));
		double balanced = prepared.federatedCost(layout(FType.ROW, 2,
			rowInput(100, 20, new long[] {0, 50, 100})));
		double skewed = prepared.federatedCost(layout(FType.ROW, 2,
			rowInput(100, 20, new long[] {0, 90, 100})));
		Assert.assertTrue(balanced < prepared.localCost());
		Assert.assertTrue(skewed > balanced);
	}

	@Test
	public void multiplePartitionsOnOneWorkerAreSummedBeforeCriticalPathMax() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=20,seed=7); Y=exp(X); print(sum(Y));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof UnaryOp));
		InputLayout grouped = new InputLayout(FType.ROW, List.of(
			new AnchorPartition("w0", List.of(0L, 0L), List.of(25L, 20L)),
			new AnchorPartition("w0", List.of(25L, 0L), List.of(50L, 20L)),
			new AnchorPartition("w1", List.of(50L, 0L), List.of(100L, 20L))), true);
		double groupedCost = prepared.federatedCost(layout(FType.ROW, 2, grouped));
		double balancedCost = prepared.federatedCost(layout(FType.ROW, 2,
			rowInput(100, 20, new long[] {0, 50, 100})));
		Assert.assertEquals(balancedCost, groupedCost, 1e-12);
	}

	@Test
	public void rowPartitionedMatmulReadsReplicatedRhsPerWorker() throws Exception {
		PlacementAnalysis analysis = analyze("A=rand(rows=2,cols=100,seed=7); B=rand(rows=100,cols=50000,seed=8);"
			+ " C=A%*%B; print(sum(C));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof AggBinaryOp ab && ab.isMatrixMultiply()));
		double fed = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2, List.of(
			rowInput(2, 100, new long[] {0, 1, 2}),
			new InputLayout(null, List.of(), false))));
		Assert.assertTrue(fed < prepared.localCost());
		Assert.assertTrue("full RHS read prevents a blind exact /W price", fed > prepared.localCost() / 2.0);
	}

	@Test
	public void sharedDimensionPartitionedMatmulWritesFullPartialOutput() throws Exception {
		PlacementAnalysis analysis = analyze("A=rand(rows=40,cols=200,seed=7); B=rand(rows=200,cols=30,seed=8);"
			+ " C=A%*%B; print(sum(C));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof AggBinaryOp ab && ab.isMatrixMultiply()));
		InputLayout localA = new InputLayout(null, List.of(), false);
		InputLayout rowB = rowInput(200, 30, new long[] {0, 100, 200});
		double fed = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2,
			List.of(localA, rowB)));
		Assert.assertTrue("each worker materializes one full overlapping partial", fed > prepared.localCost() / 2.0);
	}

	@Test
	public void colPartitionedLeftMatmulSlicesLocalRightRowsAndWritesFullPartial() throws Exception {
		PlacementAnalysis analysis = analyze("A=rand(rows=40,cols=200,seed=7); B=rand(rows=200,cols=30,seed=8);"
			+ " C=A%*%B; print(sum(C));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof AggBinaryOp ab && ab.isMatrixMultiply()));
		InputLayout colA = new InputLayout(FType.COL, List.of(
			new AnchorPartition("w0", List.of(0L, 0L), List.of(40L, 100L)),
			new AnchorPartition("w1", List.of(0L, 100L), List.of(40L, 200L))), true);
		double fed = prepared.federatedCost(new FederatedExecutionLayout(FType.COL, 2,
			List.of(colA, local())));
		Assert.assertTrue(fed > prepared.localCost() / 2.0);
	}

	@Test
	public void indexingExactRangesExecuteOnlyOverlappingWorker() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=20,seed=7); Y=X[1:10,1:20]; print(sum(Y));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof IndexingOp));
		List<InputLayout> exactInputs = List.of(rowInput(100, 20, new long[] {0, 50, 100}),
			local(), local(), local(), local());
		double exact = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2, exactInputs));
		double unknown = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2,
			List.of(new InputLayout(FType.ROW, List.of(), false), local(), local(), local(), local())));
		Assert.assertEquals(prepared.localCost(), exact, 1e-12);
		Assert.assertTrue(exact > unknown);
	}

	@Test
	public void wdivmmWorkUsesRankAndActiveWeights() {
		double rank2Dense = ComputeCost.getWdivmmComputeCost(100, 50, -1, 2);
		double rank20Dense = ComputeCost.getWdivmmComputeCost(100, 50, -1, 20);
		double rank20Sparse = ComputeCost.getWdivmmComputeCost(100, 50, 100, 20);
		Assert.assertEquals(rank2Dense * 10.0, rank20Dense, 0.0);
		Assert.assertEquals(4.0 * 20.0 * 100.0, rank20Sparse, 0.0);
		Assert.assertTrue(rank20Sparse < rank20Dense);
		Assert.assertEquals(0.0, ComputeCost.getWdivmmComputeCost(100, 50, 0, 20), 0.0);
	}

	@Test
	public void localFullShapeElementwiseOperandIsSlicedButBroadcastIsReplicated() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=20,seed=7);"
			+ " L=rand(rows=100,cols=20,seed=8); Y=X+L; print(sum(Y));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof BinaryOp));
		InputLayout x = rowInput(100, 20, new long[] {0, 50, 100});
		double sliced = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2,
			List.of(x, local())));
		double replicated = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2,
			List.of(x, new InputLayout(FType.BROADCAST, List.of(), false))));
		Assert.assertTrue("same-shape local operand must follow runtime row slicing",
			sliced < replicated);
	}

	@Test
	public void localRowVectorRemainsBroadcastAcrossRowPartitions() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=100,cols=20,seed=7);"
			+ " b=rand(rows=1,cols=20,seed=8); Y=X+b; print(sum(Y));");
		PreparedExecutionCost prepared = PlacementCostSemantics.prepareExecutionCost(analysis,
			find(analysis, hop -> hop instanceof BinaryOp));
		InputLayout x = rowInput(100, 20, new long[] {0, 50, 100});
		double localVector = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2,
			List.of(x, local())));
		double broadcastVector = prepared.federatedCost(new FederatedExecutionLayout(FType.ROW, 2,
			List.of(x, new InputLayout(FType.BROADCAST, List.of(), false))));
		Assert.assertEquals(broadcastVector, localVector, 1e-12);
	}

	private static PreparedExecutionCost aggregate(String script) throws Exception {
		PlacementAnalysis analysis = analyze(script);
		CompiledHopKey key = find(analysis, hop -> hop instanceof AggUnaryOp au
			&& au.getDirection() == Direction.Row);
		return PlacementCostSemantics.prepareExecutionCost(analysis, key);
	}

	private static FederatedExecutionLayout layout(FType type, int workers, InputLayout... inputs) {
		return new FederatedExecutionLayout(type, workers, List.of(inputs));
	}

	private static InputLayout local() {
		return new InputLayout(null, List.of(), false);
	}

	private static InputLayout rowInput(long rows, long cols, long[] boundaries) {
		java.util.ArrayList<AnchorPartition> ranges = new java.util.ArrayList<>();
		for(int i = 0; i + 1 < boundaries.length; i++)
			ranges.add(new AnchorPartition("w" + i, List.of(boundaries[i], 0L),
				List.of(boundaries[i + 1], cols)));
		return new InputLayout(FType.ROW, ranges, true);
	}

	private static CompiledHopKey find(PlacementAnalysis analysis, Predicate<Hop> predicate) {
		return analysis.graph().nodes().stream().map(node -> node.key())
			.filter(key -> analysis.hop(key).map(predicate::test).orElse(false))
			.findFirst().orElseThrow();
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}
