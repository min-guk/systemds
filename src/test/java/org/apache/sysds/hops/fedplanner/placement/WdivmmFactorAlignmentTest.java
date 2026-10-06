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

package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.junit.Assert;
import org.junit.Test;

public class WdivmmFactorAlignmentTest {
	@Test public void rowURequiresWorkerAddressesAndRowRanges() {
		InputLayout w = rows(FType.ROW, "a", "b", 50, 100, 10);
		Assert.assertTrue(reuse(w, rows(FType.ROW, "a", "b", 50, 100, 2), 1));
		Assert.assertFalse(reuse(w, rows(FType.ROW, "a", "c", 50, 100, 2), 1));
		Assert.assertFalse(reuse(w, rows(FType.ROW, "a", "b", 60, 100, 2), 1));
		Assert.assertFalse(reuse(w, rows(FType.PART, "a", "b", 50, 100, 2), 1));
	}
	@Test public void fullW1ReusesFullUButStillBroadcastsV() {
		InputLayout w = layout(FType.FULL, range("a", 0, 0, 100, 10));
		InputLayout u = layout(FType.FULL, range("a", 0, 0, 100, 2));
		Assert.assertTrue(reuse(w, u, 1));
		Assert.assertFalse(reuse(w, u, 2));
	}
	@Test public void colWeightsReuseColOrTransposedRowAlignedV() {
		InputLayout w = layout(FType.COL, range("a", 0, 0, 100, 5), range("b", 0, 5, 100, 10));
		Assert.assertTrue(reuse(w, layout(FType.COL, range("a", 0, 0, 2, 5), range("b", 0, 5, 2, 10)), 2));
		Assert.assertTrue(reuse(w, rows(FType.ROW, "a", "b", 5, 10, 2), 2));
		Assert.assertFalse(reuse(w, rows(FType.ROW, "a", "c", 5, 10, 2), 2));
		Assert.assertFalse(reuse(w, rows(FType.ROW, "a", "b", 5, 10, 2), 1));
	}
	@Test public void unknownOrLocalLayoutsDoNotAuthorizeFreeMovement() {
		InputLayout w = rows(FType.ROW, "a", "b", 50, 100, 10);
		Assert.assertFalse(reuse(w, new InputLayout(FType.ROW, List.of(), false), 1));
		Assert.assertFalse(reuse(new InputLayout(FType.ROW, List.of(), false), w, 1));
		Assert.assertFalse(reuse(w, new InputLayout(null, List.of(), false), 1));
	}
	@Test public void generatedTransposeCannotReuseTheUntransposedSourceMap() {
		InputLayout w = layout(FType.COL, range("a", 0, 0, 100, 5), range("b", 0, 5, 100, 10));
		Assert.assertFalse(PlacementCostSemantics.reusesWdivmmFactor(w,
			rows(FType.ROW, "a", "b", 5, 10, 2), 2, true));
	}
	@Test public void everyMatchingRangeMustHaveTheSameAddress() {
		InputLayout w = layout(FType.FULL, range("a", 0, 0, 100, 10));
		InputLayout replicas = layout(FType.BROADCAST,
			range("a", 0, 0, 100, 2), range("b", 0, 0, 100, 2));
		Assert.assertFalse(reuse(w, replicas, 1));
	}
	@Test public void colAndColTransposeCannotBeMixedBetweenWorkers() {
		InputLayout w = layout(FType.COL, range("a", 0, 0, 100, 5), range("b", 0, 5, 100, 10));
		InputLayout mixed = layout(FType.PART, range("a", 0, 0, 2, 5), range("b", 5, 0, 10, 2));
		Assert.assertFalse(reuse(w, mixed, 2));
	}
	private static boolean reuse(InputLayout w, InputLayout factor, int position) {
		return PlacementCostSemantics.reusesWdivmmFactor(w, factor, position, false);
	}
	private static InputLayout rows(FType type, String a, String b, int split, int rows, int cols) {
		return layout(type, range(a, 0, 0, split, cols), range(b, split, 0, rows, cols));
	}
	private static InputLayout layout(FType type, AnchorPartition... ranges) {
		return new InputLayout(type, List.of(ranges), true);
	}
	private static AnchorPartition range(String worker, long r0, long c0, long r1, long c1) {
		return new AnchorPartition(worker, List.of(r0, c0), List.of(r1, c1));
	}
}
