/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlacementPlannerAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Runtime-alignment contract for coordinator metadata reads of federated values. */
public class CoordinatorMetadataInputAccessTest {
	@Test
	public void nrowUsesFederationMapMetadataWithoutPayloadMaterialization() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(
			fed() + "n=nrow(A);print(n);"));
		CompiledInputEdgeFact edge = metadataEdge(analysis, OpOp1.NROW);

		Assert.assertTrue("nrow of a matrix/frame is an exact federation-map metadata read",
			analysis.isCoordinatorMetadataOnlyInput(edge));
		var normalized = PlacementPlannerAdapter.normalize(analysis,
			new FedAllPlacementAdapter().select(analysis));
		PlacementState producer = normalized.selectedStates().get(edge.producer());
		Assert.assertEquals(ExecType.FED, producer.execType());
		Assert.assertEquals(FederatedOutput.FOUT, producer.output());
		@SuppressWarnings("unchecked")
		var localMaterializations = (java.util.List<LocalMaterializationActionKey>)
			normalized.selectedLocalMaterializations();
		Assert.assertTrue("a coordinator nrow must not create a FOUT payload prefetch",
			localMaterializations.stream().noneMatch(action ->
				action.obligations().stream().anyMatch(obligation ->
					obligation.consumerOccurrence() == edge.consumer()
						&& obligation.inputPosition() == edge.inputPosition())));
	}

	@Test
	public void payloadUnaryIsNotMisclassifiedAsMetadataAccess() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(
			fed() + "B=abs(A);write(B,\"/tmp/g014-metadata-access\",format=\"binary\");"));
		CompiledInputEdgeFact edge = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(candidate -> analysis.hop(candidate.consumer()).orElseThrow() instanceof UnaryOp unary
				&& unary.getOp() == OpOp1.ABS).findFirst().orElseThrow();

		Assert.assertFalse("ordinary unary operations still require the matrix/frame payload",
			analysis.isCoordinatorMetadataOnlyInput(edge));
	}

	private static CompiledInputEdgeFact metadataEdge(PlacementAnalysis analysis, OpOp1 op) {
		return analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> analysis.hop(edge.consumer()).orElseThrow() instanceof UnaryOp unary
				&& unary.getOp() == op).findFirst().orElseThrow();
	}

	private static String fed() {
		return "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		return program;
	}
}
