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
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationObserver;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NeutralEnumerationContext;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class CampaignBG011DpTransientWriteExactOwnerRedTest {
	@Test
	public void transientReadRetainsExactCfgForwardedFederatedState() throws Exception {
		DMLProgram program = CampaignBG014HermeticPlannerFixtureFactory.compile("B-21");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);

		List<Node> reads = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.TRANSIENT_READ)
			.filter(node -> reachingTransientWrites(analysis, node).stream()
				.anyMatch(CampaignBG011DpTransientWriteExactOwnerRedTest::hasRowFederatedState))
			.toList();
		Assert.assertEquals("B-21 must have one CFG-forwarded federated transient read", 1, reads.size());
		Node read = reads.get(0);
		List<Node> sources = reachingTransientWrites(analysis, read);
		Assert.assertEquals("transient read must have one exact reaching transient write", 1, sources.size());
		Node source = sources.get(0);

		DurableAnchorKey sourceAnchor = onlyRowAnchor(source, "reaching transient write");
		DurableAnchorKey readAnchor = onlyRowAnchor(read, "transient read");
		Assert.assertSame("CFG-forwarded read must retain the exact source anchor", sourceAnchor, readAnchor);
		Assert.assertTrue("reaching transient write lacks FED/FOUT/ROW",
			hasState(source, ExecType.FED, FederatedOutput.FOUT, FType.ROW));
		Assert.assertTrue("transient read must retain CP/LOUT",
			hasState(read, ExecType.CP, FederatedOutput.LOUT, null));
		Assert.assertTrue("transient read lacks CFG-forwarded FED/FOUT/ROW",
			hasState(read, ExecType.FED, FederatedOutput.FOUT, FType.ROW));
		Assert.assertFalse("transient read must not admit CP/FOUT",
			hasTuple(read, ExecType.CP, FederatedOutput.FOUT));
		Assert.assertFalse("transient read must not admit FED/LOUT",
			hasTuple(read, ExecType.FED, FederatedOutput.LOUT));

		List<CandidateRuleKey> keys = analysis.candidateRuleDomain().orderedRuleKeys().stream()
			.filter(key -> key.parentOccurrence() == read.key()).toList();
		Assert.assertEquals("transient read logical candidate domain order differs", List.of(
			List.of(CandidateInputState.absentLocal()),
			List.of(CandidateInputState.present(FType.ROW))),
			keys.stream().map(CandidateRuleKey::orderedInputs).toList());
		List<CandidateRuleFact> facts = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == read.key())
			.toList();
		Assert.assertEquals("transient read candidate fact order differs", keys,
			facts.stream().map(CandidateRuleFact::key).toList());
		Assert.assertEquals("transient read lacks exact local and present-ROW candidate facts", 2, facts.size());
		CandidateRuleFact localFact = facts.get(0);
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, localFact.status());
		Assert.assertEquals(ExecType.CP, localFact.capability().nativeExec());
		Assert.assertEquals(FederatedOutput.LOUT, localFact.capability().nativeOutput());
		Assert.assertNull(localFact.capability().nativeFoutFType());
		CandidateRuleFact fact = facts.get(1);
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, fact.status());
		Assert.assertEquals(ExecType.FED, fact.capability().nativeExec());
		Assert.assertEquals(FederatedOutput.FOUT, fact.capability().nativeOutput());
		Assert.assertEquals(FType.ROW, fact.capability().nativeFoutFType());

		List<LogicalTransientInputFact> logicalInputs = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.filter(input -> input.targetRead() == read.key()).toList();
		Assert.assertEquals("transient read must publish one analysis-owned logical input", 1,
			logicalInputs.size());
		LogicalTransientInputFact logical = logicalInputs.get(0);
		Assert.assertSame(source.key(), logical.sourceWrite());
		Assert.assertSame(read.key(), logical.targetRead());
		Assert.assertEquals(0, logical.logicalPosition());
		Assert.assertSame(source.valueVersion(), logical.sourceValueVersion());
		Assert.assertSame(read.valueVersion(), logical.readValueVersion());
		Assert.assertEquals(sourceAnchor, logical.anchor());
		Assert.assertTrue(source.legalAlternatives().stream()
			.anyMatch(state -> state == logical.localSourceState()));
		Assert.assertTrue(source.legalAlternatives().stream()
			.anyMatch(state -> state == logical.federatedSourceState()));
		Assert.assertEquals(CandidateInputState.absentLocal(), logical.localInput());
		Assert.assertEquals(CandidateInputState.present(FType.ROW), logical.federatedInput());
		Assert.assertSame(logical, analysis.requireExactLogicalTransientInput(source.key(), read.key(), 0));
		Assert.assertTrue("logical input must not fabricate a physical compiled edge",
			analysis.compiledInputEdgesInCanonicalOrder().stream().noneMatch(edge ->
				edge.producer() == source.key() && edge.consumer() == read.key()));
	}

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

	private static List<Node> reachingTransientWrites(PlacementAnalysis analysis, Node read) {
		return analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.TRANSIENT_WRITE)
			.filter(node -> read.valueVersion().predecessorVersions()
				.contains("cfg-definition:" + valueReference(node.valueVersion())))
			.toList();
	}

	private static String valueReference(ValueVersionKey value) {
		return value.lexicalVariable() + '#' + value.definitionOrdinal() + '@'
			+ value.definingControlRegion().callSitePath() + ':' + value.versionKind();
	}

	private static boolean hasRowFederatedState(Node node) {
		return node.anchors().stream().anyMatch(anchor -> anchor.fType() == FType.ROW)
			&& hasState(node, ExecType.FED, FederatedOutput.FOUT, FType.ROW);
	}

	private static DurableAnchorKey onlyRowAnchor(Node node, String owner) {
		List<DurableAnchorKey> anchors = node.anchors().stream()
			.filter(anchor -> anchor.fType() == FType.ROW).toList();
		Assert.assertEquals(owner + " must own one ROW anchor", 1, anchors.size());
		return anchors.get(0);
	}

	private static boolean hasState(Node node, ExecType execType, FederatedOutput output, FType fType) {
		return node.legalAlternatives().stream().anyMatch(state -> state.execType() == execType
			&& state.output() == output && state.fType() == fType);
	}

	private static boolean hasTuple(Node node, ExecType execType, FederatedOutput output) {
		return node.legalAlternatives().stream().anyMatch(state -> state.execType() == execType
			&& state.output() == output);
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
