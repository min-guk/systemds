/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/**
 * Exact conditional relation of candidate-rule members.
 *
 * <p>Each disjoint region owns one exact header and rectangular input axes. A
 * member key/fact is interned only when that exact input tuple is requested.</p>
 */
public final class CandidateRuleRelation {
	/**
	 * Immutable, closure-owned input-indexed emission authority. Implementations
	 * resolve from closed source/action tables, never by rerunning an Oracle or
	 * consulting mutable planner state. Absent inputs are explicit table options.
	 * All stored source, action and proof identities must be validated against
	 * the same closed source revision before this object is published. Region
	 * membership denotes an available exact candidate row. Every member therefore
	 * requires a nonempty emission list; sparse holes must be excluded from its
	 * regions rather than represented by an empty resolver result.
	 */
	public interface MemberEmissions {
		List<CandidateEmissionFact> resolve(List<CandidateInputState> inputs);
		String normalizedSignature();
		long storedChoiceCount();
	}

	public record Header(CandidateCapabilityFact capability, CandidateShapeProofFact shapeProof,
		CandidateProfileFact profile, List<CandidateEmissionFact> emissions) {
		public Header {
			Objects.requireNonNull(capability, "candidate relation capability");
			Objects.requireNonNull(shapeProof, "candidate relation shape proof");
			Objects.requireNonNull(profile, "candidate relation profile");
			emissions = List.copyOf(Objects.requireNonNull(emissions, "candidate relation emissions"));
			if(!profile.available() || emissions.isEmpty())
				throw new IllegalArgumentException("Candidate relation requires an available exact header");
		}
		public Header mapEmissions(UnaryOperator<CandidateEmissionFact> mapper) {
			return new Header(capability, shapeProof, profile, emissions.stream().map(mapper).toList());
		}
		public String normalizedSignature() {
			return "capability=" + capability + "|shapeProof=" + shapeProof
				+ "|profile=" + profile + "|emissions=" + emissions.stream()
					.map(CandidateEmissionFact::normalizedSignature).toList();
		}
	}

	public static final class ConditionalRegion {
		private final List<List<CandidateInputState>> axes;
		private final Header header;
		private final BigInteger logicalSize;
		private final MemberEmissions memberEmissions;
		private final String closedEmissionSignature;
		private volatile String normalizedSignature;
		private final Map<List<CandidateInputState>,List<CandidateEmissionFact>> resolvedEmissions =
			new LinkedHashMap<>();

		public ConditionalRegion(List<List<CandidateInputState>> axes, Header header) {
			this(axes, header, null);
		}

		private ConditionalRegion(List<List<CandidateInputState>> axes, Header header,
			MemberEmissions memberEmissions) {
			Objects.requireNonNull(axes, "candidate relation axes");
			List<List<CandidateInputState>> copied = new ArrayList<>(axes.size());
			BigInteger size = BigInteger.ONE;
			for(int position = 0; position < axes.size(); position++) {
				List<CandidateInputState> axis = List.copyOf(Objects.requireNonNull(
					axes.get(position), "candidate relation axis"));
				if(axis.isEmpty() || axis.stream().distinct().count() != axis.size())
					throw new IllegalArgumentException("Candidate relation axis is empty or duplicated: " + position);
				copied.add(axis);
				size = size.multiply(BigInteger.valueOf(axis.size()));
			}
			this.axes = List.copyOf(copied);
			this.header = Objects.requireNonNull(header, "candidate relation header");
			this.memberEmissions = memberEmissions;
			logicalSize = size;
			// Validate and snapshot the closed authority as before, but do not walk
			// every support member in the header just to construct this relation.
			closedEmissionSignature = memberEmissions == null ? null
				: Objects.requireNonNull(memberEmissions.normalizedSignature(), "closed emission signature");
		}

		public static ConditionalRegion withConditionedEmissions(List<List<CandidateInputState>> axes,
			Header template, MemberEmissions emissions) {
			return new ConditionalRegion(axes, template,
				Objects.requireNonNull(emissions, "closed member emissions"));
		}

		public List<List<CandidateInputState>> axes() { return axes; }
		public Header header() { return header; }
		public BigInteger logicalSize() { return logicalSize; }
		public boolean hasConditionedEmissions() { return memberEmissions != null; }
		public long storedEmissionChoiceCount() {
			return memberEmissions == null ? header.emissions().size() : memberEmissions.storedChoiceCount();
		}
		public synchronized int materializedEmissionMemberCount() { return resolvedEmissions.size(); }
		public synchronized List<CandidateEmissionFact> emissionsFor(List<CandidateInputState> inputs) {
			if(!contains(inputs))
				throw new IllegalArgumentException("Inputs are outside the closed emission region");
			return memberEmissions == null ? header.emissions()
				: resolvedEmissions.computeIfAbsent(List.copyOf(inputs), selected -> {
					List<CandidateEmissionFact> resolved = List.copyOf(memberEmissions.resolve(selected));
					if(resolved.isEmpty())
						throw new IllegalStateException("Available candidate relation member has no emissions");
					return resolved;
				});
		}
		public boolean contains(List<CandidateInputState> inputs) {
			if(inputs == null || inputs.size() != axes.size())
				return false;
			for(int position = 0; position < axes.size(); position++)
				if(!axes.get(position).contains(inputs.get(position)))
					return false;
			return true;
		}
		public ConditionalRegion mapEmissions(UnaryOperator<CandidateEmissionFact> mapper) {
			if(memberEmissions != null)
				throw new UnsupportedOperationException(
					"Rebind input-indexed emission tables before publishing the closed relation");
			return new ConditionalRegion(axes, header.mapEmissions(mapper));
		}
		public List<CandidateInputState> canonicalInputs(CompiledHopKey parent) {
			return CandidateRuleRelation.canonicalInputs(parent, axes);
		}
		public String normalizedSignature() {
			String result = normalizedSignature;
			if(result == null) {
				result = "axes=" + axes.stream().map(axis -> axis.stream()
					.map(CandidateInputState::normalizedSignature).toList()).toList()
					+ "|header=" + header.normalizedSignature()
					+ (closedEmissionSignature == null ? "" : "|closedEmissions=" + closedEmissionSignature);
				normalizedSignature = result;
			}
			return result;
		}
	}

	private final CompiledHopKey parent;
	private final List<ConditionalRegion> regions;
	private final BigInteger logicalSize;
	private volatile String normalizedSignature;
	private final Map<List<CandidateInputState>,CandidateRuleFact> exactMembers = new LinkedHashMap<>();

	public CandidateRuleRelation(CompiledHopKey parent, List<ConditionalRegion> regions) {
		this.parent = Objects.requireNonNull(parent, "candidate relation parent");
		this.regions = List.copyOf(Objects.requireNonNull(regions, "candidate relation regions"));
		if(this.regions.isEmpty())
			throw new IllegalArgumentException("Candidate relation requires at least one region");
		int arity = this.regions.get(0).axes().size();
		BigInteger size = BigInteger.ZERO;
		for(int left = 0; left < this.regions.size(); left++) {
			ConditionalRegion region = this.regions.get(left);
			if(region.axes().size() != arity)
				throw new IllegalArgumentException("Candidate relation region arity differs");
			size = size.add(region.logicalSize());
			for(int right = left + 1; right < this.regions.size(); right++)
				if(overlap(region, this.regions.get(right)))
					throw new IllegalArgumentException("Candidate relation regions overlap");
		}
		logicalSize = size;
	}

	public CompiledHopKey parent() { return parent; }
	public List<ConditionalRegion> regions() { return regions; }
	public BigInteger logicalSize() { return logicalSize; }
	public String normalizedSignature() {
		String result = normalizedSignature;
		if(result == null) {
			result = parent.normalizedSignature() + "|candidateRelation="
				+ regions.stream().map(ConditionalRegion::normalizedSignature).toList();
			normalizedSignature = result;
		}
		return result;
	}
	public synchronized int materializedMemberCount() { return exactMembers.size(); }

	public boolean contains(List<CandidateInputState> inputs) {
		return matchingRegion(inputs) != null;
	}

	public Optional<ConditionalRegion> regionFor(List<CandidateInputState> inputs) {
		return Optional.ofNullable(matchingRegion(inputs));
	}

	/** Deterministic minimum exact key without expanding any region product. */
	public List<CandidateInputState> canonicalInputs() {
		return regions.stream().map(region -> region.canonicalInputs(parent)).min((left, right) -> {
			String leftKey = new CandidateRuleKey(parent, left).normalizedSignature();
			String rightKey = new CandidateRuleKey(parent, right).normalizedSignature();
			int order = compareLengthPrefixes(leftKey.length(), rightKey.length());
			return order != 0 ? order : leftKey.compareTo(rightKey);
		}).orElseThrow();
	}

	public synchronized CandidateRuleFact canonicalMember() {
		return requireExact(canonicalInputs());
	}

	/**
	 * Lazily restores every exact row for legacy selectors that require a flat
	 * candidate domain. The compact relation remains the stored authority; rows
	 * are interned only when such a selector is actually invoked.
	 */
	public void forEachExactMember(Consumer<CandidateRuleFact> consumer) {
		Objects.requireNonNull(consumer, "candidate relation member consumer");
		for(ConditionalRegion region : regions)
			forEachExactMember(region, 0, new ArrayList<>(), consumer);
	}

	private void forEachExactMember(ConditionalRegion region, int position,
		List<CandidateInputState> inputs, Consumer<CandidateRuleFact> consumer) {
		if(position == region.axes().size()) {
			consumer.accept(requireExact(inputs));
			return;
		}
		for(CandidateInputState input : region.axes().get(position)) {
			inputs.add(input);
			forEachExactMember(region, position + 1, inputs, consumer);
			inputs.remove(inputs.size() - 1);
		}
	}

	public synchronized CandidateRuleFact requireExact(List<CandidateInputState> inputs) {
		ConditionalRegion region = matchingRegion(inputs);
		if(region == null)
			throw new IllegalArgumentException("Exact inputs are outside candidate relation: " + inputs);
		List<CandidateInputState> copied = List.copyOf(inputs);
		return exactMembers.computeIfAbsent(copied, keyInputs -> {
			Header header = region.header();
			return new CandidateRuleFact(new CandidateRuleKey(parent, keyInputs),
				CandidateEvaluationStatus.AVAILABLE, header.capability(), header.shapeProof(),
				header.profile(), region.emissionsFor(keyInputs), "");
		});
	}

	public CandidateRuleRelation mapEmissions(UnaryOperator<CandidateEmissionFact> mapper) {
		Objects.requireNonNull(mapper, "candidate relation emission mapper");
		return new CandidateRuleRelation(parent, regions.stream().map(region ->
			region.mapEmissions(mapper)).toList());
	}

	private ConditionalRegion matchingRegion(List<CandidateInputState> inputs) {
		ConditionalRegion match = null;
		for(ConditionalRegion region : regions)
			if(region.contains(inputs)) {
				if(match != null)
					throw new IllegalStateException("Exact inputs match multiple candidate regions");
				match = region;
			}
		return match;
	}

	private static boolean overlap(ConditionalRegion left, ConditionalRegion right) {
		if(left.axes().size() != right.axes().size())
			return false;
		for(int position = 0; position < left.axes().size(); position++)
			if(left.axes().get(position).stream().noneMatch(right.axes().get(position)::contains))
				return false;
		return true;
	}

	private static List<CandidateInputState> canonicalInputs(CompiledHopKey parent,
		List<List<CandidateInputState>> axes) {
		List<java.util.Set<Integer>> suffixLengths = new ArrayList<>(axes.size() + 1);
		for(int index = 0; index <= axes.size(); index++)
			suffixLengths.add(new java.util.HashSet<>());
		suffixLengths.get(axes.size()).add(0);
		for(int position = axes.size() - 1; position >= 0; position--)
			for(CandidateInputState input : axes.get(position))
				for(int suffix : suffixLengths.get(position + 1))
					suffixLengths.get(position).add(Math.addExact(
						input.normalizedSignature().length(), suffix));
		int fixedLength = parent.normalizedSignature().length() + "|inputs=[]".length()
			+ Math.max(0, axes.size() - 1) * 2;
		int target = suffixLengths.get(0).stream().min((left, right) -> {
			int order = compareLengthPrefixes(fixedLength + left, fixedLength + right);
			return order != 0 ? order : Integer.compare(left, right);
		}).orElseThrow();
		List<CandidateInputState> result = new ArrayList<>(axes.size());
		int remaining = target;
		for(int position = 0; position < axes.size(); position++) {
			final int suffixPosition = position + 1;
			final int required = remaining;
			CandidateInputState selected = axes.get(position).stream()
				.filter(input -> suffixLengths.get(suffixPosition).contains(
					required - input.normalizedSignature().length()))
				.min(java.util.Comparator.comparing(CandidateInputState::normalizedSignature))
				.orElseThrow();
			result.add(selected);
			remaining -= selected.normalizedSignature().length();
		}
		return List.copyOf(result);
	}

	private static int compareLengthPrefixes(int left, int right) {
		return (Integer.toString(left) + ':').compareTo(Integer.toString(right) + ':');
	}
}
