/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Passing, production-independent proof for the unique B0 two-decision literal. */
public class MinStExactTwoDecisionOracleTest {
	@Test
	public void uniqueTwoDecisionLiteralHasFixedRawObjectiveAndSelection() {
		MinStExactTwoDecisionOracle.Selection selected = MinStExactTwoDecisionOracle.enumerateUniqueFixture();
		Assert.assertEquals(MinStExactTwoDecisionOracle.bits(3.0), selected.objectiveBits());
		Assert.assertEquals(List.of(MinStExactTwoDecisionOracle.A_ID, MinStExactTwoDecisionOracle.B_ID),
			selected.sourceNodeIds());
		Assert.assertEquals(3, selected.mask());
		MinStExactTwoDecisionOracle.validateObjective(MinStExactTwoDecisionOracle.UNIQUE_EDGES, selected);
	}

	@Test
	public void literalOracleRejectsCapacityTotalAndSourceCorruption() {
		MinStExactTwoDecisionOracle.Selection selected = MinStExactTwoDecisionOracle.enumerateUniqueFixture();
		List<MinStExactTwoDecisionOracle.Edge> mutated = new ArrayList<>(MinStExactTwoDecisionOracle.UNIQUE_EDGES);
		MinStExactTwoDecisionOracle.Edge selectedCutEdge = mutated.get(1);
		mutated.set(1, new MinStExactTwoDecisionOracle.Edge(selectedCutEdge.from(), selectedCutEdge.to(),
			selectedCutEdge.capacityBits() ^ 1L));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateObjective(mutated, selected));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateObjective(MinStExactTwoDecisionOracle.UNIQUE_EDGES,
			new MinStExactTwoDecisionOracle.Selection(selected.objectiveBits() ^ 1L,
				selected.sourceNodeIds(), selected.mask())));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateCanonicalSourceIds(
			List.of(MinStExactTwoDecisionOracle.B_ID, MinStExactTwoDecisionOracle.A_ID)));
		assertRejects(() -> MinStExactTwoDecisionOracle.validateCanonicalSourceIds(
			List.of(MinStExactTwoDecisionOracle.A_ID, MinStExactTwoDecisionOracle.A_ID)));
	}

	private static void assertRejects(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected literal corruption rejection");
		}
		catch(IllegalArgumentException expected) {
			// expected
		}
	}
}
