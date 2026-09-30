/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.junit.Assert;
import org.junit.Test;

/** The default global optimizer chooses a certified representation, not a different planner. */
public class ExactSharedSourceDefaultRoutingTest {
	@Test
	public void eligibleDefaultUsesTheCertifiedGraphWithBothCompactionModes() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var limits = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var certificate = ExactDyadicCosts.certify(surface);
		Assert.assertTrue(certificate.supported());
		String previous = System.getProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY);
		try {
			for(boolean compact : new boolean[] {false, true}) {
				System.setProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY, Boolean.toString(compact));
				var explicit = ExactPhysicalOptimizer.optimizeSharedSource(model, surface, limits, certificate);
				var actual = ExactPhysicalOptimizer.optimize(model, surface, limits);
				Assert.assertEquals(explicit.canonicalObjectiveBits(), actual.canonicalObjectiveBits());
				Assert.assertEquals(explicit.solverResult().assignmentInVariableOrder(),
					actual.solverResult().assignmentInVariableOrder());
				Assert.assertEquals("default must compile the certified shared-source graph",
					explicit.solverResult().statistics(), actual.solverResult().statistics());
			}
		}
		finally { restore(ExactPhysicalOptimizer.COMPACT_PROPERTY, previous); }
	}

	@Test
	public void forcedTargetRetainsWholeLegacyFactorsAndOriginalOrder() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var limits = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
		var baseline = ExactPhysicalOptimizer.optimize(model, surface, limits).solverResult();
		int targetIndex = -1;
		for(int index = 0; index < model.domains().size(); index++)
			if(model.domains().get(index).alternatives().get(
				baseline.assignmentInVariableOrder().get(index)).captured()) {
				targetIndex = index;
				break;
			}
		Assert.assertTrue("fixture must expose a captured feasible target", targetIndex >= 0);
		var domain = model.domains().get(targetIndex);
		var target = domain.alternatives().get(baseline.assignmentInVariableOrder().get(targetIndex));
		String[] names = {ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY,
			ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY, ExactPhysicalForcedStateAudit.INPUT_PROPERTY,
			ExactPhysicalForcedStateAudit.STATE_PROPERTY, ExactPhysicalOptimizer.COMPACT_PROPERTY};
		String[] prior = java.util.Arrays.stream(names).map(System::getProperty).toArray(String[]::new);
		try {
			System.setProperty(names[0], analysis.analysisFingerprint());
			System.setProperty(names[1], PlannerCandidateSpaceAudit.replayOccurrenceHash(domain.node().key()));
			System.setProperty(names[2], ExactPhysicalForcedStateAudit.inputSignature(target.orderedInputs()));
			System.setProperty(names[3], ExactPhysicalForcedStateAudit.physicalState(target.state()));
			System.setProperty(names[4], "true");
			var forced = ExactPhysicalForcedStateAudit.prepare(model);
			Assert.assertNotNull(forced);
			var factors = new ArrayList<>(model.exactSolverHardFactors());
			factors.add(forced.factor());
			factors.addAll(surface.exactSolverFactors());
			var prepared = ExactPhysicalReducedSolver.prepareCompacted(model.variables().size(),
				surface.exactSolverVariables(), factors, limits,
				ExactEliminationOrderPolicy.globalConfigured(), "forced-reference");
			var expected = ExactPhysicalReducedSolver.solve(prepared);
			var actual = ExactPhysicalOptimizer.optimize(model, surface, limits).solverResult();
			Assert.assertEquals(expected.statistics(), actual.statistics());
			Assert.assertEquals(expected.assignmentInVariableOrder().subList(0, model.variables().size()),
				actual.assignmentInVariableOrder());
			Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
				Double.doubleToRawLongBits(actual.objective()));
		}
		finally { for(int index = 0; index < names.length; index++) restore(names[index], prior[index]); }
	}

	@Test
	public void encodingCapRejectionUsesTheOriginalExactBranch() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var limits = new ExactCategoricalSolver.Limits(1, 1);
		Assert.assertTrue("fixture must pass the independent numeric gate",
			ExactDyadicCosts.certify(surface).supported());
		var encoding = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(), limits);
		Assert.assertFalse("tiny cap must reject only the candidate representation",
			encoding.statistics().transformed());

		var originalFactors = new ArrayList<>(model.exactSolverHardFactors());
		originalFactors.addAll(surface.exactSolverFactors());
		String previous = System.getProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY);
		try {
			System.setProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY, "true");
			IllegalArgumentException expected = Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactPhysicalReducedSolver.prepareCompacted(model.variables().size(),
					surface.exactSolverVariables(), originalFactors, limits,
					ExactEliminationOrderPolicy.globalConfigured(), "fallback-reference"));
			IllegalArgumentException actual = Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactPhysicalOptimizer.optimize(model, surface, limits));
			Assert.assertEquals("default must surface the original exact branch's cap failure",
				expected.getMessage(), actual.getMessage());
		}
		finally { restore(ExactPhysicalOptimizer.COMPACT_PROPERTY, previous); }
	}

	private static void restore(String name, String value) {
		if(value == null) System.clearProperty(name);
		else System.setProperty(name, value);
	}
}
