/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class RelocationProofScopeReuseTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"relocation-proof-scope", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = key("owner");
	private static final PlacementState LOCAL_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState FOUT_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState LOCAL_EMISSION =
		new PlacementEmissionState(LOCAL_STATE, false);

	@Test
	public void oneBindScopeSharesRelocationProofsAcrossProductsButNotOwnersOrInvocations()
		throws Exception {
		Object scope = newScope();
		RelocationActionKey firstAction = action("shared");
		RelocationActionKey equalAction = action("shared");
		Assert.assertEquals(firstAction, equalAction);
		Assert.assertNotSame(firstAction, equalAction);
		CandidateRealizationInputBinding first = binding("source-a", firstAction);
		CandidateRealizationInputBinding equal = binding("source-b", equalAction);
		CandidateEmissionFact emission = new CandidateEmissionFact(LOCAL_EMISSION, FType.ROW);

		List<CandidateEmissionRealization> firstProduct = generateScoped(
			scope, OWNER, emission, List.of(List.of(first)));
		List<CandidateEmissionRealization> secondProduct = generateScoped(
			scope, OWNER, emission, List.of(List.of(equal)));
		Assert.assertEquals(generateCold(OWNER, emission, List.of(List.of(first))), firstProduct);
		Assert.assertEquals(generateCold(OWNER, emission, List.of(List.of(equal))), secondProduct);
		PlacementProofKey firstProof = realizationProof(firstProduct, PlacementProofKind.NATIVE_CONTINUITY);
		PlacementProofKey secondProof = realizationProof(secondProduct, PlacementProofKind.NATIVE_CONTINUITY);
		Assert.assertSame("equal actions must share one immutable proof across products",
			firstProof, secondProof);

		CompiledHopKey otherOwner = key("other-owner");
		PlacementProofKey otherOwnerProof = realizationProof(generateScoped(
			scope, otherOwner, emission, List.of(List.of(first))), PlacementProofKind.NATIVE_CONTINUITY);
		Assert.assertNotSame(firstProof, otherOwnerProof);
		Assert.assertNotEquals(firstProof, otherOwnerProof);
		PlacementProofKey otherInvocationProof = realizationProof(generateScoped(
			newScope(), OWNER, emission, List.of(List.of(first))), PlacementProofKind.NATIVE_CONTINUITY);
		Assert.assertEquals(firstProof, otherInvocationProof);
		Assert.assertNotSame("the memo lifetime must end with its bind invocation",
			firstProof, otherInvocationProof);
	}

	@Test
	public void derivedClausesShareCompleteActionAndRelocationProofsWithLegacyParity()
		throws Exception {
		Object scope = newScope();
		DerivedFoutMaterializationActionKey firstDerived = derivedAction("derived");
		DerivedFoutMaterializationActionKey equalDerived = derivedAction("derived");
		RelocationActionKey firstRelocation = action("relocation");
		RelocationActionKey equalRelocation = action("relocation");
		CandidateRealizationSupportClause firstClause = clause(List.of(
			binding("source-a", firstRelocation), binding("source-z", action("z-last"))));
		CandidateRealizationSupportClause equalClause = clause(List.of(
			binding("source-b", equalRelocation), binding("source-z", action("z-last"))));

		List<CandidateRealizationSupportClause> first = clausesScoped(
			scope, OWNER, firstDerived, List.of(firstClause));
		List<CandidateRealizationSupportClause> second = clausesScoped(
			scope, OWNER, equalDerived, List.of(equalClause));
		Assert.assertEquals(clausesCold(OWNER, firstDerived, List.of(firstClause)), first);
		Assert.assertEquals(clausesCold(OWNER, equalDerived, List.of(equalClause)), second);
		Assert.assertSame("equal complete derived actions must share their owner proof",
			clauseProof(first, PlacementProofKind.DURABLE_ANCHOR),
			clauseProof(second, PlacementProofKind.DURABLE_ANCHOR));
		Assert.assertSame("equal relocation actions must share their proof across carried clauses",
			proof(first, PlacementProofKind.NATIVE_CONTINUITY, "relocation"),
			proof(second, PlacementProofKind.NATIVE_CONTINUITY, "relocation"));
		List<CandidateRealizationSupportClause> changedAction = clausesScoped(
			scope, OWNER, derivedAction("other-derived"), List.of(firstClause));
		Assert.assertNotSame("a different complete derived action retains distinct authority",
			clauseProof(first, PlacementProofKind.DURABLE_ANCHOR),
			clauseProof(changedAction, PlacementProofKind.DURABLE_ANCHOR));
		Assert.assertNotEquals(clauseProof(first, PlacementProofKind.DURABLE_ANCHOR),
			clauseProof(changedAction, PlacementProofKind.DURABLE_ANCHOR));
		Assert.assertSame("derived-action separation must not split the same relocation proof",
			proof(first, PlacementProofKind.NATIVE_CONTINUITY, "relocation"),
			proof(changedAction, PlacementProofKind.NATIVE_CONTINUITY, "relocation"));

		CompiledHopKey otherOwner = key("derived-other-owner");
		List<CandidateRealizationSupportClause> other = clausesScoped(
			scope, otherOwner, firstDerived, List.of(firstClause));
		Assert.assertNotSame(clauseProof(first, PlacementProofKind.DURABLE_ANCHOR),
			clauseProof(other, PlacementProofKind.DURABLE_ANCHOR));
		Assert.assertNotSame(proof(first, PlacementProofKind.NATIVE_CONTINUITY, "relocation"),
			proof(other, PlacementProofKind.NATIVE_CONTINUITY, "relocation"));
	}

	private static Object newScope() throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$RelocationProofScope");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> generateScoped(Object scope,
		CompiledHopKey owner, CandidateEmissionFact emission,
		List<List<CandidateRealizationInputBinding>> choices) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("generateRelocationBindingProduct",
			CompiledHopKey.class, CandidateEmissionFact.class, List.class, DurableAnchorKey.class,
			boolean.class, DurableAnchorKey.class, SearchSpaceMetrics.class, scope.getClass());
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(
			null, owner, emission, choices, null, false, null, null, scope);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> generateCold(CompiledHopKey owner,
		CandidateEmissionFact emission, List<List<CandidateRealizationInputBinding>> choices)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("generateRelocationBindingProduct",
			CompiledHopKey.class, CandidateEmissionFact.class, List.class, DurableAnchorKey.class,
			boolean.class, DurableAnchorKey.class, SearchSpaceMetrics.class);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(
			null, owner, emission, choices, null, false, null, null);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationSupportClause> clausesScoped(Object scope,
		CompiledHopKey owner, DerivedFoutMaterializationActionKey action,
		List<CandidateRealizationSupportClause> clauses) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("canonicalDerivedFoutClauses",
			CompiledHopKey.class, DerivedFoutMaterializationActionKey.class, List.class, scope.getClass());
		method.setAccessible(true);
		return (List<CandidateRealizationSupportClause>)method.invoke(null, owner, action, clauses, scope);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationSupportClause> clausesCold(CompiledHopKey owner,
		DerivedFoutMaterializationActionKey action, List<CandidateRealizationSupportClause> clauses)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("canonicalDerivedFoutClauses",
			CompiledHopKey.class, DerivedFoutMaterializationActionKey.class, List.class);
		method.setAccessible(true);
		return (List<CandidateRealizationSupportClause>)method.invoke(null, owner, action, clauses);
	}

	private static PlacementProofKey realizationProof(List<CandidateEmissionRealization> realizations,
		PlacementProofKind kind) {
		return realizations.stream().flatMap(realization -> realization.supportClauses().stream())
			.flatMap(clause -> clause.proofDependencies().stream())
			.filter(candidate -> candidate.kind() == kind).findFirst().orElseThrow();
	}

	private static PlacementProofKey clauseProof(List<CandidateRealizationSupportClause> clauses,
		PlacementProofKind kind) {
		return clauses.stream().flatMap(clause -> clause.proofDependencies().stream())
			.filter(candidate -> candidate.kind() == kind).findFirst().orElseThrow();
	}

	private static PlacementProofKey proof(List<CandidateRealizationSupportClause> clauses,
		PlacementProofKind kind, String detailFragment) {
		return clauses.stream().flatMap(clause -> clause.proofDependencies().stream())
			.filter(candidate -> candidate.kind() == kind
				&& candidate.authoritySignature().contains(detailFragment))
			.findFirst().orElseThrow();
	}

	private static CandidateRealizationSupportClause clause(
		List<CandidateRealizationInputBinding> bindings) {
		return new CandidateRealizationSupportClause(List.of(), bindings);
	}

	private static CandidateRealizationInputBinding binding(String sourceId,
		RelocationActionKey action) {
		CandidateRuleKey rule = new CandidateRuleKey(key(sourceId), List.of());
		CandidateRealizationReference source = CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.local(LOCAL_EMISSION));
		return CandidateRealizationInputBinding.relocation(0, source, action);
	}

	private static RelocationActionKey action(String id) {
		return new RelocationActionKey(version("source", 0), FOUT_STATE, FType.ROW,
			anchor(id), REGION.normalizedSignature(), List.of(OWNER));
	}

	private static DerivedFoutMaterializationActionKey derivedAction(String id) {
		CandidateRuleKey rule = new CandidateRuleKey(OWNER,
			List.of(CandidateInputState.present(FType.ROW)));
		return new DerivedFoutMaterializationActionKey(OWNER, version("owner", 1), rule,
			LOCAL_STATE, FOUT_STATE, anchor(id), OWNER, FType.ROW, FType.ROW,
			REGION.normalizedSignature());
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition("localhost:1235", List.of(4L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("relocation-proof-scope", "main", "root", "compiled",
			REGION, id, id);
	}

	private static ValueVersionKey version(String id, int ordinal) {
		return new ValueVersionKey("relocation-proof-scope", id, REGION, ordinal,
			VersionKind.ORDINARY, List.of());
	}
}
