/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;
import java.util.function.IntUnaryOperator;

import org.junit.Assert;
import org.junit.Test;

public class ExactSharedSourceLinearPreparationTest {
	@Test
	public void mixedBoundHeadersRemainPartialWhileWhollyUnboundHeadersFillAllValues()
		throws Exception {
		int[] headers = {2, 0, 2, 1, 0, 2, 1, 0};
		int[] selected = {-1, -1, 3, -1, -1, 3, -1, 0};
		assertRawMembership(4, 5, headers, selected);
		double[] values = membership(4, 5, headers, selected);
		for(int source = 0; source < 5; source++)
			Assert.assertEquals("wholly-unbound header", 0L,
				Double.doubleToRawLongBits(values[1 * 5 + source]));
		Assert.assertEquals("mixed header binds ordinal zero", 0L,
			Double.doubleToRawLongBits(values[0]));
		for(int source = 1; source < 5; source++)
			Assert.assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
				Double.doubleToRawLongBits(values[source]));
		Assert.assertEquals("mixed -1 must not wildcard-fill", Double.POSITIVE_INFINITY,
			values[2 * 5], 0d);
		Assert.assertEquals(0d, values[2 * 5 + 3], 0d);
		for(int source = 0; source < 5; source++)
			Assert.assertEquals("empty header follows wholly-unbound semantics", 0L,
				Double.doubleToRawLongBits(values[3 * 5 + source]));
	}

	@Test
	public void duplicatesAndLargeDomainsPreserveHeaderMajorRawTruth() throws Exception {
		int[] headers = {1, 1, 0, 3, 2, 3, 0, 2, 3, 1};
		int[] selected = {139, 139, 0, -1, 128, 127, -1, 128, 127, -1};
		assertRawMembership(4, 140, headers, selected);
	}

	@Test
	public void deterministicRandomRelationsMatchIndependentLegacyOracle() throws Exception {
		Random random = new Random(903311L);
		for(int example = 0; example < 500; example++) {
			int headerDomain = 1 + random.nextInt(12);
			int sourceDomain = 1 + random.nextInt(180);
			int rows = headerDomain + random.nextInt(80);
			int[] headers = new int[rows];
			int[] selected = new int[rows];
			for(int header = 0; header < headerDomain; header++) {
				headers[header] = header;
				selected[header] = random.nextInt(3) == 0 ? -1 : random.nextInt(sourceDomain);
			}
			for(int row = headerDomain; row < rows; row++) {
				headers[row] = random.nextInt(headerDomain);
				selected[row] = random.nextInt(3) == 0 ? -1 : random.nextInt(sourceDomain);
			}
			assertRawMembership(headerDomain, sourceDomain, headers, selected);
		}
	}

	private static void assertRawMembership(int headerDomain, int sourceDomain,
		int[] headers, int[] selected) throws Exception {
		double[] expected = legacy(headerDomain, sourceDomain, headers, selected);
		double[] actual = membership(headerDomain, sourceDomain, headers, selected);
		Assert.assertEquals(expected.length, actual.length);
		for(int cell = 0; cell < expected.length; cell++)
			Assert.assertEquals("cell=" + cell, Double.doubleToRawLongBits(expected[cell]),
				Double.doubleToRawLongBits(actual[cell]));
	}

	private static double[] legacy(int headerDomain, int sourceDomain,
		int[] headers, int[] selected) {
		if(headers.length != selected.length)
			throw new IllegalArgumentException("row length mismatch");
		double[] result = new double[Math.multiplyExact(headerDomain, sourceDomain)];
		Arrays.fill(result, Double.POSITIVE_INFINITY);
		for(int header = 0; header < headerDomain; header++) {
			boolean anyBound = false;
			for(int row = 0; row < headers.length; row++)
				if(headers[row] == header && selected[row] >= 0) {
					anyBound = true;
					result[header * sourceDomain + selected[row]] = 0d;
				}
			if(!anyBound)
				Arrays.fill(result, header * sourceDomain, (header + 1) * sourceDomain, 0d);
		}
		return result;
	}

	private static double[] membership(int headerDomain, int sourceDomain,
		int[] headers, int[] selected) throws Exception {
		double[] values = new double[Math.multiplyExact(headerDomain, sourceDomain)];
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		IntUnaryOperator selectedByRow = row -> selected[row];
		method().invoke(null, values, headerDomain, sourceDomain, headers, selectedByRow);
		return values;
	}

	private static Method method() throws Exception {
		Method method = ExactPhysicalSharedSourceEncoding.class.getDeclaredMethod(
			"fillMembershipValues", double[].class, int.class, int.class, int[].class,
			IntUnaryOperator.class);
		method.setAccessible(true);
		return method;
	}
}
