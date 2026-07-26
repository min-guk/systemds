/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.parser;

import java.util.ArrayList;
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
import org.apache.sysds.hops.fedplanner.placement.ExactPlacementRegistration;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED-1 contract for exact occurrence-keyed registration across independently owned scopes. */
public class CampaignBG014ExactOccurrenceRegistrationRedTest {
	private static final String POISON = "poison.invalid:9/P@0:0-1:1|BROADCAST";

	@Test
	public void sharedTargetAndAnchorEmitOneOrderedReceiptPerExactScope() {
		assertExactRegistration(AnchorMode.SHARED);
	}

	@Test
	public void sharedTargetBindsOnlyItsScopeLocalDistinctAnchor() {
		assertExactRegistration(AnchorMode.DISTINCT_VALUES);
	}

	@Test
	public void sameValueAnchorsBindByExactScopeIdentityNotRuntimeKey() {
		assertExactRegistration(AnchorMode.SAME_VALUE_DISTINCT_IDENTITY);
	}

	private static void assertExactRegistration(AnchorMode mode) {
		Fixture fixture = fixture(mode);
		try {
			assertCanonicalRegistrationBoundary(fixture);
			String fingerprint = fixture.analysis().analysisFingerprint();
			FederatedPlannerUtils.clearFedAnchorKeys();
			for(DataOp anchor : fixture.anchors())
				FederatedPlannerUtils.registerFedAnchorKey(anchor.getName(), POISON);
			ExactPlacementRegistration.Receipt receipt;
			try {
				receipt = ExactPlacementRegistration.registerProgram(
					fixture.program(), fixture.selectedTypes(), fixture.analysis());
			}
			catch(DMLRuntimeException currentCrossScopeUnion) {
				Assert.fail("G014_RED1_EXACT_SCOPE_ANCHORS_WERE_UNIONED|" + currentCrossScopeUnion.getMessage());
				return;
			}

			Assert.assertEquals("G014_RED1_DROPPED_EXACT_OCCURRENCE", 2, receipt.uploads().size());
			assertExactReceipt(fixture, receipt);
			assertExactRegistries(fixture);
			Assert.assertEquals("G014_RED1_ANALYSIS_MUTATED", fingerprint,
				fixture.analysis().analysisFingerprint());
			assertImmutable(receipt.uploads());
			assertForeignAndCopiedAnalysisRejectWithoutMutation(fixture);
		}
		finally {
			FederatedPlannerUtils.clearFedAnchorKeys();
			clearRegistries();
		}
	}

	private static Fixture fixture(AnchorMode mode) {
		DataOp target = matrix("G014_RED1_TARGET");
		target.setForcedExecType(ExecType.CP);
		target.setFederatedOutput(FederatedOutput.FOUT);
		DataOp firstAnchor = literalFederatedAnchor("G014_RED1_ANCHOR_A", 18111);
		DataOp secondAnchor;
		switch(mode) {
			case SHARED:
				secondAnchor = firstAnchor;
				break;
			case DISTINCT_VALUES:
				secondAnchor = literalFederatedAnchor("G014_RED1_ANCHOR_B", 18121);
				break;
			case SAME_VALUE_DISTINCT_IDENTITY:
				secondAnchor = literalFederatedAnchor("G014_RED1_ANCHOR_B", 18111);
				break;
			default:
				throw new AssertionError("Unhandled exact registration fixture mode: " + mode);
		}
		Hop firstParent = HopRewriteUtils.createBinary(target, firstAnchor, OpOp2.PLUS);
		Hop secondParent = HopRewriteUtils.createBinary(target, secondAnchor, OpOp2.MINUS);

		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block(firstParent), block(secondParent))));
		PlacementAnalysis analysis = program.bindPlacementAnalysisAtFinalHopBoundary();
		Assert.assertSame("G014_RED1_FIXTURE_NOT_FINAL_BOUNDARY_AUTHORITY", analysis,
			program.requirePlacementAnalysisAuthority());
		List<PlacementAnalysis.HopOccurrenceProjection> targets = occurrences(analysis, target);
		Assert.assertEquals("G014_RED1_FIXTURE_MUST_PROJECT_TWO_EXACT_TARGET_OCCURRENCES", 2, targets.size());
		Assert.assertNotSame("G014_RED1_FIXTURE_MUST_OWN_DISTINCT_KEYS", targets.get(0).key(), targets.get(1).key());

		List<DataOp> anchors = List.of(firstAnchor, secondAnchor);
		List<PlacementAnalysis.HopOccurrenceProjection> scopedAnchors = new ArrayList<>();
		List<DurableAnchorKey> durableAnchors = new ArrayList<>();
		for(int i = 0; i < targets.size(); i++) {
			long scope = targets.get(i).scopeId();
			PlacementAnalysis.HopOccurrenceProjection anchorOccurrence = occurrences(analysis, anchors.get(i)).stream()
				.filter(value -> value.scopeId() == scope).findFirst().orElseThrow();
			scopedAnchors.add(anchorOccurrence);
			List<DurableAnchorKey> keys = analysis.graph().node(anchorOccurrence.key()).orElseThrow().anchors();
			Assert.assertEquals("G014_RED1_LITERAL_ANCHOR_MUST_OWN_ONE_DURABLE_KEY_" + i, 1, keys.size());
			Assert.assertSame("G014_RED1_LITERAL_ANCHOR_KEY_NOT_ANALYSIS_OWNED_" + i, keys,
				analysis.graph().node(anchorOccurrence.key()).orElseThrow().anchors());
			durableAnchors.add(keys.get(0));
		}
		if(mode == AnchorMode.SAME_VALUE_DISTINCT_IDENTITY) {
			Assert.assertNotSame("G014_RED1_SAME_VALUE_FIXTURE_MUST_USE_DISTINCT_ANCHOR_HOPS",
				firstAnchor, secondAnchor);
			Assert.assertNotSame("G014_RED1_SAME_VALUE_FIXTURE_MUST_USE_DISTINCT_DURABLE_ANCHOR_IDENTITIES",
				durableAnchors.get(0), durableAnchors.get(1));
			Assert.assertEquals("G014_RED1_SAME_VALUE_FIXTURE_MUST_MATCH_RUNTIME_KEYS",
				runtimeKey(durableAnchors.get(0)), runtimeKey(durableAnchors.get(1)));
		}

		Map<Long, FType> selected = new LinkedHashMap<>();
		selected.put(target.getHopID(), FType.ROW);
		selected.put(firstAnchor.getHopID(), FType.ROW);
		selected.put(secondAnchor.getHopID(), FType.ROW);
		return new Fixture(program, analysis, target, targets, anchors, List.copyOf(scopedAnchors),
			List.copyOf(durableAnchors), Map.copyOf(selected));
	}

	private static void assertExactReceipt(Fixture fixture, ExactPlacementRegistration.Receipt receipt) {
		Assert.assertSame("G014_RED1_RECEIPT_ANALYSIS_IDENTITY", fixture.analysis(), receipt.analysis());
		for(int i = 0; i < fixture.targets().size(); i++) {
			ExactPlacementRegistration.RegisteredUpload upload = receipt.uploads().get(i);
			DurableAnchorKey durable = fixture.durableAnchors().get(i);
			Assert.assertEquals("G014_RED1_ORDERED_SCOPE_" + i, fixture.targets().get(i).scopeId(), upload.scopeId());
			Assert.assertEquals("G014_RED1_TARGET_HOP_" + i, fixture.target().getHopID(), upload.hopId());
			Assert.assertEquals("G014_RED1_ANCHOR_HOP_" + i, fixture.anchors().get(i).getHopID(), upload.anchorHopId());
			Assert.assertEquals("G014_RED1_ANCHOR_LABEL_" + i, fixture.anchors().get(i).getName(), upload.anchorLabel());
			Assert.assertEquals("G014_RED1_FTYPE_" + i, FType.ROW, upload.fType());
			Assert.assertEquals("G014_RED1_ANALYSIS_RUNTIME_AUTHORITY_" + i, runtimeKey(durable), upload.anchorKey());
			Assert.assertNotEquals("G014_RED1_MUTABLE_GLOBAL_OVERRULED_ANALYSIS_" + i, POISON, upload.anchorKey());
			Assert.assertFalse("G014_RED1_EXACT_CONSUMERS_MISSING_" + i, upload.consumerHopIds().isEmpty());
		}
	}

	private static void assertCanonicalRegistrationBoundary(Fixture fixture) {
		Assert.assertSame("G014_RED1_PROGRAM_AUTHORITY_NOT_EXACT_ANALYSIS", fixture.analysis(),
			fixture.program().requirePlacementAnalysisAuthority());
		Assert.assertEquals("G014_RED1_BOUNDARY_TARGET_OCCURRENCE_COUNT", 2, fixture.targets().size());
		Assert.assertEquals("G014_RED1_BOUNDARY_ANCHOR_OCCURRENCE_COUNT", 2, fixture.anchorOccurrences().size());
		for(int i = 0; i < fixture.targets().size(); i++) {
			PlacementAnalysis.HopOccurrenceProjection targetOccurrence = fixture.targets().get(i);
			PlacementAnalysis.HopOccurrenceProjection anchorOccurrence = fixture.anchorOccurrences().get(i);
			Assert.assertSame("G014_RED1_TARGET_OCCURRENCE_KEY_IS_NOT_GRAPH_OWNED_" + i,
				fixture.analysis().graph().node(targetOccurrence.key()).orElseThrow().key(), targetOccurrence.key());
			Assert.assertSame("G014_RED1_ANCHOR_OCCURRENCE_KEY_IS_NOT_GRAPH_OWNED_" + i,
				fixture.analysis().graph().node(anchorOccurrence.key()).orElseThrow().key(), anchorOccurrence.key());
			Assert.assertEquals("G014_RED1_SCOPE_LOCAL_TARGET_ANCHOR_SCOPE_" + i,
				targetOccurrence.scopeId(), anchorOccurrence.scopeId());
			Assert.assertNotSame("G014_RED1_TARGET_AND_ANCHOR_OCCURRENCE_MUST_BE_DISTINCT_" + i,
				targetOccurrence.key(), anchorOccurrence.key());
			Assert.assertSame("G014_RED1_DURABLE_ANCHOR_IS_SCOPE_OCCURRENCE_OWNED_" + i,
				fixture.durableAnchors().get(i), fixture.analysis().graph().node(anchorOccurrence.key())
					.orElseThrow().anchors().get(0));
		}
	}

	private static void assertExactRegistries(Fixture fixture) {
		for(int i = 0; i < fixture.targets().size(); i++) {
			long scope = fixture.targets().get(i).scopeId();
			long target = fixture.target().getHopID();
			long anchor = fixture.anchors().get(i).getHopID();
			String key = runtimeKey(fixture.durableAnchors().get(i));
			FederatedRefedRegistry.AnchorSpec refed = FederatedRefedRegistry.snapshot(scope).get(target);
			FederatedFoutMaterializeRegistry.MaterializeSpec fout =
				FederatedFoutMaterializeRegistry.snapshot(scope).get(target);
			Assert.assertNotNull("G014_RED1_MISSING_REFED_SCOPE_" + i, refed);
			Assert.assertNotNull("G014_RED1_MISSING_FOUT_SCOPE_" + i, fout);
			Assert.assertEquals("G014_RED1_REFED_ANCHOR_" + i, anchor, refed.getAnchorHopId());
			Assert.assertEquals("G014_RED1_FOUT_ANCHOR_" + i, anchor, fout.getAnchorHopId());
			Assert.assertEquals("G014_RED1_REFED_RUNTIME_KEY_" + i, key, refed.getAnchorKey());
			Assert.assertEquals("G014_RED1_FOUT_RUNTIME_KEY_" + i, key, fout.getAnchorKey());
			Assert.assertFalse("G014_RED1_REFED_EXACT_CONSUMERS_MISSING_" + i,
				refed.getConsumerHopIds().isEmpty());
		}
	}

	private static void assertForeignAndCopiedAnalysisRejectWithoutMutation(Fixture fixture) {
		RegistrySnapshot before = registrySnapshot(fixture);
		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(fixture.program());
		expectReject(fixture, copied, "G014_RED1_ACCEPTED_COPIED_ANALYSIS");
		Assert.assertEquals("G014_RED1_COPIED_ANALYSIS_MUTATED_REGISTRIES", before, registrySnapshot(fixture));
		DMLProgram foreignProgram = new DMLProgram();
		foreignProgram.setStatementBlocks(new ArrayList<>(List.of(block(matrix("G014_RED1_FOREIGN")))));
		PlacementAnalysis foreign = foreignProgram.bindPlacementAnalysisAtFinalHopBoundary();
		Assert.assertSame("G014_RED1_FOREIGN_NOT_FINAL_BOUNDARY_AUTHORITY", foreign,
			foreignProgram.requirePlacementAnalysisAuthority());
		expectReject(fixture, foreign, "G014_RED1_ACCEPTED_FOREIGN_ANALYSIS");
		Assert.assertEquals("G014_RED1_FOREIGN_ANALYSIS_MUTATED_REGISTRIES", before, registrySnapshot(fixture));
	}

	private static void expectReject(Fixture fixture, PlacementAnalysis candidate, String failure) {
		try {
			ExactPlacementRegistration.registerProgram(fixture.program(), fixture.selectedTypes(), candidate);
			Assert.fail(failure);
		}
		catch(IllegalArgumentException expected) {
			// Exact canonical analysis identity is mandatory and rejection must precede registry mutation.
		}
	}

	private static RegistrySnapshot registrySnapshot(Fixture fixture) {
		return new RegistrySnapshot(fixture.targets().stream()
			.map(value -> FederatedRefedRegistry.snapshot(value.scopeId())).toList(), fixture.targets().stream()
			.map(value -> FederatedFoutMaterializeRegistry.snapshot(value.scopeId())).toList());
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
		String script = name + "=federated(addresses=list(\"localhost:" + port + "/A\",\"localhost:"
			+ (port + 1) + "/B\"),ranges=list(list(0,0),list(5,10),list(5,0),list(10,10)));\n"
			+ "print(sum(" + name + "));\n";
		try {
			DMLProgram parsed = compile(script);
			return (DataOp) parsed.getStatementBlocks().stream().flatMap(value -> value.getHops().stream())
				.flatMap(root -> collect(root).stream()).filter(Hop::isFederatedDataOp).findFirst().orElseThrow();
		}
		catch(Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static DMLProgram compile(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new java.util.HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static List<Hop> collect(Hop root) {
		List<Hop> out = new ArrayList<>();
		java.util.ArrayDeque<Hop> pending = new java.util.ArrayDeque<>();
		java.util.Set<Hop> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		pending.push(root);
		while(!pending.isEmpty()) {
			Hop hop = pending.pop();
			if(!seen.add(hop)) continue;
			out.add(hop);
			hop.getInput().forEach(pending::push);
		}
		return out;
	}

	private static List<PlacementAnalysis.HopOccurrenceProjection> occurrences(PlacementAnalysis analysis, Hop hop) {
		return analysis.occurrences().stream().filter(value -> value.hop() == hop).toList();
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

	private static void assertImmutable(List<ExactPlacementRegistration.RegisteredUpload> uploads) {
		try {
			uploads.clear();
			Assert.fail("G014_RED1_RECEIPT_UPLOADS_MUTABLE");
		}
		catch(UnsupportedOperationException expected) {
			// exact receipt is immutable
		}
	}

	private static void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis, DataOp target,
		List<PlacementAnalysis.HopOccurrenceProjection> targets, List<DataOp> anchors,
		List<PlacementAnalysis.HopOccurrenceProjection> anchorOccurrences, List<DurableAnchorKey> durableAnchors,
		Map<Long, FType> selectedTypes) { }

	private enum AnchorMode {
		SHARED,
		DISTINCT_VALUES,
		SAME_VALUE_DISTINCT_IDENTITY
	}

	private record RegistrySnapshot(List<Map<Long, FederatedRefedRegistry.AnchorSpec>> refed,
		List<Map<Long, FederatedFoutMaterializeRegistry.MaterializeSpec>> fout) { }

}
