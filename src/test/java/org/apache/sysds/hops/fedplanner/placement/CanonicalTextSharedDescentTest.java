/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class CanonicalTextSharedDescentTest {
	private static final Class<?> CANONICAL_TEXT = nestedClass("CanonicalText");
	private static final Class<?> COMPARISON = nestedClass("CanonicalTextComparison");

	@Test
	public void sharedPendingChildDoesNotGrowEitherCursorStack() throws Exception {
		Object shared = nested("shared-body-정확-\uD800\uDC00", 256);
		Object left = rope("equal-prefix/", "", shared, "/a");
		Object right = rope("equal-prefix/", "", shared, "/b");
		Object comparison = comparison();

		assertFlattenedOrder(comparison, left, right);

		Assert.assertEquals("the left cursor must skip the pending shared child without descending",
			8, cursorNodeCapacity(comparison, "leftCursor"));
		Assert.assertEquals("the right cursor must skip the pending shared child without descending",
			8, cursorNodeCapacity(comparison, "rightCursor"));
	}

	@Test
	public void deepAndDifferentlySegmentedTextsMatchFlattenedUtf16Ordering() throws Exception {
		Object comparison = comparison();
		Object shared = nested("common-\u0000-\uD800-\uDC00-끝", 192);
		Object pending = nested("abcdef", 96);
		Object[][] pairs = {
			{rope(shared, nested("-left", 160)), rope(shared, nested("-right", 160))},
			{rope("pending/", pending, "x"), rope("pending/abc", "defy")},
			{nested("unequal-depth", 1), nested("unequal-depth", 130)},
			{rope("abc", "z"), rope("abcd", "a")},
			{rope("ab", rope("c", "def")), rope(rope("abcd"), "ef")},
			{rope("\uD800", rope("x")), rope("\uD801")},
			{rope("\uDC00"), rope("\uD800", "\uFFFF")},
			{rope(), rope(rope(), "")},
			{rope(rope(), "a"), rope("", rope("b"))}
		};
		for(Object[] pair : pairs)
			assertFlattenedOrder(comparison, pair[0], pair[1]);
	}

	@Test
	public void standaloneReadersTraversePendingChildrenAndDoNotMisclassifyBlankText() throws Exception {
		Constructor<PlacementAnalysis.NormalizedText> constructor =
			PlacementAnalysis.NormalizedText.class.getDeclaredConstructor(CANONICAL_TEXT);
		constructor.setAccessible(true);
		PlacementAnalysis.NormalizedText blank = constructor.newInstance(
			rope(" \t", rope(rope(), "\n", rope("\u2003"))));
		Assert.assertTrue(blank.isBlank());
		Assert.assertTrue(constructor.newInstance(rope(rope(), "")).isBlank());
		PlacementAnalysis.NormalizedText value = constructor.newInstance(
			rope(" \t", rope("", nested("정확-\uD800-x-\uDC00", 64)), "\n"));
		String expected = " \t정확-\uD800-x-\uDC00\n";
		Assert.assertFalse("whitespace before a pending nonblank child is not the whole text", value.isBlank());
		StringBuilder appended = new StringBuilder("prefix:");
		value.appendTo(appended::append);
		Assert.assertEquals("prefix:" + expected, appended.toString());
		Assert.assertEquals(expected, value.materialize());
		Assert.assertEquals(expected.hashCode(), value.hashCode());
		Assert.assertEquals(expected.length(), value.length());
	}

	@Test
	public void reusableComparatorRemainsExactAfterIdentityAndEarlyReturns() throws Exception {
		Object comparison = comparison();
		Object identical = nested("identity", 64);
		Assert.assertEquals(0, compare(comparison, identical, identical));

		Object earlyLeft = rope("z", nested("unvisited", 96));
		Object earlyRight = rope("a", nested("unvisited", 96));
		assertFlattenedOrder(comparison, earlyLeft, earlyRight);

		Object equalLeft = rope("seg", rope("ment", rope("ed")));
		Object equalRight = rope(rope("segment"), "ed");
		assertFlattenedOrder(comparison, equalLeft, equalRight);
		assertFlattenedOrder(comparison, equalRight, equalLeft);

		Object deepLeft = nested("prefix-\uD800-x", 128);
		Object deepRight = nested("prefix-\uD800-y", 128);
		assertFlattenedOrder(comparison, deepLeft, deepRight);
		assertFlattenedOrder(comparison, deepRight, deepLeft);
	}

	private static void assertFlattenedOrder(Object comparison, Object left, Object right) throws Exception {
		int expected = Integer.signum(flatten(left).compareTo(flatten(right)));
		Assert.assertEquals(expected, Integer.signum(compare(comparison, left, right)));
		Assert.assertEquals(-expected, Integer.signum(compare(comparison, right, left)));
	}

	private static Object nested(String literal, int depth) throws Exception {
		Object value = rope(literal);
		for(int index = 0; index < depth; index++)
			value = rope(value);
		return value;
	}

	private static Object rope(Object... pieces) throws Exception {
		Constructor<?> constructor = CANONICAL_TEXT.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(List.of(pieces));
	}

	private static Object comparison() throws Exception {
		Constructor<?> constructor = COMPARISON.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static int compare(Object comparison, Object left, Object right) throws Exception {
		Method compare = COMPARISON.getDeclaredMethod("compare", CANONICAL_TEXT, CANONICAL_TEXT);
		compare.setAccessible(true);
		return (int)compare.invoke(comparison, left, right);
	}

	private static int cursorNodeCapacity(Object comparison, String cursorName) throws Exception {
		Field cursorField = COMPARISON.getDeclaredField(cursorName);
		cursorField.setAccessible(true);
		Object cursor = cursorField.get(comparison);
		Field nodes = cursor.getClass().getDeclaredField("nodes");
		nodes.setAccessible(true);
		return Array.getLength(nodes.get(cursor));
	}

	private static String flatten(Object value) throws Exception {
		if(value instanceof String literal)
			return literal;
		Field pieces = CANONICAL_TEXT.getDeclaredField("pieces");
		pieces.setAccessible(true);
		StringBuilder result = new StringBuilder();
		for(Object piece : (Object[])pieces.get(value))
			result.append(flatten(piece));
		return result.toString();
	}

	private static Class<?> nestedClass(String simpleName) {
		try {
			return Class.forName(PlacementAnalysis.class.getName() + "$" + simpleName);
		}
		catch(ClassNotFoundException exception) {
			throw new AssertionError(exception);
		}
	}
}
