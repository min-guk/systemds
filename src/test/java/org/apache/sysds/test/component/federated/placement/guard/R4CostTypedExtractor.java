/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;

/** Derives every live manifest field from identity-verified concrete producer receipts. */
final class R4CostTypedExtractor {
	record FieldRequirement(R4CostAdapterBridge.EvidenceKind kind,R4CostAdapterBridge.ReceiptField receiptField,
		String dp04Arm) { }
	static CampaignBLiteralAuthority.TypedPlan extract(CampaignBLiteralAuthority.Expected expected,
		R4CostAdapterBridge.Selection selection)throws Exception {
		return extract(expected,List.of(selection));
	}
	static CampaignBLiteralAuthority.TypedPlan extract(CampaignBLiteralAuthority.Expected expected,
		List<R4CostAdapterBridge.Selection> selections)throws Exception {
		if(expected.fixture().equals("C2-DP-04-ANCHOR-CONTRAST")){
			if(selections.size()!=2||selections.stream().filter(s->s.input().fixtureId().endsWith(":CONCRETE")).count()!=1
				||selections.stream().filter(s->s.input().fixtureId().endsWith(":MISSING")).count()!=1)
				throw new AssertionError("R4_DP04_ARM_BIJECTION|selections="+selections.size());
		}
		else if(selections.size()!=1)throw new AssertionError("R4_TYPED_INPUT_BIJECTION|selections="+selections.size());
		Map<String,String> merged=new LinkedHashMap<>();Set<CampaignBFrozenCostFixtureBridge.CostSelectionInput> inputs=
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for(var selection:selections){if(!inputs.add(selection.input()))throw new AssertionError("R4_DUPLICATE_TYPED_INPUT|"+selection.input().fixtureId());
			validateSelectionReceipts(selection);List<R4CostAdapterBridge.TypedEvidence> evidence=selection.evidence().isEmpty()?
				testEvidence(expected,selection):selection.evidence();
			for(var entry:derivePartial(expected,selection.input(),evidence).entrySet())
				if(merged.put(entry.getKey(),entry.getValue())!=null)throw new AssertionError("R4_DUPLICATE_TYPED_FIELD|"+entry.getKey());}
		return CampaignBLiteralAuthority.fromDerived(expected,Map.copyOf(merged));
	}

	static List<R4CostAdapterBridge.TypedEvidence> testEvidence(CampaignBLiteralAuthority.Expected expected,
		R4CostAdapterBridge.Selection selection) {
		if(selection.input() instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f) {
			List<R4CostAdapterBridge.TypedEvidence> full=new java.util.ArrayList<>();
			for(var row:expected.rows())for(String field:row.fields().keySet()) {
				var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);
	                var req=requirement(coordinate); Object producer=f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedMinstReceipt?selection.producer():f.analysis(), receipt=f.analysis(); CampaignBFrozenCostFixtureBridge.EvidenceRole role=new CampaignBFrozenCostFixtureBridge.AnalysisReceiptRole(f.analysis());
                if(selection.producer()!=null && req.receiptField()!=R4CostAdapterBridge.ReceiptField.ANALYSIS) producer=selection.producer();
                if(req.receiptField()==R4CostAdapterBridge.ReceiptField.DP_ROOT||req.receiptField()==R4CostAdapterBridge.ReceiptField.DP_SELECTED||req.receiptField()==R4CostAdapterBridge.ReceiptField.FULL_CERTIFICATE){receipt=selection.selectedReceipt(); role=new CampaignBFrozenCostFixtureBridge.PlanReceiptRole((org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)receipt);}
                else if(req.receiptField()==R4CostAdapterBridge.ReceiptField.DP_ENUMERATED){
                    if(!(f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt retained))
                        throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+f.fixtureId()+"|enumeratedContext");
                    receipt=retained.retained().selectedPlanReceipts().get(0);
                    role=new CampaignBFrozenCostFixtureBridge.PlanReceiptRole((org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)receipt);
                }
                full.add(new R4CostAdapterBridge.TypedEvidence(req.kind(),producer,role,coordinate,req.receiptField(),receipt));
			}
			return List.copyOf(full);
		}
		if(selection.input() instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m)
			return minstEvidence(expected,m);
		if(selection.input() instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph){
			if(expected.rows().stream().anyMatch(row->!row.kind().equals("NEUTRAL_GRAPH_EXCLUSION")))
				throw new AssertionError("R4_DP08_FROZEN_RESOURCE_CONTRACT_MISMATCH");
			List<R4CostAdapterBridge.TypedEvidence> graphEvidence=new java.util.ArrayList<>();
			for(var row:expected.rows())for(String field:row.fields().keySet()){
				var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);
				graphEvidence.add(new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,
					graph.producer(),new CampaignBFrozenCostFixtureBridge.GraphExclusionRole(graph.receipt()),coordinate,
					R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,graph.receipt()));
			}
			return List.copyOf(graphEvidence);
		}
		List<R4CostAdapterBridge.TypedEvidence> evidence=new java.util.ArrayList<>();
		for(var row:expected.rows())for(String field:row.fields().keySet()){
			var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);
			var requirement=requirement(coordinate);
			if(requirement.dp04Arm()!=null&&!selection.input().fixtureId().endsWith(':'+requirement.dp04Arm()))continue;
			Object receipt=dpReceipt(selection,requirement.receiptField(),field);
			var role=dpRole(selection,requirement.receiptField(),receipt);
			evidence.add(new R4CostAdapterBridge.TypedEvidence(requirement.kind(),selection.producer(),role,coordinate,
				requirement.receiptField(),receipt));
		}
		return List.copyOf(evidence);
	}
	private static List<R4CostAdapterBridge.TypedEvidence> minstEvidence(
		CampaignBLiteralAuthority.Expected expected,CampaignBFrozenCostFixtureBridge.MinstGraphInput input) {
		List<R4CostAdapterBridge.TypedEvidence> evidence=new java.util.ArrayList<>();
		for(var row:expected.rows())for(String field:row.fields().keySet()) {
			var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);
			var requirement=requirement(coordinate);
			CampaignBFrozenCostFixtureBridge.RoleAlias role=minstRole(expected,input,coordinate,requirement.receiptField());
			Object receipt=switch(requirement.receiptField()) {
				case ANALYSIS->input.analysis();
				case MINST_GRAPH,MINST_CUT_EDGE->input.graph();
				case MINST_CUT_CERTIFICATE->input.ownerBound();
				case MINST_REPAIR->input.analysis().hop(role.compiledKey()).orElseThrow();
				case MINST_OBLIGATION->input.ownerBound().obligationReceipts().stream()
					.filter(o->o.childHopId()==role.producerHopId()).findFirst().orElseThrow();
				case MINST_REGISTRY->input.registryCertificates().stream().filter(r->r.producer()==role)
					.filter(r->registryKind(row.kind(),r.spec())).findFirst().orElseThrow().spec();
				default->throw new AssertionError("R4_MINST_EVIDENCE_FIELD|"+requirement.receiptField());
			};
			evidence.add(new R4CostAdapterBridge.TypedEvidence(requirement.kind(),input.producer(),role,coordinate,
				requirement.receiptField(),receipt));
		}
		return List.copyOf(evidence);
	}
	private static CampaignBFrozenCostFixtureBridge.RoleAlias minstRole(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.MinstGraphInput input,R4CostAdapterBridge.FieldCoordinate coordinate,
		R4CostAdapterBridge.ReceiptField field) {
		boolean repairOwnedKey=field==R4CostAdapterBridge.ReceiptField.ANALYSIS && coordinate.fieldName().equals("key")
			&& expected.rows().stream().filter(r->r.digest().equals(coordinate.rowDigest()))
				.flatMap(r->r.fields().keySet().stream())
				.anyMatch(name->requirement(new R4CostAdapterBridge.FieldCoordinate(coordinate.rowDigest(),
					coordinate.rowKind(),name)).receiptField()==R4CostAdapterBridge.ReceiptField.MINST_REPAIR);
		if(field==R4CostAdapterBridge.ReceiptField.MINST_REPAIR || repairOwnedKey) {
			var rows=expected.rows().stream().filter(r->r.kind().equals(coordinate.rowKind())).toList();
			int ordinal=java.util.stream.IntStream.range(0,rows.size()).filter(i->rows.get(i).digest().equals(coordinate.rowDigest())).findFirst().orElseThrow();
			return input.repairCertificates().get(ordinal).role();
		}
		if(field==R4CostAdapterBridge.ReceiptField.MINST_REGISTRY)
			return input.registryCertificates().stream().filter(r->registryKind(coordinate.rowKind(),r.spec())).findFirst().orElseThrow().producer();
		if(field==R4CostAdapterBridge.ReceiptField.MINST_OBLIGATION) {
			var obligation=input.ownerBound().obligationReceipts().stream()
				.findFirst().orElseThrow();
			return input.aliases().stream().filter(a->a.producerHopId()==obligation.childHopId()).findFirst().orElseThrow();
		}
		return input.aliases().stream().sorted(java.util.Comparator.comparing(a->a.compiledKey().normalizedSignature())).findFirst().orElseThrow();
	}
	private static boolean registryKind(String rowKind,Object spec) {
		return rowKind.equals("REGISTRY_REFED")&&spec instanceof org.apache.sysds.lops.compile.FederatedRefedRegistry.AnchorSpec
			||rowKind.equals("REGISTRY_FOUT_MATERIALIZE")&&spec instanceof org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry.MaterializeSpec
			||rowKind.equals("REGISTRY_LOCAL_MATERIALIZE")&&spec instanceof org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.LocalMaterializeSpec;
	}

	private static CampaignBFrozenCostFixtureBridge.EvidenceRole dpRole(R4CostAdapterBridge.Selection selection,
		R4CostAdapterBridge.ReceiptField field,Object receipt) {
		if(field==R4CostAdapterBridge.ReceiptField.FULL_CERTIFICATE){
            if(!(receipt instanceof org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)) throw new AssertionError("R4_DP_FULL_RECEIPT_TYPE|"+selection.input().fixtureId());
            return new CampaignBFrozenCostFixtureBridge.PlanReceiptRole((org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)receipt);
        }
		Hop target=switch(field){
			case DP_ROOT->selection.root();
			case DP_SELECTED->((org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)
				selection.selectedReceipt()).getHopRef();
			case DP_ENUMERATED->((org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)
				receipt).getHopRef();
			case ANALYSIS,DP_MEMO->selection.selectedReceipt() instanceof org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan p?
				p.getHopRef():null;
			default->null;
		};
		if(target==null)return selection.input().aliases().stream()
			.sorted(java.util.Comparator.comparing(a->a.compiledKey().normalizedSignature())).findFirst().orElseThrow();
		var matches=selection.input().aliases().stream().filter(a->selection.analysis().hop(a.compiledKey()).orElseThrow()==target).toList();
		if(matches.size()!=1)throw new AssertionError("R4_TYPED_ROLE_ALIAS_MISMATCH|fixture="+selection.input().fixtureId()
			+"|receipt="+field+"|matches="+matches.size());
		return matches.get(0);
	}

	private static Object dpReceipt(R4CostAdapterBridge.Selection selection,R4CostAdapterBridge.ReceiptField field,
		String fieldName) {
		return switch(field){
			case ANALYSIS->selection.analysis();case DP_MEMO->selection.producer();case DP_ROOT->selection.root();
			case DP_SELECTED->selection.selectedReceipt();
			case DP_ENUMERATED->{
                if(!(selection.input() instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d))
                    throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+selection.input().fixtureId()+"|enumeratedContext");
                List<?> variants=d.enumeratedPlans();
                if(fieldName.equals("rank0Cost"))yield variants.get(0);
				if(fieldName.equals("rank1Cost"))yield variants.get(variants.size()-1);
				if(fieldName.equals("cp")||fieldName.equals("fed"))yield uniquePlanByExec(variants,
					fieldName.equals("cp")?org.apache.sysds.common.Types.ExecType.CP:org.apache.sysds.common.Types.ExecType.FED,d.fixtureId());
				String output=fieldName.equals("lout")?"LOUT":"FOUT";
				yield variants.stream().map(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan.class::cast)
					.filter(plan->plan.getFedOutType().name().equals(output)).findFirst().orElseThrow();}
			case FULL_CERTIFICATE->selection.selectedReceipt();
			default->throw new AssertionError("R4_TEST_EVIDENCE_RECEIPT|"+field);
		};
	}

	static Map<String,String> derive(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.CostSelectionInput input,List<R4CostAdapterBridge.TypedEvidence> evidence) {
		Map<String,String> values=derivePartial(expected,input,evidence);for(var row:expected.rows())for(String field:row.fields().keySet()){
			String key=row.digest()+'.'+field;if(!values.containsKey(key))throw new AssertionError("R4_TYPED_EVIDENCE_MISSING|"+key);}
		return values;
	}
	private static Map<String,String> derivePartial(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.CostSelectionInput input,List<R4CostAdapterBridge.TypedEvidence> evidence) {
		Map<String,R4CostAdapterBridge.TypedEvidence> live=new LinkedHashMap<>();
		Set<String> required=new LinkedHashSet<>();
		for(var row:expected.rows())for(String field:row.fields().keySet())required.add(row.digest()+'.'+field);
		for(var item:evidence) {
			validateIdentity(input,item);
			validateCoordinateReceipt(expected,input,item);
			String key=item.coordinate().rowDigest()+'.'+item.coordinate().fieldName();
			if(!required.contains(key)||expected.rows().stream().noneMatch(r->r.digest().equals(item.coordinate().rowDigest())
				&&r.kind().equals(item.coordinate().rowKind())&&r.fields().containsKey(item.coordinate().fieldName())))
				throw new AssertionError("R4_TYPED_FIELD_NOT_LIVE|"+key);
			if(live.put(key,item)!=null)throw new AssertionError("R4_DUPLICATE_TYPED_FIELD|"+key);
		}
		Map<String,String> values=new LinkedHashMap<>();
		for(var entry:live.entrySet())values.put(entry.getKey(),deriveValue(expected,input,entry.getValue()));
		return Map.copyOf(values);
	}
	private static void validateCoordinateReceipt(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.CostSelectionInput input,R4CostAdapterBridge.TypedEvidence item){
		if(!(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m))return;String kind=item.coordinate().rowKind();
		if(item.receiptField()==R4CostAdapterBridge.ReceiptField.MINST_CUT_EDGE){var rows=expected.rows().stream().filter(r->r.kind().equals(kind)).toList();
			int ordinal=java.util.stream.IntStream.range(0,rows.size()).filter(i->rows.get(i).digest().equals(item.coordinate().rowDigest())).findFirst().orElse(-1);
			if(ordinal<0||ordinal>=m.cutCertificate().edges().size()||item.receipt()!=m.graph())throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|cutEdgeOrdinal="+ordinal);}
		if(item.receiptField()==R4CostAdapterBridge.ReceiptField.MINST_REPAIR){var rows=expected.rows().stream().filter(r->r.kind().equals(kind)).toList();
			int ordinal=java.util.stream.IntStream.range(0,rows.size()).filter(i->rows.get(i).digest().equals(item.coordinate().rowDigest())).findFirst().orElse(-1);
			if(ordinal<0||ordinal>=m.repairCertificates().size()||item.receipt()!=m.analysis().hop(m.repairCertificates().get(ordinal).role().compiledKey()).orElseThrow())throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|repairOrdinal="+ordinal);}
	}

	static void rejectGoldenEcho(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.CostSelectionInput input,CampaignBLiteralAuthority.TypedPlan echo,
		List<R4CostAdapterBridge.TypedEvidence> evidence) {
		if(!expected.planner().equals(echo.planner())||!expected.fixture().equals(echo.fixture())
			||!expected.assignments().equals(echo.assignments())||!expected.relocations().equals(echo.relocations())
			||!expected.obligations().equals(echo.obligations())||!expected.anchors().equals(echo.anchors())
			||!expected.registries().equals(echo.registries())||!expected.objective().equals(echo.objective())
			||!expected.ties().equals(echo.ties())||!expected.trtw().equals(echo.trtw())
			||!expected.boundaries().equals(echo.boundaries())||!expected.clones().equals(echo.clones())
			||!expected.structural().equals(echo.structural())||!expected.facts().equals(echo.facts())
			||!expected.fingerprint().equals(echo.fingerprint()))
			throw new AssertionError("R4_GOLDEN_ECHO_SETUP");
		derive(expected,input,evidence);
		throw new AssertionError("R4_GOLDEN_ECHO_ADMITTED");
	}

	private static void validateSelectionReceipts(R4CostAdapterBridge.Selection s) {
		if(s.input().producer()!=s.producer()||s.input().analysis()!=s.analysis())
			throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+s.input().fixtureId());
		if(s.input() instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d) validateDpSelection(s,d,d.memo(),d.aggregatePlan(),
            d.aggregatePlan().getChildFedPlans(),null,null);
		else if(s.input() instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m) {
			boolean ordered=s.orderedReceipts().size()==m.analysis().occurrences().size();
			if(ordered)for(int i=0;i<s.orderedReceipts().size();i++) {
				var receipt=(MinStPlacementAdapter.SelectedReceipt)s.orderedReceipts().get(i);
				var occurrence=m.analysis().occurrences().get(i);
				ordered=receipt.planningKey().equals(occurrence.key())&&receipt.planningHop()==occurrence.hop()&&receipt.executableHop()==occurrence.hop();
				if(!ordered)break;
			}
			if(!sameObjects(s.obligationReceipts(),m.ownerBound().obligationReceipts())||!sameObjects(s.registryReceipts(),m.registryReceipts())
				||s.evidence().isEmpty()&&(!s.certificateReceipts().isEmpty()||!ordered))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+m.fixtureId()+"|receipt=MINST");
		}
		else if(s.input() instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph){
			if(!sameObjects(s.certificateReceipts(),graph.result().certificateReceipts())||s.certificateReceipts().stream().noneMatch(x->x==graph.receipt()))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+graph.fixtureId()+"|receipt=GRAPH_EXCLUSION");
		}
        else if(s.input() instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f
            &&f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt retained)
            validateDpSelection(s,f,retained.memo(),retained.rootPlan(),retained.rootPlan().getChildFedPlans(),
                retained.retained().rootChildPlanReceipts(),retained.retained().rootHops());
		else if(s.input() instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f
			&&f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedMinstReceipt retained) {
			var snapshot=retained.retained();var selected=snapshot.selection();
			if(s.analysis()!=snapshot.analysis()||s.producer()!=selected.producer()
				||s.selectedReceipt()!=snapshot.input()||s.objectiveCostBits()!=selected.cutObjectiveBits()
				||!s.analysisFingerprint().equals(selected.analysisFingerprint())
				||!sameObjects(s.orderedReceipts(),selected.selectedReceipts())
				||!sameObjects(s.obligationReceipts(),selected.selectedObligations()))
				throw producer(f,"MINST_FULL");
		}
	}

    private static void validateDpSelection(R4CostAdapterBridge.Selection s,
        CampaignBFrozenCostFixtureBridge.CostSelectionInput input,
        org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable memo,
        org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan rootPlan,
        List<?> expectedEdges,List<?> expectedPlans,List<?> expectedHops) {
        if(s.producer()!=memo||s.selectedReceipt()!=rootPlan&&!(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d&&s.selectedReceipt()==d.selectedPlan())
            ||!s.analysisFingerprint().equals(input.analysis().analysisFingerprint())
            ||!sameObjects(s.aggregateReceipts(),expectedEdges)) throw producer(input,"DP_OWNER");
        int n=s.aggregateReceipts().size();
        if(s.orderedReceipts().size()!=n||s.obligationReceipts().size()!=n||s.registryReceipts().size()!=n)
            throw producer(input,"DP_CARDINALITY");
        for(int i=0;i<n;i++) {
            var edge=(org.apache.commons.lang3.tuple.Pair<Long,org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput>)s.aggregateReceipts().get(i);
            var plan=(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)s.orderedReceipts().get(i);
            Hop hop=(Hop)s.obligationReceipts().get(i);var tie=(DpPlacementAdapter.TieReceipt)s.registryReceipts().get(i);
            if(memo.getFedPlanAfterPrune(edge)!=plan||plan.getHopRef()!=hop||tie.rootHopId()!=edge.getLeft()
                ||tie.selectedPlan()!=plan||plan.getFedOutType()!=edge.getRight()
                ||expectedPlans!=null&&expectedPlans.get(i)!=plan||expectedHops!=null&&expectedHops.get(i)!=hop)
                throw producer(input,"DP_SELECTED");
        }
        if(s.objectiveCostBits()!=Double.doubleToRawLongBits(rootPlan.getCumulativeCost()))throw producer(input,"DP_OBJECTIVE");
        int receiptIndex=0;
        for(var node:input.analysis().graph().nodes())for(var exclusion:node.exclusions()) {
            if(receiptIndex>=s.certificateReceipts().size())throw producer(input,"DP_EXCLUSION_CARDINALITY");
            var receipt=(DpPlacementAdapter.GraphExclusionReceipt)s.certificateReceipts().get(receiptIndex++);
            var occurrence=input.analysis().occurrences().stream().filter(o->o.key().equals(node.key())).findFirst().orElseThrow();
            if(receipt.analysis()!=input.analysis()||receipt.occurrence()!=occurrence||receipt.node()!=node||receipt.exclusion()!=exclusion)
                throw producer(input,"DP_EXCLUSION");
        }
        if(receiptIndex!=s.certificateReceipts().size())throw producer(input,"DP_EXCLUSION_CARDINALITY");
        for(List<?> receipts:List.of(s.aggregateReceipts(),s.orderedReceipts(),s.obligationReceipts(),s.registryReceipts(),s.certificateReceipts()))
            for(Object receipt:receipts)if(R4CostAdapterBridge.isWrapper(receipt))throw producer(input,"DP_WRAPPER");
    }
    private static AssertionError producer(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,String receipt) {
        return new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|receipt="+receipt);
    }

	private static void validateIdentity(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,
		R4CostAdapterBridge.TypedEvidence item) {
		if(item.producer()!=input.producer())throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId());
		FieldRequirement required=requirement(item.coordinate());
		if(item.kind()!=required.kind()||item.receiptField()!=required.receiptField())
			throw new AssertionError("R4_TYPED_RECEIPT_FIELD_MISMATCH|coordinate="+item.coordinate()+"|required="+required+"|actual="+item.kind()+"/"+item.receiptField());
		if(required.dp04Arm()!=null&&!input.fixtureId().endsWith(':'+required.dp04Arm()))
			throw new AssertionError("R4_DP04_ARM_BIJECTION|coordinate="+item.coordinate()+"|required="+required.dp04Arm());
		if(input instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph){
			validateGraphExclusionEvidence(graph,item);
			return;
		}
        if(input instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f && f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt retained) {
            if(item.receiptField()!=R4CostAdapterBridge.ReceiptField.FULL_CERTIFICATE || item.producer()!=retained.memo() || !(item.receipt() instanceof org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan) || !(item.role() instanceof CampaignBFrozenCostFixtureBridge.PlanReceiptRole role) || role.rootPlan()!=item.receipt()) throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|fullReceipt");
            return;
        }
		if(input instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f
			&&f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedMinstReceipt retained) {
			if(item.receiptField()!=R4CostAdapterBridge.ReceiptField.ANALYSIS||item.receipt()!=f.analysis()
				||item.producer()!=retained.retained().selection().producer()
				||!(item.role() instanceof CampaignBFrozenCostFixtureBridge.AnalysisReceiptRole role
					&&role.analysis()==f.analysis()))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|minstFullReceipt");
			return;
		}
		if(input instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f) {
			if(item.receiptField()!=R4CostAdapterBridge.ReceiptField.ANALYSIS||item.receipt()!=f.analysis()
				||!(item.role() instanceof CampaignBFrozenCostFixtureBridge.AnalysisReceiptRole role&&role.analysis()==f.analysis()
					||item.role() instanceof CampaignBFrozenCostFixtureBridge.RoleAlias alias&&f.aliases().stream().anyMatch(a->a==alias)))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|fullReceipt");
			return;
		}
		var role=hopRole(input,item);Hop hop=input.analysis().hop(role.compiledKey()).orElseThrow();
		if(hop.getHopID()!=role.producerHopId())throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|hopCrossCheck");
		if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d) {
			if(d.root()!=hop&&d.enumeratedPlans().stream().noneMatch(p->p.getHopRef()==hop))
				throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|memoHop");
			boolean exact=switch(item.receiptField()){
				case ANALYSIS->item.receipt()==d.analysis();case DP_MEMO->item.receipt()==d.memo();case DP_ROOT->item.receipt()==d.root();
				case DP_SELECTED->item.receipt()==d.selectedPlan();case DP_ENUMERATED->d.enumeratedPlans().stream().anyMatch(p->p==item.receipt());
				default->false;};
			if(!exact)throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|receipt="+item.receiptField());
			validateDpEnumeratedCoordinate(d,item);
		}
		else if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m) {
			boolean exact=switch(item.receiptField()){
				case ANALYSIS->item.receipt()==m.analysis();case MINST_GRAPH->item.receipt()==m.graph();
				case MINST_CUT_EDGE->item.receipt()==m.graph();case MINST_CUT_CERTIFICATE->item.receipt()==m.ownerBound();
				case MINST_REPAIR->item.receipt()==hop;
				case MINST_OBLIGATION->m.ownerBound().obligationReceipts().stream().anyMatch(x->x==item.receipt());
				case MINST_REGISTRY->m.registryReceipts().stream().anyMatch(x->x==item.receipt());default->false;};
			if(!exact)throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|receipt="+item.receiptField());
			if(item.receipt() instanceof org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput.ObligationReceipt o
				&&o.childHopId()!=role.producerHopId())
				throw new AssertionError("R4_TYPED_ROLE_ALIAS_MISMATCH|fixture="+input.fixtureId()+"|obligationChild");
			validateMinstReceiptClass(item);
		}
	}
	static void validateGraphExclusionEvidence(CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph,
		R4CostAdapterBridge.TypedEvidence item){
		if(item.producer()!=graph.producer()||item.kind()!=R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION
			||item.receiptField()!=R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION||item.receipt()!=graph.receipt()
			||!(item.role() instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionRole role)
			||role.receipt()!=graph.receipt()||graph.result().certificateReceipts().stream().noneMatch(x->x==item.receipt()))
			throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+graph.fixtureId()+"|graphExclusion");
	}

	static FieldRequirement requirement(R4CostAdapterBridge.FieldCoordinate c){
		String k=c.rowKind(),f=c.fieldName();String arm=null;
		if(k.equals("NEUTRAL_GRAPH_EXCLUSION"))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,
			R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,null);
		if(k.equals("DP_FULL_OFFLINE_SELECTION"))
			return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_CERTIFICATE,R4CostAdapterBridge.ReceiptField.FULL_CERTIFICATE,null);
		if(k.equals("MINST_FULL_OFFLINE_SELECTION"))
			return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.PLACEMENT_ANALYSIS,R4CostAdapterBridge.ReceiptField.ANALYSIS,null);
		if(k.equals("DP_ANCHOR_CAPABILITY"))arm=f.startsWith("missing")?"MISSING":f.startsWith("concrete")||f.equals("selectedRegistry")||f.equals("registryProducedByFrozenPolicy")?"CONCRETE":"CONCRETE";
		if(k.startsWith("DP_")){
			if(f.equals("evidence"))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.DP_MEMO,R4CostAdapterBridge.ReceiptField.DP_MEMO,arm);
			if(f.equals("key")||f.equals("runtimeOutputConstraint"))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_CERTIFICATE,R4CostAdapterBridge.ReceiptField.DP_ROOT,arm);
			if(f.equals("seed"))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.PLACEMENT_ANALYSIS,R4CostAdapterBridge.ReceiptField.ANALYSIS,arm);
			if(Set.of("rank0Cost","rank1Cost").contains(f)||k.equals("DP_VARIANT_ORDER")&&Set.of("cp","fed").contains(f))
				return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_PLAN,R4CostAdapterBridge.ReceiptField.DP_ENUMERATED,arm);
			if(Set.of("lout","fout","cp","fed").contains(f))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.DP_MEMO,R4CostAdapterBridge.ReceiptField.DP_MEMO,arm);
			if(Set.of("objective","selected","selectedInsertionOrdinal","selectedChildOutput","selectedOutput","selectedExec","missingSelection","concreteSelection","selectedRegistry","registryProducedByFrozenPolicy").contains(f))
				return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_PLAN,R4CostAdapterBridge.ReceiptField.DP_SELECTED,arm);
			if(Set.of("tieRule","equal").contains(f))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.DP_MEMO,R4CostAdapterBridge.ReceiptField.DP_MEMO,arm);
			if(Set.of("missingAnchorCapable","concreteAnchorCapable").contains(f))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_CERTIFICATE,R4CostAdapterBridge.ReceiptField.DP_ROOT,arm);
		}
		if(k.equals("MINST_CUT_EDGE"))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.MINST_CUT,R4CostAdapterBridge.ReceiptField.MINST_CUT_EDGE,null);
		if(k.equals("MINST_CUT_OBJECTIVE"))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_CERTIFICATE,R4CostAdapterBridge.ReceiptField.MINST_CUT_CERTIFICATE,null);
		if(k.startsWith("MINST_")){
			if(f.equals("key"))
				return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.PLACEMENT_ANALYSIS,R4CostAdapterBridge.ReceiptField.ANALYSIS,null);
			return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_CERTIFICATE,R4CostAdapterBridge.ReceiptField.MINST_REPAIR,null);
		}
		if(k.startsWith("REGISTRY_")){
			if(f.equals("consumers")||f.equals("consumerCount"))return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.SELECTED_CERTIFICATE,R4CostAdapterBridge.ReceiptField.MINST_OBLIGATION,null);
			return new FieldRequirement(R4CostAdapterBridge.EvidenceKind.REGISTRY_SNAPSHOT,R4CostAdapterBridge.ReceiptField.MINST_REGISTRY,null);
		}
		throw new AssertionError("R4_TYPED_FIELD_NOT_LIVE|"+c);
	}
	private static CampaignBFrozenCostFixtureBridge.RoleAlias hopRole(
		CampaignBFrozenCostFixtureBridge.CostSelectionInput input,R4CostAdapterBridge.TypedEvidence item){
		if(!(item.role() instanceof CampaignBFrozenCostFixtureBridge.RoleAlias role)||
			input.aliases().stream().noneMatch(a->a==role))
			throw new AssertionError("R4_TYPED_ROLE_ALIAS_MISMATCH|fixture="+input.fixtureId());
		return role;
	}

	private static void validateDpEnumeratedCoordinate(CampaignBFrozenCostFixtureBridge.DpMemoInput d,R4CostAdapterBridge.TypedEvidence item){
		if(item.receiptField()!=R4CostAdapterBridge.ReceiptField.DP_ENUMERATED)return;
		var plan=(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)item.receipt();String f=item.coordinate().fieldName();
		if(f.equals("lout")&&!plan.getFedOutType().name().equals("LOUT")||f.equals("fout")&&!plan.getFedOutType().name().equals("FOUT")
			||f.equals("cp")&&plan.getExecType()!=org.apache.sysds.common.Types.ExecType.CP
			||f.equals("fed")&&plan.getExecType()!=org.apache.sysds.common.Types.ExecType.FED
			||f.equals("rank0Cost")&&plan!=d.enumeratedPlans().get(0)||f.equals("rank1Cost")&&plan!=d.enumeratedPlans().get(d.enumeratedPlans().size()-1))
			throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+d.fixtureId()+"|enumeratedCoordinate="+f);
	}
	private static org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan uniquePlanByExec(
		List<?> receipts,org.apache.sysds.common.Types.ExecType exec,String fixture){
		var matches=receipts.stream().map(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan.class::cast)
			.filter(plan->plan.getExecType()==exec).toList();
		if(matches.size()!=1)throw new AssertionError("R4_DP_EXEC_VARIANT_BIJECTION|fixture="+fixture+"|exec="+exec+"|matches="+matches.size());
		return matches.get(0);
	}
	private static String runtimeOutputConstraint(CampaignBFrozenCostFixtureBridge.DpMemoInput input){
		var outputs=input.enumeratedPlans().stream().map(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan::getFedOutType)
			.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
		if(outputs.isEmpty())throw new AssertionError("R4_DP_OUTPUT_CONSTRAINT_EMPTY|fixture="+input.fixtureId());
		if(outputs.size()>1)return "MIXED";
		return outputs.iterator().next().name()+"_ONLY";
	}
	private static void validateMinstReceiptClass(R4CostAdapterBridge.TypedEvidence item){
		if(item.receiptField()!=R4CostAdapterBridge.ReceiptField.MINST_REGISTRY)return;String n=item.receipt().getClass().getSimpleName(),k=item.coordinate().rowKind();
		if(k.equals("REGISTRY_REFED")&&!n.equals("AnchorSpec")||k.equals("REGISTRY_FOUT_MATERIALIZE")&&!n.equals("MaterializeSpec")
			||k.equals("REGISTRY_LOCAL_MATERIALIZE")&&!n.equals("LocalMaterializeSpec"))
			throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|registryClass="+n+"|rowKind="+k);
	}

	private static String deriveValue(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.CostSelectionInput input,
		R4CostAdapterBridge.TypedEvidence item) {
		if(input instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph)return deriveGraphExclusion(graph,item);
		Hop hop=item.role() instanceof CampaignBFrozenCostFixtureBridge.RoleAlias role?
			input.analysis().hop(role.compiledKey()).orElseThrow():null;
		if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d)return deriveDp(d,item,hop);
		if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m)return deriveMinst(expected,m,item,hop);
		return deriveFull(input,item,hop);
	}
	private static String deriveGraphExclusion(CampaignBFrozenCostFixtureBridge.GraphExclusionInput input,
		R4CostAdapterBridge.TypedEvidence item){
		var receipt=(org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.GraphExclusionReceipt)item.receipt();
		return switch(item.coordinate().fieldName()){
			case "seed"->Long.toString(input.seed());case "fixture"->input.sourceFixture();case "nodeKind"->receipt.node().kind().name();
			case "emittedWork"->Boolean.toString(receipt.node().emittedWork());
			case "excludedState"->receipt.exclusion().state().normalizedSignature();
			case "reason"->receipt.exclusion().reasonCode().name();default->throw notLive(item);
		};
	}

	private static String deriveDp(CampaignBFrozenCostFixtureBridge.DpMemoInput d,
		R4CostAdapterBridge.TypedEvidence item,Hop hop) {
		String kind=item.coordinate().rowKind(),field=item.coordinate().fieldName();
		var plan=item.receipt() instanceof org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan?
			(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan)item.receipt():null;
		var memo=item.receipt() instanceof org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable?
			(org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable)item.receipt():null;
		String state=plan==null?null:plan.getExecType()+"/"+plan.getFedOutType();
		java.util.function.Function<org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput,org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan> memoPlan=out->memo==null?null:memo.getFedPlanAfterPrune(hop.getHopID(),out);
		java.util.function.Function<org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput,String> cost=out->{var p=plan!=null&&plan.getFedOutType()==out?plan:memoPlan.apply(out);return p==null?"NONE":Double.toHexString(p.getCumulativeCost());};
		if(kind.equals("DP_ROOT_OBJECTIVE"))return switch(field){
			case "evidence"->"SYNTHETIC_SELECTOR_FIXTURE";case "key"->hopRole(d,item).literalKey();case "seed"->"-1";
			case "lout"->cost.apply(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT);
			case "fout"->cost.apply(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT);
			case "objective"->Double.toHexString(plan.getCumulativeCost());case "selected"->plan.getFedOutType().name();
			case "tieRule"->{var l=d.enumeratedPlans().stream().filter(p->p.getFedOutType()==org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT).findFirst().orElseThrow();
				var f=d.enumeratedPlans().stream().filter(p->p.getFedOutType()==org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT).findFirst().orElseThrow();
				boolean selectedLout=d.selectedPlan().getFedOutType()==org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT;
				if(selectedLout!= (l.getCumulativeCost()<=f.getCumulativeCost()))throw new AssertionError("R4_DP_TIE_POLICY_RECEIPT|fixture="+d.fixtureId());
				yield d.tiePolicy();}default->throw notLive(item);};
		if(kind.equals("DP_ANCHOR_CAPABILITY"))return switch(field){
			case "evidence"->"SYNTHETIC_SELECTOR_FIXTURE";case "key"->hopRole(d,item).literalKey();case "seed"->"-1";
			case "lout"->cost.apply(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT);
			case "fout"->cost.apply(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT);
			case "objective"->Double.toHexString(plan.getCumulativeCost());case "selected"->plan.getFedOutType().name();
			case "tieRule"->"LOUT_LE_FOUT";case "missingAnchorCapable"->Boolean.toString(d.fixtureId().endsWith(":CONCRETE"));
			case "concreteAnchorCapable"->Boolean.toString(d.fixtureId().endsWith(":CONCRETE"));
			case "missingSelection","concreteSelection"->state;case "selectedRegistry"->plan.getFedOutType()==org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT?"FOUT_MATERIALIZE":"NONE";
			case "registryProducedByFrozenPolicy"->Boolean.toString(plan.getFedOutType()==org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT);
			default->throw notLive(item);};
		if(kind.equals("DP_VARIANT_ORDER"))return switch(field){
			case "evidence"->"SYNTHETIC_SELECTOR_FIXTURE";case "key"->hopRole(d,item).literalKey();case "seed"->"-1";
			case "rank0Cost","rank1Cost","cp","fed"->Double.toHexString(plan.getCumulativeCost());
			case "equal"->Boolean.toString(d.enumeratedPlans().size()>1&&d.enumeratedPlans().stream()
				.mapToLong(p->Double.doubleToLongBits(p.getCumulativeCost())).distinct().count()==1);
			case "selectedInsertionOrdinal"->Integer.toString(d.enumeratedPlans().indexOf(plan));
			case "selectedChildOutput"->plan.getChildFedPlans().get(0).getRight().name();case "selectedOutput"->plan.getFedOutType().name();
			case "selectedExec"->String.valueOf(plan.getExecType());case "runtimeOutputConstraint"->runtimeOutputConstraint(d);
			default->throw notLive(item);};
		throw notLive(item);
	}

	private static String deriveMinst(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.MinstGraphInput m,
		R4CostAdapterBridge.TypedEvidence item,Hop hop) {
		String kind=item.coordinate().rowKind(),field=item.coordinate().fieldName();
		if(kind.equals("MINST_CUT_EDGE")){var edge=cutEdge(expected,m,item);return switch(field){case "evidence"->"EXACT_PRIVATE_REPLAY";case "from"->edge.fromAlias();case "to"->edge.toAlias();
			case "capacity"->Double.toHexString(Double.longBitsToDouble(edge.capacityBits()));default->throw notLive(item);};}
		if(kind.equals("MINST_CUT_OBJECTIVE")){var cert=m.cutCertificate();return switch(field){case "evidence"->"EXACT_PRIVATE_REPLAY";case "seed"->Long.toString(cert.seed());
			case "capacity"->Double.toHexString(Double.longBitsToDouble(cert.capacityBits()));case "reverseCapacity"->Double.toHexString(Double.longBitsToDouble(cert.reverseCapacityBits()));
			case "sourceCount"->Integer.toString(cert.sourceCount());case "orderControl"->cert.orderControl();case "tieLimit"->cert.tieLimit();default->throw notLive(item);};}
		if(kind.equals("MINST_CAPS_REPAIR")||kind.equals("MINST_CAPABILITY_GATE")||kind.equals("MINST_FINAL_STATE"))return switch(field){
			case "evidence"->"EXACT_PRIVATE_REPLAY";case "seed"->Long.toString(repair(expected,m,item).seed());case "key"->hopRole(m,item).compiledKey().normalizedSignature();
			case "rawExec"->repair(expected,m,item).rawExec();case "finalExec"->repair(expected,m,item).finalExec();case "rawOutput"->repair(expected,m,item).rawOutput();case "finalOutput"->repair(expected,m,item).finalOutput();
			case "caps","capsBefore"->caps(repair(expected,m,item));case "capabilityGateApplied","fullPathParity"->Boolean.toString(repair(expected,m,item).role()==item.role());
			case "concreteAnchor"->Boolean.toString(repair(expected,m,item).concreteAnchor());case "repair","reason"->repair(expected,m,item).reason();case "fType"->repair(expected,m,item).fType();default->throw notLive(item);};
		if(kind.equals("REGISTRY_LOCAL_MATERIALIZE")||kind.equals("REGISTRY_FOUT_MATERIALIZE")||kind.equals("REGISTRY_REFED"))return deriveRegistry(m,item,field);
		throw notLive(item);
	}
	private static CampaignBFrozenCostFixtureBridge.CutEdgeReceipt cutEdge(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.MinstGraphInput m,
		R4CostAdapterBridge.TypedEvidence item) {
		int ordinal=rowOrdinal(expected,m,item,"MINST_CUT_EDGE",m.cutCertificate().edges().size());
		return m.cutCertificate().edges().get(ordinal);
	}
	private static CampaignBFrozenCostFixtureBridge.RepairCertificate repair(CampaignBLiteralAuthority.Expected expected,
		CampaignBFrozenCostFixtureBridge.MinstGraphInput m,R4CostAdapterBridge.TypedEvidence item){return m.repairCertificates().get(rowOrdinal(expected,m,item,item.coordinate().rowKind(),m.repairCertificates().size()));}
	private static int rowOrdinal(CampaignBLiteralAuthority.Expected expected,CampaignBFrozenCostFixtureBridge.MinstGraphInput m,R4CostAdapterBridge.TypedEvidence item,
		String kind,int size) {
		var rows=expected.rows().stream().filter(r->r.kind().equals(kind)).toList();
		int ordinal=java.util.stream.IntStream.range(0,rows.size()).filter(i->rows.get(i).digest().equals(item.coordinate().rowDigest())).findFirst().orElse(-1);
		if(ordinal<0||ordinal>=size)throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+m.fixtureId()+"|rowOrdinal="+ordinal);
		return ordinal;
	}
	private static String caps(CampaignBFrozenCostFixtureBridge.RepairCertificate r){return "CP_LOUT="+r.cpLout()+"|CP_FOUT="+r.cpFout()+"|FED_LOUT="+r.fedLout()+"|FED_FOUT="+r.fedFout();}
	private static String deriveRegistry(CampaignBFrozenCostFixtureBridge.MinstGraphInput input,R4CostAdapterBridge.TypedEvidence item,String field){
		if(item.receipt() instanceof org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput.ObligationReceipt o)
			return switch(field){case "consumers"->stableAliases(input,o.consumerHopIds()).toString();case "consumerCount"->Integer.toString(o.consumerHopIds().size());default->throw notLive(item);};
		var registry=input.registryCertificates().stream().filter(r->r.spec()==item.receipt()).findFirst()
			.orElseThrow(()->new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|registryDescriptor"));
		Object receipt=registry.spec();
		if(receipt instanceof org.apache.sysds.lops.compile.FederatedRefedRegistry.AnchorSpec s)return switch(field){
			case "evidence"->"EXACT_PRIVATE_REPLAY";case "producer"->registry.producer().compiledKey().normalizedSignature();case "scope"->Long.toString(registry.scope());
			case "anchorHop"->stableAlias(input,s.getAnchorHopId());case "anchorKey"->normalizeAnchorKey(input,s.getAnchorKey());case "source"->registry.source();default->throw notLive(item);};
		if(receipt instanceof org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry.MaterializeSpec s)return switch(field){
			case "evidence"->"EXACT_PRIVATE_REPLAY";case "producer"->registry.producer().compiledKey().normalizedSignature();case "scope"->Long.toString(registry.scope());case "fType"->String.valueOf(s.getFTypeHint());
			case "anchorLabel"->String.valueOf(s.getAnchorLabel());case "anchorKey"->normalizeAnchorKey(input,s.getAnchorKey());case "source"->registry.source();default->throw notLive(item);};
		if(receipt instanceof org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.LocalMaterializeSpec s)return switch(field){
			case "evidence"->"EXACT_PRIVATE_REPLAY";case "producer"->registry.producer().compiledKey().normalizedSignature();case "consumers"->stableAliases(input,s.getConsumerHopIds()).toString();case "scope"->Long.toString(registry.scope());
			case "consumerCount"->Integer.toString(s.getConsumerHopIds().size());case "fType"->String.valueOf(s.getFTypeHint());case "reason"->String.valueOf(s.getReason());case "source"->registry.source();default->throw notLive(item);};
		throw notLive(item);
	}
	private static String stableAlias(CampaignBFrozenCostFixtureBridge.MinstGraphInput input,long hopId){if(hopId==-1L)return "SCOPE_ANCHOR";return input.aliases().stream().filter(a->a.producerHopId()==hopId).findFirst()
		.map(a->a.compiledKey().normalizedSignature()).orElseGet(()->input.analysis().occurrences().stream().filter(o->o.hop().getHopID()==hopId).findFirst()
			.map(o->o.key().normalizedSignature()).orElseThrow(()->new AssertionError("R4_STABLE_REGISTRY_ALIAS|fixture="+input.fixtureId()+"|unmappedHop")));}
	private static List<String> stableAliases(CampaignBFrozenCostFixtureBridge.MinstGraphInput input,List<Long> hopIds){return hopIds.stream().map(id->stableAlias(input,id)).sorted().toList();}
	private static String normalizeAnchorKey(CampaignBFrozenCostFixtureBridge.MinstGraphInput input,String key){String out=String.valueOf(key);for(var a:input.aliases())out=out.replace(Long.toString(a.producerHopId()),a.compiledKey().normalizedSignature());return out;}

	static Map<String,String> retainedMinstFields(CampaignBFrozenCostFixtureBridge.FullPathInput input,
		R4CostAdapterBridge.Selection selection) {
		if(!(input.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedMinstReceipt retained))
			throw new AssertionError("R4_MINST_FULL_RECEIPT_TYPE|"+input.fixtureId());
		var snapshot=retained.retained();var selected=snapshot.selection();
		if(selection.input()!=input||snapshot.analysis()!=input.analysis()||snapshot.input().analysis()!=input.analysis()
			||selected.analysis()!=input.analysis()||selection.analysis()!=input.analysis()
			||selection.producer()!=selected.producer()||input.producer()!=selected.producer()
			||selection.selectedReceipt()!=snapshot.input()
			||!sameObjects(selection.orderedReceipts(),selected.selectedReceipts())
			||!sameObjects(selection.obligationReceipts(),selected.selectedObligations())
			||selection.objectiveCostBits()!=selected.cutObjectiveBits()
			||!selection.analysisFingerprint().equals(selected.analysisFingerprint()))
			throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|fixture="+input.fixtureId()+"|minstFullFields");
		String facts=retainedMinstSemanticFacts(snapshot.analysis(),selected);
		if(!snapshot.semanticFacts().equals(facts))
			throw new AssertionError("R4_MINST_FULL_STRUCTURAL_FACT_DRIFT|"+input.fixtureId());
		return Map.of("evidence","ACTUAL_RETAINED","seed",Long.toString(snapshot.seed()),
			"fixture",input.fixtureId(),"selectedStates",snapshot.selectedStates().toString(),"semanticFacts",facts);
	}

	static String retainedMinstSemanticFacts(PlacementAnalysis analysis, MinStPlacementAdapter.Selection selection) {
		if(analysis != selection.analysis())
			throw new AssertionError("R4_MINST_RETAINED_OWNER");
		long none = selection.selectedReceipts().stream()
			.filter(receipt -> receipt.execType() == null
				&& receipt.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE)
			.count();
		boolean allLocal = selection.selectedReceipts().stream().allMatch(receipt ->
			receipt.execType() == null
				&& receipt.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE
				|| receipt.execType() == org.apache.sysds.common.Types.ExecType.CP
					&& receipt.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT);
		boolean contextual = analysis.graph().nodes().stream().anyMatch(node -> node.kind()
			== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_BODY_NON_EMITTED);
		if(contextual)
			return "classification=ACTUAL_CONTEXTUAL_NONE,noneNodeCount=" + none
				+ ",nodeKind=FUNCTION_BODY_NON_EMITTED,emittedWork=false,caps=NONE,reason=NON_EMITTED_FUNCTION_BODY_CONTEXT";
		boolean recompile = analysis.graph().nodes().stream().flatMap(node -> node.exclusions().stream()).anyMatch(
			exclusion -> exclusion.reasonCode()
				== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode.RECOMPILE_CP_FOUT);
		if(recompile)
			return "classification=ACTUAL_ALL_LOCAL,recompileCpFout=UNSUPPORTED,reason=RECOMPILE_CP_FOUT_FORBIDDEN";
		boolean loop = analysis.graph().nodes().stream().anyMatch(node -> node.kind()
			== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.LOOP_PHI);
		if(loop)
			return "classification=ACTUAL_ALL_LOCAL_LOOP,allCpLout=" + allLocal
				+ ",equalCut=NONE,repair=NONE,reason=NO_EQUAL_CUT_CLAIM";
		boolean branch = analysis.graph().nodes().stream().anyMatch(node -> node.kind()
			== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.BRANCH_JOIN);
		if(branch)
			return "classification=ACTUAL_ALL_LOCAL,allCpLout=" + allLocal;
		return noTrtwFacts(allLocal);
	}
	private static String noTrtwFacts(boolean allLocal) {
		return "classification=ACTUAL_ALL_LOCAL_NO_TRTW_RELATION,allCpLout=" + allLocal
			+ ",registry=NONE,reason=COMPILED_FIXTURE_HAS_NO_TRTW_SELECTED_RELATION";
	}

	private static String deriveFull(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,
		R4CostAdapterBridge.TypedEvidence item,Hop hop) {
		String kind=item.coordinate().rowKind(),field=item.coordinate().fieldName();
		if(input instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f
			&&f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedMinstReceipt) {
			if(!kind.equals("MINST_FULL_OFFLINE_SELECTION"))throw notLive(item);
			String value=retainedMinstFields(f,R4CostAdapterBridge.select(f)).get(field);
			if(value==null)throw notLive(item);
			return value;
		}
		if(input instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f && f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt retained){var cert=retained.retained();
			return switch(field){case "evidence"->"ACTUAL_RETAINED";case "seed"->Long.toString(cert.seed());case "fixture"->cert.fixture();
				case "rootObjective"->org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.LegacyDpOfflineSelectedCapture.formatObservedHex(cert.rootObjective());
				case "observedFloatNormalization"->cert.floatNormalization();case "observedFloatTolerance"->cert.floatTolerance();case "rootChildren"->cert.rootChildren().toString();
				case "decisionCount"->Integer.toString(cert.decisionCount());case "rewrittenCount"->Integer.toString(cert.rewrittenCount());case "conflictCount"->Integer.toString(cert.conflictCount());
				case "selectedStates"->cert.selectedStates().toString();case "selectedPlans"->cert.selectedPlans().toString();case "semanticFacts"->cert.semanticFacts();
				case "registrySnapshots"->cert.registrySnapshots().toString();default->throw notLive(item);};}
		var cert=((CampaignBFrozenCostFixtureBridge.ExistingCertificateReceipt)
			((CampaignBFrozenCostFixtureBridge.FullPathInput)input).certificate()).certificate();
		if(kind.equals("DP_FULL_OFFLINE_SELECTION"))return switch(field){case "evidence"->"ACTUAL_RETAINED";case "seed"->Long.toString(cert.seed());case "fixture"->cert.fixtureId();
			case "rootObjective"->Double.toHexString(Double.longBitsToDouble(cert.rootObjectiveBits()));case "observedFloatNormalization"->cert.floatNormalization();case "observedFloatTolerance"->cert.floatTolerance();
			case "rootChildren"->Integer.toString(cert.rootChildren());case "decisionCount"->Integer.toString(cert.decisionCount());case "rewrittenCount"->Integer.toString(cert.rewrittenCount());
			case "conflictCount"->Integer.toString(cert.conflictCount());case "selectedStates"->cert.selectedStates().toString();case "selectedPlans"->cert.selectedPlans().toString();case "semanticFacts"->cert.semanticFacts();
			case "registrySnapshots"->cert.registrySnapshots().toString();default->throw notLive(item);};
		throw notLive(item);
	}

	private static String stableStates(CampaignBFrozenCostFixtureBridge.CostSelectionInput input){return input.aliases().stream().map(a->a.compiledKey().normalizedSignature()+"="+
		input.analysis().hop(a.compiledKey()).orElseThrow().getForcedExecType()+"/"+input.analysis().hop(a.compiledKey()).orElseThrow().getFederatedOutput()).sorted().toList().toString();}
	private static AssertionError notLive(R4CostAdapterBridge.TypedEvidence item){return new AssertionError("R4_TYPED_FIELD_NOT_LIVE|"+item.coordinate());}

	private static boolean sameObjects(List<?> a,List<?> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;}
	private R4CostTypedExtractor() { }
}
