/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.EvaluationContext;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.GuardDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.LoopCardinalityDecision;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.IterationPhase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopCardinality;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.LoopExecutionCase;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.PhaseLoop;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.OriginKind;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedEnvironment;
import org.apache.sysds.hops.fedplanner.memo.MemoBindingResolver.ResolvedOrigin;
import org.apache.sysds.hops.fedplanner.memo.MemoNativeRules.InputRole;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Check;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.CostTerm;
import org.apache.sysds.hops.fedplanner.memo.MemoOptimizer.Truth;
import org.apache.sysds.hops.fedplanner.memo.MemoPrivacy.Context;
import org.apache.sysds.hops.fedplanner.memo.MemoPrivacy.LoopSlot;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.Occurrence;
import org.apache.sysds.hops.fedplanner.memo.MemoProgramFacts.RegionKind;
import org.apache.sysds.lops.compile.MemoSupplyRegistry;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.controlprogram.federated.MemoLoopRuntime;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyStore.Action;

/**
 * Native owners and exact reaching uses, with small loop-boundary domains.
 * Conditions subscribe only to their actual binding/owner dependencies.
 */
public final class MemoProgramModel {
	/**
	 * Frozen cost-estimation profile. An unproved {@link GuardDecision} has one symbolic outcome per
	 * call frame, shared across repeated loop evaluations; different guard ids are independent.
	 * Phase proofs override that estimate. This is not a reachability fact and never prunes a plan.
	 */
	public record Profile(double unknownLoopIterations,double branchThenProbability) {
		public Profile {
			if(!Double.isFinite(unknownLoopIterations) || unknownLoopIterations <= 1
				|| !Double.isFinite(branchThenProbability) || branchThenProbability <= 0 || branchThenProbability >= 1)
				throw new IllegalArgumentException("Profile needs T>1 and 0<p<1; it is not a reachability oracle");
		}
		public static Profile defaults() { return new Profile(10,0.5); }
	}
	public record Head(int variable,LoopSlot slot,MemoProgramFacts.CarriedValue value) { }
	public record CostBreakdown(BigDecimal nativeExecution,BigDecimal movement) {
		public BigDecimal total() { return nativeExecution.add(movement); }
	}
	/** Aggregate counters; primitiveValueBytes counts double payloads only, excluding object/view overhead. */
	public record CostProjectionStatistics(long bindingDagVisits,long projectionCacheHits,
		long relevantGuardDecisions,long supportPrefixChecks,long noRegionsSkipped,long unknownObligations,
		long invariantProjectionStops,long activationEnvironments,
		long projectedContexts,long primitiveVectors,long primitiveValues,long primitiveValueBytes) { }
	public record Solution(MemoOptimizer.Result optimization,
		Map<Long,List<MemoSupplyRegistry.Supply>> supplies,List<String> diagnostics) {
		public Solution { supplies = Map.copyOf(supplies); diagnostics = List.copyOf(diagnostics); }
	}
	private record Use(Occurrence consumer,List<Integer> bindings,List<List<Integer>> frames) { }
	private record PrivacyDemand(int occurrence,List<Integer> localInputs,boolean localOutput) { }
	private final Map<PrivacyDemand,Truth> privacyLegality=new LinkedHashMap<>();
	private record MovementEdge(int use,int position) { }
	private record MovementProjectionKey(MovementEdge edge,List<Integer> frame) {
		private MovementProjectionKey { frame=List.copyOf(frame); }
	}
	private static final class ActivationRow {
		private final ResolvedOrigin origin;
		private final MemoBindingResolver.OriginIdentity identity;
		private final ResolvedEnvironment environment;
		private List<MovementPrimitive> costRows;
		private ActivationRow(ResolvedOrigin origin,MemoBindingResolver.OriginIdentity identity,
			ResolvedEnvironment environment) {
			this.origin=origin; this.identity=identity; this.environment=environment;
		}
		private ResolvedOrigin origin() { return origin; }
		private MemoBindingResolver.OriginIdentity identity() { return identity; }
		private ResolvedEnvironment environment() { return environment; }
	}
	private record MovementInputProjection(List<ActivationRow> rows,MemoBindingResolver.VisitStatus status) {
		private MovementInputProjection { rows=List.copyOf(rows); }
	}
	private record UnitKey(MemoPartition from,MemoPartition target) { }
	private static final class MovementPrimitive {
		private final ResolvedEnvironment environment;
		private final double weight;
		private final Map<GuardDecision,Boolean> guards;
		private final Map<LoopDecision,IterationPhase> phases;
		private final Map<UnitKey,Long> units=new LinkedHashMap<>();
		private MemoCostModel.MovementMeasurement uploadMeasurement;
		private MemoCostModel.MovementMeasurement downloadMeasurement;
		private final Activation activation;
		private MovementPrimitive(ResolvedEnvironment environment,double weight,
			Map<GuardDecision,Boolean> guards,Map<LoopDecision,IterationPhase> phases) {
			this.environment=environment; this.weight=weight;
			this.guards=Map.copyOf(guards); this.phases=Map.copyOf(phases);
			activation=new Activation(this.guards,this.phases,weight);
		}
		private void copyMeasurementsFrom(MovementPrimitive source) {
			uploadMeasurement=source.uploadMeasurement;
			downloadMeasurement=source.downloadMeasurement;
		}
	}
	// Identity of immutable events also preserves their guard-map iteration and input order:
	// unordered semantic equality is insufficient for an exact binary64 computation cache.
	private static final class ActivationListKey {
		private final Activation[] events;
		private final int hash;
		private ActivationListKey(List<Activation> values) {
			events=values.toArray(Activation[]::new);
			int value=1;
			for(Activation event:events) value=31*value+System.identityHashCode(event);
			hash=value;
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			if(this==other) return true;
			if(!(other instanceof ActivationListKey that) || events.length!=that.events.length) return false;
			for(int i=0;i<events.length;i++) if(events[i]!=that.events[i]) return false;
			return true;
		}
	}
	public record MovementReuseStatistics(long activationRows,long activationReplays,long postGateRequests,
		long postGateBuilds,long postGateHits,long primitiveContexts,long contextualViewBuilds,
		long unionRequests,long unionCacheHits,long measurementRequests,long measurementHits) { }
	private record MovementGroup(MemoBindingResolver.OriginIdentity identity,MovementEdge isolated) { }
	private record RetentionScope(MemoPlanRegistry.ScopeKind kind,long statementBlockId) { }
	private record SupplyEdge(long statementBlockId,long producerHopId,long consumerHopId,int inputPosition) { }
	private record ScheduledDemand(RetentionScope scope,MemoBindingResolver.OriginIdentity identity,
		SupplyEdge edge,MemoSupplyRegistry.Recipe recipe,MemoPartition target) { }
	private static final int MOVEMENT_FACTOR_WIDTH_DIAGNOSTIC = 8;
	private static final int MOVEMENT_GUARD_STATE_LIMIT = 100_000;
	private static final int FAIR_GUARD_LIMIT = 16;
	private static final int MOVEMENT_ACTIVATION_CACHE_LIMIT = 100_000;
	private static final int MOVEMENT_PRIMITIVE_CACHE_LIMIT = 100_000;
	private record EntryUse(LoopSlot head,List<Integer> frame) {
		private EntryUse { frame=List.copyOf(frame); }
	}
	private record LoopBoundary(boolean entry,IterationPhase phase) { }
	private record ContextAnalysisKey(int region,List<Integer> frame) {
		private ContextAnalysisKey { frame=List.copyOf(frame); }
	}
	private record ContextAnalysis(Map<GuardDecision,Boolean> guards,boolean reachable) {
		private ContextAnalysis { guards=Map.copyOf(guards); }
	}
	record ContextAnalysisReuseStatistics(long requests,long hits,int entries) { }
	private record BoundaryRow(int binding,boolean entry,ResolvedOrigin origin,Context context,
		MemoShapes.Shape shape,boolean entryTypeTransition) { }
	private final Map<EntryUse,Boolean> entryUses=new LinkedHashMap<>();
	private final MemoProgramFacts facts;
	private final MemoSources sources;
	private final MemoRuleBuilder.Result universe;
	private final RuleMemo memo;
	private final MemoShapes shapes;
	private final MemoBindingResolver resolver;
	private final MemoReachableContexts reachableContexts;
	private final MemoPrivacy privacy;
	private final Profile profile;
	private final List<Head> heads = new ArrayList<>();
	private final Head[][] headsByLoop;
	private final List<Check> checks = new ArrayList<>();
	private final List<CostTerm> costs = new ArrayList<>();
	private final int nativeCostTermCount;
	private record NativeVectorKey(int occurrence,MemoShapes.Shape output,List<MemoShapes.Shape> inputs,
		int blocksize,long frequencyBits) { }
	private long nativeVectorRequests,nativeVectorHits,nativeVectorBuilds;
	private final int nativeVectorCacheEntries;
	private final Map<NativeVectorKey,double[]> nativeVectorCache=new LinkedHashMap<>(64,0.75f,true) {
		@Override protected boolean removeEldestEntry(Map.Entry<NativeVectorKey,double[]> eldest) { return size()>nativeVectorCacheEntries; }
	};
	private record MovementRecipeKey(MemoShapes.MovementViewRecipe recipe,int position,
		boolean upload,int workers) { }
	private record PreparedMovementPrice(MemoCostModel.MovementMeasurement measurement,double unit) { }
	private final int movementRecipeCacheEntries;
	private final Map<MovementRecipeKey,MemoCostModel.MovementMeasurement> movementRecipeCache=
		new LinkedHashMap<>(64,0.75f,true) {
			@Override protected boolean removeEldestEntry(
				Map.Entry<MovementRecipeKey,MemoCostModel.MovementMeasurement> eldest) {
				return size()>movementRecipeCacheEntries;
			}
		};
	private long movementRecipeRequests,movementRecipeHits;
	record MovementRecipeStatistics(long requests,long hits,int entries) { }
	MovementRecipeStatistics movementRecipeStatistics() {
		return new MovementRecipeStatistics(movementRecipeRequests,movementRecipeHits,
			movementRecipeCache.size());
	}

	private final List<Use> uses = new ArrayList<>();
	private final List<String> diagnostics = new ArrayList<>();
	private final Map<Integer,List<Occurrence>> occurrencesByOwner = new LinkedHashMap<>();
	private final boolean movementReuse;
	private final boolean contextAnalysisReuse;
	private final Map<ContextAnalysisKey,ContextAnalysis> contextAnalysisCache=new LinkedHashMap<>();
	private long contextAnalysisRequests,contextAnalysisHits;
	private final Map<MovementProjectionKey,MovementInputProjection> movementProjections=new LinkedHashMap<>();
	private long movementActivationRows,movementActivationReplays,movementPostGateRequests;
	private long movementPostGateBuilds,movementPostGateHits,movementPrimitiveContexts,movementContextualViewBuilds;
	private int cachedMovementActivationRows,cachedMovementPrimitives;
	private long movementUnionRequests,movementUnionCacheHits;
	private long movementMeasurementRequests,movementMeasurementHits;
	private final Map<ActivationListKey,Double> movementUnionCache=new LinkedHashMap<>(64,0.75f,true) {
		@Override protected boolean removeEldestEntry(Map.Entry<ActivationListKey,Double> eldest) {
			return size()>4096;
		}
	};
	private static final class SelectionScratch {
		private final int[] values;
		private final AtomicBoolean leased=new AtomicBoolean();
		private SelectionScratch(int width) {
			values=new int[width];
			Arrays.fill(values,-1);
		}
		private int[] acquire(int[] scope,int[] choices) {
			if(!leased.compareAndSet(false,true)) return expanded(values.length,scope,choices);
			int populated=0;
			try {
				for(;populated<scope.length;populated++) values[scope[populated]]=choices[populated];
				return values;
			}
			catch(RuntimeException|Error failure) {
				for(int i=0;i<populated;i++) values[scope[i]]=-1;
				leased.set(false);
				throw failure;
			}
		}
		private void release(int[] scope,int[] selection) {
			if(selection!=values) return;
			try { for(int owner:scope) values[owner]=-1; }
			finally { leased.set(false); }
		}
		private static int[] expanded(int width,int[] scope,int[] choices) {
			int[] result=new int[width]; Arrays.fill(result,-1);
			for(int i=0;i<scope.length;i++) result[scope[i]]=choices[i];
			return result;
		}
	}
	private final SelectionScratch selectionScratch;
	private final int[] sizes;
	// Read-only selection for definition weights; never lend this array to mutable scratch paths.
	private final int[] unselectedDefinitionRules;
	private final boolean[] coverage;
	private final double[][] nativeCosts;
	private final Map<Integer,MutableCostProjectionStatistics> costProjectionStatistics=new LinkedHashMap<>();
	private static final class MutableCostProjectionStatistics {
		private long bindingDagVisits;
		private long projectionCacheHits;
		private long relevantGuardDecisions;
		private long supportPrefixChecks;
		private long noRegionsSkipped;
		private long unknownObligations;
		private long invariantProjectionStops;
		private long activationEnvironments;
		private long projectedContexts;
		private long primitiveVectors;
		private long primitiveValues;
		private long primitiveValueBytes;
		private CostProjectionStatistics snapshot() {
			return new CostProjectionStatistics(bindingDagVisits,projectionCacheHits,relevantGuardDecisions,
				supportPrefixChecks,noRegionsSkipped,unknownObligations,invariantProjectionStops,
				activationEnvironments,projectedContexts,primitiveVectors,
				primitiveValues,primitiveValueBytes);
		}
	}

	public MemoProgramModel(MemoProgramFacts facts,MemoSources sources,
		MemoRuleBuilder.Result universe,Profile profile) {
		this(facts,sources,universe,profile,true);
	}
	MemoProgramModel(MemoProgramFacts facts,MemoSources sources,
		MemoRuleBuilder.Result universe,Profile profile,boolean movementReuse) {
		this(facts,sources,universe,profile,movementReuse,4096);
	}
	MemoProgramModel(MemoProgramFacts facts,MemoSources sources,
		MemoRuleBuilder.Result universe,Profile profile,boolean movementReuse,int nativeVectorCacheEntries) {
		this(facts,sources,universe,profile,movementReuse,nativeVectorCacheEntries,true);
	}
	MemoProgramModel(MemoProgramFacts facts,MemoSources sources,
		MemoRuleBuilder.Result universe,Profile profile,boolean movementReuse,int nativeVectorCacheEntries,
		boolean contextAnalysisReuse) {
		this(facts,sources,universe,profile,movementReuse,nativeVectorCacheEntries,
			contextAnalysisReuse,4096);
	}
	MemoProgramModel(MemoProgramFacts facts,MemoSources sources,
		MemoRuleBuilder.Result universe,Profile profile,boolean movementReuse,int nativeVectorCacheEntries,
		boolean contextAnalysisReuse,int movementRecipeCacheEntries) {
		if(nativeVectorCacheEntries<0) throw new IllegalArgumentException("native cache size must be nonnegative");
		if(movementRecipeCacheEntries<0)
			throw new IllegalArgumentException("movement recipe cache size must be nonnegative");
		this.nativeVectorCacheEntries=nativeVectorCacheEntries;
		this.movementRecipeCacheEntries=movementRecipeCacheEntries;
		this.facts = facts; this.sources = sources; this.universe = universe; this.memo = universe.memo();
		this.shapes = universe.shapes();
		this.profile = profile; this.movementReuse=movementReuse; this.contextAnalysisReuse=contextAnalysisReuse;
		privacy = universe.privacy(); resolver = privacy.resolverFor(facts,sources);
		reachableContexts = new MemoReachableContexts(facts,resolver);
		shapes.requireRequest(facts,sources);
		facts.requireComplete(); diagnostics.addAll(universe.diagnostics());
		for(Occurrence occurrence : facts.occurrences()) {
			occurrencesByOwner.computeIfAbsent(occurrence.compiledOwnerId(),ignored -> new ArrayList<>()).add(occurrence);
		}
		headsByLoop=new Head[facts.loops().size()][];
		for(var loop : facts.loops()) {
			if(framesFor(loop.regionId()).isEmpty()) continue;
			if(loop.executionCases().stream().allMatch(value -> value.cardinality()==LoopCardinality.ZERO)) continue;
			int slots=0;
			for(var value:loop.carriedValues()) slots=Math.max(slots,value.slot()+1);
			headsByLoop[loop.id()]=new Head[slots];
			for(var value : loop.carriedValues()) {
				Head head = new Head(facts.compiledOwnerCount()+heads.size(),new LoopSlot(loop.id(),value.slot()),value);
				heads.add(head); headsByLoop[loop.id()][value.slot()]=head;
			}
		}
		sizes = new int[facts.compiledOwnerCount()+heads.size()]; coverage = new boolean[sizes.length];
		unselectedDefinitionRules=new int[sizes.length]; Arrays.fill(unselectedDefinitionRules,-1);
		selectionScratch=new SelectionScratch(sizes.length);
		nativeCosts = new double[sizes.length][];
		for(int owner = 0; owner < facts.compiledOwnerCount(); owner++) {
			List<Occurrence> occurrences = occurrencesByOwner.get(owner);
			Hop hop = facts.compiledOwnerHop(owner);
			List<List<Integer>> ownerContexts = new ArrayList<>();
			for(Occurrence occurrence : occurrences) {
				List<List<Integer>> contexts = framesForOccurrence(occurrence);
				ownerContexts.addAll(contexts);
			}
			boolean nativeOwner = !MemoRuleBuilder.transparent(hop) && !ownerContexts.isEmpty();
			sizes[owner] = nativeOwner ? memo.rules(owner).size() : 1;
			coverage[owner] = !nativeOwner || memo.complete(owner);
			nativeCosts[owner] = new double[sizes[owner]];
			if(!nativeOwner) continue;
			boolean firstNativeTerm = true;
			for(Occurrence occurrence : occurrences) {
				List<List<Integer>> contexts = framesForOccurrence(occurrence);
				if(contexts.isEmpty()) continue;
				for(List<Integer> frame : contexts) {
					for(double[] values : nativeCostTerms(occurrence,frame)) {
						// Each reachable phase/context is one frozen binary64 primitive.
						if(firstNativeTerm) { nativeCosts[owner] = values; firstNativeTerm = false; }
						else costs.add(new CostTerm(new int[] {owner},choices -> values[choices[0]],0));
					}
				}
				List<Integer> bindings = new ArrayList<>();
				for(int i = 0; i < hop.getInput().size(); i++) bindings.add(facts.inputBinding(occurrence.id(),i));
				Use use = new Use(occurrence,List.copyOf(bindings),contexts); uses.add(use);
				// Native pattern/shape is already frozen. Privacy transfer observes semantic
				// values, not producer placement choices, so it needs only this rule choice.
				checks.add(new Check(occurrence.id(),new int[] {owner},choices ->
					privacyCompatible(use,choices[0])));
			}
		}
		for(Head head : heads) {
			// Scalar iterator/control contracts never introduce a FED scalar state.
			Set<Integer> origins = new LinkedHashSet<>();
			for(List<Integer> frame : framesFor(facts.loop(head.slot().loopId()).regionId()))
				origins.addAll(resolver.directSupplyOwners(head.value().entryBindingId(),frame));
			boolean cacheable = origins.stream().anyMatch(owner -> facts.compiledOwnerHop(owner).getDataType().isMatrix()
				|| facts.compiledOwnerHop(owner).getDataType().isFrame());
			// A nested loop may enter directly from an already-bound outer head,
			// which intentionally has no compiled immediate owner.
			DataType entryType=shapes.bindingShape(head.value().entryBindingId()).dataType();
			cacheable |= entryType.isMatrix() || entryType.isFrame();
			sizes[head.variable()] = cacheable && sources.workers() > 0 ? sources.workers() == 1 ? 2 : 4 : 1;
			coverage[head.variable()] = true; nativeCosts[head.variable()] = new double[sizes[head.variable()]];
		}
		registerBoundaryChecks();
		registerInputChecks();
		nativeCostTermCount=costs.size();
		registerMovementCosts();
	}
	public RuleMemo memo() { return memo; }
	public List<Head> heads() { return List.copyOf(heads); }
	public List<Check> checks() { return List.copyOf(checks); }
	MemoBindingResolver.DirectRootStatistics directRootStatistics() { return resolver.directRootStatistics(); }
	ContextAnalysisReuseStatistics contextAnalysisReuseStatistics() {
		return new ContextAnalysisReuseStatistics(contextAnalysisRequests,contextAnalysisHits,
			contextAnalysisCache.size());
	}
	record NativeCostReuseStatistics(long requests,long cacheHits,long vectorBuilds,int entries) { }
	NativeCostReuseStatistics nativeCostReuseStatistics() {
		return new NativeCostReuseStatistics(nativeVectorRequests,nativeVectorHits,nativeVectorBuilds,nativeVectorCache.size());
	}
	public MovementReuseStatistics movementReuseStatistics() {
		return new MovementReuseStatistics(movementActivationRows,movementActivationReplays,movementPostGateRequests,
			movementPostGateBuilds,movementPostGateHits,movementPrimitiveContexts,movementContextualViewBuilds,
			movementUnionRequests,movementUnionCacheHits,movementMeasurementRequests,movementMeasurementHits);
	}
	public CostProjectionStatistics costProjectionStatistics(int owner) {
		MutableCostProjectionStatistics statistics=costProjectionStatistics.get(owner);
		return statistics==null ? emptyCostProjectionStatistics()
			: statistics.snapshot();
	}
	public CostProjectionStatistics costProjectionStatistics() {
		long[] total=new long[12];
		for(MutableCostProjectionStatistics value:costProjectionStatistics.values()) {
			CostProjectionStatistics statistics=value.snapshot();
			total[0]+=statistics.bindingDagVisits(); total[1]+=statistics.projectionCacheHits();
			total[2]+=statistics.relevantGuardDecisions(); total[3]+=statistics.supportPrefixChecks();
			total[4]+=statistics.noRegionsSkipped(); total[5]+=statistics.unknownObligations();
			total[6]+=statistics.invariantProjectionStops(); total[7]+=statistics.activationEnvironments();
			total[8]+=statistics.projectedContexts(); total[9]+=statistics.primitiveVectors();
			total[10]+=statistics.primitiveValues(); total[11]+=statistics.primitiveValueBytes();
		}
		return new CostProjectionStatistics(total[0],total[1],total[2],total[3],total[4],total[5],
			total[6],total[7],total[8],total[9],total[10],total[11]);
	}
	private static CostProjectionStatistics emptyCostProjectionStatistics() {
		return new CostProjectionStatistics(0,0,0,0,0,0,0,0,0,0,0,0);
	}
	public Solution optimize(MemoOptimizer.Limits limits) {
		MemoOptimizer.Result result = new MemoOptimizer(sizes,coverage,nativeCosts,checks,costs,limits).optimize();
		if(result.status()==MemoOptimizer.Status.OPTIMAL
			&& evaluateCost(result.selectedRules()).total().compareTo(result.cost())!=0)
			throw new IllegalStateException("Selected Memo cost differs from the original cost ownership model");
		Map<Long,List<MemoSupplyRegistry.Supply>> supplies = result.status() == MemoOptimizer.Status.OPTIMAL
			? reconstructSupplies(result.selectedRules()) : Map.of();
		return new Solution(result,supplies,diagnostics);
	}

	/** Re-evaluate one complete selection from the original, ungrouped cost primitives. */
	public CostBreakdown evaluateCost(int[] selected) {
		if(selected==null || selected.length!=sizes.length)
			throw new IllegalArgumentException("Cost evaluation requires one complete Memo selection");
		BigDecimal nativeTotal=BigDecimal.ZERO;
		BigDecimal movementTotal=BigDecimal.ZERO;
		for(int owner=0;owner<sizes.length;owner++) {
			if(selected[owner]<0 || selected[owner]>=sizes[owner])
				throw new IllegalArgumentException("Invalid selected rule for owner "+owner);
			nativeTotal=nativeTotal.add(new BigDecimal(nativeCosts[owner][selected[owner]]));
		}
		for(int i=0;i<costs.size();i++) {
			CostTerm term=costs.get(i);
			int[] tuple=java.util.Arrays.stream(term.owners()).map(owner -> selected[owner]).toArray();
			BigDecimal value=term.evaluateExact(tuple);
			if(i<nativeCostTermCount) nativeTotal=nativeTotal.add(value);
			else movementTotal=movementTotal.add(value);
		}
		return new CostBreakdown(nativeTotal,movementTotal);
	}

	private Truth compatible(Use use,int useIndex,int[] selected) {
		Truth result=privacyCompatible(use,selected);
		if(result==Truth.NO) return result;
		for(int position=0; position<use.bindings().size(); position++) {
			Truth edge=inputCompatible(use,new MovementEdge(useIndex,position),selected);
			if(edge==Truth.NO) return edge;
			if(edge==Truth.UNKNOWN) result=Truth.UNKNOWN;
		}
		return result;
	}

	/** Correlated privacy environments stay in Facts, without producer-choice products. */
	private Truth privacyCompatible(Use use,int[] selected) {
		return privacyCompatible(use,selected[use.consumer().compiledOwnerId()]);
	}

	private Truth privacyCompatible(Use use,int choice) {
		int owner=use.consumer().compiledOwnerId();
		if(choice<0) return Truth.UNKNOWN;
		RuleMemo.Rule rule=memo.rule(owner,choice);
		List<Integer> localInputs=new ArrayList<>();
		for(int position=0; position<rule.inputs().size(); position++)
			if(rule.inputs().get(position)==MemoPartition.LOCAL) localInputs.add(position);
		PrivacyDemand demand=new PrivacyDemand(use.consumer().id(),List.copyOf(localInputs),
			rule.exec()==org.apache.sysds.common.Types.ExecType.CP || rule.outputs().contains(MemoPartition.LOCAL));
		Truth known=privacyLegality.get(demand);
		if(known!=null) return known;
		Truth result=privacyCompatible(use,rule);
		privacyLegality.put(demand,result);
		return result;
	}

	private Truth privacyCompatible(Use use,RuleMemo.Rule rule) {
		int owner=use.consumer().compiledOwnerId();
		int[] selected=new int[sizes.length]; Arrays.fill(selected,-1);
		Set<Integer> privacyBindings=new LinkedHashSet<>();
		for(int i=0; i<rule.inputs().size(); i++)
			if(rule.inputs().get(i)==MemoPartition.LOCAL && !privacy.provesReleasedBinding(use.bindings().get(i)))
				privacyBindings.add(use.bindings().get(i));
		boolean outputNeedsProof=(rule.exec()==org.apache.sysds.common.Types.ExecType.CP || rule.outputs().contains(MemoPartition.LOCAL))
			&& !privacy.provesReleasedOccurrence(use.consumer().id());
		if(outputNeedsProof) privacyBindings.addAll(use.bindings());
		if(privacyBindings.isEmpty() && !outputNeedsProof) return Truth.YES;
		final List<Integer> relevantPrivacy=List.copyOf(privacyBindings);
		Truth[] result={Truth.YES};
		for(List<Integer> frame : use.frames()) {
			Context context=privacyContext(context(selected,use.consumer().regionId(),frame),relevantPrivacy);
			// Activation and privacy need no exact producer-origin product. Every
			// input edge has its own universal inputCompatible availability check
			// under the same guards; here we derive only correlated payload privacy.
			var visit=visitActive(use.consumer(),List.of(),context,environment -> {
				Context exactEnvironment=context.withEnvironment(environment).withPayloadOnly();
				var conditional=privacy.visitConditionalInputs(relevantPrivacy,exactEnvironment,exact -> {
					var legal=privacy.unconditionalRuleLegalityAt(use.consumer().id(),rule,
						universe.inputRoles().get(owner),exact);
					if(legal.legality()==MemoPrivacy.Legality.ILLEGAL) { result[0]=Truth.NO; return false; }
					if(legal.legality()==MemoPrivacy.Legality.DEFER) result[0]=Truth.UNKNOWN;
					return true;
				});
				if(result[0]==Truth.NO) return false;
				if(conditional.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
				return true;
			});
			if(result[0]==Truth.NO) return Truth.NO;
			if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
		}
		return result[0];
	}

	/** Each reachable source row is a universal obligation, not a source-choice product. */
	private void registerInputChecks() {
		int[] unselected=new int[sizes.length]; Arrays.fill(unselected,-1);
		for(int index=0;index<uses.size();index++) {
			Use use=uses.get(index);
			int owner=use.consumer().compiledOwnerId();
			for(int position=0;position<use.bindings().size();position++) {
				MovementEdge edge=new MovementEdge(index,position);
				int[] originalScope=scope(List.of(use.bindings().get(position)),List.of(owner),use.frames());
				List<MovementInputProjection> projections=new ArrayList<>();
				// Singleton-only checks have no repeated choice computation to factor. Keep
				// their lazy original proof, especially for long scalar/source-only programs.
				boolean complete=movementReuse && Arrays.stream(originalScope).anyMatch(value -> sizes[value]>1);
				if(complete) for(List<Integer> frame:use.frames()) {
					MovementInputProjection projection=movementProjection(edge,frame);
					// Keep the old checker when resolution or bounded retention is incomplete.
					if(projection.status()!=MemoBindingResolver.VisitStatus.COMPLETE
						|| cachedMovementProjection(edge,frame)!=projection) { complete=false; break; }
					projections.add(projection);
				}
				if(!complete) {
					checks.add(new Check(use.consumer().id(),originalScope,choices ->
						inputCompatibleExpanded(use,edge,originalScope,choices)));
					continue;
				}
				for(int frameIndex=0;frameIndex<projections.size();frameIndex++) {
					Context base=context(unselected,use.consumer().regionId(),use.frames().get(frameIndex));
					for(ActivationRow row:projections.get(frameIndex).rows())
						registerInputRow(use,position,row,base,unselected);
				}
			}
		}
	}

	private void registerInputRow(Use use,int position,ActivationRow row,Context base,int[] unselected) {
		int consumer=use.consumer().compiledOwnerId();
		ResolvedOrigin origin=row.origin();
		int source=placementOwner(origin);
		int[] scope=source<0 || source==consumer ? new int[] {consumer}
			: source<consumer ? new int[] {source,consumer} : new int[] {consumer,source};
		int consumerPosition=scope[0]==consumer ? 0 : 1;
		int sourcePosition=source<0 ? -1 : scope[0]==source ? 0 : 1;
		MemoPartition[] outputs=new MemoPartition[source<0 ? 1 : sizes[source]];
		for(int choice=0;choice<outputs.length;choice++) {
			if(source>=0) unselected[source]=choice;
			outputs[choice]=layout(origin,unselected);
		}
		if(source>=0) unselected[source]=-1;
		// The row fixes guards, phases, cardinalities, frame and depth. Model head binding
		// is fixed by headsByLoop; shape evaluation ignores selected rule/layout payloads.
		// Retain the entire row and lazily evaluate only the actually requested layouts.
		Truth[][] legality=new Truth[MemoPartition.values().length][MemoPartition.values().length];
		int[][] truthClasses=inputTruthClasses(scope,consumer,source,position,outputs);
		checks.add(new Check(use.consumer().id(),scope,choices -> {
			int consumerRule=choices[consumerPosition];
			if(consumerRule<0) return Truth.UNKNOWN;
			int sourceRule=sourcePosition<0 ? 0 : choices[sourcePosition];
			MemoPartition from=sourceRule<0 ? null : outputs[sourceRule];
			if(from==null) return Truth.UNKNOWN;
			RuleMemo.Rule rule=memo.rule(consumer,consumerRule);
			MemoPartition target=rule.inputs().get(position);
			Truth known=legality[from.ordinal()][target.ordinal()];
			if(known!=null) return known;
			Truth result=inputCompatibility(use,position,rule,from,base,row.environment());
			legality[from.ordinal()][target.ordinal()]=result;
			return result;
		},truthClasses));
	}
	private int[][] inputTruthClasses(int[] scope,int consumer,int source,int inputPosition,MemoPartition[] outputs) {
		if(scope.length==1) {
			int owner=scope[0];
			int[] signatures=new int[sizes[owner]];
			for(int ordinal=0;ordinal<signatures.length;ordinal++) {
				MemoPartition from=outputs[source<0 ? 0 : ordinal];
				MemoPartition target=memo.rule(consumer,ordinal).inputs().get(inputPosition);
				signatures[ordinal]=layoutPair(from,target);
			}
			return new int[][] {denseClasses(signatures)};
		}
		int[][] result=new int[2][];
		for(int position=0;position<scope.length;position++) {
			int owner=scope[position];
			int[] signatures=new int[sizes[owner]];
			for(int ordinal=0;ordinal<signatures.length;ordinal++)
				signatures[ordinal]=owner==source ? layoutCode(outputs[ordinal])
					: layoutCode(memo.rule(consumer,ordinal).inputs().get(inputPosition));
			result[position]=denseClasses(signatures);
		}
		return result;
	}

	private int placementOwner(ResolvedOrigin origin) {
		if(origin.kind()==OriginKind.ITERATOR_LOCAL) return -1;
		if(origin.kind()==OriginKind.LOOP_HEAD_CONTRACT) {
			Head head=head(origin.referenceId(),origin.boundarySlot());
			return head==null ? -1 : head.variable();
		}
		return origin.compiledOwnerId();
	}

	/** Universal conjunction distributes over exact guarded input projections. */
	private Truth inputCompatible(Use use,MovementEdge edge,int[] selected) {
		int position=edge.position();
		int owner=use.consumer().compiledOwnerId(), choice=selected[owner];
		if(choice<0) return Truth.UNKNOWN;
		RuleMemo.Rule rule=memo.rule(owner,choice);
		int binding=use.bindings().get(position);
		Truth[] result={Truth.YES};
		for(List<Integer> frame : use.frames()) {
			Context context=context(selected,use.consumer().regionId(),frame);
			MovementInputProjection projection=movementReuse ? cachedMovementProjection(edge,frame) : null;
			if(projection!=null) {
				movementActivationReplays++;
				for(ActivationRow row:projection.rows()) {
					ResolvedEnvironment environment=row.environment();
					ResolvedOrigin origin=row.origin();
					MemoPartition layout=layout(origin,selected);
					Truth compatible=inputCompatibility(use,position,rule,layout,context,environment);
					if(compatible==Truth.NO) return Truth.NO;
					if(compatible==Truth.UNKNOWN) result[0]=Truth.UNKNOWN;
				}
				if(projection.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
				continue;
			}
			var visit=visitActive(use.consumer(),List.of(binding),context,environment -> {
				ResolvedOrigin origin=environment.origins().get(0);
				MemoPartition layout=layout(origin,selected);
				Truth compatible=inputCompatibility(use,position,rule,layout,context,environment);
				if(compatible==Truth.NO) { result[0]=Truth.NO; return false; }
				if(compatible==Truth.UNKNOWN) result[0]=Truth.UNKNOWN;
				return true;
			});
			if(result[0]==Truth.NO) return Truth.NO;
			if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
		}
		return result[0];
	}
	private Truth inputCompatibility(Use use,int position,RuleMemo.Rule rule,MemoPartition layout,
		Context context,ResolvedEnvironment environment) {
		int owner=use.consumer().compiledOwnerId();
		if(universe.inputRoles().get(owner).get(position)==InputRole.METADATA)
			return layout==null ? Truth.UNKNOWN : layout==rule.inputs().get(position) ? Truth.YES : Truth.NO;
		if(layout==null) return Truth.UNKNOWN;
		MemoMovement.Kind kind=MemoMovement.kind(layout,rule.inputs().get(position),false);
		if(kind!=MemoMovement.Kind.COLLECT) {
			Hop source=use.consumer().hop().getInput(position);
			var shape=kind==MemoMovement.Kind.UPLOAD
				? shapes.inputShape(use.consumer().id(),position,context.withEnvironment(environment).evaluation()) : null;
			return MemoMovement.legal(layout,rule.inputs().get(position),source.getDataType(),null,
				shape==null ? -1 : shape.rows(),shape==null ? -1 : shape.columns(),sources.workers(),false);
		}
		DataType type=use.consumer().hop().getInput(position).getDataType();
		return type==DataType.MATRIX || type==DataType.FRAME ? Truth.YES : Truth.NO;
	}

	private Context withPhase(Context context,int loop,List<Integer> frame,IterationPhase phase) {
		EvaluationContext e=context.evaluation();
		Map<LoopDecision,IterationPhase> phases=new LinkedHashMap<>(e.iterationPhases());
		phases.put(new LoopDecision(loop,frame),phase);
		return context.withEvaluation(new EvaluationContext(e.guards(),e.loops(),e.selections(),e.maxDepth(),
			e.callFrame(),phases,e.cardinalities()));
	}
	private Context withCardinality(Context context,int loop,List<Integer> frame,LoopCardinality cardinality) {
		EvaluationContext e=context.evaluation();
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities=new LinkedHashMap<>(e.cardinalities());
		cardinalities.put(new LoopCardinalityDecision(loop,frame),cardinality);
		return context.withEvaluation(new EvaluationContext(e.guards(),e.loops(),e.selections(),e.maxDepth(),
			e.callFrame(),e.iterationPhases(),cardinalities));
	}
	private Context withLoopCase(Context context,int loop,List<Integer> frame,LoopCardinality cardinality,
		IterationPhase phase) {
		Context selected=withCardinality(context,loop,frame,cardinality);
		return phase==null ? selected : withPhase(selected,loop,frame,phase);
	}
	/** A overwritten initializer is not a source for a later value version. */
	private boolean entryRequired(Head head,List<Integer> frame) {
		EntryUse key=new EntryUse(head.slot(),frame);
		Boolean known=entryUses.get(key); if(known!=null) return known;
		int[] none=new int[sizes.length]; Arrays.fill(none,-1);
		var loop=facts.loop(head.slot().loopId());
		LoopCardinality cardinality=firstBodyCase(loop.id()).cardinality();
		Context first=withLoopCase(context(none,loop.regionId(),frame),loop.id(),frame,cardinality,
			IterationPhase.FIRST);
		boolean needed=resolver.directLoopHeads(List.of(head.value().backedgeBindingId()),first.evaluation()).contains(head.slot());
		for(Use use : uses) {
			if(needed) break;
			for(List<Integer> useFrame : use.frames()) {
				if(!insideLoop(use.consumer().regionId(),useFrame,loop.id(),frame)) continue;
				Context useContext=withLoopCase(context(none,use.consumer().regionId(),useFrame),loop.id(),frame,
					cardinality,IterationPhase.FIRST);
				if(!resolver.directLoopHeads(use.bindings(),useContext.evaluation()).contains(head.slot())) continue;
				boolean[] reached={false};
				var visit=visitActive(use.consumer(),use.bindings(),useContext,environment -> {
					for(ResolvedOrigin origin : environment.origins())
						if(origin.kind()==OriginKind.LOOP_HEAD_CONTRACT && origin.referenceId()==loop.id()
							&& origin.boundarySlot()==head.slot().slot() && origin.callFrame().equals(frame)) {
							reached[0]=true; return false;
						}
					return true;
				});
				// Incomplete liveness cannot justify deleting an entry obligation.
				needed |= reached[0] || visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED;
				if(needed) break;
			}
		}
		entryUses.put(key,needed); return needed;
	}
	private boolean insideLoop(int region,List<Integer> frame,int loop,List<Integer> loopFrame) {
		if(frame.size()<loopFrame.size() || !frame.subList(0,loopFrame.size()).equals(loopFrame)) return false;
		int at=frame.size()==loopFrame.size() ? region
			: facts.occurrence(facts.call(frame.get(loopFrame.size())).callOccurrenceId()).regionId();
		for(int r=at; r>=0; r=facts.region(r).parentId()) if(facts.region(r).loopId()==loop) return true;
		return false;
	}
	private List<IterationPhase> takenBackedgePhases(int loopId) {
		return facts.loopExecutionCases(loopId).stream()
			.filter(value -> value.cardinality()==LoopCardinality.MANY)
			.flatMap(value -> (value.exactIterations()==2 ? List.of(IterationPhase.FIRST)
				: List.of(IterationPhase.FIRST,IterationPhase.MIDDLE)).stream()).distinct().toList();
	}
	private LoopExecutionCase firstBodyCase(int loopId) {
		return facts.loopExecutionCases(loopId).stream().filter(value -> value.cardinality()!=LoopCardinality.ZERO)
			.findFirst().orElseThrow(() -> new IllegalStateException("Loop has no reachable body case: "+loopId));
	}
	private MemoShapes.Shape repeatShape(Head head,Context context,List<Integer> frame,IterationPhase phase) {
		return shapes.bindingShape(head.value().backedgeBindingId(),
			withPhase(context,head.slot().loopId(),frame,phase).evaluation());
	}
	private void registerBoundaryChecks() {
		int[] unselected=new int[sizes.length]; Arrays.fill(unselected,-1);
		int retained=0;
		for(Head head:heads) {
			List<BoundaryRow> rows=null;
			if(movementReuse && retained<MOVEMENT_ACTIVATION_CACHE_LIMIT) {
				try {
					rows=projectBoundary(head,unselected,MOVEMENT_ACTIVATION_CACHE_LIMIT-retained);
				}
				catch(RuntimeException incomplete) {
					// This optional proof cannot move a failure ahead of the original lazy check.
					// Nothing has been published; the original evaluator remains authoritative.
				}
			}
			if(rows==null) {
				int[] scope=scope(List.of(head.value().entryBindingId(),head.value().backedgeBindingId()),
					List.of(head.variable()),framesFor(facts.loop(head.slot().loopId()).regionId()));
				checks.add(new Check(-1-head.variable(),scope,choices -> boundaryCompatibleExpanded(head,scope,choices)));
				continue;
			}
			retained+=rows.size();
			if(rows.isEmpty()) checks.add(new Check(-1-head.variable(),new int[] {head.variable()},
				choices -> choices[0]<0 ? Truth.UNKNOWN : Truth.YES));
			for(BoundaryRow row:rows) registerBoundaryRow(head,row,unselected);
		}
	}
	/** Prove every placement-independent contract before distributing the universal conjunction. */
	private List<BoundaryRow> projectBoundary(Head head,int[] unselected,int limit) {
		List<BoundaryRow> rows=new ArrayList<>();
		MemoShapes.Shape contractRepeat=null;
		MemoShapes.Shape[] contractEntry={null};
		Boolean contractEntryRequired=null;
		var loop=facts.loop(head.slot().loopId());
		LoopExecutionCase bodyCase=firstBodyCase(loop.id());
		for(List<Integer> frame:framesFor(loop.regionId())) {
			Context base=withCardinality(context(unselected,loop.regionId(),frame),loop.id(),frame,
				bodyCase.cardinality());
			MemoShapes.Shape repeat=repeatShape(head,base,frame,IterationPhase.FIRST);
			if(!repeat.exact() || contractRepeat!=null && !samePhysicalShape(contractRepeat,repeat)) return null;
			contractRepeat=repeat;
			boolean required=entryRequired(head,frame);
			if(contractEntryRequired!=null && contractEntryRequired!=required) return null;
			contractEntryRequired=required;
			List<LoopBoundary> boundaries=new ArrayList<>();
			if(required) boundaries.add(new LoopBoundary(true,IterationPhase.FIRST));
			for(IterationPhase phase:takenBackedgePhases(loop.id())) boundaries.add(new LoopBoundary(false,phase));
			for(LoopBoundary boundary:boundaries) {
				boolean entry=boundary.entry();
				int binding=entry ? head.value().entryBindingId() : head.value().backedgeBindingId();
				Context context=withLoopCase(base,loop.id(),frame,
					entry ? bodyCase.cardinality() : LoopCardinality.MANY,boundary.phase());
				boolean[] complete={true};
				var visit=visitActive(entry ? loop.activationBindingId() : loop.backedgeActivationBindingId(),
					List.of(binding),context,environment -> {
					Context exact=context.withEnvironment(environment);
					MemoShapes.Shape actual=shapes.bindingShape(binding,exact.evaluation());
					if(!actual.exact() || !loopBoundaryTypeCompatible(entry,actual,repeat)
						|| !entry && (actual.rows()!=repeat.rows() || actual.columns()!=repeat.columns())
						|| entry && contractEntry[0]!=null && !samePhysicalShape(contractEntry[0],actual)
						|| rows.size()>=limit) { complete[0]=false; return false; }
					if(entry) contractEntry[0]=actual;
					rows.add(new BoundaryRow(binding,entry,environment.origins().get(0),exact,actual,
						actual.valueType()!=repeat.valueType()));
					return true;
				});
				if(!complete[0] || visit.status()!=MemoBindingResolver.VisitStatus.COMPLETE) return null;
			}
		}
		return rows;
	}
	private void registerBoundaryRow(Head head,BoundaryRow row,int[] unselected) {
		int consumer=head.variable(),source=placementOwner(row.origin());
		int[] scope=source<0 || source==consumer ? new int[] {consumer}
			: source<consumer ? new int[] {source,consumer} : new int[] {consumer,source};
		int headPosition=scope[0]==consumer ? 0 : 1;
		int sourcePosition=source<0 ? -1 : scope[0]==source ? 0 : 1;
		MemoPartition[] outputs=new MemoPartition[source<0 ? 1 : sizes[source]];
		for(int choice=0;choice<outputs.length;choice++) {
			if(source>=0) unselected[source]=choice;
			outputs[choice]=layout(row.origin(),unselected);
		}
		if(source>=0) unselected[source]=-1;
		MemoPartition[] targets=new MemoPartition[sizes[consumer]];
		for(int choice=0;choice<targets.length;choice++) {
			unselected[consumer]=choice; targets[choice]=headLayout(head,unselected);
		}
		unselected[consumer]=-1;
		Truth[][] legality=new Truth[MemoPartition.values().length][MemoPartition.values().length];
		int[][] truthClasses=boundaryTruthClasses(scope,consumer,source,outputs,targets);
		checks.add(new Check(-1-consumer,scope,choices -> {
			if(choices[headPosition]<0) return Truth.UNKNOWN;
			int sourceRule=sourcePosition<0 ? 0 : choices[sourcePosition];
			MemoPartition from=sourceRule<0 ? null : outputs[sourceRule];
			if(from==null) return Truth.UNKNOWN;
			MemoPartition target=targets[choices[headPosition]];
			Truth known=legality[from.ordinal()][target.ordinal()];
			if(known!=null) return known;
			Truth result=Truth.YES;
			if(row.entryTypeTransition() && (from!=MemoPartition.LOCAL || target!=MemoPartition.LOCAL
				|| MemoMovement.kind(from,target,false)!=MemoMovement.Kind.DIRECT)) result=Truth.NO;
			if(target==MemoPartition.LOCAL) {
				var residency=privacy.localResidencyAt(row.binding(),privacyContext(row.context(),List.of(row.binding())));
				if(residency.legality()==MemoPrivacy.Legality.ILLEGAL) result=Truth.NO;
				else if(residency.legality()==MemoPrivacy.Legality.DEFER && result==Truth.YES) result=Truth.UNKNOWN;
			}
			if(result==Truth.YES) result=row.entry() ? MemoMovement.legal(from,target,row.shape().dataType(),
				Privacy.PUBLIC,row.shape().rows(),row.shape().columns(),sources.workers(),false)
				: from==target ? Truth.YES : Truth.NO;
			legality[from.ordinal()][target.ordinal()]=result;
			return result;
		},truthClasses));
	}
	private int[][] boundaryTruthClasses(int[] scope,int consumer,int source,MemoPartition[] outputs,
		MemoPartition[] targets) {
		if(scope.length==1) {
			int[] signatures=new int[sizes[scope[0]]];
			for(int ordinal=0;ordinal<signatures.length;ordinal++)
				signatures[ordinal]=layoutPair(outputs[source<0 ? 0 : ordinal],targets[ordinal]);
			return new int[][] {denseClasses(signatures)};
		}
		int[][] result=new int[2][];
		for(int position=0;position<scope.length;position++) {
			int owner=scope[position];
			int[] signatures=new int[sizes[owner]];
			for(int ordinal=0;ordinal<signatures.length;ordinal++)
				signatures[ordinal]=owner==source ? layoutCode(outputs[ordinal]) : layoutCode(targets[ordinal]);
			result[position]=denseClasses(signatures);
		}
		return result;
	}
	private static int layoutCode(MemoPartition layout) {
		return layout==null ? 0 : layout.ordinal()+1;
	}
	private static int layoutPair(MemoPartition from,MemoPartition target) {
		int width=MemoPartition.values().length+1;
		return layoutCode(from)*width+layoutCode(target);
	}
	private static int[] denseClasses(int[] signatures) {
		Map<Integer,Integer> classes=new LinkedHashMap<>();
		int[] result=new int[signatures.length];
		for(int ordinal=0;ordinal<signatures.length;ordinal++)
			result[ordinal]=classes.computeIfAbsent(signatures[ordinal],ignored -> classes.size());
		return result;
	}
	private Truth boundaryCompatible(Head head,int[] selected) {
		if(selected[head.variable()]<0) return Truth.UNKNOWN;
		MemoPartition target=headLayout(head,selected);
		Truth[] result={Truth.YES};
		MemoShapes.Shape[] contractRepeat={null},contractEntry={null};
		Boolean[] contractEntryRequired={null};
		var loop=facts.loop(head.slot().loopId());
		for(List<Integer> frame : framesFor(loop.regionId())) {
			LoopExecutionCase bodyCase=firstBodyCase(loop.id());
			Context base=withCardinality(context(selected,loop.regionId(),frame),loop.id(),frame,
				bodyCase.cardinality());
			MemoShapes.Shape repeat=repeatShape(head,base,frame,IterationPhase.FIRST);
			if(!repeat.exact()) { result[0]=Truth.UNKNOWN; continue; }
			if(contractRepeat[0]==null) contractRepeat[0]=repeat;
			else if(!samePhysicalShape(contractRepeat[0],repeat)) return Truth.NO;
			List<LoopBoundary> boundaries=new ArrayList<>();
			boolean required=entryRequired(head,frame);
			if(contractEntryRequired[0]==null) contractEntryRequired[0]=required;
			else if(contractEntryRequired[0]!=required) return Truth.NO;
			if(required) boundaries.add(new LoopBoundary(true,IterationPhase.FIRST));
			for(IterationPhase phase : takenBackedgePhases(loop.id()))
				boundaries.add(new LoopBoundary(false,phase));
			for(LoopBoundary boundary : boundaries) {
				boolean entry=boundary.entry();
				IterationPhase phase=boundary.phase();
				Context context=withLoopCase(base,loop.id(),frame,
					entry ? bodyCase.cardinality() : LoopCardinality.MANY,phase);
				int binding=entry ? head.value().entryBindingId() : head.value().backedgeBindingId();
				var visit=visitActive(entry ? loop.activationBindingId() : loop.backedgeActivationBindingId(),
					List.of(binding),context,environment -> {
					Context exact=context.withEnvironment(environment);
					MemoShapes.Shape actual=shapes.bindingShape(binding,exact.evaluation());
					if(!actual.exact()) { result[0]=Truth.UNKNOWN; return true; }
					if(!loopBoundaryTypeCompatible(entry,actual,repeat)
						|| !entry && (actual.rows()!=repeat.rows() || actual.columns()!=repeat.columns())) {
						result[0]=Truth.NO; return false;
					}
					if(entry) {
						if(contractEntry[0]==null) contractEntry[0]=actual;
						else if(!samePhysicalShape(contractEntry[0],actual)) { result[0]=Truth.NO; return false; }
					}
					ResolvedOrigin origin=environment.origins().get(0);
					MemoPartition from=layout(origin,selected);
					if(from==null) { result[0]=Truth.UNKNOWN; return true; }
					boolean entryTypeTransition=actual.valueType()!=repeat.valueType();
					if(entryTypeTransition && (from!=MemoPartition.LOCAL || target!=MemoPartition.LOCAL
						|| MemoMovement.kind(from,target,false)!=MemoMovement.Kind.DIRECT)) {
						result[0]=Truth.NO; return false;
					}
					if(target==MemoPartition.LOCAL) {
						var residency=privacy.localResidencyAt(binding,privacyContext(exact,List.of(binding)));
						if(residency.legality()==MemoPrivacy.Legality.ILLEGAL) {
							result[0]=Truth.NO; return false;
						}
						if(residency.legality()==MemoPrivacy.Legality.DEFER) {
							result[0]=Truth.UNKNOWN; return true;
						}
					}
					Truth legal=entry ? MemoMovement.legal(from,target,actual.dataType(),Privacy.PUBLIC,
						actual.rows(),actual.columns(),sources.workers(),false) : from==target ? Truth.YES : Truth.NO;
					if(legal==Truth.NO) { result[0]=Truth.NO; return false; }
					if(legal==Truth.UNKNOWN) result[0]=Truth.UNKNOWN;
					return true;
				});
				if(result[0]==Truth.NO) return Truth.NO;
				if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED) result[0]=Truth.UNKNOWN;
			}
		}
		return result[0];
	}
	private static boolean samePhysicalShape(MemoShapes.Shape left,MemoShapes.Shape right) {
		return left.dataType()==right.dataType() && left.valueType()==right.valueType()
			&& left.rows()==right.rows() && left.columns()==right.columns();
	}
	private static boolean loopBoundaryTypeCompatible(boolean entry,MemoShapes.Shape actual,
		MemoShapes.Shape repeat) {
		if(actual.dataType()!=repeat.dataType()) return false;
		if(actual.valueType()==repeat.valueType()) return true;
		// BinaryScalarScalarCPInstruction promotes this exact arithmetic case at the first body execution.
		return entry && actual.dataType()==DataType.SCALAR
			&& actual.valueType()==ValueType.INT64 && repeat.valueType()==ValueType.FP64;
	}

	private void registerMovementCosts() {
		Map<MovementGroup,Set<MovementEdge>> groups = new LinkedHashMap<>();
		for(int u = 0; u < uses.size(); u++) {
			Use use = uses.get(u);
			for(int i = 0; i < use.bindings().size(); i++) {
				Hop input = use.consumer().hop().getInput(i);
				if(!input.getDataType().isMatrix() && !input.getDataType().isFrame()
					|| universe.inputRoles().get(use.consumer().compiledOwnerId()).get(i) == InputRole.METADATA) continue;
				MovementEdge edge=new MovementEdge(u,i);
				for(List<Integer> frame : use.frames()) {
					for(ActivationRow row:movementProjection(edge,frame).rows()) {
						var identity=row.identity();
						if(retentionScope(identity)!=null)
							groups.computeIfAbsent(new MovementGroup(identity,null),ignored -> new LinkedHashSet<>()).add(edge);
						else groups.computeIfAbsent(new MovementGroup(null,edge),ignored -> new LinkedHashSet<>()).add(edge);
					}
				}
			}
		}
		for(var group : groups.entrySet()) {
			List<Integer> bindings=group.getValue().stream()
				.map(edge -> uses.get(edge.use()).bindings().get(edge.position())).toList();
			List<Integer> owners=group.getValue().stream()
				.map(edge -> uses.get(edge.use()).consumer().compiledOwnerId()).toList();
			List<List<Integer>> frames=group.getValue().stream().flatMap(edge -> uses.get(edge.use()).frames().stream()).toList();
			int[] scope=movementScope(group.getKey(),group.getValue());
			int[][] costClasses=scope==null ? null : movementCostClasses(group.getKey(),group.getValue(),scope);
			if(scope==null) scope=scope(bindings,owners,frames);
			final int[] factorScope=scope;
			if(scope.length>MOVEMENT_FACTOR_WIDTH_DIAGNOSTIC)
				diagnostics.add("MOVEMENT_FACTOR_WIDTH origin="+(group.getKey().identity()==null ? "unretained"
					: group.getKey().identity().visibleBindingId())+" width="+scope.length);
			List<MovementEdge> edges=List.copyOf(group.getValue());
			costs.add(new CostTerm(scope,choices ->
				movementGroupCostExpanded(group.getKey(),edges,factorScope,choices),0,costClasses));
		}
		for(Head head : heads) {
			int[] scope = scope(List.of(head.value().entryBindingId()),List.of(head.variable()),
				framesFor(facts.loop(head.slot().loopId()).regionId()));
			costs.add(new CostTerm(scope,choices -> entryCostExpanded(head,scope,choices),0));
		}
	}
	/** Keep one exact aggregate primitive, but omit alternate origins filtered by its identity. */
	private int[] movementScope(MovementGroup group,Set<MovementEdge> edges) {
		if(!movementReuse) return null;
		Set<Integer> scope=new LinkedHashSet<>();
		for(MovementEdge edge:edges) {
			Use use=uses.get(edge.use());
			scope.add(use.consumer().compiledOwnerId());
			for(List<Integer> frame:use.frames()) {
				MovementInputProjection projection=cachedMovementProjection(edge,frame);
				if(projection==null || projection.status()!=MemoBindingResolver.VisitStatus.COMPLETE) return null;
				for(ActivationRow row:projection.rows()) {
					if(group.identity()!=null ? !group.identity().equals(row.identity())
						: retentionScope(row.identity())!=null) continue;
					int owner=placementOwner(row.origin());
					if(owner>=0) scope.add(owner);
				}
			}
		}
		return scope.stream().sorted().mapToInt(Integer::intValue).toArray();
	}


	/** A term observes raw input/output layouts, including each multi-return slot.
	 * The projection is complete before metadata is attached; no rule domains or
	 * feasibility checks are merged. Entry terms retain their original ordinal scope. */
	private record MovementCostRole(int inputPosition,int outputSlot,Head head) { }
	private int[][] movementCostClasses(MovementGroup group,Set<MovementEdge> edges,int[] scope) {
		Map<Integer,Set<MovementCostRole>> roles=new LinkedHashMap<>();
		for(int owner:scope) roles.put(owner,new LinkedHashSet<>());
		for(MovementEdge edge:edges) {
			Use use=uses.get(edge.use());
			roles.get(use.consumer().compiledOwnerId()).add(new MovementCostRole(edge.position(),-1,null));
			for(List<Integer> frame:use.frames()) {
				MovementInputProjection projection=cachedMovementProjection(edge,frame);
				if(projection==null || projection.status()!=MemoBindingResolver.VisitStatus.COMPLETE) return null;
				for(ActivationRow row:projection.rows()) {
					if(group.identity()!=null ? !group.identity().equals(row.identity())
						: retentionScope(row.identity())!=null) continue;
					ResolvedOrigin origin=row.origin(); int owner=placementOwner(origin);
					if(owner<0) continue;
					Head head=origin.kind()==OriginKind.LOOP_HEAD_CONTRACT
						? head(origin.referenceId(),origin.boundarySlot()) : null;
					roles.get(owner).add(new MovementCostRole(-1,origin.boundarySlot(),head));
				}
			}
		}
		int[][] classes=new int[scope.length][];
		int[] selected=new int[sizes.length]; Arrays.fill(selected,-1);
		for(int position=0;position<scope.length;position++) {
			int owner=scope[position]; classes[position]=new int[sizes[owner]];
			Map<List<Integer>,Integer> states=new LinkedHashMap<>();
			for(int ordinal=0;ordinal<sizes[owner];ordinal++) {
				selected[owner]=ordinal;
				List<Integer> signature=new ArrayList<>(roles.get(owner).size());
				for(MovementCostRole role:roles.get(owner)) {
					MemoPartition partition=role.head()!=null ? headLayout(role.head(),selected)
						: role.inputPosition()>=0 ? memo.rule(owner,ordinal).inputs().get(role.inputPosition())
						: selectedOutput(memo.rule(owner,ordinal),role.outputSlot());
					signature.add(partition==null ? -1 : partition.ordinal());
				}
				classes[position][ordinal]=states.computeIfAbsent(List.copyOf(signature),ignored -> states.size());
			}
			selected[owner]=-1;
		}
		return classes;
	}

	private MovementInputProjection movementProjection(MovementEdge edge,List<Integer> frame) {
		MovementProjectionKey key=new MovementProjectionKey(edge,frame);
		MovementInputProjection cached=movementProjections.get(key);
		if(cached!=null) return cached;
		Use use=uses.get(edge.use());
		int binding=use.bindings().get(edge.position());
		int[] none=new int[sizes.length]; Arrays.fill(none,-1);
		Context base=context(none,use.consumer().regionId(),frame);
		List<ActivationRow> rows=new ArrayList<>();
		var visit=visitActive(use.consumer(),List.of(binding),base,environment -> {
			ResolvedOrigin origin=environment.origins().get(0);
			var identity=resolver.resolveOriginIdentity(binding,base.evaluation(),environment);
			rows.add(new ActivationRow(origin,identity,environment));
			return true;
		});
		MovementInputProjection result=new MovementInputProjection(rows,visit.status());
		movementActivationRows+=rows.size();
		int cacheCharge=Math.max(1,rows.size());
		if(visit.status()==MemoBindingResolver.VisitStatus.COMPLETE
			&& cachedMovementActivationRows+cacheCharge<=MOVEMENT_ACTIVATION_CACHE_LIMIT) {
			movementProjections.put(key,result);
			cachedMovementActivationRows+=cacheCharge;
		}
		return result;
	}
	private MovementInputProjection cachedMovementProjection(MovementEdge edge,List<Integer> frame) {
		return movementProjections.get(new MovementProjectionKey(edge,frame));
	}
	record Activation(Map<GuardDecision,Boolean> guards,Map<LoopDecision,IterationPhase> phases,double weight) {
		Activation(Map<GuardDecision,Boolean> guards,double weight) { this(guards,Map.of(),weight); }
		Activation { guards = Map.copyOf(guards); phases=Map.copyOf(phases); }
	}
	// Each movementGroupCost event map contains only its group's already-validated origin identity.
	private record MovementActivation(MemoPartition target,long unitCostBits) {
		double unitCost() { return Double.longBitsToDouble(unitCostBits); }
	}
	/** Exact union under {@link Profile}'s correlated-per-GuardDecision estimator, not runtime reachability. */
	static double activationUnion(List<Activation> input,double scopeWeight) {
		return activationUnion(input,scopeWeight,0.5);
	}
	static double activationUnion(List<Activation> input,double scopeWeight,double thenProbability) {
		if(!(thenProbability>0 && thenProbability<1))
			throw new IllegalArgumentException("Guard probability must be between zero and one");
		Map<Map<LoopDecision,IterationPhase>,List<Activation>> byPhase=new LinkedHashMap<>();
		for(Activation event : new LinkedHashSet<>(input)) if(event.weight()!=0)
			byPhase.computeIfAbsent(event.phases(),ignored -> new ArrayList<>()).add(event);
		double total=0;
		for(List<Activation> events : byPhase.values()) {
			Set<Map<GuardDecision,Boolean>> cubes=new LinkedHashSet<>();
			double base=Double.NaN;
			for(Activation event : events) {
				double probability=guardCubeProbability(event.guards(),thenProbability);
				if(probability==0) continue;
				double candidate=event.weight()/probability;
				if(Double.isNaN(base)) base=candidate;
				else if(Math.abs(base-candidate)>1e-9*Math.max(1,Math.max(Math.abs(base),Math.abs(candidate))))
					throw new IllegalStateException("One retained origin has inconsistent activation bases: "
						+base+" and "+candidate);
				cubes.add(event.guards());
			}
			if(!Double.isNaN(base)) total+=base*guardUnionProbability(cubes,thenProbability,
				new LinkedHashMap<>(),new int[] {0});
		}
		return Math.min(scopeWeight,total);
	}
	private static double guardCubeProbability(Map<GuardDecision,Boolean> cube,double thenProbability) {
		if(thenProbability==0.5d) return Math.scalb(1d,-cube.size());
		double result=1;
		for(boolean value : cube.values()) result*=value ? thenProbability : 1-thenProbability;
		return result;
	}
	private static double guardUnionProbability(Set<Map<GuardDecision,Boolean>> cubes,double thenProbability,
		Map<Set<Map<GuardDecision,Boolean>>,Double> memo,int[] states) {
		if(cubes.isEmpty()) return 0;
		for(var cube:cubes) if(cube.isEmpty()) return 1;
		if(Double.doubleToRawLongBits(thenProbability)==Double.doubleToRawLongBits(0.5d)) {
			if(cubes.size()==1) {
				int guards=cubes.iterator().next().size();
				if(guards<=FAIR_GUARD_LIMIT) return Math.scalb(1d,-guards);
			}
			LinkedHashMap<GuardDecision,Integer> positions=new LinkedHashMap<>();
			boolean encodable=true;
			for(var cube:cubes) for(GuardDecision guard:cube.keySet())
				if(!positions.containsKey(guard)) {
					if(positions.size()==FAIR_GUARD_LIMIT) { encodable=false; break; }
					positions.put(guard,positions.size());
				}
			if(encodable) {
				int[] encoded=encodeFairCubes(cubes,positions);
				long numerator=fairGuardUnionAssignments(encoded,(1<<positions.size())-1,
					new LinkedHashMap<>(),new int[1]);
				return numerator/(double)(1L<<positions.size());
			}
		}
		return legacyGuardUnionProbability(cubes,thenProbability,memo,states);
	}
	private static double legacyGuardUnionProbability(Set<Map<GuardDecision,Boolean>> cubes,
		double thenProbability,Map<Set<Map<GuardDecision,Boolean>>,Double> memo,int[] states) {
		if(cubes.isEmpty()) return 0;
		if(cubes.stream().anyMatch(Map::isEmpty)) return 1;
		Set<Map<GuardDecision,Boolean>> key=Set.copyOf(cubes);
		Double cached=memo.get(key); if(cached!=null) return cached;
		if(++states[0]>MOVEMENT_GUARD_STATE_LIMIT)
			throw new IllegalStateException("MOVEMENT_GUARD_FACTOR_STATES exceeds exact limit="
				+MOVEMENT_GUARD_STATE_LIMIT);
		Set<GuardDecision> guards=new LinkedHashSet<>();
		for(var cube : cubes) guards.addAll(cube.keySet());
		GuardDecision split=guards.iterator().next();
		Set<Map<GuardDecision,Boolean>> whenTrue=restrict(cubes,split,true);
		Set<Map<GuardDecision,Boolean>> whenFalse=restrict(cubes,split,false);
		double result=thenProbability*legacyGuardUnionProbability(whenTrue,thenProbability,memo,states)
			+(1-thenProbability)*legacyGuardUnionProbability(whenFalse,thenProbability,memo,states);
		memo.put(key,result); return result;
	}
	private static int[] encodeFairCubes(Set<Map<GuardDecision,Boolean>> cubes,
		Map<GuardDecision,Integer> positions) {
		int[] encoded=new int[cubes.size()]; int count=0;
		for(var cube:cubes) {
			int whenTrue=0,whenFalse=0;
			for(var entry:cube.entrySet()) {
				int bit=1<<positions.get(entry.getKey());
				if(entry.getValue()) whenTrue|=bit; else whenFalse|=bit;
			}
			if((whenTrue&whenFalse)!=0)
				throw new IllegalStateException("One guard cube requires both outcomes of one decision");
			encoded[count++]=whenTrue|(whenFalse<<16);
		}
		return sortedUnique(Arrays.copyOf(encoded,count));
	}
	private static long fairGuardUnionAssignments(int[] cubes,int remaining,
		Map<FairUnionState,Long> memo,int[] states) {
		if(cubes.length==0) return 0;
		for(int cube:cubes) if(cube==0) return 1L<<Integer.bitCount(remaining);
		FairUnionState key=new FairUnionState(remaining,cubes);
		Long cached=memo.get(key);
		if(cached!=null) return cached;
		if(++states[0]>MOVEMENT_GUARD_STATE_LIMIT)
			throw new IllegalStateException("MOVEMENT_GUARD_FACTOR_STATES exceeds exact limit="
				+MOVEMENT_GUARD_STATE_LIMIT);
		if(remaining==0) return 0;
		int bit=Integer.lowestOneBit(remaining);
		long result=fairGuardUnionAssignments(restrictFair(cubes,bit,true),remaining^bit,memo,states)
			+fairGuardUnionAssignments(restrictFair(cubes,bit,false),remaining^bit,memo,states);
		memo.put(key,result);
		return result;
	}
	private static int[] restrictFair(int[] cubes,int bit,boolean value) {
		int[] result=new int[cubes.length]; int count=0;
		for(int cube:cubes) {
			int whenTrue=cube&0xffff,whenFalse=cube>>>16;
			if(value ? (whenFalse&bit)!=0 : (whenTrue&bit)!=0) continue;
			if(value) whenTrue&=~bit; else whenFalse&=~bit;
			result[count++]=whenTrue|(whenFalse<<16);
		}
		return sortedUnique(Arrays.copyOf(result,count));
	}
	private static int[] sortedUnique(int[] values) {
		Arrays.sort(values);
		if(values.length<2) return values;
		int count=1;
		for(int i=1;i<values.length;i++) if(values[i]!=values[count-1]) values[count++]=values[i];
		return count==values.length ? values : Arrays.copyOf(values,count);
	}
	private static final class FairUnionState {
		private final int remaining;
		private final int[] cubes;
		private final int hash;
		private FairUnionState(int remaining,int[] cubes) {
			this.remaining=remaining;
			this.cubes=cubes.clone();
			hash=31*remaining+Arrays.hashCode(cubes);
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			return this==other || other instanceof FairUnionState that
				&& remaining==that.remaining && Arrays.equals(cubes,that.cubes);
		}
	}
	private static Set<Map<GuardDecision,Boolean>> restrict(Set<Map<GuardDecision,Boolean>> cubes,
		GuardDecision decision,boolean value) {
		Set<Map<GuardDecision,Boolean>> result=new LinkedHashSet<>();
		for(var cube : cubes) {
			Boolean required=cube.get(decision);
			if(required!=null && required!=value) continue;
			Map<GuardDecision,Boolean> reduced=new LinkedHashMap<>(cube); reduced.remove(decision);
			result.add(Map.copyOf(reduced));
		}
		return result;
	}
	private double movementGroupCost(MovementGroup group,List<MovementEdge> edges,int[] selected) {
		Map<MovementActivation,List<Activation>> events = new LinkedHashMap<>();
		double[] unretainedCost = {0};
		for(MovementEdge edge : edges) {
			Use use = uses.get(edge.use()); int position = edge.position();
			MemoPartition target = memo.rule(use.consumer().compiledOwnerId(),selected[use.consumer().compiledOwnerId()])
				.inputs().get(position);
			for(List<Integer> frame : use.frames()) {
				Context context = context(selected,use.consumer().regionId(),frame);
				MovementInputProjection projection=movementReuse ? cachedMovementProjection(edge,frame) : null;
				if(projection!=null) {
					movementActivationReplays++;
					for(ActivationRow row:projection.rows())
						accumulateMovement(group,use,position,frame,context,row,selected,events,unretainedCost);
					if(projection.status()==MemoBindingResolver.VisitStatus.UNRESOLVED)
						throw new IllegalStateException("UNRESOLVED_MOVEMENT_ACTIVATION: occurrence "
							+use.consumer().id());
					continue;
				}
				var visit = visitActive(use.consumer(),List.of(use.bindings().get(position)),context,environment -> {
					ResolvedOrigin origin = environment.origins().get(0);
					var identity=resolver.resolveOriginIdentity(use.bindings().get(position),context.evaluation(),environment);
					if(group.identity()!=null ? !group.identity().equals(identity) : retentionScope(identity)!=null)
						return true;
					MemoPartition from = layout(origin,selected);
					if(from == null) throw new IllegalStateException("Unresolved source in selected movement cost");
					MemoMovement.Kind kind = MemoMovement.kind(from,target,false);
					if(kind == MemoMovement.Kind.DIRECT || kind == MemoMovement.Kind.IMPOSSIBLE) return true;
					Context supplied=context.withEnvironment(environment);
					visitBindingCostContexts(use.bindings().get(position),supplied,exact -> {
						ResolvedEnvironment refined=new ResolvedEnvironment(environment.origins(),
							exact.evaluation().guards(),exact.evaluation().loops(),
							exact.evaluation().iterationPhases(),exact.evaluation().cardinalities());
						double weight=group.identity()==null
							? frequency(use.consumer().regionId(),frame,exact.evaluation())
								*environmentProbability(refined,context.evaluation())
							: definitionActivationWeight(identity,refined,selected);
						Hop view=shapes.viewAt(use.consumer().id(),exact.evaluation());
						// The input view already carries the exact reaching output slot's
						// frozen contextual shape. Live producer HOPs are emission identities,
						// not cost authority (especially for heterogeneous builtin outputs).
						double unit = MemoCostModel.movementCost(view.getInput(position),
							from,target,Math.max(1,sources.workers()),1);
						if(group.identity()==null) {
							// This group is one unretained demand edge. It has no shared
							// materialization activation, and its exact phase/call shape may
							// legitimately change between dynamic environments.
							unretainedCost[0] += unit*weight;
							return;
						}
						MovementActivation key=new MovementActivation(target,Double.doubleToLongBits(unit));
						events.computeIfAbsent(key,ignored -> new ArrayList<>()).add(new Activation(
							activeGuards(refined),definitionPhases(identity,refined),weight));
					});
					return true;
				});
				if(visit.status() == MemoBindingResolver.VisitStatus.UNRESOLVED)
					throw new IllegalStateException("UNRESOLVED_MOVEMENT_ACTIVATION: occurrence " + use.consumer().id());
			}
		}
		double total = unretainedCost[0];
		for(var entry : events.entrySet()) {
			double activations=group.identity()==null ? entry.getValue().stream().mapToDouble(Activation::weight).sum()
				: retainedActivationUnion(entry.getValue());
			total += entry.getKey().unitCost()*activations;
		}
		return total;
	}
	private double retainedActivationUnion(List<Activation> events) {
		if(!movementReuse)
			return activationUnion(events,Double.POSITIVE_INFINITY,profile.branchThenProbability());
		movementUnionRequests++;
		ActivationListKey key=new ActivationListKey(events);
		Double known=movementUnionCache.get(key);
		if(known!=null) { movementUnionCacheHits++; return known; }
		double result=activationUnion(events,Double.POSITIVE_INFINITY,profile.branchThenProbability());
		movementUnionCache.put(key,result);
		return result;
	}

	private void accumulateMovement(MovementGroup group,Use use,int position,List<Integer> frame,Context context,
		ActivationRow row,int[] selected,Map<MovementActivation,List<Activation>> events,double[] unretainedCost) {
		ResolvedOrigin origin=row.origin();
		var identity=row.identity();
		if(group.identity()!=null ? !group.identity().equals(identity) : retentionScope(identity)!=null) return;
		MemoPartition from=layout(origin,selected);
		if(from==null) throw new IllegalStateException("Unresolved source in selected movement cost");
		MemoPartition target=memo.rule(use.consumer().compiledOwnerId(),selected[use.consumer().compiledOwnerId()])
			.inputs().get(position);
		MemoMovement.Kind kind=MemoMovement.kind(from,target,false);
		if(kind==MemoMovement.Kind.DIRECT || kind==MemoMovement.Kind.IMPOSSIBLE) return;
		movementPostGateRequests++;
		if(row.costRows!=null) {
			movementPostGateHits++;
			for(MovementPrimitive primitive:row.costRows)
				applyMovementPrimitive(group,use,position,context,primitive,from,target,events,unretainedCost);
			return;
		}
		List<MovementPrimitive> built=new ArrayList<>();
		ResolvedEnvironment environment=row.environment();
		Context supplied=context.withEnvironment(environment);
		visitBindingCostContexts(use.bindings().get(position),supplied,exact -> {
			ResolvedEnvironment refined=new ResolvedEnvironment(environment.origins(),exact.evaluation().guards(),
				exact.evaluation().loops(),exact.evaluation().iterationPhases(),exact.evaluation().cardinalities());
			boolean retained=group.identity()!=null;
			double weight=retained ? definitionActivationWeight(identity,refined)
				: frequency(use.consumer().regionId(),frame,exact.evaluation())
					*environmentProbability(refined,context.evaluation());
			MovementPrimitive primitive=new MovementPrimitive(refined,weight,Map.of(),Map.of());
			double unit=movementUnit(use,position,context,primitive,from,target);
			if(retained) {
				MovementPrimitive completed=new MovementPrimitive(refined,weight,activeGuards(refined),
					definitionPhases(identity,refined));
				completed.copyMeasurementsFrom(primitive);
				completed.units.put(new UnitKey(from,target),Double.doubleToLongBits(unit));
				primitive=completed;
			}
			accumulateMovementPrimitive(group,primitive,target,unit,events,unretainedCost);
			built.add(primitive);
		});
		movementPostGateBuilds++;
		movementPrimitiveContexts+=built.size();
		if(cachedMovementPrimitives+built.size()<=MOVEMENT_PRIMITIVE_CACHE_LIMIT) {
			row.costRows=List.copyOf(built);
			cachedMovementPrimitives+=built.size();
		}
	}
	private void applyMovementPrimitive(MovementGroup group,Use use,int position,Context context,MovementPrimitive primitive,MemoPartition from,MemoPartition target,
		Map<MovementActivation,List<Activation>> events,double[] unretainedCost) {
		double unit=movementUnit(use,position,context,primitive,from,target);
		accumulateMovementPrimitive(group,primitive,target,unit,events,unretainedCost);
	}
	private void accumulateMovementPrimitive(MovementGroup group,MovementPrimitive primitive,MemoPartition target,double unit,
		Map<MovementActivation,List<Activation>> events,double[] unretainedCost) {
		if(group.identity()==null) unretainedCost[0]+=unit*primitive.weight;
		else {
			MovementActivation key=new MovementActivation(target,Double.doubleToLongBits(unit));
			events.computeIfAbsent(key,ignored -> new ArrayList<>()).add(primitive.activation);
		}
	}
	private double movementUnit(Use use,int position,Context context,MovementPrimitive primitive,
		MemoPartition from,MemoPartition target) {
		UnitKey key=new UnitKey(from,target);
		Long cached=primitive.units.get(key);
		if(cached!=null) return Double.longBitsToDouble(cached);
		int workers=Math.max(1,sources.workers());
		boolean upload=from.normalize(workers)==MemoPartition.LOCAL;
		movementMeasurementRequests++;
		MemoCostModel.MovementMeasurement measurement=upload
			? primitive.uploadMeasurement : primitive.downloadMeasurement;
		boolean publishMeasurement=false;
		Double preparedUnit=null;
		if(measurement==null) {
			Context exact=context.withEnvironment(primitive.environment);
			if(movementRecipeCacheEntries>0 && shapes.supportsPreparedMovementView(use.consumer().id())) {
				PreparedMovementPrice price=pricePreparedMovement(
					shapes.prepareMovementView(use.consumer().id(),exact.evaluation()),position,from,target,workers);
				measurement=price.measurement(); preparedUnit=price.unit();
			}
			else {
				Hop view=shapes.viewAt(use.consumer().id(),exact.evaluation());
				movementContextualViewBuilds++;
				measurement=MemoCostModel.movementMeasurement(view.getInput(position),from,target,workers);
			}
			publishMeasurement=true;
		}
		else movementMeasurementHits++;
		double unit=preparedUnit!=null ? preparedUnit
			: MemoCostModel.movementCost(measurement,from,target,workers,1);
		// Do not publish a partially validated measurement: the first complete
		// movement request must succeed before later targets may reuse its bytes.
		if(publishMeasurement) {
			if(upload) primitive.uploadMeasurement=measurement;
			else primitive.downloadMeasurement=measurement;
		}
		primitive.units.put(key,Double.doubleToLongBits(unit));
		return unit;
	}

	private PreparedMovementPrice pricePreparedMovement(MemoShapes.PreparedMovementView prepared,
		int position,MemoPartition from,MemoPartition target,int workers) {
		boolean upload=from.normalize(workers)==MemoPartition.LOCAL;
		MovementRecipeKey key=new MovementRecipeKey(prepared.recipe(),position,upload,workers);
		movementRecipeRequests++;
		if(prepared.view()!=null) movementContextualViewBuilds++;
		MemoCostModel.MovementMeasurement measurement=movementRecipeCache.get(key);
		if(measurement!=null) movementRecipeHits++;
		else {
			Hop view=prepared.view()!=null ? prepared.view()
				: shapes.materializeMovementView(prepared.recipe());
			if(prepared.view()==null) movementContextualViewBuilds++;
			measurement=MemoCostModel.movementMeasurement(view.getInput(position),from,target,workers);
		}
		double unit=MemoCostModel.movementCost(measurement,from,target,workers,1);
		if(!movementRecipeCache.containsKey(key)) movementRecipeCache.put(key,measurement);
		return new PreparedMovementPrice(measurement,unit);
	}
	private double definitionActivationWeight(MemoBindingResolver.OriginIdentity identity,
		ResolvedEnvironment environment,int[] selected) {
		return definitionActivationWeight(identity,environment);
	}
	private double definitionActivationWeight(MemoBindingResolver.OriginIdentity identity,
		ResolvedEnvironment environment) {
		Occurrence definition=facts.occurrence(identity.nativeDefinitionOccurrenceId());
		int[] none=unselectedDefinitionRules;
		EvaluationContext evaluation=new EvaluationContext(environment.guards(),environment.loops(),
			context(none,definition.regionId(),identity.callFrame()).evaluation().selections(),4096,
			identity.callFrame(),environment.iterationPhases(),environment.cardinalities());
		Map<GuardDecision,Boolean> all=activeGuards(environment);
		Map<GuardDecision,Boolean> defining=new LinkedHashMap<>(
			context(none,definition.regionId(),identity.callFrame()).evaluation().guards());
		defining.entrySet().removeIf(entry -> phaseGuard(entry.getKey().guardId(),entry.getKey().callFrame(),
			environment.iterationPhases())!=null);
		if(!all.entrySet().containsAll(defining.entrySet()))
			throw new IllegalStateException("Demand environment contradicts its exact native definition guards");
		double relative=guardCubeProbability(all,profile.branchThenProbability())
			/guardCubeProbability(defining,profile.branchThenProbability())
			*cardinalityProbability(environment.cardinalities());
		return frequency(definition.regionId(),identity.callFrame(),evaluation)*relative;
	}
	private Map<GuardDecision,Boolean> activeGuards(ResolvedEnvironment environment) {
		Map<GuardDecision,Boolean> result=new LinkedHashMap<>(environment.guards());
		result.entrySet().removeIf(entry -> phaseGuard(entry.getKey().guardId(),entry.getKey().callFrame(),
			environment.iterationPhases())!=null);
		return Map.copyOf(result);
	}
	private Map<LoopDecision,IterationPhase> definitionPhases(MemoBindingResolver.OriginIdentity identity,
		ResolvedEnvironment environment) {
		// LOOP_HEAD/iterator/unresolved origins are costed per demand and have no
		// native definition occurrence whose execution phases could be shared.
		if(identity.nativeDefinitionOccurrenceId()<0) return Map.of();
		Set<LoopDecision> relevant=new LinkedHashSet<>();
		for(var occurrenceContext : facts.occurrenceContexts(identity.nativeDefinitionOccurrenceId()))
			if(occurrenceContext.callFrame().equals(identity.callFrame()))
				for(PhaseLoop loop : occurrenceContext.phaseLoops()) relevant.add(new LoopDecision(loop.loopId(),loop.callFrame()));
		Map<LoopDecision,IterationPhase> result=new LinkedHashMap<>();
		for(var entry : environment.iterationPhases().entrySet()) if(relevant.contains(entry.getKey()))
			result.put(entry.getKey(),entry.getValue());
		return Map.copyOf(result);
	}
	private boolean retainable(MemoBindingResolver.OriginIdentity identity) {
		if(identity.kind()!=OriginKind.COMPILED_OWNER || identity.nativeDefinitionOccurrenceId()<0) return false;
		Occurrence occurrence=facts.occurrence(identity.nativeDefinitionOccurrenceId());
		if(occurrence.hop() instanceof org.apache.sysds.hops.FunctionOp function
			&& function.getFunctionType()==org.apache.sysds.hops.FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			&& identity.boundarySlot()>=0 && identity.exportChain().isEmpty()) {
			int binding=identity.visibleBindingId();
			if(binding<0 || binding>=facts.bindings().size()) return false;
			var node=facts.binding(binding);
			return node.kind()==MemoProgramFacts.BindingKind.DEFINITION
				&& node.referenceId()==occurrence.id() && node.position()==identity.boundarySlot();
		}
		long nativeBinding=facts.valueBinding(identity.nativeDefinitionOccurrenceId());
		if(identity.exportChain().isEmpty()) return identity.visibleBindingId()==nativeBinding;
		return identity.exportChain().get(0).sourceBindingId()==nativeBinding
			&& identity.exportChain().get(identity.exportChain().size()-1).targetBindingId()==identity.visibleBindingId();
	}
	/** Broadest runtime scope in which the exact visible value version exists. */
	private RetentionScope retentionScope(MemoBindingResolver.OriginIdentity identity) {
		if(!retainable(identity)) return null;
		int visibleDepth=identity.callFrame().size()-identity.exportChain().size();
		if(visibleDepth<0) return null;
		if(visibleDepth==0) return new RetentionScope(MemoPlanRegistry.ScopeKind.PROGRAM_ROOT,-1);
		int callId=identity.callFrame().get(visibleDepth-1);
		if(callId<0 || callId>=facts.calls().size()) return null;
		long block=facts.functionStatementBlockId(facts.call(callId).functionSummaryId());
		return block<0 ? null : new RetentionScope(MemoPlanRegistry.ScopeKind.FUNCTION_INVOCATION,block);
	}
	private double entryCost(Head head,int[] selected) {
		double[] total = {0}; var loop = facts.loop(head.slot().loopId());
		for(List<Integer> frame : framesFor(loop.regionId())) {
			if(!entryRequired(head,frame)) continue;
			for(LoopExecutionCase executionCase : loop.executionCases()) {
				if(executionCase.cardinality()==LoopCardinality.ZERO) continue;
				Context base=context(selected,loop.regionId(),frame);
				visitPhases(enclosingPhaseLoops(loop,frame),0,base,enclosing -> {
					Context first=withLoopCase(enclosing,loop.id(),frame,
						executionCase.cardinality(),IterationPhase.FIRST);
					return visitActive(loop.activationBindingId(),List.of(head.value().entryBindingId()),first,environment -> {
						ResolvedOrigin origin=environment.origins().get(0);
						MemoPartition from=layout(origin,selected),target=headLayout(head,selected);
						if(from!=null && from!=target
							&& MemoMovement.kind(from,target,false)!=MemoMovement.Kind.IMPOSSIBLE) {
							Context exact=first.withEnvironment(environment);
							visitBindingCostContexts(head.value().entryBindingId(),exact,shaped -> {
								Hop source=origin.kind()==OriginKind.COMPILED_OWNER
									? shapes.outputViewAt(origin.referenceId(),Math.max(0,origin.boundarySlot()),
										shaped.evaluation().withCallFrame(origin.callFrame()))
									: shapes.bindingViewAt(head.value().entryBindingId(),shaped.evaluation());
								ResolvedEnvironment projected=environment(shaped.evaluation());
								total[0]+=MemoCostModel.movementCost(source,from,target,
									Math.max(1,sources.workers()),frequency(
										facts.regions().get(loop.regionId()).parentId(),frame,shaped.evaluation())
										*environmentProbability(projected,first.evaluation()));
							});
						}
						return true;
					});
				});
			}
		}
		return total[0];
	}

	private List<PhaseLoop> enclosingPhaseLoops(MemoProgramFacts.LoopRecord boundary,List<Integer> frame) {
		Set<PhaseLoop> result=new LinkedHashSet<>();
		for(int depth=frame.size();depth>=0;depth--) {
			List<Integer> prefix=frame.subList(0,depth);
			int region=depth==frame.size() ? facts.region(boundary.regionId()).parentId()
				: facts.occurrence(facts.call(frame.get(depth)).callOccurrenceId()).regionId();
			for(int current=region;current>=0;current=facts.region(current).parentId()) {
				int loop=facts.region(current).loopId();
				if(loop<0 || loop==boundary.id()) continue;
				List<LoopExecutionCase> cases=facts.loopExecutionCases(loop);
				List<IterationPhase> phases=cases.stream().flatMap(value -> value.bodyPhases().stream())
					.distinct().toList();
				if(!phases.isEmpty()) result.add(new PhaseLoop(loop,prefix,phases,cases));
			}
		}
		return List.copyOf(result);
	}

	private Map<Long,List<MemoSupplyRegistry.Supply>> reconstructSupplies(int[] selected) {
		Map<SupplyEdge,MemoPartition> targets = new LinkedHashMap<>();
		Map<SupplyEdge,Map<MemoPartition,MemoSupplyRegistry.Recipe>> recipesByEdge = new LinkedHashMap<>();
		for(int useIndex=0;useIndex<uses.size();useIndex++) {
			Use use=uses.get(useIndex);
			if(compatible(use,useIndex,selected) != Truth.YES) throw new IllegalStateException("Selected use lost legality");
			int owner = use.consumer().compiledOwnerId(); RuleMemo.Rule rule = memo.rule(owner,selected[owner]);
			for(int position = 0; position < use.bindings().size(); position++) {
				if(universe.inputRoles().get(owner).get(position) == InputRole.METADATA) continue;
				Hop input = use.consumer().hop().getInput(position);
				if(!input.getDataType().isMatrix() && !input.getDataType().isFrame()) continue;
				Set<MemoPartition> layouts = new LinkedHashSet<>();
				for(List<Integer> frame : use.frames()) {
					Context context = context(selected,use.consumer().regionId(),frame);
					visitActive(use.consumer(),List.of(use.bindings().get(position)),context,environment -> {
						layouts.add(layout(environment.origins().get(0),selected)); return true;
					});
				}
				MemoPartition target = rule.inputs().get(position);
				if(layouts.isEmpty()) continue; // the consumer follows an always-throwing function call
				SupplyEdge edge=new SupplyEdge(use.consumer().statementBlockId(),input.getHopID(),
					use.consumer().hop().getHopID(),position);
				MemoPartition priorTarget=targets.putIfAbsent(edge,target);
				if(priorTarget!=null && priorTarget!=target)
					throw new IllegalStateException("Selected physical supply has conflicting targets at "+edge
						+": "+priorTarget+" and "+target);
				Map<MemoPartition,MemoSupplyRegistry.Recipe> recipes=recipesByEdge.computeIfAbsent(edge,
					ignored -> new LinkedHashMap<>());
				for(MemoPartition source : layouts) {
					MemoMovement.Kind movement=MemoMovement.kind(source,target,false);
					if(movement==MemoMovement.Kind.IMPOSSIBLE)
						throw new IllegalStateException("Selected physical supply became impossible at "+edge
							+": "+source+" -> "+target);
					MemoSupplyRegistry.Recipe recipe=new MemoSupplyRegistry.Recipe(source,
						MemoSupplyRegistry.Movement.valueOf(movement.name()),
						source.federated() || target.federated() ? sources.pool().encode() : null);
					MemoSupplyRegistry.Recipe prior=recipes.putIfAbsent(source,recipe);
					if(prior!=null && !prior.equals(recipe))
						throw new IllegalStateException("Selected physical supply has conflicting recipes at "+edge
							+" for "+source+": "+prior+" and "+recipe);
				}
			}
		}
		Map<Long,List<MemoSupplyRegistry.Supply>> byBlock = new LinkedHashMap<>();
		for(var entry : recipesByEdge.entrySet()) {
			SupplyEdge edge=entry.getKey(); MemoPartition target=targets.get(edge);
			Map<MemoPartition,MemoSupplyRegistry.Recipe> recipes=entry.getValue();
			// Omit a DIRECT-only edge only after every occurrence has contributed its exact source layout.
			if(recipes.size()==1 && recipes.containsKey(target)) continue;
			byBlock.computeIfAbsent(edge.statementBlockId(),ignored -> new ArrayList<>()).add(
				new MemoSupplyRegistry.Supply(edge.producerHopId(),edge.consumerHopId(),edge.inputPosition(),
					target,List.copyOf(recipes.values())));
		}
		byBlock.replaceAll((block,supplies) -> List.copyOf(supplies));
		return byBlock;
	}

	/** Reconstruct the exact retained actions costed by {@link #movementGroupCost}. */
	public MemoPlanRegistry.SupplySchedule supplySchedule(long epoch,Solution solution) {
		Map<SupplyEdge,MemoSupplyRegistry.Supply> selectedSupplies=new LinkedHashMap<>();
		for(var block : solution.supplies().entrySet()) for(var supply : block.getValue()) {
			SupplyEdge edge=new SupplyEdge(block.getKey(),supply.producerHopId(),supply.consumerHopId(),
				supply.inputPosition());
			if(selectedSupplies.putIfAbsent(edge,supply)!=null)
				throw new IllegalStateException("Duplicate selected supply edge "+edge);
		}
		List<ScheduledDemand> demands=new ArrayList<>();
		int[] selected=solution.optimization().selectedRules();
		for(Use use : uses) for(int position=0;position<use.bindings().size();position++) {
			final int inputPosition=position;
			Hop input=use.consumer().hop().getInput(position);
			SupplyEdge edge=new SupplyEdge(use.consumer().statementBlockId(),input.getHopID(),
				use.consumer().hop().getHopID(),position);
			MemoSupplyRegistry.Supply supply=selectedSupplies.get(edge);
			if(supply==null) continue;
			for(List<Integer> frame : use.frames()) {
				Context context=context(selected,use.consumer().regionId(),frame);
				var visit=visitActive(use.consumer(),List.of(use.bindings().get(position)),context,environment -> {
					ResolvedOrigin origin=environment.origins().get(0);
					MemoPartition source=layout(origin,selected);
					if(source==null) throw new IllegalStateException("Unresolved source in selected supply schedule");
					MemoMovement.Kind movement=MemoMovement.kind(source,supply.targetPartition(),false);
					if(movement==MemoMovement.Kind.DIRECT) return true;
					if(movement==MemoMovement.Kind.IMPOSSIBLE)
						throw new IllegalStateException("Selected supply became impossible for "+edge);
					MemoBindingResolver.OriginIdentity identity=resolver.resolveOriginIdentity(
						use.bindings().get(inputPosition),context.evaluation(),environment);
					RetentionScope scope=retentionScope(identity);
					if(scope==null) return true; // paid per demand; no runtime retained action
					MemoSupplyRegistry.Recipe recipe=supply.recipes().stream()
						.filter(candidate -> candidate.sourcePartition()==source
							&& candidate.movement()==MemoSupplyRegistry.Movement.valueOf(movement.name()))
						.findFirst().orElseThrow(() -> new IllegalStateException(
							"Selected supply has no recipe for exact source "+source+" at "+edge));
					demands.add(new ScheduledDemand(scope,identity,edge,recipe,supply.targetPartition()));
					return true;
				});
				if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED)
					throw new IllegalStateException("UNRESOLVED_SUPPLY_SCHEDULE: occurrence "+use.consumer().id());
			}
		}

		Map<RetentionScope,Long> retentionRegions=new LinkedHashMap<>();
		long nextRegion=facts.regions().stream().mapToLong(MemoProgramFacts.Region::id).max().orElse(-1)+1;
		for(ScheduledDemand demand : demands)
			if(!retentionRegions.containsKey(demand.scope())) retentionRegions.put(demand.scope(),nextRegion++);
		Map<RetentionScope,Set<Action>> actions=new LinkedHashMap<>();
		Set<MemoPlanRegistry.DefinitionSite> definitions = new LinkedHashSet<>();
		Set<MemoPlanRegistry.ReturnDefinitionSite> returns=new LinkedHashSet<>();
		Set<MemoPlanRegistry.ActionEdge> edges = new LinkedHashSet<>();
		for(ScheduledDemand demand : demands) {
			long region=retentionRegions.get(demand.scope());
			Action action=new Action(epoch,demand.identity().visibleBindingId(),demand.target(),demand.recipe(),
				region,"canonical/v2");
			actions.computeIfAbsent(demand.scope(),ignored -> new LinkedHashSet<>()).add(action);
			Occurrence nativeDefinition=facts.occurrence(demand.identity().nativeDefinitionOccurrenceId());
			long nativeOrigin=demand.identity().boundarySlot()>=0
				? demand.identity().visibleBindingId() : facts.valueBinding(nativeDefinition.id());
			Hop definitionHop=nativeOutputHop(demand.identity(),nativeDefinition.hop());
			definitions.add(new MemoPlanRegistry.DefinitionSite(nativeDefinition.statementBlockId(),
				definitionHop.getHopID(),nativeOrigin));
			for(var export : demand.identity().exportChain()) {
				var call=facts.call(export.callId());
				if(call.callOccurrenceId()<0)
					throw new IllegalStateException("Retained export has no exact call occurrence: "+export.callId());
				Occurrence callOccurrence=facts.occurrence(call.callOccurrenceId());
				returns.add(new MemoPlanRegistry.ReturnDefinitionSite(callOccurrence.statementBlockId(),
					callOccurrence.hop().getHopID(),export.outputSlot(),export.sourceBindingId(),export.targetBindingId()));
			}
			edges.add(new MemoPlanRegistry.ActionEdge(demand.edge().statementBlockId(),
				demand.edge().producerHopId(),demand.edge().consumerHopId(),demand.edge().inputPosition(),action));
		}
		List<MemoPlanRegistry.ScopePlan> scopes = new ArrayList<>();
		for(var entry : actions.entrySet()) scopes.add(new MemoPlanRegistry.ScopePlan(entry.getKey().kind(),
			entry.getKey().statementBlockId(),retentionRegions.get(entry.getKey()),List.copyOf(entry.getValue())));
		return new MemoPlanRegistry.SupplySchedule(scopes,List.copyOf(definitions),List.copyOf(edges),List.copyOf(returns));
	}


	/** All runtime residencies of one transparent value; no alias creates a new layout. */
	public Set<MemoPartition> selectedLayouts(int owner,int[] selected) {
		if(!memo.rules(owner).isEmpty()) return Set.copyOf(memo.rule(owner,selected[owner]).outputs());
		Set<MemoPartition> layouts = new LinkedHashSet<>();
		for(Occurrence occurrence : occurrencesByOwner.get(owner)) {
			if(facts.occurrenceContexts(occurrence.id()).isEmpty()) continue;
			for(List<Integer> frame : framesFor(occurrence.regionId())) {
				Context context = context(selected,occurrence.regionId(),frame);
				var visit = visitActive(occurrence,List.of(facts.valueBinding(occurrence.id())),context,environment -> {
					MemoPartition partition = layout(environment.origins().get(0),selected);
					if(partition != null) layouts.add(partition);
					return true;
				});
				if(visit.status() == MemoBindingResolver.VisitStatus.UNRESOLVED)
					throw new IllegalStateException("Unresolved selected alias owner " + owner);
			}
		}
		return Set.copyOf(layouts);
	}

	/** Resolve normal continuation before payloads: an existing function-return error supplies no value. */
	private MemoBindingResolver.VisitResult visitActive(Occurrence occurrence,List<Integer> bindings,Context context,
		MemoBindingResolver.EnvironmentVisitor visitor) {
		if(!context.reachable()) return unreachableGuardConjunction();
		return reachableContexts.visitBindings(occurrence,context.evaluation(),bindings,visitor);
	}
	@FunctionalInterface private interface ContextVisitor {
		MemoBindingResolver.VisitResult visit(Context context);
	}
	private MemoBindingResolver.VisitResult visitPhases(List<PhaseLoop> loops,int index,Context context,
		ContextVisitor visitor) {
		if(index==loops.size()) return visitor.visit(context);
		PhaseLoop fact=loops.get(index);
		LoopDecision loop=new LoopDecision(fact.loopId(),fact.callFrame());
		LoopCardinalityDecision cardinalityDecision=new LoopCardinalityDecision(fact.loopId(),fact.callFrame());
		if(context.evaluation().iterationPhases().containsKey(loop)
			&& context.evaluation().cardinalities().containsKey(cardinalityDecision))
			return visitPhases(loops,index+1,context,visitor);
		int count=0; boolean unknown=false;
		for(LoopExecutionCase executionCase : fact.executionCases()) {
			LoopCardinality fixedCardinality=context.evaluation().cardinalities().get(cardinalityDecision);
			if(fixedCardinality!=null && fixedCardinality!=executionCase.cardinality()) continue;
			for(IterationPhase phase : executionCase.bodyPhases()) {
				IterationPhase fixedPhase=context.evaluation().iterationPhases().get(loop);
				if(fixedPhase!=null && fixedPhase!=phase) continue;
				Context next=withLoopCase(context,fact.loopId(),fact.callFrame(),executionCase.cardinality(),phase);
				var result=visitPhases(loops,index+1,next,visitor); count+=result.environmentCount();
				if(result.status()==MemoBindingResolver.VisitStatus.STOPPED) return result;
				unknown |= result.status()==MemoBindingResolver.VisitStatus.UNRESOLVED;
			}
		}
		return new MemoBindingResolver.VisitResult(unknown ? MemoBindingResolver.VisitStatus.UNRESOLVED
			: MemoBindingResolver.VisitStatus.COMPLETE,count,"reachable structured loop phases");
	}
	private List<double[]> nativeCostTerms(Occurrence occurrence,List<Integer> frame) {
		int owner=occurrence.compiledOwnerId();
		int[] unselected=new int[sizes.length]; Arrays.fill(unselected,-1);
		Context base=context(unselected,occurrence.regionId(),frame);
		// Native execution depends on control activation and native shape, never on
		// which exact producer realizes an input. Source-only branch products are
		// intentionally absent; every remaining projected context stays a separate
		// binary64 primitive and is summed exactly by the optimizer/audit.
		List<double[]> terms=new ArrayList<>();
		MutableCostProjectionStatistics statistics=costProjectionStatistics.computeIfAbsent(owner,
			ignored -> new MutableCostProjectionStatistics());
		var visit=visitActive(occurrence,List.of(),base,environment -> {
			statistics.activationEnvironments++;
			Context active=base.withEnvironment(environment);
			visitNativeCostContexts(occurrence,active,statistics,exact -> {
				ResolvedEnvironment projected=environment(exact.evaluation());
				double weight=frequency(occurrence.regionId(),frame,exact.evaluation())
					*environmentProbability(projected,base.evaluation());
				if(weight==0) return;
				double[] values=nativeCostVector(occurrence,exact.evaluation(),weight);
				statistics.projectedContexts++;
				statistics.primitiveVectors++;
				statistics.primitiveValues+=values.length;
				statistics.primitiveValueBytes+=(long)Double.BYTES*values.length;
				terms.add(values);
			});
			return true;
		});
		if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED)
			throw new IllegalStateException("UNRESOLVED_PROGRAM_ACTIVATION: occurrence "+occurrence.id());
		return terms;
	}
	private double[] nativeCostVector(Occurrence occurrence,EvaluationContext evaluation,double weight) {
		int owner=occurrence.compiledOwnerId();
		NativeVectorKey key=null;
		Hop template=shapes.view(owner);
		// The occurrence fixes the template DAG, literal selectors, rule ordinals,
		// workers and cost profile. For these regular families only the full ordered
		// contextual geometry and exact frequency vary between native primitives.
		boolean wideFamily=template instanceof org.apache.sysds.hops.NaryOp nary
			&& (nary.getOp()==org.apache.sysds.common.Types.OpOpN.PLUS
				|| nary.getOp()==org.apache.sysds.common.Types.OpOpN.MULT)
			|| template instanceof org.apache.sysds.hops.TernaryOp ternary
			&& (ternary.getOp()==org.apache.sysds.common.Types.OpOp3.IFELSE
				|| ternary.getOp()==org.apache.sysds.common.Types.OpOp3.PLUS_MULT
				|| ternary.getOp()==org.apache.sysds.common.Types.OpOp3.MINUS_MULT);
		// Repeated direct identities collapse to their last effective shape, while an
		// intervening child can retain a memory estimate from an earlier raw write.
		boolean distinctWideInputs=true;
		if(wideFamily) for(int i=0;i<template.getInput().size() && distinctWideInputs;i++)
			for(int j=0;j<i;j++) if(template.getInput().get(i)==template.getInput().get(j)) {
				distinctWideInputs=false; break;
			}
		if(nativeVectorCacheEntries>0 && (template.getInput().size()<=2
			&& (template instanceof org.apache.sysds.hops.UnaryOp || template instanceof org.apache.sysds.hops.BinaryOp
			|| template instanceof org.apache.sysds.hops.AggUnaryOp || template instanceof org.apache.sysds.hops.AggBinaryOp
			|| template.getInput().size()==1
				&& template instanceof org.apache.sysds.hops.ReorgOp reorg
				&& reorg.getOp()==org.apache.sysds.common.Types.ReOrgOp.TRANS)
			|| wideFamily && distinctWideInputs)) {
			var descriptor=shapes.nativeDescriptorAt(occurrence.id(),evaluation);
			key=new NativeVectorKey(occurrence.id(),descriptor.output(),descriptor.inputs(),
				descriptor.blocksize(),Double.doubleToLongBits(weight));
			nativeVectorRequests++;
			double[] known=nativeVectorCache.get(key);
			if(known!=null) { nativeVectorHits++; return known.clone(); }
		}
		double[] values=new double[sizes[owner]];
		Hop view=shapes.viewAt(occurrence.id(),evaluation);
		for(int rule=0;rule<values.length;rule++) values[rule]=MemoCostModel.nativeCost(view,
			memo.rule(owner,rule),Math.max(1,sources.workers()),weight,owner,shapes.transformProof(owner));
		nativeVectorBuilds++;
		if(key!=null) nativeVectorCache.put(key,values.clone());
		return values;
	}

	@FunctionalInterface private interface NativeContextVisitor { void visit(Context context); }
	private void visitNativeCostContexts(Occurrence occurrence,Context context,
		MutableCostProjectionStatistics statistics,NativeContextVisitor visitor) {
		statistics.supportPrefixChecks++;
		MemoShapes.CostGuardSearch search=shapes.firstUnfixedCostGuard(occurrence.id(),context.evaluation());
		statistics.bindingDagVisits+=search.bindingVisits();
		statistics.projectionCacheHits+=search.cacheHits();
		statistics.noRegionsSkipped+=search.noRegionsSkipped();
		statistics.invariantProjectionStops+=search.invariantProjectionStops();
		if(search.unresolved()) {
			statistics.unknownObligations++;
			throw new IllegalStateException("UNRESOLVED_COST_CONTEXT: occurrence "+occurrence.id()
				+" frame="+context.evaluation().callFrame()+" phases="+context.evaluation().iterationPhases()
				+" cardinalities="+context.evaluation().cardinalities()+" guards="+context.evaluation().guards());
		}
		GuardDecision guard=search.guard();
		if(guard==null) { visitor.visit(context); return; }
		statistics.relevantGuardDecisions++;
		visitNativeCostContexts(occurrence,withGuard(context,guard,false),statistics,visitor);
		visitNativeCostContexts(occurrence,withGuard(context,guard,true),statistics,visitor);
	}
	private void visitBindingCostContexts(int binding,Context context,NativeContextVisitor visitor) {
		MemoShapes.CostGuardSearch search=shapes.firstUnfixedCostGuardForBinding(binding,context.evaluation());
		if(search.unresolved())
			throw new IllegalStateException("UNRESOLVED_COST_CONTEXT: binding "+binding);
		GuardDecision guard=search.guard();
		if(guard==null) { visitor.visit(context); return; }
		visitBindingCostContexts(binding,withGuard(context,guard,false),visitor);
		visitBindingCostContexts(binding,withGuard(context,guard,true),visitor);
	}
	private Context withGuard(Context context,GuardDecision guard,boolean value) {
		EvaluationContext e=context.evaluation();
		Map<GuardDecision,Boolean> guards=new LinkedHashMap<>(e.guards()); guards.put(guard,value);
		return context.withEvaluation(new EvaluationContext(guards,e.loops(),e.selections(),e.maxDepth(),
			e.callFrame(),e.iterationPhases(),e.cardinalities()));
	}
	private static ResolvedEnvironment environment(EvaluationContext evaluation) {
		return new ResolvedEnvironment(List.of(),evaluation.guards(),evaluation.loops(),
			evaluation.iterationPhases(),evaluation.cardinalities());
	}
	private MemoBindingResolver.VisitResult visitActive(int activation,List<Integer> bindings,Context context,
		MemoBindingResolver.EnvironmentVisitor visitor) {
		if(!context.reachable()) return unreachableGuardConjunction();
		List<Integer> frame = context.evaluation().callFrame();
		// Caller prerequisites precede every instruction in its callee. Resolve each in its own frame.
		return visitCallerPrerequisites(activation,bindings,context,visitor,frame,0);
	}
	private static MemoBindingResolver.VisitResult unreachableGuardConjunction() {
		return new MemoBindingResolver.VisitResult(MemoBindingResolver.VisitStatus.COMPLETE,0,
			"unreachable correlated guard conjunction");
	}
	private MemoBindingResolver.VisitResult visitCallerPrerequisites(int activation,List<Integer> bindings,
		Context context,MemoBindingResolver.EnvironmentVisitor visitor,List<Integer> frame,int depth) {
		if(depth < frame.size()) {
			var call = facts.call(frame.get(depth));
			if(call.callOccurrenceId() >= 0) {
				Occurrence caller = facts.occurrence(call.callOccurrenceId());
				Context callerContext = context.withCallFrame(frame.subList(0,depth));
				return visitAfterActivation(caller.activationBindingId(),callerContext,environment ->
					visitCallerPrerequisites(activation,bindings,context.withEnvironment(environment),visitor,
						frame,depth+1));
			}
			return visitCallerPrerequisites(activation,bindings,context,visitor,frame,depth+1);
		}
		return visitAfterActivation(activation,context,environment ->
			resolver.visitJoint(bindings,context.withEnvironment(environment).evaluation(),visitor));
	}
	@FunctionalInterface private interface ActivatedVisitor {
		MemoBindingResolver.VisitResult visit(ResolvedEnvironment environment);
	}
	private MemoBindingResolver.VisitResult visitAfterActivation(int activation,Context context,ActivatedVisitor visitor) {
		if(activation < 0) return visitor.visit(new ResolvedEnvironment(List.of(),context.evaluation().guards(),
			context.evaluation().loops(),context.evaluation().iterationPhases(),context.evaluation().cardinalities()));
		if(resolver.alwaysAvailable(activation,context.evaluation().callFrame()))
			return visitor.visit(new ResolvedEnvironment(List.of(),context.evaluation().guards(),
				context.evaluation().loops(),context.evaluation().iterationPhases(),context.evaluation().cardinalities()));
		boolean[] unresolved = {false}, stopped = {false}; int[] count = {0};
		var outer = resolver.visitJoint(List.of(activation),context.evaluation(),environment -> {
			OriginKind kind = environment.origins().get(0).kind();
			if(kind == OriginKind.TERMINATED_AT_RETURN) return true;
			if(kind != OriginKind.NORMAL_CONTINUATION) { unresolved[0] = true; return true; }
			var result = visitor.visit(environment); count[0] += result.environmentCount();
			unresolved[0] |= result.status() == MemoBindingResolver.VisitStatus.UNRESOLVED;
			stopped[0] |= result.status() == MemoBindingResolver.VisitStatus.STOPPED;
			return !stopped[0];
		});
		unresolved[0] |= outer.status() == MemoBindingResolver.VisitStatus.UNRESOLVED;
		return new MemoBindingResolver.VisitResult(stopped[0] ? MemoBindingResolver.VisitStatus.STOPPED
			: unresolved[0] ? MemoBindingResolver.VisitStatus.UNRESOLVED : MemoBindingResolver.VisitStatus.COMPLETE,
			count[0],"normal-continuation activation");
	}
	private double normalProbability(Occurrence occurrence,List<Integer> frame) {
		int[] unselected = new int[sizes.length]; Arrays.fill(unselected,-1);
		Context context = context(unselected,occurrence.regionId(),frame);
		double[] probability = {0};
		var result = visitActive(occurrence,List.of(),context,environment -> {
			probability[0] += environmentProbability(environment,context.evaluation()); return true;
		});
		if(result.status() == MemoBindingResolver.VisitStatus.UNRESOLVED)
			throw new IllegalStateException("UNRESOLVED_PROGRAM_ACTIVATION: occurrence " + occurrence.id());
		return probability[0];
	}

	public Map<StatementBlock,List<MemoLoopRuntime.Contract>> loopContracts(int[] selected) {
		Map<StatementBlock,List<MemoLoopRuntime.Contract>> result=new java.util.IdentityHashMap<>();
		for(var loop : facts.loops()) if(!framesFor(loop.regionId()).isEmpty())
			result.put(facts.statementBlock(loop.statementBlockId()),new ArrayList<>());
		for(Head head : heads) {
			var loop=facts.loop(head.slot().loopId());
			List<List<Integer>> contexts=framesFor(loop.regionId());
			if(contexts.isEmpty()) continue;
			MemoPartition target=headLayout(head,selected);
			Set<MemoPartition> layouts=new LinkedHashSet<>();
			List<MemoShapes.Shape> entries=new ArrayList<>(), repeats=new ArrayList<>();
			boolean[] iterator={false}; boolean required=false;
			for(List<Integer> frame : contexts) {
				LoopExecutionCase bodyCase=firstBodyCase(loop.id());
				Context base=withCardinality(context(selected,loop.regionId(),frame),loop.id(),frame,
					bodyCase.cardinality());
				Context first=withPhase(base,loop.id(),frame,IterationPhase.FIRST);
				repeats.add(repeatShape(head,base,frame,IterationPhase.FIRST));
				for(IterationPhase phase : takenBackedgePhases(loop.id())) {
					Context repeatContext=withLoopCase(base,loop.id(),frame,LoopCardinality.MANY,phase);
					repeats.add(shapes.bindingShape(head.value().backedgeBindingId(),repeatContext.evaluation()));
					if(target==MemoPartition.LOCAL) {
						var visit=visitActive(loop.backedgeActivationBindingId(),
							List.of(head.value().backedgeBindingId()),repeatContext,environment -> {
							var residency=privacy.localResidencyAt(head.value().backedgeBindingId(),privacyContext(
								repeatContext.withEnvironment(environment),List.of(head.value().backedgeBindingId())));
							if(residency.legality()!=MemoPrivacy.Legality.LEGAL)
								throw new IllegalStateException("Selected LOCAL loop residency is not proven: "
									+head.value().variable()+" "+residency.reason());
							return true;
						});
						if(visit.status()==MemoBindingResolver.VisitStatus.UNRESOLVED)
							throw new IllegalStateException("Selected LOCAL loop residency is unresolved: "
								+head.value().variable());
					}
				}
				if(!entryRequired(head,frame)) continue;
				required=true;
				entries.add(shapes.bindingShape(head.value().entryBindingId(),first.evaluation()));
				visitActive(loop.activationBindingId(),List.of(head.value().entryBindingId()),first,environment -> {
					ResolvedOrigin origin=environment.origins().get(0);
					if(origin.kind()==OriginKind.ITERATOR_LOCAL) { iterator[0]=true; return true; }
					if(target==MemoPartition.LOCAL) {
						var residency=privacy.localResidencyAt(head.value().entryBindingId(),privacyContext(
							first.withEnvironment(environment),List.of(head.value().entryBindingId())));
						if(residency.legality()!=MemoPrivacy.Legality.LEGAL)
							throw new IllegalStateException("Selected LOCAL loop entry residency is not proven: "
								+head.value().variable()+" "+residency.reason());
					}
					layouts.add(layout(origin,selected)); return true;
				});
			}
			if(iterator[0]) continue;
			if(required && layouts.isEmpty()) continue;
			MemoShapes.Shape repeat=contractPhysicalShape(repeats);
			MemoShapes.Shape entry=required ? contractPhysicalShape(entries) : repeat;
			if(repeat==null || entry==null || layouts.contains(null))
				throw new IllegalStateException("Selected loop contract has unresolved phase shape/source: "
					+head.value().variable()+" entries="+entries+" repeats="+repeats+" layouts="+layouts);
			if(!loopBoundaryTypeCompatible(required,entry,repeat))
				throw new IllegalStateException("Selected loop contract has an unsupported entry/repeat type transition: "
					+head.value().variable()+" "+entry.valueType()+" -> "+repeat.valueType());
			boolean entryTypeTransition=entry.valueType()!=repeat.valueType();
			if(entryTypeTransition && (target!=MemoPartition.LOCAL
				|| layouts.stream().anyMatch(source -> source!=MemoPartition.LOCAL
					|| MemoMovement.kind(source,target,false)!=MemoMovement.Kind.DIRECT)))
				throw new IllegalStateException("Selected scalar type transition is not LOCAL/DIRECT: "
					+head.value().variable());
			List<MemoSupplyRegistry.Recipe> recipes=new ArrayList<>();
			for(MemoPartition source : layouts) recipes.add(new MemoSupplyRegistry.Recipe(source,
				MemoSupplyRegistry.Movement.valueOf(MemoMovement.kind(source,target,false).name()),
				source.federated() || target.federated() ? sources.pool().encode() : null));
			boolean scalar=repeat.dataType().isScalar();
			MemoLoopRuntime.Contract contract=new MemoLoopRuntime.Contract(head.value().variable(),repeat.dataType(),
				entry.valueType(),repeat.valueType(),target,scalar ? -1 : entry.rows(),scalar ? -1 : entry.columns(),
				scalar ? -1 : repeat.rows(),scalar ? -1 : repeat.columns(),required,
				true,recipes,sources.pool());
			result.computeIfAbsent(facts.statementBlock(loop.statementBlockId()),ignored -> new ArrayList<>()).add(contract);
		}
		result.replaceAll((block,contracts) -> List.copyOf(contracts));
		return java.util.Collections.unmodifiableMap(result);
	}

	static MemoShapes.Shape contractPhysicalShape(List<MemoShapes.Shape> candidates) {
		MemoShapes.Shape result=null;
		for(MemoShapes.Shape candidate:candidates) {
			if(candidate==null || !candidate.exact()) return null;
			if(result==null) result=candidate;
			else if(!samePhysicalShape(result,candidate)) return null;
		}
		return result;
	}

	private int[] scope(List<Integer> bindings,List<Integer> required,List<List<Integer>> frames) {
		Set<Integer> scope = new LinkedHashSet<>(required);
		for(List<Integer> frame : frames) {
			scope.addAll(resolver.directSupplyOwners(bindings,frame));
			for(LoopSlot slot : resolver.directLoopHeads(bindings,frame)) {
				Head head = head(slot.loopId(),slot.slot());
				if(head != null) scope.add(head.variable());
			}
		}
		return scope.stream().sorted().mapToInt(Integer::intValue).toArray();
	}
	private int[] acquireSelection(int[] scope,int[] choices) {
		return selectionScratch.acquire(scope,choices);
	}
	private void releaseSelection(int[] scope,int[] selection) {
		selectionScratch.release(scope,selection);
	}
	private Truth inputCompatibleExpanded(Use use,MovementEdge edge,int[] scope,int[] choices) {
		int[] selected=acquireSelection(scope,choices);
		try { return inputCompatible(use,edge,selected); }
		finally { releaseSelection(scope,selected); }
	}
	private Truth boundaryCompatibleExpanded(Head head,int[] scope,int[] choices) {
		int[] selected=acquireSelection(scope,choices);
		try { return boundaryCompatible(head,selected); }
		finally { releaseSelection(scope,selected); }
	}
	private double movementGroupCostExpanded(MovementGroup group,List<MovementEdge> edges,
		int[] scope,int[] choices) {
		int[] selected=acquireSelection(scope,choices);
		try { return movementGroupCost(group,edges,selected); }
		finally { releaseSelection(scope,selected); }
	}
	private double entryCostExpanded(Head head,int[] scope,int[] choices) {
		int[] selected=acquireSelection(scope,choices);
		try { return entryCost(head,selected); }
		finally { releaseSelection(scope,selected); }
	}
	private Context context(int[] selected,int region,List<Integer> frame) {
		contextAnalysisRequests++;
		ContextAnalysisKey key=new ContextAnalysisKey(region,frame);
		ContextAnalysis analysis=contextAnalysisReuse ? contextAnalysisCache.get(key) : null;
		if(analysis!=null) contextAnalysisHits++;
		else {
			analysis=analyzeContext(region,key.frame());
			if(contextAnalysisReuse) contextAnalysisCache.put(key,analysis);
		}
		MemoBindingResolver.SelectionLookup lookup = new MemoBindingResolver.SelectionLookup() {
			@Override public int selectedRule(int owner) { return selected[owner]; }
			@Override public MemoPartition selectedLayout(int owner) {
				return selected[owner] < 0 || memo.rules(owner).isEmpty() ? null : memo.rule(owner,selected[owner]).output();
			}
			@Override public boolean headIsBound(int loop,int slot) { return head(loop,slot)!=null; }
			@Override public MemoPartition selectedHeadLayout(int loop,int slot) {
				Head head = head(loop,slot); return head == null ? null : headLayout(head,selected);
			}
		};
		EvaluationContext evaluation = new EvaluationContext(analysis.guards(),Map.of(),lookup,4096,key.frame());
		return new Context(evaluation,Map.of(),analysis.reachable());
	}
	private ContextAnalysis analyzeContext(int region,List<Integer> frame) {
		Map<GuardDecision,Boolean> guards = new LinkedHashMap<>();
		boolean reachable=true;
		for(int r = region; r >= 0; r = facts.regions().get(r).parentId()) {
			var current = facts.regions().get(r);
			if(current.kind() == RegionKind.IF_THEN || current.kind() == RegionKind.IF_ELSE) {
				boolean branch=current.kind()==RegionKind.IF_THEN;
				reachable &= putGuard(guards,facts.guardDecision(current.guardId(),frame),
					facts.canonicalGuardValue(current.guardId(),frame,branch));
			}
		}
		for(int index = 0; index < frame.size(); index++) {
			var call = facts.call(frame.get(index));
			if(call.callOccurrenceId() < 0) continue;
			for(int r = facts.occurrence(call.callOccurrenceId()).regionId(); r >= 0;
				r = facts.regions().get(r).parentId()) {
				var current = facts.regions().get(r);
				if(current.kind() == RegionKind.IF_THEN || current.kind() == RegionKind.IF_ELSE) {
					List<Integer> callerFrame=frame.subList(0,index);
					boolean branch=current.kind()==RegionKind.IF_THEN;
					reachable &= putGuard(guards,facts.guardDecision(current.guardId(),callerFrame),
						facts.canonicalGuardValue(current.guardId(),callerFrame,branch));
				}
			}
		}
		return new ContextAnalysis(guards,reachable);
	}
	private static boolean putGuard(Map<GuardDecision,Boolean> guards,GuardDecision guard,boolean value) {
		Boolean prior=guards.putIfAbsent(guard,value);
		return prior==null || prior==value;
	}

	/** Attach only loop privacy contracts transitively required by an exact privacy fallback. */
	private Context privacyContext(Context context,List<Integer> bindings) {
		if(bindings.isEmpty() || bindings.stream().allMatch(privacy::provesReleasedBinding)) return context;
		Set<Integer> relevant=new LinkedHashSet<>();
		for(LoopSlot slot : resolver.relevantLoopHeads(bindings,context.evaluation().callFrame()))
			relevant.add(slot.loopId());
		if(relevant.isEmpty()) return context;
		var evaluation=context.evaluation();
		Context invariant=context.withEvaluation(new EvaluationContext(evaluation.guards(),evaluation.loops(),
			MemoBindingResolver.NO_SELECTION,evaluation.maxDepth(),evaluation.callFrame(),
			evaluation.iterationPhases(),evaluation.cardinalities()));
		List<Integer> requestedFrame=evaluation.callFrame();
		for(var loop : facts.loops()) if(relevant.contains(loop.id())) {
			for(List<Integer> frame : facts.callFramesForRegion(loop.regionId())) {
				if(!prefix(requestedFrame,frame) && !prefix(frame,requestedFrame)) continue;
				Context invoked=invariant.withCallFrame(frame);
				Map<LoopSlot,MemoPrivacy.Result> summary=privacy.summarizeLoop(loop.id(),invoked);
				for(var entry : summary.entrySet()) if(entry.getValue().exact())
					invariant=invariant.withLoopHeadAt(frame,entry.getKey().loopId(),entry.getKey().slot(),
						entry.getValue().privacy());
			}
		}
		return invariant.withEvaluation(evaluation);
	}
	private static boolean prefix(List<Integer> prefix,List<Integer> value) {
		return prefix.size()<=value.size() && value.subList(0,prefix.size()).equals(prefix);
	}
	private MemoPartition layout(ResolvedOrigin origin,int[] selected) {
		if(origin.kind() == OriginKind.ITERATOR_LOCAL) return MemoPartition.LOCAL;
		if(origin.kind() == OriginKind.LOOP_HEAD_CONTRACT) {
			Head head = head(origin.referenceId(),origin.boundarySlot());
			return head == null ? null : headLayout(head,selected);
		}
		int owner = origin.compiledOwnerId();
		if(owner < 0 || selected[owner] < 0 || memo.rules(owner).isEmpty()) return null;
		return selectedOutput(memo.rule(owner,selected[owner]),origin.boundarySlot());
	}
	private static MemoPartition selectedOutput(RuleMemo.Rule rule,int slot) {
		if(slot<0) return rule.output();
		return slot<rule.outputs().size() ? rule.outputs().get(slot) : null;
	}
	private Hop nativeOutputHop(ResolvedOrigin origin,Hop fallback) {
		return origin.kind()==OriginKind.COMPILED_OWNER
			? nativeOutputHop(origin.referenceId(),origin.boundarySlot(),fallback) : fallback;
	}
	private Hop nativeOutputHop(MemoBindingResolver.OriginIdentity identity,Hop fallback) {
		return identity.kind()==OriginKind.COMPILED_OWNER
			? nativeOutputHop(identity.nativeDefinitionOccurrenceId(),identity.boundarySlot(),fallback) : fallback;
	}
	private Hop nativeOutputHop(int occurrence,int slot,Hop fallback) {
		if(slot<0 || occurrence<0 || occurrence>=facts.occurrences().size()) return fallback;
		Hop hop=facts.occurrence(occurrence).hop();
		if(hop instanceof org.apache.sysds.hops.FunctionOp function
			&& function.getFunctionType()==org.apache.sysds.hops.FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			&& function.getOutputs()!=null && slot<function.getOutputs().size()
			&& function.getOutputs().get(slot)!=null) return function.getOutputs().get(slot);
		return fallback;
	}
	private Head head(int loop,int slot) {
		if(loop<0 || loop>=headsByLoop.length || slot<0) return null;
		Head[] values=headsByLoop[loop];
		return values!=null && slot<values.length ? values[slot] : null;
	}
	private MemoPartition headLayout(Head head,int[] selected) {
		int value = selected[head.variable()];
		return value < 0 ? null : sources.workers() == 1 && value == 1 ? MemoPartition.REP : MemoPartition.values()[value];
	}
	private long dimension(ResolvedOrigin origin,Hop fallback,boolean rows) {
		MemoSources.Source source = sources.source(origin.compiledOwnerId());
		if(source != null) return rows ? source.rows() : source.columns();
		if(origin.kind() == OriginKind.LOOP_HEAD_CONTRACT) {
			Head head = head(origin.referenceId(),origin.boundarySlot());
			var shape = shapes.bindingShape(head.value().headBindingId());
			return rows ? shape.rows() : shape.columns();
		}
		Hop hop = origin.kind() == OriginKind.COMPILED_OWNER
			? nativeOutputHop(origin,shapes.viewAt(origin.referenceId())) : fallback;
		return rows ? hop.getDim1() : hop.getDim2();
	}
	private List<List<Integer>> framesFor(int region) {
		return facts.callFramesForRegion(region);
	}
	private List<List<Integer>> framesForOccurrence(Occurrence occurrence) {
		return facts.occurrenceContexts(occurrence.id()).stream()
			.map(MemoProgramFacts.OccurrenceContext::callFrame).distinct().toList();
	}
	private double frequency(int region,List<Integer> frame) {
		return frequency(region,frame,new LinkedHashMap<>());
	}
	private double frequency(int region,List<Integer> frame,Map<GuardDecision,Boolean> chargedGuards) {
		if(region < 0) return 1;
		var f = facts.executionFrequency(region);
		double own = switch(f.kind()) {
			case ONCE -> 1;
			case GUARDED_THEN, GUARDED_ELSE -> {
				int guard=facts.region(region).guardId();
				boolean branch=f.kind()==MemoProgramFacts.FrequencyKind.GUARDED_THEN;
				GuardDecision decision=facts.guardDecision(guard,frame);
				boolean required=facts.canonicalGuardValue(guard,frame,branch);
				Boolean prior=chargedGuards.putIfAbsent(decision,required);
				yield prior!=null ? prior==required ? 1 : 0
					: required ? profile.branchThenProbability() : 1-profile.branchThenProbability();
			}
			case LOOP_ITERATIONS -> f.multiplier() < 0 ? profile.unknownLoopIterations() : f.multiplier();
			case FUNCTION_INVOCATIONS -> 1;
		};
		if(f.kind() == MemoProgramFacts.FrequencyKind.FUNCTION_INVOCATIONS && !frame.isEmpty()) {
			var call = facts.call(frame.get(frame.size()-1));
			return frequency(facts.occurrence(call.callOccurrenceId()).regionId(),frame.subList(0,frame.size()-1),
				chargedGuards);
		}
		return own*frequency(f.parentRegionId(),frame,chargedGuards);
	}
	private double frequency(int region,List<Integer> frame,EvaluationContext evaluation) {
		return frequency(region,frame,evaluation,new LinkedHashMap<>());
	}
	private double frequency(int region,List<Integer> frame,EvaluationContext evaluation,
		Map<GuardDecision,Boolean> chargedGuards) {
		if(region<0) return 1;
		var f=facts.executionFrequency(region);
		double own=switch(f.kind()) {
			case ONCE,FUNCTION_INVOCATIONS -> 1;
			case GUARDED_THEN,GUARDED_ELSE -> {
				int guard=facts.region(region).guardId();
				Boolean proven=phaseGuard(guard,frame,evaluation.iterationPhases());
				boolean then=f.kind()==MemoProgramFacts.FrequencyKind.GUARDED_THEN;
				if(proven!=null) yield proven==then ? 1 : 0;
				GuardDecision decision=facts.guardDecision(guard,frame);
				boolean required=facts.canonicalGuardValue(guard,frame,then);
				Boolean prior=chargedGuards.putIfAbsent(decision,required);
				yield prior!=null ? prior==required ? 1 : 0
					: required ? profile.branchThenProbability() : 1-profile.branchThenProbability();
			}
			case LOOP_ITERATIONS -> {
				LoopCardinality cardinality=evaluation.cardinalities().get(
					new LoopCardinalityDecision(f.referenceId(),frame));
				LoopExecutionCase executionCase=cardinality==null ? null : facts.loopExecutionCases(f.referenceId())
					.stream().filter(value -> value.cardinality()==cardinality).findFirst().orElse(null);
				double count=executionCase!=null && executionCase.exactIterations()>=0
					? executionCase.exactIterations() : f.multiplier()<0
						? profile.unknownLoopIterations() : f.multiplier();
				IterationPhase phase=evaluation.iterationPhases().get(new LoopDecision(f.referenceId(),frame));
				yield cardinality==LoopCardinality.ZERO ? 0
					: cardinality==LoopCardinality.ONE ? phase==null || phase==IterationPhase.FIRST ? 1 : 0
					: phase==null ? count : phaseIterations(count,phase);
			}
		};
		if(f.kind()==MemoProgramFacts.FrequencyKind.FUNCTION_INVOCATIONS && !frame.isEmpty()) {
			var call=facts.call(frame.get(frame.size()-1));
			return frequency(facts.occurrence(call.callOccurrenceId()).regionId(),frame.subList(0,frame.size()-1),
				evaluation,chargedGuards);
		}
		return own*frequency(f.parentRegionId(),frame,evaluation,chargedGuards);
	}
	static double phaseIterations(double count,IterationPhase phase) {
		if(!Double.isFinite(count) || count < 0 || phase == null)
			throw new IllegalArgumentException("Loop phase cost requires a finite nonnegative count and an exact phase");
		return switch(phase) {
			case FIRST -> count>=1 ? 1 : 0;
			case MIDDLE -> Math.max(0,count-2);
			case LAST -> count>=2 ? 1 : 0;
		};
	}
	private Boolean phaseGuard(int guard,List<Integer> frame,Map<LoopDecision,IterationPhase> phases) {
		if(guard<0) return null;
		Boolean result=null;
		for(var entry : phases.entrySet()) {
			if(!entry.getKey().callFrame().equals(frame)) continue;
			Boolean proof=facts.guardValue(entry.getKey().loopId(),guard,entry.getValue());
			if(proof==null) continue;
			if(result!=null && !result.equals(proof))
				throw new IllegalStateException("CONFLICTING_PHASE_GUARD_PROOF guard="+guard+" frame="+frame);
			result=proof;
		}
		return result;
	}
	private double environmentProbability(ResolvedEnvironment environment,EvaluationContext fixed) {
		double probability=cardinalityProbability(environment.cardinalities());
		for(var guard : environment.guards().entrySet()) if(!fixed.guards().containsKey(guard.getKey())
			&& phaseGuard(guard.getKey().guardId(),guard.getKey().callFrame(),environment.iterationPhases())==null)
			probability *= guard.getValue() ? profile.branchThenProbability() : 1-profile.branchThenProbability();
		return probability;
	}
	private double cardinalityProbability(
		Map<LoopCardinalityDecision,LoopCardinality> cardinalities) {
		double probability=1;
		for(var decision : cardinalities.entrySet()) {
			LoopExecutionCase executionCase=facts.loopExecutionCases(decision.getKey().loopId()).stream()
				.filter(value -> value.cardinality()==decision.getValue()).findFirst().orElseThrow();
			// Runtime-dependent counts retain the historical typical-many estimator. ZERO/ONE
			// remain mandatory feasibility cases, but carry no uncalibrated probability mass.
			if(executionCase.exactIterations()<0 && decision.getValue()!=LoopCardinality.MANY) probability=0;
		}
		return probability;
	}
}
