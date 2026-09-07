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
import java.util.IdentityHashMap;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Verifies activation-union accounting when reusable demands have different unit prices. */
public class ExactActivationBindingCostTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000, 1_000_000);

	@Test
	public void coactiveDemandsChargeLargestUnitPrice() {
		var fixture = fixture(2, new boolean[] {true, false}, List.of(
			demand("cp", unconditional(), 9, 9),
			demand("fed-one", unconditional(), 4, 4),
			demand("fed-two", unconditional(), 7, 7)));
		Assert.assertEquals(7, fixture.cost(0, 0, 1, 1), 0);
		Assert.assertEquals(9, fixture.cost(0, 1, 1, 1), 0);
		Assert.assertEquals(0, fixture.cost(1, 1, 1, 1), 0);
		fixture.verifyEveryAssignment();
	}

	@Test
	public void exclusiveBranchesChargeExpectedPricedCreation() {
		var fixture = fixture(2, new boolean[] {true, false}, List.of(
			demand("left", event(.5, "branch", true), 9, 9),
			demand("right", event(.5, "branch", false), 4, 4)));
		Assert.assertEquals(6.5, fixture.cost(0, 1, 1), 0);
		Assert.assertEquals(4.5, fixture.cost(0, 1, 0), 0);
		Assert.assertEquals(2, fixture.cost(0, 0, 1), 0);
		fixture.verifyEveryAssignment();
	}

	@Test
	public void sourceAlternativeChangesPriceOrderingAndCanDisableCreation() {
		var fixture = fixture(3, new boolean[] {true, true, false}, List.of(
			demand("first", unconditional(), 9, 2, 0),
			demand("second", unconditional(), 4, 8, 0)));
		Assert.assertEquals(9, fixture.cost(0, 1, 1), 0);
		Assert.assertEquals(8, fixture.cost(1, 1, 1), 0);
		Assert.assertEquals(0, fixture.cost(2, 1, 1), 0);
		fixture.verifyEveryAssignment();
	}

	@Test
	public void unresolvedPricedEventsConservativelyBoundConcreteUnion() {
		var fixture = fixture(2, new boolean[] {true, false}, List.of(
			demand("expensive", event(.6, "unknown-a", true), 9, 9),
			demand("cheap", event(.6, "unknown-b", true), 4, 4)));
		double estimate = fixture.cost(0, 1, 1);
		// Six expensive executions and two additional cheap-only executions cost 6.2 on ten scopes.
		Assert.assertTrue(estimate >= 6.2);
		Assert.assertEquals(7, estimate, 0);
		Assert.assertTrue(fixture.descriptors.stream()
			.anyMatch(value -> value.contains("CONSERVATIVE_CAPPED_ACTIVATION_UNION")));
		fixture.verifyEveryAssignment();
	}

	private static ExactPhysicalCostModel.PricedActivationDemand demand(String name,
		ExactMaterializationActivation.Event event, double... prices) {
		var variable = new ExactCategoricalSolver.Variable(name, 2);
		return new ExactPhysicalCostModel.PricedActivationDemand(
			new ExactPhysicalCostModel.ActivationDemand(List.of(variable),
				List.of(new boolean[] {false, true}), event), prices);
	}

	private static ExactMaterializationActivation.Event unconditional() {
		return new ExactMaterializationActivation.Event(1, List.of());
	}

	private static ExactMaterializationActivation.Event event(double weight, String key, boolean arm) {
		return new ExactMaterializationActivation.Event(weight,
			List.of(new ExactPhysicalCostModel.BranchLiteral(key, arm)));
	}

	private static Fixture fixture(int sourceDomain, boolean[] activeSource,
		List<ExactPhysicalCostModel.PricedActivationDemand> demands) {
		var source = new ExactCategoricalSolver.Variable("source", sourceDomain);
		List<ExactCategoricalSolver.Variable> originals = new ArrayList<>(List.of(source));
		for(var demand : demands)
			originals.addAll(demand.activation().variables());
		List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>();
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addPricedMaterializationActivationFactors("priced", source,
			activeSource, demands, 1, canonical, factorizations, null);
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		List<ExactCategoricalSolver.Factor> factored = new ArrayList<>();
		List<String> descriptors = new ArrayList<>();
		for(var factor : canonical) {
			var decomposition = factorizations.get(factor);
			augmented.addAll(decomposition.auxiliaryVariables());
			factored.addAll(decomposition.factors());
			descriptors.add(decomposition.semanticDescriptor());
		}
		return new Fixture(originals, canonical, augmented, factored, descriptors);
	}

	private record Fixture(List<ExactCategoricalSolver.Variable> originals,
		List<ExactCategoricalSolver.Factor> canonical, List<ExactCategoricalSolver.Variable> augmented,
		List<ExactCategoricalSolver.Factor> factored, List<String> descriptors) {
		double cost(Integer... values) {
			return ExactCategoricalSolver.evaluate(originals, canonical, LIMITS, List.of(values));
		}

		void verifyEveryAssignment() {
			int assignments = 1;
			for(var variable : originals)
				assignments *= variable.domainSize();
			for(int ordinal = 0; ordinal < assignments; ordinal++) {
				int remaining = ordinal;
				List<Integer> values = new ArrayList<>();
				List<ExactCategoricalSolver.Factor> fixed = new ArrayList<>(factored);
				for(var variable : originals) {
					int value = remaining % variable.domainSize();
					remaining /= variable.domainSize();
					values.add(value);
					fixed.add(ExactCategoricalSolver.Factor.lazy(List.of(variable),
						assignment -> assignment[0] == value ? 0 : Double.POSITIVE_INFINITY));
				}
				double expected = ExactCategoricalSolver.evaluate(originals, canonical, LIMITS, values);
				double actual = ExactCategoricalSolver.solve(augmented, fixed, LIMITS).objective();
				Assert.assertEquals(values.toString(), Double.doubleToRawLongBits(expected),
					Double.doubleToRawLongBits(actual));
			}
		}
	}
}
