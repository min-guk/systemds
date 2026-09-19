/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NeutralPlacementBindingAssignmentStreamingTest {
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void streamedLeavesMatchIndependentNestedLoopOrderAndDoNotAliasTraversalBuffer() {
		List<List<CandidateRealizationInputBinding>> choices = List.of(
			bindings(0, "a", 2), bindings(1, "b", 3), bindings(2, "c", 2));
		List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder.enumerateBindingAssignments(
			choices, 0, new ArrayList<>(), actual::add, metrics);

		List<List<CandidateRealizationInputBinding>> expected = new ArrayList<>();
		for(CandidateRealizationInputBinding a : choices.get(0))
			for(CandidateRealizationInputBinding b : choices.get(1))
				for(CandidateRealizationInputBinding c : choices.get(2))
					expected.add(List.of(a, b, c));
		Assert.assertEquals(expected, actual);
		Assert.assertEquals(12, actual.size());
		Assert.assertEquals(12, actual.stream().distinct().count());
		Assert.assertThrows(UnsupportedOperationException.class, () -> actual.get(0).clear());
		Assert.assertEquals(expected.get(0), actual.get(0));

		SearchSpaceMetrics.Snapshot snapshot = metrics.snapshot();
		Assert.assertEquals(12, snapshot.relocationLeaves());
		Assert.assertEquals(3, snapshot.relocationPeakDepth());
		Assert.assertEquals(1, snapshot.relocationPeakPendingAssignments());
	}

	@Test
	public void emptyProductContractsAndConsumerFailureArePreserved() {
		List<List<CandidateRealizationInputBinding>> zeroDimension = new ArrayList<>();
		NeutralPlacementGraphBuilder.enumerateBindingAssignments(
			List.of(), 0, new ArrayList<>(), zeroDimension::add, null);
		Assert.assertEquals(List.of(List.of()), zeroDimension);

		List<List<CandidateRealizationInputBinding>> emptyDomain = new ArrayList<>();
		NeutralPlacementGraphBuilder.enumerateBindingAssignments(
			List.of(bindings(0, "a", 1), List.of()), 0, new ArrayList<>(), emptyDomain::add, null);
		Assert.assertTrue(emptyDomain.isEmpty());

		List<CandidateRealizationInputBinding> traversal = new ArrayList<>();
		IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
			() -> NeutralPlacementGraphBuilder.enumerateBindingAssignments(
				List.of(bindings(0, "a", 2)), 0, traversal,
				ignored -> { throw new IllegalStateException("consumer"); }, null));
		Assert.assertEquals("consumer", failure.getMessage());
		Assert.assertTrue("the reusable traversal buffer must be restored on failure", traversal.isEmpty());
	}

	@Test
	public void inputProductsStreamInLegacyOrderIncludingAbsentLocalNull() {
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.ROW), List.of(FType.COL, FType.FULL));
		List<List<FType>> actual = new ArrayList<>();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder.forEachInputCombination(domains, actual::add, metrics);

		List<List<FType>> expected = List.of(
			Arrays.asList(null, FType.COL), Arrays.asList(null, FType.FULL),
			List.of(FType.ROW, FType.COL), List.of(FType.ROW, FType.FULL));
		Assert.assertEquals(expected, actual);
		Assert.assertThrows(UnsupportedOperationException.class, () -> actual.get(0).clear());
		Assert.assertEquals(4, metrics.snapshot().inputLeaves());
		Assert.assertEquals(2, metrics.snapshot().inputPeakDepth());

		List<List<FType>> zeroDimension = new ArrayList<>();
		NeutralPlacementGraphBuilder.forEachInputCombination(List.of(), zeroDimension::add, null);
		Assert.assertEquals(List.of(List.of()), zeroDimension);
		List<List<FType>> emptyDomain = new ArrayList<>();
		NeutralPlacementGraphBuilder.forEachInputCombination(
			List.of(List.of(FType.ROW), List.of()), emptyDomain::add, null);
		Assert.assertTrue(emptyDomain.isEmpty());
	}

	private static List<CandidateRealizationInputBinding> bindings(int position, String prefix, int count) {
		List<CandidateRealizationInputBinding> result = new ArrayList<>();
		for(int index = 0; index < count; index++)
			result.add(CandidateRealizationInputBinding.direct(position, source(prefix + index)));
		return List.copyOf(result);
	}

	private static CandidateRealizationReference source(String name) {
		CandidateRuleKey rule = new CandidateRuleKey(key(name), List.of());
		DurableAnchorKey pool = new DurableAnchorKey("stream-" + name, FType.ROW,
			List.of(new AnchorPartition("localhost:18101", List.of(0L, 0L), List.of(8L, 2L))));
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.durable(EMISSION, pool, List.of(), List.of()));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"stream-test", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("stream-test", "main", name, "compiled", region, name, name);
	}
}
