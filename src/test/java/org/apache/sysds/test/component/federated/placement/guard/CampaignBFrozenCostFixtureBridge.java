/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.R4ExactPrivateCostDpFixtures;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.R4ExactPrivateCostMinstFixtures;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.junit.Assert;

/** Exact producer-bound all-22 input gate. */
final class CampaignBFrozenCostFixtureBridge {
	record RoleAlias(String literalKey,String role,CompiledHopKey compiledKey,long producerHopId) { }
	sealed interface CostSelectionInput permits DpMemoInput,MinstGraphInput,FullPathInput {
		R4CostAdapterBridge.Planner planner(); String fixtureId(); PlacementAnalysis analysis();
		List<RoleAlias> aliases(); String inputFingerprint(); Object producer();
	}
	record CutEdgeReceipt(String fromAlias,String toAlias,long capacityBits) { }
	record CutCertificate(long seed,long capacityBits,long reverseCapacityBits,int sourceCount,String orderControl,
		String tieLimit,List<CutEdgeReceipt> edges) { }
	record RepairCertificate(long seed,RoleAlias role,boolean cpLout,boolean cpFout,boolean fedLout,boolean fedFout,
		String rawExec,String rawOutput,String finalExec,String finalOutput,String fType,boolean concreteAnchor,String reason) { }
	record RegistryCertificate(Object spec,long scope,String source,RoleAlias producer) { }
	record FullPathCertificate(long seed,String fixtureId,String analysisFingerprint,long rootObjectiveBits,String floatNormalization,
		String floatTolerance,int occurrenceCount,int rootChildren,int decisionCount,int conflictCount,int rewrittenCount,
		List<String> selectedStates,List<String> selectedPlans,List<String> registrySnapshots,String semanticFacts,String nodeKind,
		List<String> caps,String reason) { }
	record DpMemoInput(String fixtureId,PlacementAnalysis analysis,FederatedPlannerDpMemoTable memo,Hop root,
		List<FedPlan> enumeratedPlans,FedPlan selectedPlan,List<RoleAlias> aliases,String inputFingerprint)
		implements CostSelectionInput {
		public R4CostAdapterBridge.Planner planner(){return R4CostAdapterBridge.Planner.DP;}
		public Object producer(){return memo;}
	}
	record MinstGraphInput(String fixtureId,PlacementAnalysis analysis,FederatedPlanMinSTGraph graph,
		Graph<Long,DefaultWeightedEdge> cutGraph,CutCertificate cutCertificate,List<RepairCertificate> repairCertificates,
		List<?> selectedObligations,List<?> registryReceipts,List<RegistryCertificate> registryCertificates,List<RoleAlias> aliases,
		String inputFingerprint) implements CostSelectionInput {
		public R4CostAdapterBridge.Planner planner(){return R4CostAdapterBridge.Planner.MIN_ST;}
		public Object producer(){return graph;}
	}
	record FullPathInput(R4CostAdapterBridge.Planner planner,String fixtureId,PlacementAnalysis analysis,
		List<RoleAlias> aliases,FullPathCertificate certificate,String inputFingerprint) implements CostSelectionInput {
		public Object producer(){return analysis;}
	}
	record Arm(String name,String bFixture,DMLProgram program,PlacementAnalysis analysis,
		Map<String,String> namedKeys,String fingerprint) { }
	record Fixture(String planner,String id,List<Arm> arms,List<CostSelectionInput> inputs,
		Map<String,String> preconditions,String digest) {
		CostSelectionInput input(){return inputs.get(0);}
	}

	private static final Map<String,String> FULL_PATH=Map.ofEntries(
		Map.entry("C2-DP-05-SHARED-DIAMOND","B-10"),Map.entry("C2-DP-06-TRTW-EXACT","B-09"),
		Map.entry("C2-DP-08-UNKNOWN-METADATA","B-21"),Map.entry("C2-MS-07-TRTW-SHARED-D","B-16"),
		Map.entry("C2-MS-08-LOOP-EQUAL-FIXPOINT","B-05"),Map.entry("C2-X-09-BRANCH-JOIN","B-02"),
		Map.entry("C2-X-10-FUNCTION-CALLSITE","B-07"),Map.entry("C2-X-11-CLONE-RECOMPILE","B-09"));

	static Fixture fresh(CampaignBLiteralAuthority.Expected expected)throws Exception{
		String id=expected.fixture(); R4CostAdapterBridge.Planner planner=expected.planner().equals("DP")?
			R4CostAdapterBridge.Planner.DP:R4CostAdapterBridge.Planner.MIN_ST;
		List<Arm> arms=new ArrayList<>(); List<CostSelectionInput> inputs=new ArrayList<>();
		for(var f:R4ExactPrivateCostDpFixtures.all()) if(f.id().equals(id)) inputs.add(dp(f));
		for(var f:R4ExactPrivateCostMinstFixtures.all()) if(f.id().equals(id)) inputs.add(minst(f));
		if(id.equals("C2-DP-04-ANCHOR-CONTRAST")) {
			Arm concrete;
			Arm missing;
			try{concrete=arm("CONCRETE_ANCHOR","B-11");missing=arm("MISSING_ANCHOR","B-12");}
			catch(Exception x){throw new AssertionError("R4_DP04_ARM_BUILD",x);}
			arms.add(concrete);arms.add(missing);
			try{inputs.add(dpArm(id+":CONCRETE",concrete,expected,true));}
			catch(Exception x){throw new AssertionError("R4_DP04_CONCRETE_PRODUCER",x);}
			try{inputs.add(dpArm(id+":MISSING",missing,expected,false));}
			catch(Exception x){throw new AssertionError("R4_DP04_MISSING_PRODUCER",x);}
		}
		else if(inputs.isEmpty()) { Arm a=arm("FULL_PATH",FULL_PATH.get(id));arms.add(a);inputs.add(full(planner,id,a,expected)); }
		Map<String,String> pre=Map.of("inputCount",String.valueOf(inputs.size()),"armCount",String.valueOf(arms.size()),
			"producerKinds",inputs.stream().map(x->x.producer().getClass().getName()).toList().toString());
		String digest=CampaignBContractProbe.sha256(expected.planner()+'|'+id+'|'+inputs.stream()
			.map(x->x.inputFingerprint()+":"+stableAliases(x.aliases())).toList()+'|'+pre);
		Fixture out=new Fixture(expected.planner(),id,List.copyOf(arms),List.copyOf(inputs),pre,digest);
		assertPreconditions(expected,out);return out;
	}

	static void assertPreconditions(CampaignBLiteralAuthority.Expected expected,Fixture fixture){
		Assert.assertFalse(fixture.inputs().isEmpty());
		Assert.assertEquals(expected.fixture().equals("C2-DP-04-ANCHOR-CONTRAST")?2:1,fixture.inputs().size());
		for(CostSelectionInput input:fixture.inputs()){
			Assert.assertNotNull(input.analysis());Assert.assertNotNull(input.producer());
			Assert.assertEquals(input.aliases().size(),new LinkedHashSet<>(input.aliases().stream().map(RoleAlias::literalKey).toList()).size());
			for(RoleAlias alias:input.aliases()) Assert.assertSame(input.analysis().hop(alias.compiledKey()).orElseThrow(),
				input.analysis().occurrences().stream().filter(o->o.key().equals(alias.compiledKey())).findFirst().orElseThrow().hop());
			if(input instanceof DpMemoInput d){Assert.assertTrue(d.aliases().stream().anyMatch(a->d.analysis().hop(a.compiledKey()).orElseThrow()==d.root()));
				for(FedPlan plan:d.enumeratedPlans())Assert.assertSame(d.root(),plan.getHopRef());
				Assert.assertTrue(d.enumeratedPlans().stream().anyMatch(p->p==d.selectedPlan()));}
			if(input instanceof MinstGraphInput m)for(RoleAlias alias:m.aliases()){
				Hop hop=m.analysis().hop(alias.compiledKey()).orElseThrow();var vertex=m.graph().getVertex(hop.getHopID());
				if(vertex!=null)Assert.assertSame(hop,vertex.getHopRef());
				else {Assert.assertEquals("cpFout",alias.role());Assert.assertFalse(m.registryReceipts().isEmpty());}}
		}
	}

	private static DpMemoInput dp(R4ExactPrivateCostDpFixtures.Fixture f)throws Exception{
		List<RoleAlias> aliases=aliases(f.analysis(),f.literalAliases(),f.namedRoles());
		return new DpMemoInput(f.id(),f.analysis(),f.memo(),f.root(),List.copyOf(f.enumeratedPlans()),f.selectedPlan(),aliases,
			CampaignBContractProbe.sha256(f.id()+"|"+f.analysis().analysisFingerprint()+"|"+stableAliases(aliases)+"|DP_MEMO"));
	}
	private static DpMemoInput dpArm(String id,Arm arm,CampaignBLiteralAuthority.Expected expected,
		boolean concreteAnchor)throws Exception{
		Map<String,Hop> literalHops=new LinkedHashMap<>();
		List<String> literalKeys=expected.assignments().isEmpty()?expected.rows().stream()
			.map(r->r.fields().get("key")).filter(java.util.Objects::nonNull).distinct().toList():
			expected.assignments().keySet().stream().toList();
		for(String literal:literalKeys) {
			var exact=arm.analysis().occurrences().stream().filter(o->o.key().normalizedSignature().equals(literal)).toList();
			var structural=arm.analysis().occurrences().stream().filter(o->concreteAnchor?
				(o.hop().getClass().getName().endsWith("BinaryOp")&&o.hop().getOpString().equals("b(+)")):
				(o.hop().getClass().getName().endsWith("DataGenOp")&&o.hop().getOpString().equals("dg(rand)"))).toList();
			if(exact.size()>1||exact.isEmpty()&&structural.size()!=1)throw new AssertionError("R4_DP04_TYPED_ROLE_BIJECTION|arm="+
				arm.name()+"|literal="+literal+"|exact="+exact.size()+"|structural="+structural.size());
			var occurrence=exact.isEmpty()?structural.get(0):exact.get(0);
			literalHops.put(literal,occurrence.hop());
		}
		Hop root=literalHops.values().iterator().next();
		return dp(R4ExactPrivateCostDpFixtures.anchorContrast(id,arm.analysis(),root,literalHops,concreteAnchor));
	}
	private static MinstGraphInput minst(R4ExactPrivateCostMinstFixtures.Fixture f)throws Exception{
		List<RoleAlias> aliases=aliases(f.analysis(),f.literalAliases(),f.namedRoles());
		return new MinstGraphInput(f.id(),f.analysis(),f.producerGraph(),f.cutGraph(),cutCertificate(f.cutGraph(),aliases),repairCertificates(f,aliases),List.copyOf(f.selectedObligationObjects()),
			List.copyOf(f.registryObjects()),registryCertificates(f,aliases),aliases,
			CampaignBContractProbe.sha256(f.id()+"|"+f.analysis().analysisFingerprint()+"|"+stableAliases(aliases)+"|MINST_GRAPH"));
	}
	private static FullPathInput full(R4CostAdapterBridge.Planner planner,String id,Arm arm,
		CampaignBLiteralAuthority.Expected expected){
		List<RoleAlias> aliases=new ArrayList<>();Set<String> literals=new LinkedHashSet<>(expected.assignments().keySet());
		expected.rows().stream().map(r->r.fields().get("key")).filter(java.util.Objects::nonNull).forEach(literals::add);
		for(String literal:literals){var match=arm.analysis().occurrences().stream().filter(x->x.key().normalizedSignature().equals(literal)).findFirst();
			if(match.isPresent()){var o=match.get();aliases.add(new RoleAlias(literal,"compiled",o.key(),o.hop().getHopID()));}
		}
		if(aliases.isEmpty()){var o=arm.analysis().occurrences().get(0);aliases.add(new RoleAlias("ANALYSIS:"+id,"compiled",o.key(),o.hop().getHopID()));}
		var roots=arm.program().getStatementBlocks().stream().flatMap(sb->sb.getHops()==null?java.util.stream.Stream.<Hop>empty():sb.getHops().stream()).toList();
		int children=roots.isEmpty()?0:roots.get(0).getInput().size();
		var states=arm.analysis().occurrences().stream().map(o->o.key().normalizedSignature()+"="+o.hop().getForcedExecType()+"/"+o.hop().getFederatedOutput()).sorted().toList();
		int occurrences=arm.analysis().occurrences().size(),conflicts=occurrences-new LinkedHashSet<>(arm.analysis().occurrences().stream().map(o->o.key().normalizedSignature()).toList()).size();
		var cert=new FullPathCertificate(-1L,id,arm.analysis().analysisFingerprint(),Double.doubleToLongBits(roots.isEmpty()?0d:roots.get(0).getOutputMemEstimate()),"DOUBLE_HEX","EXACT_BITS",
			occurrences,children,occurrences,conflicts,occurrences,states,states,List.of(),arm.analysis().analysisFingerprint(),
			arm.analysis().occurrences().get(0).hop().getClass().getName(),arm.analysis().occurrences().stream().map(o->o.hop().getClass().getSimpleName()+":"+o.hop().getOpString()).sorted().toList(),"ANALYSIS_CERTIFICATE");
		return new FullPathInput(planner,id,arm.analysis(),List.copyOf(aliases),cert,arm.fingerprint());
	}
	private static CutCertificate cutCertificate(Graph<Long,DefaultWeightedEdge> graph,List<RoleAlias> aliases){
		var solver=new org.jgrapht.alg.flow.PushRelabelMFImpl<Long,DefaultWeightedEdge>(graph);solver.calculateMinCut(-1L,-2L);
		List<CutEdgeReceipt> edges=solver.getCutEdges().stream().map(e->new CutEdgeReceipt(endpoint(graph.getEdgeSource(e),aliases,graph),
			endpoint(graph.getEdgeTarget(e),aliases,graph),Double.doubleToLongBits(graph.getEdgeWeight(e)))).sorted(java.util.Comparator.comparing(CutEdgeReceipt::fromAlias).thenComparing(CutEdgeReceipt::toAlias).thenComparingLong(CutEdgeReceipt::capacityBits)).toList();
		var reverse=new org.jgrapht.graph.DefaultDirectedWeightedGraph<Long,DefaultWeightedEdge>(DefaultWeightedEdge.class);
		graph.vertexSet().stream().sorted(java.util.Comparator.reverseOrder()).forEach(reverse::addVertex);
		graph.edgeSet().stream().sorted(java.util.Comparator.comparingLong((DefaultWeightedEdge e)->graph.getEdgeSource(e)).thenComparingLong(graph::getEdgeTarget).reversed()).forEach(e->{
			var copy=reverse.addEdge(graph.getEdgeSource(e),graph.getEdgeTarget(e));reverse.setEdgeWeight(copy,graph.getEdgeWeight(e));});
		var reverseSolver=new org.jgrapht.alg.flow.PushRelabelMFImpl<Long,DefaultWeightedEdge>(reverse);reverseSolver.calculateMinCut(-1L,-2L);
		long bits=Double.doubleToLongBits(solver.getCutCapacity()),reverseBits=Double.doubleToLongBits(reverseSolver.getCutCapacity());
		return new CutCertificate(-1L,bits,reverseBits,solver.getSourcePartition().size(),bits==reverseBits?"FORWARD_EQUALS_REVERSE":"ORDER_SENSITIVE","EXACT_SELECTED_CUT_ONLY",edges);
	}
	private static String endpoint(long id,List<RoleAlias> aliases,Graph<Long,DefaultWeightedEdge> graph){if(id==-1L)return "SOURCE";if(id==-2L)return "SINK";if(id<0){var aux=graph.vertexSet().stream().filter(x->x<0&&x!=-1L&&x!=-2L).sorted().toList();return "AUX_"+aux.indexOf(id);}
		for(RoleAlias a:aliases){long h=a.producerHopId(),compute=h<<2,placement=(h<<2)|1;if(id==compute)return a.compiledKey().normalizedSignature()+":C";
			if(id==placement)return a.compiledKey().normalizedSignature()+":P";}
		throw new AssertionError("R4_STABLE_CUT_ENDPOINT|"+id);
	}
	private static List<RepairCertificate> repairCertificates(R4ExactPrivateCostMinstFixtures.Fixture f,List<RoleAlias> aliases){List<RepairCertificate> out=new ArrayList<>();
		var solver=new org.jgrapht.alg.flow.PushRelabelMFImpl<Long,DefaultWeightedEdge>(f.cutGraph());solver.calculateMinCut(-1L,-2L);Set<Long> source=solver.getSourcePartition();
		for(RoleAlias a:aliases){Hop h=f.analysis().hop(a.compiledKey()).orElseThrow();var v=f.producerGraph().getVertex(h.getHopID());if(v==null)continue;var c=v.getCaps();
			String rawExec=source.contains(h.getHopID()<<2)?"FED":"CP",rawOutput=source.contains((h.getHopID()<<2)|1)?"FOUT":"LOUT";
			String finalExec=String.valueOf(h.getForcedExecType()),finalOutput=String.valueOf(h.getFederatedOutput());boolean changed=!rawExec.equals(finalExec)||!rawOutput.equals(finalOutput);
			out.add(new RepairCertificate(-1L,a,c.allowCP_LOUT,c.allowCP_FOUT,c.allowFED_LOUT,c.allowFED_FOUT,rawExec,rawOutput,finalExec,finalOutput,String.valueOf(v.getDataType()),
				c.allowCP_FOUT&&rawExec.equals("CP")&&rawOutput.equals("FOUT"),changed?"CAPS_REPAIR":"NONE"));}
		return out.stream().sorted(java.util.Comparator.comparing(r->r.role().compiledKey().normalizedSignature())).toList();
	}
	private static List<RegistryCertificate> registryCertificates(R4ExactPrivateCostMinstFixtures.Fixture f,List<RoleAlias> aliases){List<RegistryCertificate> out=new ArrayList<>();
		for(Object spec:f.registryObjects()){String n=spec.getClass().getSimpleName(),role=n.equals("LocalMaterializeSpec")?"producer":n.equals("AnchorSpec")?"child":"cpFout";
			RoleAlias producer=aliases.stream().filter(a->a.role().equals(role)).findFirst().orElseThrow(()->new AssertionError("R4_REGISTRY_ROLE|"+f.id()+"|"+role));
			String source=n.equals("LocalMaterializeSpec")?"FROZEN_SELECTED_D":n.equals("AnchorSpec")?"FROZEN_SELECTED_U":"FROZEN_SELECTED_CP_FOUT";
			out.add(new RegistryCertificate(spec,-1L,source,producer));}return List.copyOf(out);}
	private static List<RoleAlias> aliases(PlacementAnalysis analysis,Map<String,String> literalToRole,
		Map<String,String> descriptors){
		List<RoleAlias> aliases=new ArrayList<>();for(var entry:literalToRole.entrySet()){
			String role=entry.getValue(),descriptor=descriptors.get(role);var matches=analysis.occurrences().stream()
				.filter(o->descriptor.equals(role+":"+o.hop().getClass().getName()+":"+o.hop().getOpString())).toList();
			if(matches.size()!=1)throw new AssertionError("R4_TYPED_ALIAS_BIJECTION|role="+role+"|matches="+matches.size());
			var o=matches.get(0);aliases.add(new RoleAlias(entry.getKey(),role,o.key(),o.hop().getHopID()));
		}return List.copyOf(aliases);
	}
	private static List<String> stableAliases(List<RoleAlias> aliases){
		return aliases.stream().map(a->a.literalKey()+"|"+a.role()+"|"+a.compiledKey().normalizedSignature()).sorted().toList();
	}
	private static Arm arm(String name,String fixture)throws Exception{
		DMLProgram program=ProductionShadowFixtureFactory.compile(fixture);PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(program);
		Map<String,String> keys=new LinkedHashMap<>();for(var o:analysis.occurrences())keys.put(o.key().normalizedSignature(),o.key().normalizedSignature());
		return new Arm(name,fixture,program,analysis,Map.copyOf(keys),analysis.analysisFingerprint());
	}
	private CampaignBFrozenCostFixtureBridge(){}
}
