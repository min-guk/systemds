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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeSparseSkeletonTransferTest {
	@Test
	public void exactClauseLookupPreservesFullNativeAuthorityWithoutMaterializingMembers() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey leftOwner = key("left");
		CompiledHopKey rightOwner = key("right");
		DurableAnchorKey output = anchor("worker1:8001", "output");
		List<DurableAnchorKey> seeds = List.of(anchor("seed-a:9001", "seed-a"),
			anchor("seed-b:9002", "seed-b"), anchor("seed-c:9003", "seed-c"));
		List<List<CandidateRealizationInputBinding>> axes = axes(leftOwner, rightOwner, 16);
		RelationData data = relation(owner, seeds, output, axes);
		var header = data.products().get(2);
		List<CandidateRealizationInputBinding> bindings = header.bindingsAt(header.size() - 1);
		int expected = data.relation().ordinalOfExactAuthorityMember(header, bindings);
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			header.externalSeed(), header.outputWorkerPoolWitness(),
			header.exactPartitionRanges(), bindings);
		PlacementProofKey typed = proof.continuityProofKey(owner);
		CandidateRealizationSupportClause typedClause = clause(typed, bindings, output, true);
		PlacementProofKey untyped = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, typed.authoritySignature());

		Assert.assertEquals(expected, data.relation().ordinalOfExactAuthorityClause(typedClause));
		Assert.assertEquals(expected, data.relation().ordinalOfExactAuthorityClause(
			clause(untyped, bindings, output, true)));
		Assert.assertEquals("exact lookup must not create a current member handle",
			0, data.relation().materializedHandleCount());

		Assert.assertEquals(-1, data.relation().ordinalOfExactAuthorityClause(
			clause(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, owner,
				typed.authoritySignature() + "-different-seed"), bindings, output, true)));
		var changedSeedProof = new NativePlacementContinuity.NativeContinuityProof(
			anchor("seed-c:9003", "different-seed-name"), output, true, bindings);
		Assert.assertEquals(-1, data.relation().ordinalOfExactAuthorityClause(
			clause(changedSeedProof.continuityProofKey(owner), bindings, output, true)));
		Assert.assertEquals(-1, data.relation().ordinalOfExactAuthorityClause(
			clause(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
				foreignEqual(owner), typed.authoritySignature()), bindings, output, true)));
		Assert.assertEquals(-1, data.relation().ordinalOfExactAuthorityClause(
			new CandidateRealizationSupportClause(List.of(typed,
				new PlacementProofKey(PlacementProofKind.CONTROL_FLOW, owner, "extra")),
				bindings, output, true)));
		Assert.assertEquals(-1, data.relation().ordinalOfExactAuthorityClause(
			clause(typed, bindings, anchor("worker2:8002", "changed-witness"), true)));
		Assert.assertEquals(-1, data.relation().ordinalOfExactAuthorityClause(
			clause(typed, bindings, output, false)));

		List<CandidateRealizationInputBinding> foreignBindings = new ArrayList<>(bindings);
		CandidateRealizationInputBinding first = foreignBindings.get(0);
		foreignBindings.set(0, CandidateRealizationInputBinding.direct(first.inputPosition(),
			new CandidateRealizationReference(
				new CandidateRuleKey(foreignEqual(first.source().rule().parentOccurrence()),
					first.source().rule().orderedInputs()), first.source().realization())));
		var foreignProof = new NativePlacementContinuity.NativeContinuityProof(
			header.externalSeed(), header.outputWorkerPoolWitness(),
			header.exactPartitionRanges(), foreignBindings).continuityProofKey(owner);
		Assert.assertEquals(-1, data.relation().ordinalOfExactAuthorityClause(
			clause(foreignProof, foreignBindings, output, true)));

		NativeContinuitySupportClauses unwitnessed = new NativeContinuitySupportClauses(
			owner, header, null, true);
		Assert.assertEquals(-1, unwitnessed.ordinalOfExactAuthorityClause(
			new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
				PlacementProofKind.SHAPE, owner, typed.authoritySignature())), bindings)));
		Assert.assertEquals(0, data.relation().materializedHandleCount());
		Assert.assertEquals(0, unwitnessed.materializedHandleCount());
	}

	@Test
	public void rebuiltNativeFactTransfersOnlyCachedMembersAcrossAllHeaders() throws Exception {
		FixtureAccess fixture = FixtureAccess.create();
		DurableAnchorKey output = anchor("worker1:8001", "output");
		Object left = fixture.source("left", output);
		Object right = fixture.source("right", output);
		Object root = fixture.binary("root", left, right);
		CompiledHopKey leftOwner = fixture.key(left);
		CompiledHopKey rightOwner = fixture.key(right);
		CompiledHopKey rootOwner = fixture.key(root);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		List<DurableAnchorKey> seeds = List.of(anchor("seed-a:9001", "seed-a"),
			anchor("seed-b:9002", "seed-b"), anchor("seed-c:9003", "seed-c"));
		List<List<CandidateRealizationInputBinding>> donorAxes = axes(leftOwner, rightOwner, 16);
		RelationData donor = relation(rootOwner, seeds, output, donorAxes);
		fixture.withClauses(root, inputs, donor.relation());
		CandidateRuleFact donorFact = fixture.fact(root, inputs);

		int firstOrdinal = donor.relation().ordinalOfExactAuthorityMember(
			donor.products().get(0), donor.products().get(0).bindingsAt(0));
		int lateHeaderOrdinal = donor.relation().ordinalOfExactAuthorityMember(
			donor.products().get(2), donor.products().get(2).bindingsAt(
				donor.products().get(2).size() - 1));
		Assert.assertTrue(firstOrdinal >= 0);
		Assert.assertTrue(lateHeaderOrdinal >= 0);
		Assert.assertNotEquals(firstOrdinal, lateHeaderOrdinal);
		CandidateRealizationSupportClause donorFirst = donor.relation().get(firstOrdinal);
		CandidateRealizationSupportClause donorLate = donor.relation().get(lateHeaderOrdinal);
		Assert.assertEquals(new NativePlacementContinuity.NativeContinuityProof(
			seeds.get(2), output, true, donor.products().get(2).bindingsAt(
				donor.products().get(2).size() - 1)).continuityProofKey(rootOwner),
			donorLate.proofDependencies().get(0));

		NativePlacementContinuity parent = fixture.resolver();
		List<?> donorFirstSkeletons = skeletons(
			parent, donorFact, donorFirst, fixture.hop(root), output);
		List<?> donorLateSkeletons = skeletons(
			parent, donorFact, donorLate, fixture.hop(root), output);
		Assert.assertEquals(2, donor.relation().materializedHandleCount());

		List<List<CandidateRealizationInputBinding>> currentAxes = cloneAxes(donorAxes);
		RelationData current = relation(rootOwner, seeds, output, currentAxes);
		CandidateRuleFact currentFact = rebuiltFact(donorFact, current.relation());
		List<CandidateRuleFact> currentFacts = new ArrayList<>(fixture.facts());
		currentFacts.set(currentFacts.indexOf(donorFact), currentFact);
		int currentFirstOrdinal = current.relation().ordinalOfExactAuthorityMember(
			current.products().get(0), current.products().get(0).bindingsAt(0));
		int currentLateOrdinal = current.relation().ordinalOfExactAuthorityMember(
			current.products().get(2), current.products().get(2).bindingsAt(
				current.products().get(2).size() - 1));

		NativePlacementContinuity revised = parent.nextRevision(List.copyOf(currentFacts));
		Assert.assertEquals("revision transfer must materialize only the two cached current members",
			2, current.relation().materializedHandleCount());
		Assert.assertEquals(2, counter(revised, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(0, counter(revised, "dependencySkeletonBuilds"));

		CandidateRealizationSupportClause currentFirst = current.relation().get(currentFirstOrdinal);
		CandidateRealizationSupportClause currentLate = current.relation().get(currentLateOrdinal);
		List<?> currentFirstSkeletons = skeletons(
			revised, currentFact, currentFirst, fixture.hop(root), output);
		List<?> currentLateSkeletons = skeletons(
			revised, currentFact, currentLate, fixture.hop(root), output);
		Assert.assertEquals(0, counter(revised, "dependencySkeletonBuilds"));
		Assert.assertEquals(2, counter(revised, "dependencySkeletonReuses"));
		Assert.assertEquals(donorFirstSkeletons, currentFirstSkeletons);
		Assert.assertEquals(donorLateSkeletons, currentLateSkeletons);

		assertCurrentSourceIdentity(revised, currentFact, currentFirst, currentFirstSkeletons);
		assertCurrentSourceIdentity(revised, currentFact, currentLate, currentLateSkeletons);
		NativePlacementContinuity cold = revised.freshQueryState();
		Assert.assertEquals(currentFirstSkeletons,
			skeletons(cold, currentFact, currentFirst, fixture.hop(root), output));
		Assert.assertEquals(currentLateSkeletons,
			skeletons(cold, currentFact, currentLate, fixture.hop(root), output));
		Assert.assertEquals(2, current.relation().materializedHandleCount());

		NativeContinuitySupportClauses oneHeader = new NativeContinuitySupportClauses(
			rootOwner, current.products().get(0), output, true);
		CandidateRuleFact oneHeaderFact = rebuiltFact(donorFact, oneHeader);
		NativePlacementContinuity headerWithdrawn = parent.nextRevision(
			replace(fixture.facts(), donorFact, oneHeaderFact));
		Assert.assertEquals("a removed seed header cannot carry its cached member",
			1, counter(headerWithdrawn, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(1, oneHeader.materializedHandleCount());
		Assert.assertEquals(-1, oneHeader.ordinalOfExactAuthorityClause(donorLate));

		CandidateRealizationInputBinding removed = donorLate.inputBindings().get(0);
		Assert.assertNotEquals(removed, donorFirst.inputBindings().get(0));
		NativeContinuitySupportClauses restricted = donor.relation()
			.restrictBindings(binding -> !binding.equals(removed)).orElseThrow();
		CandidateRuleFact restrictedFact = rebuiltFact(donorFact, restricted);
		NativePlacementContinuity bindingWithdrawn = parent.nextRevision(
			replace(fixture.facts(), donorFact, restrictedFact));
		Assert.assertEquals("a removed binding cannot carry its cached member",
			1, counter(bindingWithdrawn, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(1, restricted.materializedHandleCount());
		Assert.assertEquals(-1, restricted.ordinalOfExactAuthorityClause(donorLate));
		Assert.assertSame(donorFirst, restricted.get(
			restricted.ordinalOfExactAuthorityClause(donorFirst)));
	}

	@Test
	public void structurallyAmbiguousClausesInOneDonorFactStayColdForSparseNativeTransfer()
		throws Exception {
		FixtureAccess fixture = FixtureAccess.create();
		DurableAnchorKey output = anchor("worker1:8001", "ambiguous-output");
		Object left = fixture.source("left", output);
		Object right = fixture.source("right", output);
		Object root = fixture.binary("root", left, right);
		CompiledHopKey rootOwner = fixture.key(root);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		List<DurableAnchorKey> seeds = List.of(anchor("seed-a:9001", "ambiguous-a"),
			anchor("seed-b:9002", "ambiguous-b"),
			anchor("seed-c:9003", "ambiguous-c"));
		RelationData source = relation(rootOwner, seeds, output,
			axes(fixture.key(left), fixture.key(right), 16));
		fixture.withClauses(root, inputs, source.relation());
		CandidateRuleFact baseFact = fixture.fact(root, inputs);
		NativePlacementContinuity base = fixture.resolver();

		CandidateRealizationSupportClause first = source.relation().get(0);
		CandidateRealizationSupportClause equal = new CandidateRealizationSupportClause(
			first.proofDependencies(), first.inputBindings(), first.nativeWorkerPoolWitness(),
			first.nativeWorkerPoolLayoutExact());
		Assert.assertEquals(first, equal);
		Assert.assertNotSame(first, equal);
		CandidateRuleFact ambiguousFact = twoRealizationFact(baseFact, first, equal);
		List<CandidateRuleFact> donorFacts = replace(fixture.facts(), baseFact, ambiguousFact);
		NativePlacementContinuity donor = base.nextRevision(donorFacts);
		skeletons(donor, ambiguousFact, first, fixture.hop(root), output);
		skeletons(donor, ambiguousFact, equal, fixture.hop(root), output);

		RelationData current = relation(rootOwner, seeds, output,
			cloneAxes(source.products().get(0).axes()));
		CandidateRuleFact currentFact = rebuiltFact(ambiguousFact, current.relation());
		NativePlacementContinuity revised = donor.nextRevision(
			replace(donorFacts, ambiguousFact, currentFact));
		Assert.assertEquals("ambiguous structural donor clauses must not transfer a template",
			0, counter(revised, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals("the sparse lookup may restore the one probed current handle only",
			1, current.relation().materializedHandleCount());

		CandidateRealizationSupportClause selected = current.relation().get(0);
		List<?> actual = skeletons(revised, currentFact, selected, fixture.hop(root), output);
		Assert.assertEquals(1, counter(revised, "dependencySkeletonBuilds"));
		Assert.assertEquals(actual, skeletons(revised.freshQueryState(), currentFact,
			selected, fixture.hop(root), output));

		CandidateRuleFact exactFact = rebuiltFact(ambiguousFact, source.relation());
		NativePlacementContinuity exact = donor.nextRevision(
			replace(donorFacts, ambiguousFact, exactFact));
		Assert.assertEquals("an exact surviving handle wins over structural ambiguity",
			1, counter(exact, "dependencySkeletonTemplatesCarried"));
		Assert.assertSame(first, source.relation().get(0));
		Assert.assertEquals(1, source.relation().materializedHandleCount());
		Assert.assertEquals(actual, skeletons(exact, exactFact, first, fixture.hop(root), output));
		Assert.assertEquals(0, counter(exact, "dependencySkeletonBuilds"));
		Assert.assertEquals(1, counter(exact, "dependencySkeletonReuses"));
	}

	private static void assertCurrentSourceIdentity(NativePlacementContinuity resolver,
		CandidateRuleFact fact, CandidateRealizationSupportClause clause, List<?> skeletons)
		throws Exception {
		List<?> support = clauseSupport(resolver, fact, clause);
		Assert.assertNotNull(support);
		Assert.assertEquals(clause.inputBindings().size(), support.size());
		for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
			CandidateRealizationReference current = binding.source();
			Assert.assertTrue(support.stream().anyMatch(candidate -> candidate == current));
			Assert.assertSame(current, pinned(skeletons,
				current.rule().parentOccurrence()));
		}
	}

	private static CandidateRuleFact rebuiltFact(CandidateRuleFact source,
		NativeContinuitySupportClauses relation) {
		CandidateEmissionFact emission = source.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		CandidateEmissionRealization replacement = new CandidateEmissionRealization(
			realization.key(), relation);
		CandidateEmissionFact replacementEmission = new CandidateEmissionFact(
			emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(),
			List.of(replacement));
		return new CandidateRuleFact(source.key(), source.status(), source.capability(),
			source.shapeProof(), source.profile(), List.of(replacementEmission), source.failureCode());
	}

	private static CandidateRuleFact twoRealizationFact(CandidateRuleFact source,
		CandidateRealizationSupportClause first, CandidateRealizationSupportClause second) {
		CandidateEmissionFact emission = source.allowedEmissionFacts().get(0);
		CandidateEmissionRealization original = emission.realizations().get(0);
		CandidateEmissionRealization left = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "ambiguous-left"), List.of(first));
		CandidateEmissionRealization right = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "ambiguous-right"), List.of(second));
		CandidateEmissionFact replacement = new CandidateEmissionFact(
			emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(),
			List.of(left, right));
		Assert.assertNotEquals(original.key(), left.key());
		return new CandidateRuleFact(source.key(), source.status(), source.capability(),
			source.shapeProof(), source.profile(), List.of(replacement), source.failureCode());
	}

	private static List<CandidateRuleFact> replace(List<CandidateRuleFact> facts,
		CandidateRuleFact oldFact, CandidateRuleFact newFact) {
		List<CandidateRuleFact> replaced = new ArrayList<>(facts);
		replaced.set(replaced.indexOf(oldFact), newFact);
		return List.copyOf(replaced);
	}

	private static RelationData relation(CompiledHopKey owner, List<DurableAnchorKey> seeds,
		DurableAnchorKey output, List<List<CandidateRealizationInputBinding>> axes) {
		List<NativePlacementContinuity.NativeSupportProduct> products = new ArrayList<>();
		NativeContinuitySupportClauses relation = null;
		for(DurableAnchorKey seed : seeds) {
			NativePlacementContinuity.NativeSupportProduct product =
				NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
			Assert.assertNotNull(product);
			products.add(product);
			NativeContinuitySupportClauses next = new NativeContinuitySupportClauses(
				owner, product, output, true);
			relation = relation == null ? next : relation.multiHeaderUnion(next).orElseThrow();
		}
		Assert.assertEquals(3 * 16 * 16, relation.size());
		return new RelationData(relation, List.copyOf(products));
	}

	private static List<List<CandidateRealizationInputBinding>> axes(
		CompiledHopKey leftOwner, CompiledHopKey rightOwner, int options) {
		return List.of(axis(0, leftOwner, "left", options),
			axis(1, rightOwner, "right", options));
	}

	private static List<CandidateRealizationInputBinding> axis(int position,
		CompiledHopKey owner, String prefix, int options) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.FULL)));
		List<CandidateRealizationInputBinding> result = new ArrayList<>();
		for(int option = 0; option < options; option++) {
			CandidateRealizationReference source = new CandidateRealizationReference(rule,
				PlacementRealizationKey.nativeLineage(emission(),
					String.format("%s-option-%02d", prefix, option)));
			result.add(CandidateRealizationInputBinding.direct(position, source));
		}
		return result.stream().sorted(PlacementAnalysis.canonicalComparator()).toList();
	}

	private static List<List<CandidateRealizationInputBinding>> cloneAxes(
		List<List<CandidateRealizationInputBinding>> axes) {
		return axes.stream().map(axis -> axis.stream().map(binding ->
			CandidateRealizationInputBinding.direct(binding.inputPosition(),
				new CandidateRealizationReference(
					binding.source().rule(), binding.source().realization())))
			.toList()).toList();
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
	}

	@SuppressWarnings("unchecked")
	private static List<?> skeletons(NativePlacementContinuity resolver, CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, DurableAnchorKey anchor) throws Exception {
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(resolver, anchor);
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateDependencySkeletons", CandidateRuleFact.class,
			CandidateRealizationSupportClause.class, Hop.class, witness.getClass());
		method.setAccessible(true);
		return (List<?>)method.invoke(resolver, fact, clause, owner, witness);
	}

	private static List<?> clauseSupport(NativePlacementContinuity resolver,
		CandidateRuleFact fact, CandidateRealizationSupportClause clause) throws Exception {
		Field memos = NativePlacementContinuity.class.getDeclaredField("dependencySkeletonMemo");
		memos.setAccessible(true);
		Object factMemo = ((Map<?,?>)memos.get(resolver)).get(fact);
		if(factMemo == null)
			return null;
		Field clauses = factMemo.getClass().getDeclaredField("clauses");
		clauses.setAccessible(true);
		Object clauseMemo = ((Map<?,?>)clauses.get(factMemo)).get(clause);
		if(clauseMemo == null)
			return null;
		Field support = clauseMemo.getClass().getDeclaredField("support");
		support.setAccessible(true);
		return (List<?>)support.get(clauseMemo);
	}

	private static Object pinned(List<?> skeletons, CompiledHopKey owner) throws Exception {
		for(Object skeleton : skeletons) {
			Field key = skeleton.getClass().getDeclaredField("key");
			key.setAccessible(true);
			if(key.get(skeleton) != owner)
				continue;
			Field pinned = skeleton.getClass().getDeclaredField("clausePinned");
			pinned.setAccessible(true);
			return pinned.get(skeleton);
		}
		return null;
	}

	private static long counter(NativePlacementContinuity resolver, String name) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getLong(resolver);
	}

	private static CandidateRealizationSupportClause clause(PlacementProofKey proof,
		List<CandidateRealizationInputBinding> bindings, DurableAnchorKey witness, boolean exact) {
		return new CandidateRealizationSupportClause(List.of(proof), bindings, witness, exact);
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"program", "ns", List.of("root"), "call", "ctx");
		return new CompiledHopKey("program", "ns", "call", "ctx", region, name, name);
	}

	private static CompiledHopKey foreignEqual(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}

	private static DurableAnchorKey anchor(String endpoint, String id) {
		return new DurableAnchorKey(id, FType.FULL,
			List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 2L))));
	}

	private record RelationData(NativeContinuitySupportClauses relation,
		List<NativePlacementContinuity.NativeSupportProduct> products) { }

	private static final class FixtureAccess {
		private final Object fixture;
		private final Class<?> type;
		private final Class<?> refType;

		private FixtureAccess(Object fixture, Class<?> type, Class<?> refType) {
			this.fixture = fixture;
			this.type = type;
			this.refType = refType;
		}

		private static FixtureAccess create() throws Exception {
			Class<?> type = Class.forName(
				NativePlacementContinuityTest.class.getName() + "$Fixture");
			Class<?> ref = Class.forName(
				NativePlacementContinuityTest.class.getName() + "$Ref");
			Constructor<?> constructor = type.getDeclaredConstructor(FType.class);
			constructor.setAccessible(true);
			return new FixtureAccess(constructor.newInstance(FType.FULL), type, ref);
		}

		private Object source(String name, DurableAnchorKey anchor) throws Exception {
			return invoke("source", new Class<?>[] {String.class, DurableAnchorKey.class}, name, anchor);
		}

		private Object binary(String name, Object left, Object right) throws Exception {
			return invoke("binary", new Class<?>[] {String.class, OpOp2.class, refType, refType,
				boolean.class}, name, OpOp2.PLUS, left, right, false);
		}

		private void withClauses(Object owner, List<CandidateInputState> inputs,
			List<CandidateRealizationSupportClause> clauses) throws Exception {
			invoke("withClauses", new Class<?>[] {refType, List.class, List.class},
				owner, inputs, clauses);
		}

		private CandidateRuleFact fact(Object owner, List<CandidateInputState> inputs)
			throws Exception {
			return (CandidateRuleFact)invoke("fact", new Class<?>[] {refType, List.class},
				owner, inputs);
		}

		private NativePlacementContinuity resolver() throws Exception {
			return (NativePlacementContinuity)invoke("resolver", new Class<?>[0]);
		}

		@SuppressWarnings("unchecked")
		private List<CandidateRuleFact> facts() throws Exception {
			Field field = type.getDeclaredField("candidates");
			field.setAccessible(true);
			return List.copyOf((List<CandidateRuleFact>)field.get(fixture));
		}

		private CompiledHopKey key(Object ref) throws Exception {
			return (CompiledHopKey)invokeRef(ref, "key");
		}

		private Hop hop(Object ref) throws Exception {
			return (Hop)invokeRef(ref, "hop");
		}

		private Object invoke(String name, Class<?>[] parameters, Object... arguments)
			throws Exception {
			Method method = type.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method.invoke(fixture, arguments);
		}

		private Object invokeRef(Object ref, String name) throws Exception {
			Method method = refType.getDeclaredMethod(name);
			method.setAccessible(true);
			return method.invoke(ref);
		}
	}
}
