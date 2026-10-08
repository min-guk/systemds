/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class FactorizedSupportClausesTest {
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
	private static final PlacementRealizationKey KEY = PlacementRealizationKey.local(EMISSION);

	@Test
	public void containedMultiAxisRectangleMergesWithoutExpansion() {
		List<CandidateRealizationInputBinding> left = List.of(binding(0, "contained-a"), binding(0, "contained-b"));
		List<CandidateRealizationInputBinding> right = List.of(binding(1, "contained-c"), binding(1, "contained-d"));
		CandidateEmissionRealization full = CandidateEmissionRealization.factorized(
			KEY, List.of(), List.of(left, right), null, true);
		CandidateEmissionRealization part = CandidateEmissionRealization.factorized(
			KEY, List.of(), List.of(left.subList(0, 1), right.subList(0, 1)), null, true);
		for(List<CandidateEmissionRealization> order : List.of(List.of(full, part), List.of(part, full))) {
			CandidateEmissionRealization merged = new CandidateEmissionFact(EMISSION, null, null, order)
				.realizations().get(0);
			Assert.assertTrue("containment must preserve a rectangle", merged.supportClauses() instanceof FactorizedSupportClauses);
			Assert.assertTrue(((FactorizedSupportClauses)full.supportClauses()).sameExactAuthority(
				(FactorizedSupportClauses)merged.supportClauses()));
		}
		Assert.assertEquals(0, ((FactorizedSupportClauses)full.supportClauses()).materializedClauseCount());
		Assert.assertEquals(0, ((FactorizedSupportClauses)part.supportClauses()).materializedClauseCount());
	}

	@Test
	public void rectangleContainmentDoesNotFillHolesOrAcceptForeignAuthority() {
		List<CandidateRealizationInputBinding> left = List.of(binding(0, "hole-a"), binding(0, "hole-b"));
		List<CandidateRealizationInputBinding> right = List.of(binding(1, "hole-c"), binding(1, "hole-d"));
		FactorizedSupportClauses first = FactorizedSupportClauses.of(List.of(),
			List.of(left, right.subList(0, 1)), null, true);
		FactorizedSupportClauses second = FactorizedSupportClauses.of(List.of(),
			List.of(left.subList(0, 1), right), null, true);
		Assert.assertTrue("three of four tuples do not form a rectangle", first.oneAxisUnion(second).isEmpty());
		FactorizedSupportClauses full = FactorizedSupportClauses.of(List.of(), List.of(left, right), null, true);
		FactorizedSupportClauses foreign = FactorizedSupportClauses.of(List.of(),
			List.of(List.of(binding(0, "hole-a")), List.of(right.get(0))), null, true);
		Assert.assertTrue("equal source values must not substitute for owner identity", full.oneAxisUnion(foreign).isEmpty());
		Assert.assertEquals(0, full.materializedClauseCount());
	}

	@Test
	public void bulkMergeMetricsAreConstantTimeAndSaturating() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		metrics.recordRealizationMergeClauses(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1);
		metrics.recordRealizationMergeClauses(10, 10);
		Assert.assertEquals(Long.MAX_VALUE,
			metrics.snapshot().realizationMergeUniqueClauses());
		Assert.assertEquals(Long.MAX_VALUE,
			metrics.snapshot().realizationMergeDuplicateClauses());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> metrics.recordRealizationMergeClauses(-1, 0));
	}

	@Test
	public void factorizedRelocationMetricsPreserveEnumeratorPrefixCounts() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		metrics.recordFactorizedRelocationProduct(4, 7, 2);
		Assert.assertEquals(7, metrics.snapshot().relocationPrefixes());
		Assert.assertEquals(4, metrics.snapshot().relocationLeaves());
		Assert.assertEquals(2, metrics.snapshot().relocationPeakDepth());
	}

	@Test
	public void independentProductPreservesCanonicalListContractLazily() {
		PlacementProofKey proof = proof(key("proof-owner"));
		List<CandidateRealizationInputBinding> left = List.of(
			binding(0, "left-b"), binding(0, "left-a"));
		List<CandidateRealizationInputBinding> right = List.of(
			binding(1, "right-c"), binding(1, "right-a"), binding(1, "right-b"));

		CandidateEmissionRealization factorized = CandidateEmissionRealization.factorized(
			KEY, List.of(proof), List.of(right, left), null, true);
		FactorizedSupportClauses relation =
			(FactorizedSupportClauses)factorized.supportClauses();
		Assert.assertEquals(6, relation.size());
		Assert.assertEquals(5, relation.retainedFactorOptionCount());
		Assert.assertEquals("construction must not retain Cartesian clauses",
			0, relation.materializedClauseCount());

		List<CandidateRealizationSupportClause> expanded = new ArrayList<>();
		for(CandidateRealizationInputBinding leftBinding : left)
			for(CandidateRealizationInputBinding rightBinding : right)
				expanded.add(new CandidateRealizationSupportClause(
					List.of(proof), List.of(leftBinding, rightBinding)));
		CandidateEmissionRealization explicit = new CandidateEmissionRealization(KEY, expanded);
		Assert.assertEquals(factorized.supportClauses(), explicit.supportClauses());
		Assert.assertEquals(explicit.supportClauses().hashCode(), factorized.supportClauses().hashCode());
		Assert.assertEquals(0, relation.materializedClauseCount());
		Assert.assertEquals(explicit.supportClauses(), factorized.supportClauses());
		Assert.assertEquals(explicit.normalizedSignature(), factorized.normalizedSignature());
		Assert.assertSame(relation.get(2), relation.get(2));
		Assert.assertEquals(6, relation.materializedClauseCount());
	}

	@Test
	public void algebraicHashMatchesThreeAxisExplicitProduct() {
		PlacementProofKey proof = proof(key("three-axis-proof"));
		List<CandidateRealizationInputBinding> first = List.of(
			binding(0, "first-b"), binding(0, "first-a"));
		List<CandidateRealizationInputBinding> second = List.of(
			binding(1, "second-c"), binding(1, "second-a"), binding(1, "second-b"));
		List<CandidateRealizationInputBinding> third = List.of(
			binding(2, "third-b"), binding(2, "third-a"));
		CandidateEmissionRealization factorized = CandidateEmissionRealization.factorized(
			KEY, List.of(proof), List.of(third, first, second), null, true);
		FactorizedSupportClauses relation =
			(FactorizedSupportClauses)factorized.supportClauses();
		List<CandidateRealizationSupportClause> expanded = new ArrayList<>();
		for(CandidateRealizationInputBinding firstBinding : first)
			for(CandidateRealizationInputBinding secondBinding : second)
				for(CandidateRealizationInputBinding thirdBinding : third)
					expanded.add(new CandidateRealizationSupportClause(List.of(proof),
						List.of(firstBinding, secondBinding, thirdBinding)));
		List<CandidateRealizationSupportClause> canonical =
			new CandidateEmissionRealization(KEY, expanded).supportClauses();
		Assert.assertEquals(canonical.hashCode(), relation.hashCode());
		Assert.assertTrue(relation.equals(canonical));
		Assert.assertEquals(0, relation.materializedClauseCount());
	}

	@Test
	public void retainedRepresentationScalesWithFactorOptions() {
		List<CandidateRealizationInputBinding> left = new ArrayList<>();
		List<CandidateRealizationInputBinding> right = new ArrayList<>();
		for(int option = 0; option < 100; option++) {
			left.add(binding(0, "left-" + option));
			right.add(binding(1, "right-" + option));
		}
		CandidateEmissionRealization realization = CandidateEmissionRealization.factorized(
			KEY, List.of(), List.of(left, right), null, true);
		FactorizedSupportClauses relation =
			(FactorizedSupportClauses)realization.supportClauses();
		Assert.assertEquals(10_000, relation.size());
		Assert.assertEquals(200, relation.retainedFactorOptionCount());
		Assert.assertEquals(0, relation.materializedClauseCount());

		CandidateEmissionRealization equalRealization = CandidateEmissionRealization.factorized(
			KEY, List.of(), List.of(right, left), null, true);
		FactorizedSupportClauses equalRelation =
			(FactorizedSupportClauses)equalRealization.supportClauses();
		Assert.assertEquals(relation, equalRelation);
		Assert.assertEquals(relation.hashCode(), equalRelation.hashCode());
		Assert.assertEquals(realization.hashCode(), equalRealization.hashCode());
		Assert.assertEquals(0, relation.materializedClauseCount());
		Assert.assertEquals(0, equalRelation.materializedClauseCount());

		CandidateRealizationSupportClause member = new CandidateRealizationSupportClause(
			List.of(), List.of(left.get(17), right.get(23)));
		Assert.assertTrue(relation.contains(member));
		Assert.assertTrue(relation.indexOf(member) >= 0);
		Assert.assertEquals(0, relation.materializedClauseCount());

		CandidateEmissionFact merged = new CandidateEmissionFact(
			EMISSION, null, null, List.of(realization, equalRealization));
		Assert.assertSame(realization, merged.realizations().get(0));
		Assert.assertEquals(0, relation.materializedClauseCount());
		Assert.assertEquals(0, equalRelation.materializedClauseCount());
	}

	@Test
	public void oneAxisUnionRemainsFactorizedAndExact() {
		List<CandidateRealizationInputBinding> common = List.of(
			binding(0, "left-a"), binding(0, "left-b"));
		List<CandidateRealizationInputBinding> firstTail = List.of(binding(1, "right-a"));
		List<CandidateRealizationInputBinding> secondTail = List.of(binding(1, "right-b"));
		CandidateEmissionRealization first = CandidateEmissionRealization.factorized(
			KEY, List.of(), List.of(common, firstTail), null, true);
		CandidateEmissionRealization second = CandidateEmissionRealization.factorized(
			KEY, List.of(), List.of(common, secondTail), null, true);

		CandidateEmissionFact merged = new CandidateEmissionFact(
			EMISSION, null, null, List.of(first, second));
		FactorizedSupportClauses union =
			(FactorizedSupportClauses)merged.realizations().get(0).supportClauses();
		Assert.assertEquals(4, union.size());
		Assert.assertEquals(4, union.retainedFactorOptionCount());
		Assert.assertEquals(0, union.materializedClauseCount());
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>();
		for(CandidateRealizationInputBinding head : common)
			for(CandidateRealizationInputBinding tail : List.of(firstTail.get(0), secondTail.get(0)))
				explicit.add(new CandidateRealizationSupportClause(List.of(), List.of(head, tail)));
		Assert.assertTrue(union.equals(
			new CandidateEmissionRealization(KEY, explicit).supportClauses()));
		Assert.assertEquals(0, union.materializedClauseCount());
	}

	@Test
	public void rectangularExplicitRelationCanCompressWithoutRetainingClauses() {
		PlacementProofKey proof = proof(key("proof-owner"));
		List<CandidateRealizationInputBinding> left = List.of(binding(0, "left-a"), binding(0, "left-b"));
		List<CandidateRealizationInputBinding> right = List.of(binding(1, "right-a"), binding(1, "right-b"));
		List<CandidateRealizationSupportClause> clauses = product(proof, left, right);
		CandidateEmissionRealization compressed = CandidateEmissionRealization.tryFactorize(KEY, clauses)
			.orElseThrow();
		FactorizedSupportClauses relation = (FactorizedSupportClauses)compressed.supportClauses();
		Assert.assertEquals(4, relation.size());
		Assert.assertEquals(0, relation.materializedClauseCount());
		Assert.assertEquals(new CandidateEmissionRealization(KEY, clauses).supportClauses(), relation);
	}

	@Test
	public void correlatedHoleRemainsExplicit() {
		PlacementProofKey proof = proof(key("proof-owner"));
		CandidateRealizationInputBinding a = binding(0, "left-a");
		CandidateRealizationInputBinding b = binding(0, "left-b");
		CandidateRealizationInputBinding c = binding(1, "right-a");
		CandidateRealizationInputBinding d = binding(1, "right-b");
		List<CandidateRealizationSupportClause> clauses = List.of(
			clause(proof, a, c), clause(proof, a, d), clause(proof, b, c));
		Assert.assertTrue(CandidateEmissionRealization.tryFactorize(KEY, clauses).isEmpty());
	}

	@Test
	public void repeatedSourceOwnerUsesOneChoiceAcrossAxesWithoutTupleStorage() {
		CompiledHopKey shared = key("shared"), independent = key("independent");
		List<CandidateRealizationInputBinding> first = new ArrayList<>();
		List<CandidateRealizationInputBinding> second = new ArrayList<>();
		List<CandidateRealizationInputBinding> third = new ArrayList<>();
		for(int option = 0; option < 100; option++) {
			CandidateRealizationReference sharedChoice = reference(shared, option);
			first.add(CandidateRealizationInputBinding.direct(0, sharedChoice));
			second.add(CandidateRealizationInputBinding.direct(1, sharedChoice));
			third.add(CandidateRealizationInputBinding.direct(2, reference(independent, option)));
		}
		CandidateEmissionRealization compact = CandidateEmissionRealization.factorized(
			KEY, List.of(), List.of(third, second, first), null, true);
		FactorizedSupportClauses relation = (FactorizedSupportClauses)compact.supportClauses();
		Assert.assertEquals(10_000, relation.size());
		Assert.assertEquals("two owner-choice domains are retained", 200,
			relation.retainedFactorOptionCount());
		Assert.assertEquals(2, relation.choiceGroups().size());
		Assert.assertEquals(0, relation.materializedClauseCount());

		List<CandidateRealizationSupportClause> expanded = new ArrayList<>();
		for(List<CandidateRealizationInputBinding> sharedChoice :
			relation.choiceGroups().get(0).choices())
			for(List<CandidateRealizationInputBinding> independentChoice :
				relation.choiceGroups().get(1).choices()) {
				List<CandidateRealizationInputBinding> bindings = new ArrayList<>(List.of(
					sharedChoice.get(0), sharedChoice.get(1), independentChoice.get(0)));
				expanded.add(new CandidateRealizationSupportClause(List.of(), bindings));
			}
		List<CandidateRealizationSupportClause> explicit =
			new CandidateEmissionRealization(KEY, expanded).supportClauses();
		Assert.assertTrue(relation.equals(explicit));
		Assert.assertEquals(explicit.hashCode(), relation.hashCode());
		List<CandidateRealizationInputBinding> selected = List.of(
			relation.choiceGroups().get(0).choices().get(17).get(0),
			relation.choiceGroups().get(0).choices().get(17).get(1),
			relation.choiceGroups().get(1).choices().get(23).get(0));
		Assert.assertEquals(1_723, relation.ordinalOfBindings(selected));
		Assert.assertEquals(1_723, relation.indexOf(
			new CandidateRealizationSupportClause(List.of(), selected)));
		Assert.assertEquals(0, relation.materializedClauseCount());
	}

	@Test
	public void repeatedOwnerAxesIntersectHolesByExactSupportIdentity() {
		CompiledHopKey shared = key("shared-holes");
		List<CandidateRealizationInputBinding> first = new ArrayList<>();
		List<CandidateRealizationInputBinding> second = new ArrayList<>();
		for(int option = 0; option < 7; option++) {
			CandidateRealizationReference source = reference(shared, option);
			if(option < 5)
				first.add(CandidateRealizationInputBinding.direct(0, source));
			if(option >= 2)
				second.add(CandidateRealizationInputBinding.direct(1, source));
		}
		FactorizedSupportClauses relation = FactorizedSupportClauses.of(
			List.of(), List.of(first, second), null, true);
		Assert.assertEquals(3, relation.size());
		Assert.assertEquals(3, relation.factors().get(0).size());
		Assert.assertEquals(3, relation.factors().get(1).size());
		Assert.assertEquals(3, relation.retainedFactorOptionCount());
		Assert.assertEquals(0, relation.materializedClauseCount());
		CandidateRealizationInputBinding removed = relation.factors().get(1).get(1);
		FactorizedSupportClauses restricted = relation.restrictBindings(binding -> binding != removed)
			.orElseThrow();
		Assert.assertEquals("removing either correlated binding removes the owner choice", 2,
			restricted.size());
		Assert.assertEquals(2, restricted.factors().get(0).size());
		Assert.assertEquals(2, restricted.factors().get(1).size());
		Assert.assertEquals(0, restricted.materializedClauseCount());
	}

	@Test
	public void explicitRepeatedOwnerRowsCompressToTheSameGroupedRelation() {
		CompiledHopKey shared = key("explicit-shared");
		CandidateRealizationReference first = reference(shared, 0);
		CandidateRealizationReference second = reference(shared, 1);
		List<CandidateRealizationSupportClause> rows = List.of(
			new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.direct(0, first),
				CandidateRealizationInputBinding.direct(1, first))),
			new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.direct(0, second),
				CandidateRealizationInputBinding.direct(1, second))));
		CandidateEmissionRealization compressed = CandidateEmissionRealization.tryFactorize(KEY, rows)
			.orElseThrow();
		FactorizedSupportClauses relation = (FactorizedSupportClauses)compressed.supportClauses();
		Assert.assertEquals(2, relation.size());
		Assert.assertEquals(2, relation.retainedFactorOptionCount());
		Assert.assertTrue(relation.equals(new CandidateEmissionRealization(KEY, rows).supportClauses()));
		Assert.assertEquals(0, relation.materializedClauseCount());
	}

	@Test
	public void structurallyEqualForeignProofIdentityDoesNotCompress() {
		CompiledHopKey firstOwner = key("proof-owner");
		CompiledHopKey equalForeignOwner = key("proof-owner");
		PlacementProofKey first = proof(firstOwner);
		PlacementProofKey foreign = proof(equalForeignOwner);
		Assert.assertNotSame(first, foreign);
		Assert.assertEquals(first, foreign);
		List<CandidateRealizationSupportClause> clauses = List.of(
			clause(first, binding(0, "left-a"), binding(1, "right-a")),
			clause(foreign, binding(0, "left-b"), binding(1, "right-a")));
		Assert.assertTrue(CandidateEmissionRealization.tryFactorize(KEY, clauses).isEmpty());
	}

	@Test
	public void equalFactorRelationsWithForeignProofIdentityUseGeneralMerge() {
		PlacementProofKey firstProof = proof(key("foreign-proof"));
		PlacementProofKey foreignProof = proof(key("foreign-proof"));
		Assert.assertEquals(firstProof, foreignProof);
		Assert.assertNotSame(firstProof, foreignProof);
		List<List<CandidateRealizationInputBinding>> factors = List.of(List.of(
			binding(0, "foreign-a"), binding(0, "foreign-b")));
		CandidateEmissionRealization first = CandidateEmissionRealization.factorized(
			KEY, List.of(firstProof), factors, null, true);
		CandidateEmissionRealization foreign = CandidateEmissionRealization.factorized(
			KEY, List.of(foreignProof), factors, null, true);
		CandidateEmissionRealization merged = new CandidateEmissionFact(
			EMISSION, null, null, List.of(first, foreign)).realizations().get(0);
		Assert.assertSame(first, merged);
		Assert.assertEquals("foreign proof identity must bypass the zero-expansion fast path",
			2, ((FactorizedSupportClauses)first.supportClauses()).materializedClauseCount());
		Assert.assertEquals(2, merged.supportClauses().size());
	}

	@Test
	public void threeEqualFactorRelationsWithForeignProofIdentityUseGeneralMerge() {
		PlacementProofKey firstProof = proof(key("three-way-foreign-proof"));
		PlacementProofKey secondProof = proof(key("three-way-foreign-proof"));
		PlacementProofKey thirdProof = proof(key("three-way-foreign-proof"));
		List<List<CandidateRealizationInputBinding>> factors = List.of(List.of(
			binding(0, "three-way-a"), binding(0, "three-way-b")));
		CandidateEmissionRealization first = CandidateEmissionRealization.factorized(
			KEY, List.of(firstProof), factors, null, true);
		CandidateEmissionRealization second = CandidateEmissionRealization.factorized(
			KEY, List.of(secondProof), factors, null, true);
		CandidateEmissionRealization third = CandidateEmissionRealization.factorized(
			KEY, List.of(thirdProof), factors, null, true);

		CandidateEmissionRealization merged = new CandidateEmissionFact(
			EMISSION, null, null, List.of(first, second, third)).realizations().get(0);

		Assert.assertSame(first, merged);
		Assert.assertEquals("foreign proof identities must bypass the K-way equal fast path",
			2, ((FactorizedSupportClauses)first.supportClauses()).materializedClauseCount());
		Assert.assertEquals(2, merged.supportClauses().size());
	}

	@Test
	public void uniformDynamicNativeWitnessIsRetainedExactly() {
		PlacementEmissionState fedEmission = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		DurableAnchorKey witness = new DurableAnchorKey("native-pool", FType.ROW, List.of(
			new AnchorPartition("worker-a", List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition("worker-b", List.of(4L, 0L), List.of(8L, 2L))));
		PlacementProofKey continuity = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, key("native-owner"), "continuity");
		PlacementRealizationKey nativeKey = PlacementRealizationKey.nativeLineage(
			fedEmission, "native-lineage");
		CandidateEmissionRealization realization = CandidateEmissionRealization.factorized(
			nativeKey, List.of(continuity), List.of(
				List.of(binding(0, "left-a"), binding(0, "left-b"))), witness, false);
		FactorizedSupportClauses relation = (FactorizedSupportClauses)realization.supportClauses();
		Assert.assertEquals(0, relation.materializedClauseCount());
		List<CandidateRealizationSupportClause> explicit = List.of(
			new CandidateRealizationSupportClause(List.of(continuity),
				List.of(binding(0, "left-a")), witness, false),
			new CandidateRealizationSupportClause(List.of(continuity),
				List.of(binding(0, "left-b")), witness, false));
		Assert.assertEquals(new CandidateEmissionRealization(nativeKey, explicit)
			.supportClauses().hashCode(), relation.hashCode());
		Assert.assertEquals(0, relation.materializedClauseCount());
		CandidateRealizationSupportClause clause = relation.get(0);
		Assert.assertSame(witness, clause.nativeWorkerPoolWitness());
		Assert.assertFalse(clause.nativeWorkerPoolLayoutExact());
		Assert.assertSame(continuity, clause.proofDependencies().get(0));
		Assert.assertSame(witness, realization.nativeWorkerPoolResidencyWitness(clause));
		Assert.assertNull(realization.provenWorkerPool(clause));
	}

	private static List<CandidateRealizationSupportClause> product(PlacementProofKey proof,
		List<CandidateRealizationInputBinding> left,
		List<CandidateRealizationInputBinding> right) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(CandidateRealizationInputBinding leftBinding : left)
			for(CandidateRealizationInputBinding rightBinding : right)
				clauses.add(clause(proof, leftBinding, rightBinding));
		return clauses;
	}

	private static CandidateRealizationSupportClause clause(PlacementProofKey proof,
		CandidateRealizationInputBinding... bindings) {
		return new CandidateRealizationSupportClause(List.of(proof), List.of(bindings));
	}

	private static CandidateRealizationInputBinding binding(int position, String source) {
		return CandidateRealizationInputBinding.direct(position, reference(key(source)));
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(CandidateInputState.absentLocal()));
		return new CandidateRealizationReference(rule, PlacementRealizationKey.local(EMISSION));
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, int variant) {
		List<CandidateInputState> inputs = new ArrayList<>();
		for(int index = 0; index <= variant; index++)
			inputs.add(CandidateInputState.absentLocal());
		return new CandidateRealizationReference(new CandidateRuleKey(owner, inputs),
			PlacementRealizationKey.local(EMISSION));
	}

	private static PlacementProofKey proof(CompiledHopKey owner) {
		return new PlacementProofKey(PlacementProofKind.VALUE_IDENTITY, owner, "proof");
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"factorized", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("factorized", "main", "call", "compiled", region, id, id);
	}
}
