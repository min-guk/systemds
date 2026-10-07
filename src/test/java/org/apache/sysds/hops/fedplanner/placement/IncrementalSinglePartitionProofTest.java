/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
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

/** Regression contract for owner-local updates to the single-partition proof index. */
public class IncrementalSinglePartitionProofTest {
	private static final PlacementEmissionState FULL = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
	private static final long RANDOM_SEED = 0x1AC3E5EEDL;

	@Test
	public void removingAndRestoringGroundedCycleRootRetractsAndRestoresProofs() throws Exception {
		CandidateRuleKey rootRule = rule("cycle-root");
		CandidateRuleKey aRule = rule("cycle-a");
		CandidateRuleKey bRule = rule("cycle-b");
		CandidateEmissionRealization root = nativePool("cycle-root", false);
		CandidateEmissionRealization aKey = valueMap("cycle-a", List.of(unknownClause()));
		CandidateEmissionRealization bKey = valueMap("cycle-b", List.of(unknownClause()));
		CandidateEmissionRealization a = valueMap("cycle-a", List.of(
			clause(ref(rootRule, root)), clause(ref(bRule, bKey))));
		CandidateEmissionRealization b = valueMap("cycle-b", List.of(clause(ref(aRule, aKey))));
		List<List<CandidateRuleFact>> inventory = mutableInventory(
			List.of(fact(rootRule, root)), List.of(fact(aRule, a)), List.of(fact(bRule, b)));
		Object index = newIndex(inventory);

		assertMatchesFullRecomputation(index, inventory);
		replace(index, inventory, 0, List.of());
		assertProof(index, ref(aRule, a), "UNAVAILABLE");
		assertProof(index, ref(bRule, b), "UNAVAILABLE");
		assertMatchesFullRecomputation(index, inventory);
		replace(index, inventory, 0, List.of(fact(rootRule, root)));
		assertProof(index, ref(aRule, a), "EXACT");
		assertProof(index, ref(bRule, b), "EXACT");
		assertMatchesFullRecomputation(index, inventory);
	}

	@Test
	public void laterMultiPartitionAndUnknownBackedgesRetractGroundedExactProof() throws Exception {
		CandidateRuleKey rootRule = rule("backedge-root");
		CandidateRuleKey edgeRule = rule("backedge-source");
		CandidateRuleKey loopRule = rule("backedge-loop");
		CandidateEmissionRealization root = nativePool("backedge-root", false);
		CandidateEmissionRealization edgeKey = valueMap("backedge-source", List.of(unknownClause()));
		CandidateEmissionRealization loopKey = valueMap("backedge-loop", List.of(unknownClause()));
		CandidateEmissionRealization edgeExact = valueMap("backedge-source",
			List.of(clause(ref(rootRule, root))));
		CandidateEmissionRealization loop = valueMap("backedge-loop", List.of(
			clause(ref(rootRule, root)), clause(ref(edgeRule, edgeKey), ref(loopRule, loopKey))));
		List<List<CandidateRuleFact>> inventory = mutableInventory(
			List.of(fact(rootRule, root)), List.of(fact(edgeRule, edgeExact)), List.of(fact(loopRule, loop)));
		Object index = newIndex(inventory);
		assertProof(index, ref(loopRule, loop), "EXACT");

		CandidateEmissionRealization multi = nativePool("backedge-multi", true);
		CandidateRuleKey multiRule = rule("backedge-multi");
		CandidateEmissionRealization edgeMulti = valueMap("backedge-source",
			List.of(clause(ref(multiRule, multi))));
		replace(index, inventory, 1, List.of(fact(edgeRule, edgeMulti)));
		replace(index, inventory, 0, List.of(fact(rootRule, root), fact(multiRule, multi)));
		assertProof(index, ref(loopRule, loop), "NON_SINGLE");
		assertMatchesFullRecomputation(index, inventory);

		CandidateEmissionRealization edgeUnknown = valueMap("backedge-source", List.of(unknownClause()));
		replace(index, inventory, 1, List.of(fact(edgeRule, edgeUnknown)));
		assertProof(index, ref(loopRule, loop), "UNKNOWN");
		assertMatchesFullRecomputation(index, inventory);
	}

	@Test
	public void missingReferenceCanAppearDisappearAndSurviveBatchedCommits() throws Exception {
		CandidateRuleKey missingRule = rule("missing-source");
		CandidateRuleKey consumerRule = rule("missing-consumer");
		CandidateEmissionRealization missingKey = valueMap("missing-source", List.of(unknownClause()));
		CandidateEmissionRealization consumer = valueMap("missing-consumer",
			List.of(clause(ref(missingRule, missingKey))));
		CandidateRuleKey seedRule = rule("missing-seed");
		CandidateEmissionRealization seed = nativePool("missing-seed", false);
		CandidateEmissionRealization missingExact = valueMap("missing-source",
			List.of(clause(ref(seedRule, seed))));
		List<List<CandidateRuleFact>> inventory = mutableInventory(
			List.of(fact(seedRule, seed)), List.of(), List.of(fact(consumerRule, consumer)));
		Object index = newIndex(inventory);
		assertProof(index, ref(consumerRule, consumer), "UNAVAILABLE");

		replace(index, inventory, 1, List.of(fact(missingRule, missingExact)));
		assertProof(index, ref(consumerRule, consumer), "EXACT");
		replace(index, inventory, 1, List.of());
		assertProof(index, ref(consumerRule, consumer), "UNAVAILABLE");

		// No proof query between these commits: lazy batching must still see the final authority.
		replace(index, inventory, 0, List.of());
		replace(index, inventory, 1, List.of(fact(missingRule, missingExact)));
		assertMatchesFullRecomputation(index, inventory);
		assertProof(index, ref(consumerRule, consumer), "UNAVAILABLE");
	}

	@Test
	public void equalReferenceWinnerTracksOwnerAndWithinOwnerOrder() throws Exception {
		CandidateRuleKey duplicateRule = rule("duplicate");
		CandidateRuleKey exactRule = rule("duplicate-exact");
		CandidateRuleKey multiRule = rule("duplicate-multi");
		CandidateEmissionRealization exactSeed = nativePool("duplicate-exact", false);
		CandidateEmissionRealization multiSeed = nativePool("duplicate-multi", true);
		CandidateEmissionRealization exact = valueMap("duplicate",
			List.of(clause(ref(exactRule, exactSeed))));
		CandidateEmissionRealization multi = valueMap("duplicate",
			List.of(clause(ref(multiRule, multiSeed))));
		CandidateRealizationReference duplicate = ref(duplicateRule, exact);
		Assert.assertEquals(duplicate, ref(duplicateRule, multi));
		List<List<CandidateRuleFact>> inventory = mutableInventory(
			List.of(fact(exactRule, exactSeed), fact(multiRule, multiSeed), fact(duplicateRule, exact)),
			List.of(fact(duplicateRule, exact), fact(duplicateRule, multi)));
		Object index = newIndex(inventory);

		assertProof(index, duplicate, "NON_SINGLE");
		assertMatchesFullRecomputation(index, inventory);
		replace(index, inventory, 1, List.of());
		assertProof(index, duplicate, "EXACT");
		assertMatchesFullRecomputation(index, inventory);
		replace(index, inventory, 0,
			List.of(fact(exactRule, exactSeed), fact(multiRule, multiSeed), fact(duplicateRule, multi)));
		assertProof(index, duplicate, "NON_SINGLE");
		assertMatchesFullRecomputation(index, inventory);
	}

	@Test
	public void seededRandomOwnerReplacementsAlwaysMatchFreshFullRecomputation() throws Exception {
		Random random = new Random(RANDOM_SEED);
		int ownerCount = 8;
		List<CandidateRuleKey> rules = new ArrayList<>();
		List<CandidateEmissionRealization> keys = new ArrayList<>();
		for(int owner = 0; owner < ownerCount; owner++) {
			rules.add(rule("random-" + owner));
			keys.add(valueMap("random-" + owner, List.of(unknownClause())));
		}
		CandidateRuleKey exactRule = rule("random-exact");
		CandidateRuleKey multiRule = rule("random-multi");
		CandidateEmissionRealization exact = nativePool("random-exact", false);
		CandidateEmissionRealization multi = nativePool("random-multi", true);
		List<List<CandidateRuleFact>> inventory = new ArrayList<>();
		inventory.add(new ArrayList<>(List.of(fact(exactRule, exact))));
		inventory.add(new ArrayList<>(List.of(fact(multiRule, multi))));
		for(int owner = 0; owner < ownerCount; owner++)
			inventory.add(new ArrayList<>());
		Object index = newIndex(inventory);

		for(int step = 0; step < 120; step++) {
			int updates = 1 + random.nextInt(3);
			for(int update = 0; update < updates; update++) {
				int node = random.nextInt(ownerCount);
				List<CandidateRuleFact> replacement = randomFacts(random, node, rules, keys,
					exactRule, exact, multiRule, multi);
				replace(index, inventory, node + 2, replacement);
			}
			Assert.assertEquals("seed=" + RANDOM_SEED + ", step=" + step,
				fullProofs(inventory), incrementalProofs(index));
		}
	}

	private static List<CandidateRuleFact> randomFacts(Random random, int node,
		List<CandidateRuleKey> rules, List<CandidateEmissionRealization> keys,
		CandidateRuleKey exactRule, CandidateEmissionRealization exact,
		CandidateRuleKey multiRule, CandidateEmissionRealization multi) {
		int choice = random.nextInt(8);
		if(choice == 0)
			return List.of();
		CandidateRuleKey rule = rules.get(node);
		CandidateEmissionRealization realization = switch(choice) {
			case 1 -> valueMap("random-" + node, List.of(unknownClause()));
			case 2 -> valueMap("random-" + node, List.of(clause(ref(exactRule, exact))));
			case 3 -> valueMap("random-" + node, List.of(clause(ref(multiRule, multi))));
			default -> {
				int first = random.nextInt(keys.size());
				int second = random.nextInt(keys.size());
				if(second == first)
					second = (second + 1) % keys.size();
				yield valueMap("random-" + node, List.of(
					clause(ref(rules.get(first), keys.get(first))),
					clause(ref(rules.get(second), keys.get(second)))));
			}
		};
		if(choice == 7) {
			CandidateEmissionRealization shadow = valueMap("random-" + node,
				List.of(clause(ref(exactRule, exact))));
			return List.of(fact(rule, shadow), fact(rule, realization));
		}
		return List.of(fact(rule, realization));
	}

	private static Object newIndex(List<List<CandidateRuleFact>> inventory) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$SinglePartitionProofIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(inventory);
	}

	private static void replace(Object index, List<List<CandidateRuleFact>> inventory,
		int owner, List<CandidateRuleFact> facts) throws Exception {
		Method replace = index.getClass().getDeclaredMethod("replaceOwner", int.class, List.class);
		replace.setAccessible(true);
		replace.invoke(index, owner, facts);
		inventory.set(owner, new ArrayList<>(facts));
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRealizationReference,?> incrementalProofs(Object index) throws Exception {
		Method proofs = index.getClass().getDeclaredMethod("proofs");
		proofs.setAccessible(true);
		return (Map<CandidateRealizationReference,?>)proofs.invoke(index);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRealizationReference,?> fullProofs(
		List<List<CandidateRuleFact>> inventory) throws Exception {
		Method full = PlacementRelationClosure.class.getDeclaredMethod(
			"exactSinglePartitionRealizationProofs", List.class);
		full.setAccessible(true);
		return (Map<CandidateRealizationReference,?>)full.invoke(null, inventory);
	}

	private static void assertMatchesFullRecomputation(Object index,
		List<List<CandidateRuleFact>> inventory) throws Exception {
		Assert.assertEquals(fullProofs(inventory), incrementalProofs(index));
	}

	private static void assertProof(Object index, CandidateRealizationReference reference,
		String expected) throws Exception {
		Object proof = incrementalProofs(index).get(reference);
		Assert.assertNotNull("missing proof for " + reference, proof);
		Assert.assertEquals(expected, proof.toString());
	}

	@SafeVarargs
	private static List<List<CandidateRuleFact>> mutableInventory(List<CandidateRuleFact>... owners) {
		List<List<CandidateRuleFact>> inventory = new ArrayList<>();
		for(List<CandidateRuleFact> owner : owners)
			inventory.add(new ArrayList<>(owner));
		return inventory;
	}

	private static CandidateEmissionRealization valueMap(String id,
		List<CandidateRealizationSupportClause> clauses) {
		return CandidateEmissionRealization.valueMap(FULL, id, clauses);
	}

	private static CandidateEmissionRealization nativePool(String id, boolean multiPartition) {
		DurableAnchorKey pool = new DurableAnchorKey(id, FType.FULL, multiPartition
			? List.of(partition("worker-a", 0, 4), partition("worker-b", 4, 8))
			: List.of(partition("worker-a", 0, 8)));
		return CandidateEmissionRealization.durable(FULL, pool, List.of(), List.of());
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

	private static AnchorPartition partition(String worker, long begin, long end) {
		return new AnchorPartition(worker, List.of(begin, 0L), List.of(end, 3L));
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"incremental-single-partition", "main", List.of("main"), "main", "compiled");
		return new CandidateRuleKey(new CompiledHopKey("incremental-single-partition", "main",
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
