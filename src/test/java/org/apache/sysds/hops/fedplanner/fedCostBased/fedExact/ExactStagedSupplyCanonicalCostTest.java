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

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ExactStagedSupplyCanonicalCostTest {
	@Test
	public void globalAndLocalUseTheSameCanonicalStagedSupplySurface() throws Exception {
		var fixture = ExactCompiledSupplySharingTest.fixture();
		var surface = fixture.surface();
		var global = ExactPhysicalOptimizer.optimize(fixture.model(), surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var local = LocalPhysicalOptimizer.optimize(fixture.model(), surface).physicalResult();
		for(var result : List.of(global, local)) {
			var assignment = result.solverResult().assignmentInVariableOrder();
			Assert.assertEquals(surface.evaluateCanonical(assignment), result.canonicalObjectiveBits());
			Assert.assertEquals(result.canonicalObjectiveBits(),
				Double.doubleToRawLongBits(result.solverResult().objective()));
			Assert.assertEquals(surface.selectedSharedSupplyLifetimes(assignment),
				result.sharedSupplyLifetimes());
			// Decode every boundary and movement receipt, validating the full selected plan.
			ExactPhysicalSelection.create(fixture.model(), result);
		}
		Assert.assertTrue(global.solverResult().objective() <= local.solverResult().objective() + 1e-9);
	}
}
