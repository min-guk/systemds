/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Marker resolution keeps exact identity and fail-closed ambiguity semantics. */
public class HeuristicPlacementAdapterIdentityIndexTest {
	@Test public void uniqueMarkerResolvesAndUnknownOrAmbiguousMarkerFails() throws Exception {
		Node first = node("first", "value", 0);
		NeutralPlacementGraph unique = new NeutralPlacementGraph(List.of(first), List.of(), List.of());
		Assert.assertEquals(List.of(first.key()), markerKeys(unique, Set.of(first.valueVersion())));

		ValueVersionKey unknown = value("missing", 0);
		assertInvalid(unique, Set.of(unknown));

		Node second = node("second", "value", 0);
		NeutralPlacementGraph ambiguous = new NeutralPlacementGraph(
			List.of(first, second), List.of(), List.of());
		assertInvalid(ambiguous, Set.of(first.valueVersion()));
	}

	@SuppressWarnings("unchecked")
	private static List<CompiledHopKey> markerKeys(NeutralPlacementGraph graph,
		Set<ValueVersionKey> markers) throws Exception {
		Method method = HeuristicPlacementAdapter.class.getDeclaredMethod(
			"markerKeys", NeutralPlacementGraph.class, Set.class);
		method.setAccessible(true);
		return (List<CompiledHopKey>)method.invoke(null, graph, markers);
	}

	private static void assertInvalid(NeutralPlacementGraph graph,
		Set<ValueVersionKey> markers) throws Exception {
		try {
			markerKeys(graph, markers);
			Assert.fail("Expected unknown or ambiguous marker rejection");
		}
		catch(InvocationTargetException error) {
			Assert.assertTrue(error.getCause() instanceof IllegalArgumentException);
			Assert.assertEquals("Unknown or ambiguous demotion marker", error.getCause().getMessage());
		}
	}

	private static Node node(String id, String value, int generation) {
		ControlRegionKey region = region();
		CompiledHopKey key = new CompiledHopKey("adapter-index", "main", "root", "compiled",
			region, id, id);
		return new Node(key, NodeKind.OPERATION, value(value, generation), true,
			List.of(new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false)),
			List.of(), List.of());
	}

	private static ValueVersionKey value(String name, int generation) {
		return new ValueVersionKey("adapter-index", name, region(), generation,
			VersionKind.ORDINARY, List.of());
	}

	private static ControlRegionKey region() {
		return new ControlRegionKey("adapter-index", "main", List.of("root"),
			"root", "compiled");
	}
}
