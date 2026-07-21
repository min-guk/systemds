/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceLifecycleCapture;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceLifecycleCapture.AnchorSnapshot;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.apache.sysds.hops.fedplanner.placement.ExactPlacementRegistration;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.ProgramConverter;
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
			assertUploadsImmutable("G014_RED2_RECEIPT_UPLOADS_MUTABLE", afterClear.uploads());
		}
		finally {
			clearMutableState();
		}
	}

	@Test
	public void functionInputBoundaryCopiesCallSiteDurablePlacementIntoDistinctOccurrence() throws Exception {
		DMLProgram compiled = ProductionShadowFixtureFactory.compile("B-21");
		PlacementAnalysis analysis = compiled.bindPlacementAnalysisAtFinalHopBoundary();
		Assert.assertSame("G014_RED2_FUNCTION_NOT_FINAL_BOUNDARY_AUTHORITY", analysis,
			compiled.requirePlacementAnalysisAuthority());
		NeutralPlacementGraph.Node boundaryNode = analysis.graph().nodes().stream()
			.filter(value -> value.kind() == NodeKind.FUNCTION_INPUT)
			.filter(value -> value.valueVersion().versionKind() == VersionKind.FUNCTION_INPUT)
			.filter(value -> analysis.graph().constraints().stream()
				.anyMatch(edge -> edge.kind() == ConstraintKind.CONJUNCTIVE
					&& edge.right() == value.key() && edge.inputPosition() >= 0))
			.findFirst().orElseThrow();
		HopOccurrenceProjection boundary = analysis.occurrences().stream()
			.filter(value -> value.key() == boundaryNode.key()).findFirst().orElseThrow();
		NeutralPlacementGraph.Constraint exactInputEdge = analysis.graph().constraints().stream()
			.filter(value -> value.kind() == ConstraintKind.CONJUNCTIVE)
			.filter(value -> value.right() == boundary.key())
			.filter(value -> value.inputPosition() >= 0)
			.findFirst().orElseThrow();
		NeutralPlacementGraph.Node callSiteAnchor = analysis.graph().node(exactInputEdge.left()).orElseThrow();

		Assert.assertNotEquals("G014_RED2_FUNCTION_BOUNDARY_REUSED_CALLSITE_KEY",
			callSiteAnchor.key(), boundary.key());
		Assert.assertSame("G014_RED2_FUNCTION_BOUNDARY_EDGE_NOT_EXACT_KEY",
			boundaryNode.key(), exactInputEdge.right());
		Assert.assertTrue("G014_RED2_FUNCTION_BOUNDARY_WRONG_INPUT_POSITION",
			exactInputEdge.inputPosition() >= 0);
		Assert.assertEquals("G014_RED2_FUNCTION_BOUNDARY_POSITION_NOT_VALUE_VERSION_OWNED",
			boundaryNode.valueVersion().definitionOrdinal(), exactInputEdge.inputPosition());
		Assert.assertSame("G014_RED2_FUNCTION_BOUNDARY_NOT_STRUCTURAL_FUNCTION_INPUT",
			NodeKind.FUNCTION_INPUT, boundaryNode.kind());
		Assert.assertEquals("G014_RED2_FUNCTION_BOUNDARY_DROPPED_DURABLE_PLACEMENT",
			callSiteAnchor.anchors(), boundaryNode.anchors());
		List<DurableAnchorKey> beforeMutation = List.copyOf(boundaryNode.anchors());
		assertAnchorsImmutable("G014_RED2_FUNCTION_BOUNDARY_ANCHOR_MUTABLE", boundaryNode.anchors());
		Assert.assertEquals("G014_RED2_FUNCTION_BOUNDARY_MUTATION_CHANGED_STATE", beforeMutation,
			boundaryNode.anchors());
	}

	@Test
	public void syntheticFunctionBoundariesDoNotMasqueradeAsCompiledSelectedPlanOccurrences() throws Exception {
		DMLProgram compiled = ProductionShadowFixtureFactory.compile("B-21");
		PlacementAnalysis analysis = compiled.bindPlacementAnalysisAtFinalHopBoundary();
		List<HopOccurrenceProjection> compiledOccurrences = analysis.compiledHopOccurrences();
		long syntheticBoundaries = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT)
			.count();

		Assert.assertTrue("G014_DIFFERENTIAL_FUNCTION_BOUNDARY_FIXTURE_EMPTY", syntheticBoundaries > 0);
		Assert.assertEquals("G014_DIFFERENTIAL_COMPILED_PROJECTION_CARDINALITY",
			analysis.occurrences().size() - syntheticBoundaries, compiledOccurrences.size());
		Assert.assertTrue("G014_DIFFERENTIAL_SYNTHETIC_BOUNDARY_LEAKED_INTO_COMPILED_PROJECTION",
			compiledOccurrences.stream().allMatch(analysis::isCompiledHopOccurrence));
		Assert.assertTrue("G014_DIFFERENTIAL_ORPHAN_FUNCTION_BODY_WAS_HIDDEN",
			compiledOccurrences.stream().anyMatch(occurrence -> analysis.graph().node(occurrence.key())
				.orElseThrow().kind() == NodeKind.FUNCTION_BODY_NON_EMITTED));
		Assert.assertEquals("G014_DIFFERENTIAL_SEMANTIC_GRAPH_LOST_BOUNDARIES", syntheticBoundaries,
			analysis.occurrences().stream().filter(occurrence -> !analysis.isCompiledHopOccurrence(occurrence)).count());
	}

	@Test
	public void immutableReceiptAnchorKeyRebuildsExactRuntimePlacement() {
		Fixture fixture = fixture();
		try {
			ExactPlacementRegistration.RegisteredUpload upload = ExactPlacementRegistration.registerProgram(
				fixture.program(), fixture.selectedTypes(), fixture.analysis()).uploads().get(0);
			FederationMap rebuilt = FederationUtils.buildAnchorMapFromKey(upload.anchorKey());
			Assert.assertNotNull("G014_RED2_RUNTIME_KEY_NOT_DECODABLE", rebuilt);
			Assert.assertSame("G014_RED2_RUNTIME_KEY_FTYPE", FType.ROW, rebuilt.getType());
			Assert.assertEquals("G014_RED2_RUNTIME_KEY_PARTITIONS", 2, rebuilt.getFederatedRanges().length);
			Assert.assertArrayEquals("G014_RED2_RUNTIME_KEY_FIRST_BEGIN", new long[] {0, 0},
				rebuilt.getFederatedRanges()[0].getBeginDims());
			Assert.assertArrayEquals("G014_RED2_RUNTIME_KEY_FIRST_END", new long[] {5, 0},
				rebuilt.getFederatedRanges()[0].getEndDims());
			Assert.assertArrayEquals("G014_RED2_RUNTIME_KEY_SECOND_BEGIN", new long[] {5, 0},
				rebuilt.getFederatedRanges()[1].getBeginDims());
			Assert.assertArrayEquals("G014_RED2_RUNTIME_KEY_SECOND_END", new long[] {10, 0},
				rebuilt.getFederatedRanges()[1].getEndDims());
		}
		finally {
			clearMutableState();
		}
	}

	@Test
	public void immutableReceiptAnchorKeySupportsEveryDurableRuntimeType() {
		try {
			assertRuntimeRoundTrip(literalFederatedAnchor("G014_RED2_KEY_ROW", 18211, FType.ROW), FType.ROW,
				new long[][] {{0, 0, 5, 0}, {5, 0, 10, 0}});
			assertRuntimeRoundTrip(literalFederatedAnchor("G014_RED2_KEY_COL", 18221, FType.COL), FType.COL,
				new long[][] {{0, 0, 0, 5}, {0, 5, 0, 10}});
			assertRuntimeRoundTrip(literalFederatedAnchor("G014_RED2_KEY_FULL", 18231, FType.FULL), FType.FULL,
				new long[][] {{0, 0, 10, 10}});
			assertRuntimeRoundTrip(literalFederatedAnchor("G014_RED2_KEY_BROADCAST", 18241, FType.BROADCAST),
				FType.BROADCAST, new long[][] {{0, 0, 10, 10}, {0, 0, 10, 10}});
		}
		finally {
			clearMutableState();
		}
	}

	@Test
	public void inlinedFunctionBoundaryMetadataSurvivesStatementBlockDeepCopy() throws Exception {
		DMLProgram compiled = ProductionShadowFixtureFactory.compile("B-21");
		StatementBlock source = compiled.getStatementBlocks().stream()
			.filter(block -> !block.getInlinedFunctionCallBoundaries().isEmpty()).findFirst().orElseThrow();
		StatementBlock copied = ProgramConverter.createStatementBlockCopy(source, -1, true, true);
		Assert.assertNotSame("G014_RED2_RECOMPILE_REUSED_STATEMENT_BLOCK", source, copied);
		Assert.assertEquals("G014_RED2_RECOMPILE_DROPPED_TYPED_FUNCTION_BOUNDARIES",
			source.getInlinedFunctionCallBoundaries(), copied.getInlinedFunctionCallBoundaries());
		DMLProgram copiedProgram = new DMLProgram();
		copiedProgram.setStatementBlocks(new ArrayList<>(List.of(copied)));
		PlacementAnalysis copiedAnalysis = copiedProgram.bindPlacementAnalysisAtFinalHopBoundary();
		Assert.assertTrue("G014_RED2_RECOMPILE_DROPPED_EXACT_FUNCTION_INPUT",
			copiedAnalysis.graph().nodes().stream().anyMatch(node -> node.kind() == NodeKind.FUNCTION_INPUT
				&& copiedAnalysis.graph().constraints().stream().anyMatch(edge -> edge.left() != node.key()
					&& edge.right() == node.key() && edge.kind() == ConstraintKind.CONJUNCTIVE)));
	}

	@Test
	public void nestedInliningPrefixesTypedBoundaryAuthorityBeforeAnalysis() throws Exception {
		String script = "inner=function(matrix[double] X) return (matrix[double] Y){Y=rowSums(X);}\n"
			+ "outer=function(matrix[double] A) return (matrix[double] B){B=inner(A);}\n"
			+ "F=federated(addresses=list(\"localhost:18201/X1\",\"localhost:18202/X2\"),"
			+ "ranges=list(list(0,0),list(5,10),list(5,0),list(10,10)));\n"
			+ "R=outer(F);\nprint(sum(R));\n";
		DMLProgram compiled = compileWithoutHopRewrites(script);
		List<StatementBlock.InlinedFunctionCallBoundary> boundaries = compiled.getStatementBlocks().stream()
			.flatMap(block -> block.getInlinedFunctionCallBoundaries().stream()).distinct().toList();
		Assert.assertEquals("G014_RED2_NESTED_TYPED_CALL_COUNT", 2, boundaries.size());
		Assert.assertEquals("G014_RED2_NESTED_CALL_POSITIONS_COLLIDE", 2,
			boundaries.stream().map(StatementBlock.InlinedFunctionCallBoundary::callStatementPosition)
				.distinct().count());
		PlacementAnalysis analysis = compiled.bindPlacementAnalysisAtFinalHopBoundary();
		List<NeutralPlacementGraph.Node> functionInputs = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.FUNCTION_INPUT)
			.filter(node -> analysis.graph().constraints().stream().anyMatch(edge -> edge.right() == node.key()
				&& edge.evidence().startsWith("inlined-function-argument:"))).toList();
		Assert.assertEquals("G014_RED2_NESTED_EXACT_FUNCTION_INPUT_COUNT", 2, functionInputs.size());
		Assert.assertTrue("G014_RED2_NESTED_INPUT_DROPPED_TYPED_CONJUNCTIVE_AUTHORITY",
			functionInputs.stream().allMatch(node -> analysis.graph().constraints().stream()
				.anyMatch(edge -> edge.kind() == ConstraintKind.CONJUNCTIVE && edge.right() == node.key())));
	}

	@Test
	public void executedCloneAndRecompileReanalysisPreserveCapturedPlacement() {
		DMLProgram sourceProgram = new DMLProgram();
		DataOp anchor = literalFederatedAnchor("G014_RED2_CLONE_X", 18141);
		Hop sourceRoot = HopRewriteUtils.createBinary(anchor, matrix("G014_RED2_CLONE_S"), OpOp2.PLUS);
		sourceProgram.setStatementBlocks(new ArrayList<>(List.of(block(sourceRoot))));
		PlacementAnalysis source = sourceProgram.bindPlacementAnalysisAtFinalHopBoundary();
		Assert.assertSame("G014_RED2_SOURCE_NOT_FINAL_BOUNDARY_AUTHORITY", source,
			sourceProgram.requirePlacementAnalysisAuthority());
		HopOccurrenceProjection sourceAnchor = occurrence(source, anchor);
		HopOccurrenceProjection sourceTarget = occurrence(source, fixtureName(source, "G014_RED2_CLONE_S"));
		DurableAnchorKey sourceDurable = durableAnchor(source, anchor);
		String sourceFingerprint = source.analysisFingerprint();
		RegistrySnapshot beforeRejectedReanalysis = registrySnapshot(source, sourceTarget.hop());
		try {
			Hop clonedRoot = Recompiler.deepCopyHopsDag(sourceRoot);
			DMLProgram cloneProgram = new DMLProgram();
			cloneProgram.setStatementBlocks(new ArrayList<>(List.of(block(clonedRoot))));
			PlacementAnalysis cloneAnalysis = cloneProgram.bindPlacementAnalysisAtFinalHopBoundary();
			Assert.assertSame("G014_RED2_CLONE_NOT_FINAL_BOUNDARY_AUTHORITY", cloneAnalysis,
				cloneProgram.requirePlacementAnalysisAuthority());
			Assert.assertNotSame("G014_RED2_CLONE_REUSED_SOURCE_ANALYSIS", source, cloneAnalysis);
			Hop cloneAnchorHop = fixtureName(cloneAnalysis, "G014_RED2_CLONE_X");
			Hop cloneTargetHop = fixtureName(cloneAnalysis, "G014_RED2_CLONE_S");
			Assert.assertNotSame("G014_RED2_DEEP_COPY_REUSED_SOURCE_ROOT", sourceRoot, clonedRoot);
			Assert.assertNotSame("G014_RED2_DEEP_COPY_REUSED_SOURCE_ANCHOR", anchor, cloneAnchorHop);
			Assert.assertNotSame("G014_RED2_DEEP_COPY_REUSED_SOURCE_TARGET", sourceTarget.hop(), cloneTargetHop);
			HopOccurrenceProjection cloneAnchor = occurrence(cloneAnalysis, cloneAnchorHop);
			HopOccurrenceProjection cloneTarget = occurrence(cloneAnalysis, cloneTargetHop);
			Assert.assertNotSame("G014_RED2_CLONE_REUSED_SOURCE_ANCHOR_OCCURRENCE", sourceAnchor, cloneAnchor);
			Assert.assertNotSame("G014_RED2_CLONE_REUSED_SOURCE_TARGET_OCCURRENCE", sourceTarget, cloneTarget);
			Assert.assertNotSame("G014_RED2_CLONE_REUSED_SOURCE_ANCHOR_KEY", sourceAnchor.key(), cloneAnchor.key());
			Assert.assertNotSame("G014_RED2_CLONE_REUSED_SOURCE_TARGET_KEY", sourceTarget.key(), cloneTarget.key());
			Assert.assertEquals("G014_RED2_CLONE_LOST_DURABLE_PLACEMENT", sourceDurable,
				durableAnchor(cloneAnalysis, cloneAnchorHop));
			Assert.assertTrue("G014_RED2_SOURCE_ANCHOR_NOT_ANALYSIS_OWNED",
				source.graph().node(sourceAnchor.key()).orElseThrow().anchors().contains(sourceDurable));
			assertAnchorsImmutable("G014_RED2_CLONE_ANCHORS_MUTABLE",
				cloneAnalysis.graph().node(cloneAnchor.key()).orElseThrow().anchors());
			Assert.assertEquals("G014_RED2_CLONE_CHANGED_SOURCE_FINGERPRINT", sourceFingerprint,
				source.analysisFingerprint());

			PlacementAnalysis copiedSource = new NeutralPlacementGraphBuilder().buildAnalysis(sourceProgram);
			HopOccurrenceProjection copiedTarget = occurrence(copiedSource, sourceTarget.hop());
			Assert.assertEquals("G014_RED2_COPIED_REANALYSIS_NOT_SAME_VALUE_TARGET", sourceTarget,
				copiedTarget);
			Assert.assertNotSame("G014_RED2_COPIED_REANALYSIS_REUSED_TARGET_OCCURRENCE", sourceTarget,
				copiedTarget);
			Assert.assertNotSame("G014_RED2_COPIED_REANALYSIS_REUSED_TARGET_KEY", sourceTarget.key(),
				copiedTarget.key());
			expectRegisterReject(sourceProgram, sourceSelectedTypes(anchor, sourceTarget.hop()), copiedSource,
				"G014_RED2_ACCEPTED_SAME_VALUE_COPIED_OCCURRENCE_AUTHORITY");
			Assert.assertEquals("G014_RED2_COPIED_OCCURRENCE_REJECTION_MUTATED_REGISTRIES",
				beforeRejectedReanalysis, registrySnapshot(source, sourceTarget.hop()));
			Assert.assertEquals("G014_RED2_COPIED_OCCURRENCE_REJECTION_CHANGED_FINGERPRINT", sourceFingerprint,
				source.analysisFingerprint());

			DMLProgram recompiled = ProductionShadowFixtureFactory.compile("B-09");
			AtomicReference<PlannerInvocationReceipt> output = new AtomicReference<>();
			String oldPlanner = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER,
					"compile_cost_based");
				new DMLTranslator(recompiled).constructLops(recompiled, receipt -> Assert.assertTrue(
					"G014_RED2_RECOMPILE_PUBLISHED_MULTIPLE_RECEIPTS", output.compareAndSet(null, receipt)));
			}
			finally {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, oldPlanner);
			}
			Assert.assertNotNull("G014_RED2_RECOMPILE_DID_NOT_PUBLISH_ANALYSIS", output.get());
			Assert.assertTrue("G014_RED2_RECOMPILE_DID_NOT_PUBLISH_DP_RECEIPT",
				output.get() instanceof DpInvocationReceipt);
			DpInvocationReceipt dpReceipt = (DpInvocationReceipt) output.get();
			Assert.assertSame("G014_RED2_RECOMPILE_OUTPUT_NOT_FINAL_BOUNDARY_AUTHORITY",
				recompiled.requirePlacementAnalysisAuthority(), dpReceipt.analysis());
			Assert.assertSame("G014_RED2_RECOMPILE_EXACT_SELECTION_LOST_ANALYSIS", dpReceipt.analysis(),
				dpReceipt.exactSelection().analysis());
			Assert.assertSame("G014_RED2_RECOMPILE_SEMANTIC_CONTEXT_LOST_ANALYSIS", dpReceipt.analysis(),
				dpReceipt.semanticConsumption().semanticBlock().context().analysis());
			Assert.assertEquals("G014_RED2_RECOMPILE_FINGERPRINT_DRIFTED_BEFORE_AFTER",
				dpReceipt.analysisFingerprintBefore(), dpReceipt.analysisFingerprintAfter());
			Assert.assertEquals("G014_RED2_RECOMPILE_FINGERPRINT_NOT_ANALYSIS_OWNED",
				dpReceipt.analysis().analysisFingerprint(), dpReceipt.analysisFingerprintBefore());
			AnchorSnapshot actual = AnchorProvenanceLifecycleCapture.snapshot(dpReceipt.analysis(),
				List.of(AnchorAccessForm.RUNTIME_RECOMPILE_SIGNATURE));
			Assert.assertFalse("G014_RED2_RECOMPILE_OUTPUT_DROPPED_RUNTIME_FACTS",
				actual.runtimeSignatureFacts().isEmpty());
			assertRecompileCpFoutExcludedByReceipt(dpReceipt);
		}
		catch(Exception failure) {
			throw new AssertionError("Unable to execute clone/recompile lifecycle", failure);
		}
		finally {
			clearMutableState();
		}
	}

	private static void assertRecompileCpFoutExcludedByReceipt(DpInvocationReceipt receipt) {
		List<NeutralPlacementGraph.Node> recompileNodes = receipt.analysis().graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.CLONE)
			.filter(node -> "recompile".equals(node.key().recompileContext()))
			.toList();
		Assert.assertEquals("G014_RED2_RECOMPILE_EXPECTS_ONE_CLONE_BOUNDARY", 1, recompileNodes.size());
		NeutralPlacementGraph.Node clone = recompileNodes.get(0);
		Assert.assertTrue("G014_RED2_RECOMPILE_CP_FOUT_NOT_EXCLUDED_BY_ANALYSIS",
			clone.exclusions().stream().anyMatch(exclusion -> exclusion.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT
				&& exclusion.state().execType() == ExecType.CP
				&& exclusion.state().output() == FederatedOutput.FOUT));
		List<CandidateDecisionReceipt> forbiddenPublished = receipt.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts().stream()
			.filter(decision -> decision.candidateSnapshot().parentOccurrence() == clone.key())
			.filter(decision -> decision.nativeExec() == ExecType.CP)
			.filter(decision -> decision.nativeOutput() == FederatedOutput.FOUT)
			.toList();
		Assert.assertTrue("G014_RED2_RECOMPILE_CP_FOUT_PUBLISHED_DECISION_RECEIPT", forbiddenPublished.isEmpty());
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
		Assert.assertSame("G014_RED2_FIXTURE_NOT_FINAL_BOUNDARY_AUTHORITY", analysis,
			program.requirePlacementAnalysisAuthority());
		PlacementAnalysis.HopOccurrenceProjection anchorOccurrence = occurrence(analysis, anchor);
		List<DurableAnchorKey> durableAnchors = analysis.graph().node(anchorOccurrence.key()).orElseThrow().anchors();
		Assert.assertEquals("G014_RED2_REAL_ANCHOR_COUNT", 1, durableAnchors.size());
		Assert.assertSame("G014_RED2_REAL_ANCHOR_FTYPE", FType.ROW, durableAnchors.get(0).fType());
		assertAnchorsImmutable("G014_RED2_ANALYSIS_ANCHORS_MUTABLE", durableAnchors);
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
		Assert.assertSame("G014_RED2_FOREIGN_NOT_FINAL_BOUNDARY_AUTHORITY", foreign,
			foreignProgram.requirePlacementAnalysisAuthority());
		expectReject(fixture, foreign, "G014_RED2_ACCEPTED_FOREIGN_ANALYSIS");
		Assert.assertEquals("G014_RED2_FOREIGN_ANALYSIS_MUTATED_REGISTRIES", expected,
			registrySnapshot(fixture));
	}

	private static void expectRegisterReject(DMLProgram program, Map<Long, FType> selectedTypes,
		PlacementAnalysis candidate, String failure) {
		try {
			ExactPlacementRegistration.registerProgram(program, selectedTypes, candidate);
			Assert.fail(failure);
		}
		catch(IllegalArgumentException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}

	private static Map<Long, FType> sourceSelectedTypes(Hop anchor, Hop target) {
		Map<Long, FType> selected = new LinkedHashMap<>();
		selected.put(anchor.getHopID(), FType.ROW);
		selected.put(target.getHopID(), FType.ROW);
		return Map.copyOf(selected);
	}

	private static Hop fixtureName(PlacementAnalysis analysis, String name) {
		return analysis.occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.filter(DataOp.class::isInstance).map(DataOp.class::cast)
			.filter(hop -> name.equals(hop.getName()))
			.findFirst().orElseThrow();
	}

	private static RegistrySnapshot registrySnapshot(PlacementAnalysis analysis, Hop target) {
		List<Long> scopes = analysis.occurrences().stream()
			.filter(value -> value.hop() == target).map(value -> value.scopeId()).distinct().toList();
		return new RegistrySnapshot(scopes.stream().map(FederatedRefedRegistry::snapshot).toList(),
			scopes.stream().map(FederatedFoutMaterializeRegistry::snapshot).toList());
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
		String addresses = anchor.partitions().stream().map(partition -> partition.workerId() + ';')
			.reduce("", String::concat);
		String ranges = anchor.partitions().stream().map(partition -> switch(anchor.fType()) {
			case ROW -> partition.begin().get(0) + "," + partition.end().get(0) + ';';
			case COL -> partition.begin().get(1) + "," + partition.end().get(1) + ';';
			case FULL, BROADCAST -> partition.begin().get(0) + "," + partition.begin().get(1) + ","
				+ partition.end().get(0) + "," + partition.end().get(1) + ';';
			default -> throw new IllegalArgumentException("unsupported durable anchor " + anchor.fType());
		}).reduce("", String::concat);
		return addresses + '|' + ranges + '|' + anchor.fType().name();
	}

	private static DataOp literalFederatedAnchor(String name, int port) {
		return literalFederatedAnchor(name, port, FType.ROW);
	}

	private static DataOp literalFederatedAnchor(String name, int port, FType type) {
		String addresses = type == FType.FULL ? "list(\"localhost:" + port + "/X1\")"
			: "list(\"localhost:" + port + "/X1\",\"localhost:" + (port + 1) + "/X2\")";
		String ranges = switch(type) {
			case ROW -> "list(list(0,0),list(5,10),list(5,0),list(10,10))";
			case COL -> "list(list(0,0),list(10,5),list(0,5),list(10,10))";
			case FULL -> "list(list(0,0),list(10,10))";
			case BROADCAST -> "list(list(0,0),list(10,10),list(0,0),list(10,10))";
			default -> throw new IllegalArgumentException("unsupported durable anchor " + type);
		};
		String script = name + "=federated(addresses=" + addresses + ",ranges=" + ranges + ");\n"
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

	private static void assertRuntimeRoundTrip(DataOp anchor, FType type, long[][] expectedRanges) {
		DataOp target = matrix("G014_RED2_KEY_TARGET_" + type);
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block(
			HopRewriteUtils.createBinary(target, anchor, OpOp2.PLUS)))));
		PlacementAnalysis analysis = program.bindPlacementAnalysisAtFinalHopBoundary();
		ExactPlacementRegistration.RegisteredUpload upload = ExactPlacementRegistration.registerProgram(program,
			Map.of(anchor.getHopID(), type, target.getHopID(), type), analysis).uploads().get(0);
		FederationMap rebuilt = FederationUtils.buildAnchorMapFromKey(upload.anchorKey());
		Assert.assertNotNull("G014_RED2_RUNTIME_KEY_NOT_DECODABLE_" + type, rebuilt);
		Assert.assertSame("G014_RED2_RUNTIME_KEY_FTYPE_" + type, type, rebuilt.getType());
		Assert.assertEquals("G014_RED2_RUNTIME_KEY_PARTITIONS_" + type,
			expectedRanges.length, rebuilt.getFederatedRanges().length);
		for(int i = 0; i < expectedRanges.length; i++) {
			Assert.assertArrayEquals("G014_RED2_RUNTIME_KEY_BEGIN_" + type + '_' + i,
				new long[] {expectedRanges[i][0], expectedRanges[i][1]},
				rebuilt.getFederatedRanges()[i].getBeginDims());
			Assert.assertArrayEquals("G014_RED2_RUNTIME_KEY_END_" + type + '_' + i,
				new long[] {expectedRanges[i][2], expectedRanges[i][3]},
				rebuilt.getFederatedRanges()[i].getEndDims());
		}
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = compileWithoutHopRewrites(script);
		new DMLTranslator(program).rewriteHopsDAG(program);
		return program;
	}

	private static DMLProgram compileWithoutHopRewrites(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
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

	private static void assertUploadsImmutable(String failure,
		List<ExactPlacementRegistration.RegisteredUpload> values) {
		try {
			values.clear();
			Assert.fail(failure);
		}
		catch(UnsupportedOperationException expected) {
			// behavioral immutability, independent of collection implementation class
		}
	}

	private static void assertAnchorsImmutable(String failure, List<DurableAnchorKey> values) {
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
