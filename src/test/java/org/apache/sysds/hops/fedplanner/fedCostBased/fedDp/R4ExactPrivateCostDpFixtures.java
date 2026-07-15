/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Native, manifest-independent DP private fixtures for the R4 cost contract. */
public final class R4ExactPrivateCostDpFixtures {
	public record Alternative(ExecType exec, FederatedOutput output, double cost) { }
	public record Fixture(String id, Map<String,String> namedRoles, Map<String,String> literalAliases,
		List<Alternative> alternatives, ExecType selectedExec, FederatedOutput selectedOutput,
		double objective, Map<String,String> facts, PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo, Hop root, List<FedPlan> enumeratedPlans, FedPlan selectedPlan) { }

	public static List<Fixture> all() throws Exception {
		return List.of(root("C2-DP-01-ROOT-EQUAL-LOUT", 0x1.0p3, 0x1.0p3),
			root("C2-DP-02-ROOT-ONEULP-FOUT",
				Double.longBitsToDouble(Double.doubleToLongBits(0x1.0p3) + 1), 0x1.0p3),
			stableVariant(), fedLocalOutput());
	}

	public static Fixture anchorContrast(String id, PlacementAnalysis analysis, Hop root,
		Map<String,Hop> literalHops, boolean concreteAnchor) throws Exception {
		double lout = concreteAnchor ? 0x1.8p2 : 0x1.0p2;
		double fout = concreteAnchor ? 0x1.0p2 : 0x1.8p2;
		FedPlan l = plan(root, FederatedOutput.LOUT, ExecType.CP, lout, List.of());
		FedPlan f = plan(root, FederatedOutput.FOUT, ExecType.CP, fout, List.of());
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.addFedPlanVariants(root.getHopID(), FederatedOutput.LOUT, variants(l));
		memo.addFedPlanVariants(root.getHopID(), FederatedOutput.FOUT, variants(f));
		Method select = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod("getMinCostRootFedPlan",
			Set.class, FederatedPlannerDpMemoTable.class);
		select.setAccessible(true);
		FedPlan rootSelection = (FedPlan) select.invoke(null, Set.of(root), memo);
		FederatedOutput selectedOutput = rootSelection.getChildFedPlans().get(0).getRight();
		FedPlan selected = memo.getFedPlanAfterPrune(root.getHopID(), selectedOutput);
		Map<String,String> named = new LinkedHashMap<>();
		Map<String,String> aliases = new LinkedHashMap<>();
		int ordinal = 0;
		for(Map.Entry<String,Hop> entry : literalHops.entrySet()) {
			String role = "compiled" + ordinal++;
			named.put(role, role + ":" + entry.getValue().getClass().getName() + ":" + entry.getValue().getOpString());
			aliases.put(entry.getKey(), role);
		}
		List<FedPlan> enumerated = List.of(memo.getFedPlanAfterPrune(root.getHopID(), FederatedOutput.LOUT),
			memo.getFedPlanAfterPrune(root.getHopID(), FederatedOutput.FOUT));
		return new Fixture(id, Map.copyOf(named), Map.copyOf(aliases),
			List.of(new Alternative(ExecType.CP, FederatedOutput.LOUT, lout),
				new Alternative(ExecType.CP, FederatedOutput.FOUT, fout)),
			ExecType.CP, selectedOutput, selected.getCumulativeCost(),
			Map.of("anchorCapability", concreteAnchor ? "CONCRETE" : "MISSING"), analysis, memo, root,
			List.copyOf(enumerated), selected);
	}

	private static Fixture root(String id, double lout, double fout) throws Exception {
		Hop root = new LiteralOp(101L);
		PlacementAnalysis analysis = analysis(root);
		FedPlan l = plan(root, FederatedOutput.LOUT, ExecType.CP, lout, List.of());
		FedPlan f = plan(root, FederatedOutput.FOUT, ExecType.FED, fout, List.of());
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.addFedPlanVariants(root.getHopID(), FederatedOutput.LOUT, variants(l));
		memo.addFedPlanVariants(root.getHopID(), FederatedOutput.FOUT, variants(f));
		Method select = FederatedPlannerDpCostEnumerator.class.getDeclaredMethod("getMinCostRootFedPlan",
			Set.class, FederatedPlannerDpMemoTable.class);
		select.setAccessible(true);
		FedPlan rootSelection = (FedPlan) select.invoke(null, Set.of(root), memo);
		Pair<Long,FederatedOutput> edge = rootSelection.getChildFedPlans().get(0);
		FedPlan selected = memo.getFedPlanAfterPrune(root.getHopID(), edge.getRight());
		Map<String,String> roles = roles("root", root);
		List<FedPlan> enumerated=List.copyOf(memo.getFedPlanVariants(Pair.of(root.getHopID(),selected.getFedOutType())).getFedPlanVariants());
		return new Fixture(id, roles, alias(id, "root", root),
			List.of(new Alternative(ExecType.CP, FederatedOutput.LOUT, lout),
				new Alternative(ExecType.FED, FederatedOutput.FOUT, fout)),
			edge.getRight() == FederatedOutput.LOUT ? ExecType.CP : ExecType.FED, edge.getRight(),
			selected.getCumulativeCost(), Map.of("tieRule", "LOUT_LE_FOUT",
				"bitDistance", Long.toString(Math.abs(Double.doubleToLongBits(lout) - Double.doubleToLongBits(fout)))),
			analysis,memo,root,enumerated,selected);
	}

	private static Fixture stableVariant() throws Exception {
		Hop root = new LiteralOp(201L);
		Hop firstChild = new LiteralOp(202L);
		Hop secondChild = new LiteralOp(203L);
		PlacementAnalysis analysis=analysis(root,firstChild,secondChild);
		FedPlan first = plan(root, FederatedOutput.FOUT, ExecType.CP, 0x1.4p2,
			List.of(Pair.of(firstChild.getHopID(), FederatedOutput.LOUT)));
		FedPlan second = plan(root, FederatedOutput.FOUT, ExecType.CP, 0x1.4p2,
			List.of(Pair.of(secondChild.getHopID(), FederatedOutput.FOUT)));
		FedPlanVariants variants = variants(first, second);
		variants.pruneFedPlans();
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.addFedPlanVariants(root.getHopID(), FederatedOutput.FOUT, variants);
		FedPlan selected = memo.getFedPlanAfterPrune(root.getHopID(), FederatedOutput.FOUT);
		Map<String,String> roles = new LinkedHashMap<>();
		roles.putAll(roles("root", root)); roles.putAll(roles("childV1", firstChild));
		roles.putAll(roles("childV2", secondChild));
		return new Fixture("C2-DP-03-STABLE-VARIANT", Map.copyOf(roles), alias("C2-DP-03", "root", root),
			List.of(new Alternative(ExecType.CP, FederatedOutput.FOUT, 0x1.4p2),
				new Alternative(ExecType.CP, FederatedOutput.FOUT, 0x1.4p2)), ExecType.CP,
			FederatedOutput.FOUT, selected.getCumulativeCost(), Map.of("selectedInsertionOrdinal",
				selected.getChildFedPlans().get(0).getRight() == FederatedOutput.LOUT ? "0" : "1",
				"selectedChildOutput", selected.getChildFedPlans().get(0).getRight().name()),analysis,memo,root,
			List.copyOf(variants.getFedPlanVariants()),selected);
	}

	private static Fixture fedLocalOutput() throws Exception {
		DataOp vector = new DataOp("localVector", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 1, 100, 100, 1000);
		DataOp matrix = new DataOp("federatedMatrix", DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, 100, 100, 10000, 1000);
		Hop mm = HopRewriteUtils.createMatrixMultiply(vector, matrix);
		PlacementAnalysis analysis=analysis(mm);
		FedPlan cp = plan(mm, FederatedOutput.LOUT, ExecType.CP, 0x1.8p3, List.of());
		FedPlan fed = plan(mm, FederatedOutput.LOUT, ExecType.FED, 0x1.0p2, List.of());
		FedPlanVariants candidates = variants(cp, fed); candidates.pruneFedPlans();
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable();
		memo.addFedPlanVariants(mm.getHopID(), FederatedOutput.LOUT, candidates);
		FedPlan selected = memo.getFedPlanAfterPrune(mm.getHopID(), FederatedOutput.LOUT);
		Map<String,String> roles = new LinkedHashMap<>();
		roles.putAll(roles("vector", vector)); roles.putAll(roles("federatedMatrix", matrix));
		roles.putAll(roles("mmRoot", mm));
		return new Fixture("C2-DP-07-FED-LOCAL-OUTPUT", Map.copyOf(roles), alias("C2-DP-07", "mmRoot", mm),
			List.of(new Alternative(ExecType.CP, FederatedOutput.LOUT, 0x1.8p3),
				new Alternative(ExecType.FED, FederatedOutput.LOUT, 0x1.0p2)), selected.getExecType(),
			selected.getFedOutType(), selected.getCumulativeCost(), Map.of("runtimeOutputConstraint", "LOUT_ONLY",
				"operandShape", "VECTOR_X_FEDERATED_MM"),analysis,memo,mm,
			List.copyOf(candidates.getFedPlanVariants()),selected);
	}

	private static PlacementAnalysis analysis(Hop... roots) {
		StatementBlock block=new StatementBlock(); block.setHops(new ArrayList<>(List.of(roots)));
		DMLProgram program=new DMLProgram(); program.setStatementBlocks(new ArrayList<>(List.of(block)));
		PlacementAnalysis analysis=new NeutralPlacementGraphBuilder().buildAnalysis(program);
		for(Hop root:roots) if(analysis.occurrences().stream().noneMatch(o->o.hop()==root))
			throw new AssertionError("R4_TYPED_PRODUCER_IDENTITY|missingDpHop="+root.getHopID());
		return analysis;
	}

	private static Map<String,String> roles(String role, Hop hop) {
		return Map.of(role, role + ":" + hop.getClass().getName() + ":" + hop.getOpString());
	}

	private static Map<String,String> alias(String fixture, String role, Hop hop) {
		return Map.of("SYNTHETIC:" + fixture + ":" + role + ":" + hop.getClass().getName() + ":" + hop.getOpString(),
			role);
	}

	private static FedPlanVariants variants(FedPlan... plans) {
		FedPlanVariants variants = planVariants(plans[0].getHopRef(), plans[0].getFedOutType());
		for(FedPlan plan : plans) {
			FedPlan rebound = new FedPlan(plan.getCumulativeCost(), variants, plan.getChildFedPlans());
			rebound.setExecType(plan.getExecType()); rebound.setFType(plan.getFType()); variants.addFedPlan(rebound);
		}
		return variants;
	}

	private static FedPlanVariants planVariants(Hop hop, FederatedOutput output) {
		HopCommon common = new HopCommon(hop, 1.0, 1.0, 1.0, 1, List.of());
		common.setSelfCost(0x1.0p-4); common.setForwardingCost(0x1.0p-3);
		return new FedPlanVariants(common, output);
	}

	private static FedPlan plan(Hop hop, FederatedOutput output, ExecType exec, double cost,
		List<Pair<Long,FederatedOutput>> children) {
		FedPlanVariants variants = planVariants(hop, output);
		FedPlan plan = new FedPlan(cost, variants, children); plan.setExecType(exec); plan.setFType(FType.ROW);
		return plan;
	}

	private R4ExactPrivateCostDpFixtures() { }
}
