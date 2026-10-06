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
import java.util.Optional;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Safety contract for the realization-level FULL single-partition least fixed point. */
public class PhysicalSinglePartitionProofFixedPointTest {
	private static final PlacementEmissionState FULL = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);

	@Test
	public void groundedCycleInheritsExactEntryCardinality() throws Exception {
		CandidateRuleKey entryRule = rule("entry"), aRule = rule("a"), bRule = rule("b");
		CandidateEmissionRealization entry = durable("entry", false);
		CandidateEmissionRealization aKey = valueMap("a", List.of(unknownClause()));
		CandidateEmissionRealization bKey = valueMap("b", List.of(unknownClause()));
		CandidateEmissionRealization a = valueMap("a", List.of(
			clause(ref(entryRule, entry)), clause(ref(bRule, bKey))));
		CandidateEmissionRealization b = valueMap("b", List.of(clause(ref(aRule, aKey))));
		List<List<CandidateRuleFact>> inventory = inventory(
			fact(entryRule, entry), fact(aRule, a), fact(bRule, b));

		Assert.assertEquals(Optional.of(true), singlePartition(List.of(fact(aRule, a)), inventory));
	}

	@Test
	public void multiPartitionBackedgeDominatesGroundedCycle() throws Exception {
		CandidateRuleKey entryRule = rule("entry"), multiRule = rule("multi");
		CandidateRuleKey aRule = rule("a"), bRule = rule("b");
		CandidateEmissionRealization entry = durable("entry", false);
		CandidateEmissionRealization multi = durable("multi", true);
		CandidateEmissionRealization aKey = valueMap("a", List.of(unknownClause()));
		CandidateEmissionRealization bKey = valueMap("b", List.of(unknownClause()));
		CandidateEmissionRealization a = valueMap("a", List.of(
			clause(ref(entryRule, entry)), clause(ref(bRule, bKey))));
		CandidateEmissionRealization b = valueMap("b", List.of(
			clause(ref(aRule, aKey), ref(multiRule, multi))));
		List<List<CandidateRuleFact>> inventory = inventory(fact(entryRule, entry),
			fact(multiRule, multi), fact(aRule, a), fact(bRule, b));

		Assert.assertEquals(Optional.of(false), singlePartition(List.of(fact(aRule, a)), inventory));
	}

	@Test
	public void unknownSiblingBlocksAnExactRealization() throws Exception {
		CandidateRuleKey owner = rule("owner");
		CandidateEmissionRealization exact = durable("exact", false);
		CandidateEmissionRealization unknown = valueMap("unknown", List.of(unknownClause()));
		CandidateRuleFact fact = fact(owner, exact, unknown);

		Assert.assertEquals(Optional.empty(), singlePartition(List.of(fact), inventory(fact)));
	}

	@Test
	public void missingReferenceIsIgnoredOnlyWhenAnExactClauseIsLive() throws Exception {
		CandidateRuleKey entryRule = rule("entry"), missingRule = rule("missing"), owner = rule("owner");
		CandidateEmissionRealization entry = durable("entry", false);
		CandidateEmissionRealization missing = durable("missing", false);
		CandidateEmissionRealization withExact = valueMap("with-exact", List.of(
			clause(ref(missingRule, missing)), clause(ref(entryRule, entry))));
		CandidateEmissionRealization missingOnly = valueMap("missing-only",
			List.of(clause(ref(missingRule, missing))));
		CandidateRuleFact entryFact = fact(entryRule, entry);

		Assert.assertEquals(Optional.of(true), singlePartition(List.of(fact(owner, withExact)),
			inventory(entryFact, fact(owner, withExact))));
		Assert.assertEquals(Optional.empty(), singlePartition(List.of(fact(owner, missingOnly)),
			inventory(entryFact, fact(owner, missingOnly))));
	}

	@Test
	public void broadeningCurrentInventoryToUnknownCannotReusePriorExactProof() throws Exception {
		CandidateRuleKey owner = rule("owner");
		CandidateEmissionRealization exact = durable("exact", false);
		CandidateEmissionRealization unknown = valueMap("unknown", List.of(unknownClause()));
		CandidateRuleFact exactFact = fact(owner, exact);
		CandidateRuleFact broadenedFact = fact(owner, exact, unknown);

		Assert.assertEquals(Optional.of(true),
			singlePartition(List.of(exactFact), inventory(exactFact)));
		Assert.assertEquals("the second invocation must use only the broadened current inventory",
			Optional.empty(), singlePartition(List.of(broadenedFact), inventory(broadenedFact)));
	}

	@SuppressWarnings("unchecked")
	private static Optional<Boolean> singlePartition(List<CandidateRuleFact> sourceFacts,
		List<List<CandidateRuleFact>> inventory) throws Exception {
		Method fixedPoint = PlacementRelationClosure.class.getDeclaredMethod(
			"exactSinglePartitionRealizationProofs", List.class);
		fixedPoint.setAccessible(true);
		Map<?,?> proofs = (Map<?,?>)fixedPoint.invoke(null, inventory);
		Method projection = PlacementRelationClosure.class.getDeclaredMethod(
			"exactCandidateInputSinglePartition", List.class, Map.class);
		projection.setAccessible(true);
		return (Optional<Boolean>)projection.invoke(null, sourceFacts, proofs);
	}

	@SafeVarargs
	private static List<List<CandidateRuleFact>> inventory(CandidateRuleFact... facts) {
		return java.util.Arrays.stream(facts).map(List::of).toList();
	}

	private static CandidateEmissionRealization valueMap(String id,
		List<CandidateRealizationSupportClause> clauses) {
		return CandidateEmissionRealization.valueMap(FULL, id, clauses);
	}

	private static CandidateRealizationSupportClause unknownClause() {
		return new CandidateRealizationSupportClause(List.of(), List.of());
	}

	private static CandidateRealizationSupportClause clause(CandidateRealizationReference... sources) {
		List<CandidateRealizationInputBinding> bindings = java.util.stream.IntStream.range(0, sources.length)
			.mapToObj(position -> CandidateRealizationInputBinding.direct(position, sources[position])).toList();
		return new CandidateRealizationSupportClause(List.of(), bindings);
	}

	private static CandidateRealizationReference ref(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationReference.of(rule, realization);
	}

	private static CandidateEmissionRealization durable(String id, boolean multiPartition) {
		List<AnchorPartition> partitions = multiPartition
			? List.of(partition("worker-a", 0, 4), partition("worker-b", 4, 8))
			: List.of(partition("worker-a", 0, 8));
		DurableAnchorKey anchor = new DurableAnchorKey(id, FType.FULL, partitions);
		return CandidateEmissionRealization.durable(FULL, anchor, List.of(), List.of());
	}

	private static AnchorPartition partition(String worker, long begin, long end) {
		return new AnchorPartition(worker, List.of(begin, 0L), List.of(end, 3L));
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"single-partition-fixed-point", "main", List.of("main"), "main", "compiled");
		return new CandidateRuleKey(new CompiledHopKey("single-partition-fixed-point", "main",
			"main", "compiled", region, name, name), List.of(CandidateInputState.present(FType.FULL)));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		CandidateEmissionRealization... realizations) {
		CandidateEmissionFact emission = new CandidateEmissionFact(
			FULL, FType.FULL, null, List.of(realizations));
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(emission), "");
	}
}
