/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class RelocationSourceOptionDedupTest {
	@Test
	public void proofHistoryDuplicatesCollapseButLayoutAttributesRemainDistinct() throws Exception {
		ControlRegionKey region = new ControlRegionKey("relocation-dedup", "main",
			List.of("main"), "main", "compiled");
		CompiledHopKey owner = new CompiledHopKey("relocation-dedup", "main", "main",
			"compiled", region, "source", "source");
		ValueVersionKey version = new ValueVersionKey("relocation-dedup", "source", region,
			0, VersionKind.ORDINARY, List.of());
		DurableAnchorKey pool = new DurableAnchorKey("pool", FType.FULL,
			List.of(new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 2L))));
		PlacementEmissionState emission = new PlacementEmissionState(
			new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		CandidateRealizationReference reference = new CandidateRealizationReference(
			new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.FULL))),
			PlacementIdentity.PlacementRealizationKey.durable(emission, pool));
		CandidateRealizationSupportClause first = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, owner, "first")), List.of());
		CandidateRealizationSupportClause historyOnly = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, owner, "second")), List.of());
		CandidateRealizationSupportClause dynamic = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, owner, "dynamic")),
			List.of(), pool, false);

		Class<?> optionClass = Class.forName(NeutralPlacementGraphBuilder.class.getName()
			+ "$ExactRealizationOption");
		Constructor<?> constructor = optionClass.getDeclaredConstructor(
			CandidateRealizationReference.class, CandidateRealizationSupportClause.class,
			ValueVersionKey.class);
		constructor.setAccessible(true);
		List<Object> options = List.of(constructor.newInstance(reference, first, version),
			constructor.newInstance(reference, historyOnly, version),
			constructor.newInstance(reference, dynamic, version));
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"distinctRelocationSourceOptions", List.class);
		method.setAccessible(true);
		List<?> distinct = (List<?>)method.invoke(null, options);

		Assert.assertEquals("proof history alone is not consumed by relocation binding", 2, distinct.size());
		Assert.assertSame(options.get(0), distinct.get(0));
		Assert.assertSame("dynamic-layout authority remains a separate exact option",
			options.get(2), distinct.get(1));
	}
}
