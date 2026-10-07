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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Cost and plan identities must not depend on the identity-map order of rebuilt HOPs. */
public class ExactPhysicalCostFingerprintDeterminismTest {
	private static final String SCRIPT =
		"X=federated(addresses=list(\"localhost:23334/X1\",\"localhost:23335/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Y=federated(addresses=list(\"localhost:23336/Y1\",\"localhost:23337/Y2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=as.scalar(rand(rows=1,cols=1));"
			+ "if(p>0.5){A=X;B=X;}else{A=Y;B=Y;}"
			+ "C=A+B;D=A-B;print(sum(C)+sum(D));";

	@Test
	public void jointCostFactorsFollowModelDomainOrder() throws Exception {
		var analysis = analysis();
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<String> owners = surface.contributions().stream()
			.filter(contribution -> contribution.id().contains("|JOINT_VALUE_MAP_EXECUTION_INVARIANT|"))
			.map(contribution -> contribution.factor().scope().get(0).key()).toList();
		Assert.assertTrue("fixture must exercise at least four joint execution owners", owners.size() >= 4);
		Assert.assertEquals("joint costs must follow the canonical model domain order",
			model.domains().stream().map(domain -> domain.variable().key()).filter(owners::contains).toList(),
			owners);
	}

	@Test
	public void rebuiltAnalysesPreserveEveryCostTableAndProjectedPlanHash() throws Exception {
		Snapshot expected = snapshot();
		for(int repeat = 0; repeat < 3; repeat++) {
			Snapshot actual = snapshot();
			Assert.assertEquals("equivalent analysis", expected.analysis(), actual.analysis());
			Assert.assertEquals("every contribution scope/kind and raw cell bit", expected.costTables(), actual.costTables());
			Assert.assertEquals("cost factor order", expected.costOrder(), actual.costOrder());
			Assert.assertEquals("cost surface fingerprint", expected.cost(), actual.cost());
			Assert.assertEquals("selected assignment", expected.assignment(), actual.assignment());
			Assert.assertEquals("canonical objective bits", expected.objectiveBits(), actual.objectiveBits());
			Assert.assertEquals("projected plan hash", expected.plan(), actual.plan());
		}
	}

	/** Also runnable in independent JVMs without depending on a cached analysis or object identity. */
	public static void main(String[] args) throws Exception {
		System.out.println("COST_FINGERPRINT_SNAPSHOT|" + snapshot());
	}

	private record Snapshot(String analysis, String costTables, String costOrder, String cost,
		List<Integer> assignment, long objectiveBits, String plan) { }

	private static Snapshot snapshot() throws Exception {
		var analysis = analysis();
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<String> rows = new ArrayList<>();
		for(var contribution : surface.contributions()) {
			var factor = contribution.factor();
			int cells = 1;
			for(var variable : factor.scope())
				cells = Math.multiplyExact(cells, variable.domainSize());
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			int[] values = new int[factor.scope().size()];
			for(int cell = 0; cell < cells; cell++) {
				int remaining = cell;
				for(int axis = values.length - 1; axis >= 0; axis--) {
					values[axis] = remaining % factor.scope().get(axis).domainSize();
					remaining /= factor.scope().get(axis).domainSize();
				}
				digest.update((Long.toUnsignedString(Double.doubleToRawLongBits(factor.cost(values)), 16)
					+ ',').getBytes(StandardCharsets.UTF_8));
			}
			// Remove only the factor ordinal, retaining duplicate rows and all semantic contents.
			rows.add(contribution.id().substring(contribution.id().indexOf('|') + 1)
				+ '|' + factor.scope().stream().map(ExactCategoricalSolver.Variable::domainSize).toList()
				+ '|' + HexFormat.of().formatHex(digest.digest()));
		}
		String costOrder = digest(rows.toString());
		rows.sort(String::compareTo);
		var optimized = ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var selection = ExactPhysicalSelection.create(model, optimized);
		var plan = ExactPhysicalPlacementProjector.project(selection).normalizedResult();
		return new Snapshot(analysis.analysisFingerprint(), digest(rows.toString()), costOrder,
			surface.contributionFingerprint(), selection.assignmentInDecisionOrder(), selection.objectiveBits(),
			plan.normalizedPlanFingerprint());
	}

	private static String digest(String text) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(text.getBytes(StandardCharsets.UTF_8)));
	}

	private static PlacementAnalysis analysis() throws Exception {
		var program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			SCRIPT, new HashMap<>());
		var translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}
