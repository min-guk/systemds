/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Files;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014HermeticPlannerFixtureFactory;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalForcedStateAuditTest {
	@After
	public void clearProperties() {
		System.clearProperty(ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.SEMANTIC_OCCURRENCE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.INPUT_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.STATE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.DIRECTORY_PROPERTY);
	}

	@Test
	public void forcedPublishedStateKeepsWholeProgramFactorsAndSelectsTarget() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile("B-22"));
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var baseline = ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS).solverResult();

		boolean forced = false;
		for(int domainIndex = 0; domainIndex < model.domains().size() && !forced; domainIndex++) {
			var domain = model.domains().get(domainIndex);
			var baselineAlternative = domain.alternatives().get(
				baseline.assignmentInVariableOrder().get(domainIndex));
			for(var target : domain.alternatives()) {
				if(!target.captured() || sameTarget(target, baselineAlternative))
					continue;
				setTarget(model, domain, target);
				try {
					var selected = ExactPhysicalOptimizer.optimize(model, surface,
						ExactPhysicalOptimizer.PRODUCTION_LIMITS).solverResult();
					var actual = domain.alternatives().get(
						selected.assignmentInVariableOrder().get(domainIndex));
					Assert.assertTrue(sameTarget(target, actual));
					forced = true;
					break;
				}
				catch(IllegalArgumentException ex) {
					if(!"EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(ex.getMessage()))
						throw ex;
				}
			}
		}
		Assert.assertTrue("fixture must contain a non-baseline published state with a coherent completion",
			forced);
	}

	@Test
	public void matchedOccurrenceRejectsStateOutsidePublishedInputDomain() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile("B-11"));
		var model = ExactPhysicalModel.build(analysis);
		var domain = model.domains().stream()
			.filter(candidate -> candidate.alternatives().stream().anyMatch(
				ExactPhysicalModel.Alternative::captured)).findFirst().orElseThrow();
		var target = domain.alternatives().stream().filter(
			ExactPhysicalModel.Alternative::captured).findFirst().orElseThrow();
		setTarget(model, domain, target);
		System.setProperty(ExactPhysicalForcedStateAudit.INPUT_PROPERTY,
			ExactPhysicalForcedStateAudit.inputSignature(target.orderedInputs()) + ",PRESENT:FULL");
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalOptimizer.optimize(model,
				ExactPhysicalCostModel.physicalCostSurface(analysis, model),
				ExactPhysicalOptimizer.PRODUCTION_LIMITS));
		Assert.assertTrue(error.getMessage().startsWith(
			"FED_SPACE_AUDIT_FORCE_TARGET_NOT_EXPOSED"));
	}

	@Test
	public void absentOccurrenceLeavesExactSolveUnchanged() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile("B-11"));
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var baseline = ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS).solverResult();
		System.setProperty(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY,
			"0000000000000000");
		System.setProperty(ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY,
			analysis.analysisFingerprint());
		System.setProperty(ExactPhysicalForcedStateAudit.INPUT_PROPERTY, "");
		System.setProperty(ExactPhysicalForcedStateAudit.STATE_PROPERTY, "FED/FOUT/ROW");
		var repeated = ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS).solverResult();
		Assert.assertEquals(baseline.objective(), repeated.objective(), 0.0);
		Assert.assertEquals(baseline.assignmentInVariableOrder(),
			repeated.assignmentInVariableOrder());
	}

	@Test
	public void uniqueSemanticReplayFallbackSurvivesEmittedPathDrift() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile("B-11"));
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var baseline = ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS).solverResult();
		int domainIndex = -1;
		for(int i = 0; i < model.domains().size(); i++) {
			var domain = model.domains().get(i);
			String semantic = PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(
				domain.node().key());
			long matches = model.domains().stream().filter(candidate ->
				PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(candidate.node().key())
					.equals(semantic)).count();
			var alternative = domain.alternatives().get(
				baseline.assignmentInVariableOrder().get(i));
			if(matches == 1 && alternative.captured()) {
				domainIndex = i;
				break;
			}
		}
		Assert.assertTrue("fixture must expose a semantically unique captured domain",
			domainIndex >= 0);
		var domain = model.domains().get(domainIndex);
		var target = domain.alternatives().get(
			baseline.assignmentInVariableOrder().get(domainIndex));
		setTarget(model, domain, target);
		System.setProperty(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY,
			"0000000000000000");
		Assert.assertTrue(ExactPhysicalForcedStateAudit.targetsAnalysis(analysis));
		var constraint = ExactPhysicalForcedStateAudit.prepare(model);
		Assert.assertNotNull(constraint);
		Assert.assertEquals(domain, constraint.domain());
		var selected = ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS).solverResult();
		Assert.assertTrue(sameTarget(target, domain.alternatives().get(
			selected.assignmentInVariableOrder().get(domainIndex))));
	}

	@Test
	public void forcedInfeasibleSolveWritesExplicitClassificationEvent() throws Exception {
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(
			CampaignBG014HermeticPlannerFixtureFactory.compile("B-11"));
		var model = ExactPhysicalModel.build(analysis);
		var domain = model.domains().stream()
			.filter(candidate -> candidate.alternatives().stream().anyMatch(
				ExactPhysicalModel.Alternative::captured)).findFirst().orElseThrow();
		var target = domain.alternatives().stream().filter(
			ExactPhysicalModel.Alternative::captured).findFirst().orElseThrow();
		setTarget(model, domain, target);
		var directory = Files.createTempDirectory("forced-state-infeasible-");
		System.setProperty(ExactPhysicalForcedStateAudit.DIRECTORY_PROPERTY,
			directory.toString());
		var constraint = ExactPhysicalForcedStateAudit.prepare(model);
		ExactPhysicalForcedStateAudit.recordSolverFailure(model, constraint,
			new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT"));
		try(var files = Files.list(directory)) {
			var receipt = files.filter(path -> path.getFileName().toString()
				.startsWith("forced-state-")).findFirst().orElseThrow();
			Assert.assertTrue(Files.readString(receipt)
				.contains("\"event\":\"WHOLE_PROGRAM_INFEASIBLE\""));
		}
		try(var files = Files.walk(directory)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch(Exception ex) {
					throw new RuntimeException(ex);
				}
			});
		}
	}

	private static void setTarget(ExactPhysicalModel model,
		ExactPhysicalModel.DecisionDomain domain,
		ExactPhysicalModel.Alternative alternative) {
		System.setProperty(ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY,
			model.analysis().analysisFingerprint());
		System.setProperty(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY,
			PlannerCandidateSpaceAudit.replayOccurrenceHash(domain.node().key()));
		System.setProperty(ExactPhysicalForcedStateAudit.SEMANTIC_OCCURRENCE_PROPERTY,
			PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(domain.node().key()));
		System.setProperty(ExactPhysicalForcedStateAudit.INPUT_PROPERTY,
			ExactPhysicalForcedStateAudit.inputSignature(alternative.orderedInputs()));
		System.setProperty(ExactPhysicalForcedStateAudit.STATE_PROPERTY,
			ExactPhysicalForcedStateAudit.physicalState(alternative.state()));
	}

	private static boolean sameTarget(ExactPhysicalModel.Alternative left,
		ExactPhysicalModel.Alternative right) {
		return ExactPhysicalForcedStateAudit.physicalState(left.state()).equals(
			ExactPhysicalForcedStateAudit.physicalState(right.state()))
			&& ExactPhysicalForcedStateAudit.inputSignature(left.orderedInputs()).equals(
				ExactPhysicalForcedStateAudit.inputSignature(right.orderedInputs()));
	}
}
