/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Exact sparse natural join over finite factor cells in one fixed elimination bucket. */
final class ExactFiniteSupportJoin {
	interface SupportRelation {
		int[] scope();
		long size();
	}

	/**
	 * A factor relation whose cells are strictly increasing, unique row-major flat indexes.
	 * Scope entries are global variable indexes into the domains supplied to {@link #forEach}.
	 */
	record Relation(int[] scope, int[] finiteCells) implements SupportRelation {
		Relation {
			scope = guardedCopy(Objects.requireNonNull(scope, "scope"),
				"exact-sparse-relation-scope");
			finiteCells = guardedCopy(Objects.requireNonNull(finiteCells, "finiteCells"),
				"exact-sparse-relation-cells");
		}
		@Override public int[] scope() { return guardedCopy(scope, "exact-sparse-relation-scope-copy"); }
		@Override public int[] finiteCells() {
			return guardedCopy(finiteCells, "exact-sparse-relation-cells-copy");
		}
		@Override public long size() { return finiteCells.length; }
	}

	/**
	 * A finite relation lifted through quotient axes without expanding the quotient Cartesian
	 * product. Each quotient value maps to one coordinate of the stored base relation.
	 */
	static final class QuotientProjectedRelation implements SupportRelation {
		private final int[] scope;
		private final int[] baseDimensions;
		private final int[] baseCells;
		private final int[][] quotientToBase;
		private final long size;

		QuotientProjectedRelation(int[] scope, int[] baseDimensions, int[] baseCells,
			int[][] quotientToBase) {
			this.scope = guardedCopy(Objects.requireNonNull(scope, "scope"),
				"exact-sparse-projected-scope");
			this.baseDimensions = guardedCopy(Objects.requireNonNull(baseDimensions, "baseDimensions"),
				"exact-sparse-projected-dimensions");
			this.baseCells = guardedCopy(Objects.requireNonNull(baseCells, "baseCells"),
				"exact-sparse-projected-cells");
			Objects.requireNonNull(quotientToBase, "quotientToBase");
			if(this.scope.length != this.baseDimensions.length
				|| this.scope.length != quotientToBase.length)
				throw new IllegalArgumentException("EXACT_FINITE_JOIN_PROJECTED_SHAPE_INVALID");
			this.quotientToBase = new int[quotientToBase.length][];
			for(int axis = 0; axis < quotientToBase.length; axis++) {
				if(this.baseDimensions[axis] <= 0)
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_PROJECTED_DOMAIN_INVALID");
				this.quotientToBase[axis] = guardedCopy(Objects.requireNonNull(quotientToBase[axis],
					"quotientToBase[" + axis + "]"), "exact-sparse-projected-map");
				for(int coordinate : this.quotientToBase[axis])
					if(coordinate < 0 || coordinate >= this.baseDimensions[axis])
						throw new IllegalArgumentException("EXACT_FINITE_JOIN_PROJECTED_VALUE_INVALID");
			}
			long baseCardinality = 1L;
			for(int dimension : this.baseDimensions) {
				baseCardinality *= dimension;
				if(baseCardinality > Integer.MAX_VALUE)
					throw new IllegalArgumentException(
						"EXACT_FINITE_JOIN_RELATION_CARDINALITY_OVERFLOW");
			}
			int previous = -1;
			for(int cell : this.baseCells) {
				if(cell < 0 || cell >= baseCardinality)
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_CELL_INVALID");
				if(cell <= previous)
					throw new IllegalArgumentException(
						"EXACT_FINITE_JOIN_CELLS_NOT_STRICTLY_SORTED");
				previous = cell;
			}
			int[][] counts = new int[this.scope.length][];
			for(int axis = 0; axis < this.scope.length; axis++) {
				counts[axis] = new int[this.baseDimensions[axis]];
				for(int coordinate : this.quotientToBase[axis])
					counts[axis][coordinate]++;
			}
			int[] strides = strides(this.baseDimensions);
			long expanded = 0L;
			for(int cell : this.baseCells) {
				long multiplicity = 1L;
				for(int axis = 0; axis < this.scope.length; axis++)
					multiplicity = saturatedMultiply(multiplicity,
						counts[axis][cell / strides[axis] % this.baseDimensions[axis]]);
				expanded = saturatedAdd(expanded, multiplicity);
			}
			size = expanded;
		}

		@Override public int[] scope() {
			return guardedCopy(scope, "exact-sparse-projected-scope-copy");
		}
		@Override public long size() { return size; }
		int storedRows() { return baseCells.length; }
	}

	/** Selector-gated union of product rectangles; absent selector rows are universal. */
	static final class ConditionalProductRelation implements SupportRelation {
		private final int[] scope;
		private final int selectorAxis;
		private final int[] domains;
		private final List<ExactCategoricalSolver.ConditionalRegion> regions;
		private final boolean[] conditioned;
		private final long size;

		ConditionalProductRelation(int[] scope, int selectorAxis, int[] domains,
			int[] constrainedSelectorValues,
			List<ExactCategoricalSolver.ConditionalRegion> regions) {
			this.scope = guardedCopy(scope, "exact-conditional-scope");
			this.selectorAxis = selectorAxis;
			this.domains = guardedCopy(domains, "exact-conditional-domains");
			this.regions = List.copyOf(regions);
			conditioned = new boolean[domains[selectorAxis]];
			for(int selector : constrainedSelectorValues)
				conditioned[selector] = true;
			long upper = 0L;
			for(ExactCategoricalSolver.ConditionalRegion region : regions) {
				long product = 1L;
				int[][] allowed = region.ownedAllowedValuesByAxis();
				for(int axis = 0; axis < scope.length; axis++)
					if(axis != selectorAxis)
						product = saturatedMultiply(product, allowed[axis].length);
				upper = saturatedAdd(upper, product);
			}
			long universalProduct = 1L;
			for(int axis = 0; axis < scope.length; axis++)
				if(axis != selectorAxis)
					universalProduct = saturatedMultiply(universalProduct, domains[axis]);
			for(boolean selected : conditioned)
				if(!selected)
					upper = saturatedAdd(upper, universalProduct);
			size = upper;
		}

		@Override public int[] scope() { return guardedCopy(scope, "exact-conditional-scope-copy"); }
		@Override public long size() { return size; }
	}

	/** Audit-only work counts; neither count is a search limit or planner policy input. */
	record Work(long visitedRelationRows, long emittedAssignments) { }

	private static final class MutableWork {
		long visitedRelationRows;
		long emittedAssignments;
	}

	private interface BoundIndex {
		int firstRow(int key);
	}

	private static final class DenseBoundIndex implements BoundIndex {
		private final int[] heads;
		private DenseBoundIndex(int keySpace) {
			heads = PlannerResourceGuard.allocateInts(keySpace, "exact-sparse-dense-index");
			Arrays.fill(heads, -1);
		}
		@Override public int firstRow(int key) { return heads[key]; }
		private int replace(int key, int row) {
			int previous = heads[key];
			heads[key] = row;
			return previous;
		}
	}

	private static final class OpenBoundIndex implements BoundIndex {
		private final int[] keys;
		private final int[] heads;
		private final int mask;
		private OpenBoundIndex(int capacity) {
			keys = PlannerResourceGuard.allocateInts(capacity, "exact-sparse-open-index-keys");
			heads = PlannerResourceGuard.allocateInts(capacity, "exact-sparse-open-index-heads");
			Arrays.fill(keys, -1);
			Arrays.fill(heads, -1);
			mask = capacity - 1;
		}
		@Override public int firstRow(int key) {
			int slot = slot(key);
			while(keys[slot] != -1) {
				if(keys[slot] == key)
					return heads[slot];
				slot = slot + 1 & mask;
			}
			return -1;
		}
		private int replace(int key, int row) {
			int slot = slot(key);
			while(keys[slot] != -1 && keys[slot] != key)
				slot = slot + 1 & mask;
			int previous = heads[slot];
			keys[slot] = key;
			heads[slot] = row;
			return previous;
		}
		private int slot(int key) {
			int hash = key * 0x9e3779b9;
			return (hash ^ hash >>> 16) & mask;
		}
	}

	private interface PreparedSupport {
		int[] scope();
		int[] newlyBoundVariables();
		void reset(int[] assignment, boolean[] bound);
		boolean next(int[] assignment, boolean[] bound, MutableWork work);
	}

	private static final class PreparedRelation implements PreparedSupport {
		private final int[] scope;
		private final int[] finiteCells;
		private final int[] strides;
		private final int[] boundAxes;
		private final BoundIndex boundIndex;
		private final int[] nextRow;
		private final int[] newlyBoundVariables;
		private final int[] domains;
		private int next;

		private PreparedRelation(int[] scope, int[] finiteCells, int[] strides,
		int[] boundAxes, BoundIndex boundIndex, int[] nextRow,
			int[] newlyBoundVariables, int[] domains) {
			this.scope = scope;
			this.finiteCells = finiteCells;
			this.strides = strides;
			this.boundAxes = boundAxes;
			this.boundIndex = boundIndex;
			this.nextRow = nextRow;
			this.newlyBoundVariables = newlyBoundVariables;
			this.domains = domains;
		}

		@Override public int[] scope() { return scope; }
		@Override public int[] newlyBoundVariables() { return newlyBoundVariables; }
		@Override public void reset(int[] assignment, boolean[] bound) {
			next = firstMatchingRow(this, assignment, domains);
		}
		@Override public boolean next(int[] assignment, boolean[] bound, MutableWork work) {
			while(next >= 0) {
				int rowIndex = next;
				next = nextMatchingRow(this, rowIndex);
				work.visitedRelationRows = increment(work.visitedRelationRows);
				int cell = finiteCells[rowIndex];
				if(!matchesBound(cell, this, bound, assignment, domains))
					continue;
				for(int axis = 0; axis < scope.length; axis++)
					if(!bound[scope[axis]])
						assignment[scope[axis]] = valueAt(cell, axis, scope, strides, domains);
				return true;
			}
			return false;
		}
	}

	private static final class PreparedProjectedRelation implements PreparedSupport {
		private final int[] scope;
		private final int[] baseDimensions;
		private final int[] baseCells;
		private final int[][] quotientToBase;
		private final int[] baseStrides;
		private final int[][] heads;
		private final int[][] nextValues;
		private final int[] newlyBoundVariables;
		private final int[] first;
		private final int[] positions;
		private int baseRow;
		private boolean activeBase;

		private PreparedProjectedRelation(QuotientProjectedRelation relation,
			boolean[] bound) {
			scope = relation.scope;
			baseDimensions = relation.baseDimensions;
			baseCells = relation.baseCells;
			quotientToBase = relation.quotientToBase;
			baseStrides = strides(baseDimensions);
			newlyBoundVariables = variables(scope, bound, false);
			heads = new int[scope.length][];
			nextValues = new int[scope.length][];
			first = new int[scope.length];
			positions = new int[scope.length];
			for(int axis = 0; axis < scope.length; axis++) {
				heads[axis] = new int[baseDimensions[axis]];
				Arrays.fill(heads[axis], -1);
				nextValues[axis] = new int[quotientToBase[axis].length];
				for(int value = quotientToBase[axis].length - 1; value >= 0; value--) {
					int coordinate = quotientToBase[axis][value];
					nextValues[axis][value] = heads[axis][coordinate];
					heads[axis][coordinate] = value;
				}
			}
		}

		@Override public int[] scope() { return scope; }
		@Override public int[] newlyBoundVariables() { return newlyBoundVariables; }
		@Override public void reset(int[] assignment, boolean[] bound) {
			baseRow = 0;
			activeBase = false;
		}
		@Override public boolean next(int[] assignment, boolean[] bound, MutableWork work) {
			if(activeBase && advance(bound)) {
				write(assignment, bound);
				work.visitedRelationRows = increment(work.visitedRelationRows);
				return true;
			}
			activeBase = false;
			while(baseRow < baseCells.length) {
				int cell = baseCells[baseRow++];
				boolean matches = true;
				for(int axis = 0; axis < scope.length; axis++) {
					int coordinate = cell / baseStrides[axis] % baseDimensions[axis];
					if(bound[scope[axis]]) {
						if(quotientToBase[axis][assignment[scope[axis]]] != coordinate) {
							matches = false;
							break;
						}
						first[axis] = positions[axis] = assignment[scope[axis]];
					}
					else {
						first[axis] = positions[axis] = heads[axis][coordinate];
						if(first[axis] < 0) {
							matches = false;
							break;
						}
					}
				}
				if(!matches)
					continue;
				activeBase = true;
				write(assignment, bound);
				work.visitedRelationRows = increment(work.visitedRelationRows);
				return true;
			}
			return false;
		}

		private boolean advance(boolean[] bound) {
			for(int axis = scope.length - 1; axis >= 0; axis--) {
				if(bound[scope[axis]])
					continue;
				int next = nextValues[axis][positions[axis]];
				if(next >= 0) {
					positions[axis] = next;
					return true;
				}
				positions[axis] = first[axis];
			}
			return false;
		}

		private void write(int[] assignment, boolean[] bound) {
			for(int axis = 0; axis < scope.length; axis++)
				if(!bound[scope[axis]])
					assignment[scope[axis]] = positions[axis];
		}
	}

	private static final class PreparedConditionalRelation implements PreparedSupport {
		private final ConditionalProductRelation relation;
		private final int[] newlyBoundVariables;
		private final int[] values;
		private final int[] indexes;
		private int selector;
		private int region;
		private boolean initialized;

		private PreparedConditionalRelation(ConditionalProductRelation relation, boolean[] bound) {
			this.relation = relation;
			newlyBoundVariables = variables(relation.scope, bound, false);
			values = new int[relation.scope.length];
			indexes = new int[relation.scope.length];
		}

		@Override public int[] scope() { return relation.scope; }
		@Override public int[] newlyBoundVariables() { return newlyBoundVariables; }
		@Override public void reset(int[] assignment, boolean[] bound) {
			selector = bound[relation.scope[relation.selectorAxis]]
				? assignment[relation.scope[relation.selectorAxis]] : 0;
			region = -1;
			initialized = false;
		}
		@Override public boolean next(int[] assignment, boolean[] bound, MutableWork work) {
			while(true) {
				if(initialized && advance(bound)) {
					if(canonical()) {
						write(assignment, bound);
						work.visitedRelationRows = increment(work.visitedRelationRows);
						return true;
					}
					continue;
				}
				initialized = false;
				if(!nextBranch(assignment, bound))
					return false;
				initialized = true;
				if(canonical()) {
					write(assignment, bound);
					work.visitedRelationRows = increment(work.visitedRelationRows);
					return true;
				}
			}
		}

		private boolean nextBranch(int[] assignment, boolean[] bound) {
			int selectorVariable = relation.scope[relation.selectorAxis];
			while(selector < relation.domains[relation.selectorAxis]) {
				if(bound[selectorVariable] && selector != assignment[selectorVariable]) {
					selector++;
					region = -1;
					continue;
				}
				if(!relation.conditioned[selector]) {
					if(region < 0) {
						region = Integer.MAX_VALUE;
						return initialize(null, assignment, bound);
					}
					selector++;
					region = -1;
					continue;
				}
				for(int next = region + 1; next < relation.regions.size(); next++) {
					ExactCategoricalSolver.ConditionalRegion candidate = relation.regions.get(next);
					if(candidate.selectorValue() != selector)
						continue;
					region = next;
					if(initialize(candidate, assignment, bound))
						return true;
				}
				selector++;
				region = -1;
			}
			return false;
		}

		private boolean initialize(ExactCategoricalSolver.ConditionalRegion rectangle,
			int[] assignment, boolean[] bound) {
			int[][] allowed = rectangle == null ? null : rectangle.ownedAllowedValuesByAxis();
			for(int axis = 0; axis < relation.scope.length; axis++) {
				if(axis == relation.selectorAxis) {
					values[axis] = selector;
					continue;
				}
				int variable = relation.scope[axis];
				indexes[axis] = 0;
				if(bound[variable]) {
					values[axis] = assignment[variable];
					if(allowed != null && Arrays.binarySearch(allowed[axis], values[axis]) < 0)
						return false;
				}
				else {
					if(allowed != null && allowed[axis].length == 0)
						return false;
					values[axis] = allowed == null ? 0 : allowed[axis][0];
				}
			}
			return true;
		}

		private boolean advance(boolean[] bound) {
			ExactCategoricalSolver.ConditionalRegion rectangle = region == Integer.MAX_VALUE
				? null : relation.regions.get(region);
			int[][] allowed = rectangle == null ? null : rectangle.ownedAllowedValuesByAxis();
			for(int axis = relation.scope.length - 1; axis >= 0; axis--) {
				if(axis == relation.selectorAxis || bound[relation.scope[axis]])
					continue;
				int limit = allowed == null ? relation.domains[axis] : allowed[axis].length;
				if(++indexes[axis] < limit) {
					values[axis] = allowed == null ? indexes[axis] : allowed[axis][indexes[axis]];
					return true;
				}
				indexes[axis] = 0;
				values[axis] = allowed == null ? 0 : allowed[axis][0];
			}
			return false;
		}

		private boolean canonical() {
			if(region == Integer.MAX_VALUE)
				return true;
			for(int prior = 0; prior < region; prior++) {
				ExactCategoricalSolver.ConditionalRegion candidate = relation.regions.get(prior);
				if(candidate.selectorValue() != selector)
					continue;
				int[][] allowed = candidate.ownedAllowedValuesByAxis();
				boolean matches = true;
				for(int axis = 0; axis < values.length; axis++)
					if(axis != relation.selectorAxis
						&& Arrays.binarySearch(allowed[axis], values[axis]) < 0) {
						matches = false;
						break;
					}
				if(matches)
					return false;
			}
			return true;
		}

		private void write(int[] assignment, boolean[] bound) {
			for(int axis = 0; axis < relation.scope.length; axis++)
				if(!bound[relation.scope[axis]])
					assignment[relation.scope[axis]] = values[axis];
		}
	}

	private ExactFiniteSupportJoin() { }

	/**
	 * Enumerates every assignment over {@code variables} satisfying every relation exactly once.
	 * The consumer receives one reused global assignment buffer of length {@code domains.length};
	 * it must treat that buffer as read-only and copy values it retains.
	 */
	static Work forEach(int[] variables, int[] domains, List<? extends SupportRelation> relations,
		Consumer<int[]> consumer) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(domains, "domains");
		Objects.requireNonNull(relations, "relations");
		Objects.requireNonNull(consumer, "consumer");
		int[] bucket = guardedCopy(variables, "exact-sparse-bucket");
		int[] domainSizes = guardedCopy(domains, "exact-sparse-domains");
		boolean[] inBucket = validateBucket(bucket, domainSizes);
		List<SupportRelation> checked = validateRelations(relations, domainSizes, inBucket);
		// Validate the complete input first, but do not allocate indexes for an
		// already-empty natural join.
		if(checked.stream().anyMatch(relation -> relation.size() == 0))
			return new Work(0L, 0L);
		// Stable support-size order reduces needless sparse expansion. It is only a
		// representation traversal; equal supports retain caller order and it does
		// not alter variable elimination order or planner policy.
		List<SupportRelation> ordered = new ArrayList<>(checked);
		ordered.sort(Comparator.comparingLong(SupportRelation::size));
		ordered = List.copyOf(ordered);
		List<PreparedSupport> prepared = prepare(ordered, domainSizes);
		boolean[] constrained = new boolean[domainSizes.length];
		for(SupportRelation relation : ordered)
			for(int variable : relation.scope())
				constrained[variable] = true;
		int[] remaining = Arrays.stream(bucket).filter(variable -> !constrained[variable]).toArray();
		int[] assignment = new int[domainSizes.length];
		boolean[] bound = new boolean[domainSizes.length];
		MutableWork work = new MutableWork();
		join(prepared, remaining, domainSizes, assignment, bound, consumer, work);
		return new Work(work.visitedRelationRows, work.emittedAssignments);
	}

	private static boolean[] validateBucket(int[] variables, int[] domains) {
		for(int domain : domains)
			if(domain <= 0)
				throw new IllegalArgumentException("EXACT_FINITE_JOIN_DOMAIN_INVALID");
		boolean[] inBucket = new boolean[domains.length];
		for(int variable : variables) {
			if(variable < 0 || variable >= domains.length)
				throw new IllegalArgumentException("EXACT_FINITE_JOIN_VARIABLE_INVALID");
			if(inBucket[variable])
				throw new IllegalArgumentException("EXACT_FINITE_JOIN_VARIABLE_DUPLICATE");
			inBucket[variable] = true;
		}
		return inBucket;
	}

	private static List<SupportRelation> validateRelations(
		List<? extends SupportRelation> relations, int[] domains,
		boolean[] inBucket) {
		List<SupportRelation> result = new ArrayList<>(relations.size());
		for(SupportRelation relation : relations) {
			Objects.requireNonNull(relation, "relation");
			boolean[] seen = new boolean[domains.length];
			long cells = 1L;
			int[] scope = relation instanceof Relation explicit ? explicit.scope
				: relation instanceof QuotientProjectedRelation projected ? projected.scope
				: ((ConditionalProductRelation) relation).scope;
			for(int variable : scope) {
				if(variable < 0 || variable >= domains.length || !inBucket[variable])
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_SCOPE_INVALID");
				if(seen[variable])
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_SCOPE_DUPLICATE");
				seen[variable] = true;
				cells *= domains[variable];
				if(cells > Integer.MAX_VALUE)
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_RELATION_CARDINALITY_OVERFLOW");
			}
			if(relation instanceof Relation explicit) {
				int previous = -1;
				for(int cell : explicit.finiteCells) {
					if(cell < 0 || cell >= cells)
						throw new IllegalArgumentException("EXACT_FINITE_JOIN_CELL_INVALID");
					if(cell <= previous)
						throw new IllegalArgumentException("EXACT_FINITE_JOIN_CELLS_NOT_STRICTLY_SORTED");
					previous = cell;
				}
			}
			else if(relation instanceof QuotientProjectedRelation projected) {
				for(int axis = 0; axis < scope.length; axis++)
					if(projected.quotientToBase[axis].length != domains[scope[axis]])
						throw new IllegalArgumentException(
							"EXACT_FINITE_JOIN_PROJECTED_SHAPE_INVALID");
			}
			else {
				ConditionalProductRelation conditional = (ConditionalProductRelation) relation;
				for(int axis = 0; axis < scope.length; axis++)
					if(conditional.domains[axis] != domains[scope[axis]])
						throw new IllegalArgumentException(
							"EXACT_FINITE_JOIN_CONDITIONAL_SHAPE_INVALID");
			}
			result.add(relation);
		}
		return List.copyOf(result);
	}

	private static List<PreparedSupport> prepare(List<SupportRelation> relations, int[] domains) {
		List<PreparedSupport> result = new ArrayList<>(relations.size());
		boolean[] bound = new boolean[domains.length];
		for(SupportRelation support : relations) {
			if(support instanceof ConditionalProductRelation conditional) {
				result.add(new PreparedConditionalRelation(conditional, bound));
				for(int variable : conditional.scope)
					bound[variable] = true;
				continue;
			}
			if(support instanceof QuotientProjectedRelation projected) {
				result.add(new PreparedProjectedRelation(projected, bound));
				for(int variable : projected.scope)
					bound[variable] = true;
				continue;
			}
			Relation relation = (Relation) support;
			int[] boundAxes = axes(relation.scope, bound, true);
			int[] newlyBoundVariables = variables(relation.scope, bound, false);
			int[] strides = strides(relation.scope, domains);
			BoundIndex index = null;
			int[] nextRow = new int[0];
			if(boundAxes.length > 0 && boundAxes.length < relation.scope.length) {
				nextRow = PlannerResourceGuard.allocateInts(relation.finiteCells.length,
					"exact-sparse-next-row");
				Arrays.fill(nextRow, -1);
				int keySpace = keySpace(relation.scope, boundAxes, domains);
				index = boundIndex(keySpace, relation.finiteCells.length);
				for(int row = relation.finiteCells.length - 1; row >= 0; row--) {
					int key = boundKey(relation.finiteCells[row], relation.scope,
						strides, boundAxes, domains);
					nextRow[row] = replace(index, key, row);
				}
			}
			result.add(new PreparedRelation(relation.scope, relation.finiteCells, strides,
				boundAxes, index, nextRow, newlyBoundVariables, domains));
			for(int variable : relation.scope)
				bound[variable] = true;
		}
		return List.copyOf(result);
	}

	private static int keySpace(int[] scope, int[] boundAxes, int[] domains) {
		int result = 1;
		for(int axis : boundAxes)
			result *= domains[scope[axis]];
		return result;
	}

	private static BoundIndex boundIndex(int keySpace, int rows) {
		if(keySpace <= (long) rows * 2L)
			return new DenseBoundIndex(keySpace);
		int maximumKeys = Math.min(keySpace, rows);
		long required = (maximumKeys * 4L + 2L) / 3L;
		if(required > (1L << 30))
			return new DenseBoundIndex(keySpace);
		int capacity = 1;
		while(capacity < required)
			capacity <<= 1;
		return new OpenBoundIndex(capacity);
	}

	private static int replace(BoundIndex index, int key, int row) {
		return index instanceof DenseBoundIndex dense
			? dense.replace(key, row) : ((OpenBoundIndex) index).replace(key, row);
	}

	private static int[] guardedCopy(int[] source, String phase) {
		int[] copy = PlannerResourceGuard.allocateInts(source.length, phase);
		System.arraycopy(source, 0, copy, 0, source.length);
		return copy;
	}

	private static int[] axes(int[] scope, boolean[] bound, boolean expected) {
		int count = 0;
		for(int variable : scope)
			if(bound[variable] == expected)
				count++;
		int[] result = new int[count];
		int output = 0;
		for(int axis = 0; axis < scope.length; axis++)
			if(bound[scope[axis]] == expected)
				result[output++] = axis;
		return result;
	}

	private static int[] variables(int[] scope, boolean[] bound, boolean expected) {
		return Arrays.stream(scope).filter(variable -> bound[variable] == expected).toArray();
	}

	private static int[] strides(int[] scope, int[] domains) {
		int[] result = new int[scope.length];
		int stride = 1;
		for(int axis = scope.length - 1; axis >= 0; axis--) {
			result[axis] = stride;
			stride *= domains[scope[axis]];
		}
		return result;
	}

	private static int boundKey(int cell, int[] scope, int[] strides,
		int[] boundAxes, int[] domains) {
		int key = 0;
		for(int axis : boundAxes)
			key = key * domains[scope[axis]] + valueAt(cell, axis, scope, strides, domains);
		return key;
	}

	private static int assignmentKey(int[] assignment, int[] scope, int[] boundAxes, int[] domains) {
		int key = 0;
		for(int axis : boundAxes) {
			int variable = scope[axis];
			key = key * domains[variable] + assignment[variable];
		}
		return key;
	}

	private static int valueAt(int cell, int axis, int[] scope, int[] strides, int[] domains) {
		return cell / strides[axis] % domains[scope[axis]];
	}

	private static int firstMatchingRow(PreparedRelation relation, int[] assignment, int[] domains) {
		if(relation.boundAxes.length == 0)
			return relation.finiteCells.length == 0 ? -1 : 0;
		if(relation.boundAxes.length == relation.scope.length) {
			int cell = 0;
			for(int variable : relation.scope)
				cell = cell * domains[variable] + assignment[variable];
			int row = Arrays.binarySearch(relation.finiteCells, cell);
			return row < 0 ? -1 : row;
		}
		int key = assignmentKey(assignment, relation.scope, relation.boundAxes, domains);
		return relation.boundIndex.firstRow(key);
	}

	private static int nextMatchingRow(PreparedRelation relation, int row) {
		if(relation.boundAxes.length == 0)
			return row + 1 < relation.finiteCells.length ? row + 1 : -1;
		if(relation.boundAxes.length == relation.scope.length)
			return -1;
		return relation.nextRow[row];
	}

	private static void join(List<PreparedSupport> relations, int[] remaining,
		int[] domains, int[] assignment, boolean[] bound, Consumer<int[]> consumer,
		MutableWork work) {
		if(relations.isEmpty()) {
			enumerateRemaining(remaining, domains, assignment, consumer, work);
			return;
		}
		boolean[] initialized = new boolean[relations.size()];
		boolean[] activeRow = new boolean[relations.size()];
		int depth = 0;
		while(depth >= 0) {
			if(depth == relations.size()) {
				enumerateRemaining(remaining, domains, assignment, consumer, work);
				depth--;
				continue;
			}
			PreparedSupport relation = relations.get(depth);
			if(activeRow[depth]) {
				for(int variable : relation.newlyBoundVariables())
					bound[variable] = false;
				activeRow[depth] = false;
			}
			if(!initialized[depth]) {
				relation.reset(assignment, bound);
				initialized[depth] = true;
			}
			boolean advanced = relation.next(assignment, bound, work);
			if(advanced) {
				for(int variable : relation.newlyBoundVariables())
					bound[variable] = true;
				activeRow[depth] = true;
				depth++;
				if(depth < relations.size())
					initialized[depth] = false;
			}
			if(!advanced) {
				initialized[depth] = false;
				depth--;
			}
		}
	}

	private static boolean matchesBound(int cell, PreparedRelation relation, boolean[] bound,
		int[] assignment, int[] domains) {
		for(int axis = 0; axis < relation.scope.length; axis++)
			if(bound[relation.scope[axis]] && valueAt(cell, axis, relation.scope,
				relation.strides, domains) != assignment[relation.scope[axis]])
				return false;
		return true;
	}

	private static void enumerateRemaining(int[] remaining, int[] domains,
		int[] assignment, Consumer<int[]> consumer, MutableWork work) {
		if(remaining.length == 0) {
			consumer.accept(assignment);
			work.emittedAssignments = increment(work.emittedAssignments);
			return;
		}
		for(int variable : remaining)
			assignment[variable] = 0;
		while(true) {
			consumer.accept(assignment);
			work.emittedAssignments = increment(work.emittedAssignments);
			int position = remaining.length - 1;
			while(position >= 0) {
				int variable = remaining[position];
				if(++assignment[variable] < domains[variable])
					break;
				assignment[variable] = 0;
				position--;
			}
			if(position < 0)
				return;
		}
	}

	private static long increment(long value) {
		return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
	}

	private static int[] strides(int[] dimensions) {
		int[] result = new int[dimensions.length];
		int stride = 1;
		for(int axis = dimensions.length - 1; axis >= 0; axis--) {
			result[axis] = stride;
			stride = Math.multiplyExact(stride, dimensions[axis]);
		}
		return result;
	}

	private static long saturatedMultiply(long left, long right) {
		if(left == 0L || right == 0L)
			return 0L;
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}
}
