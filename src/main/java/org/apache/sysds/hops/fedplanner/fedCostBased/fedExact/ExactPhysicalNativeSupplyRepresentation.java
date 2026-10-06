/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/**
 * Lossless projection of graph-owned physical rows into native executions ({@code a_v}) and
 * edge/output supplies ({@code b_e}). The original alternatives remain the legality authority;
 * relation witnesses prevent an accidental Cartesian product of independently projected values.
 */
final class ExactPhysicalNativeSupplyRepresentation {
	enum NativeExecutionKind {
		CANDIDATE, DURABLE_SOURCE, SUPPLY_ONLY, LEGAL_SINGLETON, SYNTHETIC_BOUNDARY
	}
	enum SupplyDirection { INPUT, OUTPUT }
	enum SupplyActionKind { NATIVE_LOCAL, DIRECT_FOUT, RELOCATION, OUTPUT_MATERIALIZATION }

	record NativeLayout(PlacementLayoutKind kind, DurableAnchorKey anchor,
		String lineage, boolean exactWorkerPool) { }

	record NativeCandidate(CompiledHopKey decision, NativeExecutionKind executionKind,
		CandidateRuleKey execution, ExecType executionType, FType executionFType,
		List<CandidateInputState> requiredInputStates, PlacementState nativeOutputState,
		NativeLayout nativeOutputLayout) {
		NativeCandidate {
			Objects.requireNonNull(decision, "decision");
			Objects.requireNonNull(executionKind, "executionKind");
			requiredInputStates = List.copyOf(requiredInputStates);
			Objects.requireNonNull(nativeOutputState, "nativeOutputState");
			Objects.requireNonNull(nativeOutputLayout, "nativeOutputLayout");
		}
	}

	record SourceProvenance(CompiledHopKey sourceDecision, ValueVersionKey valueVersion,
		CandidateRealizationReference realization, DurableAnchorKey durableAnchor) { }

	record TargetLayout(PlacementLayoutKind kind, DurableAnchorKey anchor, String lineage) { }

	record SupplyCandidate(CompiledHopKey consumer, SupplyDirection direction, int inputPosition,
		SourceProvenance source, SupplyActionKind actionKind, Object action,
		PlacementState targetState, TargetLayout targetLayout) {
		SupplyCandidate {
			Objects.requireNonNull(consumer, "consumer");
			Objects.requireNonNull(direction, "direction");
			if(direction == SupplyDirection.INPUT && inputPosition < 0
				|| direction == SupplyDirection.OUTPUT && inputPosition != -1)
				throw new IllegalArgumentException("EXACT_SUPPLY_POSITION_INVALID");
			Objects.requireNonNull(actionKind, "actionKind");
			Objects.requireNonNull(targetState, "targetState");
			Objects.requireNonNull(targetLayout, "targetLayout");
			if(actionKind == SupplyActionKind.RELOCATION
				&& !(action instanceof RelocationAction)
				|| actionKind == SupplyActionKind.OUTPUT_MATERIALIZATION
					&& !(action instanceof DerivedFoutMaterializationAction)
				|| actionKind == SupplyActionKind.DIRECT_FOUT && action != null
					&& !(action instanceof RelocationAction)
				|| actionKind == SupplyActionKind.NATIVE_LOCAL && action != null)
				throw new IllegalArgumentException("EXACT_SUPPLY_ACTION_INVALID");
		}
	}

	record RelationWitness(int originalOrdinal, int nativeOrdinal, List<Integer> supplyOrdinals) {
		RelationWitness {
			if(originalOrdinal < 0 || nativeOrdinal < 0)
				throw new IllegalArgumentException("EXACT_NATIVE_SUPPLY_RELATION_ORDINAL_INVALID");
			supplyOrdinals = List.copyOf(supplyOrdinals);
		}
	}

	record Domain(ExactPhysicalModel.DecisionDomain authority, List<NativeCandidate> nativeCandidates,
		List<SupplyCandidate> supplyCandidates, List<RelationWitness> relation) {
		Domain {
			Objects.requireNonNull(authority, "authority");
			nativeCandidates = List.copyOf(nativeCandidates);
			supplyCandidates = List.copyOf(supplyCandidates);
			relation = List.copyOf(relation);
			if(relation.size() != authority.alternatives().size())
				throw new IllegalArgumentException("EXACT_NATIVE_SUPPLY_RELATION_INCOMPLETE");
			for(int row = 0; row < relation.size(); row++) {
				RelationWitness witness = relation.get(row);
				if(witness.originalOrdinal() != row || witness.nativeOrdinal() >= nativeCandidates.size())
					throw new IllegalArgumentException("EXACT_NATIVE_SUPPLY_RELATION_INVALID");
				for(int ordinal : witness.supplyOrdinals())
					if(ordinal < 0 || ordinal >= supplyCandidates.size())
						throw new IllegalArgumentException("EXACT_NATIVE_SUPPLY_RELATION_INVALID");
			}
		}
		RelationWitness witness(int originalOrdinal) { return relation.get(originalOrdinal); }
		NativeCandidate nativeCandidate(int originalOrdinal) {
			return nativeCandidates.get(witness(originalOrdinal).nativeOrdinal());
		}
		List<SupplyCandidate> supplies(int originalOrdinal) {
			return witness(originalOrdinal).supplyOrdinals().stream().map(supplyCandidates::get).toList();
		}
		List<Integer> reconstruct(int nativeOrdinal, List<Integer> supplyOrdinals) {
			return relation.stream().filter(witness -> witness.nativeOrdinal() == nativeOrdinal
				&& witness.supplyOrdinals().equals(supplyOrdinals))
				.map(RelationWitness::originalOrdinal).toList();
		}
	}

	record Statistics(int originalRows, int nativeCandidates, int supplyCandidates,
		int relationWitnesses) { }

	private final List<Domain> domains;
	private final IdentityHashMap<CompiledHopKey,Domain> byDecision;
	private final Statistics statistics;

	private ExactPhysicalNativeSupplyRepresentation(List<Domain> domains) {
		this.domains = List.copyOf(domains);
		byDecision = new IdentityHashMap<>();
		int rows = 0;
		int natives = 0;
		int supplies = 0;
		int witnesses = 0;
		for(Domain domain : domains) {
			byDecision.put(domain.authority().node().key(), domain);
			rows += domain.authority().alternatives().size();
			natives += domain.nativeCandidates().size();
			supplies += domain.supplyCandidates().size();
			witnesses += domain.relation().size();
		}
		statistics = new Statistics(rows, natives, supplies, witnesses);
	}

	static ExactPhysicalNativeSupplyRepresentation build(ExactPhysicalModel model) {
		List<Domain> domains = new ArrayList<>(model.domains().size());
		for(ExactPhysicalModel.DecisionDomain domain : model.domains())
			domains.add(buildDomain(model, domain));
		return new ExactPhysicalNativeSupplyRepresentation(domains);
	}

	List<Domain> domains() { return domains; }
	Domain domain(CompiledHopKey decision) {
		Domain domain = byDecision.get(decision);
		if(domain == null)
			throw new IllegalArgumentException("EXACT_NATIVE_SUPPLY_DOMAIN_UNKNOWN");
		return domain;
	}
	Statistics statistics() { return statistics; }

	private static Domain buildDomain(ExactPhysicalModel model,
		ExactPhysicalModel.DecisionDomain domain) {
		Map<NativeCandidate,Integer> nativeOrdinals = new LinkedHashMap<>();
		Map<SupplyCandidate,Integer> supplyOrdinals = new LinkedHashMap<>();
		List<NativeCandidate> natives = new ArrayList<>();
		List<SupplyCandidate> supplies = new ArrayList<>();
		List<RelationWitness> relation = new ArrayList<>();
		for(int row = 0; row < domain.alternatives().size(); row++) {
			ExactPhysicalModel.Alternative alternative = domain.alternatives().get(row);
			NativeCandidate nativeCandidate = nativeCandidate(alternative);
			int nativeOrdinal = nativeOrdinals.computeIfAbsent(nativeCandidate, ignored -> {
				natives.add(nativeCandidate);
				return natives.size() - 1;
			});
			List<Integer> selectedSupplies = new ArrayList<>();
			for(SupplyCandidate supply : supplies(model, alternative))
				selectedSupplies.add(supplyOrdinals.computeIfAbsent(supply, ignored -> {
					supplies.add(supply);
					return supplies.size() - 1;
				}));
			relation.add(new RelationWitness(row, nativeOrdinal, selectedSupplies));
		}
		return new Domain(domain, natives, supplies, relation);
	}

	private static NativeCandidate nativeCandidate(ExactPhysicalModel.Alternative alternative) {
		CandidateRuleFact rule = alternative.captured()
			? alternative.candidateRule() : alternative.executionRule();
		var emission = alternative.captured()
			? alternative.candidateEmission() : alternative.executionEmission();
		PlacementState nativeState = alternative.derivedFoutAction() == null
			? alternative.state() : alternative.derivedFoutAction().key().sourcePlacement();
		if(alternative.relocationAction() != null) {
			if(emission == null) {
				List<PlacementState> directSources = alternative.relocationAction()
					.directSourcePlacements();
				nativeState = directSources.size() == 1 ? directSources.get(0)
					: new PlacementState(org.apache.sysds.common.Types.ExecType.FED,
						org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT,
						alternative.relocationAction().key().materializationFType(),
						alternative.state().shapeDependent());
			}
		}
		NativeExecutionKind kind = rule != null ? NativeExecutionKind.CANDIDATE
			: switch(alternative.authorityKind()) {
				case DURABLE_ANCHOR -> NativeExecutionKind.DURABLE_SOURCE;
				case RELOCATION_SOURCE -> NativeExecutionKind.SUPPLY_ONLY;
				case SYNTHETIC_BOUNDARY -> NativeExecutionKind.SYNTHETIC_BOUNDARY;
				default -> NativeExecutionKind.LEGAL_SINGLETON;
			};
		CandidateRealizationSupportClause clause = alternative.supportClause();
		DurableAnchorKey nativeAnchor = clause == null ? alternative.durableAnchor()
			: clause.nativeWorkerPoolWitness();
		PlacementLayoutKind layoutKind = nativeState.output()
			== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT
			? PlacementLayoutKind.LOCAL
			: alternative.realization() != null ? alternative.realization().key().layoutKind()
				: nativeAnchor != null ? PlacementLayoutKind.DURABLE_MAP : PlacementLayoutKind.VALUE_MAP;
		String lineage = alternative.realization() != null
			? alternative.realization().key().nativeLineage() : null;
		if(alternative.derivedFoutAction() != null) {
			layoutKind = nativeState.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT
					? PlacementLayoutKind.LOCAL : PlacementLayoutKind.NATIVE_LINEAGE;
			lineage = layoutKind == PlacementLayoutKind.NATIVE_LINEAGE
				? rule.key().normalizedSignature() : null;
			nativeAnchor = null;
		}
		return new NativeCandidate(alternative.decision(), kind, rule == null ? null : rule.key(),
			emission == null ? nativeState.execType() : emission.emissionState().placementState().execType(),
			emission == null ? nativeState.fType() : emission.executionFType(), alternative.orderedInputs(), nativeState,
			new NativeLayout(layoutKind, nativeAnchor, lineage,
				clause != null && clause.nativeWorkerPoolLayoutExact()));
	}

	private static List<SupplyCandidate> supplies(ExactPhysicalModel model,
		ExactPhysicalModel.Alternative alternative) {
		List<SupplyCandidate> supplies = new ArrayList<>();
		for(ExactPhysicalModel.InputAuthority authority : alternative.inputAuthorities()) {
			List<CompiledHopKey> sourceDecisions = sourceDecisions(model, alternative, authority);
			if(sourceDecisions.isEmpty())
				sourceDecisions = java.util.Collections.singletonList(null);
			for(CompiledHopKey sourceDecision : sourceDecisions) {
				CandidateRealizationInputBinding binding = binding(
					alternative.supportClause(), authority, sourceDecision);
				ValueVersionKey version = sourceDecision == null ? null
					: model.analysis().graph().node(sourceDecision).orElseThrow().valueVersion();
				CandidateRealizationReference reference = binding == null ? null : binding.source();
				RelocationAction relocation = authority.relocationAction();
				SupplyActionKind actionKind = switch(authority.kind()) {
					case NATIVE_LOCAL -> SupplyActionKind.NATIVE_LOCAL;
					case DIRECT_FOUT -> SupplyActionKind.DIRECT_FOUT;
					case RELOCATION -> SupplyActionKind.RELOCATION;
				};
				PlacementState target = targetState(alternative, authority);
				DurableAnchorKey targetAnchor = relocation == null ? null
					: relocation.key().durableAnchor();
				supplies.add(new SupplyCandidate(alternative.decision(), SupplyDirection.INPUT,
					authority.inputPosition(), new SourceProvenance(sourceDecision, version, reference,
						targetAnchor), actionKind, relocation, target,
					new TargetLayout(target.output()
						== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT
							? PlacementLayoutKind.LOCAL
							: targetAnchor == null ? PlacementLayoutKind.VALUE_MAP
								: PlacementLayoutKind.DURABLE_MAP, targetAnchor, null)));
			}
		}
		if(alternative.derivedFoutAction() != null) {
			DerivedFoutMaterializationAction action = alternative.derivedFoutAction();
			supplies.add(new SupplyCandidate(alternative.decision(), SupplyDirection.OUTPUT, -1,
				new SourceProvenance(alternative.decision(), action.key().producerValueVersion(), null,
					null), SupplyActionKind.OUTPUT_MATERIALIZATION, action, action.key().targetPlacement(),
				new TargetLayout(PlacementLayoutKind.DURABLE_MAP,
					action.key().durableAnchor(), null)));
		}
		else if(alternative.relocationAction() != null) {
			RelocationAction action = alternative.relocationAction();
			supplies.add(new SupplyCandidate(alternative.decision(), SupplyDirection.OUTPUT, -1,
				new SourceProvenance(null, action.key().sourceValueVersion(), null,
					action.key().durableAnchor()), SupplyActionKind.RELOCATION, action,
				action.key().targetPlacement(), new TargetLayout(PlacementLayoutKind.DURABLE_MAP,
					action.key().durableAnchor(), null)));
		}
		return List.copyOf(supplies);
	}

	private static List<CompiledHopKey> sourceDecisions(ExactPhysicalModel model,
		ExactPhysicalModel.Alternative alternative, ExactPhysicalModel.InputAuthority authority) {
		if(authority.sourceDecision() != null)
			return List.of(authority.sourceDecision());
		IdentityHashMap<CompiledHopKey,Boolean> sources = new IdentityHashMap<>();
		for(var edge : model.analysis().compiledInputEdgesInCanonicalOrder())
			if(edge.consumer() == alternative.decision()
				&& edge.inputPosition() == authority.inputPosition())
				sources.put(edge.producer(), Boolean.TRUE);
		for(var edge : model.analysis().logicalTransientInputsInCanonicalOrder())
			if(edge.targetRead() == alternative.decision()
				&& edge.logicalPosition() == authority.inputPosition())
				sources.put(edge.sourceWrite(), Boolean.TRUE);
		for(var edge : model.analysis().logicalFunctionInputsInCanonicalOrder())
			if(edge.targetRead() == alternative.decision()
				&& edge.logicalPosition() == authority.inputPosition())
				sources.put(edge.sourceArgument(), Boolean.TRUE);
		return sources.keySet().stream().sorted(java.util.Comparator.comparing(
			CompiledHopKey::normalizedSignature)).toList();
	}

	private static CandidateRealizationInputBinding binding(
		CandidateRealizationSupportClause clause, ExactPhysicalModel.InputAuthority authority,
		CompiledHopKey sourceDecision) {
		if(clause == null)
			return null;
		CandidateRealizationInputBinding match = null;
		for(CandidateRealizationInputBinding binding : clause.inputBindings())
			if(binding.inputPosition() == authority.inputPosition()
				&& (sourceDecision == null
					|| binding.source().rule().parentOccurrence() == sourceDecision)) {
				if(match != null && !match.equals(binding))
					throw new IllegalArgumentException("EXACT_SUPPLY_INPUT_BINDING_AMBIGUOUS");
				match = binding;
			}
		return match;
	}

	private static PlacementState targetState(ExactPhysicalModel.Alternative alternative,
		ExactPhysicalModel.InputAuthority authority) {
		CandidateInputState required = alternative.orderedInputs().get(authority.inputPosition());
		if(!required.present())
			return new PlacementState(org.apache.sysds.common.Types.ExecType.CP,
				org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT,
				null, alternative.state().shapeDependent());
		return new PlacementState(org.apache.sysds.common.Types.ExecType.FED,
			org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT,
			authority.expectedFType(), alternative.state().shapeDependent());
	}
}
