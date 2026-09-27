/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

public class CanonicalTextChunkComparisonTest {
	private static Object rope(List<Object> pieces) throws Exception {
		Constructor<?> c = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalText")
			.getDeclaredConstructor(List.class);
		c.setAccessible(true);
		return c.newInstance(pieces);
	}
	@SuppressWarnings("unchecked")
	private static int compare(Object left, Object right) {
		return Integer.signum(((Comparable<Object>) left).compareTo(right));
	}
	private static Object segmented(String value, Random random) throws Exception {
		List<Object> pieces = new ArrayList<>();
		for(int i = 0; i < value.length();) {
			int end = Math.min(value.length(), i + 1 + random.nextInt(12));
			String segment = value.substring(i, end);
			pieces.add(random.nextBoolean() ? segment : rope(List.of(segment)));
			i = end;
		}
		return rope(pieces);
	}
	@Test
	public void segmentationAndUnicodeDoNotChangeLegacyLexicalOrder() throws Exception {
		Random r = new Random(923745);
		for(int n = 0; n < 500; n++) {
			StringBuilder prefix = new StringBuilder();
			for(int i = r.nextInt(100); i > 0; i--) prefix.append((char) r.nextInt(65536));
			String a = prefix + (n % 3 == 0 ? "" : "\u0000\ud800\uffff");
			String b = prefix + (n % 3 == 1 ? "" : "\u0000\ud801");
			Assert.assertEquals(Integer.signum(a.compareTo(b)), compare(segmented(a, r), segmented(b, r)));
			Assert.assertEquals(0, compare(segmented(a, r), segmented(a, r)));
		}
	}
	@Test
	public void sharedSubtreesAndLongPrefixesRemainExact() throws Exception {
		String prefix = "common-prefix-".repeat(1000);
		Object shared = rope(List.of(prefix, rope(List.of("nested"))));
		Assert.assertEquals(-1, compare(rope(List.of(shared, "a")), rope(List.of(shared, "b"))));
		Assert.assertEquals(0, compare(rope(List.of(shared, "tail")), rope(List.of(prefix + "nestedtail"))));
		Assert.assertEquals(1, compare(rope(List.of(shared, "tail")), shared));
	}
}
