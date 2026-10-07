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

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

public class ExactHardAxisProfileParityTest {
	@Test
	public void hardProfilesMatchIndependentFirstValueOracleAcrossShapes() {
		Random random = new Random(42919L);
		for(int trial = 0; trial < 600; trial++) {
			int domain = 1 + random.nextInt(12);
			int stride = 1 + random.nextInt(17);
			int outer = 1 + random.nextInt(5);
			boolean[] forbidden = new boolean[domain * stride * outer];
			ExactCategoricalSolver.HardTable table = ExactCategoricalSolver.HardTable.allocate(forbidden.length);
			for(int cell = 0; cell < forbidden.length; cell++) {
				forbidden[cell] = trial % 3 != 0 && random.nextInt(7) == 0;
				if(forbidden[cell]) table.forbid(cell);
			}
			Assert.assertArrayEquals("trial=" + trial, oracle(forbidden, domain, stride),
				table.compactAllFeasible().axisClasses(domain, stride));
		}
	}

	@Test
	public void changingUnpublishedHardTableBetweenCallsRecomputesProfiles() {
		ExactCategoricalSolver.HardTable table = ExactCategoricalSolver.HardTable.allocate(12);
		Assert.assertArrayEquals(new int[] {0, 0, 0}, table.axisClasses(3, 2));
		table.forbid(2);
		Assert.assertArrayEquals(new int[] {0, 1, 0}, table.axisClasses(3, 2));
		table.forbid(4);
		Assert.assertArrayEquals(new int[] {0, 1, 1}, table.axisClasses(3, 2));
	}

	@Test
	public void everyForbiddenProfileAndNearUniformProfileMatchOracle() {
		for(int stride : new int[] {1, 2, 63, 64, 65}) {
			int domain = 7;
			boolean[] forbidden = new boolean[3 * domain * stride];
			ExactCategoricalSolver.HardTable table = ExactCategoricalSolver.HardTable.allocate(forbidden.length);
			for(int cell = 0; cell < forbidden.length - 1; cell++) { forbidden[cell] = true; table.forbid(cell); }
			Assert.assertArrayEquals(oracle(forbidden, domain, stride), table.axisClasses(domain, stride));
			forbidden[forbidden.length - 1] = true; table.forbid(forbidden.length - 1);
			Assert.assertArrayEquals(oracle(forbidden, domain, stride), table.axisClasses(domain, stride));
			Assert.assertArrayEquals(new int[domain], table.axisClasses(domain, stride));
		}
	}

	@Test
	public void uniformProfileStillValidatesShape() {
		ExactCategoricalSolver.HardTable zeros = ExactCategoricalSolver.HardTable.allocate(12).compactAllFeasible();
		ExactCategoricalSolver.HardTable forbidden = ExactCategoricalSolver.HardTable.allocate(12);
		for(int cell = 0; cell < 12; cell++) forbidden.forbid(cell);
		for(var table : new ExactCategoricalSolver.HardTable[] {zeros, forbidden}) {
			Assert.assertThrows(IllegalArgumentException.class, () -> table.axisClasses(3, 0));
			Assert.assertThrows(IllegalArgumentException.class, () -> table.axisClasses(5, 1));
			Assert.assertThrows(IllegalArgumentException.class, () -> table.axisClasses(2, Integer.MAX_VALUE));
		}
	}

	private static int[] oracle(boolean[] forbidden, int domain, int stride) {
		int[] classes = new int[domain];
		int next = 0;
		for(int value = 0; value < domain; value++) {
			int representative = -1;
			for(int prior = 0; prior < value; prior++) {
				boolean equal = true;
				for(int base = 0; base < forbidden.length && equal; base += domain * stride)
					for(int inner = 0; inner < stride && equal; inner++)
						equal = forbidden[base + prior * stride + inner] == forbidden[base + value * stride + inner];
				if(equal) { representative = prior; break; }
			}
			classes[value] = representative < 0 ? next++ : classes[representative];
		}
		return classes;
	}
}
