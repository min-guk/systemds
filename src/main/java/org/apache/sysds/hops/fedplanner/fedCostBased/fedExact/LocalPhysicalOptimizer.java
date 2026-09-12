/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Connects the shared physical factor model to the incremental Regional optimizer. */
final class LocalPhysicalOptimizer {
	record Result(ExactPhysicalOptimizer.Result physicalResult,
		LocalCategoricalOptimizer.Statistics localStatistics) {
		Result {
			Objects.requireNonNull(physicalResult, "physicalResult");
			Objects.requireNonNull(localStatistics, "localStatistics");
		}
	}
	private record Seed(LocalCategoricalOptimizer.Result local, List<Variable> order) { }

	private LocalPhysicalOptimizer() { }

	static String incrementalCheckpointTrace(IncrementalRegionalOptimizer.Checkpoint cp,
		long plannerElapsedNanos) {
		return new StringBuilder(384)
			.append("phase=").append(cp.phase())
			.append(" merges=").append(cp.merges())
			.append(" clusters=").append(cp.activeClusters())
			.append(" lower=").append(cp.lower())
			.append(" upper=").append(cp.upper())
			.append(" relativeGap=").append(cp.relativeGap())
			.append(" elapsedNanos=").append(cp.elapsedNanos())
			.append(" dpNanos=").append(cp.dpNanos())
			.append(" scoringNanos=").append(cp.scoringNanos())
			.append(" validationNanos=").append(cp.validationNanos())
			.append(" assignments=").append(cp.assignments())
			.append(" retainedSlots=").append(cp.retainedSlots())
			.append(" improvements=").append(cp.improvements())
			.append(" resourceRejected=").append(cp.resourceRejected())
			.append(" internalDecisions=").append(cp.internalDecisions())
			.append(" plannerElapsedNanos=").append(plannerElapsedNanos)
			.append(" separateGlobalCalls=0 scope=encoded-model")
			.toString();
	}

	static Result optimize(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) {
		IncrementalRegionalOptimizer.validateConfiguration();
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(surface, "surface");
		validateSharedSurface(model, surface);

		List<Factor> hardFactors = new ArrayList<>(model.hardFactors());
		RegionalSearchProblem problem = RegionalSearchProblem.physical(model, surface, null);
		ExactCategoricalSolver.Limits limits = ExactPhysicalOptimizer.PRODUCTION_LIMITS;
		SharedRegionalPreparation shared = new SharedRegionalPreparation(problem, limits,
			LocalCategoricalOptimizer.configuredCompaction());
		Seed seed = regionalSeed(model, surface, hardFactors, shared);
		LocalCategoricalOptimizer.Result local = seed.local();

		long canonicalBits = surface.evaluateCanonical(local.assignmentInVariableOrder());
		double canonicalObjective = Double.longBitsToDouble(canonicalBits);
		if(Double.doubleToRawLongBits(local.objective()) != canonicalBits)
			throw new IllegalArgumentException("LOCAL_PHYSICAL_CANONICAL_OBJECTIVE_MISMATCH|local="
				+ local.objective() + "|canonical=" + canonicalObjective);
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("Planner-Stage", String.format(java.util.Locale.ROOT,
				"stage=REGIONAL_BOOTSTRAP_READY plannerElapsedNanos=%d objective=%.17g objectiveBits=%s "
					+ "costFingerprint=%s analysis=%s scope=encoded-model clock=compile-fedplanner",
				FederatedPlannerTrace.plannerElapsedNanos(), canonicalObjective,
				Long.toUnsignedString(canonicalBits), surface.contributionFingerprint(),
				model.analysis().analysisFingerprint()));

		var root = problem.reducedRoot(limits);
		var incremental = IncrementalRegionalOptimizer.optimize(problem, root,
			local.assignmentInVariableOrder(), limits, IncrementalRegionalOptimizer.Options.configured(), cp -> {
				if(FederatedPlannerTrace.isEnabled())
					FederatedPlannerTrace.logGlobal("DP-IncrementalRegional",
						incrementalCheckpointTrace(cp, FederatedPlannerTrace.plannerElapsedNanos()));
			});
		List<Integer> selectedAssignment = incremental.assignment();
		canonicalObjective = incremental.upper();
		canonicalBits = Double.doubleToRawLongBits(canonicalObjective);

		LocalCategoricalOptimizer.Statistics statistics = local.statistics();
		ExactCategoricalSolver.Statistics solverStatistics = new ExactCategoricalSolver.Statistics(
			seed.order().stream().map(Variable::key).toList(),
			Math.max(0, statistics.maximumBlockVariables() - 1),
			statistics.maximumBlockAssignments(), 0L,
			statistics.maximumBlockAssignments(), statistics.blockAssignments());
		ExactCategoricalSolver.Result solverResult = new ExactCategoricalSolver.Result(
			canonicalObjective, selectedAssignment, solverStatistics);
		ExactPhysicalOptimizer.Result physical = new ExactPhysicalOptimizer.Result(
			solverResult, canonicalBits, surface.contributionFingerprint());
		return new Result(physical, statistics);
	}

	private static Seed regionalSeed(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, List<Factor> hardFactors,
		SharedRegionalPreparation shared) {
		long seedStarted = System.nanoTime();
		boolean regionalCompact = LocalCategoricalOptimizer.configuredCompaction();
		List<Variable> localOrder = producerBeforeConsumerOrder(model);
		IdentityHashMap<Variable,DecisionDomain> domains = new IdentityHashMap<>();
		for(DecisionDomain domain : model.domains())
			domains.put(domain.variable(), domain);
		LocalCategoricalOptimizer.Result local = LocalCategoricalOptimizer.optimize(
			model.variables(), hardFactors, surface.factors(), localOrder, List.of(), ignored -> List.of(),
			(variable, value) -> {
				DecisionDomain domain = domains.get(variable);
				if(domain == null)
					throw new IllegalArgumentException("LOCAL_PHYSICAL_STATE_DOMAIN_MISSING");
				return domain.alternatives().get(value).signature();
			}, 0, regionalCompact, shared);
		shared.trace();
		long seedNanos = System.nanoTime() - seedStarted;
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("DP-RegionalSeedTiming", "seedNanos=" + seedNanos
				+ " improveNeighborhoods=false regionalCompact=" + regionalCompact
				+ " revisitPasses=0 localBlocks=" + local.statistics().localBlocks()
				+ " localOptimizationNanos=" + local.statistics().totalOptimizationNanos()
				+ " exactBlockPreparationNanos=" + local.statistics().exactBlockPreparationNanos()
				+ " exactBlockSolveNanos=" + local.statistics().exactBlockSolveNanos()
				+ " objective=" + local.objective());
		return new Seed(local, localOrder);
	}

	private static void validateSharedSurface(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) {
		if(surface.factors().isEmpty())
			throw new IllegalArgumentException(model.missingCostSurface());
		if(surface.owner() != model.analysis()
			|| !surface.ownerFingerprint().equals(model.analysis().analysisFingerprint()))
			throw new IllegalArgumentException("LOCAL_PHYSICAL_COST_OWNER_MISMATCH");
		List<Variable> modelVariables = model.variables();
		if(surface.variables().size() != modelVariables.size())
			throw new IllegalArgumentException("LOCAL_PHYSICAL_COST_VARIABLE_CARDINALITY_MISMATCH");
		for(int index = 0; index < modelVariables.size(); index++)
			if(surface.variables().get(index) != modelVariables.get(index))
				throw new IllegalArgumentException("LOCAL_PHYSICAL_COST_VARIABLE_IDENTITY_MISMATCH");
	}

	private static List<Variable> producerBeforeConsumerOrder(ExactPhysicalModel model) {
		List<DecisionDomain> domains = model.domains();
		IdentityHashMap<CompiledHopKey,Integer> positions = decisionPositions(domains);
		List<Set<Integer>> outgoing = new ArrayList<>(domains.size());
		int[] indegree = new int[domains.size()];
		for(int index = 0; index < domains.size(); index++)
			outgoing.add(new LinkedHashSet<>());
		for(DecisionEdge edge : decisionEdges(model.analysis())) {
			Integer producer = positions.get(edge.producer());
			Integer consumer = positions.get(edge.consumer());
			if(producer == null || consumer == null || producer.equals(consumer))
				continue;
			if(outgoing.get(producer).add(consumer))
				indegree[consumer]++;
		}
		PriorityQueue<Integer> ready = new PriorityQueue<>();
		for(int index = 0; index < indegree.length; index++)
			if(indegree[index] == 0)
				ready.add(index);
		List<Integer> order = new ArrayList<>(domains.size());
		while(!ready.isEmpty()) {
			int current = ready.remove();
			order.add(current);
			for(int consumer : outgoing.get(current))
				if(--indegree[consumer] == 0)
					ready.add(consumer);
		}
		if(order.size() != domains.size()) {
			Set<Integer> selected = new LinkedHashSet<>(order);
			for(int index = 0; index < domains.size(); index++)
				if(!selected.contains(index))
					order.add(index);
		}
		return order.stream().map(index -> domains.get(index).variable()).toList();
	}

	private static IdentityHashMap<CompiledHopKey,Integer> decisionPositions(
		List<DecisionDomain> domains) {
		IdentityHashMap<CompiledHopKey,Integer> result = new IdentityHashMap<>();
		for(int index = 0; index < domains.size(); index++)
			result.put(domains.get(index).node().key(), index);
		return result;
	}

	private static List<DecisionEdge> decisionEdges(PlacementAnalysis analysis) {
		List<DecisionEdge> edges = new ArrayList<>();
		analysis.compiledInputEdgesInCanonicalOrder().forEach(edge ->
			edges.add(new DecisionEdge(edge.producer(), edge.consumer())));
		analysis.logicalTransientInputsInCanonicalOrder().forEach(edge ->
			edges.add(new DecisionEdge(edge.sourceWrite(), edge.targetRead())));
		analysis.logicalFunctionInputsInCanonicalOrder().forEach(edge ->
			edges.add(new DecisionEdge(edge.sourceArgument(), edge.targetRead())));
		return edges.stream().distinct().sorted(java.util.Comparator
			.comparing((DecisionEdge edge) -> edge.producer().normalizedSignature())
			.thenComparing(edge -> edge.consumer().normalizedSignature())).toList();
	}

	private record DecisionEdge(CompiledHopKey producer, CompiledHopKey consumer) {
		DecisionEdge {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(consumer, "consumer");
		}
	}
}
