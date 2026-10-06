/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.BranchActivationFact;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact;
import org.junit.Test;

public class ExactDerivedSupplySharingTest {
	@Test
	public void selectedSharingUsesExactSourceAndDemandMasks() {
		var source = new ExactCategoricalSolver.Variable("source", 2);
		var consumer = new ExactCategoricalSolver.Variable("consumer", 2);
		var repeated = new ExactPhysicalCostModel.ActivationDemand(List.of(consumer),
			List.of(new boolean[] {false, true}),
			new ExactMaterializationActivation.Event(1d, List.of()), true);
		var group = new ExactPhysicalCostModel.SupplySharingGroup("REFED|value-v1|ROW|anchor|scope",
			source, new boolean[] {true, false}, List.of(repeated));
		var positions = new IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		positions.put(source, 0);
		positions.put(consumer, 1);

		assertTrue(group.selectedRequiresCrossExecutionRetention(List.of(0, 1), positions));
		assertEquals(false, group.selectedRequiresCrossExecutionRetention(List.of(0, 0), positions));
		assertEquals(false, group.selectedRequiresCrossExecutionRetention(List.of(1, 1), positions));
	}

	@Test
	public void samePlacementDoesNotMergeDifferentVersionsOrSingleUse() {
		var source = new ExactCategoricalSolver.Variable("source", 1);
		var consumer = new ExactCategoricalSolver.Variable("consumer", 1);
		var selected = new ExactPhysicalCostModel.ActivationDemand(List.of(consumer),
			List.of(new boolean[] {true}),
			new ExactMaterializationActivation.Event(1d, List.of()), true);
		var singleUse = new ExactPhysicalCostModel.ActivationDemand(List.of(consumer),
			List.of(new boolean[] {true}),
			new ExactMaterializationActivation.Event(1d, List.of()), false);
		var first = new ExactPhysicalCostModel.SupplySharingGroup("REFED|value-v1|ROW|anchor|scope",
			source, new boolean[] {true}, List.of(selected));
		var second = new ExactPhysicalCostModel.SupplySharingGroup("REFED|value-v2|ROW|anchor|scope",
			source, new boolean[] {true}, List.of(selected));
		var ordinary = new ExactPhysicalCostModel.SupplySharingGroup("REFED|value-v3|ROW|anchor|scope",
			source, new boolean[] {true}, List.of(singleUse));
		var positions = new IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		positions.put(source, 0);
		positions.put(consumer, 1);

		Set<String> retained = new java.util.TreeSet<>();
		for(var group : List.of(first, second, ordinary))
			if(group.selectedRequiresCrossExecutionRetention(List.of(0, 0), positions))
				retained.add(group.physicalEmissionIdentity());
		assertEquals(Set.of(first.physicalEmissionIdentity(), second.physicalEmissionIdentity()), retained);
	}

	@Test
	public void conditionalInnerLoopSharesOneInvariantFoutSupplyAtItsActivationCost() {
		var sourceProfile = new OccurrenceProfileFact(1d, List.of(), 0);
		var consumerProfile = new OccurrenceProfileFact(1d, List.of(Pair.of(7L, 2d)), 0,
			List.of(new BranchActivationFact("outer", true, 0.5d, List.of())));
		var source = new ExactCategoricalSolver.Variable("fout-source", 2);
		var consumer = new ExactCategoricalSolver.Variable("loop-consumer", 2);
		var activation = ExactPhysicalCostModel.materializationActivation(sourceProfile, consumerProfile);
		var demand = new ExactPhysicalCostModel.ActivationDemand(List.of(consumer),
			List.of(new boolean[] {false, true}), activation,
			ExactPhysicalCostModel.requiresCrossExecutionReuse(sourceProfile, consumerProfile));
		var group = new ExactPhysicalCostModel.SupplySharingGroup(
			"REFED|invariant-fout|ROW|anchor|scope", source,
			new boolean[] {false, true}, List.of(demand));
		var positions = new IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		positions.put(source, 0);
		positions.put(consumer, 1);
		assertTrue(group.selectedRequiresCrossExecutionRetention(List.of(1, 1), positions));
		assertEquals(0.5d, activation.weight(), 0d);

		var factors = new java.util.ArrayList<ExactCategoricalSolver.Factor>();
		var decompositions = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addMaterializationActivationFactors("conditional-fout", source,
			new boolean[] {false, true}, new double[] {10d, 10d}, List.of(demand), 1d,
			factors, decompositions, null);
		assertEquals(5d, ExactCategoricalSolver.evaluate(List.of(source, consumer), factors,
			new ExactCategoricalSolver.Limits(16, 16), List.of(1, 1)), 0d);
	}
}
