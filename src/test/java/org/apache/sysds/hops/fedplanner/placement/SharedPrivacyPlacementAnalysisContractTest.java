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

import java.lang.reflect.Field;
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
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class SharedPrivacyPlacementAnalysisContractTest {
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void privateDataClosesOneExactOccurrenceDomainBeforeSelection() throws Exception {
		DMLProgram program = isolatedFederatedChain();
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);

		DataOp source = analysis.occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.filter(DataOp.class::isInstance).map(DataOp.class::cast)
			.filter(data -> data.getOp() == OpOpData.FEDERATED).findFirst().orElseThrow();
		var sourceMetadata = FederatedPlannerUtils.resolveFederatedSourceMetadata(source);
		Assert.assertEquals(2, sourceMetadata.partitions().size());
		Assert.assertTrue(sourceMetadata.partitions().stream()
			.allMatch(partition -> partition.getRight().getAddress() != null));
		Assert.assertEquals(2, analysis.numWorkers());
		Assert.assertEquals(analysis.graph().nodes().size(),
			analysis.privacyFactAuthority().orderedFacts().size());
		Assert.assertTrue("fixture must propagate strict privacy beyond the literal source",
			analysis.privacyFactAuthority().orderedFacts().stream()
				.filter(fact -> fact.privacy() == Privacy.PRIVATE).count() > 1);
		for(var node : analysis.graph().nodes()) {
			Assert.assertSame(node.key(), analysis.privacyFactAuthority()
				.requireExact(node.key()).occurrence());
			Assert.assertSame(node.valueVersion(), analysis.privacyFactAuthority()
				.requireExact(node.key()).valueVersion());
			if(analysis.requirePrivacy(node.key()) != Privacy.PRIVATE)
				continue;
			Assert.assertTrue("strict-private node retained a local or collected state: "
				+ node.normalizedIdentity(), node.legalAlternatives().stream().allMatch(state ->
					state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
		}
		Assert.assertTrue("privacy pruning must remain explicit in the shared graph",
			analysis.graph().nodes().stream().flatMap(node -> node.exclusions().stream())
				.anyMatch(exclusion -> exclusion.reasonCode() == ReasonCode.PRIVACY));
		Assert.assertTrue("privacy-denied candidate rows must remain auditable",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.anyMatch(fact -> fact.status() == CandidateEvaluationStatus.PRIVACY_EXCLUDED));
		analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> analysis.requirePrivacy(fact.key().parentOccurrence()) == Privacy.PRIVATE)
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream()).forEach(emission -> {
				PlacementState state = emission.emissionState().placementState();
				Assert.assertEquals(ExecType.FED, state.execType());
				Assert.assertEquals(FederatedOutput.FOUT, state.output());
			});
	}

	@Test
	public void plannerMetadataResolutionDoesNotRegisterRuntimeCleanupSites() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(sum(A));\n", false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		DataOp source = federatedSource(program);

		FederatedData.resetFederatedSites();
		try {
			FederatedPlannerUtils.resolveFederatedSourceMetadata(source);
			Assert.assertEquals("pre-selector metadata must not create runtime CLEAR targets",
				0, registeredFederatedSiteCount());
		}
		finally {
			FederatedData.resetFederatedSites();
		}
	}

	@Test
	public void branchLoopAndFunctionBoundariesShareTheSamePrivacyClosure() throws Exception {
		String script = "f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE
			+ "i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
			+ "C=f(D);print(sum(C));\n";
		DMLProgram program = compile(script, false);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);

		Assert.assertTrue("loop-body data must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.key().controlRegion().regionPath()
				.stream().anyMatch(path -> path.contains("loop-body"))
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		Assert.assertTrue("branch data must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.key().controlRegion().regionPath()
				.stream().anyMatch(path -> path.contains("branch-"))
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		Assert.assertTrue("function input boundary must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_INPUT
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		Assert.assertTrue("function output boundary must retain aggregate privacy",
			analysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_OUTPUT
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
	}

	@Test
	public void privateCollectionFailsBeforeAnyPlannerSelectorCanRun() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "B=A+1;print(sum(B));\n", true);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE);

		DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().contains("No privacy-safe physical placement"));
	}

	private static DMLProgram compile(String script, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite)
			translator.rewriteHopsDAG(program);
		return program;
	}

	private static DMLProgram isolatedFederatedChain() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(sum(A));\n", false);
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

	private static int registeredFederatedSiteCount() throws Exception {
		Field sites = FederatedData.class.getDeclaredField("_allFedSites");
		sites.setAccessible(true);
		return ((Set<?>) sites.get(null)).size();
	}
}
