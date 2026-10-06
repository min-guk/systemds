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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Exactness guard for the bounded unresolved-union encoding used by shared function GETs. */
public class ExactFunctionAliasActivationEncodingTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000, 1_000_000);

	@Test
	public void boundedEventQuotientMatchesCanonicalUnionForEveryAssignment() throws Exception {
		var representative = new ExactCategoricalSolver.Variable("representative", 2);
		var firstProducer = new ExactCategoricalSolver.Variable("producer-a", 2);
		var secondProducer = new ExactCategoricalSolver.Variable("producer-b", 2);
		var firstConsumer = new ExactCategoricalSolver.Variable("consumer-a", 2);
		var duplicateConsumer = new ExactCategoricalSolver.Variable("consumer-a-duplicate", 2);
		var secondConsumer = new ExactCategoricalSolver.Variable("consumer-b", 2);
		List<ExactCategoricalSolver.Variable> originals = List.of(representative,
			firstProducer, secondProducer, firstConsumer, duplicateConsumer, secondConsumer);
		var firstEvent = event(0.6, "a");
		var secondEvent = event(0.6, "b");
		List<ExactPhysicalCostModel.ActivationDemand> demands = List.of(
			new ExactPhysicalCostModel.ActivationDemand(List.of(firstConsumer, firstProducer),
				List.of(active(), active()), firstEvent),
			new ExactPhysicalCostModel.ActivationDemand(List.of(duplicateConsumer, firstProducer),
				List.of(active(), active()), firstEvent),
			new ExactPhysicalCostModel.ActivationDemand(List.of(secondConsumer, secondProducer),
				List.of(active(), active()), secondEvent));
		Assert.assertFalse(ExactMaterializationActivation.partition(
			demands.stream().map(ExactPhysicalCostModel.ActivationDemand::event).toList(), 1d).resolved());

		List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>();
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactPhysicalCostModel.SolverFactorization>();
		boundedAssembler().invoke(null, "function-alias-fixture", representative,
			new boolean[] {true, true}, new double[] {10d, 10d}, demands, 1d,
			canonical, factorizations, null, true);
		Assert.assertEquals(1, canonical.size());
		var decomposition = factorizations.get(canonical.get(0));
		Assert.assertTrue(decomposition.semanticDescriptor().contains("CONSERVATIVE_EVENT_QUOTIENT_V1"));
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		augmented.addAll(decomposition.auxiliaryVariables());

		for(int mask = 0; mask < (1 << originals.size()); mask++) {
			List<Integer> values = new ArrayList<>();
			List<ExactCategoricalSolver.Factor> fixed = new ArrayList<>(decomposition.factors());
			for(int index = 0; index < originals.size(); index++) {
				int value = mask >> index & 1;
				values.add(value);
				var variable = originals.get(index);
				fixed.add(ExactCategoricalSolver.Factor.lazy(List.of(variable),
					assignment -> assignment[0] == value ? 0d : Double.POSITIVE_INFINITY));
			}
			double expected = ExactCategoricalSolver.evaluate(originals, canonical, LIMITS, values);
			double actual = ExactCategoricalSolver.solve(augmented, fixed, LIMITS).objective();
			Assert.assertEquals(values.toString(), Double.doubleToRawLongBits(expected),
				Double.doubleToRawLongBits(actual));
		}
	}

	private static boolean[] active() {
		return new boolean[] {false, true};
	}

	private static ExactMaterializationActivation.Event event(double weight, String decision) {
		return new ExactMaterializationActivation.Event(weight,
			List.of(new ExactPhysicalCostModel.BranchLiteral(decision, true)));
	}

	private static Method boundedAssembler() throws Exception {
		Method method = ExactPhysicalCostModel.class.getDeclaredMethod(
			"addMaterializationActivationFactors", String.class,
			ExactCategoricalSolver.Variable.class, boolean[].class, double[].class,
			List.class, double.class, List.class, IdentityHashMap.class,
			IdentityHashMap.class, boolean.class);
		method.setAccessible(true);
		return method;
	}
}
