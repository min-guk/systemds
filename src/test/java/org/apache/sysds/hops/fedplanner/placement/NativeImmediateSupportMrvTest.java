/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeImmediateSupportMrvTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"support-mrv", "main", List.of("root"), "root", "compiled");
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);

	@Test
	public void sharedOwnerRestrictionPrecedesLargeIndependentAxis() throws Exception {
		CompiledHopKey shared = key("shared");
		List<CandidateRealizationInputBinding> independent = new ArrayList<>();
		for(int i = 0; i < 80; i++)
			independent.add(binding(0, key("independent-" + i), false));
		List<List<CandidateRealizationInputBinding>> domains = List.of(independent,
			List.of(binding(1, shared, false), binding(1, shared, true)),
			List.of(binding(2, shared, true)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		long[] referenceVisits = {0};
		List<List<CandidateRealizationInputBinding>> expected = reference(domains, referenceVisits);
		Assert.assertEquals(new HashSet<>(expected), new HashSet<>(enumerate(domains, metrics)));
		Assert.assertEquals(80, metrics.snapshot().supportLeaves());
		Assert.assertEquals(321, referenceVisits[0]);
		Assert.assertEquals(83, metrics.snapshot().supportPrefixes());
		Assert.assertEquals(1, metrics.generationPruningCoverage().supportMrvProducts());
		Assert.assertEquals(2, metrics.generationPruningCoverage().supportSourceChecks());
		Assert.assertEquals(1, metrics.generationPruningCoverage().supportSourceOptionsRemoved());
		Assert.assertTrue("reject the shared-owner alternative before the independent fanout",
			metrics.snapshot().supportPrefixes() * 3 < referenceVisits[0]);
	}

	@Test
	public void independentOwnersUseAConstantMrvOrderWithoutSourceChecks() throws Exception {
		List<CandidateRealizationInputBinding> wide = new ArrayList<>();
		for(int i = 0; i < 80; i++)
			wide.add(binding(0, key("wide-" + i), false));
		CompiledHopKey middle = key("middle"), narrow = key("narrow");
		List<List<CandidateRealizationInputBinding>> domains = List.of(wide,
			List.of(binding(1, middle, false), binding(1, middle, true)),
			List.of(binding(2, narrow, true)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		long[] visits = {0};
		Assert.assertEquals(new HashSet<>(reference(domains, visits)), new HashSet<>(enumerate(domains, metrics)));
		Assert.assertEquals(401, visits[0]);
		Assert.assertEquals(164, metrics.snapshot().supportPrefixes());
		Assert.assertEquals(160, metrics.snapshot().supportLeaves());
		Assert.assertEquals(0, metrics.generationPruningCoverage().supportSourceChecks());
	}

	@Test
	public void randomizedSparseDomainsPreserveExactMembersAndAxisOrder() throws Exception {
		Random random = new Random(812421);
		CompiledHopKey first = key("equal"), equalButDistinct = key("equal");
		Assert.assertEquals(first, equalButDistinct);
		Assert.assertNotSame(first, equalButDistinct);
		List<CompiledHopKey> owners = List.of(first, equalButDistinct, key("third"));
		for(int trial = 0; trial < 240; trial++) {
			List<List<CandidateRealizationInputBinding>> domains = new ArrayList<>();
			for(int axis = 0, axes = random.nextInt(5); axis < axes; axis++) {
				List<CandidateRealizationInputBinding> options = new ArrayList<>();
				for(CompiledHopKey owner : owners)
					for(boolean variant : new boolean[] {false, true})
						if(random.nextBoolean())
							options.add(binding(axis, owner, variant));
				domains.add(options);
			}
			List<List<CandidateRealizationInputBinding>> actual = enumerate(domains, new SearchSpaceMetrics());
			List<List<CandidateRealizationInputBinding>> expected = reference(domains, new long[1]);
			Assert.assertEquals("trial " + trial, expected.size(), actual.size());
			Assert.assertEquals("trial " + trial, new HashSet<>(expected), new HashSet<>(actual));
			for(List<CandidateRealizationInputBinding> member : actual)
				for(int axis = 0; axis < member.size(); axis++)
					Assert.assertEquals(axis, member.get(axis).inputPosition());
			Assert.assertEquals(new HashSet<>(actual), new HashSet<>(enumerate(domains, null)));
		}
	}

	@Test
	public void ownerIdentityDoesNotConflateStructurallyEqualOccurrences() throws Exception {
		List<List<CandidateRealizationInputBinding>> domains = List.of(
			List.of(binding(0, key("same"), false)), List.of(binding(1, key("same"), true)));
		Assert.assertEquals(1, enumerate(domains, new SearchSpaceMetrics()).size());
	}

	@Test
	public void checksWithoutCutsAreDistinctFromAnUncheckedIndependentProduct() throws Exception {
		CompiledHopKey shared = key("shared-check");
		SearchSpaceMetrics checked = new SearchSpaceMetrics();
		Assert.assertEquals(1, enumerate(List.of(List.of(binding(0, shared, false)),
			List.of(binding(1, shared, false))), checked).size());
		Assert.assertEquals(1, checked.generationPruningCoverage().supportSourceChecks());
		Assert.assertEquals(0, checked.generationPruningCoverage().supportSourceOptionsRemoved());
		SearchSpaceMetrics independent = new SearchSpaceMetrics();
		enumerate(List.of(List.of(binding(0, key("left"), false)),
			List.of(binding(1, key("right"), false))), independent);
		Assert.assertEquals(0, independent.generationPruningCoverage().supportSourceChecks());
		checked.reset();
		Assert.assertEquals(independent.generationPruningCoverage().supportSourceChecks(),
			checked.generationPruningCoverage().supportSourceChecks());
		Assert.assertEquals(0, checked.generationPruningCoverage().supportMrvProducts());
	}

	@Test
	public void emptyAxisRejectsAndZeroAxesRetainTheEmptyMember() throws Exception {
		Assert.assertEquals(List.of(List.of()), enumerate(List.of(), new SearchSpaceMetrics()));
		Assert.assertTrue(enumerate(List.of(List.of(binding(0, key("before-empty"), false)), List.of()),
			new SearchSpaceMetrics()).isEmpty());
	}

	private static List<List<CandidateRealizationInputBinding>> enumerate(
		List<List<CandidateRealizationInputBinding>> domains, SearchSpaceMetrics metrics) throws Exception {
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		Method method = NativePlacementContinuity.class.getDeclaredMethod("enumerateImmediateSupports",
			List.class, int.class, List.class, Consumer.class);
		method.setAccessible(true);
		List<List<CandidateRealizationInputBinding>> result = new ArrayList<>();
		Consumer<List<CandidateRealizationInputBinding>> append = result::add;
		method.invoke(continuity, domains, 0, new ArrayList<>(), append);
		return result;
	}

	private static List<List<CandidateRealizationInputBinding>> reference(
		List<List<CandidateRealizationInputBinding>> domains, long[] visits) {
		List<List<CandidateRealizationInputBinding>> result = new ArrayList<>();
		reference(domains, new ArrayList<>(), new IdentityHashMap<>(), visits, result);
		return result;
	}

	private static void reference(List<List<CandidateRealizationInputBinding>> domains,
		List<CandidateRealizationInputBinding> current, Map<CompiledHopKey,CandidateRealizationReference> selected,
		long[] visits, List<List<CandidateRealizationInputBinding>> output) {
		visits[0]++;
		if(current.size() == domains.size()) {
			output.add(List.copyOf(current));
			return;
		}
		for(CandidateRealizationInputBinding binding : domains.get(current.size())) {
			CompiledHopKey owner = binding.source().rule().parentOccurrence();
			CandidateRealizationReference prior = selected.get(owner);
			if(prior != null && !prior.equals(binding.source()))
				continue;
			if(prior == null)
				selected.put(owner, binding.source());
			current.add(binding);
			reference(domains, current, selected, visits, output);
			current.remove(current.size() - 1);
			if(prior == null)
				selected.remove(owner);
		}
	}

	private static CandidateRealizationInputBinding binding(int axis, CompiledHopKey owner, boolean variant) {
		return CandidateRealizationInputBinding.direct(axis, new CandidateRealizationReference(
			new CandidateRuleKey(owner, List.of(CandidateInputState.present(variant ? FType.COL : FType.ROW))),
			PlacementRealizationKey.local(LOCAL)));
	}
	private static CompiledHopKey key(String name) {
		return new CompiledHopKey("support-mrv", "main", "root", "compiled", REGION, name, name);
	}
}
