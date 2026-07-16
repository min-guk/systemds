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
package org.apache.sysds.test.component.federated.placement.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic;
import org.junit.Test;

public class FederatedPlannerFactoryContractTest {
	private static final String FACTORY = "org.apache.sysds.hops.ipa.FederatedPlannerFactory";

	@Test
	public void exactTypedMappingAndNullSentinels() throws Exception {
		Class<?> factory;
		try {
			factory = Class.forName(FACTORY);
		}
		catch(ClassNotFoundException ex) {
			fail("typed factory is absent");
			return;
		}
		Method create;
		try {
			create = factory.getMethod("create", FederatedPlanner.class);
		}
		catch(NoSuchMethodException ex) {
			fail("exact public create(FederatedPlanner) required");
			return;
		}
		assertEquals(AFederatedPlanner.class, create.getReturnType());
		assertNull(create.invoke(null, FederatedPlanner.NONE));
		assertNull(create.invoke(null, FederatedPlanner.RUNTIME));

		Map<FederatedPlanner, Class<? extends AFederatedPlanner>> expected =
			new EnumMap<>(FederatedPlanner.class);
		expected.put(FederatedPlanner.COMPILE_FED_ALL, FederatedPlannerFedAll.class);
		expected.put(FederatedPlanner.COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS,
			FederatedPlannerFedAllMaxFedFoutSinglePass.class);
		expected.put(FederatedPlanner.COMPILE_FED_HEURISTIC, FederatedPlannerFedHeuristic.class);
		expected.put(FederatedPlanner.COMPILE_COST_BASED, FederatedPlannerDpFedCostBased.class);
		expected.put(FederatedPlanner.COMPILE_MIN_ST_CUT, FederatedPlanMinSTCut.class);
		for(Map.Entry<FederatedPlanner, Class<? extends AFederatedPlanner>> entry : expected.entrySet())
			assertEquals(entry.getKey().name(), entry.getValue(), create.invoke(null, entry.getKey()).getClass());
	}

	@Test
	public void allInstanceAndStaticIsCompiledSemantics() {
		for(FederatedPlanner planner : FederatedPlanner.values()) {
			boolean compiled = planner != FederatedPlanner.NONE && planner != FederatedPlanner.RUNTIME;
			assertEquals(planner.name(), compiled, planner.isCompiled());
			assertEquals(planner.name(), compiled, FederatedPlanner.isCompiled(mixedCase(planner.name())));
		}
		assertFalse(FederatedPlanner.isCompiled(null));
		assertFalse(FederatedPlanner.isCompiled("none"));
		assertFalse(FederatedPlanner.isCompiled("RuNtImE"));
		for(FederatedPlanner planner : FederatedPlanner.values())
			if(planner != FederatedPlanner.NONE && planner != FederatedPlanner.RUNTIME)
				assertTrue(planner.name(), FederatedPlanner.isCompiled(planner.name().toLowerCase()));
		try {
			FederatedPlanner.isCompiled("invalid-name");
			fail("invalid names must preserve IllegalArgumentException semantics");
		}
		catch(IllegalArgumentException expected) {
			// expected
		}
	}

	private static String mixedCase(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for(int i = 0; i < value.length(); i++)
			result.append(i % 2 == 0 ? Character.toLowerCase(value.charAt(i)) : Character.toUpperCase(value.charAt(i)));
		return result.toString();
	}
}
