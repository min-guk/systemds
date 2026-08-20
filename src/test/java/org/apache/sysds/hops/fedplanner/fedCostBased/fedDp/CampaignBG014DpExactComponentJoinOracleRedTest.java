/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator.ExactRecurrenceTerm;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Production forest recurrence agrees with a Cartesian oracle and is order-stable. */
public class CampaignBG014DpExactComponentJoinOracleRedTest {
	private record Arm(String id, String authority, double sinkExclusive, double forwarding,
		double sharedExclusive) { }
	private record Selection(List<String> arms, double objective) { }

	@Test
	public void multiSinkSharedOccurrenceMatchesBruteForceForEveryRootAndArmPermutation() {
		List<Arm> first = List.of(
			new Arm("a-local", "LOCAL", 3.0, 1.0, 5.0),
			new Arm("a-fed", "FED", 1.0, 1.0, 2.0));
		List<Arm> second = List.of(
			new Arm("b-local", "LOCAL", 1.0, 1.0, 5.0),
			new Arm("b-fed", "FED", 4.0, 1.0, 2.0));

		for(boolean reverseRoots : List.of(false, true))
			for(boolean reverseFirst : List.of(false, true))
				for(boolean reverseSecond : List.of(false, true)) {
					List<List<Arm>> roots = new ArrayList<>(List.of(
						permute(first, reverseFirst), permute(second, reverseSecond)));
					if(reverseRoots)
						Collections.reverse(roots);
					Selection selected = bruteForce(roots);
					Assert.assertEquals(9.0, selected.objective(), 0.0);
					Assert.assertEquals(List.of("a-fed", "b-fed"), selected.arms());
				}
	}

	@Test
	public void sharedChildExclusiveIsChargedOnceAndEachSelectedEdgeIsChargedOnce() {
		List<ExactRecurrenceTerm> terms = List.of(
			term(1.0, 1.0), // sink A and its selected edge
			term(4.0, 1.0), // sink B and its selected edge
			term(2.0));     // one shared child occurrence
		Assert.assertEquals(9.0, FederatedPlannerDpCostEstimator.exactForestObjective(terms), 0.0);
	}

	@Test
	public void productionJoinChoosesMinimumCostRatherThanFirstCoherentArm() throws Exception {
		DMLProgram program = CampaignBG014HermeticPlannerFixtureFactory.compile("B-21");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		HopOccurrenceProjection occurrence = analysis.occurrences().stream()
			.filter(candidate -> analysis.graph().node(candidate.key()).orElseThrow()
				.legalAlternatives().stream().anyMatch(state -> state.output() == FederatedOutput.FOUT))
			.findFirst().orElseThrow();
		PlacementState state = analysis.graph().node(occurrence.key()).orElseThrow()
			.legalAlternatives().stream().filter(candidate -> candidate.output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow();
		FedPlanVariants variants = variants(occurrence.hop(), state.output());
		FedPlan expensiveFirst = plan(variants, state, 10d, false);
		FedPlan cheapLater = plan(variants, state, 1d, true);

		Class<?> componentClass = Class.forName(
			FederatedPlannerDpFedCostBased.class.getName() + "$OrdinaryComponentId");
		Constructor<?> componentConstructor = componentClass.getDeclaredConstructor(
			PlacementAnalysis.class, String.class, int.class, List.class);
		componentConstructor.setAccessible(true);
		CompiledHopKey key = occurrence.key();
		Object component = componentConstructor.newInstance(
			analysis, analysis.analysisFingerprint(), 0, List.of(key));
		Class<?> joinClass = Class.forName(
			FederatedPlannerDpFedCostBased.class.getName() + "$ExactComponentJoin");
		Object best = Array.newInstance(joinClass, 1);
		Method search = FederatedPlannerDpFedCostBased.class.getDeclaredMethod(
			"searchExactComponentAssignment", PlacementAnalysis.class,
			FederatedPlannerDpMemoTable.class, componentClass, List.class, List.class,
			Map.class, Map.class, int.class, Map.class, Map.class, double.class, Map.class,
			best.getClass());
		search.setAccessible(true);
		Map<CompiledHopKey,List<FedPlan>> domains = new IdentityHashMap<>();
		domains.put(key, List.of(expensiveFirst, cheapLater));
		search.invoke(null, analysis, new FederatedPlannerDpMemoTable(analysis), component,
			List.of(key), List.of(key), domains, new IdentityHashMap<>(), 0,
			new IdentityHashMap<>(), new IdentityHashMap<>(), 0d, new HashMap<>(), best);

		Object selectedJoin = Array.get(best, 0);
		Assert.assertNotNull(selectedJoin);
		Method objective = joinClass.getDeclaredMethod("objective");
		Method selections = joinClass.getDeclaredMethod("selections");
		objective.setAccessible(true);
		selections.setAccessible(true);
		Assert.assertEquals("minimum coherent component objective", 1d,
			(double) objective.invoke(selectedJoin), 0d);
		Object selectedState = ((Map<?,?>) selections.invoke(selectedJoin)).get(key);
		Method retainedPlan = selectedState.getClass().getDeclaredMethod("retainedPlan");
		retainedPlan.setAccessible(true);
		Assert.assertSame("later cheaper coherent arm", cheapLater,
			retainedPlan.invoke(selectedState));
	}

	private static Selection bruteForce(List<List<Arm>> roots) {
		Selection[] best = {null};
		search(roots, 0, new ArrayList<>(), new LinkedHashMap<>(), best);
		return best[0];
	}

	private static void search(List<List<Arm>> roots, int index, List<Arm> chosen,
		Map<String,String> authorities, Selection[] best) {
		if(index == roots.size()) {
			List<ExactRecurrenceTerm> terms = new ArrayList<>();
			for(Arm arm : chosen)
				terms.add(term(arm.sinkExclusive(), arm.forwarding()));
			Map<String,Arm> shared = new LinkedHashMap<>();
			for(Arm arm : chosen)
				shared.putIfAbsent(arm.authority(), arm);
			for(Arm arm : shared.values())
				terms.add(term(arm.sharedExclusive()));
			double objective = FederatedPlannerDpCostEstimator.exactForestObjective(terms);
			List<String> ids = chosen.stream().map(Arm::id).sorted().toList();
			if(best[0] == null || objective < best[0].objective()
				|| objective == best[0].objective() && compare(ids, best[0].arms()) < 0)
				best[0] = new Selection(ids, objective);
			return;
		}
		for(Arm arm : roots.get(index)) {
			String existing = authorities.get("shared");
			if(existing != null && !existing.equals(arm.authority()))
				continue;
			Map<String,String> next = new LinkedHashMap<>(authorities);
			next.put("shared", arm.authority());
			chosen.add(arm);
			search(roots, index + 1, chosen, next, best);
			chosen.remove(chosen.size() - 1);
		}
	}

	private static ExactRecurrenceTerm term(double exclusive, double... forwarding) {
		List<Long> edges = new ArrayList<>();
		for(double edge : forwarding)
			edges.add(Double.doubleToRawLongBits(edge));
		return new ExactRecurrenceTerm(Double.doubleToRawLongBits(exclusive), edges);
	}

	private static FedPlanVariants variants(Hop hop, FederatedOutput output) {
		HopCommon common = new HopCommon(hop, 1d, 1d, 1d, 1, List.of());
		common.setSelfCost(0d);
		common.setForwardingCost(0d);
		return new FedPlanVariants(common, output);
	}

	private static FedPlan plan(FedPlanVariants variants, PlacementState state,
		double cost, boolean derivedFout) {
		FedPlan plan = new FedPlan(cost, variants, List.of());
		plan.setExecType(state.execType());
		plan.setFType(state.fType());
		plan.setCpFoutType(state.fType());
		plan.setDerivedFedFout(derivedFout);
		plan.setSelectedPlacementState(state);
		plan.setExactRecurrenceCosts(0d, 0d);
		return plan;
	}

	private static List<Arm> permute(List<Arm> source, boolean reverse) {
		List<Arm> result = new ArrayList<>(source);
		if(reverse)
			Collections.reverse(result);
		return result;
	}

	private static int compare(List<String> left, List<String> right) {
		return String.join("|", left).compareTo(String.join("|", right));
	}
}
