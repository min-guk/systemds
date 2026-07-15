/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.ipa.IPAPassRewriteFederatedPlan;
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
		requireMethod(DMLTranslator.class,"rewriteHopsDAG","CAMPAIGN_B_DP_DML_OBSERVABLE_OVERLOAD_MISSING",
			DMLProgram.class,Consumer.class);
	}

	@Test public void realIpaEntryPublishesOneCompleteDpReceipt() throws Exception {
		Method entry=requireMethod(IPAPassRewriteFederatedPlan.class,"rewriteProgram",
			"CAMPAIGN_B_DP_IPA_EXECUTABLE_RECEIPT_MISSING",DMLProgram.class,FunctionCallGraph.class,
			FunctionCallSizeInfo.class,Consumer.class);
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-05"); ProgramSnapshot before=snapshotProgram(program);
		AtomicReference<Object> receipt=new AtomicReference<>(); AtomicInteger deliveries=new AtomicInteger();
		withDpPlanner(() -> invoke(entry,new IPAPassRewriteFederatedPlan(),program,new FunctionCallGraph(program),null,
			(Consumer<Object>)value->{Assert.assertTrue(receipt.compareAndSet(null,value));deliveries.incrementAndGet();}));
		Assert.assertEquals("IPA receipt delivery",1,deliveries.get()); validateInvocation(program,before,receipt.get());
	}

	@Test public void realTranslatorEntryPublishesOneCompleteDpReceipt() throws Exception {
		Method entry=requireMethod(DMLTranslator.class,"rewriteHopsDAG",
			"CAMPAIGN_B_DP_DML_EXECUTABLE_RECEIPT_MISSING",DMLProgram.class,Consumer.class);
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-05"); ProgramSnapshot before=snapshotProgram(program);
		AtomicReference<Object> receipt=new AtomicReference<>(); AtomicInteger deliveries=new AtomicInteger();
		withDpPlanner(() -> invoke(entry,new DMLTranslator(program),program,
			(Consumer<Object>)value->{Assert.assertTrue(receipt.compareAndSet(null,value));deliveries.incrementAndGet();}));
		Assert.assertEquals("DML receipt delivery",1,deliveries.get()); validateInvocation(program,before,receipt.get());
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
		PlacementAnalysis foreign=new NeutralPlacementGraphBuilder().buildAnalysis(producer.program());
		MemoSnapshot before=snapshot(producer);
		expectReject(handle,foreign,producer.memo(),producer.aggregate(),"foreign analysis"); assertSnapshotSame(before,snapshot(producer));
		expectReject(handle,producer.analysis(),new FederatedPlannerDpMemoTable(),producer.aggregate(),"foreign memo"); assertSnapshotSame(before,snapshot(producer));
		FedPlan copy=new FedPlan(producer.aggregate().getCumulativeCost(),null,List.copyOf(producer.aggregate().getChildFedPlans()));
		expectReject(handle,producer.analysis(),producer.memo(),copy,"copied aggregate"); assertSnapshotSame(before,snapshot(producer));
		DMLProgram foreignProgram=ProductionShadowFixtureFactory.compile("B-02"); PlacementAnalysis foreignProgramAnalysis=new NeutralPlacementGraphBuilder().buildAnalysis(foreignProgram);
		ProgramSnapshot programBefore=snapshotProgram(producer.program()); Object planner=new FederatedPlannerDpFedCostBased();
		Method supplied=requireMethod(planner.getClass(),"rewriteProgram","CAMPAIGN_B_DP_NEGATIVE_SUPPLIED_ANALYSIS_API_MISSING",DMLProgram.class,FunctionCallGraph.class,FunctionCallSizeInfo.class,PlacementAnalysis.class);
		try{invoke(supplied,planner,producer.program(),new FunctionCallGraph(producer.program()),null,foreignProgramAnalysis);Assert.fail("accepted foreign program analysis");}
		catch(IllegalArgumentException expected){} assertProgramSnapshotSame(programBefore,snapshotProgram(producer.program()));
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

	private record ExactHandle(Object adapter,Method method) { }
	private record ProducerCase(DMLProgram program,PlacementAnalysis analysis,FederatedPlannerDpMemoTable memo,
		FedPlan aggregate,FedPlan lout,FedPlan fout,FedPlan selected,Hop root) { }
	private record HopState(Hop hop,long hopId,ExecType exec,FederatedOutput output,List<Long> inputIds) { }
	private record ProgramSnapshot(List<HopState> states) { }
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
		HopCommon common=new HopCommon(root,1,1,1,1,List.of());common.setSelfCost(0x1.0p-4);common.setForwardingCost(0x1.0p-3);
		FedPlanVariants variants=new FedPlanVariants(common,output);FedPlan plan=new FedPlan(cost,variants,List.of());
		plan.setExecType(exec);plan.setFType(FType.ROW);variants.addFedPlan(plan);variants.pruneFedPlans();
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
		Assert.assertNotNull(receipt); Object analysisObject=call(receipt,"analysis"),memoObject=call(receipt,"memo");
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
		List<FedPlan> expected=new ArrayList<>(aggregatePlans); List<Boolean> additional=new ArrayList<>(); List<Long> expectedIds=new ArrayList<>();
		for(int i=0;i<aggregatePlans.size();i++){additional.add(false);expectedIds.add(edges.get(i).getLeft());}
		int nonVirtualAdditional=0;
		for(long id:memo.getAdditionalRootHopIDs()) { if(memo.isVirtualClone(id))continue; FedPlan l=memo.getFedPlanAfterPrune(id,FederatedOutput.LOUT),f=memo.getFedPlanAfterPrune(id,FederatedOutput.FOUT);
			FedPlan seed=l==null?f:f==null?l:l.getCumulativeCost()<=f.getCumulativeCost()?l:f; Assert.assertNotNull("non-virtual additional root has no selected seed id="+id,seed);
			nonVirtualAdditional++;expected.add(seed);additional.add(true);expectedIds.add(id); }
		Assert.assertTrue("fixture must apply at least one non-virtual additional root",nonVirtualAdditional>0);
		List<?> applied=(List<?>)call(receipt,"appliedPlans"); assertImmutable(applied,"appliedPlans"); Assert.assertEquals(expected.size(),applied.size()); Assert.assertFalse(applied.isEmpty());
		Set<FedPlan> uniquePlans=Collections.newSetFromMap(new IdentityHashMap<>());Set<Hop> uniqueHops=Collections.newSetFromMap(new IdentityHashMap<>());Set<Long> uniqueIds=new java.util.HashSet<>();int observedAdditional=0;
		for(int i=0;i<expected.size();i++) { Object item=applied.get(i); FedPlan plan=expected.get(i); Hop hop=plan.getHopRef(); long id=expectedIds.get(i); Assert.assertEquals(i,((Number)call(item,"ordinal")).intValue());
			Assert.assertEquals(additional.get(i),call(item,"additionalRoot"));if(additional.get(i))observedAdditional++;Assert.assertSame(plan,call(item,"plan"));Assert.assertSame(hop,call(item,"hop"));
			Assert.assertEquals(id,((Number)call(item,"hopId")).longValue());Assert.assertEquals(id,hop.getHopID());Assert.assertEquals(plan.getFedOutType(),call(item,"output"));
			Assert.assertTrue("duplicate exact plan application",uniquePlans.add(plan));Assert.assertTrue("duplicate exact Hop application",uniqueHops.add(hop));Assert.assertTrue("duplicate Hop ID application",uniqueIds.add(id));}
		Assert.assertEquals("non-virtual additional receipt count",nonVirtualAdditional,observedAdditional);
		Object counters=call(receipt,"counters"); assertCount(counters,"enumerationCount",1); assertCount(counters,"exactSelectionCount",1); assertCount(counters,"applicationPhaseCount",1);
		assertCount(counters,"appliedPlanCount",applied.size()); for(String zero:List.of("internalAnalysisBuildCount","oldOverloadCount","reenumerationCount","repairCount","fallbackCount","doubleApplicationCount"))assertCount(counters,zero,0);
		ProgramSnapshot after=snapshotProgram(program); assertPlacementMutationsAccounted(before,after,expected,memo);
	}
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
	@SuppressWarnings("unchecked") private static MemoSnapshot snapshot(ProducerCase p) {
		try {
			Field field=FederatedPlannerDpMemoTable.class.getDeclaredField("hopMemoTable"); field.setAccessible(true);
			var table=(java.util.Map<Pair<Long,FederatedOutput>,FedPlanVariants>)field.get(p.memo());
			List<Pair<Long,FederatedOutput>> keys=new ArrayList<>(table.keySet()); List<FedPlanVariants> variants=new ArrayList<>();
			List<List<FedPlan>> plans=new ArrayList<>(); List<List<Pair<Long,FederatedOutput>>> planEdges=new ArrayList<>(); List<Long> bits=new ArrayList<>();
			for(Pair<Long,FederatedOutput> key:keys) { FedPlanVariants value=table.get(key); variants.add(value);
				List<FedPlan> raw=value.getFedPlanVariants(); plans.add(List.copyOf(raw));
				for(FedPlan plan:raw) { bits.add(Double.doubleToRawLongBits(plan.getCumulativeCost())); planEdges.add(List.copyOf(plan.getChildFedPlans())); } }
			return new MemoSnapshot(List.copyOf(keys),List.copyOf(variants),List.copyOf(plans),List.copyOf(planEdges),List.copyOf(bits),
				List.copyOf(p.aggregate().getChildFedPlans()),List.copyOf(p.memo().getAdditionalRootHopIDs()),
				p.analysis().analysisFingerprint(),List.copyOf(p.analysis().occurrences()),snapshotHops(
					p.analysis().occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop).toList()).states());
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
	private static void assertPlacementMutationsAccounted(ProgramSnapshot before,ProgramSnapshot after,List<FedPlan> applications,FederatedPlannerDpMemoTable memo){Assert.assertEquals(before.states().size(),after.states().size());Set<Hop>planned=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<FedPlan>queue=new ArrayDeque<>(applications);while(!queue.isEmpty()){FedPlan plan=queue.removeFirst();if(plan==null||!planned.add(plan.getHopRef()))continue;for(Pair<Long,FederatedOutput> edge:plan.getChildFedPlans()){FedPlan child=memo.getFedPlanAfterPrune(edge);if(child!=null)queue.add(child);}}
		for(int i=0;i<before.states().size();i++){HopState a=before.states().get(i),b=after.states().get(i);Assert.assertSame(a.hop(),b.hop());Assert.assertEquals(a.inputIds(),b.inputIds());if(a.exec()!=b.exec()||a.output()!=b.output())Assert.assertTrue("unreceipted placement mutation hop="+a.hopId(),planned.contains(a.hop()));}
		for(FedPlan application:applications){HopState state=after.states().stream().filter(x->x.hop()==application.getHopRef()).findFirst().orElseThrow();Assert.assertEquals(application.getExecType(),state.exec());Assert.assertEquals(application.getFedOutType(),state.output());}}
	private static void withDpPlanner(Throwing action)throws Exception{String old=ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);try{ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER,"compile_cost_based");action.run();}finally{ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER,old);}}
	@FunctionalInterface private interface Throwing{void run()throws Exception;}
}
