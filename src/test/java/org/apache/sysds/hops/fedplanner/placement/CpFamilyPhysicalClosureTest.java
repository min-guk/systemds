/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Full-closure and symbolic transition coverage for scalar CP-family revisions. */
public class CpFamilyPhysicalClosureTest {
	private static final String FINGERPRINT = "cp-family-physical-closure";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");
	private static final CandidateInputState ABSENT = CandidateInputState.absentLocal();
	private static final CandidateInputState ROW = CandidateInputState.present(FType.ROW);
	private static final CandidateInputState COL = CandidateInputState.present(FType.COL);

	@Test(timeout = 30000)
	public void privateAggregateDynamicDimensionsRemainExactAcrossRelationMode() throws Exception {
		PlacementAnalysis explicit = analyze(dynamicDimensionScript(), false);
		PlacementAnalysis compact = analyze(dynamicDimensionScript(), true);

		for(OpOp1 operation : List.of(OpOp1.NROW, OpOp1.NCOL)) {
			var explicitOccurrence = occurrence(explicit, operation);
			var compactOccurrence = occurrence(compact, operation);
			Assert.assertEquals(Privacy.PUBLIC, explicit.requirePrivacy(explicitOccurrence.key()));
			Assert.assertEquals(Privacy.PUBLIC, compact.requirePrivacy(compactOccurrence.key()));
			Assert.assertTrue(explicit.isCoordinatorMetadataOnlyInput(
				explicit.compiledInputEdge(explicitOccurrence.key(), 0).orElseThrow()));
			Assert.assertTrue(compact.isCoordinatorMetadataOnlyInput(
				compact.compiledInputEdge(compactOccurrence.key(), 0).orElseThrow()));

			List<CandidateRuleFact> explicitFacts = explicit.candidateRuleFacts()
				.orderedFactsForParent(explicitOccurrence.key());
			List<CandidateRuleFact> compactFacts = compact.candidateRuleFacts()
				.orderedFactsForParent(compactOccurrence.key());
			Assert.assertTrue("the exact baseline must not publish a CP family",
				explicit.candidateRuleFacts().cpFamiliesForParent(explicitOccurrence.key()).isEmpty());
			Assert.assertTrue("PRIVATE_AGGREGATE closure must not widen metadata input authority"
				+ " merely because compact relations are enabled",
				compact.candidateRuleFacts().cpFamiliesForParent(compactOccurrence.key()).isEmpty());
			Assert.assertEquals(1, explicitFacts.size());
			Assert.assertEquals(1, compactFacts.size());
			Assert.assertEquals(factsByInputs(explicitFacts), factsByInputs(compactFacts));
			Assert.assertEquals(explicit.graph().node(explicitOccurrence.key()).orElseThrow().legalAlternatives(),
				compact.graph().node(compactOccurrence.key()).orElseThrow().legalAlternatives());
		}
	}

	@Test
	public void familyNarrowsAcrossTwoInputsThenBecomesOneExactRow() {
		CompiledHopKey owner = key("sequential-two-input-refinement");
		Header header = header();
		CpRuleFamily original = family(owner,
			List.of(List.of(ABSENT, ROW), List.of(ROW, COL)), header);
		CpRuleFamily afterFirstInput = family(owner,
			List.of(List.of(ROW), List.of(ROW, COL)), header);

		Assert.assertTrue(CandidateFamilyRefinement.covers(original, List.of(afterFirstInput), List.of(),
			List.of(domain(FType.ROW), domain(FType.ROW, FType.COL)), Set.of(0)));
		CandidateRuleFact singleton = fact(owner, List.of(ROW, COL), header);
		Assert.assertTrue(CandidateFamilyRefinement.covers(afterFirstInput, List.of(), List.of(singleton),
			List.of(domain(FType.ROW), domain(FType.COL)), Set.of(1)));
		Assert.assertFalse("the second input cannot disappear without its own exact refinement proof",
			CandidateFamilyRefinement.covers(afterFirstInput, List.of(), List.of(singleton),
				List.of(domain(FType.ROW), domain(FType.COL)), Set.of(0)));
		Assert.assertEquals(0, original.materializedMemberCount());
		Assert.assertEquals(0, afterFirstInput.materializedMemberCount());
	}

	private static PlacementAnalysis analyze(String script, boolean enableRelations) throws Exception {
		DMLProgram program = compile(script);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder(null, new SearchSpaceMetrics(), true, true,
			enableRelations).buildAnalysis(program);
	}

	private static PlacementAnalysis.HopOccurrenceProjection occurrence(
		PlacementAnalysis analysis, OpOp1 operation) {
		return analysis.compiledHopOccurrences().stream()
			.filter(candidate -> candidate.hop() instanceof UnaryOp unary
				&& unary.getOp() == operation)
			.findFirst().orElseThrow();
	}

	private static Map<List<CandidateInputState>,FactView> factsByInputs(
		List<CandidateRuleFact> facts) {
		Map<List<CandidateInputState>,FactView> result = new LinkedHashMap<>();
		for(CandidateRuleFact fact : facts)
			Assert.assertNull(result.put(fact.key().orderedInputs(), FactView.of(fact)));
		return result;
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

	private static String dynamicDimensionScript() {
		return "X=federated(addresses=list(\"localhost:13001/X\",\"localhost:13002/X\"),"
			+ "ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));"
			+ "Y=removeEmpty(target=X,margin=\"rows\");r=nrow(Y);c=ncol(Y);print(r+c);";
	}

	private static CpRuleFamily family(CompiledHopKey owner,
		List<List<CandidateInputState>> axes, Header header) {
		return new CpRuleFamily(owner, axes, header.capability(), header.shapeProof(),
			header.profile(), header.emission());
	}

	private static CandidateRuleFact fact(CompiledHopKey owner,
		List<CandidateInputState> inputs, Header header) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, inputs),
			CandidateEvaluationStatus.AVAILABLE, header.capability(), header.shapeProof(),
			header.profile(), List.of(header.emission()), "");
	}

	private static Header header() {
		CandidateCapabilityFact capability = new CandidateCapabilityFact(OpCategory.OTHER,
			"metadata-family", ExecType.CP, FederatedOutput.LOUT, null, ReasonCode.NO_RULE,
			"", List.of());
		CandidateEmissionFact emission = new CandidateEmissionFact(
			new PlacementEmissionState(new PlacementState(
				ExecType.CP, FederatedOutput.LOUT, null, false), false), null);
		return new Header(capability, new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emission);
	}

	private static List<FType> domain(FType... types) {
		return Collections.unmodifiableList(java.util.Arrays.asList(types));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", REGION, id, id);
	}

	private record Header(CandidateCapabilityFact capability, CandidateShapeProofFact shapeProof,
		CandidateProfileFact profile, CandidateEmissionFact emission) { }

	private record FactView(CandidateEvaluationStatus status, CandidateCapabilityFact capability,
		CandidateShapeProofFact shapeProof, CandidateProfileFact profile,
		List<CandidateEmissionFact> emissions, String failureCode) {
		private static FactView of(CandidateRuleFact fact) {
			return new FactView(fact.status(), fact.capability(), fact.shapeProof(), fact.profile(),
				fact.allowedEmissionFacts(), fact.failureCode());
		}
	}
}
