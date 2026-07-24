/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireConsumerEdge;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Authoritative compile-time RED for the production post-rewire occurrence snapshot. */
public class CampaignBG014RewireOccurrenceSnapshotRedTest {

	@Test
	public void standaloneRuntimeRecompileOccurrenceIsPhysicalProducerNotSemanticClone() {
		DMLProgram program = standaloneRuntimeRecompileProgram();
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<NeutralPlacementGraph.Node> recompileNodes = analysis.graph().nodes().stream()
			.filter(node -> "recompile".equals(node.key().recompileContext()))
			.filter(node -> node.valueVersion().versionKind().name().equals("CLONE_RECOMPILE"))
			.toList();
		Assert.assertEquals("standalone fixture owns one runtime-recompile occurrence", 1, recompileNodes.size());
		NeutralPlacementGraph.Node recompiled = recompileNodes.get(0);
		Assert.assertEquals("standalone runtime recompile should keep its physical producer kind",
			NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE, recompiled.kind());
		Assert.assertTrue("standalone runtime recompile must still exclude CP/FOUT",
			recompiled.exclusions().stream().anyMatch(exclusion ->
				exclusion.reasonCode() == NeutralPlacementGraph.ReasonCode.RECOMPILE_CP_FOUT));
		Assert.assertTrue("standalone runtime recompile must not publish clone same-origin constraints",
			analysis.graph().constraints().stream().noneMatch(constraint ->
				constraint.kind() == NeutralPlacementGraph.ConstraintKind.SAME_ORIGIN
					&& (constraint.left().equals(recompiled.key()) || constraint.right().equals(recompiled.key()))));

		DpInvocationReceipt invocation = invoke(program, "standalone-runtime-recompile");
		Assert.assertSame("final-boundary DP receipt must own the standalone analysis",
			program.requirePlacementAnalysisAuthority(), invocation.analysis());
		Assert.assertTrue("standalone runtime recompile must yield no semantic clone receipt",
			invocation.semanticConsumption().rewireSnapshot().cloneReceipts().isEmpty());
	}

	@Test
	public void ambiguousRuntimeRecompileOriginsRemainSemanticCloneAndFailClosed() {
		DMLProgram program = ambiguousRuntimeRecompileProgram();
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		List<NeutralPlacementGraph.Node> recompileClones = analysis.graph().nodes().stream()
			.filter(node -> "recompile".equals(node.key().recompileContext()))
			.filter(node -> node.valueVersion().versionKind().name().equals("CLONE_RECOMPILE"))
			.filter(node -> node.kind() == NeutralPlacementGraph.NodeKind.CLONE)
			.toList();
		Assert.assertEquals("ambiguous same-origin recompile must remain a semantic clone",
			1, recompileClones.size());
		NeutralPlacementGraph.Node clone = recompileClones.get(0);
		Assert.assertTrue("ambiguous clone still carries recompile CP/FOUT exclusion",
			clone.exclusions().stream().anyMatch(exclusion ->
				exclusion.reasonCode() == NeutralPlacementGraph.ReasonCode.RECOMPILE_CP_FOUT));
		Assert.assertTrue("ambiguous clone must not receive a fabricated same-origin constraint",
			analysis.graph().constraints().stream().noneMatch(constraint ->
				constraint.kind() == NeutralPlacementGraph.ConstraintKind.SAME_ORIGIN
					&& constraint.right().equals(clone.key())));

		try {
			invoke(program, "ambiguous-runtime-recompile");
			Assert.fail("ambiguous runtime recompile clone must fail closed during exact clone receipt construction");
		}
		catch(AssertionError failure) {
			Assert.assertTrue("ambiguous runtime recompile must expose clone multiplicity failure: " + failure,
				failureContains(failure, "REWIRE_CLONE_SAME_ORIGIN_MULTIPLICITY_0")
					|| failureContains(failure, "DpSemanticConstructionException"));
		}
	}
	@Test
	public void b09PublishesTheExactProductionRewireUniverse() {
		DpInvocationReceipt invocation = invoke("B-09");
		PlacementAnalysis analysis = invocation.analysis();
		RewireOccurrenceSnapshot snapshot = invocation.semanticConsumption().rewireSnapshot();

		Assert.assertSame(analysis, snapshot.analysis());
		Assert.assertEquals(analysis.analysisFingerprint(), snapshot.analysisFingerprint());
		assertIdentityList(analysis.occurrences(), snapshot.occurrences(), "occurrences");
		Assert.assertFalse("scope key must bind one real enumeration", snapshot.enumerationScopeKey().isBlank());
		Assert.assertEquals("B-09 must retain its semantic recompile clone", 1, snapshot.cloneReceipts().size());
		FederatedPlannerDpRewireTransTable.CloneReceipt semanticClone = snapshot.cloneReceipts().get(0);
		assertPhysicalCloneDomainIsDisjoint(analysis, snapshot);
		Assert.assertFalse("semantic clone Hop ID must not be a physical loop-unroll clone key",
			snapshot.cloneToOriginal().containsKey(semanticClone.cloneOccurrence().hop().getHopID()));
		assertImmutable(snapshot.occurrences());
		assertImmutable(snapshot.cloneReceipts());
		assertImmutable(snapshot.additionalRoots());
		assertImmutable(snapshot.consumerEdges());
		assertImmutable(snapshot.cloneToOriginal());
		assertConsumerEdgesAreExact(analysis, snapshot.consumerEdges());
	}

	@Test
	public void b05PreservesMultiplicityAndInputPositionsWithoutCloneRepair() {
		DpInvocationReceipt invocation = invoke("B-05");
		RewireOccurrenceSnapshot snapshot = invocation.semanticConsumption().rewireSnapshot();

		Assert.assertTrue("B-05 must not fabricate clone receipts", snapshot.cloneReceipts().isEmpty());
		assertPhysicalCloneDomainIsDisjoint(invocation.analysis(), snapshot);
		assertIdentityList(invocation.analysis().occurrences(), snapshot.occurrences(), "B-05 occurrences");
		assertConsumerEdgesAreExact(invocation.analysis(), snapshot.consumerEdges());
	}

	private static void assertPhysicalCloneDomainIsDisjoint(PlacementAnalysis analysis,
		RewireOccurrenceSnapshot snapshot) {
		Set<Long> analysisHopIds = new HashSet<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			analysisHopIds.add(occurrence.hop().getHopID());
		Assert.assertFalse("loop-unroll fixture must retain physical clone mappings",
			snapshot.cloneToOriginal().isEmpty());
		for(Map.Entry<Long, Long> physicalClone : snapshot.cloneToOriginal().entrySet()) {
			Assert.assertFalse("physical clone key must not be an analysis-owned Hop ID",
				analysisHopIds.contains(physicalClone.getKey()));
			Assert.assertTrue("physical clone original must be an analysis-owned Hop ID",
				analysisHopIds.contains(physicalClone.getValue()));
		}
	}

	private static void assertConsumerEdgesAreExact(PlacementAnalysis analysis, List<RewireConsumerEdge> edges) {
		List<ExpectedEdge> expected = independentlyDeriveEdges(analysis);
		Assert.assertEquals("consumer edge completeness/multiplicity", expected.size(), edges.size());
		for(int i = 0; i < expected.size(); i++) {
			ExpectedEdge want = expected.get(i);
			RewireConsumerEdge actual = edges.get(i);
			Assert.assertSame("parent occurrence identity " + i, want.parent().key(), actual.parentOccurrence());
			Assert.assertSame("child occurrence identity " + i, want.child().key(), actual.childOccurrence());
			Assert.assertEquals("input position " + i, want.inputPosition(), actual.inputPosition());
		}
	}

	private static List<ExpectedEdge> independentlyDeriveEdges(PlacementAnalysis analysis) {
		Map<Hop, HopOccurrenceProjection> exactByHop = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Assert.assertNull("one exact occurrence per concrete rewired Hop",
				exactByHop.put(occurrence.hop(), occurrence));
		}
		List<ExpectedEdge> expected = new ArrayList<>();
		for(HopOccurrenceProjection parent : analysis.occurrences()) {
			for(int inputPosition = 0; inputPosition < parent.hop().getInput().size(); inputPosition++) {
				Hop input = parent.hop().getInput().get(inputPosition);
				HopOccurrenceProjection child = exactByHop.get(input);
				Assert.assertNotNull("concrete input must resolve by exact object identity", child);
				expected.add(new ExpectedEdge(parent, child, inputPosition));
			}
		}
		return List.copyOf(expected);
	}

	private static DpInvocationReceipt invoke(String id) {
		try {
			return invoke(ProductionShadowFixtureFactory.compile(id), id);
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile G014 rewire fixture " + id, e);
		}
	}

	private static DpInvocationReceipt invoke(DMLProgram program, String label) {
		try {
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
				new DMLTranslator(program).constructLops(program, value -> {
					if(!receipt.compareAndSet(null, value))
						throw new AssertionError("G014_REWIRE_MULTIPLE_RECEIPTS|" + label);
				});
			}
			finally {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old);
			}
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return (DpInvocationReceipt) receipt.get();
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile G014 rewire fixture " + label, e);
		}
	}

	private static DMLProgram standaloneRuntimeRecompileProgram() {
		DataOp input = new DataOp("G014_STANDALONE_IN", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "G014_STANDALONE_IN", 2, 2, 4, 1000);
		DataOp output = new DataOp("G014_STANDALONE_X", DataType.MATRIX, ValueType.FP64, input,
			OpOpData.TRANSIENTWRITE, "G014_STANDALONE_X");
		output.setDim1(2);
		output.setDim2(2);
		output.setRequiresRecompile();
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(output)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		return program;
	}

	private static boolean failureContains(Throwable failure, String needle) {
		for(Throwable cursor = failure; cursor != null; cursor = cursor.getCause())
			if(String.valueOf(cursor).contains(needle) || String.valueOf(cursor.getMessage()).contains(needle))
				return true;
		return false;
	}

	private static DMLProgram ambiguousRuntimeRecompileProgram() {
		DataOp firstOrigin = write("G014_AMBIG_X", "G014_AMBIG_IN1", false);
		DataOp secondOrigin = write("G014_AMBIG_X", "G014_AMBIG_IN2", false);
		DataOp recompiled = write("G014_AMBIG_X", "G014_AMBIG_IN3", true);
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block(firstOrigin), block(secondOrigin), block(recompiled))));
		return program;
	}

	private static StatementBlock block(Hop root) {
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(root)));
		return block;
	}

	private static DataOp write(String outputName, String inputName, boolean recompile) {
		DataOp input = new DataOp(inputName, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, inputName, 2, 2, 4, 1000);
		DataOp output = new DataOp(outputName, DataType.MATRIX, ValueType.FP64, input,
			OpOpData.TRANSIENTWRITE, outputName);
		output.setDim1(2);
		output.setDim2(2);
		if(recompile)
			output.setRequiresRecompile();
		return output;
	}

	private static void assertIdentityList(List<?> expected, List<?> actual, String label) {
		Assert.assertEquals(label, expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++) Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values) {
		try { ((List) values).add(null); Assert.fail("mutable list"); }
		catch(UnsupportedOperationException expected) { }
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(java.util.Map<?, ?> values) {
		try { ((java.util.Map) values).put(null, null); Assert.fail("mutable map"); }
		catch(UnsupportedOperationException expected) { }
	}

	private record ExpectedEdge(HopOccurrenceProjection parent, HopOccurrenceProjection child,
		int inputPosition) { }
}
