/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Connects the shared physical factor model to the local-conflict optimizer. */
final class LocalPhysicalOptimizer {
	record Result(ExactPhysicalOptimizer.Result physicalResult,
		LocalCategoricalOptimizer.Statistics localStatistics) {
		Result {
			Objects.requireNonNull(physicalResult, "physicalResult");
			Objects.requireNonNull(localStatistics, "localStatistics");
		}
	}

	private LocalPhysicalOptimizer() { }

	static Result optimize(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(surface, "surface");
		validateSharedSurface(model, surface);
		List<Variable> variables = model.variables();
		List<Variable> localOrder = producerBeforeConsumerOrder(model);
		List<List<Variable>> sharedBlocks = sharedProducerBlocks(model);
		IdentityHashMap<Variable,DecisionDomain> domains = new IdentityHashMap<>();
		for(DecisionDomain domain : model.domains())
			domains.put(domain.variable(), domain);

		LocalCategoricalOptimizer.Result local = LocalCategoricalOptimizer.optimize(
			variables, model.hardFactors(), surface.factors(), localOrder, sharedBlocks,
			(variable, value) -> {
				DecisionDomain domain = domains.get(variable);
				if(domain == null)
					throw new IllegalArgumentException("LOCAL_PHYSICAL_STATE_DOMAIN_MISSING");
				// Alternative.signature is the complete future-observable state: it includes
				// placement plus exact candidate, input, and movement authority.
				return domain.alternatives().get(value).signature();
			});

		long canonicalBits = surface.evaluateCanonical(local.assignmentInVariableOrder());
		double canonicalObjective = Double.longBitsToDouble(canonicalBits);
		if(Double.doubleToRawLongBits(local.objective()) != canonicalBits)
			throw new IllegalArgumentException("LOCAL_PHYSICAL_CANONICAL_OBJECTIVE_MISMATCH|local="
				+ local.objective() + "|canonical=" + canonicalObjective);
		LocalCategoricalOptimizer.Statistics statistics = local.statistics();
		ExactCategoricalSolver.Statistics solverStatistics = new ExactCategoricalSolver.Statistics(
			localOrder.stream().map(Variable::key).toList(),
			Math.max(0, statistics.maximumBlockVariables() - 1),
			statistics.maximumBlockAssignments(), 0L,
			statistics.maximumBlockAssignments(), statistics.blockAssignments());
		ExactCategoricalSolver.Result solverResult = new ExactCategoricalSolver.Result(
			canonicalObjective, local.assignmentInVariableOrder(), solverStatistics);
		ExactPhysicalOptimizer.Result physical = new ExactPhysicalOptimizer.Result(
			solverResult, canonicalBits, surface.contributionFingerprint());
		return new Result(physical, statistics);
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
		// Recursive function/transient structures can contain a logical cycle. Keep
		// the acyclic prefix and append its residual decisions in canonical order.
		if(order.size() != domains.size()) {
			Set<Integer> selected = new LinkedHashSet<>(order);
			for(int index = 0; index < domains.size(); index++)
				if(!selected.contains(index))
					order.add(index);
		}
		return order.stream().map(index -> domains.get(index).variable()).toList();
	}

	private static List<List<Variable>> sharedProducerBlocks(ExactPhysicalModel model) {
		List<DecisionDomain> domains = model.domains();
		IdentityHashMap<CompiledHopKey,Integer> positions = decisionPositions(domains);
		Map<Integer,Set<Integer>> consumersByProducer = new LinkedHashMap<>();
		for(DecisionEdge edge : decisionEdges(model.analysis())) {
			Integer producer = positions.get(edge.producer());
			Integer consumer = positions.get(edge.consumer());
			if(producer != null && consumer != null && !producer.equals(consumer))
				consumersByProducer.computeIfAbsent(producer, ignored -> new LinkedHashSet<>())
					.add(consumer);
		}
		IdentityHashMap<Variable,Integer> variablePositions = new IdentityHashMap<>();
		for(int index = 0; index < domains.size(); index++)
			variablePositions.put(domains.get(index).variable(), index);
		List<Set<Integer>> blocks = new ArrayList<>();
		for(Map.Entry<Integer,Set<Integer>> entry : consumersByProducer.entrySet()) {
			if(entry.getValue().size() < 2)
				continue;
			Set<Integer> block = new LinkedHashSet<>();
			block.add(entry.getKey());
			block.addAll(entry.getValue());
			// Include the producer's immediate feasibility response region. This covers
			// value-version siblings and transient/function equality without reconstructing
			// a second whole-program representation.
			for(Factor factor : model.hardFactors()) {
				List<Integer> scope = factor.scope().stream().map(variablePositions::get)
					.filter(Objects::nonNull).toList();
				if(scope.contains(entry.getKey()))
					block.addAll(scope);
			}
			blocks.add(block);
		}
		mergeOverlapping(blocks);
		blocks.sort(LocalPhysicalOptimizer::compareBlocks);
		List<List<Variable>> result = new ArrayList<>(blocks.size());
		for(Set<Integer> block : blocks)
			result.add(block.stream().sorted().map(index -> domains.get(index).variable()).toList());
		return List.copyOf(result);
	}

	private static void mergeOverlapping(List<Set<Integer>> blocks) {
		boolean changed;
		do {
			changed = false;
			outer:
			for(int left = 0; left < blocks.size(); left++)
				for(int right = left + 1; right < blocks.size(); right++)
					if(!Collections.disjoint(blocks.get(left), blocks.get(right))) {
						blocks.get(left).addAll(blocks.remove(right));
						changed = true;
						break outer;
					}
		}
		while(changed);
	}

	private static int compareBlocks(Set<Integer> left, Set<Integer> right) {
		var leftValues = left.stream().sorted().toList();
		var rightValues = right.stream().sorted().toList();
		for(int index = 0; index < Math.min(leftValues.size(), rightValues.size()); index++) {
			int comparison = Integer.compare(leftValues.get(index), rightValues.get(index));
			if(comparison != 0)
				return comparison;
		}
		return Integer.compare(leftValues.size(), rightValues.size());
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
		return edges.stream().distinct().sorted(Comparator
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
