/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.PrivacyEvidenceMode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeHeaderForwardingBudgetTest {
	@Test
	public void sixtyFiveActualSeedRequestsUseExactScalarFallback() throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("forwarding-budget-source");
		CompiledHopKey consumerOwner = fixtureKey("forwarding-budget-consumer");
		List<DurableAnchorKey> pools = new ArrayList<>();
		List<CandidateEmissionRealization> initialVariants = new ArrayList<>();
		for(int index = 0; index < 65; index++) {
			DurableAnchorKey pool = pool("forwarding-budget-" + index, 2L + index);
			pools.add(pool);
			if(index < 64)
				initialVariants.add(exactSource(sourceOwner, pool, "source-" + index));
		}
		CandidateRuleFact consumer = consumerFact(consumerOwner);
		DataOp sourceHop = new DataOp("forwarding-budget-source", DataType.MATRIX,
			ValueType.FP64, OpOpData.TRANSIENTREAD, "forwarding-budget-source",
			8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("forwarding-budget-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false);

		CandidateRuleFact retained = bind(closure, sourceFact(sourceOwner, initialVariants),
			consumer, sourceHop, consumerHop, widths(pools.subList(0, 64), 64));
		CandidateEmissionRealization retainedPublication = realizations(retained).stream()
			.filter(realization -> realization.supportClauses()
				instanceof NativeContinuitySupportClauses)
			.findFirst().orElseThrow();
		NativeContinuitySupportClauses retainedRelation =
			(NativeContinuitySupportClauses)retainedPublication.supportClauses();
		Assert.assertEquals(64, retainedRelation.headerCount());
		Assert.assertEquals(4096, retainedRelation.size());
		var retainedAuthority = retainedRelation.get(0);

		List<CandidateEmissionRealization> grownVariants = new ArrayList<>(initialVariants);
		grownVariants.add(exactSource(sourceOwner, pools.get(0), "added-existing-header"));
		grownVariants.add(exactSource(sourceOwner, pools.get(64), "source-64"));
		long consumedBefore = metrics.directWorkCount(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED);
		CandidateRuleFact grown = bind(closure, sourceFact(sourceOwner, grownVariants), retained,
			sourceHop, consumerHop, widths(pools, 66));
		CandidateRuleFact cold = bind(new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false),
			sourceFact(sourceOwner, grownVariants), consumer, sourceHop, consumerHop,
			widths(pools, 66));

		Assert.assertEquals("the >64 request guard must preserve exact ordered authority",
			expanded(cold), expanded(grown));
		Assert.assertTrue("bounded forwarding must use scalar support after the 64-request cap",
			realizations(grown).stream().noneMatch(realization ->
				realization.supportClauses() instanceof NativeContinuitySupportClauses));
		Assert.assertTrue("scalar fallback must preserve the first retained donor object",
			realizations(grown).stream().flatMap(realization -> realization.supportClauses().stream())
				.anyMatch(clause -> clause == retainedAuthority));
		Assert.assertTrue("the capped batch must consume its exact scalar proofs",
			metrics.directWorkCount(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED) > consumedBefore);
	}

	private static Map<DurableAnchorKey,Integer> widths(
		List<DurableAnchorKey> pools, int width) {
		Map<DurableAnchorKey,Integer> result = new LinkedHashMap<>();
		for(DurableAnchorKey pool : pools)
			result.put(pool, width);
		return result;
	}

	private static CandidateRuleFact bind(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, Map<DurableAnchorKey,Integer> widths) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod("bind",
			PlacementRelationClosure.class, CandidateRuleFact.class, CandidateRuleFact.class,
			DataOp.class, UnaryOp.class, Map.class, PlacementAnalysis.NodeShapeFact.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, closure, source, consumer,
			sourceHop, consumerHop, widths,
			new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
	}

	private static CandidateRuleFact sourceFact(CompiledHopKey owner,
		List<CandidateEmissionRealization> variants) throws Exception {
		return (CandidateRuleFact)invoke("sourceFact",
			new Class<?>[] {CompiledHopKey.class, List.class}, owner, variants);
	}

	private static CandidateRuleFact consumerFact(CompiledHopKey owner) throws Exception {
		return (CandidateRuleFact)invoke("consumerFact",
			new Class<?>[] {CompiledHopKey.class}, owner);
	}

	private static CandidateEmissionRealization exactSource(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage) throws Exception {
		return (CandidateEmissionRealization)invoke("exactSource",
			new Class<?>[] {CompiledHopKey.class, DurableAnchorKey.class, String.class},
			owner, pool, lineage);
	}

	private static CompiledHopKey fixtureKey(String name) throws Exception {
		return (CompiledHopKey)invoke("fixtureKey", new Class<?>[] {String.class}, name);
	}

	private static Object invoke(String name, Class<?>[] parameters, Object... arguments)
		throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method.invoke(null, arguments);
	}

	private static List<CandidateEmissionRealization> realizations(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().get(0).realizations();
	}

	private static List<String> expanded(CandidateRuleFact fact) {
		return realizations(fact).stream().flatMap(realization -> realization.supportClauses().stream()
			.map(clause -> realization.key().normalizedSignature() + '|' + clause.normalizedSignature()))
			.toList();
	}

	private static DurableAnchorKey pool(String id, long columns) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("shared-worker", List.of(0L, 0L), List.of(8L, columns))));
	}
}
