/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayDeque;

/**
 * Thread-local timing for the fixed production candidate-formation boundary.
 *
 * <p>The boundary starts before final physical normalization and ends only after
 * final verification, registration, and the planner-receipt handoff. All stored
 * phases are exclusive and their sum is exactly {@link Timing#totalNanos()}.
 * Same-thread nested compilation uses identity-scoped LIFO stack entries.</p>
 */
public final class CandidateFormationTiming {
	private static final ThreadLocal<ArrayDeque<State>> STATE = new ThreadLocal<>();

	private CandidateFormationTiming() { }

	public record Timing(boolean exactPhaseAttribution, long commonPreparationNanos, long analysisNanos,
		long plannerSetupNanos, long modelNanos, long costSurfaceNanos,
		long optimizerNanos, long selectionNanos, long otherPlanningNanos,
		long diagnosticsNanos, long conversionNanos, long applicationNanos,
		long finalVerificationNanos, long registrationNanos, long receiptHandoffNanos,
		long totalNanos) {
		public Timing {
			long[] phases = {commonPreparationNanos, analysisNanos, plannerSetupNanos,
				modelNanos, costSurfaceNanos, optimizerNanos, selectionNanos,
				otherPlanningNanos, diagnosticsNanos, conversionNanos, applicationNanos,
				finalVerificationNanos, registrationNanos, receiptHandoffNanos};
			long sum = 0L;
			try {
				for(long phase : phases) {
					if(phase < 0L)
						throw new IllegalArgumentException("CANDIDATE_E2E_TIMING_NEGATIVE");
					sum = Math.addExact(sum, phase);
				}
			}
			catch(ArithmeticException ex) {
				throw new IllegalArgumentException("CANDIDATE_E2E_TIMING_OVERFLOW", ex);
			}
			if(totalNanos < 0L || sum != totalNanos)
				throw new IllegalArgumentException("CANDIDATE_E2E_TIMING_PARTITION_MISMATCH");
		}

		public String traceFields() {
			return "schema=candidate-e2e-v1"
				+ " exactPhaseAttribution=" + exactPhaseAttribution
				+ " commonPreparationNanos=" + commonPreparationNanos
				+ " analysisNanos=" + analysisNanos
				+ " plannerSetupNanos=" + plannerSetupNanos
				+ " modelNanos=" + modelNanos
				+ " costSurfaceNanos=" + costSurfaceNanos
				+ " optimizerNanos=" + optimizerNanos
				+ " selectionNanos=" + selectionNanos
				+ " otherPlanningNanos=" + otherPlanningNanos
				+ " diagnosticsNanos=" + diagnosticsNanos
				+ " conversionNanos=" + conversionNanos
				+ " applicationNanos=" + applicationNanos
				+ " finalVerificationNanos=" + finalVerificationNanos
				+ " registrationNanos=" + registrationNanos
				+ " receiptHandoffNanos=" + receiptHandoffNanos
				+ " totalNanos=" + totalNanos;
		}
	}

	/** Identity token used to close exactly the scope opened by one translator invocation. */
	public static final class Scope {
		private final State state;

		private Scope(State state) {
			this.state = state;
		}
	}

	public static Scope begin(long startNanos) {
		ArrayDeque<State> stack = STATE.get();
		if(stack == null) {
			stack = new ArrayDeque<>();
			STATE.set(stack);
		}
		State state = new State(startNanos);
		stack.push(state);
		return new Scope(state);
	}
	public static void commonPreparationComplete() {
		if(current() != null)
			commonPreparationComplete(System.nanoTime());
	}
	public static void analysisComplete() {
		if(current() != null)
			analysisComplete(System.nanoTime());
	}
	public static void plannerSetupComplete() {
		if(current() != null)
			plannerSetupComplete(System.nanoTime());
	}
	public static void modelComplete() {
		if(current() != null)
			modelComplete(System.nanoTime());
	}
	public static void costSurfaceComplete() {
		if(current() != null)
			costSurfaceComplete(System.nanoTime());
	}
	public static void optimizerComplete() {
		if(current() != null)
			optimizerComplete(System.nanoTime());
	}
	public static void selectionComplete() {
		if(current() != null)
			selectionComplete(System.nanoTime());
	}
	public static void planningComplete() {
		if(current() != null)
			planningComplete(System.nanoTime());
	}
	public static void diagnosticsComplete() {
		if(current() != null)
			diagnosticsComplete(System.nanoTime());
	}
	public static void conversionComplete() {
		if(current() != null)
			conversionComplete(System.nanoTime());
	}
	public static void applicationComplete() {
		if(current() != null)
			applicationComplete(System.nanoTime());
	}
	public static void finalVerificationComplete() {
		if(current() != null)
			finalVerificationComplete(System.nanoTime());
	}
	public static void registrationComplete() {
		if(current() != null)
			registrationComplete(System.nanoTime());
	}

	static void commonPreparationComplete(long nanos) { mark(Phase.STARTED, Phase.COMMON_COMPLETE, nanos); }
	static void analysisComplete(long nanos) { mark(Phase.COMMON_COMPLETE, Phase.ANALYSIS_COMPLETE, nanos); }
	static void plannerSetupComplete(long nanos) { mark(Phase.ANALYSIS_COMPLETE, Phase.SETUP_COMPLETE, nanos); }
	static void modelComplete(long nanos) {
		State state = current();
		if(state != null)
			state.exactPhaseAttribution = true;
		mark(Phase.SETUP_COMPLETE, Phase.MODEL_COMPLETE, nanos);
	}
	static void costSurfaceComplete(long nanos) { mark(Phase.MODEL_COMPLETE, Phase.COST_COMPLETE, nanos); }
	static void optimizerComplete(long nanos) { mark(Phase.COST_COMPLETE, Phase.OPTIMIZER_COMPLETE, nanos); }
	static void selectionComplete(long nanos) { mark(Phase.OPTIMIZER_COMPLETE, Phase.SELECTION_COMPLETE, nanos); }

	static void planningComplete(long nanos) {
		State state = current();
		if(state == null)
			return;
		if(state.phase == Phase.SETUP_COMPLETE)
			mark(Phase.SETUP_COMPLETE, Phase.PLANNING_COMPLETE, nanos);
		else
			mark(Phase.SELECTION_COMPLETE, Phase.PLANNING_COMPLETE, nanos);
	}

	static void diagnosticsComplete(long nanos) { mark(Phase.PLANNING_COMPLETE, Phase.DIAGNOSTICS_COMPLETE, nanos); }
	static void conversionComplete(long nanos) { mark(Phase.DIAGNOSTICS_COMPLETE, Phase.CONVERSION_COMPLETE, nanos); }
	static void applicationComplete(long nanos) { mark(Phase.CONVERSION_COMPLETE, Phase.APPLICATION_COMPLETE, nanos); }
	static void finalVerificationComplete(long nanos) { mark(Phase.APPLICATION_COMPLETE, Phase.VERIFICATION_COMPLETE, nanos); }
	static void registrationComplete(long nanos) { mark(Phase.VERIFICATION_COMPLETE, Phase.REGISTRATION_COMPLETE, nanos); }

	public static Timing finish(long endNanos) {
		State state = current();
		if(state == null)
			return null;
		return finishState(state, endNanos);
	}

	public static Timing finish(Scope scope, long endNanos) {
		return finishState(requireTop(scope, "finish"), endNanos);
	}

	private static Timing finishState(State state, long endNanos) {
		ensure(state, Phase.REGISTRATION_COMPLETE);
		state.receiptHandoffNanos = elapsed(state.lastNanos, endNanos, state);
		long total = elapsed(state.startNanos, endNanos, state);
		Timing timing = new Timing(state.exactPhaseAttribution, state.commonPreparationNanos, state.analysisNanos,
			state.plannerSetupNanos, state.modelNanos, state.costSurfaceNanos,
			state.optimizerNanos, state.selectionNanos, state.otherPlanningNanos,
			state.diagnosticsNanos, state.conversionNanos, state.applicationNanos,
			state.finalVerificationNanos, state.registrationNanos,
			state.receiptHandoffNanos, total);
		state.phase = Phase.FINISHED;
		state.lastNanos = endNanos;
		return timing;
	}

	/** Test/reset utility that clears every timing scope on the current thread. */
	public static void clear() { STATE.remove(); }

	/** Pops one invocation scope and fails closed if scopes are closed out of LIFO order. */
	public static void clear(Scope scope) {
		State state = requireTop(scope, "clear");
		ArrayDeque<State> stack = STATE.get();
		if(stack.pop() != state)
			throw new IllegalStateException("CANDIDATE_E2E_TIMING_SCOPE_IDENTITY");
		if(stack.isEmpty())
			STATE.remove();
	}

	private static void mark(Phase expected, Phase next, long nanos) {
		State state = current();
		if(state == null)
			return;
		ensure(state, expected);
		long duration = elapsed(state.lastNanos, nanos, state);
		switch(next) {
			case COMMON_COMPLETE -> state.commonPreparationNanos = duration;
			case ANALYSIS_COMPLETE -> state.analysisNanos = duration;
			case SETUP_COMPLETE -> state.plannerSetupNanos = duration;
			case MODEL_COMPLETE -> state.modelNanos = duration;
			case COST_COMPLETE -> state.costSurfaceNanos = duration;
			case OPTIMIZER_COMPLETE -> state.optimizerNanos = duration;
			case SELECTION_COMPLETE -> state.selectionNanos = duration;
			case PLANNING_COMPLETE -> state.otherPlanningNanos = duration;
			case DIAGNOSTICS_COMPLETE -> state.diagnosticsNanos = duration;
			case CONVERSION_COMPLETE -> state.conversionNanos = duration;
			case APPLICATION_COMPLETE -> state.applicationNanos = duration;
			case VERIFICATION_COMPLETE -> state.finalVerificationNanos = duration;
			case REGISTRATION_COMPLETE -> state.registrationNanos = duration;
			default -> throw new IllegalStateException("CANDIDATE_E2E_TIMING_INTERNAL_PHASE");
		}
		state.phase = next;
		state.lastNanos = nanos;
	}

	private static State current() {
		ArrayDeque<State> stack = STATE.get();
		return stack == null ? null : stack.peek();
	}

	private static State requireTop(Scope scope, String operation) {
		if(scope == null)
			throw new IllegalStateException("CANDIDATE_E2E_TIMING_SCOPE_MISSING|operation=" + operation);
		State top = current();
		if(top != scope.state)
			throw new IllegalStateException("CANDIDATE_E2E_TIMING_SCOPE_ORDER|operation=" + operation);
		return top;
	}

	private static void ensure(State state, Phase expected) {
		if(state.failed || state.phase != expected) {
			state.failed = true;
			throw new IllegalStateException("CANDIDATE_E2E_TIMING_ORDER|expected="
				+ expected + " actual=" + state.phase);
		}
	}

	private static long elapsed(long start, long end, State state) {
		try {
			long value = Math.subtractExact(end, start);
			if(value < 0L)
				throw new ArithmeticException("negative duration");
			return value;
		}
		catch(ArithmeticException ex) {
			state.failed = true;
			throw new IllegalStateException("CANDIDATE_E2E_TIMING_TIMESTAMP", ex);
		}
	}

	private enum Phase {
		STARTED, COMMON_COMPLETE, ANALYSIS_COMPLETE, SETUP_COMPLETE, MODEL_COMPLETE,
		COST_COMPLETE, OPTIMIZER_COMPLETE, SELECTION_COMPLETE, PLANNING_COMPLETE,
		DIAGNOSTICS_COMPLETE, CONVERSION_COMPLETE, APPLICATION_COMPLETE,
		VERIFICATION_COMPLETE, REGISTRATION_COMPLETE, FINISHED
	}

	private static final class State {
		private final long startNanos;
		private long lastNanos;
		private long commonPreparationNanos;
		private long analysisNanos;
		private long plannerSetupNanos;
		private long modelNanos;
		private long costSurfaceNanos;
		private long optimizerNanos;
		private long selectionNanos;
		private long otherPlanningNanos;
		private long diagnosticsNanos;
		private long conversionNanos;
		private long applicationNanos;
		private long finalVerificationNanos;
		private long registrationNanos;
		private long receiptHandoffNanos;
		private boolean exactPhaseAttribution;
		private Phase phase = Phase.STARTED;
		private boolean failed;

		private State(long startNanos) {
			this.startNanos = startNanos;
			lastNanos = startNanos;
		}
	}
}
