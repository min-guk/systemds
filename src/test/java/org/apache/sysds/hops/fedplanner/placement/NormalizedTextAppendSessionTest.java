/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextBuilder;

public class NormalizedTextAppendSessionTest {
	@Test public void sharedProperSubtreesReplayTheSameStringWithoutChangingOrdinaryChunks() throws Exception {
		var child = new NormalizedTextBuilder().append("alpha").append(":").append("beta").build();
		var root = new NormalizedTextBuilder().append(child).append("/").append(child).build();
		List<String> ordinary = new ArrayList<>(); root.appendTo(ordinary::add);
		Assert.assertEquals(List.of("alpha", ":", "beta", "/", "alpha", ":", "beta"), ordinary);
		Object session = session(4096, 32, 1_000_000);
		List<String> first = chunks(session, root), second = chunks(session, root);
		Assert.assertEquals(root.materialize(), String.join("", first));
		Assert.assertEquals(List.of("alpha:beta", "/", "alpha:beta"), first);
		Assert.assertSame(first.get(0), first.get(2)); Assert.assertSame(first.get(0), second.get(0));
		Assert.assertEquals("only the proper shared subtree is cached", 1, cache(session).size());
		List<String> after = new ArrayList<>(); root.appendTo(after::add); Assert.assertEquals(ordinary, after);
	}
	@Test public void zeroTinyAndEntryBudgetsFallBackWithoutConflatingEqualHashes() throws Exception {
		var a = new NormalizedTextBuilder().append("A").append("a").build();
		var b = new NormalizedTextBuilder().append("B").append("B").build();
		var root = new NormalizedTextBuilder().append(a).append(b).append(a).append(b).build();
		for(Object session : List.of(session(0, 32, 1_000_000), session(8, 0, 1_000_000),
			session(8, 32, 0), session(8, 32, 1), session(1, 32, 1_000_000))) {
			Assert.assertEquals("AaBBAaBB", String.join("", chunks(session, root)));
			Assert.assertTrue(cache(session).isEmpty());
		}
		Object one = session(8, 1, 1_000_000);
		Assert.assertEquals("AaBBAaBB", String.join("", chunks(one, root))); Assert.assertEquals(1, cache(one).size());
		Object both = session(8, 2, 1_000_000);
		Assert.assertEquals("AaBBAaBB", String.join("", chunks(both, root))); Assert.assertEquals(2, cache(both).size());
	}
	@Test public void exactAndAggregateRetainedBudgetsAreRespected() throws Exception {
		var a = new NormalizedTextBuilder().append("A").append("a").build();
		var b = new NormalizedTextBuilder().append("B").append("B").build();
		var single = new NormalizedTextBuilder().append(a).build();
		var pair = new NormalizedTextBuilder().append(a).append(b).append(a).build();
		Object roomy = session(8, 32, 1_000_000);
		chunks(roomy, single);
		Field retained = roomy.getClass().getDeclaredField("retainedBytes");
		retained.setAccessible(true);
		long weight = retained.getLong(roomy);
		Assert.assertTrue(weight > 0);
		Object exact = session(8, 32, weight);
		Assert.assertEquals("AaBBAa", String.join("", chunks(exact, pair)));
		Assert.assertEquals(1, cache(exact).size());
		Assert.assertEquals(weight, retained.getLong(exact));
		Object shortBudget = session(8, 32, weight - 1);
		Assert.assertEquals("Aa", String.join("", chunks(shortBudget, single)));
		Assert.assertTrue(cache(shortBudget).isEmpty());
		Object two = session(8, 32, 2 * weight);
		for(int i = 0; i < 10; i++) {
			Assert.assertEquals("AaBBAa", String.join("", chunks(two, pair)));
			Assert.assertEquals(2, cache(two).size());
			Assert.assertEquals(2 * weight, retained.getLong(two));
		}
	}
	@Test public void deepRopesAndRetainedGraphAdmissionAreStackSafe() throws Exception {
		var deep = NormalizedText.literal("x");
		for(int i=0; i<10_000; i++) deep = new NormalizedTextBuilder().append(deep).append("y").build();
		String expected = "x" + "y".repeat(10_000);
		for(Object session : List.of(session(4096, 32, 1_000_000), session(4096, 32, 1)))
			Assert.assertEquals(expected, String.join("", chunks(session, deep)));
		var unary = NormalizedText.literal("x");
		for(int i=0; i<10_000; i++) unary = new NormalizedTextBuilder().append(unary).build();
		Object small = session(4096, 32, 1024);
		Assert.assertEquals("x", String.join("", chunks(small, unary)));
		Field text = NormalizedText.class.getDeclaredField("text"); text.setAccessible(true);
		Field pieces = text.get(unary).getClass().getDeclaredField("pieces"); pieces.setAccessible(true);
		Object firstChild = ((Object[])pieces.get(text.get(unary)))[0];
		Assert.assertFalse("short text must not admit a huge retained key graph", cache(small).containsKey(firstChild));
	}
	@Test public void consumerReentryAndExceptionsDoNotCorruptTraversal() throws Exception {
		var child = new NormalizedTextBuilder().append("a").append("b").build();
		var root = new NormalizedTextBuilder().append(child).append("/").append(child).build();
		Object session = session(32, 32, 100_000);
		StringBuilder outer = new StringBuilder(); List<String> nested = new ArrayList<>();
		append(session, root, value -> {
			outer.append(value);
			try { nested.add(String.join("", chunks(session, child))); }
			catch(Exception ex) { throw new AssertionError(ex); }
		});
		Assert.assertEquals("ab/ab", outer.toString()); Assert.assertEquals(List.of("ab","ab","ab"), nested);
		try { append(session, root, value -> { throw new IllegalStateException("fixture"); }); Assert.fail(); }
		catch(java.lang.reflect.InvocationTargetException ex) { Assert.assertEquals("fixture", ex.getCause().getMessage()); }
		Assert.assertEquals("ab/ab", String.join("", chunks(session, root)));
	}
	@Test public void fingerprintBytesMatchFlattenedUtf8AcrossColdHotAndDisabledReplay() throws Exception {
		Random random = new Random(0x292029L);
		for(int trial=0; trial<100; trial++) {
			var high = new NormalizedTextBuilder().append("prefix").append("\ud83d").build();
			var low = new NormalizedTextBuilder().append("").append("\ude00").append("suffix").build();
			StringBuilder unusual = new StringBuilder();
			for(int i=0; i<30; i++) unusual.append((char)random.nextInt(65536));
			var root = new NormalizedTextBuilder().append(high).append(low).append(high).append(low)
				.append(unusual.toString()).append("\ud800").build();
			String expected = root.materialize() + root.materialize() + "1f,";
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected.getBytes(StandardCharsets.UTF_8)));
			Assert.assertEquals(hash, fingerprint(root, false)); Assert.assertEquals(hash, fingerprint(root, true));
		}
	}
	private static Object session(int chars, int entries, long bytes) throws Exception {
		return Class.forName(PlacementAnalysis.class.getName()+"$NormalizedTextAppendSession")
			.getConstructor(int.class,int.class,long.class).newInstance(chars,entries,bytes);
	}
	private static void append(Object session, NormalizedText text, Consumer<String> consumer) throws Exception {
		session.getClass().getMethod("appendTo", NormalizedText.class, Consumer.class).invoke(session,text,consumer);
	}
	private static List<String> chunks(Object session, NormalizedText text) throws Exception {
		List<String> result = new ArrayList<>(); append(session,text,result::add); return result;
	}
	private static Map<?,?> cache(Object session) throws Exception {
		Field field = session.getClass().getDeclaredField("chunks"); field.setAccessible(true); return (Map<?,?>)field.get(session);
	}
	private static String fingerprint(NormalizedText text, boolean disabled) throws Exception {
		Class<?> writer = Class.forName("org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel$FingerprintWriter");
		Constructor<?> constructor = writer.getDeclaredConstructor(); constructor.setAccessible(true); Object target = constructor.newInstance();
		if(disabled) { Field f=writer.getDeclaredField("signatureReplay"); f.setAccessible(true); f.set(target,session(0,0,0)); }
		Method append=writer.getDeclaredMethod("appendSignature",NormalizedText.class); append.setAccessible(true);
		append.invoke(target,text); append.invoke(target,text);
		Method hex=writer.getDeclaredMethod("appendUnsignedHexWithComma",long.class); hex.setAccessible(true); hex.invoke(target,31L);
		Method finish=writer.getDeclaredMethod("finish"); finish.setAccessible(true); return (String)finish.invoke(target);
	}
}
