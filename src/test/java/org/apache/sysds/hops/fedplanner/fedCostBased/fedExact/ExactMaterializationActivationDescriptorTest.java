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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ExactMaterializationActivationDescriptorTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void streamedDigestMatchesLegacyDescriptorAndKeepsCosts() throws Exception {
		var source = new ExactCategoricalSolver.Variable("source", 2);
		var consumer = new ExactCategoricalSolver.Variable("consumer", 3);
		boolean[] sourceActive = {false, true};
		double[] prices = {2d, 7d};
		boolean[] observed = {false, true, false};
		var event = new ExactMaterializationActivation.Event(0.5,
			List.of(new ExactPhysicalCostModel.BranchLiteral("branch", true)));
		var demand = new ExactPhysicalCostModel.ActivationDemand(
			List.of(consumer), List.of(observed), event);
		var partition = ExactMaterializationActivation.partition(List.of(event), 1d);
		String legacy = new StringBuilder("MATERIALIZATION_ACTIVATION_V1|")
			.append("test").append('|').append(partition.semanticDescriptor())
			.append("|source=").append(source.key()).append("|sourceActive=")
			.append(Arrays.toString(sourceActive))
			.append("|unitBits=").append(Long.toUnsignedString(
				Double.doubleToRawLongBits(prices[0]), 16))
			.append("|unitBits=").append(Long.toUnsignedString(
				Double.doubleToRawLongBits(prices[1]), 16))
			.append("|observation=").append(consumer.key()).append(':')
			.append(Arrays.toString(observed)).toString();
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(legacy.getBytes(StandardCharsets.UTF_8)));
		Assert.assertEquals(expected,
			ExactPhysicalCostModel.materializationActivationSemanticDigestForTest(
				"test", source, sourceActive, prices, List.of(demand), 1d));

		List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>();
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addMaterializationActivationFactors("test", source,
			sourceActive, prices, List.of(demand), 1d, canonical, factorizations, null);
		Assert.assertEquals(1, canonical.size());
		for(int sourceValue = 0; sourceValue < 2; sourceValue++)
			for(int consumerValue = 0; consumerValue < 3; consumerValue++) {
				double expectedCost = sourceValue == 1 && consumerValue == 1 ? 3.5d : 0d;
				Assert.assertEquals(expectedCost, ExactCategoricalSolver.evaluate(
					List.of(source, consumer), canonical, LIMITS,
					List.of(sourceValue, consumerValue)), 0d);
			}
		Assert.assertTrue(factorizations.get(canonical.get(0)).semanticDescriptor()
			.startsWith("MATERIALIZATION_ACTIVATION_V2|semanticSha256=" + expected));
	}

	@Test
	public void largeObservationDigestIsBoundedAndSensitive() {
		var source = new ExactCategoricalSolver.Variable("large-source", 2);
		var consumer = new ExactCategoricalSolver.Variable("large-consumer", 2_000_000);
		boolean[] observed = new boolean[consumer.domainSize()];
		observed[observed.length - 1] = true;
		var event = new ExactMaterializationActivation.Event(1d,
			List.of(new ExactPhysicalCostModel.BranchLiteral("first", true)));
		var first = new ExactPhysicalCostModel.ActivationDemand(
			List.of(consumer), List.of(observed), event);
		String digest = ExactPhysicalCostModel.materializationActivationSemanticDigestForTest(
			"large", source, new boolean[] {false, true}, new double[] {0d, 1d},
			List.of(first), 1d);
		Assert.assertEquals(64, digest.length());
		observed[0] = true;
		var changed = new ExactPhysicalCostModel.ActivationDemand(
			List.of(consumer), List.of(observed), event);
		Assert.assertNotEquals(digest,
			ExactPhysicalCostModel.materializationActivationSemanticDigestForTest(
				"large", source, new boolean[] {false, true}, new double[] {0d, 1d},
				List.of(changed), 1d));

		var overlapping = new ExactMaterializationActivation.Event(1d,
			List.of(new ExactPhysicalCostModel.BranchLiteral("independent", true)));
		var second = new ExactPhysicalCostModel.ActivationDemand(
			List.of(consumer), List.of(observed), overlapping);
		List<ExactCategoricalSolver.Factor> canonical = new ArrayList<>();
		var factorizations = new IdentityHashMap<ExactCategoricalSolver.Factor,
			ExactPhysicalCostModel.SolverFactorization>();
		ExactPhysicalCostModel.addMaterializationActivationFactors("large", source,
			new boolean[] {false, true}, new double[] {0d, 1d}, List.of(first, second),
			1d, canonical, factorizations, null);
		Assert.assertEquals(1, canonical.size());
		Assert.assertTrue("descriptor must not retain expanded observation text",
			factorizations.get(canonical.get(0)).semanticDescriptor().length() < 4096);
	}
}
