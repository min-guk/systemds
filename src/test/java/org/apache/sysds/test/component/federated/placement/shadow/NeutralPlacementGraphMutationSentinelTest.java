/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License");
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
package org.apache.sysds.test.component.federated.placement.shadow;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Mutation, repeat-build, and insertion-order sentinels for shadow construction. */
@RunWith(Parameterized.class)
public class NeutralPlacementGraphMutationSentinelTest {
	private static final long SENTINEL_SCOPE = 7001L;
	private final String _fixtureId;

	public NeutralPlacementGraphMutationSentinelTest(String fixtureId) {
		_fixtureId = fixtureId;
	}

	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> fixtures() {
		return ProductionShadowFixtureFactory.ids().stream().map(id -> new Object[] {id})
			.collect(Collectors.toList());
	}

	@After
	public void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	@Test
	public void buildIsMutationFreeIdempotentAndOrderInvariant() throws Exception {
		synchronized(NeutralPlacementGraphMutationSentinelTest.class) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(_fixtureId);
			seedRegistries();
			String hopBefore = hopFingerprint(program);
			String registryBefore = registryFingerprint();

			NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
			NeutralPlacementGraph first = builder.build(program);
			Assert.assertEquals(_fixtureId + " mutated Hop state/topology", hopBefore, hopFingerprint(program));
			Assert.assertEquals(_fixtureId + " mutated a placement registry", registryBefore, registryFingerprint());

			NeutralPlacementGraph second = builder.build(program);
			String firstSignature = first.normalizedSignature();
			Assert.assertEquals(_fixtureId + " repeat build is not idempotent",
				firstSignature, second.normalizedSignature());
			Assert.assertEquals(_fixtureId + " root/input insertion order changed the normalized graph",
				firstSignature, reversed(first).normalizedSignature());
		}
	}

	@Test
	public void exhaustiveAssignmentsRemainAvailableOnlyForBoundedFixtures() {
		if(!"B-01".equals(_fixtureId))
			return;
		NeutralPlacementGraph bounded = boundedTwoNodeGraph();
		Assert.assertEquals(bounded.normalizedSignatureWithLegalAssignments(),
			reversed(bounded).normalizedSignatureWithLegalAssignments());
	}

	private static NeutralPlacementGraph boundedTwoNodeGraph() {
		ControlRegionKey region = new ControlRegionKey("bounded", "main", List.of("root"), "main", "compiled");
		CompiledHopKey left = new CompiledHopKey("bounded", "main", "main", "compiled", region,
			"left", "left");
		CompiledHopKey right = new CompiledHopKey("bounded", "main", "main", "compiled", region,
			"right", "right");
		PlacementState cp = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState fed = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);
		Node leftNode = new Node(left, NeutralPlacementGraph.NodeKind.OPERATION,
			new ValueVersionKey("bounded", "left", region, 0, VersionKind.ORDINARY, List.of()), true,
			List.of(cp, fed), List.of(), List.of());
		Node rightNode = new Node(right, NeutralPlacementGraph.NodeKind.OPERATION,
			new ValueVersionKey("bounded", "right", region, 0, VersionKind.ORDINARY, List.of()), true,
			List.of(cp, fed), List.of(), List.of());
		return new NeutralPlacementGraph(List.of(leftNode, rightNode), List.of(), List.of());
	}

	private static void seedRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		FederatedRefedRegistry.register(SENTINEL_SCOPE, 11L, 101L, "anchor:row", java.util.List.of(12L));
		FederatedFoutMaterializeRegistry.register(SENTINEL_SCOPE, 12L, 102L, "ROW", "row-anchor", "anchor:row");
		FederatedLocalMaterializeRegistry.register(SENTINEL_SCOPE, 13L, List.of(14L, 15L), "ROW", "test-sentinel");
	}

	private static NeutralPlacementGraph reversed(NeutralPlacementGraph graph) {
		List<Node> nodes = new ArrayList<>();
		for(Node node : graph.nodes()) {
			List<org.apache.sysds.hops.fedplanner.placement.PlacementState> legal = reversed(node.legalAlternatives());
			List<NeutralPlacementGraph.Exclusion> exclusions = reversed(node.exclusions());
			List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey> anchors =
				reversed(node.anchors());
			nodes.add(new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(), legal, exclusions, anchors));
		}
		Collections.reverse(nodes);
		return new NeutralPlacementGraph(nodes, reversed(graph.constraints()), reversed(graph.relocationActions()));
	}

	private static <T> List<T> reversed(List<T> values) {
		List<T> copy = new ArrayList<>(values);
		Collections.reverse(copy);
		return copy;
	}

	private static String hopFingerprint(DMLProgram program) {
		List<Hop> hops = allHops(program);
		List<String> rows = new ArrayList<>();
		for(Hop hop : hops) {
			List<Long> inputs = hop.getInput().stream().map(Hop::getHopID).sorted().collect(Collectors.toList());
			List<Long> parents = hop.getParent().stream().map(Hop::getHopID).sorted().collect(Collectors.toList());
			rows.add(hop.getHopID() + "|" + hop.getClass().getName() + "|" + hop.getOpString() + "|"
				+ hop.getExecType() + "|" + hop.getForcedExecType() + "|" + hop.getFederatedOutput() + "|"
				+ hop.isFederatedOutputDerived() + "|" + hop.requiresRecompile() + "|" + hop.isVisited()
				+ "|in=" + inputs + "|parents=" + parents);
		}
		Collections.sort(rows);
		return String.join("\n", rows);
	}

	private static List<Hop> allHops(DMLProgram program) {
		List<Hop> result = new ArrayList<>();
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		walkBlocks(program.getStatementBlocks(), result, seen);
		program.getNamedNSFunctionStatementBlocks().entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(entry -> walkBlock(entry.getValue(), result, seen));
		return result;
	}

	private static void walkBlocks(List<StatementBlock> blocks, List<Hop> result, Set<Hop> seen) {
		if(blocks != null)
			for(StatementBlock block : blocks)
				walkBlock(block, result, seen);
	}

	private static void walkBlock(StatementBlock block, List<Hop> result, Set<Hop> seen) {
		List<Hop> roots = new ArrayList<>();
		if(block.getHops() != null)
			roots.addAll(block.getHops());
		if(block instanceof IfStatementBlock)
			roots.add(((IfStatementBlock) block).getPredicateHops());
		if(block instanceof WhileStatementBlock)
			roots.add(((WhileStatementBlock) block).getPredicateHops());
		if(block instanceof ForStatementBlock) {
			roots.add(((ForStatementBlock) block).getFromHops());
			roots.add(((ForStatementBlock) block).getToHops());
			roots.add(((ForStatementBlock) block).getIncrementHops());
		}
		for(Hop root : roots)
			walkHop(root, result, seen);
		if(block instanceof FunctionStatementBlock)
			walkBlocks(((FunctionStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof WhileStatementBlock)
			walkBlocks(((WhileStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof ForStatementBlock)
			walkBlocks(((ForStatement) block.getStatement(0)).getBody(), result, seen);
		else if(block instanceof IfStatementBlock) {
			IfStatement statement = (IfStatement) block.getStatement(0);
			walkBlocks(statement.getIfBody(), result, seen);
			walkBlocks(statement.getElseBody(), result, seen);
		}
	}

	private static void walkHop(Hop hop, List<Hop> result, Set<Hop> seen) {
		if(hop == null || !seen.add(hop))
			return;
		result.add(hop);
		for(Hop input : hop.getInput())
			walkHop(input, result, seen);
	}

	private static String registryFingerprint() throws Exception {
		return registryMap(FederatedRefedRegistry.class, "REFED_ANCHORS") + "|"
			+ registryMap(FederatedFoutMaterializeRegistry.class, "MATERIALIZE_ANCHORS") + "|"
			+ registryMap(FederatedLocalMaterializeRegistry.class, "LOCAL_MATERIALIZE");
	}

	private static String registryMap(Class<?> registry, String fieldName) throws Exception {
		Field field = registry.getDeclaredField(fieldName);
		field.setAccessible(true);
		Object value = field.get(null);
		return registry.getSimpleName() + "=" + String.valueOf(value);
	}
}
