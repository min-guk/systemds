/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Assert;
import org.junit.Test;

/** Docker LogReg regression: transient forwards retain one exact CP/LOUT source authority. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpLogRegTransientForwardRedTest {
	@Test
	public void logRegDpKeepsExactTransientForwardAuthority() throws Exception {
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
			Path features = matrixMetadata("target/g014-logreg-worker-features.data", 50000, 2100, 100050000);
			Path labels = matrixMetadata("target/g014-logreg-worker-labels.data", 50000, 1, 50000);
			worker = AutomatedTestBase.startLocalFedWorkerThread(port, 1000);
			ConfigurationManager.setGlobalConfig(config);
			ConfigurationManager.setLocalConfig(config);
			DMLProgram program = compile(logRegScript(port, features, labels));
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue("LogReg must use the DP planner, receipt=" + captured.get(),
				captured.get() instanceof DpInvocationReceipt);
			assertBranchJoinForwardUsesOnlyNeutralAuthorizedState((DpInvocationReceipt) captured.get());
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

	private static void assertBranchJoinForwardUsesOnlyNeutralAuthorizedState(DpInvocationReceipt receipt) {
		List<RewireTransientForwardEdge> schedulingOnly = receipt.semanticConsumption().rewireSnapshot()
			.transientForwardEdges().stream().filter(edge -> {
				String writeName = receipt.analysis().hop(edge.writeOccurrence()).orElseThrow().getName();
				String readName = receipt.analysis().hop(edge.readOccurrence()).orElseThrow().getName();
				boolean hasPhysicalOwner = receipt.analysis().compiledInputEdgesInCanonicalOrder().stream()
					.anyMatch(fact -> fact.producer() == edge.writeOccurrence()
						&& fact.consumer() == edge.readOccurrence());
				boolean hasLogicalOwner = receipt.analysis().logicalTransientInputsInCanonicalOrder().stream()
					.anyMatch(fact -> fact.sourceWrite() == edge.writeOccurrence()
						&& fact.targetRead() == edge.readOccurrence());
				return "rowSums_X_sq".equals(writeName) && "rowSums_X_sq".equals(readName)
					&& !hasPhysicalOwner && !hasLogicalOwner;
			}).toList();
		Assert.assertFalse("LogReg must retain the branch-join scheduling dependency", schedulingOnly.isEmpty());
		for(RewireTransientForwardEdge edge : schedulingOnly) {
			Assert.assertTrue("branch-join read must be neutral-authorized only as CP/LOUT",
				receipt.analysis().graph().node(edge.readOccurrence()).orElseThrow().legalAlternatives().stream()
					.allMatch(state -> state.execType() == ExecType.CP
						&& state.output() == FederatedOutput.LOUT && state.fType() == null));
			List<CandidateDecisionReceipt> decisions = receipt.semanticConsumption().semanticBlock()
				.candidateDecisionReceipts().stream()
					.filter(decision -> decision.candidateSnapshot().parentOccurrence() == edge.readOccurrence())
					.toList();
			Assert.assertFalse("branch-join read must retain a local candidate", decisions.isEmpty());
			Assert.assertTrue("scheduling-only edge must not manufacture FED/FOUT authority",
				decisions.stream().noneMatch(decision -> decision.allowFEDFOUT()));
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

	private static String logRegScript(int port, Path features, Path labels) {
		return "N=50000;\n" +
			"D=2100;\n" +
			"X=federated(addresses=list(\"" + TestUtils.federatedAddress(port, features.toString()) + "\")," +
				"ranges=list(list(0,0),list(N,D)));\n" +
			"Y=federated(addresses=list(\"" + TestUtils.federatedAddress(port, labels.toString()) + "\")," +
				"ranges=list(list(0,0),list(N,1)));\n" +
			"Y=(Y<0)+1;\n" +
			"m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0," +
				"numclasses=2,numrows=N,numcols=D);\n" +
			"write(m,\"target/g014-dp-logreg-transient-forward.csv\",format=\"csv\");\n";
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
