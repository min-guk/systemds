/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class ExactPhysicalReducedSolverOwnershipTransferTest {
	private static final Limits GENEROUS = new Limits(20_000_000, 100_000_000);

	@Test
	public void processedLazyBinarySupportDoesNotAccumulateFrozenSourceTables() {
		int domain = 750;
		int retained = 620;
		Variable x = new Variable("transfer-large-x", domain);
		Variable y = new Variable("transfer-large-y", domain);
		List<Factor> factors = new ArrayList<>();
		for(int distinct = 0; distinct < 5; distinct++) {
			Factor repeated = Factor.lazy(List.of(x,y), values ->
				values[0] < retained && values[1] < retained ? 0d : Double.POSITIVE_INFINITY);
			// Repeated object identity still denotes two factor occurrences and therefore
			// two independently frozen support slots, each consumed exactly once.
			factors.add(repeated);
			factors.add(repeated);
		}

		ExactPhysicalReducedSolver.CompactModel reduced = ExactPhysicalReducedSolver.reducedModel(
			2,List.of(x,y),factors,GENEROUS);

		Assert.assertEquals(1,reduced.variables().get(0).domainSize());
		Assert.assertEquals(1,reduced.variables().get(1).domainSize());
		Assert.assertEquals(List.of(0,0),reduced.expandAssignment(List.of(0,0)));
		Assert.assertEquals(-1,reduced.reducedValue(0,retained));
		Assert.assertEquals(-1,reduced.reducedValue(1,retained));
		Assert.assertEquals(0d,RegionalSearchProblem.evaluateFactors(
			reduced.variables(),reduced.factors(),List.of(0,0)),0d);
		Assert.assertEquals(0d,RegionalSearchProblem.evaluateFactors(
			List.of(x,y),factors,List.of(0,0)),0d);
	}

	@Test
	public void denseAndLazyRepeatedSupportPreserveMappingCanonicalCostAndTieChoice() {
		Variable a = new Variable("transfer-mixed-a",4);
		Variable b = new Variable("transfer-mixed-b",4);
		Factor unary = Factor.dense(List.of(a),Double.POSITIVE_INFINITY,0d,0d,0d);
		double[] binaryValues = new double[16];
		Arrays.fill(binaryValues,Double.POSITIVE_INFINITY);
		for(int av=1; av<4; av++) {
			binaryValues[av*4+1] = 0d;
			binaryValues[av*4+3] = 0d;
		}
		Factor dense = Factor.dense(List.of(a,b),binaryValues);
		Factor lazy = Factor.lazy(List.of(a,b),values -> values[0] > 0
			&& (values[1] == 1 || values[1] == 3) ? 0d : Double.POSITIVE_INFINITY);
		List<Factor> factors = List.of(unary,dense,dense,lazy,lazy);

		ExactPhysicalReducedSolver.CompactModel reduced = ExactPhysicalReducedSolver.reducedModel(
			2,List.of(a,b),factors,GENEROUS);
		Assert.assertEquals(-1,reduced.reducedValue(0,0));
		Assert.assertEquals(-1,reduced.reducedValue(1,0));
		Assert.assertEquals(-1,reduced.reducedValue(1,2));
		for(int source : List.of(1,3)) {
			int reducedValue = reduced.reducedValue(1,source);
			Assert.assertTrue(reducedValue >= 0);
			Assert.assertEquals(1,reduced.sourceValue(1,reducedValue));
		}

		ExactCategoricalSolver.Result selected = ExactPhysicalReducedSolver.solve(
			2,List.of(a,b),factors,GENEROUS,(variable,value) -> value == 3 ? 0L : 5L);
		Assert.assertEquals(List.of(3,3),selected.assignmentInVariableOrder());
		Assert.assertEquals(0d,selected.objective(),0d);
		Assert.assertEquals(selected.objective(),RegionalSearchProblem.evaluateFactors(
			List.of(a,b),factors,selected.assignmentInVariableOrder()),0d);
	}
}
