/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.wink.json4j.JSONObject;
import org.apache.wink.json4j.JSONException;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Conservative proof that native FED/FOUT execution preserves one physical worker pool. */
final class NativePlacementContinuity {
	private static final Set<String> NATIVE_UNARY_ELEMWISE_OPCODES =
		Set.copyOf(new Rulesets.UnaryElemwiseRule().opcodes());
	private static final Set<String> NATIVE_UNARY_CUMULATIVE_OPCODES =
		Set.copyOf(new Rulesets.UnaryCumulativeRule().opcodes());
	private static final Set<String> NATIVE_BINARY_ELEMWISE_OPCODES =
		Set.copyOf(new Rulesets.BinaryElemwiseRule().opcodes());
	private final StructuralContext structuralContext;
	private final Map<CompiledHopKey,Node> nodesByKey;
	private final Map<CompiledHopKey,Hop> originsByKey;
	private final List<CandidateRuleFact> candidateFacts;
	private final Map<CompiledHopKey,List<CandidateRuleFact>> candidateFactsByKey;
	private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> edgesByConsumer;
	private final Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions;
	private final PlacementDependencyComponents occurrenceComponents;
	private final Set<CompiledHopKey> incompleteSources;
	private final Map<CompiledHopKey,Privacy> privacyByKey;
	private final Map<CandidateRealizationSupportClause,List<CandidateRealizationReference>>
		requiredInputSupportByClause = new IdentityHashMap<>();
	private final Map<DurableAnchorKey,NativePoolWitness> nativeWitnessByAnchor = new IdentityHashMap<>();
	private final Map<String,String> canonicalEndpointByWorker = new java.util.HashMap<>();
	private final Map<CandidateRealizationReference,Integer> candidateHandleByReference =
		new IdentityHashMap<>();
	private final Map<CandidateRealizationReference,Integer> candidateHandleByStructure =
		new java.util.HashMap<>();
	// Analysis-arena handles are positive. Overflow-local handles use a disjoint
	// negative namespace so a bounded arena can never alias two references.
	private int nextCandidateHandle = -1;
	private final Map<CandidateTopologyKey,CandidateTopology> candidateTopologies =
		new java.util.LinkedHashMap<>(16, 0.75f, true);
	private final int topologyMaxEntries;
	private final long topologyMaxRows;
	private long topologyRetainedRows;
	private final SearchSpaceMetrics metrics;
	// The observer is revision-local. CandidateQueryKey additionally retains the
	// complete rule/emission/product reference and exact worker/layout witness.
	private final SearchSpaceMetrics.BoundedContextObserver<CandidateQueryKey> observedQueries;
	private final int memoMaxEntries;
	private final long memoMaxProofs;
	private final long memoMaxEstimatedBytes;
	private final Map<PublicCandidateQueryKey,MemoEntry> completedProofMemo;
	private long memoRetainedProofs;
	private long memoRetainedEstimatedBytes;
	private final int supportMemoMaxEntries;
	private final long supportMemoMaxTemplates;
	private final long supportMemoMaxEstimatedBytes;
	private final Map<CandidateSupportQueryKey,SupportMemoEntry> completedSupportMemo;
	private final Map<AcyclicRootSupportKey,SupportMemoEntry> acyclicRootSupportMemo;
	private long supportMemoRetainedTemplates;
	private long supportMemoRetainedEstimatedBytes;
	// Acyclic child components retain one immutable exact relation DAG while their
	// grounded boundary rows provide the fast path. The complete footprint guards
	// the query-local root pin and revision invalidation.
	private final Map<CandidateProofState,AcyclicComponentSummary> acyclicComponentMemo;
	private final int acyclicComponentMaxEntries;
	private final long acyclicComponentMaxStates;
	private final long acyclicComponentMaxAlternatives;
	private long acyclicComponentRetainedStates;
	private long acyclicComponentRetainedAlternatives;

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions, Set.of(), Map.of(), null);
	}

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		SearchSpaceMetrics metrics) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions, Set.of(), Map.of(), metrics);
	}

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions,
			incompleteSources, Map.of(), null);
	}

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions,
			incompleteSources, privacyByKey, null);
	}

	private NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey,
		SearchSpaceMetrics metrics) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions,
			incompleteSources, privacyByKey, metrics,
			Integer.getInteger("sysds.fedplanner.continuityMemo.maxEntries", 4096),
			Long.getLong("sysds.fedplanner.continuityMemo.maxProofs", 65536L),
			Long.getLong("sysds.fedplanner.continuityMemo.maxEstimatedBytes", 64L * 1024 * 1024));
	}

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey,
		SearchSpaceMetrics metrics, int memoMaxEntries, long memoMaxProofs) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions,
			incompleteSources, privacyByKey, metrics, memoMaxEntries, memoMaxProofs,
			16L * 1024 * 1024);
	}

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey,
		SearchSpaceMetrics metrics, int memoMaxEntries, long memoMaxProofs,
		long memoMaxEstimatedBytes) {
		this(new StructuralContext(nodesByKey, originsByKey, compiledEdges, reachingDefinitions,
			incompleteSources, privacyByKey), candidateFacts, metrics, memoMaxEntries,
			memoMaxProofs, memoMaxEstimatedBytes);
	}

	private NativePlacementContinuity(StructuralContext structuralContext,
		List<CandidateRuleFact> candidateFacts, SearchSpaceMetrics metrics,
		int memoMaxEntries, long memoMaxProofs, long memoMaxEstimatedBytes) {
		this.structuralContext = Objects.requireNonNull(structuralContext, "structuralContext");
		nodesByKey = structuralContext.nodesByKey;
		originsByKey = structuralContext.originsByKey;
		privacyByKey = structuralContext.privacyByKey;
		reachingDefinitions = structuralContext.reachingDefinitions;
		incompleteSources = structuralContext.incompleteSources;
		edgesByConsumer = structuralContext.edgesByConsumer;
		occurrenceComponents = structuralContext.occurrenceComponents;
		this.metrics = metrics;
		int observedQueryLimit = Math.max(0,
			Integer.getInteger("sysds.fedplanner.metrics.maxExactContexts", 4096));
		observedQueries = metrics == null ? null
			: new SearchSpaceMetrics.BoundedContextObserver<>(observedQueryLimit);
		this.memoMaxEntries = Math.max(0, memoMaxEntries);
		this.memoMaxProofs = Math.max(0, memoMaxProofs);
		this.memoMaxEstimatedBytes = Math.max(0, memoMaxEstimatedBytes);
		boolean resultCachingEnabled = this.memoMaxEntries > 0 && this.memoMaxProofs > 0
			&& this.memoMaxEstimatedBytes > 0;
		supportMemoMaxEntries = resultCachingEnabled ? Math.max(0,
			Integer.getInteger("sysds.fedplanner.continuitySupportMemo.maxEntries", 4096)) : 0;
		supportMemoMaxTemplates = resultCachingEnabled ? Math.max(0,
			Long.getLong("sysds.fedplanner.continuitySupportMemo.maxTemplates", 131072L)) : 0;
		supportMemoMaxEstimatedBytes = resultCachingEnabled ? Math.max(0,
			Long.getLong("sysds.fedplanner.continuitySupportMemo.maxEstimatedBytes", 16L * 1024 * 1024)) : 0;
		acyclicComponentMaxEntries = resultCachingEnabled ? Math.max(0,
			Integer.getInteger("sysds.fedplanner.continuityAcyclicComponent.maxEntries", 4096)) : 0;
		acyclicComponentMaxStates = resultCachingEnabled ? Math.max(0,
			Long.getLong("sysds.fedplanner.continuityAcyclicComponent.maxStates", 131072L)) : 0;
		acyclicComponentMaxAlternatives = resultCachingEnabled ? Math.max(0,
			Long.getLong("sysds.fedplanner.continuityAcyclicComponent.maxAlternatives", 32768L)) : 0;
		topologyMaxEntries = Math.max(0,
			Integer.getInteger("sysds.fedplanner.continuityTopology.maxEntries", 2048));
		topologyMaxRows = Math.max(0,
			Long.getLong("sysds.fedplanner.continuityTopology.maxRows", 131072L));
		completedProofMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		completedSupportMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		acyclicRootSupportMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		acyclicComponentMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		this.candidateFacts = List.copyOf(Objects.requireNonNull(candidateFacts, "candidateFacts"));
		candidateFactsByKey = new IdentityHashMap<>();
		for(CandidateRuleFact fact : this.candidateFacts)
			candidateFactsByKey.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
		candidateFactsByKey.replaceAll((ignored, facts) -> List.copyOf(facts));
	}

	/** Returns an exact resolver with no query-local history. */
	NativePlacementContinuity freshQueryState() {
		return new NativePlacementContinuity(structuralContext, candidateFacts, metrics,
			memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
	}

	/**
	 * Starts a fact revision. The nodes, compiled edges, reaching definitions,
	 * privacy and Hops remain unchanged. Only the candidate facts may change; explanatory
	 * proof dependencies do not change this private execution relation.
	 */
	NativePlacementContinuity nextRevision(List<CandidateRuleFact> candidateFacts) {
		NativePlacementContinuity next = new NativePlacementContinuity(structuralContext,
			candidateFacts, metrics, memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
		// Reuse is governed by the complete private execution projection, so
		// proof-history-only changes do not force recomputation while any changed
		// execution row still invalidates its cached supports.
		Map<CompiledHopKey,Boolean> unchangedRows = new IdentityHashMap<>();
		long reused = 0;
		for(var entry : candidateTopologies.entrySet()) {
			CompiledHopKey occurrence = entry.getKey().occurrence;
			if(!unchangedRows.computeIfAbsent(occurrence, key -> sameContinuityFacts(next, key)))
				continue;
			if(next.cacheTopology(entry.getKey(), next.reindexTopology(entry.getValue())))
				reused++;
		}
		long supportReused = 0;
		for(var entry : completedSupportMemo.entrySet()) {
			SupportMemoEntry support = entry.getValue();
			boolean unchanged = support.occurrences.stream().noneMatch(occurrence ->
				!unchangedRows.computeIfAbsent(occurrence, key -> sameContinuityFacts(next, key)));
			if(!unchanged)
				continue;
			CandidateSupportQueryKey nextKey = next.candidateSupportQueryKey(
				support.root, entry.getKey().witness, entry.getKey().generated);
			next.cacheCompletedSupports(nextKey, support);
			if(next.completedSupportMemo.containsKey(nextKey))
				supportReused++;
		}
		for(var entry : acyclicRootSupportMemo.entrySet()) {
			SupportMemoEntry support = entry.getValue();
			boolean unchanged = support.occurrences.stream().noneMatch(occurrence ->
				!unchangedRows.computeIfAbsent(occurrence, key -> sameContinuityFacts(next, key)));
			if(unchanged)
				next.cacheAcyclicRootSupport(entry.getKey(), support);
		}
		for(var entry : completedProofMemo.entrySet()) {
			MemoEntry proof = entry.getValue();
			boolean unchanged = proof.occurrences().stream().noneMatch(occurrence ->
				!unchangedRows.computeIfAbsent(occurrence, key -> sameContinuityFacts(next, key)));
			if(unchanged)
				next.cacheCompletedProofs(entry.getKey(), proof);
		}
		for(var entry : acyclicComponentMemo.entrySet()) {
			AcyclicComponentSummary summary = entry.getValue();
			boolean unchanged = summary.occurrences.stream().noneMatch(occurrence ->
				!unchangedRows.computeIfAbsent(occurrence, key -> sameContinuityFacts(next, key)));
			if(!unchanged)
				continue;
			CandidateProofState state = entry.getKey();
			CandidateProofState rebound = new CandidateProofState(state.key(), state.realization(),
				next.candidateHandle(state.realization()), state.witness(), state.templateRoot());
			next.cacheAcyclicSummary(rebound, summary);
		}
		if(metrics != null) {
			metrics.recordTopologyRevisionReuse(reused);
			metrics.recordSupportMemoRevisionReuse(supportReused);
		}
		return next;
	}

	/**
	 * Returns whether the supplied program structure is exactly the immutable structure owned by
	 * this resolver. Candidate facts are deliberately excluded: callers may use {@link #nextRevision(List)}
	 * only after this complete identity-sensitive boundary succeeds.
	 */
	boolean matchesStructuralContext(Map<CompiledHopKey,Node> nodes,
		Map<CompiledHopKey,Hop> origins, List<CompiledInputEdgeFact> edges,
		Map<CompiledHopKey,List<CompiledHopKey>> reaching, Set<CompiledHopKey> incomplete,
		Map<CompiledHopKey,Privacy> privacy) {
		return structuralContext.matches(nodes, origins, edges, reaching, incomplete, privacy);
	}

	private boolean sameContinuityFacts(NativePlacementContinuity next, CompiledHopKey occurrence) {
		List<CandidateRuleFact> before = candidateFactsByKey.getOrDefault(occurrence, List.of());
		List<CandidateRuleFact> after = next.candidateFactsByKey.getOrDefault(occurrence, List.of());
		return before.equals(after) || continuityProjection(before).equals(continuityProjection(after));
	}

	private static Set<ContinuityFactProjection> continuityProjection(List<CandidateRuleFact> facts) {
		Set<ContinuityFactProjection> projected = new java.util.HashSet<>();
		for(CandidateRuleFact fact : facts) {
			Set<ContinuityEmissionProjection> emissions = new java.util.HashSet<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				Set<ContinuityRealizationProjection> realizations = new java.util.HashSet<>();
				for(CandidateEmissionRealization realization : emission.realizations()) {
					Set<ContinuityClauseProjection> clauses = new java.util.HashSet<>();
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						List<ContinuityBindingProjection> bindings = clause.inputBindings().stream()
							.map(ContinuityBindingProjection::new).toList();
						clauses.add(new ContinuityClauseProjection(bindings,
							clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact()));
					}
					realizations.add(new ContinuityRealizationProjection(realization.key(), clauses));
				}
				emissions.add(new ContinuityEmissionProjection(emission.emissionState(),
					emission.executionFType(), emission.derivedFoutAction(), realizations));
			}
			projected.add(new ContinuityFactProjection(fact.key(), fact.status(), emissions));
		}
		return projected;
	}

	private record ContinuityFactProjection(CandidateRuleKey key, CandidateEvaluationStatus status,
		Set<ContinuityEmissionProjection> emissions) { }
	private record ContinuityEmissionProjection(PlacementEmissionState state, FType executionFType,
		DerivedFoutMaterializationActionKey action,
		Set<ContinuityRealizationProjection> realizations) { }
	private record ContinuityRealizationProjection(PlacementRealizationKey key,
		Set<ContinuityClauseProjection> clauses) { }
	private record ContinuityClauseProjection(List<ContinuityBindingProjection> bindings,
		DurableAnchorKey nativeWorkerPoolWitness, boolean nativeWorkerPoolLayoutExact) { }
	private static final class ContinuityBindingProjection {
		private final CandidateRealizationInputBinding binding;
		private final CompiledHopKey owner;
		private final int hashCode;

		private ContinuityBindingProjection(CandidateRealizationInputBinding binding) {
			this.binding = binding;
			owner = binding.source().rule().parentOccurrence();
			hashCode = 31 * binding.hashCode() + System.identityHashCode(owner);
		}

		@Override public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof ContinuityBindingProjection that
				&& owner == that.owner && binding.equals(that.binding);
		}
	}

	/**
	 * Immutable program structure shared by every fact revision. Construction is
	 * the sole validation boundary for compiled edges and the sole SCC build.
	 */
	private static final class StructuralContext {
		private final Map<CompiledHopKey,Node> nodesByKey;
		private final Map<CompiledHopKey,Hop> originsByKey;
		private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> edgesByConsumer;
		private final Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions;
		private final Set<CompiledHopKey> incompleteSources;
		private final Map<CompiledHopKey,Privacy> privacyByKey;
		private final PlacementDependencyComponents occurrenceComponents;

		private StructuralContext(Map<CompiledHopKey,Node> nodesByKey,
			Map<CompiledHopKey,Hop> originsByKey, List<CompiledInputEdgeFact> compiledEdges,
			Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
			Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey) {
			this.nodesByKey = immutableIdentityMap(nodesByKey, "nodesByKey");
			this.originsByKey = immutableIdentityMap(originsByKey, "originsByKey");
			this.privacyByKey = immutableIdentityMap(privacyByKey, "privacyByKey");
			this.reachingDefinitions = immutableIdentityLists(
				reachingDefinitions, "reachingDefinitions");
			Set<CompiledHopKey> incomplete = Collections.newSetFromMap(new IdentityHashMap<>());
			incomplete.addAll(Objects.requireNonNull(incompleteSources, "incompleteSources"));
			this.incompleteSources = Collections.unmodifiableSet(incomplete);

			Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> indexed = new IdentityHashMap<>();
			for(CompiledInputEdgeFact edge : List.copyOf(
				Objects.requireNonNull(compiledEdges, "compiledEdges"))) {
				Map<Integer,CompiledInputEdgeFact> positions = indexed.computeIfAbsent(
					edge.consumer(), ignored -> new java.util.LinkedHashMap<>());
				if(positions.put(edge.inputPosition(), edge) != null)
					throw new IllegalArgumentException("Duplicate compiled input edge position");
			}
			indexed.replaceAll((ignored, positions) -> Collections.unmodifiableMap(positions));
			edgesByConsumer = Collections.unmodifiableMap(indexed);

			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			owners.addAll(this.nodesByKey.keySet());
			List<PlacementDependencyComponents.SemanticDependency> dependencies = new ArrayList<>();
			for(Map<Integer,CompiledInputEdgeFact> inputs : edgesByConsumer.values())
				for(CompiledInputEdgeFact edge : inputs.values()) {
					owners.add(edge.producer());
					owners.add(edge.consumer());
					dependencies.add(new PlacementDependencyComponents.SemanticDependency(
						edge.producer(), edge.consumer()));
				}
			for(var entry : this.reachingDefinitions.entrySet()) {
				owners.add(entry.getKey());
				for(CompiledHopKey producer : entry.getValue()) {
					owners.add(producer);
					dependencies.add(new PlacementDependencyComponents.SemanticDependency(
						producer, entry.getKey()));
				}
			}
			occurrenceComponents = new PlacementDependencyComponents(
				List.copyOf(owners), dependencies, List.of());
		}

		private boolean matches(Map<CompiledHopKey,Node> nodes,
			Map<CompiledHopKey,Hop> origins, List<CompiledInputEdgeFact> edges,
			Map<CompiledHopKey,List<CompiledHopKey>> reaching, Set<CompiledHopKey> incomplete,
			Map<CompiledHopKey,Privacy> privacy) {
			if(nodes == null || origins == null || edges == null || reaching == null
				|| incomplete == null || privacy == null)
				return false;
			Map<CompiledHopKey,Node> nodesByIdentity = new IdentityHashMap<>(nodes);
			Map<CompiledHopKey,Hop> originsByIdentity = new IdentityHashMap<>(origins);
			Map<CompiledHopKey,List<CompiledHopKey>> reachingByIdentity = new IdentityHashMap<>(reaching);
			Map<CompiledHopKey,Privacy> privacyByIdentity = new IdentityHashMap<>(privacy);
			if(!sameIdentityKeys(nodesByKey, nodesByIdentity)
				|| !sameIdentityKeys(originsByKey, originsByIdentity)
				|| !sameIdentityKeys(reachingDefinitions, reachingByIdentity)
				|| !sameIdentityKeys(privacyByKey, privacyByIdentity)
				|| !sameIdentitySet(incompleteSources, incomplete))
				return false;
			for(CompiledHopKey key : nodesByKey.keySet())
				if(!nodesByKey.get(key).equals(nodesByIdentity.get(key)))
					return false;
			for(CompiledHopKey key : originsByKey.keySet())
				if(originsByKey.get(key) != originsByIdentity.get(key))
					return false;
			for(CompiledHopKey key : reachingDefinitions.keySet()) {
				List<CompiledHopKey> before = reachingDefinitions.get(key);
				List<CompiledHopKey> after = reachingByIdentity.get(key);
				if(after == null || before.size() != after.size())
					return false;
				for(int index = 0; index < before.size(); index++)
					if(before.get(index) != after.get(index))
						return false;
			}
			for(CompiledHopKey key : privacyByKey.keySet())
				if(privacyByKey.get(key) != privacyByIdentity.get(key))
					return false;
			return matchesEdges(edges);
		}

		private boolean matchesEdges(List<CompiledInputEdgeFact> edges) {
			int expectedCount = edgesByConsumer.values().stream().mapToInt(Map::size).sum();
			if(edges.size() != expectedCount)
				return false;
			Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> indexed = new IdentityHashMap<>();
			for(CompiledInputEdgeFact edge : edges) {
				if(edge == null)
					return false;
				Map<Integer,CompiledInputEdgeFact> positions = indexed.computeIfAbsent(
					edge.consumer(), ignored -> new java.util.HashMap<>());
				if(positions.put(edge.inputPosition(), edge) != null)
					return false;
			}
			if(!sameIdentityKeys(edgesByConsumer, indexed))
				return false;
			for(var consumer : edgesByConsumer.entrySet()) {
				Map<Integer,CompiledInputEdgeFact> after = indexed.get(consumer.getKey());
				if(after == null || consumer.getValue().size() != after.size())
					return false;
				for(var position : consumer.getValue().entrySet()) {
					CompiledInputEdgeFact candidate = after.get(position.getKey());
					if(candidate == null || candidate.producer() != position.getValue().producer()
						|| candidate.consumer() != position.getValue().consumer())
						return false;
				}
			}
			return true;
		}

		private static boolean sameIdentityKeys(Map<CompiledHopKey,?> before,
			Map<CompiledHopKey,?> after) {
			if(before.size() != after.size())
				return false;
			Set<CompiledHopKey> afterKeys = Collections.newSetFromMap(new IdentityHashMap<>());
			afterKeys.addAll(after.keySet());
			return afterKeys.size() == after.size() && before.keySet().stream().allMatch(afterKeys::contains);
		}

		private static boolean sameIdentitySet(Set<CompiledHopKey> before,
			Set<CompiledHopKey> after) {
			if(before.size() != after.size())
				return false;
			Set<CompiledHopKey> afterKeys = Collections.newSetFromMap(new IdentityHashMap<>());
			afterKeys.addAll(after);
			return afterKeys.size() == after.size() && before.stream().allMatch(afterKeys::contains);
		}
	}

	boolean proves(List<CompiledHopKey> sources, DurableAnchorKey externalSeed) {
		Objects.requireNonNull(sources, "sources");
		NativePoolWitness witness = nativeWitness(
			Objects.requireNonNull(externalSeed, "externalSeed"));
		if(sources.isEmpty() || witness == null)
			return false;
		Map<CompiledHopKey,ProofNode> proof = new IdentityHashMap<>();
		for(CompiledHopKey source : sources)
			buildProof(Objects.requireNonNull(source, "source"), witness, proof);
		if(proof.values().stream().anyMatch(node -> !node.valid))
			return false;

		Map<CompiledHopKey,Boolean> grounded = new IdentityHashMap<>();
		proof.forEach((key, node) -> grounded.put(key, node.directGround));
		boolean changed;
		do {
			changed = false;
			for(var entry : proof.entrySet()) {
				if(grounded.get(entry.getKey()))
					continue;
				if(entry.getValue().dependencies.stream().anyMatch(key -> grounded.getOrDefault(key, false))) {
					grounded.put(entry.getKey(), true);
					changed = true;
				}
			}
		}
		while(changed);
		return proof.keySet().stream().allMatch(key -> grounded.getOrDefault(key, false));
	}

	NativeContinuityProof proveCandidate(CandidateRealizationReference source,
		DurableAnchorKey externalSeed) {
		List<NativeContinuityProof> alternatives = proveCandidateAlternatives(source, externalSeed);
		return alternatives.isEmpty() ? null : alternatives.get(0);
	}

	List<NativeContinuityProof> proveCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed) {
		return proveCandidateSupport(source, externalSeed).proofs();
	}

	/**
	 * Proves a proof-only root from the current exact ordinary native row. This is
	 * the compatibility entry point for direct publication: it delegates to the
	 * single generated-query mode rather than creating a second cache/state mode.
	 */
	List<NativeContinuityProof> provePrimitiveCandidateAlternatives(
		CandidateRealizationReference source, DurableAnchorKey externalSeed) {
		Objects.requireNonNull(source, "primitive proof source");
		Objects.requireNonNull(externalSeed, "primitive proof external seed");
		CandidateRuleFact matchedFact = null;
		CandidateEmissionFact matchedEmission = null;
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(
			source.rule().parentOccurrence(), List.of())) {
			if(!fact.key().equals(source.rule())
				|| fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				if(!emission.emissionState().equals(source.realization().emissionState())
					|| state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() == null || emission.executionFType() != state.fType()
					|| emission.derivedFoutAction() != null)
					continue;
				if(matchedFact != null)
					return List.of();
				matchedFact = fact;
				matchedEmission = emission;
			}
		}
		return matchedFact == null ? List.of() : proveGeneratedCandidateAlternatives(
			matchedFact, matchedEmission, source, externalSeed);
	}

	/**
	 * Proves one prospective native output from the current exact base row. Unlike
	 * declared-realization validation, the root is derived from an empty support
	 * clause and therefore cannot inherit or self-ground from prior publication
	 * history. Recursive descendants still use the current exact candidate facts.
	 */
	List<NativeContinuityProof> proveGeneratedCandidateAlternatives(
		CandidateRuleFact trustedBase, CandidateEmissionFact baseEmission,
		CandidateRealizationReference proposedOutput, DurableAnchorKey externalSeed) {
		Objects.requireNonNull(trustedBase, "trusted generator base");
		Objects.requireNonNull(baseEmission, "generator base emission");
		Objects.requireNonNull(proposedOutput, "proposed generator output");
		Objects.requireNonNull(externalSeed, "generator external seed");
		CompiledHopKey root = trustedBase.key().parentOccurrence();
		boolean currentBase = candidateFactsByKey.getOrDefault(root, List.of()).stream()
			.anyMatch(fact -> fact == trustedBase);
		boolean currentEmission = trustedBase.allowedEmissionFacts().stream()
			.anyMatch(emission -> emission == baseEmission);
		PlacementState state = baseEmission.emissionState().placementState();
		if(!currentBase || !currentEmission
			|| trustedBase.status() != CandidateEvaluationStatus.AVAILABLE
			|| !proposedOutput.rule().equals(trustedBase.key())
			|| !proposedOutput.realization().emissionState().equals(baseEmission.emissionState())
			|| state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
			|| state.fType() == null || baseEmission.executionFType() != state.fType()
			|| baseEmission.derivedFoutAction() != null)
			return List.of();
		GenerationRoot generation = new GenerationRoot(trustedBase, baseEmission);
		PublicCandidateQueryKey query = new PublicCandidateQueryKey(
			proposedOutput, externalSeed, true);
		MemoEntry cached = completedProofMemo.get(query);
		if(cached != null) {
			if(metrics != null)
				metrics.recordMemoHit();
			return cached.proofs();
		}
		if(metrics != null)
			metrics.recordMemoMiss();
		ComputedPublicProof computed = computeCandidateAlternatives(
			proposedOutput, externalSeed, generation);
		cacheCompletedProofs(query, computed);
		return computed.proofs();
	}

	/** Native-continuity result plus its complete native proof occurrence footprint. */
	CandidateSupportResult proveCandidateSupport(CandidateRealizationReference source,
		DurableAnchorKey externalSeed) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(externalSeed, "externalSeed");
		PublicCandidateQueryKey query = new PublicCandidateQueryKey(source, externalSeed, false);
		MemoEntry cached = completedProofMemo.get(query);
		if(cached != null) {
			if(metrics != null)
				metrics.recordMemoHit();
			return new CandidateSupportResult(cached.proofs(), cached.occurrences());
		}
		if(metrics != null)
			metrics.recordMemoMiss();
		ComputedPublicProof computed = computeCandidateAlternatives(source, externalSeed, null);
		cacheCompletedProofs(query, computed);
		return new CandidateSupportResult(computed.proofs(), computed.occurrences());
	}

	private ComputedPublicProof computeCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed, GenerationRoot generation) {
		NativePoolWitness seedWitness = nativeWitness(
			externalSeed);
		if(seedWitness == null)
			return new ComputedPublicProof(List.of(), Set.of(source.rule().parentOccurrence()));
		FType outputType = source.realization().emissionState().placementState().fType();
		NativePoolWitness witness = seedWitness.retyped(outputType);
		Hop owner = originsByKey.get(source.rule().parentOccurrence());
		if(witness == null && recomputesNativePartitionRanges(owner, outputType))
			witness = seedWitness.retypedForResidency(outputType);
		if(witness == null)
			return new ComputedPublicProof(List.of(), Set.of(source.rule().parentOccurrence()));
		ComputedPublicProof exact = proveCandidateAlternatives(
			source, externalSeed, witness, generation);
		if(witness.fType != FType.ROW && witness.fType != FType.COL && witness.fType != FType.FULL)
			return exact;
		FType witnessType = witness.fType;
		ComputedPublicProof dynamic = proveCandidateAlternatives(
			source, externalSeed, witness.withDynamicPartitionRanges(), generation);
		List<NativeContinuityProof> dynamicProofs = dynamic.proofs().stream()
			.filter(proof -> recomputesNativePartitionRanges(owner, witnessType)
				|| proof.immediateBindings().stream().anyMatch(binding ->
					hasDynamicNativeLayout(binding.source())))
			.toList();
		Set<CompiledHopKey> occurrences = Collections.newSetFromMap(new IdentityHashMap<>());
		occurrences.addAll(exact.occurrences());
		occurrences.addAll(dynamic.occurrences());
		return new ComputedPublicProof(java.util.stream.Stream.concat(exact.proofs().stream(),
			dynamicProofs.stream()).distinct()
			.sorted(java.util.Comparator.comparing(NativeContinuityProof::normalizedSignature)).toList(),
			Collections.unmodifiableSet(occurrences));
	}

	private void cacheCompletedProofs(PublicCandidateQueryKey query, ComputedPublicProof computed) {
		long proofBytes = estimatedProofBytes(computed.proofs());
		// Every public entry retains its footprint even when it shares proof objects.
		// Account for the identity-set backing table rather than treating 64 MiB as
		// a bound on proofs alone.
		long footprintBytes = 64L + 64L * computed.occurrences().size();
		long estimatedBytes = Long.MAX_VALUE - proofBytes < footprintBytes
			? Long.MAX_VALUE : proofBytes + footprintBytes;
		cacheCompletedProofs(query, new MemoEntry(computed.proofs(),
			estimatedBytes, computed.occurrences()));
	}

	private void cacheCompletedProofs(PublicCandidateQueryKey query, MemoEntry entry) {
		long estimatedBytes = entry.estimatedBytes();
		if(memoMaxEntries == 0 || memoMaxProofs == 0 || memoMaxEstimatedBytes == 0
			|| entry.proofs().size() > memoMaxProofs || estimatedBytes > memoMaxEstimatedBytes)
			return;
		while(!completedProofMemo.isEmpty()
			&& (completedProofMemo.size() >= memoMaxEntries
				|| memoRetainedProofs + entry.proofs().size() > memoMaxProofs
				|| memoRetainedEstimatedBytes + estimatedBytes > memoMaxEstimatedBytes)) {
			var oldest = completedProofMemo.entrySet().iterator().next();
			memoRetainedProofs -= oldest.getValue().proofs().size();
			memoRetainedEstimatedBytes -= oldest.getValue().estimatedBytes();
			completedProofMemo.remove(oldest.getKey());
			if(metrics != null)
				metrics.recordMemoEviction();
		}
		completedProofMemo.put(query, entry);
		memoRetainedProofs += entry.proofs().size();
		memoRetainedEstimatedBytes += estimatedBytes;
		if(metrics != null)
			metrics.recordMemoResident(completedProofMemo.size(), memoRetainedProofs,
				memoRetainedEstimatedBytes);
	}

	private static long estimatedProofBytes(List<NativeContinuityProof> proofs) {
		long bytes = 0;
		for(NativeContinuityProof proof : proofs) {
			long proofBytes = 96L + 2L * proof.normalizedSignature().length()
				+ 32L * proof.immediateBindings().size();
			bytes = Long.MAX_VALUE - bytes < proofBytes ? Long.MAX_VALUE : bytes + proofBytes;
		}
		return bytes;
	}

	private CandidateSupportQueryKey candidateSupportQueryKey(
		CandidateRealizationReference source, NativePoolWitness witness, boolean generated) {
		int sourceHandle = candidateHandle(source);
		CandidateTopology topology = candidateTopology(source.rule().parentOccurrence(), witness);
		boolean exactTopologyRow = topology.rowsByHandle.containsKey(sourceHandle);
		return new CandidateSupportQueryKey(source, sourceHandle, witness, exactTopologyRow, generated);
	}

	private List<NativeContinuityProof> instantiateSupportTemplates(SupportMemoEntry entry,
		CandidateRealizationReference source, DurableAnchorKey externalSeed) {
		SearchSpaceMetrics.PhaseToken started = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.PUBLIC_PROOF_MATERIALIZATION);
		try {
			List<NativeContinuityProof> proofs = new ArrayList<>(entry.templates.size());
			for(CandidateSupportTemplate template : entry.templates) {
				List<CandidateRealizationInputBinding> bindings = entry.root.equals(source)
					? template.immediateBindings : rebindTemplateRoot(
						template.immediateBindings, entry.root, source);
				proofs.add(new NativeContinuityProof(externalSeed, template.outputWorkerPoolWitness,
					template.exactPartitionRanges, bindings));
			}
			proofs.sort(java.util.Comparator.comparing(NativeContinuityProof::normalizedSignature));
			return List.copyOf(proofs);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.PUBLIC_PROOF_MATERIALIZATION, started);
		}
	}

	private static List<CandidateRealizationInputBinding> rebindTemplateRoot(
		List<CandidateRealizationInputBinding> bindings, CandidateRealizationReference cachedRoot,
		CandidateRealizationReference requestedRoot) {
		List<CandidateRealizationInputBinding> rebound = null;
		for(int index = 0; index < bindings.size(); index++) {
			CandidateRealizationInputBinding binding = bindings.get(index);
			if(binding.source().rule().parentOccurrence() != cachedRoot.rule().parentOccurrence()
				|| !binding.source().equals(cachedRoot))
				continue;
			if(rebound == null)
				rebound = new ArrayList<>(bindings);
			rebound.set(index, new CandidateRealizationInputBinding(binding.inputPosition(),
				requestedRoot, binding.kind(), binding.relocationAction()));
		}
		return rebound == null ? bindings : List.copyOf(rebound);
	}

	private void cacheCompletedSupports(CandidateSupportQueryKey query, SupportMemoEntry entry) {
		if(supportMemoMaxEntries == 0 || supportMemoMaxTemplates == 0
			|| supportMemoMaxEstimatedBytes == 0
			|| entry.templates.size() > supportMemoMaxTemplates
			|| entry.estimatedBytes > supportMemoMaxEstimatedBytes)
			return;
		SupportMemoEntry prior = completedSupportMemo.remove(query);
		if(prior != null) {
			supportMemoRetainedTemplates -= prior.templates.size();
			supportMemoRetainedEstimatedBytes -= prior.estimatedBytes;
		}
		while(!completedSupportMemo.isEmpty()
			&& (completedSupportMemo.size() >= supportMemoMaxEntries
				|| supportMemoRetainedTemplates + entry.templates.size() > supportMemoMaxTemplates
				|| supportMemoRetainedEstimatedBytes + entry.estimatedBytes
					> supportMemoMaxEstimatedBytes)) {
			var oldest = completedSupportMemo.entrySet().iterator().next();
			supportMemoRetainedTemplates -= oldest.getValue().templates.size();
			supportMemoRetainedEstimatedBytes -= oldest.getValue().estimatedBytes;
			completedSupportMemo.remove(oldest.getKey());
			if(metrics != null)
				metrics.recordSupportMemoEviction();
		}
		completedSupportMemo.put(query, entry);
		supportMemoRetainedTemplates += entry.templates.size();
		supportMemoRetainedEstimatedBytes += entry.estimatedBytes;
		if(metrics != null)
			metrics.recordSupportMemoResident(completedSupportMemo.size(),
				supportMemoRetainedTemplates, supportMemoRetainedEstimatedBytes);
	}

	private static long estimatedSupportBytes(List<CandidateSupportTemplate> templates) {
		long bytes = 0;
		for(CandidateSupportTemplate template : templates) {
			long templateBytes = 80L + 32L * template.immediateBindings.size();
			bytes = Long.MAX_VALUE - bytes < templateBytes
				? Long.MAX_VALUE : bytes + templateBytes;
		}
		return Math.max(32L, bytes);
	}

	private boolean hasDynamicNativeLayout(CandidateRealizationReference reference) {
		return candidateFactsByKey.getOrDefault(reference.rule().parentOccurrence(), List.of()).stream()
			.filter(fact -> fact.key().equals(reference.rule()))
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().equals(reference.realization()))
			.flatMap(realization -> realization.supportClauses().stream())
			.anyMatch(clause -> clause.nativeWorkerPoolWitness() != null
				&& !clause.nativeWorkerPoolLayoutExact());
	}

	private ComputedPublicProof proveCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed, NativePoolWitness witness, GenerationRoot generation) {
		boolean generated = generation != null;
		CandidateSupportQueryKey query = candidateSupportQueryKey(source, witness, generated);
		SupportMemoEntry cached = completedSupportMemo.get(query);
		if(cached != null) {
			if(metrics != null)
				metrics.recordSupportMemoHit();
			return new ComputedPublicProof(instantiateSupportTemplates(cached, source, externalSeed),
				cached.occurrences());
		}
		AcyclicRootSupportKey rootSupportKey = generated ? null
			: acyclicRootSupportKey(source, witness);
		SupportMemoEntry sharedRoot = rootSupportKey == null ? null
			: acyclicRootSupportMemo.get(rootSupportKey);
		if(sharedRoot != null) {
			if(metrics != null)
				metrics.recordSupportMemoHit();
			cacheCompletedSupports(query, sharedRoot);
			return new ComputedPublicProof(
				instantiateSupportTemplates(sharedRoot, source, externalSeed), sharedRoot.occurrences());
		}
		if(metrics != null)
			metrics.recordSupportMemoMiss();
		ComputedCandidateSupport computed = computeCandidateSupportAlternatives(
			source, witness, generation);
		SupportMemoEntry entry = new SupportMemoEntry(source, computed.templates,
			computed.occurrences, estimatedSupportBytes(computed.templates), computed.rootIndependent);
		cacheCompletedSupports(query, entry);
		if(rootSupportKey != null && entry.rootIndependent)
			cacheAcyclicRootSupport(rootSupportKey, entry);
		return new ComputedPublicProof(instantiateSupportTemplates(entry, source, externalSeed),
			entry.occurrences());
	}

	private AcyclicRootSupportKey acyclicRootSupportKey(CandidateRealizationReference source,
		NativePoolWitness witness) {
		CompiledHopKey occurrence = source.rule().parentOccurrence();
		if(occurrenceComponents.componentOf(occurrence).cyclic())
			return null;
		CandidateTopology topology = candidateTopology(occurrence, witness);
		int handle = candidateHandle(source);
		List<CandidateTopologyRow> rows = topology.rowsByHandle.get(handle);
		if(rows == null || rows.isEmpty())
			return null;
		List<RootTopologyRowKey> relation = rows.stream().map(row -> new RootTopologyRowKey(
			row.dependencies().stream().map(ContinuityDependencyKey::of).toList(),
			row.directGround())).toList();
		return new AcyclicRootSupportKey(occurrence, witness, relation);
	}

	private void cacheAcyclicRootSupport(AcyclicRootSupportKey key, SupportMemoEntry entry) {
		if(supportMemoMaxEntries == 0 || supportMemoMaxTemplates == 0
			|| supportMemoMaxEstimatedBytes == 0
			|| entry.templates.size() > supportMemoMaxTemplates
			|| entry.estimatedBytes > supportMemoMaxEstimatedBytes)
			return;
		acyclicRootSupportMemo.remove(key);
		while(acyclicRootSupportMemo.size() >= supportMemoMaxEntries)
			acyclicRootSupportMemo.remove(acyclicRootSupportMemo.entrySet().iterator().next().getKey());
		acyclicRootSupportMemo.put(key, entry);
	}

	private ComputedCandidateSupport computeCandidateSupportAlternatives(
		CandidateRealizationReference source, NativePoolWitness witness,
		GenerationRoot generation) {
		if(metrics != null) {
			metrics.recordProofQuery();
			SearchSpaceMetrics.PhaseToken observerStarted =
				metrics.startPhase(SearchSpaceMetrics.Phase.CONTEXT_OBSERVER);
			try {
				CandidateQueryKey observed = new CandidateQueryKey(source, witness, generation);
				SearchSpaceMetrics.ContextObservation observation = observedQueries.observe(observed);
				metrics.recordContextObserver(observation.verifiedHashCollisions());
				switch(observation.result()) {
					case FIRST -> metrics.recordExactContext(true);
					case REPEATED -> metrics.recordExactContext(false);
					case OVERFLOW -> metrics.recordExactContextOverflow();
				}
			}
			finally {
				metrics.finishPhase(SearchSpaceMetrics.Phase.CONTEXT_OBSERVER, observerStarted);
			}
		}
		int sourceHandle = candidateHandle(source);
		CandidateProofState root = new CandidateProofState(source.rule().parentOccurrence(), source,
			sourceHandle, witness, true);
		Map<CandidateProofState,List<SelectedCandidateProof>> graph = new java.util.LinkedHashMap<>();
		Map<CompiledHopKey,CandidateRealizationReference> fixed = new IdentityHashMap<>();
		Map<CompiledHopKey,Integer> fixedHandles = new IdentityHashMap<>();
		fixed.put(source.rule().parentOccurrence(), source);
		fixedHandles.put(source.rule().parentOccurrence(), sourceHandle);
		SearchSpaceMetrics.PhaseToken overlayStarted = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.PROOF_OVERLAY);
		long[] graphWork = metrics == null ? null : new long[2];
		CandidateProofTraversal traversal = new CandidateProofTraversal();
		try {
			buildCandidateProofGraph(root, root, generation,
				graph, traversal, fixed, fixedHandles, graphWork);
			if(metrics != null)
				metrics.recordProofGraph(graph.size(), graphWork[0], graphWork[1]);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_OVERLAY, overlayStarted);
		}
		SearchSpaceMetrics.PhaseToken groundingStarted = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.PROOF_GROUNDING);
		Map<CandidateProofState,List<SelectedCandidateProof>> viable;
		Set<CandidateProofState> grounded;
		long acyclicRemoved = 0;
		try {
			if(traversal.cycleDetected) {
				Map<CandidateProofState,AcyclicComponentFootprint> independentChildren =
					rootIndependentChildFootprints(root, graph, traversal);
				viable = pruneDeadAlternatives(graph);
				grounded = groundedCandidateStates(viable);
			cacheAcyclicRootChildren(independentChildren, viable, grounded,
				generation == null ? null : root.key());
			}
			else {
				Map<CandidateProofState,AcyclicComponentFootprint> childFootprints =
					acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
						|| acyclicComponentMaxAlternatives == 0 ? Map.of()
						: acyclicRootChildFootprints(root, graph, traversal);
				acyclicRemoved = pruneDeadAcyclicAlternatives(graph, traversal.completionOrder);
				viable = graph;
				grounded = groundedAcyclicCandidateStates(viable, traversal.completionOrder);
			cacheAcyclicRootChildren(childFootprints, viable, grounded,
				generation == null ? null : root.key());
			}
			if(metrics != null)
				metrics.recordProofGraphPath(traversal.cycleDetected, acyclicRemoved);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_GROUNDING, groundingStarted);
		}
		SearchSpaceMetrics.PhaseToken supportStarted = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.SUPPORT_PRODUCT_RELATION_MATERIALIZATION);
		try {
		Map<CandidateProofState,List<CandidateRealizationReference>> groundedReferences =
			new java.util.HashMap<>();
		Map<CandidateProofState,Boolean> directlyGrounded = new java.util.HashMap<>();
		Set<CandidateSupportTemplate> proofs = new LinkedHashSet<>();
		Set<List<List<CandidateRealizationInputBinding>>> expandedSupportProducts =
			new java.util.HashSet<>();
		long[] rawProofs = metrics == null ? null : new long[] {0};
		DurableAnchorKey outputWitness = witness.asAnchor(
			"native-proof-output:" + source.rule().parentOccurrence().normalizedSignature());
		if(grounded.contains(root))
			for(SelectedCandidateProof alternative : viable.getOrDefault(root, List.of())) {
				if((!alternative.directGround && alternative.dependencies.isEmpty())
					|| !alternative.dependencies.stream().allMatch(dependency -> grounded.contains(dependency.state())))
					continue;
				List<List<CandidateRealizationInputBinding>> immediateOptions = new ArrayList<>();
				boolean complete = true;
				for(CandidateProofDependency dependency : alternative.dependencies) {
					if(dependency.inputPosition() < 0)
						continue;
					// A query-pinned generated recurrence may prove its SCC, but the
					// proposed output is not an executable premise for its own receipt.
					if(generation != null && dependency.state().equals(root)) {
						complete = false;
						break;
					}
					List<CandidateRealizationReference> options = groundedReferences.computeIfAbsent(
						dependency.state(), state -> canonicalReferences(viable.getOrDefault(state, List.of()).stream()
							.filter(option -> option.realization != null)
							.filter(option -> (option.directGround || !option.dependencies.isEmpty())
								&& option.dependencies.stream().allMatch(child -> grounded.contains(child.state())))
							.map(SelectedCandidateProof::realization).toList()));
					if(options.isEmpty()) {
						if(!directlyGrounded.computeIfAbsent(dependency.state(), state -> viable
							.getOrDefault(state, List.of()).stream().anyMatch(option -> option.directGround))) {
							complete = false;
							break;
						}
						continue;
					}
					immediateOptions.add(options.stream()
						.map(reference -> CandidateRealizationInputBinding.direct(
							dependency.inputPosition(), reference)).toList());
				}
				if(complete) {
					List<List<CandidateRealizationInputBinding>> descriptor = immediateOptions.stream()
						.map(List::copyOf).toList();
					boolean expand = expandedSupportProducts.add(descriptor);
					if(metrics != null)
						metrics.recordSupportProductDescriptor(expand);
					if(!expand)
						continue;
					enumerateImmediateSupports(immediateOptions, 0, new ArrayList<>(), support -> {
						if(metrics != null)
							rawProofs[0]++;
						proofs.add(new CandidateSupportTemplate(outputWitness,
							witness.exactPartitionRanges, support));
					});
				}
			}
		List<CandidateSupportTemplate> distinct = List.copyOf(proofs);
		if(distinct.isEmpty())
			traceFailedCandidateSupport(source, root, graph, grounded);
		if(metrics != null)
			metrics.recordProofResult(rawProofs[0], distinct.size());
		Set<CompiledHopKey> occurrences = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateProofState state : graph.keySet())
			occurrences.add(state.key());
		// A reused acyclic boundary deliberately hides its transitive graph states
		// from this query's work graph. They still belong to the completed support's
		// invalidation footprint and must cross revisions only when every row matches.
		for(AcyclicComponentSummary reused : traversal.reusedComponents.values())
			occurrences.addAll(reused.occurrences);
		boolean rootIndependent = graph.entrySet().stream()
			.filter(entry -> !entry.getKey().equals(root))
			.flatMap(entry -> entry.getValue().stream())
			.flatMap(alternative -> alternative.dependencies.stream())
			.noneMatch(dependency -> dependency.key == root.key());
		return new ComputedCandidateSupport(distinct, Collections.unmodifiableSet(occurrences),
			rootIndependent);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(
					SearchSpaceMetrics.Phase.SUPPORT_PRODUCT_RELATION_MATERIALIZATION,
					supportStarted);
		}
	}

	private void traceFailedCandidateSupport(CandidateRealizationReference source,
		CandidateProofState root, Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		Set<CandidateProofState> grounded) {
		Hop owner = originsByKey.get(source.rule().parentOccurrence());
		if(!FederatedPlannerTrace.shouldTrace(owner))
			return;
		StringBuilder detail = new StringBuilder("owner=")
			.append(source.rule().parentOccurrence().callSitePath()).append(':')
			.append(source.rule().parentOccurrence().emittedHopInstance())
			.append("|opcode=").append(owner.getOpString())
			.append("|rootGrounded=").append(grounded.contains(root))
			.append("|rootPinnedHash=").append(source.hashCode())
			.append("|rootDeclared=").append(declaresExactRealization(
				source.rule().parentOccurrence(), source)).append("|states=[");
		int emitted = 0;
		for(var entry : graph.entrySet()) {
			CandidateProofState state = entry.getKey();
			List<SelectedCandidateProof> alternatives = entry.getValue();
			if(state != root && grounded.contains(state) && !alternatives.isEmpty())
				continue;
			if(emitted++ >= 6)
				break;
			if(emitted > 1)
				detail.append(',');
			Hop stateOwner = originsByKey.get(state.key());
			Node node = nodesByKey.get(state.key());
			detail.append('{').append(state.key().callSitePath()).append(':')
				.append(state.key().emittedHopInstance())
				.append("|opcode=").append(stateOwner == null ? "-" : stateOwner.getOpString())
				.append("|pinnedHash=").append(state.realization() == null ? "-"
					: state.realization().hashCode())
				.append("|declared=").append(state.realization() != null
					&& declaresExactRealization(state.key(), state.realization()))
				.append("|witness=").append(state.witness().fType).append('/')
				.append(state.witness().exactPartitionRanges ? "exact" : "dynamic")
				.append("|legal=").append(node == null ? List.of() : node.legalAlternatives())
				.append("|grounded=").append(grounded.contains(state))
				.append("|alternatives=").append(alternatives.size()).append('[');
			for(int alternativeIndex = 0;
				alternativeIndex < Math.min(6, alternatives.size()); alternativeIndex++) {
				if(alternativeIndex > 0)
					detail.append(',');
				SelectedCandidateProof alternative = alternatives.get(alternativeIndex);
				detail.append("direct=").append(alternative.directGround()).append(";deps=");
				detail.append('[');
				for(int dependencyIndex = 0;
					dependencyIndex < Math.min(6, alternative.dependencies().size()); dependencyIndex++) {
					if(dependencyIndex > 0)
						detail.append(',');
					CandidateProofDependency dependency = alternative.dependencies().get(dependencyIndex);
					detail.append(dependency.key.callSitePath()).append(':')
						.append(dependency.key.emittedHopInstance()).append("#")
						.append(dependency.realization == null ? "-" : dependency.realization.hashCode());
				}
				detail.append(']');
			}
			detail.append("]}");
		}
		detail.append(']');
		FederatedPlannerTrace.log(owner, "Native-EmptyCandidateSupport", detail.toString());
	}

	private static List<CandidateRealizationReference> canonicalReferences(
		List<CandidateRealizationReference> references) {
		Map<Object,CandidateRealizationReference> distinct = new java.util.LinkedHashMap<>();
		for(CandidateRealizationReference reference : references) {
			Integer structuralHandle = PlacementIdentity.structuralHandle(reference);
			distinct.putIfAbsent(structuralHandle == null ? reference : structuralHandle, reference);
		}
		List<CandidateRealizationReference> canonical = new ArrayList<>(distinct.values());
		canonical.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(canonical);
	}

	private Map<CandidateProofState,List<SelectedCandidateProof>> pruneDeadAlternatives(
		Map<CandidateProofState,List<SelectedCandidateProof>> graph) {
		boolean hasDeadSeed = false;
		for(var entry : graph.entrySet()) {
			if(metrics != null)
				metrics.recordOwnerElementsScanned(entry.getValue().size());
			hasDeadSeed |= entry.getValue().isEmpty();
			if(!hasDeadSeed) {
				alternatives:
				for(SelectedCandidateProof alternative : entry.getValue())
					for(CandidateProofDependency dependency : alternative.dependencies)
						if(!graph.containsKey(dependency.state())) {
							hasDeadSeed = true;
							break alternatives;
						}
			}
		}
		if(!hasDeadSeed)
			return graph;
		// Dense IDs are query-local aliases for the complete state equality, not a
		// structural quotient. Keep every original key (including dead states) for
		// downstream SCC grounding and revision invalidation.
		Map<CandidateProofState,Integer> stateIds = new java.util.HashMap<>();
		int slotCount = 0, edgeCount = 0;
		for(var entry : graph.entrySet()) {
			stateIds.put(entry.getKey(), stateIds.size());
			slotCount = Math.addExact(slotCount, entry.getValue().size());
			for(SelectedCandidateProof alternative : entry.getValue())
				edgeCount = Math.addExact(edgeCount, alternative.dependencies.size());
		}
		int[] slotRemovalIds = new int[slotCount];
		int[] alternativeOwners = new int[slotCount];
		int[] dependencyStates = new int[edgeCount];
		int[] dependencySlots = new int[edgeCount];
		IdentityHashMap<SelectedCandidateProof,Integer> ownerAlternatives = new IdentityHashMap<>();
		int owner = 0, slot = 0, edge = 0;
		for(List<SelectedCandidateProof> alternatives : graph.values()) {
			ownerAlternatives.clear();
			for(SelectedCandidateProof alternative : alternatives) {
				Integer previousSlot = ownerAlternatives.putIfAbsent(alternative, slot);
				int removalId = previousSlot == null ? slot : previousSlot;
				slotRemovalIds[slot] = removalId;
				alternativeOwners[removalId] = owner;
				for(CandidateProofDependency dependency : alternative.dependencies) {
					dependencyStates[edge] = stateIds.computeIfAbsent(dependency.state(),
						ignored -> stateIds.size());
					dependencySlots[edge++] = slot;
				}
				slot++;
			}
			owner++;
		}
		int[] reverseHeads = new int[stateIds.size()];
		int[] reverseNext = new int[edgeCount];
		int[] lastSlot = new int[stateIds.size()];
		java.util.Arrays.fill(reverseHeads, -1);
		java.util.Arrays.fill(lastSlot, -1);
		for(edge = 0; edge < edgeCount; edge++) {
			int dependency = dependencyStates[edge];
			// The legacy reverse index deduplicates dependencies per original list
			// slot, not per shared alternative object or per owner.
			if(lastSlot[dependency] == dependencySlots[edge])
				continue;
			lastSlot[dependency] = dependencySlots[edge];
			reverseNext[edge] = reverseHeads[dependency];
			reverseHeads[dependency] = edge;
		}
		int[] liveCounts = new int[stateIds.size()];
		owner = 0;
		for(List<SelectedCandidateProof> alternatives : graph.values())
			liveCounts[owner++] = alternatives.size();
		int[] dead = new int[stateIds.size()];
		int nextDead = 0, deadCount = 0;
		for(int state = 0; state < liveCounts.length; state++)
			if(liveCounts[state] == 0) {
				dead[deadCount++] = state;
				if(metrics != null)
					metrics.recordDeadStateQueued();
			}
		boolean[] removed = new boolean[slotCount];
		while(nextDead < deadCount)
			for(edge = reverseHeads[dead[nextDead++]]; edge >= 0; edge = reverseNext[edge]) {
				if(metrics != null)
					metrics.recordDependencyNotification();
				int removalId = slotRemovalIds[dependencySlots[edge]];
				if(removed[removalId])
					continue;
				removed[removalId] = true;
				if(metrics != null)
					metrics.recordAlternativeRemoved();
				// Duplicate object slots share one removal but retain the legacy
				// original-list live count. Equal distinct objects do not share it.
				owner = alternativeOwners[removalId];
				if(--liveCounts[owner] == 0) {
					dead[deadCount++] = owner;
					if(metrics != null)
						metrics.recordDeadStateQueued();
				}
			}
		Map<CandidateProofState,List<SelectedCandidateProof>> viable =
			new java.util.LinkedHashMap<>(graph);
		slot = 0;
		for(var entry : graph.entrySet()) {
			List<SelectedCandidateProof> alternatives = entry.getValue();
			List<SelectedCandidateProof> survivors = null;
			for(int index = 0; index < alternatives.size(); index++) {
				if(removed[slotRemovalIds[slot + index]]) {
					if(survivors == null) {
						survivors = new ArrayList<>(alternatives.size() - 1);
						survivors.addAll(alternatives.subList(0, index));
					}
				}
				else if(survivors != null)
					survivors.add(alternatives.get(index));
			}
			if(survivors != null)
				viable.put(entry.getKey(), List.copyOf(survivors));
			slot += alternatives.size();
		}
		return viable;
	}

	private long pruneDeadAcyclicAlternatives(
		Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		List<CandidateProofState> completionOrder) {
		long removed = 0;
		// Dependencies are complete before their owners in DFS completion order.
		// Only replace values so graph key order and revision-invalidation footprint stay intact.
		for(CandidateProofState state : completionOrder) {
			List<SelectedCandidateProof> alternatives = graph.getOrDefault(state, List.of());
			if(metrics != null)
				metrics.recordOwnerElementsScanned(alternatives.size());
			List<SelectedCandidateProof> survivors = null;
			for(int index = 0; index < alternatives.size(); index++) {
				SelectedCandidateProof alternative = alternatives.get(index);
				boolean dead = false;
				for(CandidateProofDependency dependency : alternative.dependencies) {
					List<SelectedCandidateProof> dependencyAlternatives = graph.get(dependency.state());
					if(dependencyAlternatives == null || dependencyAlternatives.isEmpty()) {
						dead = true;
						break;
					}
				}
				if(dead) {
					removed++;
					if(metrics != null)
						metrics.recordAlternativeRemoved();
					if(survivors == null) {
						survivors = new ArrayList<>(alternatives.size() - 1);
						survivors.addAll(alternatives.subList(0, index));
					}
				}
				else if(survivors != null)
					survivors.add(alternative);
			}
			if(survivors != null)
				graph.put(state, List.copyOf(survivors));
		}
		return removed;
	}

	private static Set<CandidateProofState> groundedAcyclicCandidateStates(
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		List<CandidateProofState> completionOrder) {
		Set<CandidateProofState> grounded = new java.util.HashSet<>();
		for(CandidateProofState state : completionOrder)
			for(SelectedCandidateProof alternative : viable.getOrDefault(state, List.of())) {
				boolean supported = true;
				boolean hasGroundPath = alternative.directGround;
				for(CandidateProofDependency dependency : alternative.dependencies) {
					if(!grounded.contains(dependency.state())) {
						supported = false;
						break;
					}
					hasGroundPath = true;
				}
				if(supported && hasGroundPath) {
					grounded.add(state);
					break;
				}
			}
		return grounded;
	}

	private void enumerateImmediateSupports(List<List<CandidateRealizationInputBinding>> options,
		int ordinal, List<CandidateRealizationInputBinding> current,
		java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer) {
		enumerateImmediateSupports(options, ordinal, current, new IdentityHashMap<>(), consumer);
	}

	private void enumerateImmediateSupports(List<List<CandidateRealizationInputBinding>> options,
		int ordinal, List<CandidateRealizationInputBinding> current,
		Map<CompiledHopKey,CandidateRealizationReference> selected,
		java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer) {
		if(metrics != null)
			metrics.recordSupportPrefix(ordinal);
		if(ordinal == options.size()) {
			if(metrics != null)
				metrics.recordSupportLeaf();
			consumer.accept(List.copyOf(current));
			return;
		}
		for(CandidateRealizationInputBinding binding : options.get(ordinal)) {
			CompiledHopKey owner = binding.source().rule().parentOccurrence();
			CandidateRealizationReference prior = selected.get(owner);
			// The physical decision owner can select only one exact realization.
			// Reject mixed choices before constructing a product leaf (ExactPhysicalModel
			// rejects the same pair later through its selected-source factor).
			if(prior != null && !prior.equals(binding.source())) {
				if(metrics != null)
					metrics.recordSupportConflictPrefix();
				continue;
			}
			boolean first = prior == null;
			if(first)
				selected.put(owner, binding.source());
			current.add(binding);
			enumerateImmediateSupports(options, ordinal + 1, current, selected, consumer);
			current.remove(current.size() - 1);
			if(first)
				selected.remove(owner);
		}
	}

	private Set<CandidateProofState> groundedCandidateStates(
		Map<CandidateProofState,List<SelectedCandidateProof>> viable) {
		List<Set<CandidateProofState>> maximalComponents = new ArrayList<>();
		Map<CandidateProofState,Integer> indices = new java.util.HashMap<>();
		Map<CandidateProofState,Integer> lowLinks = new java.util.HashMap<>();
		java.util.ArrayDeque<CandidateProofState> stack = new java.util.ArrayDeque<>();
		Set<CandidateProofState> onStack = new java.util.HashSet<>();
		int[] nextIndex = {0};
		long[] scan = metrics == null ? null : new long[3];
		for(CandidateProofState state : viable.keySet())
			if(!indices.containsKey(state))
				collectStronglyConnectedComponents(state, viable, indices, lowLinks,
					stack, onStack, nextIndex, maximalComponents, scan);
		if(metrics != null)
			metrics.recordSccScan(scan[0], scan[1], scan[2], 0);
		Set<CandidateProofState> grounded = new java.util.HashSet<>();
		// Tarjan closes every owner-to-dependency edge before its owner component,
		// so this list is dependency-first. A component rejected for an ungrounded
		// external dependency cannot be revived by a later sibling.
		for(Set<CandidateProofState> component : maximalComponents)
			groundEligibleComponents(component, viable, grounded, 1);
		return grounded;
	}

	private boolean groundEligibleComponents(Set<CandidateProofState> component,
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		Set<CandidateProofState> grounded, int refinementDepth) {
		if(component.isEmpty() || grounded.containsAll(component))
			return false;
		if(component.size() == 1)
			return groundEligibleSingleton(component.iterator().next(), viable, grounded);
		Map<CandidateProofState,List<SelectedCandidateProof>> eligible = new java.util.LinkedHashMap<>();
		boolean relationUnchanged = true;
		boolean everyStateSupported = true;
		boolean hasGroundPath = false;
		for(CandidateProofState state : component) {
			List<SelectedCandidateProof> alternatives = viable.getOrDefault(state, List.of());
			List<SelectedCandidateProof> survivors = null;
			for(int index = 0; index < alternatives.size(); index++) {
				SelectedCandidateProof alternative = alternatives.get(index);
				boolean supported = alternative.directGround || !alternative.dependencies.isEmpty();
				boolean alternativeHasGroundPath = alternative.directGround;
				for(CandidateProofDependency dependency : alternative.dependencies) {
					CandidateProofState dependencyState = dependency.state();
					if(component.contains(dependencyState))
						continue;
					if(!grounded.contains(dependencyState)) {
						supported = false;
						break;
					}
					alternativeHasGroundPath = true;
				}
				if(supported) {
					if(survivors != null)
						survivors.add(alternative);
					// A direct-looking alternative cannot seed the SCC until every AND dependency is eligible.
					hasGroundPath |= alternativeHasGroundPath;
				}
				else {
					relationUnchanged = false;
					if(survivors == null)
						survivors = new ArrayList<>(alternatives.subList(0, index));
				}
			}
			List<SelectedCandidateProof> allowed = survivors == null ? alternatives : List.copyOf(survivors);
			eligible.put(state, allowed);
			everyStateSupported &= !allowed.isEmpty();
		}
		// Retaining every exact alternative leaves this maximal SCC's induced edge relation unchanged.
		// Any rejection, even of a redundant edge, still takes the existing refinement path.
		if(relationUnchanged)
			return everyStateSupported && hasGroundPath && grounded.addAll(component);
		List<Set<CandidateProofState>> refined = stronglyConnectedComponents(eligible, refinementDepth);
		if(refined.size() == 1 && refined.get(0).size() == component.size())
			return everyStateSupported && hasGroundPath && grounded.addAll(component);
		boolean changed = false;
		for(Set<CandidateProofState> subcomponent : refined)
			changed |= groundEligibleComponents(subcomponent, viable, grounded, refinementDepth + 1);
		return changed;
	}

	private static boolean groundEligibleSingleton(CandidateProofState state,
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		Set<CandidateProofState> grounded) {
		for(SelectedCandidateProof alternative : viable.getOrDefault(state, List.of())) {
			if(!alternative.directGround && alternative.dependencies.isEmpty())
				continue;
			boolean supported = true;
			boolean hasGroundPath = alternative.directGround;
			for(CandidateProofDependency dependency : alternative.dependencies) {
				if(state.equals(dependency.state()))
					continue;
				if(!grounded.contains(dependency.state())) {
					supported = false;
					break;
				}
				hasGroundPath = true;
			}
			if(supported && hasGroundPath)
				return grounded.add(state);
		}
		return false;
	}

	private List<Set<CandidateProofState>> stronglyConnectedComponents(
		Map<CandidateProofState,List<SelectedCandidateProof>> graph, int refinementDepth) {
		List<Set<CandidateProofState>> components = new ArrayList<>();
		Map<CandidateProofState,Integer> indices = new java.util.HashMap<>();
		Map<CandidateProofState,Integer> lowLinks = new java.util.HashMap<>();
		java.util.ArrayDeque<CandidateProofState> stack = new java.util.ArrayDeque<>();
		Set<CandidateProofState> onStack = new java.util.HashSet<>();
		int[] nextIndex = {0};
		long[] scan = metrics == null ? null : new long[3];
		for(CandidateProofState state : graph.keySet())
			if(!indices.containsKey(state))
				collectStronglyConnectedComponents(state, graph, indices, lowLinks,
					stack, onStack, nextIndex, components, scan);
		if(metrics != null)
			metrics.recordSccScan(scan[0], scan[1], scan[2], refinementDepth);
		return components;
	}

	private static void collectStronglyConnectedComponents(CandidateProofState state,
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		Map<CandidateProofState,Integer> indices, Map<CandidateProofState,Integer> lowLinks,
		java.util.ArrayDeque<CandidateProofState> stack, Set<CandidateProofState> onStack,
		int[] nextIndex, List<Set<CandidateProofState>> components, long[] scan) {
		int index = nextIndex[0]++;
		indices.put(state, index);
		lowLinks.put(state, index);
		stack.push(state);
		onStack.add(state);
		List<SelectedCandidateProof> alternatives = viable.getOrDefault(state, List.of());
		if(scan != null) {
			scan[0]++;
			scan[1] += alternatives.size();
		}
		for(SelectedCandidateProof alternative : alternatives) {
			if(scan != null)
				scan[2] += alternative.dependencies.size();
			for(CandidateProofDependency dependency : alternative.dependencies) {
				CandidateProofState successor = dependency.state();
				if(!viable.containsKey(successor))
					continue;
				if(!indices.containsKey(successor)) {
					collectStronglyConnectedComponents(successor, viable, indices, lowLinks,
						stack, onStack, nextIndex, components, scan);
					lowLinks.put(state, Math.min(lowLinks.get(state), lowLinks.get(successor)));
				}
				else if(onStack.contains(successor))
					lowLinks.put(state, Math.min(lowLinks.get(state), indices.get(successor)));
			}
		}
		if(!lowLinks.get(state).equals(indices.get(state)))
			return;
		Set<CandidateProofState> component = new java.util.LinkedHashSet<>();
		CandidateProofState member;
		do {
			member = stack.pop();
			onStack.remove(member);
			component.add(member);
		}
		while(!member.equals(state));
		components.add(component);
	}

	private void buildCandidateProofGraph(CandidateProofState state, CandidateProofState root,
		GenerationRoot generation,
		Map<CandidateProofState,List<SelectedCandidateProof>> graph, CandidateProofTraversal traversal,
		Map<CompiledHopKey,CandidateRealizationReference> fixed,
		Map<CompiledHopKey,Integer> fixedHandles, long[] graphWork) {
		if(traversal.active.contains(state)) {
			traversal.cycleDetected = true;
			return;
		}
		if(graph.containsKey(state))
			return;
		boolean generatedRoot = generation != null && state.key() == root.key()
			&& Objects.equals(state.realization(), root.realization());
		AcyclicComponentSummary shared = generatedRoot
			? null : reusableAcyclicComponent(state, fixed.keySet());
		if(shared != null) {
			graph.put(state, shared.groundedAlternatives);
			traversal.reusedComponents.put(state, shared);
			if(graphWork != null)
				graphWork[0] += shared.groundedAlternatives.size();
			traversal.completionOrder.add(state);
			return;
		}
		traversal.active.add(state);
		try {
			List<SelectedCandidateProof> alternatives = candidateProofAlternatives(
				state.key(), state.realization(), state.realizationHandle(), state.witness(),
				state.templateRoot(), fixed, fixedHandles, generatedRoot ? generation : null);
			graph.put(state, alternatives);
			if(graphWork != null) {
				graphWork[0] += alternatives.size();
				for(SelectedCandidateProof alternative : alternatives)
					graphWork[1] += alternative.dependencies.size();
			}
			for(SelectedCandidateProof alternative : alternatives)
				for(CandidateProofDependency dependency : alternative.dependencies)
					buildCandidateProofGraph(dependency.state(), root, generation, graph, traversal,
						fixed, fixedHandles, graphWork);
			traversal.completionOrder.add(state);
		}
		finally {
			traversal.active.remove(state);
		}
	}

	private AcyclicComponentSummary reusableAcyclicComponent(CandidateProofState state,
		Set<CompiledHopKey> fixedOccurrences) {
		AcyclicComponentSummary summary = acyclicComponentMemo.get(state);
		if(summary == null)
			return null;
		for(CompiledHopKey fixed : fixedOccurrences)
			if(summary.occurrences.contains(fixed))
				return null;
		return summary;
	}

	private Map<CandidateProofState,AcyclicComponentFootprint> acyclicRootChildFootprints(
		CandidateProofState root, Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal) {
		Map<CandidateProofState,AcyclicComponentFootprint> footprints = new java.util.LinkedHashMap<>();
		for(SelectedCandidateProof alternative : graph.getOrDefault(root, List.of()))
			for(CandidateProofDependency dependency : alternative.dependencies)
				if(!dependency.state().equals(root))
					footprints.computeIfAbsent(dependency.state(), child ->
						acyclicComponentFootprint(child, graph, traversal));
		return footprints;
	}

	/** A pinned root cannot affect a child outside its conservative occurrence SCC. */
	private Map<CandidateProofState,AcyclicComponentFootprint> rootIndependentChildFootprints(
		CandidateProofState root, Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal) {
		if(acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
			|| acyclicComponentMaxAlternatives == 0)
			return Map.of();
		var rootComponent = occurrenceComponents.componentOf(root.key());
		Map<CandidateProofState,AcyclicComponentFootprint> footprints = new java.util.LinkedHashMap<>();
		for(SelectedCandidateProof alternative : graph.getOrDefault(root, List.of()))
			for(CandidateProofDependency dependency : alternative.dependencies) {
				CandidateProofState child = dependency.state();
				if(occurrenceComponents.componentOf(child.key()) != rootComponent)
					footprints.computeIfAbsent(child, state ->
						acyclicComponentFootprint(state, graph, traversal));
			}
		return footprints;
	}

	private AcyclicComponentFootprint acyclicComponentFootprint(CandidateProofState child,
		Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal) {
		Set<CandidateProofState> states = new java.util.HashSet<>();
		Set<CompiledHopKey> occurrences = Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.ArrayDeque<CandidateProofState> pending = new java.util.ArrayDeque<>();
		long retainedStates = 0;
		pending.add(child);
		while(!pending.isEmpty()) {
			CandidateProofState state = pending.removeFirst();
			if(!states.add(state))
				continue;
			AcyclicComponentSummary reused = traversal.reusedComponents.get(state);
			if(reused != null) {
				occurrences.addAll(reused.occurrences);
				retainedStates += reused.retainedStates;
				if(retainedStates > acyclicComponentMaxStates)
					return null;
				continue;
			}
			occurrences.add(state.key());
			retainedStates++;
			if(retainedStates > acyclicComponentMaxStates)
				return null;
			for(SelectedCandidateProof alternative : graph.getOrDefault(state, List.of()))
				for(CandidateProofDependency dependency : alternative.dependencies)
					pending.addLast(dependency.state());
		}
		return new AcyclicComponentFootprint(Collections.unmodifiableSet(occurrences),
			retainedStates);
	}

	private void cacheAcyclicRootChildren(
		Map<CandidateProofState,AcyclicComponentFootprint> childFootprints,
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		Set<CandidateProofState> grounded, CompiledHopKey generatedRoot) {
		if(acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
			|| acyclicComponentMaxAlternatives == 0)
			return;
		for(var entry : childFootprints.entrySet())
			if(generatedRoot == null || !entry.getValue().occurrences.contains(generatedRoot))
				cacheAcyclicComponent(entry.getKey(), entry.getValue(), viable, grounded);
	}

	private void cacheAcyclicComponent(CandidateProofState child, AcyclicComponentFootprint footprint,
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		Set<CandidateProofState> grounded) {
		long retainedStates = footprint.retainedStates;
		if(retainedStates > acyclicComponentMaxStates)
			return;
		List<SelectedCandidateProof> groundedAlternatives = new ArrayList<>();
		if(grounded.contains(child))
			for(SelectedCandidateProof alternative : viable.getOrDefault(child, List.of()))
				if((alternative.directGround || !alternative.dependencies.isEmpty())
					&& alternative.dependencies.stream().allMatch(dependency ->
						grounded.contains(dependency.state()))) {
					if(groundedAlternatives.size() >= acyclicComponentMaxAlternatives)
						return; // Oversized boundary: exact recomputation is the safe fallback.
					groundedAlternatives.add(new SelectedCandidateProof(alternative.realization,
						List.of(), true, alternative.witness));
				}
		AcyclicComponentSummary summary = new AcyclicComponentSummary(groundedAlternatives,
			footprint.occurrences, retainedStates);
		cacheAcyclicSummary(child, summary);
	}

	private void cacheAcyclicSummary(CandidateProofState child, AcyclicComponentSummary summary) {
		if(acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
			|| acyclicComponentMaxAlternatives == 0
			|| summary.retainedStates > acyclicComponentMaxStates
			|| summary.groundedAlternatives.size() > acyclicComponentMaxAlternatives)
			return;
		long retainedStates = summary.retainedStates;
		List<SelectedCandidateProof> groundedAlternatives = summary.groundedAlternatives;
		AcyclicComponentSummary prior = acyclicComponentMemo.remove(child);
		if(prior != null) {
			acyclicComponentRetainedStates -= prior.retainedStates;
			acyclicComponentRetainedAlternatives -= prior.groundedAlternatives.size();
		}
		while(!acyclicComponentMemo.isEmpty()
			&& (acyclicComponentMemo.size() >= acyclicComponentMaxEntries
				|| acyclicComponentRetainedStates + retainedStates > acyclicComponentMaxStates
				|| acyclicComponentRetainedAlternatives + groundedAlternatives.size()
					> acyclicComponentMaxAlternatives)) {
			var oldest = acyclicComponentMemo.entrySet().iterator().next();
			acyclicComponentRetainedStates -= oldest.getValue().retainedStates;
			acyclicComponentRetainedAlternatives -= oldest.getValue().groundedAlternatives.size();
			acyclicComponentMemo.remove(oldest.getKey());
		}
		acyclicComponentMemo.put(child, summary);
		acyclicComponentRetainedStates += retainedStates;
		acyclicComponentRetainedAlternatives += groundedAlternatives.size();
	}

	private List<SelectedCandidateProof> candidateProofAlternatives(CompiledHopKey key,
		CandidateRealizationReference pinned, int pinnedHandle, NativePoolWitness witness,
		boolean allowPinnedTemplate, Map<CompiledHopKey,CandidateRealizationReference> fixed,
		Map<CompiledHopKey,Integer> fixedHandles, GenerationRoot generation) {
		if(metrics != null)
			metrics.recordTopologyOverlayEvaluation();
		if(generation != null)
			return generatedRootAlternative(key, pinned, witness, fixed, fixedHandles, generation);
		CandidateTopology topology = candidateTopology(key, witness);
		if(!topology.eligible)
			return List.of();
		// A topology owns immutable default edges, not query/grounding results.
		// Unrelated fixed owners cannot change any immediate dependency, so reuse
		// the enclosing canonical list too. Synthetic anchors and empty/template
		// cases intentionally remain on their existing query-local paths below.
		if((pinned != null || !topology.nodeDirectGround) && !topology.hasFixedDependency(fixed)) {
			List<SelectedCandidateProof> defaults = pinned == null ? topology.defaultAlternatives
				: topology.defaultAlternativesByHandle.getOrDefault(pinnedHandle, List.of());
			if(!defaults.isEmpty())
				return defaults;
		}
		List<SelectedCandidateProof> alternatives = new ArrayList<>();
		List<CandidateTopologyRow> overlayRows = pinned == null ? topology.rows
			: topology.rowsByHandle.getOrDefault(pinnedHandle, List.of());
		// The topology rows are already canonical. The old final sort only moved
		// the synthetic null realization to the front, so publish it first instead.
		if(pinned == null && topology.nodeDirectGround)
			alternatives.add(new SelectedCandidateProof(null, List.of(), true, witness));
		// A staging template is a proof obligation, not publication authority. Keep
		// its exact input dependencies in recursive loop SCCs; only grounded rows
		// with executable bindings are materialized by the caller.
		// Topology construction already deduplicates defaults by the equivalent edge key.
		// The first query-local overlay creates the only collision opportunity, so seed
		// every preceding default then check that row and all later defaults/overlays.
		Set<ContinuityEdgeKey> seen = null;
		for(int rowIndex = 0; rowIndex < overlayRows.size(); rowIndex++) {
			CandidateTopologyRow row = overlayRows.get(rowIndex);
			boolean queryOverlay = false;
			for(CandidateDependencySkeleton dependency : row.dependencies)
				if(fixed.containsKey(dependency.key)) {
					queryOverlay = true;
					break;
				}
			SelectedCandidateProof alternative;
			ContinuityEdgeKey edge;
			if(queryOverlay) {
				if(seen == null) {
					seen = new java.util.HashSet<>();
					for(int priorIndex = 0; priorIndex < rowIndex; priorIndex++)
						seen.add(overlayRows.get(priorIndex).defaultEdge);
				}
				List<CandidateProofDependency> dependencies =
					overlayDependencies(row.dependencies, fixed, fixedHandles);
				alternative = new SelectedCandidateProof(row.reference,
					dependencies, row.directGround, witness);
				edge = ContinuityEdgeKey.ofEffective(row.reference, row.directGround, dependencies);
			}
			else {
				alternative = row.defaultAlternative;
				edge = row.defaultEdge;
			}
			if(seen == null || seen.add(edge))
				alternatives.add(alternative);
			else if(metrics != null)
				metrics.recordTopologyOverlayRowCollapsed();
		}
		Hop hop = originsByKey.get(key);
		// Template fallback intentionally remains a separate query-overlay path. Its
		// predicates differ from normal candidate rows and must not be folded into the
		// shared topology merely because both name the same occurrence and witness.
		if(alternatives.isEmpty() && allowPinnedTemplate && pinned != null
			&& !declaresExactRealization(key, pinned))
			for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
				if(metrics != null)
					metrics.recordProofRowExamined();
				if(!fact.key().equals(pinned.rule()) || fact.status() != CandidateEvaluationStatus.AVAILABLE
					|| !operationPreservesWitness(hop, witness, fact))
					continue;
				boolean matchingEmission = fact.allowedEmissionFacts().stream().anyMatch(emission ->
					emission.emissionState().equals(pinned.realization().emissionState())
						&& emission.executionFType() == witness.fType && emission.derivedFoutAction() == null);
				if(!matchingEmission)
					continue;
				CandidateRealizationSupportClause templateClause =
					new CandidateRealizationSupportClause(List.of(), List.of());
				List<CandidateProofDependency> dependencies = candidateDependencies(
					fact, templateClause, hop, witness, fixed, fixedHandles);
				if(dependencies != null)
					alternatives.add(new SelectedCandidateProof(pinned, dependencies,
						false, witness));
			}
		return List.copyOf(alternatives);
	}

	private List<SelectedCandidateProof> generatedRootAlternative(CompiledHopKey key,
		CandidateRealizationReference proposed, NativePoolWitness witness,
		Map<CompiledHopKey,CandidateRealizationReference> fixed,
		Map<CompiledHopKey,Integer> fixedHandles, GenerationRoot generation) {
		CandidateRuleFact fact = generation.trustedBase();
		CandidateEmissionFact emission = generation.baseEmission();
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		PlacementState state = emission.emissionState().placementState();
		if(key != fact.key().parentOccurrence() || node == null || hop == null
			|| incompleteSources.contains(key)
			|| fact.status() != CandidateEvaluationStatus.AVAILABLE
			|| isBroadcastRowProvablyUnselectable(fact)
			|| !operationPreservesWitness(hop, witness, fact)
			|| state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
			|| state.fType() != witness.fType || emission.executionFType() != witness.fType
			|| emission.derivedFoutAction() != null
			|| node.legalAlternatives().stream().noneMatch(candidate -> candidate.execType() == ExecType.FED
				&& candidate.output() == FederatedOutput.FOUT && candidate.fType() == witness.fType))
			return List.of();
		CandidateRealizationSupportClause empty =
			new CandidateRealizationSupportClause(List.of(), List.of());
		List<CandidateProofDependency> dependencies = candidateDependencies(
			fact, empty, hop, witness, fixed, fixedHandles);
		return dependencies == null ? List.of()
			: List.of(new SelectedCandidateProof(proposed, dependencies, false, witness));
	}

	private boolean declaresExactRealization(CompiledHopKey key,
		CandidateRealizationReference pinned) {
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of()))
			if(fact.key().equals(pinned.rule()))
				for(var emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(CandidateRealizationReference.of(fact.key(), realization).equals(pinned))
							return true;
		return false;
	}

	private CandidateTopology candidateTopology(CompiledHopKey key, NativePoolWitness witness) {
		SearchSpaceMetrics.PhaseToken started = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		try {
			return candidateTopologyMeasured(key, witness);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY, started);
		}
	}

	private CandidateTopology candidateTopologyMeasured(CompiledHopKey key,
		NativePoolWitness witness) {
		CandidateTopologyKey topologyKey = new CandidateTopologyKey(key, witness);
		CandidateTopology cached = candidateTopologies.get(topologyKey);
		if(cached != null) {
			if(metrics != null)
				metrics.recordTopologyExpansion(true, 0);
			return cached;
		}
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		if(node == null || hop == null || incompleteSources.contains(key)
			|| node.legalAlternatives().stream().noneMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == witness.fType)) {
			CandidateTopology unavailable = new CandidateTopology(false, false, List.of(), Map.of());
			cacheTopology(topologyKey, unavailable);
			if(metrics != null)
				metrics.recordTopologyExpansion(false, 0);
			return unavailable;
		}
		boolean nodeDirectGround = node.anchors().stream()
			.anyMatch(anchor -> witness.matches(nativeWitness(anchor), true));
		List<CandidateTopologyRow> rows = new ArrayList<>();
		Set<ContinuityEdgeKey> seen = new java.util.HashSet<>();
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
			if(metrics != null)
				metrics.recordProofRowExamined();
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| isBroadcastRowProvablyUnselectable(fact)
				|| !operationPreservesWitness(hop, witness, fact))
				continue;
			for(var emission : fact.allowedEmissionFacts()) {
				if(metrics != null)
					metrics.recordProofRowExamined();
				var state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != witness.fType || emission.executionFType() != witness.fType
					|| emission.derivedFoutAction() != null)
					continue;
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(metrics != null)
						metrics.recordProofRowExamined();
					CandidateRealizationReference reference = CandidateRealizationReference.of(
						fact.key(), realization);
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						if(metrics != null)
							metrics.recordProofRowExamined();
						List<CandidateDependencySkeleton> dependencies = candidateDependencySkeletons(
							fact, clause, hop, witness);
						if(dependencies != null) {
							boolean realizationGround = realization.key().layoutKind()
								== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
								&& witness.matches(nativeWitness(realization.anchor()), true)
							|| clause.nativeWorkerPoolWitness() != null
									&& witness.matches(nativeWitness(clause.nativeWorkerPoolWitness()),
										clause.nativeWorkerPoolLayoutExact());
							CandidateTopologyRow row = CandidateTopologyRow.create(reference,
								dependencies, realizationGround, witness);
							if(seen.add(ContinuityEdgeKey.of(row)))
								rows.add(row);
							else if(metrics != null)
								metrics.recordTopologyRowCollapsed();
						}
					}
				}
			}
		}
		java.util.Comparator<CandidateRealizationReference> referenceOrder =
			PlacementAnalysis.canonicalComparator();
		List<CandidateTopologyRow> canonicalRows = rows.stream().sorted((left, right) ->
			referenceOrder.compare(left.reference, right.reference)).toList();
		Map<Integer,List<CandidateTopologyRow>> mutableRowsByHandle =
			new java.util.HashMap<>();
		for(CandidateTopologyRow row : canonicalRows)
			mutableRowsByHandle.computeIfAbsent(candidateHandle(row.reference),
				ignored -> new ArrayList<>()).add(row);
		mutableRowsByHandle.replaceAll((ignored, referenceRows) -> List.copyOf(referenceRows));
		CandidateTopology topology = new CandidateTopology(true, nodeDirectGround, canonicalRows,
			Collections.unmodifiableMap(mutableRowsByHandle));
		cacheTopology(topologyKey, topology);
		if(metrics != null)
			metrics.recordTopologyExpansion(false, canonicalRows.size());
		return topology;
	}

	private CandidateTopology reindexTopology(CandidateTopology topology) {
		if(!topology.eligible || topology.rows.isEmpty())
			return topology;
		List<CandidateTopologyRow> rows = new ArrayList<>(topology.rows.size());
		for(CandidateTopologyRow row : topology.rows) {
			List<CandidateDependencySkeleton> dependencies = row.dependencies.stream()
				.map(dependency -> new CandidateDependencySkeleton(dependency.key,
					dependency.clausePinned, candidateHandle(dependency.clausePinned),
					dependency.witness, dependency.inputPosition)).toList();
			rows.add(CandidateTopologyRow.create(row.reference, dependencies,
				row.directGround, row.defaultAlternative.witness()));
		}
		Map<Integer,List<CandidateTopologyRow>> rowsByHandle = new java.util.HashMap<>();
		for(CandidateTopologyRow row : rows)
			rowsByHandle.computeIfAbsent(candidateHandle(row.reference),
				ignored -> new ArrayList<>()).add(row);
		rowsByHandle.replaceAll((ignored, referenceRows) -> List.copyOf(referenceRows));
		return new CandidateTopology(true, topology.nodeDirectGround, List.copyOf(rows),
			Collections.unmodifiableMap(rowsByHandle));
	}

	private boolean cacheTopology(CandidateTopologyKey key, CandidateTopology topology) {
		long rows = topology.rows.size();
		if(topologyMaxEntries == 0 || topologyMaxRows == 0 || rows > topologyMaxRows) {
			if(metrics != null)
				metrics.recordTopologyCacheBypass();
			return false;
		}
		CandidateTopology prior = candidateTopologies.remove(key);
		if(prior != null)
			topologyRetainedRows -= prior.rows.size();
		while(!candidateTopologies.isEmpty()
			&& (candidateTopologies.size() >= topologyMaxEntries
				|| topologyRetainedRows + rows > topologyMaxRows)) {
			var oldest = candidateTopologies.entrySet().iterator().next();
			topologyRetainedRows -= oldest.getValue().rows.size();
			candidateTopologies.remove(oldest.getKey());
			if(metrics != null)
				metrics.recordTopologyCacheEviction();
		}
		candidateTopologies.put(key, topology);
		topologyRetainedRows += rows;
		if(metrics != null)
			metrics.recordTopologyCacheResident(candidateTopologies.size(), topologyRetainedRows);
		return true;
	}

	private List<CandidateProofDependency> candidateDependencies(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, NativePoolWitness witness,
		Map<CompiledHopKey,CandidateRealizationReference> fixed,
		Map<CompiledHopKey,Integer> fixedHandles) {
		List<CandidateDependencySkeleton> skeletons = candidateDependencySkeletons(
			fact, clause, owner, witness);
		return skeletons == null ? null : overlayDependencies(skeletons, fixed, fixedHandles);
	}

	private List<CandidateDependencySkeleton> candidateDependencySkeletons(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, NativePoolWitness witness) {
		Map<CompiledHopKey,CandidateRealizationReference> pinned = new IdentityHashMap<>();
		for(CandidateRealizationReference support : requiredInputSupport(clause)) {
			CompiledHopKey source = support.rule().parentOccurrence();
			CandidateRealizationReference prior = pinned.putIfAbsent(source, support);
			if(prior != null && !prior.equals(support)) {
				if(metrics != null)
					metrics.recordContradictoryClausePin();
				return null; // An AND clause cannot pin one decision owner to two realizations.
			}
		}
		List<CandidateDependencySkeleton> dependencies = new ArrayList<>();
		for(CompiledHopKey source : reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of())) {
			CandidateRealizationReference reference = pinned.get(source);
			dependencies.add(new CandidateDependencySkeleton(source, reference,
				candidateHandle(reference), witness, -1));
		}
		if(owner instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			return dependencies.isEmpty() ? dependencies : null;
		if(owner instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD)
			return (fact.key().orderedInputs().stream().anyMatch(input -> input.present())
				|| fact.key().orderedInputs().isEmpty() && clause.nativeWorkerPoolWitness() != null
					&& witness.matches(nativeWitness(clause.nativeWorkerPoolWitness()),
						clause.nativeWorkerPoolLayoutExact()))
				&& fact.key().orderedInputs().stream().noneMatch(input -> input.present()
					&& input.fType() != witness.fType) ? dependencies : null;
		Map<Integer,CompiledInputEdgeFact> edges = edgesByConsumer.getOrDefault(
			fact.key().parentOccurrence(), Map.of());
		boolean presentPlacementData = false;
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			var input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			CompiledInputEdgeFact edge = edges.get(position);
			boolean placementData = edge != null && isPlacementDataOrigin(edge.producer())
				|| edge == null && position < owner.getInput().size()
					&& isPlacementData(owner.getInput(position));
			if(!placementData)
				continue;
			presentPlacementData = true;
			NativePoolWitness dependencyWitness = dependencyWitness(
				owner, fact, witness, input.fType(), position);
			if(dependencyWitness == null || input.fType() != dependencyWitness.fType || edge == null)
				return null;
			CandidateRealizationReference reference = pinned.get(edge.producer());
			dependencies.add(new CandidateDependencySkeleton(edge.producer(), reference,
				candidateHandle(reference), dependencyWitness, position));
		}
		if(!presentPlacementData && !(transformEncodePreservesPool(owner, witness.fType)
			&& !reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of()).isEmpty()))
			return null;
		Map<ContinuityDependencyKey,CandidateDependencySkeleton> distinct = new java.util.LinkedHashMap<>();
		for(CandidateDependencySkeleton dependency : dependencies)
			distinct.putIfAbsent(ContinuityDependencyKey.of(dependency), dependency);
		return List.copyOf(distinct.values());
	}

	private List<CandidateProofDependency> overlayDependencies(
		List<CandidateDependencySkeleton> skeletons,
		Map<CompiledHopKey,CandidateRealizationReference> fixed,
		Map<CompiledHopKey,Integer> fixedHandles) {
		List<CandidateProofDependency> dependencies = new ArrayList<>(skeletons.size());
		for(CandidateDependencySkeleton skeleton : skeletons) {
			boolean queryPinned = fixed.containsKey(skeleton.key);
			CandidateRealizationReference pinned = queryPinned
				? fixed.get(skeleton.key) : skeleton.clausePinned;
			int handle = queryPinned ? fixedHandles.get(skeleton.key) : skeleton.clausePinnedHandle;
			dependencies.add(new CandidateProofDependency(skeleton.key, pinned,
				handle, skeleton.witness, skeleton.inputPosition, queryPinned));
		}
		return dependencies;
	}

	private List<CandidateRealizationReference> requiredInputSupport(
		CandidateRealizationSupportClause clause) {
		return requiredInputSupportByClause.computeIfAbsent(clause, ignored -> {
			List<CandidateRealizationReference> distinct = new ArrayList<>();
			for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
				CandidateRealizationReference source = binding.source();
				if(distinct.stream().noneMatch(existing -> existing.rule().parentOccurrence()
					== source.rule().parentOccurrence() && existing.equals(source)))
					distinct.add(source);
			}
			distinct.sort(PlacementAnalysis.canonicalComparator());
			return List.copyOf(distinct);
		});
	}

	private int candidateHandle(CandidateRealizationReference reference) {
		if(reference == null)
			return 0;
		Integer handle = candidateHandleByReference.get(reference);
		if(handle != null) {
			if(metrics != null)
				metrics.recordStructuralHandle(false, true);
			return handle;
		}
		Integer analysisHandle = PlacementIdentity.structuralHandle(reference);
		if(analysisHandle != null) {
			candidateHandleByReference.put(reference, analysisHandle);
			return analysisHandle;
		}
		handle = candidateHandleByStructure.get(reference);
		boolean created = handle == null;
		if(created) {
			handle = nextCandidateHandle--;
			candidateHandleByStructure.put(reference, handle);
		}
		candidateHandleByReference.put(reference, handle);
		if(metrics != null)
			metrics.recordStructuralHandle(created, false);
		return handle;
	}

	private static NativePoolWitness dependencyWitness(Hop owner, CandidateRuleFact fact,
		NativePoolWitness outputWitness, FType inputType, int position) {
		// ROW matmul is the one existing candidate family whose selected
		// BROADCAST RHS is a native same-pool input. Other families remain
		// fail-closed until their rule/runtime contract proves the same property.
		if(owner instanceof AggBinaryOp && outputWitness.fType == FType.ROW
			&& position == 1 && inputType == FType.BROADCAST)
			return new NativePoolWitness(FType.BROADCAST, outputWitness.endpoints, List.of(), true);

		if(owner instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS)
			return outputWitness.transposed();

		if(owner instanceof QuaternaryOp quaternary && quaternary.getOp() == OpOp4.WDIVMM
			&& position == 0 && isLeftWDivMM(quaternary) && outputWitness.fType == FType.ROW)
			return outputWitness.retyped(FType.COL);

		if(owner instanceof ParameterizedBuiltinOp parameterized
			&& parameterized.getOp() == ParamBuiltinOp.REXPAND
			&& position < owner.getInput().size() && owner.getInput(position) == parameterized.getTargetHop()) {
			if(outputWitness.fType == FType.FULL)
				return outputWitness;
			Boolean rows = rexpandRows(parameterized);
			if(rows == null)
				return null;
			if(rows && outputWitness.fType == FType.COL)
				return outputWitness.retyped(FType.ROW);
			if(!rows && outputWitness.fType == FType.ROW)
				return outputWitness;
			return null;
		}

		if(recomputesNativePartitionRanges(owner, outputWitness.fType)) {
			NativePoolWitness inputWitness = outputWitness.retypedForResidency(inputType);
			// Range-recomputing operations consume endpoint residency. Requiring exact
			// predecessor ranges here breaks valid chains such as REV -> ROLL, where
			// both runtime operations rebuild their output ranges on the same workers.
			if(inputWitness == null)
				return null;
			long placementInputs = owner == null ? 0 : java.util.stream.IntStream
				.range(0, Math.min(owner.getInput().size(), fact.key().orderedInputs().size()))
				.filter(inputPosition -> fact.key().orderedInputs().get(inputPosition).present())
				.filter(inputPosition -> isPlacementData(owner.getInput(inputPosition)))
				.count();
			return placementInputs == 1 ? inputWitness : inputWitness.withExactPartitionRanges();
		}
		return outputWitness;
	}

	record CandidateSupportResult(List<NativeContinuityProof> proofs,
		Set<CompiledHopKey> dependencyOccurrences) {
		CandidateSupportResult {
			proofs = Objects.requireNonNull(proofs, "proofs");
			Set<CompiledHopKey> immutable = Collections.newSetFromMap(new IdentityHashMap<>());
			immutable.addAll(dependencyOccurrences);
			dependencyOccurrences = Collections.unmodifiableSet(immutable);
		}
	}

	static final class NativeContinuityProof {
		private final DurableAnchorKey externalSeed;
		private final DurableAnchorKey outputWorkerPoolWitness;
		private final boolean exactPartitionRanges;
		private final List<CandidateRealizationInputBinding> immediateBindings;
		private String normalizedSignature;
		private final int hashCode;

		NativeContinuityProof(DurableAnchorKey externalSeed, DurableAnchorKey outputWorkerPoolWitness,
			boolean exactPartitionRanges, List<CandidateRealizationInputBinding> immediateBindings) {
			this.externalSeed = Objects.requireNonNull(externalSeed, "externalSeed");
			this.outputWorkerPoolWitness = Objects.requireNonNull(
				outputWorkerPoolWitness, "outputWorkerPoolWitness");
			this.exactPartitionRanges = exactPartitionRanges;
			// enumerateImmediateSupports walks positive dependency positions and each
			// position's canonical option list in order, so every leaf is already canonical.
			this.immediateBindings = PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				immediateBindings, "native proof immediate binding");
			int hash = externalSeed.hashCode();
			hash = 31 * hash + outputWorkerPoolWitness.hashCode();
			hash = 31 * hash + Boolean.hashCode(exactPartitionRanges);
			hashCode = 31 * hash + this.immediateBindings.hashCode();
		}

		DurableAnchorKey externalSeed() { return externalSeed; }
		DurableAnchorKey outputWorkerPoolWitness() { return outputWorkerPoolWitness; }
		boolean exactPartitionRanges() { return exactPartitionRanges; }
		List<CandidateRealizationInputBinding> immediateBindings() { return immediateBindings; }
		String normalizedSignature() {
			if(normalizedSignature == null)
				normalizedSignature = PlacementIdentity.cachedSignature(this);
			if(normalizedSignature == null) {
				String seedSignature = externalSeed.normalizedSignature();
				String outputSignature = outputWorkerPoolWitness.normalizedSignature();
				String[] bindingSignatures = new String[immediateBindings.size()];
				long estimatedCapacity = (long)seedSignature.length() + outputSignature.length()
					+ "|outputPool=".length() + "|partitionRanges=".length()
					+ (exactPartitionRanges ? "exact".length() : "dynamic".length())
					+ "|bindings=[]".length() + 2L * Math.max(0, immediateBindings.size() - 1);
				for(int index = 0; index < immediateBindings.size(); index++) {
					String bindingSignature = immediateBindings.get(index).normalizedSignature();
					bindingSignatures[index] = bindingSignature;
					estimatedCapacity += bindingSignature.length();
				}
				StringBuilder builder = new StringBuilder((int)Math.min(
					Integer.MAX_VALUE - 8L, estimatedCapacity));
				builder.append(seedSignature).append("|outputPool=").append(outputSignature)
					.append("|partitionRanges=")
					.append(exactPartitionRanges ? "exact" : "dynamic").append("|bindings=[");
				for(int index = 0; index < bindingSignatures.length; index++) {
					if(index > 0)
						builder.append(", ");
					builder.append(bindingSignatures[index]);
				}
				normalizedSignature = PlacementIdentity.rememberSignature(this, builder.append(']').toString());
			}
			return normalizedSignature;
		}

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof NativeContinuityProof that))
				return false;
			return exactPartitionRanges == that.exactPartitionRanges
				&& externalSeed.equals(that.externalSeed)
				&& outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness)
				&& immediateBindings.equals(that.immediateBindings);
		}
	}

	private static final class CandidateProofDependency {
		private final CompiledHopKey key;
		private final CandidateRealizationReference realization;
		private final int realizationHandle;
		private final NativePoolWitness witness;
		private final int inputPosition;
		private final boolean templateRoot;
		private final CandidateProofState state;
		private final int hashCode;

		private CandidateProofDependency(CompiledHopKey key,
			CandidateRealizationReference realization, int realizationHandle,
			NativePoolWitness witness, int inputPosition, boolean templateRoot) {
			this.key = key;
			this.realization = realization;
			this.realizationHandle = realizationHandle;
			this.witness = witness;
			this.inputPosition = inputPosition;
			this.templateRoot = templateRoot;
			state = new CandidateProofState(key, realization, realizationHandle, witness, templateRoot);
			int hash = 31 * System.identityHashCode(key) + realizationHandle;
			hash = 31 * hash + witness.hashCode();
			hash = 31 * hash + inputPosition;
			hashCode = 31 * hash + Boolean.hashCode(templateRoot);
		}

		private int inputPosition() { return inputPosition; }
		private CandidateProofState state() { return state; }

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof CandidateProofDependency that))
				return false;
			return inputPosition == that.inputPosition && templateRoot == that.templateRoot && key == that.key
				&& realizationHandle == that.realizationHandle && witness.equals(that.witness);
		}
	}

	private record CandidateDependencySkeleton(CompiledHopKey key,
		CandidateRealizationReference clausePinned, int clausePinnedHandle, NativePoolWitness witness,
		int inputPosition) { }

	private record ContinuityDependencyKey(CompiledHopKey key,
		CandidateRealizationReference pinned, int handle, NativePoolWitness witness,
		int inputPosition, boolean templateRoot) {
		private static ContinuityDependencyKey of(CandidateDependencySkeleton skeleton) {
			return new ContinuityDependencyKey(skeleton.key(), skeleton.clausePinned(),
				skeleton.clausePinnedHandle(), skeleton.witness(), skeleton.inputPosition(), false);
		}

		private static ContinuityDependencyKey of(CandidateProofDependency dependency) {
			return new ContinuityDependencyKey(dependency.key, dependency.realization,
				dependency.realizationHandle, dependency.witness, dependency.inputPosition,
				dependency.templateRoot);
		}

		@Override
		public int hashCode() {
			int hash = 31 * System.identityHashCode(key) + Objects.hashCode(pinned);
			hash = 31 * hash + handle;
			hash = 31 * hash + witness.hashCode();
			hash = 31 * hash + inputPosition;
			return 31 * hash + Boolean.hashCode(templateRoot);
		}

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof ContinuityDependencyKey that))
				return false;
			return key == that.key && Objects.equals(pinned, that.pinned) && handle == that.handle
				&& witness.equals(that.witness) && inputPosition == that.inputPosition
				&& templateRoot == that.templateRoot;
		}
	}

	private record ContinuityEdgeKey(CandidateRealizationReference reference,
		boolean directGround, List<ContinuityDependencyKey> dependencies) {
		private ContinuityEdgeKey {
			dependencies = List.copyOf(dependencies);
		}

		private static ContinuityEdgeKey of(CandidateTopologyRow row) {
			return new ContinuityEdgeKey(row.reference(), row.directGround(),
				row.dependencies().stream().map(ContinuityDependencyKey::of).toList());
		}

		private static ContinuityEdgeKey ofEffective(CandidateRealizationReference reference,
			boolean directGround, List<CandidateProofDependency> dependencies) {
			return new ContinuityEdgeKey(reference, directGround,
				dependencies.stream().map(ContinuityDependencyKey::of).toList());
		}
	}

	private record CandidateTopologyRow(CandidateRealizationReference reference,
		List<CandidateDependencySkeleton> dependencies, boolean directGround,
		SelectedCandidateProof defaultAlternative, ContinuityEdgeKey defaultEdge) {
		private CandidateTopologyRow {
			dependencies = List.copyOf(dependencies);
			Objects.requireNonNull(defaultAlternative, "defaultAlternative");
			Objects.requireNonNull(defaultEdge, "defaultEdge");
		}

		private static CandidateTopologyRow create(CandidateRealizationReference reference,
			List<CandidateDependencySkeleton> dependencies, boolean directGround,
			NativePoolWitness witness) {
			List<CandidateDependencySkeleton> immutable = List.copyOf(dependencies);
			List<CandidateProofDependency> defaults = immutable.stream().map(skeleton ->
				new CandidateProofDependency(skeleton.key(), skeleton.clausePinned(),
					skeleton.clausePinnedHandle(), skeleton.witness(),
					skeleton.inputPosition(), false)).toList();
			SelectedCandidateProof alternative = new SelectedCandidateProof(
				reference, defaults, directGround, witness);
			return new CandidateTopologyRow(reference, immutable, directGround, alternative,
				ContinuityEdgeKey.ofEffective(reference, directGround, defaults));
		}
	}

	private static final class CandidateTopology {
		private final boolean eligible;
		private final boolean nodeDirectGround;
		private final List<CandidateTopologyRow> rows;
		private final Map<Integer,List<CandidateTopologyRow>> rowsByHandle;
		private final List<SelectedCandidateProof> defaultAlternatives;
		private final Map<Integer,List<SelectedCandidateProof>> defaultAlternativesByHandle;
		private final Set<CompiledHopKey> dependencyOwners;

		private CandidateTopology(boolean eligible, boolean nodeDirectGround,
			List<CandidateTopologyRow> rows, Map<Integer,List<CandidateTopologyRow>> rowsByHandle) {
			this.eligible = eligible;
			this.nodeDirectGround = nodeDirectGround;
			this.rows = List.copyOf(rows);
			this.rowsByHandle = Map.copyOf(rowsByHandle);
			defaultAlternatives = this.rows.stream().map(row -> row.defaultAlternative).toList();
			Map<Integer,List<SelectedCandidateProof>> defaults = new java.util.HashMap<>();
			this.rowsByHandle.forEach((handle, handleRows) -> defaults.put(handle,
				handleRows.stream().map(row -> row.defaultAlternative).toList()));
			defaultAlternativesByHandle = Map.copyOf(defaults);
			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			for(CandidateTopologyRow row : this.rows)
				for(CandidateDependencySkeleton dependency : row.dependencies)
					owners.add(dependency.key);
			dependencyOwners = Collections.unmodifiableSet(owners);
		}

		private boolean hasFixedDependency(Map<CompiledHopKey,CandidateRealizationReference> fixed) {
			// Production query pins are identity maps over analysis-owned occurrences.
			for(CompiledHopKey owner : fixed.keySet())
				if(dependencyOwners.contains(owner))
					return true;
			return false;
		}
	}

	private record RootTopologyRowKey(List<ContinuityDependencyKey> dependencies,
		boolean directGround) {
		private RootTopologyRowKey {
			dependencies = List.copyOf(dependencies);
		}
	}

	private static final class AcyclicRootSupportKey {
		private final CompiledHopKey occurrence;
		private final NativePoolWitness witness;
		private final List<RootTopologyRowKey> relation;
		private final int hashCode;

		private AcyclicRootSupportKey(CompiledHopKey occurrence, NativePoolWitness witness,
			List<RootTopologyRowKey> relation) {
			this.occurrence = occurrence;
			this.witness = witness;
			this.relation = List.copyOf(relation);
			int hash = 31 * System.identityHashCode(occurrence) + witness.hashCode();
			hashCode = 31 * hash + this.relation.hashCode();
		}

		@Override public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof AcyclicRootSupportKey that
				&& occurrence == that.occurrence && witness.equals(that.witness)
				&& relation.equals(that.relation);
		}
	}

	private static final class CandidateTopologyKey {
		private final CompiledHopKey occurrence;
		private final NativePoolWitness witness;
		private final int hashCode;

		private CandidateTopologyKey(CompiledHopKey occurrence, NativePoolWitness witness) {
			this.occurrence = Objects.requireNonNull(occurrence, "topology occurrence");
			this.witness = Objects.requireNonNull(witness, "topology witness");
			hashCode = 31 * System.identityHashCode(occurrence) + witness.hashCode();
		}

		@Override public int hashCode() { return hashCode; }

		@Override public boolean equals(Object other) {
			return this == other || other instanceof CandidateTopologyKey that
				&& occurrence == that.occurrence && witness.equals(that.witness);
		}
	}

	private static final class CandidateQueryKey {
		private final CompiledHopKey rootOccurrence;
		private final CandidateRealizationReference source;
		private final NativePoolWitness witness;
		private final boolean generated;
		private final int hashCode;

		private CandidateQueryKey(CandidateRealizationReference source, NativePoolWitness witness,
			GenerationRoot generation) {
			this.rootOccurrence = source.rule().parentOccurrence();
			this.source = source;
			this.witness = witness;
			generated = generation != null;
			int hash = 31 * System.identityHashCode(rootOccurrence) + source.hashCode();
			hash = 31 * hash + witness.hashCode();
			hashCode = 31 * hash + Boolean.hashCode(generated);
		}

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof CandidateQueryKey that))
				return false;
			return rootOccurrence == that.rootOccurrence && source.equals(that.source)
				&& witness.equals(that.witness) && generated == that.generated;
		}
	}

	private static final class CandidateSupportQueryKey {
		private final CompiledHopKey rootOccurrence;
		private final CandidateRuleKey rule;
		private final PlacementEmissionState emissionState;
		private final int exactSourceHandle;
		private final NativePoolWitness witness;
		private final boolean generated;
		private final int hashCode;

		private CandidateSupportQueryKey(CandidateRealizationReference source, int sourceHandle,
			NativePoolWitness witness, boolean exactTopologyRow, boolean generated) {
			rootOccurrence = source.rule().parentOccurrence();
			rule = source.rule();
			emissionState = source.realization().emissionState();
			exactSourceHandle = exactTopologyRow ? sourceHandle : 0;
			this.witness = witness;
			this.generated = generated;
			int hash = 31 * System.identityHashCode(rootOccurrence) + witness.hashCode();
			if(exactSourceHandle != 0)
				hash = 31 * hash + exactSourceHandle;
			else {
				hash = 31 * hash + rule.hashCode();
				hash = 31 * hash + emissionState.hashCode();
			}
			hashCode = 31 * hash + Boolean.hashCode(generated);
		}

		@Override public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof CandidateSupportQueryKey that)
				|| rootOccurrence != that.rootOccurrence || !witness.equals(that.witness)
				|| generated != that.generated
				|| (exactSourceHandle == 0) != (that.exactSourceHandle == 0))
				return false;
			return exactSourceHandle != 0 ? exactSourceHandle == that.exactSourceHandle
				: rule.equals(that.rule) && emissionState.equals(that.emissionState);
		}
	}

	private static final class PublicCandidateQueryKey {
		private final CompiledHopKey rootOccurrence;
		private final CandidateRealizationReference source;
		private final DurableAnchorKey externalSeed;
		private final boolean generated;
		private final int hashCode;

		private PublicCandidateQueryKey(CandidateRealizationReference source,
			DurableAnchorKey externalSeed, boolean generated) {
			rootOccurrence = source.rule().parentOccurrence();
			this.source = source;
			this.externalSeed = externalSeed;
			this.generated = generated;
			int hash = 31 * System.identityHashCode(rootOccurrence) + source.hashCode();
			hash = 31 * hash + externalSeed.hashCode();
			hashCode = 31 * hash + Boolean.hashCode(generated);
		}

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof PublicCandidateQueryKey that))
				return false;
			return rootOccurrence == that.rootOccurrence && source.equals(that.source)
				&& externalSeed.equals(that.externalSeed)
				&& generated == that.generated;
		}
	}

	private record GenerationRoot(CandidateRuleFact trustedBase,
		CandidateEmissionFact baseEmission) {
		private GenerationRoot {
			Objects.requireNonNull(trustedBase, "trusted generator base");
			Objects.requireNonNull(baseEmission, "generator base emission");
		}
	}

	private record MemoEntry(List<NativeContinuityProof> proofs, long estimatedBytes,
		Set<CompiledHopKey> occurrences) { }
	private record ComputedPublicProof(List<NativeContinuityProof> proofs,
		Set<CompiledHopKey> occurrences) { }
	private record CandidateSupportTemplate(DurableAnchorKey outputWorkerPoolWitness,
		boolean exactPartitionRanges,
		List<CandidateRealizationInputBinding> immediateBindings) {
		private CandidateSupportTemplate {
			immediateBindings = List.copyOf(immediateBindings);
		}
	}
	private record ComputedCandidateSupport(List<CandidateSupportTemplate> templates,
		Set<CompiledHopKey> occurrences, boolean rootIndependent) { }
	private record SupportMemoEntry(CandidateRealizationReference root,
		List<CandidateSupportTemplate> templates, Set<CompiledHopKey> occurrences,
		long estimatedBytes, boolean rootIndependent) {
		private SupportMemoEntry {
			templates = List.copyOf(templates);
			occurrences = Collections.unmodifiableSet(occurrences);
		}
	}
	private record AcyclicComponentSummary(List<SelectedCandidateProof> groundedAlternatives,
		Set<CompiledHopKey> occurrences, long retainedStates) {
		private AcyclicComponentSummary {
			groundedAlternatives = List.copyOf(groundedAlternatives);
			Set<CompiledHopKey> immutableOccurrences =
				Collections.newSetFromMap(new IdentityHashMap<>());
			immutableOccurrences.addAll(occurrences);
			occurrences = Collections.unmodifiableSet(immutableOccurrences);
		}
	}
	private record AcyclicComponentFootprint(Set<CompiledHopKey> occurrences,
		long retainedStates) { }
	private static final class CandidateProofTraversal {
		private final Set<CandidateProofState> active = new java.util.HashSet<>();
		private final List<CandidateProofState> completionOrder = new ArrayList<>();
		private final Map<CandidateProofState,AcyclicComponentSummary> reusedComponents =
			new java.util.HashMap<>();
		private boolean cycleDetected;
	}

	private static final class CandidateProofState {
		private final CompiledHopKey key;
		private final CandidateRealizationReference realization;
		private final int realizationHandle;
		private final NativePoolWitness witness;
		private final boolean templateRoot;
		private final int hashCode;

		private CandidateProofState(CompiledHopKey key,
			CandidateRealizationReference realization, int realizationHandle,
			NativePoolWitness witness, boolean templateRoot) {
			this.key = key;
			this.realization = realization;
			this.realizationHandle = realizationHandle;
			this.witness = witness;
			this.templateRoot = templateRoot;
			int hash = 31 * System.identityHashCode(key) + realizationHandle;
			hash = 31 * hash + witness.hashCode();
			hashCode = 31 * hash + Boolean.hashCode(templateRoot);
		}

		private CompiledHopKey key() { return key; }
		private CandidateRealizationReference realization() { return realization; }
		private int realizationHandle() { return realizationHandle; }
		private NativePoolWitness witness() { return witness; }
		private boolean templateRoot() { return templateRoot; }

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof CandidateProofState that))
				return false;
			return templateRoot == that.templateRoot && key == that.key
				&& realizationHandle == that.realizationHandle && witness.equals(that.witness);
		}
	}
	private record SelectedCandidateProof(CandidateRealizationReference realization,
		List<CandidateProofDependency> dependencies,
		boolean directGround, NativePoolWitness witness) { }

	private void buildProof(CompiledHopKey key, NativePoolWitness witness,
		Map<CompiledHopKey,ProofNode> proof) {
		if(proof.containsKey(key))
			return;
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		ProofNode current = new ProofNode();
		proof.put(key, current);
		if(node == null || hop == null) {
			current.valid = false;
			return;
		}
		if(node.legalAlternatives().stream().noneMatch(state -> state.execType() == ExecType.FED
			&& state.output() == FederatedOutput.FOUT && state.fType() == witness.fType))
			current.valid = false;
		current.directGround = node.anchors().stream()
			.anyMatch(anchor -> witness.matches(nativeWitness(anchor), true));

		if(incompleteSources.contains(key))
			current.valid = false;
		for(CompiledHopKey source : reachingDefinitions.getOrDefault(key, List.of()))
			addDependency(current, source);

		boolean matchedNativeRow = false;
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			if(isBroadcastRowProvablyUnselectable(fact))
				continue;
			boolean matchingNativeRow = false;
			for(var emission : fact.allowedEmissionFacts()) {
				var state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != witness.fType)
					continue;
				if(emission.executionFType() == witness.fType && emission.derivedFoutAction() == null)
					matchingNativeRow = true;
			}
			if(!matchingNativeRow)
				continue;
			matchedNativeRow = true;
			if(!operationPreservesWitness(hop, witness, fact))
				current.valid = false;
			collectCandidateDependencies(fact, hop, witness, current);
		}

		boolean transientWrite = hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE;
		boolean computedValue = hop instanceof AggUnaryOp || hop instanceof UnaryOp || hop instanceof BinaryOp
			|| hop instanceof ReorgOp || hop instanceof TernaryOp || hop instanceof NaryOp
			|| hop instanceof ParameterizedBuiltinOp || hop instanceof LeftIndexingOp || hop instanceof QuaternaryOp;
		if(!matchedNativeRow && (transientWrite || requiresExactNativeRow(hop)
			|| computedValue && !current.directGround))
			current.valid = false;
		for(CompiledHopKey dependency : current.dependencies)
			buildProof(dependency, witness, proof);
	}

	private boolean isBroadcastRowProvablyUnselectable(CandidateRuleFact fact) {
		Map<Integer,CompiledInputEdgeFact> edges = edgesByConsumer
			.getOrDefault(fact.key().parentOccurrence(), Map.of());
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			var input = fact.key().orderedInputs().get(position);
			if(!input.present() || input.fType() != FType.BROADCAST)
				continue;
			CompiledInputEdgeFact edge = edges.get(position);
			Node source = edge == null ? null : nodesByKey.get(edge.producer());
			if(source == null)
				return false;
			boolean direct = nodesByKey.values().stream()
				.filter(alias -> alias.valueVersion().equals(source.valueVersion()))
				.anyMatch(alias -> alias.legalAlternatives().stream().anyMatch(state ->
					state.output() == FederatedOutput.FOUT && state.fType() == FType.BROADCAST));
			Privacy privacy = privacyByKey.get(source.key());
			if(!direct && privacy != null && ExecPlacementPolicy.requiresOriginResidency(privacy))
				return true;
		}
		return false;
	}

	private void collectCandidateDependencies(CandidateRuleFact fact, Hop owner,
		NativePoolWitness witness, ProofNode proof) {
		if(owner instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			if(!proof.directGround)
				proof.valid = false;
			return;
		}
		if(owner instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD) {
			if(fact.key().orderedInputs().stream().noneMatch(input -> input.present()))
				proof.valid = false;
			else if(fact.key().orderedInputs().stream()
				.anyMatch(input -> input.present() && input.fType() != witness.fType))
				proof.valid = false;
			return;
		}
		Map<Integer,CompiledInputEdgeFact> edges = edgesByConsumer.getOrDefault(
			fact.key().parentOccurrence(), Map.of());
		boolean presentPlacementData = false;
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			var input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			CompiledInputEdgeFact edge = edges.get(position);
			boolean placementData = edge != null && isPlacementDataOrigin(edge.producer())
				|| edge == null && position < owner.getInput().size()
					&& isPlacementData(owner.getInput(position));
			if(!placementData)
				continue;
			presentPlacementData = true;
			NativePoolWitness dependencyWitness = dependencyWitness(
				owner, fact, witness, input.fType(), position);
			if(dependencyWitness == null || input.fType() != dependencyWitness.fType || edge == null) {
				proof.valid = false;
				continue;
			}
			addDependency(proof, edge.producer());
		}
		if(!presentPlacementData && !(transformEncodePreservesPool(owner, witness.fType)
			&& !reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of()).isEmpty()))
			proof.valid = false;
	}

	private boolean isPlacementDataOrigin(CompiledHopKey key) {
		return isPlacementData(originsByKey.get(key));
	}

	private static boolean isPlacementData(Hop hop) {
		return hop != null && (hop.getDataType().isMatrix() || hop.getDataType().isFrame());
	}

	private static boolean requiresExactNativeRow(Hop hop) {
		if(hop instanceof AggUnaryOp || hop instanceof LeftIndexingOp || hop instanceof UnaryOp)
			return true;
		return hop instanceof BinaryOp binary && binary.getInput().size() == 2
			&& binary.getInput(0).getDataType().isMatrix()
			&& binary.getInput(1).getDataType().isMatrix();
	}

	private static boolean operationPreservesWitness(Hop hop, NativePoolWitness witness, CandidateRuleFact fact) {
		if(witness.fType == FType.FULL && !witness.exactPartitionRanges
			&& !preservesDynamicFullResidency(hop))
			return false;
		if(!witness.exactPartitionRanges && !recomputesNativePartitionRanges(hop, witness.fType)
			&& requiresExactPartitionAlignment(hop, witness, fact))
			return false;
		if(transformEncodePreservesPool(hop, witness.fType)
			|| hop instanceof FunctionOp call && call.getOutputs() != null && !call.getOutputs().isEmpty()
				&& FederatedPlannerUtils.getMultiReturnFunctionOutputParent(call.getOutputs().get(0)) == call
				&& transformEncodePreservesPool(call.getOutputs().get(0), witness.fType))
			return true;
		if(hop instanceof AggUnaryOp aggregate && aggregate.getInput().size() == 1) {
			var inputs = fact.key().orderedInputs();
			if(inputs.size() != 1 || !inputs.get(0).present() || inputs.get(0).fType() != witness.fType)
				return false;
			return switch(witness.fType) {
				case BROADCAST -> true;
				case FULL -> witness.singleEndpoint();
				case ROW -> aggregate.getDirection() == Direction.Row;
				case COL -> aggregate.getDirection() == Direction.Col;
				default -> false;
			};
		}
		if(hop instanceof AggBinaryOp aggregate && aggregate.getInput().size() == 2) {
			var inputs = fact.key().orderedInputs();
			if(inputs.size() != 2)
				return false;
			boolean leftMatrix = aggregate.getInput(0).getDataType().isMatrix();
			boolean rightMatrix = aggregate.getInput(1).getDataType().isMatrix();
			if(!leftMatrix || !rightMatrix)
				return false;
			// The existing FULL kernel executes the complete product on one worker.
			// Exact input dependencies still prove the chosen FULL operands; local
			// companions use the oracle-authorized native broadcast, not a new map.
			if(witness.fType == FType.FULL && witness.singleEndpoint())
				return inputs.stream().anyMatch(input -> input.present() && input.fType() == FType.FULL)
					&& inputs.stream().allMatch(input -> !input.present() || input.fType() == FType.FULL);
			return witness.fType == FType.ROW
				&& inputs.get(0).present() && inputs.get(0).fType() == FType.ROW
				&& (!inputs.get(1).present() || inputs.get(1).fType() == FType.BROADCAST)
				|| witness.fType == FType.BROADCAST && !inputs.get(0).present()
					&& inputs.get(1).present() && inputs.get(1).fType() == FType.BROADCAST;
		}
		if(hop instanceof LeftIndexingOp) {
			var inputs = fact.key().orderedInputs();
			if(witness.fType != FType.FULL || !witness.singleEndpoint() || inputs.size() != hop.getInput().size()
				|| inputs.size() < 2 || inputs.subList(2, inputs.size()).stream().anyMatch(input -> input.present()))
				return false;
			var lhs = inputs.get(0);
			var rhs = inputs.get(1);
			return hop.getInput(1).getDataType().isMatrix()
				? rhs.present() && rhs.fType() == FType.FULL && (!lhs.present() || lhs.fType() == FType.FULL)
				: hop.getInput(1).getDataType().isScalar() && !rhs.present()
					&& lhs.present() && lhs.fType() == FType.FULL;
		}
		if(hop instanceof DataOp data)
			return data.getOp() == OpOpData.FEDERATED || data.getOp() == OpOpData.TRANSIENTREAD
				|| data.getOp() == OpOpData.TRANSIENTWRITE;
		if(hop instanceof UnaryOp unary && unary.getInput().size() == 1) {
			if(unary.getOp() == OpOp1.CAST_AS_FRAME && unary.getDataType().isFrame()
				&& unary.getInput(0).getDataType().isMatrix())
				return true;
			if(unary.getOp() == OpOp1.CAST_AS_MATRIX && unary.getDataType().isMatrix()
				&& unary.getInput(0).getDataType().isFrame())
				return true;
		}
		if(hop instanceof UnaryOp unary) {
			var inputs = fact.key().orderedInputs();
			return unary.getDataType().isMatrix() && unary.getInput().size() == 1
				&& unary.getInput(0).getDataType().isMatrix()
				&& (NATIVE_UNARY_ELEMWISE_OPCODES.contains(unary.getOp().toString())
					|| NATIVE_UNARY_CUMULATIVE_OPCODES.contains(unary.getOp().toString()))
				&& inputs.size() == 1 && inputs.get(0).present()
				&& inputs.get(0).fType() == witness.fType
				&& (!NATIVE_UNARY_CUMULATIVE_OPCODES.contains(unary.getOp().toString())
					|| witness.fType == FType.ROW || witness.fType == FType.COL);
		}
		if(hop instanceof QuaternaryOp quaternary) {
			var inputs = fact.key().orderedInputs();
			if(inputs.isEmpty() || !inputs.get(0).present())
				return false;
			FType primary = inputs.get(0).fType();
			if(quaternary.getOp() == OpOp4.WSIGMOID || quaternary.getOp() == OpOp4.WUMM)
				return (witness.fType == FType.ROW || witness.fType == FType.COL)
					&& primary == witness.fType;
			if(quaternary.getOp() != OpOp4.WDIVMM)
				return false;
			if(witness.fType == FType.FULL)
				return witness.singleEndpoint() && primary == FType.FULL;
			if(isBasicWDivMM(quaternary))
				return (witness.fType == FType.ROW || witness.fType == FType.COL)
					&& primary == witness.fType;
			if(isLeftWDivMM(quaternary))
				return witness.fType == FType.ROW && primary == FType.COL;
			return isRightWDivMM(quaternary) && witness.fType == FType.ROW && primary == FType.ROW;
		}
		if(hop instanceof BinaryOp binary && (binary.getOp() == OpOp2.CBIND || binary.getOp() == OpOp2.RBIND)) {
			if(witness.fType == FType.ROW)
				return binary.getOp() == OpOp2.CBIND;
			if(witness.fType == FType.COL)
				return binary.getOp() == OpOp2.RBIND;
			return witness.fType == FType.FULL && witness.singleEndpoint();
		}
		if(hop instanceof BinaryOp binary && binary.getInput().size() == 2) {
			boolean leftMatrix = binary.getInput(0).getDataType().isMatrix();
			boolean rightMatrix = binary.getInput(1).getDataType().isMatrix();
			if(leftMatrix != rightMatrix)
				return true;
			var inputs = fact.key().orderedInputs();
			if(leftMatrix && rightMatrix && NATIVE_BINARY_ELEMWISE_OPCODES.contains(binary.getOp().toString())
				&& inputs.size() == 2) {
				long residentInputs = inputs.stream()
					.filter(input -> input.present() && input.fType() == witness.fType).count();
				if(witness.fType == FType.FULL)
					return witness.singleEndpoint() && residentInputs > 0
						&& inputs.stream().allMatch(input -> !input.present() || input.fType() == FType.FULL);
				return (witness.fType == FType.ROW || witness.fType == FType.COL
					|| witness.fType == FType.BROADCAST)
					&& residentInputs > 0
					&& inputs.stream().allMatch(input -> !input.present() || input.fType() == witness.fType);
			}
		}
		if(hop instanceof NaryOp nary)
			return nary.getOp() == OpOpN.PLUS || nary.getOp() == OpOpN.MULT
				|| nary.getOp() == OpOpN.MIN || nary.getOp() == OpOpN.MAX;
		if(hop instanceof TernaryOp ternary) {
			if(ternary.getOp() == OpOp3.MAP) {
				var inputs = fact.key().orderedInputs();
				for(int i = 0; i < inputs.size() && i < ternary.getInput().size(); i++)
					if(ternary.getInput(i).getDataType().isFrame())
						return inputs.get(i).present() && inputs.get(i).fType() == witness.fType;
				return false;
			}
			return ternary.getOp() == OpOp3.PLUS_MULT || ternary.getOp() == OpOp3.MINUS_MULT
				|| ternary.getOp() == OpOp3.IFELSE
				|| ternary.getOp() == OpOp3.CTABLE && !witness.exactPartitionRanges;
		}
		if(hop instanceof ParameterizedBuiltinOp parameterized) {
			if(parameterized.getOp() == ParamBuiltinOp.REPLACE)
				return true;
			var inputs = fact.key().orderedInputs();
			Integer targetPosition = parameterized.getParamIndexMap().get("target");
			if(targetPosition == null || targetPosition >= inputs.size() || !inputs.get(targetPosition).present())
				return false;
			FType targetType = inputs.get(targetPosition).fType();
			if(parameterized.getOp() == ParamBuiltinOp.RMEMPTY)
				return targetType == witness.fType
					&& (witness.fType != FType.ROW && witness.fType != FType.COL
						|| !witness.exactPartitionRanges);
			if(parameterized.getOp() == ParamBuiltinOp.REXPAND) {
				if(witness.fType == FType.FULL)
					return witness.singleEndpoint() && targetType == FType.FULL;
				Boolean rows = rexpandRows(parameterized);
				return rows != null && targetType == FType.ROW
					&& (rows ? witness.fType == FType.COL : witness.fType == FType.ROW);
			}
			return false;
		}
		if(hop instanceof ReorgOp reorg) {
			if(reorg.getOp() == ReOrgOp.TRANS) {
				if(witness.fType == FType.FULL)
					return witness.singleEndpoint();
				if(witness.fType == FType.BROADCAST)
					return true;
				NativePoolWitness inputWitness = witness.transposed();
				return inputWitness != null && fact.key().orderedInputs().size() == 1
					&& fact.key().orderedInputs().get(0).present()
					&& fact.key().orderedInputs().get(0).fType() == inputWitness.fType;
			}
			if(reorg.getOp() == ReOrgOp.ROLL)
				return !witness.exactPartitionRanges && (witness.fType == FType.ROW
					|| witness.fType == FType.COL
					|| witness.fType == FType.FULL && witness.singleEndpoint());
			if(reorg.getOp() == ReOrgOp.RESHAPE)
				return (witness.fType == FType.ROW || witness.fType == FType.COL)
					&& !witness.exactPartitionRanges;
			if(reorg.getOp() == ReOrgOp.REV) {
				if(witness.fType == FType.ROW)
					return !witness.exactPartitionRanges;
				return witness.fType == FType.COL || witness.fType == FType.BROADCAST
					|| witness.fType == FType.FULL && witness.singleEndpoint();
			}
			if(reorg.getOp() == ReOrgOp.DIAG) {
				if(witness.fType == FType.ROW)
					return !witness.exactPartitionRanges;
				return witness.fType == FType.BROADCAST
					|| witness.fType == FType.FULL && witness.singleEndpoint();
			}
		}
		if(hop instanceof IndexingOp index && hop.getDataType().isMatrix()
			&& !hop.getInput().isEmpty() && hop.getInput(0).getDataType().isMatrix())
			return witness.fType == FType.FULL && witness.singleEndpoint()
				|| exactFullRowColumnSlice(index, witness, fact);
		return false;
	}

	/** A valid column slice of every row keeps every ROW partition on its original worker. */
	private static boolean exactFullRowColumnSlice(IndexingOp index, NativePoolWitness witness,
		CandidateRuleFact fact) {
		if(witness.fType != FType.ROW || index.getInput().size() != 5
			|| !witness.exactPartitionRanges || !retainsRuntimeRowMapType(witness)
			|| fact.key().orderedInputs().size() != 5 || !index.isAllRows()
			|| fact.key().orderedInputs().get(0).fType() != FType.ROW
			|| !fact.key().orderedInputs().get(0).present()
			|| fact.key().orderedInputs().subList(1, 5).stream().anyMatch(CandidateInputState::present))
			return false;
		// The FED rightIndex keeps the ROW partition axis for every valid column
		// interval, even when its endpoints are scalar expressions. Do not claim
		// that the other (column) axis or complete 2D map stays unchanged.
		if(!(index.getInput(3) instanceof LiteralOp colLower)
			|| !(index.getInput(4) instanceof LiteralOp colUpper))
			return true;
		long columns = index.getInput(0).getDim2();
		return columns > 0 && colLower.getLongValue() >= 1
			&& colUpper.getLongValue() >= colLower.getLongValue()
			&& colUpper.getLongValue() <= columns;
	}

	/** FederationMap.filter retypes one ROW range to FULL and overlapping full ranges to BROADCAST. */
	private static boolean retainsRuntimeRowMapType(NativePoolWitness witness) {
		List<AxisInterval> intervals = witness.partitionAxisIntervals.stream()
			.sorted(java.util.Comparator.comparingLong(AxisInterval::begin)
				.thenComparingLong(AxisInterval::end)).toList();
		if(intervals.size() < 2)
			return false;
		for(int i = 0; i < intervals.size(); i++)
			if(intervals.get(i).begin() < 0 || intervals.get(i).end() <= intervals.get(i).begin()
				|| i > 0 && intervals.get(i - 1).end() > intervals.get(i).begin())
				return false;
		return true;
	}

	private static boolean requiresExactPartitionAlignment(Hop hop, NativePoolWitness witness,
		CandidateRuleFact fact) {
		if(witness.fType != FType.ROW && witness.fType != FType.COL)
			return false;
		int alignedInputs = 0;
		for(int i = 0; i < fact.key().orderedInputs().size() && i < hop.getInput().size(); i++)
			if(fact.key().orderedInputs().get(i).present()
				&& fact.key().orderedInputs().get(i).fType() == witness.fType
				&& isPlacementData(hop.getInput(i)))
				alignedInputs++;
		return alignedInputs > 1;
	}

	static boolean recomputesNativePartitionRanges(Hop hop) {
		return recomputesNativePartitionRanges(hop, null);
	}

	static boolean recomputesNativePartitionRanges(Hop hop, FType outputType) {
		if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.REV)
			return outputType == null || outputType == FType.ROW;
		if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.ROLL)
			return outputType == null || outputType == FType.ROW || outputType == FType.COL
				|| outputType == FType.FULL;
		if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.DIAG)
			return outputType == null || outputType == FType.ROW;
		return hop instanceof ParameterizedBuiltinOp parameterized
			&& parameterized.getOp() == ParamBuiltinOp.RMEMPTY
			|| hop instanceof TernaryOp ternary && ternary.getOp() == OpOp3.CTABLE
				|| hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.RESHAPE;
	}

	private static boolean preservesDynamicFullResidency(Hop hop) {
		if(hop instanceof ReorgOp reorg)
			return reorg.getOp() == ReOrgOp.ROLL || reorg.getOp() == ReOrgOp.REV;
		if(hop instanceof AggUnaryOp aggregate)
			return aggregate.getInput().size() == 1;
		if(hop instanceof UnaryOp unary)
			return unary.getInput().size() == 1 && unary.getInput(0).getDataType().isMatrix()
				&& NATIVE_UNARY_ELEMWISE_OPCODES.contains(unary.getOp().toString());
		if(hop instanceof BinaryOp binary)
			return binary.getInput().stream().filter(NativePlacementContinuity::isPlacementData).count() == 1;
		if(hop instanceof DataOp data)
			return data.getOp() == OpOpData.FEDERATED || data.getOp() == OpOpData.TRANSIENTREAD
				|| data.getOp() == OpOpData.TRANSIENTWRITE;
		return hop instanceof ParameterizedBuiltinOp parameterized
			&& parameterized.getOp() == ParamBuiltinOp.REPLACE;
	}

	private static Boolean rexpandRows(ParameterizedBuiltinOp parameterized) {
		Hop dir = parameterized.getParameterHop("dir");
		if(!(dir instanceof LiteralOp literal))
			return null;
		String value = literal.getStringValue();
		if(value == null)
			return null;
		if(value.equalsIgnoreCase("rows"))
			return true;
		if(value.equalsIgnoreCase("cols"))
			return false;
		return null;
	}

	private static boolean isBasicWDivMM(QuaternaryOp hop) {
		return hop.getBaseType() == 0;
	}

	private static boolean isLeftWDivMM(QuaternaryOp hop) {
		return hop.getBaseType() == 1 || hop.getBaseType() == 3;
	}

	private static boolean isRightWDivMM(QuaternaryOp hop) {
		return hop.getBaseType() == 2 || hop.getBaseType() == 4;
	}

	static boolean transformEncodePreservesPool(Hop hop, FType type) {
		if(type != FType.ROW && type != FType.FULL || !(hop instanceof DataOp data)
			|| data.getOp() != OpOpData.FUNCTIONOUTPUT || !hop.getDataType().isMatrix()
			|| hop.getInput().size() != 1 || !hop.getInput(0).getDataType().isFrame())
			return false;
		FunctionOp call = FederatedPlannerUtils.getMultiReturnFunctionOutputParent(hop);
		if(call == null || call.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| !"transformencode".equalsIgnoreCase(call.getFunctionName())
			|| call.getInput().size() != 2 || call.getOutputs().size() != 2
			|| call.getOutputs().get(0) != hop)
			return false;
		if(type == FType.FULL)
			return true;
		if(!(call.getInput(1) instanceof LiteralOp literal))
			return false;
		try {
			JSONObject spec = new JSONObject(literal.getStringValue());
			for(Object key : spec.keySet())
				if(!Set.of("ids", "recode", "dummycode", "cofeePublicRecodeMetadata").contains(key))
					return false;
			return spec.containsKey("recode") || spec.containsKey("dummycode");
		}
		catch(JSONException ex) {
			return false;
		}
	}

	private static void addDependency(ProofNode proof, CompiledHopKey dependency) {
		if(dependency != null && proof.dependencies.stream().noneMatch(existing -> existing == dependency))
			proof.dependencies.add(dependency);
	}

	private static final class ProofNode {
		private final List<CompiledHopKey> dependencies = new ArrayList<>();
		private boolean valid = true;
		private boolean directGround;
	}

	private NativePoolWitness nativeWitness(DurableAnchorKey anchor) {
		if(nativeWitnessByAnchor.containsKey(anchor))
			return nativeWitnessByAnchor.get(anchor);
		NativePoolWitness witness = NativePoolWitness.from(anchor, this::canonicalEndpoint);
		nativeWitnessByAnchor.put(anchor, witness);
		return witness;
	}

	private String canonicalEndpoint(String workerId) {
		if(canonicalEndpointByWorker.containsKey(workerId))
			return canonicalEndpointByWorker.get(workerId);
		String endpoint = FederationUtils.canonicalFederatedWorkerAddress(workerId);
		canonicalEndpointByWorker.put(workerId, endpoint);
		return endpoint;
	}

	private record AxisInterval(String endpoint, long begin, long end)
		implements Comparable<AxisInterval> {
		@Override public int compareTo(AxisInterval that) {
			int endpointOrder = endpoint.compareTo(that.endpoint);
			if(endpointOrder != 0)
				return endpointOrder;
			int beginOrder = Long.compare(begin, that.begin);
			return beginOrder != 0 ? beginOrder : Long.compare(end, that.end);
		}
	}

	private static final class NativePoolWitness {
		private final FType fType;
		private final List<String> endpoints;
		private final List<AxisInterval> partitionAxisIntervals;
		private final boolean exactPartitionRanges;
		private final int hashCode;
		private NativePoolWitness dynamicPartitionRangesWitness;

		private NativePoolWitness(FType fType, List<String> endpoints,
			List<AxisInterval> partitionAxisIntervals, boolean exactPartitionRanges) {
			Objects.requireNonNull(fType, "native witness FType");
			this.fType = fType;
			this.endpoints = List.copyOf(endpoints);
			this.partitionAxisIntervals = List.copyOf(partitionAxisIntervals);
			this.exactPartitionRanges = exactPartitionRanges;
			int hash = fType.hashCode();
			hash = 31 * hash + this.endpoints.hashCode();
			hash = 31 * hash + this.partitionAxisIntervals.hashCode();
			hashCode = 31 * hash + Boolean.hashCode(exactPartitionRanges);
		}

		@Override public int hashCode() { return hashCode; }

		@Override public boolean equals(Object other) {
			return this == other || other instanceof NativePoolWitness that
				&& exactPartitionRanges == that.exactPartitionRanges && fType == that.fType
				&& endpoints.equals(that.endpoints)
				&& partitionAxisIntervals.equals(that.partitionAxisIntervals);
		}

		private static NativePoolWitness from(DurableAnchorKey anchor,
			java.util.function.Function<String,String> canonicalEndpoint) {
			if(anchor == null || anchor.fType() == FType.PART || anchor.fType() == FType.OTHER
				|| anchor.partitions().isEmpty())
				return null;
			if(anchor.fType() == FType.FULL && anchor.partitions().size() != 1)
				return null;
			List<String> endpoints = new ArrayList<>();
			List<AxisInterval> intervals = new ArrayList<>();
			int axis = anchor.fType() == FType.ROW ? 0 : anchor.fType() == FType.COL ? 1 : -1;
			for(AnchorPartition partition : anchor.partitions()) {
				String endpoint = canonicalEndpoint.apply(partition.workerId());
				if(endpoint == null || endpoint.isBlank())
					return null;
				endpoints.add(endpoint);
				if(axis >= 0) {
					if(partition.begin().size() <= axis || partition.end().size() <= axis)
						return null;
					intervals.add(new AxisInterval(endpoint, partition.begin().get(axis), partition.end().get(axis)));
				}
			}
			Collections.sort(intervals);
			return new NativePoolWitness(anchor.fType(), endpoints.stream().distinct().sorted().toList(),
				intervals, true);
		}

		private NativePoolWitness withDynamicPartitionRanges() {
			if(!exactPartitionRanges)
				return this;
			if(fType != FType.ROW && fType != FType.COL && fType != FType.FULL)
				return this;
			if(dynamicPartitionRangesWitness == null)
				dynamicPartitionRangesWitness = new NativePoolWitness(fType, endpoints, partitionAxisIntervals, false);
			return dynamicPartitionRangesWitness;
		}

		private NativePoolWitness withExactPartitionRanges() {
			if(exactPartitionRanges)
				return this;
			return new NativePoolWitness(fType, endpoints, partitionAxisIntervals, true);
		}

		private boolean matches(NativePoolWitness candidate, boolean anchorLayoutExact) {
			if(candidate == null || candidate.fType != fType || !candidate.endpoints.equals(endpoints))
				return false;
			if(!exactPartitionRanges)
				return true;
			return anchorLayoutExact && candidate.partitionAxisIntervals.equals(partitionAxisIntervals);
		}

		private boolean singleEndpoint() {
			return endpoints.size() == 1;
		}

		private NativePoolWitness retyped(FType target) {
			if(target == null || target == FType.PART || target == FType.OTHER)
				return null;
			if(target == fType)
				return this;
			if(target == FType.BROADCAST || fType == FType.BROADCAST)
				return null;
			if((fType == FType.ROW && target == FType.COL)
				|| (fType == FType.COL && target == FType.ROW))
				return new NativePoolWitness(target, endpoints, partitionAxisIntervals, exactPartitionRanges);
			return null;
		}

		private NativePoolWitness retypedForResidency(FType target) {
			NativePoolWitness exact = retyped(target);
			if(exact != null)
				return exact;
			if(target == null || target == FType.PART || target == FType.OTHER
				|| target == FType.BROADCAST || fType == FType.BROADCAST || !singleEndpoint())
				return null;
			if(fType == FType.FULL && (target == FType.ROW || target == FType.COL))
				return new NativePoolWitness(target, endpoints, List.of(), false);
			if(target == FType.FULL && (fType == FType.ROW || fType == FType.COL))
				return new NativePoolWitness(FType.FULL, endpoints, List.of(), true);
			return null;
		}

		private DurableAnchorKey asAnchor(String placementId) {
			List<AnchorPartition> partitions = new ArrayList<>();
			if((fType == FType.ROW || fType == FType.COL) && !partitionAxisIntervals.isEmpty()) {
				for(AxisInterval interval : partitionAxisIntervals)
					partitions.add(fType == FType.ROW
						? new AnchorPartition(interval.endpoint(), List.of(interval.begin(), 0L),
							List.of(interval.end(), 1L))
						: new AnchorPartition(interval.endpoint(), List.of(0L, interval.begin()),
							List.of(1L, interval.end())));
			}
			else {
				for(String endpoint : endpoints)
					partitions.add(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(1L, 1L)));
			}
			return new DurableAnchorKey(placementId, fType, partitions);
		}

		private NativePoolWitness transposed() {
			return switch(fType) {
				case ROW -> retyped(FType.COL);
				case COL -> retyped(FType.ROW);
				case FULL, BROADCAST -> this;
				default -> null;
			};
		}
	}

	private static <K,V> Map<K,V> immutableIdentityMap(Map<K,V> source, String name) {
		Objects.requireNonNull(source, name);
		Map<K,V> copy = new IdentityHashMap<>();
		source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name + " key"),
			Objects.requireNonNull(value, name + " value")));
		return Collections.unmodifiableMap(copy);
	}

	private static <K,V> Map<K,List<V>> immutableIdentityLists(Map<K,List<V>> source, String name) {
		Objects.requireNonNull(source, name);
		Map<K,List<V>> copy = new IdentityHashMap<>();
		source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name + " key"),
			List.copyOf(Objects.requireNonNull(value, name + " value"))));
		return Collections.unmodifiableMap(copy);
	}
}
