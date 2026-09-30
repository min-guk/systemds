/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Independent boundary tests for the ordered residual certificate. */
public class ExactDyadicOrderedResidualBoundaryTest {
	private static final BigInteger CORRECTION_LIMIT = BigInteger.ONE.shiftLeft(53);
	private static final String INVALID = "ORDERED_RESIDUAL_TEST_INVALID";

	@Test
	public void residualLimitIsInclusiveAndTheFirstExcessIsRejected() {
		BigInteger large = BigInteger.ONE.shiftLeft(105);
		BigInteger halfUlp = BigInteger.ONE.shiftLeft(52);
		var boundary = ExactDyadicCosts.certifyOrderedMaxima(0,
			List.of(large, halfUlp, halfUlp));
		assertTrue(boundary.supported());
		assertEquals("CERTIFIED_ORDERED_RESIDUAL_BOUND", boundary.reason());
		assertEquals(CORRECTION_LIMIT.longValueExact(), boundary.maximumResidualUnits());

		var exceeded = ExactDyadicCosts.certifyOrderedMaxima(0,
			List.of(large, halfUlp, halfUlp, halfUlp));
		assertFalse(exceeded.supported());
		assertEquals("INSUFFICIENT_ACCUMULATION_HEADROOM", exceeded.reason());
		assertTrue(exceeded.maximumResidualUnits() > CORRECTION_LIMIT.longValueExact());
	}

	@Test
	public void everyAcceptedPrefixHasExactMainPlusSignedCorrection() {
		List<List<BigInteger>> sequences = List.of(
			List.of(BigInteger.ONE.shiftLeft(105), BigInteger.ONE.shiftLeft(52),
				BigInteger.ONE.shiftLeft(52)),
			List.of(BigInteger.ONE.shiftLeft(53), BigInteger.ONE),
			List.of(BigInteger.ONE.shiftLeft(53), BigInteger.valueOf(3)));
		for(List<BigInteger> sequence : sequences) {
			State state = new State();
			BigInteger exactPrefix = BigInteger.ZERO;
			List<BigInteger> prefix = new ArrayList<>();
			for(BigInteger value : sequence) {
				prefix.add(value);
				assertTrue("prefix must be admitted: " + prefix,
					ExactDyadicCosts.certifyOrderedMaxima(0, prefix).supported());
				state.add(exactDouble(value, 0));
				exactPrefix = exactPrefix.add(value);
				assertEquals(exactPrefix,
					binary64Units(state.main, 0).add(binary64Units(state.correction, 0)));
				assertEquals(exactOracleBits(exactPrefix, 0), accumulatorBits(prefix, 0));
			}
		}
		State positive = state(BigInteger.ONE.shiftLeft(53), BigInteger.ONE);
		State negative = state(BigInteger.ONE.shiftLeft(53), BigInteger.valueOf(3));
		assertTrue("tie-down addition produces positive correction", positive.correction > 0);
		assertTrue("round-up addition produces negative correction", negative.correction < 0);
	}

	@Test
	public void subnormalNormalAndFiniteOverflowBoundariesUseExactDecimalOracle() {
		assertAcceptedAndExact(-1074, List.of(BigInteger.ONE, BigInteger.ONE));
		assertAcceptedAndExact(-1074,
			List.of(BigInteger.ONE.shiftLeft(52).subtract(BigInteger.ONE), BigInteger.ONE));
		assertAcceptedAndExact(971, List.of(BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE)));

		var finiteOverflow = ExactDyadicCosts.certifyOrderedMaxima(972,
			List.of(BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE)));
		assertFalse(finiteOverflow.supported());
		assertEquals("BINARY64_FINITE_MAXIMUM", finiteOverflow.reason());
		var wideOverflow = ExactDyadicCosts.certifyOrderedMaxima(970,
			List.of(BigInteger.ONE.shiftLeft(53)));
		assertFalse(wideOverflow.supported());
		assertEquals("BINARY64_OVERFLOW_HEADROOM", wideOverflow.reason());
	}

	@Test
	public void scalarHelperRemainsConservativeAndNeitherNumericHelperHasAuthority() {
		List<BigInteger> ordered = new ArrayList<>();
		ordered.add(BigInteger.ONE.shiftLeft(96));
		for(int i = 0; i < 1500; i++)
			ordered.add(BigInteger.ONE);
		var scalar = ExactDyadicCosts.certify(0, 97, ordered.size(), true);
		var stronger = ExactDyadicCosts.certifyOrderedMaxima(0, ordered);
		assertFalse(scalar.supported());
		assertEquals(ordered.size(), scalar.accumulationContributionCount());
		assertFalse(scalar.hasPhysicalAuthority());
		assertTrue(stronger.supported());
		assertFalse(stronger.hasPhysicalAuthority());

		var zeros = ExactDyadicCosts.certifyOrderedMaxima(0,
			List.of(BigInteger.ZERO, BigInteger.ONE.shiftLeft(10), BigInteger.ZERO));
		assertEquals(3, zeros.canonicalContributionCount());
		assertEquals(1, zeros.accumulationContributionCount());
		assertFalse(zeros.hasPhysicalAuthority());
	}

	private static void assertAcceptedAndExact(int q, List<BigInteger> values) {
		var certificate = ExactDyadicCosts.certifyOrderedMaxima(q, values);
		assertTrue(certificate.reason(), certificate.supported());
		assertEquals(exactOracleBits(sum(values), q), accumulatorBits(values, q));
	}

	private static State state(BigInteger... values) {
		State state = new State();
		for(BigInteger value : values)
			state.add(exactDouble(value, 0));
		return state;
	}

	private static long accumulatorBits(List<BigInteger> values, int q) {
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		for(BigInteger value : values)
			sum.addBits(Double.doubleToRawLongBits(exactDouble(value, q)), INVALID, INVALID);
		return sum.totalBits(INVALID);
	}

	private static double exactDouble(BigInteger units, int q) {
		double value = exactDecimal(units, q).doubleValue();
		assertEquals("test contribution must itself be binary64-exact", units,
			binary64Units(value, q));
		return value;
	}

	private static long exactOracleBits(BigInteger units, int q) {
		return Double.doubleToRawLongBits(exactDecimal(units, q).doubleValue());
	}

	private static BigDecimal exactDecimal(BigInteger units, int q) {
		if(q >= 0)
			return new BigDecimal(units.shiftLeft(q));
		return new BigDecimal(units).divide(new BigDecimal(BigInteger.ONE.shiftLeft(-q)));
	}

	private static BigInteger binary64Units(double value, int q) {
		long raw = Double.doubleToRawLongBits(value);
		boolean negative = (raw & Long.MIN_VALUE) != 0;
		long magnitude = raw & Long.MAX_VALUE;
		if(magnitude == 0)
			return BigInteger.ZERO;
		int exponentBits = (int)((magnitude >>> 52) & 0x7ffL);
		long significand = (magnitude & ((1L << 52) - 1))
			| (exponentBits == 0 ? 0 : 1L << 52);
		int exponent = exponentBits == 0 ? -1074 : exponentBits - 1023 - 52;
		BigInteger units = BigInteger.valueOf(significand);
		int shift = exponent - q;
		if(shift >= 0)
			units = units.shiftLeft(shift);
		else {
			BigInteger divisor = BigInteger.ONE.shiftLeft(-shift);
			assertEquals("binary64 value must lie on the asserted lattice",
				BigInteger.ZERO, units.remainder(divisor));
			units = units.divide(divisor);
		}
		return negative ? units.negate() : units;
	}

	private static BigInteger sum(List<BigInteger> values) {
		return values.stream().reduce(BigInteger.ZERO, BigInteger::add);
	}

	private static final class State {
		private double main;
		private double correction;

		private void add(double value) {
			double next = main + value;
			correction += Math.abs(main) >= Math.abs(value)
				? (main - next) + value : (value - next) + main;
			main = next;
		}
	}
}
