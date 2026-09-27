/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class BaseCandidateOwnershipTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"base-ownership", "main", List.of("root"), "root", "compiled");
	private static final CompiledHopKey OWNER = new CompiledHopKey(
		"base-ownership", "main", "root", "compiled", REGION, "owner", "owner");
	private static final ValueVersionKey VERSION = new ValueVersionKey(
		"base-ownership", "value", REGION, 0, VersionKind.ORDINARY, List.of());
	private static final CompiledHopKey SOURCE = new CompiledHopKey(
		"base-ownership", "main", "root", "compiled", REGION, "source", "source");
	private static final CandidateRuleKey RULE = new CandidateRuleKey(
		OWNER, List.of(CandidateInputState.present(FType.ROW)));
	private static final PlacementState ROW_STATE = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState ROW = new PlacementEmissionState(ROW_STATE, false);
	private static final Node NODE = new Node(OWNER, NodeKind.OPERATION, VERSION,
		true, List.of(ROW_STATE), List.of(), List.of());

	@Test
	public void semanticShellPreservesDerivedRealizationsButRejectsIncompleteOrChangedRows() {
		CandidateRuleFact base = available(emission(ROW, "base"));
		CandidateRuleFact derived = available(emission(ROW, "derived"));

		Assert.assertTrue("realization/proof derivation may differ behind the same complete base shell",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base), List.of(derived)));
		Assert.assertFalse("a missing semantic slot must be rebuilt",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base), List.of()));
		Assert.assertFalse("an unavailable current row must be rebuilt",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base),
				List.of(profileError())));
		Assert.assertFalse("profile evidence is part of the exact semantic shell",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base),
				List.of(availableWithProfile(emission(ROW, "derived"), List.of(FType.COL)))));
		Assert.assertFalse("shape evidence is part of the exact semantic shell",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base),
				List.of(availableWithShape(emission(ROW, "derived"), Map.of("rows", "changed")))));
		Assert.assertFalse("capability evidence is part of the exact semantic shell",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base),
				List.of(availableWithCapability(emission(ROW, "derived"), "changed"))));
		Assert.assertFalse("an extra derived-FOUT emission sharing the coarse state is not base coverage",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base),
				List.of(available(List.of(emission(ROW, "derived"), derivedEmission())))));
		Assert.assertFalse("a stale privacy-excluded row must not hide a reappearing raw native source",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(base),
				List.of(fact(CandidateEvaluationStatus.PRIVACY_EXCLUDED, capability("base"), shape(Map.of()),
					new CandidateProfileFact(List.of(FType.ROW), ""), List.of(), "PRIVACY:PRIVATE"))));
	}

	@Test
	public void sameRowSourceReappearanceRestoresDerivedTargetBeforeRetention() {
		CandidateEmissionFact nativeSource = nativeSourceEmission();
		CandidateEmissionFact derived = derivedEmission();
		Assert.assertTrue("a derived target without its row-owned source is removed",
			NeutralPlacementGraphBuilder.sourceClosedCandidateEmissions(List.of(derived)).isEmpty());
		Assert.assertEquals("fresh raw reconstruction must re-evaluate source closure instead of trusting old status",
			List.of(nativeSource, derived),
			NeutralPlacementGraphBuilder.sourceClosedCandidateEmissions(List.of(nativeSource, derived)));
	}

	@Test
	public void retentionRequiresPreviouslySeenIdenticalBaseAndMatchingCurrentShell() throws Exception {
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		CandidateRuleFact base = available(emission(ROW, "base"));
		CandidateRuleFact derived = available(emission(ROW, "derived"));
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod("retainCompleteDerivedBase",
			Node.class, List.class, List.class, Node.class, List.class, List.class);
		method.setAccessible(true);

		Assert.assertFalse("first observation installs base ownership rather than retaining prior output",
			invoke(method, builder, derived, base));
		Assert.assertTrue("the exact repeated base retains a complete derived shell",
			invoke(method, builder, derived, base));
		CandidateRuleFact changedBase = availableWithShape(emission(ROW, "base"), Map.of("rows", "changed"));
		Assert.assertFalse("a changed fresh base invalidates retention",
			invoke(method, builder, derived, changedBase));
		Assert.assertFalse("returning to the earlier base is another ownership change",
			invoke(method, builder, derived, base));
		Assert.assertTrue("the restored base becomes retainable only after its fresh reinstall",
			invoke(method, builder, derived, base));
	}

	@Test
	public void nativeReconciliationKeepsFreshInventoryAndOnlyMatchingTentativeSupport() throws Exception {
		CandidateEmissionFact freshNative = emission(ROW, "fresh");
		CandidateEmissionFact priorNative = emission(ROW, "grounded");
		CandidateRuleFact fresh = available(freshNative);
		CandidateRuleFact prior = available(List.of(priorNative, derivedEmission()));
		List<CandidateRuleFact> reconciled = reconcile(List.of(fresh), List.of(prior));
		Assert.assertEquals("current-only materialization is not adopted", 1,
			reconciled.get(0).allowedEmissionFacts().size());
		Assert.assertSame("matching native support survives only as a tentative seed", priorNative,
			reconciled.get(0).allowedEmissionFacts().get(0));
		Assert.assertEquals(fresh.key(), reconciled.get(0).key());
		Assert.assertEquals(fresh.profile(), reconciled.get(0).profile());
		Assert.assertFalse("whole-base retention still rejects the extra emission",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(fresh), List.of(prior)));
	}

	@Test
	public void missingNativeSiblingDoesNotResetMatchingLocalAndDerivedSupports() throws Exception {
		CandidateEmissionFact nativeFresh = emission(ROW, "native-fresh");
		CandidateEmissionFact localFresh = nativeSourceEmission();
		CandidateEmissionFact derivedFresh = certifiedDerivedEmission();
		CandidateRealizationInputBinding binding = binding();
		CandidateEmissionFact localPrior = supported(localFresh, binding);
		CandidateEmissionFact derivedPrior = supported(derivedFresh, binding);
		CandidateRuleFact fresh = available(List.of(nativeFresh, localFresh, derivedFresh));
		CandidateRuleFact prior = available(List.of(localPrior, derivedPrior));
		Assert.assertFalse("a missing native sibling still requires fresh inventory reconstruction",
			NeutralPlacementGraphBuilder.hasCompleteBaseEmissionCoverage(List.of(fresh), List.of(prior)));
		CandidateRuleFact result = reconcile(List.of(fresh), List.of(prior)).get(0);
		Assert.assertEquals(3, result.allowedEmissionFacts().size());
		Assert.assertTrue("missing native generation is restored", result.allowedEmissionFacts().contains(nativeFresh));
		Assert.assertTrue("independently owned local support must not be reset by its native sibling",
			result.allowedEmissionFacts().contains(localPrior));
		CandidateEmissionFact retainedDerived = result.allowedEmissionFacts().stream()
			.filter(emission -> emission.derivedFoutAction() != null).findFirst().orElseThrow();
		Assert.assertEquals("derived output retains its template and current-action-owned support", 2,
			retainedDerived.realizations().get(0).supportClauses().size());
		Assert.assertTrue(hasBinding(result, binding));
		Assert.assertEquals("reconciliation is idempotent behind an unchanged generation envelope", result,
			reconcile(List.of(fresh), List.of(result)).get(0));
	}

	@Test
	public void derivedReconciliationRejectsForeignOutputAndActionCertificates() throws Exception {
		CandidateEmissionFact fresh = certifiedDerivedEmission();
		CandidateEmissionRealization template = fresh.realizations().get(0);
		CandidateEmissionFact noCertificate = new CandidateEmissionFact(fresh.emissionState(),
			fresh.executionFType(), fresh.derivedFoutAction(), List.of(new CandidateEmissionRealization(
				template.key(), List.of(), List.of(binding()))));
		CandidateEmissionFact otherOutput = new CandidateEmissionFact(fresh.emissionState(),
			fresh.executionFType(), fresh.derivedFoutAction(), List.of(CandidateEmissionRealization.durable(
				fresh.emissionState(), anchor("foreign-output"),
				template.supportClauses().get(0).proofDependencies(), List.of(binding()))));
		DerivedFoutMaterializationActionKey changed = new DerivedFoutMaterializationActionKey(
			OWNER, VERSION, RULE, nativeSourceState(), ROW_STATE, anchor("changed-action"),
			OWNER, FType.ROW, FType.ROW, REGION.normalizedSignature());
		CandidateEmissionFact otherAction = new CandidateEmissionFact(fresh.emissionState(),
			fresh.executionFType(), changed, supported(fresh, binding()).realizations());
		for(CandidateEmissionFact prior : List.of(noCertificate, otherOutput, otherAction)) {
			CandidateRuleFact result = reconcile(List.of(available(fresh)), List.of(available(prior))).get(0);
			Assert.assertEquals("coarse output/action equality does not authorize foreign support",
				available(fresh), result);
		}
	}

	@Test
	public void retainedLocalSupportStillRequiresItsExactLiveSource() throws Exception {
		CandidateRealizationInputBinding binding = binding();
		CandidateEmissionFact fresh = nativeSourceEmission();
		CandidateRuleFact result = reconcile(List.of(available(fresh)),
			List.of(available(supported(fresh, binding)))).get(0);
		Assert.assertTrue("fixture must preserve the tentative local support", hasBinding(result, binding));
		Method prune = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"removeUngroundedStagingRealizations", List.class);
		prune.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> withdrawn = (List<CandidateRuleFact>)prune.invoke(null, List.of(result));
		Assert.assertFalse("retention cannot rescue an absent exact source", hasBinding(withdrawn.get(0), binding));
		CandidateRuleFact source = sourceFact();
		CompiledHopKey foreignOwner = new CompiledHopKey("base-ownership", "main", "root", "compiled",
			REGION, "foreign-source", "foreign-source");
		CandidateRuleFact foreign = new CandidateRuleFact(new CandidateRuleKey(foreignOwner, List.of()),
			source.status(), source.capability(), source.shapeProof(), source.profile(),
			source.allowedEmissionFacts(), source.failureCode());
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> notRescued = (List<CandidateRuleFact>)prune.invoke(null, List.of(foreign, result));
		Assert.assertFalse("equal layout on a different exact source cannot rescue a retained binding",
			hasBinding(notRescued.get(1), binding));
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> restored = (List<CandidateRuleFact>)prune.invoke(null, List.of(source, result));
		Assert.assertTrue("the exact source allows regenerated tentative support", hasBinding(restored.get(1), binding));
	}

	private static CandidateEmissionFact certifiedDerivedEmission() {
		CandidateEmissionFact emission = derivedEmission();
		PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR,
			OWNER, "derived-fout:" + emission.derivedFoutAction().normalizedSignature());
		return new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
			emission.derivedFoutAction(), List.of(new CandidateEmissionRealization(
				emission.realizations().get(0).key(), List.of(proof), List.of())));
	}

	private static CandidateEmissionFact supported(CandidateEmissionFact emission,
		CandidateRealizationInputBinding binding) {
		CandidateEmissionRealization template = emission.realizations().get(0);
		return new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
			emission.derivedFoutAction(), List.of(new CandidateEmissionRealization(template.key(),
				template.supportClauses().get(0).proofDependencies(), List.of(binding))));
	}

	private static CandidateRuleFact sourceFact() {
		CandidateRuleKey rule = new CandidateRuleKey(SOURCE, List.of());
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE, capability("source"),
			shape(Map.of()), new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(emission(ROW, "source")), "");
	}

	private static CandidateRealizationInputBinding binding() {
		CandidateRuleFact source = sourceFact();
		return CandidateRealizationInputBinding.direct(0, CandidateRealizationReference.of(source.key(),
			source.allowedEmissionFacts().get(0).realizations().get(0)));
	}

	private static boolean hasBinding(CandidateRuleFact fact, CandidateRealizationInputBinding binding) {
		return fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.anyMatch(clause -> clause.inputBindings().contains(binding));
	}

	@Test
	public void nativeReconciliationRestoresMissingSourcesAndRejectsChangedAuthority() throws Exception {
		CandidateRuleFact fresh = available(emission(ROW, "fresh"));
		for(CandidateRuleFact prior : List.of(available(derivedEmission()), profileError(),
			availableWithShape(emission(ROW, "old"), Map.of("rows", "changed")),
			availableWithCapability(emission(ROW, "old"), "changed"),
			availableWithProfile(emission(ROW, "old"), List.of(FType.COL))))
			Assert.assertSame("missing or changed native authority must reinstall the fresh row", fresh,
				reconcile(List.of(fresh), List.of(prior)).get(0));
		CandidateRuleFact lout = available(nativeSourceEmission());
		Assert.assertSame("equal local support remains unchanged", lout,
			reconcile(List.of(lout), List.of(lout)).get(0));
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> reconcile(List<CandidateRuleFact> fresh,
		List<CandidateRuleFact> prior) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"reconcilePhysicalSupports", List.class, List.class);
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(null, fresh, prior);
	}

	private static boolean invoke(Method method, NeutralPlacementGraphBuilder builder,
		CandidateRuleFact current, CandidateRuleFact fresh) throws Exception {
		return (boolean)method.invoke(builder, NODE, List.of(RULE), List.of(current),
			NODE, List.of(RULE), List.of(fresh));
	}

	private static CandidateEmissionFact emission(PlacementEmissionState emission, String id) {
		return new CandidateEmissionFact(emission, FType.ROW, null,
			List.of(CandidateEmissionRealization.durable(emission, anchor(id), List.of(), List.of())));
	}

	private static CandidateEmissionFact derivedEmission() {
		PlacementEmissionState derived = new PlacementEmissionState(ROW_STATE, true);
		PlacementState source = nativeSourceState();
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			OWNER, VERSION, RULE, source, ROW_STATE, anchor("derived-action"),
			OWNER, FType.ROW, FType.ROW, REGION.normalizedSignature());
		return new CandidateEmissionFact(derived, FType.ROW, action,
			List.of(CandidateEmissionRealization.durable(derived, anchor("derived-output"), List.of(), List.of())));
	}

	private static CandidateEmissionFact nativeSourceEmission() {
		PlacementEmissionState source = new PlacementEmissionState(nativeSourceState(), false);
		return new CandidateEmissionFact(source, FType.ROW);
	}

	private static PlacementState nativeSourceState() {
		return new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	}

	private static CandidateRuleFact available(CandidateEmissionFact emission) {
		return available(List.of(emission));
	}

	private static CandidateRuleFact available(List<CandidateEmissionFact> emissions) {
		return fact(CandidateEvaluationStatus.AVAILABLE, capability("base"), shape(Map.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), emissions, "");
	}

	private static CandidateRuleFact availableWithProfile(CandidateEmissionFact emission,
		List<FType> outputs) {
		return fact(CandidateEvaluationStatus.AVAILABLE, capability("base"), shape(Map.of()),
			new CandidateProfileFact(outputs, ""), List.of(emission), "");
	}

	private static CandidateRuleFact availableWithShape(CandidateEmissionFact emission,
		Map<String,String> consulted) {
		return fact(CandidateEvaluationStatus.AVAILABLE, capability("base"), shape(consulted),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
	}

	private static CandidateRuleFact availableWithCapability(CandidateEmissionFact emission,
		String detail) {
		return fact(CandidateEvaluationStatus.AVAILABLE, capability(detail), shape(Map.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
	}

	private static CandidateRuleFact profileError() {
		return fact(CandidateEvaluationStatus.PROFILE_ERROR, capability("base"), shape(Map.of()),
			new CandidateProfileFact(List.of(), "profile-error"), List.of(), "PROFILE_ERROR");
	}

	private static CandidateRuleFact fact(CandidateEvaluationStatus status,
		CandidateCapabilityFact capability, CandidateShapeProofFact shape, CandidateProfileFact profile,
		List<CandidateEmissionFact> emissions, String failure) {
		return new CandidateRuleFact(RULE, status, capability, shape, profile, emissions, failure);
	}

	private static CandidateCapabilityFact capability(String detail) {
		return new CandidateCapabilityFact(OpCategory.AGG_UNARY, "fixture", ExecType.FED,
			FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, detail, List.of());
	}

	private static CandidateShapeProofFact shape(Map<String,String> consulted) {
		return new CandidateShapeProofFact(consulted, List.of(), List.of());
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(4L, 2L))));
	}
}
