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
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.WorkerResponseSummary;
import org.junit.Assert;
import org.junit.Test;

public class GetAggregateProcessingCostTest {
	@Test public void coordinatorProcessingUsesTotalBytesNotThreadCount() throws Exception { probe("workers", false); }
	@Test public void processingPriceIsContinuousAcrossFourMiB() throws Exception { probe("threshold", false); }
	@Test public void skewChangesOnlyWireCriticalBytes() throws Exception { probe("skew", false); }
	@Test public void obsoleteEnvironmentSettingsDoNotChangePrice() throws Exception { probe("workers", true); }
	@Test public void explicitDirectionalZeroDisablesProcessing() throws Exception { probe("zero", false); }

	private static void probe(String mode, boolean environment) throws Exception {
		List<String> command = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java",
			"-DSYSDS_FED_COST_NET_BW_W2C=125",
			"-DSYSDS_FED_COST_NET_SERDES_BW_W2C=" + (mode.equals("zero") ? "0" : "14.7"),
			"-DSYSDS_FED_COST_NET_LATENCY_C2W=0.0005", "-DSYSDS_FED_COST_NET_LATENCY_W2C=0.0005"));
		if(!environment) command.addAll(List.of("-DSYSDS_FED_COST_INBAND_RESULT_SERDES_BW_W2C=999",
			"-DSYSDS_FED_COST_REUSABLE_GET_VAR_FAST_MAX_MB=4"));
		command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().remove("SYSDS_FED_COST_INBAND_RESULT_SERDES_BW_W2C");
		builder.environment().remove("SYSDS_FED_COST_REUSABLE_GET_VAR_FAST_MAX_MB");
		if(environment) {
			builder.environment().put("SYSDS_FED_COST_INBAND_RESULT_SERDES_BW_W2C", "999");
			builder.environment().put("SYSDS_FED_COST_REUSABLE_GET_VAR_FAST_MAX_MB", "4");
		}
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		Assert.assertEquals(output, 0, process.waitFor());

	}

	public static class Probe {
		private static final double MIB = 1024.0 * 1024.0;
		public static void main(String[] args) {
			double rate = args[0].equals("zero") ? 0.0 : 14.7;
			if(args[0].equals("skew")) {
				double balanced = price(64, 16, 4);
				double skewed = price(64, 40, 4);
				Assert.assertEquals((40.0 - 16.0) / 125.0 * 1000.0, skewed - balanced, 1e-8);
				Assert.assertEquals((16.0 / 125.0 + 64.0 / rate) * 1000.0 + 1, balanced, 1e-8);
				return;
			}
			for(int workers : new int[] {1, 3, 8, 16}) {
				for(double total : args[0].equals("threshold")
					? new double[] {(4.0 - 1.0 / 1024) * workers, 4.0 * workers, (4.0 + 1.0 / 1024) * workers}
					: new double[] {64.0}) {
					double expected = (total / workers / 125 + (rate > 0 ? total / rate : 0)) * 1000 + 1;
					double actual = FederatedCostModel.computeDownloadNetworkCost(total * MIB, FType.ROW, workers);
					Assert.assertEquals("Aggregate processing W=" + workers + " totalMiB=" + total, expected, actual, 1e-8);
					Assert.assertEquals(actual, FederatedCostModel.computeReusableMaterializationDownloadCost(
						total * MIB, FType.ROW, workers), 0.0);
				}
			}
		}
		private static double price(double total, double largest, int workers) {
			return FederatedCostModel.computeNativeFederatedLoutResultCost(null,
				new WorkerResponseSummary(total * MIB, largest * MIB, workers));
		}
	}
}
