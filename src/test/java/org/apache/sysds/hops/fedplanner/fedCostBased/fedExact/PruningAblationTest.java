/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Test;

public class PruningAblationTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000L, 1_000_000L);

	@After
	public void clearProperty() {
		System.clearProperty(PruningAblation.PROPERTY);
	}

	@Test
	public void baselineAndLocalOnlyDifferOnlyByBoundaryPrefixCuts() {
		assertFlags("baseline", false);
		assertFlags("local_only", true);

		var domain = variable("domain", 3);
		var domainFactors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(domain), 5d, 1d, 2d));
		assertDomain("baseline", domain, domainFactors, 3);
		assertDomain("local_only", domain, domainFactors, 3);

		var mergeVariable = variable("flags-merge", 3);
		List<ExactCategoricalSolver.Variable> mergeVariables = List.of(mergeVariable);
		List<ExactCategoricalSolver.BoundaryMessage> leaves = ExactCategoricalSolver.boundaryLeaves(
			mergeVariables, List.of(
				ExactCategoricalSolver.Factor.dense(List.of(mergeVariable), 1d, 2d, 3d),
				ExactCategoricalSolver.Factor.dense(List.of(mergeVariable), 0d, 0d, 0d),
				ExactCategoricalSolver.Factor.dense(List.of(mergeVariable), 0d, 0d, 0d)), LIMITS);
		System.setProperty(PruningAblation.PROPERTY, "baseline");
		var baseline = new ExactCategoricalSolver.BoundaryMergeCounters();
		ExactCategoricalSolver.mergeBoundary(leaves, List.of(), LIMITS, 3L, baseline);
		assertEquals(9L, baseline.childEvaluations());
		assertEquals(0L, baseline.costCuts());
		System.setProperty(PruningAblation.PROPERTY, "local_only");
		var local = new ExactCategoricalSolver.BoundaryMergeCounters();
		ExactCategoricalSolver.mergeBoundary(leaves, List.of(), LIMITS, 3L, local);
		assertEquals(5L, local.childEvaluations());
		assertEquals(2L, local.costCuts());
		assertEquals(baseline.fullChildEvaluations(), local.fullChildEvaluations());
	}

	@Test
	public void productionDefaultMatchesExplicitLocalOnlyWithoutEnablingAReceipt() {
		PruningAblation.Options defaults = PruningAblation.current();
		assertFalse(defaults.explicit());
		assertEquals(PruningAblation.Variant.LOCAL_ONLY, defaults.variant());
		assertTrue(defaults.local());

		var mergeVariable = variable("default-merge-boundary", 2);
		var mergeInternal = variable("default-merge-internal", 3);
		List<ExactCategoricalSolver.Variable> mergeVariables = List.of(mergeVariable, mergeInternal);
		List<ExactCategoricalSolver.BoundaryMessage> leaves = ExactCategoricalSolver.boundaryLeaves(
			mergeVariables, List.of(
				ExactCategoricalSolver.Factor.dense(mergeVariables, 1d, 2d, 3d, .125d, 2d, 3d),
				ExactCategoricalSolver.Factor.dense(mergeVariables, 0d, 0d, 0d, 0d, 0d, 0d),
				ExactCategoricalSolver.Factor.dense(mergeVariables, 0d, 0d, 0d, 0d, 0d, 0d)), LIMITS);
		var defaultCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var defaultMessage = ExactCategoricalSolver.mergeBoundary(
			leaves, List.of(mergeVariable), LIMITS, 6L, defaultCounters);
		System.setProperty(PruningAblation.PROPERTY, "local_only");
		PruningAblation.Options explicit = PruningAblation.current();
		assertTrue(explicit.explicit());
		assertEquals(defaults.variant(), explicit.variant());
		assertEquals(defaults.local(), explicit.local());
		var explicitCounters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var explicitMessage = ExactCategoricalSolver.mergeBoundary(
			leaves, List.of(mergeVariable), LIMITS, 6L, explicitCounters);
		assertEquals(defaultMessage.minimum(), explicitMessage.minimum(), 0d);
		assertEquals(defaultMessage.lowerBound(), explicitMessage.lowerBound(), 0d);
		assertTrue(Arrays.equals(defaultMessage.minMarginals(mergeVariable),
			explicitMessage.minMarginals(mergeVariable)));
		assertTrue(Arrays.equals(defaultMessage.lowerMinMarginals(mergeVariable),
			explicitMessage.lowerMinMarginals(mergeVariable)));
		int[] defaultAssignment = {1, -1};
		int[] explicitAssignment = {1, -1};
		defaultMessage.decodeInto(defaultAssignment, mergeVariables);
		explicitMessage.decodeInto(explicitAssignment, mergeVariables);
		assertTrue(Arrays.equals(defaultAssignment, explicitAssignment));
		assertEquals(defaultCounters.fullChildEvaluations(), explicitCounters.fullChildEvaluations());
		assertEquals(defaultCounters.childEvaluations(), explicitCounters.childEvaluations());
		assertEquals(defaultCounters.infeasibleCuts(), explicitCounters.infeasibleCuts());
		assertEquals(defaultCounters.costCuts(), explicitCounters.costCuts());
		assertTrue(defaultCounters.costCuts() > 0);
	}

	@Test
	public void removedVariantsAreRejected() {
		for(String variant : List.of("local", "dominance", "support", "dominance_local",
			"support_local", "global")) {
			System.setProperty(PruningAblation.PROPERTY, variant);
			try {
				PruningAblation.current();
				throw new AssertionError("removed variant was accepted: " + variant);
			}
			catch(IllegalArgumentException expected) {
				assertTrue(expected.getMessage().startsWith(
					"FED_PLANNER_PRUNING_ABLATION_INVALID|value=" + variant));
			}
		}
	}

	private static void assertDomain(String variant, ExactCategoricalSolver.Variable variable,
		List<ExactCategoricalSolver.Factor> factors, int expected) {
		System.setProperty(PruningAblation.PROPERTY, variant);
		var root = RegionalSearchProblem.generic(List.of(variable), factors).reducedRoot(LIMITS);
		assertEquals(expected, root.variables().get(0).domainSize());
	}

	private static void assertFlags(String variant, boolean local) {
		System.setProperty(PruningAblation.PROPERTY, variant);
		PruningAblation.Options options = PruningAblation.current();
		assertTrue(options.explicit());
		assertEquals(local, options.local());
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}
}
