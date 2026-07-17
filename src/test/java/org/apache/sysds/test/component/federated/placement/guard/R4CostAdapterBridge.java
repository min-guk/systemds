/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.List;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;

/** Public adapter boundary whose authority is exact producer identity, never supplied strings. */
final class R4CostAdapterBridge {
	enum Planner { DP, MIN_ST }
	enum EvidenceKind { DP_MEMO,MINST_CUT,SELECTED_PLAN,SELECTED_CERTIFICATE,REGISTRY_SNAPSHOT,PLACEMENT_ANALYSIS,GRAPH_EXCLUSION }
	enum ReceiptField { ANALYSIS,DP_MEMO,DP_ROOT,DP_SELECTED,DP_ENUMERATED,MINST_GRAPH,MINST_CUT_EDGE,
		MINST_CUT_CERTIFICATE,MINST_REPAIR,MINST_OBLIGATION,MINST_REGISTRY,FULL_CERTIFICATE,GRAPH_EXCLUSION }
	record FieldCoordinate(String rowDigest,String rowKind,String fieldName) { }
	record TypedEvidence(EvidenceKind kind,Object producer,CampaignBFrozenCostFixtureBridge.EvidenceRole role,
		FieldCoordinate coordinate,ReceiptField receiptField,Object receipt) { }
	record Selection(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,PlacementAnalysis analysis,
		Object producer,Hop root,Object selectedReceipt,List<?> aggregateReceipts,List<?> orderedReceipts,
		List<?> obligationReceipts,List<?> registryReceipts,List<?> certificateReceipts,long objectiveCostBits,
		String analysisFingerprint,List<TypedEvidence> evidence) { }

	static Selection select(CampaignBFrozenCostFixtureBridge.CostSelectionInput input) {
		if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d)
			return dp(d);
		if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m)
			return minst(m,m.analysis());
		if(input instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput g)
			return graph(g);
		return full((CampaignBFrozenCostFixtureBridge.FullPathInput)input);
	}

	static Selection selectMinst(CampaignBFrozenCostFixtureBridge.MinstGraphInput input,PlacementAnalysis requested) {
		return minst(input,requested);
	}

	private static Selection dp(CampaignBFrozenCostFixtureBridge.DpMemoInput d) {
		var x=new DpPlacementAdapter().selectExact(d.analysis(),d.memo(),d.aggregatePlan());
		if(x.analysis()!=d.analysis()||x.memo()!=d.memo()||x.legacyOptimalPlan()!=d.aggregatePlan()
			||!x.analysisFingerprint().equals(d.analysis().analysisFingerprint()))
			throw identity(d.fixtureId(),"DP_OWNER");
		if(!sameObjects(x.aggregateChildEdges(),d.aggregatePlan().getChildFedPlans())
			||x.selectedRootPlans().size()!=1||x.selectedRootPlans().get(0)!=d.selectedPlan()
			||x.selectedRootHops().size()!=1||x.selectedRootHops().get(0)!=d.root()
			||x.objectiveCostBits()!=Double.doubleToRawLongBits(d.aggregatePlan().getCumulativeCost()))
			throw identity(d.fixtureId(),"DP_SELECTED");
		for(int i=0;i<x.aggregateChildEdges().size();i++) {
			var edge=x.aggregateChildEdges().get(i);
			if(d.memo().getFedPlanAfterPrune(edge)!=x.selectedRootPlans().get(i)
				||x.selectedRootPlans().get(i).getHopRef()!=x.selectedRootHops().get(i))
				throw identity(d.fixtureId(),"DP_EDGE");
			var tie=x.tieReceipts().get(i);
			if(tie.rootHopId()!=edge.getLeft()||tie.selectedPlan()!=x.selectedRootPlans().get(i)
				||tie.selectedPlan().getFedOutType()!=edge.getRight())
				throw identity(d.fixtureId(),"DP_TIE");
		}
		for(var receipt:x.graphExclusionReceipts())
			if(receipt.analysis()!=d.analysis()||!receipt.occurrence().key().equals(receipt.node().key()))
				throw identity(d.fixtureId(),"DP_EXCLUSION");
		return new Selection(d,d.analysis(),d.memo(),d.root(),d.selectedPlan(),x.aggregateChildEdges(),x.selectedRootPlans(),
			x.selectedRootHops(),x.tieReceipts(),x.graphExclusionReceipts(),x.objectiveCostBits(),x.analysisFingerprint(),List.of());
	}

	private static Selection minst(CampaignBFrozenCostFixtureBridge.MinstGraphInput m,PlacementAnalysis requested) {
		var x=new MinStPlacementAdapter().select(requested,m.ownerBound());
		if(x.analysis()!=requested||x.producer()!=m.ownerBound().producerReceipt()
			||!x.analysisFingerprint().equals(requested.analysisFingerprint())
			||x.cutObjectiveBits()!=m.graph().getSelectedCutObjectiveBits()
			||!x.sourcePartitionNodeIds().equals(m.graph().getSelectedSourcePartitionNodeIds())
			||!sameObjects(x.selectedObligations(),m.ownerBound().obligationReceipts())
			||x.selectedReceipts().size()!=requested.occurrences().size())
			throw identity(m.fixtureId(),"MINST_OWNER");
		for(int i=0;i<x.selectedReceipts().size();i++) {
			var receipt=x.selectedReceipts().get(i);var occurrence=requested.occurrences().get(i);
			if(!receipt.planningKey().equals(occurrence.key())||receipt.planningHop()!=occurrence.hop()
				||receipt.executableHop()!=occurrence.hop())
				throw identity(m.fixtureId(),"MINST_SELECTED");
		}
		for(Object obligation:m.selectedObligations())for(Object descriptor:m.registryReceipts())
			if(obligation==descriptor)throw identity(m.fixtureId(),"MINST_DESCRIPTOR_ALIAS");
		return new Selection(m,requested,x.producer(),null,null,List.of(),x.selectedReceipts(),x.selectedObligations(),
			m.registryReceipts(),List.of(),x.cutObjectiveBits(),x.analysisFingerprint(),List.of());
	}

	private static Selection graph(CampaignBFrozenCostFixtureBridge.GraphExclusionInput g) {
		var r=g.result();
		if(r.analysis()!=g.analysis()||r.producer()!=g.analysis()
			||r.certificateReceipts().stream().noneMatch(x->x==g.receipt()))
			throw identity(g.fixtureId(),"GRAPH_EXCLUSION");
		return new Selection(g,g.analysis(),g.analysis(),null,null,List.of(),List.of(),List.of(),List.of(),
			r.certificateReceipts(),0L,r.analysisFingerprint(),List.of());
	}

	private static Selection full(CampaignBFrozenCostFixtureBridge.FullPathInput f) {
		if(f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt retained) {
			var r=new DpPlacementAdapter().selectExact(f.analysis(),retained.memo(),retained.rootPlan());
			validateRetained(f,retained,r);
			return new Selection(f,r.analysis(),r.memo(),null,r.legacyOptimalPlan(),r.aggregateChildEdges(),
				r.selectedRootPlans(),r.selectedRootHops(),r.tieReceipts(),r.graphExclusionReceipts(),r.objectiveCostBits(),
				r.analysisFingerprint(),List.of());
		}
		if(f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedMinstReceipt retained) {
			var snapshot=retained.retained();
			validateRetainedMinst(f,snapshot);
			var selected=snapshot.selection();
			return new Selection(f,snapshot.analysis(),selected.producer(),null,snapshot.input(),List.of(),
				selected.selectedReceipts(),selected.selectedObligations(),List.of(),List.of(),selected.cutObjectiveBits(),
				selected.analysisFingerprint(),List.of());
		}
		throw identity(f.fixtureId(),"FULL_CERTIFICATE");
	}

	private static void validateRetainedMinst(CampaignBFrozenCostFixtureBridge.FullPathInput f,
		org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.LegacyMinstOfflineSelectedCapture.RetainedFullPath snapshot) {
		var selected=snapshot.selection();
		if(snapshot.analysis()!=f.analysis()||snapshot.input().analysis()!=f.analysis()
			||selected.analysis()!=f.analysis()||f.producer()!=selected.producer()
			||!selected.analysisFingerprint().equals(f.analysis().analysisFingerprint())
			||!selected.analysisFingerprint().equals(f.inputFingerprint()))
			throw identity(f.fixtureId(),"FULL_MINST_OWNER");
		if(selected.selectedReceipts().size()!=f.analysis().occurrences().size())
			throw identity(f.fixtureId(),"FULL_MINST_CARDINALITY");
		for(int i=0;i<selected.selectedReceipts().size();i++) {
			var receipt=selected.selectedReceipts().get(i);var occurrence=f.analysis().occurrences().get(i);
			if(!receipt.planningKey().equals(occurrence.key())||receipt.planningHop()!=occurrence.hop()
				||receipt.executableHop()!=occurrence.hop())
				throw identity(f.fixtureId(),"FULL_MINST_SELECTED");
		}
	}

	private static void validateRetained(CampaignBFrozenCostFixtureBridge.FullPathInput f,
		CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt retained,DpPlacementAdapter.ExactSelection exact) {
		var snapshot=retained.retained();
		if(exact.analysis()!=f.analysis()||exact.memo()!=retained.memo()||snapshot.memo()!=retained.memo()
			||exact.legacyOptimalPlan()!=retained.rootPlan()||snapshot.rootPlan()!=retained.rootPlan()
			||!exact.analysisFingerprint().equals(f.analysis().analysisFingerprint())
			||!exact.analysisFingerprint().equals(f.inputFingerprint())) throw identity(f.fixtureId(),"FULL_OWNER");
		if(!sameObjects(exact.aggregateChildEdges(),retained.rootPlan().getChildFedPlans())
			||!sameObjects(exact.selectedRootPlans(),snapshot.rootChildPlanReceipts())
			||!sameObjects(exact.selectedRootHops(),snapshot.rootHops())) throw identity(f.fixtureId(),"FULL_SELECTED");
		int size=exact.aggregateChildEdges().size();
		if(exact.tieReceipts().size()!=size)throw identity(f.fixtureId(),"FULL_TIE_CARDINALITY");
		for(int i=0;i<size;i++) {
			var edge=exact.aggregateChildEdges().get(i);var plan=exact.selectedRootPlans().get(i);
			var hop=exact.selectedRootHops().get(i);var tie=exact.tieReceipts().get(i);
			if(retained.memo().getFedPlanAfterPrune(edge)!=plan||plan.getHopRef()!=hop
				||tie.rootHopId()!=edge.getLeft()||tie.selectedPlan()!=plan||plan.getFedOutType()!=edge.getRight())
				throw identity(f.fixtureId(),"FULL_TIE");
		}
		long objective=Double.doubleToRawLongBits(snapshot.rootObjective());
		if(exact.objectiveCostBits()!=objective
			||exact.objectiveCostBits()!=Double.doubleToRawLongBits(retained.rootPlan().getCumulativeCost()))
			throw identity(f.fixtureId(),"FULL_OBJECTIVE");
		int exclusionIndex=0;
		for(var node:f.analysis().graph().nodes())for(var exclusion:node.exclusions()) {
			if(exclusionIndex>=exact.graphExclusionReceipts().size())throw identity(f.fixtureId(),"FULL_EXCLUSION_CARDINALITY");
			var receipt=exact.graphExclusionReceipts().get(exclusionIndex++);
			var occurrence=f.analysis().occurrences().stream().filter(o->o.key().equals(node.key())).findFirst().orElseThrow();
			if(receipt.analysis()!=f.analysis()||receipt.occurrence()!=occurrence||receipt.node()!=node||receipt.exclusion()!=exclusion)
				throw identity(f.fixtureId(),"FULL_EXCLUSION");
		}
		if(exclusionIndex!=exact.graphExclusionReceipts().size())throw identity(f.fixtureId(),"FULL_EXCLUSION_CARDINALITY");
		for(List<?> receipts:List.of(exact.aggregateChildEdges(),exact.selectedRootPlans(),exact.selectedRootHops(),
			exact.tieReceipts(),exact.graphExclusionReceipts()))for(Object receipt:receipts)if(isWrapper(receipt))
			throw identity(f.fixtureId(),"FULL_WRAPPER");
	}

	static boolean isWrapper(Object receipt) {
		return receipt instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt
			||receipt instanceof CampaignBFrozenCostFixtureBridge.RetainedMinstReceipt
			||receipt instanceof CampaignBFrozenCostFixtureBridge.FullPathCertificate
			||receipt instanceof CampaignBFrozenCostFixtureBridge.RoleAlias
			||receipt instanceof CampaignBFrozenCostFixtureBridge.RegistryCertificate
			||receipt instanceof CampaignBFrozenCostFixtureBridge.RepairCertificate
			||receipt instanceof CampaignBFrozenCostFixtureBridge.CutEdgeReceipt;
	}

	private static AssertionError identity(String fixture,String receipt) {
		return new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+fixture+"|receipt="+receipt);
	}
	static boolean sameObjects(List<?> a,List<?> b) {
		if(a.size()!=b.size())return false;
		for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;
		return true;
	}
	private R4CostAdapterBridge(){}
}
