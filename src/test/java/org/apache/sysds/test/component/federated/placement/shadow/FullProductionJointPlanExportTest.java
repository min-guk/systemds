/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class FullProductionJointPlanExportTest {
	@Test
	public void halfOpenSlicesCoverExactlyTheSameRawOrdinalsAsWholeRange() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			protectedProgram("B-14"));
		var exporter = new FullProductionJointPlanExport(analysis);
		BigInteger end = exporter.rawCount();
		Assert.assertTrue(end.signum() > 0);
		Assert.assertTrue(end.compareTo(BigInteger.valueOf(10000)) < 0);
		List<FullProductionJointPlanExport.Audit> whole = new ArrayList<>();
		exporter.stream(BigInteger.ZERO, end, whole::add);
		List<FullProductionJointPlanExport.Audit> split = new ArrayList<>();
		BigInteger cut = end.divide(BigInteger.TWO);
		exporter.stream(BigInteger.ZERO, cut, split::add);
		exporter.stream(cut, end, split::add);
		Assert.assertEquals(whole, split);
		Assert.assertEquals(end.intValueExact(), whole.size());
		for(int i = 0; i < whole.size(); i++)
			Assert.assertEquals(BigInteger.valueOf(i), whole.get(i).ordinal());
		Assert.assertTrue(whole.stream().anyMatch(row ->
			row.verdict() == FullProductionJointPlanExport.Verdict.ACCEPTED));
	}

	@Test
	public void relocationDomainIsAuditedWithoutPolicySelection() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			protectedProgram("B-22"));
		Assert.assertFalse(analysis.graph().relocationActions().isEmpty());
		var exporter = new FullProductionJointPlanExport(analysis);
		List<FullProductionJointPlanExport.Audit> sample = new ArrayList<>();
		exporter.stream(BigInteger.ZERO, exporter.rawCount().min(BigInteger.valueOf(100)), sample::add);
		Assert.assertFalse(sample.isEmpty());
		Assert.assertTrue(sample.stream().allMatch(row -> row.verdict() != null && row.reason() != null));
	}

	@Test
	public void invalidRangeFailsBeforeAnyAuditEmission() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			protectedProgram("B-14"));
		var exporter = new FullProductionJointPlanExport(analysis);
		List<FullProductionJointPlanExport.Audit> rows = new ArrayList<>();
		try {
			exporter.stream(BigInteger.ZERO, exporter.rawCount().add(BigInteger.ONE), rows::add);
			Assert.fail("Out of range requests must fail");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(rows.isEmpty());
		}
	}

	@Test
	public void nonEmittedFunctionTemplateFactsDoNotCreatePhantomPlanChoices() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(protectedProgram("B-21"));
		var exporter = new FullProductionJointPlanExport(analysis);
		Assert.assertTrue(exporter.rawCount().signum() > 0);
		Assert.assertFalse(exporter.nonDecisionCandidateOwners().isEmpty());
		Assert.assertTrue(exporter.nonDecisionCandidateOwners().stream().allMatch(owner ->
			owner.role() == FullProductionJointPlanExport.NonDecisionOwnerRole.INERT_FUNCTION_TEMPLATE
				&& owner.nodeKind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind
					.FUNCTION_BODY_NON_EMITTED));
		List<FullProductionJointPlanExport.Audit> sample = new ArrayList<>();
		exporter.stream(BigInteger.ZERO, exporter.rawCount().min(BigInteger.valueOf(3)), sample::add);
		Assert.assertFalse(sample.isEmpty());
		Assert.assertTrue(sample.stream().allMatch(row ->
			row.verdict() != FullProductionJointPlanExport.Verdict.UNKNOWN));
	}

	@Test
	public void derivedFoutIsAReceiptCoupledActionRatherThanAnUnclassifiedGraphChoice() throws Exception {
		String script = "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
			+ "P=A/2;S=colSums(P);write(S,\"out\",format=\"binary\");";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		var exporter = new FullProductionJointPlanExport(new NeutralPlacementGraphBuilder().buildAnalysis(program));
		Assert.assertEquals(1, exporter.graph().derivedFoutMaterializationActions().size());
		List<FullProductionJointPlanExport.Audit> sample = new ArrayList<>();
		exporter.stream(BigInteger.ZERO, BigInteger.valueOf(5000), sample::add);
		Assert.assertTrue(sample.stream().noneMatch(row ->
			row.verdict() == FullProductionJointPlanExport.Verdict.UNKNOWN));
		Assert.assertTrue(sample.stream().anyMatch(row -> !row.derivedFoutActions().isEmpty()));
		Assert.assertTrue(sample.stream().flatMap(row -> row.derivedFoutActions().stream())
			.allMatch(action -> exporter.graph().derivedFoutMaterializationActions().stream()
				.anyMatch(graphAction -> graphAction.key() == action)));
	}

	private static DMLProgram protectedProgram(String id) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(id);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}
