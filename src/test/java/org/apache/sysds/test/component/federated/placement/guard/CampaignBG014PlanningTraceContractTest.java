/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Source-level guard for the planning-only audit contract.
 *
 * <p>The Docker planning receipt is meaningful only when it can prove which planner
 * implementation ran, and the production MinST path exposes its physical objective
 * and selected categorical alternatives. DP detail logging must also remain bounded
 * when no explicit hop filter is supplied; otherwise an audit itself can exhaust the
 * experiment host before a runtime plan is emitted.</p>
 */
public class CampaignBG014PlanningTraceContractTest {
	private static final Path MAIN = Path.of("src/main/java/org/apache/sysds/hops");
	private static final Path IPA = MAIN.resolve("ipa/IPAPassRewriteFederatedPlan.java");
	private static final Path TRANSLATOR = Path.of("src/main/java/org/apache/sysds/parser/DMLTranslator.java");
	private static final Path TRACE = MAIN.resolve("fedplanner/fedCostBased/FederatedPlannerTrace.java");
	private static final Path DP = MAIN.resolve(
		"fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");
	private static final Path MINST = MAIN.resolve(
		"fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java");

	@Test
	public void topLevelInvocationIdentifiesConfiguredPlannerAndImplementation() throws Exception {
		for(Path entryPoint : new Path[] {IPA, TRANSLATOR}) {
			String source = Files.readString(entryPoint);
			assertTrue(entryPoint + " planning trace lacks Planner-Invoke",
				source.contains("\"Planner-Invoke\""));
			assertTrue(entryPoint + " planning trace lacks Planner-Complete",
				source.contains("\"Planner-Complete\""));
			assertTrue(entryPoint + " planner implementation identity is not retained",
				source.contains("AFederatedPlanner implementation"));
			assertTrue(entryPoint + " planner implementation class is not logged",
				source.contains("implementation.getClass().getName()"));
		}
	}

	@Test
	public void productionMinStPhysicalPathIsAuditable() throws Exception {
		String source = Files.readString(MINST);
		int selection = source.indexOf("MinStExactPhysicalSelection.create");
		int trace = source.indexOf("tracePhysicalSelection");
		int projection = source.indexOf("MinStExactPhysicalPlacementProjector.project");
		assertTrue("MinST trace must observe the production physical selection before projection",
			selection >= 0 && trace > selection && projection > trace);
		for(String stage : new String[] {"MinST-PhysicalOptimize", "MinST-PhysicalSelect",
			"MinST-PhysicalAlternative", "MinST-PhysicalComplete"})
			assertTrue("missing production MinST stage " + stage, source.contains("\"" + stage + "\""));
		assertTrue("MinST trace must expose fixed-others alternative deltas",
			source.contains("fixedOthersDelta"));
	}

	@Test
	public void dpDecisionMapDetailIsBoundedWithoutExplicitHopFilter() throws Exception {
		String traceSource = Files.readString(TRACE);
		String dpSource = Files.readString(DP);
		assertTrue("trace API does not expose explicit hop-filter state",
			traceSource.contains("boolean hasExplicitHopFilter()"));
		assertTrue("DP unfiltered trace does not collapse repeated score targets",
			dpSource.contains("FederatedPlannerTrace.hasExplicitHopFilter()"));
		assertTrue("DP root detail does not use the configured edge budget",
			dpSource.contains("FederatedPlannerTrace.getMaxEdgeLogsPerHop()"));
		for(String summary : new String[] {"DP-DecisionMap-RootSummary",
			"DP-DecisionMap-AltRootSummary", "DP-DecisionMap-BundleRootSummary"})
			assertTrue("missing bounded-detail summary " + summary,
				dpSource.contains("\"" + summary + "\""));
	}
}
