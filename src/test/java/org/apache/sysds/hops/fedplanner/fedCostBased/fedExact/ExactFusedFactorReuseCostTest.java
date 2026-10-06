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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactFusedFactorReuseCostTest {
	@Test public void selectedAlignedFactorRemovesThePhysicalMovementCost() throws Exception {
		double aligned = price(true, false, false, false);
		double remote = price(false, false, false, false);
		Assert.assertTrue("Already aligned U must avoid both its collect and sliced PUT; aligned="
			+ aligned + " remote=" + remote, aligned < remote);
	}

	@Test public void alignedReuseRemovesBothGetAndPut() throws Exception {
		Evaluation e = evaluate(fixture(true, false, false), false);
		Assert.assertEquals(0.0, e.getCost(), 0.0);
		Assert.assertEquals(0.0, e.putCost(), 0.0);
		var removed = PlacementCostSemantics.prepareExecutionCosts(e.fixture().analysis(), null);
		Assert.assertTrue(e.surface().transferKeys().stream().flatMap(k -> k.endpoints().stream())
			.noneMatch(edge -> removed.get(edge.consumer()).removedKernel()));
	}

	@Test public void remoteFactorCollectsOnceAndUploadsForTheOwner() throws Exception {
		Evaluation e = evaluate(fixture(false, false, false), false);
		Assert.assertEquals(e.expectedGet(), e.getCost(), 1e-10);
		Assert.assertEquals(FederatedCostModel.computeInBandUploadPayloadCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(e.fixture().u()), FType.ROW, 2), e.putCost(), 1e-10);
	}

	@Test public void cpOwnerStillCollectsEvenAnAlignedFactor() throws Exception {
		for(boolean aligned : new boolean[] {true, false}) {
			Evaluation e = evaluate(fixture(aligned, false, false), true);
			Assert.assertEquals(e.expectedGet(), e.getCost(), 1e-10);
			Assert.assertEquals(0.0, e.putCost(), 0.0);
		}
	}

	@Test public void retainedCpConsumerAndRuntimeOwnerShareOneGet() throws Exception {
		for(boolean aligned : new boolean[] {true, false}) {
			Evaluation e = evaluate(fixture(aligned, true, false), false);
			Assert.assertEquals(e.expectedGet(), e.getCost(), 1e-10);
			Assert.assertEquals(1, e.surface().transferKeys().stream()
				.filter(k -> k.direction() == ExactPhysicalCostModel.Direction.DOWNLOAD
					&& k.endpoints().stream().anyMatch(edge -> edge.producer() == e.fixture().key(e.fixture().u())))
				.count());
		}
	}

	@Test public void twoRuntimeOwnersShareOneGetButEachOwnsItsPut() throws Exception {
		Evaluation e = evaluate(fixture(false, false, true), false);
		Assert.assertEquals(e.expectedGet(), e.getCost(), 1e-10);
		Assert.assertEquals(2 * FederatedCostModel.computeInBandUploadPayloadCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(e.fixture().u()), FType.ROW, 2), e.putCost(), 1e-10);
	}

	@Test public void colWeightsReuseActualVThroughColTransposeAlignment() throws Exception {
		Evaluation aligned = evaluate(fixture(true, false, false, true), false);
		Assert.assertEquals(0.0, aligned.getCost(), 0.0);
		Assert.assertEquals(0.0, aligned.putCost(), 0.0);
		Evaluation remote = evaluate(fixture(false, false, false, true), false);
		Assert.assertEquals(remote.expectedGet(), remote.getCost(), 1e-10);
		Assert.assertEquals(FederatedCostModel.computeInBandUploadPayloadCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(remote.fixture().factor()), FType.COL, 2),
			remote.putCost(), 1e-10);
	}

	static double price(boolean aligned, boolean sharedOuter, boolean cpOwner, boolean secondOwner) throws Exception {
		Evaluation e = evaluate(fixture(aligned, sharedOuter, secondOwner), cpOwner);
		return Double.longBitsToDouble(e.surface().evaluateCanonical(e.values()));
	}

	/** Price explicit cost-table cells, not an assertion that the entire assignment is a solved plan. */
	static Evaluation evaluate(Fixture fixture, boolean cpOwner) {
		var analysis = fixture.analysis();
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertEquals(fixture.owners().size(), surface.contributions().stream()
			.filter(c -> c.id().contains("RUNTIME_FUSED_KERNEL")).count());
		List<Integer> values = new ArrayList<>();
		for(var domain : model.domains()) {
			Hop hop = analysis.hop(domain.node().key()).orElse(null);
			boolean owner = fixture.owners().contains(hop);
			boolean federatedSource = hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
			ExecType exec = federatedSource || owner && !cpOwner ? ExecType.FED : ExecType.CP;
			FederatedOutput output = federatedSource ? FederatedOutput.FOUT : FederatedOutput.LOUT;
			int chosen = -1;
			for(int index = 0; index < domain.alternatives().size(); index++) {
				var state = domain.alternatives().get(index).state();
				if(state.execType() == exec && state.output() == output) { chosen = index; break; }
			}
			Assert.assertTrue("Fixture must expose selected cost cell for " + hop + " owner=" + owner, chosen >= 0);
			values.add(chosen);
		}
		return new Evaluation(fixture, model, surface, values);
	}

	record Evaluation(Fixture fixture, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, List<Integer> values) {
		double putCost() {
			return surface.contributions().stream().filter(c -> c.id().contains("RUNTIME_FUSED_FACTOR_PUT_" + fixture.factorPosition()))
				.mapToDouble(c -> surface.evaluateContributionCanonical(c, values)).sum();
		}
		double getCost() {
			var source = model.domains().stream().filter(d -> d.node().key() == fixture.key(fixture.factor()))
				.findFirst().orElseThrow().variable();
			return surface.contributions().stream().filter(c -> c.factor().scope().size() > 1
				&& c.factor().scope().get(0) == source && !c.id().contains("RUNTIME_FUSED_FACTOR_PUT"))
				.mapToDouble(c -> surface.evaluateContributionCanonical(c, values)).sum();
		}
		double expectedGet() {
			return FederatedCostModel.computeReusableMaterializationDownloadCost(
				FederatedCostModel.getEffectiveOutputMemEstimate(fixture.factor()), FType.ROW, 2);
		}
	}

	static Fixture fixture(boolean aligned, boolean sharedOuter, boolean secondOwner) throws Exception {
		return fixture(aligned, sharedOuter, secondOwner, false);
	}

	static Fixture fixture(boolean aligned, boolean sharedOuter, boolean secondOwner, boolean column) throws Exception {
		String script = (column
			? "W=federated(addresses=list(\"localhost:1234/W\",\"localhost:1235/W\"),"
				+ "ranges=list(list(0,0),list(1000,50),list(0,50),list(1000,100)));"
				+ "U=rand(rows=1000,cols=2,seed=7);" + fed("V", aligned ? 1234 : 2234, 100, 2)
			: fed("W", 1234, 1000, 100) + fed("U", aligned ? 1234 : 2234, 1000, 2)
				+ "V=rand(rows=100,cols=2,seed=8);")
			+ "print(sum(W));print(sum(U));print(sum(V));";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		List<Hop> hops = new ArrayList<>();
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var block : program.getStatementBlocks())
			for(Hop root : block.getHops()) collect(root, seen, hops);
		Hop w = namedMatrix(hops, "W"), u = namedMatrix(hops, "U"), v = namedMatrix(hops, "V");
		w.setDim1(1000); w.setDim2(100);
		u.setDim1(1000); u.setDim2(2);
		v.setDim1(100); v.setDim2(2);
		Hop outer = HopRewriteUtils.createMatrixMultiply(u, HopRewriteUtils.createTranspose(v));
		Hop weighted = HopRewriteUtils.createBinary(w, outer, OpOp2.MULT);
		Hop owner = column ? HopRewriteUtils.createMatrixMultiply(weighted, v)
			: HopRewriteUtils.createTranspose(HopRewriteUtils.createMatrixMultiply(HopRewriteUtils.createTranspose(u), weighted));
		List<Hop> owners = new ArrayList<>(List.of(owner));
		List<Hop> roots = new ArrayList<>(List.of(write("H", owner)));
		if(sharedOuter) roots.add(write("shared", outer));
		if(secondOwner) {
			Hop secondOuter = HopRewriteUtils.createMatrixMultiply(u, HopRewriteUtils.createTranspose(v));
			Hop second = HopRewriteUtils.createMatrixMultiply(HopRewriteUtils.createBinary(w, secondOuter, OpOp2.MULT), v);
			owners.add(second);
			roots.add(write("H2", second));
		}
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(roots));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return new Fixture(CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program),
			w, u, v, outer, owners, column);
	}

	private static Hop namedMatrix(List<Hop> hops, String name) {
		return hops.stream().filter(h -> (h instanceof DataOp || h instanceof DataGenOp)
			&& h.getName().equals(name)).findFirst().orElseThrow();
	}

	private static Hop write(String name, Hop hop) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, hop, OpOpData.TRANSIENTWRITE, name);
	}

	private static String fed(String name, int port, int rows, int cols) {
		return name + "=federated(addresses=list(\"localhost:" + port + "/" + name + "\",\"localhost:"
			+ (port + 1) + "/" + name + "\"),ranges=list(list(0,0),list(" + (rows / 2) + "," + cols
			+ "),list(" + (rows / 2) + ",0),list(" + rows + "," + cols + ")));";
	}

	private static void collect(Hop hop, Set<Hop> seen, List<Hop> hops) {
		if(!seen.add(hop)) return;
		hop.setBlocksize(1000);
		hops.add(hop);
		for(Hop input : hop.getInput()) collect(input, seen, hops);
	}

	record Fixture(PlacementAnalysis analysis, Hop w, Hop u, Hop v, Hop outer, List<Hop> owners, boolean column) {
		Hop factor() { return column ? v : u; }
		int factorPosition() { return column ? 2 : 1; }
		CompiledHopKey key(Hop hop) {
			return analysis.graph().nodes().stream().map(n -> n.key())
				.filter(k -> analysis.hop(k).orElse(null) == hop).findFirst().orElseThrow();
		}
	}
}
