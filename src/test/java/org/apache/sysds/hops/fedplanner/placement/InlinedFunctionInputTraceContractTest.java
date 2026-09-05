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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock.InlinedFunctionCallBoundary;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression contract for compiler-owned, instruction-free inlined input markers. */
public class InlinedFunctionInputTraceContractTest {
	private static final String FEDERATED_SOURCE =
		"F=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void matrixScalarAndLiteralActualsAreTraceOnly() throws Exception {
		DMLProgram program = compile("""
			fm=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}
			fs=function(double x) return (double y){y=x+1;}
			A=matrix(1,2,2);
			B=fm(A);
			C=fm(matrix(2,2,2));
			s=3;
			t=fs(s);
			u=fs(7);
			print(sum(B)+sum(C)+t+u);
			""", false);
		List<InlinedFunctionCallBoundary> calls = inlinedCalls(program);
		Assert.assertEquals("fixture must retain all four compiler-owned call boundaries", 4, calls.size());
		Assert.assertTrue("fixture must cover an actual with a lexical carrier",
			calls.stream().flatMap(call -> call.inputs().stream()).anyMatch(input -> input.actualVariable() != null));
		Assert.assertTrue("fixture must cover an expression/literal actual without a lexical carrier",
			calls.stream().flatMap(call -> call.inputs().stream()).anyMatch(input -> input.actualVariable() == null));

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<Node> inputs = inlinedInputNodes(analysis);
		Assert.assertEquals("one trace marker per compiler-owned input", 4, inputs.size());
		inputs.forEach(InlinedFunctionInputTraceContractTest::assertTraceOnly);

		Set<CompiledHopKey> constrainedInputs = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.CONJUNCTIVE)
			.filter(constraint -> constraint.evidence().startsWith("inlined-function-argument:"))
			.map(NeutralPlacementGraph.Constraint::right).collect(Collectors.toSet());
		Assert.assertFalse("an exact lexical argument may retain its optional trace constraint",
			constrainedInputs.isEmpty());
		// A generated RHS may retain a compiler name even for an expression actual.
		// The builtin-GLM regression separately covers names removed by HOP rewrites.
	}

	@Test
	public void realPrivateSourcesConsumersAndCompiledInputEdgesRetainAuthority() throws Exception {
		for(Privacy privacy : List.of(Privacy.PRIVATE, Privacy.PRIVATE_AGGREGATE)) {
			DMLProgram program = compile("f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
				+ FEDERATED_SOURCE + "R=f(F);print(sum(R));\n", false);
			// Keep the real inlined matrix RHS live without requiring a forbidden private
			// collection/print. This fixture exercises the boundary, not an output sink.
			var result = PlacementGraphFingerprint.orderedOccurrences(program).stream()
				.filter(occurrence -> !occurrence.block().getInlinedFunctionCallBoundaries().isEmpty())
				.filter(occurrence -> occurrence.hop() instanceof BinaryOp).findFirst().orElseThrow();
			result.block().setHops(new ArrayList<>(List.of(result.hop())));
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			List<Node> inputs = inlinedInputNodes(analysis);
			Assert.assertEquals(privacy + " fixture input-marker count", 1, inputs.size());
			inputs.forEach(InlinedFunctionInputTraceContractTest::assertTraceOnly);

			PlacementAnalysis.HopOccurrenceProjection source = analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == OpOpData.FEDERATED).findFirst().orElseThrow();
			PlacementAnalysis.HopOccurrenceProjection consumer = analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof BinaryOp)
				.filter(occurrence -> analysis.requirePrivacy(occurrence.key()) == privacy)
				.findFirst().orElseThrow();
			Assert.assertEquals(privacy, analysis.requirePrivacy(source.key()));
			Assert.assertFalse("real federated source retains a physical placement domain",
				analysis.graph().node(source.key()).orElseThrow().legalAlternatives().isEmpty());
			Assert.assertFalse("real federated source retains executable anchor authority",
				analysis.graph().node(source.key()).orElseThrow().anchors().isEmpty());
			Assert.assertFalse("real inlined-body consumer retains a physical placement domain",
				analysis.graph().node(consumer.key()).orElseThrow().legalAlternatives().isEmpty());
			if(privacy == Privacy.PRIVATE)
				Assert.assertTrue("strict-private matrix body cannot be made local by an inlined marker",
					analysis.graph().node(consumer.key()).orElseThrow().legalAlternatives().stream()
						.allMatch(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
			// Check the existing shared policy, rather than assigning a new interpretation
			// of PRIVATE_AGGREGATE as part of this representation-only change.
			DMLProgram direct = compile(FEDERATED_SOURCE + "R=F+1;print(sum(R));\n", false);
			var directResult = PlacementGraphFingerprint.orderedOccurrences(direct).stream()
				.filter(occurrence -> occurrence.hop() instanceof BinaryOp).findFirst().orElseThrow();
			directResult.block().setHops(new ArrayList<>(List.of(directResult.hop())));
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(direct, privacy);
			PlacementAnalysis directAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(direct);
			var directConsumer = directAnalysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof BinaryOp).findFirst().orElseThrow();
			Assert.assertEquals("inlining cannot relax or restrict the equivalent physical HOP's domain",
				directAnalysis.graph().node(directConsumer.key()).orElseThrow().legalAlternatives(),
				analysis.graph().node(consumer.key()).orElseThrow().legalAlternatives());

			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis.compiledInputEdgesInCanonicalOrder()
				.stream().filter(edge -> edge.consumer() == consumer.key()).toList();
			Assert.assertEquals("the real matrix argument retains its exact compiled data-flow edge", 1, edges.size());
			Assert.assertSame("the inlined body consumes the exact compiled matrix input",
				consumer.hop().getInput().get(0), analysis.hop(edges.get(0).producer()).orElseThrow());
			Assert.assertEquals("privacy crosses the real transient input, not a trace marker",
				privacy, analysis.requirePrivacy(edges.get(0).producer()));
			Assert.assertTrue("compiled edges resolve only to real HOP occurrences", edges.stream()
				.allMatch(edge -> analysis.hop(edge.producer()).isPresent()
					&& analysis.graph().node(edge.producer()).orElseThrow().emittedWork()));
			Assert.assertTrue("trace markers are not fabricated as compiled HOP input carriers", edges.stream()
				.noneMatch(edge -> inputs.stream().anyMatch(input -> input.key() == edge.producer()
					|| input.key() == edge.consumer())));
		}
	}

	@Test
	public void nestedRepeatedCallsKeepDistinctTraceKeysAndContexts() throws Exception {
		DMLProgram program = compile("""
			inner=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}
			outer=function(matrix[double] A) return (matrix[double] B){B=inner(A);}
			M=matrix(1,2,2);
			R1=outer(M);
			R2=outer(M+1);
			print(sum(R1)+sum(R2));
			""", false);
		Map<String,Long> metadataCounts = inlinedCalls(program).stream()
			.collect(Collectors.groupingBy(InlinedFunctionCallBoundary::functionKey, Collectors.counting()));
		Assert.assertEquals("nested fixture must contain two calls to each inlined function",
			Set.of(2L), Set.copyOf(metadataCounts.values()));

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<Node> inputs = inlinedInputNodes(analysis);
		Assert.assertEquals(4, inputs.size());
		inputs.forEach(InlinedFunctionInputTraceContractTest::assertTraceOnly);
		Assert.assertEquals("every nested call retains its own compiled trace key", inputs.size(),
			inputs.stream().map(Node::key).distinct().count());
		Set<Long> inlinedScopes = program.getStatementBlocks().stream()
			.filter(block -> !block.getInlinedFunctionCallBoundaries().isEmpty())
			.map(block -> block.getSBID()).collect(Collectors.toSet());
		for(Node input : inputs) {
			List<PlacementAnalysis.HopOccurrenceProjection> projections = analysis.occurrences().stream()
				.filter(occurrence -> occurrence.key() == input.key()).toList();
			Assert.assertEquals("one exact projection per inlined trace key", 1, projections.size());
			Assert.assertTrue(projections.get(0).key().canonicalSourceOrigin()
				.startsWith("function-boundary:"));
			Assert.assertTrue("trace projection retains the compiler-owned statement-block scope",
				inlinedScopes.contains(projections.get(0).scopeId()));
		}
		for(String functionKey : metadataCounts.keySet())
			Assert.assertTrue("repeated nested calls require an explicit distinct-context fact for " + functionKey,
				analysis.graph().constraints().stream().anyMatch(constraint ->
					constraint.kind() == ConstraintKind.DISTINCT_CONTEXT
						&& functionKey.equals(constraint.evidence())));
	}

	@Test
	public void actualNonInlinedFunctionInputRemainsEmitted() throws Exception {
		DMLProgram program = compile(nonInlinedFunctionScript(), true);
		Assert.assertTrue("loop-bearing function must survive as an actual FunctionOp",
			allHops(program).stream().anyMatch(FunctionOp.class::isInstance));

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<Node> inputs = actualFunctionInputNodes(analysis);
		Assert.assertFalse(inputs.isEmpty());
		Assert.assertTrue("runtime FunctionCall inputs remain emitted placement carriers",
			inputs.stream().allMatch(node -> node.emittedWork() && !node.legalAlternatives().isEmpty()));
		Assert.assertTrue("runtime FunctionCall inputs must not use the inlined trace reason",
			inputs.stream().flatMap(node -> node.exclusions().stream()).noneMatch(exclusion ->
				exclusion.reasonCode() == ReasonCode.NON_EMITTED_INLINED_FUNCTION_INPUT));
	}

	@Test
	public void actualNonInlinedFunctionInputRejectsMissingExactArgumentAuthority() throws Exception {
		DMLProgram program = compile(nonInlinedFunctionScript(), true);
		FunctionOp call = allHops(program).stream().filter(FunctionOp.class::isInstance)
			.map(FunctionOp.class::cast).findFirst().orElseThrow();
		Assert.assertEquals("valid compiler fixture must begin with one exact argument", 1, call.getInput().size());
		call.getInput().clear();

		IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
			() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertTrue("missing runtime boundary authority must fail closed: " + failure.getMessage(),
			failure.getMessage().toLowerCase().contains("function")
				&& failure.getMessage().toLowerCase().contains("input"));
	}

	private static void assertTraceOnly(Node node) {
		Assert.assertEquals(NodeKind.FUNCTION_INPUT, node.kind());
		Assert.assertFalse("inlined input has no FunctionCall instruction", node.emittedWork());
		Assert.assertTrue("trace marker cannot publish a selectable placement", node.legalAlternatives().isEmpty());
		Assert.assertTrue("trace marker cannot publish executable anchor authority", node.anchors().isEmpty());
		Assert.assertFalse("trace-only status must remain auditable", node.exclusions().isEmpty());
		Assert.assertTrue(node.exclusions().stream().allMatch(exclusion ->
			exclusion.reasonCode() == ReasonCode.NON_EMITTED_INLINED_FUNCTION_INPUT));
	}

	private static List<Node> inlinedInputNodes(PlacementAnalysis analysis) {
		return analysis.graph().nodes().stream().filter(node -> node.kind() == NodeKind.FUNCTION_INPUT)
			.filter(node -> !(analysis.hop(node.key()).orElseThrow() instanceof FunctionOp)).toList();
	}

	private static List<Node> actualFunctionInputNodes(PlacementAnalysis analysis) {
		return analysis.graph().nodes().stream().filter(node -> node.kind() == NodeKind.FUNCTION_INPUT)
			.filter(node -> analysis.hop(node.key()).orElseThrow() instanceof FunctionOp).toList();
	}

	private static List<InlinedFunctionCallBoundary> inlinedCalls(DMLProgram program) {
		return program.getStatementBlocks().stream()
			.flatMap(block -> block.getInlinedFunctionCallBoundaries().stream()).toList();
	}

	private static List<Hop> allHops(DMLProgram program) {
		return PlacementGraphFingerprint.orderedOccurrences(program).stream()
			.map(PlacementGraphFingerprint.HopOccurrence::hop).toList();
	}

	private static String nonInlinedFunctionScript() {
		return """
			f=function(matrix[double] A) return (matrix[double] B) {
				B=A;
				i=1;
				while(i<2) { B=B+1; i=i+1; }
			}
			X=matrix(1,2,2);
			Y=f(X);
			print(sum(Y));
			""";
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
}
