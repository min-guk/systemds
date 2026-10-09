/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeClauseOwnershipLookupTest {
	@Test
	public void cachedExplicitOwnershipPrecedesNativeHandleTraversal() throws Exception {
		CompiledHopKey owner = key("mixed-owner");
		NativeContinuitySupportClauses nativeClauses = relation(owner);
		CandidateRealizationSupportClause nativeClause = nativeClauses.get(0);
		CandidateRealizationSupportClause explicitClause =
			new CandidateRealizationSupportClause(List.of(), List.of());
		CandidateEmissionRealization nativeRealization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "a-native"), nativeClauses);
		CandidateEmissionRealization explicitRealization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "z-explicit"), List.of(explicitClause));
		CandidateRuleFact fact = fact(owner, List.of(nativeRealization, explicitRealization));
		NativePlacementContinuity resolver = resolver(fact);

		ownership(resolver).clear();
		long coldScans = counter(resolver, "dependencySkeletonOwnerFactScans");
		Assert.assertTrue(owns(resolver, fact, explicitClause));
		Assert.assertEquals("an uncached ordinary clause retains the exact cold owner scan",
			coldScans + 1, counter(resolver, "dependencySkeletonOwnerFactScans"));
		CountingHandles counted = new CountingHandles();
		counted.put(0, nativeClause);
		setField(nativeClauses, "handles", counted);

		Assert.assertTrue(owns(resolver, fact, explicitClause));
		Assert.assertEquals("a positive exact ownership hit must not walk unrelated native handles",
			0, counted.entrySetReads);
	}

	@Test
	public void cachedNegativeStillFindsLateNativeHandleButRejectsForeignAuthority() throws Exception {
		CompiledHopKey owner = key("late-owner");
		NativeContinuitySupportClauses nativeClauses = relation(owner);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "late-native"), nativeClauses);
		CandidateRuleFact fact = fact(owner, List.of(realization));
		NativePlacementContinuity resolver = resolver(fact);
		CandidateRealizationSupportClause absent =
			new CandidateRealizationSupportClause(List.of(), List.of());

		Assert.assertFalse(owns(resolver, fact, absent));
		Assert.assertTrue("the negative ownership result must be cached",
			ownership(resolver).containsKey(fact));
		Assert.assertEquals("the cached miss must precede native member materialization",
			0, nativeClauses.materializedHandleCount());
		CandidateRealizationSupportClause materialized = nativeClauses.get(0);
		Assert.assertEquals(1, nativeClauses.materializedHandleCount());
		Assert.assertTrue("a cached ordinary miss cannot hide a newly materialized native handle",
			owns(resolver, fact, materialized));
		CandidateRealizationSupportClause equalForeign = new CandidateRealizationSupportClause(
			materialized.proofDependencies(), materialized.inputBindings(),
			materialized.nativeWorkerPoolWitness(), materialized.nativeWorkerPoolLayoutExact());
		Assert.assertEquals(materialized, equalForeign);
		Assert.assertNotSame(materialized, equalForeign);
		Assert.assertFalse("ownership is identity based, not structural clause equality",
			owns(resolver, fact, equalForeign));

		CandidateRuleFact foreignFact = fact(owner, List.of(new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "foreign-explicit"),
			List.of(equalForeign))));
		Assert.assertNotSame(fact, foreignFact);
		Assert.assertFalse("an explicit clause in a foreign fact cannot borrow structural authority",
			owns(resolver, foreignFact, equalForeign));
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner) {
		CandidateRuleKey sourceRule = new CandidateRuleKey(key("source"), List.of());
		CandidateRealizationReference source = new CandidateRealizationReference(sourceRule,
			PlacementRealizationKey.nativeLineage(emission(), "source"));
		CandidateRealizationInputBinding binding =
			CandidateRealizationInputBinding.direct(0, source);
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, true, List.of(List.of(binding)));
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, output, true);
	}

	private static CandidateRuleFact fact(CompiledHopKey owner,
		List<CandidateEmissionRealization> realizations) {
		CandidateRuleKey key = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(emission(), FType.ROW, null, realizations)), "");
	}

	private static NativePlacementContinuity resolver(CandidateRuleFact fact) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(fact), List.of(), Map.of());
	}

	private static boolean owns(NativePlacementContinuity resolver,
		CandidateRuleFact fact, CandidateRealizationSupportClause clause) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"ownsCandidateClause", CandidateRuleFact.class, CandidateRealizationSupportClause.class);
		method.setAccessible(true);
		return (boolean)method.invoke(resolver, fact, clause);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRuleFact,Set<CandidateRealizationSupportClause>> ownership(
		NativePlacementContinuity resolver) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("ownedCandidateClausesByFact");
		field.setAccessible(true);
		return (Map<CandidateRuleFact,Set<CandidateRealizationSupportClause>>)field.get(resolver);
	}

	private static long counter(NativePlacementContinuity resolver, String name) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getLong(resolver);
	}

	private static void setField(Object owner, String name, Object value) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
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
			"native-clause-ownership", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-clause-ownership", "main", name, "compiled",
			region, name, name);
	}

	private static final class CountingHandles
		extends ConcurrentHashMap<Integer,CandidateRealizationSupportClause> {
		private int entrySetReads;
		@Override public Set<Map.Entry<Integer,CandidateRealizationSupportClause>> entrySet() {
			entrySetReads++;
			Set<Map.Entry<Integer,CandidateRealizationSupportClause>> delegate = super.entrySet();
			return new AbstractSet<>() {
				@Override public Iterator<Map.Entry<Integer,CandidateRealizationSupportClause>> iterator() {
					return delegate.iterator();
				}
				@Override public int size() { return delegate.size(); }
			};
		}
	}
}
