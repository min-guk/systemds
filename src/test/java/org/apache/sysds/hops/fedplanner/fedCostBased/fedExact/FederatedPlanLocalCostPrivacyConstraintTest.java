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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
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

public class FederatedPlanLocalCostPrivacyConstraintTest {
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void localCostDpSelectsOnlyTheSharedPrivacyFilteredDomain() throws Exception {
		DMLProgram program = isolatedFederatedChain();
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE);
		PlacementAnalysis analysis =
			CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);

		ExactPlacementInput receipt = new FederatedPlanLocalCost()
			.rewriteProgram(program, null, null, analysis);

		assertSelectionInsidePrivacyFilteredDomain(analysis, receipt);
	}

	@Test
	public void localCostDpPreservesPrivacyAcrossBranchLoopAndFunctionBoundaries() throws Exception {
		String script = "f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE
			+ "i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
			+ "C=f(D);print(sum(C));\n";
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis =
			CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);

		ExactPlacementInput receipt = new FederatedPlanLocalCost()
			.rewriteProgram(program, null, null, analysis);

		assertSelectionInsidePrivacyFilteredDomain(analysis, receipt);
	}

	private static void assertSelectionInsidePrivacyFilteredDomain(PlacementAnalysis analysis,
			ExactPlacementInput receipt) {
		Assert.assertSame(analysis, receipt.analysis());
		Assert.assertEquals(analysis.graph().decisionNodes().size(),
			receipt.exactSelectedStates().size());
		analysis.graph().decisionNodes().forEach(node -> {
			PlacementState selected = receipt.exactSelectedStates().get(node.key());
			Assert.assertNotNull("DP omitted a privacy-filtered decision", selected);
			Assert.assertTrue("DP selected a state outside the shared privacy-filtered domain: "
				+ node.normalizedIdentity() + " selected=" + selected,
				node.legalAlternatives().stream().anyMatch(legal -> legal == selected));
			Assert.assertTrue("DP selected a state explicitly excluded by privacy: "
				+ node.normalizedIdentity() + " selected=" + selected,
				node.exclusions().stream().noneMatch(exclusion -> exclusion.reasonCode() == ReasonCode.PRIVACY
					&& exclusion.state().equals(selected)));
			if(analysis.requirePrivacy(node.key()) == Privacy.PRIVATE) {
				Assert.assertEquals(ExecType.FED, selected.execType());
				Assert.assertEquals(FederatedOutput.FOUT, selected.output());
			}
		});
	}

	private static DMLProgram isolatedFederatedChain() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(sum(A));\n");
		DataOp source = federatedSource(program);
		BinaryOp plus = HopRewriteUtils.createBinary(source, new LiteralOp(1), OpOp2.PLUS);
		plus.setDim1(4);
		plus.setDim2(2);
		BinaryOp times = HopRewriteUtils.createBinary(plus, new LiteralOp(2), OpOp2.MULT);
		times.setDim1(4);
		times.setDim2(2);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(times)));
		program.getStatementBlocks().clear();
		program.getStatementBlocks().add(block);
		return program;
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static DataOp federatedSource(DMLProgram program) {
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(StatementBlock block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
				return data;
			pending.addAll(hop.getInput());
		}
		throw new IllegalStateException("Fixture has no federated source");
	}
}
