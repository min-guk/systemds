/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientCompatibilityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientPlacementCompatibility;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class PlacementRealizationAuthorityTest {
	private static final PlacementState FED_ROW = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState FED_EMISSION = new PlacementEmissionState(FED_ROW, false);
	private static final CompiledHopKey OWNER = key("owner");
	private static final CandidateRuleKey RULE = new CandidateRuleKey(OWNER,
		List.of(CandidateInputState.present(FType.ROW)));

	@Test
	public void sameFTypeDoesNotCollapseDifferentExactMaps() {
		DurableAnchorKey first = anchor("first", 4);
		DurableAnchorKey shifted = anchor("shifted", 3);
		CandidateEmissionRealization firstRealization = CandidateEmissionRealization.durable(
			FED_EMISSION, first, List.of(anchorProof(first)), List.of());
		CandidateEmissionRealization shiftedRealization = CandidateEmissionRealization.durable(
			FED_EMISSION, shifted, List.of(anchorProof(shifted)), List.of());
		CandidateEmissionFact emission = new CandidateEmissionFact(FED_EMISSION, FType.ROW, null,
			List.of(shiftedRealization, firstRealization));

		Assert.assertEquals(2, emission.realizations().size());
		Assert.assertNotEquals(firstRealization.key(), shiftedRealization.key());
		Assert.assertFalse(PlacementIdentity.samePhysicalLayout(first, shifted));
	}

	@Test
	public void metadataAliasesWithIdenticalGeometryRemainLayoutCompatible() {
		Assert.assertTrue(PlacementIdentity.samePhysicalLayout(anchor("writer", 4), anchor("reader", 4)));
	}

	@Test
	public void receiptRequiresExplicitRealizationWhenEmissionIsAmbiguous() {
		CandidateEmissionRealization first = CandidateEmissionRealization.durable(
			FED_EMISSION, anchor("first", 4), List.of(anchorProof(anchor("first", 4))), List.of());
		CandidateEmissionRealization second = CandidateEmissionRealization.durable(
			FED_EMISSION, anchor("second", 3), List.of(anchorProof(anchor("second", 3))), List.of());
		CandidateEmissionFact emission = new CandidateEmissionFact(FED_EMISSION, FType.ROW, null,
			List.of(first, second));

		Assert.assertThrows(IllegalArgumentException.class,
			() -> new CandidateSelectionReceipt(RULE, emission, List.of()));
		CandidateSelectionReceipt receipt = new CandidateSelectionReceipt(RULE, emission, first, List.of());
		Assert.assertSame(first, receipt.realization());
	}

	@Test
	public void sameLayoutWithDifferentSupportKeepsDistinctOrClauses() {
		DurableAnchorKey map = anchor("map", 4);
		PlacementEmissionState localEmission = new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
		CandidateEmissionRealization local = CandidateEmissionRealization.local(localEmission);
		CandidateRealizationReference inputA = CandidateRealizationReference.of(
			new CandidateRuleKey(key("input-a"), List.of()), local);
		CandidateRealizationReference inputB = CandidateRealizationReference.of(
			new CandidateRuleKey(key("input-b"), List.of()), local);
		CandidateEmissionRealization first = CandidateEmissionRealization.durable(
			FED_EMISSION, map, List.of(anchorProof(map)),
			List.of(CandidateRealizationInputBinding.direct(0, inputA)));
		CandidateEmissionRealization second = CandidateEmissionRealization.durable(
			FED_EMISSION, map, List.of(anchorProof(map)),
			List.of(CandidateRealizationInputBinding.direct(0, inputB)));
		CandidateEmissionFact emission = new CandidateEmissionFact(FED_EMISSION, FType.ROW, null,
			List.of(first, second));

		Assert.assertEquals(first.key(), second.key());
		Assert.assertNotEquals(first.normalizedSignature(), second.normalizedSignature());
		Assert.assertEquals(1, emission.realizations().size());
		Assert.assertEquals(2, emission.realizations().get(0).supportClauses().size());
		Assert.assertEquals(CandidateRealizationReference.of(RULE, first),
			CandidateRealizationReference.of(RULE, second));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> emission.realizations().get(0).requireSingletonSupportClause());
		CandidateEmissionRealization merged = emission.realizations().get(0);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new CandidateSelectionReceipt(RULE, emission, merged, List.of()));
		CandidateSelectionReceipt selected = new CandidateSelectionReceipt(
			RULE, emission, merged, merged.supportClauses().get(0), List.of());
		Assert.assertSame(merged.supportClauses().get(0), selected.supportClause());
	}

	@Test
	public void transientIdentityExactLayoutRequiresEveryDirectReceiptToMatch() {
		DurableAnchorKey reader = anchor("reader", 4);
		CandidateEmissionRealization sameLayout = CandidateEmissionRealization.durable(
			FED_EMISSION, anchor("same-layout", 4), List.of(anchorProof(reader)), List.of());
		CandidateEmissionRealization differentLayout = CandidateEmissionRealization.durable(
			FED_EMISSION, anchor("different-layout", 3), List.of(anchorProof(reader)), List.of());
		CandidateRealizationReference same = CandidateRealizationReference.of(
			new CandidateRuleKey(key("same-source"), List.of()), sameLayout);
		CandidateRealizationReference different = CandidateRealizationReference.of(
			new CandidateRuleKey(key("different-source"), List.of()), differentLayout);

		Assert.assertTrue("metadata aliases with one exact geometry may certify the transient value",
			NeutralPlacementGraphBuilder.exactTransientIdentitySupport(reader,
				new CandidateRealizationSupportClause(List.of(),
					List.of(CandidateRealizationInputBinding.direct(0, same)))));
		Assert.assertFalse("a different DIRECT receipt must keep the transient value layout ambiguous",
			NeutralPlacementGraphBuilder.exactTransientIdentitySupport(reader,
				new CandidateRealizationSupportClause(List.of(),
					List.of(CandidateRealizationInputBinding.direct(0, different)))));
		Assert.assertFalse("an unbound transient realization is not exact runtime-map authority",
			NeutralPlacementGraphBuilder.exactTransientIdentitySupport(reader,
				new CandidateRealizationSupportClause(List.of(), List.of())));
	}

	@Test
	public void incompatibleDurableMapsCannotFormCompatibilityProof() {
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new TransientCompatibilityProof(anchor("left", 4), anchor("right", 3), List.of()));
	}

	@Test
	public void shallowReferencesPermitStableSelfAndTwoNodeSupportCycles() {
		PlacementRealizationKey localKey = PlacementRealizationKey.local(new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false));
		CandidateRuleKey leftRule = new CandidateRuleKey(key("left"),
			List.of(CandidateInputState.absentLocal()));
		CandidateRuleKey rightRule = new CandidateRuleKey(key("right"),
			List.of(CandidateInputState.absentLocal()));
		CandidateRealizationReference left = new CandidateRealizationReference(leftRule, localKey);
		CandidateRealizationReference right = new CandidateRealizationReference(rightRule, localKey);
		CandidateEmissionRealization self = new CandidateEmissionRealization(localKey, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, left)));
		CandidateEmissionRealization leftToRight = new CandidateEmissionRealization(localKey, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, right)));
		CandidateEmissionRealization rightToLeft = new CandidateEmissionRealization(localKey, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, left)));

		Assert.assertEquals(left, CandidateRealizationReference.of(leftRule, self));
		Assert.assertFalse(left.normalizedSignature().contains(self.normalizedSignature()));
		Assert.assertTrue(leftToRight.normalizedSignature().length() < 4096);
		Assert.assertTrue(rightToLeft.normalizedSignature().length() < 4096);
	}

	@Test
	public void supportClauseMergeIsArrivalOrderIndependent() {
		DurableAnchorKey map = anchor("map", 4);
		CandidateEmissionRealization first = CandidateEmissionRealization.durable(
			FED_EMISSION, map, List.of(anchorProof(map)), List.of());
		CandidateEmissionRealization second = CandidateEmissionRealization.durable(
			FED_EMISSION, map, List.of(new PlacementProofKey(PlacementProofKind.SHAPE,
				OWNER, "shape-proof")), List.of());
		CandidateEmissionFact forward = new CandidateEmissionFact(
			FED_EMISSION, FType.ROW, null, List.of(first, second));
		CandidateEmissionFact reverse = new CandidateEmissionFact(
			FED_EMISSION, FType.ROW, null, List.of(second, first));

		Assert.assertEquals(forward.normalizedSignature(), reverse.normalizedSignature());
		Assert.assertEquals(2, forward.realizations().get(0).supportClauses().size());
	}

	@Test
	public void typedNativePoolWitnessRequiresOwnedContinuityProof() {
		DurableAnchorKey pool = anchor("native-pool", 4);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new PlacementAnalysis.CandidateRealizationSupportClause(
				List.of(), List.of(), pool));
		PlacementProofKey continuity = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, OWNER, "native-continuity");
		CandidateEmissionRealization realization = CandidateEmissionRealization.nativeLineage(
			FED_EMISSION, "native", pool, List.of(continuity), List.of());
		PlacementRealizationKey nativeKey = realization.key();
		Assert.assertThrows(IllegalArgumentException.class, () -> new CandidateEmissionRealization(
			nativeKey, List.of(realization.supportClauses().get(0),
				new PlacementAnalysis.CandidateRealizationSupportClause(
					List.of(continuity), List.of(), anchor("other-pool", 3)))));
		Assert.assertThrows(IllegalArgumentException.class, () -> new CandidateEmissionRealization(
			nativeKey, List.of(realization.supportClauses().get(0),
				new PlacementAnalysis.CandidateRealizationSupportClause(
					List.of(), List.of()))));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			FED_EMISSION, FType.ROW, null, List.of(realization));
		CandidateSelectionReceipt receipt = new CandidateSelectionReceipt(
			RULE, emission, realization, realization.supportClauses().get(0), List.of());

		Assert.assertSame(pool, receipt.provenWorkerPool());
	}

	@Test
	public void joinRequiresEveryReachingDefinitionAndOneCommonExactReaderLayout() {
		CompiledHopKey firstWriter = key("first-writer");
		CompiledHopKey secondWriter = key("second-writer");
		CompiledHopKey reader = key("reader");
		DurableAnchorKey firstMap = anchor("first-map", 4);
		DurableAnchorKey secondMap = anchor("second-map", 3);
		LogicalTransientInputFact firstToFirst = transientFact(
			firstWriter, reader, firstMap, firstMap, 1);
		LogicalTransientInputFact secondToFirst = transientFact(
			secondWriter, reader, firstMap, firstMap, 2);

		PlacementAnalysis.validateReachingDefinitionSupportForSlot(
			List.of(firstWriter, secondWriter), List.of(firstToFirst, secondToFirst));
		Assert.assertThrows("missing one reaching writer must not authorize the join",
			IllegalArgumentException.class, () ->
				PlacementAnalysis.validateReachingDefinitionSupportForSlot(
					List.of(firstWriter, secondWriter), List.of(firstToFirst)));

		LogicalTransientInputFact secondToSecond = transientFact(
			secondWriter, reader, secondMap, secondMap, 2);
		Assert.assertThrows("same FType with incompatible exact maps has no common reader alternative",
			IllegalArgumentException.class, () ->
				PlacementAnalysis.validateReachingDefinitionSupportForSlot(
					List.of(firstWriter, secondWriter), List.of(firstToFirst, secondToSecond)));
	}

	private static LogicalTransientInputFact transientFact(CompiledHopKey writer,
		CompiledHopKey reader, DurableAnchorKey sourceMap, DurableAnchorKey readerMap, int ordinal) {
		CandidateRuleKey sourceRule = new CandidateRuleKey(writer, List.of());
		CandidateRuleKey readerRule = new CandidateRuleKey(reader,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization source = CandidateEmissionRealization.durable(
			FED_EMISSION, sourceMap, List.of(anchorProof(sourceMap)), List.of());
		CandidateEmissionRealization target = CandidateEmissionRealization.durable(
			FED_EMISSION, readerMap, List.of(anchorProof(readerMap)), List.of());
		ValueVersionKey sourceVersion = version(writer, ordinal);
		ValueVersionKey readerVersion = version(reader, 10);
		PlacementProofKey valueProof = PlacementAnalysis.transientValueIdentityProof(
			writer, sourceVersion, readerVersion);
		TransientPlacementCompatibility edge = new TransientPlacementCompatibility(
			CandidateRealizationReference.of(sourceRule, source),
			CandidateRealizationReference.of(readerRule, target),
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW),
			new TransientCompatibilityProof(sourceMap, readerMap, List.of(valueProof)));
		return new LogicalTransientInputFact(writer, reader, 0, sourceVersion, readerVersion,
			List.of(edge));
	}

	private static ValueVersionKey version(CompiledHopKey owner, int ordinal) {
		return new ValueVersionKey(owner.programFingerprint(), "value",
			owner.controlRegion(), ordinal, VersionKind.ORDINARY, List.of());
	}

	private static PlacementProofKey anchorProof(DurableAnchorKey anchor) {
		return new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, OWNER,
			anchor.normalizedSignature());
	}

	private static DurableAnchorKey anchor(String id, long split) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(split, 2L)),
			new AnchorPartition("localhost:1235", List.of(split, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = new ControlRegionKey("program", "main", List.of("root"), "call", "rc");
		return new CompiledHopKey("program", "main", "call", "rc", region, id, id);
	}
}
