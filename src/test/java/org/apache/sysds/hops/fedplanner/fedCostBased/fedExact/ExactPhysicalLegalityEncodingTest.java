/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalLegalityEncodingTest {
	private static final ExactCategoricalSolver.Limits CAMPAIGN_LIMITS =
		new ExactCategoricalSolver.Limits(10_000_000, 60_000_000);

	@Test
	public void encodedLegalityMatchesRawCanonicalFeasibilityOnSmallFixture() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis(String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(10,8),list(10,0),list(20,8)));",
			"Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
				+ "ranges=list(list(0,0),list(10,1),list(10,0),list(20,1)));",
			"Z=t(X)%*%Y;",
			"write(Z,\"out\",format=\"csv\");") + "\n"));
		ExactCategoricalSolver.Limits generous =
			new ExactCategoricalSolver.Limits(10_000_000, 60_000_000);
		ExactCategoricalSolver.Result raw = ExactCategoricalSolver.solve(
			model.variables(), model.hardFactors(), generous);
		ExactCategoricalSolver.Result encoded = model.solveLegalityOnly(generous);

		Assert.assertEquals(0.0, raw.objective(), 0.0);
		Assert.assertEquals(0.0, encoded.objective(), 0.0);
		Assert.assertEquals(model.variables().size(), encoded.assignmentInVariableOrder().size());
		Assert.assertTrue(Double.isFinite(RegionalSearchProblem.evaluateFactors(model.variables(),
			model.hardFactors(), encoded.assignmentInVariableOrder())));
	}

	@Test
	public void l2svmLegalityUsesEncodedFactorsAndProjectsAuxiliaries() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis(dataPrelude()
			+ "B=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-22,maxIterations=30);\n"
			+ "write(B,\"out\",format=\"csv\");\n"));
		long largestCanonical = maximumCells(model.hardFactors());
		long largestEncoded = maximumCells(model.exactSolverHardFactors());
		Assert.assertTrue("fixture must exceed the campaign cap before hard-factor encoding",
			largestCanonical > CAMPAIGN_LIMITS.maximumFactorCells());
		Assert.assertTrue("every encoded input factor must fit the unchanged campaign cap",
			largestEncoded <= CAMPAIGN_LIMITS.maximumFactorCells());
		Assert.assertFalse("fixture must exercise auxiliary hard-factor observations",
			model.exactSolverAuxiliaryVariables().isEmpty());

		ExactCategoricalSolver.Statistics statistics = model.analyze(CAMPAIGN_LIMITS);
		ExactCategoricalSolver.Result solved = model.solveLegalityOnly(CAMPAIGN_LIMITS);
		Assert.assertTrue(statistics.maximumFactorCells() <= CAMPAIGN_LIMITS.maximumFactorCells());
		Assert.assertEquals(model.variables().size(), solved.assignmentInVariableOrder().size());
		Assert.assertTrue(Double.isFinite(RegionalSearchProblem.evaluateFactors(model.variables(),
			model.hardFactors(), solved.assignmentInVariableOrder())));
	}

	private static long maximumCells(List<ExactCategoricalSolver.Factor> factors) {
		long maximum = 0L;
		for(ExactCategoricalSolver.Factor factor : factors) {
			long cells = 1L;
			for(ExactCategoricalSolver.Variable variable : factor.scope())
				cells = Math.multiplyExact(cells, variable.domainSize());
			maximum = Math.max(maximum, cells);
		}
		return maximum;
	}

	private static String dataPrelude() {
		return "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n"
			+ "Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));\n";
	}

	private static PlacementAnalysis analysis(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}
