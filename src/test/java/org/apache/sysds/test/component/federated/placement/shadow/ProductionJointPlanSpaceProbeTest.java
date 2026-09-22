/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Bounded production-side enumeration checks; these are not semantic certificates. */
public class ProductionJointPlanSpaceProbeTest {
	@Test
	public void actionFreeFixtureEnumeratesWholePublishedProduct() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			protectedProgram("B-14"));
		Assert.assertTrue(analysis.graph().relocationActions().isEmpty());
		Assert.assertTrue(analysis.graph().derivedFoutMaterializationActions().isEmpty());
		var result = ProductionJointPlanSpaceProbe.enumerate(analysis, 100000);
		Assert.assertEquals(ProductionJointPlanSpaceProbe.Status.BOUNDED_COMPLETE, result.status());
		Assert.assertEquals(1, result.plans().size());
		Assert.assertEquals(result,
			ProductionJointPlanSpaceProbe.enumerate(analysis, 100000));
		Assert.assertTrue(result.plans().stream().allMatch(plan ->
			plan.receipts().stream().allMatch(receipt ->
				plan.assignment().get(receipt.rule().parentOccurrence())
					.equals(receipt.emission().emissionState().placementState()))));
	}

	@Test
	public void actionBearingFixtureCannotBeCertifiedByThisProbe() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			protectedProgram("B-22"));
		Assert.assertFalse(analysis.graph().relocationActions().isEmpty());
		var result = ProductionJointPlanSpaceProbe.enumerate(analysis, 100000);
		Assert.assertEquals(ProductionJointPlanSpaceProbe.Status.UNKNOWN, result.status());
		Assert.assertTrue(result.plans().isEmpty());
	}

	@Test
	public void invalidBoundIsRejected() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			protectedProgram("B-14"));
		try {
			ProductionJointPlanSpaceProbe.enumerate(analysis, 0);
			Assert.fail("zero bound must not silently truncate the set");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("positive"));
		}
	}

	private static DMLProgram protectedProgram(String id) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(id);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}
