/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Symbolic monotonicity proof across explicit rows and scalar CP-family rectangles. */
final class CandidateFamilyRefinement {
	private CandidateFamilyRefinement() {
		// utility class
	}

	/** Exact inventory comparison; normalized signatures are intentionally insufficient. */
	static boolean sameFamilyInventory(List<CpRuleFamily> left, List<CpRuleFamily> right) {
		if(left == null || right == null || left.size() != right.size())
			return false;
		for(int index = 0; index < left.size(); index++) {
			CpRuleFamily first = left.get(index);
			CpRuleFamily second = right.get(index);
			if(first == null || second == null || first.parent() != second.parent()
				|| !first.axes().equals(second.axes()) || !sameHeader(first, second))
				return false;
		}
		return true;
	}

	/** Exact-row authority retained by one non-overlapping replacement family. */
	static boolean retainsExactFact(CandidateRuleFact fact,
		List<CpRuleFamily> replacementFamilies) {
		if(fact == null || replacementFamilies == null || !eligible(fact))
			return false;
		Header header = Header.of(fact);
		int matches = 0;
		for(CpRuleFamily family : replacementFamilies)
			if(family != null && header.matches(family)
				&& family.contains(fact.key().orderedInputs()))
				matches++;
		return matches == 1;
	}

	/** Symbolic coverage of one prior family; an entirely refined-away family is covered. */
	static boolean covers(CpRuleFamily prior, List<CpRuleFamily> replacementFamilies,
		List<CandidateRuleFact> replacementFacts, List<List<FType>> exactInputDomains,
		Set<Integer> refinedInputPositions) {
		if(prior == null || replacementFacts == null || replacementFamilies == null
			|| exactInputDomains == null || refinedInputPositions == null)
			return false;
		for(int position : refinedInputPositions)
			if(position < 0 || position >= exactInputDomains.size())
				return false;
		if(exactInputDomains.stream().anyMatch(Objects::isNull)
			|| !owned(prior.parent(), replacementFacts, replacementFamilies)
			|| prior.axes().size() != exactInputDomains.size())
			return false;
		List<List<CandidateInputState>> surviving = refine(
			prior.axes(), exactInputDomains, refinedInputPositions);
		return surviving != null && (empty(surviving)
			|| covered(surviving, Header.of(prior), replacementFacts, replacementFamilies));
	}

	private static boolean owned(CompiledHopKey owner, List<CandidateRuleFact> facts,
		List<CpRuleFamily> families) {
		return facts.stream().allMatch(fact -> fact != null
			&& fact.key().parentOccurrence() == owner)
			&& families.stream().allMatch(family -> family != null && family.parent() == owner);
	}

	private static List<List<CandidateInputState>> refine(List<List<CandidateInputState>> axes,
		List<List<FType>> exactDomains, Set<Integer> refinedPositions) {
		if(axes.size() != exactDomains.size())
			return null;
		List<List<CandidateInputState>> result = new ArrayList<>(axes.size());
		for(int position = 0; position < axes.size(); position++) {
			List<CandidateInputState> axis = axes.get(position);
			if(axis == null)
				return null;
			if(!refinedPositions.contains(position)) {
				result.add(axis);
				continue;
			}
			Set<CandidateInputState> admitted = new LinkedHashSet<>();
			for(FType type : exactDomains.get(position))
				admitted.add(type == null ? CandidateInputState.absentLocal()
					: CandidateInputState.present(type));
			result.add(axis.stream().filter(admitted::contains).toList());
		}
		return List.copyOf(result);
	}

	private static boolean covered(List<List<CandidateInputState>> target, Header header,
		List<CandidateRuleFact> facts, List<CpRuleFamily> families) {
		List<List<List<CandidateInputState>>> rectangles = new ArrayList<>();
		for(CpRuleFamily family : families) {
			if(family.axes().size() != target.size())
				return false;
			if(!header.matches(family))
				continue;
			List<List<CandidateInputState>> intersection = intersection(target, family.axes());
			if(!empty(intersection))
				rectangles.add(intersection);
		}
		for(int left = 0; left < rectangles.size(); left++)
			for(int right = left + 1; right < rectangles.size(); right++)
				if(!empty(intersection(rectangles.get(left), rectangles.get(right))))
					return false;

		BigInteger covered = BigInteger.ZERO;
		for(List<List<CandidateInputState>> rectangle : rectangles)
			covered = covered.add(size(rectangle));
		Set<List<CandidateInputState>> explicit = new HashSet<>();
		for(CandidateRuleFact fact : facts) {
			if(fact.key().orderedInputs().size() != target.size())
				return false;
			if(!header.matches(fact) || !contains(target, fact.key().orderedInputs()))
				continue;
			if(rectangles.stream().anyMatch(rectangle ->
				contains(rectangle, fact.key().orderedInputs())))
				return false;
			explicit.add(fact.key().orderedInputs());
		}
		covered = covered.add(BigInteger.valueOf(explicit.size()));
		return covered.equals(size(target));
	}

	private static List<List<CandidateInputState>> intersection(
		List<List<CandidateInputState>> left, List<List<CandidateInputState>> right) {
		if(left.size() != right.size())
			return List.of(List.of());
		List<List<CandidateInputState>> result = new ArrayList<>(left.size());
		for(int position = 0; position < left.size(); position++)
			result.add(left.get(position).stream().filter(right.get(position)::contains).toList());
		return List.copyOf(result);
	}

	private static boolean contains(List<List<CandidateInputState>> rectangle,
		List<CandidateInputState> point) {
		if(rectangle.size() != point.size())
			return false;
		for(int position = 0; position < rectangle.size(); position++)
			if(!rectangle.get(position).contains(point.get(position)))
				return false;
		return true;
	}

	private static boolean empty(List<List<CandidateInputState>> rectangle) {
		return rectangle.stream().anyMatch(List::isEmpty);
	}

	private static BigInteger size(List<List<CandidateInputState>> rectangle) {
		BigInteger result = BigInteger.ONE;
		for(List<CandidateInputState> axis : rectangle)
			result = result.multiply(BigInteger.valueOf(axis.size()));
		return result;
	}

	private static boolean eligible(CandidateRuleFact fact) {
		return fact.status() == CandidateEvaluationStatus.AVAILABLE
			&& fact.failureCode().isEmpty() && fact.capability() != null
			&& fact.allowedEmissionFacts().size() == 1;
	}

	private static boolean sameHeader(CpRuleFamily left, CpRuleFamily right) {
		return left.capability().equals(right.capability())
			&& left.shapeProof().equals(right.shapeProof())
			&& left.profile().equals(right.profile())
			&& left.emission().equals(right.emission());
	}

	private record Header(CompiledHopKey owner, CandidateCapabilityFact capability,
		CandidateShapeProofFact shapeProof, CandidateProfileFact profile,
		CandidateEmissionFact emission) {
		static Header of(CpRuleFamily family) {
			return new Header(family.parent(), family.capability(), family.shapeProof(),
				family.profile(), family.emission());
		}
		static Header of(CandidateRuleFact fact) {
			return new Header(fact.key().parentOccurrence(), fact.capability(), fact.shapeProof(),
				fact.profile(), fact.allowedEmissionFacts().get(0));
		}
		boolean matches(CpRuleFamily family) {
			return family.parent() == owner && capability.equals(family.capability())
				&& shapeProof.equals(family.shapeProof()) && profile.equals(family.profile())
				&& emission.equals(family.emission());
		}
		boolean matches(CandidateRuleFact fact) {
			return eligible(fact) && fact.key().parentOccurrence() == owner
				&& capability.equals(fact.capability()) && shapeProof.equals(fact.shapeProof())
				&& profile.equals(fact.profile()) && emission.equals(fact.allowedEmissionFacts().get(0));
		}
	}
}
