/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ExactNativeLocalSourceProjectionTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000, 1_000_000);

	@Test
	public void everyOriginalAssignmentHasExactlyOneBitExactCompletion() {
		var source = new ExactCategoricalSolver.Variable("source", 6);
		var target = new ExactCategoricalSolver.Variable("target", 4);
		int[] classes = {0, 1, 0, 2, 1, 2};
		double[][] prices = {{0d, 1d, 0.1, 1e100}, {3d, 0d, 0.2, 1e-100}, {4d, 3d, 0d, 0.3}};
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(source, target),
			values -> prices[classes[values[0]]][values[1]]);
		var encoded = ExactPhysicalCostModel.projectNativeLocalSource("projection", canonical, classes);
		Assert.assertEquals(1, encoded.auxiliaryVariables().size());
		Assert.assertEquals(3, encoded.auxiliaryVariables().get(0).domainSize());
		for(int s = 0; s < source.domainSize(); s++)
			for(int t = 0; t < target.domainSize(); t++) {
				int completions = 0;
				for(int c = 0; c < 3; c++) {
					double relation = encoded.factors().get(0).cost(new int[] {s, c});
					if(!Double.isFinite(relation))
						continue;
					completions++;
					Assert.assertEquals(classes[s], c);
					Assert.assertEquals(Double.doubleToRawLongBits(canonical.cost(new int[] {s, t})),
						Double.doubleToRawLongBits(encoded.factors().get(1).cost(new int[] {c, t})));
				}
				Assert.assertEquals(1, completions);
			}
	}

	@Test
	public void conditionalOptimumAndOriginalTieArePreserved() {
		var source = new ExactCategoricalSolver.Variable("source", 5);
		var target = new ExactCategoricalSolver.Variable("target", 3);
		int[] classes = {0, 1, 0, 1, 0};
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(source, target),
			values -> classes[values[0]] == values[1] ? 0d : 3d);
		var encoded = ExactPhysicalCostModel.projectNativeLocalSource("ties", canonical, classes);
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(List.of(source, target));
		augmented.addAll(encoded.auxiliaryVariables());
		for(int fixedSource = -1; fixedSource < source.domainSize(); fixedSource++)
			for(int fixedTarget = -1; fixedTarget < target.domainSize(); fixedTarget++) {
				List<ExactCategoricalSolver.Factor> originalFactors = new ArrayList<>(List.of(canonical));
				List<ExactCategoricalSolver.Factor> encodedFactors = new ArrayList<>(encoded.factors());
				if(fixedSource >= 0) {
					originalFactors.add(fixed(source, fixedSource));
					encodedFactors.add(fixed(source, fixedSource));
				}
				if(fixedTarget >= 0) {
					originalFactors.add(fixed(target, fixedTarget));
					encodedFactors.add(fixed(target, fixedTarget));
				}
				ExactCategoricalSolver.TieCostFunction tie = (variable, value) ->
					variable == source ? 10L * value : variable == target ? value : 0L;
				var expected = ExactCategoricalSolver.solve(List.of(source, target), originalFactors, LIMITS, tie);
				var actual = ExactCategoricalSolver.solve(augmented, encodedFactors, LIMITS, tie);
				Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
					Double.doubleToRawLongBits(actual.objective()));
				Assert.assertEquals(expected.assignmentInVariableOrder(),
					actual.assignmentInVariableOrder().subList(0, 2));
			}
	}

	@Test
	public void aggregatePreflightStillPrecedesEveryProjectedCostCallback() {
		var source = new ExactCategoricalSolver.Variable("source", 6);
		var target = new ExactCategoricalSolver.Variable("target", 4);
		AtomicInteger calls = new AtomicInteger();
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(source, target), values -> {
			calls.incrementAndGet();
			return 1d;
		});
		var encoded = ExactPhysicalCostModel.projectNativeLocalSource("preflight", canonical,
			new int[] {0, 1, 0, 1, 0, 1});
		Assert.assertEquals(0, calls.get());
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(List.of(source, target));
		variables.addAll(encoded.auxiliaryVariables());
		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(variables, List.of(),
				encoded.factors(), encoded.ordinaryFactors(), new ExactCategoricalSolver.Limits(100, 19)));
		Assert.assertEquals(0, calls.get());
		var frozen = ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(variables, List.of(),
			encoded.factors(), encoded.ordinaryFactors(), new ExactCategoricalSolver.Limits(100, 20));
		Assert.assertEquals(8, calls.get());
		Assert.assertEquals(1, frozen.size());
	}

	@Test
	public void representativeOrderAndFirstInvalidCostMatchOriginalFirstOccurrences() {
		var source = new ExactCategoricalSolver.Variable("source", 5);
		var target = new ExactCategoricalSolver.Variable("target", 3);
		List<String> calls = new ArrayList<>();
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(source, target), values -> {
			calls.add(values[0] + ":" + values[1]);
			return values[0] == 2 && values[1] == 1 ? Double.NaN : 1d;
		});
		var encoded = ExactPhysicalCostModel.projectNativeLocalSource("order", canonical,
			new int[] {0, 0, 1, 2, 1});
		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactCategoricalSolver.freezeValidatedFactor(encoded.ordinaryFactors().get(0)));
		Assert.assertEquals(List.of("0:0", "0:1", "0:2", "2:0", "2:1"), calls);
	}

	@Test
	public void sourceMapIsDefensiveAndDescriptorBindsIt() {
		var source = new ExactCategoricalSolver.Variable("source", 4);
		var target = new ExactCategoricalSolver.Variable("target", 2);
		var canonical = ExactCategoricalSolver.Factor.lazy(List.of(source, target), values -> 1d);
		int[] map = {0, 1, 0, 1};
		var encoded = ExactPhysicalCostModel.projectNativeLocalSource("descriptor", canonical, map);
		map[2] = 1;
		Assert.assertEquals(0d, encoded.factors().get(0).cost(new int[] {2, 0}), 0d);
		Assert.assertNotEquals(encoded.semanticDescriptor(),
			ExactPhysicalCostModel.projectNativeLocalSource("descriptor", canonical, map).semanticDescriptor());
		Assert.assertTrue(encoded.semanticDescriptor().contains("logicalCells=8"));
		Assert.assertTrue(encoded.semanticDescriptor().contains("encodedCells=12"));
		for(int[] invalid : List.of(new int[] {0}, new int[] {0, -1, 0, 1},
			new int[] {1, 0, 1, 0}, new int[] {0, 2, 0, 2}))
			Assert.assertThrows(IllegalArgumentException.class, () ->
				ExactPhysicalCostModel.projectNativeLocalSource("invalid", canonical, invalid));
	}

	@Test
	public void publishedCanonicalViewReadsTheFrozenCostSnapshot() {
		var source = new ExactCategoricalSolver.Variable("source", 4);
		var target = new ExactCategoricalSolver.Variable("target", 3);
		AtomicInteger generation = new AtomicInteger(1);
		int[] classes = {0, 1, 0, 1};
		var original = ExactCategoricalSolver.Factor.lazy(List.of(source, target),
			values -> generation.get() * (1d + classes[values[0]] + values[1]));
		var encoded = ExactPhysicalCostModel.projectNativeLocalSource("snapshot", original, classes);
		var prices = encoded.ordinaryFactors().get(0);
		var frozen = new IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor>();
		frozen.put(prices, ExactCategoricalSolver.freezeValidatedFactor(prices));
		var published = encoded.canonicalFactorAfterFreeze(original, frozen);
		generation.set(17);
		for(int s = 0; s < source.domainSize(); s++)
			for(int t = 0; t < target.domainSize(); t++)
				Assert.assertEquals(Double.doubleToRawLongBits(1d + classes[s] + t),
					Double.doubleToRawLongBits(published.cost(new int[] {s, t})));
	}

	@Test
	public void productionClassesAndSurfaceWiringPreserveEveryOriginalNativeLocalCost() throws Exception {
		var model = ExactPhysicalModel.build(ExactInputAuthorityOptimizationTest.logregAnalysis());
		var projections = ExactPhysicalCostModel.nativeLocalSourceProjectionsForTest(model);
		Assert.assertFalse("real native-local source observations must reduce stored cells", projections.isEmpty());
		long logicalCells = 0;
		long encodedCells = 0;
		for(var entry : projections.entrySet()) {
			var original = entry.getKey();
			var encoded = entry.getValue();
			var frozen = new IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor>();
			var prices = encoded.ordinaryFactors().get(0);
			frozen.put(prices, ExactCategoricalSolver.freezeValidatedFactor(prices));
			var projected = encoded.canonicalFactorAfterFreeze(original, frozen);
			int sources = original.scope().get(0).domainSize();
			int targets = original.scope().get(1).domainSize();
			logicalCells += (long) sources * targets;
			encodedCells += (long) encoded.auxiliaryVariables().get(0).domainSize() * (sources + targets);
			for(int s = 0; s < sources; s++)
				for(int t = 0; t < targets; t++) {
					int[] values = {s, t};
					Assert.assertEquals(Double.doubleToRawLongBits(original.cost(values)),
						Double.doubleToRawLongBits(projected.cost(values)));
				}
		}
		Assert.assertTrue(encodedCells < logicalCells);
		var surface = ExactPhysicalCostModel.physicalCostSurface(model.analysis(), model);
		long published = surface.exactSolverVariables().stream()
			.filter(v -> v.key().startsWith("exact-native-local|")).count();
		Assert.assertEquals(projections.size(), published);
		System.out.println("EXACT_NATIVE_LOCAL_PROJECTION_EVIDENCE|factors=" + published
			+ "|logicalCells=" + logicalCells + "|encodedCells=" + encodedCells);
	}

	private static ExactCategoricalSolver.Factor fixed(ExactCategoricalSolver.Variable variable, int value) {
		return ExactCategoricalSolver.Factor.lazy(List.of(variable),
			values -> values[0] == value ? 0d : Double.POSITIVE_INFINITY);
	}
}
