/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Objects;

/** Adds one owner-bound canonical cost surface to an exact physical legality model. */
final class ExactPhysicalOptimizer {
	static final ExactCategoricalSolver.Limits PRODUCTION_LIMITS =
		new ExactCategoricalSolver.Limits(10_000_000L, 50_000_000L);

	record Result(ExactCategoricalSolver.Result solverResult,
		long canonicalObjectiveBits, String contributionFingerprint) {
		Result {
			Objects.requireNonNull(solverResult, "solverResult");
			if(contributionFingerprint == null || contributionFingerprint.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_FINGERPRINT_INVALID");
		}
	}

	private ExactPhysicalOptimizer() { }

	static Result optimize(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactCategoricalSolver.Limits limits) {
		Objects.requireNonNull(model, "model");
		if(surface == null || surface.factors().isEmpty())
			throw new IllegalArgumentException(model.missingCostSurface());
		if(!surface.ownerFingerprint().equals(model.analysis().analysisFingerprint()))
			throw new IllegalArgumentException("EXACT_PHYSICAL_COST_OWNER_MISMATCH");
		List<ExactCategoricalSolver.Variable> modelVariables = model.variables();
		if(surface.variables().size() != modelVariables.size())
			throw new IllegalArgumentException("EXACT_PHYSICAL_COST_VARIABLE_CARDINALITY_MISMATCH");
		for(int index = 0; index < modelVariables.size(); index++)
			if(surface.variables().get(index) != modelVariables.get(index))
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_VARIABLE_IDENTITY_MISMATCH");

		List<ExactCategoricalSolver.Factor> factors =
			new java.util.ArrayList<>(model.hardFactors());
		ExactPhysicalForcedStateAudit.Constraint forced =
			ExactPhysicalForcedStateAudit.prepare(model);
		if(forced != null)
			factors.add(forced.factor());
		factors.addAll(surface.factors());
		ExactCategoricalSolver.Result solved;
		try {
			solved = ExactCategoricalSolver.solve(modelVariables, factors, limits);
		}
		catch(IllegalArgumentException failure) {
			ExactPhysicalForcedStateAudit.recordSolverFailure(model, forced, failure);
			throw failure;
		}
		ExactPhysicalForcedStateAudit.verify(model, forced, solved);
		long canonicalBits = surface.evaluateCanonical(solved.assignmentInVariableOrder());
		if(Double.doubleToRawLongBits(solved.objective()) != canonicalBits)
			throw new IllegalArgumentException("EXACT_PHYSICAL_SOLVER_CANONICAL_OBJECTIVE_MISMATCH"
				+ "|solver=" + solved.objective() + "|canonical="
				+ Double.longBitsToDouble(canonicalBits));
		return new Result(solved, canonicalBits, surface.contributionFingerprint());
	}
}
