/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/**
 * L2SVM clone-family regression: conflict discovery must retain a concrete
 * occurrence even when the current family-wide output decision is unavailable
 * for that occurrence.
 */
public class CampaignBG014DpL2SvmCloneFamilyDecisionRedTest {
	@Test
	public void conflictDiscoveryRetainsLoutOnlyCloneUnderFamilyFoutDecision() throws Exception {
		Hop original = new LiteralOp("original");
		Hop clone = new LiteralOp("clone");
		Hop parentFout = new LiteralOp("parentFout");
		Hop parentLout = new LiteralOp("parentLout");
		Hop aggregate = new LiteralOp("aggregate");

		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.registerCloneMapping(Map.of(clone.getHopID(), original.getHopID()));

		add(memo, original, FederatedOutput.LOUT, ExecType.CP, List.of());
		add(memo, original, FederatedOutput.FOUT, ExecType.FED, List.of());
		add(memo, clone, FederatedOutput.LOUT, ExecType.CP, List.of());
		add(memo, parentFout, FederatedOutput.LOUT, ExecType.CP,
			List.of(Pair.of(original.getHopID(), FederatedOutput.FOUT)));
		add(memo, parentLout, FederatedOutput.LOUT, ExecType.CP,
			List.of(Pair.of(clone.getHopID(), FederatedOutput.LOUT)));

		FedPlan rootPlan = plan(aggregate, FederatedOutput.LOUT, ExecType.CP,
			List.of(Pair.of(parentFout.getHopID(), FederatedOutput.LOUT),
				Pair.of(parentLout.getHopID(), FederatedOutput.LOUT)));
		Map<Long, FederatedOutput> decisions = Map.of(original.getHopID(), FederatedOutput.FOUT);

		Map<Long, Object> conflicts = collectConflicts(memo, rootPlan, decisions);
		Object family = conflicts.get(original.getHopID());
		Assert.assertNotNull("The original/clone family must be represented", family);
		Assert.assertEquals("Both concrete occurrences must remain visible under an infeasible family decision",
			Set.of(original.getHopID(), clone.getHopID()), memberHopIDs(family));

		refreshChoiceFeasibility(conflicts, memo);
		Assert.assertFalse("A family-wide FOUT choice is illegal when one concrete clone has no FOUT variant",
			booleanField(family, "canChooseFOUT"));
		Assert.assertTrue("The common LOUT choice remains legal for the observed clone",
			booleanField(family, "canChooseLOUT"));
	}

	@Test
	public void outputDecisionsIncludeAndCloseVirtualAdditionalRootFamily() throws Exception {
		Hop originalRoot = new LiteralOp("originalRoot");
		Hop cloneRoot = new LiteralOp("cloneRoot");
		Hop originalChild = new LiteralOp("originalChild");
		Hop cloneChild = new LiteralOp("cloneChild");
		Hop ordinaryParent = new LiteralOp("ordinaryParent");
		Hop aggregate = new LiteralOp("aggregate");

		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.registerCloneMapping(Map.of(
			cloneRoot.getHopID(), originalRoot.getHopID(),
			cloneChild.getHopID(), originalChild.getHopID()));
		memo.registerAdditionalRootHopIDs(List.of(cloneRoot));

		add(memo, originalChild, FederatedOutput.LOUT, ExecType.CP, List.of(), 1.0);
		add(memo, originalChild, FederatedOutput.FOUT, ExecType.FED, List.of(), 100.0);
		add(memo, cloneChild, FederatedOutput.LOUT, ExecType.CP, List.of(), 1.0);
		add(memo, cloneChild, FederatedOutput.FOUT, ExecType.FED, List.of(), 100.0);

		add(memo, originalRoot, FederatedOutput.LOUT, ExecType.CP,
			List.of(Pair.of(originalChild.getHopID(), FederatedOutput.LOUT)), 2.0);
		add(memo, originalRoot, FederatedOutput.FOUT, ExecType.FED,
			List.of(Pair.of(originalChild.getHopID(), FederatedOutput.FOUT)), 1.0);
		add(memo, cloneRoot, FederatedOutput.LOUT, ExecType.CP,
			List.of(Pair.of(cloneChild.getHopID(), FederatedOutput.LOUT)), 2.0);
		add(memo, cloneRoot, FederatedOutput.FOUT, ExecType.FED,
			List.of(Pair.of(cloneChild.getHopID(), FederatedOutput.FOUT)), 1.0);
		add(memo, ordinaryParent, FederatedOutput.LOUT, ExecType.CP,
			List.of(Pair.of(originalChild.getHopID(), FederatedOutput.LOUT)), 1.0);

		FedPlan rootPlan = plan(aggregate, FederatedOutput.LOUT, ExecType.CP,
			List.of(Pair.of(ordinaryParent.getHopID(), FederatedOutput.LOUT)));

		Map<Long, FederatedOutput> decisions = computeOutputDecisions(memo, rootPlan);
		Assert.assertEquals("The virtual additional root must receive a family-wide output decision",
			FederatedOutput.LOUT, decisions.get(originalRoot.getHopID()));
		Assert.assertEquals("The selected root and child outputs must be closed together",
			FederatedOutput.LOUT, decisions.get(originalChild.getHopID()));
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, FederatedOutput> computeOutputDecisions(
		FederatedPlannerDpMemoTable memo, FedPlan root) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"computeOutputDecisions", FederatedPlannerDpMemoTable.class, FedPlan.class);
		method.setAccessible(true);
		return (Map<Long, FederatedOutput>) method.invoke(null, memo, root);
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, Object> collectConflicts(FederatedPlannerDpMemoTable memo, FedPlan root,
		Map<Long, FederatedOutput> decisions) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"collectConflictsSingleBFS", FederatedPlannerDpMemoTable.class, FedPlan.class, Map.class);
		method.setAccessible(true);
		return (Map<Long, Object>) method.invoke(null, memo, root, decisions);
	}

	private static void refreshChoiceFeasibility(Map<Long, Object> conflicts,
		FederatedPlannerDpMemoTable memo) throws Exception {
		Method method = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"refreshConflictChoiceFeasibility", Map.class, FederatedPlannerDpMemoTable.class);
		method.setAccessible(true);
		method.invoke(null, conflicts, memo);
	}

	@SuppressWarnings("unchecked")
	private static Set<Long> memberHopIDs(Object conflict) throws Exception {
		Field field = conflict.getClass().getDeclaredField("memberHopIDs");
		field.setAccessible(true);
		return Set.copyOf((Set<Long>) field.get(conflict));
	}

	private static boolean booleanField(Object conflict, String name) throws Exception {
		Field field = conflict.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getBoolean(conflict);
	}

	private static FedPlan add(FederatedPlannerDpMemoTable memo, Hop hop, FederatedOutput output,
		ExecType exec, List<Pair<Long, FederatedOutput>> children) {
		return add(memo, hop, output, exec, children, 1.0);
	}

	private static FedPlan add(FederatedPlannerDpMemoTable memo, Hop hop, FederatedOutput output,
		ExecType exec, List<Pair<Long, FederatedOutput>> children, double cumulativeCost) {
		FedPlanVariants variants = variants(hop, output);
		FedPlan plan = new FedPlan(cumulativeCost, variants, children);
		plan.setExecType(exec);
		plan.setFType(FType.ROW);
		variants.addFedPlan(plan);
		variants.pruneFedPlans();
		memo.addFedPlanVariants(hop.getHopID(), output, variants);
		return memo.getFedPlanAfterPrune(hop.getHopID(), output);
	}

	private static FedPlan plan(Hop hop, FederatedOutput output, ExecType exec,
		List<Pair<Long, FederatedOutput>> children) {
		FedPlanVariants variants = variants(hop, output);
		FedPlan plan = new FedPlan(1.0, variants, children);
		plan.setExecType(exec);
		plan.setFType(FType.ROW);
		return plan;
	}

	private static FedPlanVariants variants(Hop hop, FederatedOutput output) {
		HopCommon common = new HopCommon(hop, 1.0, 1.0, 1.0, 1, List.of());
		common.setSelfCost(0.1);
		common.setForwardingCost(0.1);
		return new FedPlanVariants(common, output);
	}
}
