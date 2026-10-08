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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** P1 work and legal-space contract for analysis-time privacy emission suppression. */
public class EarlyPrivacyGenerationWorkTest {
	private static final String FEDERATED_SOURCE =
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n";

	@Test
	public void fusedGeneratorBoundaryRejectsProtectedAbsenceBeforeCandidateAllocation() {
		List<List<FType>> unmasked = List.of(
			java.util.Arrays.asList(null, FType.ROW),
			java.util.Arrays.asList(null, FType.BROADCAST),
			List.of(FType.COL));
		var privacy = new PlacementCandidateGenerator.GenerationPrivacy(
			Privacy.PRIVATE_AGGREGATE, Set.of(0, 1));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<List<FType>> allocatedCandidates = new java.util.ArrayList<>();

		PlacementCandidateGenerator.forEachInputCombination(
			unmasked, privacy, allocatedCandidates::add, metrics);

		Assert.assertEquals("only the all-present protected tuple reaches key/fact allocation",
			List.of(List.of(FType.ROW, FType.BROADCAST, FType.COL)), allocatedCandidates);
		Assert.assertEquals("one accepted tuple corresponds to one possible Oracle call", 1,
			metrics.snapshot().inputLeaves());
		Assert.assertEquals("three illegal Cartesian tuples are rejected before allocation", 3,
			4 - allocatedCandidates.size());
		Assert.assertEquals("generator reports Oracle calls and key/fact allocations avoided",
			java.math.BigInteger.valueOf(3),
			metrics.privacyPruningSnapshot().generatorCombinationsRejected());
	}

	@Test
	public void fusedInputGateDoesNotGuessOutputPrivacyOrAggregateExceptions() {
		List<List<FType>> domains = List.of(
			java.util.Arrays.asList(null, FType.ROW),
			java.util.Arrays.asList(null, FType.BROADCAST));
		List<List<FType>> expectedProtectedInputs = List.of(
			java.util.Arrays.asList(FType.ROW, null),
			List.of(FType.ROW, FType.BROADCAST));
		for(Privacy outputPrivacy : List.of(Privacy.PRIVATE, Privacy.PRIVATE_AGGREGATE,
			Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, Privacy.PUBLIC)) {
			List<List<FType>> accepted = new java.util.ArrayList<>();
			PlacementCandidateGenerator.forEachInputCombination(domains,
				new PlacementCandidateGenerator.GenerationPrivacy(outputPrivacy, Set.of(0)),
				accepted::add, null);
			Assert.assertEquals("input fusion only enforces certified payload residency; output policy stays post-Oracle",
				expectedProtectedInputs, accepted);
		}

		List<List<FType>> unknown = new java.util.ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(domains, null, unknown::add, null);
		Assert.assertEquals("unknown privacy remains conservative and retains the whole input product",
			4, unknown.size());
	}

	@Test
	public void sameBlockPrivacyPruningAvoidsTuplesAndExpansionWithoutChangingLegalSpace()
		throws Exception {
		String script = FEDERATED_SOURCE + "B=A+1;C=B*2;print(sum(C));\n";
		MeasuredAnalysis baseline = analyze(script, false, true);
		MeasuredAnalysis early = analyze(script, true, true);
		PlacementAnalysis earlyWithoutMetrics = analyze(script, true, false).analysis();

		assertSameLegalSpace(baseline.analysis(), early.analysis());
		assertSameLegalSpace(early.analysis(), earlyWithoutMetrics);
		Assert.assertEquals("disabled early pruning must not report suppressed emission work", 0,
			baseline.metrics().privacyPruningSnapshot().emissionsSuppressed());
		Assert.assertTrue("protected replay fixture must suppress emission expansion before support construction",
			early.metrics().privacyPruningSnapshot().emissionsSuppressed() > 0);
		Assert.assertTrue("complete protected payload authority must mask at least one input domain",
			early.metrics().privacyPruningSnapshot().maskedDomains() > 0);
		Assert.assertTrue("the compact certificate must account for omitted Cartesian tuples",
			early.metrics().privacyPruningSnapshot().avoidedTuples().signum() > 0);
		Assert.assertTrue("early masks must visit fewer complete input tuples",
			early.metrics().snapshot().inputLeaves() < baseline.metrics().snapshot().inputLeaves());
		Assert.assertTrue("early masks must avoid Oracle calls for certified illegal tuples",
			early.metrics().privacyPruningSnapshot().oracleCalls()
				< baseline.metrics().privacyPruningSnapshot().oracleCalls());
		printDeterministicWorkEvidence(baseline.metrics(), early.metrics());
	}

	@Test
	public void fixedControlFlowPrivacyPrunesDescendantGenerationWithoutChangingLegalSpace() throws Exception {
		String script = controlFlowScript();
		MeasuredAnalysis baseline = analyze(script, false, true);
		MeasuredAnalysis early = analyze(script, true, true);

		assertSameLegalSpace(baseline.analysis(), early.analysis());
		Assert.assertTrue("fixed privacy must mask ordinary loop/function descendants before full closure",
			early.metrics().privacyPruningSnapshot().maskedDomains() > 0);
		Assert.assertTrue("fixed protected positions must avoid illegal descendant tuples",
			early.metrics().privacyPruningSnapshot().avoidedTuples().signum() > 0);
		Assert.assertTrue("forbidden descendant tuples must not reach the Oracle",
			early.metrics().privacyPruningSnapshot().oracleCalls()
				< baseline.metrics().privacyPruningSnapshot().oracleCalls());
		printDeterministicWorkEvidence(baseline.metrics(), early.metrics());
	}

	@Test
	public void controlFlowGenerationProjectionRespectsPrivateAndPublicPrivacy() throws Exception {
		for(Privacy privacy : List.of(Privacy.PRIVATE, Privacy.PRIVATE_AGGREGATE, Privacy.PUBLIC)) {
			MeasuredAnalysis baseline = analyze(controlFlowPayloadScript(), false, true, privacy);
			MeasuredAnalysis early = analyze(controlFlowPayloadScript(), true, true, privacy);
			assertSameLegalSpace(baseline.analysis(), early.analysis());
			var coverage = early.metrics().generationPruningCoverage();
			Assert.assertTrue("structural privacy projection must reach descendant generation: " + privacy,
				coverage.privacyProjectionLookups() > 0);
			if(privacy == Privacy.PUBLIC) {
				Assert.assertEquals("public projection has no origin-resident payload positions", 0,
					coverage.privacyProtectedLookups());
				Assert.assertEquals("public inputs have no protected payload position", 0,
					early.metrics().privacyPruningSnapshot().maskedDomains());
				Assert.assertEquals(java.math.BigInteger.ZERO,
					early.metrics().privacyPruningSnapshot().avoidedTuples());
			}
			else {
				Assert.assertTrue("protected descendants must receive fixed generation authority: " + privacy,
					coverage.privacyProtectedLookups() > 0);
				Assert.assertTrue("protected descendant domains must be checked before full closure: " + privacy,
					coverage.privacyInputMaskChecks() > 0);
			}
		}
	}

	@Test
	public void metadataAndInlinedHandleExceptionsRemainLegalWithMetricsOnOrOff() throws Exception {
		String script = "f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE + "r=nrow(A);C=f(A);print(r);print(sum(C));\n";
		MeasuredAnalysis baseline = analyze(script, false, true);
		MeasuredAnalysis early = analyze(script, true, true);
		PlacementAnalysis earlyWithoutMetrics = analyze(script, true, false).analysis();

		assertSameLegalSpace(baseline.analysis(), early.analysis());
		assertSameLegalSpace(early.analysis(), earlyWithoutMetrics);
		Assert.assertTrue("candidate generation must reach the production Oracle",
			early.metrics().privacyPruningSnapshot().oracleCalls() > 0);
	}

	private static void assertSameLegalSpace(PlacementAnalysis expected, PlacementAnalysis actual) {
		Assert.assertEquals(EarlyPrivacyPruningLegalSpaceParityTest.semanticSnapshot(expected),
			EarlyPrivacyPruningLegalSpaceParityTest.semanticSnapshot(actual));
	}

	private static void printDeterministicWorkEvidence(SearchSpaceMetrics baseline,
		SearchSpaceMetrics early) {
		var bPrivacy = baseline.privacyPruningSnapshot();
		var ePrivacy = early.privacyPruningSnapshot();
		var b = baseline.snapshot();
		var e = early.snapshot();
		System.out.println("EARLY_PRIVACY_WORK"
			+ "|baselineOracle=" + bPrivacy.oracleCalls() + "|earlyOracle=" + ePrivacy.oracleCalls()
			+ "|baselineLeaves=" + b.inputLeaves() + "|earlyLeaves=" + e.inputLeaves()
			+ "|avoidedTuples=" + ePrivacy.avoidedTuples()
			+ "|emissionsSuppressed=" + ePrivacy.emissionsSuppressed()
			+ "|emissionAllocationsAvoided=" + e.privacyEmissionAllocationsAvoided()
			+ "|privacyTransferVisits=" + e.privacyTransferVisits()
			+ "|supportIndexedRealizations=" + e.supportIndexedRealizations()
			+ "|supportIndexedClauses=" + e.supportIndexedClauses()
			+ "|supportQueueVisits=" + e.supportQueueVisits());
	}

	private static MeasuredAnalysis analyze(String script, boolean earlyPrivacyPruning,
		boolean collectMetrics) throws Exception {
		return analyze(script, earlyPrivacyPruning, collectMetrics, Privacy.PRIVATE_AGGREGATE);
	}

	private static MeasuredAnalysis analyze(String script, boolean earlyPrivacyPruning,
		boolean collectMetrics, Privacy privacy) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		SearchSpaceMetrics metrics = collectMetrics ? new SearchSpaceMetrics() : null;
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder(null, metrics, true,
			earlyPrivacyPruning).buildDetachedAnalysis(program);
		return new MeasuredAnalysis(analysis, metrics);
	}

	private static String controlFlowScript() {
		return controlFlowPayloadScript() + "print(sum(C));\n";
	}

	private static String controlFlowPayloadScript() {
		return "f=function(matrix[double] X) return (matrix[double] Y){Y=X+1;}\n"
			+ FEDERATED_SOURCE
			+ "i=1;while(i<=2){if(i>0){D=A+1;}else{D=A-1;}i=i+1;}"
			+ "C=f(D);";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		assertFederatedSourcePresent(program);
		return program;
	}

	private static void assertFederatedSourcePresent(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		boolean found = false;
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			found |= hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
			pending.addAll(hop.getInput());
		}
		Assert.assertTrue("fixture requires one protected federated source", found);
	}

	private record MeasuredAnalysis(PlacementAnalysis analysis, SearchSpaceMetrics metrics) { }
}
