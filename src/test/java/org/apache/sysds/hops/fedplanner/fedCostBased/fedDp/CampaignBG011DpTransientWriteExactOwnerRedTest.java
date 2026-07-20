/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationObserver;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NeutralEnumerationContext;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class CampaignBG011DpTransientWriteExactOwnerRedTest {
	@Test
	public void compatibleForeignWriteWithoutExactForwardEdgeIsRejectedBeforePublication() throws Exception {
		Fixture fixture = fixture("B-09");
		ForeignPair pair = foreignCompatiblePair(fixture.receipt());
		FederatedPlannerDpMemoTable memo = fixture.receipt().memo();
		Object capture = capture(fixture.receipt(), memo);
		int beforeLout = variantCount(memo, pair.read(), FederatedOutput.LOUT);
		int beforeFout = variantCount(memo, pair.read(), FederatedOutput.FOUT);

		Assert.assertFalse("foreign transient write was accepted",
			invoke(pair.read(), List.of(pair.write()), memo, common(memo, pair.read()), capture));
		Assert.assertEquals("foreign transient write published captured candidates", 0, captureCount(capture));
		Assert.assertEquals(beforeLout, variantCount(memo, pair.read(), FederatedOutput.LOUT));
		Assert.assertEquals(beforeFout, variantCount(memo, pair.read(), FederatedOutput.FOUT));
	}

	private static ForeignPair foreignCompatiblePair(DpInvocationReceipt receipt) {
		RewireOccurrenceSnapshot snapshot = receipt.semanticConsumption().rewireSnapshot();
		List<HopOccurrenceProjection> reads = receipt.analysis().occurrences().stream()
			.filter(value -> is(value, OpOpData.TRANSIENTREAD)).toList();
		List<HopOccurrenceProjection> writes = receipt.analysis().occurrences().stream()
			.filter(value -> is(value, OpOpData.TRANSIENTWRITE)).toList();
		for(HopOccurrenceProjection read : reads) {
			for(HopOccurrenceProjection write : writes) {
				if(!sameNameAndDimensions(read.hop(), write.hop()) || hasForward(snapshot, write, read))
					continue;
				Assert.assertSame(read, snapshot.projectExactCarrier(read.hop()));
				Assert.assertSame(write, snapshot.projectExactCarrier(write.hop()));
				return new ForeignPair((DataOp) read.hop(), (DataOp) write.hop());
			}
		}
		throw new AssertionError("B-09 lacks a same-name/same-dimension foreign transient write pair");
	}

	private static boolean is(HopOccurrenceProjection occurrence, OpOpData op) {
		return occurrence.hop() instanceof DataOp && ((DataOp) occurrence.hop()).getOp() == op;
	}

	private static boolean sameNameAndDimensions(Hop left, Hop right) {
		return left.getName() != null && left.getName().equals(right.getName())
			&& left.getDim1() == right.getDim1() && left.getDim2() == right.getDim2();
	}

	private static boolean hasForward(RewireOccurrenceSnapshot snapshot, HopOccurrenceProjection write,
		HopOccurrenceProjection read) {
		for(RewireTransientForwardEdge edge : snapshot.transientForwardEdges())
			if(edge.writeOccurrence() == write.key() && edge.readOccurrence() == read.key())
				return true;
		return false;
	}

	private static boolean invoke(DataOp read, List<Hop> children, FederatedPlannerDpMemoTable memo,
		HopCommon common, Object capture) throws Exception {
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod("enumerateTransientReadDataOp",
			DataOp.class, List.class, FederatedPlannerDpMemoTable.class, HopCommon.class, int.class, captureClass());
		method.setAccessible(true);
		try {
			return (boolean) method.invoke(null, read, children, memo, common, memo.getNumWorkers(), capture);
		}
		catch(InvocationTargetException ex) {
			if(ex.getCause() instanceof Exception cause)
				throw cause;
			throw ex;
		}
	}

	private static Object capture(DpInvocationReceipt receipt, FederatedPlannerDpMemoTable memo) throws Exception {
		Constructor<?> constructor = captureClass().getDeclaredConstructor(NeutralEnumerationContext.class,
			FederatedPlannerDpMemoTable.class, DpEnumerationObserver.class);
		constructor.setAccessible(true);
		return constructor.newInstance(receipt.semanticConsumption().semanticBlock().context(), memo,
			new DpEnumerationObserver() { });
	}

	private static Class<?> captureClass() {
		return java.util.Arrays.stream(FederatedPlannerDpCostEnumerator.class.getDeclaredClasses())
			.filter(value -> value.getSimpleName().equals("EnumerationCapture")).findFirst().orElseThrow();
	}

	private static int captureCount(Object capture) throws Exception {
		var field = capture.getClass().getDeclaredField("rawCandidateCount");
		field.setAccessible(true);
		return field.getInt(capture);
	}

	private static HopCommon common(FederatedPlannerDpMemoTable memo, DataOp hop) {
		for(FederatedOutput output : FederatedOutput.values()) {
			FedPlanVariants variants = memo.getFedPlanVariants(Pair.of(hop.getHopID(), output));
			if(variants != null)
				return variants.hopCommon;
		}
		throw new AssertionError("No memo common for transient read " + hop.getHopID());
	}

	private static int variantCount(FederatedPlannerDpMemoTable memo, DataOp hop, FederatedOutput output) {
		FedPlanVariants variants = memo.getFedPlanVariants(Pair.of(hop.getHopID(), output));
		return variants == null ? 0 : variants.getFedPlanVariants().size();
	}

	private static Fixture fixture(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
				new DMLTranslator(program).constructLops(program, receipt::set);
			}
			finally {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old);
			}
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return new Fixture((DpInvocationReceipt) receipt.get());
		}
		catch(Exception ex) {
			throw new AssertionError("Unable to build hostile transient-write fixture " + id, ex);
		}
	}

	private record ForeignPair(DataOp read, DataOp write) { }
	private record Fixture(DpInvocationReceipt receipt) { }
}
