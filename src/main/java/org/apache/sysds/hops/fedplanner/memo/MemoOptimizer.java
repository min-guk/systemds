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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Lazy exact selection over the immutable native universe. Conditions are attached
 * to existing use/boundary sites; no source products or physical alternatives are built.
 * An owner is a single variable even when several occurrences or inputs use it.
 */
public final class MemoOptimizer {
	public enum Truth { YES, NO, UNKNOWN }
	public enum Status { OPTIMAL, INFEASIBLE, UNRESOLVED, RESOURCE_EXHAUSTED }
	@FunctionalInterface public interface Predicate {
		/** -1 means unassigned; YES/NO must hold for every completion of that prefix. */
		Truth test(int[] scopedRules);
	}
	public static final class Check {
		private final int site;
		private final int[] owners;
		private final Predicate predicate;
		public Check(int site, int[] owners, Predicate predicate) {
			this.site = site;
			this.owners = uniqueScope(owners);
			this.predicate = Objects.requireNonNull(predicate);
		}
		public int site() { return site; }
		public int[] owners() { return owners.clone(); }
	}
	/** A term has exactly one cost owner. A shared movement is one term with several demands.
	 * The evaluator is pure and finite for every complete scoped tuple, including
	 * infeasible tuples used in a relaxation; certifiedMinimum bounds all of them. */
	public static final class CostTerm {
		private final int[] owners;
		private final ToDoubleFunction<int[]> evaluator;
		private final BigDecimal certifiedMinimum;
		public CostTerm(int[] owners, ToDoubleFunction<int[]> evaluator, double certifiedMinimum) {
			this.owners = uniqueScope(owners);
			this.evaluator = Objects.requireNonNull(evaluator);
			this.certifiedMinimum = exact(certifiedMinimum);
		}
		public int[] owners() { return owners.clone(); }
		BigDecimal evaluateExact(int[] scopedRules) {
			return exact(evaluator.applyAsDouble(scopedRules.clone()));
		}
	}
	private static final class CostFactor {
		private final int[] owners;
		private final Function<int[],BigDecimal> evaluator;
		private final BigDecimal certifiedMinimum;
		private CostFactor(int[] owners,Function<int[],BigDecimal> evaluator,BigDecimal certifiedMinimum) {
			this.owners=uniqueScope(owners); this.evaluator=Objects.requireNonNull(evaluator);
			this.certifiedMinimum=Objects.requireNonNull(certifiedMinimum);
		}
		private static CostFactor from(CostTerm term) {
			return new CostFactor(term.owners,term::evaluateExact,
				term.certifiedMinimum);
		}
	}
	public record Limits(long searchNodes, int supportEvaluations, int cacheEntries) {
		public Limits {
			if(searchNodes < 1 || supportEvaluations < 1 || cacheEntries < 0)
				throw new IllegalArgumentException("Invalid search limits");
		}
		public static Limits exactDefaults() { return new Limits(Long.MAX_VALUE, 256, 4096); }
	}
	public record Statistics(long nodes, long checkEvaluations, long checkCacheHits,
		long supportQueries, long supportCacheHits, long worklistVisits, long removedRules, long costEvaluations,
		long costCacheHits, long boundPrunes, long conditionalBoundQueries, long conditionalBoundCacheHits,
		long wideBoundRelaxations,long forestTailAttempts,long forestTailApplicable,long forestTailCompletions,
		long forestTailCells) {}
	public record Result(Status status, BigDecimal cost, int[] selectedRules, Statistics statistics) {
		public Result { selectedRules = selectedRules == null ? null : selectedRules.clone(); }
		@Override public int[] selectedRules() { return selectedRules == null ? null : selectedRules.clone(); }
	}

	private final int[] domainSizes;
	private final int[] domainCardinalities;
	private final boolean[] coverage;
	private List<Check> checks;
	private List<CostFactor> terms;
	private final BigDecimal[][] nativeCosts;
	private final Limits limits;
	private int[][] incident;
	private int[][] costIncident;
	private int[][] branchAdjacent;
	private int[] activeBranchNeighbors;
	private final BitSet[] domains;
	private final long[] compactDomains;
	private final boolean[] wideDomains;
	private final long[] revisions;
	private final ArrayList<int[]> trail = new ArrayList<>();
	private final ArrayDeque<Integer> queue = new ArrayDeque<>();
	private final BitSet queued = new BitSet();
	private long[][] checkedRevisions;
	private final Map<TupleKey,Truth> truthCache;
	private final Map<TupleKey,BigDecimal> costCache;
	private final Map<ScopedDomainKey,BigDecimal> boundCache;
	private final Map<ScopedDomainKey,Truth> supportCache;
	private final TupleProbe lookupProbe = new TupleProbe();
	private final ScopedDomainProbe domainProbe = new ScopedDomainProbe();
	private final BigDecimal[] ownerBounds;
	private BigDecimal[] termBounds;
	private final BitSet dirtyOwnerBounds = new BitSet();
	private final BitSet dirtyTermBounds = new BitSet();
	private BigDecimal cachedLowerBound = BigDecimal.ZERO;
	private BigDecimal fixedCost = BigDecimal.ZERO;
	private boolean lowerBoundInitialized;
	private ReducedSearch activeReducedSearch;
	private BigDecimal incumbent;
	private int[] selected;
	private BigDecimal unresolvedLowerBound;
	private boolean exhausted;
	private boolean optimized;
	private long nodes, checkEvaluations, checkCacheHits, supportQueries, supportCacheHits, worklistVisits,
		removedRules, costEvaluations, costCacheHits, boundPrunes, conditionalBoundQueries,
		conditionalBoundCacheHits, wideBoundRelaxations,forestTailAttempts,forestTailApplicable,
		forestTailCompletions,forestTailCells;

	public MemoOptimizer(RuleMemo memo, double[][] nativeCosts, List<Check> checks,
		List<CostTerm> terms, Limits limits) {
		this(java.util.stream.IntStream.range(0,memo.ownerCount()).map(i -> memo.rules(i).size()).toArray(),
			coverage(memo),nativeCosts,checks,terms,limits);
	}

	/** Native owner variables plus explicit boundary-contract variables; rules remain in RuleMemo. */
	public MemoOptimizer(int[] domainSizes, boolean[] coverage, double[][] nativeCosts,
		List<Check> checks, List<CostTerm> terms, Limits limits) {
		this.domainSizes = domainSizes.clone();
		domainCardinalities = domainSizes.clone();
		this.coverage = coverage.clone();
		if(coverage.length != domainSizes.length) throw new IllegalArgumentException("Coverage/domain mismatch");
		this.checks = checks.stream().map(check -> {
			int[] reduced = nonConstantScope(check.owners,domainSizes);
			return reduced.length == check.owners.length ? check : new Check(check.site,reduced,
				values -> check.predicate.test(expandConstantScope(check.owners,domainSizes,values)));
		}).toList();
		this.terms = terms.stream().map(term -> {
			CostFactor factor=CostFactor.from(term);
			int[] reduced = nonConstantScope(term.owners,domainSizes);
			return reduced.length == term.owners.length ? factor : new CostFactor(reduced,
				values -> factor.evaluator.apply(expandConstantScope(term.owners,domainSizes,values)),
				factor.certifiedMinimum);
		}).toList();
		this.limits = Objects.requireNonNull(limits);
		if(nativeCosts.length != domainSizes.length)
			throw new IllegalArgumentException("Native costs must match owner count");
		this.nativeCosts = new BigDecimal[nativeCosts.length][];
		for(int owner = 0; owner < nativeCosts.length; owner++) {
			if(nativeCosts[owner].length != domainSizes[owner])
				throw new IllegalArgumentException("Native costs must match immutable rule domain");
			this.nativeCosts[owner] = Arrays.stream(nativeCosts[owner]).mapToObj(MemoOptimizer::exact)
				.toArray(BigDecimal[]::new);
		}
		domains = new BitSet[domainSizes.length];
		compactDomains = new long[domainSizes.length];
		wideDomains = new boolean[domainSizes.length];
		for(int i = 0; i < domains.length; i++) {
			if(domainSizes[i] < 0) throw new IllegalArgumentException("Negative domain size");
			domains[i] = new BitSet(domainSizes[i]); domains[i].set(0,domainSizes[i]);
			wideDomains[i]=domainSizes[i]>Long.SIZE;
			if(!wideDomains[i]) compactDomains[i]=domainSizes[i]==Long.SIZE ? -1L
				: domainSizes[i]==0 ? 0 : (1L<<domainSizes[i])-1;
		}
		revisions = new long[domainSizes.length];
		checkedRevisions = new long[this.checks.size()][];
		ArrayList<List<Integer>> uses = new ArrayList<>();
		for(int i = 0; i < domainSizes.length; i++) uses.add(new ArrayList<>());
		for(int c = 0; c < this.checks.size(); c++)
			for(int owner : this.checks.get(c).owners) {
				validateOwner(owner);
				uses.get(owner).add(c);
			}
		ArrayList<List<Integer>> costUses = new ArrayList<>();
		for(int i = 0; i < domainSizes.length; i++) costUses.add(new ArrayList<>());
		for(int id=0; id<this.terms.size(); id++)
			for(int owner : this.terms.get(id).owners) {
				validateOwner(owner);
				costUses.get(owner).add(id);
			}
		incident = uses.stream().map(v -> v.stream().mapToInt(Integer::intValue).toArray())
			.toArray(int[][]::new);
		costIncident = costUses.stream().map(v -> v.stream().mapToInt(Integer::intValue).toArray())
			.toArray(int[][]::new);
		rebuildBranchAdjacency();
		ownerBounds = new BigDecimal[domainSizes.length];
		termBounds = new BigDecimal[this.terms.size()];
		dirtyOwnerBounds.set(0,domainSizes.length);
		dirtyTermBounds.set(0,this.terms.size());
		truthCache = boundedCache(limits.cacheEntries());
		costCache = boundedCache(limits.cacheEntries());
		boundCache = boundedCache(limits.cacheEntries());
		supportCache = boundedCache(limits.cacheEntries());
	}

	private static int[] nonConstantScope(int[] owners,int[] domainSizes) {
		return Arrays.stream(owners).filter(owner -> {
			if(owner < 0 || owner >= domainSizes.length)
				throw new IllegalArgumentException("Unknown owner " + owner);
			return domainSizes[owner] != 1;
		}).toArray();
	}
	private static int[] expandConstantScope(int[] owners,int[] domainSizes,int[] reduced) {
		int[] values = new int[owners.length]; int position = 0;
		for(int i = 0; i < owners.length; i++) values[i] = domainSizes[owners[i]] == 1 ? 0 : reduced[position++];
		return values;
	}

	/** A solver instance owns one invocation; do not share mutable domains across queries. */
	public Result optimize() {
		if(optimized) throw new IllegalStateException("Optimizer invocation already consumed");
		optimized = true;
		// An open native universe can never certify this planner's exact objective.
		// Searching its known subset only moves a generation blocker into the solver.
		// No rule is removed and no fallback/incumbent is emitted as an optimum.
		if(!complete()) return new Result(Status.UNRESOLVED,null,null,statistics());
		for(int c = 0; c < checks.size(); c++) enqueue(c);
		// A forest has an exact separator of one owner. Shared multi-owner terms
		// or cycles remain in the lazy global search; they are never duplicated.
		boolean feasible = propagate() && Arrays.stream(domains).noneMatch(BitSet::isEmpty);
		if(feasible) normalizeAfterPropagation();
		// A fixed analysis obligation cannot be resolved by assigning unrelated
		// owners. Preserve UNKNOWN (not infeasibility), without enumerating their
		// Cartesian product. Singleton projection has made every dependency explicit.
		if(feasible)
			for(int id=0;id<checks.size();id++)
				if(checks.get(id).owners.length==0 && evaluate(id,new int[0])==Truth.UNKNOWN)
					return new Result(Status.UNRESOLVED,null,null,statistics());
		if(feasible && !tryTreeSolve() && !tryLeafRegionSolve()) search();
		Status status = exhausted ? Status.RESOURCE_EXHAUSTED
			: !complete() || unresolvedLowerBound != null &&
				(incumbent == null || unresolvedLowerBound.compareTo(incumbent) <= 0) ? Status.UNRESOLVED
			: selected == null ? Status.INFEASIBLE : Status.OPTIMAL;
		return new Result(status, incumbent, selected, statistics());
	}

	/** Fold ordinals proven singleton by the initial feasibility fixed point exactly once. */
	private void normalizeAfterPropagation() {
		List<Check> reducedChecks=new ArrayList<>(checks.size());
		boolean projected=false;
		for(Check check : checks) {
			int[] reduced=nonSingletonScope(check.owners);
			if(reduced.length==check.owners.length) reducedChecks.add(check);
			else {
				projected=true;
				int[] fixed=fixedOrdinals(check.owners);
				reducedChecks.add(new Check(check.site,reduced,values -> check.predicate.test(
					expandFixedScope(check.owners,fixed,values))));
			}
		}
		List<CostFactor> reducedTerms=new ArrayList<>(terms.size());
		for(CostFactor term : terms) {
			int[] reduced=nonSingletonScope(term.owners);
			if(reduced.length==term.owners.length) reducedTerms.add(term);
			else {
				projected=true;
				int[] fixed=fixedOrdinals(term.owners);
				reducedTerms.add(new CostFactor(reduced,values -> term.evaluator.apply(
					expandFixedScope(term.owners,fixed,values)),term.certifiedMinimum));
			}
		}
		List<Check> groupedChecks=groupChecks(reducedChecks);
		boolean costNormalization=reducedTerms.stream().anyMatch(term -> term.owners.length<=1)
			|| uniqueScopeCount(reducedTerms.stream().map(term -> term.owners).toList())<reducedTerms.size();
		if(!projected && groupedChecks.size()==checks.size() && !costNormalization) return;
		checks=groupedChecks;
		terms=foldAndGroupCosts(reducedTerms);
		rebuildDependencyIndexes();
		truthCache.clear(); costCache.clear(); boundCache.clear(); supportCache.clear();
		Arrays.fill(ownerBounds,null); termBounds=new BigDecimal[terms.size()];
		dirtyOwnerBounds.clear(); dirtyOwnerBounds.set(0,domains.length);
		dirtyTermBounds.clear(); dirtyTermBounds.set(0,terms.size());
		cachedLowerBound=BigDecimal.ZERO; lowerBoundInitialized=false;
	}
	private static int uniqueScopeCount(List<int[]> scopes) {
		Set<ScopeKey> unique=new LinkedHashSet<>();
		for(int[] scope : scopes) unique.add(new ScopeKey(scope));
		return unique.size();
	}
	private List<Check> groupChecks(List<Check> input) {
		Map<ScopeKey,List<Check>> groups=new LinkedHashMap<>();
		for(Check check : input) groups.computeIfAbsent(new ScopeKey(check.owners),ignored -> new ArrayList<>()).add(check);
		List<Check> result=new ArrayList<>(groups.size());
		for(List<Check> group : groups.values()) {
			Check first=group.get(0);
			result.add(group.size()==1 ? first : new Check(first.site,first.owners,values -> {
				boolean unknown=false;
				for(Check check : group) {
					Truth truth=check.predicate.test(values.clone());
					if(truth==Truth.NO) return Truth.NO;
					unknown|=truth==Truth.UNKNOWN;
				}
				return unknown ? Truth.UNKNOWN : Truth.YES;
			}));
		}
		return List.copyOf(result);
	}
	private List<CostFactor> foldAndGroupCosts(List<CostFactor> input) {
		Map<ScopeKey,List<CostFactor>> groups=new LinkedHashMap<>();
		for(CostFactor factor : input)
			groups.computeIfAbsent(new ScopeKey(factor.owners),ignored -> new ArrayList<>()).add(factor);
		List<CostFactor> result=new ArrayList<>();
		for(List<CostFactor> group : groups.values()) {
			CostFactor first=group.get(0);
			BigDecimal certified=group.stream().map(value -> value.certifiedMinimum)
				.reduce(BigDecimal.ZERO,BigDecimal::add);
			CostFactor combined=group.size()==1 ? first : new CostFactor(first.owners,values -> {
				BigDecimal sum=BigDecimal.ZERO;
				for(CostFactor factor : group) sum=sum.add(factor.evaluator.apply(values.clone()));
				return sum;
			},certified);
			if(combined.owners.length==0) fixedCost=fixedCost.add(evaluateFactor(combined,new int[0]));
			else if(combined.owners.length==1) {
				int owner=combined.owners[0];
				for(int rule=0; rule<domainSizes[owner]; rule++)
					nativeCosts[owner][rule]=nativeCosts[owner][rule].add(evaluateFactor(combined,new int[] {rule}));
			}
			else result.add(combined);
		}
		return List.copyOf(result);
	}
	private BigDecimal evaluateFactor(CostFactor factor,int[] values) {
		costEvaluations++;
		BigDecimal result=Objects.requireNonNull(factor.evaluator.apply(values.clone()));
		if(result.compareTo(factor.certifiedMinimum)<0)
			throw new IllegalStateException("Cost below certified bound while folding scope "+Arrays.toString(factor.owners));
		return result;
	}
	private int[] nonSingletonScope(int[] owners) {
		return Arrays.stream(owners).filter(owner -> domainCardinalities[owner]!=1).toArray();
	}
	private int[] fixedOrdinals(int[] owners) {
		int[] fixed=new int[owners.length]; Arrays.fill(fixed,-1);
		for(int i=0; i<owners.length; i++) if(domainCardinalities[owners[i]]==1)
			fixed[i]=domains[owners[i]].nextSetBit(0);
		return fixed;
	}
	private static int[] expandFixedScope(int[] owners,int[] fixed,int[] reduced) {
		int[] values=new int[owners.length]; int position=0;
		for(int i=0; i<owners.length; i++) values[i]=fixed[i]>=0 ? fixed[i] : reduced[position++];
		return values;
	}
	private void rebuildDependencyIndexes() {
		ArrayList<List<Integer>> checkUses=new ArrayList<>(),costUses=new ArrayList<>();
		for(int i=0; i<domains.length; i++) { checkUses.add(new ArrayList<>()); costUses.add(new ArrayList<>()); }
		checkedRevisions=new long[checks.size()][];
		for(int id=0; id<checks.size(); id++) for(int owner : checks.get(id).owners) checkUses.get(owner).add(id);
		for(int id=0; id<terms.size(); id++) for(int owner : terms.get(id).owners) costUses.get(owner).add(id);
		incident=checkUses.stream().map(values -> values.stream().mapToInt(Integer::intValue).toArray())
			.toArray(int[][]::new);
		costIncident=costUses.stream().map(values -> values.stream().mapToInt(Integer::intValue).toArray())
			.toArray(int[][]::new);
		rebuildBranchAdjacency();
		queue.clear(); queued.clear();
	}
	private void rebuildBranchAdjacency() {
		BitSet[] adjacent=new BitSet[domains.length];
		for(int owner=0; owner<adjacent.length; owner++) adjacent[owner]=new BitSet(domains.length);
		for(Check check : checks) connectScope(adjacent,check.owners);
		for(CostFactor term : terms) connectScope(adjacent,term.owners);
		branchAdjacent=Arrays.stream(adjacent).map(bits -> bits.stream().toArray()).toArray(int[][]::new);
		activeBranchNeighbors=new int[domains.length];
		for(int owner=0;owner<domains.length;owner++)
			activeBranchNeighbors[owner]=scanBranchConnectivity(owner);
	}
	private static void connectScope(BitSet[] adjacent,int[] owners) {
		for(int left=0; left<owners.length; left++) for(int right=left+1; right<owners.length; right++) {
			adjacent[owners[left]].set(owners[right]);
			adjacent[owners[right]].set(owners[left]);
		}
	}

	private void search() {
		if(nodes >= limits.searchNodes()) { exhausted = true; return; }
		nodes++;
		if(!propagate()) return;
		for(int owner = 0; owner < domains.length; owner++)
			if(domains[owner].isEmpty()) {
				if(!coverage[owner]) unresolvedLowerBound = minimum(unresolvedLowerBound, BigDecimal.ZERO);
				return;
			}
		BigDecimal bound = lowerBound();
		if(incumbent != null && (bound.compareTo(incumbent) > 0 || bound.compareTo(incumbent) == 0
			&& lexicographicDomainFloor(selected) >= 0)) { boundPrunes++; return; }
		int owner = chooseOwner();
		if(owner < 0) {
			int[] assignment = currentAssignment();
			boolean unknown = false;
			for(int c = 0; c < checks.size(); c++) {
				Truth truth = evaluate(c, project(checks.get(c).owners, assignment));
				if(truth == Truth.NO) return;
				unknown |= truth == Truth.UNKNOWN;
			}
			if(unknown) { unresolvedLowerBound = minimum(unresolvedLowerBound, bound); return; }
			// All native and owned cost terms are exact for a complete assignment.
			if(incumbent == null || bound.compareTo(incumbent) < 0 ||
				bound.compareTo(incumbent) == 0 && lexicographic(assignment, selected) < 0) {
				incumbent = bound;
				selected = assignment;
			}
			return;
		}
		List<Integer> choices = domains[owner].stream().boxed().sorted((a,b) -> {
			int cmp = nativeCosts[owner][a].compareTo(nativeCosts[owner][b]);
			return cmp == 0 ? Integer.compare(a,b) : cmp;
		}).toList();
		for(int value : choices) {
			int mark = trail.size();
			for(int other = domains[owner].nextSetBit(0); other >= 0;
				other = domains[owner].nextSetBit(other + 1))
				if(other != value) remove(owner, other);
			search();
			undo(mark);
			if(exhausted) return;
		}
	}

	private boolean propagate() { return propagate(null); }

	private boolean propagate(BitSet relevantChecks) {
		while(!queue.isEmpty()) {
			int id = queue.removeFirst(); queued.clear(id);
			if(relevantChecks != null && !relevantChecks.get(id)) continue;
			Check check = checks.get(id);
			long[] stamp = stamp(check);
			if(Arrays.equals(stamp, checkedRevisions[id])) continue;
			worklistVisits++;
			boolean ready = true;
			for(int owner : check.owners) ready &= coverage[owner];
			if(!ready) { checkedRevisions[id] = stamp; continue; }
			if(check.owners.length == 0 && evaluate(id, new int[0]) == Truth.NO) return false;
			for(int owner : check.owners)
				for(int value = domains[owner].nextSetBit(0); value >= 0;
					value = domains[owner].nextSetBit(value + 1))
					if(support(id, owner, value) == Truth.NO) {
						remove(owner, value);
						if(domains[owner].isEmpty()) return false;
					}
			if(Arrays.equals(stamp, stamp(check))) checkedRevisions[id] = stamp;
			else enqueue(id);
		}
		return true;
	}

	private Truth support(int checkId, int fixedOwner, int fixedValue) {
		Check check = checks.get(checkId);
		Truth cached = cachedDomains(supportCache,checkId,fixedOwner,fixedValue,check.owners);
		if(cached != null) { supportCacheHits++; return cached; }
		supportQueries++;
		int[] choices = new int[check.owners.length]; Arrays.fill(choices, -1);
		for(int i = 0; i < check.owners.length; i++)
			if(check.owners[i] == fixedOwner) choices[i] = fixedValue;
		Truth result = supportDfs(checkId, choices, new int[] {limits.supportEvaluations()});
		supportCache.put(new ScopedDomainKey(checkId,fixedOwner,fixedValue,check.owners,domains,
			compactDomains,wideDomains),result);
		return result;
	}

	private Truth supportDfs(int id, int[] choices, int[] budget) {
		if(budget[0]-- <= 0) return Truth.UNKNOWN;
		Truth prefix = evaluate(id, choices);
		if(prefix != Truth.UNKNOWN) return prefix;
		Check check = checks.get(id);
		int pos = -1;
		for(int i = 0; i < choices.length; i++)
			if(choices[i] < 0 && (pos < 0 || domainCardinalities[check.owners[i]] <
				domainCardinalities[check.owners[pos]])) pos = i;
		if(pos < 0) return Truth.UNKNOWN;
		boolean unknown = false;
		for(int value = domains[check.owners[pos]].nextSetBit(0); value >= 0;
			value = domains[check.owners[pos]].nextSetBit(value + 1)) {
			choices[pos] = value;
			Truth result = supportDfs(id, choices, budget);
			choices[pos] = -1;
			if(result == Truth.YES) return Truth.YES;
			unknown |= result == Truth.UNKNOWN;
			if(budget[0] <= 0) return Truth.UNKNOWN;
		}
		return unknown ? Truth.UNKNOWN : Truth.NO;
	}

	private Truth evaluate(int id, int[] values) {
		Truth cached = cached(truthCache,id,values);
		if(cached != null) { checkCacheHits++; return cached; }
		checkEvaluations++;
		Truth value = Objects.requireNonNull(checks.get(id).predicate.test(values.clone()));
		truthCache.put(new TupleKey(id,values), value);
		return value;
	}


	/** Exact DP for a closed forest of unary/binary scopes; no physical alternative table. */
	private boolean tryTreeSolve() {
		if(!complete() || limits.searchNodes() != Long.MAX_VALUE) return false;
		List<Set<Integer>> adjacent = new ArrayList<>();
		for(int i = 0; i < domains.length; i++) adjacent.add(new LinkedHashSet<>());
		for(Check check : checks) if(!addTreeEdge(adjacent,check.owners)) return false;
		for(CostFactor term : terms) if(!addTreeEdge(adjacent,term.owners)) return false;
		int[] parent = new int[domains.length]; Arrays.fill(parent,-2);
		List<Integer> order = new ArrayList<>();
		List<Integer> roots = new ArrayList<>();
		for(int root = 0; root < domains.length; root++) {
			if(parent[root] != -2) continue;
			parent[root] = -1; roots.add(root);
			ArrayDeque<Integer> pending = new ArrayDeque<>(); pending.add(root);
			while(!pending.isEmpty()) {
				int owner = pending.removeFirst(); order.add(owner);
				for(int next : adjacent.get(owner)) {
					if(next == parent[owner]) continue;
					if(parent[next] != -2) return false;
					parent[next] = owner; pending.add(next);
				}
			}
		}
		List<List<Integer>> unaryChecks = new ArrayList<>(), unaryTerms = new ArrayList<>();
		Map<Long,List<Integer>> edgeChecks = new HashMap<>(), edgeTerms = new HashMap<>();
		for(int i = 0; i < domains.length; i++) { unaryChecks.add(new ArrayList<>()); unaryTerms.add(new ArrayList<>()); }
		BigDecimal constant = fixedCost;
		for(int id = 0; id < checks.size(); id++) {
			int[] scope = checks.get(id).owners;
			if(scope.length == 0) {
				Truth result = evaluate(id,new int[0]);
				if(result == Truth.UNKNOWN) return false;
				if(result == Truth.NO) return true;
			}
			else if(scope.length == 1) unaryChecks.get(scope[0]).add(id);
			else edgeChecks.computeIfAbsent(edgeKey(scope[0],scope[1]),key -> new ArrayList<>()).add(id);
		}
		for(int id = 0; id < terms.size(); id++) {
			int[] scope = terms.get(id).owners;
			if(scope.length == 0) constant = constant.add(evaluateCost(id,new int[0]));
			else if(scope.length == 1) unaryTerms.get(scope[0]).add(id);
			else edgeTerms.computeIfAbsent(edgeKey(scope[0],scope[1]),key -> new ArrayList<>()).add(id);
		}
		TreeCell[][] cells = new TreeCell[domains.length][];
		for(int pos = order.size()-1; pos >= 0; pos--) {
			int owner = order.get(pos);
			cells[owner] = new TreeCell[domainSizes[owner]];
			int[] children = adjacent.get(owner).stream().filter(next -> parent[next] == owner).mapToInt(Integer::intValue).toArray();
			for(int rule = 0; rule < cells[owner].length; rule++) {
				if(!domains[owner].get(rule)) continue;
				nodes++;
				boolean legal = true;
				for(int check : unaryChecks.get(owner)) {
					Truth truth = evaluate(check,new int[] {rule});
					if(truth == Truth.UNKNOWN) return false;
					if(truth == Truth.NO) { legal = false; break; }
				}
				if(!legal) continue;
				BigDecimal cost = nativeCosts[owner][rule];
				for(int term : unaryTerms.get(owner)) cost = cost.add(evaluateCost(term,new int[] {rule}));
				int[] childRules = new int[children.length];
				for(int c = 0; c < children.length; c++) {
					int child = children[c], bestRule = -1;
					BigDecimal best = null;
					long edge = edgeKey(owner,child);
					for(int childRule = 0; childRule < cells[child].length; childRule++) {
						TreeCell childCell = cells[child][childRule];
						if(childCell == null) continue;
						boolean compatible = true;
						for(int id : edgeChecks.getOrDefault(edge,List.of())) {
							int[] scope = checks.get(id).owners;
							Truth truth = evaluate(id,scope[0] == owner ? new int[] {rule,childRule} : new int[] {childRule,rule});
							if(truth == Truth.UNKNOWN) return false;
							if(truth == Truth.NO) { compatible = false; break; }
						}
						if(!compatible) continue;
						BigDecimal candidate = childCell.cost;
						for(int id : edgeTerms.getOrDefault(edge,List.of())) {
							int[] scope = terms.get(id).owners;
							candidate = candidate.add(evaluateCost(id,scope[0] == owner
								? new int[] {rule,childRule} : new int[] {childRule,rule}));
						}
						if(best == null || candidate.compareTo(best) < 0 || candidate.compareTo(best) == 0
							&& compareTreeChoice(cells,child,childRule,bestRule) < 0) {
							best = candidate; bestRule = childRule;
						}
					}
					if(best == null) { legal = false; break; }
					cost = cost.add(best); childRules[c] = bestRule;
				}
				if(legal) cells[owner][rule] = new TreeCell(cost,children,childRules);
			}
		}
		int[] assignment = new int[domains.length];
		BigDecimal total = constant;
		for(int root : roots) {
			int bestRule = -1;
			for(int rule = 0; rule < cells[root].length; rule++) {
				if(cells[root][rule] == null) continue;
				if(bestRule < 0 || cells[root][rule].cost.compareTo(cells[root][bestRule].cost) < 0
					|| cells[root][rule].cost.compareTo(cells[root][bestRule].cost) == 0
					&& compareTreeChoice(cells,root,rule,bestRule) < 0) bestRule = rule;
			}
			if(bestRule < 0) return true;
			total = total.add(cells[root][bestRule].cost);
			reconstructTree(cells,root,bestRule,assignment);
		}
		selected = assignment; incumbent = total;
		return true;
	}
	private static boolean addTreeEdge(List<Set<Integer>> adjacent,int[] scope) {
		if(scope.length > 2) return false;
		if(scope.length == 2) { adjacent.get(scope[0]).add(scope[1]); adjacent.get(scope[1]).add(scope[0]); }
		return true;
	}
	private static long edgeKey(int a,int b) { return ((long)Math.min(a,b) << 32) | Math.max(a,b); }
	private record TreeCell(BigDecimal cost,int[] children,int[] childRules) { }
	private static void reconstructTree(TreeCell[][] cells,int owner,int rule,int[] assignment) {
		assignment[owner] = rule;
		TreeCell cell = cells[owner][rule];
		for(int i = 0; i < cell.children.length; i++) reconstructTree(cells,cell.children[i],cell.childRules[i],assignment);
	}
	private static int compareTreeChoice(TreeCell[][] cells,int owner,int a,int b) {
		int[] left = new int[cells.length], right = new int[cells.length];
		reconstructTree(cells,owner,a,left); reconstructTree(cells,owner,b,right);
		return lexicographic(left,right);
	}
	private BigDecimal evaluateCost(int id,int[] values) {
		BigDecimal value = cached(costCache,id,values);
		if(value != null) { costCacheHits++; return value; }
		costEvaluations++;
		CostFactor term = terms.get(id);
		value = Objects.requireNonNull(term.evaluator.apply(values.clone()));
		if(value.compareTo(term.certifiedMinimum) < 0)
			throw new IllegalStateException("Cost below certified bound at term " + id);
		costCache.put(new TupleKey(id,values),value);
		return value;
	}

	/** Exact leaf-tree elimination around a cyclic residual core. */
	private boolean tryLeafRegionSolve() {
		if(!complete()) return false;
		List<Set<Integer>> adjacent = new ArrayList<>();
		for(int i = 0; i < domains.length; i++) adjacent.add(new LinkedHashSet<>());
		boolean[] pinned = new boolean[domains.length];
		for(Check check : checks) addResidualScope(adjacent,pinned,check.owners);
		for(CostFactor term : terms) addResidualScope(adjacent,pinned,term.owners);
		int[] degree = adjacent.stream().mapToInt(Set::size).toArray();
		boolean[] removed = new boolean[domains.length];
		int[] parent = new int[domains.length]; Arrays.fill(parent,-1);
		ArrayDeque<Integer> leaves = new ArrayDeque<>();
		for(int owner = 0; owner < domains.length; owner++)
			if(!pinned[owner] && degree[owner] <= 1) leaves.add(owner);
		List<Integer> removalOrder = new ArrayList<>();
		while(!leaves.isEmpty()) {
			int owner = leaves.removeFirst();
			if(removed[owner]) continue;
			int liveNeighbor = -1;
			for(int neighbor : adjacent.get(owner)) if(!removed[neighbor]) { liveNeighbor = neighbor; break; }
			removed[owner] = true; parent[owner] = liveNeighbor; removalOrder.add(owner);
			if(liveNeighbor >= 0 && --degree[liveNeighbor] == 1 && !pinned[liveNeighbor])
				leaves.add(liveNeighbor);
		}
		int coreCount = 0;
		for(boolean value : removed) if(!value) coreCount++;
		if(coreCount == 0 || removalOrder.isEmpty()) return false;

		List<List<Integer>> unaryChecks = lists(domains.length), unaryTerms = lists(domains.length);
		Map<Long,List<Integer>> edgeChecks = new HashMap<>(), edgeTerms = new HashMap<>();
		List<Integer> constantChecks = new ArrayList<>(), constantTerms = new ArrayList<>();
		for(int id = 0; id < checks.size(); id++) {
			int[] scope = checks.get(id).owners;
			if(scope.length == 0) constantChecks.add(id);
			else if(scope.length == 1) unaryChecks.get(scope[0]).add(id);
			else if(scope.length == 2)
				edgeChecks.computeIfAbsent(edgeKey(scope[0],scope[1]), ignored -> new ArrayList<>()).add(id);
		}
		for(int id = 0; id < terms.size(); id++) {
			int[] scope = terms.get(id).owners;
			if(scope.length == 0) constantTerms.add(id);
			else if(scope.length == 1) unaryTerms.get(scope[0]).add(id);
			else if(scope.length == 2)
				edgeTerms.computeIfAbsent(edgeKey(scope[0],scope[1]), ignored -> new ArrayList<>()).add(id);
		}
		for(int id : constantChecks) {
			Truth truth = evaluate(id,new int[0]);
			if(truth == Truth.UNKNOWN) return false;
			if(truth == Truth.NO) return true;
		}
		BigDecimal constant = fixedCost;
		for(int id : constantTerms) constant = constant.add(evaluateCost(id,new int[0]));

		List<List<Integer>> children = lists(domains.length);
		for(int owner : removalOrder) if(parent[owner] >= 0 && removed[parent[owner]])
			children.get(parent[owner]).add(owner);
		TreeCell[][] cells = new TreeCell[domains.length][];
		for(int owner : removalOrder) {
			cells[owner] = new TreeCell[domainSizes[owner]];
			int[] childOwners = children.get(owner).stream().mapToInt(Integer::intValue).toArray();
			for(int rule = domains[owner].nextSetBit(0); rule >= 0; rule = domains[owner].nextSetBit(rule+1)) {
				if(nodes >= limits.searchNodes()) { exhausted = true; return true; }
				nodes++;
				boolean legal = true;
				for(int check : unaryChecks.get(owner)) {
					Truth truth = evaluate(check,new int[] {rule});
					if(truth == Truth.UNKNOWN) return false;
					if(truth == Truth.NO) { legal = false; break; }
				}
				if(!legal) continue;
				BigDecimal cost = nativeCosts[owner][rule];
				for(int term : unaryTerms.get(owner)) cost = cost.add(evaluateCost(term,new int[] {rule}));
				int[] childRules = new int[childOwners.length];
				for(int position = 0; position < childOwners.length; position++) {
					int child = childOwners[position], bestRule = -1;
					BigDecimal best = null; long edge = edgeKey(owner,child);
					for(int childRule = 0; childRule < cells[child].length; childRule++) {
						TreeCell childCell = cells[child][childRule];
						if(childCell == null) continue;
						Truth compatible = edgeTruth(edgeChecks.getOrDefault(edge,List.of()),
							owner,rule,child,childRule);
						if(compatible == Truth.UNKNOWN) return false;
						if(compatible == Truth.NO) continue;
						BigDecimal candidate = childCell.cost.add(edgeCost(
							edgeTerms.getOrDefault(edge,List.of()),owner,rule,child,childRule));
						if(best == null || candidate.compareTo(best) < 0 || candidate.compareTo(best) == 0
							&& compareTreeChoice(cells,child,childRule,bestRule) < 0) {
							best = candidate; bestRule = childRule;
						}
					}
					if(best == null) { legal = false; break; }
					cost = cost.add(best); childRules[position] = bestRule;
				}
				if(legal) cells[owner][rule] = new TreeCell(cost,childOwners,childRules);
			}
		}

		List<BoundaryTree> boundaries = new ArrayList<>(), isolated = new ArrayList<>();
		for(int owner : removalOrder) {
			if(parent[owner] >= 0 && !removed[parent[owner]])
				boundaries.add(boundaryTree(cells,owner,parent[owner],edgeChecks,edgeTerms));
			else if(parent[owner] < 0)
				isolated.add(bestIsolated(cells,owner));
		}
		if(boundaries.stream().anyMatch(Objects::isNull) || isolated.stream().anyMatch(Objects::isNull))
			return false;
		for(BoundaryTree tree : boundaries) if(tree.unknown) return false;
		BigDecimal base = constant;
		int[] baseAssignment = currentAssignment();
		for(BoundaryTree tree : isolated) {
			base = base.add(tree.costs[0]);
			reconstructTree(cells,tree.root,tree.rules[0],baseAssignment);
		}
			ReducedSearch reduced = new ReducedSearch(removed,cells,boundaries,base,baseAssignment);
			if(!reduced.run()) return false;
			if(reduced.bestCost!=null) { incumbent=reduced.bestCost; selected=reduced.bestAssignment; }
			if(reduced.exhausted) { exhausted = true; return true; }
			return true;
	}

	private static void addResidualScope(List<Set<Integer>> adjacent,boolean[] pinned,int[] scope) {
		if(scope.length > 2) {
			for(int owner : scope) pinned[owner]=true;
		}
		else if(scope.length == 2) {
			adjacent.get(scope[0]).add(scope[1]); adjacent.get(scope[1]).add(scope[0]);
		}
	}

	private BoundaryTree boundaryTree(TreeCell[][] cells,int root,int boundary,
		Map<Long,List<Integer>> edgeChecks,Map<Long,List<Integer>> edgeTerms) {
		BigDecimal[] costs = new BigDecimal[domainSizes[boundary]];
		int[] rules = new int[domainSizes[boundary]]; Arrays.fill(rules,-1);
		long edge = edgeKey(root,boundary); boolean unknown = false;
		for(int boundaryRule = domains[boundary].nextSetBit(0); boundaryRule >= 0;
			boundaryRule = domains[boundary].nextSetBit(boundaryRule+1)) {
			for(int rootRule = 0; rootRule < cells[root].length; rootRule++) {
				TreeCell cell = cells[root][rootRule]; if(cell == null) continue;
				Truth truth = edgeTruth(edgeChecks.getOrDefault(edge,List.of()),boundary,boundaryRule,root,rootRule);
				if(truth == Truth.UNKNOWN) { unknown = true; continue; }
				if(truth == Truth.NO) continue;
				BigDecimal candidate = cell.cost.add(edgeCost(edgeTerms.getOrDefault(edge,List.of()),
					boundary,boundaryRule,root,rootRule));
				if(costs[boundaryRule] == null || candidate.compareTo(costs[boundaryRule]) < 0
					|| candidate.compareTo(costs[boundaryRule]) == 0
					&& compareTreeChoice(cells,root,rootRule,rules[boundaryRule]) < 0) {
					costs[boundaryRule] = candidate; rules[boundaryRule] = rootRule;
				}
			}
		}
		return new BoundaryTree(root,boundary,costs,rules,unknown);
	}

	private BoundaryTree bestIsolated(TreeCell[][] cells,int root) {
		int bestRule = -1; BigDecimal best = null;
		for(int rule = 0; rule < cells[root].length; rule++) if(cells[root][rule] != null &&
			(best == null || cells[root][rule].cost.compareTo(best) < 0
				|| cells[root][rule].cost.compareTo(best) == 0
				&& compareTreeChoice(cells,root,rule,bestRule) < 0)) {
			best = cells[root][rule].cost; bestRule = rule;
		}
		return best == null ? null : new BoundaryTree(root,-1,new BigDecimal[] {best},new int[] {bestRule},false);
	}

	private Truth edgeTruth(List<Integer> ids,int left,int leftRule,int right,int rightRule) {
		for(int id : ids) {
			int[] scope = checks.get(id).owners;
			Truth truth = evaluate(id,scope[0] == left ? new int[] {leftRule,rightRule}
				: new int[] {rightRule,leftRule});
			if(truth != Truth.YES) return truth;
		}
		return Truth.YES;
	}

	private BigDecimal edgeCost(List<Integer> ids,int left,int leftRule,int right,int rightRule) {
		BigDecimal cost = BigDecimal.ZERO;
		for(int id : ids) {
			int[] scope = terms.get(id).owners;
			cost = cost.add(evaluateCost(id,scope[0] == left ? new int[] {leftRule,rightRule}
				: new int[] {rightRule,leftRule}));
		}
		return cost;
	}

	private static <T> List<List<T>> lists(int size) {
		List<List<T>> result = new ArrayList<>(size);
		for(int i = 0; i < size; i++) result.add(new ArrayList<>());
		return result;
	}

	private record BoundaryTree(int root,int boundary,BigDecimal[] costs,int[] rules,boolean unknown) { }
	private enum ForestTailStatus { NOT_APPLICABLE, COMPLETE, EXHAUSTED }

	private final class ReducedSearch {
		private final boolean[] removed;
		private final TreeCell[][] cells;
		private final List<BoundaryTree> boundaries;
		private final BigDecimal base;
		private final int[] assignment;
		private BigDecimal bestCost;
		private int[] bestAssignment;
		private boolean unknown,exhausted;
		private final BitSet coreChecks = new BitSet();
		private final List<Integer> coreTerms = new ArrayList<>();
		private final BitSet coreOwners = new BitSet();
		private final BitSet coreTermSet = new BitSet();
		private final BitSet dirtyCoreOwners = new BitSet();
		private final BitSet dirtyCoreTerms = new BitSet();
		private final BigDecimal[] coreOwnerBounds = new BigDecimal[domains.length];
		private final BigDecimal[] coreTermBounds = new BigDecimal[terms.size()];
		private final BigDecimal[][] ownerMessageCosts;
		private final int[][] ownerMessageOrder;
		private final List<List<BoundaryTree>> boundaryTrees;
		private int[] coreOwnerIds=new int[0];
		private BigDecimal cachedCoreBound;
		private ReducedSearch(boolean[] removed,TreeCell[][] cells,List<BoundaryTree> boundaries,
			BigDecimal base,int[] assignment) {
			this.removed=removed; this.cells=cells; this.boundaries=boundaries; this.base=base;
			this.assignment=assignment;
			List<List<Integer>> uses=lists(domains.length);
			boundaryTrees=new ArrayList<>(domains.length);
			for(int owner=0;owner<domains.length;owner++) boundaryTrees.add(new ArrayList<>());
			for(int id=0; id<boundaries.size(); id++) {
				uses.get(boundaries.get(id).boundary).add(id);
				boundaryTrees.get(boundaries.get(id).boundary).add(boundaries.get(id));
			}
			int[][] boundaryIncident=uses.stream().map(values -> values.stream().mapToInt(Integer::intValue).toArray())
				.toArray(int[][]::new);
			ownerMessageCosts=new BigDecimal[domains.length][];
			ownerMessageOrder=new int[domains.length][];
			for(int owner=0;owner<domains.length;owner++) if(!removed[owner]) {
				ownerMessageCosts[owner]=new BigDecimal[domainSizes[owner]];
				for(int rule=0;rule<domainSizes[owner];rule++) {
					BigDecimal cost=nativeCosts[owner][rule];
					for(int id:boundaryIncident[owner]) {
						BigDecimal message=boundaries.get(id).costs[rule];
						if(message==null) { cost=null; break; }
						cost=cost.add(message);
					}
					ownerMessageCosts[owner][rule]=cost;
				}
				BigDecimal[] costs=ownerMessageCosts[owner];
				Integer[] order=new Integer[(int)Arrays.stream(costs).filter(Objects::nonNull).count()];
				for(int rule=0,position=0;rule<costs.length;rule++) if(costs[rule]!=null) order[position++]=rule;
				Arrays.sort(order,(left,right) -> {
					int comparison=costs[left].compareTo(costs[right]);
					return comparison==0 ? Integer.compare(left,right) : comparison;
				});
				ownerMessageOrder[owner]=Arrays.stream(order).mapToInt(Integer::intValue).toArray();
			}
		}
		private boolean run() {
			for(int id = 0; id < checks.size(); id++)
				if(Arrays.stream(checks.get(id).owners).noneMatch(owner -> removed[owner])) coreChecks.set(id);
			for(int owner=0; owner<removed.length; owner++) if(!removed[owner]) coreOwners.set(owner);
			coreOwnerIds=coreOwners.stream().toArray();
			for(int id = 0; id < terms.size(); id++)
				if(terms.get(id).owners.length > 0 && Arrays.stream(terms.get(id).owners)
					.noneMatch(owner -> removed[owner])) { coreTerms.add(id); coreTermSet.set(id); }
			dirtyCoreOwners.or(coreOwners); dirtyCoreTerms.or(coreTermSet);
			cachedCoreBound=base;
			int mark = trail.size();
			activeReducedSearch=this;
			try {
				// A missing exact tree message makes that boundary choice infeasible.
				// Use the ordinary reversible domains/incidence queue, not a second solver.
				for(BoundaryTree tree : boundaries)
					for(int value = domains[tree.boundary].nextSetBit(0); value >= 0;
						value = domains[tree.boundary].nextSetBit(value+1))
						if(tree.costs[value] == null) remove(tree.boundary,value);
				enumerateCore();
			}
			finally { activeReducedSearch=null; undo(mark); }
			return !unknown;
		}
		private void enumerateCore() {
			if(unknown || exhausted) return;
			if(nodes >= limits.searchNodes()) { exhausted=true; return; }
			nodes++;
			if(!propagate(coreChecks)) return;
			for(int owner:coreOwnerIds) {
				if(domains[owner].isEmpty()) return;
				assignment[owner] = domainCardinalities[owner]==1 ? domains[owner].nextSetBit(0) : -1;
			}
			BigDecimal bound = coreLowerBound();
			if(bestCost != null && (bound.compareTo(bestCost)>0 || bound.compareTo(bestCost)==0
				&& reducedLexicographicFloor(bestAssignment)>=0)) { boundPrunes++; return; }
			ForestTailStatus tail=tryForestTail();
			if(tail!=ForestTailStatus.NOT_APPLICABLE) return;
			int owner = -1, connectivity = -1;
			for(int i:coreOwnerIds) if(assignment[i]<0) {
				// Eliminated tree neighbors remain exact boundary messages for this owner;
				// count them when prioritizing decisions that expose those message costs.
				int candidateConnectivity=branchConnectivity(i);
				if(owner<0 || candidateConnectivity>connectivity
					|| candidateConnectivity==connectivity && costIncident[i].length>costIncident[owner].length
					|| candidateConnectivity==connectivity && costIncident[i].length==costIncident[owner].length
						&& incident[i].length>incident[owner].length
					|| candidateConnectivity==connectivity && costIncident[i].length==costIncident[owner].length
						&& incident[i].length==incident[owner].length
						&& domainCardinalities[i]<domainCardinalities[owner]) {
					owner=i; connectivity=candidateConnectivity;
				}
			}
			if(owner<0) { completeCore(); return; }
			for(int value : ownerMessageOrder[owner]) {
				if(!domains[owner].get(value)) continue;
				int mark=trail.size();
				for(int other=domains[owner].nextSetBit(0); other>=0;
					other=domains[owner].nextSetBit(other+1)) if(other!=value) remove(owner,other);
				enumerateCore(); undo(mark);
				if(unknown || exhausted) return;
			}
		}
		private ForestTailStatus tryForestTail() {
			forestTailAttempts++;
			boolean[] active=new boolean[domains.length];
			int unfixed=0;
			for(int owner:coreOwnerIds) if(domainCardinalities[owner]>1) { active[owner]=true; unfixed++; }
			if(unfixed==0) return ForestTailStatus.NOT_APPLICABLE;
			int[] parent=new int[domains.length]; Arrays.fill(parent,-2);
			List<Integer> order=new ArrayList<>(),roots=new ArrayList<>();
			for(int root:coreOwnerIds) {
				if(domainCardinalities[root]==1 || parent[root]!=-2) continue;
				parent[root]=-1; roots.add(root);
				ArrayDeque<Integer> pending=new ArrayDeque<>(); pending.add(root);
				while(!pending.isEmpty()) {
					int owner=pending.removeFirst(); order.add(owner);
					for(int next:branchAdjacent[owner]) {
						if(!active[next]) continue;
						if(next==parent[owner]) continue;
						if(parent[next]!=-2) return ForestTailStatus.NOT_APPLICABLE;
						parent[next]=owner; pending.add(next);
					}
				}
			}
			long remaining=limits.searchNodes()-nodes,cellsNeeded=0;
			for(int owner:coreOwnerIds) if(active[owner]) {
				cellsNeeded+=domainCardinalities[owner];
				if(cellsNeeded>remaining) return ForestTailStatus.NOT_APPLICABLE;
			}
			forestTailApplicable++;
			List<List<Integer>> unaryChecks=lists(domains.length),unaryTerms=lists(domains.length);
			List<Integer> constantChecks=new ArrayList<>(),constantTerms=new ArrayList<>();
			Map<Long,List<Integer>> edgeChecks=new HashMap<>(),edgeTerms=new HashMap<>();
			for(int id=coreChecks.nextSetBit(0);id>=0;id=coreChecks.nextSetBit(id+1))
				if(!classifyTail(id,unfixedScope(checks.get(id).owners),constantChecks,unaryChecks,edgeChecks))
					return ForestTailStatus.NOT_APPLICABLE;
			for(int id:coreTerms)
				if(!classifyTail(id,unfixedScope(terms.get(id).owners),constantTerms,unaryTerms,edgeTerms))
					return ForestTailStatus.NOT_APPLICABLE;
			BigDecimal total=base;
			for(int id:constantChecks) {
				Truth truth=evaluate(id,project(checks.get(id).owners,assignment));
				if(truth==Truth.UNKNOWN) return ForestTailStatus.NOT_APPLICABLE;
				if(truth==Truth.NO) { forestTailCompletions++; return ForestTailStatus.COMPLETE; }
			}
			for(int id:constantTerms) total=total.add(evaluateCost(id,project(terms.get(id).owners,assignment)));
			int[] candidate=assignment.clone();
			for(int owner:coreOwnerIds) if(domainCardinalities[owner]==1) {
				int rule=assignment[owner]; BigDecimal cost=ownerMessageCosts[owner][rule];
				if(cost==null) { forestTailCompletions++; return ForestTailStatus.COMPLETE; }
				total=total.add(cost);
				for(BoundaryTree tree:boundaryTrees.get(owner))
					reconstructTree(cells,tree.root,tree.rules[rule],candidate);
			}
			TreeCell[][] tailCells=cells.clone();
			boolean[] unknown={false};
			for(int position=order.size()-1;position>=0;position--) {
				int owner=order.get(position);
				tailCells[owner]=new TreeCell[domainSizes[owner]];
				int[] dynamicChildren=Arrays.stream(branchAdjacent[owner]).filter(next -> parent[next]==owner).toArray();
				List<BoundaryTree> attached=boundaryTrees.get(owner);
				for(int rule=domains[owner].nextSetBit(0);rule>=0;rule=domains[owner].nextSetBit(rule+1)) {
					if(nodes>=limits.searchNodes()) { exhausted=true; return ForestTailStatus.EXHAUSTED; }
					nodes++; forestTailCells++;
					boolean legal=true;
					for(int id:unaryChecks.get(owner)) {
						Truth truth=evaluate(id,tailValues(checks.get(id).owners,owner,rule,-1,-1));
						if(truth==Truth.UNKNOWN) { unknown[0]=true; legal=false; break; }
						if(truth==Truth.NO) { legal=false; break; }
					}
					BigDecimal cost=ownerMessageCosts[owner][rule];
					if(!legal || cost==null) continue;
					for(int id:unaryTerms.get(owner))
						cost=cost.add(evaluateCost(id,tailValues(terms.get(id).owners,owner,rule,-1,-1)));
					int[] children=new int[dynamicChildren.length+attached.size()];
					int[] childRules=new int[children.length];
					for(int childPosition=0;childPosition<dynamicChildren.length;childPosition++) {
						int child=dynamicChildren[childPosition],bestRule=-1;
						BigDecimal best=null; long edge=edgeKey(owner,child);
						for(int childRule=0;childRule<tailCells[child].length;childRule++) {
							TreeCell childCell=tailCells[child][childRule]; if(childCell==null) continue;
							boolean compatible=true;
							for(int id:edgeChecks.getOrDefault(edge,List.of())) {
								Truth truth=evaluate(id,tailValues(checks.get(id).owners,owner,rule,child,childRule));
								if(truth==Truth.UNKNOWN) { unknown[0]=true; compatible=false; break; }
								if(truth==Truth.NO) { compatible=false; break; }
							}
							if(!compatible) continue;
							BigDecimal value=childCell.cost;
							for(int id:edgeTerms.getOrDefault(edge,List.of()))
								value=value.add(evaluateCost(id,tailValues(terms.get(id).owners,
									owner,rule,child,childRule)));
							if(best==null || value.compareTo(best)<0 || value.compareTo(best)==0
								&& compareTreeChoice(tailCells,child,childRule,bestRule)<0) {
								best=value; bestRule=childRule;
							}
						}
						if(best==null) { legal=false; break; }
						cost=cost.add(best); children[childPosition]=child; childRules[childPosition]=bestRule;
					}
					if(!legal) continue;
					for(int attachedPosition=0;attachedPosition<attached.size();attachedPosition++) {
						BoundaryTree tree=attached.get(attachedPosition);
						children[dynamicChildren.length+attachedPosition]=tree.root;
						childRules[dynamicChildren.length+attachedPosition]=tree.rules[rule];
					}
					tailCells[owner][rule]=new TreeCell(cost,children,childRules);
				}
			}
			if(unknown[0]) return ForestTailStatus.NOT_APPLICABLE;
			for(int root:roots) {
				int bestRule=-1;
				for(int rule=0;rule<tailCells[root].length;rule++) if(tailCells[root][rule]!=null &&
					(bestRule<0 || tailCells[root][rule].cost.compareTo(tailCells[root][bestRule].cost)<0
						|| tailCells[root][rule].cost.compareTo(tailCells[root][bestRule].cost)==0
						&& compareTreeChoice(tailCells,root,rule,bestRule)<0)) bestRule=rule;
				if(bestRule<0) { forestTailCompletions++; return ForestTailStatus.COMPLETE; }
				total=total.add(tailCells[root][bestRule].cost);
				reconstructTree(tailCells,root,bestRule,candidate);
			}
			if(bestCost==null || total.compareTo(bestCost)<0 || total.compareTo(bestCost)==0
				&& lexicographic(candidate,bestAssignment)<0) { bestCost=total; bestAssignment=candidate; }
			forestTailCompletions++;
			return ForestTailStatus.COMPLETE;
		}
		private int[] unfixedScope(int[] scope) {
			return Arrays.stream(scope).filter(owner -> domainCardinalities[owner]>1).toArray();
		}
		private boolean classifyTail(int id,int[] residual,List<Integer> constants,
			List<List<Integer>> unary,Map<Long,List<Integer>> edges) {
			if(residual.length>2) return false;
			if(residual.length==0) constants.add(id);
			else if(residual.length==1) unary.get(residual[0]).add(id);
			else {
				long edge=edgeKey(residual[0],residual[1]);
				edges.computeIfAbsent(edge,ignored -> new ArrayList<>()).add(id);
			}
			return true;
		}
		private int[] tailValues(int[] scope,int first,int firstRule,int second,int secondRule) {
			int[] values=new int[scope.length];
			for(int position=0;position<scope.length;position++) {
				int owner=scope[position];
				values[position]=owner==first ? firstRule : owner==second ? secondRule : assignment[owner];
			}
			return values;
		}
		private BigDecimal coreLowerBound() {
			for(int owner=dirtyCoreOwners.nextSetBit(0); owner>=0; owner=dirtyCoreOwners.nextSetBit(owner+1)) {
				BigDecimal next=ownerMessageBound(owner),old=coreOwnerBounds[owner];
				if(!sameBoundRepresentation(old,next)) {
					cachedCoreBound=cachedCoreBound.add(next).subtract(old==null ? BigDecimal.ZERO : old);
				}
				coreOwnerBounds[owner]=next;
			}
			dirtyCoreOwners.clear();
			for(int id=dirtyCoreTerms.nextSetBit(0); id>=0; id=dirtyCoreTerms.nextSetBit(id+1)) {
				BigDecimal next=currentTermBound(id),old=coreTermBounds[id];
				if(!sameBoundRepresentation(old,next)) {
					cachedCoreBound=cachedCoreBound.add(next).subtract(old==null ? BigDecimal.ZERO : old);
				}
				coreTermBounds[id]=next;
			}
			dirtyCoreTerms.clear();
			return cachedCoreBound;
		}
		private BigDecimal ownerMessageBound(int owner) {
			BigDecimal minimum=null;
			for(int rule=domains[owner].nextSetBit(0);rule>=0;rule=domains[owner].nextSetBit(rule+1))
				minimum=minimum(minimum,ownerMessageCosts[owner][rule]);
			return Objects.requireNonNull(minimum);
		}
		private void invalidate(int owner) {
			if(coreOwners.get(owner)) dirtyCoreOwners.set(owner);
			for(int id : costIncident[owner]) if(coreTermSet.get(id)) dirtyCoreTerms.set(id);
		}
		private int reducedLexicographicFloor(int[] best) {
			for(int owner=0; owner<removed.length; owner++) {
				// Zero is a conservative floor for an eliminated tree decision. Core domains are exact.
				int floor=removed[owner] ? 0 : domains[owner].nextSetBit(0);
				int comparison=Integer.compare(floor,best[owner]);
				if(comparison!=0) return comparison;
			}
			return 0;
		}
		private void completeCore() {
			for(int id = 0; id < checks.size(); id++) {
				int[] scope=checks.get(id).owners;
				if(Arrays.stream(scope).anyMatch(owner -> removed[owner])) continue;
				Truth truth=evaluate(id,project(scope,assignment));
				if(truth==Truth.UNKNOWN) { unknown=true; return; }
				if(truth==Truth.NO) return;
			}
			BigDecimal cost=base;
			for(int owner=0; owner<removed.length; owner++) if(!removed[owner])
				cost=cost.add(nativeCosts[owner][assignment[owner]]);
			for(int id=0; id<terms.size(); id++) {
				int[] scope=terms.get(id).owners;
				if(scope.length==0 || Arrays.stream(scope).anyMatch(owner -> removed[owner])) continue;
				cost=cost.add(evaluateCost(id,project(scope,assignment)));
			}
			int[] candidate=assignment.clone();
			for(BoundaryTree tree : boundaries) {
				int boundaryRule=candidate[tree.boundary];
				if(tree.costs[boundaryRule]==null) return;
				cost=cost.add(tree.costs[boundaryRule]);
				reconstructTree(cells,tree.root,tree.rules[boundaryRule],candidate);
			}
			if(bestCost==null || cost.compareTo(bestCost)<0 || cost.compareTo(bestCost)==0
				&& lexicographic(candidate,bestAssignment)<0) { bestCost=cost; bestAssignment=candidate; }
		}
	}

	private BigDecimal lowerBound() {
		if(!lowerBoundInitialized) {
			cachedLowerBound=fixedCost;
			for(int owner=0; owner<domains.length; owner++) {
				ownerBounds[owner]=nativeCostBound(owner);
				cachedLowerBound=cachedLowerBound.add(ownerBounds[owner]);
			}
			for(int id=0; id<terms.size(); id++) {
				termBounds[id]=computeConditionalCostBound(id);
				cachedLowerBound=cachedLowerBound.add(termBounds[id]);
			}
			dirtyOwnerBounds.clear(); dirtyTermBounds.clear(); lowerBoundInitialized=true;
			return cachedLowerBound;
		}
		for(int owner=dirtyOwnerBounds.nextSetBit(0); owner>=0;
			owner=dirtyOwnerBounds.nextSetBit(owner+1)) {
			BigDecimal old=ownerBounds[owner],next=nativeCostBound(owner);
			if(!sameBoundRepresentation(old,next)) cachedLowerBound=cachedLowerBound.add(next).subtract(old);
			ownerBounds[owner]=next;
		}
		dirtyOwnerBounds.clear();
		for(int id=dirtyTermBounds.nextSetBit(0); id>=0; id=dirtyTermBounds.nextSetBit(id+1))
			refreshTermBound(id);
		return cachedLowerBound;
	}
	private BigDecimal nativeCostBound(int owner) {
		BigDecimal min=null;
		for(int rule=domains[owner].nextSetBit(0); rule>=0; rule=domains[owner].nextSetBit(rule+1))
			min=minimum(min,nativeCosts[owner][rule]);
		return Objects.requireNonNull(min);
	}
	private BigDecimal currentTermBound(int id) {
		if(termBounds[id]==null || dirtyTermBounds.get(id)) refreshTermBound(id);
		return termBounds[id];
	}
	private void refreshTermBound(int id) {
		BigDecimal old=termBounds[id],next=computeConditionalCostBound(id);
		dirtyTermBounds.clear(id);
		termBounds[id]=next;
		if(sameBoundRepresentation(old,next)) return;
		if(lowerBoundInitialized)
			cachedLowerBound=cachedLowerBound.add(next).subtract(old==null ? BigDecimal.ZERO : old);
	}
	private static boolean sameBoundRepresentation(BigDecimal left,BigDecimal right) {
		return left==right || left!=null && left.equals(right);
	}

	/**
	 * Minimize one small lazy term over its current projected domains. This is
	 * a relaxation: other terms and constraints cannot make its minimum larger
	 * than a feasible completion's term. A wide term keeps its certified bound;
	 * partial enumeration is never presented as a lower-bound proof.
	 */
	private BigDecimal computeConditionalCostBound(int id) {
		conditionalBoundQueries++;
		CostFactor term=terms.get(id);
		BigDecimal cached=cachedDomains(boundCache,id,-1,-1,term.owners);
		if(cached!=null) { conditionalBoundCacheHits++; return cached; }
		long combinations=1;
		for(int owner : term.owners) {
			int size=domainCardinalities[owner];
			if(size==0) throw new IllegalStateException("Empty domain reached a cost bound");
			if(combinations>limits.supportEvaluations()/size) { wideBoundRelaxations++; return term.certifiedMinimum; }
			combinations*=size;
		}
		BigDecimal minimum=minimumTerm(id,0,new int[term.owners.length]);
		boundCache.put(new ScopedDomainKey(id,-1,-1,term.owners,domains,compactDomains,wideDomains),minimum);
		return minimum;
	}
	private BigDecimal minimumTerm(int id,int position,int[] values) {
		CostFactor term=terms.get(id);
		if(position==values.length) return evaluateCost(id,values);
		BigDecimal min=null;
		int owner=term.owners[position];
		for(int value=domains[owner].nextSetBit(0); value>=0; value=domains[owner].nextSetBit(value+1)) {
			values[position]=value;
			min=minimum(min,minimumTerm(id,position+1,values));
			if(min.compareTo(term.certifiedMinimum)==0) break;
		}
		return Objects.requireNonNull(min);
	}
	private int chooseOwner() {
		int selectedOwner = -1, connectivity = -1;
		for(int i = 0; i < domains.length; i++) {
			int size = domainCardinalities[i];
			if(size <= 1) continue;
			int candidateConnectivity=branchConnectivity(i);
			if(selectedOwner<0 || candidateConnectivity>connectivity
				|| candidateConnectivity==connectivity && costIncident[i].length>costIncident[selectedOwner].length
				|| candidateConnectivity==connectivity && costIncident[i].length==costIncident[selectedOwner].length
					&& incident[i].length>incident[selectedOwner].length
				|| candidateConnectivity==connectivity && costIncident[i].length==costIncident[selectedOwner].length
					&& incident[i].length==incident[selectedOwner].length
					&& size<domainCardinalities[selectedOwner]) {
				selectedOwner=i; connectivity=candidateConnectivity;
			}
		}
		return selectedOwner;
	}
	private int branchConnectivity(int owner) {
		assert activeBranchNeighbors[owner]==scanBranchConnectivity(owner);
		return activeBranchNeighbors[owner];
	}
	private int scanBranchConnectivity(int owner) {
		int count=0;
		for(int adjacent : branchAdjacent[owner])
			if(domainCardinalities[adjacent]>1) count++;
		return count;
	}
	private int lexicographicDomainFloor(int[] best) {
		for(int owner=0; owner<domains.length; owner++) {
			int comparison=Integer.compare(domains[owner].nextSetBit(0),best[owner]);
			if(comparison!=0) return comparison;
		}
		return 0;
	}
	private void remove(int owner, int rule) {
		assert domains[owner].get(rule);
		domains[owner].clear(rule); trail.add(new int[] {owner, rule}); removedRules++;
		if(!wideDomains[owner]) compactDomains[owner]&=~(1L<<rule);
		domainCardinalities[owner]--;
		if(domainCardinalities[owner]==1) for(int adjacent:branchAdjacent[owner]) {
			activeBranchNeighbors[adjacent]--;
			assert activeBranchNeighbors[adjacent]==scanBranchConnectivity(adjacent);
		}
		assert domainCardinalities[owner]==domains[owner].cardinality();
		assert wideDomains[owner] || compactDomains[owner]==compactMask(domains[owner]);
		revisions[owner]++;
		invalidateCostBounds(owner);
		for(int check : incident[owner]) enqueue(check);
	}
	private void undo(int mark) {
		while(trail.size() > mark) {
			int[] entry = trail.remove(trail.size() - 1);
			assert !domains[entry[0]].get(entry[1]);
			domains[entry[0]].set(entry[1]); revisions[entry[0]]++;
			if(!wideDomains[entry[0]]) compactDomains[entry[0]]|=1L<<entry[1];
			domainCardinalities[entry[0]]++;
			if(domainCardinalities[entry[0]]==2) for(int adjacent:branchAdjacent[entry[0]]) {
				activeBranchNeighbors[adjacent]++;
				assert activeBranchNeighbors[adjacent]==scanBranchConnectivity(adjacent);
			}
			assert domainCardinalities[entry[0]]==domains[entry[0]].cardinality();
			assert wideDomains[entry[0]] || compactDomains[entry[0]]==compactMask(domains[entry[0]]);
			invalidateCostBounds(entry[0]);
			for(int check : incident[entry[0]]) enqueue(check);
		}
	}
	private static long compactMask(BitSet domain) {
		long[] words=domain.toLongArray();
		return words.length==0 ? 0 : words[0];
	}
	private void invalidateCostBounds(int owner) {
		dirtyOwnerBounds.set(owner);
		for(int id : costIncident[owner]) dirtyTermBounds.set(id);
		if(activeReducedSearch!=null) activeReducedSearch.invalidate(owner);
	}
	private void enqueue(int check) {
		if(!queued.get(check)) { queue.addLast(check); queued.set(check); }
	}
	private long[] stamp(Check check) {
		long[] result = new long[check.owners.length];
		for(int i = 0; i < result.length; i++) result[i] = revisions[check.owners[i]];
		return result;
	}
	private int[] currentAssignment() {
		int[] result = new int[domains.length];
		for(int i = 0; i < result.length; i++)
			result[i] = domainCardinalities[i] == 1 ? domains[i].nextSetBit(0) : -1;
		return result;
	}
	private boolean complete() {
		for(boolean ready : coverage) if(!ready) return false;
		return true;
	}
	private static boolean[] coverage(RuleMemo memo) {
		boolean[] result = new boolean[memo.ownerCount()];
		for(int i = 0; i < result.length; i++) result[i] = memo.complete(i);
		return result;
	}
	private void validateOwner(int owner) {
		if(owner < 0 || owner >= domainSizes.length) throw new IllegalArgumentException("Unknown owner " + owner);
	}
	private Statistics statistics() {
		return new Statistics(nodes,checkEvaluations,checkCacheHits,supportQueries,supportCacheHits,worklistVisits,
			removedRules,costEvaluations,costCacheHits,boundPrunes,conditionalBoundQueries,
			conditionalBoundCacheHits,wideBoundRelaxations,forestTailAttempts,forestTailApplicable,
			forestTailCompletions,forestTailCells);
	}
	private static int[] uniqueScope(int[] owners) {
		int[] copy = owners.clone();
		if(Arrays.stream(copy).distinct().count() != copy.length)
			throw new IllegalArgumentException("A shared owner appears once in a condition scope");
		return copy;
	}
	private static int[] project(int[] scope, int[] assignment) {
		int[] result = new int[scope.length];
		for(int i = 0; i < scope.length; i++) result[i] = assignment[scope[i]];
		return result;
	}
	private static BigDecimal exact(double value) {
		if(!Double.isFinite(value)) throw new IllegalArgumentException("Costs must be finite: " + value);
		return new BigDecimal(value); // preserve the exact dyadic value of each binary64 term
	}
	private static BigDecimal minimum(BigDecimal a, BigDecimal b) {
		return a == null || a.compareTo(b) > 0 ? b : a;
	}
	private static int lexicographic(int[] a, int[] b) {
		for(int i = 0; i < a.length; i++) {
			int cmp = Integer.compare(a[i],b[i]); if(cmp != 0) return cmp;
		}
		return 0;
	}
	private static <K,V> Map<K,V> boundedCache(int capacity) {
		return new LinkedHashMap<>() {
			private static final long serialVersionUID = 1L;
			@Override protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
				return size() > capacity;
			}
		};
	}
	private static final class ScopeKey {
		private final int[] owners;
		private final int hash;
		private ScopeKey(int[] owners) { this.owners=owners.clone(); hash=Arrays.hashCode(owners); }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			return other instanceof ScopeKey key && Arrays.equals(owners,key.owners);
		}
	}
	/** Lookup borrows the tuple only during Map.get, before any predicate/evaluator runs. */
	private <V> V cached(Map<TupleKey,V> cache,int id,int[] values) {
		lookupProbe.id=id; lookupProbe.values=values;
		lookupProbe.hash=tupleHash(id,values);
		try { return cache.get(lookupProbe); }
		finally { lookupProbe.id=0; lookupProbe.values=null; lookupProbe.hash=0; }
	}
	private static int tupleHash(int id,int[] values) { return 31*id+Arrays.hashCode(values); }
	/** Domain lookup borrows mutable solver state only during Map.get. */
	private <V> V cachedDomains(Map<ScopedDomainKey,V> cache,int id,int fixedOwner,int fixedValue,int[] scope) {
		domainProbe.id=id; domainProbe.fixedOwner=fixedOwner; domainProbe.fixedValue=fixedValue;
		domainProbe.scope=scope; domainProbe.domains=domains;
		domainProbe.compactDomains=compactDomains; domainProbe.wideOwners=wideDomains;
		domainProbe.hash=scopedDomainHash(domainProbe);
		try { return cache.get(domainProbe); }
		finally {
			domainProbe.id=0; domainProbe.fixedOwner=0; domainProbe.fixedValue=0;
			domainProbe.scope=null; domainProbe.domains=null; domainProbe.compactDomains=null;
			domainProbe.wideOwners=null; domainProbe.hash=0;
		}
	}
	private static int scopedDomainHash(ScopedDomainIdentity value) {
		int hash=mixDomainHash(0x6d2b79f5,value.id());
		hash=mixDomainHash(hash,value.fixedOwner());
		hash=mixDomainHash(hash,value.fixedValue());
		for(int position=0;position<value.domainCount();position++) {
			hash=mixDomainHash(hash,value.wide(position) ? 0x77696465L : 0x636f6d70L);
			if(value.wide(position)) {
				BitSet domain=value.wideDomain(position);
				hash=mixDomainHash(hash,domain.hashCode());
			}
			else hash=mixDomainHash(hash,value.compactDomain(position));
		}
		return hash;
	}
	private static int mixDomainHash(int hash,long value) {
		long mixed=value;
		mixed^=mixed>>>33; mixed*=0xff51afd7ed558ccdL;
		mixed^=mixed>>>33; mixed*=0xc4ceb9fe1a85ec53L; mixed^=mixed>>>33;
		return Integer.rotateLeft(hash,13)*5+0xe6546b64 ^ (int)(mixed^(mixed>>>32));
	}
	private interface ScopedDomainIdentity {
		int id();
		int fixedOwner();
		int fixedValue();
		int domainCount();
		boolean wide(int position);
		long compactDomain(int position);
		BitSet wideDomain(int position);
		default boolean sameDomains(Object other) {
			if(!(other instanceof ScopedDomainIdentity value) || id()!=value.id()
				|| fixedOwner()!=value.fixedOwner() || fixedValue()!=value.fixedValue()
				|| domainCount()!=value.domainCount()) return false;
			for(int position=0;position<domainCount();position++) {
				if(wide(position)!=value.wide(position)) return false;
				if(wide(position) ? !wideDomain(position).equals(value.wideDomain(position))
					: compactDomain(position)!=value.compactDomain(position)) return false;
			}
			return true;
		}
	}
	/** Never inserted; scoped domains cannot mutate during the synchronous Map.get. */
	private static final class ScopedDomainProbe implements ScopedDomainIdentity {
		private int id;
		private int fixedOwner;
		private int fixedValue;
		private int[] scope;
		private BitSet[] domains;
		private long[] compactDomains;
		private boolean[] wideOwners;
		private int hash;
		@Override public int id() { return id; }
		@Override public int fixedOwner() { return fixedOwner; }
		@Override public int fixedValue() { return fixedValue; }
		@Override public int domainCount() { return scope.length; }
		@Override public boolean wide(int position) { return wideOwners[scope[position]]; }
		@Override public long compactDomain(int position) { return compactDomains[scope[position]]; }
		@Override public BitSet wideDomain(int position) { return domains[scope[position]]; }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) { return sameDomains(other); }
	}
	private static final class ScopedDomainKey implements ScopedDomainIdentity {
		private final int id;
		private final int fixedOwner;
		private final int fixedValue;
		private final long[] compactDomains;
		private final BitSet[] wideDomains;
		private final int hash;
		private ScopedDomainKey(int id,int fixedOwner,int fixedValue,int[] scope,BitSet[] current,
			long[] currentCompact,boolean[] currentWide) {
			this.id=id; this.fixedOwner=fixedOwner; this.fixedValue=fixedValue;
			compactDomains=new long[scope.length]; wideDomains=new BitSet[scope.length];
			for(int position=0;position<scope.length;position++) {
				int owner=scope[position];
				if(currentWide[owner]) wideDomains[position]=(BitSet)current[owner].clone();
				else compactDomains[position]=currentCompact[owner];
			}
			hash=scopedDomainHash(this);
		}
		@Override public int id() { return id; }
		@Override public int fixedOwner() { return fixedOwner; }
		@Override public int fixedValue() { return fixedValue; }
		@Override public int domainCount() { return compactDomains.length; }
		@Override public boolean wide(int position) { return wideDomains[position]!=null; }
		@Override public long compactDomain(int position) { return compactDomains[position]; }
		@Override public BitSet wideDomain(int position) { return wideDomains[position]; }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) { return sameDomains(other); }
	}
	private interface TupleIdentity {
		int id();
		int[] values();
		default boolean sameTuple(Object other) {
			return other instanceof TupleIdentity tuple && id()==tuple.id() && Arrays.equals(values(),tuple.values());
		}
	}
	/** Never inserted in a cache; one optimizer invocation owns this synchronous lookup scratch. */
	private static final class TupleProbe implements TupleIdentity {
		private int id;
		private int[] values;
		private int hash;
		@Override public int id() { return id; }
		@Override public int[] values() { return values; }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) { return sameTuple(other); }
	}
	private static final class TupleKey implements TupleIdentity {
		private final int id;
		private final int[] values;
		private final int hash;
		private TupleKey(int id, int[] values) {
			this.id = id; this.values = values.clone(); hash = tupleHash(id,values);
		}
		@Override public int id() { return id; }
		@Override public int[] values() { return values; }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) { return sameTuple(other); }
	}
}
