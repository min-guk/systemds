/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ObligationKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
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
		String[] names = {"vCpl", "vCpf", "vFl", "vFf"};
		ExecType[] exec = {ExecType.CP, ExecType.CP, ExecType.FED, ExecType.FED};
		FederatedOutput[] output = {FederatedOutput.LOUT, FederatedOutput.FOUT,
			FederatedOutput.LOUT, FederatedOutput.FOUT};
		List<Hop> hops = List.of(new LiteralOp(11L), new LiteralOp(12L), new LiteralOp(13L), new LiteralOp(14L));
		FederatedPlanMinSTGraph graph = buildQuartet(hops, names, exec, output, false);
		Graph<Long,DefaultWeightedEdge> nativeGraph = graph.getGraph();
		PushRelabelMFImpl<Long,DefaultWeightedEdge> solver = new PushRelabelMFImpl<>(nativeGraph);
		solver.calculateMinCut(SOURCE, SINK);
		Set<Long> source = solver.getSourcePartition();
		Map<String,String> assignments = assignments(hops, names, source);
		Map<String,String> roles = roles(hops, names);
		Map<String,String> aliases = aliases("C2-MS-06", hops, names);
		List<String> cutEdges = solver.getCutEdges().stream().map(e ->
			node(nativeGraph.getEdgeSource(e), hops, names) + "->" +
				node(nativeGraph.getEdgeTarget(e), hops, names) + "@" +
				Double.toHexString(nativeGraph.getEdgeWeight(e))).sorted().collect(Collectors.toList());

		List<Hop> reverseHops = List.of(new LiteralOp(21L), new LiteralOp(22L), new LiteralOp(23L), new LiteralOp(24L));
		FederatedPlanMinSTGraph reverse = buildQuartet(reverseHops, names, exec, output, true);
		PushRelabelMFImpl<Long,DefaultWeightedEdge> reverseSolver = new PushRelabelMFImpl<>(reverse.getGraph());
		reverseSolver.calculateMinCut(SOURCE, SINK);
		Map<String,String> reverseAssignments = assignments(reverseHops, names, reverseSolver.getSourcePartition());
		if(!assignments.equals(reverseAssignments))
			throw new IllegalStateException("forward/reverse quartet mismatch");
		Map<String,String> cutFacts = new LinkedHashMap<>();
		cutFacts.put("capacity", Double.toHexString(solver.getCutCapacity()));
		cutFacts.put("reverseCapacity", Double.toHexString(reverseSolver.getCutCapacity()));
		cutFacts.put("orderControl", "FORWARD_EQUALS_REVERSE");
		cutFacts.put("cutEdges", cutEdges.toString());
		PlacementAnalysis retainedAnalysis=analysis(hops);
		Fixture equalCut = new Fixture("C2-MS-01-EQUAL-CUT", roles, aliases, assignments,
			List.of(), List.of(), Map.copyOf(cutFacts),retainedAnalysis,graph,nativeGraph,
			List.copyOf(graph.getSelectedObligations()),List.of());
		Fixture states = new Fixture("C2-MS-06-STATE-QUARTET", roles, aliases, assignments,
			List.of(), List.of(), Map.of("alternativeCountEach", "2", "fType", "ROW", "repair", "NONE"),
			retainedAnalysis,graph,nativeGraph,List.copyOf(graph.getSelectedObligations()),List.of());
		return List.of(equalCut, states);
	}

	private static FederatedPlanMinSTGraph buildQuartet(List<Hop> hops, String[] names, ExecType[] exec,
		FederatedOutput[] output, boolean reverse) throws Exception {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		for(int n = 0; n < hops.size(); n++) {
			int i = reverse ? hops.size() - 1 - n : n;
			ExecPlacementCaps caps = new ExecPlacementCaps(); caps.fedFoutMode = ExecPlacementCaps.FedFoutMode.NATIVE;
			if(i == 2) { caps.allowCP_FOUT = false; caps.allowFED_FOUT = false; }
			else if(i == 3) { caps.allowCP_LOUT = false; caps.allowCP_FOUT = false; }
			Vertex vertex = new Vertex(hops.get(i), Privacy.PRIVATE, FType.ROW, FType.ROW, caps);
			vertex.setMetadata(1.0, 1.0, List.of()); vertex.setCost(8.0, 4.0, 4.0); graph.addVertex(vertex);
		}
		invoke(graph, "applyConcreteAnchorCapabilityGate", new Class<?>[0]);
		for(int i = 0; i < hops.size(); i++) {
			long id = hops.get(i).getHopID();
			weighted(graph.getGraph(), SOURCE, FederatedPlanMinSTPlanner.computeId(id), exec[i] == ExecType.FED ? 8 : 1);
			weighted(graph.getGraph(), FederatedPlanMinSTPlanner.computeId(id), SINK, exec[i] == ExecType.FED ? 1 : 8);
			weighted(graph.getGraph(), SOURCE, FederatedPlanMinSTPlanner.placementId(id), output[i] == FederatedOutput.FOUT ? 8 : 1);
			weighted(graph.getGraph(), FederatedPlanMinSTPlanner.placementId(id), SINK, output[i] == FederatedOutput.FOUT ? 1 : 8);
		}
		return graph;
	}

	private static Fixture capsRepair() throws Exception {
		reset(); Hop hop = new LiteralOp(41L); FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		ExecPlacementCaps caps = new ExecPlacementCaps(); caps.allowCP_FOUT = false; caps.allowFED_LOUT = false;
		Vertex vertex = new Vertex(hop, Privacy.PRIVATE, FType.ROW, caps);
		vertex.setMetadata(1, 1, List.of()); vertex.setCost(8, 4, 4); graph.addVertex(vertex);
		invoke(graph, "applyConcreteAnchorCapabilityGate", new Class<?>[0]);
		Map<Long,ExecType> exec = new HashMap<>(); exec.put(hop.getHopID(), ExecType.FED);
		Map<Long,FederatedOutput> out = new HashMap<>(); out.put(hop.getHopID(), FederatedOutput.LOUT);
		invoke(graph, "repairSelectionFixpoint", new Class<?>[]{Map.class, Map.class}, exec, out);
		return new Fixture("C2-MS-02-CAPS-FIXPOINT", roles(List.of(hop), new String[]{"repairVertex"}),
			Map.of("SYNTHETIC:C2-MS-02:repair:"+hop.getClass().getName()+":"+hop.getOpString(),"repairVertex"),
			Map.of("repairVertex", exec.get(hop.getHopID()) + "/" + out.get(hop.getHopID())), List.of(), List.of(),
			Map.of("raw", "FED/LOUT", "caps", "CP_LOUT,FED_FOUT", "repair", "CAPS_TO_FED_FOUT"),
			analysis(List.of(hop)),graph,graph.getGraph(),List.copyOf(graph.getSelectedObligations()),List.of());
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
		graph.getOptimalPlan(); register(graph);
		var obligation = graph.getSelectedObligations().stream().filter(o -> o.getKind() == ObligationKind.D).findFirst().orElseThrow();
		var spec = FederatedLocalMaterializeRegistry.snapshot(-1L).get(producer.getHopID());
		List<Hop> hops=List.of(producer,c1h,c2h); String[] names={"producer","consumer1","consumer2"};
		return new Fixture("C2-MS-03-SHARED-DOWNLOAD", roles(hops,names), aliases("C2-MS-03",hops,names),
			Map.of("producer","FED/FOUT","consumer1","CP/LOUT","consumer2","CP/LOUT"),
			List.of("D:producer->"+obligation.getConsumerHopIds().size()),
			List.of("LOCAL:consumers="+spec.getConsumerHopIds().size()+":reason="+spec.getReason()),
			Map.of("consumerCount","2","fType",String.valueOf(spec.getFTypeHint())),analysis(hops),graph,
			graph.getGraph(),List.copyOf(graph.getSelectedObligations()),List.of(spec));
	}

	private static Fixture anchoredUpload() throws Exception {
		reset(); FederatedPlannerUtils.registerFedInitVar("X_anchor", FType.ROW,
			"localhost:1234/X1@0:0-50:100;localhost:1235/X2@50:0-100:100");
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
		graph.addRequiredLocalInputEdge(local.getHopID(), child.getHopID()); graph.getOptimalPlan(); register(graph);
		var obligation=graph.getSelectedObligations().stream().filter(o->o.getKind()==ObligationKind.U).findFirst().orElseThrow();
		var refed=FederatedRefedRegistry.snapshot(-1L).get(child.getHopID());
		DataOp localInput = new DataOp("local_cp_fout_input", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 100, 100, 10000, 1000);
		Hop computed = HopRewriteUtils.createBinary(localInput, new LiteralOp(3.0), OpOp2.PLUS);
		DataOp cpFout = new DataOp("Y_cp_fout", DataType.MATRIX, ValueType.FP64, computed,
			OpOpData.TRANSIENTWRITE, null);
		FederatedPlanMinSTGraph cpFoutGraph = new FederatedPlanMinSTGraph();
		ExecPlacementCaps cpFoutCaps = new ExecPlacementCaps(); cpFoutCaps.allowCP_LOUT=false;
		cpFoutCaps.allowFED_LOUT=false; cpFoutCaps.allowFED_FOUT=false;
		Vertex cpFoutVertex=vertex(cpFout,cpFoutCaps,0,0,100); cpFoutGraph.addVertex(cpFoutVertex);
		cpFoutGraph.setVertexCost(cpFoutVertex); cpFoutGraph.addExecPlacementResultEdge(cpFoutVertex);
		weighted(cpFoutGraph.getGraph(),SOURCE,FederatedPlanMinSTPlanner.placementId(cpFout.getHopID()),1000);
		cpFoutGraph.getOptimalPlan();
		Method cpfoutRegister=FederatedPlanMinSTCut.class.getDeclaredMethod(
			"registerMinstCpfoutSelections",FederatedPlanMinSTGraph.class,Map.class);
		cpfoutRegister.setAccessible(true); Map<Long,FType> fTypes=new HashMap<>(); fTypes.put(cpFout.getHopID(),FType.BROADCAST);
		cpfoutRegister.invoke(null,cpFoutGraph,fTypes);
		var fout=FederatedFoutMaterializeRegistry.snapshot(-1L).get(cpFout.getHopID());
		if(refed==null || fout==null) throw new IllegalStateException("missing selected U/CP-FOUT registry refed="
			+(refed!=null)+" fout="+(fout!=null)+" cpState="+cpFout.getForcedExecType()+"/"+cpFout.getFederatedOutput()
			+" keys="+FederatedFoutMaterializeRegistry.snapshot(-1L).keySet());
		List<Hop> hops=List.of(child,fed,local,cpFout); String[] names={"child","fedConsumer","localConsumer","cpFout"};
		return new Fixture("C2-MS-04-ANCHORED-UPLOAD",roles(hops,names),aliases("C2-MS-04",hops,names),
			Map.of("child","CP/LOUT","fedConsumer","FED/FOUT","localConsumer","CP/LOUT","cpFout","CP/FOUT"),
			List.of("U:child->"+obligation.getConsumerHopIds().size()),
			List.of("REFED:anchorKey="+refed.getAnchorKey(),"FOUT:fType="+fout.getFTypeHint()),
			Map.of("concreteAnchor","true","scope","-1"),analysis(hops),graph,graph.getGraph(),
			List.copyOf(graph.getSelectedObligations()),List.of(refed,fout));
	}

	private static Fixture missingAnchor() {
		reset(); FederatedPlanMinSTGraph graph=new FederatedPlanMinSTGraph();
		Hop hop=HopRewriteUtils.createBinary(new LiteralOp(2.0),new LiteralOp(3.0),OpOp2.PLUS);
		ExecPlacementCaps caps=new ExecPlacementCaps(); caps.allowFED_LOUT=false; caps.allowFED_FOUT=false;
		Vertex v=vertex(hop,caps,0,0,100); graph.addVertex(v); graph.setVertexCost(v); graph.addExecPlacementResultEdge(v);
		graph.getOptimalPlan();
		return new Fixture("C2-MS-05-MISSING-ANCHOR",roles(List.of(hop),new String[]{"candidate"}),
			aliases("C2-MS-05",List.of(hop),new String[]{"candidate"}),
			Map.of("candidate",hop.getForcedExecType()+"/"+hop.getFederatedOutput()),List.of(),List.of(),
			Map.of("concreteAnchor","false","reason","MISSING_CONCRETE_ANCHOR"),analysis(List.of(hop)),graph,
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
	private static Map<String,String> assignments(List<Hop> hops,String[] names,Set<Long> source) {
		Map<String,String> m=new LinkedHashMap<>(); for(int i=0;i<hops.size();i++){long id=hops.get(i).getHopID();
			m.put(names[i],(source.contains(FederatedPlanMinSTPlanner.computeId(id))?"FED":"CP")+"/"+
				(source.contains(FederatedPlanMinSTPlanner.placementId(id))?"FOUT":"LOUT"));} return Map.copyOf(m);
	}
	private static Map<String,String> roles(List<Hop> hops,String[] names){Map<String,String> m=new LinkedHashMap<>();
		for(int i=0;i<hops.size();i++)m.put(names[i],names[i]+":"+hops.get(i).getClass().getName()+":"+hops.get(i).getOpString());return Map.copyOf(m);}
	private static Map<String,String> aliases(String fixture,List<Hop> hops,String[] names){Map<String,String> m=new LinkedHashMap<>();
		for(int i=0;i<hops.size();i++)m.put("SYNTHETIC:"+fixture+":"+names[i]+":"+hops.get(i).getClass().getName()+":"+hops.get(i).getOpString(),names[i]);return Map.copyOf(m);}
	private static String node(long n,List<Hop> hops,String[] names){if(n==SOURCE)return"SOURCE";if(n==SINK)return"SINK";
		for(int i=0;i<hops.size();i++){long id=hops.get(i).getHopID();if(n==FederatedPlanMinSTPlanner.computeId(id))return names[i]+":C";
			if(n==FederatedPlanMinSTPlanner.placementId(id))return names[i]+":P";}throw new IllegalStateException("foreign cut node "+n);}
	private static void weighted(Graph<Long,DefaultWeightedEdge> g,long s,long t,double w){DefaultWeightedEdge e=g.addEdge(s,t);g.setEdgeWeight(e,w);}
	private static void register(FederatedPlanMinSTGraph graph)throws Exception{Method m=FederatedPlanMinSTCut.class.getDeclaredMethod(
		"registerMinstSelectedObligations",FederatedPlanMinSTGraph.class,Map.class);m.setAccessible(true);m.invoke(null,graph,new HashMap<Long,FType>());}
	private static void invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
	private static void reset(){FederatedPlannerUtils.resetFederatedPlannerRunState();FederatedPlannerUtils.clearFedInitVars();
		FederatedRefedRegistry.clear();FederatedFoutMaterializeRegistry.clear();FederatedLocalMaterializeRegistry.clear();}
	private R4ExactPrivateCostMinstFixtures(){ }
}
