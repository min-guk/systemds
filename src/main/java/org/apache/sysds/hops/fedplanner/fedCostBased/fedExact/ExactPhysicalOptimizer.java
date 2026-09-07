/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Objects;

/** Adds one owner-bound canonical cost surface to an exact physical legality model. */
final class ExactPhysicalOptimizer {
	static final ExactCategoricalSolver.Limits PRODUCTION_LIMITS =
		new ExactCategoricalSolver.Limits(10_000_000L, 50_000_000L);

	record JointSearch(List<Integer> assignment, int operatorVariables, int bindingVariables) {
		JointSearch {
			assignment = List.copyOf(assignment);
			if(operatorVariables < 0 || bindingVariables < 0
				|| assignment.size() != operatorVariables + bindingVariables)
				throw new IllegalArgumentException("EXACT_BINDING_SEARCH_CARDINALITY_MISMATCH");
		}
	}

	record Result(ExactCategoricalSolver.Result solverResult,
		long canonicalObjectiveBits, String contributionFingerprint,
		List<ExactPhysicalBindingModel.SelectedBinding> inputBindings, JointSearch jointSearch) {
		// LocalCost and explicit catalog fixtures already select complete physical rows.
		Result(ExactCategoricalSolver.Result solverResult, long canonicalObjectiveBits,
			String contributionFingerprint) {
			this(solverResult, canonicalObjectiveBits, contributionFingerprint, null, null);
		}
		Result(ExactCategoricalSolver.Result solverResult, long canonicalObjectiveBits,
			String contributionFingerprint, List<ExactPhysicalBindingModel.SelectedBinding> inputBindings) {
			this(solverResult, canonicalObjectiveBits, contributionFingerprint, inputBindings, null);
		}
		Result {
			Objects.requireNonNull(solverResult, "solverResult");
			if(contributionFingerprint == null || contributionFingerprint.isBlank())
				throw new IllegalArgumentException("EXACT_PHYSICAL_COST_FINGERPRINT_INVALID");
			if(jointSearch != null && inputBindings == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_SEARCHED_BINDINGS_MISSING");
			if(inputBindings != null)
				inputBindings = List.copyOf(inputBindings);
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
		ExactPhysicalBindingModel bindings = ExactPhysicalBindingModel.build(model);
		ExactCategoricalSolver.Result solved;
		try {
			solved = ExactPhysicalReducedSolver.solve(bindings.variables().size(),
				bindings.solverVariables(surface.exactSolverVariables()),
				bindings.translateFactors(factors), limits);
		}
		catch(IllegalArgumentException failure) {
			ExactPhysicalForcedStateAudit.recordSolverFailure(model, forced, failure);
			throw failure;
		}
		List<Integer> jointAssignment = solved.assignmentInVariableOrder()
			.subList(0, bindings.variables().size());
		ExactCategoricalSolver.Result decisionResult = new ExactCategoricalSolver.Result(
			solved.objective(), bindings.reconstruct(jointAssignment),
			solved.statistics());
		ExactPhysicalForcedStateAudit.verify(model, forced, decisionResult);
		long canonicalBits = surface.evaluateCanonical(decisionResult.assignmentInVariableOrder());
		if(Double.doubleToRawLongBits(decisionResult.objective()) != canonicalBits)
			throw new IllegalArgumentException("EXACT_PHYSICAL_SOLVER_CANONICAL_OBJECTIVE_MISMATCH"
				+ "|solver=" + decisionResult.objective() + "|canonical="
				+ Double.longBitsToDouble(canonicalBits));
		return new Result(decisionResult, canonicalBits, surface.contributionFingerprint(),
			bindings.bindings(jointAssignment), new JointSearch(jointAssignment, model.domains().size(),
				bindings.variables().size() - model.domains().size()));
	}
}
