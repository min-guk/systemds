/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Random;

import org.junit.Test;

public class ExactDyadicCostsTest {
	private static final long WORD_BASE = 1L << 53;
	private static final long WORD_MASK = WORD_BASE - 1;

	@Test
	public void checkedTwoWordAdditionCarriesWithoutBinary64Rounding() {
		var sum = ExactDyadicCosts.ofWords(0, WORD_MASK).add(ExactDyadicCosts.ofWords(0, WORD_MASK));
		assertEquals(1, sum.highWord());
		assertEquals(WORD_BASE - 2, sum.lowWord());

		var maximum = ExactDyadicCosts.ofWords(WORD_MASK, WORD_MASK - 1)
			.add(ExactDyadicCosts.ofWords(0, 1));
		assertEquals(WORD_MASK, maximum.highWord());
		assertEquals(WORD_MASK, maximum.lowWord());
		assertThrows(ArithmeticException.class,
			() -> maximum.add(ExactDyadicCosts.ofWords(0, 1)));
	}

	@Test
	public void exactOrderingDistinguishesBinary64RoundedTies() {
		var even = ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(0x1p53), 0);
		var next = even.add(ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(1), 0));
		assertTrue(even.compareTo(next) < 0);
		assertEquals(Double.doubleToRawLongBits(0x1p53), even.toDoubleBits(0));
		assertEquals(Double.doubleToRawLongBits(0x1p53), next.toDoubleBits(0));
	}

	@Test
	public void repairsConditionalOptimumFromNumericGateCounterexample() {
		int q = -2;
		var common = ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(0x1p53), q);
		var branchZero = common
			.add(ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(.75), q))
			.add(ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(1), q));
		var branchOne = common.add(ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(1), q));
		assertTrue(branchOne.compareTo(branchZero) < 0);
		assertEquals(Double.doubleToRawLongBits(0x1.0000000000001p53), branchZero.toDoubleBits(q));
		assertEquals(Double.doubleToRawLongBits(0x1p53), branchOne.toDoubleBits(q));
	}

	@Test
	public void finalConversionUsesGuardStickyAndTiesToEven() {
		assertEquals(Double.doubleToRawLongBits(0x1p53),
			fromBigInteger(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE))
				.toDoubleBits(0));
		assertEquals(Double.doubleToRawLongBits(0x1.0000000000002p53),
			fromBigInteger(BigInteger.ONE.shiftLeft(53).add(BigInteger.valueOf(3)))
				.toDoubleBits(0));
	}

	@Test
	public void conversionCoversMinimumSubnormalNormalBoundaryAndMaxFinite() {
		assertEquals(Double.doubleToRawLongBits(Double.MIN_VALUE),
			ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(Double.MIN_VALUE), -1074)
				.toDoubleBits(-1074));
		assertEquals(Double.doubleToRawLongBits(Double.MIN_NORMAL),
			ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(Double.MIN_NORMAL), -1074)
				.toDoubleBits(-1074));
		assertEquals(Double.doubleToRawLongBits(Double.MAX_VALUE),
			ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(Double.MAX_VALUE), 919)
				.toDoubleBits(919));
	}

	@Test
	public void invalidInputsAndWiderThanCarrierValuesAreRejected() {
		for(double invalid : new double[] {-0.0, -1.0, Double.NaN,
			Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
			assertThrows(IllegalArgumentException.class,
				() -> ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(invalid), -1074));
		assertThrows(IllegalArgumentException.class,
			() -> ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(Double.MAX_VALUE), -1074));
		assertThrows(IllegalArgumentException.class,
			() -> ExactDyadicCosts.fromRawBits(Double.doubleToRawLongBits(1), 1));
	}

	@Test
	public void activationCertificateRequiresCorrespondenceAndAllNumericBounds() {
		var actual = ExactDyadicCosts.certify(-74, 91, 4591, true);
		assertTrue(actual.supported());
		assertFalse(actual.hasPhysicalAuthority());
		assertEquals(-74, actual.q());
		assertEquals(91, actual.maximumSumBits());
		assertEquals(4591, actual.canonicalContributionCount());
		assertEquals(13, actual.ceilLog2AccumulationCount());
		assertEquals(104, actual.bitsWithAccumulationHeadroom());

		assertFalse(ExactDyadicCosts.certify(-74, 91, 4591, false).supported());
		assertFalse(ExactDyadicCosts.certify(1000, 54, 1, true).supported());
		assertFalse(ExactDyadicCosts.certify(-1075, 1, 1, true).supported());
		assertFalse(ExactDyadicCosts.certify(0, 107, 1, true).supported());
		assertFalse(ExactDyadicCosts.certify(1024, 1, 1, true).supported());
		assertFalse(ExactDyadicCosts.certify(0, 1, 0, true).supported());
		assertTrue(ExactDyadicCosts.certify(Integer.MAX_VALUE, 0, 0, true).supported());
	}

	@Test
	public void randomizedCarrierMatchesBigIntegerOracle() {
		Random random = new Random(0x5eed5eedL);
		for(int trial = 0; trial < 10_000; trial++) {
			BigInteger left = new BigInteger(105, random);
			BigInteger right = new BigInteger(105, random);
			var a = fromBigInteger(left);
			var b = fromBigInteger(right);
			assertEquals(Integer.signum(left.compareTo(right)), Integer.signum(a.compareTo(b)));
			BigInteger expected = left.add(right);
			if(expected.bitLength() <= 106) {
				var actual = a.add(b);
				assertEquals(expected, toBigInteger(actual));
				int q = -1000 + random.nextInt(896);
				assertEquals(Double.doubleToRawLongBits(Math.scalb(expected.doubleValue(), q)),
					actual.toDoubleBits(q));
			}
		}
	}

	private static ExactDyadicCosts fromBigInteger(BigInteger value) {
		if(value.signum() < 0 || value.bitLength() > 106)
			throw new IllegalArgumentException("test value out of range");
		return ExactDyadicCosts.ofWords(value.shiftRight(53).longValueExact(),
			value.and(BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE)).longValueExact());
	}

	private static BigInteger toBigInteger(ExactDyadicCosts value) {
		return BigInteger.valueOf(value.highWord()).shiftLeft(53)
			.add(BigInteger.valueOf(value.lowWord()));
	}
}
