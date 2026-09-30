/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;

/** Adds one owner-bound canonical cost surface to an exact physical legality model. */
final class ExactPhysicalOptimizer {
	static final String COMPACT_PROPERTY = "sysds.fedplanner.exact.compact";
	static final ExactCategoricalSolver.Limits PRODUCTION_LIMITS =
		new ExactCategoricalSolver.Limits(Integer.MAX_VALUE, Long.MAX_VALUE);

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

		ExactPhysicalForcedStateAudit.Constraint forced =
			ExactPhysicalForcedStateAudit.prepare(model);
		if(forced == null) {
			long started = System.nanoTime();
			var certificate = ExactDyadicCosts.certify(surface);
			String legacyReason = certificate.reason();
			if(certificate.supported()) {
				var encoding = ExactPhysicalSharedSourceEncoding.prepare(model, surface, List.of(), limits);
				if(encoding.statistics().transformed())
					return optimizePreparedSharedSource(model, surface, limits, certificate, encoding, null, started);
				legacyReason = encoding.statistics().reason();
			}
			if(FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("Exact-Representation", "sharedSource=false reason="
					+ legacyReason + " numericQ=" + certificate.q()
					+ " numericMaxBits=" + certificate.maximumSumBits()
					+ " canonicalContributions=" + certificate.canonicalContributionCount()
					+ " accumulationContributions=" + certificate.accumulationContributionCount()
					+ " numericHeadroom=" + certificate.bitsWithAccumulationHeadroom()
					+ " numericResidualUnits=" + certificate.maximumResidualUnits()
					+ " eligibilityNanos=" + (System.nanoTime() - started));
		}
		// Unsupported representation/numeric eligibility retains the complete original
		// exact problem. Solver or canonical-audit failures never select this branch.
		List<ExactCategoricalSolver.Factor> factors =
			new java.util.ArrayList<>(model.exactSolverHardFactors());
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
		return verifyResult(model, surface, forced, solved);
	}

	/**
	 * Strict certified composition used to validate the production representation choice.
	 * Unlike the default entry, unsupported eligibility here is an explicit error.
	 */
	static Result optimizeSharedSource(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactCategoricalSolver.Limits limits,
		ExactDyadicCosts.Certificate certificate) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(certificate, "certificate");
		certificate.validateSurface(surface);
		certificate.validateSourceFactors(surface.exactSolverFactors());
		if(!certificate.supported())
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_NUMERIC_UNSUPPORTED|" + certificate.reason());
		ExactPhysicalForcedStateAudit.Constraint forced = ExactPhysicalForcedStateAudit.prepare(model);
		long started = System.nanoTime();
		var encoding = ExactPhysicalSharedSourceEncoding.prepare(model, surface,
			forced == null ? List.of() : List.of(forced.factor()), limits);
		if(!encoding.statistics().transformed())
			throw new IllegalArgumentException("EXACT_SHARED_SOURCE_ENCODING_UNSUPPORTED|"
				+ encoding.statistics().reason());
		return optimizePreparedSharedSource(model, surface, limits, certificate, encoding, forced, started);
	}

	private static Result optimizePreparedSharedSource(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactCategoricalSolver.Limits limits,
		ExactDyadicCosts.Certificate certificate, ExactPhysicalSharedSourceEncoding.Encoding encoding,
		ExactPhysicalForcedStateAudit.Constraint forced, long started) {
		ExactCategoricalSolver.Result decoded;
		try {
			boolean compact = configuredCompaction();
			var orderPolicy = ExactEliminationOrderPolicy.globalConfigured();
			// Only this exact encoding of the already validated surface can supply the
			// private preparation below. No caller-supplied replacement factor list is accepted.
			var prepared = compact ? ExactPhysicalReducedSolver.prepareCompacted(
				encoding.decisionPrefixCount(), encoding.variables(), encoding.factors(), limits,
				orderPolicy, "global-shared-compact") : ExactPhysicalReducedSolver.prepare(
				encoding.decisionPrefixCount(), encoding.variables(), encoding.factors(), limits,
				orderPolicy, "global-shared");
			if(FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("Exact-SharedSourcePreparation", "compact=" + compact
					+ " numericQ=" + certificate.q() + " numericMaxBits=" + certificate.maximumSumBits()
					+ " canonicalContributions=" + certificate.canonicalContributionCount()
					+ " accumulationContributions=" + certificate.accumulationContributionCount()
					+ " numericHeadroom=" + certificate.bitsWithAccumulationHeadroom()
					+ " numericResidualUnits=" + certificate.maximumResidualUnits()
					+ " originalRows=" + encoding.statistics().originalRows()
					+ " headerValues=" + encoding.statistics().headerValues()
					+ " encodedVariables=" + encoding.variables().size()
					+ " compiledVariables=" + prepared.compiledVariableCount()
					+ " factorLimit=" + limits.maximumFactorCells()
					+ " totalCellLimit=" + limits.maximumMaterializedCells()
					+ orderTrace(orderPolicy, prepared)
					+ " preparationNanos=" + (System.nanoTime() - started));
			LocalCategoricalOptimizer.tracePreparation("global-shared", prepared.preparationStatistics());
			var solved = ExactPhysicalReducedSolver.solveDyadic(prepared,
				certificate.bindDerived(encoding, prepared));
			decoded = new ExactCategoricalSolver.Result(solved.objective(),
				encoding.decode(solved.assignmentInVariableOrder()), solved.statistics());
		}
		catch(IllegalArgumentException failure) {
			ExactPhysicalForcedStateAudit.recordSolverFailure(model, forced, failure);
			throw failure;
		}
		return verifyResult(model, surface, forced, decoded);
	}

	private static Result verifyResult(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactPhysicalForcedStateAudit.Constraint forced, ExactCategoricalSolver.Result solved) {
		List<ExactCategoricalSolver.Variable> modelVariables = model.variables();
		ExactCategoricalSolver.Result decisionResult = new ExactCategoricalSolver.Result(
			solved.objective(), solved.assignmentInVariableOrder().subList(0, modelVariables.size()),
			solved.statistics());
		if(!Double.isFinite(RegionalSearchProblem.evaluateFactors(modelVariables,
			model.hardFactors(), decisionResult.assignmentInVariableOrder())))
			throw new IllegalArgumentException("EXACT_PHYSICAL_SOLVER_CANONICAL_HARD_MISMATCH");
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
