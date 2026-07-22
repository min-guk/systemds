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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** RED for coherent completion of one disconnected component with two sink paths. */
public class CampaignBG014DisconnectedComponentCompletionRedTest {
	@Test
	public void sharedProducerAcrossTwoSinkPathsHasOneExactState() throws Exception {
		assertCoherent(invoke(false));
	}

	@Test
	public void sourceStatementInsertionOrderDoesNotChangeCoherentState() throws Exception {
		DpInvocationReceipt first = invoke(false);
		DpInvocationReceipt permuted = invoke(true);
		Assert.assertEquals(sharedProducerTuple(first), sharedProducerTuple(permuted));
		Assert.assertEquals(first.counters().fallbackCount(), permuted.counters().fallbackCount());
		Assert.assertEquals(first.counters().repairCount(), permuted.counters().repairCount());
		Assert.assertEquals(first.counters().reenumerationCount(), permuted.counters().reenumerationCount());
	}

	private static void assertCoherent(DpInvocationReceipt receipt) {
		PlacementEmissionState selected = sharedProducerState(receipt);
		Assert.assertEquals(ExecType.CP, selected.placementState().execType());
		Assert.assertEquals(FederatedOutput.LOUT, selected.placementState().output());
		Assert.assertNull(selected.placementState().fType());
		Assert.assertFalse(selected.derivedFedFout());
		Assert.assertEquals(0, receipt.counters().fallbackCount());
		Assert.assertEquals(0, receipt.counters().repairCount());
		Assert.assertEquals(0, receipt.counters().reenumerationCount());
	}

	private static List<Object> sharedProducerTuple(DpInvocationReceipt receipt) {
		PlacementEmissionState selected = sharedProducerState(receipt);
		return List.of(selected.placementState().execType(), selected.placementState().output(),
			selected.placementState().fType() == null ? "null" : selected.placementState().fType(),
			selected.derivedFedFout());
	}

	private static PlacementEmissionState sharedProducerState(DpInvocationReceipt receipt) {
		PlacementAnalysis analysis = receipt.analysis();
		List<CompiledHopKey> sources = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.map(LogicalTransientInputFact::sourceWrite).distinct().toList();
		Assert.assertEquals("fixture must expose one exact shared producer", 1, sources.size());
		CompiledHopKey source = sources.get(0);
		Assert.assertEquals("shared producer must have one concrete occurrence", 1,
			analysis.occurrences().stream().filter(value -> value.key() == source).count());
		return receipt.normalizedResult().selectedEmissionStates().get(source);
	}

	private static DpInvocationReceipt invoke(boolean consumerFirst) throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		DMLProgram program = compile(consumerFirst);
		AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
		String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
		try {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			new DMLTranslator(program).constructLops(program, receipt::set);
		}
		finally {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old);
		}
		Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
		return (DpInvocationReceipt) receipt.get();
	}

	private static DMLProgram compile(boolean consumerFirst) throws Exception {
		Path data = Files.createTempFile("g014-component-", ".data");
		Path mtd = Path.of(data + ".mtd");
		Files.writeString(data, "");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"text\",\"rows\":4,\"cols\":2,\"nnz\":0,"
			+ "\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		String script = String.join("\n",
			"g=function(matrix[double] I) return (matrix[double] O){O=rowSums(I);}",
			"P_LOCAL=read(\"" + path + "\");",
			"P=federated(local_matrix=P_LOCAL,addresses=list(\"localhost:1234\",\"localhost:1235\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"S=sum(P);",
			"Q=g(P);",
			consumerFirst ? "print(sum(Q)+S);" : "print(S+sum(Q));", "");
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}
