/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/** Adapter-independent exact-recipe, alias, parser and anti-echo gates. */
public class CampaignBR4CostSelfTest {
	@Test public void allTwentyTwoGroupsHaveExactDeterministicRecipes()throws Exception{
		var groups=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest")));
		Assert.assertEquals(22,groups.size());Set<String>digests=new LinkedHashSet<>();int dual=0,nativeRecipes=0;
		for(var e:groups){CampaignBFrozenCostFixtureBridge.Fixture a,b;try{a=CampaignBFrozenCostFixtureBridge.fresh(e);b=CampaignBFrozenCostFixtureBridge.fresh(e);}
			catch(Exception x){throw new AssertionError("R4_RECIPE_BUILD|"+e.planner()+"|"+e.fixture(),x);}
			Assert.assertEquals(a.planner()+"|"+a.id(),a.digest(),b.digest());Assert.assertTrue(digests.add(a.planner()+'|'+a.id()+'|'+a.digest()));
			if(a.id().equals("C2-DP-04-ANCHOR-CONTRAST")){dual++;Assert.assertEquals(Set.of("B-11","B-12"),a.arms().stream().map(CampaignBFrozenCostFixtureBridge.Arm::bFixture).collect(java.util.stream.Collectors.toSet()));
				Assert.assertEquals(2,a.inputs().size());Assert.assertTrue(a.inputs().stream().allMatch(x->x instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput));
				var concrete=(CampaignBFrozenCostFixtureBridge.DpMemoInput)a.inputs().get(0);var missing=(CampaignBFrozenCostFixtureBridge.DpMemoInput)a.inputs().get(1);
				Assert.assertNotSame(concrete.analysis(),missing.analysis());Assert.assertNotSame(concrete.memo(),missing.memo());Assert.assertNotSame(concrete.root(),missing.root());
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
			var input=CampaignBFrozenCostFixtureBridge.fresh(expected).input();var role=input.aliases().get(0);
			for(var row:expected.rows())for(String field:row.fields().keySet()){
				var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);
				var corrupt=new R4CostAdapterBridge.TypedEvidence(kinds[checked%kinds.length],new Object(),role,coordinate,R4CostAdapterBridge.ReceiptField.ANALYSIS,new Object());
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
			for(var input:fixture.inputs()){List<R4CostAdapterBridge.TypedEvidence> evidence=new java.util.ArrayList<>();
				for(var row:expected.rows())for(String field:row.fields().keySet()){
					var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),field);var req=R4CostTypedExtractor.requirement(coordinate);
					if(req.dp04Arm()!=null&&!input.fixtureId().endsWith(':'+req.dp04Arm()))continue;
					int ordinal=java.util.stream.IntStream.range(0,expected.rows().size()).filter(i->expected.rows().get(i).kind().equals(row.kind())&&expected.rows().get(i).digest().equals(row.digest())).findFirst().orElseThrow();
					ordinal=(int)expected.rows().subList(0,ordinal).stream().filter(r->r.kind().equals(row.kind())).count();
					Object receipt=receipt(input,req.receiptField(),row.kind(),field,ordinal);var role=receipt instanceof CampaignBFrozenCostFixtureBridge.RepairCertificate r?r.role():
						receipt instanceof CampaignBFrozenCostFixtureBridge.RegistryCertificate r?r.producer():
						receipt instanceof org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.SelectedObligation o?
							input.aliases().stream().filter(a->a.producerHopId()==o.getChildHopId()).findFirst().orElseThrow():input.aliases().get(0);
					evidence.add(new R4CostAdapterBridge.TypedEvidence(req.kind(),input.producer(),role,coordinate,req.receiptField(),receipt));
				}
				selections.add(selection(input,List.copyOf(evidence)));
			}
			try{var actual=R4CostTypedExtractor.extract(expected,List.copyOf(selections));Assert.assertEquals(expected.facts().size(),actual.facts().size());}
			catch(RuntimeException x){throw new AssertionError("R4_POSITIVE_COORDINATE|"+expected.fixture(),x);}
		}
	}
	@Test public void correctMinstObligationWithWrongChildRoleIsRejected()throws Exception{
		var expected=CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource("g004b-c2-dp-minst-offline-literal.manifest"))).stream()
			.filter(e->e.fixture().equals("C2-MS-03-SHARED-DOWNLOAD")).findFirst().orElseThrow();
		var input=(CampaignBFrozenCostFixtureBridge.MinstGraphInput)CampaignBFrozenCostFixtureBridge.fresh(expected).input();
		var row=expected.rows().stream().filter(r->r.kind().equals("REGISTRY_LOCAL_MATERIALIZE")).findFirst().orElseThrow();
		var coordinate=new R4CostAdapterBridge.FieldCoordinate(row.digest(),row.kind(),"consumers");var req=R4CostTypedExtractor.requirement(coordinate);
		var obligation=(org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.SelectedObligation)
			receipt(input,req.receiptField(),row.kind(),"consumers",0);
		var wrongRole=input.aliases().stream().filter(a->a.producerHopId()!=obligation.getChildHopId()).findFirst().orElseThrow();
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
	private static R4CostAdapterBridge.Selection selection(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,List<R4CostAdapterBridge.TypedEvidence> evidence){
		if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d)return new R4CostAdapterBridge.Selection(input,d.analysis(),d.memo(),d.root(),d.selectedPlan(),d.enumeratedPlans(),List.of(),List.of(),List.of(),d.analysis().analysisFingerprint(),evidence);
		if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m){List<Object> certs=new java.util.ArrayList<>();certs.add(m.cutCertificate());certs.addAll(m.cutCertificate().edges());certs.addAll(m.repairCertificates());certs.addAll(m.registryCertificates());
			return new R4CostAdapterBridge.Selection(input,m.analysis(),m.graph(),null,null,List.of(),m.selectedObligations(),m.registryReceipts(),List.copyOf(certs),m.analysis().analysisFingerprint(),evidence);}
		var f=(CampaignBFrozenCostFixtureBridge.FullPathInput)input;return new R4CostAdapterBridge.Selection(input,input.analysis(),input.analysis(),null,null,List.of(),List.of(),List.of(),List.of(f.certificate()),input.analysis().analysisFingerprint(),evidence);
	}
	private static Object receipt(CampaignBFrozenCostFixtureBridge.CostSelectionInput input,R4CostAdapterBridge.ReceiptField field,String rowKind,String fieldName,int ordinal){
		if(input instanceof CampaignBFrozenCostFixtureBridge.DpMemoInput d)return switch(field){case ANALYSIS->d.analysis();case DP_MEMO->d.memo();case DP_ROOT->d.root();case DP_SELECTED->d.selectedPlan();
			case DP_ENUMERATED->{if(fieldName.equals("rank0Cost"))yield d.enumeratedPlans().get(0);if(fieldName.equals("rank1Cost"))yield d.enumeratedPlans().get(d.enumeratedPlans().size()-1);
				String output=fieldName.equals("lout")||fieldName.equals("cp")?"LOUT":"FOUT";yield d.enumeratedPlans().stream().filter(p->p.getFedOutType().name().equals(output)).findFirst().orElseThrow();}default->throw new AssertionError(field);};
		if(input instanceof CampaignBFrozenCostFixtureBridge.MinstGraphInput m)return switch(field){case ANALYSIS->m.analysis();case MINST_GRAPH->m.graph();case MINST_CUT_CERTIFICATE->m.cutCertificate();case MINST_CUT_EDGE->m.cutCertificate().edges().get(ordinal);case MINST_REPAIR->m.repairCertificates().get(ordinal);
			case MINST_OBLIGATION->{var producer=registryCertificate(m,rowKind).producer();yield m.selectedObligations().stream()
				.map(org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.SelectedObligation.class::cast)
				.filter(o->o.getChildHopId()==producer.producerHopId()).findFirst().orElseThrow();}
			case MINST_REGISTRY->registryCertificate(m,rowKind);default->throw new AssertionError(field);};
		var full=(CampaignBFrozenCostFixtureBridge.FullPathInput)input;if(field==R4CostAdapterBridge.ReceiptField.ANALYSIS)return input.analysis();if(field==R4CostAdapterBridge.ReceiptField.FULL_CERTIFICATE)return full.certificate();throw new AssertionError(field);
	}
	private static CampaignBFrozenCostFixtureBridge.RegistryCertificate registryCertificate(
		CampaignBFrozenCostFixtureBridge.MinstGraphInput input,String rowKind){
		return input.registryCertificates().stream().filter(x->rowKind.equals("REGISTRY_REFED")?x.spec().getClass().getSimpleName().equals("AnchorSpec"):
			rowKind.equals("REGISTRY_FOUT_MATERIALIZE")?x.spec().getClass().getSimpleName().equals("MaterializeSpec"):
			x.spec().getClass().getSimpleName().equals("LocalMaterializeSpec")).findFirst().orElseThrow();
	}
}
