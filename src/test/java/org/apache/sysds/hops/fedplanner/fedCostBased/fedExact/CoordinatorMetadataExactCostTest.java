/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalCostModel.Direction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact must not price a payload transfer that the CP runtime never performs. */
public class CoordinatorMetadataExactCostTest {
	@Test
	public void nrowBoundaryHasNoExactDownloadFactorOrLoweringAction() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile());
		var edge = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(candidate -> analysis.hop(candidate.consumer()).orElseThrow() instanceof UnaryOp unary
				&& unary.getOp() == OpOp1.NROW).findFirst().orElseThrow();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);

		Assert.assertFalse("Exact must not encode a FOUT payload download for nrow",
			surface.transferKeys().stream().filter(key -> key.direction() == Direction.DOWNLOAD)
				.flatMap(key -> key.endpoints().stream()).anyMatch(endpoint ->
					endpoint.producer() == edge.producer()
						&& endpoint.consumer() == edge.consumer()
						&& endpoint.inputPosition() == edge.inputPosition()));

		var selected = ExactPhysicalSelection.create(model, ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		var normalized = ExactPhysicalPlacementProjector.project(selected).normalizedResult();
		Assert.assertTrue("Exact lowering must agree with its metadata-only cost boundary",
			normalized.selectedLocalMaterializations().stream().noneMatch(value ->
				((org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey)value)
					.obligations().stream().anyMatch(obligation ->
						obligation.consumerOccurrence() == edge.consumer()
							&& obligation.inputPosition() == edge.inputPosition())));
	}

	private static DMLProgram compile() throws Exception {
		String script = "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
			+ "n=nrow(A);print(n);";
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
