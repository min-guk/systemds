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
	public void cascadingDeletionMatchesRepeatedFullPasses() {
		CandidateRuleKey a = rule("cascade-a"), b = rule("cascade-b"), c = rule("cascade-c");
		CandidateEmissionRealization invalid = CandidateEmissionRealization.nativeLineage(
			ROW, "missing-worker-pool", List.of(), List.of());
		CandidateEmissionRealization rb = dependent(b, ref(a, invalid));
		CandidateEmissionRealization rc = dependent(c, ref(b, rb));
		List<CandidateRuleFact> facts = List.of(fact(a, ROW, List.of(invalid)),
			fact(b, LOCAL, List.of(rb)), fact(c, LOCAL, List.of(rc)));

		assertSameClosure(facts, null, null, null);
		Assert.assertTrue(fixed(facts, null, null, null).stream()
			.allMatch(fact -> fact.status() == CandidateEvaluationStatus.PROFILE_ERROR));
	}

	@Test
	public void supportedCycleSurvivesAsGreatestDeletionFixedPoint() {
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
	}

	@Test
	public void expiredOrSiblingAndActionDoNotRemoveLiveAlternative() {
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
	}

	@Test
	public void everyReachingWriterRemainsMandatory() {
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
	}

	@Test
	public void duplicateReferenceStaysLiveUntilItsLastSlotIsDeleted() {
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
	public void workCountersAreDeterministicAndDoNotChangeTheResult() {
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
		Assert.assertEquals(1, first.work().reverseIncidences());
		Assert.assertEquals(0, first.work().deletedRealizations());
		Assert.assertEquals(facts, first.facts());
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
		ValueVersionKey sourceVersion = new ValueVersionKey("worklist", "source",
			consumer.parentOccurrence().controlRegion(), 0, VersionKind.ORDINARY, List.of());
		RelocationActionKey key = new RelocationActionKey(sourceVersion, LOCAL.placementState(),
			FType.ROW, anchor(id), "root", List.of(consumer.parentOccurrence()));
		ObligationKey obligation = new ObligationKey(consumer.parentOccurrence(), position,
			sourceVersion, LOCAL.placementState(), key, "root");
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
