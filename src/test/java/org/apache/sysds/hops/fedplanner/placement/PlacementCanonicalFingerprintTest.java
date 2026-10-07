/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

/** Regression tests for canonical placement fingerprint normalization. */
public class PlacementCanonicalFingerprintTest {
	@Test
	public void stableSignatureMatchesRegexAtHexRunBoundaries() {
		assertRegexParity("prefix-" + "a".repeat(63) + "-suffix");
		assertRegexParity("prefix-" + "a".repeat(64) + "-suffix");
		assertRegexParity("prefix-" + "a".repeat(65) + "-suffix");
		assertRegexParity("prefix-" + "a".repeat(127) + "-suffix");
		assertRegexParity("prefix-" + "a".repeat(128) + "-suffix");
		Assert.assertEquals("prefix-<program>a-suffix",
			PlacementGraphFingerprint.stableSignature("prefix-" + "a".repeat(65) + "-suffix"));
		Assert.assertEquals("<program>" + "a".repeat(63),
			PlacementGraphFingerprint.stableSignature("a".repeat(127)));
		Assert.assertEquals("<program><program>",
			PlacementGraphFingerprint.stableSignature("a".repeat(128)));
	}

	@Test
	public void stableSignatureLeavesNonLowercaseAsciiHexUnchanged() {
		for(String value : new String[] {
			"", "no fingerprint", "A".repeat(64), "g".repeat(64), "é".repeat(64),
			"a".repeat(63) + "A" + "b".repeat(63), "０".repeat(64), "deadBEEF".repeat(8)}) {
			assertRegexParity(value);
			Assert.assertSame("no match should return the original String", value,
				PlacementGraphFingerprint.stableSignature(value));
		}
		String mixed = "A" + "0123456789abcdef".repeat(4) + "é";
		assertRegexParity(mixed);
	}

	@Test
	public void stableSignatureMatchesRegexOracleForRandomizedInputs() {
		Random random = new Random(0x5eedc0deL);
		String alphabet = "0123456789abcdefgxyzABCDEF-_:éΩ";
		for(int trial = 0; trial < 1000; trial++) {
			StringBuilder value = new StringBuilder();
			int chunks = 1 + random.nextInt(12);
			for(int chunk = 0; chunk < chunks; chunk++) {
				if(random.nextBoolean()) {
					int length = random.nextInt(141);
					for(int i = 0; i < length; i++)
						value.append("0123456789abcdef".charAt(random.nextInt(16)));
				}
				else {
					int length = random.nextInt(31);
					for(int i = 0; i < length; i++)
						value.append(alphabet.charAt(random.nextInt(alphabet.length())));
				}
			}
			assertRegexParity(value.toString());
		}
	}

	@Test
	public void digestEncodingMatchesKnownVectorsAndLegacyByteFormatting() throws Exception {
		Assert.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			PlacementGraphFingerprint.sha256(""));
		Assert.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
			PlacementGraphFingerprint.sha256("abc"));
		for(String value : List.of("éΩ한글\n", "0123456789abcdef".repeat(64))) {
			byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder expected = new StringBuilder();
			for(byte b : bytes)
				expected.append(String.format("%02x", b));
			Assert.assertEquals(expected.toString(), PlacementGraphFingerprint.sha256(value));
		}
	}

	@Test
	public void canonicalConstructorMatchesLegacyHexRecanonicalization() {
		PlacementAnalysis canonical = canonicalAnalysis();
		PlacementAnalysis legacyHex = legacyAnalysis("a".repeat(64));

		Assert.assertEquals(legacyHex.analysisFingerprint(), canonical.analysisFingerprint());
		Assert.assertEquals(canonical.analysisFingerprint(),
			canonical.candidateRuleDomain().analysisFingerprint());
		Assert.assertTrue(canonical.analysisFingerprint().matches("[0-9a-f]{64}"));
	}

	@Test
	public void legacyConstructorRetainsFixtureIdentityAndRejectsBlankValues() {
		Assert.assertEquals("fixture-fingerprint", legacyAnalysis("fixture-fingerprint").analysisFingerprint());
		Assert.assertThrows(IllegalArgumentException.class, () -> legacyAnalysis(null));
		Assert.assertThrows(IllegalArgumentException.class, () -> legacyAnalysis(""));
		Assert.assertThrows(IllegalArgumentException.class, () -> legacyAnalysis(" \t"));
	}

	private static void assertRegexParity(String value) {
		Assert.assertEquals(value.replaceAll("[0-9a-f]{64}", "<program>"),
			PlacementGraphFingerprint.stableSignature(value));
	}

	private static PlacementAnalysis canonicalAnalysis() {
		NeutralPlacementGraph graph = emptyGraph();
		return new PlacementAnalysis(graph, List.of(), List.of(), null, emptyShapes(),
			new PlacementAnalysis.HeuristicPolicyFacts(List.of()), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), emptyPrivacy(), null, List.of(), null, List.of(), null);
	}

	private static PlacementAnalysis legacyAnalysis(String fingerprint) {
		NeutralPlacementGraph graph = emptyGraph();
		return new PlacementAnalysis(graph, List.of(), List.of(), null, emptyShapes(), fingerprint,
			new PlacementAnalysis.HeuristicPolicyFacts(List.of()), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), List.of(), emptyPrivacy(), null, List.of(), null, List.of(), null);
	}

	private static NeutralPlacementGraph emptyGraph() {
		return new NeutralPlacementGraph(List.of(), List.of(), List.of());
	}

	private static PlacementShapeFacts emptyShapes() {
		return new PlacementShapeFacts(Map.of(), Set.of());
	}

	private static PlacementPrivacyFacts emptyPrivacy() {
		return new PlacementPrivacyFacts(List.of(), List.of(), 0);
	}
}
