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

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMergeCounters;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

public class MixedHardBoundaryProjectionTest {
	private static final Limits LIMITS = new Limits(2_000_000,20_000_000);

	@Test
	public void functionalHardAndNumericResponseClassesUseOnlyProjectedRowsInBothOrders()
		throws Exception {
		Variable x = new Variable("mixed-functional-x",128);
		Variable y = new Variable("mixed-functional-y",8);
		List<Variable> variables = List.of(x,y);
		int[] mapping = new int[x.domainSize()];
		double[] high = new double[x.domainSize()];
		double[] residue = new double[x.domainSize()];
		for(int value=0; value<x.domainSize(); value++) {
			mapping[value] = value % y.domainSize();
			high[value] = 0x1p53 + value % y.domainSize() * 4d;
			residue[value] = 1d;
		}
		Factor functional = Factor.functionalMap(x,y,mapping);
		Factor first = Factor.dense(List.of(x),high);
		Factor second = Factor.dense(List.of(x),residue);

		for(List<Factor> factors : List.of(List.of(functional,first,second),
			List.of(first,second,functional))) {
			BoundaryMergeCounters counters = new BoundaryMergeCounters();
			BoundaryMessage merged = merge(variables,factors,List.of(x),counters);
			Assert.assertEquals(3L * x.domainSize() * y.domainSize(),counters.fullChildEvaluations());
			Assert.assertEquals(3L * y.domainSize(),counters.childEvaluations());
			for(int value=0; value<x.domainSize(); value++) {
				int[] assignment = {value,0};
				double expected = high[value] + residue[value];
				Assert.assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(
					merged.valueForAssignment(assignment,variables)));
				Assert.assertEquals(Double.doubleToRawLongBits(expected),Double.doubleToRawLongBits(
					merged.lowerMinMarginals(x)[value]));
				Assert.assertArrayEquals(new long[] {Double.doubleToRawLongBits(high[value]),
					Double.doubleToRawLongBits(1d)},rawStoredCost(merged,assignment));
				merged.decodeInto(assignment,variables);
				Assert.assertArrayEquals(new int[] {value,mapping[value]},assignment);
			}
			var storageClasses = BoundaryMessage.class.getDeclaredField("storageClasses");
			storageClasses.setAccessible(true);
			Assert.assertNotNull("the mixed merge must retain its exact response quotient",
				storageClasses.get(merged));

			// Feed the compressed numeric message back through the mixed lane with a
			// typed all-feasible relation. No logical boundary value may be lost.
			double[] allZero = new double[x.domainSize()];
			BoundaryMergeCounters compressedCounters = new BoundaryMergeCounters();
			BoundaryMessage compressed = ExactCategoricalSolver.mergeBoundary(List.of(merged,
				ExactCategoricalSolver.boundaryLeaf(variables,Factor.dense(List.of(x),allZero),LIMITS)),
				List.of(x),LIMITS,compressedCounters);
			Assert.assertEquals(2L * y.domainSize(),compressedCounters.childEvaluations());
			for(int value=0; value<x.domainSize(); value++)
				Assert.assertEquals(Double.doubleToRawLongBits(high[value] + 1d),
					Double.doubleToRawLongBits(compressed.valueForAssignment(
						new int[] {value,0},variables)));
		}
	}

	@Test
	public void packedPermutedHardAndSingletonMatchFullEnumerationAndCanonicalTie()
		throws Exception {
		Variable x = new Variable("mixed-packed-x",20);
		Variable y = new Variable("mixed-packed-y",4);
		Variable one = new Variable("mixed-packed-one",1);
		List<Variable> variables = List.of(x,y,one);
		double[] packed = new double[y.domainSize() * x.domainSize()];
		Arrays.fill(packed,Double.POSITIVE_INFINITY);
		for(int value=0; value<x.domainSize(); value++)
			packed[(value % y.domainSize()) * x.domainSize() + value] = 0d;
		double[] numeric = new double[x.domainSize()];
		for(int value=0; value<numeric.length; value++)
			numeric[value] = value % y.domainSize();
		List<Factor> factors = List.of(Factor.dense(List.of(y,x),packed),
			Factor.dense(List.of(x),numeric),Factor.dense(List.of(one),0d));
		BoundaryMergeCounters counters = new BoundaryMergeCounters();
		BoundaryMessage merged = merge(variables,factors,List.of(x,one),counters);
		Assert.assertEquals(3L * x.domainSize() * y.domainSize(),counters.fullChildEvaluations());
		Assert.assertEquals(3L * y.domainSize(),counters.childEvaluations());
		var storageClasses = BoundaryMessage.class.getDeclaredField("storageClasses");
		storageClasses.setAccessible(true);
		Assert.assertNotNull(storageClasses.get(merged));
		for(int value=0; value<x.domainSize(); value++) {
			int[] assignment = {value,3,0};
			Assert.assertEquals(Double.doubleToRawLongBits(numeric[value]),Double.doubleToRawLongBits(
				merged.valueForAssignment(assignment,variables)));
			Assert.assertEquals(Double.doubleToRawLongBits(numeric[value]),Double.doubleToRawLongBits(
				merged.lowerMinMarginals(x)[value]));
			Assert.assertArrayEquals(new long[] {Double.doubleToRawLongBits(numeric[value]),0L},
				rawStoredCost(merged,assignment));
			merged.decodeInto(assignment,variables);
			Assert.assertArrayEquals(new int[] {value,value % y.domainSize(),0},assignment);
		}
		BoundaryMessage scalar = merge(variables,factors,List.of(),new BoundaryMergeCounters());
		int[] selected = {11,3,0};
		scalar.decodeInto(selected,variables);
		Assert.assertArrayEquals(new int[] {0,0,0},selected);
	}

	@Test
	public void laterTypedHardInfinityCannotHideEarlierOverflow() {
		Variable x = new Variable("mixed-overflow-x",65);
		Variable y = new Variable("mixed-overflow-y",2);
		List<Variable> variables = List.of(x,y);
		double[] first = new double[x.domainSize()];
		double[] second = new double[x.domainSize()];
		Arrays.fill(first,Double.MAX_VALUE);
		Arrays.fill(second,Double.MAX_VALUE);
		first[1] = second[1] = 1d;
		int[] mapping = new int[x.domainSize()];
		Arrays.fill(mapping,-1);
		mapping[1] = 0;
		try {
			merge(variables,List.of(Factor.dense(List.of(x),first),Factor.dense(List.of(x),second),
				Factor.functionalMap(x,y,mapping)),List.of(),new BoundaryMergeCounters());
			Assert.fail("A later forbidden typed-hard row must not hide ordered numeric overflow");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW",expected.getMessage());
		}
	}

	@Test
	public void allForbiddenAndNegativeValidationRemainExact() {
		Variable x = new Variable("mixed-edge-x",65);
		List<Variable> variables = List.of(x);
		double[] zero = new double[x.domainSize()];
		double[] forbidden = new double[x.domainSize()];
		Arrays.fill(forbidden,Double.POSITIVE_INFINITY);
		BoundaryMessage infeasible = merge(variables,List.of(Factor.dense(List.of(x),zero),
			Factor.dense(List.of(x),forbidden)),List.of(x),new BoundaryMergeCounters());
		for(int value=0; value<x.domainSize(); value++)
			Assert.assertEquals(Double.POSITIVE_INFINITY,
				infeasible.valueForAssignment(new int[] {value},variables),0d);
		try {
			infeasible.decodeInto(new int[] {0},variables);
			Assert.fail("An all-forbidden mixed result must have no backpointer");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("INCREMENTAL_MESSAGE_BOUNDARY_INFEASIBLE",expected.getMessage());
		}

		double[] negative = zero.clone();
		negative[0] = -1d;
		try {
			ExactCategoricalSolver.boundaryLeaves(variables,List.of(Factor.dense(List.of(x),negative),
				Factor.dense(List.of(x),zero)),LIMITS);
			Assert.fail("Negative boundary costs must retain their validation failure");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("INCREMENTAL_MESSAGE_COST_INVALID|value=-1.0",expected.getMessage());
		}
	}

	@Test
	public void unrelatedCompressedAxisDoesNotScanFunctionalCartesianStorage() {
		Variable x = new Variable("mixed-input-identity-x",128);
		Variable y = new Variable("mixed-input-identity-y",128);
		Variable z = new Variable("mixed-input-identity-z",8);
		List<Variable> variables = List.of(x,y,z);
		int[] identity = new int[x.domainSize()];
		for(int value=0; value<identity.length; value++)
			identity[value] = value;
		BoundaryMergeCounters counters = new BoundaryMergeCounters();
		BoundaryMessage merged = merge(variables,List.of(Factor.functionalMap(x,y,identity),
			Factor.dense(List.of(z),new double[z.domainSize()])),List.of(x),counters);

		Assert.assertEquals(2L * x.domainSize(),counters.childEvaluations());
		Assert.assertEquals("only the unrelated all-feasible unary row is classified",
			1L,counters.supportCellsExamined());
		for(int value : new int[] {0,1,63,127}) {
			int[] assignment = {value,0,7};
			Assert.assertEquals(0d,merged.valueForAssignment(assignment,variables),0d);
			merged.decodeInto(assignment,variables);
			Assert.assertArrayEquals(new int[] {value,value,0},assignment);
		}
	}

	@Test
	public void compressedFunctionalAxisProjectsFiniteRowsInsteadOfScanningQuotientTable() {
		Variable x = new Variable("mixed-projected-support-x",4096);
		Variable y = new Variable("mixed-projected-support-y",2048);
		List<Variable> variables = List.of(x,y);
		int[] mapping = new int[x.domainSize()];
		double[] numeric = new double[x.domainSize()];
		for(int value=0; value<x.domainSize(); value++) {
			mapping[value] = value % y.domainSize();
			numeric[value] = mapping[value];
		}
		Limits large = new Limits(10_000_000,40_000_000);
		BoundaryMergeCounters counters = new BoundaryMergeCounters();
		List<BoundaryMessage> leaves = ExactCategoricalSolver.boundaryLeaves(variables,List.of(
			Factor.functionalMap(x,y,mapping),Factor.dense(List.of(x),numeric)),large);
		BoundaryMessage merged = ExactCategoricalSolver.mergeBoundary(leaves,List.of(x),large,counters);

		Assert.assertEquals(2L * y.domainSize(),counters.childEvaluations());
		Assert.assertEquals("the hard 2048x2048 quotient table must not be classified",
			1025L,counters.supportCellsExamined());
		for(int value : new int[] {0,1,2047,2048,4095}) {
			int[] assignment = {value,0};
			Assert.assertEquals(numeric[value],merged.valueForAssignment(assignment,variables),0d);
			merged.decodeInto(assignment,variables);
			Assert.assertArrayEquals(new int[] {value,mapping[value]},assignment);
		}
	}

	private static BoundaryMessage merge(List<Variable> variables, List<Factor> factors,
		List<Variable> boundary, BoundaryMergeCounters counters) {
		List<BoundaryMessage> leaves = new ArrayList<>(
			ExactCategoricalSolver.boundaryLeaves(variables,factors,LIMITS));
		return ExactCategoricalSolver.mergeBoundary(leaves,boundary,LIMITS,counters);
	}

	private static long[] rawStoredCost(BoundaryMessage message, int[] assignment) throws Exception {
		var boundaryCell = BoundaryMessage.class.getDeclaredMethod("boundaryCell",int[].class);
		boundaryCell.setAccessible(true);
		int cell = (int)boundaryCell.invoke(message,(Object)assignment);
		var values = BoundaryMessage.class.getDeclaredField("values");
		var lows = BoundaryMessage.class.getDeclaredField("lowValues");
		values.setAccessible(true);
		lows.setAccessible(true);
		return new long[] {Double.doubleToRawLongBits(((double[])values.get(message))[cell]),
			Double.doubleToRawLongBits(((double[])lows.get(message))[cell])};
	}
}
