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
	/**
	 * A factor relation whose cells are strictly increasing, unique row-major flat indexes.
	 * Scope entries are global variable indexes into the domains supplied to {@link #forEach}.
	 */
	record Relation(int[] scope, int[] finiteCells) {
		Relation {
			scope = Objects.requireNonNull(scope, "scope").clone();
			finiteCells = Objects.requireNonNull(finiteCells, "finiteCells").clone();
		}
		@Override public int[] scope() { return scope.clone(); }
		@Override public int[] finiteCells() { return finiteCells.clone(); }
		int size() { return finiteCells.length; }
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
			heads = new int[keySpace];
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
			keys = new int[capacity];
			heads = new int[capacity];
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

	private record PreparedRelation(int[] scope, int[] finiteCells, int[] strides,
		int[] boundAxes, BoundIndex boundIndex, int[] nextRow,
		int[] newlyBoundVariables) { }

	private ExactFiniteSupportJoin() { }

	/**
	 * Enumerates every assignment over {@code variables} satisfying every relation exactly once.
	 * The consumer receives one reused global assignment buffer of length {@code domains.length};
	 * it must treat that buffer as read-only and copy values it retains.
	 */
	static Work forEach(int[] variables, int[] domains, List<Relation> relations,
		Consumer<int[]> consumer) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(domains, "domains");
		Objects.requireNonNull(relations, "relations");
		Objects.requireNonNull(consumer, "consumer");
		int[] bucket = variables.clone();
		int[] domainSizes = domains.clone();
		boolean[] inBucket = validateBucket(bucket, domainSizes);
		List<Relation> checked = validateRelations(relations, domainSizes, inBucket);
		// Validate the complete input first, but do not allocate indexes for an
		// already-empty natural join.
		if(checked.stream().anyMatch(relation -> relation.size() == 0))
			return new Work(0L, 0L);
		// Stable support-size order reduces needless sparse expansion. It is only a
		// representation traversal; equal supports retain caller order and it does
		// not alter variable elimination order or planner policy.
		List<Relation> ordered = new ArrayList<>(checked);
		ordered.sort(Comparator.comparingInt(Relation::size));
		ordered = List.copyOf(ordered);
		List<PreparedRelation> prepared = prepare(ordered, domainSizes);
		boolean[] constrained = new boolean[domainSizes.length];
		for(Relation relation : ordered)
			for(int variable : relation.scope)
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

	private static List<Relation> validateRelations(List<Relation> relations, int[] domains,
		boolean[] inBucket) {
		List<Relation> result = new ArrayList<>(relations.size());
		for(Relation relation : relations) {
			Objects.requireNonNull(relation, "relation");
			boolean[] seen = new boolean[domains.length];
			long cells = 1L;
			for(int variable : relation.scope) {
				if(variable < 0 || variable >= domains.length || !inBucket[variable])
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_SCOPE_INVALID");
				if(seen[variable])
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_SCOPE_DUPLICATE");
				seen[variable] = true;
				cells *= domains[variable];
				if(cells > Integer.MAX_VALUE)
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_RELATION_CARDINALITY_OVERFLOW");
			}
			int previous = -1;
			for(int cell : relation.finiteCells) {
				if(cell < 0 || cell >= cells)
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_CELL_INVALID");
				if(cell <= previous)
					throw new IllegalArgumentException("EXACT_FINITE_JOIN_CELLS_NOT_STRICTLY_SORTED");
				previous = cell;
			}
			result.add(relation);
		}
		return List.copyOf(result);
	}

	private static List<PreparedRelation> prepare(List<Relation> relations, int[] domains) {
		List<PreparedRelation> result = new ArrayList<>(relations.size());
		boolean[] bound = new boolean[domains.length];
		for(Relation relation : relations) {
			int[] boundAxes = axes(relation.scope, bound, true);
			int[] newlyBoundVariables = variables(relation.scope, bound, false);
			int[] strides = strides(relation.scope, domains);
			BoundIndex index = null;
			int[] nextRow = new int[0];
			if(boundAxes.length > 0 && boundAxes.length < relation.scope.length) {
				nextRow = new int[relation.finiteCells.length];
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
				boundAxes, index, nextRow, newlyBoundVariables));
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

	private static void join(List<PreparedRelation> relations, int[] remaining,
		int[] domains, int[] assignment, boolean[] bound, Consumer<int[]> consumer,
		MutableWork work) {
		if(relations.isEmpty()) {
			enumerateRemaining(remaining, domains, assignment, consumer, work);
			return;
		}
		int[] nextAtDepth = new int[relations.size()];
		boolean[] initialized = new boolean[relations.size()];
		boolean[] activeRow = new boolean[relations.size()];
		int depth = 0;
		while(depth >= 0) {
			if(depth == relations.size()) {
				enumerateRemaining(remaining, domains, assignment, consumer, work);
				depth--;
				continue;
			}
			PreparedRelation relation = relations.get(depth);
			if(activeRow[depth]) {
				for(int variable : relation.newlyBoundVariables)
					bound[variable] = false;
				activeRow[depth] = false;
			}
			if(!initialized[depth]) {
				nextAtDepth[depth] = firstMatchingRow(relation, assignment, domains);
				initialized[depth] = true;
			}
			boolean advanced = false;
			while(nextAtDepth[depth] >= 0) {
				int rowIndex = nextAtDepth[depth];
				nextAtDepth[depth] = nextMatchingRow(relation, rowIndex);
				work.visitedRelationRows = increment(work.visitedRelationRows);
				int cell = relation.finiteCells[rowIndex];
				if(!matchesBound(cell, relation, bound, assignment, domains))
					continue;
				for(int axis = 0; axis < relation.scope.length; axis++)
					if(!bound[relation.scope[axis]])
						assignment[relation.scope[axis]] = valueAt(cell, axis,
							relation.scope, relation.strides, domains);
				for(int variable : relation.newlyBoundVariables)
					bound[variable] = true;
				activeRow[depth] = true;
				depth++;
				if(depth < relations.size())
					initialized[depth] = false;
				advanced = true;
				break;
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
}
