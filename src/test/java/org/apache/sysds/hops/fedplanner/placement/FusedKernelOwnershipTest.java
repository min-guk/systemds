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
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class FusedKernelOwnershipTest {
	@Test public void latentOwnerAloneOwnsUnsharedKernel() { assertOwnership(false, false, false); }
	@Test public void directOwnerAloneOwnsUnsharedKernel() { assertOwnership(true, false, false); }
	@Test public void emptyWeightsRetainDirectKernelOwnership() { assertEmptyWeightsOwnership(true); }
	@Test public void emptyWeightsRetainLatentKernelOwnership() { assertEmptyWeightsOwnership(false); }
	@Test public void sharedWeightedIntermediateStillExecutesAndReceivesWeights() {
		Fixture fixture = assertOwnership(false, true, false);
		var boundaries = PlacementCostSemantics.latentWdivmmRuntimeTransferBoundaries(fixture.analysis());
		Assert.assertFalse(boundaries.stream().anyMatch(edge -> edge.producer() == fixture.key(fixture.weights())
			&& edge.consumer() == fixture.key(fixture.weighted())));
	}
	@Test public void sharedOuterProductStillExecutes() { assertOwnership(false, false, true); }
	@Test public void latentOwnerWithoutExplicitFactorTransposePricesTheCreatedTranspose() {
		Fixture explicit = fixture(false, false, false);
		Fixture implicit = fixture(false, false, false, false);
		var direct = PlacementCostSemantics.prepareExecutionCost(explicit.analysis(), explicit.key(explicit.owner()));
		var created = PlacementCostSemantics.prepareExecutionCost(implicit.analysis(), implicit.key(implicit.owner()));
		Assert.assertNotNull(created.fusedWeightsOccurrence());
		Assert.assertTrue("The rewrite creates a real coordinator factor transpose",
			created.fusedInputPreparationCost(FType.ROW, 2) > direct.fusedInputPreparationCost(FType.ROW, 2));
		Assert.assertTrue(created.localCost() > direct.localCost());
	}
	@Test public void directOwnerRetainsAnExistingRuntimeFactorTranspose() {
		Fixture fixture = fixture(true, false, false, false);
		Hop factor = fixture.owner().getInput(1);
		var costs = PlacementCostSemantics.prepareExecutionCosts(fixture.analysis(), null);
		var owner = costs.get(fixture.key(fixture.owner()));
		Assert.assertNotNull(owner.fusedWeightsOccurrence());
		Assert.assertTrue("The actual V=t(B) factor still executes once",
			costs.get(fixture.key(factor)).localCost() > 0.0);
		Assert.assertEquals(0.0, costs.get(fixture.key(fixture.outer())).localCost(), 0.0);
		Assert.assertEquals(0.0, costs.get(fixture.key(fixture.weighted())).localCost(), 0.0);
	}

	@Test public void latentKernelUsesWeightGeometryNotRankByColumnsShell() {
		Fixture fixture = fixture(false, false, false);
		var prepared = PlacementCostSemantics.prepareExecutionCost(fixture.analysis(), fixture.key(fixture.owner()));
		Assert.assertSame(fixture.key(fixture.weights()), prepared.fusedWeightsOccurrence());
		double balanced = prepared.federatedCost(layout(500));
		double skewed = prepared.federatedCost(layout(800));
		Assert.assertTrue("W is 1000x100, while the removed inner shell is 2x100", skewed > balanced);
	}

	private static FederatedExecutionLayout layout(int split) {
		return new FederatedExecutionLayout(FType.ROW, 2, List.of(new InputLayout(FType.ROW, List.of(
			new AnchorPartition("w0", List.of(0L, 0L), List.of((long)split, 100L)),
			new AnchorPartition("w1", List.of((long)split, 0L), List.of(1000L, 100L))), true)));
	}

	private static void assertEmptyWeightsOwnership(boolean direct) {
		Fixture f = fixture(direct, false, false);
		f.weights().setNnz(0);
		var costs = PlacementCostSemantics.prepareExecutionCosts(f.analysis(), null);
		Assert.assertSame("Zero FLOPs must not erase a structurally proven runtime kernel",
			f.key(f.weights()), costs.get(f.key(f.owner())).fusedWeightsOccurrence());
		double flops = direct ? PlacementCostSemantics.directWdivmmRuntimeFact(
			f.analysis(), f.key(f.owner())).computeFlops()
			: PlacementCostSemantics.latentWdivmmTransposePairFact(
				f.analysis(), f.key(f.owner())).computeFlops();
		Assert.assertEquals(0.0, flops, 0.0);
		Assert.assertTrue("The actual kernel still reads factors and writes its result",
			costs.get(f.key(f.owner())).localCost() > 0.0);
		Assert.assertEquals(0.0, costs.get(f.key(f.weighted())).localCost(), 0.0);
		Assert.assertEquals(0.0, costs.get(f.key(f.outer())).localCost(), 0.0);
		if(!direct)
			Assert.assertEquals(0.0, costs.get(f.key(f.inner())).localCost(), 0.0);
	}

	private static Fixture assertOwnership(boolean direct, boolean sharedWeighted, boolean sharedOuter) {
		Fixture f = fixture(direct, sharedWeighted, sharedOuter);
		var costs = PlacementCostSemantics.prepareExecutionCosts(f.analysis(), null);
		Assert.assertTrue(costs.get(f.key(f.owner())).localCost() > 0);
		Assert.assertEquals(sharedWeighted, costs.get(f.key(f.weighted())).localCost() > 0);
		Assert.assertEquals(sharedWeighted || sharedOuter, costs.get(f.key(f.outer())).localCost() > 0);
		if(!direct)
			Assert.assertEquals(0, costs.get(f.key(f.inner())).localCost(), 0.0);
		return f;
	}

	private static Fixture fixture(boolean direct, boolean sharedWeighted, boolean sharedOuter) {
		return fixture(direct, sharedWeighted, sharedOuter, true);
	}

	private static Fixture fixture(boolean direct, boolean sharedWeighted, boolean sharedOuter, boolean transpose) {
		Hop w = matrix("W", 1000, 100), u = matrix("U", 1000, 2), v = matrix("V", 100, 2);
		Hop outer = HopRewriteUtils.createMatrixMultiply(u,
			transpose ? HopRewriteUtils.createTranspose(v) : matrix("B", 2, 100));
		Hop weighted = HopRewriteUtils.createBinary(w, outer, OpOp2.MULT);
		Hop inner = direct ? HopRewriteUtils.createMatrixMultiply(weighted,
			transpose ? v : HopRewriteUtils.createTranspose(outer.getInput(1)))
			: HopRewriteUtils.createMatrixMultiply(HopRewriteUtils.createTranspose(u), weighted);
		Hop owner = direct ? inner : HopRewriteUtils.createTranspose(inner);
		List<Hop> roots = new ArrayList<>();
		roots.add(write("H", owner));
		if(sharedWeighted) roots.add(write("sharedWeighted", weighted));
		if(sharedOuter) roots.add(write("sharedOuter", outer));
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(roots));
		DMLProgram program = new DMLProgram();
		program.addStatementBlock(block);
		return new Fixture(new NeutralPlacementGraphBuilder().buildAnalysis(program), w, outer, weighted, inner, owner);
	}

	private static Hop matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, rows, cols, rows * cols, 1000);
	}
	private static Hop write(String name, Hop input) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, input, OpOpData.TRANSIENTWRITE, name);
	}
	private record Fixture(PlacementAnalysis analysis, Hop weights, Hop outer, Hop weighted, Hop inner, Hop owner) {
		CompiledHopKey key(Hop hop) {
			return analysis.graph().nodes().stream().map(node -> node.key())
				.filter(key -> analysis.hop(key).orElse(null) == hop).findFirst().orElseThrow();
		}
	}
}
