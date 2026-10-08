/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Independent full-closure reference for weighted relation-native generation. */
public class WeightedRelationNativeClosureParityTest {
	@Test(timeout = 30000)
	public void publicWeightedRelationMatchesExplicitFullClosure() throws Exception {
		assertFullClosureParity(Privacy.PUBLIC, false);
	}

	@Test(timeout = 30000)
	public void privateAggregateWeightedRelationMatchesExplicitFullClosure() throws Exception {
		assertFullClosureParity(Privacy.PRIVATE_AGGREGATE, false);
	}

	@Test(timeout = 30000)
	public void publicWeightedCrossEntropyMatchesExplicitFullClosure() throws Exception {
		assertFullClosureParity(Privacy.PUBLIC, true);
	}

	@Test(timeout = 30000)
	public void privateWeightedCrossEntropyMatchesExplicitFullClosure() throws Exception {
		assertFullClosureParity(Privacy.PRIVATE_AGGREGATE, true);
	}

	@Test(timeout = 30000)
	public void distinctPoolFederatedAuxiliariesMatchExplicitFullClosure() throws Exception {
		assertFullClosureParity(Privacy.PUBLIC, false, true);
	}

	@Test(timeout = 30000)
	public void repeatedAuxiliaryOwnerMatchesExplicitFullClosure() throws Exception {
		assertScriptParity(Privacy.PUBLIC, repeatedOwnerScript(), false);
	}

	@Test(timeout = 30000)
	public void weightedFunctionBoundaryMatchesExplicitFullClosure() throws Exception {
		assertScriptParity(Privacy.PUBLIC, functionBoundaryScript(), false);
	}

	@Test(timeout = 30000)
	public void dynamicRemoveEmptySourceMatchesExplicitFullClosure() throws Exception {
		assertUnsupportedFallbackParity(Privacy.PUBLIC, dynamicSourceScript());
	}

	@Test(timeout = 30000)
	public void privateWeightedSourceFailsClosedInBothGenerators() throws Exception {
		String explicit = failureSignature(Privacy.PRIVATE, false, standardScript(false, false));
		String relation = failureSignature(Privacy.PRIVATE, true, standardScript(false, false));
		Assert.assertFalse("PRIVATE weighted input must fail closed", explicit.isEmpty());
		Assert.assertEquals(explicit, relation);
	}

	@Test(timeout = 120000)
	public void fixedSeedTopologyMatrixMatchesExplicitFullClosure() throws Exception {
		Random random = new Random(0x51_7a_11_0cL);
		for(int testCase = 0; testCase < 12; testCase++) {
			boolean rowPartitioned = testCase % 2 == 0;
			AuxTopology auxiliary = AuxTopology.values()[(testCase / 2) % 3];
			boolean crossEntropy = testCase >= 6;
			Privacy privacy = (testCase / 3) % 2 == 0
				? Privacy.PUBLIC : Privacy.PRIVATE_AGGREGATE;
			int rows = 4 + 2 * random.nextInt(3);
			int cols = auxiliary == AuxTopology.SAME_OWNER
				? rows : 4 + 2 * random.nextInt(3);
			int rank = 2 + random.nextInt(2);
			assertScriptParity(privacy, randomizedWeightedScript(rows, cols, rank,
				rowPartitioned, auxiliary, crossEntropy), auxiliary == AuxTopology.DIFFERENT_POOLS);
		}
	}

	@Test(timeout = 30000)
	public void parsedIndependentAxesRemainFactorizedThroughFullClosure() throws Exception {
		AnalysisFixture explicit = fixtureForScript(Privacy.PUBLIC, false,
			multiAlternativeWeightedScript());
		AnalysisFixture compact = fixtureForScript(Privacy.PUBLIC, true,
			multiAlternativeWeightedScript());
		long logicalSize = compact.analysis().candidateRuleFacts().candidateRelations().stream()
			.filter(relation -> compact.analysis().hop(relation.parent()).orElse(null)
				instanceof QuaternaryOp)
			.map(CandidateRuleRelation::logicalSize)
			.reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add).longValueExact();
		Assert.assertTrue("parsed weighted fixture must retain multiple relation members",
			logicalSize > 1);
		assertFixtureParity(explicit, compact, Privacy.PUBLIC, true);
	}

	private enum AuxTopology { LOCAL, DIFFERENT_POOLS, SAME_OWNER }

	private static void assertFullClosureParity(Privacy privacy, boolean crossEntropy) throws Exception {
		assertFullClosureParity(privacy, crossEntropy, false);
	}

	private static void assertFullClosureParity(Privacy privacy, boolean crossEntropy,
		boolean federatedAuxiliaries) throws Exception {
		AnalysisFixture explicitFixture = fixtureForTest(
			privacy, false, crossEntropy, federatedAuxiliaries);
		AnalysisFixture compactFixture = fixtureForTest(
			privacy, true, crossEntropy, federatedAuxiliaries);
		assertFixtureParity(explicitFixture, compactFixture, privacy, federatedAuxiliaries);
	}

	private static void assertScriptParity(Privacy privacy, String script,
		boolean federatedAuxiliaries) throws Exception {
		assertFixtureParity(fixtureForScript(privacy, false, script),
			fixtureForScript(privacy, true, script), privacy, federatedAuxiliaries);
	}

	private static void assertUnsupportedFallbackParity(Privacy privacy, String script)
		throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		PlacementAnalysis explicit = new NeutralPlacementGraphBuilder(null,
			new SearchSpaceMetrics(), true, true, false).buildAnalysis(program);
		PlacementAnalysis compact = new NeutralPlacementGraphBuilder(null,
			new SearchSpaceMetrics(), true, true, true).buildAnalysis(program);
		Assert.assertTrue("unsupported weighted shape must not publish a relation",
			compact.candidateRuleFacts().candidateRelations().stream().noneMatch(relation ->
				compact.hop(relation.parent()).orElse(null) instanceof QuaternaryOp));
		Assert.assertEquals(signatures(explicit.candidateRuleFacts().orderedFacts()),
			signatures(compact.candidateRuleFacts().orderedFacts()));
		Assert.assertEquals(explicit.graph().relocationActions().stream()
				.map(action -> action.normalizedSignature()).sorted().toList(),
			compact.graph().relocationActions().stream()
				.map(action -> action.normalizedSignature()).sorted().toList());
	}

	private static void assertFixtureParity(AnalysisFixture explicitFixture,
		AnalysisFixture compactFixture, Privacy privacy, boolean federatedAuxiliaries) {
		PlacementAnalysis explicit = explicitFixture.analysis();
		PlacementAnalysis compact = compactFixture.analysis();
		List<CandidateRuleFact> explicitWeighted = weightedFacts(explicit);
		Assert.assertFalse(explicitWeighted.isEmpty());

		List<CandidateRuleRelation> relations = compact.candidateRuleFacts().candidateRelations().stream()
			.filter(relation -> compact.hop(relation.parent()).orElse(null) instanceof QuaternaryOp)
			.toList();
		Assert.assertFalse(relations.isEmpty());
		Assert.assertTrue(relations.stream().flatMap(relation -> relation.regions().stream())
			.allMatch(CandidateRuleRelation.ConditionalRegion::hasConditionedEmissions));
		Assert.assertEquals(0, relations.stream()
			.mapToInt(CandidateRuleRelation::materializedMemberCount).sum());

		List<CandidateRuleFact> restored = new ArrayList<>(weightedFacts(compact));
		for(CpRuleFamily family : compact.candidateRuleFacts().cpFamilies())
			if(compact.hop(family.parent()).orElse(null) instanceof QuaternaryOp)
				expandFamily(family, 0, new ArrayList<>(), restored);
		for(CandidateRuleRelation relation : relations)
			for(CandidateRuleRelation.ConditionalRegion region : relation.regions())
				expandRelation(relation, region, 0, new ArrayList<>(), restored);

		Map<String,String> explicitSignatures = signatures(explicitWeighted);
		Map<String,String> restoredSignatures = signatures(restored);
		Assert.assertEquals(explicitSignatures.keySet(), restoredSignatures.keySet());
		Assert.assertEquals(explicitSignatures, restoredSignatures);
		Assert.assertEquals("relation closure must preserve the complete relocation-action authority",
			explicit.graph().relocationActions().stream()
				.map(action -> action.normalizedSignature()).sorted().toList(),
			compact.graph().relocationActions().stream()
				.map(action -> action.normalizedSignature()).sorted().toList());
		if(!federatedAuxiliaries)
			Assert.assertFalse("CP-only auxiliaries must exercise relocation-action projection",
				explicit.graph().relocationActions().isEmpty());

		List<String> keys = new ArrayList<>(explicitSignatures.keySet());
		Random random = new Random(0x57_10_55L + privacy.ordinal());
		for(int sample = 0; sample < Math.min(64, keys.size()); sample++) {
			String key = keys.get(random.nextInt(keys.size()));
			Assert.assertEquals(explicitSignatures.get(key), restoredSignatures.get(key));
		}
		long explicitDirectBindings = directBindingCount(explicitWeighted);
		long restoredDirectBindings = directBindingCount(restored);
		Assert.assertEquals("closed weighted members must preserve exact DIRECT input authority",
			explicitDirectBindings, restoredDirectBindings);
		if(!federatedAuxiliaries)
			Assert.assertTrue("local-auxiliary fixture must exercise DIRECT input authority",
				restoredDirectBindings > 0);
		long storedChoices = relations.stream().flatMap(relation -> relation.regions().stream())
			.mapToLong(CandidateRuleRelation.ConditionalRegion::storedEmissionChoiceCount).sum();
		long restoredClauses = restored.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.mapToLong(realization -> realization.supportClauses().size()).sum();
		System.out.println("WEIGHTED_CLOSED_RELATION_WORK|privacy=" + privacy
			+ "|explicitClosedFacts=" + explicitWeighted.size()
			+ "|publishedRelations=" + relations.size()
			+ "|storedBindingChoices=" + storedChoices
			+ "|restoredSupportClauses=" + restoredClauses
			+ "|explicitCreated=" + explicitFixture.creationSnapshot()
			+ "|relationCreated=" + compactFixture.creationSnapshot());
	}

	private static long directBindingCount(List<CandidateRuleFact> facts) {
		return facts.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
				.flatMap(emission -> emission.realizations().stream())
				.flatMap(realization -> realization.supportClauses().stream())
				.flatMap(clause -> clause.inputBindings().stream())
				.filter(binding -> binding.kind() == CandidateInputBindingKind.DIRECT)
				.count();
	}

	private static Map<String,String> signatures(List<CandidateRuleFact> facts) {
		Map<String,String> result = new LinkedHashMap<>();
		facts.stream().sorted(java.util.Comparator.comparing(fact -> fact.key().normalizedSignature()))
			.forEach(fact -> result.put(fact.key().normalizedSignature(),
				fact.capability() + "|" + fact.shapeProof() + "|" + fact.profile()
					+ "|" + fact.allowedEmissionFacts().stream()
						.map(PlacementAnalysis.CandidateEmissionFact::normalizedSignature).toList()));
		return result;
	}

	private static List<CandidateRuleFact> weightedFacts(PlacementAnalysis analysis) {
		return analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> analysis.hop(fact.key().parentOccurrence()).orElse(null)
				instanceof QuaternaryOp)
			.toList();
	}

	private static void expandFamily(CpRuleFamily family, int position,
		List<CandidateInputState> inputs, List<CandidateRuleFact> output) {
		if(position == family.axes().size()) {
			output.add(family.requireExact(inputs));
			return;
		}
		for(CandidateInputState input : family.axes().get(position)) {
			inputs.add(input);
			expandFamily(family, position + 1, inputs, output);
			inputs.remove(inputs.size() - 1);
		}
	}

	private static void expandRelation(CandidateRuleRelation relation,
		CandidateRuleRelation.ConditionalRegion region, int position,
		List<CandidateInputState> inputs, List<CandidateRuleFact> output) {
		if(position == region.axes().size()) {
			output.add(relation.requireExact(inputs));
			return;
		}
		for(CandidateInputState input : region.axes().get(position)) {
			inputs.add(input);
			expandRelation(relation, region, position + 1, inputs, output);
			inputs.remove(inputs.size() - 1);
		}
	}

	public static PlacementAnalysis analysisForTest(Privacy privacy, boolean enableFedRelations)
		throws Exception {
		return fixtureForTest(privacy, enableFedRelations).analysis();
	}

	public static PlacementAnalysis analysisForTest(Privacy privacy, boolean enableFedRelations,
		boolean crossEntropy) throws Exception {
		return fixtureForTest(privacy, enableFedRelations, crossEntropy).analysis();
	}

	public static PlacementAnalysis multiAlternativeAnalysisForTest(boolean enableFedRelations)
		throws Exception {
		return fixtureForScript(Privacy.PUBLIC, enableFedRelations,
			multiAlternativeWeightedScript()).analysis();
	}

	public static AnalysisFixture fixtureForTest(Privacy privacy, boolean enableFedRelations)
		throws Exception {
		return fixtureForTest(privacy, enableFedRelations, false);
	}

	private static AnalysisFixture fixtureForTest(Privacy privacy, boolean enableFedRelations,
		boolean crossEntropy) throws Exception {
		return fixtureForTest(privacy, enableFedRelations, crossEntropy, false);
	}

	private static AnalysisFixture fixtureForTest(Privacy privacy, boolean enableFedRelations,
		boolean crossEntropy, boolean federatedAuxiliaries) throws Exception {
		return fixtureForScript(privacy, enableFedRelations,
			standardScript(crossEntropy, federatedAuxiliaries));
	}

	private static AnalysisFixture fixtureForScript(Privacy privacy, boolean enableFedRelations,
		String script) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder(null, metrics, true, true,
			enableFedRelations).buildAnalysis(program);
		return new AnalysisFixture(analysis, metrics.objectCreationSnapshot());
	}

	public record AnalysisFixture(PlacementAnalysis analysis,
		SearchSpaceMetrics.ObjectCreationSnapshot creationSnapshot) { }

	private static DMLProgram compile(boolean crossEntropy) throws Exception {
		return compile(crossEntropy, false);
	}

	private static DMLProgram compile(boolean crossEntropy, boolean federatedAuxiliaries)
		throws Exception {
		return compile(standardScript(crossEntropy, federatedAuxiliaries));
	}

	private static String standardScript(boolean crossEntropy, boolean federatedAuxiliaries) {
		return "X=federated(addresses=list(\"localhost:13001/X\",\"localhost:13002/X\"),"
			+ "ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));"
			+ (federatedAuxiliaries
				? "U=federated(addresses=list(\"localhost:14001/U\",\"localhost:14002/U\"),"
					+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
					+ "V=federated(addresses=list(\"localhost:15001/V\",\"localhost:15002/V\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));"
				: "U=matrix(1,rows=8,cols=2);V=matrix(1,rows=4,cols=2);")
			+ (crossEntropy ? "sl=sum(X*log(U%*%t(V)+0.1));"
				: "W=matrix(1,rows=8,cols=4);sl=sum(W*(X-U%*%t(V))^2);")
			+ "print(sl);";
	}

	private static String repeatedOwnerScript() {
		return "X=federated(addresses=list(\"localhost:13001/X\",\"localhost:13002/X\"),"
			+ "ranges=list(list(0,0),list(4,8),list(4,0),list(8,8)));"
			+ "Z=matrix(1,rows=8,cols=8);W=matrix(1,rows=8,cols=8);"
			+ "sl=sum(W*(X-Z%*%t(Z))^2);print(sl);";
	}

	private static String functionBoundaryScript() {
		return "weighted=function(matrix[double] X,matrix[double] U,matrix[double] V,"
			+ "matrix[double] W) return(double s) {s=sum(W*(X-U%*%t(V))^2);};"
			+ "X=federated(addresses=list(\"localhost:13001/X\",\"localhost:13002/X\"),"
			+ "ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));"
			+ "U=matrix(1,rows=8,cols=2);V=matrix(1,rows=4,cols=2);"
			+ "W=matrix(1,rows=8,cols=4);sl=weighted(X,U,V,W);print(sl);";
	}

	private static String dynamicSourceScript() {
		return "X=federated(addresses=list(\"localhost:13001/X\",\"localhost:13002/X\"),"
			+ "ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));"
			+ "Y=removeEmpty(target=X,margin=\"rows\");n=nrow(Y);"
			+ "U=matrix(1,rows=n,cols=2);V=matrix(1,rows=4,cols=2);"
			+ "W=matrix(1,rows=n,cols=4);sl=sum(W*(Y-U%*%t(V))^2);print(sl);";
	}

	private static String randomizedWeightedScript(int rows, int cols, int rank,
		boolean rowPartitioned, AuxTopology auxiliary, boolean crossEntropy) {
		String xRanges = rowPartitioned
			? "list(list(0,0),list(" + rows / 2 + ',' + cols + "),list(" + rows / 2
				+ ",0),list(" + rows + ',' + cols + "))"
			: "list(list(0,0),list(" + rows + ',' + cols / 2 + "),list(0," + cols / 2
				+ "),list(" + rows + ',' + cols + "))";
		StringBuilder script = new StringBuilder("X=federated(addresses=list(\"localhost:13001/X\","
			+ "\"localhost:13002/X\"),ranges=").append(xRanges).append(");");
		switch(auxiliary) {
			case LOCAL -> script.append("U=matrix(1,rows=").append(rows).append(",cols=")
				.append(rank).append(");V=matrix(1,rows=").append(cols).append(",cols=")
				.append(rank).append(");");
			case DIFFERENT_POOLS -> script
				.append("U=federated(addresses=list(\"localhost:14001/U\",\"localhost:14002/U\"),")
				.append("ranges=list(list(0,0),list(").append(rows / 2).append(',').append(rank)
				.append("),list(").append(rows / 2).append(",0),list(").append(rows).append(',')
				.append(rank).append(")));")
				.append("V=federated(addresses=list(\"localhost:15001/V\",\"localhost:15002/V\"),")
				.append("ranges=list(list(0,0),list(").append(cols / 2).append(',').append(rank)
				.append("),list(").append(cols / 2).append(",0),list(").append(cols).append(',')
				.append(rank).append(")));");
			case SAME_OWNER -> script.append("Z=matrix(1,rows=").append(rows).append(",cols=")
				.append(rank).append(");U=Z;V=Z;");
		}
		if(crossEntropy)
			script.append("sl=sum(X*log(U%*%t(V)+0.1));");
		else
			script.append("W=matrix(1,rows=").append(rows).append(",cols=").append(cols)
				.append(");sl=sum(W*(X-U%*%t(V))^2);");
		return script.append("print(sl);").toString();
	}

	private static String multiAlternativeWeightedScript() {
		return "X=federated(addresses=list(\"localhost:13001/X\",\"localhost:13002/X\"),"
			+ "ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));"
			+ "U0=federated(addresses=list(\"localhost:14001/U\",\"localhost:14002/U\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));U=abs(U0);"
			+ "V0=federated(addresses=list(\"localhost:15001/V\",\"localhost:15002/V\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));V=abs(V0);"
			+ "W0=federated(addresses=list(\"localhost:16001/W\",\"localhost:16002/W\"),"
			+ "ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));W=abs(W0);"
			+ "sl=sum(W*(X-U%*%t(V))^2);print(sl);";
	}

	private static String failureSignature(Privacy privacy, boolean enableFedRelations,
		String script) throws Exception {
		try {
			fixtureForScript(privacy, enableFedRelations, script);
			return "";
		}
		catch(RuntimeException failure) {
			return failure.getClass().getName() + '|' + failure.getMessage();
		}
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
