/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.Assert;
import org.junit.Test;

/** RED guard for removing the three heuristic transient-write owner residuals from the DP collector. */
public class CampaignBG011DpTransientWriteCollectorResidualRedTest {
	private static final Path ENUMERATOR = Path.of("").toAbsolutePath().normalize().resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java");

	@Test
	public void transientWriteCollectorContainsNoFallbackOwnerResiduals() throws Exception {
		String source = Files.readString(ENUMERATOR);
		String declaration = "collectTransientWriteChildHops(Hop hop, List<Hop> childHops)";
		int declarationOffset = source.indexOf(declaration);
		Assert.assertTrue("DP transient-write collector declaration is missing", declarationOffset >= 0);
		long declarationLine = source.substring(0, declarationOffset).lines().count();
		String collector = JavaSourceBoundaryScanner.methodBody(
			source, "collectTransientWriteChildHops", "childHops");
		List<String> residuals = JavaSourceTokenScanner.tokens(collector).stream()
			.filter(token -> normalize(token.text()).equals("fallback"))
			.map(token -> (declarationLine + token.line() - 1) + ":" + token.text())
			.toList();
		Assert.assertEquals("G011_DP_TRANSIENT_WRITE_COLLECTOR_RESIDUALS", List.of(), residuals);
	}

	private static String normalize(String identifier) {
		return identifier.replace("_", "").toLowerCase(Locale.ROOT);
	}
}
