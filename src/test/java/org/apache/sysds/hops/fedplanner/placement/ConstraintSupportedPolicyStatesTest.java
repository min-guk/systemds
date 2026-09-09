/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Abstract contract fixtures for copied-domain hard-constraint support. */
public class ConstraintSupportedPolicyStatesTest {
	private static final PlacementState ROW_LOUT =
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState ROW_FOUT =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState COL_FOUT =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.COL, false);

	@Test
	public void conjunctiveChainPropagatesForcedFoutWithoutMutatingGraphDomains() {
		Node source = node("chain", "source", 0, List.of(ROW_LOUT, ROW_FOUT));
		Node middle = node("chain", "middle", 1, List.of(ROW_LOUT, ROW_FOUT));
		Node sink = node("chain", "sink", 2, List.of(ROW_FOUT));
		NeutralPlacementGraph graph = graph(List.of(source, middle, sink), List.of(
			new Constraint(ConstraintKind.CONJUNCTIVE, source.key(), middle.key(), 0, "chain-source"),
			new Constraint(ConstraintKind.CONJUNCTIVE, middle.key(), sink.key(), 0, "chain-sink")));

		Map<CompiledHopKey,List<PlacementState>> supported =
			NeutralPlacementGraphBuilder.constraintSupportedPolicyStates(graph);

		Assert.assertEquals(List.of(ROW_FOUT), supported.get(source.key()));
		Assert.assertEquals(List.of(ROW_FOUT), supported.get(middle.key()));
		Assert.assertEquals(List.of(ROW_FOUT), supported.get(sink.key()));
		Assert.assertTrue("support propagation must not narrow the immutable base graph",
			graph.node(source.key()).orElseThrow().legalAlternatives().contains(ROW_LOUT));
		Assert.assertTrue(graph.node(middle.key()).orElseThrow().legalAlternatives().contains(ROW_LOUT));
	}

	@Test
	public void parallelConstraintsRequireOneSharedSupportingState() {
		Node left = node("parallel", "left", 0, List.of(ROW_FOUT));
		Node right = node("parallel", "right", 1, List.of(ROW_LOUT, ROW_FOUT));
		Constraint forbidsFout = forbid(left, right, ROW_FOUT, ROW_FOUT);
		Constraint forbidsLout = forbid(left, right, ROW_FOUT, ROW_LOUT);
		Assert.assertTrue(right.legalAlternatives().stream()
			.anyMatch(state -> NeutralPlacementGraph.constraintSatisfied(forbidsFout, ROW_FOUT, state)));
		Assert.assertTrue(right.legalAlternatives().stream()
			.anyMatch(state -> NeutralPlacementGraph.constraintSatisfied(forbidsLout, ROW_FOUT, state)));

		Map<CompiledHopKey,List<PlacementState>> supported = NeutralPlacementGraphBuilder
			.constraintSupportedPolicyStates(graph(List.of(left, right), List.of(forbidsFout, forbidsLout)));

		Assert.assertTrue("different per-constraint witnesses are not one consistent pair",
			supported.get(left.key()).isEmpty());
		Assert.assertTrue(supported.get(right.key()).isEmpty());
	}

	@Test
	public void reverseOrientationAndSelfForbidPairsUseDeclaredPairOrder() {
		Node fixedLeft = node("reverse", "fixed", 0, List.of(ROW_FOUT));
		Node variableRight = node("reverse", "variable", 1, List.of(ROW_LOUT, ROW_FOUT));
		Constraint reverse = forbid(fixedLeft, variableRight, ROW_FOUT, ROW_FOUT);
		Map<CompiledHopKey,List<PlacementState>> reverseSupported = NeutralPlacementGraphBuilder
			.constraintSupportedPolicyStates(graph(List.of(fixedLeft, variableRight), List.of(reverse)));
		Assert.assertEquals("processing the right endpoint must not reverse directional evidence",
			List.of(ROW_LOUT), reverseSupported.get(variableRight.key()));

		Node self = node("self", "self", 0, List.of(ROW_LOUT, ROW_FOUT));
		Constraint selfForbid = forbid(self, self, ROW_FOUT, ROW_FOUT);
		Map<CompiledHopKey,List<PlacementState>> selfSupported = NeutralPlacementGraphBuilder
			.constraintSupportedPolicyStates(graph(List.of(self), List.of(selfForbid)));
		Assert.assertEquals(List.of(ROW_LOUT), selfSupported.get(self.key()));
	}

	@Test
	public void unrelatedEligibleStatesRemainUnchanged() {
		Node fixed = node("unrelated", "fixed", 0, List.of(ROW_FOUT));
		Node constrained = node("unrelated", "constrained", 1, List.of(ROW_LOUT, ROW_FOUT));
		Node unrelated = node("unrelated", "unrelated", 2, List.of(ROW_LOUT, ROW_FOUT, COL_FOUT));
		NeutralPlacementGraph graph = graph(List.of(fixed, constrained, unrelated), List.of(
			new Constraint(ConstraintKind.SAME_PLACEMENT, fixed.key(), constrained.key(), 0, "same")));

		Map<CompiledHopKey,List<PlacementState>> supported =
			NeutralPlacementGraphBuilder.constraintSupportedPolicyStates(graph);

		Assert.assertEquals(unrelated.legalAlternatives(), supported.get(unrelated.key()));
		Assert.assertEquals(unrelated.legalAlternatives(),
			graph.node(unrelated.key()).orElseThrow().legalAlternatives());
	}

	private static Constraint forbid(Node left, Node right, PlacementState leftState,
		PlacementState rightState) {
		return new Constraint(ConstraintKind.CONJUNCTIVE, left.key(), right.key(), 0,
			"forbid-pair:" + leftState.normalizedSignature() + "=>" + rightState.normalizedSignature());
	}

	private static NeutralPlacementGraph graph(List<Node> nodes, List<Constraint> constraints) {
		return new NeutralPlacementGraph(nodes, constraints, List.of());
	}

	private static Node node(String fingerprint, String name, int ordinal,
		List<PlacementState> alternatives) {
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("root"),
			"main", "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled",
			region, name, name);
		ValueVersionKey value = new ValueVersionKey(fingerprint, name, region, ordinal,
			VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true, alternatives, List.of(), List.of());
	}
}
