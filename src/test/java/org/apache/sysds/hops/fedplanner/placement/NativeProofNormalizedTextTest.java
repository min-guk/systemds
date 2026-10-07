/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeProofNormalizedTextTest {
	@Test
	public void ropeExactlyMatchesLegacyBytesLengthAndUtf16Order() throws Exception {
		List<NativeContinuityProof> proofs = List.of(
			proof("seed|é", "pool[😀]", true, "source\ud83d", "origin:=,|[]", false),
			proof("seed|é", "pool[😀]", true, "source\ud83d\ude80", "origin:=,|[]", true),
			proof("seed|é", "pool[😀]", false, "source\ude80", "한글|[]=,", true),
			proof("seed|ê", "pool[😀]", true, "source\ud83d", "origin:=,|[]", true));
		Comparator<NativeContinuityProof> comparator = proofComparator();
		for(NativeContinuityProof left : proofs) {
			String leftLegacy = legacy(left);
			Assert.assertEquals(leftLegacy, left.normalizedSignature());
			Assert.assertEquals(leftLegacy.length(), signatureText(left).length());
			for(NativeContinuityProof right : proofs)
				Assert.assertEquals(Integer.signum(leftLegacy.compareTo(legacy(right))),
					Integer.signum(comparator.compare(left, right)));
		}
		List<NativeContinuityProof> expected = new ArrayList<>(proofs);
		expected.sort(Comparator.comparing(NativeProofNormalizedTextTest::legacy));
		List<NativeContinuityProof> actual = new ArrayList<>(proofs);
		actual.sort(comparator);
		Assert.assertEquals(expected, actual);
	}

	@Test
	public void sortingAndEstimateDoNotFlattenProofOrBindingText() throws Exception {
		PlacementIdentity.beginAnalysisScope(null);
		try {
			NativeContinuityProof later = proof("seed", "pool", true, "z-source", "z-origin", true);
			NativeContinuityProof earlier = proof("seed", "pool", true, "a-source", "a-origin", true);
			Assert.assertNull(PlacementIdentity.cachedSignature(later.immediateBindings().get(0)));
			Assert.assertNull(PlacementIdentity.cachedSignature(earlier.immediateBindings().get(0)));
			Assert.assertNull(proofString(later));
			Assert.assertNull(materialized(signatureText(later)));

			List<NativeContinuityProof> sorted = new ArrayList<>(List.of(later, earlier));
			sorted.sort(proofComparator());
			long estimated = estimatedProofBytes(sorted);
			for(NativeContinuityProof proof : sorted) {
				Assert.assertNull("sort and admission estimate must keep final proof text lazy",
					proofString(proof));
				Assert.assertNull("rope comparison and length must not flatten the rope",
					materialized(signatureText(proof)));
				Assert.assertNull("binding descriptors must not be serialized as intermediate strings",
					PlacementIdentity.cachedSignature(proof.immediateBindings().get(0)));
			}
			long expected = 0;
			for(NativeContinuityProof proof : sorted)
				expected += 96L + 2L * legacy(proof).length()
					+ 32L * proof.immediateBindings().size();
			Assert.assertEquals(expected, estimated);
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void explicitMaterializationIsLazyAndObjectLocalIdentityIsStable() throws Exception {
		NativeContinuityProof proof = proof("seed", "pool", true, "source", "origin", true);
		Assert.assertNull(proofString(proof));
		NormalizedText structural = signatureText(proof);
		Assert.assertNull(materialized(structural));
		String first = proof.normalizedSignature();
		Assert.assertEquals(legacy(proof), first);
		Assert.assertSame(first, proof.normalizedSignature());
		Assert.assertNotSame("the proof must release its structural rope after publication",
			structural, signatureText(proof));
		Assert.assertEquals(first.length(), signatureText(proof).length());
		Assert.assertNull("the replacement is a literal descriptor, not a second cached String",
			materialized(signatureText(proof)));
	}

	@Test
	public void structuralCacheHitSharesFinalStringAndRetainsOnlyLiteralDescriptors() throws Exception {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(1_000_000L);
		PlacementIdentity.beginAnalysisScope(null);
		try {
			NativeContinuityProof first = proof("seed", "pool", true, "source", "origin", true);
			String text = first.normalizedSignature();
			NativeContinuityProof equal = proof("seed", "pool", true, "source", "origin", true);
			Assert.assertSame(text, equal.normalizedSignature());
			Assert.assertNull(materialized(signatureText(first)));
			Assert.assertNull(materialized(signatureText(equal)));
			Assert.assertEquals(text.length(), signatureText(equal).length());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	@Test
	public void exhaustedSignatureCacheKeepsExactLongTextOnlyObjectLocal() throws Exception {
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		PlacementIdentity.beginAnalysisScope(null);
		try {
			String longId = "long|[]=,\ud83d\ude80".repeat(1_000);
			NativeContinuityProof first = proof(longId, "pool", true, longId, longId, true);
			NativeContinuityProof equal = proof(longId, "pool", true, longId, longId, true);
			String firstText = first.normalizedSignature();
			String equalText = equal.normalizedSignature();
			Assert.assertEquals(firstText, equalText);
			Assert.assertNotSame(firstText, equalText);
			Assert.assertSame(firstText, first.normalizedSignature());
			Assert.assertSame(equalText, equal.normalizedSignature());
			Assert.assertEquals(0L, PlacementIdentity.normalizedSignatureCacheRetainedChars());
			Assert.assertNull(materialized(signatureText(first)));
			Assert.assertNull(materialized(signatureText(equal)));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	@Test
	public void equalFullKeysKeepDistinctOwnerAuthorityAndStableTieOrder() throws Exception {
		CompiledHopKey firstOwner = owner("same-source", "same-origin");
		CompiledHopKey secondOwner = owner("same-source", "same-origin");
		Assert.assertEquals(firstOwner, secondOwner);
		Assert.assertNotSame(firstOwner, secondOwner);
		NativeContinuityProof first = proof(firstOwner);
		NativeContinuityProof second = proof(secondOwner);
		Assert.assertEquals(first, second);
		Assert.assertEquals(0, proofComparator().compare(first, second));
		Assert.assertSame(firstOwner,
			first.immediateBindings().get(0).source().rule().parentOccurrence());
		Assert.assertSame(secondOwner,
			second.immediateBindings().get(0).source().rule().parentOccurrence());
		List<NativeContinuityProof> tied = new ArrayList<>(List.of(second, first));
		tied.sort(proofComparator());
		Assert.assertSame("stable equal-text ordering must not replace exact owner authority",
			secondOwner, tied.get(0).immediateBindings().get(0).source().rule().parentOccurrence());
	}

	@Test
	public void proofRetainsRopeButNotBatchContext() {
		for(Field field : NativeContinuityProof.class.getDeclaredFields())
			Assert.assertNotEquals("a proof must not retain the batch identity context",
				PlacementAnalysis.NormalizedTextContext.class, field.getType());
	}

	private static NativeContinuityProof proof(String seed, String pool, boolean exact,
		String source, String origin, boolean fullBindings) {
		CompiledHopKey owner = owner(source, origin);
		CandidateRealizationReference reference = reference(owner);
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		bindings.add(CandidateRealizationInputBinding.direct(0, reference));
		if(fullBindings) {
			bindings.add(CandidateRealizationInputBinding.logicalTransient(1, reference));
			ValueVersionKey version = new ValueVersionKey("proof|program", "value[,]=\ud83d",
				owner.controlRegion(), 2, VersionKind.ORDINARY, List.of("pred|1", "pred,2"));
			RelocationActionKey action = new RelocationActionKey(version,
				reference.realization().emissionState().placementState(), FType.ROW,
				anchor("relocation|[]=,"), owner.controlRegion().normalizedSignature(), List.of(owner));
			bindings.add(CandidateRealizationInputBinding.relocation(2, reference, action));
		}
		return new NativeContinuityProof(anchor(seed), anchor(pool), exact, bindings);
	}

	private static NativeContinuityProof proof(CompiledHopKey owner) {
		return new NativeContinuityProof(anchor("seed"), anchor("pool"), true,
			List.of(CandidateRealizationInputBinding.direct(0, reference(owner))));
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, false), false);
		return CandidateRealizationReference.of(rule, CandidateEmissionRealization.local(emission));
	}

	private static CompiledHopKey owner(String source, String origin) {
		ControlRegionKey region = new ControlRegionKey("proof|program", "main",
			List.of("root[,]=", "unicode\ud83d\ude80"), "root|call", "compiled[]=,");
		return new CompiledHopKey("proof|program", "main", "root|call", "compiled[]=,",
			region, source, origin);
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static String legacy(NativeContinuityProof proof) {
		return proof.externalSeed().normalizedSignature() + "|outputPool="
			+ proof.outputWorkerPoolWitness().normalizedSignature() + "|partitionRanges="
			+ (proof.exactPartitionRanges() ? "exact" : "dynamic") + "|bindings="
			+ proof.immediateBindings().stream()
				.map(CandidateRealizationInputBinding::normalizedSignature).toList();
	}

	@SuppressWarnings("unchecked")
	private static Comparator<NativeContinuityProof> proofComparator() throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("nativeProofSignatureComparator");
		method.setAccessible(true);
		return (Comparator<NativeContinuityProof>)method.invoke(null);
	}

	private static long estimatedProofBytes(List<NativeContinuityProof> proofs) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("estimatedProofBytes", List.class);
		method.setAccessible(true);
		return (long)method.invoke(null, proofs);
	}

	private static NormalizedText signatureText(NativeContinuityProof proof) throws Exception {
		Field field = NativeContinuityProof.class.getDeclaredField("normalizedSignatureText");
		field.setAccessible(true);
		return (NormalizedText)field.get(proof);
	}

	private static String proofString(NativeContinuityProof proof) throws Exception {
		Field field = NativeContinuityProof.class.getDeclaredField("normalizedSignature");
		field.setAccessible(true);
		return (String)field.get(proof);
	}

	private static String materialized(NormalizedText text) throws Exception {
		Field field = NormalizedText.class.getDeclaredField("materialized");
		field.setAccessible(true);
		return (String)field.get(text);
	}
}
