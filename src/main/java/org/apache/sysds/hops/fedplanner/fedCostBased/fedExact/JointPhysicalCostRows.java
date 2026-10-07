/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;

/** Physical cost projections for selected value-map relations, independent of their legality factors. */
final class JointPhysicalCostRows {
	record Value(DurableAnchorKey pool, Map<CompiledHopKey,CompiledHopKey> sources,
		Set<CompiledHopKey> activations) { }
	record Row(List<Value> inputs) {
		Set<CompiledHopKey> activations() {
			Set<CompiledHopKey> result = new LinkedHashSet<>();
			inputs.stream().filter(java.util.Objects::nonNull).forEach(value -> result.addAll(value.activations()));
			return result;
		}
	}
	/** Audit-only enumeration work; neither count is a planner policy input. */
	record ProductWork(long visitedValues, long emittedRows) { }
	private static final class MutableProductWork {
		long visitedValues;
		long emittedRows;
	}

	private final PlacementAnalysis analysis;
	private final Map<CompiledHopKey,JointValueMapRelations.Relation> relations = new IdentityHashMap<>();

	JointPhysicalCostRows(PlacementAnalysis analysis) {
		this.analysis = analysis;
		for(var relation : JointValueMapRelations.from(analysis))
			relations.put(relation.consumer(), relation);
	}

	static boolean dynamic(ExactPhysicalModel.Alternative alternative) {
		return alternative.realization() != null
			&& (alternative.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP
				|| alternative.supportClause().inputBindings().stream().anyMatch(binding ->
					binding.source().realization().layoutKind() == PlacementLayoutKind.VALUE_MAP));
	}

	List<CompiledHopKey> dependencies(ExactPhysicalModel.DecisionDomain owner,
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains) {
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		collectDependencies(owner.node().key(), domains, result);
		return result.stream().sorted().toList();
	}

	private void collectDependencies(CompiledHopKey key,
		Map<CompiledHopKey,ExactPhysicalModel.DecisionDomain> domains, Set<CompiledHopKey> result) {
		if(!result.add(key)) return;
		for(var alternative : domains.get(key).alternatives()) {
			if(!dynamic(alternative)) continue;
			for(var binding : alternative.supportClause().inputBindings())
				if(binding.source().realization().layoutKind() == PlacementLayoutKind.VALUE_MAP)
					collectDependencies(binding.source().rule().parentOccurrence(), domains, result);
		}
	}

	List<Row> executionRows(ExactPhysicalModel.Alternative owner,
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected) {
		return executionRows(owner, selected, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	List<Row> valueRows(ExactPhysicalModel.Alternative owner,
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected) {
		var rule = owner.captured() ? owner.candidateRule() : owner.executionRule();
		if(rule == null || owner.realization() == null) return List.of();
		return values(CandidateRealizationReference.of(rule.key(), owner.realization()), selected,
			Collections.newSetFromMap(new IdentityHashMap<>())).stream()
			.map(value -> new Row(List.of(value))).toList();
	}

	/**
	 * A bounded superset used only to prove an execution price independent of map
	 * selection. Failure to prove constancy leaves the relational cost unchanged;
	 * it never removes a physical candidate or a reachable row.
	 */
	List<Row> possibleExecutionRows(ExactPhysicalModel.Alternative owner) {
		if(owner.supportClause() == null) return List.of();
		List<List<Value>> inputs = new ArrayList<>();
		int combinations = 1;
		for(int position = 0; position < analysis.hop(owner.decision()).orElseThrow().getInput().size(); position++) {
			if(position >= owner.orderedInputs().size() || !owner.orderedInputs().get(position).present()) {
				inputs.add(Collections.singletonList(null));
				continue;
			}
			int inputPosition = position;
			var binding = owner.supportClause().inputBindings().stream().filter(input ->
				input.inputPosition() == inputPosition
					&& input.kind() != CandidateInputBindingKind.LOGICAL_TRANSIENT).findFirst().orElse(null);
			if(binding == null) return List.of();
			Set<DurableAnchorKey> pools = new LinkedHashSet<>();
			if(binding.relocationAction() != null)
				pools.add(binding.relocationAction().durableAnchor());
			else {
				if(!collectExactMapChoices(binding.source(), new java.util.HashSet<>(),
					new java.util.HashSet<>(), pools))
					return List.of();
			}
			// Value/alias IDs do not change a layout's price. Preserve every range
			// dimension and endpoint while quotienting duplicate physical geometry.
			pools = pools.stream().map(pool -> new DurableAnchorKey("joint-cost-geometry", pool.fType(),
				pool.partitions().stream().map(partition ->
					new org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition(
						org.apache.sysds.runtime.controlprogram.federated.FederationUtils
							.canonicalFederatedWorkerAddress(partition.workerId()), partition.begin(), partition.end()))
					.toList())).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			if(pools.isEmpty() || pools.size() > 128 / combinations) return List.of();
			combinations *= pools.size();
			inputs.add(pools.stream().map(pool -> new Value(pool, Map.of(), Set.of())).toList());
		}
		List<Row> result = new ArrayList<>();
		if(relations.containsKey(owner.decision()) && dynamic(owner)) {
			// The joint hard factor proves that every FED input used by this kernel
			// has an aligned effective pool (after explicit relocations). Cartesian
			// cross-pool rows cannot be selected and cannot disprove cost invariance.
			// This filters a cost-proof superset only; no candidate is removed.
			alignedProduct(inputs, 0, new ArrayList<>(), result, null);
		}
		else product(inputs, 0, new ArrayList<>(), result);
		return List.copyOf(result);
	}

	private boolean collectExactMapChoices(CandidateRealizationReference reference,
		Set<CandidateRealizationReference> active, Set<CandidateRealizationReference> completed,
		Set<DurableAnchorKey> pools) {
		var source = analysis.requireExactCandidateRealization(reference);
		// Resolve every edge before either skip so a repeated or cyclic foreign
		// reference cannot borrow an earlier analysis-owned realization.
		if(active.contains(reference) || completed.contains(reference))
			return true; // Anchors must still be found outside the cycle.
		active.add(reference);
		try {
			for(var clause : source.supportClauses()) {
				if(source.key().layoutKind() != PlacementLayoutKind.VALUE_MAP) {
					var pool = source.provenWorkerPool(clause);
					if(pool == null || !source.nativeWorkerPoolLayoutExact(clause)) return false;
					pools.add(pool);
				}
				else {
					if(clause.inputBindings().isEmpty()) return false;
					for(var binding : clause.inputBindings()) {
						if(binding.relocationAction() != null)
							pools.add(binding.relocationAction().durableAnchor());
						else if(!collectExactMapChoices(binding.source(), active, completed, pools))
							return false;
					}
				}
			}
			completed.add(reference);
			return true;
		}
		finally { active.remove(reference); }
	}

	/** All proven maps, used only for a conservative bound when a cost scope has no reader choices. */
	static Set<DurableAnchorKey> possiblePools(PlacementAnalysis analysis,
		PlacementAnalysis.CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause clause) {
		Set<DurableAnchorKey> result = new LinkedHashSet<>();
		possiblePools(analysis, realization, clause, new java.util.HashSet<>(),
			new java.util.HashSet<>(), result);
		return Set.copyOf(result);
	}

	private static void possiblePools(PlacementAnalysis analysis,
		PlacementAnalysis.CandidateEmissionRealization realization,
		PlacementAnalysis.CandidateRealizationSupportClause clause,
		Set<CandidateRealizationReference> visiting,
		Set<CandidateRealizationReference> completed, Set<DurableAnchorKey> result) {
		DurableAnchorKey pool = realization.provenWorkerPool(clause);
		if(pool != null) {
			result.add(pool);
			return;
		}
		for(var binding : clause.inputBindings()) {
			if(binding.relocationAction() != null) {
				result.add(binding.relocationAction().durableAnchor());
				continue;
			}
			var source = analysis.requireExactCandidateRealization(binding.source());
			// Authority belongs to the exact edge, including cycle and completed-DAG
			// skips. Only fully expanded references enter the query-local completion set.
			if(visiting.contains(binding.source()) || completed.contains(binding.source()))
				continue;
			visiting.add(binding.source());
			try {
				for(var support : source.supportClauses())
					possiblePools(analysis, source, support, visiting, completed, result);
				completed.add(binding.source());
			}
			finally { visiting.remove(binding.source()); }
		}
	}

	private List<Row> executionRows(ExactPhysicalModel.Alternative owner,
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected, Set<CompiledHopKey> visiting) {
		if(owner.supportClause() == null) return List.of();
		var relation = relations.get(owner.decision());
		if(relation != null && dynamic(owner)
			&& owner.orderedInputs().stream().allMatch(PlacementAnalysis.CandidateInputState::present)
			&& owner.supportClause().inputBindings().stream()
				.allMatch(binding -> binding.kind() == CandidateInputBindingKind.DIRECT))
			return correlatedRows(relation, selected);
		List<List<Value>> inputs = new ArrayList<>();
		for(int position = 0; position < analysis.hop(owner.decision()).orElseThrow().getInput().size(); position++) {
			if(position >= owner.orderedInputs().size() || !owner.orderedInputs().get(position).present()) {
				inputs.add(Collections.singletonList(null));
				continue;
			}
			int inputPosition = position;
			var binding = owner.supportClause().inputBindings().stream()
				.filter(input -> input.inputPosition() == inputPosition
					&& input.kind() != CandidateInputBindingKind.LOGICAL_TRANSIENT).findFirst().orElse(null);
			if(binding == null) return List.of();
			List<Value> choices = binding.relocationAction() == null
				? values(binding.source(), selected, visiting)
				: List.of(new Value(binding.relocationAction().durableAnchor(), Map.of(), Set.of()));
			if(choices.isEmpty()) return List.of();
			inputs.add(choices);
		}
		List<Row> rows = new ArrayList<>();
		if(relation == null) product(inputs, 0, new ArrayList<>(), rows);
		else enumerateMatchingProduct(inputs, relation, rows);
		return List.copyOf(rows);
	}

	private List<Row> correlatedRows(JointValueMapRelations.Relation relation,
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected) {
		Map<CompiledHopKey,CandidateSelectionReceipt> receipts = new IdentityHashMap<>();
		for(var alternative : selected.values()) {
			var rule = alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
			var emission = alternative.captured() ? alternative.candidateEmission() : alternative.executionEmission();
			if(rule != null && emission != null && alternative.realization() != null)
				receipts.put(alternative.decision(), analysis.canonicalCandidateReceipt(rule.key(), emission,
					alternative.realization(), alternative.supportClause()));
		}
		// The legality projection owns alias/conversion provenance and correlations
		// through nested joins and call bindings. Cost must use those same rows.
		return JointValueMapRelations.groundedRows(analysis, relation, receipts, 0).stream()
			.map(row -> new Row(row.inputs().stream().map(input -> new Value(input.pool(),
				Map.of(input.reader(), input.source()), Set.of(input.source()))).toList())).toList();
	}

	private List<Value> values(CandidateRealizationReference reference,
		Map<CompiledHopKey,ExactPhysicalModel.Alternative> selected, Set<CompiledHopKey> visiting) {
		var realization = analysis.requireExactCandidateRealization(reference);
		if(realization.key().layoutKind() != PlacementLayoutKind.VALUE_MAP
			&& realization.supportClauses().stream().allMatch(realization::nativeWorkerPoolLayoutExact)) {
			var pool = realization.provenWorkerPool(realization.supportClauses().get(0));
			return pool == null ? List.of() : List.of(new Value(pool, Map.of(), Set.of()));
		}
		if(realization.key().layoutKind() != PlacementLayoutKind.VALUE_MAP) return List.of();
		CompiledHopKey key = reference.rule().parentOccurrence();
		var alternative = selected.get(key);
		if(alternative == null || alternative.realization() != realization || !visiting.add(key))
			return List.of();
		try {
			List<Value> result = new ArrayList<>();
			// Transient reads and synthetic function bindings carry the same
			// per-execution map relation through their exact logical source bindings.
			if(alternative.supportClause().inputBindings().stream()
				.anyMatch(binding -> binding.kind() == CandidateInputBindingKind.LOGICAL_TRANSIENT)) {
				for(var binding : alternative.supportClause().inputBindings()) {
					if(binding.kind() != CandidateInputBindingKind.LOGICAL_TRANSIENT) continue;
					CompiledHopKey source = binding.source().rule().parentOccurrence();
					for(Value value : values(binding.source(), selected, visiting)) {
						Map<CompiledHopKey,CompiledHopKey> sources = new IdentityHashMap<>(value.sources());
						sources.put(key, source);
						Set<CompiledHopKey> activations = new LinkedHashSet<>(value.activations());
						activations.add(source);
						result.add(new Value(value.pool(), Collections.unmodifiableMap(sources), Set.copyOf(activations)));
					}
				}
			}
			else for(Row row : executionRows(alternative, selected, visiting)) {
				Value first = row.inputs().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
				if(first == null) continue;
				Map<CompiledHopKey,CompiledHopKey> sources = new IdentityHashMap<>();
				row.inputs().stream().filter(java.util.Objects::nonNull).forEach(value -> sources.putAll(value.sources()));
				result.add(new Value(first.pool(), Collections.unmodifiableMap(sources), row.activations()));
			}
			return List.copyOf(result);
		}
		finally { visiting.remove(key); }
	}

	/** Enumerates only prefixes that still extend to a row in the exact source relation. */
	static ProductWork enumerateMatchingProduct(List<List<Value>> choices,
		JointValueMapRelations.Relation relation, List<Row> output) {
		MutableProductWork work = new MutableProductWork();
		matchingProduct(choices, 0, new ArrayList<>(), relation, output, work);
		return new ProductWork(work.visitedValues, work.emittedRows);
	}

	private static void matchingProduct(List<List<Value>> choices, int position, List<Value> current,
		JointValueMapRelations.Relation relation, List<Row> output, MutableProductWork work) {
		if(position == choices.size()) {
			output.add(new Row(Collections.unmodifiableList(new ArrayList<>(current))));
			work.emittedRows++;
			return;
		}
		for(Value value : choices.get(position)) {
			work.visitedValues++;
			current.add(value);
			if(matches(relation, current))
				matchingProduct(choices, position + 1, current, relation, output, work);
			current.remove(current.size() - 1);
		}
	}

	private static boolean matches(JointValueMapRelations.Relation relation, List<Value> values) {
		Map<CompiledHopKey,CompiledHopKey> choices = new IdentityHashMap<>();
		for(Value value : values) {
			if(value == null) continue;
			for(var entry : value.sources().entrySet()) {
				var prior = choices.putIfAbsent(entry.getKey(), entry.getValue());
				if(prior != null && prior != entry.getValue()) return false;
			}
		}
		return relation.rows().stream().anyMatch(tuple -> tuple.inputs().stream().allMatch(input ->
			!choices.containsKey(input.reader()) || choices.get(input.reader()) == input.source()));
	}

	private static void product(List<List<Value>> choices, int position, List<Value> current, List<Row> output) {
		if(position == choices.size()) {
			output.add(new Row(Collections.unmodifiableList(new ArrayList<>(current))));
			return;
		}
		for(Value value : choices.get(position)) {
			current.add(value);
			product(choices, position + 1, current, output);
			current.remove(current.size() - 1);
		}
	}

	private static void alignedProduct(List<List<Value>> choices, int position, List<Value> current,
		List<Row> output, DurableAnchorKey selectedPool) {
		if(position == choices.size()) {
			output.add(new Row(Collections.unmodifiableList(new ArrayList<>(current))));
			return;
		}
		for(Value value : choices.get(position)) {
			if(value != null && selectedPool != null
				&& !org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
					.samePhysicalWorkerPool(selectedPool, value.pool()))
				continue;
			current.add(value);
			alignedProduct(choices, position + 1, current, output,
				selectedPool != null || value == null ? selectedPool : value.pool());
			current.remove(current.size() - 1);
		}
	}

	/**
	 * Branch events, not row counts, determine weights. Distinct acyclic arm
	 * events partition a consumer invocation. When loop history or call-context
	 * overlap prevents that partition proof, use the largest projected unit cost
	 * as an explicit conservative estimate; never charge zero or one arbitrary map.
	 */
	static double expectedUnit(OccurrenceExecutionFrequencyFacts frequencies, CompiledHopKey consumer,
		List<Row> rows, ToDoubleFunction<Row> price) {
		if(rows.isEmpty()) throw new IllegalArgumentException("JOINT_PHYSICAL_COST_ROWS_UNPROVEN");
		double max = rows.stream().mapToDouble(price).max().orElseThrow();
		if(rows.stream().allMatch(row -> Double.compare(price.applyAsDouble(row), max) == 0)) return max;
		List<Map<String,OccurrenceExecutionFrequencyFacts.BranchActivationFact>> events = new ArrayList<>();
		var profiles = frequencies.exactProfiles(consumer);
		if(profiles.size() != 1) return max;
		var context = profiles.get(0);
		Set<String> consumerConditions = new java.util.HashSet<>();
		context.activationConditions().forEach(condition -> consumerConditions.add(condition.decisionKey()));
		for(Row row : rows) {
			Map<String,OccurrenceExecutionFrequencyFacts.BranchActivationFact> event = new LinkedHashMap<>();
			for(CompiledHopKey source : row.activations()) {
				var sourceProfiles = frequencies.exactProfiles(source);
				if(sourceProfiles.size() != 1 || sourceProfiles.get(0).contextOrdinal() != context.contextOrdinal()) return max;
				for(var condition : sourceProfiles.get(0).activationConditions()) {
					if(consumerConditions.contains(condition.decisionKey())) continue;
					var prior = event.putIfAbsent(condition.decisionKey(), condition);
					if(prior != null && prior.ifArm() != condition.ifArm()) return max;
				}
			}
			events.add(event);
		}
		for(int a = 0; a < events.size(); a++)
			for(int b = a + 1; b < events.size(); b++) {
				var left = events.get(a); var right = events.get(b);
				if(left.entrySet().stream().noneMatch(entry -> right.containsKey(entry.getKey())
					&& entry.getValue().ifArm() != right.get(entry.getKey()).ifArm())) return max;
			}
		double total = 0, cost = 0;
		for(int row = 0; row < rows.size(); row++) {
			double probability = 1;
			for(var condition : events.get(row).values()) probability *= condition.probability();
			total += probability;
			cost += probability * price.applyAsDouble(rows.get(row));
		}
		return Math.abs(total - 1) < 1e-12 ? cost : max;
	}
}
