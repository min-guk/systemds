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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Golden contract for the final legal candidate space before early privacy pruning.
 *
 * <p>The digest intentionally includes every AVAILABLE rule, emission, realization,
 * OR-support clause/input binding, legal node state, and physical action. It excludes
 * compiler-assigned Hop IDs by using only canonical placement identities.</p>
 */
public class EarlyPrivacyPruningLegalSpaceParityTest {
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	// Filled from the clean pre-pruning baseline. A mismatch prints the observed digest.
	private static final String PROTECTED_AGGREGATE_GOLDEN =
		"09e2e4069fefd2f280f137b7c042182cc6a7e2e994169aca9ccc9c4c77e23ea2";
	private static final String METADATA_AND_HANDLE_GOLDEN =
		"f8c65fdda8a60cc67ee4a2143f50ab39d3af36fbd424ccbc1be9f254da0e0c3b";
	private static final String CONTROL_FLOW_GOLDEN =
		"4744ada268c297e4daad886f6adae367a0070d6fbcff6bcdc2cda97ff5363d28";
	private static final String UNKNOWN_WIDTH_GOLDEN =
		"2bded4649153d1e1f4542c78d3e5862c19e3fa6fb7c52a00f96fad499050d3d0";

	@Test
	public void protectedPayloadAndPublicAggregateKeepTheFullLegalSpace() throws Exception {
		String script = FEDERATED_SOURCE + "B=A+1;s=sum(B);print(s);\n";
		PlacementAnalysis analysis = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false);
		PlacementAnalysis repeated = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false);

		var plus = uniqueOccurrence(analysis, BinaryOp.class, "B");
		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(plus.key()));
		assertAvailableEmissions(analysis, plus.key(), state -> state.execType() == ExecType.FED
			&& state.output() == FederatedOutput.FOUT,
			"protected payload computation must stay remote");

		var aggregate = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggUnaryOp op && op.getOp() == AggOp.SUM)
			.findFirst().orElseThrow();
		Assert.assertEquals("a permitted aggregate result is public", Privacy.PUBLIC,
			analysis.requirePrivacy(aggregate.key()));
		assertHasAvailableState(analysis, aggregate.key(), ExecType.FED, FederatedOutput.LOUT, FType.ROW);
		assertAvailableEmissions(analysis, aggregate.key(), state -> state.execType() != ExecType.CP,
			"a public result cannot authorize coordinator collection of its protected input");
		assertDerivedEmissionsKeepTheirExactSameRowSource(analysis);
		assertGoldenAndRepeatable(PROTECTED_AGGREGATE_GOLDEN, analysis, repeated);
	}

	@Test
	public void metadataAndFunctionHandleExceptionsDoNotReleaseFormalPayload() throws Exception {
		String script = "f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE + "r=nrow(A);C=f(A);print(r);print(sum(C));\n";
		PlacementAnalysis analysis = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false);
		PlacementAnalysis repeated = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false);

		var dimensions = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof org.apache.sysds.hops.UnaryOp unary
				&& unary.getOp() == org.apache.sysds.common.Types.OpOp1.NROW)
			.findFirst().orElseThrow();
		Assert.assertEquals(Privacy.PUBLIC, analysis.requirePrivacy(dimensions.key()));
		Assert.assertTrue("nrow reads federation metadata rather than matrix payload",
			analysis.isCoordinatorMetadataOnlyInput(
				analysis.compiledInputEdge(dimensions.key(), 0).orElseThrow()));
		assertHasAvailableState(analysis, dimensions.key(), ExecType.CP, FederatedOutput.LOUT, null);

		Assert.assertTrue("the DML handle boundary must remain represented, including an inlined formal shell",
			analysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_CALL
				|| node.kind() == NodeKind.FUNCTION_INPUT && !node.emittedWork()));
		Assert.assertTrue("protected function formals and outputs must retain protected privacy",
			analysis.graph().nodes().stream().filter(node -> node.kind() == NodeKind.FUNCTION_INPUT
				|| node.kind() == NodeKind.FUNCTION_OUTPUT)
				.anyMatch(node -> analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		analysis.graph().nodes().stream()
			.filter(node -> (node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT)
				&& node.emittedWork() && analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)
			.forEach(node -> Assert.assertTrue("a handle exception must not release formal payload",
				node.legalAlternatives().stream().allMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT)));
		assertGoldenAndRepeatable(METADATA_AND_HANDLE_GOLDEN, analysis, repeated);
	}

	@Test
	public void loopBranchPhiAndFunctionRelationsKeepAllProtectedAlternatives() throws Exception {
		String script = "f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE
			+ "i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
			+ "C=f(D);print(sum(C));\n";
		PlacementAnalysis analysis = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false);
		PlacementAnalysis repeated = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false);

		Assert.assertTrue("loop data must retain protected privacy", hasProtectedRegion(analysis, "loop-body"));
		Assert.assertTrue("branch data must retain protected privacy", hasProtectedRegion(analysis, "branch-"));
		Assert.assertTrue("fixture must retain a protected function input",
			analysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_INPUT
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		Assert.assertTrue("fixture must retain a protected function output",
			analysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_OUTPUT
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE));
		analysis.graph().nodes().stream()
			.filter(node -> node.emittedWork()
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)
			.forEach(node -> Assert.assertTrue("protected CFG/phi value lost every remote alternative",
				node.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT)));
		assertGoldenAndRepeatable(CONTROL_FLOW_GOLDEN, analysis, repeated);
	}

	@Test
	public void unknownShapeKeepsSafeAggregateAndCenteringAlternatives() throws Exception {
		String script = FEDERATED_SOURCE + "colMean=colMeans(A);X=A-colMean;print(sum(X));\n";
		PlacementAnalysis analysis = analysis(script, Privacy.PRIVATE_AGGREGATE, false, true);
		PlacementAnalysis repeated = analysis(script, Privacy.PRIVATE_AGGREGATE, false, true);

		var mean = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggUnaryOp op && op.getOp() == AggOp.MEAN
				&& op.getDirection() == org.apache.sysds.common.Types.Direction.Col
				&& "colMean".equals(op.getName()))
			.findFirst().orElseThrow();
		var centered = uniqueOccurrence(analysis, BinaryOp.class, "X");
		Assert.assertTrue("fixture must retain unknown source width", mean.hop().getInput(0).getDim2() < 0);
		assertHasAvailableState(analysis, mean.key(), ExecType.FED, FederatedOutput.LOUT, FType.ROW);
		assertHasAvailableState(analysis, centered.key(), ExecType.FED, FederatedOutput.FOUT, FType.ROW);
		assertGoldenAndRepeatable(UNKNOWN_WIDTH_GOLDEN, analysis, repeated);
	}

	private static PlacementAnalysis analysis(String script, Privacy privacy, boolean rewrite,
		boolean unknownSourceWidth) throws Exception {
		DMLProgram program = compile(script, rewrite);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		if(unknownSourceWidth)
			federatedSource(program).setDim2(-1);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
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

	private static void assertGoldenAndRepeatable(String expected, PlacementAnalysis analysis,
		PlacementAnalysis repeated) throws Exception {
		String observed = digest(semanticSnapshot(analysis));
		String repeatedDigest = digest(semanticSnapshot(repeated));
		Assert.assertEquals("canonical snapshot must exclude process-global Hop IDs", observed, repeatedDigest);
		Assert.assertEquals("legal-space baseline changed; observed=" + observed, expected, observed);
	}

	static String semanticSnapshot(PlacementAnalysis analysis) {
		StringBuilder out = new StringBuilder();
		for(var node : analysis.graph().nodes()) {
			out.append("NODE|").append(node.key().normalizedSignature())
				.append("|privacy=").append(analysis.requirePrivacy(node.key()))
				.append("|states=").append(node.legalAlternatives().stream()
					.map(PlacementState::normalizedSignature).toList()).append('\n');
		}
		for(var fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			out.append("AVAILABLE|").append(fact.key().normalizedSignature())
				.append("|emissions=").append(fact.allowedEmissionFacts().stream()
					.map(CandidateEmissionFact::normalizedSignature).toList()).append('\n');
		}
		analysis.graph().normalizedRelocationActions().forEach(value ->
			out.append("RELOCATION|").append(value).append('\n'));
		analysis.graph().normalizedDerivedFoutMaterializationActions().forEach(value ->
			out.append("DERIVED_FOUT|").append(value).append('\n'));
		return out.toString();
	}

	private static String digest(String value) throws Exception {
		byte[] bytes = MessageDigest.getInstance("SHA-256")
			.digest(value.getBytes(StandardCharsets.UTF_8));
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for(byte b : bytes)
			result.append(String.format("%02x", b & 0xff));
		return result.toString();
	}

	private static PlacementAnalysis.HopOccurrenceProjection uniqueOccurrence(PlacementAnalysis analysis,
		Class<? extends Hop> type, String name) {
		List<PlacementAnalysis.HopOccurrenceProjection> matches = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> type.isInstance(occurrence.hop()) && name.equals(occurrence.hop().getName()))
			.toList();
		Assert.assertEquals("fixture requires one occurrence named " + name, 1, matches.size());
		return matches.get(0);
	}

	private static void assertAvailableEmissions(PlacementAnalysis analysis,
		PlacementIdentity.CompiledHopKey key, java.util.function.Predicate<PlacementState> predicate,
		String message) {
		List<CandidateEmissionFact> emissions = analysis.candidateRuleFacts().orderedFactsForParent(key).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream()).toList();
		Assert.assertFalse("fixture requires AVAILABLE emissions", emissions.isEmpty());
		Assert.assertTrue(message + "; emissions=" + emissions,
			emissions.stream().map(emission -> emission.emissionState().placementState()).allMatch(predicate));
	}

	private static void assertHasAvailableState(PlacementAnalysis analysis,
		PlacementIdentity.CompiledHopKey key, ExecType execType, FederatedOutput output, FType fType) {
		Assert.assertTrue("missing AVAILABLE state " + execType + '/' + output + '/' + fType,
			analysis.candidateRuleFacts().orderedFactsForParent(key).stream()
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.map(emission -> emission.emissionState().placementState())
				.anyMatch(state -> state.execType() == execType && state.output() == output
					&& state.fType() == fType));
	}

	private static void assertDerivedEmissionsKeepTheirExactSameRowSource(PlacementAnalysis analysis) {
		for(var fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				if(emission.derivedFoutAction() == null)
					continue;
				PlacementState exactSource = emission.derivedFoutAction().sourcePlacement();
				Assert.assertTrue("derived FOUT lost its exact source in the same candidate row",
					fact.allowedEmissionFacts().stream().anyMatch(source -> source.derivedFoutAction() == null
						&& source.emissionState().placementState().equals(exactSource)));
			}
		}
	}

	private static boolean hasProtectedRegion(PlacementAnalysis analysis, String marker) {
		return analysis.graph().nodes().stream().anyMatch(node ->
			node.key().controlRegion().regionPath().stream().anyMatch(path -> path.contains(marker))
				&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE);
	}

	private static DataOp federatedSource(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<DataOp> sources = new ArrayList<>();
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
				sources.add(data);
			pending.addAll(hop.getInput());
		}
		return sources.stream().findFirst().orElseThrow();
	}
}
