/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.Component;
import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.InvalidationAdjacency;
import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.SemanticDependency;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class PlacementDependencyComponentsTest {
	@Test
	public void chainSchedulesProducerBeforeConsumerAndInvalidatesDownstream() {
		CompiledHopKey a = key("a");
		CompiledHopKey b = key("b");
		CompiledHopKey c = key("c");
		PlacementDependencyComponents graph = graph(List.of(c, a, b),
			edge(a, b), edge(a, b), edge(b, c));

		Assert.assertEquals(List.of(a, b, c), scheduledOwners(graph));
		Assert.assertEquals(List.of(graph.componentOf(b)), graph.initialDirtyComponents(List.of(b)));
		Assert.assertEquals(List.of(graph.componentOf(c)),
			graph.immediateSuccessors(graph.componentOf(b)));
	}

	@Test
	public void diamondKeepsSharedJoinAsOneDownstreamComponent() {
		CompiledHopKey root = key("root");
		CompiledHopKey left = key("left");
		CompiledHopKey right = key("right");
		CompiledHopKey join = key("join");
		PlacementDependencyComponents graph = graph(List.of(join, right, root, left),
			edge(root, right), edge(left, join), edge(root, left), edge(right, join));

		Assert.assertEquals(List.of(graph.componentOf(left), graph.componentOf(right)),
			graph.immediateSuccessors(graph.componentOf(root)));
		Assert.assertEquals(List.of(graph.componentOf(join)),
			graph.immediateSuccessors(graph.componentOf(left)));
		Assert.assertEquals(List.of(root, left, right, join), scheduledOwners(graph));
	}

	@Test
	public void joinWaitsForBothIndependentProducers() {
		CompiledHopKey a = key("a");
		CompiledHopKey b = key("b");
		CompiledHopKey join = key("join");
		PlacementDependencyComponents graph = graph(List.of(join, b, a), edge(b, join), edge(a, join));

		Assert.assertTrue(graph.componentOf(a).topologicalIndex() < graph.componentOf(join).topologicalIndex());
		Assert.assertTrue(graph.componentOf(b).topologicalIndex() < graph.componentOf(join).topologicalIndex());
		Assert.assertEquals(List.of(graph.componentOf(a)), graph.initialDirtyComponents(List.of(a)));
		Assert.assertEquals(List.of(graph.componentOf(join)),
			graph.immediateSuccessors(graph.componentOf(a)));
	}

	@Test
	public void loopFormsOneCyclicComponent() {
		CompiledHopKey a = key("a");
		CompiledHopKey b = key("b");
		CompiledHopKey c = key("c");
		PlacementDependencyComponents graph = graph(List.of(a, b, c),
			edge(a, b), edge(b, c), edge(c, a));

		Assert.assertSame(graph.componentOf(a), graph.componentOf(b));
		Assert.assertSame(graph.componentOf(b), graph.componentOf(c));
		Assert.assertTrue(graph.componentOf(a).cyclic());
		Assert.assertEquals(List.of(a, b, c), graph.componentOf(a).owners());
	}

	@Test
	public void singletonIsCyclicOnlyWithSemanticSelfEdge() {
		CompiledHopKey plain = key("plain");
		CompiledHopKey recursive = key("recursive");
		PlacementDependencyComponents graph = graph(List.of(recursive, plain), edge(recursive, recursive));

		Assert.assertFalse(graph.componentOf(plain).cyclic());
		Assert.assertTrue(graph.componentOf(recursive).cyclic());
	}

	@Test
	public void equalLookingOwnersAtDifferentCallsitesRemainDistinct() {
		CompiledHopKey first = key("first-call", "same-hop", "same-origin");
		CompiledHopKey second = key("second-call", "same-hop", "same-origin");
		CompiledHopKey equalCopy = new CompiledHopKey(first.programFingerprint(), first.functionNamespace(),
			first.callSitePath(), first.recompileContext(), first.controlRegion(), first.emittedHopInstance(),
			first.canonicalSourceOrigin());
		PlacementDependencyComponents graph = graph(List.of(first, second, equalCopy), edge(first, second));

		Assert.assertNotSame(graph.componentOf(first), graph.componentOf(equalCopy));
		Assert.assertNotSame(graph.componentOf(first), graph.componentOf(second));
		Assert.assertEquals(3, graph.topologicalOrder().size());
	}

	@Test
	public void registeredOrderBreaksTiesBetweenStructurallyEqualIdentities() {
		CompiledHopKey first = key("same");
		CompiledHopKey equalCopy = new CompiledHopKey(first.programFingerprint(), first.functionNamespace(),
			first.callSitePath(), first.recompileContext(), first.controlRegion(), first.emittedHopInstance(),
			first.canonicalSourceOrigin());

		PlacementDependencyComponents forward = graph(List.of(first, equalCopy));
		PlacementDependencyComponents reversed = graph(List.of(equalCopy, first));
		Assert.assertSame(first, scheduledOwners(forward).get(0));
		Assert.assertSame(equalCopy, scheduledOwners(reversed).get(0));
	}

	@Test
	public void identityValuedEdgesSurviveCallerSetDistinct() {
		CompiledHopKey producer = key("producer");
		CompiledHopKey producerCopy = copy(producer);
		CompiledHopKey consumer = key("consumer");
		CompiledHopKey consumerCopy = copy(consumer);
		SemanticDependency first = edge(producer, consumer);
		SemanticDependency second = edge(producerCopy, consumerCopy);
		Set<SemanticDependency> callerSet = new HashSet<>(List.of(first, second));

		Assert.assertEquals(2, callerSet.size());
		PlacementDependencyComponents graph = new PlacementDependencyComponents(
			List.of(producer, producerCopy, consumer, consumerCopy), new ArrayList<>(callerSet), List.of());
		Assert.assertEquals(List.of(graph.componentOf(consumer)),
			graph.immediateSuccessors(graph.componentOf(producer)));
		Assert.assertEquals(List.of(graph.componentOf(consumerCopy)),
			graph.immediateSuccessors(graph.componentOf(producerCopy)));
	}

	@Test
	public void aliasesWidenInvalidationWithoutCreatingSemanticScc() {
		CompiledHopKey source = key("source");
		CompiledHopKey consumer = key("consumer");
		CompiledHopKey alias = key("alias");
		CompiledHopKey aliasConsumer = key("alias-consumer");
		PlacementDependencyComponents graph = new PlacementDependencyComponents(
			List.of(source, consumer, alias, aliasConsumer),
			List.of(edge(source, consumer), edge(alias, aliasConsumer)),
			List.of(new InvalidationAdjacency(source, alias)));

		Assert.assertNotSame(graph.componentOf(source), graph.componentOf(alias));
		Assert.assertFalse(graph.componentOf(source).cyclic());
		Assert.assertEquals(List.of(alias), graph.invalidationPeers(source));
		Assert.assertEquals(List.of(graph.componentOf(alias), graph.componentOf(source)),
			graph.initialDirtyComponents(List.of(source)));
		Assert.assertEquals(List.of(graph.componentOf(aliasConsumer)),
			graph.immediateSuccessors(graph.componentOf(alias)));
		Assert.assertEquals(List.of(graph.componentOf(consumer)),
			graph.immediateSuccessors(graph.componentOf(source)));
	}

	@Test
	public void aliasDiscoveredAfterSemanticHopAppearsInSecondWave() {
		CompiledHopKey a = key("a");
		CompiledHopKey b = key("b");
		CompiledHopKey c = key("c");
		PlacementDependencyComponents graph = new PlacementDependencyComponents(List.of(a, b, c),
			List.of(edge(a, b)), List.of(new InvalidationAdjacency(b, c)));

		Assert.assertEquals(List.of(graph.componentOf(a)), graph.initialDirtyComponents(List.of(a)));
		Assert.assertEquals(List.of(graph.componentOf(b)),
			graph.exportChangeFrontier(graph.componentOf(a)));
		Assert.assertEquals(List.of(graph.componentOf(c)),
			graph.exportChangeFrontier(graph.componentOf(b)));
	}

	@Test
	public void longChainEnqueuesOnlyOneImmediateSuccessorPerChangedExport() {
		int length = 512;
		List<CompiledHopKey> owners = new ArrayList<>(length);
		List<SemanticDependency> dependencies = new ArrayList<>(length - 1);
		for(int i = 0; i < length; i++)
			owners.add(key(String.format("owner-%04d", i)));
		for(int i = 1; i < length; i++)
			dependencies.add(edge(owners.get(i - 1), owners.get(i)));
		PlacementDependencyComponents graph = new PlacementDependencyComponents(
			owners, dependencies, List.of());

		Assert.assertEquals(List.of(graph.componentOf(owners.get(0))),
			graph.initialDirtyComponents(List.of(owners.get(0))));
		for(int i = 0; i < length - 1; i++)
			Assert.assertEquals(List.of(graph.componentOf(owners.get(i + 1))),
				graph.exportChangeFrontier(graph.componentOf(owners.get(i))));
		Assert.assertTrue(graph.exportChangeFrontier(graph.componentOf(owners.get(length - 1))).isEmpty());
	}

	private static PlacementDependencyComponents graph(List<CompiledHopKey> owners,
		SemanticDependency... dependencies) {
		return new PlacementDependencyComponents(owners, List.of(dependencies), List.of());
	}

	private static SemanticDependency edge(CompiledHopKey producer, CompiledHopKey consumer) {
		return new SemanticDependency(producer, consumer);
	}

	private static List<CompiledHopKey> scheduledOwners(PlacementDependencyComponents graph) {
		List<CompiledHopKey> result = new ArrayList<>();
		for(Component component : graph.topologicalOrder())
			result.addAll(component.owners());
		return result;
	}

	private static CompiledHopKey key(String owner) {
		return key("call", owner, owner);
	}

	private static CompiledHopKey key(String callsite, String owner, String origin) {
		ControlRegionKey region = new ControlRegionKey("program", "main", List.of("body"),
			callsite, "compiled");
		return new CompiledHopKey("program", "main", callsite, "compiled", region, owner, origin);
	}

	private static CompiledHopKey copy(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(), key.callSitePath(),
			key.recompileContext(), key.controlRegion(), key.emittedHopInstance(), key.canonicalSourceOrigin());
	}
}
