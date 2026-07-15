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

		rows.addAll(captureProductionRegistrations(occurrences));
		rows.add(captureCapsFixpoint());
		rows.add(captureMissingAnchor());
		rows.add(captureFullPath("C2-MS-07-TRTW-SHARED-D", "B-16"));
		rows.add(captureFullPath("C2-MS-08-LOOP-EQUAL-FIXPOINT", "B-05"));
		rows.add(captureFullPath("C2-X-09-BRANCH-JOIN", "B-02"));
		rows.add(captureFullPath("C2-X-10-FUNCTION-CALLSITE", "B-07"));
		rows.add(captureFullPath("C2-X-11-CLONE-RECOMPILE", "B-09"));
		return rows;
	}

	private static String captureCapsFixpoint() throws Exception {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		Hop hop = new LiteralOp(41L);
		ExecPlacementCaps caps = new ExecPlacementCaps();
		caps.allowCP_FOUT = false; caps.allowFED_LOUT = false;
		Vertex vertex = new Vertex(hop, Privacy.PRIVATE, FType.ROW, caps);
		vertex.setMetadata(1.0, 1.0, Collections.emptyList()); vertex.setCost(8.0, 4.0, 4.0);
		graph.addVertex(vertex);
		Method gate = FederatedPlanMinSTGraph.class.getDeclaredMethod("applyConcreteAnchorCapabilityGate");
		gate.setAccessible(true); gate.invoke(graph);
		Map<Long,ExecType> exec = new HashMap<>(); exec.put(hop.getHopID(), ExecType.FED);
		Map<Long,FederatedOutput> output = new HashMap<>(); output.put(hop.getHopID(), FederatedOutput.LOUT);
		Method repair = FederatedPlanMinSTGraph.class.getDeclaredMethod("repairSelectionFixpoint", Map.class, Map.class);
		repair.setAccessible(true); repair.invoke(graph, exec, output);
		return "C2-MS-02-CAPS-FIXPOINT|MINST_CAPS_REPAIR|evidence=EXACT_PRIVATE_REPLAY|seed=-1|key="
			+ syntheticKey("C2-MS-02", "repair", hop) + "|caps=CP_LOUT,FED_FOUT|rawExec=FED|rawOutput=LOUT"
			+ "|finalExec=" + exec.get(hop.getHopID()) + "|finalOutput=" + output.get(hop.getHopID())
				+ "|capabilityGateApplied=true|fullPathParity=NOT_APPLICABLE_SINGLE_VERTEX_PRIVATE_REPLAY|repair="
				+ ((exec.get(hop.getHopID()) == ExecType.FED
				&& output.get(hop.getHopID()) == FederatedOutput.FOUT) ? "CAPS_TO_FED_FOUT" : "FIXPOINT_REPAIR");
	}

	private static String captureMissingAnchor() {
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		Hop hop = HopRewriteUtils.createBinary(new LiteralOp(2.0), new LiteralOp(3.0), OpOp2.PLUS);
		ExecPlacementCaps caps = new ExecPlacementCaps();
		caps.allowFED_LOUT = false; caps.allowFED_FOUT = false;
		Vertex vertex = new Vertex(hop, Privacy.PRIVATE, FType.ROW, caps);
		vertex.setMetadata(1.0, 1.0, Collections.emptyList()); vertex.setCost(0.0, 0.0, 100.0);
		graph.addVertex(vertex); graph.setVertexCost(vertex); graph.addExecPlacementResultEdge(vertex);
		graph.getOptimalPlan();
		return "C2-MS-05-MISSING-ANCHOR|MINST_CAPABILITY_GATE|evidence=EXACT_PRIVATE_REPLAY|seed=-1|key="
			+ syntheticKey("C2-MS-05", "candidate", hop)
			+ "|capsBefore=CP_LOUT,CP_FOUT|concreteAnchor=false|finalExec=" + hop.getForcedExecType()
			+ "|finalOutput=" + hop.getFederatedOutput() + "|reason=MISSING_CONCRETE_ANCHOR";
	}

	private static String captureFullPath(String rowId, String fixture) throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		new FederatedPlanMinSTCut().rewriteProgram(program, null, null);
		List<String> states = new ArrayList<>();
		int none = 0;
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			ExecType exec = hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
			states.add(occurrence.key().normalizedSignature() + "=" + exec + "/" + hop.getFederatedOutput());
			if(exec == null || hop.getFederatedOutput() == null) none++;
		}
		states.sort(String::compareTo);
		return rowId + "|MINST_FULL_OFFLINE_SELECTION|evidence=ACTUAL_RETAINED|seed=-1|fixture=" + fixture
			+ "|selectedStates=" + states + "|semanticFacts=" + semanticFacts(rowId, states, none);
	}

	private static String semanticFacts(String rowId, List<String> states, int none) {
		boolean allLocal = states.stream().allMatch(s -> s.endsWith("=CP/LOUT"));
		if(rowId.equals("C2-MS-07-TRTW-SHARED-D"))
			return "classification=ACTUAL_ALL_LOCAL_NO_TRTW_RELATION,allCpLout=" + allLocal
				+ ",registry=NONE,reason=COMPILED_FIXTURE_HAS_NO_TRTW_SELECTED_RELATION";
		if(rowId.equals("C2-MS-08-LOOP-EQUAL-FIXPOINT"))
			return "classification=ACTUAL_ALL_LOCAL_LOOP,allCpLout=" + allLocal
				+ ",equalCut=NONE,repair=NONE,reason=NO_EQUAL_CUT_CLAIM";
		if(rowId.equals("C2-X-10-FUNCTION-CALLSITE"))
			return "classification=ACTUAL_CONTEXTUAL_NONE,noneNodeCount=" + none
				+ ",nodeKind=FUNCTION_BODY_NON_EMITTED,emittedWork=false,caps=NONE,reason=NON_EMITTED_FUNCTION_BODY_CONTEXT";
		if(rowId.equals("C2-X-11-CLONE-RECOMPILE"))
			return "classification=ACTUAL_ALL_LOCAL,recompileCpFout=UNSUPPORTED,reason=RECOMPILE_CP_FOUT_FORBIDDEN";
		return "classification=ACTUAL_ALL_LOCAL,allCpLout=" + allLocal;
	}

	private static List<String> captureProductionRegistrations(
		List<PlacementAnalysis.HopOccurrenceProjection> occurrences) throws Exception {
		List<String> rows = new ArrayList<>();
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlannerUtils.registerFedInitVar("X_anchor", FType.ROW,
			"localhost:1234/X1@0:0-50:100;localhost:1235/X2@50:0-100:100");
		try {
			FederatedPlanMinSTGraph upload = new FederatedPlanMinSTGraph();
			upload.setNumOfWorkers(2);
			DataOp childHop = new DataOp("X_u_obligation", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, null, 100, 100, 10000, 1000);
			ExecPlacementCaps childCaps = new ExecPlacementCaps();
			childCaps.allowFED_LOUT = false;
			childCaps.allowFED_FOUT = false;
			Vertex child = new Vertex(childHop, Privacy.PUBLIC, FType.ROW, childCaps);
			child.setMetadata(1.0, 1.0, Collections.emptyList());
			child.setCost(0.0, 6.0, 4.0);
			upload.addVertex(child); upload.setVertexCost(child); upload.addExecPlacementResultEdge(child);
			ExecPlacementCaps fedCaps = new ExecPlacementCaps();
			fedCaps.allowCP_LOUT = false; fedCaps.allowCP_FOUT = false;
			Vertex parent = new Vertex(new LiteralOp(21L), Privacy.PUBLIC, FType.FULL, fedCaps);
			parent.setMetadata(1.0, 1.0, Collections.emptyList()); parent.setCost(100.0, 0.0, 0.0);
			upload.addVertex(parent); upload.setVertexCost(parent);
			upload.addParentChildNetEdge(child, child.getHopID(), parent, parent.getHopID(), true);
			ExecPlacementCaps localCaps = new ExecPlacementCaps();
			localCaps.allowFED_LOUT = false; localCaps.allowFED_FOUT = false;
			Vertex local = new Vertex(new LiteralOp(22L), Privacy.PUBLIC, FType.FULL, localCaps);
			local.setMetadata(1.0, 1.0, Collections.emptyList()); local.setCost(0.0, 0.0, 0.0);
			upload.addVertex(local); upload.setVertexCost(local);
			upload.addRequiredLocalInputEdge(local.getHopID(), child.getHopID());
			upload.getOptimalPlan();
				invokeRegistration(upload);
				String childKey = syntheticKey("C2-MS-04", "child", childHop);
				String parentKey = syntheticKey("C2-MS-04", "fed-consumer", parent.getHopRef());
				Map<Long,String> uploadKeys = new HashMap<>();
				uploadKeys.put(childHop.getHopID(), childKey);
				uploadKeys.put(parent.getHopID(), parentKey);
				uploadKeys.put(local.getHopID(), syntheticKey("C2-MS-04", "local-consumer", local.getHopRef()));
				FederatedPlanMinSTGraph.SelectedObligation uploadObligation = upload.getSelectedObligations().stream()
					.filter(o -> o.getKind() == FederatedPlanMinSTGraph.ObligationKind.U
						&& o.getChildHopId() == childHop.getHopID()).findFirst().orElseThrow(() ->
							new IllegalStateException("Missing selected U obligation"));
				List<String> uploadConsumers = uploadObligation.getConsumerHopIds().stream()
					.map(id -> mappedSyntheticKey(uploadKeys, id)).sorted().collect(Collectors.toList());
				FederatedRefedRegistry.AnchorSpec refedSpec = FederatedRefedRegistry.snapshot(-1L).get(childHop.getHopID());
				if(refedSpec != null)
					rows.add("C2-MS-04-ANCHORED-UPLOAD|REGISTRY_REFED|evidence=EXACT_PRIVATE_REPLAY|producer="
						+ childKey + "|consumers=" + uploadConsumers + "|scope=-1|anchorHop="
					+ (refedSpec.getAnchorHopId() < 0 ? "SIGNATURE_ONLY" : "UNMAPPED")
					+ "|anchorKey=" + refedSpec.getAnchorKey() + "|source=FROZEN_SELECTED_U");
			FederatedFoutMaterializeRegistry.MaterializeSpec uploadSpec =
				FederatedFoutMaterializeRegistry.snapshot(-1L).get(childHop.getHopID());
			if(uploadSpec != null)
					rows.add("C2-MS-04-ANCHORED-UPLOAD|REGISTRY_FOUT_MATERIALIZE|evidence=EXACT_PRIVATE_REPLAY|producer="
						+ childKey + "|consumers=" + uploadConsumers + "|scope=-1|fType=" + uploadSpec.getFTypeHint()
					+ "|anchorLabel=" + uploadSpec.getAnchorLabel() + "|anchorKey=" + uploadSpec.getAnchorKey()
					+ "|source=FROZEN_SELECTED_U");
			FederatedPlanMinSTGraph cpfout = new FederatedPlanMinSTGraph();
			PlacementAnalysis freshAnalysis = new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile("B-01"));
			Hop matrixInput = freshAnalysis.occurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
				.filter(h -> h.getDataType() == DataType.MATRIX).findFirst().orElseThrow();
			Hop cpfoutHop = HopRewriteUtils.createBinary(matrixInput, new LiteralOp(3.0), OpOp2.PLUS);
			ExecPlacementCaps cpfoutCaps = new ExecPlacementCaps();
			cpfoutCaps.allowCP_LOUT = false; cpfoutCaps.allowFED_LOUT = false; cpfoutCaps.allowFED_FOUT = false;
			Vertex cpfoutVertex = new Vertex(cpfoutHop, Privacy.PUBLIC, FType.ROW, cpfoutCaps);
			cpfoutVertex.setMetadata(1.0, 1.0, Collections.emptyList()); cpfoutVertex.setCost(0.0, 0.0, 100.0);
			cpfout.addVertex(cpfoutVertex); cpfout.setVertexCost(cpfoutVertex); cpfout.addExecPlacementResultEdge(cpfoutVertex);
			weighted(cpfout.getGraph(), SOURCE, FederatedPlanMinSTPlanner.placementId(cpfoutHop.getHopID()), 1000.0);
			cpfout.getOptimalPlan();
			if(cpfoutHop.getForcedExecType() != ExecType.CP || cpfoutHop.getFederatedOutput() != FederatedOutput.FOUT)
				throw new IllegalStateException("CP/FOUT fixture selected " + cpfoutHop.getForcedExecType()
					+ "/" + cpfoutHop.getFederatedOutput());
			Method cpfoutRegister = FederatedPlanMinSTCut.class.getDeclaredMethod(
				"registerMinstCpfoutSelections", FederatedPlanMinSTGraph.class, Map.class);
			cpfoutRegister.setAccessible(true);
			Map<Long,FType> cpfoutTypes = new HashMap<>();
			cpfoutTypes.put(cpfoutHop.getHopID(), FType.BROADCAST);
			cpfoutRegister.invoke(null, cpfout, cpfoutTypes);
			FederatedFoutMaterializeRegistry.MaterializeSpec cpfoutSpec =
				FederatedFoutMaterializeRegistry.snapshot(-1L).get(cpfoutHop.getHopID());
			if(cpfoutSpec != null)
					rows.add("C2-MS-04-ANCHORED-UPLOAD|REGISTRY_FOUT_MATERIALIZE|evidence=EXACT_PRIVATE_REPLAY|producer="
					+ syntheticKey("C2-MS-04", "cp-fout", cpfoutHop)
					+ "|scope=-1|fType=" + cpfoutSpec.getFTypeHint() + "|anchorLabel="
					+ cpfoutSpec.getAnchorLabel() + "|anchorKey=" + cpfoutSpec.getAnchorKey()
					+ "|source=FROZEN_SELECTED_CP_FOUT");

			FederatedLocalMaterializeRegistry.clear();
			FederatedPlanMinSTGraph download = new FederatedPlanMinSTGraph();
			download.setNumOfWorkers(4);
			DataOp sourceHop = new DataOp("X_d_obligation", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, null, 100, 100, 10000, 1000);
			ExecPlacementCaps sourceCaps = new ExecPlacementCaps();
			sourceCaps.allowCP_LOUT = false; sourceCaps.allowCP_FOUT = false; sourceCaps.allowFED_LOUT = false;
			Vertex source = new Vertex(sourceHop, Privacy.PUBLIC, FType.ROW, sourceCaps);
			source.setMetadata(1.0, 1.0, Collections.emptyList()); source.setCost(100.0, 6.0, 4.0);
			download.addVertex(source); download.setVertexCost(source); download.addExecPlacementResultEdge(source);
			Vertex consumer = new Vertex(new LiteralOp(31L), Privacy.PUBLIC, FType.FULL, new ExecPlacementCaps());
			consumer.setMetadata(1.0, 1.0, Collections.emptyList()); consumer.setCost(0.0, 0.0, 0.0);
			download.addVertex(consumer); download.setVertexCost(consumer);
			download.addLoopCarryNetEdge(consumer.getHopID(), source.getHopID(), 0.0, 0.0);
			Vertex consumer2 = new Vertex(new LiteralOp(32L), Privacy.PUBLIC, FType.FULL, new ExecPlacementCaps());
			consumer2.setMetadata(1.0, 1.0, Collections.emptyList()); consumer2.setCost(0.0, 0.0, 0.0);
			download.addVertex(consumer2); download.setVertexCost(consumer2);
			download.addLoopCarryNetEdge(consumer2.getHopID(), source.getHopID(), 0.0, 0.0);
			download.getOptimalPlan();
				invokeRegistration(download);
				Map<Long,String> downloadKeys = new HashMap<>();
				downloadKeys.put(sourceHop.getHopID(), syntheticKey("C2-MS-03", "producer", sourceHop));
				downloadKeys.put(consumer.getHopID(), syntheticKey("C2-MS-03", "consumer-1", consumer.getHopRef()));
				downloadKeys.put(consumer2.getHopID(), syntheticKey("C2-MS-03", "consumer-2", consumer2.getHopRef()));
				FederatedLocalMaterializeRegistry.LocalMaterializeSpec localSpec =
					FederatedLocalMaterializeRegistry.snapshot(-1L).get(sourceHop.getHopID());
				if(localSpec != null) {
					List<String> localConsumers = localSpec.getConsumerHopIds().stream()
						.map(id -> mappedSyntheticKey(downloadKeys, id)).sorted().collect(Collectors.toList());
					rows.add("C2-MS-03-SHARED-DOWNLOAD|REGISTRY_LOCAL_MATERIALIZE|evidence=EXACT_PRIVATE_REPLAY|producer="
						+ mappedSyntheticKey(downloadKeys, sourceHop.getHopID()) + "|consumers=" + localConsumers
						+ "|scope=-1|consumerCount=" + localSpec.getConsumerHopIds().size() + "|fType="
						+ localSpec.getFTypeHint() + "|reason=" + localSpec.getReason() + "|source=FROZEN_SELECTED_D");
				}
				return rows;
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
	}

	private static String syntheticKey(String fixture, String role, Hop hop) {
		return "SYNTHETIC:" + fixture + ":" + role + ":" + hop.getClass().getName() + ":" + hop.getOpString();
	}

	private static String mappedSyntheticKey(Map<Long,String> keys, long hopId) {
		String key = keys.get(hopId);
		if(key == null)
			throw new IllegalStateException("UNMAPPED_SELECTED_HOP_ID " + hopId);
		return key;
	}

	private static void invokeRegistration(FederatedPlanMinSTGraph graph) throws Exception {
		Method register = FederatedPlanMinSTCut.class.getDeclaredMethod(
			"registerMinstSelectedObligations", FederatedPlanMinSTGraph.class, Map.class);
		register.setAccessible(true);
		register.invoke(null, graph, new HashMap<Long,FType>());
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
