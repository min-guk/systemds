/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalCostFingerprintPreflightTest {
	@Test
	public void productionSurfacePathPreflightsBeforeOrdinaryEvaluation() throws Exception {
		DMLProgram program = compileFederatedSum();
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var model = ExactPhysicalModel.build(analysis);
		Assert.assertFalse(model.variables().isEmpty());
		AtomicInteger evaluations = new AtomicInteger();
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalCostModel.physicalCostSurface(analysis, model,
				new ExactCategoricalSolver.Limits(100_000, 1),
				factor -> evaluations.incrementAndGet()));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void aggregateInputBudgetFailsBeforeFingerprintEvaluation() {
		var variable = new ExactCategoricalSolver.Variable("value", 3);
		AtomicInteger evaluations = new AtomicInteger();
		var first = lazy(variable, evaluations);
		var second = lazy(variable, evaluations);
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(
				List.of(variable), List.of(), List.of(first, second), List.of(first, second),
				new ExactCategoricalSolver.Limits(10, 5)));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void fingerprintAndSolverShareOneFrozenOrdinaryTable() {
		var variable = new ExactCategoricalSolver.Variable("value", 2);
		AtomicInteger generation = new AtomicInteger(0);
		AtomicInteger evaluations = new AtomicInteger();
		var ordinary = ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
			evaluations.incrementAndGet();
			return values[0] == generation.get() ? 0d : 1d;
		});
		List<ExactCategoricalSolver.Factor> frozen =
			ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(
				List.of(variable), List.of(), List.of(ordinary), List.of(ordinary),
				new ExactCategoricalSolver.Limits(10, 20));
		Assert.assertEquals(2, evaluations.get());
		generation.set(1);
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(
			List.of(variable), frozen, new ExactCategoricalSolver.Limits(10, 20));
		Assert.assertEquals(List.of(0), result.assignmentInVariableOrder());
		Assert.assertEquals(2, evaluations.get());
		Assert.assertEquals(Double.doubleToRawLongBits(0d),
			Double.doubleToRawLongBits(frozen.get(0).cost(new int[] {0})));
	}

	private static ExactCategoricalSolver.Factor lazy(
		ExactCategoricalSolver.Variable variable, AtomicInteger evaluations) {
		return ExactCategoricalSolver.Factor.lazy(List.of(variable), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
	}

	private static DMLProgram compileFederatedSum() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(8,4)));\n"
			+ "print(sum(X));\n";
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
