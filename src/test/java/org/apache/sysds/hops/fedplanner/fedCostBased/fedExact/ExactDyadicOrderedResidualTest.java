/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class ExactDyadicOrderedResidualTest {
	@Test
	public void orderedTinyContributionsFitCorrectionDespiteNaiveHeadroomRejection() {
		List<BigInteger> maxima = new ArrayList<>();
		maxima.add(BigInteger.ONE.shiftLeft(96));
		for(int i = 0; i < 1500; i++)
			maxima.add(BigInteger.ONE);
		assertFalse(ExactDyadicCosts.certify(0, 97, maxima.size(), true).supported());
		var certificate = ExactDyadicCosts.certifyOrderedMaxima(0, maxima);
		assertTrue(certificate.supported());
		assertFalse(certificate.hasPhysicalAuthority());
		assertEquals("CERTIFIED_ORDERED_RESIDUAL_BOUND", certificate.reason());
		assertEquals(108, certificate.bitsWithAccumulationHeadroom());
		assertEquals(1500, certificate.maximumResidualUnits());
		assertExact(maxima);
		Collections.reverse(maxima);
		assertTrue(ExactDyadicCosts.certifyOrderedMaxima(0, maxima).supported());
		assertExact(maxima);
	}

	@Test
	public void compensationLossWithin106BitCarrierRemainsRejected() {
		List<BigInteger> values = new ArrayList<>();
		values.add(BigInteger.ONE.shiftLeft(105));
		for(int i = 0; i < 5; i++)
			values.add(BigInteger.ONE.shiftLeft(52));
		values.add(BigInteger.ONE);
		var certificate = ExactDyadicCosts.certifyOrderedMaxima(0, values);
		assertEquals(106, certificate.maximumSumBits());
		assertFalse(certificate.supported());
		assertEquals("INSUFFICIENT_ACCUMULATION_HEADROOM", certificate.reason());
		assertNotEquals(Double.doubleToRawLongBits(sum(values).doubleValue()), neumaierBits(values));
	}

	@Test
	public void finiteCarrierAndLatticeGatesCannotBeBypassedByTheStrongerProof() {
		assertFalse(ExactDyadicCosts.certifyOrderedMaxima(-1075, List.of(BigInteger.ONE)).supported());
		assertFalse(ExactDyadicCosts.certifyOrderedMaxima(0,
			List.of(BigInteger.ONE.shiftLeft(106))).supported());
		List<BigInteger> maxima = new ArrayList<>(Collections.nCopies(2048, BigInteger.ONE));
		maxima.set(0, BigInteger.ONE.shiftLeft(96));
		assertFalse(ExactDyadicCosts.certifyOrderedMaxima(927, maxima).supported());
		assertFalse(ExactDyadicCosts.certifyOrderedMaxima(0,
			List.of(BigInteger.valueOf(-1))).supported());
		assertTrue(ExactDyadicCosts.certifyOrderedMaxima(-1074,
			List.of(BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE)).supported());
		assertTrue(ExactDyadicCosts.certifyOrderedMaxima(0,
			List.of(BigInteger.ZERO, BigInteger.ZERO)).supported());
	}

	@Test
	public void everyAcceptedOrderedBoundCoversAllSmallAdmittedAssignments() {
		List<BigInteger> maxima = List.of(BigInteger.ONE.shiftLeft(101),
			BigInteger.ONE.shiftLeft(50), BigInteger.ONE.shiftLeft(48), BigInteger.valueOf(3));
		assertTrue(ExactDyadicCosts.certifyOrderedMaxima(0, maxima).supported());
		for(int choices = 0; choices < 81; choices++) {
			int remaining = choices;
			List<BigInteger> actual = new ArrayList<>();
			for(BigInteger maximum : maxima) {
				int choice = remaining % 3;
				remaining /= 3;
				actual.add(choice == 0 ? BigInteger.ZERO : choice == 1 ? maximum.shiftRight(1) : maximum);
			}
			assertExact(actual);
		}
	}

	@Test
	public void randomAcceptedSequencesAndPermutationsMatchIndependentIntegerSum() {
		Random random = new Random(0x20_106_53L);
		int accepted = 0;
		int ordered = 0;
		for(int trial = 0; trial < 2000; trial++) {
			List<BigInteger> maxima = new ArrayList<>();
			maxima.add(BigInteger.ONE.shiftLeft(90 + random.nextInt(16)));
			for(int i = 0, n = 2 + random.nextInt(80); i < n; i++)
				maxima.add(BigInteger.valueOf(random.nextInt(1 << 12)).shiftLeft(random.nextInt(42)));
			Collections.shuffle(maxima, random);
			var certificate = ExactDyadicCosts.certifyOrderedMaxima(0, maxima);
			if(!certificate.supported())
				continue;
			accepted++;
			if(certificate.reason().equals("CERTIFIED_ORDERED_RESIDUAL_BOUND"))
				ordered++;
			assertExact(maxima);
			List<BigInteger> actual = new ArrayList<>();
			for(BigInteger maximum : maxima)
				actual.add(random.nextBoolean() ? maximum : BigInteger.ZERO);
			assertExact(actual);
		}
		assertTrue("exercise accepted bounds", accepted > 500);
		assertTrue("exercise stronger proof", ordered > 100);
	}

	private static void assertExact(List<BigInteger> values) {
		assertEquals(Double.doubleToRawLongBits(sum(values).doubleValue()), neumaierBits(values));
	}

	private static BigInteger sum(List<BigInteger> values) {
		return values.stream().reduce(BigInteger.ZERO, BigInteger::add);
	}

	private static long neumaierBits(List<BigInteger> values) {
		double total = 0;
		double correction = 0;
		for(BigInteger value : values) {
			double next = value.doubleValue();
			assertEquals("fixture values must be exactly representable", value,
				new java.math.BigDecimal(next).toBigIntegerExact());
			double sum = total + next;
			correction += Math.abs(total) >= Math.abs(next)
				? (total - sum) + next : (next - sum) + total;
			total = sum;
		}
		return Double.doubleToRawLongBits(total + correction);
	}
}
