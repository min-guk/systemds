/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Arrays;
import java.util.Objects;

/** Exact value partitions used to preserve dense factor responses during domain reduction. */
final class ExactFactorValueClasses {
	private ExactFactorValueClasses() {
		// utility class
	}

	static int[] denseAxisClasses(double[] high, double[] low, int domain, int stride) {
		Objects.requireNonNull(high, "high");
		long blockSize = (long)domain * stride;
		if(domain <= 0 || stride <= 0 || blockSize > Integer.MAX_VALUE || high.length % blockSize != 0)
			throw new IllegalArgumentException("Dense factor shape does not match axis domain and stride");
		if(low != null && low.length != high.length)
			throw new IllegalArgumentException("Dense factor high/low lengths differ");

		int[] classes = new int[domain];
		int tableCapacity = tableCapacity(domain);
		if(tableCapacity < 0 || !hasTableStorage(tableCapacity))
			return identity(classes);
		int[] representativeBySlot = new int[tableCapacity];
		int mask = tableCapacity - 1;
		int classCount = 0;
		for(int value = 0; value < domain; value++) {
			int hash = profileHash(high, low, domain, stride, value);
			int slot = spread(hash) & mask;
			int matching = -1;
			while(representativeBySlot[slot] != 0) {
				int representative = representativeBySlot[slot] - 1;
				if(profileHash(high, low, domain, stride, representative) == hash
					&& sameProfile(high, low, domain, stride, value, representative)) {
					matching = classes[representative];
					break;
				}
				slot = (slot + 1) & mask;
			}
			if(matching < 0) {
				matching = classCount++;
				representativeBySlot[slot] = value + 1;
			}
			classes[value] = matching;
		}
		return classes;
	}

	static int[] refine(int[] currentClasses, int[] nextClasses) {
		Objects.requireNonNull(currentClasses, "currentClasses");
		Objects.requireNonNull(nextClasses, "nextClasses");
		if(currentClasses.length != nextClasses.length)
			throw new IllegalArgumentException("Partitions have different domains");
		int[] refined = new int[currentClasses.length];
		int tableCapacity = tableCapacity(refined.length);
		if(tableCapacity < 0 || !hasTableStorage(tableCapacity))
			return identity(refined);
		int[] representativeBySlot = new int[tableCapacity];
		int mask = tableCapacity - 1;
		int classCount = 0;
		for(int value = 0; value < refined.length; value++) {
			long pair = pair(currentClasses, nextClasses, value);
			int slot = pairHash(currentClasses[value], nextClasses[value]) & mask;
			int matching = -1;
			while(representativeBySlot[slot] != 0) {
				int representative = representativeBySlot[slot] - 1;
				if(pair(currentClasses, nextClasses, representative) == pair) {
					matching = refined[representative];
					break;
				}
				slot = (slot + 1) & mask;
			}
			if(matching < 0) {
				matching = classCount++;
				representativeBySlot[slot] = value + 1;
			}
			refined[value] = matching;
		}
		return refined;
	}

	static int[] representatives(int[] classes) {
		Objects.requireNonNull(classes, "classes");
		int classCount = 0;
		for(int valueClass : classes) {
			if(valueClass < 0)
				throw new IllegalArgumentException("Class identifiers must be nonnegative");
			classCount = Math.max(classCount, valueClass + 1);
		}
		int[] representatives = new int[classCount];
		Arrays.fill(representatives, -1);
		for(int value = 0; value < classes.length; value++)
			if(representatives[classes[value]] < 0)
				representatives[classes[value]] = value;
		return representatives;
	}

	private static long pair(int[] currentClasses, int[] nextClasses, int value) {
		return ((long)currentClasses[value] << 32) | (nextClasses[value] & 0xffffffffL);
	}

	private static int[] identity(int[] classes) {
		for(int value = 0; value < classes.length; value++)
			classes[value] = value;
		return classes;
	}

	private static int tableCapacity(int size) {
		long required = Math.max(1L, (3L * size + 1L) / 2L);
		if(required > 1L << 30)
			return -1;
		int capacity = 1;
		while(capacity < required)
			capacity <<= 1;
		return capacity;
	}

	/** Keep the memo below half of current heap headroom; identity is an exact conservative partition. */
	private static boolean hasTableStorage(int capacity) {
		Runtime runtime = Runtime.getRuntime();
		long used = runtime.totalMemory() - runtime.freeMemory();
		long headroom = Math.max(0L, runtime.maxMemory() - used);
		long tableBytes = 16L + (long)Integer.BYTES * capacity;
		return tableBytes <= headroom / 2L;
	}

	private static int pairHash(int currentClass, int nextClass) {
		return spread(31 * (31 + currentClass) + nextClass);
	}

	private static int spread(int hash) {
		return hash ^ hash >>> 16;
	}

	private static int profileHash(double[] high, double[] low, int domain, int stride, int value) {
		int hash = 1;
		int blockSize = (int)((long)domain * stride);
		for(int base = 0; base < high.length; base += blockSize)
			for(int inner = 0; inner < stride; inner++) {
				int cell = base + value * stride + inner;
				hash = 31 * hash + Long.hashCode(Double.doubleToRawLongBits(high[cell]));
				hash = 31 * hash + Long.hashCode(low == null ? 0L : Double.doubleToRawLongBits(low[cell]));
			}
		return hash;
	}

	private static boolean sameProfile(double[] high, double[] low, int domain, int stride,
		int leftValue, int rightValue) {
		int blockSize = (int)((long)domain * stride);
		for(int base = 0; base < high.length; base += blockSize)
			for(int inner = 0; inner < stride; inner++) {
				int left = base + leftValue * stride + inner;
				int right = base + rightValue * stride + inner;
				if(Double.doubleToRawLongBits(high[left]) != Double.doubleToRawLongBits(high[right])
					|| (low != null && Double.doubleToRawLongBits(low[left]) != Double.doubleToRawLongBits(low[right])))
					return false;
			}
		return true;
	}
}
