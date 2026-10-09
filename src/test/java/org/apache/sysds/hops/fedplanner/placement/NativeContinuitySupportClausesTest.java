/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeContinuitySupportClausesTest {
	@Test
	public void inverseRankRejectsStructurallyEqualForeignBindingIdentity() {
		CompiledHopKey sourceOwner = key("source");
		var owned = direct(0, "a", sourceOwner);
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(List.of(owned)));
		Assert.assertNotNull(product);
		var foreign = direct(0, "a", cloneKey(sourceOwner));
		Assert.assertEquals(owned, foreign);
		Assert.assertNotSame(owned, foreign);
		Assert.assertEquals(-1, product.ordinalOfBindings(List.of(foreign)));
	}

	@Test
	public void rectangularProductRestoresEveryExactMemberLazilyInCanonicalOrder() {
		CompiledHopKey owner = key("owner");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		CompiledHopKey leftOwner = key("left");
		CompiledHopKey rightOwner = key("right");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(direct(0, "left-a", leftOwner), direct(0, "left-b", leftOwner)),
			List.of(direct(1, "right-a", rightOwner), direct(1, "right-b", rightOwner),
				direct(1, "right-c", rightOwner)));
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		NativeContinuitySupportClauses relation =
			new NativeContinuitySupportClauses(owner, product, output, true);
		Assert.assertEquals(6, relation.size());
		Assert.assertEquals(0, relation.materializedHandleCount());

		List<CandidateRealizationSupportClause> explicit =
			explicitClauses(owner, seed, output, axes);
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++) {
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				relation.get(ordinal).normalizedSignature());
			Assert.assertEquals(ordinal,
				product.ordinalOfBindings(product.bindingsAt(ordinal)));
		}
		Assert.assertEquals(6, relation.materializedHandleCount());
	}

	@Test
	public void selectedMemberNeverBorrowsFirstMembersProofAuthority() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey sourceOwner = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var first = direct(0, "a", sourceOwner);
		var selected = direct(0, "b", sourceOwner);
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(first, selected)));
		var relation = new NativeContinuitySupportClauses(owner, product, output, true);

		CandidateRealizationSupportClause clause = relation.get(1);
		Assert.assertSame(selected.source(), clause.inputBindings().get(0).source());
		Assert.assertTrue(clause.proofDependencies().get(0).authoritySignature()
			.contains(selected.source().normalizedSignature()));
		Assert.assertFalse(clause.proofDependencies().get(0).authoritySignature()
			.contains(first.source().normalizedSignature()));
		Assert.assertEquals(1, relation.materializedHandleCount());
	}

	@Test
	public void factoryRejectsCorrelatedMixedUnorderedAndStagingAxes() {
		CompiledHopKey shared = key("shared");
		CompiledHopKey other = key("other");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", shared);
		var b = direct(0, "b", shared);
		var c = direct(1, "c", shared);
		var d = direct(1, "d", other);

		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of()));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(a, b), List.of(c))));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(b, a), List.of(d))));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(d), List.of(a))));
	}

	@Test
	public void exactAuthorityKeepsEqualButForeignOwnerIdentitiesDistinct() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey foreignOwner = cloneKey(owner);
		CompiledHopKey sourceOwner = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true,
			List.of(List.of(direct(0, "a", sourceOwner))));
		var retained = new NativeContinuitySupportClauses(owner, product, output, true);
		var foreign = new NativeContinuitySupportClauses(foreignOwner, product, output, true);
		Assert.assertEquals(owner, foreignOwner);
		Assert.assertNotSame(owner, foreignOwner);
		Assert.assertFalse(retained.sameExactAuthority(foreign));
	}

	@Test
	public void exactAuthorityKeepsEqualButForeignSourceOwnerIdentitiesDistinct() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey sourceOwner = key("source");
		CompiledHopKey foreignSourceOwner = cloneKey(sourceOwner);
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var retainedProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(direct(0, "a", sourceOwner))));
		var foreignProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(direct(0, "a", foreignSourceOwner))));
		Assert.assertNotNull(retainedProduct);
		Assert.assertNotNull(foreignProduct);
		Assert.assertFalse(new NativeContinuitySupportClauses(owner, retainedProduct, output, true)
			.sameExactAuthority(new NativeContinuitySupportClauses(owner, foreignProduct, output, true)));
	}

	@Test
	public void oversizedOrderedProductFailsBeforeCardinalityOverflow() {
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		for(int position = 0; position < 31; position++) {
			CompiledHopKey sourceOwner = key("source-" + position);
			axes.add(List.of(direct(position, "a", sourceOwner),
				direct(position, "b", sourceOwner)));
		}
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, axes));
	}

	@Test
	public void multiDigitInputPositionsRetainExplicitCanonicalOrder() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey left = key("position-2");
		CompiledHopKey right = key("position-10");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(direct(2, "a", left), direct(2, "b", left)),
			List.of(direct(10, "a", right), direct(10, "b", right)));
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		var relation = new NativeContinuitySupportClauses(owner, product, output, true);
		List<CandidateRealizationSupportClause> explicit =
			explicitClauses(owner, seed, output, axes);
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++)
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				relation.get(ordinal).normalizedSignature());
	}

	@Test
	public void fixedSeedRandomRectanglesMatchIndependentNestedEnumeration() {
		Random random = new Random(0x5eedL);
		for(int iteration = 0; iteration < 32; iteration++) {
			CompiledHopKey owner = key("owner-" + iteration);
			DurableAnchorKey seed = pool("seed-" + iteration);
			DurableAnchorKey output = pool("output-" + iteration);
			List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
			for(int position = 0; position < 1 + random.nextInt(4); position++) {
				CompiledHopKey sourceOwner = key("source-" + iteration + '-' + position);
				List<CandidateRealizationInputBinding> options = new ArrayList<>();
				for(int option = 0; option < 1 + random.nextInt(4); option++)
					options.add(direct(position, "option-" + option + '-'
						+ "x".repeat(1 + random.nextInt(12)), sourceOwner));
				Collections.shuffle(options, random);
				options.sort(PlacementAnalysis.canonicalComparator());
				axes.add(List.copyOf(options));
			}
			var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, true, axes);
			Assert.assertNotNull(product);
			var relation = new NativeContinuitySupportClauses(owner, product, output, true);
			List<CandidateRealizationSupportClause> explicit =
				explicitClauses(owner, seed, output, axes);
			Assert.assertEquals(explicit.size(), relation.size());
			for(int ordinal = 0; ordinal < explicit.size(); ordinal++) {
				Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
					relation.get(ordinal).normalizedSignature());
				Assert.assertEquals(ordinal,
					product.ordinalOfBindings(product.bindingsAt(ordinal)));
			}
		}
	}

	@Test
	public void variableLengthBindingAuthorityMatchesIndependentCanonicalOrder() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey leftOwner = key("left-source");
		CompiledHopKey rightOwner = key("right-source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(direct(0, "left-a", leftOwner), direct(0, "left-b", leftOwner)),
			List.of(direct(1, "a", rightOwner),
				direct(1, "medium-source-lineage", rightOwner),
				direct(1, "a-much-longer-source-lineage-than-the-others", rightOwner))
				.stream().sorted(PlacementAnalysis.canonicalComparator()).toList());
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, axes);
		Assert.assertNotNull(product);
		var relation = new NativeContinuitySupportClauses(owner, product, output, true);
		List<CandidateRealizationSupportClause> explicit = explicitClauses(
			owner, seed, output, axes);
		List<CandidateRealizationSupportClause> rowMajor = enumeratedClauses(
			owner, seed, output, axes);
		Assert.assertEquals(6, explicit.size());
		Assert.assertNotEquals(rowMajor.stream().map(
			CandidateRealizationSupportClause::normalizedSignature).toList(), explicit.stream().map(
				CandidateRealizationSupportClause::normalizedSignature).toList());
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++) {
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				relation.get(ordinal).normalizedSignature());
			Assert.assertEquals(ordinal,
				product.ordinalOfBindings(product.bindingsAt(ordinal)));
		}
	}

	@Test
	public void canonicalLengthStateBudgetFallsBackWithoutEnumeratingMembers() {
		CompiledHopKey sourceOwner = key("source");
		List<CandidateRealizationInputBinding> axis = List.of(
			direct(0, "a", sourceOwner), direct(0, "medium-name", sourceOwner),
			direct(0, "a-very-long-source-lineage", sourceOwner))
			.stream().sorted(PlacementAnalysis.canonicalComparator()).toList();
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(axis), 2));
		Assert.assertNotNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(axis)));
	}

	@Test
	public void uniformLengthFastPathDoesNotSpendSuffixIndexBudget() {
		CompiledHopKey sourceOwner = key("source");
		List<CandidateRealizationInputBinding> axis = List.of(
			direct(0, "a", sourceOwner), direct(0, "b", sourceOwner));
		Assert.assertNotNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(axis), 0, 0));
	}

	@Test
	public void equalLengthOptionsShareOneSuffixTransition() {
		CompiledHopKey sourceOwner = key("source");
		List<CandidateRealizationInputBinding> axis = List.of(
			direct(0, "a", sourceOwner), direct(0, "b", sourceOwner),
			direct(0, "a-much-longer-lineage", sourceOwner))
			.stream().sorted(PlacementAnalysis.canonicalComparator()).toList();
		Assert.assertNotNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(axis), 8, 2));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(axis), 8, 1));
	}

	@Test
	public void suffixTransitionBudgetFallsBackBeforeProductWorkExpands() {
		CompiledHopKey leftOwner = key("left");
		CompiledHopKey rightOwner = key("right");
		List<CandidateRealizationInputBinding> left = List.of(
			direct(0, "a", leftOwner), direct(0, "a-long-left-lineage", leftOwner))
			.stream().sorted(PlacementAnalysis.canonicalComparator()).toList();
		List<CandidateRealizationInputBinding> right = List.of(
			direct(1, "a", rightOwner), direct(1, "medium-right", rightOwner),
			direct(1, "a-much-longer-right-lineage", rightOwner))
			.stream().sorted(PlacementAnalysis.canonicalComparator()).toList();
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(left, right), 32, 8));
		Assert.assertNotNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(left, right), 32, 9));
	}

	@Test
	public void decimalAuthorityLengthPrefixOrderMatchesExplicitReference() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey sourceOwner = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		List<CandidateRealizationInputBinding> axis = List.of(
			direct(0, "a", sourceOwner), direct(0, "x".repeat(10_000), sourceOwner))
			.stream().sorted(PlacementAnalysis.canonicalComparator()).toList();
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(axis));
		Assert.assertNotNull(product);
		var relation = new NativeContinuitySupportClauses(owner, product, output, true);
		List<CandidateRealizationSupportClause> explicit = explicitClauses(
			owner, seed, output, List.of(axis));
		Assert.assertNotEquals(Integer.toString(explicit.get(0).proofDependencies().get(0)
			.authoritySignature().length()).length(), Integer.toString(explicit.get(1)
				.proofDependencies().get(0).authoritySignature().length()).length());
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++)
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				relation.get(ordinal).normalizedSignature());
	}

	@Test
	public void exactRectangleComplementMatchesIndependentCanonicalListAndHash() {
		CompiledHopKey owner = key("conditional-owner");
		CompiledHopKey leftOwner = key("conditional-left");
		CompiledHopKey rightOwner = key("conditional-right");
		DurableAnchorKey seed = pool("conditional-seed");
		DurableAnchorKey output = pool("conditional-output");
		var leftExact = direct(0, "a", leftOwner);
		var leftInexact = direct(0, "left-name-with-variable-width", leftOwner);
		var rightExact = direct(1, "b", rightOwner);
		var rightInexactA = direct(1, "right-short", rightOwner);
		var rightInexactB = direct(1, "right-name-much-longer-than-short", rightOwner);
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(leftExact, leftInexact).stream().sorted().toList(),
			List.of(rightExact, rightInexactA, rightInexactB).stream().sorted().toList());
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, axes);
		var relation = NativeContinuitySupportClauses.exactComplement(owner, base,
			List.of(List.of(leftExact), List.of(rightExact)), output, true);
		Assert.assertNotNull(relation);
		List<CandidateRealizationSupportClause> explicit = enumeratedClauses(
			owner, seed, output, axes).stream().filter(clause ->
				clause.inputBindings().get(0) != leftExact
					|| clause.inputBindings().get(1) != rightExact).sorted(
						PlacementAnalysis.canonicalComparator()).toList();
		Assert.assertEquals(5, explicit.size());
		Assert.assertEquals(explicit.hashCode(), relation.hashCode());
		Assert.assertEquals(0, relation.materializedHandleCount());
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++)
			Assert.assertEquals("sparse transfer uses the retained member's hole-adjusted ordinal",
				ordinal, relation.ordinalOfExactAuthorityClause(explicit.get(ordinal)));
		CandidateRealizationSupportClause excludedClause = enumeratedClauses(owner, seed, output, axes)
			.stream().filter(clause -> clause.inputBindings().get(0) == leftExact
				&& clause.inputBindings().get(1) == rightExact).findFirst().orElseThrow();
		Assert.assertEquals("sparse transfer cannot restore an excluded exact member", -1,
			relation.ordinalOfExactAuthorityClause(excludedClause));
		Assert.assertEquals("authority lookup must not materialize conditional handles", 0,
			relation.materializedHandleCount());
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++) {
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				relation.get(ordinal).normalizedSignature());
			Assert.assertEquals(ordinal,
				relation.product().ordinalOfExactAuthorityBindings(
					relation.get(ordinal).inputBindings()));
			Assert.assertEquals(ordinal, relation.ordinalOfExactAuthorityMember(
				base, relation.get(ordinal).inputBindings()));
		}
		Assert.assertEquals(-1, relation.product().ordinalOfExactAuthorityBindings(
			List.of(leftExact, rightExact)));
		Assert.assertEquals(-1, relation.ordinalOfExactAuthorityMember(
			base, List.of(leftExact, rightExact)));
	}

	@Test
	public void conditionalLengthRanksRejectEveryOutOfBucketIndex() {
		CompiledHopKey owner = key("length-rank-owner");
		CompiledHopKey leftOwner = key("length-rank-left");
		CompiledHopKey rightOwner = key("length-rank-right");
		DurableAnchorKey seed = pool("length-rank-seed");
		DurableAnchorKey output = pool("length-rank-output");
		List<CandidateRealizationInputBinding> left = List.of(
			direct(0, "a", leftOwner),
			direct(0, "left-medium-width", leftOwner),
			direct(0, "left-" + "x".repeat(67), leftOwner)).stream().sorted().toList();
		List<CandidateRealizationInputBinding> right = List.of(
			direct(1, "b", rightOwner),
			direct(1, "right-somewhat-longer", rightOwner),
			direct(1, "right-" + "y".repeat(103), rightOwner)).stream().sorted().toList();
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(left, right));
		Assert.assertNotNull(base);
		var relation = NativeContinuitySupportClauses.exactComplement(owner, base,
			List.of(List.of(left.get(0)), List.of(right.get(0))), output, true);
		Assert.assertNotNull(relation);
		var conditional = relation.product();
		var counts = conditional.bindingLengthCounts();
		Assert.assertTrue("fixture needs several nonempty canonical length buckets: " + counts,
			counts.size() >= 3);

		for(var bucket : counts.entrySet()) {
			int length = bucket.getKey();
			int count = bucket.getValue();
			Assert.assertTrue(count > 0);
			for(int rank = 0; rank < count; rank++) {
				List<CandidateRealizationInputBinding> bindings =
					conditional.bindingsAtLengthRank(length, rank);
				Assert.assertEquals(length, conditional.bindingLength(bindings));
				Assert.assertEquals(rank, conditional.rankWithinBindingLength(bindings));
				Assert.assertTrue(conditional.ordinalOfExactAuthorityBindings(bindings) >= 0);
			}
			Assert.assertThrows(IndexOutOfBoundsException.class,
				() -> conditional.bindingsAtLengthRank(length, -1));
			Assert.assertThrows(IndexOutOfBoundsException.class,
				() -> conditional.bindingsAtLengthRank(length, count));
			Assert.assertThrows(IndexOutOfBoundsException.class,
				() -> conditional.bindingsAtLengthRank(length, Integer.MAX_VALUE));
		}
		Assert.assertThrows(IndexOutOfBoundsException.class,
			() -> conditional.bindingsAtLengthRank(Integer.MIN_VALUE, 0));
		Assert.assertEquals("rank probes must not materialize clause handles",
			0, relation.materializedHandleCount());
	}

	@Test
	public void complementRestrictionCollapsesOrDiesWithoutInventingHoles() {
		CompiledHopKey owner = key("restrict-owner");
		CompiledHopKey leftOwner = key("restrict-left");
		CompiledHopKey rightOwner = key("restrict-right");
		DurableAnchorKey seed = pool("restrict-seed");
		DurableAnchorKey output = pool("restrict-output");
		var leftExact = direct(0, "exact-left", leftOwner);
		var leftInexact = direct(0, "inexact-left", leftOwner);
		var rightExact = direct(1, "exact-right", rightOwner);
		var rightInexact = direct(1, "inexact-right", rightOwner);
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true,
			List.of(List.of(leftExact, leftInexact).stream().sorted().toList(),
				List.of(rightExact, rightInexact).stream().sorted().toList()));
		var relation = NativeContinuitySupportClauses.exactComplement(owner, base,
			List.of(List.of(leftExact), List.of(rightExact)), output, true);
		Assert.assertTrue(relation.conditionalComplement());
		var fullRectangle = relation.restrictBindings(binding -> binding != leftExact).orElseThrow();
		Assert.assertFalse(fullRectangle.conditionalComplement());
		Assert.assertEquals(2, fullRectangle.size());
		Assert.assertTrue(relation.restrictBindings(binding ->
			binding == leftExact || binding == rightExact).isEmpty());
	}

	@Test
	public void complementKeepsDistinctFullSeedsWithIdenticalWorkerGeometry() {
		CompiledHopKey owner = key("seed-identity-owner");
		CompiledHopKey leftOwner = key("seed-identity-left");
		CompiledHopKey rightOwner = key("seed-identity-right");
		var leftExact = direct(0, "exact", leftOwner);
		var leftOther = direct(0, "other", leftOwner);
		var rightExact = direct(1, "exact", rightOwner);
		var rightOther = direct(1, "other", rightOwner);
		var axes = List.of(List.of(leftExact, leftOther).stream().sorted().toList(),
			List.of(rightExact, rightOther).stream().sorted().toList());
		var excluded = List.of(List.of(leftExact), List.of(rightExact));
		DurableAnchorKey firstSeed = pool("seed-a"), secondSeed = pool("seed-b");
		DurableAnchorKey output = pool("seed-output");
		Assert.assertNotEquals(firstSeed, secondSeed);
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(firstSeed, secondSeed));
		var first = NativeContinuitySupportClauses.exactComplement(owner,
			NativePlacementContinuity.NativeSupportProduct.tryCreate(firstSeed, output, true, axes),
			excluded, output, true);
		var second = NativeContinuitySupportClauses.exactComplement(owner,
			NativePlacementContinuity.NativeSupportProduct.tryCreate(secondSeed, output, true, axes),
			excluded, output, true);
		Assert.assertFalse(first.sameExactAuthority(second));
		Assert.assertTrue(first.oneAxisUnion(second).isEmpty());
		Assert.assertEquals(2, first.multiHeaderUnion(second).orElseThrow().headerCount());
		Assert.assertEquals(2, NativeContinuitySupportClauses.unionSameAxesHeaderGroup(
			List.of(first, second)).orElseThrow().headerCount());
		Assert.assertEquals(0, first.materializedHandleCount() + second.materializedHandleCount());
		List<CandidateRealizationSupportClause> expected = new ArrayList<>();
		for(var seed : List.of(firstSeed, secondSeed))
			for(var clause : enumeratedClauses(owner, seed, output, axes))
				if(clause.inputBindings().get(0) != leftExact
					|| clause.inputBindings().get(1) != rightExact)
					expected.add(clause);
		var realizationKey = PlacementRealizationKey.nativeLineage(emission(), "same-seed-output");
		var merged = new PlacementAnalysis.CandidateEmissionFact(emission(), FType.ROW, null, List.of(
			new CandidateEmissionRealization(realizationKey, first),
			new CandidateEmissionRealization(realizationKey, second))).realizations().get(0);
		Assert.assertEquals(6, merged.supportClauses().size());
		Assert.assertEquals(new CandidateEmissionRealization(realizationKey, expected).supportClauses(),
			merged.supportClauses());
		for(var clause : merged.supportClauses()) {
			int firstOrdinal = first.ordinalOfExactAuthorityClause(clause);
			int secondOrdinal = second.ordinalOfExactAuthorityClause(clause);
			Assert.assertTrue("full seed, not worker geometry, chooses exactly one authority",
				(firstOrdinal >= 0) != (secondOrdinal >= 0));
			Assert.assertSame(firstOrdinal >= 0 ? first.get(firstOrdinal) : second.get(secondOrdinal), clause);
		}
	}

	@Test
	public void complementMaskNeverBorrowsEqualDistinctBindingAuthority() {
		CompiledHopKey owner = key("identity-owner");
		CompiledHopKey sourceOwner = key("identity-source");
		DurableAnchorKey seed = pool("identity-seed");
		DurableAnchorKey output = pool("identity-output");
		var ownedExact = direct(0, "same", sourceOwner);
		var ownedInexact = direct(0, "other", sourceOwner);
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true,
			List.of(List.of(ownedInexact, ownedExact).stream().sorted().toList()));
		var equalDistinct = direct(0, "same", sourceOwner);
		Assert.assertEquals(ownedExact, equalDistinct);
		Assert.assertNotSame(ownedExact, equalDistinct);
		Assert.assertNull(NativeContinuitySupportClauses.exactComplement(owner, base,
			List.of(List.of(equalDistinct)), output, true));

		var first = NativeContinuitySupportClauses.exactComplement(owner, base,
			List.of(List.of(ownedExact)), output, true);
		var secondExact = direct(0, "same", sourceOwner);
		var secondInexact = direct(0, "other", sourceOwner);
		var secondBase = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true,
			List.of(List.of(secondInexact, secondExact).stream().sorted().toList()));
		var second = NativeContinuitySupportClauses.exactComplement(owner, secondBase,
			List.of(List.of(secondExact)), output, true);
		Assert.assertNotNull(first);
		Assert.assertNotNull(second);
		Assert.assertFalse(first.sameExactAuthority(second));
		// Each exclusion still belongs to its own base. Union can map equal choices
		// by exact source owner and retain the first relation without borrowing a mask.
		Assert.assertSame(first, first.oneAxisUnion(second).orElseThrow());
		Assert.assertEquals(0, first.materializedHandleCount() + second.materializedHandleCount());
	}

	@Test
	public void firstCanonicalHoleAndSeededComplementsPreserveEveryRankAndHash() {
		Random random = new Random(0xc0ffeeL);
		for(int iteration = 0; iteration < 24; iteration++) {
			CompiledHopKey owner = key("random-complement-owner-" + iteration);
			DurableAnchorKey seed = pool("random-complement-seed-" + iteration);
			DurableAnchorKey output = pool("random-complement-output-" + iteration);
			List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
			List<List<CandidateRealizationInputBinding>> exact = new ArrayList<>();
			for(int axis = 0; axis < 2 + random.nextInt(3); axis++) {
				CompiledHopKey sourceOwner = key("random-complement-source-" + iteration + '-' + axis);
				List<CandidateRealizationInputBinding> options = new ArrayList<>();
				for(int option = 0; option < 2 + random.nextInt(3); option++)
					options.add(direct(axis, option == 0 ? "a" : option == 1
						? "emoji-\ud83d\ude42-" + "x".repeat(9 + axis)
						: "long-" + "z".repeat(90 + option), sourceOwner));
				options.sort(PlacementAnalysis.canonicalComparator());
				axes.add(List.copyOf(options));
				exact.add(List.of(options.get(0)));
			}
			var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
			var relation = NativeContinuitySupportClauses.exactComplement(
				owner, base, exact, output, true);
			Assert.assertNotNull(relation);
			List<CandidateRealizationSupportClause> explicit = enumeratedClauses(owner,
				seed, output, axes).stream().filter(clause -> {
					for(int axis = 0; axis < exact.size(); axis++)
						if(clause.inputBindings().get(axis) != exact.get(axis).get(0))
							return true;
					return false;
				}).sorted(PlacementAnalysis.canonicalComparator()).toList();
			Assert.assertEquals(explicit.hashCode(), relation.hashCode());
			for(int ordinal = 0; ordinal < explicit.size(); ordinal++) {
				Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
					relation.get(ordinal).normalizedSignature());
				Assert.assertEquals(ordinal, relation.product().ordinalOfExactAuthorityBindings(
					relation.get(ordinal).inputBindings()));
			}
			Assert.assertEquals(-1, relation.product().ordinalOfExactAuthorityBindings(
				exact.stream().map(axis -> axis.get(0)).toList()));
		}
	}

	@Test
	public void complementCombinedIndexBudgetFallsBackBeforeAdmission() {
		CompiledHopKey left = key("budget-left");
		CompiledHopKey right = key("budget-right");
		var leftExact = direct(0, "a", left);
		var rightExact = direct(1, "a", right);
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("budget-seed"), pool("budget-output"), true, List.of(
				List.of(leftExact, direct(0, "b", left)),
				List.of(rightExact, direct(1, "b", right))));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreateExactComplement(
			base, List.of(List.of(leftExact), List.of(rightExact)), 8, 9));
		Assert.assertNotNull(NativePlacementContinuity.NativeSupportProduct.tryCreateExactComplement(
			base, List.of(List.of(leftExact), List.of(rightExact)), 9, 10));
	}

	private static List<CandidateRealizationSupportClause> explicitClauses(
		CompiledHopKey owner, DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		List<CandidateRealizationSupportClause> explicit = enumeratedClauses(
			owner, seed, output, axes);
		explicit.sort(PlacementAnalysis.canonicalComparator());
		return explicit;
	}

	private static List<CandidateRealizationSupportClause> enumeratedClauses(
		CompiledHopKey owner, DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>();
		enumerateExplicit(owner, seed, output, axes, 0, new ArrayList<>(), explicit);
		return explicit;
	}

	private static void enumerateExplicit(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey output, List<List<CandidateRealizationInputBinding>> axes,
		int axis, List<CandidateRealizationInputBinding> selected,
		List<CandidateRealizationSupportClause> explicit) {
		if(axis < axes.size()) {
			for(CandidateRealizationInputBinding option : axes.get(axis)) {
				selected.add(option);
				enumerateExplicit(owner, seed, output, axes, axis + 1, selected, explicit);
				selected.remove(selected.size() - 1);
			}
			return;
		}
		List<CandidateRealizationInputBinding> bindings =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				List.copyOf(selected), "independent explicit binding");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, bindings).continuityProofKey(owner);
		explicit.add(new CandidateRealizationSupportClause(
			List.of(proof), bindings, output, true));
	}

	@Test
	public void candidateConstructionAndMetadataDoNotMaterializeMembers() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey sourceOwner = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true,
			List.of(List.of(direct(0, "a", sourceOwner), direct(0, "b", sourceOwner))));
		var clauses = new NativeContinuitySupportClauses(owner, product, output, true);
		PlacementEmissionState emission = emission();
		var realization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission, "native-product"), clauses);
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(2,
			realization.nativeContinuitySupportProduct().orElseThrow().logicalClauseCount());
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
	}

	@Test
	public void dynamicLayoutMetadataDoesNotEnumerateNativeProductMembers() {
		CompiledHopKey owner = key("metadata-owner");
		CompiledHopKey left = key("metadata-left");
		CompiledHopKey right = key("metadata-right");
		DurableAnchorKey seed = pool("metadata-seed");
		DurableAnchorKey output = pool("metadata-output");
		var axes = List.of(List.of(direct(0, "a", left), direct(0, "b", left)),
			List.of(direct(1, "a", right), direct(1, "b", right)));
		for(boolean proofExact : new boolean[] {true, false}) {
			var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, proofExact, axes);
			Assert.assertNotNull(product);
			for(boolean clauseExact : new boolean[] {true, false}) {
				var measured = new NativeContinuitySupportClauses(owner, product, output, clauseExact);
				var oracle = new NativeContinuitySupportClauses(owner, product, output, clauseExact);
				List<CandidateRealizationSupportClause> explicit = new ArrayList<>(oracle);
				Assert.assertEquals(!clauseExact, NativePlacementContinuity.hasDynamicNativeLayout(explicit));
				Assert.assertEquals(!clauseExact, NativePlacementContinuity.hasDynamicNativeLayout(measured));
				Assert.assertEquals("constant clause metadata must not read a product member",
					0, measured.materializedHandleCount());
			}
			var absentWitness = new NativeContinuitySupportClauses(owner, product, null, true);
			Assert.assertFalse(NativePlacementContinuity.hasDynamicNativeLayout(absentWitness));
			Assert.assertEquals(0, absentWitness.materializedHandleCount());
		}
	}

	private static CandidateRealizationInputBinding direct(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference reference = new CandidateRealizationReference(rule,
			PlacementRealizationKey.nativeLineage(emission(), "source-" + name));
		return CandidateRealizationInputBinding.direct(position, reference);
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	}

	private static DurableAnchorKey pool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"native-product", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-product", "main", name, "compiled",
			region, name, name);
	}

	private static CompiledHopKey cloneKey(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}
}
