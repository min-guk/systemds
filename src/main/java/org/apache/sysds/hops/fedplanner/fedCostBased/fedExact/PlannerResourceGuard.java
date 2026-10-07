/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Objects;

/** Rejects impossible planner allocations and reports allocation failures without imposing a planner budget. */
public final class PlannerResourceGuard {
	private static final long BYTES_PER_CELL = Double.BYTES;
	private static final ResourceExhaustedException DIAGNOSTIC_ALLOCATION_FALLBACK =
		new ResourceExhaustedException("SYSTEM_RESOURCE_EXHAUSTED|diagnostic=unavailable");
	private static final ExhaustionFormatter DEFAULT_EXHAUSTION_FORMATTER =
		(phase,bytes,allocation,cause) -> exhausted(phase,Long.toString(bytes),
			currentSnapshot(),allocation,cause);

	@FunctionalInterface
	interface ExhaustionFormatter {
		ResourceExhaustedException format(String phase, long bytes, String allocation,
			OutOfMemoryError cause);
	}

	private PlannerResourceGuard() {
		// utility class
	}

	public static void checkAdditionalCells(long cells, String phase) {
		checkAdditionalCells(cells, phase, currentSnapshot());
	}

	static void checkAdditionalCells(long cells, String phase, HeapSnapshot snapshot) {
		if(cells < 0)
			throw new IllegalArgumentException("SYSTEM_RESOURCE_REQUEST_INVALID|phase=" + checkedPhase(phase)
				+ "|additionalCells=" + cells);
		final long bytes;
		try {
			bytes = Math.multiplyExact(cells, BYTES_PER_CELL);
		}
		catch(ArithmeticException ex) {
			throw exhausted(checkedPhase(phase), "OVERFLOW", Objects.requireNonNull(snapshot, "snapshot"), null,
				ex);
		}
		checkAdditionalBytes(bytes, phase, snapshot);
	}

	public static void checkAdditionalBytes(long bytes, String phase) {
		// Total/free heap are diagnostic fields, not allocation-policy inputs.
		// Avoid both native queries for the ordinary accepted request.
		if(bytes >= 0 && bytes <= Runtime.getRuntime().maxMemory()) {
			checkedPhase(phase);
			return;
		}
		checkAdditionalBytes(bytes, phase, currentSnapshot());
	}

	static void checkAdditionalBytes(long bytes, String phase, HeapSnapshot snapshot) {
		String checkedPhase = checkedPhase(phase);
		Objects.requireNonNull(snapshot, "snapshot");
		if(bytes < 0)
			throw new IllegalArgumentException("SYSTEM_RESOURCE_REQUEST_INVALID|phase=" + checkedPhase
				+ "|additionalBytes=" + bytes);
		if(bytes > snapshot.maxMemory())
			throw exhausted(checkedPhase, Long.toString(bytes), snapshot, null, null);
	}

	public static double[] allocateDoubles(int length, String phase) {
		long bytes = -1L;
		try {
			bytes = checkedArrayBytes(length, Double.BYTES, phase);
			checkAdditionalBytes(bytes, phase);
			return new double[length];
		}
		catch(OutOfMemoryError failure) {
			throw allocationFailure(phase,bytes,"double[]",failure);
		}
	}

	public static int[] allocateInts(int length, String phase) {
		long bytes = -1L;
		try {
			bytes = checkedArrayBytes(length, Integer.BYTES, phase);
			checkAdditionalBytes(bytes, phase);
			return new int[length];
		}
		catch(OutOfMemoryError failure) {
			throw allocationFailure(phase,bytes,"int[]",failure);
		}
	}

	public static long[] allocateLongs(int length, String phase) {
		long bytes = -1L;
		try {
			bytes = checkedArrayBytes(length, Long.BYTES, phase);
			checkAdditionalBytes(bytes, phase);
			return new long[length];
		}
		catch(OutOfMemoryError failure) {
			throw allocationFailure(phase,bytes,"long[]",failure);
		}
	}

	static ResourceExhaustedException allocationFailure(String phase, long bytes,
		String allocation, OutOfMemoryError cause) {
		return allocationFailure(phase,bytes,allocation,cause,DEFAULT_EXHAUSTION_FORMATTER);
	}

	static ResourceExhaustedException allocationFailure(String phase, long bytes,
		String allocation, OutOfMemoryError cause, ExhaustionFormatter formatter) {
		try {
			return formatter.format(phase,bytes,allocation,cause);
		}
		catch(OutOfMemoryError diagnosticFailure) {
			return DIAGNOSTIC_ALLOCATION_FALLBACK;
		}
	}

	static HeapSnapshot currentSnapshot() {
		Runtime runtime = Runtime.getRuntime();
		return new HeapSnapshot(runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory());
	}

	private static String checkedPhase(String phase) {
		if(phase == null || phase.isBlank())
			throw new IllegalArgumentException("SYSTEM_RESOURCE_PHASE_INVALID");
		return phase;
	}

	private static long checkedArrayBytes(int length, int bytesPerElement, String phase) {
		if(length < 0)
			throw new IllegalArgumentException("SYSTEM_RESOURCE_REQUEST_INVALID|phase=" + checkedPhase(phase)
				+ "|arrayLength=" + length);
		return Math.multiplyExact((long)length, bytesPerElement);
	}

	private static ResourceExhaustedException exhausted(String phase, String required, HeapSnapshot snapshot,
		String allocation, Throwable cause) {
		String message = "SYSTEM_RESOURCE_EXHAUSTED|phase=" + phase + "|requiredBytes=" + required
			+ "|availableBytes=" + snapshot.availableBytes() + "|maxMemory=" + snapshot.maxMemory()
			+ (allocation == null ? "" : "|allocation=" + allocation);
		return cause == null ? new ResourceExhaustedException(message) : new ResourceExhaustedException(message, cause);
	}

	static record HeapSnapshot(long maxMemory, long totalMemory, long freeMemory) {
		HeapSnapshot {
			if(maxMemory < 0 || totalMemory < 0 || freeMemory < 0 || freeMemory > totalMemory
				|| totalMemory > maxMemory)
				throw new IllegalArgumentException("SYSTEM_RESOURCE_SNAPSHOT_INVALID|maxMemory=" + maxMemory
					+ "|totalMemory=" + totalMemory + "|freeMemory=" + freeMemory);
		}

		long availableBytes() {
			return maxMemory - (totalMemory - freeMemory);
		}
	}

	public static final class ResourceExhaustedException extends IllegalArgumentException {
		private static final long serialVersionUID = 4934660911717295727L;

		private ResourceExhaustedException(String message) {
			super(message);
		}

		private ResourceExhaustedException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
