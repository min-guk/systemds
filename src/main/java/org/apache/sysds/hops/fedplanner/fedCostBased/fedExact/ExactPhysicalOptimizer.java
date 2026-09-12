/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;

/** Adds one owner-bound canonical cost surface to an exact physical legality model. */
final class ExactPhysicalOptimizer {
	static final String COMPACT_PROPERTY = "sysds.fedplanner.exact.compact";
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
		factors.addAll(surface.exactSolverFactors());
		ExactCategoricalSolver.Result solved;
		try {
			boolean compact = configuredCompaction();
			ExactEliminationOrderPolicy.Configuration orderPolicy =
				ExactEliminationOrderPolicy.globalConfigured();
			long started = System.nanoTime();
			// The off variant keeps the same exact domain reduction and quotient;
			// only singleton substitution before elimination is disabled.
			ExactPhysicalReducedSolver.Prepared prepared = compact
				? ExactPhysicalReducedSolver.prepareCompacted(modelVariables.size(),
					surface.exactSolverVariables(), factors, limits, orderPolicy, "global-compact")
				: ExactPhysicalReducedSolver.prepare(modelVariables.size(),
					surface.exactSolverVariables(), factors, limits, orderPolicy, "global");
			if(FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("Exact-Preparation", "compact=" + compact
					+ " exactReduction=true encodedVariables=" + surface.exactSolverVariables().size()
					+ " compiledVariables=" + prepared.compiledVariableCount()
					+ " factorLimit=" + limits.maximumFactorCells()
					+ " totalCellLimit=" + limits.maximumMaterializedCells()
					+ orderTrace(orderPolicy, prepared)
					+ " compileNanos=" + prepared.preparationStatistics().compileNanos()
					+ " preparationNanos=" + (System.nanoTime() - started));
			LocalCategoricalOptimizer.tracePreparation("global", prepared.preparationStatistics());
			solved = ExactPhysicalReducedSolver.solve(prepared);
		}
		catch(IllegalArgumentException failure) {
			ExactPhysicalForcedStateAudit.recordSolverFailure(model, forced, failure);
			throw failure;
		}
		ExactCategoricalSolver.Result decisionResult = new ExactCategoricalSolver.Result(
			solved.objective(), solved.assignmentInVariableOrder().subList(0, modelVariables.size()),
			solved.statistics());
		ExactPhysicalForcedStateAudit.verify(model, forced, decisionResult);
		long canonicalBits = surface.evaluateCanonical(decisionResult.assignmentInVariableOrder());
		if(Double.doubleToRawLongBits(decisionResult.objective()) != canonicalBits)
			throw new IllegalArgumentException("EXACT_PHYSICAL_SOLVER_CANONICAL_OBJECTIVE_MISMATCH"
				+ "|solver=" + decisionResult.objective() + "|canonical="
				+ Double.longBitsToDouble(canonicalBits));
		return new Result(decisionResult, canonicalBits, surface.contributionFingerprint());
	}

	private static String orderTrace(ExactEliminationOrderPolicy.Configuration policy,
		ExactPhysicalReducedSolver.Prepared prepared) {
		ExactCategoricalSolver.OrderCompilation compilation = prepared.orderCompilation();
		return " fastOrderConfigured=" + policy.fastOrder()
			+ " fastOrderAccepted=" + (compilation != null && compilation.fastOrderAccepted())
			+ " fastOrderFallback=" + (compilation != null && compilation.fastOrderFallback())
			+ " fastOrderEstimatedAssignments="
			+ (compilation == null ? 0L : compilation.fastOrderAssignments())
			+ " fastOrderAssignmentsLimit=" + policy.maximumAssignments()
			+ " fastOrderSource=" + policy.source();
	}

	private static boolean configuredCompaction() {
		String value = System.getProperty(COMPACT_PROPERTY, "true");
		if(!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
			throw new IllegalArgumentException("EXACT_PHYSICAL_COMPACT_OPTION_INVALID|" + value);
		return Boolean.parseBoolean(value);
	}
}
