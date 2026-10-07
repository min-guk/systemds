/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/** Differential contract for exact finite-support boundary-message merging. */
public class BoundarySupportMergeTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(2_000_000L, 10_000_000L);
	private static final double INF = Double.POSITIVE_INFINITY;

	@After
	public void clearAblation() {
		System.clearProperty(PruningAblation.PROPERTY);
	}

	@Test
	public void correlatedSparseSupportsMatchCartesianForNonsortedBoundary() {
		var a = variable("sparse-a", 3);
		var b = variable("sparse-b", 4);
		var c = variable("sparse-c", 3);
		var d = variable("sparse-d", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, d);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			factor(List.of(b, a), 4, 3, cell -> cell[0] == cell[1] ? .1 * (cell[0] + 1) : INF),
			factor(List.of(c, b), 3, 4, cell -> cell[0] == cell[1] ? .01 * (cell[0] + 1) : INF),
			factor(List.of(d, c, a), 2, 3, 3,
				cell -> cell[1] == cell[2] && cell[0] == (cell[1] & 1) ? .001 : INF));
		assertParity(variables, factors, List.of(d, a));
	}

	@Test
	public void randomizedScopesOrdersDecimalsAndMagnitudeExtremesMatchCartesian() {
		Random random = new Random(7_102_026L);
		for(int trial = 0; trial < 80; trial++) {
			int count = 1 + random.nextInt(4);
			List<ExactCategoricalSolver.Variable> core = new ArrayList<>();
			for(int index = 0; index < count; index++)
				core.add(variable("random-" + trial + '-' + index, 1 + random.nextInt(3)));
			var padA = variable("random-pad-a-" + trial, 5);
			var padB = variable("random-pad-b-" + trial, 5);
			var padC = variable("random-pad-c-" + trial, 3);
			List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(core);
			variables.addAll(List.of(padA, padB, padC));
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			int factorCount = 1 + random.nextInt(5);
			for(int ordinal = 0; ordinal < factorCount; ordinal++) {
				List<ExactCategoricalSolver.Variable> shuffled = new ArrayList<>(core);
				Collections.shuffle(shuffled, random);
				List<ExactCategoricalSolver.Variable> scope = List.copyOf(
					shuffled.subList(0, random.nextInt(count + 1)));
				int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
					.reduce(1, Math::multiplyExact);
				double[] values = new double[cells];
				for(int cell = 0; cell < cells; cell++) {
					int selector = random.nextInt(12);
					values[cell] = selector < 4 ? INF : selector == 4 ? Double.MIN_VALUE
						: selector == 5 ? 1.0e16 : random.nextInt(31) / 10.0;
				}
				factors.add(ExactCategoricalSolver.Factor.dense(scope, values));
			}
			for(ExactCategoricalSolver.Variable variable : core) {
				double[] zero = new double[variable.domainSize()];
				factors.add(ExactCategoricalSolver.Factor.dense(List.of(variable), zero));
			}
			double[] sparsePad = new double[75];
			Arrays.fill(sparsePad, INF);
			sparsePad[0] = 0d;
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(padC, padA, padB), sparsePad));
			Collections.shuffle(factors, random);
			List<ExactCategoricalSolver.Variable> boundary = new ArrayList<>(core);
			Collections.shuffle(boundary, random);
			boundary = List.copyOf(boundary.subList(0, random.nextInt(count + 1)));
			assertParity(variables, factors, boundary);
		}
	}

	@Test
	public void reorderedTieRelationsKeepSmallestCanonicalUnionAssignment() {
		var a = variable("tie-a", 5);
		var b = variable("tie-b", 5);
		var c = variable("tie-c", 5);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		double[] ba = new double[25];
		double[] cb = new double[25];
		Arrays.fill(ba, INF);
		Arrays.fill(cb, INF);
		ba[4] = 0d; // First [b,a] row is (0,4), canonical union cell 100.
		ba[5] = 0d; // Later row (1,0) is canonical union cell 5 and must win.
		cb[0] = 0d;
		cb[1] = 0d;
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(b, a), ba),
			ExactCategoricalSolver.Factor.dense(List.of(c, b), cb));
		var baseline = merge("baseline", variables, factors, List.of(c), null);
		var joined = merge("local_only", variables, factors, List.of(c), null);
		assertSnapshotEquals(snapshot(baseline, variables), snapshot(joined, variables));
		int[] assignment = {-1, -1, 0};
		joined.decodeInto(assignment, variables);
		Assert.assertArrayEquals(new int[] {0, 1, 0}, assignment);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> joined.decodeInto(new int[] {-1, -1, 1}, variables));
	}

	@Test
	public void mergedResidueChildAndNonabsorbingInfinityLowerBoundMatchCartesian()
		throws Exception {
		var x = variable("lower-x", 2);
		var padA = variable("lower-pad-a", 5);
		var padB = variable("lower-pad-b", 7);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, padA, padB);
		var baselineResidue = residueChild("baseline", variables, x);
		var joinedResidue = residueChild("local_only", variables, x);
		Assert.assertNotEquals(Double.doubleToRawLongBits(baselineResidue.minimum()),
			Double.doubleToRawLongBits(baselineResidue.lowerBound()));
		double[] sparse = new double[70];
		Arrays.fill(sparse, INF);
		sparse[0] = 0d;
		sparse[35] = 0d;
		List<ExactCategoricalSolver.Factor> outer = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, padA, padB), sparse));
		assertMessageParity(variables, List.of(baselineResidue,
			ExactCategoricalSolver.boundaryLeaf(variables, outer.get(0), LIMITS)),
			List.of(joinedResidue,
				ExactCategoricalSolver.boundaryLeaf(variables, outer.get(0), LIMITS)), List.of());

		var guarded = lowerFiniteInfinityMessage(variables, x);
		var sparseLeaf = ExactCategoricalSolver.boundaryLeaf(variables, outer.get(0), LIMITS);
		var baseline = mergeMessages("baseline", List.of(guarded, sparseLeaf), List.of());
		var joined = mergeMessages("local_only", List.of(guarded, sparseLeaf), List.of());
		assertSnapshotEquals(snapshot(baseline, variables), snapshot(joined, variables));
		Assert.assertEquals(Double.doubleToRawLongBits(7d),
			Double.doubleToRawLongBits(joined.lowerBound()));
		Assert.assertEquals(10d, joined.minimum(), 0d);
	}

	@Test
	public void unsafeOverflowIsNotHiddenByARejectedSparseRelation() {
		var a = variable("overflow-a", 5);
		var b = variable("overflow-b", 5);
		var c = variable("overflow-c", 5);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		double[] nearMax = {Double.MAX_VALUE, INF, INF, INF, INF};
		double[] impossible = {INF, INF, INF, INF, INF};
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), nearMax),
			ExactCategoricalSolver.Factor.dense(List.of(b), nearMax),
			ExactCategoricalSolver.Factor.dense(List.of(c), impossible));
		IllegalArgumentException baseline = Assert.assertThrows(IllegalArgumentException.class,
			() -> merge("baseline", variables, factors, List.of(), null));
		IllegalArgumentException joined = Assert.assertThrows(IllegalArgumentException.class,
			() -> merge("local_only", variables, factors, List.of(), null));
		Assert.assertEquals(baseline.getMessage(), joined.getMessage());
		Assert.assertTrue(joined.getMessage().startsWith("EXACT_VE_OBJECTIVE_OVERFLOW"));
	}

	@Test
	public void singletonAndZeroScopeMessagesMatchCartesian() {
		var singleton = variable("singleton", 1);
		var x = variable("zero-scope-x", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(singleton, x);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(), .1),
			ExactCategoricalSolver.Factor.dense(List.of(singleton), .2),
			ExactCategoricalSolver.Factor.dense(List.of(x), .3, INF, .4));
		assertParity(variables, factors, List.of());
		assertParity(variables, factors, List.of(singleton));
		assertParity(variables, factors, List.of(x, singleton));
	}

	@Test
	public void sparseJoinAvoidsDenseUnionChildReads() {
		int domain = 12;
		var x = variable("work-x", domain);
		var y = variable("work-y", domain);
		var z = variable("work-z", domain);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y, z);
		double[] equality = new double[domain * domain];
		Arrays.fill(equality, INF);
		for(int value = 0; value < domain; value++)
			equality[value * domain + value] = value / 10.0;
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(y, x), equality),
			ExactCategoricalSolver.Factor.dense(List.of(z, y), equality),
			ExactCategoricalSolver.Factor.dense(List.of(x, z), equality));
		var baselineCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var joinedCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var baseline = merge("baseline", variables, factors, List.of(), baselineCounters);
		var joined = merge("local_only", variables, factors, List.of(), joinedCounters);
		assertSnapshotEquals(snapshot(baseline, variables), snapshot(joined, variables));
		Assert.assertEquals(3L * domain * domain * domain, baselineCounters.childEvaluations());
		Assert.assertTrue("finite-support join should avoid more than 90% of dense child reads: joined="
			+ joinedCounters.childEvaluations() + ", baseline=" + baselineCounters.childEvaluations(),
			joinedCounters.childEvaluations() * 10 < baselineCounters.childEvaluations());
	}

	@Test
	public void identicalProfilesShareEvaluationButKeepEveryOutputAndBacktrace() {
		var x = variable("profile-x", 12);
		var y = variable("profile-y", 8);
		var z = variable("profile-z", 5);
		var variables = List.of(x,y,z);
		var factors = List.of(
			factor(List.of(x,y),12,8,a -> a[0] % 3 + (a[1] % 2) * .1),
			factor(List.of(y,z),8,5,a -> a[0] % 2 + (a[1] % 2) * .01));
		var before = new ExactCategoricalSolver.BoundaryMergeCounters();
		var after = new ExactCategoricalSolver.BoundaryMergeCounters();
		var baseline = merge("baseline",variables,factors,List.of(z,x),before);
		var joined = merge("local_only",variables,factors,List.of(z,x),after);
		assertSnapshotEquals(snapshot(baseline,variables),snapshot(joined,variables));
		Assert.assertTrue("response-equivalent values should share arithmetic",
			after.childEvaluations() * 10 < before.childEvaluations());
		Assert.assertTrue("response-equivalent output rows should share numeric storage",
			joined.retainedCells() * 5 < baseline.retainedCells());
	}

	@Test
	public void compressedChildCanSplitClassesInSparseAndDenseParents() {
		var x = variable("split-x", 12);
		var y = variable("split-y", 8);
		var z = variable("split-z", 5);
		var w = variable("split-w", 7);
		var variables = List.of(x, y, z, w);
		var factors = List.of(
			factor(List.of(x, y), 12, 8, a -> a[0] % 3 + (a[1] % 2) * .1),
			factor(List.of(y, z), 8, 5, a -> a[0] % 2 + (a[1] % 2) * .01));
		var baselineChild = merge("baseline", variables, factors, List.of(z, x), null);
		var compressedChild = merge("local_only", variables, factors, List.of(z, x), null);
		Assert.assertTrue(compressedChild.retainedCells() < baselineChild.retainedCells());
		var splitting = ExactCategoricalSolver.boundaryLeaf(variables,
			factor(List.of(w, x), 7, 12, a -> a[0] == a[1] % 7 ? a[1] * .01 : INF), LIMITS);
		var baseline = mergeMessages("baseline", List.of(baselineChild, splitting), List.of(x, z));
		// The parent distinguishes all x values, including values that shared child storage.
		for(String variant : List.of("local_only", "baseline")) {
			var parent = mergeMessages(variant, List.of(compressedChild, splitting), List.of(x, z));
			assertSnapshotEquals(snapshot(baseline, variables), snapshot(parent, variables));
		}
		var denseInternalBaseline = mergeMessages("baseline",
			List.of(baselineChild, splitting), List.of(z));
		var denseInternalCompressed = mergeMessages("baseline",
			List.of(compressedChild, splitting), List.of(z));
		assertSnapshotEquals(snapshot(denseInternalBaseline, variables),
			snapshot(denseInternalCompressed, variables));
	}

	@Test
	public void singletonProjectionPreservesCompressedCoordinatesAndNestedWitnesses() {
		var x = variable("alias-x", 12);
		var y = variable("alias-y", 8);
		var singleton = variable("alias-singleton", 1);
		var variables = List.of(x, y, singleton);
		var factors = List.of(
			factor(List.of(x, y), 12, 8, a -> a[0] % 3 + (a[1] % 2) * .1),
			ExactCategoricalSolver.Factor.dense(List.of(singleton), .2));
		var baseline = ExactCategoricalSolver.projectSingletons(
			merge("baseline", variables, factors, List.of(singleton, x), null));
		var compressed = ExactCategoricalSolver.projectSingletons(
			merge("local_only", variables, factors, List.of(singleton, x), null));
		assertSnapshotEquals(snapshot(baseline, variables), snapshot(compressed, variables));
		Assert.assertEquals(List.of(x), compressed.scope());
		var split = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(x),
				.2, .4, .8, .1, .3, .7, .5, .6, .9, .2, .5, .1), LIMITS);
		assertMessageParity(variables, List.of(baseline, split), List.of(compressed, split), List.of());
	}

	@Test
	public void profileClassesDistinguishLowerBoundsAndHiddenResidues() throws Exception {
		var x = variable("profile-residue", 10);
		var y = variable("profile-padding", 8);
		var variables = List.of(x,y);
		double[] high = new double[10], low = new double[10], lower = new double[10];
		Arrays.fill(high,1e16);
		for(int value = 0; value < 10; value++) {
			low[value] = value % 2;
			lower[value] = value % 3 == 0 ? Math.nextDown(1e16) : 1e16;
		}
		Constructor<?> constructor = ExactCategoricalSolver.BoundaryMessage.class.getDeclaredConstructor(
			List.class,int[].class,List.class,int[].class,double[].class,double[].class,
			double[].class,List.class,int[].class,int[].class,long.class,long.class);
		constructor.setAccessible(true);
		var input = (ExactCategoricalSolver.BoundaryMessage)constructor.newInstance(
			variables,new int[]{10,8},List.of(x),new int[]{0},high,low,lower,List.of(),null,null,0L,10L);
		var padding = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(y),1,1,1,1,1,1,1,1),LIMITS);
		assertMessageParity(variables,List.of(input,padding),List.of(input,padding),List.of(x));
	}

	@Test
	public void equalOutputCostsRetainActualBoundaryForNestedWitnesses() {
		var x = variable("witness-x",10);
		var z = variable("witness-z",10);
		var padding = variable("witness-padding",8);
		var variables = List.of(x,z,padding);
		var equality = factor(List.of(x,z),10,10,a -> a[0] == a[1] ? 0d : INF);
		var beforeChild = merge("baseline",variables,List.of(equality),List.of(x),null);
		var afterChild = merge("local_only",variables,List.of(equality),List.of(x),null);
		var zero = ExactCategoricalSolver.boundaryLeaf(variables,
			ExactCategoricalSolver.Factor.dense(List.of(padding),0,0,0,0,0,0,0,0),LIMITS);
		assertMessageParity(variables,List.of(beforeChild,zero),List.of(afterChild,zero),List.of(x));
		var output = mergeMessages("local_only",List.of(afterChild,zero),List.of(x));
		int[] assignment = {9,-1,-1};
		output.decodeInto(assignment,variables);
		Assert.assertArrayEquals(new int[]{9,9,0},assignment);
	}

	@Test
	public void repeatedProfilesKeepOriginalBoundaryResourceLimit() {
		var x = variable("limit-x",10);
		var y = variable("limit-y",8);
		var variables = List.of(x,y);
		var leaves = ExactCategoricalSolver.boundaryLeaves(variables,List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x),new double[10]),
			ExactCategoricalSolver.Factor.dense(List.of(y),new double[8])),LIMITS);
		var limits = new ExactCategoricalSolver.Limits(16,128);
		System.setProperty(PruningAblation.PROPERTY,"baseline");
		var before = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.mergeBoundary(leaves,variables,limits));
		System.setProperty(PruningAblation.PROPERTY,"local_only");
		var after = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.mergeBoundary(leaves,variables,limits));
		Assert.assertEquals(before.getMessage(),after.getMessage());
		Assert.assertTrue(after.getMessage().contains("kind=factor-cells"));
	}

	private static void assertParity(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		List<ExactCategoricalSolver.Variable> boundary) {
		var baseline = merge("baseline", variables, factors, boundary, null);
		var joined = merge("local_only", variables, factors, boundary, null);
		assertSnapshotEquals(snapshot(baseline, variables), snapshot(joined, variables));
	}

	private static void assertMessageParity(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.BoundaryMessage> baselineInputs,
		List<ExactCategoricalSolver.BoundaryMessage> joinedInputs,
		List<ExactCategoricalSolver.Variable> boundary) {
		var baseline = mergeMessages("baseline", baselineInputs, boundary);
		var joined = mergeMessages("local_only", joinedInputs, boundary);
		assertSnapshotEquals(snapshot(baseline, variables), snapshot(joined, variables));
	}

	private static ExactCategoricalSolver.BoundaryMessage merge(String variant,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		List<ExactCategoricalSolver.Variable> boundary,
		ExactCategoricalSolver.BoundaryMergeCounters counters) {
		System.setProperty(PruningAblation.PROPERTY, variant);
		return ExactCategoricalSolver.mergeBoundary(
			ExactCategoricalSolver.boundaryLeaves(variables, factors, LIMITS),
			boundary, LIMITS, counters);
	}

	private static ExactCategoricalSolver.BoundaryMessage mergeMessages(String variant,
		List<ExactCategoricalSolver.BoundaryMessage> inputs,
		List<ExactCategoricalSolver.Variable> boundary) {
		System.setProperty(PruningAblation.PROPERTY, variant);
		return ExactCategoricalSolver.mergeBoundary(inputs, boundary, LIMITS);
	}

	private static ExactCategoricalSolver.BoundaryMessage residueChild(String variant,
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable x) {
		return merge(variant, variables, List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), 1.0e16, 1.0e16),
			ExactCategoricalSolver.Factor.dense(List.of(x), 1d, 1d),
			ExactCategoricalSolver.Factor.dense(List.of(x), 1d, 1d)), List.of(x), null);
	}

	private static ExactCategoricalSolver.BoundaryMessage lowerFiniteInfinityMessage(
		List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable x) throws Exception {
		Constructor<?> constructor = ExactCategoricalSolver.BoundaryMessage.class.getDeclaredConstructor(
			List.class, int[].class, List.class, int[].class, double[].class, double[].class,
			double[].class, List.class, int[].class, int[].class, long.class, long.class);
		constructor.setAccessible(true);
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		return (ExactCategoricalSolver.BoundaryMessage)constructor.newInstance(
			variables, domains, List.of(x), new int[] {0},
			new double[] {INF, 10d}, null, new double[] {7d, 10d},
			List.of(), null, null, 0L, 2L);
	}

	private static Snapshot snapshot(ExactCategoricalSolver.BoundaryMessage message,
		List<ExactCategoricalSolver.Variable> variables) {
		List<ExactCategoricalSolver.Variable> scope = message.scope();
		int cells = Math.toIntExact(message.cells());
		List<Cell> values = new ArrayList<>(cells);
		int[] local = new int[scope.size()];
		for(int cell = 0; cell < cells; cell++) {
			decode(cell, scope, local);
			int[] assignment = new int[variables.size()];
			Arrays.fill(assignment, -1);
			for(int position = 0; position < scope.size(); position++)
				assignment[variables.indexOf(scope.get(position))] = local[position];
			double value = message.valueForAssignment(assignment, variables);
			int[] decoded = null;
			try {
				message.decodeInto(assignment, variables);
				decoded = assignment.clone();
			}
			catch(IllegalArgumentException expected) {
				Assert.assertEquals(INF, value, 0d);
			}
			values.add(new Cell(bits(value), decoded));
		}
		List<long[]> marginals = new ArrayList<>();
		List<long[]> lowerMarginals = new ArrayList<>();
		for(ExactCategoricalSolver.Variable variable : scope) {
			marginals.add(bits(message.minMarginals(variable)));
			lowerMarginals.add(bits(message.lowerMinMarginals(variable)));
		}
		return new Snapshot(scope.stream().map(ExactCategoricalSolver.Variable::key).toList(),
			bits(message.minimum()), bits(message.lowerBound()), values,
			marginals, lowerMarginals, rawCosts(message), message.cells(), message.retainedCells(), message.assignments());
	}

	private static List<long[]> rawCosts(ExactCategoricalSolver.BoundaryMessage message) {
		List<long[]> result = new ArrayList<>();
		try {
			var indexMethod = ExactCategoricalSolver.BoundaryMessage.class
				.getDeclaredMethod("boundaryCell",int[].class);
			indexMethod.setAccessible(true);
			var variablesField = ExactCategoricalSolver.BoundaryMessage.class.getDeclaredField("variables");
			variablesField.setAccessible(true);
			@SuppressWarnings("unchecked")
			var variables = (List<ExactCategoricalSolver.Variable>)variablesField.get(message);
			int[] local = new int[message.scope().size()];
			int[] assignment = new int[variables.size()];
			for(String name : List.of("values","lowValues","lowerValues")) {
				var field = ExactCategoricalSolver.BoundaryMessage.class.getDeclaredField(name);
				field.setAccessible(true);
				double[] values = (double[])field.get(message);
				long[] logical = new long[Math.toIntExact(message.cells())];
				for(int cell = 0; values != null && cell < logical.length; cell++) {
					decode(cell,message.scope(),local);
					for(int axis = 0; axis < local.length; axis++)
						assignment[variables.indexOf(message.scope().get(axis))] = local[axis];
					logical[cell] = bits(values[(int)indexMethod.invoke(message,(Object)assignment)]);
				}
				result.add(logical);
			}
		}
		catch(ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
		return result;
	}

	private static void assertSnapshotEquals(Snapshot expected, Snapshot actual) {
		Assert.assertEquals(expected.scope(), actual.scope());
		Assert.assertEquals(expected.minimum(), actual.minimum());
		Assert.assertEquals(expected.lowerBound(), actual.lowerBound());
		Assert.assertEquals(expected.cells(), actual.cells());
		Assert.assertTrue(actual.retainedCells() <= expected.retainedCells());
		Assert.assertEquals(expected.assignments(), actual.assignments());
		Assert.assertEquals(expected.values(), actual.values());
		for(int index = 0; index < expected.rawCosts().size(); index++)
			Assert.assertArrayEquals(expected.rawCosts().get(index),actual.rawCosts().get(index));
		Assert.assertEquals(expected.marginals().size(), actual.marginals().size());
		for(int index = 0; index < expected.marginals().size(); index++) {
			Assert.assertArrayEquals(expected.marginals().get(index), actual.marginals().get(index));
			Assert.assertArrayEquals(expected.lowerMarginals().get(index),
				actual.lowerMarginals().get(index));
		}
	}

	private static ExactCategoricalSolver.Factor factor(
		List<ExactCategoricalSolver.Variable> scope, int first, int second,
		CellCost cost) {
		return factor(scope, new int[] {first, second}, cost);
	}

	private static ExactCategoricalSolver.Factor factor(
		List<ExactCategoricalSolver.Variable> scope, int first, int second, int third,
		CellCost cost) {
		return factor(scope, new int[] {first, second, third}, cost);
	}

	private static ExactCategoricalSolver.Factor factor(
		List<ExactCategoricalSolver.Variable> scope, int[] domains, CellCost cost) {
		int cells = Arrays.stream(domains).reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		int[] assignment = new int[domains.length];
		for(int cell = 0; cell < cells; cell++) {
			int residual = cell;
			for(int position = domains.length - 1; position >= 0; position--) {
				assignment[position] = residual % domains[position];
				residual /= domains[position];
			}
			values[cell] = cost.value(assignment);
		}
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static void decode(int cell, List<ExactCategoricalSolver.Variable> scope, int[] values) {
		for(int position = scope.size() - 1; position >= 0; position--) {
			values[position] = cell % scope.get(position).domainSize();
			cell /= scope.get(position).domainSize();
		}
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private static long[] bits(double[] values) {
		return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	@FunctionalInterface
	private interface CellCost {
		double value(int[] assignment);
	}

	private record Cell(long value, int[] assignment) {
		@Override
		public boolean equals(Object other) {
			return other instanceof Cell that && value == that.value
				&& Arrays.equals(assignment, that.assignment);
		}

		@Override
		public int hashCode() {
			return 31 * Long.hashCode(value) + Arrays.hashCode(assignment);
		}
	}

	private record Snapshot(List<String> scope, long minimum, long lowerBound,
		List<Cell> values, List<long[]> marginals, List<long[]> lowerMarginals,
		List<long[]> rawCosts, long cells, long retainedCells, long assignments) { }
}
