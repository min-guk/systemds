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

package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Compact current-policy coverage replacing the obsolete descendant/NO_REFED metadata fixtures. */
public class CampaignBHeuristicMetadataCurrentPolicyTest {
	@Test
	public void exactKnownMatrixBroadcastFactsRemainAdmissibleButUnknownShapeInventsNoDemotion() throws Exception {
		PlacementAnalysis broadcast = new NeutralPlacementGraphBuilder().buildAnalysis(compile(broadcastVectorScript()));
		var node = broadcast.graph().nodes().stream()
			.filter(candidate -> candidate.anchors().stream().anyMatch(anchor -> anchor.fType() == FType.BROADCAST))
			.filter(candidate -> candidate.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == FType.BROADCAST))
			.findFirst().orElseThrow();
		Assert.assertEquals("exact graph fact is a known matrix", DataType.MATRIX,
			broadcast.shapeFact(node.key()).orElseThrow().dataType());
		Assert.assertTrue("exact graph fact retains a broadcast anchor",
			node.anchors().stream().anyMatch(anchor -> anchor.fType() == FType.BROADCAST));
		PlacementState broadcastFout = node.legalAlternatives().stream().filter(state -> state.execType() == ExecType.FED
			&& state.output() == FederatedOutput.FOUT && state.fType() == FType.BROADCAST).findFirst().orElseThrow();
		var selected = new HeuristicPlacementAdapter().select(broadcast, Set.of());
		Assert.assertTrue("known matrix/broadcast candidate remains admissible from exact graph facts",
			selected.filteredCandidateUniverse().contains(candidate(node.key().normalizedSignature(),
				broadcastFout.normalizedSignature())));
		Assert.assertTrue("no marker invents no policy evidence", selected.policyExclusions().isEmpty());

		DMLProgram program = compile(localVectorScript());
		PlacementAnalysis known = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertEquals(1, known.heuristicPolicyFacts().demotions().size());
		var fact = known.heuristicPolicyFacts().demotions().get(0);
		known.hop(fact.producer()).orElseThrow().setDim1(-1);
		known.hop(fact.producer()).orElseThrow().setDim2(-1);
		PlacementAnalysis unknown = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Assert.assertTrue("unknown vector shape cannot invent a typed demotion",
			unknown.heuristicPolicyFacts().demotions().isEmpty());
		var noPolicy = new HeuristicPlacementAdapter().select(unknown, Set.of());
		Assert.assertEquals("no invented policy must preserve exact FedAll selection",
			new FedAllPlacementAdapter().select(unknown).assignment(), noPolicy.assignment());
		Assert.assertTrue("no invented policy evidence", noPolicy.policyExclusions().isEmpty());
	}

	@Test
	public void twoTypedDemotionMarkersUnionExactlyIndependentOfInputOrder() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(twoMarkerScript()));
		List<ValueVersionKey> values = analysis.heuristicPolicyFacts().paths().stream()
			.filter(path -> !expectedPolicyExclusions(analysis, Set.of(path.demotion().valueVersion())).isEmpty())
			.limit(2).map(path -> path.demotion().valueVersion()).toList();
		Assert.assertEquals("fixture must contain two real typed markers with exact path evidence", 2, values.size());
		Set<ValueVersionKey> forward = new LinkedHashSet<>(values);
		List<ValueVersionKey> reversedValues = new ArrayList<>(values);
		java.util.Collections.reverse(reversedValues);
		Set<ValueVersionKey> reverse = new LinkedHashSet<>(reversedValues);
		Assert.assertNotEquals(List.copyOf(forward), List.copyOf(reverse));

		var selected = new HeuristicPlacementAdapter().select(analysis, forward);
		var reversed = new HeuristicPlacementAdapter().select(analysis, reverse);
		Assert.assertEquals("both typed paths contribute their exact union",
			expectedPolicyExclusions(analysis, forward), selected.policyExclusions());
		for(ValueVersionKey marker : forward) {
			List<String> contribution = expectedPolicyExclusions(analysis, Set.of(marker));
			Assert.assertFalse("each real marker contributes exact PATH_LOCAL/REENTRY_FRONTIER evidence",
				contribution.isEmpty());
			Assert.assertTrue("selected union retains every marker contribution",
				selected.policyExclusions().containsAll(contribution));
		}
		Assert.assertEquals(selected.policyExclusions(), reversed.policyExclusions());
		Assert.assertEquals(selected.filteredCandidateUniverse(), reversed.filteredCandidateUniverse());
		Assert.assertEquals(selected.assignment(), reversed.assignment());
		Assert.assertEquals(selected.certificate(), reversed.certificate());
		Assert.assertEquals(selected.normalizedPlanFingerprint(), reversed.normalizedPlanFingerprint());
	}

	@Test
	public void currentPolicySelectionUsesNoFallbackRepairOrMutation() throws Exception {
		DMLProgram program = compile(twoMarkerScript());
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var before = R4Heuristic2Probe.snapshot(program, analysis);
		Set<ValueVersionKey> markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toUnmodifiableSet());

		var selected = new HeuristicPlacementAdapter().select(analysis, markers);

		Assert.assertSame(analysis, selected.analysis());
		Assert.assertFalse("runtime fallback is forbidden", selected.certificate().fallbackUsed());
		Assert.assertTrue("policy facts cannot advertise fallback or repair", selected.plannerFacts().entrySet().stream()
			.noneMatch(entry -> (entry.getKey() + '=' + entry.getValue()).toLowerCase()
				.matches(".*(fallback|repair).*")));
		Assert.assertEquals(List.of(), selected.registryRefed());
		Assert.assertEquals(List.of(), selected.registryFoutMaterialize());
		Assert.assertEquals(List.of(), selected.registryLocalMaterialize());
		R4Heuristic2Probe.unchanged(before, R4Heuristic2Probe.snapshot(program, analysis));
	}

	private static List<String> expectedPolicyExclusions(PlacementAnalysis analysis, Set<ValueVersionKey> markers) {
		List<String> expected = new ArrayList<>();
		for(HeuristicPathFact path : analysis.heuristicPolicyFacts().paths()) {
			if(!markers.contains(path.demotion().valueVersion()))
				continue;
			for(CompiledHopKey key : path.localPrefix()) {
				var node = analysis.graph().node(key).orElseThrow();
				for(PlacementState state : node.legalAlternatives())
					if(state.output() == FederatedOutput.FOUT)
						expected.add("PATH_LOCAL|" + candidate(key.normalizedSignature(), state.normalizedSignature())
							+ "|value=" + node.valueVersion().normalizedSignature());
			}
			path.reentries().forEach(frontier -> expected.add("REENTRY_FRONTIER|consumer="
				+ frontier.consumer().normalizedSignature() + "|input=" + frontier.inputPosition() + "|value="
				+ frontier.sourceValueVersion().normalizedSignature() + "|relocation="
				+ frontier.relocationAction().normalizedSignature()));
		}
		expected.sort(String::compareTo);
		return expected;
	}

	private static String candidate(String key, String state) {
		return key.length() + ":" + key + '|' + state.length() + ":" + state;
	}

	private static String broadcastVectorScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(4,2),list(0,0),list(4,2)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "w=z+1;", "print(sum(w));") + "\n";
	}

	private static String twoMarkerScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),"
				+ "ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"B=federated(addresses=list(\"localhost:1234/B1\",\"localhost:1235/B2\"),"
				+ "ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"v1=matrix(1,2,1);", "v2=matrix(2,2,1);", "z1=X%*%v1;", "z2=Y%*%v2;",
			"q1=A*(z1+1);", "q2=B*(z2+1);", "print(sum(q1)+sum(q2));") + "\n";
	}

	private static String localVectorScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "w=z+1;", "print(sum(w));") + "\n";
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
