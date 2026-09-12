/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Canonical model and shared root tables for incremental Regional planning. */
final class RegionalSearchProblem {
	private final List<Variable> variables;
	private final List<Factor> factors;
	private final int decisionCount;
	private final ToDoubleFunction<List<Integer>> evaluator;
	private ExactPhysicalReducedSolver.CompactModel reducedRoot;
	private Limits reducedRootLimits;
	private long reducedRootNanos;
	private int reducedRootRequests;

	ExactPhysicalReducedSolver.CompactModel reducedRoot(Limits limits) {
		reducedRootRequests++;
		if(reducedRoot != null) {
			if(!limits.equals(reducedRootLimits))
				throw new IllegalArgumentException("REGIONAL_SHARED_ROOT_LIMITS_MISMATCH");
			return reducedRoot;
		}
		long started = System.nanoTime();
		try {
			reducedRoot = ExactPhysicalReducedSolver.reducedModel(decisionCount, variables, factors, limits);
			reducedRootLimits = limits;
			return reducedRoot;
		}
		finally { reducedRootNanos += System.nanoTime() - started; }
	}

	long reducedRootNanos() { return reducedRootNanos; }
	int reducedRootRequests() { return reducedRootRequests; }

	static RegionalSearchProblem generic(List<Variable> variables, List<Factor> factors) {
		return new RegionalSearchProblem(variables, factors, variables.size(),
			assignment -> evaluateFactors(variables, factors, assignment));
	}

	static RegionalSearchProblem physical(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactPhysicalForcedStateAudit.Constraint forced) {
		if(surface.owner() != model.analysis()
			|| !surface.ownerFingerprint().equals(model.analysis().analysisFingerprint())
			|| surface.variables().size() != model.variables().size()
			|| surface.exactSolverVariables().size() < model.variables().size())
			throw new IllegalArgumentException("REGIONAL_SEARCH_PHYSICAL_SURFACE_MISMATCH");
		for(int i = 0; i < model.variables().size(); i++)
			if(surface.variables().get(i) != model.variables().get(i)
				|| surface.exactSolverVariables().get(i) != model.variables().get(i))
				throw new IllegalArgumentException("REGIONAL_SEARCH_VARIABLE_IDENTITY_MISMATCH");
		List<Factor> hard = new ArrayList<>(model.hardFactors());
		if(forced != null)
			hard.add(forced.factor());
		List<Factor> encoded = new ArrayList<>(hard);
		encoded.addAll(surface.exactSolverFactors());
		return new RegionalSearchProblem(surface.exactSolverVariables(), encoded, model.variables().size(),
			assignment -> Double.isFinite(evaluateFactors(model.variables(), hard, assignment))
				? Double.longBitsToDouble(surface.evaluateCanonical(assignment)) : Double.POSITIVE_INFINITY);
	}

	private RegionalSearchProblem(List<Variable> variables, List<Factor> factors, int decisionCount,
		ToDoubleFunction<List<Integer>> evaluator) {
		this.variables = List.copyOf(variables);
		this.factors = List.copyOf(factors);
		this.decisionCount = decisionCount;
		this.evaluator = evaluator;
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		Set<String> keys = new LinkedHashSet<>();
		for(int i = 0; i < variables.size(); i++) {
			Variable variable = variables.get(i);
			if(positions.put(variable, i) != null || !keys.add(variable.key()))
				throw new IllegalArgumentException("REGIONAL_SEARCH_DUPLICATE_VARIABLE");
		}
		for(Factor factor : factors) {
			Set<Integer> unique = new LinkedHashSet<>();
			for(Variable variable : factor.scope()) {
				Integer index = positions.get(variable);
				if(index == null || !unique.add(index))
					throw new IllegalArgumentException("REGIONAL_SEARCH_FACTOR_SCOPE_INVALID");
			}
		}
	}

	List<Variable> variables() { return variables; }
	List<Factor> factors() { return factors; }
	int decisionCount() { return decisionCount; }

	double evaluate(List<Integer> assignment) {
		if(assignment == null || assignment.size() != decisionCount)
			throw new IllegalArgumentException("REGIONAL_SEARCH_ASSIGNMENT_SIZE_INVALID");
		for(int i = 0; i < assignment.size(); i++)
			if(assignment.get(i) == null || assignment.get(i) < 0 || assignment.get(i) >= variables.get(i).domainSize())
				throw new IllegalArgumentException("REGIONAL_SEARCH_ASSIGNMENT_VALUE_INVALID");
		double cost = evaluator.applyAsDouble(assignment);
		if(!Double.isFinite(cost) || cost < 0 || Double.doubleToRawLongBits(cost) == Long.MIN_VALUE)
			throw new IllegalArgumentException("REGIONAL_SEARCH_PLAN_NOT_FEASIBLE|cost=" + cost);
		return cost;
	}

	/** Reevaluate all factors with the same compensated sum as the canonical model. */
	static double evaluateFactors(List<Variable> variables, List<Factor> factors, List<Integer> assignment) {
		if(assignment == null || assignment.size() != variables.size())
			throw new IllegalArgumentException("REGIONAL_ASSIGNMENT_SIZE_INVALID");
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		Set<String> keys = new LinkedHashSet<>();
		for(int index = 0; index < variables.size(); index++) {
			Variable variable = variables.get(index);
			if(positions.put(variable, index) != null || !keys.add(variable.key()))
				throw new IllegalArgumentException("REGIONAL_VARIABLE_DUPLICATE");
			Integer value = assignment.get(index);
			if(value == null || value < 0 || value >= variable.domainSize())
				throw new IllegalArgumentException("REGIONAL_ASSIGNMENT_VALUE_INVALID");
		}
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		for(Factor factor : factors) {
			int[] values = new int[factor.scope().size()];
			for(int local = 0; local < values.length; local++) {
				Integer position = positions.get(factor.scope().get(local));
				if(position == null)
					throw new IllegalArgumentException("REGIONAL_FACTOR_SCOPE_INVALID");
				values[local] = assignment.get(position);
			}
			double cost = factor.cost(values);
			if(cost == Double.POSITIVE_INFINITY)
				return cost;
			sum.addBits(Double.doubleToRawLongBits(cost), "REGIONAL_COST_INVALID", "REGIONAL_TOTAL_INVALID");
		}
		return Double.longBitsToDouble(sum.totalBits("REGIONAL_TOTAL_INVALID"));
	}

	static boolean isResourceLimit(IllegalArgumentException failure) {
		String message = failure.getMessage();
		return message != null && (message.startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED")
			|| message.startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED")
			|| message.startsWith("EXACT_VE_FACTOR_CELL_OVERFLOW")
			|| message.startsWith("EXACT_VE_MATERIALIZED_CELL_OVERFLOW")
			|| message.startsWith("EXACT_VE_ELIMINATION_ASSIGNMENT_OVERFLOW"));
	}
}
