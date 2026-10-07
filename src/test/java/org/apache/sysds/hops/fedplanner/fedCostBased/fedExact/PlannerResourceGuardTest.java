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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlannerResourceGuardTest {
	@Test
	public void collectibleGarbageDoesNotBecomePlannerBudget() {
		PlannerResourceGuard.HeapSnapshot snapshot = new PlannerResourceGuard.HeapSnapshot(1_000, 1_000, 1);
		PlannerResourceGuard.checkAdditionalBytes(1_000, "global-exact", snapshot);
	}

	@Test
	public void rejectsOnlyPayloadLargerThanMaximumHeap() {
		PlannerResourceGuard.ResourceExhaustedException failure = assertThrows(
			PlannerResourceGuard.ResourceExhaustedException.class,
			() -> PlannerResourceGuard.checkAdditionalBytes(1_001, "local-dp",
				new PlannerResourceGuard.HeapSnapshot(1_000, 1_000, 64)));
		assertTrue(failure.getMessage(), failure.getMessage().contains("SYSTEM_RESOURCE_EXHAUSTED"));
		assertTrue(failure.getMessage(), failure.getMessage().contains("phase=local-dp"));
		assertTrue(failure.getMessage(), failure.getMessage().contains("requiredBytes=1001"));
		assertTrue(failure.getMessage(), failure.getMessage().contains("availableBytes=64"));
		assertTrue(failure.getMessage(), failure.getMessage().contains("maxMemory=1000"));
	}

	@Test
	public void convertsCellsToEightByteAllocationWithoutOverflow() {
		PlannerResourceGuard.checkAdditionalCells(125, "cell-table",
			new PlannerResourceGuard.HeapSnapshot(1_000, 1_000, 1));

		PlannerResourceGuard.ResourceExhaustedException failure = assertThrows(
			PlannerResourceGuard.ResourceExhaustedException.class,
			() -> PlannerResourceGuard.checkAdditionalCells(Long.MAX_VALUE, "cell-table",
					snapshotWithAvailableBytes(64)));
		assertTrue(failure.getMessage(), failure.getMessage().contains("phase=cell-table"));
		assertTrue(failure.getMessage(), failure.getMessage().contains("requiredBytes=OVERFLOW"));
	}

	@Test
	public void allocationHelpersCreatePrimitiveArrays() {
		assertTrue(PlannerResourceGuard.allocateDoubles(3, "double-table").length == 3);
		assertTrue(PlannerResourceGuard.allocateInts(4, "int-table").length == 4);
		assertTrue(PlannerResourceGuard.allocateLongs(5, "long-table").length == 5);
	}

	@Test
	public void diagnosticOutOfMemoryStillReturnsPreallocatedTypedFailure() {
		OutOfMemoryError allocationFailure = new OutOfMemoryError("array allocation");
		PlannerResourceGuard.ResourceExhaustedException failure =
			PlannerResourceGuard.allocationFailure("exact-backpointer",4096L,"int[]",
				allocationFailure,(phase,bytes,allocation,cause) -> {
					throw new OutOfMemoryError("diagnostic construction");
				});
		PlannerResourceGuard.ResourceExhaustedException repeatedFailure =
			PlannerResourceGuard.allocationFailure("exact-values",8192L,"double[]",
				allocationFailure,(phase,bytes,allocation,cause) -> {
					throw new OutOfMemoryError("diagnostic construction");
				});

		assertEquals("SYSTEM_RESOURCE_EXHAUSTED|diagnostic=unavailable",failure.getMessage());
		assertSame(failure,repeatedFailure);
		assertTrue(failure.actualAllocationFailure());
		assertTrue(repeatedFailure.actualAllocationFailure());
	}

	@Test
	public void detailedAllocationFailurePreservesOriginalCause() {
		OutOfMemoryError allocationFailure = new OutOfMemoryError("array allocation");
		PlannerResourceGuard.ResourceExhaustedException failure =
			PlannerResourceGuard.allocationFailure("exact-values",8192L,"double[]",allocationFailure);

		assertTrue(failure.getMessage(),failure.getMessage().contains("SYSTEM_RESOURCE_EXHAUSTED"));
		assertTrue(failure.getMessage(),failure.getMessage().contains("phase=exact-values"));
		assertTrue(failure.getMessage(),failure.getMessage().contains("requiredBytes=8192"));
		assertTrue(failure.getMessage(),failure.getMessage().contains("allocation=double[]"));
		assertSame(allocationFailure,failure.getCause());
		assertTrue(failure.actualAllocationFailure());
	}

	@Test
	public void resourcePreflightIsNotAnActualAllocationFailure() {
		PlannerResourceGuard.ResourceExhaustedException failure = assertThrows(
			PlannerResourceGuard.ResourceExhaustedException.class,
			() -> PlannerResourceGuard.checkAdditionalBytes(1_001, "regional-merge",
				new PlannerResourceGuard.HeapSnapshot(1_000, 1_000, 64)));

		assertTrue(!failure.actualAllocationFailure());
	}

	@Test
	public void validatesInjectedHeapSnapshot() {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
			() -> new PlannerResourceGuard.HeapSnapshot(100, 101, 0));
		assertTrue(failure.getMessage(), failure.getMessage().startsWith("SYSTEM_RESOURCE_SNAPSHOT_INVALID"));
	}

	private static PlannerResourceGuard.HeapSnapshot snapshotWithAvailableBytes(long available) {
		return new PlannerResourceGuard.HeapSnapshot(1_000, 1_000, available);
	}
}
