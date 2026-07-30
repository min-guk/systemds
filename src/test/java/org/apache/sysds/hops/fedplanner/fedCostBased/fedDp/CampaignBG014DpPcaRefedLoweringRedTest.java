/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Docker PCA regression: a planned refed must never be lowered over an already-federated source. */
public class CampaignBG014DpPcaRefedLoweringRedTest {
	@Test
	public void pcaDpDoesNotPublishRefedForAlreadyFederatedLop() throws Exception {
		DMLConfig oldGlobal = ConfigurationManager.getDMLConfig();
		DMLConfig config = new DMLConfig(oldGlobal);
		config.setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
		ConfigurationManager.setGlobalConfig(config);
		ConfigurationManager.setLocalConfig(config);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		PlacementEmissionTransaction.resetForTesting();
		try {
			DMLProgram program = compile(pcaScript());
			AtomicReference<PlannerInvocationReceipt> captured = new AtomicReference<>();
			DMLTranslator translator = new DMLTranslator(program);
			translator.constructLops(program, captured::set);
			Assert.assertTrue("PCA must use the DP planner", captured.get() instanceof DpInvocationReceipt);
			DpInvocationReceipt receipt = (DpInvocationReceipt) captured.get();

			StringBuilder conflicts = new StringBuilder();
			for(Map.Entry<Long, Map<Long, FederatedRefedRegistry.AnchorSpec>> scope :
				FederatedRefedRegistry.snapshotAll().scopes().entrySet()) {
				for(Map.Entry<Long, FederatedRefedRegistry.AnchorSpec> entry : scope.getValue().entrySet()) {
					for(HopOccurrenceProjection occurrence : receipt.analysis().occurrences()) {
						Hop hop = occurrence.hop();
						if(occurrence.scopeId() != scope.getKey() || hop.getHopID() != entry.getKey())
							continue;
						Lop lop = hop.getLops();
						if(lop != null && lop.getFederatedOutput().isForcedFederated())
							conflicts.append("scope=").append(scope.getKey())
								.append(" occurrence=").append(occurrence.key().normalizedSignature())
								.append(" hop=").append(hop.getHopID()).append(':').append(hop.getOpString())
								.append(" hopState=").append(hop.getForcedExecType()).append('/')
								.append(hop.getFederatedOutput())
								.append(" lopState=").append(lop.getExecType()).append('/')
								.append(lop.getFederatedOutput())
								.append(" selected=").append(receipt.normalizedResult().selectedEmissionStates()
									.get(occurrence.key()))
								.append(" anchors=").append(receipt.analysis().graph().node(occurrence.key())
									.orElseThrow().anchors())
								.append(" relocations=").append(receipt.normalizedResult().selectedRelocations().stream()
									.filter(action -> action.sourceValueVersion().equals(receipt.analysis().graph()
										.node(occurrence.key()).orElseThrow().valueVersion())).toList())
								.append(" spec=").append(entry.getValue().getAnchorKey())
								.append(" consumers=").append(entry.getValue().getConsumerHopIds()).append('\n');
					}
				}
			}
			Assert.assertEquals("A refed registry entry targets an already-federated lop:\n" + conflicts,
				"", conflicts.toString());
			// Exercise the same Dag.getJobs lowering boundary that failed in the Docker canary.
			translator.getRuntimeProgram(program, config);
		}
		finally {
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

	private static String pcaScript() throws Exception {
		Path data = Files.createTempFile("g014-pca-refed-", ".data");
		Path mtd = Path.of(data + ".mtd");
		Files.writeString(data, "");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\"," +
			"\"format\":\"text\",\"rows\":50000,\"cols\":2100,\"nnz\":100050000," +
			"\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		return "X_LOCAL=read(\"" + path + "\");\n" +
			"X=federated(local_matrix=X_LOCAL,addresses=list(\"localhost:1234\")," +
			"ranges=list(list(0,0),list(50000,2100)));\n" +
			"[Xout,Mout]=pca(X=X,K=10);\n" +
			"write(Mout,\"target/g014-pca-refed-lowering.csv\",format=\"csv\");\n";
	}
}
