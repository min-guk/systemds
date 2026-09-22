/* Licensed to the Apache Software Foundation (ASF) under one
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
 * under the License. */

package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.HashMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Checks that differential capture includes protected production facts and support. */
public class ProductionPlanSpaceCaptureTest {
	@Test
	public void capturesEveryPublishedReceiptAndRejectedRuleWithoutFilteringSupport() throws Exception {
		String script = "A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "B=A*2; print(sum(B));";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);

		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var captured = ProductionPlanSpaceCapture.capture(analysis);
		Assert.assertEquals("every rule fact must have a captured disposition",
			analysis.candidateRuleFacts().orderedFacts().size(),
			captured.availableRuleCount() + captured.rejectedRules().size());
		Assert.assertFalse("published receipts must be exposed", captured.rows().isEmpty());
		Assert.assertEquals("capture must be deterministic and read-only", captured,
			ProductionPlanSpaceCapture.capture(analysis));
		Assert.assertTrue("every captured row retains its support and exact realization",
			captured.rows().stream().allMatch(row ->
				!row.supportClause().isEmpty() && !row.realization().isEmpty()
					&& !row.diagnosticSignature().isEmpty()));
	}
}
