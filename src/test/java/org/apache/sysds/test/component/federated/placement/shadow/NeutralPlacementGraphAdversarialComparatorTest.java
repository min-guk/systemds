/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracleFixtures;
import org.apache.sysds.test.component.federated.placement.shadow.NormalizedPlacementGraphSnapshot.Surface;
import org.junit.Assert;
import org.junit.Test;

/** Adversarial precision and multiplicity checks for normalized shadow diffs. */
public class NeutralPlacementGraphAdversarialComparatorTest {
	@Test
	public void addedAndDeletedCandidatesArePrecise() {
		NormalizedPlacementGraphSnapshot baseline = snapshot("B-03");
		List<String> added = copy(baseline, Surface.CANDIDATES);
		added.add("adversarial=FED/FOUT/ROW");
		assertOnly(baseline, baseline.withSurface(Surface.CANDIDATES, added), Surface.CANDIDATES, "+adversarial");

		List<String> deleted = copy(baseline, Surface.CANDIDATES);
		String removed = deleted.remove(0);
		assertOnly(baseline, baseline.withSurface(Surface.CANDIDATES, deleted), Surface.CANDIDATES, "-" + removed);
	}

	@Test
	public void changedReasonIsAnExclusionMismatch() {
		NormalizedPlacementGraphSnapshot baseline = snapshot("B-21");
		List<String> changed = copy(baseline, Surface.EXCLUSIONS);
		changed.set(0, changed.get(0).replace("UNKNOWN_METADATA", "PRIVACY"));
		assertOnly(baseline, baseline.withSurface(Surface.EXCLUSIONS, changed), Surface.EXCLUSIONS, "PRIVACY");
	}

	@Test
	public void valueAndControlIdentitySwapNamesBothSurfaces() {
		NormalizedPlacementGraphSnapshot baseline = snapshot("B-17");
		NormalizedPlacementGraphSnapshot changed = baseline.withSurface(Surface.VALUE_IDENTITIES,
			swapped(copy(baseline, Surface.VALUE_IDENTITIES))).withSurface(Surface.CONTROL_IDENTITIES,
				swapped(copy(baseline, Surface.CONTROL_IDENTITIES)));
		Assert.assertEquals(Arrays.asList(Surface.VALUE_IDENTITIES, Surface.CONTROL_IDENTITIES),
			new ArrayList<>(baseline.diff(changed).keySet()));
	}

	@Test
	public void droppedAndDuplicatedObligationsPreserveMultiplicity() {
		NormalizedPlacementGraphSnapshot baseline = snapshot("B-22");
		List<String> dropped = copy(baseline, Surface.OBLIGATIONS);
		String removed = dropped.remove(0);
		assertOnly(baseline, baseline.withSurface(Surface.OBLIGATIONS, dropped), Surface.OBLIGATIONS, "-" + removed);

		List<String> duplicated = copy(baseline, Surface.OBLIGATIONS);
		duplicated.add(duplicated.get(0));
		assertOnly(baseline, baseline.withSurface(Surface.OBLIGATIONS, duplicated), Surface.OBLIGATIONS,
			"+" + duplicated.get(0));
	}

	@Test
	public void splitRelocationAndAssignmentChangeArePrecise() {
		NormalizedPlacementGraphSnapshot relocations = snapshot("B-22");
		List<String> split = copy(relocations, Surface.RELOCATIONS);
		split.add(split.get(0) + "|split");
		assertOnly(relocations, relocations.withSurface(Surface.RELOCATIONS, split), Surface.RELOCATIONS, "split");

		NormalizedPlacementGraphSnapshot assignments = snapshot("B-03");
		List<String> changed = copy(assignments, Surface.LEGAL_ASSIGNMENTS);
		changed.add("adversarial-assignment");
		assertOnly(assignments, assignments.withSurface(Surface.LEGAL_ASSIGNMENTS, changed),
			Surface.LEGAL_ASSIGNMENTS, "adversarial-assignment");
	}

	private static NormalizedPlacementGraphSnapshot snapshot(String id) {
		return NormalizedPlacementGraphSnapshot.fromOracle(BuilderOracleFixtures.fixture(id));
	}

	private static List<String> copy(NormalizedPlacementGraphSnapshot snapshot, Surface surface) {
		return new ArrayList<>(snapshot.surface(surface));
	}

	private static List<String> swapped(List<String> values) {
		Assert.assertTrue("adversarial identity fixture needs two values", values.size() > 1);
		Collections.swap(values, 0, 1);
		values.set(0, values.get(0) + "|swapped");
		return values;
	}

	private static void assertOnly(NormalizedPlacementGraphSnapshot expected,
		NormalizedPlacementGraphSnapshot actual, Surface surface, String evidence) {
		Map<Surface,List<String>> diff = expected.diff(actual);
		Assert.assertEquals(Collections.singletonList(surface), new ArrayList<>(diff.keySet()));
		Assert.assertTrue(diff.get(surface).toString(), diff.get(surface).stream().anyMatch(value -> value.contains(evidence)));
	}
}
