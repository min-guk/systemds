/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.ipa.IPAPassRewriteFederatedPlan;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Independent RED contracts for aggregate DP selection and per-invocation live observability. */
public class CampaignBDpAggregateProducerContractTest {
	private static final String ADAPTER="org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter";
	private static final long ABSENT_ARM_COST_BITS=Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);

	@Test public void adapterAggregateSignatureIsExplicit() throws Exception {
		requireExactMethod("CAMPAIGN_B_DP_AGGREGATE_EXACT_API_MISSING");
	}

	@Test public void abstractPlannerSuppliedAnalysisReceiptSignatureIsExplicit() throws Exception {
		Class<?> receipt=requireClass("org.apache.sysds.hops.fedplanner.AFederatedPlanner$PlannerInvocationReceipt",
			"CAMPAIGN_B_DP_PLANNER_RECEIPT_TYPE_MISSING");
		Method method=requireMethod(AFederatedPlanner.class,"rewriteProgram","CAMPAIGN_B_DP_SUPPLIED_ANALYSIS_API_MISSING",
			DMLProgram.class,FunctionCallGraph.class,FunctionCallSizeInfo.class,PlacementAnalysis.class);
		Assert.assertTrue("CAMPAIGN_B_DP_SUPPLIED_ANALYSIS_RETURN_TYPE",receipt.isAssignableFrom(method.getReturnType()));
	}

	@Test public void ipaObservableConsumerOverloadIsExplicit() {
		requireMethod(IPAPassRewriteFederatedPlan.class,"rewriteProgram","CAMPAIGN_B_DP_IPA_OBSERVABLE_OVERLOAD_MISSING",
			DMLProgram.class,FunctionCallGraph.class,FunctionCallSizeInfo.class,Consumer.class);
	}

	@Test public void translatorObservableConsumerOverloadIsExplicit() {
		assertMethodAbsent(DMLTranslator.class,"rewriteHopsDAG",
			"CAMPAIGN_B_DP_EARLY_REWRITE_RECEIPT_OVERLOAD_REMAINS",DMLProgram.class,Consumer.class);
		Method finalBoundary=requireMethod(DMLTranslator.class,"constructLops",
			"CAMPAIGN_B_DP_FINAL_BOUNDARY_RECEIPT_OVERLOAD_MISSING",DMLProgram.class,Consumer.class);
		Assert.assertSame("CAMPAIGN_B_DP_FINAL_BOUNDARY_RECEIPT_RETURN_TYPE",void.class,
			finalBoundary.getReturnType());
	}

	@Test public void realIpaEntryPublishesOneCompleteDpReceipt() throws Exception {
		Method finalBoundary=requireMethod(DMLTranslator.class,"constructLops",
			"CAMPAIGN_B_DP_FINAL_BOUNDARY_RECEIPT_OVERLOAD_MISSING",DMLProgram.class,Consumer.class);
		Method entry=requireMethod(IPAPassRewriteFederatedPlan.class,"rewriteProgram",
			"CAMPAIGN_B_DP_IPA_EXECUTABLE_RECEIPT_MISSING",DMLProgram.class,FunctionCallGraph.class,
			FunctionCallSizeInfo.class,Consumer.class);
		DMLProgram boundaryProgram=ProductionShadowFixtureFactory.compile("B-05");
		ProgramSnapshot boundaryBefore=snapshotProgram(boundaryProgram);
		AtomicReference<Object> boundaryReceipt=new AtomicReference<>(); AtomicInteger boundaryDeliveries=new AtomicInteger();
		withDpPlanner(() -> invoke(finalBoundary,new DMLTranslator(boundaryProgram),boundaryProgram,
			(Consumer<Object>)value->{Assert.assertTrue(boundaryReceipt.compareAndSet(null,value));boundaryDeliveries.incrementAndGet();}));
		Assert.assertEquals("final-boundary receipt delivery",1,boundaryDeliveries.get());
		validateInvocation(boundaryProgram,boundaryBefore,boundaryReceipt.get());
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-05");
		PlacementAnalysis authority=CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ProgramSnapshot before=snapshotProgram(program);
		AtomicReference<Object> receipt=new AtomicReference<>(); AtomicInteger deliveries=new AtomicInteger();
		withDpPlanner(() -> invoke(entry,new IPAPassRewriteFederatedPlan(),program,new FunctionCallGraph(program),null,
			(Consumer<Object>)value->{Assert.assertTrue(receipt.compareAndSet(null,value));deliveries.incrementAndGet();}));
		Assert.assertEquals("IPA receipt delivery",1,deliveries.get());
		Assert.assertSame("CAMPAIGN_B_DP_IPA_REPLACED_FINAL_BOUNDARY_OWNER",authority,call(receipt.get(),"analysis"));
		validateInvocation(program,before,receipt.get());
	}

	@Test public void realTranslatorEntryPublishesOneCompleteDpReceipt() throws Exception {
		Method entry=requireMethod(DMLTranslator.class,"constructLops",
			"CAMPAIGN_B_DP_FINAL_BOUNDARY_RECEIPT_OVERLOAD_MISSING",DMLProgram.class,Consumer.class);
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-05"); ProgramSnapshot before=snapshotProgram(program);
		AtomicReference<Object> receipt=new AtomicReference<>(); AtomicInteger deliveries=new AtomicInteger();
		withDpPlanner(() -> invoke(entry,new DMLTranslator(program),program,
			(Consumer<Object>)value->{Assert.assertTrue(receipt.compareAndSet(null,value));deliveries.incrementAndGet();}));
		Assert.assertEquals("CAMPAIGN_B_DP_FINAL_BOUNDARY_RECEIPT_COUNT",1,deliveries.get());
		validateInvocation(program,before,receipt.get());
	}

	@Test public void equalCostProducerReceiptRetainsLoutIdentityAndRawBits() throws Exception {
		ProducerCase producer=producerCase(0x1.0p3,0x1.0p3);
		Object exact=invokeExactUnchanged(newExactHandle("CAMPAIGN_B_DP_EQUAL_TIE_API_MISSING"),producer);
		validateExact(exact,producer,"LOUT_EQUAL");
		ProducerCase loutOnly=producerCase(0x1.0p3,null);Object oneArm=invokeExactUnchanged(newExactHandle("CAMPAIGN_B_DP_EQUAL_TIE_API_MISSING"),loutOnly);validateExact(oneArm,loutOnly,"LOUT_ONLY");
	}

	@Test public void oneUlpProducerReceiptRetainsFoutIdentityAndRawBits() throws Exception {
		double fout=0x1.0p3, lout=Double.longBitsToDouble(Double.doubleToRawLongBits(fout)+1);
		ProducerCase producer=producerCase(lout,fout);
		Object exact=invokeExactUnchanged(newExactHandle("CAMPAIGN_B_DP_ONE_ULP_API_MISSING"),producer);
		validateExact(exact,producer,"FOUT_LESS");
		ProducerCase foutOnly=producerCase(null,0x1.0p3);Object oneArm=invokeExactUnchanged(newExactHandle("CAMPAIGN_B_DP_ONE_ULP_API_MISSING"),foutOnly);validateExact(oneArm,foutOnly,"FOUT_ONLY");
	}

	@Test public void foreignAndCopiedProducerIdentitiesRejectBeforeApplication() throws Exception {
		ProducerCase producer=producerCase(0x1.0p3,0x1.0p3); ExactHandle handle=newExactHandle("CAMPAIGN_B_DP_NEGATIVE_API_MISSING");
		MemoSnapshot before=snapshot(producer);
		PlacementAnalysis twin=new NeutralPlacementGraphBuilder().buildAnalysis(producer.program());
		Object twinSelection=invoke(handle.method(),handle.adapter(),twin,producer.memo(),producer.aggregate());
		Assert.assertSame("same-program twin analysis receipt",twin,call(twinSelection,"analysis"));
		Assert.assertSame(producer.memo(),call(twinSelection,"memo"));
		Assert.assertSame(producer.aggregate(),call(twinSelection,"legacyOptimalPlan"));
		assertSnapshotSame(before,snapshot(producer));
		DMLProgram directForeignProgram=ProductionShadowFixtureFactory.compile("B-02");
		PlacementAnalysis directForeign=new NeutralPlacementGraphBuilder().buildAnalysis(directForeignProgram);
		ProgramSnapshot directForeignProgramBefore=snapshotProgram(directForeignProgram); AnalysisSnapshot directForeignBefore=snapshotAnalysis(directForeign);
		expectReject(handle,directForeign,producer.memo(),producer.aggregate(),"foreign-program analysis");
		assertProgramSnapshotSame(directForeignProgramBefore,snapshotProgram(directForeignProgram)); assertAnalysisSnapshotSame(directForeignBefore,snapshotAnalysis(directForeign)); assertSnapshotSame(before,snapshot(producer));
		FederatedPlannerDpMemoTable foreignMemo=new FederatedPlannerDpMemoTable(); MemoSnapshot foreignMemoBefore=snapshot(foreignMemo,producer.aggregate(),producer.analysis());
		expectReject(handle,producer.analysis(),foreignMemo,producer.aggregate(),"foreign memo"); assertSnapshotSame(foreignMemoBefore,snapshot(foreignMemo,producer.aggregate(),producer.analysis())); assertSnapshotSame(before,snapshot(producer));
		FedPlan copy=new FedPlan(producer.aggregate().getCumulativeCost(),null,List.copyOf(producer.aggregate().getChildFedPlans()));
		AggregateSnapshot copyBefore=snapshotAggregate(copy); expectReject(handle,producer.analysis(),producer.memo(),copy,"copied aggregate"); assertAggregateSnapshotSame(copyBefore,snapshotAggregate(copy)); assertSnapshotSame(before,snapshot(producer));
		DMLProgram foreignProgram=ProductionShadowFixtureFactory.compile("B-02"); PlacementAnalysis foreignProgramAnalysis=new NeutralPlacementGraphBuilder().buildAnalysis(foreignProgram);
		ProgramSnapshot programBefore=snapshotProgram(producer.program()); Object planner=new FederatedPlannerDpFedCostBased();
		ProgramSnapshot foreignProgramBefore=snapshotProgram(foreignProgram); AnalysisSnapshot foreignAnalysisBefore=snapshotAnalysis(foreignProgramAnalysis);
		Method supplied=requireMethod(planner.getClass(),"rewriteProgram","CAMPAIGN_B_DP_NEGATIVE_SUPPLIED_ANALYSIS_API_MISSING",DMLProgram.class,FunctionCallGraph.class,FunctionCallSizeInfo.class,PlacementAnalysis.class);
		try{invoke(supplied,planner,producer.program(),new FunctionCallGraph(producer.program()),null,foreignProgramAnalysis);Assert.fail("accepted foreign program analysis");}
		catch(IllegalArgumentException expected){} assertProgramSnapshotSame(programBefore,snapshotProgram(producer.program()));
		assertProgramSnapshotSame(foreignProgramBefore,snapshotProgram(foreignProgram)); assertAnalysisSnapshotSame(foreignAnalysisBefore,snapshotAnalysis(foreignProgramAnalysis));
	}

	@Test public void sameAdapterSequentialAndBarrierConcurrentCallsAreIdentityStable() throws Exception {
		ProducerCase producer=producerCase(0x1.0p3,0x1.0p3); ExactHandle handle=newExactHandle("CAMPAIGN_B_DP_CONCURRENCY_API_MISSING");
		MemoSnapshot before=snapshot(producer); Object baseline=invokeExact(handle,producer); Object repeated=invokeExact(handle,producer);
		assertSameExact(baseline,repeated); assertSnapshotSame(before,snapshot(producer));
		CountDownLatch ready=new CountDownLatch(6),start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);
		List<Future<Object>> futures=new ArrayList<>();
		for(int i=0;i<6;i++) futures.add(pool.submit(()->{ready.countDown();start.await();return invokeExact(handle,producer);}));
		ready.await();start.countDown();for(Future<Object> future:futures)assertSameExact(baseline,future.get());pool.shutdownNow();
		assertSnapshotSame(before,snapshot(producer));
	}

	@Test public void missingExecutableAssociationRejectsBeforeApplication() throws Exception {
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(program);Hop root=analysis.occurrences().get(0).hop();
		FederatedPlannerDpMemoTable memo=new FederatedPlannerDpMemoTable();
		add(memo,root,FederatedOutput.LOUT,ExecType.CP,0x1.0p3,false);
		Method owner=FederatedPlannerDpCostEnumerator.class.getDeclaredMethod("getMinCostRootFedPlan",Set.class,FederatedPlannerDpMemoTable.class);
		owner.setAccessible(true);LinkedHashSet<Hop> roots=new LinkedHashSet<>();roots.add(root);
		FedPlan aggregate=(FedPlan)owner.invoke(null,roots,memo);FedPlan selected=memo.getFedPlanAfterPrune(aggregate.getChildFedPlans().get(0));
		Assert.assertNotNull("adversary selected plan",selected);Assert.assertNull("adversary unexpectedly registered executable Hop",memo.resolveOriginalHop(root.getHopID()));
		MemoSnapshot memoBefore=snapshot(memo,aggregate,analysis);ProgramSnapshot programBefore=snapshotProgram(program);
		AnalysisSnapshot analysisBefore=snapshotAnalysis(analysis);AggregateSnapshot aggregateBefore=snapshotAggregate(aggregate);PlanSnapshot planBefore=snapshotPlan(selected);
		ExactHandle handle=newExactHandle("CAMPAIGN_B_DP_MISSING_EXECUTABLE_API_MISSING");
		expectReject(handle,analysis,memo,aggregate,"missing executable association");
		assertSnapshotSame(memoBefore,snapshot(memo,aggregate,analysis));assertProgramSnapshotSame(programBefore,snapshotProgram(program));
		assertAnalysisSnapshotSame(analysisBefore,snapshotAnalysis(analysis));assertAggregateSnapshotSame(aggregateBefore,snapshotAggregate(aggregate));assertPlanSnapshotSame(planBefore,snapshotPlan(selected));
	}

	private record ExactHandle(Object adapter,Method method) { }
	private record ProducerCase(DMLProgram program,PlacementAnalysis analysis,FederatedPlannerDpMemoTable memo,
		FedPlan aggregate,FedPlan lout,FedPlan fout,FedPlan selected,Hop root) { }
	private record HopState(Hop hop,long hopId,ExecType exec,FederatedOutput output,List<Long> inputIds) { }
	private record ProgramSnapshot(List<HopState> states) { }
	private record AnalysisSnapshot(PlacementAnalysis analysis,String fingerprint,
		List<PlacementAnalysis.HopOccurrenceProjection> occurrences,List<HopState> hopStates) { }
	private record AggregateSnapshot(FedPlan aggregate,long costBits,List<Pair<Long,FederatedOutput>> edges) { }
	private record PlanSnapshot(FedPlan plan,Hop planningHop,long planningHopId,FederatedOutput output,
		ExecType exec,FType fType,long costBits,List<Pair<Long,FederatedOutput>> edges) { }
	private record PlanningIdentity(FedPlan plan,Hop planningHop,long planningHopId) {
		private boolean matches(FedPlan candidatePlan,Hop candidatePlanningHop,long candidatePlanningHopId) {
			return plan==candidatePlan&&planningHop==candidatePlanningHop&&planningHopId==candidatePlanningHopId;
		}
	}
	private record ApplicationIdentity(FedPlan plan,Hop planningHop,long planningHopId,
		Hop executableHop,long executableHopId) { }
	private record ComponentExpectation(int ordinal,List<CompiledHopKey> members,CompiledHopKey sinkRoot,
		PlacementAnalysis.HopOccurrenceProjection sinkRootOccurrence) { }
	private record MemoSnapshot(List<Pair<Long,FederatedOutput>> memoKeys,List<FedPlanVariants> variants,
		List<List<FedPlan>> variantPlans,List<List<Pair<Long,FederatedOutput>>> planEdges,List<Long> costBits,List<Pair<Long,FederatedOutput>> edges,
		List<Long> additionalRoots,String fingerprint,List<PlacementAnalysis.HopOccurrenceProjection> occurrences,
		List<HopState> hopStates) { }

	private static ExactHandle newExactHandle(String code) throws Exception {
		Class<?> type=requireClass(ADAPTER,code); return new ExactHandle(type.getConstructor().newInstance(),
			requireMethod(type,"selectExact",code,PlacementAnalysis.class,FederatedPlannerDpMemoTable.class,FedPlan.class));
	}
	private static Method requireExactMethod(String code) throws Exception {
		return requireMethod(requireClass(ADAPTER,code),"selectExact",code,PlacementAnalysis.class,
			FederatedPlannerDpMemoTable.class,FedPlan.class);
	}
	private static Class<?> requireClass(String name,String code) {
		try{return Class.forName(name);}catch(ClassNotFoundException e){throw new AssertionError(code+"|class="+name);}
	}
	private static Method requireMethod(Class<?> owner,String name,String code,Class<?>...parameters) {
		try{return owner.getMethod(name,parameters);}catch(NoSuchMethodException e){throw new AssertionError(code+"|member="+owner.getName()+'.'+name);}
	}
	private static void assertMethodAbsent(Class<?> owner,String name,String code,Class<?>...parameters) {
		try{owner.getMethod(name,parameters);throw new AssertionError(code+"|member="+owner.getName()+'.'+name);}
		catch(NoSuchMethodException expected){}
	}

	private static ProducerCase producerCase(Double loutCost,Double foutCost) throws Exception {
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(program);Hop root=analysis.occurrences().get(0).hop();
		FederatedPlannerDpMemoTable memo=new FederatedPlannerDpMemoTable();
		FedPlan lout=loutCost==null?null:add(memo,root,FederatedOutput.LOUT,ExecType.CP,loutCost);
		FedPlan fout=foutCost==null?null:add(memo,root,FederatedOutput.FOUT,ExecType.FED,foutCost);
		Method owner=FederatedPlannerDpCostEnumerator.class.getDeclaredMethod("getMinCostRootFedPlan",Set.class,FederatedPlannerDpMemoTable.class);
		owner.setAccessible(true);LinkedHashSet<Hop> roots=new LinkedHashSet<>();roots.add(root);
		FedPlan aggregate=(FedPlan)owner.invoke(null,roots,memo);FedPlan selected=memo.getFedPlanAfterPrune(aggregate.getChildFedPlans().get(0));
		return new ProducerCase(program,analysis,memo,aggregate,lout,fout,selected,root);
	}
	private static FedPlan add(FederatedPlannerDpMemoTable memo,Hop root,FederatedOutput output,ExecType exec,double cost) {
		return add(memo,root,output,exec,cost,true);
	}
	private static FedPlan add(FederatedPlannerDpMemoTable memo,Hop root,FederatedOutput output,ExecType exec,double cost,boolean registerExecutable) {
		HopCommon common=new HopCommon(root,1,1,1,1,List.of());common.setSelfCost(0x1.0p-4);common.setForwardingCost(0x1.0p-3);
		FedPlanVariants variants=new FedPlanVariants(common,output);FedPlan plan=new FedPlan(cost,variants,List.of());
		plan.setExecType(exec);plan.setFType(FType.ROW);variants.addFedPlan(plan);variants.pruneFedPlans();
		if(registerExecutable)memo.registerHopRefs(Map.of(root.getHopID(),common));
		memo.addFedPlanVariants(root.getHopID(),output,variants);return memo.getFedPlanAfterPrune(root.getHopID(),output);
	}
	private static Object invokeExact(ExactHandle handle,ProducerCase producer) throws Exception {
		return invoke(handle.method(),handle.adapter(),producer.analysis(),producer.memo(),producer.aggregate());
	}
	private static Object invokeExactUnchanged(ExactHandle handle,ProducerCase producer) throws Exception {
		MemoSnapshot before=snapshot(producer); Object exact=invokeExact(handle,producer); assertSnapshotSame(before,snapshot(producer)); return exact;
	}
	private static Object invoke(Method method,Object target,Object...args) throws Exception {
		try{return method.invoke(target,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();
			if(cause instanceof Exception x)throw x;if(cause instanceof Error x)throw x;throw new AssertionError(cause);}
	}
	private static Object call(Object value,String name) throws Exception {return value.getClass().getMethod(name).invoke(value);}
	private static void requireTypedReceiptAuthority(Object receipt) {
		Class<?> owner=receipt.getClass(); List<String> missing=new ArrayList<>();
		requireTypedReceiptAuthority(owner,"deferredOutputDecisionReceipts","DeferredOutputDecisionReceipt",missing);
		requireTypedReceiptAuthority(owner,"disconnectedCompletionReceipts","DisconnectedCompletionReceipt",missing);
		Assert.assertTrue("CAMPAIGN_B_DP_TYPED_RECEIPT_AUTHORITY_MISSING|"+String.join(",",missing),missing.isEmpty());
	}
	private static void requireTypedReceiptAuthority(Class<?> owner,String accessor,String elementName,List<String> missing) {
		Class<?> element;
		try{element=Class.forName(owner.getEnclosingClass().getName()+'$'+elementName);}
		catch(ClassNotFoundException e){missing.add("type="+elementName);return;}
		Method method;
		try{method=owner.getMethod(accessor);}
		catch(NoSuchMethodException e){missing.add("member="+owner.getName()+'.'+accessor);return;}
		Assert.assertSame("CAMPAIGN_B_DP_TYPED_RECEIPT_AUTHORITY_RETURN_TYPE|member="+accessor,List.class,method.getReturnType());
		Type generic=method.getGenericReturnType();
		Assert.assertTrue("CAMPAIGN_B_DP_TYPED_RECEIPT_AUTHORITY_GENERIC|member="+accessor,
			generic instanceof ParameterizedType);
		Assert.assertSame("CAMPAIGN_B_DP_TYPED_RECEIPT_AUTHORITY_ELEMENT|member="+accessor,element,
			((ParameterizedType)generic).getActualTypeArguments()[0]);
	}

	@SuppressWarnings("unchecked") private static void validateExact(Object exact,ProducerCase producer,String decision) throws Exception {
		Assert.assertSame(producer.analysis(),call(exact,"analysis"));Assert.assertSame(producer.memo(),call(exact,"memo"));
		Assert.assertSame(producer.aggregate(),call(exact,"legacyOptimalPlan"));Assert.assertEquals(producer.analysis().analysisFingerprint(),call(exact,"analysisFingerprint"));
		List<?> edges=(List<?>)call(exact,"aggregateChildEdges");List<?> plans=(List<?>)call(exact,"selectedRootPlans");
		List<?> hops=(List<?>)call(exact,"selectedRootHops");List<?> ties=(List<?>)call(exact,"tieReceipts");List<?> exclusions=(List<?>)call(exact,"graphExclusionReceipts");
		assertImmutable(edges,"aggregateChildEdges");assertImmutable(plans,"selectedRootPlans");assertImmutable(hops,"selectedRootHops");
		assertImmutable(ties,"tieReceipts");assertImmutable(exclusions,"graphExclusionReceipts");
		assertIdentityList(producer.aggregate().getChildFedPlans(),edges,"direct.aggregateChildEdges");Assert.assertSame(producer.selected(),plans.get(0));Assert.assertSame(producer.root(),hops.get(0));
		Assert.assertEquals(Double.doubleToRawLongBits(producer.aggregate().getCumulativeCost()),((Number)call(exact,"objectiveCostBits")).longValue());
		Assert.assertEquals(1,ties.size());Object tie=ties.get(0);Pair<Long,FederatedOutput> edge=producer.aggregate().getChildFedPlans().get(0);Assert.assertEquals(edge.getLeft().longValue(),((Number)call(tie,"rootHopId")).longValue());
		Assert.assertSame(producer.lout(),call(tie,"loutPlan"));Assert.assertSame(producer.fout(),call(tie,"foutPlan"));Assert.assertSame(producer.selected(),call(tie,"selectedPlan"));Assert.assertEquals(producer.selected().getFedOutType(),edge.getRight());
		Assert.assertEquals(producer.lout()==null?ABSENT_ARM_COST_BITS:Double.doubleToRawLongBits(producer.lout().getCumulativeCost()),((Number)call(tie,"loutCostBits")).longValue());
		Assert.assertEquals(producer.fout()==null?ABSENT_ARM_COST_BITS:Double.doubleToRawLongBits(producer.fout().getCumulativeCost()),((Number)call(tie,"foutCostBits")).longValue());Assert.assertEquals(decision,String.valueOf(call(tie,"decision")));
		var expected=new DpPlacementAdapter().select(producer.analysis()).certificateReceipts();Assert.assertEquals(expected.size(),exclusions.size());
		for(int i=0;i<expected.size();i++){Object actual=exclusions.get(i);Assert.assertSame(expected.get(i).occurrence(),call(actual,"occurrence"));
			Assert.assertSame(expected.get(i).node(),call(actual,"node"));Assert.assertSame(expected.get(i).exclusion(),call(actual,"exclusion"));}
	}
	@SuppressWarnings("unchecked") private static void validateInvocation(DMLProgram program,ProgramSnapshot before,Object receipt) throws Exception {
		Assert.assertNotNull(receipt); requireTypedReceiptAuthority(receipt);
		Object analysisObject=call(receipt,"analysis"),memoObject=call(receipt,"memo");
		PlacementAnalysis analysis=(PlacementAnalysis)analysisObject; FederatedPlannerDpMemoTable memo=(FederatedPlannerDpMemoTable)memoObject;
		FedPlan aggregate=(FedPlan)call(receipt,"legacyOptimalPlan"); Object exact=call(receipt,"exactSelection");
		Assert.assertSame(analysis,call(exact,"analysis")); Assert.assertSame(memo,call(exact,"memo")); Assert.assertSame(aggregate,call(exact,"legacyOptimalPlan"));
		Assert.assertEquals(analysis.analysisFingerprint(),call(exact,"analysisFingerprint"));
		Assert.assertEquals(call(receipt,"analysisFingerprintBefore"),call(receipt,"analysisFingerprintAfter"));
		Assert.assertEquals(call(receipt,"analysisFingerprintBefore"),analysis.analysisFingerprint());
		Set<Hop> reachable=Collections.newSetFromMap(new IdentityHashMap<>()); for(HopState state:before.states()) reachable.add(state.hop());
		for(var occurrence:analysis.occurrences()) Assert.assertTrue("analysis occurrence is foreign to supplied program",reachable.contains(occurrence.hop()));
		List<Pair<Long,FederatedOutput>> edges=aggregate.getChildFedPlans(); List<?> exactEdges=(List<?>)call(exact,"aggregateChildEdges"); assertImmutable(exactEdges,"live.aggregateChildEdges"); assertIdentityList(edges,exactEdges,"live.aggregateChildEdges");
		List<FedPlan> aggregatePlans=new ArrayList<>(); for(Pair<Long,FederatedOutput> edge:edges) { FedPlan plan=memo.getFedPlanAfterPrune(edge);
			Assert.assertNotNull("aggregate edge missing from returned memo",plan); aggregatePlans.add(plan); }
		List<?> exactPlans=(List<?>)call(exact,"selectedRootPlans");assertImmutable(exactPlans,"live.selectedRootPlans");assertIdentityList(aggregatePlans,exactPlans,"selectedRootPlans");
		List<Hop> aggregateHops=aggregatePlans.stream().map(FedPlan::getHopRef).toList();List<?> exactHops=(List<?>)call(exact,"selectedRootHops");assertImmutable(exactHops,"live.selectedRootHops");assertIdentityList(aggregateHops,exactHops,"selectedRootHops");
		Assert.assertEquals(Double.doubleToRawLongBits(aggregate.getCumulativeCost()),((Number)call(exact,"objectiveCostBits")).longValue());
		validateTies((List<?>)call(exact,"tieReceipts"),memo,edges); validateExclusions((List<?>)call(exact,"graphExclusionReceipts"),analysis);
		List<FedPlan> additionalPlans=new ArrayList<>(); List<Long> additionalIds=new ArrayList<>();
		for(long id:memo.getAdditionalRootHopIDs()) { if(memo.isVirtualClone(id))continue; FedPlan l=memo.getFedPlanAfterPrune(id,FederatedOutput.LOUT),f=memo.getFedPlanAfterPrune(id,FederatedOutput.FOUT);
			FedPlan seed=l==null?f:f==null?l:l.getCumulativeCost()<=f.getCumulativeCost()?l:f; Assert.assertNotNull("non-virtual additional root has no selected seed id="+id,seed);
			additionalPlans.add(seed);additionalIds.add(id); }
		Assert.assertFalse("fixture must invoke at least one non-virtual additional root",additionalPlans.isEmpty());
		List<FedPlan> expected=new ArrayList<>(aggregatePlans); List<Boolean> additional=new ArrayList<>();
		List<ApplicationIdentity> expectedApplications=new ArrayList<>();
		List<PlanningIdentity> effectiveTuples=new ArrayList<>(); Set<FedPlan> effectivePlans=Collections.newSetFromMap(new IdentityHashMap<>()); Set<Hop> effectivePlanningHops=Collections.newSetFromMap(new IdentityHashMap<>()); Set<Long> effectivePlanningIds=new java.util.HashSet<>();
		for(int i=0;i<aggregatePlans.size();i++){FedPlan plan=aggregatePlans.get(i);long planningHopId=edges.get(i).getLeft();Hop planningHop=plan.getHopRef();
			Assert.assertNotNull("aggregate planning Hop",planningHop);Assert.assertEquals("aggregate planning plan ID",planningHopId,plan.getHopID());Assert.assertEquals("aggregate planning Hop ID",planningHopId,planningHop.getHopID());
			long executableHopId=memo.resolveOriginalHopId(planningHopId);Hop executableHop=memo.resolveOriginalHop(planningHopId);Assert.assertNotNull("aggregate executable Hop resolution id="+planningHopId,executableHop);
			Assert.assertEquals("aggregate executable Hop ID",executableHopId,executableHop.getHopID());Assert.assertTrue("aggregate executable Hop is absent from supplied program",reachable.contains(executableHop));
			assertCloneIdentityRelation(memo,planningHopId,planningHop,executableHopId,executableHop,"aggregate");
			Assert.assertTrue("duplicate aggregate plan identity",effectivePlans.add(plan)); Assert.assertTrue("duplicate aggregate planning Hop identity",effectivePlanningHops.add(planningHop)); Assert.assertTrue("duplicate aggregate planning Hop ID",effectivePlanningIds.add(planningHopId));
			effectiveTuples.add(new PlanningIdentity(plan,planningHop,planningHopId));expectedApplications.add(new ApplicationIdentity(plan,planningHop,planningHopId,executableHop,executableHopId));
			additional.add(false);}
		List<String> expectedDispositions=new ArrayList<>(); List<FedPlan> explicitAppliedPlans=new ArrayList<>();
		int appliedAdditional=0,alreadyVisited=0;
		for(int i=0;i<additionalPlans.size();i++){FedPlan plan=additionalPlans.get(i);long planningHopId=additionalIds.get(i);Hop planningHop=plan.getHopRef();
			Assert.assertNotNull("additional planning Hop",planningHop);Assert.assertEquals("additional planning plan ID",planningHopId,plan.getHopID());Assert.assertEquals("additional planning Hop ID",planningHopId,planningHop.getHopID());
			long executableHopId=memo.resolveOriginalHopId(planningHopId);Hop executableHop=memo.resolveOriginalHop(planningHopId);Assert.assertNotNull("additional executable Hop resolution id="+planningHopId,executableHop);
			Assert.assertEquals("additional executable Hop ID",executableHopId,executableHop.getHopID());Assert.assertTrue("additional executable Hop is absent from supplied program",reachable.contains(executableHop));
			assertCloneIdentityRelation(memo,planningHopId,planningHop,executableHopId,executableHop,"additional");
			boolean exactTupleSeen=effectiveTuples.stream().anyMatch(tuple->tuple.matches(plan,planningHop,planningHopId));
			boolean componentCollision=effectivePlans.contains(plan)||effectivePlanningHops.contains(planningHop)||effectivePlanningIds.contains(planningHopId);
			Assert.assertFalse("recombined/partial additional-root planning identity overlap id="+planningHopId,!exactTupleSeen&&componentCollision);
			if(exactTupleSeen){expectedDispositions.add("ALREADY_VISITED");alreadyVisited++;}
			else{expectedDispositions.add("APPLIED");appliedAdditional++;Assert.assertTrue(effectivePlans.add(plan));Assert.assertTrue(effectivePlanningHops.add(planningHop));Assert.assertTrue(effectivePlanningIds.add(planningHopId));effectiveTuples.add(new PlanningIdentity(plan,planningHop,planningHopId));expected.add(plan);explicitAppliedPlans.add(plan);additional.add(true);expectedApplications.add(new ApplicationIdentity(plan,planningHop,planningHopId,executableHop,executableHopId));}}
		List<?> invocations=(List<?>)call(receipt,"additionalRootInvocations"); assertImmutable(invocations,"additionalRootInvocations");
		Assert.assertEquals("non-virtual additional invocation count",additionalPlans.size(),invocations.size());
		for(int i=0;i<invocations.size();i++) { Object invocation=invocations.get(i); FedPlan plan=additionalPlans.get(i); long id=additionalIds.get(i);
			Assert.assertEquals("AdditionalRootInvocationReceipt",invocation.getClass().getSimpleName());
			Assert.assertEquals(i,((Number)call(invocation,"ordinal")).intValue()); Assert.assertEquals(id,((Number)call(invocation,"planningHopId")).longValue());
			Assert.assertEquals(plan.getFedOutType(),call(invocation,"output")); Assert.assertSame(plan,call(invocation,"plan")); Assert.assertSame(plan.getHopRef(),call(invocation,"planningHop"));
			Assert.assertSame(memo.resolveOriginalHop(id),call(invocation,"executableHop"));Assert.assertEquals(memo.resolveOriginalHopId(id),((Number)call(invocation,"executableHopId")).longValue());
			Object dispositionValue=call(invocation,"disposition"); Assert.assertEquals("AdditionalRootDisposition",dispositionValue.getClass().getSimpleName());
			String disposition=String.valueOf(dispositionValue);
			Assert.assertEquals("independent additional-root disposition",expectedDispositions.get(i),disposition); }
		List<?> applied=(List<?>)call(receipt,"appliedPlans"); assertImmutable(applied,"appliedPlans"); Assert.assertFalse(applied.isEmpty());
		List<?> deferredReceipts=(List<?>)call(receipt,"deferredOutputDecisionReceipts"); assertImmutable(deferredReceipts,"deferredOutputDecisionReceipts");
		List<?> disconnectedReceipts=(List<?>)call(receipt,"disconnectedCompletionReceipts"); assertImmutable(disconnectedReceipts,"disconnectedCompletionReceipts");
		Assert.assertEquals("B-05 aggregate/explicit applied prefix",3,expected.size());
		Assert.assertEquals("B-05 deferred authority receipts",5,deferredReceipts.size());
		Assert.assertEquals("B-05 disconnected completion receipts",7,disconnectedReceipts.size());
		Assert.assertEquals("B-05 total applied plans",10,applied.size());
		Assert.assertEquals("B-05 disconnected applied suffix",7,applied.size()-expected.size());
		Set<FedPlan> uniquePlans=Collections.newSetFromMap(new IdentityHashMap<>());Set<Hop> uniqueHops=Collections.newSetFromMap(new IdentityHashMap<>());Set<Long> uniqueIds=new java.util.HashSet<>();int observedAdditional=0;
		for(int i=0;i<expected.size();i++) { Object item=applied.get(i); ApplicationIdentity identity=expectedApplications.get(i); FedPlan plan=identity.plan(); Hop planningHop=identity.planningHop(); long planningHopId=identity.planningHopId(); Assert.assertEquals(i,((Number)call(item,"ordinal")).intValue());
			Assert.assertEquals(additional.get(i),call(item,"additionalRoot"));if(additional.get(i))observedAdditional++;Assert.assertSame(plan,call(item,"plan"));Assert.assertSame(planningHop,call(item,"planningHop"));
			Assert.assertEquals(planningHopId,((Number)call(item,"planningHopId")).longValue());Assert.assertSame(identity.executableHop(),call(item,"executableHop"));Assert.assertEquals(identity.executableHopId(),((Number)call(item,"executableHopId")).longValue());Assert.assertEquals(plan.getFedOutType(),call(item,"output"));
			Assert.assertTrue("duplicate exact plan application",uniquePlans.add(plan));Assert.assertTrue("duplicate exact planning Hop application",uniqueHops.add(planningHop));Assert.assertTrue("duplicate planning Hop ID application",uniqueIds.add(planningHopId));}
		Assert.assertEquals("effective additional application count",appliedAdditional,observedAdditional);
		NormalizedPlannerResult normalized=(NormalizedPlannerResult)call(receipt,"normalizedResult");
		Set<CompiledHopKey> ordinaryKeys=ordinaryNormalizedKeys(analysis,normalized);
		Set<CompiledHopKey> aggregateRawClosure=planClosure(memo,aggregatePlans);
		Set<CompiledHopKey> explicitRawClosure=planClosure(memo,explicitAppliedPlans);
		assertDisjoint("aggregate vs explicit raw memo closure",aggregateRawClosure,explicitRawClosure);
		Set<CompiledHopKey> aggregateExplicitClosure=new HashSet<>(aggregateRawClosure);aggregateExplicitClosure.addAll(explicitRawClosure);
		Set<CompiledHopKey> aggregateClosure=new HashSet<>(aggregateRawClosure);aggregateClosure.retainAll(ordinaryKeys);
		Set<CompiledHopKey> explicitClosure=new HashSet<>(explicitRawClosure);explicitClosure.retainAll(ordinaryKeys);
		Set<CompiledHopKey> deferredKeys=validateDeferredReceipts(deferredReceipts,analysis,memo,normalized,aggregateExplicitClosure,applied);
		Set<CompiledHopKey> preCompletionCoverage=new HashSet<>(aggregateExplicitClosure);preCompletionCoverage.addAll(deferredKeys);
		List<ComponentExpectation> components=disconnectedComponents(analysis,normalized,preCompletionCoverage);
		expectedApplications.addAll(validateDisconnectedReceipts(disconnectedReceipts,components,applied,
			expected.size(),analysis,memo,normalized));
		Set<CompiledHopKey> disconnectedCoverage=new HashSet<>();for(ComponentExpectation component:components)disconnectedCoverage.addAll(component.members());
		assertDisjoint("aggregate vs deferred",aggregateClosure,deferredKeys);assertDisjoint("aggregate vs disconnected",aggregateClosure,disconnectedCoverage);
		assertDisjoint("explicit vs deferred",explicitClosure,deferredKeys);assertDisjoint("explicit vs disconnected",explicitClosure,disconnectedCoverage);
		assertDisjoint("deferred vs disconnected",deferredKeys,disconnectedCoverage);
		Set<CompiledHopKey> covered=new HashSet<>(aggregateClosure);covered.addAll(explicitClosure);covered.addAll(deferredKeys);covered.addAll(disconnectedCoverage);
		Assert.assertEquals("ordinary normalized coverage",ordinaryKeys,covered);
		Object counters=call(receipt,"counters"); assertCount(counters,"enumerationCount",1); assertCount(counters,"exactSelectionCount",1); assertCount(counters,"applicationPhaseCount",1);
		assertCount(counters,"appliedPlanCount",applied.size()); assertCount(counters,"additionalRootInvocationCount",invocations.size()); assertCount(counters,"additionalRootNoOpCount",alreadyVisited);
		for(String zero:List.of("internalAnalysisBuildCount","oldOverloadCount","reenumerationCount","repairCount","fallbackCount","doubleApplicationCount"))assertCount(counters,zero,0);
		ProgramSnapshot after=snapshotProgram(program); assertPlacementMutationsAccounted(before,after,expectedApplications,memo);
	}
	private static Set<CompiledHopKey> planClosure(FederatedPlannerDpMemoTable memo,List<FedPlan> roots) {
		Set<CompiledHopKey> closure=new HashSet<>();Set<FedPlan> seen=Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<FedPlan> queue=new ArrayDeque<>(roots);
		while(!queue.isEmpty()) { FedPlan plan=queue.removeFirst(); if(plan==null||!seen.add(plan))continue;
			PlacementAnalysis.HopOccurrenceProjection occurrence=memo.requirePlanCarrierOccurrence(plan.getHopRef());
			closure.add(occurrence.key());
			for(Pair<Long,FederatedOutput> edge:plan.getChildFedPlans()) { FedPlan child=memo.getFedPlanAfterPrune(edge);
				Assert.assertNotNull("aggregate/explicit closure edge is absent",child); queue.add(child); }
		}
		return closure;
	}
	@SuppressWarnings("unchecked") private static Set<CompiledHopKey> validateDeferredReceipts(List<?> receipts,PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo,NormalizedPlannerResult normalized,Set<CompiledHopKey> aggregateExplicitClosure,
		List<?> applied) throws Exception {
		Set<CompiledHopKey> deferred=new HashSet<>();Set<Object> receiptPlans=Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Object> appliedPlans=Collections.newSetFromMap(new IdentityHashMap<>());for(Object item:applied)appliedPlans.add(call(item,"plan"));
		for(int i=0;i<receipts.size();i++) { Object receipt=receipts.get(i);
			Assert.assertEquals("DeferredOutputDecisionReceipt",receipt.getClass().getSimpleName());
			Assert.assertEquals("deferred ordinal",i,((Number)call(receipt,"ordinal")).intValue());
			long decisionHopId=((Number)call(receipt,"decisionHopId")).longValue();long originalHopId=((Number)call(receipt,"originalHopId")).longValue();
			FederatedOutput desired=(FederatedOutput)call(receipt,"desiredOutput");
			PlacementAnalysis.HopOccurrenceProjection occurrence=(PlacementAnalysis.HopOccurrenceProjection)call(receipt,"occurrence");
			CompiledHopKey key=(CompiledHopKey)call(receipt,"key");FedPlan plan=(FedPlan)call(receipt,"plan");Hop planningHop=(Hop)call(receipt,"planningHop");
			PlacementState state=(PlacementState)call(receipt,"state");boolean derived=(Boolean)call(receipt,"derivedFedFout");
			Assert.assertTrue("deferred occurrence is not analysis-owned",containsOccurrenceIdentity(analysis,occurrence));
			Assert.assertEquals("deferred occurrence/key value",occurrence.key(),key);Assert.assertSame("deferred occurrence/planning Hop",occurrence.hop(),planningHop);
			Assert.assertSame("deferred memo carrier occurrence",occurrence,memo.requirePlanCarrierOccurrence(planningHop));
			Assert.assertEquals("deferred memo original decision id",originalHopId,memo.resolveOriginalHopId(decisionHopId));
			Assert.assertEquals("deferred memo original plan id",originalHopId,memo.resolveOriginalHopId(plan.getHopID()));
			Assert.assertSame("deferred plan carrier Hop",planningHop,plan.getHopRef());Assert.assertEquals("deferred plan carrier id",planningHop.getHopID(),plan.getHopID());
			Assert.assertEquals("deferred desired output",plan.getFedOutType(),desired);Assert.assertSame("deferred selected state",plan.getSelectedPlacementState(),state);
			Assert.assertEquals("deferred state exec",plan.getExecType(),state.execType());Assert.assertEquals("deferred state output",desired,state.output());
			if(state.execType()==ExecType.FED&&state.output()==FederatedOutput.FOUT)Assert.assertEquals("deferred FED/FOUT FType",plan.getFType(),state.fType());
			Assert.assertEquals("deferred derived FED/FOUT",plan.isDerivedFedFout(),derived);
			Assert.assertSame("deferred normalized state",state,normalized.selectedStates().get(key));
			Assert.assertSame("deferred normalized emission state",state,normalized.selectedEmissionStates().get(key).placementState());
			Assert.assertEquals("deferred normalized derived",derived,normalized.selectedEmissionStates().get(key).derivedFedFout());
			Assert.assertFalse("deferred overlaps aggregate/explicit closure",aggregateExplicitClosure.contains(key));
			Assert.assertFalse("deferred receipt is an applied plan",appliedPlans.contains(plan));
			Assert.assertTrue("duplicate deferred key",deferred.add(key));Assert.assertTrue("duplicate deferred plan",receiptPlans.add(plan));
		}
		return deferred;
	}
	private static List<ComponentExpectation> disconnectedComponents(PlacementAnalysis analysis,NormalizedPlannerResult normalized,
		Set<CompiledHopKey> preCompletionCoverage) {
		Set<CompiledHopKey> remaining=ordinaryNormalizedKeys(analysis,normalized);remaining.removeAll(preCompletionCoverage);
		Map<CompiledHopKey,Set<CompiledHopKey>> undirected=new LinkedHashMap<>(),directed=new LinkedHashMap<>();
		for(CompiledHopKey key:sortedKeys(remaining)){undirected.put(key,new LinkedHashSet<>());directed.put(key,new LinkedHashSet<>());}
		for(PlacementAnalysis.CompiledInputEdgeFact edge:analysis.compiledInputEdgesInCanonicalOrder())addDisconnectedEdge(edge.producer(),edge.consumer(),remaining,undirected,directed);
		for(PlacementAnalysis.LogicalTransientInputFact edge:analysis.logicalTransientInputsInCanonicalOrder())addDisconnectedEdge(edge.sourceWrite(),edge.targetRead(),remaining,undirected,directed);
		List<ComponentExpectation> components=new ArrayList<>();Set<CompiledHopKey> seen=new HashSet<>();
		List<CompiledHopKey> starts=new ArrayList<>(sortedKeys(remaining));Collections.reverse(starts);int componentOrdinal=0;
		for(CompiledHopKey start:starts)if(seen.add(start)){
			Set<CompiledHopKey> memberSet=new HashSet<>();ArrayDeque<CompiledHopKey> queue=new ArrayDeque<>();queue.add(start);memberSet.add(start);
			while(!queue.isEmpty()) { CompiledHopKey key=queue.removeFirst();for(CompiledHopKey next:sortedKeys(undirected.get(key)))if(seen.add(next)){memberSet.add(next);queue.add(next);} }
			List<CompiledHopKey> members=sortedKeys(memberSet);
			for(CompiledHopKey sink:sinkRootsByMutualReachability(members,directed))
				components.add(new ComponentExpectation(componentOrdinal,members,sink,occurrenceByKey(analysis,sink)));
			componentOrdinal++;
		}
		return components;
	}
	private static void addDisconnectedEdge(CompiledHopKey producer,CompiledHopKey consumer,Set<CompiledHopKey> remaining,
		Map<CompiledHopKey,Set<CompiledHopKey>> undirected,Map<CompiledHopKey,Set<CompiledHopKey>> directed) {
		CompiledHopKey p=findEqualKey(remaining,producer),c=findEqualKey(remaining,consumer);if(p==null||c==null)return;
		directed.get(p).add(c);undirected.get(p).add(c);undirected.get(c).add(p);
	}
	private static List<CompiledHopKey> sinkRootsByMutualReachability(List<CompiledHopKey> members,
		Map<CompiledHopKey,Set<CompiledHopKey>> directed) {
		Set<CompiledHopKey> universe=new HashSet<>(members);Map<CompiledHopKey,Set<CompiledHopKey>> reachable=new HashMap<>();
		for(CompiledHopKey start:members) {
			Set<CompiledHopKey> reached=new HashSet<>();ArrayDeque<CompiledHopKey> queue=new ArrayDeque<>();queue.add(start);
			while(!queue.isEmpty()) { CompiledHopKey key=queue.removeFirst();if(!reached.add(key))continue;
				for(CompiledHopKey next:sortedKeys(directed.getOrDefault(key,Set.of())))if(universe.contains(next))queue.add(next); }
			reachable.put(start,reached);
		}
		List<Set<CompiledHopKey>> strongComponents=new ArrayList<>();Set<CompiledHopKey> assigned=new HashSet<>();
		for(CompiledHopKey seed:members)if(assigned.add(seed)) {
			Set<CompiledHopKey> component=new HashSet<>();component.add(seed);
			for(CompiledHopKey candidate:members)
				if(!assigned.contains(candidate)&&reachable.get(seed).contains(candidate)&&reachable.get(candidate).contains(seed)) {
					assigned.add(candidate);component.add(candidate);
				}
			strongComponents.add(component);
		}
		List<CompiledHopKey> roots=new ArrayList<>();
		for(Set<CompiledHopKey> component:strongComponents) {
			boolean sink=true;
			for(CompiledHopKey member:component)
				for(CompiledHopKey next:directed.getOrDefault(member,Set.of()))
					if(universe.contains(next)&&!component.contains(next))sink=false;
			if(sink)roots.addAll(component);
		}
		Collections.sort(roots);Assert.assertFalse("disconnected component has no sink SCC root",roots.isEmpty());
		return roots;
	}
	private static List<ApplicationIdentity> validateDisconnectedReceipts(List<?> receipts,List<ComponentExpectation> components,List<?> applied,int prefix,
		PlacementAnalysis analysis,FederatedPlannerDpMemoTable memo,NormalizedPlannerResult normalized) throws Exception {
		List<ApplicationIdentity> independentlyValidated=new ArrayList<>();
		Assert.assertEquals("independent disconnected component count",components.size(),receipts.size());
		for(int i=0;i<receipts.size();i++) { Object receipt=receipts.get(i);ComponentExpectation component=components.get(i);
			Assert.assertEquals("DisconnectedCompletionReceipt",receipt.getClass().getSimpleName());Assert.assertEquals("disconnected ordinal",i,((Number)call(receipt,"ordinal")).intValue());
			Assert.assertEquals("disconnected component ordinal",component.ordinal(),((Number)call(receipt,"componentOrdinal")).intValue());Assert.assertEquals("disconnected fingerprint",analysis.analysisFingerprint(),call(receipt,"analysisFingerprint"));
			List<?> memberReceipt=(List<?>)call(receipt,"componentMembers");assertImmutable(memberReceipt,"disconnected.componentMembers");
			Assert.assertEquals("disconnected component members",component.members(),memberReceipt);Assert.assertEquals("disconnected sink root",component.sinkRoot(),call(receipt,"sinkRoot"));
			Assert.assertSame("disconnected sink occurrence",component.sinkRootOccurrence(),call(receipt,"sinkRootOccurrence"));
			Assert.assertEquals("disconnected sink occurrence key",component.sinkRoot(),component.sinkRootOccurrence().key());
			Assert.assertTrue("disconnected occurrence is not analysis-owned",containsOccurrenceIdentity(analysis,component.sinkRootOccurrence()));
			PlacementState state=normalized.selectedStates().get(component.sinkRoot());Assert.assertNotNull("disconnected normalized state",state);
			FedPlan plan=memo.getFedPlanAfterPrune(component.sinkRootOccurrence().hop().getHopID(),state.output());
			Assert.assertNotNull("disconnected memo plan",plan);
			Assert.assertSame("disconnected independent plan carrier",component.sinkRootOccurrence(),memo.requirePlanCarrierOccurrence(plan.getHopRef()));
			Assert.assertSame("disconnected independent selected state",plan.getSelectedPlacementState(),state);
			Assert.assertSame("disconnected independent emission state",state,normalized.selectedEmissionStates().get(component.sinkRoot()).placementState());
			Assert.assertEquals("disconnected independent derived",plan.isDerivedFedFout(),normalized.selectedEmissionStates().get(component.sinkRoot()).derivedFedFout());
			long planningHopId=plan.getHopID();Hop executableHop=memo.resolveOriginalHop(planningHopId);
			Assert.assertNotNull("disconnected executable Hop",executableHop);
			independentlyValidated.add(new ApplicationIdentity(plan,plan.getHopRef(),planningHopId,
				executableHop,memo.resolveOriginalHopId(planningHopId)));
		}
		Assert.assertEquals("disconnected applied suffix size",receipts.size(),applied.size()-prefix);
		for(int i=0;i<receipts.size();i++) {
			Object receipt=receipts.get(i);Object appliedReceipt=applied.get(prefix+i);
			ApplicationIdentity expected=independentlyValidated.get(i);
			Assert.assertEquals("disconnected applied ordinal",prefix+i,((Number)call(receipt,"appliedPlanOrdinal")).intValue());
			Assert.assertSame("disconnected applied receipt",appliedReceipt,call(receipt,"appliedPlan"));
			Assert.assertEquals("disconnected applied receipt ordinal",prefix+i,((Number)call(appliedReceipt,"ordinal")).intValue());
			Assert.assertEquals(Boolean.TRUE,call(appliedReceipt,"additionalRoot"));
			Assert.assertSame("disconnected applied plan",expected.plan(),call(appliedReceipt,"plan"));
			Assert.assertSame("disconnected applied planning Hop",expected.planningHop(),call(appliedReceipt,"planningHop"));
			Assert.assertEquals("disconnected applied planning ID",expected.planningHopId(),((Number)call(appliedReceipt,"planningHopId")).longValue());
			Assert.assertSame("disconnected applied executable Hop",expected.executableHop(),call(appliedReceipt,"executableHop"));
			Assert.assertEquals("disconnected applied executable ID",expected.executableHopId(),((Number)call(appliedReceipt,"executableHopId")).longValue());
			Assert.assertEquals("disconnected applied output",expected.plan().getFedOutType(),call(appliedReceipt,"output"));
		}
		return independentlyValidated;
	}
	private static Set<CompiledHopKey> ordinaryNormalizedKeys(PlacementAnalysis analysis,NormalizedPlannerResult normalized) {
		Set<CompiledHopKey> keys=new HashSet<>();
		for(var node:analysis.graph().decisionNodes())
			if(node.kind()!=org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
				&&node.kind()!=org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT) {
				Assert.assertTrue("ordinary decision lacks normalized state",normalized.selectedStates().containsKey(node.key()));
				keys.add(node.key());
			}
		return keys;
	}
	private static PlacementAnalysis.HopOccurrenceProjection occurrenceByKey(PlacementAnalysis analysis,CompiledHopKey key) { for(PlacementAnalysis.HopOccurrenceProjection occurrence:analysis.occurrences())if(occurrence.key().equals(key))return occurrence;throw new AssertionError("missing occurrence"); }
	private static boolean containsOccurrenceIdentity(PlacementAnalysis analysis,PlacementAnalysis.HopOccurrenceProjection occurrence) { return analysis.occurrences().stream().anyMatch(candidate->candidate==occurrence); }
	private static CompiledHopKey findEqualKey(Set<CompiledHopKey> keys,CompiledHopKey probe) { for(CompiledHopKey key:keys)if(key.equals(probe))return key;return null; }
	private static List<CompiledHopKey> sortedKeys(Set<CompiledHopKey> keys) { return keys.stream().sorted().toList(); }
	private static List<CompiledHopKey> sortedKeys(List<CompiledHopKey> keys) { return keys.stream().sorted().toList(); }
	private static void assertDisjoint(String label,Set<CompiledHopKey> left,Set<CompiledHopKey> right){for(CompiledHopKey key:left)Assert.assertFalse(label+" overlap",right.contains(key));}
	private static void validateTies(List<?> ties,FederatedPlannerDpMemoTable memo,List<Pair<Long,FederatedOutput>> edges)throws Exception {
		assertImmutable(ties,"tieReceipts"); Assert.assertEquals(edges.size(),ties.size());
		for(int i=0;i<edges.size();i++){Pair<Long,FederatedOutput> edge=edges.get(i);long id=edge.getLeft();FedPlan selected=memo.getFedPlanAfterPrune(edge);FedPlan l=memo.getFedPlanAfterPrune(id,FederatedOutput.LOUT),f=memo.getFedPlanAfterPrune(id,FederatedOutput.FOUT);Object tie=ties.get(i);Assert.assertNotNull("selected edge must resolve",selected);Assert.assertEquals(id,((Number)call(tie,"rootHopId")).longValue());
			Assert.assertSame(l,call(tie,"loutPlan"));Assert.assertSame(f,call(tie,"foutPlan"));Assert.assertSame(selected,call(tie,"selectedPlan"));String decision;
			if(l==null){Assert.assertNotNull("FOUT_ONLY requires FOUT arm",f);Assert.assertEquals(FederatedOutput.FOUT,edge.getRight());Assert.assertSame(f,selected);Assert.assertEquals(ABSENT_ARM_COST_BITS,((Number)call(tie,"loutCostBits")).longValue());Assert.assertEquals(Double.doubleToRawLongBits(f.getCumulativeCost()),((Number)call(tie,"foutCostBits")).longValue());decision="FOUT_ONLY";}
			else if(f==null){Assert.assertEquals(FederatedOutput.LOUT,edge.getRight());Assert.assertSame(l,selected);Assert.assertEquals(Double.doubleToRawLongBits(l.getCumulativeCost()),((Number)call(tie,"loutCostBits")).longValue());Assert.assertEquals(ABSENT_ARM_COST_BITS,((Number)call(tie,"foutCostBits")).longValue());decision="LOUT_ONLY";}
			else{Assert.assertEquals(Double.doubleToRawLongBits(l.getCumulativeCost()),((Number)call(tie,"loutCostBits")).longValue());Assert.assertEquals(Double.doubleToRawLongBits(f.getCumulativeCost()),((Number)call(tie,"foutCostBits")).longValue());decision=l.getCumulativeCost()==f.getCumulativeCost()?"LOUT_EQUAL":l.getCumulativeCost()<f.getCumulativeCost()?"LOUT_LESS":"FOUT_LESS";Assert.assertEquals(decision.startsWith("LOUT")?FederatedOutput.LOUT:FederatedOutput.FOUT,edge.getRight());}
			Assert.assertEquals(decision,String.valueOf(call(tie,"decision")));}}
	private static void validateExclusions(List<?> actual,PlacementAnalysis analysis)throws Exception {assertImmutable(actual,"graphExclusionReceipts");var expected=new DpPlacementAdapter().select(analysis).certificateReceipts();Assert.assertEquals(expected.size(),actual.size());
		for(int i=0;i<expected.size();i++){Assert.assertSame(expected.get(i).occurrence(),call(actual.get(i),"occurrence"));Assert.assertSame(expected.get(i).node(),call(actual.get(i),"node"));Assert.assertSame(expected.get(i).exclusion(),call(actual.get(i),"exclusion"));}}
	private static void assertCount(Object counters,String name,int expected)throws Exception{Assert.assertEquals(name,expected,((Number)call(counters,name)).intValue());}
	private static MemoSnapshot snapshot(ProducerCase p) { return snapshot(p.memo(),p.aggregate(),p.analysis()); }
	@SuppressWarnings("unchecked") private static MemoSnapshot snapshot(FederatedPlannerDpMemoTable memo,FedPlan aggregate,PlacementAnalysis analysis) {
		try {
			Field field=FederatedPlannerDpMemoTable.class.getDeclaredField("hopMemoTable"); field.setAccessible(true);
			var table=(java.util.Map<Pair<Long,FederatedOutput>,FedPlanVariants>)field.get(memo);
			List<Pair<Long,FederatedOutput>> keys=new ArrayList<>(table.keySet()); List<FedPlanVariants> variants=new ArrayList<>();
			List<List<FedPlan>> plans=new ArrayList<>(); List<List<Pair<Long,FederatedOutput>>> planEdges=new ArrayList<>(); List<Long> bits=new ArrayList<>();
			for(Pair<Long,FederatedOutput> key:keys) { FedPlanVariants value=table.get(key); variants.add(value);
				List<FedPlan> raw=value.getFedPlanVariants(); plans.add(List.copyOf(raw));
				for(FedPlan plan:raw) { bits.add(Double.doubleToRawLongBits(plan.getCumulativeCost())); planEdges.add(List.copyOf(plan.getChildFedPlans())); } }
			return new MemoSnapshot(List.copyOf(keys),List.copyOf(variants),List.copyOf(plans),List.copyOf(planEdges),List.copyOf(bits),
				List.copyOf(aggregate.getChildFedPlans()),List.copyOf(memo.getAdditionalRootHopIDs()),
				analysis.analysisFingerprint(),List.copyOf(analysis.occurrences()),snapshotHops(
					analysis.occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop).toList()).states());
		}
		catch(ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	private static void assertSnapshotSame(MemoSnapshot before,MemoSnapshot after) {
		assertIdentityList(before.memoKeys(),after.memoKeys(),"memoKeys");assertNestedPairIdentity(before.planEdges(),after.planEdges(),"variantPlanEdges");Assert.assertEquals(before.costBits(),after.costBits());
		assertIdentityList(before.edges(),after.edges(),"aggregateEdges"); Assert.assertEquals(before.additionalRoots(),after.additionalRoots());
		Assert.assertEquals(before.fingerprint(),after.fingerprint()); Assert.assertEquals(before.occurrences().size(),after.occurrences().size());
		for(int i=0;i<before.occurrences().size();i++){Assert.assertSame(before.occurrences().get(i),after.occurrences().get(i));Assert.assertSame(before.occurrences().get(i).hop(),after.occurrences().get(i).hop());}
		assertProgramSnapshotSame(new ProgramSnapshot(before.hopStates()),new ProgramSnapshot(after.hopStates()));
		Assert.assertEquals(before.variants().size(),after.variants().size());
		for(int i=0;i<before.variants().size();i++) { Assert.assertSame(before.variants().get(i),after.variants().get(i));
			Assert.assertEquals(before.variantPlans().get(i).size(),after.variantPlans().get(i).size());
			for(int j=0;j<before.variantPlans().get(i).size();j++) Assert.assertSame(before.variantPlans().get(i).get(j),after.variantPlans().get(i).get(j)); }
		}
	private static AnalysisSnapshot snapshotAnalysis(PlacementAnalysis analysis) { return new AnalysisSnapshot(analysis,
		analysis.analysisFingerprint(),List.copyOf(analysis.occurrences()),snapshotHops(
			analysis.occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop).toList()).states()); }
	private static void assertAnalysisSnapshotSame(AnalysisSnapshot before,AnalysisSnapshot after) {
		Assert.assertSame(before.analysis(),after.analysis()); Assert.assertEquals(before.fingerprint(),after.fingerprint());
		Assert.assertEquals(before.occurrences().size(),after.occurrences().size());
		for(int i=0;i<before.occurrences().size();i++){Assert.assertSame(before.occurrences().get(i),after.occurrences().get(i));Assert.assertSame(before.occurrences().get(i).hop(),after.occurrences().get(i).hop());}
		assertProgramSnapshotSame(new ProgramSnapshot(before.hopStates()),new ProgramSnapshot(after.hopStates())); }
	private static AggregateSnapshot snapshotAggregate(FedPlan aggregate) { return new AggregateSnapshot(aggregate,
		Double.doubleToRawLongBits(aggregate.getCumulativeCost()),List.copyOf(aggregate.getChildFedPlans())); }
	private static void assertAggregateSnapshotSame(AggregateSnapshot before,AggregateSnapshot after) {
		Assert.assertSame(before.aggregate(),after.aggregate()); Assert.assertEquals(before.costBits(),after.costBits()); assertIdentityList(before.edges(),after.edges(),"copiedAggregateEdges"); }
	private static PlanSnapshot snapshotPlan(FedPlan plan) { return new PlanSnapshot(plan,plan.getHopRef(),plan.getHopID(),
		plan.getFedOutType(),plan.getExecType(),plan.getFType(),Double.doubleToRawLongBits(plan.getCumulativeCost()),List.copyOf(plan.getChildFedPlans())); }
	private static void assertPlanSnapshotSame(PlanSnapshot before,PlanSnapshot after) { Assert.assertSame(before.plan(),after.plan());Assert.assertSame(before.planningHop(),after.planningHop());
		Assert.assertEquals(before.planningHopId(),after.planningHopId());Assert.assertEquals(before.output(),after.output());Assert.assertEquals(before.exec(),after.exec());Assert.assertEquals(before.fType(),after.fType());Assert.assertEquals(before.costBits(),after.costBits());assertIdentityList(before.edges(),after.edges(),"selectedPlanEdges"); }
	private static void assertSameExact(Object left,Object right)throws Exception {
		for(String field:List.of("analysis","memo","legacyOptimalPlan"))Assert.assertSame(call(left,field),call(right,field));
		Assert.assertEquals(call(left,"objectiveCostBits"),call(right,"objectiveCostBits"));Assert.assertEquals(call(left,"analysisFingerprint"),call(right,"analysisFingerprint"));
		for(String field:List.of("aggregateChildEdges","selectedRootPlans","selectedRootHops")){List<?>a=(List<?>)call(left,field),b=(List<?>)call(right,field);assertImmutable(a,field+".left");assertImmutable(b,field+".right");assertIdentityList(a,b,field);}
		List<?>at=(List<?>)call(left,"tieReceipts"),bt=(List<?>)call(right,"tieReceipts");assertImmutable(at,"ties.left");assertImmutable(bt,"ties.right");Assert.assertEquals(at.size(),bt.size());
		for(int i=0;i<at.size();i++)for(String field:List.of("loutPlan","foutPlan","selectedPlan"))Assert.assertSame(call(at.get(i),field),call(bt.get(i),field));
		for(int i=0;i<at.size();i++)for(String field:List.of("rootHopId","loutCostBits","foutCostBits","decision"))Assert.assertEquals(call(at.get(i),field),call(bt.get(i),field));
		List<?>ae=(List<?>)call(left,"graphExclusionReceipts"),be=(List<?>)call(right,"graphExclusionReceipts");assertImmutable(ae,"exclusions.left");assertImmutable(be,"exclusions.right");Assert.assertEquals(ae.size(),be.size());
		for(int i=0;i<ae.size();i++)for(String field:List.of("occurrence","node","exclusion"))Assert.assertSame(call(ae.get(i),field),call(be.get(i),field));
	}
	private static void assertIdentityList(List<?> expected,List<?> actual,String field){Assert.assertEquals(field,expected.size(),actual.size());for(int i=0;i<expected.size();i++)Assert.assertSame(field+"["+i+"]",expected.get(i),actual.get(i));}
	private static void assertNestedPairIdentity(List<? extends List<?>> expected,List<? extends List<?>> actual,String field){Assert.assertEquals(field,expected.size(),actual.size());for(int i=0;i<expected.size();i++)assertIdentityList(expected.get(i),actual.get(i),field+"["+i+"]");}
	private static void expectReject(ExactHandle h,PlacementAnalysis a,FederatedPlannerDpMemoTable m,FedPlan p,String label)throws Exception{try{invoke(h.method(),h.adapter(),a,m,p);Assert.fail("accepted "+label);}catch(IllegalArgumentException expected){}}
	@SuppressWarnings({"rawtypes","unchecked"})private static void assertImmutable(List<?> list,String field){try{((List)list).add(null);Assert.fail("mutable "+field);}catch(UnsupportedOperationException expected){}}
	private static ProgramSnapshot snapshotProgram(DMLProgram program){List<Hop> roots=new ArrayList<>();for(StatementBlock sb:program.getStatementBlocks())collectRoots(sb,roots);return snapshotHops(roots);}
	private static ProgramSnapshot snapshotHops(List<Hop> roots){List<HopState>states=new ArrayList<>();Set<Hop>seen=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<Hop>queue=new ArrayDeque<>(roots);
		while(!queue.isEmpty()){Hop hop=queue.removeFirst();if(hop==null||!seen.add(hop))continue;List<Hop>inputs=hop.getInput()==null?List.of():hop.getInput();states.add(new HopState(hop,hop.getHopID(),hop.getForcedExecType(),hop.getFederatedOutput(),inputs.stream().map(Hop::getHopID).toList()));queue.addAll(inputs);}return new ProgramSnapshot(List.copyOf(states));}
	private static void collectRoots(StatementBlock sb,List<Hop> roots){if(sb==null)return;if(sb instanceof IfStatementBlock b){IfStatement x=(IfStatement)b.getStatement(0);roots.add(b.getPredicateHops());for(StatementBlock q:x.getIfBody())collectRoots(q,roots);for(StatementBlock q:x.getElseBody())collectRoots(q,roots);}
		else if(sb instanceof ForStatementBlock b){ForStatement x=(ForStatement)b.getStatement(0);roots.add(b.getFromHops());roots.add(b.getToHops());if(b.getIncrementHops()!=null)roots.add(b.getIncrementHops());for(StatementBlock q:x.getBody())collectRoots(q,roots);}
		else if(sb instanceof WhileStatementBlock b){WhileStatement x=(WhileStatement)b.getStatement(0);roots.add(b.getPredicateHops());for(StatementBlock q:x.getBody())collectRoots(q,roots);}
		else if(sb instanceof FunctionStatementBlock b){FunctionStatement x=(FunctionStatement)b.getStatement(0);for(StatementBlock q:x.getBody())collectRoots(q,roots);}else if(sb.getHops()!=null)roots.addAll(sb.getHops());}
	private static void assertProgramSnapshotSame(ProgramSnapshot before,ProgramSnapshot after){Assert.assertEquals(before.states().size(),after.states().size());for(int i=0;i<before.states().size();i++){HopState a=before.states().get(i),b=after.states().get(i);Assert.assertSame(a.hop(),b.hop());Assert.assertEquals(a.hopId(),b.hopId());Assert.assertEquals(a.exec(),b.exec());Assert.assertEquals(a.output(),b.output());Assert.assertEquals(a.inputIds(),b.inputIds());}}
	private static void assertCloneIdentityRelation(FederatedPlannerDpMemoTable memo,long planningHopId,Hop planningHop,
		long executableHopId,Hop executableHop,String label){
		if(memo.isVirtualClone(planningHopId)){Assert.assertNotSame(label+" clone planning/executable Hop",planningHop,executableHop);Assert.assertNotEquals(label+" clone planning/executable Hop ID",planningHopId,executableHopId);}
		else{Assert.assertSame(label+" non-clone planning/executable Hop",planningHop,executableHop);Assert.assertEquals(label+" non-clone planning/executable Hop ID",planningHopId,executableHopId);}}
	private static void assertPlacementMutationsAccounted(ProgramSnapshot before,ProgramSnapshot after,List<ApplicationIdentity> applications,FederatedPlannerDpMemoTable memo){Assert.assertEquals(before.states().size(),after.states().size());Set<Hop>plannedExecutable=Collections.newSetFromMap(new IdentityHashMap<>());Set<FedPlan>seenPlans=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<FedPlan>queue=new ArrayDeque<>();for(ApplicationIdentity application:applications)queue.add(application.plan());while(!queue.isEmpty()){FedPlan plan=queue.removeFirst();if(plan==null||!seenPlans.add(plan))continue;Hop executableHop=memo.resolveOriginalHop(plan.getHopID());Assert.assertNotNull("planned executable Hop",executableHop);plannedExecutable.add(executableHop);for(Pair<Long,FederatedOutput> edge:plan.getChildFedPlans()){FedPlan child=memo.getFedPlanAfterPrune(edge);if(child!=null)queue.add(child);}}
		for(int i=0;i<before.states().size();i++){HopState a=before.states().get(i),b=after.states().get(i);Assert.assertSame(a.hop(),b.hop());Assert.assertEquals(a.inputIds(),b.inputIds());if(a.exec()!=b.exec()||a.output()!=b.output())Assert.assertTrue("unreceipted executable placement mutation hop="+a.hopId(),plannedExecutable.contains(a.hop()));}
		for(ApplicationIdentity application:applications){
			if(memo.isVirtualClone(application.planningHopId())){Assert.assertNotSame("virtual clone planning/executable Hop",application.planningHop(),application.executableHop());Assert.assertNotEquals("virtual clone planning/executable Hop ID",application.planningHopId(),application.executableHopId());Assert.assertNull("virtual clone forced exec must remain neutral",application.planningHop().getForcedExecType());Assert.assertEquals("virtual clone output must remain neutral",FederatedOutput.NONE,application.planningHop().getFederatedOutput());}
			else{Assert.assertSame("non-clone planning/executable Hop",application.planningHop(),application.executableHop());Assert.assertEquals("non-clone planning/executable Hop ID",application.planningHopId(),application.executableHopId());Assert.assertEquals(application.plan().getExecType(),application.planningHop().getForcedExecType());Assert.assertEquals(application.plan().getFedOutType(),application.planningHop().getFederatedOutput());}
			HopState state=after.states().stream().filter(x->x.hop()==application.executableHop()).findFirst().orElseThrow();Assert.assertEquals(application.executableHopId(),state.hopId());Assert.assertEquals(application.plan().getExecType(),state.exec());Assert.assertEquals(application.plan().getFedOutType(),state.output());}}
	private static void withDpPlanner(Throwing action)throws Exception{String old=ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);try{ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER,"compile_cost_based");action.run();}finally{ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER,old);}}
	@FunctionalInterface private interface Throwing{void run()throws Exception;}
}
