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
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Regression probe for privacy legality of movement into coordinator-only sinks. */
public class SharedPrivacyMovementLegalityAuditTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void strictPrivateDirectPrintMustFailBeforeSelection() throws Exception {
		Path directory = Files.createTempDirectory("fed-planner-privacy-failure-audit-");
		System.setProperty(PlannerCandidateSpaceAudit.PROPERTY, Boolean.TRUE.toString());
		System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, directory.toString());
		try {
			DMLProgram program = compile(FEDERATED_SOURCE + "print(A);\n");
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE);

			DMLRuntimeException failure = Assert.assertThrows(DMLRuntimeException.class,
				() -> new NeutralPlacementGraphBuilder().buildAnalysis(program));
			Assert.assertTrue(failure.getMessage(),
				failure.getMessage().contains("No privacy-safe physical placement"));

			Path output;
			try(var files = Files.list(directory)) {
				output = files.filter(path -> path.getFileName().toString()
					.startsWith("candidate-space-")).findFirst().orElseThrow();
			}
			List<String> lines = Files.readAllLines(output);
			Assert.assertEquals("exactly the fail-closed occurrence must be recorded", 1, lines.size());
			JsonNode row = MAPPER.readTree(lines.get(0));
			Assert.assertEquals("fedplanner-candidate-space-privacy-failure-v1",
				row.path("schema").asText());
			Assert.assertEquals("PRIVATE", row.path("privacy").asText());
			Assert.assertEquals("NO_PRIVACY_SAFE_PHYSICAL_PLACEMENT",
				row.path("failureReason").asText());
			Assert.assertFalse(row.path("prePrivacyNodeStates").isEmpty());
			Assert.assertTrue(row.path("publishedNodeStates").isEmpty());
			Assert.assertTrue(row.path("publishedStatesP").isEmpty());
			Assert.assertFalse(row.path("prePrivacyRules").isEmpty());
			Assert.assertFalse(row.path("publishedRules").isEmpty());
			Assert.assertEquals("PRIVACY", row.path("publishedExclusions").elements().next()
				.path("reason").asText());
		}
		finally {
			System.clearProperty(PlannerCandidateSpaceAudit.PROPERTY);
			System.clearProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
			try(var files = Files.walk(directory)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					}
					catch(Exception ex) {
						throw new RuntimeException(ex);
					}
				});
			}
		}
	}

	@Test
	public void publicDirectPrintRetainsAFeasiblePlacement() throws Exception {
		DMLProgram program = compile(FEDERATED_SOURCE + "print(A);\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		Assert.assertNotNull(new NeutralPlacementGraphBuilder().buildAnalysis(program));
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}
