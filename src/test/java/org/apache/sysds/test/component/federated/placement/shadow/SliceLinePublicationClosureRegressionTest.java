/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.nio.file.Path;
import java.util.Map;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Exercises the frozen loop-carried transient reader after final publication. */
public class SliceLinePublicationClosureRegressionTest {
	@Test
	public void frozenSliceLineKeepsExactReaderAndSourceRelations() {
		String catalog = System.getProperty("g009.sliceline.catalog");
		String evaluation = System.getProperty("g009.sliceline.evaluation");
		Assume.assumeTrue("frozen external capture inputs are not configured",
			catalog != null || evaluation != null);
		Assert.assertNotNull("set -Dg009.sliceline.catalog", catalog);
		Assert.assertNotNull("set -Dg009.sliceline.evaluation", evaluation);
		Map<String,Object> result = PlanningNativeModelCapture.captureResult(
			Path.of(catalog), Path.of(evaluation), "cell_15d0f7daf5f68a637fe2", true);
		Assert.assertEquals(result.toString(), "COMPLETE", result.get("status"));
	}
}
