/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;

import org.junit.Assert;
import org.junit.Test;

/** Locks the physical-cost factor fingerprint byte stream while avoiding per-cell text objects. */
public class ExactPhysicalCostFingerprintStreamingTest {
	@Test
	public void nestedCandidateRealizationsPreserveUnicodeAndSharedClauseBytes() throws Exception {
		var original = ExactNativeLocalAnchorFanoutCostTest.analysis(false)
			.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> !fact.allowedEmissionFacts().isEmpty()).findFirst().orElseThrow();
		var emission = original.allowedEmissionFacts().get(0);
		var realization = emission.realizations().get(0);
		var clause = realization.supportClauses().get(0);
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(String authority : List.of("한글|[,]:\ud83d\ude00", "unpaired:\ud800x\udc00", "Aa", "BB")) {
			var proofs = new ArrayList<>(clause.proofDependencies());
			proofs.add(new PlacementProofKey(PlacementProofKind.VALUE_IDENTITY,
				original.key().parentOccurrence(), authority));
			clauses.add(new CandidateRealizationSupportClause(proofs, clause.inputBindings(),
				clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact()));
		}
		var changed = new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
			emission.derivedFoutAction(), List.of(new CandidateEmissionRealization(realization.key(), clauses)));
		var fact = new CandidateRuleFact(original.key(), original.status(), original.capability(),
			original.shapeProof(), original.profile(), List.of(changed), original.failureCode());
		List<CandidateRuleFact> repeated = List.of(fact, fact, original, fact);
		StringBuilder reference = new StringBuilder();
		for(var row : repeated)
			reference.append("|candidate:").append(row.key().normalizedSignature())
				.append("|status=").append(row.status()).append("|capability=").append(row.capability())
				.append("|shape=").append(row.shapeProof()).append("|profile=").append(row.profile())
				.append("|emissions=").append(row.allowedEmissionFacts().stream()
					.map(CandidateEmissionFact::normalizedSignature).toList())
				.append("|failure=").append(row.failureCode());
		Assert.assertEquals(textFingerprint(reference.toString()),
			ExactPhysicalCostModel.physicalCandidateFactsFingerprintForTest(repeated));
	}

	@Test
	public void chunkedUtf8TextMatchesOneLegacyAggregateAcrossSurrogateBoundary() throws Exception {
		List<Object> chunks = Arrays.asList("candidate:", "한글/astral-", "\ud83d", "", "\ude80",
			null, "|zero=", -0.0d, "|empty=[]");
		StringBuilder legacy = new StringBuilder();
		for(Object chunk : chunks)
			legacy.append(String.valueOf(chunk));
		Assert.assertEquals(textFingerprint(legacy.toString()),
			ExactPhysicalCostModel.fingerprintTextChunksForTest(chunks));
	}

	@Test
	public void bufferedUtf8MatchesLegacyAtExactAndMalformedBoundaries() throws Exception {
		List<Object> chunks = Arrays.asList(
			"a".repeat(8191) + "\ud83d",
			"\ude80",
			"한글/astral-\ud83d\ude00".repeat(2000),
			"\ud800",
			123456789L,
			"|lone-low=\udc00|",
			Boolean.TRUE,
			"z".repeat(16_385),
			"\ud800");
		StringBuilder legacy = new StringBuilder();
		for(Object chunk : chunks)
			legacy.append(String.valueOf(chunk));
		Assert.assertEquals(textFingerprint(legacy.toString()),
			ExactPhysicalCostModel.fingerprintTextChunksForTest(chunks));
	}

	@Test
	public void optimizedNumericAndBooleanTokensFlushMalformedSurrogatesInOrder() throws Exception {
		ExactPhysicalCostModel.FingerprintWriter writer = new ExactPhysicalCostModel.FingerprintWriter();
		Method appendHex = ExactPhysicalCostModel.FingerprintWriter.class
			.getDeclaredMethod("appendUnsignedHexWithComma", long.class);
		appendHex.setAccessible(true);
		writer.append("\ud800");
		appendHex.invoke(writer, 0x1afL);
		writer.append("\ud800").appendBooleanArray(new boolean[] {true, false, true});
		writer.append("\ud800");

		String legacy = "\ud800" + Long.toUnsignedString(0x1afL, 16) + ','
			+ "\ud800" + Arrays.toString(new boolean[] {true, false, true}) + "\ud800";
		Assert.assertEquals(textFingerprint(legacy), writer.finish());
	}

	@Test
	public void streamedCandidateFactsMatchLegacyAggregateBytes() throws Exception {
		var allFacts = ExactNativeLocalAnchorFanoutCostTest.analysis(false)
			.candidateRuleFacts().orderedFacts();
		List<CandidateRuleFact> facts = new ArrayList<>();
		int stride = Math.max(1, allFacts.size() / 12);
		for(int index = 0; index < allFacts.size() && facts.size() < 12; index += stride)
			facts.add(allFacts.get(index));
		allFacts.stream().filter(fact -> fact.allowedEmissionFacts().size() > 1)
			.filter(fact -> !facts.contains(fact)).findFirst().ifPresent(facts::add);
		MessageDigest legacy = MessageDigest.getInstance("SHA-256");
		long aggregateCharacters = 0L;
		for(var fact : facts) {
			String signature = fact.key().normalizedSignature() + "|status=" + fact.status()
				+ "|capability=" + fact.capability() + "|shape=" + fact.shapeProof()
				+ "|profile=" + fact.profile() + "|emissions="
				+ fact.allowedEmissionFacts().stream()
					.map(CandidateEmissionFact::normalizedSignature).toList()
				+ "|failure=" + fact.failureCode();
			String row = "|candidate:" + signature;
			aggregateCharacters += row.length();
			legacy.update(row.getBytes(StandardCharsets.UTF_8));
		}
		Assert.assertFalse("fixture must publish candidate facts", facts.isEmpty());
		Assert.assertEquals(HexFormat.of().formatHex(legacy.digest()),
			ExactPhysicalCostModel.physicalCandidateFactsFingerprintForTest(facts));
		System.out.println("EXACT_COST_FINGERPRINT_STREAMING_EVIDENCE|facts=" + facts.size()
			+ "|legacyAggregateCharacters=" + aggregateCharacters);
	}

	@Test
	public void streamedAlternativeSignaturesMatchEveryFlattenedLegacySignature() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		int alternatives = 0;
		for(var domain : model.domains())
			for(var alternative : domain.alternatives()) {
				Assert.assertEquals(textFingerprint(alternative.signature()),
					ExactPhysicalCostModel.physicalAlternativeSignatureFingerprintForTest(alternative));
				alternatives++;
			}
		Assert.assertTrue("fixture must publish physical alternatives", alternatives > 0);
	}

	@Test
	public void streamingHexMatchesLegacyReferenceForRawBits() throws Exception {
		long[] edgeBits = {
			0L,
			Double.doubleToRawLongBits(-0.0),
			Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
			Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY),
			0x7ff8000000000001L,
			1L,
			15L,
			16L,
			Long.MAX_VALUE,
			Long.MIN_VALUE,
			-1L
		};
		int randomValues = 100_000;
		double[] values = new double[edgeBits.length + randomValues];
		for(int index = 0; index < edgeBits.length; index++)
			values[index] = Double.longBitsToDouble(edgeBits[index]);
		SplittableRandom random = new SplittableRandom(1011081480L);
		for(int index = edgeBits.length; index < values.length; index++)
			values[index] = Double.longBitsToDouble(random.nextLong());
		var variable = new ExactCategoricalSolver.Variable("raw-bits", values.length);
		var factor = ExactCategoricalSolver.Factor.dense(List.of(variable), values);
		Assert.assertEquals(legacyFingerprint(values),
			ExactPhysicalCostModel.physicalFactorValuesFingerprint(factor));
	}

	@Test
	public void streamingDenseOrderMatchesLegacyMultidimensionalOrder() throws Exception {
		var rows = new ExactCategoricalSolver.Variable("rows", 2);
		var columns = new ExactCategoricalSolver.Variable("columns", 3);
		double[] values = {
			Double.longBitsToDouble(0x0000000000000001L),
			Double.longBitsToDouble(0x0000000000000010L),
			Double.longBitsToDouble(0x0000000000000100L),
			Double.longBitsToDouble(0x1000000000000000L),
			Double.longBitsToDouble(0x7ff0000000000000L),
			Double.longBitsToDouble(0x8000000000000000L)
		};
		var factor = ExactCategoricalSolver.Factor.dense(List.of(rows, columns), values);
		Assert.assertEquals(legacyFingerprint(values),
			ExactPhysicalCostModel.physicalFactorValuesFingerprint(factor));
	}

	@Test
	public void repeatedValuesAcrossDigestBuffersRetainExactBytes() throws Exception {
		double[] values = new double[32_771];
		for(int index = 0; index < values.length; index++)
			values[index] = index < 8_193 ? 0d : index < 24_579 ? 1.25d
				: Double.longBitsToDouble(0x7ff8000000000001L);
		var variable = new ExactCategoricalSolver.Variable("buffer-boundaries", values.length);
		Assert.assertEquals(legacyFingerprint(values),
			ExactPhysicalCostModel.physicalFactorValuesFingerprint(
				ExactCategoricalSolver.Factor.dense(List.of(variable), values)));
	}

	private static String legacyFingerprint(double[] values) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for(double value : values) {
			digest.update(Long.toUnsignedString(Double.doubleToRawLongBits(value), 16)
				.getBytes(StandardCharsets.UTF_8));
			digest.update((byte)',');
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static String textFingerprint(String value) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest.digest());
	}
}
