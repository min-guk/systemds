/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;

/** MinST policy root over the canonical immutable placement analysis. */
public class FederatedPlanMinSTCut extends AFederatedPlanner {
	private final MinStPlacementAdapter adapter = new MinStPlacementAdapter();

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes) {
		Objects.requireNonNull(prog, "prog");
		rewriteProgram(prog, fgraph, fcallSizes, prog.requirePlacementAnalysisAuthority());
	}

	@Override
	public MinStPlacementInput rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, PlacementAnalysis analysis) {
		Objects.requireNonNull(prog, "prog");
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertCanonicalProgramAuthority(prog);
		analysis.assertProgramStructureUnchanged();

		List<CompiledHopKey> scope = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope);
		MinStExactSelection selection = MinStExactSelector.select(facts);
		MinStPlacementInput input = MinStExactPlacementProjector.project(facts, selection);
		adapter.select(analysis, input);
		Map<CompiledHopKey, PlacementState> states = new LinkedHashMap<>();
		for(var node : analysis.graph().decisionNodes()) {
			MinStPlacementInput.OccurrenceReceipt receipt = input.occurrenceReceipts().stream()
				.filter(candidate -> candidate.planningKey().equals(node.key())).findFirst()
				.orElseThrow(() -> new IllegalStateException("MinST selection omitted " + node.key()));
			List<PlacementState> matches = node.legalAlternatives().stream()
				.filter(state -> state.execType() == receipt.execType() && state.output() == receipt.output()).toList();
			if(matches.size() != 1)
				throw new IllegalStateException("MinST selection is not an exact neutral state: " + node.key());
			states.put(node.key(), matches.get(0));
		}
		NormalizedPlannerResult normalized = NormalizedPlannerResults.create(analysis, "MinST", states,
			"cut=" + input.producerReceipt().cutObjectiveBits() + ";source="
				+ input.producerReceipt().sourcePartitionNodeIds());
		input = input.withEmissionReceipt(PlacementEmissionTransaction.emit(prog, normalized,
			PlacementEmissionTransaction.FailureInjector.none()));

		return input;
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		Objects.requireNonNull(function, "function");
		DMLProgram program = Objects.requireNonNull(function.getDMLProg(), "function program");
		rewriteFunctionDynamic(function, funcArgs, program.requirePlacementAnalysisAuthority());
	}

	public MinStPlacementInput rewriteFunctionDynamic(FunctionStatementBlock function,
		LocalVariableMap funcArgs, PlacementAnalysis analysis) {
		Objects.requireNonNull(function, "function");
		Objects.requireNonNull(analysis, "analysis");
		DMLProgram program = Objects.requireNonNull(function.getDMLProg(), "function program");
		return rewriteProgram(program, null, null, analysis);
	}
}
