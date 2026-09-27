/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalSemanticBindingOracleTest {
	@Test public void exportsBoundedTinyFixtureOracles() throws Exception {
		String configured = System.getProperty("g009.semanticBindingOutput");
		Path output = configured == null ? Files.createTempDirectory("g009-e-semantic-")
			: Path.of(configured);
		for(String fixture : new String[] {"B-01", "B-02", "B-21"}) {
			var result = ExactPhysicalSemanticBindingOracle.exportFixture(fixture, output);
			Assert.assertEquals(64, ((String) result.get("modelSha256")).length());
			Assert.assertEquals(64, ((String) result.get("oracleSha256")).length());
			Assert.assertTrue(Files.size(Path.of((String) result.get("modelPath"))) > 0);
			Assert.assertTrue(Files.size(Path.of((String) result.get("oraclePath"))) > 0);
		}
	}
}
