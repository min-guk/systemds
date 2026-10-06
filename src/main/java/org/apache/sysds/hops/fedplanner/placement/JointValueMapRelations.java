/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;

/** Immutable projection of joint reaching definitions onto physical value-map owners. */
public final class JointValueMapRelations {
	private JointValueMapRelations() { }

	public record InputSource(int inputPosition, CompiledHopKey reader, CompiledHopKey source,
		CompiledHopKey valueOrigin)
		implements Comparable<InputSource> {
		public InputSource(int inputPosition, CompiledHopKey reader, CompiledHopKey source) {
			this(inputPosition, reader, source, source);
		}
		public InputSource {
			if(inputPosition < 0)
				throw new IllegalArgumentException("Joint value-map input position must be non-negative");
			Objects.requireNonNull(reader, "reader");
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(valueOrigin, "valueOrigin");
		}
		@Override public int compareTo(InputSource that) {
			int position = Integer.compare(inputPosition, that.inputPosition);
			if(position != 0)
				return position;
			int read = reader.compareTo(that.reader);
			if(read != 0)
				return read;
			int supplier = source.compareTo(that.source);
			return supplier != 0 ? supplier : valueOrigin.compareTo(that.valueOrigin);
		}
	}

	public record Row(List<InputSource> inputs) implements Comparable<Row> {
		public Row {
			inputs = List.copyOf(inputs);
			for(int position = 0; position < inputs.size(); position++)
				if(inputs.get(position).inputPosition() != position)
					throw new IllegalArgumentException("Joint value-map row input positions are not dense");
		}
		@Override public int compareTo(Row that) {
			for(int position = 0; position < Math.min(inputs.size(), that.inputs.size()); position++) {
				int compared = inputs.get(position).compareTo(that.inputs.get(position));
				if(compared != 0)
					return compared;
			}
			return Integer.compare(inputs.size(), that.inputs.size());
		}
	}

	public record Relation(CompiledHopKey consumer, List<CompiledHopKey> readers, List<Row> rows,
		List<CompiledHopKey> sources) {
		public Relation {
			Objects.requireNonNull(consumer, "consumer");
			readers = List.copyOf(readers);
			rows = List.copyOf(rows);
			sources = List.copyOf(sources);
			if(readers.size() < 2 || rows.isEmpty())
				throw new IllegalArgumentException("Joint value-map relation requires multiple inputs and rows");
			int arity = readers.size();
			if(rows.stream().anyMatch(row -> row.inputs().size() != arity))
				throw new IllegalArgumentException("Joint value-map row arity differs from its reader arity");
		}

		public String normalizedSignature() {
			return consumer.normalizedSignature() + "|readers="
				+ readers.stream().map(CompiledHopKey::normalizedSignature).toList() + "|rows="
				+ rows.stream().map(row -> row.inputs().stream()
					.map(input -> input.inputPosition() + ":" + input.source().normalizedSignature()
						+ "@" + input.valueOrigin().normalizedSignature()).toList()).toList();
		}
	}

	public record GroundedInput(int inputPosition, CompiledHopKey reader,
		CompiledHopKey source, DurableAnchorKey pool) {
		public GroundedInput {
			if(inputPosition < 0)
				throw new IllegalArgumentException("Grounded input position must be non-negative");
			Objects.requireNonNull(reader, "reader");
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(pool, "pool");
		}
	}

	/** One reachable physical row; outputPool is derived from the declared native output input. */
	public record GroundedLayoutRow(List<GroundedInput> inputs, DurableAnchorKey outputPool) {
		public GroundedLayoutRow { inputs = List.copyOf(inputs); }
	}

	public static List<Relation> from(PlacementAnalysis analysis) {
		return analysis.jointInputAnalysis().map(joint -> from(analysis, joint)).orElse(List.of());
	}

	/**
	 * Resolves the selected VALUE_MAP reader clauses into every correlated Jv row.
	 * Empty means that the receipts do not provide a complete, exact physical map.
	 * One reader receipt is shared by every row, so a producer cannot change its
	 * physical realization between rows.
	 */
	public static List<GroundedLayoutRow> groundedRows(PlacementAnalysis analysis,
		Relation relation, Map<CompiledHopKey,CandidateSelectionReceipt> selectedReceipts,
		int outputSourcePosition) {
		Set<Integer> positions = new TreeSet<>();
		for(int position = 0; position < relation.readers().size(); position++)
			positions.add(position);
		return groundedRows(analysis, relation, selectedReceipts, outputSourcePosition, positions);
	}

	/** Local or explicitly relocated inputs are checked by their own input authority. */
	public static List<GroundedLayoutRow> groundedRows(PlacementAnalysis analysis,
		Relation relation, Map<CompiledHopKey,CandidateSelectionReceipt> selectedReceipts,
		int outputSourcePosition, Set<Integer> directFederatedPositions) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(relation, "relation");
		Objects.requireNonNull(selectedReceipts, "selectedReceipts");
		if(outputSourcePosition < -1 || outputSourcePosition >= relation.readers().size())
			throw new IllegalArgumentException("Output source position is outside relation arity");
		return new Grounding(analysis, relation, true).rows(selectedReceipts, outputSourcePosition,
			directFederatedPositions);
	}

	private record PoolQuery(CandidateRealizationReference reference,
		CompiledHopKey supplier, CompiledHopKey origin) { }

	/** Immutable-analysis projection; memoized proofs never depend on a selected plan. */
	public static final class Grounding {
		private final PlacementAnalysis analysis;
		private final Relation relation;
		private final boolean requireSameGeometry;
		private final Map<PoolQuery,java.util.Optional<DurableAnchorKey>> invariantPools = new java.util.HashMap<>();

		public Grounding(PlacementAnalysis analysis, Relation relation) {
			this(analysis, relation, false);
		}

		private Grounding(PlacementAnalysis analysis, Relation relation, boolean requireSameGeometry) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.relation = Objects.requireNonNull(relation, "relation");
			this.requireSameGeometry = requireSameGeometry;
		}

		private boolean sameObservedPool(DurableAnchorKey left, DurableAnchorKey right) {
			// Joint hard legality observes worker alignment and the partitioned axis.
			// Cost projections additionally observe all extents; do not reuse the
			// weaker proof to assign one layout's price to a different geometry.
			return requireSameGeometry ? PlacementIdentity.samePhysicalLayout(left, right)
				: PlacementIdentity.samePhysicalWorkerPool(left, right);
		}

		public List<GroundedLayoutRow> rows(Map<CompiledHopKey,CandidateSelectionReceipt> selected,
			int outputSourcePosition, Set<Integer> positions) {
			List<GroundedLayoutRow> result = new ArrayList<>();
			for(Row row : relation.rows()) {
				List<GroundedInput> inputs = new ArrayList<>();
				for(InputSource input : row.inputs()) {
					if(!positions.contains(input.inputPosition())) continue;
					CandidateSelectionReceipt receipt = selected.get(input.reader());
					if(receipt == null) return List.of();
					PoolQuery query = new PoolQuery(CandidateRealizationReference.of(receipt.rule(),
						receipt.realization()), input.source(), input.valueOrigin());
					DurableAnchorKey pool = selectedPool(query, receipt, selected, new java.util.HashSet<>());
					if(pool == null) return List.of();
					inputs.add(new GroundedInput(input.inputPosition(), input.reader(), input.source(), pool));
				}
				DurableAnchorKey output = inputs.stream().filter(input ->
					input.inputPosition() == outputSourcePosition).map(GroundedInput::pool).findFirst().orElse(null);
				result.add(new GroundedLayoutRow(inputs, output));
			}
			return List.copyOf(result);
		}

		private DurableAnchorKey selectedPool(PoolQuery query, CandidateSelectionReceipt receipt,
			Map<CompiledHopKey,CandidateSelectionReceipt> selected, Set<PoolQuery> active) {
			if(!active.add(query)) return null;
			try {
				DurableAnchorKey exact = exactPool(receipt.realization(), receipt.supportClause());
				if(exact != null) return exact;
				if(receipt.realization().key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
					return null;
				List<CandidateRealizationReference> sources = sources(receipt.supportClause(), query);
				DurableAnchorKey common = null;
				for(CandidateRealizationReference reference : sources) {
					PoolQuery childQuery = new PoolQuery(reference, query.origin(), query.origin());
					DurableAnchorKey pool = invariantPool(childQuery, new java.util.HashSet<>());
					if(pool == null) {
						CandidateSelectionReceipt child = selected.get(reference.rule().parentOccurrence());
						if(child == null || !CandidateSelections.matchesRealization(reference, child)) return null;
						pool = selectedPool(childQuery, child, selected, active);
					}
					if(pool == null || common != null && !sameObservedPool(common, pool))
						return null;
					common = pool;
				}
				return common;
			}
			finally { active.remove(query); }
		}

		/** All owned clauses must prove the same observed layout, including conversion results. */
		private DurableAnchorKey invariantPool(PoolQuery query, Set<PoolQuery> active) {
			var cached = invariantPools.get(query);
			if(cached != null) return cached.orElse(null);
			if(!active.add(query)) return null;
			DurableAnchorKey common = null;
			boolean complete = true;
			try {
				var realization = analysis.requireExactCandidateRealization(query.reference());
				for(var clause : realization.supportClauses()) {
					DurableAnchorKey pool = exactPool(realization, clause);
					if(pool == null && realization.key().layoutKind() == PlacementIdentity.PlacementLayoutKind.VALUE_MAP) {
						for(var reference : sources(clause, query)) {
							DurableAnchorKey child = invariantPool(new PoolQuery(reference, query.origin(), query.origin()), active);
							if(child == null || pool != null && !sameObservedPool(pool, child)) {
								complete = false;
								break;
							}
							pool = child;
						}
					}
					if(!complete || pool == null || common != null && !sameObservedPool(common, pool)) {
						complete = false;
						break;
					}
					common = pool;
				}
			}
			finally { active.remove(query); }
			DurableAnchorKey result = complete ? common : null;
			invariantPools.put(query, java.util.Optional.ofNullable(result));
			return result;
		}

		private static DurableAnchorKey exactPool(PlacementAnalysis.CandidateEmissionRealization realization,
			PlacementAnalysis.CandidateRealizationSupportClause clause) {
			return realization.nativeWorkerPoolLayoutExact(clause)
				? realization.provenWorkerPoolForOwnedClause(clause) : null;
		}

		private List<CandidateRealizationReference> sources(
			PlacementAnalysis.CandidateRealizationSupportClause clause, PoolQuery query) {
			List<CandidateRealizationReference> candidates = clause.requiredInputSupport().stream()
				.filter(reference -> reference.rule().parentOccurrence() == query.supplier()).toList();
			if(candidates.isEmpty()) candidates = clause.requiredInputSupport().stream()
				.filter(reference -> reference.rule().parentOccurrence() == query.origin()).toList();
			if(candidates.isEmpty() && clause.requiredInputSupport().size() == 1)
				candidates = clause.requiredInputSupport();
			if(candidates.isEmpty()) candidates = clause.requiredInputSupport().stream().filter(reference ->
				aliasesOrigin(analysis, reference, query.origin(), new java.util.HashSet<>())).toList();
			return candidates;
		}

		/** Scope contains only decisions whose chosen clause can change a queried physical map. */
		public List<CompiledHopKey> supportOwners() {
			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			owners.addAll(relation.readers());
			Set<PoolQuery> visited = new java.util.HashSet<>();
			for(Row row : relation.rows()) for(InputSource input : row.inputs())
				for(var fact : analysis.candidateRuleFacts().orderedFactsForParent(input.reader()))
					for(var emission : fact.allowedEmissionFacts()) for(var realization : emission.realizations()) {
						PoolQuery query = new PoolQuery(CandidateRealizationReference.of(fact.key(), realization),
							input.source(), input.valueOrigin());
						collectOwners(query, owners, visited);
					}
			return owners.stream().sorted().toList();
		}

		private void collectOwners(PoolQuery query, Set<CompiledHopKey> owners, Set<PoolQuery> visited) {
			if(!visited.add(query)) return;
			var realization = analysis.requireExactCandidateRealization(query.reference());
			if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP) return;
			for(var clause : realization.supportClauses()) for(var reference : sources(clause, query)) {
				PoolQuery child = new PoolQuery(reference, query.origin(), query.origin());
				if(invariantPool(child, new java.util.HashSet<>()) == null) {
					owners.add(reference.rule().parentOccurrence());
					collectOwners(child, owners, visited);
				}
			}
		}
	}

	/** Follow value aliases only: an operand of a computation is not its result value. */
	private static boolean aliasesOrigin(PlacementAnalysis analysis,
		CandidateRealizationReference reference, CompiledHopKey origin,
		Set<CandidateRealizationReference> active) {
		CompiledHopKey owner = reference.rule().parentOccurrence();
		if(owner == origin) return true;
		if(!active.add(reference)) return false;
		try {
			Hop hop = analysis.hop(owner).orElse(null);
			if(hop != null && !PlacementProgramFacts.isTransientRead(hop)
				&& !PlacementProgramFacts.isTransientWrite(hop)
				&& !(hop instanceof UnaryOp unary && unary.getOp() == OpOp1._PLACEMENT)) return false;
			for(var clause : analysis.requireExactCandidateRealization(reference).supportClauses())
				for(var support : clause.requiredInputSupport())
					if(aliasesOrigin(analysis, support, origin, active)) return true;
			return false;
		}
		finally { active.remove(reference); }
	}

	public static List<CompiledHopKey> supportOwners(PlacementAnalysis analysis, Relation relation) {
		return new Grounding(analysis, relation).supportOwners();
	}

	/**
	 * Shared hard legality for a complete selected witness. A VALUE_MAP reader can
	 * resolve to a different pool in each correlated control-flow row, but every
	 * physical input used by one FED execution row must resolve to the same worker
	 * pool. LOCAL inputs are owned by their broadcast/download authority, while an
	 * explicit relocation contributes its target pool rather than its source pool.
	 */
	public static boolean selectedExecutionRowsAligned(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> receipts) {
		return incompatibleSelectedExecutionConsumers(analysis, assignment, receipts).isEmpty();
	}

	/** Identity-owned consumers whose selected runtime rows cannot share one worker pool. */
	public static List<CompiledHopKey> incompatibleSelectedExecutionConsumers(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> receipts) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(assignment, "assignment");
		Objects.requireNonNull(receipts, "receipts");
		List<CompiledHopKey> incompatible = new ArrayList<>();
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : receipts)
			if(selected.put(receipt.rule().parentOccurrence(), receipt) != null)
				return List.of(receipt.rule().parentOccurrence());
		for(Relation relation : from(analysis)) {
			boolean valueMapped = relation.readers().stream().map(selected::get)
				.filter(Objects::nonNull).anyMatch(receipt -> receipt.realization().key().layoutKind()
					== PlacementIdentity.PlacementLayoutKind.VALUE_MAP);
			if(!valueMapped)
				continue;
			PlacementState state = assignment.get(relation.consumer());
			if(state == null) {
				incompatible.add(relation.consumer());
				continue;
			}
			// Exact admits a receiptless CP/LOUT alternative before consulting its
			// realization because the coordinator collects every input locally.
			if(state.execType() == org.apache.sysds.common.Types.ExecType.CP
				&& state.output()
					== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT)
				continue;
			CandidateSelectionReceipt consumer = selected.get(relation.consumer());
			if(consumer == null) {
				incompatible.add(relation.consumer());
				continue;
			}
			// Exact inputAuthorityProducts classifies every CP input and every DML
			// FunctionOp placeholder input as NATIVE_LOCAL. They do not represent a
			// runtime kernel consuming multiple FederationMaps, even for CP/FOUT or a
			// FED forwarding placeholder, so joint worker-pool alignment does not apply.
			if(state.execType() != org.apache.sysds.common.Types.ExecType.FED
				|| analysis.isDmlFunctionCallBoundary(relation.consumer()))
				continue;
			Set<Integer> directPositions = new TreeSet<>();
			Set<Integer> physicalPositions = new TreeSet<>();
			List<DurableAnchorKey> relocatedPools = new ArrayList<>();
			for(var binding : consumer.supportClause().inputBindings()) {
				if(binding.inputPosition() >= consumer.rule().orderedInputs().size()) {
					incompatible.add(relation.consumer());
					break;
				}
				if(!consumer.rule().orderedInputs().get(binding.inputPosition()).present())
					continue; // Exact classifies ABSENT_LOCAL as NATIVE_LOCAL.
				if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT) {
					directPositions.add(binding.inputPosition());
					physicalPositions.add(binding.inputPosition());
				}
				else if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION) {
					relocatedPools.add(binding.relocationAction().durableAnchor());
					physicalPositions.add(binding.inputPosition());
				}
			}
			// One runtime FederationMap has no peer to align with. Its availability
			// remains owned by the ordinary exact input authority; requiring complete
			// joint row grounding here would reject matrix-scalar kernels such as
			// alpha*HS solely because the scalar's control-flow relation is dynamic.
			if(physicalPositions.size() <= 1)
				continue;
			List<GroundedLayoutRow> rows = new Grounding(analysis, relation)
				.rows(selected, -1, directPositions);
			if(!executionRowsAligned(relation, rows, relocatedPools))
				incompatible.add(relation.consumer());
		}
		return incompatible.stream().distinct().sorted().toList();
	}

	/** Shared row predicate used by exact factors without changing their scope. */
	public static boolean executionRowsAligned(Relation relation,
		List<GroundedLayoutRow> rows, Collection<DurableAnchorKey> relocatedPools) {
		Objects.requireNonNull(relation, "relation");
		Objects.requireNonNull(rows, "rows");
		Objects.requireNonNull(relocatedPools, "relocatedPools");
		if(rows.size() != relation.rows().size())
			return false;
		for(GroundedLayoutRow row : rows) {
			DurableAnchorKey first = null;
			List<DurableAnchorKey> pools = new ArrayList<>(relocatedPools);
			row.inputs().forEach(input -> pools.add(input.pool()));
			for(DurableAnchorKey pool : pools) {
				if(first == null)
					first = pool;
				else if(!PlacementIdentity.samePhysicalWorkerPool(first, pool))
					return false;
			}
		}
		return true;
	}

	private static List<Relation> from(PlacementAnalysis analysis, PlacementJointInputAnalysis joint) {
		List<Relation> relations = new ArrayList<>();
		for(var occurrence : analysis.compiledHopOccurrences()) {
			Hop hop = occurrence.hop();
			if(hop.getInput().size() < 2 || !hop.getInput().stream().allMatch(PlacementProgramFacts::isTransientRead))
				continue;
			Relation relation = from(joint, occurrence.key(), analysis.graph().nodes());
			if(relation != null)
				relations.add(relation);
		}
		return relations.stream().sorted((left, right) -> left.consumer().compareTo(right.consumer())).toList();
	}

	static Relation from(PlacementJointInputAnalysis joint, CompiledHopKey consumer) {
		return from(joint, consumer, List.of());
	}

	static Relation from(PlacementJointInputAnalysis joint, CompiledHopKey consumer,
		List<Node> physicalNodes) {
		Objects.requireNonNull(joint, "joint");
		int consumerOrdinal = joint.occurrenceOrdinal(consumer);
		Hop hop = joint.occurrenceHop(consumerOrdinal);
		if(hop instanceof FunctionOp || hop.getInput().size() < 2
			|| !hop.getInput().stream().allMatch(PlacementProgramFacts::isTransientRead))
			return null;
		List<PlacementJointInputAnalysis.JointTuple> tuples = joint.tuplesForConsumer(consumer);
		if(tuples.isEmpty())
			return null;
		List<CompiledHopKey> readers = tuples.get(0).inputs().stream()
			.map(input -> joint.occurrenceKey(input.readOrdinal())).toList();
		Set<Row> rows = new TreeSet<>();
		Set<CompiledHopKey> sourceSet = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var tuple : tuples) {
			List<InputSource> inputs = new ArrayList<>(tuple.inputs().size());
			for(var input : tuple.inputs()) {
				CompiledHopKey source = physicalSource(input.source(), physicalNodes);
				if(source == null)
					return null;
				sourceSet.add(source);
				inputs.add(new InputSource(input.inputPosition(),
					joint.occurrenceKey(input.readOrdinal()), source,
					input.source().valueOccurrence() == null ? source : input.source().valueOccurrence()));
			}
			rows.add(new Row(inputs));
		}
		List<CompiledHopKey> sources = sourceSet.stream().sorted().toList();
		return new Relation(consumer, readers, List.copyOf(rows), sources);
	}

	private static CompiledHopKey physicalSource(PlacementJointInputAnalysis.Definition definition,
		List<Node> physicalNodes) {
		if(definition.kind() == PlacementJointInputAnalysis.SourceKind.OCCURRENCE)
			return definition.occurrence();
		if(definition.occurrence() == null || physicalNodes.isEmpty())
			return null;
		NodeKind expectedNode = definition.kind() == PlacementJointInputAnalysis.SourceKind.FUNCTION_INPUT
			? NodeKind.FUNCTION_INPUT : NodeKind.FUNCTION_OUTPUT;
		VersionKind expectedVersion = definition.kind() == PlacementJointInputAnalysis.SourceKind.FUNCTION_INPUT
			? VersionKind.FUNCTION_INPUT : VersionKind.FUNCTION_OUTPUT;
		String context = "callsite:" + definition.occurrence().normalizedSignature();
		List<CompiledHopKey> matches = physicalNodes.stream()
			.filter(node -> node.kind() == expectedNode
				&& node.valueVersion().versionKind() == expectedVersion
				&& node.valueVersion().definitionOrdinal() == definition.boundaryPosition()
				&& context.equals(node.key().recompileContext()))
			.map(Node::key).toList();
		return matches.size() == 1 ? matches.get(0) : null;
	}

	/** Every row must be executable; different rows may retain different exact worker pools. */
	static boolean allRowsHaveAlignedPools(Relation relation,
		Map<CompiledHopKey,DurableAnchorKey> selectedPools) {
		Objects.requireNonNull(relation, "relation");
		Objects.requireNonNull(selectedPools, "selectedPools");
		for(Row row : relation.rows()) {
			DurableAnchorKey first = null;
			for(InputSource input : row.inputs()) {
				DurableAnchorKey pool = selectedPools.get(input.source());
				if(pool == null)
					return false;
				if(first == null)
					first = pool;
				else if(!PlacementIdentity.samePhysicalWorkerPool(first, pool))
					return false;
			}
		}
		return true;
	}

	/** Identity-keyed selected pool map; one producer decision is shared across every row that names it. */
	static Map<CompiledHopKey,DurableAnchorKey> selectedPools() {
		return new IdentityHashMap<>();
	}
}
