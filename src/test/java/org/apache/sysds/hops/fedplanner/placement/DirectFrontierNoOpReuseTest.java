package org.apache.sysds.hops.fedplanner.placement;

import org.junit.Assert;
import org.junit.Test;

public class DirectFrontierNoOpReuseTest {
	@Test
	public void strictNoOpFrontierPreservesTheFullRecomputeOracle() throws Exception {
		SearchSpaceMetrics incrementalMetrics = new SearchSpaceMetrics();
		PlacementAnalysis incremental = new NeutralPlacementGraphBuilder(
			null, incrementalMetrics, true).buildAnalysis(
				NeutralPlacementFixedPointCompositionTest.compileProtected(
					NeutralPlacementFixedPointCompositionTest.ACTIONS));
		SearchSpaceMetrics fullMetrics = new SearchSpaceMetrics();
		PlacementAnalysis full = new NeutralPlacementGraphBuilder(
			null, fullMetrics, false).buildAnalysis(
				NeutralPlacementFixedPointCompositionTest.compileProtected(
					NeutralPlacementFixedPointCompositionTest.ACTIONS));

		Assert.assertEquals(full.analysisFingerprint(), incremental.analysisFingerprint());
		Assert.assertEquals(full.graph().normalizedSignatureWithLegalAssignments(),
			incremental.graph().normalizedSignatureWithLegalAssignments());
		Assert.assertEquals(full.candidateRuleFacts().orderedFacts(),
			incremental.candidateRuleFacts().orderedFacts());
		Assert.assertEquals(full.logicalTransientInputsInCanonicalOrder(),
			incremental.logicalTransientInputsInCanonicalOrder());
		Assert.assertTrue("the repeated direct frontier must avoid the four known complete reruns",
			fullMetrics.snapshot().directClosureFullPasses()
				- incrementalMetrics.snapshot().directClosureFullPasses() >= 4);
	}
}
