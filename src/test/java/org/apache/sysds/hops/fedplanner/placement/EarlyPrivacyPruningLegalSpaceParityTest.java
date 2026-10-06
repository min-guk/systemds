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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
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

	// Complete legal-space baselines, with audited updates documented below.
	private static final String PROTECTED_AGGREGATE_GOLDEN =
		"09e2e4069fefd2f280f137b7c042182cc6a7e2e994169aca9ccc9c4c77e23ea2";
	// d7e88516a1 preserves the full native output geometry; 0146f043e0 includes
	// layout and exact/dynamic precision in native/transient realization identities.
	private static final String METADATA_AND_HANDLE_GOLDEN =
		"811d78e00e9566e9997c39aa8527c9a96d2e50af7575ec42f291f74c705629d6";
	private static final String CONTROL_FLOW_GOLDEN =
		"b9b2d88c077b94dd1df073861fcb4dece09d14c06370585829fe974df24e69cd";
	// 3d0d683c1b changed only colMean's materialization ID and its consumer reference
	// versus adaebee9cc. Keep exact action identities and support bindings in the digest.
	private static final String UNKNOWN_WIDTH_GOLDEN =
		"ff0e870b4afd1d7ebb1708ea4b0c21fa99345dd369d91d1b8f5a65d2e68451b7";

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
		assertNativePayloadFlow(analysis, "C", 1);
		PlacementAnalysis unpruned = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false, false);
		Assert.assertEquals("early pruning must preserve the complete function/metadata legal space",
			semanticSnapshot(unpruned), semanticSnapshot(analysis));
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
		assertNativePayloadFlow(analysis, "D", 2);
		assertNativePayloadFlow(analysis, "C", 1);
		PlacementAnalysis unpruned = analysis(script, Privacy.PRIVATE_AGGREGATE, false, false, false);
		Assert.assertEquals("early pruning must preserve the complete loop/branch/function legal space",
			semanticSnapshot(unpruned), semanticSnapshot(analysis));
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
		assertMeanMaterializationAuthority(analysis, mean.key(), centered.key());
		assertDerivedEmissionsKeepTheirExactSameRowSource(analysis);
		PlacementAnalysis unpruned = analysis(script, Privacy.PRIVATE_AGGREGATE, false, true, false);
		Assert.assertEquals("early pruning must preserve the complete unknown-width legal space",
			semanticSnapshot(unpruned), semanticSnapshot(analysis));
		assertGoldenAndRepeatable(UNKNOWN_WIDTH_GOLDEN, analysis, repeated);
	}

	private static PlacementAnalysis analysis(String script, Privacy privacy, boolean rewrite,
		boolean unknownSourceWidth) throws Exception {
		return analysis(script, privacy, rewrite, unknownSourceWidth, true);
	}

	private static PlacementAnalysis analysis(String script, Privacy privacy, boolean rewrite,
		boolean unknownSourceWidth, boolean earlyPrivacyPruning) throws Exception {
		DMLProgram program = compile(script, rewrite);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		if(unknownSourceWidth)
			federatedSource(program).setDim2(-1);
		return new NeutralPlacementGraphBuilder(null, null, true, earlyPrivacyPruning).buildAnalysis(program);
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

	private static void assertNativePayloadFlow(PlacementAnalysis analysis, String variable,
		int expectedWriterCount) {
		Set<CompiledHopKey> writers = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTWRITE && variable.equals(data.getName()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.collect(java.util.stream.Collectors.toSet());
		Assert.assertEquals("fixture must retain every writer of " + variable, expectedWriterCount, writers.size());
		for(CompiledHopKey writer : writers) {
			assertNativePayloadGeometry(analysis, analysis.compiledInputEdge(writer, 0).orElseThrow().producer());
			assertNativePayloadGeometry(analysis, writer);
		}
		var readers = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && variable.equals(data.getName())).toList();
		Assert.assertFalse("fixture must read " + variable, readers.isEmpty());
		for(var reader : readers) {
			assertNativePayloadGeometry(analysis, reader.key());
			var relations = analysis.logicalTransientInputsForReader(reader.key(), 0);
			Assert.assertEquals("the reader must retain every reaching writer of " + variable, writers,
				relations.stream().map(relation -> relation.sourceWrite())
					.collect(java.util.stream.Collectors.toSet()));
			for(var relation : relations) {
				var nativeEdges = relation.compatibility().stream()
					.filter(edge -> edge.sourceRealization().realization().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE
						&& edge.readerRealization().realization().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE).toList();
				Assert.assertFalse("every reaching writer needs native compatibility with its reader", nativeEdges.isEmpty());
				for(var edge : nativeEdges) {
					Assert.assertEquals(relation.sourceWrite(), edge.sourceRealization().rule().parentOccurrence());
					Assert.assertEquals(reader.key(), edge.readerRealization().rule().parentOccurrence());
					// Resolve the references carried by the boundary, not reconstructed references.
					assertNativePayloadGeometry(analysis.requireExactCandidateRealization(edge.sourceRealization()));
					assertNativePayloadGeometry(analysis.requireExactCandidateRealization(edge.readerRealization()));
				}
			}
		}
	}

	private static void assertNativePayloadGeometry(PlacementAnalysis analysis, CompiledHopKey owner) {
		var nativeOutputs = analysis.candidateRuleFacts().orderedFactsForParent(owner).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE).toList();
		Assert.assertFalse("fixture must publish native payload at " + owner, nativeOutputs.isEmpty());
		for(var realization : nativeOutputs)
			assertNativePayloadGeometry(realization);
	}

	private static void assertNativePayloadGeometry(CandidateEmissionRealization realization) {
		Assert.assertFalse("native output needs support", realization.supportClauses().isEmpty());
		for(var clause : realization.supportClauses()) {
			Assert.assertTrue("shape-preserving fixture must retain exact native geometry",
				realization.nativeWorkerPoolLayoutExact(clause));
			var witness = realization.nativeWorkerPoolResidencyWitness(clause);
			Assert.assertNotNull("exact native output needs a worker map", witness);
			Assert.assertEquals(FType.ROW, witness.fType());
			Assert.assertEquals("native output must preserve both columns of the 4x2 payload", List.of(
				new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(2L, 2L)),
				new AnchorPartition("localhost:1235", List.of(2L, 0L), List.of(4L, 2L))), witness.partitions());
		}
	}

	private static void assertMeanMaterializationAuthority(PlacementAnalysis analysis,
		CompiledHopKey mean, CompiledHopKey centered) throws Exception {
		var derived = analysis.candidateRuleFacts().orderedFactsForParent(mean).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.filter(emission -> emission.derivedFoutAction() != null).toList();
		Assert.assertEquals("colMean must retain its one explicit upload alternative", 1, derived.size());
		var emission = derived.get(0);
		var action = emission.derivedFoutAction();
		Assert.assertSame(mean, action.producer());
		Assert.assertEquals(FType.ROW, emission.executionFType());
		Assert.assertEquals("the upload must retain its exact output realization", 1, emission.realizations().size());
		var realization = emission.realizations().get(0);
		var anchor = realization.key().durableAnchor();
		Assert.assertNotNull("the uploaded mean requires a concrete worker layout", anchor);
		Assert.assertEquals("the output identity must name its exact materialization action",
			"materialized-output:" + digest(action.normalizedSignature()), anchor.placementId());
		Assert.assertEquals(FType.BROADCAST, anchor.fType());
		Assert.assertEquals("both workers must receive the complete 1x2 mean", List.of(
			new AnchorPartition("localhost:1234/X1", List.of(0L, 0L), List.of(1L, 2L)),
			new AnchorPartition("localhost:1235/X2", List.of(0L, 0L), List.of(1L, 2L))), anchor.partitions());
		var proof = new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, mean,
			"derived-fout:" + action.normalizedSignature());
		Assert.assertTrue("every upload support clause must retain the exact action authority",
			realization.supportClauses().stream().allMatch(clause -> clause.proofDependencies().contains(proof)));

		var bindings = analysis.candidateRuleFacts().orderedFactsForParent(centered).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(candidate -> candidate.realizations().stream())
			.flatMap(candidate -> candidate.supportClauses().stream())
			.flatMap(clause -> clause.inputBindings().stream())
			.filter(binding -> binding.source().realization().emissionState().derivedFedFout()).toList();
		Assert.assertFalse("centering must retain a route consuming the uploaded mean", bindings.isEmpty());
		var expectedSource = CandidateRealizationReference.of(action.candidateRule(), realization);
		for(var binding : bindings) {
			Assert.assertEquals("the mean is the right operand of centering", 1, binding.inputPosition());
			Assert.assertEquals(CandidateInputBindingKind.RELOCATION, binding.kind());
			Assert.assertEquals("centering must reference the exact uploaded realization",
				expectedSource, binding.source());
			Assert.assertEquals(action.producerValueVersion(), binding.relocationAction().sourceValueVersion());
			Assert.assertEquals(FType.BROADCAST, binding.relocationAction().materializationFType());
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
