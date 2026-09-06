/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** The production local-conflict DP must retain the nested protected StepLM loop. */
@net.jcip.annotations.NotThreadSafe
public class StepLmPrivateAggregatePlanningContractTest {
	@Test
	public void localConflictDpPlansAndLowersHybridStepLmWithoutProtectedRelocation() throws Exception {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		ExecMode oldMode = DMLScript.getGlobalExecMode();
		long oldMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		String oldAudit = System.getProperty(PlannerRuntimePlacementAudit.PROPERTY);
		Path root = Files.createTempDirectory("steplm-private-cfg-");
		Thread worker = null;
		try {
			DMLScript.setGlobalExecMode(ExecMode.HYBRID);
			DMLConfig config = new DMLConfig(oldConfig);
			config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			CompilerConfig compiler = OptimizerUtils.constructCompilerConfig(config);
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, Boolean.TRUE.toString());
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			Path x = metadata(root.resolve("X"), 2100, 100050000);
			Path y = metadata(root.resolve("Y"), 1, 50000);
			int port = AutomatedTestBase.getRandomAvailablePort();
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			ConfigurationManager.setGlobalConfig(compiler);
			ConfigurationManager.setLocalConfig(compiler);
			String script = "X=federated(addresses=list(\"" + TestUtils.federatedAddress(port, x.toString())
				+ "\"),ranges=list(list(0,0),list(50000,2100)));\n"
				+ "Y=federated(addresses=list(\"" + TestUtils.federatedAddress(port, y.toString())
				+ "\"),ranges=list(list(0,0),list(50000,1)));\n"
				+ "[B,S]=steplm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,verbose=FALSE);\n"
				+ "write(B,\"" + root.resolve("out.csv") + "\",format=\"csv\");\n";
			DMLProgram program = ParserFactory.createParser().parse(
				DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
			DMLTranslator translator = new DMLTranslator(program);
			translator.liveVariableAnalysis(program);
			translator.validateParseTree(program);
			translator.constructHops(program);
			translator.rewriteHopsDAG(program);
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			translator.constructLops(program, captured::set);
			Assert.assertTrue("Current DP uses the shared normalized placement carrier",
				captured.get() instanceof ExactPlacementInput);
			var result = ((ExactPlacementInput) captured.get()).normalizedResult();
			Assert.assertEquals("DP-LocalConflict", result.plannerId());
			var analysis = result.analysis();
			var loopReads = analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTREAD && "X_global".equals(data.getName())
					&& data.getBeginLine() == 163).toList();
			Assert.assertFalse("Fixture must retain the actual nested loop-body read", loopReads.isEmpty());
			for(var read : loopReads) {
				Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(read.key()));
				var state = result.selectedStates().get(read.key());
				Assert.assertEquals(ExecType.FED, state.execType());
				Assert.assertEquals(FederatedOutput.FOUT, state.output());
				Assert.assertEquals("The single-worker loop must retain its native FULL state",
					FType.FULL, state.fType());
			}
			for(var node : analysis.graph().decisionNodes())
				Assert.assertTrue("Selection must be an exact member of its privacy-filtered domain",
					node.legalAlternatives().stream().anyMatch(state -> state == result.selectedStates().get(node.key())));
			Assert.assertTrue("Protected data must not acquire a relocation to repair its pool proof",
				result.selectedRelocations().stream().noneMatch(action -> analysis.graph().nodes().stream()
					.anyMatch(node -> node.valueVersion().equals(action.sourceValueVersion())
						&& analysis.requirePrivacy(node.key()) == Privacy.PRIVATE_AGGREGATE)));
			translator.getRuntimeProgram(program, config);
			String audit = PlannerRuntimePlacementAudit.display();
			Assert.assertTrue(audit, audit.contains("missingPhysicalHops=0"));
			Assert.assertTrue(audit, audit.contains("mismatches=0"));
			var observed = PlacementEmissionTransaction.observabilitySnapshot();
			Assert.assertEquals(0, observed.runtimeFallbackCount());
			Assert.assertEquals(0, observed.runtimeRepairCount());
			Assert.assertFalse("Planning must not execute the model", Files.exists(root.resolve("out.csv")));
		}
		finally {
			TestUtils.shutdownThreads(worker);
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.setGlobalExecMode(oldMode);
			InfrastructureAnalyzer.setLocalMaxMemory(oldMemory);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			if(oldAudit == null)
				System.clearProperty(PlannerRuntimePlacementAudit.PROPERTY);
			else
				System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, oldAudit);
			try(var paths = Files.walk(root)) {
				for(Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	private static Path metadata(Path path, int cols, long nnz) throws Exception {
		Files.writeString(path, "");
		Files.writeString(Path.of(path + ".mtd"), "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"binary\",\"rows\":50000,\"cols\":" + cols
			+ ",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + nnz
			+ ",\"privacy\":\"private-aggregate\"}");
		return path;
	}
}
