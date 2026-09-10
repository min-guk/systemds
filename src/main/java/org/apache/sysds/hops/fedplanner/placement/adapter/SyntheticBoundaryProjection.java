/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Synthetic-boundary projection retained for the active cost planners. */
public final class SyntheticBoundaryProjection {
	public record SyntheticBoundaryReceipt(PlacementAnalysis analysis,
		NeutralPlacementGraph.Node boundary, List<NeutralPlacementGraph.Constraint> authorities,
		Map<CompiledHopKey,PlacementEmissionState> sourceEmissionStates,
		PlacementEmissionState selectedEmissionState) {
		public SyntheticBoundaryReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(boundary, "boundary");
			authorities = List.copyOf(Objects.requireNonNull(authorities, "authorities"));
			sourceEmissionStates = Collections.unmodifiableMap(new IdentityHashMap<>(
				Objects.requireNonNull(sourceEmissionStates, "sourceEmissionStates")));
			Objects.requireNonNull(selectedEmissionState, "selectedEmissionState");
			if(boundary.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
				&& boundary.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT)
				throw new IllegalArgumentException("DP synthetic receipt requires a function boundary");
			if(analysis.graph().node(boundary.key()).orElse(null) != boundary || authorities.isEmpty())
				throw new IllegalArgumentException("DP synthetic receipt contains a foreign graph node");
			if(boundary.legalAlternatives().stream()
				.noneMatch(state -> state == selectedEmissionState.placementState()))
				throw new IllegalArgumentException(
					"DP synthetic boundary did not retain its exact normalized state identity: kind="
						+ boundary.kind() + ", boundary=" + boundary.key().normalizedSignature()
						+ ", sources=" + sourceEmissionStates + ", selected="
						+ selectedEmissionState.placementState().normalizedSignature() + ", alternatives="
						+ boundary.legalAlternatives().stream().map(PlacementState::normalizedSignature).toList());
			PlacementState boundaryState = selectedEmissionState.placementState();
			boolean legalTransientTuple = boundaryState.execType() == ExecType.CP
				&& boundaryState.output() == FederatedOutput.LOUT
				&& boundaryState.fType() == null && !boundaryState.shapeDependent()
				|| boundaryState.execType() == ExecType.FED
					&& boundaryState.output() == FederatedOutput.FOUT
					&& boundaryState.fType() != null;
			Boolean expectedDerivedFedFout = null;
			for(NeutralPlacementGraph.Constraint authority : authorities) {
				boolean expectedKind = boundary.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
					? authority.kind() == NeutralPlacementGraph.ConstraintKind.CONJUNCTIVE
					: authority.kind() == NeutralPlacementGraph.ConstraintKind.SAME_VALUE_PLACEMENT;
				PlacementEmissionState sourceEmissionState = sourceEmissionStates.get(authority.left());
				NeutralPlacementGraph.Node source = analysis.graph().node(authority.left()).orElse(null);
				if(!expectedKind || authority.right() != boundary.key() || source == null
					|| analysis.graph().constraints().stream().noneMatch(candidate -> candidate == authority)
					|| sourceEmissionState == null || source.legalAlternatives().stream()
						.noneMatch(state -> state == sourceEmissionState.placementState())
					|| !NeutralPlacementGraph.constraintSatisfied(authority,
						sourceEmissionState.placementState(), boundaryState))
					throw new IllegalArgumentException("DP synthetic receipt contains foreign or incompatible authority");
				boolean derived = boundaryState.output() == FederatedOutput.FOUT
					&& sourceEmissionState.derivedFedFout();
				if(expectedDerivedFedFout != null && expectedDerivedFedFout != derived)
					throw new IllegalArgumentException("DP synthetic output authorities disagree on derived FOUT");
				expectedDerivedFedFout = derived;
			}
			if(!legalTransientTuple || selectedEmissionState.derivedFedFout()
				!= Boolean.TRUE.equals(expectedDerivedFedFout))
				throw new IllegalArgumentException("DP synthetic boundary projection semantics differ: sources="
					+ sourceEmissionStates + ", boundary=" + selectedEmissionState.normalizedSignature());
		}
	}

	/**
	 * Projects the sole incoming compiler-owned conjunctive authority for a synthetic boundary.
	 * Returns {@code null} only while that exact source occurrence has not yet been selected.
	 */
	public static SyntheticBoundaryReceipt projectSyntheticBoundary(PlacementAnalysis analysis,
		NeutralPlacementGraph.Node boundary,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates) {
		PlacementState selected = selectedFunctionInputState(
			analysis, boundary, selectedEmissionStates);
		return projectSyntheticBoundary(analysis, boundary, selectedEmissionStates, selected);
	}

	/** Projects and validates an already selected exact synthetic-boundary placement. */
	public static SyntheticBoundaryReceipt projectSyntheticBoundary(PlacementAnalysis analysis,
		NeutralPlacementGraph.Node boundary,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		PlacementState selectedBoundaryState) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(boundary, "boundary");
		Objects.requireNonNull(selectedEmissionStates, "selectedEmissionStates");
		if(boundary.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
			&& boundary.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT)
			throw new IllegalArgumentException("DP synthetic projection requires a function boundary");
		List<NeutralPlacementGraph.Constraint> authorities = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.right() == boundary.key())
			.filter(constraint -> boundary.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
				? constraint.kind() == NeutralPlacementGraph.ConstraintKind.CONJUNCTIVE
					&& (constraint.evidence().startsWith("function-argument:")
						|| constraint.evidence().startsWith("inlined-function-argument:"))
				: constraint.kind() == NeutralPlacementGraph.ConstraintKind.SAME_VALUE_PLACEMENT
					&& (constraint.evidence().startsWith("function-result:")
						|| constraint.evidence().startsWith("inlined-function-result:")))
			.sorted().toList();
		if(authorities.isEmpty() || boundary.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
			&& authorities.size() != 1)
			throw new IllegalStateException("DP synthetic boundary has invalid exact authority cardinality: kind="
				+ boundary.kind() + ", key=" + boundary.key() + ", authorities=" + authorities.size());
		Map<CompiledHopKey,PlacementEmissionState> sources = new IdentityHashMap<>();
		PlacementEmissionState selected = null;
		for(NeutralPlacementGraph.Constraint authority : authorities) {
			PlacementEmissionState source = selectedEmissionStates.get(authority.left());
			if(source == null)
				return null;
			sources.put(authority.left(), source);
			PlacementEmissionState projected = selectedBoundaryState == null
				? normalizeSyntheticBoundaryEmission(boundary, source)
				: normalizeSyntheticBoundaryEmission(boundary, source, selectedBoundaryState);
			if(selected == null)
				selected = projected;
			else if(!selected.equals(projected))
				throw new IllegalArgumentException("DP synthetic output authorities select different value placements: "
					+ boundary.key().normalizedSignature());
		}
		return new SyntheticBoundaryReceipt(analysis, boundary, authorities, sources, selected);
	}

	private static PlacementState selectedFunctionInputState(PlacementAnalysis analysis,
		NeutralPlacementGraph.Node boundary,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates) {
		if(boundary.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_INPUT)
			return null;
		List<PlacementState> selected = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == NeutralPlacementGraph.ConstraintKind.SAME_PLACEMENT
				&& constraint.left() == boundary.key()
				&& "function-formal-input".equals(constraint.evidence()))
			.map(constraint -> selectedEmissionStates.get(constraint.right()))
			.filter(Objects::nonNull).map(PlacementEmissionState::placementState).distinct().toList();
		if(selected.size() > 1)
			throw new IllegalArgumentException("DP synthetic function input has conflicting selected formals: "
				+ boundary.key().normalizedSignature() + " states="
				+ selected.stream().map(PlacementState::normalizedSignature).toList());
		return selected.isEmpty() ? null : selected.get(0);
	}

	/**
	 * Function boundaries carry value placement, not the producer's execution location. A local
	 * producer value therefore enters the transient boundary as CP/LOUT even when the producer ran
	 * in FED mode; a federated value analogously enters as FED/FOUT. The returned state is always the
	 * exact boundary-owned object, preserving fail-closed analysis identity without admitting the
	 * forbidden transient tuples FED/LOUT or CP/FOUT.
	 */
	private static PlacementEmissionState normalizeSyntheticBoundaryEmission(
		NeutralPlacementGraph.Node boundary, PlacementEmissionState sourceEmission) {
		PlacementState source = sourceEmission.placementState();
		if(source.output() != FederatedOutput.LOUT && source.output() != FederatedOutput.FOUT)
			throw new IllegalArgumentException("DP synthetic boundary source has no value placement: "
				+ source.normalizedSignature());
		if(source.output() == FederatedOutput.FOUT && source.fType() == null)
			throw new IllegalArgumentException("DP synthetic boundary FOUT source has no exact FType: "
				+ source.normalizedSignature());
		ExecType boundaryExec = source.output() == FederatedOutput.LOUT ? ExecType.CP : ExecType.FED;
		FType boundaryFType = source.output() == FederatedOutput.FOUT ? source.fType() : null;
		boolean boundaryShapeDependent = source.output() == FederatedOutput.FOUT && source.shapeDependent();
		List<PlacementState> matches = boundary.legalAlternatives().stream()
			.filter(state -> state.execType() == boundaryExec)
			.filter(state -> state.output() == source.output())
			.filter(state -> state.fType() == boundaryFType)
			.filter(state -> boundary.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT
				|| state.shapeDependent() == boundaryShapeDependent)
			.toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("DP synthetic boundary has no unique normalized source state: kind="
				+ boundary.kind() + ", boundary=" + boundary.key().normalizedSignature()
				+ ", source=" + sourceEmission.normalizedSignature() + ", alternatives="
				+ boundary.legalAlternatives().stream().map(PlacementState::normalizedSignature).toList());
		return new PlacementEmissionState(matches.get(0),
			source.output() == FederatedOutput.FOUT && sourceEmission.derivedFedFout());
	}

	private static PlacementEmissionState normalizeSyntheticBoundaryEmission(
		NeutralPlacementGraph.Node boundary, PlacementEmissionState sourceEmission,
		PlacementState selectedBoundaryState) {
		Objects.requireNonNull(selectedBoundaryState, "selectedBoundaryState");
		List<PlacementState> matches = boundary.legalAlternatives().stream()
			.filter(state -> state == selectedBoundaryState || state.equals(selectedBoundaryState)).toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("DP synthetic boundary selected tuple has no unique exact owner: kind="
				+ boundary.kind() + ", boundary=" + boundary.key().normalizedSignature()
				+ ", selected=" + selectedBoundaryState.normalizedSignature());
		boolean derived = selectedBoundaryState.output() == FederatedOutput.FOUT
			&& sourceEmission.derivedFedFout();
		return new PlacementEmissionState(matches.get(0), derived);
	}

}
