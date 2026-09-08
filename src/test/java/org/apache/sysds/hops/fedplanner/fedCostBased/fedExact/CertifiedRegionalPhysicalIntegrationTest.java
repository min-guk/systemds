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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.Checkpoint;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.ExpansionPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.Options;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.StopReason;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Physical-model coverage for the certified/anytime Regional search envelope. */
public class CertifiedRegionalPhysicalIntegrationTest {
	private static final String PREFIX = CertifiedRegionalOptimizer.PROPERTY_PREFIX;
	private static final String PRIVATE_AGGREGATE_BRANCH_FIXTURE = String.join("\n",
		"A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
		"B=A+1;",
		"if(rand()>0.5) {",
		"  print(sum(B));",
		"} else {",
		"  print(sum(B*2));",
		"}") + "\n";

	@Test
	public void certificationPreservesRegionalIncumbentAndBoundsGlobalExact() throws Exception {
		Fixture fixture = fixture();
		Assert.assertTrue("fixture must exercise exact-only activation auxiliaries",
			fixture.surface().exactSolverVariables().size() > fixture.model().variables().size());
		LocalPhysicalOptimizer.Result regional = LocalPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), null, ignored -> { });
		ExactPhysicalOptimizer.Result exact = ExactPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Options certify = options(1, 1, 1, 1, false, false,
			ExpansionPolicy.STRUCTURAL, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		LocalPhysicalOptimizer.Result certified = LocalPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), certify, ignored -> { });

		Assert.assertEquals(regional.physicalResult().solverResult().assignmentInVariableOrder(),
			certified.physicalResult().solverResult().assignmentInVariableOrder());
		Assert.assertEquals(regional.physicalResult().canonicalObjectiveBits(),
			certified.physicalResult().canonicalObjectiveBits());
		Assert.assertNotNull(certified.certificate());
		Assert.assertEquals(StopReason.CERTIFIED, certified.certificate().stopReason());
		assertCertificateEnvelope(certified.certificate(), exact.solverResult().objective(), fixture);
	}

	@Test
	public void anytimeFullRegionReachesExactEndpointWithMonotoneCheckpoints() throws Exception {
		Fixture fixture = fixture();
		ExactPhysicalOptimizer.Result exact = ExactPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		int decisions = fixture.model().variables().size();
		Options anytime = options(1, 1, 1, Math.max(1, decisions), true, false,
			ExpansionPolicy.DISAGREEMENT, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		LocalPhysicalOptimizer.Result result = LocalPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), anytime, ignored -> { });

		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.certificate().stopReason());
		Assert.assertEquals(exact.canonicalObjectiveBits(),
			result.physicalResult().canonicalObjectiveBits());
		Assert.assertEquals(exact.solverResult().assignmentInVariableOrder(),
			result.physicalResult().solverResult().assignmentInVariableOrder());
		Assert.assertTrue(result.certificate().checkpoints().stream()
			.anyMatch(row -> row.phase().equals("GLOBAL") && row.regionVariables() == decisions));
		assertCertificateEnvelope(result.certificate(), exact.solverResult().objective(), fixture);
	}

	@Test
	public void zeroBudgetAndResourceLimitRetainOriginalFeasibleUpperBound() throws Exception {
		Fixture fixture = fixture();
		LocalPhysicalOptimizer.Result regional = LocalPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), null, ignored -> { });
		List<Integer> incumbent = regional.physicalResult().solverResult().assignmentInVariableOrder();
		long incumbentBits = regional.physicalResult().canonicalObjectiveBits();

		Options zeroBudget = new Options(1, 1, 1, 1,
			Math.max(1, fixture.model().variables().size()), 0L, 0d, 0d,
			false, true, ExpansionPolicy.STRUCTURAL, 20260908L,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		LocalPhysicalOptimizer.Result timed = LocalPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), zeroBudget, ignored -> { });
		Assert.assertEquals(StopReason.TIME_BUDGET, timed.certificate().stopReason());
		Assert.assertEquals(incumbent, timed.certificate().assignment());
		Assert.assertEquals(incumbentBits, timed.physicalResult().canonicalObjectiveBits());

		Options constrained = options(1, 1, 1, 1, false, false,
			ExpansionPolicy.STRUCTURAL, new ExactCategoricalSolver.Limits(1, 1));
		LocalPhysicalOptimizer.Result limited = LocalPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), constrained, ignored -> { });
		Assert.assertEquals(StopReason.RESOURCE_LIMIT, limited.certificate().stopReason());
		Assert.assertEquals(incumbent, limited.certificate().assignment());
		Assert.assertEquals(incumbentBits, limited.physicalResult().canonicalObjectiveBits());
		Assert.assertTrue(limited.certificate().checkpoints().stream()
			.anyMatch(row -> row.phase().equals("BOUND_LIMIT") && Double.isNaN(row.rawLowerBound())));
	}

	@Test
	public void configuredAnytimePlannerEmitsCompletePrivateAggregateReceipt() throws Exception {
		Map<String,String> values = new LinkedHashMap<>();
		values.put(PREFIX + "mode", "anytime");
		values.put(PREFIX + "width", "1");
		values.put(PREFIX + "maxWidth", "1");
		values.put(PREFIX + "rounds", "1");
		values.put(PREFIX + "regionGrowth", "256");
		values.put(PREFIX + "maxRegion", "256");
		values.put(PREFIX + "timeMillis", "60000");
		values.put(PREFIX + "absoluteGap", "0");
		values.put(PREFIX + "relativeGap", "0");
		values.put(PREFIX + "refineBound", "false");
		values.put(PREFIX + "policy", "STRUCTURAL");
		Map<String,String> previous = setProperties(values);
		try {
			Fixture fixture = fixture();
			ExactPlacementInput receipt = new FederatedPlanLocalCost().rewriteProgram(
				fixture.program(), null, null, fixture.analysis());
			Assert.assertSame(fixture.analysis(), receipt.analysis());
			Assert.assertTrue(receipt.emissionReceipt().applied());
			Assert.assertEquals(PlacementEmissionTransaction.canonicalPlanHash(receipt.normalizedResult()),
				receipt.emissionReceipt().planHash());
			Assert.assertEquals(fixture.analysis().graph().decisionNodes().size(),
				receipt.exactSelectedStates().size());
			fixture.analysis().graph().decisionNodes().forEach(node -> {
				PlacementState selected = receipt.exactSelectedStates().get(node.key());
				Assert.assertNotNull("planner omitted a physical decision", selected);
				Assert.assertTrue(node.legalAlternatives().stream().anyMatch(state -> state == selected));
				Assert.assertTrue("privacy-excluded state was emitted for " + node.normalizedIdentity(),
					node.exclusions().stream().noneMatch(exclusion ->
						exclusion.reasonCode() == ReasonCode.PRIVACY && exclusion.state().equals(selected)));
			});
		}
		finally {
			restoreProperties(values.keySet(), previous);
		}
	}

	@Test
	public void writesControlledAblationRowsWhenOutputIsConfigured() throws Exception {
		String configured = System.getProperty("sysds.regional.ablation.output");
		Assume.assumeTrue("set sysds.regional.ablation.output to emit the controlled TSV", configured != null);
		Fixture fixture = fixture();
		LocalPhysicalOptimizer.Result regional = LocalPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), null, ignored -> { });
		ExactPhysicalOptimizer.Result global = ExactPhysicalOptimizer.optimize(
			fixture.model(), fixture.surface(), ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		int decisions = fixture.model().variables().size();
		int matchedGrowth = Math.max(1, (decisions + 2) / 3);
		StringBuilder rows = new StringBuilder("variant\titeration\tphase\twidth\tregionVariables"
			+ "\trawLower\tlower\tupper\tabsoluteGap\trelativeGap\telapsedNanos\tboundNanos"
			+ "\tregionNanos\tsplitBuckets\tdisagreements\tmaximumFactorCells"
			+ "\tboundMaterializedCells\tboundAssignments\tregionAssignments\timproved\tassignment\n");
		appendEndpoint(rows, "Regional", regional.physicalResult(), 0d);
		append(rows, "Certify", optimize(fixture,
			options(1, 1, 1, 1, false, false, ExpansionPolicy.STRUCTURAL,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS)));
		append(rows, "BoundOnly", optimize(fixture,
			options(1, 3, 3, 1, false, true, ExpansionPolicy.STRUCTURAL,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS)));
		append(rows, "Structural", optimize(fixture,
			options(1, 3, 3, matchedGrowth, true, true, ExpansionPolicy.STRUCTURAL,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS)));
		append(rows, "Random", optimize(fixture,
			options(1, 3, 3, matchedGrowth, true, true, ExpansionPolicy.RANDOM,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS)));
		append(rows, "GuidedFixed", optimize(fixture,
			options(1, 1, 3, matchedGrowth, true, false, ExpansionPolicy.DISAGREEMENT,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS)));
		append(rows, "Anytime", optimize(fixture,
			options(1, 3, 3, matchedGrowth, true, true, ExpansionPolicy.DISAGREEMENT,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS)));
		appendEndpoint(rows, "Global", global, global.solverResult().objective());
		Path output = Path.of(configured).toAbsolutePath();
		Files.createDirectories(output.getParent());
		Files.writeString(output, rows);
	}

	private static void assertCertificateEnvelope(CertifiedRegionalOptimizer.Result certificate,
		double exactOptimum, Fixture fixture) {
		double previousLower = 0d;
		double previousUpper = Double.POSITIVE_INFINITY;
		for(Checkpoint row : certificate.checkpoints()) {
			Assert.assertTrue("lower bound exceeds Global optimum: " + row,
				row.lowerBound() <= exactOptimum);
			Assert.assertTrue("feasible upper bound is below Global optimum: " + row,
				row.upperBound() >= exactOptimum);
			Assert.assertTrue("lower bound regressed: " + row, row.lowerBound() >= previousLower);
			Assert.assertTrue("upper bound regressed: " + row, row.upperBound() <= previousUpper);
			Assert.assertTrue("checkpoint incumbent violates a physical hard constraint: " + row,
				Double.isFinite(CertifiedRegionalOptimizer.evaluate(fixture.model().variables(),
					fixture.model().hardFactors(), row.assignment())));
			Assert.assertEquals(row.upperBound(),
				Double.longBitsToDouble(fixture.surface().evaluateCanonical(row.assignment())), 0d);
			previousLower = row.lowerBound();
			previousUpper = row.upperBound();
		}
	}

	private static Options options(int width, int maxWidth, int rounds, int regionGrowth,
		boolean expand, boolean refine, ExpansionPolicy policy, ExactCategoricalSolver.Limits limits) {
		return new Options(width, maxWidth, rounds, regionGrowth, 256, 60_000L,
			0d, 0d, refine, expand, policy, 20260908L, limits);
	}

	private static CertifiedRegionalOptimizer.Result optimize(Fixture fixture, Options options) {
		return LocalPhysicalOptimizer.optimize(fixture.model(), fixture.surface(), options,
			ignored -> { }).certificate();
	}

	private static void append(StringBuilder rows, String variant,
		CertifiedRegionalOptimizer.Result result) {
		for(Checkpoint row : result.checkpoints())
			rows.append(variant).append('\t').append(row.iteration()).append('\t')
				.append(row.phase()).append('\t').append(row.width()).append('\t')
				.append(row.regionVariables()).append('\t').append(row.rawLowerBound()).append('\t')
				.append(row.lowerBound()).append('\t').append(row.upperBound()).append('\t')
				.append(row.absoluteGap()).append('\t').append(row.relativeGap()).append('\t')
				.append(row.elapsedNanos()).append('\t').append(row.boundNanos()).append('\t')
				.append(row.regionNanos()).append('\t').append(row.splitBuckets()).append('\t')
				.append(row.disagreements()).append('\t').append(row.maximumFactorCells()).append('\t')
				.append(row.boundMaterializedCells()).append('\t').append(row.boundAssignments()).append('\t')
				.append(row.regionAssignments()).append('\t').append(row.improved()).append('\t')
				.append(row.assignment()).append('\n');
	}

	private static void appendEndpoint(StringBuilder rows, String variant,
		ExactPhysicalOptimizer.Result result, double lower) {
		double upper = result.solverResult().objective();
		double gap = upper - lower;
		double relative = lower > 0d ? gap / lower : gap == 0d ? 0d : Double.POSITIVE_INFINITY;
		rows.append(variant).append("\t0\tENDPOINT\t0\t0\tNaN\t").append(lower).append('\t')
			.append(upper).append('\t').append(gap).append('\t').append(relative)
			.append("\t0\t0\t0\t0\t0\t0\t0\t0\t0\tfalse\t")
			.append(result.solverResult().assignmentInVariableOrder()).append('\n');
	}

	private static Fixture fixture() throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, PRIVATE_AGGREGATE_BRANCH_FIXTURE, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis =
			CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		return new Fixture(program, analysis, model, surface);
	}

	private static Map<String,String> setProperties(Map<String,String> values) {
		Map<String,String> previous = new LinkedHashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Iterable<String> keys, Map<String,String> previous) {
		for(String key : keys) {
			String value = previous.get(key);
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		}
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		ExactPhysicalModel model, ExactPhysicalCostModel.PhysicalCostSurface surface) { }
}
