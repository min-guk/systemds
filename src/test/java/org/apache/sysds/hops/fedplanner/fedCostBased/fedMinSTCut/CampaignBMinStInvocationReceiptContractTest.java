/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
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
	private static final String B09_CLONE_PREDECESSOR =
		"input-0:64:6736da1dbbc6a6a05e3213f3c83cebba5d53ed0c6e6a3c7947c99d83ebe0f09f|2:M1|"
			+ "105:64:6736da1dbbc6a6a05e3213f3c83cebba5d53ed0c6e6a3c7947c99d83ebe0f09f|4:main|"
			+ "8:6:main/4|6:main/4|8:compiled|1:0|8:ORDINARY|0:";

	@Test public void b09CloneRecompileProjectionPreservesExactNormalizedSelection() throws Exception {
		FederatedPlanMinSTCut planner = new FederatedPlanMinSTCut();
		Invocation first = invoke(planner, declaredRewrite(), "B-09");
		SelectionSnapshot actual = snapshot(first);
		assertExactPhysicalSnapshot(actual);
		assertB09CloneOriginIdentity(first);

		Invocation repeated = invoke(planner, declaredRewrite(), "B-09");
		SelectionSnapshot repeatedActual = snapshot(repeated);
		assertExactPhysicalSnapshot(repeatedActual);
		assertB09CloneOriginIdentity(repeated);
		Assert.assertEquals("MINST_B09_REPEATED_SELECTION_STABILITY", actual, repeatedActual);
	}

	private static void assertExactPhysicalSnapshot(SelectionSnapshot snapshot) {
		double objective = Double.longBitsToDouble(snapshot.objectiveBits());
		Assert.assertTrue("MINST_B09_PHYSICAL_OBJECTIVE_FINITE", Double.isFinite(objective));
		Assert.assertTrue("MINST_B09_PHYSICAL_OBJECTIVE_NONNEGATIVE", objective >= 0.0);
		Assert.assertFalse("MINST_B09_EXACT_STATES_EMPTY", snapshot.selectedStates().isEmpty());
		Assert.assertFalse("MINST_B09_CANONICAL_PLAN_HASH_EMPTY", snapshot.normalizedPlanFingerprint().isBlank());
	}

	private static void assertB09CloneOriginIdentity(Invocation invocation) {
		PlacementAnalysis analysis = invocation.analysis();
		NormalizedPlannerResult normalized = invocation.receipt().normalizedResult();
		var clones = analysis.graph().nodes().stream().filter(node -> node.kind() == NodeKind.CLONE
			&& "CLONE_RECOMPILE".equals(node.valueVersion().versionKind().name())).toList();
		Assert.assertEquals("MINST_B09_CLONE_RECOMPILE_MULTIPLICITY", 1, clones.size());
		var clone = clones.get(0);
		Assert.assertEquals("MINST_B09_CLONE_RECOMPILE_CONTEXT", "recompile", clone.key().recompileContext());
		var sameOrigin = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.SAME_ORIGIN)
			.filter(constraint -> constraint.right() == clone.key()).toList();
		Assert.assertEquals("MINST_B09_SAME_ORIGIN_MULTIPLICITY", 1, sameOrigin.size());
		var origins = analysis.graph().nodes().stream().filter(node -> node.key() == sameOrigin.get(0).left()).toList();
		Assert.assertEquals("MINST_B09_ORIGIN_MULTIPLICITY", 1, origins.size());
		var origin = origins.get(0);
		Assert.assertNotEquals("MINST_B09_ORIGIN_MUST_NOT_BE_CLONE", NodeKind.CLONE, origin.kind());
		Assert.assertEquals("MINST_B09_CANONICAL_SOURCE_ORIGIN", origin.key().canonicalSourceOrigin(),
			clone.key().canonicalSourceOrigin());
		Assert.assertEquals("MINST_B09_SAME_ORIGIN_INPUT_POSITION", -1, sameOrigin.get(0).inputPosition());
		Assert.assertEquals("MINST_B09_SAME_ORIGIN_EVIDENCE", "stable-origin", sameOrigin.get(0).evidence());
		Assert.assertEquals("MINST_B09_CLONE_SELECTED_STATE_EQUALITY",
			normalized.selectedStates().get(origin.key()), normalized.selectedStates().get(clone.key()));
		var exclusions = clone.exclusions().stream()
			.filter(exclusion -> exclusion.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT).toList();
		Assert.assertEquals("MINST_B09_RECOMPILE_EXCLUSION_MULTIPLICITY", 1, exclusions.size());
		Assert.assertEquals("MINST_B09_RECOMPILE_EXCLUSION_EXEC", ExecType.CP, exclusions.get(0).state().execType());
		Assert.assertEquals("MINST_B09_RECOMPILE_EXCLUSION_OUTPUT", FederatedOutput.FOUT,
			exclusions.get(0).state().output());
		Assert.assertNull("MINST_B09_RECOMPILE_EXCLUSION_FTYPE", exclusions.get(0).state().fType());
		Assert.assertFalse("MINST_B09_RECOMPILE_EXCLUSION_SHAPE", exclusions.get(0).state().shapeDependent());
		Assert.assertEquals("MINST_B09_RECOMPILE_EXCLUSION_DETAIL", "recompile-context forbids CP/FOUT",
			exclusions.get(0).detail());
		Assert.assertEquals("MINST_B09_CLONE_PREDECESSOR_IDENTITY", List.of(B09_CLONE_PREDECESSOR),
			clone.valueVersion().predecessorVersions());
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
		assertSingleCanonicalEmission(invocation.receipt, invocation.analysis);
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

	private static void assertSingleCanonicalEmission(Object receipt, PlacementAnalysis analysis) {
		try {
			Object normalized = receipt.getClass().getMethod("normalizedResult").invoke(receipt);
			Assert.assertTrue("MINST_NORMALIZED_RESULT_TYPE", normalized instanceof NormalizedPlannerResult);
			Assert.assertSame("MINST_NORMALIZED_ANALYSIS_IDENTITY", analysis,
				((NormalizedPlannerResult) normalized).analysis());
			String canonical = PlacementEmissionTransaction.canonicalPlanHash((NormalizedPlannerResult) normalized);
			Assert.assertEquals("MINST_PUBLIC_CANONICAL_HASH_AUTHORITY", canonical,
				((NormalizedPlannerResult) normalized).normalizedPlanFingerprint());
			Object emission = receipt.getClass().getMethod("emissionReceipt").invoke(receipt);
			Assert.assertEquals("MINST_EXACTLY_ONE_EMISSION_HASH", canonical,
				emission.getClass().getMethod("planHash").invoke(emission));
			Assert.assertEquals("MINST_EMISSION_APPLIED", true,
				emission.getClass().getMethod("applied").invoke(emission));
			Assert.assertEquals("MINST_EMISSION_NOT_NOOP", false,
				emission.getClass().getMethod("noOp").invoke(emission));
		}
		catch(ReflectiveOperationException e) {
			throw new AssertionError("MINST_TRANSACTION_ENTRYPOINT_CONTRACT_MISSING", e);
		}
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
		List<String> states = selection.selectedReceipts().stream().map(receipt ->
			receipt.planningKey().normalizedSignature() + '=' + receipt.execType() + '/' + receipt.output())
			.toList();
		NormalizedPlannerResult normalized = invocation.receipt.normalizedResult();
		List<String> candidates = normalized.selectedCandidateSelections().stream()
			.map(candidate -> candidate.normalizedSignature()).toList();
		List<String> relocationChoices = normalized.selectedRelocationChoices().stream()
			.map(choice -> choice.normalizedSignature()).toList();
		List<String> relocations = normalized.selectedRelocations().stream()
			.map(action -> action.normalizedSignature()).toList();
		return new SelectionSnapshot(selection.cutObjectiveBits(), states, candidates,
			relocationChoices, relocations, normalized.normalizedPlanFingerprint());
	}

	private record Invocation(PlacementAnalysis analysis,MinStPlacementInput receipt,String fingerprint) { }
	private record SelectionSnapshot(long objectiveBits, List<String> selectedStates,
		List<String> candidateReceipts, List<String> relocationChoices,
		List<String> emittedRelocations, String normalizedPlanFingerprint) { }
}
