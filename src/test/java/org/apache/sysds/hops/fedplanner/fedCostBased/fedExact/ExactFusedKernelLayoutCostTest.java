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

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactFusedKernelLayoutCostTest {
	@Test public void actualWeightSkewChangesFusedOwnerCost() throws Exception {
		Assert.assertTrue("Same two-worker pool but 80% vs 50% weight shard",
			fusedPrice(800) > fusedPrice(500));
	}

	private static void collect(Hop hop, Set<Hop> seen, List<Hop> hops) {
		if(!seen.add(hop)) return;
		hop.setBlocksize(1000);
		hops.add(hop);
		for(Hop input : hop.getInput()) collect(input, seen, hops);
	}

	private static double fusedPrice(int split) throws Exception {
		String script = "W=federated(addresses=list(\"localhost:1234/W\",\"localhost:1235/W\"),"
			+ "ranges=list(list(0,0),list(" + split + ",100),list(" + split + ",0),list(1000,100)));"
			+ "U=rand(rows=1000,cols=2,seed=7);V=rand(rows=100,cols=2,seed=8);"
			+ "H=t(t(U)%*%(W*(U%*%t(V))));print(sum(H));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program); // Keep the source shell; runtime will own the fused kernel.
		List<Hop> hops = new ArrayList<>();
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var block : program.getStatementBlocks())
			for(Hop root : block.getHops()) collect(root, seen, hops);
		Hop weightHop = hops.stream().filter(hop -> hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			.findFirst().orElseThrow();
		Hop u = hops.stream().filter(hop -> hop instanceof DataGenOp && hop.getDim1() == 1000 && hop.getDim2() == 2)
			.findFirst().orElseThrow();
		Hop v = hops.stream().filter(hop -> hop instanceof DataGenOp && hop.getDim1() == 100 && hop.getDim2() == 2)
			.findFirst().orElseThrow();
		// Construct the source DAG with shared U/V identity, normally supplied by CSE,
		// without applying the dynamic fusion being modeled by this test.
		Hop weighted = HopRewriteUtils.createBinary(weightHop,
			HopRewriteUtils.createMatrixMultiply(u, HopRewriteUtils.createTranspose(v)), OpOp2.MULT);
		Hop ownerHop = HopRewriteUtils.createTranspose(HopRewriteUtils.createMatrixMultiply(
			HopRewriteUtils.createTranspose(u), weighted));
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(new DataOp("H", DataType.MATRIX, ValueType.FP64,
			ownerHop, OpOpData.TRANSIENTWRITE, "H"))));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		var analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var weightedKey = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(key -> analysis.hop(key).orElse(null) == weighted).findFirst().orElseThrow();
		Assert.assertTrue("Removed weighted/full-matrix intermediates must not own transfer demands",
			surface.transferKeys().stream().flatMap(key -> key.endpoints().stream())
				.noneMatch(edge -> edge.producer() == weightedKey || edge.consumer() == weightedKey));
		var contribution = surface.contributions().stream()
			.filter(item -> item.id().contains("RUNTIME_FUSED_KERNEL")).findFirst().orElseThrow();
		var scope = contribution.factor().scope();
		Assert.assertEquals("Reuse the existing owner/weights legality dependency", 2, scope.size());
		var owner = model.domains().stream().filter(domain -> domain.variable() == scope.get(0)).findFirst().orElseThrow();
		var weights = model.domains().stream().filter(domain -> domain.variable() == scope.get(1)).findFirst().orElseThrow();
		Assert.assertSame(weights.node().key(), PlacementCostSemantics.prepareExecutionCost(
			analysis, owner.node().key()).fusedWeightsOccurrence());
		double maximum = 0.0;
		for(int o = 0; o < owner.alternatives().size(); o++)
			for(int w = 0; w < weights.alternatives().size(); w++) {
				double cost = contribution.factor().cost(new int[] {o, w});
				if(owner.alternatives().get(o).state().execType() == ExecType.CP)
					Assert.assertEquals("CP compute remains solely on the unary owner", 0.0, cost, 0.0);
				else maximum = Math.max(maximum, cost);
			}
		Assert.assertTrue("Fused FED cost must be represented", maximum > 0.0);
		return maximum;
	}
}
