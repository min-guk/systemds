/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class JointPhysicalCostRowsPrefixTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"joint-row-prefix", "main", List.of("root"), "main", "compiled");

	@Test
	public void rejectedPrefixDoesNotExpandRemainingAxes() {
		CompiledHopKey reader = key("reader");
		CompiledHopKey otherReader = key("other-reader");
		CompiledHopKey allowed = key("allowed");
		CompiledHopKey rejected = key("rejected");
		var relation = relation(reader, otherReader, allowed, key("other-source"));
		var choices = List.of(
			List.of(value(reader, rejected), value(reader, allowed)),
			java.util.stream.IntStream.range(0, 100).mapToObj(index -> emptyValue("tail-" + index)).toList());
		List<JointPhysicalCostRows.Row> rows = new ArrayList<>();

		JointPhysicalCostRows.ProductWork work =
			JointPhysicalCostRows.enumerateMatchingProduct(choices, relation, rows);

		Assert.assertEquals(100, rows.size());
		Assert.assertEquals("two root choices plus only the legal root's tail", 102, work.visitedValues());
		Assert.assertEquals(100, work.emittedRows());
	}

	@Test
	public void prefixEnumerationMatchesExhaustiveFilterForMixedRows() {
		CompiledHopKey readerA = key("reader-a");
		CompiledHopKey readerB = key("reader-b");
		CompiledHopKey sourceA0 = key("source-a0");
		CompiledHopKey sourceA1 = key("source-a1");
		CompiledHopKey sourceB0 = key("source-b0");
		CompiledHopKey sourceB1 = key("source-b1");
		var relation = new JointValueMapRelations.Relation(key("consumer"), List.of(readerA, readerB), List.of(
			new JointValueMapRelations.Row(List.of(
				new JointValueMapRelations.InputSource(0, readerA, sourceA0),
				new JointValueMapRelations.InputSource(1, readerB, sourceB0))),
			new JointValueMapRelations.Row(List.of(
				new JointValueMapRelations.InputSource(0, readerA, sourceA1),
				new JointValueMapRelations.InputSource(1, readerB, sourceB1)))),
			List.of(sourceA0, sourceA1, sourceB0, sourceB1));
		List<JointPhysicalCostRows.Value> atoms = List.of(
			value(readerA, sourceA0), value(readerA, sourceA1),
			value(readerB, sourceB0), value(readerB, sourceB1),
			value(readerA, sourceA0, readerB, sourceB1), emptyValue("empty"));
		assertSameAsExhaustive(List.of(), relation, "zero axes");
		assertSameAsExhaustive(List.of(List.of()), relation, "empty axis");
		assertSameAsExhaustive(List.of(Collections.singletonList(null),
			List.of(value(readerA, sourceA0))), relation, "null input");
		Random random = new Random(73);
		for(int trial = 0; trial < 200; trial++) {
			List<List<JointPhysicalCostRows.Value>> choices = new ArrayList<>();
			int axes = random.nextInt(5);
			for(int axis = 0; axis < axes; axis++) {
				List<JointPhysicalCostRows.Value> values = new ArrayList<>();
				int size = random.nextInt(5);
				for(int index = 0; index < size; index++)
					values.add(random.nextInt(5) == 0 ? null : atoms.get(random.nextInt(atoms.size())));
				choices.add(values);
			}
			assertSameAsExhaustive(choices, relation, "trial " + trial);
		}
	}

	private static void assertSameAsExhaustive(List<List<JointPhysicalCostRows.Value>> choices,
		JointValueMapRelations.Relation relation, String message) {
		List<JointPhysicalCostRows.Row> expected = exhaustive(choices, relation);
		List<JointPhysicalCostRows.Row> actual = new ArrayList<>();
		JointPhysicalCostRows.ProductWork work =
			JointPhysicalCostRows.enumerateMatchingProduct(choices, relation, actual);
		Assert.assertEquals(message, expected, actual);
		Assert.assertEquals(message, actual.size(), work.emittedRows());
	}

	private static List<JointPhysicalCostRows.Row> exhaustive(
		List<List<JointPhysicalCostRows.Value>> choices, JointValueMapRelations.Relation relation) {
		List<JointPhysicalCostRows.Row> rows = new ArrayList<>();
		exhaustive(choices, 0, new ArrayList<>(), rows);
		rows.removeIf(row -> !matches(relation, row));
		return rows;
	}

	private static void exhaustive(List<List<JointPhysicalCostRows.Value>> choices, int position,
		List<JointPhysicalCostRows.Value> current, List<JointPhysicalCostRows.Row> rows) {
		if(position == choices.size()) {
			rows.add(new JointPhysicalCostRows.Row(Collections.unmodifiableList(new ArrayList<>(current))));
			return;
		}
		for(var value : choices.get(position)) {
			current.add(value);
			exhaustive(choices, position + 1, current, rows);
			current.remove(current.size() - 1);
		}
	}

	private static boolean matches(JointValueMapRelations.Relation relation, JointPhysicalCostRows.Row row) {
		Map<CompiledHopKey,CompiledHopKey> selected = new IdentityHashMap<>();
		for(var value : row.inputs()) {
			if(value == null) continue;
			for(var entry : value.sources().entrySet()) {
				CompiledHopKey prior = selected.putIfAbsent(entry.getKey(), entry.getValue());
				if(prior != null && prior != entry.getValue()) return false;
			}
		}
		return relation.rows().stream().anyMatch(tuple -> tuple.inputs().stream().allMatch(input ->
			!selected.containsKey(input.reader()) || selected.get(input.reader()) == input.source()));
	}

	private static JointValueMapRelations.Relation relation(CompiledHopKey readerA, CompiledHopKey readerB,
		CompiledHopKey sourceA, CompiledHopKey sourceB) {
		return new JointValueMapRelations.Relation(key("consumer"), List.of(readerA, readerB),
			List.of(new JointValueMapRelations.Row(List.of(
				new JointValueMapRelations.InputSource(0, readerA, sourceA),
				new JointValueMapRelations.InputSource(1, readerB, sourceB)))), List.of(sourceA, sourceB));
	}

	private static JointPhysicalCostRows.Value value(CompiledHopKey reader, CompiledHopKey source) {
		return new JointPhysicalCostRows.Value(anchor(source.emittedHopInstance()), Map.of(reader, source), Set.of());
	}

	private static JointPhysicalCostRows.Value value(CompiledHopKey readerA, CompiledHopKey sourceA,
		CompiledHopKey readerB, CompiledHopKey sourceB) {
		Map<CompiledHopKey,CompiledHopKey> sources = new IdentityHashMap<>();
		sources.put(readerA, sourceA);
		sources.put(readerB, sourceB);
		return new JointPhysicalCostRows.Value(anchor("mixed"), Collections.unmodifiableMap(sources), Set.of());
	}

	private static JointPhysicalCostRows.Value emptyValue(String id) {
		return new JointPhysicalCostRows.Value(anchor(id), Map.of(), Set.of());
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("localhost:2345/" + id, List.of(0L, 0L), List.of(1L, 1L))));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("joint-row-prefix", "main", "main", "compiled", REGION, id, id);
	}
}
