/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;

/** Reflection-only boundary whose authority is exact producer identity, never supplied strings. */
final class R4CostAdapterBridge {
	enum Planner { DP, MIN_ST }
	enum EvidenceKind { DP_MEMO,MINST_CUT,SELECTED_PLAN,SELECTED_CERTIFICATE,REGISTRY_SNAPSHOT,PLACEMENT_ANALYSIS,GRAPH_EXCLUSION }
	enum ReceiptField { ANALYSIS,DP_MEMO,DP_ROOT,DP_SELECTED,DP_ENUMERATED,MINST_GRAPH,MINST_CUT_EDGE,
		MINST_CUT_CERTIFICATE,MINST_REPAIR,MINST_OBLIGATION,MINST_REGISTRY,FULL_CERTIFICATE,GRAPH_EXCLUSION }
	record FieldCoordinate(String rowDigest,String rowKind,String fieldName) { }
	record TypedEvidence(EvidenceKind kind,Object producer,CampaignBFrozenCostFixtureBridge.EvidenceRole role,
		FieldCoordinate coordinate,ReceiptField receiptField,Object receipt) { }
	record Selection(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,PlacementAnalysis analysis,
		Object producer,Hop root,Object selectedReceipt,List<?> orderedReceipts,List<?> obligationReceipts,
		List<?> registryReceipts,List<?> certificateReceipts,String analysisFingerprint,List<TypedEvidence> evidence) { }

	static Selection select(CampaignBFrozenCostFixtureBridge.CostSelectionInput input){
		if(input instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph)try{return normalize(input,graph.result());}
		catch(ReflectiveOperationException e){throw new AssertionError("CAMPAIGN_B_REFLECTION_CONTRACT_BROKEN|planner="+input.planner()+"|cause="+e.getClass().getName());}
		String simple=input.planner()==Planner.DP?"DpPlacementAdapter":"MinStPlacementAdapter";
		String name="org.apache.sysds.hops.fedplanner.placement.adapter."+simple;
		try{
			Class<?> c=Class.forName(name);Object adapter=c.getConstructor().newInstance();Object result;
			if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d)
				result=c.getMethod("selectExact",PlacementAnalysis.class,FederatedPlannerDpMemoTable.class,Hop.class)
					.invoke(adapter,d.analysis(),d.memo(),d.root());
			else if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m)
				result=c.getMethod("selectExact",PlacementAnalysis.class,FederatedPlanMinSTGraph.class)
					.invoke(adapter,m.analysis(),m.graph());
			else result=c.getMethod("select",PlacementAnalysis.class).invoke(adapter,input.analysis());
			return normalize(input,result);
		}
		catch(ClassNotFoundException|NoSuchMethodException e){throw new AssertionError("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING|planner="+input.planner()+"|class="+name);}
		catch(InvocationTargetException e){Throwable x=e.getCause();if(x instanceof AssertionError)throw(AssertionError)x;
			throw new AssertionError("CAMPAIGN_B_ADAPTER_INVOCATION_FAILED|planner="+input.planner()+"|cause="+x.getClass().getName());}
		catch(ReflectiveOperationException|RuntimeException e){throw new AssertionError("CAMPAIGN_B_REFLECTION_CONTRACT_BROKEN|planner="+input.planner()+"|cause="+e.getClass().getName());}
	}
	private static Selection normalize(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,Object r)throws ReflectiveOperationException{
		if(r==null)throw new AssertionError("CAMPAIGN_B_ADAPTER_RESULT_INCOMPLETE|accessor=result");
		PlacementAnalysis analysis=(PlacementAnalysis)get(r,"analysis");Object producer=get(r,"producer");
		if(analysis!=input.analysis()||producer!=input.producer())throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId());
		Hop root=null;Object selected=null;List<?> ordered=List.of(),obligations=List.of(),registries=List.of(),certificates=List.of();
		if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d){
			root=(Hop)get(r,"root");selected=get(r,"selectedPlan");ordered=objects(r,"enumeratedPlans");
			if(root!=d.root()||selected!=d.selectedPlan()||!sameObjects(ordered,d.enumeratedPlans()))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|receipt=DP");
		}
		else if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m){
			obligations=objects(r,"selectedObligations");registries=objects(r,"registryReceipts");certificates=objects(r,"certificateReceipts");
			if(!sameObjects(obligations,m.selectedObligations())||!sameObjects(registries,m.registryReceipts()))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|receipt=MINST");
		}
		else {certificates=objects(r,"certificateReceipts");
			if(input instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph&&
				(!sameObjects(certificates,graph.result().certificateReceipts())||certificates.stream().noneMatch(x->x==graph.receipt())))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|receipt=GRAPH_EXCLUSION");}
		List<TypedEvidence> evidence=new ArrayList<>();
		if(input.planner()==Planner.MIN_ST)for(Object h:(Iterable<?>)get(r,"typedEvidence")){
			EvidenceKind kind=EvidenceKind.valueOf(String.valueOf(get(h,"kind")));
			FieldCoordinate coordinate=new FieldCoordinate(str(h,"rowDigest"),str(h,"rowKind"),str(h,"fieldName"));
			ReceiptField receiptField=ReceiptField.valueOf(String.valueOf(get(h,"receiptField")));
			String literal=String.valueOf(get(h,"literalKey"));var role=input.aliases().stream().filter(a->a.literalKey().equals(literal)).findFirst()
				.orElseThrow(()->new AssertionError("R4_TYPED_ALIAS_BIJECTION|literal="+literal));
			evidence.add(new TypedEvidence(kind,get(h,"producer"),role,coordinate,receiptField,get(h,"receipt")));
		}
		return new Selection(input,analysis,producer,root,selected,List.copyOf(ordered),List.copyOf(obligations),
			List.copyOf(registries),List.copyOf(certificates),str(r,"analysisFingerprint"),List.copyOf(evidence));
	}
	private static Object get(Object o,String n)throws ReflectiveOperationException{return o.getClass().getMethod(n).invoke(o);}
	private static String str(Object o,String n)throws ReflectiveOperationException{return String.valueOf(get(o,n));}
	private static List<?> objects(Object o,String n)throws ReflectiveOperationException{List<Object>x=new ArrayList<>();for(Object v:(Iterable<?>)get(o,n))x.add(v);return List.copyOf(x);}
	private static boolean sameObjects(List<?> a,List<?> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;}
	private R4CostAdapterBridge(){}
}
