/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import static org.junit.Assert.assertFalse;
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
	private static final Path EMISSION = MAIN.resolve(
		"fedplanner/placement/PlacementEmissionTransaction.java");
	private static final Path DP = MAIN.resolve(
		"fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");
	private static final Path DP_COST = MAIN.resolve(
		"fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java");
	private static final Path MINST = MAIN.resolve(
		"fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java");
	private static final Path FEDALL = MAIN.resolve(
		"fedplanner/fedAll/FederatedPlannerFedAll.java");
	private static final Path HEURISTIC = MAIN.resolve(
		"fedplanner/fedHeuristic/FederatedPlannerFedHeuristic.java");

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
	public void fedAllAndHeuristicPolicySelectionsAreAuditable() throws Exception {
		String fedAll = Files.readString(FEDALL);
		for(String stage : new String[] {"FedAll-PolicySummary", "FedAll-Select"})
			assertTrue("missing FedAll policy trace stage " + stage,
				fedAll.contains("\"" + stage + "\""));
		assertTrue("FedAll trace must expose its lexicographic objective",
			fedAll.contains("fedCount=") && fedAll.contains("foutCount=")
				&& fedAll.contains("relocationCount="));

		String heuristic = Files.readString(HEURISTIC);
		for(String stage : new String[] {"Heuristic-PolicySummary", "Heuristic-Select"})
			assertTrue("missing Heuristic policy trace stage " + stage,
				heuristic.contains("\"" + stage + "\""));
		assertTrue("Heuristic trace must expose its pathwise demotion policy",
			heuristic.contains("markerCount=") && heuristic.contains("localPrefixCount=")
				&& heuristic.contains("frontierEdgeCount="));
	}

	@Test
	public void everyPlannerExposesExactEmissionAuthority() throws Exception {
		String source = Files.readString(EMISSION);
		assertTrue("emission audit lacks a bounded per-occurrence record",
			source.contains("\"Emission-Select\""));
		assertTrue("emission audit lacks a bounded exact candidate-row record",
			source.contains("\"Emission-Candidate\""));
		assertTrue("emission audit lacks one exact transaction summary",
			source.contains("\"Emission-Summary\""));
		for(String field : new String[] {"key=", "nodeKind=", "emittedWork=", "compiledOccurrence=",
			"selected=", "inputs=", "executionFType=", "selectedCandidates=", "planFingerprint=",
			"placementFingerprint=", "candidateFingerprint=", "hopMutations=", "registryWrites="})
			assertTrue("emission audit is missing field " + field, source.contains(field));
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

	@Test
	public void highVolumePlanningTraceIsInvocationScopedLazyAndStageBounded() throws Exception {
		String traceSource = Files.readString(TRACE);
		String dpSource = Files.readString(DP);
		String dpCostSource = Files.readString(DP_COST);
		assertTrue("trace API lacks a per-stage record budget",
			traceSource.contains("TRACE_MAX_RECORDS_PER_STAGE"));
		assertTrue("trace API eagerly formats messages that may be suppressed",
			traceSource.contains("void logLazy(Hop hop, String stage, Supplier<String> messageSupplier)"));
		assertTrue("trace budget is not reset for each planner invocation",
			traceSource.contains("void beginInvocation()"));
		assertTrue("trace budget does not emit an omission receipt",
			traceSource.contains("void completeInvocation()")
				&& traceSource.contains("Trace-SuppressionSummary"));

		for(Path entryPoint : new Path[] {IPA, TRANSLATOR}) {
			String source = Files.readString(entryPoint);
			int begin = source.indexOf("FederatedPlannerTrace.beginInvocation()");
			int invoke = source.indexOf("\"Planner-Invoke\"");
			int complete = source.indexOf("FederatedPlannerTrace.completeInvocation()");
			int receipt = source.indexOf("\"Planner-Complete\"");
			assertTrue(entryPoint + " does not scope the trace budget around one planner invocation",
				begin >= 0 && begin < invoke && complete > invoke && receipt > complete);
		}

		for(String stage : new String[] {"DP-OutputDecision-Member", "DP-ParentVariantCandidate",
			"DP-ParentVariantSearch", "DP-ParentVariantResult", "DP-ParentVariantDelta",
			"DP-OutputDecision-Entry"})
			assertTrue("high-volume DP stage still eagerly logs: " + stage,
				dpSource.contains("logLazy(") && lazyCallContains(dpSource, stage));
		for(String stage : new String[] {"DP-BoundaryShare", "DP-StableTRShare", "DP-FoutCpShare"})
			assertTrue("high-volume DP cost stage still eagerly logs: " + stage,
				lazyCallContains(dpCostSource, stage));
		assertFalse("enabling trace must not disable the production parent-variant cache",
			dpSource.contains("if (!trace && parentVariantDeltaCache != null)"));
		assertFalse("enabling trace must not disable the production decision-simulation cache",
			dpSource.contains("simulationDecisionCache == null || FederatedPlannerTrace.isEnabled()"));
		assertFalse("enabling trace must not disable the production transient-share cache",
			dpSource.contains("transientReadPlanShareCache != null && !FederatedPlannerTrace.isEnabled()"));
	}

	private static boolean lazyCallContains(String source, String stage) {
		int offset = 0;
		while((offset = source.indexOf("\"" + stage + "\"", offset)) >= 0) {
			int callStart = source.lastIndexOf("FederatedPlannerTrace.", offset);
			if(callStart >= 0 && source.substring(callStart, offset).contains("logLazy("))
				return true;
			offset += stage.length() + 2;
		}
		return false;
	}
}
