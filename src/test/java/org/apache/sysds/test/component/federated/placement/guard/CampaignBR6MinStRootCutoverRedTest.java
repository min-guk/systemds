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
		List<String> violations = new java.util.ArrayList<>();
		for(String required : List.of("MinStExactPhysicalModel", "MinStExactCostFactsProducer",
			"MinStExactPhysicalOptimizer", "MinStExactPhysicalSelection",
			"MinStExactPhysicalPlacementProjector", "MinStPlacementAdapter"))
			if(names.stream().noneMatch(n -> n.endsWith(required)))
				violations.add("R6_PHYSICAL_OWNER_MISSING|" + required);
		for(String forbidden : List.of("FederatedPlanMinSTGraph", "FederatedPlanMinSTRewire",
			"FederatedPlanMinSTCostEstimator", "FederatedPlanMinSTPlanner"))
			if(names.stream().anyMatch(n -> n.endsWith(forbidden))) violations.add("R6_LEGACY_REACHABLE|" + forbidden);
		Assert.assertTrue("R6_OWNERSHIP_SET|" + violations, violations.isEmpty());
	}
}
