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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.BranchActivationFact;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts.OccurrenceProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Focused coverage for occurrence-scoped branch activation literals. */
public class OccurrenceActivationContextTest {
	@Test
	public void oppositeCallerBranchesReachFunctionWithOppositeLiterals() throws Exception {
		PlacementAnalysis analysis = analyze("""
			f=function(matrix[double] A) return (double out) {
				inner_if=0; inner_else=0;
				if(sum(A)>0) { inner_if=1; } else { inner_else=2; }
				out=sum(A)+inner_if+inner_else;
			}
			X=rand(rows=2,cols=2,min=-1,max=1,seed=7); a=0; b=0;
			if(sum(X)>0) { a=f(X); } else { b=f(X*2); }
			print(a+b);
			""");

		List<OccurrenceProfileFact> profiles = profiles(analysis, "inner_if");
		Assert.assertEquals(2, profiles.size());
		Assert.assertEquals(2, profiles.get(0).activationConditions().size());
		Assert.assertEquals(2, profiles.get(1).activationConditions().size());
		BranchActivationFact left = profiles.get(0).activationConditions().get(0);
		BranchActivationFact right = profiles.get(1).activationConditions().get(0);
		Assert.assertEquals(left.decisionKey(), right.decisionKey());
		Assert.assertNotEquals(left.ifArm(), right.ifArm());
		Assert.assertEquals(0.5, left.probability(), 0.0);
		Assert.assertEquals(0.5, right.probability(), 0.0);
		Assert.assertNotEquals(profiles.get(0).contextOrdinal(), profiles.get(1).contextOrdinal());
		Assert.assertEquals(0.5, analysis.executionFrequencyFacts()
			.executionWeight(occurrence(analysis, "inner_if").key()), 0.0);
	}

	@Test
	public void calledFunctionAppendsInternalBranchToCallerLiteral() throws Exception {
		PlacementAnalysis analysis = analyze("""
			f=function(matrix[double] A) return (double out) {
				inner_if=0; inner_else=0;
				if(sum(A)>0) { inner_if=1; } else { inner_else=2; }
				out=inner_if+inner_else;
			}
			X=rand(rows=2,cols=2,seed=7); result=0;
			if(sum(X)<10) { result=f(X); }
			print(result);
			""");

		OccurrenceProfileFact profile = onlyProfile(analysis, "inner_if");
		Assert.assertEquals(2, profile.activationConditions().size());
		BranchActivationFact caller = profile.activationConditions().get(0);
		BranchActivationFact internal = profile.activationConditions().get(1);
		Assert.assertNotEquals(caller.decisionKey(), internal.decisionKey());
		Assert.assertTrue(caller.decisionKey().contains("context=0"));
		Assert.assertTrue(internal.decisionKey().contains(
			"context=" + profile.contextOrdinal()));
		Assert.assertTrue(caller.ifArm());
		Assert.assertTrue(internal.ifArm());
		Assert.assertEquals(0.25, profile.expectedExecutions(), 0.0);
	}

	@Test
	public void branchLiteralCapturesItsEnclosingLoopIdentity() throws Exception {
		PlacementAnalysis analysis = analyze("""
			X=rand(rows=2,cols=2,seed=7); loop_branch=0;
			for(i in 1:3) {
				if(sum(X)>0) { loop_branch=i; }
			}
			print(loop_branch);
			""");

		OccurrenceProfileFact profile = onlyProfile(analysis, "loop_branch");
		Assert.assertEquals(1, profile.loopContext().size());
		BranchActivationFact condition = onlyCondition(profile);
		Assert.assertEquals(List.of(profile.loopContext().get(0).getLeft()),
			condition.enclosingLoopIds());
		Assert.assertEquals(1.5, profile.expectedExecutions(), 0.0);
	}

	@Test
	public void compilerKnownArmsRetainZeroAndOneProbabilityLiterals() throws Exception {
		PlacementAnalysis analysis = analyze("""
			x=0; known_dead=0; known_live=0;
			if(x!=0) { known_dead=1; } else { known_live=2; }
			print(known_dead+known_live);
			""");

		OccurrenceProfileFact dead = onlyProfile(analysis, "known_dead");
		OccurrenceProfileFact live = onlyProfile(analysis, "known_live");
		Assert.assertEquals(0.0, dead.expectedExecutions(), 0.0);
		Assert.assertEquals(1.0, live.expectedExecutions(), 0.0);
		Assert.assertEquals(0.0, onlyCondition(dead).probability(), 0.0);
		Assert.assertEquals(1.0, onlyCondition(live).probability(), 0.0);
		Assert.assertEquals(onlyCondition(dead).decisionKey(), onlyCondition(live).decisionKey());
		Assert.assertFalse(onlyCondition(dead).ifArm() == onlyCondition(live).ifArm());
	}

	@Test
	public void legacyThreeArgumentProfileConstructorHasNoConditions() {
		OccurrenceProfileFact profile = new OccurrenceProfileFact(2.0, List.of(), 7L);
		Assert.assertEquals(2.0, profile.expectedExecutions(), 0.0);
		Assert.assertTrue(profile.activationConditions().isEmpty());
	}

	@Test
	public void conservativeMissingProfileRemainsUsableLocallyButFailsClosedForExact()
		throws Exception {
		String path = "missing/structured/path";
		var constructor = OccurrenceExecutionFrequencyFacts.class.getDeclaredConstructor(
			Map.class, Set.class, boolean.class);
		constructor.setAccessible(true);
		OccurrenceExecutionFrequencyFacts facts = constructor.newInstance(
			Map.of(path, List.of(new OccurrenceProfileFact(1.0, List.of(), 0L))),
			Set.of(path), true);
		ControlRegionKey region = new ControlRegionKey(
			"fallback", "main", List.of(path), "main", "compiled");
		CompiledHopKey key = new CompiledHopKey(
			"fallback", "main", "main", "compiled", region, "read", "read");

		Assert.assertEquals("Non-exact policy ordering retains its conservative fallback",
			1.0, facts.executionWeight(key), 0.0);
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> facts.exactExecutionWeight(key));
		Assert.assertEquals("EXACT_OCCURRENCE_PROFILE_FALLBACK_UNPROVEN|path=" + path,
			failure.getMessage());
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static OccurrenceProfileFact onlyProfile(PlacementAnalysis analysis, String writeName) {
		List<OccurrenceProfileFact> profiles = profiles(analysis, writeName);
		Assert.assertEquals(writeName, 1, profiles.size());
		return profiles.get(0);
	}

	private static List<OccurrenceProfileFact> profiles(PlacementAnalysis analysis, String writeName) {
		return analysis.executionFrequencyFacts().profilesByPath()
			.get(path(analysis, writeName));
	}

	private static BranchActivationFact onlyCondition(OccurrenceProfileFact profile) {
		Assert.assertEquals(1, profile.activationConditions().size());
		return profile.activationConditions().get(0);
	}

	private static String path(PlacementAnalysis analysis, String writeName) {
		List<String> paths = occurrence(analysis, writeName).key().controlRegion().regionPath();
		Assert.assertEquals(writeName, 1, paths.size());
		return paths.get(0);
	}

	private static HopOccurrenceProjection occurrence(PlacementAnalysis analysis, String writeName) {
		List<HopOccurrenceProjection> matches = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.hop() instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTWRITE && writeName.equals(data.getName()))
			.toList();
		if(matches.size() > 1)
			matches = matches.stream().filter(occurrence -> occurrence.key().controlRegion()
				.regionPath().stream().anyMatch(path -> path.contains("/branch-"))).toList();
		Assert.assertEquals("Expected exactly one transient write for " + writeName, 1, matches.size());
		return matches.get(0);
	}
}
