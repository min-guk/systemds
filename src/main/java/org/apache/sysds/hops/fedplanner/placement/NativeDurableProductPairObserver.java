/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.SearchSpaceMetrics.DirectWork;

/**
 * Metrics-only first-exemplar pair incidences, not union eligibility or savings.
 * Limits bound retained references and axis comparisons, not deep key/anchor
 * equality CPU or bytes. No proof members or growable handle maps are retained.
 */
final class NativeDurableProductPairObserver {
	private final SearchSpaceMetrics metrics;
	private final int maxKeys, maxObservations, maxOptions, maxAxisComparisons;
	private final Map<PlacementRealizationKey,Exemplar> exemplars = new HashMap<>();
	private int admittedKeys, observations, retainedOptions, axisComparisons;

	NativeDurableProductPairObserver(SearchSpaceMetrics metrics) {
		this(metrics, 64, 256, 8192, 65_536);
	}

	NativeDurableProductPairObserver(SearchSpaceMetrics metrics, int maxKeys,
		int maxObservations, int maxOptions, int maxAxisComparisons) {
		this.metrics = Objects.requireNonNull(metrics);
		if(maxKeys < 0 || maxObservations < 0 || maxOptions < 0 || maxAxisComparisons < 0)
			throw new IllegalArgumentException("Negative native pair diagnostic limit");
		this.maxKeys = maxKeys;
		this.maxObservations = maxObservations;
		this.maxOptions = maxOptions;
		this.maxAxisComparisons = maxAxisComparisons;
	}

	void beginEmission() {
		// The first exemplar is local to this exact fact/emission occurrence.
		// Cumulative binder-pass budgets deliberately do not reset here.
		exemplars.clear();
	}

	void observe(CompiledHopKey owner, CandidateEmissionRealization publication) {
		if(publication.key().layoutKind() != PlacementLayoutKind.DURABLE_MAP
			|| !(publication.supportClauses() instanceof NativeContinuitySupportClauses relation))
			return;
		var product = relation.product();
		metrics.recordDirectWork(DirectWork.NATIVE_PAIR_OBSERVED);
		metrics.recordDirectWork(DirectWork.NATIVE_PAIR_LOGICAL_MEMBERS, product.size());
		DirectWork category = DirectWork.NATIVE_PAIR_BUDGET_UNKNOWN;
		if(observations < maxObservations) {
			observations++;
			Exemplar prior = exemplars.get(publication.key());
			if(prior == null) {
				if(admittedKeys < maxKeys) {
					int options = 0;
					boolean fits = true;
					for(var axis : product.axes()) {
						if(axis.size() > maxOptions - retainedOptions - options) {
							fits = false;
							break;
						}
						options += axis.size();
					}
					if(fits) {
						exemplars.put(publication.key(), new Exemplar(owner, product.externalSeed(),
							product.outputWorkerPoolWitness(), product.exactPartitionRanges(),
							relation.clauseWitness(), relation.clauseLayoutExact(), product.axes()));
						admittedKeys++;
						retainedOptions += options;
						category = DirectWork.NATIVE_PAIR_FIRST;
					}
				}
			}
			else if(prior.owner() != owner || prior.exactRanges() != product.exactPartitionRanges()
				|| prior.clauseExact() != relation.clauseLayoutExact()
				|| !Objects.equals(prior.outputWitness(), product.outputWorkerPoolWitness())
				|| !Objects.equals(prior.clauseWitness(), relation.clauseWitness()))
				category = DirectWork.NATIVE_PAIR_METADATA_CHANGED;
			else {
				category = compareAxes(prior.axes(), product.axes());
				if(category == null)
					category = prior.seed().equals(product.externalSeed())
						? DirectWork.NATIVE_PAIR_SAME_SEED : DirectWork.NATIVE_PAIR_DISTINCT_SEED;
			}
		}
		metrics.recordDirectWork(category);
		if(category == DirectWork.NATIVE_PAIR_DISTINCT_SEED)
			metrics.recordDirectWork(DirectWork.NATIVE_PAIR_DISTINCT_SEED_LOGICAL_MEMBERS, product.size());
	}

	// Null means complete axis-identity equality; exhaustion never means equality.
	private DirectWork compareAxes(List<List<CandidateRealizationInputBinding>> left,
		List<List<CandidateRealizationInputBinding>> right) {
		if(!axisStep())
			return DirectWork.NATIVE_PAIR_BUDGET_UNKNOWN;
		if(left.size() != right.size())
			return DirectWork.NATIVE_PAIR_AXES_CHANGED;
		for(int axis = 0; axis < left.size(); axis++) {
			if(!axisStep())
				return DirectWork.NATIVE_PAIR_BUDGET_UNKNOWN;
			if(left.get(axis).size() != right.get(axis).size())
				return DirectWork.NATIVE_PAIR_AXES_CHANGED;
			for(int option = 0; option < left.get(axis).size(); option++) {
				if(!axisStep())
					return DirectWork.NATIVE_PAIR_BUDGET_UNKNOWN;
				if(left.get(axis).get(option) != right.get(axis).get(option))
					return DirectWork.NATIVE_PAIR_AXES_CHANGED;
			}
		}
		return null;
	}

	private boolean axisStep() {
		if(axisComparisons >= maxAxisComparisons)
			return false;
		axisComparisons++;
		return true;
	}

	private record Exemplar(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey outputWitness, boolean exactRanges, DurableAnchorKey clauseWitness,
		boolean clauseExact, List<List<CandidateRealizationInputBinding>> axes) { }
}
