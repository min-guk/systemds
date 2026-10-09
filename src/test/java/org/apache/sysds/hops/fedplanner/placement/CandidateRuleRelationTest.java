/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class CandidateRuleRelationTest {
	@Test
	public void nativeHeaderSupportStaysCompressedUntilItsSignatureIsRequested() {
		var realization = NativeContinuitySupportFixtureBridge.realization("lazy-header", 3, 4);
		var template = header(FType.ROW);
		var nativeHeader = new CandidateRuleRelation.Header(template.capability(), template.shapeProof(),
			template.profile(), List.of(new CandidateEmissionFact(realization.key().emissionState(),
				FType.ROW, null, List.of(realization))));
		var axes = List.of(List.of(present(FType.ROW), present(FType.COL)), List.of(absent()));
		var region = new CandidateRuleRelation.ConditionalRegion(axes, nativeHeader);
		var relation = new CandidateRuleRelation(parent(), List.of(region));
		var mapped = relation.mapEmissions(java.util.function.UnaryOperator.identity());

		assertEquals("constructing and mapping a relation must not expand its native support", 0,
			realization.fullyMaterializedSupportClauseCount());
		assertEquals(BigInteger.valueOf(2), relation.logicalSize());
		assertTrue(relation.contains(List.of(present(FType.COL), absent())));
		assertSame(realization, relation.requireExact(List.of(present(FType.ROW), absent()))
			.allowedEmissionFacts().get(0).realizations().get(0));
		assertEquals(0, realization.fullyMaterializedSupportClauseCount());

		String expectedRegion = "axes=" + axes.stream().map(axis -> axis.stream()
			.map(CandidateInputState::normalizedSignature).toList()).toList()
			+ "|header=" + nativeHeader.normalizedSignature();
		String expected = parent().normalizedSignature() + "|candidateRelation=" + List.of(expectedRegion);
		assertEquals(expectedRegion, region.normalizedSignature());
		assertEquals(expected, relation.normalizedSignature());
		assertSame(relation.normalizedSignature(), relation.normalizedSignature());
		assertEquals(expected, mapped.normalizedSignature());
		assertEquals(12, realization.fullyMaterializedSupportClauseCount());
	}

	@Test
	public void conditionedHeaderDefersSupportButValidatesAndSnapshotsTheAuthoritySignature() {
		var realization = NativeContinuitySupportFixtureBridge.realization("lazy-conditioned", 2, 3);
		var template = header(FType.ROW);
		var nativeHeader = new CandidateRuleRelation.Header(template.capability(), template.shapeProof(),
			template.profile(), List.of(new CandidateEmissionFact(realization.key().emissionState(),
				FType.ROW, null, List.of(realization))));
		AtomicInteger signatureCalls = new AtomicInteger();
		CandidateRuleRelation.MemberEmissions authority = new CandidateRuleRelation.MemberEmissions() {
			@Override public List<CandidateEmissionFact> resolve(List<CandidateInputState> inputs) {
				return nativeHeader.emissions();
			}
			@Override public String normalizedSignature() {
				return "closed-authority-" + signatureCalls.incrementAndGet();
			}
			@Override public long storedChoiceCount() { return 6; }
		};
		var axes = List.of(List.of(present(FType.ROW)));
		var region = CandidateRuleRelation.ConditionalRegion.withConditionedEmissions(
			axes, nativeHeader, authority);
		var relation = new CandidateRuleRelation(parent(), List.of(region));
		assertEquals(1, signatureCalls.get());
		assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		assertSame(realization, relation.requireExact(List.of(present(FType.ROW)))
			.allowedEmissionFacts().get(0).realizations().get(0));
		assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		assertTrue(relation.normalizedSignature().contains("|closedEmissions=closed-authority-1"));
		assertEquals(1, signatureCalls.get());
		assertEquals(6, realization.fullyMaterializedSupportClauseCount());

		CandidateRuleRelation.MemberEmissions invalid = new CandidateRuleRelation.MemberEmissions() {
			@Override public List<CandidateEmissionFact> resolve(List<CandidateInputState> inputs) {
				return nativeHeader.emissions();
			}
			@Override public String normalizedSignature() { return null; }
			@Override public long storedChoiceCount() { return 0; }
		};
		assertThrows(NullPointerException.class, () ->
			CandidateRuleRelation.ConditionalRegion.withConditionedEmissions(axes, nativeHeader, invalid));
	}

	@Test
	public void conditionedEmissionsStayLazyImmutableAndMemoizedByExactMember() {
		AtomicInteger resolutions = new AtomicInteger();
		List<CandidateEmissionFact> mutableAuthority = new ArrayList<>();
		mutableAuthority.add(header(FType.ROW).emissions().get(0));
		CandidateRuleRelation.MemberEmissions authority = new CandidateRuleRelation.MemberEmissions() {
			@Override public List<CandidateEmissionFact> resolve(List<CandidateInputState> inputs) {
				resolutions.incrementAndGet();
				return mutableAuthority;
			}
			@Override public String normalizedSignature() { return "closed-authority-v1"; }
			@Override public long storedChoiceCount() { return 1; }
		};
		CandidateRuleRelation.ConditionalRegion region =
			CandidateRuleRelation.ConditionalRegion.withConditionedEmissions(
				List.of(List.of(present(FType.ROW), present(FType.COL)), List.of(absent())),
				header(FType.ROW), authority);
		CandidateRuleRelation relation = new CandidateRuleRelation(parent(), List.of(region));
		List<CandidateInputState> row = List.of(present(FType.ROW), absent());

		assertEquals(0, resolutions.get());
		assertTrue(relation.contains(row));
		assertTrue(relation.normalizedSignature().contains("closed-authority-v1"));
		assertEquals(0, resolutions.get());
		CandidateRuleFact first = relation.requireExact(row);
		assertSame(first, relation.requireExact(row));
		assertEquals(1, resolutions.get());
		mutableAuthority.clear();
		assertEquals("resolved authority is copied before the mutable source changes", 1,
			first.allowedEmissionFacts().size());
		assertThrows(UnsupportedOperationException.class,
			() -> first.allowedEmissionFacts().add(header(FType.COL).emissions().get(0)));
		assertEquals(1, region.materializedEmissionMemberCount());
	}

	@Test
	public void conditionedEmissionsRejectHolesAndCannotBeMappedWithoutRebindingAuthority() {
		AtomicInteger resolutions = new AtomicInteger();
		CandidateRuleRelation.MemberEmissions authority = new CandidateRuleRelation.MemberEmissions() {
			@Override public List<CandidateEmissionFact> resolve(List<CandidateInputState> inputs) {
				resolutions.incrementAndGet();
				return header(FType.ROW).emissions();
			}
			@Override public String normalizedSignature() { return "closed-authority-hole"; }
			@Override public long storedChoiceCount() { return 1; }
		};
		CandidateRuleRelation.ConditionalRegion region =
			CandidateRuleRelation.ConditionalRegion.withConditionedEmissions(
				List.of(List.of(present(FType.ROW)), List.of(absent())), header(FType.ROW), authority);
		CandidateRuleRelation relation = new CandidateRuleRelation(parent(), List.of(region));
		List<CandidateInputState> hole = List.of(present(FType.COL), absent());

		assertFalse(relation.contains(hole));
		assertThrows(IllegalArgumentException.class, () -> relation.requireExact(hole));
		assertThrows(UnsupportedOperationException.class,
			() -> relation.mapEmissions(java.util.function.UnaryOperator.identity()));
		assertEquals("membership and rejected rebinding never resolve member emissions", 0,
			resolutions.get());
	}

	@Test
	public void preservesConditionalHeadersAndInternsOnlyRequestedMembers() {
		CandidateRuleRelation relation = relation();
		assertEquals(BigInteger.valueOf(4), relation.logicalSize());
		assertEquals(0, relation.materializedMemberCount());
		assertTrue(relation.contains(relation.canonicalInputs()));
		assertTrue(relation.regionFor(relation.canonicalInputs()).isPresent());
		assertEquals(0, relation.materializedMemberCount());

		List<CandidateInputState> rowInputs = List.of(present(FType.ROW), absent());
		CandidateRuleFact row = relation.requireExact(rowInputs);
		assertSame(row, relation.requireExact(rowInputs));
		assertEquals(FType.ROW, row.profile().producerOutputs().get(0));
		assertEquals(FType.ROW, row.allowedEmissionFacts().get(0).executionFType());
		assertEquals(1, relation.materializedMemberCount());

		CandidateRuleFact col = relation.requireExact(
			List.of(present(FType.COL), present(FType.BROADCAST)));
		assertEquals(FType.COL, col.profile().producerOutputs().get(0));
		assertEquals(FType.COL, col.allowedEmissionFacts().get(0).executionFType());
		assertEquals(2, relation.materializedMemberCount());
		assertFalse(relation.contains(List.of(absent(), absent())));
		assertThrows(IllegalArgumentException.class,
			() -> relation.requireExact(List.of(absent(), absent())));
	}

	@Test
	public void mapsEveryConditionalEmissionWithoutExpandingTheRelation() {
		CandidateRuleRelation relation = relation();
		CandidateRuleRelation mapped = relation.mapEmissions(emission -> {
			PlacementState rebound = new PlacementState(ExecType.FED, FederatedOutput.LOUT,
				emission.executionFType(), true);
			return new CandidateEmissionFact(new PlacementEmissionState(rebound, false),
				emission.executionFType());
		});

		assertEquals(0, relation.materializedMemberCount());
		assertEquals(0, mapped.materializedMemberCount());
		assertTrue(mapped.normalizedSignature().contains("SHAPE_DEPENDENT"));
		assertTrue(mapped.requireExact(List.of(present(FType.ROW), absent()))
			.allowedEmissionFacts().get(0).emissionState().placementState().shapeDependent());
		assertEquals(0, relation.materializedMemberCount());
	}

	@Test
	public void rejectsOverlappingConditionalRegions() {
		List<List<CandidateInputState>> axes = List.of(
			List.of(present(FType.ROW)), List.of(absent(), present(FType.BROADCAST)));
		CandidateRuleRelation.ConditionalRegion first =
			new CandidateRuleRelation.ConditionalRegion(axes, header(FType.ROW));
		CandidateRuleRelation.ConditionalRegion duplicate =
			new CandidateRuleRelation.ConditionalRegion(axes, header(FType.COL));

		assertThrows(IllegalArgumentException.class,
			() -> new CandidateRuleRelation(parent(), List.of(first, duplicate)));
	}

	@Test
	public void analysisFactsRestoreAnArbitraryRelationMemberWithoutExplicitRows() {
		CandidateRuleRelation relation = relation();
		CandidateRuleDomain domain = new CandidateRuleDomain("candidate-relation",
			List.of(), List.of(), List.of(), List.of(), List.of(relation));
		CandidateRuleFacts facts = new CandidateRuleFacts(domain, List.of(), List.of(),
			List.of(relation));
		List<CandidateInputState> inputs = List.of(
			present(FType.COL), present(FType.BROADCAST));

		CandidateRuleFact exact = facts.requireExact(relation.parent(), inputs);
		assertSame(exact, facts.requireExact(relation.parent(), inputs));
		assertEquals(FType.COL, exact.allowedEmissionFacts().get(0).executionFType());
		assertEquals(1, relation.materializedMemberCount());
	}

	@Test
	public void legacyFlatConsumerRestoresEveryNonSingletonRelationRow() {
		CandidateRuleRelation relation = relation();
		List<CandidateRuleFact> rows = new java.util.ArrayList<>();

		relation.forEachExactMember(rows::add);

		assertEquals(relation.logicalSize().intValueExact(), rows.size());
		assertEquals(4, relation.materializedMemberCount());
		assertEquals(List.of(
			List.of(present(FType.ROW), absent()),
			List.of(present(FType.ROW), present(FType.BROADCAST)),
			List.of(present(FType.COL), absent()),
			List.of(present(FType.COL), present(FType.BROADCAST))),
			rows.stream().map(row -> row.key().orderedInputs()).toList());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void closedRelationCompactionPreservesRectanglesHolesAndExactHeaders() throws Exception {
		CandidateRuleRelation.Header shared = header(FType.ROW);
		List<CandidateRuleFact> rectangle = List.of(
			closedFact(List.of(present(FType.ROW), absent()), shared),
			closedFact(List.of(present(FType.COL), absent()), shared),
			closedFact(List.of(present(FType.ROW), present(FType.BROADCAST)), shared),
			closedFact(List.of(present(FType.COL), present(FType.BROADCAST)), shared));
		var method = PlacementRelationClosure.class.getDeclaredMethod(
			"compactClosedRelationRegions", List.class);
		method.setAccessible(true);
		List<CandidateRuleRelation.ConditionalRegion> compact =
			(List<CandidateRuleRelation.ConditionalRegion>) method.invoke(null, rectangle);
		assertEquals(1, compact.size());
		assertEquals(BigInteger.valueOf(4), compact.get(0).logicalSize());

		List<CandidateRuleRelation.ConditionalRegion> sparse =
			(List<CandidateRuleRelation.ConditionalRegion>) method.invoke(null,
				rectangle.subList(0, 3));
		CandidateRuleRelation sparseRelation = new CandidateRuleRelation(parent(), sparse);
		assertEquals(BigInteger.valueOf(3), sparseRelation.logicalSize());
		assertFalse(sparseRelation.contains(
			List.of(present(FType.COL), present(FType.BROADCAST))));

		CandidateRuleRelation.Header distinctAuthority = header(FType.ROW);
		List<CandidateRuleRelation.ConditionalRegion> separated =
			(List<CandidateRuleRelation.ConditionalRegion>) method.invoke(null, List.of(
				closedFact(List.of(present(FType.ROW), absent()), shared),
				closedFact(List.of(present(FType.COL), absent()), distinctAuthority)));
		assertEquals("equal geometry with distinct exact emission authority must not merge",
			2, separated.size());
	}

	private static CandidateRuleRelation relation() {
		List<CandidateInputState> auxiliary = List.of(absent(), present(FType.BROADCAST));
		return new CandidateRuleRelation(parent(), List.of(
			new CandidateRuleRelation.ConditionalRegion(
				List.of(List.of(present(FType.ROW)), auxiliary), header(FType.ROW)),
			new CandidateRuleRelation.ConditionalRegion(
				List.of(List.of(present(FType.COL)), auxiliary), header(FType.COL))));
	}

	private static CandidateRuleRelation.Header header(FType type) {
		CandidateCapabilityFact capability = new CandidateCapabilityFact(OpCategory.QUATERNARY,
			"WSLOSS", ExecType.FED, FederatedOutput.LOUT, null, ReasonCode.OK,
			"conditional-" + type, List.of());
		PlacementState state = new PlacementState(ExecType.FED, FederatedOutput.LOUT, type, false);
		return new CandidateRuleRelation.Header(capability,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(type), ""),
			List.of(new CandidateEmissionFact(new PlacementEmissionState(state, false), type)));
	}

	private static CandidateRuleFact closedFact(List<CandidateInputState> inputs,
		CandidateRuleRelation.Header header) {
		return new CandidateRuleFact(new CandidateRuleKey(parent(), inputs),
			CandidateEvaluationStatus.AVAILABLE, header.capability(), header.shapeProof(),
			header.profile(), header.emissions(), "");
	}

	private static CandidateInputState present(FType type) {
		return CandidateInputState.present(type);
	}

	private static CandidateInputState absent() {
		return CandidateInputState.absentLocal();
	}

	private static CompiledHopKey parent() {
		ControlRegionKey region = new ControlRegionKey(
			"candidate-relation", "main", List.of("root"), "root", "compiled");
		return new CompiledHopKey("candidate-relation", "main", "root", "compiled",
			region, "weighted", "weighted");
	}
}
