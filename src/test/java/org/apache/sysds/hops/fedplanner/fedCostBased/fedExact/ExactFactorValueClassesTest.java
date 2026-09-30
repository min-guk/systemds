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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

public class ExactFactorValueClassesTest {
	@Test
	public void denseProfilesCanonicalizeByFirstOriginalValue() {
		// Shape [outer=2, axis=4, inner=1]. Values 0/2 and 1/3 have equal, non-adjacent profiles.
		double[] high = {1, 5, 1, 5, 2, 6, 2, 6};
		Assert.assertArrayEquals(new int[] {0, 1, 0, 1},
			ExactFactorValueClasses.denseAxisClasses(high, null, 4, 1));
	}

	@Test
	public void lowBitsArePartOfTheOrderedProfile() {
		double[] high = {7, 7, 7};
		double[] low = {0, Double.MIN_VALUE, 0};
		Assert.assertArrayEquals(new int[] {0, 1, 0},
			ExactFactorValueClasses.denseAxisClasses(high, low, 3, 1));
	}

	@Test
	public void absentLowIsExactlyPositiveZeroAtEveryCell() {
		double[] high = {2, 2, 4, 4};
		Assert.assertArrayEquals(
			ExactFactorValueClasses.denseAxisClasses(high, new double[high.length], 2, 1),
			ExactFactorValueClasses.denseAxisClasses(high, null, 2, 1));
	}

	@Test
	public void rawSignedZeroInfinityAndNanPayloadsRemainExact() {
		double nanA = Double.longBitsToDouble(0x7ff8000000000001L);
		double nanB = Double.longBitsToDouble(0x7ff8000000000002L);
		double[] high = {+0d, -0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, nanA, nanB};
		Assert.assertArrayEquals(new int[] {0, 1, 2, 2, 3, 4},
			ExactFactorValueClasses.denseAxisClasses(high, null, 6, 1));
	}

	@Test
	public void hashCollisionStillRequiresFullRawBitEquality() {
		double collision = Double.longBitsToDouble(0x0000000100000001L);
		Assert.assertEquals(Long.hashCode(0L), Long.hashCode(Double.doubleToRawLongBits(collision)));
		Assert.assertArrayEquals(new int[] {0, 1, 0},
			ExactFactorValueClasses.denseAxisClasses(new double[] {0d, collision, 0d}, null, 3, 1));
	}

	@Test
	public void refinementUsesExactPairsAndCanonicalFirstSeenIds() {
		Assert.assertArrayEquals(new int[] {0, 1, 2, 3}, ExactFactorValueClasses.refine(
			new int[] {0, 0, 1, 1}, new int[] {0, 1, 0, 1}));
		Assert.assertArrayEquals(new int[] {0, 1, 0, 2, 1}, ExactFactorValueClasses.refine(
			new int[] {8, 8, 8, 9, 8}, new int[] {4, 5, 4, 4, 5}));
	}

	@Test
	public void representativesAreLeastOriginalValues() {
		Assert.assertArrayEquals(new int[] {0, 1, 3},
			ExactFactorValueClasses.representatives(new int[] {0, 1, 0, 2, 1, 2}));
	}

	@Test
	public void uniqueFiveMillionUnaryProfilesFitBoundedHeap() throws Exception {
		String javaExecutable = System.getProperty("java.home") + "/bin/java";
		Process process = new ProcessBuilder(javaExecutable, "-Xmx192m", "-cp",
			System.getProperty("java.class.path"), MemoryProbe.class.getName())
			.redirectErrorStream(true).start();
		if(!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			Assert.fail("bounded-memory probe timed out");
		}
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		Assert.assertEquals(output, 0, process.exitValue());
		Assert.assertTrue(output, output.contains("MEMORY_PROBE_OK"));
	}

	public static final class MemoryProbe {
		public static void main(String[] args) {
			int domain = 5_000_000;
			double[] high = new double[domain];
			for(int value = 0; value < domain; value++)
				high[value] = value;
			int[] classes = ExactFactorValueClasses.denseAxisClasses(high, null, domain, 1);
			if(classes.length != domain || classes[0] != 0 || classes[domain / 2] != domain / 2
				|| classes[domain - 1] != domain - 1)
				throw new AssertionError("unique unary profiles lost their identity partition");
			high = null;
			int[] next = new int[domain];
			for(int value = 0; value < domain; value++)
				next[value] = domain - value - 1;
			int[] refined = ExactFactorValueClasses.refine(classes, next);
			if(refined.length != domain || refined[0] != 0 || refined[domain / 2] != domain / 2
				|| refined[domain - 1] != domain - 1)
				throw new AssertionError("unique refinement pairs lost their identity partition");
			System.out.println("MEMORY_PROBE_OK");
		}
	}

	@Test
	public void randomizedDenseClassesMatchIndependentRawProfileOracle() {
		Random random = new Random(0x5eedc0deL);
		long[] values = {0L, Long.MIN_VALUE, 1L, 0x0000000100000001L,
			Double.doubleToRawLongBits(1d), Double.doubleToRawLongBits(-3d),
			Double.doubleToRawLongBits(Double.POSITIVE_INFINITY), 0x7ff8000000000001L};
		for(int trial = 0; trial < 500; trial++) {
			int domain = 1 + random.nextInt(6);
			int stride = 1 + random.nextInt(4);
			int outer = 1 + random.nextInt(4);
			double[] high = new double[outer * domain * stride];
			double[] low = random.nextBoolean() ? null : new double[high.length];
			for(int i = 0; i < high.length; i++) {
				high[i] = Double.longBitsToDouble(values[random.nextInt(values.length)]);
				if(low != null)
					low[i] = Double.longBitsToDouble(values[random.nextInt(values.length)]);
			}
			Assert.assertArrayEquals("trial=" + trial, bruteForce(high, low, domain, stride),
				ExactFactorValueClasses.denseAxisClasses(high, low, domain, stride));
		}
	}

	private static int[] bruteForce(double[] high, double[] low, int domain, int stride) {
		Map<String,Integer> classes = new LinkedHashMap<>();
		int[] result = new int[domain];
		for(int value = 0; value < domain; value++) {
			StringBuilder profile = new StringBuilder();
			for(int base = 0; base < high.length; base += domain * stride)
				for(int inner = 0; inner < stride; inner++) {
					int cell = base + value * stride + inner;
					profile.append(Long.toUnsignedString(Double.doubleToRawLongBits(high[cell]))).append('/')
						.append(Long.toUnsignedString(low == null ? 0L : Double.doubleToRawLongBits(low[cell])))
						.append(';');
				}
			result[value] = classes.computeIfAbsent(profile.toString(), ignored -> classes.size());
		}
		return result;
	}
}
