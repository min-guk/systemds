/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NormalizedCandidateInputs;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Test-only owner proof for deriving DP upload projection from immutable placement facts. */
public class CampaignBG011DpEstimatorUploadProjectionOwnerProofTest {
	private static final int WORKERS = 3;

	@Test
	public void singletonRowAndColProjectionMatchesCurrentPolicyAndUploadCostBits() throws Exception {
		assertSingletonParity(FType.ROW, 4, 2, 4, 2, FType.ROW);
		assertSingletonParity(FType.ROW, 4, 2, 3, 2, FType.BROADCAST);
		assertSingletonParity(FType.COL, 2, 4, 2, 4, FType.COL);
		assertSingletonParity(FType.COL, 2, 4, 2, 3, FType.BROADCAST);
	}

	@Test
	public void canonicalEnumeratorPublishesTypedProjectionReceiptAndSelectedPlanUploadBits() throws Exception {
		CanonicalFixture fixture = canonicalFixture();
		DpEnumerationResult result = FederatedPlannerDpCostEnumerator.enumerateProgramWithReceipts(
			fixture.program(), fixture.memo(), false, fixture.analysis());
		PreSelectionSemanticBlock block = result.semanticBlock();
		CandidateDecisionReceipt decision = exactDecision(block, fixture.targetOccurrence());
		NormalizedCandidateInputs normalized = DpPlacementAdapter.normalizeCandidateInputs(
			block.context(), fixture.targetOccurrence(), List.of(), List.of(), List.of(), Map.of(), fixture.memo());
		Assert.assertSame("canonical analysis authority", fixture.analysis(), block.context().analysis());
		Assert.assertSame("receipt context identity", block.context(), decision.context());
		Assert.assertEquals("normalized occurrence key", decision.candidateSnapshot().parentOccurrence(),
			normalized.snapshot().parentOccurrence());
		Assert.assertEquals("normalized oracle inputs", decision.orderedOracleInputs(),
			normalized.snapshot().orderedOracleInputs());
		FType projected = PlacementCandidateRuleResolver.projectConsumerSafeType(
			decision.logicalFType(), decision.invocationEvidence().projection());
		Assert.assertSame("typed receipt projects the row anchor mismatch to BROADCAST", FType.BROADCAST, projected);
		FedPlan cpFout = exactPlan(fixture.memo(), fixture.targetOccurrence(), FederatedOutput.FOUT, ExecType.CP);
		FedPlan cpLout = exactPlan(fixture.memo(), fixture.targetOccurrence(), FederatedOutput.LOUT, ExecType.CP);
		Assert.assertSame("memo-selected FOUT plan", cpFout,
			fixture.memo().getFedPlanAfterPrune(fixture.targetOccurrence(), FederatedOutput.FOUT));
		Assert.assertEquals("CP variants retain identical child edges",
			cpLout.getChildFedPlans(), cpFout.getChildFedPlans());
		Assert.assertSame("selected CP/FOUT plan FType", projected, cpFout.getFType());
		Assert.assertSame("selected CP/FOUT materialization type", projected, cpFout.getCpFoutType());
		Assert.assertTrue("selected CP/FOUT materialization is accounted",
			cpFout.isFoutMaterializationAccounted());
		Assert.assertSame("ExactEstimator binds the same canonical analysis", fixture.analysis(),
			FederatedPlannerDpCostEstimator.bindExact(fixture.analysis(), fixture.targetOccurrence(), fixture.memo())
				.estimate(cpFout).analysis());
		double expectedBoundary = cpFout.getComputeWeight() * cpFout.getMultiplicity()
			* FederatedCostModel.computeUploadNetworkCost(CANONICAL_FALLBACK_PAYLOAD, projected,
				block.context().numWorkers());
		double rawDelta = cpFout.getCumulativeCost() - cpLout.getCumulativeCost();
		Assert.assertEquals("selected CP/FOUT upload boundary bits",
			Double.doubleToRawLongBits(expectedBoundary), Double.doubleToRawLongBits(rawDelta));
	}

	@Test
	public void unknownScalarMissingAndAmbiguousCasesNeverGuess() throws Exception {
		Fixture unknown = fixture(FType.ROW, 4, 2, -1, -1);
		IndependentComparator unknownComparator = new IndependentComparator(unknown.analysis());
		Projection unknownProjection = unknownComparator.project(unknown.target(), FType.ROW, WORKERS);
		Assert.assertSame(Disposition.UNKNOWN_SHAPE, unknownProjection.disposition());
		Assert.assertSame(FType.ROW, unknownProjection.projectedType());
		Assert.assertEquals(0L, unknownProjection.uploadCostBits());

		Fixture scalar = fixture(FType.ROW, 4, 2, 1, 1);
		HopOccurrenceProjection scalarOccurrence = scalar.analysis().occurrences().stream()
			.filter(o -> o.hop().getDataType() == DataType.SCALAR).findFirst().orElseThrow();
		Projection scalarProjection = new IndependentComparator(scalar.analysis())
			.project(scalarOccurrence, FType.ROW, WORKERS);
		Assert.assertSame(Disposition.ZERO_TRANSFER, scalarProjection.disposition());
		Assert.assertEquals(Double.doubleToRawLongBits(0.0), scalarProjection.uploadCostBits());

		DMLProgram missingProgram = compile("S=matrix(1,3,2);\nprint(sum(S));\n");
		PlacementAnalysis missingAnalysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(missingProgram);
		Projection missing = new IndependentComparator(missingAnalysis).project(
			matrixOccurrence(missingAnalysis, 3, 2, false), FType.ROW, WORKERS);
		Assert.assertSame(Disposition.MISSING_ANCHOR, missing.disposition());
		Assert.assertNull(missing.projectedType());
		Assert.assertEquals(0L, missing.uploadCostBits());

		DMLProgram ambiguousProgram = compile(federated("X", FType.ROW, 4, 2)
			+ federated("Z", FType.COL, 2, 4)
			+ "S=matrix(1,4,2);\nprint(sum(X)+sum(Z)+sum(S));\n");
		PlacementAnalysis ambiguousAnalysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(ambiguousProgram);
		Projection ambiguous = new IndependentComparator(ambiguousAnalysis).project(
			matrixOccurrence(ambiguousAnalysis, 4, 2, false), FType.ROW, WORKERS);
		Assert.assertSame(Disposition.AMBIGUOUS_ANCHOR, ambiguous.disposition());
		Assert.assertNull(ambiguous.projectedType());
		Assert.assertEquals(0L, ambiguous.uploadCostBits());
	}

	@Test
	public void ownerIdentityAndAllObservableStateRemainUnchanged() throws Exception {
		Fixture owner = fixture(FType.ROW, 4, 2, 4, 2);
		IndependentComparator comparator = new IndependentComparator(owner.analysis());
		AnalysisSnapshot before = snapshot(owner.analysis());
		Map<String, FederatedPlannerUtils.FedVarSnapshot> registryBefore = FederatedPlannerUtils.snapshotFedState();

		Projection projection = comparator.project(owner.target(), FType.ROW, WORKERS);
		Assert.assertSame(owner.analysis(), projection.analysis());
		Assert.assertSame(owner.target(), projection.occurrence());
		assertSnapshotSame(before, snapshot(owner.analysis()));
		Assert.assertEquals(registryBefore, FederatedPlannerUtils.snapshotFedState());

		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(owner.program());
		HopOccurrenceProjection copiedOccurrence = copied.occurrences().stream()
			.filter(o -> o.key().equals(owner.target().key())).findFirst().orElseThrow();
		expectReject(() -> comparator.project(copiedOccurrence, FType.ROW, WORKERS));
		Fixture foreign = fixture(FType.COL, 2, 4, 2, 4);
		expectReject(() -> comparator.project(foreign.target(), FType.COL, WORKERS));
		assertSnapshotSame(before, snapshot(owner.analysis()));
		Assert.assertEquals(registryBefore, FederatedPlannerUtils.snapshotFedState());
	}

	private static void assertSingletonParity(FType anchorType, long anchorRows, long anchorCols,
		long targetRows, long targetCols, FType expected) throws Exception {
		Fixture fixture = fixture(anchorType, anchorRows, anchorCols, targetRows, targetCols);
		AnalysisSnapshot before = snapshot(fixture.analysis());
		IndependentComparator comparator = new IndependentComparator(fixture.analysis());
		Projection independent = comparator.project(fixture.target(), anchorType, WORKERS);
		Projection typedCost = typedEstimatorCostOracle(fixture, anchorType, WORKERS);

		Assert.assertSame(Disposition.ACCEPTED_SINGLETON, independent.disposition());
		Assert.assertSame(expected, independent.projectedType());
		Assert.assertSame(typedCost.projectedType(), independent.projectedType());
		Assert.assertEquals(typedCost.uploadCostBits(), independent.uploadCostBits());
		assertSnapshotSame(before, snapshot(fixture.analysis()));
	}

	private static Projection typedEstimatorCostOracle(Fixture fixture, FType logicalType, int workers) {
		Projection typed = new IndependentComparator(fixture.analysis()).project(fixture.target(), logicalType, workers);
		double cost = FederatedPlannerDpCostEstimator.computeUploadCostWithFallback(
			fixture.target().hop(), null, typed.projectedType(), workers);
		return new Projection(fixture.analysis(), fixture.target(), typed.disposition(), typed.anchor(),
			typed.projectedType(), Double.doubleToRawLongBits(cost));
	}


	private static CandidateDecisionReceipt exactDecision(PreSelectionSemanticBlock block,
		HopOccurrenceProjection occurrence) {
		List<CandidateDecisionReceipt> matches = block.candidateDecisionReceipts().stream()
			.filter(candidate -> candidate.candidateSnapshot().parentOccurrence() == occurrence.key()).toList();
		Assert.assertEquals("target must retain one exact candidate decision", 1, matches.size());
		CandidateDecisionReceipt decision = matches.get(0);
		int index = identityIndex(block.candidateDecisionReceipts(), decision);
		Assert.assertTrue("target decision must be retained by exact identity", index >= 0);
		Assert.assertSame("decision snapshot must be retained by exact identity",
			block.candidateSnapshots().get(index), decision.candidateSnapshot());
		Assert.assertEquals("decision variant ordinal", block.candidateVariantOrdinals().get(index).longValue(),
			decision.variantOrdinal());
		return decision;
	}

	private static FedPlan exactPlan(FederatedPlannerDpMemoTable memo, HopOccurrenceProjection occurrence,
		FederatedOutput output, ExecType exec) {
		FedPlanVariants variants = memo.getFedPlanVariants(Pair.of(occurrence.hop().getHopID(), output));
		Assert.assertNotNull("missing target " + output + " variants", variants);
		List<FedPlan> matches = variants.getFedPlanVariants().stream()
			.filter(plan -> plan.getExecType() == exec).toList();
		Assert.assertFalse("target must retain a " + exec + '/' + output + " plan", matches.isEmpty());
		return matches.get(0);
	}

	private static int identityIndex(List<CandidateDecisionReceipt> values, CandidateDecisionReceipt target) {
		for(int i = 0; i < values.size(); i++)
			if(values.get(i) == target) return i;
		return -1;
	}

	private static final class IndependentComparator {
		private final PlacementAnalysis analysis;
		private final List<HopOccurrenceProjection> exactOccurrences;

		private IndependentComparator(PlacementAnalysis analysis) {
			this.analysis = analysis;
			this.exactOccurrences = List.copyOf(analysis.occurrences());
		}

		private Projection project(HopOccurrenceProjection occurrence, FType logicalType, int workers) {
			if(exactOccurrences.stream().noneMatch(candidate -> candidate == occurrence)
				|| analysis.hop(occurrence.key()).orElse(null) != occurrence.hop())
				throw new IllegalArgumentException("Occurrence is foreign or copied");
			NodeShapeFact shape = analysis.shapeFact(occurrence.key()).orElseThrow();
			if(shape.dataType() != DataType.MATRIX)
				return new Projection(analysis, occurrence, Disposition.ZERO_TRANSFER,
					null, logicalType, Double.doubleToRawLongBits(0.0));
			Set<DurableAnchorKey> anchors = distinctAnchors(analysis);
			if(anchors.isEmpty())
				return new Projection(analysis, occurrence, Disposition.MISSING_ANCHOR, null, null, 0L);
			if(anchors.size() != 1)
				return new Projection(analysis, occurrence, Disposition.AMBIGUOUS_ANCHOR, null, null, 0L);
			DurableAnchorKey anchor = anchors.iterator().next();
			if(!shape.knownPositiveMatrix())
				return new Projection(analysis, occurrence, Disposition.UNKNOWN_SHAPE,
					anchor, logicalType, 0L);

			FType projected = projectType(shape, logicalType, anchor);
			double immutableMemEstimate = OptimizerUtils.estimateSize(shape.rows(), shape.cols());
			double cost = FederatedCostModel.computeUploadNetworkCost(immutableMemEstimate, projected, workers)
				+ FederatedCostModel.computeLocalToFedForwardingPenalty(projected, workers);
			return new Projection(analysis, occurrence, Disposition.ACCEPTED_SINGLETON,
				anchor, projected, Double.doubleToRawLongBits(cost));
		}
	}

	private static FType projectType(NodeShapeFact shape, FType logicalType, DurableAnchorKey anchor) {
		if(logicalType == null || logicalType == FType.BROADCAST)
			return logicalType;
		FType vectorAxis = shape.rows() == 1 && shape.cols() > 1 ? FType.COL
			: shape.cols() == 1 && shape.rows() > 1 ? FType.ROW : null;
		if(vectorAxis != null && vectorAxis != anchor.fType())
			return FType.BROADCAST;
		long hopAxis = anchor.fType() == FType.ROW ? shape.rows() : shape.cols();
		long anchorAxis = anchor.partitions().stream()
			.mapToLong(partition -> partition.end().get(anchor.fType() == FType.ROW ? 0 : 1)).max().orElse(-1);
		return hopAxis > 0 && anchorAxis >= 0 && hopAxis != anchorAxis ? FType.BROADCAST : logicalType;
	}

	private static Fixture fixture(FType anchorType, long anchorRows, long anchorCols,
		long targetRows, long targetCols) throws Exception {
		String target = targetRows > 0 && targetCols > 0
			? "S=matrix(1," + targetRows + ',' + targetCols + ");\n"
			: "S=matrix(1," + anchorRows + ',' + anchorCols + ");\n";
		DMLProgram program = compile(federated("X", anchorType, anchorRows, anchorCols)
			+ target + "print(sum(X)+sum(S));\n");
		if(targetRows <= 0 || targetCols <= 0) {
			Hop unknown = program.getStatementBlocks().stream().flatMap(sb -> sb.getHops().stream())
				.flatMap(root -> collect(root).stream())
				.filter(hop -> hop.getDataType() == DataType.MATRIX && !hop.isFederatedDataOp()
					&& hop.getDim1() == anchorRows && hop.getDim2() == anchorCols)
				.findFirst().orElseThrow();
			unknown.setDim1(-1);
			unknown.setDim2(-1);
		}
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		HopOccurrenceProjection occurrence = targetRows > 0 && targetCols > 0
			? matrixOccurrence(analysis, targetRows, targetCols, false)
			: analysis.occurrences().stream().filter(o -> o.hop().getDataType() == DataType.MATRIX
				&& (o.hop().getDim1() <= 0 || o.hop().getDim2() <= 0)).findFirst().orElseThrow();
		return new Fixture(program, analysis, occurrence);
	}

	private static CanonicalFixture canonicalFixture() throws Exception {
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
		Assert.assertSame("canonical fixture authority", analysis, program.requirePlacementAnalysisAuthority());
		HopOccurrenceProjection occurrence = analysis.occurrences().stream()
			.filter(candidate -> candidate.hop() == target).findFirst().orElseThrow();
		return new CanonicalFixture(program, analysis, occurrence, new FederatedPlannerDpMemoTable(analysis));
	}

	private static String anchoredGeometry() {
		return "L=matrix(0,4,2);\n"
			+ "X=federated(local_matrix=L,addresses=list(\"localhost:1234\",\"localhost:1235\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "print(sum(X));\n";
	}

	private static List<Hop> collect(Hop root) {
		List<Hop> result = new ArrayList<>();
		Set<Hop> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		java.util.ArrayDeque<Hop> pending = new java.util.ArrayDeque<>();
		pending.push(root);
		while(!pending.isEmpty()) {
			Hop hop = pending.pop();
			if(!seen.add(hop))
				continue;
			result.add(hop);
			for(Hop input : hop.getInput())
				pending.push(input);
		}
		return result;
	}

	private static HopOccurrenceProjection matrixOccurrence(PlacementAnalysis analysis,
		long rows, long cols, boolean federated) {
		return analysis.occurrences().stream().filter(o -> o.hop().getDataType() == DataType.MATRIX
			&& o.hop().getDim1() == rows && o.hop().getDim2() == cols
			&& o.hop().isFederatedDataOp() == federated).findFirst().orElseThrow();
	}

	private static Set<DurableAnchorKey> distinctAnchors(PlacementAnalysis analysis) {
		Set<DurableAnchorKey> anchors = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
			anchors.addAll(node.anchors());
		return Set.copyOf(anchors);
	}


	private static String federated(String variable, FType type, long rows, long cols) {
		if(type == FType.ROW)
			return variable + "=federated(addresses=list(\"localhost:1234/A\",\"localhost:1235/B\"),"
				+ "ranges=list(list(0,0),list(" + rows / 2 + ',' + cols + "),list(" + rows / 2
				+ ",0),list(" + rows + ',' + cols + ")));\n";
		return variable + "=federated(addresses=list(\"localhost:1234/A\",\"localhost:1235/B\"),"
			+ "ranges=list(list(0,0),list(" + rows + ',' + cols / 2 + "),list(0," + cols / 2
			+ "),list(" + rows + ',' + cols + ")));\n";
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

	private static AnalysisSnapshot snapshot(PlacementAnalysis analysis) {
		List<HopState> hops = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			hops.add(new HopState(occurrence, occurrence.hop(), occurrence.hop().getDim1(),
				occurrence.hop().getDim2(), occurrence.hop().getDataType()));
		return new AnalysisSnapshot(analysis, analysis.graph(), analysis.analysisFingerprint(),
			List.copyOf(analysis.occurrences()), analysis.graph().normalizedIdentities(),
			analysis.graph().normalizedAnchors(), analysis.graph().normalizedRelocationActions(), List.copyOf(hops));
	}

	private static void assertSnapshotSame(AnalysisSnapshot expected, AnalysisSnapshot actual) {
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertSame(expected.graph(), actual.graph());
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		Assert.assertEquals(expected.identities(), actual.identities());
		Assert.assertEquals(expected.anchors(), actual.anchors());
		Assert.assertEquals(expected.relocations(), actual.relocations());
		Assert.assertEquals(expected.occurrences().size(), actual.occurrences().size());
		for(int i = 0; i < expected.occurrences().size(); i++) {
			Assert.assertSame(expected.occurrences().get(i), actual.occurrences().get(i));
			Assert.assertEquals(expected.hops().get(i), actual.hops().get(i));
		}
	}

	private static void expectReject(Runnable action) {
		try {
			action.run();
			Assert.fail("accepted foreign or copied occurrence");
		}
		catch(IllegalArgumentException expected) {
			// Exact analysis ownership is required.
		}
	}

	private static final double CANONICAL_FALLBACK_PAYLOAD = 4096.0;

	private enum Disposition { ACCEPTED_SINGLETON, AMBIGUOUS_ANCHOR, MISSING_ANCHOR, UNKNOWN_SHAPE, ZERO_TRANSFER }
	private record Projection(PlacementAnalysis analysis, HopOccurrenceProjection occurrence,
		Disposition disposition, DurableAnchorKey anchor, FType projectedType, long uploadCostBits) { }
	private record Fixture(DMLProgram program, PlacementAnalysis analysis, HopOccurrenceProjection target) { }
	private record CanonicalFixture(DMLProgram program, PlacementAnalysis analysis,
		HopOccurrenceProjection targetOccurrence, FederatedPlannerDpMemoTable memo) { }
	private record HopState(HopOccurrenceProjection occurrence, Hop hop, long rows, long cols, DataType dataType) { }
	private record AnalysisSnapshot(PlacementAnalysis analysis, NeutralPlacementGraph graph, String fingerprint,
		List<HopOccurrenceProjection> occurrences, List<String> identities, List<String> anchors,
		List<String> relocations, List<HopState> hops) { }

	private static final class ControlledMemoryHop extends DataOp {
		private ControlledMemoryHop() {
			super("S", DataType.MATRIX, ValueType.FP64, OpOpData.PERSISTENTREAD,
				"G011_CANONICAL_CONTROLLED_MEMORY", 1, 2, -1, 1024);
		}

		@Override public double getInputMemEstimate() { return CANONICAL_FALLBACK_PAYLOAD; }
		@Override public double getOutputMemEstimate() { return Double.NaN; }
	}
}
