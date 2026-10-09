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

	enum PartialTruth {
		UNKNOWN,
		ALL_ZERO,
		ALL_FORBIDDEN
	}

	interface PartialHardCostFunction extends CostFunction {
		/**
		 * Classifies every completion of this prefix. Assigned values precede unassigned
		 * {@code -1} entries. Values are reused and must not be retained or modified.
		 */
		PartialTruth partialTruth(int[] valuesInScopeOrder);
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

	static record ConditionalRegion(int selectorValue, int[][] allowedValuesByAxis) {
		ConditionalRegion {
			Objects.requireNonNull(allowedValuesByAxis, "allowedValuesByAxis");
			int[][] copy = new int[allowedValuesByAxis.length][];
			for(int axis = 0; axis < copy.length; axis++)
				copy[axis] = allowedValuesByAxis[axis] == null ? null
					: allowedValuesByAxis[axis].clone();
			allowedValuesByAxis = copy;
		}
		@Override public int[][] allowedValuesByAxis() {
			int[][] copy = new int[allowedValuesByAxis.length][];
			for(int axis = 0; axis < copy.length; axis++)
				copy[axis] = allowedValuesByAxis[axis] == null ? null
					: allowedValuesByAxis[axis].clone();
			return copy;
		}
		int[][] ownedAllowedValuesByAxis() { return allowedValuesByAxis; }
	}

	public static final class Factor {
		private final List<Variable> scope;
		private final double[] denseValues;
		private final HardTable hardValues;
		private final CostFunction evaluator;

		private Factor(List<Variable> scope, double[] denseValues, HardTable hardValues, CostFunction evaluator,
			boolean copyDenseValues) {
			this.scope = List.copyOf(Objects.requireNonNull(scope, "scope"));
			if(denseValues != null && copyDenseValues) {
				this.denseValues = PlannerResourceGuard.allocateDoubles(denseValues.length, "factor-copy");
				System.arraycopy(denseValues, 0, this.denseValues, 0, denseValues.length);
			}
			else
				this.denseValues = denseValues;
			this.hardValues = hardValues;
			this.evaluator = evaluator;
			if((denseValues != null ? 1 : 0) + (hardValues != null ? 1 : 0)
				+ (evaluator != null ? 1 : 0) != 1)
				throw new IllegalArgumentException("EXACT_VE_FACTOR_REPRESENTATION_INVALID");
		}

		public static Factor dense(List<Variable> scope, double... values) {
			return new Factor(scope, Objects.requireNonNull(values, "values"), null, null, true);
		}

		/** Internal ownership transfer; the caller must never mutate {@code values} again. */
		static Factor denseOwned(List<Variable> scope, double[] values) {
			return new Factor(scope, Objects.requireNonNull(values, "values"), null, null, false);
		}

		/** Internal exact hard-table ownership transfer. */
		static Factor hardOwned(List<Variable> scope, HardTable values) {
			return new Factor(scope, null, Objects.requireNonNull(values, "values"), null, false);
		}

		public static Factor lazy(List<Variable> scope, CostFunction evaluator) {
			return new Factor(scope, null, null, Objects.requireNonNull(evaluator, "evaluator"), false);
		}

		/**
		 * Exact hard relation represented only by its feasible row-major cells.
		 * Cells must be strictly increasing and unique; all omitted cells are forbidden.
		 */
		public static Factor finiteSupport(List<Variable> scope, int... finiteCells) {
			List<Variable> dimensions = List.copyOf(Objects.requireNonNull(scope, "scope"));
			return lazy(dimensions, new FiniteSupport(dimensions,
				Objects.requireNonNull(finiteCells, "finiteCells")));
		}

		static Factor conditionalSupport(List<Variable> scope, int selectorAxis,
			List<ConditionalRegion> regions) {
			int[] constrained = regions.stream().mapToInt(ConditionalRegion::selectorValue)
				.distinct().sorted().toArray();
			return conditionalSupport(scope, selectorAxis, constrained, regions);
		}

		static Factor conditionalSupport(List<Variable> scope, int selectorAxis,
			int[] constrainedSelectorValues, List<ConditionalRegion> regions) {
			List<Variable> dimensions = List.copyOf(Objects.requireNonNull(scope, "scope"));
			return lazy(dimensions, new ConditionalSupport(dimensions, selectorAxis,
				constrainedSelectorValues, regions));
		}

		/** Keep the complete hard relation deferred until unary support has reduced its shape. */
		static Factor functionalMap(Variable source, Variable target, int[] rowToColumn) {
			Objects.requireNonNull(rowToColumn, "rowToColumn");
			if(rowToColumn.length != source.domainSize())
				throw new IllegalArgumentException("EXACT_VE_FUNCTIONAL_MAP_SHAPE_INVALID");
			return lazy(List.of(source, target), new FunctionalMap(rowToColumn.clone(), target.domainSize()));
		}

		FunctionalMap functionalMapping() {
			FunctionalMap mapping = evaluator instanceof FunctionalMap direct ? direct
				: hardValues == null ? null : hardValues.functional;
			return mapping != null && scope.size() == 2
				&& scope.get(0).domainSize() == mapping.rows()
				&& scope.get(1).domainSize() == mapping.columns ? mapping : null;
		}

		/** Fix singleton boundary values without expanding a sparse hard relation. Null requests the numeric fallback. */
		Factor conditionSupport(int[] boundary) {
			FunctionalMap mapping = functionalMapping();
			ConditionalSupport conditional = conditionalSupport();
			if(!(evaluator instanceof FiniteSupport) && mapping == null && conditional == null)
				return null;
			if(boundary.length != scope.size())
				throw new IllegalArgumentException("EXACT_VE_CONDITION_BOUNDARY_SIZE_INVALID");
			List<Variable> free = new ArrayList<>();
			for(int axis = 0; axis < boundary.length; axis++) {
				if(boundary[axis] < -1 || boundary[axis] >= scope.get(axis).domainSize())
					throw new IllegalArgumentException("EXACT_VE_CONDITION_BOUNDARY_VALUE_INVALID");
				if(boundary[axis] < 0)
					free.add(scope.get(axis));
			}
			if(free.size() == scope.size())
				return this;
			if(conditional != null) {
				// A fixed selector needs a selector-free union representation; retain
				// the existing exact fallback until that representation is available.
				if(boundary[conditional.selectorAxis] >= 0)
					return null;
				int selector = 0;
				for(int axis = 0; axis < conditional.selectorAxis; axis++)
					if(boundary[axis] < 0) selector++;
				List<ConditionalRegion> regions = new ArrayList<>();
				for(ConditionalRegion region : conditional.regions) {
					boolean retained = true;
					for(int axis = 0; axis < boundary.length && retained; axis++)
						if(boundary[axis] >= 0)
							retained = Arrays.binarySearch(region.allowedValuesByAxis[axis], boundary[axis]) >= 0;
					if(!retained)
						continue;
					int[][] allowed = new int[free.size()][];
					for(int axis = 0, output = 0; axis < boundary.length; axis++)
						if(boundary[axis] < 0)
							allowed[output++] = region.allowedValuesByAxis[axis];
					regions.add(new ConditionalRegion(region.selectorValue, allowed));
				}
				// A constrained selector whose regions all died remains forbidden.
				// Inferring this set from the survivors would introduce wildcard holes.
				return conditionalSupport(free, selector, conditional.constrainedSelectorValues, regions);
			}
			if(mapping != null) {
				if(boundary[0] >= 0) {
					int target = mapping.target(boundary[0]);
					boolean allowed = target >= 0 && (boundary[1] < 0 || boundary[1] == target);
					return finiteSupport(free, allowed ? new int[] {boundary[1] < 0 ? target : 0} : new int[0]);
				}
				int count = 0;
				for(int row = 0; row < mapping.rows(); row++)
					if(mapping.target(row) == boundary[1]) count++;
				int[] rows = PlannerResourceGuard.allocateInts(count, "exact-functional-condition");
				int output = 0;
				for(int row = 0; row < mapping.rows(); row++)
					if(mapping.target(row) == boundary[1]) rows[output++] = row;
				return finiteSupport(free, rows);
			}
			FiniteSupport support = (FiniteSupport)evaluator;
			int[] freeStrides = new int[boundary.length];
			int stride = 1;
			for(int axis = boundary.length - 1; axis >= 0; axis--)
				if(boundary[axis] < 0) {
					freeStrides[axis] = stride;
					stride = Math.multiplyExact(stride, support.dimensions[axis]);
				}
			int count = 0;
			for(int cell : support.finiteCells)
				if(conditionedCell(cell, support.dimensions, boundary, freeStrides) >= 0) count++;
			int[] projected = PlannerResourceGuard.allocateInts(count, "exact-finite-support-condition");
			int output = 0;
			for(int cell : support.finiteCells) {
				int selected = conditionedCell(cell, support.dimensions, boundary, freeStrides);
				if(selected >= 0) projected[output++] = selected;
			}
			// Surviving tuples agree on every removed coordinate. Their projection is
			// injective and preserves row-major order, including the zero-axis case.
			return finiteSupport(free, projected);
		}

		private static int conditionedCell(int cell, int[] dimensions, int[] boundary, int[] freeStrides) {
			int projected = 0;
			for(int axis = boundary.length - 1; axis >= 0; axis--) {
				int value = cell % dimensions[axis];
				cell /= dimensions[axis];
				if(boundary[axis] >= 0 && boundary[axis] != value)
					return -1;
				projected += value * freeStrides[axis];
			}
			return projected;
		}

		/** Null requests the ordinary projection when the target projection is not injective. */
		Factor projectFunctionalMap(List<Variable> projectedScope, int[] rows, int[] columns) {
			FunctionalMap mapping = functionalMapping();
			if(mapping == null)
				return null;
			if(projectedScope.size() != 2 || projectedScope.get(0).domainSize() != rows.length
				|| projectedScope.get(1).domainSize() != columns.length)
				throw new IllegalArgumentException("EXACT_VE_FUNCTIONAL_MAP_SHAPE_INVALID");
			int[] inverse = PlannerResourceGuard.allocateInts(mapping.columns, "exact-functional-projection");
			Arrays.fill(inverse, -1);
			for(int column = 0; column < columns.length; column++) {
				if(inverse[columns[column]] >= 0)
					return null;
				inverse[columns[column]] = column;
			}
			int[] projected = PlannerResourceGuard.allocateInts(rows.length, "exact-functional-projection");
			for(int row = 0; row < rows.length; row++) {
				int target = mapping.target(rows[row]);
				projected[row] = target < 0 ? -1 : inverse[target];
			}
			FunctionalMap result = new FunctionalMap(projected, columns.length);
			return hardValues == null ? lazy(projectedScope, result)
				: hardOwned(projectedScope, new HardTable(result));
		}

		Factor projectConditionalSupport(List<Variable> projectedScope, int[][] sourceValues) {
			if(!(evaluator instanceof ConditionalSupport support))
				return null;
			if(projectedScope.size() != scope.size() || sourceValues.length != scope.size())
				throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_PROJECTION_SHAPE_INVALID");
			int[] selectorInverse = new int[scope.get(support.selectorAxis).domainSize()];
			Arrays.fill(selectorInverse, -1);
			for(int value = 0; value < sourceValues[support.selectorAxis].length; value++)
				selectorInverse[sourceValues[support.selectorAxis][value]] = value;
			List<ConditionalRegion> projected = new ArrayList<>();
			for(ConditionalRegion region : support.regions) {
				int selector = selectorInverse[region.selectorValue];
				if(selector < 0)
					continue;
				int[][] allowed = new int[scope.size()][];
				for(int axis = 0; axis < scope.size(); axis++) {
					if(axis == support.selectorAxis)
						continue;
					int[] values = region.allowedValuesByAxis[axis];
					int[] mapped = new int[values.length];
					int count = 0;
					for(int value : values) {
						int reduced = Arrays.binarySearch(sourceValues[axis], value);
						if(reduced >= 0)
							mapped[count++] = reduced;
					}
					allowed[axis] = Arrays.copyOf(mapped, count);
				}
				projected.add(new ConditionalRegion(selector, allowed));
			}
			int[] constrained = Arrays.stream(support.constrainedSelectorValues)
				.map(value -> selectorInverse[value]).filter(value -> value >= 0).toArray();
			return conditionalSupport(projectedScope, support.selectorAxis, constrained, projected);
		}

		List<Variable> scope() { return scope; }
		boolean isHardTable() { return hardValues != null; }
		HardTable hardTable() { return hardValues; }
		boolean isFiniteSupport() { return evaluator instanceof FiniteSupport; }
		boolean isConditionalSupport() { return evaluator instanceof ConditionalSupport; }
		ConditionalSupport conditionalSupport() {
			return evaluator instanceof ConditionalSupport support ? support : null;
		}
		long conditionalStoredValues() {
			if(!(evaluator instanceof ConditionalSupport support))
				throw new IllegalStateException("EXACT_VE_FACTOR_NOT_CONDITIONAL_SUPPORT");
			return support.storedValues();
		}
		Variable conditionalSelectorVariable() {
			return evaluator instanceof ConditionalSupport support
				? scope.get(support.selectorAxis) : null;
		}
		int[] finiteSupportCells() {
			if(!(evaluator instanceof FiniteSupport support))
				throw new IllegalStateException("EXACT_VE_FACTOR_NOT_FINITE_SUPPORT");
			return support.finiteCells.clone();
		}
		Factor rebindOwned(List<Variable> reboundScope) {
			if(denseValues != null)
				return denseOwned(reboundScope, denseValues);
			if(hardValues != null)
				return hardOwned(reboundScope, hardValues);
			if(evaluator instanceof FiniteSupport support)
				return finiteSupport(reboundScope, support.finiteCells);
			if(evaluator instanceof ConditionalSupport support) {
				int[] oldToNew = new int[scope.size()];
				Arrays.fill(oldToNew, -1);
				if(reboundScope.size() == scope.size())
					for(int axis = 0; axis < scope.size(); axis++)
						oldToNew[axis] = axis;
				else
					for(int oldAxis = 0; oldAxis < scope.size(); oldAxis++)
						oldToNew[oldAxis] = reboundScope.indexOf(scope.get(oldAxis));
				int selector = oldToNew[support.selectorAxis];
				if(selector < 0)
					throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_SELECTOR_REMOVED");
				List<ConditionalRegion> regions = new ArrayList<>();
				for(ConditionalRegion region : support.regions) {
					boolean retained = true;
					int[][] allowed = new int[reboundScope.size()][];
					for(int oldAxis = 0; oldAxis < scope.size(); oldAxis++) {
						if(oldAxis == support.selectorAxis)
							continue;
						int newAxis = oldToNew[oldAxis];
						if(newAxis < 0)
							retained &= Arrays.binarySearch(
								region.allowedValuesByAxis[oldAxis], 0) >= 0;
						else
							allowed[newAxis] = region.allowedValuesByAxis[oldAxis];
					}
					if(retained)
						regions.add(new ConditionalRegion(region.selectorValue, allowed));
				}
				return conditionalSupport(reboundScope, selector,
					support.constrainedSelectorValues, regions);
			}
			throw new IllegalStateException("EXACT_VE_FACTOR_NOT_FROZEN");
		}
		double denseCostAt(int cell) {
			if(evaluator instanceof ConditionalSupport support)
				return support.costAtCell(cell);
			if(denseValues == null && hardValues == null && !(evaluator instanceof FiniteSupport))
				throw new IllegalStateException("EXACT_VE_FACTOR_NOT_DENSE");
			return evaluator instanceof FiniteSupport support ? support.costAt(cell)
				: hardValues == null ? denseValues[cell] : hardValues.costAt(cell);
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
			return hardValues == null ? denseValues[cell] : hardValues.costAt(cell);
		}
		boolean supportsPartialTruth() {
			return evaluator instanceof PartialHardCostFunction;
		}
		PartialTruth partialTruth(int[] values) {
			if(values == null || values.length != scope.size())
				throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_SIZE_MISMATCH");
			if(!(evaluator instanceof PartialHardCostFunction partial))
				return PartialTruth.UNKNOWN;
			return Objects.requireNonNull(partial.partialTruth(values),
				"EXACT_VE_PARTIAL_TRUTH_NULL");
		}
	}

	/** Immutable wildcard-or-union-of-products hard relation. */
	static final class ConditionalSupport implements PartialHardCostFunction {
		private final int[] dimensions;
		private final int[] strides;
		private final int selectorAxis;
		private final List<ConditionalRegion> regions;
		private final boolean[] conditioned;
		private final int[] constrainedSelectorValues;

		private ConditionalSupport(List<Variable> scope, int selectorAxis,
			int[] constrainedSelectorValues, List<ConditionalRegion> regions) {
			if(selectorAxis < 0 || selectorAxis >= scope.size())
				throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_SELECTOR_INVALID");
			this.selectorAxis = selectorAxis;
			dimensions = scope.stream().mapToInt(Variable::domainSize).toArray();
			strides = new int[dimensions.length];
			for(int axis = dimensions.length - 1, stride = 1; axis >= 0; axis--) {
				strides[axis] = stride;
				stride = Math.multiplyExact(stride, dimensions[axis]);
			}
			conditioned = new boolean[dimensions[selectorAxis]];
			this.constrainedSelectorValues = Objects.requireNonNull(
				constrainedSelectorValues, "constrainedSelectorValues").clone();
			int previousSelector = -1;
			for(int selector : this.constrainedSelectorValues) {
				if(selector < 0 || selector >= conditioned.length || selector <= previousSelector)
					throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_SELECTORS_INVALID");
				conditioned[selector] = true;
				previousSelector = selector;
			}
			List<ConditionalRegion> checked = new ArrayList<>();
			for(ConditionalRegion supplied : Objects.requireNonNull(regions, "regions")) {
				ConditionalRegion region = Objects.requireNonNull(supplied, "region");
				if(region.selectorValue < 0 || region.selectorValue >= dimensions[selectorAxis]
					|| region.allowedValuesByAxis.length != dimensions.length)
					throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_REGION_SHAPE_INVALID");
				int[][] allowed = region.allowedValuesByAxis;
				if(allowed[selectorAxis] != null && allowed[selectorAxis].length != 0)
					throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_SELECTOR_VALUES_INVALID");
				for(int axis = 0; axis < dimensions.length; axis++) {
					if(axis == selectorAxis)
						continue;
					if(allowed[axis] == null)
						throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_AXIS_VALUES_MISSING");
					int previous = -1;
					for(int value : allowed[axis]) {
						if(value < 0 || value >= dimensions[axis] || value <= previous)
							throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_AXIS_VALUES_INVALID");
						previous = value;
					}
				}
				if(!conditioned[region.selectorValue])
					throw new IllegalArgumentException("EXACT_VE_CONDITIONAL_REGION_SELECTOR_UNDECLARED");
				checked.add(new ConditionalRegion(region.selectorValue, allowed));
			}
			this.regions = List.copyOf(checked);
		}

		@Override public double cost(int[] values) {
			validateValues(values);
			int selector = values[selectorAxis];
			if(!conditioned[selector])
				return 0d;
			for(ConditionalRegion region : regions)
				if(region.selectorValue == selector && matches(region, values, false))
					return 0d;
			return Double.POSITIVE_INFINITY;
		}

		@Override public PartialTruth partialTruth(int[] values) {
			if(values.length != dimensions.length)
				throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_SIZE_MISMATCH");
			int selector = values[selectorAxis];
			if(selector < -1 || selector >= dimensions[selectorAxis])
				throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_VALUE_INVALID");
			if(selector < 0)
				return PartialTruth.UNKNOWN;
			if(!conditioned[selector])
				return PartialTruth.ALL_ZERO;
			for(ConditionalRegion region : regions)
				if(region.selectorValue == selector && matches(region, values, true))
					return PartialTruth.UNKNOWN;
			return PartialTruth.ALL_FORBIDDEN;
		}

		private boolean matches(ConditionalRegion region, int[] values, boolean partial) {
			for(int axis = 0; axis < values.length; axis++) {
				if(axis == selectorAxis || partial && values[axis] < 0)
					continue;
				if(values[axis] < 0 || values[axis] >= dimensions[axis])
					throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_VALUE_INVALID");
				if(Arrays.binarySearch(region.allowedValuesByAxis[axis], values[axis]) < 0)
					return false;
			}
			return true;
		}

		private void validateValues(int[] values) {
			if(values.length != dimensions.length)
				throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_SIZE_MISMATCH");
			for(int axis = 0; axis < values.length; axis++)
				if(values[axis] < 0 || values[axis] >= dimensions[axis])
					throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_VALUE_INVALID");
		}

		private long storedValues() {
			long result = 0L;
			for(ConditionalRegion region : regions)
				for(int axis = 0; axis < dimensions.length; axis++)
					if(axis != selectorAxis)
						result = saturatedAdd(result, region.allowedValuesByAxis[axis].length);
			return result;
		}

		private double costAtCell(int cell) {
			int selector = cell / strides[selectorAxis] % dimensions[selectorAxis];
			if(!conditioned[selector])
				return 0d;
			for(ConditionalRegion region : regions) {
				if(region.selectorValue != selector)
					continue;
				boolean match = true;
				for(int axis = 0; axis < dimensions.length; axis++)
					if(axis != selectorAxis && Arrays.binarySearch(region.allowedValuesByAxis[axis],
						cell / strides[axis] % dimensions[axis]) < 0) {
						match = false;
						break;
					}
				if(match)
					return 0d;
			}
			return Double.POSITIVE_INFINITY;
		}

		private boolean hasCompletion(int axis, int value) {
			if(axis < 0 || axis >= dimensions.length || value < 0 || value >= dimensions[axis])
				return false;
			if(axis == selectorAxis) {
				if(!conditioned[value])
					return true;
				for(ConditionalRegion region : regions)
					if(region.selectorValue == value && nonempty(region))
						return true;
				return false;
			}
			for(boolean selected : conditioned)
				if(!selected)
					return true;
			for(ConditionalRegion region : regions)
				if(nonempty(region)
					&& Arrays.binarySearch(region.allowedValuesByAxis[axis], value) >= 0)
					return true;
			return false;
		}

		private boolean feasible() {
			for(int selector = 0; selector < conditioned.length; selector++)
				if(hasCompletion(selectorAxis, selector))
					return true;
			return false;
		}

		private boolean nonempty(ConditionalRegion region) {
			for(int axis = 0; axis < dimensions.length; axis++)
				if(axis != selectorAxis && region.allowedValuesByAxis[axis].length == 0)
					return false;
			return true;
		}
	}

	/** Immutable sparse +0/+INF input relation. */
	private static final class FiniteSupport implements CostFunction {
		private final int[] finiteCells;
		private final int[] dimensions;
		private final int logicalCells;

		private FiniteSupport(List<Variable> scope, int[] cells) {
			dimensions = scope.stream().mapToInt(Variable::domainSize).toArray();
			int logical = 1;
			for(int dimension : dimensions)
				logical = Math.multiplyExact(logical, dimension);
			logicalCells = logical;
			finiteCells = cells.clone();
			int previous = -1;
			for(int cell : finiteCells) {
				if(cell < 0 || cell >= logicalCells)
					throw new IllegalArgumentException("EXACT_VE_FINITE_SUPPORT_CELL_INVALID");
				if(cell <= previous)
					throw new IllegalArgumentException("EXACT_VE_FINITE_SUPPORT_CELLS_NOT_STRICTLY_SORTED");
				previous = cell;
			}
		}

		@Override public double cost(int[] values) {
			if(values.length != dimensions.length)
				throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_SIZE_MISMATCH");
			int cell = 0;
			for(int axis = 0; axis < values.length; axis++) {
				if(values[axis] < 0 || values[axis] >= dimensions[axis])
					throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_VALUE_INVALID");
				cell = cell * dimensions[axis] + values[axis];
			}
			return costAt(cell);
		}

		private double costAt(int cell) {
			return Arrays.binarySearch(finiteCells, cell) >= 0
				? 0d : Double.POSITIVE_INFINITY;
		}
	}

	/** A closed, immutable +0.0/+INF relation. Its array is privately owned, never exposed. */
	static final class FunctionalMap implements CostFunction {
		private final int[] rowToColumn;
		private final int columns;

		private FunctionalMap(int[] ownedRows, int columns) {
			for(int target : ownedRows)
				if(target < -1 || target >= columns)
					throw new IllegalArgumentException("EXACT_VE_FUNCTIONAL_MAP_TARGET_INVALID");
			rowToColumn = ownedRows;
			this.columns = columns;
		}

		int rows() { return rowToColumn.length; }
		int columns() { return columns; }
		int target(int row) { return rowToColumn[row]; }

		/** Ordered logical cells; used only after the complete matrix shape has been validated. */
		private int finiteCellAfter(int previous) {
			for(int row = previous < 0 ? 0 : previous / columns + 1; row < rows(); row++)
				if(rowToColumn[row] >= 0)
					return row * columns + rowToColumn[row];
			return -1;
		}

		private int finiteCount() {
			int count = 0;
			for(int target : rowToColumn)
				if(target >= 0)
					count++;
			return count;
		}

		@Override
		public double cost(int[] values) {
			if(values[0] < 0 || values[0] >= rows() || values[1] < 0 || values[1] >= columns)
				throw new IllegalArgumentException("EXACT_VE_FACTOR_ASSIGNMENT_VALUE_INVALID");
			return rowToColumn[values[0]] == values[1] ? 0d : Double.POSITIVE_INFINITY;
		}
	}

	/** Exact +0.0/+INF table with either packed forbidden bits or a functional backing. */
	static final class HardTable {
		private final int cells;
		private final long[] forbidden;
		private final FunctionalMap functional;
		private int forbiddenCount;

		private HardTable(int cells, long[] forbidden) {
			this.cells = cells;
			this.forbidden = forbidden;
			functional = null;
		}

		private HardTable(FunctionalMap mapping) {
			cells = Math.multiplyExact(mapping.rows(), mapping.columns);
			forbidden = null;
			functional = mapping;
			int finite = 0;
			for(int target : mapping.rowToColumn)
				if(target >= 0)
					finite++;
			forbiddenCount = cells - finite;
		}

		static HardTable allocate(int cells) {
			return new HardTable(cells, PlannerResourceGuard.allocateLongs((int)(((long)cells + 63L) >>> 6),
				"exact-hard-table"));
		}

		double costAt(int cell) {
			if(functional != null)
				return functional.target(cell / functional.columns) == cell % functional.columns
					? 0d : Double.POSITIVE_INFINITY;
			return (forbidden.length == 0 || (forbidden[cell >>> 6] & 1L << (cell & 63)) == 0L)
				? 0d : Double.POSITIVE_INFINITY;
		}

		void forbid(int cell) {
			if(functional != null)
				throw new IllegalStateException("EXACT_VE_FUNCTIONAL_MAP_IMMUTABLE");
			long bit = 1L << (cell & 63);
			int word = cell >>> 6;
			if((forbidden[word] & bit) == 0L) {
				forbidden[word] |= bit;
				forbiddenCount++;
			}
		}
		void forbidRange(int start, int end) {
			if(functional != null)
				throw new IllegalStateException("EXACT_VE_FUNCTIONAL_MAP_IMMUTABLE");
			while(start < end && (start & 63) != 0)
				forbid(start++);
			int fullEnd = end & ~63;
			if(start < fullEnd) {
				Arrays.fill(forbidden,start >>> 6,fullEnd >>> 6,-1L);
				forbiddenCount += fullEnd - start;
				start = fullEnd;
			}
			while(start < end)
				forbid(start++);
		}
		int cells() { return cells; }
		int finiteCount() { return cells - forbiddenCount; }
		boolean packed() { return functional == null; }
		boolean sparseExceptionsForbidden() { return forbiddenCount <= cells - forbiddenCount; }
		int sparseExceptionCount() { return Math.min(forbiddenCount,cells - forbiddenCount); }
		int packedWordCount() { return (int)(((long)cells + 63L) >>> 6); }
		long sparseExceptionWord(int word, boolean exceptionsForbidden) {
			if(functional != null || word < 0 || word >= packedWordCount())
				throw new IllegalArgumentException("EXACT_VE_HARD_EXCEPTION_WORD_INVALID");
			long stored = forbidden.length == 0 ? 0L : forbidden[word];
			long exceptions = exceptionsForbidden ? stored : ~stored;
			if(word == packedWordCount() - 1 && (cells & 63) != 0)
				exceptions &= (1L << (cells & 63)) - 1L;
			return exceptions;
		}
		HardTable compactAllFeasible() {
			return forbiddenCount == 0 ? new HardTable(cells,new long[0]) : this;
		}

		int[] axisClasses(int domain, int stride) {
			int[] classes = PlannerResourceGuard.allocateInts(domain,"exact-hard-classes");
			long blockSize = (long)domain * stride;
			if(domain <= 0 || stride <= 0 || blockSize > Integer.MAX_VALUE || cells % blockSize != 0)
				throw new IllegalArgumentException("Dense factor shape does not match axis domain and stride");
			// A uniform hard table gives every axis value the same response profile.
			if(forbiddenCount == 0 || forbiddenCount == cells)
				return classes;
			if(functional != null) {
				if(domain == functional.rows() && stride == functional.columns) {
					if(functional.columns >= domain) {
						Map<Integer,Integer> categories = new HashMap<>();
						for(int row = 0; row < domain; row++)
							classes[row] = categories.computeIfAbsent(functional.target(row),
								ignored -> categories.size());
						return classes;
					}
					int[] categories = PlannerResourceGuard.allocateInts(functional.columns + 1,
						"exact-functional-classes");
					int next = 0;
					for(int row = 0; row < domain; row++) {
						int category = functional.target(row) + 1;
						if(categories[category] == 0)
							categories[category] = ++next;
						classes[row] = categories[category] - 1;
					}
					return classes;
				}
				if(domain == functional.columns && stride == 1) {
					for(int target : functional.rowToColumn)
						if(target >= 0)
							classes[target] = 1;
					int next = 0, emptyClass = -1;
					for(int column = 0; column < domain; column++) {
						if(classes[column] != 0)
							classes[column] = next++;
						else {
							if(emptyClass < 0)
								emptyClass = next++;
							classes[column] = emptyClass;
						}
					}
					return classes;
				}
			}
			long required = Math.max(1L,(3L * domain + 1L) / 2L);
			if(required > 1L << 30)
				return identityClasses(classes);
			int capacity = 1;
			while(capacity < required)
				capacity <<= 1;
			PlannerResourceGuard.HeapSnapshot heap = PlannerResourceGuard.currentSnapshot();
			if(16L + (long)Integer.BYTES * capacity > heap.availableBytes() / 2L)
				return identityClasses(classes);
			int[] representatives = PlannerResourceGuard.allocateInts(capacity,"exact-hard-classes");
			int[] profileHashes = PlannerResourceGuard.allocateInts(domain,"exact-hard-profile-hashes");
			int mask = capacity - 1;
			int classCount = 0;
			for(int value = 0; value < domain; value++) {
				int hash = profileHashes[value] = profileHash(domain,stride,value);
				int slot = (hash ^ hash >>> 16) & mask;
				int matching = -1;
				while(representatives[slot] != 0) {
					int representative = representatives[slot] - 1;
					if(profileHashes[representative] == hash
						&& sameProfile(domain,stride,value,representative)) {
						matching = classes[representative];
						break;
					}
					slot = (slot + 1) & mask;
				}
				if(matching < 0) {
					matching = classCount++;
					representatives[slot] = value + 1;
				}
				classes[value] = matching;
			}
			return classes;
		}

		private int profileHash(int domain, int stride, int value) {
			int hash = 1;
			int blockSize = domain * stride;
			for(int base = 0; base < cells; base += blockSize)
				for(int inner = 0; inner < stride; inner++)
					hash = 31 * hash + Long.hashCode(Double.doubleToRawLongBits(
						costAt(base + value * stride + inner)));
			return hash;
		}

		private boolean sameProfile(int domain, int stride, int left, int right) {
			int blockSize = domain * stride;
			for(int base = 0; base < cells; base += blockSize)
				for(int inner = 0; inner < stride; inner++)
					if(costAt(base + left * stride + inner)
						!= costAt(base + right * stride + inner))
						return false;
			return true;
		}

		private static int[] identityClasses(int[] classes) {
			for(int value = 0; value < classes.length; value++)
				classes[value] = value;
			return classes;
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
		private final List<Factor> factors;

		private FrozenInputs(InputDefinition definition, List<Factor> factors) {
			// Both inputs are solve-local immutable structures. Retaining their arrays avoids
			// copying every dense table once more between materialization and reduction.
			domains = definition.domains;
			scopes = definition.scopes;
			this.factors = new ArrayList<>(factors);
		}

		int domainSize(int variable) { return domains[variable]; }
		int[] scope(int factor) { return scopes.get(factor); }
		double costAt(int factor, int cell) { return factors.get(factor).denseCostAt(cell); }
		double[] values(int factor) {
			Factor frozen = factors.get(factor);
			if(frozen.hardValues != null)
				throw new IllegalStateException("EXACT_VE_HARD_TABLE_NOT_DENSE");
			return frozen.denseValues;
		}
		Factor factor(int factor) { return factors.get(factor); }
		/** After support/quotient analysis, transfer each table to its reduced replacement. */
		Factor takeFactor(int factor) { return factors.set(factor, null); }
		int factorCount() { return scopes.size(); }
	}

	/** Persistent exact min-sum message over a boundary of the original variables. */
	static final class BoundaryMessage {
		private final List<Variable> variables;
		private final int[] domains;
		private final List<Variable> scope;
		private final int[] scopeIndices;
		private final int[] strides;
		private final int[] dimensions;
		private final int[][] storageClasses;
		private final long logicalCells;
		private final double[] values;
		private final HardTable hardValues;
		private final ConditionalSupport conditionalSupport;
		private final double[] lowValues;
		private final double[] lowerValues;
		private final int[] sparseCells;
		private final PreciseCost cachedMinimum;
		private final double cachedLowerBound;
		private final List<BoundaryMessage> children;
		private final int[] unionScope;
		private final int[] unionChoices;
		private final long retainedCells;
		private final long assignments;
		private final ExactFiniteSupportJoin.SupportRelation hardSupport;
		private int[][] valueClasses;

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments) {
			this(variables, domains, scope, scopeIndices, values, lowValues, lowerValues,
				children, unionScope, unionChoices, retainedCells, assignments, null, null, 0d);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, HardTable hardValues,
			List<BoundaryMessage> children, int[] unionScope, int[] unionChoices,
			long retainedCells, long assignments) {
			this(variables, domains, scope, scopeIndices, null, null, null, children,
				unionScope, unionChoices, retainedCells, assignments, hardValues, null, 0d);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments,
			HardTable hardValues, PreciseCost knownMinimum, double knownLowerBound) {
			this(variables, domains, scope, scopeIndices, values, lowValues, lowerValues,
				children, unionScope, unionChoices, retainedCells, assignments, hardValues,
				knownMinimum, knownLowerBound, null);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments,
			PreciseCost knownMinimum, double knownLowerBound, int[][] storageClasses) {
			this(variables, domains, scope, scopeIndices, values, lowValues, lowerValues,
				children, unionScope, unionChoices, retainedCells, assignments, null,
				knownMinimum, knownLowerBound, storageClasses);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments,
			HardTable hardValues, PreciseCost knownMinimum, double knownLowerBound,
			int[][] storageClasses) {
			this(variables, domains, scope, scopeIndices, values, lowValues, lowerValues,
				children, unionScope, unionChoices, retainedCells, assignments, hardValues,
				knownMinimum, knownLowerBound, storageClasses, null, null);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments,
			HardTable hardValues, PreciseCost knownMinimum, double knownLowerBound,
			int[][] storageClasses, int[] sparseCells) {
			this(variables, domains, scope, scopeIndices, values, lowValues, lowerValues,
				children, unionScope, unionChoices, retainedCells, assignments, hardValues,
				knownMinimum, knownLowerBound, storageClasses, sparseCells, null);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, ConditionalSupport conditionalSupport,
			long retainedCells, long assignments) {
			this(variables, domains, scope, scopeIndices, null, null, null, List.of(),
				null, null, retainedCells, assignments, null, null, 0d, null, null,
				conditionalSupport);
		}

		private BoundaryMessage(List<Variable> variables, int[] domains,
			List<Variable> scope, int[] scopeIndices, double[] values, double[] lowValues,
			double[] lowerValues, List<BoundaryMessage> children,
			int[] unionScope, int[] unionChoices, long retainedCells, long assignments,
			HardTable hardValues, PreciseCost knownMinimum, double knownLowerBound,
			int[][] storageClasses, int[] sparseCells, ConditionalSupport conditionalSupport) {
			this.variables = variables;
			this.domains = domains;
			this.scope = List.copyOf(scope);
			this.scopeIndices = scopeIndices.clone();
			this.values = values;
			this.hardValues = hardValues;
			this.conditionalSupport = conditionalSupport;
			this.storageClasses = storageClasses;
			this.lowValues = lowValues;
			this.lowerValues = lowerValues;
			this.sparseCells = sparseCells == null ? null : sparseCells.clone();
			this.children = List.copyOf(children);
			this.unionScope = unionScope == null ? null : unionScope.clone();
			this.unionChoices = unionChoices;
			this.retainedCells = retainedCells;
			this.assignments = assignments;
			this.hardSupport = conditionalSupport != null
				? new ExactFiniteSupportJoin.ConditionalProductRelation(scopeIndices,
					conditionalSupport.selectorAxis, conditionalSupport.dimensions,
					conditionalSupport.constrainedSelectorValues, conditionalSupport.regions)
				: this.sparseCells != null
				? new ExactFiniteSupportJoin.Relation(scopeIndices, this.sparseCells)
				: hardValues == null
				? storageClasses == null
					? sparseHardSupport(scopeIndices,values,lowValues,lowerValues) : null
				: sparseHardSupport(scopeIndices,hardValues);
			strides = new int[scopeIndices.length];
			dimensions = new int[scopeIndices.length];
			int stride = 1;
			long logical = 1;
			for(int index = scopeIndices.length - 1; index >= 0; index--) {
				int domain = domains[scopeIndices[index]];
				dimensions[index] = storageClasses == null || storageClasses[index] == null ? domain
					: Arrays.stream(storageClasses[index]).max().orElseThrow() + 1;
				strides[index] = stride;
				stride = Math.multiplyExact(stride, dimensions[index]);
				logical = Math.multiplyExact(logical, domain);
			}
			logicalCells = logical;
			int storedCells = conditionalSupport != null
				? Math.toIntExact(conditionalSupport.storedValues())
				: hardValues == null ? values.length : hardValues.cells();
			if(this.sparseCells != null && storageClasses != null)
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_SPARSE_CLASSES_UNSUPPORTED");
			if(conditionalSupport == null && this.sparseCells == null && stride != storedCells)
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_STORAGE_SIZE_MISMATCH");
			if(this.sparseCells != null && this.sparseCells.length != storedCells)
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_SPARSE_SIZE_MISMATCH");
			if(knownMinimum == null && conditionalSupport != null) {
				boolean finite = conditionalSupport.feasible();
				cachedMinimum = finite ? PreciseCost.ZERO : PreciseCost.POSITIVE_INFINITY;
				cachedLowerBound = finite ? 0d : Double.POSITIVE_INFINITY;
			}
			else if(knownMinimum == null && hardValues != null) {
				boolean finite = hardValues.finiteCount() > 0;
				cachedMinimum = finite ? PreciseCost.ZERO : PreciseCost.POSITIVE_INFINITY;
				cachedLowerBound = finite ? 0d : Double.POSITIVE_INFINITY;
			}
			else if(knownMinimum == null) {
				double minimumHigh = Double.POSITIVE_INFINITY;
				double minimumLow = 0d;
				double lower = Double.POSITIVE_INFINITY;
				for(int cell = 0; cell < storedCells; cell++) {
					int logicalCell = storedLogicalCell(cell);
					double candidateHigh = highAt(logicalCell);
					double candidateLow = lowAt(logicalCell);
					if(compareBoundaryCost(candidateHigh,candidateLow,minimumHigh,minimumLow) < 0) {
						minimumHigh = candidateHigh;
						minimumLow = candidateLow;
					}
					lower = Math.min(lower, lowerAt(logicalCell));
				}
				cachedMinimum = new PreciseCost(minimumHigh,minimumLow,0L);
				cachedLowerBound = lower;
			}
			else {
				cachedMinimum = knownMinimum;
				cachedLowerBound = knownLowerBound;
			}
		}

		List<Variable> scope() { return scope; }
		long cells() { return logicalCells; }
		long retainedCells() { return retainedCells; }
		long assignments() { return assignments; }
		ExactFiniteSupportJoin.Relation hardSupport() {
			return hardSupport instanceof ExactFiniteSupportJoin.Relation relation ? relation : null;
		}
		ExactFiniteSupportJoin.SupportRelation supportRelation() { return hardSupport; }

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
			if(conditionalSupport != null)
				return conditionalSupport.hasCompletion(position, value)
					? 0d : Double.POSITIVE_INFINITY;
			double minimumHigh = Double.POSITIVE_INFINITY;
			double minimumLow = 0d;
			int storedValue = storedValue(position, value);
			int storedCells = hardValues == null ? values.length : hardValues.cells();
			for(int cell = 0; cell < storedCells; cell++)
				if((storedLogicalCell(cell) / strides[position]) % dimensions[position] == storedValue) {
					int logicalCell = storedLogicalCell(cell);
					double candidateHigh = highAt(logicalCell);
					double candidateLow = lowAt(logicalCell);
					if(compareBoundaryCost(candidateHigh,candidateLow,minimumHigh,minimumLow) < 0) {
						minimumHigh = candidateHigh;
						minimumLow = candidateLow;
					}
				}
			return roundBoundaryCost(minimumHigh,minimumLow);
		}

		/** Computes every value marginal in one table scan; the caller owns the result. */
		double[] minMarginals(Variable variable) {
			int position = marginalPosition(variable);
			if(conditionalSupport != null) {
				double[] result = new double[variable.domainSize()];
				for(int value = 0; value < result.length; value++)
					result[value] = conditionalSupport.hasCompletion(position, value)
						? 0d : Double.POSITIVE_INFINITY;
				return result;
			}
			double[] minimumHigh = PlannerResourceGuard.allocateDoubles(
				dimensions[position], "exact-numeric");
			double[] minimumLow = lowValues == null ? null : new double[dimensions[position]];
			Arrays.fill(minimumHigh, Double.POSITIVE_INFINITY);
			int storedCells = hardValues == null ? values.length : hardValues.cells();
			for(int cell = 0; cell < storedCells; cell++) {
				int logicalCell = storedLogicalCell(cell);
				int value = (logicalCell / strides[position]) % dimensions[position];
				double candidateHigh = highAt(logicalCell);
				double candidateLow = lowAt(logicalCell);
				if(compareBoundaryCost(candidateHigh,candidateLow,
					minimumHigh[value],minimumLow == null ? 0d : minimumLow[value]) < 0) {
					minimumHigh[value] = candidateHigh;
					if(minimumLow != null)
						minimumLow[value] = candidateLow;
				}
			}
			double[] result = PlannerResourceGuard.allocateDoubles(variable.domainSize(), "exact-numeric");
			for(int value = 0; value < result.length; value++)
				result[value] = roundBoundaryCost(minimumHigh[storedValue(position,value)],
					minimumLow == null ? 0d : minimumLow[storedValue(position,value)]);
			return result;
		}

		double[] lowerMinMarginals(Variable variable) {
			int position = marginalPosition(variable);
			if(conditionalSupport != null)
				return minMarginals(variable);
			double[] minima = new double[dimensions[position]];
			Arrays.fill(minima, Double.POSITIVE_INFINITY);
			int storedCells = hardValues == null ? values.length : hardValues.cells();
			for(int cell = 0; cell < storedCells; cell++) {
				int logicalCell = storedLogicalCell(cell);
				int value = (logicalCell / strides[position]) % dimensions[position];
				minima[value] = Math.min(minima[value], lowerAt(logicalCell));
			}
			if(dimensions[position] == variable.domainSize())
				return minima;
			double[] result = new double[variable.domainSize()];
			for(int value = 0; value < result.length; value++)
				result[value] = minima[storedValue(position, value)];
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

		/** Keep the incumbent on a local DP tie; only a strict subtree improvement needs backtracing. */
		boolean improvesBacktrace(int[] assignment, List<Variable> allVariables) {
			if(assignment == null || assignment.length != variables.size())
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_ASSIGNMENT_SIZE_INVALID");
			if(!variables.equals(allVariables))
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VARIABLE_UNIVERSE_MISMATCH");
			return !children.isEmpty() && value(assignment).compareTo(childCost(assignment)) < 0;
		}

		double valueForAssignment(int[] assignment, List<Variable> allVariables) {
			if(assignment == null || assignment.length != variables.size())
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_ASSIGNMENT_SIZE_INVALID");
			if(!variables.equals(allVariables))
				throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VARIABLE_UNIVERSE_MISMATCH");
			return value(assignment).rounded();
		}

		private void decodeInto(int[] assignment) {
			int cell = boundaryCell(assignment);
			if(valueAt(cell).high == Double.POSITIVE_INFINITY)
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
			int[] actualBoundary = storageClasses == null ? null : new int[scopeIndices.length];
			if(actualBoundary != null)
				for(int axis = 0; axis < scopeIndices.length; axis++)
					actualBoundary[axis] = assignment[scopeIndices[axis]];
			decode(unionCell, unionScope, domains, new int[unionScope.length], assignment);
			// Equal numeric rows may have distinct child witnesses. Restore the
			// caller's logical boundary before any child follows its own backpointers.
			if(actualBoundary != null)
				for(int axis = 0; axis < scopeIndices.length; axis++)
					assignment[scopeIndices[axis]] = actualBoundary[axis];
			for(BoundaryMessage child : children)
				child.decodeInto(assignment);
		}

		private int boundaryCell(int[] assignment) {
			int cell = 0;
			for(int index = 0; index < scopeIndices.length; index++) {
				int value = assignment[scopeIndices[index]];
				if(value < 0 || value >= domains[scopeIndices[index]])
					throw new IllegalArgumentException("INCREMENTAL_MESSAGE_VALUE_INVALID");
				cell += storedValue(index, value) * strides[index];
			}
			return cell;
		}

		private int boundaryCellUnchecked(int[] assignment) {
			int cell = 0;
			for(int index = 0; index < scopeIndices.length; index++)
				cell += storedValue(index, assignment[scopeIndices[index]]) * strides[index];
			return cell;
		}

		private int storedValue(int axis, int original) {
			return storageClasses == null || storageClasses[axis] == null ? original : storageClasses[axis][original];
		}

		private PreciseCost value(int[] assignment) {
			return valueAt(boundaryCell(assignment));
		}

		private PreciseCost childCost(int[] assignment) {
			PreciseCost total = PreciseCost.ZERO;
			for(BoundaryMessage child : children)
				total = total.plus(child.value(assignment));
			return total;
		}

		private PreciseCost valueAt(int cell) {
			return new PreciseCost(highAt(cell),lowAt(cell),0L);
		}

		private double highAt(int cell) {
			if(conditionalSupport != null)
				return conditionalSupport.costAtCell(cell);
			if(hardValues != null)
				return hardValues.costAt(cell);
			int stored = sparseCells == null ? cell : Arrays.binarySearch(sparseCells, cell);
			return stored < 0 ? Double.POSITIVE_INFINITY : values[stored];
		}

		private double lowAt(int cell) {
			if(lowValues == null)
				return 0d;
			int stored = sparseCells == null ? cell : Arrays.binarySearch(sparseCells, cell);
			return stored < 0 ? 0d : lowValues[stored];
		}

		private double lowerAt(int cell) {
			if(conditionalSupport != null)
				return conditionalSupport.costAtCell(cell);
			if(hardValues != null)
				return hardValues.costAt(cell);
			int stored = sparseCells == null ? cell : Arrays.binarySearch(sparseCells, cell);
			return stored < 0 ? Double.POSITIVE_INFINITY : lowerValues[stored];
		}

		private int storedLogicalCell(int stored) {
			return sparseCells == null ? stored : sparseCells[stored];
		}

		private int[] valueClasses(int axis) {
			if(conditionalSupport != null)
				return java.util.stream.IntStream.range(0,
					domains[scopeIndices[axis]]).toArray();
			if(valueClasses == null)
				valueClasses = new int[scopeIndices.length][];
			if(valueClasses[axis] == null) {
				int domain = dimensions[axis];
				int[] classes = sparseCells != null ? java.util.stream.IntStream.range(0, domain).toArray()
					: hardValues == null
					? ExactFactorValueClasses.denseAxisClasses(values,lowValues,domain,strides[axis])
					: hardValues.axisClasses(domain,strides[axis]);
				if(hardValues == null && lowerValues != values)
					classes = ExactFactorValueClasses.refine(classes,
						ExactFactorValueClasses.denseAxisClasses(lowerValues, null, domain, strides[axis]));
				if(domain != domains[scopeIndices[axis]]) {
					int[] original = new int[domains[scopeIndices[axis]]];
					for(int value = 0; value < original.length; value++)
						original[value] = classes[storedValue(axis, value)];
					classes = original;
				}
				valueClasses[axis] = classes;
			}
			return valueClasses[axis];
		}

		private double lowerValue(int[] assignment) {
			return lowerAt(boundaryCell(assignment));
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

	/**
	 * Compiles a certified-dyadic solve whose exact value-profile projection and
	 * finite support determine the stored message sizes. Input tables retain the
	 * ordinary strict limits; only intermediate dense estimates are deferred.
	 */
	static OrderCompilation compileDyadicWithFastOrder(List<Variable> variables,
		List<Factor> factors, Limits limits, boolean fastOrder, long maximumAssignments) {
		if(maximumAssignments <= 0)
			throw new IllegalArgumentException("EXACT_VE_FAST_ORDER_WORK_INVALID|value="
				+ maximumAssignments);
		InputDefinition input = validateInputs(variables, factors, limits);
		Plan selected;
		boolean accepted = false;
		boolean fallback = false;
		long fastAssignments = 0L;
		if(fastOrder) {
			Plan fast = eliminationPlan(input.variables, input.domains, input.scopes,
				PlanOrdering.MIN_SEPARATOR_CELLS);
			PlanMetrics metrics = planMetrics(fast, input.domains);
			fastAssignments = metrics.eliminationAssignments;
			accepted = metrics.eliminationAssignments != Long.MAX_VALUE
				&& metrics.eliminationAssignments <= maximumAssignments
				&& planFitsLimits(input, metrics, limits);
			fallback = !accepted;
			selected = accepted ? fast : minimumMaterializationPlan(
				input.variables, input.domains, input.scopes,
				new ScoredPlan(fast, metrics, PlanOrdering.MIN_SEPARATOR_CELLS.ordinal()));
		}
		else
			selected = minimumMaterializationPlan(input.variables, input.domains, input.scopes);
		Prepared prepared = prepareDeferredDyadic(input, limits, selected);
		return new OrderCompilation(new CompiledProblem(prepared, factors), fastOrder,
			accepted, fallback, fastAssignments);
	}

	/**
	 * Selects the ordinary exact portfolio order subject to a caller's per-step
	 * elimination-work limit. A lower-memory order that exceeds the work limit must
	 * not hide another exact portfolio order that fits both declared budgets.
	 */
	static CompiledProblem compileWithinMaximumEliminationAssignments(
		List<Variable> variables, List<Factor> factors, Limits limits,
		long maximumEliminationAssignments) {
		if(maximumEliminationAssignments <= 0)
			throw new IllegalArgumentException("EXACT_VE_ASSIGNMENT_LIMIT_INVALID|value="
				+ maximumEliminationAssignments);
		InputDefinition input = validateInputs(variables,factors,limits);
		Plan selected = minimumMaterializationPlan(input.variables,input.domains,input.scopes,
			null,limits,maximumEliminationAssignments);
		return new CompiledProblem(prepare(input,limits,selected),factors);
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

	/** Test-only fixed-order preparation for exercising deferred storage boundaries. */
	static CompiledProblem compileDyadicPreferredForTest(List<Variable> variables,
		List<Factor> factors, Limits limits, List<String> preferredEliminationOrder) {
		InputDefinition input = validateInputs(variables, factors, limits);
		return new CompiledProblem(prepareDeferredDyadic(input, limits,
			preferredPlan(input, preferredEliminationOrder)), factors);
	}

	static Result solve(CompiledProblem compiled) {
		Objects.requireNonNull(compiled, "compiled");
		if(compiled.prepared.deferredDyadic)
			throw new IllegalArgumentException("EXACT_VE_DEFERRED_DYADIC_CERTIFICATE_REQUIRED");
		return solve(compiled.prepared, compiled.factors, null);
	}

	/** Isolated opt-in integer arithmetic; legacy/local callers never enter this path. */
	static Result solveDyadic(CompiledProblem compiled, ExactDyadicCosts.Certificate certificate) {
		Objects.requireNonNull(compiled, "compiled");
		Objects.requireNonNull(certificate, "certificate");
		if(!certificate.supported())
			throw new IllegalArgumentException("EXACT_VE_DYADIC_CERTIFICATE_REJECTED|"
				+ certificate.reason());
		if(compiled.prepared.deferredDyadic && !certificate.hasPhysicalAuthority())
			throw new IllegalArgumentException("EXACT_VE_DEFERRED_PHYSICAL_AUTHORITY_REQUIRED");
		certificate.validateCompiledProblem(compiled);
		return solve(compiled.prepared, compiled.factors, null, null, certificate);
	}

	static Statistics statistics(CompiledProblem compiled) {
		return Objects.requireNonNull(compiled, "compiled").prepared.statistics;
	}

	static FrozenInputs freezeInputs(List<Variable> variables, List<Factor> factors,
		Limits limits) {
		InputDefinition definition = validateInputs(variables, factors, limits);
		List<Factor> frozen = new ArrayList<>(factors.size());
		long hardCells = 0L;
		long numericCells = 0L;
		for(Factor factor : factors) {
			Factor materialized = freezeValidatedFactor(factor);
			frozen.add(materialized);
			long cells = 1L;
			for(Variable variable : materialized.scope)
				cells = saturatedMultiply(cells,variable.domainSize());
			FunctionalMap mapping = materialized.functionalMapping();
			if(mapping != null)
				hardCells = saturatedAdd(hardCells,mapping.finiteCount());
			else if(materialized.evaluator instanceof FiniteSupport support)
				hardCells = saturatedAdd(hardCells,support.finiteCells.length);
			else if(materialized.evaluator instanceof ConditionalSupport support)
				hardCells = saturatedAdd(hardCells,support.storedValues());
			else if(materialized.hardValues != null)
				hardCells = saturatedAdd(hardCells,cells);
			else
				numericCells = saturatedAdd(numericCells,cells);
		}
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-FrozenRepresentation", "factors=" + factors.size()
				+ " hardCells=" + hardCells + " numericCells=" + numericCells);
		return new FrozenInputs(definition, frozen);
	}

	static void validateInputStructure(List<Variable> variables, List<Factor> factors,
		Limits limits) {
		validateInputs(variables, factors, limits);
	}

	/**
	 * Validates the complete raw model before support reduction without requiring a
	 * lazy factor's conceptual Cartesian product to fit a Java array. Dense factors
	 * retain the ordinary exact-size and cost validation contract.
	 */
	static void validateReductionInputStructure(List<Variable> variables, List<Factor> factors,
		Limits limits) {
		validateCompressedInputStructure(variables, factors, List.of(), limits, false);
	}

	/**
	 * Validates deferred solver factors together with the ordinary factors that will
	 * be frozen for the cost surface. Solver-only lazy and functional relations may
	 * retain a conceptual Cartesian product larger than a Java array; every ordinary
	 * factor must already have an indexable shape. Shared factor identities are counted
	 * once because the frozen ordinary instance is also reused by the solver.
	 */
	static void validateCompressedCostInputStructure(List<Variable> variables,
		List<Factor> solverFactors, List<Factor> ordinaryFactors, Limits limits) {
		validateCompressedInputStructure(variables, solverFactors, ordinaryFactors, limits, true);
	}

	private static void validateCompressedInputStructure(List<Variable> variables,
		List<Factor> solverFactors, List<Factor> ordinaryFactors, Limits limits,
		boolean countIndexableDeferredFactors) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(solverFactors, "solverFactors");
		Objects.requireNonNull(ordinaryFactors, "ordinaryFactors");
		Objects.requireNonNull(limits, "limits");
		List<Variable> canonical = List.copyOf(variables);
		Map<Variable,Integer> index = new LinkedHashMap<>();
		Map<String,Variable> keys = new HashMap<>();
		for(int position = 0; position < canonical.size(); position++) {
			Variable variable = Objects.requireNonNull(canonical.get(position), "variable");
			if(index.put(variable, position) != null || keys.put(variable.key(), variable) != null)
				throw new IllegalArgumentException("EXACT_VE_VARIABLE_DUPLICATE|key=" + variable.key());
		}
		Set<Factor> ordinaryIdentities = java.util.Collections.newSetFromMap(
			new java.util.IdentityHashMap<>());
		ordinaryIdentities.addAll(ordinaryFactors);
		java.util.IdentityHashMap<Factor,Integer> unmatchedSolverOccurrences =
			new java.util.IdentityHashMap<>();
		List<Factor> factors = new ArrayList<>(solverFactors.size() + ordinaryFactors.size());
		for(Factor factor : solverFactors) {
			factors.add(factor);
			if(factor != null)
				unmatchedSolverOccurrences.merge(factor, 1, Integer::sum);
		}
		for(Factor factor : ordinaryFactors) {
			Integer unmatched = factor == null ? null : unmatchedSolverOccurrences.get(factor);
			if(unmatched == null || unmatched == 0)
				factors.add(factor);
			else if(unmatched == 1)
				unmatchedSolverOccurrences.remove(factor);
			else
				unmatchedSolverOccurrences.put(factor, unmatched - 1);
		}
		long denseCells = 0L;
		long maximumDenseCells = 0L;
		for(int factorOrdinal = 0; factorOrdinal < factors.size(); factorOrdinal++) {
			Factor factor = factors.get(factorOrdinal);
			Objects.requireNonNull(factor, "factor");
			Set<Integer> unique = new HashSet<>();
			long cells = 1L;
			for(Variable scoped : factor.scope) {
				Integer variable = index.get(scoped);
				if(variable == null)
					throw new IllegalArgumentException("EXACT_VE_FACTOR_VARIABLE_UNKNOWN");
				if(!unique.add(variable))
					throw new IllegalArgumentException("EXACT_VE_FACTOR_VARIABLE_DUPLICATE");
				if(cells <= Integer.MAX_VALUE)
					cells = Math.min((long)Integer.MAX_VALUE + 1L,
						cells * canonical.get(variable).domainSize());
			}
			FunctionalMap mapping = factor.functionalMapping();
			boolean indexedStorage = ordinaryIdentities.contains(factor)
				|| factor.denseValues != null || factor.hardValues != null
				|| factor.evaluator instanceof FiniteSupport
				|| factor.evaluator instanceof ConditionalSupport;
			if(indexedStorage) {
				if(cells > Integer.MAX_VALUE)
					throw new IllegalArgumentException(compressedFactorOverflow(
						factorOrdinal, factor));
				if(factor.denseValues != null && factor.denseValues.length != (int)cells
					|| factor.hardValues != null && factor.hardValues.cells() != (int)cells)
					throw new IllegalArgumentException("EXACT_VE_DENSE_FACTOR_SIZE_MISMATCH");
			}
			if(indexedStorage || countIndexableDeferredFactors
				&& (mapping != null || cells <= Integer.MAX_VALUE)) {
				long stored = mapping != null ? mapping.finiteCount()
					: factor.evaluator instanceof FiniteSupport support ? support.finiteCells.length
					: factor.evaluator instanceof ConditionalSupport conditional
						? conditional.storedValues() : cells;
				denseCells = checkedAdd(denseCells, stored, "EXACT_VE_MATERIALIZED_CELL_OVERFLOW");
				maximumDenseCells = Math.max(maximumDenseCells, stored);
			}
		}
		if(maximumDenseCells > limits.maximumFactorCells())
			throw new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells="
				+ maximumDenseCells + "|limit=" + limits.maximumFactorCells() + "|input");
		if(denseCells > limits.maximumMaterializedCells())
			throw new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells="
				+ denseCells + "|limit=" + limits.maximumMaterializedCells());
		// Preserve batch validation order: reject every malformed dense value before
		// any support factor invokes a lazy evaluator.
		for(Factor factor : factors)
			if(factor.denseValues != null)
				for(double value : factor.denseValues)
					validateCost(value);
	}

	private static String compressedFactorOverflow(int ordinal, Factor factor) {
		StringBuilder domains = new StringBuilder("[");
		int retained = Math.min(8, factor.scope.size());
		for(int axis = 0; axis < retained; axis++) {
			if(axis > 0)
				domains.append(',');
			domains.append(factor.scope.get(axis).domainSize());
		}
		if(factor.scope.size() > retained)
			domains.append(",...+").append(factor.scope.size() - retained);
		domains.append(']');
		String representation = factor.denseValues != null ? "DENSE"
			: factor.hardValues != null ? factor.hardValues.functional == null
				? "HARD_TABLE" : "HARD_FUNCTIONAL"
			: factor.evaluator instanceof FunctionalMap ? "FUNCTIONAL_MAP"
			: factor.evaluator instanceof FiniteSupport ? "FINITE_SUPPORT"
			: factor.evaluator instanceof ConditionalSupport ? "CONDITIONAL_SUPPORT"
			: factor.supportsPartialTruth() ? "PARTIAL_LAZY" : "LAZY";
		return "EXACT_VE_FACTOR_CELL_OVERFLOW|factor=" + ordinal
			+ "|arity=" + factor.scope.size() + "|domains=" + domains
			+ "|representation=" + representation;
	}

	/** Failure-only context: never evaluates a factor or serializes its full scope keys. */
	static String factorOverflowContext(int ordinal, Factor factor) {
		StringBuilder context = new StringBuilder(compressedFactorOverflow(ordinal, factor));
		context.append("|evaluator=").append(factor.evaluator == null ? "stored"
			: factor.evaluator.getClass().getName()).append("|keys=[");
		for(int axis = 0; axis < Math.min(8, factor.scope.size()); axis++) {
			if(axis > 0)
				context.append(',');
			String key = factor.scope.get(axis).key();
			if(key.length() <= 96)
				context.append(key);
			else
				context.append(key, 0, 48).append("...")
					.append(key, key.length() - 48, key.length());
		}
		return context.append(']').toString();
	}

	static Factor freezeValidatedFactor(Factor factor) {
		Objects.requireNonNull(factor, "factor");
		if(factor.denseValues != null || factor.hardValues != null
			|| factor.evaluator instanceof FiniteSupport
			|| factor.evaluator instanceof ConditionalSupport)
			return factor;
		int cells = 1;
		for(Variable variable : factor.scope)
			cells = Math.multiplyExact(cells, variable.domainSize());
		FunctionalMap mapping = factor.functionalMapping();
		if(mapping != null)
			return Factor.hardOwned(factor.scope, new HardTable(mapping));
		if(!factor.supportsPartialTruth())
			return freezeGenericFactor(factor, cells);
		return freezePartialHardFactor(factor,cells);
	}

	private static Factor freezeGenericFactor(Factor factor, int cells) {
		HardTable hard = HardTable.allocate(cells);
		double[] values = null;
		int[] local = new int[factor.scope.size()];
		int[] domains = factor.scope.stream().mapToInt(Variable::domainSize).toArray();
		for(int cell = 0; cell < cells; cell++) {
			double value = factor.evaluator.cost(local);
			validateCost(value);
			if(values == null && value == Double.POSITIVE_INFINITY)
				hard.forbid(cell);
			else if(values == null && Double.doubleToRawLongBits(value) != 0L) {
				PlannerResourceGuard.checkAdditionalCells(cells, "freeze-lazy-factor");
				values = PlannerResourceGuard.allocateDoubles(cells, "exact-numeric");
				for(int prior = 0; prior < cell; prior++)
					values[prior] = hard.costAt(prior);
				values[cell] = value;
			}
			else if(values != null)
				values[cell] = value;
			for(int position = local.length - 1; position >= 0; position--) {
				if(++local[position] < domains[position])
					break;
				local[position] = 0;
			}
		}
		return values == null ? Factor.hardOwned(factor.scope, hard.compactAllFeasible())
			: Factor.denseOwned(factor.scope, values);
	}

	private static Factor freezePartialHardFactor(Factor factor, int cells) {
		HardTable hard = HardTable.allocate(cells);
		double[] values = null;
		int arity = factor.scope.size();
		int[] local = new int[arity];
		Arrays.fill(local,-1);
		int[] domains = factor.scope.stream().mapToInt(Variable::domainSize).toArray();
		int[] suffixCells = new int[arity + 1];
		suffixCells[arity] = 1;
		for(int position=arity-1; position>=0; position--)
			suffixCells[position] = Math.multiplyExact(suffixCells[position+1],domains[position]);
		int depth = 0;
		int cell = 0;
		while(true) {
			PartialTruth truth = factor.partialTruth(local);
			if(truth != PartialTruth.UNKNOWN) {
				int end = Math.addExact(cell,suffixCells[depth]);
				if(values == null) {
					if(truth == PartialTruth.ALL_FORBIDDEN)
						hard.forbidRange(cell,end);
				}
				else
					Arrays.fill(values,cell,end,truth == PartialTruth.ALL_ZERO
						? 0d : Double.POSITIVE_INFINITY);
				cell = end;
			}
			else if(depth == arity) {
				double value = factor.evaluator.cost(local);
				validateCost(value);
				if(values == null && value == Double.POSITIVE_INFINITY)
					hard.forbid(cell);
				else if(values == null && Double.doubleToRawLongBits(value) != 0L) {
					PlannerResourceGuard.checkAdditionalCells(cells,"freeze-lazy-factor");
					values = PlannerResourceGuard.allocateDoubles(cells,"exact-numeric");
					for(int prior=0; prior<cell; prior++)
						values[prior] = hard.costAt(prior);
					values[cell] = value;
				}
				else if(values != null)
					values[cell] = value;
				cell++;
			}
			else {
				local[depth++] = 0;
				continue;
			}
			while(depth > 0) {
				int position = depth - 1;
				if(++local[position] < domains[position])
					break;
				local[position] = -1;
				depth--;
			}
			if(depth == 0)
				break;
		}
		if(cell != cells)
			throw new IllegalStateException("EXACT_VE_PARTIAL_MATERIALIZATION_SIZE_MISMATCH");
		return values == null ? Factor.hardOwned(factor.scope,hard.compactAllFeasible())
			: Factor.denseOwned(factor.scope,values);
	}

	static void materializeFactorValues(Factor factor, double[] values, boolean validateCosts) {
		Objects.requireNonNull(factor, "factor");
		Objects.requireNonNull(values, "values");
		if(factor.denseValues != null || factor.hardValues != null)
			throw new IllegalArgumentException("EXACT_VE_FACTOR_ALREADY_DENSE");
		int cells = 1;
		for(Variable variable : factor.scope)
			cells = Math.multiplyExact(cells, variable.domainSize());
		if(values.length != cells)
			throw new IllegalArgumentException("EXACT_VE_DENSE_FACTOR_SIZE_MISMATCH");
		if(!factor.supportsPartialTruth()) {
			materializeGenericFactorValues(factor, values, validateCosts);
			return;
		}
		materializePartialHardFactorValues(factor, values, validateCosts);
	}

	private static void materializeGenericFactorValues(Factor factor, double[] values,
		boolean validateCosts) {
		int[] local = new int[factor.scope.size()];
		int[] domains = factor.scope.stream().mapToInt(Variable::domainSize).toArray();
		for(int cell = 0; cell < values.length; cell++) {
			values[cell] = factor.evaluator.cost(local);
			if(validateCosts)
				validateCost(values[cell]);
			// Same last-axis-fastest callback order without divisions at every cell.
			for(int position = local.length - 1; position >= 0; position--) {
				if(++local[position] < domains[position])
					break;
				local[position] = 0;
			}
		}
	}

	private static void materializePartialHardFactorValues(Factor factor, double[] values,
		boolean validateCosts) {
		int arity = factor.scope.size();
		int[] local = new int[arity];
		Arrays.fill(local, -1);
		int[] domains = factor.scope.stream().mapToInt(Variable::domainSize).toArray();
		int[] suffixCells = new int[arity + 1];
		suffixCells[arity] = 1;
		for(int position = arity - 1; position >= 0; position--)
			suffixCells[position] = Math.multiplyExact(suffixCells[position + 1], domains[position]);
		int depth = 0;
		int cell = 0;
		long partialCalls = 0;
		long leafCalls = 0;
		long zeroCells = 0;
		long forbiddenCells = 0;
		long provenSubtrees = 0;
		long subtreeCells = 0;
		long started = System.nanoTime();
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-PartialHardFreezeBegin", "domains="
				+ Arrays.toString(domains) + " logicalCells=" + values.length);
		while(true) {
			PartialTruth truth = factor.partialTruth(local);
			partialCalls++;
			if(truth != PartialTruth.UNKNOWN) {
				double value = truth == PartialTruth.ALL_ZERO ? 0.0 : Double.POSITIVE_INFINITY;
				int end = Math.addExact(cell, suffixCells[depth]);
				Arrays.fill(values, cell, end, value);
				if(depth < arity) {
					provenSubtrees++;
					subtreeCells += end - cell;
				}
				if(truth == PartialTruth.ALL_ZERO)
					zeroCells += end - cell;
				else
					forbiddenCells += end - cell;
				cell = end;
			}
			else if(depth == arity) {
				values[cell] = factor.evaluator.cost(local);
				leafCalls++;
				if(validateCosts)
					validateCost(values[cell]);
				cell++;
			}
			else {
				local[depth++] = 0;
				continue;
			}
			while(depth > 0) {
				int position = depth - 1;
				if(++local[position] < domains[position])
					break;
				local[position] = -1;
				depth--;
			}
			if(depth == 0)
				break;
		}
		if(cell != values.length)
			throw new IllegalStateException("EXACT_VE_PARTIAL_MATERIALIZATION_SIZE_MISMATCH");
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-PartialHardFreeze", "scope="
				+ factor.scope.stream().map(Variable::key).toList() + " domains="
				+ Arrays.toString(domains) + " logicalCells=" + values.length
				+ " partialCalls=" + partialCalls + " leafCalls=" + leafCalls
				+ " zeroCells=" + zeroCells + " forbiddenCells=" + forbiddenCells
				+ " provenSubtrees=" + provenSubtrees + " subtreeCells=" + subtreeCells
				+ " elapsedNanos=" + (System.nanoTime() - started));
	}

	static BoundaryMessage boundaryLeaf(List<Variable> allVariables, Factor factor,
		Limits limits) {
		return boundaryLeaves(allVariables, List.of(factor), limits).get(0);
	}

	/**
	 * Additional Regional numeric storage owned by one leaf. Source-owned dense
	 * tables are borrowed and remain governed by the exact input limits; this is
	 * not an estimate or cap for total JVM/model memory. Generic lazy tables report
	 * their frozen size; finite and conditional hard relations report stored support.
	 */
	static long boundaryLeafRetainedCells(Factor factor) {
		Objects.requireNonNull(factor, "factor");
		FunctionalMap mapping = factor.functionalMapping();
		if(mapping != null)
			return mapping.finiteCount();
		if(factor.evaluator instanceof FiniteSupport support)
			return support.finiteCells.length;
		if(factor.evaluator instanceof ConditionalSupport support)
			return support.storedValues();
		if(factor.denseValues != null || factor.hardValues != null)
			return 0L;
		long cells = 1L;
		for(Variable variable : factor.scope)
			cells = saturatedMultiply(cells, variable.domainSize());
		return cells;
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
			Factor factor = factors.get(factorIndex);
			DenseFactor dense = denseFactors.get(factorIndex);
			if(dense.conditionalSupport != null) {
				leaves.add(new BoundaryMessage(input.variables, input.domains,
					factor.scope, input.scopes.get(factorIndex), dense.conditionalSupport,
					boundaryLeafRetainedCells(factor), dense.logicalCells()));
				continue;
			}
			if(dense.hardValues == null)
				for(int cell = 0; cell < dense.storedCells(); cell++)
					if(dense.storedValue(cell) < 0d)
						throw new IllegalArgumentException(
							"INCREMENTAL_MESSAGE_COST_INVALID|value=" + dense.storedValue(cell));
			leaves.add(dense.sparseCells != null
				? new BoundaryMessage(input.variables, input.domains,
					factor.scope, input.scopes.get(factorIndex), dense.values,
					null, dense.values, List.of(), null, null,
					boundaryLeafRetainedCells(factor), dense.logicalCells(), null, null, 0d,
					null, dense.sparseCells)
				: dense.hardValues == null
				? new BoundaryMessage(input.variables, input.domains,
					factor.scope, input.scopes.get(factorIndex), dense.values,
					null, dense.values, List.of(), null, null,
					boundaryLeafRetainedCells(factor), dense.logicalCells())
				: new BoundaryMessage(input.variables, input.domains,
					factor.scope, input.scopes.get(factorIndex), dense.hardValues,
					List.of(), null, null, boundaryLeafRetainedCells(factor), dense.logicalCells()));
		}
		return List.copyOf(leaves);
	}

	/**
	 * Removes only domain-one axes. Their sole value needs neither enumeration nor an
	 * argmin table, so the projected message aliases all numeric state from its child.
	 */
	static BoundaryMessage projectSingletons(BoundaryMessage input) {
		Objects.requireNonNull(input, "input");
		if(input.conditionalSupport != null)
			return input;
		int retainedCount = 0;
		for(int variable : input.scopeIndices)
			if(input.domains[variable] > 1)
				retainedCount++;
		if(retainedCount == input.scopeIndices.length)
			return input;
		int[] retainedScope = new int[retainedCount];
		int[] removedScope = new int[input.scopeIndices.length - retainedCount];
		int[][] retainedClasses = input.storageClasses == null ? null : new int[retainedCount][];
		List<Variable> retainedVariables = new ArrayList<>(retainedCount);
		int retained = 0;
		int removed = 0;
		for(int axis = 0; axis < input.scopeIndices.length; axis++) {
			int variable = input.scopeIndices[axis];
			if(input.domains[variable] == 1)
				removedScope[removed++] = variable;
			else {
				if(retainedClasses != null)
					retainedClasses[retained] = input.storageClasses[axis];
				retainedScope[retained++] = variable;
				retainedVariables.add(input.variables.get(variable));
			}
		}
		return new BoundaryMessage(input.variables, input.domains, retainedVariables,
			retainedScope, input.values, input.lowValues, input.lowerValues, List.of(input),
			removedScope, null, 0L, 0L, input.hardValues,
			input.cachedMinimum, input.cachedLowerBound, retainedClasses, input.sparseCells);
	}

	static BoundaryMessage mergeBoundary(BoundaryMessage left, BoundaryMessage right,
		List<Variable> outputBoundary, Limits limits, long maximumAssignments) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");
		return mergeBoundary(List.of(left, right), outputBoundary, limits, maximumAssignments);
	}

	static BoundaryMessage mergeBoundary(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits, long maximumAssignments) {
		if(maximumAssignments <= 0)
			throw incrementalResource("merge", "assignment-limit", maximumAssignments);
		return mergeBoundaryInternal(inputMessages, outputBoundary, limits, maximumAssignments, null);
	}

	static BoundaryMessage mergeBoundary(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits, long maximumAssignments,
		BoundaryMergeCounters counters) {
		if(maximumAssignments <= 0)
			throw incrementalResource("merge", "assignment-limit", maximumAssignments);
		return mergeBoundaryInternal(inputMessages, outputBoundary, limits, maximumAssignments, counters);
	}

	/** Production merging has no elapsed-time or assignment-count cutoff. */
	static BoundaryMessage mergeBoundary(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits) {
		return mergeBoundaryInternal(inputMessages, outputBoundary, limits, null, null);
	}

	/** Production merging with optional pruning diagnostics and no fixed work cutoff. */
	static BoundaryMessage mergeBoundary(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits, BoundaryMergeCounters counters) {
		return mergeBoundaryInternal(inputMessages, outputBoundary, limits, null, counters);
	}

	private static BoundaryMessage mergeBoundaryInternal(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits, Long maximumAssignments,
		BoundaryMergeCounters counters) {
		return mergeBoundaryInternal(inputMessages, outputBoundary, limits, maximumAssignments,
			counters, defaultCostPruningMode());
	}

	static BoundaryMessage mergeBoundaryWithPruningForTest(List<BoundaryMessage> inputs,
		List<Variable> boundary, Limits limits, CostPruningMode mode, BoundaryMergeCounters counters) {
		return mergeBoundaryInternal(inputs, boundary, limits, null, counters, mode);
	}

	private static BoundaryMessage mergeBoundaryInternal(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits, Long maximumAssignments,
		BoundaryMergeCounters counters, CostPruningMode mode) {
		try {
			return computeBoundaryMerge(inputMessages, outputBoundary, limits, maximumAssignments, counters, mode);
		}
		catch(OutOfMemoryError failure) {
			// The merge only reads its inputs and publishes a completed message. If any
			// workspace allocation fails, Local can retain its already validated cover.
			throw PlannerResourceGuard.allocationFailure("regional-merge", -1L, "boundary-message", failure);
		}
	}

	private static BoundaryMessage computeBoundaryMerge(List<BoundaryMessage> inputMessages,
		List<Variable> outputBoundary, Limits limits, Long maximumAssignments,
		BoundaryMergeCounters counters, CostPruningMode mode) {
		Objects.requireNonNull(inputMessages, "inputMessages");
		Objects.requireNonNull(outputBoundary, "outputBoundary");
		Objects.requireNonNull(limits, "limits");
		if(inputMessages.isEmpty())
			throw new IllegalArgumentException("INCREMENTAL_MESSAGE_INPUT_EMPTY");
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
		if(maximumAssignments != null && unionCells > maximumAssignments)
			throw incrementalResource("merge", "assignments", unionCells);
		if(outputCells > limits.maximumFactorCells())
			throw incrementalResource("merge", "factor-cells", outputCells);
		long retained = boundaryMultiply(outputCells, 4L, "merge", "retained-overflow");
		if(retained > limits.maximumMaterializedCells())
			throw incrementalResource("merge", "retained-cells", retained);
		if(counters != null)
			counters.fullChildEvaluations += unionCells * inputMessages.size();
		boolean localPrefixCuts = PruningAblation.current().local();
		if(localPrefixCuts && unionCells > 64) {
			BoundaryMessage supported = mergeBoundarySupport(inputMessages, outputBoundary,
				unionScope, outputScope, unionCells, counters, mode);
			if(supported != null)
				return supported;
		}
		boolean exactNonnegativeSum = exactNonnegativeBoundarySum(inputMessages);
		// A later forbidden row may only be skipped when every earlier numeric
		// prefix is certified exact and finite. Otherwise an earlier addition must
		// still report overflow, even if the final hard child would be infinite.
		// The first child's infinity is unconditionally absorbing, as in FiniteRowIndex.
		List<ExactFiniteSupportJoin.SupportRelation> hardSupports = exactNonnegativeSum
			? inputMessages.stream().map(BoundaryMessage::supportRelation).filter(Objects::nonNull).toList()
			: first.supportRelation() == null ? List.of() : List.of(first.supportRelation());
		boolean localCostCut = localPrefixCuts && inputMessages.size() > 1 && internalCells > 1
			&& exactNonnegativeSum;
		CostPruning costPruning = internalCells > 1
			? boundaryCostPruning(inputMessages, mode, counters, exactNonnegativeSum) : null;
		// Three double arrays and one int backpointer array; borrowed inputs are
		// already reflected in the JVM's used heap, not charged a second time.
		PlannerResourceGuard.checkAdditionalBytes(outputCells * (3L * Double.BYTES + Integer.BYTES),
			"regional-merge");

		int cells = (int)outputCells;
		double[] values = PlannerResourceGuard.allocateDoubles(cells, "exact-numeric");
		double[] lowValues = PlannerResourceGuard.allocateDoubles(cells, "exact-numeric");
		double[] lowerValues = PlannerResourceGuard.allocateDoubles(cells, "exact-numeric");
		int[] choices = PlannerResourceGuard.allocateInts(cells, "exact-backpointer");
		Arrays.fill(choices, -1);
		int[] assignment = new int[first.variables.size()];
		double[] candidateCost = new double[2];
		if(!hardSupports.isEmpty()) {
			Arrays.fill(values,Double.POSITIVE_INFINITY);
			Arrays.fill(lowerValues,Double.POSITIVE_INFINITY);
			ExactFiniteSupportJoin.forEach(unionScope,first.domains,hardSupports,selected -> {
				int outputCell = encode(outputScope,first.domains,selected);
				int unionCell = encode(unionScope,first.domains,selected);
				double bestHigh = values[outputCell];
				double bestLow = lowValues[outputCell];
				candidateCost[0] = 0d;
				candidateCost[1] = 0d;
				double candidateLower = 0d;
				boolean cut = false;
				for(int messageIndex=0; messageIndex<inputMessages.size(); messageIndex++) {
					BoundaryMessage message = inputMessages.get(messageIndex);
					int childCell = message.boundaryCellUnchecked(selected);
					addBoundaryCost(candidateCost,message.highAt(childCell),message.lowAt(childCell));
					candidateLower = addBoundaryLower(candidateLower,message.lowerAt(childCell));
					if(counters != null)
						counters.childEvaluations++;
					if(localPrefixCuts && messageIndex + 1 < inputMessages.size()
						&& absorbingBoundaryInfinity(candidateCost[0],candidateLower)) {
						if(counters != null)
							counters.infeasibleCuts++;
						cut = true;
						break;
					}
					if(costPruning != null && costPruning.exceeds(candidateCost[0], candidateCost[1],
						messageIndex + 1, bestHigh, bestLow)) {
						cut = true;
						break;
					}
					int comparison = compareBoundaryCost(candidateCost[0],candidateCost[1],bestHigh,bestLow);
					if(messageIndex + 1 < inputMessages.size() && localCostCut
						&& (comparison > 0 || comparison == 0 && choices[outputCell] >= 0
							&& unionCell >= choices[outputCell])) {
						if(counters != null)
							counters.costCuts++;
						cut = true;
						break;
					}
				}
				if(cut)
					return;
				int comparison = compareBoundaryCost(candidateCost[0],candidateCost[1],bestHigh,bestLow);
				if(comparison < 0 || comparison == 0 && choices[outputCell] >= 0
					&& unionCell < choices[outputCell]) {
					values[outputCell] = candidateCost[0];
					lowValues[outputCell] = candidateCost[1];
					choices[outputCell] = unionCell;
				}
				lowerValues[outputCell] = Math.min(lowerValues[outputCell],candidateLower);
			});
			double messageMinimumHigh = Double.POSITIVE_INFINITY;
			double messageMinimumLow = 0d;
			double messageLowerBound = Double.POSITIVE_INFINITY;
			for(int cell=0; cell<cells; cell++) {
				if(compareBoundaryCost(values[cell],lowValues[cell],
					messageMinimumHigh,messageMinimumLow) < 0) {
					messageMinimumHigh = values[cell];
					messageMinimumLow = lowValues[cell];
				}
				messageLowerBound = Math.min(messageLowerBound,lowerValues[cell]);
			}
			return new BoundaryMessage(first.variables,first.domains,outputBoundary,outputScope,
				values,lowValues,lowerValues,inputMessages,unionScope,choices,
				retained,unionCells,null,
				new PreciseCost(messageMinimumHigh,messageMinimumLow,0L),messageLowerBound);
		}
		int[] outputLocal = new int[outputScope.length];
		BoundaryProjectionOdometer projection = new BoundaryProjectionOdometer(
			inputMessages,internalScope,first.domains,assignment);
		double messageMinimumHigh = Double.POSITIVE_INFINITY;
		double messageMinimumLow = 0d;
		double messageLowerBound = Double.POSITIVE_INFINITY;
		for(int outputCell = 0; outputCell < cells; outputCell++) {
			decode(outputCell, outputScope, first.domains, outputLocal, assignment);
			projection.initialize();
			double bestHigh = Double.POSITIVE_INFINITY;
			double bestLow = 0d;
			double bestLower = Double.POSITIVE_INFINITY;
			int bestUnionCell = -1;
			for(int internalCell = 0; internalCell < (int)internalCells; internalCell++) {
				if(internalCell > 0)
					projection.advance();
				int childCell = projection.childCell(0);
				candidateCost[0] = first.highAt(childCell);
				candidateCost[1] = first.lowAt(childCell);
				double candidateLower = first.lowerAt(childCell);
				if(counters != null)
					counters.childEvaluations++;
				boolean cut = false;
				// Both infinities are absorbing; no unread child can restore feasibility.
				if(localPrefixCuts && inputMessages.size() > 1
					&& absorbingBoundaryInfinity(candidateCost[0], candidateLower)) {
					if(counters != null)
						counters.infeasibleCuts++;
					continue;
				}
				if(costPruning != null && costPruning.exceeds(candidateCost[0], candidateCost[1],
					1, bestHigh, bestLow))
					continue;
				// The certificate proves exact partial sums and nonnegative unread terms.
				// Equality is also removable because canonical enumeration keeps the first tie.
				if(inputMessages.size() > 1 && localCostCut
					&& compareBoundaryCost(candidateCost[0],candidateCost[1],bestHigh,bestLow) >= 0) {
					if(counters != null)
						counters.costCuts++;
					continue;
				}
				for(int messageIndex = 1; messageIndex < inputMessages.size(); messageIndex++) {
					BoundaryMessage message = inputMessages.get(messageIndex);
					childCell = projection.childCell(messageIndex);
					addBoundaryCost(candidateCost,message.highAt(childCell),message.lowAt(childCell));
					candidateLower = addBoundaryLower(candidateLower,
						message.lowerAt(childCell));
					if(counters != null)
						counters.childEvaluations++;
					// Apply the same absorbing-infinity and exact nonnegative-prefix proofs
					// after each canonical child, but only when a suffix remains unread.
					if(localPrefixCuts && messageIndex + 1 < inputMessages.size()
						&& absorbingBoundaryInfinity(candidateCost[0], candidateLower)) {
						if(counters != null)
							counters.infeasibleCuts++;
						cut = true;
						break;
					}
					if(costPruning != null && costPruning.exceeds(candidateCost[0], candidateCost[1],
						messageIndex + 1, bestHigh, bestLow)) {
						cut = true;
						break;
					}
					if(messageIndex + 1 < inputMessages.size() && localCostCut
						&& compareBoundaryCost(candidateCost[0],candidateCost[1],bestHigh,bestLow) >= 0) {
						if(counters != null)
							counters.costCuts++;
						cut = true;
						break;
					}
				}
				if(cut)
					continue;
				if(compareBoundaryCost(candidateCost[0],candidateCost[1],bestHigh,bestLow) < 0) {
					bestHigh = candidateCost[0];
					bestLow = candidateCost[1];
					bestUnionCell = encode(unionScope, first.domains, assignment);
				}
				bestLower = Math.min(bestLower, candidateLower);
			}
			values[outputCell] = bestHigh;
			lowValues[outputCell] = bestLow;
			lowerValues[outputCell] = bestLower;
			choices[outputCell] = bestUnionCell;
			if(compareBoundaryCost(bestHigh,bestLow,messageMinimumHigh,messageMinimumLow) < 0) {
				messageMinimumHigh = bestHigh;
				messageMinimumLow = bestLow;
			}
			messageLowerBound = Math.min(messageLowerBound, bestLower);
		}
		return new BoundaryMessage(first.variables, first.domains, outputBoundary, outputScope,
			values, lowValues, lowerValues, inputMessages, unionScope, choices,
			retained, unionCells, null,
			new PreciseCost(messageMinimumHigh,messageMinimumLow,0L),messageLowerBound);
	}

	private static final class BoundaryProjectionOdometer {
		private final List<BoundaryMessage> messages;
		private final int[] internalScope;
		private final int[] domains;
		private final int[] assignment;
		private final int[] childCells;
		private final int[][] childMessages;
		private final int[][] childAxes;

		private BoundaryProjectionOdometer(List<BoundaryMessage> messages, int[] internalScope,
			int[] domains, int[] assignment) {
			this.messages = messages;
			this.internalScope = internalScope;
			this.domains = domains;
			this.assignment = assignment;
			childCells = PlannerResourceGuard.allocateInts(messages.size(),
				"regional-merge-projection");
			childMessages = new int[internalScope.length][];
			childAxes = new int[internalScope.length][];
			for(int axis = 0; axis < internalScope.length; axis++) {
				int variable = internalScope[axis];
				int count = 0;
				for(BoundaryMessage message : messages)
					for(int scopedVariable : message.scopeIndices)
						if(scopedVariable == variable) {
							count++;
							break;
						}
				int[] messageIndexes = childMessages[axis] = PlannerResourceGuard.allocateInts(
					count,"regional-merge-projection");
				int[] axes = childAxes[axis] = PlannerResourceGuard.allocateInts(
					count,"regional-merge-projection");
				int output = 0;
				for(int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
					BoundaryMessage message = messages.get(messageIndex);
					for(int position = 0; position < message.scopeIndices.length; position++)
						if(message.scopeIndices[position] == variable) {
							messageIndexes[output] = messageIndex;
							axes[output++] = position;
							break;
						}
				}
			}
		}

		private void initialize() {
			for(int variable : internalScope)
				assignment[variable] = 0;
			for(int messageIndex = 0; messageIndex < messages.size(); messageIndex++)
				childCells[messageIndex] = messages.get(messageIndex)
					.boundaryCellUnchecked(assignment);
		}

		private int childCell(int message) {
			return childCells[message];
		}

		private void advance() {
			for(int axis = internalScope.length - 1; axis >= 0; axis--) {
				int variable = internalScope[axis];
				int previous = assignment[variable];
				int next = previous + 1;
				if(next < domains[variable]) {
					assignment[variable] = next;
				}
				else
					assignment[variable] = 0;
				int[] messageIndexes = childMessages[axis];
				int[] axes = childAxes[axis];
				for(int index = 0; index < messageIndexes.length; index++) {
					BoundaryMessage message = messages.get(messageIndexes[index]);
					int messageAxis = axes[index];
					int current = message.storedValue(messageAxis,previous);
					int updated = message.storedValue(messageAxis,assignment[variable]);
					childCells[messageIndexes[index]] +=
						(updated - current) * message.strides[messageAxis];
				}
				if(next < domains[variable])
					return;
			}
			throw new IllegalStateException("INCREMENTAL_MESSAGE_PROJECTION_EXHAUSTED");
		}
	}

	private static ExactFiniteSupportJoin.Relation sparseHardSupport(int[] scope,
		double[] values, double[] lowValues, double[] lowerValues) {
		int finite = 0;
		for(int cell=0; cell<values.length; cell++) {
			double high = values[cell];
			double low = lowValues == null ? 0d : lowValues[cell];
			if(Double.doubleToRawLongBits(low) != 0L
				|| Double.doubleToRawLongBits(lowerValues[cell]) != Double.doubleToRawLongBits(high))
				return null;
			if(high == Double.POSITIVE_INFINITY)
				continue;
			if(Double.doubleToRawLongBits(high) != 0L)
				return null;
			finite++;
		}
		if(finite == values.length)
			return null;
		int[] finiteCells = new int[finite];
		int output = 0;
		for(int cell=0; cell<values.length; cell++)
			if(values[cell] != Double.POSITIVE_INFINITY)
				finiteCells[output++] = cell;
		return new ExactFiniteSupportJoin.Relation(scope,finiteCells);
	}

	private static ExactFiniteSupportJoin.Relation sparseHardSupport(int[] scope,
		HardTable values) {
		int finite = values.finiteCount();
		if(finite == values.cells() || (long)finite * 2 > values.cells())
			return null;
		int[] finiteCells = PlannerResourceGuard.allocateInts(finite, "exact-hard-support");
		int output = 0;
		if(values.functional != null) {
			for(int cell = values.functional.finiteCellAfter(-1); cell >= 0;
				cell = values.functional.finiteCellAfter(cell))
				finiteCells[output++] = cell;
			return new ExactFiniteSupportJoin.Relation(scope, finiteCells);
		}
		for(int word = 0; word < values.forbidden.length; word++) {
			long finiteBits = ~values.forbidden[word];
			if(word == values.forbidden.length - 1 && (values.cells() & 63) != 0)
				finiteBits &= (1L << (values.cells() & 63)) - 1L;
			while(finiteBits != 0L) {
				finiteCells[output++] = (word << 6) + Long.numberOfTrailingZeros(finiteBits);
				finiteBits &= finiteBits - 1L;
			}
		}
		return new ExactFiniteSupportJoin.Relation(scope,finiteCells);
	}

	/** Pointwise-equal (high, low, lower) response classes for this exact merge. */
	private static final class BoundaryProjection {
		final int[] domains;
		final int[][] classes, representatives;
		final boolean identity;
		final int[] originalDomains, unionScope;

		BoundaryProjection(List<BoundaryMessage> inputs, int[] unionScope) {
			originalDomains = inputs.get(0).domains;
			domains = originalDomains.clone();
			classes = new int[domains.length][];
			representatives = new int[domains.length][];
			this.unionScope = unionScope;
			boolean unchanged = true;
			for(int variable : unionScope) {
				int[] common = null;
				for(BoundaryMessage input : inputs) {
					for(int axis = 0; axis < input.scopeIndices.length; axis++) {
						if(input.scopeIndices[axis] != variable)
							continue;
						int[] next = input.valueClasses(axis);
						common = common == null ? next : ExactFactorValueClasses.refine(common, next);
						break;
					}
					if(common != null && common[common.length - 1] == common.length - 1)
						break;
				}
				classes[variable] = common;
				representatives[variable] = ExactFactorValueClasses.representatives(common);
				domains[variable] = representatives[variable].length;
				unchanged &= domains[variable] == originalDomains[variable];
			}
			identity = unchanged;
		}

		StoredCellOdometer storedCells(BoundaryMessage input) {
			return new StoredCellOdometer(input);
		}

		private final class StoredCellOdometer {
			private final BoundaryMessage input;
			private final int[] quotientValues;
			private final boolean direct;
			private int stored;

			private StoredCellOdometer(BoundaryMessage input) {
				this.input = input;
				quotientValues = new int[input.scopeIndices.length];
				direct = identity && input.storageClasses == null;
				reset();
			}

			private void reset() {
				Arrays.fill(quotientValues, 0);
				stored = 0;
				if(!direct)
					for(int axis = 0; axis < input.scopeIndices.length; axis++) {
						int variable = input.scopeIndices[axis];
						int original = representatives[variable][0];
						stored += input.storedValue(axis, original) * input.strides[axis];
					}
			}

			private int next() {
				int result = stored;
				if(direct) {
					stored++;
					return result;
				}
				for(int axis = input.scopeIndices.length - 1; axis >= 0; axis--) {
					int variable = input.scopeIndices[axis];
					int previousOriginal = representatives[variable][quotientValues[axis]];
					int next = quotientValues[axis] + 1;
					if(next == domains[variable])
						next = 0;
					quotientValues[axis] = next;
					int nextOriginal = representatives[variable][next];
					stored += (input.storedValue(axis, nextOriginal)
						- input.storedValue(axis, previousOriginal)) * input.strides[axis];
					if(next != 0)
						break;
				}
				return result;
			}
		}

		int[][] storageClasses(int[] scope) {
			boolean unchanged = true;
			for(int variable : scope)
				unchanged &= domains[variable] == originalDomains[variable];
			if(unchanged)
				return null;
			int[][] maps = new int[scope.length][];
			for(int axis = 0; axis < scope.length; axis++) {
				int variable = scope[axis];
				if(domains[variable] != originalDomains[variable])
					maps[axis] = classes[variable];
			}
			return maps;
		}

		int[] lift(int[] quotient, int[] original) {
			if(identity)
				return quotient;
			for(int variable : unionScope)
				original[variable] = representatives[variable][quotient[variable]];
			return original;
		}

		boolean inputIdentity(BoundaryMessage input) {
			for(int variable : input.scopeIndices)
				if(domains[variable] != originalDomains[variable])
					return false;
			return true;
		}

		ExactFiniteSupportJoin.SupportRelation projectSupport(BoundaryMessage input) {
			ExactFiniteSupportJoin.SupportRelation support = input.supportRelation();
			if(support == null || input.storageClasses != null)
				return null;
			if(inputIdentity(input))
				return support;
			if(!(support instanceof ExactFiniteSupportJoin.Relation explicit))
				throw new IllegalStateException("REGIONAL_CONDITIONAL_PROJECTION_NONIDENTITY");
			int[] finite = explicit.finiteCells();
			int[] projected = PlannerResourceGuard.allocateInts(finite.length,
				"regional-projected-support-cells");
			int[] originalStrides = PlannerResourceGuard.allocateInts(input.scopeIndices.length,
				"regional-projected-support-strides");
			int stride = 1;
			for(int axis = input.scopeIndices.length - 1; axis >= 0; axis--) {
				originalStrides[axis] = stride;
				stride *= originalDomains[input.scopeIndices[axis]];
			}
			for(int row = 0; row < finite.length; row++) {
				int quotientCell = 0;
				for(int axis = 0; axis < input.scopeIndices.length; axis++) {
					int variable = input.scopeIndices[axis];
					int original = finite[row] / originalStrides[axis] % originalDomains[variable];
					quotientCell = quotientCell * domains[variable] + classes[variable][original];
				}
				projected[row] = quotientCell;
			}
			Arrays.sort(projected);
			int unique = 0;
			for(int cell : projected)
				if(unique == 0 || projected[unique - 1] != cell)
					projected[unique++] = cell;
			if(unique != projected.length) {
				int[] compact = PlannerResourceGuard.allocateInts(unique,
					"regional-projected-support-unique");
				System.arraycopy(projected,0,compact,0,unique);
				projected = compact;
			}
			return new ExactFiniteSupportJoin.Relation(input.scopeIndices,projected);
		}
	}

	/**
	 * Enumerates the exact natural join of selective, nonabsorbing child rows. A
	 * primary infinity alone cannot discard a row: its certified lower bound may
	 * still be finite. Logical scopes and their resource preflight are unchanged;
	 * response-equivalent output rows share stored numbers and internal witnesses.
	 */
	private static BoundaryMessage mergeBoundarySupport(List<BoundaryMessage> inputs,
		List<Variable> boundary, int[] unionScope, int[] outputScope, long unionCells,
		BoundaryMergeCounters counters, CostPruningMode mode) {
		// As in sparseRangeSafe, a conservative exponent certificate protects the
		// ordered DD arithmetic. At most 2^20 additions of high/low terms <= 2^400
		// (including their rounding intermediates) stay far below binary64 overflow.
		// Otherwise retain dense traversal, including its observable overflow errors.
		if(inputs.size() > (1 << 20))
			return null;
		for(BoundaryMessage input : inputs) {
			if(input.hardValues != null || input.conditionalSupport != null)
				continue;
			for(int cell = 0; cell < input.values.length; cell++) {
				double high = input.values[cell];
				double low = input.lowValues == null ? 0d : input.lowValues[cell];
				if(high != Double.POSITIVE_INFINITY && (Math.abs(high) > 0x1.0p400
					|| Math.abs(low) > 0x1.0p400))
					return null;
			}
		}
		BoundaryProjection projection = new BoundaryProjection(inputs, unionScope);
		List<ExactFiniteSupportJoin.SupportRelation> relations = new ArrayList<>();
		for(BoundaryMessage input : inputs) {
			// An uncompressed typed relation already owns its finite logical rows in
			// ascending order. Reuse them instead of scanning its Cartesian table.
			ExactFiniteSupportJoin.SupportRelation projectedSupport = projection.projectSupport(input);
			if(projectedSupport != null) {
				relations.add(projectedSupport);
				continue;
			}
			int cells = (int)boundaryCells(input.scopeIndices, projection.domains, "merge", "support-cells");
			BoundaryProjection.StoredCellOdometer storedCells = projection.storedCells(input);
			int size = 0;
			for(int cell = 0; cell < cells; cell++) {
				int stored = storedCells.next();
				if(counters != null)
					counters.supportCellsExamined++;
				if(!absorbingBoundaryInfinity(input.highAt(stored), input.lowerAt(stored))
					&& ++size > cells / 2)
					break;
			}
			if((long)size * 2 > cells)
				continue;
			int[] support = PlannerResourceGuard.allocateInts(size,
				"regional-support-cells");
			int position = 0;
			storedCells.reset();
			for(int cell = 0; cell < cells; cell++) {
				int stored = storedCells.next();
				if(counters != null)
					counters.supportCellsExamined++;
				if(!absorbingBoundaryInfinity(input.highAt(stored), input.lowerAt(stored)))
					support[position++] = cell;
			}
			relations.add(new ExactFiniteSupportJoin.Relation(input.scopeIndices, support));
		}
		if(projection.identity && relations.isEmpty())
			return null;
		int quotientCells = (int)boundaryCells(outputScope, projection.domains, "merge", "quotient-output");
		PlannerResourceGuard.checkAdditionalBytes((long)quotientCells * (3L * Double.BYTES + Integer.BYTES),
			"regional-merge");
		double[] values = PlannerResourceGuard.allocateDoubles(quotientCells, "exact-numeric");
		double[] lows = PlannerResourceGuard.allocateDoubles(quotientCells, "exact-numeric");
		double[] lowers = PlannerResourceGuard.allocateDoubles(quotientCells, "exact-numeric");
		int[] choices = PlannerResourceGuard.allocateInts(quotientCells, "exact-backpointer");
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		Arrays.fill(lowers, Double.POSITIVE_INFINITY);
		Arrays.fill(choices, -1);
		BoundaryMessage first = inputs.get(0);
		CostPruning costPruning = mode == CostPruningMode.LEGACY ? null
			: boundaryCostPruning(inputs, mode, counters, exactNonnegativeBoundarySum(inputs));
		int[] originalAssignment = projection.identity ? null : new int[first.domains.length];
		ExactFiniteSupportJoin.forEach(unionScope, projection.domains, relations, quotient -> {
			int[] assignment = projection.lift(quotient, originalAssignment);
			int outputCell = encode(outputScope, projection.domains, quotient);
			int childCell = first.boundaryCellUnchecked(assignment);
			PreciseCost candidate = first.valueAt(childCell);
			double lower = first.lowerAt(childCell);
			if(counters != null)
				counters.childEvaluations++;
			for(int index = 1; index < inputs.size(); index++) {
				if(costPruning != null && costPruning.exceeds(candidate.high, candidate.low,
					index, values[outputCell], lows[outputCell]))
					return;
				BoundaryMessage input = inputs.get(index);
				childCell = input.boundaryCellUnchecked(assignment);
				candidate = candidate.plus(input.highAt(childCell),input.lowAt(childCell),0L);
				lower = addBoundaryLower(lower,input.lowerAt(childCell));
				if(counters != null)
					counters.childEvaluations++;
			}
			int comparison = candidate.compareTo(new PreciseCost(values[outputCell], lows[outputCell], 0L));
			if(comparison < 0 || comparison == 0 && choices[outputCell] >= 0) {
				int unionCell = encode(unionScope, first.domains, assignment);
				// Join order is independent of canonical tie order. With the boundary
				// fixed, the smallest union row is the first original internal row.
				if(comparison < 0 || unionCell < choices[outputCell]) {
					values[outputCell] = candidate.high;
					lows[outputCell] = candidate.low;
					choices[outputCell] = unionCell;
				}
			}
			lowers[outputCell] = Math.min(lowers[outputCell], lower);
		});
		return new BoundaryMessage(first.variables, first.domains, boundary, outputScope,
			values, lows, lowers, inputs, unionScope, choices, 4L * quotientCells, unionCells,
			null, 0d, projection.storageClasses(outputScope));
	}

	private static boolean exactNonnegativeBoundarySum(List<BoundaryMessage> inputMessages) {
		List<double[]> tables = new ArrayList<>(inputMessages.size());
		for(BoundaryMessage message : inputMessages) {
			if(message.hardValues != null || message.conditionalSupport != null)
				continue;
			tables.add(message.values);
			for(int cell = 0; cell < message.values.length; cell++) {
				double high = message.values[cell];
				double low = message.lowValues == null ? 0d : message.lowValues[cell];
				if(high != Double.POSITIVE_INFINITY && high < 0d)
					return false;
				if(low != 0d || Double.doubleToRawLongBits(message.lowerValues[cell])
					!= Double.doubleToRawLongBits(high))
					return false;
			}
		}
		ExactDyadicCosts.Certificate certificate = ExactDyadicCosts.certifyTables(tables);
		return certificate.supported() && certificate.maximumSumBits() <= 53;
	}

	/** Legacy experiment names retain their original behavior; no new public flags. */
	enum CostPruningMode { LEGACY, PREFIX, SUFFIX }

	private static CostPruningMode defaultCostPruningMode() {
		return PruningAblation.current().explicit() ? CostPruningMode.LEGACY : CostPruningMode.SUFFIX;
	}

	/** Exact, nonnegative suffix bounds over disjoint child factors, never across output states. */
	private static final class CostPruning {
		private final double[] suffixHigh;
		private final double[] suffixLow;
		private final boolean dyadic;
		private final BoundaryMergeCounters counters;

		private CostPruning(double[] high, double[] low, boolean dyadic, BoundaryMergeCounters counters) {
			suffixHigh = high;
			suffixLow = low;
			this.dyadic = dyadic;
			this.counters = counters;
			for(int index = high.length - 2; index >= 0; index--) {
				if(dyadic) {
					long sum = (long)low[index] + (long)low[index + 1];
					high[index] += high[index + 1] + (sum >>> 53);
					low[index] = sum & ExactDyadicCosts.MAX_DIGIT;
				}
				else
					high[index] += high[index + 1];
			}
			if(counters != null)
				counters.certifiedCostBuckets++;
		}

		private boolean exceeds(double high, double low, int next, double bestHigh, double bestLow) {
			if(next >= suffixHigh.length - 1 || !Double.isFinite(bestHigh))
				return false;
			double boundHigh;
			double boundLow;
			if(dyadic) {
				long sum = (long)low + (long)suffixLow[next];
				boundHigh = high + suffixHigh[next] + (sum >>> 53);
				boundLow = sum & ExactDyadicCosts.MAX_DIGIT;
			}
			else {
				// The 53-bit lattice certificate makes this addition exact and finite.
				boundHigh = high + suffixHigh[next];
				boundLow = 0d;
			}
			if(compareWords(boundHigh, boundLow, bestHigh, bestLow) <= 0)
				return false;
			if(counters != null) {
				counters.costCuts++;
				if(compareWords(high, low, bestHigh, bestLow) <= 0)
					counters.suffixCostCuts++;
			}
			return true;
		}
	}

	private static CostPruning boundaryCostPruning(List<BoundaryMessage> inputs,
		CostPruningMode mode, BoundaryMergeCounters counters, boolean certified) {
		if(mode == CostPruningMode.LEGACY || inputs.size() < 2)
			return null;
		if(!certified) {
			if(counters != null)
				counters.uncertifiedCostBuckets++;
			return null;
		}
		long start = counters == null ? 0L : System.nanoTime();
		final double[] minima;
		try {
			minima = PlannerResourceGuard.allocateDoubles(inputs.size() + 1, "regional-cost-bounds");
		}
		catch(PlannerResourceGuard.ResourceExhaustedException unavailable) {
			// Only the optional bound allocation may be omitted. Exact message
			// allocations and arithmetic still follow their original failure contract.
			if(counters != null) {
				counters.resourceSkippedCostBuckets++;
				counters.boundPreparationNanos += System.nanoTime() - start;
			}
			return null;
		}
		if(mode == CostPruningMode.SUFFIX)
			for(int index = 0; index < inputs.size(); index++) {
				double minimum = inputs.get(index).cachedMinimum.high;
				// No finite completion: zero is still a safe lower bound. Keep normal
				// infeasibility handling, and never cast infinity to an integer word.
				minima[index] = Double.isFinite(minimum) ? minimum : 0d;
			}
		CostPruning result = new CostPruning(minima, null, false, counters);
		if(counters != null)
			counters.boundPreparationNanos += System.nanoTime() - start;
		return result;
	}

	/** Called only after materialization has performed every original cost callback. */
	private static boolean exactNonnegativeGlobalSum(List<DenseFactor> inputs) {
		List<double[]> tables = new ArrayList<>(inputs.size());
		for(DenseFactor input : inputs) {
			if(input.tieCosts != null || input.lowValues != null)
				return false;
			// Conditional hard relations also contain only zero/infinity and have no
			// numeric table. Certify their monetary factors without unfolding support.
			if(input.hardValues == null && input.conditionalSupport == null)
				tables.add(input.values);
		}
		ExactDyadicCosts.Certificate proof = ExactDyadicCosts.certifyTables(tables);
		return proof.supported() && proof.maximumSumBits() <= 53;
	}

	private static CostPruning globalCostPruning(List<DenseFactor> inputs, boolean dyadic,
		CostPruningMode mode, BoundaryMergeCounters counters, boolean certified) {
		if(mode == CostPruningMode.LEGACY || inputs.size() < 2)
			return null;
		if(!certified) {
			if(counters != null)
				counters.uncertifiedCostBuckets++;
			return null;
		}
		long start = counters == null ? 0L : System.nanoTime();
		final double[] high;
		final double[] low;
		try {
			high = PlannerResourceGuard.allocateDoubles(inputs.size() + 1, "exact-cost-bounds");
			low = dyadic
				? PlannerResourceGuard.allocateDoubles(inputs.size() + 1, "exact-cost-bound-words") : null;
		}
		catch(PlannerResourceGuard.ResourceExhaustedException unavailable) {
			// Losing an optional bound must not prevent the original exact sum.
			// Keep validation and all essential storage failures outside this catch.
			if(counters != null) {
				counters.resourceSkippedCostBuckets++;
				counters.boundPreparationNanos += System.nanoTime() - start;
			}
			return null;
		}
		if(mode == CostPruningMode.SUFFIX)
			for(int index = 0; index < inputs.size(); index++) {
				DenseFactor input = inputs.get(index);
				if(input.hardValues != null || input.conditionalSupport != null)
					continue; // Typed hard costs are zero or forbidden, including sparse holes.
				double minimumHigh = Double.POSITIVE_INFINITY;
				double minimumLow = 0d;
				for(int stored = 0; stored < input.values.length; stored++) {
					double candidateHigh = input.values[stored];
					double candidateLow = input.lowValues == null ? 0d : input.lowValues[stored];
					if(compareWords(candidateHigh, candidateLow, minimumHigh, minimumLow) < 0) {
						minimumHigh = candidateHigh;
						minimumLow = candidateLow;
					}
				}
				if(Double.isFinite(minimumHigh)) {
					high[index] = minimumHigh;
					if(dyadic)
						low[index] = minimumLow;
				}
				if(counters != null)
					counters.boundCellsExamined += input.values.length;
			}
		CostPruning result = new CostPruning(high, low, dyadic, counters);
		if(counters != null)
			counters.boundPreparationNanos += System.nanoTime() - start;
		return result;
	}

	static final class BoundaryMergeCounters {
		private long fullChildEvaluations;
		private long childEvaluations;
		private long infeasibleCuts;
		private long costCuts;
		private long supportCellsExamined;
		private long suffixCostCuts;
		private long certifiedCostBuckets;
		private long uncertifiedCostBuckets;
		private long resourceSkippedCostBuckets;
		private long boundCellsExamined;
		private long boundPreparationNanos;

		long fullChildEvaluations() { return fullChildEvaluations; }
		long childEvaluations() { return childEvaluations; }
		long infeasibleCuts() { return infeasibleCuts; }
		long costCuts() { return costCuts; }
		long supportCellsExamined() { return supportCellsExamined; }
		long suffixCostCuts() { return suffixCostCuts; }
		long certifiedCostBuckets() { return certifiedCostBuckets; }
		long uncertifiedCostBuckets() { return uncertifiedCostBuckets; }
		long resourceSkippedCostBuckets() { return resourceSkippedCostBuckets; }
		long boundCellsExamined() { return boundCellsExamined; }
		long boundPreparationNanos() { return boundPreparationNanos; }
	}

	/** Both exact cost and lower bound are absorbing; later nonnegative terms cannot change either. */
	static boolean absorbingBoundaryInfinity(double exactCost, double lowerBound) {
		return exactCost == Double.POSITIVE_INFINITY
			&& lowerBound == Double.POSITIVE_INFINITY;
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
		return solve(prepared, factors, tieCostFunction, observer, dyadic, defaultCostPruningMode(), null);
	}

	static Result solveWithPruningForTest(CompiledProblem compiled, TieCostFunction tieCostFunction,
		ExactDyadicCosts.Certificate dyadic, CostPruningMode mode, BoundaryMergeCounters counters,
		Consumer<EliminationSnapshot> observer) {
		return solve(compiled.prepared, compiled.factors, tieCostFunction, observer, dyadic, mode, counters);
	}

	private static Result solve(Prepared prepared, List<Factor> factors,
		TieCostFunction tieCostFunction, Consumer<EliminationSnapshot> observer,
		ExactDyadicCosts.Certificate dyadic, CostPruningMode mode, BoundaryMergeCounters counters) {
		// Only the two no-callback entry points pass null. Caller callbacks remain
		// observable even for infeasible candidates and therefore retain dense visits.
		List<DenseFactor> active = materializeInputs(prepared, factors);
		if(dyadic != null)
			active = dyadicInputs(prepared.domains, active, dyadic);
		// Every replacement owns disjoint original factors. An exact whole-input
		// bound therefore also certifies each later bucket and its suffix minima.
		boolean certifiedCosts = mode != CostPruningMode.LEGACY && tieCostFunction == null
			&& (dyadic != null || exactNonnegativeGlobalSum(active));
		boolean sparseEligible = tieCostFunction == null
			&& (dyadic != null || sparseRangeSafe(prepared, active));
		long storedCells = active.stream().mapToLong(DenseFactor::storedCells).sum();
		long maximumStoredCells = active.stream().mapToLong(DenseFactor::storedCells)
			.max().orElse(0L);
		List<Backpointer> backpointers = new ArrayList<>(prepared.variables.size());
		int[] global = new int[prepared.variables.size()];

		for(Step step : prepared.steps) {
			List<DenseFactor> bucket = new ArrayList<>();
			for(DenseFactor factor : active)
				if(factor.contains(step.variable))
					bucket.add(factor);
			active.removeAll(bucket);
			CostPruning costPruning = globalCostPruning(bucket, dyadic != null, mode, counters, certifiedCosts);
			long logicalOutputCells = prepared.deferredDyadic
				? saturatedCells(step.separator, prepared.domains)
				: checkedCells(step.separator, prepared.domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
			if(counters != null)
				counters.fullChildEvaluations += saturatedMultiply(saturatedMultiply(logicalOutputCells,
					prepared.domains[step.variable]), bucket.size());
			if(sparseEligible) {
				BucketProjection projection = new BucketProjection(step, prepared.domains, bucket);
				List<ExactFiniteSupportJoin.SupportRelation> supports = new ArrayList<>();
				boolean[] knownZeroFactors = dyadic == null ? new boolean[bucket.size()] : null;
				boolean anyKnownZeroFactor = false;
				for(int index = 0; index < bucket.size(); index++) {
					DenseFactor factor = bucket.get(index);
					ExactFiniteSupportJoin.SupportRelation support = factor.projectedSupport(projection);
					if(support != null)
						supports.add(support);
					if(dyadic == null) {
						knownZeroFactors[index] = (support != null || factor.allFiniteDense())
							&& factor.finiteValuesAreExactPositiveZero();
						anyKnownZeroFactor |= knownZeroFactors[index];
					}
				}
				if(!projection.identity || !supports.isEmpty()
					|| bucket.stream().anyMatch(factor -> factor.valueMaps != null)) {
					int[] knownZeroTokens = anyKnownZeroFactor
						? knownZeroFactorTokens(knownZeroFactors) : null;
					StorageBudget storageBudget = prepared.deferredDyadic
						? new StorageBudget(prepared.limits, storedCells) : null;
					SparseStep sparse = eliminateSparse(step, bucket, supports, projection,
						knownZeroTokens,
						logicalOutputCells, dyadic != null, storageBudget, costPruning, counters);
					if(prepared.deferredDyadic) {
						storedCells = checkedAdd(storedCells, sparse.factor.values.length,
							"EXACT_VE_MATERIALIZED_CELL_OVERFLOW");
						maximumStoredCells = Math.max(maximumStoredCells, sparse.factor.values.length);
					}
					active.add(sparse.factor);
					backpointers.add(sparse.backpointer);
					if(observer != null && logicalOutputCells > Integer.MAX_VALUE)
						throw new IllegalArgumentException("EXACT_VE_OBSERVER_FACTOR_CELL_OVERFLOW");
					observeStep(observer, sparse.factor, sparse.backpointer,
						(int)logicalOutputCells, prepared.domains);
					continue;
				}
			}
			if(logicalOutputCells > Integer.MAX_VALUE)
				throw new IllegalArgumentException("EXACT_VE_FACTOR_CELL_OVERFLOW");
			int outputCells = (int)logicalOutputCells;
			if(prepared.deferredDyadic) {
				long maximumOutputCells = maximumOutputCells(prepared, storedCells);
				if(outputCells > maximumOutputCells)
					throw storedCellLimit(prepared, outputCells, storedCells);
				storedCells = checkedAdd(storedCells, outputCells,
					"EXACT_VE_MATERIALIZED_CELL_OVERFLOW");
				maximumStoredCells = Math.max(maximumStoredCells, outputCells);
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
			PlannerResourceGuard.checkAdditionalBytes((long)outputCells * (Double.BYTES + Integer.BYTES),
				"exact-elimination-output");
			double[] output = PlannerResourceGuard.allocateDoubles(outputCells, "exact-numeric");
			double[] outputLow = null;
			long[] outputTie = null;
			int[] choices = PlannerResourceGuard.allocateInts(outputCells, "exact-backpointer");
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
					PreciseCost candidate = dyadic == null
						? preciseSum(bucket, global, baseCells, valueStrides, value, costPruning, best, counters)
						: dyadicSum(bucket, global, baseCells, valueStrides, value, costPruning, best, counters);
					if(candidate == null)
						continue; // Certified dominated, not an infeasible boundary cell.
					candidate = candidate.plusTie(tieCost);
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
					if(outputLow == null) {
						PlannerResourceGuard.checkAdditionalCells(outputCells, "exact-elimination-low");
						outputLow = PlannerResourceGuard.allocateDoubles(outputCells, "exact-numeric");
					}
					outputLow[cell] = best.low;
				}
				if(best.tieCost != 0L) {
					if(outputTie == null) {
						PlannerResourceGuard.checkAdditionalCells(outputCells, "exact-elimination-tie");
						outputTie = PlannerResourceGuard.allocateLongs(outputCells, "exact-tie");
					}
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
		Statistics statistics = prepared.deferredDyadic
			? new Statistics(prepared.statistics.eliminationOrder(), prepared.statistics.inducedWidth(),
				maximumStoredCells, storedCells, prepared.statistics.maximumEliminationAssignments(),
				prepared.statistics.eliminationAssignments())
			: prepared.statistics;
		return new Result(objective, assignment, statistics);
	}

	private static long maximumOutputCells(Prepared prepared, long storedCells) {
		long remaining = prepared.limits.maximumMaterializedCells() - storedCells;
		if(remaining < 0)
			throw new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells="
				+ storedCells + "|limit=" + prepared.limits.maximumMaterializedCells());
		return Math.min(prepared.limits.maximumFactorCells(), remaining);
	}

	private static IllegalArgumentException storedCellLimit(Prepared prepared,
		long outputCells, long storedCells) {
		if(outputCells > prepared.limits.maximumFactorCells())
			return new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells="
				+ outputCells + "|limit=" + prepared.limits.maximumFactorCells() + "|stored");
		return new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells="
			+ saturatedAdd(storedCells, outputCells) + "|limit="
			+ prepared.limits.maximumMaterializedCells());
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
			// Conditional support contains only +0/+INF. Its finite cardinality
			// is not needed by the relation-native sparse join, so keep it deferred.
			if(factor.conditionalSupport != null)
				continue;
			if(factor.hardValues != null) {
				factor.finiteCount = factor.hardValues.finiteCount();
				continue;
			}
			int finiteCount = 0;
			// Missing sparse cells are implicit +INF and cannot affect this bound.
			// Scanning logical cells here would re-expand an already sparse input.
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
	private record StorageBudget(Limits limits, long storedBefore) {
		private long maximumOutputCells() {
			return Math.min(limits.maximumFactorCells(),
				Math.max(0L, limits.maximumMaterializedCells() - storedBefore));
		}
		private IllegalArgumentException exceeded(long outputCells) {
			if(outputCells > limits.maximumFactorCells())
				return new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells="
					+ outputCells + "|limit=" + limits.maximumFactorCells() + "|stored");
			return new IllegalArgumentException("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells="
				+ saturatedAdd(storedBefore, outputCells) + "|limit="
				+ limits.maximumMaterializedCells());
		}
	}

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
			for(int stored = factor.nextFiniteStoredCell(-1); stored >= 0;
				stored = factor.nextFiniteStoredCell(stored)) {
				int cell = factor.storedLogicalCell(stored);
				long multiplicity = 1L;
				for(int axis = 0; axis < factor.scope.length; axis++)
					multiplicity = saturatedMultiply(multiplicity, weights[factor.scope[axis]][
						cell / factor.strides[axis] % factor.dimensions[axis]]);
				count = saturatedAdd(count, multiplicity);
			}
			return count;
		}
	}

	/** Retains all minima, switching only their storage when a message is not sparse. */
	private static final class SparseAccumulator {
		private final int outputCells;
		private final boolean dyadic;
		private final StorageBudget storageBudget;
		private Map<Integer,SparseMinimum> minima = new HashMap<>();
		private double[] high;
		private double[] low;
		private int[] choices;
		private int finiteOutputs;

		private SparseAccumulator(int outputCells, boolean dyadic, StorageBudget storageBudget) {
			this.outputCells = outputCells;
			this.dyadic = dyadic;
			this.storageBudget = storageBudget;
		}

		private PreciseCost best(int cell) {
			if(high != null)
				return new PreciseCost(high[cell], low == null ? 0d : low[cell], 0L);
			SparseMinimum prior = minima.get(cell);
			return prior == null ? PreciseCost.POSITIVE_INFINITY : prior.cost;
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
			long maximumStoredCells = storageBudget == null
				? Long.MAX_VALUE : storageBudget.maximumOutputCells();
			if(prior == null && minima.size() >= maximumStoredCells)
				throw storageBudget.exceeded((long)minima.size() + 1L);
			int comparison = prior == null ? -1 : dyadic
				? compareWords(candidate.high, candidate.low, prior.cost.high, prior.cost.low)
				: Double.compare(rounded, prior.rounded);
			if(comparison < 0 || comparison == 0 && value < prior.choice)
				minima.put(cell, new SparseMinimum(candidate, rounded, value));
			// This is a representation crossover, never a search/candidate limit.
			// Boxed hash entries cost much more than a dense high/low/choice slot.
			if(outputCells <= maximumStoredCells
				&& minima.size() >= Math.max(64, outputCells / 16)) {
				PlannerResourceGuard.checkAdditionalBytes((long)outputCells * (Double.BYTES + Integer.BYTES),
					"exact-sparse-dense-conversion");
				high = PlannerResourceGuard.allocateDoubles(outputCells, "exact-numeric");
				Arrays.fill(high, Double.POSITIVE_INFINITY);
				choices = PlannerResourceGuard.allocateInts(outputCells, "exact-backpointer");
				for(Map.Entry<Integer,SparseMinimum> entry : minima.entrySet())
					store(entry.getKey(), entry.getValue().cost, entry.getValue().choice);
				finiteOutputs = minima.size();
				minima = null;
			}
		}

		private void store(int cell, PreciseCost cost, int value) {
			high[cell] = cost.high;
			if(low == null && cost.low != 0d) {
				PlannerResourceGuard.checkAdditionalCells(high.length, "exact-sparse-low");
				low = PlannerResourceGuard.allocateDoubles(high.length, "exact-numeric");
			}
			if(low != null)
				low[cell] = cost.low != 0d ? cost.low : 0d;
			choices[cell] = value;
		}

		private SparseStep finish(Step step, int[] domains) {
			int[] sparseCells = null;
			if(high == null) {
				PlannerResourceGuard.checkAdditionalBytes((long)minima.size() * (Double.BYTES + 2L * Integer.BYTES),
					"exact-sparse-output");
				int[] cells = PlannerResourceGuard.allocateInts(minima.size(), "exact-sparse-output-keys");
				int output = 0;
				for(int cell : minima.keySet())
					cells[output++] = cell;
				Arrays.sort(cells);
				high = PlannerResourceGuard.allocateDoubles(cells.length, "exact-numeric");
				choices = PlannerResourceGuard.allocateInts(cells.length, "exact-backpointer");
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
		List<ExactFiniteSupportJoin.SupportRelation> supports, BucketProjection projection,
		int[] knownZeroTokens, long logicalOutputCells, boolean dyadic,
		StorageBudget storageBudget) {
		return eliminateSparse(step, bucket, supports, projection, knownZeroTokens,
			logicalOutputCells, dyadic, storageBudget, null, null);
	}

	private static SparseStep eliminateSparse(Step step, List<DenseFactor> bucket,
		List<ExactFiniteSupportJoin.SupportRelation> supports, BucketProjection projection,
		int[] knownZeroTokens, long logicalOutputCells, boolean dyadic,
		StorageBudget storageBudget, CostPruning costPruning, BoundaryMergeCounters counters) {
		int[] domains = projection.domains;
		int outputCells = checkedCells(step.separator, domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
		long supportRows = 0L;
		for(ExactFiniteSupportJoin.SupportRelation relation : supports)
			supportRows += relation.size();
		long started = FederatedPlannerTrace.isEnabled() ? System.nanoTime() : 0L;
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Exact-SparseJoinBegin", "variableIndex=" + step.variable
				+ " logicalAssignments=" + saturatedMultiply(
					logicalOutputCells, projection.originalDomains[step.variable])
				+ " outputCells=" + logicalOutputCells + " supportRows=" + supportRows
				+ " quotientAssignments=" + (long)outputCells * domains[step.variable]
				+ " quotientOutputCells=" + outputCells
				+ " partitionNanos=" + projection.preparationNanos);
		SparseAccumulator accumulator = new SparseAccumulator(outputCells, dyadic, storageBudget);
		ExactFiniteSupportJoin.Work work;
		if(dyadic && supports.isEmpty())
			work = eliminateDyadicSeparatorMajor(step, bucket, projection, outputCells, accumulator,
				costPruning, counters);
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
				int cell = encode(step.separator, domains, assignment);
				PreciseCost best = costPruning == null ? PreciseCost.POSITIVE_INFINITY : accumulator.best(cell);
				PreciseCost candidate = dyadic
					? dyadicSum(bucket, selected, null, null, 0, costPruning, best, counters)
					: knownZeroTokens == null
						? preciseSum(bucket, selected, null, null, 0, costPruning, best, counters)
						: preciseSumKnownZeros(bucket, selected, knownZeroTokens, costPruning, best, counters);
				if(candidate != null)
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
		SparseAccumulator accumulator, CostPruning costPruning, BoundaryMergeCounters counters) {
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
					zeroCoordinates, baseCells, representative, costPruning, best, counters);
				if(candidate == null)
					continue;
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
		int[] strides, int[] zeroCoordinates, int[] baseCells, int representative,
		CostPruning costPruning, PreciseCost best, BoundaryMergeCounters counters) {
		final long mask = (1L << 53) - 1;
		long high = 0L;
		long low = 0L;
		for(int index = 0; index < bucket.size(); index++) {
			DenseFactor factor = bucket.get(index);
			int coordinate = factor.coordinate(axes[index], representative);
			int logicalCell = baseCells[index]
				+ (coordinate - zeroCoordinates[index]) * strides[index];
			int cell = factor.storageCell(logicalCell);
			if(counters != null)
				counters.childEvaluations++;
			if(cell < 0 || factor.valueAt(logicalCell) == Double.POSITIVE_INFINITY)
				return PreciseCost.POSITIVE_INFINITY;
			long lowSum = low + (factor.lowValues == null ? 0L : (long)factor.lowValues[cell]);
			high += (long)factor.valueAt(logicalCell) + (lowSum >>> 53);
			if(high > mask)
				throw new IllegalArgumentException("EXACT_VE_DYADIC_WORD_OVERFLOW");
			low = lowSum & mask;
			if(costPruning != null && costPruning.exceeds(high, low, index + 1, best.high, best.low))
				return null;
		}
		return new PreciseCost(high, low, 0L);
	}

	private static void observeStep(Consumer<EliminationSnapshot> observer, DenseFactor factor,
		Backpointer backpointer, int outputCells, int[] domains) {
		if(observer == null)
			return;
		double[] high = PlannerResourceGuard.allocateDoubles(outputCells, "exact-numeric");
		double[] low = PlannerResourceGuard.allocateDoubles(outputCells, "exact-numeric");
		int[] choices = PlannerResourceGuard.allocateInts(outputCells, "exact-backpointer");
		int[] global = new int[domains.length];
		int[] local = new int[factor.scope.length];
		for(int cell = 0; cell < outputCells; cell++) {
			decode(cell, factor.scope, domains, local, global);
			int stored = factor.storageCell(factor.cell(global));
			high[cell] = stored < 0 ? Double.POSITIVE_INFINITY : factor.valueAt(factor.cell(global));
			low[cell] = stored < 0 || factor.lowValues == null ? 0d : factor.lowValues[stored];
			choices[cell] = backpointer.choice(backpointer.cell(global, domains));
		}
		observer.accept(new EliminationSnapshot(backpointer.variable, factor.scope.clone(),
			high, low, choices, factor.sparseCells != null, factor.storedCells()));
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
		return new Prepared(canonical, domains, scopes, plan.steps, statistics, limits, false);
	}

	private static Prepared prepareDeferredDyadic(InputDefinition input, Limits limits, Plan plan) {
		long totalCells = input.inputCells;
		long maximumCells = input.maximumInputCells;
		long maximumAssignments = 0L;
		long assignments = 0L;
		for(Step step : plan.steps) {
			long cells = saturatedCells(step.separator, input.domains);
			maximumCells = Math.max(maximumCells, cells);
			totalCells = saturatedAdd(totalCells, cells);
			long stepAssignments = saturatedMultiply(cells, input.domains[step.variable]);
			maximumAssignments = Math.max(maximumAssignments, stepAssignments);
			assignments = saturatedAdd(assignments, stepAssignments);
		}
		Statistics estimates = new Statistics(plan.steps.stream()
			.map(step -> input.variables.get(step.variable).key()).toList(), plan.inducedWidth,
			maximumCells, totalCells, maximumAssignments, assignments);
		return new Prepared(input.variables, input.domains, input.scopes, plan.steps,
			estimates, limits, true);
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
			if(factor.denseValues != null && factor.denseValues.length != cells
				|| factor.hardValues != null && factor.hardValues.cells() != cells)
				throw new IllegalArgumentException("EXACT_VE_DENSE_FACTOR_SIZE_MISMATCH");
			FunctionalMap mapping = factor.functionalMapping();
			long storedCells = mapping != null ? mapping.finiteCount()
				: factor.evaluator instanceof FiniteSupport support ? support.finiteCells.length
				: factor.evaluator instanceof ConditionalSupport conditional
					? conditional.storedValues() : cells;
			inputCells = checkedAdd(inputCells, storedCells, "EXACT_VE_MATERIALIZED_CELL_OVERFLOW");
			maximumInputCells = Math.max(maximumInputCells, storedCells);
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
		return minimumMaterializationPlan(variables, domains, initialScopes, null, null,
			Long.MAX_VALUE);
	}

	private static Plan minimumMaterializationPlan(List<Variable> variables, int[] domains,
		List<int[]> initialScopes, ScoredPlan precomputed) {
		return minimumMaterializationPlan(variables,domains,initialScopes,precomputed,null,
			Long.MAX_VALUE);
	}

	private static Plan minimumMaterializationPlan(List<Variable> variables, int[] domains,
		List<int[]> initialScopes, ScoredPlan precomputed, Limits limits,
		long maximumEliminationAssignments) {
		List<PlanOrdering> orderings = List.of(
			PlanOrdering.MIN_FILL,
			PlanOrdering.MIN_SEPARATOR_CELLS,
			PlanOrdering.MIN_ELIMINATION_ASSIGNMENTS,
			PlanOrdering.MIN_DEGREE);
		StringBuilder diagnostic = FederatedPlannerTrace.isEnabled() ? new StringBuilder() : null;
		InputDefinition boundedInput = limits == null ? null : new InputDefinition(variables,domains,
			initialScopes,inputCells(initialScopes,domains),maximumInputCells(initialScopes,domains));
		ScoredPlan best = null;
		ScoredPlan ordinaryBest = null;
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
			if(ordinaryBest == null || compare(candidate,ordinaryBest) < 0)
				ordinaryBest = candidate;
			boolean eligible = candidate.metrics.maximumEliminationAssignments
				<= maximumEliminationAssignments
				&& (limits == null || planFitsLimits(boundedInput,candidate.metrics,limits));
			if(eligible && (best == null || compare(candidate, best) < 0))
				best = candidate;
		}
		if(best == null) {
			Objects.requireNonNull(ordinaryBest,"best elimination plan");
			if(limits != null && !planFitsLimits(boundedInput,ordinaryBest.metrics,limits))
				prepare(boundedInput,limits,ordinaryBest.plan);
			throw new IllegalArgumentException("EXACT_VE_ASSIGNMENT_LIMIT_EXCEEDED"
				+ "|assignments=" + ordinaryBest.metrics.maximumEliminationAssignments
				+ "|limit=" + maximumEliminationAssignments);
		}
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

	private static long inputCells(List<int[]> scopes, int[] domains) {
		long cells = 0L;
		for(int[] scope : scopes)
			cells = saturatedAdd(cells,saturatedCells(scope,domains));
		return cells;
	}

	private static long maximumInputCells(List<int[]> scopes, int[] domains) {
		long maximum = 0L;
		for(int[] scope : scopes)
			maximum = Math.max(maximum,saturatedCells(scope,domains));
		return maximum;
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
		return eliminationPlan(variables,domains,initialScopes,ordering,null);
	}

	private static Plan eliminationPlan(List<Variable> variables, int[] domains,
		List<int[]> initialScopes, PlanOrdering ordering, EliminationScoreCounters counters) {
		EliminationGraph graph = interactionGraph(variables.size(), initialScopes);
		List<Step> steps = new ArrayList<>(variables.size());
		OrderScoreCache scores = new OrderScoreCache(graph,domains,variables.size(),counters);
		int width = 0;
		while(graph.hasRemaining()) {
			int selected = -1;
			for(int variable = graph.nextRemaining(0); variable >= 0;
				variable = graph.nextRemaining(variable + 1))
				if(selected < 0 || compareEliminationScores(variable,selected,
					variables,ordering,scores) < 0)
					selected = variable;
			if(selected < 0)
				throw new IllegalStateException("EXACT_VE_ORDER_SELECTION_EMPTY");
			int[] separator = graph.remainingNeighbors(selected);
			width = Math.max(width, separator.length);
			scores.invalidateAfterElimination(separator);
			graph.connectClique(separator);
			graph.removeRemaining(selected);
			EliminationGraph adapted = densifyIfBeneficial(graph);
			if(adapted != graph) {
				graph = adapted;
				scores.setGraph(graph);
			}
			steps.add(new Step(selected, separator));
		}
		return new Plan(List.copyOf(steps), width);
	}

	private static int compareEliminationScores(int left, int right,
		List<Variable> variables, PlanOrdering ordering, OrderScoreCache scores) {
		int comparison;
		switch(ordering) {
			case MIN_FILL:
				comparison = Long.compare(scores.fillEdges(left),scores.fillEdges(right));
				if(comparison == 0)
					comparison = Long.compare(scores.neighborCells(left),scores.neighborCells(right));
				break;
			case MIN_SEPARATOR_CELLS:
				comparison = Long.compare(scores.neighborCells(left),scores.neighborCells(right));
				if(comparison == 0)
					comparison = Long.compare(scores.fillEdges(left),scores.fillEdges(right));
				break;
			case MIN_ELIMINATION_ASSIGNMENTS:
				comparison = Long.compare(scores.eliminationAssignments(left),
					scores.eliminationAssignments(right));
				if(comparison == 0)
					comparison = Long.compare(scores.neighborCells(left),scores.neighborCells(right));
				if(comparison == 0)
					comparison = Long.compare(scores.fillEdges(left),scores.fillEdges(right));
				break;
			case MIN_DEGREE:
				comparison = Long.compare(scores.remainingDegree(left),scores.remainingDegree(right));
				if(comparison == 0)
					comparison = Long.compare(scores.neighborCells(left),scores.neighborCells(right));
				if(comparison == 0)
					comparison = Long.compare(scores.fillEdges(left),scores.fillEdges(right));
				break;
			default:
				throw new IllegalStateException("Unknown elimination ordering " + ordering);
		}
		return comparison != 0 ? comparison
			: variables.get(left).key().compareTo(variables.get(right).key());
	}

	static EliminationOrderScoreResult eliminationOrderScoreForTest(List<Variable> variables,
		int[] domains, List<int[]> initialScopes, String ordering) {
		EliminationScoreCounters counters = new EliminationScoreCounters();
		Plan plan = eliminationPlan(variables,domains,initialScopes,
			PlanOrdering.valueOf(ordering),counters);
		PlanMetrics metrics = planMetrics(plan,domains);
		Statistics statistics = new Statistics(plan.steps.stream()
			.map(step -> variables.get(step.variable).key()).toList(), plan.inducedWidth,
			metrics.maximumFactorCells, metrics.materializedFactorCells,
			metrics.maximumEliminationAssignments, metrics.eliminationAssignments);
		List<List<String>> separators = plan.steps.stream().map(step -> Arrays.stream(step.separator)
			.mapToObj(index -> variables.get(index).key()).toList()).toList();
		return new EliminationOrderScoreResult(statistics,separators,counters.fillComputations,
			counters.neighborCellComputations,counters.degreeComputations);
	}

	static Statistics eliminationPortfolioScoreForTest(List<Variable> variables,
		int[] domains, List<int[]> initialScopes) {
		Plan plan = minimumMaterializationPlan(variables,domains,initialScopes);
		PlanMetrics metrics = planMetrics(plan,domains);
		return new Statistics(plan.steps.stream().map(step -> variables.get(step.variable).key()).toList(),
			plan.inducedWidth, metrics.maximumFactorCells, metrics.materializedFactorCells,
			metrics.maximumEliminationAssignments, metrics.eliminationAssignments);
	}

	private static Plan eliminationPlan(List<Variable> variables,
		List<int[]> initialScopes, int[] order) {
		EliminationGraph graph = interactionGraph(variables.size(), initialScopes);
		List<Step> steps = new ArrayList<>(variables.size());
		int width = 0;
		for(int selected : order) {
			if(!graph.removeRemaining(selected))
				throw new IllegalArgumentException("EXACT_VE_PREFERRED_ORDER_INVALID");
			int[] separator = graph.remainingNeighbors(selected);
			width = Math.max(width, separator.length);
			graph.connectClique(separator);
			graph = densifyIfBeneficial(graph);
			steps.add(new Step(selected, separator));
		}
		if(graph.hasRemaining())
			throw new IllegalArgumentException("EXACT_VE_PREFERRED_ORDER_INVALID");
		return new Plan(List.copyOf(steps), width);
	}

	private static EliminationGraph interactionGraph(int variableCount,
		List<int[]> initialScopes) {
		SparseEliminationGraph sparse = new SparseEliminationGraph(variableCount);
		for(int[] scope : initialScopes)
			for(int i = 0; i < scope.length; i++)
				for(int j = i + 1; j < scope.length; j++)
					sparse.connect(scope[i],scope[j]);
		return densifyIfBeneficial(sparse);
	}

	private static EliminationGraph densifyIfBeneficial(EliminationGraph graph) {
		if(!(graph instanceof SparseEliminationGraph sparse))
			return graph;
		long denseWords = (long)sparse.variableCount
			* ((sparse.variableCount + (long)Long.SIZE - 1) / Long.SIZE);
		return denseWords <= sparse.adjacencyEntries() / 2L
			? new DenseEliminationGraph(sparse) : sparse;
	}

	private interface EliminationGraph {
		boolean hasRemaining();
		int nextRemaining(int from);
		boolean removeRemaining(int variable);
		int[] remainingNeighbors(int variable);
		void connectClique(int[] variables);
		long fillEdges(int variable);
		long neighborCells(int variable, int[] domains);
		long remainingDegree(int variable);
	}

	private static final class DenseEliminationGraph implements EliminationGraph {
		private final int variableCount;
		private final int wordCount;
		private final long[][] adjacency;
		private final long[] remaining;
		private final long[] cliqueBuffer;

		private DenseEliminationGraph(SparseEliminationGraph sparse) {
			this(sparse.variableCount);
			System.arraycopy(sparse.remaining,0,remaining,0,remaining.length);
			for(int left = 0; left < variableCount; left++) {
				Set<Integer> neighbors = sparse.adjacency[left];
				if(neighbors != null)
					for(int right : neighbors)
						connect(left,right);
			}
		}

		private DenseEliminationGraph(int variableCount) {
			this.variableCount = variableCount;
			wordCount = (variableCount + Long.SIZE - 1) / Long.SIZE;
			adjacency = new long[variableCount][wordCount];
			remaining = new long[wordCount];
			Arrays.fill(remaining,-1L);
			if(wordCount > 0 && (variableCount & (Long.SIZE - 1)) != 0)
				remaining[wordCount - 1] = (1L << (variableCount & (Long.SIZE - 1))) - 1L;
			cliqueBuffer = new long[wordCount];
		}

		private void connect(int left, int right) {
			adjacency[left][right >>> 6] |= 1L << right;
			adjacency[right][left >>> 6] |= 1L << left;
		}

		@Override
		public boolean hasRemaining() {
			for(long word : remaining)
				if(word != 0L)
					return true;
			return false;
		}

		@Override
		public int nextRemaining(int from) {
			if(from < 0 || from >= variableCount)
				return -1;
			int word = from >>> 6;
			long candidates = remaining[word] & (-1L << from);
			while(true) {
				if(candidates != 0L)
					return (word << 6) + Long.numberOfTrailingZeros(candidates);
				if(++word >= wordCount)
					return -1;
				candidates = remaining[word];
			}
		}

		@Override
		public boolean removeRemaining(int variable) {
			long mask = 1L << variable;
			int word = variable >>> 6;
			if((remaining[word] & mask) == 0L)
				return false;
			remaining[word] &= ~mask;
			return true;
		}

		@Override
		public int[] remainingNeighbors(int variable) {
			int count = 0;
			for(int word = 0; word < wordCount; word++)
				count += Long.bitCount(adjacency[variable][word] & remaining[word]);
			int[] neighbors = new int[count];
			int offset = 0;
			for(int word = 0; word < wordCount; word++) {
				long candidates = adjacency[variable][word] & remaining[word];
				while(candidates != 0L) {
					int bit = Long.numberOfTrailingZeros(candidates);
					neighbors[offset++] = (word << 6) + bit;
					candidates &= candidates - 1L;
				}
			}
			return neighbors;
		}

		@Override
		public void connectClique(int[] variables) {
			Arrays.fill(cliqueBuffer,0L);
			for(int variable : variables)
				cliqueBuffer[variable >>> 6] |= 1L << variable;
			for(int variable : variables) {
				boolean selfConnected = connected(variable,variable);
				for(int word = 0; word < wordCount; word++)
					adjacency[variable][word] |= cliqueBuffer[word];
				if(!selfConnected)
					adjacency[variable][variable >>> 6] &= ~(1L << variable);
			}
		}

		private boolean connected(int left, int right) {
			return (adjacency[left][right >>> 6] & (1L << right)) != 0L;
		}

		@Override
		public long fillEdges(int variable) {
			long missing = 0L;
			for(int neighborWord = 0; neighborWord < wordCount; neighborWord++) {
				long neighbors = adjacency[variable][neighborWord] & remaining[neighborWord];
				while(neighbors != 0L) {
					int bit = Long.numberOfTrailingZeros(neighbors);
					int neighbor = (neighborWord << 6) + bit;
					long higherInWord = bit == Long.SIZE - 1 ? 0L : -1L << (bit + 1);
					missing += Long.bitCount(adjacency[variable][neighborWord]
						& remaining[neighborWord] & higherInWord & ~adjacency[neighbor][neighborWord]);
					for(int word = neighborWord + 1; word < wordCount; word++)
						missing += Long.bitCount(adjacency[variable][word]
							& remaining[word] & ~adjacency[neighbor][word]);
					neighbors &= neighbors - 1L;
				}
			}
			return missing;
		}

		@Override
		public long neighborCells(int variable, int[] domains) {
			long cells = 1L;
			for(int word = 0; word < wordCount; word++) {
				long neighbors = adjacency[variable][word] & remaining[word];
				while(neighbors != 0L) {
					int neighbor = (word << 6) + Long.numberOfTrailingZeros(neighbors);
					if(cells > Long.MAX_VALUE / domains[neighbor])
						return Long.MAX_VALUE;
					cells *= domains[neighbor];
					neighbors &= neighbors - 1L;
				}
			}
			return cells;
		}

		@Override
		public long remainingDegree(int variable) {
			long degree = 0L;
			for(int word = 0; word < wordCount; word++)
				degree += Long.bitCount(adjacency[variable][word] & remaining[word]);
			return degree;
		}
	}

	private static final class SparseEliminationGraph implements EliminationGraph {
		private final int variableCount;
		private final Set<Integer>[] adjacency;
		private final Integer[] ordinals;
		private final long[] remaining;
		private final int[] neighborBuffer;
		private long adjacencyEntries;

		@SuppressWarnings("unchecked")
		private SparseEliminationGraph(int variableCount) {
			this.variableCount = variableCount;
			adjacency = (Set<Integer>[])new Set<?>[variableCount];
			ordinals = new Integer[variableCount];
			for(int variable = 0; variable < variableCount; variable++)
				ordinals[variable] = Integer.valueOf(variable);
			remaining = new long[(variableCount + Long.SIZE - 1) / Long.SIZE];
			Arrays.fill(remaining,-1L);
			if(remaining.length > 0 && (variableCount & (Long.SIZE - 1)) != 0)
				remaining[remaining.length - 1] =
					(1L << (variableCount & (Long.SIZE - 1))) - 1L;
			neighborBuffer = new int[variableCount];
		}

		private void connect(int left, int right) {
			if(adjacency[left] == null)
				adjacency[left] = new HashSet<>();
			if(adjacency[left].add(ordinals[right]))
				adjacencyEntries++;
			if(adjacency[right] == null)
				adjacency[right] = new HashSet<>();
			if(adjacency[right].add(ordinals[left]))
				adjacencyEntries++;
		}

		private long adjacencyEntries() {
			return adjacencyEntries;
		}

		@Override
		public boolean hasRemaining() {
			for(long word : remaining)
				if(word != 0L)
					return true;
			return false;
		}

		@Override
		public int nextRemaining(int from) {
			if(from < 0 || from >= variableCount)
				return -1;
			int word = from >>> 6;
			long candidates = remaining[word] & (-1L << from);
			while(true) {
				if(candidates != 0L)
					return (word << 6) + Long.numberOfTrailingZeros(candidates);
				if(++word >= remaining.length)
					return -1;
				candidates = remaining[word];
			}
		}

		@Override
		public boolean removeRemaining(int variable) {
			long mask = 1L << variable;
			int word = variable >>> 6;
			if((remaining[word] & mask) == 0L)
				return false;
			remaining[word] &= ~mask;
			return true;
		}

		private boolean isRemaining(int variable) {
			return (remaining[variable >>> 6] & (1L << variable)) != 0L;
		}

		@Override
		public int[] remainingNeighbors(int variable) {
			Set<Integer> row = adjacency[variable];
			if(row == null)
				return new int[0];
			int count = 0;
			for(int neighbor : row)
				if(isRemaining(neighbor))
					count++;
			int[] neighbors = new int[count];
			int offset = 0;
			for(int neighbor : row)
				if(isRemaining(neighbor))
					neighbors[offset++] = neighbor;
			Arrays.sort(neighbors);
			return neighbors;
		}

		@Override
		public void connectClique(int[] variables) {
			for(int i = 0; i < variables.length; i++)
				for(int j = i + 1; j < variables.length; j++)
					connect(variables[i],variables[j]);
		}

		@Override
		public long fillEdges(int variable) {
			Set<Integer> variableRow = adjacency[variable];
			if(variableRow == null)
				return 0L;
			int neighborCount = 0;
			for(int neighbor : variableRow)
				if(isRemaining(neighbor))
					neighborBuffer[neighborCount++] = neighbor;
			long missing = 0L;
			for(int i = 0; i < neighborCount; i++)
				for(int j = i + 1; j < neighborCount; j++) {
					Set<Integer> row = adjacency[neighborBuffer[i]];
					if(row == null || !row.contains(ordinals[neighborBuffer[j]]))
						missing++;
				}
			return missing;
		}

		@Override
		public long neighborCells(int variable, int[] domains) {
			long cells = 1L;
			Set<Integer> row = adjacency[variable];
			if(row == null)
				return cells;
			for(int neighbor : row) {
				if(!isRemaining(neighbor))
					continue;
				if(cells > Long.MAX_VALUE / domains[neighbor])
					return Long.MAX_VALUE;
				cells *= domains[neighbor];
			}
			return cells;
		}

		@Override
		public long remainingDegree(int variable) {
			long degree = 0L;
			Set<Integer> row = adjacency[variable];
			if(row != null)
				for(int neighbor : row)
					if(isRemaining(neighbor))
						degree++;
			return degree;
		}
	}

	private static final class OrderScoreCache {
		private EliminationGraph graph;
		private final int[] domains;
		private final EliminationScoreCounters counters;
		private final long[] fillEdges;
		private final long[] neighborCells;
		private final long[] remainingDegrees;
		private final boolean[] fillValid;
		private final boolean[] neighborValid;
		private final boolean[] degreeValid;

		private OrderScoreCache(EliminationGraph graph, int[] domains, int variableCount,
			EliminationScoreCounters counters) {
			this.graph = graph;
			this.domains = domains;
			this.counters = counters;
			fillEdges = new long[variableCount];
			neighborCells = new long[variableCount];
			remainingDegrees = new long[variableCount];
			fillValid = new boolean[variableCount];
			neighborValid = new boolean[variableCount];
			degreeValid = new boolean[variableCount];
		}

		private void setGraph(EliminationGraph graph) {
			this.graph = graph;
		}

		private void invalidateAfterElimination(int[] separator) {
			// Removing the selected variable and completing its separator changes degree and
			// neighbor cells only on the separator. A new separator edge can additionally
			// reduce fill for any common neighbor, so invalidate fill over the two-hop cone.
			for(int variable : separator) {
				fillValid[variable] = false;
				neighborValid[variable] = false;
				degreeValid[variable] = false;
				for(int neighbor : graph.remainingNeighbors(variable))
					fillValid[neighbor] = false;
			}
		}

		private long fillEdges(int variable) {
			if(fillValid[variable])
				return fillEdges[variable];
			fillEdges[variable] = graph.fillEdges(variable);
			fillValid[variable] = true;
			if(counters != null)
				counters.fillComputations++;
			return fillEdges[variable];
		}

		private long neighborCells(int variable) {
			if(neighborValid[variable])
				return neighborCells[variable];
			neighborCells[variable] = graph.neighborCells(variable,domains);
			neighborValid[variable] = true;
			if(counters != null)
				counters.neighborCellComputations++;
			return neighborCells[variable];
		}

		private long eliminationAssignments(int variable) {
			return saturatedMultiply(neighborCells(variable),domains[variable]);
		}

		private long remainingDegree(int variable) {
			if(degreeValid[variable])
				return remainingDegrees[variable];
			remainingDegrees[variable] = graph.remainingDegree(variable);
			degreeValid[variable] = true;
			if(counters != null)
				counters.degreeComputations++;
			return remainingDegrees[variable];
		}
	}

	private static final class EliminationScoreCounters {
		private long fillComputations;
		private long neighborCellComputations;
		private long degreeComputations;
	}

	private static long saturatedCells(int[] scope, int[] domains) {
		long cells = 1L;
		for(int variable : scope)
			cells = saturatedMultiply(cells, domains[variable]);
		return cells;
	}

	private static long saturatedMultiply(long left, long right) {
		if(left == 0L || right == 0L)
			return 0L;
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
			Factor frozen = freezeValidatedFactor(factor);
			if(frozen.evaluator instanceof FiniteSupport support) {
				double[] zeros = PlannerResourceGuard.allocateDoubles(
					support.finiteCells.length, "exact-sparse-input-values");
				result.add(new DenseFactor(scope, prepared.domains, zeros, null, null,
					support.finiteCells));
			}
			else if(frozen.evaluator instanceof ConditionalSupport support)
				result.add(new DenseFactor(scope, prepared.domains, support));
			else result.add(frozen.hardValues == null
				? new DenseFactor(scope, prepared.domains, frozen.denseValues, null, null)
				: new DenseFactor(scope, prepared.domains, frozen.hardValues));
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
			if(factor.conditionalSupport != null) {
				converted.add(factor);
				continue;
			}
			if(factor.hardValues != null) {
				converted.add(factor);
				continue;
			}
			PlannerResourceGuard.checkAdditionalCells(factor.values.length, "exact-dyadic-input");
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
					if(low == null) {
						PlannerResourceGuard.checkAdditionalCells(high.length, "exact-dyadic-low");
						low = PlannerResourceGuard.allocateDoubles(high.length, "exact-numeric");
					}
					low[cell] = exact.lowWord();
				}
				if(exact.compareTo(factorMaximum) > 0)
					factorMaximum = exact;
			}
			maximum = maximum.add(factorMaximum);
			converted.add(new DenseFactor(factor.scope, domains, high, low, null,
				factor.sparseCells));
		}
		if(maximum.bitLength() > certificate.maximumSumBits())
			throw new IllegalArgumentException("EXACT_VE_DYADIC_MAXIMUM_BOUND_MISMATCH");
		return converted;
	}

	/** Base-2^53 integer words, never double-double residues or individually rounded costs. */
	private static PreciseCost dyadicSum(List<DenseFactor> factors, int[] global,
		int[] baseCells, int[] valueStrides, int value) {
		return dyadicSum(factors, global, baseCells, valueStrides, value, null, null, null);
	}

	private static PreciseCost dyadicSum(List<DenseFactor> factors, int[] global,
		int[] baseCells, int[] valueStrides, int value, CostPruning costPruning,
		PreciseCost best, BoundaryMergeCounters counters) {
		final long mask = (1L << 53) - 1;
		long high = 0;
		long low = 0;
		for(int index = 0; index < factors.size(); index++) {
			DenseFactor factor = factors.get(index);
			if(counters != null)
				counters.childEvaluations++;
			int cell = factor.summedCell(global, baseCells, valueStrides, index, value);
			int logicalCell = baseCells == null ? factor.cell(global)
				: baseCells[index] + value * valueStrides[index];
			if(cell < 0 || factor.valueAt(logicalCell) == Double.POSITIVE_INFINITY)
				return PreciseCost.POSITIVE_INFINITY;
			// Each word is at most 53 bits, so both long additions below fit exactly.
			long lowSum = low + (factor.lowValues == null ? 0L : (long)factor.lowValues[cell]);
			high += (long)factor.valueAt(logicalCell) + (lowSum >>> 53);
			if(high > mask)
				throw new IllegalArgumentException("EXACT_VE_DYADIC_WORD_OVERFLOW");
			low = lowSum & mask;
			if(costPruning != null && costPruning.exceeds(high, low, index + 1, best.high, best.low))
				return null;
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
		return preciseSum(factors, global, baseCells, valueStrides, value, null, null, null);
	}

	private static PreciseCost preciseSum(List<DenseFactor> factors, int[] global,
		int[] baseCells, int[] valueStrides, int value, CostPruning costPruning,
		PreciseCost best, BoundaryMergeCounters counters) {
		double high = 0d;
		double low = 0d;
		long tie = 0L;
		for(int index = 0; index < factors.size(); index++) {
			DenseFactor factor = factors.get(index);
			if(counters != null)
				counters.childEvaluations++;
			int cell = factor.summedCell(global, baseCells, valueStrides, index, value);
			if(cell < 0)
				return PreciseCost.POSITIVE_INFINITY;
			int logicalCell = baseCells == null ? factor.cell(global)
				: baseCells[index] + value * valueStrides[index];
			double valueHigh = factor.valueAt(logicalCell);
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
			if(costPruning != null && costPruning.exceeds(high, low, index + 1, best.high, best.low))
				return null;
		}
		return new PreciseCost(high, low, tie);
	}

	private static int[] knownZeroFactorTokens(boolean[] knownZeroFactors) {
		int count = 0;
		boolean previousZero = false;
		for(boolean knownZero : knownZeroFactors) {
			if(!knownZero || !previousZero)
				count++;
			previousZero = knownZero;
		}
		int[] tokens = new int[count];
		int output = 0;
		for(int index = 0; index < knownZeroFactors.length;) {
			if(!knownZeroFactors[index]) {
				tokens[output++] = index++;
				continue;
			}
			int start = index;
			while(index < knownZeroFactors.length && knownZeroFactors[index])
				index++;
			tokens[output++] = start - index;
		}
		return tokens;
	}

	/** Sparse-only path; certified-zero runs retain their exact original arithmetic positions. */
	private static PreciseCost preciseSumKnownZeros(List<DenseFactor> factors, int[] global,
		int[] knownZeroTokens) {
		return preciseSumKnownZeros(factors, global, knownZeroTokens, null, null, null);
	}

	private static PreciseCost preciseSumKnownZeros(List<DenseFactor> factors, int[] global,
		int[] knownZeroTokens, CostPruning costPruning, PreciseCost best, BoundaryMergeCounters counters) {
		double high = 0d;
		double low = 0d;
		long tie = 0L;
		for(int token : knownZeroTokens) {
			if(token < 0) {
				if(low == 0d) {
					high += 0d;
					low = 0d;
				}
				else {
					PreciseCost normalized = addKnownZeroRun(high, low, tie, -token);
					high = normalized.high;
					low = normalized.low;
				}
				continue;
			}
			int index = token;
			DenseFactor factor = factors.get(index);
			if(counters != null)
				counters.childEvaluations++;
			int cell = factor.summedCell(global, null, null, index, 0);
			if(cell < 0)
				return PreciseCost.POSITIVE_INFINITY;
			double valueHigh = factor.storedValue(cell);
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
			// index is the original factor position, not the compressed token offset.
			if(costPruning != null && costPruning.exceeds(high, low, index + 1, best.high, best.low))
				return null;
		}
		return new PreciseCost(high, low, tie);
	}

	private static PreciseCost addKnownZeroRun(double high, double low, long tie, int length) {
		if(length <= 0)
			throw new IllegalArgumentException("Known-zero run length must be positive");
		for(int remaining = length; remaining > 0; remaining--) {
			if(low == 0d) {
				high += 0d;
				low = 0d;
				break;
			}
			long beforeHigh = Double.doubleToRawLongBits(high);
			long beforeLow = Double.doubleToRawLongBits(low);
			double sum = high + 0d;
			if(!Double.isFinite(sum))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double virtual = sum - high;
			double error = (high - (sum - virtual)) + (0d - virtual);
			error += low;
			if(!Double.isFinite(error))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedHigh = sum + error;
			if(!Double.isFinite(normalizedHigh))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			low = error - (normalizedHigh - sum);
			high = normalizedHigh;
			if(beforeHigh == Double.doubleToRawLongBits(high)
				&& beforeLow == Double.doubleToRawLongBits(low))
				break;
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

	/** PreciseCost.plus in caller-owned primitive storage, preserving its error order. */
	private static void addBoundaryCost(double[] cost, double valueHigh, double valueLow) {
		if(cost[0] == Double.POSITIVE_INFINITY || valueHigh == Double.POSITIVE_INFINITY) {
			cost[0] = Double.POSITIVE_INFINITY;
			cost[1] = 0d;
			return;
		}
		double sum = cost[0] + valueHigh;
		if(!Double.isFinite(sum))
			throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
		double virtual = sum - cost[0];
		double error = (cost[0] - (sum - virtual)) + (valueHigh - virtual);
		error += cost[1] + valueLow;
		if(!Double.isFinite(error))
			throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
		double normalizedHigh = sum + error;
		if(!Double.isFinite(normalizedHigh))
			throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
		cost[1] = error - (normalizedHigh - sum);
		cost[0] = normalizedHigh;
	}

	/** PreciseCost.compareTo for boundary costs, whose tie cost is always zero. */
	private static int compareBoundaryCost(double leftHigh, double leftLow,
		double rightHigh, double rightLow) {
		return Double.compare(roundBoundaryCost(leftHigh,leftLow),
			roundBoundaryCost(rightHigh,rightLow));
	}

	private static double roundBoundaryCost(double high, double low) {
		if(high == Double.POSITIVE_INFINITY)
			return high;
		double result = high + low;
		if(!Double.isFinite(result))
			throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
		return result;
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
	static record EliminationOrderScoreResult(Statistics statistics,
		List<List<String>> separators, long fillComputations,
		long neighborCellComputations, long degreeComputations) { }
	private record ScoredPlan(Plan plan, PlanMetrics metrics, int priority) { }
	private record Prepared(List<Variable> variables, int[] domains, List<int[]> scopes,
		List<Step> steps, Statistics statistics, Limits limits, boolean deferredDyadic) { }
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
		private static final PreciseCost ZERO = new PreciseCost(0d, 0d, 0L);
		private static final PreciseCost POSITIVE_INFINITY =
			new PreciseCost(Double.POSITIVE_INFINITY, 0d, 0L);

		private PreciseCost plus(PreciseCost that) {
			// Preserve the legacy absorbing-left short circuit before dereferencing RHS.
			if(high == Double.POSITIVE_INFINITY)
				return POSITIVE_INFINITY;
			return plus(that.high, that.low, that.tieCost);
		}

		private PreciseCost plus(double thatHigh, double thatLow, long thatTieCost) {
			if(high == Double.POSITIVE_INFINITY || thatHigh == Double.POSITIVE_INFINITY)
				return POSITIVE_INFINITY;
			double sum = high + thatHigh;
			if(!Double.isFinite(sum))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double virtual = sum - high;
			double error = (high - (sum - virtual)) + (thatHigh - virtual);
			error += low + thatLow;
			if(!Double.isFinite(error))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedHigh = sum + error;
			if(!Double.isFinite(normalizedHigh))
				throw new IllegalArgumentException("EXACT_VE_OBJECTIVE_OVERFLOW");
			double normalizedLow = error - (normalizedHigh - sum);
			long combinedTie = addTieCost(tieCost, thatTieCost);
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
			if(first.finiteCount == first.logicalCells())
				return null;
			int stride = first.stride(variable);
			int rows = first.logicalCells() / domain;
			int words = (domain - 1) / Long.SIZE + 1;
			// words <= domain, so the index cannot exceed the validated input cells.
			long[] forbidden = null;
			long count = 0L;
			for(int cell = 0; cell < first.logicalCells(); cell++) {
				if(first.valueAt(cell) != Double.POSITIVE_INFINITY)
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

	private static final class DenseFactor {
		private final int[] scope;
		private final int[] strides;
		private final int[] dimensions;
		private final double[] values;
		private final HardTable hardValues;
		private final ConditionalSupport conditionalSupport;
		private final double[] lowValues;
		private final long[] tieCosts;
		private final int[] sparseCells;
		private int finiteCount = -1;
		private byte finiteValuesAreExactPositiveZero = -1;
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
			this.hardValues = null;
			this.conditionalSupport = null;
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

		private DenseFactor(int[] scope, int[] domains, HardTable hardValues) {
			this.scope = scope.clone();
			this.values = null;
			this.hardValues = hardValues;
			this.conditionalSupport = null;
			this.lowValues = null;
			this.tieCosts = null;
			this.sparseCells = null;
			this.strides = new int[scope.length];
			this.dimensions = new int[scope.length];
			int stride = 1;
			for(int index = scope.length - 1; index >= 0; index--) {
				strides[index] = stride;
				dimensions[index] = domains[scope[index]];
				stride = Math.multiplyExact(stride, dimensions[index]);
			}
			if(stride != hardValues.cells())
				throw new IllegalArgumentException("EXACT_VE_DENSE_FACTOR_SIZE_MISMATCH");
		}

		private DenseFactor(int[] scope, int[] domains, ConditionalSupport conditionalSupport) {
			this.scope = scope.clone();
			this.values = null;
			this.hardValues = null;
			this.conditionalSupport = conditionalSupport;
			this.lowValues = null;
			this.tieCosts = null;
			this.sparseCells = null;
			this.strides = new int[scope.length];
			this.dimensions = new int[scope.length];
			int stride = 1;
			for(int index = scope.length - 1; index >= 0; index--) {
				strides[index] = stride;
				dimensions[index] = domains[scope[index]];
				stride = Math.multiplyExact(stride, dimensions[index]);
			}
		}

		private int logicalCells() {
			if(hardValues != null)
				return hardValues.cells();
			if(conditionalSupport != null) {
				int cells = 1;
				for(int dimension : dimensions)
					cells = Math.multiplyExact(cells, dimension);
				return cells;
			}
			if(sparseCells == null)
				return values.length;
			int cells = 1;
			for(int dimension : dimensions)
				cells = Math.multiplyExact(cells, dimension);
			return cells;
		}

		private double valueAt(int logicalCell) {
			if(hardValues != null)
				return hardValues.costAt(logicalCell);
			if(conditionalSupport != null)
				return conditionalSupport.costAtCell(logicalCell);
			int stored = storageCell(logicalCell);
			return stored < 0 ? Double.POSITIVE_INFINITY : values[stored];
		}

		private int storedCells() {
			return conditionalSupport != null ? Math.toIntExact(conditionalSupport.storedValues())
				: hardValues == null ? values.length : hardValues.cells();
		}
		private double storedValue(int stored) {
			return conditionalSupport != null ? 0d
				: hardValues == null ? values[stored] : hardValues.costAt(stored);
		}
		private int storedLogicalCell(int stored) {
			return hardValues != null || sparseCells == null ? stored : sparseCells[stored];
		}

		/** Storage indices retain their existing meaning; only iteration skips implicit infinities. */
		private int nextFiniteStoredCell(int previous) {
			if(conditionalSupport != null)
				throw new IllegalStateException("EXACT_CONDITIONAL_SUPPORT_NOT_FLAT");
			if(hardValues != null && hardValues.functional != null)
				return hardValues.functional.finiteCellAfter(previous);
			for(int stored = previous + 1; stored < storedCells(); stored++)
				if(storedValue(stored) != Double.POSITIVE_INFINITY)
					return stored;
			return -1;
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
			if(conditionalSupport != null) {
				int[] identity = new int[domain];
				for(int value = 0; value < domain; value++)
					identity[value] = value;
				return identity;
			}
			if(responseClasses == null)
				responseClasses = new int[scope.length][];
			if(responseClasses[axis] == null) {
				if(hardValues != null) {
					responseClasses[axis] = hardValues.axisClasses(dimensions[axis],strides[axis]);
				}
				else if(conditionalSupport == null && sparseCells == null)
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
		private ExactFiniteSupportJoin.SupportRelation projectedSupport(BucketProjection projection) {
			if(conditionalSupport != null)
				return new ExactFiniteSupportJoin.ConditionalProductRelation(scope,
					conditionalSupport.selectorAxis, dimensions,
					conditionalSupport.constrainedSelectorValues, conditionalSupport.regions);
			if(logicalCells() == 0)
				return new ExactFiniteSupportJoin.Relation(scope, new int[0]);
			ensureFiniteCount();
			if(finiteCount == logicalCells())
				return null;
			boolean identity = true;
			for(int axis = 0; identity && axis < scope.length; axis++) {
				int[] representatives = projection.representatives[scope[axis]];
				identity = representatives.length == dimensions[axis];
				for(int value = 0; identity && value < representatives.length; value++)
					identity = coordinate(axis, representatives[value]) == value;
			}
			if(identity)
				return explicitSupport(selectiveFiniteCells());
			int cells = checkedCells(scope, projection.domains, "EXACT_VE_FACTOR_CELL_OVERFLOW");
			int[][] quotientToStored = new int[scope.length][];
			int[][] coordinateCounts = new int[scope.length][];
			for(int axis = 0; axis < scope.length; axis++) {
				int[] representatives = projection.representatives[scope[axis]];
				quotientToStored[axis] = new int[representatives.length];
				coordinateCounts[axis] = new int[dimensions[axis]];
				for(int value = 0; value < representatives.length; value++) {
					int coordinate = coordinate(axis, representatives[value]);
					quotientToStored[axis][value] = coordinate;
					coordinateCounts[axis][coordinate]++;
				}
			}
			long projectedFinite = 0L;
			for(int stored = nextFiniteStoredCell(-1); stored >= 0;
				stored = nextFiniteStoredCell(stored)) {
				int cell = storedLogicalCell(stored);
				long multiplicity = 1L;
				for(int axis = 0; axis < scope.length; axis++)
					multiplicity *= coordinateCounts[axis][
						cell / strides[axis] % dimensions[axis]];
				projectedFinite += multiplicity;
				if(projectedFinite * 2L > cells)
					return null;
			}
			int[] finite = finiteCells();
			ExactFiniteSupportJoin.QuotientProjectedRelation projected =
				new ExactFiniteSupportJoin.QuotientProjectedRelation(
					scope, dimensions, finite, quotientToStored);
			return projected;
		}

		private ExactFiniteSupportJoin.SupportRelation explicitSupport(int[] finite) {
			return finite == null ? null : new ExactFiniteSupportJoin.Relation(scope, finite);
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
			ensureFiniteCount();
			if((long)finiteCount * 2 > logicalCells())
				return null;
			return finiteCells();
		}

		private int[] finiteCells() {
			if(sparseCells != null)
				return sparseCells;
			ensureFiniteCount();
			int[] cells = PlannerResourceGuard.allocateInts(finiteCount, "exact-sparse-support");
			int output = 0;
			for(int cell = nextFiniteStoredCell(-1); cell >= 0; cell = nextFiniteStoredCell(cell))
				cells[output++] = cell;
			return cells;
		}

		private void ensureFiniteCount() {
			if(sparseCells != null) {
				finiteCount = values.length;
				return;
			}
			if(hardValues != null)
				finiteCount = hardValues.finiteCount();
			if(finiteCount >= 0)
				return;
			finiteCount = 0;
			for(int cell = 0; cell < logicalCells(); cell++)
				if(valueAt(cell) != Double.POSITIVE_INFINITY)
					finiteCount++;
		}

		private boolean allFiniteDense() {
			if(conditionalSupport != null)
				return false;
			if(sparseCells != null)
				return false;
			if(hardValues != null)
				return hardValues.finiteCount() == hardValues.cells();
			if(finiteCount < 0) {
				finiteCount = 0;
				for(double value : values)
					if(value != Double.POSITIVE_INFINITY)
						finiteCount++;
			}
			return finiteCount == values.length;
		}

		private boolean finiteValuesAreExactPositiveZero() {
			if(conditionalSupport != null)
				return true;
			if(hardValues != null)
				return true;
			if(finiteValuesAreExactPositiveZero < 0) {
				boolean exactZero = true;
				for(int cell = 0; exactZero && cell < values.length; cell++) {
					if(values[cell] == Double.POSITIVE_INFINITY)
						continue;
					exactZero = Double.doubleToRawLongBits(values[cell]) == 0L
						&& (lowValues == null || Double.doubleToRawLongBits(lowValues[cell]) == 0L)
						&& (tieCosts == null || tieCosts[cell] == 0L);
				}
				finiteValuesAreExactPositiveZero = (byte)(exactZero ? 1 : 0);
			}
			return finiteValuesAreExactPositiveZero == 1;
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
