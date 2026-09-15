/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;

/**
 * Shared proof helper for exact candidate-domain refinement.
 *
 * <p>The physical closure must validate removal against the same exact input
 * domains that were used to rebuild the consumer. Re-deriving those domains
 * from a later node projection can disagree with the rebuild authority and can
 * turn a legitimate predecessor-domain shrink into a false non-monotone
 * failure. This helper is deliberately policy-free: it neither creates nor
 * removes candidates; it only proves that an already-removed input tuple is no
 * longer admitted at an input position whose predecessor was refined.</p>
 */
final class CandidateDomainRefinement {
	private CandidateDomainRefinement() {
		// utility class
	}

	static boolean removedByRefinedInputDomain(List<CandidateInputState> priorInputs,
		List<List<FType>> exactInputDomains, Set<Integer> refinedInputPositions) {
		if(priorInputs == null || exactInputDomains == null || refinedInputPositions == null
			|| priorInputs.size() != exactInputDomains.size())
			return false;
		for(int inputPosition : refinedInputPositions) {
			if(inputPosition < 0 || inputPosition >= priorInputs.size())
				continue;
			CandidateInputState prior = priorInputs.get(inputPosition);
			boolean stillAdmitted = exactInputDomains.get(inputPosition).stream()
				.map(type -> type == null ? CandidateInputState.absentLocal()
					: CandidateInputState.present(type))
				.anyMatch(prior::equals);
			if(!stillAdmitted)
				return true;
		}
		return false;
	}

	/**
	 * Finds positions whose exact rebuild domain is a strict subset of the
	 * candidate input projection that existed before this rebuild. This local
	 * proof remains valid when an outer changed-ordinal set names an older
	 * generation. A swap or expansion is deliberately not a refinement.
	 */
	static Set<Integer> refinedInputPositions(List<List<CandidateInputState>> priorRows,
		List<List<FType>> exactInputDomains) {
		if(priorRows == null || priorRows.isEmpty() || exactInputDomains == null)
			return Set.of();
		int width = exactInputDomains.size();
		if(priorRows.stream().anyMatch(row -> row == null || row.size() != width))
			return Set.of();
		Set<Integer> refined = new java.util.LinkedHashSet<>();
		for(int position = 0; position < width; position++) {
			Set<CandidateInputState> prior = new java.util.LinkedHashSet<>();
			for(List<CandidateInputState> row : priorRows)
				prior.add(row.get(position));
			Set<CandidateInputState> exact = new java.util.LinkedHashSet<>();
			for(FType type : exactInputDomains.get(position))
				exact.add(type == null ? CandidateInputState.absentLocal()
					: CandidateInputState.present(type));
			if(prior.containsAll(exact) && !exact.containsAll(prior))
				refined.add(position);
		}
		return Set.copyOf(refined);
	}
}
