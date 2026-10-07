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
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class RegionalHardSupportMergeTest {
	private static final Limits LIMITS = new Limits(1_000_000,10_000_000);

	@Test
	public void laterHardInfinityDoesNotHideEarlierNumericOverflow() {
		Variable x = new Variable("hard-overflow-x",2);
		List<Variable> variables = List.of(x);
		List<Factor> factors = List.of(
			Factor.dense(variables,Double.MAX_VALUE,1d),
			Factor.dense(variables,Double.MAX_VALUE,2d),
			Factor.dense(variables,Double.POSITIVE_INFINITY,0d));
		try {
			merge(variables,factors,List.of());
			Assert.fail("A later hard infinity must not mask the earlier addition overflow");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW",expected.getMessage());
		}
	}

	@Test
	public void firstHardInfinityStillAbsorbsOtherwiseOverflowingArithmetic() {
		Variable x = new Variable("first-hard-overflow-x",2);
		List<Variable> variables = List.of(x);
		BoundaryMessage result = merge(variables,List.of(
			Factor.dense(variables,Double.POSITIVE_INFINITY,0d),
			Factor.dense(variables,Double.MAX_VALUE,1d),
			Factor.dense(variables,Double.MAX_VALUE,2d)),List.of());
		Assert.assertEquals(3d,result.minimum(),0d);
		int[] selected = {0};
		result.decodeInto(selected,variables);
		Assert.assertArrayEquals(new int[] {1},selected);
	}

	@Test
	public void sparseHardTraversalKeepsCanonicalUnionTieAndAvoidsFullProductCosts() {
		Variable x = new Variable("hard-join-x",3);
		Variable y = new Variable("hard-join-y",3);
		List<Variable> variables = List.of(x,y);
		Factor numeric = Factor.dense(List.of(x),0d,0d,0d);
		Factor secondNumeric = Factor.dense(List.of(y),0d,0d,0d);
		double[] support = new double[9];
		Arrays.fill(support,Double.POSITIVE_INFINITY);
		// Scope is [y,x]. Relation order visits (x=2,y=0), union cell 6,
		// before (x=0,y=1), union cell 1. The canonical tie is still cell 1.
		support[2] = 0d;
		support[3] = 0d;
		Factor hard = Factor.dense(List.of(y,x),support);
		List<BoundaryMessage> leaves = ExactCategoricalSolver.boundaryLeaves(
			variables,List.of(numeric,secondNumeric,hard),LIMITS);
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();

		BoundaryMessage merged = ExactCategoricalSolver.mergeBoundary(
			leaves,List.of(),LIMITS,counters);

		Assert.assertEquals(0d,merged.minimum(),0d);
		int[] selected = {2,2};
		merged.decodeInto(selected,variables);
		Assert.assertArrayEquals(new int[] {0,1},selected);
		Assert.assertEquals(27L,counters.fullChildEvaluations());
		Assert.assertEquals(6L,counters.childEvaluations());
	}

	@Test
	public void hardSupportPositionPreservesNumericOrderRawCostsBoundsAndDecodedPlan() {
		Variable a = new Variable("hard-order-a",2);
		Variable b = new Variable("hard-order-b",2);
		Variable c = new Variable("hard-order-c",2);
		List<Variable> variables = List.of(a,b,c);
		Factor first = Factor.dense(List.of(a,c),0x1p53,7d,0x1p53,2d);
		Factor second = Factor.dense(List.of(b,c),1d,0x1p-53,3d,0x1p-52);
		Factor hard = Factor.dense(List.of(b,a),0d,Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY,0d);

		BoundaryMessage middle = merge(variables,List.of(first,hard,second),List.of(c));
		BoundaryMessage last = merge(variables,List.of(first,second,hard),List.of(c));
		for(int cValue=0; cValue<c.domainSize(); cValue++) {
			int[] boundary = {0,0,cValue};
			Assert.assertEquals(Double.doubleToRawLongBits(middle.valueForAssignment(boundary,variables)),
				Double.doubleToRawLongBits(last.valueForAssignment(boundary,variables)));
			Assert.assertEquals(Double.doubleToRawLongBits(middle.lowerMinMarginals(c)[cValue]),
				Double.doubleToRawLongBits(last.lowerMinMarginals(c)[cValue]));
		}
		Assert.assertEquals(Double.doubleToRawLongBits(middle.minimum()),
			Double.doubleToRawLongBits(last.minimum()));
		Assert.assertEquals(Double.doubleToRawLongBits(middle.lowerBound()),
			Double.doubleToRawLongBits(last.lowerBound()));

		ExactCategoricalSolver.Result oracle = ExactCategoricalSolver.solve(
			variables,List.of(first,hard,second),LIMITS);
		Assert.assertEquals(Double.doubleToRawLongBits(oracle.objective()),
			Double.doubleToRawLongBits(middle.minimum()));
		int[] selected = {0,0,oracle.assignmentInVariableOrder().get(2)};
		middle.decodeInto(selected,variables);
		Assert.assertEquals(oracle.assignmentInVariableOrder(),Arrays.stream(selected).boxed().toList());
	}

	@Test
	public void mergedHardMessageRemainsAnExactSupportRelation() {
		Variable x = new Variable("propagated-hard-x",3);
		Variable y = new Variable("propagated-hard-y",3);
		Variable z = new Variable("propagated-hard-z",3);
		List<Variable> variables = List.of(x,y,z);
		double[] diagonal = new double[9];
		Arrays.fill(diagonal,Double.POSITIVE_INFINITY);
		diagonal[0] = 0d;
		diagonal[4] = 0d;
		diagonal[8] = 0d;
		List<BoundaryMessage> hardLeaves = ExactCategoricalSolver.boundaryLeaves(variables,List.of(
			Factor.dense(List.of(x,y),diagonal),Factor.dense(List.of(y,z),diagonal)),LIMITS);
		BoundaryMessage propagated = ExactCategoricalSolver.mergeBoundary(
			hardLeaves,List.of(x,z),LIMITS);
		BoundaryMessage numeric = ExactCategoricalSolver.boundaryLeaf(variables,
			Factor.dense(List.of(x),0d,0d,0d),LIMITS);
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();

		BoundaryMessage scalar = ExactCategoricalSolver.mergeBoundary(
			List.of(numeric,propagated),List.of(),LIMITS,counters);

		Assert.assertEquals(18L,counters.fullChildEvaluations());
		Assert.assertEquals(4L,counters.childEvaluations());
		int[] selected = {2,2,2};
		scalar.decodeInto(selected,variables);
		Assert.assertArrayEquals(new int[] {0,0,0},selected);
	}

	@Test
	public void randomizedSparseHardMergeMatchesExactConditionalOracle() {
		Random random = new Random(70231);
		for(int trial=0; trial<30; trial++) {
			Variable a = new Variable("random-hard-a-"+trial,3);
			Variable b = new Variable("random-hard-b-"+trial,3);
			Variable c = new Variable("random-hard-c-"+trial,2);
			List<Variable> variables = List.of(a,b,c);
			double[] firstValues = new double[6];
			double[] secondValues = new double[6];
			for(int cell=0; cell<6; cell++) {
				firstValues[cell] = random.nextInt(17) + (cell%2==0 ? 0x1p-52 : 0d);
				secondValues[cell] = random.nextInt(13) + (cell%3==0 ? 0x1p-53 : 0d);
			}
			Factor first = Factor.dense(List.of(a,c),firstValues);
			Factor second = Factor.dense(List.of(b,c),secondValues);
			double[] support = new double[9];
			Arrays.fill(support,Double.POSITIVE_INFINITY);
			for(int cell=0; cell<support.length; cell++)
				if(random.nextInt(4)==0)
					support[cell] = 0d;
			support[random.nextInt(support.length)] = 0d;
			Factor hard = Factor.dense(List.of(b,a),support);
			List<Factor> factors = List.of(first,hard,second);
			BoundaryMessage merged = merge(variables,factors,List.of(c));

			for(int cValue=0; cValue<c.domainSize(); cValue++) {
				Factor pin = Factor.dense(List.of(c),cValue==0 ? 0d : Double.POSITIVE_INFINITY,
					cValue==1 ? 0d : Double.POSITIVE_INFINITY);
				ExactCategoricalSolver.Result oracle = ExactCategoricalSolver.solve(
					variables,List.of(first,hard,second,pin),LIMITS);
				int[] boundary = {0,0,cValue};
				double actual = merged.valueForAssignment(boundary,variables);
				Assert.assertEquals(Double.doubleToRawLongBits(oracle.objective()),
					Double.doubleToRawLongBits(actual));
				Assert.assertEquals(Double.doubleToRawLongBits(actual),
					Double.doubleToRawLongBits(merged.lowerMinMarginals(c)[cValue]));
				merged.decodeInto(boundary,variables);
				Assert.assertEquals(cValue,boundary[2]);
				Assert.assertEquals(Double.doubleToRawLongBits(actual),Double.doubleToRawLongBits(
					RegionalSearchProblem.evaluateFactors(variables,factors,
						Arrays.stream(boundary).boxed().toList())));
			}
		}
	}

	private static BoundaryMessage merge(List<Variable> variables, List<Factor> factors,
		List<Variable> boundary) {
		List<BoundaryMessage> leaves = new ArrayList<>(
			ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS));
		return ExactCategoricalSolver.mergeBoundary(leaves,boundary,LIMITS);
	}
}
