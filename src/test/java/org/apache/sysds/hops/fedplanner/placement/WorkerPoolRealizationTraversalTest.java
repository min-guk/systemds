/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
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

/** Pool discovery is graph reachability, not enumeration of every support path. */
public class WorkerPoolRealizationTraversalTest {

	@Test
	public void sharedProofDagVisitsEachReferenceOnce() throws Exception {
		Map<String,CandidateEmissionRealization> facts = new HashMap<>();
		DurableAnchorKey anchor = anchor("localhost:1234");
		CandidateRealizationReference root = reference("leaf", anchor);
		facts.put(root.normalizedSignature(), new CandidateEmissionRealization(root.realization(), List.of(), List.of()));
		int depth = 12;
		for(int i = 0; i < depth; i++) {
			CandidateRealizationReference parent = reference("level-" + i, null);
			facts.put(parent.normalizedSignature(), new CandidateEmissionRealization(parent.realization(), List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, root),
					CandidateRealizationInputBinding.direct(1, root))));
			root = parent;
		}
		CountingSet visited = new CountingSet();
		Assert.assertEquals(Set.of(anchor), resolve(root, facts, visited));
		Assert.assertTrue("Shared subgraphs must not be expanded once per path: " + visited.attempts,
			visited.attempts <= 2 * depth + 1);
	}

	@Test
	public void cycleRetainsGroundedSiblingPools() throws Exception {
		DurableAnchorKey first = anchor("localhost:1234"), second = anchor("localhost:1235");
		CandidateRealizationReference left = reference("left", first), right = reference("right", second);
		Map<String,CandidateEmissionRealization> facts = Map.of(
			left.normalizedSignature(), new CandidateEmissionRealization(left.realization(), List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, right))),
			right.normalizedSignature(), new CandidateEmissionRealization(right.realization(), List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, left))));
		Assert.assertEquals(Set.of(first, second), resolve(left, facts, new CountingSet()));
		Assert.assertEquals(Set.of(first, second), resolve(right, facts, new CountingSet()));
	}

	@Test
	public void ungroundedCycleDoesNotInventAnAnchor() throws Exception {
		CandidateRealizationReference root = reference("cycle", null);
		Assert.assertTrue(resolve(root, Map.of(root.normalizedSignature(),
			new CandidateEmissionRealization(root.realization(), List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, root)))), new CountingSet()).isEmpty());
	}

	private static class CountingSet extends HashSet<String> {
		private static final long serialVersionUID = 1L;
		private int attempts;
		@Override public boolean add(String value) { attempts++; return super.add(value); }
	}

	@SuppressWarnings("unchecked")
	private static Set<DurableAnchorKey> resolve(CandidateRealizationReference root,
		Map<String,CandidateEmissionRealization> facts, Set<String> visited) throws Exception {
		Class<?> type = Class.forName(NeutralPlacementGraphBuilder.class.getName() + "$WorkerPoolAnchorResolver");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class, List.class,
			List.class, Collection.class, Map.class, Map.class);
		constructor.setAccessible(true);
		Object resolver = constructor.newInstance(Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
		Field field = type.getDeclaredField("realizationsByReference");
		field.setAccessible(true);
		((Map<String,CandidateEmissionRealization>)field.get(resolver)).putAll(facts);
		Method method = type.getDeclaredMethod("resolveCandidateRealization",
			CandidateRealizationReference.class, FType.class, Set.class);
		method.setAccessible(true);
		return (Set<DurableAnchorKey>)method.invoke(resolver, root, FType.FULL, visited);
	}

	private static CandidateRealizationReference reference(String name, DurableAnchorKey anchor) {
		ControlRegionKey region = new ControlRegionKey("traversal", "main", List.of("root"), "root", "compiled");
		CompiledHopKey key = new CompiledHopKey("traversal", "main", "root", "compiled", region, name, name);
		PlacementEmissionState emission = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		return new CandidateRealizationReference(new CandidateRuleKey(key,
			List.of(CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL))),
			anchor == null ? PlacementRealizationKey.nativeLineage(emission, name)
				: PlacementRealizationKey.durable(emission, anchor));
	}

	private static DurableAnchorKey anchor(String endpoint) {
		return new DurableAnchorKey(endpoint, FType.FULL,
			List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 2L))));
	}
}
