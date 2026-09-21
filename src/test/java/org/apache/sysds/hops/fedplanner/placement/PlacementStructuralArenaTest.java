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
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class PlacementStructuralArenaTest {
	private record Colliding(String value) {
		@Override public int hashCode() { return 7; }
	}

	@Test
	public void equalCopiesShareHandleButCollisionsAndSupportDifferencesDoNot() {
		PlacementIdentity.beginAnalysisScope(new SearchSpaceMetrics());
		try {
			Integer first = PlacementIdentity.structuralHandle(new Colliding("a"));
			Assert.assertEquals(first, PlacementIdentity.structuralHandle(new Colliding("a")));
			Assert.assertNotEquals(first, PlacementIdentity.structuralHandle(new Colliding("b")));
			CandidateRealizationSupportClause a = clause("shape-a", "owner-a");
			Integer aHandle = PlacementIdentity.structuralHandle(a);
			Assert.assertEquals(aHandle, PlacementIdentity.structuralHandle(clause("shape-a", "owner-a")));
			Assert.assertNotEquals(aHandle, PlacementIdentity.structuralHandle(clause("shape-b", "owner-a")));
			Assert.assertNotEquals(aHandle, PlacementIdentity.structuralHandle(clause("shape-a", "owner-b")));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void identityAndStructureRetentionHaveIndependentBudgets() throws Exception {
		String identityProperty = "sysds.fedplanner.structuralArena.maxIdentityEntries";
		String structureProperty = "sysds.fedplanner.structuralArena.maxEntries";
		String oldIdentity = System.getProperty(identityProperty);
		String oldStructure = System.getProperty(structureProperty);
		try {
			System.setProperty(identityProperty, "1");
			System.setProperty(structureProperty, "4");
			PlacementIdentity.beginAnalysisScope(new SearchSpaceMetrics());
			Integer expected = PlacementIdentity.structuralHandle(new Colliding("same"));
			for(int i = 0; i < 100; i++)
				Assert.assertEquals(expected, PlacementIdentity.structuralHandle(new Colliding("same")));
			Assert.assertTrue(arenaMapSize("byIdentity") <= 1);
			Assert.assertTrue(arenaMapSize("byStructure") <= 4);
			PlacementIdentity.endAnalysisScope();
			Assert.assertNull(PlacementIdentity.structuralHandle(new Colliding("same")));
			System.setProperty(structureProperty, "0");
			PlacementIdentity.beginAnalysisScope(new SearchSpaceMetrics());
			Assert.assertNull(PlacementIdentity.structuralHandle(new Colliding("same")));
			// Exhausted child handles must fall back exactly, not auto-unbox null.
			Assert.assertNull(PlacementIdentity.structuralHandle(clause("shape", "owner")));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			restore(identityProperty, oldIdentity);
			restore(structureProperty, oldStructure);
		}
	}

	private static CandidateRealizationSupportClause clause(String signature, String owner) {
		// The owner is part of exact proof authority, even when the support text matches.
		ControlRegionKey region = new ControlRegionKey("program", "main", List.of("body"),
			"call", "compile");
		CompiledHopKey occurrence = new CompiledHopKey("program", "main", "call", "compile",
			region, owner, "source");
		return new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
			PlacementProofKind.SHAPE, occurrence, signature)), List.of());
	}

	private static int arenaMapSize(String name) throws Exception {
		Field local = PlacementIdentity.class.getDeclaredField("ACTIVE_STRUCTURAL_ARENA");
		local.setAccessible(true);
		Object arena = ((ThreadLocal<?>) local.get(null)).get();
		Field field = arena.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return ((Map<?,?>) field.get(arena)).size();
	}

	private static void restore(String name, String value) {
		if(value == null)
			System.clearProperty(name);
		else
			System.setProperty(name, value);
	}
}
