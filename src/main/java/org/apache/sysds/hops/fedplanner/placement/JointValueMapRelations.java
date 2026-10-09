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

import java.util.ArrayDeque;
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

	/**
	 * A joint-alignment evaluator with one certified internal alias owner removed.
	 *
	 * <p>The projection is equivalent only together with the original candidate
	 * realization-support factors. Those factors still require the removed alias
	 * decision and its retained target to select the references used by the
	 * projected evaluator. This certificate is therefore not a standalone
	 * whole-assignment equivalence.</p>
	 */
	public record AliasProjection(Grounding grounding, CompiledHopKey removedOwner,
		int certifiedQueryCount) {
		public AliasProjection {
			Objects.requireNonNull(grounding, "grounding");
			Objects.requireNonNull(removedOwner, "removedOwner");
			if(certifiedQueryCount <= 0)
				throw new IllegalArgumentException("Alias projection requires a certified query");
		}
	}

	private record SourceQuery(PlacementAnalysis.CandidateRealizationSupportClause clause,
		CompiledHopKey supplier, CompiledHopKey origin) {
		@Override public boolean equals(Object other) {
			return other instanceof SourceQuery that && clause == that.clause
				&& supplier == that.supplier && origin == that.origin;
		}
		@Override public int hashCode() {
			return 31 * (31 * System.identityHashCode(clause) + System.identityHashCode(supplier))
				+ System.identityHashCode(origin);
		}
	}

	/** Immutable-analysis projection; memoized proofs never depend on a selected plan. */
	public static final class Grounding {
		/** A proof about every completion of the currently assigned decision owners. */
		public enum PartialAlignment { UNKNOWN, ALIGNED, FORBIDDEN }

		/** One pool-proof context. A projected query is a virtual alias node owned by its retained target. */
		public record ProofQuery(CandidateRealizationReference reference,
			CompiledHopKey supplier, CompiledHopKey origin, CompiledHopKey projectedTarget) {
			public ProofQuery {
				Objects.requireNonNull(reference, "reference");
				Objects.requireNonNull(supplier, "supplier");
				Objects.requireNonNull(origin, "origin");
			}
			public CompiledHopKey decisionOwner() {
				return projectedTarget == null ? reference.rule().parentOccurrence() : projectedTarget;
			}
			public boolean projectedAlias() { return projectedTarget != null; }
			public String normalizedSignature() {
				return reference.normalizedSignature() + "|supplier=" + supplier.normalizedSignature()
					+ "|origin=" + origin.normalizedSignature() + "|projectedTarget="
					+ (projectedTarget == null ? "-" : projectedTarget.normalizedSignature());
			}
		}

		/** A conjunctive child demand; a non-null guard must match the selected child receipt exactly. */
		public record ProofEdge(ProofQuery child, CandidateRealizationReference selectedReferenceGuard) {
			public ProofEdge { Objects.requireNonNull(child, "child"); }
			public String normalizedSignature() {
				return child.normalizedSignature() + "|guard=" + (selectedReferenceGuard == null ? "-"
					: selectedReferenceGuard.normalizedSignature());
			}
		}

		/** One selected alternative's local transition in the recursive worker-pool proof. */
		public record ProofStep(boolean invalid, DurableAnchorKey exactPool,
			List<DurableAnchorKey> invariantPools, List<ProofEdge> edges) {
			public ProofStep {
				invariantPools = List.copyOf(invariantPools);
				edges = List.copyOf(edges);
				if(invalid && (exactPool != null || !invariantPools.isEmpty() || !edges.isEmpty())
					|| exactPool != null && (!invariantPools.isEmpty() || !edges.isEmpty()))
					throw new IllegalArgumentException("Joint proof step has mixed terminal states");
			}
			public String normalizedSignature() {
				return invalid ? "INVALID" : exactPool != null
					? "EXACT:" + exactPool.normalizedSignature()
					: "CONJUNCTION|invariants=" + invariantPools.stream()
						.map(DurableAnchorKey::normalizedSignature).toList() + "|edges="
						+ edges.stream().map(ProofEdge::normalizedSignature).toList();
			}
		}

		private record PartialPool(DurableAnchorKey pool, boolean forbidden) {
			private static final PartialPool UNKNOWN = new PartialPool(null, false);
			private static final PartialPool FORBIDDEN = new PartialPool(null, true);
		}

		private final PlacementAnalysis analysis;
		private final Relation relation;
		private final CompiledHopKey fixedPoolOwner;
		private final boolean requireSameGeometry;
		private final Map<PoolQuery,CompiledHopKey> projectedAliasOwners;
		private final Map<PoolQuery,java.util.Optional<DurableAnchorKey>> invariantPools = new java.util.HashMap<>();
		private final Map<CompiledHopKey,Map<CandidateRealizationReference,Boolean>> aliasOrigins =
			new IdentityHashMap<>();
		private final Map<CandidateRealizationReference,java.util.Optional<DurableAnchorKey>>
			allSourceInvariantPools = new java.util.HashMap<>();
		private final Map<SourceQuery,List<CandidateRealizationReference>> sourceProjections = new java.util.HashMap<>();

		public Grounding(PlacementAnalysis analysis, Relation relation) {
			this(analysis, relation, false);
		}

		private Grounding(PlacementAnalysis analysis, Relation relation, boolean requireSameGeometry) {
			this(analysis, relation, requireSameGeometry, Map.of());
		}

		private Grounding(PlacementAnalysis analysis, Relation relation, boolean requireSameGeometry,
			Map<PoolQuery,CompiledHopKey> projectedAliasOwners) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.relation = Objects.requireNonNull(relation, "relation");
			this.fixedPoolOwner = null;
			this.requireSameGeometry = requireSameGeometry;
			this.projectedAliasOwners = Map.copyOf(projectedAliasOwners);
		}

		/** Root proof query for one selected reader and one correlated relation-row input. */
		public ProofQuery proofQuery(CandidateSelectionReceipt receipt, InputSource input) {
			Objects.requireNonNull(receipt, "receipt");
			Objects.requireNonNull(input, "input");
			return external(new PoolQuery(CandidateRealizationReference.of(receipt.rule(), receipt.realization()),
				input.source(), input.valueOrigin()), null);
		}

		/**
		 * Exposes one local proof transition without evaluating a complete assignment.
		 * This is a structural view of {@link #selectedPool}: the canonical evaluator
		 * remains the source of truth and is intentionally unchanged.
		 */
		public ProofStep proofStep(ProofQuery query, CandidateSelectionReceipt selected) {
			Objects.requireNonNull(query, "query");
			if(query.projectedAlias())
				return projectedProofStep(query, selected);
			if(selected == null || !CandidateSelections.matchesRealization(query.reference(), selected))
				return invalidProofStep();
			DurableAnchorKey exact = exactPool(selected.realization(), selected.supportClause());
			if(exact != null)
				return new ProofStep(false, exact, List.of(), List.of());
			if(selected.realization().key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
				return invalidProofStep();
			PoolQuery internal = internal(query);
			List<CandidateRealizationReference> sources = sources(selected.supportClause(), internal);
			if(sources.isEmpty())
				return invalidProofStep();
			List<DurableAnchorKey> constants = new ArrayList<>();
			List<ProofEdge> edges = new ArrayList<>();
			for(CandidateRealizationReference reference : sources) {
				PoolQuery child = new PoolQuery(reference, internal.origin(), internal.origin());
				DurableAnchorKey invariant = invariantPool(child, new java.util.HashSet<>());
				if(invariant != null)
					constants.add(invariant);
				else {
					CompiledHopKey projected = projectedAliasOwners.get(child);
					ProofQuery target = external(child, projected);
					edges.add(new ProofEdge(target, projected == null ? reference : null));
				}
			}
			return new ProofStep(false, null, constants, edges);
		}

		private ProofStep projectedProofStep(ProofQuery query, CandidateSelectionReceipt selected) {
			if(selected == null)
				return invalidProofStep();
			PoolQuery target = new PoolQuery(CandidateRealizationReference.of(selected.rule(), selected.realization()),
				query.origin(), query.origin());
			DurableAnchorKey invariant = invariantPool(target, new java.util.HashSet<>());
			if(invariant != null)
				return new ProofStep(false, invariant, List.of(), List.of());
			// The virtual alias transition selects the target receipt itself, so the
			// forwarded ordinary query needs no second selected-reference guard.
			return new ProofStep(false, null, List.of(),
				List.of(new ProofEdge(external(target, null), null)));
		}

		private static ProofStep invalidProofStep() {
			return new ProofStep(true, null, List.of(), List.of());
		}

		private static PoolQuery internal(ProofQuery query) {
			return new PoolQuery(query.reference(), query.supplier(), query.origin());
		}

		private static ProofQuery external(PoolQuery query, CompiledHopKey projectedTarget) {
			return new ProofQuery(query.reference(), query.supplier(), query.origin(), projectedTarget);
		}

		/**
		 * Certifies removal of one non-reader transient alias from this joint factor.
		 * The returned evaluator is sound only while the original realization-support
		 * factors remain in the model; they enforce the skipped alias-to-target
		 * references. The source evaluator is immutable and remains unchanged.
		 */
		public java.util.Optional<AliasProjection> projectAliasOwner(CompiledHopKey owner,
			Set<CompiledHopKey> retainedOwners) {
			Objects.requireNonNull(owner, "owner");
			Objects.requireNonNull(retainedOwners, "retainedOwners");
			if(!projectedAliasOwners.isEmpty() || owner == relation.consumer()
				|| relation.readers().contains(owner) || !internalAliasOwner(owner))
				return java.util.Optional.empty();
			List<CompiledHopKey> originalOwners = supportOwners();
			if(!originalOwners.contains(owner))
				return java.util.Optional.empty();

			Set<PoolQuery> reachable = new java.util.HashSet<>();
			Set<PoolQuery> candidates = new java.util.HashSet<>();
			for(Row row : relation.rows()) for(InputSource input : row.inputs())
				for(var fact : analysis.candidateRuleFacts().orderedFactsForParent(input.reader()))
					for(var emission : fact.allowedEmissionFacts()) for(var realization : emission.realizations()) {
						PoolQuery query = new PoolQuery(CandidateRealizationReference.of(fact.key(), realization),
							input.source(), input.valueOrigin());
						collectProjectionQueries(query, owner, reachable, candidates);
					}
			if(candidates.isEmpty())
				return java.util.Optional.empty();

			Map<PoolQuery,CompiledHopKey> certified = new java.util.HashMap<>(projectedAliasOwners);
			for(PoolQuery query : candidates) {
				var realization = analysis.requireExactCandidateRealization(query.reference());
				if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
					return java.util.Optional.empty();
				CompiledHopKey target = null;
				for(var clause : realization.supportClauses()) {
					if(exactPool(realization, clause) != null)
						return java.util.Optional.empty();
					List<CandidateRealizationReference> sources = sources(clause, query);
					if(sources.size() != 1)
						return java.util.Optional.empty();
					CompiledHopKey candidate = sources.get(0).rule().parentOccurrence();
					if(candidate == owner || !retainedOwners.contains(candidate)
						|| target != null && target != candidate)
						return java.util.Optional.empty();
					target = candidate;
				}
				if(target == null)
					return java.util.Optional.empty();
				certified.put(query, target);
			}
			Grounding projected = new Grounding(analysis, relation, requireSameGeometry, certified);
			projected.invariantPools.putAll(invariantPools);
			aliasOrigins.forEach((origin, queries) ->
				projected.aliasOrigins.put(origin, new java.util.HashMap<>(queries)));
			return java.util.Optional.of(new AliasProjection(projected, owner, candidates.size()));
		}

		private boolean internalAliasOwner(CompiledHopKey owner) {
			Hop hop = analysis.hop(owner).orElse(null);
			return hop != null && (PlacementProgramFacts.isTransientRead(hop)
				|| PlacementProgramFacts.isTransientWrite(hop)
				|| hop instanceof UnaryOp unary && unary.getOp() == OpOp1._PLACEMENT);
		}

		private void collectProjectionQueries(PoolQuery query, CompiledHopKey owner,
			Set<PoolQuery> visited, Set<PoolQuery> candidates) {
			if(!visited.add(query)) return;
			var realization = analysis.requireExactCandidateRealization(query.reference());
			if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP) return;
			for(var clause : realization.supportClauses()) for(var reference : sources(clause, query)) {
				PoolQuery child = new PoolQuery(reference, query.origin(), query.origin());
				if(invariantPool(child, new java.util.HashSet<>()) != null)
					continue;
				if(reference.rule().parentOccurrence() == owner)
					candidates.add(child);
				collectProjectionQueries(child, owner, visited, candidates);
			}
		}

		private Grounding(PlacementAnalysis analysis, CompiledHopKey fixedPoolOwner) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.relation = null;
			this.fixedPoolOwner = Objects.requireNonNull(fixedPoolOwner, "fixedPoolOwner");
			this.requireSameGeometry = false;
			this.projectedAliasOwners = Map.of();
		}

		/** Resolves every source of one selected VALUE_MAP into one fixed worker pool. */
		public static Grounding fixedPool(PlacementAnalysis analysis, CompiledHopKey owner) {
			return new Grounding(analysis, owner);
		}

		/** Root query for the fixed-pool proof selected by one exact owner receipt. */
		public ProofQuery fixedPoolProofQuery(CandidateSelectionReceipt receipt) {
			if(fixedPoolOwner == null)
				throw new IllegalStateException("Correlated-row grounding does not own a fixed-pool query");
			Objects.requireNonNull(receipt, "receipt");
			if(receipt.rule().parentOccurrence() != fixedPoolOwner)
				throw new IllegalArgumentException("Fixed-pool receipt owner mismatch");
			CandidateRealizationReference reference =
				CandidateRealizationReference.of(receipt.rule(), receipt.realization());
			return new ProofQuery(reference, fixedPoolOwner, fixedPoolOwner, null);
		}

		/**
		 * One exact transition of the fixed-pool graph used by {@link #matchesFixedPool}.
		 * The returned constants and guarded children preserve source, rule, realization,
		 * and owned-clause identity; callers still decide which concrete pool is expected.
		 */
		public ProofStep fixedPoolProofStep(ProofQuery query, CandidateSelectionReceipt selected) {
			if(fixedPoolOwner == null)
				throw new IllegalStateException("Correlated-row grounding does not own a fixed-pool query");
			Objects.requireNonNull(query, "query");
			if(selected == null || query.projectedAlias()
				|| query.decisionOwner() != selected.rule().parentOccurrence()
				|| query.reference().rule() != selected.rule()
				|| !selected.realization().ownsSupportClauseIdentity(selected.supportClause()))
				return invalidProofStep();
			var fact = analysis.candidateRuleFacts().requireExact(
				selected.rule().parentOccurrence(), selected.rule().orderedInputs());
			if(fact.key() != selected.rule()
				|| analysis.requireExactCandidateRealization(query.reference()) != selected.realization())
				return invalidProofStep();
			DurableAnchorKey exact = exactPool(selected.realization(), selected.supportClause());
			if(exact != null)
				return new ProofStep(false, exact, List.of(), List.of());
			if(selected.realization().key().layoutKind()
				!= PlacementIdentity.PlacementLayoutKind.VALUE_MAP
				|| selected.supportClause().requiredInputSupport().isEmpty())
				return invalidProofStep();
			List<DurableAnchorKey> constants = new ArrayList<>();
			List<ProofEdge> edges = new ArrayList<>();
			for(CandidateRealizationReference reference :
				selected.supportClause().requiredInputSupport()) {
				DurableAnchorKey invariant = allSourceInvariantPool(reference,
					new java.util.HashSet<>());
				if(invariant != null)
					constants.add(invariant);
				else {
					ProofQuery child = new ProofQuery(reference,
						reference.rule().parentOccurrence(), fixedPoolOwner, null);
					edges.add(new ProofEdge(child, reference));
				}
			}
			return new ProofStep(false, null, constants, edges);
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
			if(relation == null)
				throw new IllegalStateException("Fixed-pool grounding does not own correlated rows");
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

		/**
		 * Missing map keys denote free decisions; present null values denote selected
		 * alternatives without a candidate receipt. Only a conflict that no remaining
		 * choice can repair is forbidden. Each CFG row has its own aligned pool.
		 */
		public PartialAlignment partialAlignment(Map<CompiledHopKey,CandidateSelectionReceipt> selected,
			Set<Integer> positions, List<DurableAnchorKey> relocatedPools) {
			boolean unknown = false;
			for(Row row : relation.rows()) {
				DurableAnchorKey common = null;
				for(DurableAnchorKey pool : relocatedPools) {
					if(common != null && !sameObservedPool(common, pool))
						return PartialAlignment.FORBIDDEN;
					common = pool;
				}
				for(InputSource input : row.inputs()) {
					if(!positions.contains(input.inputPosition())) continue;
					if(!selected.containsKey(input.reader())) {
						unknown = true;
						continue;
					}
					CandidateSelectionReceipt receipt = selected.get(input.reader());
					if(receipt == null) return PartialAlignment.FORBIDDEN;
					PoolQuery query = new PoolQuery(CandidateRealizationReference.of(receipt.rule(),
						receipt.realization()), input.source(), input.valueOrigin());
					PartialPool proof = partialPool(query, receipt, selected, new java.util.HashSet<>());
					if(proof.forbidden()) return PartialAlignment.FORBIDDEN;
					if(proof.pool() == null) unknown = true;
					else {
						if(common != null && !sameObservedPool(common, proof.pool()))
							return PartialAlignment.FORBIDDEN;
						common = proof.pool();
					}
				}
			}
			return unknown ? PartialAlignment.UNKNOWN : PartialAlignment.ALIGNED;
		}

		private PartialPool partialPool(PoolQuery query, CandidateSelectionReceipt receipt,
			Map<CompiledHopKey,CandidateSelectionReceipt> selected, Set<PoolQuery> active) {
			if(!active.add(query)) return PartialPool.FORBIDDEN;
			try {
				DurableAnchorKey exact = exactPool(receipt.realization(), receipt.supportClause());
				if(exact != null) return new PartialPool(exact, false);
				if(receipt.realization().key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
					return PartialPool.FORBIDDEN;
				List<CandidateRealizationReference> sources = sources(receipt.supportClause(), query);
				if(sources.isEmpty()) return PartialPool.FORBIDDEN;
				DurableAnchorKey common = null;
				boolean unknown = false;
				for(CandidateRealizationReference reference : sources) {
					PoolQuery childQuery = new PoolQuery(reference, query.origin(), query.origin());
					DurableAnchorKey pool = invariantPool(childQuery, new java.util.HashSet<>());
					if(pool == null) {
						CompiledHopKey projectedOwner = projectedAliasOwners.get(childQuery);
						if(projectedOwner != null) {
							PartialPool proof = projectedPartialPool(childQuery, projectedOwner, selected, active);
							if(proof.forbidden()) return proof;
							pool = proof.pool();
							if(pool == null) {
								unknown = true;
								continue;
							}
						}
						else {
							CompiledHopKey owner = reference.rule().parentOccurrence();
							if(!selected.containsKey(owner)) {
								unknown = true;
								continue;
							}
							CandidateSelectionReceipt child = selected.get(owner);
							if(child == null || !CandidateSelections.matchesRealization(reference, child))
								return PartialPool.FORBIDDEN;
							PartialPool proof = partialPool(childQuery, child, selected, active);
							if(proof.forbidden()) return proof;
							pool = proof.pool();
						}
					}
					if(pool == null) unknown = true;
					else {
						if(common != null && !sameObservedPool(common, pool))
							return PartialPool.FORBIDDEN;
						common = pool;
					}
				}
				return unknown ? PartialPool.UNKNOWN : new PartialPool(common, false);
			}
			finally { active.remove(query); }
		}

		private PartialPool projectedPartialPool(PoolQuery query, CompiledHopKey target,
			Map<CompiledHopKey,CandidateSelectionReceipt> selected, Set<PoolQuery> active) {
			if(!active.add(query)) return PartialPool.FORBIDDEN;
			try {
				if(!selected.containsKey(target)) return PartialPool.UNKNOWN;
				CandidateSelectionReceipt receipt = selected.get(target);
				if(receipt == null) return PartialPool.FORBIDDEN;
				PoolQuery child = new PoolQuery(CandidateRealizationReference.of(receipt.rule(),
					receipt.realization()), query.origin(), query.origin());
				DurableAnchorKey pool = invariantPool(child, new java.util.HashSet<>());
				return pool == null ? partialPool(child, receipt, selected, active)
					: new PartialPool(pool, false);
			}
			finally { active.remove(query); }
		}

		/**
		 * The selected owner and every recursively required VALUE_MAP source must
		 * resolve to the supplied concrete pool. Missing selected receipts are
		 * deferred only for an explicit partial-search call. Clause-independent
		 * invariant sources are intentionally omitted from the selected map here;
		 * callers must enforce ordinary realization-support selection separately.
		 */
		public boolean matchesFixedPool(Map<CompiledHopKey,CandidateSelectionReceipt> selected,
			DurableAnchorKey expected, boolean allowUnassigned) {
			if(fixedPoolOwner == null)
				throw new IllegalStateException("Correlated-row grounding does not own a fixed-pool query");
			Objects.requireNonNull(selected, "selected");
			Objects.requireNonNull(expected, "expected");
			CandidateSelectionReceipt receipt = selected.get(fixedPoolOwner);
			if(receipt == null)
				return allowUnassigned;
			return new SelectedFixedPoolGraph(selected, expected).matches(receipt, allowUnassigned);
		}

		/** Selected fixed-pool proof graph; cycles require a reachable exact or deferred terminal. */
		private final class SelectedFixedPoolGraph {
			private final Map<CompiledHopKey,CandidateSelectionReceipt> selected;
			private final DurableAnchorKey expected;
			private final Map<CompiledHopKey,SelectedPoolNode> nodes = new IdentityHashMap<>();
			private boolean valid = true;

			private SelectedFixedPoolGraph(Map<CompiledHopKey,CandidateSelectionReceipt> selected,
				DurableAnchorKey expected) {
				this.selected = selected;
				this.expected = expected;
			}

			private boolean matches(CandidateSelectionReceipt root, boolean allowUnassigned) {
				visit(root);
				if(!valid || !allowUnassigned && nodes.values().stream().anyMatch(node -> node.deferred))
					return false;
				Set<SelectedPoolNode> grounded = Collections.newSetFromMap(new IdentityHashMap<>());
				Map<SelectedPoolNode,List<SelectedPoolNode>> dependents = new IdentityHashMap<>();
				java.util.ArrayDeque<SelectedPoolNode> work = new java.util.ArrayDeque<>();
				for(SelectedPoolNode node : nodes.values()) {
					if(node.exact || allowUnassigned && node.deferred) {
						grounded.add(node);
						work.add(node);
					}
					for(SelectedPoolNode dependency : node.dependencies)
						dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node);
				}
				while(!work.isEmpty())
					for(SelectedPoolNode dependent : dependents.getOrDefault(work.remove(), List.of()))
						if(grounded.add(dependent))
							work.add(dependent);
				return grounded.size() == nodes.size();
			}

			private SelectedPoolNode visit(CandidateSelectionReceipt receipt) {
				CompiledHopKey owner = receipt.rule().parentOccurrence();
				SelectedPoolNode existing = nodes.get(owner);
				if(existing != null) {
					if(existing.receipt != receipt)
						valid = false;
					return existing;
				}
				SelectedPoolNode node = new SelectedPoolNode(receipt);
				nodes.put(owner, node);
				var fact = analysis.candidateRuleFacts().requireExact(owner, receipt.rule().orderedInputs());
				if(fact.key() != receipt.rule()
					|| analysis.requireExactCandidateRealization(CandidateRealizationReference.of(
						receipt.rule(), receipt.realization())) != receipt.realization()
					|| !receipt.realization().ownsSupportClauseIdentity(receipt.supportClause())) {
					valid = false;
					return node;
				}
				DurableAnchorKey exact = exactPool(receipt.realization(), receipt.supportClause());
				if(exact != null) {
					node.exact = true;
					valid &= sameObservedPool(exact, expected);
					return node;
				}
				if(receipt.realization().key().layoutKind()
					!= PlacementIdentity.PlacementLayoutKind.VALUE_MAP
					|| receipt.supportClause().requiredInputSupport().isEmpty()) {
					valid = false;
					return node;
				}
				for(CandidateRealizationReference reference : receipt.supportClause().requiredInputSupport()) {
					DurableAnchorKey invariant = allSourceInvariantPool(reference, new java.util.HashSet<>());
					if(invariant != null) {
						node.exact = true;
						valid &= sameObservedPool(invariant, expected);
						continue;
					}
					CandidateSelectionReceipt child = selected.get(reference.rule().parentOccurrence());
					if(child == null) {
						node.deferred = true;
						continue;
					}
					if(reference.rule() != child.rule()
						|| analysis.requireExactCandidateRealization(reference) != child.realization()) {
						valid = false;
						continue;
					}
					node.dependencies.add(visit(child));
				}
				return node;
			}
		}

		private static final class SelectedPoolNode {
			private final CandidateSelectionReceipt receipt;
			private final List<SelectedPoolNode> dependencies = new ArrayList<>();
			private boolean exact;
			private boolean deferred;

			private SelectedPoolNode(CandidateSelectionReceipt receipt) {
				this.receipt = receipt;
			}
		}

		private DurableAnchorKey allSourceInvariantPool(CandidateRealizationReference reference,
			Set<CandidateRealizationReference> active) {
			var cached = allSourceInvariantPools.get(reference);
			if(cached != null)
				return cached.orElse(null);
			if(!active.add(reference))
				return null;
			DurableAnchorKey common = null;
			boolean complete = true;
			try {
				var realization = analysis.requireExactCandidateRealization(reference);
				if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP) {
					// The realization constructor proves a common witness/exactness for all
					// clauses. A concrete map query does not depend on source choices.
					common = uniformConcretePool(realization);
					complete = common != null;
				}
				else for(var clause : realization.supportClauses()) {
					DurableAnchorKey pool = exactPool(realization, clause);
					if(pool == null && realization.key().layoutKind()
						== PlacementIdentity.PlacementLayoutKind.VALUE_MAP
						&& !clause.requiredInputSupport().isEmpty()) {
						for(var support : clause.requiredInputSupport()) {
							DurableAnchorKey child = allSourceInvariantPool(support, active);
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
			finally {
				active.remove(reference);
			}
			DurableAnchorKey result = complete ? common : null;
			allSourceInvariantPools.put(reference, java.util.Optional.ofNullable(result));
			return result;
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
						CompiledHopKey projectedOwner = projectedAliasOwners.get(childQuery);
						if(projectedOwner != null)
							pool = projectedSelectedPool(childQuery, projectedOwner, selected, active);
						else {
							CandidateSelectionReceipt child = selected.get(reference.rule().parentOccurrence());
							if(child == null || !CandidateSelections.matchesRealization(reference, child)) return null;
							pool = selectedPool(childQuery, child, selected, active);
						}
					}
					if(pool == null || common != null && !sameObservedPool(common, pool))
						return null;
					common = pool;
				}
				return common;
			}
			finally { active.remove(query); }
		}

		private DurableAnchorKey projectedSelectedPool(PoolQuery query, CompiledHopKey target,
			Map<CompiledHopKey,CandidateSelectionReceipt> selected, Set<PoolQuery> active) {
			if(!active.add(query)) return null;
			try {
				CandidateSelectionReceipt receipt = selected.get(target);
				if(receipt == null) return null;
				PoolQuery child = new PoolQuery(CandidateRealizationReference.of(receipt.rule(),
					receipt.realization()), query.origin(), query.origin());
				DurableAnchorKey pool = invariantPool(child, new java.util.HashSet<>());
				return pool != null ? pool : selectedPool(child, receipt, selected, active);
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
				if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP) {
					// The realization constructor proves a common witness/exactness for all
					// clauses. A concrete map query does not depend on source choices.
					common = uniformConcretePool(realization);
					complete = common != null;
				}
				else for(var clause : realization.supportClauses()) {
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

		private static DurableAnchorKey uniformConcretePool(
			PlacementAnalysis.CandidateEmissionRealization realization) {
			if(realization.anchor() != null) return realization.anchor();
			var product = realization.factorizedSupportProduct().orElse(null);
			if(product != null)
				return product.nativeWorkerPoolLayoutExact() ? product.nativeWorkerPoolWitness() : null;
			return exactPool(realization, realization.supportClauses().get(0));
		}

		private static DurableAnchorKey exactPool(PlacementAnalysis.CandidateEmissionRealization realization,
			PlacementAnalysis.CandidateRealizationSupportClause clause) {
			return realization.nativeWorkerPoolLayoutExact(clause)
				? realization.provenWorkerPoolForOwnedClause(clause) : null;
		}

		private List<CandidateRealizationReference> sources(
			PlacementAnalysis.CandidateRealizationSupportClause clause, PoolQuery query) {
			SourceQuery key = new SourceQuery(clause, query.supplier(), query.origin());
			List<CandidateRealizationReference> cached = sourceProjections.get(key);
			if(cached != null) return cached;
			List<CandidateRealizationReference> candidates = clause.requiredInputSupport().stream()
				.filter(reference -> reference.rule().parentOccurrence() == query.supplier()).toList();
			if(candidates.isEmpty()) candidates = clause.requiredInputSupport().stream()
				.filter(reference -> reference.rule().parentOccurrence() == query.origin()).toList();
			if(candidates.isEmpty() && clause.requiredInputSupport().size() == 1)
				candidates = clause.requiredInputSupport();
			if(candidates.isEmpty()) candidates = clause.requiredInputSupport().stream().filter(reference ->
				aliasesOrigin(reference, query.origin())).toList();
			sourceProjections.put(key, candidates);
			return candidates;
		}

		private boolean aliasesOrigin(CandidateRealizationReference reference, CompiledHopKey origin) {
			// Origin matching is identity-based; references retain realization equality.
			Map<CandidateRealizationReference,Boolean> queries = aliasOrigins.computeIfAbsent(origin,
				ignored -> new java.util.HashMap<>());
			// Only completed root queries are reusable. A recursive cycle cut is not
			// a proof that the corresponding intermediate node cannot reach origin.
			return queries.computeIfAbsent(reference, ignored -> JointValueMapRelations.aliasesOrigin(
				analysis, reference, origin, new java.util.HashSet<>()));
		}

		/** Scope contains only decisions whose chosen clause can change a queried physical map. */
		public List<CompiledHopKey> supportOwners() {
			if(fixedPoolOwner != null)
				return fixedPoolSupportOwners();
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
			owners.removeAll(projectedAliasOwners.keySet().stream()
				.map(query -> query.reference().rule().parentOccurrence()).toList());
			return owners.stream().sorted().toList();
		}

		private List<CompiledHopKey> fixedPoolSupportOwners() {
			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			owners.add(fixedPoolOwner);
			Set<CandidateRealizationReference> visited = new java.util.HashSet<>();
			for(var fact : analysis.candidateRuleFacts().orderedFactsForParent(fixedPoolOwner))
				for(var emission : fact.allowedEmissionFacts())
					for(var realization : emission.realizations())
						collectAllSourceOwners(CandidateRealizationReference.of(fact.key(), realization),
							owners, visited);
			return owners.stream().sorted().toList();
		}

		private void collectAllSourceOwners(CandidateRealizationReference reference,
			Set<CompiledHopKey> owners, Set<CandidateRealizationReference> visited) {
			if(!visited.add(reference) || allSourceInvariantPool(reference, new java.util.HashSet<>()) != null)
				return;
			var realization = analysis.requireExactCandidateRealization(reference);
			if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
				return;
			for(var clause : realization.supportClauses())
				for(var support : clause.requiredInputSupport()) {
					if(allSourceInvariantPool(support, new java.util.HashSet<>()) == null) {
						owners.add(support.rule().parentOccurrence());
						collectAllSourceOwners(support, owners, visited);
					}
				}
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
		Set<CandidateRealizationReference> visited) {
		ArrayDeque<CandidateRealizationReference> pending = new ArrayDeque<>();
		pending.push(reference);
		while(!pending.isEmpty()) {
			CandidateRealizationReference current = pending.pop();
			CompiledHopKey owner = current.rule().parentOccurrence();
			if(owner == origin) return true;
			// Retain visits for the entire query, including across reconvergent paths.
			if(!visited.add(current)) continue;
			Hop hop = analysis.hop(owner).orElse(null);
			if(hop != null && !PlacementProgramFacts.isTransientRead(hop)
				&& !PlacementProgramFacts.isTransientWrite(hop)
				&& !(hop instanceof UnaryOp unary && unary.getOp() == OpOp1._PLACEMENT)) continue;
			var realization = analysis.requireExactCandidateRealization(current);
			var clauses = realization.supportClauses();
			// Reverse pushes preserve the previous depth-first support order.
			for(int clause = clauses.size() - 1; clause >= 0; clause--) {
				var support = clauses.get(clause).requiredInputSupport();
				for(int position = support.size() - 1; position >= 0; position--)
					pending.push(support.get(position));
			}
			// Native TRead provenance comes from exact logical compatibility edges.
			// Matching worker pools or FTypes alone never proves the writer identity.
			if(PlacementProgramFacts.isTransientRead(hop)
				&& clauses.stream().allMatch(clause -> clause.requiredInputSupport().isEmpty())
				&& clauses.stream().anyMatch(clause -> realization.provenWorkerPoolForOwnedClause(clause) != null)) {
				var compatibility = analysis.transientCompatibilityForReader(current);
				for(int edge = compatibility.size() - 1; edge >= 0; edge--)
					pending.push(compatibility.get(edge).sourceRealization());
			}
		}
		return false;
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
