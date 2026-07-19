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
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.DpSemanticConstructionException;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NeutralEnumerationContext;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class CampaignBG014TransientWriteIdentityCollisionRedTest {
	@Test
	public void distinctSameIdWritesRejectCanonicallyBeforeCaptureOrMemoPublication() throws Exception {
		Fixture fixture = fixture("B-09");
		DataOp transientRead = first(fixture.receipt(), OpOpData.TRANSIENTREAD);
		DataOp transientWrite = first(fixture.receipt(), OpOpData.TRANSIENTWRITE);
		DataOp sameIdClone = sameIdClone(transientWrite);
		Assert.assertNotSame(transientWrite, sameIdClone);
		Assert.assertEquals(transientWrite.getHopID(), sameIdClone.getHopID());

		FederatedPlannerDpMemoTable memo = fixture.receipt().memo();
		HopCommon common = common(memo, transientRead);
		Object capture = capture(fixture.receipt(), memo);
		int beforeLout = variantCount(memo, transientRead, FederatedOutput.LOUT);
		int beforeFout = variantCount(memo, transientRead, FederatedOutput.FOUT);
		try {
			invoke(transientRead, List.of(transientWrite, sameIdClone), memo, common, capture);
			Assert.fail("distinct same-ID transient writes were accepted");
		}
		catch(DpSemanticConstructionException expected) {
			HopOccurrenceProjection parent = fixture.receipt().analysis().occurrences().stream()
				.filter(value -> value.hop() == transientRead).findFirst().orElseThrow();
			Assert.assertSame(ConstructionDisposition.DUPLICATE_OCCURRENCE, expected.disposition());
			Assert.assertEquals("DUPLICATE_OCCURRENCE", expected.reasonCode());
			Assert.assertEquals(fixture.receipt().analysis().analysisFingerprint(), expected.analysisFingerprint());
			Assert.assertSame(parent.key(), expected.parentOccurrence());
		}
		Assert.assertEquals(0, captureCount(capture));
		Assert.assertEquals(beforeLout, variantCount(memo, transientRead, FederatedOutput.LOUT));
		Assert.assertEquals(beforeFout, variantCount(memo, transientRead, FederatedOutput.FOUT));
	}

	@Test
	public void captureNullPreservesAmbiguityFailureAndExactRepeatedReferenceIsAllowed() throws Exception {
		Fixture ambiguous = fixture("B-09");
		DataOp read = first(ambiguous.receipt(), OpOpData.TRANSIENTREAD);
		DataOp write = first(ambiguous.receipt(), OpOpData.TRANSIENTWRITE);
		DataOp sameIdClone = sameIdClone(write);
		try {
			invoke(read, List.of(write, sameIdClone), ambiguous.receipt().memo(),
				common(ambiguous.receipt().memo(), read), null);
			Assert.fail("capture-null ambiguity was accepted");
		}
		catch(DMLRuntimeException expected) {
			Assert.assertEquals("Ambiguous transient-write child hop " + write.getHopID(), expected.getMessage());
		}

		Fixture repeated = fixture("B-09");
		DataOp repeatedRead = first(repeated.receipt(), OpOpData.TRANSIENTREAD);
		DataOp repeatedWrite = first(repeated.receipt(), OpOpData.TRANSIENTWRITE);
		Assert.assertTrue(invoke(repeatedRead, List.of(repeatedWrite, repeatedWrite), repeated.receipt().memo(),
			common(repeated.receipt().memo(), repeatedRead), null));
	}

	private static DataOp sameIdClone(DataOp original) throws CloneNotSupportedException {
		DataOp clone = Mockito.spy((DataOp) original.clone());
		Mockito.doReturn(original.getHopID()).when(clone).getHopID();
		return clone;
	}

	private static boolean invoke(DataOp read, List<Hop> children, FederatedPlannerDpMemoTable memo,
		HopCommon common, Object capture) throws Exception {
		Class<?> captureClass = captureClass();
		Method method = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod("enumerateTransientReadDataOp",
			DataOp.class, List.class, FederatedPlannerDpMemoTable.class, HopCommon.class, int.class, captureClass);
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
		Class<?> captureClass = captureClass();
		Constructor<?> constructor = captureClass.getDeclaredConstructor(NeutralEnumerationContext.class,
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

	private static DataOp first(DpInvocationReceipt receipt, OpOpData op) {
		return receipt.analysis().occurrences().stream().map(HopOccurrenceProjection::hop)
			.filter(DataOp.class::isInstance).map(DataOp.class::cast).filter(value -> value.getOp() == op)
			.findFirst().orElseThrow(() -> new AssertionError("Fixture lacks " + op));
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
			finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return new Fixture((DpInvocationReceipt) receipt.get());
		}
		catch(Exception ex) {
			throw new AssertionError("Unable to build hostile transient-write fixture " + id, ex);
		}
	}

	private record Fixture(DpInvocationReceipt receipt) { }
}
