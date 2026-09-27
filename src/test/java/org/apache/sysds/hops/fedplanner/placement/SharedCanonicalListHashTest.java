/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.util.AbstractList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class SharedCanonicalListHashTest {
	private static class ImmutableCountedList extends AbstractList<String> {
		private final List<String> values;
		private int calls;
		private ImmutableCountedList(List<String> values) { this.values = values; }
		@Override public String get(int i) { return values.get(i); }
		@Override public int size() { return values.size(); }
		@Override public int hashCode() { calls++; return values.hashCode(); }
	}
	private static List<?> wrapped(List<?> values) throws Exception {
		Constructor<?> c = Class.forName(PlacementAnalysis.class.getName() + "$SharedCanonicalList")
			.getDeclaredConstructor(List.class);
		c.setAccessible(true);
		return (List<?>) c.newInstance(values);
	}
	@Test
	public void immutableWrapperHashesContentsOnceAndPreservesListEquality() throws Exception {
		ImmutableCountedList backing = new ImmutableCountedList(List.of("first", "second"));
		List<?> list = wrapped(backing), equal = wrapped(List.of("first", "second"));
		for(int i = 0; i < 100; i++) {
			Assert.assertEquals(List.of("first", "second").hashCode(), list.hashCode());
			Assert.assertEquals(equal, list);
			Assert.assertEquals(List.of("first", "second"), list);
			Assert.assertEquals(list, List.of("first", "second"));
		}
		Assert.assertNotEquals(list, List.of("second", "first"));
		Assert.assertEquals("immutable nested graph hashes must not be repeated", 1, backing.calls);
	}
	@Test
	public void mutationsRemainUnsupported() throws Exception {
		ImmutableCountedList backing = new ImmutableCountedList(List.of("value"));
		List<?> list = wrapped(backing);
		Assert.assertEquals(List.of("value").hashCode(), list.hashCode());
		Assert.assertEquals(list.hashCode(), list.hashCode());
		Assert.assertEquals(1, backing.calls);
		try { list.clear(); Assert.fail("canonical list must remain immutable"); }
		catch(UnsupportedOperationException expected) { }
	}
	@Test
	public void zeroHashIsAlsoMemoized() throws Exception {
		String element = new String(new char[] {4, 26, 0, 19, 29, 23, 4});
		Assert.assertEquals(-31, element.hashCode());
		ImmutableCountedList backing = new ImmutableCountedList(List.of(element));
		List<?> list = wrapped(backing);
		Assert.assertEquals(0, list.hashCode());
		Assert.assertEquals(0, list.hashCode());
		Assert.assertEquals(1, backing.calls);
	}

}
