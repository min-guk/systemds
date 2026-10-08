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
package org.apache.sysds.hops.fedplanner.placement;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class IndexedSupportClausesTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"indexed", "main", List.of("root"), "root", "compiled");
	private static final PlacementState LOCAL_STATE = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementEmissionState LOCAL_EMISSION =
		new PlacementEmissionState(LOCAL_STATE, false);
	private static final PlacementState FOUT_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState FOUT_EMISSION =
		new PlacementEmissionState(FOUT_STATE, false);
	private static final PlacementRealizationKey LOCAL_KEY =
		PlacementRealizationKey.local(LOCAL_EMISSION);

	@Test
	public void roundTripUsesLazyOwnedHandlesAndPreservesListContract() {
		PlacementProofKey proof = proof(key("proof"), "shared");
		CandidateRealizationSupportClause shortRow = clause(List.of(proof),
			direct(0, "short"));
		CandidateRealizationSupportClause longRow = clause(List.of(proof),
			direct(0, "long-left"), direct(3, "long-right"));
		CandidateEmissionRealization explicit = new CandidateEmissionRealization(
			LOCAL_KEY, List.of(shortRow, longRow));
		List<CandidateRealizationSupportClause> canonical = explicit.supportClauses();
		IndexedSupportClauses indexed = IndexedSupportClauses.fromCanonical(canonical);

		Assert.assertEquals(canonical.size(), indexed.size());
		Assert.assertTrue(indexed.equals(canonical));
		Assert.assertEquals(canonical.hashCode(), indexed.hashCode());
		Assert.assertEquals("hash/equality must not create row handles",
			0, indexed.materializedHandleCount());
		Assert.assertEquals(3, indexed.retainedBindingOptionCount());
		Assert.assertEquals(1, indexed.retainedMetadataCount());
		Assert.assertEquals(3, indexed.uniqueBindings().size());

		CandidateRealizationSupportClause handle = indexed.get(1);
		Assert.assertSame(handle, indexed.get(1));
		Assert.assertTrue(handle.isIndexed());
		Assert.assertEquals(indexed.combinationIdAt(1), handle.indexedCombinationId());
		Assert.assertEquals(1, indexed.firstIdentityOrdinal(handle));
		Assert.assertEquals(-1, indexed.firstIdentityOrdinal(canonical.get(1)));
		CandidateRealizationSupportClause forged =
			CandidateRealizationSupportClause.indexed(indexed, 1);
		Assert.assertEquals("an uncached handle cannot acquire relation ownership",
			-1, indexed.firstIdentityOrdinal(forged));
		Assert.assertEquals(canonical.get(1), handle);
		Assert.assertEquals(1, indexed.materializedHandleCount());

		CandidateEmissionRealization compact = explicit.withIndexedSupport();
		Assert.assertTrue(compact.indexedSupport());
		Assert.assertEquals(0, compact.fullyMaterializedSupportClauseCount());
		Assert.assertSame(compact.supportClauses().get(0),
			compact.supportClauseForCombinationId(
				compact.supportClauses().get(0).indexedCombinationId()));
	}

	@Test
	public void dictionariesPreserveForeignProofReferenceAndActionIdentity() {
		CompiledHopKey firstOwner = key("equal-owner");
		CompiledHopKey foreignOwner = key("equal-owner");
		RelocationActionKey firstAction = action("equal-action");
		RelocationActionKey foreignAction = action("equal-action");
		PlacementProofKey firstProof = proof(firstOwner, "equal-proof");
		PlacementProofKey foreignProof = proof(foreignOwner, "equal-proof");
		PlacementProofKey firstMarker = proof(key("first-marker"), "first-marker");
		PlacementProofKey foreignMarker = proof(key("foreign-marker"), "foreign-marker");
		CandidateRealizationInputBinding firstBinding = CandidateRealizationInputBinding.relocation(
			0, reference(firstOwner, PlacementRealizationKey.local(LOCAL_EMISSION), List.of()), firstAction);
		CandidateRealizationInputBinding foreignBinding = CandidateRealizationInputBinding.relocation(
			0, reference(foreignOwner, PlacementRealizationKey.local(LOCAL_EMISSION), List.of()), foreignAction);
		Assert.assertEquals(firstProof, foreignProof);
		Assert.assertEquals(firstBinding, foreignBinding);
		Assert.assertNotSame(firstOwner, foreignOwner);
		Assert.assertNotSame(firstAction, foreignAction);

		List<CandidateRealizationSupportClause> rows = canonicalRows(
			clause(List.of(firstProof, firstMarker), firstBinding),
			clause(List.of(foreignProof, foreignMarker), foreignBinding));
		IndexedSupportClauses indexed = IndexedSupportClauses.fromCanonical(rows);
		Assert.assertEquals(2, indexed.retainedMetadataCount());
		Assert.assertEquals(2, indexed.retainedBindingOptionCount());
		Assert.assertNotEquals(indexed.combinationIdAt(0), indexed.combinationIdAt(1));
		int firstRow = indexed.bindingsAt(0).get(0).source().rule().parentOccurrence() == firstOwner
			? 0 : 1;
		int foreignRow = 1 - firstRow;
		Assert.assertTrue(indexed.proofsAt(firstRow).stream().anyMatch(proof -> proof == firstProof));
		Assert.assertTrue(indexed.proofsAt(foreignRow).stream().anyMatch(proof -> proof == foreignProof));
		Assert.assertSame(firstOwner,
			indexed.bindingsAt(firstRow).get(0).source().rule().parentOccurrence());
		Assert.assertSame(foreignOwner,
			indexed.bindingsAt(foreignRow).get(0).source().rule().parentOccurrence());
		Assert.assertSame(firstAction, indexed.bindingsAt(firstRow).get(0).relocationAction());
		Assert.assertSame(foreignAction, indexed.bindingsAt(foreignRow).get(0).relocationAction());
	}

	@Test
	public void overflowUsesUnboundedCombinationIdsWithoutExpandingCandidateSpace() {
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		for(int position = 0; position < 64; position++)
			bindings.add(direct(position, "axis-" + position));
		CandidateRealizationSupportClause row = new CandidateRealizationSupportClause(
			List.of(), bindings);
		IndexedSupportClauses indexed = IndexedSupportClauses.fromCanonical(List.of(row));
		BigInteger expected = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
		Assert.assertEquals(expected, indexed.combinationIdAt(0));
		Assert.assertEquals(0, indexed.ordinalOfCombinationId(expected));
		Assert.assertEquals(-1, indexed.ordinalOfCombinationId(expected.add(BigInteger.ONE)));
		Assert.assertEquals(-1, indexed.ordinalOfCombinationId(BigInteger.valueOf(-1)));
		Assert.assertEquals(-1, indexed.ordinalOfCombinationId(null));
		Assert.assertEquals(64, indexed.retainedBindingOptionCount());
		Assert.assertEquals(0, indexed.materializedHandleCount());
	}

	@Test
	public void metadataAndTrailingAbsenceArePartOfTheWholeCombinationId() {
		PlacementProofKey firstProof = proof(key("metadata-first"), "first");
		PlacementProofKey secondProof = proof(key("metadata-second"), "second");
		CandidateRealizationInputBinding shared = direct(0, "shared");
		CandidateRealizationInputBinding tail = direct(5, "tail");
		IndexedSupportClauses indexed = IndexedSupportClauses.fromCanonical(canonicalRows(
			clause(List.of(firstProof), shared),
			clause(List.of(firstProof), shared, tail),
			clause(List.of(secondProof), shared)));
		int shortFirst = -1, longFirst = -1, shortSecond = -1;
		for(int row = 0; row < indexed.size(); row++) {
			boolean first = indexed.proofsAt(row).stream().anyMatch(proof -> proof == firstProof);
			if(first && indexed.bindingsAt(row).size() == 1)
				shortFirst = row;
			else if(first)
				longFirst = row;
			else
				shortSecond = row;
		}
		Assert.assertTrue(shortFirst >= 0 && longFirst >= 0 && shortSecond >= 0);
		Assert.assertNotEquals(indexed.combinationIdAt(shortFirst),
			indexed.combinationIdAt(longFirst));
		Assert.assertNotEquals(indexed.combinationIdAt(shortFirst),
			indexed.combinationIdAt(shortSecond));
	}

	@Test
	public void nativeMetadataValidationMatchesExplicitRealizationRules() {
		CompiledHopKey owner = key("native-owner");
		DurableAnchorKey witness = anchor("native-witness");
		PlacementProofKey continuity = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, "continuity");
		IndexedSupportClauses valid = IndexedSupportClauses.fromCanonical(List.of(
			new CandidateRealizationSupportClause(List.of(continuity),
				List.of(direct(0, "source")), witness, true)));
		valid.validateRealizationKey(PlacementRealizationKey.nativeLineage(
			FOUT_EMISSION, "native"));
		Assert.assertFalse(valid.hasMissingNativeWitness());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> valid.validateRealizationKey(LOCAL_KEY));

		IndexedSupportClauses inconsistent = IndexedSupportClauses.fromCanonical(canonicalRows(
			new CandidateRealizationSupportClause(List.of(continuity),
				List.of(direct(0, "first")), witness, true),
			new CandidateRealizationSupportClause(List.of(continuity),
				List.of(direct(0, "second")), null, true)));
		Assert.assertTrue(inconsistent.hasMissingNativeWitness());
		Assert.assertThrows(IllegalArgumentException.class, () -> inconsistent.validateRealizationKey(
			PlacementRealizationKey.nativeLineage(FOUT_EMISSION, "native")));
	}

	@Test
	public void rowAccessRejectsInvalidOrdinalsAndUnknownIds() {
		IndexedSupportClauses indexed = IndexedSupportClauses.fromCanonical(List.of(
			clause(List.of(), direct(0, "only"))));
		Assert.assertThrows(IndexOutOfBoundsException.class, () -> indexed.get(-1));
		Assert.assertThrows(IndexOutOfBoundsException.class, () -> indexed.get(1));
		Assert.assertThrows(IndexOutOfBoundsException.class, () -> indexed.proofsAt(1));
		Assert.assertThrows(IndexOutOfBoundsException.class, () -> indexed.bindingsAt(1));
		Assert.assertThrows(IndexOutOfBoundsException.class, () -> indexed.combinationIdAt(1));
		Assert.assertEquals(-1, indexed.ordinalOfCombinationId(BigInteger.valueOf(99)));
	}

	private static CandidateRealizationSupportClause clause(List<PlacementProofKey> proofs,
		CandidateRealizationInputBinding... bindings) {
		return new CandidateRealizationSupportClause(proofs, List.of(bindings));
	}

	private static List<CandidateRealizationSupportClause> canonicalRows(
		CandidateRealizationSupportClause... rows) {
		return java.util.Arrays.stream(rows).sorted().toList();
	}

	private static CandidateRealizationInputBinding direct(int position, String source) {
		CompiledHopKey owner = key(source);
		return CandidateRealizationInputBinding.direct(position,
			reference(owner, PlacementRealizationKey.local(LOCAL_EMISSION), List.of()));
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner,
		PlacementRealizationKey realization, List<CandidateInputState> inputs) {
		return new CandidateRealizationReference(new CandidateRuleKey(owner, inputs), realization);
	}

	private static PlacementProofKey proof(CompiledHopKey owner, String detail) {
		return new PlacementProofKey(PlacementProofKind.VALUE_IDENTITY, owner, detail);
	}

	private static RelocationActionKey action(String id) {
		return new RelocationActionKey(new ValueVersionKey("indexed", "source", REGION, 0,
			VersionKind.ORDINARY, List.of()), FOUT_STATE, FType.ROW, anchor(id),
			REGION.normalizedSignature(), List.of(key("consumer")));
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker-a", List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition("worker-b", List.of(4L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("indexed", "main", "root", "compiled", REGION, id, id);
	}
}
