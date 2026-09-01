/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Guards the production ownership closure of the categorical exact planner. */
public class CampaignBR6ExactOwnershipClosureTest {
	@Test
	public void exactRootClosureUsesOnlyTheCategoricalPhysicalPipeline() throws Exception {
		Path root = Path.of("").toAbsolutePath().normalize().resolve("src/main/java/org/apache/sysds/hops/fedplanner");
		Map<String,CampaignBPlannerOwnershipClosure.Unit> index = CampaignBPlannerOwnershipClosure.index(root);
		List<CampaignBPlannerOwnershipClosure.Unit> closure = CampaignBPlannerOwnershipClosure.closure(
			"org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.FederatedPlanExact", index);
		List<String> names = closure.stream().map(CampaignBPlannerOwnershipClosure.Unit::fqcn).toList();
		List<String> violations = new java.util.ArrayList<>();
		for(String required : List.of("ExactPhysicalModel", "ExactPhysicalCostModel",
			"ExactPhysicalOptimizer", "ExactPhysicalSelection",
			"ExactPhysicalPlacementProjector", "ExactPlacementAdapter"))
			if(names.stream().noneMatch(n -> n.endsWith(required)))
				violations.add("R6_PHYSICAL_OWNER_MISSING|" + required);
		violations.addAll(CampaignBPlannerOwnershipClosure.violations(closure));
		Assert.assertTrue("R6_OWNERSHIP_SET|" + violations, violations.isEmpty());
	}
}
