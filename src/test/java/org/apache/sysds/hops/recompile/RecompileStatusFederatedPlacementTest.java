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

package org.apache.sysds.hops.recompile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.junit.Test;

public class RecompileStatusFederatedPlacementTest {
	@Test
	public void testSyntheticStatisticsAreRemovedFromRuntimePlacementObservations() {
		RecompileStatus status = new RecompileStatus();
		status.markFederatedPlacementUnknown(java.util.List.of("synthetic"));

		Map<String, String> signatures = new HashMap<>();
		signatures.put("synthetic", "worker1:8001/data/synthetic;|0,10;");
		signatures.put("actual", "worker1:8001/data/actual;|0,10;");
		Map<String, FType> types = new HashMap<>();
		types.put("synthetic", null);
		types.put("actual", FType.ROW);

		Recompiler.filterUnknownFederatedPlacementObservations(signatures, types, status);

		assertFalse("A statistics-only MatrixObject must not be reported as runtime-local",
			types.containsKey("synthetic"));
		assertFalse("A statistics-only MatrixObject must not contribute a runtime signature",
			signatures.containsKey("synthetic"));
		assertEquals("Actual runtime observations must remain available", FType.ROW, types.get("actual"));
		assertTrue("Actual runtime signatures must remain available", signatures.containsKey("actual"));
	}

	@Test
	public void testUnknownPlacementProvenanceSurvivesStatusCloneAndMerge() {
		RecompileStatus first = new RecompileStatus();
		first.markFederatedPlacementUnknown(java.util.List.of("if_value"));
		RecompileStatus cloned = (RecompileStatus) first.clone();
		RecompileStatus alternate = new RecompileStatus();
		alternate.markFederatedPlacementUnknown(java.util.List.of("else_value"));

		cloned.mergeUnknownFederatedPlacementVars(alternate);

		assertTrue(cloned.getUnknownFederatedPlacementVars().contains("if_value"));
		assertTrue(cloned.getUnknownFederatedPlacementVars().contains("else_value"));
	}
}
