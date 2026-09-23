/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DirectSourceIndexRevisionTest {
	private static final String FINGERPRINT = "direct-source-index";
	private static final ControlRegionKey REGION = new ControlRegionKey(FINGERPRINT, "main",
		List.of("main"), "main", "compiled");
	private static final PlacementEmissionState FED = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);

	@Test
	public void replacementAndDeletionPreserveOtherOwnersAndReferenceMembership() throws Exception {
		CompiledHopKey a = key("a"), b = key("b");
		CandidateRuleFact a1 = fact(a, "first", "p1"), a2 = fact(a, "second", "p2");
		CandidateRuleFact b1 = fact(b, "other", "p3");
		Object index = index(List.of(a1, b1, a2));
		List<CandidateRealizationReference> originalB = nativeByParent(index, b);
		Assert.assertEquals(List.of(reference(a1), reference(a2)), nativeByParent(index, a));
		Assert.assertTrue(executable(index, reference(a1)));

		CandidateRuleFact replacement = fact(a, "replacement", "p4");
		revise(index, List.of(replacement, b1, a2), a);
		Assert.assertEquals(List.of(reference(replacement), reference(a2)), nativeByParent(index, a));
		Assert.assertSame("unchanged producer list is reused", originalB, nativeByParent(index, b));
		Assert.assertNull(realization(index, reference(a1)));
		Assert.assertFalse(executable(index, reference(a1)));
		Assert.assertTrue(executable(index, reference(replacement)));

		revise(index, List.of(failed(a), b1, failed(a)), a);
		Assert.assertTrue(nativeByParent(index, a).isEmpty());
		Assert.assertSame(originalB, nativeByParent(index, b));
		Assert.assertFalse(executable(index, reference(a2)));
		Assert.assertTrue(executable(index, reference(b1)));
	}

	@Test
	public void structurallyDuplicateOwnersRetainGlobalLastWinsOnRevision() throws Exception {
		CompiledHopKey first = key("shared"), second = key("shared");
		Assert.assertNotSame(first, second);
		CandidateRuleFact firstFact = fact(first, "same-route", "first-proof");
		CandidateRuleFact secondFact = fact(second, "same-route", "second-proof");
		Object index = index(List.of(firstFact, secondFact));
		Assert.assertEquals(secondFact.allowedEmissionFacts().get(0).realizations().get(0),
			realization(index, reference(firstFact)));
		CandidateRuleFact changedFirst = fact(first, "same-route", "new-first-proof");
		revise(index, List.of(changedFirst, secondFact), first);
		Assert.assertEquals("the later owner remains authoritative for an equal reference key",
			secondFact.allowedEmissionFacts().get(0).realizations().get(0),
			realization(index, reference(firstFact)));
	}

	@Test
	public void deletingMoreThanIntegerCacheRangeDuplicatesRemovesExecutableMembership() throws Exception {
		CompiledHopKey owner = key("many");
		CandidateRuleFact source = fact(owner, "same-route", "same-proof");
		Object index = index(Collections.nCopies(128, source));
		Assert.assertTrue(executable(index, reference(source)));
		CandidateRuleFact failed = failed(owner);
		revise(index, Collections.nCopies(128, failed), owner);
		Assert.assertFalse(executable(index, reference(source)));
	}

	private static CompiledHopKey key(String name) {
		return new CompiledHopKey(FINGERPRINT, "main", "main", "compiled", REGION, name, name);
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, String lineage, String proof) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		CandidateEmissionRealization realization = CandidateEmissionRealization.sourceLineage(FED, lineage);
		if(!proof.isEmpty())
			realization = new CandidateEmissionRealization(realization.key(),
				List.of(new PlacementProofKey(PlacementProofKind.SHAPE, owner, proof)), List.of());
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(FED, FType.FULL, null, List.of(realization))), "");
	}

	private static CandidateRuleFact failed(CompiledHopKey owner) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.RULE_ERROR, null,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), "failed"), List.of(), "failed");
	}

	private static CandidateRealizationReference reference(CandidateRuleFact fact) {
		return CandidateRealizationReference.of(fact.key(),
			fact.allowedEmissionFacts().get(0).realizations().get(0));
	}

	private static Class<?> sourceIndexClass() {
		for(Class<?> type : NeutralPlacementGraphBuilder.class.getDeclaredClasses())
			if(type.getSimpleName().equals("DirectSourceIndex"))
				return type;
		throw new AssertionError("DirectSourceIndex was not found");
	}

	private static Object index(List<CandidateRuleFact> facts) throws Exception {
		Constructor<?> constructor = sourceIndexClass().getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	private static Object invoke(Object index, String name, Class<?>[] types, Object... args)
		throws Exception {
		Method method = sourceIndexClass().getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(index, args);
	}

	private static void revise(Object index, List<CandidateRuleFact> facts, CompiledHopKey owner)
		throws Exception {
		Set<CompiledHopKey> changed = Collections.newSetFromMap(new IdentityHashMap<>());
		changed.add(owner);
		invoke(index, "nextRevision", new Class<?>[]{List.class, Set.class}, facts, changed);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> nativeByParent(Object index, CompiledHopKey owner)
		throws Exception {
		return (List<CandidateRealizationReference>) invoke(index, "nativeByParent",
			new Class<?>[]{CompiledHopKey.class}, owner);
	}

	private static CandidateEmissionRealization realization(Object index,
		CandidateRealizationReference reference) throws Exception {
		return (CandidateEmissionRealization) invoke(index, "nativeRealization",
			new Class<?>[]{CandidateRealizationReference.class}, reference);
	}

	private static boolean executable(Object index, CandidateRealizationReference reference)
		throws Exception {
		return (boolean) invoke(index, "executable", new Class<?>[]{String.class},
			reference.normalizedSignature());
	}
}
