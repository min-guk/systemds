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
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Lazy exact selection over the immutable native universe. Conditions are attached
 * to existing use/boundary sites; no source products or physical alternatives are built.
 * An owner is a single variable even when several occurrences or inputs use it.
 */
public final class MemoOptimizer {
	public enum Truth { YES, NO, UNKNOWN }
	public enum Status { OPTIMAL, EPSILON_OPTIMAL, INFEASIBLE, UNRESOLVED, RESOURCE_EXHAUSTED }
	@FunctionalInterface public interface Predicate {
		/** -1 means unassigned; YES/NO must hold for every completion of that prefix. */
		Truth test(int[] scopedRules);
	}
	public static final class Check {
		private final int site;
		private final int[] owners;
		private final Predicate predicate;
		private final int[][] truthClasses;
		public Check(int site, int[] owners, Predicate predicate) {
			this(site,owners,predicate,null);
		}
		/** Caller-supplied proof: ordinals in one class have identical complete-tuple
		 * Truth, including UNKNOWN, for every combination of all other owner classes.
		 * Partial tuples continue to call the original predicate with actual ordinals. */
		public Check(int site,int[] owners,Predicate predicate,int[][] truthClasses) {
			this.site = site;
			this.owners = uniqueScope(owners);
			this.predicate = Objects.requireNonNull(predicate);
			this.truthClasses=validateClasses(this.owners,truthClasses,"Truth");
		}
		public int site() { return site; }
		/** Read-only factor evaluation shared by independently selectable optimizers. */
		Truth evaluate(int[] scopedRules) { return predicate.test(scopedRules.clone()); }
		public int[] owners() { return owners.clone(); }
	}
	/** A term has exactly one cost owner. A shared movement is one term with several demands.
	 * The evaluator is pure and finite for every complete scoped tuple, including
	 * infeasible tuples used in a relaxation; certifiedMinimum bounds all of them. */
	public static final class CostTerm {
		private final int[] owners;
		private final ToDoubleFunction<int[]> evaluator;
		private final BigDecimal certifiedMinimum;
		private final int[][] costClasses;
		public CostTerm(int[] owners, ToDoubleFunction<int[]> evaluator, double certifiedMinimum) {
			this(owners,evaluator,certifiedMinimum,null);
		}
		/** Optional immutable classes certify identical complete-tuple term values for
		 * each owner's ordinals in the same class, for every combination of other owners.
		 * They do not identify rules for feasibility, native cost or solution selection. */
		public CostTerm(int[] owners,ToDoubleFunction<int[]> evaluator,double certifiedMinimum,
			int[][] costClasses) {
			this.owners = uniqueScope(owners);
			this.evaluator = Objects.requireNonNull(evaluator);
			this.certifiedMinimum = exact(certifiedMinimum);
			this.costClasses=validateCostClasses(this.owners,costClasses);
		}
		public int[] owners() { return owners.clone(); }
		BigDecimal certifiedMinimum() { return certifiedMinimum; }
		int[][] certifiedCostClasses() { return copyCostClasses(costClasses); }
		BigDecimal evaluateExact(int[] scopedRules) {
			return exact(evaluator.applyAsDouble(scopedRules.clone()));
		}
	}
	private static final class CostFactor {
		private final int[] owners;
		private final Function<int[],BigDecimal> evaluator;
		private final BigDecimal certifiedMinimum;
		private final int[][] costClasses;
		private CostFactor(int[] owners,Function<int[],BigDecimal> evaluator,BigDecimal certifiedMinimum,
			int[][] costClasses) {
			this.owners=uniqueScope(owners); this.evaluator=Objects.requireNonNull(evaluator);
			this.certifiedMinimum=Objects.requireNonNull(certifiedMinimum);
			this.costClasses=copyCostClasses(costClasses);
		}
		private static CostFactor from(CostTerm term,DoubleFunction<BigDecimal> converter) {
			return new CostFactor(term.owners,values -> converter.apply(
				term.evaluator.applyAsDouble(values.clone())),term.certifiedMinimum,term.costClasses);
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
		long supportQueries, long supportCacheHits, long supportWitnessHits, long binarySupportHits,
		long binarySupportRows, long worklistVisits,
		long removedRules, long costEvaluations,
		long costCacheHits, long boundPrunes, long conditionalBoundQueries, long conditionalBoundCacheHits,
		long wideBoundRelaxations,long forestTailAttempts,long forestTailApplicable,long forestTailCompletions,
		long forestTailCells,long annotatedChecks,long invariantCheckAxes,long binaryChecksReduced,
		long branchEdgesRemovedDuringNormalization,long eliminationVariables,long eliminationCells,
		long eliminationRows,long eliminationFactorHits) {}
	public record Result(Status status, BigDecimal cost, int[] selectedRules, Statistics statistics) {
		public Result { selectedRules = selectedRules == null ? null : selectedRules.clone(); }
		@Override public int[] selectedRules() { return selectedRules == null ? null : selectedRules.clone(); }
	}
	/** Result of the optional direct exact kernel.  An incomplete result has not
	 * changed the candidate universe and may be covered by another exact search. */
	public record DirectResult(boolean completed,Result result,BitSet[] domains,String kernel) {
		public DirectResult {
			result=Objects.requireNonNull(result);
			domains=cloneDomains(domains);
			kernel=Objects.requireNonNull(kernel);
		}
		@Override public BitSet[] domains() { return cloneDomains(domains); }
	}

	private final int[] domainSizes;
	private final int[] domainCardinalities;
	private final boolean[] coverage;
	private List<Check> checks;
	private List<CostFactor> terms;
	private final BigDecimal[][] nativeCosts;
	private final Limits limits;
	private final Map<Long,BigDecimal> exactValues;
	private final int exactValueCacheLimit;
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
	private final Map<SupportWitnessKey,int[]> supportWitnesses;
	private long[][][] binarySupports;
	private int binarySupportRows;
	private final TupleProbe lookupProbe = new TupleProbe();
	private final ScopedDomainProbe domainProbe = new ScopedDomainProbe();
	private final SupportWitnessProbe witnessProbe = new SupportWitnessProbe();
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
	private int[] foundSupportWitness;
	private boolean exhausted;
	private boolean optimized;
	private long nodes, checkEvaluations, checkCacheHits, supportQueries, supportCacheHits,
		supportWitnessHits, binarySupportHits, worklistVisits,
		removedRules, costEvaluations, costCacheHits, boundPrunes, conditionalBoundQueries,
		conditionalBoundCacheHits, wideBoundRelaxations,forestTailAttempts,forestTailApplicable,
		forestTailCompletions,forestTailCells,annotatedChecks,invariantCheckAxes,binaryChecksReduced,
		branchEdgesRemovedDuringNormalization,eliminationVariables,eliminationCells,eliminationRows,eliminationFactorHits;

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
			if(check.truthClasses!=null) for(int position=0;position<check.owners.length;position++)
				if(check.truthClasses[position].length!=domainSizes[check.owners[position]])
					throw new IllegalArgumentException("Truth classes must match immutable rule domain");
			int[] reduced = nonConstantScope(check.owners,domainSizes);
			return reduced.length == check.owners.length ? check : new Check(check.site,reduced,
				values -> check.predicate.test(expandConstantScope(check.owners,domainSizes,values)),
				projectClasses(check.owners,reduced,check.truthClasses));
		}).toList();
		annotatedChecks=this.checks.stream().filter(check -> check.truthClasses!=null).count();
		this.terms = terms.stream().map(term -> {
			if(term.costClasses!=null) for(int position=0;position<term.owners.length;position++)
				if(term.costClasses[position].length!=domainSizes[term.owners[position]])
					throw new IllegalArgumentException("Cost classes must match immutable rule domain");
			CostFactor factor=CostFactor.from(term,this::cachedExact);
			int[] reduced = nonConstantScope(term.owners,domainSizes);
			return reduced.length == term.owners.length ? factor : new CostFactor(reduced,
				values -> factor.evaluator.apply(expandConstantScope(term.owners,domainSizes,values)),
				factor.certifiedMinimum,projectCostClasses(term.owners,reduced,factor.costClasses));
		}).toList();
		this.limits = Objects.requireNonNull(limits);
		exactValueCacheLimit=Math.min(4096,limits.cacheEntries());
		exactValues=new HashMap<>(Math.max(1,exactValueCacheLimit));
		if(nativeCosts.length != domainSizes.length)
			throw new IllegalArgumentException("Native costs must match owner count");
		this.nativeCosts = new BigDecimal[nativeCosts.length][];
		for(int owner = 0; owner < nativeCosts.length; owner++) {
			if(nativeCosts[owner].length != domainSizes[owner])
				throw new IllegalArgumentException("Native costs must match immutable rule domain");
			this.nativeCosts[owner] = Arrays.stream(nativeCosts[owner]).mapToObj(this::cachedExact)
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
		supportWitnesses = boundedCache(limits.cacheEntries());
		binarySupports = new long[this.checks.size()][][];
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
		if(feasible && !tryTreeSolve(null) && !tryEliminationSolve() && !tryLeafRegionSolve()) search();
		Status status = exhausted ? Status.RESOURCE_EXHAUSTED
			: !complete() || unresolvedLowerBound != null &&
				(incumbent == null || unresolvedLowerBound.compareTo(incumbent) <= 0) ? Status.UNRESOLVED
			: selected == null ? Status.INFEASIBLE : Status.OPTIMAL;
		return new Result(status, incumbent, selected, statistics());
	}

	/**
	 * Runs only propagation followed by an admitted tree or class-aware bucket
	 * elimination kernel.  It does not create an incumbent seed, search frontier,
	 * leaf-region search, or approximate proof.  A rejection/UNKNOWN is an
	 * incomplete handoff with the sound propagated domains in original ordinals.
	 */
	public DirectResult optimizeDirect(BitSet[] restrictions,MemoResourcePolicy resources) {
		return optimizeDirect(restrictions,resources,Long.MAX_VALUE);
	}

	/** A work-selector overload; exceeding the cell estimate declines this kernel, not the problem. */
	public DirectResult optimizeDirect(BitSet[] restrictions,MemoResourcePolicy resources,
		long maxEliminationCells) {
		if(optimized) throw new IllegalStateException("Optimizer invocation already consumed");
		if(maxEliminationCells<0) throw new IllegalArgumentException("Negative elimination cell limit");
		optimized=true;
		resources=Objects.requireNonNull(resources);
		applyRestrictions(restrictions);
		if(!complete()) return direct(false,Status.UNRESOLVED,"coverage");
		for(int c=0;c<checks.size();c++) enqueue(c);
		boolean feasible=Arrays.stream(domains).noneMatch(BitSet::isEmpty) && propagate()
			&& Arrays.stream(domains).noneMatch(BitSet::isEmpty);
		if(!feasible) return direct(true,Status.INFEASIBLE,"propagation");
		normalizeAfterPropagation();
		for(int id=0;id<checks.size();id++)
			if(checks.get(id).owners.length==0 && evaluate(id,new int[0])==Truth.UNKNOWN)
				return direct(false,Status.UNRESOLVED,"unknown");

		long treeBytes=estimateTreeBytes();
		if(treeBytes>=0 && resources.admit(treeBytes) && tryTreeSolve(resources))
			return direct(true,selected==null ? Status.INFEASIBLE : Status.OPTIMAL,"tree");

		if(!resources.admit(estimateEliminationPreparationBytes()))
			return direct(false,Status.UNRESOLVED,"none");
		EliminationPlan plan;
		boolean prepared;
		try {
			plan=new EliminationPlan(resources,maxEliminationCells);
			prepared=plan.prepare();
		}
		catch(OutOfMemoryError allocationFailure) {
			// Planning invokes no user evaluator; failure here is confined to the
			// admitted class/graph workspace and publishes no partial message.
			return direct(false,Status.UNRESOLVED,"none");
		}
		if(prepared && plan.solve())
			return direct(true,selected==null ? Status.INFEASIBLE : Status.OPTIMAL,"elimination");
		return direct(false,Status.UNRESOLVED,"none");
	}

	private DirectResult direct(boolean completed,Status status,String kernel) {
		return new DirectResult(completed,new Result(status,incumbent,selected,statistics()),domains,kernel);
	}

	private void applyRestrictions(BitSet[] restrictions) {
		if(restrictions==null) return;
		if(restrictions.length!=domains.length)
			throw new IllegalArgumentException("Restriction/domain mismatch");
		for(int owner=0;owner<domains.length;owner++) {
			BitSet allowed=restrictions[owner];
			if(allowed==null) continue;
			if(allowed.length()>domainSizes[owner])
				throw new IllegalArgumentException("Restriction contains unknown ordinal for owner "+owner);
			domains[owner].and((BitSet)allowed.clone());
			domainCardinalities[owner]=domains[owner].cardinality();
			if(!wideDomains[owner]) compactDomains[owner]=compact(domains[owner]);
			revisions[owner]++;
		}
		// Restriction updates bypass remove(); refresh its live-neighbor invariant
		// before propagation can transition another owner to singleton.
		for(int owner=0;owner<domains.length;owner++)
			activeBranchNeighbors[owner]=scanBranchConnectivity(owner);
	}

	private static BitSet[] cloneDomains(BitSet[] values) {
		return Arrays.stream(values).map(bits -> (BitSet)bits.clone()).toArray(BitSet[]::new);
	}
	private static long compact(BitSet bits) {
		long[] words=bits.toLongArray();
		return words.length==0 ? 0 : words[0];
	}
	private long estimateEliminationPreparationBytes() {
		long bytes=MemoResourcePolicy.checkedMultiply(domains.length,512);
		for(BitSet domain:domains)
			bytes=MemoResourcePolicy.checkedAdd(bytes,
				MemoResourcePolicy.checkedMultiply(domain.cardinality(),Integer.BYTES));
		for(Check check:checks) bytes=estimateFactorPreparation(bytes,check.owners);
		for(CostFactor term:terms) bytes=estimateFactorPreparation(bytes,term.owners);
		return MemoResourcePolicy.checkedAdd(bytes,
			MemoResourcePolicy.checkedMultiply(limits.cacheEntries(),256));
	}
	private long estimateFactorPreparation(long bytes,int[] owners) {
		bytes=MemoResourcePolicy.checkedAdd(bytes,MemoResourcePolicy.checkedMultiply(owners.length,128));
		for(int owner:owners)
			bytes=MemoResourcePolicy.checkedAdd(bytes,
				MemoResourcePolicy.checkedMultiply(domainSizes[owner],Integer.BYTES));
		return bytes;
	}

	/** Exact bucket elimination over certified factor classes, not native-rule identities.
	 * A message's separator partition refines only its consumed factors. Future native
	 * costs remain outside the message. This preserves both its cost and lexicographic
	 * witness for every actual separator ordinal in the same certified class. */
	private boolean tryEliminationSolve() {
		// Keep the existing finite-node budget traversal and its resource semantics.
		if(limits.searchNodes()!=Long.MAX_VALUE) return false;
		EliminationPlan plan=new EliminationPlan(null,EliminationPlan.MAX_CELLS);
		return plan.prepare() && plan.solve();
	}

	private final class EliminationPlan {
		private static final long MAX_CELLS=2_000_000,MAX_ROWS=500_000;
		private static final long MAX_ARRAY_ROWS=Integer.MAX_VALUE-8L;
		private final MemoResourcePolicy resources;
		private final long maxEliminationCells;
		private final int[][] live=Arrays.stream(domains).map(bits -> bits.stream().toArray()).toArray(int[][]::new);
		private final List<EliminationFactor> originals=new ArrayList<>();
		private final List<EliminationBucket> order=new ArrayList<>(),roots=new ArrayList<>();
		private final int[] assignment=currentAssignment();
		private final int[] tieLeft=new int[domains.length],tieRight=new int[domains.length];
		private long plannedRows,factorRows;
		private EliminationPlan(MemoResourcePolicy resources,long maxEliminationCells) {
			this.resources=resources;
			this.maxEliminationCells=maxEliminationCells;
		}

		private final class EliminationFactor {
			private final int[] owners;
			private final int[][] classes;
			private final int[] strides;
			private final int check,term;
			private final EliminationBucket message;
			private final int rows;
			private Truth[] truths;
			private BigDecimal[] costs;
			private EliminationFactor(int[] owners,int[][] proof,int check,int term,EliminationBucket message) {
				this.owners=owners; this.check=check; this.term=term; this.message=message;
				classes=new int[owners.length][]; strides=new int[owners.length];
				long size=1;
				for(int axis=owners.length-1;axis>=0;axis--) {
					int owner=owners[axis];
					classes[axis]=new int[domainSizes[owner]]; Arrays.fill(classes[axis],-1);
					Map<Integer,Integer> labels=new LinkedHashMap<>();
					for(int rule:live[owner]) {
						int label=proof==null ? rule : proof[axis][rule];
						classes[axis][rule]=labels.computeIfAbsent(label,ignored -> labels.size());
					}
					strides[axis]=(int)Math.min(size,rowLimit()+1);
					size=boundedProduct(size,labels.size());
				}
				rows=(int)Math.min(size,rowLimit()+1);
			}
			private int index(int[] selected) {
				int index=0;
				for(int axis=0;axis<owners.length;axis++) index+=classes[axis][selected[owners[axis]]]*strides[axis];
				return index;
			}
			private Truth truth() {
				int index=index(assignment);
				if(truths[index]!=null) { eliminationFactorHits++; return truths[index]; }
				return truths[index]=evaluate(check,project(owners,assignment));
			}
			private BigDecimal cost() {
				int index=index(assignment);
				if(message!=null) return message.values[index];
				if(costs[index]!=null) { eliminationFactorHits++; return costs[index]; }
				return costs[index]=evaluateCost(term,project(owners,assignment));
			}
		}

		private final class EliminationBucket {
			private final int owner;
			private final List<EliminationFactor> inputs;
			private final int[] separator;
			private final int[][] classes,representatives;
			private final int ownerClasses;
			private final long rowCount,score;
			private final int[] witnessOwners;
			private BigDecimal[] values;
			private int[] choices;
			private EliminationFactor output;
			private EliminationBucket(int owner,List<EliminationFactor> inputs,BitSet[] graph) {
				this.owner=owner; this.inputs=List.copyOf(inputs);
				BitSet scope=new BitSet(),witness=new BitSet(); witness.set(owner);
				for(EliminationFactor factor:inputs) {
					for(int axis:factor.owners) scope.set(axis);
					if(factor.message!=null) for(int axis:factor.message.witnessOwners) witness.set(axis);
				}
				witnessOwners=witness.stream().toArray();
				scope.clear(owner); separator=scope.stream().toArray();
				classes=new int[separator.length][]; representatives=new int[separator.length][];
				long rows=1;
				for(int axis=0;axis<separator.length;axis++) {
					classes[axis]=refine(separator[axis],inputs);
					representatives[axis]=representatives(separator[axis],classes[axis]);
					rows=boundedProduct(rows,representatives[axis].length);
				}
				rowCount=rows;
				ownerClasses=representatives(owner,refine(owner,inputs)).length;
				long missing=0;
				for(int a=0;a<separator.length;a++) for(int b=a+1;b<separator.length;b++)
					if(!graph[separator[a]].get(separator[b]))
						missing+=(long)representatives[a].length*representatives[b].length;
				score=missing;
			}
			private void selectRow(int row) {
				for(int axis=separator.length-1;axis>=0;axis--) {
					int[] reps=representatives[axis];
					assignment[separator[axis]]=reps[row%reps.length]; row/=reps.length;
				}
			}
			private void restore(int row,int[] selected) {
				if(resources==null) {
					selected[owner]=choices[row];
					for(EliminationFactor factor:inputs) if(factor.message!=null)
						factor.message.restore(factor.index(selected),selected);
				}
				else EliminationPlan.this.restore(this,row,selected);
			}
			private boolean lexLess(int candidate,int retained) {
				for(int axis:separator) tieLeft[axis]=tieRight[axis]=assignment[axis];
				tieLeft[owner]=candidate; tieRight[owner]=retained;
				for(EliminationFactor factor:inputs) if(factor.message!=null) {
					factor.message.restore(factor.index(tieLeft),tieLeft);
					factor.message.restore(factor.index(tieRight),tieRight);
				}
				for(int axis:witnessOwners) if(tieLeft[axis]!=tieRight[axis]) return tieLeft[axis]<tieRight[axis];
				return false;
			}
		}
		private EliminationBucket[] restoreBuckets;
		private int[] restoreRows;
		/** Message dependencies follow elimination order; an explicit stack avoids depth overflow. */
		private void restore(EliminationBucket root,int row,int[] solution) {
			int top=0; restoreBuckets[0]=root; restoreRows[0]=row;
			while(top>=0) {
				EliminationBucket bucket=restoreBuckets[top];
				solution[bucket.owner]=bucket.choices[restoreRows[top--]];
				for(int i=bucket.inputs.size()-1;i>=0;i--) {
					EliminationFactor factor=bucket.inputs.get(i);
					if(factor.message!=null) {
						restoreBuckets[++top]=factor.message; restoreRows[top]=factor.index(solution);
					}
				}
			}
		}

		private long boundedProduct(long left,int right) {
			long cap=resources==null ? MAX_CELLS : MAX_ARRAY_ROWS;
			return right==0 ? 0 : left>(cap+1)/right ? cap+1 : left*right;
		}
		private long rowLimit() { return resources==null ? MAX_ROWS : MAX_ARRAY_ROWS; }
		private int[] refine(int owner,List<EliminationFactor> factors) {
			List<int[]> mappings=new ArrayList<>();
			for(EliminationFactor factor:factors) for(int axis=0;axis<factor.owners.length;axis++)
				if(factor.owners[axis]==owner) mappings.add(factor.classes[axis]);
			int[] result=new int[domainSizes[owner]]; Arrays.fill(result,-1);
			List<Integer> leaders=new ArrayList<>();
			for(int rule:live[owner]) {
				int found=-1;
				for(int label=0;label<leaders.size() && found<0;label++) {
					int prior=leaders.get(label); boolean same=true;
					for(int[] mapping:mappings) if(mapping[rule]!=mapping[prior]) { same=false; break; }
					if(same) found=label;
				}
				if(found<0) { found=leaders.size(); leaders.add(rule); }
				result[rule]=found;
			}
			return result;
		}
		private int[] representatives(int owner,int[] classes) {
			int count=0; for(int rule:live[owner]) count=Math.max(count,classes[rule]+1);
			int[] reps=new int[count]; Arrays.fill(reps,-1);
			for(int rule:live[owner]) if(reps[classes[rule]]<0) reps[classes[rule]]=rule;
			return reps;
		}
		private boolean better(EliminationBucket candidate,EliminationBucket best) {
			if(best==null || candidate.score!=best.score) return best==null || candidate.score<best.score;
			long left=boundedProduct(candidate.rowCount,candidate.ownerClasses);
			long right=boundedProduct(best.rowCount,best.ownerClasses);
			if(left!=right) return left<right;
			if(candidate.separator.length!=best.separator.length)
				return candidate.separator.length<best.separator.length;
			return candidate.owner<best.owner;
		}
		private boolean prepare() {
			factorRows=0;
			for(int id=0;id<checks.size();id++) {
				Check check=checks.get(id);
				originals.add(new EliminationFactor(check.owners,check.truthClasses,id,-1,null));
			}
			for(int id=0;id<terms.size();id++) {
				CostFactor term=terms.get(id);
				originals.add(new EliminationFactor(term.owners,term.costClasses,-1,id,null));
			}
			List<List<EliminationFactor>> incidentFactors=lists(domains.length);
			for(EliminationFactor factor:originals) {
				factorRows=MemoResourcePolicy.checkedAdd(factorRows,factor.rows);
				if(factorRows>rowLimit()) return false;
				for(int owner:factor.owners) incidentFactors.get(owner).add(factor);
			}
			BitSet remaining=new BitSet(); BitSet[] graph=new BitSet[domains.length];
			for(int owner=0;owner<domains.length;owner++) {
				graph[owner]=new BitSet();
				if(live[owner].length>1) remaining.set(owner);
				for(int next:branchAdjacent[owner]) graph[owner].set(next);
			}
			long cells=0;
			while(!remaining.isEmpty()) {
				EliminationBucket best=null;
				for(int owner=remaining.nextSetBit(0);owner>=0;owner=remaining.nextSetBit(owner+1)) {
					EliminationBucket candidate=new EliminationBucket(owner,incidentFactors.get(owner),graph);
					if(better(candidate,best)) best=candidate;
				}
				cells=MemoResourcePolicy.checkedAdd(cells,boundedProduct(best.rowCount,live[best.owner].length));
				plannedRows=MemoResourcePolicy.checkedAdd(plannedRows,best.rowCount);
				if((resources==null && cells>MAX_CELLS) || (resources!=null && cells>maxEliminationCells)
					|| MemoResourcePolicy.checkedAdd(plannedRows,factorRows)>rowLimit()) return false;
				best.output=new EliminationFactor(best.separator,best.classes,-1,-1,best);
				for(EliminationFactor factor:best.inputs) for(int axis:factor.owners)
					incidentFactors.get(axis).remove(factor);
				for(int axis:best.separator) {
					incidentFactors.get(axis).add(best.output); graph[axis].clear(best.owner);
					for(int other:best.separator) if(axis!=other) graph[axis].set(other);
				}
				if(best.separator.length==0) roots.add(best);
				remaining.clear(best.owner); order.add(best);
			}
			return resources==null || resources.admit(estimateBytes());
		}
		private long estimateBytes() {
			long rows=MemoResourcePolicy.checkedAdd(plannedRows,factorRows);
			long tableBytes=MemoResourcePolicy.checkedMultiply(rows,96);
			long metadataBytes=MemoResourcePolicy.checkedMultiply(domains.length,192);
			for(EliminationFactor factor:originals)
				metadataBytes=MemoResourcePolicy.checkedAdd(metadataBytes,factorMetadataBytes(factor));
			for(EliminationBucket bucket:order) {
				metadataBytes=MemoResourcePolicy.checkedAdd(metadataBytes,
					MemoResourcePolicy.checkedMultiply(bucket.witnessOwners.length,Integer.BYTES));
				metadataBytes=MemoResourcePolicy.checkedAdd(metadataBytes,factorMetadataBytes(bucket.output));
				for(int axis=0;axis<bucket.separator.length;axis++) {
					metadataBytes=MemoResourcePolicy.checkedAdd(metadataBytes,
						MemoResourcePolicy.checkedMultiply(domainSizes[bucket.separator[axis]],Integer.BYTES));
					metadataBytes=MemoResourcePolicy.checkedAdd(metadataBytes,
						MemoResourcePolicy.checkedMultiply(bucket.representatives[axis].length,Integer.BYTES));
				}
			}
			long cacheBytes=MemoResourcePolicy.checkedMultiply(limits.cacheEntries(),256);
			return MemoResourcePolicy.checkedAdd(MemoResourcePolicy.checkedAdd(tableBytes,cacheBytes),metadataBytes);
		}
		private long factorMetadataBytes(EliminationFactor factor) {
			long bytes=MemoResourcePolicy.checkedMultiply(factor.owners.length,64);
			for(int owner:factor.owners)
				bytes=MemoResourcePolicy.checkedAdd(bytes,
					MemoResourcePolicy.checkedMultiply(domainSizes[owner],Integer.BYTES));
			return bytes;
		}
		private boolean solve() {
			if(resources!=null) {
				try {
					restoreBuckets=new EliminationBucket[order.size()]; restoreRows=new int[order.size()];
				}
				catch(OutOfMemoryError allocationFailure) { return false; }
			}
			for(EliminationFactor factor:originals) {
				try {
					if(factor.check>=0) factor.truths=new Truth[factor.rows];
					else factor.costs=new BigDecimal[factor.rows];
				}
				catch(OutOfMemoryError allocationFailure) {
					if(resources==null) throw allocationFailure;
					return false;
				}
			}
			BigDecimal total=fixedCost;
			for(int owner=0;owner<domains.length;owner++) if(live[owner].length==1)
				total=total.add(nativeCosts[owner][live[owner][0]]);
			for(EliminationFactor factor:originals) if(factor.owners.length==0) {
				if(factor.check>=0) {
					Truth truth=factor.truth();
					if(truth==Truth.UNKNOWN) return false;
					if(truth==Truth.NO) return true;
				}
				else total=total.add(factor.cost());
			}
			for(EliminationBucket bucket:order) {
				int rows=(int)bucket.rowCount;
				try {
					bucket.values=new BigDecimal[rows]; bucket.choices=new int[rows];
				}
				catch(OutOfMemoryError allocationFailure) {
					if(resources==null) throw allocationFailure;
					return false;
				}
				Arrays.fill(bucket.choices,-1);
				for(int row=0;row<rows;row++) {
					bucket.selectRow(row);
					for(int rule:live[bucket.owner]) {
						nodes++; eliminationCells++; assignment[bucket.owner]=rule;
						boolean feasible=true;
						for(EliminationFactor factor:bucket.inputs) {
							if(factor.message!=null && factor.cost()==null) { feasible=false; break; }
							if(factor.check>=0) {
								Truth truth=factor.truth();
								if(truth==Truth.UNKNOWN) return false;
								if(truth==Truth.NO) { feasible=false; break; }
							}
						}
						if(!feasible) continue;
						BigDecimal cost=nativeCosts[bucket.owner][rule];
						for(EliminationFactor factor:bucket.inputs) if(factor.check<0) cost=cost.add(factor.cost());
						BigDecimal best=bucket.values[row];
						int comparison=best==null ? -1 : cost.compareTo(best);
						if(comparison<0 || comparison==0 && bucket.lexLess(rule,bucket.choices[row])) {
							bucket.values[row]=cost; bucket.choices[row]=rule;
						}
					}
				}
				eliminationVariables++; eliminationRows+=rows;
			}
			int[] solution=currentAssignment();
			for(EliminationBucket root:roots) {
				if(root.values[0]==null) return true;
				total=total.add(root.values[0]); root.restore(0,solution);
			}
			incumbent=total; selected=solution;
			return true;
		}
	}

	/** Fold ordinals proven singleton by the initial feasibility fixed point exactly once. */
	private void normalizeAfterPropagation() {
		long branchEdgesBefore=branchEdgeCount();
		List<Check> reducedChecks=new ArrayList<>(checks.size());
		boolean projected=false,removedTautology=false;
		for(int id=0;id<checks.size();id++) {
			Check check=checks.get(id);
			int[] fixed=checkFixedOrdinals(check);
			int[] reduced=unfixedScope(check.owners,fixed);
			if(check.owners.length==2 && reduced.length==1) binaryChecksReduced++;
			if(reduced.length<=1 && unaryTautology(id,check.owners,fixed,reduced)) {
				removedTautology=true;
				continue;
			}
			if(reduced.length==check.owners.length) reducedChecks.add(check);
			else {
				projected=true;
				reducedChecks.add(new Check(check.site,reduced,values -> check.predicate.test(
					expandFixedScope(check.owners,fixed,values)),
					projectClasses(check.owners,reduced,check.truthClasses)));
			}
		}
		List<CostFactor> reducedTerms=new ArrayList<>(terms.size());
		for(CostFactor term : terms) {
			int[] fixed=costFixedOrdinals(term);
			int[] reduced=unfixedScope(term.owners,fixed);
			if(reduced.length==term.owners.length) reducedTerms.add(term);
			else {
				projected=true;
				reducedTerms.add(new CostFactor(reduced,values -> term.evaluator.apply(
					expandFixedScope(term.owners,fixed,values)),term.certifiedMinimum,
					projectCostClasses(term.owners,reduced,term.costClasses)));
			}
		}
		List<Check> groupedChecks=groupChecks(reducedChecks);
		boolean costNormalization=reducedTerms.stream().anyMatch(term -> term.owners.length<=1)
			|| uniqueScopeCount(reducedTerms.stream().map(term -> term.owners).toList())<reducedTerms.size();
		if(!projected && !removedTautology && groupedChecks.size()==checks.size() && !costNormalization) return;
		checks=groupedChecks;
		terms=foldAndGroupCosts(reducedTerms);
		rebuildDependencyIndexes();
		branchEdgesRemovedDuringNormalization=Math.max(0,branchEdgesBefore-branchEdgeCount());
		truthCache.clear(); costCache.clear(); boundCache.clear(); supportCache.clear();
		supportWitnesses.clear();
		binarySupports=new long[checks.size()][][]; binarySupportRows=0;
		Arrays.fill(ownerBounds,null); termBounds=new BigDecimal[terms.size()];
		dirtyOwnerBounds.clear(); dirtyOwnerBounds.set(0,domains.length);
		dirtyTermBounds.clear(); dirtyTermBounds.set(0,terms.size());
		cachedLowerBound=BigDecimal.ZERO; lowerBoundInitialized=false;
	}
	private long branchEdgeCount() {
		long edges=0;
		for(int owner=0;owner<branchAdjacent.length;owner++)
			for(int adjacent:branchAdjacent[owner]) if(adjacent>owner) edges++;
		return edges;
	}
	private int[] checkFixedOrdinals(Check check) {
		int[] fixed=fixedOrdinals(check.owners);
		if(check.truthClasses==null) return fixed;
		for(int position=0;position<check.owners.length;position++) {
			if(fixed[position]>=0) continue;
			int owner=check.owners[position],representative=domains[owner].nextSetBit(0);
			if(representative<0) continue;
			int truthClass=check.truthClasses[position][representative];
			boolean invariant=true;
			for(int ordinal=domains[owner].nextSetBit(representative+1);ordinal>=0;
				ordinal=domains[owner].nextSetBit(ordinal+1))
				if(check.truthClasses[position][ordinal]!=truthClass) { invariant=false; break; }
			if(invariant) { fixed[position]=representative; invariantCheckAxes++; }
		}
		return fixed;
	}
	/** A factor-local class-invariant owner cannot affect this term on any later domain subset. */
	private int[] costFixedOrdinals(CostFactor term) {
		int[] fixed=fixedOrdinals(term.owners);
		if(term.costClasses==null) return fixed;
		for(int position=0;position<term.owners.length;position++) {
			if(fixed[position]>=0) continue;
			int owner=term.owners[position];
			int representative=domains[owner].nextSetBit(0);
			if(representative<0) continue;
			int costClass=term.costClasses[position][representative];
			boolean invariant=true;
			for(int ordinal=domains[owner].nextSetBit(representative+1);ordinal>=0;
				ordinal=domains[owner].nextSetBit(ordinal+1))
				if(term.costClasses[position][ordinal]!=costClass) { invariant=false; break; }
			if(invariant) fixed[position]=representative;
		}
		return fixed;
	}
	private static int[] unfixedScope(int[] owners,int[] fixed) {
		int count=0;
		for(int ordinal:fixed) if(ordinal<0) count++;
		int[] result=new int[count];
		for(int source=0,target=0;source<owners.length;source++)
			if(fixed[source]<0) result[target++]=owners[source];
		return result;
	}
	/** Initial propagation's removed values are below every subsequent undo mark.
	 * Only an all-YES proof over that permanent domain can erase an obligation. */
	private boolean unaryTautology(int id,int[] owners,int[] fixed,int[] reduced) {
		if(reduced.length==0) return evaluate(id,expandFixedScope(owners,fixed,new int[0]))==Truth.YES;
		int owner=reduced[0];
		int[] value=new int[1];
		for(int ordinal=domains[owner].nextSetBit(0);ordinal>=0;ordinal=domains[owner].nextSetBit(ordinal+1)) {
			value[0]=ordinal;
			if(evaluate(id,expandFixedScope(owners,fixed,value))!=Truth.YES) return false;
		}
		return !domains[owner].isEmpty();
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
			},refinedTruthClasses(group)));
		}
		return List.copyOf(result);
	}
	private static int[][] refinedTruthClasses(List<Check> group) {
		if(group.stream().anyMatch(check -> check.truthClasses==null)) return null;
		return refinedClasses(group.get(0).owners.length,position ->
			group.stream().map(check -> check.truthClasses[position]).toList());
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
			},certified,refinedCostClasses(group));
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
	/** The exact class of a grouped factor is the intersection of every member partition. */
	private static int[][] refinedCostClasses(List<CostFactor> group) {
		if(group.stream().anyMatch(factor -> factor.costClasses==null)) return null;
		return refinedClasses(group.get(0).owners.length,position ->
			group.stream().map(factor -> factor.costClasses[position]).toList());
	}
	private static int[][] refinedClasses(int positions,Function<Integer,List<int[]>> mappings) {
		int[][] result=new int[positions][];
		for(int position=0;position<positions;position++) {
			List<int[]> classes=mappings.apply(position);
			int size=classes.get(0).length;
			for(int[] mapping:classes) if(mapping.length!=size)
				throw new IllegalStateException("Grouped classes disagree on owner domain");
			result[position]=new int[size];
			int nextClass=0;
			for(int ordinal=0;ordinal<size;ordinal++) {
				int equivalent=-1;
				for(int prior=0;prior<ordinal && equivalent<0;prior++) {
					boolean same=true;
					for(int[] mapping:classes) same&=mapping[ordinal]==mapping[prior];
					if(same) equivalent=prior;
				}
				result[position][ordinal]=equivalent<0 ? nextClass++ : result[position][equivalent];
			}
		}
		return result;
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
		int fixedPosition=-1;
		for(int position=0;position<check.owners.length;position++)
			if(check.owners[position]==fixedOwner) { fixedPosition=position; break; }
		assert fixedPosition>=0;
		if(limits.cacheEntries()>0) {
			long[][] partners=binarySupports[checkId];
			if(partners!=null && (compactDomains[fixedOwner]&(1L<<fixedValue))!=0
				&& (partners[fixedPosition][fixedValue]&compactDomains[check.owners[1-fixedPosition]])!=0) {
				supportWitnessHits++;
				binarySupportHits++;
				return Truth.YES;
			}
			int[] witness=partners==null ? cachedWitness(checkId,fixedPosition,fixedValue) : null;
			if(witness!=null && supports(check,witness)) {
				supportWitnessHits++;
				return Truth.YES;
			}
		}
		Truth cached = cachedDomains(supportCache,checkId,fixedOwner,fixedValue,check.owners);
		if(cached != null) { supportCacheHits++; return cached; }
		supportQueries++;
		int[] choices = new int[check.owners.length]; Arrays.fill(choices, -1);
		choices[fixedPosition]=fixedValue;
		foundSupportWitness=null;
		Truth result = supportDfs(checkId, choices, new int[] {limits.supportEvaluations()});
		supportCache.put(new ScopedDomainKey(checkId,fixedOwner,fixedValue,check.owners,domains,
			compactDomains,wideDomains),result);
		if(result==Truth.YES && foundSupportWitness!=null) {
			if(!rememberBinarySupport(checkId,foundSupportWitness))
				supportWitnesses.put(new SupportWitnessKey(checkId,fixedPosition,fixedValue),foundSupportWitness);
		}
		foundSupportWitness=null;
		return result;
	}
	/** Retain only proved YES pairs; domain intersection preserves removal and undo exactly. */
	private boolean rememberBinarySupport(int id,int[] tuple) {
		Check check=checks.get(id);
		if(limits.cacheEntries()==0 || check.owners.length!=2 || tuple[0]<0 || tuple[1]<0
			|| wideDomains[check.owners[0]] || wideDomains[check.owners[1]]) return false;
		long[][] partners=binarySupports[id];
		if(partners==null) {
			int first=domainSizes[check.owners[0]],second=domainSizes[check.owners[1]];
			if((long)binarySupportRows+first+second>limits.cacheEntries()) return false;
			partners=new long[][] {new long[first],new long[second]};
			binarySupports[id]=partners; binarySupportRows+=first+second;
		}
		partners[0][tuple[0]]|=1L<<tuple[1];
		partners[1][tuple[1]]|=1L<<tuple[0];
		return true;
	}

	private boolean supports(Check check,int[] witness) {
		if(witness.length!=check.owners.length) return false;
		for(int position=0;position<witness.length;position++)
			if(!domains[check.owners[position]].get(witness[position])) return false;
		return true;
	}

	private Truth supportDfs(int id, int[] choices, int[] budget) {
		if(budget[0]-- <= 0) return Truth.UNKNOWN;
		Truth prefix = evaluate(id, choices);
		if(prefix==Truth.YES) {
			if(limits.cacheEntries()>0) foundSupportWitness=completeWitness(checks.get(id),choices);
			return Truth.YES;
		}
		if(prefix==Truth.NO) return Truth.NO;
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
	private int[] completeWitness(Check check,int[] choices) {
		int[] witness=choices.clone();
		for(int position=0;position<witness.length;position++) if(witness[position]<0) {
			witness[position]=domains[check.owners[position]].nextSetBit(0);
			if(witness[position]<0) return null;
		}
		return witness;
	}

	private Truth evaluate(int id, int[] values) {
		Check check=checks.get(id);
		int[] keyValues=truthClassTuple(check,values);
		Truth cached = cached(truthCache,id,keyValues);
		if(cached != null) { checkCacheHits++; return cached; }
		checkEvaluations++;
		Truth value = Objects.requireNonNull(check.predicate.test(values.clone()));
		truthCache.put(new TupleKey(id,keyValues), value);
		if(value==Truth.YES) rememberBinarySupport(id,values);
		return value;
	}
	private static int[] truthClassTuple(Check check,int[] values) {
		if(check.truthClasses==null) return values;
		for(int value:values) if(value<0) return values;
		int[] result=new int[values.length];
		for(int position=0;position<values.length;position++)
			result[position]=check.truthClasses[position][values[position]];
		return result;
	}


	/** Exact DP for a closed forest of unary/binary scopes; no physical alternative table. */
	private boolean tryTreeSolve(MemoResourcePolicy resources) {
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
		TreeCell[][] cells;
		try { cells=new TreeCell[domains.length][]; }
		catch(OutOfMemoryError allocationFailure) {
			if(resources==null) throw allocationFailure;
			return false;
		}
		for(int pos = order.size()-1; pos >= 0; pos--) {
			int owner = order.get(pos);
			try { cells[owner]=new TreeCell[domainSizes[owner]]; }
			catch(OutOfMemoryError allocationFailure) {
				if(resources==null) throw allocationFailure;
				return false;
			}
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
							&& compareTreeChoice(cells,child,childRule,bestRule,resources!=null) < 0) {
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
					&& compareTreeChoice(cells,root,rule,bestRule,resources!=null) < 0) bestRule = rule;
			}
			if(bestRule < 0) return true;
			total = total.add(cells[root][bestRule].cost);
			if(resources==null) reconstructTree(cells,root,bestRule,assignment);
			else reconstructTreeIterative(cells,root,bestRule,assignment);
		}
		selected = assignment; incumbent = total;
		return true;
	}
	/** Returns -1 when the current primal graph is not a unary/binary forest. */
	private long estimateTreeBytes() {
		List<Set<Integer>> adjacent=new ArrayList<>();
		for(int owner=0;owner<domains.length;owner++) adjacent.add(new LinkedHashSet<>());
		for(Check check:checks) if(!addTreeEdge(adjacent,check.owners)) return -1;
		for(CostFactor term:terms) if(!addTreeEdge(adjacent,term.owners)) return -1;
		int[] parent=new int[domains.length]; Arrays.fill(parent,-2);
		for(int root=0;root<domains.length;root++) {
			if(parent[root]!=-2) continue;
			parent[root]=-1;
			ArrayDeque<Integer> pending=new ArrayDeque<>(); pending.add(root);
			while(!pending.isEmpty()) {
				int owner=pending.removeFirst();
				for(int next:adjacent.get(owner)) {
					if(next==parent[owner]) continue;
					if(parent[next]!=-2) return -1;
					parent[next]=owner; pending.add(next);
				}
			}
		}
		long bytes=MemoResourcePolicy.checkedMultiply(domains.length,192);
		for(int owner=0;owner<domains.length;owner++) {
			long children=0;
			for(int next:adjacent.get(owner)) if(parent[next]==owner) children++;
			long perCell=MemoResourcePolicy.checkedAdd(112,
				MemoResourcePolicy.checkedMultiply(children,8));
			bytes=MemoResourcePolicy.checkedAdd(bytes,
				MemoResourcePolicy.checkedMultiply(domainSizes[owner],perCell));
		}
		return bytes;
	}
	private static boolean addTreeEdge(List<Set<Integer>> adjacent,int[] scope) {
		if(scope.length > 2) return false;
		if(scope.length == 2) { adjacent.get(scope[0]).add(scope[1]); adjacent.get(scope[1]).add(scope[0]); }
		return true;
	}
	private static long edgeKey(int a,int b) { return ((long)Math.min(a,b) << 32) | Math.max(a,b); }
	private record TreeCell(BigDecimal cost,int[] children,int[] childRules) { }
	private static void reconstructTree(TreeCell[][] cells,int owner,int rule,int[] assignment) {
		assignment[owner]=rule;
		TreeCell cell=cells[owner][rule];
		for(int i=0;i<cell.children.length;i++)
			reconstructTree(cells,cell.children[i],cell.childRules[i],assignment);
	}
	private static void reconstructTreeIterative(TreeCell[][] cells,int owner,int rule,int[] assignment) {
		ArrayDeque<int[]> pending=new ArrayDeque<>(); pending.push(new int[] {owner,rule});
		while(!pending.isEmpty()) {
			int[] next=pending.pop();
			assignment[next[0]]=next[1];
			TreeCell cell=cells[next[0]][next[1]];
			for(int i=cell.children.length-1;i>=0;i--)
				pending.push(new int[] {cell.children[i],cell.childRules[i]});
		}
	}
	private static int compareTreeChoice(TreeCell[][] cells,int owner,int a,int b,boolean iterative) {
		int[] left = new int[cells.length], right = new int[cells.length];
		if(iterative) {
			reconstructTreeIterative(cells,owner,a,left); reconstructTreeIterative(cells,owner,b,right);
		}
		else {
			reconstructTree(cells,owner,a,left); reconstructTree(cells,owner,b,right);
		}
		return lexicographic(left,right);
	}
	private static int compareTreeChoice(TreeCell[][] cells,int owner,int a,int b) {
		return compareTreeChoice(cells,owner,a,b,false);
	}
	private BigDecimal evaluateCost(int id,int[] values) {
		CostFactor term = terms.get(id);
		int[] key=costClassTuple(term,values);
		BigDecimal value = cached(costCache,id,key);
		if(value != null) { costCacheHits++; return value; }
		costEvaluations++;
		value = Objects.requireNonNull(term.evaluator.apply(values.clone()));
		if(value.compareTo(term.certifiedMinimum) < 0)
			throw new IllegalStateException("Cost below certified bound at term " + id);
		costCache.put(new TupleKey(id,key),value);
		return value;
	}
	private static int[] costClassTuple(CostFactor term,int[] values) {
		if(term.costClasses==null) return values;
		int[] result=new int[values.length];
		for(int position=0;position<values.length;position++)
			result[position]=term.costClasses[position][values[position]];
		return result;
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
		private int cycleBranch=-1;
		private final class TailFactor {
			private final int id;
			private final int[] values;
			private final int firstOwner,firstPosition,secondOwner,secondPosition;
			private final int arity;
			private TailFactor(int id,int[] scope) {
				this.id=id; values=new int[scope.length];
				int owner1=-1,position1=-1,owner2=-1,position2=-1,count=0;
				for(int position=0;position<scope.length;position++) {
					int owner=scope[position];
					if(domainCardinalities[owner]>1) {
						if(count==0) { owner1=owner; position1=position; }
						else if(count==1) { owner2=owner; position2=position; }
						count++;
					}
					else values[position]=assignment[owner];
				}
				firstOwner=owner1; firstPosition=position1; secondOwner=owner2; secondPosition=position2;
				arity=count;
			}
			private int arity() { return arity; }
			private int[] tuple(int firstRule,int secondRule) {
				if(firstPosition>=0) values[firstPosition]=firstRule;
				if(secondPosition>=0) values[secondPosition]=secondRule;
				return values;
			}
		}
		private final class TailEdge {
			private final int child;
			private final List<TailFactor> checks,terms;
			private TailEdge(int child,List<TailFactor> checks,List<TailFactor> terms) {
				this.child=child; this.checks=checks; this.terms=terms;
			}
			private int child() { return child; }
			private List<TailFactor> checks() { return checks; }
			private List<TailFactor> terms() { return terms; }
		}
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
			int owner = cycleBranch, connectivity = -1;
			for(int i:coreOwnerIds) if(cycleBranch<0 && assignment[i]<0) {
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
		/** Fix a cycle owner only when every residual component is a tree or unicycle.
		 * Peeling leaves finds the cycle exactly; all original values are still searched. */
		private void choosePseudoforestBranch(boolean[] active) {
			boolean[] seen=new boolean[domains.length];
			boolean cycle=false;
			for(int root:coreOwnerIds) if(active[root] && !seen[root]) {
				int vertices=0,twiceEdges=0;
				ArrayDeque<Integer> queue=new ArrayDeque<>(); queue.add(root); seen[root]=true;
				while(!queue.isEmpty()) {
					int owner=queue.removeFirst(); vertices++;
					for(int next:branchAdjacent[owner]) if(active[next]) {
						twiceEdges++;
						if(!seen[next]) { seen[next]=true; queue.add(next); }
					}
				}
				int excess=twiceEdges/2-vertices+1;
				if(excess>1) return;
				cycle|=excess==1;
			}
			if(cycle) {
				int[] degree=new int[domains.length];
				ArrayDeque<Integer> peel=new ArrayDeque<>();
				for(int owner:coreOwnerIds) if(active[owner]) {
					for(int next:branchAdjacent[owner]) if(active[next]) degree[owner]++;
					if(degree[owner]<=1) peel.add(owner);
				}
				while(!peel.isEmpty()) {
					int owner=peel.removeFirst(); degree[owner]=0;
					for(int next:branchAdjacent[owner]) if(degree[next]>0 && --degree[next]==1) peel.add(next);
				}
				for(int owner:coreOwnerIds) if(degree[owner]>1 && (cycleBranch<0
					|| domainCardinalities[owner]<domainCardinalities[cycleBranch])) cycleBranch=owner;
			}
		}
		private ForestTailStatus tryForestTail() {
			cycleBranch=-1;
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
						if(parent[next]!=-2) {
							choosePseudoforestBranch(active);
							return ForestTailStatus.NOT_APPLICABLE;
						}
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
			List<List<TailFactor>> unaryChecks=lists(domains.length),unaryTerms=lists(domains.length);
			List<TailFactor> constantChecks=new ArrayList<>(),constantTerms=new ArrayList<>();
			Map<Long,List<TailFactor>> edgeChecks=new HashMap<>(),edgeTerms=new HashMap<>();
			for(int id=coreChecks.nextSetBit(0);id>=0;id=coreChecks.nextSetBit(id+1))
				if(!classifyTail(new TailFactor(id,checks.get(id).owners),constantChecks,unaryChecks,edgeChecks))
					return ForestTailStatus.NOT_APPLICABLE;
			for(int id:coreTerms)
				if(!classifyTail(new TailFactor(id,terms.get(id).owners),constantTerms,unaryTerms,edgeTerms))
					return ForestTailStatus.NOT_APPLICABLE;
			TailEdge[][] childEdges=new TailEdge[domains.length][];
			int[][] tailChildren=new int[domains.length][];
			for(int owner:order) {
				int[] dynamicChildren=Arrays.stream(branchAdjacent[owner]).filter(next -> parent[next]==owner).toArray();
				childEdges[owner]=new TailEdge[dynamicChildren.length];
				for(int position=0;position<dynamicChildren.length;position++) {
					int child=dynamicChildren[position]; long edge=edgeKey(owner,child);
					childEdges[owner][position]=new TailEdge(child,
						edgeChecks.getOrDefault(edge,List.of()),edgeTerms.getOrDefault(edge,List.of()));
				}
				List<BoundaryTree> attached=boundaryTrees.get(owner);
				int[] children=Arrays.copyOf(dynamicChildren,dynamicChildren.length+attached.size());
				for(int position=0;position<attached.size();position++)
					children[dynamicChildren.length+position]=attached.get(position).root;
				tailChildren[owner]=children;
			}
			BigDecimal total=base;
			for(TailFactor factor:constantChecks) {
				Truth truth=evaluate(factor.id,factor.tuple(-1,-1));
				if(truth==Truth.UNKNOWN) return ForestTailStatus.NOT_APPLICABLE;
				if(truth==Truth.NO) { forestTailCompletions++; return ForestTailStatus.COMPLETE; }
			}
			for(TailFactor factor:constantTerms)
				total=total.add(evaluateCost(factor.id,factor.tuple(-1,-1)));
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
				TailEdge[] dynamicChildren=childEdges[owner];
				List<BoundaryTree> attached=boundaryTrees.get(owner);
				for(int rule=domains[owner].nextSetBit(0);rule>=0;rule=domains[owner].nextSetBit(rule+1)) {
					if(nodes>=limits.searchNodes()) { exhausted=true; return ForestTailStatus.EXHAUSTED; }
					nodes++; forestTailCells++;
					boolean legal=true;
					for(TailFactor factor:unaryChecks.get(owner)) {
						Truth truth=evaluate(factor.id,factor.tuple(rule,-1));
						if(truth==Truth.UNKNOWN) { unknown[0]=true; legal=false; break; }
						if(truth==Truth.NO) { legal=false; break; }
					}
					BigDecimal cost=ownerMessageCosts[owner][rule];
					if(!legal || cost==null) continue;
					for(TailFactor factor:unaryTerms.get(owner))
						cost=cost.add(evaluateCost(factor.id,factor.tuple(rule,-1)));
					int[] children=tailChildren[owner];
					int[] childRules=new int[children.length];
					for(int childPosition=0;childPosition<dynamicChildren.length;childPosition++) {
						TailEdge edge=dynamicChildren[childPosition];
						int child=edge.child(),bestRule=-1;
						BigDecimal best=null;
						for(int childRule=0;childRule<tailCells[child].length;childRule++) {
							TreeCell childCell=tailCells[child][childRule]; if(childCell==null) continue;
							boolean compatible=true;
							for(TailFactor factor:edge.checks()) {
								Truth truth=evaluate(factor.id,factor.tuple(
									factor.firstOwner==owner ? rule : childRule,
									factor.secondOwner==child ? childRule : rule));
								if(truth==Truth.UNKNOWN) { unknown[0]=true; compatible=false; break; }
								if(truth==Truth.NO) { compatible=false; break; }
							}
							if(!compatible) continue;
							BigDecimal value=childCell.cost;
							for(TailFactor factor:edge.terms())
								value=value.add(evaluateCost(factor.id,factor.tuple(
									factor.firstOwner==owner ? rule : childRule,
									factor.secondOwner==child ? childRule : rule)));
							if(best==null || value.compareTo(best)<0 || value.compareTo(best)==0
								&& compareTreeChoice(tailCells,child,childRule,bestRule)<0) {
								best=value; bestRule=childRule;
							}
						}
						if(best==null) { legal=false; break; }
						cost=cost.add(best); childRules[childPosition]=bestRule;
					}
					if(!legal) continue;
					for(int attachedPosition=0;attachedPosition<attached.size();attachedPosition++) {
						BoundaryTree tree=attached.get(attachedPosition);
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
		private boolean classifyTail(TailFactor factor,List<TailFactor> constants,
			List<List<TailFactor>> unary,Map<Long,List<TailFactor>> edges) {
			if(factor.arity()>2) return false;
			if(factor.arity()==0) constants.add(factor);
			else if(factor.arity()==1) unary.get(factor.firstOwner).add(factor);
			else {
				long edge=edgeKey(factor.firstOwner,factor.secondOwner);
				edges.computeIfAbsent(edge,ignored -> new ArrayList<>()).add(factor);
			}
			return true;
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
		int[][] representatives=new int[term.owners.length][];
		for(int position=0;position<term.owners.length;position++) {
			representatives[position]=liveCostRepresentatives(term,position);
			int size=representatives[position].length;
			if(size==0) throw new IllegalStateException("Empty domain reached a cost bound");
			if(combinations>limits.supportEvaluations()/size) { wideBoundRelaxations++; return term.certifiedMinimum; }
			combinations*=size;
		}
		BigDecimal minimum=minimumTerm(id,0,new int[term.owners.length],representatives);
		boundCache.put(new ScopedDomainKey(id,-1,-1,term.owners,domains,compactDomains,wideDomains),minimum);
		return minimum;
	}
	private int[] liveCostRepresentatives(CostFactor term,int position) {
		int owner=term.owners[position];
		if(term.costClasses==null) return domains[owner].stream().toArray();
		int[] classes=term.costClasses[position];
		int max=Arrays.stream(classes).max().orElseThrow();
		int[] byClass=new int[max+1]; Arrays.fill(byClass,-1);
		for(int value=domains[owner].nextSetBit(0);value>=0;value=domains[owner].nextSetBit(value+1))
			if(byClass[classes[value]]<0) byClass[classes[value]]=value;
		return Arrays.stream(byClass).filter(value -> value>=0).toArray();
	}
	private BigDecimal minimumTerm(int id,int position,int[] values,int[][] representatives) {
		CostFactor term=terms.get(id);
		if(position==values.length) return evaluateCost(id,values);
		BigDecimal min=null;
		for(int value:representatives[position]) {
			values[position]=value;
			min=minimum(min,minimumTerm(id,position+1,values,representatives));
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
		return new Statistics(nodes,checkEvaluations,checkCacheHits,supportQueries,supportCacheHits,
			supportWitnessHits,binarySupportHits,binarySupportRows,worklistVisits,
			removedRules,costEvaluations,costCacheHits,boundPrunes,conditionalBoundQueries,
			conditionalBoundCacheHits,wideBoundRelaxations,forestTailAttempts,forestTailApplicable,
			forestTailCompletions,forestTailCells,annotatedChecks,invariantCheckAxes,binaryChecksReduced,
			branchEdgesRemovedDuringNormalization,eliminationVariables,eliminationCells,eliminationRows,eliminationFactorHits);
	}
	private static int[] uniqueScope(int[] owners) {
		int[] copy=owners.clone();
		if(copy.length<=1) return copy;
		if(copy.length==2) {
			if(copy[0]==copy[1])
				throw new IllegalArgumentException("A shared owner appears once in a condition scope");
			return copy;
		}
		// Sort a validation copy so the returned scope retains caller order.
		int[] sorted=copy.clone();
		Arrays.sort(sorted);
		for(int position=1;position<sorted.length;position++)
			if(sorted[position-1]==sorted[position])
				throw new IllegalArgumentException("A shared owner appears once in a condition scope");
		return copy;
	}
	private static int[][] validateCostClasses(int[] owners,int[][] costClasses) {
		return validateClasses(owners,costClasses,"Cost");
	}
	private static int[][] validateClasses(int[] owners,int[][] classes,String name) {
		if(classes==null) return null;
		if(classes.length!=owners.length)
			throw new IllegalArgumentException(name+" classes must align with factor scope");
		int[][] copy=copyCostClasses(classes);
		for(int[] mapping:copy) {
			if(mapping.length==0) throw new IllegalArgumentException(name+" classes cannot be empty");
			int max=-1;
			for(int value:mapping) {
				if(value<0) throw new IllegalArgumentException(name+" class IDs must be nonnegative");
				max=Math.max(max,value);
			}
			boolean[] seen=new boolean[max+1];
			for(int value:mapping) seen[value]=true;
			for(boolean present:seen) if(!present)
				throw new IllegalArgumentException(name+" class IDs must be dense");
		}
		return copy;
	}
	private static int[][] copyCostClasses(int[][] costClasses) {
		if(costClasses==null) return null;
		int[][] copy=new int[costClasses.length][];
		for(int position=0;position<copy.length;position++)
			copy[position]=Objects.requireNonNull(costClasses[position]).clone();
		return copy;
	}
	private static int[][] projectCostClasses(int[] original,int[] reduced,int[][] costClasses) {
		return projectClasses(original,reduced,costClasses);
	}
	private static int[][] projectClasses(int[] original,int[] reduced,int[][] costClasses) {
		if(costClasses==null) return null;
		int[][] result=new int[reduced.length][];
		for(int position=0;position<reduced.length;position++) {
			int source=0;
			while(original[source]!=reduced[position]) source++;
			result[position]=costClasses[source].clone();
		}
		return result;
	}
	private static int[] project(int[] scope, int[] assignment) {
		int[] result = new int[scope.length];
		for(int i = 0; i < scope.length; i++) result[i] = assignment[scope[i]];
		return result;
	}
	private BigDecimal cachedExact(double value) {
		if(!Double.isFinite(value)) throw new IllegalArgumentException("Costs must be finite: " + value);
		if(exactValueCacheLimit>0) {
			long bits=Double.doubleToRawLongBits(value);
			BigDecimal retained=exactValues.get(bits);
			if(retained!=null) return retained;
			BigDecimal built=new BigDecimal(value);
			if(exactValues.size()<exactValueCacheLimit) exactValues.put(bits,built);
			return built;
		}
		return new BigDecimal(value); // preserve the exact dyadic value of each binary64 term
	}
	private static BigDecimal exact(double value) {
		if(!Double.isFinite(value)) throw new IllegalArgumentException("Costs must be finite: " + value);
		return new BigDecimal(value); // CostTerm construction has no optimizer-owned cache
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
	/** Witness lookup borrows one reusable key only during Map.get. */
	private int[] cachedWitness(int id,int fixedPosition,int fixedValue) {
		witnessProbe.id=id; witnessProbe.fixedPosition=fixedPosition; witnessProbe.fixedValue=fixedValue;
		witnessProbe.hash=supportWitnessHash(id,fixedPosition,fixedValue);
		try { return supportWitnesses.get(witnessProbe); }
		finally {
			witnessProbe.id=0; witnessProbe.fixedPosition=0; witnessProbe.fixedValue=0;
			witnessProbe.hash=0;
		}
	}
	private static int supportWitnessHash(int id,int fixedPosition,int fixedValue) {
		return 31*(31*id+fixedPosition)+fixedValue;
	}
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
	private interface SupportWitnessIdentity {
		int id();
		int fixedPosition();
		int fixedValue();
		default boolean sameWitness(Object other) {
			return other instanceof SupportWitnessIdentity value && id()==value.id()
				&& fixedPosition()==value.fixedPosition() && fixedValue()==value.fixedValue();
		}
	}
	/** Never inserted; one optimizer invocation owns this synchronous lookup scratch. */
	private static final class SupportWitnessProbe implements SupportWitnessIdentity {
		private int id;
		private int fixedPosition;
		private int fixedValue;
		private int hash;
		@Override public int id() { return id; }
		@Override public int fixedPosition() { return fixedPosition; }
		@Override public int fixedValue() { return fixedValue; }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) { return sameWitness(other); }
	}
	private static final class SupportWitnessKey implements SupportWitnessIdentity {
		private final int id;
		private final int fixedPosition;
		private final int fixedValue;
		private final int hash;
		private SupportWitnessKey(int id,int fixedPosition,int fixedValue) {
			this.id=id; this.fixedPosition=fixedPosition; this.fixedValue=fixedValue;
			hash=supportWitnessHash(id,fixedPosition,fixedValue);
		}
		@Override public int id() { return id; }
		@Override public int fixedPosition() { return fixedPosition; }
		@Override public int fixedValue() { return fixedValue; }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) { return sameWitness(other); }
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
