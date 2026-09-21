/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Small exhaustive oracle for the policy-owned candidate-row branch-and-bound. */
public class CandidateSelectionPruningOracleTest {
	@Test(timeout = 30000)
	public void boundedPolicySearchMatchesLiteralCartesianEnumeration() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(compileProtectedFixture());
		List<RelocationAction> actions = analysis.graph().relocationActions();
		var policy = new PolicyFirstFeasiblePlacementSelector().select(analysis, analysis.graph());
		Map<CompiledHopKey,PlacementState> assignment = policy.assignment();
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> complete =
			CandidateSelections.feasibleVariants(
				analysis, analysis.graph(), actions, assignment);
		Map<CompiledHopKey,CandidateSelectionReceipt> selectedByOwner = new IdentityHashMap<>();
		for(CandidateSelectionReceipt row : policy.selectedCandidateSelections())
			selectedByOwner.put(row.rule().parentOccurrence(), row);

		Map<CompiledHopKey,List<CandidateSelectionReceipt>> bounded = new LinkedHashMap<>();
		long product = 1L;
		int variableDomains = 0;
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : complete.entrySet()) {
			List<CandidateSelectionReceipt> rows = entry.getValue();
			boolean independentlyEnumerable = rows.size() > 1
				&& product <= 256L / rows.size();
			if(independentlyEnumerable) {
				bounded.put(entry.getKey(), rows);
				product = Math.multiplyExact(product, rows.size());
				variableDomains++;
			}
			else {
				CandidateSelectionReceipt selected = selectedByOwner.get(entry.getKey());
				CandidateSelectionReceipt pinned = rows.stream()
					.filter(row -> row == selected || row.equals(selected)).findFirst()
					.orElseThrow(() -> new AssertionError(
						"selected policy row is absent from its candidate domain"));
				bounded.put(entry.getKey(), List.of(pinned));
			}
		}
		Assert.assertTrue("fixture must retain two independent non-singleton row domains",
			variableDomains >= 2 && product >= 4L);

		CandidateSelections.Selection expected = exhaustive(
			analysis, actions, assignment, bounded);
		Assert.assertEquals("fixture must exercise the zero-emission proof path", 0,
			expected.relocationPhysicalEmissionCount()
				+ expected.localMaterializationActionCount()
				+ expected.foutMaterializationActionCount());
		CandidateSelections.Selection actual =
			CandidateSelections.selectMaterializationMaximalPrevalidated(
				analysis, analysis.graph(), actions, assignment,
				analysis.relocationOrderFor(actions), null, bounded);

		Assert.assertEquals(expected.candidates(), actual.candidates());
		Assert.assertEquals(expected.relocationChoices(), actual.relocationChoices());
		Assert.assertEquals(expected.emittedActions(), actual.emittedActions());
		Assert.assertEquals(expected.materializedInputCount(), actual.materializedInputCount());
		Assert.assertEquals(expected.relocationPhysicalEmissionCount(),
			actual.relocationPhysicalEmissionCount());
		Assert.assertEquals(expected.localMaterializationActionCount(),
			actual.localMaterializationActionCount());
		Assert.assertEquals(expected.foutMaterializationActionCount(),
			actual.foutMaterializationActionCount());
	}

	private static CandidateSelections.Selection exhaustive(PlacementAnalysis analysis,
		List<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants) {
		List<CompiledHopKey> consumers = new ArrayList<>(variants.keySet());
		Collections.sort(consumers);
		Oracle oracle = new Oracle(analysis, actions, assignment, consumers, variants);
		oracle.solve(0);
		return oracle.best();
	}

	private static final class Oracle {
		private final PlacementAnalysis analysis;
		private final List<RelocationAction> actions;
		private final Map<CompiledHopKey,PlacementState> assignment;
		private final List<CompiledHopKey> consumers;
		private final Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants;
		private final List<CandidateSelectionReceipt> selected = new ArrayList<>();
		private CandidateSelections.Selection best;
		private int bestInput = Integer.MIN_VALUE;
		private int bestAligned = Integer.MIN_VALUE;
		private int bestPhysical = Integer.MAX_VALUE;

		private Oracle(PlacementAnalysis analysis, List<RelocationAction> actions,
			Map<CompiledHopKey,PlacementState> assignment, List<CompiledHopKey> consumers,
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants) {
			this.analysis = analysis;
			this.actions = actions;
			this.assignment = assignment;
			this.consumers = consumers;
			this.variants = variants;
		}

		private void solve(int index) {
			if(index < consumers.size()) {
				for(CandidateSelectionReceipt row : variants.get(consumers.get(index))) {
					selected.add(row);
					solve(index + 1);
					selected.remove(selected.size() - 1);
				}
				return;
			}
			if(!CandidateSelections.realizationsCanStillBeCompatible(
				analysis, assignment, selected))
				return;
			RelocationSelections.Selection relocation;
			try {
				relocation = RelocationSelections.selectCanonicalPrevalidated(
					analysis, analysis.graph(), actions, assignment, selected,
					(demand, action) -> true);
			}
			catch(IllegalStateException incompatible) {
				return;
			}
			int input = 0;
			int aligned = 0;
			for(CandidateSelectionReceipt row : selected) {
				int present = (int)row.rule().orderedInputs().stream()
					.filter(CandidateInputState::present).count();
				input += assignment.get(row.rule().parentOccurrence()).execType() == ExecType.FED
					? present : -present;
				if(anchorAligned(analysis, actions, assignment, row))
					aligned++;
			}
			int relocationCount = RelocationSelections.physicalEmissionCount(
				relocation.emittedActions());
			int localCount = LocalMaterializationSelections.physicalEmissionCount(
				analysis, assignment, selected);
			int foutCount = CandidateSelections.foutMaterializationPhysicalEmissionCount(selected);
			int physical = Math.addExact(Math.addExact(relocationCount, localCount), foutCount);
			if(best != null && (input < bestInput
				|| input == bestInput && (aligned < bestAligned
					|| aligned == bestAligned && (physical > bestPhysical
						|| physical == bestPhysical
							&& compareRows(selected, best.candidates()) >= 0))))
				return;
			bestInput = input;
			bestAligned = aligned;
			bestPhysical = physical;
			best = new CandidateSelections.Selection(
				analysis.canonicalCandidateReceipts(selected), relocation.choices(),
				relocation.emittedActions(), selected.stream().mapToInt(row -> (int)row.rule()
					.orderedInputs().stream().filter(CandidateInputState::present).count()).sum(),
				relocationCount, localCount, foutCount);
		}

		private int compareRows(List<CandidateSelectionReceipt> left,
			List<CandidateSelectionReceipt> canonicalRight) {
			Map<CompiledHopKey,CandidateSelectionReceipt> right = new IdentityHashMap<>();
			for(CandidateSelectionReceipt row : canonicalRight)
				right.put(row.rule().parentOccurrence(), row);
			for(int index = 0; index < consumers.size(); index++) {
				int order = left.get(index).compareTo(right.get(consumers.get(index)));
				if(order != 0)
					return order;
			}
			return 0;
		}

		private CandidateSelections.Selection best() {
			if(best == null)
				throw new AssertionError("bounded literal candidate product has no legal assignment");
			return best;
		}
	}

	private static boolean anchorAligned(PlacementAnalysis analysis,
		List<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		CandidateSelectionReceipt row) {
		if(row.emission().emissionState().placementState().execType() != ExecType.FED)
			return true;
		for(int position = 0; position < row.rule().orderedInputs().size(); position++) {
			CandidateInputState input = row.rule().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			var edges = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == row.rule().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				return false;
			PlacementState source = assignment.get(edges.get(0).producer());
			if(source != null
				&& source.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& source.fType() == input.fType())
				continue;
			boolean aligned = actions.stream().anyMatch(action ->
				action.key().materializationFType() == input.fType()
					&& action.key().durableAnchor().fType() == input.fType()
					&& action.key().targetPlacement().equals(
						row.emission().emissionState().placementState())
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == row.rule().parentOccurrence()
							&& obligation.inputPosition() == inputPosition));
			if(!aligned)
				return false;
		}
		return true;
	}

	private static DMLProgram compileProtectedFixture() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-22");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return program;
	}
}
