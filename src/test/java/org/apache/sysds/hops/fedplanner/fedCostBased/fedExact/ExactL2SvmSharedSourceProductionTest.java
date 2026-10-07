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
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactL2SvmSharedSourceProductionTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(10_000_000, 60_000_000);

	@Test
	public void productionSharedSourceEncodingSolvesL2SvmWithinUnchangedLimits()
		throws Exception {
		DMLProgram program = compile(dataPrelude()
			+ "B=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-22,maxIterations=30);\n"
			+ "write(B,\"out\",format=\"csv\");\n");
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var encoding = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(), LIMITS);
		Assert.assertTrue(encoding.statistics().toString(), encoding.statistics().transformed());

		var optimized = ExactPhysicalOptimizer.optimize(model, surface, LIMITS);
		Assert.assertTrue(Double.isFinite(optimized.solverResult().objective()));
		Assert.assertEquals(optimized.canonicalObjectiveBits(),
			Double.doubleToRawLongBits(optimized.solverResult().objective()));
		Assert.assertEquals(model.variables().size(),
			optimized.solverResult().assignmentInVariableOrder().size());
		var solver = optimized.solverResult().statistics();
		Assert.assertTrue("actual stored factor must fit the unchanged cap",
			solver.maximumFactorCells() <= LIMITS.maximumFactorCells());
		Assert.assertTrue("inputs and stored messages must fit the unchanged cumulative cap",
			solver.materializedFactorCells() <= LIMITS.maximumMaterializedCells());
		System.out.println("L2SVM_SHARED_SOURCE_EVIDENCE|decisions=" + model.variables().size()
			+ "|rows=" + model.domains().stream()
				.mapToInt(domain -> domain.alternatives().size()).sum()
			+ "|headers=" + encoding.statistics().headerValues()
			+ "|references=" + encoding.statistics().sourceVariables()
			+ "|encodedFactors=" + encoding.factors().size()
			+ "|factorCells=" + encoding.statistics().factorCells()
			+ "|rawProfileCells=" + encoding.statistics().rawProfileCells()
			+ "|objectiveBits=" + Long.toUnsignedString(optimized.canonicalObjectiveBits())
			+ "|maximumFactorCells=" + solver.maximumFactorCells()
			+ "|materializedFactorCells=" + solver.materializedFactorCells());
	}

	private static String dataPrelude() {
		return "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n"
			+ "Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));\n";
	}

	private static DMLProgram compile(String script) throws Exception {
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
