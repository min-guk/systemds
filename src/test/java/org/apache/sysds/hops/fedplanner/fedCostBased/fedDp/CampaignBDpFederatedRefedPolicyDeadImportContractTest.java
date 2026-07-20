/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Locks the reachable DP collaborators against dead FederatedRefedPolicy dependency edges. */
public class CampaignBDpFederatedRefedPolicyDeadImportContractTest {
	private static final Path DP_PACKAGE = Path.of("").toAbsolutePath().normalize().resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp");
	private static final List<String> COLLABORATORS = List.of(
		"FederatedPlannerDpMemoTable.java",
		"FederatedPlannerDpRewireTransTable.java");

	@Test
	public void reachableDpCollaboratorsDoNotOwnFederatedRefedPolicy() {
		List<String> owners = COLLABORATORS.stream()
			.filter(file -> containsFederatedRefedPolicyImport(DP_PACKAGE.resolve(file)))
			.toList();
		Assert.assertEquals("reachable DP collaborators retain dead FederatedRefedPolicy dependency edges",
			List.of(), owners);
	}

	private static boolean containsFederatedRefedPolicyImport(Path source) {
		try {
			return Files.readString(source).contains(
				"import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;");
		}
		catch(Exception e) {
			throw new AssertionError("could not read DP collaborator " + source, e);
		}
	}
}
