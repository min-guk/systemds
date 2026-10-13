/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeConditionalSupportUnionTest {
	@Test
	public void exactOrdinaryMaskAndItsComplementRestoreTheBaseWithDonorAuthority() {
		Fixture fixture = fixture("ordinary-complement", 2, 3);
		List<List<CandidateRealizationInputBinding>> exactAxes = List.of(
			List.of(fixture.axes.get(0).get(0)),
			List.of(fixture.axes.get(1).get(1)));
		var retainedProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			fixture.seed, fixture.output, true, exactAxes);
		var retained = new NativeContinuitySupportClauses(fixture.owner,
			retainedProduct, fixture.output, true);
		var complement = conditional(fixture, exactAxes);
		var retainedClause = retained.get(0);
		var complementClauses = new java.util.IdentityHashMap<
			CandidateRealizationSupportClause,Boolean>();
		for(int ordinal = 0; ordinal < complement.size(); ordinal++)
			complementClauses.put(complement.get(ordinal), Boolean.TRUE);

		var union = retained.oneAxisUnion(complement).orElseThrow();

		Assert.assertFalse(union.conditionalComplement());
		Assert.assertEquals(fixture.base.size(), union.size());
		Assert.assertEquals(new ArrayList<>(union).hashCode(), union.hashCode());
		Assert.assertEquals(signatures(new NativeContinuitySupportClauses(fixture.owner,
			fixture.base, fixture.output, true)), signatures(union));
		for(int ordinal = 0; ordinal < union.size(); ordinal++) {
			CandidateRealizationSupportClause clause = union.get(ordinal);
			if(retainedProduct.ordinalOfExactAuthorityBindings(clause.inputBindings()) >= 0)
				Assert.assertSame(retainedClause, clause);
			else
				Assert.assertTrue("complement members retain their exact donor object",
					complementClauses.containsKey(clause));
		}
		Assert.assertTrue("reverse order remains conservative",
			complement.oneAxisUnion(retained).isEmpty());

		List<List<CandidateRealizationInputBinding>> clonedExact = cloneAxes(exactAxes, false);
		var equalDistinct = new NativeContinuitySupportClauses(fixture.owner,
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				fixture.seed, fixture.output, true, clonedExact), fixture.output, true);
		var rebuiltUnion = equalDistinct.oneAxisUnion(complement).orElseThrow();
		List<CandidateRealizationInputBinding> rebuiltOwned = List.of(
			fixture.axes.get(0).get(0), fixture.axes.get(1).get(1));
		Assert.assertSame("rebuilt binding wrappers retain their exact source authority",
			equalDistinct.get(0), rebuiltUnion.get(
				rebuiltUnion.product().ordinalOfExactAuthorityBindings(rebuiltOwned)));
		List<List<CandidateRealizationInputBinding>> foreignExact = cloneAxes(exactAxes, true);
		var foreign = new NativeContinuitySupportClauses(fixture.owner,
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				fixture.seed, fixture.output, true, foreignExact), fixture.output, true);
		Assert.assertTrue("structurally equal foreign owners cannot replace exact authority",
			foreign.oneAxisUnion(complement).isEmpty());
		var partial = new NativeContinuitySupportClauses(fixture.owner,
			NativePlacementContinuity.NativeSupportProduct.tryCreate(fixture.seed,
				fixture.output, true, List.of(fixture.axes.get(0), exactAxes.get(1))),
			fixture.output, true);
		Assert.assertTrue(partial.oneAxisUnion(complement).isEmpty());
	}

	@Test
	public void rebuiltBindingsKeepFirstDonorAndRightOnlyOwnedObjects() {
		Fixture fixture = fixture("rebuilt", 3, 3);
		List<List<CandidateRealizationInputBinding>> clonedAxes = cloneAxes(fixture.axes, false);
		var clonedBase = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			fixture.seed, fixture.output, true, clonedAxes);
		var left = conditional(fixture, List.of(
			fixture.axes.get(0).subList(0, 2), fixture.axes.get(1).subList(0, 2)));
		var right = NativeContinuitySupportClauses.exactComplement(fixture.owner, clonedBase,
			List.of(clonedAxes.get(0).subList(1, 3), clonedAxes.get(1).subList(1, 3)),
			fixture.output, true);
		var union = left.oneAxisUnion(right).orElseThrow();
		Assert.assertEquals(8, union.size());
		Assert.assertEquals(0, left.materializedHandleCount());
		Assert.assertEquals(0, right.materializedHandleCount());
		Assert.assertEquals(0, union.materializedHandleCount());
		List<CandidateRealizationInputBinding> overlap = List.of(
			fixture.axes.get(0).get(0), fixture.axes.get(1).get(2));
		List<CandidateRealizationInputBinding> rightOnly = List.of(
			fixture.axes.get(0).get(0), fixture.axes.get(1).get(0));
		var retainedLeft = left.get(left.product().ordinalOfExactAuthorityBindings(overlap));
		var retainedRight = right.get(right.product().ordinalOfExactAuthorityBindings(rightOnly));
		Assert.assertSame(retainedLeft, union.get(union.product().ordinalOfExactAuthorityBindings(overlap)));
		Assert.assertSame(retainedRight, union.get(union.product().ordinalOfExactAuthorityBindings(rightOnly)));
		Assert.assertSame(clonedAxes.get(0).get(0), retainedRight.inputBindings().get(0));
		Assert.assertNotSame(rightOnly.get(0), retainedRight.inputBindings().get(0));
		Assert.assertNotSame(rightOnly.get(0).source(), retainedRight.inputBindings().get(0).source());
		Assert.assertEquals(explicitUnion(fixture, left, right), signatures(union));
		Assert.assertEquals(new ArrayList<>(union).hashCode(), union.hashCode());
		var restricted = union.restrictBindings(binding -> binding.inputPosition() != 0
			|| binding == fixture.axes.get(0).get(0)).orElseThrow();
		Assert.assertEquals(3, restricted.size());
		Assert.assertEquals(0, restricted.materializedHandleCount());
		Assert.assertSame(retainedLeft, restricted.get(restricted.product().ordinalOfExactAuthorityBindings(overlap)));
		Assert.assertSame(retainedRight, restricted.get(restricted.product().ordinalOfExactAuthorityBindings(rightOnly)));
		Assert.assertTrue(union.restrictBindings(binding -> binding.inputPosition() != 0
			|| binding == clonedAxes.get(0).get(0)).isEmpty());
	}

	@Test
	public void rebuiltBindingMasksMatchExplicitUnionAcrossFixedSeedDomains() {
		Random random = new Random(0xaba51cL);
		for(int trial = 0; trial < 40; trial++) {
			Fixture fixture = fixture("rebuilt-random-" + trial, 2 + random.nextInt(3), 2 + random.nextInt(3));
			List<List<CandidateRealizationInputBinding>> clonedAxes = cloneAxes(fixture.axes, false);
			var clonedBase = NativePlacementContinuity.NativeSupportProduct.tryCreate(
				fixture.seed, fixture.output, true, clonedAxes);
			var rightMask = randomMask(fixture, random);
			List<List<CandidateRealizationInputBinding>> mapped = new ArrayList<>();
			for(int axis = 0; axis < rightMask.size(); axis++) {
				int at = axis;
				mapped.add(rightMask.get(axis).stream().map(binding ->
					clonedAxes.get(at).get(fixture.axes.get(at).indexOf(binding))).toList());
			}
			var left = conditional(fixture, randomMask(fixture, random));
			var right = NativeContinuitySupportClauses.exactComplement(fixture.owner, clonedBase,
				mapped, fixture.output, true);
			var union = left.oneAxisUnion(right).orElseThrow();
			Assert.assertEquals(explicitUnion(fixture, left, right), signatures(union));
			for(int index = 0; index < union.size(); index++) {
				var member = union.get(index);
				int leftIndex = left.product().ordinalOfExactAuthorityBindings(member.inputBindings());
				var donor = leftIndex >= 0 ? left.get(leftIndex)
					: right.get(right.product().ordinalOfExactAuthorityBindings(member.inputBindings()));
				Assert.assertSame(donor, member);
			}
		}
	}

	@Test
	public void structurallyEqualForeignOwnerStillFailsWithoutExpansion() {
		Fixture fixture = fixture("foreign-rebuilt", 3, 3);
		var clonedAxes = cloneAxes(fixture.axes, true);
		var clonedBase = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			fixture.seed, fixture.output, true, clonedAxes);
		var left = conditional(fixture, List.of(
			fixture.axes.get(0).subList(0, 2), fixture.axes.get(1).subList(0, 2)));
		var foreign = NativeContinuitySupportClauses.exactComplement(fixture.owner, clonedBase,
			List.of(clonedAxes.get(0).subList(1, 3), clonedAxes.get(1).subList(1, 3)), fixture.output, true);
		Assert.assertEquals(fixture.axes, clonedAxes);
		Assert.assertTrue(left.oneAxisUnion(foreign).isEmpty());
		Assert.assertEquals(0, left.materializedHandleCount());
		Assert.assertEquals(0, foreign.materializedHandleCount());
	}

	private static List<List<CandidateRealizationInputBinding>> cloneAxes(
		List<List<CandidateRealizationInputBinding>> axes, boolean foreignOwners) {
		List<List<CandidateRealizationInputBinding>> cloned = new ArrayList<>();
		for(var axis : axes) {
			CompiledHopKey original = axis.get(0).source().rule().parentOccurrence();
			CompiledHopKey owner = foreignOwners ? new CompiledHopKey(original.programFingerprint(),
				original.functionNamespace(), original.callSitePath(), original.recompileContext(),
				original.controlRegion(), original.emittedHopInstance(), original.canonicalSourceOrigin()) : original;
			cloned.add(axis.stream().map(binding -> CandidateRealizationInputBinding.direct(
				binding.inputPosition(), new CandidateRealizationReference(new CandidateRuleKey(owner,
					binding.source().rule().orderedInputs()), binding.source().realization()))).toList());
		}
		return List.copyOf(cloned);
	}

	@Test
	public void threeWayConditionalGroupFoldsInEncounterOrder() {
		Fixture fixture = fixture("three-way", 4, 4);
		var first = conditional(fixture, List.of(
			fixture.axes.get(0).subList(0, 3), fixture.axes.get(1).subList(0, 3)));
		var second = conditional(fixture, List.of(
			fixture.axes.get(0).subList(1, 4), fixture.axes.get(1).subList(1, 4)));
		var third = conditional(fixture, List.of(
			fixture.axes.get(0).subList(2, 4), fixture.axes.get(1).subList(2, 4)));
		var union = NativeContinuitySupportClauses.unionSameAxesHeaderGroup(
			List.of(first, second, third)).orElseThrow();
		Assert.assertEquals(15, union.size());
		Assert.assertEquals(3, union.authorityDonorCount());
		Assert.assertEquals(0, union.materializedHandleCount());

		List<CandidateRealizationInputBinding> firstOwned = List.of(
			fixture.axes.get(0).get(0), fixture.axes.get(1).get(3));
		List<CandidateRealizationInputBinding> secondOwned = List.of(
			fixture.axes.get(0).get(0), fixture.axes.get(1).get(0));
		List<CandidateRealizationInputBinding> thirdOwned = List.of(
			fixture.axes.get(0).get(1), fixture.axes.get(1).get(1));
		Assert.assertSame(first.get(first.product().ordinalOfExactAuthorityBindings(firstOwned)),
			union.get(union.product().ordinalOfExactAuthorityBindings(firstOwned)));
		Assert.assertSame(second.get(second.product().ordinalOfExactAuthorityBindings(secondOwned)),
			union.get(union.product().ordinalOfExactAuthorityBindings(secondOwned)));
		Assert.assertSame(third.get(third.product().ordinalOfExactAuthorityBindings(thirdOwned)),
			union.get(union.product().ordinalOfExactAuthorityBindings(thirdOwned)));
	}

	@Test
	public void threeWayGroupFallsBackWhenIntermediateUnionBecomesOrdinary() {
		Fixture fixture = fixture("three-way-ordinary", 2, 3);
		var first = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(0)), List.of(fixture.axes.get(1).get(0))));
		var second = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(1)), List.of(fixture.axes.get(1).get(1))));
		var third = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(0)), List.of(fixture.axes.get(1).get(2))));
		Assert.assertTrue(NativeContinuitySupportClauses.unionSameAxesHeaderGroup(
			List.of(first, second, third)).isEmpty());
		Assert.assertEquals(0, first.materializedHandleCount());
		Assert.assertEquals(0, second.materializedHandleCount());
		Assert.assertEquals(0, third.materializedHandleCount());
	}

	@Test
	public void candidateEmissionMergeKeepsConditionalUnionLazy() {
		Fixture fixture = fixture("emission", 3, 3);
		var left = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(0), fixture.axes.get(0).get(1)),
			List.of(fixture.axes.get(1).get(0))));
		var right = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(1)),
			List.of(fixture.axes.get(1).get(0), fixture.axes.get(1).get(1))));
		PlacementRealizationKey outputKey = PlacementRealizationKey.nativeLineage(
			emission(), "conditional-output");
		CandidateEmissionFact merged = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(outputKey, left),
				new CandidateEmissionRealization(outputKey, right)));
		Assert.assertEquals(1, merged.realizations().size());
		var union = (NativeContinuitySupportClauses)merged.realizations().get(0).supportClauses();
		Assert.assertEquals(0, union.materializedHandleCount());
		Assert.assertEquals(explicitUnion(fixture, left, right), signatures(union));
	}

	@Test
	public void overlappingHolesProduceExactIntersectionAndFirstDonorAuthority() {
		Fixture fixture = fixture("overlap", 3, 3);
		var left = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(0), fixture.axes.get(0).get(1)),
			List.of(fixture.axes.get(1).get(0), fixture.axes.get(1).get(1))));
		var right = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(1), fixture.axes.get(0).get(2)),
			List.of(fixture.axes.get(1).get(1), fixture.axes.get(1).get(2))));
		Assert.assertNotNull(left);
		Assert.assertNotNull(right);
		var union = left.oneAxisUnion(right).orElseThrow();
		Assert.assertEquals(8, union.size());
		Assert.assertTrue(union.conditionalComplement());
		Assert.assertEquals(0, union.materializedHandleCount());
		Assert.assertEquals(explicitUnion(fixture, left, right), signatures(union));

		List<CandidateRealizationInputBinding> overlap = List.of(
			fixture.axes.get(0).get(0), fixture.axes.get(1).get(2));
		int overlapOrdinal = union.product().ordinalOfExactAuthorityBindings(overlap);
		int leftOrdinal = left.product().ordinalOfExactAuthorityBindings(overlap);
		Assert.assertSame("overlap must retain the first relation's exact member",
			left.get(leftOrdinal), union.get(overlapOrdinal));

		List<CandidateRealizationInputBinding> rightOnly = List.of(
			fixture.axes.get(0).get(0), fixture.axes.get(1).get(0));
		int rightOnlyOrdinal = union.product().ordinalOfExactAuthorityBindings(rightOnly);
		int rightOrdinal = right.product().ordinalOfExactAuthorityBindings(rightOnly);
		Assert.assertSame("only newly admitted members may use the right donor",
			right.get(rightOrdinal), union.get(rightOnlyOrdinal));
		Assert.assertSame(rightOnly.get(0), union.get(rightOnlyOrdinal)
			.inputBindings().get(0));
		Assert.assertEquals(8, union.materializedHandleCount());
		var restricted = union.restrictBindings(binding ->
			binding.inputPosition() != 0 || binding == fixture.axes.get(0).get(0)).orElseThrow();
		Assert.assertEquals(0, restricted.materializedHandleCount());
		Assert.assertSame(left.get(leftOrdinal), restricted.get(
			restricted.product().ordinalOfExactAuthorityBindings(overlap)));
		Assert.assertSame(right.get(rightOrdinal), restricted.get(
			restricted.product().ordinalOfExactAuthorityBindings(rightOnly)));
		Assert.assertSame(left, left.oneAxisUnion(left).orElseThrow());
	}

	@Test
	public void disjointHolesRestoreOrdinaryBaseWithoutEnumeration() {
		Fixture fixture = fixture("disjoint", 2, 3);
		var left = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(0)), List.of(fixture.axes.get(1).get(0))));
		var right = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(1)), List.of(fixture.axes.get(1).get(1))));
		var union = left.oneAxisUnion(right).orElseThrow();
		Assert.assertFalse(union.conditionalComplement());
		Assert.assertEquals(6, union.size());
		Assert.assertEquals(0, left.materializedHandleCount());
		Assert.assertEquals(0, right.materializedHandleCount());
		Assert.assertEquals(0, union.materializedHandleCount());
		Assert.assertEquals(explicitUnion(fixture, left, right), signatures(union));
	}

	@Test
	public void seededMasksPreserveCanonicalSetRankHashAndAuthority() {
		Random random = new Random(0x51a7eL);
		for(int iteration = 0; iteration < 32; iteration++) {
			Fixture fixture = fixture("random-" + iteration, 2 + random.nextInt(3),
				2 + random.nextInt(3));
			List<List<CandidateRealizationInputBinding>> leftMask = randomMask(fixture, random);
			List<List<CandidateRealizationInputBinding>> rightMask = randomMask(fixture, random);
			var left = conditional(fixture, leftMask);
			var right = conditional(fixture, rightMask);
			var merged = left.oneAxisUnion(right);
			Assert.assertTrue("iteration " + iteration + " left=" + leftMask
				+ " right=" + rightMask, merged.isPresent());
			var union = merged.orElseThrow();
			List<String> expected = explicitUnion(fixture, left, right);
			Assert.assertEquals(expected, signatures(union));
			Assert.assertEquals(new ArrayList<>(union).hashCode(), union.hashCode());
			for(int ordinal = 0; ordinal < union.size(); ordinal++)
				Assert.assertEquals(ordinal, union.product().ordinalOfExactAuthorityBindings(
					union.get(ordinal).inputBindings()));
		}
	}

	@Test
	public void foreignBaseNestedRightAndTightCapsFailClosed() {
		Fixture fixture = fixture("fallback", 3, 3);
		var left = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(0), fixture.axes.get(0).get(1)),
			List.of(fixture.axes.get(1).get(0))));
		var right = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(1)),
			List.of(fixture.axes.get(1).get(0), fixture.axes.get(1).get(1))));
		var accumulated = left.oneAxisUnion(right).orElseThrow();
		Assert.assertTrue("a nested right donor must use the unchanged exact fallback",
			right.oneAxisUnion(accumulated).isEmpty());

		List<List<CandidateRealizationInputBinding>> equalDistinctAxes = List.of(
			axis(0, 3, fixture.sourceOwners.get(0), "fallback-l"),
			axis(1, 3, fixture.sourceOwners.get(1), "fallback-r"));
		var foreignBase = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			fixture.seed, fixture.output, true, equalDistinctAxes);
		var foreign = NativeContinuitySupportClauses.exactComplement(fixture.owner,
			foreignBase, List.of(List.of(equalDistinctAxes.get(0).get(0)),
				List.of(equalDistinctAxes.get(1).get(0))), fixture.output, true);
		Assert.assertTrue("rebuilt bindings retain the same exact owner authority",
			left.oneAxisUnion(foreign).isPresent());
		List<List<CandidateRealizationInputBinding>> foreignOwnerAxes = List.of(
			axis(0, 3, key("fallback-foreign-left"), "fallback-l"),
			fixture.axes.get(1));
		var foreignOwnerBase = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			fixture.seed, fixture.output, true, foreignOwnerAxes);
		var foreignOwner = NativeContinuitySupportClauses.exactComplement(fixture.owner,
			foreignOwnerBase, List.of(List.of(foreignOwnerAxes.get(0).get(0)),
				List.of(foreignOwnerAxes.get(1).get(0))), fixture.output, true);
		Assert.assertTrue(left.oneAxisUnion(foreignOwner).isEmpty());
		Assert.assertTrue(left.product().conditionalUnion(right.product(), 8, 8).isEmpty());
		Assert.assertEquals(0, left.materializedHandleCount());
		Assert.assertEquals(0, right.materializedHandleCount());
		Assert.assertEquals(0, accumulated.materializedHandleCount());
	}

	@Test
	public void donorLimitFallsBackBeforeRetainingAnUnboundedUnionChain() {
		Fixture fixture = fixture("donor-cap", 66, 2);
		List<CandidateRealizationInputBinding> firstAxis = fixture.axes.get(0);
		List<CandidateRealizationInputBinding> secondMask = List.of(fixture.axes.get(1).get(0));
		NativeContinuitySupportClauses accumulated = conditional(fixture,
			List.of(firstAxis, secondMask));
		Assert.assertNotNull(accumulated);
		for(int removed = 1; removed < 63; removed++) {
			var next = conditional(fixture,
				List.of(firstAxis.subList(removed, firstAxis.size()), secondMask));
			accumulated = accumulated.oneAxisUnion(next).orElseThrow();
		}
		Assert.assertEquals(63, accumulated.authorityDonorCount());
		var sixtyFourth = conditional(fixture,
			List.of(firstAxis.subList(63, firstAxis.size()), secondMask));
		accumulated = accumulated.oneAxisUnion(sixtyFourth).orElseThrow();
		Assert.assertEquals(64, accumulated.authorityDonorCount());
		var overLimit = conditional(fixture,
			List.of(firstAxis.subList(64, firstAxis.size()), secondMask));
		Assert.assertTrue(accumulated.oneAxisUnion(overLimit).isEmpty());
		Assert.assertEquals(0, accumulated.materializedHandleCount());
		Assert.assertEquals(0, overLimit.materializedHandleCount());
	}

	@Test
	public void conditionalBaseGrowthPreservesBothAuthorityRegions() {
		Fixture retainedFixture = fixture("base-growth", 2, 2);
		List<List<CandidateRealizationInputBinding>> grownAxes = new ArrayList<>();
		for(int axis = 0; axis < retainedFixture.axes.size(); axis++) {
			List<CandidateRealizationInputBinding> grown = new ArrayList<>(retainedFixture.axes.get(axis));
			grown.add(direct(axis, "base-growth-new-" + axis,
				retainedFixture.sourceOwners.get(axis)));
			grown.sort(PlacementAnalysis.canonicalComparator());
			grownAxes.add(List.copyOf(grown));
		}
		Fixture grownFixture = new Fixture(retainedFixture.owner, retainedFixture.sourceOwners,
			retainedFixture.seed, retainedFixture.output, List.copyOf(grownAxes),
			NativePlacementContinuity.NativeSupportProduct.tryCreate(retainedFixture.seed,
				retainedFixture.output, true, grownAxes));
		List<List<CandidateRealizationInputBinding>> hole = List.of(
			List.of(retainedFixture.axes.get(0).get(0)),
			List.of(retainedFixture.axes.get(1).get(0)));
		var retained = conditional(retainedFixture, hole);
		var candidate = conditional(grownFixture, hole);
		List<CandidateRealizationSupportClause> retainedMembers = new ArrayList<>(retained);
		List<CandidateRealizationSupportClause> candidateMembers = new ArrayList<>(candidate);

		var union = retained.oneAxisUnion(candidate).orElseThrow();

		Assert.assertTrue(union.conditionalComplement());
		Assert.assertEquals(8, union.size());
		Assert.assertEquals(0, union.materializedHandleCount());
		Assert.assertEquals(signatures(candidate), signatures(union));
		Assert.assertEquals(new ArrayList<>(union).hashCode(), union.hashCode());
		for(int ordinal = 0; ordinal < union.size(); ordinal++) {
			CandidateRealizationSupportClause member = union.get(ordinal);
			int retainedOrdinal = retained.product()
				.ordinalOfExactAuthorityBindings(member.inputBindings());
			if(retainedOrdinal >= 0)
				Assert.assertSame(retainedMembers.get(retainedOrdinal), member);
			else {
				int candidateOrdinal = candidate.product()
					.ordinalOfExactAuthorityBindings(member.inputBindings());
				Assert.assertSame(candidateMembers.get(candidateOrdinal), member);
			}
		}
		var restricted = union.restrictBindings(binding ->
			retained.product().containsExactBinding(binding.inputPosition(), binding)).orElseThrow();
		Assert.assertEquals(retained.size(), restricted.size());
		for(int ordinal = 0; ordinal < restricted.size(); ordinal++)
			Assert.assertSame(retainedMembers.get(ordinal), restricted.get(ordinal));
		Assert.assertNull(candidate.product().conditionalGrowthResidualAfter(
			retained.product(), 0, 0));
	}

	@Test
	public void conditionalBaseGrowthRejectsChangedHoleAndIncompleteBase() {
		Fixture retainedFixture = fixture("base-growth-reject", 2, 2);
		Fixture grownFixture = fixtureWithSharedPrefix("base-growth-reject", retainedFixture, 3, 3);
		List<List<CandidateRealizationInputBinding>> hole = List.of(
			List.of(retainedFixture.axes.get(0).get(0)),
			List.of(retainedFixture.axes.get(1).get(0)));
		var retained = conditional(retainedFixture, hole);
		var changedHole = conditional(grownFixture, List.of(
			List.of(grownFixture.axes.get(0).get(1)),
			List.of(grownFixture.axes.get(1).get(0))));
		Assert.assertTrue(retained.oneAxisUnion(changedHole).isEmpty());

		CandidateRealizationInputBinding added = grownFixture.axes.get(0).stream()
			.filter(binding -> !retainedFixture.axes.get(0).contains(binding)).findFirst().orElseThrow();
		List<List<CandidateRealizationInputBinding>> incompleteAxes = List.of(
			List.of(retainedFixture.axes.get(0).get(0), added),
			grownFixture.axes.get(1));
		Fixture incomplete = new Fixture(retainedFixture.owner, retainedFixture.sourceOwners,
			retainedFixture.seed, retainedFixture.output, incompleteAxes,
			NativePlacementContinuity.NativeSupportProduct.tryCreate(retainedFixture.seed,
				retainedFixture.output, true, incompleteAxes));
		var incompleteCandidate = conditional(incomplete, hole);
		Assert.assertTrue(retained.oneAxisUnion(incompleteCandidate).isEmpty());
		var grownCandidate = conditional(grownFixture, hole);
		CompiledHopKey foreignRelationOwner = new CompiledHopKey(
			retainedFixture.owner.programFingerprint(), retainedFixture.owner.functionNamespace(),
			retainedFixture.owner.callSitePath(), retainedFixture.owner.recompileContext(),
			retainedFixture.owner.controlRegion(), retainedFixture.owner.emittedHopInstance(),
			retainedFixture.owner.canonicalSourceOrigin());
		Assert.assertEquals(retainedFixture.owner, foreignRelationOwner);
		Assert.assertNotSame(retainedFixture.owner, foreignRelationOwner);
		Assert.assertTrue(retained.oneAxisUnion(new NativeContinuitySupportClauses(
			foreignRelationOwner, grownCandidate.product(), retainedFixture.output, true)).isEmpty());
		Assert.assertTrue(retained.oneAxisUnion(new NativeContinuitySupportClauses(
			retainedFixture.owner, grownCandidate.product(), pool("base-growth-other-output"), true))
			.isEmpty());
		Assert.assertTrue(retained.oneAxisUnion(new NativeContinuitySupportClauses(
			retainedFixture.owner, grownCandidate.product(), retainedFixture.output, false)).isEmpty());
		Assert.assertEquals(0, retained.materializedHandleCount());
		Assert.assertEquals(0, changedHole.materializedHandleCount());
		Assert.assertEquals(0, incompleteCandidate.materializedHandleCount());
	}

	@Test
	public void conditionalBaseGrowthMatchesExplicitAcrossFixedSeedDomains() {
		Random random = new Random(0x6ba5eL);
		for(int trial = 0; trial < 24; trial++) {
			Fixture retainedFixture = fixture("base-growth-random-" + trial,
				2 + random.nextInt(2), 2 + random.nextInt(2));
			Fixture grownFixture = fixtureWithSharedPrefix("base-growth-random-" + trial,
				retainedFixture, retainedFixture.axes.get(0).size() + 1,
				retainedFixture.axes.get(1).size() + 1);
			List<List<CandidateRealizationInputBinding>> hole = List.of(
				List.of(retainedFixture.axes.get(0).get(0)),
				List.of(retainedFixture.axes.get(1).get(0)));
			var retained = conditional(retainedFixture, hole);
			var candidate = conditional(grownFixture, hole);
			var union = retained.oneAxisUnion(candidate).orElseThrow();
			Assert.assertEquals(signatures(candidate), signatures(union));
			Assert.assertEquals(new ArrayList<>(candidate).hashCode(), union.hashCode());
			for(int ordinal = 0; ordinal < union.size(); ordinal++)
				Assert.assertEquals(ordinal, union.product().ordinalOfExactAuthorityBindings(
					union.get(ordinal).inputBindings()));
		}
	}

	@Test
	public void canonicalRealizationBoundarySkipsUnrelatedLazySupportButKeepsLegacyDuplicates() {
		Fixture fixture = fixture("canonical-boundary", 2, 2);
		var nativeSupport = conditional(fixture, List.of(
			List.of(fixture.axes.get(0).get(0)), List.of(fixture.axes.get(1).get(0))));
		PlacementRealizationKey firstKey = PlacementRealizationKey.nativeLineage(
			emission(), "canonical-a");
		PlacementRealizationKey secondKey = PlacementRealizationKey.nativeLineage(
			emission(), "canonical-b");
		var explicit = new CandidateEmissionRealization(firstKey,
			List.copyOf(nativeSupport));
		var lazy = new NativeContinuitySupportClauses(fixture.owner,
			nativeSupport.product(), fixture.output, true);
		var nativeRealization = new CandidateEmissionRealization(secondKey, lazy);
		lazy = new NativeContinuitySupportClauses(fixture.owner,
			nativeSupport.product(), fixture.output, true);
		nativeRealization = new CandidateEmissionRealization(secondKey, lazy);
		List<CandidateEmissionRealization> canonical = PlacementAnalysis.CandidateEmissionFact
			.canonicalRealizationList(List.of(explicit, nativeRealization));
		Assert.assertEquals(2, canonical.size());
		Assert.assertEquals(0, lazy.materializedHandleCount());

		List<List<CandidateRealizationInputBinding>> clonedAxes = cloneAxes(fixture.axes, false);
		var equalDistinctProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			fixture.seed, fixture.output, true, clonedAxes);
		var equalDistinct = new NativeContinuitySupportClauses(fixture.owner,
			equalDistinctProduct, fixture.output, true);
		PlacementRealizationKey equalKey = PlacementRealizationKey.nativeLineage(
			emission(), "canonical-equal");
		Assert.assertThrows(IllegalArgumentException.class, () ->
			PlacementAnalysis.CandidateEmissionFact.canonicalRealizationList(List.of(
				new CandidateEmissionRealization(equalKey, new NativeContinuitySupportClauses(
					fixture.owner, fixture.base, fixture.output, true)),
				new CandidateEmissionRealization(equalKey, equalDistinct))));
		Assert.assertThrows(IllegalArgumentException.class, () ->
			PlacementAnalysis.CandidateEmissionFact.canonicalRealizationList(List.of(
				new CandidateEmissionRealization(equalKey, nativeSupport),
				new CandidateEmissionRealization(equalKey, List.copyOf(nativeSupport)))));
		List<CandidateEmissionRealization> distinctAuthority =
			PlacementAnalysis.CandidateEmissionFact.canonicalRealizationList(List.of(
				new CandidateEmissionRealization(equalKey, nativeSupport),
				new CandidateEmissionRealization(equalKey, List.of(
					new CandidateRealizationSupportClause(List.of(), List.of())))));
		Assert.assertEquals("equal keys with different proof authority remain distinct",
			2, distinctAuthority.size());
	}

	private static List<List<CandidateRealizationInputBinding>> randomMask(
		Fixture fixture, Random random) {
		List<List<CandidateRealizationInputBinding>> mask = new ArrayList<>();
		boolean excludesEverything = true;
		for(List<CandidateRealizationInputBinding> axis : fixture.axes) {
			List<CandidateRealizationInputBinding> selected = new ArrayList<>();
			for(CandidateRealizationInputBinding binding : axis)
				if(random.nextBoolean())
					selected.add(binding);
			if(selected.isEmpty())
				selected.add(axis.get(random.nextInt(axis.size())));
			excludesEverything &= selected.size() == axis.size();
			mask.add(List.copyOf(selected));
		}
		if(excludesEverything) {
			List<CandidateRealizationInputBinding> last = new ArrayList<>(mask.get(mask.size() - 1));
			last.remove(last.size() - 1);
			mask.set(mask.size() - 1, List.copyOf(last));
		}
		return List.copyOf(mask);
	}

	private static NativeContinuitySupportClauses conditional(Fixture fixture,
		List<List<CandidateRealizationInputBinding>> mask) {
		return NativeContinuitySupportClauses.exactComplement(fixture.owner,
			fixture.base, mask, fixture.output, true);
	}

	private static List<String> explicitUnion(Fixture fixture,
		NativeContinuitySupportClauses left, NativeContinuitySupportClauses right) {
		List<CandidateRealizationSupportClause> clauses = explicit(fixture);
		return clauses.stream().filter(clause -> left.product()
			.ordinalOfExactAuthorityBindings(clause.inputBindings()) >= 0
			|| right.product().ordinalOfExactAuthorityBindings(clause.inputBindings()) >= 0)
			.map(CandidateRealizationSupportClause::normalizedSignature).toList();
	}

	private static List<String> signatures(NativeContinuitySupportClauses relation) {
		return relation.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList();
	}

	private static List<CandidateRealizationSupportClause> explicit(Fixture fixture) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(CandidateRealizationInputBinding left : fixture.axes.get(0))
			for(CandidateRealizationInputBinding right : fixture.axes.get(1)) {
				List<CandidateRealizationInputBinding> bindings =
					PlacementAnalysis.sharedAlreadyCanonicalComparableList(
						List.of(left, right), "conditional union oracle");
				var proof = new NativePlacementContinuity.NativeContinuityProof(
					fixture.seed, fixture.output, true, bindings)
					.continuityProofKey(fixture.owner);
				clauses.add(new CandidateRealizationSupportClause(
					List.of(proof), bindings, fixture.output, true));
			}
		clauses.sort(PlacementAnalysis.canonicalComparator());
		return clauses;
	}

	private static Fixture fixture(String id, int leftWidth, int rightWidth) {
		CompiledHopKey owner = key(id + "-owner");
		List<CompiledHopKey> sourceOwners = List.of(key(id + "-left"), key(id + "-right"));
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			axis(0, leftWidth, sourceOwners.get(0), id + "-l"),
			axis(1, rightWidth, sourceOwners.get(1), id + "-r"));
		DurableAnchorKey seed = pool(id + "-seed");
		DurableAnchorKey output = pool(id + "-output");
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, axes);
		return new Fixture(owner, sourceOwners, seed, output, axes, base);
	}

	private static Fixture fixtureWithSharedPrefix(String id, Fixture prefix,
		int leftWidth, int rightWidth) {
		List<Integer> widths = List.of(leftWidth, rightWidth);
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		for(int axis = 0; axis < widths.size(); axis++) {
			List<CandidateRealizationInputBinding> values = new ArrayList<>(prefix.axes.get(axis));
			for(int option = values.size(); option < widths.get(axis); option++)
				values.add(direct(axis, id + "-new-" + axis + '-' + option,
					prefix.sourceOwners.get(axis)));
			values.sort(PlacementAnalysis.canonicalComparator());
			axes.add(List.copyOf(values));
		}
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			prefix.seed, prefix.output, true, axes);
		return new Fixture(prefix.owner, prefix.sourceOwners, prefix.seed, prefix.output,
			List.copyOf(axes), base);
	}

	private static List<CandidateRealizationInputBinding> axis(int position, int width,
		CompiledHopKey owner, String prefix) {
		List<CandidateRealizationInputBinding> axis = new ArrayList<>();
		for(int option = 0; option < width; option++)
			axis.add(direct(position, prefix + '-' + option + "x".repeat(option * 7), owner));
		axis.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(axis);
	}

	private static CandidateRealizationInputBinding direct(int position, String lineage,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference reference = new CandidateRealizationReference(rule,
			PlacementRealizationKey.nativeLineage(emission(), lineage));
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
			"native-conditional-union", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-conditional-union", "main", name,
			"compiled", region, name, name);
	}

	private record Fixture(CompiledHopKey owner, List<CompiledHopKey> sourceOwners,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes,
		NativePlacementContinuity.NativeSupportProduct base) { }
}
