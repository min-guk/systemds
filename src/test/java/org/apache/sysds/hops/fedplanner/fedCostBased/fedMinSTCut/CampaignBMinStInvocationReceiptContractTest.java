/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for an explicit, invocation-scoped MinST selection receipt. */
public class CampaignBMinStInvocationReceiptContractTest {
	@Test public void b09CloneRecompileProjectionPreservesExactNormalizedSelection() throws Exception {
		FederatedPlanMinSTCut planner = new FederatedPlanMinSTCut();
		Invocation first = invoke(planner, declaredRewrite(), "B-09");
		Invocation second = invoke(planner, declaredRewrite(), "B-09");

		SelectionSnapshot expected = snapshot(first);
		SelectionSnapshot actual = snapshot(second);
		Assert.assertEquals("MINST_B09_OBJECTIVE_BITS", expected.objectiveBits(), actual.objectiveBits());
		Assert.assertEquals("MINST_B09_SOURCE_PARTITION", expected.sourcePartition(), actual.sourcePartition());
		Assert.assertEquals("MINST_B09_SELECTED_STATES", expected.selectedStates(), actual.selectedStates());
		Assert.assertEquals("MINST_B09_SELECTED_OBLIGATIONS", expected.obligations(), actual.obligations());
	}

	@Test public void b07NamedFunctionBodyFutureContractRed() throws Exception {
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-07");
		PlacementAnalysis analysis=CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		Assert.assertTrue("B07_NAMED_FUNCTION_BODY_MISSING",analysis.occurrences().stream().anyMatch(
			o -> !"main".equals(o.key().functionNamespace()) && o.key().callSitePath().startsWith("function/")));
		MinStPlacementInput receipt=new FederatedPlanMinSTCut().rewriteProgram(program,null,null,analysis);
		Assert.assertSame("MINST_FUNCTION_BODY_ANALYSIS_IDENTITY",analysis,receipt.analysis());
		MinStPlacementAdapter.Selection selection=new MinStPlacementAdapter().select(analysis,receipt);
		Assert.assertSame("MINST_FUNCTION_BODY_SELECTION_ANALYSIS_IDENTITY",analysis,selection.analysis());
		Assert.assertEquals("MINST_FUNCTION_BODY_RECEIPT_CARDINALITY",analysis.occurrences().size(),
			selection.selectedReceipts().size());
		Set<?> expected=new HashSet<>(analysis.occurrences().stream().map(o->o.key()).toList());
		Set<?> actual=new HashSet<>(selection.selectedReceipts().stream().map(r->r.planningKey()).toList());
		Assert.assertEquals("MINST_FUNCTION_BODY_RECEIPT_KEYS",expected,actual);
		List<MinStPlacementAdapter.SelectedReceipt> named=selection.selectedReceipts().stream().filter(
			r -> !"main".equals(r.planningKey().functionNamespace())
				&& r.planningKey().callSitePath().startsWith("function/")).toList();
		Assert.assertFalse("MINST_FUNCTION_BODY_RECEIPTS_MISSING",named.isEmpty());
		for(MinStPlacementAdapter.SelectedReceipt selected:named) {
			Assert.assertSame("MINST_FUNCTION_BODY_HOP_IDENTITY",selected.planningHop(),selected.executableHop());
			Assert.assertEquals("MINST_FUNCTION_BODY_HOP_ID",selected.planningHopId(),selected.executableHopId());
			Assert.assertNull("MINST_FUNCTION_BODY_EXEC_MUST_BE_NONE",selected.execType());
			Assert.assertEquals("MINST_FUNCTION_BODY_OUTPUT_MUST_BE_NONE",FederatedOutput.NONE,selected.output());
		}
	}
	private static final Class<?>[] REWRITE_PARAMETERS={DMLProgram.class,FunctionCallGraph.class,
		FunctionCallSizeInfo.class,PlacementAnalysis.class};

	@Test public void explicitRewriteResultOwnsExactlyOneSuppliedAnalysis() throws Exception {
		Method inherited=AFederatedPlanner.class.getMethod("rewriteProgram",REWRITE_PARAMETERS);
		Assert.assertSame("MINST_EXPLICIT_RECEIPT_DEFAULT_CONTRACT_MISSING",
			AFederatedPlanner.class,inherited.getDeclaringClass());
		Assert.assertSame("MINST_EXPLICIT_RECEIPT_DEFAULT_RETURN_TYPE",
			AFederatedPlanner.PlannerInvocationReceipt.class,inherited.getReturnType());

		Method rewrite=declaredRewrite();
		Assert.assertSame("MINST_EXPLICIT_RECEIPT_RETURN_TYPE",
			MinStPlacementInput.class,rewrite.getReturnType());
		Assert.assertTrue("MINST_EXPLICIT_RECEIPT_INTERFACE_MISSING",
			AFederatedPlanner.PlannerInvocationReceipt.class.isAssignableFrom(MinStPlacementInput.class));
		Method analysis=publicAnalysis();
		Assert.assertSame("MINST_EXPLICIT_RECEIPT_ANALYSIS_RETURN_TYPE",PlacementAnalysis.class,analysis.getReturnType());
		assertNoPersistedInvocationResult();

		FederatedPlanMinSTCut planner=new FederatedPlanMinSTCut();
		Invocation first=invoke(planner,rewrite,"B-16");
		Invocation second=invoke(planner,rewrite,"B-16");
		Assert.assertNotSame("MINST_EXPLICIT_RECEIPT_SEQUENTIAL_ALIAS",first.receipt,second.receipt);
		Assert.assertNotSame("MINST_EXPLICIT_RECEIPT_SEQUENTIAL_ANALYSIS_ALIAS",first.analysis,second.analysis);
		assertComplete(first);
		assertComplete(second);
		Assert.assertSame("MINST_EXPLICIT_RECEIPT_PUBLIC_ANALYSIS_IDENTITY",first.analysis,analysis.invoke(first.receipt));
		Assert.assertSame("MINST_EXPLICIT_RECEIPT_PUBLIC_ANALYSIS_IDENTITY",second.analysis,analysis.invoke(second.receipt));

		DMLProgram foreignProgram=ProductionShadowFixtureFactory.compile("B-16");
		PlacementAnalysis foreign=CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(foreignProgram);
		try {
			new MinStPlacementAdapter().select(foreign,first.receipt);
			Assert.fail("MINST_EXPLICIT_RECEIPT_FOREIGN_ANALYSIS_ACCEPTED");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue("MINST_EXPLICIT_RECEIPT_FOREIGN_REJECTION_EMPTY",
				expected.getMessage()!=null&&!expected.getMessage().isBlank());
		}
		assertComplete(first);
	}

	private static Method declaredRewrite() {
		try {
			return FederatedPlanMinSTCut.class.getDeclaredMethod("rewriteProgram",REWRITE_PARAMETERS);
		}
		catch(NoSuchMethodException e) {
			Assert.fail("MINST_EXPLICIT_RECEIPT_OVERRIDE_MISSING");
			throw new AssertionError(e);
		}
	}

	private static Method publicAnalysis() {
		try {
			Method method=MinStPlacementInput.class.getMethod("analysis");
			Assert.assertTrue("MINST_EXPLICIT_RECEIPT_ANALYSIS_NOT_PUBLIC",Modifier.isPublic(method.getModifiers()));
			return method;
		}
		catch(NoSuchMethodException e) {
			Assert.fail("MINST_EXPLICIT_RECEIPT_ANALYSIS_MISSING");
			throw new AssertionError(e);
		}
	}

	private static Invocation invoke(FederatedPlanMinSTCut planner,Method rewrite,String fixture) throws Exception {
		DMLProgram program=ProductionShadowFixtureFactory.compile(fixture);
		PlacementAnalysis supplied=CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		String fingerprint=supplied.analysisFingerprint();
		Object result;
		try {
			result=rewrite.invoke(planner,program,null,null,supplied);
		}
		catch(InvocationTargetException e) {
			throw new AssertionError("MINST_EXPLICIT_RECEIPT_INVOCATION_FAILED",e.getCause());
		}
		Assert.assertTrue("MINST_EXPLICIT_RECEIPT_CONCRETE_TYPE",result instanceof MinStPlacementInput);
		Assert.assertEquals("MINST_EXPLICIT_RECEIPT_ANALYSIS_MUTATED",fingerprint,supplied.analysisFingerprint());
		return new Invocation(supplied,(MinStPlacementInput)result,fingerprint);
	}

	private static void assertComplete(Invocation invocation) {
		MinStPlacementAdapter.Selection selection=new MinStPlacementAdapter().select(invocation.analysis,invocation.receipt);
		Assert.assertSame("MINST_EXPLICIT_RECEIPT_SELECTION_ANALYSIS_IDENTITY",invocation.analysis,selection.analysis());
		Assert.assertEquals("MINST_EXPLICIT_RECEIPT_SELECTION_FINGERPRINT",invocation.fingerprint,
			selection.analysisFingerprint());
		Assert.assertEquals("MINST_EXPLICIT_RECEIPT_BIJECTION_CARDINALITY",invocation.analysis.occurrences().size(),
			selection.selectedReceipts().size());
		Set<?> expected=new HashSet<>(invocation.analysis.occurrences().stream().map(o->o.key()).toList());
		Set<?> actual=new HashSet<>(selection.selectedReceipts().stream().map(r->r.planningKey()).toList());
		Assert.assertEquals("MINST_EXPLICIT_RECEIPT_BIJECTION_KEYS",expected,actual);
		Assert.assertTrue("MINST_EXPLICIT_RECEIPT_INCOMPLETE_STATE",selection.selectedReceipts().stream()
			.allMatch(r->r.execType()!=null&&r.output()!=null));
	}

	private static void assertNoPersistedInvocationResult() {
		for(Field field:FederatedPlanMinSTCut.class.getDeclaredFields()) {
			Class<?> type=field.getType(); String name=field.getName().toLowerCase(Locale.ROOT);
			boolean forbidden=AFederatedPlanner.PlannerInvocationReceipt.class.isAssignableFrom(type)
				||MinStPlacementInput.class.isAssignableFrom(type)||PlacementAnalysis.class.isAssignableFrom(type)
				||MinStPlacementAdapter.Selection.class.isAssignableFrom(type)
				||Arrays.stream(new String[]{"last","result","receipt"}).anyMatch(name::contains);
			Assert.assertFalse("MINST_EXPLICIT_RECEIPT_PERSISTED_STATE|"+field.getName(),forbidden);
		}
	}

	private static SelectionSnapshot snapshot(Invocation invocation) {
		MinStPlacementAdapter.Selection selection = new MinStPlacementAdapter()
			.select(invocation.analysis, invocation.receipt);
		Map<Long,String> hopKeys = new LinkedHashMap<>();
		invocation.analysis.occurrences().forEach(occurrence ->
			hopKeys.put(occurrence.hop().getHopID(), occurrence.key().normalizedSignature()));
		List<String> partition = selection.sourcePartitionNodeIds().stream()
			.map(node -> normalizeCutNode(node, hopKeys)).toList();
		List<String> states = selection.selectedReceipts().stream().map(receipt ->
			receipt.planningKey().normalizedSignature() + '=' + receipt.execType() + '/' + receipt.output())
			.toList();
		List<String> obligations = selection.selectedObligations().stream().map(obligation -> {
			String expectedDomain = obligation.kind() + ":" + obligation.originalHopId() + ":"
				+ obligation.fType() + ":" + obligation.consumerHopIds();
			Assert.assertEquals("MINST_B09_OBLIGATION_DOMAIN", expectedDomain, obligation.domainId());
			List<String> consumers = obligation.consumerHopIds().stream()
				.map(id -> normalizeHop(id, hopKeys)).toList();
			return obligation.kind() + "|child=" + normalizeHop(obligation.childHopId(), hopKeys)
				+ "|original=" + normalizeHop(obligation.originalHopId(), hopKeys)
				+ "|domain=" + obligation.kind() + ':' + normalizeHop(obligation.originalHopId(), hopKeys)
				+ ':' + obligation.fType() + ':' + consumers + "|consumers=" + consumers
				+ "|fType=" + obligation.fType() + "|capability=" + obligation.capability()
				+ "|capabilityReason=" + obligation.capabilityReason() + "|reason=" + obligation.reason();
		}).toList();
		return new SelectionSnapshot(selection.cutObjectiveBits(), partition, states, obligations);
	}

	private static String normalizeCutNode(long node, Map<Long,String> hopKeys) {
		if(node == -1L)
			return "SOURCE";
		if(node == -2L)
			return "SINK";
		String key = hopKeys.get(node >> 2);
		if(key == null)
			throw new AssertionError("MINST_B09_FOREIGN_CUT_NODE|" + node);
		return key + switch((int)(node & 3L)) {
			case 0 -> ":COMPUTE";
			case 1 -> ":PLACEMENT";
			case 2 -> ":LOCALITY";
			default -> throw new AssertionError("MINST_B09_UNKNOWN_CUT_NODE_KIND|" + node);
		};
	}

	private static String normalizeHop(long hopId, Map<Long,String> hopKeys) {
		String key = hopKeys.get(hopId);
		if(key == null)
			throw new AssertionError("MINST_B09_FOREIGN_OBLIGATION_HOP|" + hopId);
		return key;
	}

	private record Invocation(PlacementAnalysis analysis,MinStPlacementInput receipt,String fingerprint) { }
	private record SelectionSnapshot(long objectiveBits, List<String> sourcePartition,
		List<String> selectedStates, List<String> obligations) { }
}
