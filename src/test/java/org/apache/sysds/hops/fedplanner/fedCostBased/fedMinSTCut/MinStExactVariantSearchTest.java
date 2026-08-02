/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;

public class MinStExactVariantSearchTest {
	private record Result(List<String> selected, double objective) { }

	@Test
	public void jointImprovementIsFoundEvenWhenEverySingleVariantIsWorse() {
		Result best = MinStExactVariantSearch.select(List.of(List.of("A"), List.of("B")), 16,
			selected -> Optional.of(new Result(selected, objective(selected))),
			Comparator.comparingDouble(Result::objective));

		Assert.assertEquals(List.of("A", "B"), best.selected());
		Assert.assertEquals(5.0, best.objective(), 0.0);
	}

	@Test
	public void oversizedVariantSpaceFailsInsteadOfFallingBackToGreedySearch() {
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> MinStExactVariantSearch.select(
				List.of(List.of("A", "B"), List.of("C", "D")), 8,
				selected -> Optional.of(new Result(selected, 0.0)),
				Comparator.comparingDouble(Result::objective)));
		Assert.assertTrue(error.getMessage().startsWith("MINST_EXACT_ROW_VARIANT_SPACE_TOO_LARGE"));
	}

	private static double objective(List<String> selected) {
		if(selected.contains("A") && selected.contains("B"))
			return 5.0;
		if(selected.isEmpty())
			return 10.0;
		return 11.0;
	}
}
