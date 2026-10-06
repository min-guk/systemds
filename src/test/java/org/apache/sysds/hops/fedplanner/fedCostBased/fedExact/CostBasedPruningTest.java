/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Exact quotient and support behavior used by cost-based root preparation. */
public class CostBasedPruningTest {
	private static final Limits LIMITS = new Limits(100000, 1000000);
	private static final double INF = Double.POSITIVE_INFINITY;

	@Test
	public void quotientMergesOnlyIdenticalFiniteUnaryValues() {
		var x = new Variable("x", 4);
		var variables = List.of(x);
		var factors = List.of(Factor.dense(variables, 10, 7, 7, INF));
		var root = ExactPhysicalReducedSolver.reducedModel(1, variables, factors, LIMITS);

		assertEquals(2, root.variables().get(0).domainSize());
		assertEquals(0, root.sourceValue(0, 0));
		assertEquals(1, root.sourceValue(0, 1));
		assertEquals(0, root.reducedValue(0, 0));
		assertEquals(1, root.reducedValue(0, 1));
		assertEquals(1, root.reducedValue(0, 2));
		assertEquals(-1, root.reducedValue(0, 3));
		assertOptimum(variables, factors, root);
	}

	@Test
	public void differentSharedChoicesRemainWhenOwnCostIsHigher() {
		var x = new Variable("same-exec-output", 2);
		var shared = new Variable("shared-child-or-loop-boundary", 2);
		var variables = List.of(x, shared);
		var factors = List.of(Factor.dense(List.of(x), 4, 6),
			Factor.dense(variables, 0, INF, INF, 0),
			Factor.dense(List.of(shared), 100, 1));
		var root = ExactPhysicalReducedSolver.reducedModel(2, variables, factors, LIMITS);

		assertEquals(2, root.variables().get(0).domainSize());
		assertOptimum(variables, factors, root);
		assertEquals(List.of(1, 1), root.expandAssignment(ExactCategoricalSolver.solve(
			root.variables(), root.factors(), LIMITS).assignmentInVariableOrder()));
	}

	@Test
	public void roundedButDistinctUnaryProfilesRemainSeparate() {
		var x = new Variable("rounding-sensitive", 2);
		var factors = List.of(Factor.dense(List.of(x), 1e16, 1e16),
			Factor.dense(List.of(x), 1, 0));
		var root = ExactPhysicalReducedSolver.reducedModel(1, List.of(x), factors, LIMITS);

		assertEquals(2, root.variables().get(0).domainSize());
		assertOptimum(List.of(x), factors, root);
	}

	@Test
	public void higherArityFactorDoesNotPerformAdditionalSupportPruning() {
		var x = new Variable("x", 2);
		var y = new Variable("y", 2);
		var z = new Variable("z", 2);
		var variables = List.of(x, y, z);
		var factors = List.of(Factor.dense(variables, INF, INF, INF, INF, 1, 2, 3, 4));
		var root = ExactPhysicalReducedSolver.reducedModel(0, variables, factors, LIMITS);

		assertEquals(List.of(2, 2, 2), root.variables().stream().map(Variable::domainSize).toList());
		assertEquals(8, cells(root.factors()));
		assertOptimum(variables, factors, root);
	}

	private static long cells(List<Factor> factors) {
		return factors.stream().mapToLong(factor -> factor.scope().stream()
			.mapToLong(Variable::domainSize).reduce(1, Math::multiplyExact)).sum();
	}

	private static void assertOptimum(List<Variable> variables, List<Factor> factors,
		ExactPhysicalReducedSolver.CompactModel root) {
		var expected = ExactCategoricalSolver.solve(variables, factors, LIMITS);
		var actual = ExactCategoricalSolver.solve(root.variables(), root.factors(), LIMITS);
		assertEquals(expected.objective(), actual.objective(), 0);
		assertEquals(actual.objective(), RegionalSearchProblem.evaluateFactors(variables, factors,
			root.expandAssignment(actual.assignmentInVariableOrder())), 0);
	}
}
