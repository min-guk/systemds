/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FederatedPlannerTraceBudgetTest {
	@Test
	public void stageBudgetEmitsOnlyItsConfiguredLimitAndCountsOmissions() {
		FederatedPlannerTrace.StageRecordBudget budget =
			new FederatedPlannerTrace.StageRecordBudget(2);

		assertTrue(budget.tryAcquire());
		assertTrue(budget.tryAcquire());
		assertFalse(budget.tryAcquire());
		assertFalse(budget.tryAcquire());
		assertEquals(2L, budget.getEmitted());
		assertEquals(2L, budget.getOmitted());
	}

	@Test
	public void nonPositiveLimitStillFailsClosedAtOneRecord() {
		FederatedPlannerTrace.StageRecordBudget budget =
			new FederatedPlannerTrace.StageRecordBudget(0);

		assertTrue(budget.tryAcquire());
		assertFalse(budget.tryAcquire());
		assertEquals(1L, budget.getEmitted());
		assertEquals(1L, budget.getOmitted());
	}
}
