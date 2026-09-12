/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.placement;

/** Thread-local timing state for the decision-to-application planner pipeline. */
public final class PlannerPipelineTiming {
	private static final ThreadLocal<State> STATE = new ThreadLocal<>();

	private PlannerPipelineTiming() {
		// utility class
	}

	public record Timing(long planningNanos, long diagnosticsNanos, long conversionNanos,
		long applicationNanos, long finalizationNanos, long totalNanos) {
		public Timing {
			if(planningNanos < 0L || diagnosticsNanos < 0L || conversionNanos < 0L
				|| applicationNanos < 0L || finalizationNanos < 0L || totalNanos < 0L)
				throw new IllegalArgumentException("PLANNER_PIPELINE_TIMING_NEGATIVE");
			long partitioned;
			try {
				partitioned = Math.addExact(Math.addExact(Math.addExact(
					planningNanos, diagnosticsNanos), Math.addExact(conversionNanos, applicationNanos)),
					finalizationNanos);
			}
			catch(ArithmeticException ex) {
				throw new IllegalArgumentException("PLANNER_PIPELINE_TIMING_OVERFLOW", ex);
			}
			if(partitioned != totalNanos)
				throw new IllegalArgumentException("PLANNER_PIPELINE_TIMING_PARTITION_MISMATCH");
		}

		public String traceFields() {
			return "schema=decision-application-v1"
				+ " planningNanos=" + planningNanos
				+ " diagnosticsNanos=" + diagnosticsNanos
				+ " conversionNanos=" + conversionNanos
				+ " applicationNanos=" + applicationNanos
				+ " finalizationNanos=" + finalizationNanos
				+ " totalNanos=" + totalNanos;
		}
	}

	/** Starts a new invocation and replaces any stale or failed state on this thread. */
	public static void begin(long startNanos) {
		STATE.set(new State(startNanos));
	}

	public static void planningComplete() {
		planningComplete(System.nanoTime());
	}

	public static void diagnosticsComplete() {
		diagnosticsComplete(System.nanoTime());
	}

	public static void conversionComplete() {
		conversionComplete(System.nanoTime());
	}

	public static void applicationComplete() {
		applicationComplete(System.nanoTime());
	}

	static void planningComplete(long completedNanos) {
		mark(Phase.STARTED, Phase.PLANNING_COMPLETE, completedNanos, "planningComplete");
	}

	static void diagnosticsComplete(long completedNanos) {
		mark(Phase.PLANNING_COMPLETE, Phase.DIAGNOSTICS_COMPLETE, completedNanos,
			"diagnosticsComplete");
	}

	static void conversionComplete(long completedNanos) {
		mark(Phase.DIAGNOSTICS_COMPLETE, Phase.CONVERSION_COMPLETE, completedNanos,
			"conversionComplete");
	}

	static void applicationComplete(long completedNanos) {
		mark(Phase.CONVERSION_COMPLETE, Phase.APPLICATION_COMPLETE, completedNanos,
			"applicationComplete");
	}

	/** Returns {@code null} when the caller did not start a timed outer invocation. */
	public static Timing finish(long endNanos) {
		State state = STATE.get();
		if(state == null)
			return null;
		ensureUsable(state, "finish");
		if(state.phase != Phase.APPLICATION_COMPLETE)
			throw fail(state, "finish", Phase.APPLICATION_COMPLETE);
		long finalizationNanos = elapsed(state.lastNanos, endNanos, state, "finish");
		long totalNanos = elapsed(state.startNanos, endNanos, state, "finish");
		Timing timing = new Timing(state.planningNanos, state.diagnosticsNanos,
			state.conversionNanos, state.applicationNanos, finalizationNanos, totalNanos);
		state.phase = Phase.FINISHED;
		state.lastNanos = endNanos;
		return timing;
	}

	/** Removes all timing state for the current thread. */
	public static void clear() {
		STATE.remove();
	}

	private static void mark(Phase expected, Phase next, long completedNanos, String operation) {
		State state = STATE.get();
		if(state == null)
			return;
		ensureUsable(state, operation);
		if(state.phase != expected)
			throw fail(state, operation, expected);
		long duration = elapsed(state.lastNanos, completedNanos, state, operation);
		switch(next) {
			case PLANNING_COMPLETE -> state.planningNanos = duration;
			case DIAGNOSTICS_COMPLETE -> state.diagnosticsNanos = duration;
			case CONVERSION_COMPLETE -> state.conversionNanos = duration;
			case APPLICATION_COMPLETE -> state.applicationNanos = duration;
			default -> throw new IllegalStateException("PLANNER_PIPELINE_TIMING_INTERNAL_PHASE");
		}
		state.phase = next;
		state.lastNanos = completedNanos;
	}

	private static void ensureUsable(State state, String operation) {
		if(state.failed)
			throw new IllegalStateException("PLANNER_PIPELINE_TIMING_FAILED|operation=" + operation);
	}

	private static IllegalStateException fail(State state, String operation, Phase expected) {
		state.failed = true;
		return new IllegalStateException("PLANNER_PIPELINE_TIMING_ORDER|operation=" + operation
			+ " expected=" + expected + " actual=" + state.phase);
	}

	private static long elapsed(long startNanos, long endNanos, State state, String operation) {
		try {
			long elapsed = Math.subtractExact(endNanos, startNanos);
			if(elapsed < 0L)
				throw new ArithmeticException("negative duration");
			return elapsed;
		}
		catch(ArithmeticException ex) {
			state.failed = true;
			throw new IllegalStateException("PLANNER_PIPELINE_TIMING_TIMESTAMP|operation="
				+ operation, ex);
		}
	}

	private enum Phase {
		STARTED,
		PLANNING_COMPLETE,
		DIAGNOSTICS_COMPLETE,
		CONVERSION_COMPLETE,
		APPLICATION_COMPLETE,
		FINISHED
	}

	private static final class State {
		private final long startNanos;
		private long lastNanos;
		private long planningNanos;
		private long diagnosticsNanos;
		private long conversionNanos;
		private long applicationNanos;
		private Phase phase = Phase.STARTED;
		private boolean failed;

		private State(long startNanos) {
			this.startNanos = startNanos;
			lastNanos = startNanos;
		}
	}
}
