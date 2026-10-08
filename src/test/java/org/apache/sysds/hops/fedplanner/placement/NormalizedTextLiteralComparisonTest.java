/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

public class NormalizedTextLiteralComparisonTest {
	@Test
	public void canonicalTextDefensivelySnapshotsMutableSourcePieces() throws Exception {
		List<Object> source = new ArrayList<>(List.of("alpha", "-정확-", "🚀"));
		PlacementAnalysis.NormalizedText retained = normalizedTextFromCanonicalPieces(source);
		source.clear();
		source.add("mutated");

		Assert.assertEquals("alpha-정확-🚀", retained.materialize());
		Assert.assertEquals("alpha-정확-🚀".hashCode(), retained.hashCode());
		Assert.assertEquals(0, retained.compareTo(
			PlacementAnalysis.NormalizedText.literal("alpha-정확-🚀")));
	}

	@Test
	public void unequalLiteralLengthsPreservePrefixAndDifferenceAmbiguity() {
		String prefix = "unchanged-정확-🚀".repeat(30);
		for(String[] pair : new String[][] {
			{prefix + "a", prefix + "bc"}, // first-char difference == length difference
			{prefix + "bc", prefix + "a"},
			{prefix + "a", prefix + "ab"},
			{prefix + "az", prefix + "baaa"},
			{prefix, prefix + "x"}, {"", "x"}, {"\uD800", "\uDC00x"}
		}) {
			assertOrder(PlacementAnalysis.NormalizedText.literal(pair[0]),
				PlacementAnalysis.NormalizedText.literal(pair[1]), pair[0], pair[1]);
		}
	}

	@Test
	public void literalPrefixMustContinueThroughRemainingRopeSegments() {
		assertOrder(text("abc", "z"), text("abcd", "a"), "abcz", "abcda");
		assertOrder(text("abcd", "a"), text("abc", "z"), "abcda", "abcz");
		assertOrder(text("abc", "def"), text("ab", "cdef"), "abcdef", "abcdef");
		assertOrder(text("ab", "cd", "e"), text("abcd", "eX"), "abcde", "abcdeX");
	}

	@Test
	public void randomizedSegmentationAndUtf16MatchMaterializedStringOrdering() {
		Random random = new Random(73419);
		String alphabet = "abxy;:=|읽기\uD800\uDC00\uD83D\uDE80";
		for(int trial = 0; trial < 2000; trial++) {
			String common = randomString(random, alphabet, random.nextInt(100));
			String left = common + randomString(random, alphabet, random.nextInt(40));
			String right = common + randomString(random, alphabet, random.nextInt(40));
			PlacementAnalysis.NormalizedText leftText = segmented(left, random);
			PlacementAnalysis.NormalizedText rightText = segmented(right, random);
			assertValue(leftText, left);
			assertValue(rightText, right);
			assertOrder(leftText, rightText, left, right);
			Assert.assertEquals("segmentation must not affect exact equality or hash",
				PlacementAnalysis.NormalizedText.literal(left), leftText);
		}
	}

	@Test
	public void sharedSubtreesPreserveMaterializationHashAndExactOrdering() {
		PlacementAnalysis.NormalizedText shared = text("shared-", "정확-", "🚀-", "segment".repeat(20));
		PlacementAnalysis.NormalizedText left = new PlacementAnalysis.NormalizedTextBuilder()
			.append("root[").append(shared).append("]/").append(shared).append("/a").build();
		PlacementAnalysis.NormalizedText right = new PlacementAnalysis.NormalizedTextBuilder()
			.append("root[").append(shared).append("]/").append(shared).append("/b").build();
		String leftString = "root[" + shared.materialize() + "]/" + shared.materialize() + "/a";
		String rightString = "root[" + shared.materialize() + "]/" + shared.materialize() + "/b";

		assertValue(left, leftString);
		assertValue(right, rightString);
		assertOrder(left, right, leftString, rightString);
	}

	private static void assertOrder(PlacementAnalysis.NormalizedText left,
		PlacementAnalysis.NormalizedText right, String leftString, String rightString) {
		int expected = Integer.signum(leftString.compareTo(rightString));
		Assert.assertEquals(expected, Integer.signum(left.compareTo(right)));
		Comparator<PlacementAnalysis.NormalizedText> reusable = PlacementAnalysis.normalizedTextComparator();
		Assert.assertEquals(expected, Integer.signum(reusable.compare(left, right)));
		Assert.assertEquals(-expected, Integer.signum(reusable.compare(right, left)));
		Assert.assertEquals(0, reusable.compare(left, left));
	}

	private static void assertValue(PlacementAnalysis.NormalizedText text, String expected) {
		Assert.assertEquals(expected, text.materialize());
		Assert.assertEquals(expected.hashCode(), text.hashCode());
	}

	private static PlacementAnalysis.NormalizedText normalizedTextFromCanonicalPieces(List<Object> pieces)
		throws Exception {
		Class<?> canonicalType = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalText");
		Constructor<?> canonicalConstructor = canonicalType.getDeclaredConstructor(List.class);
		canonicalConstructor.setAccessible(true);
		Object canonical = canonicalConstructor.newInstance(pieces);
		Constructor<PlacementAnalysis.NormalizedText> normalizedConstructor =
			PlacementAnalysis.NormalizedText.class.getDeclaredConstructor(canonicalType);
		normalizedConstructor.setAccessible(true);
		return normalizedConstructor.newInstance(canonical);
	}

	private static PlacementAnalysis.NormalizedText text(String... chunks) {
		PlacementAnalysis.NormalizedTextBuilder builder = new PlacementAnalysis.NormalizedTextBuilder();
		for(String chunk : chunks)
			builder.append(chunk);
		return builder.build();
	}

	private static PlacementAnalysis.NormalizedText segmented(String value, Random random) {
		PlacementAnalysis.NormalizedTextBuilder builder = new PlacementAnalysis.NormalizedTextBuilder();
		for(int from = 0; from < value.length();) {
			int to = Math.min(value.length(), from + 1 + random.nextInt(12));
			String chunk = value.substring(from, to);
			if(random.nextBoolean())
				builder.append(text(chunk));
			else
				builder.append(chunk);
			from = to;
		}
		return builder.build();
	}

	private static String randomString(Random random, String alphabet, int length) {
		StringBuilder result = new StringBuilder(length);
		for(int index = 0; index < length; index++)
			result.append(alphabet.charAt(random.nextInt(alphabet.length())));
		return result.toString();
	}
}
