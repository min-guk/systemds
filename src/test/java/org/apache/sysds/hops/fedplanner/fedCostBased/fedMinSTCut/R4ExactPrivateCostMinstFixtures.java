/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ObligationKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

/** Native, manifest-independent MinST private fixtures for the R4 cost contract. */
public final class R4ExactPrivateCostMinstFixtures {
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;

	public record Fixture(String id, Map<String,String> namedRoles, Map<String,String> literalAliases,
		Map<String,String> assignments, List<String> obligations, List<String> registries,
		Map<String,String> facts, PlacementAnalysis analysis, FederatedPlanMinSTGraph producerGraph,
		Graph<Long,DefaultWeightedEdge> cutGraph, List<?> selectedObligationObjects, List<?> registryObjects) { }

	public static List<Fixture> all() throws Exception {
		List<Fixture> result = new ArrayList<>(quartet());
		result.add(capsRepair()); result.add(sharedDownload()); result.add(anchoredUpload());
		result.add(missingAnchor());
		return List.copyOf(result);
	}

	private static List<Fixture> quartet() throws Exception {
		reset();
		FederatedPlannerUtils.registerFedInitVar("X_quartet_anchor", FType.ROW,
			"localhost:1234/X1@0:0-2:2;localhost:1235/X2@2:0-4:2");
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis full = CampaignBPlacementAnalysisFixtureBridge.build(program);
		requireShape("source", full, 8, 8, 10, 0);
		List<String> fullSnapshot = CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(full);
		String registrySnapshot = registrySnapshot();
		PlacementAnalysis retainedAnalysis = CampaignBPlacementAnalysisFixtureBridge.prefix(full, 4);
		requireShape("projection", retainedAnalysis, 4, 4, 5, 0);
		for(int i = 0; i < retainedAnalysis.occurrences().size(); i++) {
			HopOccurrenceProjection source = full.occurrences().get(i);
			HopOccurrenceProjection projected = retainedAnalysis.occurrences().get(i);
			if(source != projected || source.hop() != projected.hop() || !source.key().equals(projected.key()))
				throw new IllegalStateException("quartet projection identity mismatch at " + i);
		}
		String[] names = {"vCpl", "vCpf", "vFl", "vFf"};
		ExecType[] exec = {ExecType.CP, ExecType.CP, ExecType.FED, ExecType.FED};
		FederatedOutput[] output = {FederatedOutput.LOUT, FederatedOutput.FOUT,
			FederatedOutput.LOUT, FederatedOutput.FOUT};
		List<Hop> hops = retainedAnalysis.occurrences().stream().map(HopOccurrenceProjection::hop).toList();
		FederatedPlanMinSTGraph graph = buildQuartet(hops, exec, output);
		if(graph.getMemoTable().size() != 4)
			throw new IllegalStateException("quartet vertex count " + graph.getMemoTable().size());
		for(Hop hop : hops) {
			Vertex vertex = graph.getVertex(hop.getHopID());
			if(vertex == null || vertex.getHopRef() != hop)
				throw new IllegalStateException("quartet graph Hop identity mismatch " + hop.getHopID());
		}
		graph.getOptimalPlan();
		Graph<Long,DefaultWeightedEdge> nativeGraph = graph.getGraph();
		PushRelabelMFImpl<Long,DefaultWeightedEdge> solver = new PushRelabelMFImpl<>(nativeGraph);
		solver.calculateMinCut(SOURCE, SINK);
		Set<Long> source = solver.getSourcePartition();
		Map<String,String> assignments = assignments(hops, names, source);
		Map<String,String> roles = roles(hops, names);
		Map<String,String> aliases = aliases(retainedAnalysis, names);
		List<String> cutEdges = cutEdges(nativeGraph, solver.getCutEdges(), hops, names);
		ReverseQuartetControl reverse = reverseQuartetControl(hops, names, exec, output);
		if(!assignments.equals(reverse.assignments()))
			throw new IllegalStateException("forward/reverse quartet mismatch");
		long capacityBits = graph.getSelectedCutObjectiveBits();
		if(capacityBits != Double.doubleToRawLongBits(0x1.0p3) || capacityBits != reverse.capacityBits())
			throw new IllegalStateException("quartet capacity mismatch " + Long.toHexString(capacityBits));
		if(graph.getSelectedSourcePartitionNodeIds().size() != 5)
			throw new IllegalStateException("quartet source partition count " + graph.getSelectedSourcePartitionNodeIds().size());
		if(!cutEdges.equals(reverse.cutEdges()))
			throw new IllegalStateException("quartet cut-edge mismatch");
		if(!fullSnapshot.equals(CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(full))
			|| !registrySnapshot.equals(registrySnapshot()))
			throw new IllegalStateException("quartet reverse control mutated analysis or registries");
		Map<String,String> cutFacts = new LinkedHashMap<>();
		cutFacts.put("capacity", Double.toHexString(Double.longBitsToDouble(capacityBits)));
		cutFacts.put("reverseCapacity", Double.toHexString(Double.longBitsToDouble(reverse.capacityBits())));
		cutFacts.put("orderControl", "FORWARD_EQUALS_REVERSE");
		cutFacts.put("tieLimit", "NONE(MINST_ALTERNATE_MINCUTS_NOT_ENUMERATED)");
		cutFacts.put("cutEdges", cutEdges.toString());
		Fixture equalCut = new Fixture("C2-MS-01-EQUAL-CUT", roles, aliases, assignments,
			List.of(), List.of(), Map.copyOf(cutFacts),retainedAnalysis,graph,nativeGraph,
			List.copyOf(graph.getSelectedObligations()),List.of());
		Fixture states = new Fixture("C2-MS-06-STATE-QUARTET", roles, aliases, assignments,
			List.of(), List.of(), Map.of("alternativeCountEach", "2", "fType", "ROW", "repair", "NONE"),
			retainedAnalysis,graph,nativeGraph,List.copyOf(graph.getSelectedObligations()),List.of());
		return List.of(equalCut, states);
	}

	private record ReverseQuartetControl(Map<String,String> assignments, long capacityBits, List<String> cutEdges) { }

	private static FederatedPlanMinSTGraph buildQuartet(List<Hop> hops, ExecType[] exec,
		FederatedOutput[] output) {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		for(int i = 0; i < hops.size(); i++) {
			ExecPlacementCaps caps = quartetCaps(i);
			Vertex vertex = new Vertex(hops.get(i), Privacy.PRIVATE, FType.ROW, FType.ROW, caps);
			vertex.setMetadata(1.0, 1.0, List.of()); vertex.setCost(8.0, 4.0, 4.0); graph.addVertex(vertex);
		}
		addQuartetEdges(graph.getGraph(), hops, exec, output, false);
		return graph;
	}

	private static ReverseQuartetControl reverseQuartetControl(List<Hop> hops, String[] names, ExecType[] exec,
		FederatedOutput[] output) {
		Graph<Long,DefaultWeightedEdge> graph = new DefaultDirectedWeightedGraph<>(DefaultWeightedEdge.class);
		graph.addVertex(SOURCE); graph.addVertex(SINK);
		for(int i = hops.size() - 1; i >= 0; i--) {
			long id = hops.get(i).getHopID();
			graph.addVertex(FederatedPlanMinSTPlanner.computeId(id));
			graph.addVertex(FederatedPlanMinSTPlanner.placementId(id));
		}
		addQuartetEdges(graph, hops, exec, output, true);
		PushRelabelMFImpl<Long,DefaultWeightedEdge> solver = new PushRelabelMFImpl<>(graph);
		solver.calculateMinCut(SOURCE, SINK);
		return new ReverseQuartetControl(assignments(hops, names, solver.getSourcePartition()),
			Double.doubleToRawLongBits(solver.getCutCapacity()), cutEdges(graph, solver.getCutEdges(), hops, names));
	}

	private static ExecPlacementCaps quartetCaps(int i) {
		ExecPlacementCaps caps = new ExecPlacementCaps(); caps.fedFoutMode = ExecPlacementCaps.FedFoutMode.NATIVE;
		if(i == 2) { caps.allowCP_FOUT = false; caps.allowFED_FOUT = false; }
		else if(i == 3) { caps.allowCP_LOUT = false; caps.allowCP_FOUT = false; }
		return caps;
	}

	private static void addQuartetEdges(Graph<Long,DefaultWeightedEdge> graph, List<Hop> hops, ExecType[] exec,
		FederatedOutput[] output, boolean reverse) {
		for(int n = 0; n < hops.size(); n++) {
			int i = reverse ? hops.size() - 1 - n : n;
			long id = hops.get(i).getHopID();
			weighted(graph, SOURCE, FederatedPlanMinSTPlanner.computeId(id), exec[i] == ExecType.FED ? 8 : 1);
			weighted(graph, FederatedPlanMinSTPlanner.computeId(id), SINK, exec[i] == ExecType.FED ? 1 : 8);
			weighted(graph, SOURCE, FederatedPlanMinSTPlanner.placementId(id), output[i] == FederatedOutput.FOUT ? 8 : 1);
			weighted(graph, FederatedPlanMinSTPlanner.placementId(id), SINK, output[i] == FederatedOutput.FOUT ? 1 : 8);
		}
	}

	private static Map<String,String> aliases(PlacementAnalysis analysis,String[] names){Map<String,String> m=new LinkedHashMap<>();
		for(int i=0;i<analysis.occurrences().size();i++)m.put(analysis.occurrences().get(i).key().normalizedSignature(),names[i]);return Map.copyOf(m);}
	private static List<String> cutEdges(Graph<Long,DefaultWeightedEdge> graph,Set<DefaultWeightedEdge> edges,List<Hop> hops,String[] names){return edges.stream().map(e ->
		node(graph.getEdgeSource(e),hops,names)+"->"+node(graph.getEdgeTarget(e),hops,names)+"@"+
		Double.toHexString(graph.getEdgeWeight(e))).sorted().collect(Collectors.toList());}
	private static void requireShape(String label,PlacementAnalysis analysis,int occurrences,int nodes,int constraints,int relocations){
		if(analysis.occurrences().size()!=occurrences||analysis.graph().nodes().size()!=nodes
			||analysis.graph().constraints().size()!=constraints||analysis.graph().relocationActions().size()!=relocations)
			throw new IllegalStateException(label+" shape "+analysis.occurrences().size()+"/"+analysis.graph().nodes().size()+"/"
				+analysis.graph().constraints().size()+"/"+analysis.graph().relocationActions().size());}
	private static String registrySnapshot(){return FederatedRefedRegistry.snapshot(-1L)+"|"+FederatedFoutMaterializeRegistry.snapshot(-1L)+"|"+FederatedLocalMaterializeRegistry.snapshot(-1L);}

	private static Fixture capsRepair() throws Exception {
		reset(); Hop hop = new LiteralOp(41L); PlacementAnalysis analysis=analysis(List.of(hop));
		HopOccurrenceProjection occurrence = analysis.occurrences().get(0);
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		ExecPlacementCaps caps = new ExecPlacementCaps(); caps.allowCP_LOUT = false;
		caps.allowCP_FOUT = false; caps.allowFED_LOUT = false;
		Vertex vertex = new Vertex(hop, Privacy.PRIVATE, FType.ROW, caps);
		vertex.setMetadata(1, 1, List.of()); vertex.setCost(8, 4, 4); graph.addVertex(vertex);
		graph.setVertexCost(vertex); graph.addExecPlacementResultEdge(vertex);
		graph.getOptimalPlan();
		PushRelabelMFImpl<Long,DefaultWeightedEdge> solver = new PushRelabelMFImpl<>(graph.getGraph());
		solver.calculateMinCut(SOURCE, SINK); Set<Long> source = solver.getSourcePartition();
		ExecType rawExec = source.contains(hop.getHopID() << 2) ? ExecType.FED : ExecType.CP;
		FederatedOutput rawOut = source.contains((hop.getHopID() << 2) | 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
		Map<Long,ExecType> exec = Map.of(hop.getHopID(), hop.getForcedExecType());
		Map<Long,FederatedOutput> out = Map.of(hop.getHopID(), hop.getFederatedOutput());
		return new Fixture("C2-MS-02-CAPS-FIXPOINT", roles(List.of(hop), new String[]{"repairVertex"}),
			Map.of(occurrence.key().normalizedSignature(),"repairVertex"),
			Map.of("repairVertex", exec.get(hop.getHopID()) + "/" + out.get(hop.getHopID())), List.of(), List.of(),
			Map.of("raw", rawExec + "/" + rawOut,
				"caps", "CP_LOUT=" + caps.allowCP_LOUT + "|CP_FOUT=" + caps.allowCP_FOUT
					+ "|FED_LOUT=" + caps.allowFED_LOUT + "|FED_FOUT=" + caps.allowFED_FOUT,
				"repair", rawExec == exec.get(hop.getHopID()) && rawOut == out.get(hop.getHopID()) ? "NONE" : "CAPS_REPAIR"),
			analysis,graph,graph.getGraph(),List.copyOf(graph.getSelectedObligations()),List.of());
	}

	private static Fixture sharedDownload() throws Exception {
		reset(); FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph(); graph.setNumOfWorkers(4);
		DataOp producer = new DataOp("X_d_obligation", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 10000, 1000);
		ExecPlacementCaps pc = new ExecPlacementCaps(); pc.allowCP_LOUT=false; pc.allowCP_FOUT=false; pc.allowFED_LOUT=false;
		Vertex pv = vertex(producer, pc, 100, 6, 4); graph.addVertex(pv); graph.setVertexCost(pv); graph.addExecPlacementResultEdge(pv);
		Hop c1h = new LiteralOp(31L), c2h = new LiteralOp(32L);
		Vertex c1 = vertex(c1h, new ExecPlacementCaps(), 0, 0, 0); graph.addVertex(c1); graph.setVertexCost(c1);
		Vertex c2 = vertex(c2h, new ExecPlacementCaps(), 0, 0, 0); graph.addVertex(c2); graph.setVertexCost(c2);
		graph.addLoopCarryNetEdge(c1h.getHopID(), producer.getHopID(), 0, 0);
		graph.addLoopCarryNetEdge(c2h.getHopID(), producer.getHopID(), 0, 0);
		graph.getOptimalPlan(); registerSelectedObligations(graph, Map.of());
		var obligation = graph.getSelectedObligations().stream().filter(o -> o.getKind() == ObligationKind.D).findFirst().orElseThrow();
		var spec = FederatedLocalMaterializeRegistry.snapshot(-1L).get(producer.getHopID());
		List<Hop> hops=List.of(producer,c1h,c2h); String[] names={"producer","consumer1","consumer2"};
		PlacementAnalysis analysis=analysis(hops);
		return new Fixture("C2-MS-03-SHARED-DOWNLOAD", roles(hops,names), canonicalAliases(analysis,hops,names),
			Map.of("producer","FED/FOUT","consumer1","CP/LOUT","consumer2","CP/LOUT"),
			List.of("D:producer->"+obligation.getConsumerHopIds().size()),
			List.of("LOCAL:consumers="+spec.getConsumerHopIds().size()+":reason="+spec.getReason()),
			Map.of("consumerCount","2","fType",String.valueOf(spec.getFTypeHint())),analysis,graph,
			graph.getGraph(),List.copyOf(graph.getSelectedObligations()),List.of(spec));
	}

	private static Fixture anchoredUpload() throws Exception {
		reset(); FederatedPlannerUtils.registerFedInitVar("X_anchor", FType.ROW,
			"localhost:1234/X1@0:0-50:100;localhost:1235/X2@50:0-100:100");
		DataOp localInput = new DataOp("local_cp_fout_input", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 10000, 1000);
		Hop computed = HopRewriteUtils.createBinary(localInput, new LiteralOp(3.0), OpOp2.PLUS);
		DataOp cpFout = new DataOp("Y_cp_fout", DataType.MATRIX, ValueType.FP64, computed,
			OpOpData.TRANSIENTWRITE, null);
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph(); graph.setNumOfWorkers(2);
		DataOp child = new DataOp("X_u_obligation", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 10000, 1000);
		ExecPlacementCaps cc=new ExecPlacementCaps(); cc.allowFED_LOUT=false; cc.allowFED_FOUT=false;
		Vertex cv=vertex(child,cc,0,6,4); graph.addVertex(cv); graph.setVertexCost(cv); graph.addExecPlacementResultEdge(cv);
		Hop fed=new LiteralOp(21L); ExecPlacementCaps fc=new ExecPlacementCaps(); fc.allowCP_LOUT=false; fc.allowCP_FOUT=false;
		Vertex fv=vertex(fed,fc,100,0,0); graph.addVertex(fv); graph.setVertexCost(fv);
		graph.addParentChildNetEdge(cv, child.getHopID(), fv, fed.getHopID(), true);
		Hop local=new LiteralOp(22L); ExecPlacementCaps lc=new ExecPlacementCaps(); lc.allowFED_LOUT=false; lc.allowFED_FOUT=false;
		Vertex lv=vertex(local,lc,0,0,0); graph.addVertex(lv); graph.setVertexCost(lv);
		graph.addRequiredLocalInputEdge(local.getHopID(), child.getHopID());
		ExecPlacementCaps cpFoutCaps = new ExecPlacementCaps(); cpFoutCaps.allowCP_LOUT=false;
		cpFoutCaps.allowFED_LOUT=false; cpFoutCaps.allowFED_FOUT=false;
		Vertex cpFoutVertex=vertex(cpFout,cpFoutCaps,0,0,100); graph.addVertex(cpFoutVertex);
		graph.setVertexCost(cpFoutVertex); graph.addExecPlacementResultEdge(cpFoutVertex);
		weighted(graph.getGraph(),SOURCE,FederatedPlanMinSTPlanner.placementId(cpFout.getHopID()),1000);
		List<Hop> hops=List.of(child,fed,local,cpFout); String[] names={"child","fedConsumer","localConsumer","cpFout"};
		PlacementAnalysis analysis=analysis(hops);
		completeOwnerVertices(graph, analysis);
		Map<Long,FType> fTypes=new LinkedHashMap<>(); fTypes.put(cpFout.getHopID(),FType.BROADCAST);
		graph.getOptimalPlan(); registerSelectedObligations(graph, fTypes);
		var obligation=graph.getSelectedObligations().stream().filter(o->o.getKind()==ObligationKind.U).findFirst().orElseThrow();
		var refed=FederatedRefedRegistry.snapshot(-1L).get(child.getHopID());
		FederatedRefedPolicy.registerFoutMaterializeCandidate(cpFout,fTypes,-1L);
		var fout=FederatedFoutMaterializeRegistry.snapshot(-1L).get(cpFout.getHopID());
		if(refed==null || fout==null) throw new IllegalStateException("missing selected U/CP-FOUT registry refed="
			+(refed!=null)+" fout="+(fout!=null)+" cpState="+cpFout.getForcedExecType()+"/"+cpFout.getFederatedOutput()
			+" keys="+FederatedFoutMaterializeRegistry.snapshot(-1L).keySet());
		return new Fixture("C2-MS-04-ANCHORED-UPLOAD",roles(hops,names),canonicalAliases(analysis,hops,names),
			Map.of("child","CP/LOUT","fedConsumer","FED/FOUT","localConsumer","CP/LOUT","cpFout","CP/FOUT"),
			List.of("U:child->"+obligation.getConsumerHopIds().size()),
			List.of("REFED:anchorKey="+refed.getAnchorKey(),"FOUT:fType="+fout.getFTypeHint()),
			Map.of("concreteAnchor","true","scope","-1"),analysis,graph,graph.getGraph(),
			List.copyOf(graph.getSelectedObligations()),List.of(refed,fout));
	}

	private static Fixture missingAnchor() {
		reset(); FederatedPlanMinSTGraph graph=new FederatedPlanMinSTGraph();
		Hop hop=HopRewriteUtils.createBinary(new LiteralOp(2.0),new LiteralOp(3.0),OpOp2.PLUS);
		PlacementAnalysis analysis=analysis(List.of(hop));
		ExecPlacementCaps caps=new ExecPlacementCaps(); caps.allowFED_LOUT=false; caps.allowFED_FOUT=false;
		Vertex v=vertex(hop,caps,0,0,100); graph.addVertex(v); graph.setVertexCost(v); graph.addExecPlacementResultEdge(v);
		completeOwnerVertices(graph, analysis);
		graph.getOptimalPlan();
		return new Fixture("C2-MS-05-MISSING-ANCHOR",roles(List.of(hop),new String[]{"candidate"}),
			canonicalAliases(analysis,List.of(hop),new String[]{"candidate"}),
			Map.of("candidate",hop.getForcedExecType()+"/"+hop.getFederatedOutput()),List.of(),List.of(),
			Map.of("concreteAnchor","false","reason","MISSING_CONCRETE_ANCHOR"),analysis,graph,
			graph.getGraph(),List.copyOf(graph.getSelectedObligations()),List.of());
	}

	private static PlacementAnalysis analysis(List<Hop> roots) {
		StatementBlock block=new StatementBlock(); block.setHops(new ArrayList<>(roots));
		DMLProgram program=new DMLProgram(); program.setStatementBlocks(new ArrayList<>(List.of(block)));
		PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(program);
		for(Hop root:roots) if(analysis.occurrences().stream().noneMatch(o->o.hop()==root))
			throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|missingMinstHop="+root.getHopID());
		return analysis;
	}

	private static Vertex vertex(Hop hop, ExecPlacementCaps caps, double cp, double fed, double net) {
		Vertex v=new Vertex(hop,Privacy.PUBLIC,FType.ROW,caps); v.setMetadata(1,1,List.of()); v.setCost(cp,fed,net); return v;
	}
	private static void completeOwnerVertices(FederatedPlanMinSTGraph graph, PlacementAnalysis analysis) {
		for(var occurrence : analysis.occurrences()) {
			Hop hop=occurrence.hop();
			if(graph.getVertex(hop.getHopID()) != null)
				continue;
			ExecPlacementCaps caps=new ExecPlacementCaps(); caps.allowCP_FOUT=false;
			caps.allowFED_LOUT=false; caps.allowFED_FOUT=false;
			Vertex v=vertex(hop,caps,0,0,0); graph.addVertex(v);
		}
	}
	private static Map<String,String> assignments(List<Hop> hops,String[] names,Set<Long> source) {
		Map<String,String> m=new LinkedHashMap<>(); for(int i=0;i<hops.size();i++){long id=hops.get(i).getHopID();
			m.put(names[i],(source.contains(FederatedPlanMinSTPlanner.computeId(id))?"FED":"CP")+"/"+
				(source.contains(FederatedPlanMinSTPlanner.placementId(id))?"FOUT":"LOUT"));} return Map.copyOf(m);
	}
	private static Map<String,String> roles(List<Hop> hops,String[] names){Map<String,String> m=new LinkedHashMap<>();
		for(int i=0;i<hops.size();i++)m.put(names[i],names[i]+":"+hops.get(i).getClass().getName()+":"+hops.get(i).getOpString());return Map.copyOf(m);}
	private static Map<String,String> aliases(String fixture,List<Hop> hops,String[] names){Map<String,String> m=new LinkedHashMap<>();
		for(int i=0;i<hops.size();i++)m.put("SYNTHETIC:"+fixture+":"+names[i]+":"+hops.get(i).getClass().getName()+":"+hops.get(i).getOpString(),names[i]);return Map.copyOf(m);}
	private static Map<String,String> canonicalAliases(PlacementAnalysis analysis,List<Hop> hops,String[] names) {
		if(hops.size()!=names.length) throw new IllegalArgumentException("role cardinality");
		Map<String,String> aliases=new LinkedHashMap<>();
		for(int i=0;i<hops.size();i++) {
			Hop hop=hops.get(i);
			var occurrences=analysis.occurrences().stream().filter(o->o.hop()==hop).toList();
			if(occurrences.size()!=1) throw new IllegalStateException("canonical role occurrence "+names[i]+"="+occurrences.size());
			if(aliases.put(occurrences.get(0).key().normalizedSignature(),names[i])!=null)
				throw new IllegalStateException("duplicate canonical role "+names[i]);
		}
		return Map.copyOf(aliases);
	}
	private static String node(long n,List<Hop> hops,String[] names){if(n==SOURCE)return"SOURCE";if(n==SINK)return"SINK";
		for(int i=0;i<hops.size();i++){long id=hops.get(i).getHopID();if(n==FederatedPlanMinSTPlanner.computeId(id))return names[i]+":C";
			if(n==FederatedPlanMinSTPlanner.placementId(id))return names[i]+":P";}throw new IllegalStateException("foreign cut node "+n);}
	private static void weighted(Graph<Long,DefaultWeightedEdge> g,long s,long t,double w){DefaultWeightedEdge e=g.addEdge(s,t);g.setEdgeWeight(e,w);}
	private static void registerSelectedObligations(FederatedPlanMinSTGraph graph, Map<Long,FType> fTypes) {
		for(var obligation:graph.getSelectedObligations()) {
			Hop child=graph.getHopRef(obligation.getChildHopId());
			if(child==null)
				throw new IllegalStateException("selected obligation has no exact child Hop");
			if(obligation.getKind()==ObligationKind.D) {
				FederatedLocalMaterializeRegistry.register(-1L,obligation.getChildHopId(),
					obligation.getConsumerHopIds(),obligation.getFType()==null?null:obligation.getFType().name(),
					obligation.getReason());
			}
			else {
				List<Hop> consumers=obligation.getConsumerHopIds().stream().map(graph::getHopRef)
					.collect(Collectors.toList());
				if(consumers.stream().anyMatch(hop->hop==null))
					throw new IllegalStateException("selected obligation has no exact consumer Hop");
				FederatedRefedPolicy.registerFoutMaterializeObligation(child,consumers,fTypes,-1L);
			}
		}
	}
	private static void reset(){FederatedPlannerUtils.resetFederatedPlannerRunState();FederatedPlannerUtils.clearFedInitVars();
		FederatedRefedRegistry.clear();FederatedFoutMaterializeRegistry.clear();FederatedLocalMaterializeRegistry.clear();}
	private R4ExactPrivateCostMinstFixtures(){ }
}
