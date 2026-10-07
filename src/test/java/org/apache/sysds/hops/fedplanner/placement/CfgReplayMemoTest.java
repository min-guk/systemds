/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Exact dependency and cold-oracle contracts for per-reader CFG replay memoization. */
public class CfgReplayMemoTest {
	private static final PlacementState FED = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState FED_EMISSION = new PlacementEmissionState(FED, false);
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementEmissionState LOCAL_EMISSION = new PlacementEmissionState(LOCAL, false);

	@Test
	public void inventoryMatchesColdSourceOraclesAndKeepsStructuralMembership() throws Exception {
		CompiledHopKey source = owner("source");
		CompiledHopKey equalForeignSource = owner("source");
		CandidateRuleKey fedRule = rule(source, CandidateInputState.present(FType.FULL));
		CandidateRuleKey localRule = rule(source, CandidateInputState.absentLocal());
		CandidateEmissionRealization durable = durable("source-durable", "durable-proof");
		CandidateEmissionRealization nonExecutable = CandidateEmissionRealization.nativeLineage(
			FED_EMISSION, "source-native", List.of(proof(source, "native-without-pool")), List.of());
		CandidateEmissionRealization local = CandidateEmissionRealization.local(LOCAL_EMISSION);
		CandidateRuleFact fed = fact(fedRule,
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null, List.of(durable, nonExecutable)));
		CandidateRuleFact localFact = fact(localRule,
			new CandidateEmissionFact(LOCAL_EMISSION, null, null, List.of(local)));
		CandidateRuleFact excluded = excluded(owner("excluded"));
		List<CandidateRuleFact> facts = List.of(fed, localFact, excluded);
		Object inventory = inventory(facts);

		Assert.assertEquals(facts.subList(0, 2), ownerFacts(inventory, source));
		Assert.assertThrows("owner slices must be immutable", UnsupportedOperationException.class,
			() -> ownerFacts(inventory, source).add(fed));
		Assert.assertTrue("equal structural keys are not graph-owned identity",
			ownerFacts(inventory, equalForeignSource).isEmpty());
		Assert.assertNotSame(source, equalForeignSource);
		Assert.assertEquals(source, equalForeignSource);

		Assert.assertEquals(coldSourceRealizations(source, FED, facts),
			sourceRealizations(inventory, source, FED));
		Assert.assertEquals(coldSourceRealizations(source, LOCAL, facts),
			sourceRealizations(inventory, source, LOCAL));
		Assert.assertEquals(coldFederatedRealizations(source, facts),
			sourceFederatedRealizations(inventory, source));
		Assert.assertEquals(List.of(reference(fedRule, durable)), sourceRealizations(inventory, source, FED));
		Assert.assertTrue("exact membership retains a non-executable realization for dependency checks",
			realization(inventory, reference(fedRule, nonExecutable)).isPresent());
		Assert.assertFalse("source projection still applies executable-source filtering",
			sourceFederatedRealizations(inventory, source).contains(reference(fedRule, nonExecutable)));
		Assert.assertTrue(sourceRealizations(inventory, equalForeignSource, FED).isEmpty());
	}

	@Test
	public void structuralReferenceCollisionKeepsFirstAndRemovalExposesNext() throws Exception {
		CompiledHopKey source = owner("collision-source");
		CandidateRuleKey firstRule = rule(source, CandidateInputState.present(FType.FULL));
		CandidateRuleKey equalRule = rule(owner("collision-source"), CandidateInputState.present(FType.FULL));
		CandidateEmissionRealization template = durable("collision-layout", "first-support");
		CandidateEmissionRealization first = withProof(template, source, "first-support");
		CandidateEmissionRealization second = withProof(template, source, "second-support");
		CandidateRuleFact firstFact = fact(firstRule,
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null, List.of(first)));
		CandidateRuleFact secondFact = fact(equalRule,
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null, List.of(second)));
		CandidateRealizationReference reference = reference(firstRule, first);

		Assert.assertEquals(reference, reference(equalRule, second));
		Assert.assertSame("cold findFirst semantics retain the first structural reference",
			first, realization(inventory(List.of(firstFact, secondFact)), reference).orElseThrow());
		Assert.assertSame("removing the first authority exposes the next structural match",
			second, realization(inventory(List.of(secondFact)), reference).orElseThrow());
	}

	@Test
	public void dependencyEvidenceInvalidatesChangedSupportWithAnIdenticalReferenceKey() throws Exception {
		CompiledHopKey source = owner("changed-support");
		CandidateRuleKey rule = rule(source, CandidateInputState.present(FType.FULL));
		CandidateEmissionRealization template = durable("stable-layout", "template");
		CandidateRuleFact first = fact(rule, new CandidateEmissionFact(FED_EMISSION, FType.FULL, null,
			List.of(withProof(template, source, "support-a"))));
		CandidateRuleFact changed = fact(rule, new CandidateEmissionFact(FED_EMISSION, FType.FULL, null,
			List.of(withProof(template, source, "support-b"))));
		Object originalInventory = inventory(List.of(first));
		Object dependencies = dependencies(originalInventory);

		Assert.assertEquals(List.of(reference(first.key(), first.allowedEmissionFacts().get(0).realizations().get(0))),
			dependencySourceRealizations(dependencies, source, FED));
		Object evidence = field(dependencies, "evidence");
		Assert.assertTrue(matches(evidence, originalInventory));
		Assert.assertFalse("same realization key with changed support must invalidate replay",
			matches(evidence, inventory(List.of(changed))));
	}

	@Test
	public void negativeRealizationLookupInvalidatesWhenTheReferenceAppears() throws Exception {
		CandidateRuleFact retained = fact(rule(owner("retained"), CandidateInputState.present(FType.FULL)),
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null,
				List.of(durable("retained-layout", "retained-proof"))));
		CandidateRuleKey lateRule = rule(owner("late"), CandidateInputState.present(FType.FULL));
		CandidateEmissionRealization late = durable("late-layout", "late-proof");
		CandidateRuleFact appeared = fact(lateRule,
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null, List.of(late)));
		Object originalInventory = inventory(List.of(retained));
		Object dependencies = dependencies(originalInventory);

		Assert.assertEquals(Optional.empty(), dependencyRealization(dependencies, reference(lateRule, late)));
		Object evidence = field(dependencies, "evidence");
		Assert.assertTrue(matches(evidence, originalInventory));
		Assert.assertFalse("an observed absence is part of exact replay evidence",
			matches(evidence, inventory(List.of(retained, appeared))));
	}

	@Test
	public void structurallyEqualSupportCannotReplaceGraphOwnedDependencyIdentities() throws Exception {
		CompiledHopKey owner = owner("support-owner");
		CandidateRuleKey rule = rule(owner, CandidateInputState.present(FType.FULL));
		CandidateEmissionRealization output = durable("support-output", "output-template");
		CompiledHopKey inputOwner = owner("nested-input");
		CompiledHopKey equalForeignInputOwner = owner("nested-input");
		CandidateEmissionRealization input = durable("nested-layout", "nested-template");
		CandidateRealizationReference inputReference = reference(
			rule(inputOwner, CandidateInputState.present(FType.FULL)), input);
		CandidateRealizationReference foreignInputReference = reference(
			rule(equalForeignInputOwner, CandidateInputState.present(FType.FULL)), input);
		CompiledHopKey proofOwner = owner("nested-proof");
		CompiledHopKey equalForeignProofOwner = owner("nested-proof");
		PlacementProofKey ownedProof = proof(proofOwner, "nested-proof");
		CandidateEmissionRealization original = new CandidateEmissionRealization(output.key(),
			List.of(ownedProof), List.of(CandidateRealizationInputBinding.direct(0, inputReference)));
		CandidateEmissionRealization changedBinding = new CandidateEmissionRealization(output.key(),
			List.of(ownedProof), List.of(CandidateRealizationInputBinding.direct(0, foreignInputReference)));
		CandidateEmissionRealization changedProof = new CandidateEmissionRealization(output.key(),
			List.of(proof(equalForeignProofOwner, "nested-proof")),
			List.of(CandidateRealizationInputBinding.direct(0, inputReference)));
		CandidateRuleFact originalFact = fact(rule,
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null, List.of(original)));
		CandidateRuleFact changedBindingFact = fact(rule,
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null, List.of(changedBinding)));
		CandidateRuleFact changedProofFact = fact(rule,
			new CandidateEmissionFact(FED_EMISSION, FType.FULL, null, List.of(changedProof)));
		Object originalInventory = inventory(List.of(originalFact));
		Object dependencies = dependencies(originalInventory);

		Assert.assertNotSame(inputOwner, equalForeignInputOwner);
		Assert.assertNotSame(proofOwner, equalForeignProofOwner);
		Assert.assertEquals("fixture differs only by graph-owned input identity", original, changedBinding);
		Assert.assertEquals("fixture differs only by graph-owned proof identity", original, changedProof);
		Assert.assertEquals(sourceRealizations(originalInventory, owner, FED),
			dependencySourceRealizations(dependencies, owner, FED));
		Object evidence = field(dependencies, "evidence");
		Assert.assertTrue(matches(evidence, originalInventory));
		Assert.assertFalse("equal structural binding cannot replace the observed source owner",
			matches(evidence, inventory(List.of(changedBindingFact))));
		Assert.assertFalse("equal structural proof cannot replace the observed proof owner",
			matches(evidence, inventory(List.of(changedProofFact))));
	}

	@Test
	public void nativeQueriesKeepStructurallyEqualRootIdentitiesDistinct() throws Exception {
		CompiledHopKey firstOwner = owner("native-root");
		CompiledHopKey secondOwner = owner("native-root");
		CandidateEmissionRealization realization = durable("native-root-layout", "native-root-proof");
		CandidateRealizationReference first = reference(
			rule(firstOwner, CandidateInputState.present(FType.FULL)), realization);
		CandidateRealizationReference second = reference(
			rule(secondOwner, CandidateInputState.present(FType.FULL)), realization);
		DurableAnchorKey seed = anchor("native-seed");
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers =
			new IdentityHashMap<>();
		answers.put(firstOwner, List.of(nativeProof(seed, anchor("first-output"), List.of())));
		answers.put(secondOwner, List.of(nativeProof(seed, anchor("second-output"), List.of())));
		NativePlacementContinuity nativePools = nativePools(answers);
		Object inventory = inventory(List.of());
		Object dependencies = dependencies(inventory, nativePools);

		Assert.assertEquals(first, second);
		Assert.assertNotSame(firstOwner, secondOwner);
		dependencyNativeProofs(dependencies, firstOwner, first, seed);
		dependencyNativeProofs(dependencies, secondOwner, second, seed);
		Object evidence = field(dependencies, "evidence");
		Assert.assertEquals("equal structural references still name two graph-owned native roots", 2,
			((Map<?,?>)field(evidence, "nativeProofs")).size());
		Assert.assertTrue(matches(evidence, inventory, nativePools));

		answers.put(firstOwner, List.of());
		Assert.assertFalse("changing only the first identity-scoped query must invalidate",
			matches(evidence, inventory, nativePools));
	}

	@Test
	public void nativeProofPresenceChangesInvalidateWithUnchangedSourceFacts() throws Exception {
		CompiledHopKey source = owner("native-presence");
		CandidateEmissionRealization realization = durable("native-presence-layout", "native-presence-proof");
		CandidateRealizationReference reference = reference(
			rule(source, CandidateInputState.present(FType.FULL)), realization);
		DurableAnchorKey seed = anchor("presence-seed");
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers =
			new IdentityHashMap<>();
		answers.put(source, List.of());
		NativePlacementContinuity nativePools = nativePools(answers);
		Object inventory = inventory(List.of());
		Object emptyDependencies = dependencies(inventory, nativePools);

		dependencyNativeProofs(emptyDependencies, source, reference, seed);
		Object emptyEvidence = field(emptyDependencies, "evidence");
		Assert.assertTrue(matches(emptyEvidence, inventory, nativePools));
		answers.put(source, List.of(nativeProof(seed, anchor("presence-output"), List.of())));
		Assert.assertFalse("an observed empty native query becoming feasible invalidates replay",
			matches(emptyEvidence, inventory, nativePools));

		Object presentDependencies = dependencies(inventory, nativePools);
		dependencyNativeProofs(presentDependencies, source, reference, seed);
		Object presentEvidence = field(presentDependencies, "evidence");
		Assert.assertTrue(matches(presentEvidence, inventory, nativePools));
		answers.put(source, List.of());
		Assert.assertFalse("withdrawal upstream invalidates replay even when source facts are unchanged",
			matches(presentEvidence, inventory, nativePools));
	}

	@Test
	public void nativeEvidenceRetainsOnlyOutputFieldsConsumedByReplay() throws Exception {
		CompiledHopKey source = owner("native-output-projection");
		CandidateEmissionRealization realization = durable("native-projection-layout", "native-projection-proof");
		CandidateRealizationReference reference = reference(
			rule(source, CandidateInputState.present(FType.FULL)), realization);
		DurableAnchorKey seed = anchor("projection-seed");
		DurableAnchorKey output = anchor("projection-output");
		CandidateRealizationReference firstInput = reference(
			rule(owner("first-immediate"), CandidateInputState.present(FType.FULL)),
			durable("first-immediate-layout", "first-immediate-proof"));
		CandidateRealizationReference secondInput = reference(
			rule(owner("second-immediate"), CandidateInputState.present(FType.FULL)),
			durable("second-immediate-layout", "second-immediate-proof"));
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers =
			new IdentityHashMap<>();
		answers.put(source, List.of(nativeProof(seed, output,
			List.of(CandidateRealizationInputBinding.direct(0, firstInput)))));
		NativePlacementContinuity nativePools = nativePools(answers);
		Object inventory = inventory(List.of());
		Object dependencies = dependencies(inventory, nativePools);

		dependencyNativeProofs(dependencies, source, reference, seed);
		Object evidence = field(dependencies, "evidence");
		answers.put(source, List.of(nativeProof(seed, output,
			List.of(CandidateRealizationInputBinding.direct(0, secondInput)))));
		Assert.assertTrue("immediate proof graph is not consumed by transient replay output construction",
			matches(evidence, inventory, nativePools));
	}

	@Test
	public void unchangedReplayEvidenceValidatesReceiptWithoutRepeatingNativeProofSearch()
		throws Exception {
		CompiledHopKey source = owner("native-receipt-hit");
		CandidateEmissionRealization realization = durable("native-receipt-layout", "native-receipt-proof");
		CandidateRealizationReference reference = reference(
			rule(source, CandidateInputState.present(FType.FULL)), realization);
		DurableAnchorKey seed = anchor("native-receipt-seed");
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers =
			new IdentityHashMap<>();
		answers.put(source, List.of(nativeProof(seed, anchor("native-receipt-output"), List.of())));
		NativePlacementContinuity nativePools = nativePools(answers);
		Object inventory = inventory(List.of());
		Object dependencies = dependencies(inventory, nativePools);

		dependencyNativeProofs(dependencies, source, reference, seed);
		Object evidence = field(dependencies, "evidence");
		Assert.assertTrue(matches(evidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(1)).proveCandidateReplay(reference, seed);
		Mockito.verify(nativePools, Mockito.times(1)).matchesReplayProofReceipt(
			Mockito.any(), Mockito.eq(reference), Mockito.eq(seed));
		Mockito.verify(nativePools, Mockito.never()).proveCandidateAlternatives(
			Mockito.any(CandidateRealizationReference.class), Mockito.any(DurableAnchorKey.class));
	}

	@Test
	public void invalidReceiptReprovesCurrentOutputsRenewsAndThenUsesTheFastPath()
		throws Exception {
		CompiledHopKey source = owner("native-receipt-renew");
		CandidateEmissionRealization realization = durable("native-renew-layout", "native-renew-proof");
		CandidateRealizationReference reference = reference(
			rule(source, CandidateInputState.present(FType.FULL)), realization);
		DurableAnchorKey seed = anchor("native-renew-seed");
		DurableAnchorKey output = anchor("native-renew-output");
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers =
			new IdentityHashMap<>();
		answers.put(source, List.of(nativeProof(seed, output, List.of())));
		AtomicInteger forcedReceiptMisses = new AtomicInteger();
		NativePlacementContinuity nativePools = nativePools(answers, forcedReceiptMisses, false);
		Object inventory = inventory(List.of());
		Object dependencies = dependencies(inventory, nativePools);

		List<?> coldOutputs = dependencyNativeProofs(dependencies, source, reference, seed);
		Object evidence = field(dependencies, "evidence");
		Object originalReceipt = nativeEvidenceReceipt(evidence);
		forcedReceiptMisses.set(1);
		Assert.assertTrue("an invalid token revalidates the cached CFG against current exact outputs",
			matches(evidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(2)).proveCandidateReplay(reference, seed);
		Assert.assertNotSame("equal current outputs renew the receipt", originalReceipt,
			nativeEvidenceReceipt(evidence));
		Assert.assertTrue("the renewed receipt validates the next pass", matches(evidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(2)).proveCandidateReplay(reference, seed);

		Object coldDependencies = dependencies(inventory, nativePools(answers));
		Assert.assertEquals("revalidation preserves the cold transient proof output", coldOutputs,
			dependencyNativeProofs(coldDependencies, source, reference, seed));
		answers.put(source, List.of(nativeProof(seed, output, false, List.of())));
		Assert.assertFalse("changed exact-layout output rejects the cached CFG replay",
			matches(evidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(3)).proveCandidateReplay(reference, seed);
		answers.put(source, List.of(nativeProof(seed, anchor("native-renew-changed"), List.of())));
		Assert.assertFalse("changed current output rejects the cached CFG replay",
			matches(evidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(4)).proveCandidateReplay(reference, seed);
		answers.put(source, List.of());
		Assert.assertFalse("withdrawn current output also rejects the cached CFG replay",
			matches(evidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(5)).proveCandidateReplay(reference, seed);
	}

	@Test
	public void absentReceiptReprovesEqualNonemptyAndEmptyOutputsEveryTime() throws Exception {
		CompiledHopKey source = owner("native-null-receipt");
		CandidateEmissionRealization realization = durable("native-null-layout", "native-null-proof");
		CandidateRealizationReference reference = reference(
			rule(source, CandidateInputState.present(FType.FULL)), realization);
		DurableAnchorKey seed = anchor("native-null-seed");
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers =
			new IdentityHashMap<>();
		answers.put(source, List.of(nativeProof(seed, anchor("native-null-output"), List.of())));
		NativePlacementContinuity nativePools = nativePools(answers, new AtomicInteger(), true);
		Object inventory = inventory(List.of());
		Object dependencies = dependencies(inventory, nativePools);

		dependencyNativeProofs(dependencies, source, reference, seed);
		Object evidence = field(dependencies, "evidence");
		Assert.assertNull(nativeEvidenceReceipt(evidence));
		Assert.assertTrue(matches(evidence, inventory, nativePools));
		Assert.assertTrue(matches(evidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(3)).proveCandidateReplay(reference, seed);

		answers.put(source, List.of());
		Object emptyDependencies = dependencies(inventory, nativePools);
		dependencyNativeProofs(emptyDependencies, source, reference, seed);
		Object emptyEvidence = field(emptyDependencies, "evidence");
		Assert.assertTrue("an empty result is accepted only after a current proof",
			matches(emptyEvidence, inventory, nativePools));
		Assert.assertTrue(matches(emptyEvidence, inventory, nativePools));
		Mockito.verify(nativePools, Mockito.times(6)).proveCandidateReplay(reference, seed);
	}

	@Test
	public void replayContextInvalidatesEveryCapturedReaderAndDefinitionAuthority() throws Exception {
		DataOp sourceHop = write("source");
		DataOp readerHop = read("reader");
		DataOp backedgeHop = write("backedge");
		StatementBlock block = new StatementBlock();
		List<PlacementGraphFingerprint.HopOccurrence> occurrences = List.of(
			occurrence(sourceHop, block, "source"), occurrence(readerHop, block, "reader"),
			occurrence(backedgeHop, block, "backedge"));
		Node source = node(owner("context-source"), NodeKind.TRANSIENT_WRITE, "source", 0, List.of());
		Node reader = node(owner("context-reader"), NodeKind.TRANSIENT_READ, "reader", 1, List.of());
		Node backedge = node(owner("context-backedge"), NodeKind.TRANSIENT_WRITE, "source", 2, List.of());
		List<Node> nodes = List.of(source, reader, backedge);
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(sourceHop, new NodeShapeFact(DataType.MATRIX, 8, 3));
		shapes.put(readerHop, new NodeShapeFact(DataType.MATRIX, 8, 3));
		shapes.put(backedgeHop, new NodeShapeFact(DataType.MATRIX, 8, 3));
		Object context = captureContext(occurrences.get(1), reader, List.of(0), occurrences,
			nodes, shapes, true, false);

		Assert.assertNotNull(context);
		Assert.assertTrue(contextMatches(context, captureContext(occurrences.get(1), reader, List.of(0),
			occurrences, nodes, shapes, true, false)));
		Assert.assertFalse("entry plus backedge is a different exact reaching-definition context",
			contextMatches(context, captureContext(occurrences.get(1), reader, List.of(0, 2),
				occurrences, nodes, shapes, true, false)));
		Assert.assertFalse("geometry and VALUE_MAP flags are part of replay semantics",
			contextMatches(context, captureContext(occurrences.get(1), reader, List.of(0),
				occurrences, nodes, shapes, false, true)));

		Node outputOnlyChanged = new Node(reader.key(), reader.kind(), reader.valueVersion(), true,
			List.of(LOCAL), reader.exclusions(), List.of(anchor("reader-output-only")));
		Assert.assertTrue("reader legal states and anchors are replay outputs, not cache inputs",
			contextMatches(context, captureContext(occurrences.get(1), outputOnlyChanged, List.of(0),
				occurrences, List.of(source, outputOnlyChanged, backedge), shapes, true, false)));

		Node privacyChanged = node(reader.key(), NodeKind.TRANSIENT_READ, "reader", 1,
			List.of(new Exclusion(LOCAL, NeutralPlacementGraph.ReasonCode.PRIVACY, "changed")));
		Assert.assertFalse("reader privacy exclusions invalidate cached output",
			contextMatches(context, captureContext(occurrences.get(1), privacyChanged, List.of(0),
				occurrences, List.of(source, privacyChanged, backedge), shapes, true, false)));

		Node foreignSource = node(owner("context-source"), NodeKind.TRANSIENT_WRITE, "source", 0, List.of());
		Assert.assertEquals(source, foreignSource);
		Assert.assertNotSame(source.key(), foreignSource.key());
		Assert.assertFalse("equal structural source keys cannot replace graph-owned identity",
			contextMatches(context, captureContext(occurrences.get(1), reader, List.of(0), occurrences,
				List.of(foreignSource, reader, backedge), shapes, true, false)));

		DataOp nonWrite = read("source");
		Map<Hop,NodeShapeFact> wrongShapes = new IdentityHashMap<>(shapes);
		wrongShapes.put(nonWrite, new NodeShapeFact(DataType.MATRIX, 8, 3));
		List<PlacementGraphFingerprint.HopOccurrence> wrongOccurrences = List.of(
			occurrence(nonWrite, block, "source"), occurrences.get(1), occurrences.get(2));
		Assert.assertNull("a source opcode change makes the captured context ineligible",
			captureContext(occurrences.get(1), reader, List.of(0), wrongOccurrences,
				nodes, wrongShapes, true, false));
	}

	@Test
	public void replayContextOrdinalIndexPreservesFirstEqualAndAbsentColdSemantics() throws Exception {
		DataOp sourceHop = write("ordinal-source");
		DataOp readerHop = read("ordinal-reader");
		StatementBlock block = new StatementBlock();
		List<PlacementGraphFingerprint.HopOccurrence> occurrences = List.of(
			occurrence(sourceHop, block, "source"), occurrence(readerHop, block, "reader-first"),
			occurrence(readerHop, block, "reader-foreign"));
		Node source = node(owner("ordinal-source"), NodeKind.TRANSIENT_WRITE, "source", 0, List.of());
		Node firstReader = node(owner("ordinal-reader"), NodeKind.TRANSIENT_READ, "reader", 1, List.of());
		Node equalForeignReader = node(
			owner("ordinal-reader"), NodeKind.TRANSIENT_READ, "reader", 1, List.of());
		List<Node> nodes = List.of(source, firstReader, equalForeignReader);
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(sourceHop, new NodeShapeFact(DataType.MATRIX, 8, 3));
		shapes.put(readerHop, new NodeShapeFact(DataType.MATRIX, 8, 3));
		Map<Node,Integer> ordinals = firstEqualNodeOrdinals(nodes);

		Assert.assertEquals(firstReader, equalForeignReader);
		Assert.assertNotSame(firstReader.key(), equalForeignReader.key());
		Assert.assertEquals("a structural duplicate retains List.indexOf first-match semantics",
			Integer.valueOf(1), ordinals.get(equalForeignReader));
		Object cold = captureContext(occurrences.get(2), equalForeignReader, List.of(0),
			occurrences, nodes, shapes, true, false);
		Object indexed = captureContext(occurrences.get(2), equalForeignReader,
			ordinals.get(equalForeignReader), List.of(0), occurrences, nodes, shapes, true, false);
		Assert.assertEquals("precomputed ordinal capture must equal the cold List.indexOf oracle", cold, indexed);
		Assert.assertEquals("the context ordinal is the first structural match, not occurrence ordinal",
			1, field(indexed, "readOrdinal"));

		Node absentReader = node(owner("absent-reader"), NodeKind.TRANSIENT_READ, "reader", 3, List.of());
		Assert.assertFalse(ordinals.containsKey(absentReader));
		Object absentCold = captureContext(occurrences.get(1), absentReader, List.of(0),
			occurrences, nodes, shapes, true, false);
		Object absentIndexed = captureContext(occurrences.get(1), absentReader,
			ordinals.getOrDefault(absentReader, -1), List.of(0), occurrences, nodes, shapes, true, false);
		Assert.assertEquals("absent readers preserve the cold -1 ordinal", absentCold, absentIndexed);
		Assert.assertEquals(-1, field(absentIndexed, "readOrdinal"));
	}

	private static CandidateEmissionRealization withProof(CandidateEmissionRealization template,
		CompiledHopKey owner, String authority) {
		return new CandidateEmissionRealization(template.key(), List.of(proof(owner, authority)), List.of());
	}

	private static PlacementProofKey proof(CompiledHopKey owner, String authority) {
		return new PlacementProofKey(PlacementProofKind.SHAPE, owner, authority);
	}

	private static CandidateEmissionRealization durable(String id, String authority) {
		CompiledHopKey proofOwner = owner("proof-" + id);
		return CandidateEmissionRealization.durable(FED_EMISSION,
			anchor(id),
			List.of(proof(proofOwner, authority)), List.of());
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.FULL, List.of(
			new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 3L))));
	}

	private static DataOp read(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, 8, 3, 24, 1000);
	}

	private static DataOp write(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, read(name + "-input"),
			OpOpData.TRANSIENTWRITE, name);
	}

	private static PlacementGraphFingerprint.HopOccurrence occurrence(Hop hop,
		StatementBlock block, String path) {
		return new PlacementGraphFingerprint.HopOccurrence(hop, path, "main", block,
			List.of("main"), "linear", false);
	}

	private static Node node(CompiledHopKey key, NodeKind kind, String variable, int ordinal,
		List<Exclusion> exclusions) {
		return new Node(key, kind, new ValueVersionKey(key.programFingerprint(), variable,
			key.controlRegion(), ordinal, VersionKind.ORDINARY, List.of()), true,
			List.of(FED), exclusions, List.of());
	}

	private static NativePlacementContinuity.NativeContinuityProof nativeProof(DurableAnchorKey seed,
		DurableAnchorKey output, List<CandidateRealizationInputBinding> bindings) {
		return nativeProof(seed, output, true, bindings);
	}

	private static NativePlacementContinuity.NativeContinuityProof nativeProof(DurableAnchorKey seed,
		DurableAnchorKey output, boolean exactPartitionRanges,
		List<CandidateRealizationInputBinding> bindings) {
		return new NativePlacementContinuity.NativeContinuityProof(
			seed, output, exactPartitionRanges, bindings);
	}

	private static NativePlacementContinuity nativePools(
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers)
		throws Exception {
		return nativePools(answers, new AtomicInteger(), false);
	}

	private static NativePlacementContinuity nativePools(
		IdentityHashMap<CompiledHopKey,List<NativePlacementContinuity.NativeContinuityProof>> answers,
		AtomicInteger forcedReceiptMisses, boolean nullReceipts) throws Exception {
		NativePlacementContinuity nativePools = Mockito.mock(NativePlacementContinuity.class);
		IdentityHashMap<NativePlacementContinuity.ReplayProofReceipt,MockReplaySnapshot> receipts =
			new IdentityHashMap<>();
		Constructor<NativePlacementContinuity.ReplayProofReceipt> constructor =
			NativePlacementContinuity.ReplayProofReceipt.class.getDeclaredConstructor(Object.class,
				CandidateRealizationReference.class, DurableAnchorKey.class, Map.class, boolean.class);
		constructor.setAccessible(true);
		Mockito.doAnswer(invocation -> {
			CandidateRealizationReference reference = invocation.getArgument(0);
			DurableAnchorKey seed = invocation.getArgument(1);
			List<NativePlacementContinuity.NativeContinuityProof> proofs =
				answers.getOrDefault(reference.rule().parentOccurrence(), List.of());
			NativePlacementContinuity.ReplayProofReceipt receipt = nullReceipts ? null
				: constructor.newInstance(new Object(), reference, seed, Map.of(), proofs.isEmpty());
			if(receipt != null)
				receipts.put(receipt, new MockReplaySnapshot(reference, seed, nativeOutputs(proofs)));
			return new NativePlacementContinuity.ReplayProofResult(proofs, receipt);
		}).when(nativePools).proveCandidateReplay(
			Mockito.any(CandidateRealizationReference.class), Mockito.any(DurableAnchorKey.class));
		Mockito.doAnswer(invocation -> {
			NativePlacementContinuity.ReplayProofReceipt receipt = invocation.getArgument(0);
			CandidateRealizationReference reference = invocation.getArgument(1);
			DurableAnchorKey seed = invocation.getArgument(2);
			if(forcedReceiptMisses.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
				return false;
			MockReplaySnapshot snapshot = receipts.get(receipt);
			return snapshot != null
				&& snapshot.reference().rule().parentOccurrence() == reference.rule().parentOccurrence()
				&& snapshot.reference().equals(reference) && snapshot.seed().equals(seed)
				&& snapshot.outputs().equals(nativeOutputs(
					answers.getOrDefault(reference.rule().parentOccurrence(), List.of())));
		}).when(nativePools).matchesReplayProofReceipt(
			Mockito.any(), Mockito.any(CandidateRealizationReference.class), Mockito.any(DurableAnchorKey.class));
		return nativePools;
	}

	private static Object nativeEvidenceReceipt(Object evidence) throws Exception {
		Map<?,?> nativeProofs = (Map<?,?>)field(evidence, "nativeProofs");
		Assert.assertEquals(1, nativeProofs.size());
		return field(nativeProofs.values().iterator().next(), "receipt");
	}

	private record MockNativeOutput(DurableAnchorKey witness, boolean exactPartitionRanges) { }
	private record MockReplaySnapshot(CandidateRealizationReference reference, DurableAnchorKey seed,
		List<MockNativeOutput> outputs) { }

	private static List<MockNativeOutput> nativeOutputs(
		List<NativePlacementContinuity.NativeContinuityProof> proofs) {
		return proofs.stream().map(proof -> new MockNativeOutput(
			proof.outputWorkerPoolWitness(), proof.exactPartitionRanges())).distinct().toList();
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule, CandidateEmissionFact... emissions) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(emissions), "");
	}

	private static CandidateRuleFact excluded(CompiledHopKey owner) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.PRIVACY_EXCLUDED,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(), "PRIVATE");
	}

	private static CandidateRuleKey rule(CompiledHopKey owner, CandidateInputState input) {
		return new CandidateRuleKey(owner, List.of(input));
	}

	private static CandidateRealizationReference reference(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationReference.of(rule, realization);
	}

	private static CompiledHopKey owner(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"cfg-replay-memo", "main", List.of("main"), "main", "compiled");
		return new CompiledHopKey("cfg-replay-memo", "main", "main",
			"compiled", region, name, name);
	}

	private static Class<?> nested(String simpleName) {
		for(Class<?> type : PlacementRelationClosure.class.getDeclaredClasses())
			if(type.getSimpleName().equals(simpleName))
				return type;
		throw new AssertionError(simpleName + " was not found");
	}

	private static Object inventory(List<CandidateRuleFact> facts) throws Exception {
		Constructor<?> constructor = nested("CandidateReplayInventory").getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	private static Object dependencies(Object inventory) throws Exception {
		return dependencies(inventory, null);
	}

	private static Object dependencies(Object inventory, NativePlacementContinuity nativePools) throws Exception {
		Constructor<?> constructor = nested("ReplayDependencies").getDeclaredConstructor(
			nested("CandidateReplayInventory"), NativePlacementContinuity.class);
		constructor.setAccessible(true);
		return constructor.newInstance(inventory, nativePools);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Object captureContext(PlacementGraphFingerprint.HopOccurrence occurrence,
		Node reader, List<Integer> definitions, List<PlacementGraphFingerprint.HopOccurrence> occurrences,
		List<Node> nodes, Map<Hop,NodeShapeFact> shapes, boolean retainGeometry, boolean allowValueMap)
		throws Exception {
		Method capture = nested("CfgReplayContext").getDeclaredMethod("capture",
			PlacementGraphFingerprint.HopOccurrence.class, Node.class, List.class, List.class,
			List.class, Map.class, boolean.class, boolean.class);
		capture.setAccessible(true);
		return capture.invoke(null, occurrence, reader, definitions, occurrences, nodes,
			shapes, retainGeometry, allowValueMap);
	}

	private static Object captureContext(PlacementGraphFingerprint.HopOccurrence occurrence,
		Node reader, int readOrdinal, List<Integer> definitions,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, List<Node> nodes,
		Map<Hop,NodeShapeFact> shapes, boolean retainGeometry, boolean allowValueMap) throws Exception {
		Method capture = nested("CfgReplayContext").getDeclaredMethod("capture",
			PlacementGraphFingerprint.HopOccurrence.class, Node.class, int.class, List.class, List.class,
			List.class, Map.class, boolean.class, boolean.class);
		capture.setAccessible(true);
		return capture.invoke(null, occurrence, reader, readOrdinal, definitions, occurrences, nodes,
			shapes, retainGeometry, allowValueMap);
	}

	@SuppressWarnings("unchecked")
	private static Map<Node,Integer> firstEqualNodeOrdinals(List<Node> nodes) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("firstEqualNodeOrdinals", List.class);
		method.setAccessible(true);
		return (Map<Node,Integer>)method.invoke(null, nodes);
	}

	private static boolean contextMatches(Object context, Object current) throws Exception {
		return current != null && (boolean)invoke(context, "matches",
			new Class<?>[]{nested("CfgReplayContext")}, current);
	}

	private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
		throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return method.invoke(target, arguments);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> ownerFacts(Object inventory, CompiledHopKey owner) throws Exception {
		return (List<CandidateRuleFact>)invoke(inventory, "ownerFacts",
			new Class<?>[]{CompiledHopKey.class}, owner);
	}

	@SuppressWarnings("unchecked")
	private static Optional<CandidateEmissionRealization> realization(Object inventory,
		CandidateRealizationReference reference) throws Exception {
		return (Optional<CandidateEmissionRealization>)invoke(inventory, "realization",
			new Class<?>[]{CandidateRealizationReference.class}, reference);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> sourceRealizations(Object inventory,
		CompiledHopKey owner, PlacementState state) throws Exception {
		return (List<CandidateRealizationReference>)invoke(inventory, "sourceRealizations",
			new Class<?>[]{CompiledHopKey.class, PlacementState.class}, owner, state);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> sourceFederatedRealizations(Object inventory,
		CompiledHopKey owner) throws Exception {
		return (List<CandidateRealizationReference>)invoke(inventory, "sourceFederatedRealizations",
			new Class<?>[]{CompiledHopKey.class}, owner);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> coldSourceRealizations(CompiledHopKey owner,
		PlacementState state, List<CandidateRuleFact> facts) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"sourceRealizations", CompiledHopKey.class, PlacementState.class, List.class);
		method.setAccessible(true);
		return (List<CandidateRealizationReference>)method.invoke(null, owner, state, facts);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> coldFederatedRealizations(CompiledHopKey owner,
		List<CandidateRuleFact> facts) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"sourceFederatedRealizations", CompiledHopKey.class, List.class);
		method.setAccessible(true);
		return (List<CandidateRealizationReference>)method.invoke(null, owner, facts);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> dependencySourceRealizations(Object dependencies,
		CompiledHopKey owner, PlacementState state) throws Exception {
		return (List<CandidateRealizationReference>)invoke(dependencies, "sourceRealizations",
			new Class<?>[]{CompiledHopKey.class, PlacementState.class}, owner, state);
	}

	@SuppressWarnings("unchecked")
	private static Optional<CandidateEmissionRealization> dependencyRealization(Object dependencies,
		CandidateRealizationReference reference) throws Exception {
		return (Optional<CandidateEmissionRealization>)invoke(dependencies, "realization",
			new Class<?>[]{CandidateRealizationReference.class}, reference);
	}

	@SuppressWarnings("unchecked")
	private static List<?> dependencyNativeProofs(Object dependencies, CompiledHopKey source,
		CandidateRealizationReference reference, DurableAnchorKey seed) throws Exception {
		return (List<?>)invoke(dependencies, "nativeProofs", new Class<?>[]{CompiledHopKey.class,
			CandidateRealizationReference.class, DurableAnchorKey.class, List.class},
			source, reference, seed, List.of());
	}

	private static boolean matches(Object evidence, Object inventory) throws Exception {
		return matches(evidence, inventory, null);
	}

	private static boolean matches(Object evidence, Object inventory,
		NativePlacementContinuity nativePools) throws Exception {
		return (boolean)invoke(evidence, "matches",
			new Class<?>[]{nested("CandidateReplayInventory"), NativePlacementContinuity.class},
			inventory, nativePools);
	}
}
