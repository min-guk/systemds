/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED for graph-free MinST root cutover and typed diagnostics seam. */
public class CampaignBR6MinStRootCutoverRedTest {
	@Test
	public void minStRootClosureUsesExactFactsSelectorAndTypedDiagnosticsOnly() throws Exception {
		Path root = Path.of("").toAbsolutePath().normalize().resolve("src/main/java/org/apache/sysds/hops/fedplanner");
		Map<String,CampaignBPlannerOwnershipClosure.Unit> index = CampaignBPlannerOwnershipClosure.index(root);
		List<CampaignBPlannerOwnershipClosure.Unit> closure = CampaignBPlannerOwnershipClosure.closure(
			"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut", index);
		List<String> names = closure.stream().map(CampaignBPlannerOwnershipClosure.Unit::fqcn).toList();
		Assert.assertTrue("R6_EXACT_FACTS_MISSING", names.stream().anyMatch(n -> n.endsWith("MinStExactCostFactsProducer")));
		Assert.assertTrue("R6_SELECTOR_MISSING", names.stream().anyMatch(n -> n.endsWith("MinStExactSelector")));
		Assert.assertFalse("R6_LEGACY_GRAPH_REACHABLE", names.stream().anyMatch(n -> n.endsWith("FederatedPlanMinSTGraph")));
		Assert.assertFalse("R6_LEGACY_REWIRE_REACHABLE", names.stream().anyMatch(n -> n.endsWith("FederatedPlanMinSTRewire")));
		Assert.assertFalse("R6_LEGACY_COST_REACHABLE", names.stream().anyMatch(n -> n.endsWith("FederatedPlanMinSTCostEstimator")));
	}
}
