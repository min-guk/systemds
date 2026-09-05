/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Contract for analysis-owned relocation source-privacy indexing. */
public class RelocationPrivacyIndexReuseTest {
	@Test
	public void exactGraphReusesOneIndexForOriginalAndCopiedActions() throws Exception {
		PlacementAnalysis analysis = analysis(Privacy.PUBLIC, 1234);
		List<RelocationAction> actions = analysis.graph().relocationActions();
		Assert.assertFalse("fixture requires relocation authority", actions.isEmpty());

		var original = RelocationSelections.relocationPrivacyIndex(
			analysis, analysis.graph(), actions);
		var repeated = RelocationSelections.relocationPrivacyIndex(
			analysis, analysis.graph(), actions);
		List<RelocationAction> copied = actions.stream().map(action -> new RelocationAction(
			action.key(), action.obligations(), action.directSourcePlacements())).toList();
		var copiedIndex = RelocationSelections.relocationPrivacyIndex(
			analysis, analysis.graph(), copied);

		Assert.assertSame(original, repeated);
		Assert.assertSame("structurally copied exact actions use the same source authority",
			original, copiedIndex);
	}

	@Test
	public void projectedGraphGetsScopedIndexAndMissingOwnerStillFailsClosed() throws Exception {
		PlacementAnalysis analysis = analysis(Privacy.PUBLIC, 1234);
		NeutralPlacementGraph graph = analysis.graph();
		List<RelocationAction> actions = graph.relocationActions();
		var common = RelocationSelections.relocationPrivacyIndex(analysis, graph, actions);
		NeutralPlacementGraph projection = new NeutralPlacementGraph(graph.nodes(), graph.constraints(),
			graph.relocationActions(), graph.derivedFoutMaterializationActions());
		var projected = RelocationSelections.relocationPrivacyIndex(analysis, projection, actions);

		Assert.assertNotSame("a projected authority graph must not inherit the exact-graph cache",
			common, projected);
		for(RelocationAction action : actions)
			Assert.assertEquals(common.requiresOriginResidency(action),
				projected.requiresOriginResidency(action));

		PlacementAnalysis foreign = analysis(Privacy.PUBLIC, 2234);
		Assert.assertThrows("foreign projection has no emitted owner for these source versions",
			IllegalStateException.class, () -> RelocationSelections.relocationPrivacyIndex(
				analysis, foreign.graph(), actions));
	}

	@Test
	public void analysesWithDifferentPrivacyFactsNeverShareAuthority() throws Exception {
		PlacementAnalysis publicAnalysis = analysis(Privacy.PUBLIC, 1234);
		RelocationAction action = publicAnalysis.graph().relocationActions().get(0);
		List<NeutralPlacementGraph.Node> owners = publicAnalysis.graph().nodes().stream()
			.filter(node -> node.valueVersion().equals(action.key().sourceValueVersion()))
			.filter(node -> publicAnalysis.isCompiledHopOccurrence(node.key())).toList();
		Assert.assertFalse("relocation source requires at least one emitted owner", owners.isEmpty());
		PlacementAnalysis protectedAnalysis = withPrivacy(publicAnalysis,
			Map.of(owners.get(0).key(), Privacy.PRIVATE_AGGREGATE));

		var publicIndex = RelocationSelections.relocationPrivacyIndex(publicAnalysis,
			publicAnalysis.graph(), publicAnalysis.graph().relocationActions());
		var protectedIndex = RelocationSelections.relocationPrivacyIndex(protectedAnalysis,
			protectedAnalysis.graph(), protectedAnalysis.graph().relocationActions());
		Assert.assertNotSame(publicIndex, protectedIndex);
		Assert.assertFalse(publicIndex.requiresOriginResidency(action));
		Assert.assertTrue("one protected possible owner conservatively binds the value version",
			protectedIndex.requiresOriginResidency(action));
		Assert.assertSame(protectedIndex, RelocationSelections.relocationPrivacyIndex(
			protectedAnalysis, protectedAnalysis.graph(), protectedAnalysis.graph().relocationActions()));
	}

	private static PlacementAnalysis analysis(Privacy privacy, int firstPort) throws Exception {
		String ranges = "list(list(0,0),list(2,2),list(2,0),list(4,2))";
		String script = "A=federated(addresses=list(\"localhost:" + firstPort
			+ "/A1\",\"localhost:" + (firstPort + 1) + "/A2\"),ranges=" + ranges + ");\n"
			+ "B=federated(addresses=list(\"localhost:" + firstPort
			+ "/B1\",\"localhost:" + (firstPort + 1) + "/B2\"),ranges=" + ranges + ");\n"
			+ "C=A+B; print(sum(C));\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static PlacementAnalysis withPrivacy(PlacementAnalysis source,
		Map<CompiledHopKey,Privacy> replacements) {
		Map<CompiledHopKey,PlacementAnalysis.NodeShapeFact> shapes = new LinkedHashMap<>();
		LinkedHashSet<CompiledHopKey> keys = new LinkedHashSet<>();
		for(HopOccurrenceProjection occurrence : source.occurrences()) {
			keys.add(occurrence.key());
			shapes.put(occurrence.key(), source.shapeFact(occurrence.key()).orElseThrow());
		}
		List<PlacementPrivacyFacts.PrivacyFact> privacy = source.graph().nodes().stream()
			.map(node -> new PlacementPrivacyFacts.PrivacyFact(node.key(), node.valueVersion(),
				replacements.getOrDefault(node.key(), source.requirePrivacy(node.key())), List.of()))
			.toList();
		return new PlacementAnalysis(source.graph(), source.occurrences(),
			source.topLevelStatementBlocks(), null,
			new PlacementShapeFacts(shapes, keys), source.analysisFingerprint(),
			source.heuristicPolicyFacts(), source.candidateRuleDomain().orderedRuleKeys(),
			source.candidateRuleFacts().orderedFacts(),
			source.candidateRuleDomain().orderedConsumerKeys(),
			source.candidateConsumerProfileFacts().orderedFacts(),
			source.detachedConsumerProfileFacts().orderedFacts(),
			source.compiledInputEdgesInCanonicalOrder(),
			source.logicalTransientInputsInCanonicalOrder(),
			new PlacementPrivacyFacts(source.graph().nodes(), privacy, source.numWorkers()), null);
	}
}
