/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Disjoint factor ownership and persistent exact boundary messages. No Global restart. */
final class IncrementalRegionalOptimizer {
	static final String PREFIX = "sysds.fedplanner.regional.incremental.";
	static boolean configured() { return Boolean.parseBoolean(System.getProperty(PREFIX + "enabled", "false")); }

	record Options(double relativeGap, long maximumMergeAssignments, long maximumRetainedSlots,
		long timeMillis, int scoredCandidates, boolean earlyStop) {
		Options {
			if(!Double.isFinite(relativeGap) || relativeGap < 0 || maximumMergeAssignments < 1
				|| maximumRetainedSlots < 1 || timeMillis < 0 || scoredCandidates < 1)
				throw new IllegalArgumentException("INCREMENTAL_REGIONAL_OPTIONS_INVALID");
		}
		static Options configured() {
			return new Options(Double.parseDouble(System.getProperty(PREFIX + "relativeGap", "0.05")),
				Long.parseLong(System.getProperty(PREFIX + "assignments", "1000000")),
				Long.parseLong(System.getProperty(PREFIX + "retainedSlots", "8000000")),
				Long.parseLong(System.getProperty(PREFIX + "timeMillis", "10000")),
				Integer.parseInt(System.getProperty(PREFIX + "scoredCandidates", "16")),
				Boolean.parseBoolean(System.getProperty(PREFIX + "earlyStop", "true")));
		}
	}
	record Checkpoint(String phase, int merges, int activeClusters, double lower, double upper,
		double relativeGap, long elapsedNanos, long dpNanos, long scoringNanos, long validationNanos,
		long assignments, long retainedSlots, int improvements, int resourceRejected, int internalDecisions) { }
	record Result(List<Integer> assignment, double lower, double upper, String stopReason,
		List<Checkpoint> checkpoints) { }

	private static final class Node {
		final int id;
		final int[] owners;
		final BoundaryMessage message;
		final double minimum, lower;
		final Map<Variable,double[]> marginals = new HashMap<>();
		Node(int id, int[] owners, BoundaryMessage message) {
			this.id = id; this.owners = owners; this.message = message;
			this.minimum = message.minimum(); this.lower = message.lowerBound();
		}
	}
	private record Candidate(Variable pivot, List<Node> inputs, List<Variable> boundary,
		long assignments, long work, long slots, int rank) { }
	private static final Comparator<Candidate> ORDER = Comparator.comparingLong(Candidate::slots)
		.thenComparingLong(Candidate::work).thenComparingInt(Candidate::rank);

	private final RegionalSearchProblem problem;
	private final ExactPhysicalReducedSolver.CompactModel root;
	private final List<Variable> variables;
	private final Map<Variable,Integer> positions = new HashMap<>();
	private final Limits limits;
	private final Options options;
	private final Consumer<Checkpoint> observer;
	private final List<Node> active = new ArrayList<>();
	private final List<Node> sealed = new ArrayList<>();
	private final Map<Variable,Set<Node>> incidence = new LinkedHashMap<>();
	private final Map<Variable,Candidate> candidates = new HashMap<>();
	private final TreeSet<Candidate> queue = new TreeSet<>(ORDER);
	private final List<Checkpoint> checkpoints = new ArrayList<>();
	private final long started = System.nanoTime();
	private int[] incumbent;
	private double lower, upper, sealedLower;
	private long slots, assignments, dpNanos, scoringNanos, validationNanos;
	private int nextId, merges, improvements, resourceRejected, internalDecisions;

	private IncrementalRegionalOptimizer(RegionalSearchProblem problem,
		ExactPhysicalReducedSolver.CompactModel root, List<Integer> originalSeed, Limits limits,
		Options options, Consumer<Checkpoint> observer) {
		this.problem = problem; this.root = root; this.variables = root.variables();
		this.limits = limits; this.options = options; this.observer = observer;
		for(int i=0; i<variables.size(); i++) positions.put(variables.get(i),i);
		incumbent = IncrementalRegionalSeed.lift(root, originalSeed, limits);
		upper = validate(incumbent);
	}

	static Result optimize(RegionalSearchProblem problem, ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> originalSeed, Limits limits,
		Options options, Consumer<Checkpoint> observer) {
		return new IncrementalRegionalOptimizer(problem, root, originalSeed, limits, options, observer)
			.run();
	}

	private Result run() {
		checkpoint("BOOTSTRAP");
		// Preflight the entire initial cover before evaluating or publishing any leaf bound.
		long initialSlots = 0;
		for(Factor factor : root.factors()) initialSlots = add(initialSlots, cells(factor.scope()));
		if(initialSlots > options.maximumRetainedSlots()) return finish("RESOURCE_INITIAL");
		long dpStarted = System.nanoTime();
		try {
			List<BoundaryMessage> leaves = ExactCategoricalSolver.boundaryLeaves(variables,root.factors(),limits);
			for(int ordinal = 0; ordinal < root.factors().size(); ordinal++) {
				BoundaryMessage leaf = leaves.get(ordinal);
				active.add(new Node(nextId++,new int[]{ordinal},leaf));
				slots = add(slots, leaf.retainedCells()); assignments = add(assignments, leaf.assignments());
			}
		}
		finally { dpNanos += System.nanoTime() - dpStarted; }
		if(slots > options.maximumRetainedSlots()) throw new IllegalStateException("INCREMENTAL_LEAF_ACCOUNTING_MISMATCH");
		normalizePrivate();
		for(Node node : List.copyOf(active)) {
			if(node.message.scope().isEmpty()) { active.remove(node); seal(node); }
			else for(Variable v : node.message.scope())
				incidence.computeIfAbsent(v, ignored -> new LinkedHashSet<>()).add(node);
		}
		// Full-variable buckets only remove their pivot from the active boundary.
		// Count projected/private decisions once, then update this statistic per pivot.
		if(!active.isEmpty() || !sealed.isEmpty())
			for(int i=0; i<problem.decisionCount(); i++)
				if(variables.get(i).domainSize()>1 && !incidence.containsKey(variables.get(i))) internalDecisions++;
		verifyCover(); updateLower(); checkpoint("INITIAL_BOUND");
		boolean scheduled = false;
		while(true) {
			if(exact()) {
				int[] candidate = incumbent.clone();
				for(Node node : sealed) node.message.decodeInto(candidate, variables);
				accept(candidate);
				lower = upper;
				return finish("EXACT");
			}
			if(options.earlyStop() && relativeGap(lower, upper) <= options.relativeGap())
				return finish("TARGET_REACHED");
			if(options.timeMillis() > 0 && (System.nanoTime() - started) / 1_000_000 >= options.timeMillis())
				return finish("TIME");
			if(!scheduled) {
				long start = System.nanoTime();
				for(Variable v : incidence.keySet()) refresh(v);
				scoringNanos += System.nanoTime()-start;
				scheduled = true;
			}
			Candidate candidate = choose();
			if(candidate == null) return finish("RESOURCE");
			// Diagnostics are optional: never reject required DP output because of an evictable cache.
			if(add(slots,candidate.slots()) > options.maximumRetainedSlots())
				for(Node node : active) releaseMarginals(node);
			if(add(slots,candidate.slots()) > options.maximumRetainedSlots()) {
				discard(candidate.pivot()); resourceRejected++; continue;
			}
			BoundaryMessage merged;
			dpStarted = System.nanoTime();
			try {
				merged = ExactCategoricalSolver.mergeBoundary(candidate.inputs().stream().map(n -> n.message).toList(),
					candidate.boundary(), limits, options.maximumMergeAssignments());
			}
			catch(IllegalArgumentException | IllegalStateException ex) {
				if(ex.getMessage() == null || !ex.getMessage().startsWith("INCREMENTAL_MESSAGE_RESOURCE")) throw ex;
				discard(candidate.pivot()); resourceRejected++;
				continue;
			}
			finally { dpNanos += System.nanoTime() - dpStarted; }
			long start = System.nanoTime();
			Set<Variable> affected = new LinkedHashSet<>();
			int[] owners = candidate.inputs().stream().flatMapToInt(n -> Arrays.stream(n.owners)).sorted().toArray();
			for(int i=1; i<owners.length; i++) if(owners[i]==owners[i-1])
				throw new IllegalStateException("INCREMENTAL_FACTOR_DOUBLE_OWNER");
			for(Node node : candidate.inputs()) {
				if(!active.remove(node)) throw new IllegalStateException("INCREMENTAL_STALE_BUCKET");
				releaseMarginals(node);
				for(Variable v : node.message.scope()) { incidence.get(v).remove(node); affected.add(v); }
			}
			Node replacement = new Node(nextId++,owners,merged);
			if(merged.scope().isEmpty()) seal(replacement);
			else {
				active.add(replacement);
				for(Variable v : merged.scope()) incidence.get(v).add(replacement);
			}
			for(Variable v : affected) {
				if(incidence.get(v).isEmpty()) incidence.remove(v);
				refresh(v);
			}
			scoringNanos += System.nanoTime()-start;
			if(incidence.containsKey(candidate.pivot()))
				throw new IllegalStateException("INCREMENTAL_BUCKET_PIVOT_NOT_ELIMINATED");
			if(positions.get(candidate.pivot())<problem.decisionCount() && candidate.pivot().domainSize()>1)
				internalDecisions++;
			merges++; slots = add(slots, merged.retainedCells());
			assignments = add(assignments, merged.assignments());
			updateLower();
			// The new bound may already certify the existing feasible Regional plan.
			if(!(options.earlyStop() && relativeGap(lower,upper)<=options.relativeGap())) {
				int[] candidateAssignment = incumbent.clone();
				merged.decodeInto(candidateAssignment, variables);
				accept(candidateAssignment);
			}
			checkpoint("MERGE");
		}
	}

	/** Private variables cannot affect another owner; project them once before bucket scheduling. */
	private void normalizePrivate() {
		int[] counts = new int[variables.size()];
		for(Node node : active) for(Variable v : node.message.scope()) counts[positions.get(v)]++;
		int[] candidate = incumbent.clone();
		long start = System.nanoTime();
		try {
			for(int index=0; index<active.size(); index++) {
				Node node = active.get(index);
				List<Variable> boundary = node.message.scope().stream()
					.filter(v -> v.domainSize()>1 && counts[positions.get(v)]>1).toList();
				if(boundary.size()==node.message.scope().size()) continue;
				BoundaryMessage projected;
				boolean singletonOnly=node.message.scope().stream().filter(v -> v.domainSize()>1).count()==boundary.size();
				if(singletonOnly) {
					// Axis size one changes neither values nor decisions: retain an alias, not a DP table.
					projected=ExactCategoricalSolver.projectSingletons(node.message);
				}
				else {
					long work=cells(node.message.scope()), needed=multiply(cells(boundary),4);
					if(work>options.maximumMergeAssignments() || add(slots,needed)>options.maximumRetainedSlots()) {
						resourceRejected++; continue;
					}
					try { projected=ExactCategoricalSolver.mergeBoundary(List.of(node.message),boundary,limits,
						options.maximumMergeAssignments()); }
					catch(IllegalArgumentException | IllegalStateException ex) {
						if(ex.getMessage()==null || !ex.getMessage().startsWith("INCREMENTAL_MESSAGE_RESOURCE")) throw ex;
						resourceRejected++; continue;
					}
					projected.decodeInto(candidate,variables);
				}
				active.set(index,new Node(nextId++,node.owners,projected));
				merges++; slots = add(slots,projected.retainedCells());
				assignments = add(assignments,projected.assignments());
			}
		}
		finally { dpNanos += System.nanoTime()-start; }
		accept(candidate);
	}

	/** Cached full-variable buckets. No pair enumeration and no stale partial bucket execution. */
	private Candidate choose() {
		long start = System.nanoTime();
		try {
			Candidate best = null;
			double bestScore = -1;
			int count = 0;
			// Restrict gain ranking to the cheapest separator size to avoid destructive fill.
			long cheapestSlots = queue.isEmpty() ? 0 : queue.first().slots();
			for(Candidate c : queue) {
				if(c.slots()!=cheapestSlots || count++>=options.scoredCandidates()) break;
				Set<Node> current = incidence.get(c.pivot());
				if(current==null || current.size()!=c.inputs().size() || !current.containsAll(c.inputs()))
					throw new IllegalStateException("INCREMENTAL_STALE_BUCKET");
				double score = conflict(c) / Math.max(1d,c.work());
				if(best==null || score>bestScore) { best=c; bestScore=score; }
			}
			return best;
		}
		finally { scoringNanos += System.nanoTime()-start; }
	}

	private void discard(Variable v) {
		Candidate previous = candidates.remove(v);
		if(previous!=null) queue.remove(previous);
	}

	private void refresh(Variable v) {
		discard(v);
		Set<Node> members = incidence.get(v);
		if(members==null || members.isEmpty()) return;
		List<Node> inputs = members.stream().sorted(Comparator.comparingInt(n -> n.owners[0])).toList();
		Set<Variable> union = new LinkedHashSet<>();
		for(Node node : inputs) union.addAll(node.message.scope());
		List<Variable> boundary = union.stream().filter(x -> !x.equals(v))
			.sorted(Comparator.comparingInt(positions::get)).toList();
		long evaluations = cells(new ArrayList<>(union)), output = cells(boundary);
		long needed = multiply(output,4);
		// Retained slots can change when diagnostics are released. Admission is repeated at execution.
		if(evaluations>options.maximumMergeAssignments() || output>limits.maximumFactorCells()) {
			resourceRejected++; return;
		}
		Candidate candidate = new Candidate(v,inputs,boundary,evaluations,
			add(multiply(evaluations,inputs.size()),output),needed,positions.get(v));
		candidates.put(v,candidate); queue.add(candidate);
	}

	private double conflict(Candidate candidate) {
		if(candidate.inputs().size()<2) return 0;
		Variable v = candidate.pivot();
		double[] sums = new double[v.domainSize()];
		double separate = 0;
		for(Node node : candidate.inputs()) {
			double[] values = marginals(node,v);
			if(values==null) return 0;
			for(int i=0; i<sums.length; i++) sums[i]+=values[i];
			separate += node.minimum;
		}
		double joined = Arrays.stream(sums).min().orElseThrow();
		return Double.isFinite(joined-separate) ? Math.max(0,joined-separate) : 0;
	}

	private double[] marginals(Node node, Variable v) {
		double[] found = node.marginals.get(v); if(found!=null) return found;
		if(add(slots,v.domainSize())>options.maximumRetainedSlots()) return null;
		found = node.message.minMarginals(v);
		slots += found.length; node.marginals.put(v,found); return found;
	}

	private void releaseMarginals(Node node) {
		for(double[] values : node.marginals.values()) slots -= values.length;
		node.marginals.clear();
	}

	private double validate(int[] assignment) {
		long start = System.nanoTime();
		try {
			List<Integer> reduced = Arrays.stream(assignment).boxed().toList();
			List<Integer> encoded = root.expandAssignment(reduced);
			double frozen = CertifiedRegionalOptimizer.evaluate(variables,root.factors(),reduced);
			double original = CertifiedRegionalOptimizer.evaluate(problem.variables(),problem.factors(),encoded);
			double canonical = problem.evaluate(encoded.subList(0,problem.decisionCount()));
			if(!Double.isFinite(canonical) || Double.doubleToRawLongBits(frozen) != Double.doubleToRawLongBits(canonical)
				|| Double.doubleToRawLongBits(original) != Double.doubleToRawLongBits(canonical))
				throw new IllegalStateException("INCREMENTAL_REGIONAL_CANONICAL_MISMATCH|frozen=" + frozen
					+ "|original=" + original + "|canonical=" + canonical);
			return canonical;
		}
		finally { validationNanos += System.nanoTime() - start; }
	}
	private void accept(int[] candidate) {
		if(Arrays.equals(candidate, incumbent)) return;
		double objective = validate(candidate);
		if(objective < lower) throw new IllegalStateException("INCREMENTAL_CANDIDATE_BELOW_PUBLISHED_LOWER");
		if(objective > upper) throw new IllegalStateException("INCREMENTAL_CONDITIONAL_DP_WORSENED");
		if(objective < upper) { incumbent = candidate; upper = objective; improvements++; }
	}
	private void updateLower() {
		double bound = sealedLower;
		for(Node node : active) {
			double term = node.lower;
			if(!Double.isFinite(term) || term < 0) throw new IllegalStateException("INCREMENTAL_REGIONAL_BOUND_INVALID");
			bound = bound == 0 ? term : term == 0 ? bound : Math.max(0,Math.nextDown(bound + term));
		}
		double published = Math.max(lower,bound);
		if(published > upper) throw new IllegalStateException("INCREMENTAL_REGIONAL_BOUND_ABOVE_INCUMBENT");
		lower = published;
	}
	private void verifyCover() {
		BitSet owners = new BitSet();
		for(Node node : java.util.stream.Stream.concat(active.stream(),sealed.stream()).toList()) {
			for(int owner : node.owners) {
				if(owner < 0 || owner >= root.factors().size() || owners.get(owner))
					throw new IllegalStateException("INCREMENTAL_FACTOR_DOUBLE_OWNER");
				owners.set(owner);
			}
		}
		if(owners.cardinality() != root.factors().size()) throw new IllegalStateException("INCREMENTAL_FACTOR_COVER_MISSING");
	}
	private void seal(Node node) {
		sealed.add(node);
		sealedLower = sealedLower==0 ? node.lower : node.lower==0 ? sealedLower
			: Math.max(0,Math.nextDown(sealedLower+node.lower));
	}
	private boolean exact() { return active.isEmpty(); }
	private void checkpoint(String phase) {
		Checkpoint cp = new Checkpoint(phase,merges,active.size()+sealed.size(),lower,upper,relativeGap(lower,upper),
			System.nanoTime()-started,dpNanos,scoringNanos,validationNanos,assignments,slots,
			improvements,resourceRejected,internalDecisions);
		checkpoints.add(cp); observer.accept(cp);
	}
	private Result finish(String reason) {
		if(!reason.equals("RESOURCE_INITIAL")) verifyCover();
		checkpoint(reason);
		List<Integer> encoded = root.expandAssignment(Arrays.stream(incumbent).boxed().toList());
		return new Result(List.copyOf(encoded.subList(0,problem.decisionCount())),lower,upper,reason,List.copyOf(checkpoints));
	}
	static double relativeGap(double lower, double upper) {
		if(upper == lower) return 0;
		return lower > 0 ? Math.nextUp(Math.nextUp(upper-lower)/lower) : Double.POSITIVE_INFINITY;
	}
	private static long multiply(long a,long b) { return a>Long.MAX_VALUE/b ? Long.MAX_VALUE : a*b; }
	private static long cells(List<Variable> scope) {
		long value = 1;
		for(Variable v : scope) { if(value > Long.MAX_VALUE/v.domainSize()) return Long.MAX_VALUE; value *= v.domainSize(); }
		return value;
	}
	private static long add(long a,long b) { return a > Long.MAX_VALUE-b ? Long.MAX_VALUE : a+b; }
}
