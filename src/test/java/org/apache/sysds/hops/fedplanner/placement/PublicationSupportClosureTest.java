/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientCompatibilityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientPlacementCompatibility;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Publication may discard expired proofs, but cannot borrow or remap their authority. */
public class PublicationSupportClosureTest {
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), false);
	private static final PlacementEmissionState ROW = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	private static final CandidateRuleKey SOURCE = rule("source"), SOURCE_B = rule("source-b"),
		CONSUMER = rule("consumer"), DOWNSTREAM = rule("downstream");

	@Test
	public void finalActionProjectionRetainsExactAndDirectAuthorityAfterClausePruning() throws Exception {
		var current = source("current");
		var expired = source("expired");
		var liveAction = action("live-action", 0);
		var orphanAction = action("orphan-action", 0);
		var directTemplate = action("direct-action", 0);
		var directAction = new NeutralPlacementGraph.RelocationAction(directTemplate.key(),
			directTemplate.obligations(), List.of(ROW.placementState()));
		var live = CandidateEmissionRealization.local(LOCAL, List.of(), List.of(
			CandidateRealizationInputBinding.relocation(0,
				CandidateRealizationReference.of(SOURCE, current), liveAction.key())));
		var stale = CandidateEmissionRealization.local(LOCAL, List.of(), List.of(
			CandidateRealizationInputBinding.relocation(0,
				CandidateRealizationReference.of(SOURCE, expired), orphanAction.key())));
		var facts = publish(List.of(fact(SOURCE, ROW, List.of(current)),
			fact(CONSUMER, LOCAL, List.of(live, stale))),
			Map.of(liveAction.key(), liveAction, orphanAction.key(), orphanAction));
		Assert.assertEquals(List.of(live), facts.get(1).allowedEmissionFacts().get(0).realizations());
		var actions = List.of(orphanAction, directAction, liveAction);
		Assert.assertThrows("the final validator must still reject discovery-only actions",
			IllegalStateException.class, () ->
				PlacementSupportRelations.verifyPublishedRelocationRealizations(facts, actions));

		var projected = PlacementSupportRelations.projectRelocationActionsToExecutableSupports(facts, actions);

		Assert.assertEquals(List.of(directAction, liveAction), projected);
		Assert.assertSame(directAction, projected.get(0));
		Assert.assertSame(liveAction, projected.get(1));
		Assert.assertSame(projected,
			PlacementSupportRelations.projectRelocationActionsToExecutableSupports(facts, projected));
		PlacementSupportRelations.verifyPublishedRelocationRealizations(facts, projected);
	}

	@Test
	public void finalActionProjectionCannotHideMissingActionOrExpiredSource() {
		var current = source("current");
		var expired = source("expired");
		var action = action("action", 0);
		for(var referencedSource : List.of(current, expired)) {
			var consumer = CandidateEmissionRealization.local(LOCAL, List.of(), List.of(
				CandidateRealizationInputBinding.relocation(0,
					CandidateRealizationReference.of(SOURCE, referencedSource), action.key())));
			var facts = List.of(fact(SOURCE, ROW, List.of(current)), fact(CONSUMER, LOCAL, List.of(consumer)));
			var actions = referencedSource == current ? List.<NeutralPlacementGraph.RelocationAction>of()
				: List.of(action);
			var projected = PlacementSupportRelations.projectRelocationActionsToExecutableSupports(facts, actions);
			Assert.assertSame(actions, projected);
			Assert.assertThrows(IllegalStateException.class, () ->
				PlacementSupportRelations.verifyPublishedRelocationRealizations(facts, projected));
		}
	}

	@Test
	public void unchangedPublicationReusesAlreadyCanonicalObjects() throws Exception {
		CandidateRuleFact original = fact(SOURCE, ROW, List.of(source("a"), source("b")));
		Assert.assertSame(original, publish(List.of(original)).get(0));
	}

	@Test
	public void equivalentSeedRequestsStillDistinguishPinnedOutputAndRanges() throws Exception {
		var ctor = Class.forName(PlacementRelationClosure.class.getName() + "$DirectNativeSeedKey")
			.getDeclaredConstructor(FType.class, List.class, CandidateRealizationReference.class);
		ctor.setAccessible(true);
		var partitions = source("seed-a").anchor().partitions();
		var output = CandidateRealizationReference.of(SOURCE, source("output"));
		Object first = ctor.newInstance(FType.ROW, partitions, output);
		Assert.assertEquals(first, ctor.newInstance(FType.ROW,
			source("seed-b").anchor().partitions(), output));
		Assert.assertNotEquals(first, ctor.newInstance(FType.ROW, partitions,
			CandidateRealizationReference.of(SOURCE, source("another-output"))));
		Assert.assertNotEquals(first, ctor.newInstance(FType.ROW,
			List.of(new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(5L, 2L))), output));
	}

	@Test
	public void privacyRowReuseRequiresTheSameExactEmissionObjects() {
		CandidateRuleFact original = fact(SOURCE, ROW, List.of(source("current")));
		Assert.assertSame(original, PlacementSupportRelations.retainUnchangedPrivacyFact(
			original, original.allowedEmissionFacts()));
		CandidateEmissionFact replacement = new CandidateEmissionFact(ROW, FType.ROW, null,
			List.of(source("another-worker-map")));
		CandidateRuleFact replaced = PlacementSupportRelations.retainUnchangedPrivacyFact(
			original, List.of(replacement));
		Assert.assertNotSame("a different realization authority must not reuse the old row", original, replaced);
		Assert.assertEquals(List.of(replacement), replaced.allowedEmissionFacts());
	}

	@Test
	public void expiredClauseDoesNotRemoveTheValidAlternative() throws Exception {
		CandidateEmissionRealization source = source("current"), expired = source("expired");
		CandidateEmissionRealization validClause = local(source, 0), expiredClause = local(expired, 0);
		List<CandidateRuleFact> result = publish(List.of(fact(SOURCE, ROW, List.of(source)),
			fact(CONSUMER, LOCAL, List.of(validClause, expiredClause))));
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, result.get(1).status());
		Assert.assertEquals(List.of(validClause), result.get(1).allowedEmissionFacts().get(0).realizations());
		Assert.assertEquals(result, publish(result));
	}

	@Test
	public void oneLiveInputCannotAuthorizeAnExpiredConjunct() throws Exception {
		CandidateEmissionRealization source = source("current"), expired = source("expired");
		CandidateEmissionRealization conjunction = CandidateEmissionRealization.local(LOCAL, List.of(), List.of(
			CandidateRealizationInputBinding.direct(0, CandidateRealizationReference.of(SOURCE, source)),
			CandidateRealizationInputBinding.direct(1, CandidateRealizationReference.of(SOURCE, expired))));
		List<CandidateRuleFact> result = publish(List.of(fact(SOURCE, ROW, List.of(source)),
			fact(CONSUMER, LOCAL, List.of(conjunction))));
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, result.get(0).status());
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR, result.get(1).status());
		Assert.assertTrue(result.get(1).allowedEmissionFacts().isEmpty());
	}

	@Test
	public void expiredRelocationRemovesItsWholeClauseButKeepsLiveSiblings() throws Exception {
		CandidateEmissionRealization current = source("current");
		CandidateRealizationReference currentReference = CandidateRealizationReference.of(SOURCE, current);
		NeutralPlacementGraph.RelocationAction liveAction = action("live-action", 0);
		RelocationActionKey expiredAction = action("expired-action", 0).key();
		CandidateRealizationSupportClause expiredConjunction = new CandidateRealizationSupportClause(
			List.of(), List.of(
				CandidateRealizationInputBinding.relocation(0, currentReference, expiredAction),
				CandidateRealizationInputBinding.direct(1, currentReference)));
		CandidateRealizationSupportClause directSibling = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, currentReference)));
		CandidateRealizationSupportClause liveRelocationSibling = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.relocation(
				0, currentReference, liveAction.key())));
		CandidateEmissionRealization alternatives = new CandidateEmissionRealization(
			CandidateEmissionRealization.local(LOCAL).key(),
			List.of(expiredConjunction, directSibling, liveRelocationSibling));

		List<CandidateRuleFact> result = publish(List.of(fact(SOURCE, ROW, List.of(current)),
			fact(CONSUMER, LOCAL, List.of(alternatives))), Map.of(liveAction.key(), liveAction));

		CandidateEmissionRealization retained = result.get(1).allowedEmissionFacts().get(0).realizations().get(0);
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, result.get(1).status());
		Assert.assertEquals("only the expired relocation conjunct is pruned", 2,
			retained.supportClauses().size());
		Assert.assertTrue("the direct OR sibling remains valid",
			retained.supportClauses().contains(directSibling));
		Assert.assertTrue("a still-authorized relocation remains valid",
			retained.supportClauses().contains(liveRelocationSibling));
		Assert.assertFalse("one live direct input cannot rescue an expired action in the same AND clause",
			retained.supportClauses().contains(expiredConjunction));
	}

	@Test
	public void logicalReaderRequiresLiveSupportFromEveryReachingWriter() throws Exception {
		CandidateEmissionRealization sourceA = source("source-a-live");
		CandidateEmissionRealization sourceB = source("source-b-live");
		CandidateEmissionRealization expiredB = source("source-b-expired");
		CandidateEmissionRealization unsupportedReader = source("reader-unsupported");
		CandidateEmissionRealization supportedReader = source("reader-supported");
		CandidateEmissionRealization staleDownstream = CandidateEmissionRealization.local(LOCAL,
			List.of(), List.of(CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(CONSUMER, unsupportedReader))));
		List<LogicalTransientInputFact> completeRelation = List.of(
			logicalInput(SOURCE, List.of(sourceA, sourceA),
				List.of(unsupportedReader, supportedReader)),
			logicalInput(SOURCE_B, List.of(expiredB, sourceB),
				List.of(unsupportedReader, supportedReader)));
		List<CandidateRuleFact> facts = List.of(
			fact(SOURCE, ROW, List.of(sourceA)),
			fact(SOURCE_B, ROW, List.of(sourceB)),
			fact(CONSUMER, ROW, List.of(unsupportedReader, supportedReader)),
			fact(DOWNSTREAM, LOCAL, List.of(staleDownstream)));

		List<CandidateRuleFact> result = publishClosed(facts, completeRelation);

		Assert.assertEquals("the reader alternative with one expired reaching writer is removed",
			List.of(supportedReader), result.get(2).allowedEmissionFacts().get(0).realizations());
		Assert.assertEquals("loss of the unsupported reader propagates to downstream bindings",
			CandidateEvaluationStatus.PROFILE_ERROR, result.get(3).status());
	}

	@Test
	public void authoritativeWriterInventoryRejectsAnEntireMissingRelation() throws Exception {
		CandidateEmissionRealization sourceA = source("source-a-live");
		CandidateEmissionRealization sourceB = source("source-b-live");
		CandidateEmissionRealization reader = source("reader");
		List<LogicalTransientInputFact> incompleteRelation = List.of(
			logicalInput(SOURCE, List.of(sourceA), List.of(reader)));
		List<CandidateRuleFact> facts = List.of(
			fact(SOURCE, ROW, List.of(sourceA)),
			fact(SOURCE_B, ROW, List.of(sourceB)),
			fact(CONSUMER, ROW, List.of(reader)));

		List<CandidateRuleFact> result = publishClosed(facts, incompleteRelation,
			Map.of(CONSUMER.parentOccurrence(),
				List.of(SOURCE.parentOccurrence(), SOURCE_B.parentOccurrence())));

		Assert.assertEquals("CFG still requires the writer whose whole compatibility record disappeared",
			CandidateEvaluationStatus.PROFILE_ERROR, result.get(2).status());
	}

	private static CandidateEmissionRealization local(CandidateEmissionRealization source, int position) {
		return CandidateEmissionRealization.local(LOCAL, List.of(),
			List.of(CandidateRealizationInputBinding.direct(position, CandidateRealizationReference.of(SOURCE, source))));
	}

	private static CandidateEmissionRealization source(String id) {
		return CandidateEmissionRealization.durable(ROW,
			new DurableAnchorKey(id, FType.ROW, List.of(new AnchorPartition("localhost:1234",
				List.of(0L, 0L), List.of(4L, 2L)))), List.of(), List.of());
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey("publication", "main", List.of("root"), "root", "compiled");
		return new CandidateRuleKey(new CompiledHopKey("publication", "main", "root", "compiled", region, name, name),
			List.of(CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW)));
	}

	private static NeutralPlacementGraph.RelocationAction action(String id, int position) {
		ValueVersionKey sourceVersion = new ValueVersionKey("publication", "source",
			SOURCE.parentOccurrence().controlRegion(), 0, VersionKind.ORDINARY, List.of());
		RelocationActionKey key = new RelocationActionKey(sourceVersion, LOCAL.placementState(),
			FType.ROW, source(id).anchor(), "root", List.of(CONSUMER.parentOccurrence()));
		ObligationKey obligation = new ObligationKey(CONSUMER.parentOccurrence(), position,
			sourceVersion, LOCAL.placementState(), key, "root");
		return new NeutralPlacementGraph.RelocationAction(key, List.of(obligation));
	}

	private static LogicalTransientInputFact logicalInput(CandidateRuleKey writer,
		List<CandidateEmissionRealization> sources, List<CandidateEmissionRealization> readers) {
		if(sources.size() != readers.size())
			throw new IllegalArgumentException("Transient fixture source/reader counts differ");
		ValueVersionKey sourceVersion = version(writer, writer == SOURCE ? 0 : 1);
		ValueVersionKey readerVersion = version(CONSUMER, 2);
		PlacementProofKey proof = PlacementAnalysis.transientValueIdentityProof(
			writer.parentOccurrence(), sourceVersion, readerVersion);
		List<TransientPlacementCompatibility> compatibility = new java.util.ArrayList<>();
		for(int index = 0; index < sources.size(); index++) {
			CandidateEmissionRealization source = sources.get(index);
			CandidateEmissionRealization reader = readers.get(index);
			compatibility.add(new TransientPlacementCompatibility(
				CandidateRealizationReference.of(writer, source),
				CandidateRealizationReference.of(CONSUMER, reader),
				CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW),
				new TransientCompatibilityProof(source.anchor(), reader.anchor(), List.of(proof))));
		}
		return new LogicalTransientInputFact(writer.parentOccurrence(), CONSUMER.parentOccurrence(),
			0, sourceVersion, readerVersion, compatibility);
	}

	private static ValueVersionKey version(CandidateRuleKey rule, int ordinal) {
		return new ValueVersionKey("publication", rule.parentOccurrence().emittedHopInstance(),
			rule.parentOccurrence().controlRegion(), ordinal, VersionKind.ORDINARY, List.of());
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, PlacementEmissionState emission,
		List<CandidateEmissionRealization> realizations) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.FED,
				emission.placementState().output(), emission.placementState().fType(), ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()), new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(emission, FType.ROW, null, realizations)), "");
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> publish(List<CandidateRuleFact> facts) throws Exception {
		Method method = PlacementSupportRelations.class.getDeclaredMethod("pruneUnsupportedRealizations", List.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, facts);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> publish(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions) throws Exception {
		Method method = PlacementSupportRelations.class.getDeclaredMethod(
			"pruneUnsupportedRealizations", List.class, Map.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, facts, actions);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> publishClosed(List<CandidateRuleFact> facts,
		List<LogicalTransientInputFact> logicalInputs) throws Exception {
		return publishClosed(facts, logicalInputs, null);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> publishClosed(List<CandidateRuleFact> facts,
		List<LogicalTransientInputFact> logicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) throws Exception {
		Method method = PlacementSupportRelations.class.getDeclaredMethod(
			"pruneUnsupportedRealizations", List.class, Map.class, List.class, Map.class);
		method.setAccessible(true);
		List<CandidateRuleFact> current = facts;
		while(true) {
			List<CandidateRuleFact> next = (List<CandidateRuleFact>)method.invoke(
				null, current, null, logicalInputs, requiredWriters);
			if(next.equals(current))
				return next;
			current = next;
		}
	}
}
