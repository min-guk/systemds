/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.Assert;
import org.junit.Test;

/** Locks the physical-cost factor fingerprint byte stream while avoiding per-cell text objects. */
public class ExactPhysicalCostFingerprintStreamingTest {
	@Test
	public void streamingHexMatchesLegacyReferenceForRawBits() throws Exception {
		long[] edgeBits = {
			0L,
			Double.doubleToRawLongBits(-0.0),
			Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
			Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY),
			0x7ff8000000000001L,
			1L,
			15L,
			16L,
			Long.MAX_VALUE,
			Long.MIN_VALUE,
			-1L
		};
		int randomValues = 100_000;
		double[] values = new double[edgeBits.length + randomValues];
		for(int index = 0; index < edgeBits.length; index++)
			values[index] = Double.longBitsToDouble(edgeBits[index]);
		SplittableRandom random = new SplittableRandom(1011081480L);
		for(int index = edgeBits.length; index < values.length; index++)
			values[index] = Double.longBitsToDouble(random.nextLong());
		var variable = new ExactCategoricalSolver.Variable("raw-bits", values.length);
		var factor = ExactCategoricalSolver.Factor.dense(List.of(variable), values);
		Assert.assertEquals(legacyFingerprint(values),
			ExactPhysicalCostModel.physicalFactorValuesFingerprint(factor));
	}

	@Test
	public void streamingDenseOrderMatchesLegacyMultidimensionalOrder() throws Exception {
		var rows = new ExactCategoricalSolver.Variable("rows", 2);
		var columns = new ExactCategoricalSolver.Variable("columns", 3);
		double[] values = {
			Double.longBitsToDouble(0x0000000000000001L),
			Double.longBitsToDouble(0x0000000000000010L),
			Double.longBitsToDouble(0x0000000000000100L),
			Double.longBitsToDouble(0x1000000000000000L),
			Double.longBitsToDouble(0x7ff0000000000000L),
			Double.longBitsToDouble(0x8000000000000000L)
		};
		var factor = ExactCategoricalSolver.Factor.dense(List.of(rows, columns), values);
		Assert.assertEquals(legacyFingerprint(values),
			ExactPhysicalCostModel.physicalFactorValuesFingerprint(factor));
	}

	private static String legacyFingerprint(double[] values) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		for(double value : values) {
			digest.update(Long.toUnsignedString(Double.doubleToRawLongBits(value), 16)
				.getBytes(StandardCharsets.UTF_8));
			digest.update((byte)',');
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
