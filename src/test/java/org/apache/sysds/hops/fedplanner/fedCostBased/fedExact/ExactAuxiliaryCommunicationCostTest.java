/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Integration checks: auxiliary request batches reach the production objective exactly once. */
public class ExactAuxiliaryCommunicationCostTest {
	@Test public void reshapeChargesMetadataAndExecutionPerInvocation() throws Exception {
		assertDispatch("Y=matrix(X,rows=10,cols=10);print(sum(Y));",
			h -> h instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.RESHAPE, 2, 0);
	}

	@Test public void ordinaryTransposeStillChargesOneExecutionBatch() throws Exception {
		assertDispatch("Y=t(X);print(sum(Y));",
			h -> h instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS, 1, 0);
	}

	@Test public void alignedCovarianceChargesTwoMeanPayloadsAndTheUnownedBatch() throws Exception {
		// Base execute + generic result term already account for two batches. The
		// execution component must own exactly one additional batch and two means.
		assertDispatch("Y=cov(X,X);print(Y);",
			h -> h instanceof BinaryOp binary && binary.getOp() == OpOp2.COV, 2, 2);
	}

	private static void assertDispatch(String expression, Predicate<Hop> match,
		int executionBatches, int scalarPayloads) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			"X=rand(rows=100,cols=1,seed=7);" + expression, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		CompiledHopKey key = analysis.graph().nodes().stream().map(n -> n.key())
			.filter(k -> match.test(analysis.hop(k).orElseThrow())).findFirst().orElseThrow();
		Hop hop = analysis.hop(key).orElseThrow();
		List<FType> types = hop.getInput().stream()
			.map(h -> h.getDataType().isMatrix() ? FType.ROW : null).toList();
		int workers = 3;
		double frequency = 4;
		var sparse = PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		var layout = new FederatedExecutionLayout(FType.ROW, workers,
			types.stream().map(type -> new InputLayout(type, List.of(), false)).toList());
		double compute = PlacementCostSemantics.prepareExecutionCost(analysis, sparse, key).federatedCost(layout);
		Method project = ExactPhysicalCostModel.class.getDeclaredMethod("fedCostProjection",
			PlacementAnalysis.class, PlacementCostSemantics.ExpectedSparseAssignmentEstimates.class,
			CompiledHopKey.class, Hop.class, List.class, FType.class, int.class, double.class);
		project.setAccessible(true);
		Object projection = project.invoke(null, analysis, sparse, key, hop, types, FType.ROW, workers, frequency);
		Method unary = projection.getClass().getDeclaredMethod("fedUnaryCost");
		unary.setAccessible(true);
		Method payload = FederatedCostModel.class.getDeclaredMethod(
			"computeReplicatedWorkerResultDownloadCost", double.class, int.class);
		payload.setAccessible(true);
		double expected = frequency * (compute + executionBatches * FederatedCostModel.computeRequestResponseLatency()
			+ scalarPayloads * (double) payload.invoke(null, 8.0, workers));
		Assert.assertEquals("Actual production cost must follow the runtime batch count and invocation count",
			expected, (double) unary.invoke(projection), 1e-9);
	}
}
