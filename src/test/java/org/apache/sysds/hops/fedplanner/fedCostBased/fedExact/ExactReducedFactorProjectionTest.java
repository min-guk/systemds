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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.HardTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class ExactReducedFactorProjectionTest {
	@Test
	public void everyProjectedCellMatchesIndependentSourceCoordinates() {
		Random random = new Random(158384L);
		for(int trial = 0; trial < 160; trial++) {
			List<Variable> variables = new ArrayList<>();
			for(int variable = 0; variable < 4; variable++)
				variables.add(new Variable("axis-" + variable, 3 + random.nextInt(4)));
			List<Factor> factors = new ArrayList<>();
			for(int variable = 0; variable < variables.size(); variable++) {
				double[] allowed = new double[variables.get(variable).domainSize()];
				for(int value = 0; value < allowed.length; value++)
					allowed[value] = value == 1 || (variable != 0 && value >= 3)
						? 0d : Double.POSITIVE_INFINITY;
				factors.add(Factor.dense(List.of(variables.get(variable)), allowed));
			}
			for(int ordinal = 0; ordinal < 8; ordinal++) {
				List<Variable> shuffled = new ArrayList<>(variables);
				Collections.shuffle(shuffled, random);
				List<Variable> scope = List.copyOf(shuffled.subList(0, ordinal % 5));
				int cells = scope.stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
				double[] costs = new double[cells];
				HardTable hard = HardTable.allocate(cells);
				for(int cell = 0; cell < cells; cell++) {
					int[] coordinates = decode(cell, scope);
					int code = 0;
					for(int coordinate : coordinates) code = 3 * code + coordinate % 2;
					if(ordinal % 2 == 0) {
						if(code % 7 == 0 && scope.size() > 0) hard.forbid(cell);
					}
					else costs[cell] = switch(code % 4) {
						case 0 -> Double.MIN_VALUE;
						case 1 -> 0d;
						case 2 -> 0x1.0000000000001p5;
						default -> 0x1.0000000000002p4;
					};
				}
				factors.add(ordinal % 2 == 0 ? Factor.hardOwned(scope, hard.compactAllFeasible())
					: Factor.dense(scope, costs));
			}
			factors.add(factors.get(6));
			var model = ExactPhysicalReducedSolver.reducedModel(variables.size(), variables, factors,
				new Limits(1000000,10000000));
			Assert.assertEquals(factors.size(), model.factors().size());
			for(int ordinal = 0; ordinal < factors.size(); ordinal++) {
				Factor before = factors.get(ordinal), after = model.factors().get(ordinal);
				Assert.assertEquals(before.scope().size(), after.scope().size());
				int cells = after.scope().stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
				for(int cell = 0; cell < cells; cell++) {
					int[] reduced = decode(cell, after.scope());
					int[] source = new int[reduced.length];
					for(int axis = 0; axis < source.length; axis++) {
						int variable = variables.indexOf(before.scope().get(axis));
						Assert.assertSame(model.variables().get(variable), after.scope().get(axis));
						source[axis] = model.sourceValue(variable,reduced[axis]);
					}
					Assert.assertEquals("trial="+trial+", factor="+ordinal+", cell="+cell,
						Double.doubleToRawLongBits(before.cost(source)),
						Double.doubleToRawLongBits(after.cost(reduced)));
				}
			}
		}
	}

	private static int[] decode(int cell, List<Variable> scope) {
		int[] coordinates = new int[scope.size()];
		for(int axis = coordinates.length-1; axis >= 0; axis--) {
			coordinates[axis] = cell % scope.get(axis).domainSize();
			cell /= scope.get(axis).domainSize();
		}
		return coordinates;
	}
}
