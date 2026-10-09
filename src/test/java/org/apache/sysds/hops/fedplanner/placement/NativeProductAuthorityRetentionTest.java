/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
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

public class NativeProductAuthorityRetentionTest {
	@Test
	public void multiHeaderIndexChargesRetainedAuthorityText() {
		CompiledHopKey owner = key("header-consumer");
		CompiledHopKey source = key("header-source");
		var axes = List.of(List.of(direct(0, "a", source), direct(0, "b", source)));
		var first = relation(owner, pool("a".repeat(600_000)), pool("output"), axes);
		var second = relation(owner, pool("b".repeat(600_000)), pool("output"), axes);
		Assert.assertTrue("the family index retains over one million header characters",
			first.multiHeaderUnion(second).isEmpty());
		Assert.assertEquals(0, first.materializedHandleCount());
		Assert.assertEquals(0, second.materializedHandleCount());
	}

	@Test
	public void growingPrefixesDeclineUnboundedDonorHistoryAndKeepExactFallback() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		List<CandidateRealizationInputBinding> options = new ArrayList<>();
		options.add(direct(0, "000", source));
		NativeContinuitySupportClauses current = relation(owner, pool("seed"), pool("output"),
			List.of(List.copyOf(options)));
		for(int count = 2; count <= 65; count++) {
			options.add(direct(0, String.format("%03d", count), source));
			NativeContinuitySupportClauses next = relation(owner, pool("seed"), pool("output"),
				List.of(List.copyOf(options)));
			var union = current.oneAxisUnion(next);
			if(count < 65) {
				Assert.assertTrue(union.isPresent());
				current = union.orElseThrow();
				Assert.assertEquals(count, current.authorityDonorCount());
				Assert.assertEquals(0, current.materializedHandleCount());
			}
			else {
				Assert.assertTrue("65 growing original products must use bounded exact fallback", union.isEmpty());
				CandidateRealizationSupportClause first = current.get(0);
				PlacementRealizationKey output = PlacementRealizationKey.nativeLineage(emission(), "out");
				var merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
					new CandidateEmissionRealization(output, current),
					new CandidateEmissionRealization(output, next))).realizations().get(0).supportClauses();
				Assert.assertFalse(merged instanceof NativeContinuitySupportClauses);
				Assert.assertEquals(65, merged.size());
				Assert.assertSame(first, merged.get(0));
				Assert.assertEquals(next.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList(),
					merged.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList());
			}
		}
	}

	@Test
	public void fullOriginalPotentialHandlesAreChargedWithoutMaterialization() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		List<List<CandidateRealizationInputBinding>> left = new ArrayList<>();
		List<List<CandidateRealizationInputBinding>> right = new ArrayList<>();
		for(int axis = 0; axis < 17; axis++) {
			CompiledHopKey axisOwner = key("source-" + axis);
			var a = direct(axis, "a", axisOwner);
			var b = direct(axis, "b", axisOwner);
			left.add(List.of(a, b));
			right.add(axis == 0 ? List.of(b, direct(axis, "c", axisOwner)) : List.of(a, b));
		}
		var before = relation(owner, pool("seed"), pool("output"), left);
		var added = relation(owner, pool("seed"), pool("output"), right);
		Assert.assertTrue("a small axis surface can retain too many original member handles",
			before.oneAxisUnion(added).isEmpty());
		Assert.assertEquals(0, before.materializedHandleCount());
		Assert.assertEquals(0, added.materializedHandleCount());
	}

	@Test
	public void singletonScopesStillChargeTheirFullOriginalDonors() {
		CompiledHopKey owner = key("consumer");
		List<CompiledHopKey> sources = java.util.stream.IntStream.range(0, 14)
			.mapToObj(axis -> key("source-" + axis)).toList();
		List<List<CandidateRealizationInputBinding>> common = new ArrayList<>();
		for(int axis = 1; axis < 14; axis++)
			common.add(List.of(direct(axis, "a", sources.get(axis)), direct(axis, "b", sources.get(axis))));
		NativeContinuitySupportClauses current = null;
		CandidateRealizationSupportClause first = null;
		for(int generation = 0; generation < 5; generation++) {
			var selected = direct(0, "a" + generation, sources.get(0));
			List<List<CandidateRealizationInputBinding>> originalAxes = new ArrayList<>();
			originalAxes.add(List.of(selected, direct(0, "z0", sources.get(0))));
			originalAxes.addAll(common);
			List<List<CandidateRealizationInputBinding>> addedAxes = new ArrayList<>(originalAxes);
			addedAxes.set(0, List.of(direct(0, "z1", sources.get(0))));
			var original = relation(owner, pool("seed"), pool("output"), originalAxes);
			var added = relation(owner, pool("seed"), pool("output"), addedAxes);
			var nativeUnion = original.oneAxisUnion(added).orElseThrow();
			var singleton = nativeUnion.restrictBindings(binding -> binding.inputPosition() == 0
				? binding == selected : binding == common.get(binding.inputPosition() - 1).get(0)).orElseThrow();
			Assert.assertEquals(1, singleton.size());
			Assert.assertEquals(1, singleton.authorityDonorCount());
			Assert.assertEquals(0, original.materializedHandleCount());
			if(current == null) {
				current = singleton;
				first = singleton.get(0);
			}
			else {
				var merged = current.oneAxisUnion(singleton);
				if(generation < 4) {
					current = merged.orElseThrow();
					Assert.assertSame(first, current.get(0));
				}
				else {
					Assert.assertTrue("five tiny scopes still retain five complete large originals", merged.isEmpty());
					Assert.assertEquals(4, current.size());
					Assert.assertSame(first, current.get(0));
				}
			}
		}
	}

	@Test
	public void ordinaryRestrictionDoesNotRetainSupersededOriginal() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		var a = direct(0, "a", source);
		var b = direct(0, "b", source);
		var original = relation(owner, pool("seed"), pool("output"), List.of(List.of(a, b)));
		var restricted = original.restrictBindings(binding -> binding == b).orElseThrow();
		Assert.assertEquals(1, restricted.size());
		Assert.assertEquals("ordinary pruning must not retain historical donors", 0, restricted.authorityDonorCount());
		Assert.assertEquals(original.get(1).normalizedSignature(), restricted.get(0).normalizedSignature());
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, output, true);
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
			"native-union", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-union", "main", name, "compiled",
			region, name, name);
	}

}
