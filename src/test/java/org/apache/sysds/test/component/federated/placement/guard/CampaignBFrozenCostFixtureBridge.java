/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.LegacyDpOfflineSelectedCapture;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.R4ExactPrivateCostDpFixtures;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.LegacyMinstOfflineSelectedCapture;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.R4ExactPrivateCostMinstFixtures;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.junit.Assert;

/** Exact producer-bound all-22 input gate. */
final class CampaignBFrozenCostFixtureBridge {
	sealed interface EvidenceRole permits RoleAlias,PlanReceiptRole,AnalysisReceiptRole,GraphExclusionRole { }
	record RoleAlias(String literalKey,String role,CompiledHopKey compiledKey,long producerHopId) implements EvidenceRole { }
	record PlanReceiptRole(FedPlan rootPlan) implements EvidenceRole { public PlanReceiptRole { if(rootPlan==null)throw new IllegalArgumentException("rootPlan"); } }
	record AnalysisReceiptRole(PlacementAnalysis analysis) implements EvidenceRole { public AnalysisReceiptRole { if(analysis==null)throw new IllegalArgumentException("analysis"); } }
	record GraphExclusionRole(DpPlacementAdapter.GraphExclusionReceipt receipt) implements EvidenceRole {
		public GraphExclusionRole { if(receipt==null)throw new IllegalArgumentException("receipt"); }
	}
	sealed interface FullPathReceipt permits RetainedPlanReceipt,RetainedMinstReceipt,ExistingCertificateReceipt { }
	record RetainedPlanReceipt(LegacyDpOfflineSelectedCapture.RetainedFullPath retained, FederatedPlannerDpMemoTable memo, FedPlan rootPlan) implements FullPathReceipt {
		public RetainedPlanReceipt { if(retained==null||memo==null||rootPlan==null)throw new IllegalArgumentException("retained"); }
		RetainedPlanReceipt(LegacyDpOfflineSelectedCapture.RetainedFullPath retained){this(retained,retained.memo(),retained.rootPlan());}
	}
	record RetainedMinstReceipt(LegacyMinstOfflineSelectedCapture.RetainedFullPath retained) implements FullPathReceipt {
		public RetainedMinstReceipt { if(retained==null)throw new IllegalArgumentException("retained"); }
	}
	record ExistingCertificateReceipt(FullPathCertificate certificate) implements FullPathReceipt {
		public ExistingCertificateReceipt { if(certificate==null)throw new IllegalArgumentException("certificate"); }
	}
	sealed interface CostSelectionInput permits DpMemoInput,MinstGraphInput,FullPathInput,GraphExclusionInput {
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
		List<FedPlan> enumeratedPlans,FedPlan selectedPlan,FedPlan aggregatePlan,String tiePolicy,List<RoleAlias> aliases,String inputFingerprint)
		implements CostSelectionInput {
		public R4CostAdapterBridge.Planner planner(){return R4CostAdapterBridge.Planner.DP;}
		public Object producer(){return memo;}
	}
	record MinstGraphInput(String fixtureId,PlacementAnalysis analysis,FederatedPlanMinSTGraph graph,
		MinStPlacementInput ownerBound,
		Graph<Long,DefaultWeightedEdge> cutGraph,CutCertificate cutCertificate,List<RepairCertificate> repairCertificates,
		List<?> selectedObligations,List<?> registryReceipts,List<RegistryCertificate> registryCertificates,List<RoleAlias> aliases,
		String inputFingerprint) implements CostSelectionInput {
		public R4CostAdapterBridge.Planner planner(){return R4CostAdapterBridge.Planner.MIN_ST;}
		public Object producer(){return ownerBound.producerReceipt();}
	}
	record FullPathInput(R4CostAdapterBridge.Planner planner,String fixtureId,PlacementAnalysis analysis,
		List<RoleAlias> aliases,FullPathReceipt certificate,String inputFingerprint) implements CostSelectionInput {
		public Object producer(){
			if(certificate instanceof RetainedPlanReceipt retained)return retained.memo();
			if(certificate instanceof RetainedMinstReceipt retained)return retained.retained().selection().producer();
			return analysis;
		}
	}
	record GraphExclusionInput(String fixtureId,String sourceFixture,long seed,PlacementAnalysis analysis,
		DpPlacementAdapter.Result result,DpPlacementAdapter.GraphExclusionReceipt receipt,String inputFingerprint)
		implements CostSelectionInput {
		public GraphExclusionInput {
			if(fixtureId==null||sourceFixture==null||analysis==null||result==null||receipt==null||inputFingerprint==null)
				throw new IllegalArgumentException("Graph exclusion input fields must be non-null");
			if(result.analysis()!=analysis||result.producer()!=analysis||result.certificateReceipts().stream().noneMatch(x->x==receipt))
				throw new IllegalArgumentException("Graph exclusion input identity differs");
		}
		public R4CostAdapterBridge.Planner planner(){return R4CostAdapterBridge.Planner.DP;}
		public List<RoleAlias> aliases(){return List.of();}
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
			Arm owner;
			try{owner=arm("ANCHOR_CONTRAST_OWNER","B-01");}
			catch(Exception x){throw new AssertionError("R4_DP04_OWNER_BUILD",x);}
			arms.add(owner);
			try{inputs.add(dpAnchor(id+":CONCRETE",owner,true));}
			catch(Exception x){throw new AssertionError("R4_DP04_CONCRETE_PRODUCER",x);}
			try{inputs.add(dpAnchor(id+":MISSING",owner,false));}
			catch(Exception x){throw new AssertionError("R4_DP04_MISSING_PRODUCER",x);}
		}
		else if(inputs.isEmpty()) { Arm a=arm("FULL_PATH",FULL_PATH.get(id));arms.add(a);
			inputs.add(id.equals("C2-DP-08-UNKNOWN-METADATA")?graphExclusion(id,a):full(planner,id,a)); }
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
				Assert.assertTrue(d.enumeratedPlans().stream().anyMatch(p->p==d.selectedPlan()));
				Assert.assertNotSame(d.selectedPlan(),d.aggregatePlan());}
			if(input instanceof MinstGraphInput m)for(RoleAlias alias:m.aliases()){
				Hop hop=m.analysis().hop(alias.compiledKey()).orElseThrow();var vertex=m.graph().getVertex(hop.getHopID());
				if(vertex!=null)Assert.assertSame(hop,vertex.getHopRef());
				else {Assert.assertEquals("cpFout",alias.role());Assert.assertFalse(m.registryReceipts().isEmpty());}}
		}
	}

	private static DpMemoInput dp(R4ExactPrivateCostDpFixtures.Fixture f)throws Exception{
		List<RoleAlias> aliases=aliases(f.analysis(),f.literalAliases(),f.namedRoles());
		String tiePolicy=f.facts().getOrDefault("tieRule","NONE");
		return new DpMemoInput(f.id(),f.analysis(),f.memo(),f.root(),List.copyOf(f.enumeratedPlans()),f.selectedPlan(),f.aggregatePlan(),tiePolicy,aliases,
			CampaignBContractProbe.sha256(f.id()+"|"+f.analysis().analysisFingerprint()+"|"+stableAliases(aliases)+"|"+tiePolicy+"|DP_MEMO"));
	}
	private static DpMemoInput dpAnchor(String id,Arm arm,boolean concreteAnchor)throws Exception{
		var owner=arm.analysis().occurrences().get(1);
		return dp(R4ExactPrivateCostDpFixtures.anchorContrast(id,arm.analysis(),owner.hop(),
			Map.of(owner.key().normalizedSignature(),owner.hop()),concreteAnchor));
	}
	private static MinstGraphInput minst(R4ExactPrivateCostMinstFixtures.Fixture f)throws Exception{
		List<RoleAlias> aliases=aliases(f.analysis(),f.literalAliases(),f.namedRoles());
		return new MinstGraphInput(f.id(),f.analysis(),f.producerGraph(),FederatedPlanMinSTCut.bindPlacementInput(f.analysis(),f.producerGraph()),
			f.cutGraph(),cutCertificate(f.cutGraph(),aliases),repairCertificates(f,aliases),List.copyOf(f.selectedObligationObjects()),
			List.copyOf(f.registryObjects()),registryCertificates(f,aliases),aliases,
			CampaignBContractProbe.sha256(f.id()+"|"+f.analysis().analysisFingerprint()+"|"+stableAliases(aliases)+"|MINST_GRAPH"));
	}
	private static FullPathInput full(R4CostAdapterBridge.Planner planner,String id,Arm arm){
		if(planner==R4CostAdapterBridge.Planner.DP){
			try{
				var retained=LegacyDpOfflineSelectedCapture.captureFullPath(id,arm.bFixture(),arm.program(),arm.analysis());
				validateRetained(arm.analysis(),retained);
				List<RoleAlias> liveAliases=arm.analysis().occurrences().stream().map(o->new RoleAlias(
					o.key().normalizedSignature(),"compiled",o.key(),o.hop().getHopID())).toList();
				return new FullPathInput(planner,id,arm.analysis(),liveAliases,new RetainedPlanReceipt(retained,retained.memo(),retained.rootPlan()),arm.fingerprint());
			}catch(AssertionError e){throw e;}catch(Exception e){throw new AssertionError("R4_DP_FULL_RETAINED_CAPTURE|"+id,e);}
		}
		try{
			var retained=LegacyMinstOfflineSelectedCapture.captureFullPath(arm.program(),arm.analysis(),-1L);
			List<RoleAlias> liveAliases=arm.analysis().occurrences().stream().map(o->new RoleAlias(
				o.key().normalizedSignature(),"compiled",o.key(),o.hop().getHopID())).toList();
			return new FullPathInput(planner,arm.bFixture(),arm.analysis(),liveAliases,new RetainedMinstReceipt(retained),
				arm.fingerprint());
		}catch(AssertionError e){throw e;}catch(Exception e){throw new AssertionError("R4_MINST_FULL_RETAINED_CAPTURE|"+id,e);}
	}
	private static GraphExclusionInput graphExclusion(String id,Arm arm){
		var result=new DpPlacementAdapter().select(arm.analysis());
		var reasonOnly=result.certificateReceipts().stream().filter(receipt->receipt.exclusion().reasonCode()==
			org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode.UNKNOWN_METADATA).toList();
		if(reasonOnly.size()<=1)throw new AssertionError("R4_DP08_REASON_ONLY_AMBIGUITY|matches="+reasonOnly.size());
		var receipt=selectGraphExclusion(reasonOnly);
		if(receipt.node().kind()!=org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE)
			throw new AssertionError("R4_DP08_NODE_KIND|"+receipt.node().kind());
		if(arm.analysis().graph().nodes().stream().flatMap(node->node.legalAlternatives().stream()).noneMatch(state->
			state.execType()==org.apache.sysds.common.Types.ExecType.FED&&!state.shapeDependent()))
			throw new AssertionError("R4_DP08_SHAPE_INDEPENDENT_FED_ALTERNATIVE_MISSING");
		return new GraphExclusionInput(id,arm.bFixture(),-1L,arm.analysis(),result,receipt,arm.fingerprint());
	}
	static DpPlacementAdapter.GraphExclusionReceipt selectGraphExclusion(
		List<DpPlacementAdapter.GraphExclusionReceipt> receipts){
		var matches=receipts.stream().filter(receipt->receipt.exclusion().reasonCode()==
			org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode.UNKNOWN_METADATA
			&&receipt.exclusion().state().execType()==org.apache.sysds.common.Types.ExecType.FED
			&&receipt.exclusion().state().shapeDependent()).toList();
		if(matches.size()!=1)throw new AssertionError("R4_DP08_GRAPH_EXCLUSION_BIJECTION|matches="+matches.size());
		return matches.get(0);
	}
	private static void validateRetained(PlacementAnalysis analysis,LegacyDpOfflineSelectedCapture.RetainedFullPath retained){
		if(retained.rootPlan()==null||retained.rootHops().isEmpty())throw new AssertionError("R4_DP_RETAINED_ROOT_EMPTY");
		if(retained.memo()==null)throw new AssertionError("R4_DP_RETAINED_MEMO_NULL");
		if(retained.rootPlan().getChildFedPlans().size()!=retained.rootChildPlanReceipts().size()
			||retained.rootHops().size()!=retained.rootChildPlanReceipts().size())
			throw new AssertionError("R4_DP_RETAINED_ROOT_CARDINALITY");
		Set<Hop> roots=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Set<FedPlan> rootPlans=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for(int i=0;i<retained.rootChildPlanReceipts().size();i++){
			FedPlan selected=retained.rootChildPlanReceipts().get(i);Hop root=retained.rootHops().get(i);
			if(selected==null||root==null)throw new AssertionError("R4_DP_RETAINED_ROOT_NULL");
			if(!rootPlans.add(selected)||!roots.add(root))throw new AssertionError("R4_DP_RETAINED_ROOT_DUPLICATE");
			if(retained.memo().getFedPlanAfterPrune(retained.rootPlan().getChildFedPlans().get(i))!=selected
				||selected.getHopRef()!=root)throw new AssertionError("R4_DP_RETAINED_ROOT_REACHABILITY|index="+i);
		}
		if(retained.selectedPlanEdges().size()!=retained.selectedPlanReceipts().size())
			throw new AssertionError("R4_DP_RETAINED_SELECTED_CARDINALITY");
		Set<FedPlan> selectedPlans=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for(int i=0;i<retained.selectedPlanReceipts().size();i++){
			var edge=retained.selectedPlanEdges().get(i);FedPlan selected=retained.selectedPlanReceipts().get(i);
			if(selected==null||selected.getHopRef()==null)throw new AssertionError("R4_DP_RETAINED_SELECTED_NULL");
			if(!selectedPlans.add(selected))throw new AssertionError("R4_DP_RETAINED_SELECTED_DUPLICATE");
			var variants=retained.memo().getFedPlanVariants(edge);
			if(variants==null||variants.getFedPlanVariants().stream().noneMatch(plan->plan==selected))
				throw new AssertionError("R4_DP_RETAINED_SELECTED_REACHABILITY|index="+i);
		}
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
		return new CutCertificate(-1L,bits,reverseBits,solver.getSourcePartition().size(),bits==reverseBits?"FORWARD_EQUALS_REVERSE":"ORDER_SENSITIVE","NONE(MINST_ALTERNATE_MINCUTS_NOT_ENUMERATED)",edges);
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
