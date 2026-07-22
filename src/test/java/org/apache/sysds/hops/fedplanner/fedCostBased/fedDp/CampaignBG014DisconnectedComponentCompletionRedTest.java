/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** RED for coherent completion of one disconnected component with two sink paths. */
public class CampaignBG014DisconnectedComponentCompletionRedTest {
	@Test
	public void captureOnlyCloneFamilySelectionDoesNotMutatePlannerState() throws Exception {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		LiteralOp original = new LiteralOp(7L);
		LiteralOp clone = new LiteralOp(11L);
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		FedPlan originalPlan = addPlan(memo, original, FederatedOutput.LOUT, ExecType.CP, 0x1.0p2);
		FedPlan clonePlan = addPlan(memo, clone, FederatedOutput.LOUT, ExecType.CP, 0x1.0p2);
		memo.registerCloneMapping(Map.of(clone.getHopID(), original.getHopID()));

		Class<?> conflictType = Class.forName(FederatedPlannerDpFedCostBased.class.getName() + "$ConflictEntry");
		Constructor<?> constructor = conflictType.getDeclaredConstructor(FederatedOutput.class,
			FedPlan.class, long.class, FedPlan.class);
		constructor.setAccessible(true);
		Object conflict = constructor.newInstance(FederatedOutput.LOUT, originalPlan,
			original.getHopID(), originalPlan);
		Method addUsage = conflictType.getDeclaredMethod("addUsage", FederatedOutput.class,
			FedPlan.class, long.class, FedPlan.class);
		addUsage.setAccessible(true);
		addUsage.invoke(conflict, FederatedOutput.LOUT, clonePlan, clone.getHopID(), clonePlan);
		Map<Long,Object> conflicts = new HashMap<>();
		conflicts.put(original.getHopID(), conflict);
		Map<Long,FederatedOutput> outputDecisions = new LinkedHashMap<>();
		outputDecisions.put(original.getHopID(), FederatedOutput.LOUT);
		Map<String,String> selectedStates = new LinkedHashMap<>(Map.of("boundary", "locked"));
		Set<String> visitedPlanHops = new LinkedHashSet<>(Set.of("boundary"));
		Map<Long,String> fTypeMap = new LinkedHashMap<>(Map.of(original.getHopID(), "ROW"));
		Map<Long,String> localMaterializeRequests =
			new LinkedHashMap<>(Map.of(original.getHopID(), "existing-request"));

		CloneSnapshot before = snapshotCloneFamily(original, clone);
		AccumulatorSnapshot accumulatorsBefore = snapshotAccumulators(selectedStates, visitedPlanHops,
			fTypeMap, outputDecisions, conflicts, localMaterializeRequests);
		Method selector = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"selectLoopAwareCloneFamilyRewritePlan", FederatedPlannerDpMemoTable.class,
			long.class, FedPlan.class, Map.class, Map.class);
		selector.setAccessible(true);
		Object selected = selector.invoke(null, memo, original.getHopID(), originalPlan,
			outputDecisions, conflicts);
		CloneSelectionReceipt receipt = new CloneSelectionReceipt((FedPlan) selected,
			Double.doubleToRawLongBits(((FedPlan) selected).getCumulativeCost()));
		Assert.assertSame("capture-only path must retain exact best-plan identity", originalPlan, receipt.plan());
		Assert.assertEquals(Double.doubleToRawLongBits(originalPlan.getCumulativeCost()), receipt.costBits());

		try {
			requireExactComponentCoverage(
				Set.of(original.getHopID(), clone.getHopID()), Set.of(original.getHopID()));
			Assert.fail("fixture must force a later incomplete component-coverage failure");
		}
		catch(IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("incomplete component coverage"));
		}
		Assert.assertEquals("failed component changed global planning accumulators",
			accumulatorsBefore, snapshotAccumulators(selectedStates, visitedPlanHops,
				fTypeMap, outputDecisions, conflicts, localMaterializeRequests));
		Assert.assertEquals("clone-family selection mutated Hop/recompile state before component validation",
			before, snapshotCloneFamily(original, clone));
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

	private static FedPlan addPlan(FederatedPlannerDpMemoTable memo, LiteralOp carrier,
		FederatedOutput output, ExecType execType, double cost) {
		HopCommon common = new HopCommon(carrier, 1, 1, 1, 1, List.of());
		common.setSelfCost(0x1.0p-4);
		common.setForwardingCost(0x1.0p-3);
		FedPlanVariants variants = new FedPlanVariants(common, output);
		FedPlan plan = new FedPlan(cost, variants, List.<Pair<Long,FederatedOutput>>of());
		plan.setExecType(execType);
		variants.addFedPlan(plan);
		variants.pruneFedPlans();
		memo.registerHopRefs(Map.of(carrier.getHopID(), common));
		memo.addFedPlanVariants(carrier.getHopID(), output, variants);
		return plan;
	}

	private static CloneSnapshot snapshotCloneFamily(LiteralOp original, LiteralOp clone) {
		return new CloneSnapshot(hopState(original), hopState(clone),
			new LinkedHashMap<>(FederatedPlannerUtils.snapshotPlannerRecompileStates()),
			FederatedPlannerUtils.snapshotAmbiguousPlannerRecompileSignatures());
	}

	private static List<Object> hopState(LiteralOp hop) {
		return List.of(String.valueOf(hop.getExecType()), String.valueOf(hop.getForcedExecType()),
			String.valueOf(hop.getFederatedOutput()), hop.isFederatedOutputDerived());
	}

	private record CloneSnapshot(List<Object> original, List<Object> cloneState,
		Map<String,FederatedPlannerUtils.PlannerRecompileStateSnapshot> recompileStates,
		java.util.Set<String> ambiguousSignatures) { }

	private record CloneSelectionReceipt(FedPlan plan, long costBits) { }

	private static AccumulatorSnapshot snapshotAccumulators(Map<String,String> selectedStates,
		Set<String> visitedPlanHops, Map<Long,String> fTypeMap,
		Map<Long,FederatedOutput> outputDecisions, Map<Long,Object> conflicts,
		Map<Long,String> localMaterializeRequests) throws ReflectiveOperationException {
		Map<Long,List<Object>> conflictStates = new LinkedHashMap<>();
		for(Map.Entry<Long,Object> entry : conflicts.entrySet())
			conflictStates.put(entry.getKey(), snapshotConflict(entry.getValue()));
		return new AccumulatorSnapshot(Map.copyOf(selectedStates), Set.copyOf(visitedPlanHops),
			Map.copyOf(fTypeMap), Map.copyOf(outputDecisions), Map.copyOf(conflictStates),
			Map.copyOf(localMaterializeRequests));
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

	private record AccumulatorSnapshot(Map<String,String> selectedStates,
		Set<String> visitedPlanHops, Map<Long,String> fTypeMap,
		Map<Long,FederatedOutput> outputDecisions, Map<Long,List<Object>> conflicts,
		Map<Long,String> localMaterializeRequests) { }

	private static void requireExactComponentCoverage(Set<Long> expected, Set<Long> captured) {
		if(!expected.equals(captured))
			throw new IllegalStateException("incomplete component coverage: expected=" + expected
				+ " captured=" + captured);
	}

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
