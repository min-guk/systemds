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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.FrozenInputs;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.PartialHardCostFunction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.PartialTruth;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class ExactFrozenSupportEpochTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);

	@Test
	public void randomFrozenRoundsMatchColdFullScanIncludingFailures() throws Exception {
		Random random = new Random(57493L);
		for(int trial = 0; trial < 400; trial++) {
			List<Variable> variables = new ArrayList<>();
			for(int index = 0; index < 6; index++)
				variables.add(new Variable("random-" + index, 1 + random.nextInt(4)));
			List<Factor> factors = new ArrayList<>();
			for(int index = 0; index < 12; index++) {
				int first = random.nextInt(variables.size());
				int second = (first + 1 + random.nextInt(variables.size() - 1)) % variables.size();
				int third = (second + 1) % variables.size();
				if(third == first) third = (third + 1) % variables.size();
				List<Variable> scope = index % 4 == 0 ? List.of(variables.get(first))
					: index % 4 == 1 ? List.of(variables.get(first), variables.get(second), variables.get(third))
					: List.of(variables.get(first), variables.get(second));
				int cells = scope.stream().mapToInt(Variable::domainSize).reduce(1, Math::multiplyExact);
				double[] values = new double[cells];
				for(int cell = 0; cell < cells; cell++)
					values[cell] = random.nextInt(4) == 0 ? Double.POSITIVE_INFINITY : cell % 2;
				factors.add(Factor.dense(scope, values));
			}
			factors.add(factors.get(2));
			assertFrozenParity(variables, factors);
		}
	}

	@Test
	public void lateUnaryPruningReactivatesEarlierFactorsAcrossManyRounds() throws Exception {
		List<Variable> variables = new ArrayList<>();
		List<Factor> factors = new ArrayList<>();
		for(int index = 0; index < 12; index++) variables.add(new Variable("chain-" + index, 3));
		for(int index = 0; index < 11; index++) factors.add(equality(variables.get(index), variables.get(index + 1)));
		factors.add(factors.get(4));
		factors.add(Factor.dense(List.of(variables.get(11)), Double.POSITIVE_INFINITY, 0d, Double.POSITIVE_INFINITY));
		assertFrozenParity(variables, factors);
		FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(variables, factors, LIMITS);
		boolean[][] active = domains(variables);
		productionFrozen(frozen, active);
		for(boolean[] values : active) Assert.assertArrayEquals(new boolean[] {false, true, false}, values);
	}

	@Test
	public void mixedPartialCallbacksRetainInputsOrderAndExceptionPriority() throws Exception {
		for(int failureAt : new int[] {-1, 19}) {
			List<Variable> variables = List.of(new Variable("a", 3), new Variable("b", 3), new Variable("c", 3));
			List<String> trace = new ArrayList<>();
			PartialHardCostFunction callback = new PartialHardCostFunction() {
				private void visit(String kind, int[] values) {
					trace.add(kind + Arrays.toString(values));
					if(trace.size() == failureAt) throw new IllegalStateException("partial-failure-" + failureAt);
				}
				@Override public PartialTruth partialTruth(int[] values) {
					visit("partial", values); return PartialTruth.UNKNOWN;
				}
				@Override public double cost(int[] values) {
					visit("cost", values); return values[0] == 1 ? 0d : Double.POSITIVE_INFINITY;
				}
			};
			Factor ab = equality(variables.get(0), variables.get(1));
			List<Factor> factors = List.of(ab, equality(variables.get(1), variables.get(2)), ab,
				Factor.lazy(List.of(variables.get(2)), callback));
			List<Factor> materializedFactors = factors.stream().filter(value -> !value.supportsPartialTruth()).toList();
			FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(variables, materializedFactors, LIMITS);
			boolean[][] expected = domains(variables);
			String expectedFailure = failure(() -> legacyMixed(variables, factors, frozen, expected));
			List<String> expectedTrace = List.copyOf(trace); trace.clear();
			boolean[][] actual = domains(variables);
			Assert.assertEquals(expectedFailure, failure(() -> invoke("arcConsistency",
				new Class<?>[] {List.class, List.class, FrozenInputs.class, boolean[][].class},
				variables, factors, frozen, actual)));
			Assert.assertEquals(expectedTrace, trace);
			assertDomains(expected, actual);
		}
	}

	@Test
	public void binaryPartialRemovalsReactivateFrozenOccurrencesWithoutChangingCallbacks() throws Exception {
		for(int failureAt : new int[] {-1, 37}) {
			List<Variable> variables = List.of(new Variable("a", 3), new Variable("b", 3),
				new Variable("c", 3), new Variable("d", 3));
			List<String> trace = new ArrayList<>();
			PartialHardCostFunction callback = new PartialHardCostFunction() {
				private void visit(String kind, int[] values) {
					trace.add(kind + Arrays.toString(values));
					if(trace.size() == failureAt) throw new IllegalStateException("binary-failure-" + failureAt);
				}
				@Override public PartialTruth partialTruth(int[] values) {
					visit("partial", values); return PartialTruth.UNKNOWN;
				}
				@Override public double cost(int[] values) {
					visit("cost", values); return values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY;
				}
			};
			Factor ab = equality(variables.get(0), variables.get(1));
			List<Factor> factors = List.of(ab,
				Factor.lazy(List.of(variables.get(1), variables.get(2)), callback),
				equality(variables.get(2), variables.get(3)), ab,
				Factor.dense(List.of(variables.get(3)), Double.POSITIVE_INFINITY, 0d, Double.POSITIVE_INFINITY));
			FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(variables,
				factors.stream().filter(value -> !value.supportsPartialTruth()).toList(), LIMITS);
			boolean[][] expected = domains(variables);
			String expectedFailure = failure(() -> legacyMixed(variables, factors, frozen, expected));
			List<String> expectedTrace = List.copyOf(trace); trace.clear();
			boolean[][] actual = domains(variables);
			Assert.assertEquals(expectedFailure, failure(() -> invoke("arcConsistency",
				new Class<?>[] {List.class, List.class, FrozenInputs.class, boolean[][].class},
				variables, factors, frozen, actual)));
			Assert.assertEquals(expectedTrace, trace);
			assertDomains(expected, actual);
			if(failureAt < 0)
				for(boolean[] domain : actual) Assert.assertArrayEquals(new boolean[] {false, true, false}, domain);
		}
	}

	private static Factor equality(Variable left, Variable right) {
		double[] values = new double[left.domainSize() * right.domainSize()];
		for(int a = 0; a < left.domainSize(); a++)
			for(int b = 0; b < right.domainSize(); b++)
				values[a * right.domainSize() + b] = a == b ? 0d : Double.POSITIVE_INFINITY;
		return Factor.dense(List.of(left, right), values);
	}

	private static void assertFrozenParity(List<Variable> variables, List<Factor> factors) throws Exception {
		FrozenInputs frozen = ExactCategoricalSolver.freezeInputs(variables, factors, LIMITS);
		boolean[][] expected = domains(variables), actual = domains(variables);
		Assert.assertEquals(failure(() -> legacyFrozen(frozen, expected)),
			failure(() -> productionFrozen(frozen, actual)));
		assertDomains(expected, actual);
	}

	private static void assertDomains(boolean[][] expected, boolean[][] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for(int variable = 0; variable < expected.length; variable++)
			Assert.assertArrayEquals("domain=" + variable, expected[variable], actual[variable]);
	}

	private static boolean[][] domains(List<Variable> variables) {
		boolean[][] values = new boolean[variables.size()][];
		for(int index = 0; index < values.length; index++) {
			values[index] = new boolean[variables.get(index).domainSize()]; Arrays.fill(values[index], true);
		}
		return values;
	}

	private static void productionFrozen(FrozenInputs frozen, boolean[][] active) throws Exception {
		invoke("arcConsistency", new Class<?>[] {FrozenInputs.class, boolean[][].class}, frozen, active);
	}

	private static void legacyFrozen(FrozenInputs frozen, boolean[][] active) throws Exception {
		boolean changed;
		do {
			changed = false;
			for(int factor = 0; factor < frozen.factorCount(); factor++)
				changed |= (Boolean)invoke("reviseFrozenSupport",
					new Class<?>[] {FrozenInputs.class, int.class, boolean[][].class}, frozen, factor, active);
			checkEmpty(active);
		} while(changed);
	}

	private static void legacyMixed(List<Variable> variables, List<Factor> factors, FrozenInputs frozen,
		boolean[][] active) throws Exception {
		Map<Variable,Integer> indexes = new LinkedHashMap<>();
		for(int variable = 0; variable < variables.size(); variable++) indexes.put(variables.get(variable), variable);
		boolean changed;
		do {
			changed = false; int ordinal = 0;
			for(Factor factor : factors) {
				if(factor.supportsPartialTruth())
					changed |= (Boolean)invoke("revisePartialHardFactor",
						new Class<?>[] {Factor.class, int[].class, boolean[][].class}, factor,
						factor.scope().stream().mapToInt(indexes::get).toArray(), active);
				else changed |= (Boolean)invoke("reviseFrozenSupport",
					new Class<?>[] {FrozenInputs.class, int.class, boolean[][].class}, frozen, ordinal++, active);
			}
			checkEmpty(active);
		} while(changed);
	}

	private static void checkEmpty(boolean[][] active) {
		for(boolean[] domain : active) {
			boolean any = false; for(boolean value : domain) any |= value;
			if(!any) throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
		}
	}

	private interface Action { void run() throws Exception; }
	private static String failure(Action action) throws Exception {
		try { action.run(); return null; }
		catch(IllegalArgumentException | IllegalStateException expected) {
			return expected.getClass().getName() + ":" + expected.getMessage();
		}
	}

	private static Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
		Method method = ExactPhysicalReducedSolver.class.getDeclaredMethod(name, types); method.setAccessible(true);
		try { return method.invoke(null, arguments); }
		catch(InvocationTargetException failure) {
			if(failure.getCause() instanceof Exception cause) throw cause;
			throw failure;
		}
	}
}
