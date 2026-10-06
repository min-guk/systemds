/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.LocalMaterializationSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicNativeContinuationFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.WorkerPoolIdentity;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections.AuditDemandOptions;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.ComponentBound;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Non-backtracking policy selection over the common, fully generated candidate universe.
 * Rows retain their exact realization AND support clause. Support groups count alternatives;
 * deleting one clause must not invalidate a realization that still has another clause.
 *
 * <p>Index construction and monotone propagation are proportional to rows and indexed support
 * incidences (plus bounded placement-state pairs), not to combinations of whole-program plans.
 * Every row is examined at most once by the policy, and deleted at most once. Canonical order
 * and final authority validation have their own costs; this is not an end-to-end linear-time
 * claim about the shared candidate generator. Cyclic decisions remain provisional until the
 * complete owned witness is validated. There is no retry, rollback, or exact-search fallback.
 * Arc consistency is necessary, not complete: a greedy conflict is NOT global infeasibility.
 */
public final class PolicyGreedyPlacementSelector implements PlacementSelector, PlacementAnalysisSelector {
	public enum Policy { FED_FIRST, AGG_LOCAL }
	public record Metrics(long candidateChecks, long supportIncidences, long deletedRows,
		long decisionCommits, long factEvents) { }
	public record Run(PlacementSelection selection, Metrics metrics) { }

	/** A failed heuristic choice, never a proof that every common plan is illegal. */
	public static final class GreedyConflictException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		GreedyConflictException(String message) { super("Greedy policy conflict (not global infeasibility): " + message); }
		GreedyConflictException(String message, Throwable cause) { this(message); initCause(cause); }
	}
	/** No selected external entry grounds a cyclic proof; this is not global infeasibility. */
	public static final class UnresolvedBoundaryContractException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		UnresolvedBoundaryContractException(String message) {
			super("Unresolved boundary contract (not global infeasibility): " + message);
		}
	}

	private final Policy policy;
	public PolicyGreedyPlacementSelector() { this(Policy.FED_FIRST); }
	public PolicyGreedyPlacementSelector(Policy policy) { this.policy = Objects.requireNonNull(policy); }
	public Policy policy() { return policy; }
	@Override public PlacementSelection select(NeutralPlacementGraph graph) { return select(null, graph); }
	@Override public PlacementSelection select(PlacementAnalysis analysis, NeutralPlacementGraph graph) {
		return selectWithMetrics(analysis, graph).selection();
	}
	public Run selectWithMetrics(PlacementAnalysis analysis, NeutralPlacementGraph graph) {
		return new Invocation(analysis, Objects.requireNonNull(graph), policy).run();
	}

	private static final class Row {
		final Domain domain;
		final PlacementState state;
		final CandidateSelectionReceipt receipt;
		final CandidateRealizationReference reference;
		final Map<CompiledHopKey,CandidateRealizationReference> inputs = new IdentityHashMap<>();
		final List<Group> memberships = new ArrayList<>();
		final Group singleton = new Group();
		boolean active = true;
		int movementInputs;
		int residentInputs;
		boolean preferLocalAggregate;
		final List<PhysicalInput> physicalInputs = new ArrayList<>();
		Row(Domain domain, PlacementState state, CandidateSelectionReceipt receipt) {
			this.domain = domain;
			this.state = state;
			this.receipt = receipt;
			this.reference = receipt == null ? null : CandidateRealizationReference.of(receipt.rule(), receipt.realization());
			singleton.add(this);
		}
	}
	private record PhysicalInput(Domain source, FType required) { }
	private record RelocationInput(RelocationActionKey action, int position, PlacementState consumerState) { }
	private record LocalContinuation(Domain seed, Domain source, int inputPosition) { }
	private static final class Domain {
		final Node node;
		final List<Row> rows = new ArrayList<>();
		final Map<PlacementState,Group> states = new LinkedHashMap<>();
		final Map<CandidateRealizationReference,Group> references = new LinkedHashMap<>();
		final Set<Domain> producers = new LinkedHashSet<>();
		final List<Group> localConsumerOptions = new ArrayList<>();
		final List<LocalContinuation> localContinuations = new ArrayList<>();
		final List<HeuristicNativeContinuationFact> nativeContinuations = new ArrayList<>();
		final Group local = new Group();
		final Group all = new Group();
		Row selected;
		Domain(Node node) { this.node = node; }
		@Override public String toString() { return node.key().normalizedSignature(); }
		void add(Row row) {
			rows.add(row);
			all.add(row);
			if(row.state.output() == FederatedOutput.LOUT) local.add(row);
			states.computeIfAbsent(row.state, ignored -> new Group()).add(row);
			if(row.reference != null)
				references.computeIfAbsent(row.reference, ignored -> new Group()).add(row);
		}
	}
	private static final class Group {
		final List<Row> rows = new ArrayList<>();
		final List<Requirement> watchers = new ArrayList<>();
		int live;
		void add(Row row) { rows.add(row); row.memberships.add(this); live++; }
	}
	private static final class Requirement {
		final Group dependent;
		int liveSupports;
		Requirement(Group dependent) { this.dependent = dependent; }
	}
	private record InputPair(Domain source, Domain consumer) { }
	private record InputSupportKey(CandidateRealizationReference own,
		CandidateRealizationReference required) { }
	private static final class InputSupports {
		final Map<InputSupportKey,Group> exact = new HashMap<>();
		final Map<CandidateRealizationReference,Group> byRequirement = new HashMap<>();
		InputSupports(Domain source, Domain other) {
			for(Row row : source.rows) {
				var required = row.inputs.get(other.node.key());
				exact.computeIfAbsent(new InputSupportKey(row.reference, required), ignored -> new Group()).add(row);
				byRequirement.computeIfAbsent(required, ignored -> new Group()).add(row);
			}
		}
		List<Group> compatible(Row row, Domain source) {
			var required = row.inputs.get(source.node.key());
			// Both directions must hold for the same row pair. A missing dependency
			// is a wildcard, not a missing realization; OR clauses remain separate rows.
			return required == null
				? java.util.Arrays.asList(byRequirement.get(null), byRequirement.get(row.reference))
				: java.util.Arrays.asList(exact.get(new InputSupportKey(required, null)),
					exact.get(new InputSupportKey(required, row.reference)));
		}
	}
	private static final class Pools {
		final Group nonNative = new Group();
		final Map<WorkerPoolIdentity,Group> endpoints = new HashMap<>();
		final Map<WorkerPoolIdentity,Group> exact = new HashMap<>();
		final Map<WorkerPoolIdentity,Group> inexact = new HashMap<>();
	}

	private record Residency(PlacementState state, WorkerPoolIdentity pool) { }
	private record MaterializedPool(FType type, WorkerPoolIdentity pool) { }
	private static Residency residency(Row row) {
		var pool = row.receipt == null ? null : row.receipt.provenWorkerPool();
		return new Residency(row.state, pool == null ? null : PlacementIdentity.physicalWorkerPoolIdentity(pool));
	}
	private static MaterializedPool materializedPool(Row row) {
		var action = row.receipt == null ? null : row.receipt.emission().derivedFoutAction();
		return action == null ? null : new MaterializedPool(action.materializationFType(),
			PlacementIdentity.physicalWorkerPoolIdentity(action.durableAnchor()));
	}
	private static final class Residencies {
		final Map<Residency,Group> nativePools = new HashMap<>();
		final Map<PlacementState,Group> noReceipt = new HashMap<>();
		final Map<MaterializedPool,Group> materialized = new HashMap<>();
		Residencies(Domain source) {
			for(Row row : source.rows) {
				if(row.receipt == null) noReceipt.computeIfAbsent(row.state, ignored -> new Group()).add(row);
				else if(residency(row).pool() != null)
					nativePools.computeIfAbsent(residency(row), ignored -> new Group()).add(row);
				var output = materializedPool(row);
				if(output != null) materialized.computeIfAbsent(output, ignored -> new Group()).add(row);
			}
		}
	}

	private static final class Invocation {
		final PlacementAnalysis analysis;
		final NeutralPlacementGraph graph;
		final Policy policy;
		final List<Domain> domains = new ArrayList<>();
		final Map<CompiledHopKey,Domain> byKey = new IdentityHashMap<>();
		final ArrayDeque<Row> deletions = new ArrayDeque<>();
		long checks, incidences, deleted, commits, events;

		Invocation(PlacementAnalysis analysis, NeutralPlacementGraph graph, Policy policy) {
			this.analysis = analysis;
			this.graph = graph;
			this.policy = policy;
		}

		Run run() {
			indexRows();
			indexConstraints();
			if(analysis != null) {
				indexInputs();
				indexPhysicalInputs();
				indexTransients();
				indexBoundaries();
				if(policy == Policy.AGG_LOCAL) indexLocalContinuations();
			}
			propagate();
			Map<Domain,Set<Domain>> dependencies = new LinkedHashMap<>();
			for(Domain domain : domains) dependencies.put(domain, domain.producers);
			// Condensation is dependency-first. Internal choices are provisional, NOT
			// seeds: the selected exact proof graph must separately establish grounding.
			for(List<Domain> component : dependencyComponents(dependencies)) for(Domain domain : component) {
				Row best = null;
				int rank = Integer.MAX_VALUE;
				for(Row row : domain.rows) {
					checks++;
					if(!row.active) continue;
					int candidateRank = rank(row);
					if(best == null || candidateRank < rank || candidateRank == rank
						&& row.movementInputs < best.movementInputs) {
						best = row;
						rank = candidateRank;
					}
				}
				if(best == null) throw conflict(domain);
				domain.selected = best;
				commits++;
				for(Row row : domain.rows) if(row != best) deletions.add(row);
				propagate();
			}
			PlacementSelection selection;
			try { selection = finish(); }
			catch(UnresolvedBoundaryContractException ex) { throw ex; }
			catch(IllegalArgumentException | IllegalStateException ex) {
				throw new GreedyConflictException("complete witness validation: " + ex.getMessage(), ex);
			}
			return new Run(selection, new Metrics(checks, incidences, deleted, commits, events));
		}

		void indexRows() {
			for(Node node : graph.decisionNodes()) {
				Domain domain = new Domain(node);
				domains.add(domain);
				byKey.put(node.key(), domain);
				if(analysis == null) {
					for(PlacementState state : node.legalAlternatives()) domain.add(new Row(domain, state, null));
					continue;
				}
				Map<PlacementState,PlacementState> owned = new HashMap<>();
				node.legalAlternatives().forEach(state -> owned.put(state, state));
				var facts = analysis.candidateRuleFacts().orderedFactsForParent(node.key());
				for(var fact : facts) if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
					for(var emission : fact.allowedEmissionFacts()) {
						PlacementState state = owned.get(emission.emissionState().placementState());
						if(state == null) continue; // A caller's explicit legal graph projection.
						for(var receipt : analysis.canonicalCandidateReceipts(fact.key(), emission)) {
							Row row = new Row(domain, state, receipt);
							row.preferLocalAggregate = aggregateVector(node.key(), emission.executionFType());
							for(var binding : receipt.supportClause().inputBindings()) {
								if(binding.kind() == CandidateInputBindingKind.RELOCATION) row.movementInputs++;
								else if(binding.source().realization().emissionState().placementState().output()
									== FederatedOutput.FOUT) row.residentInputs++;
							}
							domain.add(row);
						}
					}
				// Match common active-consumer semantics: only AVAILABLE emissions
				// require a receipt. A legal non-candidate state remains state-only even
				// when this occurrence has excluded/error facts for other states.
				for(PlacementState state : node.legalAlternatives())
					if(!domain.states.containsKey(state)) domain.add(new Row(domain, state, null));
				if(domain.rows.isEmpty()) throw conflict(domain);
			}
		}
		boolean aggregateVector(CompiledHopKey key, FType executionType) {
			return policy == Policy.AGG_LOCAL && prefersLocalAggregate(analysis.hop(key).orElse(null),
				analysis.abstractShapeFact(key).orElse(null), executionType);
		}
		void indexLocalContinuations() {
			// Facts describe exact marker/edge paths, not an unconditional union of
			// descendants. Keep their premises so a dead local seed cannot bias CP.
			Set<CompiledHopKey> vectorOnly = Collections.newSetFromMap(new IdentityHashMap<>());
			for(Domain domain : domains) if(scalarOrVector(domain.node.key())) vectorOnly.add(domain.node.key());
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder())
				if(!scalarOrVector(edge.producer())) vectorOnly.remove(edge.consumer());
			for(var path : analysis.heuristicPolicyFacts().paths()) {
				Domain seed = byKey.get(path.demotion().producer());
				if(seed == null) continue;
				Set<CompiledHopKey> prefix = new LinkedHashSet<>(path.localPrefix());
				for(var edge : path.edges()) {
					Domain source = byKey.get(edge.producer()), consumer = byKey.get(edge.consumer());
					if(source == null || consumer == null || consumer == seed
						|| !prefix.contains(edge.consumer()) || !vectorOnly.contains(edge.consumer())) continue;
					var continuation = new LocalContinuation(seed, source, edge.inputPosition());
					if(!consumer.localContinuations.contains(continuation)) consumer.localContinuations.add(continuation);
				}
				for(var fact : path.nativeContinuations()) {
					Domain consumer = byKey.get(fact.consumer());
					if(consumer == null) continue;
					if(!consumer.nativeContinuations.contains(fact)) consumer.nativeContinuations.add(fact);
					// The old path tracer visits a nested aggregate's native boundary
					// before CP. Prefer an exact all-vector CP row for a public
					// sibling, not a fresh release of a protected aggregate sibling.
					Domain source = byKey.get(fact.localProducer());
					if(source != null && vectorOnly.contains(fact.consumer())
						&& analysis.requirePrivacy(fact.siblingProducer()) == Privacy.PUBLIC) {
						var continuation = new LocalContinuation(seed, source, fact.localInputPosition());
						if(!consumer.localContinuations.contains(continuation)) consumer.localContinuations.add(continuation);
					}
				}
			}
		}
		boolean scalarOrVector(CompiledHopKey key) {
			var shape = analysis.abstractShapeFact(key).orElse(null);
			return shape != null && (shape.dataType().isScalar() || shape.provablyVector());
		}
		boolean localInputSupported(Row row, Domain source, int position) {
			if(source == null || source.local.live == 0) return false;
			var reference = row.inputs.get(source.node.key());
			if(reference != null) {
				Group support = source.references.get(reference);
				if(reference.realization().emissionState().placementState().output() != FederatedOutput.LOUT
					|| support == null || support.live == 0) return false;
			}
			if(row.receipt != null) for(var binding : row.receipt.supportClause().inputBindings())
				if(binding.inputPosition() == position && (binding.kind() == CandidateInputBindingKind.RELOCATION
					|| binding.source().realization().emissionState().placementState().output() != FederatedOutput.LOUT))
					return false;
			// An unbound legacy row still has the shared physical/transient support
			// relation. Do not demand a committed producer inside a dependency SCC.
			return true;
		}
		boolean prefersLocalContinuation(Row row) {
			if(row.state.execType() != ExecType.CP || row.state.output() != FederatedOutput.LOUT) return false;
			for(var continuation : row.domain.localContinuations)
				if(continuation.seed().local.live > 0
					&& localInputSupported(row, continuation.source(), continuation.inputPosition())) return true;
			return false;
		}
		boolean prefersNativeContinuation(Row row) {
			if(row.receipt == null || row.receipt.emission().derivedFoutAction() != null) return false;
			for(var fact : row.domain.nativeContinuations) {
				Domain sibling = byKey.get(fact.siblingProducer());
				Group support = sibling == null ? null : sibling.states.get(fact.siblingFoutState());
				if(row.state.equals(fact.consumerState()) && row.receipt.rule().equals(fact.runtimeCandidate().key())
					&& support != null && support.live > 0
					&& localInputSupported(row, byKey.get(fact.localProducer()), fact.localInputPosition())) return true;
			}
			return false;
		}
		int rank(Row row) {
			if(policy == Policy.AGG_LOCAL) {
				if(prefersLocalContinuation(row)) return -2;
				if(prefersNativeContinuation(row)) return -1;
			}
			int residentCount = row.residentInputs;
			for(PhysicalInput input : row.physicalInputs) {
				Row source = input.source().selected;
				if(source != null && source.state.output() == FederatedOutput.FOUT
					&& source.state.fType() == input.required()) residentCount++;
				else row.movementInputs++;
			}
			boolean fed = row.state.execType() == ExecType.FED;
			boolean fout = row.state.output() == FederatedOutput.FOUT;
			boolean nativeOutput = row.receipt == null || row.receipt.emission().derivedFoutAction() == null;
			// Graph-only tests cannot express input residency. Production ranks exact input proofs.
			boolean resident = row.receipt == null || residentCount > 0
				|| row.receipt.rule().orderedInputs().isEmpty();
			if(fed && resident && nativeOutput) {
				// Local continuation has priority above this output-only preference.
				// Keep a required PRESENT boundary (e.g. a shared protected formal)
				// resident instead of gathering only to upload the same result again.
				if(row.preferLocalAggregate && row.domain.localConsumerOptions.stream().noneMatch(g -> g.live == 0))
					return fout ? 1 : 0;
				return fout ? 0 : 1;
			}
			if(!fed && !fout) return 2;
			// Uploads remain legal exact alternatives, but not speculative first preferences.
			if(fed && nativeOutput) return fout ? 3 : 4;
			return fed ? 5 : 6;
		}
		void dependency(Domain source, Domain target) {
			if(source != null && target != null && source != target) target.producers.add(source);
		}
		void require(Group dependent, Collection<Group> supports) {
			Requirement requirement = new Requirement(dependent);
			for(Group support : new LinkedHashSet<>(supports)) if(support != null && support.live > 0) {
				requirement.liveSupports++;
				support.watchers.add(requirement);
				incidences++;
			}
			if(requirement.liveSupports == 0) deletions.addAll(dependent.rows);
		}
		void indexConstraints() {
			for(var constraint : graph.constraints()) {
				Domain left = byKey.get(constraint.left()), right = byKey.get(constraint.right());
				if(left == null || right == null) continue;
				if(constraint.kind() == ConstraintKind.DOMINATES && "data-input".equals(constraint.evidence()))
					dependency(left, right);
				if(constraint.kind() != ConstraintKind.SAME_PLACEMENT
					&& constraint.kind() != ConstraintKind.SAME_VALUE_PLACEMENT
					&& constraint.kind() != ConstraintKind.FUNCTION_INPUT_TRANSFER
					&& constraint.kind() != ConstraintKind.SAME_FTYPE
					&& constraint.kind() != ConstraintKind.CONJUNCTIVE) continue;
				for(var l : left.states.entrySet()) {
					List<Group> supports = new ArrayList<>();
					for(var r : right.states.entrySet())
						if((left != right || l.getKey().equals(r.getKey()))
							&& NeutralPlacementGraph.constraintSatisfied(constraint, l.getKey(), r.getKey())) supports.add(r.getValue());
					require(l.getValue(), supports);
				}
				for(var r : right.states.entrySet()) {
					List<Group> supports = new ArrayList<>();
					for(var l : left.states.entrySet())
						if((left != right || l.getKey().equals(r.getKey()))
							&& NeutralPlacementGraph.constraintSatisfied(constraint, l.getKey(), r.getKey())) supports.add(l.getValue());
					require(r.getValue(), supports);
				}
			}
		}
		void indexInputs() {
			Set<InputPair> pairs = new LinkedHashSet<>();
			for(Domain consumer : domains) {
				for(Row row : consumer.rows) if(row.receipt != null) {
					for(var ref : row.receipt.supportClause().requiredInputSupport()) {
						Domain source = byKey.get(ref.rule().parentOccurrence());
						var prior = row.inputs.put(ref.rule().parentOccurrence(), ref);
						if(prior != null && !prior.equals(ref)) deletions.add(row);
						if(source == null) {
							// Non-decision constants are checked by the final common authority validator.
							continue;
						}
						if(source == consumer) {
							if(!ref.equals(row.reference)) deletions.add(row);
							continue;
						}
						dependency(source, consumer);
						if(!pairs.contains(new InputPair(consumer, source)))
							pairs.add(new InputPair(source, consumer));
					}
					var action = row.receipt.emission().derivedFoutAction();
					if(action != null) {
						Domain anchor = byKey.get(action.durableAnchorOwner());
						if(anchor != null) {
							dependency(anchor, consumer);
							List<Group> supports = new ArrayList<>();
							anchor.states.forEach((state, group) -> {
								if(state.output() == FederatedOutput.FOUT && state.fType() == action.durableAnchorOwnerFType())
									supports.add(group);
							});
							require(row.singleton, supports);
						}
					}
				}
			}
			// Register every row's input references before joining reciprocal edges.
			// Buckets avoid a Cartesian product of the two owned-row domains.
			for(InputPair pair : pairs) {
				var sources = new InputSupports(pair.source(), pair.consumer());
				var consumers = new InputSupports(pair.consumer(), pair.source());
				for(Row row : pair.consumer().rows)
					require(row.singleton, sources.compatible(row, pair.source()));
				for(Row row : pair.source().rows)
					require(row.singleton, consumers.compatible(row, pair.consumer()));
			}
		}
		void indexPhysicalInputs() {
			// Legacy unbound rows also need physical support. A relocation-backed
			// input is joined by the source's exact owned pool, not only its FType.
			var privacy = RelocationSelections.relocationPrivacyIndex(analysis, graph, graph.relocationActions());
			Map<Domain,Residencies> indexedPools = new IdentityHashMap<>();
			Map<CompiledHopKey,Map<Integer,List<NeutralPlacementGraph.RelocationAction>>> actions = new IdentityHashMap<>();
			Map<CompiledHopKey,Set<RelocationInput>> unsafeRelocations = new IdentityHashMap<>();
			for(var action : graph.relocationActions()) {
				boolean unsafe = !privacy.isPrivacySafe(action, true);
				for(var obligation : action.obligations()) {
					actions.computeIfAbsent(obligation.consumer(), ignored -> new HashMap<>())
						.computeIfAbsent(obligation.inputPosition(), ignored -> new ArrayList<>()).add(action);
					if(unsafe) unsafeRelocations.computeIfAbsent(obligation.consumer(), ignored -> new LinkedHashSet<>())
						.add(new RelocationInput(action.key(), obligation.inputPosition(), obligation.requiredPlacement()));
				}
			}
			// An exact RELOCATION binding forces emission even if its source also has
			// the requested FType. Apply the same privacy gate as final relocation
			// validation before an invalid row can force other greedy commitments.
			for(Domain consumer : domains) for(Row row : consumer.rows) if(row.receipt != null)
				for(var binding : row.receipt.supportClause().inputBindings())
					if(binding.kind() == CandidateInputBindingKind.RELOCATION
						&& unsafeRelocations.getOrDefault(consumer.node.key(), Set.of()).contains(
							new RelocationInput(binding.relocationAction(), binding.inputPosition(), row.state)))
						deletions.add(row);
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(PlacementCostSemantics.isLatentWdivmmTransposePairBoundary(analysis,
					edge.producer(), edge.consumer(), edge.inputPosition())) continue;
				Domain source = byKey.get(edge.producer()), consumer = byKey.get(edge.consumer());
				if(source == null || consumer == null || source == consumer) continue;
				dependency(source, consumer);
				Group localOptions = new Group(), wildcard = new Group();
				source.localConsumerOptions.add(localOptions);
				Residencies pools = indexedPools.computeIfAbsent(source, Residencies::new);
				Map<PlacementState,Group> acceptedStates = new HashMap<>();
				Map<Residency,Group> acceptedPools = new HashMap<>();
				Map<MaterializedPool,Group> acceptedMaterialized = new HashMap<>();
				for(Row row : consumer.rows) {
					boolean present = row.receipt != null && row.state.execType() == ExecType.FED
						&& edge.inputPosition() < row.receipt.rule().orderedInputs().size()
						&& row.receipt.rule().orderedInputs().get(edge.inputPosition()).present();
					if(!present) localOptions.add(row);
					if(!present || analysis.isDmlFunctionCallBoundary(consumer.node.key())
						|| row.receipt.supportClause().inputBindings().stream()
							.anyMatch(binding -> binding.inputPosition() == edge.inputPosition())) {
						wildcard.add(row);
						continue;
					}
					FType required = row.receipt.rule().orderedInputs().get(edge.inputPosition()).fType();
					row.physicalInputs.add(new PhysicalInput(source, required));
					Set<NeutralPlacementGraph.RelocationAction> matching = new LinkedHashSet<>();
					for(var action : actions.getOrDefault(consumer.node.key(), Map.of()).getOrDefault(edge.inputPosition(), List.of()))
						if(action.obligations().stream().anyMatch(obligation -> obligation.consumer() == consumer.node.key()
							&& obligation.inputPosition() == edge.inputPosition()
							&& CandidateSelections.actionMatchesSelectedCandidate(action, obligation, row.receipt))) matching.add(action);
					List<Group> support = new ArrayList<>();
					if(matching.isEmpty()) {
						// No relocation demand: the common unary/parametric direct-input
						// predicate requires FOUT/type. Final validation checks its full chain.
						for(var entry : source.states.entrySet()) if(entry.getKey().output() == FederatedOutput.FOUT
							&& entry.getKey().fType() == required) {
							support.add(entry.getValue());
							acceptedStates.computeIfAbsent(entry.getKey(), ignored -> new Group()).add(row);
						}
					}
					else if(matching.stream().anyMatch(action -> privacy.isPrivacySafe(action, true)
						|| !action.key().sourceValueVersion().equals(source.node.valueVersion()))) {
						// A releasable upload (or a different reaching value owner) is
						// not ruled out by this input's direct-source projection.
						support.add(source.all);
						wildcard.add(row);
					}
					else for(var action : matching) {
						var pool = PlacementIdentity.physicalWorkerPoolIdentity(action.key().durableAnchor());
						for(PlacementState state : action.directSourcePlacements()) {
							Residency residency = new Residency(state, pool);
							support.add(pools.nativePools.get(residency));
							support.add(pools.noReceipt.get(state));
							acceptedPools.computeIfAbsent(residency, ignored -> new Group()).add(row);
						}
						var materialized = new MaterializedPool(action.key().materializationFType(), pool);
						support.add(pools.materialized.get(materialized));
						acceptedMaterialized.computeIfAbsent(materialized, ignored -> new Group()).add(row);
					}
					require(row.singleton, support);
				}
				for(Row row : source.rows) {
					List<Group> support = new ArrayList<>();
					support.add(wildcard);
					support.add(acceptedStates.get(row.state));
					support.add(acceptedPools.get(residency(row)));
					support.add(acceptedMaterialized.get(materializedPool(row)));
					if(row.receipt == null)
						acceptedPools.forEach((residency, group) -> { if(residency.state().equals(row.state)) support.add(group); });
					require(row.singleton, support);
				}
			}
		}
		void indexTransients() {
			for(var fact : analysis.logicalTransientInputsInCanonicalOrder()) {
				Domain source = byKey.get(fact.sourceWrite()), target = byKey.get(fact.targetRead());
				if(source == null || target == null) continue;
				dependency(source, target);
				Map<CandidateRealizationReference,Set<Group>> forward = new HashMap<>(), reverse = new HashMap<>();
				for(var edge : fact.compatibility()) {
					if(source == target && !edge.sourceRealization().equals(edge.readerRealization())) continue;
					forward.computeIfAbsent(edge.sourceRealization(), ignored -> new LinkedHashSet<>())
						.add(target.references.get(edge.readerRealization()));
					reverse.computeIfAbsent(edge.readerRealization(), ignored -> new LinkedHashSet<>())
						.add(source.references.get(edge.sourceRealization()));
				}
				source.references.forEach((ref, group) -> require(group, forward.getOrDefault(ref, Set.of())));
				target.references.forEach((ref, group) -> require(group, reverse.getOrDefault(ref, Set.of())));
			}
		}
		Pools pools(Domain domain, boolean targets) {
			Pools pools = new Pools();
			for(Row row : domain.rows) {
				if(row.receipt == null) continue;
				boolean nativeTarget = row.state.execType() == ExecType.FED
					&& row.state.output() == FederatedOutput.FOUT && !row.receipt.emission().emissionState().derivedFedFout();
				if(targets && !nativeTarget) { pools.nonNative.add(row); continue; }
				var realization = row.receipt.realization();
				var clause = row.receipt.supportClause();
				var anchor = realization.nativeWorkerPoolResidencyWitness(clause);
				if(anchor == null) continue;
				var endpoints = PlacementIdentity.physicalWorkerEndpointIdentity(anchor);
				if(endpoints != null) pools.endpoints.computeIfAbsent(endpoints, ignored -> new Group()).add(row);
				if(realization.nativeWorkerPoolLayoutExact(clause))
					pools.exact.computeIfAbsent(PlacementIdentity.physicalWorkerPoolIdentity(anchor), ignored -> new Group()).add(row);
				else if(endpoints != null) pools.inexact.computeIfAbsent(endpoints, ignored -> new Group()).add(row);
			}
			return pools;
		}
		List<Group> poolSupports(Row row, Pools other, boolean allowNonNative) {
			List<Group> supports = new ArrayList<>();
			if(allowNonNative) supports.add(other.nonNative);
			if(row.receipt == null) return supports;
			var realization = row.receipt.realization();
			var clause = row.receipt.supportClause();
			var anchor = realization.nativeWorkerPoolResidencyWitness(clause);
			if(anchor == null) return supports;
			var endpoints = PlacementIdentity.physicalWorkerEndpointIdentity(anchor);
			if(realization.nativeWorkerPoolLayoutExact(clause)) {
				supports.add(other.exact.get(PlacementIdentity.physicalWorkerPoolIdentity(anchor)));
				if(endpoints != null) supports.add(other.inexact.get(endpoints));
			}
			else if(endpoints != null) supports.add(other.endpoints.get(endpoints));
			return supports;
		}
		void indexBoundaries() {
			for(Domain target : domains)
				for(var source : analysis.logicalBoundaryRealizations().sources(target.node.key()))
					dependency(byKey.get(source), target);
			Map<Domain,Pools> sources = new IdentityHashMap<>(), targets = new IdentityHashMap<>();
			for(var relation : analysis.logicalBoundaryRealizations().relations()) {
				Domain source = byKey.get(relation.source()), target = byKey.get(relation.target());
				if(source == null || target == null) continue;
				dependency(source, target);
				Pools s = sources.computeIfAbsent(source, d -> pools(d, false));
				Pools t = targets.computeIfAbsent(target, d -> pools(d, true));
				for(Row row : target.rows) if(row.receipt != null && row.state.execType() == ExecType.FED
					&& row.state.output() == FederatedOutput.FOUT && !row.receipt.emission().emissionState().derivedFedFout())
					require(row.singleton, poolSupports(row, s, false));
				for(Row row : source.rows) require(row.singleton, poolSupports(row, t, true));
			}
		}
		void propagate() {
			while(!deletions.isEmpty()) {
				Row row = deletions.removeFirst();
				if(!row.active) continue;
				row.active = false;
				deleted++;
				for(Group group : row.memberships) {
					events++;
					if(--group.live == 0)
						for(Requirement requirement : group.watchers) {
							events++;
							if(--requirement.liveSupports == 0) deletions.addAll(requirement.dependent.rows);
						}
				}
				if(row.domain.all.live == 0) throw conflict(row.domain);
			}
		}
		GreedyConflictException conflict(Domain domain) {
			return new GreedyConflictException("empty owned-row domain at " + domain.node.key().normalizedSignature()
				+ "; commits=" + commits + "; deletedRows=" + deleted);
		}
		PlacementSelection finish() {
			Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>();
			List<CandidateSelectionReceipt> receipts = new ArrayList<>();
			int fed = 0, fout = 0, upperFed = 0, upperFout = 0;
			for(Domain domain : domains) {
				Row selected = domain.selected;
				assignment.put(domain.node.key(), selected.state);
				if(selected.receipt != null) receipts.add(selected.receipt);
				if(selected.state.execType() == ExecType.FED) fed++;
				if(selected.state.output() == FederatedOutput.FOUT) fout++;
				if(domain.node.legalAlternatives().stream().anyMatch(s -> s.execType() == ExecType.FED)) upperFed++;
				if(domain.node.legalAlternatives().stream().anyMatch(s -> s.output() == FederatedOutput.FOUT)) upperFout++;
			}
			for(var edge : graph.constraints()) {
				var left = assignment.get(edge.left());
				var right = assignment.get(edge.right());
				if(left != null && right != null && !NeutralPlacementGraph.constraintSatisfied(edge, left, right))
					throw new IllegalStateException("selected constraint: " + edge.normalizedSignature());
			}
			if(analysis != null)
				receipts = CandidateSelections.resolveAndValidateSelected(analysis, graph, assignment, receipts);
			List<AuditDemandOptions> demands = analysis == null ? RelocationSelections.auditDemandOptions(graph, assignment)
				: RelocationSelections.auditDemandOptions(analysis, assignment, receipts);
			List<RelocationChoiceReceipt> choices = choosePools(demands);
			if(analysis != null) CandidateSelections.validateRealizationSelections(analysis, assignment, receipts, choices);
			if(analysis != null) validateSelectedGrounding();
			List<RelocationActionKey> actions = analysis == null ? RelocationSelections.emittedActions(graph, assignment, choices)
				: RelocationSelections.emittedActions(analysis, assignment, receipts, choices);
			int movement = RelocationSelections.physicalEmissionCount(actions);
			if(analysis != null) movement = Math.addExact(movement, Math.addExact(
				LocalMaterializationSelections.physicalEmissionCount(analysis, assignment, receipts),
				CandidateSelections.foutMaterializationPhysicalEmissionCount(receipts)));
			List<String> signature = new ArrayList<>();
			assignment.forEach((key, state) -> signature.add(key.normalizedSignature() + '=' + state.normalizedSignature()));
			receipts.forEach(receipt -> signature.add(receipt.normalizedSignature()));
			choices.forEach(choice -> signature.add(choice.normalizedSignature()));
			signature.sort(String::compareTo);
			PlacementScore score = new PlacementScore(fed, fout, movement, String.join("\n", signature));
			PlacementScore upper = new PlacementScore(upperFed, upperFout, 0, "");
			Set<String> nodes = new LinkedHashSet<>();
			graph.nodes().forEach(node -> nodes.add(node.key().normalizedSignature()));
			String derivation = "monotone-owned-row-greedy-" + policy.name().toLowerCase(java.util.Locale.ROOT);
			List<ComponentBound> bounds = List.of(new ComponentBound("whole-graph-envelope", nodes,
				graph.nodes().size(), graph.constraints().size(), upper, "structural-envelope-not-an-optimality-proof"));
			PlacementCertificate certificate = new PlacementCertificate(score, upper, 1, 0,
				sha256(score.normalizedSignature()), sha256(graph.normalizedSignature()), graph.nodes().size(),
				graph.constraints().size(), 1, 0, bounds, derivation, "policy", -1, TerminationReason.POLICY_FEASIBLE);
			return new PlacementSelection(assignment, receipts, choices, new LinkedHashSet<>(actions), score, certificate);
		}

		void validateSelectedGrounding() {
			Map<Domain,Set<Domain>> proof = new LinkedHashMap<>();
			for(Domain domain : domains) proof.put(domain, new LinkedHashSet<>());
			for(Domain domain : domains)
				for(var reference : domain.selected.inputs.values()) {
					Domain source = byKey.get(reference.rule().parentOccurrence());
					if(source != null) proof.get(domain).add(source);
				}
			// The common validator has checked the selected exact rows, slots, pools,
			// and every reaching writer. Do not use the union of unselected OR clauses
			// or generic SAME_PLACEMENT edges as a seed for the selected value graph.
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
				if(PlacementCostSemantics.isLatentWdivmmTransposePairBoundary(analysis,
					edge.producer(), edge.consumer(), edge.inputPosition())) continue;
				Domain target = byKey.get(edge.consumer());
				if(target != null && (target.selected.receipt == null
					|| target.selected.receipt.supportClause().inputBindings().isEmpty()))
					addSelectedInput(proof, edge.producer(), edge.consumer());
			}
			for(Domain target : domains) for(PhysicalInput input : target.selected.physicalInputs)
				proof.get(target).add(input.source());
			for(var fact : analysis.logicalTransientInputsInCanonicalOrder())
				addSelectedInput(proof, fact.sourceWrite(), fact.targetRead());
			// Local function/result boundaries carry values too. The pool-only
			// relations() projection intentionally omits all-local targets, so use
			// the complete analysis-owned value-source relation for grounding.
			for(Domain target : domains)
				for(var source : analysis.logicalBoundaryRealizations().sources(target.node.key()))
					addSelectedInput(proof, source, target.node.key());
			// A durable placement anchor alone cannot initialize a cyclic value.
			// Anchor/action authority is checked above, but is deliberately not a value seed.
			requireGroundedComponents(proof);
		}
		void addSelectedInput(Map<Domain,Set<Domain>> proof, CompiledHopKey sourceKey, CompiledHopKey targetKey) {
			Domain source = byKey.get(sourceKey), target = byKey.get(targetKey);
			if(source != null && target != null) proof.get(target).add(source);
		}
	}

	static boolean prefersLocalAggregate(Hop hop, PlacementAnalysis.AbstractShapeFact shape, FType executionType) {
		return hop instanceof AggBinaryOp && shape != null && (executionType == FType.ROW && shape.provablyColumnVector()
			|| executionType == FType.COL && shape.provablyRowVector()
			|| executionType == FType.FULL && shape.provablyVector());
	}

	/** Iterative Kosaraju; identity-preserving dependency-first SCCs, no search or recursion. */
	static <T> List<List<T>> dependencyComponents(Map<T,Set<T>> dependencies) {
		Map<T,Set<T>> reverse = new IdentityHashMap<>();
		Map<T,Integer> ordinal = new IdentityHashMap<>();
		for(T value : dependencies.keySet()) {
			reverse.put(value, new LinkedHashSet<>());
			ordinal.put(value, ordinal.size());
		}
		dependencies.forEach((owner, inputs) -> inputs.forEach(input -> reverse.get(input).add(owner)));
		Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<T> completed = new ArrayList<>();
		record Frame<T>(T value, Iterator<T> inputs) { }
		ArrayDeque<Frame<T>> stack = new ArrayDeque<>();
		for(T root : dependencies.keySet()) if(seen.add(root)) {
			stack.push(new Frame<>(root, dependencies.get(root).iterator()));
			while(!stack.isEmpty()) {
				Frame<T> frame = stack.peek();
				if(frame.inputs().hasNext()) {
					T input = frame.inputs().next();
					if(seen.add(input)) stack.push(new Frame<>(input, dependencies.get(input).iterator()));
				}
				else { completed.add(frame.value()); stack.pop(); }
			}
		}
		seen.clear();
		List<List<T>> components = new ArrayList<>();
		ArrayDeque<T> pending = new ArrayDeque<>();
		for(int index = completed.size() - 1; index >= 0; index--) {
			T root = completed.get(index);
			if(!seen.add(root)) continue;
			List<T> component = new ArrayList<>();
			pending.add(root);
			while(!pending.isEmpty()) {
				T value = pending.removeFirst();
				component.add(value);
				for(T next : reverse.get(value)) if(seen.add(next)) pending.addLast(next);
			}
			component.sort(java.util.Comparator.comparingInt(ordinal::get));
			components.add(component);
		}
		Collections.reverse(components);
		return components;
	}

	static <T> void requireGroundedComponents(Map<T,Set<T>> selectedInputs) {
		for(List<T> component : dependencyComponents(selectedInputs)) {
			boolean cyclic = component.size() > 1 || selectedInputs.get(component.get(0)).contains(component.get(0));
			if(!cyclic) continue;
			Set<T> members = Collections.newSetFromMap(new IdentityHashMap<>());
			members.addAll(component);
			boolean entry = component.stream().anyMatch(value -> selectedInputs.get(value).stream()
				.anyMatch(input -> !members.contains(input)));
			if(!entry) throw new UnresolvedBoundaryContractException("selected proof SCC has no external value entry; size="
				+ component.size() + "; first=" + component.get(0));
		}
	}

	/** Independent consumer pool intersection, never a Cartesian product of action choices. */
	static List<RelocationChoiceReceipt> choosePools(List<AuditDemandOptions> demands) {
		Map<CompiledHopKey,List<AuditDemandOptions>> consumers = new LinkedHashMap<>();
		for(var demand : demands) consumers.computeIfAbsent(demand.demand().consumer(), ignored -> new ArrayList<>()).add(demand);
		List<RelocationChoiceReceipt> result = new ArrayList<>();
		for(var entry : consumers.entrySet()) {
			List<Map<WorkerPoolIdentity,RelocationChoiceReceipt>> indexed = new ArrayList<>();
			Set<WorkerPoolIdentity> common = null;
			for(var demand : entry.getValue()) {
				Map<WorkerPoolIdentity,RelocationChoiceReceipt> options = new LinkedHashMap<>();
				for(var choice : demand.choices())
					options.putIfAbsent(PlacementIdentity.physicalWorkerPoolIdentity(choice.action().durableAnchor()), choice);
				indexed.add(options);
				if(common == null) common = new LinkedHashSet<>(options.keySet());
				else common.retainAll(options.keySet());
			}
			if(common == null || common.isEmpty()) throw new GreedyConflictException("no common relocation pool at " + entry.getKey());
			WorkerPoolIdentity pool = common.iterator().next();
			for(var options : indexed) result.add(options.get(pool));
		}
		return List.copyOf(result);
	}
	private static String sha256(String text) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
		catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
	}
}
