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

public class ExactMaxDemandFactorDecompositionTest {
	private static final ExactCategoricalSolver.Limits TEST_LIMITS =
		new ExactCategoricalSolver.Limits(100_000L, 1_000_000L);

	@Test
	public void factorizedMaximumIsBitExactForEveryOriginalAssignment() {
		var source = new ExactCategoricalSolver.Variable("source", 4);
		var repeatedConsumer = new ExactCategoricalSolver.Variable("consumer-a", 4);
		var otherConsumer = new ExactCategoricalSolver.Variable("consumer-b", 3);
		var decomposition = ExactMaxDemandFactorDecomposition.create("fixture", source,
			new boolean[] {false, true, true, true}, List.of(
				new ExactMaxDemandFactorDecomposition.Demand(repeatedConsumer,
					new boolean[] {false, true, false, true}, new double[] {9d, 7d, 7d, 11d}),
				new ExactMaxDemandFactorDecomposition.Demand(otherConsumer,
					new boolean[] {false, true, true}, new double[] {4d, 7d, 13d, 5d}),
				new ExactMaxDemandFactorDecomposition.Demand(repeatedConsumer,
					new boolean[] {false, false, true, true}, new double[] {2d, 3d, 13d, 11d})));
		List<ExactCategoricalSolver.Variable> originals =
			List.of(source, repeatedConsumer, otherConsumer);
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		augmented.addAll(decomposition.auxiliaryVariables());

		for(int sourceValue = 0; sourceValue < source.domainSize(); sourceValue++)
			for(int firstValue = 0; firstValue < repeatedConsumer.domainSize(); firstValue++)
				for(int secondValue = 0; secondValue < otherConsumer.domainSize(); secondValue++) {
					List<Integer> assignment = List.of(sourceValue, firstValue, secondValue);
					double expected = ExactCategoricalSolver.evaluate(originals,
						List.of(decomposition.canonicalFactor()), TEST_LIMITS, assignment);
					List<ExactCategoricalSolver.Factor> factors =
						new ArrayList<>(decomposition.solverFactors());
					factors.add(fixed(source, sourceValue));
					factors.add(fixed(repeatedConsumer, firstValue));
					factors.add(fixed(otherConsumer, secondValue));
					var solved = ExactCategoricalSolver.solve(augmented, factors, TEST_LIMITS);
					Assert.assertEquals("factorization changed raw objective bits for " + assignment,
						Double.doubleToRawLongBits(expected),
						Double.doubleToRawLongBits(solved.objective()));
					Assert.assertEquals("factorization changed original decision assignment",
						assignment, solved.assignmentInVariableOrder().subList(0, originals.size()));
				}
	}

	@Test
	public void semanticProjectionKeepsDistinctOriginalAlternatives() {
		var source = new ExactCategoricalSolver.Variable("source-authority", 3);
		var consumer = new ExactCategoricalSolver.Variable("consumer-authority", 4);
		var decomposition = ExactMaxDemandFactorDecomposition.create("authority", source,
			new boolean[] {true, true, true}, List.of(
				new ExactMaxDemandFactorDecomposition.Demand(consumer,
					new boolean[] {false, true, true, false}, new double[] {5d, 5d, 5d})));
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(List.of(source, consumer));
		augmented.addAll(decomposition.auxiliaryVariables());
		for(int authorityAlternative : List.of(1, 2)) {
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(decomposition.solverFactors());
			factors.add(fixed(source, authorityAlternative));
			factors.add(fixed(consumer, authorityAlternative));
			var solved = ExactCategoricalSolver.solve(augmented, factors, TEST_LIMITS);
			Assert.assertEquals(authorityAlternative,
				(int) solved.assignmentInVariableOrder().get(0));
			Assert.assertEquals(authorityAlternative,
				(int) solved.assignmentInVariableOrder().get(1));
		}
	}

	@Test
	public void sourceMayAlsoCarryAConsumerObservation() {
		var sourceAndConsumer = new ExactCategoricalSolver.Variable("source-consumer", 3);
		var decomposition = ExactMaxDemandFactorDecomposition.create("overlap", sourceAndConsumer,
			new boolean[] {true, true, true}, List.of(
				new ExactMaxDemandFactorDecomposition.Demand(sourceAndConsumer,
					new boolean[] {false, true, true}, new double[] {2d, 3d, 5d}),
				new ExactMaxDemandFactorDecomposition.Demand(sourceAndConsumer,
					new boolean[] {true, false, true}, new double[] {7d, 11d, 13d})));
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(List.of(sourceAndConsumer));
		augmented.addAll(decomposition.auxiliaryVariables());
		for(int value = 0; value < sourceAndConsumer.domainSize(); value++) {
			double expected = ExactCategoricalSolver.evaluate(List.of(sourceAndConsumer),
				List.of(decomposition.canonicalFactor()), TEST_LIMITS, List.of(value));
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(decomposition.solverFactors());
			factors.add(fixed(sourceAndConsumer, value));
			var solved = ExactCategoricalSolver.solve(augmented, factors, TEST_LIMITS);
			Assert.assertEquals(Double.doubleToRawLongBits(expected),
				Double.doubleToRawLongBits(solved.objective()));
		}
	}

	@Test
	public void boundedWidthDecompositionAvoidsOriginalCartesianFactor() {
		var source = new ExactCategoricalSolver.Variable("wide-source", 4);
		List<ExactCategoricalSolver.Variable> originals = new ArrayList<>();
		originals.add(source);
		List<ExactMaxDemandFactorDecomposition.Demand> demands = new ArrayList<>();
		for(int index = 0; index < 16; index++) {
			var consumer = new ExactCategoricalSolver.Variable("wide-consumer-" + index, 4);
			originals.add(consumer);
			demands.add(new ExactMaxDemandFactorDecomposition.Demand(consumer,
				new boolean[] {false, true, false, true}, new double[] {1d, 2d, 3d, 4d}));
		}
		var decomposition = ExactMaxDemandFactorDecomposition.create("wide", source,
			new boolean[] {false, true, true, true}, demands);
		Assert.assertThrows("monolithic factor must exceed the dense solver representation",
			IllegalArgumentException.class, () -> ExactCategoricalSolver.analyze(originals,
				List.of(decomposition.canonicalFactor()), TEST_LIMITS));
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		augmented.addAll(decomposition.auxiliaryVariables());
		ExactCategoricalSolver.Statistics statistics = ExactCategoricalSolver.analyze(
			augmented, decomposition.solverFactors(), TEST_LIMITS);
		Assert.assertTrue(statistics.maximumFactorCells() <= TEST_LIMITS.maximumFactorCells());
		Assert.assertTrue(statistics.materializedFactorCells()
			<= TEST_LIMITS.maximumMaterializedCells());
	}

	@Test
	public void descriptorBindsEveryNumericPrice() {
		var source = new ExactCategoricalSolver.Variable("descriptor-source", 2);
		var consumer = new ExactCategoricalSolver.Variable("descriptor-consumer", 2);
		var first = ExactMaxDemandFactorDecomposition.create("descriptor", source,
			new boolean[] {true, true}, List.of(new ExactMaxDemandFactorDecomposition.Demand(
				consumer, new boolean[] {false, true}, new double[] {1d, 2d})));
		var second = ExactMaxDemandFactorDecomposition.create("descriptor", source,
			new boolean[] {true, true}, List.of(new ExactMaxDemandFactorDecomposition.Demand(
				consumer, new boolean[] {false, true}, new double[] {1d, 3d})));
		Assert.assertNotEquals(first.semanticDescriptor(), second.semanticDescriptor());
	}

	private static ExactCategoricalSolver.Factor fixed(
		ExactCategoricalSolver.Variable variable, int accepted) {
		return ExactCategoricalSolver.Factor.lazy(List.of(variable), values ->
			values[0] == accepted ? 0d : Double.POSITIVE_INFINITY);
	}
}
