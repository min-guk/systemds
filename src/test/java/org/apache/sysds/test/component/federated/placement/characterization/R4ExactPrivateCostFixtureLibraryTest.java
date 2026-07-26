/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.characterization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.R4ExactPrivateCostDpFixtures;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.R4ExactPrivateCostDpFixtures.Fixture;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.R4ExactPrivateCostMinstFixtures;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ObligationKind;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

/** Adapter-independent executable proof for every exact private cost recipe. */
public class R4ExactPrivateCostFixtureLibraryTest {
	@Test
	public void exactDpPrivateRecipesUseNativeSelection() throws Exception {
		Map<String,Fixture> fixtures = dp();
		assertEquals(4, fixtures.size());
		assertField("DP01_SELECTION", FederatedOutput.LOUT,
			fixtures.get("C2-DP-01-ROOT-EQUAL-LOUT").selectedOutput());
		assertField("DP01_OBJECTIVE", 0x1.0p3, fixtures.get("C2-DP-01-ROOT-EQUAL-LOUT").objective());
		assertField("DP02_ONE_ULP", "1", fixtures.get("C2-DP-02-ROOT-ONEULP-FOUT").facts().get("bitDistance"));
		assertField("DP02_SELECTION", FederatedOutput.FOUT,
			fixtures.get("C2-DP-02-ROOT-ONEULP-FOUT").selectedOutput());
		assertField("DP03_INSERTION_TIE", "0",
			fixtures.get("C2-DP-03-STABLE-VARIANT").facts().get("selectedInsertionOrdinal"));
		Fixture vectorMm = fixtures.get("C2-DP-07-FED-LOCAL-OUTPUT");
		assertField("DP07_EXEC", "FED", vectorMm.selectedExec().name());
		assertField("DP07_OUTPUT", FederatedOutput.LOUT, vectorMm.selectedOutput());
		assertField("DP07_SHAPE", "VECTOR_X_FEDERATED_MM", vectorMm.facts().get("operandShape"));
		for(Fixture fixture : fixtures.values()) assertAliases(fixture.namedRoles(), fixture.literalAliases());
	}

	@Test
	public void exactMinstPrivateRecipesUseNativeCutRepairAndRegistries() throws Exception {
		Map<String,R4ExactPrivateCostMinstFixtures.Fixture> fixtures = minst();
		assertEquals(6, fixtures.size());
		var cut = fixtures.get("C2-MS-01-EQUAL-CUT");
		assertField("MS01_CAPACITY", Double.toHexString(0x1.0p3), cut.facts().get("capacity"));
		assertField("MS01_REVERSE", cut.facts().get("capacity"), cut.facts().get("reverseCapacity"));
		assertField("MS02_REPAIR", "FED/FOUT",
			fixtures.get("C2-MS-02-CAPS-FIXPOINT").assignments().get("repairVertex"));
		assertField("MS03_CONSUMERS", "2",
			fixtures.get("C2-MS-03-SHARED-DOWNLOAD").facts().get("consumerCount"));
		assertTrue(fixtures.get("C2-MS-03-SHARED-DOWNLOAD").obligations().get(0).startsWith("D:"));
		var upload = fixtures.get("C2-MS-04-ANCHORED-UPLOAD");
		assertTrue(upload.obligations().get(0).startsWith("U:"));
		assertEquals(2, upload.registries().size());
		var obligation = upload.selectedObligationObjects().stream()
			.map(org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.SelectedObligation.class::cast)
			.filter(candidate -> candidate.getKind() == ObligationKind.U).findFirst().orElseThrow();
		var refed = upload.registryObjects().stream().filter(FederatedRefedRegistry.AnchorSpec.class::isInstance)
			.map(FederatedRefedRegistry.AnchorSpec.class::cast).findFirst().orElseThrow();
		assertEquals("MS04 REFED registry must preserve the selected exact consumer IDs",
			obligation.getConsumerHopIds(), refed.getConsumerHopIds());
		var exactChild = upload.producerGraph().getHopRef(obligation.getChildHopId());
		assertEquals("MS04 must select exactly one direct matrix consumer", 1, refed.getConsumerHopIds().size());
		var exactConsumer = upload.producerGraph().getHopRef(refed.getConsumerHopIds().get(0));
		assertTrue("MS04 selected consumer must be matrix-valued", exactConsumer.getDataType().isMatrix());
		assertTrue("MS04 selected consumer must directly consume the exact child",
			exactConsumer.getInput().contains(exactChild));
		assertField("MS05_MISSING_ANCHOR", "CP/LOUT",
			fixtures.get("C2-MS-05-MISSING-ANCHOR").assignments().get("candidate"));
		Map<String,String> quartet = fixtures.get("C2-MS-06-STATE-QUARTET").assignments();
		assertField("MS06_CPL", "CP/LOUT", quartet.get("vCpl"));
		assertField("MS06_CPF", "CP/FOUT", quartet.get("vCpf"));
		assertField("MS06_FL", "FED/LOUT", quartet.get("vFl"));
		assertField("MS06_FF", "FED/FOUT", quartet.get("vFf"));
		for(var fixture : fixtures.values()) assertAliases(fixture.namedRoles(), fixture.literalAliases());
	}

	@Test
	public void corruptionsFailWithTheirOwnSurfaceCodes() {
		Map<String,String> expected = new LinkedHashMap<>();
		expected.put("ASSIGNMENT", "FED/FOUT"); expected.put("OBJECTIVE", "0x1.0p3");
		expected.put("ORDERED_TIE", "0"); expected.put("CUT_EDGE", "a->b@0x1.0p3");
		expected.put("CAPS_REPAIR", "FED/FOUT"); expected.put("D_OBLIGATION", "D:p->[c1,c2]");
		expected.put("U_OBLIGATION", "U:p->[c]"); expected.put("REFED_REGISTRY", "anchor=ROW");
		expected.put("FOUT_REGISTRY", "fType=ROW"); expected.put("LOCAL_REGISTRY", "consumers=2");
		expected.put("MISSING_ANCHOR", "CP/LOUT"); expected.put("ALIAS_BIJECTION", "1:1");
		for(Map.Entry<String,String> field : expected.entrySet()) {
			try {
				assertField(field.getKey(), field.getValue(), "CORRUPTED");
				fail("corruption admitted for " + field.getKey());
			}
			catch(AssertionError error) {
				assertTrue(error.getMessage(), error.getMessage().startsWith(field.getKey() + ":"));
			}
		}
	}

	private static Map<String,Fixture> dp() throws Exception {
		Map<String,Fixture> result = new LinkedHashMap<>();
		for(Fixture fixture : R4ExactPrivateCostDpFixtures.all())
			assertEquals("duplicate DP fixture", null, result.put(fixture.id(), fixture));
		return result;
	}

	private static Map<String,R4ExactPrivateCostMinstFixtures.Fixture> minst() throws Exception {
		Map<String,R4ExactPrivateCostMinstFixtures.Fixture> result = new LinkedHashMap<>();
		for(var fixture : R4ExactPrivateCostMinstFixtures.all())
			assertEquals("duplicate MinST fixture", null, result.put(fixture.id(), fixture));
		return result;
	}

	private static void assertAliases(Map<String,String> roles, Map<String,String> aliases) {
		assertEquals("literal aliases must be one-to-one", aliases.size(), new HashSet<>(aliases.values()).size());
		assertTrue("every alias must resolve to an independently named role", roles.keySet().containsAll(aliases.values()));
	}

	private static void assertField(String code, Object expected, Object actual) {
		if(!java.util.Objects.equals(expected, actual))
			throw new AssertionError(code + ": expected=<" + expected + "> actual=<" + actual + ">");
	}
}
