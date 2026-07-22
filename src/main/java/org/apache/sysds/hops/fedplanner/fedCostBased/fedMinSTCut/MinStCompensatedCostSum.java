/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

/** Package-private compensated accumulator for canonical non-negative MinST costs. */
final class MinStCompensatedCostSum {
	private double sum;
	private double correction;

	void addBits(long bits, String invalidCostReason, String invalidTotalReason) {
		double value = Double.longBitsToDouble(bits);
		validate(bits, value, invalidCostReason);
		double next = sum + value;
		validate(Double.doubleToRawLongBits(next), next, invalidTotalReason);
		if(Math.abs(sum) >= Math.abs(value))
			correction += (sum - next) + value;
		else
			correction += (value - next) + sum;
		if(!Double.isFinite(correction))
			throw new IllegalArgumentException(invalidTotalReason + "|value=" + correction);
		sum = next;
	}

	long totalBits(String invalidTotalReason) {
		double total = sum + correction;
		long bits = Double.doubleToRawLongBits(total);
		validate(bits, total, invalidTotalReason);
		return bits;
	}

	private static void validate(long bits, double value, String reason) {
		if(!Double.isFinite(value) || value < 0.0
			|| bits == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException(reason + "|value=" + value);
	}
}
