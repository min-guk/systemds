/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.SelectedObligation;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultWeightedEdge;

/** Test-only exact MinST seam: producer identities are validated before normalized evidence is copied. */
final class MinStAnalysisContractBridge {
	private static final long SOURCE = -1L;
	private static final long SINK = -2L;
	private static final String ADAPTER = "org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter";
	private static final String INPUT = "org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput";
	private static final String BINDER =
		"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.LegacyMinstOfflineSelectedCapture";

	record Handle(Object adapter, Method bindPlacementInput, Method select) { }
	record Selection(PlacementAnalysis analysis, long objectiveBits, List<String> sourcePartition,
		List<String> receipts, List<String> obligations, String analysisFingerprint) { }
	record Invocation(PlacementAnalysis analysis, FederatedPlanMinSTGraph graph,
		long objectiveBits, List<Long> sourcePartition, List<PlacementAnalysis.HopOccurrenceProjection> occurrences,
		List<SelectedObligation> obligations) { }
	record Prepared(Invocation input, Object ownerBound) { }
	record Applicability(boolean applicable, String reason) {
		Applicability {
			if(reason == null || reason.isBlank()) throw new IllegalArgumentException("reason");
		}
	}

	static Applicability applicability(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		List<PlacementAnalysis.HopOccurrenceProjection> occurrences = analysis.occurrences().stream()
			.sorted(Comparator.comparing(PlacementAnalysis.HopOccurrenceProjection::key)).toList();
		PlacementAnalysis.HopOccurrenceProjection matrix = occurrences.stream()
			.filter(occurrence -> occurrence.hop().getDataType() != null
				&& occurrence.hop().getDataType().isMatrix()
				&& analysis.shapeFact(occurrence.key()).map(fact -> fact.dataType().isMatrix()).orElse(false))
			.findFirst().orElse(null);
		if(matrix == null) return new Applicability(false, "NO_MATRIX_OCCURRENCE");
		boolean distinctConsumer = occurrences.stream().anyMatch(occurrence -> occurrence.hop() != matrix.hop()
			&& occurrence.hop().getHopID() != matrix.hop().getHopID());
		if(!distinctConsumer) return new Applicability(false, "NO_DISTINCT_CONSUMER");
		return new Applicability(true, "MATRIX_AND_DISTINCT_CONSUMER");
	}

	static Handle open() {
		try {
			Class<?> inputType = Class.forName(INPUT);
			Method bindInput = Class.forName(BINDER).getMethod("bindLegacyPlacementInput",
				PlacementAnalysis.class, FederatedPlanMinSTGraph.class);
			Class<?> adapterType = Class.forName(ADAPTER);
			return new Handle(adapterType.getConstructor().newInstance(), bindInput,
				adapterType.getMethod("select", PlacementAnalysis.class, inputType));
		}
		catch(ReflectiveOperationException e) {
			throw new AssertionError("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING|planner=MIN_ST|member=" + BINDER
				+ ".bindLegacyPlacementInput(PlacementAnalysis,FederatedPlanMinSTGraph)->" + ADAPTER
				+ ".select(PlacementAnalysis,MinStPlacementInput)");
		}
	}

	static Selection select(Handle handle, PlacementAnalysis analysis) {
		return select(handle, prepare(handle, analysis), analysis);
	}

	static Prepared prepare(Handle handle, PlacementAnalysis owner) {
		Invocation input = selectedInput(owner);
		try {
			Object ownerBound = handle.bindPlacementInput().invoke(null, owner, input.graph());
			if(ownerBound == null) throw new AssertionError("R4_MINST_OWNER_BOUND_INPUT_NULL");
			return new Prepared(input, ownerBound);
		}
		catch(InvocationTargetException e) { throw contract("bind", e.getCause()); }
		catch(ReflectiveOperationException | RuntimeException e) { throw contract("bind", e); }
	}

	static Selection select(Handle handle, Prepared prepared, PlacementAnalysis requested) {
		try {
			return normalize(prepared, handle.select().invoke(
				handle.adapter(), requested, prepared.ownerBound()));
		}
		catch(InvocationTargetException e) { throw contract("invoke", e.getCause()); }
		catch(ReflectiveOperationException | RuntimeException e) { throw contract("invoke", e); }
	}

	static void verifyFixture(PlacementAnalysis analysis) {
		Applicability applicability = applicability(analysis);
		if(!applicability.applicable())
			throw new AssertionError("R4_MINST_FIXTURE_NOT_APPLICABLE|" + applicability.reason());
		Invocation input = selectedInput(analysis);
		if(input.sourcePartition().isEmpty() || input.occurrences().isEmpty() || input.obligations().isEmpty())
			throw new AssertionError("R4_MINST_FIXTURE_VACUOUS");
	}

	static void rejectForeign(Handle handle, PlacementAnalysis owner, PlacementAnalysis foreign) {
		Prepared prepared = prepare(handle, owner);
		Invocation input = prepared.input();
		select(handle, prepared, owner);
		List<String> ownerBefore = analysisSnapshot(owner);
		List<String> foreignBefore = analysisSnapshot(foreign);
		List<String> graphBefore = graphSnapshot(input.graph());
		try {
			handle.select().invoke(handle.adapter(), foreign, prepared.ownerBound());
			throw new AssertionError("R4_MINST_FOREIGN_ANALYSIS_ACCEPTED");
		}
		catch(InvocationTargetException e) {
			if(!(e.getCause() instanceof IllegalArgumentException)) throw contract("foreign", e.getCause());
		}
		catch(ReflectiveOperationException e) { throw contract("foreign", e); }
		if(!ownerBefore.equals(analysisSnapshot(owner)) || !foreignBefore.equals(analysisSnapshot(foreign))
			|| !graphBefore.equals(graphSnapshot(input.graph())))
			throw new AssertionError("R4_MINST_FOREIGN_MUTATION");
	}

	static void stable(Selection left, Selection right, String code) {
		if(left.objectiveBits() != right.objectiveBits()
			|| !left.sourcePartition().equals(right.sourcePartition())
			|| !left.receipts().equals(right.receipts())
			|| !left.obligations().equals(right.obligations())
			|| !left.analysisFingerprint().equals(right.analysisFingerprint()))
			throw new AssertionError(code);
	}

	private static Invocation selectedInput(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		List<PlacementAnalysis.HopOccurrenceProjection> occurrences = analysis.occurrences().stream()
			.sorted(Comparator.comparing(PlacementAnalysis.HopOccurrenceProjection::key)).toList();
		Hop obligationChild = occurrences.stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.filter(hop -> hop.getDataType() != null && hop.getDataType().isMatrix()).findFirst()
			.orElseThrow(() -> new AssertionError("R4_MINST_FIXTURE_HAS_NO_MATRIX"));
		Hop localConsumer = occurrences.stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.filter(hop -> hop.getHopID() != obligationChild.getHopID()).findFirst()
			.orElseThrow(() -> new AssertionError("R4_MINST_FIXTURE_TOO_SMALL"));
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph(); graph.setNumOfWorkers(2);
		Map<Long,Vertex> vertices = new LinkedHashMap<>();
		for(var occurrence : occurrences) {
			long hopId = occurrence.hop().getHopID();
			if(vertices.containsKey(hopId)) continue;
			ExecPlacementCaps caps = new ExecPlacementCaps();
			int ordinal = vertices.size();
			if(hopId == obligationChild.getHopID()) {
				caps.allowCP_LOUT = false; caps.allowCP_FOUT = false; caps.allowFED_LOUT = false;
				caps.fedFoutMode = ExecPlacementCaps.FedFoutMode.NATIVE;
			}
			else if(hopId == localConsumer.getHopID()) {
				caps.allowCP_FOUT = false; caps.allowFED_LOUT = false; caps.allowFED_FOUT = false;
			}
			Vertex vertex = new Vertex(occurrence.hop(), Privacy.PRIVATE, FType.ROW, FType.ROW, caps);
			vertex.setMetadata(1.0, 1.0, List.of()); vertex.setCost(8.0 + ordinal, 4.0, 4.0);
			graph.addVertex(vertex); graph.setVertexCost(vertex); graph.addExecPlacementResultEdge(vertex);
			vertices.put(hopId, vertex);
		}
		if(vertices.size() < 2) throw new AssertionError("R4_MINST_FIXTURE_TOO_SMALL");
		graph.addLoopCarryNetEdge(localConsumer.getHopID(), obligationChild.getHopID(), 0.0, 0.0);
		graph.getOptimalPlan();
		PushRelabelMFImpl<Long,DefaultWeightedEdge> solver = new PushRelabelMFImpl<>(graph.getGraph());
		solver.calculateMinCut(SOURCE, SINK);
		List<Long> source = solver.getSourcePartition().stream().sorted().toList();
		List<SelectedObligation> obligations = List.copyOf(graph.getSelectedObligations());
		if(obligations.isEmpty()) throw new AssertionError("R4_MINST_OBLIGATION_FIXTURE_EMPTY");
		return new Invocation(analysis, graph, Double.doubleToRawLongBits(solver.getCutCapacity()),
			List.copyOf(source), occurrences, obligations);
	}

	private static Selection normalize(Prepared prepared, Object raw) throws ReflectiveOperationException {
		Invocation input=prepared.input();
		if(raw == null) throw new AssertionError("R4_MINST_RESULT_NULL");
		if(call(raw, "analysis") != input.analysis()
			|| call(raw, "producer") != call(prepared.ownerBound(), "producerReceipt"))
			throw new AssertionError("R4_MINST_PRODUCER_IDENTITY");
		String fingerprint = String.valueOf(call(raw, "analysisFingerprint"));
		if(!fingerprint.equals(input.analysis().analysisFingerprint())) throw new AssertionError("R4_MINST_ANALYSIS_FINGERPRINT");
		long objectiveBits = ((Number)call(raw, "cutObjectiveBits")).longValue();
		if(objectiveBits != input.objectiveBits()) throw new AssertionError("R4_MINST_CUT_OBJECTIVE");
		List<?> sourceRaw = (List<?>)call(raw, "sourcePartitionNodeIds"); assertImmutable(sourceRaw, "sourcePartition");
		List<Long> source = sourceRaw.stream().map(v -> ((Number)v).longValue()).toList();
		if(!source.equals(input.sourcePartition())) throw new AssertionError("R4_MINST_SOURCE_PARTITION");
		List<?> receiptRaw = (List<?>)call(raw, "selectedReceipts"); assertImmutable(receiptRaw, "selectedReceipts");
		List<?> boundRaw=(List<?>)call(prepared.ownerBound(),"occurrenceReceipts");
		if(receiptRaw.size() != boundRaw.size()) throw new AssertionError("R4_MINST_SELECTED_ORDER");
		List<String> receipts = new ArrayList<>(); Map<Long,List<CompiledHopKey>> keysByHop = keysByHop(input.occurrences());
		for(int i=0; i<receiptRaw.size(); i++) {
			Object receipt = receiptRaw.get(i), bound=boundRaw.get(i);
			CompiledHopKey key = (CompiledHopKey)call(receipt, "planningKey");
			Hop planningHop = (Hop)call(receipt, "planningHop"); long planningHopId = number(receipt, "planningHopId");
			Hop executableHop = (Hop)call(receipt, "executableHop"); long executableHopId = number(receipt, "executableHopId");
			ExecType exec = (ExecType)call(receipt, "execType");
			FederatedOutput output = (FederatedOutput)call(receipt, "output");
			if(!key.equals(call(bound,"planningKey"))||planningHop!=call(bound,"planningHop")
				||planningHopId!=number(bound,"planningHopId")||executableHop!=call(bound,"executableHop")
				||executableHopId!=number(bound,"executableHopId"))
				throw new AssertionError("R4_MINST_PLANNING_EXECUTABLE_IDENTITY");
			if(exec!=call(bound,"execType")||output!=call(bound,"output"))
				throw new AssertionError("R4_MINST_SELECTED_STATE");
			receipts.add(key.normalizedSignature() + '=' + exec + '/' + output);
		}
		List<?> obligationRaw = (List<?>)call(raw, "selectedObligations"); assertImmutable(obligationRaw, "selectedObligations");
		if(obligationRaw.size()!=input.obligations().size()) throw new AssertionError("R4_MINST_OBLIGATION_IDENTITY");
		for(int i=0;i<obligationRaw.size();i++)assertGenericObligation(obligationRaw.get(i),input.obligations().get(i));
		List<String> obligations = input.obligations().stream().map(o -> obligation(o, keysByHop)).toList();
		return new Selection(input.analysis(), objectiveBits, normalizeSource(source, keysByHop),
			List.copyOf(receipts), List.copyOf(obligations), fingerprint);
	}

	private static void assertGenericObligation(Object actual, SelectedObligation expected)
		throws ReflectiveOperationException {
		if(!String.valueOf(call(actual,"kind")).equals(expected.getKind().name())
			||number(actual,"childHopId")!=expected.getChildHopId()
			||number(actual,"originalHopId")!=expected.getOriginalHopId()
			||!call(actual,"consumerHopIds").equals(expected.getConsumerHopIds())
			||call(actual,"fType")!=expected.getFType()
			||!call(actual,"capability").equals(expected.hasCapability())
			||!String.valueOf(call(actual,"capabilityReason")).equals(expected.getCapabilityReason())
			||!String.valueOf(call(actual,"reason")).equals(expected.getReason()))
			throw new AssertionError("R4_MINST_OBLIGATION_IDENTITY");
	}

	private static Map<Long,List<CompiledHopKey>> keysByHop(List<PlacementAnalysis.HopOccurrenceProjection> occurrences) {
		Map<Long,List<CompiledHopKey>> out = new HashMap<>();
		for(var occurrence : occurrences) out.computeIfAbsent(occurrence.hop().getHopID(), k -> new ArrayList<>())
			.add(occurrence.key());
		for(List<CompiledHopKey> keys : out.values()) keys.sort(Comparator.naturalOrder());
		return out;
	}

	private static List<String> normalizeSource(List<Long> source, Map<Long,List<CompiledHopKey>> keysByHop) {
		List<String> out = new ArrayList<>();
		for(long node : source) {
			if(node == SOURCE) out.add("SOURCE");
			else if(node == SINK) out.add("SINK");
			else {
				String mapped = null;
				for(var entry : keysByHop.entrySet()) {
					String keys = entry.getValue().stream().map(CompiledHopKey::normalizedSignature).toList().toString();
					if(node == nodeId("computeId", entry.getKey())) mapped = "C|" + keys;
					else if(node == nodeId("placementId", entry.getKey())) mapped = "P|" + keys;
					if(mapped != null) break;
				}
				out.add(mapped == null ? "AUX|" + node : mapped);
			}
		}
		out.sort(String::compareTo); return List.copyOf(out);
	}

	private static String obligation(SelectedObligation obligation, Map<Long,List<CompiledHopKey>> keysByHop) {
		return obligation.getKind() + "|child=" + stableKeys(keysByHop, obligation.getChildHopId())
			+ "|original=" + stableKeys(keysByHop, obligation.getOriginalHopId()) + "|consumers="
			+ obligation.getConsumerHopIds().stream().map(id -> stableKeys(keysByHop, id)).sorted().toList()
			+ "|fType=" + obligation.getFType() + "|capability=" + obligation.hasCapability()
			+ "|capabilityReason=" + obligation.getCapabilityReason() + "|reason=" + obligation.getReason();
	}

	private static String stableKeys(Map<Long,List<CompiledHopKey>> keysByHop, long hopId) {
		List<CompiledHopKey> keys = keysByHop.get(hopId);
		if(keys == null) throw new AssertionError("R4_MINST_UNMAPPED_OBLIGATION_HOP");
		return keys.stream().map(CompiledHopKey::normalizedSignature).toList().toString();
	}

	private static List<String> graphSnapshot(FederatedPlanMinSTGraph graph) {
		List<String> out = new ArrayList<>(); Graph<Long,DefaultWeightedEdge> nativeGraph = graph.getGraph();
		for(DefaultWeightedEdge edge : nativeGraph.edgeSet()) out.add("E|" + nativeGraph.getEdgeSource(edge) + '|'
			+ nativeGraph.getEdgeTarget(edge) + '|' + Double.doubleToRawLongBits(nativeGraph.getEdgeWeight(edge)));
		for(var entry : graph.getMemoTable().entrySet()) {
			Hop hop = entry.getValue().getHopRef();
			out.add("V|" + entry.getKey() + '|' + System.identityHashCode(hop) + '|' + hop.getForcedExecType()
				+ '|' + hop.getFederatedOutput() + '|' + hop.isFederatedOutputDerived());
		}
		for(SelectedObligation obligation : graph.getSelectedObligations())
			out.add("O|" + System.identityHashCode(obligation) + '|' + obligation);
		out.sort(String::compareTo); return List.copyOf(out);
	}

	private static List<String> analysisSnapshot(PlacementAnalysis analysis) {
		List<String> out = new ArrayList<>(CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(analysis));
		for(var occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			out.add("S|" + occurrence.key().normalizedSignature() + '|' + hop.getForcedExecType()
				+ '|' + hop.getFederatedOutput() + '|' + hop.isFederatedOutputDerived());
		}
		return List.copyOf(out);
	}

	private static long number(Object value, String name) throws ReflectiveOperationException {
		return ((Number)call(value, name)).longValue();
	}
	private static long nodeId(String method, long hopId) {
		try {
			Class<?> planner = Class.forName(
				"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTPlanner");
			Method id = planner.getDeclaredMethod(method, long.class); id.setAccessible(true);
			return ((Number)id.invoke(null, hopId)).longValue();
		}
		catch(ReflectiveOperationException e) { throw new AssertionError("R4_MINST_NODE_ID_CONTRACT", e); }
	}
	private static Object call(Object value, String name) throws ReflectiveOperationException {
		return value.getClass().getMethod(name).invoke(value);
	}
	private static boolean sameObjects(List<?> actual, List<?> expected) {
		if(actual.size() != expected.size()) return false;
		for(int i=0; i<actual.size(); i++) if(actual.get(i) != expected.get(i)) return false;
		return true;
	}
	@SuppressWarnings({"rawtypes", "unchecked"}) private static void assertImmutable(List list, String field) {
		try { list.add(null); throw new AssertionError("R4_MINST_RESULT_MUTABILITY|" + field); }
		catch(UnsupportedOperationException expected) { }
	}
	private static AssertionError contract(String field, Throwable cause) {
		return new AssertionError("CAMPAIGN_B_RUNTIME_CONTRACT|planner=MIN_ST|field=" + field
			+ "|reason=" + cause.getClass().getSimpleName());
	}
	private MinStAnalysisContractBridge() { }
}
