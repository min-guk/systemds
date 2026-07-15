/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED: exact frozen recipes and typed selected-plan surfaces are mandatory. */
public class CampaignBCostPlannerDifferentialContractTest {
	private static final String MANIFEST="g004b-c2-dp-minst-offline-literal.manifest",DIGEST=MANIFEST+".sha256",LEDGER="g004b-campaign-b-difference-authorizations.jsonl";

	@Test public void everyFreshExactDpMinstPlanEqualsFrozenLiteral()throws Exception{
		String manifest=CampaignBContractProbe.resource(MANIFEST);
		Assert.assertEquals(CampaignBContractProbe.resource(DIGEST).trim(),CampaignBContractProbe.sha256(manifest));
		Assert.assertEquals("",CampaignBContractProbe.resource(LEDGER));
		List<String>missing=new ArrayList<>();
		for(var expected:groups()){
			var f=CampaignBFrozenCostFixtureBridge.fresh(expected);List<R4CostAdapterBridge.Selection> selections=new ArrayList<>();boolean groupMissing=false;
			for(int inputIndex=0;inputIndex<f.inputs().size();inputIndex++){
				var input=f.inputs().get(inputIndex);CampaignBContractProbe.Fixture base=f.arms().isEmpty()?null:
					new CampaignBContractProbe.Fixture(expected.fixture(),f.arms().get(Math.min(inputIndex,f.arms().size()-1)).program(),input.analysis());
				var before=base==null?null:CampaignBContractProbe.snapshot(base);
				try{
					var s=R4CostAdapterBridge.select(input);
					Assert.assertSame(input,s.input());Assert.assertSame(input.producer(),s.producer());
					Assert.assertSame(input.analysis(),s.analysis());Assert.assertEquals(input.analysis().analysisFingerprint(),s.analysisFingerprint());
					selections.add(s);
				}
				catch(AssertionError e){if(e.getMessage()!=null&&e.getMessage().startsWith("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING")){missing.add(expected.planner()+'|'+input.fixtureId());groupMissing=true;}else throw e;}
				if(base!=null)CampaignBContractProbe.assertUnchanged(before,CampaignBContractProbe.snapshot(base));
			}
			if(!groupMissing)CampaignBLiteralAuthority.assertBijection(CampaignBLiteralAuthority.compare(expected,
				CampaignBLiteralAuthority.actual(expected,List.copyOf(selections))),List.of());
		}
		Assert.assertEquals("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING|"+missing,List.of(),missing);
	}

	@Test public void plannerSwapFedAllLeakageWrongLegalPlanAndEchoAreLive()throws Exception{
		for(var e:groups()){
			var p=exact(e);
			var swap=with(p,e.planner().equals("DP")?"MINST":"DP",p.assignments(),p.relocations(),p.obligations(),p.anchors(),p.registries(),p.objective(),p.ties(),p.trtw(),p.boundaries(),p.clones(),p.structural(),p.facts(),p.fingerprint());
			Assert.assertTrue(CampaignBLiteralAuthority.compare(e,swap).stream().anyMatch(d->d.surface()==CampaignBLiteralAuthority.Surface.OBJECTIVE));
			var fed=with(p,p.planner(),p.assignments(),p.relocations(),p.obligations(),p.anchors(),p.registries(),List.of("FEDALL_OBJECTIVE|fedCount=99|foutCount=99"),p.ties(),p.trtw(),p.boundaries(),p.clones(),p.structural(),p.facts(),"fedall");
			Assert.assertTrue(CampaignBLiteralAuthority.compare(e,fed).stream().anyMatch(d->d.surface()==CampaignBLiteralAuthority.Surface.OBJECTIVE));
		}
		var e=groups().stream().filter(x->!x.assignments().isEmpty()).findFirst().orElseThrow();var p=exact(e);
		Map<String,String>wrong=new LinkedHashMap<>(p.assignments());String key=wrong.keySet().iterator().next();wrong.put(key,alternateLegal(wrong.get(key)));
		var echo=with(p,p.planner(),Map.copyOf(wrong),p.relocations(),p.obligations(),p.anchors(),p.registries(),p.objective(),p.ties(),p.trtw(),p.boundaries(),p.clones(),p.structural(),p.facts(),p.fingerprint()+"|TYPED_PLAN_MISMATCH");
		var diffs=CampaignBLiteralAuthority.compare(e,echo);
		Assert.assertTrue(diffs.stream().anyMatch(d->d.surface()==CampaignBLiteralAuthority.Surface.ASSIGNMENT));
		Assert.assertTrue(diffs.stream().anyMatch(d->d.surface()==CampaignBLiteralAuthority.Surface.FINGERPRINT));
	}

	@Test public void everyRequiredTypedSurfaceHasAFieldSpecificCorruption()throws Exception{
		EnumSet<CampaignBLiteralAuthority.Surface> seen=EnumSet.noneOf(CampaignBLiteralAuthority.Surface.class);
		for(var e:groups()){var p=exact(e);for(var s:CampaignBLiteralAuthority.Surface.values()){
			var corrupt=corrupt(p,s);if(corrupt==null)continue;
			var ds=CampaignBLiteralAuthority.compare(e,corrupt);Assert.assertTrue("missing live diff "+s,ds.stream().anyMatch(d->d.surface()==s));seen.add(s);
		}}
		Assert.assertEquals(EnumSet.allOf(CampaignBLiteralAuthority.Surface.class),seen);
	}

	@Test public void allowedLabelsCannotAuthorizeGoldenEcho()throws Exception{
		new CampaignBR4CostSelfTest().allowedLabelsAndCorrectGoldenValuesCannotReplaceProducerIdentity();
	}

	@Test public void ledgerRequiresExactDirectionConsumptionAndUniqueness()throws Exception{
		var e=groups().stream().filter(x->!x.assignments().isEmpty()).findFirst().orElseThrow();var d=CampaignBLiteralAuthority.compare(e,corrupt(exact(e),CampaignBLiteralAuthority.Surface.ASSIGNMENT)).get(0);
		var row=new CampaignBLiteralAuthority.LedgerRow(d,"proof","artifact","not timing/runtime/environment");
		CampaignBLiteralAuthority.assertBijection(List.of(d),List.of(row));
		CampaignBLiteralAuthority.expect("DUPLICATE_LEDGER_ROW",()->CampaignBLiteralAuthority.assertBijection(List.of(d),List.of(row,row)));
		CampaignBLiteralAuthority.expect("UNCONSUMED_LEDGER_ROW",()->CampaignBLiteralAuthority.assertBijection(List.of(),List.of(row)));
		var rev=new CampaignBLiteralAuthority.Diff(d.planner(),d.fixture(),d.surface(),d.key(),d.adapter(),d.legacy(),d.objectiveDelta(),d.tieDelta(),d.reason());
		CampaignBLiteralAuthority.expect("UNAUTHORIZED_DIFFERENCE",()->CampaignBLiteralAuthority.assertBijection(List.of(d),List.of(new CampaignBLiteralAuthority.LedgerRow(rev,"p","a","n"))));
	}

	private static List<CampaignBLiteralAuthority.Expected> groups()throws Exception{return CampaignBLiteralAuthority.group(CampaignBLiteralAuthority.parse(CampaignBContractProbe.resource(MANIFEST)));}
	private static CampaignBLiteralAuthority.TypedPlan exact(CampaignBLiteralAuthority.Expected e){return new CampaignBLiteralAuthority.TypedPlan(e.planner(),e.fixture(),e.assignments(),e.relocations(),e.obligations(),e.anchors(),e.registries(),e.objective(),e.ties(),e.trtw(),e.boundaries(),e.clones(),e.structural(),e.facts(),e.fingerprint());}
	private static String alternateLegal(String s){return switch(s){case "CP/LOUT"->"FED/FOUT";case "FED/FOUT"->"CP/LOUT";case "CP/FOUT"->"FED/LOUT";default->"CP/FOUT";};}
	private static <T> List<T> changed(List<T>x,T v){if(x.isEmpty())return null;List<T>o=new ArrayList<>(x);o.set(0,v);return List.copyOf(o);}
	private static Map<String,String> changed(Map<String,String>x){if(x.isEmpty())return null;Map<String,String>o=new LinkedHashMap<>(x);String k=o.keySet().iterator().next();o.put(k,o.get(k)+"|CORRUPT");return Map.copyOf(o);}
	private static CampaignBLiteralAuthority.TypedPlan corrupt(CampaignBLiteralAuthority.TypedPlan p,CampaignBLiteralAuthority.Surface s){
		Map<String,String>a=p.assignments(),facts=p.facts();List<String>rel=p.relocations(),obl=p.obligations(),anc=p.anchors(),reg=p.registries(),obj=p.objective(),tie=p.ties(),tr=p.trtw(),b=p.boundaries(),c=p.clones(),st=p.structural();String fp=p.fingerprint();String planner=p.planner(),fixture=p.fixture();
		switch(s){case ASSIGNMENT:a=changed(a);break;case RELOCATION:rel=changed(rel,rel.isEmpty()?"":rel.get(0)+"|CORRUPT");break;case OBLIGATION:obl=changed(obl,obl.isEmpty()?"":obl.get(0)+"|CORRUPT");break;case ANCHOR:anc=changed(anc,anc.isEmpty()?"":anc.get(0)+"|CORRUPT");break;case REGISTRY:reg=changed(reg,reg.isEmpty()?"":reg.get(0)+"|CORRUPT");break;case OBJECTIVE:obj=changed(obj,obj.isEmpty()?"":obj.get(0)+"|CORRUPT");break;case ORDERED_TIE:tie=changed(tie,tie.isEmpty()?"":tie.get(0)+"|CORRUPT");break;case TRTW:tr=changed(tr,tr.isEmpty()?"":tr.get(0)+"|CORRUPT");break;case BOUNDARY:b=changed(b,b.isEmpty()?"":b.get(0)+"|CORRUPT");break;case CLONE_RECOMPILE:c=changed(c,c.isEmpty()?"":c.get(0)+"|CORRUPT");break;case STRUCTURAL:st=changed(st,st.isEmpty()?"":st.get(0)+"|CORRUPT");break;case LITERAL_FIELD:facts=changed(facts);break;case FINGERPRINT:fp+="|CORRUPT";break;}
		if(a==null||rel==null||obl==null||anc==null||reg==null||obj==null||tie==null||tr==null||b==null||c==null||st==null||facts==null)return null;
		return with(p,planner, a,rel,obl,anc,reg,obj,tie,tr,b,c,st,facts,fp);
	}
	private static CampaignBLiteralAuthority.TypedPlan with(CampaignBLiteralAuthority.TypedPlan p,String planner,Map<String,String>a,List<String>rel,List<String>obl,List<String>anc,List<String>reg,List<String>obj,List<String>tie,List<String>tr,List<String>b,List<String>c,List<String>st,Map<String,String>facts,String fp){return new CampaignBLiteralAuthority.TypedPlan(planner,p.fixture(),a,rel,obl,anc,reg,obj,tie,tr,b,c,st,facts,fp);}
}
