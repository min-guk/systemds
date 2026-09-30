/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Test;

public class ExactFiniteSupportJoinTest {
	@Test
	public void randomizedSparseJoinsMatchIndependentCartesianOracle() {
		Random random = new Random(29_092_026L);
		for(int trial = 0; trial < 300; trial++) {
			int variableCount = 1 + random.nextInt(5);
			int[] domains = new int[variableCount];
			for(int variable = 0; variable < variableCount; variable++)
				domains[variable] = 1 + random.nextInt(3);
			int[] variables = permutation(variableCount, random);
			List<ExactFiniteSupportJoin.Relation> relations = new ArrayList<>();
			int relationCount = random.nextInt(5);
			for(int ordinal = 0; ordinal < relationCount; ordinal++) {
				int scopeSize = random.nextInt(variableCount + 1);
				int[] scope = Arrays.copyOf(permutation(variableCount, random), scopeSize);
				int cells = 1;
				for(int variable : scope)
					cells *= domains[variable];
				TreeSet<Integer> finite = new TreeSet<>();
				for(int cell = 0; cell < cells; cell++)
					if(random.nextBoolean())
						finite.add(cell);
				relations.add(new ExactFiniteSupportJoin.Relation(scope,
					finite.stream().mapToInt(Integer::intValue).toArray()));
			}

			Set<String> expected = exhaustive(variables, domains, relations);
			List<String> emitted = new ArrayList<>();
			ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
				variables, domains, relations, assignment -> emitted.add(Arrays.toString(assignment)));

			Assert.assertEquals("trial=" + trial, expected, new LinkedHashSet<>(emitted));
			Assert.assertEquals("duplicate assignment at trial=" + trial,
				emitted.size(), new LinkedHashSet<>(emitted).size());
			Assert.assertEquals(expected.size(), work.emittedAssignments());
			Assert.assertTrue(work.visitedRelationRows() >= 0L);
		}
	}

	@Test
	public void repeatedJoinKeysReversedScopesAndUnboundVariableAreExactAndDeterministic() {
		int[] domains = {2, 3, 2, 2};
		int[] variables = {2, 0, 3, 1};
		List<ExactFiniteSupportJoin.Relation> relations = List.of(
			// scope [1,0]: (0,0), (1,0), (2,1); key 0 has two matching rows.
			new ExactFiniteSupportJoin.Relation(new int[] {1, 0}, new int[] {0, 2, 5}),
			// scope [2,0]: (0,0), (1,0), (1,1); the first joined key repeats.
			new ExactFiniteSupportJoin.Relation(new int[] {2, 0}, new int[] {0, 2, 3}));
		List<String> first = collect(variables, domains, relations);
		List<String> second = collect(variables, domains, relations);
		Assert.assertEquals(exhaustive(variables, domains, relations), new LinkedHashSet<>(first));
		Assert.assertEquals(first, second);
		Assert.assertEquals(10, first.size()); // five constrained tuples times free variable 3.
	}

	@Test
	public void emptyZeroScopeSingletonAndDisconnectedRelationsAreHandled() {
		List<String> scalar = collect(new int[0], new int[0], List.of(
			new ExactFiniteSupportJoin.Relation(new int[0], new int[] {0})));
		Assert.assertEquals(List.of("[]"), scalar);
		int[] domains = {2, 2, 3};
		int[] variables = {0, 1, 2};
		Assert.assertTrue(collect(variables, domains, List.of(
			new ExactFiniteSupportJoin.Relation(new int[0], new int[0]))).isEmpty());
		List<ExactFiniteSupportJoin.Relation> relations = List.of(
			new ExactFiniteSupportJoin.Relation(new int[0], new int[] {0}),
			new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {1}),
			new ExactFiniteSupportJoin.Relation(new int[] {2}, new int[] {0, 2}));
		List<String> actual = collect(variables, domains, relations);
		Assert.assertEquals(exhaustive(variables, domains, relations), new LinkedHashSet<>(actual));
		Assert.assertEquals(4, actual.size());
	}

	@Test
	public void noRelationsEnumeratesCallerVariableOrderAndAscendingDomains() {
		int[][] firstBuffer = new int[1][];
		List<String> actual = new ArrayList<>();
		ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
			new int[] {1, 0}, new int[] {2, 3}, List.of(), assignment -> {
				if(firstBuffer[0] == null)
					firstBuffer[0] = assignment;
				else
					Assert.assertSame(firstBuffer[0], assignment);
				actual.add(Arrays.toString(assignment));
			});
		Assert.assertEquals(List.of("[0, 0]", "[1, 0]", "[0, 1]", "[1, 1]", "[0, 2]", "[1, 2]"),
			actual);
		Assert.assertEquals(new ExactFiniteSupportJoin.Work(0L, 6L), work);
	}

	@Test
	public void malformedRelationAndBucketContractsFailClosed() {
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactFiniteSupportJoin.forEach(
			new int[] {0}, new int[] {2}, List.of(
				new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {1, 1})), ignored -> { }));
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactFiniteSupportJoin.forEach(
			new int[] {0}, new int[] {2}, List.of(
				new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {2})), ignored -> { }));
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactFiniteSupportJoin.forEach(
			new int[] {0}, new int[] {2, 2}, List.of(
				new ExactFiniteSupportJoin.Relation(new int[] {1}, new int[] {0})), ignored -> { }));
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactFiniteSupportJoin.forEach(
			new int[] {0, 0}, new int[] {2}, List.of(), ignored -> { }));
	}

	@Test
	public void tenThousandUnaryRelationsAndFreeSingletonsDoNotUseCallStackDepth() {
		List<ExactFiniteSupportJoin.Relation> relations = new ArrayList<>();
		for(int index = 0; index < 10_000; index++)
			relations.add(new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {0}));
		int[] domains = new int[10_001];
		Arrays.fill(domains, 1);
		int[] variables = new int[domains.length];
		for(int variable = 0; variable < variables.length; variable++)
			variables[variable] = variable;
		List<String> emitted = new ArrayList<>();
		ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
			variables, domains, relations, assignment -> emitted.add(Arrays.toString(assignment)));
		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(new ExactFiniteSupportJoin.Work(10_000L, 1L), work);
	}

	@Test
	public void relationPermutationsPreserveEqualityStarWithoutCartesianExpansion() {
		int domain = 5;
		int[] domains = {domain, domain, domain, domain};
		int[] variables = {0, 1, 2, 3};
		int[] equality = new int[domain];
		for(int value = 0; value < domain; value++)
			equality[value] = value * domain + value;
		List<ExactFiniteSupportJoin.Relation> base = List.of(
			new ExactFiniteSupportJoin.Relation(new int[] {0, 1}, equality),
			new ExactFiniteSupportJoin.Relation(new int[] {2, 0}, equality),
			new ExactFiniteSupportJoin.Relation(new int[] {0, 3}, equality));
		Set<String> oracle = exhaustive(variables, domains, base);
		for(int[] permutation : List.of(
			new int[] {0, 1, 2}, new int[] {0, 2, 1}, new int[] {1, 0, 2},
			new int[] {1, 2, 0}, new int[] {2, 0, 1}, new int[] {2, 1, 0})) {
			List<ExactFiniteSupportJoin.Relation> relations = Arrays.stream(permutation)
				.mapToObj(base::get).toList();
			List<String> emitted = new ArrayList<>();
			ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
				variables, domains, relations, assignment -> emitted.add(Arrays.toString(assignment)));
			Assert.assertEquals(oracle, new LinkedHashSet<>(emitted));
			Assert.assertEquals("join must emit D, not the D^(k+1) dense product",
				domain, work.emittedAssignments());
			Assert.assertEquals("each equality support row should be visited exactly once per relation",
				(long) domain * relations.size(), work.visitedRelationRows());
		}
	}

	@Test
	public void smallestFiniteSupportIsJoinedFirstRegardlessOfCallerOrder() {
		int domain = 100;
		int[] equality = new int[domain];
		for(int value = 0; value < domain; value++)
			equality[value] = value * domain + value;
		List<ExactFiniteSupportJoin.Relation> broadFirst = List.of(
			new ExactFiniteSupportJoin.Relation(new int[] {0, 1}, equality),
			new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {37}));
		List<String> emitted = new ArrayList<>();
		ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
			new int[] {0, 1}, new int[] {domain, domain}, broadFirst,
			assignment -> emitted.add(Arrays.toString(assignment)));
		Assert.assertEquals(List.of("[37, 37]"), emitted);
		Assert.assertEquals(new ExactFiniteSupportJoin.Work(2L, 1L), work);
	}

	@Test
	public void emptySupportShortCircuitsBeforePreparingLargePartialIndexButAfterValidation() {
		int[] largeSupport = new int[100_000];
		for(int cell = 0; cell < largeSupport.length; cell++)
			largeSupport[cell] = cell;
		List<ExactFiniteSupportJoin.Relation> relations = List.of(
			new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[0]),
			new ExactFiniteSupportJoin.Relation(new int[] {0, 1}, largeSupport));
		List<int[]> emitted = new ArrayList<>();
		ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
			new int[] {0, 1}, new int[] {100, 1_000}, relations, emitted::add);
		Assert.assertTrue(emitted.isEmpty());
		Assert.assertEquals(new ExactFiniteSupportJoin.Work(0L, 0L), work);

		Assert.assertThrows("empty support must not bypass validation of later relations",
			IllegalArgumentException.class, () -> ExactFiniteSupportJoin.forEach(
				new int[] {0}, new int[] {2}, List.of(
					new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[0]),
					new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {2})),
				ignored -> { }));
	}

	@Test
	public void primitiveOpenAddressIndexResolvesCollidingBoundKeysExactly() {
		int domain = 64;
		int left = -1;
		int right = -1;
		for(int candidate = 0; candidate < domain && left < 0; candidate++)
			for(int other = candidate + 1; other < domain; other++)
				if(hashSlot(candidate, 7) == hashSlot(other, 7)) {
					left = candidate;
					right = other;
					break;
				}
		Assert.assertTrue(left >= 0);
		List<ExactFiniteSupportJoin.Relation> relations = List.of(
			new ExactFiniteSupportJoin.Relation(new int[] {0}, new int[] {left, right}),
			new ExactFiniteSupportJoin.Relation(new int[] {0, 1},
				new int[] {left * 2, left * 2 + 1, right * 2, right * 2 + 1}));
		List<String> actual = collect(new int[] {0, 1}, new int[] {domain, 2}, relations);
		Assert.assertEquals(exhaustive(new int[] {0, 1}, new int[] {domain, 2}, relations),
			new LinkedHashSet<>(actual));
		Assert.assertEquals(4, actual.size());
	}

	@Test
	public void millionKeyPartialJoinUsesPrimitiveIndexAndPreservesExactWork() {
		int xDomain = 1_000_000;
		int[] first = new int[500_000];
		for(int row = 0; row < first.length; row++)
			first[row] = row * 4;
		int[] second = new int[2 * xDomain];
		for(int key = 0; key < second.length; key++)
			second[key] = key * 2 + (key & 1);
		List<ExactFiniteSupportJoin.Relation> relations = List.of(
			new ExactFiniteSupportJoin.Relation(new int[] {0, 1}, first),
			new ExactFiniteSupportJoin.Relation(new int[] {0, 1, 2}, second));
		long[] emitted = {0L};
		ExactFiniteSupportJoin.Work work = ExactFiniteSupportJoin.forEach(
			new int[] {0, 1, 2}, new int[] {2, xDomain, 2}, relations,
			assignment -> emitted[0]++);
		Assert.assertEquals(500_000L, emitted[0]);
		Assert.assertEquals(new ExactFiniteSupportJoin.Work(1_000_000L, 500_000L), work);
	}

	private static int hashSlot(int key, int mask) {
		int hash = key * 0x9e3779b9;
		return (hash ^ hash >>> 16) & mask;
	}

	private static List<String> collect(int[] variables, int[] domains,
		List<ExactFiniteSupportJoin.Relation> relations) {
		List<String> result = new ArrayList<>();
		ExactFiniteSupportJoin.forEach(variables, domains, relations,
			assignment -> result.add(Arrays.toString(assignment)));
		return result;
	}

	private static Set<String> exhaustive(int[] variables, int[] domains,
		List<ExactFiniteSupportJoin.Relation> relations) {
		Set<String> result = new LinkedHashSet<>();
		int[] assignment = new int[domains.length];
		exhaustive(0, variables, domains, relations, assignment, result);
		return result;
	}

	private static void exhaustive(int position, int[] variables, int[] domains,
		List<ExactFiniteSupportJoin.Relation> relations, int[] assignment, Set<String> result) {
		if(position == variables.length) {
			for(var relation : relations)
				if(Arrays.binarySearch(relation.finiteCells(), flatCell(
					relation.scope(), domains, assignment)) < 0)
					return;
			result.add(Arrays.toString(assignment));
			return;
		}
		int variable = variables[position];
		for(int value = 0; value < domains[variable]; value++) {
			assignment[variable] = value;
			exhaustive(position + 1, variables, domains, relations, assignment, result);
		}
	}

	private static int flatCell(int[] scope, int[] domains, int[] assignment) {
		int cell = 0;
		for(int variable : scope)
			cell = cell * domains[variable] + assignment[variable];
		return cell;
	}

	private static int[] permutation(int size, Random random) {
		int[] result = new int[size];
		for(int index = 0; index < size; index++)
			result[index] = index;
		for(int index = size - 1; index > 0; index--) {
			int other = random.nextInt(index + 1);
			int value = result[index];
			result[index] = result[other];
			result[other] = value;
		}
		return result;
	}
}
