/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

/** Exact RED/GREEN contracts for CFG reaching definitions and stable semantic identities. */
public class NeutralPlacementGraphExactCfgIdentityTest {
	@Test
	public void sequentialOverwriteHasOnlyLatestReachingDefinition() throws Exception {
		NeutralPlacementGraph graph = buildLinearOverwriteFixture();
		List<Node> feedingReads = readsFeeding(graph, "Y", "X");
		Assert.assertEquals("expected exactly one X read feeding Y: " + graph.normalizedConstraints(),
			1, feedingReads.size());
		Node last = feedingReads.get(0);
		Assert.assertEquals(last.valueVersion().predecessorVersions().toString(), Set.of("X#2"),
			distinctCfgDefinitions(last));
		Assert.assertNotEquals("sequential overwrite is not a branch phi", VersionKind.BRANCH_JOIN_PHI,
			last.valueVersion().versionKind());
	}

	@Test
	public void branchReadCannotSeeFutureWrite() throws Exception {
		NeutralPlacementGraph graph = build("X=matrix(0,2,2);if(sum(X)>=0){X=matrix(1,2,2);}"
			+ "else{X=matrix(2,2,2);}Y=X+1;if(sum(Y)>0){X=matrix(3,2,2);}"
			+ "else{X=matrix(4,2,2);}print(sum(Y));");
		List<Node> feedingReads = readsFeeding(graph, "Y", "X");
		Assert.assertEquals("expected exactly one X read feeding first-join Y", 1, feedingReads.size());
		Node read = feedingReads.get(0);
		Assert.assertEquals("first branch join must contain only branch versions X#2 and X#3: "
			+ read.valueVersion().predecessorVersions(), Set.of("X#2", "X#3"), distinctCfgDefinitions(read));
	}

	@Test
	public void loopPhiContainsOnlyEntryAndLatchLineage() throws Exception {
		NeutralPlacementGraph graph = build("i=1;while(i<3){T=i+1;i=T;}print(i);");
		List<Node> predicates = readsFeeding(graph, "__pred", "i");
		Assert.assertEquals("expected exactly one predicate TRead i", 1, predicates.size());
		Node predicate = predicates.get(0);
		Assert.assertEquals("loop predicate must see entry i and latch i only: "
			+ predicate.valueVersion().predecessorVersions(), Set.of("i#1", "i#2"),
			distinctCfgDefinitions(predicate));
		Assert.assertEquals("predicate read must be the exact loop head phi",
			VersionKind.LOOP_HEAD_PHI, predicate.valueVersion().versionKind());
		List<Node> loopHeads = graph.nodes().stream()
			.filter(n -> n.valueVersion().versionKind() == VersionKind.LOOP_HEAD_PHI)
			.collect(Collectors.toList());
		Assert.assertTrue("loop head for i missing", loopHeads.stream()
			.anyMatch(n -> "i".equals(n.valueVersion().lexicalVariable())
				&& definitionPredecessors(n).size() == 2));
		Assert.assertFalse("body-local/compiler values must not become loop-carried: " + loopHeads,
			loopHeads.stream().anyMatch(n -> "T".equals(n.valueVersion().lexicalVariable())
				|| n.valueVersion().lexicalVariable().startsWith("__pred")));
	}

	@Test
	public void functionBoundariesAreExactlyIsolatedPerCallSiteAndPosition() throws Exception {
		NeutralPlacementGraph graph = build("f=function(matrix[double] A)return(matrix[double] B){"
			+ "B=A;i=1;while(i<2){B=B+1;i=i+1;}}"
			+ "X=matrix(1,2,2);Y1=f(X);Y2=f(X+1);print(sum(Y1)+sum(Y2));");
		List<Node> boundaries = graph.nodes().stream().filter(n -> n.kind() == NodeKind.FUNCTION_INPUT
			|| n.kind() == NodeKind.FUNCTION_OUTPUT)
			.collect(Collectors.toList());
		List<Node> calls = graph.nodes().stream().filter(n -> n.kind() == NodeKind.FUNCTION_CALL)
			.collect(Collectors.toList());
		Assert.assertEquals("fixture must expose exactly two callsites", 2, calls.size());
		Set<Node> owned = new HashSet<>();
		for(Node call : calls) {
			List<Node> perCall = graph.constraints().stream().filter(c -> c.left().equals(call.key()))
				.map(c -> graph.node(c.right())).filter(java.util.Optional::isPresent)
				.map(java.util.Optional::get).filter(boundaries::contains).distinct().collect(Collectors.toList());
			Assert.assertEquals("each callsite must own exactly one input and output boundary: " + perCall,
				2, perCall.size());
			Assert.assertEquals(1, perCall.stream().filter(n -> n.kind() == NodeKind.FUNCTION_INPUT).count());
			Assert.assertEquals(1, perCall.stream().filter(n -> n.kind() == NodeKind.FUNCTION_OUTPUT).count());
			for(Node boundary : perCall)
				Assert.assertEquals("boundary must reference exactly its own callsite: "
					+ boundary.valueVersion().predecessorVersions(), 1,
					boundary.valueVersion().predecessorVersions().stream()
						.filter(s -> s.startsWith("callsite:")).count());
			owned.addAll(perCall);
		}
		Assert.assertEquals("exported function boundaries must all be owned by one actual callsite",
			new HashSet<>(boundaries), owned);
	}

	@Test
	public void repeatedCompilationHasStableCounterFreeSemanticIdentity() throws Exception {
		String script = "X=matrix(1,2,2);i=1;while(i<3){X=X+1;i=i+1;}print(sum(X));";
		NeutralPlacementGraph first = build(script);
		NeutralPlacementGraph second = build(script);
		Assert.assertEquals(first.normalizedSignature(), second.normalizedSignature());
		Assert.assertFalse(first.nodes().stream().map(n -> n.key().canonicalSourceOrigin())
			.anyMatch(s -> s.matches(".*(parsertemp|__(pred|tmp))[0-9]+.*")));
	}

	@Test
	public void recompileCloneHasExplicitStableOriginRelation() throws Exception {
		NeutralPlacementGraph graph = buildCloneFixture();
		List<Node> clones = graph.nodes().stream().filter(n -> n.kind() == NodeKind.CLONE)
			.collect(Collectors.toList());
		Assert.assertEquals("fixture must produce exactly one recompile clone", 1, clones.size());
		for(Node clone : clones)
		{
			List<Node> origins = graph.nodes().stream().filter(n -> n.kind() != NodeKind.CLONE
				&& n.key().canonicalSourceOrigin().equals(clone.key().canonicalSourceOrigin()))
				.collect(Collectors.toList());
			Assert.assertEquals("clone fixture must expose exactly one non-clone structural origin", 1, origins.size());
			Node origin = origins.get(0);
			Assert.assertTrue("clone lacks typed SAME_ORIGIN relation: " + clone.key().normalizedSignature(),
				graph.constraints().stream().anyMatch(c -> "SAME_ORIGIN".equals(c.kind().name())
					&& ((c.left().equals(clone.key()) && c.right().equals(origin.key()))
						|| (c.right().equals(clone.key()) && c.left().equals(origin.key())))));
		}
	}

	private static List<Node> reads(NeutralPlacementGraph graph, String variable) {
		return graph.nodes().stream().filter(n -> n.kind() == NodeKind.TRANSIENT_READ
			|| n.kind() == NodeKind.BRANCH_JOIN || n.kind() == NodeKind.LOOP_PHI)
			.filter(n -> variable.equals(n.valueVersion().lexicalVariable())).collect(Collectors.toList());
	}

	private static List<String> definitionPredecessors(Node node) {
		return node.valueVersion().predecessorVersions().stream()
			.filter(s -> s.startsWith("definition:") || s.startsWith("cfg-definition:"))
			.collect(Collectors.toList());
	}

	private static Set<String> distinctCfgDefinitions(Node node) {
		return node.valueVersion().predecessorVersions().stream().filter(s -> s.startsWith("cfg-definition:"))
			.map(s -> s.substring("cfg-definition:".length()))
			.map(s -> s.substring(0, s.indexOf('@'))).collect(Collectors.toCollection(java.util.TreeSet::new));
	}

	private static List<Node> readsFeeding(NeutralPlacementGraph graph, String targetVariable,
		String sourceVariable) {
		List<Node> targets = graph.nodes().stream().filter(n -> targetVariable.equals(n.valueVersion().lexicalVariable())
			&& n.kind() == NodeKind.TRANSIENT_WRITE).collect(Collectors.toList());
		Assert.assertEquals("expected exactly one TWrite " + targetVariable, 1, targets.size());
		Node target = targets.get(0);
		Map<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey,Node> nodes = graph.nodes()
			.stream().collect(Collectors.toMap(Node::key, n -> n));
		List<Node> pending = new java.util.ArrayList<>(List.of(target));
		Set<Node> seen = new HashSet<>();
		List<Node> matches = new ArrayList<>();
		while(!pending.isEmpty()) {
			Node current = pending.remove(pending.size() - 1);
			if(!seen.add(current)) continue;
			if(sourceVariable.equals(current.valueVersion().lexicalVariable())
				&& (current.kind() == NodeKind.TRANSIENT_READ || current.kind() == NodeKind.BRANCH_JOIN
					|| current.kind() == NodeKind.LOOP_PHI)) matches.add(current);
			graph.constraints().stream().filter(c -> c.right().equals(current.key()) && "data-input".equals(c.evidence()))
				.map(c -> nodes.get(c.left())).filter(java.util.Objects::nonNull).forEach(pending::add);
		}
		return matches;
	}

	private static NeutralPlacementGraph build(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return new NeutralPlacementGraphBuilder().build(program);
	}

	/**
	 * A bounded compiled-Hop fixture for the exact linear script
	 * {@code X=matrix(1,2,2);X=matrix(2,2,2);Y=X;print(sum(Y));}. The normal
	 * translator forwards the second matrix producer directly into Y and removes
	 * the transient read whose reaching definition this contract must inspect.
	 */
	private static NeutralPlacementGraph buildLinearOverwriteFixture() {
		DMLProgram program = new DMLProgram();
		DataOp firstX = transientWrite("X", new LiteralOp(1L));
		DataOp secondX = transientWrite("X", new LiteralOp(2L));
		DataOp readX = new DataOp("X", DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, "X", 0, 0, -1, -1);
		DataOp writeY = transientWrite("Y", readX);
		program.setStatementBlocks(new ArrayList<>(List.of(block(firstX), block(secondX), block(writeY))));
		return new NeutralPlacementGraphBuilder().build(program);
	}

	private static DataOp transientWrite(String variable, Hop input) {
		return new DataOp(variable, input.getDataType(), input.getValueType(), input,
			OpOpData.TRANSIENTWRITE, variable);
	}

	private static StatementBlock block(Hop root) {
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(root)));
		return block;
	}

	private static NeutralPlacementGraph buildCloneFixture() {
		DMLProgram program = new DMLProgram();
		DataOp origin = transientWrite("X", new LiteralOp(1L));
		DataOp recompiled = transientWrite("X", new LiteralOp(1L));
		recompiled.setRequiresRecompile();
		program.setStatementBlocks(new ArrayList<>(List.of(block(origin), block(recompiled))));
		return new NeutralPlacementGraphBuilder().build(program);
	}

}
