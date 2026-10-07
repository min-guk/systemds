/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PlannerRuntimeGenerationReceiptTest {
	@Before
	public void reset() {
		System.setProperty(PlannerRuntimePlacementAudit.PROPERTY, "true");
		PlannerRuntimePlacementAudit.resetForTesting();
	}

	@After
	public void cleanup() {
		PlannerRuntimePlacementAudit.resetForTesting();
		System.clearProperty(PlannerRuntimePlacementAudit.PROPERTY);
	}

	@Test
	public void replacementRetainsInitialGenerationAndRepeatedCommitsInOrder() {
		PlannerRuntimePlacementAudit.installForTesting("initial", List.of(), List.of());
		var firstSnapshot = PlannerRuntimePlacementAudit.authorityGenerations();
		PlannerRuntimePlacementAudit.installForTesting("recompiled", List.of(), List.of());
		PlannerRuntimePlacementAudit.installForTesting("recompiled", List.of(), List.of());
		var generations = PlannerRuntimePlacementAudit.authorityGenerations();
		Assert.assertEquals(List.of("initial", "recompiled", "recompiled"), generations.stream()
			.map(PlannerRuntimePlacementAudit.AuthorityGeneration::planFingerprint).toList());
		Assert.assertEquals(List.of(0, 1, 2), generations.stream()
			.map(PlannerRuntimePlacementAudit.AuthorityGeneration::sequence).toList());
		Assert.assertEquals(1, firstSnapshot.size());
		Assert.assertThrows(UnsupportedOperationException.class, () -> firstSnapshot.clear());
		Assert.assertTrue(PlannerRuntimePlacementAudit.display().contains("plan=recompiled authorityGenerations=2"));
	}

	@Test
	public void freshInvocationAndUnplannedCompilationClearGenerationHistory() {
		PlannerRuntimePlacementAudit.installForTesting("old", List.of(), List.of());
		PlannerRuntimePlacementAudit.resetForTesting();
		Assert.assertTrue(PlannerRuntimePlacementAudit.authorityGenerations().isEmpty());
		PlannerRuntimePlacementAudit.installForTesting("new", List.of(), List.of());
		Assert.assertEquals(0, PlannerRuntimePlacementAudit.authorityGenerations().get(0).sequence());
		PlannerRuntimePlacementAudit.clearForUnplannedCompilation();
		Assert.assertTrue(PlannerRuntimePlacementAudit.authorityGenerations().isEmpty());
	}
}
