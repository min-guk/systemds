/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analysis-scoped aggregate work counters for the neutral placement search space.
 *
 * <p>Production construction does not create a collector, so the disabled path consists
 * only of null checks at the instrumentation sites. Opt-in diagnostics retain aggregate
 * counters plus a bounded set of full query-context keys in the owning continuity
 * instance; overflow is counted rather than sampled or used to alter planning.</p>
 */
final class SearchSpaceMetrics {
	enum Phase {
		ANALYSIS,
		CONTEXT_OBSERVER,
		PROOF_TOPOLOGY,
		PROOF_OVERLAY,
		PROOF_GROUNDING,
		SUPPORT_PRODUCT_RELATION_MATERIALIZATION,
		PUBLIC_PROOF_MATERIALIZATION,
		CLAUSE_MERGE_CANONICALIZATION,
		CLOSURE_REPLAY,
		PUBLICATION_VALIDATION,
		RECEIPT_RANK_CONSUMER_PREPARATION
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
	private long acyclicProofGraphs;
	private long cyclicProofGraphs;
	private long acyclicAlternativesRemoved;
	private long proofRowsExamined;
	private long deadStatesQueued;
	private long dependencyNotifications;
	private long alternativesRemoved;
	private long ownerCompactionElementsScanned;
	private long sccInvocations;
	private long sccStatesScanned;
	private long sccAlternativesScanned;
	private long sccEdgesScanned;
	private long sccMaxRefinementDepth;
	private long supportPrefixes;
	private long supportLeaves;
	private long uniqueProofs;
	private long duplicateProofs;
	private long supportProductDescriptorsExpanded;
	private long supportProductDescriptorsReused;
	private long relocationPrefixes;
	private long relocationLeaves;
	private long relocationPeakDepth;
	private long relocationPeakPendingAssignments;
	private long inputPrefixes;
	private long inputLeaves;
	private long inputPeakDepth;
	private long templateLookups;
	private long templateLookupCandidatesExamined;
	private long incrementalPasses;
	private long incrementalFactsRecomputed;
	private long incrementalFactsReused;
	private long cfgReplaySelectivePasses;
	private long cfgReplayFullPasses;
	private long cfgReplayFallbackPasses;
	private long cfgReplayReadersRecomputed;
	private long cfgReplayReadersReused;
	private long cfgReplayLoopSeedsInstalled;
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
	private long canonicalSortCalls;
	private long canonicalSortElements;
	private long canonicalOrderingKeys;
	private long canonicalComparisons;
	private long realizationMergeInputs;
	private long realizationMergeUniqueClauses;
	private long realizationMergeDuplicateClauses;
	private long realizationMergeReusedRealizations;
	private long topologyExpansionBuilds;
	private long topologyExpansionHits;
	private long topologyRowsBuilt;
	private long topologyOverlayEvaluations;
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
	private final ArrayDeque<PhaseToken> phaseStack = new ArrayDeque<>();
	private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN = allocationBean();
	private static final java.lang.management.ThreadMXBean CPU_BEAN = cpuBean();

	void reset() {
		if(!phaseStack.isEmpty())
			throw new IllegalStateException("SEARCH_SPACE_PHASE_RESET_WHILE_ACTIVE");
		fixedPointPasses = cfgRefinementPasses = functionBoundaryPasses = semanticPasses = 0;
		publicationPasses = proofQueries = exactContextUniqueQueries = exactContextRepeatedQueries = 0;
		directClosurePasses = directClosureStablePasses = directClosureFullPasses = 0;
		exactContextOverflowQueries = 0;
		proofGraphsBuilt = proofStatesBuilt = 0;
		proofAlternativesBuilt = proofDependencyEdgesBuilt = 0;
		acyclicProofGraphs = cyclicProofGraphs = acyclicAlternativesRemoved = proofRowsExamined = 0;
		deadStatesQueued = dependencyNotifications = alternativesRemoved = 0;
		ownerCompactionElementsScanned = sccInvocations = sccStatesScanned = 0;
		sccAlternativesScanned = sccEdgesScanned = sccMaxRefinementDepth = 0;
		supportPrefixes = supportLeaves = uniqueProofs = duplicateProofs = 0;
		supportProductDescriptorsExpanded = supportProductDescriptorsReused = 0;
		relocationPrefixes = relocationLeaves = relocationPeakDepth = 0;
		relocationPeakPendingAssignments = inputPrefixes = inputLeaves = inputPeakDepth = 0;
		templateLookups = templateLookupCandidatesExamined = 0;
		incrementalPasses = incrementalFactsRecomputed = incrementalFactsReused = 0;
		cfgReplaySelectivePasses = cfgReplayFullPasses = cfgReplayFallbackPasses = 0;
		cfgReplayReadersRecomputed = cfgReplayReadersReused = cfgReplayLoopSeedsInstalled = 0;
		memoHits = memoMisses = memoEvictions = memoEntries = memoRetainedProofs = 0;
		memoRetainedEstimatedBytes = 0;
		supportMemoHits = supportMemoMisses = supportMemoEvictions = supportMemoEntries = 0;
		supportMemoRetainedTemplates = supportMemoRetainedEstimatedBytes = 0;
		supportMemoRevisionEntriesReused = 0;
		factorizedClauses = factorizedProofObjectsReused = factorizedBindingObjectsReused = 0;
		factorizedProofListsReused = factorizedBindingListsReused = 0;
		signatureIdentityCacheHits = signatureStructuralCacheHits = signatureCacheMisses = 0;
		signatureSerializations = signatureSerializedChars = 0;
		canonicalSortCalls = canonicalSortElements = canonicalOrderingKeys = canonicalComparisons = 0;
		realizationMergeInputs = realizationMergeUniqueClauses = realizationMergeDuplicateClauses = 0;
		realizationMergeReusedRealizations = 0;
		topologyExpansionBuilds = topologyExpansionHits = topologyRowsBuilt = 0;
		topologyOverlayEvaluations = topologyRevisionEntriesReused = 0;
		structuralHandleLookups = structuralHandlesCreated = 0;
		structuralHandleIdentityHits = structuralHandleStructuralHits = 0;
		receiptRelationSlots = candidateReceiptsCreated = receiptRankKeyChars = 0;
		structuralArenaOverflows = 0;
		topologyCacheEvictions = topologyCacheBypasses = topologyCacheEntries = 0;
		topologyCacheRetainedRows = 0;
		contextObserverHashCollisions = 0;
		Arrays.fill(phaseCalls, 0);
		Arrays.fill(inclusiveWallNanos, 0);
		Arrays.fill(exclusiveWallNanos, 0);
		Arrays.fill(inclusiveCpuNanos, 0);
		Arrays.fill(exclusiveCpuNanos, 0);
		Arrays.fill(inclusiveAllocatedBytes, 0);
		Arrays.fill(exclusiveAllocatedBytes, 0);
	}

	static final class PhaseToken {
		private final Phase phase;
		private final long startedWallNanos;
		private final long startedCpuNanos;
		private final long startedAllocatedBytes;
		private long childWallNanos;
		private long childCpuNanos;
		private long childAllocatedBytes;

		private PhaseToken(Phase phase, long startedWallNanos, long startedCpuNanos,
			long startedAllocatedBytes) {
			this.phase = phase;
			this.startedWallNanos = startedWallNanos;
			this.startedCpuNanos = startedCpuNanos;
			this.startedAllocatedBytes = startedAllocatedBytes;
		}
	}

	PhaseToken startPhase(Phase phase) {
		PhaseToken token = new PhaseToken(phase, System.nanoTime(), currentThreadCpuNanos(),
			currentThreadAllocatedBytes());
		phaseStack.push(token);
		return token;
	}

	void finishPhase(Phase phase, PhaseToken token) {
		if(token == null || token.phase != phase || phaseStack.peek() != token)
			throw new IllegalStateException("SEARCH_SPACE_PHASE_ORDER:" + phase);
		long wall = delta(token.startedWallNanos, System.nanoTime());
		long cpu = delta(token.startedCpuNanos, currentThreadCpuNanos());
		long allocation = delta(token.startedAllocatedBytes, currentThreadAllocatedBytes());
		phaseStack.pop();
		long exclusiveWall = subtractChild(wall, token.childWallNanos);
		long exclusiveCpu = subtractChild(cpu, token.childCpuNanos);
		long exclusiveAllocation = subtractChild(allocation, token.childAllocatedBytes);
		int ordinal = phase.ordinal();
		phaseCalls[ordinal]++;
		inclusiveWallNanos[ordinal] += wall;
		exclusiveWallNanos[ordinal] += exclusiveWall;
		inclusiveCpuNanos[ordinal] = addKnown(inclusiveCpuNanos[ordinal], cpu);
		exclusiveCpuNanos[ordinal] = addKnown(exclusiveCpuNanos[ordinal], exclusiveCpu);
		inclusiveAllocatedBytes[ordinal] = addKnown(inclusiveAllocatedBytes[ordinal], allocation);
		exclusiveAllocatedBytes[ordinal] = addKnown(exclusiveAllocatedBytes[ordinal], exclusiveAllocation);
		PhaseToken parent = phaseStack.peek();
		if(parent != null) {
			parent.childWallNanos += wall;
			parent.childCpuNanos = addKnown(parent.childCpuNanos, cpu);
			parent.childAllocatedBytes = addKnown(parent.childAllocatedBytes, allocation);
		}
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

	void recordSccScan(long states, long alternatives, long dependencyEdges, int refinementDepth) {
		sccInvocations++;
		sccStatesScanned += states;
		sccMaxRefinementDepth = Math.max(sccMaxRefinementDepth, refinementDepth);
		sccAlternativesScanned += alternatives;
		sccEdgesScanned += dependencyEdges;
	}

	void recordSupportPrefix(int depth) { supportPrefixes++; }
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
	void recordCfgReplayPass(boolean selective, boolean fallback) {
		if(selective)
			cfgReplaySelectivePasses++;
		else
			cfgReplayFullPasses++;
		if(fallback)
			cfgReplayFallbackPasses++;
	}
	void recordCfgReplayLoopSeedInstalled() { cfgReplayLoopSeedsInstalled++; }
	void recordCfgReplayReader(boolean recomputed) {
		if(recomputed)
			cfgReplayReadersRecomputed++;
		else
			cfgReplayReadersReused++;
	}
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
	void recordRealizationMergeReuse() { realizationMergeReusedRealizations++; }
	void recordTopologyExpansion(boolean cacheHit, long rows) {
		if(cacheHit)
			topologyExpansionHits++;
		else {
			topologyExpansionBuilds++;
			topologyRowsBuilt += rows;
		}
	}
	void recordTopologyOverlayEvaluation() { topologyOverlayEvaluations++; }
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

	Snapshot snapshot() {
		return new Snapshot(fixedPointPasses, cfgRefinementPasses, functionBoundaryPasses,
			semanticPasses, publicationPasses, directClosurePasses, directClosureStablePasses,
			directClosureFullPasses, proofQueries, exactContextUniqueQueries,
			exactContextRepeatedQueries, exactContextOverflowQueries, proofGraphsBuilt, proofStatesBuilt,
			proofAlternativesBuilt, proofDependencyEdgesBuilt, acyclicProofGraphs,
			cyclicProofGraphs, acyclicAlternativesRemoved, proofRowsExamined, deadStatesQueued,
			dependencyNotifications, alternativesRemoved, ownerCompactionElementsScanned,
			sccInvocations, sccStatesScanned, sccAlternativesScanned, sccEdgesScanned,
			sccMaxRefinementDepth, supportPrefixes, supportLeaves, uniqueProofs, duplicateProofs,
			supportProductDescriptorsExpanded, supportProductDescriptorsReused,
			relocationPrefixes, relocationLeaves, relocationPeakDepth,
			relocationPeakPendingAssignments, inputPrefixes, inputLeaves, inputPeakDepth,
			templateLookups, templateLookupCandidatesExamined, incrementalPasses,
			incrementalFactsRecomputed, incrementalFactsReused,
			cfgReplaySelectivePasses, cfgReplayFullPasses, cfgReplayFallbackPasses,
			cfgReplayReadersRecomputed, cfgReplayReadersReused, cfgReplayLoopSeedsInstalled,
			memoHits, memoMisses,
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
			topologyOverlayEvaluations, topologyRevisionEntriesReused,
			structuralHandleLookups, structuralHandlesCreated,
			structuralHandleIdentityHits, structuralHandleStructuralHits,
			receiptRelationSlots, candidateReceiptsCreated, receiptRankKeyChars,
			structuralArenaOverflows, topologyCacheEvictions, topologyCacheBypasses,
			topologyCacheEntries, topologyCacheRetainedRows, contextObserverHashCollisions);
	}

	AttributionSnapshot attributionSnapshot() {
		if(!phaseStack.isEmpty())
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

	record Snapshot(long fixedPointPasses, long cfgRefinementPasses,
		long functionBoundaryPasses, long semanticPasses, long publicationPasses,
		long directClosurePasses, long directClosureStablePasses, long directClosureFullPasses,
		long proofQueries, long exactContextUniqueQueries, long exactContextRepeatedQueries,
		long exactContextOverflowQueries,
		long proofGraphsBuilt, long proofStatesBuilt,
		long proofAlternativesBuilt, long proofDependencyEdgesBuilt, long acyclicProofGraphs,
		long cyclicProofGraphs, long acyclicAlternativesRemoved, long proofRowsExamined,
		long deadStatesQueued, long dependencyNotifications, long alternativesRemoved,
		long ownerCompactionElementsScanned, long sccInvocations, long sccStatesScanned,
		long sccAlternativesScanned, long sccEdgesScanned, long sccMaxRefinementDepth,
		long supportPrefixes, long supportLeaves, long uniqueProofs, long duplicateProofs,
		long supportProductDescriptorsExpanded, long supportProductDescriptorsReused,
		long relocationPrefixes, long relocationLeaves, long relocationPeakDepth,
		long relocationPeakPendingAssignments, long inputPrefixes, long inputLeaves,
		long inputPeakDepth, long templateLookups, long templateLookupCandidatesExamined,
		long incrementalPasses, long incrementalFactsRecomputed, long incrementalFactsReused,
		long cfgReplaySelectivePasses, long cfgReplayFullPasses, long cfgReplayFallbackPasses,
		long cfgReplayReadersRecomputed, long cfgReplayReadersReused,
		long cfgReplayLoopSeedsInstalled,
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
		long topologyExpansionHits, long topologyRowsBuilt, long topologyOverlayEvaluations,
		long topologyRevisionEntriesReused, long structuralHandleLookups,
		long structuralHandlesCreated, long structuralHandleIdentityHits,
		long structuralHandleStructuralHits, long receiptRelationSlots,
		long candidateReceiptsCreated, long receiptRankKeyChars,
		long structuralArenaOverflows, long topologyCacheEvictions,
		long topologyCacheBypasses, long topologyCacheEntries,
		long topologyCacheRetainedRows, long contextObserverHashCollisions) { }

	record PhaseMeasurement(String phase, long calls, long inclusiveWallNanos,
		long exclusiveWallNanos, long inclusiveCpuNanos, long exclusiveCpuNanos,
		long inclusiveAllocatedBytes, long exclusiveAllocatedBytes) { }

	record ContextDistribution(long unique, long repeated, long overflow, long total) {
		double uniqueWeight() { return total == 0 ? 0 : (double) unique / total; }
		double repeatedWeight() { return total == 0 ? 0 : (double) repeated / total; }
		double overflowWeight() { return total == 0 ? 0 : (double) overflow / total; }
	}

	record AttributionSnapshot(List<PhaseMeasurement> phases,
		ContextDistribution contextDistribution) {
		PhaseMeasurement phase(Phase phase) {
			return phases.get(phase.ordinal());
		}
	}
}
