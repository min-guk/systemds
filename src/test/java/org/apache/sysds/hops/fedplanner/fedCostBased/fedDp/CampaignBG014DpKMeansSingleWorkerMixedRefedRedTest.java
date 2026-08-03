/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** Docker KMeans regression: one-worker FULL remains executable without closing BROADCAST candidates. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpKMeansSingleWorkerMixedRefedRedTest {
	@Test
	public void oneWorkerDpKMeansPreservesAndLowersEveryConsumerSpecificRefedLayout() throws Exception {
		Map<String,String> oldCostProperties = installDockerLanCostProperties();
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
		Path root = Path.of("target/g014-dp-kmeans-single-full-worker");
		try {
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			InfrastructureAnalyzer.setLocalPar(8);
			Path input = matrixMetadata(root.resolve("features.data"));
			Path script = root.resolve("kmeans.dml");
			Path config = root.resolve("SystemDS-config.xml");
			Files.writeString(script, kmeansSingleFullWorkerScript(port, input, root.resolve("result.csv")));
			Files.writeString(config, config(root));
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			boolean success = DMLScript.executeScript(new String[] {
				"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
				"-stats", "100", "-config", config.toString()
			});
			Assert.assertTrue("The Docker-equivalent KMeans compile and Dag lowering must complete", success);
			Assert.assertEquals("The DP CLI fixture must publish one exact placement authority", 1,
				PlacementEmissionTransaction.receiptSnapshotForTesting().size());
			NormalizedPlannerResult result = PlacementEmissionTransaction.currentNormalizedResult(
				PlacementEmissionTransaction.receiptSnapshotForTesting().keySet().iterator().next());
			Assert.assertEquals("DP", result.plannerId());

			Map<RelocationDemandKey,Set<FType>> legalTypes = new LinkedHashMap<>();
			result.analysis().graph().relocationActions().forEach(action -> action.obligations().forEach(obligation ->
				legalTypes.computeIfAbsent(RelocationDemandKey.from(obligation), ignored -> new LinkedHashSet<>())
					.add(action.key().materializationFType())));
			var choices = result.selectedRelocationChoices();
			Assert.assertEquals("Every exact relocation demand must have one choice", choices.size(),
				choices.stream().map(choice -> choice.demand()).distinct().count());
			for(var choice : choices)
				Assert.assertTrue("DP must select an action admitted by the exact relocation demand",
					legalTypes.getOrDefault(choice.demand(), Set.of())
						.contains(choice.action().materializationFType()));
			Assert.assertTrue("The one-worker KMeans plan must retain a FULL exact relocation choice",
				choices.stream().anyMatch(choice ->
					choice.action().materializationFType() == FType.FULL));
			Assert.assertTrue("The one-worker KMeans graph must not close BROADCAST relocation candidates",
				legalTypes.values().stream().anyMatch(types -> types.contains(FType.BROADCAST)));

			Map<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey,
				org.apache.sysds.hops.fedplanner.placement.PlacementState> assignment = new LinkedHashMap<>();
			result.selectedEmissionStates().forEach((key, state) -> assignment.put(key, state.placementState()));
			Assert.assertEquals("Emitted actions must be exactly the physical subset of DP choices",
				result.selectedRelocations(), RelocationSelections.emittedActions(
					result.analysis(), assignment, result.selectedCandidateSelections(), choices));

			var resolved = RelocationSelections.resolveAndValidate(
				result.analysis(), assignment, result.selectedCandidateSelections(), choices);
			for(var choice : resolved) {
				if(!choice.requiresEmission())
					continue;
				List<HopOccurrenceProjection> sourceOccurrences = result.analysis().occurrences().stream()
					.filter(occurrence -> result.analysis().graph().node(occurrence.key()).orElseThrow()
						.valueVersion().equals(choice.receipt().demand().sourceValueVersion()))
					.filter(occurrence -> result.analysis().graph().node(occurrence.key()).orElseThrow().emittedWork())
					.toList();
				Assert.assertEquals("A selected KMeans relocation must have one emitted source owner",
					1, sourceOccurrences.size());
				HopOccurrenceProjection source = sourceOccurrences.get(0);
				FederatedRefedRegistry.AnchorSpec spec = FederatedRefedRegistry.snapshot(source.scopeId())
					.get(source.hop().getHopID());
				Assert.assertNotNull("Every emitted KMeans relocation must reach the refed registry", spec);
				long consumerHopId = result.analysis().hop(choice.receipt().demand().consumer())
					.orElseThrow().getHopID();
				var exactInput = new FederatedRefedRegistry.ConsumerInputSpec(
					consumerHopId, choice.receipt().demand().inputPosition());
				var owners = spec.getAuthorities().stream()
					.filter(authority -> authority.getConsumerInputs().contains(exactInput)).toList();
				Assert.assertEquals("An exact consumer input must have one registry authority", 1, owners.size());
				Assert.assertEquals("Registry layout must equal the DP edge choice",
					choice.receipt().action().materializationFType(), owners.get(0).getMaterializationFType());
			}

			for(var spec : FederatedRefedRegistry.snapshotAll().scopes().values().stream()
				.flatMap(scope -> scope.values().stream()).toList()) {
				Set<FederatedRefedRegistry.ConsumerInputSpec> ownedInputs = new HashSet<>();
				for(var authority : spec.getAuthorities())
					for(var inputSpec : authority.getConsumerInputs())
						Assert.assertTrue("No exact consumer input may have conflicting registry authorities",
							ownedInputs.add(inputSpec));
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
			restoreProperties(oldCostProperties);
		}
	}

	private static String kmeansSingleFullWorkerScript(int port, Path input, Path output) {
		return "X=federated(addresses=list(\"" + TestUtils.federatedAddress(port, input.toString()) + "\"),"
			+ "ranges=list(list(0,0),list(50000,2100)));\n"
			+ "[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
			+ "avg_sample_size_per_centroid=50,seed=133815928);\n"
			+ "write(Y,\"" + output + "\",format=\"csv\");\n";
	}

	private static String config(Path root) {
		return "<root>\n" +
			"  <sysds.native.blas>mkl</sysds.native.blas>\n" +
			"  <sysds.local.spark>true</sysds.local.spark>\n" +
			"  <sysds.federated.planner>compile_cost_based</sysds.federated.planner>\n" +
			"  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>\n" +
			"  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n" +
			"  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n" +
			"</root>\n";
	}

	private static Path matrixMetadata(Path data) throws Exception {
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Files.writeString(Path.of(data + ".mtd"), "{\"data_type\":\"matrix\"," +
			"\"value_type\":\"double\",\"format\":\"binary\",\"rows\":50000," +
			"\"cols\":2100,\"rows_in_block\":1000,\"cols_in_block\":1000," +
			"\"nnz\":100050000,\"privacy\":\"private-aggregate\"}");
		return data;
	}

	private static Map<String,String> installDockerLanCostProperties() {
		Map<String,String> values = Map.of(
			"SYSDS_FED_COST_MEM_BW", "25000",
			"SYSDS_FED_COST_NET_BW", "1250",
			"SYSDS_FED_COST_NET_BW_C2W", "1250",
			"SYSDS_FED_COST_NET_BW_W2C", "1250",
			"SYSDS_FED_COST_NET_SERDES_BW", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_C2W", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7",
			"SYSDS_FED_COST_NET_LATENCY", "0.001",
			"SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0",
			"SYSDS_FED_COST_FLOPS", "2147483648");
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
}
