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

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.CandidateSupportRelation.ProductRoute;
import org.apache.sysds.hops.fedplanner.placement.CandidateSupportRelation.SupportAnnotations;
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
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class CandidateSupportRelationTest {
	private static final PlacementState FED = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(FED, false);
	private static final CandidateRuleKey OWNER = rule("owner");

	@Test
	public void selectedDecodeInternsButTraversalRemainsEphemeral() {
		List<CandidateRealizationInputBinding> left = bindings(0, "left", 8);
		List<CandidateRealizationInputBinding> right = bindings(1, "right", 8);
		CandidateSupportRelation relation = CandidateSupportRelation.fromProducts(OWNER, new Object(),
			List.of(new ProductRoute(List.of(), List.of(left, right), SupportAnnotations.exact())));

		Assert.assertEquals(64, relation.rawCardinality());
		Assert.assertEquals(16, relation.summary().storedChoiceBindingAtomCount());
		Assert.assertEquals(0, relation.constructionMaterializedCount());
		CandidateSupportRelation.OwnedSupportChoice choice = relation.choiceAt(19);
		CandidateRealizationSupportClause first = relation.decode(choice);
		Assert.assertSame(first, relation.decode(choice));
		Assert.assertTrue(relation.ownsMaterialized(choice, first));
		Assert.assertEquals(List.of(left.get(2), right.get(3)), first.inputBindings());
		Assert.assertEquals(1, relation.constructionMaterializedCount());
		Assert.assertEquals(first, relation.decoder().get(19));
		Assert.assertEquals("random decoder reads must not enter the authority cache",
			1, relation.constructionMaterializedCount());
		Assert.assertEquals(64, relation.exportCanonicalClauses().size());
		Assert.assertEquals("full export must not retain every raw path",
			1, relation.constructionMaterializedCount());
		Assert.assertEquals(64, relation.exactCardinality());
		Assert.assertEquals("cardinality traversal must also remain ephemeral",
			1, relation.constructionMaterializedCount());
	}

	@Test
	public void eightSlotsByEightOptionsRemainSixtyFourAtomsWithoutEnumeration() {
		List<List<CandidateRealizationInputBinding>> slots = new ArrayList<>();
		for(int slot = 0; slot < 8; slot++)
			slots.add(bindings(slot, "slot-" + slot + '-', 8));
		CandidateSupportRelation relation = CandidateSupportRelation.fromProducts(
			new Object(), new Object(), List.of(
				new ProductRoute(List.of(), slots, SupportAnnotations.exact())));
		Assert.assertEquals(16_777_216L, relation.rawCardinality());
		Assert.assertEquals(64, relation.summary().storedChoiceBindingAtomCount());
		Assert.assertEquals(0, relation.constructionMaterializedCount());
	}

	@Test
	public void correlatedPairsNeverBecomeCrossedProducts() {
		CandidateRealizationInputBinding a1 = binding(0, "a1");
		CandidateRealizationInputBinding b1 = binding(1, "b1");
		CandidateRealizationInputBinding a2 = binding(0, "a2");
		CandidateRealizationInputBinding b2 = binding(1, "b2");
		CandidateSupportRelation relation = CandidateSupportRelation.fromProducts(OWNER, new Object(), List.of(
			new ProductRoute(List.of(proof("p1")), List.of(a1, b1), List.of(), SupportAnnotations.exact()),
			new ProductRoute(List.of(proof("p2")), List.of(a2, b2), List.of(), SupportAnnotations.exact())));

		List<CandidateRealizationSupportClause> leaves = relation.exportCanonicalClauses();
		Assert.assertEquals(2, leaves.size());
		Assert.assertTrue(leaves.stream().anyMatch(leaf -> leaf.inputBindings().equals(List.of(a1, b1))));
		Assert.assertTrue(leaves.stream().anyMatch(leaf -> leaf.inputBindings().equals(List.of(a2, b2))));
		Assert.assertFalse(leaves.stream().anyMatch(leaf -> leaf.inputBindings().equals(List.of(a1, b2))));
		Assert.assertFalse(leaves.stream().anyMatch(leaf -> leaf.inputBindings().equals(List.of(a2, b1))));
	}

	@Test
	public void exhaustiveFlatOracleHasCanonicalSetParity() {
		CandidateRealizationInputBinding sameA = binding(0, "same-a");
		CandidateRealizationInputBinding sameB = binding(0, "same-b");
		List<CandidateRealizationSupportClause> universe = List.of(
			clause("a", List.of(binding(0, "left"))),
			clause("b", List.of(binding(1, "right"))),
			clause("c", List.of(sameA, sameB)));
		for(int length = 0; length <= 3; length++) {
			int combinations = (int) Math.pow(universe.size(), length);
			for(int code = 0; code < combinations; code++) {
				List<CandidateRealizationSupportClause> flat = new ArrayList<>();
				int remainder = code;
				for(int index = 0; index < length; index++) {
					flat.add(universe.get(remainder % universe.size()));
					remainder /= universe.size();
				}
				List<CandidateRealizationSupportClause> expected = List.copyOf(new TreeSet<>(flat));
				CandidateSupportRelation relation = CandidateSupportRelation.fromFlat(
					OWNER, new Object(), flat);
				Assert.assertEquals(expected, relation.exportCanonicalClauses());
				Assert.assertEquals(expected.size(), relation.exactCardinality());
			}
		}
	}

	@Test
	public void exportGloballySortsReversedAndInterleavedRoutes() {
		CandidateRealizationSupportClause low = clause("a", List.of(binding(0, "a")));
		CandidateRealizationSupportClause middle = clause("m", List.of(binding(0, "m")));
		CandidateRealizationSupportClause high = clause("z", List.of(binding(0, "z")));
		CandidateSupportRelation relation = CandidateSupportRelation.fromFlat(
			OWNER, new Object(), List.of(high, low, middle, high, low));
		Assert.assertEquals(List.of(low, middle, high), relation.exportCanonicalClauses());
		Assert.assertEquals(3, relation.exactCardinality());
		Assert.assertEquals("export and cardinality must use reclaimable traversal state",
			0, relation.constructionMaterializedCount());
	}

	@Test
	public void crossRouteDuplicatesShareOneCanonicalLeafIdentity() {
		ProductRoute route = new ProductRoute(List.of(proof("same")),
			List.of(List.of(binding(0, "same"), binding(0, "same"))), SupportAnnotations.exact());
		CandidateSupportRelation relation = CandidateSupportRelation.fromProducts(
			OWNER, new Object(), List.of(route, route));
		Assert.assertEquals(2, relation.rawCardinality());
		CandidateRealizationSupportClause first = relation.decode(relation.choiceAt(0));
		CandidateRealizationSupportClause second = relation.decode(relation.choiceAt(1));
		Assert.assertSame(first, second);
		Assert.assertEquals(1, relation.exactCardinality());
		Assert.assertEquals(List.of(first), relation.exportCanonicalClauses());
	}

	@Test
	public void flatAdapterPreservesSamePositionConjunctiveBindings() {
		CandidateRealizationInputBinding first = binding(0, "same-position-a");
		CandidateRealizationInputBinding second = binding(0, "same-position-b");
		CandidateRealizationSupportClause clause = clause("conjunction", List.of(first, second));
		CandidateSupportRelation relation = CandidateSupportRelation.fromFlat(
			OWNER, new Object(), List.of(clause));
		Assert.assertEquals(List.of(clause), relation.exportCanonicalClauses());
		Assert.assertEquals(2, relation.routes().get(0).fixedBindingAtoms().size());
		Assert.assertTrue(relation.routes().get(0).bindingChoicesBySlot().isEmpty());
	}

	@Test
	public void overlappingConjunctiveAtomsFailAtProductConstruction() {
		CandidateRealizationInputBinding shared = binding(0, "shared");
		Assert.assertThrows(IllegalArgumentException.class, () -> new ProductRoute(
			List.of(), List.of(shared), List.of(List.of(shared)), SupportAnnotations.exact()));
		Assert.assertThrows(IllegalArgumentException.class, () -> new ProductRoute(
			List.of(), List.of(), List.of(List.of(shared), List.of(shared)),
			SupportAnnotations.exact()));
	}

	@Test
	public void relationInstanceIsAuthorityAcrossStaleEpochAndEqualRuleRelations() {
		Object epoch = new Object();
		ProductRoute oldRoute = new ProductRoute(List.of(),
			List.of(List.of(binding(0, "old-realization"))), SupportAnnotations.exact());
		ProductRoute newRoute = new ProductRoute(List.of(),
			List.of(List.of(binding(0, "new-realization"))), SupportAnnotations.exact());
		CandidateSupportRelation owned = CandidateSupportRelation.fromProducts(OWNER, epoch, List.of(oldRoute));
		CandidateSupportRelation equalRuleOtherRealization = CandidateSupportRelation.fromProducts(
			new CandidateRuleKey(OWNER.parentOccurrence(), OWNER.orderedInputs()), epoch, List.of(newRoute));
		CandidateSupportRelation staleEpoch = CandidateSupportRelation.fromProducts(
			OWNER, new Object(), List.of(oldRoute));
		Assert.assertFalse(owned.owns(equalRuleOtherRealization.choiceAt(0)));
		Assert.assertFalse(owned.owns(staleEpoch.choiceAt(0)));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> owned.decode(equalRuleOtherRealization.choiceAt(0)));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> owned.decode(staleEpoch.choiceAt(0)));
	}

	@Test
	public void diagnosticOwnerMayPrecedeRuleRealization() {
		Object preRuleOwner = new Object();
		CandidateSupportRelation relation = CandidateSupportRelation.fromProducts(
			preRuleOwner, new Object(), List.of(new ProductRoute(
				List.of(), List.of(), SupportAnnotations.exact())));
		Assert.assertSame(preRuleOwner, relation.owner());
		Assert.assertSame(preRuleOwner, relation.choiceAt(0).owner());
	}

	@Test
	public void emptyRelationAndZeroSlotRouteHaveExactBooleanSemantics() {
		CandidateSupportRelation empty = CandidateSupportRelation.fromProducts(
			OWNER, new Object(), List.of());
		Assert.assertEquals(0, empty.rawCardinality());
		Assert.assertEquals(0, empty.exactCardinality());
		Assert.assertTrue(empty.exportCanonicalClauses().isEmpty());
		CandidateSupportRelation unit = CandidateSupportRelation.fromProducts(OWNER, new Object(),
			List.of(new ProductRoute(List.of(proof("unit")), List.of(), SupportAnnotations.exact())));
		Assert.assertEquals(1, unit.rawCardinality());
		Assert.assertEquals(1, unit.exportCanonicalClauses().size());
	}

	@Test
	public void productCardinalityOverflowFailsClosed() {
		List<List<CandidateRealizationInputBinding>> slots = new ArrayList<>();
		for(int index = 0; index < 63; index++)
			slots.add(List.of(binding(0, "overflow-" + index + "-a"),
				binding(0, "overflow-" + index + "-b")));
		Assert.assertThrows(ArithmeticException.class,
			() -> new ProductRoute(List.of(), slots, SupportAnnotations.exact()));
	}

	private static CandidateRealizationSupportClause clause(String proof,
		List<CandidateRealizationInputBinding> bindings) {
		return new CandidateRealizationSupportClause(List.of(proof(proof)), bindings);
	}

	private static PlacementProofKey proof(String text) {
		return new PlacementProofKey(PlacementProofKind.SHAPE, OWNER.parentOccurrence(), text);
	}

	private static List<CandidateRealizationInputBinding> bindings(int position, String prefix, int count) {
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		for(int index = 0; index < count; index++)
			bindings.add(binding(position, prefix + index));
		return List.copyOf(bindings);
	}

	private static CandidateRealizationInputBinding binding(int position, String source) {
		CandidateRuleKey sourceRule = rule(source);
		CandidateEmissionRealization realization = CandidateEmissionRealization.durable(
			EMISSION, pool(source), List.of(), List.of());
		return CandidateRealizationInputBinding.direct(position,
			CandidateRealizationReference.of(sourceRule, realization));
	}

	private static CandidateRuleKey rule(String name) {
		return new CandidateRuleKey(key(name), List.of(CandidateInputState.present(FType.ROW)));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey("support-relation-test", "main",
			List.of("root"), name, "compiled");
		return new CompiledHopKey("support-relation-test", "main", name,
			"compiled", region, name, name);
	}

	private static DurableAnchorKey pool(String name) {
		return new DurableAnchorKey(name, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(8L, 2L))));
	}
}
