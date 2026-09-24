/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.Assert;
import org.junit.Test;

public class ExactPlanningModelCaptureTest {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	@Test public void packedTruthMatchesNativeFactorStatusesExactly() throws Exception {
		var left = new ExactCategoricalSolver.Variable("left", 2);
		var right = new ExactCategoricalSolver.Variable("right", 3);
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> {
			if(values[0] == 0 && values[1] == 0) return 0.0;
			if(values[0] == 0 && values[1] == 1) return Double.POSITIVE_INFINITY;
			if(values[0] == 0 && values[1] == 2) throw new IllegalStateException("unknown");
			if(values[0] == 1 && values[1] == 0) return -0.0d;
			if(values[0] == 1 && values[1] == 1) return 1.0d;
			return 0.0;
		});

		ExactPlanningModelCapture.PackedTruth packed =
			ExactPlanningModelCapture.packFactorTruth(factor, 6);
		Assert.assertArrayEquals(new int[] {0, 1, 2, 2, 2, 0},
			java.util.stream.IntStream.range(0, 6).map(packed::statusCode).toArray());
		Assert.assertEquals(2, packed.allow());
		Assert.assertEquals(1, packed.reject());
		Assert.assertEquals(3, packed.unknown());
	}

	@Test public void groupedTruthUsesRejectUnknownAllowPrecedenceAndContinuesEvaluation() {
		var variable = new ExactCategoricalSolver.Variable("value", 1);
		AtomicBoolean evaluatedAfterException = new AtomicBoolean();
		var unknown = ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
			throw new IllegalStateException("unknown");
		});
		var reject = ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
			evaluatedAfterException.set(true);
			return Double.POSITIVE_INFINITY;
		});
		var group = new ExactPlanningModelCapture.FactorGroup(List.of(0), List.of(2, 7),
			List.of(unknown, reject));

		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.REJECT,
			ExactPlanningModelCapture.evaluateFactorGroup(group, new int[] {0}));
		Assert.assertTrue(evaluatedAfterException.get());
		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.ALLOW,
			ExactPlanningModelCapture.classifyCost(+0.0d));
		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.UNKNOWN,
			ExactPlanningModelCapture.classifyCost(-0.0d));
		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.UNKNOWN,
			ExactPlanningModelCapture.classifyCost(Double.NaN));
		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.UNKNOWN,
			ExactPlanningModelCapture.classifyCost(Double.NEGATIVE_INFINITY));
	}

	@Test public void groupedTruthShortCircuitsAfterReject() {
		var variable = new ExactCategoricalSolver.Variable("value", 1);
		AtomicBoolean evaluatedAfterReject = new AtomicBoolean();
		var reject = ExactCategoricalSolver.Factor.lazy(List.of(variable),
			values -> Double.POSITIVE_INFINITY);
		var later = ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
			evaluatedAfterReject.set(true);
			return 0.0;
		});
		var group = new ExactPlanningModelCapture.FactorGroup(List.of(0), List.of(0, 1),
			List.of(reject, later));
		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.REJECT,
			ExactPlanningModelCapture.evaluateFactorGroup(group, new int[] {0}));
		Assert.assertFalse(evaluatedAfterReject.get());
		Assert.assertEquals(1, rawConjunction(List.of(reject, later), new int[] {0}));
	}

	@Test public void evaluatedProducerErrorPropagatesFailClosed() {
		var variable = new ExactCategoricalSolver.Variable("value", 1);
		var broken = ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
			throw new AssertionError("fatal");
		});
		var group = new ExactPlanningModelCapture.FactorGroup(List.of(0), List.of(0),
			List.of(broken));
		try {
			ExactPlanningModelCapture.evaluateFactorGroup(group, new int[] {0});
			Assert.fail("An evaluated producer Error must fail the capture");
		}
		catch(AssertionError expected) {
			Assert.assertEquals("fatal", expected.getMessage());
		}
	}

	@Test public void rejectInDifferentScopeDoesNotHideProducerError() {
		var left = new ExactCategoricalSolver.Variable("left", 1);
		var right = new ExactCategoricalSolver.Variable("right", 1);
		var reject = ExactCategoricalSolver.Factor.lazy(List.of(left),
			values -> Double.POSITIVE_INFINITY);
		var broken = ExactCategoricalSolver.Factor.lazy(List.of(right), values -> {
			throw new AssertionError("different-scope-fatal");
		});
		var positions = new IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		positions.put(left, 0);
		positions.put(right, 1);
		var groups = ExactPlanningModelCapture.aggregateFactors(List.of(reject, broken), positions)
			.groups();
		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.REJECT,
			ExactPlanningModelCapture.evaluateFactorGroup(groups.get(0), new int[] {0}));
		try {
			ExactPlanningModelCapture.evaluateFactorGroup(groups.get(1), new int[] {0});
			Assert.fail("A producer Error in another materialized group must fail capture");
		}
		catch(AssertionError expected) {
			Assert.assertEquals("different-scope-fatal", expected.getMessage());
		}
	}

	@Test public void zeroArityFactorsFormOneCellListGroup() {
		var allow = ExactCategoricalSolver.Factor.lazy(List.of(), values -> 0.0);
		var unknown = ExactCategoricalSolver.Factor.lazy(List.of(), values -> {
			throw new IllegalStateException("unknown");
		});
		var aggregation = ExactPlanningModelCapture.aggregateFactors(List.of(allow, unknown),
			new IdentityHashMap<>());
		Assert.assertEquals(List.of(List.of(), List.of()), aggregation.sourceFactorScopes());
		Assert.assertEquals(1, aggregation.groups().size());
		var group = aggregation.groups().get(0);
		Assert.assertEquals(List.of(), group.scope());
		Assert.assertEquals(List.of(0, 1), group.sourceFactorIndices());
		Assert.assertEquals(java.math.BigInteger.ONE,
			ExactPlanningModelCapture.factorCellCount(group.factors().get(0).scope()));
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.LIST,
			ExactPlanningModelCapture.selectTruthStorage(java.math.BigInteger.ONE,
				java.math.BigInteger.ONE, ExactPlanningModelCapture.MAX_PACKED_FACTOR_CELLS));
		Assert.assertEquals(ExactPlanningModelCapture.FactorTruth.UNKNOWN,
			ExactPlanningModelCapture.evaluateFactorGroup(group, new int[0]));
	}

	@Test public void orderedScopeAggregationIsDeterministicAndDoesNotMergeReverseScope() {
		var left = new ExactCategoricalSolver.Variable("left", 2);
		var right = new ExactCategoricalSolver.Variable("right", 2);
		var positions = new IdentityHashMap<ExactCategoricalSolver.Variable,Integer>();
		positions.put(left, 4);
		positions.put(right, 9);
		var xy0 = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> 0.0);
		var yx = ExactCategoricalSolver.Factor.lazy(List.of(right, left), values -> 0.0);
		var x = ExactCategoricalSolver.Factor.lazy(List.of(left), values -> 0.0);
		var xy1 = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> 0.0);

		var aggregation = ExactPlanningModelCapture.aggregateFactors(
			List.of(xy0, yx, x, xy1), positions);
		Assert.assertEquals(List.of(List.of(4, 9), List.of(9, 4), List.of(4), List.of(4, 9)),
			aggregation.sourceFactorScopes());
		Assert.assertEquals(3, aggregation.groups().size());
		Assert.assertEquals(List.of(4, 9), aggregation.groups().get(0).scope());
		Assert.assertEquals(List.of(0, 3), aggregation.groups().get(0).sourceFactorIndices());
		Assert.assertEquals(List.of(9, 4), aggregation.groups().get(1).scope());
		Assert.assertEquals(List.of(1), aggregation.groups().get(1).sourceFactorIndices());
		Assert.assertEquals(List.of(4), aggregation.groups().get(2).scope());
		Assert.assertEquals(List.of(2), aggregation.groups().get(2).sourceFactorIndices());
	}

	@Test public void groupedTruthMatchesRawConjunctionForEveryTinyAssignment() throws Exception {
		var left = new ExactCategoricalSolver.Variable("left", 2);
		var right = new ExactCategoricalSolver.Variable("right", 2);
		var first = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> {
			int row = values[0] * 2 + values[1];
			if(row == 1) return Double.POSITIVE_INFINITY;
			if(row == 2) throw new IllegalStateException("unknown");
			return 0.0;
		});
		var second = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> {
			int row = values[0] * 2 + values[1];
			if(row == 1) throw new IllegalArgumentException("unknown");
			if(row == 2) return Double.POSITIVE_INFINITY;
			if(row == 3) return -0.0d;
			return 0.0;
		});
		var group = new ExactPlanningModelCapture.FactorGroup(List.of(0, 1), List.of(0, 1),
			List.of(first, second));
		int[] expected = {0, 1, 1, 2};
		for(int row = 0; row < 4; row++) {
			int[] values = {row / 2, row % 2};
			int grouped = ExactPlanningModelCapture.evaluateFactorGroup(group, values).code();
			Assert.assertEquals(expected[row], grouped);
			Assert.assertEquals(rawConjunction(List.of(first, second), values), grouped);
		}
		var packed = ExactPlanningModelCapture.packFactorTruth(group, 4);
		Assert.assertArrayEquals(expected,
			java.util.stream.IntStream.range(0, 4).map(packed::statusCode).toArray());
	}

	@Test public void observedLargestFactorSelectsPackedWithoutEvaluation() {
		var packed = ExactPlanningModelCapture.selectTruthStorage(
			java.math.BigInteger.valueOf(38_102_016), java.math.BigInteger.ZERO,
			ExactPlanningModelCapture.MAX_PACKED_FACTOR_CELLS);
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.PACKED, packed);
	}

	@Test public void packedTruthSelectionHonorsExactSingleAndGlobalCaps() {
		var singleCap = ExactPlanningModelCapture.MAX_SINGLE_PACKED_FACTOR_CELLS;
		var globalCap = ExactPlanningModelCapture.MAX_PACKED_FACTOR_CELLS;
		Assert.assertEquals(java.math.BigInteger.valueOf(40_000_000), singleCap);
		Assert.assertEquals(java.math.BigInteger.valueOf(210_000_000), globalCap);
		Assert.assertTrue(java.math.BigInteger.valueOf(202_728_043).compareTo(globalCap) <= 0);
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.PACKED,
			ExactPlanningModelCapture.selectTruthStorage(singleCap, java.math.BigInteger.ZERO, globalCap));
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.OPAQUE,
			ExactPlanningModelCapture.selectTruthStorage(singleCap.add(java.math.BigInteger.ONE),
				java.math.BigInteger.ZERO, globalCap));
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.PACKED,
			ExactPlanningModelCapture.selectTruthStorage(singleCap, java.math.BigInteger.ZERO, singleCap));
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.OPAQUE,
			ExactPlanningModelCapture.selectTruthStorage(singleCap, java.math.BigInteger.ZERO,
				singleCap.subtract(java.math.BigInteger.ONE)));
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.LIST,
			ExactPlanningModelCapture.selectTruthStorage(java.math.BigInteger.valueOf(100_000),
				java.math.BigInteger.valueOf(100_000), globalCap));
		Assert.assertEquals(ExactPlanningModelCapture.TruthStorage.PACKED,
			ExactPlanningModelCapture.selectTruthStorage(java.math.BigInteger.valueOf(100_000),
				java.math.BigInteger.valueOf(99_999), globalCap));
	}

	@Test public void packedByteCountIsExactAtBoundaryAndRejectsOverflowingInput() {
		Assert.assertEquals(10_000_000,
			ExactPlanningModelCapture.packedByteCount(java.math.BigInteger.valueOf(40_000_000)));
		Assert.assertEquals(1,
			ExactPlanningModelCapture.packedByteCount(java.math.BigInteger.ONE));
		try {
			ExactPlanningModelCapture.packedByteCount(java.math.BigInteger.valueOf(40_000_001));
			Assert.fail("Per-factor cell budget must remain bounded");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("per-factor cell limit"));
		}
		try {
			ExactPlanningModelCapture.packedByteCount(java.math.BigInteger.valueOf(Long.MAX_VALUE).pow(2));
			Assert.fail("Overflow-sized cell counts must fail closed");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("per-factor cell limit"));
		}
	}

	@Test public void factorCellCountDoesNotOverflowLongArithmetic() {
		var scope = List.of(
			new ExactCategoricalSolver.Variable("a", Integer.MAX_VALUE),
			new ExactCategoricalSolver.Variable("b", Integer.MAX_VALUE),
			new ExactCategoricalSolver.Variable("c", Integer.MAX_VALUE));
		Assert.assertEquals(java.math.BigInteger.valueOf(Integer.MAX_VALUE).pow(3),
			ExactPlanningModelCapture.factorCellCount(scope));
	}

	@Test public void streamedArtifactReceiptDescribesUncompressedJson() throws Exception {
		Path directory = Files.createTempDirectory("e-model-stream-");
		Path destination = directory.resolve("artifact.json.gz");
		Map<String,Object> artifact = new LinkedHashMap<>();
		artifact.put("schema", "closed-e-native-model-artifact-v2");
		artifact.put("values", java.util.List.of("alpha", "beta", "gamma"));
		artifact.put("nested", Map.of("z", 3, "a", 1));
		byte[] expected = JSON.writeValueAsBytes(artifact);

		ExactPlanningModelCapture.ArtifactWrite receipt =
			ExactPlanningModelCapture.writeArtifact(destination, artifact);
		byte[] actual;
		try(GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(destination));
			ByteArrayOutputStream plain = new ByteArrayOutputStream()) {
			gzip.transferTo(plain);
			actual = plain.toByteArray();
		}

		Assert.assertArrayEquals(expected, actual);
		Assert.assertEquals(expected.length, receipt.bytes());
		Assert.assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(expected)),
			receipt.sha256());
	}

	@Test public void failedStreamDoesNotReplaceExistingArtifact() throws Exception {
		Path directory = Files.createTempDirectory("e-model-stream-failure-");
		Path destination = directory.resolve("artifact.json.gz");
		byte[] prior = new byte[] {1, 2, 3, 4};
		Files.write(destination, prior);
		try {
			ExactPlanningModelCapture.writeArtifact(destination, Map.of("broken", new BrokenValue()));
			Assert.fail("Serialization failure must propagate");
		}
		catch(Exception expected) {
			Assert.assertTrue(expected.getMessage().contains("boom"));
		}
		Assert.assertArrayEquals(prior, Files.readAllBytes(destination));
		try(var entries = Files.list(directory)) {
			Assert.assertEquals(1, entries.count());
		}
	}

	public static final class BrokenValue {
		public String getValue() { throw new IllegalStateException("boom"); }
	}

	private static int rawConjunction(List<ExactCategoricalSolver.Factor> factors, int[] values) {
		int result = 0;
		for(var factor : factors) {
			int current;
			try {
				double cost = factor.cost(values);
				current = cost == 0.0 && Double.doubleToRawLongBits(cost)
					!= Double.doubleToRawLongBits(-0.0d) ? 0
					: cost == Double.POSITIVE_INFINITY ? 1 : 2;
			}
			catch(RuntimeException error) { current = 2; }
			if(current == 1) return 1;
			else if(current == 2 && result == 0) result = 2;
		}
		return result;
	}

	private static String hex(byte[] digest) {
		StringBuilder result = new StringBuilder(digest.length * 2);
		for(byte value : digest) result.append(String.format("%02x", value & 0xff));
		return result.toString();
	}
}
