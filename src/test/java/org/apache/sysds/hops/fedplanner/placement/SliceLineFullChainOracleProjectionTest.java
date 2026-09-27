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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Test;

/** SliceLine's existing CTABLE aggregate-release contract under production privacy. */
public class SliceLineFullChainOracleProjectionTest {
	private static final String WORKER = "localhost:1234/X";

	@Test
	public void releasedCtableRetainsRuntimeSupportedForcedLocalResult() throws Exception {
		PlacementAnalysis analysis = analyze(sliceLineCtableScript(), Privacy.PRIVATE_AGGREGATE);

		PlacementAnalysis.HopOccurrenceProjection x2 = ctableOccurrence(analysis);
		assertFalse("six-input SliceLine CTABLE must stay on ordinary processRequest",
			((TernaryOp) x2.hop()).isSequenceRewriteApplicable(true));
		assertEquals("CTABLE is an aggregate release, not raw-data declassification",
			Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, analysis.requirePrivacy(x2.key()));
		CandidateRuleFact fact = analysis.candidateRuleFacts().requireExact(x2.key(), ctableInputs());
		assertEquals(CandidateEvaluationStatus.AVAILABLE, fact.status());
		assertTrue("disjoint CTABLE must retain its runtime-supported federated output",
			hasEmission(fact, ExecType.FED, FederatedOutput.FOUT, FType.ROW));
		assertTrue("released CTABLE must also retain runtime forced-local aggregation",
			hasEmission(fact, ExecType.FED, FederatedOutput.LOUT, FType.ROW));
		List<CandidateInputState> allLocal = List.of(CandidateInputState.absentLocal(),
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
			CandidateInputState.absentLocal());
		assertTrue("missing all-local domain row cannot manufacture a forced-local FED sibling",
			analysis.candidateRuleFacts().orderedFactsForParent(x2.key()).stream()
				.noneMatch(candidate -> candidate.key().orderedInputs().equals(allLocal)
					&& hasExecOutput(candidate, ExecType.FED, FederatedOutput.LOUT)));
		assertEquals("CTABLE has exactly one physical input at position 1", 1,
			fact.key().orderedInputs().stream().filter(CandidateInputState::present).count());
		assertEquals(CandidateInputState.present(FType.ROW), fact.key().orderedInputs().get(1));
		var cixEdge = analysis.compiledInputEdge(x2.key(), 1).orElseThrow();
		assertSame("compiled position 1 must target the selected cix occurrence",
			x2.hop().getInput().get(1), analysis.hop(cixEdge.producer()).orElseThrow());
		assertEquals("compiled input edge must retain position 1", 1, cixEdge.inputPosition());

		var loutRealizations = fact.allowedEmissionFacts().stream()
			.filter(emission -> isEmission(emission.emissionState().placementState(),
				ExecType.FED, FederatedOutput.LOUT, FType.ROW))
			.flatMap(emission -> emission.realizations().stream()).toList();
		assertFalse("forced-local CTABLE must publish one local realization", loutRealizations.isEmpty());
		assertTrue("forced-local CTABLE cannot claim native output-map continuity",
			loutRealizations.stream().allMatch(realization ->
				realization.key().layoutKind() == PlacementLayoutKind.LOCAL));
		var loutClauses = loutRealizations.stream()
			.flatMap(realization -> realization.supportClauses().stream()).toList();
		assertTrue("local CTABLE clauses cannot carry a native worker-pool witness",
			loutClauses.stream().allMatch(clause -> clause.nativeWorkerPoolWitness() == null));
		assertTrue("local execution retains its legal empty support clause",
			loutClauses.stream().anyMatch(clause -> clause.inputBindings().isEmpty()));
		assertTrue("exact compiled input projection retains a bound support clause",
			loutClauses.stream().anyMatch(clause -> !clause.inputBindings().isEmpty()));
		assertTrue("every bound local clause uses the exact compiled cix authority",
			loutClauses.stream().filter(clause -> !clause.inputBindings().isEmpty())
				.flatMap(clause -> clause.inputBindings().stream())
				.allMatch(binding -> exactCtableInputAuthority(analysis, x2, cixEdge,
					fact.allowedEmissionFacts().stream()
						.filter(emission -> isEmission(emission.emissionState().placementState(),
							ExecType.FED, FederatedOutput.LOUT, FType.ROW))
						.findFirst().orElseThrow().emissionState().placementState(), binding)));
		assertTrue("fixture must retain its exact relocation-backed local alternative",
			loutClauses.stream().flatMap(clause -> clause.inputBindings().stream())
				.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.RELOCATION));


		PlacementAnalysis.HopOccurrenceProjection protectedCix = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == x2.hop().getInput().get(1))
			.findFirst().orElseThrow();
		assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(protectedCix.key()));
		var protectedStates = analysis.candidateRuleFacts().orderedFactsForParent(protectedCix.key()).stream()
			.flatMap(candidate -> candidate.allowedEmissionFacts().stream())
			.map(emission -> emission.emissionState().placementState()).toList();
		assertFalse("raw PA predecessor must retain an origin-resident candidate", protectedStates.isEmpty());
		assertTrue("every raw PA predecessor candidate must remain FED/FOUT at the origin",
			protectedStates.stream().allMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT));

		PlacementAnalysis.HopOccurrenceProjection multiply = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof BinaryOp binary
				&& binary.getOp() == OpOp2.MULT && binary.getInput().get(0) == x2.hop())
			.findFirst().orElseThrow();
		CandidateRuleFact downstream = analysis.candidateRuleFacts().requireExact(multiply.key(),
			List.of(CandidateInputState.absentLocal(), CandidateInputState.present(FType.FULL)));
		assertEquals(CandidateEvaluationStatus.AVAILABLE, downstream.status());
		assertTrue("local released X2 plus federated e must use the existing FED/FOUT FULL rule: "
			+ downstream, hasEmission(downstream, ExecType.FED, FederatedOutput.FOUT, FType.FULL));
	}

	private static boolean exactCtableInputAuthority(PlacementAnalysis analysis,
		PlacementAnalysis.HopOccurrenceProjection ctable,
		PlacementAnalysis.CompiledInputEdgeFact cixEdge, PlacementState loutState,
		CandidateRealizationInputBinding binding) {
		if(binding.inputPosition() != 1
			|| analysis.hop(binding.source().rule().parentOccurrence()).orElse(null)
				!= ctable.hop().getInput().get(1)
			|| binding.kind() != CandidateInputBindingKind.DIRECT
				&& binding.kind() != CandidateInputBindingKind.RELOCATION)
			return false;
		if(binding.kind() == CandidateInputBindingKind.DIRECT)
			return binding.relocationAction() == null;
		var cixNode = analysis.graph().node(cixEdge.producer()).orElseThrow();
		return analysis.graph().relocationActions().stream()
			.filter(action -> action.key().equals(binding.relocationAction()))
			.anyMatch(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == ctable.key() && obligation.inputPosition() == 1
					&& obligation.sourceValueVersion().equals(cixNode.valueVersion())
					&& obligation.requiredPlacement().equals(loutState)));
	}

	private static String sliceLineCtableScript() {
		return "X=federated(addresses=list(\"" + WORKER + "\"),"
			+ "ranges=list(list(0,0),list(32561,13)));"
			+ "m=nrow(X);n=ncol(X);"
			+ "fdom=colMaxs(X);"
			+ "foffb=t(cumsum(t(fdom)))-fdom;"
			+ "foffe=t(cumsum(t(fdom)));"
			+ "rix=matrix(seq(1,m)%*%matrix(1,1,n),m*n,1);"
			+ "cix=matrix(X+foffb,m*n,1);"
			+ "X2=table(rix,cix,1,m,as.scalar(foffe[,n]),FALSE);"
			+ "e=federated(addresses=list(\"localhost:1234/e\"),"
			+ "ranges=list(list(0,0),list(32561,1)));"
			+ "Y=X2*e;"
			+ "print(max(colSums(Y)));";
	}

	private static List<CandidateInputState> ctableInputs() {
		return List.of(CandidateInputState.absentLocal(), CandidateInputState.present(FType.ROW),
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
	}

	private static PlacementAnalysis.HopOccurrenceProjection ctableOccurrence(PlacementAnalysis analysis) {
		return analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof TernaryOp ternary
				&& ternary.getOp() == OpOp3.CTABLE)
			.findFirst()
			.orElseThrow(() -> new AssertionError("CTABLE occurrence missing; inventory="
				+ occurrenceInventory(analysis)));
	}

	private static String occurrenceInventory(PlacementAnalysis analysis) {
		return analysis.occurrences().stream()
			.map(occurrence -> occurrence.hop().getClass().getSimpleName() + ':'
				+ occurrence.hop().getOpString() + ':' + occurrence.hop().getName())
			.collect(Collectors.joining(","));
	}

	private static boolean hasEmission(CandidateRuleFact fact, ExecType exec,
		FederatedOutput output, FType fType) {
		return fact.allowedEmissionFacts().stream()
			.anyMatch(emission -> isEmission(emission.emissionState().placementState(), exec, output, fType));
	}

	private static boolean hasExecOutput(CandidateRuleFact fact, ExecType exec, FederatedOutput output) {
		return fact.allowedEmissionFacts().stream().map(emission -> emission.emissionState().placementState())
			.anyMatch(state -> state.execType() == exec && state.output() == output);
	}

	private static boolean isEmission(PlacementState state, ExecType exec,
		FederatedOutput output, FType fType) {
		return state.execType() == exec && state.output() == output && state.fType() == fType;
	}

	private static PlacementAnalysis analyze(String script, Privacy privacy) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		assertTrue("fixture must retain CTABLE before placement analysis", containsCtable(program));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static boolean containsCtable(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(StatementBlock block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof TernaryOp ternary && ternary.getOp() == OpOp3.CTABLE)
				return true;
			pending.addAll(hop.getInput());
		}
		return false;
	}
}
