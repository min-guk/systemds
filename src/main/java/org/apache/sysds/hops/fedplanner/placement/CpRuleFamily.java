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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Exact rectangular family of scalar CP/LOUT candidate rows.
 *
 * <p>The family owns every member described by its position-preserving axes. It
 * never substitutes a representative {@link CandidateRuleKey}; an exact key and
 * fact are interned only when that exact member is requested.</p>
 */
public final class CpRuleFamily {
	private final CompiledHopKey parent;
	private final List<List<CandidateInputState>> axes;
	private final CandidateCapabilityFact capability;
	private final CandidateShapeProofFact shapeProof;
	private final CandidateProfileFact profile;
	private final CandidateEmissionFact emission;
	private final BigInteger logicalSize;
	private final String normalizedSignature;
	private final Map<List<CandidateInputState>,CandidateRuleFact> exactMembers = new LinkedHashMap<>();

	CpRuleFamily(CompiledHopKey parent, List<List<CandidateInputState>> axes,
		CandidateCapabilityFact capability, CandidateShapeProofFact shapeProof,
		CandidateProfileFact profile, CandidateEmissionFact emission) {
		this.parent = Objects.requireNonNull(parent, "CP family parent");
		Objects.requireNonNull(axes, "CP family axes");
		List<List<CandidateInputState>> copied = new ArrayList<>(axes.size());
		BigInteger size = BigInteger.ONE;
		for(int position = 0; position < axes.size(); position++) {
			List<CandidateInputState> axis = List.copyOf(Objects.requireNonNull(
				axes.get(position), "CP family axis"));
			if(axis.isEmpty())
				throw new IllegalArgumentException("CP family axis must not be empty: " + position);
			if(axis.stream().distinct().count() != axis.size())
				throw new IllegalArgumentException("CP family axis contains duplicate exact states: " + position);
			copied.add(axis);
			size = size.multiply(BigInteger.valueOf(axis.size()));
		}
		this.axes = List.copyOf(copied);
		this.capability = Objects.requireNonNull(capability, "CP family capability");
		this.shapeProof = Objects.requireNonNull(shapeProof, "CP family shape proof");
		this.profile = Objects.requireNonNull(profile, "CP family profile");
		this.emission = Objects.requireNonNull(emission, "CP family emission");
		PlacementState state = emission.emissionState().placementState();
		if(capability.nativeExec() != ExecType.CP
			|| capability.nativeOutput() != FederatedOutput.LOUT
			|| state.execType() != ExecType.CP || state.output() != FederatedOutput.LOUT
			|| emission.executionFType() != null || emission.derivedFoutAction() != null
			|| !shapeProof.requiredFacts().isEmpty() || !shapeProof.missingRequiredFacts().isEmpty()
			|| !profile.available() || !profile.producerOutputs().isEmpty())
			throw new IllegalArgumentException("CP family requires an exact scalar CP/LOUT header");
		logicalSize = size;
		normalizedSignature = parent.normalizedSignature() + "|cp-family|axes=" + axes.stream()
			.map(axis -> axis.stream().map(CandidateInputState::normalizedSignature).toList()).toList()
			+ "|capability=" + capability.opcode() + ':' + capability.reasonCode()
			+ "|emission=" + emission.selectionSignature();
	}

	public CompiledHopKey parent() { return parent; }
	public List<List<CandidateInputState>> axes() { return axes; }
	public CandidateCapabilityFact capability() { return capability; }
	public CandidateShapeProofFact shapeProof() { return shapeProof; }
	public CandidateProfileFact profile() { return profile; }
	public CandidateEmissionFact emission() { return emission; }
	public BigInteger logicalSize() { return logicalSize; }
	public String normalizedSignature() { return normalizedSignature; }

	public CpRuleFamily withEmissionState(PlacementState state) {
		Objects.requireNonNull(state, "CP family emission state");
		if(emission.emissionState().placementState() == state)
			return this;
		if(state.execType() != ExecType.CP || state.output() != FederatedOutput.LOUT
			|| state.fType() != null)
			throw new IllegalArgumentException("CP family can bind only to a CP/LOUT state");
		CandidateEmissionFact rebound = new CandidateEmissionFact(
			new PlacementEmissionState(state, false), null);
		return new CpRuleFamily(parent, axes, capability, shapeProof, profile, rebound);
	}

	public boolean contains(List<CandidateInputState> orderedInputs) {
		if(orderedInputs == null || orderedInputs.size() != axes.size())
			return false;
		for(int position = 0; position < axes.size(); position++)
			if(!axes.get(position).contains(orderedInputs.get(position)))
				return false;
		return true;
	}

	public List<CandidateInputState> canonicalInputs() {
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
		int targetVariableLength = suffixLengths.get(0).stream().min((left, right) -> {
			int order = compareLengthPrefixes(fixedLength + left, fixedLength + right);
			return order != 0 ? order : Integer.compare(left, right);
		}).orElseThrow();
		List<CandidateInputState> inputs = new ArrayList<>(axes.size());
		int remaining = targetVariableLength;
		for(int position = 0; position < axes.size(); position++) {
			final int suffixPosition = position + 1;
			final int required = remaining;
			CandidateInputState selected = axes.get(position).stream()
				.filter(input -> suffixLengths.get(suffixPosition).contains(
					required - input.normalizedSignature().length()))
				.min(java.util.Comparator.comparing(CandidateInputState::normalizedSignature))
				.orElseThrow();
			inputs.add(selected);
			remaining -= selected.normalizedSignature().length();
		}
		return Collections.unmodifiableList(inputs);
	}

	private static int compareLengthPrefixes(int left, int right) {
		String leftPrefix = Integer.toString(left) + ':';
		String rightPrefix = Integer.toString(right) + ':';
		return leftPrefix.compareTo(rightPrefix);
	}

	public synchronized CandidateRuleFact canonicalMember() {
		return requireExact(canonicalInputs());
	}

	public synchronized CandidateRuleFact requireExact(List<CandidateInputState> orderedInputs) {
		if(!contains(orderedInputs))
			throw new IllegalArgumentException("Exact inputs are outside CP rule family: " + orderedInputs);
		List<CandidateInputState> keyInputs = List.copyOf(orderedInputs);
		return exactMembers.computeIfAbsent(keyInputs, inputs -> {
			CandidateRuleKey key = new CandidateRuleKey(parent, inputs);
			return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
				capability, shapeProof, profile, List.of(emission), "");
		});
	}

	public synchronized int materializedMemberCount() { return exactMembers.size(); }

	static List<List<CandidateInputState>> axes(List<List<FType>> domains,
		List<List<Integer>> allowedOrdinals) {
		if(domains.size() != allowedOrdinals.size())
			throw new IllegalArgumentException("CP family domain arity differs");
		List<List<CandidateInputState>> axes = new ArrayList<>(domains.size());
		for(int position = 0; position < domains.size(); position++) {
			List<CandidateInputState> states = new ArrayList<>();
			for(int ordinal : allowedOrdinals.get(position)) {
				FType type = domains.get(position).get(ordinal);
				CandidateInputState state = type == null
					? CandidateInputState.absentLocal() : CandidateInputState.present(type);
				if(!states.contains(state))
					states.add(state);
			}
			axes.add(List.copyOf(states));
		}
		return List.copyOf(axes);
	}
}
