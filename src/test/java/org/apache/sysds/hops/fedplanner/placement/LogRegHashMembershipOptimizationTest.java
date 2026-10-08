/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class LogRegHashMembershipOptimizationTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"logreg-hash", "main", List.of("root"), "call", "compiled");
	private static final PlacementEmissionState LOCAL_EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);

	private record ProofHashOracle(PlacementProofKind kind, CompiledHopKey owner,
		String authoritySignature) { }
	private record ClauseHashOracle(List<PlacementProofKey> proofDependencies,
		List<PlacementIdentity.CandidateRealizationInputBinding> inputBindings,
		DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact) { }

	@Test
	public void proofKeyPreservesGeneratedRecordContractIncludingCollisionAndZeroHash() {
		CompiledHopKey owner = key("owner");
		PlacementProofKey aa = new PlacementProofKey(PlacementProofKind.SHAPE, owner, "Aa");
		PlacementProofKey bb = new PlacementProofKey(PlacementProofKind.SHAPE, owner, "BB");
		Assert.assertEquals("fixture uses a real String hash collision", aa.hashCode(), bb.hashCode());
		Assert.assertNotEquals(aa, bb);
		assertLegacyProofContract(aa);
		assertLegacyProofContract(bb);

		String zeroAuthority = stringWithHash(-31 * (31 * PlacementProofKind.PRIVACY.hashCode()));
		PlacementProofKey zero = new PlacementProofKey(
			PlacementProofKind.PRIVACY, null, zeroAuthority);
		Assert.assertEquals(new ProofHashOracle(
			PlacementProofKind.PRIVACY, null, zeroAuthority).hashCode(), zero.hashCode());
		Assert.assertEquals("the cached-hash implementation must not reserve zero as unset",
			0, zero.hashCode());
		Assert.assertEquals(0, zero.hashCode());
	}

	@Test
	public void normalizedTextFactoryPreservesLegacyProofContractForExactUtf16Content() {
		CompiledHopKey owner = key("normalized-owner");
		assertNormalizedFactoryRejectionMatchesString(owner,
			PlacementAnalysis.NormalizedText.literal(""));
		assertNormalizedFactoryRejectionMatchesString(owner,
			new PlacementAnalysis.NormalizedTextBuilder().append(" \t").append("\n").build());

		PlacementAnalysis.NormalizedText zero = PlacementAnalysis.NormalizedText.literal("\u0000\u0000");
		Assert.assertEquals(0, zero.hashCode());
		assertNormalizedFactoryParity(owner, zero);
		PlacementProofKey aa = assertNormalizedFactoryParity(owner,
			new PlacementAnalysis.NormalizedTextBuilder().append("A").append("a").build());
		PlacementProofKey bb = assertNormalizedFactoryParity(owner,
			new PlacementAnalysis.NormalizedTextBuilder().append("B").append("B").build());
		Assert.assertEquals(aa.hashCode(), bb.hashCode());
		Assert.assertNotEquals(aa, bb);

		assertNormalizedFactoryParity(owner, new PlacementAnalysis.NormalizedTextBuilder()
			.append("\ud83d").append("\ude00|\u03bb\u0000\ud800").append("\udc00").build());
		PlacementAnalysis.NormalizedText shared = new PlacementAnalysis.NormalizedTextBuilder()
			.append("segment-Aa|").append(PlacementAnalysis.NormalizedText.literal("\ud83d\ude00\u0000"))
			.append("|tail").build();
		PlacementAnalysis.NormalizedText longSegmented = shared;
		for(int level = 0; level < 15; level++)
			longSegmented = new PlacementAnalysis.NormalizedTextBuilder()
				.append(longSegmented).append(shared).append(longSegmented).build();
		assertNormalizedFactoryParity(owner, longSegmented);
	}

	@Test
	public void directPublicationCachesSignatureAndReleasesStructuralProofRope() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		try {
			NativeContinuityProof proof = new NativeContinuityProof(
				anchor("lifecycle-seed"), anchor("lifecycle-output"), true, List.of());
			PlacementAnalysis.NormalizedText structural = proofText(proof);
			Assert.assertNull(proofString(proof));
			Assert.assertNull(materialized(structural));
			PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
			CandidateEmissionRealization publication = directNativePublication(
				new PlacementRelationClosure(null, null, null, false,
					NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false),
				proof, key("lifecycle-owner"), emission, anchor("published-output"));

			String cached = proofString(proof);
			Assert.assertNotNull("publication must preserve proof-local signature caching", cached);
			Assert.assertSame("the factory materialization becomes the proof's cached String",
				cached, materialized(structural));
			Assert.assertSame(cached, proof.normalizedSignature());
			Assert.assertNotSame("publication must release the structural binding/reference rope",
				structural, proofText(proof));
			Assert.assertNull("the replacement is a literal descriptor, not another String copy",
				materialized(proofText(proof)));
			Assert.assertSame(cached, publication.requireSingletonSupportClause()
				.proofDependencies().get(0).authoritySignature());
		}
		finally {
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void directPublicationReusesExplicitlyWarmProofStringIdentity() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		try {
			NativeContinuityProof proof = new NativeContinuityProof(
				anchor("warm-seed"), anchor("warm-output"), true, List.of());
			String warm = proof.normalizedSignature();
			PlacementAnalysis.NormalizedText literal = proofText(proof);
			Assert.assertNull(materialized(literal));
			CandidateEmissionRealization publication = publish(proof, "warm-owner");
			Assert.assertSame(warm, proofString(proof));
			Assert.assertSame("warm publication must not retain a newly flattened alias",
				warm, publication.requireSingletonSupportClause()
					.proofDependencies().get(0).authoritySignature());
			Assert.assertSame(literal, proofText(proof));
			Assert.assertNull(materialized(proofText(proof)));
		}
		finally {
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void directPublicationReusesConstructorStructuralCacheHitStringIdentity() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		try {
			DurableAnchorKey seed = anchor("cache-hit-seed");
			DurableAnchorKey output = anchor("cache-hit-output");
			NativeContinuityProof first = new NativeContinuityProof(seed, output, true, List.of());
			String cached = first.normalizedSignature();
			NativeContinuityProof hit = new NativeContinuityProof(seed, output, true, List.of());
			Assert.assertSame("equal proof construction must recover the structural cached String",
				cached, proofString(hit));
			Assert.assertNull(materialized(proofText(hit)));
			CandidateEmissionRealization publication = publish(hit, "cache-hit-owner");
			Assert.assertSame(cached, proofString(hit));
			Assert.assertSame("cache-hit publication must keep the recovered String identity",
				cached, publication.requireSingletonSupportClause()
					.proofDependencies().get(0).authoritySignature());
			Assert.assertNull(materialized(proofText(hit)));
		}
		finally {
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void explicitClausePreservesLegacyHashAndDefensiveSnapshots() {
		List<PlacementProofKey> mutable = new ArrayList<>(List.of(
			new PlacementProofKey(PlacementProofKind.VALUE_IDENTITY, key("source"), "proof")));
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(
			mutable, List.of());
		int expected = new ClauseHashOracle(clause.proofDependencies(), clause.inputBindings(),
			null, true).hashCode();
		mutable.clear();
		Assert.assertEquals(1, clause.proofDependencies().size());
		Assert.assertEquals(expected, clause.hashCode());
		Assert.assertEquals(expected, clause.hashCode());
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> clause.proofDependencies().clear());
	}

	@Test
	public void indexedAndExplicitClausesKeepEqualLegacyHashes() {
		CandidateRealizationSupportClause first = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, key("source-a"), "Aa")), List.of());
		CandidateRealizationSupportClause second = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, key("source-a"), "BB")), List.of());
		Assert.assertEquals("unequal clauses intentionally collide", first.hashCode(), second.hashCode());
		Assert.assertNotEquals(first, second);
		IndexedSupportClauses indexed = IndexedSupportClauses.fromCanonical(
			List.of(first, second).stream().sorted().toList());
		for(int row = 0; row < indexed.size(); row++) {
			CandidateRealizationSupportClause handle = indexed.get(row);
			CandidateRealizationSupportClause explicit = indexed.proofsAt(row).get(0)
				.authoritySignature().equals("Aa") ? first : second;
			Assert.assertEquals(explicit, handle);
			Assert.assertEquals(explicit.hashCode(), handle.hashCode());
			Assert.assertEquals(handle.hashCode(), handle.hashCode());
		}
	}

	@Test
	public void groundedMembershipKeepsCollisionsDuplicatesContentAndOwnerIdentityExact()
		throws Exception {
		CompiledHopKey owner = key("owner");
		CompiledHopKey equalForeignOwner = key("owner");
		PlacementProofKey retainedProof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, "Aa");
		CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.direct(0,
			new CandidateRealizationReference(new CandidateRuleKey(key("source"), List.of()),
				PlacementRealizationKey.local(LOCAL_EMISSION)));
		CandidateEmissionRealization retained = CandidateEmissionRealization.local(
			LOCAL_EMISSION, List.of(retainedProof), List.of(binding));
		CandidateEmissionFact prior = new CandidateEmissionFact(
			LOCAL_EMISSION, null, null, List.of(retained));
		Object prepared = prepare(prior);

		CandidateEmissionRealization equalContent = CandidateEmissionRealization.local(
			LOCAL_EMISSION, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, owner, "Aa")), List.of(binding));
		Assert.assertTrue(contains(prepared, List.of(retained, equalContent)));
		Assert.assertTrue("duplicate queries retain old membership semantics",
			contains(prepared, List.of(retained, equalContent, equalContent)));

		CandidateEmissionRealization collision = CandidateEmissionRealization.local(
			LOCAL_EMISSION, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, owner, "BB")), List.of(binding));
		Assert.assertEquals(retained.supportClauses().get(0).hashCode(),
			collision.supportClauses().get(0).hashCode());
		Assert.assertFalse(contains(prepared, List.of(retained, collision)));

		CandidateEmissionRealization foreignAuthority = CandidateEmissionRealization.local(
			LOCAL_EMISSION, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, equalForeignOwner, "Aa")), List.of(binding));
		Assert.assertEquals(retained.supportClauses().get(0), foreignAuthority.supportClauses().get(0));
		Assert.assertFalse("equal content cannot replace owner identity authority",
			contains(prepared, List.of(retained, foreignAuthority)));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void directInputLookupPreservesBucketOrderAndOwnerIdentity() throws Exception {
		CompiledHopKey owner = key("lookup-owner");
		CompiledHopKey equalForeignOwner = key("lookup-owner");
		CandidateRealizationInputBinding first = directBinding(owner);
		CandidateRealizationInputBinding second = directBinding(owner);
		Class<?> type = Class.forName(
			PlacementRelationClosure.class.getName() + "$DirectInputLookup");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		Object lookup = constructor.newInstance(List.of(first, second));
		Method candidates = type.getDeclaredMethod(
			"candidates", int.class, CompiledHopKey.class,
			org.apache.sysds.hops.fedplanner.FTypes.FType.class);
		candidates.setAccessible(true);
		List<CandidateRealizationInputBinding> retained =
			(List<CandidateRealizationInputBinding>)candidates.invoke(lookup, 0, owner, null);
		Assert.assertEquals(2, retained.size());
		Assert.assertSame(first, retained.get(0));
		Assert.assertSame(second, retained.get(1));
		Assert.assertTrue(((List<?>)candidates.invoke(lookup, 0, equalForeignOwner, null)).isEmpty());
		Assert.assertTrue(((List<?>)candidates.invoke(lookup, 1, owner, null)).isEmpty());
	}

	private static void assertLegacyProofContract(PlacementProofKey proof) {
		ProofHashOracle oracle = new ProofHashOracle(
			proof.kind(), proof.owner(), proof.authoritySignature());
		Assert.assertEquals(oracle.hashCode(), proof.hashCode());
		Assert.assertEquals("PlacementProofKey[kind=" + proof.kind() + ", owner=" + proof.owner()
			+ ", authoritySignature=" + proof.authoritySignature() + "]", proof.toString());
		Assert.assertEquals(proof, new PlacementProofKey(
			proof.kind(), proof.owner(), proof.authoritySignature()));
		Assert.assertNotEquals(proof, null);
		Assert.assertNotEquals(proof, oracle);
	}

	private static void assertNormalizedFactoryRejectionMatchesString(CompiledHopKey owner,
		PlacementAnalysis.NormalizedText text) {
		String authority = text.materialize();
		IllegalArgumentException viaString = Assert.assertThrows(IllegalArgumentException.class,
			() -> new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, owner, authority));
		IllegalArgumentException viaText = Assert.assertThrows(IllegalArgumentException.class,
			() -> PlacementProofKey.fromNormalizedText(
				PlacementProofKind.NATIVE_CONTINUITY, owner, text));
		Assert.assertEquals(viaString.getMessage(), viaText.getMessage());
	}

	private static PlacementProofKey assertNormalizedFactoryParity(CompiledHopKey owner,
		PlacementAnalysis.NormalizedText text) {
		PlacementProofKey actual = PlacementProofKey.fromNormalizedText(
			PlacementProofKind.NATIVE_CONTINUITY, owner, text);
		String authority = actual.authoritySignature();
		PlacementProofKey viaString = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, authority);
		ProofHashOracle legacy = new ProofHashOracle(
			PlacementProofKind.NATIVE_CONTINUITY, owner, authority);
		Assert.assertSame("factory retains the one materialized String", authority, text.materialize());
		Assert.assertEquals(legacy.hashCode(), actual.hashCode());
		Assert.assertEquals(viaString.hashCode(), actual.hashCode());
		Assert.assertEquals(viaString, actual);
		Assert.assertEquals(actual, viaString);
		Assert.assertEquals(viaString.toString(), actual.toString());
		Assert.assertEquals(authority, actual.authoritySignature());
		return actual;
	}

	private static String stringWithHash(int target) {
		char[] value = new char[7];
		long unsigned = Integer.toUnsignedLong(target);
		for(int index = value.length - 1; index >= 0; index--) {
			value[index] = (char)(unsigned % 31);
			unsigned /= 31;
		}
		Assert.assertEquals(target, new String(value).hashCode());
		return new String(value);
	}

	private static Object prepare(CandidateEmissionFact emission) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"prepareGroundedNativeSupport", CandidateEmissionFact.class);
		method.setAccessible(true);
		return method.invoke(null, emission);
	}

	private static boolean contains(Object preparation,
		List<CandidateEmissionRealization> candidates) throws Exception {
		Method method = preparation.getClass().getDeclaredMethod("containsExact", List.class);
		method.setAccessible(true);
		return (boolean)method.invoke(preparation, candidates);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("logreg-hash", "main", "call", "compiled",
			REGION, id, id);
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CandidateEmissionRealization publish(NativeContinuityProof proof,
		String owner) throws Exception {
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		return directNativePublication(new PlacementRelationClosure(null, null, null, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false), proof, key(owner),
			emission, anchor(owner + "-published"));
	}

	private static CandidateEmissionRealization directNativePublication(
		PlacementRelationClosure closure, NativeContinuityProof proof, CompiledHopKey owner,
		PlacementEmissionState emission, DurableAnchorKey outputAnchor) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativeContinuityProof.class, CompiledHopKey.class, PlacementEmissionState.class,
			DurableAnchorKey.class, String.class, boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			emission, outputAnchor, "lifecycle", true);
	}

	private static String proofString(NativeContinuityProof proof) throws Exception {
		Field field = NativeContinuityProof.class.getDeclaredField("normalizedSignature");
		field.setAccessible(true);
		return (String)field.get(proof);
	}

	private static PlacementAnalysis.NormalizedText proofText(NativeContinuityProof proof)
		throws Exception {
		Field field = NativeContinuityProof.class.getDeclaredField("normalizedSignatureText");
		field.setAccessible(true);
		return (PlacementAnalysis.NormalizedText)field.get(proof);
	}

	private static String materialized(PlacementAnalysis.NormalizedText text) throws Exception {
		Field field = PlacementAnalysis.NormalizedText.class.getDeclaredField("materialized");
		field.setAccessible(true);
		return (String)field.get(text);
	}

	private static CandidateRealizationInputBinding directBinding(CompiledHopKey owner) {
		return CandidateRealizationInputBinding.direct(0, new CandidateRealizationReference(
			new CandidateRuleKey(owner, List.of()), PlacementRealizationKey.local(LOCAL_EMISSION)));
	}
}
