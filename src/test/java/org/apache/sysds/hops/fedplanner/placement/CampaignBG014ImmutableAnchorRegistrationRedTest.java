/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceLifecycleCapture.AnchorSnapshot;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED-2 contract: registration consumes analysis-owned durable anchors, never mutable globals. */
public class CampaignBG014ImmutableAnchorRegistrationRedTest {
	private static final String POISON_KEY = "poison.invalid:9/P@0:0-1:1|BROADCAST";

	@Test
	public void poisonedGlobalAfterAnalysisCannotChangeExactReceipt() {
		Fixture fixture = fixture();
		try {
			FederatedPlannerUtils.registerFedAnchorKey(fixture.anchor().getName(), POISON_KEY);
			ExactPlacementRegistration.Receipt receipt = ExactPlacementRegistration.registerProgram(
				fixture.program(), fixture.selectedTypes(), fixture.analysis());

			assertCanonicalReceipt("G014_RED2_POISON", fixture, receipt);
			RegistrySnapshot beforeRejects = registrySnapshot(fixture);
			assertCopiedAndForeignAnalysisRejectWithoutRegistryMutation(fixture, beforeRejects);
		}
		finally {
			clearMutableState();
		}
	}

	@Test
	public void clearedGlobalAfterAnalysisRetainsByteIdenticalReceipt() {
		Fixture fixture = fixture();
		try {
			ExactPlacementRegistration.Receipt beforeClear = ExactPlacementRegistration.registerProgram(
				fixture.program(), fixture.selectedTypes(), fixture.analysis());
			FederatedPlannerUtils.clearFedAnchorKeys();
			ExactPlacementRegistration.Receipt afterClear = ExactPlacementRegistration.registerProgram(
				fixture.program(), fixture.selectedTypes(), fixture.analysis());

			assertCanonicalReceipt("G014_RED2_BEFORE_CLEAR", fixture, beforeClear);
			assertCanonicalReceipt("G014_RED2_AFTER_CLEAR", fixture, afterClear);
			Assert.assertEquals("G014_RED2_CLEAR_CHANGED_IMMUTABLE_RECEIPT", beforeClear.uploads(),
				afterClear.uploads());
			assertImmutable("G014_RED2_RECEIPT_UPLOADS_MUTABLE", afterClear.uploads());
		}
		finally {
			clearMutableState();
		}
	}

	@Test
	public void functionInputBoundaryCopiesCallSiteDurablePlacementIntoDistinctOccurrence() throws Exception {
		DMLProgram compiled = ProductionShadowFixtureFactory.compile("B-21");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compiled);
		PlacementAnalysis.HopOccurrenceProjection boundary = analysis.occurrences().stream()
			.filter(value -> value.key().canonicalSourceOrigin().startsWith("function-boundary:"))
			.filter(value -> value.key().canonicalSourceOrigin().contains(":input:"))
			.findFirst().orElseThrow();
		NeutralPlacementGraph.Node boundaryNode = analysis.graph().node(boundary.key()).orElseThrow();
		NeutralPlacementGraph.Constraint exactInputEdge = analysis.graph().constraints().stream()
			.filter(value -> value.kind() == ConstraintKind.CONJUNCTIVE)
			.filter(value -> value.right() == boundary.key())
			.filter(value -> value.inputPosition() >= 0)
			.findFirst().orElseThrow();
		NeutralPlacementGraph.Node callSiteAnchor = analysis.graph().node(exactInputEdge.left()).orElseThrow();

		Assert.assertNotEquals("G014_RED2_FUNCTION_BOUNDARY_REUSED_CALLSITE_KEY",
			callSiteAnchor.key(), boundary.key());
		Assert.assertEquals("G014_RED2_FUNCTION_BOUNDARY_WRONG_INPUT_POSITION",
			boundary.key().callSitePath().substring(boundary.key().callSitePath().lastIndexOf("input-") + 6),
			Integer.toString(exactInputEdge.inputPosition()));
		Assert.assertEquals("G014_RED2_FUNCTION_BOUNDARY_DROPPED_DURABLE_PLACEMENT",
			callSiteAnchor.anchors(), boundaryNode.anchors());
		List<DurableAnchorKey> beforeMutation = List.copyOf(boundaryNode.anchors());
		assertImmutable("G014_RED2_FUNCTION_BOUNDARY_ANCHOR_MUTABLE", boundaryNode.anchors());
		Assert.assertEquals("G014_RED2_FUNCTION_BOUNDARY_MUTATION_CHANGED_STATE", beforeMutation,
			boundaryNode.anchors());
	}

	@Test
	public void executedCloneAndRecompileReanalysisPreserveCapturedPlacement() {
		DMLProgram sourceProgram = new DMLProgram();
		DataOp anchor = literalFederatedAnchor("G014_RED2_CLONE_X", 18141);
		Hop sourceRoot = HopRewriteUtils.createBinary(anchor, matrix("G014_RED2_CLONE_S"), OpOp2.PLUS);
		sourceProgram.setStatementBlocks(new ArrayList<>(List.of(block(sourceRoot))));
		PlacementAnalysis source = sourceProgram.bindPlacementAnalysisAtFinalHopBoundary();
		try {
			Hop clonedRoot = (Hop) sourceRoot.clone();
			DMLProgram cloneProgram = new DMLProgram();
			cloneProgram.setStatementBlocks(new ArrayList<>(List.of(block(clonedRoot))));
			PlacementAnalysis cloneAnalysis = cloneProgram.bindPlacementAnalysisAtFinalHopBoundary();
			Assert.assertNotSame("G014_RED2_CLONE_REUSED_SOURCE_ANALYSIS", source, cloneAnalysis);
			Assert.assertEquals("G014_RED2_CLONE_LOST_DURABLE_PLACEMENT",
				AnchorProvenanceLifecycleCapture.snapshot(source, List.of(AnchorAccessForm.FEDINIT_LITERAL)).anchors(),
				AnchorProvenanceLifecycleCapture.snapshot(cloneAnalysis,
					List.of(AnchorAccessForm.FEDINIT_LITERAL)).anchors());

			DMLProgram recompiled = ProductionShadowFixtureFactory.compile("B-09");
			PlacementAnalysis[] output = new PlacementAnalysis[1];
			new DMLTranslator(recompiled).constructLops(recompiled, receipt -> output[0] = receipt.analysis());
			Assert.assertNotNull("G014_RED2_RECOMPILE_DID_NOT_PUBLISH_ANALYSIS", output[0]);
			Assert.assertSame("G014_RED2_RECOMPILE_OUTPUT_NOT_FINAL_BOUNDARY_AUTHORITY",
				recompiled.requirePlacementAnalysisAuthority(), output[0]);
			AnchorSnapshot actual = AnchorProvenanceLifecycleCapture.snapshot(output[0],
				List.of(AnchorAccessForm.RUNTIME_RECOMPILE_SIGNATURE));
			Assert.assertFalse("G014_RED2_RECOMPILE_OUTPUT_DROPPED_RUNTIME_FACTS",
				actual.runtimeSignatureFacts().isEmpty());
		}
		catch(Exception failure) {
			throw new AssertionError("Unable to execute clone/recompile lifecycle", failure);
		}
		finally {
			clearMutableState();
		}
	}

	private static Fixture fixture() {
		DataOp anchor = literalFederatedAnchor("G014_RED2_X", 18101);
		DataOp target = matrix("G014_RED2_S");
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		Hop parent = HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(parent)));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		PlacementAnalysis analysis = program.bindPlacementAnalysisAtFinalHopBoundary();
		PlacementAnalysis.HopOccurrenceProjection anchorOccurrence = occurrence(analysis, anchor);
		List<DurableAnchorKey> durableAnchors = analysis.graph().node(anchorOccurrence.key()).orElseThrow().anchors();
		Assert.assertEquals("G014_RED2_REAL_ANCHOR_COUNT", 1, durableAnchors.size());
		Assert.assertSame("G014_RED2_REAL_ANCHOR_FTYPE", FType.ROW, durableAnchors.get(0).fType());
		assertImmutable("G014_RED2_ANALYSIS_ANCHORS_MUTABLE", durableAnchors);
		Map<Long, FType> selected = new LinkedHashMap<>();
		selected.put(anchor.getHopID(), FType.ROW);
		selected.put(target.getHopID(), FType.ROW);
		return new Fixture(program, analysis, anchor, target, runtimeKey(durableAnchors.get(0)),
			Map.copyOf(selected));
	}

	private static void assertCanonicalReceipt(String label, Fixture fixture,
		ExactPlacementRegistration.Receipt receipt) {
		Assert.assertSame(label + "_ANALYSIS_IDENTITY", fixture.analysis(), receipt.analysis());
		Assert.assertEquals(label + "_UPLOAD_COUNT", 1, receipt.uploads().size());
		ExactPlacementRegistration.RegisteredUpload upload = receipt.uploads().get(0);
		Assert.assertEquals(label + "_TARGET", fixture.target().getHopID(), upload.hopId());
		Assert.assertEquals(label + "_ANCHOR", fixture.anchor().getHopID(), upload.anchorHopId());
		Assert.assertEquals(label + "_FTYPE", FType.ROW, upload.fType());
		Assert.assertEquals(label + "_ANCHOR_LABEL", fixture.anchor().getName(), upload.anchorLabel());
		Assert.assertEquals(label + "_MUTABLE_GLOBAL_OVERRULED_ANALYSIS", fixture.anchorKey(), upload.anchorKey());
		Assert.assertNotEquals(label + "_POISON_ACCEPTED", POISON_KEY, upload.anchorKey());
	}

	private static void assertCopiedAndForeignAnalysisRejectWithoutRegistryMutation(Fixture fixture,
		RegistrySnapshot expected) {
		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(fixture.program());
		Assert.assertEquals("G014_RED2_REANALYSIS_DROPPED_ANCHOR",
			fixture.anchorKey(), runtimeKey(durableAnchor(copied, fixture.anchor())));
		expectReject(fixture, copied, "G014_RED2_ACCEPTED_COPIED_REANALYSIS");
		Assert.assertEquals("G014_RED2_COPIED_REANALYSIS_MUTATED_REGISTRIES", expected,
			registrySnapshot(fixture));

		DMLProgram foreignProgram = new DMLProgram();
		DataOp foreignAnchor = literalFederatedAnchor("G014_RED2_FOREIGN_X", 18131);
		DataOp foreignTarget = matrix("G014_RED2_FOREIGN_S");
		foreignTarget.setForcedExecType(ExecType.CP);
		foreignTarget.setFederatedOutput(FederatedOutput.FOUT);
		foreignProgram.setStatementBlocks(new ArrayList<>(List.of(block(
			HopRewriteUtils.createBinary(foreignTarget, foreignAnchor, OpOp2.PLUS)))));
		PlacementAnalysis foreign = foreignProgram.bindPlacementAnalysisAtFinalHopBoundary();
		expectReject(fixture, foreign, "G014_RED2_ACCEPTED_FOREIGN_ANALYSIS");
		Assert.assertEquals("G014_RED2_FOREIGN_ANALYSIS_MUTATED_REGISTRIES", expected,
			registrySnapshot(fixture));
	}

	private static void expectReject(Fixture fixture, PlacementAnalysis candidate, String failure) {
		try {
			ExactPlacementRegistration.registerProgram(fixture.program(), fixture.selectedTypes(), candidate);
			Assert.fail(failure);
		}
		catch(IllegalArgumentException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}

	private static RegistrySnapshot registrySnapshot(Fixture fixture) {
		List<Long> scopes = fixture.analysis().occurrences().stream()
			.filter(value -> value.hop() == fixture.target()).map(value -> value.scopeId()).distinct().toList();
		return new RegistrySnapshot(scopes.stream().map(FederatedRefedRegistry::snapshot).toList(),
			scopes.stream().map(FederatedFoutMaterializeRegistry::snapshot).toList());
	}

	private static DurableAnchorKey durableAnchor(PlacementAnalysis analysis, Hop anchor) {
		PlacementAnalysis.HopOccurrenceProjection occurrence = occurrence(analysis, anchor);
		List<DurableAnchorKey> anchors = analysis.graph().node(occurrence.key()).orElseThrow().anchors();
		Assert.assertEquals("G014_RED2_REANALYSIS_ANCHOR_COUNT", 1, anchors.size());
		return anchors.get(0);
	}

	private static PlacementAnalysis.HopOccurrenceProjection occurrence(PlacementAnalysis analysis, Hop hop) {
		return analysis.occurrences().stream().filter(value -> value.hop() == hop).findFirst().orElseThrow();
	}

	private static String runtimeKey(DurableAnchorKey anchor) {
		return anchor.partitions().stream().map(partition -> partition.address() + "@"
			+ partition.begin().get(0) + ":" + partition.begin().get(1) + "-"
			+ partition.end().get(0) + ":" + partition.end().get(1))
			.reduce((left, right) -> left + ";" + right).orElseThrow() + "|" + anchor.fType().name();
	}

	private static DataOp literalFederatedAnchor(String name, int port) {
		String script = name + "=federated(addresses=list(\"localhost:" + port + "/X1\",\"localhost:"
			+ (port + 1) + "/X2\"),ranges=list(list(0,0),list(5,10),list(5,0),list(10,10)));\n"
			+ "print(sum(" + name + "));\n";
		try {
			DMLProgram parsed = compile(script);
			return (DataOp) parsed.getStatementBlocks().stream().flatMap(value -> value.getHops().stream())
				.flatMap(root -> collect(root).stream()).filter(Hop::isFederatedDataOp).findFirst().orElseThrow();
		}
		catch(Exception failure) {
			throw new AssertionError("Unable to build real G014 RED-2 federated anchor", failure);
		}
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static List<Hop> collect(Hop root) {
		List<Hop> result = new ArrayList<>();
		java.util.Set<Hop> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.ArrayDeque<Hop> pending = new java.util.ArrayDeque<>();
		pending.push(root);
		while(!pending.isEmpty()) {
			Hop hop = pending.pop();
			if(!seen.add(hop)) continue;
			result.add(hop);
			for(Hop input : hop.getInput()) pending.push(input);
		}
		return result;
	}

	private static StatementBlock block(Hop root) {
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(root)));
		return block;
	}

	private static DataOp matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 10, 10, -1, 1000);
	}

	private static void assertImmutable(String failure, List<?> values) {
		try {
			values.clear();
			Assert.fail(failure);
		}
		catch(UnsupportedOperationException expected) {
			// behavioral immutability, independent of collection implementation class
		}
	}

	private static void clearMutableState() {
		FederatedPlannerUtils.clearFedAnchorKeys();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis, DataOp anchor, DataOp target,
		String anchorKey, Map<Long, FType> selectedTypes) { }

	private record RegistrySnapshot(List<Map<Long, FederatedRefedRegistry.AnchorSpec>> refed,
		List<Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec>> fout) { }

}
