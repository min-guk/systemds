/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.test.component.federated.placement.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput.OccurrenceReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput.ProducerReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.FederatedPlanExact;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Pre-patch contract for exact program ownership and a graph-agnostic Exact adapter boundary. */
public class ExactLayer1OwnerCarrierContractTest {
	@Test
	public void placementAnalysisOwnsExactProgramIdentity() throws Exception {
		Method assertion;
		try {
			assertion = PlacementAnalysis.class.getMethod("assertProgramOwner", DMLProgram.class);
		}
		catch(ReflectiveOperationException missing) {
			Assert.fail("PLACEMENT_ANALYSIS_OWNER_ASSERTION_MISSING");
			return;
		}

		DMLProgram owner = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(owner);
		assertion.invoke(analysis, owner);
		assertForeignOwnerRejected(assertion, analysis, ProductionShadowFixtureFactory.compile("B-01"));

		DMLProgram emptyOwner = emptyProgram();
		PlacementAnalysis emptyAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(emptyOwner);
		assertion.invoke(emptyAnalysis, emptyOwner);
		assertForeignOwnerRejected(assertion, emptyAnalysis, emptyProgram());
	}

	@Test
	public void productionBoundaryUsesSelectAndNoSelectExact() throws Exception {
		Method select;
		try {
			select = ExactPlacementAdapter.class.getMethod("select", PlacementAnalysis.class,
				ExactPlacementInput.class);
		}
		catch(ReflectiveOperationException missing) {
			Assert.fail("EXACT_SELECT_BOUNDARY_MISSING");
			return;
		}
		Assert.assertEquals(ExactPlacementAdapter.Selection.class, select.getReturnType());
		Assert.assertThrows(NoSuchMethodException.class, () -> ExactPlacementAdapter.class.getMethod(
			"selectExact", PlacementAnalysis.class, ExactPlacementInput.class));
	}

	@Test
	public void carriersExposeOnlyNeutralTypes() {
		assertNeutralSurface(ExactPlacementInput.class);
		assertNeutralSurface(ExactPlacementAdapter.class);
	}

	@Test
	public void carrierRejectsForeignProducerFingerprintAndEqualCopiedOccurrenceKey() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ExactPlacementInput retained = new FederatedPlanExact().rewriteProgram(program, null, null, analysis);
		ProducerReceipt producer = retained.producerReceipt();
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactPlacementInput.create(analysis,
			new ProducerReceipt("foreign", producer.objectiveBits()), retained.occurrenceReceipts()));

		List<OccurrenceReceipt> copied = new ArrayList<>(retained.occurrenceReceipts());
		OccurrenceReceipt first = copied.get(0);
		var key = first.planningKey();
		var equalCopy = new org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey(
			key.programFingerprint(), key.functionNamespace(), key.callSitePath(), key.recompileContext(),
			key.controlRegion(), key.emittedHopInstance(), key.canonicalSourceOrigin());
		Assert.assertEquals(key, equalCopy);
		Assert.assertNotSame(key, equalCopy);
		copied.set(0, new OccurrenceReceipt(equalCopy, first.planningHop(), first.planningHopId(),
			first.executableHop(), first.executableHopId(), first.execType(), first.output()));
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactPlacementInput.create(analysis,
			producer, copied));
	}

	private static void assertForeignOwnerRejected(Method assertion, PlacementAnalysis analysis,
		DMLProgram foreign) throws Exception {
		InvocationTargetException thrown = Assert.assertThrows(InvocationTargetException.class,
			() -> assertion.invoke(analysis, foreign));
		Assert.assertTrue("foreign owner must fail closed",
			thrown.getCause() instanceof IllegalArgumentException);
	}

	private static DMLProgram emptyProgram() {
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>());
		return program;
	}

	private static void assertNeutralSurface(Class<?> type) {
		for(Field field : type.getDeclaredFields())
			assertNeutralType(field.getGenericType());
		for(Constructor<?> constructor : type.getDeclaredConstructors())
			for(Type parameter : constructor.getGenericParameterTypes())
				assertNeutralType(parameter);
		for(Method method : type.getDeclaredMethods()) {
			assertNeutralType(method.getGenericReturnType());
			for(Type parameter : method.getGenericParameterTypes())
				assertNeutralType(parameter);
		}
		if(type.isRecord())
			for(RecordComponent component : type.getRecordComponents())
				assertNeutralType(component.getGenericType());
		for(Class<?> nested : type.getDeclaredClasses())
			assertNeutralSurface(nested);
	}

	private static void assertNeutralType(Type type) {
		String name = type.getTypeName();
		Assert.assertFalse("graph-typed Exact carrier: " + name,
			name.contains("FederatedPlanExactGraph") || name.endsWith(".Vertex")
				|| name.contains("SelectedObligation"));
	}
}
