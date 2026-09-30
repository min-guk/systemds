/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Assert;
import org.junit.Test;

public class PlacementAnalysisNormalizedTextTest {
	@Test
	public void sharedChildrenAreHashedLazilyWithoutMaterialization() throws Exception {
		List<PlacementAnalysis.NormalizedText> nodes = new ArrayList<>();
		var value = PlacementAnalysis.NormalizedText.literal("Aa");
		nodes.add(value);
		for(int level = 0; level < 18; level++) {
			value = new PlacementAnalysis.NormalizedTextBuilder().append(value).append(value).build();
			nodes.add(value);
		}
		var textField = PlacementAnalysis.NormalizedText.class.getDeclaredField("text");
		var materializedField = PlacementAnalysis.NormalizedText.class.getDeclaredField("materialized");
		textField.setAccessible(true);
		materializedField.setAccessible(true);
		var computedField = textField.get(nodes.get(0)).getClass().getDeclaredField("hashComputed");
		computedField.setAccessible(true);
		for(var node : nodes)
			Assert.assertFalse(computedField.getBoolean(textField.get(node)));
		Assert.assertEquals("Aa".repeat(1 << 18).hashCode(), value.hashCode());
		for(var node : nodes) {
			Assert.assertTrue("Shared child hash must be memoized", computedField.getBoolean(textField.get(node)));
			Assert.assertNull("Hashing must not flatten text", materializedField.get(node));
		}
	}

	@Test
	public void concurrentSharedHashPublicationIsStable() throws Exception {
		var pool = Executors.newFixedThreadPool(8);
		try {
			for(int trial = 0; trial < 20; trial++) {
				var value = PlacementAnalysis.NormalizedText.literal("\ud83d\ude00\u0000Aa");
				for(int level = 0; level < 12; level++)
					value = new PlacementAnalysis.NormalizedTextBuilder().append(value).append(value).build();
				var shared = value;
				int expected = "\ud83d\ude00\u0000Aa".repeat(1 << 12).hashCode();
				CountDownLatch start = new CountDownLatch(1);
				List<Future<Integer>> results = new ArrayList<>();
				for(int thread = 0; thread < 8; thread++)
					results.add(pool.submit(() -> { start.await(); return shared.hashCode(); }));
				start.countDown();
				for(Future<Integer> result : results)
					Assert.assertEquals(expected, result.get().intValue());
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void sharedAndDeepTextsHaveExactlyTheFlattenedStringHash() {
		Random random = new Random(0xC4A0A1CAL);
		for(int trial = 0; trial < 500; trial++) {
			StringBuilder flattened = new StringBuilder();
			var pieces = new PlacementAnalysis.NormalizedTextBuilder();
			for(int piece = 0; piece < 20; piece++) {
				StringBuilder literal = new StringBuilder();
				for(int index = 0; index < random.nextInt(30); index++)
					literal.append((char)random.nextInt(65536));
				String value = literal.toString();
				var shared = PlacementAnalysis.NormalizedText.literal(value);
				pieces.append(shared).append("").append(shared);
				flattened.append(value).append(value);
			}
			Assert.assertEquals(flattened.toString().hashCode(), pieces.build().hashCode());
		}
		var deep = PlacementAnalysis.NormalizedText.literal("x");
		StringBuilder flat = new StringBuilder("x");
		for(int index = 0; index < 10_000; index++) {
			deep = new PlacementAnalysis.NormalizedTextBuilder().append(deep).append("y").build();
			flat.append('y');
		}
		Assert.assertEquals(flat.toString().hashCode(), deep.hashCode());
		var dag = PlacementAnalysis.NormalizedText.literal("Aa");
		for(int level = 0; level < 18; level++)
			dag = new PlacementAnalysis.NormalizedTextBuilder().append(dag).append(dag).build();
		Assert.assertEquals("Aa".repeat(1 << 18).hashCode(), dag.hashCode());
		Assert.assertEquals(PlacementAnalysis.NormalizedText.literal("Aa").hashCode(),
			PlacementAnalysis.NormalizedText.literal("BB").hashCode());
		Assert.assertNotEquals(PlacementAnalysis.NormalizedText.literal("Aa"),
			PlacementAnalysis.NormalizedText.literal("BB"));
		Assert.assertEquals(0, PlacementAnalysis.NormalizedText.literal("\u0000\u0000").hashCode());
	}

	@Test
	public void segmentedTextPreservesExactUtf16Contracts() {
		String left = "a|[,\u0000\ud83d\ude00\ud800";
		String right = "z:]\udc00";
		var segmented = new PlacementAnalysis.NormalizedTextBuilder()
			.append(left).append(PlacementAnalysis.NormalizedText.literal(right)).build();
		String expected = left + right;
		Assert.assertEquals(expected, segmented.materialize());
		Assert.assertEquals(expected.hashCode(), segmented.hashCode());
		Assert.assertEquals(0, segmented.compareTo(
			PlacementAnalysis.NormalizedText.literal(expected)));
		Assert.assertEquals(PlacementAnalysis.NormalizedText.literal(expected), segmented);
		List<String> chunks = new ArrayList<>();
		segmented.appendTo(chunks::add);
		Assert.assertEquals(expected, String.join("", chunks));
		Assert.assertFalse(segmented.isBlank());
		Assert.assertTrue(new PlacementAnalysis.NormalizedTextBuilder()
			.append(" \t").append("\n").build().isBlank());
	}

	@Test
	public void lexicalOrderMatchesMaterializedStringOrder() {
		var left = new PlacementAnalysis.NormalizedTextBuilder().append("ab").append("c").build();
		var right = new PlacementAnalysis.NormalizedTextBuilder().append("ab").append("d").build();
		Assert.assertEquals(Integer.signum("abc".compareTo("abd")),
			Integer.signum(left.compareTo(right)));
	}

	@Test
	public void chunkContractPreservesSurrogatePairsAcrossSegmentBoundaries() {
		var split = new PlacementAnalysis.NormalizedTextBuilder()
			.append("prefix\ud83d").append("\ude00suffix").build();
		String expected = "prefix\ud83d\ude00suffix";
		List<String> chunks = new ArrayList<>();
		split.appendTo(chunks::add);
		Assert.assertEquals(expected, String.join("", chunks));
		Assert.assertEquals(expected, split.materialize());
		Assert.assertEquals(expected.hashCode(), split.hashCode());
	}
}
