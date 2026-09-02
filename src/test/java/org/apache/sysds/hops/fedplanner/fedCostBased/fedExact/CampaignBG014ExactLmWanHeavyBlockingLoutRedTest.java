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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Campaign-shaped LM regression for a loop-amplified FED/LOUT blocking result. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014ExactLmWanHeavyBlockingLoutRedTest {
	@Test
	public void exactKeepsTheRepeatedLmProjectionWorkerResidentOnWanHeavy() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldCostProperties = installWanHeavyCostProperties();
		Path root = Files.createTempDirectory(Path.of("target"), "g014-exact-lm-wan-heavy-");
		Path script = root.resolve("lm.dml");
		Path config = root.resolve("SystemDS-config.xml");
		List<Path> payloads = new ArrayList<>();
		try {
			List<String> xAddresses = new ArrayList<>();
			List<String> yAddresses = new ArrayList<>();
			long[] rows = {16_667, 16_667, 16_666};
			for(int worker = 0; worker < rows.length; worker++) {
				Path x = root.resolve("x-" + worker + ".data");
				Path y = root.resolve("y-" + worker + ".data");
				payloads.add(x);
				payloads.add(y);
				xAddresses.add(ExactCliMetadataFixture.privateAggregateAddress(
					"worker" + (worker + 1), 8001 + worker, x, rows[worker], 2_100,
					rows[worker] * 2_100));
				yAddresses.add(ExactCliMetadataFixture.privateAggregateAddress(
					"worker" + (worker + 1), 8001 + worker, y, rows[worker], 1, rows[worker]));
			}
			Files.writeString(script, String.join("\n",
				"X = federated(addresses=list(\"" + String.join("\", \"", xAddresses)
					+ "\"), ranges=list(list(0,0), list(16667,2100), list(16667,0),"
					+ " list(33334,2100), list(33334,0), list(50000,2100)))",
				"Y = federated(addresses=list(\"" + String.join("\", \"", yAddresses)
					+ "\"), ranges=list(list(0,0), list(16667,1), list(16667,0),"
					+ " list(33334,1), list(33334,0), list(50000,1)))",
				"m = lm(X=X, y=Y, verbose=FALSE, tol=1e-9)",
				"write(m, \"" + root.resolve("result.csv") + "\", format=\"csv\")", ""));
			Files.writeString(config, String.join("\n",
				"<root>",
				"  <sysds.local.spark>true</sysds.local.spark>",
				"  <sysds.federated.planner>compile_exact</sysds.federated.planner>",
				"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
				"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>",
				"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>",
				"</root>", ""));

			resetPlannerState();
			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			}));
			var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
			Assert.assertEquals(1, committed.size());
			var result = PlacementEmissionTransaction.currentNormalizedResult(
				committed.keySet().iterator().next());
			var projections = result.analysis().occurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof AggBinaryOp mm
					&& mm.isMatrixMultiply() && mm.getBeginLine() == 129)
				.filter(occurrence -> occurrence.hop().getInput(0) instanceof DataOp input
					&& "X".equals(input.getName()))
				.toList();
			Assert.assertFalse("LM fixture must expose X %*% ssX_p in the iterative body",
				projections.isEmpty());
			for(var projection : projections) {
				var selected = result.selectedEmissionStates().get(projection.key());
				Assert.assertNotNull(selected);
				Assert.assertEquals("WAN-Heavy LM must avoid a blocking coordinator result on every"
					+ " estimated loop execution", ExecType.FED, selected.placementState().execType());
				Assert.assertEquals(FederatedOutput.FOUT, selected.placementState().output());
				Assert.assertEquals(FType.ROW, selected.placementState().fType());
			}
		}
		finally {
			ExactCliMetadataFixture.delete(payloads.toArray(Path[]::new));
			restoreProperties(oldCostProperties);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			resetPlannerState();
		}
	}

	private static Map<String,String> installWanHeavyCostProperties() {
		Map<String,String> values = Map.ofEntries(
			Map.entry("DOCKER_NUM_WORKERS", "3"),
			Map.entry("SYSDS_FED_COST_MEM_BW", "25000"),
			Map.entry("SYSDS_FED_COST_FLOPS", "2147483648"),
			Map.entry("SYSDS_FED_COST_NET_BW", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_BW_C2W", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_BW_W2C", "12.5"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_C2W", "210"),
			Map.entry("SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7"),
			Map.entry("SYSDS_FED_COST_NET_LATENCY", "0.2"),
			Map.entry("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0.35"));
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Map<String,String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}

	private static void resetPlannerState() {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}
}
