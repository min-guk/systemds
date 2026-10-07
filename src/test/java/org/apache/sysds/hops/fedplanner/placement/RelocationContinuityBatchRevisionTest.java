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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression for the complete multi-owner fact revisions used between relocation SCCs. */
public class RelocationContinuityBatchRevisionTest {
	private static final String FINGERPRINT = "relocation-continuity-batch";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(STATE, false);
	private static final DurableAnchorKey POOL = new DurableAnchorKey("shared-pool", FType.ROW, List.of(
		new AnchorPartition("worker-a:19101", List.of(0L, 0L), List.of(4L, 2L)),
		new AnchorPartition("worker-b:19101", List.of(4L, 0L), List.of(8L, 2L))));

	@Test
	public void completeMultiOwnerAddWithdrawRestoreMatchesFreshResolver() {
		Fixture fixture = new Fixture();
		List<CandidateRuleFact> withoutLeaves = fixture.withoutLeaves();
		NativePlacementContinuity continuity = fixture.continuity(withoutLeaves);
		assertPoolsEqual(fixture, fixture.continuity(withoutLeaves), continuity);
		Assert.assertNull(continuity.fixedValueMapPool(fixture.rootReference));

		List<CandidateRuleFact> added = fixture.withLeaves(false);
		continuity = continuity.nextRevisionWithCompleteCandidateDelta(
			added, identitySet(fixture.leftLeaf.key(), fixture.rightLeaf.key()));
		assertPoolsEqual(fixture, fixture.continuity(added), continuity);
		assertExactPool(continuity.fixedValueMapPool(fixture.rootReference));

		continuity = continuity.nextRevisionWithCompleteCandidateDelta(
			withoutLeaves, identitySet(fixture.rightLeaf.key(), fixture.leftLeaf.key()));
		assertPoolsEqual(fixture, fixture.continuity(withoutLeaves), continuity);
		Assert.assertNull(continuity.fixedValueMapPool(fixture.rootReference));

		List<CandidateRuleFact> restored = fixture.withLeaves(true);
		NativePlacementContinuity restoredContinuity = continuity.nextRevisionWithCompleteCandidateDelta(
			restored, identitySet(fixture.rightLeaf.key(), fixture.leftLeaf.key()));
		assertPoolsEqual(fixture, fixture.continuity(restored), restoredContinuity);
		assertExactPool(restoredContinuity.fixedValueMapPool(fixture.rootReference));

		NativePlacementContinuity alternateOrder = continuity.nextRevisionWithCompleteCandidateDelta(
			fixture.withLeaves(false), identitySet(fixture.leftLeaf.key(), fixture.rightLeaf.key()));
		for(CandidateRealizationReference reference : fixture.references())
			Assert.assertEquals("fact and changed-owner order must not affect the completed SCC snapshot",
				restoredContinuity.fixedValueMapPool(reference), alternateOrder.fixedValueMapPool(reference));
	}

	private static void assertPoolsEqual(Fixture fixture, NativePlacementContinuity expected,
		NativePlacementContinuity actual) {
		for(CandidateRealizationReference reference : fixture.references()) {
			Assert.assertEquals("incremental component publication must equal a fresh exact resolver",
				expected.fixedValueMapPool(reference), actual.fixedValueMapPool(reference));
			CandidateRealizationSupportClause clause = fixture.clause(reference);
			Assert.assertEquals("direct relocation filtering must use the same component snapshot",
				PlacementRelationClosure.directCandidatePool(reference, clause, expected),
				PlacementRelationClosure.directCandidatePool(reference, clause, actual));
			Assert.assertEquals("relocation generation must use the same component snapshot",
				PlacementRelationClosure.relocationCandidatePool(reference, clause, expected),
				PlacementRelationClosure.relocationCandidatePool(reference, clause, actual));
		}
	}

	private static void assertExactPool(NativePlacementContinuity.FixedValueMapPool fixed) {
		Assert.assertNotNull(fixed);
		Assert.assertTrue(fixed.exactLayout());
		Assert.assertTrue(fixed.exactPhysicalLayout());
		Assert.assertTrue(PlacementIdentity.samePhysicalLayout(POOL, fixed.pool()));
	}

	private static Set<CompiledHopKey> identitySet(CompiledHopKey first, CompiledHopKey second) {
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		result.add(first);
		result.add(second);
		return result;
	}

	private static final class Fixture {
		private final Node leftLeaf = node("left-leaf", 0);
		private final Node rightLeaf = node("right-leaf", 1);
		private final Node leftAlias = node("left-alias", 2);
		private final Node rightAlias = node("right-alias", 3);
		private final Node root = node("root", 4);
		private final List<Node> nodes = List.of(leftLeaf, rightLeaf, leftAlias, rightAlias, root);
		private final CandidateRuleKey leftLeafRule = rule(leftLeaf, 0);
		private final CandidateRuleKey rightLeafRule = rule(rightLeaf, 0);
		private final CandidateEmissionRealization leftLeafRealization = durable();
		private final CandidateEmissionRealization rightLeafRealization = durable();
		private final CandidateEmissionRealization leftAliasRealization = valueMap("left-alias", List.of(
			binding(0, leftLeafRule, leftLeafRealization)));
		private final CandidateEmissionRealization rightAliasRealization = valueMap("right-alias", List.of(
			binding(0, rightLeafRule, rightLeafRealization)));
		private final CandidateRuleKey leftAliasRule = rule(leftAlias, 1);
		private final CandidateRuleKey rightAliasRule = rule(rightAlias, 1);
		private final CandidateEmissionRealization rootRealization = valueMap("root", List.of(
			binding(0, leftAliasRule, leftAliasRealization),
			binding(1, rightAliasRule, rightAliasRealization)));
		private final CandidateRuleKey rootRule = rule(root, 2);
		private final CandidateRealizationReference leftAliasReference =
			CandidateRealizationReference.of(leftAliasRule, leftAliasRealization);
		private final CandidateRealizationReference rightAliasReference =
			CandidateRealizationReference.of(rightAliasRule, rightAliasRealization);
		private final CandidateRealizationReference rootReference =
			CandidateRealizationReference.of(rootRule, rootRealization);

		private List<CandidateRealizationReference> references() {
			return List.of(leftAliasReference, rightAliasReference, rootReference);
		}

		private CandidateRealizationSupportClause clause(CandidateRealizationReference reference) {
			if(reference == leftAliasReference)
				return leftAliasRealization.supportClauses().get(0);
			if(reference == rightAliasReference)
				return rightAliasRealization.supportClauses().get(0);
			if(reference == rootReference)
				return rootRealization.supportClauses().get(0);
			throw new IllegalArgumentException("Foreign fixture reference");
		}

		private List<CandidateRuleFact> withoutLeaves() {
			return List.of(fact(leftAliasRule, leftAliasRealization),
				fact(rightAliasRule, rightAliasRealization), fact(rootRule, rootRealization));
		}

		private List<CandidateRuleFact> withLeaves(boolean reverse) {
			List<CandidateRuleFact> facts = new ArrayList<>(List.of(
				fact(leftLeafRule, leftLeafRealization), fact(rightLeafRule, rightLeafRealization),
				fact(leftAliasRule, leftAliasRealization), fact(rightAliasRule, rightAliasRealization),
				fact(rootRule, rootRealization)));
			if(reverse)
				Collections.reverse(facts);
			return List.copyOf(facts);
		}

		private NativePlacementContinuity continuity(List<CandidateRuleFact> facts) {
			Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
			Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
			for(Node node : nodes) {
				nodesByKey.put(node.key(), node);
				origins.put(node.key(), new DataOp(node.key().canonicalSourceOrigin(),
					DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
					node.key().canonicalSourceOrigin(), 8, 2, 16, 1000));
			}
			return new NativePlacementContinuity(nodesByKey, origins, facts, List.of(), Map.of());
		}
	}

	private static CandidateEmissionRealization durable() {
		return CandidateEmissionRealization.durable(EMISSION, POOL, List.of(), List.of());
	}

	private static CandidateEmissionRealization valueMap(String id,
		List<CandidateRealizationInputBinding> bindings) {
		return CandidateEmissionRealization.valueMap(EMISSION, id,
			List.of(new CandidateRealizationSupportClause(List.of(), bindings)));
	}

	private static CandidateRealizationInputBinding binding(int position, CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationInputBinding.logicalTransient(
			position, CandidateRealizationReference.of(rule, realization));
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		CandidateEmissionFact emission = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(realization));
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "batch-revision", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "batch-revision", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(rule.orderedInputs().stream()
				.map(CandidateInputState::fType).toList(), ""), List.of(emission), "");
	}

	private static CandidateRuleKey rule(Node node, int inputs) {
		return new CandidateRuleKey(node.key(),
			Collections.nCopies(inputs, CandidateInputState.present(FType.ROW)));
	}

	private static Node node(String id, int ordinal) {
		CompiledHopKey key = new CompiledHopKey(
			FINGERPRINT, "main", "root", "compiled", REGION, id, id);
		ValueVersionKey version = new ValueVersionKey(
			FINGERPRINT, id, REGION, ordinal, VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, version, true,
			List.of(STATE), List.of(), List.of());
	}
}
