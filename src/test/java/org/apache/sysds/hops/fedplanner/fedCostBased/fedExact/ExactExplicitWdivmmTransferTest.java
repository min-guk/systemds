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
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.common.Types.OpOp4;
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

public class ExactExplicitWdivmmTransferTest {
	@Test public void alignedReuseChangesBothPhysicalGetAndUnaryPut() throws Exception {
		var aligned = evaluate(true, false); var remote = evaluate(false, false);
		Assert.assertEquals(0d, aligned.get, 0d);
		Assert.assertEquals(remote.expectedGet, remote.get, 1e-10);
		Assert.assertEquals(remote.expectedPut, remote.unary - aligned.unary, 1e-10);
	}
	@Test public void explicitAndOrdinaryCpConsumersShareOneGet() throws Exception {
		var alone = evaluate(false, false); var shared = evaluate(false, true);
		Assert.assertEquals(alone.expectedGet, shared.get, 1e-10);
	}
	record Result(double get, double unary, double expectedGet, double expectedPut) { }
	private static Result evaluate(boolean aligned, boolean extraConsumer) throws Exception {
		String script = fed("W", 1234, 1000, 100) + fed("U", aligned ? 1234 : 2234, 1000, 2)
			+ "V=rand(rows=100,cols=2,seed=8);print(sum(W));print(sum(U));print(sum(V));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		List<Hop> hops = new ArrayList<>(); Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var block : program.getStatementBlocks()) for(Hop root : block.getHops()) collect(root, seen, hops);
		Hop w = namedMatrix(hops, "W"), u = namedMatrix(hops, "U"), v = namedMatrix(hops, "V");
		w.setDim1(1000); w.setDim2(100); u.setDim1(1000); u.setDim2(2); v.setDim1(100); v.setDim2(2);
		QuaternaryOp owner = new QuaternaryOp("wd", DataType.MATRIX, ValueType.FP64, OpOp4.WDIVMM,
			w, u, v, new LiteralOp(0), 1, false, false);
		owner.setDim1(100); owner.setDim2(2);
		List<Hop> roots = new ArrayList<>(List.of(write("H", owner)));
		if(extraConsumer) roots.add(write("ucopy", HopRewriteUtils.createUnary(u, org.apache.sysds.common.Types.OpOp1.ABS)));
		StatementBlock block = new StatementBlock(); block.setHops(new ArrayList<>(roots));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		var analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		var model = ExactPhysicalModel.build(analysis); var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<Integer> values = new ArrayList<>();
		ExactPhysicalModel.DecisionDomain uDomain = null, ownerDomain = null;
		for(var domain : model.domains()) {
			Hop hop = analysis.hop(domain.node().key()).orElse(null);
			boolean fedSource = hop instanceof DataOp d && d.getOp() == OpOpData.FEDERATED;
			int chosen = -1;
			for(int i = 0; i < domain.alternatives().size(); i++) {
				var alt = domain.alternatives().get(i); var state = alt.state();
				if(state.execType() != (fedSource || hop == owner ? ExecType.FED : ExecType.CP)
					|| state.output() != (fedSource ? FederatedOutput.FOUT : FederatedOutput.LOUT)) continue;
				if(hop == owner && (!alt.orderedInputs().get(0).present() || !alt.orderedInputs().get(1).present()
					|| alt.orderedInputs().get(2).present() || alt.inputAuthorities().stream().anyMatch(a ->
						a.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION))) continue;
				if(hop == owner) Assert.assertEquals("Only W workers execute explicit WDivMM", 2,
					ExactPhysicalCostModel.executionWorkerCount(analysis, alt, ExactPhysicalCostModel.workerCount(analysis.graph())));
				chosen = i; break;
			}
			Assert.assertTrue("Missing exact explicit cost cell " + hop, chosen >= 0); values.add(chosen);
			if(hop == u) uDomain = domain; if(hop == owner) ownerDomain = domain;
		}
		var source = uDomain.variable(); var target = ownerDomain.variable();
		double get = surface.contributions().stream().filter(c -> c.factor().scope().size() > 1 && c.factor().scope().get(0) == source)
			.mapToDouble(c -> surface.evaluateContributionCanonical(c, values)).sum();
		double unary = surface.contributions().stream().filter(c -> c.factor().scope().equals(List.of(target)))
			.mapToDouble(c -> surface.evaluateContributionCanonical(c, values)).sum();
		double bytes = FederatedCostModel.getEffectiveOutputMemEstimate(u);
		return new Result(get, unary, FederatedCostModel.computeReusableMaterializationDownloadCost(bytes, FType.ROW, 2),
			FederatedCostModel.computeInBandUploadPayloadCost(bytes, FType.ROW, 2));
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

}
