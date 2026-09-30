/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

/** Compose physical authority, exact representation, reduction and numeric kernel. */
public class ExactCertifiedSharedSourceOptimizerTest {
	@Test
	public void certifiedPhysicalSolveMatchesRawCanonicalObjectiveWithBothCompactionModes() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var limits = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model, limits, factor -> { });
		var certificate = ExactDyadicCosts.certify(surface);
		Assert.assertTrue(certificate.supported());
		// Keep an independent original-representation solve after the default entry
		// begins choosing the certified representation itself.
		var originalFactors = new ArrayList<>(model.exactSolverHardFactors());
		originalFactors.addAll(surface.exactSolverFactors());
		var original = ExactPhysicalReducedSolver.solve(ExactPhysicalReducedSolver.prepareCompacted(
			model.variables().size(), surface.exactSolverVariables(), originalFactors, limits));
		long originalBits = surface.evaluateCanonical(
			original.assignmentInVariableOrder().subList(0, model.variables().size()));
		Assert.assertEquals(originalBits, Double.doubleToRawLongBits(original.objective()));
		String old = System.getProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY);
		try {
			for(boolean compact : new boolean[] {false, true}) {
				System.setProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY, Boolean.toString(compact));
				var result = ExactPhysicalOptimizer.optimizeSharedSource(model, surface, limits, certificate);
				Assert.assertEquals(model.variables().size(), result.solverResult().assignmentInVariableOrder().size());
				Assert.assertEquals(originalBits, result.canonicalObjectiveBits());
				Assert.assertEquals(surface.contributionFingerprint(), result.contributionFingerprint());
				Assert.assertEquals(surface.evaluateCanonical(result.solverResult().assignmentInVariableOrder()),
					Double.doubleToRawLongBits(result.solverResult().objective()));
			}
		}
		finally {
			if(old == null) System.clearProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY);
			else System.setProperty(ExactPhysicalOptimizer.COMPACT_PROPERTY, old);
		}
	}

	@Test
	public void scalarAndDifferentSurfaceCertificatesCannotAuthorizePhysicalSolve() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var limits = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var certificate = ExactDyadicCosts.certify(surface);
		var numericOnly = ExactDyadicCosts.certify(certificate.q(), certificate.maximumSumBits(),
			certificate.canonicalContributionCount(), true);
		Assert.assertTrue(numericOnly.supported());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalOptimizer.optimizeSharedSource(model, surface, limits, numericOnly));
		var fresh = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalOptimizer.optimizeSharedSource(model, fresh, limits, certificate));
	}

	@Test
	public void exactReductionExpansionRetainsDyadicConditionalOptimum() {
		var singleton = new ExactCategoricalSolver.Variable("singleton", 1);
		var x = new ExactCategoricalSolver.Variable("choice", 2);
		var factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(singleton), new double[] {1}),
			ExactCategoricalSolver.Factor.dense(List.of(x), new double[] {0x1.0p53, 0x1.0p53}),
			ExactCategoricalSolver.Factor.dense(List.of(x), new double[] {0.75, 0}));
		var limits = new ExactCategoricalSolver.Limits(100, 1000);
		var certificate = ExactDyadicCosts.certify(-2, 56, 3, true);
		for(boolean compact : new boolean[] {false, true}) {
			var prepared = compact ? ExactPhysicalReducedSolver.prepareCompacted(2, List.of(singleton, x), factors, limits)
				: ExactPhysicalReducedSolver.prepare(2, List.of(singleton, x), factors, limits);
			var solved = ExactPhysicalReducedSolver.solveDyadic(prepared, certificate);
			Assert.assertEquals(List.of(0, 1), solved.assignmentInVariableOrder());
			Assert.assertEquals(Double.doubleToRawLongBits(0x1.0p53),
				Double.doubleToRawLongBits(solved.objective()));
		}
	}

	@Test
	public void physicalCertificateIsBoundToTheExactFinalCompiledProblem() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var limits = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var certificate = ExactDyadicCosts.certify(surface);
		var encoding = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(), limits);
		Assert.assertTrue(encoding.statistics().transformed());
		var prepared = ExactPhysicalReducedSolver.prepareCompacted(encoding.decisionPrefixCount(),
			encoding.variables(), encoding.factors(), limits);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solveDyadic(prepared.compiledProblem(), certificate));
		var bound = certificate.bindDerived(encoding, prepared);
		Assert.assertTrue(Double.isFinite(ExactPhysicalReducedSolver.solveDyadic(prepared, bound).objective()));
		var sameStructure = ExactPhysicalReducedSolver.prepareCompacted(encoding.decisionPrefixCount(),
			encoding.variables(), encoding.factors(), limits);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalReducedSolver.solveDyadic(sameStructure, bound));
		Assert.assertThrows("derivation must not mutate original physical certificate",
			IllegalArgumentException.class,
			() -> ExactPhysicalReducedSolver.solveDyadic(prepared, certificate));
	}

	@Test
	public void replacementInputsAndUntransformedEncodingCannotDeriveAuthority() throws Exception {
		var analysis = ExactNativeLocalAnchorFanoutCostTest.analysis(false);
		var model = ExactPhysicalModel.build(analysis);
		var limits = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var certificate = ExactDyadicCosts.certify(surface);
		var encoding = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(), limits);
		var factors = new ArrayList<>(encoding.factors());
		var first = factors.get(0);
		int cells = first.scope().stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++) values[cell] = first.denseCostAt(cell);
		factors.set(0, ExactCategoricalSolver.Factor.dense(first.scope(), values));
		var replacement = ExactPhysicalReducedSolver.prepareCompacted(encoding.decisionPrefixCount(),
			encoding.variables(), factors, limits);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> certificate.bindDerived(encoding, replacement));
		var prepared = ExactPhysicalReducedSolver.prepareCompacted(encoding.decisionPrefixCount(),
			encoding.variables(), encoding.factors(), limits);
		var independentlyEncoded = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(), limits);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> certificate.bindDerived(independentlyEncoded, prepared));
		var forced = ExactCategoricalSolver.Factor.dense(List.of(model.variables().get(0)),
			new double[model.variables().get(0).domainSize()]);
		var legacy = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(forced), limits);
		Assert.assertFalse(legacy.statistics().transformed());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> certificate.bindDerived(legacy, prepared));
	}
}
