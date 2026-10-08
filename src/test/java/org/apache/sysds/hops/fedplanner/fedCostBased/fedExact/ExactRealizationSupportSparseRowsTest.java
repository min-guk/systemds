/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

public class ExactRealizationSupportSparseRowsTest {
	@Test
	public void binaryRowsAreGeneratedFromInvertedHandlesWithoutCartesianEnumeration() {
		int[] handles = {10,20,10,30,40,20,50,60,70,80,90,100,110,120,130,140};
		List<Set<Integer>> requirements = new ArrayList<>();
		requirements.add(Set.of(10));
		requirements.add(Set.of());
		requirements.add(Set.of(20,30));
		for(int owner = 3; owner < 16; owner++)
			requirements.add(Set.of());

		int[] actual = ExactPhysicalModel.sparseRealizationSupportCells(
			requirements, handles, false);

		Assert.assertArrayEquals(new int[] {0,2, 2 * 16 + 1, 2 * 16 + 3, 2 * 16 + 5}, actual);
		assertBinaryTruth(requirements, handles, actual);
	}

	@Test
	public void unconstrainedRowsUseDenseFallbackWhenSparseStorageWouldNotHelp() {
		int[] handles = {10,20,30,40};
		List<Set<Integer>> requirements = Arrays.asList(null, null, Set.of(10));

		Assert.assertNull(ExactPhysicalModel.sparseRealizationSupportCells(
			requirements, handles, false));
	}

	@Test
	public void selfScopeEvaluatesOnlyMatchingOwnerAndSourceOrdinal() {
		int[] handles = {10,20,10,30,40,50,60,70,80,90,100,110,120,130,140,150};
		List<Set<Integer>> requirements = new ArrayList<>();
		requirements.add(null);
		requirements.add(Set.of(10));
		requirements.add(Set.of(10));
		requirements.add(Set.of(30));
		for(int owner = 4; owner < 16; owner++)
			requirements.add(Set.of());

		int[] actual = ExactPhysicalModel.sparseRealizationSupportCells(
			requirements, handles, true);

		Assert.assertArrayEquals(new int[] {0,2,3}, actual);
	}

	private static void assertBinaryTruth(List<Set<Integer>> requirements,
		int[] handles, int[] finiteCells) {
		Set<Integer> actual = new HashSet<>();
		for(int cell : finiteCells)
			Assert.assertTrue("duplicate sparse cell", actual.add(cell));
		for(int owner = 0; owner < requirements.size(); owner++)
			for(int source = 0; source < handles.length; source++) {
				Set<Integer> required = requirements.get(owner);
				boolean expected = required == null
					|| !required.isEmpty() && required.contains(handles[source]);
				Assert.assertEquals(expected, actual.contains(owner * handles.length + source));
			}
	}
}
