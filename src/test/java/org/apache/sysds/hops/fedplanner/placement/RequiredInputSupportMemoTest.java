/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class RequiredInputSupportMemoTest {
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), false);
	private static final PlacementEmissionState ROW = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@After
	public void resetCache() {
		PlacementIdentity.resetNormalizedSignatureCache();
	}

	@Test
	public void warmExactClauseReusesSortedDistinctImmutableList() {
		CandidateRealizationReference first = reference("source-b");
		CandidateRealizationReference second = reference("source-a");
		CandidateRealizationSupportClause clause = clause(first, second, first);

		List<CandidateRealizationReference> expected = clause.inputBindings().stream()
			.map(CandidateRealizationInputBinding::source).distinct().sorted().toList();
		List<CandidateRealizationReference> cold = clause.requiredInputSupport();
		List<CandidateRealizationReference> warm = clause.requiredInputSupport();

		Assert.assertEquals(expected, cold);
		Assert.assertSame("the exact immutable clause must not sort twice", cold, warm);
		Assert.assertThrows(UnsupportedOperationException.class, () -> cold.add(first));
	}

	@Test
	public void equalForeignClauseDoesNotShareExactIdentityEntry() {
		CandidateRealizationReference first = reference("foreign-a");
		CandidateRealizationReference second = reference("foreign-b");
		CandidateRealizationSupportClause left = clause(first, second, first);
		CandidateRealizationSupportClause right = clause(first, second, first);
		Assert.assertEquals(left, right);

		List<CandidateRealizationReference> leftSupport = left.requiredInputSupport();
		List<CandidateRealizationReference> rightSupport = right.requiredInputSupport();
		Assert.assertEquals(leftSupport, rightSupport);
		Assert.assertNotSame(leftSupport, rightSupport);
		Assert.assertSame(leftSupport, left.requiredInputSupport());
		Assert.assertSame(rightSupport, right.requiredInputSupport());
	}

	@Test
	public void resetAndCompilerThreadIsolateNonEmptyMultiSourceLists() throws Exception {
		CandidateRealizationSupportClause clause = clause(
			reference("thread-a"), reference("thread-b"), reference("thread-a"));
		List<CandidateRealizationReference> main = clause.requiredInputSupport();
		PlacementIdentity.resetNormalizedSignatureCache();
		List<CandidateRealizationReference> reset = clause.requiredInputSupport();
		Assert.assertEquals(main, reset);
		Assert.assertNotSame(main, reset);

		AtomicReference<List<CandidateRealizationReference>> otherCold = new AtomicReference<>();
		AtomicReference<List<CandidateRealizationReference>> otherWarm = new AtomicReference<>();
		Thread compiler = new Thread(() -> {
			PlacementIdentity.resetNormalizedSignatureCache();
			otherCold.set(clause.requiredInputSupport());
			otherWarm.set(clause.requiredInputSupport());
			PlacementIdentity.resetNormalizedSignatureCache();
		});
		compiler.start();
		compiler.join();
		Assert.assertEquals(reset, otherCold.get());
		Assert.assertNotSame(reset, otherCold.get());
		Assert.assertSame(otherCold.get(), otherWarm.get());
	}

	@Test
	public void warmSourceListDoesNotFreezeLiveSupportPruning() {
		CandidateRuleKey firstRule = rule("live-a");
		CandidateRuleKey secondRule = rule("live-b");
		CandidateRuleKey consumerRule = rule("consumer");
		CandidateEmissionRealization first = durable("live-a");
		CandidateEmissionRealization second = durable("live-b");
		CandidateRealizationSupportClause support = clause(
			CandidateRealizationReference.of(firstRule, first),
			CandidateRealizationReference.of(secondRule, second),
			CandidateRealizationReference.of(firstRule, first));
		CandidateEmissionRealization consumer = new CandidateEmissionRealization(
			CandidateEmissionRealization.local(LOCAL).key(), List.of(support));
		List<CandidateRuleFact> complete = List.of(fact(firstRule, ROW, first),
			fact(secondRule, ROW, second), fact(consumerRule, LOCAL, consumer));
		List<CandidateRuleFact> missing = List.of(fact(firstRule, ROW, first),
			fact(consumerRule, LOCAL, consumer));

		List<CandidateRealizationReference> warmed = support.requiredInputSupport();
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE,
			status(prune(complete), consumerRule));
		Assert.assertSame(warmed, support.requiredInputSupport());
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR,
			status(prune(missing), consumerRule));

		PlacementIdentity.resetNormalizedSignatureCache();
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE,
			status(prune(complete), consumerRule));
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR,
			status(prune(missing), consumerRule));
	}

	@Test
	public void warmRelocationSupportStillReadsCurrentActionInventory() {
		CandidateRuleKey sourceRule = rule("relocation-source");
		CandidateRuleKey consumerRule = rule("relocation-consumer");
		CandidateEmissionRealization source = durable("relocation-source");
		NeutralPlacementGraph.RelocationAction matching = action(
			"matching-action", consumerRule, 0);
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(List.of(), List.of(
			CandidateRealizationInputBinding.relocation(0,
				CandidateRealizationReference.of(sourceRule, source), matching.key())));
		CandidateEmissionRealization consumer = new CandidateEmissionRealization(
			CandidateEmissionRealization.local(LOCAL).key(), List.of(clause));
		List<CandidateRuleFact> facts = List.of(fact(sourceRule, ROW, source),
			fact(consumerRule, LOCAL, consumer));
		NeutralPlacementGraph.RelocationAction incompatible = action(
			"incompatible-action", rule("foreign-consumer"), 0);

		clause.requiredInputSupport();
		PlacementSupportRelations.WorklistResult supported = warmColdResult(facts,
			Map.of(matching.key(), matching), null, null, clause);
		PlacementSupportRelations.WorklistResult missing = warmColdResult(facts,
			Map.of(), null, null, clause);
		PlacementSupportRelations.WorklistResult incompatibleResult = warmColdResult(facts,
			Map.of(matching.key(), incompatible), null, null, clause);

		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE,
			status(supported.facts(), consumerRule));
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR,
			status(missing.facts(), consumerRule));
		Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR,
			status(incompatibleResult.facts(), consumerRule));
		Assert.assertTrue(missing.work().deletedRealizations() > supported.work().deletedRealizations());
		Assert.assertEquals(missing.work(), incompatibleResult.work());
	}

	@Test
	public void warmClauseStillReadsLogicalCompatibilityAndEveryRequiredWriter() {
		CandidateRuleKey writerA = rule("logical-writer-a");
		CandidateRuleKey writerB = rule("logical-writer-b");
		CandidateRuleKey missingWriter = rule("logical-writer-missing");
		CandidateRuleKey reader = rule("logical-reader");
		CandidateEmissionRealization sourceA = durable("logical-source-a");
		CandidateEmissionRealization sourceB = durable("logical-source-b");
		CandidateRealizationSupportClause clause = clause(
			CandidateRealizationReference.of(writerA, sourceA),
			CandidateRealizationReference.of(writerB, sourceB),
			CandidateRealizationReference.of(writerA, sourceA));
		CandidateEmissionRealization readerBase = durable("logical-reader");
		CandidateEmissionRealization readerRealization = new CandidateEmissionRealization(
			readerBase.key(), List.of(clause));
		CandidateEmissionRealization foreignReader = durable("logical-foreign-reader");
		List<CandidateRuleFact> facts = List.of(fact(writerA, ROW, sourceA),
			fact(writerB, ROW, sourceB), fact(reader, ROW, readerRealization));
		List<LogicalTransientInputFact> compatible = List.of(
			logicalInput(writerA, reader, sourceA, readerRealization, 0),
			logicalInput(writerB, reader, sourceB, readerRealization, 1));
		List<LogicalTransientInputFact> incompatible = List.of(
			logicalInput(writerA, reader, sourceA, readerRealization, 0),
			logicalInput(writerB, reader, sourceB, foreignReader, 1));
		Map<CompiledHopKey,List<CompiledHopKey>> required = Map.of(reader.parentOccurrence(),
			List.of(writerA.parentOccurrence(), writerB.parentOccurrence()));
		Map<CompiledHopKey,List<CompiledHopKey>> requiresMissingWriter = Map.of(reader.parentOccurrence(),
			List.of(writerA.parentOccurrence(), writerB.parentOccurrence(), missingWriter.parentOccurrence()));

		clause.requiredInputSupport();
		PlacementSupportRelations.WorklistResult supported = warmColdResult(
			facts, null, compatible, required, clause);
		PlacementSupportRelations.WorklistResult incompatibleResult = warmColdResult(
			facts, null, incompatible, required, clause);
		PlacementSupportRelations.WorklistResult missingWriterResult = warmColdResult(
			facts, null, compatible, requiresMissingWriter, clause);
		PlacementSupportRelations.WorklistResult missingSourceResult = warmColdResult(
			List.of(fact(writerA, ROW, sourceA), fact(reader, ROW, readerRealization)),
			null, compatible, required, clause);

		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE,
			status(supported.facts(), reader));
		for(PlacementSupportRelations.WorklistResult rejected :
			List.of(incompatibleResult, missingWriterResult, missingSourceResult)) {
			Assert.assertEquals(CandidateEvaluationStatus.PROFILE_ERROR,
				status(rejected.facts(), reader));
			Assert.assertTrue(rejected.work().deletedRealizations()
				> supported.work().deletedRealizations());
		}
	}

	private static CandidateRealizationSupportClause clause(CandidateRealizationReference... sources) {
		java.util.ArrayList<CandidateRealizationInputBinding> bindings = new java.util.ArrayList<>();
		for(int index = 0; index < sources.length; index++)
			bindings.add(CandidateRealizationInputBinding.direct(index, sources[index]));
		return new CandidateRealizationSupportClause(List.of(), bindings);
	}

	private static CandidateRealizationReference reference(String name) {
		CandidateRuleKey rule = rule(name);
		return CandidateRealizationReference.of(rule, durable(name));
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey("required-support", "main",
			List.of("root"), "root", "compiled");
		return new CandidateRuleKey(new CompiledHopKey("required-support", "main", "root",
			"compiled", region, name, name), List.of(CandidateInputState.present(FType.ROW)));
	}

	private static CandidateEmissionRealization durable(String name) {
		DurableAnchorKey anchor = new DurableAnchorKey(name, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
		return CandidateEmissionRealization.durable(ROW, anchor, List.of(), List.of());
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, PlacementEmissionState emission,
		CandidateEmissionRealization realization) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.FED,
				emission.placementState().output(), emission.placementState().fType(),
				ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(emission, FType.ROW, null, List.of(realization))), "");
	}

	private static List<CandidateRuleFact> prune(List<CandidateRuleFact> facts) {
		return PlacementSupportRelations.pruneUnsupportedRealizationsToFixedPointWithWork(
			facts, null, null, null).facts();
	}

	private static PlacementSupportRelations.WorklistResult warmColdResult(
		List<CandidateRuleFact> facts,
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> actions,
		List<LogicalTransientInputFact> logicalInputs,
		Map<CompiledHopKey,List<CompiledHopKey>> requiredWriters,
		CandidateRealizationSupportClause warmedClause) {
		List<CandidateRealizationReference> warmed = warmedClause.requiredInputSupport();
		PlacementSupportRelations.WorklistResult warm = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(
				facts, actions, logicalInputs, requiredWriters);
		Assert.assertSame(warmed, warmedClause.requiredInputSupport());
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementSupportRelations.WorklistResult cold = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(
				facts, actions, logicalInputs, requiredWriters);
		Assert.assertEquals("facts and exact work counters must be cache-independent", cold, warm);
		return warm;
	}

	private static NeutralPlacementGraph.RelocationAction action(
		String name, CandidateRuleKey consumer, int position) {
		ValueVersionKey sourceVersion = new ValueVersionKey("required-support", "source",
			consumer.parentOccurrence().controlRegion(), 0, VersionKind.ORDINARY, List.of());
		RelocationActionKey key = new RelocationActionKey(sourceVersion, LOCAL.placementState(),
			FType.ROW, anchor(name), "root", List.of(consumer.parentOccurrence()));
		ObligationKey obligation = new ObligationKey(consumer.parentOccurrence(), position,
			sourceVersion, LOCAL.placementState(), key, "root");
		return new NeutralPlacementGraph.RelocationAction(key, List.of(obligation));
	}

	private static LogicalTransientInputFact logicalInput(CandidateRuleKey writer,
		CandidateRuleKey reader, CandidateEmissionRealization source,
		CandidateEmissionRealization readerRealization, int ordinal) {
		ValueVersionKey sourceVersion = version(writer, ordinal);
		ValueVersionKey readerVersion = version(reader, 10);
		PlacementProofKey proof = PlacementAnalysis.transientValueIdentityProof(
			writer.parentOccurrence(), sourceVersion, readerVersion);
		TransientPlacementCompatibility compatibility = new TransientPlacementCompatibility(
			CandidateRealizationReference.of(writer, source),
			CandidateRealizationReference.of(reader, readerRealization),
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW),
			new TransientCompatibilityProof(source.anchor(), readerRealization.anchor(), List.of(proof)));
		return new LogicalTransientInputFact(writer.parentOccurrence(), reader.parentOccurrence(),
			0, sourceVersion, readerVersion, List.of(compatibility));
	}

	private static ValueVersionKey version(CandidateRuleKey rule, int ordinal) {
		return new ValueVersionKey("required-support", rule.parentOccurrence().emittedHopInstance(),
			rule.parentOccurrence().controlRegion(), ordinal, VersionKind.ORDINARY, List.of());
	}

	private static DurableAnchorKey anchor(String name) {
		return new DurableAnchorKey(name, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CandidateEvaluationStatus status(List<CandidateRuleFact> facts, CandidateRuleKey key) {
		return facts.stream().filter(fact -> fact.key().equals(key)).findFirst().orElseThrow().status();
	}
}
