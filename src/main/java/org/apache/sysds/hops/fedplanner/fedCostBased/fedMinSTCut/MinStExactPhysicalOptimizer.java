/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.List;
import java.util.Objects;

/** Adds one owner-bound canonical cost surface to an exact physical legality model. */
final class MinStExactPhysicalOptimizer {
	static final MinStExactCategoricalSolver.Limits PRODUCTION_LIMITS =
		new MinStExactCategoricalSolver.Limits(10_000_000L, 50_000_000L);

	record Result(MinStExactCategoricalSolver.Result solverResult,
		long canonicalObjectiveBits, String contributionFingerprint) {
		Result {
			Objects.requireNonNull(solverResult, "solverResult");
			if(contributionFingerprint == null || contributionFingerprint.isBlank())
				throw new IllegalArgumentException("MINST_PHYSICAL_COST_FINGERPRINT_INVALID");
		}
	}

	private MinStExactPhysicalOptimizer() { }

	static Result optimize(MinStExactPhysicalModel model,
		MinStExactCostFactsProducer.PhysicalCostSurface surface,
		MinStExactCategoricalSolver.Limits limits) {
		Objects.requireNonNull(model, "model");
		if(surface == null || surface.factors().isEmpty())
			throw new IllegalArgumentException(model.missingCostSurface());
		if(!surface.ownerFingerprint().equals(model.analysis().analysisFingerprint()))
			throw new IllegalArgumentException("MINST_PHYSICAL_COST_OWNER_MISMATCH");
		List<MinStExactCategoricalSolver.Variable> modelVariables = model.variables();
		if(surface.variables().size() != modelVariables.size())
			throw new IllegalArgumentException("MINST_PHYSICAL_COST_VARIABLE_CARDINALITY_MISMATCH");
		for(int index = 0; index < modelVariables.size(); index++)
			if(surface.variables().get(index) != modelVariables.get(index))
				throw new IllegalArgumentException("MINST_PHYSICAL_COST_VARIABLE_IDENTITY_MISMATCH");

		List<MinStExactCategoricalSolver.Factor> factors =
			new java.util.ArrayList<>(model.hardFactors());
		factors.addAll(surface.factors());
		MinStExactCategoricalSolver.Result solved = MinStExactCategoricalSolver.solve(
			modelVariables, factors, limits);
		long canonicalBits = surface.evaluateCanonical(solved.assignmentInVariableOrder());
		if(Double.doubleToRawLongBits(solved.objective()) != canonicalBits)
			throw new IllegalArgumentException("MINST_PHYSICAL_SOLVER_CANONICAL_OBJECTIVE_MISMATCH"
				+ "|solver=" + solved.objective() + "|canonical="
				+ Double.longBitsToDouble(canonicalBits));
		return new Result(solved, canonicalBits, surface.contributionFingerprint());
	}
}
