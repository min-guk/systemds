/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections.PartialReachabilityIndex;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;

/**
 * Immutable policy-owned projection over the shared placement analysis.
 *
 * <p>This is the sole production caller of the materialization-maximal
 * candidate quotient. Exact/DP consumes the analysis-owned candidate universe
 * directly and never receives this package-private view.</p>
 */
final class PolicyCandidateSelectionView {
	private final PlacementAnalysis analysis;
	private final NeutralPlacementGraph authorityGraph;
	private final List<RelocationAction> actionUniverse;
	private final RelocationSelections.CanonicalOrderIndex relocationOrder;
	private final PartialReachabilityIndex reachability;
	private final String analysisFingerprint;
	private final String graphSignature;

	PolicyCandidateSelectionView(PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		RelocationSelections.CanonicalOrderIndex relocationOrder,
		PartialReachabilityIndex reachability) {
		this.analysis = Objects.requireNonNull(analysis, "analysis");
		this.authorityGraph = Objects.requireNonNull(authorityGraph, "authorityGraph");
		this.actionUniverse = List.copyOf(Objects.requireNonNull(actionUniverse, "actionUniverse"));
		this.relocationOrder = Objects.requireNonNull(relocationOrder, "relocationOrder");
		this.reachability = Objects.requireNonNull(reachability, "reachability");
		analysisFingerprint = analysis.analysisFingerprint();
		graphSignature = authorityGraph.normalizedSignature();
	}

	CandidateSelections.Selection select(Map<CompiledHopKey,PlacementState> assignment) {
		requireSourceUnchanged();
		CandidateSelections.Selection selected = CandidateSelections.selectPolicyFirstFeasible(
			analysis, authorityGraph, actionUniverse, Objects.requireNonNull(assignment, "assignment"),
			relocationOrder, reachability);
		requireSourceUnchanged();
		return selected;
	}

	private void requireSourceUnchanged() {
		if(!analysisFingerprint.equals(analysis.analysisFingerprint()))
			throw new IllegalStateException("Policy candidate view observed a mutated placement analysis");
		if(!graphSignature.equals(authorityGraph.normalizedSignature()))
			throw new IllegalStateException("Policy candidate view observed a mutated placement graph");
	}
}
