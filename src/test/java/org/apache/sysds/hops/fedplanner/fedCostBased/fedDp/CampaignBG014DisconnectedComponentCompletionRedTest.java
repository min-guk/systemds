/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED for coherent completion of one disconnected component with two sink paths. */
public class CampaignBG014DisconnectedComponentCompletionRedTest {
	@Test
	public void captureOnlyCloneFamilySelectionDoesNotMutatePlannerState() throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		EnumeratedFixture enumerated = enumerateHostileFixture();
		PlacementAnalysis analysis = enumerated.analysis();
		FederatedPlannerDpMemoTable memo = enumerated.memo();
		Map<Long,FederatedOutput> outputDecisions = enumerated.outputDecisions();
		Map<Long,Object> conflicts = enumerated.conflicts();
		HostileComponentFixture fixture = enumerated.hostile();
		Map<Hop,MutableHopState> initialFamilyState = snapshotMutableHopState(fixture.familyHops());
		FedPlan selectedBest = selectEffectivePlan(memo, fixture.rootPlan(), outputDecisions, conflicts);
		Assert.assertNotNull("real clone-family selector must return an exact plan", selectedBest);
		long selectedCostBits = Double.doubleToRawLongBits(selectedBest.getCumulativeCost());
		restoreMutableHopState(initialFamilyState);
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		fixture = new HostileComponentFixture(fixture.rootKey(), selectedBest,
			selectedBest.getSelectedPlacementState(), !selectedBest.isDerivedFedFout(), fixture.familyHops());

		Map<CompiledHopKey,Object> selectedStates = new IdentityHashMap<>();
		Set<CompiledHopKey> visitedPlanHops = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<Long,FType> fTypeMap = new LinkedHashMap<>();
		selectedStates.put(fixture.rootKey(),
			newSelectedState(fixture.lockedRootState(), fixture.lockedDerivedFedFout()));
		visitedPlanHops.add(fixture.rootKey());
		if(fixture.lockedRootState().fType() != null)
			fTypeMap.put(memo.resolveOriginalHopId(fixture.rootPlan().getHopID()),
				fixture.lockedRootState().fType());
		Map<Long,Object> localMaterializeRequests = new LinkedHashMap<>();
		CloneSnapshot before = snapshotCloneFamily(fixture.familyHops());
		AccumulatorSnapshot accumulatorsBefore = snapshotAccumulators(selectedStates, visitedPlanHops,
			fTypeMap, outputDecisions, conflicts, localMaterializeRequests);

		Method rewrite = FederatedPlannerDpFedCostBased.class.getDeclaredMethod("rewriteHop",
			FedPlan.class, FederatedPlannerDpMemoTable.class, Map.class, Set.class, Map.class,
			Map.class, boolean.class, Map.class, Map.class);
		rewrite.setAccessible(true);
		try {
			rewrite.invoke(new FederatedPlannerDpFedCostBased(), fixture.rootPlan(), memo, outputDecisions,
				visitedPlanHops, fTypeMap, conflicts, true, localMaterializeRequests, selectedStates);
			Assert.fail("fixture must force a real boundary-lock disagreement");
		}
		catch(InvocationTargetException expected) {
			Assert.assertTrue(expected.getCause() instanceof IllegalStateException);
			Assert.assertTrue(expected.getCause().getMessage().contains(
				"DP occurrence has disagreeing exact selections: " + fixture.rootKey()));
		}
		Assert.assertSame("real-path rewrite must retain the probed clone-family best-plan identity",
			selectedBest, fixture.rootPlan());
		Assert.assertEquals("real-path rewrite changed the probed clone-family best-plan cost",
			selectedCostBits, Double.doubleToRawLongBits(fixture.rootPlan().getCumulativeCost()));
		Assert.assertEquals("failed component changed global planning accumulators",
			accumulatorsBefore, snapshotAccumulators(selectedStates, visitedPlanHops,
				fTypeMap, outputDecisions, conflicts, localMaterializeRequests));
		Assert.assertEquals("clone-family selection mutated Hop/recompile state before component validation",
			before, snapshotCloneFamily(fixture.familyHops()));
	}

	private static FedPlan selectEffectivePlan(FederatedPlannerDpMemoTable memo, FedPlan seed,
		Map<Long,FederatedOutput> decisions, Map<Long,Object> conflicts) throws Exception {
		Method selector = FederatedPlannerDpFedCostBased.class.getDeclaredMethod("selectRewritePlanVariant",
			FederatedPlannerDpMemoTable.class, long.class, FederatedOutput.class, FederatedOutput.class,
			FedPlan.class, Map.class, Map.class, boolean.class);
		selector.setAccessible(true);
		long originalID = memo.resolveOriginalHopId(seed.getHopID());
		FederatedOutput desired = decisions.getOrDefault(originalID, seed.getFedOutType());
		return (FedPlan) selector.invoke(null, memo, seed.getHopID(), desired, seed.getFedOutType(),
			seed, decisions, conflicts, true);
	}

	private static EnumeratedFixture enumerateHostileFixture() throws Exception {
		AssertionError last = null;
		for(String fixtureID : List.of("B-09", "B-05")) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixtureID);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
			FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(analysis);
			DpEnumerationResult enumeration =
				FederatedPlannerDpCostEnumerator.enumerateProgramWithReceipts(program, memo, false, analysis);
			Map<Long,FederatedOutput> decisions = computeOutputDecisions(memo, enumeration.optimalPlan());
			Map<Long,Object> conflicts = collectConflicts(memo, enumeration.optimalPlan(), decisions);
			try {
				return new EnumeratedFixture(analysis, memo, decisions, conflicts,
					findHostileComponentFixture(analysis, memo, enumeration, decisions, conflicts));
			}
			catch(AssertionError miss) {
				last = miss;
			}
		}
		throw last == null ? new AssertionError("no clone-family fixture enumerated") : last;
	}

	@Test
	public void sharedProducerAcrossTwoSinkPathsHasOneExactState() throws Exception {
		assertCoherent(invoke(false));
	}

	@Test
	public void sourceStatementInsertionOrderDoesNotChangeCoherentState() throws Exception {
		DpInvocationReceipt first = invoke(false);
		DpInvocationReceipt permuted = invoke(true);
		Assert.assertEquals(sharedProducerTuple(first), sharedProducerTuple(permuted));
		Assert.assertEquals(first.counters().fallbackCount(), permuted.counters().fallbackCount());
		Assert.assertEquals(first.counters().repairCount(), permuted.counters().repairCount());
		Assert.assertEquals(first.counters().reenumerationCount(), permuted.counters().reenumerationCount());
	}

	private static void assertCoherent(DpInvocationReceipt receipt) {
		PlacementEmissionState selected = sharedProducerState(receipt);
		Assert.assertEquals(ExecType.CP, selected.placementState().execType());
		Assert.assertEquals(FederatedOutput.LOUT, selected.placementState().output());
		Assert.assertNull(selected.placementState().fType());
		Assert.assertFalse(selected.derivedFedFout());
		Assert.assertEquals(0, receipt.counters().fallbackCount());
		Assert.assertEquals(0, receipt.counters().repairCount());
		Assert.assertEquals(0, receipt.counters().reenumerationCount());
	}

	private static List<Object> sharedProducerTuple(DpInvocationReceipt receipt) {
		PlacementEmissionState selected = sharedProducerState(receipt);
		return List.of(selected.placementState().execType(), selected.placementState().output(),
			selected.placementState().fType() == null ? "null" : selected.placementState().fType(),
			selected.derivedFedFout());
	}

	private static PlacementEmissionState sharedProducerState(DpInvocationReceipt receipt) {
		PlacementAnalysis analysis = receipt.analysis();
		List<CompiledHopKey> sources = analysis.logicalTransientInputsInCanonicalOrder().stream()
			.map(LogicalTransientInputFact::sourceWrite).distinct().toList();
		Assert.assertEquals("fixture must expose one exact shared producer", 1, sources.size());
		CompiledHopKey source = sources.get(0);
		Assert.assertEquals("shared producer must have one concrete occurrence", 1,
			analysis.occurrences().stream().filter(value -> value.key() == source).count());
		return receipt.normalizedResult().selectedEmissionStates().get(source);
	}

	private static DpInvocationReceipt invoke(boolean consumerFirst) throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		DMLProgram program = compile(consumerFirst);
		AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
		String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
		try {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			new DMLTranslator(program).constructLops(program, receipt::set);
		}
		finally {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old);
		}
		Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
		return (DpInvocationReceipt) receipt.get();
	}

	@SuppressWarnings("unchecked")
	private static Map<Long,FederatedOutput> computeOutputDecisions(
		FederatedPlannerDpMemoTable memo, FedPlan root) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeOutputDecisions", FederatedPlannerDpMemoTable.class, FedPlan.class);
		method.setAccessible(true);
		return (Map<Long,FederatedOutput>) method.invoke(null, memo, root);
	}

	@SuppressWarnings("unchecked")
	private static Map<Long,Object> collectConflicts(FederatedPlannerDpMemoTable memo,
		FedPlan root, Map<Long,FederatedOutput> decisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"collectConflictsSingleBFS", FederatedPlannerDpMemoTable.class, FedPlan.class, Map.class);
		method.setAccessible(true);
		return (Map<Long,Object>) method.invoke(null, memo, root, decisions);
	}

	private static HostileComponentFixture findHostileComponentFixture(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo, DpEnumerationResult enumeration,
		Map<Long,FederatedOutput> decisions, Map<Long,Object> conflicts) throws Exception {
		for(Map.Entry<Long,Long> mapping : enumeration.rewireSnapshot().cloneToOriginal().entrySet()) {
			long cloneID = mapping.getKey();
			long originalID = mapping.getValue();
			Object conflict = conflicts.get(originalID);
			if(conflict == null || !conflictMemberIDs(conflict).containsAll(Set.of(originalID, cloneID)))
				continue;
			FedPlan local = memo.getFedPlanAfterPrune(originalID, FederatedOutput.LOUT);
			FedPlan federated = memo.getFedPlanAfterPrune(originalID, FederatedOutput.FOUT);
			if(local == null && federated == null)
				continue;
			FedPlan root = local == null ? federated : federated == null ? local
				: local.getCumulativeCost() <= federated.getCumulativeCost() ? local : federated;
			FederatedOutput effectiveOutput = decisions.getOrDefault(originalID, root.getFedOutType());
			FedPlan effective = memo.getFedPlanAfterPrune(originalID, effectiveOutput);
			if(effective != null)
				root = effective;
			CompiledHopKey rootKey = memo.requirePlanCarrierOccurrence(root.getHopRef()).key();
			PlacementState locked = root.getSelectedPlacementState();
			List<Hop> family = new java.util.ArrayList<>();
			for(long memberID : conflictMemberIDs(conflict)) {
				FedPlan member = cheapest(memo, memberID);
				if(member != null && family.stream().noneMatch(hop -> hop == member.getHopRef()))
					family.add(member.getHopRef());
			}
			if(family.size() > 1)
				return new HostileComponentFixture(rootKey, root, locked,
					!root.isDerivedFedFout(), List.copyOf(family));
		}
		Map<Long,Object> conflictMembers = new LinkedHashMap<>();
		for(Map.Entry<Long,Object> entry : conflicts.entrySet())
			conflictMembers.put(entry.getKey(), conflictMemberIDs(entry.getValue()));
		throw new AssertionError("fixture has no real clone-family root with an opposite exact child arm: clones="
			+ enumeration.rewireSnapshot().cloneToOriginal() + " conflicts=" + conflictMembers);
	}

	private static Object newSelectedState(FedPlan plan) throws Exception {
		PlacementState exact = plan.getSelectedPlacementState();
		return newSelectedState(exact, plan.isDerivedFedFout());
	}

	private static Object newSelectedState(PlacementState exact, boolean derivedFedFout) throws Exception {
		Class<?> type = Class.forName(FederatedPlannerDpFedCostBased.class.getName() + "$SelectedDpState");
		Constructor<?> constructor = type.getDeclaredConstructor(ExecType.class, FederatedOutput.class,
			FType.class, boolean.class, PlacementState.class);
		constructor.setAccessible(true);
		return constructor.newInstance(exact.execType(), exact.output(), exact.fType(), derivedFedFout, exact);
	}

	private static FedPlan cheapest(FederatedPlannerDpMemoTable memo, long hopID) {
		FedPlan local = memo.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
		FedPlan federated = memo.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);
		return local == null ? federated : federated == null ? local
			: local.getCumulativeCost() <= federated.getCumulativeCost() ? local : federated;
	}

	@SuppressWarnings("unchecked")
	private static Set<Long> conflictMemberIDs(Object conflict) throws Exception {
		Field field = conflict.getClass().getDeclaredField("memberHopIDs");
		field.setAccessible(true);
		return Set.copyOf((Set<Long>) field.get(conflict));
	}

	private static CloneSnapshot snapshotCloneFamily(List<Hop> family) {
		List<List<Object>> states = family.stream().map(CampaignBG014DisconnectedComponentCompletionRedTest::hopState)
			.toList();
		return new CloneSnapshot(states,
			new LinkedHashMap<>(FederatedPlannerUtils.snapshotPlannerRecompileStates()),
			FederatedPlannerUtils.snapshotAmbiguousPlannerRecompileSignatures());
	}

	private static Map<Hop,MutableHopState> snapshotMutableHopState(List<Hop> family) {
		Map<Hop,MutableHopState> states = new IdentityHashMap<>();
		for(Hop hop : family)
			states.put(hop, new MutableHopState(hop.getExecType(), hop.getForcedExecType(),
				hop.getFederatedOutput(), hop.isFederatedOutputDerived()));
		return states;
	}

	private static void restoreMutableHopState(Map<Hop,MutableHopState> states) {
		for(Map.Entry<Hop,MutableHopState> entry : states.entrySet()) {
			Hop hop = entry.getKey();
			MutableHopState state = entry.getValue();
			hop.setExecType(state.execType());
			if(state.forcedExecType() == null)
				hop.clearForcedExecType();
			else
				hop.setForcedExecType(state.forcedExecType());
			hop.setFederatedOutput(state.output());
			hop.setFederatedOutputDerived(state.derived());
		}
	}

	private static List<Object> hopState(Hop hop) {
		return List.of(String.valueOf(hop.getExecType()), String.valueOf(hop.getForcedExecType()),
			String.valueOf(hop.getFederatedOutput()), hop.isFederatedOutputDerived());
	}

	private record CloneSnapshot(List<List<Object>> familyStates,
		Map<String,FederatedPlannerUtils.PlannerRecompileStateSnapshot> recompileStates,
		java.util.Set<String> ambiguousSignatures) { }

	private record MutableHopState(ExecType execType, ExecType forcedExecType,
		FederatedOutput output, boolean derived) { }

	private record HostileComponentFixture(CompiledHopKey rootKey, FedPlan rootPlan,
		PlacementState lockedRootState, boolean lockedDerivedFedFout, List<Hop> familyHops) { }

	private record EnumeratedFixture(PlacementAnalysis analysis, FederatedPlannerDpMemoTable memo,
		Map<Long,FederatedOutput> outputDecisions, Map<Long,Object> conflicts,
		HostileComponentFixture hostile) { }

	private static AccumulatorSnapshot snapshotAccumulators(Map<CompiledHopKey,Object> selectedStates,
		Set<CompiledHopKey> visitedPlanHops, Map<Long,FType> fTypeMap,
		Map<Long,FederatedOutput> outputDecisions, Map<Long,Object> conflicts,
		Map<Long,Object> localMaterializeRequests) throws ReflectiveOperationException {
		Map<Long,List<Object>> conflictStates = new LinkedHashMap<>();
		for(Map.Entry<Long,Object> entry : conflicts.entrySet())
			conflictStates.put(entry.getKey(), snapshotConflict(entry.getValue()));
		Map<Long,List<Object>> requestStates = new LinkedHashMap<>();
		for(Map.Entry<Long,Object> entry : localMaterializeRequests.entrySet())
			requestStates.put(entry.getKey(), snapshotLocalMaterializeRequest(entry.getValue()));
		return new AccumulatorSnapshot(Map.copyOf(selectedStates), Set.copyOf(visitedPlanHops),
			Map.copyOf(fTypeMap), Map.copyOf(outputDecisions), Map.copyOf(conflictStates),
			Map.copyOf(requestStates));
	}

	private static List<Object> snapshotConflict(Object conflict) throws ReflectiveOperationException {
		List<Object> state = new java.util.ArrayList<>();
		for(String fieldName : List.of("parents", "memberHopIDs", "selectedMemberPlans",
			"seenLOUT", "seenFOUT", "canChooseLOUT", "canChooseFOUT")) {
			Field field = conflict.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			Object value = field.get(conflict);
			if(value instanceof Set<?> set)
				value = List.copyOf(set);
			else if(value instanceof Map<?,?> map)
				value = Map.copyOf(map);
			state.add(value);
		}
		return List.copyOf(state);
	}

	private static List<Object> snapshotLocalMaterializeRequest(Object request)
		throws ReflectiveOperationException {
		List<Object> state = new java.util.ArrayList<>();
		for(String fieldName : List.of("producerHopID", "producerHop", "consumerHops",
			"consumerOutputs", "fTypeHint")) {
			Field field = request.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			Object value = field.get(request);
			if(value instanceof Set<?> set)
				value = List.copyOf(set);
			else if(value instanceof Map<?,?> map)
				value = Map.copyOf(map);
			state.add(value);
		}
		return List.copyOf(state);
	}

	private record AccumulatorSnapshot(Map<CompiledHopKey,Object> selectedStates,
		Set<CompiledHopKey> visitedPlanHops, Map<Long,FType> fTypeMap,
		Map<Long,FederatedOutput> outputDecisions, Map<Long,List<Object>> conflicts,
		Map<Long,List<Object>> localMaterializeRequests) { }

	private static DMLProgram compile(boolean consumerFirst) throws Exception {
		Path data = Files.createTempFile("g014-component-", ".data");
		Path mtd = Path.of(data + ".mtd");
		Files.writeString(data, "");
		Files.writeString(mtd, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
			+ "\"format\":\"text\",\"rows\":4,\"cols\":2,\"nnz\":0,"
			+ "\"privacy\":\"private-aggregate\"}");
		data.toFile().deleteOnExit();
		mtd.toFile().deleteOnExit();
		String path = data.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		String script = String.join("\n",
			"g=function(matrix[double] I) return (matrix[double] O){O=rowSums(I);}",
			"P_LOCAL=read(\"" + path + "\");",
			"P=federated(local_matrix=P_LOCAL,addresses=list(\"localhost:1234\",\"localhost:1235\"),"
				+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"S=sum(P);",
			"Q=g(P);",
			consumerFirst ? "print(sum(Q)+S);" : "print(S+sum(Q));", "");
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}
}
