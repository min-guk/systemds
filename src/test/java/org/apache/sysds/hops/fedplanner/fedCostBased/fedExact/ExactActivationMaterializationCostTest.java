/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.BranchActivationFact;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact;
import org.junit.Assert;
import org.junit.Test;

/** Exercises the production cost-surface assembly, including its canonical monetary terms. */
public class ExactActivationMaterializationCostTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000, 1_000_000);

	@Test
	public void oppositeHalfFrequencyDemandsChargeOneWholeCopy() {
		var left = event(0.5, "branch", true);
		var right = event(0.5, "branch", false);
		var fixture = assemble(List.of(left, right), 1, 10);
		Assert.assertEquals(2, fixture.canonical.size());
		Assert.assertEquals(10, fixture.cost(1, 1, 1), 0);
		Assert.assertEquals(5, fixture.cost(1, 1, 0), 0);
		Assert.assertEquals(5, fixture.cost(1, 0, 1), 0);
		Assert.assertEquals(0, fixture.cost(0, 1, 1), 0);
		fixture.verifyEveryAssignment();
	}

	@Test
	public void sequentialAndNestedConsumersShareTheirActivationClasses() {
		var unconditional = new ExactMaterializationActivation.Event(1, List.of());
		var sequential = assemble(List.of(unconditional, unconditional), 1, 10);
		Assert.assertEquals(1, sequential.canonical.size());
		Assert.assertEquals(10, sequential.cost(1, 1, 1), 0);
		sequential.verifyEveryAssignment();
		var nested = assemble(List.of(event(0.5, "outer", true),
			new ExactMaterializationActivation.Event(0.25, List.of(
				new ExactPhysicalCostModel.BranchLiteral("outer", true),
				new ExactPhysicalCostModel.BranchLiteral("inner", true)))), 1, 10);
		Assert.assertEquals(5, nested.cost(1, 1, 1), 0);
		Assert.assertEquals(2.5, nested.cost(1, 0, 1), 0);
		nested.verifyEveryAssignment();
	}

	@Test
	public void unresolvedCorrelationKeepsConservativeCapForEverySubset() {
		var fixture = assemble(List.of(event(0.6, "a", true), event(0.6, "b", true),
			event(0.6, "c", true)), 1, 10);
		Assert.assertTrue(fixture.descriptors.stream()
			.anyMatch(value -> value.contains("CONSERVATIVE_CAPPED_ACTIVATION_UNION")));
		Assert.assertEquals(6, fixture.cost(1, 1, 0, 0), 0);
		Assert.assertEquals(10, fixture.cost(1, 1, 1, 0), 0);
		Assert.assertEquals(10, fixture.cost(1, 1, 1, 1), 0);
		fixture.verifyEveryAssignment();
	}

	@Test
	public void sourceLifetimeDistinguishesInvariantFromUpdatedLoopValues() {
		var outside = new OccurrenceProfileFact(1, List.of(), 0);
		var inside = new OccurrenceProfileFact(10, List.of(Pair.of(7L, 10d)), 0);
		Assert.assertEquals(1, ExactPhysicalCostModel.materializationActivation(outside, inside).weight(), 0);
		Assert.assertEquals(10, ExactPhysicalCostModel.materializationActivation(inside, inside).weight(), 0);
		var invariant = assemble(List.of(ExactPhysicalCostModel.materializationActivation(outside, inside)), 1, 10);
		var updated = assemble(List.of(ExactPhysicalCostModel.materializationActivation(inside, inside)), 10, 10);
		Assert.assertEquals(10, invariant.cost(1, 1), 0);
		Assert.assertEquals(100, updated.cost(1, 1), 0);
		invariant.verifyEveryAssignment();
		updated.verifyEveryAssignment();
	}

	@Test
	public void stableOuterBranchCapsInvariantCopyBeforeInnerLoopFrequency() {
		var source = new OccurrenceProfileFact(1, List.of(), 0);
		var consumer = new OccurrenceProfileFact(50, List.of(Pair.of(7L, 100d)), 0,
			List.of(new BranchActivationFact("outer", true, 0.5, List.of())));
		Assert.assertEquals(0.5, ExactPhysicalCostModel.materializationActivation(source, consumer).weight(), 0);
	}

	@Test
	public void oppositeArmsInsideRepeatedLoopAreNotDisjointLifetimeEvents() {
		var source = new OccurrenceProfileFact(1, List.of(), 0);
		var loops = List.of(Pair.of(7L, 10d));
		var left = new OccurrenceProfileFact(5, loops, 0,
			List.of(new BranchActivationFact("inside", true, 0.5, List.of(7L))));
		var right = new OccurrenceProfileFact(5, loops, 0,
			List.of(new BranchActivationFact("inside", false, 0.5, List.of(7L))));
		var events = List.of(ExactPhysicalCostModel.materializationActivation(source, left),
			ExactPhysicalCostModel.materializationActivation(source, right));
		Assert.assertFalse(ExactMaterializationActivation.partition(events, 1).resolved());
		Assert.assertEquals(1, events.get(0).weight(), 0);
		var fixture = assemble(events, 1, 10);
		Assert.assertEquals(10, fixture.cost(1, 1, 1), 0);
		fixture.verifyEveryAssignment();
	}

	@Test
	public void callerBranchConditionsSurviveDifferentFunctionContexts() {
		var source = new OccurrenceProfileFact(1, List.of(), 0);
		var left = new OccurrenceProfileFact(0.5, List.of(), 1,
			List.of(new BranchActivationFact("caller", true, 0.5, List.of())));
		var right = new OccurrenceProfileFact(0.5, List.of(), 2,
			List.of(new BranchActivationFact("caller", false, 0.5, List.of())));
		var fixture = assemble(List.of(ExactPhysicalCostModel.materializationActivation(source, left),
			ExactPhysicalCostModel.materializationActivation(source, right)), 1, 10);
		Assert.assertEquals(10, fixture.cost(1, 1, 1), 0);
		fixture.verifyEveryAssignment();
	}

	@Test
	public void classMonetaryTermsKeepCanonicalAndSolverRoundingIdentical() {
		var fixture = assemble(List.of(new ExactMaterializationActivation.Event(1, List.of()),
			event(0.3, "a", true), event(0.7, "a", false)), 1, 1.234567891234567e16);
		fixture.verifyEveryAssignment();
	}

	private static ExactMaterializationActivation.Event event(double weight, String key, boolean arm) {
		return new ExactMaterializationActivation.Event(weight,
			List.of(new ExactPhysicalCostModel.BranchLiteral(key, arm)));
	}

	private static Fixture assemble(List<ExactMaterializationActivation.Event> events,
		double scope, double unit) {
		var source = new ExactCategoricalSolver.Variable("source", 2);
		List<ExactCategoricalSolver.Variable> originals = new ArrayList<>(List.of(source));
		List<ExactPhysicalCostModel.ActivationDemand> demands = new ArrayList<>();
		for(int index = 0; index < events.size(); index++) {
			var consumer = new ExactCategoricalSolver.Variable("consumer-" + index, 2);
			originals.add(consumer);
			demands.add(new ExactPhysicalCostModel.ActivationDemand(List.of(consumer),
				List.of(new boolean[] {false, true}), events.get(index)));
		}
		List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>();
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addMaterializationActivationFactors("fixture", source,
			new boolean[] {false, true}, new double[] {unit, unit}, demands, scope,
			canonical, factorizations, null);
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		List<String> descriptors = new ArrayList<>();
		for(var factor : canonical) {
			var decomposition = factorizations.get(factor);
			augmented.addAll(decomposition.auxiliaryVariables());
			factors.addAll(decomposition.factors());
			descriptors.add(decomposition.semanticDescriptor());
		}
		return new Fixture(originals, canonical, augmented, factors, descriptors);
	}

	private record Fixture(List<ExactCategoricalSolver.Variable> originals,
		List<ExactCategoricalSolver.Factor> canonical, List<ExactCategoricalSolver.Variable> augmented,
		List<ExactCategoricalSolver.Factor> factors, List<String> descriptors) {
		double cost(Integer... values) {
			return ExactCategoricalSolver.evaluate(originals, canonical, LIMITS, List.of(values));
		}
		void verifyEveryAssignment() {
			for(int mask = 0; mask < (1 << originals.size()); mask++) {
				List<Integer> values = new ArrayList<>();
				List<ExactCategoricalSolver.Factor> fixed = new ArrayList<>(factors);
				for(int index = 0; index < originals.size(); index++) {
					int value = (mask >> index) & 1;
					values.add(value);
					fixed.add(ExactCategoricalSolver.Factor.lazy(List.of(originals.get(index)),
						assignment -> assignment[0] == value ? 0 : Double.POSITIVE_INFINITY));
				}
				var solved = ExactCategoricalSolver.solve(augmented, fixed, LIMITS);
				double expected = ExactCategoricalSolver.evaluate(originals, canonical, LIMITS, values);
				Assert.assertEquals(values.toString(), Double.doubleToRawLongBits(expected),
					Double.doubleToRawLongBits(solved.objective()));
			}
		}
	}
}
