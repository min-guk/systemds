/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.PhysicalSemanticDagFingerprint.LiteralByteMemoSnapshot;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextBuilder;
import org.junit.Assert;
import org.junit.Test;

public class PhysicalSemanticDagLiteralByteMemoTest {
	@Test
	public void disabledAndAdmittedMemoMatchIndependentLegacyEncoder() {
		String shared = new String("literal-Aa|\ud83d\ude00|\ud800|tail");
		String equalForeign = new String(shared);
		Assert.assertNotSame(shared, equalForeign);
		Assert.assertEquals("Aa".hashCode(), "BB".hashCode());
		String boundary = "x".repeat(511) + "\ud800" + "y".repeat(513);
		List<NormalizedText> values = List.of(
			NormalizedText.literal(shared),
			new NormalizedTextBuilder().append("literal-").append("Aa|")
				.append("\ud83d").append("\ude00|\ud800|tail").build(),
			NormalizedText.literal(equalForeign),
			NormalizedText.literal("Aa"), NormalizedText.literal("BB"),
			NormalizedText.literal(boundary), NormalizedText.literal(""));
		String expected = legacyFingerprint(values);
		PhysicalSemanticDagFingerprint disabled = new PhysicalSemanticDagFingerprint(null, 0L);
		PhysicalSemanticDagFingerprint admitted = new PhysicalSemanticDagFingerprint(null, 1L << 20);
		Assert.assertEquals(expected, disabled.normalizedTextsForTest(values));
		Assert.assertEquals(expected, admitted.normalizedTextsForTest(values));
	}

	@Test
	public void sameLiteralIdentityConvertsOnceWhileDisabledConvertsEveryOccurrence() {
		String literal = new String("repeat-\ud800-" + "x".repeat(1024));
		List<NormalizedText> values = new ArrayList<>();
		for(int index = 0; index < 100; index++)
			values.add(NormalizedText.literal(literal));
		PhysicalSemanticDagFingerprint admitted = new PhysicalSemanticDagFingerprint(null, 1L << 20);
		PhysicalSemanticDagFingerprint disabled = new PhysicalSemanticDagFingerprint(null, 0L);
		Assert.assertEquals(disabled.normalizedTextsForTest(values), admitted.normalizedTextsForTest(values));
		LiteralByteMemoSnapshot admittedStats = admitted.literalByteMemoSnapshotForTest();
		LiteralByteMemoSnapshot disabledStats = disabled.literalByteMemoSnapshotForTest();
		Assert.assertEquals(100, admittedStats.lookups());
		Assert.assertEquals(99, admittedStats.hits());
		Assert.assertEquals(1, admittedStats.conversions());
		Assert.assertEquals(literal.length(), admittedStats.convertedUtf16Units());
		Assert.assertEquals(1, admittedStats.admittedEntries());
		Assert.assertEquals(100, disabledStats.conversions());
		Assert.assertEquals(0, disabledStats.hits());
		Assert.assertEquals(100, disabledStats.rejectedEntries());
	}

	@Test
	public void equalForeignAndHashCollisionLiteralsRemainSeparateIdentityEntries() {
		String first = new String("equal");
		String second = new String("equal");
		String collisionA = new String("Aa");
		String collisionB = new String("BB");
		List<NormalizedText> values = List.of(NormalizedText.literal(first),
			NormalizedText.literal(second), NormalizedText.literal(collisionA),
			NormalizedText.literal(collisionB), NormalizedText.literal(first),
			NormalizedText.literal(second));
		PhysicalSemanticDagFingerprint fingerprint = new PhysicalSemanticDagFingerprint(null, 4096L);
		Assert.assertEquals(legacyFingerprint(values), fingerprint.normalizedTextsForTest(values));
		LiteralByteMemoSnapshot stats = fingerprint.literalByteMemoSnapshotForTest();
		Assert.assertEquals(4, stats.retainedEntries());
		Assert.assertEquals(4, stats.conversions());
		Assert.assertEquals(2, stats.hits());
	}

	@Test
	public void admissionCeilingChangesStorageOnlyAndRetainedEstimateStaysBounded() {
		String first = new String("12345678");
		String second = new String("abcdefgh");
		long oneEntryBytes = 64L + 2L * first.length();
		List<NormalizedText> values = List.of(NormalizedText.literal(first),
			NormalizedText.literal(second), NormalizedText.literal(first),
			NormalizedText.literal(second));
		PhysicalSemanticDagFingerprint partial =
			new PhysicalSemanticDagFingerprint(null, oneEntryBytes);
		Assert.assertEquals(legacyFingerprint(values), partial.normalizedTextsForTest(values));
		LiteralByteMemoSnapshot stats = partial.literalByteMemoSnapshotForTest();
		Assert.assertEquals(1, stats.retainedEntries());
		Assert.assertEquals(1, stats.admittedEntries());
		Assert.assertEquals(2, stats.rejectedEntries());
		Assert.assertEquals(1, stats.hits());
		Assert.assertEquals(3, stats.conversions());
		Assert.assertTrue(stats.retainedEstimatedBytes() <= oneEntryBytes);
	}

	@Test
	public void explicitLifecycleReleaseDropsAllRetainedLiteralPayload() {
		PhysicalSemanticDagFingerprint fingerprint =
			new PhysicalSemanticDagFingerprint(null, 4096L);
		fingerprint.normalizedTextsForTest(List.of(
			NormalizedText.literal(new String("retained-a")),
			NormalizedText.literal(new String("retained-b"))));
		Assert.assertEquals(2, fingerprint.literalByteMemoSnapshotForTest().retainedEntries());
		fingerprint.clearLiteralByteMemo();
		LiteralByteMemoSnapshot cleared = fingerprint.literalByteMemoSnapshotForTest();
		Assert.assertEquals(0, cleared.retainedEntries());
		Assert.assertEquals(0, cleared.retainedEstimatedBytes());
	}

	@Test
	public void productionConstructorDoesNotCollectTestWorkCounters() {
		PhysicalSemanticDagFingerprint fingerprint = new PhysicalSemanticDagFingerprint();
		fingerprint.normalizedTextsForTest(List.of(NormalizedText.literal("production")));
		LiteralByteMemoSnapshot snapshot = fingerprint.literalByteMemoSnapshotForTest();
		Assert.assertEquals(1, snapshot.retainedEntries());
		Assert.assertEquals(0, snapshot.lookups());
		Assert.assertEquals(0, snapshot.conversions());
	}

	@Test
	public void memoHitsPreserveEveryNormalizedTextLiteralCallback() {
		String shared = new String("callback-\ud800");
		NormalizedText rope = new NormalizedTextBuilder().append(shared).append(shared).build();
		PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics diagnostics =
			new PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics();
		PhysicalSemanticDagFingerprint fingerprint =
			new PhysicalSemanticDagFingerprint(diagnostics, 4096L);
		fingerprint.normalizedTextsForTest(List.of(rope, rope));

		PhysicalSemanticDagFingerprint.NormalizedTextSharingSnapshot callbacks =
			diagnostics.snapshotAndClear();
		Assert.assertEquals(2, callbacks.normalizedOccurrences());
		Assert.assertEquals(4, callbacks.literalOccurrences());
		Assert.assertEquals(1, callbacks.distinctLiteralIdentities());
		Assert.assertEquals(shared.length(), callbacks.uniqueLiteralUnits());
		Assert.assertEquals(3L * shared.length(), callbacks.repeatedLiteralUnits());
		Assert.assertEquals(3, fingerprint.literalByteMemoSnapshotForTest().hits());
	}

	private static String legacyFingerprint(List<NormalizedText> values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1024];
			writeText(digest, buffer, "nodeType");
			writeText(digest, buffer, PhysicalSemanticDagFingerprint.SCHEMA + ":normalized-text-root");
			writeText(digest, buffer, "count");
			writeInt(digest, values.size());
			for(NormalizedText value : values) {
				writeText(digest, buffer, "value");
				writeInt(digest, value.length());
				value.appendTo(segment -> writeChars(digest, buffer, segment));
			}
			return PhysicalSemanticDagFingerprint.SCHEMA + ':'
				+ HexFormat.of().formatHex(digest.digest());
		}
		catch(NoSuchAlgorithmException ex) {
			throw new AssertionError(ex);
		}
	}

	private static void writeText(MessageDigest digest, byte[] buffer, String value) {
		writeInt(digest, value.length());
		writeChars(digest, buffer, value);
	}

	private static void writeChars(MessageDigest digest, byte[] buffer, String value) {
		for(int source = 0; source < value.length();) {
			int units = Math.min(value.length() - source, buffer.length / 2);
			int target = 0;
			for(int end = source + units; source < end; source++) {
				char unit = value.charAt(source);
				buffer[target++] = (byte)(unit >>> 8);
				buffer[target++] = (byte)unit;
			}
			digest.update(buffer, 0, target);
		}
	}

	private static void writeInt(MessageDigest digest, int value) {
		digest.update((byte)(value >>> 24));
		digest.update((byte)(value >>> 16));
		digest.update((byte)(value >>> 8));
		digest.update((byte)value);
	}
}
