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

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ExactMaterializationActivationTest {
	private static final ExactPhysicalCostModel.BranchLiteral A_IF = literal("a", true);
	private static final ExactPhysicalCostModel.BranchLiteral A_ELSE = literal("a", false);
	private static final ExactPhysicalCostModel.BranchLiteral B_IF = literal("b", true);
	private static final ExactPhysicalCostModel.BranchLiteral C_IF = literal("c", true);

	@Test
	public void coactiveDemandsShareMaximumAcrossEveryActiveSubset() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.5, A_IF), event(0.5, A_IF));
		var partition = ExactMaterializationActivation.partition(events, 1d);
		Assert.assertTrue(partition.resolved());
		Assert.assertEquals(1, partition.classes().size());
		Assert.assertEquals(List.of(0, 1), partition.classes().get(0).demandIndexes());
		assertEverySubset(partition, events, 1d, new double[] {0d, 0.5, 0.5, 0.5});
	}

	@Test
	public void mutuallyExclusiveDemandsAddAcrossEveryActiveSubset() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.5, A_IF), event(0.5, A_ELSE));
		var partition = ExactMaterializationActivation.partition(events, 1d);
		Assert.assertTrue(partition.resolved());
		assertEverySubset(partition, events, 1d, new double[] {0d, 0.5, 0.5, 1d});
	}

	@Test
	public void nestedEventsProduceAncestorAndResidualMembership() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.6, A_IF), event(0.2, A_IF, B_IF));
		var partition = ExactMaterializationActivation.partition(events, 1d);
		Assert.assertTrue(partition.resolved());
		assertEverySubset(partition, events, 1d, new double[] {0d, 0.6, 0.2, 0.6});
		Assert.assertTrue(partition.classes().stream().anyMatch(activationClass ->
			activationClass.multiplicity() == 0.2
				&& activationClass.demandIndexes().equals(List.of(0, 1))));
	}

	@Test
	public void unequalWeightsForSameConditionsRequireConservativeFallback() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.6, A_IF), event(0.4, A_IF));
		var partition = ExactMaterializationActivation.partition(events, 1d);
		Assert.assertFalse(partition.resolved());
		Assert.assertTrue(partition.classes().isEmpty());
		Assert.assertTrue(partition.semanticDescriptor().startsWith(
			"CONSERVATIVE_UNION_BOUND_V1"));
		Assert.assertEquals(0.6, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {true, false}), 0d);
		Assert.assertEquals(0.4, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {false, true}), 0d);
		Assert.assertEquals(1d, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {true, true}), 0d);
	}

	@Test
	public void zeroEventsNeitherSubsumeNorCreateClasses() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0d), event(0.5, A_IF));
		var partition = ExactMaterializationActivation.partition(events, 1d);
		Assert.assertTrue(partition.resolved());
		assertEverySubset(partition, events, 1d, new double[] {0d, 0d, 0.5, 0.5});
		Assert.assertTrue(partition.classes().stream().noneMatch(activationClass ->
			activationClass.demandIndexes().contains(0)));
		Assert.assertTrue(partition.semanticDescriptor().contains("event=0:weight=0"));
	}

	@Test
	public void unresolvedOverlapUsesCappedConservativeUnionWithoutIndependence() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.6, A_IF), event(0.6, B_IF), event(0.6, C_IF));
		var partition = ExactMaterializationActivation.partition(events, 1d);
		Assert.assertFalse(partition.resolved());
		Assert.assertTrue(partition.classes().isEmpty());
		Assert.assertTrue(partition.semanticDescriptor().startsWith(
			"CONSERVATIVE_UNION_BOUND_V1"));
		Assert.assertTrue(partition.semanticDescriptor().contains("independenceAssumed=false"));
		Assert.assertEquals(0d, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {false, false, false}), 0d);
		Assert.assertEquals(0.6, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {true, false, false}), 0d);
		Assert.assertEquals(1d, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {true, true, false}), 0d);
		Assert.assertEquals(1d, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {true, true, true}), 0d);
	}

	@Test
	public void invalidLaminarWeightsAndOversubscribedDisjointChildrenAreUnresolved() {
		var narrowerHeavier = ExactMaterializationActivation.partition(List.of(
			event(0.4, A_IF), event(0.6, A_IF, B_IF)), 1d);
		Assert.assertFalse(narrowerHeavier.resolved());
		Assert.assertTrue(narrowerHeavier.classes().isEmpty());

		var childrenExceedParent = ExactMaterializationActivation.partition(List.of(
			event(0.7, A_IF), event(0.7, A_ELSE)), 1d);
		Assert.assertFalse(childrenExceedParent.resolved());
		Assert.assertTrue(childrenExceedParent.semanticDescriptor().contains(
			"CHILD_WEIGHT_EXCEEDS_PARENT"));
	}

	@Test
	public void conservativeUnionMergesExactDuplicatesAndOnlyRemovesProvenSubsumption() {
		List<ExactMaterializationActivation.Event> events = List.of(
			event(0.6, A_IF), event(0.6, A_IF), event(0.2, A_IF, B_IF),
			event(0.8, A_IF, B_IF));
		Assert.assertEquals(0.6, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {true, true, true, false}), 0d);
		Assert.assertEquals(1d, ExactMaterializationActivation.conservativeUnion(events,
			1d, new boolean[] {true, false, false, true}), 0d);
	}

	@Test
	public void eventNormalizationAndValidationAreDefensive() {
		var normalized = new ExactMaterializationActivation.Event(0.5,
			List.of(B_IF, A_IF, A_IF));
		Assert.assertEquals(List.of(A_IF, B_IF), normalized.conditions());
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> normalized.conditions().add(C_IF));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new ExactMaterializationActivation.Event(0.5, List.of(A_IF, A_ELSE)));
		for(double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, -1d, -0d))
			Assert.assertThrows(IllegalArgumentException.class,
				() -> new ExactMaterializationActivation.Event(invalid, List.of()));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactMaterializationActivation.partition(List.of(event(1.1, A_IF)), 1d));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactMaterializationActivation.partition(List.of(), Double.NaN));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactMaterializationActivation.conservativeUnion(List.of(event(0.5, A_IF)),
				1d, new boolean[0]));
		var empty = ExactMaterializationActivation.partition(List.of(), 1d);
		Assert.assertTrue(empty.resolved());
		Assert.assertTrue(empty.classes().isEmpty());
	}

	@Test
	public void descriptorBindsOriginalOrderWeightsAndClassMembership() {
		var first = ExactMaterializationActivation.partition(List.of(
			event(0.5, A_IF), event(0.5, A_IF)), 1d);
		var changedWeight = ExactMaterializationActivation.partition(List.of(
			event(0.5, A_IF), event(0.4, A_IF)), 1d);
		var changedOrder = ExactMaterializationActivation.partition(List.of(
			event(0.5, A_ELSE), event(0.5, A_IF)), 1d);
		var reverseOrder = ExactMaterializationActivation.partition(List.of(
			event(0.5, A_IF), event(0.5, A_ELSE)), 1d);
		Assert.assertTrue(first.semanticDescriptor().contains("[0, 1]"));
		Assert.assertNotEquals(first.semanticDescriptor(), changedWeight.semanticDescriptor());
		Assert.assertNotEquals(changedOrder.semanticDescriptor(), reverseOrder.semanticDescriptor());
	}

	private static void assertEverySubset(ExactMaterializationActivation.Partition partition,
		List<ExactMaterializationActivation.Event> events, double scopeWeight,
		double[] expected) {
		Assert.assertEquals(1 << events.size(), expected.length);
		for(int subset = 0; subset < expected.length; subset++) {
			boolean[] active = new boolean[events.size()];
			for(int index = 0; index < active.length; index++)
				active[index] = (subset & (1 << index)) != 0;
			double factorized = 0d;
			for(ExactMaterializationActivation.ActivationClass activationClass :
				partition.classes())
				if(activationClass.demandIndexes().stream().anyMatch(index -> active[index]))
					factorized += activationClass.multiplicity();
			Assert.assertEquals("subset=" + subset, expected[subset], factorized, 0d);
			Assert.assertEquals("fallback subset=" + subset, expected[subset],
				ExactMaterializationActivation.conservativeUnion(events, scopeWeight, active), 0d);
		}
	}

	private static ExactMaterializationActivation.Event event(double weight,
		ExactPhysicalCostModel.BranchLiteral... conditions) {
		return new ExactMaterializationActivation.Event(weight, List.of(conditions));
	}

	private static ExactPhysicalCostModel.BranchLiteral literal(String path, boolean arm) {
		return new ExactPhysicalCostModel.BranchLiteral(path, arm);
	}
}
