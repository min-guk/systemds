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
package org.apache.sysds.hops.fedplanner.memo;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Exact forest dynamic programming with best-bound conflict resolution.
 *
 * <p>The acyclic unary/binary part of the Memo factor graph is solved by tree
 * DP. Factors that would close a cycle, or have arity greater than two, are
 * relaxed. Their conditional minimum (or their declared certified floor when
 * enumeration is too large) is included in the node lower bound. A relaxed
 * witness is checked against every original factor; a conflict partitions the
 * node on one owner and leaves all other tree messages reusable. Consequently
 * every queued node denotes a disjoint part of the original search space and
 * supplies a valid lower bound for that part.</p>
 */
public final class MemoTreeDpOptimizer {
	public enum Mode { GLOBAL, LOCAL }
	public enum Status { OPTIMAL, EPSILON_OPTIMAL, INFEASIBLE, UNRESOLVED, RESOURCE_EXHAUSTED }

	/** Resource limits are deterministic counts; there is deliberately no time limit. */
	public record Limits(long searchNodes, int factorCells, int cacheEntries, int frontierNodes) {
		public Limits {
			if(searchNodes < 1 || factorCells < 1 || cacheEntries < 0 || frontierNodes < 1)
				throw new IllegalArgumentException("Invalid Tree DP limits");
		}
		public static Limits defaults() {
			return new Limits(Long.MAX_VALUE, 256, 4096, 10_000);
		}
	}

	public record Statistics(long nodes, long conflicts, long dpCells, long cacheHits,
		long checkEvaluations, long costEvaluations, long maxFrontier,
		long retainedChecks, long retainedCosts, long deferredChecks, long deferredCosts,
		long cacheEntries, long maxRetainedDomainSlots, long exactCostCacheHits,
		long conditionalCostQueries, long conditionalCostCacheHits,
		long conditionalEnumeratedCells, long wideBoundRelaxations,
		long dpNanos, long conflictNanos) { }

	public record BoundPoint(long nodes, BigDecimal lowerBound, BigDecimal upperBound) { }

	public record Result(Status status, BigDecimal lowerBound, BigDecimal upperBound,
		int[] selectedRules, Statistics statistics, List<BoundPoint> progression) {
		public Result {
			Objects.requireNonNull(status);
			Objects.requireNonNull(statistics);
			selectedRules = selectedRules == null ? null : selectedRules.clone();
			progression = List.copyOf(progression);
		}
		@Override public int[] selectedRules() {
			return selectedRules == null ? null : selectedRules.clone();
		}
		public boolean certified() {
			return status == Status.OPTIMAL || status == Status.EPSILON_OPTIMAL;
		}
	}

	private static final BigDecimal EPSILON_FACTOR = new BigDecimal("1.05");
	private final int[] sizes;
	private final boolean[] coverage;
	private final BigDecimal[][] nativeCosts;
	private final List<MemoOptimizer.Check> checks;
	private final List<MemoOptimizer.CostTerm> costs;
	private final int[][][] costClasses;
	private final Mode mode;
	private final Limits limits;
	private final List<Integer> retainedChecks = new ArrayList<>();
	private final List<Integer> deferredChecks = new ArrayList<>();
	private final List<Integer> retainedCosts = new ArrayList<>();
	private final List<Integer> deferredCosts = new ArrayList<>();
	private final List<Integer> constantChecks = new ArrayList<>();
	private final List<Integer> constantCosts = new ArrayList<>();
	private final List<List<Integer>> unaryChecks;
	private final List<List<Integer>> unaryCosts;
	private final Map<Long,Edge> edges = new LinkedHashMap<>();
	private final List<List<Integer>> adjacency;
	private final List<Component> components = new ArrayList<>();
	private final Map<SubtreeKey,SubtreeSolution> messageCache;
	private final Map<CostTupleKey,BigDecimal> exactCostCache;
	private final Map<CostDomainKey,BigDecimal> conditionalCostCache;
	private long nodes, conflicts, dpCells, cacheHits, checkEvaluations, costEvaluations,
		maxFrontier, maxRetainedDomainSlots, dpNanos, conflictNanos, serial;
	private long exactCostCacheHits, conditionalCostQueries, conditionalCostCacheHits,
		conditionalEnumeratedCells, wideBoundRelaxations;
	private BigDecimal incumbent;
	private int[] selected;
	private BigDecimal lastReportedLower;
	private final List<BoundPoint> progression = new ArrayList<>();
	private boolean optimized;

	public MemoTreeDpOptimizer(RuleMemo memo, double[][] nativeCosts,
		List<MemoOptimizer.Check> checks, List<MemoOptimizer.CostTerm> costs,
		Mode mode, Limits limits) {
		this(domainSizes(memo), coverage(memo), nativeCosts, checks, costs, mode, limits);
	}

	public MemoTreeDpOptimizer(int[] sizes, boolean[] coverage, double[][] nativeCosts,
		List<MemoOptimizer.Check> checks, List<MemoOptimizer.CostTerm> costs,
		Mode mode, Limits limits) {
		this.sizes = Objects.requireNonNull(sizes).clone();
		this.coverage = Objects.requireNonNull(coverage).clone();
		this.checks = List.copyOf(checks);
		this.costs = List.copyOf(costs);
		costClasses=new int[this.costs.size()][][];
		this.mode = Objects.requireNonNull(mode);
		this.limits = Objects.requireNonNull(limits);
		if(this.sizes.length != this.coverage.length || nativeCosts.length != this.sizes.length)
			throw new IllegalArgumentException("Owner arrays must have identical length");
		this.nativeCosts = new BigDecimal[this.sizes.length][];
		for(int owner = 0; owner < this.sizes.length; owner++) {
			if(this.sizes[owner] < 0 || nativeCosts[owner].length != this.sizes[owner])
				throw new IllegalArgumentException("Native costs must match immutable rule domains");
			this.nativeCosts[owner] = new BigDecimal[this.sizes[owner]];
			for(int rule = 0; rule < this.sizes[owner]; rule++)
				this.nativeCosts[owner][rule] = exact(nativeCosts[owner][rule]);
		}
		for(MemoOptimizer.Check check : this.checks)
			validateScope(check.owners());
		for(int id=0;id<this.costs.size();id++) {
			MemoOptimizer.CostTerm cost=this.costs.get(id);
			validateScope(cost.owners());
			Objects.requireNonNull(cost.certifiedMinimum());
			costClasses[id]=cost.certifiedCostClasses();
			if(costClasses[id]!=null) {
				int[] owners=cost.owners();
				if(costClasses[id].length!=owners.length)
					throw new IllegalArgumentException("Cost classes must align with factor scope");
				for(int position=0;position<owners.length;position++)
					if(costClasses[id][position].length!=this.sizes[owners[position]])
						throw new IllegalArgumentException("Cost classes must match immutable rule domain");
			}
		}
		adjacency = new ArrayList<>(this.sizes.length);
		unaryChecks = new ArrayList<>(this.sizes.length);
		unaryCosts = new ArrayList<>(this.sizes.length);
		for(int i = 0; i < this.sizes.length; i++) {
			adjacency.add(new ArrayList<>());
			unaryChecks.add(new ArrayList<>());
			unaryCosts.add(new ArrayList<>());
		}
		int messageEntries=(limits.cacheEntries()+1)/2;
		int remainingEntries=limits.cacheEntries()-messageEntries;
		int tupleEntries=(remainingEntries+1)/2;
		int conditionalEntries=remainingEntries-tupleEntries;
		messageCache = new LinkedHashMap<>(16, .75f, true) {
			@Override protected boolean removeEldestEntry(Map.Entry<SubtreeKey,SubtreeSolution> eldest) {
				return size() > messageEntries;
			}
		};
		exactCostCache = boundedCache(tupleEntries);
		conditionalCostCache = boundedCache(conditionalEntries);
		buildForest();
	}

	public Result optimize() {
		if(optimized) throw new IllegalStateException("A Tree DP optimizer instance is single-use");
		optimized = true;
		if(!completeCoverage())
			return result(Status.UNRESOLVED, null, null);
		for(int size : sizes)
			if(size == 0) return result(Status.INFEASIBLE,null,null);
		BitSet[] domains = initialDomains();
		if(!projectSingletonChecks(domains))
			return result(Status.INFEASIBLE, null, null);
		Relaxation root;
		try {
			root = relax(domains);
		}
		catch(DpDepthException | StackOverflowError ex) {
			return result(Status.RESOURCE_EXHAUSTED,null,null);
		}
		if(root == null)
			return result(Status.INFEASIBLE, null, null);
		PriorityQueue<Node> frontier = new PriorityQueue<>(Comparator
			.comparing((Node n) -> n.bound).thenComparingLong(n -> n.serial));
		frontier.add(new Node(domains, root.bound, root.witness, root.deferredBounds, serial++));
		maxFrontier = 1;
		maxRetainedDomainSlots = sizes.length;
		List<Node> unresolved = new ArrayList<>();

		while(true) {
			BigDecimal lower = globalLower(frontier, unresolved);
			recordProgress(lower);
			Status certified = certification(lower);
			if(certified != null)
				return result(certified, lower, incumbent);
			if(frontier.isEmpty()) {
				if(!unresolved.isEmpty())
					return result(Status.UNRESOLVED, lower, incumbent);
				return incumbent == null ? result(Status.INFEASIBLE, null, null)
					: result(Status.OPTIMAL, incumbent, incumbent);
			}

			Node node = frontier.poll();
			long conflictStart = System.nanoTime();
			Validation validation;
			try {
				validation = validate(node.witness, node.deferredBounds);
			}
			catch(StackOverflowError error) {
				frontier.add(node);
				return result(Status.RESOURCE_EXHAUSTED,globalLower(frontier,unresolved),incumbent);
			}
			conflictNanos += System.nanoTime() - conflictStart;
			if(validation.feasible) {
				if(incumbent == null || validation.exactCost.compareTo(incumbent) < 0) {
					incumbent = validation.exactCost;
					selected = node.witness.clone();
				}
				if(validation.exactCost.compareTo(node.bound) == 0)
					continue;
			}
			BigDecimal currentLower = node.bound;
			BigDecimal remainingLower = globalLower(frontier, unresolved);
			if(remainingLower != null && remainingLower.compareTo(currentLower) < 0)
				currentLower = remainingLower;
			recordProgress(currentLower);
			Status currentCertificate = certification(currentLower);
			if(currentCertificate != null)
				return result(currentCertificate,currentLower,incumbent);
			if(fixed(validation.conflictOwners, node.domains)) {
				if(validation.definitivelyInfeasible) continue;
				unresolved.add(node);
				continue;
			}
			int branchOwner = chooseBranchOwner(validation.conflictOwners, node.domains);
			if(branchOwner < 0) {
				unresolved.add(node);
				continue;
			}
			conflicts++;
			int children = node.domains[branchOwner].cardinality();
			if(nodes + children > limits.searchNodes()
				|| frontier.size() + unresolved.size() + children > limits.frontierNodes()) {
				frontier.add(node); // expansion is atomic: retain the parent's valid bound
				return result(Status.RESOURCE_EXHAUSTED, globalLower(frontier, unresolved), incumbent);
			}
			List<Node> expanded = new ArrayList<>(children);
			for(int rule = node.domains[branchOwner].nextSetBit(0); rule >= 0;
				rule = node.domains[branchOwner].nextSetBit(rule + 1)) {
				BitSet[] childDomains = node.domains.clone();
				childDomains[branchOwner] = new BitSet(sizes[branchOwner]);
				childDomains[branchOwner].set(rule);
				Relaxation relaxed;
				try {
					relaxed = relax(childDomains);
				}
				catch(DpDepthException | StackOverflowError ex) {
					frontier.add(node);
					return result(Status.RESOURCE_EXHAUSTED,globalLower(frontier,unresolved),incumbent);
				}
				if(relaxed != null)
					expanded.add(new Node(childDomains, relaxed.bound, relaxed.witness,
						relaxed.deferredBounds, serial++));
			}
			frontier.addAll(expanded);
			maxFrontier = Math.max(maxFrontier, frontier.size() + unresolved.size());
			maxRetainedDomainSlots = Math.max(maxRetainedDomainSlots,
				(long)(frontier.size() + unresolved.size()) * sizes.length);
		}
	}

	private Validation validate(int[] assignment, BigDecimal[] deferredBounds) {
		int[] unknownScope = null;
		boolean unknown = false;
		for(MemoOptimizer.Check check : checks) {
			MemoOptimizer.Truth truth = evaluate(check, assignment);
			if(truth == MemoOptimizer.Truth.NO) {
				return new Validation(false,true,check.owners(),null);
			}
			else if(truth == MemoOptimizer.Truth.UNKNOWN) {
				unknown = true;
				if(unknownScope == null) unknownScope = check.owners();
			}
		}
		int[] conflictScope = unknownScope;
		if(unknown)
			return new Validation(false,false,conflictScope,null);
		BigDecimal exactCost = BigDecimal.ZERO;
		for(int owner = 0; owner < sizes.length; owner++)
			exactCost = exactCost.add(nativeCosts[owner][assignment[owner]]);
		BigDecimal[] exactTerms = new BigDecimal[costs.size()];
		for(int id=0;id<costs.size();id++) {
			exactTerms[id]=evaluate(id,costs.get(id),assignment);
			exactCost=exactCost.add(exactTerms[id]);
		}
		for(int position=0;position<deferredCosts.size();position++) {
			int id=deferredCosts.get(position);
			if(exactTerms[id].compareTo(deferredBounds[position])>0) {
				conflictScope=costs.get(id).owners();
				break;
			}
		}
		return new Validation(true,false,conflictScope,exactCost);
	}

	private Relaxation relax(BitSet[] domains) {
		nodes++;
		long start = System.nanoTime();
		int[] witness = new int[sizes.length];
		Arrays.fill(witness, -1);
		BigDecimal bound = BigDecimal.ZERO;
		for(int id : constantCosts) {
			MemoOptimizer.CostTerm cost = costs.get(id);
			bound = bound.add(evaluateProjected(id,cost,-1,0));
		}
		for(Component component : components) {
			ComponentSolution solution = solveComponent(component, domains);
			if(solution == null) {
				dpNanos += System.nanoTime() - start;
				return null;
			}
			bound = bound.add(solution.cost);
			for(int i = 0; i < component.owners.length; i++)
				witness[component.owners[i]] = solution.rules[i];
		}
		BigDecimal[] deferredBounds=new BigDecimal[deferredCosts.size()];
		for(int position=0;position<deferredCosts.size();position++) {
			int id=deferredCosts.get(position);
			deferredBounds[position]=conditionalMinimum(id,costs.get(id),domains);
			bound=bound.add(deferredBounds[position]);
		}
		dpNanos += System.nanoTime() - start;
		return new Relaxation(bound,witness,deferredBounds);
	}

	private ComponentSolution solveComponent(Component component, BitSet[] domains) {
		SubtreeSolution root;
		try {
			root = solveSubtree(component.root, -1, domains);
		}
		catch(StackOverflowError error) {
			throw new DpDepthException();
		}
		BigDecimal[] rootValues = root.values;
		int rootRule = argMin(rootValues, domains[component.root]);
		if(rootRule < 0) return null;
		int[] assignment = new int[sizes.length];
		Arrays.fill(assignment, -1);
		assignment[component.root] = rootRule;
		reconstruct(component.root, -1, rootRule, assignment, root.choices);
		int[] rules = new int[component.owners.length];
		for(int i = 0; i < rules.length; i++) rules[i] = assignment[component.owners[i]];
		return new ComponentSolution(rootValues[rootRule], rules);
	}

	private SubtreeSolution solveSubtree(int owner, int parent, BitSet[] domains) {
		SubtreeKey key = new SubtreeKey(owner,parent,subtreeSignature(owner,parent,domains));
		SubtreeSolution cached = messageCache.get(key);
		if(cached != null) {
			cacheHits++;
			return cached;
		}
		Map<Long,int[]> choices = new HashMap<>();
		BigDecimal[] values = new BigDecimal[sizes[owner]];
		for(int rule = domains[owner].nextSetBit(0); rule >= 0; rule = domains[owner].nextSetBit(rule + 1)) {
			values[rule] = nativeCosts[owner][rule].add(unaryCost(owner, rule));
		}
		for(int child : adjacency.get(owner)) if(child != parent) {
			SubtreeSolution childSolution = solveSubtree(child, owner, domains);
			BigDecimal[] childValues = childSolution.values;
			choices.putAll(childSolution.choices);
			int[] childChoice = new int[sizes[owner]];
			Arrays.fill(childChoice, -1);
			Edge edge = edges.get(pairKey(owner, child));
			for(int rule = domains[owner].nextSetBit(0); rule >= 0; rule = domains[owner].nextSetBit(rule + 1)) {
				if(values[rule] == null) continue;
				BigDecimal best = null;
				for(int childRule = domains[child].nextSetBit(0); childRule >= 0;
					childRule = domains[child].nextSetBit(childRule + 1)) {
					if(childValues[childRule] == null || !edgeAllowed(edge, owner, rule, child, childRule)) continue;
					BigDecimal candidate = childValues[childRule]
						.add(edgeCost(edge, owner, rule, child, childRule));
					dpCells++;
					if(best == null || candidate.compareTo(best) < 0) {
						best = candidate;
						childChoice[rule] = childRule;
					}
				}
				if(best == null) values[rule] = null;
				else values[rule] = values[rule].add(best);
			}
			choices.put(directedKey(owner, child), childChoice);
		}
		SubtreeSolution result = new SubtreeSolution(values,Map.copyOf(choices));
		if(limits.cacheEntries() > 0) messageCache.put(key,result);
		return result;
	}

	private void reconstruct(int owner, int parent, int rule, int[] assignment,
		Map<Long,int[]> choices) {
		for(int child : adjacency.get(owner)) if(child != parent) {
			int childRule = choices.get(directedKey(owner, child))[rule];
			assignment[child] = childRule;
			reconstruct(child, owner, childRule, assignment, choices);
		}
	}

	private BigDecimal unaryCost(int owner, int rule) {
		BigDecimal result = BigDecimal.ZERO;
		for(int id : unaryCosts.get(owner)) {
			MemoOptimizer.CostTerm cost = costs.get(id);
			result = result.add(evaluateProjected(id,cost,owner,rule));
		}
		return result;
	}

	private boolean edgeAllowed(Edge edge, int left, int leftRule, int right, int rightRule) {
		for(int id : edge.checks) {
			MemoOptimizer.Check check = checks.get(id);
			checkEvaluations++;
			if(evaluateProjected(check,left,leftRule,right,rightRule) == MemoOptimizer.Truth.NO) return false;
		}
		return true;
	}

	private BigDecimal edgeCost(Edge edge, int left, int leftRule, int right, int rightRule) {
		BigDecimal result = BigDecimal.ZERO;
		for(int id : edge.costs) {
			MemoOptimizer.CostTerm cost = costs.get(id);
			result = result.add(evaluateProjected(id,cost,left,leftRule,right,rightRule));
		}
		return result;
	}

	private BigDecimal conditionalMinimum(int id, MemoOptimizer.CostTerm cost, BitSet[] domains) {
		conditionalCostQueries++;
		int[] scope = cost.owners();
		CostDomainKey key=null;
		if(!conditionalCostCache.isEmpty() || limits.cacheEntries()>0) {
			key=new CostDomainKey(id,scope,domains,costClasses[id]);
			BigDecimal cached=conditionalCostCache.get(key);
			if(cached!=null) {
				conditionalCostCacheHits++;
				return cached;
			}
		}
		long combinations = 1;
		int[][] representatives=new int[scope.length][];
		for(int position=0;position<scope.length;position++) {
			int owner=scope[position];
			representatives[position]=costRepresentatives(id,position,owner,domains[owner]);
			int cardinality=representatives[position].length;
			if(cardinality == 0) return null;
			if(combinations > limits.factorCells() / cardinality) {
				wideBoundRelaxations++;
				BigDecimal floor=cost.certifiedMinimum();
				if(key!=null) conditionalCostCache.put(key,floor);
				return floor;
			}
			combinations *= cardinality;
		}
		BigDecimal minimum=enumerateMinimum(id,cost,representatives,new int[scope.length],0,null);
		if(minimum.compareTo(cost.certifiedMinimum()) < 0)
			throw new IllegalArgumentException("Cost term violates its certified minimum");
		if(key!=null) conditionalCostCache.put(key,minimum);
		return minimum;
	}

	private BigDecimal enumerateMinimum(int id, MemoOptimizer.CostTerm cost, int[][] representatives,
		int[] tuple, int position, BigDecimal minimum) {
		if(position == representatives.length) {
			dpCells++;
			conditionalEnumeratedCells++;
			BigDecimal value = evaluateTuple(id,cost,tuple);
			return minimum == null || value.compareTo(minimum) < 0 ? value : minimum;
		}
		for(int rule:representatives[position]) {
			tuple[position] = rule;
			minimum=enumerateMinimum(id,cost,representatives,tuple,position+1,minimum);
		}
		return minimum;
	}

	private int[] costRepresentatives(int id,int position,int owner,BitSet domain) {
		if(costClasses[id]==null) return domain.stream().toArray();
		int[] classes=costClasses[id][position];
		int max=Arrays.stream(classes).max().orElseThrow();
		int[] representatives=new int[max+1];
		Arrays.fill(representatives,-1);
		for(int rule=domain.nextSetBit(0);rule>=0;rule=domain.nextSetBit(rule+1))
			if(representatives[classes[rule]]<0) representatives[classes[rule]]=rule;
		return Arrays.stream(representatives).filter(rule -> rule>=0).toArray();
	}

	private MemoOptimizer.Truth evaluate(MemoOptimizer.Check check, int[] assignment) {
		int[] scope = check.owners();
		int[] tuple = new int[scope.length];
		for(int i = 0; i < scope.length; i++) tuple[i] = assignment[scope[i]];
		checkEvaluations++;
		return check.evaluate(tuple);
	}

	private BigDecimal evaluate(int id, MemoOptimizer.CostTerm cost, int[] assignment) {
		int[] scope = cost.owners();
		int[] tuple = new int[scope.length];
		for(int i = 0; i < scope.length; i++) tuple[i] = assignment[scope[i]];
		return evaluateTuple(id,cost,tuple);
	}

	private MemoOptimizer.Truth evaluateProjected(MemoOptimizer.Check check,
		int owner, int rule) {
		return evaluateProjected(check,owner,rule,-1,0);
	}

	private MemoOptimizer.Truth evaluateProjected(MemoOptimizer.Check check,
		int owner1, int rule1, int owner2, int rule2) {
		int[] scope = check.owners(), tuple = new int[scope.length];
		for(int i = 0; i < scope.length; i++)
			tuple[i] = scope[i] == owner1 ? rule1 : scope[i] == owner2 ? rule2 : 0;
		return check.evaluate(tuple);
	}

	private BigDecimal evaluateProjected(int id, MemoOptimizer.CostTerm cost,
		int owner, int rule) {
		return evaluateProjected(id,cost,owner,rule,-1,0);
	}

	private BigDecimal evaluateProjected(int id, MemoOptimizer.CostTerm cost,
		int owner1, int rule1, int owner2, int rule2) {
		int[] scope = cost.owners(), tuple = new int[scope.length];
		for(int i = 0; i < scope.length; i++)
			tuple[i] = scope[i] == owner1 ? rule1 : scope[i] == owner2 ? rule2 : 0;
		return evaluateTuple(id,cost,tuple);
	}

	private BigDecimal evaluateTuple(int id, MemoOptimizer.CostTerm cost, int[] tuple) {
		int[] cacheTuple=tuple;
		if(costClasses[id]!=null) {
			cacheTuple=new int[tuple.length];
			for(int position=0;position<tuple.length;position++)
				cacheTuple[position]=costClasses[id][position][tuple[position]];
		}
		CostTupleKey probe=new CostTupleKey(id,cacheTuple);
		BigDecimal cached=exactCostCache.get(probe);
		if(cached!=null) {
			exactCostCacheHits++;
			return cached;
		}
		costEvaluations++;
		BigDecimal value = cost.evaluateExact(tuple);
		if(value.compareTo(cost.certifiedMinimum()) < 0)
			throw new IllegalArgumentException("Cost term violates its certified minimum");
		if(limits.cacheEntries()>0) exactCostCache.put(probe,value);
		return value;
	}

	private int[] effectiveScope(int[] scope) {
		return Arrays.stream(scope).filter(owner -> sizes[owner] > 1).toArray();
	}

	private int[] effectiveCostScope(int id) {
		int[] owners=costs.get(id).owners();
		java.util.stream.IntStream positions=java.util.stream.IntStream.range(0,owners.length);
		return positions.filter(position -> sizes[owners[position]]>1
			&& (costClasses[id]==null || Arrays.stream(costClasses[id][position]).max().orElseThrow()>0))
			.map(position -> owners[position]).toArray();
	}

	private void buildForest() {
		Map<Long,Edge> candidates = new LinkedHashMap<>();
		for(int id = 0; id < checks.size(); id++) {
			int[] scope = effectiveScope(checks.get(id).owners());
			if(scope.length <= 1) {
				retainedChecks.add(id);
				if(scope.length == 0) constantChecks.add(id);
				else unaryChecks.get(scope[0]).add(id);
			}
			else if(scope.length == 2)
				candidates.computeIfAbsent(pairKey(scope[0],scope[1]), k -> new Edge()).checks.add(id);
			else deferredChecks.add(id);
		}
		for(int id = 0; id < costs.size(); id++) {
			int[] scope = effectiveCostScope(id);
			if(scope.length <= 1) {
				retainedCosts.add(id);
				if(scope.length == 0) constantCosts.add(id);
				else unaryCosts.get(scope[0]).add(id);
			}
			else if(scope.length == 2)
				candidates.computeIfAbsent(pairKey(scope[0],scope[1]), k -> new Edge()).costs.add(id);
			else deferredCosts.add(id);
		}
		DisjointSet sets = new DisjointSet(sizes.length);
		for(Map.Entry<Long,Edge> candidate : candidates.entrySet()) {
			int left = pairLeft(candidate.getKey()), right = pairRight(candidate.getKey());
			// Keep a wide pair out of the materialized tree table. Its checks remain a
			// relaxation and its costs retain their certified floors until branching
			// makes the conditional table small enough to enumerate exactly.
			boolean wide = sizes[right] != 0 && sizes[left] > limits.factorCells() / sizes[right];
			if(!wide && sets.union(left,right)) {
				edges.put(candidate.getKey(), candidate.getValue());
				adjacency.get(left).add(right);
				adjacency.get(right).add(left);
				retainedChecks.addAll(candidate.getValue().checks);
				retainedCosts.addAll(candidate.getValue().costs);
			}
			else {
				deferredChecks.addAll(candidate.getValue().checks);
				deferredCosts.addAll(candidate.getValue().costs);
			}
		}
		boolean[] seen = new boolean[sizes.length];
		for(int owner = 0; owner < sizes.length; owner++) if(!seen[owner]) {
			List<Integer> members = new ArrayList<>();
			ArrayDeque<Integer> pending = new ArrayDeque<>();
			pending.push(owner);
			seen[owner] = true;
			while(!pending.isEmpty()) {
				int current = pending.pop();
				members.add(current);
				for(int next : adjacency.get(current)) if(!seen[next]) {
					seen[next] = true;
					pending.push(next);
				}
			}
			components.add(new Component(components.size(), owner,
				members.stream().mapToInt(Integer::intValue).toArray()));
		}
	}

	private boolean projectSingletonChecks(BitSet[] domains) {
		for(int id : constantChecks) {
			MemoOptimizer.Check check = checks.get(id);
			checkEvaluations++;
			if(evaluateProjected(check,-1,0) == MemoOptimizer.Truth.NO) return false;
		}
		for(int owner = 0; owner < sizes.length; owner++)
			for(int id : unaryChecks.get(owner)) {
				MemoOptimizer.Check check = checks.get(id);
				for(int rule = domains[owner].nextSetBit(0); rule >= 0;
					rule = domains[owner].nextSetBit(rule + 1)) {
					checkEvaluations++;
					if(evaluateProjected(check,owner,rule) == MemoOptimizer.Truth.NO)
						domains[owner].clear(rule);
				}
				if(domains[owner].isEmpty()) return false;
			}
		return true;
	}

	private Status certification(BigDecimal lower) {
		if(incumbent == null || lower == null) return null;
		if(incumbent.compareTo(lower) <= 0) return Status.OPTIMAL;
		if(mode == Mode.LOCAL && lower.signum() > 0
			&& incumbent.compareTo(lower.multiply(EPSILON_FACTOR)) <= 0)
			return Status.EPSILON_OPTIMAL;
		return null;
	}

	private BigDecimal globalLower(PriorityQueue<Node> frontier, List<Node> unresolved) {
		BigDecimal lower = frontier.isEmpty() ? null : frontier.peek().bound;
		for(Node node : unresolved)
			if(lower == null || node.bound.compareTo(lower) < 0) lower = node.bound;
		if(lower == null) return incumbent;
		if(incumbent != null && incumbent.compareTo(lower) < 0) return incumbent;
		return lower;
	}

	private void recordProgress(BigDecimal lower) {
		if(lower == null) return;
		if(lastReportedLower != null && lower.compareTo(lastReportedLower) < 0)
			throw new IllegalStateException("Global lower bound regressed");
		if(progression.isEmpty() || lower.compareTo(lastReportedLower) != 0
			|| !Objects.equals(incumbent, progression.get(progression.size()-1).upperBound())) {
			progression.add(new BoundPoint(nodes, lower, incumbent));
			lastReportedLower = lower;
		}
	}

	private Result result(Status status, BigDecimal lower, BigDecimal upper) {
		Statistics statistics = new Statistics(nodes,conflicts,dpCells,cacheHits,
			checkEvaluations,costEvaluations,maxFrontier,retainedChecks.size(),retainedCosts.size(),
			deferredChecks.size(),deferredCosts.size(),messageCache.size()+exactCostCache.size()
				+conditionalCostCache.size(),maxRetainedDomainSlots,exactCostCacheHits,
			conditionalCostQueries,conditionalCostCacheHits,conditionalEnumeratedCells,
			wideBoundRelaxations,dpNanos,conflictNanos);
		return new Result(status,lower,upper,selected,statistics,progression);
	}

	private int chooseBranchOwner(int[] preferred, BitSet[] domains) {
		if(preferred != null)
			for(int owner : preferred) if(domains[owner].cardinality() > 1) return owner;
		for(int owner = 0; owner < domains.length; owner++)
			if(domains[owner].cardinality() > 1) return owner;
		return -1;
	}

	private static boolean fixed(int[] owners, BitSet[] domains) {
		if(owners == null) return false;
		for(int owner : owners) if(domains[owner].cardinality() > 1) return false;
		return true;
	}

	private String subtreeSignature(int owner, int parent, BitSet[] domains) {
		StringBuilder result = new StringBuilder();
		appendSubtreeSignature(owner,parent,domains,result);
		return result.toString();
	}

	private void appendSubtreeSignature(int owner, int parent, BitSet[] domains, StringBuilder result) {
		result.append(owner).append(':').append(Arrays.toString(domains[owner].toLongArray())).append(';');
		for(int child : adjacency.get(owner)) if(child != parent)
			appendSubtreeSignature(child,owner,domains,result);
	}

	private BitSet[] initialDomains() {
		BitSet[] result = new BitSet[sizes.length];
		for(int owner = 0; owner < sizes.length; owner++) {
			result[owner] = new BitSet(sizes[owner]);
			result[owner].set(0,sizes[owner]);
		}
		return result;
	}

	private boolean completeCoverage() {
		for(boolean complete : coverage) if(!complete) return false;
		return true;
	}

	private void validateScope(int[] scope) {
		Set<Integer> distinct = new LinkedHashSet<>();
		for(int owner : scope) {
			if(owner < 0 || owner >= sizes.length || !distinct.add(owner))
				throw new IllegalArgumentException("Invalid factor owner scope");
		}
	}

	private static BigDecimal exact(double value) {
		if(!Double.isFinite(value)) throw new IllegalArgumentException("Costs must be finite");
		return new BigDecimal(value);
	}

	private static <K,V> Map<K,V> boundedCache(int entries) {
		return new LinkedHashMap<>(16,.75f,true) {
			@Override protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
				return size()>entries;
			}
		};
	}

	private static int argMin(BigDecimal[] values, BitSet domain) {
		int best = -1;
		for(int rule = domain.nextSetBit(0); rule >= 0; rule = domain.nextSetBit(rule + 1))
			if(values[rule] != null && (best < 0 || values[rule].compareTo(values[best]) < 0)) best = rule;
		return best;
	}

	private static long pairKey(int a, int b) {
		int left = Math.min(a,b), right = Math.max(a,b);
		return ((long)left << 32) | (right & 0xffffffffL);
	}
	private static int pairLeft(long key) { return (int)(key >>> 32); }
	private static int pairRight(long key) { return (int)key; }
	private static long directedKey(int parent, int child) {
		return ((long)parent << 32) | (child & 0xffffffffL);
	}
	private static int[] domainSizes(RuleMemo memo) {
		int[] result = new int[memo.ownerCount()];
		for(int owner = 0; owner < result.length; owner++) result[owner] = memo.rules(owner).size();
		return result;
	}
	private static boolean[] coverage(RuleMemo memo) {
		boolean[] result = new boolean[memo.ownerCount()];
		for(int owner = 0; owner < result.length; owner++) result[owner] = memo.complete(owner);
		return result;
	}

	private record Relaxation(BigDecimal bound, int[] witness, BigDecimal[] deferredBounds) { }
	private record Validation(boolean feasible, boolean definitivelyInfeasible,
		int[] conflictOwners, BigDecimal exactCost) { }
	private record Node(BitSet[] domains, BigDecimal bound, int[] witness,
		BigDecimal[] deferredBounds, long serial) { }
	private record Component(int id, int root, int[] owners) { }
	private record ComponentSolution(BigDecimal cost, int[] rules) { }
	private record SubtreeKey(int owner, int parent, String domains) { }
	private record SubtreeSolution(BigDecimal[] values, Map<Long,int[]> choices) { }
	private static final class CostTupleKey {
		private final int id;
		private final int[] tuple;
		private final int hash;
		private CostTupleKey(int id,int[] tuple) {
			this.id=id;
			this.tuple=tuple.clone();
			hash=31*id+Arrays.hashCode(this.tuple);
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object value) {
			return value instanceof CostTupleKey other && id==other.id
				&& Arrays.equals(tuple,other.tuple);
		}
	}
	private static final class CostDomainKey {
		private final int id;
		private final long[][] domains;
		private final int hash;
		private CostDomainKey(int id,int[] scope,BitSet[] current,int[][] classes) {
			this.id=id;
			domains=new long[scope.length][];
			int value=id;
			for(int i=0;i<scope.length;i++) {
				if(classes==null) domains[i]=current[scope[i]].toLongArray();
				else {
					BitSet liveClasses=new BitSet();
					for(int rule=current[scope[i]].nextSetBit(0);rule>=0;
						rule=current[scope[i]].nextSetBit(rule+1))
						liveClasses.set(classes[i][rule]);
					domains[i]=liveClasses.toLongArray();
				}
				value=31*value+Arrays.hashCode(domains[i]);
			}
			hash=value;
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object value) {
			if(!(value instanceof CostDomainKey other) || id!=other.id
				|| domains.length!=other.domains.length) return false;
			for(int i=0;i<domains.length;i++)
				if(!Arrays.equals(domains[i],other.domains[i])) return false;
			return true;
		}
	}
	private static final class Edge {
		private final List<Integer> checks = new ArrayList<>();
		private final List<Integer> costs = new ArrayList<>();
	}
	private static final class DpDepthException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
	private static final class DisjointSet {
		private final int[] parent;
		private DisjointSet(int size) {
			parent = new int[size];
			for(int i = 0; i < size; i++) parent[i] = i;
		}
		private int find(int value) {
			while(parent[value] != value) {
				parent[value] = parent[parent[value]];
				value = parent[value];
			}
			return value;
		}
		private boolean union(int left, int right) {
			left = find(left); right = find(right);
			if(left == right) return false;
			parent[right] = left;
			return true;
		}
	}
}
