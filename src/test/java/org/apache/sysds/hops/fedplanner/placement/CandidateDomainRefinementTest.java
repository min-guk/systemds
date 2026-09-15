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
import org.junit.Assert;
import org.junit.Test;

/** Contract tests for exact predecessor-domain refinement evidence. */
public class CandidateDomainRefinementTest {
	@Test
	public void removedPresentRowIsProvenByExactRefinedLocalDomain() {
		List<CandidateInputState> prior = List.of(
			CandidateInputState.present(FType.ROW),
			CandidateInputState.absentLocal(),
			CandidateInputState.absentLocal());
		List<List<FType>> rebuiltDomains = List.of(
			java.util.Collections.singletonList(null),
			java.util.Collections.singletonList(null),
			java.util.Collections.singletonList(null));

		Assert.assertTrue(CandidateDomainRefinement.removedByRefinedInputDomain(
			prior, rebuiltDomains, Set.of(0)));
	}

	@Test
	public void unchangedPresentRowIsNotARefinementWitness() {
		List<CandidateInputState> prior = List.of(CandidateInputState.present(FType.ROW));
		List<List<FType>> rebuiltDomains = List.of(List.of(FType.ROW));

		Assert.assertFalse(CandidateDomainRefinement.removedByRefinedInputDomain(
			prior, rebuiltDomains, Set.of(0)));
	}

	@Test
	public void unrefinedInputCannotAuthorizeRemoval() {
		List<CandidateInputState> prior = List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.absentLocal());
		List<List<FType>> rebuiltDomains = List.of(
			java.util.Collections.singletonList(null),
			java.util.Collections.singletonList(null));

		Assert.assertFalse(CandidateDomainRefinement.removedByRefinedInputDomain(
			prior, rebuiltDomains, Set.of(1)));
	}

	@Test
	public void priorProjectionProvesRefinementWhenOrdinalBookkeepingIsStale() {
		List<List<CandidateInputState>> priorRows = List.of(
			List.of(CandidateInputState.absentLocal(), CandidateInputState.absentLocal()),
			List.of(CandidateInputState.present(FType.COL), CandidateInputState.present(FType.BROADCAST)),
			List.of(CandidateInputState.present(FType.COL), CandidateInputState.present(FType.ROW)));
		List<List<FType>> rebuiltDomains = List.of(
			java.util.Arrays.asList(null, FType.COL), List.of(FType.ROW));

		Assert.assertEquals(Set.of(1), CandidateDomainRefinement.refinedInputPositions(
			priorRows, rebuiltDomains));
	}

	@Test
	public void replacementDomainIsNotClassifiedAsRefinement() {
		List<List<CandidateInputState>> priorRows = List.of(
			List.of(CandidateInputState.present(FType.ROW)));
		Assert.assertTrue(CandidateDomainRefinement.refinedInputPositions(
			priorRows, List.of(List.of(FType.COL))).isEmpty());
	}

	@Test
	public void malformedDomainCardinalityFailsClosed() {
		List<CandidateInputState> prior = List.of(CandidateInputState.present(FType.ROW));

		Assert.assertFalse(CandidateDomainRefinement.removedByRefinedInputDomain(
			prior, List.of(), Set.of(0)));
	}
}
