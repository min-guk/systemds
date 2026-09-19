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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Bounded generation-completeness checks whose expected universes come from
 * literal fixture contracts rather than candidate facts or a selected plan.
 * The active protected fixtures certify relocation absence only; positive
 * relocation generation remains unresolved while its PUBLIC-dependent fixture
 * is excluded by repository policy.
 */
public class IndependentPlanSpaceGenerationCompletenessTest {
	private static final PlacementState PROTECTED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final Set<String> TRANSIENT_STATE_UNIVERSE = Set.of(
		state(ExecType.CP, FederatedOutput.LOUT, FType.ROW, false),
		state(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false),
		state(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false),
		state(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false));

	private static final String PROTECTED_TRANSIENT_SCRIPT =
		"f=function(matrix[double] X) return (matrix[double] q){"
			+ "gate=matrix(1,rows=1,cols=1);"
			+ "if(sum(gate)>0){z=X+1;}else{z=X+2;}q=z*3;}"
			+ "A=federated(addresses=list(\"localhost:31334/A1\",\"localhost:31335/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "Q=f(A);print(sum(Q));";

	private static final String MIXED_POOL_SCRIPT =
		"A=federated(addresses=list(\"localhost:32334/A1\",\"localhost:32335/A2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
			+ "B=federated(addresses=list(\"localhost:32434/B1\",\"localhost:32435/B2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
			+ "C=A+B;print(sum(C));";
	private static final String PROTECTED_ROW_SCRIPT =
		"A=federated(addresses=list(\"localhost:33334/A1\",\"localhost:33335/A2\"),"
			+ "ranges=list(list(0,0),list(3,2),list(3,0),list(6,2)));"
			+ "C=A+1;print(sum(C));";

	@Test
	public void protectedTransientGenerationMatchesExplicitCompileAndRecompileUniverse() throws Exception {
		for(boolean recompile : List.of(false, true)) {
			DMLProgram program = compile(PROTECTED_TRANSIENT_SCRIPT);
			if(recompile)
				markRecompile(program);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
				program, Privacy.PRIVATE_AGGREGATE);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);

			List<HopOccurrenceProjection> reads = analysis.occurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTREAD
					&& ("z".equals(data.getName()) || "q".equals(data.getName())))
				.filter(occurrence -> analysis.requirePrivacy(occurrence.key())
					== Privacy.PRIVATE_AGGREGATE)
				.filter(occurrence -> !analysis.logicalTransientInputsForReader(
					occurrence.key(), 0).isEmpty())
				.toList();
			Assert.assertFalse("fixture must contain protected transient readers", reads.isEmpty());
			if(recompile)
				Assert.assertTrue("recompile fixture must exercise recompile occurrences", reads.stream()
					.anyMatch(read -> "recompile".equals(read.key().recompileContext())));

			for(HopOccurrenceProjection read : reads) {
				Set<String> expected = Set.of(PROTECTED_ROW.normalizedSignature());
				Set<String> actual = analysis.logicalTransientInputsForReader(read.key(), 0).stream()
					.flatMap(fact -> fact.compatibility().stream())
					.map(edge -> edge.readerRealization().realization().emissionState()
						.placementState().normalizedSignature())
					.collect(Collectors.toCollection(LinkedHashSet::new));
				assertExactSet("protected transient " + read.key().normalizedSignature(), expected, actual);

				Set<String> forbidden = new LinkedHashSet<>(TRANSIENT_STATE_UNIVERSE);
				forbidden.removeAll(expected);
				Assert.assertEquals("fixture contract must exercise three forbidden alternatives",
					3, forbidden.size());
				Assert.assertTrue("privacy/TR-TW contract admitted a forbidden state: " + actual,
					Collections.disjoint(actual, forbidden));
			}
		}
	}

	@Test
	public void protectedExactRowGenerationMatchesStateGeometryAndNoEmissionUniverse() throws Exception {
		DMLProgram program = compile(PROTECTED_ROW_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Node source = uniqueNode(analysis, "A", "Fed A");
		Node transform = uniqueNode(analysis, "C", "b(+)");

		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(source.key()));
		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(transform.key()));
		Map<String,Set<String>> expectedStates = Map.of(
			"A", Set.of(PROTECTED_ROW.normalizedSignature()),
			"C", Set.of(PROTECTED_ROW.normalizedSignature()));
		Map<String,Set<String>> actualStates = Map.of(
			"A", stateSignatures(source),
			"C", stateSignatures(transform));
		for(String name : expectedStates.keySet())
			assertExactSet("protected state universe " + name,
				expectedStates.get(name), actualStates.get(name));

		Assert.assertEquals("protected source must retain one literal durable map",
			1, source.anchors().size());
		assertExactSet("protected exact ROW geometry", Set.of(
			"localhost:33334/A1|0,0:3,2", "localhost:33335/A2|3,0:6,2"),
			partitionGeometry(source.anchors().get(0)));
		long demandedInputs = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == source.key() && edge.consumer() == transform.key()
				&& edge.inputPosition() == 0)
			.count();
		Assert.assertEquals("fixture must exercise one protected matrix input demand",
			1, demandedInputs);

		List<RelocationAction> inputAuthorities = analysis.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion().equals(source.valueVersion()))
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == transform.key() && obligation.inputPosition() == 0))
			.toList();
		Set<String> emitted = inputAuthorities.stream()
			.filter(action -> action.directSourcePlacements().isEmpty())
			.map(action -> action.key().normalizedSignature())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		assertExactSet("protected origin-resident input must generate no physical relocation emission",
			Set.of(), emitted);
		Assert.assertTrue("every protected input authority must be an existing-map direct receipt",
			inputAuthorities.stream().allMatch(action ->
				action.directSourcePlacements().contains(PROTECTED_ROW)
					&& geometry(action.key().durableAnchor())
						.equals(geometry(source.anchors().get(0)))));
	}

	@Test
	@Ignore("Requires one PUBLIC source; excluded by repository privacy-test policy")
	public void mixedPrivacyExactRowPoolsGenerateEveryAndOnlyContractRelocation() throws Exception {
		DMLProgram program = compile(MIXED_POOL_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		List<DataOp> sources = federatedSources(program);
		Assert.assertEquals("fixture requires two literal federated sources", 2, sources.size());
		sources.stream().filter(source -> "A".equals(source.getName())).forEach(source ->
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(
				source, Privacy.PRIVATE_AGGREGATE));
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Node consumer = uniqueNode(analysis, "C", "b(+)");
		Node left = uniqueNode(analysis, "A", "Fed A");
		Node right = uniqueNode(analysis, "B", "Fed B");

		Assert.assertEquals(Privacy.PRIVATE_AGGREGATE, analysis.requirePrivacy(left.key()));
		Assert.assertEquals(Privacy.PUBLIC, analysis.requirePrivacy(right.key()));
		Assert.assertEquals("each source must own one exact two-row anchor", 1, left.anchors().size());
		Assert.assertEquals("each source must own one exact two-row anchor", 1, right.anchors().size());
		Assert.assertEquals(Set.of(
			"localhost:32334/A1|0,0:2,2", "localhost:32335/A2|2,0:4,2"),
			partitionGeometry(left.anchors().get(0)));
		Assert.assertEquals(Set.of(
			"localhost:32434/B1|0,0:2,2", "localhost:32435/B2|2,0:4,2"),
			partitionGeometry(right.anchors().get(0)));

		Map<String,String> sourceByVersion = Map.of(
			left.valueVersion().normalizedSignature(), "A",
			right.valueVersion().normalizedSignature(), "B");
		Set<String> actual = new LinkedHashSet<>();
		for(RelocationAction action : analysis.graph().relocationActions()) {
			String source = sourceByVersion.get(action.key().sourceValueVersion().normalizedSignature());
			if(source == null)
				continue;
			for(var obligation : action.obligations()) {
				if(obligation.consumer() != consumer.key())
					continue;
				actual.add(relocation(source, obligation.inputPosition(), action));
			}
		}

		String protectedGeometry = geometry(left.anchors().get(0));
		Set<String> expected = Set.of(
			"A|0|" + PROTECTED_ROW.normalizedSignature() + '|' + protectedGeometry + "|DIRECT",
			"B|1|" + PROTECTED_ROW.normalizedSignature() + '|' + protectedGeometry + "|EMIT");
		assertExactSet("mixed-privacy relocation universe", expected, actual);
		Assert.assertTrue("fixture must exercise one direct input receipt",
			actual.stream().anyMatch(value -> value.endsWith("|DIRECT")));
		Assert.assertTrue("fixture must exercise one emitted relocation",
			actual.stream().anyMatch(value -> value.endsWith("|EMIT")));
		Assert.assertTrue("protected input must never be relocated into the public pool",
			actual.stream().noneMatch(value -> value.startsWith("A|")
				&& value.contains(geometry(right.anchors().get(0)))));
	}

	private static String relocation(String source, int position, RelocationAction action) {
		boolean direct = action.directSourcePlacements().contains(PROTECTED_ROW);
		return source + '|' + position + '|'
			+ action.key().targetPlacement().normalizedSignature() + '|'
			+ geometry(action.key().durableAnchor()) + '|' + (direct ? "DIRECT" : "EMIT");
	}

	private static String geometry(DurableAnchorKey anchor) {
		return anchor.fType().name() + ':' + String.join(",", partitionGeometry(anchor));
	}

	private static Set<String> partitionGeometry(DurableAnchorKey anchor) {
		return anchor.partitions().stream().map(IndependentPlanSpaceGenerationCompletenessTest::partition)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static String partition(AnchorPartition partition) {
		return partition.workerId() + '|' + coordinates(partition.begin()) + ':'
			+ coordinates(partition.end());
	}

	private static String coordinates(List<Long> values) {
		return values.stream().map(String::valueOf).collect(Collectors.joining(","));
	}

	private static Set<String> stateSignatures(Node node) {
		return node.legalAlternatives().stream().map(PlacementState::normalizedSignature)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static void assertExactSet(String label, Set<String> expected, Set<String> actual) {
		Set<String> missing = new LinkedHashSet<>(expected);
		missing.removeAll(actual);
		Set<String> extra = new LinkedHashSet<>(actual);
		extra.removeAll(expected);
		Assert.assertTrue(label + " missing=" + missing + " extra=" + extra
			+ " expected=" + expected + " actual=" + actual,
			missing.isEmpty() && extra.isEmpty());
	}

	private static String state(ExecType exec, FederatedOutput output, FType fType,
		boolean shapeDependent) {
		return new PlacementState(exec, output, fType, shapeDependent).normalizedSignature();
	}

	private static Node uniqueNode(PlacementAnalysis analysis, String name, String opcode) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> name.equals(hop.getName()) && opcode.equals(hop.getOpString())).orElse(false)).toList();
		Assert.assertEquals("fixture requires one " + name + '/' + opcode, 1, matches.size());
		return matches.get(0);
	}

	private static List<DataOp> federatedSources(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(StatementBlock block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<DataOp> result = new ArrayList<>();
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
				result.add(data);
			pending.addAll(hop.getInput());
		}
		return result;
	}

	private static void markRecompile(DMLProgram program) {
		for(StatementBlock block : program.getStatementBlocks())
			block.setRecompileOnce(true);
		program.getNamedNSFunctionStatementBlocks().values().forEach(function ->
			function.setRecompileOnce(true));
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
