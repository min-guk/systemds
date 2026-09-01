/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedAll;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Docker-shaped KMeans regression for exact graph-owned derived FOUT emission authority. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014FedAllKMeansDerivedFoutAuthorityRedTest {
	@Test
	public void oneWorkerFedAllKMeansCommitsExactFoutAuthority() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		int oldLocalParallelism = InfrastructureAnalyzer.getLocalParallelism();
		boolean oldStatistics = DMLScript.STATISTICS;
		int oldStatisticsCount = DMLScript.STATISTICS_COUNT;
		int oldSeed = DMLScript.SEED;
		boolean oldLocalSpark = DMLScript.USE_LOCAL_SPARK_CONFIG;
		String oldParserPath = DMLScript.DML_FILE_PATH_ANTLR_PARSER;
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		Path root = Path.of("target/g014-fedall-kmeans-single-full-worker");
		try {
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			InfrastructureAnalyzer.setLocalPar(8);
			Path input = matrixMetadata(root.resolve("features.data"));
			Path script = root.resolve("kmeans.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, script(port, input, root.resolve("result.csv")));
			Files.writeString(config, config(root));
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			boolean success = DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			});
			Assert.assertTrue("The Docker-shaped FedAll KMeans compile and lowering must complete", success);
			Assert.assertEquals("FedAll must publish one exact placement authority", 1,
				PlacementEmissionTransaction.receiptSnapshotForTesting().size());
			var program = PlacementEmissionTransaction.receiptSnapshotForTesting().keySet().iterator().next();
			var result = PlacementEmissionTransaction.currentNormalizedResult(program);
			Assert.assertEquals("FED_ALL", result.plannerId());
			Assert.assertFalse("KMeans must exercise derived FOUT authority",
				result.analysis().graph().derivedFoutMaterializationActions().isEmpty());
			long selectedDerived = result.selectedEmissionStates().entrySet().stream()
				.filter(entry -> entry.getValue().derivedFedFout()).count();
			int selectedDerivedActions = CandidateSelections.derivedFoutPhysicalEmissionCount(
				result.selectedCandidateSelections());
			Assert.assertEquals("Every selected derived FOUT state must own one exact physical action",
				selectedDerived, selectedDerivedActions);
			long selectedDirectFullDistanceProducts = result.selectedStates().entrySet().stream()
				.filter(entry -> {
					var hop = result.analysis().hop(entry.getKey()).orElse(null);
					return hop != null && hop.getBeginLine() == 134 && "ba(+*)".equals(hop.getOpString())
						&& entry.getValue().execType() == ExecType.FED
						&& entry.getValue().output() == FederatedOutput.FOUT;
				})
				.count();
			Assert.assertTrue("Single-worker FULL KMeans must use the runtime-supported direct FED/FOUT "
				+ "distance product instead of forcing an unnecessary derived upload",
				selectedDirectFullDistanceProducts > 0);
			int exactPhysicalTransfers = Math.addExact(Math.addExact(
				RelocationSelections.physicalEmissionCount(result.selectedRelocations()),
				result.selectedLocalMaterializations().size()), selectedDerivedActions);
			Assert.assertTrue("FedAll's exact objective must count derived FOUT uploads as physical transfers: "
				+ result.objectiveCertificate(), result.objectiveCertificate().contains(
					"relocationCount=" + exactPhysicalTransfers + ","));
			for(Object localValue : result.selectedLocalMaterializations()) {
				Assert.assertTrue(localValue instanceof LocalMaterializationActionKey);
				LocalMaterializationActionKey local = (LocalMaterializationActionKey) localValue;
				Assert.assertFalse("A derived FOUT source already owns a physical local result and must not "
					+ "request a second FOUT-to-local prefetch: " + local.normalizedSignature(),
					result.selectedEmissionStates().get(local.sourceOccurrence()).derivedFedFout());
			}
			for(var selected : result.selectedEmissionStates().entrySet()) {
				if(!selected.getValue().derivedFedFout()
					|| !result.analysis().isCompiledHopOccurrence(selected.getKey()))
					continue;
				var occurrence = result.analysis().occurrences().stream()
					.filter(candidate -> candidate.key() == selected.getKey()).findFirst().orElseThrow();
				var spec = FederatedFoutMaterializeRegistry.snapshot(occurrence.scopeId())
					.get(occurrence.hop().getHopID());
				Assert.assertNotNull("Selected derived FOUT must own a lowering registration", spec);
				Assert.assertTrue("Common planning must retain exact PRESENT consumer input authority",
					spec.hasExactConsumerAuthority());
			}
			for(var action : result.analysis().graph().derivedFoutMaterializationActions()) {
				var producer = result.analysis().graph().node(action.key().producer()).orElseThrow();
				Assert.assertSame("Every derived FOUT action must retain the final graph-owned value version",
					producer.valueVersion(), action.key().producerValueVersion());
			}
		}
		finally {
			TestUtils.shutdownThreads(worker);
			InfrastructureAnalyzer.setLocalMaxMemory(oldLocalMaxMemory);
			InfrastructureAnalyzer.setLocalPar(oldLocalParallelism);
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

	private static String script(int port, Path input, Path output) {
		return "X=federated(addresses=list(\"" + TestUtils.federatedAddress(port, input.toString()) + "\"),"
			+ "ranges=list(list(0,0),list(50000,2100)));\n"
			+ "[C_n,Y_n]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
			+ "avg_sample_size_per_centroid=50,seed=133815928);\n"
			+ "write(Y_n,\"" + output + "\",format=\"csv\");\n";
	}

	private static String config(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>mkl</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_fed_all</sysds.federated.planner>\n"
			+ "  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static Path matrixMetadata(Path data) throws Exception {
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\","
			+ "\"value_type\":\"double\",\"format\":\"binary\",\"rows\":50000,"
			+ "\"cols\":2100,\"rows_in_block\":1000,\"cols_in_block\":1000,"
			+ "\"nnz\":100050000,\"privacy\":\"private-aggregate\"}");
		return data;
	}
}
