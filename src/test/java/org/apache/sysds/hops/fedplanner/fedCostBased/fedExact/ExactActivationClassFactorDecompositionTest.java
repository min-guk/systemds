/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ExactActivationClassFactorDecompositionTest {
	private static final ExactCategoricalSolver.Limits TEST_LIMITS =
		new ExactCategoricalSolver.Limits(100_000L, 1_000_000L);

	@Test
	public void factorizedOrIsBitExactForEveryOriginalAssignment() {
		var source = new ExactCategoricalSolver.Variable("source", 4);
		var repeated = new ExactCategoricalSolver.Variable("consumer-a", 4);
		var other = new ExactCategoricalSolver.Variable("consumer-b", 3);
		var decomposition = ExactActivationClassFactorDecomposition.create("fixture", source,
			new boolean[] {false, true, true, true}, new double[] {3d, 5d, 7d, 11d}, List.of(
				new ExactActivationClassFactorDecomposition.Demand(repeated,
					new boolean[] {false, true, false, false}),
				new ExactActivationClassFactorDecomposition.Demand(other,
					new boolean[] {false, true, true}),
				new ExactActivationClassFactorDecomposition.Demand(repeated,
					new boolean[] {false, false, true, false})));
		List<ExactCategoricalSolver.Variable> originals = List.of(source, repeated, other);
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		augmented.addAll(decomposition.auxiliaryVariables());

		for(int sourceValue = 0; sourceValue < source.domainSize(); sourceValue++)
			for(int repeatedValue = 0; repeatedValue < repeated.domainSize(); repeatedValue++)
				for(int otherValue = 0; otherValue < other.domainSize(); otherValue++) {
					List<Integer> assignment = List.of(sourceValue, repeatedValue, otherValue);
					double expected = ExactCategoricalSolver.evaluate(originals,
						List.of(decomposition.canonicalFactor()), TEST_LIMITS, assignment);
					List<ExactCategoricalSolver.Factor> factors =
						new ArrayList<>(decomposition.solverFactors());
					factors.add(fixed(source, sourceValue));
					factors.add(fixed(repeated, repeatedValue));
					factors.add(fixed(other, otherValue));
					var solved = ExactCategoricalSolver.solve(augmented, factors, TEST_LIMITS);
					Assert.assertEquals("factorization changed raw objective bits for " + assignment,
						Double.doubleToRawLongBits(expected),
						Double.doubleToRawLongBits(solved.objective()));
					Assert.assertEquals(assignment,
						solved.assignmentInVariableOrder().subList(0, originals.size()));
				}
	}

	@Test
	public void repeatedConsumerAndSourceOverlapUseOneMergedObservation() {
		var sourceAndConsumer = new ExactCategoricalSolver.Variable("source-consumer", 4);
		var decomposition = ExactActivationClassFactorDecomposition.create("overlap",
			sourceAndConsumer, new boolean[] {true, true, false, true},
			new double[] {2d, 3d, 5d, 7d}, List.of(
				new ExactActivationClassFactorDecomposition.Demand(sourceAndConsumer,
					new boolean[] {false, true, false, false}),
				new ExactActivationClassFactorDecomposition.Demand(sourceAndConsumer,
					new boolean[] {true, false, false, true})));
		Assert.assertEquals(1, decomposition.auxiliaryVariables().size());
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(List.of(sourceAndConsumer));
		augmented.addAll(decomposition.auxiliaryVariables());
		for(int value = 0; value < sourceAndConsumer.domainSize(); value++) {
			double expected = ExactCategoricalSolver.evaluate(List.of(sourceAndConsumer),
				List.of(decomposition.canonicalFactor()), TEST_LIMITS, List.of(value));
			List<ExactCategoricalSolver.Factor> factors =
				new ArrayList<>(decomposition.solverFactors());
			factors.add(fixed(sourceAndConsumer, value));
			Assert.assertEquals(Double.doubleToRawLongBits(expected),
				Double.doubleToRawLongBits(ExactCategoricalSolver.solve(augmented, factors,
					TEST_LIMITS).objective()));
		}
	}

	@Test
	public void emptyInactiveAndZeroPriceClassesNeedNoAuxiliaries() {
		var source = new ExactCategoricalSolver.Variable("zero-source", 3);
		var consumer = new ExactCategoricalSolver.Variable("zero-consumer", 2);
		for(var decomposition : List.of(
			ExactActivationClassFactorDecomposition.create("empty", source,
				new boolean[] {true, true, true}, new double[] {1d, 2d, 3d}, List.of()),
			ExactActivationClassFactorDecomposition.create("inactive", source,
				new boolean[] {true, true, true}, new double[] {1d, 2d, 3d}, List.of(
					new ExactActivationClassFactorDecomposition.Demand(consumer,
						new boolean[] {false, false}))),
			ExactActivationClassFactorDecomposition.create("zero", source,
				new boolean[] {true, true, true}, new double[] {0d, 0d, 0d}, List.of(
					new ExactActivationClassFactorDecomposition.Demand(consumer,
						new boolean[] {false, true}))))) {
			Assert.assertTrue(decomposition.auxiliaryVariables().isEmpty());
			Assert.assertTrue(decomposition.solverFactors().isEmpty());
			Assert.assertTrue(decomposition.semanticDescriptor().contains("identicallyZero=true"));
		}
	}

	@Test
	public void inputArraysAndReturnedDemandMaskAreDefensive() {
		var source = new ExactCategoricalSolver.Variable("immutable-source", 2);
		var consumer = new ExactCategoricalSolver.Variable("immutable-consumer", 2);
		boolean[] sourceMask = {true, true};
		double[] prices = {2d, 3d};
		boolean[] consumerMask = {false, true};
		var demand = new ExactActivationClassFactorDecomposition.Demand(consumer, consumerMask);
		var decomposition = ExactActivationClassFactorDecomposition.create("immutable", source,
			sourceMask, prices, List.of(demand));
		sourceMask[1] = false;
		prices[1] = 99d;
		consumerMask[1] = false;
		boolean[] returned = demand.activeConsumerValues();
		returned[1] = false;
		Assert.assertEquals(Double.doubleToRawLongBits(3d), Double.doubleToRawLongBits(
			ExactCategoricalSolver.evaluate(List.of(source, consumer),
				List.of(decomposition.canonicalFactor()), TEST_LIMITS, List.of(1, 1))));
	}

	@Test
	public void validatesDomainsPricesAndVariableIdentity() {
		var source = new ExactCategoricalSolver.Variable("source", 2);
		var consumer = new ExactCategoricalSolver.Variable("consumer", 2);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new ExactActivationClassFactorDecomposition.Demand(consumer,
				new boolean[] {true}));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactActivationClassFactorDecomposition.create("domain", source,
				new boolean[] {true}, new double[] {1d, 2d}, List.of()));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactActivationClassFactorDecomposition.create("prices", source,
				new boolean[] {true, true}, new double[] {1d}, List.of()));
		for(double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY,
			Double.NEGATIVE_INFINITY, -1d, -0d))
			Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactActivationClassFactorDecomposition.create("invalid", source,
					new boolean[] {true, true}, new double[] {1d, invalid}, List.of()));
		var equalButDistinct = new ExactCategoricalSolver.Variable("source", 2);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactActivationClassFactorDecomposition.create("identity", source,
				new boolean[] {true, true}, new double[] {1d, 2d}, List.of(
					new ExactActivationClassFactorDecomposition.Demand(equalButDistinct,
						new boolean[] {false, true}))));
	}

	@Test
	public void descriptorBindsSourceAndDemandSemantics() {
		var source = new ExactCategoricalSolver.Variable("descriptor-source", 2);
		var consumer = new ExactCategoricalSolver.Variable("descriptor-consumer", 2);
		var base = ExactActivationClassFactorDecomposition.create("descriptor", source,
			new boolean[] {true, true}, new double[] {1d, 2d}, List.of(
				new ExactActivationClassFactorDecomposition.Demand(consumer,
					new boolean[] {false, true}))).semanticDescriptor();
		var changedPrice = ExactActivationClassFactorDecomposition.create("descriptor", source,
			new boolean[] {true, true}, new double[] {1d, 3d}, List.of(
				new ExactActivationClassFactorDecomposition.Demand(consumer,
					new boolean[] {false, true}))).semanticDescriptor();
		var changedSourceMask = ExactActivationClassFactorDecomposition.create("descriptor", source,
			new boolean[] {true, false}, new double[] {1d, 2d}, List.of(
				new ExactActivationClassFactorDecomposition.Demand(consumer,
					new boolean[] {false, true}))).semanticDescriptor();
		var changedDemandMask = ExactActivationClassFactorDecomposition.create("descriptor", source,
			new boolean[] {true, true}, new double[] {1d, 2d}, List.of(
				new ExactActivationClassFactorDecomposition.Demand(consumer,
					new boolean[] {true, false}))).semanticDescriptor();
		Assert.assertNotEquals(base, changedPrice);
		Assert.assertNotEquals(base, changedSourceMask);
		Assert.assertNotEquals(base, changedDemandMask);
	}

	@Test
	public void manyConsumersKeepBoundedWidthAndBooleanAuxiliaries() {
		var source = new ExactCategoricalSolver.Variable("wide-source", 4);
		List<ExactCategoricalSolver.Variable> originals = new ArrayList<>();
		originals.add(source);
		List<ExactActivationClassFactorDecomposition.Demand> demands = new ArrayList<>();
		for(int index = 0; index < 20; index++) {
			var consumer = new ExactCategoricalSolver.Variable("wide-consumer-" + index, 4);
			originals.add(consumer);
			demands.add(new ExactActivationClassFactorDecomposition.Demand(consumer,
				new boolean[] {false, true, false, true}));
		}
		var decomposition = ExactActivationClassFactorDecomposition.create("wide", source,
			new boolean[] {false, true, true, true}, new double[] {1d, 2d, 3d, 4d}, demands);
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactCategoricalSolver.analyze(
			originals, List.of(decomposition.canonicalFactor()), TEST_LIMITS));
		Assert.assertTrue(decomposition.auxiliaryVariables().stream()
			.allMatch(variable -> variable.domainSize() == 2));
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		augmented.addAll(decomposition.auxiliaryVariables());
		var statistics = ExactCategoricalSolver.analyze(augmented,
			decomposition.solverFactors(), TEST_LIMITS);
		Assert.assertTrue(statistics.maximumFactorCells() <= 16L);
		Assert.assertTrue(decomposition.solverFactors().stream().allMatch(factor ->
			factor.scope().size() <= 3));
	}

	private static ExactCategoricalSolver.Factor fixed(
		ExactCategoricalSolver.Variable variable, int accepted) {
		return ExactCategoricalSolver.Factor.lazy(List.of(variable), values ->
			values[0] == accepted ? 0d : Double.POSITIVE_INFINITY);
	}
}
