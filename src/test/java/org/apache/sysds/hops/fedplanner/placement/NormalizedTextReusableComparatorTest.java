/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextBuilder;
import org.junit.Assert;
import org.junit.Test;

public class NormalizedTextReusableComparatorTest {
	@Test
	public void reusableComparatorMatchesExactUtf16OrderAcrossSegmentsAndCollisions() throws Exception {
		Comparator<NormalizedText> comparator = PlacementAnalysis.normalizedTextComparator();
		List<String> fixed = List.of("", "a", "a;", "a|reads=", "Aa", "BB", "한글",
			"prefix\ud83d\ude80", "prefix\ud83d", "\ude80suffix", "\u0000");
		for(String left : fixed)
			for(String right : fixed)
				assertOrder(comparator, segmented(left), segmented(right), left, right);
		Assert.assertEquals(segmented("Aa").hashCode(), segmented("BB").hashCode());
		Assert.assertNotEquals(0, comparator.compare(segmented("Aa"), segmented("BB")));

		Random random = new Random(0xE1170ADEL);
		for(int trial = 0; trial < 500; trial++) {
			String left = randomUtf16(random);
			String right = randomUtf16(random);
			assertOrder(comparator, segmented(left), segmented(right), left, right);
		}
		assertCleared(comparator);
	}

	@Test
	public void failedComparisonClearsCursorsAndDoesNotPoisonReuse() throws Exception {
		Comparator<NormalizedText> comparator = PlacementAnalysis.normalizedTextComparator();
		NormalizedText left = new NormalizedTextBuilder().append("shared-prefix").append("a").build();
		NormalizedText right = new NormalizedTextBuilder().append("shared-prefix").append("b").build();
		Assert.assertTrue(comparator.compare(left, right) < 0);
		try {
			comparator.compare(left, null);
			Assert.fail("null comparison must fail");
		}
		catch(NullPointerException expected) {
			// Expected.
		}
		assertCleared(comparator);
		Assert.assertTrue("the same comparator must remain reusable after failure",
			comparator.compare(right, left) > 0);
		assertCleared(comparator);
	}

	private static void assertOrder(Comparator<NormalizedText> comparator,
		NormalizedText leftText, NormalizedText rightText, String left, String right) {
		Assert.assertEquals(Integer.signum(left.compareTo(right)),
			Integer.signum(comparator.compare(leftText, rightText)));
	}

	private static NormalizedText segmented(String value) {
		int first = value.length() / 3;
		int second = 2 * value.length() / 3;
		return new NormalizedTextBuilder().append(value.substring(0, first))
			.append(NormalizedText.literal(value.substring(first, second)))
			.append(value.substring(second)).build();
	}

	private static String randomUtf16(Random random) {
		StringBuilder value = new StringBuilder();
		for(int index = 0, length = random.nextInt(40); index < length; index++)
			value.append((char)random.nextInt(1 << 16));
		return value.toString();
	}

	private static void assertCleared(Comparator<NormalizedText> comparator) throws Exception {
		Field comparisonField = comparator.getClass().getDeclaredField("comparison");
		comparisonField.setAccessible(true);
		Object comparison = comparisonField.get(comparator);
		for(String cursorName : List.of("leftCursor", "rightCursor")) {
			Field cursorField = comparison.getClass().getDeclaredField(cursorName);
			cursorField.setAccessible(true);
			Object cursor = cursorField.get(comparison);
			Field nodesField = cursor.getClass().getDeclaredField("nodes");
			Field depthField = cursor.getClass().getDeclaredField("depth");
			Field textField = cursor.getClass().getDeclaredField("text");
			nodesField.setAccessible(true);
			depthField.setAccessible(true);
			textField.setAccessible(true);
			Assert.assertEquals(0, depthField.getInt(cursor));
			Assert.assertNull(textField.get(cursor));
			Assert.assertTrue("reusable comparator must not retain a compared text",
				Arrays.stream((Object[])nodesField.get(cursor)).allMatch(node -> node == null));
		}
	}
}
