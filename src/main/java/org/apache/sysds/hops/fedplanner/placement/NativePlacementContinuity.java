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

import java.util.AbstractList;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Conditional physical compatibility for native FED/FOUT candidates in validated
 * nonrecursive programs. Final entry/input relations retain program dependencies;
 * this helper does not independently check reachability from program sources.
 */
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
	private final CandidateFactsSnapshot candidateFactsSnapshot;
	private final Map<CompiledHopKey,List<CandidateRuleFact>> candidateFactsByKey;
	private final Map<CompiledHopKey,NativeRelationInventory> nativeRelationOwners = new IdentityHashMap<>();
	private Map<CompiledHopKey,List<CompiledHopKey>> boundCandidateReadersBySource;
	private long boundSourceProjectionScans;
	private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> edgesByConsumer;
	private final Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions;
	private final LazyOccurrenceComponents occurrenceComponents;
	private final Set<CompiledHopKey> incompleteSources;
	private final Map<CompiledHopKey,Privacy> privacyByKey;
	private final Set<ValueVersionKey> broadcastCapableValueVersions;
	private final Map<CandidateRealizationSupportClause,List<CandidateRealizationReference>>
		requiredInputSupportByClause = new IdentityHashMap<>();
	private final Map<CandidateRuleFact,Set<CandidateRealizationSupportClause>>
		ownedCandidateClausesByFact = new IdentityHashMap<>();
	private final Map<DurableAnchorKey,NativePoolWitness> nativeWitnessByAnchor = new IdentityHashMap<>();
	private final Map<String,String> canonicalEndpointByWorker = new java.util.HashMap<>();
	private final Map<CandidateRealizationReference,Integer> candidateHandleByReference =
		new IdentityHashMap<>();
	private final Map<CandidateRealizationReference,Integer> candidateHandleByStructure =
		new java.util.HashMap<>();
	// Opaque current-revision stamps for owners named by active CFG replay receipts.
	// Receipts live in the per-reader replay memo; this resolver retains no query list.
	private final Map<CompiledHopKey,Object> replayOwnerRevisionTokens = new IdentityHashMap<>();
	private long replayReceiptHits;
	private long replayReceiptMisses;
	private long replayReceiptTokensCarried;
	// Analysis-arena handles are positive. Overflow-local handles use a disjoint
	// negative namespace so a bounded arena can never alias two references.
	private int nextCandidateHandle = -1;
	private final Map<CandidateTopologyKey,CandidateTopology> candidateTopologies =
		new java.util.LinkedHashMap<>(16, 0.75f, true);
	// Exact resident keys mirror candidateTopologies. Owner identity prevents an
	// equal foreign occurrence from borrowing authority; witness equality preserves
	// the existing topology-key relation without allocating a probe on every hit.
	private final Map<CompiledHopKey,Map<NativePoolWitness,CandidateTopologyKey>>
		candidateTopologyKeysByOwner = new IdentityHashMap<>();
	private final int topologyMaxEntries;
	private final long topologyMaxRows;
	private long topologyRetainedRows;
	private long topologyRetainedOwnerReads;
	// Query-local fixed-boundary overlays are immutable for one candidate revision.
	// Keep the overlaid list, rather than only its shared topology, so its lazy DFS
	// schedule is also reused. Exact positive analysis handles and owner identity are
	// the authority boundary; this memo is deliberately never migrated to a revision.
	private final Map<FixedBoundaryOverlayKey,DefaultAlternativeList> fixedBoundaryOverlays =
		new java.util.LinkedHashMap<>(16, 0.75f, true);
	private long fixedBoundaryOverlayRetainedRows;
	private long fixedBoundaryOverlayBuilds;
	private long fixedBoundaryOverlayHits;
	private long fixedBoundaryOverlayRowsVisited;
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
	// Exact immutable fact projections are revision-chain state. Identity keys prevent
	// an equal reconstruction from inheriting authority, and every revision retains
	// only facts in its own current inventory.
	private final Map<CandidateRuleFact,FactProjectionMemo> continuityProjectionMemo =
		new IdentityHashMap<>();
	private final Map<CompiledHopKey,Map<List<CandidateInputState>,FactProjectionMemo>>
		continuityProjectionMemoByRule = new IdentityHashMap<>();
	// Dependency skeletons are independent of revision-local candidate handles.
	// Each revision re-keys successful templates to its current facts and clauses;
	// structural revisions deliberately start with empty maps.
	private final Map<CandidateRuleFact,SkeletonFactMemo> dependencySkeletonMemo =
		new IdentityHashMap<>();
	private final Map<CompiledHopKey,Map<List<CandidateInputState>,SkeletonFactMemo>>
		dependencySkeletonMemoByRule = new IdentityHashMap<>();
	private long dependencySkeletonBuilds;
	private long dependencySkeletonReuses;
	private long dependencySkeletonTemplatesCarried;
	private long dependencySkeletonOwnerFactScans;
	private long supportMemoRetainedTemplates;
	private long supportMemoRetainedEstimatedBytes;
	private long acyclicRootRetainedUnits;
	private long acyclicRootRetainedEstimatedBytes;
	// Acyclic child components retain one immutable exact relation DAG while their
	// supported boundary rows provide the fast path. The complete footprint guards
	// the query-local root pin and revision invalidation.
	private final Map<CandidateProofState,AcyclicComponentSummary> acyclicComponentMemo;
	private final int acyclicComponentMaxEntries;
	private final long acyclicComponentMaxStates;
	private final long acyclicComponentMaxAlternatives;
	private long acyclicComponentRetainedStates;
	private long acyclicComponentRetainedAlternatives;
	private RevisionComparisonSnapshot revisionComparison = RevisionComparisonSnapshot.EMPTY;
	// Only local immutable rows are shared. A query's representative anchor still
	// depends on its historical BFS/sweep order, so cached results are not graph leaves.
	// Reference equality is structural, while candidate fact lookup owns exact Hop
	// identities. Do not let an equal foreign owner poison or borrow a decoded row.
	private final Map<CompiledHopKey,Map<CandidateRealizationReference,FixedPoolNode>> fixedPoolNodes =
		new IdentityHashMap<>();
	private final FixedPoolWork fixedPoolWork;
	// Snapshot-local guard for structurally keyed pool results versus exact owner reads.
	private Map<CompiledHopKey,CompiledHopKey> metadataOwnerIdentities;
	private Set<CompiledHopKey> ambiguousMetadataOwners;


	record FixedPoolWorkSnapshot(long decodedRows, long activations, long groundingEdgeVisits,
		long geometryVisits, long inexactEdgeVisits) { }

	private static final class FixedPoolWork {
		private long decodedRows;
		private long activations;
		private long groundingEdgeVisits;
		private long geometryVisits;
		private long inexactEdgeVisits;
		private FixedPoolWorkSnapshot snapshot() {
			return new FixedPoolWorkSnapshot(decodedRows, activations, groundingEdgeVisits,
				geometryVisits, inexactEdgeVisits);
		}
	}

	/** Diagnostic-only work counts; no collector is created on the default/unmeasured path. */
	FixedPoolWorkSnapshot fixedPoolWorkSnapshot() {
		return fixedPoolWork == null ? new FixedPoolWorkSnapshot(0, 0, 0, 0, 0) : fixedPoolWork.snapshot();
	}
	private final Map<FixedValueMapReferenceKey,FixedValueMapResolution> fixedValueMapResolutions =
		new java.util.LinkedHashMap<>(16, 0.75f, true);
	private final int fixedValueMapMaxEntries;
	private final long fixedValueMapMaxOwnerReads;
	private long fixedValueMapRetainedOwnerReads;

	record FixedValueMapPool(DurableAnchorKey pool, boolean exactLayout,
		boolean exactPhysicalLayout) { }
	record FixedValueMapResolution(FixedValueMapPool pool, Set<CompiledHopKey> ownerReads) {
		FixedValueMapResolution {
			if(!(ownerReads instanceof ImmutableIdentityOwnerReads))
				ownerReads = new ImmutableIdentityOwnerReads(ownerReads);
		}
	}
	private static final class ImmutableIdentityOwnerReads
		extends java.util.AbstractSet<CompiledHopKey> {
		private final Set<CompiledHopKey> delegate;
		private ImmutableIdentityOwnerReads(java.util.Collection<CompiledHopKey> owners) {
			Set<CompiledHopKey> identity = Collections.newSetFromMap(new IdentityHashMap<>());
			identity.addAll(owners);
			delegate = Collections.unmodifiableSet(identity);
		}
		@Override public java.util.Iterator<CompiledHopKey> iterator() { return delegate.iterator(); }
		@Override public int size() { return delegate.size(); }
		@Override public boolean contains(Object owner) { return delegate.contains(owner); }
	}
	private static final class FixedValueMapReferenceKey {
		private final CompiledHopKey owner;
		private final CandidateRealizationReference reference;
		private final int hashCode;
		private FixedValueMapReferenceKey(CandidateRealizationReference reference) {
			owner = reference.rule().parentOccurrence();
			this.reference = reference;
			hashCode = 31 * System.identityHashCode(owner) + reference.hashCode();
		}
		@Override public int hashCode() { return hashCode; }
		@Override public boolean equals(Object other) {
			return this == other || other instanceof FixedValueMapReferenceKey that
				&& owner == that.owner && reference.equals(that.reference);
		}
	}
	private record FixedPoolClause(FixedValueMapPool leaf,
		List<CandidateRealizationReference> sources) { }
	private record FixedPoolNode(List<FixedPoolClause> clauses) { }
	private record FixedPoolUse(int owner, int clause, int position) { }

	private static final class FixedPoolGrounding {
		private final FixedValueMapPool[] clauses;
		private final int[] firstSources;
		private final List<FixedPoolUse> consumers = new ArrayList<>();
		private int missingClauses;
		private FixedValueMapPool endpoints;
		private FixedValueMapPool selected;

		private FixedPoolGrounding(int clauseCount) {
			clauses = new FixedValueMapPool[clauseCount];
			firstSources = new int[clauseCount];
			java.util.Arrays.fill(firstSources, Integer.MAX_VALUE);
			missingClauses = clauseCount;
		}

		private boolean accept(int clause, int position, FixedValueMapPool pool) {
			if(endpoints != null && !sameFixedEndpoints(endpoints, pool))
				return false;
			endpoints = pool;
			if(clauses[clause] == null)
				missingClauses--;
			if(position < firstSources[clause]) {
				clauses[clause] = pool;
				firstSources[clause] = position;
			}
			return true;
		}
	}

	record RevisionComparisonSnapshot(long hintedOwnersBypassed, long ownersCompared,
		long continuityProjectionsCompared) {
		private static final RevisionComparisonSnapshot EMPTY =
			new RevisionComparisonSnapshot(0, 0, 0);
	}

	private static final class RevisionComparisonWork {
		private final Map<CompiledHopKey,DirectContinuityComparison> direct = new IdentityHashMap<>();
		private long hintedOwnersBypassed;
		private long ownersCompared;
		private long continuityProjectionsCompared;
		private RevisionComparisonSnapshot snapshot() {
			return new RevisionComparisonSnapshot(hintedOwnersBypassed, ownersCompared,
				continuityProjectionsCompared);
		}
	}
	private record DirectContinuityComparison(boolean unchanged, boolean projectionNeeded) { }

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
		this(structuralContext, CandidateFactsSnapshot.index(candidateFacts), metrics,
			memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
	}

	private NativePlacementContinuity(StructuralContext structuralContext,
		CandidateFactsSnapshot candidateFactsSnapshot, SearchSpaceMetrics metrics,
		int memoMaxEntries, long memoMaxProofs, long memoMaxEstimatedBytes) {
		this.structuralContext = Objects.requireNonNull(structuralContext, "structuralContext");
		nodesByKey = structuralContext.nodesByKey;
		originsByKey = structuralContext.originsByKey;
		privacyByKey = structuralContext.privacyByKey;
		broadcastCapableValueVersions = structuralContext.broadcastCapableValueVersions;
		reachingDefinitions = structuralContext.reachingDefinitions;
		incompleteSources = structuralContext.incompleteSources;
		edgesByConsumer = structuralContext.edgesByConsumer;
		occurrenceComponents = structuralContext.occurrenceComponents;
		this.metrics = metrics;
		fixedPoolWork = metrics == null ? null : new FixedPoolWork();
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
		fixedValueMapMaxEntries = Math.max(0,
			Integer.getInteger("sysds.fedplanner.fixedValueMap.maxEntries", 4096));
		fixedValueMapMaxOwnerReads = Math.max(0,
			Long.getLong("sysds.fedplanner.fixedValueMap.maxOwnerReads", 131072L));
		completedProofMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		completedSupportMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		acyclicRootSupportMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		acyclicComponentMemo = new java.util.LinkedHashMap<>(16, 0.75f, true);
		this.candidateFactsSnapshot = Objects.requireNonNull(candidateFactsSnapshot,
			"candidateFactsSnapshot");
		candidateFactsByKey = candidateFactsSnapshot.factsByKey;
	}

	/** Immutable identity-indexed candidate authority shared by cold query states. */
	private static final class CandidateFactsSnapshot {
		private final Map<CompiledHopKey,List<CandidateRuleFact>> factsByKey;
		private final int factCount;

		private CandidateFactsSnapshot(Map<CompiledHopKey,List<CandidateRuleFact>> factsByKey) {
			this.factsByKey = Collections.unmodifiableMap(factsByKey);
			factCount = factsByKey.values().stream().mapToInt(List::size).sum();
		}

		private static CandidateFactsSnapshot index(List<CandidateRuleFact> candidateFacts) {
			Map<CompiledHopKey,List<CandidateRuleFact>> indexed = new IdentityHashMap<>();
			for(CandidateRuleFact fact : List.copyOf(
				Objects.requireNonNull(candidateFacts, "candidateFacts"))) {
				CompiledHopKey owner = Objects.requireNonNull(fact.key().parentOccurrence(),
					"candidateFacts owner");
				indexed.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(fact);
			}
			indexed.replaceAll((ignored, facts) -> List.copyOf(facts));
			return new CandidateFactsSnapshot(indexed);
		}

		private CandidateFactsSnapshot withOwner(CompiledHopKey owner,
			List<CandidateRuleFact> replacementFacts) {
			Map<CompiledHopKey,List<CandidateRuleFact>> revised = new IdentityHashMap<>(factsByKey);
			if(replacementFacts.isEmpty())
				revised.remove(owner);
			else
				revised.put(owner, replacementFacts);
			return new CandidateFactsSnapshot(revised);
		}

		private boolean hasSameFactObjects(List<CandidateRuleFact> candidateFacts) {
			List<CandidateRuleFact> facts = List.copyOf(
				Objects.requireNonNull(candidateFacts, "candidateFacts"));
			if(facts.size() != factCount)
				return false;
			Map<CompiledHopKey,Integer> nextByOwner = new IdentityHashMap<>();
			for(CandidateRuleFact fact : facts) {
				CompiledHopKey owner = Objects.requireNonNull(fact.key().parentOccurrence(),
					"candidateFacts owner");
				List<CandidateRuleFact> expected = factsByKey.get(owner);
				if(expected == null)
					return false;
				int index = nextByOwner.getOrDefault(owner, 0);
				if(index >= expected.size() || expected.get(index) != fact)
					return false;
				nextByOwner.put(owner, index + 1);
			}
			return true;
		}
	}

	/** Exact fact-object authority, preserving order within each candidate owner. */
	boolean hasSameCandidateFactObjects(List<CandidateRuleFact> candidateFacts) {
		return candidateFactsSnapshot.hasSameFactObjects(candidateFacts);
	}

	/** Returns an exact resolver with no query-local history. */
	NativePlacementContinuity freshQueryState() {
		return new NativePlacementContinuity(structuralContext, candidateFactsSnapshot, metrics,
			memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
	}

	NativePlacementContinuity nextOwnerRevision(CompiledHopKey expectedOwner,
		List<CandidateRuleFact> replacementFacts) {
		Objects.requireNonNull(expectedOwner, "expectedOwner");
		List<CandidateRuleFact> replacement = List.copyOf(replacementFacts);
		for(CandidateRuleFact fact : replacement)
			if(fact.key().parentOccurrence() != expectedOwner)
				throw new IllegalArgumentException("Candidate owner delta contains a foreign fact");
		if(!nodesByKey.containsKey(expectedOwner))
			throw new IllegalArgumentException("Candidate owner delta has a foreign owner identity");
		CandidateFactsSnapshot revised = candidateFactsSnapshot.withOwner(expectedOwner, replacement);
		NativePlacementContinuity next = new NativePlacementContinuity(structuralContext, revised, metrics,
			memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
		return next;
	}

	NativePlacementContinuity nextNodeAuthorityRevision(Node replacementNode,
		List<CandidateRuleFact> replacementFacts) {
		Objects.requireNonNull(replacementNode, "replacementNode");
		CompiledHopKey owner = replacementNode.key();
		Node previous = nodesByKey.get(owner);
		if(previous == null || previous.key() != owner || previous.kind() != replacementNode.kind()
			|| !previous.valueVersion().equals(replacementNode.valueVersion()))
			throw new IllegalArgumentException("Node-authority delta changed key, kind, or value version");
		List<CandidateRuleFact> replacement = List.copyOf(replacementFacts);
		for(CandidateRuleFact fact : replacement)
			if(fact.key().parentOccurrence() != owner)
				throw new IllegalArgumentException("Node-authority delta contains a foreign fact");
		CandidateFactsSnapshot revisedFacts = candidateFactsSnapshot.withOwner(owner, replacement);
		NativePlacementContinuity next = new NativePlacementContinuity(
			structuralContext.withNodeAuthority(replacementNode), revisedFacts, metrics,
			memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
		return next;
	}

	/**
	 * Starts a fact revision. The nodes, compiled edges, reaching definitions,
	 * privacy and Hops remain unchanged. Only the candidate facts may change; explanatory
	 * proof dependencies do not change this private execution relation.
	 */
	NativePlacementContinuity nextRevision(List<CandidateRuleFact> candidateFacts) {
		return nextRevisionInternal(candidateFacts, null);
	}

	/** Rebuilds all structural authority while retaining only an exactly unchanged SCC index. */
	NativePlacementContinuity structuralRevision(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey) {
		StructuralContext nextContext = new StructuralContext(nodesByKey, originsByKey, compiledEdges,
			reachingDefinitions, incompleteSources, privacyByKey, structuralContext);
		return new NativePlacementContinuity(nextContext, candidateFacts, metrics,
			memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
	}

	boolean sharesOccurrenceComponentsWith(NativePlacementContinuity that) {
		return that != null && occurrenceComponents == that.occurrenceComponents;
	}

	/**
	 * Starts a revision using the complete exact before/after canonical-owner delta.
	 * This bounded hint is valid only while the structural context and every non-candidate input
	 * remain the same. Owners in the set are still compared by their complete continuity projection;
	 * only owners proven absent from the set bypass that deep comparison. The caller must supply every
	 * changed owner; validating completeness would require the deep comparison this path avoids.
	 */
	NativePlacementContinuity nextRevisionWithCompleteCandidateDelta(List<CandidateRuleFact> candidateFacts,
		Set<CompiledHopKey> completeChangedOccurrences) {
		Objects.requireNonNull(completeChangedOccurrences, "completeChangedOccurrences");
		return nextRevisionInternal(candidateFacts, completeChangedOccurrences);
	}

	private NativePlacementContinuity nextRevisionInternal(List<CandidateRuleFact> candidateFacts,
		Set<CompiledHopKey> completeChangedOccurrences) {
		boolean exactFacts = (completeChangedOccurrences == null || completeChangedOccurrences.isEmpty())
			&& candidateFactsSnapshot.hasSameFactObjects(candidateFacts);
		NativePlacementContinuity next = exactFacts
			? new NativePlacementContinuity(structuralContext, candidateFactsSnapshot, metrics,
				memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes)
			: new NativePlacementContinuity(structuralContext, candidateFacts, metrics,
				memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
		return reuseRevisionCaches(next, exactFacts ? Set.of() : completeChangedOccurrences);
	}

	private NativePlacementContinuity reuseRevisionCaches(NativePlacementContinuity next,
		Set<CompiledHopKey> completeChangedOccurrences) {
		copyCurrentProjectionMemo(next);
		copyCurrentDependencySkeletonMemo(next);
		final Set<CompiledHopKey> changedOccurrences;
		if(completeChangedOccurrences == null)
			changedOccurrences = null;
		else if(completeChangedOccurrences.isEmpty())
			changedOccurrences = Set.of();
		else {
			Set<CompiledHopKey> knownOwners = Collections.newSetFromMap(new IdentityHashMap<>());
			knownOwners.addAll(candidateFactsByKey.keySet());
			knownOwners.addAll(next.candidateFactsByKey.keySet());
			Set<CompiledHopKey> identitySnapshot = Collections.newSetFromMap(new IdentityHashMap<>());
			for(CompiledHopKey occurrence : completeChangedOccurrences) {
				if(occurrence == null || !knownOwners.contains(occurrence))
					throw new IllegalArgumentException(
						"Candidate delta contains a null or foreign owner identity");
				identitySnapshot.add(occurrence);
			}
			changedOccurrences = identitySnapshot;
		}
		// Reuse is governed by the complete private execution projection, so
		// proof-history-only changes do not force recomputation while any changed
		// execution row still invalidates its cached supports.
		Map<CompiledHopKey,Boolean> unchangedRows = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> unchangedGeneratedRoots = new IdentityHashMap<>();
		RevisionComparisonWork comparisonWork = new RevisionComparisonWork();
		boolean sharedStructuralAuthority = structuralContext == next.structuralContext;
		boolean hasReusableEntries = !candidateTopologies.isEmpty() || !completedSupportMemo.isEmpty()
			|| !acyclicRootSupportMemo.isEmpty() || !completedProofMemo.isEmpty()
			|| !acyclicComponentMemo.isEmpty() || !replayOwnerRevisionTokens.isEmpty();
		BoundOwnerRevisionImpact boundOwnerImpact = hasReusableEntries
			? boundOwnerRevisionImpact(next, changedOccurrences, comparisonWork) : BoundOwnerRevisionImpact.EMPTY;
		long reused = 0;
		for(var entry : replayOwnerRevisionTokens.entrySet()) {
			CompiledHopKey occurrence = entry.getKey();
			if(unchangedRows.computeIfAbsent(occurrence, key -> unchangedContinuityFacts(
				next, key, changedOccurrences, comparisonWork, boundOwnerImpact))) {
				next.replayOwnerRevisionTokens.put(occurrence, entry.getValue());
				next.replayReceiptTokensCarried++;
			}
		}
		for(var entry : candidateTopologies.entrySet()) {
			// Partial coverage is rebuilt in the destination snapshot. Completed
			// supports still migrate under their full occurrence/metadata receipts.
			if(entry.getValue().ordinaryOnly)
				continue;
			CompiledHopKey occurrence = entry.getKey().occurrence;
			if(!unchangedRows.computeIfAbsent(occurrence, key -> unchangedContinuityFacts(
				next, key, changedOccurrences, comparisonWork, boundOwnerImpact)))
				continue;
			CandidateTopology topology = entry.getValue();
			if(topology.metadataOwnerReads.stream().anyMatch(owner ->
				!unchangedRows.computeIfAbsent(owner, key -> unchangedContinuityFacts(
					next, key, changedOccurrences, comparisonWork, boundOwnerImpact))))
				continue;
			// The complete execution/metadata checks above authorize the old references.
			// A fresh fact snapshot alone does not require rebuilding immutable rows:
			// only their destination-local handle indexes can still have changed.
			boolean shareRows = sharedStructuralAuthority && topology.hasStableStructuralHandles(next);
			CandidateTopology migrated = shareRows ? topology : next.reindexTopology(topology);
			if(next.cacheTopology(entry.getKey(), migrated)) {
				reused++;
				if(metrics != null)
					metrics.recordDirectWork(shareRows
						? SearchSpaceMetrics.DirectWork.TOPOLOGY_REVISION_SHARED_ROWS
						: SearchSpaceMetrics.DirectWork.TOPOLOGY_REVISION_REINDEXED_ROWS,
						migrated.rows.size());
			}
		}
		long supportReused = 0;
		for(var entry : completedSupportMemo.entrySet()) {
			SupportMemoEntry support = entry.getValue();
			CompiledHopKey root = support.root.rule().parentOccurrence();
			// A generated root reads primitive row/emission authority, not its own
			// published support history. The exact query traversal records whether a
			// descendant nevertheless read that history. Keep this weaker comparison
			// out of unchangedRows: ordinary queries and public dynamic-layout results
			// still need full facts.
			boolean generatedHistoryIndependentRoot = entry.getKey().generated
				&& nodesByKey.containsKey(root) && support.rootIndependent;
			boolean unchanged = support.occurrences.stream().noneMatch(occurrence ->
				!(generatedHistoryIndependentRoot && occurrence == root
					? unchangedGeneratedRoots.computeIfAbsent(root, key -> unchangedGeneratedRootFacts(
						next, key, changedOccurrences, comparisonWork))
						: unchangedRows.computeIfAbsent(occurrence, key -> unchangedContinuityFacts(
							next, key, changedOccurrences, comparisonWork, boundOwnerImpact))));
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
				!unchangedRows.computeIfAbsent(occurrence, key -> unchangedContinuityFacts(
					next, key, changedOccurrences, comparisonWork, boundOwnerImpact)));
			if(unchanged)
				next.cacheAcyclicRootSupport(entry.getKey(), support);
		}
		for(var entry : completedProofMemo.entrySet()) {
			MemoEntry proof = entry.getValue();
			boolean unchanged = proof.occurrences().stream().noneMatch(occurrence ->
				!unchangedRows.computeIfAbsent(occurrence, key -> unchangedContinuityFacts(
					next, key, changedOccurrences, comparisonWork, boundOwnerImpact)));
			if(unchanged)
				next.cacheCompletedProofs(entry.getKey(), proof);
		}
		for(var entry : acyclicComponentMemo.entrySet()) {
			AcyclicComponentSummary summary = entry.getValue();
			boolean unchanged = summary.occurrences.stream().noneMatch(occurrence ->
				!unchangedRows.computeIfAbsent(occurrence, key -> unchangedContinuityFacts(
					next, key, changedOccurrences, comparisonWork, boundOwnerImpact)));
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
		next.revisionComparison = comparisonWork.snapshot();
		return next;
	}

	private void copyCurrentProjectionMemo(NativePlacementContinuity next) {
		if(continuityProjectionMemo.isEmpty())
			return;
		// Traverse the current inventory once. Identity lookup retains only the exact
		// surviving facts without rescanning each owner's candidate list for every memo.
		for(var entry : next.candidateFactsByKey.entrySet())
			for(CandidateRuleFact fact : entry.getValue()) {
				FactProjectionMemo projection = continuityProjectionMemo.get(fact);
				if(projection != null && fact.key().parentOccurrence() == entry.getKey())
					next.rememberFactProjectionMemo(fact, projection);
			}
	}

	private void copyCurrentDependencySkeletonMemo(NativePlacementContinuity next) {
		if(dependencySkeletonMemo.isEmpty())
			return;
		for(var entry : next.candidateFactsByKey.entrySet())
			for(CandidateRuleFact fact : entry.getValue()) {
				SkeletonFactMemo exact = dependencySkeletonMemo.get(fact);
				if(exact != null) {
					exact.shared = true;
					next.rememberDependencySkeletonMemo(fact, exact);
					next.dependencySkeletonTemplatesCarried += exact.templateCount;
					Set<CandidateRealizationSupportClause> owned = ownedCandidateClausesByFact.get(fact);
					if(owned != null)
						next.ownedCandidateClausesByFact.put(fact, owned);
					continue;
				}
				SkeletonFactMemo donor = donorDependencySkeletonMemo(fact);
				if(donor == null)
					continue;
				SkeletonFactMemo current = new SkeletonFactMemo();
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						for(CandidateRealizationSupportClause clause : skeletonTransferClauses(
							realization.supportClauses(), donor)) {
							SkeletonClauseMemo inherited = donor.donor(clause);
							if(inherited == null || !sameSkeletonClauseAuthority(inherited.clause, clause))
								continue;
							SkeletonClauseMemo copied = new SkeletonClauseMemo(
								current.ownerToken, clause,
								inherited.clause == clause ? inherited.support : null,
								inherited.clause == clause ? inherited.pinProjection : null,
								new HashMap<>(inherited.templates));
							current.remember(clause, copied);
						}
				if(!current.clauses.isEmpty()) {
					next.rememberDependencySkeletonMemo(fact, current);
					next.dependencySkeletonTemplatesCarried += current.templateCount;
				}
			}
	}

	private static List<CandidateRealizationSupportClause> skeletonTransferClauses(
		List<CandidateRealizationSupportClause> clauses, SkeletonFactMemo donor) {
		if(!(clauses instanceof NativeContinuitySupportClauses relation)
			|| relation.size() <= donor.clauses.size())
			return clauses;
		// Probe only cached donor members when the current native family is larger.
		// Canonical ordinals preserve the old transfer order; get() below restores
		// current first-donor authority before the ordinary donor/identity checks.
		Set<Integer> ordinals = new java.util.TreeSet<>();
		for(CandidateRealizationSupportClause clause : donor.clauses.keySet()) {
			int ordinal = relation.ordinalOfExactAuthorityClause(clause);
			if(ordinal >= 0)
				ordinals.add(ordinal);
		}
		return ordinals.stream().map(relation::get).toList();
	}

	RevisionComparisonSnapshot revisionComparisonSnapshot() {
		return revisionComparison;
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

	private boolean unchangedContinuityFacts(NativePlacementContinuity next, CompiledHopKey occurrence,
		Set<CompiledHopKey> completeChangedOccurrences, RevisionComparisonWork comparisonWork,
		BoundOwnerRevisionImpact boundOwnerImpact) {
		if(!unchangedDirectContinuityFacts(next, occurrence,
			completeChangedOccurrences, comparisonWork))
			return false;
		Set<CompiledHopKey> metadataOwners = Collections.newSetFromMap(new IdentityHashMap<>());
		collectDerivedFoutOwnerReads(candidateFactsByKey.getOrDefault(occurrence, List.of()), metadataOwners);
		collectDerivedFoutOwnerReads(next.candidateFactsByKey.getOrDefault(occurrence, List.of()), metadataOwners);
		for(CompiledHopKey owner : metadataOwners)
			if(owner != occurrence && !unchangedDirectContinuityFacts(next, owner,
				completeChangedOccurrences, comparisonWork))
				return false;
		return !boundOwnerImpact.affects(occurrence);
	}

	private BoundOwnerRevisionImpact boundOwnerRevisionImpact(NativePlacementContinuity next,
		Set<CompiledHopKey> completeChangedOccurrences, RevisionComparisonWork comparisonWork) {
		Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
		if(completeChangedOccurrences != null)
			owners.addAll(completeChangedOccurrences);
		else {
			owners.addAll(candidateFactsByKey.keySet());
			owners.addAll(next.candidateFactsByKey.keySet());
		}
		Set<CompiledHopKey> changedOwners = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CompiledHopKey owner : owners)
			if(!directContinuityComparison(next, owner, comparisonWork).unchanged())
				changedOwners.add(owner);
		if(changedOwners.isEmpty())
			return BoundOwnerRevisionImpact.EMPTY;
		return new BoundOwnerRevisionImpact(
			reverseReachableReaders(changedOwners, boundCandidateReadersBySource()),
			reverseReachableReaders(changedOwners, next.boundCandidateReadersBySource(this)));
	}

	private Map<CompiledHopKey,List<CompiledHopKey>> boundCandidateReadersBySource() {
		return boundCandidateReadersBySource(null);
	}

	private Map<CompiledHopKey,List<CompiledHopKey>> boundCandidateReadersBySource(
		NativePlacementContinuity donor) {
		if(boundCandidateReadersBySource == null)
			boundCandidateReadersBySource = indexBoundCandidateReadersOwned(candidateFactsByKey, donor);
		return boundCandidateReadersBySource;
	}

	long boundSourceProjectionScans() {
		return boundSourceProjectionScans;
	}

	private Map<CompiledHopKey,List<CompiledHopKey>> indexBoundCandidateReadersOwned(
		Map<CompiledHopKey,List<CandidateRuleFact>> factsByOwner, NativePlacementContinuity donor) {
		Map<CompiledHopKey,List<CompiledHopKey>> readersBySource = new IdentityHashMap<>();
		Map<CompiledHopKey,Set<CompiledHopKey>> seenReadersBySource = new IdentityHashMap<>();
		for(var entry : factsByOwner.entrySet()) {
			CompiledHopKey owner = entry.getKey();
			for(CandidateRuleFact fact : entry.getValue())
				for(CompiledHopKey source : boundCandidateSources(fact, donor)) {
					Set<CompiledHopKey> seen = seenReadersBySource.computeIfAbsent(source,
						ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
					if(seen.add(owner))
						readersBySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(owner);
				}
		}
		Map<CompiledHopKey,List<CompiledHopKey>> immutable = new IdentityHashMap<>();
		readersBySource.forEach((source, readers) -> immutable.put(source, List.copyOf(readers)));
		return Collections.unmodifiableMap(immutable);
	}

	/**
	 * Complete the physical proof receipt with VALUE_MAP and derived-FOUT metadata reads. This is
	 * deliberately a conservative owner closure, not a new proof or a candidate
	 * filter. Reusing the immutable per-fact projection covers positive, negative
	 * and cached fixed-pool queries alike, including references not yet available.
	 * The supplied set is binder-owned and identity-based; no closure is retained.
	 */
	boolean expandValueMapMetadataDependencies(Set<CompiledHopKey> owners) {
		if(metadataOwnerIdentities == null) {
			metadataOwnerIdentities = new HashMap<>();
			ambiguousMetadataOwners = new HashSet<>();
			for(CompiledHopKey owner : nodesByKey.keySet())
				registerMetadataOwner(owner);
			for(CompiledHopKey owner : candidateFactsByKey.keySet())
				registerMetadataOwner(owner);
		}
		boolean complete = true;
		java.util.ArrayDeque<CompiledHopKey> pending = new java.util.ArrayDeque<>(owners);
		while(!pending.isEmpty()) {
			CompiledHopKey owner = pending.removeFirst();
			CompiledHopKey registered = metadataOwnerIdentities.get(owner);
			// Pool/reference caches use structural equality. Never certify a receipt
			// that could borrow a distinct owner's cached positive or negative result.
			if(ambiguousMetadataOwners.contains(owner) || registered != null && registered != owner) {
				complete = false;
				if(metrics != null)
					metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.METADATA_FOOTPRINT_IDENTITY_REJECTS);
			}
			for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(owner, List.of())) {
				if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
					for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
						if(emission.derivedFoutAction() != null) {
							// Topology reads this node and its owned native-pool certificate,
							// even on failed authority checks. The certificate accessor is
							// field-only; no upstream binding is implicitly authorized here.
							CompiledHopKey anchorOwner = emission.derivedFoutAction().durableAnchorOwner();
							if(owners.add(anchorOwner))
								pending.addLast(anchorOwner);
							if(metrics != null)
								metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.METADATA_FOOTPRINT_DERIVED_EDGES);
						}
				for(CompiledHopKey source : boundCandidateSources(fact, null))
					if(owners.add(source))
						pending.addLast(source);
			}
		}
		return complete;
	}

	private void registerMetadataOwner(CompiledHopKey owner) {
		CompiledHopKey previous = metadataOwnerIdentities.putIfAbsent(owner, owner);
		if(previous != null && previous != owner)
			ambiguousMetadataOwners.add(owner);
	}

	private List<CompiledHopKey> boundCandidateSources(CandidateRuleFact fact,
		NativePlacementContinuity donor) {
		FactProjectionMemo memo = continuityProjectionMemo.get(fact);
		List<CompiledHopKey> sources = memo == null ? null : memo.boundCandidateSources;
		if(sources == null && donor != null) {
			FactProjectionMemo inherited = donor.continuityProjectionMemo.get(fact);
			sources = inherited == null ? null : inherited.boundCandidateSources;
			if(sources != null) {
				if(memo == null) {
					memo = new FactProjectionMemo();
					continuityProjectionMemo.put(fact, memo);
				}
				memo.boundCandidateSources = sources;
			}
		}
		if(sources == null) {
			sources = indexBoundCandidateSources(fact);
			boundSourceProjectionScans++;
			if(memo == null) {
				memo = new FactProjectionMemo();
				continuityProjectionMemo.put(fact, memo);
			}
			memo.boundCandidateSources = sources;
		}
		return sources;
	}

	private static List<CompiledHopKey> indexBoundCandidateSources(CandidateRuleFact fact) {
		List<CompiledHopKey> sources = new ArrayList<>();
		Set<CompiledHopKey> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
			for(CandidateEmissionRealization realization : emission.realizations()) {
				if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
					continue;
				for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
					if(realization.nativeWorkerPoolResidencyForOwnedClause(clause) != null)
						continue;
					for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
						if(binding.kind() == CandidateInputBindingKind.RELOCATION)
							continue;
						CompiledHopKey source = binding.source().rule().parentOccurrence();
						if(seen.add(source))
							sources.add(source);
					}
				}
			}
		return List.copyOf(sources);
	}

	private static Map<CompiledHopKey,List<CompiledHopKey>> indexBoundCandidateReaders(
		Map<CompiledHopKey,List<CandidateRuleFact>> factsByOwner) {
		Map<CompiledHopKey,List<CompiledHopKey>> readersBySource = new IdentityHashMap<>();
		Map<CompiledHopKey,Set<CompiledHopKey>> seenReadersBySource = new IdentityHashMap<>();
		for(var entry : factsByOwner.entrySet()) {
			CompiledHopKey owner = entry.getKey();
			for(CandidateRuleFact fact : entry.getValue())
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
						continue;
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						if(realization.nativeWorkerPoolResidencyForOwnedClause(clause) != null)
							continue;
						for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
							if(binding.kind() == CandidateInputBindingKind.RELOCATION)
								continue;
							CompiledHopKey source = binding.source().rule().parentOccurrence();
							Set<CompiledHopKey> seen = seenReadersBySource.computeIfAbsent(source,
								ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
							if(seen.add(owner))
								readersBySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(owner);
						}
					}
				}
		}
		Map<CompiledHopKey,List<CompiledHopKey>> immutable = new IdentityHashMap<>();
		readersBySource.forEach((source, readers) -> immutable.put(source, List.copyOf(readers)));
		return Collections.unmodifiableMap(immutable);
	}

	private static Set<CompiledHopKey> reverseReachableReaders(Set<CompiledHopKey> changedOwners,
		Map<CompiledHopKey,List<CompiledHopKey>> readersBySource) {
		Set<CompiledHopKey> affected = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<CompiledHopKey> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>(changedOwners);
		while(!pending.isEmpty()) {
			CompiledHopKey source = pending.removeFirst();
			if(!expanded.add(source))
				continue;
			for(CompiledHopKey reader : readersBySource.getOrDefault(source, List.of()))
				if(affected.add(reader))
					pending.addLast(reader);
		}
		return affected;
	}

	private record BoundOwnerRevisionImpact(Set<CompiledHopKey> before,
		Set<CompiledHopKey> after) {
		private static final BoundOwnerRevisionImpact EMPTY =
			new BoundOwnerRevisionImpact(Set.of(), Set.of());

		private boolean affects(CompiledHopKey owner) {
			return before.contains(owner) || after.contains(owner);
		}
	}

	private static void collectDerivedFoutOwnerReads(List<CandidateRuleFact> facts,
		Set<CompiledHopKey> owners) {
		for(CandidateRuleFact fact : facts)
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				if(emission.derivedFoutAction() != null)
					owners.add(emission.derivedFoutAction().durableAnchorOwner());
	}

	private boolean unchangedDirectContinuityFacts(NativePlacementContinuity next,
		CompiledHopKey occurrence, Set<CompiledHopKey> completeChangedOccurrences,
		RevisionComparisonWork comparisonWork) {
		if(completeChangedOccurrences != null && !completeChangedOccurrences.contains(occurrence)) {
			comparisonWork.hintedOwnersBypassed++;
			return true;
		}
		comparisonWork.ownersCompared++;
		DirectContinuityComparison result = directContinuityComparison(next, occurrence, comparisonWork);
		if(result.projectionNeeded())
			comparisonWork.continuityProjectionsCompared++;
		return result.unchanged();
	}

	/** Share exact comparisons within this revision pair. */
	private DirectContinuityComparison directContinuityComparison(NativePlacementContinuity next,
		CompiledHopKey occurrence, RevisionComparisonWork comparisonWork) {
		return comparisonWork.direct.computeIfAbsent(occurrence, owner -> {
			List<CandidateRuleFact> before = candidateFactsByKey.getOrDefault(owner, List.of());
			List<CandidateRuleFact> after = next.candidateFactsByKey.getOrDefault(owner, List.of());
			return sameCandidateFactIdentities(before, after) ? new DirectContinuityComparison(true, false)
				: new DirectContinuityComparison(continuityProjectionOwned(before, true, null)
					.equals(next.continuityProjectionOwned(after, true, this)), true);
		});
	}

	private static boolean sameCandidateFactIdentities(List<CandidateRuleFact> before,
		List<CandidateRuleFact> after) {
		if(before == after)
			return true;
		if(before.size() != after.size())
			return false;
		for(int i = 0; i < before.size(); i++)
			if(before.get(i) != after.get(i))
				return false;
		return true;
	}

	private static Set<ContinuityFactProjection> continuityProjection(List<CandidateRuleFact> facts) {
		return continuityProjection(facts, true);
	}

	private boolean unchangedGeneratedRootFacts(NativePlacementContinuity next, CompiledHopKey root,
		Set<CompiledHopKey> completeChangedOccurrences, RevisionComparisonWork comparisonWork) {
		if(completeChangedOccurrences != null && !completeChangedOccurrences.contains(root)) {
			comparisonWork.hintedOwnersBypassed++;
			return true;
		}
		comparisonWork.ownersCompared++;
		comparisonWork.continuityProjectionsCompared++;
		return continuityProjectionOwned(candidateFactsByKey.getOrDefault(root, List.of()), false, null)
			.equals(next.continuityProjectionOwned(
				next.candidateFactsByKey.getOrDefault(root, List.of()), false, this));
	}

	private static final class FactProjectionMemo {
		private static final FactProjectionMemo AMBIGUOUS = new FactProjectionMemo();
		private ContinuityFactProjection published;
		private ContinuityFactProjection generatedRoot;
		private List<CompiledHopKey> boundCandidateSources;
		private final Map<CandidateEmissionFact,EmissionProjectionMemo> emissions =
			new IdentityHashMap<>();
		private final Map<ContinuityEmissionHeader,EmissionProjectionMemo> emissionsByHeader =
			new HashMap<>();

		private ContinuityFactProjection get(boolean includePublishedRealizations) {
			return includePublishedRealizations ? published : generatedRoot;
		}

		private void put(boolean includePublishedRealizations, ContinuityFactProjection projection) {
			if(includePublishedRealizations)
				published = projection;
			else
				generatedRoot = projection;
		}

		private void remember(CandidateEmissionFact emission, EmissionProjectionMemo memo) {
			emissions.put(emission, memo);
			ContinuityEmissionHeader header = ContinuityEmissionHeader.of(emission);
			EmissionProjectionMemo prior = emissionsByHeader.putIfAbsent(header, memo);
			if(prior != null && prior != memo)
				emissionsByHeader.put(header, EmissionProjectionMemo.AMBIGUOUS);
		}

		private void inheritExact(FactProjectionMemo donor) {
			if(published == null)
				published = donor.published;
			if(generatedRoot == null)
				generatedRoot = donor.generatedRoot;
			if(boundCandidateSources == null)
				boundCandidateSources = donor.boundCandidateSources;
			donor.emissions.forEach(this::remember);
		}

		private EmissionProjectionMemo donor(CandidateEmissionFact emission) {
			EmissionProjectionMemo exact = emissions.get(emission);
			if(exact != null)
				return exact;
			EmissionProjectionMemo structural = emissionsByHeader.get(ContinuityEmissionHeader.of(emission));
			return structural == EmissionProjectionMemo.AMBIGUOUS ? null : structural;
		}
	}

	private static final class SkeletonFactMemo {
		private static final SkeletonFactMemo AMBIGUOUS = new SkeletonFactMemo();
		private final Object ownerToken = new Object();
		private final Map<CandidateRealizationSupportClause,SkeletonClauseMemo> clauses =
			new IdentityHashMap<>();
		private final Map<CandidateRealizationSupportClause,SkeletonClauseMemo> clausesByStructure =
			new HashMap<>();
		private long templateCount;
		private boolean shared;

		private void remember(CandidateRealizationSupportClause clause, SkeletonClauseMemo memo) {
			SkeletonClauseMemo priorExact = clauses.put(clause, memo);
			if(priorExact != null)
				templateCount -= priorExact.templates.size();
			templateCount += memo.templates.size();
			SkeletonClauseMemo prior = clausesByStructure.get(clause);
			if(prior == null || prior == priorExact)
				clausesByStructure.put(clause, memo);
			else if(prior != memo)
				clausesByStructure.put(clause, SkeletonClauseMemo.AMBIGUOUS);
		}

		private SkeletonFactMemo mutableCopy() {
			SkeletonFactMemo copy = new SkeletonFactMemo();
			copy.clauses.putAll(clauses);
			copy.clausesByStructure.putAll(clausesByStructure);
			copy.templateCount = templateCount;
			return copy;
		}

		private SkeletonClauseMemo donor(CandidateRealizationSupportClause clause) {
			SkeletonClauseMemo exact = clauses.get(clause);
			if(exact != null)
				return exact;
			SkeletonClauseMemo structural = clausesByStructure.get(clause);
			return structural == SkeletonClauseMemo.AMBIGUOUS ? null : structural;
		}
	}

	private static final class SkeletonClauseMemo {
		private static final SkeletonClauseMemo AMBIGUOUS =
			new SkeletonClauseMemo(null, null, null, null, Map.of());
		private final Object ownerToken;
		private final CandidateRealizationSupportClause clause;
		private final List<CandidateRealizationReference> support;
		private final ClausePinProjection pinProjection;
		private final Map<NativePoolWitness,SkeletonTemplate> templates;

		private SkeletonClauseMemo(Object ownerToken, CandidateRealizationSupportClause clause,
			List<CandidateRealizationReference> support, ClausePinProjection pinProjection) {
			this(ownerToken, clause, support, pinProjection, new HashMap<>());
		}

		private SkeletonClauseMemo(Object ownerToken, CandidateRealizationSupportClause clause,
			List<CandidateRealizationReference> support,
			ClausePinProjection pinProjection,
			Map<NativePoolWitness,SkeletonTemplate> templates) {
			this.ownerToken = ownerToken;
			this.clause = clause;
			this.support = support;
			this.pinProjection = pinProjection;
			this.templates = templates;
		}
	}

	private static final class ClausePinProjection {
		private final Map<CompiledHopKey,Integer> firstSupportIndex;
		private final boolean contradictory;

		private ClausePinProjection(Map<CompiledHopKey,Integer> firstSupportIndex,
			boolean contradictory) {
			this.firstSupportIndex = firstSupportIndex;
			this.contradictory = contradictory;
		}

		private static ClausePinProjection prepare(
			List<CandidateRealizationReference> support) {
			Map<CompiledHopKey,Integer> firstSupportIndex = new IdentityHashMap<>();
			for(int index = 0; index < support.size(); index++) {
				CandidateRealizationReference reference = support.get(index);
				CompiledHopKey source = reference.rule().parentOccurrence();
				Integer prior = firstSupportIndex.putIfAbsent(source, index);
				if(prior != null && !support.get(prior).equals(reference))
					return new ClausePinProjection(firstSupportIndex, true);
			}
			return new ClausePinProjection(firstSupportIndex, false);
		}

		private CandidateRealizationReference reference(
			List<CandidateRealizationReference> support, CompiledHopKey owner) {
			Integer index = firstSupportIndex.get(owner);
			return index == null ? null : support.get(index);
		}
	}

	private record SkeletonTemplate(Hop owner, List<SkeletonTemplateDependency> dependencies) {
		private SkeletonTemplate {
			Objects.requireNonNull(owner, "dependency skeleton owner");
			dependencies = List.copyOf(dependencies);
		}
	}

	private record SkeletonTemplateDependency(CompiledHopKey key, int pinnedSupportIndex,
		NativePoolWitness witness, int inputPosition) { }

	private static final class EmissionProjectionMemo {
		private static final EmissionProjectionMemo AMBIGUOUS = new EmissionProjectionMemo();
		private ContinuityEmissionProjection published;
		private ContinuityEmissionProjection generatedRoot;
		private final Map<CandidateEmissionRealization,RealizationProjectionMemo> realizations =
			new IdentityHashMap<>();
		private final Map<PlacementRealizationKey,RealizationProjectionMemo> realizationsByKey =
			new HashMap<>();

		private ContinuityEmissionProjection get(boolean includePublishedRealizations) {
			return includePublishedRealizations ? published : generatedRoot;
		}

		private void put(boolean includePublishedRealizations, ContinuityEmissionProjection projection) {
			if(includePublishedRealizations)
				published = projection;
			else
				generatedRoot = projection;
		}

		private void remember(CandidateEmissionRealization realization,
			RealizationProjectionMemo memo) {
			realizations.put(realization, memo);
			RealizationProjectionMemo prior = realizationsByKey.putIfAbsent(realization.key(), memo);
			if(prior != null && prior != memo)
				realizationsByKey.put(realization.key(), RealizationProjectionMemo.AMBIGUOUS);
		}

		private RealizationProjectionMemo donor(CandidateEmissionRealization realization) {
			RealizationProjectionMemo exact = realizations.get(realization);
			if(exact != null)
				return exact;
			RealizationProjectionMemo structural = realizationsByKey.get(realization.key());
			return structural == RealizationProjectionMemo.AMBIGUOUS ? null : structural;
		}
	}

	private static final class RealizationProjectionMemo {
		private static final RealizationProjectionMemo AMBIGUOUS = new RealizationProjectionMemo();
		private ContinuityRealizationProjection projection;
		private final Map<CandidateRealizationSupportClause,ContinuityClauseProjection> clauses =
			new IdentityHashMap<>();
		private final Map<CandidateRealizationInputBinding,ContinuityBindingProjection> bindings =
			new IdentityHashMap<>();
	}

	private record ContinuityEmissionHeader(PlacementEmissionState state, FType executionFType,
		DerivedFoutMaterializationActionKey action) {
		private static ContinuityEmissionHeader of(CandidateEmissionFact emission) {
			return new ContinuityEmissionHeader(emission.emissionState(), emission.executionFType(),
				emission.derivedFoutAction());
		}
	}

	private FactProjectionMemo factProjectionMemo(CandidateRuleFact fact) {
		FactProjectionMemo memo = continuityProjectionMemo.get(fact);
		if(memo == null) {
			memo = new FactProjectionMemo();
			continuityProjectionMemo.put(fact, memo);
		}
		rememberFactProjectionMemo(fact, memo);
		return memo;
	}

	private void rememberFactProjectionMemo(CandidateRuleFact fact, FactProjectionMemo memo) {
		continuityProjectionMemo.put(fact, memo);
		Map<List<CandidateInputState>,FactProjectionMemo> byInputs =
			continuityProjectionMemoByRule.computeIfAbsent(fact.key().parentOccurrence(),
				ignored -> new HashMap<>());
		FactProjectionMemo prior = byInputs.putIfAbsent(fact.key().orderedInputs(), memo);
		if(prior != null && prior != memo)
			byInputs.put(fact.key().orderedInputs(), FactProjectionMemo.AMBIGUOUS);
	}

	private FactProjectionMemo donorFactProjectionMemo(CandidateRuleFact fact) {
		Map<List<CandidateInputState>,FactProjectionMemo> byInputs =
			continuityProjectionMemoByRule.get(fact.key().parentOccurrence());
		if(byInputs == null)
			return null;
		FactProjectionMemo memo = byInputs.get(fact.key().orderedInputs());
		return memo == FactProjectionMemo.AMBIGUOUS ? null : memo;
	}

	private SkeletonFactMemo dependencySkeletonMemo(CandidateRuleFact fact) {
		SkeletonFactMemo memo = dependencySkeletonMemo.get(fact);
		if(memo == null) {
			memo = new SkeletonFactMemo();
			rememberDependencySkeletonMemo(fact, memo);
		}
		else if(memo.shared) {
			SkeletonFactMemo shared = memo;
			memo = shared.mutableCopy();
			dependencySkeletonMemo.put(fact, memo);
			Map<List<CandidateInputState>,SkeletonFactMemo> byInputs =
				dependencySkeletonMemoByRule.get(fact.key().parentOccurrence());
			if(byInputs != null && byInputs.get(fact.key().orderedInputs()) == shared)
				byInputs.put(fact.key().orderedInputs(), memo);
		}
		return memo;
	}

	private void rememberDependencySkeletonMemo(CandidateRuleFact fact, SkeletonFactMemo memo) {
		dependencySkeletonMemo.put(fact, memo);
		Map<List<CandidateInputState>,SkeletonFactMemo> byInputs =
			dependencySkeletonMemoByRule.computeIfAbsent(fact.key().parentOccurrence(),
				ignored -> new HashMap<>());
		SkeletonFactMemo prior = byInputs.putIfAbsent(fact.key().orderedInputs(), memo);
		if(prior != null && prior != memo)
			byInputs.put(fact.key().orderedInputs(), SkeletonFactMemo.AMBIGUOUS);
	}

	private SkeletonFactMemo donorDependencySkeletonMemo(CandidateRuleFact fact) {
		Map<List<CandidateInputState>,SkeletonFactMemo> byInputs =
			dependencySkeletonMemoByRule.get(fact.key().parentOccurrence());
		if(byInputs == null)
			return null;
		SkeletonFactMemo memo = byInputs.get(fact.key().orderedInputs());
		return memo == SkeletonFactMemo.AMBIGUOUS ? null : memo;
	}

	private static boolean sameSkeletonClauseAuthority(
		CandidateRealizationSupportClause left, CandidateRealizationSupportClause right) {
		if(left == right)
			return true;
		if(left == null || !left.equals(right))
			return false;
		for(int i = 0; i < left.proofDependencies().size(); i++)
			if(left.proofDependencies().get(i).owner() != right.proofDependencies().get(i).owner())
				return false;
		for(int i = 0; i < left.inputBindings().size(); i++) {
			CandidateRealizationInputBinding a = left.inputBindings().get(i);
			CandidateRealizationInputBinding b = right.inputBindings().get(i);
			if(a.source().rule().parentOccurrence() != b.source().rule().parentOccurrence()
				|| !sameRelocationConsumerAuthority(a.relocationAction(), b.relocationAction()))
				return false;
		}
		return true;
	}

	private static boolean sameRelocationConsumerAuthority(
		PlacementIdentity.RelocationActionKey left, PlacementIdentity.RelocationActionKey right) {
		if(left == right)
			return true;
		if(left == null || !left.equals(right)
			|| left.compatibleConsumers().size() != right.compatibleConsumers().size())
			return false;
		for(int i = 0; i < left.compatibleConsumers().size(); i++)
			if(left.compatibleConsumers().get(i) != right.compatibleConsumers().get(i))
				return false;
		return true;
	}

	private Set<ContinuityFactProjection> continuityProjectionOwned(List<CandidateRuleFact> facts,
		boolean includePublishedRealizations, NativePlacementContinuity donor) {
		Set<ContinuityFactProjection> projected = new java.util.HashSet<>();
		for(CandidateRuleFact fact : facts) {
			FactProjectionMemo memo = continuityProjectionMemo.get(fact);
			FactProjectionMemo inherited = donor == null ? null
				: donor.continuityProjectionMemo.get(fact);
			if(memo == null && inherited != null) {
				memo = inherited;
				rememberFactProjectionMemo(fact, inherited);
			}
			else if(memo != null && inherited != null && memo != inherited)
				memo.inheritExact(inherited);
			if(memo == null)
				memo = factProjectionMemo(fact);
			else
				rememberFactProjectionMemo(fact, memo);
			ContinuityFactProjection projection = memo.get(includePublishedRealizations);
			FactProjectionMemo donorMemo = donor == null ? null
				: donor.donorFactProjectionMemo(fact);
			if(projection == null) {
				projection = continuityFactProjectionOwned(fact, includePublishedRealizations,
					memo, donorMemo);
				memo.put(includePublishedRealizations, projection);
			}
			projected.add(projection);
		}
		return cachedSet(projected);
	}

	private static ContinuityFactProjection continuityFactProjectionOwned(CandidateRuleFact fact,
		boolean includePublishedRealizations, FactProjectionMemo memo,
		FactProjectionMemo donorMemo) {
		Set<ContinuityEmissionProjection> emissions = new java.util.HashSet<>();
		for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
			emissions.add(continuityEmissionProjectionOwned(emission,
				includePublishedRealizations, memo, donorMemo));
		return new ContinuityFactProjection(fact.key(), fact.status(), cachedSet(emissions));
	}

	private static ContinuityEmissionProjection continuityEmissionProjectionOwned(
		CandidateEmissionFact emission, boolean includePublishedRealizations,
		FactProjectionMemo memo, FactProjectionMemo donorMemo) {
		EmissionProjectionMemo current = memo.emissions.get(emission);
		EmissionProjectionMemo exactDonor = donorMemo == null ? null : donorMemo.emissions.get(emission);
		EmissionProjectionMemo donor = donorMemo == null ? null : donorMemo.donor(emission);
		if(current == null) {
			current = exactDonor == null ? new EmissionProjectionMemo() : exactDonor;
			memo.remember(emission, current);
		}
		ContinuityEmissionProjection projection = current.get(includePublishedRealizations);
		if(projection == null && !includePublishedRealizations && donor != null)
			projection = donor.generatedRoot;
		if(projection == null) {
			Set<ContinuityRealizationProjection> realizations = new java.util.HashSet<>();
			if(includePublishedRealizations)
				for(CandidateEmissionRealization realization : emission.realizations())
					realizations.add(continuityRealizationProjectionOwned(
						realization, current, donor));
			projection = new ContinuityEmissionProjection(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), cachedSet(realizations));
		}
		current.put(includePublishedRealizations, projection);
		return projection;
	}

	private static ContinuityRealizationProjection continuityRealizationProjectionOwned(
		CandidateEmissionRealization realization, EmissionProjectionMemo memo,
		EmissionProjectionMemo donorMemo) {
		RealizationProjectionMemo current = memo.realizations.get(realization);
		RealizationProjectionMemo exactDonor = donorMemo == null ? null
			: donorMemo.realizations.get(realization);
		RealizationProjectionMemo donor = donorMemo == null ? null : donorMemo.donor(realization);
		if(current == null) {
			current = exactDonor == null ? new RealizationProjectionMemo() : exactDonor;
			memo.remember(realization, current);
		}
		if(current.projection == null) {
			if(realization.supportClauses() instanceof NativeContinuitySupportClauses product) {
				current.projection = new ContinuityRealizationProjection(realization.key(), Set.of(),
					continuityProductProjectionOwned(product, current, donor));
				return current.projection;
			}
			Set<ContinuityClauseProjection> clauses = new java.util.HashSet<>();
			for(CandidateRealizationSupportClause clause : realization.supportClauses())
				clauses.add(continuityClauseProjectionOwned(clause, current, donor));
			current.projection = new ContinuityRealizationProjection(
				realization.key(), cachedSet(clauses));
		}
		return current.projection;
	}

	private static NativeProductContinuityProjection continuityProductProjectionOwned(
		NativeContinuitySupportClauses product, RealizationProjectionMemo memo,
		RealizationProjectionMemo donorMemo) {
		List<List<ContinuityBindingProjection>> axes = product.commonAxes().stream()
			.map(axis -> axis.stream()
				.map(binding -> continuityBindingProjectionOwned(binding, memo, donorMemo))
				.toList())
			.toList();
		List<List<ContinuityBindingProjection>> excluded = product.conditionalComplement()
			? product.excludedExactAxes().stream().map(axis -> axis.stream()
				.map(binding -> continuityBindingProjectionOwned(binding, memo, donorMemo)).toList())
				.toList() : null;
		return new NativeProductContinuityProjection(
			axes, excluded, product.clauseWitness(), product.clauseLayoutExact());
	}

	private static ContinuityBindingProjection continuityBindingProjectionOwned(
		CandidateRealizationInputBinding binding, RealizationProjectionMemo memo,
		RealizationProjectionMemo donorMemo) {
		ContinuityBindingProjection projection = memo.bindings.get(binding);
		if(projection == null && donorMemo != null)
			projection = donorMemo.bindings.get(binding);
		if(projection == null)
			projection = new ContinuityBindingProjection(binding);
		memo.bindings.put(binding, projection);
		return projection;
	}

	private static ContinuityClauseProjection continuityClauseProjectionOwned(
		CandidateRealizationSupportClause clause, RealizationProjectionMemo memo,
		RealizationProjectionMemo donorMemo) {
		ContinuityClauseProjection current = memo.clauses.get(clause);
		if(current != null)
			return current;
		ContinuityClauseProjection exactDonor = donorMemo == null ? null : donorMemo.clauses.get(clause);
		if(exactDonor != null) {
			for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
				ContinuityBindingProjection projection = donorMemo.bindings.get(binding);
				if(projection == null)
					projection = new ContinuityBindingProjection(binding);
				memo.bindings.put(binding, projection);
			}
			memo.clauses.put(clause, exactDonor);
			return exactDonor;
		}
		List<ContinuityBindingProjection> bindings = new ArrayList<>(clause.inputBindings().size());
		for(CandidateRealizationInputBinding binding : clause.inputBindings())
			bindings.add(continuityBindingProjectionOwned(binding, memo, donorMemo));
		current = new ContinuityClauseProjection(List.copyOf(bindings),
			clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact());
		memo.clauses.put(clause, current);
		return current;
	}

	private static Set<ContinuityFactProjection> continuityProjection(List<CandidateRuleFact> facts,
		boolean includePublishedRealizations) {
		Set<ContinuityFactProjection> projected = new java.util.HashSet<>();
		for(CandidateRuleFact fact : facts)
			projected.add(continuityFactProjection(fact, includePublishedRealizations));
		return cachedSet(projected);
	}

	private static ContinuityFactProjection continuityFactProjection(CandidateRuleFact fact,
		boolean includePublishedRealizations) {
		Set<ContinuityEmissionProjection> emissions = new java.util.HashSet<>();
		for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
			Set<ContinuityRealizationProjection> realizations = new java.util.HashSet<>();
			for(CandidateEmissionRealization realization : includePublishedRealizations
				? emission.realizations() : List.<CandidateEmissionRealization>of()) {
				if(realization.supportClauses() instanceof NativeContinuitySupportClauses product) {
					realizations.add(new ContinuityRealizationProjection(realization.key(), Set.of(),
						continuityProductProjection(product)));
					continue;
				}
				Set<ContinuityClauseProjection> clauses = new java.util.HashSet<>();
				for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
					List<ContinuityBindingProjection> bindings = clause.inputBindings().stream()
						.map(ContinuityBindingProjection::new).toList();
					clauses.add(new ContinuityClauseProjection(bindings,
						clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact()));
				}
				realizations.add(new ContinuityRealizationProjection(
					realization.key(), cachedSet(clauses)));
			}
			emissions.add(new ContinuityEmissionProjection(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), cachedSet(realizations)));
		}
		return new ContinuityFactProjection(fact.key(), fact.status(), cachedSet(emissions));
	}

	private static NativeProductContinuityProjection continuityProductProjection(
		NativeContinuitySupportClauses product) {
		List<List<ContinuityBindingProjection>> axes = product.commonAxes().stream()
			.map(axis -> axis.stream().map(ContinuityBindingProjection::new).toList())
			.toList();
		List<List<ContinuityBindingProjection>> excluded = product.conditionalComplement()
			? product.excludedExactAxes().stream().map(axis -> axis.stream()
				.map(ContinuityBindingProjection::new).toList()).toList() : null;
		return new NativeProductContinuityProjection(
			axes, excluded, product.clauseWitness(), product.clauseLayoutExact());
	}

	private static <E> Set<E> cachedSet(Set<E> ownedElements) {
		return new CachedImmutableSet<>(ownedElements);
	}

	/** Immutable set whose hash is paid once while its projection object is built. */
	private static final class CachedImmutableSet<E> extends AbstractSet<E> {
		private final Set<E> elements;
		private final int hashCode;

		private CachedImmutableSet(Set<E> ownedElements) {
			// Every caller transfers a fresh local set; the unmodifiable view is the only escaping alias.
			elements = Collections.unmodifiableSet(ownedElements);
			hashCode = this.elements.hashCode();
		}

		@Override public Iterator<E> iterator() { return elements.iterator(); }
		@Override public int size() { return elements.size(); }
		@Override public boolean contains(Object value) { return elements.contains(value); }
		@Override public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(other instanceof CachedImmutableSet<?> that)
				return hashCode == that.hashCode && elements.equals(that.elements);
			return elements.equals(other);
		}
	}

	private record ContinuityFactProjection(CandidateRuleKey key, CandidateEvaluationStatus status,
		Set<ContinuityEmissionProjection> emissions) { }
	private record ContinuityEmissionProjection(PlacementEmissionState state, FType executionFType,
		DerivedFoutMaterializationActionKey action,
		Set<ContinuityRealizationProjection> realizations) { }
	private record ContinuityRealizationProjection(PlacementRealizationKey key,
		Set<ContinuityClauseProjection> clauses,
		NativeProductContinuityProjection nativeProduct) {
		private ContinuityRealizationProjection(PlacementRealizationKey key,
			Set<ContinuityClauseProjection> clauses) {
			this(key, clauses, null);
		}
	}
	private record NativeProductContinuityProjection(
		List<List<ContinuityBindingProjection>> axes,
		List<List<ContinuityBindingProjection>> excludedExactAxes,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact) { }
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
	 * the sole validation boundary for compiled edges and owns the exact lazy SCC input.
	 */
	private static final class StructuralContext {
		private final Object replayReceiptLineage = new Object();
		private final NativeWitnessArena nativeWitnessArena;
		private final Map<CompiledHopKey,Node> nodesByKey;
		private final Map<CompiledHopKey,Hop> originsByKey;
		private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> edgesByConsumer;
		private final Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions;
		private final Set<CompiledHopKey> incompleteSources;
		private final Map<CompiledHopKey,Privacy> privacyByKey;
		private final Set<ValueVersionKey> broadcastCapableValueVersions;
		private final LazyOccurrenceComponents occurrenceComponents;
		private final ComponentReadSet componentReadSet;

		private StructuralContext(StructuralContext source, Map<CompiledHopKey,Node> nodesByKey) {
			nativeWitnessArena = source.nativeWitnessArena;
			this.nodesByKey = Collections.unmodifiableMap(nodesByKey);
			originsByKey = source.originsByKey;
			edgesByConsumer = source.edgesByConsumer;
			reachingDefinitions = source.reachingDefinitions;
			incompleteSources = source.incompleteSources;
			privacyByKey = source.privacyByKey;
			broadcastCapableValueVersions = broadcastCapableValueVersions(nodesByKey);
			occurrenceComponents = source.occurrenceComponents;
			componentReadSet = source.componentReadSet;
		}

		private StructuralContext withNodeAuthority(Node replacement) {
			CompiledHopKey owner = replacement.key();
			Node previous = nodesByKey.get(owner);
			if(previous == null || previous.key() != owner)
				throw new IllegalArgumentException("Node-authority delta has a foreign owner identity");
			Map<CompiledHopKey,Node> revised = new IdentityHashMap<>(nodesByKey);
			revised.put(owner, replacement);
			return new StructuralContext(this, revised);
		}


		private StructuralContext(Map<CompiledHopKey,Node> nodesByKey,
			Map<CompiledHopKey,Hop> originsByKey, List<CompiledInputEdgeFact> compiledEdges,
			Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
			Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey) {
			this(nodesByKey, originsByKey, compiledEdges, reachingDefinitions,
				incompleteSources, privacyByKey, null);
		}

		private StructuralContext(Map<CompiledHopKey,Node> nodesByKey,
			Map<CompiledHopKey,Hop> originsByKey, List<CompiledInputEdgeFact> compiledEdges,
			Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
			Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey,
			StructuralContext reusableComponents) {
			nativeWitnessArena = new NativeWitnessArena(4096);
			this.nodesByKey = immutableIdentityMap(nodesByKey, "nodesByKey");
			this.originsByKey = immutableIdentityMap(originsByKey, "originsByKey");
			this.privacyByKey = immutableIdentityMap(privacyByKey, "privacyByKey");
			broadcastCapableValueVersions = broadcastCapableValueVersions(this.nodesByKey);
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
			componentReadSet = new ComponentReadSet(List.copyOf(owners), dependencies);
			occurrenceComponents = reusableComponents != null
				&& componentReadSet.matches(reusableComponents.componentReadSet)
				? reusableComponents.occurrenceComponents
				: new LazyOccurrenceComponents(componentReadSet);
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

		private static Set<ValueVersionKey> broadcastCapableValueVersions(
			Map<CompiledHopKey,Node> nodesByKey) {
			Set<ValueVersionKey> versions = new java.util.HashSet<>();
			for(Node node : nodesByKey.values())
				if(node.legalAlternatives().stream().anyMatch(state ->
					state.output() == FederatedOutput.FOUT && state.fType() == FType.BROADCAST))
					versions.add(node.valueVersion());
			return Set.copyOf(versions);
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

	/** Shared immutable SCC input whose expensive index is built only by SCC-aware optimizations. */
	private static final class LazyOccurrenceComponents {
		private final ComponentReadSet readSet;
		private volatile PlacementDependencyComponents components;

		private LazyOccurrenceComponents(ComponentReadSet readSet) {
			this.readSet = Objects.requireNonNull(readSet, "readSet");
		}

		private PlacementDependencyComponents components() {
			PlacementDependencyComponents current = components;
			if(current != null)
				return current;
			synchronized(this) {
				if(components == null)
					components = new PlacementDependencyComponents(
						readSet.owners(), readSet.dependencies(), List.of());
				return components;
			}
		}
	}

	/** Complete identity-ordered input of the native occurrence SCC/index. */
	private static final class ComponentReadSet {
		private final List<CompiledHopKey> owners;
		private final List<PlacementDependencyComponents.SemanticDependency> dependencies;

		private ComponentReadSet(List<CompiledHopKey> owners,
			List<PlacementDependencyComponents.SemanticDependency> dependencies) {
			this.owners = List.copyOf(owners);
			this.dependencies = List.copyOf(dependencies);
		}

		private List<CompiledHopKey> owners() {
			return owners;
		}

		private List<PlacementDependencyComponents.SemanticDependency> dependencies() {
			return dependencies;
		}

		private boolean matches(ComponentReadSet that) {
			if(that == null || owners.size() != that.owners.size()
				|| dependencies.size() != that.dependencies.size())
				return false;
			for(int index = 0; index < owners.size(); index++)
				if(owners.get(index) != that.owners.get(index))
					return false;
			for(int index = 0; index < dependencies.size(); index++) {
				PlacementDependencyComponents.SemanticDependency left = dependencies.get(index);
				PlacementDependencyComponents.SemanticDependency right = that.dependencies.get(index);
				if(left.producer() != right.producer() || left.consumer() != right.consumer())
					return false;
			}
			return true;
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
		if(proof.values().stream().anyMatch(node -> !node.valid
			|| !node.directGround && node.dependencies.isEmpty()))
			return false;
		// Validated nonrecursive programs obtain their entry/input obligations from
		// the final boundary relations. This helper checks physical compatibility;
		// it does not independently establish complete program derivability.
		return true;
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
		return provePrimitiveCandidateSupport(source, externalSeed).proofs();
	}

	CandidateSupportResult provePrimitiveCandidateSupport(
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
					return new CandidateSupportResult(List.of(),
						Set.of(source.rule().parentOccurrence()));
				matchedFact = fact;
				matchedEmission = emission;
			}
		}
		return matchedFact == null
			? new CandidateSupportResult(List.of(), Set.of(source.rule().parentOccurrence()))
			: proveGeneratedCandidateSupport(matchedFact, matchedEmission, source, externalSeed);
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
		return proveGeneratedCandidateSupport(
			trustedBase, baseEmission, proposedOutput, externalSeed).proofs();
	}

	CandidateSupportResult proveGeneratedCandidateSupport(
		CandidateRuleFact trustedBase, CandidateEmissionFact baseEmission,
		CandidateRealizationReference proposedOutput, DurableAnchorKey externalSeed) {
		return proveGeneratedCandidateSupport(
			trustedBase, baseEmission, proposedOutput, externalSeed, null);
	}

	GeneratedSupportBatch generatedSupportBatch(
		CandidateRuleFact trustedBase, CandidateEmissionFact baseEmission) {
		return supportMemoMaxEntries == 0 || supportMemoMaxTemplates == 0
			|| supportMemoMaxEstimatedBytes == 0 ? null
			: new GeneratedSupportBatch(trustedBase, baseEmission);
	}

	CandidateSupportResult proveGeneratedCandidateSupport(
		CandidateRuleFact trustedBase, CandidateEmissionFact baseEmission,
		CandidateRealizationReference proposedOutput, DurableAnchorKey externalSeed,
		GeneratedSupportBatch batch) {
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
			return new CandidateSupportResult(List.of(), Set.of(root));
		if(batch != null && !batch.matches(
			this, trustedBase, baseEmission, proposedOutput))
			batch = null;
		GenerationRoot generation = new GenerationRoot(trustedBase, baseEmission);
		PublicCandidateQueryKey query = new PublicCandidateQueryKey(
			proposedOutput, externalSeed, true);
		MemoEntry cached = completedProofMemo.get(query);
		if(cached != null) {
			if(metrics != null)
				metrics.recordMemoHit();
			return new CandidateSupportResult(cached.proofs(), cached.occurrences());
		}
		if(metrics != null)
			metrics.recordMemoMiss();
		ComputedPublicProof computed = computeCandidateAlternatives(
			proposedOutput, externalSeed, generation, batch);
		cacheCompletedProofs(query, computed);
		return new CandidateSupportResult(computed.proofs(), computed.occurrences());
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

	ReplayProofResult proveCandidateReplay(CandidateRealizationReference source,
		DurableAnchorKey externalSeed) {
		CandidateSupportResult support = proveCandidateSupport(source, externalSeed);
		CompiledHopKey root = source.rule().parentOccurrence();
		if(!support.dependencyOccurrences().contains(root))
			return new ReplayProofResult(support.proofs(), null);
		Map<CompiledHopKey,Object> footprint = new IdentityHashMap<>();
		for(CompiledHopKey occurrence : support.dependencyOccurrences())
			footprint.put(occurrence,
				replayOwnerRevisionTokens.computeIfAbsent(occurrence, ignored -> new Object()));
		ReplayProofReceipt receipt = new ReplayProofReceipt(
			structuralContext.replayReceiptLineage, source, externalSeed, footprint,
			support.proofs().isEmpty());
		return new ReplayProofResult(support.proofs(), receipt);
	}

	boolean matchesReplayProofReceipt(ReplayProofReceipt receipt,
		CandidateRealizationReference source, DurableAnchorKey externalSeed) {
		boolean matches = receipt != null
			&& receipt.structuralLineage == structuralContext.replayReceiptLineage
			&& receipt.source.rule().parentOccurrence() == source.rule().parentOccurrence()
			&& receipt.source.equals(source) && receipt.externalSeed.equals(externalSeed);
		if(matches)
			for(var entry : receipt.ownerRevisionTokens.entrySet())
				if(replayOwnerRevisionTokens.get(entry.getKey()) != entry.getValue()) {
					matches = false;
					break;
				}
		if(matches)
			replayReceiptHits++;
		else
			replayReceiptMisses++;
		return matches;
	}

	private ComputedPublicProof computeCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed, GenerationRoot generation) {
		return computeCandidateAlternatives(source, externalSeed, generation, null);
	}

	private ComputedPublicProof computeCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed, GenerationRoot generation,
		GeneratedSupportBatch batch) {
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
			source, externalSeed, witness, generation, batch);
		if(witness.fType != FType.ROW && witness.fType != FType.COL && witness.fType != FType.FULL)
			return exact;
		FType witnessType = witness.fType;
		NativePoolWitness dynamicWitness = distinctDynamicPartitionWitness(witness);
		if(dynamicWitness == null)
			return exact;
		ComputedPublicProof dynamic = proveCandidateAlternatives(
			source, externalSeed, dynamicWitness, generation, batch);
		List<NativeContinuityProof> dynamicProofs = retainDynamicProofs(dynamic.proofs(),
			recomputesNativePartitionRanges(owner, witnessType), this::hasDynamicNativeLayout);
		return mergeExactAndDynamicAlternatives(exact, dynamic, dynamicProofs);
	}

	static List<NativeContinuityProof> retainDynamicProofs(List<NativeContinuityProof> proofs,
		boolean recomputesRanges,
		java.util.function.Predicate<CandidateRealizationReference> dynamicSource) {
		if(proofs instanceof NativeContinuityProofProduct product) {
			if(recomputesRanges)
				return proofs;
			boolean anyDynamic = false;
			for(List<CandidateRealizationInputBinding> axis : product.product.axes) {
				boolean allDynamic = true;
				for(CandidateRealizationInputBinding binding : axis) {
					boolean dynamic = dynamicSource.test(binding.source());
					anyDynamic |= dynamic;
					allDynamic &= dynamic;
				}
				// Every member chooses one option from this nonempty axis.
				if(allDynamic)
					return proofs;
			}
			if(!anyDynamic)
				return List.of();
		}
		// Mixed products can have sparse holes. Retain their exact member filter.
		return proofs.stream().filter(proof -> recomputesRanges
			|| proof.immediateBindings().stream().anyMatch(binding -> dynamicSource.test(binding.source())))
			.toList();
	}

	private static NativePoolWitness distinctDynamicPartitionWitness(NativePoolWitness witness) {
		NativePoolWitness dynamic = witness.withDynamicPartitionRanges();
		return dynamic == witness ? null : dynamic;
	}

	private static ComputedPublicProof mergeExactAndDynamicAlternatives(
		ComputedPublicProof exact, ComputedPublicProof dynamic,
		List<NativeContinuityProof> dynamicProofs) {
		Set<CompiledHopKey> occurrences = Collections.newSetFromMap(new IdentityHashMap<>());
		occurrences.addAll(exact.occurrences());
		occurrences.addAll(dynamic.occurrences());
		Set<CompiledHopKey> immutableOccurrences = Collections.unmodifiableSet(occurrences);
		if(dynamicProofs.isEmpty())
			return new ComputedPublicProof(exact.proofs(), immutableOccurrences);
		if(exact.proofs().isEmpty())
			return new ComputedPublicProof(dynamicProofs, immutableOccurrences);
		java.util.Comparator<NativeContinuityProof> comparator = nativeProofSignatureComparator();
		List<NativeContinuityProof> merged = new ArrayList<>(
			exact.proofs().size() + dynamicProofs.size());
		Set<NativeContinuityProof> seen = new LinkedHashSet<>();
		int exactIndex = 0, dynamicIndex = 0;
		while(exactIndex < exact.proofs().size() || dynamicIndex < dynamicProofs.size()) {
			NativeContinuityProof next;
			if(dynamicIndex == dynamicProofs.size()
				|| exactIndex < exact.proofs().size() && comparator.compare(
					exact.proofs().get(exactIndex), dynamicProofs.get(dynamicIndex)) <= 0)
				next = exact.proofs().get(exactIndex++);
			else
				next = dynamicProofs.get(dynamicIndex++);
			if(seen.add(next))
				merged.add(next);
		}
		return new ComputedPublicProof(List.copyOf(merged), immutableOccurrences);
	}

	private static java.util.Comparator<NativeContinuityProof> nativeProofSignatureComparator() {
		java.util.Comparator<PlacementAnalysis.NormalizedText> textComparator =
			PlacementAnalysis.normalizedTextComparator();
		return (left, right) -> {
			PlacementAnalysis.NormalizedText leftSuffix = left.canonicalOrderingSuffixText();
			PlacementAnalysis.NormalizedText rightSuffix = right.canonicalOrderingSuffixText();
			if(leftSuffix != null && rightSuffix != null
				&& (left.externalSeed() == right.externalSeed()
					|| left.externalSeed().equals(right.externalSeed()))) {
				PlacementAnalysis.NormalizedText leftTail = left.canonicalRangeBindingText();
				PlacementAnalysis.NormalizedText rightTail = right.canonicalRangeBindingText();
				if(leftTail != null && rightTail != null
					&& (left.outputWorkerPoolWitness() == right.outputWorkerPoolWitness()
						|| left.outputWorkerPoolWitness().equals(right.outputWorkerPoolWitness())))
					return textComparator.compare(leftTail, rightTail);
				return textComparator.compare(leftSuffix, rightSuffix);
			}
			return textComparator.compare(
				left.normalizedSignatureText(), right.normalizedSignatureText());
		};
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
		// Charge the compressed immutable metadata, never its logical Cartesian members.
		// The ordinary proof-count budget still charges product.size(), so this only
		// admits relations already within the existing logical-proof limit.
		if(proofs instanceof NativeContinuityProofProduct product)
			return product.estimatedPublicMemoBytes();
		long bytes = 0;
		for(NativeContinuityProof proof : proofs) {
			long proofBytes = 96L + 2L * proof.normalizedSignatureLength()
				+ 32L * proof.immediateBindings().size();
			bytes = Long.MAX_VALUE - bytes < proofBytes ? Long.MAX_VALUE : bytes + proofBytes;
		}
		return bytes;
	}

	private CandidateSupportQueryKey candidateSupportQueryKey(
		CandidateRealizationReference source, NativePoolWitness witness, boolean generated) {
		int sourceHandle = candidateHandle(source);
		// A generated root uses the trusted base row, not its previously published
		// topology. Keep its full source identity instead of building that topology
		// merely to choose a memo bucket. This refines absent-row groups; it cannot
		// make two previously distinct queries share a support result.
		if(generated)
			return new CandidateSupportQueryKey(source, sourceHandle, witness, true, true);
		// A native-containing owner can supply ordinary rows without flattening its
		// sibling products. Exact handles conservatively refine the old shallow
		// bucket, including missing rows, and are recomputed during revision transfer.
		if(hasNativeRelationOwner(source.rule().parentOccurrence()))
			return new CandidateSupportQueryKey(source, sourceHandle, witness, true, false);
		if(metrics != null) {
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.SUPPORT_QUERY_TOPOLOGY_REQUESTS);
			CandidateTopologyKey resident = residentTopologyKey(source.rule().parentOccurrence(), witness);
			metrics.recordDirectWork(resident != null && candidateTopologies.containsKey(resident)
				? SearchSpaceMetrics.DirectWork.SUPPORT_QUERY_TOPOLOGY_RESIDENT
				: SearchSpaceMetrics.DirectWork.SUPPORT_QUERY_TOPOLOGY_COLD);
		}
		CandidateTopology topology = candidateTopology(source.rule().parentOccurrence(), witness);
		boolean exactTopologyRow = topology.rowsByHandle.containsKey(sourceHandle);
		return new CandidateSupportQueryKey(source, sourceHandle, witness, exactTopologyRow, generated);
	}

	private List<NativeContinuityProof> instantiateSupportTemplates(SupportMemoEntry entry,
		CandidateRealizationReference source, DurableAnchorKey externalSeed) {
		return instantiateSupportTemplates(entry, source, externalSeed, false);
	}

	private List<NativeContinuityProof> instantiateSupportTemplates(SupportMemoEntry entry,
		CandidateRealizationReference source, DurableAnchorKey externalSeed,
		boolean certifiedRootFree) {
		SearchSpaceMetrics.PhaseToken started = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.PUBLIC_PROOF_MATERIALIZATION);
		try {
			// A certified generated batch has no returned root binding to rename.
			// Keep its factor axes and canonical order, but instantiate the current seed.
			boolean unchangedBindings = certifiedRootFree || entry.root.equals(source);
			if(unchangedBindings && entry.templates instanceof CandidateSupportTemplateProduct product)
				return new NativeContinuityProofProduct(externalSeed, product);
			if(unchangedBindings && entry.templates instanceof CandidateConditionalSupportTemplateProduct product)
				return new NativeContinuityProofProduct(externalSeed, product);
			List<NativeContinuityProof> proofs = new ArrayList<>(entry.templates.size());
			for(CandidateSupportTemplate template : entry.templates) {
				List<CandidateRealizationInputBinding> bindings = unchangedBindings
					? template.immediateBindings : rebindTemplateRoot(
						template.immediateBindings, entry.root, source);
				proofs.add(new NativeContinuityProof(externalSeed, template.outputWorkerPoolWitness,
					template.exactPartitionRanges, bindings,
					unchangedBindings ? template.canonicalOrderingSuffixLength : -1));
			}
			if(unchangedBindings)
				return List.copyOf(proofs);
			proofs.sort(nativeProofSignatureComparator());
			Set<NativeContinuityProof> distinct = new LinkedHashSet<>();
			distinct.addAll(proofs);
			return List.copyOf(distinct);
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
		if(templates instanceof CandidateSupportTemplateProduct product)
			return product.estimatedRetainedBytes();
		if(templates instanceof CandidateConditionalSupportTemplateProduct product)
			return product.estimatedRetainedBytes();
		long bytes = 0;
		for(CandidateSupportTemplate template : templates) {
			// Include the trusted suffix-length scalar plus ordinary object alignment.
			long templateBytes = 88L + 32L * template.immediateBindings.size();
			bytes = Long.MAX_VALUE - bytes < templateBytes
				? Long.MAX_VALUE : bytes + templateBytes;
		}
		return Math.max(32L, bytes);
	}

	private static PlacementAnalysis.NormalizedText supportTemplateOrderingText(
		CandidateSupportTemplate template, PlacementAnalysis.NormalizedTextContext textContext) {
		PlacementAnalysis.NormalizedTextBuilder builder =
			new PlacementAnalysis.NormalizedTextBuilder()
				.append(template.outputWorkerPoolWitness.normalizedSignature())
				.append("|partitionRanges=")
				.append(template.exactPartitionRanges ? "exact" : "dynamic")
				.append("|bindings=[");
		for(int index = 0; index < template.immediateBindings.size(); index++) {
			if(index > 0)
				builder.append(", ");
			builder.append(textContext.binding(template.immediateBindings.get(index)));
		}
		return builder.append("]").build();
	}

	private boolean hasDynamicNativeLayout(CandidateRealizationReference reference) {
		return candidateFactsByKey.getOrDefault(reference.rule().parentOccurrence(), List.of()).stream()
			.filter(fact -> fact.key().equals(reference.rule()))
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().equals(reference.realization()))
			.anyMatch(realization -> hasDynamicNativeLayout(realization.supportClauses()));
	}

	static boolean hasDynamicNativeLayout(List<CandidateRealizationSupportClause> supportClauses) {
		// Every member uses this exact clause metadata, independently of its proof's ranges.
		if(supportClauses instanceof NativeContinuitySupportClauses product)
			return product.clauseWitness() != null && !product.clauseLayoutExact();
		if(supportClauses instanceof FactorizedSupportClauses factorized)
			return factorized.nativeWorkerPoolWitness() != null
				&& !factorized.nativeWorkerPoolLayoutExact();
		if(supportClauses instanceof IndexedSupportClauses indexed) {
			for(int row = 0; row < indexed.size(); row++)
				if(indexed.witnessAt(row) != null && !indexed.layoutExactAt(row))
					return true;
			return false;
		}
		for(var clause : supportClauses)
			if(clause.nativeWorkerPoolWitness() != null
				&& !clause.nativeWorkerPoolLayoutExact())
				return true;
		return false;
	}

	private ComputedPublicProof proveCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed, NativePoolWitness witness, GenerationRoot generation) {
		return proveCandidateAlternatives(source, externalSeed, witness, generation, null);
	}

	private ComputedPublicProof proveCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed, NativePoolWitness witness, GenerationRoot generation,
		GeneratedSupportBatch batch) {
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
		SupportMemoEntry batchEntry = batch == null ? null : batch.residentSupport(witness);
		if(batchEntry != null) {
			if(metrics != null) {
				metrics.recordSupportMemoHit();
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.GENERATED_BATCH_REUSE_HITS);
			}
			// Do not retain another alias in the bounded support memo. The final
			// public proof is still keyed by this exact proposal and external seed.
			return new ComputedPublicProof(
				instantiateSupportTemplates(batchEntry, source, externalSeed, true),
				batchEntry.occurrences());
		}
		if(metrics != null)
			metrics.recordSupportMemoMiss();
		ComputedCandidateSupport computed = computeCandidateSupportAlternatives(
			source, witness, generation);
		SupportMemoEntry entry = new SupportMemoEntry(source, computed.templates,
			computed.occurrences, estimatedSupportBytes(computed.templates), computed.rootIndependent);
		cacheCompletedSupports(query, entry);
		if(batch != null)
			batch.admit(witness, query, entry);
		// A native-containing ordinary root may admit its bounded ordinary topology
		// only while building this first graph. Recheck the resident slice afterward;
		// never build or expand topology solely to manufacture a shared-root key.
		if(rootSupportKey == null && !generated && entry.rootIndependent
			&& hasNativeRelationOwner(source.rule().parentOccurrence()))
			rootSupportKey = acyclicRootSupportKey(source, witness);
		if(rootSupportKey != null && entry.rootIndependent)
			cacheAcyclicRootSupport(rootSupportKey, entry);
		return new ComputedPublicProof(instantiateSupportTemplates(entry, source, externalSeed),
			entry.occurrences());
	}

	private AcyclicRootSupportKey acyclicRootSupportKey(CandidateRealizationReference source,
		NativePoolWitness witness) {
		CompiledHopKey occurrence = source.rule().parentOccurrence();
		if(occurrenceComponents.components().componentOf(occurrence).cyclic())
			return null;
		boolean nativeOwner = hasNativeRelationOwner(occurrence);
		int handle = candidateHandle(source);
		CandidateTopology topology = null;
		if(nativeOwner) {
			// A native product remains an AND-of-axis-OR circuit and must never become
			// this flat relation key. Only an already resident bounded ordinary slice is
			// eligible; key construction itself never builds or expands fallback topology.
			if(hasNativeContinuityRelation(occurrence, source)
				|| !declaresExactRealization(occurrence, source))
				return null;
			CandidateTopologyKey resident = residentTopologyKey(occurrence, witness);
			topology = resident == null ? null : candidateTopologies.get(resident);
			if(topology == null || !topology.ordinaryOnly)
				return null;
		}
		if(metrics != null) {
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.ACYCLIC_ROOT_TOPOLOGY_REQUESTS);
			CandidateTopologyKey resident = residentTopologyKey(occurrence, witness);
			metrics.recordDirectWork(resident != null && candidateTopologies.containsKey(resident)
				? SearchSpaceMetrics.DirectWork.ACYCLIC_ROOT_TOPOLOGY_RESIDENT
				: SearchSpaceMetrics.DirectWork.ACYCLIC_ROOT_TOPOLOGY_COLD);
		}
		if(!nativeOwner)
			topology = candidateTopology(occurrence, witness);
		List<CandidateTopologyRow> rows = topology.rowsByHandle.get(handle);
		if(rows == null || rows.isEmpty())
			return null;
		List<RootTopologyRowKey> relation = rows.stream().map(row -> new RootTopologyRowKey(
			row.dependencies().stream().map(ContinuityDependencyKey::of).toList(),
			row.directGround())).toList();
		return new AcyclicRootSupportKey(occurrence, witness, relation);
	}

	private void cacheAcyclicRootSupport(AcyclicRootSupportKey key, SupportMemoEntry entry) {
		long retainedUnits = key.retainedUnits() + entry.templates.size();
		long estimatedBytes = estimatedAcyclicRootRetentionBytes(key, entry);
		if(supportMemoMaxEntries == 0 || supportMemoMaxTemplates == 0
			|| supportMemoMaxEstimatedBytes == 0
			|| retainedUnits > supportMemoMaxTemplates
			|| estimatedBytes > supportMemoMaxEstimatedBytes)
			return;
		SupportMemoEntry prior = acyclicRootSupportMemo.remove(key);
		if(prior != null) {
			acyclicRootRetainedUnits -= key.retainedUnits() + prior.templates.size();
			acyclicRootRetainedEstimatedBytes -= estimatedAcyclicRootRetentionBytes(key, prior);
		}
		while(!acyclicRootSupportMemo.isEmpty()
			&& (acyclicRootSupportMemo.size() >= supportMemoMaxEntries
				|| saturatedAdd(acyclicRootRetainedUnits, retainedUnits) > supportMemoMaxTemplates
				|| saturatedAdd(acyclicRootRetainedEstimatedBytes, estimatedBytes)
					> supportMemoMaxEstimatedBytes)) {
			var oldest = acyclicRootSupportMemo.entrySet().iterator().next();
			acyclicRootRetainedUnits -= oldest.getKey().retainedUnits()
				+ oldest.getValue().templates.size();
			acyclicRootRetainedEstimatedBytes -= estimatedAcyclicRootRetentionBytes(
				oldest.getKey(), oldest.getValue());
			acyclicRootSupportMemo.remove(oldest.getKey());
			if(metrics != null)
				metrics.recordSupportMemoEviction();
		}
		acyclicRootSupportMemo.put(key, entry);
		acyclicRootRetainedUnits += retainedUnits;
		acyclicRootRetainedEstimatedBytes += estimatedBytes;
	}

	private static long estimatedAcyclicRootRetentionBytes(
		AcyclicRootSupportKey key, SupportMemoEntry entry) {
		long occurrenceBytes = entry.occurrences.size() > Long.MAX_VALUE / 64L
			? Long.MAX_VALUE : 64L * entry.occurrences.size();
		return saturatedAdd(key.estimatedBytes(), saturatedAdd(entry.estimatedBytes,
			saturatedAdd(96L, occurrenceBytes)));
	}

	private static long saturatedAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
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
		FixedCandidateBoundary fixed = new FixedCandidateBoundary(
			source.rule().parentOccurrence(), source, sourceHandle);
		SearchSpaceMetrics.PhaseToken overlayStarted = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.PROOF_OVERLAY);
		long[] graphWork = metrics == null ? null : new long[2];
		CandidateProofTraversal traversal = new CandidateProofTraversal();
		try {
			buildCandidateProofGraph(root, root, generation,
				graph, traversal, fixed, graphWork);
			if(metrics != null)
				metrics.recordProofGraph(graph.size(), graphWork[0], graphWork[1]);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_OVERLAY, overlayStarted);
		}
		SearchSpaceMetrics.PhaseToken pruningStarted = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.PROOF_DEPENDENCY_PRUNING);
		Map<CandidateProofState,List<SelectedCandidateProof>> viable;
		Set<CandidateProofState> supported;
		long acyclicRemoved = 0;
		try {
			if(traversal.cycleDetected) {
				Map<CandidateProofState,AcyclicComponentFootprint> independentChildren =
					rootIndependentChildFootprints(root, graph, traversal);
				viable = pruneDeadAlternatives(graph, traversal,
					graphWork == null ? 0 : graphWork[0]);
				// Mandatory entry/input relations are enforced by the final program
				// boundaries. Only physical dependency viability is needed here.
				supported = viableCandidateStates(viable);
			cacheAcyclicRootChildren(independentChildren, viable, supported,
				generation == null ? null : root.key(), traversal);
			}
			else {
				Map<CandidateProofState,AcyclicComponentFootprint> childFootprints =
					acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
						|| acyclicComponentMaxAlternatives == 0 ? Map.of()
						: acyclicRootChildFootprints(root, graph, traversal);
				if(traversal.emptyFilteredStates == 0) {
					if(metrics != null)
						metrics.recordNoEmptyDagPruningSkip();
				}
				else
					acyclicRemoved = pruneDeadAcyclicAlternatives(graph, traversal.completionOrder);
				viable = graph;
				// In a DAG every surviving row reaches a direct leaf after dead pruning.
				supported = viableCandidateStates(viable);
			cacheAcyclicRootChildren(childFootprints, viable, supported,
				generation == null ? null : root.key(), traversal);
			}
			if(metrics != null)
				metrics.recordProofGraphPath(traversal.cycleDetected, acyclicRemoved);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_DEPENDENCY_PRUNING, pruningStarted);
		}
		SearchSpaceMetrics.PhaseToken supportStarted = metrics == null ? null
			: metrics.startPhase(SearchSpaceMetrics.Phase.SUPPORT_PRODUCT_RELATION_MATERIALIZATION);
		try {
		Map<CandidateProofState,List<CandidateRealizationReference>> supportedReferences =
			new java.util.HashMap<>();
		Map<CandidateProofState,Boolean> directlySupported = new java.util.HashMap<>();
			Set<CandidateSupportTemplate> proofs = new LinkedHashSet<>();
			Set<List<List<CandidateRealizationInputBinding>>> expandedSupportProducts =
				new LinkedHashSet<>();
		long[] rawProofs = metrics == null ? null : new long[] {0};
		DurableAnchorKey outputWitness = witness.asAnchor(
			"native-proof-output:" + source.rule().parentOccurrence().normalizedSignature());
		if(supported.contains(root))
			for(SelectedCandidateProof alternative : viable.getOrDefault(root, List.of())) {
				if((!alternative.directGround && alternative.dependencies.isEmpty())
					|| !alternative.dependencies.stream().allMatch(dependency -> supported.contains(dependency.state())))
					continue;
				List<List<CandidateRealizationInputBinding>> immediateOptions = new ArrayList<>();
				boolean complete = true;
				for(CandidateProofDependency dependency : alternative.dependencies) {
					if(dependency.inputPosition() < 0)
						continue;
					// A query-pinned generated recurrence may be physically compatible, but the
					// proposed output is not an executable premise for its own receipt.
					if(generation != null && dependency.state().equals(root)) {
						complete = false;
						break;
					}
					List<CandidateRealizationReference> options = supportedReferences.computeIfAbsent(
						dependency.state(), state -> canonicalSupportedReferences(
							viable.getOrDefault(state, List.of()), supported));
					if(options.isEmpty()) {
						if(!directlySupported.computeIfAbsent(dependency.state(), state -> viable
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
				}
			}
		List<CandidateSupportTemplate> distinct;
		CandidateSupportTemplateProduct product = CandidateSupportTemplateProduct.tryCreateUnion(
			outputWitness, witness.exactPartitionRanges, expandedSupportProducts);
		if(product != null) {
			distinct = product;
			if(metrics != null)
				rawProofs[0] = product.size();
		}
		else {
			CandidateConditionalSupportTemplateProduct conditional =
				CandidateConditionalSupportTemplateProduct.tryCreateUnion(outputWitness,
					witness.exactPartitionRanges, expandedSupportProducts);
			if(conditional != null) {
				distinct = conditional;
				if(metrics != null)
					rawProofs[0] = conditional.size();
			}
			else {
			for(List<List<CandidateRealizationInputBinding>> options : expandedSupportProducts)
				enumerateImmediateSupports(options, 0, new ArrayList<>(), support -> {
					if(metrics != null)
						rawProofs[0]++;
					proofs.add(new CandidateSupportTemplate(outputWitness,
						witness.exactPartitionRanges, support));
				});
			distinct = List.copyOf(proofs);
			}
		}
		if(distinct.isEmpty())
			traceFailedCandidateSupport(source, root, graph, supported);
		if(metrics != null)
			metrics.recordProofResult(rawProofs[0], distinct.size());
		Set<CompiledHopKey> occurrences = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateProofState state : graph.keySet())
			occurrences.add(state.key());
		for(Set<CompiledHopKey> hiddenReads : traversal.hiddenOwnerReadsByState.values())
			occurrences.addAll(hiddenReads);
		// A reused acyclic boundary deliberately hides its transitive graph states
		// from this query's work graph. They still belong to the completed support's
		// invalidation footprint and must cross revisions only when every row matches.
		for(AcyclicComponentSummary reused : traversal.reusedComponents.values())
			occurrences.addAll(reused.occurrences);
		boolean rootIndependent = generation != null
			? !traversal.generatedRootPublishedHistoryObserved
			: graph.entrySet().stream()
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
		Set<CandidateProofState> supported) {
		Hop owner = originsByKey.get(source.rule().parentOccurrence());
		if(!FederatedPlannerTrace.shouldTrace(owner))
			return;
		StringBuilder detail = new StringBuilder("owner=")
			.append(source.rule().parentOccurrence().callSitePath()).append(':')
			.append(source.rule().parentOccurrence().emittedHopInstance())
			.append("|opcode=").append(owner.getOpString())
			.append("|rootSupported=").append(supported.contains(root))
			.append("|rootPinnedHash=").append(source.hashCode())
			.append("|rootDeclared=").append(declaresExactRealization(
				source.rule().parentOccurrence(), source)).append("|states=[");
		int emitted = 0;
		for(var entry : graph.entrySet()) {
			CandidateProofState state = entry.getKey();
			List<SelectedCandidateProof> alternatives = entry.getValue();
			if(state != root && supported.contains(state) && !alternatives.isEmpty())
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
				.append("|supported=").append(supported.contains(state))
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

	private static List<CandidateRealizationReference> canonicalSupportedReferences(
		List<SelectedCandidateProof> alternatives, Set<CandidateProofState> supported) {
		if(alternatives instanceof DefaultAlternativeList defaults) {
			DefaultTraversalSchedule schedule = defaults.traversalSchedule(null);
			// Recheck query viability before using immutable row-only metadata. A later
			// query may have withdrawn a successor even when this exact list is reused.
			if(schedule.filteredAlternatives == defaults
				&& schedule.uniqueSuccessors.stream().allMatch(supported::contains))
				return defaults.canonicalRealizations();
		}
		return canonicalReferences(alternatives.stream()
			.filter(option -> option.realization != null)
			.filter(option -> (option.directGround || !option.dependencies.isEmpty())
				&& option.dependencies.stream().allMatch(child -> supported.contains(child.state())))
			.map(SelectedCandidateProof::realization).toList());
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
			List<SelectedCandidateProof> alternatives = entry.getValue();
			if(metrics != null)
				metrics.recordOwnerElementsScanned(alternatives.size());
			hasDeadSeed |= alternatives.isEmpty();
			if(!hasDeadSeed) {
				alternatives:
				for(int alternativeIndex = 0; alternativeIndex < alternatives.size(); alternativeIndex++) {
					List<CandidateProofDependency> dependencies = alternatives.get(alternativeIndex).dependencies;
					for(int dependencyIndex = 0; dependencyIndex < dependencies.size(); dependencyIndex++) {
						CandidateProofDependency dependency = dependencies.get(dependencyIndex);
						if(!graph.containsKey(dependency.state())) {
							hasDeadSeed = true;
							break alternatives;
						}
					}
				}
			}
		}
		if(!hasDeadSeed)
			return graph;
		return pruneDeadAlternativesFromKnownDeadSeed(graph, false);
	}

	private Map<CandidateProofState,List<SelectedCandidateProof>> pruneDeadAlternatives(
		Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal, long alternativeCount) {
		Objects.requireNonNull(traversal, "candidate proof traversal");
		if(metrics != null)
			metrics.recordOwnerElementsScanned(alternativeCount);
		if(traversal.emptyFilteredStates == 0)
			return graph;
		// buildCandidateProofGraph owns the traversal certificate: every dependency
		// of every retained alternative was recursively inserted into this graph.
		// Its exact empty-row count therefore replaces both conservative pre-scans.
		return pruneDeadAlternativesFromKnownDeadSeed(graph, true);
	}

	private Map<CandidateProofState,List<SelectedCandidateProof>>
		pruneDeadAlternativesFromKnownDeadSeed(
		Map<CandidateProofState,List<SelectedCandidateProof>> graph, boolean dependencyClosed) {
		// Dense IDs are query-local aliases for the complete state equality, not a
		// structural quotient. Keep every original key (including dead states) for
		// support extraction and revision invalidation.
		Map<CandidateProofState,Integer> stateIds = new java.util.HashMap<>();
		for(CandidateProofState state : graph.keySet())
			stateIds.put(state, stateIds.size());
		int slotCount = 0, edgeCount = 0;
		for(List<SelectedCandidateProof> alternatives : graph.values()) {
			slotCount = Math.addExact(slotCount, alternatives.size());
			DefaultTraversalSchedule schedule = dependencyClosed
				&& alternatives instanceof DefaultAlternativeList defaults ? defaults.schedule : null;
			if(schedule != null && schedule.filteredAlternatives == alternatives) {
				// The closed builder already consumed this exact immutable schedule.
				// Reuse its edge count without building another index or visiting rows.
				edgeCount = Math.addExact(edgeCount, Math.toIntExact(schedule.rawDependencyCount));
				continue;
			}
			for(int alternativeIndex = 0; alternativeIndex < alternatives.size(); alternativeIndex++) {
				List<CandidateProofDependency> dependencies = alternatives.get(alternativeIndex).dependencies;
				edgeCount = Math.addExact(edgeCount, dependencies.size());
				// Only the conservative entry point can observe dependencies absent from
				// graph.keySet(). The builder certificate makes this first lookup pass
				// redundant; keep counting every edge for the unchanged reverse index.
				if(!dependencyClosed)
					for(int dependencyIndex = 0; dependencyIndex < dependencies.size(); dependencyIndex++) {
						CandidateProofState dependency = dependencies.get(dependencyIndex).state();
						if(stateIds.get(dependency) == null)
							stateIds.put(dependency, stateIds.size());
					}
			}
		}
		int[] slotRemovalIds = new int[slotCount];
		int[] alternativeOwners = new int[slotCount];
		int[] reverseHeads = new int[stateIds.size()];
		int[] reverseNext = new int[edgeCount];
		int[] reverseSlots = new int[edgeCount];
		int[] lastSlot = new int[stateIds.size()];
		int[] liveCounts = new int[stateIds.size()];
		java.util.Arrays.fill(reverseHeads, -1);
		java.util.Arrays.fill(lastSlot, -1);
		IdentityHashMap<SelectedCandidateProof,Integer> ownerAlternatives = null;
		int retainedOwnerAlternativeWidth = 0;
		int owner = 0, slot = 0, edge = 0;
		for(List<SelectedCandidateProof> alternatives : graph.values()) {
			int[] dependencyOrdinals = null;
			int[] denseSuccessors = null;
			if(alternatives instanceof DefaultAlternativeList defaults) {
				dependencyOrdinals = defaults.pruningDependencyOrdinals();
				if(dependencyOrdinals != null) {
					List<CandidateProofState> uniqueSuccessors = defaults.traversalSchedule(null).uniqueSuccessors;
					try {
						denseSuccessors = new int[uniqueSuccessors.size()];
						for(int successor = 0; successor < uniqueSuccessors.size(); successor++)
							denseSuccessors[successor] = stateIds.get(uniqueSuccessors.get(successor));
					}
					catch(OutOfMemoryError optionalDenseIndexAllocationFailure) {
						dependencyOrdinals = null;
						denseSuccessors = null;
					}
					if(denseSuccessors != null && metrics != null) {
						metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.PRUNE_DEFAULT_ORDINAL_EDGES,
							dependencyOrdinals.length);
						metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.PRUNE_DEFAULT_DENSE_RESOLUTIONS,
							denseSuccessors.length);
					}
				}
			}
			int ownerDependency = 0;
			int ownerWidth = alternatives.size();
			liveCounts[owner] = ownerWidth;
			if(ownerWidth > 1) {
				// IdentityHashMap.clear scans its retained backing array. Avoid paying for a
				// prior wide owner on the overwhelmingly common empty/singleton rows, and
				// discard a grossly oversized table before the next duplicate-bearing row.
				int reusableWidth = ownerWidth > Integer.MAX_VALUE / 4
					? Integer.MAX_VALUE : Math.max(64, 4 * ownerWidth);
				if(ownerAlternatives == null || retainedOwnerAlternativeWidth > reusableWidth)
					ownerAlternatives = new IdentityHashMap<>(ownerWidth);
				else
					ownerAlternatives.clear();
				retainedOwnerAlternativeWidth = ownerWidth;
			}
			for(int alternativeIndex = 0; alternativeIndex < alternatives.size(); alternativeIndex++) {
				SelectedCandidateProof alternative = alternatives.get(alternativeIndex);
				Integer previousSlot = ownerWidth == 1 ? null
					: ownerAlternatives.putIfAbsent(alternative, slot);
				int removalId = previousSlot == null ? slot : previousSlot;
				slotRemovalIds[slot] = removalId;
				alternativeOwners[removalId] = owner;
				List<CandidateProofDependency> dependencies = alternative.dependencies;
				for(int dependencyIndex = 0; dependencyIndex < dependencies.size(); dependencyIndex++) {
					int dependency = denseSuccessors == null
						? stateIds.get(dependencies.get(dependencyIndex).state())
						: denseSuccessors[dependencyOrdinals[ownerDependency]];
					ownerDependency++;
					// The legacy reverse index deduplicates dependencies per original list
					// slot, not per shared alternative object or per owner.
					if(lastSlot[dependency] == slot)
						continue;
					lastSlot[dependency] = slot;
					reverseSlots[edge] = slot;
					reverseNext[edge] = reverseHeads[dependency];
					reverseHeads[dependency] = edge++;
				}
				slot++;
			}
			owner++;
		}
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
				int removalId = slotRemovalIds[reverseSlots[edge]];
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
		owner = 0;
		for(var entry : graph.entrySet()) {
			List<SelectedCandidateProof> alternatives = entry.getValue();
			// Every first removal decrements this owner, even for shared object
			// slots. An unchanged count proves that the original list survives.
			if(liveCounts[owner++] == alternatives.size()) {
				if(metrics != null)
					metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.PRUNE_UNCHANGED_OWNER_SLOTS_SKIPPED,
						alternatives.size());
				slot += alternatives.size();
				continue;
			}
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
			DefaultTraversalSchedule schedule = alternatives instanceof DefaultAlternativeList defaults
				? defaults.schedule : null;
			if(schedule != null && schedule.filteredAlternatives == alternatives) {
				boolean allSuccessorsLive = true;
				for(CandidateProofState successor : schedule.uniqueSuccessors) {
					List<SelectedCandidateProof> child = graph.get(successor);
					if(child == null || child.isEmpty()) {
						allSuccessorsLive = false;
						break;
					}
				}
				if(allSuccessorsLive) {
					// DFS completed these children before this owner. Every original row
					// therefore survives; retain its exact order and authority without a row scan.
					if(metrics != null)
						metrics.recordDirectWork(
							SearchSpaceMetrics.DirectWork.PRUNE_UNCHANGED_OWNER_SLOTS_SKIPPED,
							alternatives.size());
					continue;
				}
			}
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

	private static Set<CandidateProofState> viableCandidateStates(
		Map<CandidateProofState,List<SelectedCandidateProof>> viable) {
		Set<CandidateProofState> states = new java.util.HashSet<>();
		viable.forEach((state, alternatives) -> {
			if(!alternatives.isEmpty())
				states.add(state);
		});
		return states;
	}

	private void enumerateImmediateSupports(List<List<CandidateRealizationInputBinding>> options,
		int ordinal, List<CandidateRealizationInputBinding> current,
		java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer) {
		if(ordinal != 0 || !current.isEmpty())
			throw new IllegalArgumentException("Immediate support enumeration must start at the root");
		Map<CompiledHopKey,List<Integer>> ownerAxes = new IdentityHashMap<>();
		for(int axis = 0; axis < options.size(); axis++) {
			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			for(CandidateRealizationInputBinding binding : options.get(axis))
				if(owners.add(binding.source().rule().parentOccurrence()))
					ownerAxes.computeIfAbsent(binding.source().rule().parentOccurrence(),
						ignored -> new ArrayList<>()).add(axis);
		}
		if(metrics != null)
			metrics.recordSupportMrvProduct();
		if(ownerAxes.values().stream().noneMatch(axes -> axes.size() > 1)) {
			// Owner-disjoint domains never narrow under another input choice. Their
			// MRV order is constant: avoid rebuilding a selected-owner map and
			// rescanning every domain at each prefix of this common product.
			List<Integer> order = java.util.stream.IntStream.range(0, options.size()).boxed()
				.sorted(java.util.Comparator.comparingInt(axis -> options.get(axis).size())).toList();
			enumerateIndependentImmediateSupports(options, order,
				new CandidateRealizationInputBinding[options.size()], 0, consumer);
			return;
		}
		enumerateImmediateSupportsMrv(new ArrayList<>(options),
			new CandidateRealizationInputBinding[options.size()], 0,
			ownerAxes, new IdentityHashMap<>(), consumer);
	}

	private void enumerateIndependentImmediateSupports(List<List<CandidateRealizationInputBinding>> domains,
		List<Integer> order, CandidateRealizationInputBinding[] current, int depth,
		java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer) {
		if(metrics != null)
			metrics.recordSupportPrefix(depth);
		if(depth == domains.size()) {
			if(metrics != null)
				metrics.recordSupportLeaf();
			consumer.accept(List.of(current.clone()));
			return;
		}
		int axis = order.get(depth);
		for(CandidateRealizationInputBinding binding : domains.get(axis)) {
			current[axis] = binding;
			enumerateIndependentImmediateSupports(domains, order, current, depth + 1, consumer);
		}
		current[axis] = null;
	}

	private void enumerateImmediateSupportsMrv(List<List<CandidateRealizationInputBinding>> domains,
		CandidateRealizationInputBinding[] current, int depth, Map<CompiledHopKey,List<Integer>> ownerAxes,
		Map<CompiledHopKey,CandidateRealizationReference> selected,
		java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer) {
		if(metrics != null)
			metrics.recordSupportPrefix(depth);
		if(depth == domains.size()) {
			if(metrics != null)
				metrics.recordSupportLeaf();
			// Search order is independent of the exact input-position binding order.
			consumer.accept(List.of(current.clone()));
			return;
		}
		int axis = -1;
		for(int candidate = 0; candidate < domains.size(); candidate++)
			if(current[candidate] == null && (axis < 0 || domains.get(candidate).size() < domains.get(axis).size()))
				axis = candidate;
		for(CandidateRealizationInputBinding binding : domains.get(axis)) {
			CompiledHopKey owner = binding.source().rule().parentOccurrence();
			boolean first = !selected.containsKey(owner);
			current[axis] = binding;
			List<List<CandidateRealizationInputBinding>> narrowed = domains;
			boolean viable = true;
			if(first) {
				selected.put(owner, binding.source());
				// Only incident axes can conflict. Narrow them once when this exact
				// owner is pinned, before expanding unrelated input choices.
				for(int other : ownerAxes.get(owner)) {
					if(current[other] != null)
						continue;
					List<CandidateRealizationInputBinding> remaining = null;
					List<CandidateRealizationInputBinding> domain = domains.get(other);
					for(int index = 0; index < domain.size(); index++) {
						CandidateRealizationInputBinding option = domain.get(index);
						boolean conflict = option.source().rule().parentOccurrence() == owner
							&& !binding.source().equals(option.source());
						if(metrics != null)
							metrics.recordSupportSourceCheck(conflict);
						if(conflict) {
							if(metrics != null)
								metrics.recordSupportConflictPrefix();
							if(remaining == null)
								remaining = new ArrayList<>(domain.subList(0, index));
						}
						else if(remaining != null)
							remaining.add(option);
					}
					if(remaining != null) {
						if(remaining.isEmpty()) {
							viable = false;
							break;
						}
						if(narrowed == domains)
							narrowed = new ArrayList<>(domains);
						narrowed.set(other, remaining);
					}
				}
			}
			if(viable)
				enumerateImmediateSupportsMrv(narrowed, current, depth + 1, ownerAxes, selected, consumer);
			current[axis] = null;
			if(first)
				selected.remove(owner);
		}
	}

	private void buildCandidateProofGraph(CandidateProofState state, CandidateProofState root,
		GenerationRoot generation,
		Map<CandidateProofState,List<SelectedCandidateProof>> graph, CandidateProofTraversal traversal,
		FixedCandidateBoundary fixed, long[] graphWork) {
		if(traversal.active.contains(state)) {
			traversal.cycleDetected = true;
			return;
		}
		if(graph.containsKey(state))
			return;
		boolean generatedRoot = generation != null && state.key() == root.key()
			&& Objects.equals(state.realization(), root.realization());
		// A proposed-root recurrence reads the same primitive recipe, not the
		// root's published clauses. Only a non-recipe root occurrence or an
		// explicit metadata receipt below requires full published-root history.
		if(generation != null && state.key() == root.key() && !generatedRoot)
			traversal.generatedRootPublishedHistoryObserved = true;
		AcyclicComponentSummary shared = generatedRoot || state.axisGate() != null
			? null : reusableAcyclicComponent(state, fixed.owner());
		if(shared != null) {
			// Failed summaries are retained for exact negative-result reuse too.
			// They remain dead seeds; hiding them would invalidate the DAG shortcut.
			if(shared.supportedAlternatives.isEmpty())
				traversal.emptyFilteredStates++;
			graph.put(state, shared.supportedAlternatives);
			traversal.reusedComponents.put(state, shared);
			if(metrics != null) {
				metrics.recordDirectWork(
					SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSE_HITS);
				metrics.recordDirectWork(
					SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSED_ROWS,
					shared.supportedAlternatives.size());
			}
			if(graphWork != null)
				graphWork[0] += shared.supportedAlternatives.size();
			traversal.completionOrder.add(state);
			return;
		}
		traversal.active.add(state);
		try {
			List<SelectedCandidateProof> alternatives = state.axisGate() == null
				? candidateProofAlternatives(
					state.key(), state.realization(), state.realizationHandle(), state.witness(),
					state.templateRoot(), fixed,
					generatedRoot ? generation : null, traversal)
				: state.axisGate().alternatives;
			DefaultTraversalSchedule defaultSchedule = alternatives instanceof DefaultAlternativeList defaults
				? defaults.traversalSchedule(metrics) : null;
			if(generation != null && traversal.hiddenOwnerReadsByState
				.getOrDefault(state, Set.of()).contains(root.key()))
				traversal.generatedRootPublishedHistoryObserved = true;
			// An empty non-source row supplies nothing. Remove it before dependency
			// pruning so its consumers cannot survive on a fictitious leaf. This also
			// covers generated and provisional template rows.
			if(defaultSchedule != null)
				alternatives = defaultSchedule.filteredAlternatives;
			else if(alternatives.stream().anyMatch(alternative -> !alternative.directGround
				&& alternative.dependencies.isEmpty()))
				alternatives = alternatives.stream().filter(alternative -> alternative.directGround
					|| !alternative.dependencies.isEmpty()).toList();
			if(alternatives.isEmpty())
				traversal.emptyFilteredStates++;
			graph.put(state, alternatives);
			if(graphWork != null) {
				graphWork[0] += alternatives.size();
				if(defaultSchedule != null)
					graphWork[1] += defaultSchedule.rawDependencyCount;
				else
					for(SelectedCandidateProof alternative : alternatives)
						graphWork[1] += alternative.dependencies.size();
			}
			if(defaultSchedule != null)
				for(CandidateProofState successor : defaultSchedule.uniqueSuccessors)
					buildCandidateProofGraph(successor, root, generation, graph, traversal,
						fixed, graphWork);
			else
				for(SelectedCandidateProof alternative : alternatives)
					for(CandidateProofDependency dependency : alternative.dependencies)
						buildCandidateProofGraph(dependency.state(), root, generation, graph, traversal,
							fixed, graphWork);
			traversal.completionOrder.add(state);
		}
		finally {
			traversal.active.remove(state);
		}
	}

	private AcyclicComponentSummary reusableAcyclicComponent(CandidateProofState state,
		CompiledHopKey fixedOccurrence) {
		AcyclicComponentSummary summary = acyclicComponentMemo.get(state);
		if(summary == null)
			return null;
		return summary.occurrences.contains(fixedOccurrence) ? null : summary;
	}

	private Map<CandidateProofState,AcyclicComponentFootprint> acyclicRootChildFootprints(
		CandidateProofState root, Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal) {
		Map<CandidateProofState,AcyclicComponentFootprint> footprints = new java.util.LinkedHashMap<>();
		for(SelectedCandidateProof alternative : graph.getOrDefault(root, List.of()))
			for(CandidateProofDependency dependency : alternative.dependencies)
				if(dependency.state().axisGate() == null && !dependency.state().equals(root))
					rememberAcyclicComponentFootprint(dependency.state(), graph, traversal, footprints);
		footprints.values().removeIf(Objects::isNull);
		return footprints;
	}

	/** A pinned root cannot affect a child outside its conservative occurrence SCC. */
	private Map<CandidateProofState,AcyclicComponentFootprint> rootIndependentChildFootprints(
		CandidateProofState root, Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal) {
		if(acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
			|| acyclicComponentMaxAlternatives == 0)
			return Map.of();
		PlacementDependencyComponents components = occurrenceComponents.components();
		var rootComponent = components.componentOf(root.key());
		Map<CandidateProofState,AcyclicComponentFootprint> footprints = new java.util.LinkedHashMap<>();
		for(SelectedCandidateProof alternative : graph.getOrDefault(root, List.of()))
			for(CandidateProofDependency dependency : alternative.dependencies) {
				CandidateProofState child = dependency.state();
				if(child.axisGate() == null && components.componentOf(child.key()) != rootComponent)
					rememberAcyclicComponentFootprint(child, graph, traversal, footprints);
			}
		footprints.values().removeIf(Objects::isNull);
		return footprints;
	}

	private void rememberAcyclicComponentFootprint(CandidateProofState child,
		Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal,
		Map<CandidateProofState,AcyclicComponentFootprint> footprints) {
		// The completed query graph is unchanged during collection. Null means this
		// boundary exceeds the existing budget, not that it needs another walk for
		// each duplicate root alternative. Remove these local negatives before admission.
		if(footprints.containsKey(child)) {
			if(metrics != null && footprints.get(child) == null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.COMPONENT_FOOTPRINT_NEGATIVE_REPROBES_AVOIDED);
			return;
		}
		footprints.put(child, acyclicComponentFootprint(child, graph, traversal));
	}

	private AcyclicComponentFootprint acyclicComponentFootprint(CandidateProofState child,
		Map<CandidateProofState,List<SelectedCandidateProof>> graph,
		CandidateProofTraversal traversal) {
		if(metrics != null)
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.COMPONENT_FOOTPRINT_REQUESTS);
		AcyclicComponentSummary boundary = traversal.reusedComponents.get(child);
		if(boundary != null) {
			// The original traversal stops at this boundary without adding visible or
			// hidden graph rows. Its summary already owns the complete immutable identity
			// footprint; sharing it avoids copying every transitive owner again.
			long retainedStates = Math.max(boundary.retainedStates, boundary.occurrences.size());
			if(retainedStates > acyclicComponentMaxStates)
				return null;
			if(metrics != null) {
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.COMPONENT_FOOTPRINT_REUSED_BOUNDARIES);
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.COMPONENT_FOOTPRINT_REUSED_OWNERS,
					boundary.occurrences.size());
			}
			return new AcyclicComponentFootprint(boundary.occurrences, retainedStates);
		}
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
			occurrences.addAll(traversal.hiddenOwnerReadsByState.getOrDefault(state, Set.of()));
			retainedStates++;
			if(retainedStates > acyclicComponentMaxStates)
				return null;
			List<SelectedCandidateProof> alternatives = graph.getOrDefault(state, List.of());
			if(alternatives instanceof DefaultAlternativeList defaults) {
				// Graph construction already prepared this exact immutable schedule.
				// Full-state, first-encounter deduplication removes only repeated queue
				// entries; ordinary overlay and filtered lists keep the original walk.
				DefaultTraversalSchedule schedule = defaults.traversalSchedule(null);
				if(metrics != null) {
					metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.COMPONENT_FOOTPRINT_SCHEDULES);
					metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.COMPONENT_FOOTPRINT_RAW_EDGES,
						schedule.rawDependencyCount);
					metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.COMPONENT_FOOTPRINT_UNIQUE_EDGES,
						schedule.uniqueSuccessors.size());
				}
				for(CandidateProofState successor : schedule.uniqueSuccessors)
					pending.addLast(successor);
			}
			else
				for(SelectedCandidateProof alternative : alternatives)
					for(CandidateProofDependency dependency : alternative.dependencies)
						pending.addLast(dependency.state());
		}
		retainedStates = Math.max(retainedStates, occurrences.size());
		return retainedStates > acyclicComponentMaxStates ? null
			: new AcyclicComponentFootprint(Collections.unmodifiableSet(occurrences), retainedStates);
	}

	private void cacheAcyclicRootChildren(
		Map<CandidateProofState,AcyclicComponentFootprint> childFootprints,
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		Set<CandidateProofState> supported, CompiledHopKey generatedRoot,
		CandidateProofTraversal traversal) {
		if(acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
			|| acyclicComponentMaxAlternatives == 0)
			return;
		for(var entry : childFootprints.entrySet())
			if(generatedRoot == null || !entry.getValue().occurrences.contains(generatedRoot)) {
				CandidateProofState child = entry.getKey();
				AcyclicComponentFootprint footprint = entry.getValue();
				AcyclicComponentSummary reused = traversal.reusedComponents.get(child);
				// This exact grounded boundary already has canonical, distinct rows.
				// Preserve admission/eviction order, including after an intervening
				// eviction, but do not reconstruct an unchanged summary or footprint.
				if(reused != null && viable.get(child) == reused.supportedAlternatives
					&& supported.contains(child) == !reused.supportedAlternatives.isEmpty()
					&& footprint.occurrences == reused.occurrences
					&& footprint.retainedStates == reused.retainedStates)
					cacheAcyclicSummary(child, reused);
				else
					cacheAcyclicComponent(child, footprint, viable, supported);
			}
	}

	private void cacheAcyclicComponent(CandidateProofState child, AcyclicComponentFootprint footprint,
		Map<CandidateProofState,List<SelectedCandidateProof>> viable,
		Set<CandidateProofState> supported) {
		long retainedStates = footprint.retainedStates;
		if(retainedStates > acyclicComponentMaxStates)
			return;
		List<SelectedCandidateProof> supportedAlternatives = new ArrayList<>();
		Set<AcyclicSummaryRowKey> distinctRows = new java.util.HashSet<>();
		if(supported.contains(child))
			for(SelectedCandidateProof alternative : viable.getOrDefault(child, List.of()))
				if((alternative.directGround || !alternative.dependencies.isEmpty())
					&& alternative.dependencies.stream().allMatch(dependency ->
						supported.contains(dependency.state()))) {
					if(metrics != null)
						metrics.recordDirectWork(
							SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_SUPPORTED_ROWS_EXAMINED);
					if(!distinctRows.add(AcyclicSummaryRowKey.of(alternative))) {
						if(metrics != null)
							metrics.recordDirectWork(
								SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_DUPLICATE_ROWS_COLLAPSED);
						continue;
					}
					if(supportedAlternatives.size() >= acyclicComponentMaxAlternatives) {
						if(metrics != null)
							metrics.recordDirectWork(
								SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_DISTINCT_BUDGET_BYPASSES);
						return; // Oversized boundary: exact recomputation is the safe fallback.
					}
					supportedAlternatives.add(new SelectedCandidateProof(alternative.realization,
						List.of(), true, alternative.witness));
				}
		AcyclicComponentSummary summary = new AcyclicComponentSummary(supportedAlternatives,
			footprint.occurrences, retainedStates);
		cacheAcyclicSummary(child, summary);
	}

	private void cacheAcyclicSummary(CandidateProofState child, AcyclicComponentSummary summary) {
		if(acyclicComponentMaxEntries == 0 || acyclicComponentMaxStates == 0
			|| acyclicComponentMaxAlternatives == 0
			|| summary.retainedStates > acyclicComponentMaxStates
			|| summary.supportedAlternatives.size() > acyclicComponentMaxAlternatives)
			return;
		long retainedStates = summary.retainedStates;
		List<SelectedCandidateProof> supportedAlternatives = summary.supportedAlternatives;
		AcyclicComponentSummary prior = acyclicComponentMemo.remove(child);
		if(prior != null) {
			acyclicComponentRetainedStates -= prior.retainedStates;
			acyclicComponentRetainedAlternatives -= prior.supportedAlternatives.size();
		}
		while(!acyclicComponentMemo.isEmpty()
			&& (acyclicComponentMemo.size() >= acyclicComponentMaxEntries
				|| acyclicComponentRetainedStates + retainedStates > acyclicComponentMaxStates
				|| acyclicComponentRetainedAlternatives + supportedAlternatives.size()
					> acyclicComponentMaxAlternatives)) {
			var oldest = acyclicComponentMemo.entrySet().iterator().next();
			acyclicComponentRetainedStates -= oldest.getValue().retainedStates;
			acyclicComponentRetainedAlternatives -= oldest.getValue().supportedAlternatives.size();
			acyclicComponentMemo.remove(oldest.getKey());
		}
		acyclicComponentMemo.put(child, summary);
		acyclicComponentRetainedStates += retainedStates;
		acyclicComponentRetainedAlternatives += supportedAlternatives.size();
		if(metrics != null)
			metrics.recordDirectWork(
				SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_ADMITTED_ROWS,
				supportedAlternatives.size());
	}

	private List<SelectedCandidateProof> candidateProofAlternatives(CompiledHopKey key,
		CandidateRealizationReference pinned, int pinnedHandle, NativePoolWitness witness,
		boolean allowPinnedTemplate, FixedCandidateBoundary fixed, GenerationRoot generation) {
		return candidateProofAlternatives(key, pinned, pinnedHandle, witness,
			allowPinnedTemplate, fixed, generation, new CandidateProofTraversal());
	}

	private List<SelectedCandidateProof> candidateProofAlternatives(CompiledHopKey key,
		CandidateRealizationReference pinned, int pinnedHandle, NativePoolWitness witness,
		boolean allowPinnedTemplate, FixedCandidateBoundary fixed, GenerationRoot generation,
		CandidateProofTraversal traversal) {
		if(metrics != null)
			metrics.recordTopologyOverlayEvaluation();
		if(generation != null)
			return generatedRootAlternative(key, pinned, witness, fixed, generation);
		NativeFactoredProofAlternatives factored = !hasNativeRelationOwner(key) ? null
			: pinned == null ? nativeUnpinnedFactoredProofAlternatives(key, witness, fixed)
				: nativeFactoredProofAlternatives(key, pinned, witness, fixed);
		if(factored != null) {
			if(!factored.metadataOwnerReads().isEmpty())
				traversal.hiddenOwnerReadsByState.put(
					new CandidateProofState(key, pinned, pinnedHandle, witness,
						allowPinnedTemplate), factored.metadataOwnerReads());
			return factored.alternatives;
		}
		CandidateTopology topology = candidateTopologyForPin(key, pinned, pinnedHandle, witness);
		if(!topology.metadataOwnerReads.isEmpty())
			traversal.hiddenOwnerReadsByState.put(
				new CandidateProofState(key, pinned, pinnedHandle, witness, allowPinnedTemplate),
				topology.metadataOwnerReads);
		if(!topology.eligible)
			return List.of();
		// A topology owns immutable default edges, not query support results.
		// Unrelated fixed owners cannot change any immediate dependency, so reuse
		// the enclosing canonical list too. Synthetic anchors and empty/template
		// cases intentionally remain on their existing query-local paths below.
		if((pinned != null || !topology.nodeDirectGround) && !topology.hasFixedDependency(fixed.owner())) {
			List<SelectedCandidateProof> defaults = pinned == null ? topology.defaultAlternatives
				: topology.defaultAlternativesByHandle.getOrDefault(pinnedHandle, List.of());
			if(!defaults.isEmpty())
				return defaults;
		}
		FixedBoundaryOverlayKey overlayKey = fixedBoundaryOverlayKey(key, pinned,
			pinnedHandle, witness, allowPinnedTemplate, fixed);
		if(metrics != null && overlayKey != null)
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.FIXED_BOUNDARY_OVERLAY_LOOKUPS);
		DefaultAlternativeList cachedOverlay = overlayKey == null ? null
			: fixedBoundaryOverlays.get(overlayKey);
		if(cachedOverlay != null) {
			fixedBoundaryOverlayHits++;
			if(metrics != null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.FIXED_BOUNDARY_OVERLAY_HITS);
			return cachedOverlay;
		}
		List<SelectedCandidateProof> alternatives = new ArrayList<>();
		List<CandidateTopologyRow> overlayRows = pinned == null ? topology.rows
			: topology.rowsByHandle.getOrDefault(pinnedHandle, List.of());
		// The topology rows are already canonical. The old final sort only moved
		// the synthetic null realization to the front, so publish it first instead.
		if(pinned == null && topology.nodeDirectGround)
			alternatives.add(new SelectedCandidateProof(null, List.of(), true, witness));
		// A staging template is a proof obligation, not publication authority. Keep
		// its exact input dependencies in loop recurrences; only supported rows
		// with executable bindings are materialized by the caller.
		// Topology construction already deduplicates defaults by the equivalent edge key.
		// The first query-local overlay creates the only collision opportunity, so seed
		// every preceding default then check that row and all later defaults/overlays.
		Set<ContinuityEdgeKey> seen = null;
		fixedBoundaryOverlayRowsVisited += overlayRows.size();
		if(metrics != null)
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.FIXED_BOUNDARY_OVERLAY_ROWS_VISITED,
				overlayRows.size());
		for(int rowIndex = 0; rowIndex < overlayRows.size(); rowIndex++) {
			CandidateTopologyRow row = overlayRows.get(rowIndex);
			boolean queryOverlay = false;
			for(CandidateDependencySkeleton dependency : row.dependencies)
				if(fixed.matches(dependency.key)) {
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
					overlayDependencies(row.dependencies, row.defaultAlternative.dependencies,
						fixed);
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
					fact, templateClause, hop, witness, fixed);
				if(dependencies != null)
					alternatives.add(new SelectedCandidateProof(pinned, dependencies,
						false, witness));
			}
		if(overlayKey != null && !overlayRows.isEmpty() && !alternatives.isEmpty()) {
			DefaultAlternativeList overlay = new DefaultAlternativeList(alternatives);
			cacheFixedBoundaryOverlay(overlayKey, overlay);
			return overlay;
		}
		return List.copyOf(alternatives);
	}

	private FixedBoundaryOverlayKey fixedBoundaryOverlayKey(CompiledHopKey owner,
		CandidateRealizationReference pinned, int pinnedHandle, NativePoolWitness witness,
		boolean templateRoot, FixedCandidateBoundary fixed) {
		// Negative handles are resolver-local structural fallbacks rather than exact
		// analysis authority. Empty/unresolved pins retain the legacy overlay path.
		if(fixed.handle() <= 0 || pinnedHandle < 0 || (pinned == null) != (pinnedHandle == 0))
			return null;
		return new FixedBoundaryOverlayKey(owner, pinnedHandle, witness, templateRoot,
			fixed.owner(), fixed.handle());
	}

	private void cacheFixedBoundaryOverlay(FixedBoundaryOverlayKey key,
		DefaultAlternativeList overlay) {
		long rows = overlay.size();
		if(topologyMaxEntries == 0 || topologyMaxRows == 0 || rows > topologyMaxRows)
			return;
		DefaultAlternativeList prior = fixedBoundaryOverlays.remove(key);
		if(prior != null)
			fixedBoundaryOverlayRetainedRows -= prior.size();
		evictOptionalOverlaysForTopologyBudget(1, rows);
		// Optional overlays share the original topology budget, rather than receiving
		// a second copy of it. Never evict topology just to retain an overlay.
		if(!hasTopologyBudgetFor(1, rows))
			return;
		fixedBoundaryOverlays.put(key, overlay);
		fixedBoundaryOverlayRetainedRows += rows;
		fixedBoundaryOverlayBuilds++;
		if(metrics != null)
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.FIXED_BOUNDARY_OVERLAY_ADMISSIONS);
	}

	private boolean hasTopologyBudgetFor(int additionalEntries, long additionalRows) {
		return (long)candidateTopologies.size() + fixedBoundaryOverlays.size() + additionalEntries
				<= topologyMaxEntries
			&& topologyRetainedRows + topologyRetainedOwnerReads
				+ fixedBoundaryOverlayRetainedRows + additionalRows <= topologyMaxRows;
	}

	private void evictOptionalOverlaysForTopologyBudget(int additionalEntries, long additionalRows) {
		while(!fixedBoundaryOverlays.isEmpty()
			&& !hasTopologyBudgetFor(additionalEntries, additionalRows)) {
			var oldest = fixedBoundaryOverlays.entrySet().iterator().next();
			fixedBoundaryOverlayRetainedRows -= oldest.getValue().size();
			fixedBoundaryOverlays.remove(oldest.getKey());
		}
	}

	/** Snapshot-local representation check; it carries no source or proof authority. */
	private boolean hasNativeRelationOwner(CompiledHopKey owner) {
		return nativeRelationInventory(owner).hasNativeRelation();
	}

	private NativeRelationInventory nativeRelationInventory(CompiledHopKey owner) {
		List<CandidateRuleFact> facts = candidateFactsByKey.get(owner);
		if(facts == null)
			return NativeRelationInventory.EMPTY;
		NativeRelationInventory known = nativeRelationOwners.get(owner);
		if(known != null)
			return known;
		NativeRelationInventory inventory = new NativeRelationInventory(facts);
		nativeRelationOwners.put(owner, inventory);
		return inventory;
	}

	/**
	 * Indexes only explicit immutable inventory, never logical support members or
	 * query eligibility. Encounter-ordered buckets retain duplicate authority for
	 * the original query-time checks. Each resolver revision owns a fresh index.
	 */
	private static final class NativeRelationInventory {
		private static final NativeRelationInventory EMPTY = new NativeRelationInventory(List.of());
		private final List<CandidateRuleFact> facts;
		private Boolean nativeRelation;
		private Map<CandidateRuleKey,List<CandidateRuleFact>> factsByRule;
		private Map<CandidateEmissionFact,Map<PlacementRealizationKey,List<CandidateEmissionRealization>>>
			realizationsByEmission;

		private NativeRelationInventory(List<CandidateRuleFact> facts) {
			this.facts = facts;
			if(facts.isEmpty()) {
				nativeRelation = false;
				factsByRule = Map.of();
				realizationsByEmission = Map.of();
			}
		}

		private boolean hasNativeRelation() {
			if(nativeRelation == null)
				nativeRelation = facts.stream().flatMap(fact -> fact.allowedEmissionFacts().stream())
					.flatMap(emission -> emission.realizations().stream()).anyMatch(realization ->
						realization.supportClauses() instanceof NativeContinuitySupportClauses);
			return nativeRelation;
		}

		private List<CandidateRuleFact> matchingFacts(CandidateRuleKey rule) {
			index();
			return factsByRule.getOrDefault(rule, List.of());
		}

		private List<CandidateEmissionRealization> matchingRealizations(
			CandidateEmissionFact emission, PlacementRealizationKey realization) {
			return realizationsByEmission.get(emission).getOrDefault(realization, List.of());
		}

		private void index() {
			if(factsByRule != null)
				return;
			Map<CandidateRuleKey,List<CandidateRuleFact>> byRule = new HashMap<>();
			Map<CandidateEmissionFact,Map<PlacementRealizationKey,List<CandidateEmissionRealization>>>
				byEmission = new IdentityHashMap<>();
			boolean foundNative = false;
			for(CandidateRuleFact fact : facts) {
				byRule.computeIfAbsent(fact.key(), ignored -> new ArrayList<>()).add(fact);
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
					if(byEmission.containsKey(emission))
						continue;
					Map<PlacementRealizationKey,List<CandidateEmissionRealization>> byRealization = new HashMap<>();
					for(CandidateEmissionRealization realization : emission.realizations()) {
						byRealization.computeIfAbsent(realization.key(), ignored -> new ArrayList<>()).add(realization);
						foundNative |= realization.supportClauses() instanceof NativeContinuitySupportClauses;
					}
					byEmission.put(emission, byRealization);
				}
			}
			// These private buckets are never mutated after publication. Their size is
			// bounded by explicit facts/emissions/realizations, not requested pin count.
			realizationsByEmission = byEmission;
			factsByRule = byRule;
			nativeRelation = foundNative;
		}
	}

	private List<SelectedCandidateProof> generatedRootAlternative(CompiledHopKey key,
		CandidateRealizationReference proposed, NativePoolWitness witness,
		FixedCandidateBoundary fixed, GenerationRoot generation) {
		CandidateRuleFact fact = generation.trustedBase();
		CandidateEmissionFact emission = generation.baseEmission();
		if(metrics != null) {
			// The prospective fact/emission are still examined even when a generated
			// support key no longer expands the unrelated published root topology.
			metrics.recordProofRowExamined();
			metrics.recordProofRowExamined();
		}
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
			fact, empty, hop, witness, fixed);
		return dependencies == null ? List.of()
			: List.of(new SelectedCandidateProof(proposed, dependencies, false, witness));
	}

	private boolean declaresExactRealization(CompiledHopKey key,
		CandidateRealizationReference pinned) {
		NativeRelationInventory inventory = nativeRelationInventory(key);
		for(CandidateRuleFact fact : inventory.matchingFacts(pinned.rule()))
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				if(!inventory.matchingRealizations(emission, pinned.realization()).isEmpty())
					return true;
		return false;
	}

	private boolean hasNativeContinuityRelation(CompiledHopKey key,
		CandidateRealizationReference pinned) {
		NativeRelationInventory inventory = nativeRelationInventory(key);
		for(CandidateRuleFact fact : inventory.matchingFacts(pinned.rule())) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| fact.key().parentOccurrence() != key || !fact.key().equals(pinned.rule()))
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : inventory.matchingRealizations(
					emission, pinned.realization())) {
					if(realization.supportClauses() instanceof NativeContinuitySupportClauses)
						return true;
				}
		}
		return false;
	}

	/**
	 * Converts one exact rectangular native relation into a query-local proof circuit:
	 * the consumer is an AND of invariant dependencies and one gate per input axis;
	 * every gate is an OR of exact source realizations. The gates remain inside this
	 * proof traversal and never become candidate or receipt authority.
	 */
	private NativeFactoredProofAlternatives nativeFactoredProofAlternatives(
		CompiledHopKey key, CandidateRealizationReference pinned,
		NativePoolWitness witness, FixedCandidateBoundary fixed) {
		if(metrics != null)
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_PINNED_REQUESTS);
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		if(node == null || hop == null || incompleteSources.contains(key)
			|| node.legalAlternatives().stream().noneMatch(state ->
				state.output() == FederatedOutput.FOUT && state.fType() == witness.fType)) {
			if(metrics != null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_PINNED_REJECT_CONTEXT);
			return null;
		}
		CandidateRuleFact matchedFact = null;
		CandidateEmissionRealization matchedRealization = null;
		NativeContinuitySupportClauses matchedRelation = null;
		NativeRelationInventory inventory = nativeRelationInventory(key);
		for(CandidateRuleFact fact : inventory.matchingFacts(pinned.rule())) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| fact.key().parentOccurrence() != key || !fact.key().equals(pinned.rule())
				|| isBroadcastRowProvablyUnselectable(fact)
				|| !operationPreservesWitness(hop, witness, fact))
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != witness.fType || emission.executionFType() != witness.fType
					|| emission.derivedFoutAction() != null)
					continue;
				for(CandidateEmissionRealization realization : inventory.matchingRealizations(
					emission, pinned.realization())) {
					if(matchedRealization != null) {
						if(metrics != null)
							metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_PINNED_REJECT_DUPLICATE);
						return null;
					}
					if(!(realization.supportClauses() instanceof NativeContinuitySupportClauses relation)) {
						if(metrics != null) {
							metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_PINNED_REJECT_ORDINARY);
							// Observe the existing cache without populating or changing its eviction order.
							CandidateTopologyKey resident = residentTopologyKey(key, witness);
							metrics.recordDirectWork(resident != null && candidateTopologies.containsKey(resident)
								? SearchSpaceMetrics.DirectWork.NATIVE_PINNED_ORDINARY_RESIDENT_TOPOLOGY
								: SearchSpaceMetrics.DirectWork.NATIVE_PINNED_ORDINARY_COLD_TOPOLOGY);
						}
						return null;
					}
					if(!nativeContinuityProductLayout(realization)) {
						if(metrics != null)
							metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_PINNED_REJECT_LAYOUT);
						return null;
					}
					matchedFact = fact;
					matchedRealization = realization;
					matchedRelation = relation;
				}
			}
		}
		if(matchedRealization == null || matchedRelation == null || matchedRelation.isEmpty()) {
			if(metrics != null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_PINNED_REJECT_MISSING);
			return null;
		}
		NativeFactoredProofAlternatives resolved = nativeFactoredProofAlternativesResolved(key, pinned, witness, fixed,
			node, hop, matchedFact, matchedRealization, matchedRelation);
		if(metrics != null)
			metrics.recordDirectWork(resolved == null ? SearchSpaceMetrics.DirectWork.NATIVE_PINNED_REJECT_PRODUCT
				: SearchSpaceMetrics.DirectWork.NATIVE_PINNED_ACCEPTED);
		return resolved;
	}

	/** Builds the circuit for an exact inventory row already validated by its caller. */
	private NativeFactoredProofAlternatives nativeFactoredProofAlternativesResolved(
		CompiledHopKey key, CandidateRealizationReference pinned,
		NativePoolWitness witness, FixedCandidateBoundary fixed,
		Node node, Hop hop, CandidateRuleFact matchedFact,
		CandidateEmissionRealization matchedRealization,
		NativeContinuitySupportClauses matchedRelation) {
		CandidateRealizationReference exact =
			CandidateRealizationReference.of(matchedFact.key(), matchedRealization);
		if(matchedFact.key().parentOccurrence() != key
			|| exact.rule().parentOccurrence() != key || !exact.equals(pinned)
			|| matchedRealization.supportClauses() != matchedRelation
			|| matchedRelation.isEmpty())
			return null;
		List<List<CandidateRealizationInputBinding>> axes = matchedRelation.commonAxes();
		if(axes.isEmpty())
			return null;
		// One authoritative member supplies the existing rule-derived dependency
		// skeleton. NativeContinuitySupportClauses differs across members only in its
		// direct bindings/proof key, and candidateDependencySkeletons reads only those
		// bindings when projecting exact source pins.
		CandidateRealizationSupportClause representative = matchedRelation.get(0);
		List<CandidateDependencySkeleton> representativeSkeletons =
			candidateDependencySkeletons(matchedFact, representative, hop, witness, true);
		if(representativeSkeletons == null)
			return null;
		Map<CompiledHopKey,Integer> ownerAxes = new IdentityHashMap<>();
		Map<CompiledHopKey,CandidateRealizationReference> representativeSources =
			new IdentityHashMap<>();
		for(int axis = 0; axis < axes.size(); axis++) {
			List<CandidateRealizationInputBinding> options = axes.get(axis);
			if(options.isEmpty())
				return null;
			CompiledHopKey owner = options.get(0).source().rule().parentOccurrence();
			// Legacy query overlays replace every clause pin at a fixed owner, rather
			// than filtering the published axis by that pin. Keep that exact path until
			// the gate's returned references and dependencies can both model the overlay.
			if(fixed.matches(owner))
				return null;
			if(ownerAxes.put(owner, axis) != null)
				return null;
		}
		for(CandidateRealizationInputBinding binding : representative.inputBindings()) {
			CompiledHopKey owner = binding.source().rule().parentOccurrence();
			if(ownerAxes.containsKey(owner))
				representativeSources.put(owner, binding.source());
		}
		if(representativeSources.size() != ownerAxes.size())
			return null;

		List<CandidateDependencySkeleton> invariant = new ArrayList<>();
		List<List<CandidateDependencySkeleton>> affected = new ArrayList<>(axes.size());
		for(int axis = 0; axis < axes.size(); axis++)
			affected.add(new ArrayList<>());
		for(CandidateDependencySkeleton skeleton : representativeSkeletons) {
			Integer axis = ownerAxes.get(skeleton.key());
			if(axis == null) {
				invariant.add(skeleton);
				continue;
			}
			CandidateRealizationReference representativeSource =
				representativeSources.get(skeleton.key());
			if(skeleton.clausePinned() == null
				|| !skeleton.clausePinned().equals(representativeSource)
				|| skeleton.clausePinned().rule().parentOccurrence() != skeleton.key())
				return null;
			affected.get(axis).add(skeleton);
		}
		if(affected.stream().anyMatch(List::isEmpty))
			return null;
		for(int axis = 0; axis < axes.size(); axis++) {
			int position = axes.get(axis).get(0).inputPosition();
			boolean exposesPosition = false;
			for(CandidateDependencySkeleton skeleton : affected.get(axis)) {
				if(skeleton.inputPosition() < 0)
					continue;
				// A single gate exposes one immediate binding position. A repeated
				// producer can require several; the legacy path preserves all of them.
				if(skeleton.inputPosition() != position)
					return null;
				exposesPosition = true;
			}
			if(!exposesPosition)
				return null;
		}

		// Mirror candidateTopology's exact grounding precedence. A durable
		// realization owns its anchor independently of optional clause metadata;
		// native lineage can be grounded only by the relation witness.
		boolean directGround = matchedRealization.key().layoutKind()
				== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
				&& witness.matches(nativeWitness(matchedRealization.anchor()), true)
			|| matchedRelation.clauseWitness() != null
				&& witness.matches(nativeWitness(matchedRelation.clauseWitness()),
					matchedRelation.clauseLayoutExact());
		List<SelectedCandidateProof> roots = new ArrayList<>();
		int firstPivot = matchedRelation.conditionalComplement() ? 0 : -1;
		int lastPivot = matchedRelation.conditionalComplement() ? axes.size() - 1 : -1;
		for(int pivot = firstPivot; pivot <= lastPivot; pivot++) {
			List<CandidateProofDependency> consumerDependencies =
				new ArrayList<>(invariant.size() + axes.size());
			consumerDependencies.addAll(overlayDependencies(invariant, fixed));
			boolean valid = true;
			for(int axis = 0; axis < axes.size(); axis++) {
				List<CandidateRealizationInputBinding> options = axes.get(axis);
				if(pivot >= 0) {
					int selectedAxis = axis;
					int selectedPivot = pivot;
					options = options.stream().filter(binding -> selectedAxis < selectedPivot
						? matchedRelation.isExcludedExactBinding(selectedAxis, binding)
						: selectedAxis == selectedPivot
							? !matchedRelation.isExcludedExactBinding(selectedAxis, binding)
							: true).toList();
				}
				if(options.isEmpty()) {
					valid = false;
					break;
				}
				CompiledHopKey owner = options.get(0).source().rule().parentOccurrence();
				List<SelectedCandidateProof> gateAlternatives = new ArrayList<>(options.size());
				for(CandidateRealizationInputBinding option : options) {
					CandidateRealizationReference source = option.source();
					List<CandidateDependencySkeleton> optionSkeletons =
						new ArrayList<>(affected.get(axis).size());
					for(CandidateDependencySkeleton skeleton : affected.get(axis))
						optionSkeletons.add(new CandidateDependencySkeleton(skeleton.key(), source,
							candidateHandle(source), skeleton.witness(), skeleton.inputPosition()));
					gateAlternatives.add(new SelectedCandidateProof(source,
						overlayDependencies(optionSkeletons, fixed), false, witness));
				}
				CandidateAxisGate gate = new CandidateAxisGate(owner,
					options.get(0).inputPosition(), witness, gateAlternatives);
				consumerDependencies.add(new CandidateProofDependency(gate));
			}
			if(valid)
				roots.add(new SelectedCandidateProof(pinned,
					List.copyOf(consumerDependencies), directGround, witness));
		}
		return roots.isEmpty() ? null : new NativeFactoredProofAlternatives(List.copyOf(roots));
	}

	/**
	 * Keeps rectangular native alternatives factorized inside an otherwise explicit
	 * unpinned occurrence. The result remains query-local: explicit rows retain the
	 * legacy overlay and metadata reads, while native gates never enter topology
	 * caches. Any ambiguous or unsupported native authority returns to the complete
	 * legacy topology.
	 */
	private NativeFactoredProofAlternatives nativeUnpinnedFactoredProofAlternatives(
		CompiledHopKey key, NativePoolWitness witness, FixedCandidateBoundary fixed) {
		if(metrics != null)
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_REQUESTS);
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		if(node == null || hop == null || incompleteSources.contains(key)
			|| node.legalAlternatives().stream().noneMatch(state ->
				state.output() == FederatedOutput.FOUT && state.fType() == witness.fType)) {
			if(metrics != null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_REJECT_CONTEXT);
			return null;
		}
		List<HybridTopologyRow> rows = new ArrayList<>();
		Set<ContinuityEdgeKey> defaultEdges = new java.util.HashSet<>();
		Set<CompiledHopKey> metadataOwnerReads =
			Collections.newSetFromMap(new IdentityHashMap<>());
		Set<CandidateRealizationReference> exactReferences = new HashSet<>();
		boolean matchedNative = false;
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| fact.key().parentOccurrence() != key)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				// Derived authority follows the same metadata validation as legacy
				// topology. It contributes explicit ground rows, never native gates.
				if(emission.derivedFoutAction() != null) {
					for(CandidateTopologyRow row : derivedFoutTopologyRows(
						key, node, fact, emission, witness, metadataOwnerReads)) {
						if(!exactReferences.add(row.reference())) {
							if(metrics != null)
								metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_REJECT_REFERENCE);
							return null;
						}
						if(defaultEdges.add(row.defaultEdge()))
							rows.add(HybridTopologyRow.explicitRow(row));
					}
					continue;
				}
				if(isBroadcastRowProvablyUnselectable(fact)
					|| !operationPreservesWitness(hop, witness, fact))
					continue;
				PlacementState state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != witness.fType || emission.executionFType() != witness.fType)
					continue;
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(realization.supportClauses().isEmpty())
						continue;
					CandidateRealizationReference reference =
						CandidateRealizationReference.of(fact.key(), realization);
					// The pinned resolver rejected structurally duplicated authority while
					// rescanning the inventory. Preserve that fallback before bypassing its
					// repeated lookup for this already validated exact row.
					if(!exactReferences.add(reference)) {
						if(metrics != null)
							metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_REJECT_REFERENCE);
						return null;
					}
					if(realization.supportClauses() instanceof NativeContinuitySupportClauses relation) {
						if(!nativeContinuityProductLayout(realization)) {
							if(metrics != null)
								metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_REJECT_LAYOUT);
							return null;
						}
						NativeFactoredProofAlternatives factored =
							nativeFactoredProofAlternativesResolved(key, reference, witness, fixed,
								node, hop, fact, realization, relation);
						if(factored == null || factored.alternatives().isEmpty()) {
							if(metrics != null)
								metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_REJECT_PRODUCT);
							return null;
						}
						for(SelectedCandidateProof alternative : factored.alternatives())
							rows.add(HybridTopologyRow.nativeRow(reference, alternative));
						matchedNative = true;
						continue;
					}
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						FixedValueMapPool fixedMap = null;
						if(realization.key().layoutKind()
							== PlacementIdentity.PlacementLayoutKind.VALUE_MAP) {
							FixedValueMapResolution fixedResolution = fixedValueMapResolution(reference);
							metadataOwnerReads.addAll(fixedResolution.ownerReads());
							fixedMap = fixedResolution.pool();
						}
						boolean fixedMapGround = fixedMap != null
							&& witness.matches(nativeWitness(fixedMap.pool()), fixedMap.exactLayout());
						List<CandidateDependencySkeleton> dependencies = fixedMapGround ? List.of()
							: candidateDependencySkeletons(fact, clause, hop, witness, true);
						if(dependencies == null)
							continue;
						boolean realizationGround = realization.key().layoutKind()
							== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
								&& witness.matches(nativeWitness(realization.anchor()), true)
							|| clause.nativeWorkerPoolWitness() != null
								&& witness.matches(nativeWitness(clause.nativeWorkerPoolWitness()),
									clause.nativeWorkerPoolLayoutExact())
							|| fixedMapGround;
						CandidateTopologyRow row = CandidateTopologyRow.create(reference,
							dependencies, realizationGround, witness);
						if(defaultEdges.add(row.defaultEdge()))
							rows.add(HybridTopologyRow.explicitRow(row));
						else if(metrics != null)
							metrics.recordTopologyRowCollapsed();
					}
				}
			}
		}
		if(!matchedNative) {
			if(metrics != null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_REJECT_NO_NATIVE);
			return null;
		}
		java.util.Comparator<CandidateRealizationReference> referenceOrder =
			PlacementAnalysis.canonicalComparator();
		rows.sort((left, right) -> referenceOrder.compare(left.reference(), right.reference()));
		List<SelectedCandidateProof> alternatives = new ArrayList<>(rows.size() + 1);
		boolean nodeDirectGround = node.legalAlternatives().stream().anyMatch(state ->
			state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() == witness.fType) && node.anchors().stream()
			.anyMatch(anchor -> witness.matches(nativeWitness(anchor), true));
		if(nodeDirectGround)
			alternatives.add(0, new SelectedCandidateProof(null, List.of(), true, witness));
		Set<ContinuityEdgeKey> seen = null;
		for(int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
			HybridTopologyRow hybrid = rows.get(rowIndex);
			if(hybrid.nativeAlternative() != null) {
				SelectedCandidateProof alternative = hybrid.nativeAlternative();
				if(seen == null || seen.add(hybrid.defaultEdge()))
					alternatives.add(alternative);
				continue;
			}
			CandidateTopologyRow row = hybrid.explicitRow();
			boolean queryOverlay = row.dependencies().stream()
				.anyMatch(dependency -> fixed.matches(dependency.key()));
			SelectedCandidateProof alternative;
			ContinuityEdgeKey edge;
			if(queryOverlay) {
				if(seen == null) {
					seen = new java.util.HashSet<>();
					for(int priorIndex = 0; priorIndex < rowIndex; priorIndex++)
						seen.add(rows.get(priorIndex).defaultEdge());
				}
				List<CandidateProofDependency> dependencies = overlayDependencies(
					row.dependencies(), row.defaultAlternative().dependencies(), fixed);
				alternative = new SelectedCandidateProof(row.reference(), dependencies,
					row.directGround(), witness);
				edge = ContinuityEdgeKey.ofEffective(row.reference(), row.directGround(), dependencies);
			}
			else {
				alternative = row.defaultAlternative();
				edge = row.defaultEdge();
			}
			if(seen == null || seen.add(edge))
				alternatives.add(alternative);
			else if(metrics != null)
				metrics.recordTopologyOverlayRowCollapsed();
		}
		if(metrics != null)
			metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.NATIVE_HYBRID_ACCEPTED);
		return new NativeFactoredProofAlternatives(List.copyOf(alternatives),
			metadataOwnerReads);
	}

	private static boolean nativeContinuityProductLayout(
		CandidateEmissionRealization realization) {
		PlacementIdentity.PlacementLayoutKind layout = realization.key().layoutKind();
		return layout == PlacementIdentity.PlacementLayoutKind.NATIVE_LINEAGE
			|| layout == PlacementIdentity.PlacementLayoutKind.DURABLE_MAP;
	}

	private CandidateTopology candidateTopologyForPin(CompiledHopKey key,
		CandidateRealizationReference pinned, int pinnedHandle, NativePoolWitness witness) {
		// The first ORDINARY return from native resolution is not an alias proof:
		// a later declaration can expose native support under the very same key.
		// Negative handles are exact resolver-local equality buckets after arena
		// overflow; only zero denotes no pin. This slice never migrates revisions.
		// An undeclared pin cannot name a skipped native row either. Its empty handle
		// bucket still reaches the unchanged staging-template fallback below, with all
		// ordinary/derived metadata reads and exact source authority preserved.
		boolean ordinaryOnly = pinned != null && pinnedHandle != 0
			&& pinned.rule().parentOccurrence() == key && hasNativeRelationOwner(key)
			&& !hasNativeContinuityRelation(key, pinned);
		if(ordinaryOnly && (topologyMaxEntries == 0 || topologyMaxRows == 0)) {
			if(metrics != null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.ORDINARY_TOPOLOGY_BYPASSES);
			ordinaryOnly = false;
		}
		return candidateTopology(key, witness, ordinaryOnly);
	}

	/** Generic consumers require complete coverage, never a cached ordinary slice. */
	private CandidateTopology candidateTopology(CompiledHopKey key, NativePoolWitness witness) {
		return candidateTopology(key, witness, false);
	}

	private CandidateTopology candidateTopology(CompiledHopKey key, NativePoolWitness witness,
		boolean ordinaryOnly) {
		long started = metrics == null ? 0L
			: metrics.startPhaseHandle(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY);
		try {
			return candidateTopologyMeasured(key, witness, ordinaryOnly);
		}
		finally {
			if(metrics != null)
				metrics.finishPhase(SearchSpaceMetrics.Phase.PROOF_TOPOLOGY, started);
		}
	}

	private CandidateTopology candidateTopologyMeasured(CompiledHopKey key,
		NativePoolWitness witness, boolean ordinaryOnly) {
		CandidateTopologyKey topologyKey = residentTopologyKey(key, witness);
		CandidateTopology cached = topologyKey == null ? null : candidateTopologies.get(topologyKey);
		if(cached != null && (ordinaryOnly || !cached.ordinaryOnly)) {
			if(metrics != null) {
				metrics.recordTopologyExpansion(true, 0);
				if(cached.ordinaryOnly)
					metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.ORDINARY_TOPOLOGY_RESIDENT_HITS);
			}
			return cached;
		}
		if(metrics != null) {
			if(ordinaryOnly)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.ORDINARY_TOPOLOGY_BUILD_ATTEMPTS);
			else if(cached != null)
				metrics.recordDirectWork(SearchSpaceMetrics.DirectWork.ORDINARY_TOPOLOGY_COMPLETE_UPGRADE_ATTEMPTS);
		}
		// Keep the slice indexed until complete admission succeeds. An oversized
		// complete result may be returned without evicting useful ordinary coverage.
		if(topologyKey != null && cached == null)
			forgetResidentTopologyKey(topologyKey);
		if(topologyKey == null || cached == null)
			topologyKey = new CandidateTopologyKey(key, witness);
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		if(node == null || hop == null || incompleteSources.contains(key)
			|| node.legalAlternatives().stream().noneMatch(state ->
				state.output() == FederatedOutput.FOUT && state.fType() == witness.fType)) {
			CandidateTopology unavailable = new CandidateTopology(false, false, List.of(), Map.of(), Set.of());
			cacheTopology(topologyKey, unavailable);
			if(metrics != null)
				metrics.recordTopologyExpansion(false, 0);
			return unavailable;
		}
		boolean nodeDirectGround = node.legalAlternatives().stream().anyMatch(state ->
			state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() == witness.fType) && node.anchors().stream()
			.anyMatch(anchor -> witness.matches(nativeWitness(anchor), true));
		List<CandidateTopologyRow> rows = new ArrayList<>();
		Set<CompiledHopKey> metadataOwnerReads = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<ContinuityEdgeKey> seen = new java.util.HashSet<>();
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
			if(metrics != null)
				metrics.recordProofRowExamined();
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			for(var emission : fact.allowedEmissionFacts()) {
				if(metrics != null)
					metrics.recordProofRowExamined();
				// A declared, explicitly costed upload creates a new map. Its local
				// computation need not preserve any input worker pool (rand has none).
				// Only published action authority can ground this root; prospective
				// native generation templates still follow their separate strict path.
				if(emission.derivedFoutAction() != null) {
					for(CandidateTopologyRow row : derivedFoutTopologyRows(
						key, node, fact, emission, witness, metadataOwnerReads))
						if(seen.add(ContinuityEdgeKey.of(row)))
							rows.add(row);
					continue;
				}
				if(isBroadcastRowProvablyUnselectable(fact) || !operationPreservesWitness(hop, witness, fact))
					continue;
				var state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != witness.fType || emission.executionFType() != witness.fType
					|| emission.derivedFoutAction() != null)
					continue;
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(ordinaryOnly
						&& realization.supportClauses() instanceof NativeContinuitySupportClauses nativeRelation
						&& nativeContinuityProductLayout(realization)) {
						// Derived actions were processed above. These native layouts are
						// not VALUE_MAP, hence carry no hidden metadata-owner reads.
						// The selected pin cannot alias a skipped native reference.
						if(metrics != null)
							metrics.recordDirectWork(
								SearchSpaceMetrics.DirectWork.ORDINARY_TOPOLOGY_NATIVE_LOGICAL_MEMBERS_SKIPPED,
								nativeRelation.size());
						continue;
					}
					if(metrics != null)
						metrics.recordProofRowExamined();
					CandidateRealizationReference reference = CandidateRealizationReference.of(
						fact.key(), realization);
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						if(metrics != null)
							metrics.recordProofRowExamined();
						FixedValueMapPool fixedMap = null;
						if(realization.key().layoutKind() == PlacementIdentity.PlacementLayoutKind.VALUE_MAP) {
							FixedValueMapResolution fixedResolution = fixedValueMapResolution(reference);
							metadataOwnerReads.addAll(fixedResolution.ownerReads());
							fixedMap = fixedResolution.pool();
						}
						boolean fixedMapGround = fixedMap != null
							&& witness.matches(nativeWitness(fixedMap.pool()), fixedMap.exactLayout());
						List<CandidateDependencySkeleton> dependencies = fixedMapGround ? List.of()
							: candidateDependencySkeletons(fact, clause, hop, witness, true);
						if(dependencies != null) {
							boolean realizationGround = realization.key().layoutKind()
								== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
								&& witness.matches(nativeWitness(realization.anchor()), true)
							|| clause.nativeWorkerPoolWitness() != null
									&& witness.matches(nativeWitness(clause.nativeWorkerPoolWitness()),
										clause.nativeWorkerPoolLayoutExact())
							|| fixedMapGround;
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
			Collections.unmodifiableMap(mutableRowsByHandle), metadataOwnerReads, ordinaryOnly);
		boolean admitted = cacheTopology(topologyKey, topology);
		// Count every topology that was actually constructed, including an ordinary
		// slice rejected by the shared cache budget before complete fallback builds.
		if(metrics != null)
			metrics.recordTopologyExpansion(false, canonicalRows.size());
		if(ordinaryOnly) {
			if(metrics != null)
				metrics.recordDirectWork(admitted
					? SearchSpaceMetrics.DirectWork.ORDINARY_TOPOLOGY_ADMISSIONS
					: SearchSpaceMetrics.DirectWork.ORDINARY_TOPOLOGY_BYPASSES);
			// Do not replace amortized legacy reuse with repeated uncached scans.
			if(!admitted)
				return candidateTopologyMeasured(key, witness, false);
		}
		return topology;
	}

	/** Shared action authority validation; failed checks still publish metadata reads. */
	private List<CandidateTopologyRow> derivedFoutTopologyRows(CompiledHopKey key,
		Node node, CandidateRuleFact fact, CandidateEmissionFact emission,
		NativePoolWitness witness, Set<CompiledHopKey> metadataOwnerReads) {
		var action = emission.derivedFoutAction();
		metadataOwnerReads.add(action.durableAnchorOwner());
		Node anchorOwner = nodesByKey.get(action.durableAnchorOwner());
		boolean sourceAvailable = fact.allowedEmissionFacts().stream().anyMatch(source ->
			source.derivedFoutAction() == null
				&& source.emissionState().placementState().equals(action.sourcePlacement()));
		if(action.producer() != key || action.candidateRule() != fact.key() || !sourceAvailable
			|| !action.producerValueVersion().equals(node.valueVersion())
			|| !action.statementBlockScope().equals(key.controlRegion().normalizedSignature())
			|| anchorOwner == null || anchorOwner.legalAlternatives().stream().noneMatch(state ->
				state.output() == FederatedOutput.FOUT && state.fType() == action.durableAnchorOwnerFType()))
			return List.of();
		boolean literalOwner = anchorOwner.anchors().stream().anyMatch(anchor ->
			PlacementIdentity.samePhysicalWorkerPool(anchor, action.durableAnchor()));
		if(!literalOwner && !declaresExactNativeOwnerAuthority(action))
			return List.of();
		List<CandidateTopologyRow> rows = new ArrayList<>();
		for(CandidateEmissionRealization realization : emission.realizations())
			if(realization.key().layoutKind() == PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
				&& realization.supportClauses().stream().anyMatch(clause -> clause.proofDependencies().stream()
					.anyMatch(proof -> proof.authoritySignature().equals("derived-fout:" + action.normalizedSignature())))
				&& witness.matches(nativeWitness(realization.anchor()), true)) {
				CandidateTopologyRow row = CandidateTopologyRow.create(
					CandidateRealizationReference.of(fact.key(), realization), List.of(), true, witness);
				rows.add(row);
			}
		return rows;
	}

	/**
	 * A normalized boundary carrier may have no literal node anchor even though its
	 * analysis-owned native realization carries the exact worker-pool certificate.
	 * This admits only that owned certificate; FOUT/FType equality alone is never
	 * upload authority. Final selected-clause identity is enforced independently by
	 * DerivedFoutAnchorCompatibility.
	 */
	private boolean declaresExactNativeOwnerAuthority(DerivedFoutMaterializationActionKey action) {
		CompiledHopKey owner = action.durableAnchorOwner();
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(owner, List.of())) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| fact.key().parentOccurrence() != owner)
				continue;
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != action.durableAnchorOwnerFType()
					|| emission.derivedFoutAction() != null)
					continue;
				for(CandidateEmissionRealization realization : emission.realizations()) {
					if(realization.key().layoutKind()
						!= PlacementIdentity.PlacementLayoutKind.NATIVE_LINEAGE)
						continue;
					if(realization.supportClauses() instanceof NativeContinuitySupportClauses product) {
						if(product.clauseWitness() != null && product.clauseLayoutExact()
							&& PlacementIdentity.samePhysicalWorkerPool(
								product.clauseWitness(), action.durableAnchor()))
							return true;
						continue;
					}
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						DurableAnchorKey pool = realization.provenWorkerPoolForOwnedClause(clause);
						if(pool != null && clause.nativeWorkerPoolLayoutExact()
							&& PlacementIdentity.samePhysicalWorkerPool(pool, action.durableAnchor()))
							return true;
					}
				}
			}
		}
		return false;
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
			Collections.unmodifiableMap(rowsByHandle), topology.metadataOwnerReads);
	}

	private boolean cacheTopology(CandidateTopologyKey key, CandidateTopology topology) {
		long rows = topology.rows.size();
		long ownerReads = topology.metadataOwnerReads.size();
		if(topologyMaxEntries == 0 || topologyMaxRows == 0
			|| rows + ownerReads > topologyMaxRows) {
			if(metrics != null)
				metrics.recordTopologyCacheBypass();
			return false;
		}
		CandidateTopologyKey resident = residentTopologyKey(key.occurrence, key.witness);
		CandidateTopologyKey cacheKey = resident == null ? key : resident;
		CandidateTopology prior = candidateTopologies.remove(cacheKey);
		if(prior != null) {
			topologyRetainedRows -= prior.rows.size();
			topologyRetainedOwnerReads -= prior.metadataOwnerReads.size();
		}
		evictOptionalOverlaysForTopologyBudget(1, rows + ownerReads);
		while(!candidateTopologies.isEmpty()
			&& !hasTopologyBudgetFor(1, rows + ownerReads)) {
			var oldest = candidateTopologies.entrySet().iterator().next();
			topologyRetainedRows -= oldest.getValue().rows.size();
			topologyRetainedOwnerReads -= oldest.getValue().metadataOwnerReads.size();
			candidateTopologies.remove(oldest.getKey());
			forgetResidentTopologyKey(oldest.getKey());
			if(metrics != null)
				metrics.recordTopologyCacheEviction();
		}
		candidateTopologies.put(cacheKey, topology);
		if(resident == null)
			candidateTopologyKeysByOwner.computeIfAbsent(cacheKey.occurrence,
				ignored -> new java.util.HashMap<>()).put(cacheKey.witness, cacheKey);
		topologyRetainedRows += rows;
		topologyRetainedOwnerReads += ownerReads;
		if(metrics != null)
			metrics.recordTopologyCacheResident(candidateTopologies.size(), topologyRetainedRows);
		return true;
	}

	private CandidateTopologyKey residentTopologyKey(CompiledHopKey owner,
		NativePoolWitness witness) {
		Map<NativePoolWitness,CandidateTopologyKey> byWitness = candidateTopologyKeysByOwner.get(owner);
		return byWitness == null ? null : byWitness.get(witness);
	}

	private void forgetResidentTopologyKey(CandidateTopologyKey key) {
		Map<NativePoolWitness,CandidateTopologyKey> byWitness =
			candidateTopologyKeysByOwner.get(key.occurrence);
		if(byWitness == null || byWitness.get(key.witness) != key)
			return;
		byWitness.remove(key.witness);
		if(byWitness.isEmpty())
			candidateTopologyKeysByOwner.remove(key.occurrence);
	}

	private List<CandidateProofDependency> candidateDependencies(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, NativePoolWitness witness,
		FixedCandidateBoundary fixed) {
		List<CandidateDependencySkeleton> skeletons = candidateDependencySkeletons(
			fact, clause, owner, witness);
		return skeletons == null ? null : overlayDependencies(skeletons, fixed);
	}

	private List<CandidateDependencySkeleton> candidateDependencySkeletons(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, NativePoolWitness witness) {
		boolean cacheable = ownsCandidateClause(fact, clause);
		return candidateDependencySkeletons(fact, clause, owner, witness, cacheable);
	}

	private List<CandidateDependencySkeleton> candidateDependencySkeletons(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, NativePoolWitness witness,
		boolean cacheable) {
		SkeletonFactMemo factMemo = cacheable ? dependencySkeletonMemo.get(fact) : null;
		SkeletonClauseMemo clauseMemo = factMemo == null ? null : factMemo.clauses.get(clause);
		boolean prepared = clauseMemo != null && clauseMemo.clause == clause
			&& clauseMemo.support != null && clauseMemo.pinProjection != null;
		List<CandidateRealizationReference> support = prepared
			? clauseMemo.support : requiredInputSupport(clause);
		ClausePinProjection pinProjection = prepared
			? clauseMemo.pinProjection : ClausePinProjection.prepare(support);
		if(!prepared) {
			if(cacheable && clauseMemo != null && clauseMemo.clause == clause
				&& clauseMemo.support == null) {
				factMemo = dependencySkeletonMemo(fact);
				clauseMemo = factMemo.clauses.get(clause);
				if(clauseMemo != null && clauseMemo.clause == clause
					&& clauseMemo.support == null) {
					clauseMemo = new SkeletonClauseMemo(factMemo.ownerToken, clause, support,
						pinProjection,
						new HashMap<>(clauseMemo.templates));
					factMemo.remember(clause, clauseMemo);
				}
			}
		}
		SkeletonTemplate cached = clauseMemo == null ? null : clauseMemo.templates.get(witness);
		if(cached != null && cached.owner == owner) {
			List<CandidateDependencySkeleton> materialized = materializeSkeletonTemplate(cached, support);
			if(materialized != null) {
				dependencySkeletonReuses++;
				return materialized;
			}
		}
		dependencySkeletonBuilds++;
		if(pinProjection.contradictory) {
			if(metrics != null)
				metrics.recordContradictoryClausePin();
			return null; // An AND clause cannot pin one decision owner to two realizations.
		}
		List<CandidateDependencySkeleton> dependencies = new ArrayList<>();
		for(CompiledHopKey source : reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of())) {
			CandidateRealizationReference reference = pinProjection.reference(support, source);
			dependencies.add(new CandidateDependencySkeleton(source, reference,
				candidateHandle(reference), witness, -1));
		}
		if(owner instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			return dependencies.isEmpty()
				? rememberSkeletonTemplate(cacheable, fact, clause, owner, witness,
					support, pinProjection, dependencies) : null;
		if(owner instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD) {
			boolean valid = (fact.key().orderedInputs().stream().anyMatch(input -> input.present())
				|| fact.key().orderedInputs().isEmpty() && clause.nativeWorkerPoolWitness() != null
					&& witness.matches(nativeWitness(clause.nativeWorkerPoolWitness()),
						clause.nativeWorkerPoolLayoutExact()))
				&& fact.key().orderedInputs().stream().noneMatch(input -> input.present()
					&& input.fType() != witness.fType);
			return valid ? rememberSkeletonTemplate(
				cacheable, fact, clause, owner, witness, support, pinProjection, dependencies) : null;
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
			if(dependencyWitness == null || input.fType() != dependencyWitness.fType || edge == null)
				return null;
			CandidateRealizationReference reference =
				pinProjection.reference(support, edge.producer());
			dependencies.add(new CandidateDependencySkeleton(edge.producer(), reference,
				candidateHandle(reference), dependencyWitness, position));
		}
		if(!presentPlacementData && !(transformEncodePreservesPool(owner, witness.fType)
			&& !reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of()).isEmpty()))
			return null;
		Map<ContinuityDependencyKey,CandidateDependencySkeleton> distinct = new java.util.LinkedHashMap<>();
		for(CandidateDependencySkeleton dependency : dependencies)
			distinct.putIfAbsent(ContinuityDependencyKey.of(dependency), dependency);
		return rememberSkeletonTemplate(cacheable, fact, clause, owner, witness,
			support, pinProjection, List.copyOf(distinct.values()));
	}

	private boolean ownsCandidateClause(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause) {
		Set<CandidateRealizationSupportClause> owned = ownedCandidateClausesByFact.get(fact);
		if(owned != null && owned.contains(clause))
			return true;
		for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
			for(CandidateEmissionRealization realization : emission.realizations())
				if(realization.supportClauses() instanceof NativeContinuitySupportClauses product
					&& product.firstIdentityOrdinal(clause) >= 0)
					return true;
		// A cached ordinary miss cannot hide a native handle materialized later.
		if(owned != null)
			return false;
		dependencySkeletonOwnerFactScans++;
		List<CandidateRuleFact> ownerFacts = candidateFactsByKey.get(fact.key().parentOccurrence());
		if(ownerFacts == null || ownerFacts.stream().noneMatch(current -> current == fact))
			return false;
		Set<CandidateRealizationSupportClause> current =
			Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
			for(CandidateEmissionRealization realization : emission.realizations())
				if(!(realization.supportClauses() instanceof NativeContinuitySupportClauses))
					current.addAll(realization.supportClauses());
		owned = Collections.unmodifiableSet(current);
		ownedCandidateClausesByFact.put(fact, owned);
		return owned.contains(clause);
	}

	private List<CandidateDependencySkeleton> rememberSkeletonTemplate(boolean cacheable,
		CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, NativePoolWitness witness,
		List<CandidateRealizationReference> support,
		ClausePinProjection pinProjection, List<CandidateDependencySkeleton> dependencies) {
		if(!cacheable)
			return dependencies;
		List<SkeletonTemplateDependency> template = new ArrayList<>(dependencies.size());
		for(CandidateDependencySkeleton dependency : dependencies) {
			int pinnedIndex = -1;
			if(dependency.clausePinned != null) {
				Integer index = pinProjection.firstSupportIndex.get(dependency.key);
				if(index == null)
					return dependencies;
				pinnedIndex = index;
			}
			template.add(new SkeletonTemplateDependency(dependency.key, pinnedIndex,
				dependency.witness, dependency.inputPosition));
		}
		SkeletonFactMemo factMemo = dependencySkeletonMemo(fact);
		SkeletonClauseMemo clauseMemo = factMemo.clauses.get(clause);
		if(clauseMemo == null) {
			clauseMemo = new SkeletonClauseMemo(factMemo.ownerToken, clause, support, pinProjection);
			factMemo.remember(clause, clauseMemo);
		}
		else if(clauseMemo.ownerToken != factMemo.ownerToken || clauseMemo.support == null) {
			clauseMemo = new SkeletonClauseMemo(factMemo.ownerToken, clause, support, pinProjection,
				new HashMap<>(clauseMemo.templates));
			factMemo.remember(clause, clauseMemo);
		}
		SkeletonTemplate prior = clauseMemo.templates.get(witness);
		if(prior != null && prior.owner != owner)
			return dependencies;
		clauseMemo.templates.put(witness, new SkeletonTemplate(owner, template));
		if(prior == null)
			factMemo.templateCount++;
		return dependencies;
	}

	private List<CandidateDependencySkeleton> materializeSkeletonTemplate(
		SkeletonTemplate template, List<CandidateRealizationReference> support) {
		List<CandidateDependencySkeleton> dependencies = new ArrayList<>(template.dependencies.size());
		for(SkeletonTemplateDependency dependency : template.dependencies) {
			CandidateRealizationReference pinned = null;
			if(dependency.pinnedSupportIndex >= 0) {
				if(dependency.pinnedSupportIndex >= support.size())
					return null;
				pinned = support.get(dependency.pinnedSupportIndex);
				if(pinned.rule().parentOccurrence() != dependency.key)
					return null;
			}
			dependencies.add(new CandidateDependencySkeleton(dependency.key, pinned,
				candidateHandle(pinned), dependency.witness, dependency.inputPosition));
		}
		return List.copyOf(dependencies);
	}

	private List<CandidateProofDependency> overlayDependencies(
		List<CandidateDependencySkeleton> skeletons,
		FixedCandidateBoundary fixed) {
		return overlayDependencies(skeletons, null, fixed);
	}

	private List<CandidateProofDependency> overlayDependencies(
		List<CandidateDependencySkeleton> skeletons, List<CandidateProofDependency> defaults,
		FixedCandidateBoundary fixed) {
		// A cached topology owns immutable default dependencies. An overlay changes
		// only identity-pinned owners; leave every other dependency/state shared.
		List<CandidateProofDependency> dependencies = defaults == null
			? new ArrayList<>(skeletons.size()) : null;
		for(int index = 0; index < skeletons.size(); index++) {
			CandidateDependencySkeleton skeleton = skeletons.get(index);
			boolean queryPinned = fixed.matches(skeleton.key);
			if(defaults != null && !queryPinned)
				continue;
			CandidateRealizationReference pinned = queryPinned
				? fixed.reference() : skeleton.clausePinned;
			int handle = queryPinned ? fixed.handle() : skeleton.clausePinnedHandle;
			CandidateProofDependency dependency = new CandidateProofDependency(skeleton.key, pinned,
				handle, skeleton.witness, skeleton.inputPosition, queryPinned);
			if(defaults == null)
				dependencies.add(dependency);
			else {
				if(dependencies == null)
					dependencies = new ArrayList<>(defaults);
				dependencies.set(index, dependency);
			}
		}
		return dependencies == null ? defaults : dependencies;
	}

	private List<CandidateRealizationReference> requiredInputSupport(
		CandidateRealizationSupportClause clause) {
		return requiredInputSupportByClause.computeIfAbsent(clause, ignored -> {
			// This immutable list depends only on the clause, not on current resolver
			// authority. Dependency and proof validation still run against this revision.
			List<CandidateRealizationReference> cached = PlacementIdentity.cachedNativeRequiredInputSupport(clause);
			if(cached != null)
				return cached;
			List<CandidateRealizationReference> distinct = new ArrayList<>();
			for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
				CandidateRealizationReference source = binding.source();
				if(distinct.stream().noneMatch(existing -> existing.rule().parentOccurrence()
					== source.rule().parentOccurrence() && existing.equals(source)))
					distinct.add(source);
			}
			distinct.sort(PlacementAnalysis.canonicalComparator());
			return PlacementIdentity.rememberNativeRequiredInputSupport(clause, List.copyOf(distinct));
		});
	}

	/**
	 * Resolves a VALUE_MAP to one native pool only when every selectable support
	 * clause recursively proves that same pool. This universal check prevents a
	 * realization with alternative A/B clauses from lending A's authority while B
	 * is selected.
	 */
	FixedValueMapPool fixedValueMapPool(CandidateRealizationReference reference) {
		return fixedValueMapResolution(reference).pool();
	}

	FixedValueMapResolution fixedValueMapResolution(CandidateRealizationReference reference) {
		FixedValueMapReferenceKey key = new FixedValueMapReferenceKey(reference);
		FixedValueMapResolution cached = fixedValueMapResolutions.get(key);
		if(cached != null)
			return cached;
		return resolveFixedValueMapGraph(reference);
	}

	private FixedValueMapResolution resolveFixedValueMapGraph(CandidateRealizationReference root) {
		Set<CompiledHopKey> ownerReads = Collections.newSetFromMap(new IdentityHashMap<>());
		if(root.realization().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP)
			return rememberFixedValueMapResolution(root, null, ownerReads);
		Map<FixedValueMapReferenceKey,FixedPoolNode> graph = new java.util.LinkedHashMap<>();
		java.util.ArrayDeque<CandidateRealizationReference> pending = new java.util.ArrayDeque<>();
		pending.add(root);
		while(!pending.isEmpty()) {
			CandidateRealizationReference current = pending.removeFirst();
			FixedValueMapReferenceKey currentKey = new FixedValueMapReferenceKey(current);
			if(graph.containsKey(currentKey))
				continue;
			ownerReads.add(current.rule().parentOccurrence());
			FixedPoolNode node = fixedPoolNodes.computeIfAbsent(
				current.rule().parentOccurrence(), ignored -> new java.util.HashMap<>())
				.computeIfAbsent(current, this::decodeFixedPoolNode);
			if(node.clauses().isEmpty())
				return rememberFixedValueMapResolution(root, null, ownerReads);
			graph.put(currentKey, node);
			for(FixedPoolClause clause : node.clauses())
				pending.addAll(clause.sources());
		}

		List<CandidateRealizationReference> references = graph.keySet().stream()
			.map(key -> key.reference).collect(java.util.stream.Collectors.toList());
		List<FixedPoolNode> rows = new ArrayList<>(graph.values());
		int size = rows.size();
		Map<FixedValueMapReferenceKey,Integer> ids = new java.util.HashMap<>();
		FixedPoolGrounding[] work = new FixedPoolGrounding[size];
		for(int owner = 0; owner < size; owner++) {
			ids.put(new FixedValueMapReferenceKey(references.get(owner)), owner);
			work[owner] = new FixedPoolGrounding(rows.get(owner).clauses().size());
		}
		// The priority is the old full-scan position: round * size + BFS ordinal.
		// A FIFO/leaf-first queue would change the first-grounded representative.
		java.util.PriorityQueue<Long> ready = new java.util.PriorityQueue<>();
		for(int owner = 0; owner < size; owner++) {
			List<FixedPoolClause> clauses = rows.get(owner).clauses();
			for(int clauseIndex = 0; clauseIndex < clauses.size(); clauseIndex++) {
				FixedPoolClause clause = clauses.get(clauseIndex);
				if(clause.leaf() != null && !work[owner].accept(clauseIndex, -1, clause.leaf()))
					return rememberFixedValueMapResolution(root, null, ownerReads);
				for(int position = 0; position < clause.sources().size(); position++)
					work[ids.get(new FixedValueMapReferenceKey(clause.sources().get(position)))].consumers.add(
						new FixedPoolUse(owner, clauseIndex, position));
			}
			if(work[owner].missingClauses == 0)
				ready.add((long) owner);
		}
		int grounded = 0;
		while(!ready.isEmpty()) {
			long activation = ready.remove();
			if(fixedPoolWork != null)
				fixedPoolWork.activations++;
			int source = (int) (activation % size);
			FixedPoolGrounding node = work[source];
			node.selected = node.clauses[0];
			// The old final stable sweep compares prior/common even on singleton
			// leaves. PART/OTHER or empty endpoints must not gain authority here.
			if(!sameFixedEndpoints(node.selected, node.selected))
				return rememberFixedValueMapResolution(root, null, ownerReads);
			grounded++;
			for(FixedPoolUse use : node.consumers) {
				if(fixedPoolWork != null)
					fixedPoolWork.groundingEdgeVisits++;
				FixedPoolGrounding consumer = work[use.owner()];
				boolean wasIncomplete = consumer.missingClauses != 0;
				if(!consumer.accept(use.clause(), use.position(), node.selected))
					return rememberFixedValueMapResolution(root, null, ownerReads);
				if(wasIncomplete && consumer.missingClauses == 0) {
					long round = activation / size + (use.owner() <= source ? 1 : 0);
					ready.add(round * size + use.owner());
				}
			}
		}
		// A provisionally grounded parent cannot hide an ungrounded reachable SCC.
		if(grounded != size)
			return rememberFixedValueMapResolution(root, null, ownerReads);

		// Inspect local geometry once, then propagate the two independent false
		// bits only to consumers whose inputs changed. Never rescan all clauses.
		int[] inexact = new int[size];
		boolean[] queued = new boolean[size];
		java.util.ArrayDeque<Integer> changed = new java.util.ArrayDeque<>();
		for(int owner = 0; owner < size; owner++) {
			DurableAnchorKey representative = null;
			for(FixedPoolClause clause : rows.get(owner).clauses()) {
				if(clause.leaf() != null) {
					if(fixedPoolWork != null)
						fixedPoolWork.geometryVisits++;
					FixedValueMapPool leaf = clause.leaf();
					inexact[owner] |= (leaf.exactLayout() ? 0 : 1)
						| (leaf.exactPhysicalLayout() ? 0 : 2)
						| fixedPoolGeometryDifference(representative, leaf.pool());
					representative = representative == null ? leaf.pool() : representative;
				}
				for(CandidateRealizationReference source : clause.sources()) {
					if(fixedPoolWork != null)
						fixedPoolWork.geometryVisits++;
					DurableAnchorKey pool = work[ids.get(new FixedValueMapReferenceKey(source))].selected.pool();
					inexact[owner] |= fixedPoolGeometryDifference(representative, pool);
					representative = representative == null ? pool : representative;
				}
			}
			if(inexact[owner] != 0) {
				changed.add(owner);
				queued[owner] = true;
			}
		}
		while(!changed.isEmpty()) {
			int source = changed.removeFirst();
			queued[source] = false;
			for(FixedPoolUse use : work[source].consumers) {
				if(fixedPoolWork != null)
					fixedPoolWork.inexactEdgeVisits++;
				int owner = use.owner();
				int combined = inexact[owner] | inexact[source];
				if(combined != inexact[owner]) {
					inexact[owner] = combined;
					if(!queued[owner]) {
						changed.add(owner);
						queued[owner] = true;
					}
				}
			}
		}
		Set<CompiledHopKey> sharedOwnerReads = new ImmutableIdentityOwnerReads(ownerReads);
		FixedValueMapResolution rootResolution = null;
		for(int owner = 0; owner < size; owner++) {
			FixedValueMapPool pool = new FixedValueMapPool(work[owner].selected.pool(),
				(inexact[owner] & 1) == 0, (inexact[owner] & 2) == 0);
			FixedValueMapResolution resolution = new FixedValueMapResolution(pool, sharedOwnerReads);
			CandidateRealizationReference reference = references.get(owner);
			cacheFixedValueMapResolution(reference, resolution);
			if(new FixedValueMapReferenceKey(reference).equals(new FixedValueMapReferenceKey(root)))
				rootResolution = resolution;
		}
		return rootResolution;
	}

	private FixedValueMapResolution rememberFixedValueMapResolution(
		CandidateRealizationReference reference, FixedValueMapPool pool,
		Set<CompiledHopKey> ownerReads) {
		FixedValueMapResolution resolution = new FixedValueMapResolution(pool, ownerReads);
		cacheFixedValueMapResolution(reference, resolution);
		return resolution;
	}

	private void cacheFixedValueMapResolution(CandidateRealizationReference reference,
		FixedValueMapResolution resolution) {
		long ownerReads = resolution.ownerReads().size();
		if(fixedValueMapMaxEntries == 0 || fixedValueMapMaxOwnerReads == 0
			|| ownerReads > fixedValueMapMaxOwnerReads)
			return;
		FixedValueMapReferenceKey key = new FixedValueMapReferenceKey(reference);
		FixedValueMapResolution prior = fixedValueMapResolutions.remove(key);
		if(prior != null)
			fixedValueMapRetainedOwnerReads -= prior.ownerReads().size();
		while(!fixedValueMapResolutions.isEmpty()
			&& (fixedValueMapResolutions.size() >= fixedValueMapMaxEntries
				|| fixedValueMapRetainedOwnerReads + ownerReads > fixedValueMapMaxOwnerReads)) {
			var oldest = fixedValueMapResolutions.entrySet().iterator().next();
			fixedValueMapRetainedOwnerReads -= oldest.getValue().ownerReads().size();
			fixedValueMapResolutions.remove(oldest.getKey());
		}
		fixedValueMapResolutions.put(key, resolution);
		fixedValueMapRetainedOwnerReads += ownerReads;
	}

	private FixedPoolNode decodeFixedPoolNode(CandidateRealizationReference reference) {
		if(fixedPoolWork != null)
			fixedPoolWork.decodedRows++;
		CandidateEmissionRealization realization = candidateRealization(reference);
		if(realization == null)
			return new FixedPoolNode(List.of());
		if(realization.supportClauses() instanceof NativeContinuitySupportClauses product) {
			DurableAnchorKey pool = realization.anchor() != null
				? realization.anchor() : product.clauseWitness();
			if(pool != null) {
				boolean exact = realization.anchor() != null || product.clauseLayoutExact();
				// Every member is the same grounded pool leaf in this metadata graph.
				// Exact source/proof authority remains owned by the original relation.
				return new FixedPoolNode(List.of(new FixedPoolClause(
					new FixedValueMapPool(pool, exact, exact), List.of())));
			}
		}
		List<FixedPoolClause> clauses = new ArrayList<>();
		for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
			DurableAnchorKey nativePool = realization.nativeWorkerPoolResidencyForOwnedClause(clause);
			if(nativePool != null) {
				boolean exact = realization.nativeWorkerPoolLayoutExactForOwnedClause(clause);
				clauses.add(new FixedPoolClause(new FixedValueMapPool(nativePool, exact, exact), List.of()));
				continue;
			}
			if(realization.key().layoutKind() != PlacementIdentity.PlacementLayoutKind.VALUE_MAP
				|| clause.inputBindings().isEmpty())
				return new FixedPoolNode(List.of());
			List<CandidateRealizationReference> sources = new ArrayList<>();
			for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
				if(binding.kind() == CandidateInputBindingKind.RELOCATION)
					return new FixedPoolNode(List.of());
				sources.add(binding.source());
			}
			clauses.add(new FixedPoolClause(null, List.copyOf(sources)));
		}
		return new FixedPoolNode(List.copyOf(clauses));
	}

	private static int fixedPoolGeometryDifference(DurableAnchorKey representative, DurableAnchorKey pool) {
		return representative == null ? 0
			: (PlacementIdentity.samePhysicalWorkerPool(representative, pool) ? 0 : 1)
				| (PlacementIdentity.samePhysicalLayout(representative, pool) ? 0 : 2);
	}

	private CandidateEmissionRealization candidateRealization(CandidateRealizationReference reference) {
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(
			reference.rule().parentOccurrence(), List.of()))
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE
				&& fact.key().equals(reference.rule()))
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(realization.key().equals(reference.realization()))
							return realization;
		return null;
	}

	private static boolean sameFixedEndpoints(FixedValueMapPool left, FixedValueMapPool right) {
		return left.pool().fType() == right.pool().fType()
			&& PlacementIdentity.samePhysicalWorkerEndpoints(left.pool(), right.pool());
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
			return outputWitness.broadcast();

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
		NativeSupportProduct supportProduct() {
			return proofs instanceof NativeContinuityProofProduct product
				? product.nativeProduct() : null;
		}
	}

	record ReplayProofResult(List<NativeContinuityProof> proofs,
		ReplayProofReceipt receipt) {
		ReplayProofResult {
			proofs = List.copyOf(proofs);
		}
	}

	static final class ReplayProofReceipt {
		private final Object structuralLineage;
		private final CandidateRealizationReference source;
		private final DurableAnchorKey externalSeed;
		private final Map<CompiledHopKey,Object> ownerRevisionTokens;
		private final boolean emptyResult;

		private ReplayProofReceipt(Object structuralLineage,
			CandidateRealizationReference source, DurableAnchorKey externalSeed,
			Map<CompiledHopKey,Object> ownerRevisionTokens, boolean emptyResult) {
			this.structuralLineage = Objects.requireNonNull(structuralLineage);
			this.source = Objects.requireNonNull(source);
			this.externalSeed = Objects.requireNonNull(externalSeed);
			Map<CompiledHopKey,Object> immutable = new IdentityHashMap<>();
			immutable.putAll(ownerRevisionTokens);
			this.ownerRevisionTokens = Collections.unmodifiableMap(immutable);
			this.emptyResult = emptyResult;
		}
	}

	static final class NativeContinuityProof {
		private final DurableAnchorKey externalSeed;
		private final DurableAnchorKey outputWorkerPoolWitness;
		private final boolean exactPartitionRanges;
		private final List<CandidateRealizationInputBinding> immediateBindings;
		private PlacementAnalysis.NormalizedText normalizedSignatureText;
		private PlacementAnalysis.NormalizedText canonicalOrderingSuffixText;
		private PlacementAnalysis.NormalizedText canonicalRangeBindingText;
		private String normalizedSignature;
		private int normalizedSignatureLength;
		private final int hashCode;

		NativeContinuityProof(DurableAnchorKey externalSeed, DurableAnchorKey outputWorkerPoolWitness,
			boolean exactPartitionRanges, List<CandidateRealizationInputBinding> immediateBindings) {
			this(externalSeed, outputWorkerPoolWitness, exactPartitionRanges, immediateBindings, -1);
		}

		private NativeContinuityProof(DurableAnchorKey externalSeed,
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<CandidateRealizationInputBinding> immediateBindings,
			int trustedCanonicalOrderingSuffixLength) {
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
			normalizedSignature = PlacementIdentity.cachedSignature(this);
			if(normalizedSignature != null) {
				normalizedSignatureText = PlacementAnalysis.NormalizedText.literal(normalizedSignature);
				normalizedSignatureLength = normalizedSignature.length();
			}
			else if(trustedCanonicalOrderingSuffixLength >= 0) {
				normalizedSignatureLength = Math.addExact(
					Math.addExact(externalSeed.normalizedSignature().length(),
						"|outputPool=".length()),
					trustedCanonicalOrderingSuffixLength);
			}
			else {
				initializeNormalizedText();
			}
		}

		DurableAnchorKey externalSeed() { return externalSeed; }
		DurableAnchorKey outputWorkerPoolWitness() { return outputWorkerPoolWitness; }
		boolean exactPartitionRanges() { return exactPartitionRanges; }
		List<CandidateRealizationInputBinding> immediateBindings() { return immediateBindings; }
		private PlacementAnalysis.NormalizedText normalizedSignatureText() {
			initializeNormalizedText();
			return normalizedSignatureText;
		}
		private PlacementAnalysis.NormalizedText canonicalOrderingSuffixText() {
			if(normalizedSignature != null)
				return null;
			initializeNormalizedText();
			return canonicalOrderingSuffixText;
		}
		private PlacementAnalysis.NormalizedText canonicalRangeBindingText() {
			if(normalizedSignature != null)
				return null;
			initializeNormalizedText();
			return canonicalRangeBindingText;
		}
		PlacementProofKey continuityProofKey(CompiledHopKey owner) {
			return PlacementProofKey.fromNativeContinuity(owner, this);
		}
		int normalizedSignatureHash() {
			if(normalizedSignature != null)
				return normalizedSignature.hashCode();
			String cached = PlacementIdentity.cachedSignature(this);
			if(cached != null) {
				normalizedSignature = cached;
				normalizedSignatureText = PlacementAnalysis.NormalizedText.literal(cached);
				canonicalOrderingSuffixText = null;
				canonicalRangeBindingText = null;
				return cached.hashCode();
			}
			return normalizedSignatureText().hashCode();
		}
		int normalizedSignatureLength() { return normalizedSignatureLength; }
		String normalizedSignature() {
			if(normalizedSignature == null) {
				normalizedSignature = PlacementIdentity.cachedSignature(this);
				if(normalizedSignature == null) {
					initializeNormalizedText();
					normalizedSignature = PlacementIdentity.rememberSignature(
						this, normalizedSignatureText.materialize());
				}
				// Once the exact String exists, retain it as one literal descriptor and
				// release the full binding/reference rope from this memoized proof.
				normalizedSignatureText = PlacementAnalysis.NormalizedText.literal(normalizedSignature);
				canonicalOrderingSuffixText = null;
				canonicalRangeBindingText = null;
			}
			return normalizedSignature;
		}

		private void initializeNormalizedText() {
			if(normalizedSignatureText != null)
				return;
			PlacementAnalysis.NormalizedTextContext textContext =
				new PlacementAnalysis.NormalizedTextContext();
			PlacementAnalysis.NormalizedTextBuilder tail =
				new PlacementAnalysis.NormalizedTextBuilder()
					.append(exactPartitionRanges ? "exact" : "dynamic")
					.append("|bindings=[");
			for(int index = 0; index < immediateBindings.size(); index++) {
				if(index > 0)
					tail.append(", ");
				tail.append(textContext.binding(immediateBindings.get(index)));
			}
			canonicalRangeBindingText = tail.append("]").build();
			canonicalOrderingSuffixText = new PlacementAnalysis.NormalizedTextBuilder()
				.append(outputWorkerPoolWitness.normalizedSignature())
				.append("|partitionRanges=").append(canonicalRangeBindingText).build();
			normalizedSignatureText = new PlacementAnalysis.NormalizedTextBuilder()
				.append(externalSeed.normalizedSignature()).append("|outputPool=")
				.append(canonicalOrderingSuffixText).build();
			if(normalizedSignatureLength == 0)
				normalizedSignatureLength = normalizedSignatureText.length();
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

		private CandidateProofDependency(CandidateAxisGate gate) {
			key = gate.owner;
			realization = null;
			realizationHandle = 0;
			witness = gate.witness;
			inputPosition = gate.inputPosition;
			templateRoot = false;
			state = new CandidateProofState(gate);
			hashCode = 31 * state.hashCode() + inputPosition;
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
			if(state.axisGate != null || that.state.axisGate != null)
				return inputPosition == that.inputPosition && state.equals(that.state);
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

	private record HybridTopologyRow(CandidateRealizationReference reference,
		CandidateTopologyRow explicitRow, SelectedCandidateProof nativeAlternative,
		ContinuityEdgeKey defaultEdge) {
		private static HybridTopologyRow explicitRow(CandidateTopologyRow row) {
			return new HybridTopologyRow(row.reference(), row, null, row.defaultEdge());
		}

		private static HybridTopologyRow nativeRow(CandidateRealizationReference reference,
			SelectedCandidateProof alternative) {
			return new HybridTopologyRow(reference, null, alternative,
				ContinuityEdgeKey.ofEffective(reference, alternative.directGround(),
					alternative.dependencies()));
		}
	}

	private static final class CandidateTopology {
		// A slice contains every ordinary/derived row, but not native sibling
		// products. It may serve only non-aliased ordinary or undeclared pins.
		private final boolean ordinaryOnly;
		private final boolean eligible;
		private final boolean nodeDirectGround;
		private final List<CandidateTopologyRow> rows;
		private final Map<Integer,List<CandidateTopologyRow>> rowsByHandle;
		private final List<SelectedCandidateProof> defaultAlternatives;
		private final Map<Integer,List<SelectedCandidateProof>> defaultAlternativesByHandle;
		private final Set<CompiledHopKey> dependencyOwners;
		private final Set<CompiledHopKey> metadataOwnerReads;
		private final CandidateRealizationReference[] structuralHandleReferences;
		private final int[] structuralHandleValues;
		private final boolean structuralHandleAuthorityValid;

		private CandidateTopology(boolean eligible, boolean nodeDirectGround,
			List<CandidateTopologyRow> rows, Map<Integer,List<CandidateTopologyRow>> rowsByHandle) {
			this(eligible, nodeDirectGround, rows, rowsByHandle, Set.of());
		}

		private CandidateTopology(boolean eligible, boolean nodeDirectGround,
			List<CandidateTopologyRow> rows, Map<Integer,List<CandidateTopologyRow>> rowsByHandle,
			Set<CompiledHopKey> metadataOwnerReads) {
			this(eligible, nodeDirectGround, rows, rowsByHandle, metadataOwnerReads, false);
		}

		private CandidateTopology(boolean eligible, boolean nodeDirectGround,
			List<CandidateTopologyRow> rows, Map<Integer,List<CandidateTopologyRow>> rowsByHandle,
			Set<CompiledHopKey> metadataOwnerReads, boolean ordinaryOnly) {
			this.ordinaryOnly = ordinaryOnly;
			this.eligible = eligible;
			this.nodeDirectGround = nodeDirectGround;
			this.rows = List.copyOf(rows);
			this.rowsByHandle = Map.copyOf(rowsByHandle);
			defaultAlternatives = new DefaultAlternativeList(
				this.rows.stream().map(row -> row.defaultAlternative).toList());
			Map<Integer,List<SelectedCandidateProof>> defaults = new java.util.HashMap<>();
			this.rowsByHandle.forEach((handle, handleRows) -> defaults.put(handle,
				new DefaultAlternativeList(
					handleRows.stream().map(row -> row.defaultAlternative).toList())));
			defaultAlternativesByHandle = Map.copyOf(defaults);
			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			for(CandidateTopologyRow row : this.rows)
				for(CandidateDependencySkeleton dependency : row.dependencies)
					owners.add(dependency.key);
			dependencyOwners = Collections.unmodifiableSet(owners);
			Set<CompiledHopKey> metadata = Collections.newSetFromMap(new IdentityHashMap<>());
			metadata.addAll(metadataOwnerReads);
			this.metadataOwnerReads = Collections.unmodifiableSet(metadata);
			IdentityHashMap<CandidateRealizationReference,Integer> structuralHandles =
				new IdentityHashMap<>();
			List<CandidateRealizationReference> structuralReferences = new ArrayList<>();
			List<Integer> structuralValues = new ArrayList<>();
			boolean validStructuralAuthority = true;
			int indexedRows = 0;
			for(var bucket : this.rowsByHandle.entrySet()) {
				int rowHandle = bucket.getKey();
				if(rowHandle <= 0)
					validStructuralAuthority = false;
				for(CandidateTopologyRow row : bucket.getValue()) {
					indexedRows++;
					validStructuralAuthority &= addStructuralHandleAuthority(structuralHandles,
						structuralReferences, structuralValues, row.reference, rowHandle);
					for(CandidateDependencySkeleton dependency : row.dependencies) {
						if(dependency.clausePinned == null) {
							if(dependency.clausePinnedHandle != 0)
								validStructuralAuthority = false;
						}
						else if(dependency.clausePinnedHandle <= 0)
							validStructuralAuthority = false;
						else
							validStructuralAuthority &= addStructuralHandleAuthority(structuralHandles,
								structuralReferences, structuralValues, dependency.clausePinned,
								dependency.clausePinnedHandle);
					}
				}
			}
			if(indexedRows != this.rows.size())
				validStructuralAuthority = false;
			structuralHandleReferences = structuralReferences.toArray(
				CandidateRealizationReference[]::new);
			structuralHandleValues = new int[structuralValues.size()];
			for(int index = 0; index < structuralValues.size(); index++)
				structuralHandleValues[index] = structuralValues.get(index);
			structuralHandleAuthorityValid = validStructuralAuthority;
		}

		private static boolean addStructuralHandleAuthority(
			IdentityHashMap<CandidateRealizationReference,Integer> handles,
			List<CandidateRealizationReference> references, List<Integer> values,
			CandidateRealizationReference reference, int expectedHandle) {
			Integer prior = handles.get(reference);
			if(prior != null)
				return prior == expectedHandle;
			handles.put(reference, expectedHandle);
			references.add(reference);
			values.add(expectedHandle);
			return true;
		}

		private boolean hasFixedDependency(CompiledHopKey fixedOwner) {
			return dependencyOwners.contains(fixedOwner);
		}

		private boolean hasStableStructuralHandles(NativePlacementContinuity destination) {
			if(!structuralHandleAuthorityValid)
				return false;
			for(int index = 0; index < structuralHandleReferences.length; index++)
				if(destination.candidateHandle(structuralHandleReferences[index])
					!= structuralHandleValues[index])
					return false;
			return true;
		}
	}

	private record DefaultTraversalSchedule(List<SelectedCandidateProof> filteredAlternatives,
		List<CandidateProofState> uniqueSuccessors, long rawDependencyCount) { }

	/** Original topology list plus its one lazy, immutable DFS schedule. */
	private static final class DefaultAlternativeList extends AbstractList<SelectedCandidateProof>
		implements java.util.RandomAccess {
		private static final int MAX_PRUNING_ORDINAL_EDGES = 65_536;
		private final List<SelectedCandidateProof> alternatives;
		private volatile DefaultTraversalSchedule schedule;
		private volatile List<CandidateRealizationReference> canonicalRealizations;
		private volatile boolean pruningOrdinalsAttempted;
		private volatile int[] pruningDependencyOrdinals;

		private DefaultAlternativeList(List<SelectedCandidateProof> alternatives) {
			this.alternatives = List.copyOf(alternatives);
		}

		@Override public SelectedCandidateProof get(int index) { return alternatives.get(index); }
		@Override public int size() { return alternatives.size(); }

		private DefaultTraversalSchedule traversalSchedule(SearchSpaceMetrics metrics) {
			DefaultTraversalSchedule current = schedule;
			boolean built = false;
			if(current == null)
				synchronized(this) {
					current = schedule;
					if(current == null) {
						current = buildSchedule();
						schedule = current;
						built = true;
					}
				}
			if(metrics != null)
				metrics.recordDefaultTraversalSchedule(built, current.rawDependencyCount,
					current.uniqueSuccessors.size());
			return current;
		}

		/** At most one reference per row, bounded by this list's existing row budget. */
		private List<CandidateRealizationReference> canonicalRealizations() {
			List<CandidateRealizationReference> current = canonicalRealizations;
			if(current == null)
				synchronized(this) {
					current = canonicalRealizations;
					if(current == null) {
						current = canonicalReferences(alternatives.stream()
							.map(SelectedCandidateProof::realization)
							.filter(Objects::nonNull).toList());
						canonicalRealizations = current;
					}
				}
			return current;
		}

		private DefaultTraversalSchedule buildSchedule() {
			List<SelectedCandidateProof> filtered = null;
			Set<CandidateProofState> successors = new LinkedHashSet<>();
			long rawDependencies = 0;
			for(int index = 0; index < alternatives.size(); index++) {
				SelectedCandidateProof alternative = alternatives.get(index);
				if(!alternative.directGround && alternative.dependencies.isEmpty()) {
					if(filtered == null) {
						filtered = new ArrayList<>(alternatives.size() - 1);
						filtered.addAll(alternatives.subList(0, index));
					}
					continue;
				}
				if(filtered != null)
					filtered.add(alternative);
				rawDependencies += alternative.dependencies.size();
				for(CandidateProofDependency dependency : alternative.dependencies)
					successors.add(dependency.state());
			}
			return new DefaultTraversalSchedule(filtered == null ? this : List.copyOf(filtered),
				List.copyOf(successors), rawDependencies);
		}

		private int[] pruningDependencyOrdinals() {
			if(!pruningOrdinalsAttempted)
				synchronized(this) {
					if(!pruningOrdinalsAttempted) {
						try {
							DefaultTraversalSchedule current = traversalSchedule(null);
							long edges = current.rawDependencyCount;
							if(current.filteredAlternatives == this && edges <= MAX_PRUNING_ORDINAL_EDGES
								&& edges <= 8L * alternatives.size()
								&& current.uniqueSuccessors.size() < edges) {
								Map<CandidateProofState,Integer> ordinalBySuccessor =
									new HashMap<>(current.uniqueSuccessors.size());
								for(int ordinal = 0; ordinal < current.uniqueSuccessors.size(); ordinal++)
									ordinalBySuccessor.put(current.uniqueSuccessors.get(ordinal), ordinal);
								int[] ordinals = new int[(int)edges];
								int edge = 0;
								for(SelectedCandidateProof alternative : alternatives)
									for(CandidateProofDependency dependency : alternative.dependencies)
										ordinals[edge++] = ordinalBySuccessor.get(dependency.state());
								pruningDependencyOrdinals = ordinals;
							}
						}
						catch(OutOfMemoryError optionalOrdinalAllocationFailure) {
							pruningDependencyOrdinals = null;
						}
						finally {
							pruningOrdinalsAttempted = true;
						}
					}
				}
			return pruningDependencyOrdinals;
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
		private final long retainedUnits;
		private final long estimatedBytes;
		private final int hashCode;

		private AcyclicRootSupportKey(CompiledHopKey occurrence, NativePoolWitness witness,
			List<RootTopologyRowKey> relation) {
			this.occurrence = occurrence;
			this.witness = witness;
			this.relation = List.copyOf(relation);
			long dependencies = this.relation.stream()
				.mapToLong(row -> row.dependencies().size()).sum();
			retainedUnits = saturatedAdd(this.relation.size(), dependencies);
			estimatedBytes = saturatedAdd(64L,
				saturatedAdd(48L * this.relation.size(), 48L * dependencies));
			int hash = 31 * System.identityHashCode(occurrence) + witness.hashCode();
			hashCode = 31 * hash + this.relation.hashCode();
		}

		private long retainedUnits() { return retainedUnits; }
		private long estimatedBytes() { return estimatedBytes; }

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

	/** Exact revision-local authority for one legacy topology overlay. */
	private static final class FixedBoundaryOverlayKey {
		private final CompiledHopKey owner;
		private final int pinnedHandle;
		private final NativePoolWitness witness;
		private final boolean templateRoot;
		private final CompiledHopKey fixedOwner;
		private final int fixedHandle;
		private final int hashCode;

		private FixedBoundaryOverlayKey(CompiledHopKey owner, int pinnedHandle,
			NativePoolWitness witness, boolean templateRoot, CompiledHopKey fixedOwner,
			int fixedHandle) {
			this.owner = Objects.requireNonNull(owner, "overlay owner");
			this.pinnedHandle = pinnedHandle;
			this.witness = Objects.requireNonNull(witness, "overlay witness");
			this.templateRoot = templateRoot;
			this.fixedOwner = Objects.requireNonNull(fixedOwner, "fixed overlay owner");
			this.fixedHandle = fixedHandle;
			int hash = 31 * System.identityHashCode(owner) + pinnedHandle;
			hash = 31 * hash + witness.hashCode();
			hash = 31 * hash + Boolean.hashCode(templateRoot);
			hash = 31 * hash + System.identityHashCode(fixedOwner);
			hashCode = 31 * hash + fixedHandle;
		}

		@Override public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof FixedBoundaryOverlayKey that
				&& owner == that.owner && pinnedHandle == that.pinnedHandle
				&& witness.equals(that.witness) && templateRoot == that.templateRoot
				&& fixedOwner == that.fixedOwner && fixedHandle == that.fixedHandle;
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

	/** One exact query pin; candidate proof traversal never carries a multi-owner boundary. */
	private record FixedCandidateBoundary(CompiledHopKey owner,
		CandidateRealizationReference reference, int handle) {
		private FixedCandidateBoundary {
			Objects.requireNonNull(owner, "fixed candidate owner");
			Objects.requireNonNull(reference, "fixed candidate reference");
			if(reference.rule().parentOccurrence() != owner || handle == 0)
				throw new IllegalArgumentException("CANDIDATE_FIXED_BOUNDARY_AUTHORITY_MISMATCH");
		}

		private boolean matches(CompiledHopKey candidateOwner) {
			return owner == candidateOwner;
		}
	}

	/** One exact emission loop; indexes resident support keys, never a second proof cache. */
	final class GeneratedSupportBatch {
		private static final int MAXIMUM_ENTRIES = 256;
		private final CandidateRuleFact trustedBase;
		private final CandidateEmissionFact baseEmission;
		private final CompiledHopKey root;
		private final int maximumEntries;
		private final Map<NativePoolWitness,CandidateSupportQueryKey> certifiedByWitness =
			new java.util.HashMap<>();

		private GeneratedSupportBatch(CandidateRuleFact trustedBase,
			CandidateEmissionFact baseEmission) {
			this.trustedBase = Objects.requireNonNull(trustedBase, "trusted generator base");
			this.baseEmission = Objects.requireNonNull(baseEmission, "generator base emission");
			root = trustedBase.key().parentOccurrence();
			maximumEntries = Math.min(supportMemoMaxEntries, MAXIMUM_ENTRIES);
		}

		private boolean matches(NativePlacementContinuity resolver,
			CandidateRuleFact fact, CandidateEmissionFact emission,
			CandidateRealizationReference proposedOutput) {
			return NativePlacementContinuity.this == resolver
				&& fact == trustedBase && emission == baseEmission
				&& proposedOutput.rule().parentOccurrence() == root;
		}

		private SupportMemoEntry residentSupport(NativePoolWitness witness) {
			CandidateSupportQueryKey prior = certifiedByWitness.get(witness);
			if(prior == null)
				return null;
			// This real reuse intentionally refreshes the existing memo's LRU order.
			SupportMemoEntry entry = completedSupportMemo.get(prior);
			if(entry == null || entry.root.rule().parentOccurrence() != root
				|| !entry.root.rule().equals(trustedBase.key())
				|| !entry.root.realization().emissionState().equals(baseEmission.emissionState())
				|| !entry.rootIndependent || hasReturnedRootBinding(entry))
				return null;
			// Under the same fact/emission/witness, generated roots differ only by
			// alpha-renaming the fixed root pin. The prior certificate excludes all
			// published-root history; root-free results need no binding substitution.
			return entry;
		}

		private void admit(NativePoolWitness witness,
			CandidateSupportQueryKey query, SupportMemoEntry entry) {
			if(!entry.rootIndependent) {
				if(metrics != null)
					metrics.recordDirectWork(
						SearchSpaceMetrics.DirectWork.GENERATED_BATCH_ROOT_HISTORY_REJECTIONS);
				return;
			}
			if(hasReturnedRootBinding(entry)) {
				if(metrics != null)
					metrics.recordDirectWork(
						SearchSpaceMetrics.DirectWork.GENERATED_BATCH_ROOT_BINDING_REJECTIONS);
				return;
			}
			if(!completedSupportMemo.containsKey(query))
				return;
			if(certifiedByWitness.containsKey(witness) || certifiedByWitness.size() < maximumEntries)
				certifiedByWitness.put(witness, query);
			else if(metrics != null)
				metrics.recordDirectWork(
					SearchSpaceMetrics.DirectWork.GENERATED_BATCH_INDEX_SATURATION);
		}

		private boolean hasReturnedRootBinding(SupportMemoEntry entry) {
			if(entry.templates instanceof CandidateSupportTemplateProduct product)
				return product.axes.stream().flatMap(List::stream)
					.anyMatch(binding -> binding.source().rule().parentOccurrence() == root);
			if(entry.templates instanceof CandidateConditionalSupportTemplateProduct product)
				return product.hasRealizableOwnerBinding(root);
			for(CandidateSupportTemplate template : entry.templates)
				for(CandidateRealizationInputBinding binding : template.immediateBindings)
					if(binding.source().rule().parentOccurrence() == root)
						return true;
			return false;
		}
	}

	private record MemoEntry(List<NativeContinuityProof> proofs, long estimatedBytes,
		Set<CompiledHopKey> occurrences) { }
	private record ComputedPublicProof(List<NativeContinuityProof> proofs,
		Set<CompiledHopKey> occurrences) { }
	private static final class CandidateSupportTemplate {
		private final DurableAnchorKey outputWorkerPoolWitness;
		private final boolean exactPartitionRanges;
		private final List<CandidateRealizationInputBinding> immediateBindings;
		private final int canonicalOrderingSuffixLength;

		private CandidateSupportTemplate(DurableAnchorKey outputWorkerPoolWitness,
			boolean exactPartitionRanges,
			List<CandidateRealizationInputBinding> immediateBindings) {
			this(outputWorkerPoolWitness, exactPartitionRanges,
				PlacementAnalysis.sharedAlreadyCanonicalComparableList(
					immediateBindings, "native support template binding"), -1);
		}

		private CandidateSupportTemplate(DurableAnchorKey outputWorkerPoolWitness,
			boolean exactPartitionRanges,
			List<CandidateRealizationInputBinding> immediateBindings,
			int canonicalOrderingSuffixLength) {
			this.outputWorkerPoolWitness = Objects.requireNonNull(outputWorkerPoolWitness);
			this.exactPartitionRanges = exactPartitionRanges;
			this.immediateBindings = immediateBindings;
			this.canonicalOrderingSuffixLength = canonicalOrderingSuffixLength;
		}

		private DurableAnchorKey outputWorkerPoolWitness() { return outputWorkerPoolWitness; }
		private boolean exactPartitionRanges() { return exactPartitionRanges; }
		private List<CandidateRealizationInputBinding> immediateBindings() { return immediateBindings; }

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof CandidateSupportTemplate that
				&& exactPartitionRanges == that.exactPartitionRanges
				&& outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness)
				&& immediateBindings.equals(that.immediateBindings);
		}

		@Override
		public int hashCode() {
			int hash = outputWorkerPoolWitness.hashCode();
			hash = 31 * hash + Boolean.hashCode(exactPartitionRanges);
			return 31 * hash + immediateBindings.hashCode();
		}
	}

	/** One independently factorized support product; exact members remain lazy. */
	static final class NativeSupportProduct {
		private static final int MAX_CANONICAL_LENGTH_STATES = 65_536;
		private static final long MAX_CANONICAL_LENGTH_TRANSITIONS = 1_000_000L;
		private final DurableAnchorKey externalSeed;
		private final DurableAnchorKey outputWorkerPoolWitness;
		private final boolean exactPartitionRanges;
		private final List<List<CandidateRealizationInputBinding>> axes;
		private final int size;
		private final NativeCanonicalProductIndex canonicalIndex;
		private final NativeSupportProduct excludedExactProduct;

		private NativeSupportProduct(DurableAnchorKey externalSeed,
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> axes, int size,
			NativeCanonicalProductIndex canonicalIndex) {
			this(externalSeed, outputWorkerPoolWitness, exactPartitionRanges,
				axes, size, canonicalIndex, null);
		}

		private NativeSupportProduct(DurableAnchorKey externalSeed,
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> axes, int size,
			NativeCanonicalProductIndex canonicalIndex,
			NativeSupportProduct excludedExactProduct) {
			this.externalSeed = Objects.requireNonNull(externalSeed, "externalSeed");
			this.outputWorkerPoolWitness = Objects.requireNonNull(
				outputWorkerPoolWitness, "outputWorkerPoolWitness");
			this.exactPartitionRanges = exactPartitionRanges;
			this.axes = axes;
			this.size = size;
			this.canonicalIndex = Objects.requireNonNull(canonicalIndex, "canonicalIndex");
			this.excludedExactProduct = excludedExactProduct;
		}

		DurableAnchorKey externalSeed() { return externalSeed; }
		DurableAnchorKey outputWorkerPoolWitness() { return outputWorkerPoolWitness; }
		boolean exactPartitionRanges() { return exactPartitionRanges; }
		List<List<CandidateRealizationInputBinding>> axes() { return axes; }
		int size() { return size; }
		long retainedMetadataUnits() {
			long units = Math.addExact(1L + 2L * axes.size(), canonicalIndex.retainedMetadataUnits());
			for(List<CandidateRealizationInputBinding> axis : axes)
				units = Math.addExact(units, axis.size());
			return excludedExactProduct == null ? units
				: Math.addExact(units, excludedExactProduct.retainedMetadataUnits());
		}
		boolean sameHeaderAuthority(NativeSupportProduct that) {
			return that != null && exactPartitionRanges == that.exactPartitionRanges
				&& externalSeed.equals(that.externalSeed)
				&& outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness);
		}
		boolean sameOutputHeaderAuthority(NativeSupportProduct that) {
			return that != null && exactPartitionRanges == that.exactPartitionRanges
				&& outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness);
		}
		List<List<CandidateRealizationInputBinding>> exactAuthorityIntersectionAxes(
			NativeSupportProduct that) {
			if(that == null || excludedExactProduct != null || that.excludedExactProduct != null
				|| !sameHeaderAuthority(that) || axes.size() != that.axes.size())
				return null;
			List<List<CandidateRealizationInputBinding>> intersection = new ArrayList<>(axes.size());
			for(int axis = 0; axis < axes.size(); axis++) {
				List<CandidateRealizationInputBinding> left = axes.get(axis);
				List<CandidateRealizationInputBinding> right = that.axes.get(axis);
				if(left.isEmpty() || right.isEmpty()
					|| left.get(0).inputPosition() != right.get(0).inputPosition()
					|| left.get(0).source().rule().parentOccurrence()
						!= right.get(0).source().rule().parentOccurrence())
					return null;
				Set<CandidateRealizationInputBinding> retained = new HashSet<>(right);
				List<CandidateRealizationInputBinding> common = left.stream()
					.filter(retained::contains).toList();
				if(common.isEmpty())
					return null;
				intersection.add(common);
			}
			return List.copyOf(intersection);
		}
		String headerAuthoritySignature() {
			return externalSeed.normalizedSignature()
				+ "|outputPool=" + outputWorkerPoolWitness.normalizedSignature()
				+ "|partitionRanges=" + (exactPartitionRanges ? "exact" : "dynamic");
		}
		static NativeSupportProduct tryCreate(DurableAnchorKey externalSeed,
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> axes) {
			return tryCreate(externalSeed, outputWorkerPoolWitness,
				exactPartitionRanges, axes, MAX_CANONICAL_LENGTH_STATES,
				MAX_CANONICAL_LENGTH_TRANSITIONS);
		}
		static NativeSupportProduct tryCreateExactComplement(NativeSupportProduct base,
			List<List<CandidateRealizationInputBinding>> exactAxes) {
			return tryCreateExactComplement(base, exactAxes,
				MAX_CANONICAL_LENGTH_STATES, MAX_CANONICAL_LENGTH_TRANSITIONS);
		}
		static NativeSupportProduct tryCreateExactComplement(NativeSupportProduct base,
			List<List<CandidateRealizationInputBinding>> exactAxes,
			int maximumLengthStates, long maximumLengthTransitions) {
			if(maximumLengthStates < 0 || maximumLengthTransitions < 0)
				return null;
			if(base == null || base.excludedExactProduct != null || exactAxes == null
				|| exactAxes.size() != base.axes.size())
				return null;
			for(int axis = 0; axis < exactAxes.size(); axis++) {
				if(exactAxes.get(axis).isEmpty()
					|| exactAxes.get(axis).size() > base.axes.get(axis).size())
					return null;
				for(CandidateRealizationInputBinding binding : exactAxes.get(axis))
					if(!base.containsIdentityBinding(axis, binding))
						return null;
			}
			long baseStates = base.canonicalIndex.retainedStateCount();
			long baseTransitions = base.canonicalIndex.transitionCount();
			if(baseStates > maximumLengthStates / 2
				|| baseTransitions > maximumLengthTransitions / 2)
				return null;
			int remainingStates = (int)(maximumLengthStates - 2 * baseStates);
			long remainingTransitions = maximumLengthTransitions - 2 * baseTransitions;
			NativeSupportProduct excluded = tryCreate(base.externalSeed,
				base.outputWorkerPoolWitness, base.exactPartitionRanges, exactAxes,
				remainingStates, remainingTransitions);
			long excludedStates = excluded == null ? Long.MAX_VALUE
				: excluded.canonicalIndex.retainedStateCount();
			long excludedTransitions = excluded == null ? Long.MAX_VALUE
				: excluded.canonicalIndex.transitionCount();
			if(excluded == null || excluded.size >= base.size
				|| excludedStates > remainingStates
				|| excludedTransitions > remainingTransitions)
				return null;
			return new NativeSupportProduct(base.externalSeed, base.outputWorkerPoolWitness,
				base.exactPartitionRanges, base.axes, base.size - excluded.size,
				base.canonicalIndex, excluded);
		}
		boolean conditionalComplement() { return excludedExactProduct != null; }
		List<List<CandidateRealizationInputBinding>> excludedExactAxes() {
			return excludedExactProduct == null ? List.of() : excludedExactProduct.axes;
		}
		boolean isExcludedExactBinding(int axis, CandidateRealizationInputBinding binding) {
			return excludedExactProduct != null
				&& excludedExactProduct.containsIdentityBinding(axis, binding);
		}
		static NativeSupportProduct tryCreate(DurableAnchorKey externalSeed,
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> axes, int maximumLengthStates) {
			return tryCreate(externalSeed, outputWorkerPoolWitness, exactPartitionRanges,
				axes, maximumLengthStates, MAX_CANONICAL_LENGTH_TRANSITIONS);
		}
		static NativeSupportProduct tryCreate(DurableAnchorKey externalSeed,
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> axes, int maximumLengthStates,
			long maximumLengthTransitions) {
			CandidateSupportTemplateProduct templates = CandidateSupportTemplateProduct.tryCreate(
				outputWorkerPoolWitness, exactPartitionRanges, axes);
			return templates == null ? null
				: templates.nativeProduct(externalSeed, maximumLengthStates,
					maximumLengthTransitions);
		}
		boolean sameExactAuthority(NativeSupportProduct that) {
			if(this == that)
				return true;
			if(that == null || exactPartitionRanges != that.exactPartitionRanges
				|| !externalSeed.equals(that.externalSeed)
				|| !outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness)
				|| axes.size() != that.axes.size()
				|| (excludedExactProduct == null) != (that.excludedExactProduct == null))
				return false;
			for(int axis = 0; axis < axes.size(); axis++) {
				List<CandidateRealizationInputBinding> left = axes.get(axis);
				List<CandidateRealizationInputBinding> right = that.axes.get(axis);
				if(left.size() != right.size())
					return false;
				for(int option = 0; option < left.size(); option++) {
					CandidateRealizationInputBinding a = left.get(option);
					CandidateRealizationInputBinding b = right.get(option);
					if(!a.equals(b)
						|| a.source().rule().parentOccurrence()
							!= b.source().rule().parentOccurrence())
						return false;
				}
			}
			return excludedExactProduct == null
				|| excludedExactProduct.sameIdentityAxes(that.excludedExactProduct);
		}
		private boolean sameIdentityAxes(NativeSupportProduct that) {
			if(that == null || axes.size() != that.axes.size())
				return false;
			for(int axis = 0; axis < axes.size(); axis++) {
				if(axes.get(axis).size() != that.axes.get(axis).size())
					return false;
				for(int option = 0; option < axes.get(axis).size(); option++)
					if(axes.get(axis).get(option) != that.axes.get(axis).get(option)
						|| axes.get(axis).get(option).source().rule().parentOccurrence()
							!= that.axes.get(axis).get(option).source().rule().parentOccurrence())
						return false;
			}
			return true;
		}
		boolean sameStructuralAuthority(NativeSupportProduct that) {
			return that != null && exactPartitionRanges == that.exactPartitionRanges
				&& externalSeed.equals(that.externalSeed)
				&& outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness)
				&& axes.equals(that.axes)
				&& (excludedExactProduct == null ? that.excludedExactProduct == null
					: excludedExactProduct.sameStructuralAuthority(that.excludedExactProduct));
		}
		NativeSupportProduct withAxes(List<List<CandidateRealizationInputBinding>> filtered) {
			NativeSupportProduct base = tryCreate(externalSeed, outputWorkerPoolWitness,
				exactPartitionRanges, filtered);
			if(base == null || excludedExactProduct == null)
				return base;
			List<List<CandidateRealizationInputBinding>> excluded = new ArrayList<>(filtered.size());
			for(int axis = 0; axis < filtered.size(); axis++) {
				List<CandidateRealizationInputBinding> retained = new ArrayList<>();
				for(CandidateRealizationInputBinding binding : filtered.get(axis))
					if(excludedExactProduct.containsExactBinding(axis, binding))
						retained.add(binding);
				if(retained.isEmpty())
					return base;
				excluded.add(List.copyOf(retained));
			}
			NativeSupportProduct conditional = tryCreateExactComplement(base, excluded);
			return conditional;
		}
		java.util.Optional<NativeSupportProduct> oneAxisUnion(NativeSupportProduct that) {
			return oneAxisUnion(that, MAX_CANONICAL_LENGTH_STATES,
				MAX_CANONICAL_LENGTH_TRANSITIONS);
		}

		java.util.Optional<ConditionalUnion> conditionalUnion(NativeSupportProduct that) {
			return conditionalUnion(that, MAX_CANONICAL_LENGTH_STATES,
				MAX_CANONICAL_LENGTH_TRANSITIONS);
		}

		java.util.Optional<ConditionalUnion> conditionalUnion(NativeSupportProduct that,
			int maximumLengthStates, long maximumLengthTransitions) {
			if(that == null || excludedExactProduct == null || that.excludedExactProduct == null
				|| !sameHeaderAuthority(that) || !sameExactAxes(that)
				|| maximumLengthStates < 0 || maximumLengthTransitions < 0)
				return java.util.Optional.empty();
			List<List<CandidateRealizationInputBinding>> intersection =
				new ArrayList<>(axes.size());
			boolean sameAsLeftMask = true;
			for(int axis = 0; axis < axes.size(); axis++) {
				List<CandidateRealizationInputBinding> leftMask = excludedExactProduct.axes.get(axis);
				List<CandidateRealizationInputBinding> common = new ArrayList<>();
				// Rebind an equivalent RHS mask onto the published left axes. Donor
				// lookup still returns the RHS-owned clause and its original bindings.
				for(CandidateRealizationInputBinding binding : leftMask)
					if(that.excludedExactProduct.containsExactBinding(axis, binding))
						common.add(binding);
				if(common.isEmpty()) {
					NativeSupportProduct full = baseProduct();
					return java.util.Optional.of(new ConditionalUnion(
						full, excludedExactProduct, false));
				}
				sameAsLeftMask &= common.size() == leftMask.size();
				intersection.add(List.copyOf(common));
			}
			if(sameAsLeftMask)
				return java.util.Optional.of(new ConditionalUnion(this, null, true));
			int stateBudget = maximumLengthStates / 2;
			long transitionBudget = maximumLengthTransitions / 2;
			NativeSupportProduct union = tryCreateExactComplement(
				baseProduct(), intersection, stateBudget, transitionBudget);
			if(union == null)
				return java.util.Optional.empty();
			NativeSupportProduct rightOnly = tryCreateExactComplement(
				excludedExactProduct, intersection,
				maximumLengthStates - stateBudget,
				maximumLengthTransitions - transitionBudget);
			return rightOnly == null ? java.util.Optional.empty()
				: java.util.Optional.of(new ConditionalUnion(union, rightOnly, false));
		}

		private NativeSupportProduct baseProduct() {
			return excludedExactProduct == null ? this : new NativeSupportProduct(
				externalSeed, outputWorkerPoolWitness, exactPartitionRanges,
				axes, Math.addExact(size, excludedExactProduct.size), canonicalIndex);
		}

		record ConditionalUnion(NativeSupportProduct product,
			NativeSupportProduct rightOnly, boolean unchanged) { }
		java.util.Optional<NativeSupportProduct> oneAxisUnion(NativeSupportProduct that,
			int maximumLengthStates, long maximumLengthTransitions) {
			if(excludedExactProduct != null || that == null || that.excludedExactProduct != null
				|| exactPartitionRanges != that.exactPartitionRanges
				|| !externalSeed.equals(that.externalSeed)
				|| !outputWorkerPoolWitness.equals(that.outputWorkerPoolWitness)
				|| axes.size() != that.axes.size())
				return java.util.Optional.empty();
			int changedAxis = -1;
			List<List<CandidateRealizationInputBinding>> unionAxes = new ArrayList<>(axes.size());
			for(int axis = 0; axis < axes.size(); axis++) {
				List<CandidateRealizationInputBinding> left = axes.get(axis);
				List<CandidateRealizationInputBinding> right = that.axes.get(axis);
				if(sameExactAxis(left, right)) {
					unionAxes.add(left);
					continue;
				}
				if(changedAxis >= 0)
					return java.util.Optional.empty();
				changedAxis = axis;
				List<CandidateRealizationInputBinding> merged = mergeExactAxis(left, right);
				if(merged == null)
					return java.util.Optional.empty();
				unionAxes.add(merged);
			}
			if(changedAxis < 0)
				return java.util.Optional.of(this);
			NativeSupportProduct union = tryCreate(externalSeed, outputWorkerPoolWitness,
				exactPartitionRanges, unionAxes, maximumLengthStates, maximumLengthTransitions);
			return java.util.Optional.ofNullable(union);
		}

		private static boolean sameExactAxis(List<CandidateRealizationInputBinding> left,
			List<CandidateRealizationInputBinding> right) {
			if(left.size() != right.size())
				return false;
			for(int option = 0; option < left.size(); option++)
				if(!sameExactBindingAuthority(left.get(option), right.get(option)))
					return false;
			return true;
		}
		boolean sameExactAxes(NativeSupportProduct that) {
			if(that == null || axes.size() != that.axes.size())
				return false;
			for(int axis = 0; axis < axes.size(); axis++)
				if(!sameExactAxis(axes.get(axis), that.axes.get(axis)))
					return false;
			return true;
		}

		private static List<CandidateRealizationInputBinding> mergeExactAxis(
			List<CandidateRealizationInputBinding> left,
			List<CandidateRealizationInputBinding> right) {
			List<CandidateRealizationInputBinding> merged = new ArrayList<>(left.size() + right.size());
			int leftIndex = 0, rightIndex = 0;
			while(leftIndex < left.size() && rightIndex < right.size()) {
				CandidateRealizationInputBinding a = left.get(leftIndex);
				CandidateRealizationInputBinding b = right.get(rightIndex);
				int order = a.compareTo(b);
				if(order < 0) {
					merged.add(a);
					leftIndex++;
				}
				else if(order > 0) {
					merged.add(b);
					rightIndex++;
				}
				else {
					if(!sameExactBindingAuthority(a, b))
						return null;
					// Preserve the first relation's exact authority object for overlaps.
					merged.add(a);
					leftIndex++;
					rightIndex++;
				}
			}
			while(leftIndex < left.size())
				merged.add(left.get(leftIndex++));
			while(rightIndex < right.size())
				merged.add(right.get(rightIndex++));
			return List.copyOf(merged);
		}

		private static boolean sameExactBindingAuthority(
			CandidateRealizationInputBinding left,
			CandidateRealizationInputBinding right) {
			return left.equals(right)
				&& left.source().rule().parentOccurrence()
					== right.source().rule().parentOccurrence();
		}
		boolean containsExactBinding(int axis, CandidateRealizationInputBinding binding) {
			if(axis < 0 || axis >= axes.size())
				return false;
			for(CandidateRealizationInputBinding candidate : axes.get(axis))
				if(sameExactBindingAuthority(candidate, binding))
					return true;
			return false;
		}
		private boolean containsIdentityBinding(int axis,
			CandidateRealizationInputBinding binding) {
			if(axis < 0 || axis >= axes.size())
				return false;
			for(CandidateRealizationInputBinding candidate : axes.get(axis))
				if(candidate == binding
					&& candidate.source().rule().parentOccurrence()
						== binding.source().rule().parentOccurrence())
					return true;
			return false;
		}

		List<CandidateRealizationInputBinding> bindingsAt(int ordinal) {
			if(excludedExactProduct == null)
				return canonicalIndex.bindingsAt(axes, size, ordinal);
			Objects.checkIndex(ordinal, size);
			int low = 0, high = Math.addExact(size, excludedExactProduct.size) - 1;
			while(low < high) {
				int mid = (low + high) >>> 1;
				List<CandidateRealizationInputBinding> member =
					canonicalIndex.bindingsAt(axes, size + excludedExactProduct.size, mid);
				int excludedThrough = excludedExactProduct.countAtOrBefore(member);
				if(mid + 1 - excludedThrough >= ordinal + 1)
					high = mid;
				else
					low = mid + 1;
			}
			List<CandidateRealizationInputBinding> selected =
				canonicalIndex.bindingsAt(axes, size + excludedExactProduct.size, low);
			if(excludedExactProduct.ordinalOfExactAuthorityBindings(selected) >= 0)
				throw new IllegalStateException("Conditional native rank selected excluded member");
			return selected;
		}
		int ordinalOfBindings(List<CandidateRealizationInputBinding> bindings) {
			int base = canonicalIndex.ordinalOfBindings(axes, bindings);
			if(base < 0 || excludedExactProduct != null
				&& excludedExactProduct.ordinalOfExactAuthorityBindings(bindings) >= 0)
				return -1;
			if(excludedExactProduct == null)
				return base;
			return base - excludedExactProduct.countBefore(bindings);
		}
		int ordinalOfExactAuthorityBindings(List<CandidateRealizationInputBinding> bindings) {
			List<CandidateRealizationInputBinding> owned = exactOwnedBindings(bindings);
			if(owned == null)
				return -1;
			return ordinalOfBindings(owned);
		}
		int bindingLength(List<CandidateRealizationInputBinding> bindings) {
			List<CandidateRealizationInputBinding> owned = exactOwnedBindings(bindings);
			return owned == null ? -1 : canonicalIndex.bindingLength(axes, owned);
		}
		int rankWithinBindingLength(List<CandidateRealizationInputBinding> bindings) {
			List<CandidateRealizationInputBinding> owned = exactOwnedBindings(bindings);
			if(owned == null)
				return -1;
			if(excludedExactProduct == null)
				return canonicalIndex.rankWithinBindingLength(axes, owned);
			int ordinal = ordinalOfBindings(owned);
			int length = bindingLength(owned);
			if(ordinal < 0 || length < 0)
				return -1;
			for(var entry : bindingLengthCounts().entrySet()) {
				if(entry.getKey() == length)
					return ordinal;
				ordinal -= entry.getValue();
			}
			return -1;
		}
		private List<CandidateRealizationInputBinding> exactOwnedBindings(
			List<CandidateRealizationInputBinding> bindings) {
			if(bindings.size() != axes.size())
				return null;
			List<CandidateRealizationInputBinding> owned = new ArrayList<>(axes.size());
			for(int axis = 0; axis < axes.size(); axis++) {
				CandidateRealizationInputBinding match = null;
				for(CandidateRealizationInputBinding candidate : axes.get(axis))
					if(sameExactBindingAuthority(candidate, bindings.get(axis))) {
						if(match != null)
							return null;
						match = candidate;
					}
				if(match == null)
					return null;
				owned.add(match);
			}
			return PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				owned, "native exact owned binding");
		}
		List<CandidateRealizationInputBinding> bindingsAtLengthRank(
			int bindingLength, int rank) {
			if(excludedExactProduct == null)
				return canonicalIndex.bindingsAtLengthRank(axes, bindingLength, rank);
			int offset = 0;
			for(var entry : bindingLengthCounts().entrySet()) {
				if(entry.getKey() == bindingLength) {
					Objects.checkIndex(rank, entry.getValue());
					return bindingsAt(Math.addExact(offset, rank));
				}
				offset = Math.addExact(offset, entry.getValue());
			}
			throw new IndexOutOfBoundsException("Native binding-length rank outside relation");
		}
		java.util.Map<Integer,Integer> bindingLengthCounts() {
			if(excludedExactProduct == null)
				return canonicalIndex.bindingLengthCounts();
			java.util.Map<Integer,Integer> excludedCounts =
				excludedExactProduct.bindingLengthCounts();
			java.util.Map<Integer,Integer> counts = new java.util.LinkedHashMap<>();
			for(NativeCanonicalLengthBucket bucket : canonicalIndex.orderedLengths) {
				int count = bucket.count() - excludedCounts.getOrDefault(bucket.bindingLength(), 0);
				if(count != 0)
					counts.put(bucket.bindingLength(), count);
			}
			return java.util.Collections.unmodifiableMap(counts);
		}
		java.util.Map<Integer,NativeHashSequence> clauseHashSummaries(CompiledHopKey owner,
			DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
			return excludedExactProduct == null
				? canonicalIndex.clauseHashSummaries(this, owner, clauseWitness, clauseLayoutExact)
				: canonicalIndex.conditionalClauseHashSummaries(this, excludedExactProduct,
					owner, clauseWitness, clauseLayoutExact);
		}
		int fixedAuthorityLength() { return canonicalIndex.fixedAuthorityLength(); }
		private int countBefore(List<CandidateRealizationInputBinding> bindings) {
			if(bindings.size() != axes.size())
				return 0;
			PlacementAnalysis.NormalizedTextContext textContext =
				new PlacementAnalysis.NormalizedTextContext();
			int length = 0;
			for(CandidateRealizationInputBinding binding : bindings)
				length = Math.addExact(length, textContext.binding(binding).length());
			int count = 0;
			for(var entry : bindingLengthCounts().entrySet()) {
				String left = Integer.toString(fixedAuthorityLength() + entry.getKey()) + ':';
				String right = Integer.toString(fixedAuthorityLength() + length) + ':';
				if(left.compareTo(right) < 0)
					count = Math.addExact(count, entry.getValue());
			}
			return Math.addExact(count,
				canonicalIndex.countBeforeAtLength(axes, bindings, length));
		}
		private int countAtOrBefore(List<CandidateRealizationInputBinding> bindings) {
			int before = countBefore(bindings);
			return ordinalOfExactAuthorityBindings(bindings) >= 0 ? before + 1 : before;
		}
		private static List<CandidateRealizationInputBinding> rowMajorBindingsAt(
			List<List<CandidateRealizationInputBinding>> axes, int size, int ordinal) {
			Objects.checkIndex(ordinal, size);
			if(axes.isEmpty())
				return List.of();
			CandidateRealizationInputBinding[] selected =
				new CandidateRealizationInputBinding[axes.size()];
			int remaining = ordinal;
			for(int axis = axes.size() - 1; axis >= 0; axis--) {
				List<CandidateRealizationInputBinding> options = axes.get(axis);
				selected[axis] = options.get(remaining % options.size());
				remaining /= options.size();
			}
			return PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				java.util.Arrays.asList(selected), "native support product binding");
		}
	}

	/** Algebraic hash of one consecutive canonical clause sequence. */
	static record NativeHashSequence(int count, int power, int weightedHash) {
		NativeHashSequence append(NativeHashSequence suffix) {
			return new NativeHashSequence(Math.addExact(count, suffix.count),
				power * suffix.power, weightedHash * suffix.power + suffix.weightedHash);
		}
	}

	/**
	 * Exact canonical unrank for a native support product. The first member-varying
	 * field in a support clause is the length-prefixed native proof authority. Its
	 * length is a fixed prefix plus the sum of the selected binding text lengths.
	 * Suffix counts therefore recover canonical members without enumerating tuples.
	 */
	private static final class NativeCanonicalProductIndex {
		private final boolean rowMajor;
		private final List<int[]> optionLengths;
		private final List<java.util.Map<Integer,Integer>> suffixLengthCounts;
		private final List<NativeCanonicalLengthBucket> orderedLengths;
		private final int fixedAuthorityLength;
		private final java.util.Map<Integer,Integer> bindingLengthCounts;
		private final long retainedStateCount;
		private final long transitionCount;

		private NativeCanonicalProductIndex(boolean rowMajor,
			List<int[]> optionLengths,
			List<java.util.Map<Integer,Integer>> suffixLengthCounts,
			List<NativeCanonicalLengthBucket> orderedLengths, int fixedAuthorityLength,
			long retainedStateCount, long transitionCount) {
			this.rowMajor = rowMajor;
			this.optionLengths = optionLengths;
			this.suffixLengthCounts = suffixLengthCounts;
			this.orderedLengths = orderedLengths;
			this.fixedAuthorityLength = fixedAuthorityLength;
			this.retainedStateCount = retainedStateCount;
			this.transitionCount = transitionCount;
			java.util.Map<Integer,Integer> counts = new java.util.LinkedHashMap<>();
			for(NativeCanonicalLengthBucket bucket : orderedLengths)
				counts.put(bucket.bindingLength(), bucket.count());
			bindingLengthCounts = java.util.Collections.unmodifiableMap(counts);
		}
		private long retainedStateCount() { return retainedStateCount; }
		private long transitionCount() { return transitionCount; }

		private long retainedMetadataUnits() {
			long units = 1L + optionLengths.size() + suffixLengthCounts.size() + orderedLengths.size();
			units = Math.addExact(units, 1L + 3L * bindingLengthCounts.size());
			for(int[] lengths : optionLengths)
				units = Math.addExact(units, 1L + lengths.length);
			for(java.util.Map<Integer,Integer> counts : suffixLengthCounts)
				units = Math.addExact(units, 1L + 3L * counts.size());
			for(NativeCanonicalLengthBucket bucket : orderedLengths)
				units = Math.addExact(units, 4L + bucket.lengthPrefix().length());
			return units;
		}

		private static NativeCanonicalProductIndex tryCreate(DurableAnchorKey externalSeed,
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> axes, int maximumLengthStates,
			long maximumLengthTransitions) {
			if(maximumLengthStates < 0 || maximumLengthTransitions < 0)
				return null;
			PlacementAnalysis.NormalizedTextContext textContext =
				new PlacementAnalysis.NormalizedTextContext();
			List<int[]> optionLengths = new ArrayList<>(axes.size());
			boolean uniform = true;
			for(List<CandidateRealizationInputBinding> axis : axes) {
				int[] lengths = new int[axis.size()];
				for(int option = 0; option < axis.size(); option++) {
					lengths[option] = textContext.binding(axis.get(option)).length();
					uniform &= option == 0 || lengths[option] == lengths[0];
				}
				optionLengths.add(lengths);
			}
			try {
				int fixedAuthorityLength = Math.addExact(
					Math.addExact(externalSeed.normalizedSignature().length(), "|outputPool=".length()),
					outputWorkerPoolWitness.normalizedSignature().length());
				fixedAuthorityLength = Math.addExact(fixedAuthorityLength, "|partitionRanges=".length());
				fixedAuthorityLength = Math.addExact(fixedAuthorityLength,
					exactPartitionRanges ? "exact".length() : "dynamic".length());
				fixedAuthorityLength = Math.addExact(fixedAuthorityLength, "|bindings=[".length() + 1);
				fixedAuthorityLength = Math.addExact(fixedAuthorityLength,
					Math.multiplyExact(Math.max(0, axes.size() - 1), ", ".length()));
				if(uniform) {
					int authorityLength = fixedAuthorityLength;
					for(int[] lengths : optionLengths)
						authorityLength = Math.addExact(authorityLength, lengths[0]);
					return new NativeCanonicalProductIndex(true, List.copyOf(optionLengths),
						List.of(), List.of(new NativeCanonicalLengthBucket(
							authorityLength - fixedAuthorityLength,
							axes.stream().mapToInt(List::size).reduce(1, Math::multiplyExact),
							Integer.toString(authorityLength) + ':')), fixedAuthorityLength,
						axes.size() + 1L, axes.stream().mapToLong(List::size).sum());
				}

				List<java.util.Map<Integer,Integer>> suffix = new ArrayList<>(
					Collections.nCopies(axes.size() + 1, null));
				suffix.set(axes.size(), java.util.Map.of(0, 1));
				long retainedStates = 1;
				long transitions = 0;
				for(int axis = axes.size() - 1; axis >= 0; axis--) {
					java.util.Map<Integer,Integer> tail = suffix.get(axis + 1);
					java.util.Map<Integer,Integer> lengthMultiplicities = new java.util.HashMap<>();
					for(int optionLength : optionLengths.get(axis))
						lengthMultiplicities.merge(optionLength, 1, Math::addExact);
					long axisTransitions = Math.multiplyExact(
						(long)lengthMultiplicities.size(), tail.size());
					if(axisTransitions > maximumLengthTransitions - transitions)
						return null;
					transitions += axisTransitions;
					java.util.Map<Integer,Integer> counts = new java.util.HashMap<>();
					for(var lengthEntry : lengthMultiplicities.entrySet())
						for(var tailEntry : tail.entrySet()) {
							int length = Math.addExact(lengthEntry.getKey(), tailEntry.getKey());
							int contribution = Math.multiplyExact(
								lengthEntry.getValue(), tailEntry.getValue());
							Integer prior = counts.get(length);
							if(prior == null) {
								if(retainedStates >= maximumLengthStates)
									return null;
								counts.put(length, contribution);
								retainedStates++;
							}
							else
								counts.put(length, Math.addExact(prior, contribution));
						}
					suffix.set(axis, java.util.Map.copyOf(counts));
				}

				List<NativeCanonicalLengthBucket> ordered = new ArrayList<>(suffix.get(0).size());
				for(var entry : suffix.get(0).entrySet()) {
					int authorityLength = Math.addExact(fixedAuthorityLength, entry.getKey());
					ordered.add(new NativeCanonicalLengthBucket(entry.getKey(), entry.getValue(),
						Integer.toString(authorityLength) + ':'));
				}
				ordered.sort(java.util.Comparator.comparing(NativeCanonicalLengthBucket::lengthPrefix));
				return new NativeCanonicalProductIndex(false, List.copyOf(optionLengths),
					List.copyOf(suffix), List.copyOf(ordered), fixedAuthorityLength,
					retainedStates, transitions);
			}
			catch(ArithmeticException overflow) {
				return null;
			}
		}

		private int fixedAuthorityLength() { return fixedAuthorityLength; }
		private java.util.Map<Integer,Integer> bindingLengthCounts() {
			return bindingLengthCounts;
		}

		private java.util.Map<Integer,NativeHashSequence> clauseHashSummaries(
			NativeSupportProduct product, CompiledHopKey owner,
			DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
			PlacementAnalysis.NormalizedTextContext textContext =
				new PlacementAnalysis.NormalizedTextContext();
			java.util.Map<Integer,NativeTupleHashSummary> tuplesByLength =
				tupleHashSummaries(product.axes(), textContext);

			java.util.Map<Integer,NativeHashSequence> result = new java.util.LinkedHashMap<>();
			String authorityPrefix = product.headerAuthoritySignature() + "|bindings=[";
			int proofConstant = 31 * (31
				* PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY.hashCode()
				+ Objects.hashCode(owner));
			int witnessHash = Objects.hashCode(clauseWitness);
			int clauseConstant = 923521 + 29791 * proofConstant
				+ 31 * witnessHash + Boolean.hashCode(clauseLayoutExact);
			for(NativeCanonicalLengthBucket bucket : orderedLengths) {
				NativeTupleHashSummary tuples = tuplesByLength.get(bucket.bindingLength());
				if(tuples == null || tuples.count() != bucket.count())
					throw new IllegalStateException("Native canonical hash index is inconsistent");
				int authorityWeighted = authorityPrefix.hashCode()
					* tuples.authorityCharPower() * tuples.geometric()
					+ tuples.weightedAuthoritySuffix();
				int bindingsWeighted = tuples.bindingsPower() * tuples.geometric()
					+ tuples.weightedBindingsSuffix();
				int clauseWeighted = clauseConstant * tuples.geometric()
					+ 29791 * authorityWeighted + 961 * bindingsWeighted;
				result.put(bucket.bindingLength(), new NativeHashSequence(
					tuples.count(), tuples.sequencePower(), clauseWeighted));
			}
			return java.util.Collections.unmodifiableMap(result);
		}

		private java.util.Map<Integer,NativeHashSequence> conditionalClauseHashSummaries(
			NativeSupportProduct product, NativeSupportProduct excluded,
			CompiledHopKey owner, DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
			PlacementAnalysis.NormalizedTextContext textContext =
				new PlacementAnalysis.NormalizedTextContext();
			java.util.Map<Integer,NativeTupleHashSummary> tuplesByLength =
				conditionalTupleHashSummaries(product.axes(), excluded.axes(), textContext);
			java.util.Map<Integer,NativeHashSequence> result = new java.util.LinkedHashMap<>();
			String authorityPrefix = product.headerAuthoritySignature() + "|bindings=[";
			int proofConstant = 31 * (31
				* PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY.hashCode()
				+ Objects.hashCode(owner));
			int witnessHash = Objects.hashCode(clauseWitness);
			int clauseConstant = 923521 + 29791 * proofConstant
				+ 31 * witnessHash + Boolean.hashCode(clauseLayoutExact);
			java.util.Map<Integer,Integer> acceptedCounts = product.bindingLengthCounts();
			for(NativeCanonicalLengthBucket bucket : orderedLengths) {
				Integer accepted = acceptedCounts.get(bucket.bindingLength());
				if(accepted == null)
					continue;
				NativeTupleHashSummary tuples = tuplesByLength.get(bucket.bindingLength());
				if(tuples == null || tuples.count() != accepted)
					throw new IllegalStateException("Conditional native canonical hash index is inconsistent");
				int authorityWeighted = authorityPrefix.hashCode()
					* tuples.authorityCharPower() * tuples.geometric()
					+ tuples.weightedAuthoritySuffix();
				int bindingsWeighted = tuples.bindingsPower() * tuples.geometric()
					+ tuples.weightedBindingsSuffix();
				int clauseWeighted = clauseConstant * tuples.geometric()
					+ 29791 * authorityWeighted + 961 * bindingsWeighted;
				result.put(bucket.bindingLength(), new NativeHashSequence(
					tuples.count(), tuples.sequencePower(), clauseWeighted));
			}
			return java.util.Collections.unmodifiableMap(result);
		}

		private java.util.Map<Integer,NativeTupleHashSummary> conditionalTupleHashSummaries(
			List<List<CandidateRealizationInputBinding>> axes,
			List<List<CandidateRealizationInputBinding>> excludedAxes,
			PlacementAnalysis.NormalizedTextContext textContext) {
			java.util.Map<Integer,NativeTupleHashSummary> allTail = java.util.Map.of(0,
				new NativeTupleHashSummary(1, 31, 1, 31, ']', 1, 0));
			java.util.Map<Integer,NativeTupleHashSummary> acceptedTail = java.util.Map.of();
			for(int axis = axes.size() - 1; axis >= 0; axis--) {
				java.util.Map<Integer,NativeTupleHashSummary> all = new java.util.HashMap<>();
				java.util.Map<Integer,NativeTupleHashSummary> accepted = new java.util.HashMap<>();
				for(int option = 0; option < axes.get(axis).size(); option++) {
					CandidateRealizationInputBinding binding = axes.get(axis).get(option);
					boolean exact = identityIndexOf(excludedAxes.get(axis), binding) >= 0;
					appendTupleBlocks(all, binding, axis, axes.size(), optionLengths.get(axis)[option],
						allTail, textContext);
					appendTupleBlocks(accepted, binding, axis, axes.size(), optionLengths.get(axis)[option],
						exact ? acceptedTail : allTail, textContext);
				}
				allTail = all;
				acceptedTail = accepted;
			}
			return acceptedTail;
		}

		private static void appendTupleBlocks(
			java.util.Map<Integer,NativeTupleHashSummary> destination,
			CandidateRealizationInputBinding binding, int axis, int axisCount, int optionLength,
			java.util.Map<Integer,NativeTupleHashSummary> tails,
			PlacementAnalysis.NormalizedTextContext textContext) {
			PlacementAnalysis.NormalizedText text = textContext.binding(binding);
			int chunkLength = text.length();
			int chunkHash = text.hashCode();
			if(axis + 1 < axisCount) {
				chunkHash = chunkHash * 961 + ", ".hashCode();
				chunkLength += 2;
			}
			for(var tailEntry : tails.entrySet()) {
				NativeTupleHashSummary tail = tailEntry.getValue();
				int authorityPower = pow31(chunkLength) * tail.authorityCharPower();
				int bindingPower = 31 * tail.bindingsPower();
				NativeTupleHashSummary block = new NativeTupleHashSummary(tail.count(),
					tail.sequencePower(), tail.geometric(), authorityPower,
					chunkHash * tail.authorityCharPower() * tail.geometric()
						+ tail.weightedAuthoritySuffix(), bindingPower,
					binding.hashCode() * tail.bindingsPower() * tail.geometric()
						+ tail.weightedBindingsSuffix());
				int length = Math.addExact(optionLength, tailEntry.getKey());
				destination.merge(length, block, NativeTupleHashSummary::append);
			}
		}

		private java.util.Map<Integer,NativeTupleHashSummary> tupleHashSummaries(
			List<List<CandidateRealizationInputBinding>> axes,
			PlacementAnalysis.NormalizedTextContext textContext) {
			java.util.Map<Integer,NativeTupleHashSummary> tail = java.util.Map.of(0,
				new NativeTupleHashSummary(1, 31, 1, 31, ']', 1, 0));
			int uniformLength = 0;
			// Arity is not bounded by product cardinality (singleton axes still have
			// one member). Evaluate the existing suffix states without Java recursion,
			// retaining only the current and immediately following axis summaries.
			for(int axis = axes.size() - 1; axis >= 0; axis--) {
				if(rowMajor)
					uniformLength = Math.addExact(uniformLength, optionLengths.get(axis)[0]);
				Iterable<Integer> lengths = rowMajor ? List.of(uniformLength)
					: suffixLengthCounts.get(axis).keySet();
				java.util.Map<Integer,NativeTupleHashSummary> current = new java.util.HashMap<>();
				for(int length : lengths) {
					NativeTupleHashSummary summary = tupleHashSummary(axes, textContext, tail, axis, length);
					if(summary != null)
						current.put(length, summary);
				}
				tail = current;
			}
			return tail;
		}

		private NativeTupleHashSummary tupleHashSummary(
			List<List<CandidateRealizationInputBinding>> axes,
			PlacementAnalysis.NormalizedTextContext textContext,
			java.util.Map<Integer,NativeTupleHashSummary> tails, int axis, int remainingLength) {
			NativeTupleHashSummary result = null;
			for(int option = 0; option < axes.get(axis).size(); option++) {
				int optionLength = optionLengths.get(axis)[option];
				if(optionLength > remainingLength)
					continue;
				NativeTupleHashSummary tail = tails.get(remainingLength - optionLength);
				if(tail == null)
					continue;
				CandidateRealizationInputBinding binding = axes.get(axis).get(option);
				PlacementAnalysis.NormalizedText text = textContext.binding(binding);
				int chunkLength = text.length();
				int chunkHash = text.hashCode();
				if(axis + 1 < axes.size()) {
					chunkHash = chunkHash * 961 + ", ".hashCode();
					chunkLength += 2;
				}
				int authorityPower = pow31(chunkLength) * tail.authorityCharPower();
				int bindingPower = 31 * tail.bindingsPower();
				NativeTupleHashSummary block = new NativeTupleHashSummary(tail.count(),
					tail.sequencePower(), tail.geometric(), authorityPower,
					chunkHash * tail.authorityCharPower() * tail.geometric()
						+ tail.weightedAuthoritySuffix(), bindingPower,
					binding.hashCode() * tail.bindingsPower() * tail.geometric()
						+ tail.weightedBindingsSuffix());
				result = result == null ? block : result.append(block);
			}
			return result;
		}

		private static int pow31(int exponent) {
			int result = 1;
			int base = 31;
			for(int remaining = exponent; remaining != 0; remaining >>>= 1) {
				if((remaining & 1) != 0)
					result *= base;
				base *= base;
			}
			return result;
		}

		private record NativeTupleHashSummary(int count, int sequencePower, int geometric,
			int authorityCharPower, int weightedAuthoritySuffix,
			int bindingsPower, int weightedBindingsSuffix) {
			private NativeTupleHashSummary append(NativeTupleHashSummary suffix) {
				if(authorityCharPower != suffix.authorityCharPower
					|| bindingsPower != suffix.bindingsPower)
					throw new IllegalStateException("Native hash bucket has inconsistent tuple width");
				return new NativeTupleHashSummary(Math.addExact(count, suffix.count),
					sequencePower * suffix.sequencePower,
					geometric * suffix.sequencePower + suffix.geometric,
					authorityCharPower,
					weightedAuthoritySuffix * suffix.sequencePower
						+ suffix.weightedAuthoritySuffix,
					bindingsPower,
					weightedBindingsSuffix * suffix.sequencePower
						+ suffix.weightedBindingsSuffix);
			}
		}

		private List<CandidateRealizationInputBinding> bindingsAt(
			List<List<CandidateRealizationInputBinding>> axes, int size, int ordinal) {
			Objects.checkIndex(ordinal, size);
			if(rowMajor)
				return NativeSupportProduct.rowMajorBindingsAt(axes, size, ordinal);
			int bindingLength = -1;
			int withinLength = ordinal;
			for(NativeCanonicalLengthBucket bucket : orderedLengths) {
				if(withinLength < bucket.count()) {
					bindingLength = bucket.bindingLength();
					break;
				}
				withinLength -= bucket.count();
			}
			if(bindingLength < 0)
				throw new IllegalStateException("Native support ordinal exceeds canonical length index");

			return bindingsAtLengthRank(axes, bindingLength, withinLength);
		}

		private List<CandidateRealizationInputBinding> bindingsAtLengthRank(
			List<List<CandidateRealizationInputBinding>> axes, int bindingLength, int rank) {
			Integer bucketCount = bindingLengthCounts().get(bindingLength);
			if(bucketCount == null || rank < 0 || rank >= bucketCount)
				throw new IndexOutOfBoundsException("Native binding-length rank outside relation");
			if(rowMajor)
				return NativeSupportProduct.rowMajorBindingsAt(axes, bucketCount, rank);
			int withinLength = rank;
			CandidateRealizationInputBinding[] selected =
				new CandidateRealizationInputBinding[axes.size()];
			int remainingLength = bindingLength;
			for(int axis = 0; axis < axes.size(); axis++) {
				boolean found = false;
				for(int optionIndex = 0; optionIndex < axes.get(axis).size(); optionIndex++) {
					CandidateRealizationInputBinding option = axes.get(axis).get(optionIndex);
					int optionLength = optionLengths.get(axis)[optionIndex];
					int suffixLength = remainingLength - optionLength;
					int count = suffixLength < 0 ? 0
						: suffixLengthCounts.get(axis + 1).getOrDefault(suffixLength, 0);
					if(withinLength >= count) {
						withinLength -= count;
						continue;
					}
					selected[axis] = option;
					remainingLength = suffixLength;
					found = true;
					break;
				}
				if(!found)
					throw new IllegalStateException("Native canonical support index is inconsistent");
			}
			if(remainingLength != 0 || withinLength != 0)
				throw new IllegalStateException("Native canonical support member was not fully consumed");
			return PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				java.util.Arrays.asList(selected), "native support product binding");
		}

		private int bindingLength(List<List<CandidateRealizationInputBinding>> axes,
			List<CandidateRealizationInputBinding> bindings) {
			if(bindings.size() != axes.size())
				return -1;
			int length = 0;
			for(int axis = 0; axis < axes.size(); axis++) {
				int option = identityIndexOf(axes.get(axis), bindings.get(axis));
				if(option < 0)
					return -1;
				length = Math.addExact(length, optionLengths.get(axis)[option]);
			}
			return length;
		}

		private int rankWithinBindingLength(List<List<CandidateRealizationInputBinding>> axes,
			List<CandidateRealizationInputBinding> bindings) {
			int ordinal = ordinalOfBindings(axes, bindings);
			int length = bindingLength(axes, bindings);
			if(ordinal < 0 || length < 0)
				return -1;
			for(NativeCanonicalLengthBucket bucket : orderedLengths) {
				if(bucket.bindingLength() == length)
					return ordinal;
				ordinal -= bucket.count();
			}
			return -1;
		}

		private int ordinalOfBindings(List<List<CandidateRealizationInputBinding>> axes,
			List<CandidateRealizationInputBinding> bindings) {
			if(bindings.size() != axes.size())
				return -1;
			int[] selectedOptions = new int[axes.size()];
			int bindingLength = 0;
			for(int axis = 0; axis < axes.size(); axis++) {
				selectedOptions[axis] = identityIndexOf(axes.get(axis), bindings.get(axis));
				if(selectedOptions[axis] < 0)
					return -1;
				bindingLength = Math.addExact(bindingLength,
					optionLengths.get(axis)[selectedOptions[axis]]);
			}
			if(rowMajor) {
				int ordinal = 0;
				for(int axis = 0; axis < axes.size(); axis++)
					ordinal = Math.addExact(Math.multiplyExact(ordinal, axes.get(axis).size()),
						selectedOptions[axis]);
				return ordinal;
			}

			int rank = 0;
			for(NativeCanonicalLengthBucket bucket : orderedLengths) {
				if(bucket.bindingLength() == bindingLength)
					break;
				rank = Math.addExact(rank, bucket.count());
			}
			int remainingLength = bindingLength;
			for(int axis = 0; axis < axes.size(); axis++) {
				for(int option = 0; option < selectedOptions[axis]; option++) {
					int suffixLength = remainingLength - optionLengths.get(axis)[option];
					if(suffixLength >= 0)
						rank = Math.addExact(rank,
							suffixLengthCounts.get(axis + 1).getOrDefault(suffixLength, 0));
				}
				remainingLength -= optionLengths.get(axis)[selectedOptions[axis]];
			}
			return remainingLength == 0 ? rank : -1;
		}

		private int countBeforeAtLength(List<List<CandidateRealizationInputBinding>> axes,
			List<CandidateRealizationInputBinding> bindings, int bindingLength) {
			if(bindings.size() != axes.size())
				return 0;
			int rank = 0;
			int remainingLength = bindingLength;
			PlacementAnalysis.NormalizedTextContext textContext =
				new PlacementAnalysis.NormalizedTextContext();
			java.util.Comparator<PlacementAnalysis.NormalizedText> comparator =
				PlacementAnalysis.normalizedTextComparator();
			for(int axis = 0; axis < axes.size(); axis++) {
				CandidateRealizationInputBinding selected = bindings.get(axis);
				boolean continued = false;
				for(int option = 0; option < axes.get(axis).size(); option++) {
					CandidateRealizationInputBinding candidate = axes.get(axis).get(option);
					int order = comparator.compare(
						textContext.binding(candidate), textContext.binding(selected));
					if(order < 0) {
						int suffixLength = remainingLength - optionLengths.get(axis)[option];
						if(suffixLength >= 0)
							rank = Math.addExact(rank,
								suffixCount(axes, axis + 1, suffixLength));
						continue;
					}
					if(order == 0 && candidate == selected) {
						remainingLength -= optionLengths.get(axis)[option];
						continued = true;
					}
					break;
				}
				if(!continued)
					return rank;
			}
			return rank;
		}

		private int suffixCount(List<List<CandidateRealizationInputBinding>> axes,
			int axis, int length) {
			if(!rowMajor)
				return suffixLengthCounts.get(axis).getOrDefault(length, 0);
			int expected = 0;
			int count = 1;
			for(int cursor = axis; cursor < axes.size(); cursor++) {
				expected = Math.addExact(expected, optionLengths.get(cursor)[0]);
				count = Math.multiplyExact(count, axes.get(cursor).size());
			}
			return expected == length ? count : 0;
		}

		private static int identityIndexOf(List<?> values, Object selected) {
			for(int index = 0; index < values.size(); index++)
				if(values.get(index) == selected)
					return index;
			return -1;
		}
	}

	private record NativeCanonicalLengthBucket(int bindingLength, int count,
		String lengthPrefix) { }

	private static final class CandidateSupportTemplateProduct
		extends AbstractList<CandidateSupportTemplate> implements java.util.RandomAccess {
		private final DurableAnchorKey outputWorkerPoolWitness;
		private final boolean exactPartitionRanges;
		private final List<List<CandidateRealizationInputBinding>> axes;
		private final int size;

		private CandidateSupportTemplateProduct(DurableAnchorKey outputWorkerPoolWitness,
			boolean exactPartitionRanges, List<List<CandidateRealizationInputBinding>> axes,
			int size) {
			this.outputWorkerPoolWitness = outputWorkerPoolWitness;
			this.exactPartitionRanges = exactPartitionRanges;
			this.axes = axes;
			this.size = size;
		}

		private static CandidateSupportTemplateProduct tryCreate(
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			List<List<CandidateRealizationInputBinding>> suppliedAxes) {
			List<List<CandidateRealizationInputBinding>> axes = suppliedAxes.stream()
				.map(List::copyOf).toList();
			// Zero-input native support is staging authority, not a grounded product.
			// Keep its established exact path and fixed-point treatment.
			if(axes.isEmpty())
				return null;
			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			long cardinality = 1L;
			int previousInputPosition = -1;
			for(List<CandidateRealizationInputBinding> axis : axes) {
				if(axis.isEmpty())
					return null;
				int inputPosition = axis.get(0).inputPosition();
				if(inputPosition <= previousInputPosition)
					return null;
				previousInputPosition = inputPosition;
				CompiledHopKey owner = axis.get(0).source().rule().parentOccurrence();
				Set<CandidateRealizationReference> exactSources = new java.util.HashSet<>();
				CandidateRealizationInputBinding previous = null;
				for(CandidateRealizationInputBinding binding : axis) {
					if(binding.kind() != CandidateInputBindingKind.DIRECT
						|| binding.inputPosition() != inputPosition
						|| binding.source().rule().parentOccurrence() != owner
						|| !exactSources.add(binding.source())
						|| previous != null && previous.compareTo(binding) >= 0)
						return null;
					previous = binding;
				}
				// Repeated owners are correlated by one physical source decision. Keep
				// the exact recursive enumerator until that correlation is represented.
				if(!owners.add(owner))
					return null;
				if(cardinality > Integer.MAX_VALUE / axis.size())
					return null;
				cardinality *= axis.size();
			}
			return new CandidateSupportTemplateProduct(outputWorkerPoolWitness,
				exactPartitionRanges, axes, (int)cardinality);
		}

		private static CandidateSupportTemplateProduct tryCreateUnion(
			DurableAnchorKey outputWorkerPoolWitness, boolean exactPartitionRanges,
			Set<List<List<CandidateRealizationInputBinding>>> descriptors) {
			if(descriptors.isEmpty())
				return null;
			if(descriptors.size() == 1) {
				List<List<CandidateRealizationInputBinding>> axes = descriptors.iterator().next().stream()
					.map(axis -> axis.stream().sorted().toList()).toList();
				return tryCreate(outputWorkerPoolWitness, exactPartitionRanges, axes);
			}
			int axisCount = descriptors.iterator().next().size();
			if(axisCount == 0)
				return null;
			List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>(axisCount);
			for(int axis = 0; axis < axisCount; axis++)
				axes.add(new ArrayList<>());
			for(List<List<CandidateRealizationInputBinding>> descriptor : descriptors) {
				if(descriptor.size() != axisCount)
					return null;
				for(int axis = 0; axis < axisCount; axis++) {
					List<CandidateRealizationInputBinding> singleton = descriptor.get(axis);
					if(singleton.size() != 1)
						return null;
					CandidateRealizationInputBinding option = singleton.get(0);
					boolean found = false;
					for(CandidateRealizationInputBinding retained : axes.get(axis))
						if(retained.equals(option)) {
							if(retained.source().rule().parentOccurrence()
								!= option.source().rule().parentOccurrence())
								return null;
							found = true;
							break;
						}
					if(!found)
						axes.get(axis).add(option);
				}
			}
			for(List<CandidateRealizationInputBinding> axis : axes)
				axis.sort(PlacementAnalysis.canonicalComparator());
			CandidateSupportTemplateProduct product = tryCreate(outputWorkerPoolWitness,
				exactPartitionRanges, axes);
			return product != null && product.size() == descriptors.size() ? product : null;
		}

		@Override public int size() { return size; }
		@Override public CandidateSupportTemplate get(int ordinal) {
			return new CandidateSupportTemplate(outputWorkerPoolWitness,
				exactPartitionRanges, NativeSupportProduct.rowMajorBindingsAt(axes, size, ordinal));
		}

		private NativeSupportProduct nativeProduct(DurableAnchorKey externalSeed) {
			return nativeProduct(externalSeed, NativeSupportProduct.MAX_CANONICAL_LENGTH_STATES,
				NativeSupportProduct.MAX_CANONICAL_LENGTH_TRANSITIONS);
		}

		private NativeSupportProduct nativeProduct(DurableAnchorKey externalSeed,
			int maximumLengthStates, long maximumLengthTransitions) {
			NativeCanonicalProductIndex canonicalIndex = NativeCanonicalProductIndex.tryCreate(
				externalSeed, outputWorkerPoolWitness, exactPartitionRanges,
				axes, maximumLengthStates, maximumLengthTransitions);
			return canonicalIndex == null ? null : new NativeSupportProduct(externalSeed,
				outputWorkerPoolWitness, exactPartitionRanges, axes, size, canonicalIndex);
		}

		private long estimatedRetainedBytes() {
			long options = axes.stream().mapToLong(List::size).sum();
			return 96L + 32L * axes.size() + 16L * options;
		}
	}

	private static final class CandidateConditionalSupportTemplateProduct
		extends AbstractList<CandidateSupportTemplate> implements java.util.RandomAccess {
		private final CandidateSupportTemplateProduct base;
		private final List<List<CandidateRealizationInputBinding>> exactAxes;
		private final int size;
		private CandidateConditionalSupportTemplateProduct(CandidateSupportTemplateProduct base,
			List<List<CandidateRealizationInputBinding>> exactAxes, int size) {
			this.base = base;
			this.exactAxes = exactAxes;
			this.size = size;
		}
		private static CandidateConditionalSupportTemplateProduct tryCreateUnion(
			DurableAnchorKey witness, boolean exactRanges,
			Set<List<List<CandidateRealizationInputBinding>>> descriptors) {
			if(descriptors.size() < 2)
				return null;
			int axes = descriptors.iterator().next().size();
			if(axes < 2)
				return null;
			List<List<CandidateRealizationInputBinding>> union = new ArrayList<>(axes);
			for(int axis = 0; axis < axes; axis++) {
				List<CandidateRealizationInputBinding> values = new ArrayList<>();
				for(var descriptor : descriptors) {
					if(descriptor.size() != axes)
						return null;
					for(var value : descriptor.get(axis))
						if(values.stream().noneMatch(retained ->
							NativeSupportProduct.sameExactBindingAuthority(retained, value)))
							values.add(value);
				}
				values.sort(PlacementAnalysis.canonicalComparator());
				union.add(List.copyOf(values));
			}
			List<List<CandidateRealizationInputBinding>> exact = new ArrayList<>(
				Collections.nCopies(axes, null));
			int cursor = 0;
			for(var descriptor : descriptors) {
				int pivot = -1;
				for(int axis = cursor; axis < axes; axis++)
					if(!sameAuthorityAxis(descriptor.get(axis), union.get(axis))) {
						pivot = axis;
						break;
					}
				if(pivot < 0)
					return null;
				for(int axis = 0; axis < pivot; axis++) {
					if(exact.get(axis) == null)
						exact.set(axis, union.get(axis));
					if(!sameAuthorityAxis(descriptor.get(axis), exact.get(axis)))
						return null;
				}
				for(int axis = pivot + 1; axis < axes; axis++)
					if(!sameAuthorityAxis(descriptor.get(axis), union.get(axis)))
						return null;
				List<CandidateRealizationInputBinding> inexact = descriptor.get(pivot);
				List<CandidateRealizationInputBinding> exactAxis = union.get(pivot).stream()
					.filter(binding -> inexact.stream().noneMatch(value ->
						NativeSupportProduct.sameExactBindingAuthority(value, binding))).toList();
				if(exactAxis.isEmpty())
					return null;
				exact.set(pivot, exactAxis);
				cursor = pivot + 1;
			}
			for(int axis = 0; axis < axes; axis++)
				if(exact.get(axis) == null)
					exact.set(axis, union.get(axis));
			CandidateSupportTemplateProduct base = CandidateSupportTemplateProduct.tryCreate(
				witness, exactRanges, union);
			CandidateSupportTemplateProduct excluded = CandidateSupportTemplateProduct.tryCreate(
				witness, exactRanges, exact);
			if(base == null || excluded == null || excluded.size() >= base.size())
				return null;
			return new CandidateConditionalSupportTemplateProduct(base,
				exact.stream().map(List::copyOf).toList(), base.size() - excluded.size());
		}
		private static boolean sameAuthorityAxis(
			List<CandidateRealizationInputBinding> left,
			List<CandidateRealizationInputBinding> right) {
			if(left.size() != right.size())
				return false;
			for(int index = 0; index < left.size(); index++)
				if(!NativeSupportProduct.sameExactBindingAuthority(
					left.get(index), right.get(index)))
					return false;
			return true;
		}
		@Override public int size() { return size; }
		private long estimatedRetainedBytes() {
			long bytes = base.estimatedRetainedBytes() + 48L + 32L * exactAxes.size();
			for(List<CandidateRealizationInputBinding> axis : exactAxes)
				bytes = Math.addExact(bytes, 8L * axis.size());
			return bytes;
		}
		private boolean hasRealizableOwnerBinding(CompiledHopKey owner) {
			for(int axis = 0; axis < base.axes.size(); axis++) {
				for(CandidateRealizationInputBinding binding : base.axes.get(axis)) {
					if(binding.source().rule().parentOccurrence() != owner)
						continue;
					if(NativeCanonicalProductIndex.identityIndexOf(
						exactAxes.get(axis), binding) < 0)
						return true;
					for(int other = 0; other < base.axes.size(); other++)
						if(other != axis
							&& base.axes.get(other).size() > exactAxes.get(other).size())
							return true;
				}
			}
			return false;
		}
		@Override public CandidateSupportTemplate get(int ordinal) {
			Objects.checkIndex(ordinal, size);
			int axes = base.axes.size();
			int[] suffixAll = new int[axes + 1];
			int[] suffixExact = new int[axes + 1];
			suffixAll[axes] = suffixExact[axes] = 1;
			for(int axis = axes - 1; axis >= 0; axis--) {
				suffixAll[axis] = Math.multiplyExact(base.axes.get(axis).size(), suffixAll[axis + 1]);
				suffixExact[axis] = Math.multiplyExact(exactAxes.get(axis).size(), suffixExact[axis + 1]);
			}
			List<CandidateRealizationInputBinding> selected = new ArrayList<>(axes);
			boolean prefixExact = true;
			int remaining = ordinal;
			for(int axis = 0; axis < axes; axis++) {
				boolean found = false;
				for(CandidateRealizationInputBinding option : base.axes.get(axis)) {
					boolean optionExact = NativeCanonicalProductIndex.identityIndexOf(
						exactAxes.get(axis), option) >= 0;
					int count = !prefixExact || !optionExact ? suffixAll[axis + 1]
						: suffixAll[axis + 1] - suffixExact[axis + 1];
					if(remaining >= count) {
						remaining -= count;
						continue;
					}
					selected.add(option);
					prefixExact &= optionExact;
					found = true;
					break;
				}
				if(!found)
					throw new IllegalStateException("Conditional support template index is inconsistent");
			}
			return new CandidateSupportTemplate(base.outputWorkerPoolWitness,
				base.exactPartitionRanges, PlacementAnalysis.sharedAlreadyCanonicalComparableList(
					selected, "conditional native support template binding"));
		}
	}

	private static final class NativeContinuityProofProduct
		extends AbstractList<NativeContinuityProof> implements java.util.RandomAccess {
		private final DurableAnchorKey externalSeed;
		private final CandidateSupportTemplateProduct product;
		private final CandidateConditionalSupportTemplateProduct conditional;
		private final NativeSupportProduct nativeProduct;

		private NativeContinuityProofProduct(DurableAnchorKey externalSeed,
			CandidateSupportTemplateProduct product) {
			this.externalSeed = externalSeed;
			this.product = product;
			this.conditional = null;
			this.nativeProduct = product.nativeProduct(externalSeed);
		}

		private NativeContinuityProofProduct(DurableAnchorKey externalSeed,
			CandidateConditionalSupportTemplateProduct conditional) {
			this.externalSeed = externalSeed;
			this.product = conditional.base;
			this.conditional = conditional;
			NativeSupportProduct base = product.nativeProduct(externalSeed);
			this.nativeProduct = base == null ? null
				: NativeSupportProduct.tryCreateExactComplement(base, conditional.exactAxes);
			if(nativeProduct == null)
				throw new IllegalArgumentException("Conditional native proof product exceeds index bounds");
		}

		@Override public int size() { return conditional == null ? product.size() : conditional.size(); }

		private long estimatedPublicMemoBytes() {
			// Retain the proof wrapper, template product and outer immutable axis array.
			// Each axis owns one immutable list/array and every option owns the direct
			// binding record created for this relation. Referenced candidate authority
			// remains owned by the immutable candidate inventory and is not counted again.
			long bytes = 192L;
			for(List<CandidateRealizationInputBinding> axis : product.axes) {
				long retained = 48L + 48L * axis.size();
				if(Long.MAX_VALUE - bytes < retained)
					return Long.MAX_VALUE;
				bytes += retained;
			}
			if(conditional != null)
				for(List<CandidateRealizationInputBinding> axis : conditional.exactAxes)
					bytes = Math.addExact(bytes, 32L + 8L * axis.size());
			return bytes;
		}

		@Override public NativeContinuityProof get(int ordinal) {
			// Public proofs preserve the legacy Cartesian encounter order. The separate
			// nativeProduct remains the canonical UTF-16 rank used by published support
			// clauses and exact member selection.
			CandidateSupportTemplate template = conditional == null
				? product.get(ordinal) : conditional.get(ordinal);
			return new NativeContinuityProof(externalSeed,
				template.outputWorkerPoolWitness, template.exactPartitionRanges,
				template.immediateBindings, template.canonicalOrderingSuffixLength);
		}
		private NativeSupportProduct nativeProduct() {
			return nativeProduct;
		}
	}
	private record ComputedCandidateSupport(List<CandidateSupportTemplate> templates,
		Set<CompiledHopKey> occurrences, boolean rootIndependent) { }
	private record SupportMemoEntry(CandidateRealizationReference root,
		List<CandidateSupportTemplate> templates, Set<CompiledHopKey> occurrences,
		long estimatedBytes, boolean rootIndependent) {
		private SupportMemoEntry {
			if(!(templates instanceof CandidateSupportTemplateProduct)
				&& !(templates instanceof CandidateConditionalSupportTemplateProduct)) {
				PlacementAnalysis.NormalizedTextContext textContext =
					new PlacementAnalysis.NormalizedTextContext();
				List<CanonicalSupportTemplate> canonical = new ArrayList<>(templates.size());
				for(CandidateSupportTemplate template : templates) {
					PlacementAnalysis.NormalizedText suffix = supportTemplateOrderingText(
						template, textContext);
					canonical.add(new CanonicalSupportTemplate(new CandidateSupportTemplate(
						template.outputWorkerPoolWitness, template.exactPartitionRanges,
						template.immediateBindings, suffix.length()), suffix));
				}
				java.util.Comparator<PlacementAnalysis.NormalizedText> comparator =
					PlacementAnalysis.normalizedTextComparator();
				canonical.sort((left, right) -> comparator.compare(left.orderingText, right.orderingText));
				templates = canonical.stream().map(CanonicalSupportTemplate::template).toList();
			}
			occurrences = Collections.unmodifiableSet(occurrences);
		}
	}
	private record CanonicalSupportTemplate(CandidateSupportTemplate template,
		PlacementAnalysis.NormalizedText orderingText) { }
	private record AcyclicComponentSummary(List<SelectedCandidateProof> supportedAlternatives,
		Set<CompiledHopKey> occurrences, long retainedStates) {
		private AcyclicComponentSummary {
			supportedAlternatives = List.copyOf(supportedAlternatives);
			Set<CompiledHopKey> immutableOccurrences =
				Collections.newSetFromMap(new IdentityHashMap<>());
			immutableOccurrences.addAll(occurrences);
			occurrences = Collections.unmodifiableSet(immutableOccurrences);
		}
	}
	private record AcyclicComponentFootprint(Set<CompiledHopKey> occurrences,
		long retainedStates) { }
	private static final class AcyclicSummaryRowKey {
		private final CompiledHopKey owner;
		private final CandidateRealizationReference realization;
		private final NativePoolWitness witness;
		private final int hashCode;

		private AcyclicSummaryRowKey(SelectedCandidateProof row) {
			realization = row.realization;
			owner = realization == null ? null : realization.rule().parentOccurrence();
			witness = row.witness;
			int hash = owner == null ? 1 : System.identityHashCode(owner);
			hash = 31 * hash + (realization == null ? 0 : realization.hashCode());
			hashCode = 31 * hash + (witness == null ? 0 : witness.hashCode());
		}

		private static AcyclicSummaryRowKey of(SelectedCandidateProof row) {
			return new AcyclicSummaryRowKey(row);
		}

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof AcyclicSummaryRowKey that))
				return false;
			if(realization == null || that.realization == null)
				return realization == null && that.realization == null
					&& Objects.equals(witness, that.witness);
			return owner == that.owner && realization.equals(that.realization)
				&& Objects.equals(witness, that.witness);
		}
	}
	private static final class CandidateProofTraversal {
		private final Set<CandidateProofState> active = new java.util.HashSet<>();
		private final List<CandidateProofState> completionOrder = new ArrayList<>();
		private final Map<CandidateProofState,AcyclicComponentSummary> reusedComponents =
			new java.util.HashMap<>();
		private final Map<CandidateProofState,Set<CompiledHopKey>> hiddenOwnerReadsByState =
			new java.util.HashMap<>();
		private boolean cycleDetected;
		private boolean generatedRootPublishedHistoryObserved;
		private long emptyFilteredStates;
	}

	private static final class CandidateProofState {
		private final CompiledHopKey key;
		private final CandidateRealizationReference realization;
		private final int realizationHandle;
		private final NativePoolWitness witness;
		private final boolean templateRoot;
		private final CandidateAxisGate axisGate;
		private final int hashCode;

		private CandidateProofState(CompiledHopKey key,
			CandidateRealizationReference realization, int realizationHandle,
			NativePoolWitness witness, boolean templateRoot) {
			this.key = key;
			this.realization = realization;
			this.realizationHandle = realizationHandle;
			this.witness = witness;
			this.templateRoot = templateRoot;
			axisGate = null;
			int hash = 31 * System.identityHashCode(key) + realizationHandle;
			hash = 31 * hash + witness.hashCode();
			hashCode = 31 * hash + Boolean.hashCode(templateRoot);
		}

		private CandidateProofState(CandidateAxisGate gate) {
			key = gate.owner;
			realization = null;
			realizationHandle = 0;
			witness = gate.witness;
			templateRoot = false;
			axisGate = gate;
			hashCode = System.identityHashCode(gate);
		}

		private CompiledHopKey key() { return key; }
		private CandidateRealizationReference realization() { return realization; }
		private int realizationHandle() { return realizationHandle; }
		private NativePoolWitness witness() { return witness; }
		private boolean templateRoot() { return templateRoot; }
		private CandidateAxisGate axisGate() { return axisGate; }

		@Override
		public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object other) {
			if(this == other)
				return true;
			if(!(other instanceof CandidateProofState that))
				return false;
			if(axisGate != null || that.axisGate != null)
				return axisGate == that.axisGate;
			return templateRoot == that.templateRoot && key == that.key
				&& realizationHandle == that.realizationHandle && witness.equals(that.witness);
		}
	}

	private static final class CandidateAxisGate {
		private final CompiledHopKey owner;
		private final int inputPosition;
		private final NativePoolWitness witness;
		private final List<SelectedCandidateProof> alternatives;

		private CandidateAxisGate(CompiledHopKey owner, int inputPosition,
			NativePoolWitness witness, List<SelectedCandidateProof> alternatives) {
			this.owner = owner;
			this.inputPosition = inputPosition;
			this.witness = witness;
			this.alternatives = List.copyOf(alternatives);
		}
	}
	private record SelectedCandidateProof(CandidateRealizationReference realization,
		List<CandidateProofDependency> dependencies,
		boolean directGround, NativePoolWitness witness) { }
	private record NativeFactoredProofAlternatives(List<SelectedCandidateProof> alternatives,
		Set<CompiledHopKey> metadataOwnerReads) {
		private NativeFactoredProofAlternatives {
			alternatives = List.copyOf(alternatives);
			metadataOwnerReads = new ImmutableIdentityOwnerReads(metadataOwnerReads);
		}

		private NativeFactoredProofAlternatives(List<SelectedCandidateProof> alternatives) {
			this(alternatives, Set.of());
		}
	}

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
			Privacy privacy = privacyByKey.get(source.key());
			if(privacy == null || !ExecPlacementPolicy.requiresOriginResidency(privacy))
				continue;
			if(!broadcastCapableValueVersions.contains(source.valueVersion()))
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
			if(unary.getOp() == OpOp1._PLACEMENT)
				return fact.key().orderedInputs().size() == 1
					&& fact.key().orderedInputs().get(0).present()
					&& fact.key().orderedInputs().get(0).fType() == witness.fType;
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
				&& (unary.getOp() == OpOp1._PLACEMENT
					|| NATIVE_UNARY_ELEMWISE_OPCODES.contains(unary.getOp().toString()));
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
		NativePoolWitness witness = NativePoolWitness.from(
			anchor, this::canonicalEndpoint, structuralContext.nativeWitnessArena);
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

	/**
	 * Structural-context-local canonical witnesses. The map never evicts because
	 * cached proof/topology keys may retain its values; after the fixed bound,
	 * new shapes keep the legacy value-equality path without being retained.
	 */
	private static final class NativeWitnessArena {
		private final int maxEntries;
		private final Map<NativePoolWitness,NativePoolWitness> canonicalWitnesses = new HashMap<>();

		private NativeWitnessArena(int maxEntries) {
			this.maxEntries = maxEntries;
		}

		private NativePoolWitness intern(FType fType, List<String> endpoints,
			List<AxisInterval> partitionAxisIntervals, boolean exactPartitionRanges) {
			NativePoolWitness candidate = new NativePoolWitness(this, fType, endpoints,
				partitionAxisIntervals, exactPartitionRanges);
			NativePoolWitness canonical = canonicalWitnesses.get(candidate);
			if(canonical != null)
				return canonical;
			if(canonicalWitnesses.size() < maxEntries)
				canonicalWitnesses.put(candidate, candidate);
			return candidate;
		}

		private boolean retains(NativePoolWitness witness) {
			return canonicalWitnesses.get(witness) == witness;
		}
	}

	private static final class NativePoolWitness {
		private final NativeWitnessArena arena;
		private final FType fType;
		private final List<String> endpoints;
		private final List<AxisInterval> partitionAxisIntervals;
		private final boolean exactPartitionRanges;
		private final int hashCode;
		private NativePoolWitness dynamicPartitionRangesWitness;
		private NativePoolWitness exactPartitionRangesWitness;
		private NativePoolWitness[] retypedWitnesses;
		private NativePoolWitness[] residencyWitnesses;

		private NativePoolWitness(FType fType, List<String> endpoints,
			List<AxisInterval> partitionAxisIntervals, boolean exactPartitionRanges) {
			this(null, fType, endpoints, partitionAxisIntervals, exactPartitionRanges);
		}

		private NativePoolWitness(NativeWitnessArena arena, FType fType, List<String> endpoints,
			List<AxisInterval> partitionAxisIntervals, boolean exactPartitionRanges) {
			Objects.requireNonNull(fType, "native witness FType");
			this.arena = arena;
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
				&& hashCode == that.hashCode
				&& exactPartitionRanges == that.exactPartitionRanges && fType == that.fType
				&& endpoints.equals(that.endpoints)
				&& partitionAxisIntervals.equals(that.partitionAxisIntervals);
		}

		private static NativePoolWitness from(DurableAnchorKey anchor,
			java.util.function.Function<String,String> canonicalEndpoint, NativeWitnessArena arena) {
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
			return arena.intern(anchor.fType(), endpoints.stream().distinct().sorted().toList(),
				intervals, true);
		}

		private NativePoolWitness derived(FType derivedType, List<AxisInterval> intervals,
			boolean exactRanges) {
			return arena == null || !arena.retains(this)
				? new NativePoolWitness(derivedType, endpoints, intervals, exactRanges)
				: arena.intern(derivedType, endpoints, intervals, exactRanges);
		}

		private NativePoolWitness broadcast() {
			return derived(FType.BROADCAST, List.of(), true);
		}

		private NativePoolWitness withDynamicPartitionRanges() {
			if(!exactPartitionRanges)
				return this;
			if(fType != FType.ROW && fType != FType.COL && fType != FType.FULL)
				return this;
			if(dynamicPartitionRangesWitness == null) {
				dynamicPartitionRangesWitness = derived(fType, partitionAxisIntervals, false);
				dynamicPartitionRangesWitness.exactPartitionRangesWitness = this;
				linkKnownRetypedSiblings(this, dynamicPartitionRangesWitness);
			}
			return dynamicPartitionRangesWitness;
		}

		private NativePoolWitness withExactPartitionRanges() {
			if(exactPartitionRanges)
				return this;
			if(exactPartitionRangesWitness == null) {
				exactPartitionRangesWitness = derived(fType, partitionAxisIntervals, true);
				exactPartitionRangesWitness.dynamicPartitionRangesWitness = this;
				linkKnownRetypedSiblings(exactPartitionRangesWitness, this);
			}
			return exactPartitionRangesWitness;
		}

		private boolean matches(NativePoolWitness candidate, boolean anchorLayoutExact) {
			if(candidate == this)
				return !exactPartitionRanges || anchorLayoutExact;
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
				|| (fType == FType.COL && target == FType.ROW)) {
				NativePoolWitness cached = retypedWitness(target);
				if(cached != null)
					return cached;
				NativePoolWitness transformed = derived(
					target, partitionAxisIntervals, exactPartitionRanges);
				cacheRetypedWitness(target, transformed);
				transformed.cacheRetypedWitness(fType, this);
				if(exactPartitionRanges && dynamicPartitionRangesWitness != null) {
					NativePoolWitness dynamicTransformed = dynamicPartitionRangesWitness.retyped(target);
					transformed.dynamicPartitionRangesWitness = dynamicTransformed;
					if(dynamicTransformed != null)
						dynamicTransformed.exactPartitionRangesWitness = transformed;
				}
				else if(!exactPartitionRanges && exactPartitionRangesWitness != null) {
					NativePoolWitness exactTransformed = exactPartitionRangesWitness.retyped(target);
					transformed.exactPartitionRangesWitness = exactTransformed;
					if(exactTransformed != null)
						exactTransformed.dynamicPartitionRangesWitness = transformed;
				}
				return transformed;
			}
			return null;
		}

		private NativePoolWitness retypedForResidency(FType target) {
			NativePoolWitness exact = retyped(target);
			if(exact != null)
				return exact;
			if(target == null || target == FType.PART || target == FType.OTHER
				|| target == FType.BROADCAST || fType == FType.BROADCAST || !singleEndpoint())
				return null;
			if((fType == FType.FULL && (target == FType.ROW || target == FType.COL))
				|| (target == FType.FULL && (fType == FType.ROW || fType == FType.COL))) {
				NativePoolWitness cached = residencyWitness(target);
				if(cached != null)
					return cached;
				NativePoolWitness transformed = fType == FType.FULL
					? derived(target, List.of(), false)
					: derived(FType.FULL, List.of(), true);
				cacheResidencyWitness(target, transformed);
				return transformed;
			}
			return null;
		}

		private NativePoolWitness retypedWitness(FType target) {
			return retypedWitnesses == null ? null : retypedWitnesses[target.ordinal()];
		}

		private static void linkKnownRetypedSiblings(NativePoolWitness exact, NativePoolWitness dynamic) {
			for(FType target : FType.values()) {
				NativePoolWitness exactRetyped = exact.retypedWitness(target);
				NativePoolWitness dynamicRetyped = dynamic.retypedWitness(target);
				if(exactRetyped == null && dynamicRetyped != null)
					exactRetyped = dynamicRetyped.exactPartitionRangesWitness;
				if(dynamicRetyped == null && exactRetyped != null)
					dynamicRetyped = exactRetyped.dynamicPartitionRangesWitness;
				if(exactRetyped == null || dynamicRetyped == null)
					continue;
				exact.cacheRetypedWitness(target, exactRetyped);
				exactRetyped.cacheRetypedWitness(exact.fType, exact);
				dynamic.cacheRetypedWitness(target, dynamicRetyped);
				dynamicRetyped.cacheRetypedWitness(dynamic.fType, dynamic);
				exactRetyped.dynamicPartitionRangesWitness = dynamicRetyped;
				dynamicRetyped.exactPartitionRangesWitness = exactRetyped;
			}
		}

		private void cacheRetypedWitness(FType target, NativePoolWitness witness) {
			if(retypedWitnesses == null)
				retypedWitnesses = new NativePoolWitness[FType.values().length];
			retypedWitnesses[target.ordinal()] = witness;
		}

		private NativePoolWitness residencyWitness(FType target) {
			return residencyWitnesses == null ? null : residencyWitnesses[target.ordinal()];
		}

		private void cacheResidencyWitness(FType target, NativePoolWitness witness) {
			if(residencyWitnesses == null)
				residencyWitnesses = new NativePoolWitness[FType.values().length];
			residencyWitnesses[target.ordinal()] = witness;
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
		Map<K,V> copy = new IdentityHashMap<>(source.size());
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
