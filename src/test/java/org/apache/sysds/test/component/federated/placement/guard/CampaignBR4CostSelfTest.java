/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.LegacyMinstOfflineSelectedCapture;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.R4ExactPrivateCostDpFixtures;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.R4ExactPrivateCostMinstFixtures;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.junit.Test;

/** Adapter-independent exact-recipe, alias, parser and anti-echo gates. */
public class CampaignBR4CostSelfTest {
	@Test public void allTwentyTwoGroupsHaveExactDeterministicRecipes()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		Assert.assertEquals(22,groups.size());Set<String>digests=new LinkedHashSet<>();int dual=0,nativeRecipes=0;
		for(var e:groups){CampaignBFrozenCostFixtureBridge.Fixture a,b;try{a=CampaignBFrozenCostFixtureBridge.fresh(e);b=CampaignBFrozenCostFixtureBridge.fresh(e);}
			catch(Exception x){throw new AssertionError("R4_RECIPE_BUILD|"+e.planner()+"|"+e.fixture(),x);}
			Assert.assertEquals(a.planner()+"|"+a.id(),a.digest(),b.digest());Assert.assertTrue(digests.add(a.planner()+'|'+a.id()+'|'+a.digest()));
			if(a.id().equals("C2-DP-04-ANCHOR-CONTRAST")){dual++;Assert.assertEquals(Set.of("B-01"),a.arms().stream().map(CampaignBFrozenCostFixtureBridge.Arm::bFixture).collect(java.util.stream.Collectors.toSet()));
				Assert.assertEquals(2,a.inputs().size());Assert.assertTrue(a.inputs().stream().allMatch(x->x instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput));
				var concrete=(CampaignBFrozenCostFixtureBridge.DpMemoInput)a.inputs().get(0);var missing=(CampaignBFrozenCostFixtureBridge.DpMemoInput)a.inputs().get(1);
				Assert.assertSame(concrete.analysis(),missing.analysis());Assert.assertNotSame(concrete.memo(),missing.memo());Assert.assertSame(concrete.root(),missing.root());
				Assert.assertEquals(concrete.aliases().get(0).compiledKey(),missing.aliases().get(0).compiledKey());Assert.assertNotEquals(concrete.inputFingerprint(),missing.inputFingerprint());
				Assert.assertSame(concrete.root(),concrete.analysis().hop(concrete.aliases().get(0).compiledKey()).orElseThrow());
				Assert.assertSame(missing.root(),missing.analysis().hop(missing.aliases().get(0).compiledKey()).orElseThrow());
				Assert.assertEquals("FOUT",concrete.selectedPlan().getFedOutType().name());Assert.assertEquals("LOUT",missing.selectedPlan().getFedOutType().name());}
			if(a.input() instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput||a.input() instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput)nativeRecipes++;
		}
		Assert.assertEquals(1,dual);Assert.assertEquals(11,nativeRecipes);
	}
	@Test public void allowedLabelsAndCorrectGoldenValuesCannotReplaceProducerIdentity()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		Set<String> corpus=Set.of("C2-DP-01-ROOT-EQUAL-LOUT","C2-DP-03-STABLE-VARIANT","C2-MS-03-SHARED-DOWNLOAD",
			"C2-MS-04-ANCHORED-UPLOAD","C2-DP-06-TRTW-EXACT","C2-X-09-BRANCH-JOIN","C2-X-10-FUNCTION-CALLSITE","C2-X-11-CLONE-RECOMPILE");
		boolean relocation=false,obligation=false,anchor=false,registry=false,objective=false,tie=false,trtw=false,boundary=false,clone=false,structural=false;
		var kinds=R4CostAdapterBridge.EvidenceKind.values();int ordinal=0;
		for(var expected:groups.stream().filter(e->corpus.contains(e.fixture())).toList()){
			var input=CampaignBFrozenCostFixtureBridge.fresh(expected).input();var role=input.aliases().get(0);
			var echo=new CampaignBLiteralAuthority.TypedPlan(expected.planner(),expected.fixture(),expected.assignments(),expected.relocations(),
				expected.obligations(),expected.anchors(),expected.registries(),expected.objective(),expected.ties(),expected.trtw(),
				expected.boundaries(),expected.clones(),expected.structural(),expected.facts(),expected.fingerprint());
			Assert.assertEquals(expected.fingerprint(),echo.fingerprint());Assert.assertTrue(CampaignBLiteralAuthority.compare(expected,echo).isEmpty());
			relocation|=!echo.relocations().isEmpty();obligation|=!echo.obligations().isEmpty();anchor|=!echo.anchors().isEmpty();registry|=!echo.registries().isEmpty();
			objective|=!echo.objective().isEmpty();tie|=!echo.ties().isEmpty();trtw|=!echo.trtw().isEmpty();boundary|=!echo.boundaries().isEmpty();clone|=!echo.clones().isEmpty();structural|=!echo.structural().isEmpty();
			List<R4CostAdapterBridge.TypedEvidence> foreign=new java.util.ArrayList<>(),foreignReceipt=new java.util.ArrayList<>();
			for(var row:expected.rows())for(String field:row.fields().keySet()){
				var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);var kind=kinds[ordinal++%kinds.length];
				foreign.add(new R4CostAdapterBridge.TypedEvidence(kind,new Object(),role,coordinate,R4CostAdapterBridge.ReceiptField.ANALYSIS,new Object()));
				var req=R4CostTypedExtractor.requirement(coordinate);
				foreignReceipt.add(new R4CostAdapterBridge.TypedEvidence(req.kind(),input.producer(),role,coordinate,req.receiptField(),new Object()));
			}
			CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.rejectGoldenEcho(expected,input,echo,List.copyOf(foreign)));
			CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.rejectGoldenEcho(expected,input,echo,List.copyOf(foreignReceipt)));
			CampaignBLiteralAuthority.expect("R4_TYPED_EVIDENCE_MISSING",()->R4CostTypedExtractor.rejectGoldenEcho(expected,input,echo,List.of()));
		}
		Assert.assertTrue(relocation&&obligation&&anchor&&registry&&objective&&tie&&trtw&&boundary&&clone&&structural);
		var dp04=groups.stream().filter(e->e.fixture().equals("C2-DP-04-ANCHOR-CONTRAST")).findFirst().orElseThrow();
		CampaignBLiteralAuthority.expect("R4_DP04_ARM_BIJECTION",()->{try{R4CostTypedExtractor.extract(dp04,List.of());}catch(Exception e){throw new AssertionError(e);}});
	}
	@Test public void everyDeclaredRowFieldHasAnIdentityBoundCorruption()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		int checked=0;var kinds=R4CostAdapterBridge.EvidenceKind.values();
		for(var expected:groups){
			var input=CampaignBFrozenCostFixtureBridge.fresh(expected).input();
			for(var row:expected.rows())for(String field:row.fields().keySet()){
				var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);
				var corrupt=input instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph?
					new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,new Object(),
						new CampaignBFrozenCostFixtureBridge.GraphExclusionRole(graph.receipt()),coordinate,
						R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,graph.receipt()):
					new R4CostAdapterBridge.TypedEvidence(kinds[checked%kinds.length],new Object(),input.aliases().get(0),coordinate,
						R4CostAdapterBridge.ReceiptField.ANALYSIS,new Object());
				CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(expected,input,List.of(corrupt)));checked++;
			}
		}
		Assert.assertTrue("field-level liveness must exceed the 13 aggregate surfaces",checked>13);
	}
	@Test public void derivedFinalStateControlsAssignmentWithoutManifestValueAuthority()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		var expected=groups.stream().filter(e->e.rows().stream().anyMatch(r->r.fields().containsKey("finalExec"))).findFirst().orElseThrow();
		var exact=new java.util.LinkedHashMap<>(expected.facts());
		var baseline=CampaignBLiteralAuthority.fromDerived(expected,exact);
		Assert.assertEquals(expected.assignments(),baseline.assignments());
		var row=expected.rows().stream().filter(r->r.fields().containsKey("finalExec")).findFirst().orElseThrow();
		String key=row.digest()+".finalExec",old=exact.get(key);exact.put(key,old.equals("CP")?"FED":"CP");
		var corrupt=CampaignBLiteralAuthority.fromDerived(expected,exact);
		Assert.assertNotEquals(baseline.assignments(),corrupt.assignments());
		Assert.assertTrue(CampaignBLiteralAuthority.compare(expected,corrupt).stream().anyMatch(d->d.surface()==CampaignBLiteralAuthority.Surface.ASSIGNMENT));
	}
	@Test public void sameProducerCannotAuthorizeAFieldWithAnotherOwnedReceipt()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		var dpExpected=groups.stream().filter(e->e.fixture().equals("C2-DP-01-ROOT-EQUAL-LOUT")).findFirst().orElseThrow();
		var dp=(CampaignBFrozenCostFixtureBridge.DpMemoInput)CampaignBFrozenCostFixtureBridge.fresh(dpExpected).input();
		var dpRow=dpExpected.rows().stream().filter(r->r.fields().containsKey("objective")).findFirst().orElseThrow();
		var dpCoordinate=new R4CostAdapterBridge.FieldCoordinate(dpRow.digest(),dpRow.kind(),"objective");var dpReq=R4CostTypedExtractor.requirement(dpCoordinate);
		var wrongDp=new R4CostAdapterBridge.TypedEvidence(dpReq.kind(),dp.producer(),dp.aliases().get(0),dpCoordinate,dpReq.receiptField(),dp.memo());
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(dpExpected,dp,List.of(wrongDp)));
		var orderExpected=groups.stream().filter(e->e.fixture().equals("C2-DP-03-STABLE-VARIANT")).findFirst().orElseThrow();var order=(CampaignBFrozenCostFixtureBridge.DpMemoInput)CampaignBFrozenCostFixtureBridge.fresh(orderExpected).input();
		var orderRow=orderExpected.rows().stream().filter(r->r.fields().containsKey("rank0Cost")).findFirst().orElseThrow();var orderCoordinate=new R4CostAdapterBridge.FieldCoordinate(orderRow.digest(),orderRow.kind(),"rank0Cost");var orderReq=R4CostTypedExtractor.requirement(orderCoordinate);
		var wrongEnumerated=new R4CostAdapterBridge.TypedEvidence(orderReq.kind(),order.producer(),order.aliases().get(0),orderCoordinate,orderReq.receiptField(),order.enumeratedPlans().get(order.enumeratedPlans().size()-1));
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(orderExpected,order,List.of(wrongEnumerated)));

		var msExpected=groups.stream().filter(e->e.fixture().equals("C2-MS-04-ANCHORED-UPLOAD")).findFirst().orElseThrow();
		var ms=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(msExpected).input();
		var msRow=msExpected.rows().stream().filter(r->r.kind().equals("REGISTRY_REFED")&&r.fields().containsKey("anchorKey")).findFirst().orElseThrow();
		var msCoordinate=new R4CostAdapterBridge.FieldCoordinate(msRow.digest(),msRow.kind(),"anchorKey");var msReq=R4CostTypedExtractor.requirement(msCoordinate);
		var wrongMs=new R4CostAdapterBridge.TypedEvidence(msReq.kind(),ms.producer(),ms.aliases().get(0),msCoordinate,msReq.receiptField(),ms.graph());
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(msExpected,ms,List.of(wrongMs)));
		var localExpected=groups.stream().filter(e->e.fixture().equals("C2-MS-03-SHARED-DOWNLOAD")).findFirst().orElseThrow();var local=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(localExpected).input();
		var localRow=localExpected.rows().get(0);var consumers=new R4CostAdapterBridge.FieldCoordinate(localRow.digest(),localRow.kind(),"consumers");var consumersReq=R4CostTypedExtractor.requirement(consumers);
		var wrongObligation=new R4CostAdapterBridge.TypedEvidence(consumersReq.kind(),local.producer(),local.aliases().get(0),consumers,consumersReq.receiptField(),local.registryReceipts().get(0));
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(localExpected,local,List.of(wrongObligation)));
		var spec=(org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.LocalMaterializeSpec)local.registryReceipts().get(0);
		var copied=new org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.LocalMaterializeSpec(spec.getConsumerHopIds(),spec.getFTypeHint(),spec.getReason());
		var reason=new R4CostAdapterBridge.FieldCoordinate(localRow.digest(),localRow.kind(),"reason");var reasonReq=R4CostTypedExtractor.requirement(reason);
		var originalCert=local.registryCertificates().get(0);var copiedCert=new CampaignBFrozenCostFixtureBridge.RegistryCertificate(copied,originalCert.scope(),originalCert.source(),originalCert.producer());
		var copiedRegistry=new R4CostAdapterBridge.TypedEvidence(reasonReq.kind(),local.producer(),originalCert.producer(),reason,reasonReq.receiptField(),copiedCert);
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(localExpected,local,List.of(copiedRegistry)));
		var cutExpected=groups.stream().filter(e->e.fixture().equals("C2-MS-01-EQUAL-CUT")).findFirst().orElseThrow();var cutInput=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(cutExpected).input();
		var cutRow=cutExpected.rows().stream().filter(r->r.kind().equals("MINST_CUT_OBJECTIVE")).findFirst().orElseThrow();var c=new R4CostAdapterBridge.FieldCoordinate(cutRow.digest(),cutRow.kind(),"capacity");var q=R4CostTypedExtractor.requirement(c);
		var wrongCut=new R4CostAdapterBridge.TypedEvidence(q.kind(),cutInput.producer(),cutInput.aliases().get(0),c,q.receiptField(),cutInput.graph());
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(cutExpected,cutInput,List.of(wrongCut)));
		var fullExpected=groups.stream().filter(e->e.fixture().equals("C2-X-09-BRANCH-JOIN")&&e.planner().equals("DP")).findFirst().orElseThrow();
		var full=(CampaignBFrozenCostFixtureBridge.FullPathInput)CampaignBFrozenCostFixtureBridge.fresh(fullExpected).input();var fullRow=fullExpected.rows().get(0);
		var fullCoordinate=new R4CostAdapterBridge.FieldCoordinate(fullRow.digest(),fullRow.kind(),fullRow.fields().keySet().iterator().next());var fullReq=R4CostTypedExtractor.requirement(fullCoordinate);
		var wrongFull=new R4CostAdapterBridge.TypedEvidence(fullReq.kind(),full.producer(),full.aliases().get(0),fullCoordinate,fullReq.receiptField(),full.analysis());
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(fullExpected,full,List.of(wrongFull)));
	}
	@Test public void everyCoordinateHasOneExactReceiptFamilyAndDp04Arm()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		for(var expected:groups){var fixture=CampaignBFrozenCostFixtureBridge.fresh(expected);List<R4CostAdapterBridge.Selection> selections=new java.util.ArrayList<>();
			if(fixture.input() instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f
				&&f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt){
				var positive=R4CostAdapterBridge.select(f); Assert.assertFalse(R4CostTypedExtractor.extract(expected,positive).facts().isEmpty());
				continue;
			}
			for(var input:fixture.inputs()){List<R4CostAdapterBridge.TypedEvidence> evidence=new java.util.ArrayList<>();
				for(var row:expected.rows())for(String field:row.fields().keySet()){
					var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);var req=R4CostTypedExtractor.requirement(coordinate);
					if(req.dp04Arm()!=null&&!input.fixtureId().endsWith(':'+req.dp04Arm()))continue;
					int ordinal=java.util.stream.IntStream.range(0,expected.rows().size()).filter(i->expected.rows().get(i).kind().equals(row.kind())&&expected.rows().get(i).digest().equals(row.digest())).findFirst().orElseThrow();
					ordinal=(int)expected.rows().subList(0,ordinal).stream().filter(r->r.kind().equals(row.kind())).count();
					Object receipt=receipt(input,req.receiptField(),row.kind(),field,ordinal);var role=receipt instanceof org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.GraphExclusionReceipt r?
						new CampaignBFrozenCostFixtureBridge.GraphExclusionRole(r):
						input instanceof CampaignBFrozenCostFixtureBridge.FullPathInput&&receipt==input.analysis()?new CampaignBFrozenCostFixtureBridge.AnalysisReceiptRole(input.analysis()):
						receipt instanceof org.apache.sysds.hops.Hop h?input.aliases().stream().filter(a->a.producerHopId()==h.getHopID()).findFirst().orElseThrow():
						input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m&&m.registryReceipts().stream().anyMatch(x->x==receipt)?registryCertificate(m,row.kind()).producer():
						receipt instanceof org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput.ObligationReceipt o?
							input.aliases().stream().filter(a->a.producerHopId()==o.childHopId()).findFirst().orElseThrow():input.aliases().get(0);
					evidence.add(new R4CostAdapterBridge.TypedEvidence(req.kind(),input.producer(),role,coordinate,req.receiptField(),receipt));
				}
				selections.add(selection(input,List.copyOf(evidence)));
			}
			try{var actual=R4CostTypedExtractor.extract(expected,List.copyOf(selections));Assert.assertEquals(expected.facts().size(),actual.facts().size());}
			catch(RuntimeException x){throw new AssertionError("R4_POSITIVE_COORDINATE|"+expected.fixture(),x);}
		}
	}
	@Test public void allDpCoordinatesAreTestBuiltFromExactLiveReceipts()throws Exception{
		for(var expected:CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.planner().equals("DP")).toList()){
			var fixture=CampaignBFrozenCostFixtureBridge.fresh(expected);
			if(fixture.input() instanceof CampaignBFrozenCostFixtureBridge.FullPathInput f
				&&f.certificate() instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt){
				var positive=R4CostAdapterBridge.select(f); Assert.assertFalse(R4CostTypedExtractor.testEvidence(expected,positive).isEmpty());
				continue;
			}
			List<R4CostAdapterBridge.Selection> selections=new java.util.ArrayList<>();
			for(var input:fixture.inputs()){
				var live=selection(input,List.of());
				var evidence=R4CostTypedExtractor.testEvidence(expected,live);
				Assert.assertFalse(evidence.isEmpty());
				Assert.assertTrue(evidence.stream().allMatch(e->e.producer()==live.producer()));
				selections.add(live);
			}
			Assert.assertEquals(expected.facts(),R4CostTypedExtractor.extract(expected,List.copyOf(selections)).facts());
		}
	}
	@Test public void dpGraphExclusionReceiptsPreserveExactIdentityAndDoNotMutate()throws Exception{
		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-DP-08-UNKNOWN-METADATA")&&e.planner().equals("DP")).findFirst().orElseThrow();
		var input=(CampaignBFrozenCostFixtureBridge.GraphExclusionInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		String before=input.analysis().graph().normalizedSignature();
		var selected=R4CostAdapterBridge.select(input);
		Assert.assertSame(input.analysis(),selected.analysis());Assert.assertSame(input.analysis(),selected.producer());
		Assert.assertEquals(input.result().certificateReceipts().size(),selected.certificateReceipts().size());
		for(int i=0;i<selected.certificateReceipts().size();i++)
			Assert.assertSame(input.result().certificateReceipts().get(i),selected.certificateReceipts().get(i));
		Assert.assertTrue(selected.certificateReceipts().stream().anyMatch(receipt->receipt==input.receipt()));
		Assert.assertEquals(before,input.analysis().graph().normalizedSignature());
		long reasonOnly=input.result().certificateReceipts().stream().filter(receipt->receipt.exclusion().reasonCode()==
			org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode.UNKNOWN_METADATA).count();
		Assert.assertTrue(reasonOnly>1);
		Assert.assertEquals(1,input.result().certificateReceipts().stream().filter(receipt->
			receipt.exclusion().reasonCode()==org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode.UNKNOWN_METADATA
			&&receipt.exclusion().state().execType()==org.apache.sysds.common.Types.ExecType.FED
			&&receipt.exclusion().state().shapeDependent()).count());
		Assert.assertTrue(input.analysis().graph().nodes().stream().flatMap(node->node.legalAlternatives().stream()).anyMatch(state->
			state.execType()==org.apache.sysds.common.Types.ExecType.FED&&!state.shapeDependent()));
	}
	@Test public void copiedGraphExclusionReceiptComponentsAreRejected()throws Exception{
		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-DP-08-UNKNOWN-METADATA")&&e.planner().equals("DP")).findFirst().orElseThrow();
		var input=(CampaignBFrozenCostFixtureBridge.GraphExclusionInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();var exact=input.receipt();
		var copiedOccurrence=new org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection(
			exact.occurrence().key(),exact.occurrence().hop(),exact.occurrence().scopeId(),
			exact.occurrence().normalizedOrdinal(),exact.occurrence().normalizedSignature());
		Assert.assertThrows(IllegalArgumentException.class,()->new org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.GraphExclusionReceipt(
			input.analysis(),copiedOccurrence,exact.node(),exact.exclusion()));
		var copiedNode=new org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node(exact.node().key(),exact.node().kind(),
			exact.node().valueVersion(),exact.node().emittedWork(),exact.node().legalAlternatives(),exact.node().exclusions(),exact.node().anchors());
		Assert.assertThrows(IllegalArgumentException.class,()->new org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.GraphExclusionReceipt(
			input.analysis(),exact.occurrence(),copiedNode,exact.exclusion()));
		var copiedExclusion=new org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion(exact.exclusion().state(),
			exact.exclusion().reasonCode(),exact.exclusion().detail());
		Assert.assertThrows(IllegalArgumentException.class,()->new org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.GraphExclusionReceipt(
			input.analysis(),exact.occurrence(),exact.node(),copiedExclusion));
		var reordered=new java.util.ArrayList<>(input.result().certificateReceipts());java.util.Collections.reverse(reordered);
		Assert.assertThrows(IllegalArgumentException.class,()->new org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.Result(
			input.analysis(),reordered,input.analysis().analysisFingerprint()));
		Assert.assertThrows(AssertionError.class,()->CampaignBFrozenCostFixtureBridge.selectGraphExclusion(List.of()));
		Assert.assertThrows(AssertionError.class,()->CampaignBFrozenCostFixtureBridge.selectGraphExclusion(List.of(exact,exact)));
	}
	@Test public void copiedForeignAndSubstitutedGraphExclusionRolesAreRejected()throws Exception{
		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-DP-08-UNKNOWN-METADATA")&&e.planner().equals("DP")).findFirst().orElseThrow();
		var input=(CampaignBFrozenCostFixtureBridge.GraphExclusionInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();var exact=input.receipt();
		var coordinate=new R4CostAdapterBridge.FieldCoordinate("receipt-test","NEUTRAL_GRAPH_EXCLUSION","reason");
		var exactEvidence=new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,input.producer(),
			new CampaignBFrozenCostFixtureBridge.GraphExclusionRole(exact),coordinate,R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,exact);
		R4CostTypedExtractor.validateGraphExclusionEvidence(input,exactEvidence);
		var copiedReceipt=new org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.GraphExclusionReceipt(
			input.analysis(),exact.occurrence(),exact.node(),exact.exclusion());
		var copied=new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,input.producer(),
			new CampaignBFrozenCostFixtureBridge.GraphExclusionRole(copiedReceipt),coordinate,R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,copiedReceipt);
		Assert.assertThrows(AssertionError.class,()->R4CostTypedExtractor.validateGraphExclusionEvidence(input,copied));
		var foreign=(CampaignBFrozenCostFixtureBridge.GraphExclusionInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		Assert.assertEquals(input.analysis().analysisFingerprint(),foreign.analysis().analysisFingerprint());Assert.assertNotSame(input.analysis(),foreign.analysis());
		var foreignEvidence=new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,foreign.producer(),
			new CampaignBFrozenCostFixtureBridge.GraphExclusionRole(foreign.receipt()),coordinate,R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,foreign.receipt());
		Assert.assertThrows(AssertionError.class,()->R4CostTypedExtractor.validateGraphExclusionEvidence(input,foreignEvidence));
		var retainedExpected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-DP-05-SHARED-DIAMOND")&&e.planner().equals("DP")).findFirst().orElseThrow();
		var retained=(CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt)
			((CampaignBFrozenCostFixtureBridge.FullPathInput)CampaignBFrozenCostFixtureBridge.fresh(retainedExpected).input()).certificate();
		var planRole=new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,input.producer(),
			new CampaignBFrozenCostFixtureBridge.PlanReceiptRole(retained.retained().rootPlan()),coordinate,
			R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,exact);
		Assert.assertThrows(AssertionError.class,()->R4CostTypedExtractor.validateGraphExclusionEvidence(input,planRole));
		var analysisRole=new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,input.producer(),
			new CampaignBFrozenCostFixtureBridge.AnalysisReceiptRole(input.analysis()),coordinate,R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,exact);
		Assert.assertThrows(AssertionError.class,()->R4CostTypedExtractor.validateGraphExclusionEvidence(input,analysisRole));
		var nullRole=new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.GRAPH_EXCLUSION,input.producer(),null,
			coordinate,R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION,exact);
		Assert.assertThrows(AssertionError.class,()->R4CostTypedExtractor.validateGraphExclusionEvidence(input,nullRole));
	}
	@Test public void correctMinstObligationWithWrongChildRoleIsRejected()throws Exception{
		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-MS-03-SHARED-DOWNLOAD")).findFirst().orElseThrow();
		var input=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		var row=expected.rows().stream().filter(r->r.kind().equals("REGISTRY_LOCAL_MATERIALIZE")).findFirst().orElseThrow();
		var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),"consumers");var req=R4CostTypedExtractor.requirement(coordinate);
		var obligation=(org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput.ObligationReceipt)
			receipt(input,req.receiptField(),row.kind(),"consumers",0);
		var wrongRole=input.aliases().stream().filter(a->a.producerHopId()!=obligation.childHopId()).findFirst().orElseThrow();
		var wrong=new R4CostAdapterBridge.TypedEvidence(req.kind(),input.producer(),wrongRole,coordinate,req.receiptField(),obligation);
		CampaignBLiteralAuthority.expect("R4_TYPED_ROLE_ALIAS_MISMATCH",()->R4CostTypedExtractor.derive(expected,input,List.of(wrong)));
	}
	@Test public void duplicateForeignRowKindAndForeignFieldCoordinatesAreRejected()throws Exception{
		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-DP-01-ROOT-EQUAL-LOUT")).findFirst().orElseThrow();var input=(CampaignBFrozenCostFixtureBridge.DpMemoInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		var row=expected.rows().get(0);String field=row.fields().keySet().iterator().next();var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);var req=R4CostTypedExtractor.requirement(coordinate);
		var exact=new R4CostAdapterBridge.TypedEvidence(req.kind(),input.producer(),input.aliases().get(0),coordinate,req.receiptField(),receipt(input,req.receiptField(),row.kind(),field,0));
		CampaignBLiteralAuthority.expect("R4_DUPLICATE_TYPED_FIELD",()->R4CostTypedExtractor.derive(expected,input,List.of(exact,exact)));
		var foreignKind=new R4CostAdapterBridge.FieldCoordinate(row.digest(),"FOREIGN_ROW_KIND",field);
		var wrongKind=new R4CostAdapterBridge.TypedEvidence(req.kind(),input.producer(),input.aliases().get(0),foreignKind,req.receiptField(),exact.receipt());
		CampaignBLiteralAuthority.expect("R4_TYPED_FIELD_NOT_LIVE",()->R4CostTypedExtractor.derive(expected,input,List.of(wrongKind)));
		var foreignField=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),"foreignField");
		var wrongField=new R4CostAdapterBridge.TypedEvidence(req.kind(),input.producer(),input.aliases().get(0),foreignField,req.receiptField(),exact.receipt());
		CampaignBLiteralAuthority.expect("R4_TYPED_FIELD_NOT_LIVE",()->R4CostTypedExtractor.derive(expected,input,List.of(wrongField)));
	}
	@Test public void exactDpFixturesRetainEnumeratorAggregateAndRegisteredRootHop()throws Exception{
		for(var fixture:R4ExactPrivateCostDpFixtures.ownerFixtures()){
			Assert.assertSame(fixture.root(),fixture.exactHopCommon().getHopRef());
			Assert.assertSame(fixture.root(),fixture.memo().resolveOriginalHop(fixture.root().getHopID()));
			Assert.assertNotSame(fixture.selectedPlan(),fixture.aggregatePlan());
			var selected=new DpPlacementAdapter().selectExact(fixture.analysis(),fixture.memo(),fixture.aggregatePlan());
			Assert.assertSame(fixture.aggregatePlan(),selected.legacyOptimalPlan());
			Assert.assertEquals(fixture.aggregatePlan().getChildFedPlans().size(),selected.aggregateChildEdges().size());
			for(int i=0;i<selected.aggregateChildEdges().size();i++){
				Assert.assertSame(fixture.aggregatePlan().getChildFedPlans().get(i),selected.aggregateChildEdges().get(i));
				Assert.assertSame(fixture.memo().getFedPlanAfterPrune(selected.aggregateChildEdges().get(i)),selected.selectedRootPlans().get(i));
				Assert.assertSame(fixture.root(),selected.selectedRootHops().get(i));
			}
			Assert.assertEquals(Double.doubleToRawLongBits(fixture.aggregatePlan().getCumulativeCost()),selected.objectiveCostBits());
		}
	}
	@Test public void exactDpOwnerRejectsCopiedRebuiltImmutableAndUnregisteredAggregate()throws Exception{
		for(var fixture:R4ExactPrivateCostDpFixtures.ownerFixtures()){
			var edge=fixture.aggregatePlan().getChildFedPlans().get(0);
			var unregisteredMemo=new org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable();
			for(var output:org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.values()){
				var key=org.apache.commons.lang3.tuple.Pair.of(fixture.root().getHopID(),output);
				var variants=fixture.memo().getFedPlanVariants(key);if(variants!=null)unregisteredMemo.addFedPlanVariants(key.getLeft(),key.getRight(),variants);
			}
			FedPlan copied=new FedPlan(fixture.aggregatePlan().getCumulativeCost(),null,new java.util.ArrayList<>(List.of(edge)));
			var copiedError=Assert.assertThrows(IllegalArgumentException.class,
				()->new DpPlacementAdapter().selectExact(fixture.analysis(),unregisteredMemo,copied));
			Assert.assertEquals("Aggregate edge has no executable Hop association",copiedError.getMessage());
			FedPlan immutable=new FedPlan(fixture.aggregatePlan().getCumulativeCost(),null,List.of(edge));
			var immutableError=Assert.assertThrows(IllegalArgumentException.class,
				()->new DpPlacementAdapter().selectExact(fixture.analysis(),fixture.memo(),immutable));
			Assert.assertEquals("Aggregate is not the enumerator-owned carrier",immutableError.getMessage());
			var unregisteredError=Assert.assertThrows(IllegalArgumentException.class,
				()->new DpPlacementAdapter().selectExact(fixture.analysis(),unregisteredMemo,fixture.aggregatePlan()));
			Assert.assertEquals("Aggregate edge has no executable Hop association",unregisteredError.getMessage());
		}
	}
	@Test public void allExactMinstFixturesOwnCompletePublicSelectedGraphs()throws Exception{
		Map<String,Integer> expected=Map.of("C2-MS-01-EQUAL-CUT",4,"C2-MS-06-STATE-QUARTET",4,
			"C2-MS-02-CAPS-FIXPOINT",1,"C2-MS-03-SHARED-DOWNLOAD",3,
			"C2-MS-04-ANCHORED-UPLOAD",7,"C2-MS-05-MISSING-ANCHOR",3);
		for(var fixture:R4ExactPrivateCostMinstFixtures.all()){
			Assert.assertEquals((int)expected.get(fixture.id()),fixture.analysis().occurrences().size());
			for(var occurrence:fixture.analysis().occurrences()){
				var vertex=fixture.producerGraph().getVertex(occurrence.hop().getHopID());
				Assert.assertNotNull(vertex);Assert.assertSame(occurrence.hop(),vertex.getHopRef());
				Assert.assertNotNull(occurrence.hop().getForcedExecType()!=null?occurrence.hop().getForcedExecType():occurrence.hop().getExecType());
				Assert.assertNotNull(occurrence.hop().getFederatedOutput());
			}
			var bound=LegacyMinstOfflineSelectedCapture.bindLegacyPlacementInput(fixture.analysis(),fixture.producerGraph());
			var selected=new MinStPlacementAdapter().select(fixture.analysis(),bound);
			Assert.assertSame(bound.producerReceipt(),selected.producer());
			Assert.assertEquals(fixture.analysis().occurrences().size(),selected.selectedReceipts().size());
			Assert.assertEquals(bound.obligationReceipts().size(),selected.selectedObligations().size());
			Assert.assertTrue(R4CostAdapterBridge.sameObjects(bound.obligationReceipts(),selected.selectedObligations()));
		}
	}
	@Test public void exactMinstOwnerRejectsMissingForeignStaleAndDescriptorReceipt()throws Exception{
		var fixtures=R4ExactPrivateCostMinstFixtures.all();var owner=fixtures.get(0);
		var bound=LegacyMinstOfflineSelectedCapture.bindLegacyPlacementInput(owner.analysis(),owner.producerGraph());
		var foreign=R4ExactPrivateCostMinstFixtures.all().get(0);
		Assert.assertThrows(IllegalArgumentException.class,()->new MinStPlacementAdapter().select(foreign.analysis(),bound));
		FederatedPlanMinSTGraph missing=new FederatedPlanMinSTGraph();
		var first=owner.analysis().occurrences().get(0).hop();
		var vertex=new FederatedPlanMinSTGraph.Vertex(first,
			org.apache.sysds.hops.fedplanner.FTypes.Privacy.PUBLIC,org.apache.sysds.hops.fedplanner.FTypes.FType.ROW,
			new FederatedPlanMinSTGraph.ExecPlacementCaps());
		vertex.setMetadata(1,1,List.of());vertex.setCost(0,0,0);missing.addVertex(vertex);
		Assert.assertThrows(IllegalArgumentException.class,()->LegacyMinstOfflineSelectedCapture.bindLegacyPlacementInput(owner.analysis(),missing));
		var staleFixture=fixtures.stream().filter(f->f.id().equals("C2-MS-03-SHARED-DOWNLOAD")).findFirst().orElseThrow();
		var stale=LegacyMinstOfflineSelectedCapture.bindLegacyPlacementInput(staleFixture.analysis(),staleFixture.producerGraph());
		var staleHop=staleFixture.analysis().occurrences().get(0).hop();
		var originalOutput=staleHop.getFederatedOutput();
		staleHop.setFederatedOutput(originalOutput==org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE?
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT:
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE);
		try{Assert.assertThrows(IllegalArgumentException.class,()->new MinStPlacementAdapter().select(staleFixture.analysis(),stale));}
		finally{staleHop.setFederatedOutput(originalOutput);}
		for(var fixture:fixtures)for(Object descriptor:fixture.registryObjects())
			Assert.assertFalse(fixture.selectedObligationObjects().stream().anyMatch(receipt->receipt==descriptor));
	}
	@Test public void ownerFixtureSelectionIsRepeatableAndMutationFree()throws Exception{
		for(var fixture:R4ExactPrivateCostDpFixtures.ownerFixtures()){
			var edges=List.copyOf(fixture.aggregatePlan().getChildFedPlans());long bits=Double.doubleToRawLongBits(fixture.aggregatePlan().getCumulativeCost());
			var first=new DpPlacementAdapter().selectExact(fixture.analysis(),fixture.memo(),fixture.aggregatePlan());
			var second=new DpPlacementAdapter().selectExact(fixture.analysis(),fixture.memo(),fixture.aggregatePlan());
			Assert.assertEquals(first.objectiveCostBits(),second.objectiveCostBits());Assert.assertEquals(edges,fixture.aggregatePlan().getChildFedPlans());
			Assert.assertEquals(bits,Double.doubleToRawLongBits(fixture.aggregatePlan().getCumulativeCost()));
		}
		for(var fixture:R4ExactPrivateCostMinstFixtures.all()){
			String before=fixture.analysis().occurrences().stream().map(o->o.key().normalizedSignature()+":"+
				(o.hop().getForcedExecType()!=null?o.hop().getForcedExecType():o.hop().getExecType())+":"+o.hop().getFederatedOutput()).toList()+"|"+
				fixture.producerGraph().getSelectedCutObjectiveBits()+"|"+fixture.producerGraph().getSelectedSourcePartitionNodeIds();
			var bound=LegacyMinstOfflineSelectedCapture.bindLegacyPlacementInput(fixture.analysis(),fixture.producerGraph());
			var first=new MinStPlacementAdapter().select(fixture.analysis(),bound);var second=new MinStPlacementAdapter().select(fixture.analysis(),bound);
			Assert.assertEquals(first.cutObjectiveBits(),second.cutObjectiveBits());Assert.assertEquals(first.selectedReceipts(),second.selectedReceipts());
			String after=fixture.analysis().occurrences().stream().map(o->o.key().normalizedSignature()+":"+
				(o.hop().getForcedExecType()!=null?o.hop().getForcedExecType():o.hop().getExecType())+":"+o.hop().getFederatedOutput()).toList()+"|"+
				fixture.producerGraph().getSelectedCutObjectiveBits()+"|"+fixture.producerGraph().getSelectedSourcePartitionNodeIds();
			Assert.assertEquals(before,after);
		}
	}
	@Test public void dpExactSelectionRejectsCopiedForeignReorderedMissingAndExtraReceipts()throws Exception{
		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-DP-01-ROOT-EQUAL-LOUT")).findFirst().orElseThrow();
		var owner=(CampaignBFrozenCostFixtureBridge.DpMemoInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		var exact=R4CostAdapterBridge.select(owner);
		Assert.assertSame(owner.memo(),exact.producer());Assert.assertSame(owner.selectedPlan(),exact.selectedReceipt());
		Assert.assertTrue(R4CostAdapterBridge.sameObjects(owner.aggregatePlan().getChildFedPlans(),exact.aggregateReceipts()));
		Assert.assertTrue(R4CostAdapterBridge.sameObjects(new DpPlacementAdapter().selectExact(owner.analysis(),owner.memo(),owner.aggregatePlan()).selectedRootPlans(),exact.orderedReceipts()));
		Assert.assertEquals(Double.doubleToRawLongBits(owner.aggregatePlan().getCumulativeCost()),exact.objectiveCostBits());
		var copiedAggregate=new FedPlan(owner.aggregatePlan().getCumulativeCost(),null,
			new java.util.ArrayList<>(owner.aggregatePlan().getChildFedPlans()));
		var unregisteredMemo=new org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable();
		for(var output:org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.values()){
			var variants=owner.memo().getFedPlanVariants(org.apache.commons.lang3.tuple.Pair.of(owner.root().getHopID(),output));
			if(variants!=null)unregisteredMemo.addFedPlanVariants(owner.root().getHopID(),output,variants);
		}
		var copied=new CampaignBFrozenCostFixtureBridge.DpMemoInput(owner.fixtureId(),owner.analysis(),unregisteredMemo,owner.root(),
			owner.enumeratedPlans(),owner.selectedPlan(),copiedAggregate,owner.tiePolicy(),owner.aliases(),owner.inputFingerprint());
		Assert.assertThrows(IllegalArgumentException.class,()->R4CostAdapterBridge.select(copied));
		var other=(CampaignBFrozenCostFixtureBridge.DpMemoInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		var foreign=new CampaignBFrozenCostFixtureBridge.DpMemoInput(owner.fixtureId(),owner.analysis(),owner.memo(),owner.root(),
			owner.enumeratedPlans(),owner.selectedPlan(),other.aggregatePlan(),owner.tiePolicy(),owner.aliases(),owner.inputFingerprint());
		Assert.assertThrows(IllegalArgumentException.class,()->R4CostAdapterBridge.select(foreign));
		var missing=new java.util.ArrayList<Object>(exact.orderedReceipts());missing.remove(missing.size()-1);
		var extra=new java.util.ArrayList<Object>(exact.orderedReceipts());extra.add(exact.orderedReceipts().get(0));
		for(var receipts:List.of(missing,extra)){
			var corrupt=new R4CostAdapterBridge.Selection(owner,exact.analysis(),exact.producer(),exact.root(),exact.selectedReceipt(),exact.aggregateReceipts(),
				List.copyOf(receipts),exact.obligationReceipts(),exact.registryReceipts(),exact.certificateReceipts(),exact.objectiveCostBits(),exact.analysisFingerprint(),List.of());
			CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->{try{R4CostTypedExtractor.extract(expected,corrupt);}
				catch(Exception e){throw new AssertionError(e);}});
		}
		var reorderExpected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.planner().equals("DP")&&e.fixture().equals("C2-DP-06-TRTW-EXACT")).findFirst().orElseThrow();
		var reorderOwner=(CampaignBFrozenCostFixtureBridge.FullPathInput)CampaignBFrozenCostFixtureBridge.fresh(reorderExpected).input();
		var reorderExact=R4CostAdapterBridge.select(reorderOwner);
		Assert.assertTrue(reorderExact.orderedReceipts().size()>1);
		var reordered=new java.util.ArrayList<Object>(reorderExact.orderedReceipts());java.util.Collections.reverse(reordered);
		var corrupt=new R4CostAdapterBridge.Selection(reorderOwner,reorderExact.analysis(),reorderExact.producer(),reorderExact.root(),
			reorderExact.selectedReceipt(),reorderExact.aggregateReceipts(),List.copyOf(reordered),reorderExact.obligationReceipts(),
			reorderExact.registryReceipts(),reorderExact.certificateReceipts(),reorderExact.objectiveCostBits(),reorderExact.analysisFingerprint(),List.of());
		CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->{try{R4CostTypedExtractor.extract(reorderExpected,corrupt);}
			catch(Exception e){throw new AssertionError(e);}});
	}
	@Test public void retainedFullPathRejectsWrapperSubstitutionAndStaleOwner()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		for(var expected:groups.stream().filter(e->e.planner().equals("DP")&&Set.of("C2-DP-05-SHARED-DIAMOND",
			"C2-DP-06-TRTW-EXACT","C2-X-09-BRANCH-JOIN","C2-X-10-FUNCTION-CALLSITE","C2-X-11-CLONE-RECOMPILE").contains(e.fixture())).toList()){
			var input=(CampaignBFrozenCostFixtureBridge.FullPathInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
			var retained=(CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt)input.certificate();
			var selected=R4CostAdapterBridge.select(input);
			Assert.assertSame(input.producer(),selected.producer()); Assert.assertSame(retained.retained().rootPlan(),selected.selectedReceipt());
			Assert.assertTrue(R4CostAdapterBridge.sameObjects(retained.rootPlan().getChildFedPlans(),selected.aggregateReceipts()));
			Assert.assertEquals(Double.doubleToRawLongBits(retained.retained().rootObjective()),selected.objectiveCostBits());
			Assert.assertTrue(selected.aggregateReceipts().stream().noneMatch(R4CostAdapterBridge::isWrapper));
			Assert.assertTrue(selected.orderedReceipts().stream().noneMatch(x->x instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt));
			Assert.assertTrue(selected.obligationReceipts().stream().noneMatch(x->x instanceof CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt));
			Assert.assertFalse(R4CostTypedExtractor.extract(expected,selected).facts().isEmpty());
			var copied=new CampaignBFrozenCostFixtureBridge.RetainedPlanReceipt(retained.retained());
			var row=expected.rows().get(0);String field=row.fields().keySet().iterator().next();
			var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);
			var evidence=new R4CostAdapterBridge.TypedEvidence(R4CostAdapterBridge.EvidenceKind.SELECTED_CERTIFICATE,input.producer(),
				new CampaignBFrozenCostFixtureBridge.PlanReceiptRole(retained.retained().rootPlan()),coordinate,
				R4CostAdapterBridge.ReceiptField.FULL_CERTIFICATE,copied);
			CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(expected,input,List.of(evidence)));
			var stale=(CampaignBFrozenCostFixtureBridge.FullPathInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
			var staleEvidence=new R4CostAdapterBridge.TypedEvidence(evidence.kind(),stale.producer(),evidence.role(),coordinate,
				evidence.receiptField(),retained);
			CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->R4CostTypedExtractor.derive(expected,input,List.of(staleEvidence)));
		}
	}
	@Test public void minstOwnerBoundRejectsForeignStaleReorderedMissingAndExtraReceipts()throws Exception{
		PlacementAnalysis full=CampaignBPlacementAnalysisFixtureBridge.build(ProductionShadowFixtureFactory.compile("B-01"));
		var fullBefore=CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(full);
		String fullFingerprint=full.analysisFingerprint();
		var sourceProjectionIdentities=full.occurrences().stream().map(System::identityHashCode).toList();
		var sourceNodeIdentities=full.graph().nodes().stream().map(System::identityHashCode).toList();
		var hopStateBefore=hopPlacementState(full);
		long counterBeforePrefix=CampaignBPlacementAnalysisFixtureBridge.constructionCount();
		PlacementAnalysis prefix=CampaignBPlacementAnalysisFixtureBridge.prefix(full,4);
		Assert.assertEquals(counterBeforePrefix+1,CampaignBPlacementAnalysisFixtureBridge.constructionCount());
		Assert.assertEquals(8,full.occurrences().size());Assert.assertEquals(8,full.graph().nodes().size());
		Assert.assertEquals(10,full.graph().constraints().size());Assert.assertEquals(0,full.graph().relocationActions().size());
		Assert.assertEquals(4,prefix.occurrences().size());Assert.assertEquals(4,prefix.graph().nodes().size());
		Assert.assertEquals(5,prefix.graph().constraints().size());Assert.assertEquals(0,prefix.graph().relocationActions().size());
		for(int i=0;i<4;i++){
			var source=full.occurrences().get(i);var projected=prefix.occurrences().get(i);
			Assert.assertSame(source,projected);Assert.assertEquals(source.key(),projected.key());
			Assert.assertEquals(source.key().normalizedSignature(),projected.key().normalizedSignature());
			Assert.assertSame(source.hop(),projected.hop());Assert.assertEquals(source.hop().getClass(),projected.hop().getClass());
			Assert.assertEquals(source.hop().getOpString(),projected.hop().getOpString());
			Assert.assertEquals(source.normalizedOrdinal(),projected.normalizedOrdinal());Assert.assertEquals(source.normalizedSignature(),projected.normalizedSignature());
			Assert.assertSame(full.graph().node(source.key()).orElseThrow(),prefix.graph().node(projected.key()).orElseThrow());
		}
		var prefixKeys=prefix.graph().nodes().stream().map(NeutralPlacementGraph.Node::key).collect(java.util.stream.Collectors.toSet());
		for(var constraint:prefix.graph().constraints()){Assert.assertTrue(prefixKeys.contains(constraint.left()));Assert.assertTrue(prefixKeys.contains(constraint.right()));}
		Assert.assertEquals(fullBefore,CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(full));
		Assert.assertEquals(fullFingerprint,full.analysisFingerprint());
		Assert.assertEquals(sourceProjectionIdentities,full.occurrences().stream().map(System::identityHashCode).toList());
		Assert.assertEquals(sourceNodeIdentities,full.graph().nodes().stream().map(System::identityHashCode).toList());
		Assert.assertEquals(hopStateBefore,hopPlacementState(full));
		long beforeInvalid=CampaignBPlacementAnalysisFixtureBridge.constructionCount();
		Assert.assertThrows(NullPointerException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(null,4));
		Assert.assertThrows(IllegalArgumentException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,0));
		Assert.assertThrows(IllegalArgumentException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,-1));
		Assert.assertThrows(IllegalArgumentException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,full.occurrences().size()+1));
		Assert.assertEquals(beforeInvalid,CampaignBPlacementAnalysisFixtureBridge.constructionCount());

		var sourceNodes=List.copyOf(full.graph().nodes().subList(0,4));
		var internalConstraintA=new NeutralPlacementGraph.Constraint(NeutralPlacementGraph.ConstraintKind.SAME_PLACEMENT,sourceNodes.get(0).key(),sourceNodes.get(1).key(),0,"r2a-internal-a");
		var internalConstraintB=new NeutralPlacementGraph.Constraint(NeutralPlacementGraph.ConstraintKind.SAME_FTYPE,sourceNodes.get(1).key(),sourceNodes.get(2).key(),0,"r2a-internal-b");
		var crossingConstraint=new NeutralPlacementGraph.Constraint(NeutralPlacementGraph.ConstraintKind.SAME_PLACEMENT,sourceNodes.get(2).key(),sourceNodes.get(3).key(),0,"r2a-crossing");
		var target=new PlacementState(ExecType.FED,FederatedOutput.FOUT,FType.ROW,false);
		var anchor=new DurableAnchorKey("r2a-prefix-anchor",FType.ROW,List.of(new AnchorPartition("r2a-worker",List.of(0L,0L),List.of(1L,1L))));
		var internalAction=relocationAction(sourceNodes.get(0),sourceNodes.get(1),target,anchor,"r2a-internal");
		var crossingAction=relocationAction(sourceNodes.get(0),sourceNodes.get(3),target,anchor,"r2a-crossing");
		var actionGraph=new NeutralPlacementGraph(sourceNodes,List.of(internalConstraintA,internalConstraintB,crossingConstraint),List.of(internalAction,crossingAction));
		PlacementAnalysis actionSource=CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(actionGraph);
		var actionSourceBefore=CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(actionSource);
		PlacementAnalysis actionPrefix=CampaignBPlacementAnalysisFixtureBridge.prefix(actionSource,3);
		Assert.assertSame(internalAction,actionPrefix.graph().relocationActions().get(0));Assert.assertEquals(1,actionPrefix.graph().relocationActions().size());
		Assert.assertFalse(actionPrefix.graph().relocationActions().stream().anyMatch(a->a==crossingAction));
		Assert.assertTrue(actionPrefix.graph().constraints().stream().anyMatch(c->c==internalConstraintA));Assert.assertTrue(actionPrefix.graph().constraints().stream().anyMatch(c->c==internalConstraintB));
		Assert.assertFalse(actionPrefix.graph().constraints().stream().anyMatch(c->c==crossingConstraint));
		Assert.assertSame(internalAction.key(),actionPrefix.graph().relocationActions().get(0).key());
		Assert.assertSame(internalAction.key().compatibleConsumers(),actionPrefix.graph().relocationActions().get(0).key().compatibleConsumers());
		Assert.assertSame(internalAction.obligations().get(0),actionPrefix.graph().relocationActions().get(0).obligations().get(0));
		for(var action:actionPrefix.graph().relocationActions())for(var obligation:action.obligations())Assert.assertEquals(action.key(),obligation.relocationAction());
		var actionPrefixKeys=actionPrefix.graph().nodes().stream().map(NeutralPlacementGraph.Node::key).collect(java.util.stream.Collectors.toSet());
		Assert.assertTrue(actionPrefix.graph().constraints().stream().allMatch(c->actionPrefixKeys.contains(c.left())&&actionPrefixKeys.contains(c.right())));
		Assert.assertEquals(actionSourceBefore,CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(actionSource));
		PlacementAnalysis wholeActions=CampaignBPlacementAnalysisFixtureBridge.prefix(actionSource,4);
		Assert.assertTrue(wholeActions.graph().relocationActions().stream().anyMatch(a->a==internalAction));
		Assert.assertTrue(wholeActions.graph().relocationActions().stream().anyMatch(a->a==crossingAction));

		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-MS-01-EQUAL-CUT")).findFirst().orElseThrow();
		var owner=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		var canonicalAnalysis=new NeutralPlacementGraphBuilder().buildAnalysis(ProductionShadowFixtureFactory.compile("B-01"));
		var expectedOccurrences=canonicalAnalysis.occurrences().subList(0,Math.min(4,canonicalAnalysis.occurrences().size()));
		var actualOccurrences=owner.analysis().occurrences(); Assert.assertEquals(4,actualOccurrences.size());
		for(int i=0;i<4;i++){var e=expectedOccurrences.get(i);var a=actualOccurrences.get(i);Assert.assertEquals("MS01_CANONICAL_OWNER_DRIFT|index="+i,e.key().normalizedSignature(),a.key().normalizedSignature());Assert.assertEquals(e.hop().getClass(),a.hop().getClass());Assert.assertEquals(e.hop().getOpString(),a.hop().getOpString());}
		for(var a:actualOccurrences)Assert.assertSame(a.hop(),owner.graph().getVertex(a.hop().getHopID()).getHopRef());
		var exact=R4CostAdapterBridge.select(owner);Assert.assertSame(owner.ownerBound().producerReceipt(),exact.producer());
		var foreign=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		Assert.assertThrows(IllegalArgumentException.class,()->R4CostAdapterBridge.selectMinst(owner,foreign.analysis()));
		var staleExpected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-MS-03-SHARED-DOWNLOAD")).findFirst().orElseThrow();
		var staleFixture=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(staleExpected).input();
		var stale=LegacyMinstOfflineSelectedCapture.bindLegacyPlacementInput(staleFixture.analysis(),staleFixture.graph());
		var staleHop=staleFixture.analysis().occurrences().get(0).hop();var originalOutput=staleHop.getFederatedOutput();
		staleHop.setFederatedOutput(originalOutput==org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE?
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT:
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE);
		try{Assert.assertThrows(IllegalArgumentException.class,()->new MinStPlacementAdapter().select(staleFixture.analysis(),stale));}
		finally{staleHop.setFederatedOutput(originalOutput);}
		var reordered=new java.util.ArrayList<Object>(exact.orderedReceipts());java.util.Collections.reverse(reordered);
		var missing=new java.util.ArrayList<Object>(exact.orderedReceipts());missing.remove(missing.size()-1);
		var extra=new java.util.ArrayList<Object>(exact.orderedReceipts());extra.add(exact.orderedReceipts().get(0));
		for(var receipts:List.of(reordered,missing,extra)){
			var corrupt=new R4CostAdapterBridge.Selection(owner,exact.analysis(),exact.producer(),exact.root(),exact.selectedReceipt(),exact.aggregateReceipts(),
				List.copyOf(receipts),exact.obligationReceipts(),exact.registryReceipts(),exact.certificateReceipts(),exact.objectiveCostBits(),exact.analysisFingerprint(),List.of());
			CampaignBLiteralAuthority.expect("R4_TYPED_PRODUCER_IDENTITY",()->{try{R4CostTypedExtractor.extract(expected,corrupt);}
				catch(Exception e){throw new AssertionError(e);}});
		}
	}
	@Test public void allBridgePathsAreRepeatableConcurrentAndMutationFree()throws Exception{
		assertPrefixSourceScope();
		PlacementAnalysis full=CampaignBPlacementAnalysisFixtureBridge.build(ProductionShadowFixtureFactory.compile("B-01"));
		var fullBefore=CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(full);
		var hopStateBefore=hopPlacementState(full);
		String graphSignatureBefore=full.graph().normalizedSignature();
		String fingerprintBefore=full.analysisFingerprint();
		var registryBefore=registrySnapshot(full);
		long beforeInvalid=CampaignBPlacementAnalysisFixtureBridge.constructionCount();
		Assert.assertThrows(NullPointerException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(null,4));
		Assert.assertThrows(IllegalArgumentException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,0));
		Assert.assertThrows(IllegalArgumentException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,-1));
		Assert.assertThrows(IllegalArgumentException.class,()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,full.occurrences().size()+1));
		Assert.assertEquals(beforeInvalid,CampaignBPlacementAnalysisFixtureBridge.constructionCount());
		long beforeSuccessfulPrefixes=CampaignBPlacementAnalysisFixtureBridge.constructionCount();
		PlacementAnalysis first=CampaignBPlacementAnalysisFixtureBridge.prefix(full,4);
		PlacementAnalysis second=CampaignBPlacementAnalysisFixtureBridge.prefix(full,4);
		var executorForPrefix=java.util.concurrent.Executors.newFixedThreadPool(2);
		PlacementAnalysis concurrentA;PlacementAnalysis concurrentB;
		try{
			var a=executorForPrefix.submit(()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,4));
			var b=executorForPrefix.submit(()->CampaignBPlacementAnalysisFixtureBridge.prefix(full,4));
			concurrentA=a.get();concurrentB=b.get();
		}
		finally{executorForPrefix.shutdownNow();}
		Assert.assertEquals(beforeSuccessfulPrefixes+4,CampaignBPlacementAnalysisFixtureBridge.constructionCount());
		for(PlacementAnalysis projected:List.of(first,second,concurrentA,concurrentB)){
			Assert.assertEquals(first.analysisFingerprint(),projected.analysisFingerprint());
			Assert.assertEquals(first.graph().normalizedSignature(),projected.graph().normalizedSignature());
			Assert.assertEquals(CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(first),CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(projected));
			Assert.assertEquals(first.occurrences().stream().map(o->o.key().normalizedSignature()).toList(),projected.occurrences().stream().map(o->o.key().normalizedSignature()).toList());
			for(int i=0;i<projected.occurrences().size();i++){
				Assert.assertSame(full.occurrences().get(i),projected.occurrences().get(i));
				Assert.assertSame(full.occurrences().get(i).hop(),projected.occurrences().get(i).hop());
				Assert.assertSame(full.graph().node(full.occurrences().get(i).key()).orElseThrow(),projected.graph().node(projected.occurrences().get(i).key()).orElseThrow());
			}
			for(var action:projected.graph().relocationActions())Assert.assertTrue(full.graph().relocationActions().stream().anyMatch(sourceAction->sourceAction==action));
		}
		Assert.assertEquals(fullBefore,CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(full));
		Assert.assertEquals(hopStateBefore,hopPlacementState(full));
		Assert.assertEquals(graphSignatureBefore,full.graph().normalizedSignature());
		Assert.assertEquals(fingerprintBefore,full.analysisFingerprint());
		Assert.assertEquals(registryBefore,registrySnapshot(full));

		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(
			CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		for(var expected:groups)for(var input:CampaignBFrozenCostFixtureBridge.fresh(expected).inputs()){
			String before=input.analysis().graph().normalizedSignature()+"|"+input.analysis().analysisFingerprint();
			var firstSelection=R4CostAdapterBridge.select(input);var secondSelection=R4CostAdapterBridge.select(input);
			Assert.assertEquals(firstSelection.analysisFingerprint(),secondSelection.analysisFingerprint());
			Assert.assertEquals(firstSelection.orderedReceipts(),secondSelection.orderedReceipts());
			var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
			try{
				var a=executor.submit(()->R4CostAdapterBridge.select(input));var b=executor.submit(()->R4CostAdapterBridge.select(input));
				Assert.assertEquals(a.get().analysisFingerprint(),b.get().analysisFingerprint());
				Assert.assertEquals(a.get().orderedReceipts(),b.get().orderedReceipts());
			}
			finally{executor.shutdownNow();}
			Assert.assertEquals(before,input.analysis().graph().normalizedSignature()+"|"+input.analysis().analysisFingerprint());
		}
	}

	private static NeutralPlacementGraph.RelocationAction relocationAction(NeutralPlacementGraph.Node source,NeutralPlacementGraph.Node consumer,
		PlacementState target,DurableAnchorKey anchor,String scope){
		var key=new RelocationActionKey(source.valueVersion(),target,anchor,scope,List.of(consumer.key()));
		var obligation=new ObligationKey(consumer.key(),0,source.valueVersion(),target,key,scope);
		return new NeutralPlacementGraph.RelocationAction(key,List.of(obligation));
	}
	private static List<String> hopPlacementState(PlacementAnalysis analysis){
		return analysis.occurrences().stream().map(o->o.key().normalizedSignature()+":"+System.identityHashCode(o.hop())+":"+
			(o.hop().getForcedExecType()!=null?o.hop().getForcedExecType():o.hop().getExecType())+":"+o.hop().getFederatedOutput()).toList();
	}
	private static List<String> registrySnapshot(PlacementAnalysis analysis){
		return analysis.occurrences().stream().map(o->o.key().normalizedSignature()+":"+
			analysis.hop(o.key()).map(System::identityHashCode).orElse(-1)).toList();
	}
	private static void assertPrefixSourceScope()throws Exception{
		String source=java.nio.file.Files.readString(java.nio.file.Path.of(
			"src/test/java/org/apache/sysds/hops/fedplanner/placement/CampaignBPlacementAnalysisFixtureBridge.java"));
		int start=source.indexOf("public static PlacementAnalysis prefix(PlacementAnalysis source, int occurrenceCount)");
		Assert.assertTrue(start>=0);
		int bodyStart=source.indexOf('{',start),depth=0,end=-1;
		for(int i=bodyStart;i<source.length();i++){
			char c=source.charAt(i);
			if(c=='{')depth++;else if(c=='}'&&--depth==0){end=i;break;}
		}
		String body=source.substring(bodyStart,end+1);
		for(String forbidden:List.of("new LiteralOp","new HopOccurrenceProjection","getResource","getResourceAsStream","manifest",
			"getDeclared","setAccessible","invoke(","B-01","C2-","expected","golden","System.getenv","System.getProperty",
			"setForcedExecType","setFederatedOutput","ProductionShadowFixtureFactory"))
			Assert.assertFalse("forbidden prefix source token: "+forbidden,body.contains(forbidden));
	}
	private static R4CostAdapterBridge.Selection selection(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,List<R4CostAdapterBridge.TypedEvidence> evidence){
		var live=R4CostAdapterBridge.select(input);return new R4CostAdapterBridge.Selection(input,live.analysis(),live.producer(),live.root(),
			live.selectedReceipt(),live.aggregateReceipts(),live.orderedReceipts(),live.obligationReceipts(),live.registryReceipts(),live.certificateReceipts(),
			live.objectiveCostBits(),live.analysisFingerprint(),evidence);
	}
	private static Object receipt(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,R4CostAdapterBridge.ReceiptField field,String rowKind,String fieldName,int ordinal){
		if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d)return switch(field){case ANALYSIS->d.analysis();case DP_MEMO->d.memo();case DP_ROOT->d.root();case DP_SELECTED->d.selectedPlan();
			case DP_ENUMERATED->{if(fieldName.equals("rank0Cost"))yield d.enumeratedPlans().get(0);if(fieldName.equals("rank1Cost"))yield d.enumeratedPlans().get(d.enumeratedPlans().size()-1);
				if(fieldName.equals("cp")||fieldName.equals("fed")){var exec=fieldName.equals("cp")?org.apache.sysds.common.Types.ExecType.CP:org.apache.sysds.common.Types.ExecType.FED;
					var matches=d.enumeratedPlans().stream().filter(p->p.getExecType()==exec).toList();if(matches.size()!=1)throw new AssertionError("R4_DP_EXEC_VARIANT_BIJECTION|fixture="+d.fixtureId()+"|exec="+exec+"|matches="+matches.size());yield matches.get(0);}
				String output=fieldName.equals("lout")||fieldName.equals("cp")?"LOUT":"FOUT";yield d.enumeratedPlans().stream().filter(p->p.getFedOutType().name().equals(output)).findFirst().orElseThrow();}default->throw new AssertionError(field);};
		if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m)return switch(field){case ANALYSIS->m.analysis();case MINST_GRAPH,MINST_CUT_EDGE->m.graph();case MINST_CUT_CERTIFICATE->m.ownerBound();case MINST_REPAIR->m.analysis().hop(m.repairCertificates().get(ordinal).role().compiledKey()).orElseThrow();
			case MINST_OBLIGATION->{var producer=registryCertificate(m,rowKind).producer();yield m.ownerBound().obligationReceipts().stream()
				.filter(o->o.childHopId()==producer.producerHopId()).findFirst().orElseThrow();}
				case MINST_REGISTRY->registryCertificate(m,rowKind).spec();default->throw new AssertionError(field);};
		if(input instanceof CampaignBFrozenCostFixtureBridge.GraphExclusionInput graph){
			if(field!=R4CostAdapterBridge.ReceiptField.GRAPH_EXCLUSION)throw new AssertionError(field);return graph.receipt();}
		var full=(CampaignBFrozenCostFixtureBridge.FullPathInput)input;if(field==R4CostAdapterBridge.ReceiptField.ANALYSIS)return input.analysis();if(field==R4CostAdapterBridge.ReceiptField.FULL_CERTIFICATE)return full.certificate();throw new AssertionError(field);
	}
	private static CampaignBFrozenCostFixtureBridge.RegistryCertificate registryCertificate(
		CampaignBFrozenCostFixtureBridge.MinstGraphInput input,String rowKind){
		return input.registryCertificates().stream().filter(x->rowKind.equals("REGISTRY_REFED")?x.spec().getClass().getSimpleName().equals("AnchorSpec"):
			rowKind.equals("REGISTRY_FOUT_MATERIALIZE")?x.spec().getClass().getSimpleName().equals("MaterializeSpec"):
			x.spec().getClass().getSimpleName().equals("LocalMaterializeSpec")).findFirst().orElseThrow();
	}
}
