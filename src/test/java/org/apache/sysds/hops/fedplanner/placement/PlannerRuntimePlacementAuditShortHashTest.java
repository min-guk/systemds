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
import java.util.BitSet;
import java.util.Locale;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

public class PlannerRuntimePlacementAuditShortHashTest {
	@Test
	public void matchesLegacyFormattingForTextAndEveryDigestByteValue() throws Exception {
		for(String value : new String[] {
			"", "ascii", "éΩ한글😀\n", "\uD800", "\uDC00", "a\uD800b\uDC00c"
		})
			assertLegacyParity(value);

		BitSet leadingDigestBytes = new BitSet(256);
		for(int i = 0; i < 4096; i++) {
			String value = "digest-byte-coverage-" + i;
			byte[] digest = digest(value);
			for(int j = 0; j < 8; j++)
				leadingDigestBytes.set(digest[j] & 0xff);
			assertLegacyParity(value);
		}
		Assert.assertEquals("the parity corpus must exercise every byte encoding", 256,
			leadingDigestBytes.cardinality());
	}

	@Test
	public void matchesLegacyFormattingForRandomUtf16Strings() throws Exception {
		Random random = new Random(0x51a256L);
		for(int trial = 0; trial < 1000; trial++) {
			char[] chars = new char[random.nextInt(80)];
			for(int i = 0; i < chars.length; i++)
				chars[i] = (char) random.nextInt(Character.MAX_VALUE + 1);
			assertLegacyParity(new String(chars));
		}
	}

	@Test
	public void outputIsLocaleIndependentAndLowercase() throws Exception {
		Locale original = Locale.getDefault(Locale.Category.FORMAT);
		try {
			String baseline = null;
			for(Locale locale : new Locale[] {Locale.US, Locale.forLanguageTag("tr-TR"),
				Locale.forLanguageTag("ar-EG")}) {
				Locale.setDefault(Locale.Category.FORMAT, locale);
				String actual = PlannerRuntimePlacementAudit.shortHash("locale-İ-١-한글");
				Assert.assertEquals(legacyShortHash("locale-İ-١-한글"), actual);
				Assert.assertTrue(actual.matches("[0-9a-f]{16}"));
				if(baseline == null)
					baseline = actual;
				else
					Assert.assertEquals(baseline, actual);
			}
		}
		finally {
			Locale.setDefault(Locale.Category.FORMAT, original);
		}
	}

	@Test
	public void nullRetainsLegacyNullPointerSemantics() {
		Assert.assertThrows(NullPointerException.class,
			() -> PlannerRuntimePlacementAudit.shortHash(null));
		Assert.assertThrows(NullPointerException.class, () -> legacyShortHash(null));
	}

	private static void assertLegacyParity(String value) throws Exception {
		Assert.assertEquals(legacyShortHash(value), PlannerRuntimePlacementAudit.shortHash(value));
	}

	private static String legacyShortHash(String value) throws Exception {
		byte[] digest = digest(value);
		StringBuilder out = new StringBuilder(16);
		for(int i = 0; i < 8; i++)
			out.append(String.format("%02x", digest[i]));
		return out.toString();
	}

	private static byte[] digest(String value) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
	}
}
