/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private static final long RANDOM_SEED = 0x5A17C0DEL;

	private enum SeedKind {
		EXACT,
		NON_SINGLE,
		VALUE_MAP
	}

	private record NodeSpec(SeedKind kind, List<List<Integer>> clauses) {
	}

	private record Possibilities(boolean exact, boolean nonSingle, boolean unknown) {
		private boolean available() {
			return exact || nonSingle || unknown;
		}
	}

	private record GeneratedInventory(List<List<CandidateRuleFact>> inventory,
		List<CandidateRealizationReference> references) {
	}

	@Test
	public void pureUngroundedCycleRemainsUnavailable() throws Exception {
		CandidateRuleKey aRule = rule("a"), bRule = rule("b"), cRule = rule("c");
		CandidateEmissionRealization aKey = valueMap("a", List.of(unknownClause()));
		CandidateEmissionRealization bKey = valueMap("b", List.of(unknownClause()));
		CandidateEmissionRealization cKey = valueMap("c", List.of(unknownClause()));
		CandidateEmissionRealization a = valueMap("a", List.of(clause(ref(bRule, bKey))));
		CandidateEmissionRealization b = valueMap("b", List.of(clause(ref(cRule, cKey))));
		CandidateEmissionRealization c = valueMap("c", List.of(clause(ref(aRule, aKey))));
		List<List<CandidateRuleFact>> inventory = inventory(
			fact(aRule, a), fact(bRule, b), fact(cRule, c));
		Map<CandidateRealizationReference,String> proofs = proofNames(inventory);

		Assert.assertEquals("UNAVAILABLE", proofs.get(ref(aRule, a)));
		Assert.assertEquals("UNAVAILABLE", proofs.get(ref(bRule, b)));
		Assert.assertEquals("UNAVAILABLE", proofs.get(ref(cRule, c)));
		Assert.assertEquals(Optional.empty(), singlePartition(List.of(fact(aRule, a)), inventory));
	}

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
	public void longGroundedCyclePropagatesMultiPartitionBackedgeToEveryNode() throws Exception {
		int length = 48;
		CandidateRuleKey entryRule = rule("long-entry"), multiRule = rule("long-multi");
		CandidateEmissionRealization entry = durable("long-entry", false);
		CandidateEmissionRealization multi = durable("long-multi", true);
		List<CandidateRuleKey> rules = new ArrayList<>();
		List<CandidateEmissionRealization> keys = new ArrayList<>();
		for(int i = 0; i < length; i++) {
			rules.add(rule("long-" + i));
			keys.add(valueMap("long-" + i, List.of(unknownClause())));
		}
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		realizations.add(valueMap("long-0", List.of(
			clause(ref(entryRule, entry)), clause(ref(rules.get(length - 1), keys.get(length - 1))))));
		for(int i = 1; i < length - 1; i++)
			realizations.add(valueMap("long-" + i,
				List.of(clause(ref(rules.get(i - 1), keys.get(i - 1))))));
		realizations.add(valueMap("long-" + (length - 1), List.of(
			clause(ref(rules.get(length - 2), keys.get(length - 2)), ref(multiRule, multi)))));
		List<CandidateRuleFact> facts = new ArrayList<>();
		facts.add(fact(entryRule, entry));
		facts.add(fact(multiRule, multi));
		for(int i = 0; i < length; i++)
			facts.add(fact(rules.get(i), realizations.get(i)));
		List<List<CandidateRuleFact>> inventory = inventory(facts.toArray(CandidateRuleFact[]::new));
		Map<CandidateRealizationReference,String> proofs = proofNames(inventory);

		for(int i = 0; i < length; i++)
			Assert.assertEquals("node " + i, "NON_SINGLE",
				proofs.get(ref(rules.get(i), realizations.get(i))));
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
	public void conjunctiveSourcesAndDuplicateEqualReferencesPreserveThreeBitSemantics() throws Exception {
		CandidateRuleKey exactRule = rule("conjunction-exact");
		CandidateRuleKey multiRule = rule("conjunction-multi");
		CandidateRuleKey unknownRule = rule("conjunction-unknown");
		CandidateEmissionRealization exact = durable("conjunction-exact", false);
		CandidateEmissionRealization multi = durable("conjunction-multi", true);
		CandidateEmissionRealization unknown = valueMap("conjunction-unknown", List.of(unknownClause()));
		CandidateEmissionRealization equalExact = durable("conjunction-exact", false);
		CandidateRuleKey exactOwner = rule("conjunction-owner-exact");
		CandidateRuleKey multiOwner = rule("conjunction-owner-multi");
		CandidateRuleKey unknownOwner = rule("conjunction-owner-unknown");
		CandidateEmissionRealization exactPair = valueMap("conjunction-owner-exact", List.of(clause(
			ref(exactRule, exact), ref(exactRule, equalExact), ref(exactRule, exact))));
		CandidateEmissionRealization withMulti = valueMap("conjunction-owner-multi", List.of(clause(
			ref(exactRule, exact), ref(multiRule, multi), ref(exactRule, equalExact))));
		CandidateEmissionRealization withUnknown = valueMap("conjunction-owner-unknown", List.of(clause(
			ref(exactRule, exact), ref(unknownRule, unknown), ref(exactRule, equalExact))));
		List<List<CandidateRuleFact>> inventory = inventory(
			fact(exactRule, exact), fact(multiRule, multi), fact(unknownRule, unknown),
			fact(exactOwner, exactPair), fact(multiOwner, withMulti), fact(unknownOwner, withUnknown));
		Map<CandidateRealizationReference,String> proofs = proofNames(inventory);

		Assert.assertEquals(ref(exactRule, exact), ref(exactRule, equalExact));
		Assert.assertEquals("EXACT", proofs.get(ref(exactOwner, exactPair)));
		Assert.assertEquals("NON_SINGLE", proofs.get(ref(multiOwner, withMulti)));
		Assert.assertEquals("UNKNOWN", proofs.get(ref(unknownOwner, withUnknown)));
	}

	@Test
	public void randomizedSmallInventoriesMatchIndependentSynchronousOracle() throws Exception {
		Random random = new Random(RANDOM_SEED);
		for(int trial = 0; trial < 250; trial++) {
			int size = 1 + random.nextInt(8);
			List<NodeSpec> specs = randomSpecs(random, size);
			GeneratedInventory generated = generatedInventory("random-" + trial, specs);
			Map<Integer,String> expected = synchronousOracle(specs);
			Map<CandidateRealizationReference,String> actual = proofNames(generated.inventory());
			for(int i = 0; i < size; i++)
				Assert.assertEquals("seed=" + RANDOM_SEED + ", trial=" + trial + ", node=" + i
					+ ", specs=" + specs, expected.get(i), actual.get(generated.references().get(i)));
		}
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

	private static List<NodeSpec> randomSpecs(Random random, int size) {
		List<NodeSpec> specs = new ArrayList<>();
		for(int node = 0; node < size; node++) {
			int kindChoice = random.nextInt(10);
			if(kindChoice < 2) {
				specs.add(new NodeSpec(SeedKind.EXACT, List.of()));
				continue;
			}
			if(kindChoice < 4) {
				specs.add(new NodeSpec(SeedKind.NON_SINGLE, List.of()));
				continue;
			}
			List<List<Integer>> clauses = new ArrayList<>();
			int clauseCount = 1 + random.nextInt(3);
			for(int clause = 0; clause < clauseCount; clause++) {
				List<Integer> sources = new ArrayList<>();
				int sourceCount = random.nextInt(4);
				for(int source = 0; source < sourceCount; source++)
					sources.add(random.nextInt(size + 1));
				while(clauses.contains(sources))
					sources.add((node + clause + sources.size()) % (size + 1));
				clauses.add(List.copyOf(sources));
			}
			specs.add(new NodeSpec(SeedKind.VALUE_MAP, List.copyOf(clauses)));
		}
		return List.copyOf(specs);
	}

	private static GeneratedInventory generatedInventory(String prefix, List<NodeSpec> specs) {
		List<CandidateRuleKey> rules = new ArrayList<>();
		List<CandidateEmissionRealization> keys = new ArrayList<>();
		for(int i = 0; i < specs.size(); i++) {
			rules.add(rule(prefix + "-rule-" + i));
			keys.add(switch(specs.get(i).kind()) {
				case EXACT -> durable(prefix + "-node-" + i, false);
				case NON_SINGLE -> durable(prefix + "-node-" + i, true);
				case VALUE_MAP -> valueMap(prefix + "-node-" + i, List.of(unknownClause()));
			});
		}
		CandidateRuleKey missingRule = rule(prefix + "-missing");
		CandidateEmissionRealization missing = valueMap(prefix + "-missing", List.of(unknownClause()));
		List<CandidateRuleFact> facts = new ArrayList<>();
		List<CandidateRealizationReference> references = new ArrayList<>();
		for(int i = 0; i < specs.size(); i++) {
			NodeSpec spec = specs.get(i);
			CandidateEmissionRealization realization;
			if(spec.kind() == SeedKind.VALUE_MAP) {
				List<CandidateRealizationSupportClause> clauses = spec.clauses().stream().map(sources -> {
					CandidateRealizationReference[] refs = sources.stream().map(source ->
						source >= 0 && source < specs.size()
							? ref(rules.get(source), keys.get(source)) : ref(missingRule, missing))
						.toArray(CandidateRealizationReference[]::new);
					return clause(refs);
				}).toList();
				realization = valueMap(prefix + "-node-" + i, clauses);
			}
			else
				realization = keys.get(i);
			facts.add(fact(rules.get(i), realization));
			references.add(ref(rules.get(i), realization));
		}
		return new GeneratedInventory(inventory(facts.toArray(CandidateRuleFact[]::new)),
			List.copyOf(references));
	}

	private static Map<Integer,String> synchronousOracle(List<NodeSpec> specs) {
		List<Possibilities> states = new ArrayList<>();
		for(int i = 0; i < specs.size(); i++)
			states.add(new Possibilities(false, false, false));
		boolean changed;
		do {
			changed = false;
			List<Possibilities> previous = List.copyOf(states);
			for(int i = 0; i < specs.size(); i++) {
				Possibilities next = oraclePossibilities(specs.get(i), previous);
				if(!next.equals(states.get(i))) {
					states.set(i, next);
					changed = true;
				}
			}
		}
		while(changed);
		Map<Integer,String> result = new HashMap<>();
		for(int i = 0; i < states.size(); i++) {
			Possibilities state = states.get(i);
			result.put(i, state.nonSingle() ? "NON_SINGLE"
				: state.unknown() ? "UNKNOWN" : state.exact() ? "EXACT" : "UNAVAILABLE");
		}
		return result;
	}

	private static Possibilities oraclePossibilities(NodeSpec spec, List<Possibilities> states) {
		if(spec.kind() == SeedKind.EXACT)
			return new Possibilities(true, false, false);
		if(spec.kind() == SeedKind.NON_SINGLE)
			return new Possibilities(false, true, false);
		boolean exact = false;
		boolean nonSingle = false;
		boolean unknown = false;
		for(List<Integer> clause : spec.clauses()) {
			if(clause.isEmpty()) {
				unknown = true;
				continue;
			}
			boolean clauseAvailable = true;
			boolean clauseExact = true;
			boolean clauseNonSingle = false;
			boolean clauseUnknown = false;
			for(int source : clause) {
				if(source < 0 || source >= states.size() || !states.get(source).available()) {
					clauseAvailable = false;
					continue;
				}
				Possibilities sourceState = states.get(source);
				clauseExact &= sourceState.exact();
				clauseNonSingle |= sourceState.nonSingle();
				clauseUnknown |= sourceState.unknown();
			}
			if(clauseAvailable) {
				exact |= clauseExact;
				nonSingle |= clauseNonSingle;
				unknown |= clauseUnknown;
			}
		}
		return new Possibilities(exact, nonSingle, unknown);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRealizationReference,String> proofNames(
		List<List<CandidateRuleFact>> inventory) throws Exception {
		Method fixedPoint = PlacementRelationClosure.class.getDeclaredMethod(
			"exactSinglePartitionRealizationProofs", List.class);
		fixedPoint.setAccessible(true);
		Map<CandidateRealizationReference,?> proofs =
			(Map<CandidateRealizationReference,?>)fixedPoint.invoke(null, inventory);
		Map<CandidateRealizationReference,String> names = new HashMap<>();
		proofs.forEach((reference, proof) -> names.put(reference, proof.toString()));
		return names;
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
