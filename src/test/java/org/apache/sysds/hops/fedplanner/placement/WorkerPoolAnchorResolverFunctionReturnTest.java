/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact worker-pool continuity across compiler-declared function return aliases. */
public class WorkerPoolAnchorResolverFunctionReturnTest {
	private static final PlacementState FULL =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);

	@Test
	public void declaredFunctionReturnAndCfgReadPreserveTheReturnedWorkerPool() throws Exception {
		DurableAnchorKey anchor = anchor("localhost:1234");
		Node returned = node("returned", NodeKind.OPERATION, List.of(anchor));
		Node boundary = node("boundary", NodeKind.FUNCTION_OUTPUT, List.of());
		Node read = node("read", NodeKind.TRANSIENT_READ, List.of());

		Set<DurableAnchorKey> resolved = resolve(List.of(returned, boundary, read), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, returned.key(), boundary.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_PLACEMENT, boundary.key(), read.key(), -1,
				"cfg-function-output-value:Y")), read.key());

		Assert.assertEquals(Set.of(anchor), resolved);
	}

	@Test
	public void declaredInlinedReturnPreservesTheReturnedWorkerPool() throws Exception {
		DurableAnchorKey anchor = anchor("localhost:1234");
		Node returned = node("inlined-returned", NodeKind.OPERATION, List.of(anchor));
		Node boundary = node("inlined-boundary", NodeKind.FUNCTION_OUTPUT, List.of());

		Set<DurableAnchorKey> resolved = resolve(List.of(returned, boundary), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, returned.key(), boundary.key(), 0,
				"inlined-function-result:Y")), boundary.key());

		Assert.assertEquals(Set.of(anchor), resolved);
	}

	@Test
	public void differentReturnEndpointsDoNotCreateAWorkerPoolProof() throws Exception {
		Node first = node("first-return", NodeKind.OPERATION, List.of(anchor("localhost:1234")));
		Node second = node("second-return", NodeKind.OPERATION, List.of(anchor("localhost:1235")));
		Node boundary = node("joined-boundary", NodeKind.FUNCTION_OUTPUT, List.of());

		Set<DurableAnchorKey> resolved = resolve(List.of(first, second, boundary), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, first.key(), boundary.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, second.key(), boundary.key(), 0,
				"function-result:Y")), boundary.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@Test
	public void unknownReturnSourceDoesNotCreateAWorkerPoolProof() throws Exception {
		Node known = node("known-return", NodeKind.OPERATION, List.of(anchor("localhost:1234")));
		Node unknown = node("unknown-return", NodeKind.OPERATION, List.of());
		Node boundary = node("unknown-boundary", NodeKind.FUNCTION_OUTPUT, List.of());

		Set<DurableAnchorKey> resolved = resolve(List.of(known, unknown, boundary), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, known.key(), boundary.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, unknown.key(), boundary.key(), 0,
				"function-result:Y")), boundary.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@Test
	public void unrelatedSameValueConstraintDoesNotCreateAFunctionReturnProof() throws Exception {
		Node source = node("unrelated-source", NodeKind.OPERATION, List.of(anchor("localhost:1234")));
		Node target = node("unrelated-target", NodeKind.FUNCTION_OUTPUT, List.of());

		Set<DurableAnchorKey> resolved = resolve(List.of(source, target), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, source.key(), target.key(), 0,
				"multi-return-primary-result:Y")), target.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@Test
	public void functionOutputAndCfgDefinitionOnSameEndpointPreserveWorkerPool() throws Exception {
		DurableAnchorKey anchor = anchor("localhost:1234");
		Node returned = node("mixed-return", NodeKind.OPERATION, List.of(anchor));
		Node boundary = node("mixed-boundary", NodeKind.FUNCTION_OUTPUT, List.of());
		Node cfgDefinition = node("mixed-definition", NodeKind.OPERATION, List.of(anchor));
		Node read = node("mixed-read", NodeKind.TRANSIENT_READ, List.of(),
			List.of(cfgDefinitionReference(cfgDefinition)));

		Set<DurableAnchorKey> resolved = resolve(List.of(returned, boundary, cfgDefinition, read), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, returned.key(), boundary.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_PLACEMENT, boundary.key(), read.key(), -1,
				"cfg-function-output-value:Y")), read.key());

		Assert.assertEquals(Set.of(anchor), resolved);
	}

	@Test
	public void functionOutputAndDifferentCfgDefinitionDoNotCreateWorkerPoolProof() throws Exception {
		Node returned = node("different-cfg-return", NodeKind.OPERATION,
			List.of(anchor("localhost:1234")));
		Node boundary = node("different-cfg-boundary", NodeKind.FUNCTION_OUTPUT, List.of());
		Node cfgDefinition = node("different-cfg-definition", NodeKind.OPERATION,
			List.of(anchor("localhost:1235")));
		Node read = node("different-cfg-read", NodeKind.TRANSIENT_READ, List.of(),
			List.of(cfgDefinitionReference(cfgDefinition)));

		Set<DurableAnchorKey> resolved = resolve(List.of(returned, boundary, cfgDefinition, read), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, returned.key(), boundary.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_PLACEMENT, boundary.key(), read.key(), -1,
				"cfg-function-output-value:Y")), read.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@Test
	public void functionOutputAndUnknownCfgDefinitionDoNotCreateWorkerPoolProof() throws Exception {
		Node returned = node("unknown-cfg-return", NodeKind.OPERATION,
			List.of(anchor("localhost:1234")));
		Node boundary = node("unknown-cfg-boundary", NodeKind.FUNCTION_OUTPUT, List.of());
		Node cfgDefinition = node("unknown-cfg-definition", NodeKind.OPERATION, List.of());
		Node read = node("unknown-cfg-read", NodeKind.TRANSIENT_READ, List.of(),
			List.of(cfgDefinitionReference(cfgDefinition)));

		Set<DurableAnchorKey> resolved = resolve(List.of(returned, boundary, cfgDefinition, read), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, returned.key(), boundary.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_PLACEMENT, boundary.key(), read.key(), -1,
				"cfg-function-output-value:Y")), read.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@Test
	public void functionOutputAndFormalInputOnSameEndpointPreserveWorkerPool() throws Exception {
		DurableAnchorKey anchor = anchor("localhost:1234");
		Node returned = node("formal-return", NodeKind.OPERATION, List.of(anchor));
		Node outputBoundary = node("formal-output-boundary", NodeKind.FUNCTION_OUTPUT, List.of());
		Node argument = node("formal-argument", NodeKind.OPERATION, List.of(anchor));
		Node formalBoundary = node("formal-input-boundary", NodeKind.FUNCTION_INPUT, List.of());
		Node read = node("formal-read", NodeKind.TRANSIENT_READ, List.of(),
			List.of("cfg-function-input:ns:Y"));

		Set<DurableAnchorKey> resolved = resolve(
			List.of(returned, outputBoundary, argument, formalBoundary, read),
			functionOutputAndFormalConstraints(returned, outputBoundary, argument, formalBoundary, read),
			read.key());

		Assert.assertEquals(Set.of(anchor), resolved);
	}

	@Test
	public void functionOutputAndDifferentFormalInputDoNotCreateWorkerPoolProof() throws Exception {
		Node returned = node("different-formal-return", NodeKind.OPERATION,
			List.of(anchor("localhost:1234")));
		Node outputBoundary = node("different-formal-output-boundary", NodeKind.FUNCTION_OUTPUT, List.of());
		Node argument = node("different-formal-argument", NodeKind.OPERATION,
			List.of(anchor("localhost:1235")));
		Node formalBoundary = node("different-formal-input-boundary", NodeKind.FUNCTION_INPUT, List.of());
		Node read = node("different-formal-read", NodeKind.TRANSIENT_READ, List.of(),
			List.of("cfg-function-input:ns:Y"));

		Set<DurableAnchorKey> resolved = resolve(
			List.of(returned, outputBoundary, argument, formalBoundary, read),
			functionOutputAndFormalConstraints(returned, outputBoundary, argument, formalBoundary, read),
			read.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@Test
	public void functionOutputAndUnknownFormalInputDoNotCreateWorkerPoolProof() throws Exception {
		Node returned = node("unknown-formal-return", NodeKind.OPERATION,
			List.of(anchor("localhost:1234")));
		Node outputBoundary = node("unknown-formal-output-boundary", NodeKind.FUNCTION_OUTPUT, List.of());
		Node argument = node("unknown-formal-argument", NodeKind.OPERATION, List.of());
		Node formalBoundary = node("unknown-formal-input-boundary", NodeKind.FUNCTION_INPUT, List.of());
		Node read = node("unknown-formal-read", NodeKind.TRANSIENT_READ, List.of(),
			List.of("cfg-function-input:ns:Y"));

		Set<DurableAnchorKey> resolved = resolve(
			List.of(returned, outputBoundary, argument, formalBoundary, read),
			functionOutputAndFormalConstraints(returned, outputBoundary, argument, formalBoundary, read),
			read.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@Test
	public void ungroundedFunctionOutputAliasCycleDoesNotCreateWorkerPoolProof() throws Exception {
		Node first = node("cycle-first", NodeKind.FUNCTION_OUTPUT, List.of());
		Node second = node("cycle-second", NodeKind.FUNCTION_OUTPUT, List.of());

		Set<DurableAnchorKey> resolved = resolve(List.of(first, second), List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, first.key(), second.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, second.key(), first.key(), 0,
				"function-result:Y")), first.key());

		Assert.assertTrue(resolved.isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static Set<DurableAnchorKey> resolve(List<Node> nodes,
		List<Constraint> constraints, CompiledHopKey target) throws Exception {
		Class<?> resolverClass = java.util.Arrays.stream(
			NeutralPlacementGraphBuilder.class.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("WorkerPoolAnchorResolver"))
			.findFirst().orElseThrow();
		Constructor<?> constructor = resolverClass.getDeclaredConstructor(Map.class, Map.class,
			List.class, List.class, Collection.class, Map.class, Map.class);
		constructor.setAccessible(true);
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		nodes.forEach(node -> nodesByKey.put(node.key(), node));
		Object resolver = constructor.newInstance(nodesByKey, Map.of(), List.of(), List.of(),
			constraints, Map.of(), Map.of());
		Method resolve = resolverClass.getDeclaredMethod("resolve", CompiledHopKey.class, FType.class);
		resolve.setAccessible(true);
		return (Set<DurableAnchorKey>)resolve.invoke(resolver, target, FType.FULL);
	}

	private static Node node(String name, NodeKind kind, List<DurableAnchorKey> anchors) {
		return node(name, kind, anchors, List.of());
	}

	private static Node node(String name, NodeKind kind, List<DurableAnchorKey> anchors,
		List<String> predecessorVersions) {
		ControlRegionKey region = new ControlRegionKey("function-return-anchor", "main",
			List.of("root"), "root", "compiled");
		CompiledHopKey key = new CompiledHopKey("function-return-anchor", "main", "root",
			"compiled", region, name, name);
		ValueVersionKey value = new ValueVersionKey("function-return-anchor", name, region,
			0, VersionKind.ORDINARY, predecessorVersions);
		return new Node(key, kind, value, true, List.of(FULL), List.of(), anchors);
	}

	private static String cfgDefinitionReference(Node source) {
		return "cfg-definition:" + source.valueVersion().cfgReferenceSignature();
	}

	private static List<Constraint> functionOutputAndFormalConstraints(Node returned,
		Node outputBoundary, Node argument, Node formalBoundary, Node read) {
		return List.of(
			new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, returned.key(), outputBoundary.key(), 0,
				"function-result:Y"),
			new Constraint(ConstraintKind.SAME_PLACEMENT, outputBoundary.key(), read.key(), -1,
				"cfg-function-output-value:Y"),
			new Constraint(ConstraintKind.CONJUNCTIVE, argument.key(), formalBoundary.key(), 0,
				"function-argument:Y"),
			new Constraint(ConstraintKind.SAME_PLACEMENT, formalBoundary.key(), read.key(), -1,
				"function-formal-input"));
	}

	private static DurableAnchorKey anchor(String endpoint) {
		return new DurableAnchorKey(endpoint, FType.FULL, List.of(
			new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 2L))));
	}
}
