/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedAll;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression for LM's ROW/COL vector-times-federated-MM output materialization. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014FedAllLmMixedRowColDerivedFoutRedTest {
	@Test
	public void twoWorkerMixedRowColCandidateRetainsDerivedBroadcastFout() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		Path root = Files.createTempDirectory(Path.of("target"), "g014-fedall-lm-row-col-");
		Path script = root.resolve("lm-row-col.dml");
		Path config = root.resolve("SystemDS-config.xml");
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		try {
			Files.writeString(script, String.join("\n",
				"X = federated(addresses=list(\"worker1:8001/data/features-1.data\", "
					+ "\"worker2:8002/data/features-2.data\"), ranges=list(list(0, 0), "
					+ "list(25000, 2100), list(25000, 0), list(50000, 2100)))",
				"Y = federated(addresses=list(\"worker1:8001/data/labels-1.data\", "
					+ "\"worker2:8002/data/labels-2.data\"), ranges=list(list(0, 0), "
					+ "list(25000, 1), list(25000, 0), list(50000, 1)))",
				"m = lm(X=X, y=Y, verbose=FALSE, tol=1e-9)",
				"write(m, \"" + root.resolve("result.csv") + "\", format=\"csv\")", ""));
			Files.writeString(config, String.join("\n",
				"<root>",
				"  <sysds.local.spark>true</sysds.local.spark>",
				"  <sysds.federated.planner>compile_fed_all</sysds.federated.planner>",
				"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>",
				"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>",
				"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>",
				"</root>", ""));

			Assert.assertTrue(DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			}));
			Assert.assertEquals(1, PlacementEmissionTransaction.receiptSnapshotForTesting().size());
			var program = PlacementEmissionTransaction.receiptSnapshotForTesting().keySet().iterator().next();
			var result = PlacementEmissionTransaction.currentNormalizedResult(program);
			var mixedRow = CandidateInputState.present(FType.ROW);
			var mixedCol = CandidateInputState.present(FType.COL);
			var outputProduct = result.analysis().occurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof AggBinaryOp)
				.filter(occurrence -> occurrence.hop().getBeginLine() == 129)
				.filter(occurrence -> result.analysis().candidateRuleFacts().orderedFacts().stream()
					.anyMatch(fact -> fact.key().parentOccurrence() == occurrence.key()
						&& fact.key().orderedInputs().equals(java.util.List.of(mixedCol, mixedRow))))
				.findFirst().orElseThrow();
			boolean candidateExists = result.analysis().candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == outputProduct.key())
				.filter(fact -> fact.key().orderedInputs().equals(java.util.List.of(mixedCol, mixedRow)))
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().derivedFedFout()
					&& emission.emissionState().placementState().execType() == ExecType.FED
					&& emission.emissionState().placementState().output() == FederatedOutput.FOUT
					&& emission.emissionState().placementState().fType() == FType.BROADCAST);
			Assert.assertTrue("The exact mixed ROW/COL candidate must retain FED->LOUT->FOUT/BROADCAST",
				candidateExists);
			var selected = result.selectedEmissionStates().get(outputProduct.key());
			Assert.assertNotNull(selected);
			Assert.assertTrue("FedAll must select the legal derived FOUT candidate", selected.derivedFedFout());
			Assert.assertEquals(ExecType.FED, selected.placementState().execType());
			Assert.assertEquals(FederatedOutput.FOUT, selected.placementState().output());
			Assert.assertEquals(FType.BROADCAST, selected.placementState().fType());
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			DMLScript.STATISTICS = oldStatistics;
			DMLScript.STATISTICS_COUNT = oldStatisticsCount;
			DMLScript.SEED = oldSeed;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldLocalSpark;
			DMLScript.DML_FILE_PATH_ANTLR_PARSER = oldParserPath;
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}
}
