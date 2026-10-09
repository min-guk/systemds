/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Differential contract for deletion-only support closure. */
public class PlacementSupportDeletionWorklistTest {
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), false);
	private static final PlacementEmissionState ROW = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void cascadingDeletionMatchesRepeatedFullPasses() throws Exception {
		CandidateRuleKey a = rule("cascade-a"), b = rule("cascade-b"), c = rule("cascade-c");
		CandidateEmissionRealization invalid = CandidateEmissionRealization.nativeLineage(
			ROW, "missing-worker-pool", List.of(), List.of());
		CandidateEmissionRealization rb = dependent(b, ref(a, invalid));
		CandidateEmissionRealization rc = dependent(c, ref(b, rb));
		List<CandidateRuleFact> facts = List.of(fact(a, ROW, List.of(invalid)),
			fact(b, LOCAL, List.of(rb)), fact(c, LOCAL, List.of(rc)));

		assertSameClosure(facts, null, null, null);
		PlacementSupportRelations.WorklistResult measured = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		Assert.assertTrue(measured.facts().stream()
			.allMatch(fact -> fact.status() == CandidateEvaluationStatus.PROFILE_ERROR));
		Assert.assertTrue("deletion seed must use the original propagation worklist",
			workMetric(measured.work(), "materializedReverseIncidences") > 0);
		Assert.assertEquals(measured.work().reverseIncidences(),
			workMetric(measured.work(), "materializedReverseIncidences"));
	}

	@Test
	public void supportedCycleSurvivesAsGreatestDeletionFixedPoint() throws Exception {
		CandidateRuleKey a = rule("cycle-a"), b = rule("cycle-b");
		CandidateEmissionRealization keyA = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization keyB = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization ra = dependent(a, ref(b, keyB));
		CandidateEmissionRealization rb = dependent(b, ref(a, keyA));
		List<CandidateRuleFact> facts = List.of(fact(a, LOCAL, List.of(ra)), fact(b, LOCAL, List.of(rb)));

		List<CandidateRuleFact> result = fixed(facts, null, null, null);
		Assert.assertEquals(repeated(facts, null, null, null), result);
		Assert.assertSame(facts.get(0), result.get(0));
		Assert.assertSame(facts.get(1), result.get(1));
		PlacementSupportRelations.WorklistResult measured = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		Assert.assertEquals(0,
			workMetric(measured.work(), "materializedReverseIncidences"));
	}

	@Test
	public void expiredOrSiblingAndActionDoNotRemoveLiveAlternative() throws Exception {
		CandidateRuleKey sourceRule = rule("or-source"), consumer = rule("or-consumer");
		CandidateEmissionRealization source = durable("or-live");
		CandidateRealizationReference sourceRef = ref(sourceRule, source);
		NeutralPlacementGraph.RelocationAction liveAction = action("or-live-action", consumer, 0);
		RelocationActionKey expiredAction = action("or-expired-action", consumer, 0).key();
		CandidateRealizationSupportClause expired = clause(
			CandidateRealizationInputBinding.relocation(0, sourceRef, expiredAction));
		CandidateRealizationSupportClause direct = clause(
			CandidateRealizationInputBinding.direct(0, sourceRef));
		CandidateRealizationSupportClause relocated = clause(
			CandidateRealizationInputBinding.relocation(0, sourceRef, liveAction.key()));
		CandidateEmissionRealization alternatives = new CandidateEmissionRealization(
			CandidateEmissionRealization.local(LOCAL).key(), List.of(expired, direct, relocated));
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, ROW, List.of(source)),
			fact(consumer, LOCAL, List.of(alternatives)));

		List<CandidateRuleFact> result = fixed(facts, Map.of(liveAction.key(), liveAction), null, null);
		Assert.assertEquals(repeated(facts, Map.of(liveAction.key(), liveAction), null, null), result);
		List<CandidateRealizationSupportClause> survivors = result.get(1).allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses();
		Assert.assertEquals(alternatives.supportClauses().stream()
			.filter(clause -> !clause.equals(expired)).toList(), survivors);
		PlacementSupportRelations.WorklistResult measured = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(
				facts, Map.of(liveAction.key(), liveAction), null, null);
		Assert.assertEquals("invalid OR sibling is not a realization deletion seed", 0,
			workMetric(measured.work(), "materializedReverseIncidences"));
		List<CandidateRealizationSupportClause> measuredSurvivors = measured.facts().get(1)
			.allowedEmissionFacts().get(0).realizations().get(0).supportClauses();
		List<CandidateRealizationSupportClause> expectedSurvivors = alternatives.supportClauses().stream()
			.filter(clause -> clause != expired).toList();
		Assert.assertEquals(expectedSurvivors.size(), measuredSurvivors.size());
		for(int index = 0; index < expectedSurvivors.size(); index++)
			Assert.assertSame(expectedSurvivors.get(index), measuredSurvivors.get(index));
	}

	@Test
	public void everyReachingWriterRemainsMandatory() throws Exception {
		CandidateRuleKey writerA = rule("writer-a"), writerB = rule("writer-b"), reader = rule("reader");
		CandidateEmissionRealization sourceA = durable("writer-a-live");
		CandidateEmissionRealization sourceB = durable("writer-b-live");
		CandidateEmissionRealization expiredB = durable("writer-b-expired");
		CandidateEmissionRealization unsupported = durable("reader-unsupported");
		CandidateEmissionRealization supported = durable("reader-supported");
		List<LogicalTransientInputFact> logical = List.of(
			logicalInput(writerA, reader, List.of(sourceA, sourceA), List.of(unsupported, supported), 0),
			logicalInput(writerB, reader, List.of(expiredB, sourceB), List.of(unsupported, supported), 1));
		List<CandidateRuleFact> facts = List.of(fact(writerA, ROW, List.of(sourceA)),
			fact(writerB, ROW, List.of(sourceB)), fact(reader, ROW, List.of(unsupported, supported)));
		Map<CompiledHopKey,List<CompiledHopKey>> required = Map.of(reader.parentOccurrence(),
			List.of(writerA.parentOccurrence(), writerB.parentOccurrence()));

		List<CandidateRuleFact> result = fixed(facts, null, logical, required);
		Assert.assertEquals(repeated(facts, null, logical, required), result);
		Assert.assertEquals(List.of(supported), result.get(2).allowedEmissionFacts().get(0).realizations());
		PlacementSupportRelations.WorklistResult measured = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, logical, required);
		Assert.assertEquals(8, measured.work().logicalRequirements());
		Assert.assertEquals(6, measured.work().reverseIncidences());
		Assert.assertTrue(workMetric(measured.work(), "materializedReverseIncidences") > 0);
		Assert.assertEquals(measured.work().reverseIncidences(),
			workMetric(measured.work(), "materializedReverseIncidences"));
	}

	@Test
	public void duplicateReferenceStaysLiveUntilItsLastSlotIsDeleted() throws Exception {
		CandidateRuleKey sourceRule = rule("duplicate-source"), consumer = rule("duplicate-consumer");
		CandidateEmissionRealization key = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization live = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization dead = new CandidateEmissionRealization(
			key.key(), List.of(clause(CandidateRealizationInputBinding.direct(0,
				ref(rule("missing"), CandidateEmissionRealization.local(LOCAL))))));
		CandidateEmissionRealization dependent = dependent(consumer, ref(sourceRule, key));
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, LOCAL, List.of(dead)),
			fact(sourceRule, LOCAL, List.of(live)), fact(consumer, LOCAL, List.of(dependent)));

		List<CandidateRuleFact> result = fixed(facts, null, null, null);
		Assert.assertEquals(repeated(facts, null, null, null), result);
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR, result.get(0).status());
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, result.get(1).status());
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, result.get(2).status());
		PlacementSupportRelations.WorklistResult measured = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		Assert.assertTrue("initial executable slot loss conservatively falls back",
			workMetric(measured.work(), "materializedReverseIncidences") > 0);
	}

	@Test
	public void unrelatedInitiallyNonExecutableSlotDoesNotSeedPropagation() throws Exception {
		CandidateRuleKey sourceRule = rule("nonexec-source"), consumer = rule("nonexec-consumer");
		CandidateEmissionRealization invalid = CandidateEmissionRealization.nativeLineage(
			ROW, "missing-worker-pool", List.of(), List.of());
		CandidateEmissionRealization live = durable("nonexec-live");
		CandidateEmissionRealization dependent = dependent(consumer, ref(sourceRule, live));
		List<CandidateRuleFact> facts = List.of(
			fact(sourceRule, ROW, List.of(invalid, live)), fact(consumer, LOCAL, List.of(dependent)));

		PlacementSupportRelations.WorklistResult measured = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		Assert.assertEquals(repeated(facts, null, null, null), measured.facts());
		Assert.assertEquals(List.of(live), measured.facts().get(0).allowedEmissionFacts()
			.get(0).realizations());
		Assert.assertSame(facts.get(1), measured.facts().get(1));
		Assert.assertEquals(3, measured.work().indexedRealizations());
		Assert.assertEquals(3, measured.work().indexedClauses());
		Assert.assertEquals(1, measured.work().deletedRealizations());
		Assert.assertEquals(1, measured.work().reverseIncidences());
		Assert.assertEquals(0,
			workMetric(measured.work(), "materializedReverseIncidences"));
	}

	@Test
	public void deterministicRandomGraphsMatchRepeatedFullPasses() {
		for(int seed = 0; seed < 32; seed++) {
			Random random = new Random(seed);
			List<CandidateRuleKey> rules = new ArrayList<>();
			for(int index = 0; index < 24; index++)
				rules.add(rule("random-" + seed + "-" + index));
			CandidateRuleKey missing = rule("random-" + seed + "-missing");
			List<CandidateRuleFact> facts = new ArrayList<>();
			for(int index = 0; index < rules.size(); index++) {
				List<CandidateRealizationSupportClause> alternatives = new ArrayList<>();
				int alternativesCount = 1 + random.nextInt(3);
				for(int alternative = 0; alternative < alternativesCount; alternative++) {
					CandidateRuleKey source = random.nextInt(7) == 0
						? missing : rules.get(random.nextInt(rules.size()));
					alternatives.add(clause(CandidateRealizationInputBinding.direct(0,
						ref(source, CandidateEmissionRealization.local(LOCAL)))));
				}
				CandidateEmissionRealization realization = new CandidateEmissionRealization(
					CandidateEmissionRealization.local(LOCAL).key(),
					alternatives.stream().distinct().toList());
				facts.add(fact(rules.get(index), LOCAL, List.of(realization)));
			}
			assertSameClosure(facts, null, null, null);
		}
	}

	@Test
	public void equalButDistinctRelocationConsumerIsRejectedByIdentity() {
		CandidateRuleKey actual = rule("foreign-consumer");
		CandidateRuleKey equalForeign = rule("foreign-consumer");
		Assert.assertEquals(actual.parentOccurrence(), equalForeign.parentOccurrence());
		Assert.assertNotSame(actual.parentOccurrence(), equalForeign.parentOccurrence());
		CandidateEmissionRealization source = durable("foreign-source");
		NeutralPlacementGraph.RelocationAction action = action("foreign-action", equalForeign, 0);
		CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.relocation(
			0, ref(rule("foreign-source-rule"), source), action.key());
		Assert.assertFalse(PlacementSupportRelations.supportsRelocationBinding(
			action, actual, LOCAL, binding));
	}

	@Test
	public void duplicateSourceBindingsCountOneLogicalClauseIncidenceWithoutMaterialization()
		throws Exception {
		CandidateRuleKey sourceRule = rule("incidence-source"), consumer = rule("incidence-consumer");
		CandidateEmissionRealization source = durable("incidence-live");
		CandidateRealizationReference reference = ref(sourceRule, source);
		CandidateEmissionRealization dependent = CandidateEmissionRealization.local(LOCAL, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, reference),
				CandidateRealizationInputBinding.direct(1, reference)));
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, ROW, List.of(source)),
			fact(consumer, LOCAL, List.of(dependent)));

		PlacementSupportRelations.WorklistResult measured = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		Assert.assertEquals(1, measured.work().reverseIncidences());
		Assert.assertEquals(0,
			workMetric(measured.work(), "materializedReverseIncidences"));
		Assert.assertSame(facts.get(0), measured.facts().get(0));
		Assert.assertSame(facts.get(1), measured.facts().get(1));
	}

	@Test
	public void workCountersAreDeterministicAndDoNotChangeTheResult() throws Exception {
		CandidateRuleKey sourceRule = rule("count-source"), consumer = rule("count-consumer");
		CandidateEmissionRealization source = durable("count-live");
		CandidateEmissionRealization dependent = dependent(consumer, ref(sourceRule, source));
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, ROW, List.of(source)),
			fact(consumer, LOCAL, List.of(dependent)));

		PlacementSupportRelations.WorklistResult first = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		PlacementSupportRelations.WorklistResult second = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		Assert.assertEquals(first, second);
		Assert.assertEquals(2, first.work().indexedRealizations());
		Assert.assertEquals(2, first.work().indexedClauses());
		Assert.assertEquals("logical inventory remains visible without allocation",
			1, first.work().reverseIncidences());
		Assert.assertEquals(0,
			workMetric(first.work(), "materializedReverseIncidences"));
		Assert.assertEquals(0, first.work().deletedRealizations());
		Assert.assertEquals(facts, first.facts());
	}

	@Test
	public void factorizedHundredByHundredNoOpDoesNotMaterializeClauses() {
		CandidateRuleKey consumer = rule("factorized-large-consumer");
		List<CandidateRuleFact> facts = new ArrayList<>();
		List<CandidateRealizationInputBinding> left = new ArrayList<>();
		List<CandidateRealizationInputBinding> right = new ArrayList<>();
		for(int index = 0; index < 200; index++) {
			CandidateRuleKey sourceRule = rule("factorized-large-source-" + index);
			CandidateEmissionRealization source = durable("factorized-large-source-" + index);
			facts.add(fact(sourceRule, ROW, List.of(source)));
			CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.direct(
				index < 100 ? 0 : 1, ref(sourceRule, source));
			(index < 100 ? left : right).add(binding);
		}
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			CandidateEmissionRealization.local(LOCAL).key(), List.of(), List.of(left, right), null, true);
		FactorizedSupportClauses relation = (FactorizedSupportClauses)product.supportClauses();
		facts.add(fact(consumer, LOCAL, List.of(product)));

		PlacementSupportRelations.WorklistResult result = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);

		Assert.assertEquals(10_000, relation.size());
		Assert.assertEquals(0, relation.materializedClauseCount());
		Assert.assertSame(facts.get(facts.size() - 1), result.facts().get(facts.size() - 1));
		Assert.assertEquals("factor-level reverse index counts dependency options", 200,
			result.work().reverseIncidences());
		Assert.assertTrue(PlacementSupportRelations.projectRelocationActionsToExecutableSupports(
			result.facts(), List.of()).isEmpty());
		PlacementSupportRelations.verifyPublishedRelocationRealizations(result.facts(), List.of());
		Assert.assertEquals(0, relation.materializedClauseCount());
	}

	@Test
	public void conditionalNativeProductDiesWhenOnlyExcludedExactRectangleRemains() {
		CandidateRuleKey leftRule = rule("conditional-left");
		CandidateRuleKey rightRule = rule("conditional-right");
		CandidateRuleKey consumer = rule("conditional-consumer");
		CandidateEmissionRealization leftExact = durable("conditional-left-exact");
		CandidateEmissionRealization leftInexact = durable("conditional-left-inexact");
		CandidateEmissionRealization rightExact = durable("conditional-right-exact");
		CandidateEmissionRealization rightInexact = durable("conditional-right-inexact");
		var leftExactBinding = CandidateRealizationInputBinding.direct(0,
			ref(leftRule, leftExact));
		var leftInexactBinding = CandidateRealizationInputBinding.direct(0,
			ref(leftRule, leftInexact));
		var rightExactBinding = CandidateRealizationInputBinding.direct(1,
			ref(rightRule, rightExact));
		var rightInexactBinding = CandidateRealizationInputBinding.direct(1,
			ref(rightRule, rightInexact));
		DurableAnchorKey pool = anchor("conditional-product");
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(pool, pool, true,
			List.of(List.of(leftExactBinding, leftInexactBinding).stream().sorted().toList(),
				List.of(rightExactBinding, rightInexactBinding).stream().sorted().toList()));
		var relation = NativeContinuitySupportClauses.exactComplement(
			consumer.parentOccurrence(), base,
			List.of(List.of(leftExactBinding), List.of(rightExactBinding)), pool, true);
		Assert.assertNotNull(relation);
		CandidateEmissionRealization dependent = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(ROW, "conditional-product"), relation);
		List<CandidateRuleFact> complete = List.of(
			fact(leftRule, ROW, List.of(leftExact, leftInexact)),
			fact(rightRule, ROW, List.of(rightExact, rightInexact)),
			fact(consumer, ROW, List.of(dependent)));
		var retained = PlacementSupportRelations.pruneUnsupportedRealizationsToFixedPointWithWork(
			complete, null, null, null);
		Assert.assertSame(complete.get(2), retained.facts().get(2));
		Assert.assertEquals(0, relation.materializedHandleCount());

		List<CandidateRuleFact> exactOnly = List.of(
			fact(leftRule, ROW, List.of(leftExact)), fact(rightRule, ROW, List.of(rightExact)),
			fact(consumer, ROW, List.of(dependent)));
		var removed = PlacementSupportRelations.pruneUnsupportedRealizationsToFixedPointWithWork(
			exactOnly, null, null, null);
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR,
			removed.facts().get(2).status());
		Assert.assertTrue(removed.work().deletedRealizations() >= 1);
		Assert.assertEquals(0, relation.materializedHandleCount());
	}

	@Test
	public void factorizedActionRestrictionPreservesProofAndNativeWitness() {
		CandidateRuleKey sourceRule = rule("factorized-action-source");
		CandidateRuleKey consumer = rule("factorized-action-consumer");
		CandidateEmissionRealization source = durable("factorized-action-source");
		CandidateRealizationReference sourceRef = ref(sourceRule, source);
		NeutralPlacementGraph.RelocationAction live = action(
			"factorized-action-live", consumer, 0, ROW.placementState());
		RelocationActionKey expired = action(
			"factorized-action-expired", consumer, 0, ROW.placementState()).key();
		DurableAnchorKey witness = anchor("factorized-native-witness");
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, consumer.parentOccurrence(), "factorized-proof");
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			PlacementIdentity.PlacementRealizationKey.nativeLineage(ROW, "factorized-native"),
			List.of(proof), List.of(List.of(
				CandidateRealizationInputBinding.relocation(0, sourceRef, expired),
				CandidateRealizationInputBinding.relocation(0, sourceRef, live.key()))), witness, false);
		FactorizedSupportClauses original = (FactorizedSupportClauses)product.supportClauses();
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, ROW, List.of(source)),
			fact(consumer, ROW, List.of(product)));

		List<CandidateRuleFact> result = fixed(facts, Map.of(live.key(), live), null, null);
		CandidateEmissionRealization retained = result.get(1).allowedEmissionFacts().get(0)
			.realizations().get(0);
		FactorizedSupportClauses restricted = (FactorizedSupportClauses)retained.supportClauses();

		Assert.assertEquals(0, original.materializedClauseCount());
		Assert.assertEquals(1, restricted.size());
		Assert.assertEquals(0, restricted.materializedClauseCount());
		Assert.assertSame(proof, restricted.proofs().get(0));
		Assert.assertSame(witness, restricted.nativeWorkerPoolWitness());
		Assert.assertFalse(restricted.nativeWorkerPoolLayoutExact());
		Assert.assertSame(live.key(), restricted.factors().get(0).get(0).relocationAction());

		CandidateEmissionRealization explicit = new CandidateEmissionRealization(product.key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof), List.of(
				CandidateRealizationInputBinding.relocation(0, sourceRef, expired)), witness, false),
			new CandidateRealizationSupportClause(List.of(proof), List.of(
				CandidateRealizationInputBinding.relocation(0, sourceRef, live.key())), witness, false)));
		List<CandidateRuleFact> explicitResult = fixed(List.of(fact(sourceRule, ROW, List.of(source)),
			fact(consumer, ROW, List.of(explicit))), Map.of(live.key(), live), null, null);
		CandidateRealizationSupportClause explicitSurvivor = explicitResult.get(1).allowedEmissionFacts()
			.get(0).realizations().get(0).supportClauses().get(0);
		Assert.assertEquals(explicitSurvivor.inputBindings(), restricted.factors().get(0));
		Assert.assertEquals(explicitSurvivor.proofDependencies(), restricted.proofs());
		Assert.assertSame(explicitSurvivor.nativeWorkerPoolWitness(),
			restricted.nativeWorkerPoolWitness());
	}

	@Test
	public void factorizedQueuedDeletionRetainsTheNonemptyRemainder() {
		CandidateRuleKey missing = rule("partial-missing"), middle = rule("partial-middle");
		CandidateRuleKey live = rule("partial-live"), right = rule("partial-right");
		CandidateRuleKey consumer = rule("partial-consumer");
		CandidateEmissionRealization source = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization invalid = dependent(middle, ref(missing, source));
		CandidateRealizationInputBinding expired = CandidateRealizationInputBinding.direct(0, ref(middle, invalid));
		CandidateRealizationInputBinding retained = CandidateRealizationInputBinding.direct(0, ref(live, source));
		CandidateRealizationInputBinding rhs = CandidateRealizationInputBinding.direct(1, ref(right, source));
		PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			consumer.parentOccurrence(), "partial-proof");
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(source.key(),
			List.of(proof), List.of(List.of(expired, retained), List.of(rhs)), null, true);
		List<CandidateRuleFact> facts = List.of(fact(middle, LOCAL, List.of(invalid)),
			fact(live, LOCAL, List.of(source)), fact(right, LOCAL, List.of(source)),
			fact(consumer, LOCAL, List.of(product)));
		List<CandidateRuleFact> actual = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPoint(facts, null, null, null);
		CandidateEmissionRealization survivor = actual.get(3).allowedEmissionFacts().get(0).realizations().get(0);
		FactorizedSupportClauses remaining = (FactorizedSupportClauses)survivor.supportClauses();
		Assert.assertEquals(1, remaining.size());
		Assert.assertSame(proof, remaining.proofs().get(0));
		Assert.assertSame(retained, remaining.factors().get(0).get(0));
		Assert.assertSame(rhs, remaining.factors().get(1).get(0));
		Assert.assertEquals(0, remaining.materializedClauseCount());
		Assert.assertEquals(0, ((FactorizedSupportClauses)product.supportClauses()).materializedClauseCount());
		CandidateEmissionRealization explicit = new CandidateEmissionRealization(source.key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof), List.of(expired, rhs)),
			new CandidateRealizationSupportClause(List.of(proof), List.of(retained, rhs))));
		List<CandidateRuleFact> explicitFacts = new ArrayList<>(facts);
		explicitFacts.set(3, fact(consumer, LOCAL, List.of(explicit)));
			Assert.assertTrue("factorized survivors equal the explicit deletion fixed point",
				actual.equals(PlacementSupportRelations.pruneUnsupportedRealizationsToFixedPoint(
					explicitFacts, null, null, null)));
		Assert.assertEquals(0, remaining.materializedClauseCount());
	}

	@Test
	public void factorizedMissingSourceCascadesWithoutMaterializingProduct() {
		CandidateRuleKey missing = rule("factorized-cascade-missing");
		CandidateRuleKey middle = rule("factorized-cascade-middle");
		CandidateRuleKey tail = rule("factorized-cascade-tail");
		CandidateEmissionRealization missingRealization = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization middleProduct = CandidateEmissionRealization.factorized(
			CandidateEmissionRealization.local(LOCAL).key(), List.of(), List.of(List.of(
				CandidateRealizationInputBinding.direct(0, ref(missing, missingRealization)))), null, true);
		CandidateEmissionRealization tailProduct = CandidateEmissionRealization.factorized(
			CandidateEmissionRealization.local(LOCAL).key(), List.of(), List.of(List.of(
				CandidateRealizationInputBinding.direct(0, ref(middle, middleProduct)))), null, true);
		FactorizedSupportClauses middleRelation =
			(FactorizedSupportClauses)middleProduct.supportClauses();
		FactorizedSupportClauses tailRelation = (FactorizedSupportClauses)tailProduct.supportClauses();
		List<CandidateRuleFact> facts = List.of(fact(middle, LOCAL, List.of(middleProduct)),
			fact(tail, LOCAL, List.of(tailProduct)));

		List<CandidateRuleFact> result = fixed(facts, null, null, null);

		Assert.assertTrue(result.stream()
			.allMatch(fact -> fact.status() == CandidateEvaluationStatus.PROFILE_ERROR));
		Assert.assertEquals(0, middleRelation.materializedClauseCount());
		Assert.assertEquals(0, tailRelation.materializedClauseCount());
	}

	@Test
	public void factorizedSupportedCycleSurvivesWithoutMaterializingClauses() {
		CandidateRuleKey leftRule = rule("factorized-cycle-left");
		CandidateRuleKey rightRule = rule("factorized-cycle-right");
		CandidateEmissionRealization leftIdentity = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization rightIdentity = CandidateEmissionRealization.local(LOCAL);
		CandidateEmissionRealization left = CandidateEmissionRealization.factorized(
			leftIdentity.key(), List.of(), List.of(List.of(CandidateRealizationInputBinding.direct(
				0, ref(rightRule, rightIdentity)))), null, true);
		CandidateEmissionRealization right = CandidateEmissionRealization.factorized(
			rightIdentity.key(), List.of(), List.of(List.of(CandidateRealizationInputBinding.direct(
				0, ref(leftRule, leftIdentity)))), null, true);
		FactorizedSupportClauses leftRelation = (FactorizedSupportClauses)left.supportClauses();
		FactorizedSupportClauses rightRelation = (FactorizedSupportClauses)right.supportClauses();
		List<CandidateRuleFact> facts = List.of(fact(leftRule, LOCAL, List.of(left)),
			fact(rightRule, LOCAL, List.of(right)));

		List<CandidateRuleFact> result = fixed(facts, null, null, null);

		Assert.assertSame(facts.get(0), result.get(0));
		Assert.assertSame(facts.get(1), result.get(1));
		Assert.assertEquals(0, leftRelation.materializedClauseCount());
		Assert.assertEquals(0, rightRelation.materializedClauseCount());
	}

	@Test
	public void correlatedOwnerChoiceDeletionRemovesEveryAxisBindingAndKeepsLiveChoice()
		throws Exception {
		CandidateRuleKey missing = rule("correlated-missing");
		CandidateRuleKey sourceRule = rule("correlated-source");
		CandidateRuleKey consumer = rule("correlated-consumer");
		CandidateEmissionRealization missingIdentity = durable("correlated-missing-value");
		CandidateEmissionRealization deadTemplate = durable("correlated-dead");
		CandidateEmissionRealization dead = new CandidateEmissionRealization(deadTemplate.key(),
			List.of(clause(CandidateRealizationInputBinding.direct(0,
				ref(missing, missingIdentity)))));
		CandidateEmissionRealization live = durable("correlated-live");
		CandidateRealizationReference deadRef = ref(sourceRule, dead);
		CandidateRealizationReference liveRef = ref(sourceRule, live);
		CandidateEmissionRealization product = CandidateEmissionRealization.factorized(
			CandidateEmissionRealization.local(LOCAL).key(), List.of(), List.of(
				List.of(CandidateRealizationInputBinding.direct(0, deadRef),
					CandidateRealizationInputBinding.direct(0, liveRef)),
				List.of(CandidateRealizationInputBinding.direct(1, deadRef),
					CandidateRealizationInputBinding.direct(1, liveRef))), null, true);
		FactorizedSupportClauses original = (FactorizedSupportClauses)product.supportClauses();
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, ROW, List.of(dead, live)),
			fact(consumer, LOCAL, List.of(product)));

		PlacementSupportRelations.WorklistResult result = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, null, null);
		CandidateEmissionRealization survivor = result.facts().get(1).allowedEmissionFacts()
			.get(0).realizations().get(0);
		FactorizedSupportClauses remaining = (FactorizedSupportClauses)survivor.supportClauses();

		Assert.assertEquals(2, original.size());
		Assert.assertEquals(1, remaining.size());
		Assert.assertEquals(1, remaining.factors().get(0).size());
		Assert.assertEquals(1, remaining.factors().get(1).size());
		Assert.assertSame(liveRef, remaining.factors().get(0).get(0).source());
		Assert.assertSame(liveRef, remaining.factors().get(1).get(0).source());
		Assert.assertTrue("one shared-owner choice is invalidated as one logical clause",
			result.work().invalidatedClauses() >= 1);
		Assert.assertEquals(0, original.materializedClauseCount());
		Assert.assertEquals(0, remaining.materializedClauseCount());
		Assert.assertEquals(repeated(facts, null, null, null), result.facts());
	}

	private static long workMetric(Object work, String name) throws Exception {
		try {
			return ((Number) work.getClass().getDeclaredMethod(name).invoke(work)).longValue();
		}
		catch(NoSuchMethodException missing) {
			Assert.fail("missing work diagnostic: " + name);
			return -1;
		}
	}

	private static void assertSameClosure(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> logical,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		Assert.assertEquals(repeated(facts, actions, logical, requiredWriters),
			fixed(facts, actions, logical, requiredWriters));
	}

	private static List<CandidateRuleFact> fixed(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> logical,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		return PlacementSupportRelations.pruneUnsupportedRealizationsToFixedPoint(
			facts, actions, logical, requiredWriters);
	}

	private static List<CandidateRuleFact> repeated(List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> logical,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters) {
		List<CandidateRuleFact> current = facts;
		while(true) {
			List<CandidateRuleFact> next = PlacementSupportRelations.pruneUnsupportedRealizations(
				current, actions, logical, requiredWriters);
			if(next.equals(current))
				return next;
			current = next;
		}
	}

	private static CandidateEmissionRealization dependent(CandidateRuleKey ignored,
		CandidateRealizationReference source) {
		return CandidateEmissionRealization.local(LOCAL, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, source)));
	}

	private static CandidateRealizationSupportClause clause(CandidateRealizationInputBinding binding) {
		return new CandidateRealizationSupportClause(List.of(), List.of(binding));
	}

	private static CandidateRealizationReference ref(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationReference.of(rule, realization);
	}

	private static CandidateEmissionRealization durable(String id) {
		return CandidateEmissionRealization.durable(ROW, anchor(id), List.of(), List.of());
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(new AnchorPartition(
			"localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey("worklist", "main", List.of("root"), "root", "compiled");
		return new CandidateRuleKey(new CompiledHopKey(
			"worklist", "main", "root", "compiled", region, name, name),
			List.of(CandidateInputState.present(FType.ROW)));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, PlacementEmissionState emission,
		List<CandidateEmissionRealization> realizations) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.FED,
				emission.placementState().output(), emission.placementState().fType(),
				ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(emission, FType.ROW, null, realizations)), "");
	}

	private static NeutralPlacementGraph.RelocationAction action(
		String id, CandidateRuleKey consumer, int position) {
		return action(id, consumer, position, LOCAL.placementState());
	}

	private static NeutralPlacementGraph.RelocationAction action(
		String id, CandidateRuleKey consumer, int position, PlacementState target) {
		ValueVersionKey sourceVersion = new ValueVersionKey("worklist", "source",
			consumer.parentOccurrence().controlRegion(), 0, VersionKind.ORDINARY, List.of());
		RelocationActionKey key = new RelocationActionKey(sourceVersion, target,
			FType.ROW, anchor(id), "root", List.of(consumer.parentOccurrence()));
		ObligationKey obligation = new ObligationKey(consumer.parentOccurrence(), position,
			sourceVersion, target, key, "root");
		return new NeutralPlacementGraph.RelocationAction(key, List.of(obligation));
	}

	private static LogicalTransientInputFact logicalInput(CandidateRuleKey writer,
		CandidateRuleKey reader, List<CandidateEmissionRealization> sources,
		List<CandidateEmissionRealization> readers, int ordinal) {
		ValueVersionKey sourceVersion = version(writer, ordinal);
		ValueVersionKey readerVersion = version(reader, 10);
		PlacementProofKey proof = PlacementAnalysis.transientValueIdentityProof(
			writer.parentOccurrence(), sourceVersion, readerVersion);
		List<TransientPlacementCompatibility> compatibility = new ArrayList<>();
		for(int index = 0; index < sources.size(); index++)
			compatibility.add(new TransientPlacementCompatibility(ref(writer, sources.get(index)),
				ref(reader, readers.get(index)), CandidateInputState.present(FType.ROW),
				CandidateInputState.present(FType.ROW),
				new TransientCompatibilityProof(sources.get(index).anchor(),
					readers.get(index).anchor(), List.of(proof))));
		return new LogicalTransientInputFact(writer.parentOccurrence(), reader.parentOccurrence(),
			0, sourceVersion, readerVersion, compatibility);
	}

	private static ValueVersionKey version(CandidateRuleKey rule, int ordinal) {
		return new ValueVersionKey("worklist", rule.parentOccurrence().emittedHopInstance(),
			rule.parentOccurrence().controlRegion(), ordinal, VersionKind.ORDINARY, List.of());
	}
}
