/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.Component;
import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.InvalidationAdjacency;
import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.SemanticDependency;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

/** Exact reuse contracts for the bounded direct-component schedule cache. */
public class DirectComponentScheduleCacheTest {
	@Test
	public void reorderedDuplicateEdgesReuseTheExactColdSchedule() {
		CompiledHopKey a = key("a"), b = key("b"), c = key("c"), d = key("d");
		List<CompiledHopKey> owners = List.of(c, a, d, b);
		List<SemanticDependency> dependencies = List.of(
			edge(a, b), edge(b, a), edge(b, c), edge(c, d), edge(a, b));
		List<InvalidationAdjacency> aliases = List.of(alias(a, d), alias(d, a));
		DirectComponentScheduleCache cache = new DirectComponentScheduleCache();
		PlacementDependencyComponents first = cache.get(owners, dependencies, aliases);
		PlacementDependencyComponents reordered = cache.get(owners,
			List.of(edge(c, d), edge(a, b), edge(b, c), edge(b, a)),
			List.of(alias(d, a)));

		Assert.assertSame("edge order and duplicates do not change the exact graph", first, reordered);
		assertEquivalent(new PlacementDependencyComponents(owners, dependencies, aliases), first, owners);
		Assert.assertTrue(first.componentOf(a).cyclic());
		Assert.assertSame(first.componentOf(a), first.componentOf(b));
	}

	@Test
	public void edgeAndAliasChangesMissAndMatchTheirOwnColdGraphs() {
		CompiledHopKey a = key("a"), b = key("b"), c = key("c");
		List<CompiledHopKey> owners = List.of(a, b, c);
		DirectComponentScheduleCache cache = new DirectComponentScheduleCache();
		List<SemanticDependency> chain = List.of(edge(a, b), edge(b, c));
		PlacementDependencyComponents base = cache.get(owners, chain, List.of());
		PlacementDependencyComponents cycle = cache.get(owners,
			List.of(edge(a, b), edge(b, c), edge(c, a)), List.of());
		PlacementDependencyComponents alias = cache.get(owners, chain, List.of(alias(a, c)));
		PlacementDependencyComponents removed = cache.get(owners, List.of(edge(a, b)), List.of());
		PlacementDependencyComponents selfEdge = cache.get(owners,
			List.of(edge(a, b), edge(b, c), edge(c, c)), List.of());

		Assert.assertNotSame(base, cycle);
		Assert.assertNotSame(base, alias);
		Assert.assertNotSame(base, removed);
		Assert.assertNotSame(base, selfEdge);
		Assert.assertFalse(base.componentOf(a).cyclic());
		Assert.assertTrue(cycle.componentOf(a).cyclic());
		Assert.assertTrue("directed semantic self-edges are retained", selfEdge.componentOf(c).cyclic());
		Assert.assertEquals(List.of(alias.componentOf(a), alias.componentOf(c)),
			alias.initialDirtyComponents(List.of(a)));
		assertEquivalent(new PlacementDependencyComponents(owners, chain, List.of()), base, owners);
		assertEquivalent(new PlacementDependencyComponents(owners,
			List.of(edge(a, b), edge(b, c), edge(c, a)), List.of()), cycle, owners);
		assertEquivalent(new PlacementDependencyComponents(owners, chain, List.of(alias(a, c))), alias, owners);
		assertEquivalent(new PlacementDependencyComponents(owners, List.of(edge(a, b)), List.of()), removed, owners);
		assertEquivalent(new PlacementDependencyComponents(owners,
			List.of(edge(a, b), edge(b, c), edge(c, c)), List.of()), selfEdge, owners);
		Assert.assertSame("returning to an exact retained graph reuses it", base,
			cache.get(owners, chain, List.of()));
	}

	@Test
	public void ownerIdentityAndRegistryOrderArePartOfTheCacheKey() {
		CompiledHopKey first = key("same");
		CompiledHopKey equalForeign = copy(first);
		CompiledHopKey tail = key("tail");
		DirectComponentScheduleCache cache = new DirectComponentScheduleCache();
		PlacementDependencyComponents original = cache.get(List.of(first, tail), List.of(), List.of());
		PlacementDependencyComponents foreign = cache.get(List.of(equalForeign, tail), List.of(), List.of());
		PlacementDependencyComponents forwardTie = cache.get(
			List.of(first, equalForeign), List.of(), List.of());
		PlacementDependencyComponents reversedTie = cache.get(
			List.of(equalForeign, first), List.of(), List.of());

		Assert.assertEquals(first, equalForeign);
		Assert.assertNotSame(first, equalForeign);
		Assert.assertNotSame(original, foreign);
		Assert.assertNotSame(forwardTie, reversedTie);
		Assert.assertSame(first, original.topologicalOrder().get(0).owners().get(0));
		Assert.assertSame(equalForeign, foreign.topologicalOrder().get(0).owners().get(0));
		Assert.assertSame(first, forwardTie.topologicalOrder().get(0).owners().get(0));
		Assert.assertSame(equalForeign, reversedTie.topologicalOrder().get(0).owners().get(0));

		Assert.assertThrows(IllegalArgumentException.class,
			() -> cache.get(List.of(first, first), List.of(), List.of()));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> cache.get(List.of(first), List.of(edge(first, tail)), List.of()));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> cache.get(List.of(first), List.of(), List.of(alias(first, tail))));
	}

	@Test
	public void boundedEvictionAndClearChangeReuseOnly() {
		CompiledHopKey a = key("a"), b = key("b"), c = key("c"), d = key("d");
		List<CompiledHopKey> owners = List.of(a, b, c, d);
		List<SemanticDependency> choices = List.of(
			edge(a, b), edge(b, c), edge(c, d), edge(d, a));
		DirectComponentScheduleCache cache = new DirectComponentScheduleCache();
		List<PlacementDependencyComponents> schedules = new ArrayList<>();
		for(int mask = 0; mask < 9; mask++) {
			List<SemanticDependency> dependencies = selected(choices, mask);
			PlacementDependencyComponents schedule = cache.get(owners, dependencies, List.of());
			schedules.add(schedule);
			assertEquivalent(new PlacementDependencyComponents(owners, dependencies, List.of()),
				schedule, owners);
		}

		Assert.assertSame("most recent entry remains cached", schedules.get(8),
			cache.get(owners, selected(choices, 8), List.of()));
		PlacementDependencyComponents rebuiltFirst = cache.get(owners, List.of(), List.of());
		Assert.assertNotSame("ninth distinct graph evicts the least recently used entry",
			schedules.get(0), rebuiltFirst);
		assertEquivalent(new PlacementDependencyComponents(owners, List.of(), List.of()), rebuiltFirst, owners);
		cache.clear();
		Assert.assertNotSame("clear affects reuse only", rebuiltFirst,
			cache.get(owners, List.of(), List.of()));
	}

	private static void assertEquivalent(PlacementDependencyComponents expected,
		PlacementDependencyComponents actual, List<CompiledHopKey> owners) {
		Assert.assertEquals(expected.topologicalOrder().size(), actual.topologicalOrder().size());
		for(int index = 0; index < expected.topologicalOrder().size(); index++) {
			Component left = expected.topologicalOrder().get(index);
			Component right = actual.topologicalOrder().get(index);
			Assert.assertEquals(left.topologicalIndex(), right.topologicalIndex());
			Assert.assertEquals(left.cyclic(), right.cyclic());
			assertSameIdentities(left.owners(), right.owners());
			Assert.assertEquals(componentIndexes(expected, expected.immediateSuccessors(left)),
				componentIndexes(actual, actual.immediateSuccessors(right)));
			Assert.assertEquals(componentIndexes(expected, expected.exportChangeFrontier(left)),
				componentIndexes(actual, actual.exportChangeFrontier(right)));
		}
		for(CompiledHopKey owner : owners) {
			Assert.assertEquals(expected.componentOf(owner).topologicalIndex(),
				actual.componentOf(owner).topologicalIndex());
			assertSameIdentities(expected.semanticConsumers(owner), actual.semanticConsumers(owner));
			assertSameIdentities(expected.invalidationPeers(owner), actual.invalidationPeers(owner));
			Assert.assertEquals(componentIndexes(expected, expected.initialDirtyComponents(List.of(owner))),
				componentIndexes(actual, actual.initialDirtyComponents(List.of(owner))));
		}
	}

	private static List<Integer> componentIndexes(PlacementDependencyComponents graph,
		List<Component> components) {
		return components.stream().map(component -> {
			Assert.assertSame(graph.componentOf(component.owners().get(0)), component);
			return component.topologicalIndex();
		}).toList();
	}

	private static void assertSameIdentities(List<CompiledHopKey> expected, List<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(int index = 0; index < expected.size(); index++)
			Assert.assertSame(expected.get(index), actual.get(index));
	}

	private static List<SemanticDependency> selected(List<SemanticDependency> choices, int mask) {
		List<SemanticDependency> result = new ArrayList<>();
		for(int index = 0; index < choices.size(); index++)
			if((mask & 1 << index) != 0)
				result.add(choices.get(index));
		return List.copyOf(result);
	}

	private static SemanticDependency edge(CompiledHopKey producer, CompiledHopKey consumer) {
		return new SemanticDependency(producer, consumer);
	}

	private static InvalidationAdjacency alias(CompiledHopKey left, CompiledHopKey right) {
		return new InvalidationAdjacency(left, right);
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"direct-component-cache", "main", List.of("main"), "main", "compiled");
		return new CompiledHopKey("direct-component-cache", "main", "main",
			"compiled", region, name, name);
	}

	private static CompiledHopKey copy(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(), key.callSitePath(),
			key.recompileContext(), key.controlRegion(), key.emittedHopInstance(), key.canonicalSourceOrigin());
	}
}
