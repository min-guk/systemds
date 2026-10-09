/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Analysis-scoped aggregate work counters for the neutral placement search space.
 *
 * <p>Production construction does not create a collector, so the disabled path consists
 * only of null checks at the instrumentation sites. Opt-in diagnostics retain aggregate
 * counters plus a bounded set of full query-context keys in the owning continuity
 * instance; overflow is counted rather than sampled or used to alter planning.</p>
 */
public final class SearchSpaceMetrics {
	public SearchSpaceMetrics() { }

	public enum Phase {
		ANALYSIS,
		RELOCATION_DISCOVERY,
		RELOCATION_BINDING,
		SOURCE_PRUNING,
		LOOP_SEED_BOOKKEEPING,
		MATERIALIZATION,
		CONTEXT_OBSERVER,
		PROOF_TOPOLOGY,
		PROOF_OVERLAY,
		PROOF_DEPENDENCY_PRUNING,
		// Retained for historical diagnostic schema compatibility; no execution phase.
		PROOF_GROUNDING,
		SUPPORT_PRODUCT_RELATION_MATERIALIZATION,
		PUBLIC_PROOF_MATERIALIZATION,
		CLAUSE_MERGE_CANONICALIZATION,
		CLOSURE_REPLAY,
		DIRECT_BINDING,
		DIRECT_PROOF_CALL,
		DIRECT_PROOF_CONSUMPTION,
		DIRECT_EMISSION_CANONICALIZATION,
		DIRECT_METADATA_DEPENDENCIES,
		PRIVACY_CLOSURE,
		PRIVACY_EVIDENCE,
		CFG_REPLAY,
		PHYSICAL_REBUILD,
		PUBLICATION_VALIDATION,
		RECEIPT_RANK_CONSUMER_PREPARATION
	}

	/**
	 * Generator invocations, except CP_FAMILY which counts published families.
	 * EXACT_RULE_RESIDUAL classifies rule evaluation and overlaps its MRV/CARTESIAN traversal;
	 * these counters are not disjoint and must not be summed as total invocations.
	 */
	public enum CandidateRoute { EXECUTION_RELATION, CP_FAMILY, MRV, CARTESIAN, EXACT_RULE_RESIDUAL }

	private long exactRuleCalls, evidenceReuses, evidenceOverflow;
	private long headerRequests, headerReuses, profileRequests, profileReuses;
	public record CandidateConstructionSnapshot(long exactRuleCalls, long evidenceReuses,
		long evidenceOverflow, long headerRequests, long headerReuses,
		long profileRequests, long profileReuses) { }
	public CandidateConstructionSnapshot candidateConstructionSnapshot() {
		return new CandidateConstructionSnapshot(exactRuleCalls, evidenceReuses, evidenceOverflow,
			headerRequests, headerReuses, profileRequests, profileReuses);
	}
	void recordExactRule(org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.ExactRuleDiagnostics work) {
		exactRuleCalls += work.oracleCalls();
		evidenceReuses += work.reusedEvidence();
		evidenceOverflow += work.overflowEvidence();
	}
	void recordCandidateHeader(boolean reused) { headerRequests++; if(reused) headerReuses++; }
	void recordCandidateProfile(boolean reused) { profileRequests++; if(reused) profileReuses++; }

	/**
	 * Event counts, not analysis-global distinct identities or numbers of live objects.
	 * UNIQUE counters sum identities deduplicated within each binder/invalidation invocation.
	 */
	enum DirectWork {
		FACT_VISITS, FACTS_SKIPPED_CLEAN, FACTS_SKIPPED_INELIGIBLE,
		EMISSION_VISITS, EMISSIONS_REUSED, EMISSIONS_REBUILT,
		SEEDS_BEFORE_DEDUP, SEED_RELATIONS_REQUESTED, SEED_RELATIONS_DUPLICATE,
		MULTI_SEED_BATCHES, MULTI_SEED_DISTINCT_SEEDS, MULTI_SEED_REQUESTED_RELATIONS,
		PROOFS_CONSUMED, REQUIRED_INPUT_CHECKS, BINDING_CANDIDATES_EXAMINED,
		INCOMPLETE_PROOFS, LAYOUT_CHECKS, LAYOUT_CACHE_HITS,
		MEMOIZED_NATIVE_PUBLICATION_REQUESTS, MEMOIZED_NATIVE_PUBLICATION_CACHE_HITS,
		EARLY_NATIVE_COVERAGE_PROBES, EARLY_NATIVE_COVERAGE_HITS,
		EARLY_NATIVE_COVERAGE_BUCKET_CANDIDATES, EARLY_NATIVE_COVERAGE_UNMARKED_CLAUSES,
		EARLY_NATIVE_COVERAGE_IDENTITY_REJECTS, EARLY_NATIVE_COVERAGE_AVOIDED_AUTHORITY_CHARS,
		EARLY_NATIVE_COVERAGE_INDEXED_CLAUSES, EARLY_NATIVE_DESCRIPTOR_CREATIONS,
		// Provisional metadata triggers, before final footprint certification.
		INCOMPLETE_SEED_VALUE_MAP_INCIDENCES, INCOMPLETE_PROOF_METADATA_INCIDENCES,
		INCOMPLETE_UNIQUE_OWNERS,
		METADATA_FOOTPRINT_CERTIFIED_OWNERS, METADATA_FOOTPRINT_FALLBACK_OWNERS,
		METADATA_FOOTPRINT_OWNERS_ADDED,
		METADATA_FOOTPRINT_IDENTITY_REJECTS, METADATA_FOOTPRINT_DERIVED_EDGES,
		INVALIDATION_SELF_INCIDENCES, INVALIDATION_IMMEDIATE_INCIDENCES,
		INVALIDATION_ALIAS_INCIDENCES, INVALIDATION_SUBSCRIBER_INCIDENCES,
		INVALIDATION_INCOMPLETE_TRANSITIVE_INCIDENCES, INVALIDATION_UNIQUE_EXTRA_OWNERS,
		INVALIDATION_INCOMPLETE_ONLY_EXTRA_OWNERS,
		INVALIDATION_NEW_PENDING_OWNERS, INVALIDATION_ALREADY_PENDING_OWNERS,
		INVALIDATION_INCOMPLETE_ONLY_NEW_PENDING_OWNERS,
		STATIC_NO_QUERY_NEW_PENDING_SKIPS, PRUNE_UNCHANGED_OWNER_SLOTS_SKIPPED,
		PRUNE_DEFAULT_ORDINAL_EDGES, PRUNE_DEFAULT_DENSE_RESOLUTIONS,
		TOPOLOGY_REVISION_SHARED_ROWS, TOPOLOGY_REVISION_REINDEXED_ROWS,
		GENERATED_BATCH_REUSE_HITS,
		GENERATED_BATCH_ROOT_HISTORY_REJECTIONS, GENERATED_BATCH_ROOT_BINDING_REJECTIONS,
		GENERATED_BATCH_INDEX_SATURATION,
		// Construction scans include bypassed summaries; admissions also include revision migration.
		COMPONENT_SUMMARY_SUPPORTED_ROWS_EXAMINED, COMPONENT_SUMMARY_DUPLICATE_ROWS_COLLAPSED,
		COMPONENT_SUMMARY_DISTINCT_BUDGET_BYPASSES, COMPONENT_SUMMARY_ADMITTED_ROWS,
		COMPONENT_SUMMARY_REUSE_HITS, COMPONENT_SUMMARY_REUSED_ROWS,
		INVALIDATION_INCOMPLETE_NEW_NO_BINDING_OWNERS, INVALIDATION_INCOMPLETE_NEW_COMMITTED_OWNERS,
		INVALIDATION_INCOMPLETE_NEW_CANCELLED_OWNERS, INVALIDATION_INCOMPLETE_NEW_OTHER_OWNERS,
		NO_DELTA_WAVES, NO_DELTA_ELIGIBLE_FACTS, NO_DELTA_SEED_RELATIONS_REQUESTED,
		NO_DELTA_PUBLICATION_REQUESTS,
		INITIAL_FULL_RESET_DERIVED_ANCHOR_CONTEXT, INITIAL_FULL_RESET_BASELINE,
		INITIAL_FULL_RESET_REFERENCED_OWNER_DIRTY,
		COMPONENT_FOOTPRINT_REQUESTS, COMPONENT_FOOTPRINT_REUSED_BOUNDARIES,
		COMPONENT_FOOTPRINT_REUSED_OWNERS, COMPONENT_FOOTPRINT_NEGATIVE_REPROBES_AVOIDED,
		COMPONENT_FOOTPRINT_SCHEDULES, COMPONENT_FOOTPRINT_RAW_EDGES, COMPONENT_FOOTPRINT_UNIQUE_EDGES,
		FIXED_BOUNDARY_OVERLAY_HITS, FIXED_BOUNDARY_OVERLAY_ADMISSIONS, FIXED_BOUNDARY_OVERLAY_ROWS_VISITED,
		FIXED_BOUNDARY_OVERLAY_LOOKUPS,
		PARTITIONED_COLLISION_PROOFS, PARTITIONED_RETAINED_PROOFS, PARTITIONED_SINGLETON_PROOFS,
		NATIVE_PAIR_OBSERVED, NATIVE_PAIR_FIRST, NATIVE_PAIR_SAME_SEED, NATIVE_PAIR_DISTINCT_SEED,
		NATIVE_PAIR_AXES_CHANGED, NATIVE_PAIR_METADATA_CHANGED, NATIVE_PAIR_BUDGET_UNKNOWN,
		NATIVE_PAIR_LOGICAL_MEMBERS, NATIVE_PAIR_DISTINCT_SEED_LOGICAL_MEMBERS
	}

	/** First observed publication blocker, not a speculative set of overlapping causes. */
	enum NativePublicationOutcome {
		NOT_RECOMPUTED, NO_PRODUCT, MULTI_SEED, NO_GROUNDED_PREPARATION,
		CONFLICTING_AUTHORITY, ZERO_AXIS, REQUIRED_INPUTS, MIXED_EXACTNESS,
		PRODUCT_RECONSTRUCTION, DURABLE_OUTPUT, SINGLETON_OUTPUT, OUTPUT_COLLISION,
		RETAINED_UNION, COVERED_RETAINED, PUBLISHED,
		MIXED_EXACTNESS_SINGLE_AXIS, MIXED_EXACTNESS_MULTIPLE_AXES, PARTITIONED
	}

	record NativePublicationCount(NativePublicationOutcome outcome, long queries,
		long logicalProofs, long consumedProofs, long emptyResults, long singletonResults,
		long smallProducts, long largeProducts) { }

	// Fixed-size diagnostics: queries, logical size, actual iterations, and 0/1/2-3/4+ buckets.
	private final long[][] nativePublication = new long[NativePublicationOutcome.values().length][7];

	void recordNativePublication(NativePublicationOutcome outcome, int logicalProofs) {
		if(logicalProofs < 0)
			throw new IllegalArgumentException("Negative native publication cardinality");
		long[] row = nativePublication[outcome.ordinal()];
		row[0]++;
		row[1] += logicalProofs;
		row[logicalProofs == 0 ? 3 : logicalProofs == 1 ? 4 : logicalProofs < 4 ? 5 : 6]++;
	}

	void recordNativePublicationProofConsumed(NativePublicationOutcome outcome) {
		nativePublication[outcome.ordinal()][2]++;
	}

	List<NativePublicationCount> nativePublicationSnapshot() {
		List<NativePublicationCount> result = new ArrayList<>(nativePublication.length);
		for(NativePublicationOutcome outcome : NativePublicationOutcome.values()) {
			long[] row = nativePublication[outcome.ordinal()];
			result.add(new NativePublicationCount(outcome, row[0], row[1], row[2],
				row[3], row[4], row[5], row[6]));
		}
		return List.copyOf(result);
	}

	private static final int CANDIDATE_OPCODE_LIMIT = 64;
	private final Map<String,long[]> candidateRoutes = new LinkedHashMap<>();
	private final long[] candidateRouteTotals = new long[CandidateRoute.values().length];
	private long candidateRouteOverflow;
	private final long[] directWork = new long[DirectWork.values().length];
	private long factorizedRelocationProducts;
	private long factorizedRelocationLogicalLeaves;
	private long explicitRelocationLeaves;

	void recordCandidateRoute(CandidateRoute route, String opcode) {
		candidateRouteTotals[route.ordinal()]++;
		// Hop diagnostics append variable names/literal values after the opcode.
		// Those are instances, not operation kinds, and must not exhaust this table.
		int separator = opcode.indexOf(' ');
		if(separator >= 0)
			opcode = opcode.substring(0, separator);
		long[] counts = candidateRoutes.get(opcode);
		if(counts == null) {
			if(candidateRoutes.size() == CANDIDATE_OPCODE_LIMIT) {
				candidateRouteOverflow++;
				return;
			}
			counts = new long[CandidateRoute.values().length];
			candidateRoutes.put(opcode, counts);
		}
		counts[route.ordinal()]++;
	}

	public record CandidateRouteCount(String opcode, CandidateRoute route, long calls) { }

	public List<CandidateRouteCount> candidateRouteSnapshot() {
		List<CandidateRouteCount> result = new ArrayList<>();
		candidateRoutes.forEach((opcode, counts) -> {
			for(CandidateRoute route : CandidateRoute.values())
				if(counts[route.ordinal()] != 0)
					result.add(new CandidateRouteCount(opcode, route, counts[route.ordinal()]));
		});
		return List.copyOf(result);
	}

	/** Exact route totals remain available even when the per-opcode table overflows. */
	public Map<CandidateRoute,Long> candidateRouteTotalsSnapshot() {
		Map<CandidateRoute,Long> result = new LinkedHashMap<>();
		for(CandidateRoute route : CandidateRoute.values())
			result.put(route, candidateRouteTotals[route.ordinal()]);
		return java.util.Collections.unmodifiableMap(result);
	}

	long candidateRouteOverflow() { return candidateRouteOverflow; }
	void recordDirectWork(DirectWork work) { recordDirectWork(work, 1); }
	void recordDirectWork(DirectWork work, long count) {
		directWork[work.ordinal()] += count;
		// A large result-consumption loop may not enter another timed child phase.
		// Check the existing wall-clock throttle only once per 1024 consumed proofs.
		if(liveMetrics && work == DirectWork.PROOFS_CONSUMED
			&& (directWork[work.ordinal()] & 1023) == 0)
			emitLiveMetrics(false);
	}
	long directWorkCount(DirectWork work) { return directWork[work.ordinal()]; }
	Map<DirectWork,Long> directBindingSnapshot() {
		Map<DirectWork,Long> result = new LinkedHashMap<>();
		for(DirectWork work : DirectWork.values())
			result.put(work, directWork[work.ordinal()]);
		return java.util.Collections.unmodifiableMap(result);
	}

	/** Separates logical factorized cardinality from actually enumerated relocation leaves. */
	record RelocationStorageWork(long factorizedProducts, long factorizedLogicalLeaves,
		long explicitLeaves) { }
	RelocationStorageWork relocationStorageSnapshot() {
		return new RelocationStorageWork(factorizedRelocationProducts,
			factorizedRelocationLogicalLeaves, explicitRelocationLeaves);
	}

	enum ContextObservationResult { FIRST, REPEATED, OVERFLOW }

	record ContextObservation(ContextObservationResult result,
		long verifiedHashCollisions) { }

	/** Bounded full-key observer; hash buckets are always verified with equals. */
	static final class BoundedContextObserver<K> {
		private final int limit;
		private final Map<Integer,List<K>> buckets = new HashMap<>();
		private int size;

		BoundedContextObserver(int limit) {
			this.limit = Math.max(0, limit);
		}

		ContextObservation observe(K key) {
			List<K> bucket = buckets.get(key.hashCode());
			long collisions = 0;
			if(bucket != null)
				for(K retained : bucket) {
					if(retained.equals(key))
						return new ContextObservation(ContextObservationResult.REPEATED, collisions);
					collisions++;
				}
			if(size >= limit)
				return new ContextObservation(ContextObservationResult.OVERFLOW, collisions);
			if(bucket == null) {
				bucket = new ArrayList<>();
				buckets.put(key.hashCode(), bucket);
			}
			bucket.add(key);
			size++;
			return new ContextObservation(ContextObservationResult.FIRST, collisions);
		}
	}

	private long fixedPointPasses;
	private long cfgRefinementPasses;
	private long functionBoundaryPasses;
	private long semanticPasses;
	private long publicationPasses;
	private long directClosurePasses;
	private long directClosureStablePasses;
	private long directClosureFullPasses;
	private long proofQueries;
	private long exactContextUniqueQueries;
	private long exactContextRepeatedQueries;
	private long exactContextOverflowQueries;
	private long proofGraphsBuilt;
	private long proofStatesBuilt;
	private long proofAlternativesBuilt;
	private long proofDependencyEdgesBuilt;
	private long proofDefaultScheduleBuilds;
	private long proofDefaultScheduleHits;
	// Baseline-equivalent dependency occurrences, not physically repeated visits.
	private long proofDefaultRawSuccessorVisits;
	private long proofDefaultUniqueSuccessorVisits;
	private long proofNoEmptyDagPruningSkips;
	private long acyclicProofGraphs;
	private long cyclicProofGraphs;
	private long acyclicAlternativesRemoved;
	private long proofRowsExamined;
	private long deadStatesQueued;
	private long dependencyNotifications;
	private long alternativesRemoved;
	private long ownerCompactionElementsScanned;
	private long supportPrefixes;
	private long supportConflictPrefixes;
	private long contradictoryClausePins;
	private long supportLeaves;
	private long uniqueProofs;
	private long duplicateProofs;
	private long supportProductDescriptorsExpanded;
	private long supportProductDescriptorsReused;
	private long relocationPrefixes;
	private long relocationLeaves;
	private long relocationPeakDepth;
	private long relocationPeakPendingAssignments;
	private long privacyEmissionAllocationsAvoided;
	private long privacyTransferVisits;
	private long relocationConflictPrefixes;
	private long supportDeletionEpochs;
	private long supportIndexedRealizations;
	private long supportIndexedClauses;
	private long supportReverseIncidences;
	private long supportQueueVisits;
	void recordPrivacyEmissionAllocationAvoided() {
		privacyEmissionsSuppressed++;
		privacyEmissionAllocationsAvoided++;
	}
	void recordPrivacyTransferVisit() { privacyTransferVisits++; }
	void recordRelocationConflictPrefix() { relocationConflictPrefixes++; }
	void recordSupportDeletionWork(PlacementSupportRelations.WorklistWork work) {
		supportDeletionEpochs++;
		supportIndexedRealizations += work.indexedRealizations();
		supportIndexedClauses += work.indexedClauses();
		supportReverseIncidences += work.reverseIncidences();
		supportQueueVisits += work.queueVisits();
	}
	private long candidateRuleKeysCreated;
	private long candidateRuleFactsCreated;
	private long explicitSupportClausesCreated;
	private long indexedSupportHandlesCreated;
	void recordCandidateRuleKeyCreated() { candidateRuleKeysCreated++; }
	void recordCandidateRuleFactCreated() { candidateRuleFactsCreated++; }
	void recordSupportClauseCreated(boolean indexed) {
		if(indexed) indexedSupportHandlesCreated++;
		else explicitSupportClausesCreated++;
	}
	/** Constructor calls while this analysis collector is active, including temporary objects. */
	public record ObjectCreationSnapshot(long candidateRuleKeys, long candidateRuleFacts,
		long explicitSupportClauses, long indexedSupportHandles) { }
	public ObjectCreationSnapshot objectCreationSnapshot() {
		return new ObjectCreationSnapshot(candidateRuleKeysCreated, candidateRuleFactsCreated,
			explicitSupportClausesCreated, indexedSupportHandlesCreated);
	}
	private long candidateOracleCalls;
	private long candidateEarlyFeasibilityApplications;
	private long candidateEarlyFeasibilityChecks;
	private long candidateEarlyFeasibilityCuts;
	private long generationPrivacyLookups;
	private long generationPrivacyProjectionLookups;
	private long generationPrivacyProtectedLookups;
	private long privacyInputMaskChecks;
	private long supportMrvProducts;
	private long supportSourceChecks;
	private long supportSourceOptionsRemoved;
	private long executionRelations;
	private long executionRegions;
	private long executionRelationOracleCalls;
	private java.math.BigInteger executionRegionTuples = java.math.BigInteger.ZERO;
	private long preparedProfileQueries;
	private long preparedProfileHits;
	private long privacyEmissionsSuppressed;
	private long privacyMaskedDomains;
	private java.math.BigInteger privacyAvoidedTuples = java.math.BigInteger.ZERO;
	private java.math.BigInteger privacyGeneratorCombinationsRejected = java.math.BigInteger.ZERO;

	void recordCandidateOracleCall() { candidateOracleCalls++; }
	void recordCandidateOracleCalls(long count) {
		if(count < 0)
			throw new IllegalArgumentException("Negative Oracle evaluation count");
		candidateOracleCalls += count;
	}
	void recordCandidateEarlyFeasibilityApplication() { candidateEarlyFeasibilityApplications++; }
	void recordCandidateEarlyFeasibilityCheck(boolean infeasible) {
		candidateEarlyFeasibilityChecks++;
		if(infeasible)
			candidateEarlyFeasibilityCuts++;
	}
	void recordGenerationPrivacyLookup(boolean projectionAvailable, boolean hasProtectedInputs) {
		generationPrivacyLookups++;
		if(projectionAvailable)
			generationPrivacyProjectionLookups++;
		if(hasProtectedInputs)
			generationPrivacyProtectedLookups++;
	}
	void recordPrivacyInputMaskCheck() { privacyInputMaskChecks++; }
	void recordSupportMrvProduct() { supportMrvProducts++; }
	void recordSupportSourceCheck(boolean removed) {
		supportSourceChecks++;
		if(removed)
			supportSourceOptionsRemoved++;
	}
	public record GenerationPruningCoverage(long earlyFeasibilityApplications,
		long earlyFeasibilityChecks, long earlyFeasibilityCuts, long privacyLookups,
		long privacyProjectionLookups, long privacyProtectedLookups, long privacyInputMaskChecks,
		long supportMrvProducts, long supportSourceChecks, long supportSourceOptionsRemoved) { }
	public GenerationPruningCoverage generationPruningCoverage() {
		return new GenerationPruningCoverage(candidateEarlyFeasibilityApplications,
			candidateEarlyFeasibilityChecks, candidateEarlyFeasibilityCuts, generationPrivacyLookups,
			generationPrivacyProjectionLookups, generationPrivacyProtectedLookups, privacyInputMaskChecks,
			supportMrvProducts, supportSourceChecks, supportSourceOptionsRemoved);
	}
	void recordExecutionRelation(int regions, int evaluations) {
		if(regions < 0 || evaluations < 0)
			throw new IllegalArgumentException("Negative execution relation metrics");
		executionRelations++;
		executionRegions += regions;
		executionRelationOracleCalls += evaluations;
		candidateOracleCalls += evaluations;
	}
	void recordExecutionRegionTuples(java.math.BigInteger logical) {
		if(logical.signum() < 0)
			throw new IllegalArgumentException("Negative execution region cardinality");
		executionRegionTuples = executionRegionTuples.add(logical);
	}
	public record ExecutionRelationSnapshot(long relations, long regions, long oracleCalls,
		java.math.BigInteger logicalTuples) { }
	public ExecutionRelationSnapshot executionRelationSnapshot() {
		return new ExecutionRelationSnapshot(executionRelations, executionRegions,
			executionRelationOracleCalls, executionRegionTuples);
	}
	void recordPreparedProfileQuery(boolean reused) {
		preparedProfileQueries++;
		if(reused)
			preparedProfileHits++;
	}
	public record PreparedProfileSnapshot(long queries, long hits, long misses) { }
	public PreparedProfileSnapshot preparedProfileSnapshot() {
		return new PreparedProfileSnapshot(preparedProfileQueries, preparedProfileHits,
			preparedProfileQueries - preparedProfileHits);
	}
	void recordPrivacyEmissionSuppressed() { privacyEmissionsSuppressed++; }
	void recordPrivacyInputMask(java.math.BigInteger avoided) {
		privacyMaskedDomains++;
		privacyAvoidedTuples = privacyAvoidedTuples.add(avoided);
	}
	void recordPrivacyGeneratorCombinationRejection(java.math.BigInteger rejected) {
		privacyGeneratorCombinationsRejected = privacyGeneratorCombinationsRejected.add(rejected);
	}
	public record PrivacyPruningSnapshot(long oracleCalls, long emissionsSuppressed,
		long maskedDomains, java.math.BigInteger avoidedTuples,
		java.math.BigInteger generatorCombinationsRejected) { }
	public PrivacyPruningSnapshot privacyPruningSnapshot() {
		return new PrivacyPruningSnapshot(candidateOracleCalls, privacyEmissionsSuppressed,
			privacyMaskedDomains, privacyAvoidedTuples, privacyGeneratorCombinationsRejected);
	}

	private long inputPrefixes;
	private long inputLeaves;
	private long inputPeakDepth;
	private long templateLookups;
	private long templateLookupCandidatesExamined;
	private long incrementalPasses;
	private long incrementalFactsRecomputed;
	private long incrementalFactsReused;
	private long memoHits;
	private long memoMisses;
	private long memoEvictions;
	private long memoEntries;
	private long memoRetainedProofs;
	private long memoRetainedEstimatedBytes;
	private long supportMemoHits;
	private long supportMemoMisses;
	private long supportMemoEvictions;
	private long supportMemoEntries;
	private long supportMemoRetainedTemplates;
	private long supportMemoRetainedEstimatedBytes;
	private long supportMemoRevisionEntriesReused;
	private long factorizedClauses;
	private long factorizedProofObjectsReused;
	private long factorizedBindingObjectsReused;
	private long factorizedProofListsReused;
	private long factorizedBindingListsReused;
	private long signatureIdentityCacheHits;
	private long signatureStructuralCacheHits;
	private long signatureCacheMisses;
	private long signatureSerializations;
	private long signatureSerializedChars;
	private SignatureAdmissionObserver signatureAdmissionObserver;
	private SignatureAdmissionSnapshot signatureAdmissionSnapshot = SignatureAdmissionSnapshot.EMPTY;
	private long canonicalSortCalls;
	private long canonicalSortElements;
	private long canonicalOrderingKeys;
	private long canonicalComparisons;
	private long realizationMergeInputs;
	private long realizationMergeUniqueClauses;
	private long realizationMergeDuplicateClauses;
	private long realizationMergeReusedRealizations;
	private DuplicateMergeDiagnostics duplicateMergeDiagnostics;
	private long topologyExpansionBuilds;
	private long topologyExpansionHits;
	private long topologyRowsBuilt;
	private long topologyRowsCollapsed;
	private long topologyOverlayEvaluations;
	private long topologyOverlayRowsCollapsed;
	private long topologyRevisionEntriesReused;
	private long structuralHandleLookups;
	private long structuralHandlesCreated;
	private long structuralHandleIdentityHits;
	private long structuralHandleStructuralHits;
	private long receiptRelationSlots;
	private long candidateReceiptsCreated;
	private long receiptRankKeyChars;
	private long structuralArenaOverflows;
	private long topologyCacheEvictions;
	private long topologyCacheBypasses;
	private long topologyCacheEntries;
	private long topologyCacheRetainedRows;
	private long contextObserverHashCollisions;
	private final long[] phaseCalls = new long[Phase.values().length];
	private final long[] inclusiveWallNanos = new long[Phase.values().length];
	private final long[] exclusiveWallNanos = new long[Phase.values().length];
	private final long[] inclusiveCpuNanos = new long[Phase.values().length];
	private final long[] exclusiveCpuNanos = new long[Phase.values().length];
	private final long[] inclusiveAllocatedBytes = new long[Phase.values().length];
	private final long[] exclusiveAllocatedBytes = new long[Phase.values().length];
	private static final int INITIAL_PHASE_DEPTH = 16;
	private static final long MAX_PHASE_OWNER = Integer.MAX_VALUE;
	private static final long MAX_PHASE_SEQUENCE = 0xffff_ffffL;
	private static final java.util.concurrent.atomic.AtomicLong PHASE_OWNER_SEQUENCE =
		new java.util.concurrent.atomic.AtomicLong();
	private long phaseOwner = allocatePhaseOwner();
	private int[] activePhaseOrdinals = new int[INITIAL_PHASE_DEPTH];
	private long[] activePhaseHandles = new long[INITIAL_PHASE_DEPTH];
	private long[] activeStartedWallNanos = new long[INITIAL_PHASE_DEPTH];
	private long[] activeStartedCpuNanos = new long[INITIAL_PHASE_DEPTH];
	private long[] activeStartedAllocatedBytes = new long[INITIAL_PHASE_DEPTH];
	private long[] activeChildWallNanos = new long[INITIAL_PHASE_DEPTH];
	private long[] activeChildCpuNanos = new long[INITIAL_PHASE_DEPTH];
	private long[] activeChildAllocatedBytes = new long[INITIAL_PHASE_DEPTH];
	private int activePhaseDepth;
	private long nextPhaseSequence;
	private final boolean liveMetrics = Boolean.getBoolean("sysds.fedplanner.liveMetrics");
	private final long liveIntervalNanos = Math.max(1L,
		Long.getLong("sysds.fedplanner.liveMetricsIntervalMs", 5000L)) * 1_000_000L;
	private long lastLiveNanos;
	private long liveSequence;

	private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN = allocationBean();
	private static final java.lang.management.ThreadMXBean CPU_BEAN = cpuBean();

	void reset() {
		if(activePhaseDepth != 0)
			throw new IllegalStateException("SEARCH_SPACE_PHASE_RESET_WHILE_ACTIVE");
		exactRuleCalls = evidenceReuses = evidenceOverflow = 0;
		headerRequests = headerReuses = profileRequests = profileReuses = 0;
		candidateRoutes.clear();
		Arrays.fill(candidateRouteTotals, 0);
		candidateRouteOverflow = 0;
		Arrays.fill(directWork, 0);
		for(long[] row : nativePublication)
			Arrays.fill(row, 0);
		factorizedRelocationProducts = factorizedRelocationLogicalLeaves = explicitRelocationLeaves = 0;
		candidateRuleKeysCreated = candidateRuleFactsCreated = 0;
		explicitSupportClausesCreated = indexedSupportHandlesCreated = 0;
		candidateOracleCalls = preparedProfileQueries = preparedProfileHits = 0;
		candidateEarlyFeasibilityApplications = candidateEarlyFeasibilityChecks = candidateEarlyFeasibilityCuts = 0;
		generationPrivacyLookups = generationPrivacyProjectionLookups = generationPrivacyProtectedLookups = 0;
		privacyInputMaskChecks = supportMrvProducts = supportSourceChecks = supportSourceOptionsRemoved = 0;
		executionRelations = executionRegions = executionRelationOracleCalls = 0;
		executionRegionTuples = java.math.BigInteger.ZERO;
		privacyEmissionsSuppressed = privacyMaskedDomains = 0;
		privacyAvoidedTuples = java.math.BigInteger.ZERO;
		privacyEmissionAllocationsAvoided = privacyTransferVisits = relocationConflictPrefixes = 0;
		supportDeletionEpochs = supportIndexedRealizations = supportIndexedClauses = 0;
		supportReverseIncidences = supportQueueVisits = 0;
		fixedPointPasses = cfgRefinementPasses = functionBoundaryPasses = semanticPasses = 0;
		publicationPasses = proofQueries = exactContextUniqueQueries = exactContextRepeatedQueries = 0;
		directClosurePasses = directClosureStablePasses = directClosureFullPasses = 0;
		exactContextOverflowQueries = 0;
		proofGraphsBuilt = proofStatesBuilt = 0;
		proofAlternativesBuilt = proofDependencyEdgesBuilt = 0;
		proofDefaultScheduleBuilds = proofDefaultScheduleHits = 0;
		proofDefaultRawSuccessorVisits = proofDefaultUniqueSuccessorVisits = 0;
		proofNoEmptyDagPruningSkips = 0;
		acyclicProofGraphs = cyclicProofGraphs = acyclicAlternativesRemoved = proofRowsExamined = 0;
		deadStatesQueued = dependencyNotifications = alternativesRemoved = 0;
		ownerCompactionElementsScanned = 0;
		supportPrefixes = supportConflictPrefixes = contradictoryClausePins = 0;
		supportLeaves = uniqueProofs = duplicateProofs = 0;
		supportProductDescriptorsExpanded = supportProductDescriptorsReused = 0;
		relocationPrefixes = relocationLeaves = relocationPeakDepth = 0;
		relocationPeakPendingAssignments = inputPrefixes = inputLeaves = inputPeakDepth = 0;
		templateLookups = templateLookupCandidatesExamined = 0;
		incrementalPasses = incrementalFactsRecomputed = incrementalFactsReused = 0;
		memoHits = memoMisses = memoEvictions = memoEntries = memoRetainedProofs = 0;
		memoRetainedEstimatedBytes = 0;
		supportMemoHits = supportMemoMisses = supportMemoEvictions = supportMemoEntries = 0;
		supportMemoRetainedTemplates = supportMemoRetainedEstimatedBytes = 0;
		supportMemoRevisionEntriesReused = 0;
		factorizedClauses = factorizedProofObjectsReused = factorizedBindingObjectsReused = 0;
		factorizedProofListsReused = factorizedBindingListsReused = 0;
		signatureIdentityCacheHits = signatureStructuralCacheHits = signatureCacheMisses = 0;
		signatureSerializations = signatureSerializedChars = 0;
		if(signatureAdmissionObserver != null)
			signatureAdmissionObserver.clear();
		signatureAdmissionObserver = null;
		signatureAdmissionSnapshot = SignatureAdmissionSnapshot.EMPTY;
		canonicalSortCalls = canonicalSortElements = canonicalOrderingKeys = canonicalComparisons = 0;
		realizationMergeInputs = realizationMergeUniqueClauses = realizationMergeDuplicateClauses = 0;
		realizationMergeReusedRealizations = 0;
		if(duplicateMergeDiagnostics != null)
			duplicateMergeDiagnostics.reset();
		topologyExpansionBuilds = topologyExpansionHits = topologyRowsBuilt = topologyRowsCollapsed = 0;
		topologyOverlayEvaluations = topologyOverlayRowsCollapsed = topologyRevisionEntriesReused = 0;
		structuralHandleLookups = structuralHandlesCreated = 0;
		structuralHandleIdentityHits = structuralHandleStructuralHits = 0;
		receiptRelationSlots = candidateReceiptsCreated = receiptRankKeyChars = 0;
		structuralArenaOverflows = 0;
		topologyCacheEvictions = topologyCacheBypasses = topologyCacheEntries = 0;
		topologyCacheRetainedRows = 0;
		contextObserverHashCollisions = 0;
		lastLiveNanos = liveSequence = 0;
		Arrays.fill(phaseCalls, 0);
		Arrays.fill(inclusiveWallNanos, 0);
		Arrays.fill(exclusiveWallNanos, 0);
		Arrays.fill(inclusiveCpuNanos, 0);
		Arrays.fill(exclusiveCpuNanos, 0);
		Arrays.fill(inclusiveAllocatedBytes, 0);
		Arrays.fill(exclusiveAllocatedBytes, 0);
	}

	static final class PhaseToken {
		private final SearchSpaceMetrics owner;
		private final long handle;

		private PhaseToken(SearchSpaceMetrics owner, long handle) {
			this.owner = owner;
			this.handle = handle;
		}
	}

	PhaseToken startPhase(Phase phase) {
		return new PhaseToken(this,startPhaseHandle(phase));
	}

	/** Allocation-free phase handle for extremely hot instrumentation sites. */
	long startPhaseHandle(Phase phase) {
		if(phase == null)
			throw new IllegalArgumentException("SEARCH_SPACE_PHASE_NULL");
		if(nextPhaseSequence == MAX_PHASE_SEQUENCE) {
			// Keep active frames and stale handles unique without limiting the
			// lifetime number of phases on this collector. Existing frames retain
			// their original complete handles until they close.
			phaseOwner = allocatePhaseOwner();
			nextPhaseSequence = 0L;
		}
		ensurePhaseCapacity(activePhaseDepth + 1);
		long handle = phaseOwner << 32 | ++nextPhaseSequence;
		int depth = activePhaseDepth++;
		activePhaseOrdinals[depth] = phase.ordinal();
		activePhaseHandles[depth] = handle;
		activeStartedWallNanos[depth] = System.nanoTime();
		activeStartedCpuNanos[depth] = currentThreadCpuNanos();
		activeStartedAllocatedBytes[depth] = currentThreadAllocatedBytes();
		activeChildWallNanos[depth] = 0L;
		activeChildCpuNanos[depth] = 0L;
		activeChildAllocatedBytes[depth] = 0L;
		if(liveMetrics)
			emitLiveMetrics(false);
		return handle;
	}

	void finishPhase(Phase phase, PhaseToken token) {
		if(token == null || token.owner != this)
			throw new IllegalStateException("SEARCH_SPACE_PHASE_ORDER:" + phase);
		finishPhase(phase,token.handle);
	}

	/** Complete a phase opened by {@link #startPhaseHandle(Phase)}. */
	void finishPhase(Phase phase, long handle) {
		int depth = activePhaseDepth - 1;
		if(phase == null || depth < 0 || activePhaseOrdinals[depth] != phase.ordinal()
			|| activePhaseHandles[depth] != handle)
			throw new IllegalStateException("SEARCH_SPACE_PHASE_ORDER:" + phase);
		long wall = delta(activeStartedWallNanos[depth], System.nanoTime());
		long cpu = delta(activeStartedCpuNanos[depth], currentThreadCpuNanos());
		long allocation = delta(activeStartedAllocatedBytes[depth], currentThreadAllocatedBytes());
		long exclusiveWall = subtractChild(wall, activeChildWallNanos[depth]);
		long exclusiveCpu = subtractChild(cpu, activeChildCpuNanos[depth]);
		long exclusiveAllocation = subtractChild(allocation, activeChildAllocatedBytes[depth]);
		activePhaseDepth = depth;
		int ordinal = phase.ordinal();
		phaseCalls[ordinal]++;
		inclusiveWallNanos[ordinal] += wall;
		exclusiveWallNanos[ordinal] += exclusiveWall;
		inclusiveCpuNanos[ordinal] = addKnown(inclusiveCpuNanos[ordinal], cpu);
		exclusiveCpuNanos[ordinal] = addKnown(exclusiveCpuNanos[ordinal], exclusiveCpu);
		inclusiveAllocatedBytes[ordinal] = addKnown(inclusiveAllocatedBytes[ordinal], allocation);
		exclusiveAllocatedBytes[ordinal] = addKnown(exclusiveAllocatedBytes[ordinal], exclusiveAllocation);
		if(depth > 0) {
			int parent = depth - 1;
			activeChildWallNanos[parent] += wall;
			activeChildCpuNanos[parent] = addKnown(activeChildCpuNanos[parent], cpu);
			activeChildAllocatedBytes[parent] = addKnown(activeChildAllocatedBytes[parent], allocation);
		}
		if(liveMetrics)
			emitLiveMetrics(activePhaseDepth == 0);
	}

	private void ensurePhaseCapacity(int required) {
		if(required <= activePhaseHandles.length)
			return;
		int capacity = Math.max(required,Math.multiplyExact(activePhaseHandles.length,2));
		activePhaseOrdinals = Arrays.copyOf(activePhaseOrdinals,capacity);
		activePhaseHandles = Arrays.copyOf(activePhaseHandles,capacity);
		activeStartedWallNanos = Arrays.copyOf(activeStartedWallNanos,capacity);
		activeStartedCpuNanos = Arrays.copyOf(activeStartedCpuNanos,capacity);
		activeStartedAllocatedBytes = Arrays.copyOf(activeStartedAllocatedBytes,capacity);
		activeChildWallNanos = Arrays.copyOf(activeChildWallNanos,capacity);
		activeChildCpuNanos = Arrays.copyOf(activeChildCpuNanos,capacity);
		activeChildAllocatedBytes = Arrays.copyOf(activeChildAllocatedBytes,capacity);
	}

	private static long allocatePhaseOwner() {
		long owner = PHASE_OWNER_SEQUENCE.incrementAndGet();
		if(owner <= 0 || owner > MAX_PHASE_OWNER)
			throw new IllegalStateException("SEARCH_SPACE_PHASE_OWNER_EXHAUSTED");
		return owner;
	}

	/** Live diagnostic only; inclusive phases overlap, exclusive self times do not. */
	record LivePhase(String phase, long completedCalls, long activeCount,
		long inclusiveWallNanos, long exclusiveWallNanos, long inclusiveCpuNanos,
		long exclusiveCpuNanos, long inclusiveAllocatedBytes, long exclusiveAllocatedBytes) { }

	List<LivePhase> livePhaseSnapshot() {
		long wallNow = System.nanoTime(), cpuNow = currentThreadCpuNanos();
		long allocationNow = currentThreadAllocatedBytes();
		long[] wall = inclusiveWallNanos.clone(), selfWall = exclusiveWallNanos.clone();
		long[] cpu = inclusiveCpuNanos.clone(), selfCpu = exclusiveCpuNanos.clone();
		long[] allocation = inclusiveAllocatedBytes.clone(), selfAllocation = exclusiveAllocatedBytes.clone();
		long[] active = new long[phaseCalls.length];
		long activeChildWall = 0, activeChildCpu = 0, activeChildAllocation = 0;
		// Walk innermost to outermost. Subtract both completed
		// children and the one still-active child, without changing timer state.
		for(int depth = activePhaseDepth - 1; depth >= 0; depth--) {
			int i = activePhaseOrdinals[depth];
			long openWall = delta(activeStartedWallNanos[depth], wallNow);
			long openCpu = delta(activeStartedCpuNanos[depth], cpuNow);
			long openAllocation = delta(activeStartedAllocatedBytes[depth], allocationNow);
			active[i]++;
			wall[i] = addKnown(wall[i], openWall);
			cpu[i] = addKnown(cpu[i], openCpu);
			allocation[i] = addKnown(allocation[i], openAllocation);
			selfWall[i] = addKnown(selfWall[i], subtractChild(openWall,
				addKnown(activeChildWallNanos[depth], activeChildWall)));
			selfCpu[i] = addKnown(selfCpu[i], subtractChild(openCpu,
				addKnown(activeChildCpuNanos[depth], activeChildCpu)));
			selfAllocation[i] = addKnown(selfAllocation[i], subtractChild(openAllocation,
				addKnown(activeChildAllocatedBytes[depth], activeChildAllocation)));
			activeChildWall = openWall;
			activeChildCpu = openCpu;
			activeChildAllocation = openAllocation;
		}
		List<LivePhase> result = new ArrayList<>();
		for(Phase phase : Phase.values()) {
			int i = phase.ordinal();
			result.add(new LivePhase(phase.name(), phaseCalls[i], active[i], wall[i], selfWall[i],
				cpu[i], selfCpu[i], allocation[i], selfAllocation[i]));
		}
		return List.copyOf(result);
	}

	private void emitLiveMetrics(boolean terminal) {
		long now = System.nanoTime();
		if(!terminal && lastLiveNanos != 0 && now - lastLiveNanos < liveIntervalNanos)
			return;
		lastLiveNanos = now;
		long sequence = ++liveSequence;
		for(LivePhase phase : livePhaseSnapshot())
			if(phase.completedCalls() != 0 || phase.activeCount() != 0)
				System.err.println("SEARCH_SPACE_LIVE|seq=" + sequence + "|terminal=" + terminal
					+ "|" + phase);
		System.err.println("SEARCH_SPACE_WORK|seq=" + sequence
			+ "|directPasses=" + directClosurePasses + "|recomputed=" + incrementalFactsRecomputed
			+ "|reused=" + incrementalFactsReused + "|queries=" + proofQueries
			+ "|graphs=" + proofGraphsBuilt + "|rows=" + proofRowsExamined
			+ "|memoHits=" + memoHits + "|memoMisses=" + memoMisses
			+ "|profileQueries=" + preparedProfileQueries + "|profileHits=" + preparedProfileHits
			+ "|supportPrefixes=" + supportPrefixes + "|supportLeaves=" + supportLeaves
			+ "|supportConflictPrefixes=" + supportConflictPrefixes
			+ "|relocationPrefixes=" + relocationPrefixes + "|relocationLeaves=" + relocationLeaves
			+ "|relocationConflictPrefixes=" + relocationConflictPrefixes
			+ "|privacyAvoidedTuples=" + privacyAvoidedTuples
			+ "|privacyGeneratorCombinationsRejected=" + privacyGeneratorCombinationsRejected
			+ "|serializations=" + signatureSerializations + "|serializedChars=" + signatureSerializedChars
			+ "|sorts=" + canonicalSortCalls + "|sortElements=" + canonicalSortElements
			+ "|comparisons=" + canonicalComparisons);
		// Numeric aggregate snapshots remain available even if analysis never returns.
		// They do not enumerate support tuples or retain rich semantic query keys.
		System.err.println("SEARCH_SPACE_COUNTERS|seq=" + sequence + "|" + snapshot());
		System.err.println("SEARCH_SPACE_EXECUTION_RELATIONS|seq=" + sequence
			+ "|" + executionRelationSnapshot());
		System.err.println("SEARCH_SPACE_GENERATION_PRUNING|seq=" + sequence
			+ "|" + generationPruningCoverage());
		System.err.println("SEARCH_SPACE_CANDIDATE_CONSTRUCTION|seq=" + sequence
			+ "|" + candidateConstructionSnapshot());
		System.err.println("SEARCH_SPACE_DIRECT_WORK|seq=" + sequence + "|" + directBindingSnapshot());
		System.err.println("SEARCH_SPACE_NATIVE_PUBLICATION|seq=" + sequence + "|"
			+ nativePublicationSnapshot());
		System.err.println("SEARCH_SPACE_RELOCATION_STORAGE|seq=" + sequence
			+ "|" + relocationStorageSnapshot());
		for(CandidateRouteCount route : candidateRouteSnapshot())
			System.err.println("SEARCH_SPACE_CANDIDATE_ROUTE|seq=" + sequence + "|" + route);
		System.err.println("SEARCH_SPACE_CANDIDATE_ROUTE_TOTALS|seq=" + sequence
			+ "|" + candidateRouteTotalsSnapshot());
		System.err.println("SEARCH_SPACE_CANDIDATE_ROUTE_OVERFLOW|seq=" + sequence
			+ "|calls=" + candidateRouteOverflow);
		System.err.flush();
	}

	private static long delta(long started, long current) {
		return started < 0 || current < started ? -1 : current - started;
	}

	private static long subtractChild(long inclusive, long child) {
		if(inclusive < 0 || child < 0)
			return -1;
		if(child > inclusive)
			throw new IllegalStateException("SEARCH_SPACE_PHASE_CHILD_EXCEEDS_PARENT");
		return inclusive - child;
	}

	private static long addKnown(long accumulated, long delta) {
		return accumulated < 0 || delta < 0 ? -1 : accumulated + delta;
	}

	private static com.sun.management.ThreadMXBean allocationBean() {
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		if(bean instanceof com.sun.management.ThreadMXBean allocationBean
			&& allocationBean.isThreadAllocatedMemorySupported()
			&& allocationBean.isThreadAllocatedMemoryEnabled())
			return allocationBean;
		return null;
	}

	private static long currentThreadAllocatedBytes() {
		return ALLOCATION_BEAN == null ? -1
			: ALLOCATION_BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	private static java.lang.management.ThreadMXBean cpuBean() {
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean : null;
	}

	private static long currentThreadCpuNanos() {
		return CPU_BEAN == null ? -1 : CPU_BEAN.getCurrentThreadCpuTime();
	}

	void recordContextObserver(long verifiedHashCollisions) {
		contextObserverHashCollisions += Math.max(0, verifiedHashCollisions);
	}

	void recordFixedPointPass(String phase) {
		fixedPointPasses++;
		switch(phase) {
			case "cfg-refinement" -> cfgRefinementPasses++;
			case "function-boundary" -> functionBoundaryPasses++;
			case "semantic" -> semanticPasses++;
			case "publication" -> publicationPasses++;
			default -> { /* future phases remain represented by the total */ }
		}
	}

	void recordProofQuery() { proofQueries++; }
	void recordDirectClosurePass(boolean stable, boolean fullPass) {
		directClosurePasses++;
		if(stable)
			directClosureStablePasses++;
		if(fullPass)
			directClosureFullPasses++;
	}
	void recordExactContext(boolean firstObservation) {
		if(firstObservation)
			exactContextUniqueQueries++;
		else
			exactContextRepeatedQueries++;
	}
	void recordExactContextOverflow() { exactContextOverflowQueries++; }

	void recordProofGraph(long states, long alternatives, long dependencyEdges) {
		proofGraphsBuilt++;
		proofStatesBuilt += states;
		proofAlternativesBuilt += alternatives;
		proofDependencyEdgesBuilt += dependencyEdges;
	}
	void recordDefaultTraversalSchedule(boolean built, long rawSuccessors,
		long uniqueSuccessors) {
		if(rawSuccessors < 0 || uniqueSuccessors < 0 || uniqueSuccessors > rawSuccessors)
			throw new IllegalArgumentException("Invalid default proof traversal work");
		if(built)
			proofDefaultScheduleBuilds++;
		else
			proofDefaultScheduleHits++;
		proofDefaultRawSuccessorVisits += rawSuccessors;
		proofDefaultUniqueSuccessorVisits += uniqueSuccessors;
	}
	void recordNoEmptyDagPruningSkip() { proofNoEmptyDagPruningSkips++; }
	void recordProofGraphPath(boolean cyclic, long acyclicRemoved) {
		if(cyclic)
			cyclicProofGraphs++;
		else {
			acyclicProofGraphs++;
			acyclicAlternativesRemoved += acyclicRemoved;
		}
	}

	void recordProofRowExamined() { proofRowsExamined++; }
	void recordDeadStateQueued() { deadStatesQueued++; }
	void recordDependencyNotification() { dependencyNotifications++; }
	void recordAlternativeRemoved() { alternativesRemoved++; }
	void recordOwnerElementsScanned(long count) { ownerCompactionElementsScanned += count; }

	void recordSupportPrefix(int depth) { supportPrefixes++; }
	void recordSupportConflictPrefix() { supportConflictPrefixes++; }
	void recordContradictoryClausePin() { contradictoryClausePins++; }
	void recordSupportLeaf() { supportLeaves++; }
	void recordProofResult(long rawProofs, long distinctProofs) {
		uniqueProofs += distinctProofs;
		duplicateProofs += rawProofs - distinctProofs;
	}
	void recordSupportProductDescriptor(boolean expanded) {
		if(expanded)
			supportProductDescriptorsExpanded++;
		else
			supportProductDescriptorsReused++;
	}
	void recordRelocationPrefix(int depth) {
		relocationPrefixes++;
		relocationPeakDepth = Math.max(relocationPeakDepth, depth);
	}
	void recordRelocationLeaf() {
		relocationLeaves++;
		explicitRelocationLeaves++;
		relocationPeakPendingAssignments = Math.max(relocationPeakPendingAssignments, 1);
	}
	void recordFactorizedRelocationProduct(long logicalLeaves, long logicalPrefixes, int depth) {
		if(logicalLeaves < 0 || logicalPrefixes < 0)
			throw new IllegalArgumentException("Negative factorized relocation metrics");
		factorizedRelocationProducts++;
		factorizedRelocationLogicalLeaves = saturatedAdd(factorizedRelocationLogicalLeaves, logicalLeaves);
		relocationPrefixes = saturatedAdd(relocationPrefixes, logicalPrefixes);
		relocationLeaves = saturatedAdd(relocationLeaves, logicalLeaves);
		relocationPeakDepth = Math.max(relocationPeakDepth, depth);
		if(logicalLeaves > 0)
			relocationPeakPendingAssignments = Math.max(relocationPeakPendingAssignments, 1);
	}
	void recordInputPrefix(int depth) {
		inputPrefixes++;
		inputPeakDepth = Math.max(inputPeakDepth, depth);
	}
	void recordInputLeaf() { inputLeaves++; }
	void recordTemplateLookup(long candidatesExamined) {
		templateLookups++;
		templateLookupCandidatesExamined += candidatesExamined;
	}
	void recordIncrementalFact(boolean recomputed) {
		if(recomputed)
			incrementalFactsRecomputed++;
		else
			incrementalFactsReused++;
	}
	void recordIncrementalPass() { incrementalPasses++; }
	void recordMemoHit() { memoHits++; }
	void recordMemoMiss() { memoMisses++; }
	void recordMemoEviction() { memoEvictions++; }
	void recordMemoResident(long entries, long retainedProofs, long retainedEstimatedBytes) {
		memoEntries = entries;
		memoRetainedProofs = retainedProofs;
		memoRetainedEstimatedBytes = retainedEstimatedBytes;
	}
	void recordSupportMemoHit() { supportMemoHits++; }
	void recordSupportMemoMiss() { supportMemoMisses++; }
	void recordSupportMemoEviction() { supportMemoEvictions++; }
	void recordSupportMemoResident(long entries, long retainedTemplates,
		long retainedEstimatedBytes) {
		supportMemoEntries = entries;
		supportMemoRetainedTemplates = retainedTemplates;
		supportMemoRetainedEstimatedBytes = retainedEstimatedBytes;
	}
	void recordSupportMemoRevisionReuse(long entries) {
		supportMemoRevisionEntriesReused += entries;
	}
	void recordFactorizedClause() { factorizedClauses++; }
	void recordFactorizedProofObjectReuse() { factorizedProofObjectsReused++; }
	void recordFactorizedBindingObjectReuse() { factorizedBindingObjectsReused++; }
	void recordFactorizedProofListReuse() { factorizedProofListsReused++; }
	void recordFactorizedBindingListReuse() { factorizedBindingListsReused++; }
	void recordSignatureIdentityCacheHit() { signatureIdentityCacheHits++; }
	void recordSignatureStructuralCacheHit() { signatureStructuralCacheHits++; }
	void recordSignatureCacheMiss() { signatureCacheMisses++; }
	void recordSignatureSerialization(long characters) {
		signatureSerializations++;
		signatureSerializedChars += characters;
	}
	void recordSignatureAdmission(Object compilerKey, String signature, boolean admitted,
		boolean oversized, int structuralEntries, int identityEntries, long retainedChars) {
		if(signatureAdmissionObserver == null)
			signatureAdmissionObserver = new SignatureAdmissionObserver();
		signatureAdmissionObserver.record(compilerKey, signature, admitted, oversized,
			structuralEntries, identityEntries, retainedChars);
	}
	void recordSignatureCacheState(int structuralEntries, int identityEntries, long retainedChars) {
		if(signatureAdmissionObserver != null)
			signatureAdmissionObserver.recordCacheState(
				structuralEntries, identityEntries, retainedChars);
	}
	void finishSignatureAdmissionScope(int structuralEntries, int identityEntries,
		long retainedChars) {
		if(signatureAdmissionObserver == null)
			return;
		try {
			signatureAdmissionSnapshot = signatureAdmissionObserver.finish(
				structuralEntries, identityEntries, retainedChars);
		}
		finally {
			signatureAdmissionObserver.clear();
			signatureAdmissionObserver = null;
		}
	}
	public SignatureAdmissionSnapshot signatureAdmissionSnapshot() {
		return signatureAdmissionSnapshot;
	}
	boolean hasActiveSignatureAdmissionObserver() {
		return signatureAdmissionObserver != null;
	}
	void recordCanonicalSort(long elements) {
		canonicalSortCalls++;
		canonicalSortElements += elements;
	}
	void recordCanonicalOrderingKey() { canonicalOrderingKeys++; }
	void recordCanonicalComparison() { canonicalComparisons++; }
	void recordRealizationMergeInput() { realizationMergeInputs++; }
	void recordRealizationMergeClause(boolean unique) {
		if(unique)
			realizationMergeUniqueClauses++;
		else
			realizationMergeDuplicateClauses++;
	}
	void recordRealizationMergeClauses(long unique, long duplicate) {
		if(unique < 0 || duplicate < 0)
			throw new IllegalArgumentException("Realization merge clause counts must be non-negative");
		realizationMergeUniqueClauses = saturatedAdd(realizationMergeUniqueClauses, unique);
		realizationMergeDuplicateClauses = saturatedAdd(realizationMergeDuplicateClauses, duplicate);
	}
	private static long saturatedAdd(long current, long delta) {
		return delta > Long.MAX_VALUE - current ? Long.MAX_VALUE : current + delta;
	}
	void recordRealizationMergeReuse() { realizationMergeReusedRealizations++; }

	/**
	 * Enables bounded duplicate-merge provenance diagnostics for this collector.
	 * Aggregate classification remains complete; {@code traceLimit} bounds only retained examples.
	 * This is intentionally opt-in because resolving call sites requires stack walking.
	 */
	public SearchSpaceMetrics enableDuplicateMergeDiagnostics(int traceLimit) {
		duplicateMergeDiagnostics = new DuplicateMergeDiagnostics(Math.max(0, traceLimit));
		return this;
	}

	void recordDuplicateMergeDiagnostics(String mergeShape, int inputRealizations,
		long immutableListReplayClauses, long sharedClauseReplayClauses,
		long equalDistinctClauseClauses, DuplicateProvenanceCounts provenance,
		boolean reusedRealization) {
		if(duplicateMergeDiagnostics == null)
			return;
		duplicateMergeDiagnostics.record(mergeShape, inputRealizations,
			immutableListReplayClauses, sharedClauseReplayClauses,
			equalDistinctClauseClauses, provenance, reusedRealization);
	}

	public DuplicateMergeDiagnosticsSnapshot duplicateMergeDiagnosticsSnapshot() {
		return duplicateMergeDiagnostics == null ? DuplicateMergeDiagnosticsSnapshot.DISABLED
			: duplicateMergeDiagnostics.snapshot(realizationMergeDuplicateClauses);
	}
	boolean hasDuplicateMergeDiagnostics() { return duplicateMergeDiagnostics != null; }

	DuplicateMergeOriginScope beginDuplicateMergeOrigin(long revision, String phase, String route) {
		return duplicateMergeDiagnostics == null ? DuplicateMergeOriginScope.NOOP
			: duplicateMergeDiagnostics.beginOrigin(revision, phase, route, false);
	}

	DuplicateMergeOriginScope beginDuplicateMergeBatchOrigin(long revision, String phase, String route) {
		return duplicateMergeDiagnostics == null ? DuplicateMergeOriginScope.NOOP
			: duplicateMergeDiagnostics.beginOrigin(revision, phase, route, true);
	}

	void recordDuplicateMergeClauseOrigins(
		List<PlacementAnalysis.CandidateRealizationSupportClause> clauses) {
		if(duplicateMergeDiagnostics != null)
			duplicateMergeDiagnostics.recordClauseOrigins(clauses);
	}

	DuplicateClauseProvenance classifyDuplicateClause(
		PlacementAnalysis.CandidateRealizationSupportClause retained,
		PlacementAnalysis.CandidateRealizationSupportClause duplicate) {
		return duplicateMergeDiagnostics == null ? DuplicateClauseProvenance.UNRESOLVED
			: duplicateMergeDiagnostics.classify(retained, duplicate);
	}

	public enum DuplicateClauseProvenance {
		SAME_BATCH,
		SAME_ROUTE_SAME_REVISION,
		CROSS_ROUTE,
		UNCHANGED_REVISION_REPLAY,
		UNRESOLVED
	}

	static final class DuplicateMergeOriginScope implements AutoCloseable {
		private static final DuplicateMergeOriginScope NOOP = new DuplicateMergeOriginScope(null, null);
		private final DuplicateMergeDiagnostics owner;
		private final DuplicateMergeOrigin origin;
		private boolean closed;
		private DuplicateMergeOriginScope(DuplicateMergeDiagnostics owner, DuplicateMergeOrigin origin) {
			this.owner = owner;
			this.origin = origin;
		}
		@Override public void close() {
			if(owner == null || closed)
				return;
			closed = true;
			owner.endOrigin(origin);
		}
	}

	public record DuplicateProvenanceCounts(long sameBatchClauses,
		long sameRouteSameRevisionClauses, long crossRouteClauses,
		long unchangedRevisionReplayClauses, long unresolvedClauses) {
		static final DuplicateProvenanceCounts EMPTY =
			new DuplicateProvenanceCounts(0, 0, 0, 0, 0);
		long total() {
			return sameBatchClauses + sameRouteSameRevisionClauses + crossRouteClauses
				+ unchangedRevisionReplayClauses + unresolvedClauses;
		}
	}

	public record DuplicateMergeTrace(String callSite, String mergeShape, int inputRealizations,
		long duplicateClauses, long immutableListReplayClauses,
		long sharedClauseReplayClauses, long equalDistinctClauseClauses,
		long sameBatchClauses, long sameRouteSameRevisionClauses,
		long crossRouteClauses, long unchangedRevisionReplayClauses,
		long provenanceUnresolvedClauses, boolean reusedRealization) { }

	public record DuplicateMergeDiagnosticsSnapshot(boolean enabled,
		long observedDuplicateClauses, long legacyDuplicateClauses,
		long immutableListReplayClauses, long sharedClauseReplayClauses,
		long equalDistinctClauseClauses, long sameBatchClauses,
		long sameRouteSameRevisionClauses, long crossRouteClauses,
		long unchangedRevisionReplayClauses, long provenanceUnresolvedClauses,
		long originEntries, long originEntryOverflows,
		long droppedTraceEvents, long unretainedOriginTransitionClauses,
		List<DuplicateOriginTransition> originTransitions,
		List<DuplicateMergeTrace> traces) {
		private static final DuplicateMergeDiagnosticsSnapshot DISABLED =
			new DuplicateMergeDiagnosticsSnapshot(false, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
	}

	public record DuplicateOriginTransition(long fromRevision, String fromPhase, String fromRoute,
		long toRevision, String toPhase, String toRoute,
		DuplicateClauseProvenance classification, long duplicateClauses) { }

	private static final class DuplicateMergeDiagnostics {
		private final int traceLimit;
		private final int originLimit;
		private final List<DuplicateMergeTrace> traces = new ArrayList<>();
		private final ArrayDeque<DuplicateMergeOrigin> origins = new ArrayDeque<>();
		private final Map<PlacementAnalysis.CandidateRealizationSupportClause,
			DuplicateMergeOrigin> clauseOrigins = new IdentityHashMap<>();
		private final Map<DuplicateOriginTransitionKey,Long> originTransitions = new LinkedHashMap<>();
		private long immutableListReplayClauses;
		private long sharedClauseReplayClauses;
		private long equalDistinctClauseClauses;
		private long sameBatchClauses;
		private long sameRouteSameRevisionClauses;
		private long crossRouteClauses;
		private long unchangedRevisionReplayClauses;
		private long unresolvedClauses;
		private long originEntryOverflows;
		private long unretainedOriginTransitionClauses;
		private long droppedTraceEvents;
		private long nextBatch;

		private DuplicateMergeDiagnostics(int traceLimit) {
			this.traceLimit = traceLimit;
			this.originLimit = Math.max(1024,
				(int) Math.min(1_000_000L, Math.max(1L, traceLimit) * 64L));
		}

		private void reset() {
			traces.clear();
			origins.clear();
			clauseOrigins.clear();
			originTransitions.clear();
			immutableListReplayClauses = 0;
			sharedClauseReplayClauses = 0;
			equalDistinctClauseClauses = 0;
			sameBatchClauses = 0;
			sameRouteSameRevisionClauses = 0;
			crossRouteClauses = 0;
			unchangedRevisionReplayClauses = 0;
			unresolvedClauses = 0;
			originEntryOverflows = 0;
			unretainedOriginTransitionClauses = 0;
			droppedTraceEvents = 0;
			nextBatch = 0;
		}

		private DuplicateMergeOriginScope beginOrigin(long revision, String phase, String route,
			boolean exactBatch) {
			DuplicateMergeOrigin origin = new DuplicateMergeOrigin(revision,
				java.util.Objects.requireNonNull(phase, "duplicate merge phase"),
				java.util.Objects.requireNonNull(route, "duplicate merge route"), ++nextBatch, exactBatch);
			origins.push(origin);
			return new DuplicateMergeOriginScope(this, origin);
		}

		private void endOrigin(DuplicateMergeOrigin origin) {
			if(origins.peek() != origin)
				throw new IllegalStateException("DUPLICATE_MERGE_ORIGIN_SCOPE_ORDER");
			origins.pop();
		}

		private void recordClauseOrigins(
			List<PlacementAnalysis.CandidateRealizationSupportClause> clauses) {
			DuplicateMergeOrigin origin = origins.peek();
			if(origin == null)
				return;
			for(PlacementAnalysis.CandidateRealizationSupportClause clause : clauses) {
				if(clauseOrigins.containsKey(clause))
					continue;
				if(clauseOrigins.size() >= originLimit) {
					originEntryOverflows++;
					continue;
				}
				clauseOrigins.put(clause, origin);
			}
		}

		private DuplicateClauseProvenance classify(
			PlacementAnalysis.CandidateRealizationSupportClause retained,
			PlacementAnalysis.CandidateRealizationSupportClause duplicate) {
			DuplicateMergeOrigin left = clauseOrigins.get(retained);
			DuplicateMergeOrigin right = clauseOrigins.get(duplicate);
			DuplicateClauseProvenance result;
			if(left == null || right == null)
				result = DuplicateClauseProvenance.UNRESOLVED;
			else if(left.exactBatch() && right.exactBatch() && left.batch() == right.batch())
				result = DuplicateClauseProvenance.SAME_BATCH;
			else if(left.revision() != right.revision())
				result = DuplicateClauseProvenance.UNCHANGED_REVISION_REPLAY;
			else if(!left.phase().equals(right.phase()) || !left.route().equals(right.route()))
				result = DuplicateClauseProvenance.CROSS_ROUTE;
			else
				result = DuplicateClauseProvenance.SAME_ROUTE_SAME_REVISION;
			recordOriginTransition(left, right, result);
			return result;
		}

		private void recordOriginTransition(DuplicateMergeOrigin left,
			DuplicateMergeOrigin right, DuplicateClauseProvenance classification) {
			DuplicateOriginTransitionKey key = new DuplicateOriginTransitionKey(left, right, classification);
			Long prior = originTransitions.get(key);
			if(prior != null) {
				originTransitions.put(key, prior + 1);
				return;
			}
			if(originTransitions.size() >= Math.max(16, traceLimit * 2L)) {
				unretainedOriginTransitionClauses++;
				return;
			}
			originTransitions.put(key, 1L);
		}

		private void record(String mergeShape, int inputRealizations,
			long immutableListReplay, long sharedClauseReplay,
			long equalDistinctClause, DuplicateProvenanceCounts provenance,
			boolean reusedRealization) {
			long duplicates = Math.addExact(Math.addExact(immutableListReplay, sharedClauseReplay),
				equalDistinctClause);
			if(duplicates == 0)
				return;
			if(provenance.total() != equalDistinctClause)
				throw new IllegalArgumentException("DUPLICATE_MERGE_PROVENANCE_COUNT_MISMATCH");
			immutableListReplayClauses += immutableListReplay;
			sharedClauseReplayClauses += sharedClauseReplay;
			equalDistinctClauseClauses += equalDistinctClause;
			sameBatchClauses += provenance.sameBatchClauses();
			sameRouteSameRevisionClauses += provenance.sameRouteSameRevisionClauses();
			crossRouteClauses += provenance.crossRouteClauses();
			unchangedRevisionReplayClauses += provenance.unchangedRevisionReplayClauses();
			unresolvedClauses += provenance.unresolvedClauses();
			if(traces.size() >= traceLimit) {
				droppedTraceEvents++;
				return;
			}
			traces.add(new DuplicateMergeTrace(resolveCallSite(), mergeShape, inputRealizations,
				duplicates, immutableListReplay, sharedClauseReplay, equalDistinctClause,
				provenance.sameBatchClauses(), provenance.sameRouteSameRevisionClauses(),
				provenance.crossRouteClauses(), provenance.unchangedRevisionReplayClauses(),
				provenance.unresolvedClauses(), reusedRealization));
		}

		private DuplicateMergeDiagnosticsSnapshot snapshot(long legacyDuplicateClauses) {
			long observed = immutableListReplayClauses + sharedClauseReplayClauses
				+ equalDistinctClauseClauses;
			List<DuplicateOriginTransition> transitions = originTransitions.entrySet().stream()
				.map(entry -> entry.getKey().snapshot(entry.getValue())).toList();
			return new DuplicateMergeDiagnosticsSnapshot(true, observed, legacyDuplicateClauses,
				immutableListReplayClauses, sharedClauseReplayClauses,
				equalDistinctClauseClauses, sameBatchClauses,
				sameRouteSameRevisionClauses, crossRouteClauses,
				unchangedRevisionReplayClauses, unresolvedClauses,
				clauseOrigins.size(), originEntryOverflows,
				droppedTraceEvents, unretainedOriginTransitionClauses,
				transitions, List.copyOf(traces));
		}

		private static String resolveCallSite() {
			return StackWalker.getInstance().walk(frames -> frames
				.filter(frame -> !frame.getClassName().equals(SearchSpaceMetrics.class.getName())
					&& !frame.getClassName().startsWith(SearchSpaceMetrics.class.getName() + "$"))
				.filter(frame -> !frame.getClassName().startsWith(PlacementAnalysis.class.getName() + "$"))
				.filter(frame -> !frame.getClassName().startsWith("java."))
				.findFirst()
				.map(frame -> frame.getClassName() + "#" + frame.getMethodName()
					+ ":" + frame.getLineNumber())
				.orElse("unresolved"));
		}
	}

	private record DuplicateMergeOrigin(long revision, String phase, String route,
		long batch, boolean exactBatch) { }
	private record DuplicateOriginTransitionKey(DuplicateMergeOrigin from,
		DuplicateMergeOrigin to, DuplicateClauseProvenance classification) {
		private DuplicateOriginTransition snapshot(long duplicateClauses) {
			return new DuplicateOriginTransition(
				from == null ? Long.MIN_VALUE : from.revision(),
				from == null ? "unresolved" : from.phase(),
				from == null ? "unresolved" : from.route(),
				to == null ? Long.MIN_VALUE : to.revision(),
				to == null ? "unresolved" : to.phase(),
				to == null ? "unresolved" : to.route(), classification, duplicateClauses);
		}
	}
	void recordTopologyExpansion(boolean cacheHit, long rows) {
		if(cacheHit)
			topologyExpansionHits++;
		else {
			topologyExpansionBuilds++;
			topologyRowsBuilt += rows;
		}
	}
	void recordTopologyOverlayEvaluation() { topologyOverlayEvaluations++; }
	void recordTopologyRowCollapsed() { topologyRowsCollapsed++; }
	void recordTopologyOverlayRowCollapsed() { topologyOverlayRowsCollapsed++; }
	void recordTopologyRevisionReuse(long entries) { topologyRevisionEntriesReused += entries; }
	void recordStructuralHandle(boolean created, boolean identityHit) {
		structuralHandleLookups++;
		if(created)
			structuralHandlesCreated++;
		else if(identityHit)
			structuralHandleIdentityHits++;
		else
			structuralHandleStructuralHits++;
	}
	void recordReceiptRelationSlot(long rankKeyCharacters) {
		receiptRelationSlots++;
		receiptRankKeyChars += rankKeyCharacters;
	}
	void recordReceiptRelationSlots(long slots) { receiptRelationSlots += slots; }
	void recordReceiptRankKeyCharacters(long characters) { receiptRankKeyChars += characters; }
	void recordCandidateReceiptCreated() { candidateReceiptsCreated++; }
	void recordStructuralArenaOverflow() { structuralArenaOverflows++; }
	void recordTopologyCacheEviction() { topologyCacheEvictions++; }
	void recordTopologyCacheBypass() { topologyCacheBypasses++; }
	void recordTopologyCacheResident(long entries, long rows) {
		topologyCacheEntries = entries;
		topologyCacheRetainedRows = rows;
	}

	public record SignatureClassAdmission(String keyClass,
		long admittedSerializations, long admittedUtf16Units,
		long rejectedSerializations, long rejectedUtf16Units,
		long oversizedRejections, long remainingCapacityRejections) { }

	public record SignatureAdmissionSnapshot(List<SignatureClassAdmission> classes,
		long admittedSerializations, long admittedUtf16Units,
		long rejectedSerializations, long rejectedUtf16Units,
		long oversizedRejections, long remainingCapacityRejections,
		long observedFirstContents, long observedRepeatSerializations,
		long observedOverflowSerializations, long observedRepeatUtf16LowerBound,
		long sameFirstKeyIdentityRepeats, long firstKeyClearedBeforeRepeat,
		long peakStructuralEntries, long peakIdentityEntries, long peakRetainedChars,
		long endStructuralEntries, long endIdentityEntries, long endRetainedChars,
		long firstRejectMaxHeapBytes, long firstRejectUsedHeapBytes) {
		private static final SignatureAdmissionSnapshot EMPTY = new SignatureAdmissionSnapshot(
			List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, -1, -1);
	}

	private static final class SignatureAdmissionObserver {
		private static final int MAX_REJECTED_CONTENTS = 256;
		private static final long MAX_REJECTED_UTF16_UNITS = 1024L * 1024L;
		private final Map<Class<?>,MutableSignatureClassAdmission> byClass = new LinkedHashMap<>();
		private final Map<RejectedContentKey,RejectedContent> rejectedContents = new HashMap<>();
		private long rejectedContentUtf16Units;
		private long admittedSerializations, admittedUtf16Units;
		private long rejectedSerializations, rejectedUtf16Units;
		private long oversizedRejections, remainingCapacityRejections;
		private long observedFirstContents, observedRepeatSerializations;
		private long observedOverflowSerializations, observedRepeatUtf16LowerBound;
		private long sameFirstKeyIdentityRepeats, firstKeyClearedBeforeRepeat;
		private long peakStructuralEntries, peakIdentityEntries, peakRetainedChars;
		private long firstRejectMaxHeapBytes = -1, firstRejectUsedHeapBytes = -1;

		private void record(Object compilerKey, String signature, boolean admitted,
			boolean oversized, int structuralEntries, int identityEntries, long retainedChars) {
			Class<?> keyClass = compilerKey.getClass();
			MutableSignatureClassAdmission stats = byClass.computeIfAbsent(
				keyClass, ignored -> new MutableSignatureClassAdmission());
			if(admitted) {
				admittedSerializations++;
				admittedUtf16Units += signature.length();
				stats.admittedSerializations++;
				stats.admittedUtf16Units += signature.length();
			}
			else {
				rejectedSerializations++;
				rejectedUtf16Units += signature.length();
				stats.rejectedSerializations++;
				stats.rejectedUtf16Units += signature.length();
				if(oversized) {
					oversizedRejections++;
					stats.oversizedRejections++;
				}
				else {
					remainingCapacityRejections++;
					stats.remainingCapacityRejections++;
				}
				if(firstRejectMaxHeapBytes < 0) {
					Runtime runtime = Runtime.getRuntime();
					firstRejectMaxHeapBytes = runtime.maxMemory();
					firstRejectUsedHeapBytes = runtime.totalMemory() - runtime.freeMemory();
				}
				recordRejectedContent(keyClass, compilerKey, signature);
			}
			recordCacheState(structuralEntries, identityEntries, retainedChars);
		}

		private void recordRejectedContent(Class<?> keyClass, Object compilerKey, String signature) {
			RejectedContentKey lookup = new RejectedContentKey(keyClass, signature);
			RejectedContent known = rejectedContents.get(lookup);
			if(known != null) {
				observedRepeatSerializations++;
				observedRepeatUtf16LowerBound += signature.length();
				Object first = known.firstCompilerKey.get();
				if(first == null)
					firstKeyClearedBeforeRepeat++;
				else if(first == compilerKey)
					sameFirstKeyIdentityRepeats++;
				return;
			}
			if(rejectedContents.size() >= MAX_REJECTED_CONTENTS
				|| signature.length() > MAX_REJECTED_UTF16_UNITS - rejectedContentUtf16Units) {
				observedOverflowSerializations++;
				return;
			}
			rejectedContents.put(lookup, new RejectedContent(new WeakReference<>(compilerKey)));
			rejectedContentUtf16Units += signature.length();
			observedFirstContents++;
		}

		private void recordCacheState(int structuralEntries, int identityEntries, long retainedChars) {
			peakStructuralEntries = Math.max(peakStructuralEntries, structuralEntries);
			peakIdentityEntries = Math.max(peakIdentityEntries, identityEntries);
			peakRetainedChars = Math.max(peakRetainedChars, retainedChars);
		}

		private SignatureAdmissionSnapshot finish(int structuralEntries, int identityEntries,
			long retainedChars) {
			recordCacheState(structuralEntries, identityEntries, retainedChars);
			List<SignatureClassAdmission> classes = byClass.entrySet().stream()
				.map(entry -> entry.getValue().snapshot(entry.getKey().getName()))
				.sorted(java.util.Comparator.comparing(SignatureClassAdmission::keyClass)).toList();
			return new SignatureAdmissionSnapshot(classes, admittedSerializations, admittedUtf16Units,
				rejectedSerializations, rejectedUtf16Units, oversizedRejections,
				remainingCapacityRejections, observedFirstContents, observedRepeatSerializations,
				observedOverflowSerializations, observedRepeatUtf16LowerBound,
				sameFirstKeyIdentityRepeats, firstKeyClearedBeforeRepeat,
				peakStructuralEntries, peakIdentityEntries, peakRetainedChars,
				structuralEntries, identityEntries, retainedChars,
				firstRejectMaxHeapBytes, firstRejectUsedHeapBytes);
		}

		private void clear() {
			byClass.clear();
			rejectedContents.clear();
			rejectedContentUtf16Units = 0;
		}
	}

	private record RejectedContentKey(Class<?> keyClass, String signature) { }
	private record RejectedContent(WeakReference<Object> firstCompilerKey) { }
	private static final class MutableSignatureClassAdmission {
		private long admittedSerializations, admittedUtf16Units;
		private long rejectedSerializations, rejectedUtf16Units;
		private long oversizedRejections, remainingCapacityRejections;
		private SignatureClassAdmission snapshot(String keyClass) {
			return new SignatureClassAdmission(keyClass, admittedSerializations, admittedUtf16Units,
				rejectedSerializations, rejectedUtf16Units,
				oversizedRejections, remainingCapacityRejections);
		}
	}

	public Snapshot snapshot() {
		return new Snapshot(fixedPointPasses, cfgRefinementPasses, functionBoundaryPasses,
			semanticPasses, publicationPasses, directClosurePasses, directClosureStablePasses,
			directClosureFullPasses, proofQueries, exactContextUniqueQueries,
			exactContextRepeatedQueries, exactContextOverflowQueries, proofGraphsBuilt, proofStatesBuilt,
			proofAlternativesBuilt, proofDependencyEdgesBuilt, acyclicProofGraphs,
			proofDefaultScheduleBuilds, proofDefaultScheduleHits,
			proofDefaultRawSuccessorVisits, proofDefaultUniqueSuccessorVisits,
			proofNoEmptyDagPruningSkips,
			cyclicProofGraphs, acyclicAlternativesRemoved, proofRowsExamined, deadStatesQueued,
			dependencyNotifications, alternativesRemoved, ownerCompactionElementsScanned,
			// Historical SCC counters remain zero in the diagnostic schema.
			0, 0, 0, 0, 0, supportPrefixes, supportConflictPrefixes,
			contradictoryClausePins, supportLeaves, uniqueProofs, duplicateProofs,
			supportProductDescriptorsExpanded, supportProductDescriptorsReused,
			relocationPrefixes, relocationLeaves, relocationPeakDepth,
			relocationPeakPendingAssignments, inputPrefixes, inputLeaves, inputPeakDepth,
			templateLookups, templateLookupCandidatesExamined, incrementalPasses,
			incrementalFactsRecomputed, incrementalFactsReused, memoHits, memoMisses,
			memoEvictions, memoEntries, memoRetainedProofs, memoRetainedEstimatedBytes,
			supportMemoHits, supportMemoMisses, supportMemoEvictions, supportMemoEntries,
			supportMemoRetainedTemplates, supportMemoRetainedEstimatedBytes,
			supportMemoRevisionEntriesReused, factorizedClauses,
			factorizedProofObjectsReused, factorizedBindingObjectsReused,
			factorizedProofListsReused, factorizedBindingListsReused,
			signatureIdentityCacheHits, signatureStructuralCacheHits, signatureCacheMisses,
			signatureSerializations, signatureSerializedChars, canonicalSortCalls,
			canonicalSortElements, canonicalOrderingKeys, canonicalComparisons,
			realizationMergeInputs, realizationMergeUniqueClauses,
			realizationMergeDuplicateClauses, realizationMergeReusedRealizations,
			topologyExpansionBuilds, topologyExpansionHits, topologyRowsBuilt,
			topologyRowsCollapsed, topologyOverlayEvaluations, topologyOverlayRowsCollapsed,
			topologyRevisionEntriesReused,
			structuralHandleLookups, structuralHandlesCreated,
			structuralHandleIdentityHits, structuralHandleStructuralHits,
			receiptRelationSlots, candidateReceiptsCreated, receiptRankKeyChars,
			structuralArenaOverflows, topologyCacheEvictions, topologyCacheBypasses,
			topologyCacheEntries, topologyCacheRetainedRows, contextObserverHashCollisions,
			candidateOracleCalls, preparedProfileQueries, preparedProfileHits,
			privacyEmissionsSuppressed, privacyEmissionAllocationsAvoided,
			privacyMaskedDomains, privacyAvoidedTuples, privacyTransferVisits, relocationConflictPrefixes,
			supportDeletionEpochs, supportIndexedRealizations, supportIndexedClauses,
			supportReverseIncidences, supportQueueVisits);
	}

	public AttributionSnapshot attributionSnapshot() {
		if(activePhaseDepth != 0)
			throw new IllegalStateException("SEARCH_SPACE_PHASES_STILL_ACTIVE");
		List<PhaseMeasurement> phases = new ArrayList<>(Phase.values().length);
		for(Phase phase : Phase.values()) {
			int ordinal = phase.ordinal();
			phases.add(new PhaseMeasurement(phase.name(), phaseCalls[ordinal],
				inclusiveWallNanos[ordinal], exclusiveWallNanos[ordinal],
				inclusiveCpuNanos[ordinal], exclusiveCpuNanos[ordinal],
				inclusiveAllocatedBytes[ordinal], exclusiveAllocatedBytes[ordinal]));
		}
		long observations = exactContextUniqueQueries + exactContextRepeatedQueries
			+ exactContextOverflowQueries;
		return new AttributionSnapshot(List.copyOf(phases), new ContextDistribution(
			exactContextUniqueQueries, exactContextRepeatedQueries,
			exactContextOverflowQueries, observations));
	}

	public record Snapshot(long fixedPointPasses, long cfgRefinementPasses,
		long functionBoundaryPasses, long semanticPasses, long publicationPasses,
		long directClosurePasses, long directClosureStablePasses, long directClosureFullPasses,
		long proofQueries, long exactContextUniqueQueries, long exactContextRepeatedQueries,
		long exactContextOverflowQueries,
		long proofGraphsBuilt, long proofStatesBuilt,
		long proofAlternativesBuilt, long proofDependencyEdgesBuilt, long acyclicProofGraphs,
		long proofDefaultScheduleBuilds, long proofDefaultScheduleHits,
		long proofDefaultRawSuccessorVisits, long proofDefaultUniqueSuccessorVisits,
		long proofNoEmptyDagPruningSkips,
		long cyclicProofGraphs, long acyclicAlternativesRemoved, long proofRowsExamined,
		long deadStatesQueued, long dependencyNotifications, long alternativesRemoved,
		long ownerCompactionElementsScanned, long sccInvocations, long sccStatesScanned,
		long sccAlternativesScanned, long sccEdgesScanned, long sccMaxRefinementDepth,
		long supportPrefixes, long supportConflictPrefixes, long contradictoryClausePins,
		long supportLeaves, long uniqueProofs, long duplicateProofs,
		long supportProductDescriptorsExpanded, long supportProductDescriptorsReused,
		long relocationPrefixes, long relocationLeaves, long relocationPeakDepth,
		long relocationPeakPendingAssignments, long inputPrefixes, long inputLeaves,
		long inputPeakDepth, long templateLookups, long templateLookupCandidatesExamined,
		long incrementalPasses, long incrementalFactsRecomputed, long incrementalFactsReused,
		long memoHits, long memoMisses, long memoEvictions, long memoEntries,
		long memoRetainedProofs, long memoRetainedEstimatedBytes,
		long supportMemoHits, long supportMemoMisses, long supportMemoEvictions,
		long supportMemoEntries, long supportMemoRetainedTemplates,
		long supportMemoRetainedEstimatedBytes, long supportMemoRevisionEntriesReused,
		long factorizedClauses,
		long factorizedProofObjectsReused,
		long factorizedBindingObjectsReused, long factorizedProofListsReused,
		long factorizedBindingListsReused,
		long signatureIdentityCacheHits, long signatureStructuralCacheHits,
		long signatureCacheMisses, long signatureSerializations, long signatureSerializedChars,
		long canonicalSortCalls, long canonicalSortElements, long canonicalOrderingKeys,
		long canonicalComparisons, long realizationMergeInputs,
		long realizationMergeUniqueClauses, long realizationMergeDuplicateClauses,
		long realizationMergeReusedRealizations, long topologyExpansionBuilds,
		long topologyExpansionHits, long topologyRowsBuilt, long topologyRowsCollapsed,
		long topologyOverlayEvaluations, long topologyOverlayRowsCollapsed,
		long topologyRevisionEntriesReused, long structuralHandleLookups,
		long structuralHandlesCreated, long structuralHandleIdentityHits,
		long structuralHandleStructuralHits, long receiptRelationSlots,
		long candidateReceiptsCreated, long receiptRankKeyChars,
		long structuralArenaOverflows, long topologyCacheEvictions,
		long topologyCacheBypasses, long topologyCacheEntries,
		long topologyCacheRetainedRows, long contextObserverHashCollisions,
		long candidateOracleCalls, long preparedProfileQueries, long preparedProfileHits,
		long privacyEmissionsSuppressed, long privacyEmissionAllocationsAvoided,
		long privacyMaskedDomains, java.math.BigInteger privacyAvoidedTuples, long privacyTransferVisits,
		long relocationConflictPrefixes, long supportDeletionEpochs, long supportIndexedRealizations,
		long supportIndexedClauses, long supportReverseIncidences, long supportQueueVisits) { }

	public record PhaseMeasurement(String phase, long calls, long inclusiveWallNanos,
		long exclusiveWallNanos, long inclusiveCpuNanos, long exclusiveCpuNanos,
		long inclusiveAllocatedBytes, long exclusiveAllocatedBytes) { }

	public record ContextDistribution(long unique, long repeated, long overflow, long total) {
		double uniqueWeight() { return total == 0 ? 0 : (double) unique / total; }
		double repeatedWeight() { return total == 0 ? 0 : (double) repeated / total; }
		double overflowWeight() { return total == 0 ? 0 : (double) overflow / total; }
	}

	public record AttributionSnapshot(List<PhaseMeasurement> phases,
		ContextDistribution contextDistribution) {
		PhaseMeasurement phase(Phase phase) {
			return phases.get(phase.ordinal());
		}
	}
}
