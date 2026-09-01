/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.BoundaryName;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.BoundaryNameKind;
import org.junit.Assert;
import org.junit.Test;

/** Unknown function-boundary metadata must be typed and must not prove legality. */
public class PlacementIdentityUnknownTypedRejectionContractTest {
	@Test
	public void unnamedBoundaryIsTypedAndCannotCarryFabricatedName() {
		BoundaryName unnamed = BoundaryName.unnamed();

		Assert.assertEquals(BoundaryNameKind.UNNAMED, unnamed.kind());
		Assert.assertNull("unnamed metadata must not carry a fabricated name", unnamed.name());
		Assert.assertEquals("<UNNAMED>", unnamed.canonicalSourceOriginToken());
	}

	@Test
	public void absentBoundaryIsTypedAndCannotCarryFabricatedName() {
		BoundaryName absent = BoundaryName.absent();

		Assert.assertEquals(BoundaryNameKind.ABSENT, absent.kind());
		Assert.assertNull("absent metadata must not carry a fabricated name", absent.name());
		Assert.assertEquals("<ABSENT>", absent.canonicalSourceOriginToken());
	}

	@Test
	public void unnamedFunctionBoundaryHasNoLegalAlternatives() {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			PlacementIdentityKnownEqualityContractTest.program(
				PlacementIdentityKnownEqualityContractTest.functionCall(new String[] {null}, new String[] {"Y"})));
		Node input = PlacementIdentityKnownEqualityContractTest.onlyBoundary(analysis, NodeKind.FUNCTION_INPUT);

		Assert.assertFalse("unknown metadata must not be emitted as legal work", input.emittedWork());
		Assert.assertTrue("unknown metadata must not prove any legal placement", input.legalAlternatives().isEmpty());
		Assert.assertTrue("unnamed metadata must be explicitly visible in the source origin",
			input.key().canonicalSourceOrigin().endsWith(":input:<UNNAMED>"));
		Assert.assertEquals("UNNAMED", input.valueVersion().lexicalVariable());
		assertUnknownMetadataExclusion(input);
	}

	@Test
	public void absentFunctionBoundaryHasNoLegalAlternatives() {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			PlacementIdentityKnownEqualityContractTest.program(
				PlacementIdentityKnownEqualityContractTest.functionCall(null, new String[] {"Y"})));
		Node input = PlacementIdentityKnownEqualityContractTest.onlyBoundary(analysis, NodeKind.FUNCTION_INPUT);

		Assert.assertFalse("absent metadata must not be emitted as legal work", input.emittedWork());
		Assert.assertTrue("absent metadata must not prove any legal placement", input.legalAlternatives().isEmpty());
		Assert.assertTrue("absent metadata must be explicitly visible in the source origin",
			input.key().canonicalSourceOrigin().endsWith(":input:<ABSENT>"));
		Assert.assertEquals("ABSENT", input.valueVersion().lexicalVariable());
		assertUnknownMetadataExclusion(input);
	}

	private static void assertUnknownMetadataExclusion(Node node) {
		Assert.assertFalse("unknown metadata must retain rejection evidence", node.exclusions().isEmpty());
		for(Exclusion exclusion : node.exclusions())
			Assert.assertEquals("unknown metadata must be the rejection reason",
				ReasonCode.UNKNOWN_METADATA, exclusion.reasonCode());
	}
}
