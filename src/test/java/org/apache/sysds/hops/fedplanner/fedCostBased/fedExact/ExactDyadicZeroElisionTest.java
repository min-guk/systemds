/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/** Zero-aware eligibility may remove only identically positive-zero contributions. */
public class ExactDyadicZeroElisionTest {
	private static final String INVALID_COST = "TEST_INVALID_COST";
	private static final String INVALID_TOTAL = "TEST_INVALID_TOTAL";

	@Test
	public void positiveZeroPaddingPreservesFormerCounterexamplesAndBothCorrectionSigns() {
		double large = 0x1.0p53;
		assertTrue("0.75 is lost downward and creates a positive correction",
			correctionAfterTwo(large, 0.75) > 0);
		assertTrue("1.25 rounds upward and creates a negative correction",
			correctionAfterTwo(large, 1.25) < 0);

		for(double[] canonical : new double[][] {
			{large, 0.75, 1.0},
			{large, 1.25, 1.0},
			{0.75, large, 1.0},
			{1.0, large, 0.75},
			{1.0e16, 1.0, 1.0}
		})
			assertEquals(rawSum(canonical), rawSumWithPositiveZeroPadding(canonical));
	}

	@Test
	public void positiveZeroPaddingPreservesAllZeroSubnormalAndMaximumFiniteBits() {
		for(double[] canonical : new double[][] {
			{},
			{0.0},
			{0.0, 0.0, 0.0},
			{Double.MIN_VALUE},
			{Double.MIN_VALUE, Double.MIN_VALUE},
			{Double.MIN_VALUE, Double.MIN_NORMAL, Double.MIN_VALUE},
			{Double.MAX_VALUE}
		})
			assertEquals(rawSum(canonical), rawSumWithPositiveZeroPadding(canonical));
		assertEquals(Double.doubleToRawLongBits(0.0), rawSumWithPositiveZeroPadding(new double[0]));
	}

	@Test
	public void positiveZeroPaddingPreservesCanonicalOrderAcrossRandomFiniteSequences() {
		Random random = new Random(0x7e10a11L);
		for(int trial = 0; trial < 1_000; trial++) {
			double[] canonical = new double[1 + random.nextInt(40)];
			for(int index = 0; index < canonical.length; index++) {
				int exponent = -1074 + random.nextInt(2011);
				canonical[index] = Math.scalb(0.5 + random.nextDouble() * 0.5, exponent);
			}
			assertEquals("trial=" + trial, rawSum(canonical),
				rawSumWithPositiveZeroPadding(canonical));
		}
	}

	@Test
	public void zeroElisionDoesNotAdmitNegativeZeroOrHideOverflow() {
		ExactCompensatedCostSum negativeZero = new ExactCompensatedCostSum();
		assertThrows(IllegalArgumentException.class, () -> negativeZero.addBits(
			Double.doubleToRawLongBits(-0.0), INVALID_COST, INVALID_TOTAL));
		assertThrows(IllegalArgumentException.class,
			() -> rawSum(Double.MAX_VALUE, Double.MAX_VALUE));
		assertThrows(IllegalArgumentException.class,
			() -> rawSum(Double.MAX_VALUE, 0.0, Double.MAX_VALUE));
	}

	@Test
	public void scalarCertificateRemainsConservativeWithoutPhysicalZeroProof() {
		var certificate = ExactDyadicCosts.certify(-74, 91, 4591, true);
		assertTrue(certificate.supported());
		assertEquals(4591, certificate.canonicalContributionCount());
		assertEquals(4591, certificate.accumulationContributionCount());
		assertEquals(13, certificate.ceilLog2AccumulationCount());
	}

	private static long rawSum(double... values) {
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		for(double value : values)
			sum.addBits(Double.doubleToRawLongBits(value), INVALID_COST, INVALID_TOTAL);
		return sum.totalBits(INVALID_TOTAL);
	}

	private static long rawSumWithPositiveZeroPadding(double[] values) {
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		long zero = Double.doubleToRawLongBits(0.0);
		sum.addBits(zero, INVALID_COST, INVALID_TOTAL);
		for(double value : values) {
			sum.addBits(zero, INVALID_COST, INVALID_TOTAL);
			sum.addBits(Double.doubleToRawLongBits(value), INVALID_COST, INVALID_TOTAL);
			sum.addBits(zero, INVALID_COST, INVALID_TOTAL);
		}
		sum.addBits(zero, INVALID_COST, INVALID_TOTAL);
		return sum.totalBits(INVALID_TOTAL);
	}

	private static double correctionAfterTwo(double first, double second) {
		double next = first + second;
		return Math.abs(first) >= Math.abs(second)
			? (first - next) + second : (second - next) + first;
	}
}
