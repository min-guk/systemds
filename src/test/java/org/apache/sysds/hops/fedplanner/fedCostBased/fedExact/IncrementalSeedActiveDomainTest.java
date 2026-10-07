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
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.*;

public class IncrementalSeedActiveDomainTest {
	private static final Limits LIMITS = new Limits(1000000, 10000000);

	@Test
	public void liftedSeedsMatchConditionedExactOptimumAcrossRepeatedAssignments() {
		Random random = new Random(543913L);
		for(int trial = 0; trial < 150; trial++) {
			List<Variable> variables = List.of(new Variable("x", 2+random.nextInt(4)),
				new Variable("a", 2+random.nextInt(4)), new Variable("b", 2+random.nextInt(4)));
			List<Factor> factors = new ArrayList<>();
			for(int variable = 0; variable < variables.size(); variable++) {
				double[] costs = new double[variables.get(variable).domainSize()];
				for(int value = 0; value < costs.length; value++) costs[value] = Math.scalb(value, -4*variable);
				factors.add(Factor.dense(List.of(variables.get(variable)), costs));
			}
			for(List<Variable> scope : List.of(List.of(variables.get(2),variables.get(0)),
				List.of(variables.get(0),variables.get(1)), variables)) {
				int cells = scope.stream().mapToInt(Variable::domainSize).reduce(1,Math::multiplyExact);
				HardTable hard = HardTable.allocate(cells);
				for(int cell = 1; cell < cells; cell++) if(random.nextBoolean()) hard.forbid(cell);
				factors.add(Factor.hardOwned(scope,hard.compactAllFeasible()));
			}
			factors.add(factors.get(4));
			int originalCount = trial % 3;
			var root = ExactPhysicalReducedSolver.reducedModel(originalCount,variables,factors,LIMITS);
			for(int attempt = 0; attempt < 4; attempt++) {
				List<Integer> original = new ArrayList<>();
				for(int variable=0;variable<originalCount;variable++)
					original.add(attempt==0?0:random.nextInt(variables.get(variable).domainSize()));
				assertExact(root, original);
			}
		}
	}

	private static void assertExact(ExactPhysicalReducedSolver.CompactModel root,List<Integer> original) {
		List<Factor> conditioned = new ArrayList<>(root.factors());
		for(int variable=0;variable<original.size();variable++) {
			int selected = root.reducedValue(variable,original.get(variable));
			if(selected < 0) {
				Assert.assertThrows(IllegalArgumentException.class,()->IncrementalRegionalSeed.lift(root,original,LIMITS));
				return;
			}
			double[] fixed = new double[root.variables().get(variable).domainSize()];
			Arrays.fill(fixed,Double.POSITIVE_INFINITY);fixed[selected]=0d;
			conditioned.add(Factor.dense(List.of(root.variables().get(variable)),fixed));
		}
		Result expected;
		try { expected = ExactCategoricalSolver.solve(root.variables(),conditioned,LIMITS); }
		catch(IllegalArgumentException infeasible) {
			Assert.assertTrue(infeasible.getMessage(),infeasible.getMessage().startsWith("EXACT_VE_NO_FEASIBLE_ASSIGNMENT"));
			Assert.assertThrows(IllegalArgumentException.class,()->IncrementalRegionalSeed.lift(root,original,LIMITS));
			return;
		}
		int[] actual = IncrementalRegionalSeed.lift(root,original,LIMITS);
		Assert.assertEquals(expected.assignmentInVariableOrder(),Arrays.stream(actual).boxed().toList());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(ExactCategoricalSolver.evaluate(root.variables(),root.factors(),LIMITS,
				Arrays.stream(actual).boxed().toList())));
	}
}
