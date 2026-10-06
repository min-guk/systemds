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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.WorkerResponseSummary;
import org.junit.Assert;
import org.junit.Test;

public class SharedNicNetworkCostTest {
	@Test public void sharedCoordinatorLimitsBalancedGetAtEveryWorkerCount() throws Exception { probe("get"); }
	@Test public void workerLegAndAggregateCoordinatorCapsAreDistinct() throws Exception { probe("put"); }
	@Test public void skewReplicasAndPartialsPreserveActualWireVolumes() throws Exception { probe("skew"); }
	@Test public void obsoleteSettingsHaveNoCompatibilityPath() throws Exception { probe("obsolete"); }
	@Test public void invalidCoordinatorThroughputFailsExplicitly() throws Exception { probe("invalid"); }
	@Test public void mixedBalancedUploadsShareTheSameStageBottleneck() throws Exception { probe("batch"); }
	@Test public void stagePrimitiveHasNoArtificialWireByteScaling() throws Exception { probe("primitive"); }

	private static void probe(String mode) throws Exception {
		List<String> command = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java"));
		if(mode.equals("obsolete")) command.addAll(List.of("-DSYSDS_FED_COST_NET_LATENCY=.9",
			"-DSYSDS_FED_COST_NET_BW=.001", "-DSYSDS_FED_COST_NET_SERDES_BW=.001",
			"-DSYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS=999"));
		else command.addAll(List.of("-DSYSDS_FED_COST_NET_LATENCY_C2W=.003",
			"-DSYSDS_FED_COST_NET_LATENCY_W2C=.007", "-DSYSDS_FED_COST_NET_BW_C2W=50",
			"-DSYSDS_FED_COST_NET_BW_W2C=400", "-DSYSDS_FED_COST_NET_BW_COORD_C2W=200",
			"-DSYSDS_FED_COST_NET_BW_COORD_W2C=" + (mode.equals("invalid") ? "-1" : mode.equals("skew") ? "10000" : "100"),
			"-DSYSDS_FED_COST_NET_SERDES_BW_C2W=0", "-DSYSDS_FED_COST_NET_SERDES_BW_W2C=0"));
		command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().keySet().removeIf(k -> k.startsWith("SYSDS_FED_COST_"));
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int result = process.waitFor();
		if(mode.equals("invalid")) {
			Assert.assertNotEquals(output, 0, result);
			Assert.assertTrue(output, output.contains("FED_COST_INVALID_NETWORK_THROUGHPUT"));
		}
		else Assert.assertEquals(output, 0, result);
	}

	public static class Probe {
		private static final double MIB=1024.0*1024.0;
		public static void main(String[] args) throws Exception {
			String mode=args[0];
			if(mode.equals("obsolete")) {
				Assert.assertEquals(65, FederatedCostModel.computeDownloadNetworkCost(8*MIB), 1e-9);
				for(String name : List.of("computeNetworkCost", "computeSingleWorkerFedExecPenalty",
					"computeLocalToFedForwardingPenalty"))
					for(var method : FederatedCostModel.class.getDeclaredMethods()) Assert.assertNotEquals(name, method.getName());
				return;
			}
			if(mode.equals("primitive")) {
				var method=FederatedCostModel.class.getDeclaredMethod("computeOneWayNetworkCost",
					double.class,double.class,double.class,double.class,double.class,double.class,double.class);
				method.setAccessible(true);
				Assert.assertEquals(703, (double)method.invoke(null, 10*MIB, 60*MIB, 20*MIB, 400d, 100d, 200d, .003), 1e-9);
				Assert.assertEquals(3, (double)method.invoke(null, 0d, 0d, 0d, 0d, 0d, 0d, .003), 0);
				return;
			}
			if(mode.equals("batch")) {
				var weights = new DataOp("W", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
					"W", 1000, 100, -1, 1000);
				var u = new DataOp("U", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
					"U", 1000, 2, -1, 1000);
				var v = new DataOp("V", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
					"V", 100, 2, -1, 1000);
				var op = new QuaternaryOp("wd", DataType.MATRIX, ValueType.FP64, OpOp4.WDIVMM,
					weights, u, v, new LiteralOp(0), 1, false, false);
				double uBytes = FederatedCostModel.getEffectiveOutputMemEstimate(u);
				double vBytes = FederatedCostModel.getEffectiveOutputMemEstimate(v);
				for(int workers : new int[]{1, 3, 7}) {
					// Both balanced slicing and broadcast have total = W * largest.
					// Thus operand-wise payload factors equal one combined-stage max,
					// including when worker and coordinator capacities differ.
					double largest = uBytes / workers + vBytes;
					double total = uBytes + workers * vBytes;
					Assert.assertEquals(Math.max(largest / MIB / 50, total / MIB / 200) * 1000,
						FederatedCostModel.computeWdivmmInputPreparationCost(op, op.getInput(),
							Arrays.asList(FType.ROW, null, null, null), workers), 1e-9);
				}
				return;
			}
			if(mode.equals("skew")) {
				Assert.assertEquals(160, FederatedCostModel.computeNativeFederatedLoutResultCost(null,
					new WorkerResponseSummary(64*MIB,60*MIB,3)),1e-9);
				Assert.assertEquals((64.0/3/400)*1000+10, FederatedCostModel.computeNativeFederatedLoutResultCost(null,
					new WorkerResponseSummary(64*MIB,64*MIB/3,3)),1e-9);
				return;
			}
			for(int workers : new int[]{1,3,7}) {
				if(!mode.equals("put")) {
				Assert.assertEquals(490, FederatedCostModel.computeDownloadNetworkCost(48*MIB,FType.ROW,workers), 1e-9);
				Assert.assertEquals(480*workers+10, FederatedCostModel.computeDownloadNetworkCost(48*MIB,FType.PART,workers), 1e-9);
				Assert.assertEquals(480*workers+10, FederatedCostModel.computeDownloadNetworkCost(48*MIB,FType.BROADCAST,workers), 1e-9);
				Assert.assertEquals(490, FederatedCostModel.computeDownloadNetworkCost(48*MIB,FType.FULL,workers), 1e-9);
				}
				if(mode.equals("put")) for(FType type : new FType[]{FType.ROW,FType.COL,FType.FULL,FType.BROADCAST}) {
					double total=type==FType.BROADCAST ? 48.0*workers : 48.0;
					double largest=type==FType.ROW||type==FType.COL ? 48.0/workers : 48.0;
					double payload=Math.max(largest/50,total/200)*1000;
					Assert.assertEquals(payload+10, FederatedCostModel.computeUploadNetworkCost(48*MIB,type,workers),1e-9);
					Assert.assertEquals(payload, FederatedCostModel.computeInBandUploadPayloadCost(48*MIB,type,workers),1e-9);
				}
			}
		}
	}
}
