/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.BoundaryMessage;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Disjoint factor ownership and persistent exact boundary messages. No Global restart. */
final class IncrementalRegionalOptimizer {
	static final String PREFIX = "sysds.fedplanner.regional.incremental.";
	private static final String REGIONAL_PREFIX = "sysds.fedplanner.regional.";
	private static final List<String> REMOVED_BUDGET_OPTIONS = List.of(
		"assignments", "retainedSlots", "timeMillis", "scoredCandidates");

	static void validateConfiguration() {
		String enabled = System.getProperty(PREFIX + "enabled");
		if(enabled != null && !"true".equalsIgnoreCase(enabled))
			throw new IllegalArgumentException("INCREMENTAL_REGIONAL_ENABLED_INVALID|value=" + enabled
				+ "|supported=true");
		String mode = System.getProperty(REGIONAL_PREFIX + "mode");
		if(mode != null && !"off".equalsIgnoreCase(mode))
			throw new IllegalArgumentException("REGIONAL_MODE_REMOVED|mode=" + mode + "|supported=off");
		String algorithm = System.getProperty(REGIONAL_PREFIX + "algorithm");
		if(algorithm != null && !"legacy".equalsIgnoreCase(algorithm))
			throw new IllegalArgumentException("REGIONAL_ALGORITHM_REMOVED|algorithm=" + algorithm
				+ "|supported=legacy");
		String initialBound = System.getProperty(REGIONAL_PREFIX + "initialBound");
		if(initialBound != null)
			throw new IllegalArgumentException("REGIONAL_INITIAL_BOUND_REMOVED|value=" + initialBound);
		for(String option : REMOVED_BUDGET_OPTIONS) {
			String value = System.getProperty(PREFIX + option);
			if(value != null)
				throw new IllegalArgumentException("INCREMENTAL_REGIONAL_"
					+ option.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT)
					+ "_REMOVED|value=" + value);
		}
	}

	record Options(double relativeGap, long maximumMergeAssignments, long maximumRetainedSlots,
		long timeMillis, int scoredCandidates, boolean earlyStop) {
		Options {
			boolean production = maximumMergeAssignments == 0 && maximumRetainedSlots == 0
				&& timeMillis == 0 && scoredCandidates == 0;
			boolean boundedTest = maximumMergeAssignments > 0 && maximumRetainedSlots > 0
				&& timeMillis >= 0 && scoredCandidates > 0;
			if(!Double.isFinite(relativeGap) || relativeGap < 0 || !(production || boundedTest))
				throw new IllegalArgumentException("INCREMENTAL_REGIONAL_OPTIONS_INVALID");
		}
		static Options configured() {
			validateConfiguration();
			return new Options(Double.parseDouble(System.getProperty(PREFIX + "relativeGap", "0.05")),
				0, 0, 0, 0,
				Boolean.parseBoolean(System.getProperty(PREFIX + "earlyStop", "true")));
		}
		boolean boundedTest() { return maximumMergeAssignments > 0; }
	}
	record Checkpoint(String phase, int merges, int activeClusters, double lower, double upper,
		double relativeGap, long elapsedNanos, long dpNanos, long scoringNanos, long validationNanos,
		long assignments, long retainedSlots, int improvements, int resourceRejected,
		int internalDecisions, int conditionalAttempts, int conditionalImprovements) { }
	record Result(List<Integer> assignment, double lower, double upper, String stopReason,
		List<Checkpoint> checkpoints) { }
	@FunctionalInterface
	interface BoundaryMerger {
		BoundaryMessage merge(List<BoundaryMessage> messages, List<Variable> boundary,
			Limits limits, Long maximumAssignments,
			ExactCategoricalSolver.BoundaryMergeCounters counters);
	}
	private static final BoundaryMerger DEFAULT_BOUNDARY_MERGER =
		(messages,boundary,limits,maximumAssignments,counters) -> maximumAssignments == null
			? ExactCategoricalSolver.mergeBoundary(messages,boundary,limits,counters)
			: ExactCategoricalSolver.mergeBoundary(messages,boundary,limits,maximumAssignments,counters);

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
	private record Neighborhood(int[] block, double potential, int interactionDepth,
		long assignments, long work, int rank) { }
	private record ConditionalBlockKey(List<Integer> positions) {
		private ConditionalBlockKey { positions = List.copyOf(positions); }
		private static ConditionalBlockKey of(int[] block) {
			int[] canonical = block.clone();
			Arrays.sort(canonical);
			for(int index = 1; index < canonical.length; index++)
				if(canonical[index] == canonical[index - 1])
					throw new IllegalArgumentException("INCREMENTAL_CONDITIONAL_BLOCK_DUPLICATE");
			return new ConditionalBlockKey(Arrays.stream(canonical).boxed().toList());
		}
	}
	private static final class SuccessfulConditionalResult {
		private final int[] outsideAssignment;
		private final int[] blockValues;
		private SuccessfulConditionalResult(int[] outsideAssignment, int[] blockValues) {
			this.outsideAssignment = outsideAssignment.clone();
			this.blockValues = blockValues.clone();
		}
	}
	private static final class CanonicalCandidateMismatchException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		private CanonicalCandidateMismatchException(String message) { super(message); }
	}
	/** One last validated exact optimum per canonical block and its fixed original boundary. */
	static final class ConditionalReplayCache {
		private final Map<ConditionalBlockKey,SuccessfulConditionalResult> entries = new HashMap<>();

		boolean applyIfPresent(int[] source, int[] block) {
			ConditionalBlockKey key = ConditionalBlockKey.of(block);
			SuccessfulConditionalResult entry = entries.get(key);
			if(entry == null || entry.outsideAssignment.length != source.length)
				return false;
			int blockIndex = 0;
			for(int position = 0; position < source.length; position++) {
				if(blockIndex < key.positions().size() && key.positions().get(blockIndex) == position) {
					blockIndex++;
					continue;
				}
				if(source[position] != entry.outsideAssignment[position])
					return false;
			}
			for(int index = 0; index < key.positions().size(); index++)
				source[key.positions().get(index)] = entry.blockValues[index];
			return true;
		}

		void rememberSuccessful(int[] source, int[] block, List<Integer> solvedValues) {
			if(solvedValues.size() != block.length)
				throw new IllegalArgumentException("INCREMENTAL_CONDITIONAL_RESULT_SIZE_MISMATCH");
			ConditionalBlockKey key = ConditionalBlockKey.of(block);
			int[] canonicalValues = new int[block.length];
			for(int canonical = 0; canonical < key.positions().size(); canonical++) {
				int position = key.positions().get(canonical);
				int supplied = 0;
				while(block[supplied] != position)
					supplied++;
				canonicalValues[canonical] = solvedValues.get(supplied);
			}
			entries.put(key,new SuccessfulConditionalResult(source,canonicalValues));
		}

		int size() { return entries.size(); }
		void clear() { entries.clear(); }
	}
	private static final Comparator<Candidate> ORDER = Comparator.comparingLong(Candidate::slots)
		.thenComparingLong(Candidate::work).thenComparingInt(Candidate::rank);
	private static final Comparator<Neighborhood> NEIGHBORHOOD_ORDER =
		Comparator.comparingDouble(Neighborhood::potential).reversed()
			.thenComparing(Comparator.comparingInt(Neighborhood::interactionDepth).reversed())
			.thenComparingLong(Neighborhood::assignments)
			.thenComparingLong(Neighborhood::work).thenComparingInt(Neighborhood::rank);

	private final RegionalSearchProblem problem;
	private final ExactPhysicalReducedSolver.CompactModel root;
	private final IncrementalRegionalSeed.Prepared seedPreparation;
	private final List<Variable> variables;
	private final Map<Variable,Integer> positions = new HashMap<>();
	private final Limits limits;
	private final Options options;
	private final Consumer<Checkpoint> observer;
	private final ExactCategoricalSolver.BoundaryMergeCounters mergeCounters;
	private final BoundaryMerger boundaryMerger;
	private final RegionalSearchProblem.PreparedFactorEvaluation reducedFactorEvaluation;
	private final BitSet coverOwners;
	private final List<Node> active = new ArrayList<>();
	private final List<Node> sealed = new ArrayList<>();
	private final Map<Variable,Set<Node>> incidence = new LinkedHashMap<>();
	private final Map<Variable,Candidate> candidates = new HashMap<>();
	private final TreeSet<Candidate> queue = new TreeSet<>(ORDER);
	private final Map<Candidate,Double> successfulConflictScores = new IdentityHashMap<>();
	private final Map<String,Neighborhood> rejectedNeighborhoods = new LinkedHashMap<>();
	private final ConditionalReplayCache conditionalReplayCache = new ConditionalReplayCache();
	private final List<Checkpoint> checkpoints = new ArrayList<>();
	private final long started = System.nanoTime();
	private int[] incumbent;
	private double lower, upper, sealedLower;
	private long slots, assignments, dpNanos, scoringNanos, validationNanos;
	private int nextId, merges, improvements, resourceRejected, internalDecisions;
	private int conditionalAttempts, conditionalImprovements;
	private long conflictScoreEvaluations, conflictScoreCacheHits, incompleteConflictScores;
	private boolean coverReleased;

	private IncrementalRegionalOptimizer(RegionalSearchProblem problem,
		ExactPhysicalReducedSolver.CompactModel root, List<Integer> originalSeed, Limits limits,
		Options options, Consumer<Checkpoint> observer, ExactCategoricalSolver.BoundaryMergeCounters mergeCounters,
		BoundaryMerger boundaryMerger) {
		this.problem = problem; this.root = root; this.variables = root.variables();
		this.limits = limits; this.options = options; this.observer = observer;
		this.mergeCounters = mergeCounters; this.boundaryMerger = boundaryMerger;
		this.reducedFactorEvaluation =
			RegionalSearchProblem.PreparedFactorEvaluation.prepare(variables, root.factors());
		this.coverOwners = new BitSet(root.factors().size());
		for(int i=0; i<variables.size(); i++) positions.put(variables.get(i),i);
		seedPreparation = IncrementalRegionalSeed.prepare(root);
		incumbent = seedPreparation.lift(originalSeed, limits);
		upper = validate(incumbent);
	}

	static Result optimize(RegionalSearchProblem problem, ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> originalSeed, Limits limits,
		Options options, Consumer<Checkpoint> observer) {
		return optimize(problem, root, originalSeed, limits, options, observer, null);
	}

	/** Optional solve-local work accounting; it does not affect the planner's resource budgets. */
	static Result optimize(RegionalSearchProblem problem, ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> originalSeed, Limits limits, Options options, Consumer<Checkpoint> observer,
		ExactCategoricalSolver.BoundaryMergeCounters mergeCounters) {
		return new IncrementalRegionalOptimizer(problem, root, originalSeed, limits, options, observer, mergeCounters,
			DEFAULT_BOUNDARY_MERGER)
			.run();
	}

	static Result optimize(RegionalSearchProblem problem, ExactPhysicalReducedSolver.CompactModel root,
		List<Integer> originalSeed, Limits limits, Options options, Consumer<Checkpoint> observer,
		ExactCategoricalSolver.BoundaryMergeCounters mergeCounters, BoundaryMerger boundaryMerger) {
		return new IncrementalRegionalOptimizer(problem, root, originalSeed, limits, options, observer, mergeCounters,
			java.util.Objects.requireNonNull(boundaryMerger, "boundaryMerger")).run();
	}

	private Result run() {
		checkpoint("BOOTSTRAP");
		// Dense compact-model tables already exist and leaf messages borrow them. The
		// retained-slot check below exists only for explicit bounded unit-test runs;
		// production allocations are protected by PlannerResourceGuard.
		long initialSlots = 0;
		for(Factor factor : root.factors())
			initialSlots = add(initialSlots, ExactCategoricalSolver.boundaryLeafRetainedCells(factor));
		if(options.boundedTest() && initialSlots > options.maximumRetainedSlots())
			return finish("RESOURCE_INITIAL");
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
		if(options.boundedTest() && slots > options.maximumRetainedSlots())
			throw new IllegalStateException("INCREMENTAL_LEAF_ACCOUNTING_MISMATCH");
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
		if(!exact() && !(options.earlyStop() && relativeGap(lower,upper)<=options.relativeGap()))
			refineSeedBoundary();
		boolean scheduled = false;
		while(true) {
			if(exact()) {
				int[] candidate = incumbent.clone();
				for(Node node : sealed)
					if(node.message.improvesBacktrace(candidate,variables))
						node.message.decodeInto(candidate,variables);
				accept(candidate);
				lower = upper;
				return finish("EXACT");
			}
			if(options.earlyStop() && relativeGap(lower, upper) <= options.relativeGap())
				return finish("TARGET_REACHED");
			if(options.boundedTest() && options.timeMillis() > 0
				&& (System.nanoTime() - started) / 1_000_000 >= options.timeMillis())
				return finish("TIME");
			if(!scheduled) {
				long start = System.nanoTime();
				for(Variable v : incidence.keySet()) refresh(v);
				scoringNanos += System.nanoTime()-start;
				scheduled = true;
			}
			Candidate candidate = choose();
			if(candidate == null) {
				rememberActiveNeighborhoods();
				releaseCertifiedCover();
				refineNeighborhoods("CONDITIONAL");
				return finish("RESOURCE");
			}
			// Diagnostics are optional: never reject required DP output because of an evictable cache.
			if(options.boundedTest() && add(slots,candidate.slots()) > options.maximumRetainedSlots()) {
				for(Node node : active)
					releaseMarginals(node);
				successfulConflictScores.clear();
			}
			if(options.boundedTest() && add(slots,candidate.slots()) > options.maximumRetainedSlots()) {
				rememberRejectedNeighborhood(candidate);
				discard(candidate.pivot()); resourceRejected++; continue;
			}
			BoundaryMessage merged;
			try {
				merged = mergeCandidate(candidate);
			}
			catch(IllegalArgumentException | IllegalStateException ex) {
				if(!(ex instanceof IllegalArgumentException)
					|| !RegionalSearchProblem.isResourceLimit((IllegalArgumentException)ex)) throw ex;
				if(ex instanceof PlannerResourceGuard.ResourceExhaustedException exhausted
					&& exhausted.actualAllocationFailure()) {
					resourceRejected++;
					candidate = null;
					releaseAfterActualMergeAllocationFailure();
					traceResourceRejection("merge",ex);
					return finish("RESOURCE");
				}
				traceResourceRejection("merge",ex);
				discard(candidate.pivot()); resourceRejected++;
				continue;
			}
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
				if(merged.improvesBacktrace(candidateAssignment,variables)) {
					merged.decodeInto(candidateAssignment, variables);
					accept(candidateAssignment);
				}
			}
			checkpoint("MERGE");
		}
	}

	private BoundaryMessage mergeCandidate(Candidate candidate) {
		long started = System.nanoTime();
		try {
			List<BoundaryMessage> messages = new ArrayList<>(candidate.inputs().size());
			for(int index=0; index<candidate.inputs().size(); index++)
				messages.add(candidate.inputs().get(index).message);
			Long maximumAssignments = options.boundedTest() ? options.maximumMergeAssignments() : null;
			return boundaryMerger.merge(messages,candidate.boundary(),limits,maximumAssignments,mergeCounters);
		}
		catch(OutOfMemoryError failure) {
			throw PlannerResourceGuard.allocationFailure("regional-merge",-1L,
				"boundary-input-list",failure);
		}
		finally {
			dpNanos += System.nanoTime()-started;
		}
	}

	/**
	 * Improve the feasible seed before constructing any joint boundary message.
	 * The owned-factor neighborhoods retain every crossing constraint, close encoded
	 * auxiliaries and fix external original decisions to the incumbent. Conditional
	 * exact solves may use temporary tables; the persistent cover and its lower bound
	 * remain unchanged. Recollect neighborhoods after later merges/resource rejection.
	 */
	private void refineSeedBoundary() {
		long start = System.nanoTime();
		rememberActiveNeighborhoods();
		scoringNanos += System.nanoTime()-start;
		try {
			refineNeighborhoods(null);
		}
		finally {
			rejectedNeighborhoods.clear();
		}
		verifyCover();
		checkpoint("SEED_BOUNDARY");
	}

	/**
	 * Retains complete bucket neighborhoods whose persistent boundary message did
	 * not fit available system resources. Explicit bounded tests may restrict the
	 * retained diagnostic set. The neighborhood is still a valid
	 * conditional subproblem: every factor crossing it is fixed to the current
	 * feasible incumbent when it is solved below.
	 */
	private void rememberRejectedNeighborhood(Candidate candidate) {
		TreeSet<Integer> originals = new TreeSet<>();
		for(Node node : candidate.inputs())
			for(Variable variable : node.message.scope()) {
				int position = positions.get(variable);
				if(position < problem.decisionCount() && variable.domainSize() > 1)
					originals.add(position);
			}
		if(originals.isEmpty())
			return;
		int[] block = originals.stream().mapToInt(Integer::intValue).toArray();
		String key = Arrays.toString(block);
		Neighborhood neighborhood = new Neighborhood(block,0d,-1,candidate.assignments(),
			candidate.work(), candidate.rank());
		rememberNeighborhood(key,neighborhood);
	}

	/** Rank complete retained regions by their exact current-to-minimum owner cost gap. */
	private void rememberActiveNeighborhoods() {
		List<List<Integer>> factorIncidence = new ArrayList<>(variables.size());
		for(int variable=0; variable<variables.size(); variable++)
			factorIncidence.add(new ArrayList<>());
		for(int factor=0; factor<root.factors().size(); factor++)
			for(Variable variable : root.factors().get(factor).scope())
				factorIncidence.get(positions.get(variable)).add(factor);
		for(Node node : active) {
			double current = node.message.valueForAssignment(incumbent,variables);
			double potential = current-node.minimum;
			if(!Double.isFinite(potential) || potential <= 0d)
				continue;
			TreeSet<Integer> base = ownerOriginals(node.owners);
			// A cost owner can contain only a singleton source and an activation
			// auxiliary. Its mutable consumers are still reachable through the latter.
			rememberOwnerNeighborhood(ownerOriginalClosure(node.owners,factorIncidence),
				potential,2,node);
			rememberOwnerNeighborhood(base,potential,0,node);
		}
	}

	private TreeSet<Integer> ownerOriginals(int[] owners) {
		TreeSet<Integer> originals = new TreeSet<>();
		for(int owner : owners)
			for(Variable variable : root.factors().get(owner).scope()) {
				int position = positions.get(variable);
				if(position < problem.decisionCount() && variable.domainSize() > 1)
					originals.add(position);
			}
		return originals;
	}

	private void rememberOwnerNeighborhood(TreeSet<Integer> originals, double potential,
		int interactionDepth, Node node) {
		if(originals.isEmpty())
			return;
		int[] block = originals.stream().mapToInt(Integer::intValue).toArray();
		long blockAssignments = 1L;
		for(int original : block)
			blockAssignments = multiply(blockAssignments,variables.get(original).domainSize());
		rememberNeighborhood(Arrays.toString(block),new Neighborhood(block,potential,interactionDepth,
			blockAssignments,node.message.assignments(),node.id));
	}

	/** Follow encoded auxiliaries to every original decision on the same exact interaction boundary. */
	private TreeSet<Integer> ownerOriginalClosure(int[] owners,
		List<List<Integer>> factorIncidence) {
		return ownerOriginalClosure(root.factors(),variables,positions,problem.decisionCount(),
			owners,factorIncidence);
	}

	private static TreeSet<Integer> ownerOriginalClosure(List<Factor> factors,
		List<Variable> variables, Map<Variable,Integer> positions, int decisionCount,
		int[] owners, List<List<Integer>> factorIncidence) {
		TreeSet<Integer> originals = new TreeSet<>();
		BitSet visitedFactors = new BitSet(factors.size());
		BitSet visitedAuxiliaries = new BitSet(variables.size());
		java.util.ArrayDeque<Integer> auxiliaries = new java.util.ArrayDeque<>();
		for(int owner : owners) {
			visitedFactors.set(owner);
			collectInteractionVariables(factors.get(owner),variables,positions,decisionCount,
				originals,visitedAuxiliaries,auxiliaries);
		}
		// Cost owners are often unary decisions in a short operator chain. Expand two
		// original-decision rings, closing every auxiliary component within each ring.
		// The exact resource preflight remains the authority for admitting the block.
		TreeSet<Integer> firstRing = new TreeSet<>(originals);
		expandOriginalRing(firstRing,factors,variables,positions,decisionCount,factorIncidence,
			visitedFactors,visitedAuxiliaries,auxiliaries,originals);
		drainAuxiliaries(factors,variables,positions,decisionCount,factorIncidence,
			visitedFactors,visitedAuxiliaries,auxiliaries,originals);
		TreeSet<Integer> secondRing = new TreeSet<>(originals);
		secondRing.removeAll(firstRing);
		expandOriginalRing(secondRing,factors,variables,positions,decisionCount,factorIncidence,
			visitedFactors,visitedAuxiliaries,auxiliaries,originals);
		drainAuxiliaries(factors,variables,positions,decisionCount,factorIncidence,
			visitedFactors,visitedAuxiliaries,auxiliaries,originals);
		return originals;
	}

	private static void expandOriginalRing(Set<Integer> ring, List<Factor> factors,
		List<Variable> variables, Map<Variable,Integer> positions, int decisionCount,
		List<List<Integer>> factorIncidence, BitSet visitedFactors, BitSet visitedAuxiliaries,
		java.util.ArrayDeque<Integer> auxiliaries, TreeSet<Integer> originals) {
		for(int original : ring)
			for(int factor : factorIncidence.get(original)) {
				if(visitedFactors.get(factor))
					continue;
				visitedFactors.set(factor);
				collectInteractionVariables(factors.get(factor),variables,positions,decisionCount,
					originals,visitedAuxiliaries,auxiliaries);
			}
	}

	private static void drainAuxiliaries(List<Factor> factors, List<Variable> variables,
		Map<Variable,Integer> positions, int decisionCount, List<List<Integer>> factorIncidence,
		BitSet visitedFactors, BitSet visitedAuxiliaries,
		java.util.ArrayDeque<Integer> auxiliaries, TreeSet<Integer> originals) {
		while(!auxiliaries.isEmpty()) {
			int auxiliary = auxiliaries.removeFirst();
			for(int factor : factorIncidence.get(auxiliary)) {
				if(visitedFactors.get(factor))
					continue;
				visitedFactors.set(factor);
				collectInteractionVariables(factors.get(factor),variables,positions,decisionCount,
					originals,visitedAuxiliaries,auxiliaries);
			}
		}
	}

	private static void collectInteractionVariables(Factor factor, List<Variable> variables,
		Map<Variable,Integer> positions, int decisionCount, TreeSet<Integer> originals,
		BitSet visitedAuxiliaries, java.util.ArrayDeque<Integer> auxiliaries) {
		for(Variable variable : factor.scope()) {
			int position = positions.get(variable);
			if(position < decisionCount) {
				if(variable.domainSize() > 1)
					originals.add(position);
			}
			else if(!visitedAuxiliaries.get(position)) {
				visitedAuxiliaries.set(position);
				auxiliaries.add(position);
			}
		}
	}

	static List<Integer> ownerOriginalClosureForTest(List<Variable> variables,
		List<Factor> factors, int decisionCount, int... owners) {
		Map<Variable,Integer> positions = new HashMap<>();
		List<List<Integer>> incidence = new ArrayList<>(variables.size());
		for(int variable=0; variable<variables.size(); variable++) {
			positions.put(variables.get(variable),variable);
			incidence.add(new ArrayList<>());
		}
		for(int factor=0; factor<factors.size(); factor++)
			for(Variable variable : factors.get(factor).scope())
				incidence.get(positions.get(variable)).add(factor);
		return List.copyOf(ownerOriginalClosure(factors,variables,positions,decisionCount,
			owners,incidence));
	}

	private void rememberNeighborhood(String key, Neighborhood neighborhood) {
		Neighborhood previous = rejectedNeighborhoods.get(key);
		if(previous == null || NEIGHBORHOOD_ORDER.compare(neighborhood,previous) < 0)
			rejectedNeighborhoods.put(key,neighborhood);
		if(!options.boundedTest() || rejectedNeighborhoods.size() <= options.scoredCandidates())
			return;
		Neighborhood worst = rejectedNeighborhoods.values().stream()
			.max(NEIGHBORHOOD_ORDER).orElseThrow();
		rejectedNeighborhoods.remove(Arrays.toString(worst.block()));
	}

	/**
	 * Uses exact conditional improvement for the initial seed or after a resource
	 * rejection. No persistent boundary message or lower bound is changed. A null
	 * phase aggregates the initial pass into one SEED_BOUNDARY checkpoint.
	 */
	private void refineNeighborhoods(String phase) {
		long started = System.nanoTime(), priorValidation = validationNanos;
		try {
			refineNeighborhoodsImpl(phase);
		}
		finally {
			dpNanos += System.nanoTime()-started-(validationNanos-priorValidation);
		}
	}

	private void refineNeighborhoodsImpl(String phase) {
		long conditionalCells = options.boundedTest()
			? Math.min(limits.maximumMaterializedCells(),options.maximumRetainedSlots())
			: limits.maximumMaterializedCells();
		Limits conditionalLimits = new Limits(Math.min(limits.maximumFactorCells(),conditionalCells),
			conditionalCells);
		// The root is immutable throughout this pass. Preparation keys conditioned
		// tables by the actual fixed boundary, including changes to the incumbent.
		boolean compact = LocalCategoricalOptimizer.configuredCompaction();
		SharedRegionalPreparation preparation = options.boundedTest()
			? new SharedRegionalPreparation(problem,limits,limits,
				options.maximumMergeAssignments(),options.maximumRetainedSlots(),compact)
			: new SharedRegionalPreparation(problem,limits,compact);
		try {
			for(Neighborhood neighborhood : rejectedNeighborhoods.values().stream()
				.sorted(NEIGHBORHOOD_ORDER).toList()) {
				if(options.earlyStop() && relativeGap(lower,upper)<=options.relativeGap())
					return;
				if(options.boundedTest() && options.timeMillis() > 0
					&& (System.nanoTime()-started)/1_000_000 >= options.timeMillis())
					return;
				List<Integer> expanded = root.expandAssignment(Arrays.stream(incumbent).boxed().toList());
				int[] source = expanded.subList(0,problem.decisionCount()).stream()
					.mapToInt(Integer::intValue).toArray();
				if(FederatedPlannerTrace.isEnabled())
					FederatedPlannerTrace.logGlobal("DP-ConditionalNeighborhood",
						"potential=" + neighborhood.potential() + " originalVariables="
							+ neighborhood.block().length + " estimatedAssignments="
							+ neighborhood.assignments() + " interactionDepth="
							+ neighborhood.interactionDepth() + " block=" + Arrays.toString(neighborhood.block()));
				boolean replayed = conditionalReplayCache.applyIfPresent(source,neighborhood.block());
				SharedRegionalPreparation.PreparedConditionalSolver solver = null;
				if(!replayed) {
					solver = preparation.prepareConditional(source,neighborhood.block(),root,incumbent);
					if(solver == null) {
						if(FederatedPlannerTrace.isEnabled())
							FederatedPlannerTrace.logGlobal("DP-ConditionalNeighborhoodRejected",
								"interactionDepth=" + neighborhood.interactionDepth()
									+ " reason=" + preparation.lastFallbackReason());
						continue;
					}
					conditionalAttempts++;
				}
				int priorImprovements = improvements;
				try {
					List<Integer> solvedValues = null;
					boolean skipLegacyLift = false;
					if(!replayed) {
						SharedRegionalPreparation.ConditionalResult conditional = solver.solve(incumbent);
						ExactCategoricalSolver.Result solved = conditional.block();
						if(solved.assignmentInVariableOrder().size()!=neighborhood.block().length)
							throw new IllegalStateException("INCREMENTAL_CONDITIONAL_RESULT_SIZE_MISMATCH");
						solvedValues = solved.assignmentInVariableOrder();
						for(int index=0; index<neighborhood.block().length; index++)
							source[neighborhood.block()[index]] = solvedValues.get(index);
						int[] witness = conditional.rootWitness();
						skipLegacyLift = witness != null && mappedWitnessProvesNoImprovement(witness);
					}
					// Conditioning can turn a canonical improvement/tie into a rounded
					// local tie. Preserve the incumbent unless the full objective improves.
					if(!skipLegacyLift)
						accept(seedPreparation.lift(
							Arrays.stream(source).boxed().toList(),conditionalLimits),false);
					if(!replayed)
						conditionalReplayCache.rememberSuccessful(source,neighborhood.block(),solvedValues);
				}
				catch(IllegalArgumentException failure) {
					if(!RegionalSearchProblem.isResourceLimit(failure))
						throw failure;
				}
				if(improvements > priorImprovements)
					conditionalImprovements++;
				if(phase != null)
					checkpoint(phase);
			}
		}
		finally { preparation.trace(); }
	}

	/** Preserve the certified scalar bound, then release its no-longer-needed messages. */
	private void releaseCertifiedCover() {
		verifyCover();
		checkpoint("RESOURCE_COVER");
		for(Node node : active)
			releaseMarginals(node);
		active.clear(); sealed.clear(); incidence.clear(); candidates.clear(); queue.clear();
		successfulConflictScores.clear();
		slots = 0;
		coverReleased = true;
		checkpoint("CONDITIONAL_READY");
	}

	/** The merge is pure, so a failed allocation leaves this certified cover intact. */
	private void releaseAfterActualMergeAllocationFailure() {
		verifyCover();
		active.clear(); sealed.clear(); incidence.clear(); candidates.clear(); queue.clear();
		rejectedNeighborhoods.clear();
		conditionalReplayCache.clear();
		successfulConflictScores.clear();
		slots = 0;
		coverReleased = true;
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
					if(options.boundedTest() && (work>options.maximumMergeAssignments()
						|| add(slots,needed)>options.maximumRetainedSlots())) {
						resourceRejected++; continue;
					}
					try { projected = options.boundedTest()
						? ExactCategoricalSolver.mergeBoundary(List.of(node.message),boundary,limits,
							options.maximumMergeAssignments(),mergeCounters)
						: ExactCategoricalSolver.mergeBoundary(List.of(node.message),boundary,limits,mergeCounters); }
					catch(IllegalArgumentException | IllegalStateException ex) {
						if(!(ex instanceof IllegalArgumentException)
							|| !RegionalSearchProblem.isResourceLimit((IllegalArgumentException)ex)) throw ex;
						traceResourceRejection("private-projection",ex);
						resourceRejected++; continue;
					}
					if(projected.improvesBacktrace(candidate,variables))
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
				if(c.slots()!=cheapestSlots
					|| options.boundedTest() && count++>=options.scoredCandidates()) break;
				Set<Node> current = incidence.get(c.pivot());
				if(current==null || current.size()!=c.inputs().size() || !current.containsAll(c.inputs()))
					throw new IllegalStateException("INCREMENTAL_STALE_BUCKET");
				double score = conflictScore(c) / Math.max(1d,c.work());
				if(best==null || score>bestScore) { best=c; bestScore=score; }
			}
			return best;
		}
		finally { scoringNanos += System.nanoTime()-start; }
	}

	private void discard(Variable v) {
		Candidate previous = candidates.remove(v);
		if(previous!=null) {
			queue.remove(previous);
			successfulConflictScores.remove(previous);
		}
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
		if(options.boundedTest() && evaluations>options.maximumMergeAssignments()
			|| output>limits.maximumFactorCells()) {
			resourceRejected++; return;
		}
		Candidate candidate = new Candidate(v,inputs,boundary,evaluations,
			add(multiply(evaluations,inputs.size()),output),needed,positions.get(v));
		candidates.put(v,candidate); queue.add(candidate);
	}

	private double conflict(Candidate candidate) {
		if(candidate.inputs().size()<2) return 0;
		Variable v = candidate.pivot();
		double[] sums;
		try {
			sums = PlannerResourceGuard.allocateDoubles(v.domainSize(),"regional-conflict-sums");
		}
		catch(PlannerResourceGuard.ResourceExhaustedException exhausted) {
			traceResourceRejection("conflict-sums",exhausted);
			resourceRejected++;
			return Double.NaN;
		}
		double separate = 0;
		for(Node node : candidate.inputs()) {
			double[] values = marginals(node,v);
			if(values==null) return Double.NaN;
			for(int i=0; i<sums.length; i++) sums[i]+=values[i];
			separate += node.minimum;
		}
		double joined = Arrays.stream(sums).min().orElseThrow();
		return Double.isFinite(joined-separate) ? Math.max(0,joined-separate) : 0;
	}

	/** Reuses only complete scores over immutable candidates; resource failures remain retryable. */
	private double conflictScore(Candidate candidate) {
		Double cached = successfulConflictScores.get(candidate);
		if(cached != null) {
			conflictScoreCacheHits++;
			return cached;
		}
		conflictScoreEvaluations++;
		double score = conflict(candidate);
		if(Double.isNaN(score)) {
			incompleteConflictScores++;
			return 0d;
		}
		successfulConflictScores.put(candidate,score);
		return score;
	}

	private double[] marginals(Node node, Variable v) {
		double[] found = node.marginals.get(v); if(found!=null) return found;
		if(options.boundedTest() && add(slots,v.domainSize())>options.maximumRetainedSlots())
			return null;
		try {
			found = node.message.minMarginals(v);
		}
		catch(PlannerResourceGuard.ResourceExhaustedException exhausted) {
			traceResourceRejection("marginals",exhausted);
			resourceRejected++;
			return null;
		}
		slots += found.length; node.marginals.put(v,found); return found;
	}

	private void releaseMarginals(Node node) {
		for(double[] values : node.marginals.values()) slots -= values.length;
		node.marginals.clear();
	}

	private static void traceResourceRejection(String phase, RuntimeException failure) {
		if(FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("DP-RegionalResourceRejection",
				"phase=" + phase + " reason=" + failure.getMessage());
	}

	private double validate(int[] assignment) {
		long start = System.nanoTime();
		try {
			List<Integer> reduced = Arrays.stream(assignment).boxed().toList();
			List<Integer> encoded = root.expandAssignment(reduced);
			double frozen = reducedFactorEvaluation.evaluate(reduced);
			double original = problem.evaluatePreparedFactors(encoded);
			double canonical = problem.evaluate(encoded.subList(0,problem.decisionCount()));
			if(!Double.isFinite(canonical) || Double.doubleToRawLongBits(frozen) != Double.doubleToRawLongBits(canonical)
				|| Double.doubleToRawLongBits(original) != Double.doubleToRawLongBits(canonical))
				throw new CanonicalCandidateMismatchException("INCREMENTAL_REGIONAL_CANONICAL_MISMATCH|frozen=" + frozen
					+ "|original=" + original + "|canonical=" + canonical);
			return canonical;
		}
		finally { validationNanos += System.nanoTime() - start; }
	}
	private void accept(int[] candidate) {
		accept(candidate,true);
	}
	private void accept(int[] candidate, boolean requireNonIncreasing) {
		if(Arrays.equals(candidate, incumbent)) return;
		double objective = validate(candidate);
		if(objective < lower) throw new IllegalStateException("INCREMENTAL_CANDIDATE_BELOW_PUBLISHED_LOWER");
		if(requireNonIncreasing && objective > upper)
			throw new IllegalStateException("INCREMENTAL_CONDITIONAL_DP_WORSENED");
		if(objective < upper) { incumbent = candidate; upper = objective; improvements++; }
	}
	private boolean mappedWitnessProvesNoImprovement(int[] candidate) {
		if(Arrays.equals(candidate,incumbent))
			return true;
		double objective;
		try {
			objective = validate(candidate);
		}
		catch(CanonicalCandidateMismatchException incompatible) {
			return false;
		}
		if(objective < lower)
			throw new IllegalStateException("INCREMENTAL_CANDIDATE_BELOW_PUBLISHED_LOWER");
		// Retain the incumbent's exact auxiliary tie witness whenever the source plan
		// does not strictly improve. A strict improvement still uses legacy lifting so
		// subsequent partial-DP backtraces observe the same auxiliary assignment.
		return objective >= upper;
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
		coverOwners.clear();
		verifyCoverNodes(active);
		verifyCoverNodes(sealed);
		if(coverOwners.cardinality() != root.factors().size())
			throw new IllegalStateException("INCREMENTAL_FACTOR_COVER_MISSING");
	}
	private void verifyCoverNodes(List<Node> nodes) {
		for(int nodeIndex=0; nodeIndex<nodes.size(); nodeIndex++) {
			int[] nodeOwners = nodes.get(nodeIndex).owners;
			for(int ownerIndex=0; ownerIndex<nodeOwners.length; ownerIndex++) {
				int owner = nodeOwners[ownerIndex];
				if(owner < 0 || owner >= root.factors().size() || coverOwners.get(owner))
					throw new IllegalStateException("INCREMENTAL_FACTOR_DOUBLE_OWNER");
				coverOwners.set(owner);
			}
		}
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
			improvements,resourceRejected,internalDecisions,conditionalAttempts,conditionalImprovements);
		checkpoints.add(cp); observer.accept(cp);
	}
	private Result finish(String reason) {
		if(!reason.equals("RESOURCE_INITIAL") && !coverReleased) verifyCover();
		checkpoint(reason);
		List<Integer> encoded = root.expandAssignment(Arrays.stream(incumbent).boxed().toList());
		return new Result(List.copyOf(encoded.subList(0,problem.decisionCount())),lower,upper,reason,List.copyOf(checkpoints));
	}
	long conflictScoreEvaluationsForTest() { return conflictScoreEvaluations; }
	long conflictScoreCacheHitsForTest() { return conflictScoreCacheHits; }
	long incompleteConflictScoresForTest() { return incompleteConflictScores; }
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
