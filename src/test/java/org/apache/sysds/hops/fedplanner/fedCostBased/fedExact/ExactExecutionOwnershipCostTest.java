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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.ExpectedSparseAssignmentEstimates;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Logical call boundaries must not duplicate the separately priced callee work. */
public class ExactExecutionOwnershipCostTest {

	@Test
	public void dmlCallPlaceholderHasNoUnaryExecutionOrPayloadScan() throws Exception {
		var analysis = analysis(functionScript());
		var model = ExactPhysicalModel.build(analysis);
		int calls = 0;
		for(var domain : model.domains()) {
			if(domain.node().kind() != NodeKind.FUNCTION_CALL)
				continue;
			calls++;
			Assert.assertTrue(analysis.isDmlFunctionCallBoundary(domain.node().key()));
			for(var factor : unary(analysis, domain))
				for(int value = 0; value < domain.alternatives().size(); value++)
					Assert.assertEquals("The non-executing call has no kernel, scan, or dispatch",
						0d, factor.cost(new int[] {value}), 0d);
		}
		Assert.assertEquals("Both call sites must survive compilation", 2, calls);
	}

	@Test
	public void sharedLocalCostAlsoRecognizesNonExecutingDmlBoundary() throws Exception {
		var analysis = analysis(functionScript());
		var calls = analysis.graph().decisionNodes().stream()
			.filter(node -> node.kind() == NodeKind.FUNCTION_CALL).toList();
		Assert.assertEquals(2, calls.size());
		for(var call : calls)
			Assert.assertEquals(0d, PlacementCostSemantics.analysisAwareUnitLocalCost(
				analysis, call.key()), 0d);
	}

	@Test
	public void calleeWorkRetainsItsExecutionFrequency() throws Exception {
		var analysis = analysis(functionScript());
		var model = ExactPhysicalModel.build(analysis);
		int checked = 0;
		for(var domain : model.domains()) {
			if(domain.node().kind() != NodeKind.OPERATION
				|| !domain.node().key().functionNamespace().contains("f"))
				continue;
			double unit = PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, domain.node().key());
			if(unit <= 0d)
				continue;
			double weight = analysis.executionFrequencyFacts().exactExecutionWeight(domain.node().key());
			var factors = unary(analysis, domain);
			for(int value = 0; value < domain.alternatives().size(); value++)
				if(domain.alternatives().get(value).state().execType() == ExecType.CP) {
					Assert.assertTrue(weight > 0d);
					Assert.assertEquals(weight * unit, factors.get(0).cost(new int[] {value}), 1e-9);
					checked++;
				}
		}
		Assert.assertTrue("Concrete callee kernels remain priced", checked > 0);
	}

	@Test
	public void multiReturnBuiltinRemainsAnExecutingOperation() throws Exception {
		var analysis = analysis("X=rand(rows=6,cols=3,seed=7);[Q,R]=qr(X);print(sum(Q)+sum(R));");
		var model = ExactPhysicalModel.build(analysis);
		int checked = 0;
		for(var domain : model.domains()) {
			if(domain.node().kind() != NodeKind.OPERATION
				|| !(analysis.hop(domain.node().key()).orElseThrow() instanceof FunctionOp function)
				|| function.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN)
				continue;
			Assert.assertEquals(NodeKind.OPERATION, domain.node().kind());
			Assert.assertTrue(PlacementCostSemantics.analysisAwareUnitLocalCost(analysis,
				domain.node().key()) > 0d);
			Assert.assertTrue(unary(analysis, domain).get(0).cost(new int[] {0}) > 0d);
			checked++;
		}
		Assert.assertEquals(1, checked);
	}

	private static List<ExactCategoricalSolver.Factor> unary(PlacementAnalysis analysis,
		ExactPhysicalModel.DecisionDomain domain) throws Exception {
		Method method = ExactPhysicalCostModel.class.getDeclaredMethod("addPhysicalUnaryFactor",
			PlacementAnalysis.class, ExpectedSparseAssignmentEstimates.class,
			ExactPhysicalModel.DecisionDomain.class, int.class,
			OccurrenceExecutionFrequencyFacts.class, List.class);
		method.setAccessible(true);
		List<ExactCategoricalSolver.Factor> result = new ArrayList<>();
		method.invoke(null, analysis, PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis),
			domain, ExactPhysicalCostModel.workerCount(analysis.graph()),
			analysis.executionFrequencyFacts(), result);
		return result;
	}

	private static String functionScript() {
		return """
			f = function(matrix[double] A) return (matrix[double] B) {
				B=A; i=1;
				while(i<2) { B=B+1; i=i+1; }
			}
			X=federated(addresses=list("localhost:1234/X","localhost:1235/X"),
				ranges=list(list(0,0),list(500,100),list(500,0),list(1000,100)));
			Y=f(X); Z=f(Y); print(sum(Z));
			""";
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
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}
}
