/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Query-local traversal memoization must preserve exact authority and reachable pools. */
public class JointPhysicalCostRowsTraversalMemoTest {
	private static final PlacementState STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(STATE, false);

	@Test
	public void possiblePoolsExpandsReconvergentSupportOnce() {
		Diamond fixture = diamond();

		Set<DurableAnchorKey> pools = JointPhysicalCostRows.possiblePools(
			fixture.analysis, fixture.root, fixture.root.supportClauses().get(0));

		Assert.assertEquals(Set.of(fixture.pool), pools);
		verify(fixture.shared, times(1)).supportClauses();
		verify(fixture.analysis, times(2)).requireExactCandidateRealization(eq(fixture.sharedReference));
	}

	@Test
	public void exactMapCollectorExpandsReconvergentSupportOnce() throws Exception {
		Diamond fixture = diamond();
		JointPhysicalCostRows rows = new JointPhysicalCostRows(fixture.analysis);
		Method collect = JointPhysicalCostRows.class.getDeclaredMethod("collectExactMapChoices",
			CandidateRealizationReference.class, Set.class, Set.class, Set.class);
		collect.setAccessible(true);
		Set<DurableAnchorKey> pools = new LinkedHashSet<>();

		boolean valid = (boolean)collect.invoke(rows, fixture.rootReference,
			new HashSet<CandidateRealizationReference>(),
			new HashSet<CandidateRealizationReference>(), pools);

		Assert.assertTrue(valid);
		Assert.assertEquals(Set.of(fixture.pool), pools);
		verify(fixture.shared, times(1)).supportClauses();
		verify(fixture.analysis, times(2)).requireExactCandidateRealization(eq(fixture.sharedReference));
	}

	@Test
	public void completedReferenceStillRequiresExactAuthorityOnEveryEdge() {
		Diamond fixture = diamond();
		AtomicInteger sharedEdges = new AtomicInteger();
		when(fixture.analysis.requireExactCandidateRealization(any())).thenAnswer(invocation -> {
			CandidateRealizationReference reference = invocation.getArgument(0);
			if(reference.equals(fixture.sharedReference) && sharedEdges.incrementAndGet() == 2)
				throw new IllegalArgumentException("foreign repeated reference");
			return fixture.realizations.get(reference);
		});

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> JointPhysicalCostRows.possiblePools(
				fixture.analysis, fixture.root, fixture.root.supportClauses().get(0)));

		Assert.assertEquals("foreign repeated reference", failure.getMessage());
		verify(fixture.shared, times(1)).supportClauses();
	}

	@Test
	public void activeCycleStillFindsAnchorOutsideCycle() {
		CandidateRuleKey seedRule = rule("cycle-seed");
		CandidateEmissionRealization seed = durable("cycle-seed");
		CandidateRuleKey aRule = rule("cycle-a");
		CandidateRuleKey bRule = rule("cycle-b");
		CandidateEmissionRealization aKey = valueMap("cycle-a", clause());
		CandidateEmissionRealization bKey = valueMap("cycle-b", clause());
		CandidateRealizationReference aReference = ref(aRule, aKey);
		CandidateRealizationReference bReference = ref(bRule, bKey);
		CandidateEmissionRealization a = spy(valueMap("cycle-a",
			clause(bReference, ref(seedRule, seed))));
		CandidateEmissionRealization b = spy(valueMap("cycle-b", clause(aReference)));
		CandidateEmissionRealization root = valueMap("cycle-root", clause(aReference));
		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations = Map.of(
			aReference, a, bReference, b, ref(seedRule, seed), seed);
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));

		Set<DurableAnchorKey> pools = JointPhysicalCostRows.possiblePools(
			analysis, root, root.supportClauses().get(0));

		Assert.assertEquals(Set.of(seed.key().durableAnchor()), pools);
		verify(a, times(1)).supportClauses();
		verify(b, times(1)).supportClauses();
		verify(analysis, times(2)).requireExactCandidateRealization(eq(aReference));
	}

	private static Diamond diamond() {
		CandidateRuleKey seedRule = rule("seed");
		CandidateEmissionRealization seed = durable("seed");
		DurableAnchorKey pool = seed.key().durableAnchor();
		CandidateRuleKey sharedRule = rule("shared");
		CandidateEmissionRealization shared = spy(valueMap("shared",
			clause(ref(seedRule, seed))));
		CandidateRealizationReference sharedReference = ref(sharedRule, shared);
		CandidateRuleKey leftRule = rule("left");
		CandidateEmissionRealization left = valueMap("left", clause(sharedReference));
		CandidateRuleKey rightRule = rule("right");
		CandidateEmissionRealization right = valueMap("right", clause(sharedReference));
		CandidateRuleKey rootRule = rule("root");
		CandidateEmissionRealization root = valueMap("root",
			clause(ref(leftRule, left), ref(rightRule, right)));
		CandidateRealizationReference rootReference = ref(rootRule, root);

		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations = new HashMap<>();
		realizations.put(rootReference, root);
		realizations.put(ref(leftRule, left), left);
		realizations.put(ref(rightRule, right), right);
		realizations.put(sharedReference, shared);
		realizations.put(ref(seedRule, seed), seed);
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		when(analysis.jointInputAnalysis()).thenReturn(Optional.empty());
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			realizations.get((CandidateRealizationReference)invocation.getArgument(0)));
		return new Diamond(analysis, realizations, root, rootReference,
			shared, sharedReference, pool);
	}

	private static CandidateEmissionRealization durable(String id) {
		DurableAnchorKey pool = new DurableAnchorKey(id, FType.FULL,
			List.of(new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 2L))));
		return CandidateEmissionRealization.durable(EMISSION, pool, List.of(), List.of());
	}

	private static CandidateEmissionRealization valueMap(String id,
		CandidateRealizationSupportClause clause) {
		return CandidateEmissionRealization.valueMap(EMISSION, id, List.of(clause));
	}

	private static CandidateRealizationSupportClause clause(
		CandidateRealizationReference... sources) {
		List<CandidateRealizationInputBinding> bindings = java.util.stream.IntStream
			.range(0, sources.length)
			.mapToObj(position -> CandidateRealizationInputBinding.direct(position, sources[position]))
			.toList();
		return new CandidateRealizationSupportClause(List.of(), bindings);
	}

	private static CandidateRealizationReference ref(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationReference.of(rule, realization);
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"joint-cost-traversal", "main", List.of("main"), "main", "compiled");
		CompiledHopKey owner = new CompiledHopKey("joint-cost-traversal", "main", "main",
			"compiled", region, name, name);
		return new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.FULL)));
	}

	private record Diamond(PlacementAnalysis analysis,
		Map<CandidateRealizationReference,CandidateEmissionRealization> realizations,
		CandidateEmissionRealization root, CandidateRealizationReference rootReference,
		CandidateEmissionRealization shared, CandidateRealizationReference sharedReference,
		DurableAnchorKey pool) { }
}
