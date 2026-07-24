/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Dedicated structural fixtures. Every marker and special role is resolved by typed graph facts. */
final class CampaignBProvenanceFixtureBridge {
	record CandidateAtom(CompiledHopKey node,PlacementState state){String normalizedSignature(){return atom(node,state.normalizedSignature());}}
	enum ShapeBasis { KNOWN_COMPATIBLE_DIMENSIONS, SHAPE_INDEPENDENT_BROADCAST, UNTRUSTED_SHAPE_INDEPENDENT_LABEL }
	enum RelocationKind { LOCAL_UPLOAD_EXISTING_DURABLE_ANCHOR, DOWNLOAD_UNSUPPORTED }
	enum ObligationReason { EXISTING_FEDERATION_MAP_COMPATIBLE, UNTRUSTED_SHAPE_LABEL }
	record CandidateProof(CandidateAtom atom,CompiledHopKey provenNode,PlacementState state,DurableAnchorKey anchor,FType supportedFType,
		long rows,long cols,ShapeBasis shapeBasis,RelocationKind relocationKind,RelocationActionKey relocation,
		ObligationKey obligation,ObligationReason obligationReason,CompiledHopKey provenanceMarker,ValueVersionKey provenanceValue) {
		String candidate(){return atom.normalizedSignature();}
	}
	record Fixture(String id,DMLProgram program,PlacementAnalysis analysis,CompiledHopKey markerKey,ValueVersionKey marker,
		Set<CompiledHopKey> descendants,Set<CompiledHopKey> unrelated,Set<CompiledHopKey> independent,
		Map<String,CompiledHopKey> roles,Map<String,DurableAnchorKey> anchors,Map<String,List<String>> baseAlternatives,
		Map<String,CandidateProof> candidateProofs,Set<String> removedCandidates,Set<String> removedRelocations,String structuralDigest) { }
	private static final List<String> IDS=List.of("H-01-TWRITE-TREAD","H-02-BRANCH-JOIN","H-03-LOOP-RECOMPILE",
		"H-04-FUNCTION-CALLSITE","H-05-CLONE-FAMILY","H-06-DYNAMIC-REWRITE","H-07-VARIABLE-REUSE",
		"H-08-LATER-ANCHOR-NO-REFED","H-09-INDEPENDENT-ANCHOR-RELEASE","H-10-SAME-SHAPE-DISTINCT-ANCHORS");
	static List<String> ids(){return IDS;}

	static Fixture fresh(String id)throws Exception{
		DMLProgram program=id.equals("H-07-VARIABLE-REUSE")?compileReuse():(id.equals("H-05-CLONE-FAMILY")||id.equals("H-06-DYNAMIC-REWRITE"))?compileClone(id):compile(script(id));PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(program);
		NeutralPlacementGraph graph=analysis.graph();if(graph.nodes().isEmpty())bad(id,"nodes");
		Map<String,CompiledHopKey> roles=new LinkedHashMap<>();Map<String,DurableAnchorKey> anchors=new LinkedHashMap<>();
		NeutralPlacementGraph.Node marker=resolveMarker(id,analysis,roles,anchors);
		Set<CompiledHopKey> descendants=closure(graph,marker.key());
		Set<CompiledHopKey> unrelated=new LinkedHashSet<>();for(var n:graph.nodes())if(!descendants.contains(n.key()))unrelated.add(n.key());
		Set<CompiledHopKey> independent=new LinkedHashSet<>();
		for(var n:graph.nodes())if(unrelated.contains(n.key())&&!n.anchors().isEmpty())independent.add(n.key());
		if(id.contains("INDEPENDENT")&&independent.isEmpty())bad(id,"Y_INDEPENDENT");
		DurableAnchorKey policyAnchor=policyAnchor(id,graph,anchors);
		String analysisFingerprintBefore=analysis.analysisFingerprint();
		Map<String,List<String>> base=new LinkedHashMap<>();Map<String,CandidateProof> candidateProofs=new LinkedHashMap<>();Set<String> removed=new LinkedHashSet<>();
		for(var n:graph.nodes()){
			Set<String> states=new java.util.TreeSet<>();n.legalAlternatives().stream().map(s->s.normalizedSignature()).forEach(states::add);
			if(descendants.contains(n.key()))candidateProof(analysis,n,policyAnchor,marker.key()).ifPresent(p->{states.add(p.state().normalizedSignature());removed.add(p.candidate());candidateProofs.put(p.candidate(),p);});
			base.put(n.key().normalizedSignature(),List.copyOf(states));
		}
		Set<ValueVersionKey> values=new LinkedHashSet<>();for(var n:graph.nodes())if(descendants.contains(n.key()))values.add(n.valueVersion());
		Set<String> removedReloc=new LinkedHashSet<>();for(var r:graph.relocationActions())if(values.contains(r.key().sourceValueVersion()))removedReloc.add(r.key().normalizedSignature());
		if(descendants.isEmpty()||unrelated.isEmpty())bad(id,"descendant-partition");
		validateSpecial(id,graph,marker,descendants,unrelated,independent,roles,anchors,base);
		if(removed.isEmpty())bad(id,"VACUOUS_NO_REFED_POLICY");
		if(!candidateProofs.keySet().equals(removed))bad(id,"CANDIDATE_PROOF_BIJECTION");
		for(String candidate:removed)if(descendants.stream().noneMatch(k->candidate.startsWith(k.normalizedSignature()+"=")))bad(id,"GLOBAL_NO_REFED_REMOVAL");
		String digest=R4Heuristic2Probe.sha256(id+'|'+marker.key().normalizedSignature()+'|'+sorted(descendants)+'|'+sorted(unrelated)+'|'+roles+'|'+anchors+'|'+base+'|'+candidateProofs+'|'+removed+'|'+removedReloc);
		Fixture fixture=new Fixture(id,program,analysis,marker.key(),marker.valueVersion(),Set.copyOf(descendants),Set.copyOf(unrelated),
			Set.copyOf(independent),Map.copyOf(roles),Map.copyOf(anchors),Map.copyOf(base),Map.copyOf(candidateProofs),Set.copyOf(removed),Set.copyOf(removedReloc),digest);
		org.junit.Assert.assertEquals("proof analysis must be mutation-free "+id,analysisFingerprintBefore,analysis.analysisFingerprint());
		org.junit.Assert.assertEquals("literal exact structural oracle "+id,R4Heuristic2LiteralExpectations.exact(id),literalDescription(fixture));
		return fixture;
	}
	private static DurableAnchorKey policyAnchor(String id,NeutralPlacementGraph graph,Map<String,DurableAnchorKey> anchors){
		if(id.contains("DISTINCT-ANCHORS"))return anchors.get("ANCHOR_A");
		return requireUnique(id,"POLICY_DURABLE_ANCHOR",graph.nodes().stream().flatMap(n->n.anchors().stream()).distinct().toList());
	}
	static java.util.Optional<CandidateProof> candidateProof(PlacementAnalysis analysis,NeutralPlacementGraph.Node n,DurableAnchorKey anchor,CompiledHopKey marker){
		if(analysis==null||n==null||marker==null||!n.emittedWork()||!n.anchors().isEmpty()||anchor==null||!concreteAnchor(anchor)
			||selfOrVariableAnchor(anchor,n)||!supported(anchor.fType())||!closure(analysis.graph(),marker).contains(n.key()))return java.util.Optional.empty();
		Hop hop=analysis.hop(n.key()).orElse(null);long[] shape=compatibleShape(hop,anchor);if(shape==null)return java.util.Optional.empty();
		boolean boundary=n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_READ||n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE||"recompile".equals(n.key().recompileContext());
		PlacementState state=new PlacementState(boundary?ExecType.FED:ExecType.CP,FederatedOutput.FOUT,anchor.fType(),anchor.fType()!=FType.BROADCAST);
		CandidateAtom atom=new CandidateAtom(n.key(),state);String candidate=atom.normalizedSignature();RelocationActionKey relocation=new RelocationActionKey(n.valueVersion(),state,anchor,n.key().controlRegion().normalizedSignature(),List.of(n.key()));
		ObligationKey obligation=new ObligationKey(n.key(),0,n.valueVersion(),state,relocation,n.key().recompileContext());
		CandidateProof proof=new CandidateProof(atom,n.key(),state,anchor,anchor.fType(),shape[0],shape[1],anchor.fType()==FType.BROADCAST?ShapeBasis.SHAPE_INDEPENDENT_BROADCAST:ShapeBasis.KNOWN_COMPATIBLE_DIMENSIONS,RelocationKind.LOCAL_UPLOAD_EXISTING_DURABLE_ANCHOR,relocation,obligation,ObligationReason.EXISTING_FEDERATION_MAP_COMPATIBLE,marker,n.valueVersion());
		if(!valid(analysis,n,marker,proof))throw new IllegalArgumentException("INVALID_CANDIDATE_PROOF|"+candidate);return java.util.Optional.of(proof);
	}
	private static boolean supported(FType f){return Set.of(FType.ROW,FType.COL,FType.FULL,FType.BROADCAST).contains(f);}
	private static boolean selfOrVariableAnchor(DurableAnchorKey a,NeutralPlacementGraph.Node n){return a.placementId().startsWith("var:")||a.placementId().startsWith("self:")||a.placementId().equals(n.valueVersion().lexicalVariable());}
	private static boolean concreteAnchor(DurableAnchorKey a){
		if(a.placementId()==null||a.placementId().isBlank()||a.partitions()==null||a.partitions().isEmpty())return false;
		Set<String> partitions=new LinkedHashSet<>();for(var p:a.partitions()){
			if(p.workerId()==null||p.workerId().isBlank()||p.begin()==null||p.end()==null||p.begin().size()!=2||p.end().size()!=2)return false;
			if(!partitions.add(p.workerId()+'|'+p.begin().toString()+'|'+p.end().toString()))return false;
		}return true;
	}
	private static long[] compatibleShape(Hop hop,DurableAnchorKey anchor){if(anchor.fType()==FType.BROADCAST)return new long[]{-1,-1};if(hop==null||hop.getDataType()!=DataType.MATRIX||hop.getDim1()<=0||hop.getDim2()<=0)return null;long rows=hop.getDim1(),cols=hop.getDim2();return validGeometry(anchor,rows,cols)?new long[]{rows,cols}:null;}
	private static boolean validGeometry(DurableAnchorKey a,long rows,long cols){
		if(!concreteAnchor(a))return false;if(a.fType()==FType.BROADCAST)return rows==-1&&cols==-1;if(rows<=0||cols<=0)return false;
		List<long[]> spans=new ArrayList<>();for(var p:a.partitions()){if(p.begin().size()!=2||p.end().size()!=2)return false;long r0=p.begin().get(0),c0=p.begin().get(1),r1=p.end().get(0),c1=p.end().get(1);if(r0<0||c0<0||r1<=r0||c1<=c0||r1>rows||c1>cols)return false;
			if(a.fType()==FType.ROW){if(c0!=0||c1!=cols)return false;spans.add(new long[]{r0,r1});}
			else if(a.fType()==FType.COL){if(r0!=0||r1!=rows)return false;spans.add(new long[]{c0,c1});}
			else if(a.fType()==FType.FULL){if(r0!=0||c0!=0||r1!=rows||c1!=cols)return false;}
			else return false;}
		if(a.fType()==FType.FULL)return !a.partitions().isEmpty();spans.sort(java.util.Comparator.comparingLong(x->x[0]));long cursor=0;for(long[] span:spans){if(span[0]!=cursor)return false;cursor=span[1];}return cursor==(a.fType()==FType.ROW?rows:cols);
	}
	static boolean valid(PlacementAnalysis analysis,NeutralPlacementGraph.Node n,CompiledHopKey expectedMarker,CandidateProof p){
		if(p==null||analysis==null||n==null||expectedMarker==null||!closure(analysis.graph(),expectedMarker).contains(n.key()))return false;
		boolean boundary=n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_READ||n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE||"recompile".equals(n.key().recompileContext());
		PlacementState expectedState=new PlacementState(boundary?ExecType.FED:ExecType.CP,FederatedOutput.FOUT,p.anchor().fType(),p.anchor().fType()!=FType.BROADCAST);
		Hop hop=analysis.hop(n.key()).orElse(null);boolean exactShape=p.supportedFType()==FType.BROADCAST?p.rows()==-1&&p.cols()==-1:
			hop!=null&&hop.getDataType()==DataType.MATRIX&&hop.getDim1()==p.rows()&&hop.getDim2()==p.cols();
		DurableAnchorKey resolvedAnchor=resolvedPolicyAnchor(analysis.graph(),expectedMarker);
		return p.atom().node().equals(n.key())&&p.atom().state().equals(p.state())&&p.provenNode().equals(n.key())&&p.candidate().equals(atom(n.key(),p.state().normalizedSignature()))&&p.state().equals(expectedState)&&p.anchor().equals(resolvedAnchor)&&concreteAnchor(p.anchor())&&!selfOrVariableAnchor(p.anchor(),n)&&supported(p.supportedFType())&&p.supportedFType()==p.anchor().fType()
			&&exactShape&&validGeometry(p.anchor(),p.rows(),p.cols())&&(p.supportedFType()==FType.BROADCAST?p.shapeBasis()==ShapeBasis.SHAPE_INDEPENDENT_BROADCAST:p.shapeBasis()==ShapeBasis.KNOWN_COMPATIBLE_DIMENSIONS)
			&&p.relocationKind()==RelocationKind.LOCAL_UPLOAD_EXISTING_DURABLE_ANCHOR&&p.relocation().sourceValueVersion().equals(n.valueVersion())&&p.relocation().durableAnchor().equals(p.anchor())&&p.relocation().targetPlacement().equals(p.state())&&p.relocation().statementBlockScope().equals(n.key().controlRegion().normalizedSignature())&&p.relocation().compatibleConsumers().equals(List.of(n.key()))
			&&p.obligation().relocationAction().equals(p.relocation())&&p.obligation().consumer().equals(n.key())&&p.obligation().inputPosition()==0&&p.obligation().sourceValueVersion().equals(n.valueVersion())&&p.obligation().requiredPlacement().equals(p.state())&&p.obligation().callRecompileContext().equals(n.key().recompileContext())
			&&p.obligationReason()==ObligationReason.EXISTING_FEDERATION_MAP_COMPATIBLE&&p.provenanceMarker().equals(expectedMarker)&&p.provenanceValue().equals(n.valueVersion());
	}
	private static DurableAnchorKey resolvedPolicyAnchor(NeutralPlacementGraph graph,CompiledHopKey expectedMarker){
		List<DurableAnchorKey> all=graph.nodes().stream().flatMap(x->x.anchors().stream()).distinct().toList();
		if(all.size()==1)return all.get(0);
		List<DurableAnchorKey> upstream=new ArrayList<>();
		for(var node:graph.nodes())if(!node.anchors().isEmpty()&&closure(graph,node.key()).contains(expectedMarker))upstream.addAll(node.anchors());
		List<DurableAnchorKey> distinct=upstream.stream().distinct().toList();
		return distinct.size()==1?distinct.get(0):null;
	}

	static boolean unknownShapeFixtureIsRejected()throws Exception{
		String fed="federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)))";
		PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(compile("A="+fed+";X=A+1;print(sum(X));"));
		DurableAnchorKey anchor=requireUnique("H-NEG-UNKNOWN","ANCHOR",analysis.graph().nodes().stream().flatMap(n->n.anchors().stream()).distinct().toList());
		NeutralPlacementGraph.Node node=one("H-NEG-UNKNOWN","X_UNKNOWN",analysis.graph().nodes().stream().filter(n->n.emittedWork()&&n.anchors().isEmpty()&&n.key().canonicalSourceOrigin()!=null&&n.key().canonicalSourceOrigin().endsWith(":X")&&analysis.hop(n.key()).filter(h->h.getDataType()==DataType.MATRIX&&(h.getDim1()<=0||h.getDim2()<=0)).isPresent()).toList());
		String before=analysis.analysisFingerprint();boolean rejected=candidateProof(analysis,node,anchor,node.key()).isEmpty();return rejected&&before.equals(analysis.analysisFingerprint());
	}

	private static NeutralPlacementGraph.Node resolveMarker(String id,PlacementAnalysis analysis,Map<String,CompiledHopKey> roles,
		Map<String,DurableAnchorKey> anchors){
		NeutralPlacementGraph g=analysis.graph();
		List<NeutralPlacementGraph.Node> ns=g.nodes();NeutralPlacementGraph.Node m;
		if(id.equals("H-01-TWRITE-TREAD"))m=one(id,"X_WRITE",ns.stream().filter(n->n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE&&"X".equals(n.valueVersion().lexicalVariable())).toList());
		else if(id.equals("H-02-BRANCH-JOIN")){var join=one(id,"JOIN_PHI",ns.stream().filter(n->n.valueVersion().versionKind()==VersionKind.BRANCH_JOIN_PHI).toList());roles.put("JOIN_PHI",join.key());CompiledHopKey source=requireUnique(id,"BRANCH_IF_SOURCE",g.constraints().stream().filter(c->c.right().equals(join.key())&&c.left().callSitePath().contains("branch-if")).map(c->c.left()).distinct().toList());m=g.node(source).orElseThrow();}
		else if(id.equals("H-03-LOOP-RECOMPILE")){m=one(id,"LOOP_BACKEDGE_X",ns.stream().filter(n->n.valueVersion().versionKind()==VersionKind.LOOP_BACKEDGE&&"X".equals(n.valueVersion().lexicalVariable())).toList());roles.put("LOOP_BACKEDGE_X",m.key());}
		else if(id.equals("H-04-FUNCTION-CALLSITE")){var input=one(id,"FUNCTION_INPUT",ns.stream().filter(n->n.kind()==NeutralPlacementGraph.NodeKind.FUNCTION_INPUT).toList());roles.put("FUNCTION_INPUT",input.key());m=one(id,"CALLSITE_X_READ",ns.stream().filter(n->n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_READ&&"X".equals(n.valueVersion().lexicalVariable())&&"main/1".equals(n.key().callSitePath())).toList());roles.put("CALLSITE_X_READ",m.key());}
		else if(id.equals("H-05-CLONE-FAMILY")||id.equals("H-06-DYNAMIC-REWRITE")){List<NeutralPlacementGraph.Node> c=ns.stream().filter(n->n.valueVersion().versionKind()==VersionKind.CLONE_RECOMPILE&&n.kind()==NeutralPlacementGraph.NodeKind.CLONE).toList();m=one(id,"CLONE_RECOMPILE",c);roles.put("CLONE_RECOMPILE",m.key());}
		else if(id.equals("H-07-VARIABLE-REUSE")){List<NeutralPlacementGraph.Node> reuse=ns.stream().filter(n->"X".equals(n.valueVersion().lexicalVariable())&&n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE).toList();if(reuse.size()!=2)bad(id,"X_VERSIONS");int old=reuse.stream().mapToInt(n->n.valueVersion().definitionOrdinal()).min().orElseThrow(),fresh=reuse.stream().mapToInt(n->n.valueVersion().definitionOrdinal()).max().orElseThrow();m=one(id,"X_OLD",reuse.stream().filter(n->n.valueVersion().definitionOrdinal()==old).toList());var newest=one(id,"X_NEW",reuse.stream().filter(n->n.valueVersion().definitionOrdinal()==fresh).toList());roles.put("X_OLD",m.key());roles.put("X_NEW",newest.key());}
		else if(id.equals("H-10-SAME-SHAPE-DISTINCT-ANCHORS")){NeutralPlacementGraph.Node an=one(id,"ANCHOR_A_NODE",ns.stream().filter(n->"A".equals(n.valueVersion().lexicalVariable())&&n.anchors().size()==1).toList());NeutralPlacementGraph.Node b=one(id,"ANCHOR_B_NODE",ns.stream().filter(n->"B".equals(n.valueVersion().lexicalVariable())&&n.anchors().size()==1).toList());Set<CompiledHopKey>aLineage=closure(g,an.key());m=one(id,"X_MARKER",ns.stream().filter(n->aLineage.contains(n.key())&&n.kind()==NeutralPlacementGraph.NodeKind.OPERATION&&n.emittedWork()&&n.anchors().isEmpty()&&analysis.hop(n.key()).filter(h->h.getDataType()==DataType.MATRIX&&h.getDim1()==4&&h.getDim2()==2).isPresent()).toList());roles.put("X_MARKER",m.key());roles.put("ANCHOR_A_NODE",an.key());roles.put("ANCHOR_B_NODE",b.key());anchors.put("ANCHOR_A",requireUnique(id,"ANCHOR_A_KEY",an.anchors()));anchors.put("ANCHOR_B",requireUnique(id,"ANCHOR_B_KEY",b.anchors()));}
		else {List<NeutralPlacementGraph.Node> local=ns.stream().filter(n->n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE&&"X".equals(n.valueVersion().lexicalVariable())&&n.anchors().isEmpty()).toList();m=one(id,"X_MARKER",local);roles.put("X_MARKER",m.key());}
		roles.putIfAbsent("MARKER",m.key());if(!m.anchors().isEmpty())anchors.putIfAbsent("MARKER_ANCHOR",requireUnique(id,"MARKER_ANCHOR",m.anchors()));return m;
	}
	private static void validateSpecial(String id,NeutralPlacementGraph g,NeutralPlacementGraph.Node marker,Set<CompiledHopKey>d,
		Set<CompiledHopKey>u,Set<CompiledHopKey>ind,Map<String,CompiledHopKey>roles,Map<String,DurableAnchorKey>a,Map<String,List<String>>base){
		if(id.contains("TWRITE")&&g.nodes().stream().noneMatch(n->n.kind()==NeutralPlacementGraph.NodeKind.TRANSIENT_READ))bad(id,"X_READ_SAME_VERSION");
		if(id.contains("LOOP")&&g.nodes().stream().noneMatch(n->n.valueVersion().versionKind()==VersionKind.LOOP_BACKEDGE))bad(id,"LOOP_BACKEDGE");
		if(id.contains("FUNCTION")&&g.nodes().stream().noneMatch(n->n.kind()==NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT))bad(id,"FUNCTION_OUTPUT");
		if(id.contains("VARIABLE-REUSE")&&d.contains(roles.get("X_NEW")))bad(id,"new-version-unrelated");
		if(id.contains("INDEPENDENT")){CompiledHopKey y=ind.stream().filter(k->base.get(k.normalizedSignature()).stream().anyMatch(s->s.contains("FED")&&s.contains("FOUT"))).findFirst().orElseThrow(()->new AssertionError("FIXTURE_ORACLE_INVALID|case="+id+"|role=Y_FED_FOUT"));roles.put("Y_INDEPENDENT",y);if(d.contains(y))bad(id,"X-to-Y-path");}
		if(id.contains("DISTINCT-ANCHORS")){if(a.get("ANCHOR_A").equals(a.get("ANCHOR_B")))bad(id,"durable-anchor-identity");if(a.get("ANCHOR_A").fType()!=a.get("ANCHOR_B").fType())bad(id,"equal-ftype");}
	}
	private static Set<CompiledHopKey> closure(NeutralPlacementGraph g,CompiledHopKey m){Set<CompiledHopKey>s=new LinkedHashSet<>();ArrayDeque<CompiledHopKey>q=new ArrayDeque<>();q.add(m);while(!q.isEmpty()){var k=q.remove();if(!s.add(k))continue;for(var c:g.constraints())if(c.left().equals(k)&&Set.of(NeutralPlacementGraph.ConstraintKind.DOMINATES,NeutralPlacementGraph.ConstraintKind.SAME_ORIGIN,NeutralPlacementGraph.ConstraintKind.SAME_PLACEMENT,NeutralPlacementGraph.ConstraintKind.CONJUNCTIVE).contains(c.kind()))q.add(c.right());}return s;}
	private static NeutralPlacementGraph.Node one(String id,String role,List<NeutralPlacementGraph.Node>x){return requireUnique(id,role,x);}
	static <T>T requireUnique(String id,String role,List<T>x){if(x.size()!=1)throw new AssertionError("FIXTURE_ROLE_AMBIGUOUS|case="+id+"|role="+role+"|count="+x.size()+"|candidates="+x);return x.get(0);}
	private static void bad(String id,String role){throw new AssertionError("FIXTURE_ORACLE_INVALID|case="+id+"|role="+role);}
	private static String atom(CompiledHopKey k,String state){return k.normalizedSignature()+"="+state;}
	private static List<String> sorted(Set<CompiledHopKey>x){return x.stream().map(CompiledHopKey::normalizedSignature).sorted().toList();}
	static String literalDescription(Fixture f)throws Exception{Set<String> candidates=new java.util.TreeSet<>();for(var e:f.baseAlternatives().entrySet())for(String s:e.getValue())candidates.add(e.getKey()+"="+s);candidates.removeAll(f.removedCandidates());List<String> exclusions=f.removedCandidates().stream().sorted().map(x->"NO_REFED|"+x+"|proof="+proofSignature(f.candidateProofs().get(x))+"|marker="+f.marker().normalizedSignature()).toList();return "LIT|"+f.id()+"|roles="+f.roles().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+"=>"+e.getValue().normalizedSignature()).toList()+"|anchors="+f.anchors().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+"=>"+e.getValue().normalizedSignature()).toList()+"|d="+f.descendants().size()+":"+R4Heuristic2Probe.sha256(f.descendants().stream().map(x->x.normalizedSignature()).sorted().toList().toString())+"|u="+f.unrelated().size()+":"+R4Heuristic2Probe.sha256(f.unrelated().stream().map(x->x.normalizedSignature()).sorted().toList().toString())+"|c="+candidates.size()+":"+R4Heuristic2Probe.sha256(candidates.toString())+"|e="+exclusions.size()+":"+R4Heuristic2Probe.sha256(exclusions.toString());}
	static String proofSignature(CandidateProof p){return p.provenNode().normalizedSignature()+";"+p.anchor().normalizedSignature()+";"+p.supportedFType()+";"+p.rows()+"x"+p.cols()+";"+p.shapeBasis()+";"+p.relocationKind()+";"+p.relocation().normalizedSignature()+";"+p.obligation().normalizedSignature()+";"+p.obligationReason()+";"+p.provenanceMarker().normalizedSignature()+";"+p.provenanceValue().normalizedSignature();}
	private static DMLProgram compile(String s)throws Exception{DMLProgram p=ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,s,new HashMap<>());DMLTranslator t=new DMLTranslator(p);t.liveVariableAnalysis(p);t.validateParseTree(p);t.constructHops(p);return p;}
	private static DMLProgram compileReuse()throws Exception{
		String fed="A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));print(sum(A));";
		DMLProgram p=compile(fed);ArrayList<StatementBlock> blocks=new ArrayList<>(p.getStatementBlocks());
		DataOp first=write("X",matrixInput(1));DataOp firstRead=new DataOp("X",DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,"X",4,2,-1,-1);
		DataOp second=write("X",matrixInput(2));DataOp secondRead=new DataOp("X",DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,"X",4,2,-1,-1);
		blocks.add(block(first));blocks.add(block(write("Z",firstRead)));blocks.add(block(second));blocks.add(block(write("W",secondRead)));p.setStatementBlocks(blocks);return p;
	}
	private static DMLProgram compileClone(String id)throws Exception{
		String fed="A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));print(sum(A));";
		DMLProgram p=compile(fed);ArrayList<StatementBlock> blocks=new ArrayList<>(p.getStatementBlocks());DataOp origin=write("X",matrixInput(1));DataOp clone=write("X",matrixInput(id.endsWith("DYNAMIC-REWRITE")?2:1));clone.setRequiresRecompile();blocks.add(block(origin));blocks.add(block(clone));p.setStatementBlocks(blocks);return p;
	}
	private static Hop matrixInput(int value){return new DataOp("M"+value,DataType.MATRIX,ValueType.FP64,OpOpData.TRANSIENTREAD,"M"+value,4,2,-1,-1);}
	private static DataOp write(String variable,Hop input){DataOp out=new DataOp(variable,input.getDataType(),input.getValueType(),input,OpOpData.TRANSIENTWRITE,variable);out.setDim1(input.getDim1());out.setDim2(input.getDim2());return out;}
	private static StatementBlock block(Hop root){StatementBlock b=new StatementBlock();b.setHops(new ArrayList<>(List.of(root)));return b;}
	private static String script(String id){String fed="federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)))";String anchor="A="+fed+";";return switch(id){
		case "H-01-TWRITE-TREAD"->anchor+"X=matrix(1,4,2);i=1;while(i<2){X=X+1;i=i+1;}Y=A+X;print(sum(Y));";
		case "H-02-BRANCH-JOIN"->anchor+"X=matrix(1,4,2);if(sum(X)>0){X=X+1;}else{X=X-1;}Y=A+X;print(sum(Y));";
		case "H-03-LOOP-RECOMPILE"->anchor+"X=matrix(1,4,2);i=1;while(i<3){X=X+1;i=i+1;}Y=A+X;print(sum(Y));";
			case "H-04-FUNCTION-CALLSITE"->"f=function(matrix[double] X)return(matrix[double] Y){Y=X;i=1;while(i<2){Y=Y+1;i=i+1;}}"+anchor+"X=matrix(1,4,2);Z1=f(X);Y=A+Z1;print(sum(Y));";
			case "H-05-CLONE-FAMILY"->"f=function(matrix[double] X)return(matrix[double] Y){Y=X;i=1;while(i<2){Y=Y+1;i=i+1;}}"+anchor+"X=matrix(1,4,2);Z1=f(X);Z2=f(X+1);Y=A+Z1+Z2;print(sum(Y));";
		case "H-06-DYNAMIC-REWRITE"->"f=function(matrix[double] X)return(matrix[double] Y){Y=X+1;}"+anchor+"X=matrix(1,4,2);i=1;while(i<2){X=f(X);i=i+1;}Y=A+X;print(sum(Y));";
		case "H-07-VARIABLE-REUSE"->anchor+"X=matrix(1,4,2);Z=X+1;X=matrix(2,4,2);W=X+1;Y=A+W;print(sum(Z)+sum(Y));";
			case "H-08-LATER-ANCHOR-NO-REFED"->"X=matrix(1,4,2);i=1;while(i<2){X=X+1;i=i+1;}"+anchor+"Z=X+1;Y=A+Z;print(sum(Y));";
			case "H-09-INDEPENDENT-ANCHOR-RELEASE"->"X=matrix(1,4,2);i=1;while(i<2){X=X+1;i=i+1;}Z=X+1;"+anchor+"Y=A+1;print(sum(Z)+sum(Y));";
		case "H-10-SAME-SHAPE-DISTINCT-ANCHORS"->"A="+fed+";B="+fed.replace("X1","Y1").replace("X2","Y2")+";X=matrix(sum(A),4,2);Y=matrix(sum(B),4,2);print(sum(X)+sum(Y));";
		default->throw new IllegalArgumentException(id);};}
	private CampaignBProvenanceFixtureBridge(){}
}
