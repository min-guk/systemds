/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Exhaustive, deterministic search over optional exact-row representative choices. */
final class MinStExactVariantSearch {
	private MinStExactVariantSearch() {
		// utility class
	}

	static <T,R> R select(List<List<T>> groups, long maximumCombinations,
		Function<List<T>,Optional<R>> evaluator, Comparator<R> order) {
		Objects.requireNonNull(groups, "groups");
		Objects.requireNonNull(evaluator, "evaluator");
		Objects.requireNonNull(order, "order");
		if(maximumCombinations <= 0)
			throw new IllegalArgumentException("MINST_EXACT_VARIANT_LIMIT_INVALID");
		long combinations = 1L;
		for(List<T> group : groups) {
			Objects.requireNonNull(group, "variant group");
			if(group.isEmpty())
				throw new IllegalArgumentException("MINST_EXACT_VARIANT_GROUP_EMPTY");
			long factor = group.size() + 1L; // baseline representative or one exact row
			if(combinations > maximumCombinations / factor)
				throw new IllegalArgumentException("MINST_EXACT_ROW_VARIANT_SPACE_TOO_LARGE|limit="
					+ maximumCombinations);
			combinations *= factor;
		}
		List<R> best = new ArrayList<>(1);
		evaluate(groups, 0, new ArrayList<>(), evaluator, order, best);
		if(best.isEmpty())
			throw new IllegalArgumentException("MINST_EXACT_ROW_VARIANT_SPACE_EMPTY");
		return best.get(0);
	}

	private static <T,R> void evaluate(List<List<T>> groups, int index, List<T> selected,
		Function<List<T>,Optional<R>> evaluator, Comparator<R> order, List<R> best) {
		if(index == groups.size()) {
			Optional<R> evaluated = evaluator.apply(List.copyOf(selected));
			if(evaluated.isPresent() && (best.isEmpty() || order.compare(evaluated.get(), best.get(0)) < 0)) {
				best.clear();
				best.add(evaluated.get());
			}
			return;
		}
		// Baseline representative is a real choice and is evaluated before explicit
		// rows, which provides deterministic tie behavior without a greedy shortcut.
		evaluate(groups, index + 1, selected, evaluator, order, best);
		for(T candidate : groups.get(index)) {
			selected.add(candidate);
			evaluate(groups, index + 1, selected, evaluator, order, best);
			selected.remove(selected.size() - 1);
		}
	}
}
