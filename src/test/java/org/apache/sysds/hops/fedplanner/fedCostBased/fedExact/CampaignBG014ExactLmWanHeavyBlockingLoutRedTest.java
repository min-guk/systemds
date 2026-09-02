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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;
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
	public void exactUsesTheOccurrenceBoundLmLoopFrequencyOnWanHeavy() throws Exception {
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
			writeLmFixture(root, script, config, payloads);

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
				Assert.assertEquals("A cached local X must prevent repeated remote matrix products",
					ExecType.CP, selected.placementState().execType());
				Assert.assertEquals(FederatedOutput.LOUT, selected.placementState().output());
				Assert.assertEquals("LM's convergence loop must consume the exact scalar cap supplied"
					+ " through the function-call occurrence instead of falling back to ten iterations",
					Math.sqrt(2_100.0),
					result.analysis().executionFrequencyFacts().exactExecutionWeight(projection.key()),
					0.0);
			}
			List<?> materializations = result.selectedLocalMaterializations();
			Assert.assertEquals("The immutable function input X must be materialized only once", 1,
				materializations.size());
			Assert.assertTrue(materializations.get(0) instanceof LocalMaterializationActionKey);
			LocalMaterializationActionKey materialization =
				(LocalMaterializationActionKey) materializations.get(0);
			Assert.assertEquals("X", result.analysis().hop(materialization.sourceOccurrence())
				.orElseThrow().getName());
			Set<?> materializedConsumers = materialization.obligations().stream()
				.map(obligation -> obligation.consumerOccurrence()).collect(java.util.stream.Collectors.toSet());
			Assert.assertTrue(projections.stream().allMatch(projection ->
				materializedConsumers.contains(projection.key())));

			authorizeCompletedCompilerLoweringForReplay(result.analysis());
			ExactPhysicalModel model = ExactPhysicalModel.build(result.analysis());
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(result.analysis(), model);
			ExactPhysicalSelection local = ExactPhysicalSelection.create(model,
				LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
			for(var projection : projections) {
				var selected = local.selectedStates().get(projection.key());
				Assert.assertNotNull(selected);
				Assert.assertEquals("LM_LOCAL_SEARCH_MUST_RETAIN_THE_REUSABLE_X_"
					+ "MATERIALIZATION", ExecType.CP, selected.execType());
				Assert.assertEquals(FederatedOutput.LOUT, selected.output());
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

	@Test
	public void heuristicAssignmentIsFeasibleAndScoredOnTheSameExactSurface() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Map<String,String> oldCostProperties = installWanHeavyCostProperties();
		Path root = Files.createTempDirectory(Path.of("target"), "g014-exact-lm-heuristic-replay-");
		Path script = root.resolve("lm.dml");
		Path config = root.resolve("SystemDS-config.xml");
		List<Path> payloads = new ArrayList<>();
		try {
			writeLmFixture(root, script, config, payloads);
			resetPlannerState();
			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			}));
			var committed = PlacementEmissionTransaction.receiptSnapshotForTesting();
			Assert.assertEquals(1, committed.size());
			var compiled = PlacementEmissionTransaction.currentNormalizedResult(
				committed.keySet().iterator().next());
			var analysis = compiled.analysis();
			authorizeCompletedCompilerLoweringForReplay(analysis);
			Set<ValueVersionKey> markers = analysis.heuristicPolicyFacts().demotions().stream()
				.map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			var heuristic = new HeuristicPlacementAdapter(new PolicyFirstFeasiblePlacementSelector())
				.select(analysis, markers);

			ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
			ExactPhysicalCostModel.PhysicalCostSurface surface =
				ExactPhysicalCostModel.physicalCostSurface(analysis, model);
			ExactPhysicalOptimizer.Result exact = ExactPhysicalOptimizer.optimize(
				model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
			factors.addAll(surface.factors());
			int forcedDomains = 0;
			int solverCompletedDomains = 0;
			for(ExactPhysicalModel.DecisionDomain domain : model.domains()) {
				var selectedState = heuristic.selectedStates().get(domain.node().key());
				if(selectedState == null) {
					solverCompletedDomains++;
					continue;
				}
				double[] force = new double[domain.alternatives().size()];
				Arrays.fill(force, Double.POSITIVE_INFINITY);
				for(int value = 0; value < domain.alternatives().size(); value++)
					if(domain.alternatives().get(value).state() == selectedState
						|| domain.alternatives().get(value).state().equals(selectedState))
						force[value] = 0.0;
				Assert.assertTrue("LM_HEURISTIC_STATE_MISSING_FROM_EXACT_DOMAIN|decision="
					+ domain.node().key().normalizedSignature() + "|state="
					+ selectedState.normalizedSignature(),
					Arrays.stream(force).anyMatch(cost -> cost == 0.0));
				factors.add(ExactCategoricalSolver.Factor.dense(List.of(domain.variable()), force));
				forcedDomains++;
			}
			ExactCategoricalSolver.Result replay = ExactCategoricalSolver.solve(
				model.variables(), factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			long heuristicBits = surface.evaluateCanonical(replay.assignmentInVariableOrder());
			double heuristicObjective = Double.longBitsToDouble(heuristicBits);
			double exactObjective = Double.longBitsToDouble(exact.canonicalObjectiveBits());
			Assert.assertEquals("LM_HEURISTIC_REPLAY_SOLVER_OBJECTIVE_MUST_BE_CANONICAL",
				heuristicBits, Double.doubleToRawLongBits(replay.objective()));
			Assert.assertTrue("LM_FUNCTION_INPUT_DOWNLOAD_MUST_BE_ONE_REUSABLE_MATERIALIZATION_PER_CALL"
				+ "|heuristic=" + heuristicObjective, heuristicObjective < 50_000.0);
			Assert.assertTrue("LM_EXACT_MUST_NOT_EXCEED_FEASIBLE_HEURISTIC_PLACEMENT_OBJECTIVE"
				+ "|exact=" + exactObjective + "|heuristic=" + heuristicObjective,
				exactObjective <= heuristicObjective);
			Assert.assertTrue(forcedDomains > 0);
			Assert.assertEquals(0, solverCompletedDomains);
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

	private static void writeLmFixture(Path root, Path script, Path config,
		List<Path> payloads) throws Exception {
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
	}

	private static void authorizeCompletedCompilerLoweringForReplay(Object analysis) throws Exception {
		var method = analysis.getClass().getDeclaredMethod("authorizeCommittedProgramStructure");
		method.setAccessible(true);
		method.invoke(analysis);
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
