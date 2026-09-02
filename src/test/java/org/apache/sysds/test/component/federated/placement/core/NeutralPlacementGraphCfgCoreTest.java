/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.test.component.federated.placement.core;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Focused RED/GREEN contract for CFG-aware neutral graph construction. */
public class NeutralPlacementGraphCfgCoreTest {
	private static final PlacementState CP = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FF = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);

	@Test
	public void conjunctiveConstraintFiltersIncompatibleFederatedAssignment() {
		CompiledHopKey left = key("left", "left-context");
		CompiledHopKey join = key("join", "join-context");
		NeutralPlacementGraph graph = new NeutralPlacementGraph(List.of(node(left, CP, FF), node(join, FF)),
			List.of(new Constraint(ConstraintKind.CONJUNCTIVE, left, join)), List.of());
		Assert.assertEquals("FED join must require a compatible FED predecessor", 1,
			graph.normalizedLegalAssignments().size());
	}

	@Test
	public void loopAndBranchValuesCarryExactCfgPredecessors() throws Exception {
		NeutralPlacementGraph graph = build("X=matrix(1,2,2);i=1;while(i<=2){"
			+ "if(sum(X)>0){X=X+1;}else{X=X-1;}i=i+1;}print(sum(X));");
		Assert.assertTrue("loop head identity missing", graph.nodes().stream()
			.anyMatch(n -> n.valueVersion().versionKind() == VersionKind.LOOP_HEAD_PHI
				&& n.valueVersion().predecessorVersions().size() >= 2));
		Assert.assertTrue("exact CFG transient placement equality missing", graph.constraints().stream()
			.anyMatch(c -> c.kind() == ConstraintKind.SAME_PLACEMENT
				&& c.evidence().startsWith("cfg-transient-value:")));
	}

	@Test
	public void functionInputsAndOutputsAreSeparatedPerCallSite() throws Exception {
		NeutralPlacementGraph graph = build("f=function(matrix[double] A)return(matrix[double] B){"
			+ "B=A;i=1;while(i<2){B=B+1;i=i+1;}}"
			+ "X=matrix(1,2,2);Y1=f(X);Y2=f(X+1);print(sum(Y1)+sum(Y2));", false);
		long inputs = graph.nodes().stream().filter(n -> n.kind() == NodeKind.FUNCTION_INPUT).count();
		long outputs = graph.nodes().stream().filter(n -> n.kind() == NodeKind.FUNCTION_OUTPUT).count();
		Assert.assertTrue("two call sites require distinct function inputs: " + graph.normalizedValueVersions(), inputs >= 2);
		Assert.assertTrue("two call sites require distinct function outputs: " + graph.normalizedValueVersions(), outputs >= 2);
		Assert.assertTrue("call sites require distinct-context relation", graph.constraints().stream()
			.anyMatch(c -> c.kind() == ConstraintKind.DISTINCT_CONTEXT));
		List<Constraint> outputAliases = graph.constraints().stream()
			.filter(c -> c.evidence().startsWith("function-result:")
				|| c.evidence().startsWith("inlined-function-result:"))
			.toList();
		Assert.assertFalse("function outputs require an explicit runtime alias relation", outputAliases.isEmpty());
		Assert.assertTrue("FunctionCallCP binds the returned Data object without materializing it",
			outputAliases.stream().allMatch(c -> c.kind() == ConstraintKind.SAME_VALUE_PLACEMENT));
	}

	@Test
	public void dmlFunctionOutputsAliasTheirOwnFormalExitValuesByPosition() throws Exception {
		NeutralPlacementGraph graph = build("f=function(matrix[double] A)"
			+ "return(matrix[double] B,matrix[double] C){B=A+1;C=t(A);i=1;while(i<2){i=i+1;}}"
			+ "X=matrix(1,2,2);[Y,Z]=f(X);print(sum(Y)+sum(Z));", false);
		Node y = graph.nodes().stream().filter(node -> node.kind() == NodeKind.FUNCTION_OUTPUT
			&& "Y".equals(node.valueVersion().lexicalVariable())).findFirst().orElseThrow();
		Node z = graph.nodes().stream().filter(node -> node.kind() == NodeKind.FUNCTION_OUTPUT
			&& "Z".equals(node.valueVersion().lexicalVariable())).findFirst().orElseThrow();
		List<Constraint> yAliases = graph.constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				&& constraint.right() == y.key()).toList();
		List<Constraint> zAliases = graph.constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				&& constraint.right() == z.key()).toList();
		Assert.assertEquals("first caller output must have one exact formal authority", 1, yAliases.size());
		Assert.assertEquals("second caller output must have one exact formal authority", 1, zAliases.size());
		Assert.assertEquals("B", graph.node(yAliases.get(0).left()).orElseThrow()
			.valueVersion().lexicalVariable());
		Assert.assertEquals("C", graph.node(zAliases.get(0).left()).orElseThrow()
			.valueVersion().lexicalVariable());
		Assert.assertNotEquals(NodeKind.FUNCTION_CALL,
			graph.node(yAliases.get(0).left()).orElseThrow().kind());
		Assert.assertNotEquals(NodeKind.FUNCTION_CALL,
			graph.node(zAliases.get(0).left()).orElseThrow().kind());
	}

	@Test
	public void dmlFunctionOutputAliasesEveryPossibleCfgExitDefinition() throws Exception {
		NeutralPlacementGraph graph = build("f=function(matrix[double] A)return(matrix[double] B){"
			+ "if(sum(A)>0){B=A+1;}else{B=A-1;}}"
			+ "X=matrix(1,2,2);Y=f(X);print(sum(Y));", false);
		Node output = graph.nodes().stream().filter(node -> node.kind() == NodeKind.FUNCTION_OUTPUT
			&& "Y".equals(node.valueVersion().lexicalVariable())).findFirst().orElseThrow();
		List<Constraint> aliases = graph.constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				&& constraint.right() == output.key()).toList();
		Assert.assertEquals("both branch definitions can reach FunctionCallCP's returned Data binding",
			2, aliases.size());
		Assert.assertTrue(aliases.stream().allMatch(constraint -> "B".equals(graph.node(constraint.left())
			.orElseThrow().valueVersion().lexicalVariable())));
	}

	@Test
	public void callerTReadReplaysExactFunctionReturnPlacementDomain() throws Exception {
		NeutralPlacementGraph graph = build("f=function(matrix[double] A)return(matrix[double] B){"
			+ "B=A;i=1;while(i<2){i=i+1;}}"
			+ "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
			+ "Y=f(X);Z=Y+1;print(sum(Z));", false);
		Node output = graph.nodes().stream().filter(node -> node.kind() == NodeKind.FUNCTION_OUTPUT
			&& "Y".equals(node.valueVersion().lexicalVariable())).findFirst().orElseThrow();
		Node read = graph.nodes().stream().filter(node -> "Y".equals(node.valueVersion().lexicalVariable())
			&& (node.kind() == NodeKind.TRANSIENT_READ || node.kind() == NodeKind.BRANCH_JOIN
				|| node.kind() == NodeKind.LOOP_PHI)
			&& node.valueVersion().predecessorVersions().stream()
				.anyMatch(value -> value.startsWith("cfg-function-output:")))
			.findFirst().orElseThrow();
		Assert.assertTrue("function return must retain its legal federated value placement",
			valuePlacements(output).stream().anyMatch(value -> value.startsWith("FOUT/")));
		Assert.assertEquals("FunctionCallCP aliases one Data object; caller TRead must expose the same value domain",
			valuePlacements(output), valuePlacements(read));
		Assert.assertEquals("caller TRead must retain the exact returned-value anchor intersection",
			output.anchors(), read.anchors());
		Assert.assertTrue("CFG return alias must be explicit in the neutral graph", graph.constraints().stream()
			.anyMatch(constraint -> constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& constraint.left() == output.key() && constraint.right() == read.key()
				&& constraint.evidence().startsWith("cfg-function-output-value:")));
	}

	@Test
	public void functionShapeClosureRetainsOnlyShapeIndependentFederatedCandidates() throws Exception {
		NeutralPlacementGraph graph = build("f=function(matrix[double] X)return(matrix[double] Y){Y=rowSums(X);}"
			+ "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));Z=sum(A);Y=f(A);print(Z+sum(Y));", false);
		Assert.assertTrue(graph.normalizedCandidateUniverse().toString(), graph.normalizedCandidateUniverse().stream()
			.anyMatch(s -> s.contains("FED/") && s.contains("SHAPE_INDEPENDENT")));
		Assert.assertFalse("Interprocedural shape closure must not expose an unproved shape-dependent"
			+ " federated state: " + graph.normalizedCandidateUniverse(), graph.normalizedCandidateUniverse().stream()
			.anyMatch(s -> s.contains("FED/") && s.contains("SHAPE_DEPENDENT")));
	}

	@Test
	public void duplicateSameConsumerInputsRemainDistinctObligations() throws Exception {
		NeutralPlacementGraph graph = build("X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));Y=cbind(X,X);print(sum(Y));");
		Assert.assertTrue("one shared relocation expected", graph.relocationActions().size() >= 1);
		Assert.assertTrue("same consumer positions must remain distinct: " + graph.normalizedSignature(),
			graph.relocationActions().stream()
			.anyMatch(a -> a.obligations().size() == 2
				&& a.obligations().get(0).inputPosition() != a.obligations().get(1).inputPosition()));
	}

	@Test
	public void coreAcceptsOneRelocationWithTwoSameConsumerObligations() {
		CompiledHopKey source = key("source", "source-context");
		CompiledHopKey consumer = key("consumer", "consumer-context");
		ValueVersionKey sourceValue = value(source, VersionKind.ORDINARY, List.of());
		DurableAnchorKey anchor = new DurableAnchorKey("fed:X", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(2L, 2L))));
		RelocationActionKey relocation = new RelocationActionKey(sourceValue, FF, anchor, "scope", List.of(consumer));
		ObligationKey left = new ObligationKey(consumer, 0, sourceValue, FF, relocation, "scope");
		ObligationKey right = new ObligationKey(consumer, 1, sourceValue, FF, relocation, "scope");
		NeutralPlacementGraph graph = new NeutralPlacementGraph(
			List.of(new Node(source, NodeKind.OPERATION, sourceValue, true, List.of(CP), List.of(), List.of(anchor)),
				node(consumer, CP, FF)), List.of(),
			List.of(new NeutralPlacementGraph.RelocationAction(relocation, List.of(left, right))));
		Assert.assertEquals(1, graph.relocationActions().size());
		Assert.assertEquals(2, graph.normalizedObligations().size());
	}

	private static NeutralPlacementGraph build(String script) throws Exception {
		return build(script, true);
	}

	private static NeutralPlacementGraph build(String script, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite) translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return new NeutralPlacementGraphBuilder().build(program);
	}

	private static Node node(CompiledHopKey key, PlacementState... states) {
		return new Node(key, NodeKind.OPERATION, value(key, VersionKind.ORDINARY, List.of()), true,
			List.of(states), List.of(), List.of());
	}

	private static Set<String> valuePlacements(Node node) {
		return node.legalAlternatives().stream().map(state -> state.output() + "/" + state.fType())
			.collect(java.util.stream.Collectors.toSet());
	}

	private static ValueVersionKey value(CompiledHopKey key, VersionKind kind, List<String> predecessors) {
		return new ValueVersionKey(key.programFingerprint(), key.canonicalSourceOrigin(), key.controlRegion(), 0,
			kind, predecessors);
	}

	private static CompiledHopKey key(String origin, String context) {
		ControlRegionKey region = new ControlRegionKey("program", "main", List.of(context), context, "compiled");
		return new CompiledHopKey("program", "main", context, "compiled", region, context, origin);
	}
}
