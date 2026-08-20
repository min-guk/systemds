/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Exact physical receipt contract: one relocation demand has exactly one selected authority. */
public class CampaignBExactDuplicateObligationRedTest {
	@Test
	public void duplicateSelectedRelocationIsRejected() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(kmeans());
		ExactPhysicalSelection selection = forceOneRelocation(analysis);
		Assert.assertFalse("G014_EXACT_RELOCATION_RECEIPT_MISSING", selection.relocationChoices().isEmpty());

		var duplicated = new ArrayList<>(selection.relocationChoices());
		duplicated.add(selection.relocationChoices().get(0));
		IllegalArgumentException failure = Assert.assertThrows(
			"duplicate exact demand must be rejected", IllegalArgumentException.class,
			() -> RelocationSelections.resolveAndValidate(analysis,
				analysis.graph().relocationActions(), selection.selectedStates(),
				selection.candidateReceipts(), duplicated));
		Assert.assertTrue(failure.getMessage(), failure.getMessage()
			.startsWith("Relocation demand has multiple selected alternatives"));
	}

	private static ExactPhysicalSelection forceOneRelocation(PlacementAnalysis analysis) {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		for(var domain : model.domains())
			for(int value = 0; value < domain.alternatives().size(); value++) {
				if(domain.alternatives().get(value).inputAuthorities().stream().noneMatch(authority ->
					authority.kind() == InputAuthorityKind.RELOCATION))
					continue;
				int required = value;
				List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
				factors.addAll(surface.factors());
				factors.add(ExactCategoricalSolver.Factor.lazy(List.of(domain.variable()),
					values -> values[0] == required ? 0.0 : Double.POSITIVE_INFINITY));
				try {
					var solved = ExactCategoricalSolver.solve(model.variables(), factors,
						ExactPhysicalOptimizer.PRODUCTION_LIMITS);
					long objective = surface.evaluateCanonical(solved.assignmentInVariableOrder());
					ExactPhysicalSelection selection = ExactPhysicalSelection.create(model,
						new ExactPhysicalOptimizer.Result(solved, objective,
							surface.contributionFingerprint()));
					if(!selection.relocationChoices().isEmpty())
						return selection;
				}
				catch(IllegalArgumentException infeasible) {
					if(!infeasible.getMessage().startsWith("EXACT_VE_NO_FEASIBLE_ASSIGNMENT"))
						throw infeasible;
				}
			}
		throw new AssertionError("G014_EXACT_RELOCATION_ALTERNATIVE_MISSING");
	}

	private static DMLProgram kmeans() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"write(Y,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}
}
