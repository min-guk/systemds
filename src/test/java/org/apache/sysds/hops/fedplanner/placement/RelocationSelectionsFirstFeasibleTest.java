/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** First-feasible policy selection differs from the exact global emission objective. */
public class RelocationSelectionsFirstFeasibleTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState FED =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final CompiledHopKey PROTECTED = key("protected-source");
	private static final CompiledHopKey RELEASED = key("released-aggregate");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final ValueVersionKey PROTECTED_VERSION = version("protected-source");
	private static final ValueVersionKey RELEASED_VERSION = version("released-aggregate");
	private static final DurableAnchorKey POOL = new DurableAnchorKey("protected-pool",
		FType.ROW, List.of(new AnchorPartition("worker1:8001", List.of(0L, 0L),
			List.of(2L, 2L)), new AnchorPartition("worker2:8002", List.of(2L, 0L),
				List.of(4L, 2L))));

	@Test
	public void firstFeasibleUsesLocalPreferenceAndDoesNotClaimGlobalMinimum() {
		// The protected raw value stays on its existing worker pool. Only an
		// already released aggregate is eligible for an active upload in this
		// graph-authority seam; a compiler analysis must authorize that release.
		RelocationAction protectedDirect = action(PROTECTED_VERSION, "protected", List.of(0), true);
		RelocationAction first = action(RELEASED_VERSION, "a", List.of(1), false);
		RelocationAction shared = action(RELEASED_VERSION, "b", List.of(1, 2), false);
		RelocationAction later = action(RELEASED_VERSION, "c", List.of(2), false);
		List<RelocationAction> actions = List.of(protectedDirect, first, shared, later);
		NeutralPlacementGraph graph = graph(actions);
		Map<CompiledHopKey,PlacementState> assignment = Map.of(
			PROTECTED, FED, RELEASED, LOCAL, CONSUMER, FED);

		var policy = RelocationSelections.selectFirstFeasible(graph, actions, assignment);
		var exact = RelocationSelections.selectCanonical(graph, assignment);
		Assert.assertEquals(3, RelocationSelections.resolveAndValidate(
			graph, actions, assignment, policy.choices()).size());
		Assert.assertEquals(2.0, policy.cost(), 0.0);
		Assert.assertEquals(1, RelocationSelections.physicalEmissionCount(
			RelocationSelections.emittedActions(graph, assignment, exact)));
		Assert.assertTrue(policy.emittedActions().stream().noneMatch(action ->
			action.sourceValueVersion().equals(PROTECTED_VERSION)));
		Assert.assertTrue(policy.choices().stream().anyMatch(choice -> choice.action().equals(first.key())));
		Assert.assertTrue(exact.stream().filter(choice -> choice.action().equals(shared.key())).count() == 2);

		List<RelocationAction> reversed = new ArrayList<>(actions);
		Collections.reverse(reversed);
		var repeat = RelocationSelections.selectFirstFeasible(graph, reversed, assignment);
		Assert.assertEquals(policy.choices(), repeat.choices());
		Assert.assertEquals(policy.emittedActions(), repeat.emittedActions());
	}

	private static RelocationAction action(ValueVersionKey source, String scope,
		List<Integer> positions, boolean direct) {
		RelocationActionKey key = new RelocationActionKey(source, FED, POOL, scope,
			List.of(CONSUMER));
		List<ObligationKey> obligations = positions.stream()
			.map(position -> new ObligationKey(CONSUMER, position, source, FED, key, "context"))
			.toList();
		return new RelocationAction(key, obligations, direct ? List.of(FED) : List.of());
	}

	private static NeutralPlacementGraph graph(List<RelocationAction> actions) {
		return new NeutralPlacementGraph(List.of(
			new Node(PROTECTED, NodeKind.OPERATION, PROTECTED_VERSION, true,
				List.of(FED), List.of(), List.of(POOL)),
			new Node(RELEASED, NodeKind.OPERATION, RELEASED_VERSION, true,
				List.of(LOCAL), List.of(), List.of()),
			new Node(CONSUMER, NodeKind.OPERATION, version("consumer"), true,
				List.of(FED), List.of(), List.of())), List.of(), actions);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("program", "ns", "call", "compiled", region(), id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("program", id, region(), 0, VersionKind.ORDINARY, List.of());
	}

	private static ControlRegionKey region() {
		return new ControlRegionKey("program", "ns", List.of("main/0"), "call", "compiled");
	}
}
