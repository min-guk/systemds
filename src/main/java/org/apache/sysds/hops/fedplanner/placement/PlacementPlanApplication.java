/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlacementPlannerAdapter;
import org.apache.sysds.parser.DMLProgram;

/** One post-selection conversion and application boundary for all four compiled planners. */
public final class PlacementPlanApplication {
	private PlacementPlanApplication() { }

	@FunctionalInterface
	public interface ReceiptFactory<P, R extends AFederatedPlanner.PlannerInvocationReceipt> {
		R create(P projected, NormalizedPlannerResult normalized, PlacementEmissionReceipt emission);
	}

	/**
	 * The caller has completed its own model, search and validated selection. Projection
	 * decodes that selection without choosing a different plan. All planners then use
	 * the same immutable normalization, atomic emission and receipt boundary.
	 */
	public static <P, R extends AFederatedPlanner.PlannerInvocationReceipt> R complete(
		DMLProgram program, PlacementAnalysis analysis, Runnable diagnostics, Supplier<P> projection,
		Function<P, NormalizedPlannerResult> selectedResult, ReceiptFactory<P, R> receipts) {
		PlannerPipelineTiming.planningComplete();
		long phaseStarted = System.nanoTime();
		long outputStarted = FederatedPlannerTrace.traceOutputNanos();
		diagnostics.run();
		FederatedPlannerTrace.logPhaseTiming("POST_SEARCH_TRACE", phaseStarted, outputStarted);
		PlannerPipelineTiming.diagnosticsComplete();

		phaseStarted = System.nanoTime();
		outputStarted = FederatedPlannerTrace.traceOutputNanos();
		P projected = Objects.requireNonNull(projection.get(), "projected selected plan");
		NormalizedPlannerResult normalized = PlacementPlannerAdapter.normalize(analysis,
			Objects.requireNonNull(selectedResult.apply(projected), "selected normalized plan"));
		FederatedPlannerTrace.logPhaseTiming("PLAN_CONVERSION", phaseStarted, outputStarted);
		PlannerPipelineTiming.conversionComplete();

		phaseStarted = System.nanoTime();
		outputStarted = FederatedPlannerTrace.traceOutputNanos();
		PlacementEmissionReceipt emission = PlacementEmissionTransaction.emit(program, normalized,
			PlacementEmissionTransaction.FailureInjector.none());
		R receipt = Objects.requireNonNull(receipts.create(projected, normalized, emission), "planner receipt");
		if(receipt.analysis() != analysis)
			throw new IllegalStateException("Planner application receipt changed common emission authority");
		FederatedPlannerTrace.logPhaseTiming("EMISSION", phaseStarted, outputStarted);
		PlannerPipelineTiming.applicationComplete();
		return receipt;
	}
}
