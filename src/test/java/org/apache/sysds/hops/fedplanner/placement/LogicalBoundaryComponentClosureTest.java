/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections.ComponentDependency;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class LogicalBoundaryComponentClosureTest {
	private static final String SCRIPT = """
		f = function(matrix[double] A) return (matrix[double] B) {
			[U, S, V] = svd(A);
			B = A + 1;
		}
		X = federated(addresses=list("localhost:8001/X", "localhost:8002/X"),
			ranges=list(list(0, 0), list(500, 100), list(500, 0), list(1000, 100)));
		Y = f(X);
		print(sum(Y));
		""";

	@Test
	public void boundaryOnlyRelationsClosePartialDependenciesAndDisableSeparablePreference() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(SCRIPT));
		List<LogicalBoundaryRealizations.Relation> relations = analysis.logicalBoundaryRealizations().relations();
		Assert.assertFalse("fixture must expose logical-boundary coupling", relations.isEmpty());
		Assert.assertTrue("fixture must isolate boundary coupling from transient-value coupling",
			analysis.logicalTransientInputsInCanonicalOrder().isEmpty());
		Assert.assertFalse("fixture must retain at least one boundary-only candidate relation",
			boundaryOnlyCandidateRelations(analysis, relations).isEmpty());

		CandidateSelections.PartialReachabilityIndex index = CandidateSelections.partialReachabilityIndex(
			analysis, analysis.graph(), analysis.graph().relocationActions());
		for(LogicalBoundaryRealizations.Relation relation : boundaryOnlyCandidateRelations(analysis, relations)) {
			if(isCandidateConsumer(analysis, relation.source()))
				Assert.assertTrue("source-side boundary dependency missing from partial component closure",
					contains(index.componentDependencies(), relation.target(), relation.source()));
			if(isCandidateConsumer(analysis, relation.target()))
				Assert.assertTrue("target-side boundary dependency missing from partial component closure",
					contains(index.componentDependencies(), relation.source(), relation.target()));
		}

		Method gate = CandidateSelections.class.getDeclaredMethod("hasRealizationDependencies", PlacementAnalysis.class);
		gate.setAccessible(true);
		Assert.assertEquals("logical-boundary coupling must disable consumer-separable row preference",
			Boolean.TRUE, gate.invoke(null, analysis));
	}

	private static List<LogicalBoundaryRealizations.Relation> boundaryOnlyCandidateRelations(
		PlacementAnalysis analysis, List<LogicalBoundaryRealizations.Relation> relations) {
		return relations.stream().filter(relation ->
			isCandidateConsumer(analysis, relation.source()) || isCandidateConsumer(analysis, relation.target()))
			.filter(relation -> analysis.compiledInputEdgesInCanonicalOrder().stream().noneMatch(edge ->
				edge.producer() == relation.source() && edge.consumer() == relation.target()
					|| edge.producer() == relation.target() && edge.consumer() == relation.source()))
			.filter(relation -> analysis.candidateRuleFacts().orderedFacts().stream().noneMatch(fact ->
				fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
					.flatMap(realization -> realization.supportClauses().stream())
					.flatMap(clause -> clause.requiredInputSupport().stream())
					.anyMatch(reference -> fact.key().parentOccurrence() == relation.source()
						&& reference.rule().parentOccurrence() == relation.target()
						|| fact.key().parentOccurrence() == relation.target()
							&& reference.rule().parentOccurrence() == relation.source())))
			.toList();
	}

	private static boolean isCandidateConsumer(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.candidateRuleFacts().orderedFacts().stream()
			.anyMatch(fact -> fact.key().parentOccurrence() == key);
	}

	private static boolean contains(List<ComponentDependency> dependencies,
		CompiledHopKey participant, CompiledHopKey consumer) {
		return dependencies.stream().anyMatch(dependency ->
			dependency.participant() == participant && dependency.consumer() == consumer);
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}
}
