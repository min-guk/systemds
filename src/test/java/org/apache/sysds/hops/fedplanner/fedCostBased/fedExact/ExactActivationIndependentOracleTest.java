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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/** Independent finite-execution oracle and an encoding-only diagnostic ablation. */
public class ExactActivationIndependentOracleTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void randomStructuredTreesMatchEnumeratedCreationEvents() throws Exception {
		Random random = new Random(20260907L);
		int comparisons = 0;
		for(int trial = 0; trial < 32; trial++) {
			List<String> prefixes = new ArrayList<>();
			List<ExactMaterializationActivation.Event> events = new ArrayList<>();
			for(int demand = 0; demand < 5; demand++) {
				int depth = random.nextInt(5);
				String prefix = bits(random.nextInt(16)).substring(0, depth);
				prefixes.add(prefix);
				List<ExactPhysicalCostModel.BranchLiteral> conditions = new ArrayList<>();
				for(int level = 0; level < depth; level++)
					conditions.add(literal("decision/" + prefix.substring(0, level),
						prefix.charAt(level) == '1'));
				events.add(new ExactMaterializationActivation.Event(
					(double) (1 << (4 - depth)) / 16, conditions));
			}
			Assert.assertTrue(ExactMaterializationActivation.partition(events, 1).resolved());
			Fixture fixture = assemble(events, 16);
			for(int mask = 0; mask < 32; mask++) {
				int creations = 0;
				for(int execution = 0; execution < 16; execution++) {
					boolean demanded = false;
					for(int demand = 0; demand < prefixes.size(); demand++)
						demanded |= (mask & (1 << demand)) != 0
							&& bits(execution).startsWith(prefixes.get(demand));
					if(demanded)
						creations++;
				}
				for(int source = 0; source < 2; source++) {
					List<Integer> values = new ArrayList<>(List.of(source));
					for(int demand = 0; demand < 5; demand++)
						values.add((mask >> demand) & 1);
					double expected = source == 0 ? 0 : creations;
					Assert.assertEquals("trial=" + trial + ", mask=" + mask, expected,
						ExactCategoricalSolver.evaluate(fixture.originals, fixture.canonical,
							LIMITS, values), 0);
					List<ExactCategoricalSolver.Factor> fixed = new ArrayList<>(fixture.factored);
					for(int index = 0; index < values.size(); index++) {
						int value = values.get(index);
						fixed.add(ExactCategoricalSolver.Factor.lazy(List.of(fixture.originals.get(index)),
							assignment -> assignment[0] == value ? 0 : Double.POSITIVE_INFINITY));
					}
					Assert.assertEquals(expected, ExactCategoricalSolver.solve(
						fixture.augmented, fixed, LIMITS).objective(), 0);
					comparisons++;
				}
			}
		}
		write("independent-structured-oracle.txt", "seed=20260907\ntrees=32\n"
			+ "executions_per_tree=16\ncanonical_and_solver_checks=" + comparisons + "\n");
	}

	@Test
	public void unknownCorrelationsBoundExplicitDynamicUnions() throws Exception {
		Random random = new Random(20260908L);
		int comparisons = 0;
		for(int trial = 0; trial < 64; trial++) {
			int[] executions = new int[5];
			List<ExactMaterializationActivation.Event> events = new ArrayList<>();
			for(int demand = 0; demand < 5; demand++) {
				executions[demand] = random.nextInt(65535) + 1;
				events.add(new ExactMaterializationActivation.Event(
					Integer.bitCount(executions[demand]) / 16d,
					List.of(literal("unknown-" + demand, true))));
			}
			for(int mask = 0; mask < 32; mask++) {
				int union = 0;
				double largest = 0;
				boolean[] active = new boolean[5];
				for(int demand = 0; demand < 5; demand++) {
					active[demand] = (mask & (1 << demand)) != 0;
					if(active[demand]) {
						union |= executions[demand];
						largest = Math.max(largest, events.get(demand).weight());
					}
				}
				double estimate = ExactMaterializationActivation.conservativeUnion(events, 1, active);
				Assert.assertTrue(estimate >= Integer.bitCount(union) / 16d);
				Assert.assertTrue(estimate >= largest && estimate <= 1);
				comparisons++;
			}
		}
		write("independent-unknown-oracle.txt", "seed=20260908\n"
			+ "dynamic_event_sets=64\nsubset_bound_checks=" + comparisons + "\n");
	}

	@Test
	public void sameObjectiveWithAndWithoutOrAuxiliaries() throws Exception {
		StringBuilder report = new StringBuilder("case\tclasses\taux_variables\t"
			+ "canonical_width\tor_width\tcanonical_max_cells\tor_max_cells\t"
			+ "canonical_materialized_cells\tor_materialized_cells\tobjective\tassignment_equal\n");
		for(boolean exclusive : List.of(false, true)) {
			List<ExactMaterializationActivation.Event> events = new ArrayList<>();
			for(int demand = 0; demand < 16; demand++)
				events.add(exclusive ? new ExactMaterializationActivation.Event(0.5,
					List.of(literal("branch", demand < 8)))
					: new ExactMaterializationActivation.Event(1, List.of()));
			Fixture fixture = assemble(events, 5);
			List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>(fixture.canonical);
			List<ExactCategoricalSolver.Factor> factored = new ArrayList<>(fixture.factored);
			for(int index = 0; index < fixture.originals.size(); index++) {
				final double price = index / 4d;
				final boolean source = index == 0;
				var preference = ExactCategoricalSolver.Factor.lazy(List.of(fixture.originals.get(index)),
					values -> values[0] == 1 ? 0 : source ? Double.POSITIVE_INFINITY : price);
				canonical.add(preference);
				factored.add(preference);
			}
			var plain = ExactCategoricalSolver.solve(fixture.originals, canonical, LIMITS);
			var encoded = ExactCategoricalSolver.solve(fixture.augmented, factored, LIMITS);
			Assert.assertEquals(Double.doubleToRawLongBits(plain.objective()),
				Double.doubleToRawLongBits(encoded.objective()));
			Assert.assertEquals(plain.assignmentInVariableOrder(),
				encoded.assignmentInVariableOrder().subList(0, fixture.originals.size()));
			report.append(exclusive ? "exclusive_16" : "coactive_16").append('\t')
				.append(fixture.canonical.size()).append('\t')
				.append(fixture.augmented.size() - fixture.originals.size()).append('\t')
				.append(plain.statistics().inducedWidth()).append('\t')
				.append(encoded.statistics().inducedWidth()).append('\t')
				.append(plain.statistics().maximumFactorCells()).append('\t')
				.append(encoded.statistics().maximumFactorCells()).append('\t')
				.append(plain.statistics().materializedFactorCells()).append('\t')
				.append(encoded.statistics().materializedFactorCells()).append('\t')
				.append(encoded.objective()).append("\ttrue\n");
		}
		write("or-encoding-ablation.tsv", report.toString());
	}

	private static Fixture assemble(List<ExactMaterializationActivation.Event> events, double unit) {
		var source = new ExactCategoricalSolver.Variable("source", 2);
		List<ExactCategoricalSolver.Variable> originals = new ArrayList<>(List.of(source));
		List<ExactPhysicalCostModel.ActivationDemand> demands = new ArrayList<>();
		for(int index = 0; index < events.size(); index++) {
			var consumer = new ExactCategoricalSolver.Variable("consumer-" + index, 2);
			originals.add(consumer);
			demands.add(new ExactPhysicalCostModel.ActivationDemand(List.of(consumer),
				List.of(new boolean[] {false, true}), events.get(index)));
		}
		List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>();
		var mappings = new IdentityHashMap<ExactCategoricalSolver.Factor,ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addMaterializationActivationFactors("oracle", source,
			new boolean[] {false, true}, new double[] {unit, unit}, demands, 1,
			canonical, mappings, null);
		List<ExactCategoricalSolver.Variable> augmented = new ArrayList<>(originals);
		List<ExactCategoricalSolver.Factor> factored = new ArrayList<>();
		for(var contribution : canonical) {
			augmented.addAll(mappings.get(contribution).auxiliaryVariables());
			factored.addAll(mappings.get(contribution).factors());
		}
		return new Fixture(originals, canonical, augmented, factored);
	}

	private static String bits(int value) {
		return Integer.toBinaryString(value | 16).substring(1);
	}

	private static ExactPhysicalCostModel.BranchLiteral literal(String path, boolean arm) {
		return new ExactPhysicalCostModel.BranchLiteral(path, arm);
	}

	private static void write(String name, String text) throws Exception {
		String configured = System.getProperty("activation.ablation.output");
		if(configured == null || configured.isBlank())
			return;
		Path directory = Path.of(configured);
		Files.createDirectories(directory);
		Files.writeString(directory.resolve(name), text);
	}

	private record Fixture(List<ExactCategoricalSolver.Variable> originals,
		List<ExactCategoricalSolver.Factor> canonical,
		List<ExactCategoricalSolver.Variable> augmented,
		List<ExactCategoricalSolver.Factor> factored) { }
}
