/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class SearchSpaceLiveMetricsTest {
	@Test
	public void nestedOpenSelfTimesPartitionWallWithoutMutatingClosedCounters() {
		SearchSpaceMetrics m = new SearchSpaceMetrics();
		var outer = m.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
		var child = m.startPhase(SearchSpaceMetrics.Phase.DIRECT_BINDING);
		var leaf = m.startPhase(SearchSpaceMetrics.Phase.PROOF_GROUNDING);
		List<SearchSpaceMetrics.LivePhase> live = m.livePhaseSnapshot();
		long self = live.stream().mapToLong(SearchSpaceMetrics.LivePhase::exclusiveWallNanos).sum();
		Assert.assertEquals(live.get(SearchSpaceMetrics.Phase.ANALYSIS.ordinal()).inclusiveWallNanos(), self);
		Assert.assertEquals(3, live.stream().mapToLong(SearchSpaceMetrics.LivePhase::activeCount).sum());
		Assert.assertEquals(0, live.stream().mapToLong(SearchSpaceMetrics.LivePhase::completedCalls).sum());
		m.finishPhase(SearchSpaceMetrics.Phase.PROOF_GROUNDING, leaf);
		m.finishPhase(SearchSpaceMetrics.Phase.DIRECT_BINDING, child);
		m.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, outer);
		var closed = m.attributionSnapshot();
		m.livePhaseSnapshot();
		Assert.assertEquals(closed, m.attributionSnapshot());
		Assert.assertEquals(3, closed.phases().stream().mapToLong(SearchSpaceMetrics.PhaseMeasurement::calls).sum());
	}

	@Test
	public void outputIsOptInAndAvailableBeforeAnalysisReturns() throws Exception {
		String old = System.getProperty("sysds.fedplanner.liveMetrics");
		PrintStream stderr = System.err;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try(PrintStream capture = new PrintStream(bytes)) {
			System.setErr(capture);
			System.clearProperty("sysds.fedplanner.liveMetrics");
			SearchSpaceMetrics disabled = new SearchSpaceMetrics();
			var off = disabled.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
			disabled.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, off);
			Assert.assertEquals(0, bytes.size());
			System.setProperty("sysds.fedplanner.liveMetrics", "true");
			SearchSpaceMetrics enabled = new SearchSpaceMetrics();
			var on = enabled.startPhase(SearchSpaceMetrics.Phase.ANALYSIS);
			Assert.assertTrue(bytes.toString().contains("SEARCH_SPACE_LIVE|"));
			Assert.assertTrue(bytes.toString().contains("activeCount=1"));
			enabled.finishPhase(SearchSpaceMetrics.Phase.ANALYSIS, on);
			Assert.assertTrue(bytes.toString().contains("terminal=true"));
		}
		finally {
			System.setErr(stderr);
			if(old == null) System.clearProperty("sysds.fedplanner.liveMetrics");
			else System.setProperty("sysds.fedplanner.liveMetrics", old);
		}
	}
}
