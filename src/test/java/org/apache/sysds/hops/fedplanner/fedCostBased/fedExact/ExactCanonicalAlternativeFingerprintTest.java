/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactCanonicalAlternativeFingerprintTest {
	@Test
	public void ownedLazyAndEagerModelsUseSameCanonicalRecipeWithoutSignatureReplay() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile("B-21"));
		ExactPhysicalModel lazy = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel eager = ExactPhysicalModel.buildWithEagerAlternativeSignaturesForTest(analysis);
		Assert.assertEquals(ExactPhysicalCostModel.physicalModelAlternativeDagFingerprintForTest(eager),
			ExactPhysicalCostModel.physicalModelAlternativeDagFingerprintForTest(lazy));

		var diagnostics = new PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics();
		var fingerprint = new PhysicalSemanticDagFingerprint(diagnostics);
		var writer = new ExactPhysicalCostModel.FingerprintWriter();
		fingerprint.appendModelAlternativeOccurrences(writer, lazy);
		var snapshot = diagnostics.snapshotAndClear();
		Assert.assertEquals("owned model path replayed a normalized signature", 0,
			snapshot.normalizedOccurrences());
		Assert.assertFalse(writer.finish().isEmpty());
	}

	@Test
	public void genericCanonicalCopiesAgreeAndArbitraryUtf16RemainsBound() throws Exception {
		ExactPhysicalModel model = model("B-11");
		Alternative generated = firstAlternative(model);
		String canonical = independentRecipe(generated);
		Assert.assertEquals(canonical, generated.signature());
		Alternative flat = copy(generated, NormalizedText.literal(canonical));
		int split = Math.max(1, canonical.length() / 2);
		Alternative rope = copy(generated, new NormalizedTextBuilder()
			.append(canonical.substring(0, split)).append(canonical.substring(split)).build());
		String expected = ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(generated);
		Assert.assertEquals(expected, ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(flat));
		Assert.assertEquals(expected, ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(rope));

		String custom = canonical + "|custom=\u0000\ud800Aa";
		Alternative rawFlat = copy(generated, NormalizedText.literal(custom));
		Alternative rawRope = copy(generated, new NormalizedTextBuilder()
			.append(canonical).append("|custom=\u0000").append("\ud800").append("Aa").build());
		Assert.assertEquals(ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(rawFlat),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(rawRope));
		Assert.assertFalse(expected.equals(
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(rawFlat)));

		var diagnostics = new PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics();
		var fingerprint = new PhysicalSemanticDagFingerprint(diagnostics);
		var writer = new ExactPhysicalCostModel.FingerprintWriter();
		fingerprint.appendAlternativeOccurrence(writer, rawRope);
		Assert.assertEquals(1, diagnostics.snapshotAndClear().normalizedOccurrences());
	}

	@Test
	public void genericAndOwnedMemoVisitOrderCannotChangeDigest() throws Exception {
		ExactPhysicalModel model = model("B-22");
		Alternative generated = firstAlternative(model);
		String freshModel = modelFingerprint(new PhysicalSemanticDagFingerprint(), model);
		String freshGeneric = alternativeFingerprint(new PhysicalSemanticDagFingerprint(), generated);

		PhysicalSemanticDagFingerprint genericFirst = new PhysicalSemanticDagFingerprint();
		Assert.assertEquals(freshGeneric, alternativeFingerprint(genericFirst, generated));
		Assert.assertEquals(freshModel, modelFingerprint(genericFirst, model));
		PhysicalSemanticDagFingerprint modelFirst = new PhysicalSemanticDagFingerprint();
		Assert.assertEquals(freshModel, modelFingerprint(modelFirst, model));
		Assert.assertEquals(freshGeneric, alternativeFingerprint(modelFirst, generated));
	}

	@Test
	public void independentRecipesCoverEveryGeneratedAuthorityKindAndTypedMutation() throws Exception {
		EnumSet<AuthorityKind> seen = EnumSet.noneOf(AuthorityKind.class);
		boolean derived = false;
		boolean relocation = false;
		for(String id : List.of("B-11", "B-21", "B-22", "RELOCATION", "LOOP", "WEIGHTED")) {
			for(var domain : model(id).domains()) {
				for(Alternative alternative : domain.alternatives()) {
					seen.add(alternative.authorityKind());
					Assert.assertEquals(independentRecipe(alternative), alternative.signature());
					derived |= alternative.derivedFoutAction() != null;
					relocation |= alternative.relocationAction() != null
						|| alternative.inputAuthorities().stream().anyMatch(a -> a.relocationAction() != null);
					Alternative changed = new Alternative(alternative.decision(), alternative.state(),
						alternative.authorityKind() == AuthorityKind.LEGAL_SINGLETON
							? AuthorityKind.SYNTHETIC_BOUNDARY : AuthorityKind.LEGAL_SINGLETON,
						alternative.candidateRule(), alternative.candidateEmission(),
						alternative.executionRule(), alternative.executionEmission(), alternative.durableAnchor(),
						alternative.relocationAction(), alternative.derivedFoutAction(), alternative.orderedInputs(),
						alternative.inputAuthorities(), alternative.realization(), alternative.supportClause(),
						alternative.normalizedSignature());
					Assert.assertFalse(ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(alternative)
						.equals(ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(changed)));
				}
			}
		}
		Alternative base = firstAlternative(model("B-11"));
		for(AuthorityKind kind : AuthorityKind.values()) {
			if(kind == AuthorityKind.CAPTURED_RULE || kind == AuthorityKind.CP_RULE_FAMILY)
				continue;
			Alternative placeholder = new Alternative(base.decision(), base.state(), kind,
				null, null, null, null, null, null, null, List.of(), List.of(), null, null, "placeholder");
			Alternative canonical = copy(placeholder,
				NormalizedText.literal(independentRecipe(placeholder)));
			Assert.assertEquals(canonical.normalizedSignature(),
				ExactPhysicalModel.canonicalAlternativeSignature(canonical));
			seen.add(kind);
		}
		Assert.assertEquals(EnumSet.allOf(AuthorityKind.class), seen);
		Assert.assertTrue("derived output action fixture missing", derived);
		Assert.assertTrue("relocation fixture missing", relocation);

		Alternative unusualCaptured = new Alternative(base.decision(), base.state(),
			AuthorityKind.CAPTURED_RULE, null, null, null, null, null, null, null,
			List.of(), List.of(), null, null, "accepted-custom-captured");
		Assert.assertNull(ExactPhysicalModel.canonicalAlternativeSignature(unusualCaptured));
		Assert.assertFalse(ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(unusualCaptured)
			.isEmpty());
	}

	@Test
	public void everyClassifierShapeOperandFallsBackToRawWhenSignatureIsRetained() throws Exception {
		ExactPhysicalModel model = model("RELOCATION");
		Alternative base = model.domains().stream().flatMap(domain -> domain.alternatives().stream())
			.filter(alternative -> alternative.authorityKind() == AuthorityKind.CAPTURED_RULE)
			.filter(alternative -> alternative.derivedFoutAction() == null)
			.filter(alternative -> !alternative.orderedInputs().isEmpty())
			.filter(alternative -> alternative.inputAuthorities().stream()
				.anyMatch(authority -> authority.relocationAction() != null))
			.findFirst().orElseThrow();
		var action = base.inputAuthorities().stream()
			.map(ExactPhysicalModel.InputAuthority::relocationAction)
			.filter(java.util.Objects::nonNull).findFirst().orElseThrow();
		String baseRecipe = independentRecipe(base);
		Alternative baseFlat = copy(base, NormalizedText.literal(baseRecipe));
		Alternative baseRope = copy(base, new NormalizedTextBuilder()
			.append(baseRecipe.substring(0, baseRecipe.length() / 2))
			.append(baseRecipe.substring(baseRecipe.length() / 2)).build());
		String baseDigest = ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(base);
		Assert.assertEquals(baseDigest,
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(baseFlat));
		Assert.assertEquals(baseDigest,
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(baseRope));
		Assert.assertEquals(IndependentPhysicalSemanticDagOracle.alternativeValue(base), baseDigest);

		int authorityIndex = java.util.stream.IntStream.range(0, base.inputAuthorities().size())
			.filter(index -> base.inputAuthorities().get(index).relocationAction() != null)
			.findFirst().orElseThrow();
		var originalAuthority = base.inputAuthorities().get(authorityIndex);
		List<ExactPhysicalModel.InputAuthority> changedAuthorities =
			new ArrayList<>(base.inputAuthorities());
		changedAuthorities.set(authorityIndex, new ExactPhysicalModel.InputAuthority(
			originalAuthority.inputPosition() + 17, originalAuthority.kind(),
			originalAuthority.expectedFType(), originalAuthority.sourceDecision(),
			originalAuthority.relocationAction()));
		Alternative changedAuthority = canonicalized(new Alternative(base.decision(), base.state(),
			base.authorityKind(), base.candidateRule(), base.candidateEmission(), null, null, null,
			null, base.derivedFoutAction(), base.orderedInputs(), changedAuthorities,
			base.realization(), base.supportClause(), "placeholder"));
		String changedAuthorityDigest =
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(changedAuthority);
		Assert.assertEquals(IndependentPhysicalSemanticDagOracle.alternativeValue(changedAuthority),
			changedAuthorityDigest);
		Assert.assertFalse(baseDigest.equals(changedAuthorityDigest));

		var changedState = model.domains().stream().flatMap(domain -> domain.alternatives().stream())
			.map(Alternative::state).filter(state -> !state.equals(base.state())).findFirst().orElseThrow();
		Alternative stateMutation = canonicalized(new Alternative(base.decision(), changedState,
			base.authorityKind(), base.candidateRule(), base.candidateEmission(), null, null, null,
			null, base.derivedFoutAction(), base.orderedInputs(), base.inputAuthorities(),
			base.realization(), base.supportClause(), "placeholder"));
		String stateDigest = ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(stateMutation);
		Assert.assertEquals(IndependentPhysicalSemanticDagOracle.alternativeValue(stateMutation), stateDigest);
		Assert.assertFalse(baseDigest.equals(stateDigest));
		List<Alternative> mutations = List.of(
			new Alternative(base.decision(), base.state(), base.authorityKind(), null,
				base.candidateEmission(), null, null, null, null, null, base.orderedInputs(),
				base.inputAuthorities(), base.realization(), base.supportClause(), base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				null, null, null, null, null, null, base.orderedInputs(), base.inputAuthorities(),
				base.realization(), base.supportClause(), base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				base.candidateEmission(), base.candidateRule(), null, null, null, null,
				base.orderedInputs(), base.inputAuthorities(), base.realization(), base.supportClause(),
				base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				base.candidateEmission(), null, base.candidateEmission(), null, null, null,
				base.orderedInputs(), base.inputAuthorities(), base.realization(), base.supportClause(),
				base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				base.candidateEmission(), null, null, action.key().durableAnchor(), null, null,
				base.orderedInputs(), base.inputAuthorities(), base.realization(), base.supportClause(),
				base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				base.candidateEmission(), null, null, null, action, null, base.orderedInputs(),
				base.inputAuthorities(), base.realization(), base.supportClause(), base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				base.candidateEmission(), null, null, null, null, null, List.of(),
				base.inputAuthorities(), base.realization(), base.supportClause(), base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				base.candidateEmission(), null, null, null, null, null, base.orderedInputs(), List.of(),
				base.realization(), base.supportClause(), base.normalizedSignature()),
			new Alternative(base.decision(), base.state(), base.authorityKind(), base.candidateRule(),
				base.candidateEmission(), null, null, null, null, null, base.orderedInputs(),
				base.inputAuthorities(), null, null, base.normalizedSignature()));
		mutations = new ArrayList<>(mutations);
		mutations.add(new Alternative(base.decision(), base.state(), AuthorityKind.SYNTHETIC_BOUNDARY,
			base.candidateRule(), base.candidateEmission(), null, null, null, null, null,
			base.orderedInputs(), base.inputAuthorities(), base.realization(), base.supportClause(),
			base.normalizedSignature()));
		String canonical = ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(base);
		for(Alternative mutation : mutations) {
			NormalizedText recipe = ExactPhysicalModel.canonicalAlternativeSignature(mutation);
			Assert.assertTrue(recipe == null || !recipe.equals(mutation.normalizedSignature()));
			var diagnostics = new PhysicalSemanticDagFingerprint.NormalizedTextSharingDiagnostics();
			var fingerprint = new PhysicalSemanticDagFingerprint(diagnostics);
			var writer = new ExactPhysicalCostModel.FingerprintWriter();
			fingerprint.appendAlternativeOccurrence(writer, mutation);
			Assert.assertEquals(1, diagnostics.snapshotAndClear().normalizedOccurrences());
			Assert.assertFalse(canonical.equals(
				ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(mutation)));
		}
	}

	private static ExactPhysicalModel model(String id) throws Exception {
		DMLProgram program = "RELOCATION".equals(id) ? relocationFixture()
			: "LOOP".equals(id) ? loopFixture()
			: "WEIGHTED".equals(id) ? weightedFixture()
			: CampaignBG014HermeticPlannerFixtureFactory.compile(id);
		return ExactPhysicalModel.build(new NeutralPlacementGraphBuilder().buildAnalysis(program));
	}

	private static DMLProgram weightedFixture() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(8,4)));U=matrix(1,rows=8,cols=2);"
			+ "V=matrix(1,rows=4,cols=2);W=matrix(1,rows=8,cols=4);"
			+ "sl=sum(W*(X-U%*%t(V))^2);print(sl);";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		return program;
	}

	private static DMLProgram loopFixture() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));"
			+ "p=rand(rows=8,cols=2,seed=7);for(i in 1:3){p=p;}print(sum(X));";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program,
			Privacy.PRIVATE_AGGREGATE);
		return program;
	}

	private static DMLProgram relocationFixture() throws Exception {
		String ranges = "list(list(0,0),list(2,2),list(2,0),list(4,2))";
		String script = "A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),"
			+ "ranges=" + ranges + ");\n"
			+ "B=federated(addresses=list(\"localhost:2234/B1\",\"localhost:2235/B2\"),"
			+ "ranges=" + ranges + ");\nC=A+B;\n"
			+ "write(C,\"/tmp/canonical-alternative-fingerprint-C\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program,
			Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		return program;
	}

	private static Alternative firstAlternative(ExactPhysicalModel model) {
		return model.domains().stream().flatMap(domain -> domain.alternatives().stream())
			.findFirst().orElseThrow();
	}

	private static Alternative copy(Alternative a, NormalizedText signature) {
		return new Alternative(a.decision(), a.state(), a.authorityKind(), a.candidateRule(),
			a.candidateEmission(), a.executionRule(), a.executionEmission(), a.durableAnchor(),
			a.relocationAction(), a.derivedFoutAction(), a.orderedInputs(), a.inputAuthorities(),
			a.realization(), a.supportClause(), a.compactSupport(), a.cpRuleFamily(), signature);
	}

	private static Alternative canonicalized(Alternative alternative) {
		return copy(alternative, NormalizedText.literal(independentRecipe(alternative)));
	}

	private static String independentRecipe(Alternative a) {
		List<String> inputs = new ArrayList<>();
		for(var input : a.inputAuthorities())
			inputs.add(input.inputPosition() + ":" + input.kind() + ":"
				+ (input.expectedFType() == null ? "-" : input.expectedFType()) + ":"
				+ (input.sourceDecision() == null ? "-" : input.sourceDecision().normalizedSignature()) + ":"
				+ (input.relocationAction() == null ? "-" : input.relocationAction().normalizedSignature()));
		if(a.authorityKind() == AuthorityKind.CP_RULE_FAMILY)
			return "CP_RULE_FAMILY|" + a.state().normalizedSignature() + "|family="
				+ a.cpRuleFamily().normalizedSignature();
		if(a.authorityKind() == AuthorityKind.CAPTURED_RULE)
			return "CAPTURED|" + a.state().normalizedSignature() + "|rule="
				+ a.candidateRule().key().normalizedSignature() + "|emission="
				+ a.candidateEmission().selectionSignature() + "|realization="
				+ a.realization().key().normalizedSignature() + "|clause="
				+ a.supportClause().normalizedSignature() + "|foutMaterializationAction="
				+ (a.derivedFoutAction() == null ? "-" : a.derivedFoutAction().normalizedSignature())
				+ "|inputs=" + inputs;
		return a.authorityKind() + "|" + a.state().normalizedSignature()
			+ "|anchor=" + (a.durableAnchor() == null ? "-" : a.durableAnchor().normalizedSignature())
			+ "|action=" + (a.relocationAction() == null ? "-" : a.relocationAction().normalizedSignature())
			+ "|executionRule=" + (a.executionRule() == null ? "-" : a.executionRule().key().normalizedSignature())
			+ "|executionEmission=" + (a.executionEmission() == null ? "-" : a.executionEmission().selectionSignature())
			+ "|realization=" + (a.realization() == null ? "-" : a.realization().key().normalizedSignature())
			+ "|clause=" + (a.supportClause() == null ? "-" : a.supportClause().normalizedSignature())
			+ "|inputs=" + inputs;
	}

	private static String modelFingerprint(PhysicalSemanticDagFingerprint fingerprint,
		ExactPhysicalModel model) {
		var writer = new ExactPhysicalCostModel.FingerprintWriter();
		fingerprint.appendModelAlternativeOccurrences(writer, model);
		return writer.finish();
	}

	private static String alternativeFingerprint(PhysicalSemanticDagFingerprint fingerprint,
		Alternative alternative) {
		var writer = new ExactPhysicalCostModel.FingerprintWriter();
		fingerprint.appendAlternativeOccurrence(writer, alternative);
		return writer.finish();
	}
}
