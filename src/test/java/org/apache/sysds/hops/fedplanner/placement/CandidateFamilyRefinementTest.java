/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class CandidateFamilyRefinementTest {
	private static final String FINGERPRINT = "candidate-family-refinement";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");
	private static final CandidateInputState ABSENT = CandidateInputState.absentLocal();
	private static final CandidateInputState ROW = CandidateInputState.present(FType.ROW);
	private static final CandidateInputState COL = CandidateInputState.present(FType.COL);

	@Test
	public void exactRowsAreRetainedByOneMatchingFamilyWithoutMaterialization() {
		CompiledHopKey owner = key("exact-to-family");
		CpRuleFamily replacement = family(owner, List.of(List.of(ABSENT, ROW)), header());
		CandidateRuleFact prior = fact(owner, List.of(ROW), header());

		assertTrue(CandidateFamilyRefinement.retainsExactFact(prior, List.of(replacement)));
		assertEquals(0, replacement.materializedMemberCount());
	}

	@Test
	public void reconciliationNeverResurrectsRowsReplacedByFamilyAuthority() {
		CompiledHopKey owner = key("reconcile");
		Header header = header();
		CandidateRuleFact priorRow = fact(owner, List.of(ROW), header);
		CandidateRuleFact priorCol = fact(owner, List.of(COL), header);
		CpRuleFamily rowFamily = family(owner, List.of(List.of(ROW)), header);

		assertTrue(PlacementRelationClosure.reconcilePhysicalSupports(
			List.of(), List.of(priorRow, priorCol)).isEmpty());
		CandidateRuleFact freshCol = fact(owner, List.of(COL), header);
		List<CandidateRuleFact> reconciled = PlacementRelationClosure.reconcilePhysicalSupports(
			List.of(freshCol), List.of(priorRow, priorCol));
		assertEquals(List.of(freshCol.key()), reconciled.stream().map(CandidateRuleFact::key).toList());
		assertTrue(CandidateFamilyRefinement.retainsExactFact(priorRow, List.of(rowFamily)));
		assertFalse(CandidateFamilyRefinement.retainsExactFact(priorCol, List.of(rowFamily)));
		assertEquals(0, rowFamily.materializedMemberCount());
	}

	@Test
	public void familyNarrowsToExactOrSmallerFamilyOnlyWithRefinementProof() {
		CompiledHopKey owner = key("narrow");
		Header header = header();
		CpRuleFamily prior = family(owner, List.of(List.of(ABSENT, ROW, COL)), header);
		CandidateRuleFact exact = fact(owner, List.of(ROW), header);
		List<List<FType>> singleton = List.of(domain(FType.ROW));
		assertTrue(CandidateFamilyRefinement.covers(prior, List.of(), List.of(exact), singleton, Set.of(0)));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(), List.of(exact), singleton, Set.of()));

		CpRuleFamily smaller = family(owner, List.of(List.of(ROW, COL)), header);
		List<List<FType>> narrowed = List.of(domain(FType.ROW, FType.COL));
		assertTrue(CandidateFamilyRefinement.covers(prior, List.of(smaller), List.of(), narrowed, Set.of(0)));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(smaller), List.of(), narrowed, Set.of()));
		assertEquals(0, prior.materializedMemberCount());
		assertEquals(0, smaller.materializedMemberCount());
	}

	@Test
	public void fullyClippedFamilyNeedsNoReplacementAuthority() {
		CompiledHopKey owner = key("empty");
		CpRuleFamily prior = family(owner, List.of(List.of(ROW)), header());
		assertTrue(CandidateFamilyRefinement.covers(prior, List.of(), List.of(),
			List.of(Collections.emptyList()), Set.of(0)));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(), List.of(),
			List.of(Collections.emptyList()), Set.of()));
	}

	@Test
	public void disjointSplitCoverageUsesCardinalityAndRejectsOverlapOrGap() {
		CompiledHopKey owner = key("split");
		Header header = header();
		CpRuleFamily prior = family(owner,
			List.of(List.of(ABSENT, ROW, COL), List.of(ROW, COL)), header);
		CpRuleFamily left = family(owner,
			List.of(List.of(ABSENT, ROW), List.of(ROW, COL)), header);
		CpRuleFamily right = family(owner,
			List.of(List.of(COL), List.of(ROW, COL)), header);
		List<List<FType>> domains = List.of(domain(null, FType.ROW, FType.COL),
			domain(FType.ROW, FType.COL));
		assertTrue(CandidateFamilyRefinement.covers(prior, List.of(left, right), List.of(), domains, Set.of()));

		CpRuleFamily overlap = family(owner,
			List.of(List.of(ROW, COL), List.of(ROW, COL)), header);
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(left, overlap), List.of(), domains, Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(left), List.of(), domains, Set.of()));
	}

	@Test
	public void explicitPointsDeduplicateButCannotOverlapFamilyAuthority() {
		CompiledHopKey owner = key("explicit");
		Header header = header();
		CpRuleFamily prior = family(owner, List.of(List.of(ROW, COL)), header);
		CandidateRuleFact row = fact(owner, List.of(ROW), header);
		CandidateRuleFact duplicateRow = fact(owner, List.of(ROW), header);
		CandidateRuleFact col = fact(owner, List.of(COL), header);
		List<List<FType>> domains = List.of(domain(FType.ROW, FType.COL));
		assertTrue(CandidateFamilyRefinement.covers(prior, List.of(),
			List.of(row, duplicateRow, col), domains, Set.of()));

		CpRuleFamily rowFamily = family(owner, List.of(List.of(ROW)), header);
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(rowFamily),
			List.of(row, col), domains, Set.of()));
	}

	@Test
	public void seededTinyRectanglesMatchExplicitSetCoverageOracle() {
		CompiledHopKey owner = key("seeded");
		Header header = header();
		List<CandidateInputState> first = List.of(ABSENT, ROW, COL);
		List<CandidateInputState> second = List.of(ROW, COL);
		CpRuleFamily prior = family(owner, List.of(first, second), header);
		List<List<FType>> domains = List.of(domain(null, FType.ROW, FType.COL),
			domain(FType.ROW, FType.COL));
		Random random = new Random(7643921L);
		for(int trial = 0; trial < 200; trial++) {
			List<CpRuleFamily> families = new ArrayList<>();
			List<CandidateRuleFact> facts = new ArrayList<>();
			Set<List<CandidateInputState>> explicitOracle = new java.util.HashSet<>();
			for(CandidateInputState left : first)
				for(CandidateInputState right : second) {
					List<CandidateInputState> point = List.of(left, right);
					switch(random.nextInt(3)) {
						case 0 -> { /* uncovered */ }
						case 1 -> {
							families.add(family(owner, List.of(List.of(left), List.of(right)), header));
							explicitOracle.add(point);
						}
						default -> {
							facts.add(fact(owner, point, header));
							explicitOracle.add(point);
						}
					}
				}
			assertEquals(explicitOracle.size() == first.size() * second.size(),
				CandidateFamilyRefinement.covers(prior, families, facts, domains, Set.of()));
		}
	}

	@Test
	public void fullHeaderAndOwnerIdentityAreRequired() {
		CompiledHopKey owner = key("header");
		Header expected = header();
		CpRuleFamily prior = family(owner, List.of(List.of(ROW)), expected);
		List<List<FType>> domains = List.of(domain(FType.ROW));

		assertFalse(CandidateFamilyRefinement.covers(prior,
			List.of(family(key("header"), List.of(List.of(ROW)), expected)), List.of(), domains, Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(prior,
			List.of(family(owner, List.of(List.of(ROW)), expected.withCapability("foreign"))),
			List.of(), domains, Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(prior,
			List.of(family(owner, List.of(List.of(ROW)), expected.withConsulted(Map.of("rows", "1")))),
			List.of(), domains, Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(prior,
			List.of(family(owner, List.of(List.of(ROW)), expected.withEmission(true))),
			List.of(), domains, Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(), List.of(
			fact(owner, List.of(ROW), expected.withProfile(List.of(FType.ROW)))), domains, Set.of()));
	}

	@Test
	public void zeroArityAndHugeProductsRemainSymbolic() {
		CompiledHopKey zeroOwner = key("zero");
		CpRuleFamily zeroPrior = family(zeroOwner, List.of(), header());
		CpRuleFamily zeroReplacement = family(zeroOwner, List.of(), header());
		assertTrue(CandidateFamilyRefinement.covers(zeroPrior, List.of(zeroReplacement),
			List.of(), List.of(), Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(zeroPrior, List.of(),
			List.of(), List.of(), Set.of()));

		CompiledHopKey hugeOwner = key("huge");
		List<CandidateInputState> all = allStates();
		List<List<CandidateInputState>> axes = new ArrayList<>();
		List<List<FType>> domains = new ArrayList<>();
		for(int position = 0; position < 40; position++) {
			axes.add(all);
			domains.add(allTypes());
		}
		CpRuleFamily hugePrior = family(hugeOwner, axes, header());
		CpRuleFamily hugeReplacement = family(hugeOwner, axes, header());
		assertTrue(hugePrior.logicalSize().bitLength() > 100);
		assertTrue(CandidateFamilyRefinement.covers(hugePrior, List.of(hugeReplacement),
			List.of(), domains, Set.of()));
		assertEquals(0, hugePrior.materializedMemberCount());
		assertEquals(0, hugeReplacement.materializedMemberCount());
	}

	@Test
	public void unavailableOutsideAndMalformedAuthoritiesFailClosed() {
		CompiledHopKey owner = key("malformed");
		Header header = header();
		CpRuleFamily prior = family(owner, List.of(List.of(ROW)), header);
		CandidateRuleFact unavailable = new CandidateRuleFact(
			new CandidateRuleKey(owner, List.of(ROW)), CandidateEvaluationStatus.PRIVACY_EXCLUDED,
			header.capability(), header.shapeProof(), header.profile(), List.of(), "PRIVATE");
		assertFalse(CandidateFamilyRefinement.retainsExactFact(unavailable,
			List.of(family(owner, List.of(List.of(ROW)), header))));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(), List.of(unavailable),
			List.of(domain(FType.ROW)), Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(),
			List.of(fact(owner, List.of(COL), header)), List.of(domain(FType.ROW)), Set.of()));
		assertFalse(CandidateFamilyRefinement.covers(prior, List.of(), List.of(),
			List.of(domain(FType.ROW), domain(FType.COL)), Set.of()));
	}

	@Test
	public void inventoryComparisonUsesAxesAndEveryHeaderField() {
		CompiledHopKey owner = key("inventory");
		Header header = header();
		CpRuleFamily first = family(owner, List.of(List.of(ABSENT, ROW)), header);
		CpRuleFamily equal = family(owner, List.of(List.of(ABSENT, ROW)), header);
		assertTrue(CandidateFamilyRefinement.sameFamilyInventory(List.of(first), List.of(equal)));
		assertFalse(CandidateFamilyRefinement.sameFamilyInventory(List.of(first), List.of(
			family(owner, List.of(List.of(ROW, ABSENT)), header))));
		assertFalse(CandidateFamilyRefinement.sameFamilyInventory(List.of(first), List.of(
			family(owner, List.of(List.of(ABSENT, ROW)), header.withConsulted(Map.of("cols", "9"))))));
	}

	@Test
	public void domainProjectionIncludesRowsAndFamiliesButRejectsSwapOrExpansion() {
		CompiledHopKey owner = key("projection");
		CpRuleFamily family = family(owner,
			List.of(List.of(ABSENT, ROW, COL), List.of(ROW, COL)), header());
		List<List<CandidateInputState>> rows = List.of(List.of(ABSENT, ROW));
		assertEquals(Set.of(0), CandidateDomainRefinement.refinedInputPositions(rows, List.of(family),
			List.of(domain(FType.ROW, FType.COL), domain(FType.ROW, FType.COL))));
		assertEquals(Set.of(), CandidateDomainRefinement.refinedInputPositions(rows, List.of(family),
			List.of(domain(FType.FULL), domain(FType.ROW, FType.COL))));
		assertEquals(Set.of(), CandidateDomainRefinement.refinedInputPositions(rows, List.of(family),
			List.of(domain(null, FType.ROW, FType.COL, FType.FULL), domain(FType.ROW, FType.COL))));
	}

	private static CpRuleFamily family(CompiledHopKey owner,
		List<List<CandidateInputState>> axes, Header header) {
		return new CpRuleFamily(owner, axes, header.capability(), header.shapeProof(),
			header.profile(), header.emission());
	}

	private static CandidateRuleFact fact(CompiledHopKey owner,
		List<CandidateInputState> inputs, Header header) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, inputs),
			CandidateEvaluationStatus.AVAILABLE, header.capability(), header.shapeProof(),
			header.profile(), List.of(header.emission()), "");
	}

	private static Header header() {
		CandidateCapabilityFact capability = new CandidateCapabilityFact(OpCategory.OTHER,
			"nrow", ExecType.CP, FederatedOutput.LOUT, null, ReasonCode.NO_RULE,
			"metadata", List.of());
		CandidateEmissionFact emission = new CandidateEmissionFact(
			new PlacementEmissionState(new PlacementState(
				ExecType.CP, FederatedOutput.LOUT, null, false), false), null);
		return new Header(capability, new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emission);
	}

	private static List<FType> domain(FType... types) {
		return Collections.unmodifiableList(Arrays.asList(types));
	}

	private static List<CandidateInputState> allStates() {
		List<CandidateInputState> result = new ArrayList<>();
		result.add(ABSENT);
		for(FType type : FType.values())
			result.add(CandidateInputState.present(type));
		return List.copyOf(result);
	}

	private static List<FType> allTypes() {
		List<FType> result = new ArrayList<>();
		result.add(null);
		result.addAll(Arrays.asList(FType.values()));
		return Collections.unmodifiableList(result);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", REGION, id, id);
	}

	private record Header(CandidateCapabilityFact capability, CandidateShapeProofFact shapeProof,
		CandidateProfileFact profile, CandidateEmissionFact emission) {
		Header withCapability(String detail) {
			return new Header(new CandidateCapabilityFact(capability.category(), capability.opcode(),
				capability.nativeExec(), capability.nativeOutput(), capability.nativeFoutFType(),
				capability.reasonCode(), detail, capability.notes()), shapeProof, profile, emission);
		}
		Header withConsulted(Map<String,String> consulted) {
			return new Header(capability,
				new CandidateShapeProofFact(consulted, List.of(), List.of()), profile, emission);
		}
		Header withProfile(List<FType> outputs) {
			return new Header(capability, shapeProof, new CandidateProfileFact(outputs, ""), emission);
		}
		Header withEmission(boolean shapeDependent) {
			return new Header(capability, shapeProof, profile, new CandidateEmissionFact(
				new PlacementEmissionState(new PlacementState(
					ExecType.CP, FederatedOutput.LOUT, null, shapeDependent), false), null));
		}
	}
}
