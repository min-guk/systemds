/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnosticsProducer;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnosticsLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ContributionKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EdgeContribution;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelector;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Focused sentinel for the graph-free exact MinST diagnostics seam. */
public class CampaignBR8MinStDiagnosticsSeamTest {
	@Test
	public void diagnosticsProjectExactUniqueSelectionWithoutLegacyDependencies() throws Exception {
		PlacementAnalysis analysis = actualRootAnalysis();
		List<CompiledHopKey> scope = scope(analysis);
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope);
		MinStExactSelection selection = MinStExactSelector.select(facts);

		MinStDiagnostics diagnostics = MinStDiagnosticsProducer.project(analysis, facts, selection);
		Assert.assertEquals("R8_DIAGNOSTICS_OBJECTIVE_PASS_THROUGH", selection.objectiveBits(),
			diagnostics.selectedObjectiveBits());
		Assert.assertEquals("R8_DIAGNOSTICS_SOURCE_PASS_THROUGH", selection.sourcePartitionNodeIds(),
			diagnostics.sourcePartitionNodeIds());
		Assert.assertEquals("R8_DIAGNOSTICS_SUMMARY_SCOPE_CARDINALITY", scope.size(),
			diagnostics.optimalSummariesInMemoOrder().size());
		Assert.assertEquals("R8_DIAGNOSTICS_HOP_FACT_CARDINALITY", scope.size(),
			diagnostics.hopsInSortedIdOrder().size());
		assertSelectedStateFieldsPassThrough(analysis, facts, selection, diagnostics);
		assertExactCrossingCosts(analysis, facts, selection, diagnostics);
		assertRejectsNonUniqueOrMalformedSelection(facts, selection);
		Assert.assertThrows("R8_DIAGNOSTICS_LOGGER_NULL_REJECTION", NullPointerException.class,
			() -> MinStDiagnosticsLogger.log(null));
		assertGraphFreeSourceSeam();
	}

	private static void assertSelectedStateFieldsPassThrough(PlacementAnalysis analysis,
		MinStExactCostFacts facts, MinStExactSelection selection, MinStDiagnostics diagnostics) {
		for(int index = 0; index < facts.decisionFactsInScopeOrder().size(); index++) {
			DecisionFact decision = facts.decisionFactsInScopeOrder().get(index);
			PlacementState selected = selection.selectedStatesInScopeOrder().get(index);
			Hop hop = analysis.hop(decision.key()).orElseThrow();
			MinStDiagnostics.OptimalSummary summary = diagnostics.optimalSummariesInMemoOrder().get(index);
			Assert.assertEquals("R8_DIAGNOSTICS_SUMMARY_HOP_ORDER", hop.getHopID(), summary.hopId());
			Assert.assertEquals("R8_DIAGNOSTICS_SUMMARY_OP", hop.getOpString(), summary.opString());
			Assert.assertEquals("R8_DIAGNOSTICS_SELECTED_EXEC", selected.execType().name(),
				summary.forcedExecNameOrNull());
			Assert.assertEquals("R8_DIAGNOSTICS_SELECTED_OUTPUT", selected.output().name(),
				summary.outputNameOrNull());
			Assert.assertEquals("R8_DIAGNOSTICS_SELECTED_FTYPE",
				selected.fType() == null ? null : selected.fType().name(), summary.fTypeNameOrNull());
			Assert.assertNull("R8_DIAGNOSTICS_PRIVACY_IS_NOT_IN_EXACT_SEAM", summary.privacyNameOrNull());
		}
	}

	private static void assertExactCrossingCosts(PlacementAnalysis analysis, MinStExactCostFacts facts,
		MinStExactSelection selection, MinStDiagnostics diagnostics) {
		IdentityHashMap<CompiledHopKey, double[]> expected = new IdentityHashMap<>();
		for(CompiledHopKey key : facts.orderedScope())
			expected.put(key, new double[2]);
		double global = 0.0;
		for(DirectedEdgeFact edge : facts.directedEdgesInDerivationOrder()) {
			boolean fromSource = edge.fromNodeId() == facts.sourceNodeId()
				|| selection.sourcePartitionNodeIds().contains(edge.fromNodeId());
			boolean toSource = edge.toNodeId() != facts.sinkNodeId()
				&& selection.sourcePartitionNodeIds().contains(edge.toNodeId());
			if(!fromSource || toSource)
				continue;
			for(EdgeContribution contribution : edge.contributionsInDerivationOrder()) {
				double value = Double.longBitsToDouble(contribution.costBits());
				global += value;
				double[] owner = expected.get(contribution.ownerKey());
				Assert.assertNotNull("R8_DIAGNOSTICS_FOREIGN_OWNER", owner);
				owner[isNetwork(contribution.kind()) ? 1 : 0] += value;
			}
		}
		Assert.assertEquals("R8_DIAGNOSTICS_GLOBAL_TOTAL_REPLAYS_SELECTOR",
			selection.objectiveBits(), Double.doubleToRawLongBits(global));
		Map<Long, MinStDiagnostics.HopFacts> actualByHop = new java.util.HashMap<>();
		for(MinStDiagnostics.HopFacts hopFacts : diagnostics.hopsInSortedIdOrder())
			actualByHop.put(hopFacts.hopId(), hopFacts);
		for(CompiledHopKey key : facts.orderedScope()) {
			Hop hop = analysis.hop(key).orElseThrow();
			MinStDiagnostics.HopFacts actual = actualByHop.get(hop.getHopID());
			double[] owner = expected.get(key);
			Assert.assertEquals("R8_DIAGNOSTICS_SELF_COST", Double.doubleToRawLongBits(owner[0]),
				actual.selfCostBits());
			Assert.assertEquals("R8_DIAGNOSTICS_NETWORK_COST", Double.doubleToRawLongBits(owner[1]),
				actual.networkCostBits());
			Assert.assertEquals("R8_DIAGNOSTICS_TOTAL_COST", Double.doubleToRawLongBits(owner[0] + owner[1]),
				actual.totalCostBits());
			Assert.assertEquals("R8_DIAGNOSTICS_COMPUTE_WEIGHT_EXPLICIT_ZERO",
				Double.doubleToRawLongBits(0.0), actual.computeWeightBits());
			Assert.assertEquals("R8_DIAGNOSTICS_TABULAR_COST_EXPLICIT_ZERO",
				Double.doubleToRawLongBits(0.0), actual.tabularOpCostBits());
		}
	}

	private static void assertRejectsNonUniqueOrMalformedSelection(MinStExactCostFacts facts,
		MinStExactSelection selection) throws Exception {
		Constructor<MinStExactSelection> ctor = MinStExactSelection.class.getDeclaredConstructor(long.class,
			List.class, List.class, List.class, String.class, List.class);
		ctor.setAccessible(true);
		MinStExactSelection tie = ctor.newInstance(selection.objectiveBits(), selection.sourcePartitionNodeIds(),
			selection.selectedStatesInScopeOrder(), selection.obligationReceiptsInOrder(),
			MinStExactSelection.TIE_UNSPECIFIED, selection.minimumSourcePartitionCertificates());
		Assert.assertThrows("R8_DIAGNOSTICS_REJECTS_TIE", IllegalArgumentException.class,
			() -> MinStDiagnosticsProducer.project(facts.analysis(), facts, tie));
		List<PlacementState> shortStates = new ArrayList<>(selection.selectedStatesInScopeOrder());
		shortStates.remove(shortStates.size() - 1);
		MinStExactSelection malformed = ctor.newInstance(selection.objectiveBits(), selection.sourcePartitionNodeIds(),
			shortStates, selection.obligationReceiptsInOrder(), MinStExactSelection.UNIQUE,
			selection.minimumSourcePartitionCertificates());
		Assert.assertThrows("R8_DIAGNOSTICS_REJECTS_STATE_CARDINALITY", IllegalArgumentException.class,
			() -> MinStDiagnosticsProducer.project(facts.analysis(), facts, malformed));
	}

	private static boolean isNetwork(ContributionKind kind) {
		return kind == ContributionKind.UPLOAD || kind == ContributionKind.DOWNLOAD
			|| kind == ContributionKind.HARD_UPLOAD_OR || kind == ContributionKind.HARD_DOWNLOAD_OR
			|| kind == ContributionKind.PRICE_UPLOAD_OR || kind == ContributionKind.PRICE_DOWNLOAD_OR;
	}

	private static void assertGraphFreeSourceSeam() throws Exception {
		Path root = Path.of("src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut");
		String logger = Files.readString(root.resolve("MinStDiagnosticsLogger.java"));
		for(String forbidden : List.of("FederatedPlannerLogger", "FederatedPlanMinSTGraph", "PlacementAnalysis",
			"MinStExact"))
			Assert.assertFalse("R8_DIAGNOSTICS_LOGGER_LEGACY_IMPORT|" + forbidden,
				logger.contains(forbidden));
		for(Path source : Files.walk(Path.of("src/main/java")).filter(path -> path.toString().endsWith(".java"))
			.toList()) {
			if(source.endsWith("MinStDiagnosticsProducer.java") || source.endsWith("MinStDiagnosticsLogger.java"))
				continue;
			String text = Files.readString(source);
			Assert.assertFalse("R8_DIAGNOSTICS_PRODUCTION_ROOT_REFERENCE|" + source,
				text.contains("MinStDiagnosticsProducer") || text.contains("MinStDiagnosticsLogger"));
		}
	}

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream().map(HopOccurrenceProjection::key).toList();
	}

	private static PlacementAnalysis actualRootAnalysis() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"write(X,\"out-r8-diagnostics\",format=\"binary\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new java.util.HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}
}
