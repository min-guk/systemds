/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultWeightedEdge;

/** Exact JGraphT replay over offline, key-normalized MinST C/P vertices. */
public final class LegacyMinstOfflineSelectedCapture {
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;

	public record RetainedFullPath(long seed, PlacementAnalysis analysis, MinStPlacementInput input,
		MinStPlacementAdapter.Selection selection, List<String> selectedStates, String semanticFacts) {
		public RetainedFullPath {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(input, "input");
			Objects.requireNonNull(selection, "selection");
			selectedStates = List.copyOf(Objects.requireNonNull(selectedStates, "selectedStates"));
			Objects.requireNonNull(semanticFacts, "semanticFacts");
			if(input.analysis() != analysis || selection.analysis() != analysis
				|| selection.selectedReceipts().size() != analysis.occurrences().size())
				throw new IllegalArgumentException("Retained MinST owner identity differs");
		}
	}

	public static List<String> capture() throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-01"));
		List<PlacementAnalysis.HopOccurrenceProjection> occurrences = analysis.occurrences().subList(0, 4);
		Map<Long,String> keys = occurrences.stream().collect(Collectors.toMap(o -> o.hop().getHopID(),
			o -> o.key().normalizedSignature(), (a, b) -> a, LinkedHashMap::new));
		FederatedPlannerUtils.registerFedInitVar("X_quartet_anchor", FType.ROW,
			"localhost:1234/X1@0:0-2:2;localhost:1235/X2@2:0-4:2");
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		for(int i = 0; i < occurrences.size(); i++) {
			PlacementAnalysis.HopOccurrenceProjection occurrence = occurrences.get(i);
			ExecPlacementCaps caps = new ExecPlacementCaps();
			caps.fedFoutMode = ExecPlacementCaps.FedFoutMode.NATIVE;
			if(i == 2) {
				caps.allowCP_FOUT = false;
				caps.allowFED_FOUT = false;
			}
			else if(i == 3) {
				caps.allowCP_LOUT = false;
				caps.allowCP_FOUT = false;
			}
			Vertex vertex = new Vertex(occurrence.hop(), Privacy.PRIVATE, FType.ROW, FType.ROW, caps);
			vertex.setMetadata(1.0, 1.0, List.of());
			vertex.setCost(8.0, 4.0, 4.0);
			graph.addVertex(vertex);
		}
		Method gate = FederatedPlanMinSTGraph.class.getDeclaredMethod("applyConcreteAnchorCapabilityGate");
		gate.setAccessible(true); gate.invoke(graph);
		Graph<Long,DefaultWeightedEdge> nativeGraph = graph.getGraph();
		ExecType[] exec = {ExecType.CP, ExecType.CP, ExecType.FED, ExecType.FED};
		FederatedOutput[] output = {FederatedOutput.LOUT, FederatedOutput.FOUT,
			FederatedOutput.LOUT, FederatedOutput.FOUT};
		for(int i = 0; i < occurrences.size(); i++) {
			long id = occurrences.get(i).hop().getHopID();
			weighted(nativeGraph, SOURCE, FederatedPlanMinSTPlanner.computeId(id),
				exec[i] == ExecType.FED ? 8.0 : 1.0);
			weighted(nativeGraph, FederatedPlanMinSTPlanner.computeId(id), SINK,
				exec[i] == ExecType.FED ? 1.0 : 8.0);
			weighted(nativeGraph, SOURCE, FederatedPlanMinSTPlanner.placementId(id),
				output[i] == FederatedOutput.FOUT ? 8.0 : 1.0);
			weighted(nativeGraph, FederatedPlanMinSTPlanner.placementId(id), SINK,
				output[i] == FederatedOutput.FOUT ? 1.0 : 8.0);
		}
		PushRelabelMFImpl<Long,DefaultWeightedEdge> solver = new PushRelabelMFImpl<>(nativeGraph);
		solver.calculateMinCut(SOURCE, SINK);
		Set<Long> rawSource = solver.getSourcePartition();
		Map<Long,ExecType> finalExec = new LinkedHashMap<>();
		Map<Long,FederatedOutput> finalOutput = new LinkedHashMap<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : occurrences) {
			long id = occurrence.hop().getHopID();
			finalExec.put(id, rawSource.contains(FederatedPlanMinSTPlanner.computeId(id)) ? ExecType.FED : ExecType.CP);
			finalOutput.put(id, rawSource.contains(FederatedPlanMinSTPlanner.placementId(id))
				? FederatedOutput.FOUT : FederatedOutput.LOUT);
		}
		Method repair = FederatedPlanMinSTGraph.class.getDeclaredMethod("repairSelectionFixpoint", Map.class, Map.class);
		repair.setAccessible(true);
		repair.invoke(graph, finalExec, finalOutput);
		FederatedPlanMinSTGraph reverseGraph = new FederatedPlanMinSTGraph();
		for(int i = occurrences.size() - 1; i >= 0; i--) {
			ExecPlacementCaps caps = new ExecPlacementCaps(); caps.fedFoutMode = ExecPlacementCaps.FedFoutMode.NATIVE;
			if(i == 2) { caps.allowCP_FOUT = false; caps.allowFED_FOUT = false; }
			else if(i == 3) { caps.allowCP_LOUT = false; caps.allowCP_FOUT = false; }
			Vertex vertex = new Vertex(occurrences.get(i).hop(), Privacy.PRIVATE, FType.ROW, FType.ROW, caps);
			vertex.setMetadata(1.0, 1.0, List.of()); vertex.setCost(8.0, 4.0, 4.0); reverseGraph.addVertex(vertex);
		}
		gate.invoke(reverseGraph);
		Graph<Long,DefaultWeightedEdge> reverseNative = reverseGraph.getGraph();
		for(int i = occurrences.size() - 1; i >= 0; i--) {
			long id = occurrences.get(i).hop().getHopID();
			weighted(reverseNative, SOURCE, FederatedPlanMinSTPlanner.computeId(id), exec[i] == ExecType.FED ? 8.0 : 1.0);
			weighted(reverseNative, FederatedPlanMinSTPlanner.computeId(id), SINK, exec[i] == ExecType.FED ? 1.0 : 8.0);
			weighted(reverseNative, SOURCE, FederatedPlanMinSTPlanner.placementId(id), output[i] == FederatedOutput.FOUT ? 8.0 : 1.0);
			weighted(reverseNative, FederatedPlanMinSTPlanner.placementId(id), SINK, output[i] == FederatedOutput.FOUT ? 1.0 : 8.0);
		}
		PushRelabelMFImpl<Long,DefaultWeightedEdge> reverseSolver = new PushRelabelMFImpl<>(reverseNative);
		reverseSolver.calculateMinCut(SOURCE, SINK);
		Set<Long> reverseSource = reverseSolver.getSourcePartition();
		if(Double.compare(solver.getCutCapacity(), reverseSolver.getCutCapacity()) != 0)
			throw new IllegalStateException("Forward/reverse MinST capacity mismatch");
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : occurrences) {
			long id = occurrence.hop().getHopID();
			if(rawSource.contains(FederatedPlanMinSTPlanner.computeId(id))
				!= reverseSource.contains(FederatedPlanMinSTPlanner.computeId(id))
				|| rawSource.contains(FederatedPlanMinSTPlanner.placementId(id))
				!= reverseSource.contains(FederatedPlanMinSTPlanner.placementId(id)))
				throw new IllegalStateException("Forward/reverse normalized MinST partition mismatch for "
					+ occurrence.key().normalizedSignature());
		}
		graph.getOptimalPlan();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : occurrences) {
			long id = occurrence.hop().getHopID();
			if(occurrence.hop().getForcedExecType() != finalExec.get(id)
				|| occurrence.hop().getFederatedOutput() != finalOutput.get(id))
				throw new IllegalStateException("Replay/full-path state mismatch for "
					+ occurrence.key().normalizedSignature());
		}

		List<String> rows = new ArrayList<>();
		rows.add("C2-MS-01-EQUAL-CUT|MINST_CUT_OBJECTIVE|evidence=EXACT_PRIVATE_REPLAY|seed=-1|capacity="
			+ Double.toHexString(solver.getCutCapacity()) + "|sourceCount=" + rawSource.size()
			+ "|reverseCapacity=" + Double.toHexString(reverseSolver.getCutCapacity())
			+ "|orderControl=FORWARD_EQUALS_REVERSE"
			+ "|tieLimit=NONE(MINST_ALTERNATE_MINCUTS_NOT_ENUMERATED)");
		for(int i = 0; i < occurrences.size(); i++) {
			PlacementAnalysis.HopOccurrenceProjection occurrence = occurrences.get(i);
			long id = occurrence.hop().getHopID();
			rows.add("C2-MS-06-STATE-QUARTET|MINST_FINAL_STATE|evidence=EXACT_PRIVATE_REPLAY|seed=-1|key="
				+ occurrence.key().normalizedSignature() + "|rawExec="
				+ (rawSource.contains(FederatedPlanMinSTPlanner.computeId(id)) ? ExecType.FED : ExecType.CP)
				+ "|rawOutput=" + (rawSource.contains(FederatedPlanMinSTPlanner.placementId(id))
					? FederatedOutput.FOUT : FederatedOutput.LOUT)
				+ "|finalExec=" + finalExec.get(id) + "|finalOutput=" + finalOutput.get(id)
				+ "|fType=ROW|repair=" + ((finalExec.get(id) == exec[i] && finalOutput.get(id) == output[i])
					? "NONE" : "FIXPOINT_REPAIR"));
		}
		for(DefaultWeightedEdge edge : solver.getCutEdges())
			rows.add("C2-MS-01-EQUAL-CUT|MINST_CUT_EDGE|evidence=EXACT_PRIVATE_REPLAY|from="
				+ nodeKey(nativeGraph.getEdgeSource(edge), keys) + "|to="
				+ nodeKey(nativeGraph.getEdgeTarget(edge), keys) + "|capacity="
				+ Double.toHexString(nativeGraph.getEdgeWeight(edge)));

		rows.addAll(captureCanonicalPrivateFixtures());
		rows.add(captureFullPath("C2-MS-07-TRTW-SHARED-D", "B-16"));
		rows.add(captureFullPath("C2-MS-08-LOOP-EQUAL-FIXPOINT", "B-05"));
		rows.add(captureFullPath("C2-X-09-BRANCH-JOIN", "B-02"));
		rows.add(captureFullPath("C2-X-10-FUNCTION-CALLSITE", "B-07"));
		rows.add(captureFullPath("C2-X-11-CLONE-RECOMPILE", "B-09"));
		return rows;
	}

	private static List<String> captureCanonicalPrivateFixtures() throws Exception {
		Map<String,R4ExactPrivateCostMinstFixtures.Fixture> fixtures=R4ExactPrivateCostMinstFixtures.all().stream()
			.collect(Collectors.toMap(R4ExactPrivateCostMinstFixtures.Fixture::id,f->f));
		List<String> rows=new ArrayList<>();

		var ms02=fixtures.get("C2-MS-02-CAPS-FIXPOINT");
		String ms02Key=roleKey(ms02,"repairVertex"), ms02State=ms02.assignments().get("repairVertex");
		String[] raw=ms02.facts().get("raw").split("/"); String[] fin=ms02State.split("/");
		rows.add("C2-MS-02-CAPS-FIXPOINT|MINST_CAPS_REPAIR|evidence=EXACT_PRIVATE_REPLAY|seed=-1|key="+ms02Key
			+"|caps="+ms02.facts().get("caps")+"|rawExec="+raw[0]+"|rawOutput="+raw[1]
			+"|finalExec="+fin[0]+"|finalOutput="+fin[1]+"|capabilityGateApplied=true|fullPathParity=true|repair="
			+ms02.facts().get("repair"));

		var ms03=fixtures.get("C2-MS-03-SHARED-DOWNLOAD");
		var local=(FederatedLocalMaterializeRegistry.LocalMaterializeSpec)ms03.registryObjects().stream()
			.filter(FederatedLocalMaterializeRegistry.LocalMaterializeSpec.class::isInstance).findFirst().orElseThrow();
		rows.add("C2-MS-03-SHARED-DOWNLOAD|REGISTRY_LOCAL_MATERIALIZE|evidence=EXACT_PRIVATE_REPLAY|producer="
			+roleKey(ms03,"producer")+"|consumers="+roleKeys(ms03,local.getConsumerHopIds())+"|scope=-1|consumerCount="
			+local.getConsumerHopIds().size()+"|fType="+local.getFTypeHint()+"|reason="+local.getReason()+"|source=FROZEN_SELECTED_D");

		var ms04=fixtures.get("C2-MS-04-ANCHORED-UPLOAD");
		var refed=(FederatedRefedRegistry.AnchorSpec)ms04.registryObjects().stream()
			.filter(FederatedRefedRegistry.AnchorSpec.class::isInstance).findFirst().orElseThrow();
		var fout=(FederatedFoutMaterializeRegistry.MaterializeSpec)ms04.registryObjects().stream()
			.filter(FederatedFoutMaterializeRegistry.MaterializeSpec.class::isInstance).findFirst().orElseThrow();
		var upload=ms04.selectedObligationObjects().stream().map(FederatedPlanMinSTGraph.SelectedObligation.class::cast)
			.filter(o->o.getKind()==FederatedPlanMinSTGraph.ObligationKind.U).findFirst().orElseThrow();
		rows.add("C2-MS-04-ANCHORED-UPLOAD|REGISTRY_REFED|evidence=EXACT_PRIVATE_REPLAY|producer="
			+roleKey(ms04,"child")+"|consumers="+roleKeys(ms04,upload.getConsumerHopIds())+"|scope=-1|anchorHop="
			+(refed.getAnchorHopId()<0?"SCOPE_ANCHOR":roleKey(ms04,refed.getAnchorHopId()))+"|anchorKey="
			+refed.getAnchorKey()+"|source=FROZEN_SELECTED_U");
		rows.add("C2-MS-04-ANCHORED-UPLOAD|REGISTRY_FOUT_MATERIALIZE|evidence=EXACT_PRIVATE_REPLAY|producer="
			+roleKey(ms04,"cpFout")+"|scope=-1|fType="+fout.getFTypeHint()+"|anchorLabel="+fout.getAnchorLabel()
			+"|anchorKey="+fout.getAnchorKey()+"|source=FROZEN_SELECTED_CP_FOUT");

		var ms05=fixtures.get("C2-MS-05-MISSING-ANCHOR"); String[] state=ms05.assignments().get("candidate").split("/");
		var candidate=ms05.analysis().occurrences().stream()
			.filter(o->o.key().normalizedSignature().equals(roleKey(ms05,"candidate"))).findFirst().orElseThrow();
		var caps=ms05.producerGraph().getVertex(candidate.hop().getHopID()).getCaps();
		String reason=state[0].equals("CP") && state[1].equals("LOUT") && caps.allowCP_LOUT ? "NONE" : ms05.facts().get("reason");
		rows.add("C2-MS-05-MISSING-ANCHOR|MINST_CAPABILITY_GATE|evidence=EXACT_PRIVATE_REPLAY|seed=-1|key="
			+roleKey(ms05,"candidate")+"|capsBefore=CP_LOUT="+caps.allowCP_LOUT+"|CP_FOUT="+caps.allowCP_FOUT
			+"|FED_LOUT="+caps.allowFED_LOUT+"|FED_FOUT="+caps.allowFED_FOUT+"|concreteAnchor=false|finalExec="+state[0]
			+"|finalOutput="+state[1]+"|reason="+reason);
		return rows;
	}

	private static String roleKey(R4ExactPrivateCostMinstFixtures.Fixture fixture,String role) {
		return fixture.literalAliases().entrySet().stream().filter(e->e.getValue().equals(role)).map(Map.Entry::getKey)
			.findFirst().orElseThrow(()->new IllegalStateException("Missing canonical role "+fixture.id()+"/"+role));
	}
	private static String roleKey(R4ExactPrivateCostMinstFixtures.Fixture fixture,long hopId) {
		var occurrence=fixture.analysis().occurrences().stream().filter(o->o.hop().getHopID()==hopId).findFirst().orElseThrow();
		String key=occurrence.key().normalizedSignature();
		if(!fixture.literalAliases().containsKey(key)) throw new IllegalStateException("Missing canonical selected Hop "+fixture.id());
		return key;
	}
	private static List<String> roleKeys(R4ExactPrivateCostMinstFixtures.Fixture fixture,List<Long> hopIds) {
		return hopIds.stream().map(id->roleKey(fixture,id)).sorted().collect(Collectors.toList());
	}

	public static RetainedFullPath captureFullPath(DMLProgram program, PlacementAnalysis analysis, long seed) {
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(analysis, "analysis");
		MinStPlacementInput input = new FederatedPlanMinSTCut().rewriteProgram(program, null, null, analysis);
		MinStPlacementAdapter.Selection selection = new MinStPlacementAdapter().select(analysis, input);
		List<String> states = selection.selectedReceipts().stream()
			.map(receipt -> receipt.planningKey().normalizedSignature() + '=' + receipt.execType() + '/'
				+ receipt.output()).sorted().toList();
		return new RetainedFullPath(seed, analysis, input, selection, states,
			structuralFacts(analysis, selection));
	}

	private static String captureFullPath(String rowId, String fixture) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		RetainedFullPath retained = captureFullPath(program, analysis, -1L);
		return rowId + "|MINST_FULL_OFFLINE_SELECTION|evidence=ACTUAL_RETAINED|seed=" + retained.seed()
			+ "|fixture=" + fixture + "|selectedStates=" + retained.selectedStates()
			+ "|semanticFacts=" + retained.semanticFacts();
	}

	private static String structuralFacts(PlacementAnalysis analysis, MinStPlacementAdapter.Selection selection) {
		long none = selection.selectedReceipts().stream()
			.filter(receipt -> receipt.execType() == null && receipt.output() == FederatedOutput.NONE).count();
		boolean allLocal = selection.selectedReceipts().stream().allMatch(receipt ->
			receipt.execType() == null && receipt.output() == FederatedOutput.NONE
				|| receipt.execType() == ExecType.CP && receipt.output() == FederatedOutput.LOUT);
		boolean contextual = analysis.graph().nodes().stream().anyMatch(node ->
			node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_BODY_NON_EMITTED);
		if(contextual)
			return "classification=ACTUAL_CONTEXTUAL_NONE,noneNodeCount=" + none
				+ ",nodeKind=FUNCTION_BODY_NON_EMITTED,emittedWork=false,caps=NONE,reason=NON_EMITTED_FUNCTION_BODY_CONTEXT";
		boolean recompile = analysis.graph().nodes().stream().flatMap(node -> node.exclusions().stream()).anyMatch(
			exclusion -> exclusion.reasonCode()
				== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode.RECOMPILE_CP_FOUT);
		if(recompile)
			return "classification=ACTUAL_ALL_LOCAL,recompileCpFout=UNSUPPORTED,reason=RECOMPILE_CP_FOUT_FORBIDDEN";
		boolean loop = analysis.graph().nodes().stream().anyMatch(node ->
			node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.LOOP_PHI);
		if(loop)
			return "classification=ACTUAL_ALL_LOCAL_LOOP,allCpLout=" + allLocal
				+ ",equalCut=NONE,repair=NONE,reason=NO_EQUAL_CUT_CLAIM";
		boolean branch = analysis.graph().nodes().stream().anyMatch(node ->
			node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.BRANCH_JOIN);
		if(branch)
			return "classification=ACTUAL_ALL_LOCAL,allCpLout=" + allLocal;
		return "classification=ACTUAL_ALL_LOCAL_NO_TRTW_RELATION,allCpLout=" + allLocal
			+ ",registry=NONE,reason=COMPILED_FIXTURE_HAS_NO_TRTW_SELECTED_RELATION";
	}

	private static void weighted(Graph<Long,DefaultWeightedEdge> graph, long source, long target, double weight) {
		DefaultWeightedEdge edge = graph.addEdge(source, target);
		graph.setEdgeWeight(edge, weight);
	}

	private static String nodeKey(long node, Map<Long,String> keys) {
		if(node == SOURCE) return "SOURCE";
		if(node == SINK) return "SINK";
		for(Map.Entry<Long,String> entry : keys.entrySet()) {
			if(node == FederatedPlanMinSTPlanner.computeId(entry.getKey())) return entry.getValue() + ":C";
			if(node == FederatedPlanMinSTPlanner.placementId(entry.getKey())) return entry.getValue() + ":P";
		}
		throw new IllegalStateException("Unmapped selected cut node " + node);
	}

	private LegacyMinstOfflineSelectedCapture() { }
}
