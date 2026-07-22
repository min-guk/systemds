/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Atomic emission boundary for an already selected canonical placement plan.
 *
 * <p>Planner integration owns construction of reversible registry mutations;
 * this class owns ordering, prevalidation, Hop snapshots, idempotency and exact
 * rollback. Runtime recovery is deliberately not part of this boundary.</p>
 */
public final class PlacementEmissionTransaction {
	private static final Map<DMLProgram,AppliedPlan> APPLIED_PLANS = new WeakHashMap<>();

	private PlacementEmissionTransaction() {
	}

	public enum FailurePoint {
		AFTER_FIRST_HOP_MUTATION,
		AFTER_FIRST_REGISTRY_MUTATION
	}

	public enum Outcome {
		APPLIED,
		ALREADY_APPLIED
	}

	/** A registry write whose complete pre-state can be restored exactly. */
	public interface RegistryMutation {
		/** Stable, transaction-local identity used to reject ambiguous duplicate writes. */
		String identity();

		/** Validate the complete write without changing any Hop or registry state. */
		void prevalidate();

		/** Capture every registry value that {@link #apply()} can change. */
		Object snapshot();

		void apply();

		void restore(Object snapshot);
	}

	@FunctionalInterface
	public interface FailureInjector {
		FailureInjector NONE = point -> { };

		void after(FailurePoint point);

		static FailureInjector failAt(FailurePoint expected) {
			Objects.requireNonNull(expected, "expected");
			return actual -> {
				if(actual == expected)
					throw new InjectedEmissionFailure(actual);
			};
		}
	}

	public record Request(DMLProgram program, NormalizedPlannerResult selectedPlan,
		List<RegistryMutation> registryMutations, FailureInjector failureInjector) {
		public Request {
			Objects.requireNonNull(program, "program");
			Objects.requireNonNull(selectedPlan, "selectedPlan");
			Objects.requireNonNull(registryMutations, "registryMutations");
			registryMutations = List.copyOf(registryMutations);
			failureInjector = failureInjector == null ? FailureInjector.NONE : failureInjector;
		}

		public Request(DMLProgram program, NormalizedPlannerResult selectedPlan,
			List<RegistryMutation> registryMutations) {
			this(program, selectedPlan, registryMutations, FailureInjector.NONE);
		}
	}

	public record Counters(long runtimeFallbacks, long runtimeRepairs) {
		private static final Counters ZERO = new Counters(0, 0);
	}

	public record Receipt(PlacementAnalysis analysis, String plannerId, String planHash,
		Outcome outcome, int hopMutationCount, int registryMutationCount, Counters counters) {
		public Receipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(plannerId, "plannerId");
			Objects.requireNonNull(planHash, "planHash");
			Objects.requireNonNull(outcome, "outcome");
			Objects.requireNonNull(counters, "counters");
		}
	}

	public static final class InjectedEmissionFailure extends DMLRuntimeException {
		private static final long serialVersionUID = 1L;
		private final FailurePoint failurePoint;

		private InjectedEmissionFailure(FailurePoint failurePoint) {
			super("Injected placement emission failure: " + failurePoint);
			this.failurePoint = failurePoint;
		}

		public FailurePoint failurePoint() {
			return failurePoint;
		}
	}

	private record HopMutation(Hop hop, PlacementState state, HopSnapshot snapshot) { }
	private record HopSnapshot(ExecType forcedExecType, FederatedOutput federatedOutput,
		boolean federatedOutputDerived) { }
	private record RegistrySnapshot(RegistryMutation mutation, Object snapshot) { }
	private record AppliedPlan(PlacementAnalysis analysis, String plannerId, String planHash,
		int hopMutationCount, int registryMutationCount) { }

	/** Prevalidate, apply once, or restore the exact pre-state on any failure. */
	public static Receipt emit(Request request) {
		Objects.requireNonNull(request, "request");
		synchronized(APPLIED_PLANS) {
			Prepared prepared = prepare(request);
			AppliedPlan prior = APPLIED_PLANS.get(request.program());
			if(prior != null) {
				if(prior.analysis() != prepared.analysis || !prior.planHash().equals(prepared.planHash))
					throw new IllegalStateException("A different or stale placement plan was already emitted");
				return receipt(prior, Outcome.ALREADY_APPLIED);
			}
			return apply(request, prepared);
		}
	}

	private static Prepared prepare(Request request) {
		NormalizedPlannerResult selected = request.selectedPlan();
		PlacementAnalysis analysis = Objects.requireNonNull(selected.analysis(), "selectedPlan.analysis");
		analysis.assertCanonicalProgramAuthority(request.program());
		if(!analysis.analysisFingerprint().equals(selected.analysisFingerprint()))
			throw new IllegalArgumentException("Selected plan has a stale or foreign analysis fingerprint");
		String plannerId = requireText(selected.plannerId(), "plannerId");
		String planHash = requireText(selected.normalizedPlanFingerprint(), "normalizedPlanFingerprint");

		Map<CompiledHopKey,HopOccurrenceProjection> exactOccurrences = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			exactOccurrences.put(occurrence.key(), occurrence);
		Map<CompiledHopKey,PlacementState> selectedStates = Objects.requireNonNull(selected.selectedStates(),
			"selectedPlan.selectedStates");
		if(selectedStates.size() != exactOccurrences.size())
			throw new IllegalArgumentException("Selected plan does not cover every canonical Hop occurrence");

		List<Map.Entry<CompiledHopKey,PlacementState>> ordered = new ArrayList<>(selectedStates.entrySet());
		ordered.sort(Map.Entry.comparingByKey());
		List<HopMutation> hopMutations = new ArrayList<>(ordered.size());
		Set<Hop> mutatedHops = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Map.Entry<CompiledHopKey,PlacementState> entry : ordered) {
			HopOccurrenceProjection occurrence = exactOccurrences.get(entry.getKey());
			if(occurrence == null)
				throw new IllegalArgumentException("Selected plan contains a copied or foreign Hop occurrence");
			Hop hop = occurrence.hop();
			if(!mutatedHops.add(hop))
				throw new IllegalArgumentException("Selected plan ambiguously assigns one Hop more than once");
			PlacementState state = Objects.requireNonNull(entry.getValue(), "selected placement state");
			hopMutations.add(new HopMutation(hop, state,
				new HopSnapshot(hop.getForcedExecType(), hop.getFederatedOutput(), hop.isFederatedOutputDerived())));
		}

		Set<String> registryIdentities = new java.util.HashSet<>();
		List<RegistrySnapshot> registrySnapshots = new ArrayList<>(request.registryMutations().size());
		for(RegistryMutation mutation : request.registryMutations()) {
			Objects.requireNonNull(mutation, "registryMutation");
			String identity = requireText(mutation.identity(), "registryMutation.identity");
			if(!registryIdentities.add(identity))
				throw new IllegalArgumentException("Duplicate registry mutation identity: " + identity);
			mutation.prevalidate();
			registrySnapshots.add(new RegistrySnapshot(mutation, mutation.snapshot()));
		}
		return new Prepared(analysis, plannerId, planHash, List.copyOf(hopMutations),
			List.copyOf(registrySnapshots));
	}

	private static Receipt apply(Request request, Prepared prepared) {
		int appliedHops = 0;
		int appliedRegistries = 0;
		try {
			for(HopMutation mutation : prepared.hopMutations) {
				appliedHops++;
				mutation.hop().setForcedExecType(mutation.state().execType());
				mutation.hop().setFederatedOutput(mutation.state().output());
				if(appliedHops == 1)
					request.failureInjector().after(FailurePoint.AFTER_FIRST_HOP_MUTATION);
			}
			for(RegistrySnapshot snapshot : prepared.registrySnapshots) {
				appliedRegistries++;
				snapshot.mutation().apply();
				if(appliedRegistries == 1)
					request.failureInjector().after(FailurePoint.AFTER_FIRST_REGISTRY_MUTATION);
			}
			AppliedPlan applied = new AppliedPlan(prepared.analysis, prepared.plannerId, prepared.planHash,
				prepared.hopMutations.size(), prepared.registrySnapshots.size());
			APPLIED_PLANS.put(request.program(), applied);
			return receipt(applied, Outcome.APPLIED);
		}
		catch(Throwable failure) {
			rollback(prepared, appliedHops, appliedRegistries, failure);
			throw failure;
		}
	}

	private static void rollback(Prepared prepared, int appliedHops, int appliedRegistries, Throwable failure) {
		for(int i = appliedRegistries - 1; i >= 0; i--)
			try {
				RegistrySnapshot snapshot = prepared.registrySnapshots.get(i);
				snapshot.mutation().restore(snapshot.snapshot());
			}
			catch(Throwable rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
		for(int i = appliedHops - 1; i >= 0; i--)
			try {
				HopMutation mutation = prepared.hopMutations.get(i);
				mutation.hop().setForcedExecType(mutation.snapshot().forcedExecType());
				mutation.hop().setFederatedOutput(mutation.snapshot().federatedOutput());
				mutation.hop().setFederatedOutputDerived(mutation.snapshot().federatedOutputDerived());
			}
			catch(Throwable rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
	}

	private static Receipt receipt(AppliedPlan applied, Outcome outcome) {
		return new Receipt(applied.analysis(), applied.plannerId(), applied.planHash(), outcome,
			applied.hopMutationCount(), applied.registryMutationCount(), Counters.ZERO);
	}

	private static String requireText(String value, String name) {
		if(value == null || value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	private record Prepared(PlacementAnalysis analysis, String plannerId, String planHash,
		List<HopMutation> hopMutations, List<RegistrySnapshot> registrySnapshots) { }
}
