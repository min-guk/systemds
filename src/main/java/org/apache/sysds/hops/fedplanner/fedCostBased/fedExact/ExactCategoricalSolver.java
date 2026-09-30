/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;

/**
 * Exact deterministic min-sum variable elimination over finite categorical variables.
 *
 * <p>The implementation deliberately materializes dense factors.  A caller-supplied
 * cell budget is checked before any lazy factor is evaluated and before elimination
 * starts, so an oversized model fails closed rather than switching algorithms or
 * returning an approximation. Positive infinity denotes a forbidden assignment.</p>
 */
public final class ExactCategoricalSolver {
	private static final long REGIONAL_FAST_ORDER_MAXIMUM_ASSIGNMENTS = 100_000L;

	private ExactCategoricalSolver() { }

	@FunctionalInterface
	public interface CostFunction {
		/** Values are reused by the solver and must not be retained or modified. */
		double cost(int[] valuesInScopeOrder);
	}

	@FunctionalInterface
	public interface TieCostFunction {
		/** Non-negative additive secondary cost charged once for a selected variable value. */
		long cost(Variable variable, int value);
	}

	public record Variable(String key, int domainSize) {
		public Variable {
			if(key == null || key.isBlank())
				throw new IllegalArgumentException("EXACT_VE_VARIABLE_KEY_INVALID");
			if(domainSize <= 0)
				throw new IllegalArgumentException("EXACT_VE_DOMAIN_INVALID|key=" + key);
		}
	}

	public static final class Factor {
		private final List<Variable> scope;
		private final double[] denseValues;
		private final CostFunction evaluator;

		private Factor(List<Variable> scope, double[] denseValues, CostFunction evaluator,
			boolean copyDenseValues) {
			this.scope = List.copyOf(Objects.requireNonNull(scope, "scope"));
			this.denseValues = denseValues == null || !copyDenseValues
				? denseValues : denseValues.clone();
			this.evaluator = evaluator;
			if((denseValues == null) == (evaluator == null))
				throw new IllegalArgumentException("EXACT_VE_FACTOR_REPRESENTATION_INVALID");
		}

		public static Factor dense(List<Variable> scope, double... values) {
			return new Factor(scope, Objects.requireNonNull(values, "values"), null, true);
		}

		/** Internal ownership transfer; the caller must never mutate {@code values} again. */
		static Factor denseOwned(List<Variable> scope, double[] values) {
			return new Factor(scope, Objects.requireNonNull(values, "values"), null, false);
		}

		public static Factor lazy(List<Variable> scope, CostFunction evaluator) {
			return new Factor(scope, null, Objects.requireNonNull(evaluator, "evaluator"), false);
		}

		List<Variable> scope() { return scope; }
		double denseCostAt(int cell) {
			if(denseValues == null)
				throw new IllegalStateException("EXACT_VE_FACTOR_NOT_DENSE");
			return denseValues[cell];
		}
		double cost(int[] values) {
			if(values == null || values.length != scope.size())
				throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_SIZE_MISMATCH");
			if(evaluator != null)
				return evaluator.cost(values);
			int cell = 0;
			for(int index = 0; index < values.length; index++) {
				if(values[index] < 0 || values[index] >= scope.get(index).domainSize())
					throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_VALUE_INVALID");
				cell = cell * scope.get(index).domainSize() + values[index];
			}
			return denseValues[cell];
		}
	}

	public record Limits(long maximumFactorCells, long maximumMaterializedCells) {
		public Limits {
			if(maximumFactorCells <= 0 || maximumMaterializedCells <= 0)
				throw new IllegalArgumentException("EXACT_VE_LIMIT_INVALID");
		}
	}

	public record Statistics(List<String> eliminationOrder, int inducedWidth,
		long maximumFactorCells, long materializedFactorCells,
		long maximumEliminationAssignments, long eliminationAssignments) {
		public Statistics { eliminationOrder = List.copyOf(eliminationOrder); }
	}

	public record Result(double objective, List<Integer> assignmentInVariableOrder,
		Statistics statistics) {
		public Result { assignmentInVariableOrder = List.copyOf(assignmentInVariableOrder); }
		int value(Variable variable, List<Variable> variablesInCanonicalOrder) {
			int index = variablesInCanonicalOrder.indexOf(variable);
			if(index < 0)
				throw new IllegalArgumentException("EXACT_VE_RESULT_VARIABLE_UNKNOWN");
			return assignmentInVariableOrder.get(index);
		}
	}

	/**
	 * Immutable structural preparation for repeated solves over the same variables and
	 * factor objects. Lazy factors are materialized again on every solve, so callers may
	 * safely expose changing boundary values through their evaluators while reusing the
	 * validated scopes and deterministic elimination plan.
	 */
	static final class CompiledProblem {
		private final Prepared prepared;
		private final List<Factor> factors;

		private CompiledProblem(Prepared prepared, List<Factor> factors) {
			this.prepared = Objects.requireNonNull(prepared, "prepared");
			this.factors = List.copyOf(Objects.requireNonNull(factors, "factors"));
		}
	}

	/** Result of the bounded single-order compilation used only by Regional blocks. */
	static record RegionalCompilation(CompiledProblem compiled, boolean fastOrderAccepted,
		long fastOrderAssignments) { }

	/** Result of the common Global/Local symbolic elimination-order selection. */
	static record OrderCompilation(CompiledProblem compiled, boolean fastOrderConfigured,
		boolean fastOrderAccepted, boolean fastOrderFallback,
		long fastOrderAssignments) { }

	/**
	 * One solve-local, validated materialization of the caller's input factors.
	 * This representation intentionally contains no elimination plan: exact
	 * structure-preserving reducers may inspect the frozen raw tables before
	 * asking the dense solver to construct an elimination order.
	 */
	static final class FrozenInputs {
		private final int[] domains;
		private final List<int[]> scopes;
		private final List<double[]> values;

		private FrozenInputs(InputDefinition definition, List<DenseFactor> factors) {
			// Both inputs are solve-local immutable structures. Retaining their arrays avoids
			// copying every dense table once more between materialization and reduction.
			domains = definition.domains;
			scopes = definition.scopes;
			values = factors.stream().map(factor -> factor.values).toList();
		}

		int domainSize(int variable) { return domains[variable]; }
		int[] scope(int factor) { return scopes.get(factor); }
		double[] values(int factor) { return values.get(factor); }
		int factorCount() { return scopes.size(); }
	}

	/** Persistent exact min-sum message over a boundary of the original variables. */
	static final class BoundaryMessage {
		private final List<Variable> variables;
		private final int[] domains;
		private final List<Variable> scope;
		private final int[] scopeIndices;
		private final int[] strides;
		private final double[] values;
		private final double[] lowValues;
		private final double[] lowerValues;
		private final PreciseCost cachedMinimum;
		private final double cachedLowerBound;
		private final List<BoundaryMessage> children;
		private final int[] unionScope;
		private final int[] unionChoices;
		private final long retainedCells;
		private final long assignments;

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments) {
			this(variables, domains, scope, scopeIndices, values, lowValues, lowerValues,
				children, unionScope, unionChoices, retainedCells, assignments, null, 0d);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments,
			PreciseCost knownMinimum, double knownLowerBound) {
			this.variables = variables;
			this.domains = domains;
			this.scope = List.copyOf(scope);
			this.scopeIndices = scopeIndices.clone();
			this.values = values;
			this.lowValues = lowValues;
			this.lowerValues = lowerValues;
			this.children = List.copyOf(children);
			this.unionScope = unionScope == null ? null : unionScope.clone();
			this.unionChoices = unionChoices;
			this.retainedCells = retainedCells;
			this.assignments = assignments;
			strides = new int[scopeIndices.length];
			int stride = 1;
			for(int index = scopeIndices.length - 1; index >= 0; index--) {
				strides[index] = stride;
				stride = Math.multiplyExact(stride, domains[scopeIndices[index]]);
			}
			if(knownMinimum == null) {
				PreciseCost minimum = PreciseCost.POSITIVE_INFINITY;
				double lower = Double.POSITIVE_INFINITY;
				for(int cell = 0; cell < values.length; cell++) {
					PreciseCost candidate = valueAt(cell);
					if(candidate.compareTo(minimum) < 0)
						minimum = candidate;
					lower = Math.min(lower, lowerValues[cell]);
				}
				cachedMinimum = minimum;
				cachedLowerBound = lower;
			}
			else {
				cachedMinimum = knownMinimum;
				cachedLowerBound = knownLowerBound;
			}
		}

		List<Variable> scope() { return scope; }
		long cells() { return values.length; }
		long retainedCells() { return retainedCells; }
		long assignments() { return assignments; }

		double lowerBound() {
			return cachedLowerBound;
		}

		double minimum() {
			return cachedMinimum.rounded();
		}

		double minMarginal(Variable variable, int value) {
			int position = marginalPosition(variable);
			if(value < 0 || value >= variable.domainSize())
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VALUE_INVALID");
			PreciseCost minimum = PreciseCost.POSITIVE_INFINITY;
			for(int cell = 0; cell < values.length; cell++)
				if((cell / strides[position]) % domains[scopeIndices[position]] == value) {
					PreciseCost candidate = valueAt(cell);
					if(candidate.compareTo(minimum) < 0)
						minimum = candidate;
				}
			return minimum.rounded();
		}

		/** Computes every value marginal in one table scan; the caller owns the result. */
		double[] minMarginals(Variable variable) {
			int position = marginalPosition(variable);
			PreciseCost[] minima = new PreciseCost[variable.domainSize()];
			Arrays.fill(minima, PreciseCost.POSITIVE_INFINITY);
			for(int cell = 0; cell < values.length; cell++) {
				int value = (cell / strides[position]) % domains[scopeIndices[position]];
				PreciseCost candidate = valueAt(cell);
				if(candidate.compareTo(minima[value]) < 0)
					minima[value] = candidate;
			}
			double[] result = new double[minima.length];
			for(int value = 0; value < result.length; value++)
				result[value] = minima[value].rounded();
			return result;
		}

		private int marginalPosition(Variable variable) {
			int position = scope.indexOf(Objects.requireNonNull(variable, "variable"));
			if(position < 0)
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VARIABLE_UNKNOWN");
			return position;
		}

		void decodeInto(int[] assignment, List<Variable> allVariables) {
			if(assignment == null || assignment.length != variables.size())
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_ASSIGNMENT_SIZE_INVALID");
			if(!variables.equals(allVariables))
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VARIABLE_UNIVERSE_MISMATCH");
			decodeInto(assignment);
		}

		private void decodeInto(int[] assignment) {
			int cell = boundaryCell(assignment);
			if(values[cell] == Double.POSITIVE_INFINITY)
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_BOUNDARY_INFEASIBLE");
			if(children.isEmpty())
				return;
			if(unionChoices == null) {
				for(int variable : unionScope)
					assignment[variable] = 0;
				children.get(0).decodeInto(assignment);
				return;
			}
			int unionCell = unionChoices[cell];
			if(unionCell < 0)
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_BOUNDARY_INFEASIBLE");
			decode(unionCell, unionScope, domains, new int[unionScope.length], assignment);
			for(BoundaryMessage child : children)
				child.decodeInto(assignment);
		}

		private int boundaryCell(int[] assignment) {
			int cell = 0;
			for(int index = 0; index < scopeIndices.length; index++) {
				int value = assignment[scopeIndices[index]];
				if(value < 0 || value >= domains[scopeIndices[index]])
					throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VALUE_INVALID");
				cell += value * strides[index];
			}
			return cell;
		}

		private int boundaryCellUnchecked(int[] assignment) {
			int cell = 0;
			for(int index = 0; index < scopeIndices.length; index++)
				cell += assignment[scopeIndices[index]] * strides[index];
			return cell;
		}

		private PreciseCost value(int[] assignment) {
			return valueAt(boundaryCell(assignment));
		}

		private PreciseCost valueAt(int cell) {
			return new PreciseCost(values[cell], lowValues == null ? 0d : lowValues[cell], 0L);
		}

		private double lowerValue(int[] assignment) {
			return lowerValues[boundaryCell(assignment)];
		}
	}

	public static Statistics analyze(List<Variable> variables, List<Factor> factors, Limits limits) {
		return prepare(variables, factors, limits).statistics;
	}

	public static Result solve(List<Variable> variables, List<Factor> factors, Limits limits) {
		return solve(prepare(variables, factors, limits), factors, null);
	}

	/**
	 * Solves the primary binary64 objective exactly as exposed by {@link Result#objective()},
	 * then minimizes the caller-supplied additive tie cost without perturbing primary factors.
	 */
	public static Result solve(List<Variable> variables, List<Factor> factors, Limits limits,
		TieCostFunction tieCostFunction) {
		Objects.requireNonNull(tieCostFunction, "tieCostFunction");
		Prepared prepared = prepare(variables, factors, limits);
		return solve(prepared, factors, tieCostFunction);
	}

	static CompiledProblem compile(List<Variable> variables, List<Factor> factors,
		Limits limits) {
		return compile(variables, factors, limits, "exact-categorical");
	}

	static CompiledProblem compile(List<Variable> variables, List<Factor> factors,
		Limits limits, String caller) {
		return ExactEliminationOrderPolicy.compile(variables, factors, limits,
			ExactEliminationOrderPolicy.globalConfigured(), caller).compiled();
	}

	/**
	 * Avoids the four-order planning portfolio for a small Regional block when the
	 * existing minimum-separator-cells order is itself cheap and within all declared
	 * materialization limits. Larger blocks retain the ordinary portfolio. Input
	 * validation is shared by both paths and failures are never converted to fallback.
	 */
	static RegionalCompilation compileRegionalFast(List<Variable> variables,
		List<Factor> factors, Limits limits) {
		return compileRegionalFast(variables, factors, limits,
			REGIONAL_FAST_ORDER_MAXIMUM_ASSIGNMENTS);
	}

	static RegionalCompilation compileRegionalFast(List<Variable> variables,
		List<Factor> factors, Limits limits, long maximumAssignments) {
		OrderCompilation compilation = compileWithFastOrder(variables, factors, limits,
			true, maximumAssignments, "EXACT_VE_REGIONAL_FAST_ORDER_WORK_INVALID");
		return new RegionalCompilation(compilation.compiled(),
			compilation.fastOrderAccepted(), compilation.fastOrderAssignments());
	}

	static OrderCompilation compileWithFastOrder(List<Variable> variables,
		List<Factor> factors, Limits limits, boolean fastOrder, long maximumAssignments) {
		return compileWithFastOrder(variables, factors, limits, fastOrder,
			maximumAssignments, "EXACT_VE_FAST_ORDER_WORK_INVALID");
	}

	private static OrderCompilation compileWithFastOrder(List<Variable> variables,
		List<Factor> factors, Limits limits, boolean fastOrder, long maximumAssignments,
		String invalidLimit) {
		if(maximumAssignments <= 0)
			throw new IllegalArgumentException(invalidLimit + "|value=" + maximumAssignments);
		InputDefinition input = validateInputs(variables, factors, limits);
		if(!fastOrder) {
			Plan selected = minimumMaterializationPlan(
				input.variables, input.domains, input.scopes);
			return new OrderCompilation(
				new CompiledProblem(prepare(input, limits, selected), factors),
				false, false, false, 0L);
		}
		Plan fast = eliminationPlan(input.variables, input.domains, input.scopes,
			PlanOrdering.MIN_SEPARATOR_CELLS);
		PlanMetrics metrics = planMetrics(fast, input.domains);
		boolean accepted = metrics.eliminationAssignments != Long.MAX_VALUE
			&& metrics.eliminationAssignments <= maximumAssignments
			&& planFitsLimits(input, metrics, limits);
		Plan selected = accepted ? fast
			: minimumMaterializationPlan(input.variables, input.domains, input.scopes,
				new ScoredPlan(fast, metrics, PlanOrdering.MIN_SEPARATOR_CELLS.ordinal()));
		return new OrderCompilation(new CompiledProblem(prepare(input, limits, selected), factors),
			true, accepted, !accepted, metrics.eliminationAssignments);
	}

	/**
	 * Compiles using a caller-supplied complete elimination order. The order is only a
	 * structural hint: current variables, domains, factor scopes, and every resource
	 * limit are validated and rebuilt exactly as for an ordinary compilation.
	 */
	static CompiledProblem compilePreferred(List<Variable> variables, List<Factor> factors,
		Limits limits, List<String> preferredEliminationOrder) {
		InputDefinition input = validateInputs(variables, factors, limits);
		Plan plan = preferredPlan(input, preferredEliminationOrder);
		return new CompiledProblem(prepare(input, limits, plan), factors);
	}

	static Result solve(CompiledProblem compiled) {
		Objects.requireNonNull(compiled, "compiled");
		return solve(compiled.prepared, compiled.factors, null);
	}

	/** Isolated opt-in integer arithmetic; legacy/local callers never enter this path. */
	static Result solveDyadic(CompiledProblem compiled, ExactDyadicCosts.Certificate certificate) {
		Objects.requireNonNull(compiled, "compiled");
		Objects.requireNonNull(certificate, "certificate");
		if(!certificate.supported())
			throw new IllegalArgumentException("EXACT_VE_DYADIC_CERTIFICATE_REJECTED|"
				+ certificate.reason());
		certificate.validateCompiledProblem(compiled);
		return solve(compiled.prepared, compiled.factors, null, null, certificate);
	}

	static Statistics statistics(CompiledProblem compiled) {
		return Objects.requireNonNull(compiled, "compiled").prepared.statistics;
	}

	static FrozenInputs freezeInputs(List<Variable> variables, List<Factor> factors,
		Limits limits) {
		InputDefinition definition = validateInputs(variables, factors, limits);
		return new FrozenInputs(definition, materializeInputs(definition, factors));
	}

	static void validateInputStructure(List<Variable> variables, List<Factor> factors,
		Limits limits) {
		validateInputs(variables, factors, limits);
	}

	static Factor freezeValidatedFactor(Factor factor) {
		Objects.requireNonNull(factor, "factor");
		if(factor.denseValues != null)
			return factor;
		int cells = 1;
		for(Variable variable : factor.scope)
			cells = Math.multiplyExact(cells, variable.domainSize());
		double[] values = new double[cells];
		int[] local = new int[factor.scope.size()];
		int[] domains = factor.scope.stream().mapToInt(Variable::domainSize).toArray();
		for(int cell = 0; cell < cells; cell++) {
			values[cell] = factor.evaluator.cost(local);
			validateCost(values[cell]);
			// Same last-axis-fastest callback order without divisions at every cell.
			for(int position = local.length - 1; position >= 0; position--) {
				if(++local[position] < domains[position])
					break;
				local[position] = 0;
			}
		}
		return Factor.denseOwned(factor.scope, values);
	}

	static BoundaryMessage boundaryLeaf(List<Variable> allVariables, Factor factor,
		Limits limits) {
		return boundaryLeaves(allVariables, List.of(factor), limits).get(0);
	}

	static List<BoundaryMessage> boundaryLeaves(List<Variable> allVariables,
		List<Factor> factors, Limits limits) {
		InputDefinition input;
		try {
			input = validateInputs(allVariables, factors, limits);
		}
		catch(IllegalArgumentException ex) {
			if(isExactResourceError(ex.getMessage()))
				throw incrementalResource("leaves", "cells", ex.getMessage());
			throw ex;
		}
		// Reject known malformed costs before invoking any lazy evaluator in the batch.
		for(Factor factor : factors)
			if(factor.denseValues != null)
				for(double value : factor.denseValues)
					if(value < 0d)
						throw new IllegalArgumentException(
							"INCREMENTAL_MESSAGE_COST_INVALID|value=" + value);
		List<DenseFactor> denseFactors = materializeInputs(input, factors);
		List<BoundaryMessage> leaves = new ArrayList<>(factors.size());
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			DenseFactor dense = denseFactors.get(factorIndex);
			for(double value : dense.values)
				if(value < 0d)
					throw new IllegalArgumentException(
						"INCREMENTAL_MESSAGE_COST_INVALID|value=" + value);
			leaves.add(new BoundaryMessage(input.variables, input.domains,
				factors.get(factorIndex).scope, input.scopes.get(factorIndex), dense.values,
				null, dense.values, List.of(), null, null,
				dense.values.length, dense.values.length));
		}
		return List.copyOf(leaves);
	}

	/**
	 * Removes only domain-one axes. Their sole value needs neither enumeration nor an
	 * argmin table, so the projected message aliases all numeric state from its child.
	 */
	static BoundaryMessage projectSingletons(BoundaryMessage input) {
		Objects.requireNonNull(input, "input");
		int retainedCount = 0;
		for(int variable : input.scopeIndices)
			if(input.domains[variable] > 1)
				retainedCount++;
		if(retainedCount == input.scopeIndices.length)
			return input;
		int[] retainedScope = new int[retainedCount];
		int[] removedScope = new int[input.scopeIndices.length - retainedCount];
		List<Variable> retainedVariables = new ArrayList<>(retainedCount);
		int retained = 0;
		int removed = 0;
		for(int variable : input.scopeIndices) {
			if(input.domains[variable] == 1)
				removedScope[removed++] = variable;
			else {
				retainedScope[retained++] = variable;
				retainedVariables.add(input.variables.get(variable));
			}
		}
		return new BoundaryMessage(input.variables, input.domains, retainedVariables,
			retainedScope, input.values, input.lowValues, input.lowerValues, List.of(input),
			removedScope, null, 0L, 0L, input.cachedMinimum, input.cachedLowerBound);
	}

	static BoundaryMessage mergeBoundary(BoundaryMessage left, BoundaryMessage right,
		List<Variable> outputBoundary, Limits limits, long maximumAssignments) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");
		return mergeBoundary(List.of(left, right), outputBoundary, limits, maximumAssignments);
	}

	static BoundaryMessage mergeBoundary(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits, long maximumAssignments) {
		Objects.requireNonNull(inputMessages, "inputMessages");
		Objects.requireNonNull(outputBoundary, "outputBoundary");
		Objects.requireNonNull(limits, "limits");
		if(inputMessages.isEmpty())
			throw new IllegalArgumentException("INCREMENTAL_MESSAGE_INPUT_EMPTY");
		if(maximumAssignments <= 0)
			throw incrementalResource("merge", "assignment-limit", maximumAssignments);
		BoundaryMessage first = Objects.requireNonNull(inputMessages.get(0), "input message");
		long combinedLength = 0L;
		for(BoundaryMessage message : inputMessages) {
			Objects.requireNonNull(message, "input message");
			if(!first.variables.equals(message.variables))
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VARIABLE_UNIVERSE_MISMATCH");
			combinedLength += message.scopeIndices.length;
			if(combinedLength > Integer.MAX_VALUE)
				throw incrementalResource("merge", "scope-indices", combinedLength);
		}

		int[] combinedScope = new int[(int)combinedLength];
		int combinedPosition = 0;
		for(BoundaryMessage message : inputMessages) {
			System.arraycopy(message.scopeIndices, 0, combinedScope, combinedPosition,
				message.scopeIndices.length);
			combinedPosition += message.scopeIndices.length;
		}
		Arrays.sort(combinedScope);
		int unionSize = 0;
		for(int variable : combinedScope)
			if(unionSize == 0 || combinedScope[unionSize - 1] != variable)
				combinedScope[unionSize++] = variable;
		int[] unionScope = Arrays.copyOf(combinedScope, unionSize);
		Map<Variable,Integer> variableIndex = new HashMap<>(unionSize);
		for(int variable : unionScope)
			variableIndex.put(first.variables.get(variable), variable);

		Set<Integer> outputUnique = new HashSet<>();
		int[] outputScope = new int[outputBoundary.size()];
		for(int position = 0; position < outputScope.length; position++) {
			Integer variable = variableIndex.get(Objects.requireNonNull(
				outputBoundary.get(position), "output variable"));
			if(variable == null || !outputUnique.add(variable))
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_BOUNDARY_INVALID");
			outputScope[position] = variable;
		}
		int[] internalScope = new int[unionScope.length - outputScope.length];
		int internalPosition = 0;
		for(int variable : unionScope)
			if(!outputUnique.contains(variable))
				internalScope[internalPosition++] = variable;

		long unionCells = boundaryCells(unionScope, first.domains, "merge", "union-cells");
		long outputCells = boundaryCells(outputScope, first.domains, "merge", "output-cells");
		long internalCells = boundaryCells(internalScope, first.domains,
			"merge", "internal-cells");
		if(unionCells > maximumAssignments)
			throw incrementalResource("merge", "assignments", unionCells);
		if(outputCells > limits.maximumFactorCells())
			throw incrementalResource("merge", "factor-cells", outputCells);
		long retained = boundaryMultiply(outputCells, 4L, "merge", "retained-overflow");
		if(retained > limits.maximumMaterializedCells())
			throw incrementalResource("merge", "retained-cells", retained);

		int cells = (int)outputCells;
		double[] values = new double[cells];
		double[] lowValues = new double[cells];
		double[] lowerValues = new double[cells];
		int[] choices = new int[cells];
		Arrays.fill(choices, -1);
		int[] assignment = new int[first.variables.size()];
		int[] outputLocal = new int[outputScope.length];
		int[] internalLocal = new int[internalScope.length];
		PreciseCost messageMinimum = PreciseCost.POSITIVE_INFINITY;
		double messageLowerBound = Double.POSITIVE_INFINITY;
		for(int outputCell = 0; outputCell < cells; outputCell++) {
			decode(outputCell, outputScope, first.domains, outputLocal, assignment);
			PreciseCost best = PreciseCost.POSITIVE_INFINITY;
			double bestLower = Double.POSITIVE_INFINITY;
			int bestUnionCell = -1;
			for(int internalCell = 0; internalCell < (int)internalCells; internalCell++) {
				decode(internalCell, internalScope, first.domains, internalLocal, assignment);
				int childCell = first.boundaryCellUnchecked(assignment);
				PreciseCost candidate = first.valueAt(childCell);
				double candidateLower = first.lowerValues[childCell];
				for(int messageIndex = 1; messageIndex < inputMessages.size(); messageIndex++) {
					BoundaryMessage message = inputMessages.get(messageIndex);
					childCell = message.boundaryCellUnchecked(assignment);
					candidate = candidate.plus(message.valueAt(childCell));
					candidateLower = addBoundaryLower(candidateLower,
						message.lowerValues[childCell]);
				}
				if(candidate.compareTo(best) < 0) {
					best = candidate;
					bestUnionCell = encode(unionScope, first.domains, assignment);
				}
				bestLower = Math.min(bestLower, candidateLower);
			}
			values[outputCell] = best.high;
			lowValues[outputCell] = best.low;
			lowerValues[outputCell] = bestLower;
			choices[outputCell] = bestUnionCell;
			if(best.compareTo(messageMinimum) < 0)
				messageMinimum = best;
			messageLowerBound = Math.min(messageLowerBound, bestLower);
		}
		return new BoundaryMessage(first.variables, first.domains, outputBoundary, outputScope,
			values, lowValues, lowerValues, inputMessages, unionScope, choices,
			retained, unionCells, messageMinimum, messageLowerBound);
	}

	private static Result solve(Prepared prepared, List<Factor> factors,
		TieCostFunction tieCostFunction) {
		return solve(prepared, factors, tieCostFunction, null);
	}

	/** Test-only observation expands small tables without changing production storage. */
	static Result solveWithStepsForTest(CompiledProblem compiled, TieCostFunction tieCostFunction,
		Consumer<EliminationSnapshot> observer) {
		return solve(compiled.prepared, compiled.factors, tieCostFunction,
			Objects.requireNonNull(observer, "observer"));
	}

	static record EliminationSnapshot(int variable, int[] scope, double[] high, double[] low,
		int[] choices, boolean sparse, int storedCells) { }

	private static Result solve(Prepared prepared, List<Factor> factors,
		TieCostFunction tieCostFunction, Consumer<EliminationSnapshot> observer) {
		return solve(prepared, factors, tieCostFunction, observer, null);
	}

	private static Result solve(Prepared prepared, List<Factor> factors,
		TieCostFunction tieCostFunction, Consumer<EliminationSnapshot> observer,
		ExactDyadicCosts.Certificate dyadic) {
		// Only the two no-callback entry points pass null. Caller callbacks remain
		// observable even for infeasible candidates and therefore retain dense visits.
		List<DenseFactor> active = materializeInputs(prepared, factors);
		if(dyadic != null)
			active = dyadicInputs(prepared.domains, active, dyadic);
		boolean sparseEligible = tieCostFunction == null
			&& (dyadic != null || sparseRangeSafe(prepared, active));
		List<Backpointer> backpointers = new ArrayList<>(prepared.variables.size());
		int[] global = new int[prepared.variables.size()];

		for(Step step : prepared.steps) {
			List<DenseFactor> bucket = new ArrayList<>();
			for(DenseFactor factor : active)
				if(factor.contains(step.variable))
					bucket.add(factor);
			active.removeAll(bucket);
			int outputCells = checkedCells(step.separator, prepared.domains,
				"EXACT_VE_FACTOR_CELL_OVERFLOW");
			if(sparseEligible) {
				BucketProjection projection = new BucketProjection(step, prepared.domains, bucket);
				List<ExactFiniteSupportJoin.Relation> supports = new ArrayList<>();
				for(DenseFactor factor : bucket) {
					int[] finite = factor.projectedFiniteCells(projection);
					if(finite != null)
						supports.add(new ExactFiniteSupportJoin.Relation(factor.scope, finite));
				}
				if(!projection.identity || !supports.isEmpty()
					|| bucket.stream().anyMatch(factor -> factor.valueMaps != null)) {
					SparseStep sparse = eliminateSparse(step, bucket, supports, projection, outputCells,
						dyadic != null);
					active.add(sparse.factor);
					backpointers.add(sparse.backpointer);
					observeStep(observer, sparse.factor, sparse.backpointer, outputCells, prepared.domains);
					continue;
				}
			}
			int[] baseCells = new int[bucket.size()];
			int[] valueStrides = new int[bucket.size()];
			for(int index = 0; index < bucket.size(); index++)
				valueStrides[index] = bucket.get(index).stride(step.variable);
			int domain = prepared.domains[step.variable];
			FiniteRowIndex finiteRows = tieCostFunction == null && !bucket.isEmpty()
				? FiniteRowIndex.create(bucket.get(0), step.variable, domain) : null;
			if(finiteRows != null && FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("Exact-FiniteRows", "variableIndex=" + step.variable
					+ " domain=" + domain + " outputCells=" + outputCells
					+ " logicalAssignments=" + (long)outputCells * domain
					+ " indexedCandidates=" + ((long)outputCells * domain
						- finiteRows.forbiddenCells * (outputCells / finiteRows.rowCount))
					+ " bitmapWords=" + finiteRows.forbidden.length);
			double[] output = new double[outputCells];
			double[] outputLow = null;
			long[] outputTie = null;
			int[] choices = new int[outputCells];
			int[] separatorValues = new int[step.separator.length];
			for(int cell = 0; cell < outputCells; cell++) {
				decode(cell, step.separator, prepared.domains, separatorValues, global);
				// Every bucket scope is within separator + eliminated variable. Its
				// separator contribution is invariant across the candidate-value loop.
				global[step.variable] = 0;
				for(int index = 0; index < bucket.size(); index++)
					baseCells[index] = bucket.get(index).cell(global);
				PreciseCost best = PreciseCost.POSITIVE_INFINITY;
				double bestRounded = Double.POSITIVE_INFINITY;
				int bestValue = 0;
				int finiteRow = finiteRows == null ? 0 : finiteRows.rowOffset(baseCells[0]);
				for(int value = finiteRows == null ? 0 : finiteRows.next(finiteRow, 0);
					value < domain;
					value = finiteRows == null ? value + 1 : finiteRows.next(finiteRow, value + 1)) {
					global[step.variable] = value;
					long tieCost = tieCostFunction == null ? 0L : tieCostFunction.cost(
						prepared.variables.get(step.variable), value);
					if(tieCost < 0)
						throw new IllegalArgumentException("EXACT_VE_TIE_COST_INVALID");
					PreciseCost candidate = (dyadic == null
						? preciseSum(bucket, global, baseCells, valueStrides, value)
						: dyadicSum(bucket, global, baseCells, valueStrides, value)).plusTie(tieCost);
					// A previous best already rounded successfully. Preserve candidate-first
					// validation and the same rounded-primary/secondary/first-value ordering.
					double candidateRounded = dyadic == null ? candidate.rounded() : 0d;
					int byPrimary = dyadic == null ? Double.compare(candidateRounded, bestRounded)
						: compareWords(candidate.high, candidate.low, best.high, best.low);
					if(byPrimary < 0 || byPrimary == 0 && candidate.tieCost < best.tieCost) {
						best = candidate;
						bestRounded = candidateRounded;
						bestValue = value;
					}
				}
				output[cell] = best.high;
				if(best.low != 0d) {
					if(outputLow == null)
						outputLow = new double[outputCells];
					outputLow[cell] = best.low;
				}
				if(best.tieCost != 0L) {
					if(outputTie == null)
						outputTie = new long[outputCells];
					outputTie[cell] = best.tieCost;
				}
				choices[cell] = bestValue;
			}
			DenseFactor reduced = new DenseFactor(
				step.separator, prepared.domains, output, outputLow, outputTie);
			active.add(reduced);
			Backpointer backpointer = new Backpointer(step.variable, step.separator, choices, null);
			backpointers.add(backpointer);
			observeStep(observer, reduced, backpointer, outputCells, prepared.domains);
		}

		PreciseCost total = dyadic == null ? preciseSum(active, global)
			: dyadicSum(active, global, null, null, 0);
		double objective = dyadic == null || total.high == Double.POSITIVE_INFINITY
			? total.rounded() : Double.longBitsToDouble(ExactDyadicCosts.ofWords(
				(long)total.high, (long)total.low).toDoubleBits(dyadic.q()));
		if(objective == Double.POSITIVE_INFINITY)
			throw new IllegalArgumentException("EXACT_VE_NO_FEASIBLE_ASSIGNMENT");
		for(int index = backpointers.size() - 1; index >= 0; index--) {
			Backpointer backpointer = backpointers.get(index);
			int cell = backpointer.cell(global, prepared.domains);
			global[backpointer.variable] = backpointer.choice(cell);
		}
		List<Integer> assignment = Arrays.stream(global).boxed().toList();
		return new Result(objective, assignment, prepared.statistics);
	}

	/**
	 * A sufficient, deliberately loose range certificate, not a resource limit.
	 * With F+V <= 2^20 and |input| <= 2^400, total input L1 <= 2^420.
	 * Each normalized DD addition increases retained L1 by at most (1+2^-53)^5.
	 * Active messages consume disjoint original factor occurrences, so a selected
	 * expression contains at most F+V additions: retained L1 < 2^421 and even a
	 * 16x bound on internal operations is < 2^425. Finite arithmetic cannot overflow.
	 * Thus later infinities may reject a tuple without hiding an earlier overflow.
	 * Outside this certificate the original ordered dense arithmetic is retained.
	 */
	private static boolean sparseRangeSafe(Prepared prepared, List<DenseFactor> factors) {
		if((long)prepared.variables.size() + factors.size() > (1L << 20))
			return false;
		for(DenseFactor factor : factors) {
			int finiteCount = 0;
			for(double value : factor.values) {
				if(value != Double.POSITIVE_INFINITY && Math.abs(value) > 0x1.0p400)
					return false;
				if(value != Double.POSITIVE_INFINITY)
					finiteCount++;
			}
			factor.finiteCount = finiteCount;
		}
		return true;
	}

	private record SparseMinimum(PreciseCost cost, double rounded, int choice) { }
	private record SparseStep(DenseFactor factor, Backpointer backpointer) { }

	/** Exact value-profile classes, never a change to logical scopes or bucket order. */
	private static final class BucketProjection {
		private final int[] originalDomains;
		private final int[] domains;
		private final int[] union;
		private final int[][] classes;
		private final int[][] representatives;
		private final int[][] weights;
		private final long preparationNanos;
		private final boolean identity;

		private BucketProjection(Step step, int[] originalDomains, List<DenseFactor> bucket) {
			long started = FederatedPlannerTrace.isEnabled() ? System.nanoTime() : 0L;
			this.originalDomains = originalDomains;
			domains = originalDomains.clone();
			classes = new int[domains.length][];
			representatives = new int[domains.length][];
			weights = new int[domains.length][];
			union = new int[step.separator.length + 1];
			union[0] = step.variable;
			System.arraycopy(step.separator, 0, union, 1, step.separator.length);
			boolean allIdentity = true;
			for(int variable : union) {
				int[] common = null;
				for(DenseFactor factor : bucket) {
					int axis = factor.axis(variable);
					if(axis < 0)
						continue;
					int[] next = factor.originalValueClasses(axis, originalDomains[variable]);
					// Each map is first-original-value ordered; borrowing the first
					// immutable partition avoids two domain-sized temporary arrays.
					common = common == null ? next : ExactFactorValueClasses.refine(common, next);
					// Once all original values differ, no later refinement can merge them.
					if(common[common.length - 1] == common.length - 1)
						break;
				}
				if(common == null)
					common = new int[originalDomains[variable]];
				classes[variable] = common;
				representatives[variable] = ExactFactorValueClasses.representatives(common);
				domains[variable] = representatives[variable].length;
				allIdentity &= domains[variable] == originalDomains[variable];
				weights[variable] = new int[domains[variable]];
				for(int valueClass : common)
					weights[variable][valueClass]++;
			}
			identity = allIdentity;
			preparationNanos = started == 0L ? 0L : System.nanoTime() - started;
		}

		private void lift(int[] quotient, int[] original) {
			for(int variable : union)
				original[variable] = representatives[variable][quotient[variable]];
		}

		private int[][] maps(int[] scope) {
			boolean identity = true;
			for(int variable : scope)
				identity &= domains[variable] == originalDomains[variable];
			if(identity)
				return null;
			int[][] maps = new int[scope.length][];
			for(int axis = 0; axis < scope.length; axis++)
				maps[axis] = classes[scope[axis]];
			return maps;
		}

		private long logicalFiniteCells(DenseFactor factor) {
			long count = 0L;
			for(int stored = 0; stored < factor.values.length; stored++) {
				if(factor.values[stored] == Double.POSITIVE_INFINITY)
					continue;
				int cell = factor.sparseCells == null ? stored : factor.sparseCells[stored];
				long multiplicity = 1L;
				for(int axis = 0; axis < factor.scope.length; axis++)
					multiplicity *= weights[factor.scope[axis]][
						cell / factor.strides[axis] % factor.dimensions[axis]];
				count += multiplicity;
			}
			return count;
		}
	}

	/** Retains all minima, switching only their storage when a message is not sparse. */
	private static final class SparseAccumulator {
		private final int outputCells;
		private final boolean dyadic;
		private Map<Integer,SparseMinimum> minima = new HashMap<>();
		private double[] high;
		private double[] low;
		private int[] choices;
		private int finiteOutputs;

		private SparseAccumulator(int outputCells, boolean dyadic) {
			this.outputCells = outputCells;
			this.dyadic = dyadic;
		}

		private void offer(int cell, int value, PreciseCost candidate) {
			double rounded = dyadic ? candidate.high : candidate.rounded();
			if(rounded == Double.POSITIVE_INFINITY)
				return;
			if(high != null) {
				double prior = dyadic ? 0d : high[cell] + (low == null ? 0d : low[cell]);
				int comparison = dyadic ? compareWords(candidate.high, candidate.low,
					high[cell], low == null ? 0d : low[cell]) : Double.compare(rounded, prior);
				if(comparison < 0 || comparison == 0 && value < choices[cell]) {
					if(high[cell] == Double.POSITIVE_INFINITY)
						finiteOutputs++;
					store(cell, candidate, value);
				}
				return;
			}
			SparseMinimum prior = minima.get(cell);
			int comparison = prior == null ? -1 : dyadic
				? compareWords(candidate.high, candidate.low, prior.cost.high, prior.cost.low)
				: Double.compare(rounded, prior.rounded);
			if(comparison < 0 || comparison == 0 && value < prior.choice)
				minima.put(cell, new SparseMinimum(candidate, rounded, value));
			// This is a representation crossover, never a search/candidate limit.
			// Boxed hash entries cost much more than a dense high/low/choice slot.
			if(minima.size() >= Math.max(64, outputCells / 16)) {
				high = new double[outputCells];
				Arrays.fill(high, Double.POSITIVE_INFINITY);
				choices = new int[outputCells];
				for(Map.Entry<Integer,SparseMinimum> entry : minima.entrySet())
					store(entry.getKey(), entry.getValue().cost, entry.getValue().choice);
				finiteOutputs = minima.size();
				minima = null;
			}
		}

		private void store(int cell, PreciseCost cost, int value) {
			high[cell] = cost.high;
			if(low == null && cost.low != 0d)
				low = new double[high.length];
			if(low != null)
				low[cell] = cost.low != 0d ? cost.low : 0d;
			choices[cell] = value;
		}

		private SparseStep finish(Step step, int[] domains) {
			int[] sparseCells = null;
			if(high == null) {
				int[] cells = minima.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
				high = new double[cells.length];
				choices = new int[cells.length];
				for(int index = 0; index < cells.length; index++) {
					SparseMinimum minimum = minima.get(cells[index]);
					store(index, minimum.cost, minimum.choice);
				}
				finiteOutputs = cells.length;
				// Full finite keys are exactly 0..outputCells-1; direct indexing suffices.
				sparseCells = cells.length == outputCells ? null : cells;
			}
			DenseFactor factor = new DenseFactor(step.separator, domains, high, low, null, sparseCells);
			factor.finiteCount = finiteOutputs;
			return new SparseStep(factor, new Backpointer(step.variable, step.separator, choices, sparseCells));
		}
	}

	private static SparseStep eliminateSparse(Step step, List<DenseFactor> bucket,
		List<ExactFiniteSupportJoin.Relation> supports, BucketProjection projection, int logicalOutputCells,
		boolean dyadic) {
		int[] domains = projection.domains;
		int outputCells = checkedCells(step.separator, domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
		long supportRows = 0L;
		for(ExactFiniteSupportJoin.Relation relation : supports)
			supportRows += relation.size();
		long started = FederatedPlannerTrace.isEnabled() ? System.nanoTime() : 0L;
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-SparseJoinBegin", "variableIndex=" + step.variable
				+ " logicalAssignments=" + (long)logicalOutputCells * projection.originalDomains[step.variable]
				+ " outputCells=" + logicalOutputCells + " supportRows=" + supportRows
				+ " quotientAssignments=" + (long)outputCells * domains[step.variable]
				+ " quotientOutputCells=" + outputCells
				+ " partitionNanos=" + projection.preparationNanos);
		SparseAccumulator accumulator = new SparseAccumulator(outputCells, dyadic);
		ExactFiniteSupportJoin.Work work;
		if(dyadic && supports.isEmpty())
			work = eliminateDyadicSeparatorMajor(step, bucket, projection, outputCells, accumulator);
		else {
			int[] original = new int[domains.length];
			work = ExactFiniteSupportJoin.forEach(
				projection.union, domains, supports, assignment -> {
				// Support traversal is independent of arithmetic order. Preserve every
				// original factor, including all-finite costs omitted from the join.
				int[] selected = assignment;
				if(!projection.identity) {
					projection.lift(assignment, original);
					selected = original;
				}
				PreciseCost candidate = dyadic ? dyadicSum(bucket, selected, null, null, 0)
					: preciseSum(bucket, selected);
				int cell = encode(step.separator, domains, assignment);
				accumulator.offer(cell, selected[step.variable], candidate);
				});
		}
		SparseStep stored = accumulator.finish(step, domains);
		stored.factor.valueMaps = projection.maps(step.separator);
		Backpointer backpointer = new Backpointer(step.variable, step.separator,
			stored.backpointer.choices, stored.backpointer.sparseCells,
			stored.factor.valueMaps, stored.factor.strides);
		SparseStep result = new SparseStep(stored.factor, backpointer);
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-SparseJoinEnd", "variableIndex=" + step.variable
				+ " visitedRelationRows=" + work.visitedRelationRows()
				+ " evaluatedAssignments=" + work.emittedAssignments()
				+ " finiteOutputCells=" + projection.logicalFiniteCells(result.factor)
				+ " outputCells=" + logicalOutputCells + " storedFiniteOutputCells=" + accumulator.finiteOutputs
				+ " quotientOutputCells=" + outputCells
				+ " elapsedNanos=" + (System.nanoTime() - started));
		return result;
	}

	/**
	 * The empty-support dyadic join is a complete Cartesian product. Traverse it
	 * separator-major and retain only O(bucket) eliminated-axis metadata; this
	 * changes neither candidates nor original factor arithmetic order.
	 */
	private static ExactFiniteSupportJoin.Work eliminateDyadicSeparatorMajor(Step step,
		List<DenseFactor> bucket, BucketProjection projection, int outputCells,
		SparseAccumulator accumulator) {
		int[] domains = projection.domains;
		int[] representatives = projection.representatives[step.variable];
		int[] axes = new int[bucket.size()];
		int[] strides = new int[bucket.size()];
		int[] zeroCoordinates = new int[bucket.size()];
		int firstRepresentative = representatives[0];
		for(int index = 0; index < bucket.size(); index++) {
			DenseFactor factor = bucket.get(index);
			int axis = factor.axis(step.variable);
			axes[index] = axis;
			strides[index] = factor.strides[axis];
			zeroCoordinates[index] = factor.coordinate(axis, firstRepresentative);
		}

		int[] quotient = new int[domains.length];
		int[] original = new int[domains.length];
		int[] separatorValues = new int[step.separator.length];
		int[] baseCells = new int[bucket.size()];
		for(int outputCell = 0; outputCell < outputCells; outputCell++) {
			decode(outputCell, step.separator, domains, separatorValues, quotient);
			quotient[step.variable] = 0;
			projection.lift(quotient, original);
			for(int index = 0; index < bucket.size(); index++)
				baseCells[index] = bucket.get(index).cell(original);
			PreciseCost best = PreciseCost.POSITIVE_INFINITY;
			int bestRepresentative = firstRepresentative;
			for(int representative : representatives) {
				PreciseCost candidate = dyadicSeparatorSum(bucket, axes, strides,
					zeroCoordinates, baseCells, representative);
				int comparison = compareWords(candidate.high, candidate.low, best.high, best.low);
				if(comparison < 0 || comparison == 0 && representative < bestRepresentative) {
					best = candidate;
					bestRepresentative = representative;
				}
			}
			accumulator.offer(outputCell, bestRepresentative, best);
		}
		return new ExactFiniteSupportJoin.Work(0L, (long)outputCells * representatives.length);
	}

	/** Base-2^53 sum over affine eliminated-axis cells, preserving factor order. */
	private static PreciseCost dyadicSeparatorSum(List<DenseFactor> bucket, int[] axes,
		int[] strides, int[] zeroCoordinates, int[] baseCells, int representative) {
		final long mask = (1L << 53) - 1;
		long high = 0L;
		long low = 0L;
		for(int index = 0; index < bucket.size(); index++) {
			DenseFactor factor = bucket.get(index);
			int coordinate = factor.coordinate(axes[index], representative);
			int logicalCell = baseCells[index]
				+ (coordinate - zeroCoordinates[index]) * strides[index];
			int cell = factor.storageCell(logicalCell);
			if(cell < 0 || factor.values[cell] == Double.POSITIVE_INFINITY)
				return PreciseCost.POSITIVE_INFINITY;
			long lowSum = low + (factor.lowValues == null ? 0L : (long)factor.lowValues[cell]);
			high += (long)factor.values[cell] + (lowSum >>> 53);
			if(high > mask)
				throw new IllegalArgumentException("EXACT_VE_DYADIC_WORD_OVERFLOW");
			low = lowSum & mask;
		}
		return new PreciseCost(high, low, 0L);
	}

	private static void observeStep(Consumer<EliminationSnapshot> observer, DenseFactor factor,
		Backpointer backpointer, int outputCells, int[] domains) {
		if(observer == null)
			return;
		double[] high = new double[outputCells];
		double[] low = new double[outputCells];
		int[] choices = new int[outputCells];
		int[] global = new int[domains.length];
		int[] local = new int[factor.scope.length];
		for(int cell = 0; cell < outputCells; cell++) {
			decode(cell, factor.scope, domains, local, global);
			int stored = factor.storageCell(factor.cell(global));
			high[cell] = stored < 0 ? Double.POSITIVE_INFINITY : factor.values[stored];
			low[cell] = stored < 0 || factor.lowValues == null ? 0d : factor.lowValues[stored];
			choices[cell] = backpointer.choice(backpointer.cell(global, domains));
		}
		observer.accept(new EliminationSnapshot(backpointer.variable, factor.scope.clone(),
			high, low, choices, factor.sparseCells != null, factor.values.length));
	}

	/** Evaluates one complete assignment using the same validation and arithmetic as solve. */
	public static double evaluate(List<Variable> variables, List<Factor> factors, Limits limits,
		List<Integer> assignmentInVariableOrder) {
		Prepared prepared = prepare(variables, factors, limits);
		if(assignmentInVariableOrder == null || assignmentInVariableOrder.size() != variables.size())
			throw new IllegalArgumentException("EXACT_VE_ASSIGNMENT_SIZE_MISMATCH");
		int[] assignment = new int[variables.size()];
		for(int index = 0; index < assignment.length; index++) {
			Integer value = assignmentInVariableOrder.get(index);
			if(value == null || value < 0 || value >= prepared.domains[index])
				throw new IllegalArgumentException("EXACT_VE_ASSIGNMENT_VALUE_INVALID|index=" + index);
			assignment[index] = value;
		}
		return preciseSum(materializeInputs(prepared, factors), assignment).rounded();
	}

	private static Prepared prepare(List<Variable> variables, List<Factor> factors, Limits limits) {
		InputDefinition input = validateInputs(variables, factors, limits);
		return prepare(input, limits,
			minimumMaterializationPlan(input.variables, input.domains, input.scopes));
	}

	private static Prepared prepare(InputDefinition input, Limits limits, Plan plan) {
		List<Variable> canonical = input.variables;
		int[] domains = input.domains;
		List<int[]> scopes = input.scopes;
		long inputCells = input.inputCells;
		long totalCells = inputCells;
		long maximumCells = input.maximumInputCells;
		String maximumSource = "input";
		for(Step step : plan.steps) {
			long cells = checkedCells(step.separator, domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
			if(cells > maximumCells) {
				maximumCells = cells;
				maximumSource = "eliminate=" + canonical.get(step.variable).key()
					+ "|separator=" + Arrays.stream(step.separator)
						.mapToObj(variable -> canonical.get(variable).key() + ':' + domains[variable])
						.toList();
			}
			totalCells = checkedAdd(totalCells, cells, "EXACT_VE_MATERIALIZED_CELL_OVERFLOW");
		}
		if(maximumCells > limits.maximumFactorCells())
			throw new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells=" + maximumCells
				+ "|limit=" + limits.maximumFactorCells() + '|' + maximumSource);
		if(totalCells > limits.maximumMaterializedCells())
			throw new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells=" + totalCells
				+ "|limit=" + limits.maximumMaterializedCells());
		long maximumAssignments = 0;
		long assignments = 0;
		for(Step step : plan.steps) {
			long cells = checkedCells(step.separator, domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
			if(cells > Long.MAX_VALUE / domains[step.variable])
				throw new IllegalArgumentException("EXACT_VE_ELIMINATION_ASSIGNMENT_OVERFLOW");
			long stepAssignments = cells * domains[step.variable];
			maximumAssignments = Math.max(maximumAssignments, stepAssignments);
			assignments = checkedAdd(assignments, stepAssignments,
				"EXACT_VE_ELIMINATION_ASSIGNMENT_OVERFLOW");
		}
		Statistics statistics = new Statistics(plan.steps.stream()
			.map(step -> canonical.get(step.variable).key()).toList(), plan.inducedWidth,
			maximumCells, totalCells, maximumAssignments, assignments);
		return new Prepared(canonical, domains, scopes, plan.steps, statistics);
	}

	private static Plan preferredPlan(InputDefinition input,
		List<String> preferredEliminationOrder) {
		if(preferredEliminationOrder == null
			|| preferredEliminationOrder.size() != input.variables.size())
			throw new IllegalArgumentException("EXACT_VE_PREFERRED_ORDER_INVALID");
		Map<String,Integer> positionByKey = new HashMap<>();
		for(int index = 0; index < input.variables.size(); index++)
			positionByKey.put(input.variables.get(index).key(), index);
		Set<Integer> seen = new HashSet<>();
		int[] order = new int[preferredEliminationOrder.size()];
		for(int index = 0; index < order.length; index++) {
			Integer position = positionByKey.get(preferredEliminationOrder.get(index));
			if(position == null || !seen.add(position))
				throw new IllegalArgumentException("EXACT_VE_PREFERRED_ORDER_INVALID");
			order[index] = position;
		}
		return eliminationPlan(input.variables, input.scopes, order);
	}

	private static InputDefinition validateInputs(List<Variable> variables, List<Factor> factors,
		Limits limits) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(limits, "limits");
		List<Variable> canonical = List.copyOf(variables);
		Map<Variable,Integer> index = new LinkedHashMap<>();
		Map<String,Variable> keys = new HashMap<>();
		int[] domains = new int[canonical.size()];
		for(int i = 0; i < canonical.size(); i++) {
			Variable variable = Objects.requireNonNull(canonical.get(i), "variable");
			if(index.put(variable, i) != null || keys.put(variable.key(), variable) != null)
				throw new IllegalArgumentException("EXACT_VE_VARIABLE_DUPLICATE|key=" + variable.key());
			domains[i] = variable.domainSize();
		}

		List<int[]> scopes = new ArrayList<>(factors.size());
		long inputCells = 0;
		long maximumInputCells = 0;
		for(Factor factor : factors) {
			Objects.requireNonNull(factor, "factor");
			int[] scope = new int[factor.scope.size()];
			Set<Integer> unique = new HashSet<>();
			for(int i = 0; i < scope.length; i++) {
				Integer variableIndex = index.get(factor.scope.get(i));
				if(variableIndex == null)
					throw new IllegalArgumentException("EXACT_VE_FACTOR_VARIABLE_UNKNOWN");
				if(!unique.add(variableIndex))
					throw new IllegalArgumentException("EXACT_VE_FACTOR_VARIABLE_DUPLICATE");
				scope[i] = variableIndex;
			}
			int cells = checkedCells(scope, domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
			if(factor.denseValues != null && factor.denseValues.length != cells)
				throw new IllegalArgumentException("EXACT_VE_DENSE_FACTOR_SIZE_MISMATCH");
			inputCells = checkedAdd(inputCells, cells, "EXACT_VE_MATERIALIZED_CELL_OVERFLOW");
			maximumInputCells = Math.max(maximumInputCells, cells);
			scopes.add(scope);
		}
		if(maximumInputCells > limits.maximumFactorCells())
			throw new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells="
				+ maximumInputCells + "|limit=" + limits.maximumFactorCells() + "|input");
		if(inputCells > limits.maximumMaterializedCells())
			throw new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells="
				+ inputCells + "|limit=" + limits.maximumMaterializedCells());
		// Validate every already-materialized value before invoking any lazy
		// evaluator. This keeps malformed dense input from causing observable
		// partial lazy evaluation.
		for(Factor factor : factors)
			if(factor.denseValues != null)
				for(double value : factor.denseValues)
					validateCost(value);
		return new InputDefinition(canonical, domains, List.copyOf(scopes), inputCells,
			maximumInputCells);
	}

	/**
	 * Chooses between deterministic exact variable-elimination orders without changing
	 * variables, domains, factors, or objective semantics. Classical min-fill ignores
	 * categorical domain cardinalities and can therefore create a smaller-width but
	 * substantially larger dense factor. Evaluate a small fixed portfolio of greedy
	 * orders symbolically and retain the order with the lowest actual dense-factor
	 * footprint before any factor is materialized.
	 */
	private static Plan minimumMaterializationPlan(List<Variable> variables, int[] domains,
		List<int[]> initialScopes) {
		return minimumMaterializationPlan(variables, domains, initialScopes, null);
	}

	private static Plan minimumMaterializationPlan(List<Variable> variables, int[] domains,
		List<int[]> initialScopes, ScoredPlan precomputed) {
		List<PlanOrdering> orderings = List.of(
			PlanOrdering.MIN_FILL,
			PlanOrdering.MIN_SEPARATOR_CELLS,
			PlanOrdering.MIN_ELIMINATION_ASSIGNMENTS,
			PlanOrdering.MIN_DEGREE);
		StringBuilder diagnostic = FederatedPlannerTrace.isEnabled() ? new StringBuilder() : null;
		ScoredPlan best = null;
		for(int priority = 0; priority < orderings.size(); priority++) {
			ScoredPlan candidate;
			if(precomputed != null && precomputed.priority == priority)
				candidate = precomputed;
			else {
				Plan plan = eliminationPlan(variables, domains, initialScopes, orderings.get(priority));
				candidate = new ScoredPlan(plan, planMetrics(plan, domains), priority);
			}
			if(diagnostic != null) {
				if(priority > 0)
					diagnostic.append("; ");
				diagnostic.append(orderings.get(priority)).append(':').append(candidate.metrics);
			}
			if(best == null || compare(candidate, best) < 0)
				best = candidate;
		}
		Objects.requireNonNull(best, "best elimination plan");
		if(diagnostic != null) {
			// A single receipt binds all already-computed candidates to the selected
			// ordering. Input cells are separate: PlanMetrics counts intermediate tables.
			long inputCells = 0L;
			long maximumInputCells = 0L;
			for(int[] scope : initialScopes) {
				long cells = saturatedCells(scope, domains);
				inputCells = saturatedAdd(inputCells, cells);
				maximumInputCells = Math.max(maximumInputCells, cells);
			}
			FederatedPlannerTrace.logGlobal("Exact-OrderPortfolio",
				"variables=" + variables.size() + " inputFactors=" + initialScopes.size()
					+ " inputCells=" + inputCells + " maximumInputCells=" + maximumInputCells
					+ " candidates=[" + diagnostic + "]"
					+ " selectedOrdering=" + orderings.get(best.priority)
					+ " selectedPriority=" + best.priority);
		}
		return best.plan;
	}

	private static int compare(ScoredPlan left, ScoredPlan right) {
		int comparison = Long.compare(left.metrics.maximumFactorCells,
			right.metrics.maximumFactorCells);
		if(comparison != 0)
			return comparison;
		comparison = Long.compare(left.metrics.materializedFactorCells,
			right.metrics.materializedFactorCells);
		if(comparison != 0)
			return comparison;
		comparison = Long.compare(left.metrics.maximumEliminationAssignments,
			right.metrics.maximumEliminationAssignments);
		if(comparison != 0)
			return comparison;
		comparison = Long.compare(left.metrics.eliminationAssignments,
			right.metrics.eliminationAssignments);
		if(comparison != 0)
			return comparison;
		comparison = Integer.compare(left.plan.inducedWidth, right.plan.inducedWidth);
		return comparison != 0 ? comparison : Integer.compare(left.priority, right.priority);
	}

	private static PlanMetrics planMetrics(Plan plan, int[] domains) {
		long maximumFactorCells = 0L;
		long materializedFactorCells = 0L;
		long maximumEliminationAssignments = 0L;
		long eliminationAssignments = 0L;
		for(Step step : plan.steps) {
			long cells = saturatedCells(step.separator, domains);
			long assignments = saturatedMultiply(cells, domains[step.variable]);
			maximumFactorCells = Math.max(maximumFactorCells, cells);
			materializedFactorCells = saturatedAdd(materializedFactorCells, cells);
			maximumEliminationAssignments = Math.max(maximumEliminationAssignments, assignments);
			eliminationAssignments = saturatedAdd(eliminationAssignments, assignments);
		}
		return new PlanMetrics(maximumFactorCells, materializedFactorCells,
			maximumEliminationAssignments, eliminationAssignments);
	}

	private static boolean planFitsLimits(InputDefinition input, PlanMetrics metrics,
		Limits limits) {
		long maximumCells = Math.max(input.maximumInputCells, metrics.maximumFactorCells);
		long totalCells = saturatedAdd(input.inputCells, metrics.materializedFactorCells);
		return maximumCells <= limits.maximumFactorCells()
			&& totalCells <= limits.maximumMaterializedCells();
	}

	private static Plan eliminationPlan(List<Variable> variables, int[] domains,
		List<int[]> initialScopes, PlanOrdering ordering) {
		List<Set<Integer>> graph = interactionGraph(variables.size(), initialScopes);
		Set<Integer> remaining = new HashSet<>();
		for(int i = 0; i < variables.size(); i++)
			remaining.add(i);
		List<Step> steps = new ArrayList<>(variables.size());
		int width = 0;
		while(!remaining.isEmpty()) {
			Comparator<Integer> comparator = switch(ordering) {
				case MIN_FILL -> Comparator
					.comparingLong((Integer variable) -> fillEdges(variable, graph, remaining))
					.thenComparingLong(variable -> neighborCells(variable, graph, remaining, domains));
				case MIN_SEPARATOR_CELLS -> Comparator
					.comparingLong((Integer variable) -> neighborCells(variable, graph, remaining, domains))
					.thenComparingLong(variable -> fillEdges(variable, graph, remaining));
				case MIN_ELIMINATION_ASSIGNMENTS -> Comparator
					.comparingLong((Integer variable) -> eliminationAssignments(
						variable, graph, remaining, domains))
					.thenComparingLong(variable -> neighborCells(variable, graph, remaining, domains))
					.thenComparingLong(variable -> fillEdges(variable, graph, remaining));
				case MIN_DEGREE -> Comparator
					.comparingLong((Integer variable) -> remainingDegree(variable, graph, remaining))
					.thenComparingLong(variable -> neighborCells(variable, graph, remaining, domains))
					.thenComparingLong(variable -> fillEdges(variable, graph, remaining));
			};
			int selected = remaining.stream().min(comparator
				.thenComparing(variable -> variables.get(variable).key())).orElseThrow();
			int[] separator = graph.get(selected).stream().filter(remaining::contains)
				.sorted().mapToInt(Integer::intValue).toArray();
			width = Math.max(width, separator.length);
			for(int i = 0; i < separator.length; i++)
				for(int j = i + 1; j < separator.length; j++) {
					graph.get(separator[i]).add(separator[j]);
					graph.get(separator[j]).add(separator[i]);
				}
			remaining.remove(selected);
			steps.add(new Step(selected, separator));
		}
		return new Plan(List.copyOf(steps), width);
	}

	private static Plan eliminationPlan(List<Variable> variables,
		List<int[]> initialScopes, int[] order) {
		List<Set<Integer>> graph = interactionGraph(variables.size(), initialScopes);
		Set<Integer> remaining = new HashSet<>();
		for(int index = 0; index < variables.size(); index++)
			remaining.add(index);
		List<Step> steps = new ArrayList<>(variables.size());
		int width = 0;
		for(int selected : order) {
			if(!remaining.remove(selected))
				throw new IllegalArgumentException("EXACT_VE_PREFERRED_ORDER_INVALID");
			int[] separator = graph.get(selected).stream().filter(remaining::contains)
				.sorted().mapToInt(Integer::intValue).toArray();
			width = Math.max(width, separator.length);
			for(int i = 0; i < separator.length; i++)
				for(int j = i + 1; j < separator.length; j++) {
					graph.get(separator[i]).add(separator[j]);
					graph.get(separator[j]).add(separator[i]);
				}
			steps.add(new Step(selected, separator));
		}
		if(!remaining.isEmpty())
			throw new IllegalArgumentException("EXACT_VE_PREFERRED_ORDER_INVALID");
		return new Plan(List.copyOf(steps), width);
	}

	private static List<Set<Integer>> interactionGraph(int variableCount,
		List<int[]> initialScopes) {
		List<Set<Integer>> graph = new ArrayList<>(variableCount);
		for(int index = 0; index < variableCount; index++)
			graph.add(new HashSet<>());
		for(int[] scope : initialScopes)
			for(int i = 0; i < scope.length; i++)
				for(int j = i + 1; j < scope.length; j++) {
					graph.get(scope[i]).add(scope[j]);
					graph.get(scope[j]).add(scope[i]);
				}
		return graph;
	}

	private static long eliminationAssignments(int variable, List<Set<Integer>> graph,
		Set<Integer> remaining, int[] domains) {
		return saturatedMultiply(neighborCells(variable, graph, remaining, domains), domains[variable]);
	}

	private static long remainingDegree(int variable, List<Set<Integer>> graph,
		Set<Integer> remaining) {
		return graph.get(variable).stream().filter(remaining::contains).count();
	}

	private static long fillEdges(int variable, List<Set<Integer>> graph, Set<Integer> remaining) {
		int[] neighbors = graph.get(variable).stream().filter(remaining::contains)
			.sorted().mapToInt(Integer::intValue).toArray();
		long missing = 0;
		for(int i = 0; i < neighbors.length; i++)
			for(int j = i + 1; j < neighbors.length; j++)
				if(!graph.get(neighbors[i]).contains(neighbors[j]))
					missing++;
		return missing;
	}

	private static long neighborCells(int variable, List<Set<Integer>> graph,
		Set<Integer> remaining, int[] domains) {
		long cells = 1;
		for(int neighbor : graph.get(variable)) {
			if(!remaining.contains(neighbor))
				continue;
			if(cells > Long.MAX_VALUE / domains[neighbor])
				return Long.MAX_VALUE;
			cells *= domains[neighbor];
		}
		return cells;
	}

	private static long saturatedCells(int[] scope, int[] domains) {
		long cells = 1L;
		for(int variable : scope)
			cells = saturatedMultiply(cells, domains[variable]);
		return cells;
	}

	private static long saturatedMultiply(long left, long right) {
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static List<DenseFactor> materializeInputs(Prepared prepared, List<Factor> factors) {
		return materializeInputs(new InputDefinition(prepared.variables, prepared.domains,
			prepared.scopes, 0L, 0L), factors);
	}

	private static List<DenseFactor> materializeInputs(InputDefinition prepared,
		List<Factor> factors) {
		List<DenseFactor> result = new ArrayList<>(factors.size());
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			Factor factor = factors.get(factorIndex);
			int[] scope = prepared.scopes.get(factorIndex);
			checkedCells(scope, prepared.domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
			// All input caps and scopes were validated before any callback. Reuse the
			// same last-axis-fastest freezer; dense owned tables remain shared as before.
			double[] values = freezeValidatedFactor(factor).denseValues;
			result.add(new DenseFactor(scope, prepared.domains, values, null, null));
		}
		return result;
	}

	private static PreciseCost preciseSum(List<DenseFactor> factors, int[] global) {
		return preciseSum(factors, global, null, null, 0);
	}

	/** Validate the certificate against these exact tables, not just caller-supplied scalars. */
	private static List<DenseFactor> dyadicInputs(int[] domains, List<DenseFactor> factors,
		ExactDyadicCosts.Certificate certificate) {
		List<DenseFactor> converted = new ArrayList<>(factors.size());
		ExactDyadicCosts maximum = ExactDyadicCosts.ofWords(0, 0);
		for(DenseFactor factor : factors) {
			double[] high = new double[factor.values.length];
			double[] low = null;
			ExactDyadicCosts factorMaximum = ExactDyadicCosts.ofWords(0, 0);
			for(int cell = 0; cell < high.length; cell++) {
				double value = factor.values[cell];
				if(value == Double.POSITIVE_INFINITY) {
					high[cell] = value;
					continue;
				}
				ExactDyadicCosts exact = ExactDyadicCosts.fromRawBits(
					Double.doubleToRawLongBits(value), certificate.q());
				high[cell] = exact.highWord();
				if(exact.lowWord() != 0L) {
					if(low == null)
						low = new double[high.length];
					low[cell] = exact.lowWord();
				}
				if(exact.compareTo(factorMaximum) > 0)
					factorMaximum = exact;
			}
			maximum = maximum.add(factorMaximum);
			converted.add(new DenseFactor(factor.scope, domains, high, low, null));
		}
		if(maximum.bitLength() > certificate.maximumSumBits())
			throw new IllegalArgumentException("EXACT_VE_DYADIC_MAXIMUM_BOUND_MISMATCH");
		return converted;
	}

	/** Base-2^53 integer words, never double-double residues or individually rounded costs. */
	private static PreciseCost dyadicSum(List<DenseFactor> factors, int[] global,
		int[] baseCells, int[] valueStrides, int value) {
		final long mask = (1L << 53) - 1;
		long high = 0;
		long low = 0;
		for(int index = 0; index < factors.size(); index++) {
			DenseFactor factor = factors.get(index);
			int cell = factor.summedCell(global, baseCells, valueStrides, index, value);
			if(cell < 0 || factor.values[cell] == Double.POSITIVE_INFINITY)
				return PreciseCost.POSITIVE_INFINITY;
			// Each word is at most 53 bits, so both long additions below fit exactly.
			long lowSum = low + (factor.lowValues == null ? 0L : (long)factor.lowValues[cell]);
			high += (long)factor.values[cell] + (lowSum >>> 53);
			if(high > mask)
				throw new IllegalArgumentException("EXACT_VE_DYADIC_WORD_OVERFLOW");
			low = lowSum & mask;
		}
		return new PreciseCost(high, low, 0L);
	}

	private static int compareWords(double leftHigh, double leftLow,
		double rightHigh, double rightLow) {
		int high = Double.compare(leftHigh, rightHigh);
		return high != 0 ? high : Double.compare(leftLow, rightLow);
	}

	/** The optional indexes are solve-local and follow exactly the factor list's order. */
	private static PreciseCost preciseSum(List<DenseFactor> factors, int[] global,
		int[] baseCells, int[] valueStrides, int value) {
		double high = 0d;
		double low = 0d;
		long tie = 0L;
		for(int index = 0; index < factors.size(); index++) {
			DenseFactor factor = factors.get(index);
			int cell = factor.summedCell(global, baseCells, valueStrides, index, value);
			if(cell < 0)
				return PreciseCost.POSITIVE_INFINITY;
			double valueHigh = factor.values[cell];
			double valueLow = factor.lowValues == null ? 0d : factor.lowValues[cell];
			long valueTie = factor.tieCosts == null ? 0L : factor.tieCosts[cell];
			if(valueHigh == Double.POSITIVE_INFINITY)
				return new PreciseCost(valueHigh, valueLow, valueTie);
			if(valueHigh == 0d && valueLow == 0d && low == 0d) {
				// Hard constraints commonly contribute zero. With no retained residue,
				// the full normalization below only canonicalizes signed zero; no
				// objective overflow is possible. Keep secondary overflow in order.
				high += 0d;
				low = 0d;
			}
			else {
				// Keep PreciseCost.plus's expression/check order, without two temporary
				// records per factor. The raw high/low/tie parity is regression-locked.
				double sum = high + valueHigh;
				if(!Double.isFinite(sum))
					throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
				double virtual = sum - high;
				double error = (high - (sum - virtual)) + (valueHigh - virtual);
				error += low + valueLow;
				if(!Double.isFinite(error))
					throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
				double normalizedHigh = sum + error;
				if(!Double.isFinite(normalizedHigh))
					throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
				low = error - (normalizedHigh - sum);
				high = normalizedHigh;
			}
			tie = addTieCost(tie, valueTie);
		}
		return new PreciseCost(high, low, tie);
	}

	private static long addTieCost(long left, long right) {
		try {
			return Math.addExact(left, right);
		}
		catch(ArithmeticException ex) {
			throw new IllegalArgumentException("EXACT_VE_TIE_COST_OVERFLOW", ex);
		}
	}

	private static boolean isExactResourceError(String message) {
		return message != null && (message.startsWith("EXACT_VE_FACTOR_CELL_OVERFLOW")
			|| message.startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED")
			|| message.startsWith("EXACT_VE_MATERIALIZED_CELL_OVERFLOW")
			|| message.startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"));
	}

	private static IllegalArgumentException incrementalResource(String operation,
		String kind, Object value) {
		return new IllegalArgumentException("INCREMENTAL_MESSAGE_RESOURCE|operation="
			+ operation + "|kind=" + kind + "|value=" + value);
	}

	private static long boundaryCells(int[] scope, int[] domains, String operation,
		String kind) {
		long cells = 1L;
		for(int variable : scope) {
			if(cells > Integer.MAX_VALUE / domains[variable])
				throw incrementalResource(operation, kind, "overflow");
			cells *= domains[variable];
		}
		return cells;
	}

	private static long boundaryMultiply(long left, long right, String operation,
		String kind) {
		if(left > Long.MAX_VALUE / right)
			throw incrementalResource(operation, kind, "overflow");
		return left * right;
	}

	private static double addBoundaryLower(double left, double right) {
		if(left == Double.POSITIVE_INFINITY || right == Double.POSITIVE_INFINITY)
			return Double.POSITIVE_INFINITY;
		double sum = left + right;
		if(sum == Double.POSITIVE_INFINITY)
			return Double.MAX_VALUE;
		double virtual = sum - left;
		double error = (left - (sum - virtual)) + (right - virtual);
		return error < 0d ? Math.nextDown(sum) : sum;
	}

	private static void validateCost(double value) {
		if(Double.isNaN(value) || value == Double.NEGATIVE_INFINITY || isNegativeZero(value))
			throw new IllegalArgumentException("EXACT_VE_FACTOR_COST_INVALID|value=" + value);
	}

	private static boolean isNegativeZero(double value) {
		return Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0.0d);
	}

	private static int checkedCells(int[] scope, int[] domains, String reason) {
		long cells = 1;
		for(int variable : scope) {
			if(cells > Integer.MAX_VALUE / domains[variable])
				throw new IllegalArgumentException(reason);
			cells *= domains[variable];
		}
		return (int)cells;
	}

	private static long checkedAdd(long left, long right, String reason) {
		if(left > Long.MAX_VALUE - right)
			throw new IllegalArgumentException(reason);
		return left + right;
	}

	private static void decode(int cell, int[] scope, int[] domains,
		int[] local, int[] global) {
		for(int index = scope.length - 1; index >= 0; index--) {
			int value = cell % domains[scope[index]];
			cell /= domains[scope[index]];
			local[index] = value;
			global[scope[index]] = value;
		}
	}

	private static int encode(int[] scope, int[] domains, int[] global) {
		int cell = 0;
		for(int variable : scope)
			cell = cell * domains[variable] + global[variable];
		return cell;
	}

	private record Step(int variable, int[] separator) {
		Step { separator = separator.clone(); }
	}
	private record Plan(List<Step> steps, int inducedWidth) { }
	private enum PlanOrdering {
		MIN_FILL,
		MIN_SEPARATOR_CELLS,
		MIN_ELIMINATION_ASSIGNMENTS,
		MIN_DEGREE
	}
	private record PlanMetrics(long maximumFactorCells, long materializedFactorCells,
		long maximumEliminationAssignments, long eliminationAssignments) { }
	private record ScoredPlan(Plan plan, PlanMetrics metrics, int priority) { }
	private record Prepared(List<Variable> variables, int[] domains, List<int[]> scopes,
		List<Step> steps, Statistics statistics) { }
	private record InputDefinition(List<Variable> variables, int[] domains, List<int[]> scopes,
		long inputCells, long maximumInputCells) { }
	private record Backpointer(int variable, int[] separator, int[] choices, int[] sparseCells,
		int[][] valueMaps, int[] strides) {
		private Backpointer(int variable, int[] separator, int[] choices, int[] sparseCells) {
			this(variable, separator, choices, sparseCells, null, null);
		}

		private int cell(int[] global, int[] domains) {
			if(valueMaps == null)
				return encode(separator, domains, global);
			int cell = 0;
			for(int axis = 0; axis < separator.length; axis++)
				cell += valueMaps[axis][global[separator[axis]]] * strides[axis];
			return cell;
		}

		private int choice(int cell) {
			int stored = sparseCells == null ? cell : Arrays.binarySearch(sparseCells, cell);
			return stored < 0 ? 0 : choices[stored];
		}
	}

	/**
	 * Legacy normalized double-double accumulator. Reduced factors retain residues,
	 * but legacy rounded-primary ties are not a universal exact-sum guarantee.
	 * The separately certified dyadic kernel reuses this pair only as integer-word
	 * storage and never invokes its floating-point addition or comparison methods.
	 */
	private record PreciseCost(double high, double low, long tieCost)
		implements Comparable<PreciseCost> {
		private static final PreciseCost POSITIVE_INFINITY =
			new PreciseCost(Double.POSITIVE_INFINITY, 0d, 0L);

		private PreciseCost plus(PreciseCost that) {
			if(high == Double.POSITIVE_INFINITY || that.high == Double.POSITIVE_INFINITY)
				return POSITIVE_INFINITY;
			double sum = high + that.high;
			if(!Double.isFinite(sum))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double virtual = sum - high;
			double error = (high - (sum - virtual)) + (that.high - virtual);
			error += low + that.low;
			if(!Double.isFinite(error))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedHigh = sum + error;
			if(!Double.isFinite(normalizedHigh))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedLow = error - (normalizedHigh - sum);
			long combinedTie = addTieCost(tieCost, that.tieCost);
			return new PreciseCost(normalizedHigh, normalizedLow, combinedTie);
		}

		private PreciseCost plusTie(long extraTieCost) {
			if(extraTieCost == 0L)
				return this;
			return new PreciseCost(high, low, addTieCost(tieCost, extraTieCost));
		}

		private double rounded() {
			if(high == Double.POSITIVE_INFINITY)
				return high;
			double result = high + low;
			if(!Double.isFinite(result))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			return result;
		}

		@Override
		public int compareTo(PreciseCost that) {
			// Result.objective is a binary64 value, so alternatives whose retained
			// double-double sums round to the same representable objective are true
			// solver ties.  Let the caller-controlled domain order resolve them instead
			// of making an unobservable residue an accidental policy decision.
			int byPrimary = Double.compare(rounded(), that.rounded());
			return byPrimary != 0 ? byPrimary : Long.compare(tieCost, that.tieCost);
		}
	}

	/**
	 * A solve-local index of first-factor infinities. Skipping these values is exact:
	 * preciseSum would return infinity before performing any arithmetic. A later
	 * infinity cannot be used this way because an earlier sum may overflow.
	 */
	private static final class FiniteRowIndex {
		private final int domain;
		private final int stride;
		private final int rowCount;
		private final int wordsPerRow;
		private final long[] forbidden;
		private final long forbiddenCells;

		private FiniteRowIndex(int domain, int stride, int rowCount, int wordsPerRow,
			long[] forbidden, long forbiddenCells) {
			this.domain = domain;
			this.stride = stride;
			this.rowCount = rowCount;
			this.wordsPerRow = wordsPerRow;
			this.forbidden = forbidden;
			this.forbiddenCells = forbiddenCells;
		}

		private static FiniteRowIndex create(DenseFactor first, int variable, int domain) {
			if(first.finiteCount == first.values.length)
				return null;
			int stride = first.stride(variable);
			int rows = first.values.length / domain;
			int words = (domain - 1) / Long.SIZE + 1;
			// words <= domain, so the index cannot exceed the validated input cells.
			long[] forbidden = null;
			long count = 0L;
			for(int cell = 0; cell < first.values.length; cell++) {
				if(first.values[cell] != Double.POSITIVE_INFINITY)
					continue;
				if(forbidden == null)
					forbidden = new long[rows * words];
				int row = cell / (stride * domain) * stride + cell % stride;
				int value = cell / stride % domain;
				forbidden[row * words + value / Long.SIZE] |= 1L << (value % Long.SIZE);
				count++;
			}
			return forbidden == null ? null
				: new FiniteRowIndex(domain, stride, rows, words, forbidden, count);
		}

		private int rowOffset(int baseCell) {
			return (baseCell / (stride * domain) * stride + baseCell % stride) * wordsPerRow;
		}

		private int next(int rowOffset, int start) {
			if(start >= domain)
				return domain;
			int word = start / Long.SIZE;
			long candidates = ~forbidden[rowOffset + word] & (-1L << (start % Long.SIZE));
			while(true) {
				if(candidates != 0L)
					return Math.min(domain, word * Long.SIZE + Long.numberOfTrailingZeros(candidates));
				if(++word == wordsPerRow)
					return domain;
				candidates = ~forbidden[rowOffset + word];
			}
		}
	}

	/** Primitive inverse fibres; absent stored coordinates allocate no per-coordinate objects. */
	private static final class InverseAxis {
		private final boolean identity;
		private final int[] keys;
		private final int[] heads;
		private final int[] counts;
		private final int[] next;

		private InverseAxis(DenseFactor factor, int axis, int[] representatives) {
			boolean same = representatives.length == factor.dimensions[axis];
			for(int value = 0; same && value < representatives.length; value++)
				same = factor.coordinate(axis, representatives[value]) == value;
			identity = same;
			if(identity) {
				keys = heads = counts = next = null;
				return;
			}
			boolean dense = factor.dimensions[axis] <= 2L * representatives.length;
			int capacity = 2;
			while(!dense && capacity * 3L / 4 < representatives.length) {
				if(capacity == 1 << 30) {
					dense = true;
					break;
				}
				capacity <<= 1;
			}
			keys = dense ? null : new int[capacity];
			if(keys != null)
				Arrays.fill(keys, -1);
			heads = new int[dense ? factor.dimensions[axis] : capacity];
			Arrays.fill(heads, -1);
			counts = new int[heads.length];
			next = new int[representatives.length];
			for(int value = representatives.length - 1; value >= 0; value--) {
				int coordinate = factor.coordinate(axis, representatives[value]);
				int slot = slot(coordinate);
				if(keys != null)
					keys[slot] = coordinate;
				next[value] = heads[slot];
				heads[slot] = value;
				counts[slot]++;
			}
		}

		private int slot(int coordinate) {
			if(keys == null)
				return coordinate;
			int hash = coordinate * 0x9E3779B9;
			int slot = (hash ^ (hash >>> 16)) & (keys.length - 1);
			while(keys[slot] != -1 && keys[slot] != coordinate)
				slot = (slot + 1) & (keys.length - 1);
			return slot;
		}

		private int first(int coordinate) { return identity ? coordinate : heads[slot(coordinate)]; }
		private int size(int coordinate) { return identity ? 1 : counts[slot(coordinate)]; }
		private int next(int value) { return identity ? -1 : next[value]; }
	}

	private static final class DenseFactor {
		private final int[] scope;
		private final int[] strides;
		private final int[] dimensions;
		private final double[] values;
		private final double[] lowValues;
		private final long[] tieCosts;
		private final int[] sparseCells;
		private int finiteCount = -1;
		// Maps and response classes are solve-local. Logical scope never shrinks.
		private int[][] valueMaps;
		private int[][] responseClasses;

		private DenseFactor(int[] scope, int[] domains, double[] values, double[] lowValues,
			long[] tieCosts) {
			this(scope, domains, values, lowValues, tieCosts, null);
		}

		private DenseFactor(int[] scope, int[] domains, double[] values, double[] lowValues,
			long[] tieCosts, int[] sparseCells) {
			this.scope = scope.clone();
			this.values = values;
			this.lowValues = lowValues;
			this.tieCosts = tieCosts;
			this.sparseCells = sparseCells;
			this.strides = new int[scope.length];
			this.dimensions = new int[scope.length];
			int stride = 1;
			for(int index = scope.length - 1; index >= 0; index--) {
				strides[index] = stride;
				dimensions[index] = domains[scope[index]];
				stride = Math.multiplyExact(stride, dimensions[index]);
			}
		}

		private int axis(int variable) {
			for(int axis = 0; axis < scope.length; axis++)
				if(scope[axis] == variable)
					return axis;
			return -1;
		}

		private int coordinate(int axis, int originalValue) {
			return valueMaps == null ? originalValue : valueMaps[axis][originalValue];
		}

		private int[] originalValueClasses(int axis, int domain) {
			if(responseClasses == null)
				responseClasses = new int[scope.length][];
			if(responseClasses[axis] == null) {
				if(sparseCells == null)
					responseClasses[axis] = ExactFactorValueClasses.denseAxisClasses(
						values, lowValues, dimensions[axis], strides[axis]);
				else {
					// Conservative stored-coordinate classes avoid expanding implicit infinities.
					responseClasses[axis] = new int[dimensions[axis]];
					if(values.length != 0)
						for(int value = 0; value < dimensions[axis]; value++)
							responseClasses[axis][value] = value;
				}
			}
			if(valueMaps == null)
				return responseClasses[axis];
			int[] classes = new int[domain];
			for(int value = 0; value < domain; value++)
				classes[value] = responseClasses[axis][coordinate(axis, value)];
			return classes;
		}

		/** Lift stored finite tuples through the bucket's exact common refinement. */
		private int[] projectedFiniteCells(BucketProjection projection) {
			if(values.length == 0)
				return new int[0];
			if(sparseCells == null && finiteCount == values.length)
				return null;
			boolean identity = true;
			for(int axis = 0; identity && axis < scope.length; axis++) {
				int[] representatives = projection.representatives[scope[axis]];
				identity = representatives.length == dimensions[axis];
				for(int value = 0; identity && value < representatives.length; value++)
					identity = coordinate(axis, representatives[value]) == value;
			}
			if(identity)
				return selectiveFiniteCells();
			int cells = checkedCells(scope, projection.domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
			InverseAxis[] inverse = new InverseAxis[scope.length];
			int[] quotientStrides = new int[scope.length];
			int stride = 1;
			for(int axis = scope.length - 1; axis >= 0; axis--) {
				int[] representatives = projection.representatives[scope[axis]];
				inverse[axis] = new InverseAxis(this, axis, representatives);
				quotientStrides[axis] = stride;
				stride = Math.multiplyExact(stride, representatives.length);
			}
			long finite = 0L;
			for(int stored = 0; stored < values.length; stored++) {
				if(values[stored] == Double.POSITIVE_INFINITY)
					continue;
				int cell = sparseCells == null ? stored : sparseCells[stored];
				long multiplicity = 1L;
				for(int axis = 0; axis < scope.length; axis++)
					multiplicity *= inverse[axis].size(cell / strides[axis] % dimensions[axis]);
				finite += multiplicity;
				if(finite * 2 > cells)
					return null;
			}
			int[] result = new int[(int)finite];
			int output = 0;
			int[] first = new int[scope.length];
			int[] positions = new int[scope.length];
			for(int stored = 0; stored < values.length; stored++) {
				if(values[stored] == Double.POSITIVE_INFINITY)
					continue;
				int cell = sparseCells == null ? stored : sparseCells[stored];
				boolean empty = false;
				for(int axis = 0; axis < scope.length; axis++) {
					first[axis] = inverse[axis].first(cell / strides[axis] % dimensions[axis]);
					empty |= first[axis] < 0;
				}
				if(empty)
					continue;
				System.arraycopy(first, 0, positions, 0, first.length);
				while(true) {
					int encoded = 0;
					for(int axis = 0; axis < scope.length; axis++)
						encoded += positions[axis] * quotientStrides[axis];
					result[output++] = encoded;
					int axis = scope.length - 1;
					while(axis >= 0 && (positions[axis] = inverse[axis].next(positions[axis])) < 0) {
						positions[axis] = first[axis];
						axis--;
					}
					if(axis < 0)
						break;
				}
			}
			Arrays.sort(result);
			return result;
		}

		private int storageCell(int logicalCell) {
			return sparseCells == null ? logicalCell : Arrays.binarySearch(sparseCells, logicalCell);
		}

		private int summedCell(int[] global, int[] baseCells, int[] valueStrides, int index, int value) {
			return storageCell(baseCells == null ? cell(global) : baseCells[index] + value * valueStrides[index]);
		}

		/**
		 * An optional support index, not a replacement for the original factor.
		 * Majority-finite dense tables stay in preciseSum without an extra relation:
		 * materializing their finite IDs and join indexes adds memory with little
		 * selectivity. Every omitted constraint is still checked in arithmetic order.
		 */
		private int[] selectiveFiniteCells() {
			if(sparseCells != null)
				return sparseCells;
			if(finiteCount < 0) {
				finiteCount = 0;
				for(double value : values)
					if(value != Double.POSITIVE_INFINITY)
						finiteCount++;
			}
			int count = finiteCount;
			if((long)count * 2 > values.length)
				return null;
			int[] cells = new int[count];
			int output = 0;
			for(int cell = 0; cell < values.length; cell++)
				if(values[cell] != Double.POSITIVE_INFINITY)
					cells[output++] = cell;
			return cells;
		}

		private boolean contains(int variable) {
			for(int candidate : scope)
				if(candidate == variable)
					return true;
			return false;
		}

		private int stride(int variable) {
			for(int index = 0; index < scope.length; index++)
				if(scope[index] == variable)
					return strides[index];
			throw new IllegalStateException("EXACT_VE_BUCKET_VARIABLE_MISSING");
		}

		private int cell(int[] global) {
			int cell = 0;
			for(int index = 0; index < scope.length; index++)
				cell += coordinate(index, global[scope[index]]) * strides[index];
			return cell;
		}
	}
}
