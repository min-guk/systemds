/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Docker LM regression: normalized DP relocations must have one exact registry owner per slot. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpLmRegistrySlotRedTest {
	@Test
	public void lmDpEmitsOneRegistryOwnerPerRelocationSource() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldGlobal);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		int port = AutomatedTestBase.getRandomAvailablePort();
		Thread worker = null;
		try {
			Path features = matrixMetadata("target/g014-lm-worker-features.data", 50000, 2100, 100050000);
			Path labels = matrixMetadata("target/g014-lm-worker-labels.data", 50000, 1, 50000);
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = compile(lmScript(port, features, labels));
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue("LM must use the local-conflict DP planner, receipt=" + captured.get(),
				captured.get() instanceof ExactPlacementInput);
			translator.getRuntimeProgram(program, config);
		}
		finally {
			TestUtils.shutdownThreads(worker);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	@Test
	public void lmDpTwoWorkersLowersEverySelectedRefedConsumer() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldGlobal);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		int port1 = AutomatedTestBase.getRandomAvailablePort();
		int port2 = AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1)
			port2 = AutomatedTestBase.getRandomAvailablePort();
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			Path features1 = matrixMetadata("target/g014-lm-worker-features-1.data", 25000, 2100, 50025000);
			Path features2 = matrixMetadata("target/g014-lm-worker-features-2.data", 25000, 2100, 50025000);
			Path labels1 = matrixMetadata("target/g014-lm-worker-labels-1.data", 25000, 1, 25000);
			Path labels2 = matrixMetadata("target/g014-lm-worker-labels-2.data", 25000, 1, 25000);
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(port1, 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(port2, 1000);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = compile(lmScript(
				List.of(port1, port2), List.of(features1, features2), List.of(labels1, labels2)));
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue("LM must use the local-conflict DP planner, receipt=" + captured.get(),
				captured.get() instanceof ExactPlacementInput);
			translator.getRuntimeProgram(program, config);
		}
		finally {
			TestUtils.shutdownThreads(worker1, worker2);
			ConfigurationManager.setGlobalConfig(oldGlobal);
			ConfigurationManager.setLocalConfig(oldGlobal);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			PlacementEmissionTransaction.resetForTesting();
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
		}
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static String lmScript(int port, Path features, Path labels) {
		return "X=federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString()) + "\")," +
			"ranges=list(list(0,0),list(50000,2100)));\n" +
			"Y=federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString()) + "\")," +
			"ranges=list(list(0,0),list(50000,1)));\n" +
			"m=lm(X=X,y=Y,verbose=FALSE,tol=1e-9);\n" +
			"write(m,\"target/g014-dp-lm-registry-slot.csv\",format=\"csv\");\n";
	}

	private static String lmScript(List<Integer> ports, List<Path> features, List<Path> labels) {
		return "X=federated(addresses=list(\"" + TestUtils.federatedAddress(ports.get(0), features.get(0).toString())
			+ "\",\"" + TestUtils.federatedAddress(ports.get(1), features.get(1).toString()) + "\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n"
			+ "Y=federated(addresses=list(\"" + TestUtils.federatedAddress(ports.get(0), labels.get(0).toString())
			+ "\",\"" + TestUtils.federatedAddress(ports.get(1), labels.get(1).toString()) + "\"),"
			+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));\n"
			+ "m=lm(X=X,y=Y,verbose=FALSE,tol=1e-9);\n"
			+ "write(m,\"target/g014-dp-lm-registry-slot-two-workers.csv\",format=\"csv\");\n";
	}

	private static Path matrixMetadata(String path, long rows, long cols, long nnz) throws Exception {
		Path data = Path.of(path);
		Files.createDirectories(data.getParent());
		Files.writeString(data, "");
		Path mtd = Path.of(path + ".mtd");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\"," +
			"\"format\":\"text\",\"rows\":" + rows + ",\"cols\":" + cols + ",\"nnz\":" + nnz + "," +
			"\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		return data;
	}
}
