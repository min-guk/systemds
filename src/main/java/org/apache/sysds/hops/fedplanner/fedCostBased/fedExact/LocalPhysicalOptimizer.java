/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayDeque;
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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

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

	private static final class ValueBoundaryHardClosure {
		private final List<DecisionDomain> domains;
		private final List<int[]> scopes;
		private final List<List<Integer>> incidentFactors;

		ValueBoundaryHardClosure(List<DecisionDomain> domains, List<Factor> hardFactors) {
			this.domains = domains;
			IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
			for(int index = 0; index < domains.size(); index++)
				positions.put(domains.get(index).variable(), index);
			incidentFactors = new ArrayList<>(domains.size());
			for(int index = 0; index < domains.size(); index++)
				incidentFactors.add(new ArrayList<>());
			scopes = new ArrayList<>(hardFactors.size());
			for(int factorIndex = 0; factorIndex < hardFactors.size(); factorIndex++) {
				int[] scope = hardFactors.get(factorIndex).scope().stream()
					.mapToInt(variable -> Objects.requireNonNull(positions.get(variable),
						"LOCAL_VALUE_BOUNDARY_HARD_FACTOR_FOREIGN_VARIABLE")).toArray();
				scopes.add(scope);
				for(int variable : scope)
					incidentFactors.get(variable).add(factorIndex);
			}
		}

		void expand(Set<Integer> variables) {
			ArrayDeque<Integer> frontier = new ArrayDeque<>();
			Set<Integer> expandedBoundaries = new LinkedHashSet<>();
			for(int variable : variables)
				if(isValueBoundary(domains.get(variable).node().kind()))
					frontier.addLast(variable);
			boolean[] includedFactors = new boolean[scopes.size()];
			while(!frontier.isEmpty()) {
				int boundary = frontier.removeFirst();
				if(!expandedBoundaries.add(boundary))
					continue;
				for(int factor : incidentFactors.get(boundary)) {
					if(includedFactors[factor])
						continue;
					includedFactors[factor] = true;
					for(int variable : scopes.get(factor)) {
						variables.add(variable);
						if(isValueBoundary(domains.get(variable).node().kind())
							&& !expandedBoundaries.contains(variable))
							frontier.addLast(variable);
					}
				}
			}
		}
	}

	static Result optimize(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(surface, "surface");
		validateSharedSurface(model, surface);
		List<Variable> variables = model.variables();
		List<Variable> localOrder = producerBeforeConsumerOrder(model);
		ValueBoundaryHardClosure hardClosure =
			new ValueBoundaryHardClosure(model.domains(), model.hardFactors());
		List<List<Variable>> localBlocks =
			localInteractionBlocks(model, localOrder, hardClosure);
		MaterializationConflictBlockProvider materializationBlocks =
			new MaterializationConflictBlockProvider(model, hardClosure);
		IdentityHashMap<Variable,DecisionDomain> domains = new IdentityHashMap<>();
		for(DecisionDomain domain : model.domains())
			domains.put(domain.variable(), domain);

		LocalCategoricalOptimizer.Result local = LocalCategoricalOptimizer.optimize(
			variables, model.hardFactors(), surface.factors(), localOrder, localBlocks,
			materializationBlocks,
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

	private static List<List<Variable>> localInteractionBlocks(ExactPhysicalModel model,
		List<Variable> localOrder,
		ValueBoundaryHardClosure hardClosure) {
		List<DecisionDomain> domains = model.domains();
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < domains.size(); index++)
			positions.put(domains.get(index).variable(), index);
		List<Set<Integer>> blocks = new ArrayList<>();
		// Optimize the physical choices visible at one program operator together:
		// the consumer and all of its direct inputs. This is the bounded HOP-and-
		// children neighborhood promised by the local planner. Do not turn every
		// cost-factor scope into a separate exact subproblem: reusable-transfer
		// factors overlap heavily, and doing so recreates most of global variable
		// elimination through repeated local solves.
		Map<Integer,Set<Integer>> inputsByConsumer = new LinkedHashMap<>();
		for(DecisionEdge edge : decisionEdges(model.analysis())) {
			Integer producer = positions.get(edge.producer());
			Integer consumer = positions.get(edge.consumer());
			if(producer != null && consumer != null && !producer.equals(consumer))
				inputsByConsumer.computeIfAbsent(consumer, ignored -> new LinkedHashSet<>())
					.add(producer);
		}
		for(Map.Entry<Integer,Set<Integer>> entry : inputsByConsumer.entrySet()) {
			Set<Integer> block = new LinkedHashSet<>(entry.getValue());
			block.add(entry.getKey());
			hardClosure.expand(block);
			blocks.add(block);
		}
		// A producer used by multiple consumers is a second bounded interaction
		// pattern. Solving its direct star jointly preserves the reusable-movement
		// decision that independent operator neighborhoods cannot see.
		for(List<Variable> shared : sharedProducerBlocks(model, localOrder, hardClosure))
			blocks.add(shared.stream().map(variable -> Objects.requireNonNull(positions.get(variable),
				"LOCAL_SHARED_BLOCK_FOREIGN_VARIABLE")).collect(
					java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

		IdentityHashMap<Variable,Integer> localRanks = new IdentityHashMap<>();
		for(int index = 0; index < localOrder.size(); index++)
			localRanks.put(localOrder.get(index), index);
		blocks.sort(Comparator
			.comparingInt((Set<Integer> block) -> block.stream()
				.map(index -> localRanks.get(domains.get(index).variable()))
				.min(Integer::compareTo).orElse(Integer.MAX_VALUE))
			.thenComparingInt(Set::size)
			.thenComparing(block -> block.stream().sorted().toList(),
				LocalPhysicalOptimizer::compareIntegerLists));
		return blocks.stream().map(block -> block.stream().sorted()
			.map(index -> domains.get(index).variable()).toList()).toList();
	}

	/**
	 * Builds a bounded neighborhood only for a physical FED/FOUT source that the
	 * selected assignment would actually materialize for a coordinator-local input.
	 * A producer-consumer pair can be trapped behind an intermediate cost barrier when
	 * a selected FOUT chain must change with it. The block therefore contains the
	 * selected downstream FOUT component plus the direct input/consumer fringe of that
	 * component. Traversal stops at non-FOUT decisions, so this remains a physical
	 * conflict region rather than a transitive whole-program component.
	 */
	private static final class MaterializationConflictBlockProvider
		implements LocalCategoricalOptimizer.DeferredBlockProvider {
		private final List<DecisionDomain> domains;
		private final ValueBoundaryHardClosure hardClosure;
		private final Map<Integer,Set<Integer>> inputsByConsumer = new LinkedHashMap<>();
		private final Map<Integer,Set<Integer>> consumersByProducer = new LinkedHashMap<>();
		private final List<IndexedCompiledInput> compiledInputs;
		private final List<IndexedFunctionInput> functionInputs;

		MaterializationConflictBlockProvider(ExactPhysicalModel model,
			ValueBoundaryHardClosure hardClosure) {
			this.domains = model.domains();
			this.hardClosure = hardClosure;
			IdentityHashMap<CompiledHopKey,Integer> positions = decisionPositions(domains);
			for(DecisionEdge edge : decisionEdges(model.analysis())) {
				Integer producer = positions.get(edge.producer());
				Integer consumer = positions.get(edge.consumer());
				if(producer == null || consumer == null || producer.equals(consumer))
					continue;
				inputsByConsumer.computeIfAbsent(consumer, ignored -> new LinkedHashSet<>())
					.add(producer);
				consumersByProducer.computeIfAbsent(producer, ignored -> new LinkedHashSet<>())
					.add(consumer);
			}
			List<IndexedCompiledInput> indexedCompiled = new ArrayList<>();
			for(CompiledInputEdgeFact edge :
				model.analysis().compiledInputEdgesInCanonicalOrder()) {
				Integer producer = positions.get(edge.producer());
				Integer consumer = positions.get(edge.consumer());
				if(producer != null && consumer != null && !producer.equals(consumer)
					&& !model.analysis().isDmlFunctionCallBoundary(edge.consumer()))
					indexedCompiled.add(new IndexedCompiledInput(
						producer, consumer, edge.inputPosition()));
			}
			compiledInputs = List.copyOf(indexedCompiled);
			List<IndexedFunctionInput> indexedFunctions = new ArrayList<>();
			model.analysis().logicalFunctionInputsInCanonicalOrder().forEach(edge -> {
				Integer producer = positions.get(edge.sourceArgument());
				Integer formal = positions.get(edge.targetRead());
				if(producer != null && formal != null && !producer.equals(formal))
					indexedFunctions.add(new IndexedFunctionInput(producer, formal));
			});
			functionInputs = List.copyOf(indexedFunctions);
		}

		@Override
		public List<List<Variable>> localBlocks(List<Integer> assignment) {
			if(assignment.size() != domains.size())
				throw new IllegalArgumentException(
					"LOCAL_MATERIALIZATION_ASSIGNMENT_SIZE_MISMATCH");
			Set<Integer> selectedSources = new LinkedHashSet<>();
			for(IndexedCompiledInput edge : compiledInputs) {
				Alternative selectedProducer = selected(
					domains.get(edge.producer()), assignment.get(edge.producer()));
				Alternative selectedConsumer = selected(
					domains.get(edge.consumer()), assignment.get(edge.consumer()));
				if(isPhysicalFout(selectedProducer)
					&& requiresLocalInput(selectedConsumer, edge.inputPosition()))
					selectedSources.add(edge.producer());
			}
			for(IndexedFunctionInput edge : functionInputs) {
				Alternative selectedProducer = selected(
					domains.get(edge.producer()), assignment.get(edge.producer()));
				Alternative selectedFormal = selected(
					domains.get(edge.formal()), assignment.get(edge.formal()));
				if(isPhysicalFout(selectedProducer)
					&& selectedFormal.state().execType() == ExecType.CP
					&& selectedFormal.state().output() == FederatedOutput.LOUT)
					selectedSources.add(edge.producer());
			}

			List<List<Variable>> blocks = new ArrayList<>(selectedSources.size());
			for(int source : selectedSources) {
				Set<Integer> block = materializationConflictNeighborhood(source,
					domains, assignment, inputsByConsumer, consumersByProducer);
				hardClosure.expand(block);
				blocks.add(block.stream().sorted()
					.map(index -> domains.get(index).variable()).toList());
			}
			return blocks;
		}
	}

	private record IndexedCompiledInput(int producer, int consumer, int inputPosition) { }
	private record IndexedFunctionInput(int producer, int formal) { }

	private static Set<Integer> materializationConflictNeighborhood(int source,
		List<DecisionDomain> domains, List<Integer> assignment,
		Map<Integer,Set<Integer>> inputsByConsumer,
		Map<Integer,Set<Integer>> consumersByProducer) {
		Set<Integer> foutComponent = new LinkedHashSet<>();
		ArrayDeque<Integer> pending = new ArrayDeque<>();
		foutComponent.add(source);
		pending.addLast(source);
		while(!pending.isEmpty()) {
			int current = pending.removeFirst();
			for(int consumer : consumersByProducer.getOrDefault(current, Set.of()))
				if(isPhysicalFout(selected(domains.get(consumer), assignment.get(consumer)))
					&& foutComponent.add(consumer))
					pending.addLast(consumer);
		}
		Set<Integer> block = new LinkedHashSet<>();
		for(int member : foutComponent)
			block.addAll(operatorNeighborhood(member, inputsByConsumer, consumersByProducer));
		return block;
	}

	private static Set<Integer> operatorNeighborhood(int source,
		Map<Integer,Set<Integer>> inputsByConsumer,
		Map<Integer,Set<Integer>> consumersByProducer) {
		Set<Integer> block = new LinkedHashSet<>();
		block.addAll(inputsByConsumer.getOrDefault(source, Set.of()));
		block.add(source);
		block.addAll(consumersByProducer.getOrDefault(source, Set.of()));
		return block;
	}

	private static Alternative selected(DecisionDomain domain, int value) {
		if(value < 0 || value >= domain.alternatives().size())
			throw new IllegalArgumentException("LOCAL_MATERIALIZATION_ASSIGNMENT_VALUE_INVALID");
		return domain.alternatives().get(value);
	}

	private static boolean isPhysicalFout(Alternative alternative) {
		return alternative.state().execType() == ExecType.FED
			&& alternative.state().output() == FederatedOutput.FOUT
			&& alternative.derivedFoutAction() == null;
	}

	private static boolean requiresLocalInput(Alternative alternative, int inputPosition) {
		if(alternative.state().execType() == ExecType.CP)
			return alternative.state().output() == FederatedOutput.LOUT;
		return alternative.state().execType() == ExecType.FED && inputPosition >= 0
			&& inputPosition < alternative.orderedInputs().size()
			&& !alternative.orderedInputs().get(inputPosition).present();
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

	private static List<List<Variable>> sharedProducerBlocks(ExactPhysicalModel model,
		List<Variable> localOrder, ValueBoundaryHardClosure hardClosure) {
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
		List<SharedRegion> regions = new ArrayList<>();
		for(Map.Entry<Integer,Set<Integer>> entry : consumersByProducer.entrySet()) {
			if(entry.getValue().size() < 2)
				continue;
			Set<Integer> block = new LinkedHashSet<>();
			block.add(entry.getKey());
			block.addAll(entry.getValue());
			regions.add(new SharedRegion(new LinkedHashSet<>(Set.of(entry.getKey())),
				new LinkedHashSet<>(entry.getValue()), block));
		}
		mergeCommonConsumerRegions(regions);
		mergeValueBoundaryRegions(regions, domains);
		for(SharedRegion region : regions)
			hardClosure.expand(region.variables());
		// Ordinary regions remain one shared producer plus its direct consumers. A
		// transient/function boundary region additionally closes only through boundary
		// hard factors, so one logical value and its consumer stars can move together.
		// Non-boundary operations are terminal: the closure cannot absorb the rest of the
		// program DAG. Actual infeasibility remains the responsibility of conflict repair.
		IdentityHashMap<Variable,Integer> localRanks = new IdentityHashMap<>();
		for(int index = 0; index < localOrder.size(); index++)
			localRanks.put(localOrder.get(index), index);
		regions.sort(Comparator
			.comparingInt((SharedRegion region) -> region.producers().stream()
				.map(index -> localRanks.get(domains.get(index).variable()))
				.min(Integer::compareTo).orElse(Integer.MAX_VALUE))
			.thenComparing(region -> region.variables().stream().sorted().toList(),
				LocalPhysicalOptimizer::compareIntegerLists));
		List<List<Variable>> result = new ArrayList<>(regions.size());
		for(SharedRegion region : regions)
			result.add(region.variables().stream().sorted()
				.map(index -> domains.get(index).variable()).toList());
		return List.copyOf(result);
	}

	private static void mergeValueBoundaryRegions(List<SharedRegion> regions,
		List<DecisionDomain> domains) {
		boolean changed;
		do {
			changed = false;
			outer:
			for(int left = 0; left < regions.size(); left++)
				for(int right = left + 1; right < regions.size(); right++) {
					SharedRegion first = regions.get(left);
					SharedRegion second = regions.get(right);
					Set<Integer> forward = new LinkedHashSet<>(first.consumers());
					forward.retainAll(second.producers());
					Set<Integer> reverse = new LinkedHashSet<>(second.consumers());
					reverse.retainAll(first.producers());
					boolean valueBoundary = java.util.stream.Stream.concat(
						forward.stream(), reverse.stream()).anyMatch(index ->
							isValueBoundary(domains.get(index).node().kind()));
					if(!valueBoundary)
						continue;
					first.producers().addAll(second.producers());
					first.consumers().addAll(second.consumers());
					first.variables().addAll(second.variables());
					regions.remove(right);
					changed = true;
					break outer;
				}
		}
		while(changed);
	}

	private static boolean isValueBoundary(NodeKind kind) {
		return kind == NodeKind.TRANSIENT_READ || kind == NodeKind.TRANSIENT_WRITE
			|| kind == NodeKind.BRANCH_JOIN || kind == NodeKind.LOOP_PHI
			|| kind == NodeKind.FUNCTION_INPUT || kind == NodeKind.FUNCTION_OUTPUT;
	}

	private static void mergeCommonConsumerRegions(List<SharedRegion> regions) {
		boolean changed;
		do {
			changed = false;
			outer:
			for(int left = 0; left < regions.size(); left++)
				for(int right = left + 1; right < regions.size(); right++)
					if(!Collections.disjoint(regions.get(left).consumers(),
						regions.get(right).consumers())) {
						SharedRegion target = regions.get(left);
						SharedRegion source = regions.remove(right);
						target.producers().addAll(source.producers());
						target.consumers().addAll(source.consumers());
						target.variables().addAll(source.variables());
						changed = true;
						break outer;
					}
		}
		while(changed);
	}

	private static int compareIntegerLists(List<Integer> leftValues, List<Integer> rightValues) {
		for(int index = 0; index < Math.min(leftValues.size(), rightValues.size()); index++) {
			int comparison = Integer.compare(leftValues.get(index), rightValues.get(index));
			if(comparison != 0)
				return comparison;
		}
		return Integer.compare(leftValues.size(), rightValues.size());
	}

	private record SharedRegion(Set<Integer> producers, Set<Integer> consumers,
		Set<Integer> variables) { }

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
