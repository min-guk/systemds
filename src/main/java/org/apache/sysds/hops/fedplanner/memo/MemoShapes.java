/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpDG;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.parser.Statement;
import org.apache.sysds.hops.recompile.Recompiler;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.BindingKind;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedOrigin;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.meta.MetaDataAll;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.wink.json4j.JSONObject;

/**
 * Occurrence-scoped shape authority derived before rule enumeration.
 *
 * <p>All inference runs over private HOP clones. Exact source ranges and symbolic
 * value bindings update only these views; the compiled program remains untouched.
 * Dependencies are revisited only when an owner or binding shape changes.</p>
 */
public final class MemoShapes {
	private static final int COST_VIEW_DESCENT_DEPTH = 6;
	private static final int DEFAULT_PROOF_CACHE_ENTRIES = 4096;
	private static final int DEFAULT_TRANSFER_CACHE_ENTRIES = 4096;
	private static final int MAX_GUARD_RELEVANCE_INDEX_ENTRIES = 65536;
	private static final int MOVEMENT_PREFIX_NODE_LIMIT=4096;
	public enum Knowledge { EXACT, UNKNOWN }
	public enum DiagnosticKind { MISMATCH, UNRESOLVED, REFRESH_FAILURE }

	public record Shape(DataType dataType, ValueType valueType, long rows, long columns,
		long nonZeros, Knowledge knowledge) {
		public Shape {
			Objects.requireNonNull(dataType);
			Objects.requireNonNull(valueType);
			Objects.requireNonNull(knowledge);
		}

		public boolean exact() { return knowledge == Knowledge.EXACT; }
	}
	record NativeShapeDescriptor(Shape output,List<Shape> inputs,int blocksize,ShapeHint hint) {
		NativeShapeDescriptor {
			inputs=List.copyOf(inputs);
			Objects.requireNonNull(hint,"shape hint");
		}
	}

	public record Diagnostic(DiagnosticKind kind, String code, int owner, int binding,
		String message) {
		public Diagnostic {
			Objects.requireNonNull(kind);
			Objects.requireNonNull(code);
			Objects.requireNonNull(message);
		}
	}

	/** Request-local work counters; do not couple the shape and privacy fixed points. */
	public record Statistics(long bindingVisits,long ownerVisits,long relevanceVisits,long relevanceEdges) { }
	private long bindingVisits, ownerVisits, relevanceVisits, relevanceEdges;
	public Statistics statistics() {
		return new Statistics(bindingVisits,ownerVisits,relevanceVisits,relevanceEdges);
	}

	private final MemoProgramFacts facts;
	private final MemoSources sources;
	private final MemoBindingResolver resolver;
	private final Hop[] views;
	private final Map<Hop,Integer> viewOwners = new IdentityHashMap<>();
	private final List<List<MemoProgramFacts.Occurrence>> occurrencesByOwner;
	private final Shape[] initialShapes;
	private final Shape[] ownerShapes;
	private final Shape[] bindingShapes;
	private final Hop[] occurrenceViews;
	private final List<Diagnostic> diagnostics = new ArrayList<>();
	private final Set<String> diagnosticKeys = new LinkedHashSet<>();
	private final Map<String,Optional<Shape>> localMetadataShapes = new HashMap<>();
	private final Map<PhaseNodeKey,Shape> phaseShapeCache = new HashMap<>();
	private final Map<OccurrenceTransferKey,Shape> occurrenceTransferCache;
	private final boolean occurrenceTransferEnabled;
	private final Map<Set<Integer>,Map<List<Integer>,Set<MemoBindingResolver.GuardDecision>>>
		guardRelevanceIndex = new IdentityHashMap<>();
	private int guardRelevanceIndexEntries;
	// GuardDecision.equals canonicalizes aliases; shape keys retain the full syntactic identity.
	private final Map<MemoBindingResolver.GuardDecision,ExactGuardAssignment[]> guardAssignments=new IdentityHashMap<>();
	private long guardAtomRequests,guardAtomHits;
	private final List<List<Integer>> ownerConsumers;
	private final List<List<Integer>> ownerBindingConsumers;
	private final List<int[]> costDependenciesByBinding;
	private final List<int[]> costInputsByOccurrence;
	private final List<List<Integer>> bindingConsumers;
	private final List<List<Integer>> bindingOwnerConsumers;
	private final List<List<Integer>> formalArguments;
	private final ArrayDeque<Integer> ownerWork = new ArrayDeque<>();
	private final ArrayDeque<Integer> bindingWork = new ArrayDeque<>();
	private final boolean[] ownerQueued;
	private final boolean[] bindingQueued;
	private final boolean[] bindingConflicts;
	private final boolean[] invariantCostStats;
	private final List<Set<Integer>> phaseSensitiveByBinding;
	private final List<Set<Integer>> dimensionPhaseSensitiveByBinding;
	private final List<Set<Integer>> guardSensitiveByBinding;
	private final List<Set<Integer>> phaseSensitiveByOccurrence;
	private final List<Set<Integer>> guardSensitiveByOccurrence;
	private final List<LoopSlotIdentity> selectionSensitiveHeads;
	private final Map<LoopSlotIdentity,int[]> headOrdinals;
	private final List<List<Set<Integer>>> phaseGuardDependencies;
	private final Map<ShapeDependencies,int[]> shapeHeadIndices=new IdentityHashMap<>();
	private final List<ShapeDependencies> shapeDependenciesByBinding;
	private final List<ShapeDependencies> shapeDependenciesByOccurrence;
	private record CanonicalDependencyKey(ShapeDependencies value,List<Integer> guardOrder) {
		private CanonicalDependencyKey(ShapeDependencies value) { this(value,List.copyOf(value.guards())); }
	}
	private static final class ProjectionAtomKey {
		private final ShapeDependencies dependencies;
		private final Map<?,?> guards,loops,phases,cardinalities;
		private final List<Integer> frame;
		private final int kind,hash;
		private ProjectionAtomKey(EvaluationContext context,ShapeDependencies dependencies,int kind) {
			this.dependencies=dependencies; guards=context.guards(); loops=context.loops();
			phases=context.iterationPhases(); cardinalities=context.cardinalities();
			frame=List.copyOf(context.callFrame()); this.kind=kind;
			int value=kind; value=31*value+System.identityHashCode(dependencies);
			value=31*value+System.identityHashCode(guards); value=31*value+System.identityHashCode(loops);
			value=31*value+System.identityHashCode(phases); value=31*value+System.identityHashCode(cardinalities);
			hash=31*value+frame.hashCode();
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			return this==other || other instanceof ProjectionAtomKey that && kind==that.kind
				&& dependencies==that.dependencies && guards==that.guards && loops==that.loops
				&& phases==that.phases && cardinalities==that.cardinalities && frame.equals(that.frame);
		}
	}
	record ProjectionAtomStatistics(long requests,long hits,long clears,int entries) { }
	private static final int PROJECTION_ATOM_CACHE_ENTRIES=4096;
	private final Map<ProjectionAtomKey,BitSet> projectionAtoms=new HashMap<>();
	private long projectionAtomRequests,projectionAtomHits,projectionAtomClears;
	ProjectionAtomStatistics projectionAtomStatistics() {
		return new ProjectionAtomStatistics(projectionAtomRequests,projectionAtomHits,
			projectionAtomClears,projectionAtoms.size());
	}
	private static final BitSet EMPTY_HEAD_BINDINGS=new BitSet();
	private static final Object ACTIVE_COST_GUARD_VISIT=new Object();
	private final Map<BindingGuardKey,CostGuardOutcome> bindingGuardCache;
	private final Map<RepeatProofKey,Boolean> repeatProofCache;
	private long bindingGuardRequests;
	private long bindingGuardCacheHits;
	private long repeatProofRequests;
	private long repeatProofCacheHits;
	private long phaseShapeRequests;
	private long phaseShapeCacheHits;
	private long guardRelevanceRequests;
	private long guardRelevanceCacheHits;
	private long occurrenceTransferRequests;
	private long occurrenceTransferCacheHits;
	private long occurrenceTransferBuilds;
	private static final int DEFAULT_INDEXED_ATOM_LIMIT=65536;
	private static final int LOOP_PHASE_COUNT=LoopPhase.values().length;
	private static final int ITERATION_PHASE_COUNT=IterationPhase.values().length;
	private static final int LOOP_CARDINALITY_COUNT=LoopCardinality.values().length;
	private final boolean indexedContexts;
	private final int indexedAtomLimit;
	private final int[] resolvedShapeAliasRepresentatives;
	private final Map<ExactGuardAssignment,Integer> indexedGuardAtoms=new HashMap<>();
	private final Map<LoopDecision,int[]> indexedLoopAtoms=new HashMap<>();
	private final Map<LoopDecision,int[]> indexedIterationAtoms=new HashMap<>();
	private final Map<LoopCardinalityDecision,int[]> indexedCardinalityAtoms=new HashMap<>();
	private int indexedAtomCount;
	private static final class MovementPrefixNode {
		private final Map<Shape,MovementPrefixNode> next=new LinkedHashMap<>();
		private boolean complete;
	}
	private final MovementPrefixNode[] movementPrefixes;
	private int movementPrefixNodes;
	private long movementPrefixRequests,movementPrefixCompleteHits,movementPrefixBuilds;

	private MemoShapes(MemoProgramFacts facts, MemoSources sources,int bindingGuardCacheEntries,
		int repeatProofCacheEntries,int transferCacheEntries,boolean indexedContexts,int indexedAtomLimit) {
		this.facts = Objects.requireNonNull(facts, "program facts");
		phaseGuardDependencies=computePhaseGuardDependencies();
		this.sources = Objects.requireNonNull(sources, "sources");
		resolvedShapeAliasRepresentatives=shapeAliasRepresentatives(facts.bindings());
		movementPrefixes=new MovementPrefixNode[facts.occurrences().size()];
		this.indexedContexts=indexedContexts;
		this.indexedAtomLimit=indexedAtomLimit;
		resolver = new MemoBindingResolver(facts);
		List<int[]> occurrenceCostInputs=new ArrayList<>(facts.occurrences().size());
		for(MemoProgramFacts.Occurrence occurrence:facts.occurrences()) {
			List<Integer> inputs=new ArrayList<>();
			for(int position=0;position<occurrence.hop().getInput().size();position++)
				if(costRelevantInput(occurrence.hop(),position))
					inputs.add(facts.inputBinding(occurrence.id(),position));
			occurrenceCostInputs.add(inputs.stream().mapToInt(Integer::intValue).toArray());
		}
		costInputsByOccurrence=List.copyOf(occurrenceCostInputs);
		List<int[]> bindingCostInputs=new ArrayList<>(facts.bindings().size());
		for(MemoProgramFacts.BindingNode node:facts.bindings()) {
			int[] inputs;
			if(node.kind()==BindingKind.CALL_ARG || !node.children().isEmpty())
				inputs=node.children().stream().mapToInt(Integer::intValue).toArray();
			else if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0
				&& node.referenceId()<facts.occurrences().size())
				inputs=costInputsByOccurrence.get(node.referenceId());
			else inputs=new int[0];
			bindingCostInputs.add(inputs);
		}
		costDependenciesByBinding=List.copyOf(bindingCostInputs);
		views = cloneViews(facts);
		for(int owner = 0; owner < views.length; owner++) viewOwners.put(views[owner], owner);
		initialShapes = new Shape[views.length];
		for(int owner = 0; owner < views.length; owner++) initialShapes[owner] = fromView(views[owner]);
		occurrencesByOwner = lists(views.length);
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences())
			occurrencesByOwner.get(occurrence.compiledOwnerId()).add(occurrence);
		ownerShapes = new Shape[views.length];
		bindingShapes = new Shape[facts.bindings().size()];
		occurrenceViews = new Hop[facts.occurrences().size()];
		ownerConsumers = lists(views.length);
		ownerBindingConsumers = lists(views.length);
		bindingConsumers = lists(bindingShapes.length);
		bindingOwnerConsumers = lists(bindingShapes.length);
		formalArguments = lists(bindingShapes.length);
		ownerQueued = new boolean[views.length];
		bindingQueued = new boolean[bindingShapes.length];
		bindingConflicts = new boolean[bindingShapes.length];
		indexDependencies();
		for(int owner = 0; owner < views.length; owner++) enqueueOwner(owner);
		for(int binding = 0; binding < bindingShapes.length; binding++) enqueueBinding(binding);
		drain();
		finalizeUnresolved();
		drain();
		invariantCostStats=new boolean[bindingShapes.length];
		for(int binding=0;binding<bindingShapes.length;binding++)
			provesInvariantCostStats(binding,new LinkedHashSet<>());
		BindingDependencies dependencies=computeBindingDependencies(true);
		dimensionPhaseSensitiveByBinding=computeBindingDependencies(false).loops();
		phaseSensitiveByBinding=dependencies.loops();
		guardSensitiveByBinding=dependencies.guards();
		List<Set<Integer>> occurrenceLoops=new ArrayList<>(),occurrenceGuards=new ArrayList<>();
		for(MemoProgramFacts.Occurrence occurrence:facts.occurrences()) {
			Set<Integer> loops=new LinkedHashSet<>(),guards=new LinkedHashSet<>();
			for(int position=0;position<occurrence.hop().getInput().size();position++) {
				int binding=facts.inputBinding(occurrence.id(),position);
				if(binding>=0) {
					loops.addAll(phaseSensitiveByBinding.get(binding));
					guards.addAll(guardSensitiveByBinding.get(binding));
				}
			}
			occurrenceLoops.add(java.util.Collections.unmodifiableSet(loops));
			occurrenceGuards.add(java.util.Collections.unmodifiableSet(guards));
		}
		phaseSensitiveByOccurrence=List.copyOf(occurrenceLoops);
		guardSensitiveByOccurrence=List.copyOf(occurrenceGuards);
		List<LoopSlotIdentity> heads=new ArrayList<>();
		for(MemoProgramFacts.LoopRecord loop:facts.loops())
			for(MemoProgramFacts.CarriedValue carried:loop.carriedValues())
				heads.add(new LoopSlotIdentity(loop.id(),carried.slot()));
		selectionSensitiveHeads=List.copyOf(heads);
		headOrdinals=indexHeadOrdinals(selectionSensitiveHeads);
		Map<CanonicalDependencyKey,ShapeDependencies> canonicalDependencies=new LinkedHashMap<>();
		shapeDependenciesByBinding=canonicalShapeDependencies(computeShapeDependencies(),canonicalDependencies);
		List<ShapeDependencies> occurrenceDependencies=new ArrayList<>(facts.occurrences().size());
		for(MemoProgramFacts.Occurrence occurrence:facts.occurrences()) {
			ShapeDependencies occurrenceShapeDependencies=ShapeDependencies.empty();
			for(int position=0;position<occurrence.hop().getInput().size();position++)
				occurrenceShapeDependencies=occurrenceShapeDependencies.union(
					shapeDependencies(facts.inputBinding(occurrence.id(),position)));
			occurrenceShapeDependencies=occurrenceShapeDependencies.union(
				shapeDependencies(occurrence.activationBindingId()));
			occurrenceDependencies.add(occurrenceShapeDependencies);
		}
		shapeDependenciesByOccurrence=canonicalShapeDependencies(occurrenceDependencies,canonicalDependencies);
		for(ShapeDependencies dependency:shapeDependenciesByBinding) indexShapeHeads(dependency);
		for(ShapeDependencies dependency:shapeDependenciesByOccurrence) indexShapeHeads(dependency);
		bindingGuardCache=boundedCache(bindingGuardCacheEntries);
		repeatProofCache=boundedCache(repeatProofCacheEntries);
		occurrenceTransferCache=boundedCache(transferCacheEntries);
		occurrenceTransferEnabled=transferCacheEntries>0;
	}

	public static MemoShapes analyze(MemoProgramFacts facts, MemoSources sources) {
		return new MemoShapes(facts,sources,DEFAULT_PROOF_CACHE_ENTRIES,DEFAULT_PROOF_CACHE_ENTRIES,
			DEFAULT_TRANSFER_CACHE_ENTRIES,true,DEFAULT_INDEXED_ATOM_LIMIT);
	}

	void requireRequest(MemoProgramFacts facts,MemoSources sources) {
		if(this.facts!=facts || this.sources!=sources)
			throw new IllegalArgumentException("Shape authority belongs to another planning request");
	}

	static MemoShapes analyze(MemoProgramFacts facts,MemoSources sources,int bindingGuardCacheEntries,
		int repeatProofCacheEntries) {
		return analyze(facts,sources,bindingGuardCacheEntries,repeatProofCacheEntries,
			DEFAULT_TRANSFER_CACHE_ENTRIES);
	}

	static MemoShapes analyze(MemoProgramFacts facts,MemoSources sources,int bindingGuardCacheEntries,
		int repeatProofCacheEntries,int transferCacheEntries) {
		if(bindingGuardCacheEntries<0 || repeatProofCacheEntries<0 || transferCacheEntries<0)
			throw new IllegalArgumentException("proof cache sizes must be nonnegative");
		return new MemoShapes(facts,sources,bindingGuardCacheEntries,repeatProofCacheEntries,
			transferCacheEntries,true,DEFAULT_INDEXED_ATOM_LIMIT);
	}

	static MemoShapes analyze(MemoProgramFacts facts,MemoSources sources,boolean indexedContexts,
		int indexedAtomLimit) {
		if(indexedAtomLimit<0) throw new IllegalArgumentException("indexed atom limit must be nonnegative");
		return new MemoShapes(facts,sources,DEFAULT_PROOF_CACHE_ENTRIES,DEFAULT_PROOF_CACHE_ENTRIES,
			DEFAULT_TRANSFER_CACHE_ENTRIES,indexedContexts,indexedAtomLimit);
	}

	private static int[] shapeAliasRepresentatives(List<MemoProgramFacts.BindingNode> nodes) {
		int[] representatives=new int[nodes.size()];
		for(int start=0;start<nodes.size();start++) {
			representatives[start]=start;
			int current=start;
			for(int remaining=nodes.size();remaining>0;remaining--) {
				if(current<0 || current>=nodes.size()) break;
				MemoProgramFacts.BindingNode node=nodes.get(current);
				boolean alias=node.kind()==BindingKind.CALL_ARG
					|| node.kind()==BindingKind.DEFINITION && !node.children().isEmpty();
				if(!alias) { representatives[start]=current; break; }
				if(node.children().isEmpty()) break;
				current=node.children().get(0);
			}
		}
		return representatives;
	}

	ReuseStatistics reuseStatistics() {
		return new ReuseStatistics(bindingGuardRequests,bindingGuardCacheHits,
			repeatProofRequests,repeatProofCacheHits,phaseShapeRequests,phaseShapeCacheHits,
			phaseShapeCache.size(),guardRelevanceRequests,guardRelevanceCacheHits,guardRelevanceIndexEntries,
			occurrenceTransferRequests,occurrenceTransferCacheHits,occurrenceTransferBuilds,
			occurrenceTransferCache.size(),guardAtomRequests,guardAtomHits,guardAssignments.size());
	}

	record ReuseStatistics(long bindingGuardRequests,long bindingGuardCacheHits,
		long repeatProofRequests,long repeatProofCacheHits,long phaseShapeRequests,
		long phaseShapeCacheHits,long phaseShapeCacheEntries,long guardRelevanceRequests,
		long guardRelevanceCacheHits,long guardRelevanceIndexEntries,long occurrenceTransferRequests,
		long occurrenceTransferCacheHits,long occurrenceTransferBuilds,long occurrenceTransferEntries,
		long guardAtomRequests,long guardAtomHits,int guardAtomIdentities) { }

	MemoBindingResolver.DirectRootStatistics directRootStatistics() { return resolver.directRootStatistics(); }

	public Hop view(int owner) {
		return views[owner];
	}

	public Shape shape(int owner) {
		return ownerShapes[owner];
	}

	public Shape bindingShape(int binding) {
		return bindingShapes[binding];
	}

	public Shape shapeAt(int occurrence) {
		return occurrenceOutputShape(facts.occurrence(occurrence));
	}

	/** Shape of one semantic input edge after reaching-definition resolution. */
	public Shape inputShape(int occurrence, int position) {
		int binding = facts.inputBinding(occurrence, position);
		return binding >= 0 ? bindingShapes[binding] : unknown((Hop)null);
	}

	/** Phase- and call-context-specific binding shape without materializing environments. */
	public Shape bindingShape(int binding, EvaluationContext context) {
		Objects.requireNonNull(context,"evaluation context");
		if(binding < 0) return unknown((Hop)null);
		EvaluationContext normalized = normalizeLoopPhases(context);
		return resolvedBindingShape(binding,normalized,new PhaseEvaluation(),new LinkedHashSet<>());
	}

	public Shape inputShape(int occurrence, int position, EvaluationContext context) {
		int binding = facts.inputBinding(occurrence,position);
		return bindingShape(binding,context);
	}

	public Shape shapeAt(int occurrence, EvaluationContext context) {
		return occurrenceShape(occurrence,normalizeLoopPhases(context),new LinkedHashSet<>());
	}

	/** Loop phases that can alter native-rule dimension capability. */
	Set<Integer> phaseSensitiveLoops(int occurrence) {
		Hop hop=facts.occurrence(occurrence).hop();
		Set<Integer> result=new LinkedHashSet<>();
		for(int position=0;position<hop.getInput().size();position++) {
			int binding=facts.inputBinding(occurrence,position);
			if(binding>=0 && costRelevantInput(hop,position)) result.addAll(dimensionPhaseSensitiveByBinding.get(binding));
		}
		return result;
	}

	/** Exact dimensions alone do not imply identical sparsity or scalar shape parameters. */
	Set<MemoBindingResolver.GuardDecision> guardSensitiveDecisions(int occurrence,List<Integer> callFrame) {
		Hop hop=facts.occurrence(occurrence).hop();
		Set<MemoBindingResolver.GuardDecision> result=new LinkedHashSet<>();
		Set<PhaseVisit> visited=new LinkedHashSet<>();
		for(int position=0;position<hop.getInput().size();position++)
			if(costRelevantInput(hop,position))
				collectShapeGuards(facts.inputBinding(occurrence,position),List.copyOf(callFrame),result,visited);
		return Set.copyOf(result);
	}

	Set<MemoBindingResolver.GuardDecision> guardSensitiveDecisionsForBinding(int binding,List<Integer> callFrame) {
		Set<MemoBindingResolver.GuardDecision> result=new LinkedHashSet<>();
		collectShapeGuards(binding,List.copyOf(callFrame),result,new LinkedHashSet<>());
		return Set.copyOf(result);
	}

	/**
	 * Finds one unresolved cost-relevant guard by following the binding DAG under
	 * the supplied partial context. Unlike {@link #guardSensitiveDecisions}, this
	 * query never collects guards from an inactive SELECT arm and never resolves
	 * concrete producer origins.
	 */
	CostGuardSearch firstUnfixedCostGuard(int occurrence,EvaluationContext context) {
		EvaluationContext normalized=normalizeLoopPhases(context);
		CostGuardAccumulator counters=new CostGuardAccumulator();
		Map<List<Integer>,Map<Integer,Object>> state=new HashMap<>();
		boolean unknown=false;
		for(int binding:costInputsByOccurrence.get(occurrence)) {
			CostGuardSearch search=firstUnfixedCostGuard(binding,normalized,state,null,counters,null);
			if(search.guard()!=null) return counters.result(search.guard(),unknown || search.unresolved());
			unknown |= search.unresolved();
		}
		return counters.result(null,unknown);
	}

	CostGuardSearch firstUnfixedCostGuardForBinding(int binding,EvaluationContext context) {
		EvaluationContext normalized=normalizeLoopPhases(context);
		bindingGuardRequests++;
		BindingGuardKey key=new BindingGuardKey(binding,semanticContext(normalized));
		CostGuardOutcome cached=bindingGuardCache.get(key);
		if(cached!=null) {
			bindingGuardCacheHits++;
			return new CostGuardSearch(cached.guard(),cached.unresolved(),0,0,0,0);
		}
		CostGuardAccumulator counters=new CostGuardAccumulator();
		CostGuardSearch search=firstUnfixedCostGuard(binding,normalized,new HashMap<>(),null,counters,null);
		CostGuardSearch result=counters.result(search.guard(),search.unresolved());
		bindingGuardCache.put(key,new CostGuardOutcome(result.guard(),result.unresolved()));
		return result;
	}

	record CostGuardSearch(MemoBindingResolver.GuardDecision guard,boolean unresolved,long bindingVisits,
		long cacheHits,long invariantProjectionStops,long noRegionsSkipped) { }
	private static final CostGuardSearch KNOWN_COST_GUARD_RESULT=
		new CostGuardSearch(null,false,0,0,0,0);
	private static final CostGuardSearch UNKNOWN_COST_GUARD_RESULT=
		new CostGuardSearch(null,true,0,0,0,0);
	private record CostGuardOutcome(MemoBindingResolver.GuardDecision guard,boolean unresolved) { }

	private static final class CostGuardAccumulator {
		private long bindingVisits;
		private long cacheHits;
		private long invariantProjectionStops;
		private long noRegionsSkipped;
		private CostGuardSearch result(MemoBindingResolver.GuardDecision guard,boolean unresolved) {
			return new CostGuardSearch(guard,unresolved,bindingVisits,cacheHits,invariantProjectionStops,noRegionsSkipped);
		}
	}

	private CostGuardSearch firstUnfixedCostGuard(int binding,EvaluationContext context,
		Map<List<Integer>,Map<Integer,Object>> state,Map<Integer,Object> currentBucket,
		CostGuardAccumulator counters,
		PhaseVisit nearestHead) {
		if(binding<0 || binding>=facts.bindings().size())
			return UNKNOWN_COST_GUARD_RESULT;
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()==BindingKind.UNBOUND || node.kind()==BindingKind.MISSING)
			return UNKNOWN_COST_GUARD_RESULT;
		if(guardSensitiveByBinding.get(binding).isEmpty()) {
			counters.invariantProjectionStops++;
			return KNOWN_COST_GUARD_RESULT;
		}
		Map<Integer,Object> bucket=currentBucket;
		if(bucket==null) bucket=state.computeIfAbsent(context.callFrame(),ignored -> new HashMap<>());
		Integer key=binding;
		Object known=bucket.get(key);
		if(known instanceof CostGuardSearch cached) { counters.cacheHits++; return cached; }
		if(known==ACTIVE_COST_GUARD_VISIT) {
			// Reaching definitions may close the recurrence before the LOOP_HEAD is
			// encountered a second time (definition -> head -> backedge -> definition).
			// The path to this point has already exposed every SELECT on the chosen
			// backedge. Discharge only against an active repeat head whose complete
			// first-to-middle cost statistics form an entry-grounded fixed point.
			if(provesActiveRepeatHeadCostStats(nearestHead,context))
				return KNOWN_COST_GUARD_RESULT;
			return UNKNOWN_COST_GUARD_RESULT;
		}
		bucket.put(key,ACTIVE_COST_GUARD_VISIT);
		counters.bindingVisits++;
		if(node.kind()==BindingKind.LOOP_HEAD)
			nearestHead=new PhaseVisit(false,binding,context.callFrame());
		try {
			if(node.kind()==BindingKind.SELECT) {
				MemoBindingResolver.GuardDecision decision=facts.guardDecision(node.referenceId(),context.callFrame());
				Boolean selected=context.guards().get(decision);
				if(selected!=null)
					selected=facts.branchGuardValue(node.referenceId(),context.callFrame(),selected);
				int loop=facts.guardEnclosingLoop(node.referenceId());
				if(selected==null && loop>=0) {
					IterationPhase phase=context.iterationPhases().get(new LoopDecision(loop,context.callFrame()));
					if(phase!=null) selected=facts.guardValue(loop,node.referenceId(),phase);
				}
				if(selected==null) return new CostGuardSearch(decision,false,0,0,0,0);
				int selectedIndex=selected ? 0 : 1;
				int inactiveIndex=selected ? 1 : 0;
				if(node.children().size()>inactiveIndex
					&& !guardSensitiveByBinding.get(node.children().get(inactiveIndex)).isEmpty())
					counters.noRegionsSkipped++;
				return remember(key,firstUnfixedCostGuard(node.children().get(selectedIndex),context,
					state,bucket,counters,nearestHead),bucket);
			}
			if(node.kind()==BindingKind.LOOP_HEAD) {
				IterationPhase phase=context.iterationPhases().get(new LoopDecision(node.referenceId(),context.callFrame()));
				if(phase==null) return UNKNOWN_COST_GUARD_RESULT;
				// Follow the selected phase's value DAG. A retained-value backedge may
				// encounter this head again; only that cycle edge is discharged by the
				// entry-grounded full cost-statistics proof above.
				int child=phase==IterationPhase.FIRST ? 0 : 1;
				return node.children().size()>child
					? remember(key,firstUnfixedCostGuard(node.children().get(child),context,
						state,bucket,counters,nearestHead),bucket)
					: UNKNOWN_COST_GUARD_RESULT;
			}
			if(node.kind()==BindingKind.LAST) {
				LoopDecision loop=new LoopDecision(node.referenceId(),context.callFrame());
				LoopCardinality cardinality=context.cardinalities().get(
					new LoopCardinalityDecision(node.referenceId(),context.callFrame()));
				if(cardinality==null) return UNKNOWN_COST_GUARD_RESULT;
				int child=cardinality==LoopCardinality.ZERO ? 0 : 1;
				EvaluationContext selected=context;
				if(cardinality!=LoopCardinality.ZERO) {
					IterationPhase phase=cardinality==LoopCardinality.ONE
						? IterationPhase.FIRST : IterationPhase.LAST;
					selected=withPhase(context,loop,phase,LoopPhase.BACKEDGE);
				}
				return child<node.children().size()
					? remember(key,firstUnfixedCostGuard(node.children().get(child),selected,
						state,bucket,counters,nearestHead),bucket)
					: UNKNOWN_COST_GUARD_RESULT;
			}
			if(node.kind()==BindingKind.FORMAL) {
				for(int index=context.callFrame().size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size())
						return remember(key,firstUnfixedCostGuard(call.actualBindingIds().get(node.position()),
							context.withCallFrame(context.callFrame().subList(0,index)),state,null,
							counters,nearestHead),bucket);
				}
				return UNKNOWN_COST_GUARD_RESULT;
			}
			if(node.kind()==BindingKind.CALL_RETURN) {
				// Cost callers must establish the call's NORMAL_CONTINUATION activation
				// before following its payload. MemoProgramModel.visitActive owns that
				// prerequisite; this traversal only projects the surviving return value.
				if(node.children().isEmpty()) return UNKNOWN_COST_GUARD_RESULT;
				if(node.children().size()>1)
					return remember(key,firstUnfixedCostGuard(node.children().get(1),context,
						state,bucket,counters,nearestHead),bucket);
				List<Integer> frame=new ArrayList<>(context.callFrame()); frame.add(node.referenceId());
				return remember(key,firstUnfixedCostGuard(node.children().get(0),context.withCallFrame(frame),
					state,null,counters,nearestHead),bucket);
			}
			int[] dependencies=costDependenciesByBinding.get(binding);
			boolean unknown=false;
			for(int dependency:dependencies) {
				CostGuardSearch search=firstUnfixedCostGuard(dependency,context,state,bucket,counters,nearestHead);
				if(search.guard()!=null) return remember(key,search,bucket);
				unknown |= search.unresolved();
			}
			CostGuardSearch result=unknown ? UNKNOWN_COST_GUARD_RESULT : KNOWN_COST_GUARD_RESULT;
			bucket.put(key,result);
			return result;
		}
		finally { bucket.remove(key,ACTIVE_COST_GUARD_VISIT); }
	}

	private boolean provesActiveRepeatHeadCostStats(PhaseVisit nearestHead,EvaluationContext context) {
		if(nearestHead==null) return false;
		MemoProgramFacts.BindingNode candidate=facts.binding(nearestHead.id());
		// Only the closest active head owns this recurrence edge. An outer head
		// cannot discharge a changing or unresolved nested-loop cycle.
		LoopDecision loop=new LoopDecision(candidate.referenceId(),nearestHead.callFrame());
		IterationPhase phase=context.iterationPhases().get(loop);
		return phase!=null && phase!=IterationPhase.FIRST
			&& provesRepeatHeadCostStats(candidate,loop,context.withCallFrame(nearestHead.callFrame()));
	}

	private boolean provesRepeatHeadCostStats(MemoProgramFacts.BindingNode node,LoopDecision loop,
		EvaluationContext context) {
		repeatProofRequests++;
		RepeatProofKey key=new RepeatProofKey(node.id(),loop,semanticContext(context));
		Boolean cached=repeatProofCache.get(key);
		if(cached!=null) {
			repeatProofCacheHits++;
			return cached;
		}
		boolean result=computeRepeatHeadCostStats(node,loop,context);
		// Publish only after the complete first-to-middle proof. Recursive paths
		// therefore never observe an in-progress optimistic value.
		repeatProofCache.put(key,result);
		return result;
	}

	private MemoProgramFacts.CarriedValue carriedValue(int loopId,int slot) {
		List<MemoProgramFacts.CarriedValue> values=facts.loop(loopId).carriedValues();
		for(int i=0;i<values.size();i++) if(values.get(i).slot()==slot) return values.get(i);
		return null;
	}

	private boolean computeRepeatHeadCostStats(MemoProgramFacts.BindingNode node,LoopDecision loop,
		EvaluationContext context) {
		// The frozen estimator assigns one symbolic outcome to an unproved canonical
		// GuardDecision in this call frame. withPhase replaces that value only when
		// ProgramFacts proves a phase-specific iterator outcome.
		MemoProgramFacts.CarriedValue carried=carriedValue(loop.loopId(),node.position());
		if(carried==null) return false;
		PhaseEvaluation evaluation=new PhaseEvaluation();
		EvaluationContext first=withPhase(context,loop,IterationPhase.FIRST,LoopPhase.ENTRY);
		Shape seed=resolvedBindingShape(carried.backedgeBindingId(),first,evaluation,new LinkedHashSet<>());
		if(!knownCostStats(seed)) return false;
		evaluation.heads.put(new LoopSlotKey(loop,node.position()),seed);
		EvaluationContext middle=withPhase(context,loop,IterationPhase.MIDDLE,LoopPhase.BACKEDGE);
		Shape repeat=resolvedBindingShape(carried.backedgeBindingId(),middle,evaluation,new LinkedHashSet<>());
		return sameCostStats(seed,repeat);
	}

	private static <K,V> Map<K,V> boundedCache(int entries) {
		return new LinkedHashMap<>(Math.max(1,Math.min(entries,64)),0.75f,true) {
			@Override protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
				return size()>entries;
			}
		};
	}

	private static boolean knownCostStats(Shape shape) {
		// NNZ=-1 is itself the frozen estimator input. It is safe to preserve after
		// the backedge walk has exposed and fixed every guard that can change it;
		// requiring a nonnegative NNZ here would reject existing exact-size loops.
		return shape!=null && shape.exact();
	}

	private static boolean sameCostStats(Shape left,Shape right) {
		return knownCostStats(left) && knownCostStats(right) && left.equals(right);
	}

	private static CostGuardSearch remember(Integer key,CostGuardSearch result,
		Map<Integer,Object> state) {
		state.put(key,result);
		return result;
	}

	private static boolean costRelevantInput(Hop hop,int position) {
		Hop input=hop.getInput(position);
		// Matrix/frame inputs contribute size and sparsity. Integer parameters can
		// control ordinary dimensions/index ranges; SEQ is the one native shape
		// operator whose FP64 from/to/increment values also control cardinality.
		return isMatrixLike(input) || input.getValueType()==ValueType.INT64
			|| costRelevantNumericScalar(hop,position);
	}

	private static boolean costRelevantNumericScalar(Hop hop,int position) {
		if(!(hop instanceof DataGenOp dataGen) || dataGen.getOp()!=OpOpDG.SEQ) return false;
		Map<String,Integer> parameters=dataGen.getParamIndexMap();
		return Objects.equals(parameters.get(Statement.SEQ_FROM),position)
			|| Objects.equals(parameters.get(Statement.SEQ_TO),position)
			|| Objects.equals(parameters.get(Statement.SEQ_INCR),position);
	}

	/** Positive-only proof over the frozen cost statistics. A cycle is not a proof. */
	private boolean provesInvariantCostStats(int binding,Set<Integer> active) {
		if(binding<0 || binding>=bindingShapes.length) return false;
		if(invariantCostStats[binding]) return true;
		Shape shape=bindingShapes[binding];
		if(shape==null || !shape.exact()) return false;
		if(shape.dataType()!=DataType.MATRIX && shape.dataType()!=DataType.FRAME)
			return invariantCostStats[binding]=scalarLong(binding,new LinkedHashSet<>())!=null;
		if(shape.nonZeros()>=0) return invariantCostStats[binding]=true;
		if(!active.add(binding)) return false;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			List<Integer> dependencies=new ArrayList<>();
			if(node.kind()==BindingKind.FORMAL) dependencies.addAll(formalArguments.get(binding));
			else if(!node.children().isEmpty()) dependencies.addAll(node.children());
			else if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0) {
				MemoProgramFacts.Occurrence definition=facts.occurrence(node.referenceId());
				for(int position=0;position<definition.hop().getInput().size();position++)
					if(costRelevantInput(definition.hop(),position))
						dependencies.add(facts.inputBinding(definition.id(),position));
			}
			else return false;
			for(int child:dependencies) if(!provesInvariantCostStats(child,active)) return false;
			if(node.kind()==BindingKind.SELECT || node.kind()==BindingKind.FORMAL
				|| node.kind()==BindingKind.LOOP_HEAD || node.kind()==BindingKind.LAST)
				for(int child:dependencies) if(!shape.equals(bindingShapes[child])) return false;
			return invariantCostStats[binding]=true;
		}
		finally { active.remove(binding); }
	}

	private void collectShapeGuards(int binding,List<Integer> frame,
		Set<MemoBindingResolver.GuardDecision> result,Set<PhaseVisit> visited) {
		if(binding<0 || binding>=facts.bindings().size() || guardSensitiveByBinding.get(binding).isEmpty()) return;
		PhaseVisit key=new PhaseVisit(false,binding,frame);
		if(!visited.add(key)) return;
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()==BindingKind.SELECT)
			result.add(facts.guardDecision(node.referenceId(),frame));
		switch(node.kind()) {
			case SELECT, LOOP_HEAD, LAST, CALL_ARG -> node.children()
				.forEach(child -> collectShapeGuards(child,frame,result,visited));
			case FORMAL -> {
				for(int index=frame.size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(frame.get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size()) {
						collectShapeGuards(call.actualBindingIds().get(node.position()),frame.subList(0,index),result,visited);
						break;
					}
				}
			}
			case CALL_RETURN -> {
				if(node.children().size()>1) collectShapeGuards(node.children().get(1),frame,result,visited);
				else if(!node.children().isEmpty()) {
					List<Integer> nested=new ArrayList<>(frame); nested.add(node.referenceId());
					collectShapeGuards(node.children().get(0),nested,result,visited);
				}
			}
			case DEFINITION -> {
				if(!node.children().isEmpty()) collectShapeGuards(node.children().get(0),frame,result,visited);
				else if(node.referenceId()>=0 && node.referenceId()<facts.occurrences().size()) {
					MemoProgramFacts.Occurrence producer=facts.occurrence(node.referenceId());
					for(int position=0;position<producer.hop().getInput().size();position++)
						if(costRelevantInput(producer.hop(),position)) collectShapeGuards(facts.inputBinding(producer.id(),position),frame,result,visited);
				}
			}
			case CALL_COMPLETION, UNBOUND, MISSING -> { }
		}
	}

	private record BindingDependencies(List<Set<Integer>> loops,List<Set<Integer>> guards) { }

	private BindingDependencies computeBindingDependencies(boolean costStatistics) {
		int count=facts.bindings().size();
		List<Set<Integer>> loops=new ArrayList<>(count);
		List<Set<Integer>> guards=new ArrayList<>(count);
		List<List<Integer>> dependencies=lists(count);
		List<List<Integer>> consumers=lists(count);
		for(int binding=0; binding<count; binding++) {
			loops.add(new LinkedHashSet<>());
			guards.add(new LinkedHashSet<>());
		}
		for(int binding=0; binding<count; binding++) {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if((costStatistics ? invariantCostStats[binding]
				: bindingShapes[binding]!=null && bindingShapes[binding].exact())
				|| node.kind()==BindingKind.LAST) continue;
			if(node.kind()==BindingKind.SELECT) {
				guards.get(binding).add(node.referenceId());
				int loop=facts.guardEnclosingLoop(node.referenceId());
				if(loop>=0 && !facts.guardPhases(loop).isEmpty()) loops.get(binding).add(loop);
			}
			if(node.kind()==BindingKind.LOOP_HEAD) loops.get(binding).add(node.referenceId());
			dependencies.get(binding).addAll(node.children());
			if(node.kind()==BindingKind.FORMAL)
				for(MemoProgramFacts.CallBinding call : facts.callsForFunction(node.referenceId()))
					if(node.position()>=0 && node.position()<call.argumentBindingIds().size())
						dependencies.get(binding).add(call.argumentBindingIds().get(node.position()));
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0
				&& node.referenceId()<facts.occurrences().size()) {
				MemoProgramFacts.Occurrence definition=facts.occurrence(node.referenceId());
				for(int position=0; position<definition.hop().getInput().size(); position++) {
					int input=facts.inputBinding(definition.id(),position);
					if(input>=0 && costRelevantInput(definition.hop(),position)) dependencies.get(binding).add(input);
				}
			}
			for(int dependency : dependencies.get(binding))
				if(dependency>=0 && dependency<count) consumers.get(dependency).add(binding);
		}
		ArrayDeque<Integer> work=new ArrayDeque<>();
		boolean[] queued=new boolean[count];
		for(int binding=0; binding<count; binding++) { work.add(binding); queued[binding]=true; }
		while(!work.isEmpty()) {
			int binding=work.removeFirst(); queued[binding]=false; relevanceVisits++;
			boolean changed=false;
			for(int dependency : dependencies.get(binding)) if(dependency>=0 && dependency<count) {
				relevanceEdges++;
				changed |= loops.get(binding).addAll(loops.get(dependency));
				changed |= guards.get(binding).addAll(guards.get(dependency));
			}
			if(changed) for(int consumer : consumers.get(binding)) if(!queued[consumer]) {
				queued[consumer]=true; work.addLast(consumer);
			}
		}
		return new BindingDependencies(loops.stream().map(Set::copyOf).toList(),
			guards.stream().map(Set::copyOf).toList());
	}

	/** Shape cache dependencies include hidden availability paths that do not affect payload cost projection. */
	private record ShapeDependencies(Set<Integer> loops,Set<Integer> guards,Set<LoopSlotIdentity> heads) {
		private ShapeDependencies {
			loops=Set.copyOf(loops); guards=Set.copyOf(guards); heads=Set.copyOf(heads);
		}
		private static ShapeDependencies empty() { return new ShapeDependencies(Set.of(),Set.of(),Set.of()); }
		private ShapeDependencies union(ShapeDependencies other) {
			if(other==null || other.loops.isEmpty() && other.guards.isEmpty() && other.heads.isEmpty()) return this;
			Set<Integer> joinedLoops=new LinkedHashSet<>(loops); joinedLoops.addAll(other.loops);
			Set<Integer> joinedGuards=new LinkedHashSet<>(guards); joinedGuards.addAll(other.guards);
			Set<LoopSlotIdentity> joinedHeads=new LinkedHashSet<>(heads); joinedHeads.addAll(other.heads);
			return new ShapeDependencies(joinedLoops,joinedGuards,joinedHeads);
		}
	}

	private static List<ShapeDependencies> canonicalShapeDependencies(List<ShapeDependencies> values,
		Map<CanonicalDependencyKey,ShapeDependencies> canonical) {
		List<ShapeDependencies> result=new ArrayList<>(values.size());
		for(ShapeDependencies value:values) {
			CanonicalDependencyKey key=new CanonicalDependencyKey(value);
			result.add(canonical.computeIfAbsent(key,ignored -> value));
		}
		return List.copyOf(result);
	}

	private ShapeDependencies shapeDependencies(int binding) {
		return binding<0 || binding>=shapeDependenciesByBinding.size()
			? ShapeDependencies.empty() : shapeDependenciesByBinding.get(binding);
	}

	private List<ShapeDependencies> computeShapeDependencies() {
		int count=facts.bindings().size();
		List<Set<Integer>> loops=new ArrayList<>(count),guards=new ArrayList<>(count);
		List<Set<LoopSlotIdentity>> heads=new ArrayList<>(count);
		List<List<Integer>> dependencies=lists(count),consumers=lists(count);
		Map<Integer,List<Integer>> symbolicReturnsByConcrete=new LinkedHashMap<>();
		for(MemoProgramFacts.CallBinding call:facts.calls())
			for(int returned:call.returnBindingIds()) if(returned>=0 && returned<count) {
				MemoProgramFacts.BindingNode node=facts.binding(returned);
				if(node.kind()==BindingKind.CALL_RETURN && node.children().size()>1)
					symbolicReturnsByConcrete.computeIfAbsent(node.children().get(1),ignored -> new ArrayList<>())
						.add(returned);
			}
		for(int binding=0;binding<count;binding++) {
			loops.add(new LinkedHashSet<>()); guards.add(new LinkedHashSet<>()); heads.add(new LinkedHashSet<>());
		}
		for(int binding=0;binding<count;binding++) {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			List<Integer> symbolicReturns=symbolicReturnsByConcrete.get(binding);
			if(symbolicReturns!=null && symbolicReturns.size()==1)
				dependencies.get(binding).add(symbolicReturns.get(0));
			if(node.kind()==BindingKind.SELECT) {
				guards.get(binding).add(node.referenceId());
				int loop=facts.guardEnclosingLoop(node.referenceId());
				if(loop>=0 && !facts.guardPhases(loop).isEmpty()) loops.get(binding).add(loop);
			}
			if(node.kind()==BindingKind.LOOP_HEAD) {
				loops.get(binding).add(node.referenceId());
				heads.get(binding).add(new LoopSlotIdentity(node.referenceId(),node.position()));
			}
			if(node.kind()==BindingKind.LAST) loops.get(binding).add(node.referenceId());
			switch(node.kind()) {
				case CALL_RETURN -> addCallReturnDependencies(node,dependencies.get(binding));
				case CALL_COMPLETION -> addCallCompletionDependencies(node,dependencies.get(binding));
				case FORMAL -> {
					for(MemoProgramFacts.CallBinding call:facts.callsForFunction(node.referenceId()))
						if(node.position()>=0 && node.position()<call.actualBindingIds().size())
							dependencies.get(binding).add(call.actualBindingIds().get(node.position()));
				}
				case DEFINITION -> {
					if(!node.children().isEmpty()) dependencies.get(binding).addAll(node.children());
					else if(node.referenceId()>=0 && node.referenceId()<facts.occurrences().size()) {
						MemoProgramFacts.Occurrence producer=facts.occurrence(node.referenceId());
						for(int position=0;position<producer.hop().getInput().size();position++)
							dependencies.get(binding).add(facts.inputBinding(producer.id(),position));
						if(producer.activationBindingId()>=0)
							dependencies.get(binding).add(producer.activationBindingId());
					}
				}
				case UNBOUND, MISSING -> { }
				default -> dependencies.get(binding).addAll(node.children());
			}
			for(int dependency:dependencies.get(binding))
				if(dependency>=0 && dependency<count) consumers.get(dependency).add(binding);
		}
		ArrayDeque<Integer> work=new ArrayDeque<>(); boolean[] queued=new boolean[count];
		for(int binding=0;binding<count;binding++) { work.add(binding); queued[binding]=true; }
		while(!work.isEmpty()) {
			int binding=work.removeFirst(); queued[binding]=false; boolean changed=false;
			for(int dependency:dependencies.get(binding)) if(dependency>=0 && dependency<count) {
				changed|=loops.get(binding).addAll(loops.get(dependency));
				changed|=guards.get(binding).addAll(guards.get(dependency));
				changed|=heads.get(binding).addAll(heads.get(dependency));
			}
			if(changed) for(int consumer:consumers.get(binding)) if(!queued[consumer]) {
				queued[consumer]=true; work.addLast(consumer);
			}
		}
		List<ShapeDependencies> result=new ArrayList<>(count);
		for(int binding=0;binding<count;binding++)
			result.add(new ShapeDependencies(loops.get(binding),guards.get(binding),heads.get(binding)));
		return List.copyOf(result);
	}

	private void addCallReturnDependencies(MemoProgramFacts.BindingNode node,List<Integer> dependencies) {
		if(node.referenceId()>=0 && node.referenceId()<facts.calls().size()) {
			int completion=facts.call(node.referenceId()).completionBindingId();
			if(completion>=0) dependencies.add(completion);
		}
		if(node.children().size()>1) dependencies.add(node.children().get(1));
		else if(!node.children().isEmpty()) dependencies.add(node.children().get(0));
	}

	private void addCallCompletionDependencies(MemoProgramFacts.BindingNode node,List<Integer> dependencies) {
		dependencies.addAll(node.children());
		if(node.referenceId()<0 || node.referenceId()>=facts.calls().size()) return;
		MemoProgramFacts.CallBinding call=facts.call(node.referenceId());
		if(call.functionSummaryId()>=0 && call.functionSummaryId()<facts.functions().size()) {
			int completion=facts.functionSummary(call.functionSummaryId()).completionBindingId();
			if(completion>=0) dependencies.add(completion);
		}
		for(int returned:call.returnBindingIds()) {
			if(returned<0 || returned>=facts.bindings().size()) continue;
			MemoProgramFacts.BindingNode value=facts.binding(returned);
			if(value.children().size()>1) dependencies.add(value.children().get(1));
			else if(!value.children().isEmpty()) dependencies.add(value.children().get(0));
		}
	}

	public Hop viewAt(int occurrence, EvaluationContext context) {
		EvaluationContext normalized=normalizeLoopPhases(context);
		PhaseEvaluation phaseEvaluation=new PhaseEvaluation();
		Set<PhaseVisit> active=new LinkedHashSet<>();
		Hop view = cloneView(views[facts.occurrence(occurrence).compiledOwnerId()]);
		applyShape(view,occurrenceShape(occurrence,normalized,phaseEvaluation,active));
		for(int position=0; position<view.getInput().size(); position++)
			applyShape(view.getInput(position),resolvedBindingShape(facts.inputBinding(occurrence,position),
				normalized,phaseEvaluation,active));
		applyMultiReturnOutputShapes(view);
		return view;
	}

	record MovementViewRecipe(int occurrence,Shape output,List<Shape> inputs) {
		MovementViewRecipe { inputs=Collections.unmodifiableList(new ArrayList<>(inputs)); }
	}
	record PreparedMovementView(MovementViewRecipe recipe,Hop view) { }
	record MovementPrefixStatistics(long requests,long completeHits,long builds,int nodes) { }
	MovementPrefixStatistics movementPrefixStatistics() {
		return new MovementPrefixStatistics(movementPrefixRequests,movementPrefixCompleteHits,
			movementPrefixBuilds,movementPrefixNodes);
	}

	boolean supportsPreparedMovementView(int occurrence) {
		Hop template=views[facts.occurrence(occurrence).compiledOwnerId()];
		return template.getInput().size()<=2 && (template instanceof UnaryOp || template instanceof BinaryOp
			|| template instanceof AggUnaryOp || template instanceof AggBinaryOp);
	}

	PreparedMovementView prepareMovementView(int occurrence,EvaluationContext context) {
		if(!supportsPreparedMovementView(occurrence))
			throw new IllegalArgumentException("Unsupported prepared movement occurrence "+occurrence);
		movementPrefixRequests++;
		EvaluationContext normalized=normalizeLoopPhases(context);
		PhaseEvaluation phaseEvaluation=new PhaseEvaluation();
		Set<PhaseVisit> active=new LinkedHashSet<>();
		MovementPrefixNode node=movementPrefixes[occurrence];
		Hop view=null;
		boolean publish=true;
		if(node==null) {
			view=cloneView(views[facts.occurrence(occurrence).compiledOwnerId()]);
			movementPrefixBuilds++;
			if(movementPrefixNodes<MOVEMENT_PREFIX_NODE_LIMIT) {
				node=new MovementPrefixNode();
				movementPrefixes[occurrence]=node;
				movementPrefixNodes++;
			}
			else publish=false;
		}
		List<Shape> resolved=new ArrayList<>(3);
		Shape output=occurrenceShape(occurrence,normalized,phaseEvaluation,active);
		view=applyMovementPrefix(view,node,publish,resolved,output,-1,occurrence);
		if(node!=null) node=node.next.get(output);
		if(node==null) publish=false;
		resolved.add(output);
		Hop template=views[facts.occurrence(occurrence).compiledOwnerId()];
		for(int position=0;position<template.getInput().size();position++) {
			Shape input=resolvedBindingShape(facts.inputBinding(occurrence,position),normalized,phaseEvaluation,active);
			view=applyMovementPrefix(view,node,publish,resolved,input,position,occurrence);
			if(node!=null) node=node.next.get(input);
			if(node==null) publish=false;
			resolved.add(input);
		}
		MovementViewRecipe recipe=new MovementViewRecipe(occurrence,output,
			resolved.subList(1,resolved.size()));
		if(view==null && node!=null && node.complete) {
			movementPrefixCompleteHits++;
			return new PreparedMovementView(recipe,null);
		}
		if(view==null) { view=materializeMovementView(recipe); movementPrefixBuilds++; }
		else applyMultiReturnOutputShapes(view);
		if(node!=null) node.complete=true;
		return new PreparedMovementView(recipe,view);
	}

	private Hop applyMovementPrefix(Hop view,MovementPrefixNode node,boolean publish,List<Shape> prior,
		Shape current,int position,int occurrence) {
		MovementPrefixNode known=node==null ? null : node.next.get(current);
		if(view==null && known!=null) return null;
		if(view==null) { view=cloneView(views[facts.occurrence(occurrence).compiledOwnerId()]);
			movementPrefixBuilds++; replayMovementPrefix(view,prior); }
		applyShape(position<0 ? view : view.getInput(position),current);
		if(known==null && publish && movementPrefixNodes<MOVEMENT_PREFIX_NODE_LIMIT) {
			known=new MovementPrefixNode(); node.next.put(current,known); movementPrefixNodes++;
		}
		return view;
	}

	Hop materializeMovementView(MovementViewRecipe recipe) {
		Hop view=cloneView(views[facts.occurrence(recipe.occurrence()).compiledOwnerId()]);
		applyShape(view,recipe.output());
		for(int position=0;position<recipe.inputs().size();position++)
			applyShape(view.getInput(position),recipe.inputs().get(position));
		applyMultiReturnOutputShapes(view);
		return view;
	}

	private void replayMovementPrefix(Hop view,List<Shape> prior) {
		if(prior.isEmpty()) return;
		applyShape(view,prior.get(0));
		for(int position=1;position<prior.size();position++)
			applyShape(view.getInput(position-1),prior.get(position));
	}

	/** Immutable geometry used to form the exact native request before a private HOP is cloned. */
	NativeShapeDescriptor nativeDescriptorAt(int occurrence,EvaluationContext context) {
		EvaluationContext normalized=normalizeLoopPhases(context);
		PhaseEvaluation phaseEvaluation=new PhaseEvaluation();
		Set<PhaseVisit> active=new LinkedHashSet<>();
		Hop template=views[facts.occurrence(occurrence).compiledOwnerId()];
		Shape resolvedOutput=occurrenceShape(occurrence,normalized,phaseEvaluation,active);
		Shape output=isMatrixLike(template) && resolvedOutput!=null ? resolvedOutput : fromView(template);
		List<Shape> resolvedInputs=new ArrayList<>(template.getInput().size());
		for(int position=0;position<template.getInput().size();position++) {
			Shape input=resolvedBindingShape(facts.inputBinding(occurrence,position),normalized,phaseEvaluation,active);
			resolvedInputs.add(input);
		}
		List<Shape> inputs=effectiveInputShapes(template,resolvedInputs);
		List<Shape> payloads=new ArrayList<>(2);
		for(int position=0;position<template.getInput().size();position++) {
			Hop templateInput=template.getInput(position);
			Shape input=inputs.get(position);
			if(payloads.size()<2 && isMatrixLike(templateInput)) payloads.add(input);
		}
		Shape first=payloads.size()>0 ? payloads.get(0) : null;
		Shape second=payloads.size()>1 ? payloads.get(1) : null;
		ShapeHint hint=new ShapeHint(dimension(output,true),dimension(output,false),template.getBlocksize(),
			Optional.empty(),dimension(first,true),dimension(first,false),
			dimension(second,true),dimension(second,false));
		return new NativeShapeDescriptor(output,inputs,template.getBlocksize(),hint);
	}

	static List<Shape> effectiveInputShapes(Hop template,List<Shape> resolvedInputs) {
		if(template.getInput().size()!=resolvedInputs.size())
			throw new IllegalArgumentException("input shape arity mismatch");
		Map<Hop,Shape> finalShapeByInput=new IdentityHashMap<>();
		for(int position=0;position<template.getInput().size();position++)
			// cloneView preserves shared-DAG input identity. viewAt applies positions in
			// order, so the last non-null matrix shape assigned to one shared input wins.
			// applyShape ignores null and therefore retains an earlier assignment.
			if(isMatrixLike(template.getInput(position)) && resolvedInputs.get(position)!=null)
				finalShapeByInput.put(template.getInput(position),resolvedInputs.get(position));
		List<Shape> result=new ArrayList<>(resolvedInputs.size());
		for(int position=0;position<template.getInput().size();position++) {
			Hop input=template.getInput(position);
			Shape resolved=isMatrixLike(input) ? finalShapeByInput.get(input) : null;
			result.add(resolved==null ? fromView(input) : resolved);
		}
		return List.copyOf(result);
	}

	/** Private contextual physical output view; never exposes a live compiler HOP. */
	public Hop outputViewAt(int occurrence,int slot,EvaluationContext context) {
		Hop view=viewAt(occurrence,context);
		if(view instanceof FunctionOp function
			&& function.getFunctionType()==FunctionOp.FunctionType.MULTIRETURN_BUILTIN) {
			if(function.getOutputs()==null || slot<0 || slot>=function.getOutputs().size()
				|| function.getOutputs().get(slot)==null)
				throw new IllegalArgumentException("Invalid multi-return output slot "+slot+" at occurrence "+occurrence);
			return function.getOutputs().get(slot);
		}
		if(slot!=0) throw new IllegalArgumentException("Invalid output slot "+slot+" at occurrence "+occurrence);
		return view;
	}

	/** Cost-only private view of one exact semantic value binding. */
	public Hop bindingViewAt(int binding,EvaluationContext context) {
		Shape shape=bindingShape(binding,context);
		Hop template=bindingTemplate(binding,shape,new LinkedHashSet<>());
		if(template==null)
			throw new IllegalStateException("No physical shape template for binding "+binding);
		Hop view=cloneView(template);
		applyShape(view,shape);
		return view;
	}

	private Hop bindingTemplate(int binding,Shape shape,Set<Integer> active) {
		if(binding<0 || binding>=facts.bindings().size() || !active.add(binding)) return null;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0
				&& node.referenceId()<facts.occurrences().size()) {
				Hop hop=facts.occurrence(node.referenceId()).hop();
				if(node.position()>=0 && hop instanceof FunctionOp function && function.getOutputs()!=null
					&& node.position()<function.getOutputs().size() && function.getOutputs().get(node.position())!=null)
					hop=function.getOutputs().get(node.position());
				if(hop.getDataType()==shape.dataType()) return hop;
			}
			List<Integer> children=node.kind()==BindingKind.FORMAL ? formalArguments.get(binding) : node.children();
			for(int child:children) {
				Hop template=bindingTemplate(child,shape,active);
				if(template!=null) return template;
			}
			return null;
		}
		finally { active.remove(binding); }
	}

	public ShapeHint hintAt(int occurrence, EvaluationContext context) {
		return hintForView(viewAt(occurrence,context));
	}

	/** Derive the same explicit hint from an already-created private contextual view. */
	static ShapeHint hintForView(Hop hop) {
		Shape output = fromView(hop);
		List<Shape> payloads = new ArrayList<>(2);
		for(int position=0; position<hop.getInput().size() && payloads.size()<2; position++)
			if(isMatrixLike(hop.getInput(position))) payloads.add(fromView(hop.getInput(position)));
		Shape first=payloads.size()>0 ? payloads.get(0) : null;
		Shape second=payloads.size()>1 ? payloads.get(1) : null;
		return new ShapeHint(dimension(output,true),dimension(output,false),hop.getBlocksize(),
			Optional.empty(),dimension(first,true),dimension(first,false),
			dimension(second,true),dimension(second,false));
	}

	private record LoopSlotKey(LoopDecision loop,int slot) { }
	private record LoopSlotIdentity(int loopId,int slot) { }
	private static final class ExactGuardAssignment {
		private final int guardId,valueBindingId;
		private final List<Integer> callFrame,valueFrame;
		private final boolean value;
		private final int hash;
		private ExactGuardAssignment(int guardId,List<Integer> callFrame,int valueBindingId,
			List<Integer> valueFrame,boolean value) {
			this.guardId=guardId; this.valueBindingId=valueBindingId;
			this.callFrame=List.copyOf(callFrame); this.valueFrame=List.copyOf(valueFrame);
			this.value=value; this.hash=computeHash();
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			if(this==other) return true;
			return other instanceof ExactGuardAssignment that && hash==that.hash
				&& guardId==that.guardId && valueBindingId==that.valueBindingId && value==that.value
				&& callFrame.equals(that.callFrame) && valueFrame.equals(that.valueFrame);
		}
		private int computeHash() {
			int hash=guardId;
			hash=31*hash+callFrame.hashCode();
			hash=31*hash+valueBindingId;
			hash=31*hash+valueFrame.hashCode();
			// Set.hashCode adds element hashes. A final linear Boolean term would
			// therefore collapse every assignment vector with the same true count.
			hash^=value ? 0x9e3779b9 : 0x85ebca6b;
			hash^=hash>>>16;
			hash*=0x7feb352d;
			hash^=hash>>>15;
			hash*=0x846ca68b;
			return hash^(hash>>>16);
		}
	}
	private interface ContextKey { }
	private record LegacySemanticContext(Set<ExactGuardAssignment> guards,Map<LoopDecision,LoopPhase> loops,
		Map<LoopDecision,IterationPhase> iterations,
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities,List<Integer> callFrame,
		int maxDepth,BitSet headBindings) implements ContextKey {
		private LegacySemanticContext(EvaluationContext context,Set<ExactGuardAssignment> exactGuards,BitSet heads) {
			this(exactGuards,Map.copyOf(context.loops()),
				Map.copyOf(context.iterationPhases()),Map.copyOf(context.cardinalities()),
				List.copyOf(context.callFrame()),context.maxDepth(),(BitSet)heads.clone());
		}
	}
	private ExactGuardAssignment guardAssignment(MemoBindingResolver.GuardDecision decision,boolean value) {
		guardAtomRequests++;
		ExactGuardAssignment[] atoms=guardAssignments.get(decision);
		int slot=value ? 1 : 0;
		if(atoms!=null && atoms[slot]!=null) { guardAtomHits++; return atoms[slot]; }
		ExactGuardAssignment result=new ExactGuardAssignment(decision.guardId(),decision.callFrame(),
			decision.valueBindingId(),decision.valueFrame(),value);
		if(atoms==null && guardAssignments.size()<DEFAULT_PROOF_CACHE_ENTRIES) {
			atoms=new ExactGuardAssignment[2]; guardAssignments.put(decision,atoms);
		}
		if(atoms!=null) atoms[slot]=result;
		return result;
	}
	private Set<ExactGuardAssignment> exactGuards(Map<MemoBindingResolver.GuardDecision,Boolean> guards) {
		if(guards.isEmpty()) return Set.of();
		List<ExactGuardAssignment> result=new ArrayList<>(guards.size());
		for(var entry:guards.entrySet()) result.add(guardAssignment(entry.getKey(),entry.getValue()));
		return Set.of(result.toArray(ExactGuardAssignment[]::new));
	}

	private record BindingGuardKey(int binding,ContextKey context) { }
	private static final class RepeatProofKey {
		private final int binding;
		private final LoopDecision loop;
		private final ContextKey context;
		private final int hash;
		private RepeatProofKey(int binding,LoopDecision loop,ContextKey context) {
			this.binding=binding; this.loop=loop; this.context=context;
			int value=31*(31*binding+loop.hashCode())+context.hashCode();
			value^=value>>>16; value*=0x7feb352d;
			value^=value>>>15; value*=0x846ca68b;
			hash=value^(value>>>16);
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			if(this==other) return true;
			return other instanceof RepeatProofKey that && binding==that.binding
				&& loop.equals(that.loop) && context.equals(that.context);
		}
	}
	private record PhaseVisit(boolean occurrence,int id,List<Integer> callFrame) {
		private PhaseVisit { callFrame=List.copyOf(callFrame); }
	}
	/** Alias provenance can cross invocation boundaries; syntactic guard IDs alone are not cache keys. */
	private static boolean guardRelevant(MemoProgramFacts facts,MemoBindingResolver.GuardDecision decision,
		Set<Integer> dependencies,List<Integer> frame) {
		for(int guard:dependencies) {
			for(int length=frame.size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,frame.subList(0,length))) return true;
			for(int length=decision.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,decision.callFrame().subList(0,length))) return true;
		}
		return false;
	}
	/** Frames are immutable, nonnull integer lists; compare without allocating a list iterator. */
	private static boolean framesEqual(List<Integer> left,List<Integer> right) {
		if(left==right) return true;
		if(left.size()!=right.size()) return false;
		for(int i=0;i<left.size();i++) if(left.get(i).intValue()!=right.get(i).intValue()) return false;
		return true;
	}

	private boolean hasRelevantGuard(EvaluationContext context,Set<Integer> dependencies) {
		if(context.guards().isEmpty()) return false;
		Set<MemoBindingResolver.GuardDecision> relevant=relevantGuardDecisions(dependencies,context.callFrame());
		for(MemoBindingResolver.GuardDecision guard:context.guards().keySet())
			if(relevant.contains(guard) || !framesEqual(context.callFrame(),guard.callFrame())
				&& relevantGuardDecisions(dependencies,guard.callFrame()).contains(guard)) return true;
		return false;
	}
	private boolean guardRelevant(MemoBindingResolver.GuardDecision decision,Set<Integer> dependencies,
		List<Integer> frame) {
		if(relevantGuardDecisions(dependencies,frame).contains(decision)) return true;
		return !framesEqual(frame,decision.callFrame())
			&& relevantGuardDecisions(dependencies,decision.callFrame()).contains(decision);
	}

	private Set<MemoBindingResolver.GuardDecision> relevantGuardDecisions(Set<Integer> dependencies,
		List<Integer> frame) {
		guardRelevanceRequests++;
		Map<List<Integer>,Set<MemoBindingResolver.GuardDecision>> byFrame=guardRelevanceIndex.get(dependencies);
		Set<MemoBindingResolver.GuardDecision> cached=byFrame==null ? null : byFrame.get(frame);
		if(cached!=null) { guardRelevanceCacheHits++; return cached; }
		Set<MemoBindingResolver.GuardDecision> result=new LinkedHashSet<>();
		for(int guard:dependencies)
			for(int length=frame.size();length>=0;length--)
				result.add(facts.guardDecision(guard,frame.subList(0,length)));
		result=Collections.unmodifiableSet(result);
		if(guardRelevanceIndexEntries<MAX_GUARD_RELEVANCE_INDEX_ENTRIES) {
			if(byFrame==null) {
				byFrame=new HashMap<>();
				guardRelevanceIndex.put(dependencies,byFrame);
			}
			if(byFrame.putIfAbsent(List.copyOf(frame),result)==null) guardRelevanceIndexEntries++;
		}
		return result;
	}

	boolean indexedGuardRelevantForTesting(MemoBindingResolver.GuardDecision decision,
		Set<Integer> dependencies,List<Integer> frame) {
		return guardRelevant(decision,dependencies,frame);
	}

	boolean referenceGuardRelevantForTesting(MemoBindingResolver.GuardDecision decision,
		Set<Integer> dependencies,List<Integer> frame) {
		return guardRelevant(facts,decision,dependencies,frame);
	}
	// Completion-only guards (including other bound returns) are not captured
	// by the payload shape relevance projection. Retain the full semantic
	// context instead of mixing normal and terminating caller environments.
	private record PhaseNodeKey(boolean occurrence,int id,ContextKey context) { }
	private static final class LegacyShapeContextKey implements ContextKey {
		private final Set<ExactGuardAssignment> guards;
		private final Map<LoopDecision,LoopPhase> loops;
		private final Map<LoopDecision,IterationPhase> iterations;
		private final Map<LoopCardinalityDecision,LoopCardinality> cardinalities;
		private final List<Integer> callFrame;
		private final int maxDepth;
		private final BitSet boundHeads;
		private final int hash;

		private LegacyShapeContextKey(Set<ExactGuardAssignment> guards,Map<LoopDecision,LoopPhase> loops,
			Map<LoopDecision,IterationPhase> iterations,
			Map<LoopCardinalityDecision,LoopCardinality> cardinalities,List<Integer> callFrame,
			int maxDepth,BitSet boundHeads) {
			this.guards=Set.copyOf(guards);
			this.loops=Map.copyOf(loops);
			this.iterations=Map.copyOf(iterations);
			this.cardinalities=Map.copyOf(cardinalities);
			this.callFrame=List.copyOf(callFrame);
			this.maxDepth=maxDepth;
			this.boundHeads=(BitSet)boundHeads.clone();
			hash=Objects.hash(this.guards,this.loops,this.iterations,this.cardinalities,
				this.callFrame,this.maxDepth,this.boundHeads);
		}

		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			if(this==other) return true;
			if(!(other instanceof LegacyShapeContextKey key) || hash!=key.hash) return false;
			return maxDepth==key.maxDepth && guards.equals(key.guards) && loops.equals(key.loops)
				&& iterations.equals(key.iterations) && cardinalities.equals(key.cardinalities)
				&& callFrame.equals(key.callFrame) && boundHeads.equals(key.boundHeads);
		}
	}

	private static final class IndexedContextKey implements ContextKey {
		private final int kind,maxDepth,hash;
		private final BitSet atoms,boundHeads;
		private final List<Integer> callFrame;
		private final ContextKey fallback;
		private IndexedContextKey(int kind,BitSet atoms,List<Integer> callFrame,int maxDepth,
			BitSet boundHeads,ContextKey fallback) {
			// Both bitsets are private builder results or shared read-only empty snapshots.
			this.kind=kind; this.atoms=atoms; this.callFrame=List.copyOf(callFrame);
			this.maxDepth=maxDepth; this.boundHeads=boundHeads; this.fallback=fallback;
			int result=kind;
			result=31*result+this.atoms.hashCode();
			result=31*result+this.callFrame.hashCode();
			result=31*result+maxDepth;
			result=31*result+this.boundHeads.hashCode();
			hash=31*result+(fallback==null ? 0 : fallback.hashCode());
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			if(this==other) return true;
			if(!(other instanceof IndexedContextKey key) || hash!=key.hash) return false;
			return kind==key.kind && maxDepth==key.maxDepth && atoms.equals(key.atoms)
				&& callFrame.equals(key.callFrame) && boundHeads.equals(key.boundHeads)
				&& Objects.equals(fallback,key.fallback);
		}
	}

	private ContextKey semanticContext(EvaluationContext context) {
		BitSet heads=headBindings(context,null);
		return indexedContexts ? indexedContextKey(context,null,heads,0)
			: legacySemanticContext(context,heads);
	}

	private LegacySemanticContext legacySemanticContext(EvaluationContext context,BitSet heads) {
		return new LegacySemanticContext(context,exactGuards(context.guards()),heads);
	}

	private ContextKey shapeContextKey(EvaluationContext context) {
		return shapeContextKey(context,null);
	}

	private ContextKey shapeContextKey(EvaluationContext context,ShapeDependencies dependencies) {
		// Layout/rule choices do not affect logical shape. Only headIsBound is
		// read by source resolution. Snapshot it, never retain a mutable lookup.
		// Completion dependencies include other bound returns before this projection.
		BitSet heads=headBindings(context,dependencies);
		return indexedContexts ? indexedContextKey(context,dependencies,heads,1)
			: legacyShapeContextKey(context,dependencies,heads);
	}

	private LegacyShapeContextKey legacyShapeContextKey(EvaluationContext context,
		ShapeDependencies dependencies,BitSet heads) {
		return new LegacyShapeContextKey(projectShapeGuards(context,dependencies),
			projectLoops(context.loops(),dependencies),projectLoops(context.iterationPhases(),dependencies),
			projectCardinalities(context.cardinalities(),dependencies),context.callFrame(),context.maxDepth(),heads);
	}

	private IndexedContextKey indexedContextKey(EvaluationContext context,ShapeDependencies dependencies,
		BitSet heads,int kind) {
		int maximumComponents=context.guards().size()+context.loops().size()
			+context.iterationPhases().size()+context.cardinalities().size();
		// The conservative bound makes the common path transactional without a
		// counting pass: even if every component is new, the complete key fits.
		if(indexedAtomCount+maximumComponents<=indexedAtomLimit)
			return cachedIndexedContextKey(context,dependencies,heads,kind);
		int missing=0;
		Set<MemoBindingResolver.GuardDecision> relevant=indexedRelevantGuards(context,dependencies);
		for(var entry:context.guards().entrySet()) if(includeGuard(context,dependencies,relevant,entry.getKey())) {
			ExactGuardAssignment atom=guardAssignment(entry.getKey(),entry.getValue());
			if(!indexedGuardAtoms.containsKey(atom)) missing++;
		}
		for(var entry:context.loops().entrySet()) if(includeLoop(dependencies,entry.getKey())) {
			if(indexedId(indexedLoopAtoms,entry.getKey(),entry.getValue().ordinal())<0) missing++;
		}
		for(var entry:context.iterationPhases().entrySet()) if(includeLoop(dependencies,entry.getKey())) {
			if(indexedId(indexedIterationAtoms,entry.getKey(),entry.getValue().ordinal())<0) missing++;
		}
		for(var entry:context.cardinalities().entrySet()) if(includeLoop(dependencies,entry.getKey())) {
			if(indexedId(indexedCardinalityAtoms,entry.getKey(),entry.getValue().ordinal())<0) missing++;
		}
		if(indexedAtomCount+missing>indexedAtomLimit) {
			ContextKey legacy=kind==0 ? legacySemanticContext(context,heads)
				: legacyShapeContextKey(context,dependencies,heads);
			return new IndexedContextKey(kind,new BitSet(),context.callFrame(),context.maxDepth(),heads,legacy);
		}
		return cachedIndexedContextKey(context,dependencies,heads,kind);
	}

	private IndexedContextKey cachedIndexedContextKey(EvaluationContext context,
		ShapeDependencies dependencies,BitSet heads,int kind) {
		ProjectionAtomKey key=new ProjectionAtomKey(context,dependencies,kind);
		projectionAtomRequests++;
		BitSet bits=projectionAtoms.get(key);
		if(bits!=null) projectionAtomHits++;
		if(bits==null) {
			bits=buildIndexedAtoms(context,dependencies);
			if(projectionAtoms.size()>=PROJECTION_ATOM_CACHE_ENTRIES) {
				projectionAtoms.clear(); projectionAtomClears++;
			}
			projectionAtoms.put(key,bits);
		}
		return new IndexedContextKey(kind,bits,context.callFrame(),context.maxDepth(),heads,null);
	}

	private BitSet buildIndexedAtoms(EvaluationContext context,ShapeDependencies dependencies) {
		BitSet bits=new BitSet();
		Set<MemoBindingResolver.GuardDecision> relevant=indexedRelevantGuards(context,dependencies);
		for(var entry:context.guards().entrySet()) if(includeGuard(context,dependencies,relevant,entry.getKey()))
			bits.set(indexedGuardId(guardAssignment(entry.getKey(),entry.getValue())));
		for(var entry:context.loops().entrySet()) if(includeLoop(dependencies,entry.getKey())) {
			bits.set(indexedId(indexedLoopAtoms,entry.getKey(),entry.getValue().ordinal(),LOOP_PHASE_COUNT));
		}
		for(var entry:context.iterationPhases().entrySet()) if(includeLoop(dependencies,entry.getKey())) {
			bits.set(indexedId(indexedIterationAtoms,entry.getKey(),entry.getValue().ordinal(),
				ITERATION_PHASE_COUNT));
		}
		for(var entry:context.cardinalities().entrySet()) if(includeLoop(dependencies,entry.getKey())) {
			bits.set(indexedId(indexedCardinalityAtoms,entry.getKey(),entry.getValue().ordinal(),
				LOOP_CARDINALITY_COUNT));
		}
		return bits;
	}

	private Set<MemoBindingResolver.GuardDecision> indexedRelevantGuards(EvaluationContext context,
		ShapeDependencies dependencies) {
		return dependencies==null || dependencies.guards().isEmpty() ? null
			: relevantGuardDecisions(dependencies.guards(),context.callFrame());
	}

	private boolean includeGuard(EvaluationContext context,ShapeDependencies dependencies,
		Set<MemoBindingResolver.GuardDecision> relevant,MemoBindingResolver.GuardDecision decision) {
		if(dependencies==null) return true;
		if(dependencies.guards().isEmpty()) return false;
		return relevant.contains(decision) || !framesEqual(context.callFrame(),decision.callFrame())
			&& relevantGuardDecisions(dependencies.guards(),decision.callFrame()).contains(decision);
	}

	private static boolean includeLoop(ShapeDependencies dependencies,LoopDecision decision) {
		return dependencies==null || dependencies.loops().contains(decision.loopId());
	}

	private static boolean includeLoop(ShapeDependencies dependencies,LoopCardinalityDecision decision) {
		return dependencies==null || dependencies.loops().contains(decision.loopId());
	}

	private int indexedGuardId(ExactGuardAssignment atom) {
		Integer id=indexedGuardAtoms.get(atom);
		if(id==null) { id=indexedAtomCount++; indexedGuardAtoms.put(atom,id); }
		return id;
	}

	private static <K> int indexedId(Map<K,int[]> atoms,K key,int ordinal) {
		int[] ids=atoms.get(key);
		return ids==null ? -1 : ids[ordinal];
	}

	private <K> int indexedId(Map<K,int[]> atoms,K key,int ordinal,int values) {
		int[] ids=atoms.get(key);
		if(ids==null) {
			ids=new int[values];
			for(int index=0;index<ids.length;index++) ids[index]=-1;
			atoms.put(key,ids);
		}
		if(ids[ordinal]<0) ids[ordinal]=indexedAtomCount++;
		return ids[ordinal];
	}

	private void indexShapeHeads(ShapeDependencies dependencies) {
		if(shapeHeadIndices.containsKey(dependencies)) return;
		int[] indices=new int[selectionSensitiveHeads.size()];
		int count=0;
		for(int index=0;index<selectionSensitiveHeads.size();index++)
			if(dependencies.heads().contains(selectionSensitiveHeads.get(index))) indices[count++]=index;
		shapeHeadIndices.put(dependencies,java.util.Arrays.copyOf(indices,count));
	}

	private static Map<LoopSlotIdentity,int[]> indexHeadOrdinals(List<LoopSlotIdentity> heads) {
		Map<LoopSlotIdentity,List<Integer>> grouped=new HashMap<>();
		for(int index=0;index<heads.size();index++)
			grouped.computeIfAbsent(heads.get(index),ignored -> new ArrayList<>()).add(index);
		Map<LoopSlotIdentity,int[]> result=new HashMap<>();
		grouped.forEach((head,indices) -> result.put(head,indices.stream().mapToInt(Integer::intValue).toArray()));
		return Map.copyOf(result);
	}

	private BitSet headBindings(EvaluationContext context,ShapeDependencies dependencies) {
		if(!(context.selections() instanceof PhaseEvaluation.HeadSelection))
			return legacyHeadBindings(context,dependencies);
		MemoBindingResolver.SelectionLookup lookup=context.selections();
		BitSet heads=EMPTY_HEAD_BINDINGS;
		// Private maps are confined to the suspended evaluation. A terminal callback
		// cannot mutate them; its live answers and per-ordinal call order remain intact.
		while(lookup instanceof PhaseEvaluation.HeadSelection bound) {
			if(dependencies==null || !dependencies.heads().isEmpty())
				for(LoopSlotKey key:bound.evaluation.heads.keySet()) {
					LoopSlotIdentity identity=new LoopSlotIdentity(key.loop().loopId(),key.slot());
					if(dependencies!=null && !dependencies.heads().contains(identity)) continue;
					int[] indices=headOrdinals.get(identity);
					if(indices==null) continue;
					if(heads==EMPTY_HEAD_BINDINGS) heads=new BitSet();
					for(int index:indices) heads.set(index);
				}
			lookup=bound.delegate;
		}
		if(lookup==MemoBindingResolver.NO_SELECTION) return heads;
		int[] indices=shapeHeadIndices.get(dependencies);
		if(indices!=null) {
			for(int headIndex:indices) {
				LoopSlotIdentity head=selectionSensitiveHeads.get(headIndex);
				if(heads.get(headIndex) || lookup.headIsBound(head.loopId(),head.slot())) {
					if(heads==EMPTY_HEAD_BINDINGS) heads=new BitSet();
					heads.set(headIndex);
				}
			}
		}
		else for(int index=0;index<selectionSensitiveHeads.size();index++) {
			LoopSlotIdentity head=selectionSensitiveHeads.get(index);
			if(dependencies!=null && !dependencies.heads().contains(head)) continue;
			if(heads.get(index) || lookup.headIsBound(head.loopId(),head.slot())) {
				if(heads==EMPTY_HEAD_BINDINGS) heads=new BitSet();
				heads.set(index);
			}
		}
		return heads;
	}

	private BitSet legacyHeadBindings(EvaluationContext context,ShapeDependencies dependencies) {
		BitSet heads=EMPTY_HEAD_BINDINGS; int index=0;
		if(context.selections()==MemoBindingResolver.NO_SELECTION) return heads;
		int[] indices=shapeHeadIndices.get(dependencies);
		if(indices!=null) {
			for(int headIndex:indices) {
				LoopSlotIdentity head=selectionSensitiveHeads.get(headIndex);
				if(context.selections().headIsBound(head.loopId(),head.slot())) {
					if(heads==EMPTY_HEAD_BINDINGS) heads=new BitSet();
					heads.set(headIndex);
				}
			}
			return heads;
		}
		if(context.selections()!=MemoBindingResolver.NO_SELECTION)
			for(LoopSlotIdentity head:selectionSensitiveHeads) {
				if((dependencies==null || dependencies.heads().contains(head))
					&& context.selections().headIsBound(head.loopId(),head.slot())) {
					if(heads==EMPTY_HEAD_BINDINGS) heads=new BitSet();
					heads.set(index);
				}
				index++;
			}
		return heads;
	}

	private Set<ExactGuardAssignment> projectShapeGuards(EvaluationContext context,
		ShapeDependencies dependencies) {
		if(dependencies==null) return exactGuards(context.guards());
		if(dependencies.guards().isEmpty()) return Set.of();
		List<ExactGuardAssignment> result=new ArrayList<>();
		Set<MemoBindingResolver.GuardDecision> relevant=relevantGuardDecisions(dependencies.guards(),context.callFrame());
		for(var entry:context.guards().entrySet()) {
			MemoBindingResolver.GuardDecision decision=entry.getKey();
			if(relevant.contains(decision) || !framesEqual(context.callFrame(),decision.callFrame())
				&& relevantGuardDecisions(dependencies.guards(),decision.callFrame()).contains(decision))
				result.add(guardAssignment(decision,entry.getValue()));
		}
		return Set.of(result.toArray(ExactGuardAssignment[]::new));
	}

	private static <V> Map<LoopDecision,V> projectLoops(Map<LoopDecision,V> values,
		ShapeDependencies dependencies) {
		if(dependencies==null || values.isEmpty()) return values;
		if(dependencies.loops().isEmpty()) return Map.of();
		boolean all=true;
		for(LoopDecision key:values.keySet()) if(!dependencies.loops().contains(key.loopId())) { all=false; break; }
		if(all) return values;
		Map<LoopDecision,V> result=new LinkedHashMap<>();
		for(var entry:values.entrySet()) if(dependencies.loops().contains(entry.getKey().loopId()))
			result.put(entry.getKey(),entry.getValue());
		return result;
	}

	private static Map<LoopCardinalityDecision,LoopCardinality> projectCardinalities(
		Map<LoopCardinalityDecision,LoopCardinality> values,ShapeDependencies dependencies) {
		if(dependencies==null || values.isEmpty()) return values;
		if(dependencies.loops().isEmpty()) return Map.of();
		boolean all=true;
		for(LoopCardinalityDecision key:values.keySet()) if(!dependencies.loops().contains(key.loopId())) { all=false; break; }
		if(all) return values;
		Map<LoopCardinalityDecision,LoopCardinality> result=new LinkedHashMap<>();
		for(var entry:values.entrySet()) if(dependencies.loops().contains(entry.getKey().loopId()))
			result.put(entry.getKey(),entry.getValue());
		return result;
	}

	Object projectedShapeKeyForBinding(int binding,EvaluationContext context) {
		return shapeContextKey(normalizeLoopPhases(context),shapeDependencies(binding));
	}

	Object projectedShapeKeyForOccurrence(int occurrence,EvaluationContext context) {
		return shapeContextKey(normalizeLoopPhases(context),shapeDependenciesByOccurrence.get(occurrence));
	}

	private EvaluationContext normalizeLoopPhases(EvaluationContext context) {
		boolean complete=true;
		for(LoopDecision loop:context.iterationPhases().keySet())
			if(!context.loops().containsKey(loop)) { complete=false; break; }
		if(complete) return context;
		Map<LoopDecision,LoopPhase> loops = new HashMap<>(context.loops());
		for(var entry : context.iterationPhases().entrySet())
			loops.putIfAbsent(entry.getKey(),entry.getValue()==IterationPhase.FIRST
				? LoopPhase.ENTRY : LoopPhase.BACKEDGE);
		return new EvaluationContext(context.guards(),loops,context.selections(),context.maxDepth(),
			context.callFrame(),context.iterationPhases(),context.cardinalities());
	}

	private final class PhaseEvaluation {
		private final Map<LoopSlotKey,Shape> heads = new HashMap<>();
		private final Set<LoopSlotKey> evaluating = new LinkedHashSet<>();
		private final class HeadSelection implements MemoBindingResolver.SelectionLookup {
			private final PhaseEvaluation evaluation=PhaseEvaluation.this;
			private final MemoBindingResolver.SelectionLookup delegate;

			private HeadSelection(MemoBindingResolver.SelectionLookup delegate) {
				this.delegate=delegate;
			}

			@Override public int selectedRule(int owner) { return delegate.selectedRule(owner); }
			@Override public MemoPartition selectedLayout(int owner) { return delegate.selectedLayout(owner); }
			@Override public MemoPartition selectedHeadLayout(int loop,int slot) {
				return delegate.selectedHeadLayout(loop,slot);
			}
			@Override public boolean headIsBound(int loop,int slot) {
				for(LoopSlotKey key:heads.keySet())
					if(key.loop().loopId()==loop && key.slot()==slot) return true;
				return delegate.headIsBound(loop,slot);
			}
		}

		private Shape head(ResolvedOrigin origin,EvaluationContext context,Set<PhaseVisit> active) {
			LoopDecision loop=new LoopDecision(origin.referenceId(),origin.callFrame());
			return head(loop,origin.boundarySlot(),context);
		}

		private Shape head(LoopDecision loop,int slot,EvaluationContext context) {
			IterationPhase requested=context.iterationPhases().get(loop);
			if(requested==null || requested==IterationPhase.FIRST) return unknown((Hop)null);
			LoopSlotKey key=new LoopSlotKey(loop,slot);
			Shape known=heads.get(key);
			if(known!=null) return known;
			if(!evaluating.add(key)) return unknown((Hop)null);
			try {
				MemoProgramFacts.CarriedValue carried=carriedValue(loop.loopId(),slot);
				if(carried==null) return unknown((Hop)null);
				EvaluationContext first=withPhase(context,loop,IterationPhase.FIRST,LoopPhase.ENTRY);
				Shape seed=resolvedBindingShape(carried.backedgeBindingId(),first,this,new LinkedHashSet<>());
				if(!seed.exact()) return unknown((Hop)null);

				// A repeat phase is grounded only by the proven first iteration. The
				// contract is accepted when one further body application preserves it.
				heads.put(key,seed);
				EvaluationContext middle=withPhase(context,loop,IterationPhase.MIDDLE,LoopPhase.BACKEDGE);
				Shape repeat=resolvedBindingShape(carried.backedgeBindingId(),middle,this,new LinkedHashSet<>());
				if(!repeat.exact() || !sameDimensions(seed,repeat)) {
					heads.remove(key);
					return unknown((Hop)null);
				}
				return seed;
			}
			finally { evaluating.remove(key); }
		}

		private EvaluationContext bindDirectHeads(int binding,EvaluationContext context) {
			for(MemoBindingResolver.DirectLoopHead slot :
				resolver.directLoopHeadIdentities(binding,context))
				head(new LoopDecision(slot.loopId(),slot.callFrame()),slot.slot(),context);
			if(context.selections() instanceof PhaseEvaluation.HeadSelection bound
				&& bound.evaluation==this) return context;
			MemoBindingResolver.SelectionLookup prior=context.selections();
			MemoBindingResolver.SelectionLookup bound=new HeadSelection(prior);
			return new EvaluationContext(context.guards(),context.loops(),bound,context.maxDepth(),
				context.callFrame(),context.iterationPhases(),context.cardinalities());
		}
	}

	private EvaluationContext withPhase(EvaluationContext context,LoopDecision loop,
		IterationPhase iteration,LoopPhase boundary) {
		Map<LoopDecision,IterationPhase> iterations=new HashMap<>(context.iterationPhases());
		iterations.put(loop,iteration);
		Map<LoopDecision,LoopPhase> loops=new HashMap<>(context.loops());
		loops.put(loop,boundary);
		Map<MemoBindingResolver.GuardDecision,Boolean> guards=new HashMap<>(context.guards());
		// Activation resolution contributes the guard value of the currently
		// visited iteration. When proving the loop contract at another phase, a
		// phase-proven guard must be recomputed rather than carried across phases.
		guards.keySet().removeIf(guard -> phaseProvesGuard(loop,iteration,guard));
		return new EvaluationContext(guards,loops,context.selections(),context.maxDepth(),
			context.callFrame(),iterations,context.cardinalities());
	}

	private List<List<Set<Integer>>> computePhaseGuardDependencies() {
		List<List<Set<Integer>>> loops=new ArrayList<>(facts.loops().size());
		for(MemoProgramFacts.LoopRecord loop:facts.loops()) {
			List<Set<Integer>> phases=new ArrayList<>(ITERATION_PHASE_COUNT);
			for(IterationPhase phase:IterationPhase.values()) {
				Set<Integer> known=new LinkedHashSet<>();
				for(MemoProgramFacts.GuardPhase guard:facts.guardPhases(loop.id()))
					if(facts.guardValue(loop.id(),guard.guardId(),phase)!=null) known.add(guard.guardId());
				phases.add(Set.copyOf(known));
			}
			loops.add(List.copyOf(phases));
		}
		return List.copyOf(loops);
	}

	private boolean phaseProvesGuard(LoopDecision loop,IterationPhase iteration,
		MemoBindingResolver.GuardDecision decision) {
		if(iteration==null || !validCallFrame(loop.callFrame()) || !validCallFrame(decision.callFrame()))
			return phaseProvesGuardReference(loop,iteration,decision);
		if(loop.loopId()<0 || loop.loopId()>=phaseGuardDependencies.size()) return false;
		Set<Integer> dependencies=phaseGuardDependencies.get(loop.loopId()).get(iteration.ordinal());
		return !dependencies.isEmpty() && guardRelevant(decision,dependencies,loop.callFrame());
	}

	private boolean validCallFrame(List<Integer> frame) {
		for(int call:frame) if(call<0 || call>=facts.calls().size()) return false;
		return true;
	}

	private boolean phaseProvesGuardReference(LoopDecision loop,IterationPhase iteration,
		MemoBindingResolver.GuardDecision decision) {
		for(MemoProgramFacts.GuardPhase phase:facts.guardPhases(loop.loopId())) {
			int guard=phase.guardId();
			if(facts.guardValue(loop.loopId(),guard,iteration)==null) continue;
			for(int length=loop.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,loop.callFrame().subList(0,length)))
					return true;
			for(int length=decision.callFrame().size();length>=0;length--)
				if(facts.guardDecisionMatches(decision,guard,decision.callFrame().subList(0,length)))
					return true;
		}
		return false;
	}

	private static EvaluationContext withEnvironment(EvaluationContext context,
		MemoBindingResolver.ResolvedEnvironment environment) {
		return new EvaluationContext(environment.guards(),environment.loops(),context.selections(),
			context.maxDepth(),context.callFrame(),environment.iterationPhases(),environment.cardinalities());
	}

	private Shape resolvedBindingShape(int binding,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(binding < 0 || binding >= bindingShapes.length) return unknown((Hop)null);
		binding=resolvedShapeAliasRepresentatives[binding];
		MemoProgramFacts.BindingNode node=facts.binding(binding);
		if(node.kind()==BindingKind.SELECT) {
			MemoBindingResolver.GuardDecision guard=
				facts.guardDecision(node.referenceId(),context.callFrame());
			Boolean selected=context.guards().get(guard);
			if(selected!=null) selected=facts.branchGuardValue(node.referenceId(),context.callFrame(),selected);
			int loop=facts.guardEnclosingLoop(node.referenceId());
			if(selected==null && loop>=0) {
				IterationPhase phase=context.iterationPhases().get(new LoopDecision(loop,context.callFrame()));
				if(phase!=null) selected=facts.guardValue(loop,node.referenceId(),phase);
			}
			if(selected!=null)
				return resolvedBindingShape(node.children().get(selected ? 0 : 1),context,phaseEvaluation,active);
			Shape left=resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
			Shape right=resolvedBindingShape(node.children().get(1),context,phaseEvaluation,active);
			return commonShape(left,right);
		}
		if(node.kind()==BindingKind.LOOP_HEAD) {
			LoopDecision loop=new LoopDecision(node.referenceId(),context.callFrame());
			IterationPhase phase=context.iterationPhases().get(loop);
			if(phase==IterationPhase.FIRST)
				return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
			if(phase!=null) return phaseEvaluation.head(loop,node.position(),context);
			return bindingShapes[binding];
		}
		if(node.kind()==BindingKind.LAST) {
			LoopDecision loop=new LoopDecision(node.referenceId(),context.callFrame());
			LoopCardinality cardinality=context.cardinalities().get(
				new LoopCardinalityDecision(node.referenceId(),context.callFrame()));
			if(cardinality==LoopCardinality.ZERO)
				return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
			if(cardinality!=null && node.children().size()>1) {
				IterationPhase phase=cardinality==LoopCardinality.ONE
					? IterationPhase.FIRST : IterationPhase.LAST;
				return resolvedBindingShape(node.children().get(1),
					withPhase(context,loop,phase,LoopPhase.BACKEDGE),phaseEvaluation,active);
			}
			return bindingShapes[binding];
		}
		if(node.kind()==BindingKind.CALL_ARG)
			return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
		if(node.kind()==BindingKind.FORMAL) {
			for(int index=context.callFrame().size()-1; index>=0; index--) {
				MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
				if(call.functionSummaryId()==node.referenceId() && node.position()>=0
					&& node.position()<call.actualBindingIds().size())
					return resolvedBindingShape(call.actualBindingIds().get(node.position()),
						context.withCallFrame(context.callFrame().subList(0,index)),phaseEvaluation,active);
			}
			return bindingShapes[binding];
		}
		if(node.kind()==BindingKind.CALL_RETURN)
			return returnedShape(node,context,phaseEvaluation,active);
		// A transient definition with a child is a value-version alias, not a
		// native producer. The ordinary shape worklist uses the same rule in
		// evaluateBinding. Preserve it here before invoking source resolution:
		// resolving the wrapper as an origin can revisit its loop SELECT through
		// a backedge and add a spurious UNRESOLVED arm even when the bound loop
		// head has an exact inductive shape.
		if(node.kind()==BindingKind.DEFINITION && !node.children().isEmpty())
			return resolvedBindingShape(node.children().get(0),context,phaseEvaluation,active);
		Set<Integer> loopDependencies=phaseSensitiveByBinding.get(binding);
		Set<Integer> guardDependencies=guardSensitiveByBinding.get(binding);
		boolean phaseRelevant=false;
		for(LoopDecision loop:context.iterationPhases().keySet())
			if(loopDependencies.contains(loop.loopId())) { phaseRelevant=true; break; }
		if(!phaseRelevant && !hasRelevantGuard(context,guardDependencies)) return bindingShapes[binding];
		PhaseNodeKey cacheKey=new PhaseNodeKey(false,binding,
			shapeContextKey(context,shapeDependenciesByBinding.get(binding)));
		phaseShapeRequests++;
		Shape cached=phaseShapeCache.get(cacheKey);
		if(cached!=null) { phaseShapeCacheHits++; return cached; }
		PhaseVisit key=new PhaseVisit(false,binding,context.callFrame());
		if(!active.add(key)) return unknown((Hop)null);
		try {
			EvaluationContext grounded=phaseEvaluation.bindDirectHeads(binding,context);
			Shape[] joined={null};
			MemoBindingResolver.VisitResult visit=resolver.visitJoint(List.of(binding),grounded,environment -> {
				EvaluationContext resolved=withEnvironment(grounded,environment);
				Shape current=originShape(environment.origins().get(0),resolved,phaseEvaluation,active);
				joined[0]=joined[0]==null ? current : commonShape(joined[0],current);
				return true;
			});
			Shape result=visit.status()==MemoBindingResolver.VisitStatus.COMPLETE
				? joined[0]==null ? bindingShapes[binding] : joined[0] : unknown((Hop)null);
			if(visit.status()==MemoBindingResolver.VisitStatus.COMPLETE) phaseShapeCache.put(cacheKey,result);
			return result;
		}
		finally { active.remove(key); }
	}

	private Shape returnedShape(MemoProgramFacts.BindingNode binding,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(binding.children().isEmpty() || binding.referenceId()<0
			|| binding.referenceId()>=facts.calls().size()) return unknown((Hop)null);
		int completion=facts.call(binding.referenceId()).completionBindingId();
		if(completion<0) return unknown((Hop)null);
		// Keep the established payload/loop induction path when completion is
		// total. Resolving every source again can reopen grounded backedge cycles.
		if(resolver.alwaysAvailable(completion,context.callFrame()))
			return returnedPayloadShape(binding,context,phaseEvaluation,active);
		PhaseVisit key=new PhaseVisit(false,binding.id(),context.callFrame());
		if(!active.add(key)) return unknown((Hop)null);
		try {
			EvaluationContext grounded=phaseEvaluation.bindDirectHeads(binding.id(),context);
			Shape[] joined={null};
			var visit=resolver.visitJoint(List.of(completion),grounded,environment -> {
				OriginKind kind=environment.origins().get(0).kind();
				// The function still terminates on this path; only its caller's
				// nonexistent return value is excluded from the shape join.
				if(kind==OriginKind.TERMINATED_AT_RETURN) return true;
				Shape current=kind==OriginKind.NORMAL_CONTINUATION
					? returnedPayloadShape(binding,withEnvironment(grounded,environment),phaseEvaluation,active)
					: unknown((Hop)null);
				joined[0]=joined[0]==null ? current : commonShape(joined[0],current);
				return true;
			});
			return visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED || joined[0]==null
				? unknown((Hop)null) : joined[0];
		}
		finally { active.remove(key); }
	}

	private boolean hasNormalContinuation(int activation,EvaluationContext context) {
		if(resolver.alwaysAvailable(activation,context.callFrame())) return true;
		boolean[] normal={false};
		var visit=resolver.visitJoint(List.of(activation),context,environment -> {
			normal[0] |= environment.origins().get(0).kind()==OriginKind.NORMAL_CONTINUATION;
			return true;
		});
		return visit.status()==MemoBindingResolver.VisitStatus.COMPLETE && normal[0];
	}

	private Shape returnedPayloadShape(MemoProgramFacts.BindingNode binding,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(binding.children().size()>1)
			return resolvedBindingShape(binding.children().get(1),context,phaseEvaluation,active);
		List<Integer> frame=new ArrayList<>(context.callFrame());
		frame.add(binding.referenceId());
		return resolvedBindingShape(binding.children().get(0),context.withCallFrame(frame),phaseEvaluation,active);
	}

	private Shape originShape(ResolvedOrigin origin,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		if(origin.kind()==OriginKind.COMPILED_OWNER) {
			Shape slot=multiReturnOutputShape(origin,context,phaseEvaluation,active);
			if(slot!=null) return slot;
			return occurrenceShape(origin.referenceId(),context.withCallFrame(origin.callFrame()),phaseEvaluation,active);
		}
		if(origin.kind()==OriginKind.LOOP_HEAD_CONTRACT) {
			return phaseEvaluation.head(origin,context,active);
		}
		if(origin.kind()==OriginKind.ITERATOR_LOCAL)
			return new Shape(DataType.SCALAR,ValueType.INT64,-1,-1,-1,Knowledge.EXACT);
		return unknown((Hop)null);
	}

	private Shape multiReturnOutputShape(ResolvedOrigin origin,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		Shape result=multiReturnOutputShape(origin.referenceId(),origin.boundarySlot());
		if(result!=null && result.exact() || origin.boundarySlot()!=0) return result;
		Hop hop=facts.occurrence(origin.referenceId()).hop();
		if(!shapePreservingTransformEncode(hop) || hop.getInput().isEmpty()) return result;
		Shape input=resolvedBindingShape(facts.inputBinding(origin.referenceId(),0),
			context.withCallFrame(origin.callFrame()),phaseEvaluation,active);
		return input.exact() ? new Shape(DataType.MATRIX,ValueType.FP64,input.rows(),input.columns(),
			-1,Knowledge.EXACT) : result;
	}

	private Shape multiReturnOutputShape(int occurrence,int slot) {
		if(slot<0 || occurrence<0 || occurrence>=facts.occurrences().size()) return null;
		Hop hop=facts.occurrence(occurrence).hop();
		if(!(hop instanceof FunctionOp function)
			|| function.getFunctionType()!=FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| function.getOutputs()==null || slot>=function.getOutputs().size()) return null;
		Hop output=function.getOutputs().get(slot);
		Shape result=output==null ? null : fromView(output);
		if(slot==0 && (result==null || !result.exact()) && shapePreservingTransformEncode(function)
			&& !function.getInput().isEmpty()) {
			int inputBinding=facts.inputBinding(occurrence,0);
			Shape input=inputBinding>=0 ? bindingShapes[inputBinding] : null;
			if(input!=null && input.exact())
				return new Shape(DataType.MATRIX,ValueType.FP64,input.rows(),input.columns(),-1,Knowledge.EXACT);
		}
		return result;
	}

	private static void applyMultiReturnOutputShapes(Hop hop) {
		if(!(hop instanceof FunctionOp function) || !shapePreservingTransformEncode(function)
			|| function.getInput().get(0).getDim1()<=0 || function.getInput().get(0).getDim2()<=0
			|| function.getOutputs()==null || function.getOutputs().isEmpty()) return;
		Hop input=function.getInput().get(0);
		Hop output=function.getOutputs().get(0);
		if(output!=null)
			applyShape(output,new Shape(DataType.MATRIX,ValueType.FP64,input.getDim1(),input.getDim2(),
				-1,Knowledge.EXACT));
	}

	private static boolean shapePreservingTransformEncode(Hop hop) {
		if(!(hop instanceof FunctionOp function)
			|| function.getFunctionType()!=FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| !function.getFunctionName().equalsIgnoreCase(Opcodes.TRANSFORMENCODE.toString())
			|| function.getInput().size()!=2 || !(function.getInput(1) instanceof LiteralOp literal)) return false;
		try {
			JSONObject spec=new JSONObject(literal.getStringValue());
			return spec.containsKey("recode") && !spec.containsKey("dummycode")
				&& !spec.containsKey("omit") && !spec.containsKey("impute")
				&& !spec.containsKey("bin") && !spec.containsKey("hash");
		}
		catch(Exception ignored) { return false; }
	}

	private Shape occurrenceShape(int occurrence,EvaluationContext context,Set<PhaseVisit> active) {
		return occurrenceShape(occurrence,context,new PhaseEvaluation(),active);
	}
	private enum ScalarReplacementKind { NONE, INT64, FP64 }
	private record ScalarReplacement(ScalarReplacementKind kind,long longValue,double doubleValue) {
		private static final ScalarReplacement NONE=new ScalarReplacement(ScalarReplacementKind.NONE,0,0);
		private static ScalarReplacement of(Long value) {
			return value==null ? NONE : new ScalarReplacement(ScalarReplacementKind.INT64,value,0);
		}
		private static ScalarReplacement of(Double value) {
			return value==null ? NONE : new ScalarReplacement(ScalarReplacementKind.FP64,0,value);
		}
	}
	private static final class OccurrenceTransferKey {
		private final int occurrence;
		private final List<Shape> inputs;
		private final List<ScalarReplacement> scalarReplacements;
		private final int hash;

		private OccurrenceTransferKey(int occurrence,List<Shape> inputs,
			List<ScalarReplacement> scalarReplacements) {
			// Both lists are fresh request-local builders and are never mutated after
			// this key takes ownership.
			this.occurrence=occurrence;
			this.inputs=inputs;
			this.scalarReplacements=scalarReplacements;
			hash=31*(31*Integer.hashCode(occurrence)+inputs.hashCode())
				+scalarReplacements.hashCode();
		}

		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object value) {
			if(this==value) return true;
			if(!(value instanceof OccurrenceTransferKey other)) return false;
			return occurrence==other.occurrence && inputs.equals(other.inputs)
				&& scalarReplacements.equals(other.scalarReplacements);
		}
	}

	private Shape occurrenceShape(int occurrence,EvaluationContext context,
		PhaseEvaluation phaseEvaluation,Set<PhaseVisit> active) {
		// Binding results already retain full context. Reuse the pure transfer below
		// only after inputs, continuation and scalar replacements have been resolved.
		PhaseVisit key=new PhaseVisit(true,occurrence,context.callFrame());
		if(!active.add(key)) return unknown(facts.occurrence(occurrence).hop());
		try {
			Hop template=views[facts.occurrence(occurrence).compiledOwnerId()];
			List<Shape> inputs=new ArrayList<>(template.getInput().size());
			boolean transferable=true;
			for(int position=0;position<template.getInput().size();position++) {
				Shape input=resolvedBindingShape(facts.inputBinding(occurrence,position),context,phaseEvaluation,active);
				// A terminated/missing value has no contextual type. Do not revive
				// its consumer using a frozen shape from a different normal path.
				if(input==null || input.dataType()==DataType.UNKNOWN) {
					int activation=facts.occurrence(occurrence).activationBindingId();
					if(activation>=0 && !hasNormalContinuation(activation,context)) {
						Shape result=unknown(template);
						return result;
					}
					transferable=false;
				}
				inputs.add(input);
			}
			List<ScalarReplacement> replacements=new ArrayList<>(template.getInput().size());
			for(int position=0;position<template.getInput().size();position++) {
				Hop input=template.getInput(position);
				if(input.getDataType().isScalar() && !(input instanceof LiteralOp)) {
					if(input.getValueType()==ValueType.INT64) {
						Long value=scalarLong(facts.inputBinding(occurrence,position),context,new LinkedHashSet<>());
						replacements.add(ScalarReplacement.of(value));
					}
					else if(costRelevantNumericScalar(template,position)) {
						Double value=scalarDouble(facts.inputBinding(occurrence,position),context,new LinkedHashSet<>());
						replacements.add(ScalarReplacement.of(value));
					}
					else replacements.add(ScalarReplacement.NONE);
				}
				else replacements.add(ScalarReplacement.NONE);
			}
			OccurrenceTransferKey transferKey=transferable && occurrenceTransferEnabled
				? new OccurrenceTransferKey(occurrence,inputs,replacements) : null;
			occurrenceTransferRequests++;
			Shape result=transferKey==null ? null : occurrenceTransferCache.get(transferKey);
			if(result!=null) {
				occurrenceTransferCacheHits++;
				return result;
			}
			Hop view=cloneView(template);
			for(int position=0;position<inputs.size();position++) applyShape(view.getInput(position),inputs.get(position));
			for(int position=0;position<replacements.size();position++) {
				ScalarReplacement replacement=replacements.get(position);
				if(replacement.kind()==ScalarReplacementKind.INT64)
					view.getInput().set(position,new LiteralOp(replacement.longValue()));
				else if(replacement.kind()==ScalarReplacementKind.FP64)
					view.getInput().set(position,new LiteralOp(replacement.doubleValue()));
			}
			applyMultiReturnOutputShapes(view);
			if(isMatrixLike(view) && !view.getInput().isEmpty()) {
				view.setDim1(-1); view.setDim2(-1); view.setNnz(-1);
			}
			view.refreshSizeInformation();
			Shape derived=fromView(view);
			result=derived.exact() ? derived : occurrenceOutputShape(facts.occurrence(occurrence));
			occurrenceTransferBuilds++;
			if(transferKey!=null && derived.exact()) occurrenceTransferCache.put(transferKey,result);
			return result;
		}
		catch(RuntimeException ignored) {
			return occurrenceOutputShape(facts.occurrence(occurrence));
		}
		finally { active.remove(key); }
	}

	/**
	 * Private operator view with occurrence-specific input shapes. One compiled
	 * owner can occur at different value versions, so its owner-wide input join
	 * is insufficient for native capability and cost evaluation.
	 */
	public Hop viewAt(int occurrence) {
		Hop cached = occurrenceViews[occurrence];
		if(cached == null) {
			MemoProgramFacts.Occurrence use = facts.occurrence(occurrence);
			cached = cloneView(views[use.compiledOwnerId()]);
			applyShape(cached, occurrenceOutputShape(use));
			for(int position = 0; position < cached.getInput().size(); position++)
				applyShape(cached.getInput(position), inputShape(occurrence, position));
			applyMultiReturnOutputShapes(cached);
			occurrenceViews[occurrence] = cached;
		}
		// Oracle and runtime-cost preparation operate on mutable HOP APIs. Never
		// let one consumer's refresh/selection annotations corrupt the cached fact.
		return cloneView(cached);
	}

	private Shape occurrenceOutputShape(MemoProgramFacts.Occurrence occurrence) {
		if(isTransientRead(occurrence.hop())) {
			int binding = facts.valueBinding(occurrence.id());
			if(binding >= 0) return bindingShapes[binding];
		}
		Shape dataGen = dataGenShape(occurrence);
		if(dataGen != null) return dataGen;
		Shape derived = refreshOccurrenceShape(occurrence);
		if(derived != null && derived.exact()) return derived;
		return ownerShapes[occurrence.compiledOwnerId()];
	}

	/**
	 * Derives an output from the exact reaching definitions at this occurrence.
	 * An owner-wide join deliberately loses dimensions when one compiled HOP is
	 * reused at multiple value versions, but each semantic occurrence can still
	 * have an exact operator result.
	 */
	private Shape refreshOccurrenceShape(MemoProgramFacts.Occurrence occurrence) {
		Hop view = cloneView(views[occurrence.compiledOwnerId()]);
		for(int position = 0; position < view.getInput().size(); position++) {
			int binding = facts.inputBinding(occurrence.id(),position);
			if(binding < 0 || bindingShapes[binding] == null) return null;
			applyShape(view.getInput(position),bindingShapes[binding]);
		}
		try {
			if(isMatrixLike(view) && !view.getInput().isEmpty()) {
				view.setDim1(-1);
				view.setDim2(-1);
				view.setNnz(-1);
			}
			view.refreshSizeInformation();
			return fromView(view);
		}
		catch(RuntimeException ignored) {
			return null;
		}
	}

	private Shape dataGenOwnerShape(int owner) {
		Shape result = null;
		for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
			Shape current = dataGenShape(occurrence);
			if(current == null) return null;
			result = result == null ? current : commonShape(result,current);
		}
		return result;
	}

	private Shape dataGenShape(MemoProgramFacts.Occurrence occurrence) {
		if(!(occurrence.hop() instanceof DataGenOp dataGen)
			|| dataGen.getOp() != OpOpDG.RAND && dataGen.getOp() != OpOpDG.SINIT
				&& dataGen.getOp() != OpOpDG.FRAMEINIT)
			return null;
		Integer rowPosition = dataGen.getParamIndexMap().get(DataExpression.RAND_ROWS);
		Integer columnPosition = dataGen.getParamIndexMap().get(DataExpression.RAND_COLS);
		Long rows = scalarLongAtInput(occurrence.id(),rowPosition);
		Long columns = scalarLongAtInput(occurrence.id(),columnPosition);
		return rows != null && rows > 0 && columns != null && columns > 0
			? exact(dataGen,rows,columns,-1) : null;
	}

	private Long scalarLongAtInput(int occurrence, Integer position) {
		if(position == null || position < 0) return null;
		int binding = facts.inputBinding(occurrence, position);
		return binding >= 0 ? scalarLong(binding, new LinkedHashSet<>()) : null;
	}

	public List<Diagnostic> diagnostics() {
		return List.copyOf(diagnostics);
	}

	public ShapeHint hint(int owner) {
		Hop hop = view(owner);
		Shape output = shape(owner);
		List<Shape> payloads = payloadInputShapes(hop,null);
		Shape first = payloads.size() > 0 ? payloads.get(0) : null;
		Shape second = payloads.size() > 1 ? payloads.get(1) : null;
		return new ShapeHint(dimension(output, true), dimension(output, false), hop.getBlocksize(),
			Optional.empty(), dimension(first, true), dimension(first, false),
			dimension(second, true), dimension(second, false));
	}

	public ShapeHint hintAt(int occurrence) {
		Hop hop = viewAt(occurrence);
		Shape output = occurrenceOutputShape(facts.occurrence(occurrence));
		List<Shape> payloads = payloadInputShapes(hop,occurrence);
		Shape first = payloads.size() > 0 ? payloads.get(0) : null;
		Shape second = payloads.size() > 1 ? payloads.get(1) : null;
		return new ShapeHint(dimension(output, true), dimension(output, false), hop.getBlocksize(),
			Optional.empty(), dimension(first, true), dimension(first, false),
			dimension(second, true), dimension(second, false));
	}

	private Shape occurrenceInputShape(int occurrence, Hop hop, int position) {
		return position < hop.getInput().size() ? inputShape(occurrence, position) : null;
	}

	private List<Shape> payloadInputShapes(Hop hop, Integer occurrence) {
		List<Shape> result = new ArrayList<>(2);
		for(int position = 0; position < hop.getInput().size() && result.size() < 2; position++)
			if(isMatrixLike(hop.getInput(position)))
				result.add(occurrence == null ? inputShape(hop,position)
					: occurrenceInputShape(occurrence,hop,position));
		return result;
	}

	private void indexDependencies() {
		for(MemoProgramFacts.Occurrence occurrence : facts.occurrences()) {
			int consumer = occurrence.compiledOwnerId();
			for(Hop input : occurrence.hop().getInput()) {
				int inputOwner = facts.compiledOwnerId(input);
				if(inputOwner >= 0) add(ownerConsumers.get(inputOwner), consumer);
			}
			if(isTransientRead(occurrence.hop())) {
				int binding = facts.valueBinding(occurrence.id());
				if(binding >= 0) add(bindingOwnerConsumers.get(binding), consumer);
			}
			for(int position = 0; position < occurrence.hop().getInput().size(); position++) {
				int binding = facts.inputBinding(occurrence.id(),position);
				if(binding >= 0) add(bindingOwnerConsumers.get(binding),consumer);
			}
		}
		for(MemoProgramFacts.BindingNode binding : facts.bindings()) {
			for(int child : binding.children())
				if(child >= 0) add(bindingConsumers.get(child), binding.id());
			if(binding.kind() == BindingKind.DEFINITION && binding.children().isEmpty()
				&& binding.referenceId() >= 0 && binding.referenceId() < facts.occurrences().size()) {
				add(ownerBindingConsumers.get(
					facts.occurrence(binding.referenceId()).compiledOwnerId()), binding.id());
				MemoProgramFacts.Occurrence definition = facts.occurrence(binding.referenceId());
				for(int position = 0; position < definition.hop().getInput().size(); position++) {
					int input = facts.inputBinding(definition.id(),position);
					if(input >= 0) add(bindingConsumers.get(input),binding.id());
					if(definition.hop().getInput(position).getDataType().isScalar())
						indexScalarValueDependencies(binding.id(),
							input,new LinkedHashSet<>());
				}
			}
		}
		for(MemoProgramFacts.FunctionSummary function : facts.functions()) {
			for(int position = 0; position < function.formalBindingIds().size(); position++) {
				int formal = function.formalBindingIds().get(position);
				for(MemoProgramFacts.CallBinding call : facts.callsForFunction(function.id())) {
					if(call.callOccurrenceId() < 0 || position >= call.argumentBindingIds().size())
						continue;
					int argument = call.argumentBindingIds().get(position);
					add(formalArguments.get(formal), argument);
					add(bindingConsumers.get(argument), formal);
				}
			}
		}
	}

	private void indexScalarValueDependencies(int dependent, int binding, Set<Integer> visiting) {
		if(binding < 0 || !visiting.add(binding)) return;
		add(bindingConsumers.get(binding),dependent);
		MemoProgramFacts.BindingNode node = facts.binding(binding);
		for(int child : node.children()) indexScalarValueDependencies(dependent,child,visiting);
		if(node.referenceId() >= 0 && node.referenceId() < facts.occurrences().size()) {
			MemoProgramFacts.Occurrence occurrence = facts.occurrence(node.referenceId());
			if(occurrence.hop().getDataType().isScalar())
				for(int position = 0; position < occurrence.hop().getInput().size(); position++)
					indexScalarValueDependencies(dependent,
						facts.inputBinding(occurrence.id(),position),visiting);
		}
	}

	private void drain() {
		while(!ownerWork.isEmpty() || !bindingWork.isEmpty()) {
			while(!bindingWork.isEmpty()) {
				int binding = bindingWork.removeFirst();
				bindingVisits++;
				bindingQueued[binding] = false;
				Shape next = evaluateBinding(binding);
				if(!Objects.equals(bindingShapes[binding], next)) {
					bindingShapes[binding] = next;
					for(int dependent : bindingConsumers.get(binding)) enqueueBinding(dependent);
					for(int owner : bindingOwnerConsumers.get(binding)) enqueueOwner(owner);
				}
			}
			if(!ownerWork.isEmpty()) {
				int owner = ownerWork.removeFirst();
				ownerVisits++;
				ownerQueued[owner] = false;
				Shape next = evaluateOwner(owner);
				if(!Objects.equals(ownerShapes[owner], next)) {
					ownerShapes[owner] = next;
					applyShape(views[owner], next);
					for(int consumer : ownerConsumers.get(owner)) enqueueOwner(consumer);
					for(int binding : ownerBindingConsumers.get(owner)) enqueueBinding(binding);
				}
			}
		}
	}

	private Shape evaluateOwner(int owner) {
		Hop view = views[owner];
		MemoSources.Source source = sources.source(owner);
		if(source != null)
			return exact(view, source.rows(), source.columns(), -1);
		if(view instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			diagnostic(DiagnosticKind.UNRESOLVED, "FEDERATED_SOURCE_SHAPE_MISSING", owner, -1,
				"Federated source has no acquired exact-range shape");
			return unknown(view);
		}
		if(view instanceof DataOp data && data.getOp() == OpOpData.PERSISTENTREAD) {
			Shape metadata = persistentReadShape(data);
			if(metadata != null) return metadata;
		}
		if(isTransientRead(view))
			return transientOwnerShape(owner, view);
		if(view instanceof DataGenOp) {
			Shape dataGen = dataGenOwnerShape(owner);
			if(dataGen != null) return dataGen;
		}
		try {
			if(isMatrixLike(view) && !view.getInput().isEmpty()) {
				view.setDim1(-1);
				view.setDim2(-1);
				view.setNnz(-1);
			}
			view.refreshSizeInformation();
			Shape refreshed = fromView(view);
			if(refreshed.exact()) return refreshed;
			Shape retained = retainedCompilerShape(owner, view);
			return retained != null ? retained : refreshed;
		}
		catch(RuntimeException ex) {
			diagnostic(DiagnosticKind.REFRESH_FAILURE, "SHAPE_REFRESH_FAILURE", owner, -1,
				ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
			return unknown(view);
		}
	}

	private Shape persistentReadShape(DataOp data) {
		String filename = data.getFileName();
		if((filename == null || filename.isEmpty()) && data.hasParameter(DataExpression.IO_FILENAME)) {
			Hop value = data.getInput(data.getParameterIndex(DataExpression.IO_FILENAME));
			if(value instanceof LiteralOp literal) filename = literal.getStringValue();
		}
		if(filename == null || filename.isEmpty()) return null;
		Optional<Shape> cached = localMetadataShapes.get(filename);
		if(cached != null) return cached.orElse(null);
		String metadataFile = DataExpression.getMTDFileName(filename);
		Optional<Shape> result = Optional.empty();
		if(HDFSTool.existsFileOnHDFS(metadataFile)) {
			MetaDataAll metadata = new MetaDataAll(metadataFile,false,true);
			if(metadata.mtdExists() && metadata.getDim1() > 0 && metadata.getDim2() > 0)
				result = Optional.of(new Shape(data.getDataType(),data.getValueType(),
					metadata.getDim1(),metadata.getDim2(),metadata.getNnz(),Knowledge.EXACT));
		}
		localMetadataShapes.put(filename,result);
		return result.orElse(null);
	}

	/**
	 * Retains an exact value-dependent compiler shape only when the source-driven
	 * axis can still be proved against the refreshed target. REXPAND's other axis
	 * comes from a scalar value that ordinary matrix-shape propagation cannot
	 * reconstruct across transient/function bindings.
	 */
	private Shape retainedCompilerShape(int owner, Hop view) {
		Shape initial = initialShapes[owner];
		if(!(view instanceof ParameterizedBuiltinOp parameterized)
			|| parameterized.getOp() != ParamBuiltinOp.REXPAND)
			return null;
		Hop target = parameterized.getTargetHop();
		Hop direction = parameterized.getParameterHop("dir");
		if(target == null || target.getDim1() <= 0 || !(direction instanceof LiteralOp literal))
			return null;
		String value = literal.getStringValue();
		Long maximum = scalarLong(parameterized.getParameterHop("max"), new LinkedHashSet<>());
		if(maximum != null && maximum > 0) {
			long rows = "rows".equals(value) ? maximum : target.getDim1();
			long columns = "cols".equals(value) ? maximum : target.getDim1();
			if(("rows".equals(value) || "cols".equals(value)) && rows > 0 && columns > 0)
				return exact(view, rows, columns, -1);
		}
		if(!initial.exact()) return null;
		if("cols".equals(value) && initial.rows() == target.getDim1()
			|| "rows".equals(value) && initial.columns() == target.getDim1())
			return initial;
		return null;
	}

	private Long scalarLong(Hop hop, Set<Integer> visitingBindings) {
		if(hop instanceof LiteralOp literal) {
			try { return literal.getLongValue(); }
			catch(RuntimeException ignored) { return null; }
		}
		Integer owner = viewOwners.get(hop);
		if(owner == null) return null;
		Long value = null;
		for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
			int binding = facts.valueBinding(occurrence.id());
			if(binding < 0) continue;
			Long current = scalarLong(binding, visitingBindings);
			if(current == null || value != null && !value.equals(current)) return null;
			value = current;
		}
		return value;
	}

	private Long scalarLong(int binding, Set<Integer> visiting) {
		if(binding < 0 || !visiting.add(binding)) return null;
		try {
			MemoProgramFacts.BindingNode node = facts.binding(binding);
			List<Integer> children = node.kind() == BindingKind.FORMAL
				? formalArguments.get(binding) : node.children();
			Long value = null;
			for(int child : children) {
				Long current = scalarLong(child, visiting);
				if(current == null || value != null && !value.equals(current)) return null;
				value = current;
			}
			if(value != null) return value;
			if(node.referenceId() >= 0 && node.referenceId() < facts.occurrences().size()) {
				MemoProgramFacts.Occurrence occurrence = facts.occurrence(node.referenceId());
				Hop referenced = occurrence.hop();
				if(referenced instanceof LiteralOp literal) {
					try { return literal.getLongValue(); }
					catch(RuntimeException ignored) { return null; }
				}
				if(referenced instanceof UnaryOp unary
					&& (unary.getOp() == OpOp1.NROW || unary.getOp() == OpOp1.NCOL)) {
					int input = facts.inputBinding(occurrence.id(),0);
					Shape inputShape = input >= 0 ? bindingShapes[input] : null;
					if(inputShape != null) {
						long dimension = unary.getOp() == OpOp1.NROW
							? inputShape.rows() : inputShape.columns();
						return dimension > 0 ? dimension : null;
					}
				}
			}
			return null;
		}
		finally { visiting.remove(binding); }
	}

	private Long scalarLong(int binding,EvaluationContext context,Set<PhaseVisit> active) {
		if(binding<0) return null;
		PhaseVisit key=new PhaseVisit(false,binding,context.callFrame());
		if(!active.add(key)) return null;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if(node.kind()==BindingKind.SELECT) {
				Boolean selected=context.guards().get(facts.guardDecision(node.referenceId(),context.callFrame()));
				if(selected!=null) return scalarLong(node.children().get(
					facts.branchGuardValue(node.referenceId(),context.callFrame(),selected) ? 0 : 1),context,active);
				Long left=scalarLong(node.children().get(0),context,active);
				Long right=scalarLong(node.children().get(1),context,active);
				return Objects.equals(left,right) ? left : null;
			}
			if(node.kind()==BindingKind.FORMAL) {
				for(int index=context.callFrame().size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size())
						return scalarLong(call.actualBindingIds().get(node.position()),
							context.withCallFrame(context.callFrame().subList(0,index)),active);
				}
				return scalarLong(binding,new LinkedHashSet<>());
			}
			if(node.kind()==BindingKind.CALL_RETURN) {
				if(node.children().size()>1) return scalarLong(node.children().get(1),context,active);
				if(node.children().isEmpty()) return null;
				List<Integer> frame=new ArrayList<>(context.callFrame()); frame.add(node.referenceId());
				return scalarLong(node.children().get(0),context.withCallFrame(frame),active);
			}
			if(node.kind()==BindingKind.DEFINITION && !node.children().isEmpty()
				|| node.kind()==BindingKind.CALL_ARG)
				return scalarLong(node.children().get(0),context,active);
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0) {
				Hop hop=facts.occurrence(node.referenceId()).hop();
				if(hop instanceof LiteralOp literal && hop.getValueType()==ValueType.INT64)
					return literal.getLongValue();
				if(hop instanceof UnaryOp unary && (unary.getOp()==OpOp1.NROW || unary.getOp()==OpOp1.NCOL)) {
					Shape input=bindingShape(facts.inputBinding(node.referenceId(),0),context);
					long value=unary.getOp()==OpOp1.NROW ? input.rows() : input.columns();
					return value>0 ? value : null;
				}
			}
			return scalarLong(binding,new LinkedHashSet<>());
		}
		finally { active.remove(key); }
	}

	private Double scalarDouble(int binding,EvaluationContext context,Set<PhaseVisit> active) {
		if(binding<0) return null;
		PhaseVisit key=new PhaseVisit(false,binding,context.callFrame());
		if(!active.add(key)) return null;
		try {
			MemoProgramFacts.BindingNode node=facts.binding(binding);
			if(node.kind()==BindingKind.SELECT) {
				Boolean selected=context.guards().get(facts.guardDecision(node.referenceId(),context.callFrame()));
				if(selected!=null) return scalarDouble(node.children().get(
					facts.branchGuardValue(node.referenceId(),context.callFrame(),selected) ? 0 : 1),context,active);
				Double left=scalarDouble(node.children().get(0),context,active);
				Double right=scalarDouble(node.children().get(1),context,active);
				return Objects.equals(left,right) ? left : null;
			}
			if(node.kind()==BindingKind.FORMAL) {
				for(int index=context.callFrame().size()-1;index>=0;index--) {
					MemoProgramFacts.CallBinding call=facts.call(context.callFrame().get(index));
					if(call.functionSummaryId()==node.referenceId() && node.position()>=0
						&& node.position()<call.actualBindingIds().size())
						return scalarDouble(call.actualBindingIds().get(node.position()),
							context.withCallFrame(context.callFrame().subList(0,index)),active);
				}
				return null;
			}
			if(node.kind()==BindingKind.CALL_RETURN) {
				if(node.children().size()>1) return scalarDouble(node.children().get(1),context,active);
				if(node.children().isEmpty()) return null;
				List<Integer> frame=new ArrayList<>(context.callFrame()); frame.add(node.referenceId());
				return scalarDouble(node.children().get(0),context.withCallFrame(frame),active);
			}
			if(node.kind()==BindingKind.DEFINITION && !node.children().isEmpty()
				|| node.kind()==BindingKind.CALL_ARG)
				return scalarDouble(node.children().get(0),context,active);
			if(node.kind()==BindingKind.DEFINITION && node.referenceId()>=0) {
				Hop hop=facts.occurrence(node.referenceId()).hop();
				if(hop instanceof LiteralOp literal) return literal.getDoubleValue();
				if(hop instanceof UnaryOp unary && (unary.getOp()==OpOp1.NROW || unary.getOp()==OpOp1.NCOL)) {
					Shape input=bindingShape(facts.inputBinding(node.referenceId(),0),context);
					long value=unary.getOp()==OpOp1.NROW ? input.rows() : input.columns();
					return value>0 ? (double)value : null;
				}
			}
			return null;
		}
		finally { active.remove(key); }
	}

	private Shape transientOwnerShape(int owner, Hop view) {
		Shape result = null;
		for(MemoProgramFacts.Occurrence occurrence : occurrencesByOwner.get(owner)) {
			int binding = facts.valueBinding(occurrence.id());
			if(binding < 0)
				return unknown(view);
			Shape current = bindingShapes[binding];
			if(current == null)
				return null;
			if(result == null)
				result = current;
			else if(result.exact() && current.exact() && !sameDimensions(result, current)) {
				diagnostic(DiagnosticKind.MISMATCH, "SHARED_OCCURRENCE_SHAPE_MISMATCH", owner, binding,
					result + " vs " + current);
				result = commonShape(result, current);
			}
			else if(sameDimensions(result, current))
				result = mergeNonZeros(result, current);
			else
				result = commonShape(result, current);
		}
		return result == null ? unknown(view) : result;
	}

	private Shape evaluateBinding(int id) {
		// Mismatch is an absorbing result. Re-evaluating a cyclic binding after its
		// consumers lose shape information must not resurrect one incompatible arm
		// and oscillate the worklist indefinitely.
		if(bindingConflicts[id] && bindingShapes[id] != null)
			return bindingShapes[id];
		MemoProgramFacts.BindingNode node = facts.binding(id);
		return switch(node.kind()) {
			case MISSING, UNBOUND, CALL_COMPLETION -> unknown((Hop)null);
			case DEFINITION -> node.children().isEmpty() ? definitionOwnerShape(node) : child(node, 0);
			case SELECT -> unify(node, node.children(), "BRANCH_SHAPE_MISMATCH");
			case LOOP_HEAD -> loopHead(node);
			case LAST, CALL_ARG -> child(node, 0);
			case FORMAL -> unify(node, formalArguments.get(id), "FUNCTION_ACTUAL_SHAPE_MISMATCH");
			case CALL_RETURN -> callReturn(node);
		};
	}

	private Shape definitionOwnerShape(MemoProgramFacts.BindingNode node) {
		if(node.referenceId() < 0 || node.referenceId() >= facts.occurrences().size())
			return unknown((Hop)null);
		Shape slot=multiReturnOutputShape(node.referenceId(),node.position());
		if(slot!=null) return slot;
		return occurrenceOutputShape(facts.occurrence(node.referenceId()));
	}

	private Shape loopHead(MemoProgramFacts.BindingNode node) {
		Shape entry = child(node, 0);
		Shape backedge = child(node, 1);
		if(entry == null || !entry.exact())
			return entry;
		if(backedge == null)
			return entry; // grounded induction seed; backedge must later prove preservation
		if(!backedge.exact())
			return unknown(entry);
		if(sameDimensions(entry, backedge))
			return mergeNonZeros(entry, backedge);
		diagnostic(DiagnosticKind.MISMATCH, "LOOP_CARRIED_SHAPE_MISMATCH", -1, node.id(),
			entry + " vs " + backedge);
		return unknown(entry);
	}

	private Shape callReturn(MemoProgramFacts.BindingNode node) {
		MemoProgramFacts.CallBinding call = node.referenceId() >= 0
			&& node.referenceId() < facts.calls().size() ? facts.call(node.referenceId()) : null;
		if(call != null && call.callOccurrenceId() < 0 && node.children().size() > 1)
			return child(node, node.children().size() - 1); // concrete inlined clone
		return unify(node, node.children(), "FUNCTION_RETURN_SHAPE_MISMATCH");
	}

	private Shape unify(MemoProgramFacts.BindingNode node, List<Integer> children, String code) {
		if(children.isEmpty()) return unknown((Hop)null);
		Shape result = null;
		for(int child : children) {
			Shape value = child >= 0 ? bindingShapes[child] : null;
			if(value == null) return null;
			if(result == null) result = value;
			else if(result.exact() && value.exact() && !sameDimensions(result, value)) {
				diagnostic(DiagnosticKind.MISMATCH, code, -1, node.id(), result + " vs " + value);
				result = commonShape(result, value);
			}
			else if(sameDimensions(result, value)) result = mergeNonZeros(result, value);
			else result = commonShape(result, value);
		}
		return result;
	}

	private Shape child(MemoProgramFacts.BindingNode node, int position) {
		return position >= 0 && position < node.children().size()
			? bindingShapes[node.children().get(position)] : unknown((Hop)null);
	}

	private void finalizeUnresolved() {
		for(int binding = 0; binding < bindingShapes.length; binding++)
			if(bindingShapes[binding] == null) {
				bindingShapes[binding] = unknown((Hop)null);
				diagnostic(DiagnosticKind.UNRESOLVED, "UNRESOLVED_BINDING_SHAPE", -1, binding,
					facts.binding(binding).label());
				for(int dependent : bindingConsumers.get(binding)) enqueueBinding(dependent);
				for(int owner : bindingOwnerConsumers.get(binding)) enqueueOwner(owner);
			}
		for(int owner = 0; owner < ownerShapes.length; owner++)
			if(ownerShapes[owner] == null) {
				ownerShapes[owner] = unknown(views[owner]);
				applyShape(views[owner], ownerShapes[owner]);
				diagnostic(DiagnosticKind.UNRESOLVED, "UNRESOLVED_OWNER_SHAPE", owner, -1,
					views[owner].getName());
				for(int consumer : ownerConsumers.get(owner)) enqueueOwner(consumer);
				for(int binding : ownerBindingConsumers.get(owner)) enqueueBinding(binding);
			}
	}

	private Shape inputShape(Hop hop, int position) {
		if(position < 0 || position >= hop.getInput().size()) return null;
		Hop input = hop.getInput(position);
		return fromView(input);
	}

	private void enqueueOwner(int owner) {
		if(owner >= 0 && owner < ownerQueued.length && !ownerQueued[owner]) {
			ownerQueued[owner] = true;
			ownerWork.addLast(owner);
		}
	}

	private void enqueueBinding(int binding) {
		if(binding >= 0 && binding < bindingQueued.length && !bindingQueued[binding]) {
			bindingQueued[binding] = true;
			bindingWork.addLast(binding);
		}
	}

	private void diagnostic(DiagnosticKind kind, String code, int owner, int binding, String message) {
		if(kind == DiagnosticKind.MISMATCH && binding >= 0 && binding < bindingConflicts.length)
			bindingConflicts[binding] = true;
		String key = kind + "|" + code + '|' + owner + '|' + binding;
		if(diagnosticKeys.add(key)) diagnostics.add(new Diagnostic(kind, code, owner, binding, message));
	}

	// All later occurrence views clone this private snapshot, never live compiler
	// HOPs whose memory/placement annotations change during lowering/recompilation.
	private static Hop[] cloneViews(MemoProgramFacts facts) {
		List<Hop> roots = new ArrayList<>(facts.compiledOwnerCount());
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++)
			roots.add(facts.compiledOwnerHop(owner));
		Map<Long,Hop> clones = new HashMap<>();
		Recompiler.deepCopyHopsDag(roots, clones);
		// A shape/native-rule view describes operator semantics, not a choice made by
		// an earlier planner invocation. Deep copies intentionally retain physical
		// annotations for recompilation, so remove them from these private views.
		for(Hop clone : clones.values()) clearPlannerState(clone);
		Hop[] result = new Hop[roots.size()];
		for(int owner = 0; owner < roots.size(); owner++) {
			result[owner] = clones.get(roots.get(owner).getHopID());
			if(result[owner] == null)
				throw new IllegalStateException("No private shape view for owner " + owner);
		}
		return result;
	}

	private static Hop cloneView(Hop root) {
		return cloneView(root,COST_VIEW_DESCENT_DEPTH,new IdentityHashMap<>(),new IdentityHashMap<>());
	}

	private static Hop cloneView(Hop root,int depth,Map<Hop,Hop> clones,Map<Hop,Integer> depths) {
		Hop result=clones.get(root);
		if(result!=null && depths.get(root)>=depth) return result;
		try {
			if(result==null) {
				result=(Hop)root.clone();
				clearPlannerState(result);
				clones.put(root,result);
			}
			depths.put(root,depth);
			// A shared node first reached by a longer path must be expanded when
			// it is subsequently reached closer to the root. Identity-only DFS
			// caching could otherwise leave a direct input with no operands.
			if(depth>0) {
				boolean attach=result.getInput().isEmpty();
				for(Hop input:root.getInput()) {
					Hop child=cloneView(input,depth-1,clones,depths);
					if(attach) result.addInput(child);
				}
			}
			if(result instanceof FunctionOp function && root instanceof FunctionOp source
				&& source.getOutputs()!=null) {
				ArrayList<Hop> outputs=new ArrayList<>(source.getOutputs().size());
				for(Hop output:source.getOutputs())
					outputs.add(output==null ? null : cloneView(output,Math.max(0,depth-1),clones,depths));
				function.setOutputs(outputs);
			}
			return result;
		}
		catch(CloneNotSupportedException exception) {
			throw new IllegalStateException("No private occurrence view for "+root.getHopID(),exception);
		}
	}

	private static void clearPlannerState(Hop clone) {
		clone.setForcedExecType(null);
		clone.resetExecType();
		clone.setFederatedOutput(FederatedOutput.NONE);
		clone.setFederatedOutputDerived(false);
		clone.deactivatePrefetch();
		clone.setPlannerPlacementSelected(false);
		clone.setLops(null);
	}

	private static Shape fromView(Hop hop) {
		if(hop == null) return unknown((Hop)null);
		boolean matrix = isMatrixLike(hop);
		boolean exact = !matrix || hop.getDim1() > 0 && hop.getDim2() > 0;
		return new Shape(hop.getDataType(), hop.getValueType(), matrix ? hop.getDim1() : -1,
			matrix ? hop.getDim2() : -1,
			hop.getNnz(), exact ? Knowledge.EXACT : Knowledge.UNKNOWN);
	}

	private static Shape exact(Hop hop, long rows, long columns, long nonZeros) {
		return new Shape(hop.getDataType(), hop.getValueType(), rows, columns, nonZeros, Knowledge.EXACT);
	}

	private static Shape unknown(Hop hop) {
		return hop == null
			? new Shape(DataType.UNKNOWN, ValueType.UNKNOWN, -1, -1, -1, Knowledge.UNKNOWN)
			: new Shape(hop.getDataType(), hop.getValueType(), -1, -1, -1, Knowledge.UNKNOWN);
	}

	private static Shape unknown(Shape basis) {
		return basis == null ? unknown((Hop)null)
			: new Shape(basis.dataType(), basis.valueType(), -1, -1, -1, Knowledge.UNKNOWN);
	}

	private static boolean sameDimensions(Shape left, Shape right) {
		if(left == null || right == null || !left.exact() || !right.exact()
			|| left.dataType() != right.dataType() || left.valueType() != right.valueType())
			return false;
		return left.dataType() != DataType.MATRIX && left.dataType() != DataType.FRAME
			|| left.rows() == right.rows() && left.columns() == right.columns();
	}

	private static Shape mergeNonZeros(Shape left, Shape right) {
		return new Shape(left.dataType(), left.valueType(), left.rows(), left.columns(),
			left.nonZeros() == right.nonZeros() ? left.nonZeros() : -1, Knowledge.EXACT);
	}

	/** Returns only dimensions independently proved equal on every incoming path. */
	private static Shape commonShape(Shape left, Shape right) {
		if(left == null || right == null || left.dataType() != right.dataType()
			|| left.valueType() != right.valueType())
			return unknown((Hop)null);
		long rows = left.rows() > 0 && left.rows() == right.rows() ? left.rows() : -1;
		long columns = left.columns() > 0 && left.columns() == right.columns() ? left.columns() : -1;
		long nonZeros = left.nonZeros() >= 0 && left.nonZeros() == right.nonZeros()
			? left.nonZeros() : -1;
		boolean exact = left.dataType() != DataType.UNKNOWN && left.valueType() != ValueType.UNKNOWN
			&& (left.dataType() != DataType.MATRIX && left.dataType() != DataType.FRAME
				|| rows > 0 && columns > 0);
		return new Shape(left.dataType(), left.valueType(), rows, columns, nonZeros,
			exact ? Knowledge.EXACT : Knowledge.UNKNOWN);
	}

	private static void applyShape(Hop hop, Shape shape) {
		if(hop == null || shape == null || !isMatrixLike(hop)) return;
		hop.setDim1(shape.rows());
		hop.setDim2(shape.columns());
		hop.setNnz(shape.nonZeros());
		// A cloned Hop may retain a different occurrence's cached memory estimate.
		// Cost reads those caches as well as NNZ; refresh from these frozen stats.
		if(shape.rows()>0 && shape.columns()>0)
			hop.computeMemEstimate(new org.apache.sysds.hops.MemoTable());
	}

	private static boolean isMatrixLike(Hop hop) {
		return hop.getDataType() == DataType.MATRIX || hop.getDataType() == DataType.FRAME;
	}

	private static boolean isTransientRead(Hop hop) {
		return hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD;
	}

	private static long dimension(Shape shape, boolean rows) {
		return shape != null ? rows ? shape.rows() : shape.columns() : -1;
	}

	private static <T> List<List<T>> lists(int size) {
		List<List<T>> result = new ArrayList<>(size);
		for(int i = 0; i < size; i++) result.add(new ArrayList<>());
		return result;
	}

	private static void add(List<Integer> values, int value) {
		values.add(value);
	}
}
