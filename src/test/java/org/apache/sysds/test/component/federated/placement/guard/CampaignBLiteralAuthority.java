/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded heterogeneous parser plus typed plan comparator and strict authorization bijection. */
final class CampaignBLiteralAuthority {
	private static final Pattern FIELD=Pattern.compile("\\|([A-Za-z][A-Za-z0-9]*)=");
	private static final Map<String,List<List<String>>> SCHEMAS=schemas();
	enum Surface { ASSIGNMENT,RELOCATION,OBLIGATION,ANCHOR,REGISTRY,OBJECTIVE,ORDERED_TIE,TRTW,BOUNDARY,CLONE_RECOMPILE,STRUCTURAL,FINGERPRINT,LITERAL_FIELD }
	record Row(String fixture,String planner,String kind,Map<String,String> fields,String digest) { }
	record Expected(String planner,String fixture,List<Row> rows,Map<String,String> assignments,List<String> relocations,
		List<String> obligations,List<String> anchors,List<String> registries,List<String> objective,List<String> ties,
		List<String> trtw,List<String> boundaries,List<String> clones,List<String> structural,Map<String,String> facts,String fingerprint) { }
	record TypedPlan(String planner,String fixture,Map<String,String> assignments,List<String> relocations,List<String> obligations,
		List<String> anchors,List<String> registries,List<String> objective,List<String> ties,List<String> trtw,
		List<String> boundaries,List<String> clones,List<String> structural,Map<String,String> facts,String fingerprint) { }
	record Diff(String planner,String fixture,Surface surface,String key,String legacy,String adapter,String objectiveDelta,String tieDelta,String reason){String identity(){return String.join("|",planner,fixture,surface.name(),key,legacy,adapter,objectiveDelta,tieDelta,reason);}}
	record LedgerRow(Diff diff,String proof,String artifact,String nonCausation) { }

	static List<Row> parse(String manifest)throws Exception{
		String[] lines=manifest.split("\\R");if(lines.length!=38||!lines[0].equals("SCHEMA|g004b-c2-offline-selected-plan-v1"))throw new AssertionError("R4_LITERAL_SCHEMA");
		List<Row> out=new ArrayList<>();for(int i=4;i<lines.length;i++)out.add(parseRow(lines[i]));
		Set<String> groups=new LinkedHashSet<>();for(Row r:out)groups.add(r.fixture()+'|'+r.planner());if(groups.size()!=22)throw new AssertionError("R4_LITERAL_GROUPS|"+groups.size());return List.copyOf(out);
	}
	static List<Expected> group(List<Row> rows)throws Exception{Map<String,List<Row>>g=new LinkedHashMap<>();for(Row r:rows)g.computeIfAbsent(r.planner()+'|'+r.fixture(),x->new ArrayList<>()).add(r);List<Expected>o=new ArrayList<>();for(var e:g.entrySet())o.add(expected(e.getValue()));return List.copyOf(o);}
	private static Row parseRow(String line)throws Exception{
		int p=line.indexOf("|planner="),k=line.indexOf('|',p+9),kindEnd=line.indexOf('|',k+1);if(p<1||k<0||kindEnd<0)throw new AssertionError("R4_LITERAL_PREFIX");
		String fixture=line.substring(0,p),planner=line.substring(p+9,k),kind=line.substring(k+1,kindEnd);Matcher m=FIELD.matcher(line.substring(k));List<Integer>s=new ArrayList<>();List<String>n=new ArrayList<>();while(m.find()){s.add(k+m.start());n.add(m.group(1));}
		List<List<String>> allowed=SCHEMAS.get(kind);if(allowed==null||allowed.stream().noneMatch(n::equals))throw new AssertionError("R4_LITERAL_FIELD_ORDER|"+fixture+'|'+kind+'|'+n);
		Map<String,String>f=new LinkedHashMap<>();for(int i=0;i<n.size();i++){int a=s.get(i)+n.get(i).length()+2,b=i+1<s.size()?s.get(i+1):line.length();if(f.put(n.get(i),line.substring(a,b))!=null)throw new AssertionError("R4_LITERAL_DUPLICATE_FIELD|"+n.get(i));}
		return new Row(fixture,planner,kind,Collections.unmodifiableMap(f),CampaignBContractProbe.sha256(line));
	}
	private static Expected expected(List<Row> rows)throws Exception{
		String planner=rows.get(0).planner(),fixture=rows.get(0).fixture();Map<String,String>a=new TreeMap<>(),facts=new LinkedHashMap<>();List<String>rel=new ArrayList<>(),obl=new ArrayList<>(),anc=new ArrayList<>(),reg=new ArrayList<>(),obj=new ArrayList<>(),ties=new ArrayList<>(),trtw=new ArrayList<>(),bound=new ArrayList<>(),clone=new ArrayList<>(),struct=new ArrayList<>();
		for(Row r:rows){for(var e:r.fields().entrySet()){String atom=r.kind()+'.'+e.getKey()+'='+e.getValue();facts.put(r.digest()+'.'+e.getKey(),e.getValue());categorize(r,e.getKey(),e.getValue(),atom,a,rel,obl,anc,reg,obj,ties,trtw,bound,clone,struct);}}
		String fp=CampaignBContractProbe.sha256(planner+'|'+fixture+'|'+a+'|'+rel+'|'+obl+'|'+anc+'|'+reg+'|'+obj+'|'+ties+'|'+trtw+'|'+bound+'|'+clone+'|'+struct+'|'+facts);
		return new Expected(planner,fixture,List.copyOf(rows),Map.copyOf(a),sorted(rel),sorted(obl),sorted(anc),sorted(reg),List.copyOf(obj),List.copyOf(ties),sorted(trtw),sorted(bound),sorted(clone),sorted(struct),Map.copyOf(facts),fp);
	}
	private static void categorize(Row r,String k,String v,String atom,Map<String,String>a,List<String>rel,List<String>obl,List<String>anc,List<String>reg,List<String>obj,List<String>ties,List<String>trtw,List<String>bound,List<String>clone,List<String>struct){String l=k.toLowerCase();
		if(k.equals("selectedStates"))for(String x:stripList(v))putState(a,x);else if(k.equals("key")&&r.fields().containsKey("finalExec"))a.put(v,r.fields().get("finalExec")+'/'+r.fields().get("finalOutput"));
		if(r.kind().startsWith("REGISTRY_")){reg.add(r.kind()+'|'+atom);if(k.toLowerCase().contains("anchor"))anc.add(atom);if(v.contains("FROZEN_SELECTED_D"))rel.add(atom);if(v.contains("FROZEN_SELECTED_U"))obl.add(atom);}
		if(l.contains("objective")||l.contains("cost")||l.contains("capacity"))obj.add(atom);
		if(l.contains("tie")||l.contains("order")||l.contains("ordinal")||l.equals("selected"))ties.add(atom);
		if(l.contains("anchor"))anc.add(atom);
		if(l.contains("semantic")){struct.add(atom);if(v.toLowerCase().contains("tread")||v.toLowerCase().contains("twrite")||v.contains("reads=[")||v.contains("writes=["))trtw.add(atom);if(v.toLowerCase().contains("branch")||v.toLowerCase().contains("loop")||v.toLowerCase().contains("function"))bound.add(atom);if(v.toLowerCase().contains("clone")||v.toLowerCase().contains("recompile"))clone.add(atom);}
		if(l.contains("reason")||l.contains("caps")||l.contains("nodekind")||l.contains("emittedwork"))struct.add(atom);
	}
	static TypedPlan actual(Expected expected,R4CostAdapterBridge.Selection s)throws Exception{
		return R4CostTypedExtractor.extract(expected,s);
	}
	static TypedPlan actual(Expected expected,List<R4CostAdapterBridge.Selection> selections)throws Exception{
		return R4CostTypedExtractor.extract(expected,selections);
	}
	static TypedPlan fromDerived(Expected schema,Map<String,String> derived)throws Exception{
		Map<String,String>a=new TreeMap<>(),facts=new LinkedHashMap<>();List<String>rel=new ArrayList<>(),obl=new ArrayList<>(),anc=new ArrayList<>(),reg=new ArrayList<>(),obj=new ArrayList<>(),ties=new ArrayList<>(),trtw=new ArrayList<>(),bound=new ArrayList<>(),clone=new ArrayList<>(),struct=new ArrayList<>();
		for(Row schemaRow:schema.rows()){
			Map<String,String> rowValues=new LinkedHashMap<>();
			for(String field:schemaRow.fields().keySet()){
				String coordinate=schemaRow.digest()+'.'+field,value=derived.get(coordinate);
				if(value==null)throw new AssertionError("R4_TYPED_EVIDENCE_MISSING|"+coordinate);
				rowValues.put(field,value);facts.put(coordinate,value);
			}
			Row derivedRow=new Row(schemaRow.fixture(),schemaRow.planner(),schemaRow.kind(),Map.copyOf(rowValues),schemaRow.digest());
			for(var entry:rowValues.entrySet())categorize(derivedRow,entry.getKey(),entry.getValue(),
				schemaRow.kind()+'.'+entry.getKey()+'='+entry.getValue(),a,rel,obl,anc,reg,obj,ties,trtw,bound,clone,struct);
		}
		String fp=CampaignBContractProbe.sha256(schema.planner()+'|'+schema.fixture()+'|'+a+'|'+rel+'|'+obl+'|'+anc+'|'+reg+'|'+obj+'|'+ties+'|'+trtw+'|'+bound+'|'+clone+'|'+struct+'|'+facts);
		return new TypedPlan(schema.planner(),schema.fixture(),Map.copyOf(a),sorted(rel),sorted(obl),sorted(anc),sorted(reg),List.copyOf(obj),List.copyOf(ties),sorted(trtw),sorted(bound),sorted(clone),sorted(struct),Map.copyOf(facts),fp);
	}

	static List<Diff> compare(Expected e,TypedPlan a){List<Diff>d=new ArrayList<>();if(!e.planner().equals(a.planner()))d.add(diff(e,a,Surface.OBJECTIVE,"planner",e.planner(),a.planner()));if(!e.fixture().equals(a.fixture()))d.add(diff(e,a,Surface.STRUCTURAL,"fixture",e.fixture(),a.fixture()));cmpMap(d,e,a,Surface.ASSIGNMENT,e.assignments(),a.assignments());cmpList(d,e,a,Surface.RELOCATION,e.relocations(),a.relocations(),false);cmpList(d,e,a,Surface.OBLIGATION,e.obligations(),a.obligations(),false);cmpList(d,e,a,Surface.ANCHOR,e.anchors(),a.anchors(),false);cmpList(d,e,a,Surface.REGISTRY,e.registries(),a.registries(),false);cmpList(d,e,a,Surface.OBJECTIVE,e.objective(),a.objective(),true);cmpList(d,e,a,Surface.ORDERED_TIE,e.ties(),a.ties(),true);cmpList(d,e,a,Surface.TRTW,e.trtw(),a.trtw(),false);cmpList(d,e,a,Surface.BOUNDARY,e.boundaries(),a.boundaries(),false);cmpList(d,e,a,Surface.CLONE_RECOMPILE,e.clones(),a.clones(),false);cmpList(d,e,a,Surface.STRUCTURAL,e.structural(),a.structural(),false);cmpMap(d,e,a,Surface.LITERAL_FIELD,e.facts(),a.facts());if(!Objects.equals(e.fingerprint(),a.fingerprint()))d.add(diff(e,a,Surface.FINGERPRINT,"plan",e.fingerprint(),a.fingerprint()));return List.copyOf(d);}
	private static void cmpMap(List<Diff>d,Expected e,TypedPlan a,Surface s,Map<String,String>x,Map<String,String>y){Set<String>k=new LinkedHashSet<>(x.keySet());k.addAll(y.keySet());for(String z:k)if(!Objects.equals(x.get(z),y.get(z)))d.add(diff(e,a,s,z,String.valueOf(x.get(z)),String.valueOf(y.get(z))));}
	private static void cmpList(List<Diff>d,Expected e,TypedPlan a,Surface s,List<String>x,List<String>y,boolean ordered){if(!x.equals(y))d.add(diff(e,a,s,ordered?"ordered":"set",x.toString(),y.toString()));}
	private static Diff diff(Expected e,TypedPlan a,Surface s,String k,String x,String y){return new Diff(e.planner(),e.fixture(),s,k,x,y,s==Surface.OBJECTIVE?x+"->"+y:"",s==Surface.ORDERED_TIE?x+"->"+y:"","FIELD_MISMATCH");}
	static void assertBijection(List<Diff>d,List<LedgerRow>l){Set<String>s=new LinkedHashSet<>();for(var r:l)if(!s.add(r.diff().identity()))throw new AssertionError("DUPLICATE_LEDGER_ROW|"+r.diff().identity());Set<String>a=new LinkedHashSet<>();for(var x:d)a.add(x.identity());Set<String>miss=new LinkedHashSet<>(a);miss.removeAll(s);Set<String>unused=new LinkedHashSet<>(s);unused.removeAll(a);if(!miss.isEmpty())throw new AssertionError("UNAUTHORIZED_DIFFERENCE|"+miss.iterator().next());if(!unused.isEmpty())throw new AssertionError("UNCONSUMED_LEDGER_ROW|"+unused.iterator().next());}
	static void expect(String code,Runnable r){try{r.run();throw new AssertionError("NEGATIVE_CONTROL_DID_NOT_FAIL|"+code);}catch(AssertionError e){if(e.getMessage()==null||!e.getMessage().startsWith(code))throw e;}}
	private static void putState(Map<String,String>a,String x){int p=x.lastIndexOf('=');if(p>0)a.put(x.substring(0,p),x.substring(p+1));}
	private static List<String> stripList(String v){if(v.length()<2)return List.of();return List.of(v.substring(1,v.length()-1).split(", "));}
	private static List<String> sorted(List<String>x){return x.stream().distinct().sorted().toList();}
	private static void prefix(List<String>o,String p,List<String>x){for(String v:x)o.add(p+'|'+v);}
	private static Map<String,List<List<String>>> schemas(){Map<String,List<List<String>>>m=new LinkedHashMap<>();
		put(m,"DP_ROOT_OBJECTIVE","evidence,key,seed,lout,fout,objective,selected,tieRule");put(m,"DP_ANCHOR_CAPABILITY","evidence,key,seed,lout,fout,objective,selected,tieRule,missingAnchorCapable,concreteAnchorCapable,missingSelection,concreteSelection,selectedRegistry,registryProducedByFrozenPolicy");
		put(m,"DP_VARIANT_ORDER","evidence,key,seed,rank0Cost,rank1Cost,equal,selectedInsertionOrdinal,selectedChildOutput","evidence,seed,key,cp,fed,selectedExec,selectedOutput,runtimeOutputConstraint");
		put(m,"DP_FULL_OFFLINE_SELECTION","evidence,seed,fixture,rootObjective,observedFloatNormalization,observedFloatTolerance,rootChildren,decisionCount,conflictCount,rewrittenCount,selectedStates,selectedPlans,semanticFacts,registrySnapshots");put(m,"NEUTRAL_GRAPH_EXCLUSION","seed,fixture,nodeKind,emittedWork,excludedState,reason");
		put(m,"MINST_CUT_EDGE","evidence,from,to,capacity");put(m,"MINST_CUT_OBJECTIVE","evidence,seed,capacity,sourceCount,reverseCapacity,orderControl,tieLimit");put(m,"MINST_CAPS_REPAIR","evidence,seed,key,caps,rawExec,rawOutput,finalExec,finalOutput,capabilityGateApplied,fullPathParity,repair");put(m,"MINST_CAPABILITY_GATE","evidence,seed,key,capsBefore,concreteAnchor,finalExec,finalOutput,reason");put(m,"MINST_FINAL_STATE","evidence,seed,key,rawExec,rawOutput,finalExec,finalOutput,fType,repair");put(m,"MINST_FULL_OFFLINE_SELECTION","evidence,seed,fixture,selectedStates,semanticFacts");
		put(m,"REGISTRY_REFED","evidence,producer,consumers,scope,anchorHop,anchorKey,source");put(m,"REGISTRY_FOUT_MATERIALIZE","evidence,producer,scope,fType,anchorLabel,anchorKey,source");put(m,"REGISTRY_LOCAL_MATERIALIZE","evidence,producer,consumers,scope,consumerCount,fType,reason,source");return Map.copyOf(m);}
	private static void put(Map<String,List<List<String>>>m,String k,String...s){List<List<String>>v=new ArrayList<>();for(String x:s)v.add(List.of(x.split(",")));m.put(k,List.copyOf(v));}
	private CampaignBLiteralAuthority(){}
}
