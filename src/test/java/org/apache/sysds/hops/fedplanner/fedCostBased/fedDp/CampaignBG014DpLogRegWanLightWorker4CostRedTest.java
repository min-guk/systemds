/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.conf.CompilerConfig;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.stats.InfrastructureAnalyzer;
import org.junit.Assert;
import org.junit.Test;

/** WAN-light Docker-shape regression for the four-worker LogReg Hessian-vector loop. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpLogRegWanLightWorker4CostRedTest {
	@Test
	public void logRegDpFourWorkersKeepsRepeatedHessianElementwiseChainLocal() throws Exception {
		int workerCount = Integer.getInteger("g014.logreg.workers", 4);
		Assert.assertTrue("The diagnostic worker override must remain within the campaign geometry",
			workerCount == 2 || workerCount == 4);
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		CompilerConfig oldCompiler = ConfigurationManager.getCompilerConfig();
		long oldLocalMaxMemory = InfrastructureAnalyzer.getLocalMaxMemory();
		Map<String, String> oldCostProperties = installWanLightCostProperties();
		int[] ports = uniquePorts(workerCount);
		Thread[] workers = new Thread[workerCount];
		Path root = Path.of("target/g014-dp-logreg-wan-light-worker" + workerCount);
		try {
			Files.createDirectories(root);
			List<Path> features = new ArrayList<>();
			List<Path> labels = new ArrayList<>();
			long rowsPerWorker = 50000 / workerCount;
			for(int i = 0; i < workers.length; i++) {
				features.add(matrixMetadata(root.resolve("features-" + (i + 1) + ".data"),
					rowsPerWorker, 2100, rowsPerWorker * 2001));
				labels.add(matrixMetadata(root.resolve("labels-" + (i + 1) + ".data"),
					rowsPerWorker, 1, rowsPerWorker));
				workers[i] = AutomatedTestBase.startLocalFedWorkerThread(ports[i], 1000);
			}
			Path script = root.resolve("logreg.dml");
			Path configPath = root.resolve("SystemDS-config.xml");
			Files.writeString(script, logRegScript(ports, features, labels, rowsPerWorker));
			Files.writeString(configPath, dockerCompileConfig(root));
			InfrastructureAnalyzer.setLocalMaxMemory(8L * 1024 * 1024 * 1024);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();

			Assert.assertTrue("The Docker-equivalent four-worker LogReg compile must complete",
				DMLScript.executeScript(new String[] {
					"-exec", "singlenode", "-seed", "2026072701", "-f", script.toString(),
					"-stats", "100", "-config", configPath.toString()
				}));
			Assert.assertEquals("The DP CLI fixture must publish one exact placement authority", 1,
				PlacementEmissionTransaction.receiptSnapshotForTesting().size());
			var result = PlacementEmissionTransaction.currentNormalizedResult(
				PlacementEmissionTransaction.receiptSnapshotForTesting().keySet().iterator().next());
			Assert.assertEquals("DP", result.plannerId());

			var repeatedElementwiseProducts = result.analysis().occurrences().stream()
				.filter(occurrence -> occurrence.hop().getBeginLine() == 197)
				.filter(occurrence -> occurrence.hop() instanceof BinaryOp)
				.filter(occurrence -> ((BinaryOp) occurrence.hop()).getOp() == OpOp2.MULT)
				.toList();
			List<String> nearby = result.analysis().occurrences().stream()
				.filter(occurrence -> occurrence.hop().getBeginLine() >= 190
					&& occurrence.hop().getBeginLine() <= 205)
				.map(occurrence -> occurrence.hop().getBeginLine() + ":"
					+ occurrence.hop().getHopID() + ":" + occurrence.hop().getClass().getSimpleName()
					+ ":" + occurrence.hop().getOpString() + ":"
					+ result.selectedStates().get(occurrence.key()))
				.toList();
			Assert.assertEquals("The LogReg Hessian-vector fixture must retain Q=P_1K*(X%*%ssX_V); nearby="
				+ nearby, 1, repeatedElementwiseProducts.size());
			PlacementState selected = result.selectedStates().get(repeatedElementwiseProducts.get(0).key());
			Assert.assertNotNull("DP must publish the repeated elementwise-product placement", selected);
			Assert.assertEquals("WAN-light control latency makes the repeated elementwise product local; nearby="
				+ nearby,
				ExecType.CP, selected.execType());
			Assert.assertEquals(FederatedOutput.LOUT, selected.output());
		}
		finally {
			TestUtils.shutdownThreads(workers);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			ConfigurationManager.setGlobalConfig(oldCompiler);
			ConfigurationManager.setLocalConfig(oldCompiler);
			InfrastructureAnalyzer.setLocalMaxMemory(oldLocalMaxMemory);
			restoreProperties(oldCostProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static int[] uniquePorts(int count) {
		int[] ports = new int[count];
		for(int i = 0; i < ports.length; i++) {
			boolean duplicate;
			do {
				ports[i] = AutomatedTestBase.getRandomAvailablePort();
				duplicate = false;
				for(int j = 0; j < i; j++)
					duplicate |= ports[j] == ports[i];
			}
			while(duplicate);
		}
		return ports;
	}

	private static String logRegScript(int[] ports, List<Path> features, List<Path> labels,
		long rowsPerWorker) {
		List<String> featureAddresses = new ArrayList<>();
		List<String> labelAddresses = new ArrayList<>();
		for(int i = 0; i < ports.length; i++) {
			featureAddresses.add("\"" + TestUtils.federatedAddress(ports[i], features.get(i).toString()) + "\"");
			labelAddresses.add("\"" + TestUtils.federatedAddress(ports[i], labels.get(i).toString()) + "\"");
		}
		List<String> featureRanges = new ArrayList<>();
		List<String> labelRanges = new ArrayList<>();
		for(int i = 0; i < ports.length; i++) {
			long begin = i * rowsPerWorker;
			long end = (i + 1L) * rowsPerWorker;
			featureRanges.add("list(" + begin + ",0)");
			featureRanges.add("list(" + end + ",D)");
			labelRanges.add("list(" + begin + ",0)");
			labelRanges.add("list(" + end + ",1)");
		}
		String ranges = "list(" + String.join(",", featureRanges) + ")";
		String labelRangeList = "list(" + String.join(",", labelRanges) + ")";
		return "N=50000;\n"
			+ "D=2100;\n"
			+ "X=federated(addresses=list(" + String.join(",", featureAddresses) + "),ranges=" + ranges + ");\n"
			+ "Y=federated(addresses=list(" + String.join(",", labelAddresses) + "),ranges=" + labelRangeList + ");\n"
			+ "Y=(Y<0)+1;\n"
			+ "m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0,"
			+ "numclasses=2,numrows=N,numcols=D);\n"
			+ "write(m,\"target/g014-dp-logreg-wan-light-worker4.csv\",format=\"csv\");\n";
	}

	private static String dockerCompileConfig(Path root) {
		return "<root>\n"
			+ "  <sysds.native.blas>none</sysds.native.blas>\n"
			+ "  <sysds.local.spark>true</sysds.local.spark>\n"
			+ "  <sysds.federated.planner>compile_cost_based</sysds.federated.planner>\n"
			+ "  <sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>\n"
			+ "  <sysds.scratch>" + root.resolve("scratch") + "</sysds.scratch>\n"
			+ "  <sysds.localtmpdir>" + root.resolve("localtmp") + "</sysds.localtmpdir>\n"
			+ "</root>\n";
	}

	private static Path matrixMetadata(Path data, long rows, long cols, long nnz) throws Exception {
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Path metadata = Path.of(data + ".mtd");
		Files.writeString(metadata, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"binary\",\"rows\":" + rows + ",\"cols\":" + cols
			+ ",\"rows_in_block\":1000,\"cols_in_block\":1000,\"nnz\":" + nnz + ","
			+ "\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		metadata.toFile().deleteOnExit();
		return data;
	}

	private static Map<String, String> installWanLightCostProperties() {
		Map<String, String> values = Map.of(
			"SYSDS_FED_COST_MEM_BW", "25000",
			"SYSDS_FED_COST_NET_BW", "125",
			"SYSDS_FED_COST_NET_BW_C2W", "125",
			"SYSDS_FED_COST_NET_BW_W2C", "125",
			"SYSDS_FED_COST_NET_SERDES_BW", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_C2W", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7",
			"SYSDS_FED_COST_NET_LATENCY", "0.020",
			"SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0",
			"SYSDS_FED_COST_FLOPS", "2147483648");
		Map<String, String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Map<String, String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}
}
