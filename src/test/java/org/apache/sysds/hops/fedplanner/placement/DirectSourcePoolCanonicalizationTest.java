/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Canonical collection semantics only; this fixture does not claim a legal executable plan. */
public class DirectSourcePoolCanonicalizationTest {
	@Test
	@SuppressWarnings("unchecked")
	public void repeatedClausesSerializeEachExactPoolOnceAfterGlobalCacheSaturation() throws Exception {
		ControlRegionKey region = new ControlRegionKey("pool-sort", "main", List.of("root"),
			"root", "compiled");
		CompiledHopKey owner = new CompiledHopKey("pool-sort", "main", "root", "compiled",
			region, "producer", "producer");
		PlacementEmissionState emission = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		List<AnchorPartition> partitions = List.of(
			new AnchorPartition("worker-a", List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition("worker-b", List.of(4L, 0L), List.of(8L, 2L)));
		DurableAnchorKey first = new DurableAnchorKey("pool-9", FType.ROW, partitions);
		DurableAnchorKey second = new DurableAnchorKey("pool-10", FType.ROW, partitions);
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		for(DurableAnchorKey pool : List.of(first, second)) {
			List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
			for(int index = 0; index < 48; index++)
				clauses.add(new CandidateRealizationSupportClause(List.of(
					new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, owner,
						"support-" + index)), List.of()));
			realizations.add(new CandidateEmissionRealization(
				PlacementRealizationKey.durable(emission, pool), clauses));
		}
		CandidateRuleFact fact = new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "pool-sort", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "pool-sort", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(emission, FType.ROW, null, realizations)), "");
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$WorkerPoolAnchorResolver");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class, List.class,
			List.class, Collection.class, Map.class, Map.class);
		constructor.setAccessible(true);
		Object resolver = constructor.newInstance(Map.of(), Map.of(), List.of(fact), List.of(),
			List.of(), Map.of(), Map.of());
		Method resolve = type.getDeclaredMethod("resolve", CompiledHopKey.class, FType.class);
		resolve.setAccessible(true);
		resolve.invoke(resolver, owner, FType.ROW);
		Method direct = type.getDeclaredMethod("resolveDirectSourcePools", CompiledHopKey.class, FType.class);
		direct.setAccessible(true);
		List<DurableAnchorKey> expected = List.copyOf(new TreeSet<>(List.of(first, second)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			Set<DurableAnchorKey> result = (Set<DurableAnchorKey>)direct.invoke(resolver, owner, FType.ROW);
			long serializations = metrics.snapshot().signatureSerializations();
			Assert.assertEquals("different exact anchors sharing a physical pool remain distinct",
				expected, List.copyOf(result));
			Assert.assertTrue("sort must serialize each anchor and its two partitions only once: "
				+ serializations, serializations <= 6L);
			for(int index = 0; index < expected.size(); index++)
				Assert.assertSame(expected.get(index), List.copyOf(result).get(index));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}
}
