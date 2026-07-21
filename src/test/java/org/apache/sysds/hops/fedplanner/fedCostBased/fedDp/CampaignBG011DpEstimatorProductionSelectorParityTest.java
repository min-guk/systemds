/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FedVarSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.ExactPlacementRegistration;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test-only G011 characterization of the production mutable selector against placement-owned facts.
 *
 * <p>The contract intentionally records both equality and inequality.  A green test means that every
 * production branch is characterized without mutation; it does not authorize replacing production
 * selection when a fixture is explicitly classified {@link Parity#DIFFERS}.</p>
 */
public class CampaignBG011DpEstimatorProductionSelectorParityTest {
	private static final Object MUTABLE_SELECTOR_LOCK = new Object();
	private static final int WORKERS = 3;

	@Test
	public void productionSelectorPriorityIsExactlyCharacterizedAcrossRegistryAndCaches() throws Exception {
		synchronized(MUTABLE_SELECTOR_LOCK) {
			MutableStateSnapshot outer = snapshotMutableState();
			assertNoOpaqueCacheAtEntry();
			try {
				Fixture row = fixture(federated("X", FType.ROW, 4, 2)
					+ "S=matrix(1,4,2); print(sum(X)+sum(S));\n", 4, 2);
				Fixture rowCol = fixture(federated("X", FType.ROW, 4, 2)
					+ federated("Z", FType.COL, 2, 4)
					+ "S=matrix(1,4,2); print(sum(X)+sum(Z)+sum(S));\n", 4, 2);
				DurableAnchorKey rowAnchor = anchor(row.analysis(), FType.ROW);
				DurableAnchorKey colAnchor = anchor(rowCol.analysis(), FType.COL);
				String rowSignature = signature(rowAnchor);
				String colSignature = signature(colAnchor);

				assertSelector("no registry/no cache", row.analysis(), SelectorDisposition.NONE,
					Parity.DIFFERS, () -> { });
				assertSelector("cached-only program", row.analysis(), SelectorDisposition.ROW,
					Parity.EQUAL, () -> cacheFromProgramOnly(row.program()));

				DMLProgram functionProgram = compile("f = function() return (matrix[double] Y) {\n"
					+ federated("Y", FType.COL, 2, 4) + "}\nR=f(); print(sum(R));\n");
				FunctionStatementBlock function = functionProgram.getFunctionStatementBlock(
					DMLProgram.DEFAULT_NAMESPACE, "f");
				PlacementAnalysis functionAnalysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(functionProgram);
				assertSelector("cached-only function currently exposes no global selector",
					functionAnalysis, SelectorDisposition.NONE, Parity.DIFFERS,
					() -> cacheFromFunctionOnly(function));

				assertSelector("single signature without key", row.analysis(), SelectorDisposition.ROW,
					Parity.EQUAL, () -> {
					FederatedPlannerUtils.registerFedInitVar("G011_SINGLE", FType.ROW, rowSignature);
					FederatedPlannerUtils.removeFedAnchorKey("G011_SINGLE");
				});
				assertSelector("non-variable key overrides signature", rowCol.analysis(), SelectorDisposition.COL,
					Parity.DIFFERS, () -> {
					FederatedPlannerUtils.registerFedInitVar("G011_KEY", FType.ROW, rowSignature);
					FederatedPlannerUtils.registerFedAnchorKey("G011_KEY", colSignature + "|COL");
				});
				assertSelector("same-signature multi-entry", row.analysis(), SelectorDisposition.ROW,
					Parity.EQUAL, () -> {
					FederatedPlannerUtils.registerFedInitVar("G011_SAME_A", FType.ROW, rowSignature);
					FederatedPlannerUtils.registerFedInitVar("G011_SAME_B", FType.ROW, rowSignature);
				});
				assertSelector("divergent signatures without cache", rowCol.analysis(), SelectorDisposition.NONE,
					Parity.EQUAL, () -> registerDivergent(rowSignature, colSignature));
				assertSelector("divergent signatures with cached fallback", row.analysis(), SelectorDisposition.ROW,
					Parity.EQUAL, () -> {
					cacheFromProgramOnly(row.program());
					registerDivergent(rowSignature, colSignature);
				});
				assertSelector("graph-wide two-anchor ambiguity differs from selected registry anchor",
					rowCol.analysis(), SelectorDisposition.ROW, Parity.DIFFERS, () ->
						FederatedPlannerUtils.registerFedInitVar("G011_GRAPH_MISMATCH", FType.ROW, rowSignature));
			}
			finally {
				clearSelectorState();
				assertMutableStateSame(outer, snapshotMutableState());
			}
		}
	}

	@Test
	public void projectionParityCoversShapesVectorsAmbiguityAndAggBinaryExceptions() throws Exception {
		synchronized(MUTABLE_SELECTOR_LOCK) {
			MutableStateSnapshot outer = snapshotMutableState();
			assertNoOpaqueCacheAtEntry();
			try {
				assertProjectionCase(FType.ROW, 4, 2, 4, 2, FType.ROW, FType.ROW, "ROW matrix match");
				assertProjectionCase(FType.ROW, 4, 2, 3, 2, FType.ROW, FType.BROADCAST, "ROW matrix mismatch");
				assertProjectionCase(FType.COL, 2, 4, 2, 4, FType.COL, FType.COL, "COL matrix match");
				assertProjectionCase(FType.COL, 2, 4, 2, 3, FType.COL, FType.BROADCAST, "COL matrix mismatch");
				assertProjectionCase(FType.ROW, 4, 2, 4, 1, FType.ROW, FType.ROW, "row vector match");
				assertProjectionCase(FType.COL, 2, 4, 4, 1, FType.COL, FType.BROADCAST, "row vector mismatch");
				assertProjectionCase(FType.COL, 2, 4, 1, 4, FType.COL, FType.COL, "column vector match");
				assertProjectionCase(FType.ROW, 4, 2, 1, 4, FType.ROW, FType.BROADCAST, "column vector mismatch");

				Fixture unknown = fixture(federated("X", FType.ROW, 4, 2)
					+ "S=matrix(1,4,2); print(sum(X)+sum(S));\n", 4, 2);
				unknown.target().hop().setDim1(-1);
				unknown.target().hop().setDim2(-1);
				PlacementAnalysis unknownAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(unknown.program());
				HopOccurrenceProjection unknownOccurrence = unknownAnalysis.occurrences().stream()
					.filter(o -> o.hop() == unknown.target().hop()).findFirst().orElseThrow();
				assertIndependentDisposition(unknownAnalysis, unknownOccurrence, FType.ROW,
					ProjectionDisposition.UNKNOWN_SHAPE, "unknown");

				Fixture scalarFixture = fixture(federated("X", FType.ROW, 4, 2)
					+ "S=matrix(1,4,2); print(sum(X)+sum(S));\n", 4, 2);
				HopOccurrenceProjection scalar = scalarFixture.analysis().occurrences().stream()
					.filter(o -> o.hop().getDataType() == DataType.SCALAR).findFirst().orElseThrow();
				assertIndependentDisposition(scalarFixture.analysis(), scalar, FType.ROW,
					ProjectionDisposition.ZERO_TRANSFER, "scalar");

				Fixture missing = fixture("S=matrix(1,4,2); print(sum(S));\n", 4, 2);
				assertIndependentDisposition(missing.analysis(), missing.target(), FType.ROW,
					ProjectionDisposition.MISSING_ANCHOR, "missing");
				Fixture ambiguous = fixture(federated("X", FType.ROW, 4, 2)
					+ federated("Z", FType.COL, 2, 4)
					+ "S=matrix(1,4,2); print(sum(X)+sum(Z)+sum(S));\n", 4, 2);
				assertIndependentDisposition(ambiguous.analysis(), ambiguous.target(), FType.ROW,
					ProjectionDisposition.AMBIGUOUS_ANCHOR, "ambiguous");

				assertAggBinarySharedDimensionCases();
			}
			finally {
				clearSelectorState();
				assertMutableStateSame(outer, snapshotMutableState());
			}
		}
	}

	@Test
	public void estimatorFallbackRawBitsExposePrimaryAndSecondProjectionBehavior() throws Exception {
		synchronized(MUTABLE_SELECTOR_LOCK) {
			MutableStateSnapshot outer = snapshotMutableState();
			assertNoOpaqueCacheAtEntry();
			try {
				String rowSignature = "g011-fallback|0,4;";
				FederatedPlannerUtils.registerFedInitVar("G011_FALLBACK", FType.ROW, rowSignature);

				FallbackHop unusable = new FallbackHop("unusable", 4, 1, 4096.0, Double.NaN);
				FallbackCertificate nullType = fallbackCertificate(unusable, null);
				Assert.assertEquals("null upload type must select vector ROW before the second projection",
					FType.ROW, nullType.fallbackLogicalType());
				Assert.assertEquals(Double.doubleToRawLongBits(Double.NaN), nullType.primaryCostBits());
				Assert.assertEquals(nullType.expectedFinalCostBits(), nullType.finalCostBits());
				Assert.assertSame(FallbackDisposition.FALLBACK_RECOVERED, nullType.disposition());

				FallbackHop nonNull = new FallbackHop("nonNull", 1, 4, 4096.0, Double.NaN);
				FallbackCertificate explicitType = fallbackCertificate(nonNull, FType.COL);
				Assert.assertEquals(FType.COL, explicitType.primaryProjectedType());
				Assert.assertEquals("fallback must preserve the typed consumer-safe upload selection",
					FType.COL, explicitType.fallbackProjectedType());
				Assert.assertEquals(Double.doubleToRawLongBits(Double.NaN), explicitType.primaryCostBits());
				Assert.assertEquals(explicitType.expectedFinalCostBits(), explicitType.finalCostBits());
				Assert.assertSame(FallbackDisposition.FALLBACK_RECOVERED, explicitType.disposition());

				FallbackHop positive = new FallbackHop("positive", 4, 2, 4096.0, 8192.0);
				FallbackCertificate ordinary = fallbackCertificate(positive, FType.ROW);
				Assert.assertSame(FallbackDisposition.PRIMARY_ACCEPTED, ordinary.disposition());
				Assert.assertTrue(Double.longBitsToDouble(ordinary.finalCostBits()) > 0.0);
				Assert.assertEquals(ordinary.expectedFinalCostBits(), ordinary.finalCostBits());

				FallbackHop infinity = new FallbackHop("infinity", 4, 2, 4096.0, Double.POSITIVE_INFINITY);
				FallbackCertificate sentinel = fallbackCertificate(infinity, FType.ROW);
				Assert.assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
					sentinel.primaryCostBits());
				Assert.assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY),
					sentinel.finalCostBits());
				Assert.assertSame(FallbackDisposition.PRIMARY_ACCEPTED, sentinel.disposition());
			}
			finally {
				clearSelectorState();
				assertMutableStateSame(outer, snapshotMutableState());
			}
		}
	}

	private static void assertProjectionCase(FType anchorType, long anchorRows, long anchorCols,
		long targetRows, long targetCols, FType logicalType, FType expected, String label) throws Exception {
		clearSelectorState();
		Fixture fixture = fixture(federated("X", anchorType, anchorRows, anchorCols)
			+ "S=matrix(1," + targetRows + ',' + targetCols + "); print(sum(X)+sum(S));\n",
			targetRows, targetCols);
		DurableAnchorKey anchor = anchor(fixture.analysis(), anchorType);
		FederatedPlannerUtils.registerFedInitVar("G011_PROJECTION", anchorType, signature(anchor));
		ProjectionCertificate immutable = immutableProjection(fixture.analysis(), fixture.target(), logicalType);
		FType productionType = immutable.primaryProjectedType();
		double productionCost = FederatedPlannerDpCostEstimator.computeUploadCostWithFallback(
			fixture.target().hop(), null, productionType, WORKERS);
		Assert.assertSame(label, expected, productionType);
		Assert.assertSame(label, productionType, immutable.primaryProjectedType());
		Assert.assertEquals(label, immutable.memoryEstimateBits(),
			Double.doubleToRawLongBits(FederatedCostModel.getEffectiveUploadMemEstimate(fixture.target().hop())));
		Assert.assertEquals(label, immutable.finalCostBits(), Double.doubleToRawLongBits(productionCost));
		Assert.assertEquals(label, vectorAxis(fixture.target().hop()), immutable.vectorAxis());
		clearSelectorState();
	}

	private static void assertAggBinarySharedDimensionCases() {
		assertAggCase(FType.ROW, FType.COL, true, FType.ROW, 4, 4, FType.COL, "COL/ROW left exception");
		assertAggCase(FType.COL, FType.ROW, false, FType.COL, 4, 4, FType.ROW, "ROW/COL right exception");
		assertAggCase(FType.ROW, FType.COL, true, FType.COL, 4, 4, FType.BROADCAST, "wrong other type");
		assertAggCase(FType.ROW, FType.COL, true, FType.ROW, 4, 3, FType.BROADCAST, "wrong shared axis");

		clearSelectorState();
		FederatedPlannerUtils.registerFedInitVar("G011_NO_MM_ANCHOR", FType.ROW, "no-mm|0,4;");
		FallbackHop target = new FallbackHop("targetNoMm", 2, 4, 1024, 1024);
		Assert.assertSame("no matrix-multiply consumer", FType.BROADCAST,
			FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(target, FType.COL));
	}

	private static void assertAggCase(FType anchorType, FType requestedType, boolean targetLeft,
		FType otherType, long anchorAxis, long sharedAxis, FType expected, String label) {
		clearSelectorState();
		FederatedPlannerUtils.registerFedInitVar("G011_AGG_ANCHOR", anchorType,
			"agg-anchor|0," + anchorAxis + ';');
		FallbackHop target = targetLeft
			? new FallbackHop("target", 2, sharedAxis, 1024, 1024)
			: new FallbackHop("target", sharedAxis, 3, 1024, 1024);
		DataOp other = new DataOp("G011_OTHER", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "G011_OTHER", targetLeft ? sharedAxis : 2,
			targetLeft ? 3 : sharedAxis, -1, 1024);
		FederatedPlannerUtils.registerFedInitVar("G011_OTHER", otherType);
		if(targetLeft)
			new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64, OpOp2.MULT, AggOp.SUM, target, other);
		else
			new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64, OpOp2.MULT, AggOp.SUM, other, target);
		Assert.assertSame(label, expected,
			FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(target, requestedType));
	}

	private static FallbackCertificate fallbackCertificate(FallbackHop hop, FType logicalType) {
		FType primary = logicalType;
		double mem = FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		double primaryCost = FederatedCostModel.computeUploadNetworkCost(mem, primary, WORKERS);
		FType fallbackLogical = logicalType == null ? vectorAxis(hop) : primary;
		FType fallbackProjected = fallbackLogical;
		double fallbackMem = mem;
		if(Double.isNaN(fallbackMem) || fallbackMem <= 0.0)
			fallbackMem = FederatedCostModel.getEffectiveInputMemEstimate(hop);
		double fallbackCost = FederatedCostModel.computeUploadNetworkCost(fallbackMem, fallbackProjected, WORKERS);
		double expectedFinal = (!(Double.isNaN(primaryCost) || primaryCost <= 0.0))
			? primaryCost + FederatedCostModel.computeLocalToFedForwardingPenalty(primary, WORKERS)
			: (!(Double.isNaN(fallbackCost) || fallbackCost <= 0.0))
				? fallbackCost + FederatedCostModel.computeLocalToFedForwardingPenalty(fallbackProjected, WORKERS)
				: fallbackCost;
		double actual = FederatedPlannerDpCostEstimator.computeUploadCostWithFallback(hop, null, logicalType, WORKERS);
		FallbackDisposition disposition = !(Double.isNaN(primaryCost) || primaryCost <= 0.0)
			? FallbackDisposition.PRIMARY_ACCEPTED
			: !(Double.isNaN(actual) || actual <= 0.0)
				? FallbackDisposition.FALLBACK_RECOVERED : FallbackDisposition.UNUSABLE_PRIMARY_NOT_RECOVERED;
		return new FallbackCertificate(primary, fallbackLogical, fallbackProjected,
			Double.doubleToRawLongBits(mem), Double.doubleToRawLongBits(primaryCost),
			Double.doubleToRawLongBits(fallbackCost), Double.doubleToRawLongBits(actual),
			Double.doubleToRawLongBits(expectedFinal), disposition);
	}

	private static ProjectionCertificate immutableProjection(PlacementAnalysis analysis,
		HopOccurrenceProjection occurrence, FType logicalType) {
		NodeShapeFact shape = analysis.shapeFact(occurrence.key()).orElseThrow();
		FType vectorAxis = vectorAxis(occurrence.hop());
		if(shape.dataType() != DataType.MATRIX)
			return new ProjectionCertificate(null, null, -1, vectorAxis, logicalType, logicalType, logicalType,
				0L, 0L, 0L, ProjectionDisposition.ZERO_TRANSFER, "non-matrix boundary");
		Set<DurableAnchorKey> anchors = distinctAnchors(analysis);
		if(anchors.isEmpty())
			return new ProjectionCertificate(null, null, -1, vectorAxis, logicalType, null, null,
				0L, 0L, 0L, ProjectionDisposition.MISSING_ANCHOR, "no placement anchor");
		if(anchors.size() != 1)
			return new ProjectionCertificate(null, null, -1, vectorAxis, logicalType, null, null,
				0L, 0L, 0L, ProjectionDisposition.AMBIGUOUS_ANCHOR, "multiple placement anchors");
		DurableAnchorKey anchor = anchors.iterator().next();
		if(!shape.knownPositiveMatrix())
			return new ProjectionCertificate(anchor.normalizedSignature(), anchor.fType(), anchorAxis(anchor),
				vectorAxis, logicalType, logicalType, logicalType, 0L, 0L, 0L,
				ProjectionDisposition.UNKNOWN_SHAPE, "unknown matrix shape");
		FType projected = immutableProjectType(occurrence.hop(), logicalType, anchor);
		double mem = OptimizerUtils.estimateSize(shape.rows(), shape.cols());
		double raw = FederatedCostModel.computeUploadNetworkCost(mem, projected, WORKERS);
		double cost = raw + FederatedCostModel.computeLocalToFedForwardingPenalty(projected, WORKERS);
		return new ProjectionCertificate(anchor.normalizedSignature(), anchor.fType(), anchorAxis(anchor),
			vectorAxis, logicalType, projected, projected, Double.doubleToRawLongBits(mem),
			Double.doubleToRawLongBits(raw), Double.doubleToRawLongBits(cost),
			ProjectionDisposition.ACCEPTED, "unique placement anchor");
	}

	private static FType immutableProjectType(Hop hop, FType logicalType, DurableAnchorKey anchor) {
		if(logicalType == null || logicalType == FType.BROADCAST)
			return logicalType;
		FType vectorAxis = vectorAxis(hop);
		if(vectorAxis != null && vectorAxis != anchor.fType())
			return FType.BROADCAST;
		long hopAxis = anchor.fType() == FType.ROW ? hop.getDim1() : hop.getDim2();
		return hopAxis > 0 && hopAxis != anchorAxis(anchor) ? FType.BROADCAST : logicalType;
	}

	private static void assertIndependentDisposition(PlacementAnalysis analysis,
		HopOccurrenceProjection occurrence, FType logicalType, ProjectionDisposition expected, String label) {
		ProjectionCertificate certificate = immutableProjection(analysis, occurrence, logicalType);
		Assert.assertSame(label, expected, certificate.disposition());
		Assert.assertNotNull(label, certificate.reason());
	}

	private static void assertSelector(String label, PlacementAnalysis analysis,
		SelectorDisposition expectedProduction, Parity expectedParity, ThrowingRunnable setup) throws Exception {
		clearSelectorState();
		AnalysisSnapshot before = snapshotAnalysis(analysis);
		setup.run();
		SelectorCertificate production = productionSelectorCertificate();
		SelectorDisposition immutable = immutableSelector(analysis);
		Assert.assertSame(label, expectedProduction, production.disposition());
		Assert.assertSame(label, expectedParity,
			production.disposition() == immutable ? Parity.EQUAL : Parity.DIFFERS);
		assertAnalysisSame(before, snapshotAnalysis(analysis));
		clearSelectorState();
		Assert.assertEquals(label, SelectorDisposition.NONE, productionSelectorCertificate().disposition());
	}

	private static SelectorCertificate productionSelectorCertificate() {
		FallbackHop rowProbe = new FallbackHop("rowProbe", 4, 7, 1024, 1024);
		FallbackHop colProbe = new FallbackHop("colProbe", 7, 4, 1024, 1024);
		FType row = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(rowProbe, FType.ROW);
		FType col = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(colProbe, FType.COL);
		SelectorDisposition disposition = row == FType.ROW && col == FType.BROADCAST
			? SelectorDisposition.ROW : row == FType.BROADCAST && col == FType.COL
				? SelectorDisposition.COL : SelectorDisposition.NONE;
		return new SelectorCertificate(disposition, row, col,
			FederatedPlannerUtils.snapshotFedState(), FederatedRefedPolicy.snapshotCpfoutAnchorCache());
	}

	private static SelectorDisposition immutableSelector(PlacementAnalysis analysis) {
		Set<DurableAnchorKey> anchors = distinctAnchors(analysis);
		if(anchors.size() != 1)
			return SelectorDisposition.NONE;
		FType type = anchors.iterator().next().fType();
		return type == FType.ROW ? SelectorDisposition.ROW
			: type == FType.COL ? SelectorDisposition.COL : SelectorDisposition.NONE;
	}

	private static void cacheFromProgramOnly(DMLProgram program) {
		PlacementAnalysis analysis = program.requirePlacementAnalysisAuthority();
		ExactPlacementRegistration.Receipt receipt = ExactPlacementRegistration.registerProgram(program, Map.of(), analysis);
		Assert.assertSame("typed registration must retain canonical PlacementAnalysis authority",
			analysis, receipt.analysis());
		FederatedRefedPolicy.registerFromProgram(program);
		removeAllFedState();
	}

	private static void cacheFromFunctionOnly(FunctionStatementBlock function) {
		FederatedRefedPolicy.registerFromFunction(function);
		removeAllFedState();
	}

	private static void registerDivergent(String rowSignature, String colSignature) {
		FederatedPlannerUtils.registerFedInitVar("G011_DIVERGENT_ROW", FType.ROW, rowSignature);
		FederatedPlannerUtils.registerFedInitVar("G011_DIVERGENT_COL", FType.COL, colSignature);
	}

	private static void removeAllFedState() {
		for(String name : List.copyOf(FederatedPlannerUtils.snapshotFedState().keySet())) {
			FederatedPlannerUtils.removeFedAnchorKey(name);
			FederatedPlannerUtils.removeFedInitVar(name);
		}
	}

	private static void clearSelectorState() {
		removeAllFedState();
		FederatedRefedPolicy.registerFromProgram((DMLProgram) null);
	}

	private static void assertNoOpaqueCacheAtEntry() {
		SelectorCertificate before = productionSelectorCertificate();
		Assert.assertEquals("test requires no inherited thread-local signature selector",
			SelectorDisposition.NONE, before.disposition());
	}

	private static MutableStateSnapshot snapshotMutableState() {
		return new MutableStateSnapshot(FederatedPlannerUtils.snapshotFedState(),
			FederatedPlannerUtils.snapshotPlannerRecompileStates(),
			FederatedPlannerUtils.snapshotAmbiguousPlannerRecompileSignatures(),
			FederatedRefedPolicy.snapshotCpfoutAnchorCache());
	}

	private static void assertMutableStateSame(MutableStateSnapshot expected, MutableStateSnapshot actual) {
		Assert.assertEquals(expected.fedState(), actual.fedState());
		Assert.assertEquals(expected.recompileState(), actual.recompileState());
		Assert.assertEquals(expected.ambiguousRecompileState(), actual.ambiguousRecompileState());
		Assert.assertEquals(expected.cpfoutCache(), actual.cpfoutCache());
	}

	private static Fixture fixture(String script, long rows, long cols) throws Exception {
		DMLProgram program = compile(script);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		HopOccurrenceProjection target = analysis.occurrences().stream()
			.filter(o -> o.hop().getDataType() == DataType.MATRIX && !o.hop().isFederatedDataOp()
				&& o.hop().getDim1() == rows && o.hop().getDim2() == cols)
			.findFirst().orElseThrow();
		return new Fixture(program, analysis, target);
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

	private static String federated(String variable, FType type, long rows, long cols) {
		if(type == FType.ROW)
			return variable + "=federated(addresses=list(\"localhost:1234/A\",\"localhost:1235/B\"),"
				+ "ranges=list(list(0,0),list(" + rows / 2 + ',' + cols + "),list(" + rows / 2
				+ ",0),list(" + rows + ',' + cols + ")));\n";
		return variable + "=federated(addresses=list(\"localhost:1234/A\",\"localhost:1235/B\"),"
			+ "ranges=list(list(0,0),list(" + rows + ',' + cols / 2 + "),list(0," + cols / 2
			+ "),list(" + rows + ',' + cols + ")));\n";
	}

	private static DurableAnchorKey anchor(PlacementAnalysis analysis, FType type) {
		return distinctAnchors(analysis).stream().filter(a -> a.fType() == type).findFirst().orElseThrow();
	}

	private static Set<DurableAnchorKey> distinctAnchors(PlacementAnalysis analysis) {
		Set<DurableAnchorKey> anchors = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
			anchors.addAll(node.anchors());
		return Set.copyOf(anchors);
	}

	private static String signature(DurableAnchorKey anchor) {
		StringBuilder value = new StringBuilder(anchor.placementId()).append('|');
		int axis = anchor.fType() == FType.ROW ? 0 : 1;
		for(AnchorPartition partition : anchor.partitions())
			value.append(partition.begin().get(axis)).append(',')
				.append(partition.end().get(axis)).append(';');
		return value.toString();
	}

	private static long anchorAxis(DurableAnchorKey anchor) {
		int axis = anchor.fType() == FType.ROW ? 0 : 1;
		return anchor.partitions().stream().mapToLong(p -> p.end().get(axis)).max().orElse(-1);
	}

	private static FType vectorAxis(Hop hop) {
		return hop != null && hop.getDim1() == 1 && hop.getDim2() > 1 ? FType.COL
			: hop != null && hop.getDim2() == 1 && hop.getDim1() > 1 ? FType.ROW : null;
	}

	private static AnalysisSnapshot snapshotAnalysis(PlacementAnalysis analysis) {
		List<HopState> hops = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			hops.add(new HopState(occurrence, hop, hop.getDim1(), hop.getDim2(), hop.getDataType(),
				inputIdentities(hop), parentIdentities(hop), Double.doubleToRawLongBits(hop.getInputMemEstimate()),
				Double.doubleToRawLongBits(hop.getOutputMemEstimate())));
		}
		return new AnalysisSnapshot(analysis, analysis.graph(), analysis.analysisFingerprint(),
			List.copyOf(analysis.occurrences()), analysis.graph().normalizedSignature(), List.copyOf(hops));
	}

	private static List<Integer> inputIdentities(Hop hop) {
		return hop.getInput().stream().map(System::identityHashCode).toList();
	}

	private static List<Integer> parentIdentities(Hop hop) {
		return hop.getParent().stream().map(System::identityHashCode).toList();
	}

	private static void assertAnalysisSame(AnalysisSnapshot expected, AnalysisSnapshot actual) {
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertSame(expected.graph(), actual.graph());
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		Assert.assertEquals(expected.graphSignature(), actual.graphSignature());
		Assert.assertEquals(expected.occurrences().size(), actual.occurrences().size());
		for(int i = 0; i < expected.occurrences().size(); i++) {
			Assert.assertSame(expected.occurrences().get(i), actual.occurrences().get(i));
			Assert.assertEquals(expected.hops().get(i), actual.hops().get(i));
		}
	}

	private static List<Hop> collect(Hop root) {
		List<Hop> result = new ArrayList<>();
		Set<Hop> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Hop> pending = new ArrayDeque<>();
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

	private enum SelectorDisposition { ROW, COL, NONE }
	private enum ProjectionDisposition { ACCEPTED, MISSING_ANCHOR, AMBIGUOUS_ANCHOR, UNKNOWN_SHAPE, ZERO_TRANSFER }
	private enum FallbackDisposition { PRIMARY_ACCEPTED, FALLBACK_RECOVERED, UNUSABLE_PRIMARY_NOT_RECOVERED }
	private enum Parity { EQUAL, DIFFERS }

	@FunctionalInterface
	private interface ThrowingRunnable { void run() throws Exception; }

	private record Fixture(DMLProgram program, PlacementAnalysis analysis, HopOccurrenceProjection target) { }
	private record SelectorCertificate(SelectorDisposition disposition, FType rowProbe, FType colProbe,
		Map<String, FedVarSnapshot> registry, Map<Long, FederatedRefedPolicy.CpfoutAnchorSnapshot> cpfout) { }
	private record ProjectionCertificate(String immutableAnchorIdentity, FType immutableAnchorType,
		long anchorAxisLength, FType vectorAxis, FType logicalType, FType primaryProjectedType,
		FType fallbackProjectedType, long memoryEstimateBits, long primaryCostBits, long finalCostBits,
		ProjectionDisposition disposition, String reason) { }
	private record FallbackCertificate(FType primaryProjectedType, FType fallbackLogicalType,
		FType fallbackProjectedType, long memoryEstimateBits, long primaryCostBits, long fallbackCostBits,
		long finalCostBits, long expectedFinalCostBits, FallbackDisposition disposition) { }
	private record MutableStateSnapshot(Map<String, FedVarSnapshot> fedState, Map<String, ?> recompileState,
		Set<String> ambiguousRecompileState, Map<Long, FederatedRefedPolicy.CpfoutAnchorSnapshot> cpfoutCache) { }
	private record HopState(HopOccurrenceProjection occurrence, Hop hop, long rows, long cols, DataType dataType,
		List<Integer> inputs, List<Integer> parents, long inputMemBits, long outputMemBits) { }
	private record AnalysisSnapshot(PlacementAnalysis analysis, NeutralPlacementGraph graph, String fingerprint,
		List<HopOccurrenceProjection> occurrences, String graphSignature, List<HopState> hops) { }

	private static final class FallbackHop extends DataOp {
		private final double inputMem;
		private final double outputMem;

		private FallbackHop(String name, long rows, long cols, double inputMem, double outputMem) {
			super(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, rows, cols, -1, 1024);
			this.inputMem = inputMem;
			this.outputMem = outputMem;
			setDim1(rows);
			setDim2(cols);
			setNnz(-1);
		}

		@Override
		public double getInputMemEstimate() { return inputMem; }

		@Override
		public double getOutputMemEstimate() { return outputMem; }
	}
}
