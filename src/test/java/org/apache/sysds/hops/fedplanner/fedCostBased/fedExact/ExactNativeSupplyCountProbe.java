/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;

/** Standalone before/after count probe; reflection keeps the frozen baseline runnable. */
public final class ExactNativeSupplyCountProbe {
	private ExactNativeSupplyCountProbe() { }

	public static void main(String[] args) throws Exception {
		ExactPhysicalModel model = model();
		var surface = ExactPhysicalCostModel.physicalCostSurface(model.analysis(), model,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS, ignored -> { });
		int authorityRows = model.domains().stream()
			.mapToInt(domain -> domain.alternatives().size()).sum();
		int nativeCandidates = -1;
		int supplyCandidates = -1;
		int relationWitnesses = -1;
		try {
			Method representationMethod = ExactPhysicalModel.class
				.getDeclaredMethod("nativeSupplyRepresentation");
			representationMethod.setAccessible(true);
			Object representation = representationMethod.invoke(model);
			Method statisticsMethod = representation.getClass().getDeclaredMethod("statistics");
			statisticsMethod.setAccessible(true);
			Object statistics = statisticsMethod.invoke(representation);
			nativeCandidates = intAccessor(statistics, "nativeCandidates");
			supplyCandidates = intAccessor(statistics, "supplyCandidates");
			relationWitnesses = intAccessor(statistics, "relationWitnesses");
		}
		catch(NoSuchMethodException ignored) {
			// Frozen origin/main baseline predates the separated representation.
		}
		System.out.println("{\"authorityRows\":" + authorityRows
			+ ",\"nativeCandidates\":" + nativeCandidates
			+ ",\"supplyCandidates\":" + supplyCandidates
			+ ",\"relationWitnesses\":" + relationWitnesses
			+ ",\"decisionVariables\":" + model.variables().size()
			+ ",\"canonicalHardFactors\":" + model.hardFactors().size()
			+ ",\"canonicalHardCells\":" + cells(model.hardFactors())
			+ ",\"canonicalCostFactors\":" + surface.contributions().size()
			+ ",\"canonicalCostCells\":" + cells(surface.factors())
			+ ",\"encodedVariables\":" + surface.exactSolverVariables().size()
			+ ",\"encodedFactors\":" + surface.exactSolverFactors().size()
			+ ",\"encodedCells\":" + cells(surface.exactSolverFactors()) + "}");
	}

	private static int intAccessor(Object owner, String name) throws Exception {
		Method method = owner.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return (Integer) method.invoke(owner);
	}

	private static long cells(List<ExactCategoricalSolver.Factor> factors) {
		long total = 0;
		for(var factor : factors) {
			long cells = 1;
			for(var variable : factor.scope())
				cells = cells > Long.MAX_VALUE / variable.domainSize()
					? Long.MAX_VALUE : cells * variable.domainSize();
			total = total > Long.MAX_VALUE - cells ? Long.MAX_VALUE : total + cells;
		}
		return total;
	}

	private static ExactPhysicalModel model() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));"
			+ "p=matrix(1,rows=3,cols=1);pred=X%*%p;print(sum(pred));";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		return ExactPhysicalModel.build(new NeutralPlacementGraphBuilder().buildAnalysis(program));
	}
}
