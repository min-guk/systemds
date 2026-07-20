/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.FedVarSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Test-only RED owner proof for the DP estimator's NaN upload-memory fallback gap. */
public class CampaignBG011DpEstimatorNaNFallbackOwnerProofTest {
	private static final Object MUTABLE_SELECTOR_LOCK = new Object();
	private static final int WORKERS = 3;
	private static final double INPUT_MEM_BYTES = 4096.0;
	private static final long ANCHOR_AXIS = 4;
	private static final String ANCHOR_VARIABLE = "G011_NAN_FALLBACK_OWNER_PROOF";

	@Test
	public void finiteInputHasFiniteIndependentCostWhileCurrentNullAndExplicitPathsRemainNaN() {
		synchronized(MUTABLE_SELECTOR_LOCK) {
			MutableStateSnapshot outer = snapshotMutableState();
			Assert.assertTrue("owner proof requires an empty planner registry", outer.fedState().isEmpty());
			try {
				FederatedPlannerUtils.registerFedInitVar(ANCHOR_VARIABLE, FType.ROW,
					"g011-nan-fallback-owner-proof|0," + ANCHOR_AXIS + ';');

				assertNaNGap(new ControlledMemoryHop("nullType", 4, 1), null);
				assertNaNGap(new ControlledMemoryHop("explicitType", 1, 4), FType.COL);
			}
			finally {
				FederatedPlannerUtils.removeFedAnchorKey(ANCHOR_VARIABLE);
				FederatedPlannerUtils.removeFedInitVar(ANCHOR_VARIABLE);
				FederatedRefedPolicy.registerFromProgram((DMLProgram) null);
				assertMutableStateSame(outer, snapshotMutableState());
			}
		}
	}

	private static void assertNaNGap(ControlledMemoryHop hop, FType logicalType) {
		HopSnapshot before = snapshot(hop);
		Assert.assertEquals("controlled finite input estimate", bits(INPUT_MEM_BYTES),
			bits(FederatedCostModel.getEffectiveInputMemEstimate(hop)));
		Assert.assertEquals("controlled NaN upload estimate", bits(Double.NaN),
			bits(FederatedCostModel.getEffectiveUploadMemEstimate(hop)));

		FType independentlyProjected = independentFallbackProjection(hop, logicalType);
		double independentNetworkCost = FederatedCostModel.computeUploadNetworkCost(
			INPUT_MEM_BYTES, independentlyProjected, WORKERS);
		double independentForwardingPenalty = FederatedCostModel.computeLocalToFedForwardingPenalty(
			independentlyProjected, WORKERS);
		double independentExpected = independentNetworkCost + independentForwardingPenalty;

		Assert.assertTrue("independent network cost must be finite and positive",
			Double.isFinite(independentNetworkCost) && independentNetworkCost > 0.0);
		Assert.assertTrue("independent forwarding penalty must be finite and positive",
			Double.isFinite(independentForwardingPenalty) && independentForwardingPenalty > 0.0);
		Assert.assertTrue("finite input must yield a finite independent expected total",
			Double.isFinite(independentExpected) && independentExpected > 0.0);
		Assert.assertEquals("independent expected raw bits must preserve primitive composition",
			bits(independentNetworkCost + independentForwardingPenalty), bits(independentExpected));

		double actual = FederatedPlannerDpCostEstimator.computeUploadCostWithFallback(
			hop, null, logicalType, WORKERS);
		Assert.assertEquals("current DP fallback must remain the independently proven RED NaN contract",
			bits(Double.NaN), bits(actual));
		Assert.assertNotEquals("finite independent owner oracle must differ from current NaN result",
			bits(independentExpected), bits(actual));
		Assert.assertEquals("owner proof must not mutate the controlled Hop", before, snapshot(hop));
	}

	private static FType independentFallbackProjection(ControlledMemoryHop hop, FType logicalType) {
		FType fallbackLogical = logicalType == null ? independentVectorAxis(hop) : logicalType;
		FType vectorAxis = independentVectorAxis(hop);
		if(vectorAxis != null && vectorAxis != FType.ROW)
			return FType.BROADCAST;
		if(hop.getDim1() != ANCHOR_AXIS)
			return FType.BROADCAST;
		return fallbackLogical;
	}

	private static FType independentVectorAxis(Hop hop) {
		return hop.getDim1() == 1 && hop.getDim2() > 1 ? FType.COL
			: hop.getDim2() == 1 && hop.getDim1() > 1 ? FType.ROW : null;
	}

	private static MutableStateSnapshot snapshotMutableState() {
		return new MutableStateSnapshot(FederatedPlannerUtils.snapshotFedState(),
			FederatedRefedPolicy.snapshotCpfoutAnchorCache());
	}

	private static void assertMutableStateSame(MutableStateSnapshot expected, MutableStateSnapshot actual) {
		Assert.assertEquals(expected.fedState(), actual.fedState());
		Assert.assertEquals(expected.cpfoutCache(), actual.cpfoutCache());
	}

	private static HopSnapshot snapshot(ControlledMemoryHop hop) {
		return new HopSnapshot(hop, hop.getDim1(), hop.getDim2(), bits(hop.getInputMemEstimate()),
			bits(hop.getOutputMemEstimate()), List.copyOf(hop.getInput()), List.copyOf(hop.getParent()));
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private record MutableStateSnapshot(Map<String, FedVarSnapshot> fedState,
		Map<Long, FederatedRefedPolicy.CpfoutAnchorSnapshot> cpfoutCache) { }
	private record HopSnapshot(ControlledMemoryHop hop, long rows, long cols, long inputMemBits,
		long outputMemBits, List<Hop> inputs, List<Hop> parents) { }

	private static final class ControlledMemoryHop extends DataOp {
		private ControlledMemoryHop(String name, long rows, long cols) {
			super(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, rows, cols, -1, 1024);
			setDim1(rows);
			setDim2(cols);
			setNnz(-1);
		}

		@Override
		public double getInputMemEstimate() {
			return INPUT_MEM_BYTES;
		}

		@Override
		public double getOutputMemEstimate() {
			return Double.NaN;
		}
	}
}
