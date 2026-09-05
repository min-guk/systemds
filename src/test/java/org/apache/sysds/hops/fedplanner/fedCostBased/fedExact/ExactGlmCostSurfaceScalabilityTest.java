/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compile-only regression for the built-in GLM exact physical cost surface. */
public class ExactGlmCostSurfaceScalabilityTest {
	@Test
	public void builtinGlmExactCostSurfaceStaysWithinProductionLimits() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(glmProgram());
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Assert.assertTrue("fixture must retain the full built-in GLM planning graph",
			model.variables().size() >= 300);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertTrue("GLM fixture must exercise exact-only auxiliary cost factors",
			surface.exactSolverVariables().size() > model.variables().size());
		var allFactors = new ArrayList<>(model.hardFactors());
		allFactors.addAll(surface.exactSolverFactors());
		for(var factor : allFactors) {
			long cells = factor.scope().stream().mapToLong(
				ExactCategoricalSolver.Variable::domainSize).reduce(1L, (left, right) ->
					left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right);
			Assert.assertTrue("oversized input factor|cells=" + cells + "|scope="
				+ factor.scope().stream().map(variable -> variable.key() + ':'
					+ variable.domainSize()).toList(),
				cells <= ExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumFactorCells());
		}
		ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(model,
			surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		ExactCategoricalSolver.Statistics statistics = optimized.solverResult().statistics();
		Assert.assertTrue(statistics.maximumFactorCells()
			<= ExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumFactorCells());
		Assert.assertTrue(statistics.materializedFactorCells()
			<= ExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumMaterializedCells());
		Assert.assertTrue("GLM reduced maximum factor unexpectedly regressed: " + statistics,
			statistics.maximumFactorCells() <= 500_000L);
		Assert.assertTrue("GLM reduced materialization unexpectedly regressed: " + statistics,
			statistics.materializedFactorCells() <= 6_000_000L);
		Assert.assertEquals(optimized.canonicalObjectiveBits(), Double.doubleToRawLongBits(
			optimized.solverResult().objective()));
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(model, optimized);
		var projected = ExactPhysicalPlacementProjector.project(selection);
		Assert.assertEquals(model.variables().size(),
			optimized.solverResult().assignmentInVariableOrder().size());
		Assert.assertEquals(selection.candidateReceipts(),
			projected.normalizedResult().selectedCandidateSelections());
		Assert.assertEquals(selection.relocationChoices(),
			projected.normalizedResult().selectedRelocationChoices());
	}

	private static DMLProgram glmProgram() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\"),"
			+ "ranges=list(list(0,0),list(8,4)));\n"
			+ "Y=federated(addresses=list(\"localhost:1234/Y1\"),"
			+ "ranges=list(list(0,0),list(8,1)));\n"
			+ "Y=(Y>mean(Y))*1;\n"
			+ "beta=glm(X=X,Y=Y,dfam=2,vpow=0.0,link=2,lpow=1.0,yneg=0.0,"
			+ "icpt=0,disp=0.0,reg=0.0,tol=1e-6,moi=2,mii=2,verbose=FALSE);\n"
			+ "write(beta,\"/tmp/g014-glm-exact-cost\",format=\"csv\");\n";
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
