/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/** Behavioral RED for the canonical DP enumerator's CP/FOUT upload boundary. */
public class CampaignBG014CanonicalDpEnumeratorProjectedUploadRedTest {
	private static final double FALLBACK_PAYLOAD = 4096.0;

	@Test
	@Ignore("IGNORE_PUBLIC: production-captured targetPrivacy=PUBLIC; see decision artifact SHA 79c0a486080b6d7a2554fbc488aaa9ccfb837a31f6797d9ffb4fa790ab7a1f66")
	public void canonicalEnumeratorRecoversNaNUploadFromExactProjectedReceipt() {
		Fixture fixture = fixture();
		ProgramState before = snapshotProgram(fixture);
		DpEnumerationResult result = FederatedPlannerDpCostEnumerator.enumerateProgramWithReceipts(
			fixture.program(), fixture.memo(), false, fixture.analysis());

		Assert.assertEquals("raw controlled output estimate", bits(Double.NaN),
			bits(fixture.target().getOutputMemEstimate()));
		Assert.assertEquals("effective controlled output estimate", bits(Double.NaN),
			bits(FederatedCostModel.getEffectiveOutputMemEstimate(fixture.target())));
		Assert.assertEquals("effective controlled upload estimate", bits(Double.NaN),
			bits(FederatedCostModel.getEffectiveUploadMemEstimate(fixture.target())));
		Assert.assertEquals("finite exact input fallback", bits(FALLBACK_PAYLOAD),
			bits(FederatedCostModel.getEffectiveInputMemEstimate(fixture.target())));
		assertProgramSame(before, snapshotProgram(fixture));

		PreSelectionSemanticBlock block = result.semanticBlock();
		CandidateDecisionReceipt decision = exactDecision(block, fixture.targetOccurrence());
		Assert.assertSame("exact decision context", block.context(), decision.context());
		Assert.assertSame("exact decision candidate", fixture.targetOccurrence().key(),
			decision.candidateSnapshot().parentOccurrence());
		Assert.assertSame("exact invocation evidence",
			block.context().invocationEvidence().get(fixture.targetOccurrence().key()), decision.invocationEvidence());
		FType projected = PlacementCandidateRuleResolver.projectConsumerSafeType(
			decision.logicalFType(), decision.invocationEvidence().projection());
		Assert.assertSame("consumer-safe invocation projection", FType.BROADCAST, projected);

		FedPlan cpFout = exactPlan(fixture.memo(), fixture.targetOccurrence(), FederatedOutput.FOUT,
			ExecType.CP, null);
		FedPlan cpLout = exactPlan(fixture.memo(), fixture.targetOccurrence(), FederatedOutput.LOUT,
			ExecType.CP, cpFout.getChildFedPlans());
		Assert.assertSame("memo-selected FOUT plan must be the exact CP/FOUT plan", cpFout,
			fixture.memo().getFedPlanAfterPrune(fixture.targetOccurrence(), FederatedOutput.FOUT));
		Assert.assertEquals("paired CP variants must retain identical child edges",
			cpLout.getChildFedPlans(), cpFout.getChildFedPlans());
		Assert.assertSame("CP/FOUT logical type", projected, cpFout.getFType());
		Assert.assertSame("CP/FOUT materialization type", projected, cpFout.getCpFoutType());
		Assert.assertTrue("CP/FOUT materialization must be accounted", cpFout.isFoutMaterializationAccounted());

		int workers = block.context().numWorkers();
		Assert.assertEquals("enumerator and semantic receipt worker count", workers, fixture.memo().getNumWorkers());
		Assert.assertTrue("fixture must exercise a real multi-worker anchor", workers > 1);
		double placementWeight = cpFout.getComputeWeight() * cpFout.getMultiplicity();
		double expectedBoundary = placementWeight * FederatedCostModel.computeUploadNetworkCost(
			FALLBACK_PAYLOAD, projected, workers);
		double forwarding = placementWeight * FederatedCostModel.computeLocalToFedForwardingPenalty(
			projected, workers);
		double rawDelta = cpFout.getCumulativeCost() - cpLout.getCumulativeCost();
		Assert.assertTrue("expected projected upload boundary must be finite", Double.isFinite(expectedBoundary));
		Assert.assertTrue("expected projected upload boundary must be positive", expectedBoundary > 0.0);
		Assert.assertFalse("CP/FOUT must not add the LOUT-to-FED forwarding penalty",
			Double.compare(rawDelta, expectedBoundary + forwarding) == 0);

		Assert.assertTrue("G014_RED4_CANONICAL_CP_FOUT_RAW_BOUNDARY_MUST_BE_FINITE",
			Double.isFinite(rawDelta));
		Assert.assertEquals("G014_RED4_CANONICAL_CP_FOUT_RAW_BOUNDARY", expectedBoundary, rawDelta, 0.0);
	}

	@Test
	public void exactEstimatorBindingRejectsCopiedAndForeignEvidenceWithoutMutation() {
		Fixture owner = enumeratedFixture();
		Assert.assertNotNull("exact analysis/occurrence/memo owner must bind",
			FederatedPlannerDpCostEstimator.bindExact(owner.analysis(), owner.targetOccurrence(), owner.memo()));

		HopOccurrenceProjection occurrence = owner.targetOccurrence();
		HopOccurrenceProjection copied = new HopOccurrenceProjection(occurrence.key(), occurrence.hop(),
			occurrence.scopeId(), occurrence.normalizedOrdinal(), occurrence.normalizedSignature());
		Assert.assertEquals("copy must retain the same record values", occurrence, copied);
		Assert.assertNotSame("copy must not retain occurrence identity", occurrence, copied);
		MemoState ownerBefore = snapshotMemo(owner);
		assertBindingRejected(owner.analysis(), copied, owner.memo(), "copied same-value occurrence");
		Assert.assertEquals("copied occurrence rejection mutated owner memo", ownerBefore, snapshotMemo(owner));

		Fixture foreign = enumeratedFixture();
		MemoState foreignBefore = snapshotMemo(foreign);
		assertBindingRejected(owner.analysis(), foreign.targetOccurrence(), owner.memo(), "foreign occurrence");
		Assert.assertEquals("foreign occurrence rejection mutated owner memo", ownerBefore, snapshotMemo(owner));
		Assert.assertEquals("foreign occurrence rejection mutated foreign memo", foreignBefore, snapshotMemo(foreign));

		assertBindingRejected(foreign.analysis(), owner.targetOccurrence(), owner.memo(), "foreign analysis");
		Assert.assertEquals("foreign analysis rejection mutated owner memo", ownerBefore, snapshotMemo(owner));
		Assert.assertEquals("foreign analysis rejection mutated foreign memo", foreignBefore, snapshotMemo(foreign));

		FederatedPlannerDpMemoTable foreignMemo = new FederatedPlannerDpMemoTable(foreign.analysis());
		MemoState emptyForeignBefore = snapshotMemo(foreign.analysis(), foreignMemo);
		assertBindingRejected(owner.analysis(), owner.targetOccurrence(), foreignMemo, "foreign memo");
		Assert.assertEquals("foreign memo rejection mutated owner memo", ownerBefore, snapshotMemo(owner));
		Assert.assertEquals("foreign memo rejection mutated supplied memo", emptyForeignBefore,
			snapshotMemo(foreign.analysis(), foreignMemo));
	}

	private static Fixture enumeratedFixture() {
		Fixture fixture = fixture();
		FederatedPlannerDpCostEnumerator.enumerateProgramWithReceipts(
			fixture.program(), fixture.memo(), false, fixture.analysis());
		return fixture;
	}

	private static Fixture fixture() {
		try {
			DMLProgram compiled = compile(anchoredGeometry());
			Hop anchor = compiled.getStatementBlocks().stream().flatMap(block -> block.getHops().stream())
				.flatMap(root -> collect(root).stream()).filter(Hop::isFederatedDataOp).findFirst().orElseThrow();
			anchor.getParent().clear();
			ControlledMemoryHop target = new ControlledMemoryHop();
			Hop consumer = HopRewriteUtils.createBinary(anchor, target, OpOp2.RBIND);
			consumer.setDim1(anchor.getDim1() + target.getDim1());
			consumer.setDim2(anchor.getDim2());
			StatementBlock block = new StatementBlock();
			block.setHops(new ArrayList<>(List.of(consumer)));
			DMLProgram program = new DMLProgram();
			program.setStatementBlocks(new ArrayList<>(List.of(block)));
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
			Assert.assertSame("G014_RED4_FIXTURE_NOT_FINAL_BOUNDARY_AUTHORITY",
				analysis, program.requirePlacementAnalysisAuthority());
			HopOccurrenceProjection occurrence = analysis.occurrences().stream()
				.filter(candidate -> candidate.hop() == target).findFirst().orElseThrow();
			return new Fixture(program, analysis, occurrence, target, new FederatedPlannerDpMemoTable(analysis));
		}
		catch(Exception failure) {
			throw new AssertionError("Unable to construct the canonical G014 RED-4 fixture", failure);
		}
	}

	private static CandidateDecisionReceipt exactDecision(PreSelectionSemanticBlock block,
		HopOccurrenceProjection occurrence) {
		List<CandidateDecisionReceipt> matches = block.candidateDecisionReceipts().stream()
			.filter(candidate -> candidate.candidateSnapshot().parentOccurrence() == occurrence.key()).toList();
		Assert.assertEquals("target must retain one exact candidate decision", 1, matches.size());
		CandidateDecisionReceipt decision = matches.get(0);
		int index = identityIndex(block.candidateDecisionReceipts(), decision);
		Assert.assertTrue("target decision must be retained by exact identity", index >= 0);
		Assert.assertSame("decision snapshot must be the exact retained occurrence",
			block.candidateSnapshots().get(index), decision.candidateSnapshot());
		Assert.assertEquals("decision variant ordinal", block.candidateVariantOrdinals().get(index).longValue(),
			decision.variantOrdinal());
		return decision;
	}

	private static FedPlan exactPlan(FederatedPlannerDpMemoTable memo, HopOccurrenceProjection occurrence,
		FederatedOutput output, ExecType exec, List<Pair<Long, FederatedOutput>> childEdges) {
		FedPlanVariants variants = memo.getFedPlanVariants(Pair.of(occurrence.hop().getHopID(), output));
		Assert.assertNotNull("missing target " + output + " variants", variants);
		List<FedPlan> matches = variants.getFedPlanVariants().stream()
			.filter(plan -> plan.getExecType() == exec)
			.filter(plan -> childEdges == null || plan.getChildFedPlans().equals(childEdges)).toList();
		Assert.assertEquals("target must retain one exact " + exec + '/' + output + " plan", 1, matches.size());
		return matches.get(0);
	}

	private static void assertBindingRejected(PlacementAnalysis analysis, HopOccurrenceProjection occurrence,
		FederatedPlannerDpMemoTable memo, String label) {
		try {
			FederatedPlannerDpCostEstimator.bindExact(analysis, occurrence, memo);
			Assert.fail(label + " unexpectedly bound");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("Exact estimator binding is not analysis-owned", expected.getMessage());
		}
	}

	private static ProgramState snapshotProgram(Fixture fixture) {
		return new ProgramState(PlacementGraphFingerprint.capture(fixture.program()),
			fixture.analysis().analysisFingerprint(), fixture.analysis().occurrences(),
			fixture.analysis().occurrences().stream().map(value -> new HopState(value.hop())).toList());
	}

	private static void assertProgramSame(ProgramState expected, ProgramState actual) {
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		Assert.assertEquals(expected.analysisFingerprint(), actual.analysisFingerprint());
		assertIdentityList(expected.occurrences(), actual.occurrences(), "analysis occurrences");
		Assert.assertEquals(expected.hops(), actual.hops());
	}

	private static MemoState snapshotMemo(Fixture fixture) {
		return snapshotMemo(fixture.analysis(), fixture.memo());
	}

	private static MemoState snapshotMemo(PlacementAnalysis analysis, FederatedPlannerDpMemoTable memo) {
		List<CoordinateState> coordinates = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			for(FederatedOutput output : FederatedOutput.values()) {
				FedPlanVariants variants = memo.getFedPlanVariants(Pair.of(occurrence.hop().getHopID(), output));
				if(variants != null)
					coordinates.add(new CoordinateState(occurrence, output, variants,
						variants.getFedPlanVariants().stream().map(PlanState::new).toList()));
			}
		return new MemoState(memo.analysis(), memo.getNumWorkers(), List.copyOf(coordinates));
	}

	private static int identityIndex(List<CandidateDecisionReceipt> values, CandidateDecisionReceipt target) {
		for(int i = 0; i < values.size(); i++)
			if(values.get(i) == target) return i;
		return -1;
	}

	private static void assertIdentityList(List<HopOccurrenceProjection> expected,
		List<HopOccurrenceProjection> actual, String label) {
		Assert.assertEquals(label + " size", expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++)
			Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	private static String anchoredGeometry() {
		return "L=matrix(0,4,2);\n"
			+ "X=federated(local_matrix=L,addresses=list(\"localhost:1234\",\"localhost:1235\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "print(sum(X));\n";
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
		Map<Hop, Boolean> seen = new IdentityHashMap<>();
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		pending.push(root);
		while(!pending.isEmpty()) {
			Hop hop = pending.pop();
			if(seen.put(hop, Boolean.TRUE) != null) continue;
			result.add(hop);
			for(Hop input : hop.getInput()) pending.push(input);
		}
		return result;
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis,
		HopOccurrenceProjection targetOccurrence, ControlledMemoryHop target, FederatedPlannerDpMemoTable memo) { }

	private record ProgramState(String fingerprint, String analysisFingerprint,
		List<HopOccurrenceProjection> occurrences, List<HopState> hops) { }

	private record HopState(Hop hop, ExecType execType, FederatedOutput output, boolean recompile,
		long rows, long cols, long inputBits, long outputBits, List<Hop> inputs, List<Hop> parents) {
		private HopState(Hop hop) {
			this(hop, hop.getForcedExecType(), hop.getFederatedOutput(), hop.requiresRecompile(),
				hop.getDim1(), hop.getDim2(), bits(hop.getInputMemEstimate()), bits(hop.getOutputMemEstimate()),
				List.copyOf(hop.getInput()), List.copyOf(hop.getParent()));
		}
	}

	private record MemoState(PlacementAnalysis analysis, int workers, List<CoordinateState> coordinates) { }

	private record CoordinateState(HopOccurrenceProjection occurrence, FederatedOutput output,
		FedPlanVariants variants, List<PlanState> plans) { }

	private record PlanState(FedPlan plan, long cumulativeBits, ExecType execType, FType fType, FType cpFoutType,
		boolean derivedFedFout, boolean materializationAccounted, List<Pair<Long, FederatedOutput>> childEdges) {
		private PlanState(FedPlan plan) {
			this(plan, bits(plan.getCumulativeCost()), plan.getExecType(), plan.getFType(), plan.getCpFoutType(),
				plan.isDerivedFedFout(), plan.isFoutMaterializationAccounted(), List.copyOf(plan.getChildFedPlans()));
		}
	}

	private static final class ControlledMemoryHop extends DataOp {
		private ControlledMemoryHop() {
			super("S", DataType.MATRIX, ValueType.FP64, OpOpData.PERSISTENTREAD,
				"G014_RED4_CONTROLLED_MEMORY", 1, 2, -1, 1024);
		}

		@Override public double getInputMemEstimate() { return FALLBACK_PAYLOAD; }
		@Override public double getOutputMemEstimate() { return Double.NaN; }
	}
}
