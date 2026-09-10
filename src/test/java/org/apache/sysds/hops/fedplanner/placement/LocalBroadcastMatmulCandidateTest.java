/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Graph regression for the native local-by-broadcast matrix-multiply candidate. */
public class LocalBroadcastMatmulCandidateTest {
	private static final String SCRIPT =
		"L=matrix(1,rows=4,cols=4);\n"
			+ "R=federated(addresses=list(\"localhost:1234/R1\",\"localhost:1235/R2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(0,0),list(4,2)));\n"
			+ "Y=L%*%R;\nprint(sum(Y));\n";
	private static final String BOUNDARY_SCRIPT =
		"L=matrix(1,rows=4,cols=4);\n"
			+ "R=federated(addresses=list(\"localhost:1234/R1\",\"localhost:1235/R2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(0,0),list(4,2)));\n"
			+ "p=sum(L)>0;\nif(p){B=R;}else{B=R;}\n"
			+ "Y=L%*%B;\nprint(sum(Y));\n";

	@Test
	public void localByPresentBroadcastPublishesNativeFedFoutBroadcastCandidate() throws Exception {
		DMLProgram program = compile(SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var mm = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggBinaryOp)
			.findFirst().orElseThrow(AssertionError::new);
		var source = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED)
			.findFirst().orElseThrow(AssertionError::new);

		CandidateRuleFact fact = analysis.candidateRuleFacts().requireExact(mm.key(), List.of(
			CandidateInputState.absentLocal(), CandidateInputState.present(FType.BROADCAST)));
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, fact.status());
		Assert.assertEquals(ExecType.FED, fact.capability().nativeExec());
		Assert.assertEquals(FederatedOutput.FOUT, fact.capability().nativeOutput());
		Assert.assertEquals(FType.BROADCAST, fact.capability().nativeFoutFType());
		Assert.assertEquals(ReasonCode.OK, fact.capability().reasonCode());
		Assert.assertTrue(fact.profile().available());
		Assert.assertEquals(List.of(FType.BROADCAST), fact.profile().producerOutputs());
		Assert.assertTrue(fact.allowedEmissionFacts().stream().anyMatch(emission ->
			emission.executionFType() == FType.BROADCAST
				&& emission.emissionState().placementState().equals(new PlacementState(
					ExecType.FED, FederatedOutput.FOUT, FType.BROADCAST, false))));

		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(source.key()));
		Assert.assertEquals("shared aggregate-release privacy provenance must remain authoritative",
			Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, analysis.requirePrivacy(mm.key()));
		Assert.assertSame(source.key(), analysis.privacyFactAuthority().requireExact(source.key()).occurrence());
		Assert.assertSame(mm.key(), analysis.privacyFactAuthority().requireExact(mm.key()).occurrence());
	}

	@Test
	public void broadcastSurvivesExactTransientBoundaryIntoNativeMatmul() throws Exception {
		DMLProgram program = compile(BOUNDARY_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		PlacementState broadcast = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.BROADCAST, false);
		List<PlacementAnalysis.HopOccurrenceProjection> writes = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTWRITE && "B".equals(data.getName()))
			.toList();
		Assert.assertEquals(2, writes.size());
		for(var write : writes) {
			CandidateRuleFact fact = analysis.candidateRuleFacts().requireExact(write.key(),
				List.of(CandidateInputState.present(FType.BROADCAST)));
			Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, fact.status());
			Assert.assertTrue(analysis.graph().node(write.key()).orElseThrow().legalAlternatives()
				.contains(broadcast));
		}

		var read = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTREAD && "B".equals(data.getName()))
			.findFirst().orElseThrow(AssertionError::new);
		Assert.assertTrue(analysis.graph().node(read.key()).orElseThrow().legalAlternatives()
			.contains(broadcast));
		Assert.assertTrue(analysis.logicalTransientInputsInCanonicalOrder().stream().anyMatch(input ->
			input.targetRead().equals(read.key()) && input.federatedSourceState().equals(broadcast)));

		var mm = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof AggBinaryOp)
			.findFirst().orElseThrow(AssertionError::new);
		CandidateRuleFact mmFact = analysis.candidateRuleFacts().requireExact(mm.key(), List.of(
			CandidateInputState.absentLocal(), CandidateInputState.present(FType.BROADCAST)));
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, mmFact.status());
		Assert.assertTrue(mmFact.allowedEmissionFacts().stream().anyMatch(emission ->
			emission.executionFType() == FType.BROADCAST
				&& emission.emissionState().placementState().equals(broadcast)));
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
