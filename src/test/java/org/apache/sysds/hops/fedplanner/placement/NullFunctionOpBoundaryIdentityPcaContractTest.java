/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.junit.Assert;
import org.junit.Test;

/** A PCA-like FunctionOp with absent input-name metadata must not fail neutral analysis. */
public class NullFunctionOpBoundaryIdentityPcaContractTest {
	@Test
	public void pcaFunctionOpWithNullInputNamesBuildsTypedAbsentBoundaryInsteadOfNpe() {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			PlacementIdentityKnownEqualityContractTest.program(
				PlacementIdentityKnownEqualityContractTest.functionCall(null, new String[] {"components"})));

		Node input = PlacementIdentityKnownEqualityContractTest.onlyBoundary(analysis, NodeKind.FUNCTION_INPUT);
		Assert.assertTrue("null FunctionOp input-name metadata must be represented as typed ABSENT",
			input.key().canonicalSourceOrigin().endsWith(":input:<ABSENT>"));
		Assert.assertTrue("ABSENT metadata must not prove legal alternatives",
			input.legalAlternatives().isEmpty());
		Assert.assertEquals("analysis projection must remain graph-aligned",
			analysis.graph().nodes().size(), analysis.occurrences().size());
	}
}
