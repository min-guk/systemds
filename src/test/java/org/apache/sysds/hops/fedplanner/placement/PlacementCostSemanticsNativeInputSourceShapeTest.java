/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CampaignBG014AlsPartitionedComputeCostRedTest;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.junit.Assert;
import org.junit.Test;

public class PlacementCostSemanticsNativeInputSourceShapeTest {
	@Test
	public void alsLine126UsesTheSourceSnapshotInTheBoundedTransfer() throws Exception {
		new CampaignBG014AlsPartitionedComputeCostRedTest()
			.wanLightAlsUsesCapturedSmallInnerShapeForNativeLocalInputCost();
	}

	@Test
	public void sourceCompiledShapeRepairsOnlyTheCostEstimate() {
		NodeShapeFact conservative = matrix(-1, 10);
		NodeShapeFact source = matrix(50000, 10);

		NodeShapeFact resolved = PlacementCostSemantics.concreteCostShape(conservative, source);

		Assert.assertSame(source, resolved);
		Assert.assertEquals(-1, conservative.rows());
		Assert.assertEquals(10, conservative.cols());
		Assert.assertEquals(50000, source.rows());
		Assert.assertEquals(10, source.cols());
	}

	@Test
	public void conflictingKnownSourceAxisFailsClosed() {
		Assert.assertNull(PlacementCostSemantics.concreteCostShape(
			matrix(-1, 10), matrix(50000, 11)));
		Assert.assertNull(PlacementCostSemantics.concreteCostShape(
			matrix(40000, -1), matrix(50000, 10)));
		Assert.assertNull(PlacementCostSemantics.concreteCostShape(
			matrix(0, 10), matrix(50000, 10)));
	}

	@Test
	public void unknownSourceCannotReplaceAnUnknownCostAxis() {
		Assert.assertNull(PlacementCostSemantics.concreteCostShape(
			matrix(-1, 10), matrix(-1, 10)));
		Assert.assertNull(PlacementCostSemantics.concreteCostShape(
			matrix(-1, -1), matrix(-1, -1)));
		Assert.assertNull(PlacementCostSemantics.concreteCostShape(
			matrix(-1, 10), matrix(0, 10)));
	}

	@Test
	public void exactConservativeShapeRemainsUsableWhenSourceAgrees() {
		NodeShapeFact conservative = matrix(50000, 10);
		Assert.assertSame(conservative, PlacementCostSemantics.concreteCostShape(
			conservative, matrix(-1, 10)));
	}

	private static NodeShapeFact matrix(long rows, long cols) {
		return new NodeShapeFact(DataType.MATRIX, rows, cols);
	}
}
