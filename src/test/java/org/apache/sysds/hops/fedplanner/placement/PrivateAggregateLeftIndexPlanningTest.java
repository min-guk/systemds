/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Privacy authority contract for native single-range FULL left indexing. */
public class PrivateAggregateLeftIndexPlanningTest {
	private static final String RHS =
		"R=federated(addresses=list(\"localhost:1234/R\"),"
			+ "ranges=list(list(0,0),list(4,1)));\n";

	@Test
	public void protectedFullLhsCannotAcquireAnAbsentLocalCandidateInput() throws Exception {
		DMLProgram program = compile(
			"A=federated(addresses=list(\"localhost:1234/A\"),"
				+ "ranges=list(list(0,0),list(4,2)));\n"
				+ RHS + "O=A;O[1:4,1]=R;print(sum(O));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		HopOccurrenceProjection leftIndex = leftIndex(analysis);
		List<CandidateRuleFact> facts = analysis.candidateRuleFacts().orderedFactsForParent(leftIndex.key());

		Assert.assertFalse("protected FULL LHS must not expose an available local-payload row: " + facts,
			facts.stream().anyMatch(fact -> fact.key().orderedInputs().get(0)
				.equals(CandidateInputState.absentLocal())
				&& fact.status() == CandidateEvaluationStatus.AVAILABLE));
		Assert.assertEquals("the safe protected FULL/FULL row must remain available",
			CandidateEvaluationStatus.AVAILABLE,
			analysis.candidateRuleFacts().requireExact(leftIndex.key(), List.of(
				CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal())).status());
		Assert.assertTrue("protected left-index output must remain remote",
			analysis.graph().node(leftIndex.key()).orElseThrow().legalAlternatives().stream()
				.allMatch(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.FOUT));
	}

	@Test
	public void publicLocalLhsAndProtectedFullRhsRetainTheExactNativeCandidate() throws Exception {
		DMLProgram program = compile(
			"Z=matrix(1,rows=4,cols=2);\n" + RHS + "O=Z;O[1:4,1]=R;print(sum(O));\n");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);

		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		HopOccurrenceProjection leftIndex = leftIndex(analysis);
		CandidateRuleFact nativeFact = analysis.candidateRuleFacts().requireExact(leftIndex.key(),
			List.of(CandidateInputState.absentLocal(), CandidateInputState.present(FType.FULL),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal()));

		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, nativeFact.status());
		Assert.assertEquals(ExecType.FED, nativeFact.capability().nativeExec());
		Assert.assertEquals(FederatedOutput.FOUT, nativeFact.capability().nativeOutput());
		Assert.assertEquals(FType.FULL, nativeFact.capability().nativeFoutFType());
		Assert.assertTrue(nativeFact.allowedEmissionFacts().stream().anyMatch(emission -> {
			PlacementState state = emission.emissionState().placementState();
			return state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() == FType.FULL;
		}));
		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(leftIndex.key()));
	}

	private static HopOccurrenceProjection leftIndex(PlacementAnalysis analysis) {
		List<HopOccurrenceProjection> matches = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof LeftIndexingOp).toList();
		Assert.assertEquals("fixture requires one compiled left-index occurrence", 1, matches.size());
		return matches.get(0);
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
