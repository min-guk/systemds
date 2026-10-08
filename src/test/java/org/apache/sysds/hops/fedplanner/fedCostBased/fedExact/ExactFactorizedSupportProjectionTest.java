/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

public class ExactFactorizedSupportProjectionTest {
	@Test
	public void randomizedQuotientProjectionMatchesExplicitExpandedRelation() {
		Random random = new Random(8_102_026L);
		for(int trial = 0; trial < 200; trial++) {
			int[] domains = {1 + random.nextInt(4), 1 + random.nextInt(4),
				1 + random.nextInt(4)};
			int[] scope = trial % 2 == 0 ? new int[] {2, 0} : new int[] {0, 2};
			int[] baseDimensions = {1 + random.nextInt(3), 1 + random.nextInt(3)};
			int[][] maps = new int[2][];
			for(int axis = 0; axis < scope.length; axis++) {
				maps[axis] = new int[domains[scope[axis]]];
				for(int value = 0; value < maps[axis].length; value++)
					maps[axis][value] = random.nextInt(baseDimensions[axis]);
			}
			int baseCardinality = baseDimensions[0] * baseDimensions[1];
			int[] baseCells = randomSupport(baseCardinality, random);
			int[] expanded = expandedCells(scope, domains, baseDimensions, baseCells, maps);
			ExactFiniteSupportJoin.QuotientProjectedRelation projected =
				new ExactFiniteSupportJoin.QuotientProjectedRelation(
					scope, baseDimensions, baseCells, maps);
			Assert.assertEquals(expanded.length, projected.size());

			int selected = random.nextInt(domains[0]);
			List<ExactFiniteSupportJoin.SupportRelation> factorized = List.of(
				projected, new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {selected}));
			List<ExactFiniteSupportJoin.SupportRelation> explicit = List.of(
				new ExactFiniteSupportJoin.Relation(scope, expanded),
				new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {selected}));
			Set<String> expected = collect(domains, explicit);
			Set<String> actual = collect(domains, factorized);
			Assert.assertEquals("trial=" + trial, expected, actual);
		}
	}

	@Test
	public void hundredMillionCellProjectionStoresOneBaseRowAndJoinsBoundValues() {
		int domain = 20_000;
		int[] first = new int[domain];
		int[] second = new int[domain];
		for(int value = domain / 2; value < domain; value++) {
			first[value] = 1;
			second[value] = 1;
		}
		ExactFiniteSupportJoin.QuotientProjectedRelation projected =
			new ExactFiniteSupportJoin.QuotientProjectedRelation(
				new int[] {0, 1}, new int[] {2, 2}, new int[] {0},
				new int[][] {first, second});
		Assert.assertEquals(100_000_000L, projected.size());
		Assert.assertEquals(1, projected.storedRows());

		List<String> assignments = new ArrayList<>();
		ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
			new int[] {0, 1}, new int[] {domain, domain}, List.of(
				new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {9_999}),
				new ExactFiniteSupportJoin.Relation(new int[] {1}, new int[] {8_888}),
				projected), assignment -> assignments.add(Arrays.toString(assignment)));

		Assert.assertEquals(List.of("[9999, 8888]"), assignments);
		Assert.assertEquals(new ExactFiniteSupportJoin.Work(3L, 1L), work);
	}

	@Test
	public void projectedRelationDefensivelyCopiesMapsAndRejectsInvalidCoordinates() {
		int[][] maps = {{0, 1}, {1, 0}};
		ExactFiniteSupportJoin.QuotientProjectedRelation relation =
			new ExactFiniteSupportJoin.QuotientProjectedRelation(
				new int[] {0, 1}, new int[] {2, 2}, new int[] {1}, maps);
		maps[0][0] = 1;
		Assert.assertEquals(Set.of("[0, 0]"), collect(new int[] {2, 2}, List.of(relation)));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new ExactFiniteSupportJoin.QuotientProjectedRelation(
				new int[] {0}, new int[] {2}, new int[] {0}, new int[][] {{2}}));
	}

	private static int[] randomSupport(int cells, Random random) {
		return java.util.stream.IntStream.range(0, cells)
			.filter(ignored -> random.nextBoolean()).toArray();
	}

	private static int[] expandedCells(int[] scope, int[] domains, int[] baseDimensions,
		int[] baseCells, int[][] maps) {
		int cells = 1;
		for(int variable : scope)
			cells *= domains[variable];
		int[] result = new int[cells];
		int count = 0;
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			int base = 0;
			for(int axis = scope.length - 1; axis >= 0; axis--) {
				int value = remaining % domains[scope[axis]];
				remaining /= domains[scope[axis]];
				base += maps[axis][value] * (axis + 1 == scope.length ? 1 : baseDimensions[axis + 1]);
			}
			if(Arrays.binarySearch(baseCells, base) >= 0)
				result[count++] = cell;
		}
		return Arrays.copyOf(result, count);
	}

	private static Set<String> collect(int[] domains,
		List<? extends ExactFiniteSupportJoin.SupportRelation> relations) {
		Set<String> result = new LinkedHashSet<>();
		int[] variables = java.util.stream.IntStream.range(0, domains.length).toArray();
		ExactFiniteSupportJoin.forEach(variables, domains, relations,
			assignment -> Assert.assertTrue("duplicate assignment",
				result.add(Arrays.toString(assignment))));
		return result;
	}
}
