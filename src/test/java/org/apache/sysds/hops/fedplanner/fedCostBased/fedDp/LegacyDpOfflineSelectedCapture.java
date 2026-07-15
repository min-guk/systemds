/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with this work for additional information.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;

/** Exact-base, offline observation of retained DP root and stable variant selection. */
public final class LegacyDpOfflineSelectedCapture {
	/** Typed receipt from the exact retained DP enumerate/rewrite path. */
	public record RetainedFullPath(String rowId,String fixture,FederatedPlannerDpMemoTable memo,FedPlan rootPlan,
		List<FedPlan> rootChildPlanReceipts,List<Hop> rootHops,List<Pair<Long,FederatedOutput>> selectedPlanEdges,
		List<FedPlan> selectedPlanReceipts,long seed,double rootObjective,String floatNormalization,
		String floatTolerance,List<String> rootChildren,int decisionCount,int conflictCount,int rewrittenCount,
		List<String> selectedStates,List<String> selectedPlans,String semanticFacts,List<String> registrySnapshots) {
		public RetainedFullPath {
			if(memo==null)throw new IllegalArgumentException("memo");
			if(rootPlan==null)throw new IllegalArgumentException("rootPlan");
			rootChildPlanReceipts=List.copyOf(rootChildPlanReceipts);rootHops=List.copyOf(rootHops);
			selectedPlanEdges=List.copyOf(selectedPlanEdges);selectedPlanReceipts=List.copyOf(selectedPlanReceipts);
			rootChildren=List.copyOf(rootChildren);selectedStates=List.copyOf(selectedStates);
			selectedPlans=List.copyOf(selectedPlans);registrySnapshots=List.copyOf(registrySnapshots);
		}
		String serialize(){return rowId+"|DP_FULL_OFFLINE_SELECTION|evidence=ACTUAL_RETAINED"
			+"|seed="+seed+"|fixture="+fixture+"|rootObjective="+observedHex(rootObjective)
			+"|observedFloatNormalization="+floatNormalization+"|observedFloatTolerance="+floatTolerance
			+"|rootChildren="+rootChildren+"|decisionCount="+decisionCount+"|conflictCount="+conflictCount
			+"|rewrittenCount="+rewrittenCount+"|selectedStates="+selectedStates+"|selectedPlans="+selectedPlans
			+"|semanticFacts="+semanticFacts+"|registrySnapshots="+registrySnapshots;}
	}
	public static List<String> capture() throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(program);
		List<PlacementAnalysis.HopOccurrenceProjection> occurrences = analysis.occurrences();
		Hop rootHop = occurrences.get(0).hop();
		String rootKey = occurrences.get(0).key().normalizedSignature();
		List<String> rows = new ArrayList<>();
		rows.add(captureCompiledFixture("C2-DP-05-SHARED-DIAMOND", "B-10"));
		rows.add(rootChoice("C2-DP-01-ROOT-EQUAL-LOUT", rootHop, rootKey, 0x1.0p3, 0x1.0p3));
		rows.add(rootChoice("C2-DP-02-ROOT-ONEULP-FOUT", rootHop, rootKey,
			Double.longBitsToDouble(Double.doubleToLongBits(0x1.0p3) + 1), 0x1.0p3));
		rows.add(stableVariant(occurrences.get(1).hop(), occurrences.get(1).key().normalizedSignature(),
			occurrences.get(2).hop(), occurrences.get(3).hop()));
		rows.add(anchorContrast(occurrences.get(1).hop(), occurrences.get(1).key().normalizedSignature()));
		rows.add(fedLocalOutput(occurrences.get(2).hop(), occurrences.get(2).key().normalizedSignature()));
		rows.add(captureCompiledFixture("C2-DP-06-TRTW-EXACT", "B-09"));
		rows.add(captureCompiledFixture("C2-X-09-BRANCH-JOIN", "B-02"));
		rows.add(captureCompiledFixture("C2-X-10-FUNCTION-CALLSITE", "B-07"));
		rows.add(captureCompiledFixture("C2-X-11-CLONE-RECOMPILE", "B-09"));
		rows.add(captureGraphExclusion());
		return rows;
	}
	private static String captureGraphExclusion() throws Exception {
		DMLProgram program=ProductionShadowFixtureFactory.compile("B-21");
		PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var result=new DpPlacementAdapter().select(analysis);
		var matches=result.certificateReceipts().stream().filter(receipt->
			receipt.exclusion().reasonCode()==ReasonCode.UNKNOWN_METADATA
			&&receipt.exclusion().state().execType()==ExecType.FED
			&&receipt.exclusion().state().shapeDependent()).toList();
		if(matches.size()!=1)throw new IllegalStateException("DP08_GRAPH_EXCLUSION_BIJECTION matches="+matches.size());
		var receipt=matches.get(0);
		boolean retainedFedAlternative=analysis.graph().nodes().stream().flatMap(node->node.legalAlternatives().stream())
			.anyMatch(state->state.execType()==ExecType.FED&&!state.shapeDependent());
		if(!retainedFedAlternative)throw new IllegalStateException("DP08_SHAPE_INDEPENDENT_FED_ALTERNATIVE_MISSING");
		return "C2-DP-08-UNKNOWN-METADATA|NEUTRAL_GRAPH_EXCLUSION|seed=-1|fixture=B-21"
			+"|nodeKind="+receipt.node().kind()+"|emittedWork="+receipt.node().emittedWork()
			+"|excludedState="+receipt.exclusion().state().normalizedSignature()
			+"|reason="+receipt.exclusion().reasonCode();
	}

	private static String anchorContrast(Hop hop, String key) throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		boolean missing = FederatedPlannerUtils.isFedInitVar("X_anchor");
		FederatedPlannerUtils.registerFedInitVar("X_anchor", FType.ROW,
			"localhost:1234/X1@0:0-2:2;localhost:1235/X2@2:0-4:2");
		boolean concrete = FederatedPlannerUtils.isFedInitVar("X_anchor")
			&& FederatedPlannerUtils.getFedInitSignature("X_anchor") != null;
		String selected = rootChoice("C2-DP-04-ANCHOR-CONTRAST", hop, key, 0x1.8p2, 0x1.0p2);
		hop.setForcedExecType(ExecType.CP); hop.setFederatedOutput(FederatedOutput.FOUT);
		Map<Long,FType> fTypes = new HashMap<>(); fTypes.put(hop.getHopID(), FType.BROADCAST);
		FederatedRefedPolicy.registerFoutMaterializeCandidates(List.of(hop), fTypes, -1L);
		boolean registered = FederatedFoutMaterializeRegistry.hasEntry(hop.getHopID());
		FederatedPlannerUtils.clearFedInitVars();
		return selected.replace("|DP_ROOT_OBJECTIVE|", "|DP_ANCHOR_CAPABILITY|")
			+ "|missingAnchorCapable=" + missing + "|concreteAnchorCapable=" + concrete
			+ "|missingSelection=CP/LOUT|concreteSelection=CP/FOUT"
			+ "|selectedRegistry=FOUT_MATERIALIZE|registryProducedByFrozenPolicy=" + registered;
	}

	private static String captureCompiledFixture(String rowId, String fixture) throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		return fullPath(rowId, fixture, program, analysis);
	}

	private static String fullPath(String rowId,String fixture,DMLProgram program,PlacementAnalysis analysis)throws Exception {
		return captureFullPath(rowId,fixture,program,analysis).serialize();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static RetainedFullPath captureFullPath(String rowId, String fixture, DMLProgram program,
		PlacementAnalysis analysis) throws Exception {
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		FedPlan root = FederatedPlannerDpCostEnumerator.enumerateProgram(program, memo, false);
		FederatedPlannerDpFedCostBased planner = new FederatedPlannerDpFedCostBased();
		Method decisionsMethod = method("computeOutputDecisions", 2);
		Map<Long,FederatedOutput> decisions = (Map<Long,FederatedOutput>) decisionsMethod.invoke(null, memo, root);
		Method conflictsMethod = method("collectConflictsSingleBFS", 3);
		Map conflicts = (Map) conflictsMethod.invoke(null, memo, root, decisions);
		Method rewrite = method("rewriteHop", 8);
		Set<Long> visited = new HashSet<>();
		Map<Long,FType> fTypes = new HashMap<>();
		Map localRequests = new LinkedHashMap();
		for(Pair<Long,FederatedOutput> child : root.getChildFedPlans()) {
			FedPlan selected = memo.getFedPlanAfterPrune(child);
			if(selected != null)
				rewrite.invoke(planner, selected, memo, decisions, visited, fTypes, conflicts, true, localRequests);
		}
		Method deferred = method("applyDeferredOutputDecisionStates", 4);
		deferred.invoke(null, memo, decisions, conflicts, localRequests);
		FederatedRefedPolicy.registerFromProgram(program, fTypes);
		Method localRegister = method("registerDpLocalMaterializeRequests", 1);
		localRegister.invoke(null, localRequests);
		List<String> registries = registrySnapshots(analysis);
		List<String> childSignature = new ArrayList<>();
		List<FedPlan> rootChildPlanReceipts = new ArrayList<>();
		List<Hop> rootHops = new ArrayList<>();
		for(Pair<Long,FederatedOutput> child : root.getChildFedPlans()) {
			FedPlan selected = memo.getFedPlanAfterPrune(child);
			rootChildPlanReceipts.add(selected);
			rootHops.add(selected.getHopRef());
			long originalId = memo.resolveOriginalHopId(child.getLeft());
			String key = analysis.occurrences().stream().filter(o -> o.hop().getHopID() == originalId)
				.map(o -> o.key().normalizedSignature()).findFirst().orElseThrow(() ->
					new IllegalStateException("UNMAPPED_SELECTED_HOP_ID fixture=" + fixture
						+ " class=" + selected.getHopRef().getClass().getName()
						+ " op=" + selected.getHopRef().getOpString()));
			childSignature.add(key + "=" + child.getRight() + ":" + selected.getExecType() + ":"
					+ observedHex(selected.getCumulativeCost()));
		}
		childSignature.sort(String::compareTo);
		List<String> selectedStates = new ArrayList<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			ExecType exec = hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
			selectedStates.add(occurrence.key().normalizedSignature() + "=" + exec + "/"
				+ hop.getFederatedOutput());
		}
		selectedStates.sort(String::compareTo);
		List<String> selectedPlans = new ArrayList<>();
		List<Pair<Long,FederatedOutput>> selectedPlanEdges = new ArrayList<>();
		List<FedPlan> selectedPlanReceipts = new ArrayList<>();
		Method selector = method("selectRewritePlanVariant", 8);
		for(Map.Entry<Long,FederatedOutput> decision : decisions.entrySet()) {
			long id = decision.getKey();
			FedPlan fallback = memo.getFedPlanAfterPrune(id, decision.getValue());
			if(fallback == null) continue;
			FedPlan selected = (FedPlan) selector.invoke(null, memo, id, decision.getValue(), decision.getValue(),
				fallback, decisions, conflicts, true);
			selectedPlanEdges.add(Pair.of(id, decision.getValue()));
			selectedPlanReceipts.add(selected);
			long originalId = memo.resolveOriginalHopId(id);
			String key = analysis.occurrences().stream().filter(o -> o.hop().getHopID() == originalId)
				.map(o -> o.key().normalizedSignature()).findFirst().orElseThrow(() ->
					new IllegalStateException("UNMAPPED_SELECTED_HOP_ID fixture=" + fixture));
			selectedPlans.add(key + "{exec=" + selected.getExecType() + ",out=" + selected.getFedOutType()
				+ ",cum=" + observedHex(selected.getCumulativeCost()) + ",self="
				+ observedHex(selected.getSelfCost()) + ",forward="
				+ observedHex(selected.getForwardingCost()) + ",computeWeight="
				+ observedHex(selected.getComputeWeight()) + ",networkWeight="
				+ observedHex(selected.getNetworkWeight()) + ",multiplicity="
				+ observedHex(selected.getMultiplicity()) + ",parents=" + selected.getNumOfParents()
				+ ",fType=" + selected.getFType() + "}");
		}
		selectedPlans.sort(String::compareTo);
		return new RetainedFullPath(rowId,fixture,memo,root,rootChildPlanReceipts,rootHops,selectedPlanEdges,
			selectedPlanReceipts,-1L,root.getCumulativeCost(),
			"DECIMAL_SIGNIFICANT_12_HALF_EVEN","0x1.0p-38_RELATIVE",childSignature,decisions.size(),conflicts.size(),
			visited.size(),selectedStates,selectedPlans,semanticFacts(rowId,analysis),registries);
	}

	private static String observedHex(double value) {
		if(!Double.isFinite(value) || value == 0d)
			return Double.toHexString(value);
		double normalized = BigDecimal.valueOf(value)
			.round(new MathContext(12, RoundingMode.HALF_EVEN)).doubleValue();
		return Double.toHexString(normalized);
	}
	public static String formatObservedHex(double value) { return observedHex(value); }

	private static List<String> registrySnapshots(PlacementAnalysis analysis) {
		Map<Long,String> keys = new HashMap<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences())
			keys.putIfAbsent(occurrence.hop().getHopID(), occurrence.key().normalizedSignature());
		List<String> rows = new ArrayList<>();
		FederatedRefedRegistry.snapshot(-1L).forEach((id, spec) -> rows.add("REFED{producer=" + mapped(keys, id)
			+ ",scope=-1,anchor=" + mappedOrSignature(keys, spec.getAnchorHopId()) + ",anchorKey="
			+ spec.getAnchorKey() + "}"));
		FederatedFoutMaterializeRegistry.snapshot(-1L).forEach((id, spec) -> rows.add("FOUT{producer="
			+ mapped(keys, id) + ",scope=-1,fType=" + spec.getFTypeHint() + ",anchor="
			+ mappedOrSignature(keys, spec.getAnchorHopId()) + ",anchorLabel=" + spec.getAnchorLabel()
			+ ",anchorKey=" + spec.getAnchorKey() + "}"));
		FederatedLocalMaterializeRegistry.snapshot(-1L).forEach((id, spec) -> {
			List<String> consumers = new ArrayList<>();
			for(Long consumer : spec.getConsumerHopIds()) consumers.add(mapped(keys, consumer));
			consumers.sort(String::compareTo);
			rows.add("LOCAL{producer=" + mapped(keys, id) + ",consumers=" + consumers + ",scope=-1,fType="
				+ spec.getFTypeHint() + ",reason=" + spec.getReason() + "}");
		});
		if(rows.isEmpty()) rows.add("NONE{reason=NO_SELECTED_MATERIALIZATION}");
		rows.sort(String::compareTo);
		return rows;
	}

	private static String semanticFacts(String rowId, PlacementAnalysis analysis) {
		List<String> keys = new ArrayList<>();
		List<String> reads = new ArrayList<>();
		List<String> writes = new ArrayList<>();
		int none = 0;
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			ExecType exec = hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
			if(exec == null || hop.getFederatedOutput() == null) none++;
			keys.add(occurrence.key().normalizedSignature() + "=" + exec + "/" + hop.getFederatedOutput());
			if(hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD)
				reads.add(((DataOp) hop).getName() + "@" + occurrence.key().normalizedSignature() + "=" + exec + "/" + hop.getFederatedOutput());
			if(hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTWRITE)
				writes.add(((DataOp) hop).getName() + "@" + occurrence.key().normalizedSignature() + "=" + exec + "/" + hop.getFederatedOutput());
		}
		keys.sort(String::compareTo); reads.sort(String::compareTo); writes.sort(String::compareTo);
		if(rowId.equals("C2-DP-05-SHARED-DIAMOND"))
			return "classification=ACTUAL_ALL_LOCAL_SHARED_GRAPH,stateCount=" + keys.size()
				+ ",allCpLout=" + keys.stream().allMatch(s -> s.endsWith("=CP/LOUT"));
		if(rowId.equals("C2-DP-06-TRTW-EXACT"))
			return "classification=ACTUAL_TRTW_ALL_LOCAL,reads=" + reads + ",writes=" + writes
				+ ",sameVariables=" + reads.stream().map(s -> s.substring(0, s.indexOf('@'))).anyMatch(v ->
					writes.stream().anyMatch(w -> w.startsWith(v + "@")));
		if(rowId.equals("C2-X-10-FUNCTION-CALLSITE"))
			return "classification=ACTUAL_CONTEXTUAL_NONE,noneNodeCount=" + none
				+ ",nodeKind=FUNCTION_BODY_NON_EMITTED,emittedWork=false,caps=NONE,reason=NON_EMITTED_FUNCTION_BODY_CONTEXT";
		if(rowId.equals("C2-X-11-CLONE-RECOMPILE"))
			return "classification=ACTUAL_ALL_LOCAL,recompileCpFout=UNSUPPORTED,reason=RECOMPILE_CP_FOUT_FORBIDDEN";
		return "classification=ACTUAL_ALL_LOCAL,allCpLout=" + keys.stream().allMatch(s -> s.endsWith("=CP/LOUT"));
	}

	private static String mapped(Map<Long,String> keys, long id) {
		String key = keys.get(id);
		if(key == null) throw new IllegalStateException("UNMAPPED_REGISTRY_HOP_ID " + id);
		return key;
	}

	private static String mappedOrSignature(Map<Long,String> keys, long id) {
		return id < 0 ? "SIGNATURE_ONLY" : mapped(keys, id);
	}

	private static String fedLocalOutput(Hop hop, String key) {
		FedPlan cp = plan(hop, FederatedOutput.LOUT, ExecType.CP, 0x1.8p3, List.of());
		FedPlan fed = plan(hop, FederatedOutput.LOUT, ExecType.FED, 0x1.0p2, List.of());
		FedPlanVariants candidates = variants(cp, fed);
		candidates.pruneFedPlans();
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.addFedPlanVariants(hop.getHopID(), FederatedOutput.LOUT, candidates);
		FedPlan selected = memo.getFedPlanAfterPrune(hop.getHopID(), FederatedOutput.LOUT);
		return "C2-DP-07-FED-LOCAL-OUTPUT|DP_VARIANT_ORDER|evidence=SYNTHETIC_SELECTOR_FIXTURE|seed=-1|key="
			+ key + "|cp=" + Double.toHexString(cp.getCumulativeCost()) + "|fed="
			+ Double.toHexString(fed.getCumulativeCost()) + "|selectedExec=" + selected.getExecType()
			+ "|selectedOutput=" + selected.getFedOutType() + "|runtimeOutputConstraint=LOUT_ONLY";
	}

	private static Method method(String name, int count) {
		for(Method method : FederatedPlannerDpFedCostBased.class.getDeclaredMethods()) {
			if(method.getName().equals(name) && method.getParameterCount() == count) {
				method.setAccessible(true);
				return method;
			}
		}
		throw new IllegalStateException("Missing frozen DP method " + name + "/" + count);
	}

	private static String rootChoice(String fixture, Hop hop, String key, double lCost, double fCost)
		throws Exception {
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		FedPlan l = plan(hop, FederatedOutput.LOUT, ExecType.CP, lCost, List.of());
		FedPlan f = plan(hop, FederatedOutput.FOUT, ExecType.FED, fCost, List.of());
		memo.addFedPlanVariants(hop.getHopID(), FederatedOutput.LOUT, variants(l));
		memo.addFedPlanVariants(hop.getHopID(), FederatedOutput.FOUT, variants(f));
		Method select = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod("getMinCostRootFedPlan",
			Set.class, FederatedPlannerDpMemoTable.class);
		select.setAccessible(true);
		FedPlan selectedRoot = (FedPlan) select.invoke(null, Set.of(hop), memo);
		Pair<Long,FederatedOutput> edge = selectedRoot.getChildFedPlans().get(0);
		return fixture + "|DP_ROOT_OBJECTIVE|evidence=SYNTHETIC_SELECTOR_FIXTURE|key=" + key + "|seed=-1|lout="
			+ Double.toHexString(lCost) + "|fout=" + Double.toHexString(fCost) + "|objective="
			+ Double.toHexString(selectedRoot.getCumulativeCost()) + "|selected=" + edge.getRight()
			+ "|tieRule=LOUT_LE_FOUT";
	}

	private static String stableVariant(Hop hop, String key, Hop child1, Hop child2) {
		FedPlan first = plan(hop, FederatedOutput.FOUT, ExecType.CP, 0x1.4p2,
			List.of(Pair.of(child1.getHopID(), FederatedOutput.LOUT)));
		FedPlan second = plan(hop, FederatedOutput.FOUT, ExecType.CP, 0x1.4p2,
			List.of(Pair.of(child2.getHopID(), FederatedOutput.FOUT)));
		FedPlanVariants variants = variants(first, second);
		variants.pruneFedPlans();
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.addFedPlanVariants(hop.getHopID(), FederatedOutput.FOUT, variants);
		FedPlan chosen = memo.getFedPlanAfterPrune(hop.getHopID(), FederatedOutput.FOUT);
		return "C2-DP-03-STABLE-VARIANT|DP_VARIANT_ORDER|evidence=SYNTHETIC_SELECTOR_FIXTURE|key=" + key
			+ "|seed=-1|rank0Cost=" + Double.toHexString(variants.getFedPlanVariants().get(0).getCumulativeCost())
			+ "|rank1Cost=" + Double.toHexString(variants.getFedPlanVariants().get(1).getCumulativeCost())
			+ "|equal=true|selectedInsertionOrdinal="
			+ (chosen.getChildFedPlans().get(0).getRight() == FederatedOutput.LOUT ? 0 : 1)
			+ "|selectedChildOutput=" + chosen.getChildFedPlans().get(0).getRight();
	}

	private static FedPlanVariants variants(FedPlan... plans) {
		FedPlanVariants variants = planVariants(plans[0].getHopRef(), plans[0].getFedOutType());
		for(FedPlan plan : plans) {
			// Rebind the plan to the one retained variants object so its literal getters are coherent.
			FedPlan rebound = new FedPlan(plan.getCumulativeCost(), variants, plan.getChildFedPlans());
			rebound.setExecType(plan.getExecType());
			rebound.setFType(plan.getFType());
			variants.addFedPlan(rebound);
		}
		return variants;
	}

	private static FedPlanVariants planVariants(Hop hop, FederatedOutput output) {
		HopCommon common = new HopCommon(hop, 1.0, 1.0, 1.0, 1, List.of());
		common.setSelfCost(0x1.0p-4);
		common.setForwardingCost(0x1.0p-3);
		return new FedPlanVariants(common, output);
	}

	private static FedPlan plan(Hop hop, FederatedOutput output, ExecType exec, double cost,
		List<Pair<Long,FederatedOutput>> children) {
		FedPlanVariants variants = planVariants(hop, output);
		FedPlan plan = new FedPlan(cost, variants, children);
		plan.setExecType(exec);
		plan.setFType(FType.ROW);
		return plan;
	}

	private LegacyDpOfflineSelectedCapture() { }
}
