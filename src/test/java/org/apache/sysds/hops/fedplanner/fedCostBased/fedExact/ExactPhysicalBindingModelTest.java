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

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014HermeticPlannerFixtureFactory;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalBindingModelTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(10_000_000L, 50_000_000L);

	@Test
	public void everyCatalogRowRoundTripsAndPreservesPulledBackHardFactors() throws Exception {
		ExactPhysicalModel packed = model("B-11");
		ExactPhysicalBindingModel joint = ExactPhysicalBindingModel.build(packed);
		Assert.assertEquals(packed.domains().stream()
			.mapToLong(domain -> domain.alternatives().size()).sum(), joint.indexedCatalogRows());
		List<ExactCategoricalSolver.Factor> translated = joint.translateFactors(packed.hardFactors());
		List<Integer> baseline = packed.domains().stream().map(ignored -> 0).toList();
		for(int decision = 0; decision < packed.domains().size(); decision++)
			for(int value = 0; value < packed.domains().get(decision).alternatives().size(); value++) {
				List<Integer> assignment = new ArrayList<>(baseline);
				assignment.set(decision, value);
				List<Integer> encoded = joint.encode(assignment);
				Assert.assertEquals("catalog row lost at decision " + decision + " value " + value,
					assignment, joint.reconstruct(encoded));
				double expected = ExactCategoricalSolver.evaluate(packed.variables(),
					packed.hardFactors(), LIMITS, assignment);
				double actual = ExactCategoricalSolver.evaluate(joint.variables(), translated,
					LIMITS, encoded);
				Assert.assertEquals(Double.doubleToRawLongBits(expected),
					Double.doubleToRawLongBits(actual));
			}
	}

	@Test
	public void sameOperatorCoordinateRetainsIndependentBindingCoordinates() throws Exception {
		ExactPhysicalModel packed = model("B-11");
		ExactPhysicalBindingModel joint = ExactPhysicalBindingModel.build(packed);
		boolean found = false;
		for(int decision = 0; decision < packed.domains().size() && !found; decision++) {
			List<Integer> base = packed.domains().stream().map(ignored -> 0).toList();
			for(int left = 0; left < packed.domains().get(decision).alternatives().size() && !found; left++)
				for(int right = left + 1; right < packed.domains().get(decision).alternatives().size(); right++) {
					List<Integer> leftPacked = new ArrayList<>(base);
					List<Integer> rightPacked = new ArrayList<>(base);
					leftPacked.set(decision, left);
					rightPacked.set(decision, right);
					List<Integer> leftJoint = joint.encode(leftPacked);
					List<Integer> rightJoint = joint.encode(rightPacked);
					int x = decision;
					if(leftJoint.get(x).equals(rightJoint.get(x)) && !leftJoint.equals(rightJoint)) {
						Assert.assertEquals(leftPacked, joint.reconstruct(leftJoint));
						Assert.assertEquals(rightPacked, joint.reconstruct(rightJoint));
						found = true;
						break;
					}
				}
		}
		Assert.assertTrue("fixture must expose two supplies under one semantic X", found);
	}

	@Test
	public void translatedCostFactorKeepsCatalogAndMaximumChargeValues() throws Exception {
		ExactPhysicalModel packed = model("B-11");
		ExactPhysicalBindingModel joint = ExactPhysicalBindingModel.build(packed);
		int decision = -1;
		for(int index = 0; index < packed.domains().size(); index++)
			if(packed.domains().get(index).alternatives().size() > 1) {
				decision = index;
				break;
			}
		Assert.assertTrue(decision >= 0);
		var domain = packed.domains().get(decision);
		double[] maximumCharges = new double[domain.alternatives().size()];
		for(int value = 0; value < maximumCharges.length; value++)
			maximumCharges[value] = Math.max(value * 7d, value * 11d);
		var maximum = ExactCategoricalSolver.Factor.lazy(List.of(domain.variable()),
			values -> maximumCharges[values[0]]);
		List<ExactCategoricalSolver.Factor> translated = joint.translateFactors(List.of(maximum));
		List<Integer> base = packed.domains().stream().map(ignored -> 0).toList();
		for(int value = 0; value < domain.alternatives().size(); value++) {
			List<Integer> assignment = new ArrayList<>(base);
			assignment.set(decision, value);
			List<Integer> encoded = joint.encode(assignment);
			double actual = ExactCategoricalSolver.evaluate(joint.variables(), translated,
				LIMITS, encoded);
			Assert.assertEquals(maximumCharges[value], actual, 0d);
		}
	}

	@Test
	public void incompleteOutOfRangeAndProducerIncompatibleAssignmentsAreRejected() throws Exception {
		ExactPhysicalModel packed = model("B-11");
		ExactPhysicalBindingModel joint = ExactPhysicalBindingModel.build(packed);
		List<Integer> encoded = joint.encode(packed.domains().stream().map(ignored -> 0).toList());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> joint.reconstruct(encoded.subList(0, encoded.size() - 1)));
		List<Integer> outOfRange = new ArrayList<>(encoded);
		outOfRange.set(0, joint.variables().get(0).domainSize());
		Assert.assertThrows(IllegalArgumentException.class, () -> joint.reconstruct(outOfRange));

		boolean rejected = false;
		for(var binding : joint.bindingDomains()) {
			int nativeValue = indexOf(binding, ExactPhysicalBindingModel.SupplyKind.NATIVE_LOUT);
			int copyValue = indexOf(binding, ExactPhysicalBindingModel.SupplyKind.LOCAL_COPY);
			if(nativeValue < 0 || copyValue < 0)
				continue;
			int y = joint.variables().indexOf(binding.variable());
			List<Integer> incompatible = new ArrayList<>(encoded);
			incompatible.set(y, incompatible.get(y) == nativeValue ? copyValue : nativeValue);
			try {
				joint.reconstruct(incompatible);
			}
			catch(IllegalArgumentException expected) {
				rejected = true;
				break;
			}
		}
		Assert.assertTrue("fixture must exercise explicit native/copy incompatibility", rejected);
	}

	private static int indexOf(ExactPhysicalBindingModel.BindingDomain domain,
		ExactPhysicalBindingModel.SupplyKind kind) {
		for(int index = 0; index < domain.choices().size(); index++)
			if(domain.choices().get(index).supplyKind() == kind)
				return index;
		return -1;
	}

	private static ExactPhysicalModel model(String fixture) throws Exception {
		return ExactPhysicalModel.build(new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile(fixture)));
	}
}
