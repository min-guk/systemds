/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Locks the reachable DP collaborators against reintroducing dead OracleUtils dependency edges. */
public class CampaignBDpOracleUtilsDeadImportContractTest {
	private static final Path DP_PACKAGE = Path.of("").toAbsolutePath().normalize().resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp");
	private static final List<String> COLLABORATORS = List.of(
		"FederatedPlannerDpCostEstimator.java",
		"FederatedPlannerDpMemoTable.java",
		"FederatedPlannerDpRewireTransTable.java");

	@Test
	public void reachableDpCollaboratorsDoNotOwnOracleUtils() throws Exception {
		List<String> owners = COLLABORATORS.stream()
			.filter(file -> containsOracleUtilsImport(DP_PACKAGE.resolve(file)))
			.toList();
		Assert.assertEquals("reachable DP collaborators retain dead OracleUtils dependency edges", List.of(), owners);
	}

	private static boolean containsOracleUtilsImport(Path source) {
		try {
			return Files.readString(source).contains(
				"import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;");
		}
		catch(Exception e) {
			throw new AssertionError("could not read DP collaborator " + source, e);
		}
	}
}
